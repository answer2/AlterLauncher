package com.answer.launcher.core.hook;

import android.util.Log;

import com.answer.launcher.core.manager.IOManager;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import dev.answer.altercore.callback.BeforeCallback;

/**
 * Redirects every path the JVM's file layer is about to touch.
 *
 * <p>{@code java.io.UnixFileSystem} delegates to {@code *0} native helpers, so
 * those are the narrowest interception point; {@code File}'s constructors are
 * hooked as well because paths are cached into the {@code File} object itself
 * and later native calls would then receive an already-unredirected path.
 *
 * <p>Targets are resolved one by one and a missing one is skipped with a
 * warning. This class used to hold eleven lookups plus thirteen registrations
 * in a single {@code try}, so a single renamed native helper (they move
 * between releases, and {@code HiddenApiBypass} returns {@code null} rather
 * than throwing for some misses) silently dropped the remainder of the list,
 * leaving file redirection half-installed and the failure nearly impossible
 * to read in logcat.
 */
public class UnixFileSystemHook extends BaseHook {

    private static final String TAG = "UnixFileSystemHook";

    /** Native/hidden helpers on {@code java.io.UnixFileSystem}, by name and signature. */
    private static final Object[][] UFS_TARGETS = new Object[][]{
            {"canonicalize0", String.class},
            {"getBooleanAttributes0", String.class},
            {"getLastModifiedTime0", File.class},
            {"setPermission0", File.class, int.class, boolean.class, boolean.class},
            {"createFileExclusively0", String.class},
            {"list0", File.class},
            {"createDirectory0", File.class},
            {"setLastModifiedTime0", File.class, long.class},
            {"setReadOnly0", File.class},
            {"getSpace0", File.class, int.class},
    };

    @Override
    public void init() {
        BeforeCallback redirect = params -> {
            Object first = params.args[0];
            if (first == null) {
                return;
            }
            if (first instanceof String) {
                params.args[0] = IOManager.get().redirectPath((String) first);
            } else if (first instanceof File) {
                params.args[0] = IOManager.get().redirectPath((File) first);
            }
        };

        List<Member> targets = collectTargets();
        int installed = 0;
        for (Member target : targets) {
            if (HookProvider.before(target, redirect)) {
                installed++;
            }
        }
        Log.i(TAG, "path redirection active on " + installed + "/" + targets.size()
                + " resolved targets");
    }

    private List<Member> collectTargets() {
        List<Member> targets = new ArrayList<>();

        Class<?> unixFileSystem = HookProvider.clazz("java.io.UnixFileSystem");
        for (Object[] spec : UFS_TARGETS) {
            String name = (String) spec[0];
            Class<?>[] parameterTypes = new Class<?>[spec.length - 1];
            System.arraycopy(spec, 1, parameterTypes, 0, parameterTypes.length);
            Method target = HookProvider.method(unixFileSystem, name, parameterTypes);
            if (target != null) {
                targets.add(target);
            }
        }

        // Not part of java.io, but paths reach it the same way. Absent on some
        // releases/ROMs, hence optional rather than fatal.
        Method setPermissions = HookProvider.method(
                HookProvider.clazz("android.os.FileUtils"), "setPermissions",
                String.class, int.class, int.class, int.class);
        if (setPermissions != null) {
            targets.add(setPermissions);
        }

        Constructor<?> singlePath = HookProvider.constructor(File.class, String.class);
        if (singlePath != null) {
            targets.add(singlePath);
        }
        Constructor<?> parentAndChild = HookProvider.constructor(File.class, String.class, String.class);
        if (parentAndChild != null) {
            targets.add(parentAndChild);
        }
        return targets;
    }
}
