package com.answer.launcher.core.plugin;

import com.answer.launcher.api.PluginConfig;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/** A JSON file ({@code config.json}) in the plugin's own dir, mirroring pl::config::ConfigFile. */
final class PluginConfigImpl implements PluginConfig {

    private final File file;
    private JSONObject obj;

    PluginConfigImpl(File file) {
        this.file = file;
        load();
    }

    private void load() {
        if (!file.exists()) {
            obj = new JSONObject();
            return;
        }
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            obj = new JSONObject(out.toString("UTF-8"));
        } catch (Exception e) {
            obj = new JSONObject();
        }
    }

    @Override public String get(String k, String d) { return obj.optString(k, d); }
    @Override public int get(String k, int d) { return obj.optInt(k, d); }
    @Override public boolean get(String k, boolean d) { return obj.optBoolean(k, d); }
    @Override public double get(String k, double d) { return obj.optDouble(k, d); }

    @Override
    public void set(String k, Object v) {
        try { obj.put(k, v); } catch (Exception e) { /* ignore */ }
    }

    @Override
    public void remove(String k) {
        obj.remove(k);
    }

    @Override
    public void save() {
        if (file.getParentFile() != null) file.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(obj.toString().getBytes("UTF-8"));
        } catch (IOException e) {
            // best-effort; the plugin can retry
        }
    }
}
