package com.answer.launcher.api;

/** Mirrors pl::mod::NativeMod::State so a Java plugin and a native mod are described alike. */
public enum PluginState {
    UNLOADED,
    LOADED,
    ENABLED
}
