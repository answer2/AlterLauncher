package com.answer.launcher.api;

import org.json.JSONObject;

/**
 * alter-plugin.json, deserialized. Same shape as Levi's manifest.json so one
 * mental model serves both ecosystems (levi-compat-design.md §5).
 *
 * <p>Found at {@code assets/alter-plugin.json} inside an APK, or a sidecar
 * {@code <name>.json} next to a bare {@code .dex}.
 */
public final class PluginManifest {

    /** Manifest schema version. v2 loaders reject any other value. */
    public final int api;
    public final String id;
    public final String name;
    public final String version;
    public final String author;
    /** Fully-qualified entry class implementing {@link AlterPlugin}. */
    public final String entry;
    /** "game" = loaded in :game; "launcher" = loaded in the host process. */
    public final String side;
    public final String minGame;
    public final String maxGame;
    public final boolean enabled;

    public PluginManifest(int api, String id, String name, String version, String author,
                          String entry, String side, String minGame, String maxGame, boolean enabled) {
        this.api = api;
        this.id = id;
        this.name = name;
        this.version = version;
        this.author = author;
        this.entry = entry;
        this.side = side;
        this.minGame = minGame;
        this.maxGame = maxGame;
        this.enabled = enabled;
    }

    public static PluginManifest fromJson(String json) {
        try {
            return fromJsonObject(new JSONObject(json));
        } catch (org.json.JSONException e) {
            return null;
        }
    }

    public static PluginManifest fromJsonObject(JSONObject o) {
        if (o == null) throw new IllegalArgumentException("manifest is null");
        return new PluginManifest(
                o.optInt("api", 2),
                o.optString("id", ""),
                o.optString("name", ""),
                o.optString("version", ""),
                o.optString("author", ""),
                o.optString("entry", ""),
                o.optString("side", "game"),
                o.optString("minGame", ""),
                o.optString("maxGame", ""),
                o.optBoolean("enabled", true));
    }

    /** Dot-run numeric compare, missing components treated as 0 (Levi's rule). */
    public boolean matchesGameVersion(String gameVersion) {
        if (gameVersion == null || gameVersion.isEmpty()) return true;
        if (!minGame.isEmpty() && compareVersions(gameVersion, minGame) < 0) return false;
        if (!maxGame.isEmpty() && compareVersions(gameVersion, maxGame) > 0) return false;
        return true;
    }

    static int compareVersions(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int ia = i < pa.length ? parseDigit(pa[i]) : 0;
            int ib = i < pb.length ? parseDigit(pb[i]) : 0;
            if (ia != ib) return Integer.compare(ia, ib);
        }
        return 0;
    }

    private static int parseDigit(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') n = n * 10 + (c - '0');
            else break;
        }
        return n;
    }
}
