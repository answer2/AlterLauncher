package com.answer.launcher.api;

import android.content.res.AssetManager;
import java.io.IOException;
import java.io.InputStream;

/** The plugin's own AssetManager, for reading bundled assets. */
public interface PluginResources {

    AssetManager assets();

    InputStream open(String path) throws IOException;
}
