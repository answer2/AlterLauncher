package com.answer.launcher.api;

/**
 * A JSON file in the plugin's own directory, mirroring pl::config::ConfigFile.
 * Mutations are buffered until {@link #save()}.
 */
public interface PluginConfig {

    String get(String key, String def);

    int get(String key, int def);

    boolean get(String key, boolean def);

    double get(String key, double def);

    void set(String key, Object value);

    void remove(String key);

    void save();
}
