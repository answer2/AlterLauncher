package com.answer.launcher.core.plugin;

import com.answer.launcher.api.EventManager;
import com.answer.launcher.api.HookFacade;
import com.answer.launcher.api.InstanceContext;
import com.answer.launcher.api.PluginConfig;
import com.answer.launcher.api.PluginContext;
import com.answer.launcher.api.PluginLogger;
import com.answer.launcher.api.PluginManifest;
import com.answer.launcher.api.PluginResources;
import java.io.File;

final class PluginContextImpl implements PluginContext {

    private final PluginManifest manifest;
    private final InstanceContext instance;
    private final EventManager events;
    private final HookFacade hooks;
    private final PluginLogger log;
    private final PluginConfig config;
    private final PluginResources resources;

    PluginContextImpl(PluginManifest manifest, File pluginDir, PluginResources resources) {
        this.manifest = manifest;
        this.instance = new InstanceContextImpl(pluginDir);
        this.events = new EventManagerImpl();
        this.log = new PluginLoggerImpl(manifest.id.isEmpty() ? manifest.name : manifest.id);
        this.hooks = new HookFacadeImpl(log);
        this.config = new PluginConfigImpl(new File(pluginDir, "config.json"));
        this.resources = resources != null ? resources : new PluginResourcesImpl(null);
    }

    @Override public PluginManifest manifest() { return manifest; }
    @Override public InstanceContext instance() { return instance; }
    @Override public EventManager events() { return events; }
    @Override public HookFacade hooks() { return hooks; }
    @Override public PluginLogger log() { return log; }
    @Override public PluginConfig config() { return config; }
    @Override public PluginResources resources() { return resources; }
}
