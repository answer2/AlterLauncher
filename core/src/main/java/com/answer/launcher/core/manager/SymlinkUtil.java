package com.answer.launcher.core.manager;

import android.system.ErrnoException;
import android.system.Os;
import android.system.StructStat;
import android.util.Log;

import java.io.File;

/**
 * Small symlink helpers used by IOManager and IdentityStore. Android's {@code Os.symlink}
 * works on the app's private data partition (ext4/f2fs), but not on external storage
 * (vfat); callers fall back to copying when these return false.
 */
public final class SymlinkUtil {
    private static final String TAG = "SymlinkUtil";

    private SymlinkUtil() {
    }

    public static boolean isSymlink(File path) {
        try {
            StructStat st = Os.lstat(path.getAbsolutePath());
            return (st.st_mode & 0xF000) == 0xA000;
        } catch (ErrnoException e) {
            return false;
        }
    }

    /** Creates a symlink at linkPath pointing to target. Returns true on success. */
    public static boolean symlink(File target, File linkPath) {
        if (linkPath.exists() || isSymlink(linkPath)) return false;
        File parent = linkPath.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try {
            Os.symlink(target.getAbsolutePath(), linkPath.getAbsolutePath());
            return true;
        } catch (ErrnoException e) {
            Log.w(TAG, "symlink " + linkPath + " -> " + target + " failed: " + e.getMessage());
            return false;
        }
    }
}
