package com.answer.launcher.core;

public class LauncherConstants {
    // Base Info
    public static String MINECRAFT_VERSION = "undefined";
    public static int LAUNCHER_VERSION = 1;
    public static final String PACKAGE = "com.answer.launcher";


    public static String MINECRAFTPACKAGE = "com.mojang.minecraftpe";
    public static final String MINECRAFTACTIVITY = "com.mojang.minecraftpe.MainActivity";
    /**
     * Launcher-owned component that {@code AlterInstrumentation.newActivity()}
     * swaps for {@link #MINECRAFTACTIVITY}. It exists so the game's activity is
     * created by the loader built over the Minecraft APK (and therefore by that
     * loader's linker namespace, which can resolve the game's own libraries)
     * rather than by a launcher class that subclasses it.
     */
    public static final String GAME_HOST_ACTIVITY =
            "com.answer.launcher.ui.activity.GameHostActivity";
    public static final String MOJANG_RED = "#ef323d";
    public static final boolean isDebug = true;

    /**
     * Suffix of the isolated process that hosts the Minecraft runtime. The whole
     * assembly chain (guest LoadedApk, guest loader, hooks, native libraries)
     * lives there; the default process only draws UI and reads that runtime
     * through {@link #GAME_BRIDGE_ACTION}.
     */
    public static final String GAME_PROCESS_SUFFIX = ":game";

    /**
     * Explicit action of the in-app bridge service, declared in the manifest with
     * {@code android:process=":game"}. Not exported: both sides run under the same
     * application UID, so an implicit or exported service would only let other apps
     * poke at the runtime.
     */
    public static final String GAME_BRIDGE_ACTION =
            "com.answer.launcher.core.bridge.GameBridgeService";

}
