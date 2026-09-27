package com.answer.launcher.core.manager;

import android.content.Context;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Instance store (levi-compat-design.md §3). Persists every imported
 * installation under {@code <dataDir>/mc/instances/<id>/instance.json} and the
 * active selection in {@code <dataDir>/mc/.active-instance}. The root uses
 * {@link Context#getDataDir()} so both the launcher UI process and the
 * {@code :game} process resolve the identical path (mirrors IdentityStore).
 */
public class InstanceStore {

    private static final String TAG = "InstanceStore";
    private static final String MC_ROOT = "mc";
    private static final String INSTANCES = "instances";
    private static final String INSTANCE_JSON = "instance.json";
    private static final String ACTIVE_FILE = ".active-instance";

    /** {@code <dataDir>/mc} — process-stable. */
    public static File mcRoot(Context ctx) {
        return new File(ctx.getDataDir(), MC_ROOT);
    }

    /** {@code <dataDir>/mc/instances}. */
    public static File instancesRoot(Context ctx) {
        File f = new File(mcRoot(ctx), INSTANCES);
        if (!f.exists()) f.mkdirs();
        return f;
    }

    public static File dir(Context ctx, String id) {
        return new File(instancesRoot(ctx), id);
    }

    /** Read-only snapshot of every instance.json in the store. */
    public static List<Instance> list(Context ctx) {
        File root = instancesRoot(ctx);
        File[] kids = root.listFiles();
        if (kids == null) return Collections.emptyList();
        List<Instance> out = new ArrayList<>();
        for (File d : kids) {
            if (!d.isDirectory()) continue;
            Instance in = readJson(d);
            if (in != null) out.add(in);
        }
        return out;
    }

    public static Instance get(Context ctx, String id) {
        if (id == null || id.isEmpty()) return null;
        return readJson(dir(ctx, id));
    }

    private static Instance readJson(File dir) {
        File f = new File(dir, INSTANCE_JSON);
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        } catch (IOException e) {
            return null;
        }
        return Instance.fromJson(sb.toString());
    }

    /** Overwrite instance.json (used after rename / enable-mods changes). */
    public static boolean writeJson(Context ctx, Instance in) {
        File d = dir(ctx, in.id);
        if (!d.exists()) d.mkdirs();
        File f = new File(d, INSTANCE_JSON);
        File tmp = new File(d, INSTANCE_JSON + ".altertmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(in.toJson().getBytes("UTF-8"));
            fos.flush();
            return tmp.renameTo(f);
        } catch (IOException e) {
            Log.w(TAG, "writeJson failed for " + in.id, e);
            return false;
        }
    }

    /** {@code null}/empty = boot the system-installed Minecraft next launch. */
    public static String getActive(Context ctx) {
        File f = new File(mcRoot(ctx), ACTIVE_FILE);
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String s = br.readLine();
            return s == null ? "" : s.trim();
        } catch (IOException e) {
            return "";
        }
    }

    public static void setActive(Context ctx, String id) {
        File f = new File(mcRoot(ctx), ACTIVE_FILE);
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write((id == null ? "" : id).getBytes("UTF-8"));
        } catch (IOException e) {
            Log.w(TAG, "setActive failed", e);
        }
    }

    public static void clearActive(Context ctx) {
        File f = new File(mcRoot(ctx), ACTIVE_FILE);
        f.delete();
    }

    public static Instance getActiveInstance(Context ctx) {
        String id = getActive(ctx);
        return id.isEmpty() ? null : get(ctx, id);
    }

    public static boolean delete(Context ctx, String id) {
        File d = dir(ctx, id);
        if (!d.exists()) return false;
        boolean ok = deleteRec(d);
        String active = getActive(ctx);
        if (id.equals(active)) clearActive(ctx);
        return ok;
    }

    private static boolean deleteRec(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File c : kids) deleteRec(c);
        }
        return f.delete();
    }

    /** Rename the human label only; the id (fs identity) never changes. */
    public static boolean rename(Context ctx, String id, String newLabel) {
        Instance in = get(ctx, id);
        if (in == null) return false;
        return writeJson(ctx, new Instance(in.id, newLabel, in.sourceApk, in.versionName,
                in.versionCode, in.abi, in.createdAt, in.isolation, in.account, in.enabledMods));
    }
}
