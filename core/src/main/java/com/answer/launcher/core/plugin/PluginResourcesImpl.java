package com.answer.launcher.core.plugin;

import com.answer.launcher.api.PluginResources;
import android.content.res.AssetManager;
import java.io.IOException;
import java.io.InputStream;

/** The plugin's own AssetManager. Null (open() throws) for bare .dex plugins with no assets. */
final class PluginResourcesImpl implements PluginResources {

    private final AssetManager am;

    PluginResourcesImpl(AssetManager am) {
        this.am = am;
    }

    @Override
    public AssetManager assets() {
        return am;
    }

    @Override
    public InputStream open(String path) throws IOException {
        if (am == null) throw new IOException("plugin has no assets (bare .dex)");
        return am.open(path);
    }
}
