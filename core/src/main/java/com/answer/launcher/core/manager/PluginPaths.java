package com.answer.launcher.core.manager;

import android.content.Context;

import java.io.File;

/**
 * Where the launcher keeps its two plugin kinds, resolved per installation (task #35).
 *
 * <p>Dex/apk plugins (loaded by {@link PluginManager}) and native {@code .so} plugins (loaded by
 * {@link NativePluginManager}) used to sit in one shared pair of directories, so a plugin written
 * for one Minecraft version was force-loaded into every other one. They now live under the
 * installation that owns them — {@code instances/<id>/plugins/{dex,native}} — beside that
 * instance's {@code mods/}, so each version carries its own set and switching the active instance
 * switches the plugins.
 *
 * <p>With no active installation — the system Minecraft booted without an imported instance — the
 * legacy shared directories are still used. That keeps every plugin that worked before working
 * now, and gives the no-instance case a place to put files.
 */
public final class PluginPaths {

    private PluginPaths() {
    }

    /** Legacy/shared dex root — also the target when no instance is active. */
    public static File globalDex(Context ctx) {
        return new File(hostFiles(ctx), "plugin");
    }

    /** Legacy/shared native root — also the target when no instance is active. */
    public static File globalNative(Context ctx) {
        return new File(hostFiles(ctx), "nativePlugin");
    }

    /** The dex plugin dir for one installation; {@code null}/empty id means the shared dir. */
    public static File dexDir(Context ctx, String instanceId) {
        return instanceId == null || instanceId.isEmpty()
                ? globalDex(ctx)
                : new File(InstanceStore.dir(ctx, instanceId), "plugins/dex");
    }

    /** The native plugin dir for one installation; {@code null}/empty id means the shared dir. */
    public static File nativeDir(Context ctx, String instanceId) {
        return instanceId == null || instanceId.isEmpty()
                ? globalNative(ctx)
                : new File(InstanceStore.dir(ctx, instanceId), "plugins/native");
    }

    /** The dex dir for the installation that is (or is about to be) booted. */
    public static File activeDexDir(Context ctx) {
        return dexDir(ctx, InstanceStore.getActive(ctx));
    }

    /** The native dir for the installation that is (or is about to be) booted. */
    public static File activeNativeDir(Context ctx) {
        return nativeDir(ctx, InstanceStore.getActive(ctx));
    }

    /** The same base the plugin managers resolve while the runtime is being assembled. */
    private static File hostFiles(Context ctx) {
        File base = null;
        try {
            base = Alternative.getManager().getHostFilesDir();
        } catch (Throwable ignored) {
            // The launcher process may not have assembled the runtime yet.
        }
        return base != null ? base : ctx.getFilesDir();
    }
}
