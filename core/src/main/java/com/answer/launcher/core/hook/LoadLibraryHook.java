package com.answer.launcher.core.hook;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.util.Log;

import com.answer.launcher.core.LauncherConstants;
import com.answer.launcher.core.manager.Alternative;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dalvik.system.BaseDexClassLoader;

/**
 * Makes the game's {@code System.loadLibrary} calls resolve inside the game's
 * own native library directory instead of the launcher's linker namespace.
 *
 * <p>The library directory comes from {@link Alternative#nativepath}, already
 * resolved during {@code initPackage()}. Asking {@code PackageManager} here
 * instead used to throw {@code NameNotFoundException} before the single hook
 * statement, which silently left this whole class inert.
 */
public class LoadLibraryHook extends BaseHook {

    private static final String TAG = "LoadLibraryHook";

    /**
     * Game libraries discovered while the IO redirect was still off, mapped
     * SONAME ("c++_shared") -> absolute path. Populated by
     * {@link #preloadGameLibraries()}.
     *
     * <p>Deliberately captured once, up front: probing with {@code new File()}
     * at hook time is unreliable because {@code UnixFileSystemHook} redirects
     * {@code java.io.File} afterwards, so a library that really is in the game's
     * install directory can be reported as missing.
     */
    private static final Map<String, String> GAME_LIBS = new HashMap<>();

    @Override
    public void init() {
        if (!Alternative.isGameDexMerged()) {
            /*
             * The game's classes are defined by the DexClassLoader built over the
             * Minecraft APK, whose library search path already points at the game's
             * native lib dir, so ART resolves the bare SONAMEs correctly on its own.
             * Intercepting here would load a second copy of the engine into the
             * launcher's namespace.
             */
            Log.i(TAG, "game dex not merged, guest namespace handles its own libraries");
            return;
        }

        if (Alternative.nativepath == null) {
            Log.w(TAG, "nativeLibraryDir unknown, library hooks skipped");
            return;
        }

        /*
         * The control point that actually works. BaseDexClassLoader#findLibrary
         * is reported as hooked by the engine but is never invoked for
         * System.loadLibrary() from game code (an entry log in that hook never
         * fired on RMX8899 during MainActivity's <clinit>): the caller reaches
         * the library lookup through a path that does not go through the hook's
         * entry point. java.lang.System#loadLibrary is public static and always
         * in the chain, so resolving the SONAME there and handing the absolute
         * path to System.load() bypasses ART's own SONAME search, which is what
         * refused the request:
         *   dlopen failed: library "/system_ext/lib64/libc++_shared.so" needed
         *   or dlopened by "/apex/com.android.art/lib64/libnativeloader.so" is
         *   not accessible for the namespace "clns-9"
         */
        Method systemLoadLibrary =
                HookProvider.method(System.class, "loadLibrary", String.class);

        HookProvider.replace(systemLoadLibrary, params -> {
            String lib = (String) params.args[0];
            String path = gameLibraryPath(lib);
            Log.i(TAG, "loadLibrary(" + lib + ") -> " + path);

            if (path == null) {
                // Not one of the game's libraries: ordinary launcher load.
                return params.invokeOriginalMethod(params.thisObject, params.args);
            }
            try {
                loadGameLibrary(lib, path);
                markLoaded(path);
                Log.i(TAG, "loadLibrary(" + lib + ") -> " + path);
                return null;
            } catch (Throwable failure) {
                Log.w(TAG, "absolute load failed for " + path + " ("
                        + failure.getMessage() + "), falling back");
                return params.invokeOriginalMethod(params.thisObject, params.args);
            }
        });

        // Exact lookup, NOT methodsByName(): walking the superclass chain also
        // finds java.lang.ClassLoader#findLibrary, whose base implementation
        // returns null. Replacing that makes every library lookup fail with
        // "library X not found" (observed with libc++_shared.so during
        // MainActivity's <clinit>).
        Method findLibrary = HookProvider.method(
                BaseDexClassLoader.class, "findLibrary", String.class);

        HookProvider.replace(findLibrary, params -> {
            String lib = (String) params.args[0];
            String path = gameLibraryPath(lib);
            if (path == null) {
                return params.invokeOriginalMethod(params.thisObject, params.args);
            }
            Log.i(TAG, "findLibrary(" + lib + ") -> " + path);
            try {
                loadGameLibrary(lib, path);
                markLoaded(path);
            } catch (Throwable missing) {
                Log.d(TAG, "not loadable from game lib dir: " + lib
                        + " (" + missing.getMessage() + ')');
                return params.invokeOriginalMethod(params.thisObject, params.args);
            }
            return path;
        });
    }

    /**
     * Loads one game library by absolute path, making sure its dependencies are
     * already resident.
     *
     * <p>{@code libminecraftpe.so} is linked against {@code libc++_shared.so} as a
     * DT_NEEDED entry: that lookup happens in the linker, below anything the Java
     * layer can intercept. So the C++ runtime has to be loaded BEFORE the engine,
     * never as a side effect of it — loading the engine first makes the linker
     * resolve {@code libc++_shared.so} by SONAME inside a namespace that does not
     * carry the game's library directory, and the request escapes to
     * {@code /system_ext/lib64/libc++_shared.so}, which the namespace refuses.
     */
    private static void loadGameLibrary(String soname, String path) {
        if (soname != null && soname.contains("minecraftpe")) {
            String cxx = GAME_LIBS.get("c++_shared");
            if (cxx != null && !cxx.equals(path) && !isLoaded(cxx)) {
                try {
                    System.load(cxx);
                    markLoaded(cxx);
                    Log.i(TAG, "loaded libc++_shared.so ahead of " + soname);
                } catch (Throwable failure) {
                    Log.w(TAG, "could not preload libc++_shared.so for "
                            + soname + " (" + failure.getMessage() + ')');
                }
            }
        }
        System.load(path);
    }

