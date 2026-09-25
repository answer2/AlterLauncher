package com.answer.launcher.core.env;

import dalvik.system.DexClassLoader;

/**
 * Class loader for the hosted game.
 *
 * <p>{@link DexClassLoader} delegates to its parent first, and the parent here is the
 * launcher's own {@code PathClassLoader}. That is wrong for a full application: the
 * launcher ships {@code androidx.appcompat}, the games library and friends, so the game
 * would link against the LAUNCHER's copy of every shared library while inflating its
 * resources from the GAME's resource table. The two copies disagree about resource ids
 * (each APK numbers its own {@code 0x7f} package), so the game's AppCompatActivity -
 * really the launcher's class object - looks up its style attributes with the launcher's
 * constants in a theme built from the game's table, finds nothing, and dies:
 *
 * <pre>
 * java.lang.IllegalStateException: You need to use a Theme.AppCompat theme (or descendant)
 *         with this activity.
 *     at androidx.appcompat.app.AppCompatDelegateImpl.createSubDecor(...)
 * </pre>
 *
 * <p>A real app's loader only ever sees the framework plus its own dex, so this loader
 * resolves everything from the game's dex first and hands only the names that must stay
 * single-copy back to the parent: the framework and the JVM, the launcher runtime (which
 * the game's Application talks to through {@code NativeCore}), and the hook engine (whose
 * tokens and callbacks are meaningless across two copies).
 *
 * <p>Consequence to keep in mind: classes the launcher used to shadow on purpose (the
 * {@code com.mojang.minecraftpe.*} and {@code com.google.androidgamesdk.GameActivity}
 * patch sources under {@code core/src/main/java}) no longer win for the game -- the game
 * gets its own copies. Anything those patches fixed has to move into this loader's dex
 * path or into a hook.
 */
public final class GuestClassLoader extends DexClassLoader {

    /** Names the launcher's loader must own; everything else is resolved from the game. */
    private static final String[] HOST_ONLY_PREFIXES = {
            // Framework and JVM: one copy only, the system's.
            "java.",
            "javax.",
            "android.",
            "dalvik.",
            "libcore.",
            "sun.",
            "com.android.internal.",
            "org.apache.http.",
            "org.json.",
            "org.w3c.",
            "org.xml.",
            // Launcher runtime: Alternative, the hooks, NativeCore, the plugin SPI.
            "com.answer.launcher.",
            // Hook engine: a backup method from another copy would not match the target.
            "dev.answer.altercore.",
            "com.v7878.",
            "io.github.vova7878.",
            // Hidden-api bypass is a process-wide singleton.
            "org.lsposed.",
            // Bytecode/reflective helpers packaged by the launcher.
            "com.google.vending.",
            "com.android.vending.",
    };

    public GuestClassLoader(String dexPath, String optimizedDirectory, String librarySearchPath,
            ClassLoader parent) {
        super(dexPath, optimizedDirectory, librarySearchPath, parent);
    }

    /** True when {@code name} must come from the launcher's loader, not the game's dex. */
    public static boolean isHostOnly(String name) {
        if (name == null) {
            return true;
        }
        for (String prefix : HOST_ONLY_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        // "androidx." does not match the "android." prefix above and must NOT: the game
        // needs its own copy so its resource ids and its theme agree.
        return false;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (isHostOnly(name)) {
            return super.loadClass(name, resolve);
        }
        // Same contract ClassLoader.loadClass offers, just with the order reversed.
        synchronized (this) {
            Class<?> resolved = findLoadedClass(name);
            if (resolved == null) {
                try {
                    resolved = findClass(name);
                } catch (ClassNotFoundException fromGameDex) {
                    resolved = super.loadClass(name, false);
                }
            }
            if (resolve) {
                resolveClass(resolved);
            }
            return resolved;
        }
    }

    /**
     * Resources follow the same rule: the game's assets are inside its own APK, and the
     * parent would return the launcher's file of the same name (icons, locales,
     * {@code META-INF} service entries).
     */
    @Override
    public java.net.URL getResource(String name) {
        if (isHostOnlyResource(name)) {
            return super.getResource(name);
        }
        java.net.URL self = findResource(name);
        return self != null ? self : super.getResource(name);
    }

    @Override
    public java.util.Enumeration<java.net.URL> getResources(String name) throws java.io.IOException {
        if (isHostOnlyResource(name)) {
            return super.getResources(name);
        }
        java.util.Enumeration<java.net.URL> self = findResources(name);
        if (self.hasMoreElements()) {
            return self;
        }
        return super.getResources(name);
    }

    private static boolean isHostOnlyResource(String name) {
        // Nothing the game ships has to be taken from the launcher; only the framework's
        // own service files, which live outside any app dex.
        return name != null && (name.startsWith("META-INF/services/")
                || name.startsWith("android/"));
    }
}
