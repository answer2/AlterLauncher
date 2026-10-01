package com.answer.launcher.core.manager;

import android.content.Context;

import com.answer.launcher.api.LoadResult;
import com.answer.launcher.api.LoadStatus;
import com.answer.launcher.api.PluginInfo;
import com.answer.launcher.core.plugin.PluginLoader;
import com.answer.launcher.core.tool.FileUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plugin host. v2 (levi-compat-design.md §5): {@link #loadPlugin()} delegates
 * to {@link PluginLoader}, which reads alter-plugin.json, loads every plugin
 * through the shared :api classloader, and returns one {@link LoadResult} per
 * source. The legacy methods ({@link #rescan()}, {@link #addPlugin(String)},
 * {@link #getPluginFiles()}, {@link #clear()}, {@link #getInfos()}) stay for
 * the UI and external callers; {@link #getInfos()} is now best-effort, derived
 * from the last load report.
 */
public class PluginManager {

    private static volatile PluginManager manager;

    private final Context mContext;
    private final PluginLoader loader;
    private final List<String> plugins = new ArrayList<>();
    private List<LoadResult> lastResults = new ArrayList<>();

    private PluginManager(Context context) {
        this.mContext = context;
        this.loader = new PluginLoader(context);
        rescan();
    }

    public static PluginManager getManager(Context context) {
        if (manager == null) {
            synchronized (PluginManager.class) {
                if (manager == null) {
                    manager = new PluginManager(context);
                }
            }
        }
        return manager;
    }

    /**
     * Re-read the active installation's plugin directory into {@link #plugins}. The directory is
     * per-instance (task #35), so a rescan after the active instance changes picks up that
     * installation's set, and the UI process can drop a file there at any time.
     */
    public void rescan() {
        plugins.clear();
        File[] files = PluginPaths.activeDexDir(mContext).listFiles();
        if (files == null) return;
        for (File file : files) {
            if (!file.isDirectory()
                    && (file.getName().endsWith(".dex") || file.getName().endsWith(".apk"))) {
                plugins.add(file.getAbsolutePath());
            }
        }
    }

    public void addPlugin(String path) {
        try {
            File file = new File(path);
            if (file.exists()) {
                File dir = PluginPaths.activeDexDir(mContext);
                if (!dir.exists()) dir.mkdirs();
                File dest = new File(dir, file.getName());
                FileUtil.copy(file, dest.getAbsolutePath());
                plugins.add(dest.getAbsolutePath());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public List<String> getPluginFiles() {
        return this.plugins;
    }

    public void clear() {
        File[] files = PluginPaths.activeDexDir(mContext).listFiles();
        if (files != null) {
            for (File file : files) file.delete();
        }
        plugins.clear();
        lastResults.clear();
    }

    /**
     * Compat entry for {@code SplashScreenHook} / {@code GameBridgeService.reloadPlugins}.
     * Loads every discovered plugin; structured results are in {@link #getLastResults()}.
     */
    public void loadPlugin() {
        if (MinecraftManager.getManager().getActivity() == null) {
            throw new NullPointerException("Minecraft MainActivity is null");
        }
        loadPlugins();
    }

    /** v2 load — returns one {@link LoadResult} per source for the UI. */
    public synchronized List<LoadResult> loadPlugins() {
        lastResults = loader.load();
        return lastResults;
    }

    public List<LoadResult> getLastResults() {
        return lastResults;
    }

    public PluginLoader getLoader() {
        return loader;
    }

    /** v1 compat: derived from the last load report (best-effort). */
    public Map<String, PluginInfo> getInfos() {
        Map<String, PluginInfo> infos = new LinkedHashMap<>();
        for (LoadResult r : lastResults) {
            if (r.status == LoadStatus.INSTALLED && r.manifest != null) {
                String id = r.manifest.id.isEmpty() ? r.source : r.manifest.id;
                infos.put(id, new PluginInfo(r.manifest.name, r.manifest.author,
                        r.manifest.version, r.manifest.id).setSupportVersion(r.manifest.minGame));
            }
        }
        return infos;
    }
}
