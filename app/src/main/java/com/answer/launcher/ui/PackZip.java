package com.answer.launcher.ui;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

import androidx.annotation.Nullable;

import com.answer.launcher.R;

import org.json.JSONArray;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Zip plumbing for the content screens (task #18/#19): turns a SAF-picked
 * {@code .mcworld} / {@code .mcpack} / {@code .mcaddon} into pack or world
 * folders under a game data root, and turns a folder back into an archive.
 *
 * <p>Imports always land in a cache staging directory first, because the archive
 * layout is not knowable up front: a {@code .mcpack} may be flat
 * ({@code manifest.json} at the zip root) or wrapped in a single folder, and a
 * {@code .mcaddon} carries a resource pack <em>and</em> a behaviour pack that have
 * to be split apart. Everything here is blocking file I/O and must be called off
 * the main thread.
 */
public final class PackZip {

    private static final String TAG = "PackZip";
    private static final int BUFFER = 8192;
    /** Refuse absurd archives instead of filling the data partition twice over. */
    private static final long MAX_ARCHIVE_BYTES = 1024L * 1024 * 1024;
    /** How deep we look for a {@code manifest.json} inside an archive. */
    private static final int MAX_SEARCH_DEPTH = 4;

    /** Outcome of an import: the destination folders created, or a human-readable error. */
    public static final class Result {
        public final List<File> imported;
        public final String error;

        private Result(List<File> imported, String error) {
            this.imported = imported == null ? new ArrayList<>() : imported;
            this.error = error;
        }

        public boolean success() {
            return error == null && !this.imported.isEmpty();
        }

        static Result ok(List<File> dirs) {
            return new Result(dirs, null);
        }

        static Result fail(String message) {
            return new Result(null, message);
        }
    }

    private PackZip() {
    }

    /**
     * Extracts every pack (or world) found in {@code uri} into {@code destRoot}.
     * Used by the world screen, where a {@code .mcworld} is a single payload.
     */
    public static Result importFromZip(Context ctx, Uri uri, File destRoot) {
        return importZip(ctx, uri, destRoot, null, null);
    }

    /**
     * Same as {@link #importFromZip(Context, Uri, File)} but routes each pack by
     * its manifest module type, so a {@code .mcaddon} drops its resource pack into
     * {@code resourceRoot} and its behaviour pack into {@code behaviorRoot}.
     */
    public static Result importFromAddon(Context ctx, Uri uri, File resourceRoot, File behaviorRoot) {
        return importZip(ctx, uri, resourceRoot, resourceRoot, behaviorRoot);
    }

    private static Result importZip(Context ctx, Uri uri, File fallbackRoot,
                                    File resourceRoot, File behaviorRoot) {
        if (uri == null || fallbackRoot == null) {
            return Result.fail(ctx.getString(R.string.content_no_instance));
        }
        File archive = null;
        File stage = null;
        try {
            archive = copyToCache(ctx, uri);
            if (archive == null || archive.length() == 0) {
                return Result.fail(ctx.getString(R.string.content_unreadable));
            }
            if (archive.length() > MAX_ARCHIVE_BYTES) {
                return Result.fail(ctx.getString(R.string.content_too_large));
            }
            stage = new File(ctx.getCacheDir(), "content-import-" + System.nanoTime());
            if (!stage.mkdirs()) {
                return Result.fail(ctx.getString(R.string.content_unreadable));
            }
            unzip(archive, stage);

            String defaultName = sanitize(baseName(displayName(ctx, uri)));
            List<File> roots = findPackRoots(stage);
            if (roots.isEmpty()) {
                return Result.fail(ctx.getString(R.string.content_import_empty));
            }
            List<File> written = new ArrayList<>();
            Set<String> taken = new HashSet<>();
            for (File root : roots) {
                JSONObject manifest = readManifest(root);
                File destRoot = routeFor(manifest, fallbackRoot, resourceRoot, behaviorRoot);
                String uuid = uuidOf(manifest);
                String name;
                if (uuid != null) {
                    // A manifest uuid is the pack's identity, so re-importing the same
                    // pack replaces it in place the way Minecraft does.
                    name = sanitize(uuid);
                } else {
                    // No identity to go by (a world folder): never clobber a neighbour.
                    name = unique(folderNameFor(root, stage, defaultName), taken, destRoot);
                }
                taken.add(name);
                File dest = new File(destRoot, name);
                if (dest.exists()) deleteRec(dest);
                //noinspection ResultOfMethodCallIgnored
                dest.getParentFile().mkdirs();
                copyRec(root, dest);
                written.add(dest);
            }
            return Result.ok(written);
        } catch (SecurityException e) {
            Log.w(TAG, "import refused by the saf provider", e);
            return Result.fail(ctx.getString(R.string.content_unreadable));
        } catch (IOException e) {
            Log.w(TAG, "import failed", e);
            return Result.fail(e.getMessage() == null ? ctx.getString(R.string.instance_unknown_error) : e.getMessage());
        } finally {
            if (archive != null) archive.delete();
            if (stage != null) deleteRec(stage);
        }
    }

    /** Zips {@code srcDir} (contents at the archive root) into the SAF target {@code dest}. */
    public static boolean exportToZip(Context ctx, File srcDir, Uri dest) {
        if (srcDir == null || dest == null || !srcDir.isDirectory()) return false;
        try (OutputStream os = ctx.getContentResolver().openOutputStream(dest, "w")) {
            if (os == null) return false;
            try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(os))) {
                zipRec(srcDir, srcDir, zos, 0);
            }
            return true;
        } catch (SecurityException e) {
            Log.w(TAG, "export refused", e);
            return false;
        } catch (IOException e) {
            Log.w(TAG, "export failed", e);
            return false;
        }
    }

    // ---------------------------------------------------------------- manifest

    /** {@code manifest.json} of a pack folder, or null when absent/malformed. */
    public static JSONObject readManifest(File dir) {
        File f = new File(dir, "manifest.json");
        if (!f.isFile()) return null;
        try (InputStream in = new BufferedInputStream(new FileInputStream(f))) {
            byte[] raw = readAll(in, 1024 * 1024);
            return new JSONObject(new String(raw, StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.d(TAG, "unreadable manifest in " + dir, e);
            return null;
        }
    }

    /** {@code header.uuid}, tolerant of the hyphen-less and absent variants. */
    public static String uuidOf(JSONObject manifest) {
        if (manifest == null) return null;
        JSONObject header = manifest.optJSONObject("header");
        String uuid = header == null ? null : header.optString("uuid", null);
        return uuid == null || uuid.trim().isEmpty() ? null : uuid.trim().toLowerCase(Locale.US);
    }

    /** {@code header.<field>} with a {@code modules[0].<field>} fallback. */
    public static String textField(JSONObject manifest, String field) {
        if (manifest == null) return null;
        JSONObject header = manifest.optJSONObject("header");
        String v = header == null ? null : header.optString(field, null);
        if (v == null || v.trim().isEmpty()) {
            JSONArray modules = manifest.optJSONArray("modules");
            JSONObject first = modules == null ? null : modules.optJSONObject(0);
            v = first == null ? null : first.optString(field, null);
        }
        return v == null || v.trim().isEmpty() ? null : v.trim();
    }

    /** {@code header.version} rendered as {@code 1.2.0}; arrays and scalars both occur. */
    public static String versionOf(JSONObject manifest) {
        if (manifest == null) return null;
        JSONObject header = manifest.optJSONObject("header");
        Object raw = header == null ? null : header.opt("version");
        if (raw instanceof JSONArray) {
            JSONArray arr = (JSONArray) raw;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
                if (i > 0) sb.append('.');
                sb.append(arr.optInt(i, 0));
            }
            return sb.length() == 0 ? null : sb.toString();
        }
        if (raw instanceof Number) return String.valueOf(raw);
        String s = header == null ? null : header.optString("version", null);
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    public static int moduleCount(JSONObject manifest) {
        if (manifest == null) return 0;
        JSONArray modules = manifest.optJSONArray("modules");
        return modules == null ? 0 : modules.length();
    }

    /** "resources" / "data" by module type, or null when the manifest says nothing. */
    private static String kindOf(JSONObject manifest) {
        if (manifest == null) return null;
        String self = textField(manifest, "type");
        if ("resources".equalsIgnoreCase(self)) return "resources";
        if ("data".equalsIgnoreCase(self)) return "data";
        JSONArray modules = manifest.optJSONArray("modules");
        if (modules != null) {
            for (int i = 0; i < modules.length(); i++) {
                JSONObject m = modules.optJSONObject(i);
                String type = m == null ? null : m.optString("type", null);
                if ("resources".equalsIgnoreCase(type)) return "resources";
                if ("data".equalsIgnoreCase(type)) return "data";
            }
        }
        return null;
    }

    private static File routeFor(JSONObject manifest, File fallback, File resourceRoot, File behaviorRoot) {
        if (resourceRoot == null || behaviorRoot == null) return fallback;
        String kind = kindOf(manifest);
        if ("resources".equals(kind)) return resourceRoot;
        if ("data".equals(kind)) return behaviorRoot;
        return fallback;
    }

    // ------------------------------------------------------------------ layout

    /**
     * Every folder in the staged archive that is a standalone payload. A payload is
     * either a directory holding a {@code manifest.json} (a pack, or an addon's
     * sub-pack) or — when the archive carries no manifest at all — the world folder
     * inside it. That is the {@code .mcworld} case, and archives of one come both
     * flat ({@code level.dat} at the zip root) and wrapped in a single folder.
     */
    private static List<File> findPackRoots(File stage) {
        List<File> out = new ArrayList<>();
        if (new File(stage, "manifest.json").isFile()) {
            out.add(stage);
            return out;
        }
        collectPackRoots(stage, out, 0);
        if (out.isEmpty()) {
            File only = singleChildDir(stage);
            if (only != null) out.add(only);
        }
        if (out.isEmpty() && hasContent(stage)) out.add(stage);
        return out;
    }

    /** The one and only directory of {@code dir} (when there are no loose files), else null. */
    @Nullable
    private static File singleChildDir(File dir) {
        File[] kids = dir.listFiles();
        if (kids == null || kids.length != 1) return null;
        return kids[0].isDirectory() ? kids[0] : null;
    }

    private static void collectPackRoots(File dir, List<File> out, int depth) {
        if (depth > MAX_SEARCH_DEPTH) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File kid : kids) {
            if (kid == null || !kid.isDirectory()) continue;
            if (new File(kid, "manifest.json").isFile()) {
                out.add(kid);
            } else {
                collectPackRoots(kid, out, depth + 1);
            }
        }
    }

    private static boolean hasContent(File dir) {
        File[] kids = dir.listFiles();
        if (kids == null) return false;
        for (File kid : kids) {
            if (kid.isFile()) return true;
            if (kid.isDirectory() && hasContent(kid)) return true;
        }
        return false;
    }

    private static String folderNameFor(File root, File stage, String defaultName) {
        if (!root.getAbsoluteFile().equals(stage.getAbsoluteFile())) return sanitize(root.getName());
        return sanitize(defaultName);
    }

    /** Two sub-packs of one addon can share a label; keep them apart. */
    private static String unique(String name, Set<String> taken, File destRoot) {
        String candidate = name;
        int n = 2;
        while (taken.contains(candidate) || new File(destRoot, candidate).exists()) {
            candidate = name + "-" + n++;
        }
        return candidate;
    }

    // -------------------------------------------------------------- zip i/o

    /** Copies a content Uri into a cache file; returns null when it cannot be read. */
    public static File copyToCache(Context ctx, Uri uri) throws IOException {
        File dest = new File(ctx.getCacheDir(), "content-" + System.nanoTime() + ".zip");
        try (InputStream in = ctx.getContentResolver().openInputStream(uri);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(dest))) {
            if (in == null) return null;
            byte[] buf = new byte[BUFFER];
            int read;
            while ((read = in.read(buf)) > 0) out.write(buf, 0, read);
        } catch (IOException | SecurityException e) {
            //noinspection ResultOfMethodCallIgnored
            dest.delete();
            throw e;
        }
        return dest;
    }

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

    private static void zipRec(File base, File dir, ZipOutputStream zos, int depth) throws IOException {
        if (depth > 16) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File kid : kids) {
            String rel = base.toPath().relativize(kid.toPath()).toString().replace('\\', '/');
            if (kid.isDirectory()) {
                ZipEntry e = new ZipEntry(rel + "/");
                zos.putNextEntry(e);
                zos.closeEntry();
                zipRec(base, kid, zos, depth + 1);
            } else if (kid.isFile()) {
                ZipEntry e = new ZipEntry(rel);
                zos.putNextEntry(e);
                try (InputStream in = new BufferedInputStream(new FileInputStream(kid))) {
                    byte[] buf = new byte[BUFFER];
                    int read;
                    while ((read = in.read(buf)) > 0) zos.write(buf, 0, read);
                }
                zos.closeEntry();
            }
        }
    }

    // ------------------------------------------------------------- file utils

    public static void copyRec(File src, File dest) throws IOException {
        if (src.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            dest.mkdirs();
            File[] kids = src.listFiles();
            if (kids == null) return;
            for (File kid : kids) copyRec(kid, new File(dest, kid.getName()));
            return;
        }
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (InputStream in = new BufferedInputStream(new FileInputStream(src));
             OutputStream out = new BufferedOutputStream(new FileOutputStream(dest))) {
            byte[] buf = new byte[BUFFER];
            int read;
            while ((read = in.read(buf)) > 0) out.write(buf, 0, read);
        }
    }

    /** Recursive delete. A symlink is unlinked, never followed into its target. */
    public static boolean deleteRec(File f) {
        if (f == null || !f.exists()) return true;
        if (f.isDirectory() && !java.nio.file.Files.isSymbolicLink(f.toPath())) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File kid : kids) deleteRec(kid);
            }
        }
        return f.delete();
    }

    /** True when {@code f} is a symlink (used to tell a globalPacks link from a copy). */
    public static boolean isSymlink(File f) {
        return f != null && java.nio.file.Files.isSymbolicLink(f.toPath());
    }

    public static long dirSize(File f) {
        if (f == null || !f.exists()) return 0L;
        if (f.isFile()) return f.length();
        if (isSymlink(f)) return 0L;
        long sum = 0L;
        File[] kids = f.listFiles();
        if (kids != null) for (File kid : kids) sum += dirSize(kid);
        return sum;
    }

    /**
     * Decodes a small preview bitmap (world.png / pack_icon.png) already sized for a
     * 64dp slot. Callers run this on a worker thread while building the list.
     */
    public static Bitmap decodeSampled(File image, int targetPx) {
        if (image == null || !image.isFile()) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(image.getAbsolutePath(), bounds);
            int sample = 1;
            while (bounds.outWidth / sample > targetPx * 2 && bounds.outHeight / sample > targetPx * 2
                    && sample <= 32) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            return BitmapFactory.decodeFile(image.getAbsolutePath(), opts);
        } catch (Exception e) {
            Log.d(TAG, "cannot decode " + image, e);
            return null;
        }
    }

    /** The file name behind a SAF content Uri, or null when the provider hides it. */
    public static String displayName(Context ctx, Uri uri) {
        if (uri == null) return null;
        if ("file".equals(uri.getScheme())) {
            String p = uri.getPath();
            return p == null ? null : new File(p).getName();
        }
        try (Cursor c = ctx.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (Exception e) {
            Log.d(TAG, "no display name for " + uri, e);
        }
        String last = uri.getLastPathSegment();
        return last == null ? null : new File(last).getName();
    }

    /** Lower-case extension without the dot, or "" when there is none. */
    public static String extension(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1).toLowerCase(Locale.US);
    }

    private static String baseName(String name) {
        if (name == null || name.isEmpty()) return "imported";
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return base.trim().isEmpty() ? "imported" : base.trim();
    }

    /** Strips everything a file system (or Minecraft) dislikes from a folder name. */
    public static String sanitize(String raw) {
        if (raw == null) return "imported";
        String s = raw.replaceAll("[^A-Za-z0-9._\\-\\u4e00-\\u9fff]", "_").replaceAll("^[. ]+", "");
        if (s.length() > 64) s = s.substring(0, 64);
        return s.isEmpty() ? "imported" : s;
    }

    private static byte[] readAll(InputStream in, int limit) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[BUFFER];
        int total = 0;
        int read;
        while ((read = in.read(buf)) > 0) {
            total += read;
            if (total > limit) throw new IOException("manifest too large");
            out.write(buf, 0, read);
        }
        return out.toByteArray();
    }
}
