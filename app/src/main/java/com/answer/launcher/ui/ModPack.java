package com.answer.launcher.ui;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.Nullable;

import com.answer.launcher.R;
import com.answer.launcher.core.manager.Alternative;
import com.answer.launcher.core.manager.GameDataStore;
import com.answer.launcher.core.manager.IdentityStore;
import com.answer.launcher.core.manager.Instance;
import com.answer.launcher.core.manager.InstanceStore;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 整合包 (task #31): the launcher's own modpack container, and the only place that knows
 * how to read and write it.
 *
 * <p>A modpack is a zip carrying a root {@code manifest.json} plus up to four component
 * folders, each of which is a drop-in copy of what the instance tree already holds:
 *
 * <pre>
 * manifest.json                       {"name","version","author","description","mcVersion",
 *                                      "components":[{"type","count"}]}
 * worlds/&lt;worldId&gt;/…                 full minecraftWorlds subfolders (level.dat …)
 * resource_packs/&lt;packId&gt;/…          each with manifest.json + pack_icon.png
 * behavior_packs/&lt;packId&gt;/…
 * plugins/&lt;name&gt;.apk|.dex|.jar|.so   flat files, routed by extension on import
 * </pre>
 *
 * <p>The archive extension is {@value #EXTENSION}, but {@code .zip} is accepted too because
 * that is what most packing tools produce. {@code manifest.json} is descriptive, not
 * load-bearing: import trusts the component folders that are actually present, so a folder
 * zipped by hand still installs. Everything here is blocking file I/O and must be called
 * off the main thread.
 *
 * <p>Imports replace same-named entries in the target instance — the same contract
 * {@link PackZip} uses when a {@code .mcpack} is re-imported over the pack it already
 * installed — and never touch anything outside the four component destinations. Plugins are
 * the exception: they live in shared app storage rather than per instance, so a plugin in a
 * modpack is installed for every instance, matching how {@code PluginManager} and
 * {@code NativePluginManager} load them.
 */
public final class ModPack {

    private static final String TAG = "ModPack";
    private static final int BUFFER = 8192;
    /** Modpacks carry worlds, so the ceiling is looser than a single pack archive. */
    private static final long MAX_ARCHIVE_BYTES = 2048L * 1024 * 1024;
    /** Guards against a self-referential link tree turning the zip walk into a loop. */
    private static final int MAX_ZIP_DEPTH = 16;

    /** The archive extension the launcher writes (and offers in the pickers). */
    public static final String EXTENSION = "altpack";
    /** Format version written into every exported manifest; bumped only on a breaking change. */
    public static final String PACK_VERSION = "1";
    /** Fallback name when the instance has no label to borrow. */
    public static final String DEFAULT_NAME = "AlterPack";
    private static final String DEFAULT_AUTHOR = "AlterLauncher";
    private static final String DEFAULT_DESCRIPTION = "Exported by AlterLauncher";

    public static final String KIND_WORLDS = "worlds";
    public static final String KIND_RESOURCE = "resource_packs";
    public static final String KIND_BEHAVIOR = "behavior_packs";
    public static final String KIND_PLUGINS = "plugins";

    /** The component folders, in the order both import and export walk them. */
    public static final String[] KINDS =
            {KIND_WORLDS, KIND_RESOURCE, KIND_BEHAVIOR, KIND_PLUGINS};

    private static final String MANIFEST_PATH = "manifest.json";
    /** Written by {@code PluginManager}; {@code NativePluginManager} uses the other. */
    private static final String PLUGIN_DIR = "plugin";
    private static final String NATIVE_PLUGIN_DIR = "nativePlugin";

    private ModPack() {
    }

    /** Outcome of an import or export: how much of each component moved, or a readable error. */
    public static final class Result {
        public final Map<String, Integer> counts;
        public final String error;

        private Result(Map<String, Integer> counts, String error) {
            this.counts = counts == null ? new LinkedHashMap<>() : counts;
            this.error = error;
        }

        static Result ok(Map<String, Integer> counts) {
            return new Result(counts, null);
        }

        static Result fail(String message) {
            return new Result(null, message);
        }

        /** True when something actually moved and nothing failed. */
        public boolean success() {
            return error == null && total() > 0;
        }

        public int count(String kind) {
            Integer value = counts.get(kind);
            return value == null ? 0 : value;
        }

        public int total() {
            int sum = 0;
            for (Integer value : counts.values()) sum += value == null ? 0 : value;
            return sum;
        }
    }

    // ------------------------------------------------------------------ import

    /**
     * Installs a modpack archive into one instance. The archive is copied to the cache and
     * unpacked into a staging directory first (the layout is not knowable up front), the
     * components found there are moved into the target instance's matching folders, and the
     * staging tree is deleted whatever happens.
     *
     * @param slug the instance to install into; never implied, since an import is destructive
     */
    public static Result importPack(Context ctx, Uri zipUri, String slug) {
        if (zipUri == null) {
            return Result.fail(ctx.getString(R.string.content_unreadable));
        }
        if (slug == null || slug.isEmpty()) {
            return Result.fail(ctx.getString(R.string.content_no_instance));
        }
        File archive = null;
        File stage = null;
        try {
            archive = PackZip.copyToCache(ctx, zipUri);
            if (archive == null || archive.length() == 0) {
                return Result.fail(ctx.getString(R.string.content_unreadable));
            }
            if (archive.length() > MAX_ARCHIVE_BYTES) {
                return Result.fail(ctx.getString(R.string.content_too_large));
            }
            stage = new File(ctx.getCacheDir(), "modpack-" + System.nanoTime());
            if (!stage.mkdirs()) {
                return Result.fail(ctx.getString(R.string.content_unreadable));
            }
            unzip(archive, stage);
            // The manifest is descriptive only: what is on disk decides what gets installed.
            Log.i(TAG, "modpack manifest " + readManifest(stage));

            List<String> present = componentsFrom(stage);
            if (present.isEmpty()) {
                return Result.fail(ctx.getString(R.string.modpack_import_empty));
            }
            ensureComMojang(ctx, slug);

            Map<String, Integer> counts = emptyCounts();
            for (String kind : present) {
                counts.put(kind, installChildren(ctx, slug, kind, new File(stage, kind)));
            }
            if (countsTotal(counts) == 0) {
                return Result.fail(ctx.getString(R.string.modpack_import_empty));
            }
            return Result.ok(counts);
        } catch (SecurityException e) {
            Log.w(TAG, "import refused by the saf provider", e);
            return Result.fail(ctx.getString(R.string.content_unreadable));
        } catch (IOException e) {
            Log.w(TAG, "modpack import failed", e);
            return Result.fail(e.getMessage() == null
                    ? ctx.getString(R.string.instance_unknown_error) : e.getMessage());
        } finally {
            if (archive != null) {
                //noinspection ResultOfMethodCallIgnored
                archive.delete();
            }
            if (stage != null) PackZip.deleteRec(stage);
        }
    }

    /** Moves every child of one component folder into its destination; returns how many landed. */
    private static int installChildren(Context ctx, String slug, String kind, File src)
            throws IOException {
        File[] kids = src.listFiles();
        if (kids == null) return 0;
        File dest = KIND_PLUGINS.equals(kind) ? null : destinationFor(ctx, slug, kind);
        int copied = 0;
        for (File kid : kids) {
            if (kid == null) continue;
            if (KIND_PLUGINS.equals(kind)) {
                // plugins/ is flat and mixed: the extension decides which loader's directory
                // the file belongs to, exactly like the plugin screen's importer.
                File target = pluginTargetFor(ctx, kid);
                if (target == null) continue;
                PackZip.copyRec(kid, target);
                copied++;
                continue;
            }
            if (dest == null) continue;
            if (!kid.isDirectory()) continue;      // a world or a pack is a folder
            File out = new File(dest, kid.getName());
            // Same name = same content identity, so the archive replaces what is already
            // installed rather than leaving two copies of one world beside each other.
            // deleteRec never follows a link, so a global pack symlinked in here is only
            // unlinked — the shared copy in global_packs stays intact.
            if (out.exists()) PackZip.deleteRec(out);
            PackZip.copyRec(kid, out);
            copied++;
        }
        return copied;
    }

    /** The per-instance destination for a component, or null for the process-wide plugin kind. */
    private static File destinationFor(Context ctx, String slug, String kind) {
        switch (kind) {
            case KIND_WORLDS:
                return GameDataStore.ensure(GameDataStore.worldsFor(ctx, slug));
            case KIND_RESOURCE:
                return GameDataStore.ensure(GameDataStore.resourcePacksFor(ctx, slug));
            case KIND_BEHAVIOR:
                return GameDataStore.ensure(GameDataStore.behaviorPacksFor(ctx, slug));
            case KIND_PLUGINS:
            default:
                return null;
        }
    }

    /**
     * The game creates {@code games/com.mojang} on its first launch, so an instance that has
     * never been booted has none — and every {@code *For} path helper then reports null. A
     * modpack still has to land somewhere, so the standard tree is created up front. It is
     * the same path the engine would make, which keeps the two in agreement.
     */
    private static void ensureComMojang(Context ctx, String slug) {
        GameDataStore.ensure(
                new File(IdentityStore.instanceRoot(ctx, slug), "games/com.mojang"));
    }

    // ------------------------------------------------------------------ export

    /**
     * Writes the instance's content into the SAF target as a modpack archive. Components that
     * do not exist on disk are left out of both the zip and the manifest rather than appearing
     * as empty folders.
     */
    public static Result exportPack(Context ctx, String slug, Uri destUri) {
        if (destUri == null) {
            return Result.fail(ctx.getString(R.string.content_export_failed));
        }
        if (slug == null || slug.isEmpty()) {
            return Result.fail(ctx.getString(R.string.content_no_instance));
        }
        final File worlds = GameDataStore.worldsFor(ctx, slug);
        final File resources = GameDataStore.resourcePacksFor(ctx, slug);
        final File behaviors = GameDataStore.behaviorPacksFor(ctx, slug);
        final File plugins = pluginDir(ctx);
        final File nativePlugins = nativePluginDir(ctx);

        Map<String, Integer> counts = emptyCounts();
        counts.put(KIND_WORLDS, countFolders(worlds));
        counts.put(KIND_RESOURCE, countFolders(resources));
        counts.put(KIND_BEHAVIOR, countFolders(behaviors));
        counts.put(KIND_PLUGINS, countPlugins(plugins) + countPlugins(nativePlugins));

        OutputStream raw = null;
        try {
            raw = ctx.getContentResolver().openOutputStream(destUri, "w");
            if (raw == null) {
                return Result.fail(ctx.getString(R.string.content_export_failed));
            }
            try (ZipOutputStream zos = new ZipOutputStream(
                    new BufferedOutputStream(raw), StandardCharsets.UTF_8)) {
                putEntry(zos, MANIFEST_PATH,
                        buildManifest(ctx, slug, counts).toString().getBytes(StandardCharsets.UTF_8));
                zipTree(worlds, KIND_WORLDS, zos);
                zipTree(resources, KIND_RESOURCE, zos);
                zipTree(behaviors, KIND_BEHAVIOR, zos);
                // Both plugin roots share one component folder in the archive: the extension
                // is what tells the two loaders apart again on import.
                zipPlugins(plugins, zos);
                zipPlugins(nativePlugins, zos);
            }
            return Result.ok(counts);
        } catch (SecurityException e) {
            Log.w(TAG, "export refused", e);
            return Result.fail(ctx.getString(R.string.content_export_failed));
        } catch (IOException e) {
            Log.w(TAG, "modpack export failed", e);
            return Result.fail(e.getMessage() == null
                    ? ctx.getString(R.string.instance_unknown_error) : e.getMessage());
        } finally {
            if (raw != null) {
                try {
                    raw.close();
                } catch (IOException ignored) {
                    // Already closed by the zip stream; nothing left to flush.
                }
            }
        }
    }

    /** The manifest written at the archive root: identity, target game version and component counts. */
    private static JSONObject buildManifest(Context ctx, String slug, Map<String, Integer> counts) {
        JSONObject o = new JSONObject();
        Instance in = InstanceStore.get(ctx, slug);
        try {
            o.put("name", packName(in));
            o.put("version", PACK_VERSION);
            o.put("author", DEFAULT_AUTHOR);
            o.put("description", DEFAULT_DESCRIPTION);
            if (in != null && in.versionName != null && !in.versionName.isEmpty()) {
                o.put("mcVersion", in.versionName);
            }
            JSONArray components = new JSONArray();
            for (String kind : KINDS) {
                int n = countOf(counts, kind);
                if (n <= 0) continue;
                JSONObject entry = new JSONObject();
                entry.put("type", kind);
                entry.put("count", n);
                components.put(entry);
            }
            o.put("components", components);
        } catch (JSONException e) {
            Log.w(TAG, "cannot build the modpack manifest", e);
        }
        return o;
    }

    private static String packName(@Nullable Instance in) {
        if (in == null) return DEFAULT_NAME;
        if (in.label != null && !in.label.trim().isEmpty()) return in.label.trim();
        return in.versionName == null || in.versionName.isEmpty()
                ? DEFAULT_NAME : in.versionName;
    }

    // ---------------------------------------------------------------- structure

    /** The modpack's root {@code manifest.json}; null when absent or malformed. */
    public static JSONObject readManifest(File packRoot) {
        return packRoot == null ? null : PackZip.readManifest(packRoot);
    }

    /** The component folders actually carrying something, in {@link #KINDS} order. */
    public static List<String> componentsFrom(File stagingRoot) {
        List<String> out = new ArrayList<>();
        if (stagingRoot == null) return out;
        for (String kind : KINDS) {
            File dir = new File(stagingRoot, kind);
            if (dir.isDirectory() && hasEntries(dir)) out.add(kind);
        }
        return out;
    }

    /** True when the archive is a modpack by extension, i.e. a zip under either name. */
    public static boolean isModPackFile(@Nullable String name) {
        if (name == null) return true;          // the provider hid the name; let the archive speak
        String ext = PackZip.extension(name);
        return ext.isEmpty() || EXTENSION.equals(ext) || "zip".equals(ext);
    }

    /** The suggested file name for an export: the instance label, or {@value #DEFAULT_NAME}. */
    public static String suggestedFileName(Context ctx, String slug) {
        return PackZip.sanitize(packName(InstanceStore.get(ctx, slug))) + "." + EXTENSION;
    }

    private static boolean hasEntries(File dir) {
        File[] kids = dir.listFiles();
        return kids != null && kids.length > 0;
    }

    // -------------------------------------------------------------- plugin roots

    /**
     * The base the two plugin loaders resolve, so an import lands where the game looks:
     * {@link Alternative#getHostFilesDir()} once the runtime has assembled it, and the
     * launcher's own {@code files/} directory before that — the same fallback order
     * {@code PluginManager} and {@code NativePluginManager} use internally.
     */
    private static File hostFilesDir(Context ctx) {
        File base = null;
        try {
            base = Alternative.getManager().getHostFilesDir();
        } catch (Throwable ignored) {
            // The launcher process may not have assembled the runtime, which is fine.
        }
        return base == null ? ctx.getFilesDir() : base;
    }

    /** {@code files/plugin/}: dex / apk / jar, loaded by {@code PluginManager}. */
    public static File pluginDir(Context ctx) {
        return new File(hostFilesDir(ctx), PLUGIN_DIR);
    }

    /** {@code files/nativePlugin/}: {@code .so}, loaded by {@code NativePluginManager}. */
    public static File nativePluginDir(Context ctx) {
        return new File(hostFilesDir(ctx), NATIVE_PLUGIN_DIR);
    }

    /** The plugin directory for one file, by extension; null when the extension means nothing. */
    private static File pluginTargetFor(Context ctx, File source) {
        if (!isPluginFile(source)) return null;
        String ext = PackZip.extension(source.getName());
        File dir = "so".equals(ext) ? nativePluginDir(ctx) : pluginDir(ctx);
        GameDataStore.ensure(dir);
        return uniqueIn(dir, source.getName());
    }

    /**
     * The suffixes the two loaders actually match. A switched-off plugin is named
     * {@code foo.apk.off} (see {@code PluginAdapter#OFF_SUFFIX}), so it is deliberately not
     * carried into an archive: an export should hold what the game would load.
     */
    private static boolean isPluginFile(File file) {
        if (file == null || !file.isFile()) return false;
        String ext = PackZip.extension(file.getName());
        return ext.equals("apk") || ext.equals("dex") || ext.equals("jar") || ext.equals("so");
    }

    /** Never overwrite: an already-installed plugin of the same name gets a numbered suffix. */
    private static File uniqueIn(File dir, String name) {
        File dest = new File(dir, name);
        if (!dest.exists()) return dest;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String suffix = dot > 0 ? name.substring(dot) : "";
        int n = 2;
        while (dest.exists()) dest = new File(dir, stem + "-" + n++ + suffix);
        return dest;
    }

    // ------------------------------------------------------------------ zip i/o

    /** Unpacked from {@link PackZip}: same guards, since a modpack archive is just a bigger zip. */
    private static void unzip(File archive, File destRoot) throws IOException {
        String rootPath = destRoot.getCanonicalPath();
        try (ZipInputStream zis = new ZipInputStream(
                new BufferedInputStream(new FileInputStream(archive)), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            byte[] buf = new byte[BUFFER];
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                if (name.isEmpty() || name.startsWith("/") || name.contains("../")) {
                    zis.closeEntry();
                    continue;
                }
                File out = new File(destRoot, name);
                if (!out.getCanonicalPath().startsWith(rootPath + File.separator)) {
                    zis.closeEntry();
                    continue;
                }
                if (entry.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    out.mkdirs();
                } else {
                    File parent = out.getParentFile();
                    if (parent != null) parent.mkdirs();
                    try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
                        int read;
                        while ((read = zis.read(buf)) > 0) os.write(buf, 0, read);
                    }
                }
                zis.closeEntry();
            }
        }
    }

    /** Writes {@code root}'s contents under the {@code kind/} folder of the archive. */
    private static void zipTree(File root, String kind, ZipOutputStream zos) throws IOException {
        if (root == null || !root.isDirectory()) return;
        zipRec(root, root, kind + "/", zos, 0);
    }

    /**
     * Writes the loadable files of one plugin root flat into {@code plugins/}. The two roots
     * share the component folder because the extension — not the folder — is what the import
     * routes on, and anything that is not a plugin (a leftover {@code .off} toggle marker, say)
     * is left out rather than carried as one.
     */
    private static void zipPlugins(File root, ZipOutputStream zos) throws IOException {
        if (root == null || !root.isDirectory()) return;
        File[] kids = root.listFiles();
        if (kids == null) return;
        for (File kid : kids) {
            if (!isPluginFile(kid)) continue;
            try (InputStream in = new BufferedInputStream(new FileInputStream(kid))) {
                putEntry(zos, KIND_PLUGINS + "/" + kid.getName(), readAll(in));
            }
        }
    }

    private static void zipRec(File base, File dir, String prefix, ZipOutputStream zos, int depth)
            throws IOException {
        if (depth > MAX_ZIP_DEPTH) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File kid : kids) {
            if (kid == null) continue;
            String rel = base.toPath().relativize(kid.toPath()).toString().replace('\\', '/');
            String name = prefix + rel;
            if (kid.isDirectory()) {
                putEntry(zos, name + "/", null);
                zipRec(base, kid, prefix, zos, depth + 1);
            } else if (kid.isFile()) {
                try (InputStream in = new BufferedInputStream(new FileInputStream(kid))) {
                    putEntry(zos, name, readAll(in));
                }
            }
        }
    }

    private static void putEntry(ZipOutputStream zos, String name, byte[] payload)
            throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        if (payload != null) zos.write(payload);
        zos.closeEntry();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[BUFFER];
        int read;
        while ((read = in.read(buf)) > 0) out.write(buf, 0, read);
        return out.toByteArray();
    }

    // ------------------------------------------------------------------- counts

    private static Map<String, Integer> emptyCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String kind : KINDS) counts.put(kind, 0);
        return counts;
    }

    private static int countOf(Map<String, Integer> counts, String kind) {
        Integer value = counts.get(kind);
        return value == null ? 0 : value;
    }

    private static int countsTotal(Map<String, Integer> counts) {
        int sum = 0;
        for (Integer value : counts.values()) sum += value == null ? 0 : value;
        return sum;
    }

    /** One component per subfolder: worlds and packs are folders, not files. */
    private static int countFolders(File dir) {
        if (dir == null || !dir.isDirectory()) return 0;
        File[] kids = dir.listFiles(File::isDirectory);
        return kids == null ? 0 : kids.length;
    }

    /** How many of one plugin root's files are loadable, so the manifest counts what import takes. */
    private static int countPlugins(File dir) {
        if (dir == null || !dir.isDirectory()) return 0;
        File[] kids = dir.listFiles(ModPack::isPluginFile);
        return kids == null ? 0 : kids.length;
    }
}
