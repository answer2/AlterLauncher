package com.answer.launcher.api;

public interface PluginLogger {

    void info(String message);

    void warn(String message);

    void error(String message, Throwable t);

    void debug(String message);
}
