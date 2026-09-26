package com.answer.launcher.core.hook;

import android.app.NativeActivity;
import android.content.res.AssetManager;
import android.os.MessageQueue;
import android.util.Log;

import com.answer.launcher.core.LauncherConstants;
import com.answer.launcher.core.manager.IOManager;

import java.io.File;
import java.util.List;

import dev.answer.altercore.callback.BeforeCallback;
import dev.answer.altercore.core.HookParams;

/**
 * Virtual-environment hooks: keeps the game's file paths inside the host data
 * directory and swallows {@code System.exit(0)}.
 *
 * <p>Each registration is independent on purpose. Every target here is a
 * framework or libcore member whose signature has moved between Android
 * releases, so a single shared {@code try} would silently disable path
 * redirection and exit suppression together as soon as one lookup missed.
 */
public class EnvironmentHook extends BaseHook {

    private static final String TAG = "EnvironmentHook";

    /** Temporary experiment switch; see hookIoBridgeOpen(). */
    private static final boolean DIAGNOSTIC_NOOP = true;

    @Override
    public void init() {
        hookEnsurePrivateDirExists();
        hookIoBridgeOpen();
        hookLinuxAccess();
        hookSystemExit();
        hookLoadNativeCode();
    }

    /**
     * {@code ContextImpl#ensurePrivateDirExists(File, int, int, String)} - the
     * game asks for directories named after its own package, which the host uid
     * may not create. Rewrite the package segment instead of letting mkdir fail.
     *
     * <p>A path already under this installation's own data root (C15) is left
     * alone: there the package name is part of the isolation slug, and rewriting
     * it invents a sibling directory nobody created — measured as
     * {@code SQLITE_CANTOPEN} on {@code .../mc/com.answer.launcher@1.26.45.1/databases}.
     */
    private void hookEnsurePrivateDirExists() {
        Class<?> contextImpl = HookProvider.clazz("android.app.ContextImpl");
        HookProvider.before(
                HookProvider.method(contextImpl, "ensurePrivateDirExists",
                        File.class, int.class, int.class, String.class),
                params -> {
                    File file = (File) params.args[0];
                    if (file == null) {
                        return;
                    }
                    String path = file.getAbsolutePath();
                    if (!path.contains(LauncherConstants.MINECRAFTPACKAGE)) {
                        return;
                    }
                    if (IOManager.isIsolated(path)) {
                        return;
                    }
                    params.args[0] = new File(
                            path.replace(LauncherConstants.MINECRAFTPACKAGE, LauncherConstants.PACKAGE));
                });
    }

    /*
     * The activity's own private directories are deliberately NOT fixed here.
     * Replacing {@code ContextImpl#getDataDir()} installs cleanly and is never
     * called: it is a three-branch accessor, so ART inlines it into
     * {@code getFilesDir()} and the other getters, and an entry hook only patches
     * the standalone body. Measured over a whole game boot —
     * {@code installed replace -> android.app.ContextImpl#getDataDir()} with zero
     * invocations, while {@code ensurePrivateDirExists()} just above, which has a
     * real body, fired in the same process.
     *
     * Why a guest activity needs them at all, and where they are written instead:
     * see {@code AlterEnvironment#adoptGuestDirs(Activity)}.
     */



    /** {@code libcore.io.IoBridge#open(String, int)} - the single choke point for java.io opens. */
    private void hookIoBridgeOpen() {
        HookProvider.before(
                HookProvider.method("libcore.io.IoBridge", "open", String.class, int.class),
                this::redirectFirstStringArg);
    }

    /** {@code libcore.io.Linux#access(String, int)} - native; existence probes land here. */
    private void hookLinuxAccess() {
        HookProvider.before(
                HookProvider.method("libcore.io.Linux", "access", String.class, int.class),
                this::redirectFirstStringArg);
    }

    private void redirectFirstStringArg(HookParams params) {
        String path = (String) params.args[0];
        if (path != null && path.contains(LauncherConstants.MINECRAFTPACKAGE)) {
            params.args[0] = IOManager.get().redirectPath(path);
        }
    }

    /**
     * The game calls {@code System.exit(0)} on some shutdown paths, which would
     * take the host process with it. Swallow the clean exit, keep panic exits.
     */
    private void hookSystemExit() {
        HookProvider.replace(HookProvider.method(System.class, "exit", int.class), params -> {
            if ((int) params.args[0] != 0) {
                params.invokeOriginalMethod();
            }
            return null;
        });
    }

    /**
     * Diagnostic only, and deliberately matched by name rather than by a fixed
     * parameter list: {@code loadNativeCode} has grown parameters across
     * releases (savedState / classLoader / libraryPath), so an exact 11-arg
     * lookup throws on any layout that is not the one this file was written
     * against - which in turn used to kill the four hooks above.
     */
    private void hookLoadNativeCode() {
        List<java.lang.reflect.Method> overloads =
                HookProvider.methodsByName(NativeActivity.class, "loadNativeCode");
        if (overloads.isEmpty()) {
            return;
        }
        BeforeCallback dispatcher = params ->
                Log.d(TAG, "loadNativeCode " + params.method.getName()
                        + " args=" + params.args.length);
        for (java.lang.reflect.Method overload : overloads) {
            HookProvider.before(overload, dispatcher);
        }
    }
}
