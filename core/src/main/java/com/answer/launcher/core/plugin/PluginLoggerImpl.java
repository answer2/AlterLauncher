package com.answer.launcher.core.plugin;

import com.answer.launcher.api.PluginLogger;
import android.util.Log;

final class PluginLoggerImpl implements PluginLogger {

    private final String tag;

    PluginLoggerImpl(String id) {
        this.tag = "Plugin/" + (id == null || id.isEmpty() ? "?" : id);
    }

    @Override public void info(String m) { Log.i(tag, m); }
    @Override public void warn(String m) { Log.w(tag, m); }
    @Override public void error(String m, Throwable t) { Log.e(tag, m, t); }
    @Override public void debug(String m) { Log.d(tag, m); }
}
