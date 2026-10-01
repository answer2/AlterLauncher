package com.answer.launcher.ui;

import android.content.Context;
import android.util.Log;

import com.answer.launcher.core.manager.GameDataStore;
import com.answer.launcher.core.manager.IdentityStore;
import com.answer.launcher.core.manager.SymlinkUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.Charset;

/**
 * Backing store for cross-version "global" packs. A pack lives once under
 * {@code <dataDir>/mc/global_packs/} and is symlinked into every instance's
 * {@code resource_packs/} or {@code behavior_packs/} directory according to its
 * module type. Adding or removing a pack therefore has to fan out across every
 * instance the launcher knows about.
 */
public final class GlobalPackManager {

    private static final String TAG = "GlobalPackManager";

    public enum Kind { RESOURCE, BEHAVIOR }

    private GlobalPackManager() {
    }

    /** The root directory where global packs are stored. */
    public static File storageRoot(Context host) {
        return GameDataStore.sharedPackStorage(host);
    }

    /** List every global pack directory (each must contain a manifest.json). */
    public static File[] listPacks(Context host) {
        File root = storageRoot(host);
        if (root == null || !root.isDirectory()) return new File[0];
        File[] kids = root.listFiles(f -> f.isDirectory() && new File(f, "manifest.json").isFile());
        return kids == null ? new File[0] : kids;
    }

    /**
     * Register a freshly-unzipped pack at the global root and link it into every
     * instance. {@code srcDir} is the unzipped pack directory; it is moved into
     * {@code global_packs/<uuid>/} and the original is removed.
     */
    public static boolean addPack(Context host, File srcDir) {
        File manifest = new File(srcDir, "manifest.json");
        if (!manifest.isFile()) return false;
        String uuid = readUuid(manifest);
        if (uuid == null || uuid.isEmpty()) return false;
        File dest = new File(storageRoot(host), uuid);
        if (dest.exists()) deleteRecursive(dest);
        if (!srcDir.renameTo(dest)) {
            if (!copyRecursive(srcDir, dest)) return false;
            deleteRecursive(srcDir);
        }
        boolean isBehavior = detectBehavior(manifest);
        Kind kind = isBehavior ? Kind.BEHAVIOR : Kind.RESOURCE;
        linkIntoEveryInstance(host, dest, kind);
        return true;
    }

    /** Remove a global pack and every symlink pointing at it. */
    public static boolean removePack(Context host, File packDir) {
        String uuid = packDir.getName();
        File manifest = new File(packDir, "manifest.json");
        Kind kind = detectBehavior(manifest) ? Kind.BEHAVIOR : Kind.RESOURCE;
        unlinkFromEveryInstance(host, uuid, kind);
        deleteRecursive(packDir);
        return true;
    }

    /**
     * Create a symlink for {@code packDir} inside every instance's resource_packs or
     * behavior_packs directory, depending on {@code kind}.
     */
    public static void linkIntoEveryInstance(Context host, File packDir, Kind kind) {
        for (String slug : GameDataStore.allInstanceSlugs(host)) {
            File instanceRoot = IdentityStore.instanceRoot(host, slug);
            File target = new File(instanceRoot, "games/com.mojang/");
            target = new File(target, kind == Kind.BEHAVIOR ? "behavior_packs" : "resource_packs");
            //noinspection ResultOfMethodCallIgnored
            target.mkdirs();
            File link = new File(target, packDir.getName());
            if (link.exists() || SymlinkUtil.isSymlink(link)) {
                if (SymlinkUtil.isSymlink(link)) link.delete();
                else continue;
            }
            if (!SymlinkUtil.symlink(packDir, link)) {
                copyRecursive(packDir, link);
            }
        }
    }

    /** Remove the symlink (or copied directory) for {@code uuid} from every instance. */
    public static void unlinkFromEveryInstance(Context host, String uuid, Kind kind) {
        for (String slug : GameDataStore.allInstanceSlugs(host)) {
            File instanceRoot = IdentityStore.instanceRoot(host, slug);
            File target = new File(instanceRoot, "games/com.mojang/");
            target = new File(target, kind == Kind.BEHAVIOR ? "behavior_packs" : "resource_packs");
            File link = new File(target, uuid);
            if (SymlinkUtil.isSymlink(link)) {
                //noinspection ResultOfMethodCallIgnored
                link.delete();
            }
        }
    }

    private static String readUuid(File manifest) {
        try {
            byte[] buf = new byte[(int) manifest.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(manifest)) {
                //noinspection ResultOfMethodCallIgnored
                in.read(buf);
            }
            JSONObject obj = new JSONObject(new String(buf, Charset.forName("UTF-8")));
            JSONObject header = obj.optJSONObject("header");
            return header == null ? null : header.optString("uuid", null);
        } catch (Throwable t) {
            Log.w(TAG, "manifest parse failed: " + t);
            return null;
        }
    }

    /**
     * A pack is a behavior pack when any module declares {@code type == "data"};
     * resource packs declare {@code type == "resources"}. Defaults to resource when
     * the manifest is malformed.
     */
    private static boolean detectBehavior(File manifest) {
        try {
            byte[] buf = new byte[(int) manifest.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(manifest)) {
                //noinspection ResultOfMethodCallIgnored
                in.read(buf);
            }
            JSONObject obj = new JSONObject(new String(buf, Charset.forName("UTF-8")));
            JSONArray modules = obj.optJSONArray("modules");
            if (modules == null) return false;
            for (int i = 0; i < modules.length(); i++) {
                JSONObject m = modules.optJSONObject(i);
                if (m != null && "data".equals(m.optString("type"))) return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean copyRecursive(File src, File dst) {
        if (src.isDirectory()) {
            if (!dst.exists() && !dst.mkdirs()) return false;
            String[] kids = src.list();
            if (kids == null) return true;
            for (String k : kids) {
                if (!copyRecursive(new File(src, k), new File(dst, k))) return false;
            }
            return true;
        }
        try (java.io.FileInputStream in = new java.io.FileInputStream(src);
             java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "copy failed: " + t);
            return false;
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
