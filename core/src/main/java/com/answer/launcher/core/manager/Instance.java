package com.answer.launcher.core.manager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One imported Minecraft installation (levi-compat-design.md §3). Lives in
 * {@code <dataDir>/mc/instances/<id>/} with {@code base.apk},
 * {@code lib/<abi>/*.so}, {@code instance.json}, {@code mods/}, {@code logs/}.
 * The runtime guest data root {@code <dataDir>/mc/<id>/} (IdentityStore's slug
 * territory) is separate from this import tree, so re-importing never clobbers
 * a booted instance's data.
 *
 * <p>{@code id} equals the isolation slug ({@link IOManager#isolationSlug}),
 * so an imported instance and a normally-booted one of the same version share
 * the same data root by construction. Duplicates of the same version get a
 * {@code -2}, {@code -3} suffix (the design wrote {@code #n}, but {@code #} is
 * mangled by {@code isolationSlug}'s sanitizer; {@code -} is kept and stays a
 * valid isolation slug).
 */
public class Instance {

    public final String id;
    public final String label;
    public final String sourceApk;
    public final String versionName;
    public final long versionCode;
    public final String abi;
    public final long createdAt;
    public final boolean isolation;
    public final String account;
    public final List<String> enabledMods;
    /** Populated at import time; not persisted. */
    public transient boolean aligned16kb;

    public Instance(String id, String label, String sourceApk, String versionName,
                    long versionCode, String abi, long createdAt, boolean isolation,
                    String account, List<String> enabledMods) {
        this.id = id;
        this.label = label;
        this.sourceApk = sourceApk;
        this.versionName = versionName;
        this.versionCode = versionCode;
        this.abi = abi;
        this.createdAt = createdAt;
        this.isolation = isolation;
        this.account = account == null ? "" : account;
        this.enabledMods = enabledMods == null ? Collections.emptyList() : enabledMods;
    }

    public File dir(File root) { return new File(root, id); }
    public File baseApk(File root) { return new File(dir(root), "base.apk"); }
    public File libDir(File root) { return new File(dir(root), "lib/" + abi); }
    public File dataDir(File root) { return new File(dir(root), "data"); }
    public File modsDir(File root) { return new File(dir(root), "mods"); }
    public File logsDir(File root) { return new File(dir(root), "logs"); }

    public String toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("label", label);
            o.put("sourceApk", sourceApk);
            o.put("versionName", versionName);
            o.put("versionCode", versionCode);
            o.put("abi", abi);
            o.put("createdAt", createdAt);
            o.put("isolation", isolation);
            o.put("account", account);
            JSONArray mods = new JSONArray();
            for (String m : enabledMods) mods.put(m);
            o.put("enabledMods", mods);
        } catch (JSONException ignored) { }
        return o.toString();
    }

    public static Instance fromJson(String json) {
        try {
            JSONObject o = new JSONObject(json);
            List<String> mods = new ArrayList<>();
            JSONArray arr = o.optJSONArray("enabledMods");
            if (arr != null) for (int i = 0; i < arr.length(); i++) mods.add(arr.getString(i));
            return new Instance(
                    o.optString("id", ""),
                    o.optString("label", ""),
                    o.optString("sourceApk", ""),
                    o.optString("versionName", ""),
                    o.optLong("versionCode", 0),
                    o.optString("abi", ""),
                    o.optLong("createdAt", 0),
                    o.optBoolean("isolation", true),
                    o.optString("account", ""),
                    mods);
        } catch (JSONException e) {
            return null;
        }
    }
}
