package com.answer.launcher.core.plugin;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import com.answer.launcher.api.AlterPlugin;
import com.answer.launcher.api.LoadResult;
import com.answer.launcher.api.LauncherInfo;
import com.answer.launcher.api.PluginInfo;
import com.answer.launcher.api.PluginInit;
import com.answer.launcher.api.PluginManifest;
import com.answer.launcher.api.PluginResources;
import com.answer.launcher.api.PluginState;
import com.answer.launcher.core.LauncherConstants;
import com.answer.launcher.core.manager.MinecraftManager;
import com.answer.launcher.core.manager.PluginPaths;

import dalvik.system.DexClassLoader;
import dalvik.system.DexFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * v2 plugin loader (levi-compat-design.md §5).
 *
 * <p>One shared API classloader: every plugin's {@link DexClassLoader} parent is
 * this class's loader, which already defines {@code :api} (core depends on it),
 * so {@link AlterPlugin}, {@link com.answer.launcher.api.PluginContext} etc.
 * resolve through the parent and {@code instanceof} / generics / default methods
 * work — no more name matching. The v1
 * {@link com.answer.launcher.api.AlternativeApi#init(LauncherInfo)} signature is
 * kept as a compat shim and is fed the real entry class name (discovered by
 * scanning the dex for a {@link PluginInit} implementer), not the file stem.
 */
public final class PluginLoader {

    private static final String TAG = "PluginLoader";
    private static final String MANIFEST_ASSET = "assets/alter-plugin.json";

    private final Context context;
    private final File optimizedDir;
    private final Map<String, LoadedPlugin> loaded = new LinkedHashMap<>();

    public PluginLoader(Context context) {
        this.context = context;
        this.optimizedDir = context.getDir("plugin", Context.MODE_PRIVATE);
    }

    /** The active installation's dex plugin dir (task #35); the shared dir when none is active. */
    public File getPluginDir() {
        return PluginPaths.activeDexDir(context);
    }

    /** Loose {@code .dex} / {@code .apk} files in the plugin dir. */
    public List<File> scan() {
        List<File> out = new ArrayList<>();
        File[] files = getPluginDir().listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (f.isFile() && (f.getName().endsWith(".dex") || f.getName().endsWith(".apk"))) {
                out.add(f);
            }
        }
        return out;
    }

    /**
     * Load (or reload) every discovered plugin. Unloads the previous set first so
     * a reload is clean. Returns one {@link LoadResult} per source for the UI.
     */
    public List<LoadResult> load() {
        for (LoadedPlugin lp : new ArrayList<>(loaded.values())) {
            try {
                lp.plugin.onUnload();
            } catch (Throwable t) {
                Log.w(TAG, "onUnload(" + lp.manifest.id + ") threw", t);
            }
            lp.state = PluginState.UNLOADED;
        }
        loaded.clear();
        PluginEventBus.clear();

        List<LoadResult> results = new ArrayList<>();
        for (File source : scan()) {
            results.add(loadOne(source));
        }
        return results;
    }

    private LoadResult loadOne(File source) {
        DexClassLoader loader;
        try {
            loader = new DexClassLoader(source.getAbsolutePath(),
                    optimizedDir.getAbsolutePath(), null, getClass().getClassLoader());
        } catch (Throwable t) {
            return LoadResult.failed(source.getName(), null, "DexClassLoader failed: " + t.getMessage());
        }

        PluginManifest manifest = readManifest(source);
        if (manifest == null) {
            // No manifest — fall back to the v1 shim, which scans the dex for a
            // PluginInit implementer and feeds it the real entry class name.
            return loadV1Compat(source, loader);
        }
        if (manifest.api != 2) {
            return LoadResult.skipped(source.getName(), manifest, "unsupported api " + manifest.api);
        }
        if (!manifest.enabled) {
            return LoadResult.skipped(source.getName(), manifest, "disabled in manifest");
        }
        if (!manifest.matchesGameVersion(LauncherConstants.MINECRAFT_VERSION)) {
            return LoadResult.skipped(source.getName(), manifest,
                    "game " + LauncherConstants.MINECRAFT_VERSION + " outside [" + manifest.minGame + "," + manifest.maxGame + "]");
        }
        if (manifest.entry == null || manifest.entry.isEmpty()) {
            return LoadResult.failed(source.getName(), manifest, "manifest has no entry class");
        }

        Class<?> entryClass;
        try {
            entryClass = Class.forName(manifest.entry, false, loader);
        } catch (Throwable t) {
            return LoadResult.failed(source.getName(), manifest, "entry not found: " + manifest.entry);
        }
        // Resolved through the shared :api parent — instanceof now works.
        if (!AlterPlugin.class.isAssignableFrom(entryClass)) {
            // A manifest-bearing v1 plugin: hand to the shim.
            return loadV1Compat(source, loader);
        }

        AlterPlugin plugin;
        try {
            plugin = (AlterPlugin) entryClass.getDeclaredConstructor().newInstance();
        } catch (Throwable t) {
            return LoadResult.failed(source.getName(), manifest,
                    "cannot instantiate " + manifest.entry + ": " + t.getMessage());
        }

        String id = manifest.id.isEmpty() ? source.getName() : manifest.id;
        File workDir = new File(getPluginDir(), id);
        workDir.mkdirs();
        PluginResources res = buildResources(source);
        PluginContextImpl ctx = new PluginContextImpl(manifest, workDir, res);
        try {
            plugin.onLoad(ctx);
            loaded.put(id, new LoadedPlugin(manifest, plugin, ctx, PluginState.LOADED));
            return LoadResult.installed(source.getName(), manifest);
        } catch (Throwable t) {
            return LoadResult.failed(source.getName(), manifest, "onLoad threw: " + t.getMessage());
        }
    }

    @SuppressWarnings("deprecation")
    private LoadResult loadV1Compat(File source, DexClassLoader loader) {
        String entryName = findPluginInitEntry(source, loader);
        if (entryName == null) {
            return LoadResult.skipped(source.getName(), null, "no manifest and no PluginInit entry in dex");
        }
        PluginInfo info = com.answer.launcher.api.AlternativeApi.init(new LauncherInfo(
                MinecraftManager.getManager().getActivity(),
                LauncherConstants.LAUNCHER_VERSION,
                LauncherConstants.MINECRAFT_VERSION,
                entryName, loader));
        if (info == null) {
            return LoadResult.failed(source.getName(), null, "v1 shim returned null (entry " + entryName + ")");
        }
        return LoadResult.installed(source.getName(), v1Manifest(info, entryName));
    }

    private static PluginManifest v1Manifest(PluginInfo info, String entry) {
        return new PluginManifest(1, info.getName(), info.getName(), info.getVersion(),
                info.getAuthor(), entry, "game", "", "", true);
    }

    /** Scan the dex for a class implementing PluginInit (shared-loader instanceof first). */
    @SuppressWarnings("deprecation")
    private String findPluginInitEntry(File source, DexClassLoader loader) {
        DexFile df = null;
        try {
            df = new DexFile(source);
            Enumeration<String> entries = df.entries();
            while (entries.hasMoreElements()) {
                String cn = entries.nextElement();
                if (cn.startsWith("android.") || cn.startsWith("java.")
                        || cn.startsWith("kotlin") || cn.startsWith("com.answer.launcher.api.")) {
                    continue;
                }
                Class<?> c;
                try {
                    c = Class.forName(cn, false, loader);
                } catch (Throwable t) {
                    continue;
                }
                if (PluginInit.class.isAssignableFrom(c)) return cn;
                if (implementsByName(c, PluginInit.class.getName())) return cn;
            }
        } catch (Throwable t) {
            Log.w(TAG, "DexFile scan failed for " + source.getName() + ": " + t);
        } finally {
            if (df != null) try { df.close(); } catch (Exception e) { /* ignore */ }
        }
        return null;
    }

    private static boolean implementsByName(Class<?> clazz, String name) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Class<?> i : c.getInterfaces()) {
                if (i.getName().equals(name)) return true;
                if (implementsByName(i, name)) return true;
            }
        }
        return false;
    }

    private PluginManifest readManifest(File source) {
        if (source.getName().endsWith(".dex")) {
            String stem = source.getName().substring(0, source.getName().length() - 4);
            File sidecar = new File(source.getParentFile(), stem + ".json");
            if (sidecar.exists()) {
                try {
                    return PluginManifest.fromJson(readFile(sidecar));
                } catch (Throwable t) {
                    Log.w(TAG, "sidecar manifest unreadable: " + sidecar + " — " + t);
                }
            }
        }
        if (source.getName().endsWith(".apk")) {
            try (ZipFile zip = new ZipFile(source)) {
                ZipEntry e = zip.getEntry(MANIFEST_ASSET);
                if (e != null) {
                    try (InputStream in = zip.getInputStream(e)) {
                        return PluginManifest.fromJson(readStream(in));
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "APK manifest read failed for " + source.getName() + " — " + t);
            }
        }
        return null;
    }

    private PluginResources buildResources(File source) {
        if (!source.getName().endsWith(".apk")) return new PluginResourcesImpl(null);
        try {
            AssetManager am = AssetManager.class.getDeclaredConstructor().newInstance();
            Method addAssetPath = AssetManager.class.getMethod("addAssetPath", String.class);
            addAssetPath.setAccessible(true);
            addAssetPath.invoke(am, source.getAbsolutePath());
            return new PluginResourcesImpl(am);
        } catch (Throwable t) {
            // addAssetPath is a hidden API; on a build without the bypass it stays null
            // and the plugin's open() will throw, which is the honest failure mode.
            return new PluginResourcesImpl(null);
        }
    }

    private static String readFile(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            return readStream(in);
        }
    }

    private static String readStream(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }

    // --- lifecycle (UI calls these) ---

    public void enable(String id) {
        LoadedPlugin lp = loaded.get(id);
        if (lp != null && lp.state != PluginState.ENABLED) {
            try { lp.plugin.onEnable(); lp.state = PluginState.ENABLED; }
            catch (Throwable t) { Log.w(TAG, "onEnable(" + id + ") threw", t); }
        }
    }

    public void disable(String id) {
        LoadedPlugin lp = loaded.get(id);
        if (lp != null && lp.state == PluginState.ENABLED) {
            try { lp.plugin.onDisable(); lp.state = PluginState.LOADED; }
            catch (Throwable t) { Log.w(TAG, "onDisable(" + id + ") threw", t); }
        }
    }

    public void unload(String id) {
        LoadedPlugin lp = loaded.remove(id);
        if (lp != null) {
            try { lp.plugin.onUnload(); } catch (Throwable t) { Log.w(TAG, "onUnload(" + id + ") threw", t); }
            lp.state = PluginState.UNLOADED;
        }
    }

    public Collection<LoadedPlugin> getLoaded() {
        return loaded.values();
    }

    public static final class LoadedPlugin {
        public final PluginManifest manifest;
        public final AlterPlugin plugin;
        public final PluginContextImpl context;
        public PluginState state;

        LoadedPlugin(PluginManifest manifest, AlterPlugin plugin, PluginContextImpl context, PluginState state) {
            this.manifest = manifest;
            this.plugin = plugin;
            this.context = context;
            this.state = state;
        }
    }
}
