// Bridge between the :game process (which owns the Minecraft runtime) and the
// UI process (which only displays state). Implemented by
// com.answer.launcher.core.bridge.GameBridgeService.
package com.answer.launcher.core.bridge;

import com.answer.launcher.core.bridge.IGameBridgeCallback;

interface IGameBridge {

    // "idle" | "assembling" | "ready" | "failed:<message>"
    String getRuntimeState();

    boolean isGameReady();

    // HookManager stats line: "installed=N failed=N missingTargets=N duplicatesSuppressed=N"
    String getHookStats();

    // Absolute paths under filesDir/plugin and filesDir/nativePlugin.
    List<String> getPluginFiles();

    List<String> getNativePluginFiles();

    // One entry per loaded plugin: "name|author|version|description", the shape of
    // PluginInfo.info(). PluginInfo is not Parcelable and its default field
    // initialiser reaches a stub-only class, so the state crosses as plain strings.
    List<String> getPluginInfos();

    String getMinecraftVersion();

    int getGamePid();

    // Copy an external file into the plugin directory of the runtime process.
    void importPluginFile(String absolutePath);

    void importNativePluginFile(String absolutePath);

    // Re-run PluginManager.loadPlugin() over the current plugin list.
    void reloadPlugins();

    void registerCallback(IGameBridgeCallback callback);

    void unregisterCallback(IGameBridgeCallback callback);
}
