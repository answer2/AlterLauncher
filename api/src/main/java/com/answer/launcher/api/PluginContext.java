package com.answer.launcher.api;

/**
 * Capability-scoped context handed to {@link AlterPlugin#onLoad}. Replaces the
 * v1 {@code LauncherInfo} data bag: instead of a single object the plugin gets
 * narrow facades for instance state, events, hooks, logging, config and assets.
 */
public interface PluginContext {

    PluginManifest manifest();

    InstanceContext instance();

    EventManager events();

    HookFacade hooks();

    PluginLogger log();

    PluginConfig config();

    PluginResources resources();
}
