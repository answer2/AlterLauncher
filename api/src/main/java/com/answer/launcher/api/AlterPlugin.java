package com.answer.launcher.api;

/**
 * v2 plugin lifecycle, mirroring PL_REGISTER_MOD's load/enable/disable/unload
 * so a native and a Java plugin share the same states (Unloaded/Loaded/Enabled,
 * exactly what pl::mod::NativeMod::State exposes).
 *
 * @see PluginState
 */
public interface AlterPlugin {

    void onLoad(PluginContext ctx);

    default void onEnable() {}

    default void onDisable() {}

    default void onUnload() {}
}
