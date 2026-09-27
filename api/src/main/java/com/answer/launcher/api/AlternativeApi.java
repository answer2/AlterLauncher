package com.answer.launcher.api;

import android.util.Log;

import java.lang.reflect.Method;

/**
 * v1 plugin entry, kept as a compat shim (levi-compat-design.md §5.6).
 *
 * <p>An existing plugin that implements {@link PluginInit} still resolves here.
 * The init-field bug is fixed: the v2 {@code PluginLoader} passes the real entry
 * class name in {@link LauncherInfo#getInit()} (discovered by scanning the dex
 * for a {@link PluginInit} implementer), so {@link #init(LauncherInfo)} loads
 * that class instead of a non-existent one named after the file stem. The
 * handshake still matches {@code PluginInit} by name (a plugin may carry its
 * own :api copy), and logs a deprecation nudging migration to
 * {@code alter-plugin.json} + {@link AlterPlugin}.
 */
public class AlternativeApi {

    private static final String TAG = "AlternativeApi";

    /**
     * v1 handshake. Public so the v2 {@code PluginLoader} can call it directly
     * for manifest-less plugins that still implement {@link PluginInit}.
     *
     * @param launcherInfo carries the real entry class name + the plugin's dex classloader
     * @return the plugin's {@link PluginInfo}, or null if it could not load
     */
    public static PluginInfo init(LauncherInfo launcherInfo) {
        if (launcherInfo == null) return null;
        try {
            Class<?> clazz = Class.forName(launcherInfo.getInit(), true, launcherInfo.getClassLoader());
            if (checkIsPluginInit(clazz)) {
                PluginInit object = (PluginInit) clazz.newInstance();
                object.load(launcherInfo);
                PluginInfo info = object.initInfo();
                Log.w(TAG, "loaded v1 plugin " + launcherInfo.getInit()
                        + " via the compat shim; migrate to alter-plugin.json + AlterPlugin (api v2)");
                return info == null ? new PluginInfo() : info;
            }
            Log.w(TAG, launcherInfo.getInit() + " is not a PluginInit; skipping v1 shim");
        } catch (Exception e) {
            Log.e(TAG, "Error loading " + launcherInfo.getInit(), e);
        }
        return null;
    }

    /*
     * The interface is matched BY NAME on purpose: a plugin dex may carry its
     * own copy of PluginInit loaded by the plugin's ClassLoader, so it is a
     * different Class object from the one in this module and `instanceof` /
     * `==` on the Class would reject a valid plugin.
     */
    private static boolean checkIsPluginInit(Class<?> clazz) {
        return implementsByName(clazz, PluginInit.class.getName());
    }

    private static boolean implementsByName(Class<?> clazz, String name) {
        if (clazz == null) return false;
        for (Class<?> interfaced : clazz.getInterfaces()) {
            if (interfaced.getName().equals(name)) return true;
            if (implementsByName(interfaced, name)) return true;
        }
        return implementsByName(clazz.getSuperclass(), name);
    }

    /**
     * Registers a surface redraw callback. v2 plugins should use
     * {@code PluginContext.events()} instead; this is kept for v1 callers and
     * reaches into core by reflection because the API module has no
     * compile-time contract with the implementation.
     */
    public static void addCallback(SurfaceCallback callback) {
        if (callback != null) {
            try {
                Class<?> clazz = Class.forName("com.answer.launcher.core.manager.MinecraftManager");
                Method manager = clazz.getDeclaredMethod("getManager");
                Method add = clazz.getDeclaredMethod("addCallbacks", SurfaceCallback.class);
                add.invoke(manager.invoke(null), callback);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