    private static final java.util.Set<String> LOADED =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private static boolean isLoaded(String path) {
        return LOADED.contains(path);
    }

    private static void markLoaded(String path) {
        LOADED.add(path);
    }

    /** Absolute path of a game library, or {@code null} if it is not ours. */
    private static String gameLibraryPath(String lib) {
        if (lib == null || lib.isEmpty() || lib.indexOf('/') >= 0
                || lib.endsWith(".so")) {
            return null;
        }
        String known = GAME_LIBS.get(lib);
        if (known != null) {
            return known;
        }
        if (!GAME_LIBS.isEmpty() || Alternative.nativepath == null) {
            return null;
        }
        // Discovery never ran (preload skipped): fall back to a plain guess.
        return Alternative.nativepath + "/lib" + lib + ".so";
    }

    /**
     * Enumerates and loads every Minecraft native library by absolute path, in
     * dependency order, before anything in the game asks for one by SONAME.
     *
     * <p>Also the discovery point for {@link #GAME_LIBS}: it runs while the IO
     * redirect is still off, so {@code java.io.File} reports the truth here.
     *
     * <p>Must run before {@code IOManager.enableRedirect()}, or the redirected
     * file hooks would rewrite these very paths.
     */
    public static synchronized void preloadGameLibraries() {
        String dir = Alternative.nativepath;
        if (dir == null) {
            Log.w(TAG, "nativeLibraryDir unknown, preload skipped");
            return;
        }

        File[] files = new File(dir).listFiles();
        if (files == null) {
            Log.w(TAG, "no readable lib dir at " + dir + ", preload skipped");
            return;
        }

        List<File> libs = new ArrayList<>();
        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(".so")) {
                libs.add(file);
            }
        }

        // libc++_shared first (everything else links against it), the game
        // binary last (it is the biggest dependency chain), the rest sorted so
        // the order is stable across runs.
        Collections.sort(libs, (a, b) -> {
            int byRank = rank(a) - rank(b);
            return byRank != 0 ? byRank : a.getName().compareTo(b.getName());
        });

        int loaded = 0;
        for (File lib : libs) {
            String soname = toSoname(lib.getName());
            if (soname != null && !isLauncherOwnLibrary(soname)) {
                GAME_LIBS.put(soname, lib.getAbsolutePath());
            }
            try {
                loadGameLibrary(toSoname(lib.getName()), lib.getAbsolutePath());
                markLoaded(lib.getAbsolutePath());
                loaded++;
            } catch (Throwable failure) {
                Log.w(TAG, "preload failed: " + lib.getName()
                        + " (" + failure.getMessage() + ')');
            }
        }
        Log.i(TAG, "game libraries preloaded from " + dir + ": "
                + loaded + '/' + libs.size()
                + " (intercepted " + GAME_LIBS.size() + ')');
    }

    /**
     * The launcher ships some of the same libraries the game does — notably
     * {@code libconscrypt_jni.so}. Those must keep loading from the launcher's
     * own directory through the normal path: claiming them for the game's
     * directory would hand the same SONAME to two different namespaces.
     *
     * <p>Resolved through {@code PackageManager} on purpose.
     * {@link BaseHook#getContext()} is the GUEST application context — that is
     * what {@code Alternative} installs into the hook layer — so asking it for
     * {@code getApplicationInfo().nativeLibraryDir} returns the game's own
     * directory and makes every game library look launcher-owned (seen as
     * {@code intercepted 0} on RMX8899, with every load falling back to ART's
     * SONAME search and failing again).
     */
    private static boolean isLauncherOwnLibrary(String soname) {
        String hostDir = launcherNativeLibraryDir();
        return hostDir != null
                && new File(hostDir, "lib" + soname + ".so").isFile();
    }

    private static String hostNativeLibraryDir;
    private static boolean hostNativeLibraryDirResolved;

    private static synchronized String launcherNativeLibraryDir() {
        if (hostNativeLibraryDirResolved) {
            return hostNativeLibraryDir;
        }
        hostNativeLibraryDirResolved = true;
        try {
            Context host = BaseHook.getContext();
            if (host == null) {
                Log.w(TAG, "no context to resolve the launcher lib dir");
                return null;
            }
            ApplicationInfo hostInfo = host.getPackageManager()
                    .getApplicationInfo(LauncherConstants.PACKAGE, 0);
            hostNativeLibraryDir = hostInfo.nativeLibraryDir;
            Log.i(TAG, "launcher native lib dir = " + hostNativeLibraryDir);
        } catch (Throwable ignored) {
            // Package name unknown to this PackageManager? Nothing is filtered
            // and every game library stays intercepted, which is the old
            // (harmless) behaviour.
        }
        return hostNativeLibraryDir;
    }

    /** "libc++_shared.so" -> "c++_shared"; null when the name is not lib*.so. */
    private static String toSoname(String fileName) {
        if (!fileName.startsWith("lib") || !fileName.endsWith(".so")
                || fileName.length() <= 5) {
            return null;
        }
        return fileName.substring(3, fileName.length() - 3);
    }

    private static int rank(File lib) {
        String name = lib.getName();
        if (name.contains("c++_shared")) return 0;
        if (name.contains("minecraftpe")) return 2;
        return 1;
    }
}
