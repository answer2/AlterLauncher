package com.answer.launcher.core.manager;

import android.content.Context;

import java.io.File;

/**
 * Resolves the on-disk locations the launcher needs to read/write for the currently
 * active instance. The guest's data root is {@code <dataDir>/mc/<slug>/} and Minecraft
 * itself writes worlds and packs under {@code games/com.mojang/} inside that root, so
 * every path here is derived from those two facts. When no instance is active the
 * helpers return {@code null} rather than a broken path — callers must handle that.
 */
public final class GameDataStore {

    private GameDataStore() {
    }

    /** The active instance's data root, or null if nothing has been launched yet. */
    public static File activeRoot(Context host) {
        String slug = IdentityStore.activeSlug(host);
        if (slug == null) return null;
        return IdentityStore.instanceRoot(host, slug);
    }

    /** The {@code games/com.mojang} directory for the active instance, or null. */
    public static File comMojang(Context host) {
        File root = activeRoot(host);
        return root == null ? null : new File(root, "games/com.mojang");
    }

    public static File worlds(Context host) {
        File base = comMojang(host);
        return base == null ? null : new File(base, "minecraftWorlds");
    }

    public static File resourcePacks(Context host) {
        File base = comMojang(host);
        return base == null ? null : new File(base, "resource_packs");
    }

    public static File behaviorPacks(Context host) {
        File base = comMojang(host);
        return base == null ? null : new File(base, "behavior_packs");
    }

    /**
     * The globally-enabled packs folder. A pack whose id appears here (as a directory
     * with a manifest.json, usually a copy or a symlink back into resource_packs /
     * behavior_packs) is applied to every world the player opens.
     */
    public static File globalPacks(Context host) {
        File base = comMojang(host);
        return base == null ? null : new File(base, "packs");
    }

    /** Ensures the directory exists; returns it unchanged so callers can chain. */
    public static File ensure(File dir) {
        if (dir != null && !dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }
}
