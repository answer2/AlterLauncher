package com.answer.launcher.core.hook;

import android.os.Build;
import android.util.Log;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import dev.answer.altercore.AlterCore;
import dev.answer.altercore.callback.AfterCallback;
import dev.answer.altercore.callback.BeforeCallback;
import dev.answer.altercore.callback.ReplaceCallback;

/**
 * The single entry point for installing hooks in this process.
 *
 * <p>Every hook goes through here so that the process has exactly one hook
 * engine (AlterCore) and so that one unavailable target can never take its
 * siblings down with it. That last point matters more than it sounds: the
 * previous code put 5 (EnvironmentHook) and 13 (UnixFileSystemHook)
 * registrations inside a single {@code try}, and each lookup throws
 * {@link NoSuchMethodException} as soon as a ROM or an Android release moves
 * a signature, silently dropping every later hook.
 *
 * <p>Contracts this class guarantees to callers:
 * <ul>
 *   <li>Lookup helpers ({@link #method}, {@link #constructor},
 *   {@link #methodsByName}) return {@code null} / an empty list instead of
 *   throwing, so a missing framework member is a normal value.</li>
 *   <li>{@link #before}, {@link #after}, {@link #replace} and
 *   {@link #doNothing} never throw. They return whether a hook was installed
 *   and log anything that went wrong.</li>
 *   <li>Registration is idempotent per (kind, member, callback) triple. A
 *   second attempt for the same target is a no-op rather than a stacked hook,
 *   which keeps re-entrant paths such as {@code MainActivity#onCreate} from
 *   running a payload twice.</li>
 * </ul>
 *
 * <p>AlterCore cannot hook a class initializer and cannot redirect the
 * original call of a constructor (it logs and resets {@code isSkipped}); use
 * {@link #methodsByName(Class, String)} on the enclosing Java method instead
 * of a {@code *0} native helper when you need to skip work, and mutate
 * {@code params.args} when you only need to rewrite arguments.
 */
public final class HookProvider {

    private static final String TAG = "HookProvider";

    private static final Set<String> REGISTERED = Collections.synchronizedSet(new HashSet<>());
    private static final AtomicInteger INSTALLED = new AtomicInteger();
    private static final AtomicInteger DUPLICATE = new AtomicInteger();
    private static final AtomicInteger FAILED = new AtomicInteger();
    private static final AtomicInteger MISSING = new AtomicInteger();

    /** True on P and later, where platform members must be fetched through HiddenApiBypass. */
    private static final boolean HIDDEN_API_GATED = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P;

    private HookProvider() {
    }

    // ------------------------------------------------------------------
    // Registration
    // ------------------------------------------------------------------

    /** Runs {@code callback} before each call to {@code target}. Null target is a no-op. */
    public static boolean before(Member target, BeforeCallback callback) {
        if (target == null || callback == null) {
            missing("before", target);
            return false;
        }
        return install("before", target, callback, () -> AlterCore.before(target, callback));
    }

    /** Runs {@code callback} after each call to {@code target}. Null target is a no-op. */
    public static boolean after(Member target, AfterCallback callback) {
        if (target == null || callback == null) {
            missing("after", target);
            return false;
        }
        return install("after", target, callback, () -> AlterCore.after(target, callback));
    }

    /**
     * Replaces {@code target}'s implementation. Null target is a no-op.
     *
     * <p>The callback's return value becomes the call result and the original
     * body is skipped; call {@code params.invokeOriginalMethod()} to chain it.
     * Not valid for constructors, which AlterCore always lets complete.
     */
    public static boolean replace(Member target, ReplaceCallback callback) {
        if (target == null || callback == null) {
            missing("replace", target);
            return false;
        }
        return install("replace", target, callback, () -> AlterCore.replace(target, callback));
    }

    /** Makes {@code target} return {@code null}/default without running its body. */
    public static boolean doNothing(Member target) {
        if (target == null) {
            missing("doNothing", null);
            return false;
        }
        return install("doNothing", target, AlterCore.DO_NOTHING, () -> AlterCore.doNothing(target));
    }

    private interface Install {
        AlterCore.CallbackToken run();
    }

    private static boolean install(String kind, Member target, Object callback, Install action) {
        String key = kind + "|" + describe(target) + "|" + System.identityHashCode(callback);
        if (!REGISTERED.add(key)) {
            DUPLICATE.incrementAndGet();
            Log.v(TAG, "skip duplicate " + kind + " " + describe(target));
            return false;
        }
        try {
            if (action.run() == null) {
                // AlterCore reports registration failure as a null token.
                throw new IllegalStateException("AlterCore returned a null token");
            }
            INSTALLED.incrementAndGet();
            Log.i(TAG, "installed " + kind + " -> " + describe(target));
            return true;
        } catch (Throwable t) {
            // Allow a later attempt (e.g. after class initialization) to try again.
            REGISTERED.remove(key);
            FAILED.incrementAndGet();
            Log.e(TAG, "failed to install " + kind + " on " + describe(target), t);
            return false;
        }
    }

    private static void missing(String kind, Member target) {
        MISSING.incrementAndGet();
        Log.w(TAG, "no target for " + kind + (target == null ? " (target unavailable)" : ""));
    }

    private static String describe(Member target) {
        if (target instanceof Executable) {
            return signatureOf((Executable) target);
        }
        return String.valueOf(target);
    }

    /**
     * Fully-qualified {@code Class#name(params)} form.
     *
     * <p>Parameter types must be part of the identity: {@code java.io.File}
     * declares both {@code File(String)} and {@code File(String, String)}, and a
     * name-only key made the second constructor look like a duplicate of the
     * first, so it was never hooked.
     */
    private static String signatureOf(Executable target) {
        StringBuilder sb = new StringBuilder();
        sb.append(target.getDeclaringClass().getName())
                .append('#')
                .append(target.getName())
                .append('(');
        Class<?>[] types = target.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(types[i].getName());
        }
        return sb.append(')').toString();
    }

    /** One-line summary for log analysis; {@code failed}/{@code missing} should be 0. */
    public static String stats() {
        return "installed=" + INSTALLED.get()
                + " failed=" + FAILED.get()
                + " missingTargets=" + MISSING.get()
                + " duplicatesSuppressed=" + DUPLICATE.get();
    }

    /** Drops the de-duplication bookkeeping so a later pass may re-install hooks. */
    public static void resetRegistry() {
        REGISTERED.clear();
    }

    // ------------------------------------------------------------------
    // Target lookup - never throws
    // ------------------------------------------------------------------

    /** Resolves a class without running its initializer; {@code null} when absent. */
    public static Class<?> clazz(String name) {
        // The guest loader first: it is child-first for the game's own classes and still
        // hands framework and launcher names to the parent, so it returns the class
        // object the game actually runs -- the only one a hook on it can affect. (Before
        // the dex merge was dropped, the launcher's loader defined everything and the
        // order here did not matter.)
        ClassLoader guest = gameClassLoader;
        if (guest != null && guest != HookProvider.class.getClassLoader()) {
            Class<?> fromGame = clazzQuietly(name, guest);
            if (fromGame != null) {
                return fromGame;
            }
        }
        Class<?> found = clazzQuietly(name, HookProvider.class.getClassLoader());
        if (found == null) {
            Log.w(TAG, "class unavailable: " + name);
        }
        return found;
    }

    public static Class<?> clazz(String name, ClassLoader loader) {
        Class<?> found = clazzQuietly(name, loader);
        if (found == null) {
            Log.w(TAG, "class unavailable: " + name);
        }
        return found;
    }

    private static Class<?> clazzQuietly(String name, ClassLoader loader) {
        try {
            return Class.forName(name, false, loader);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Class loader that defines the hosted game. Registered by
     * {@code Alternative} once the guest {@code LoadedApk} exists; hook targets
     * resolve through it when the launcher's own loader does not define them.
     */
    private static volatile ClassLoader gameClassLoader;

    public static void setGameClassLoader(ClassLoader loader) {
        gameClassLoader = loader;
    }

    public static ClassLoader getGameClassLoader() {
        return gameClassLoader;
    }

    /** Declared method with an exact parameter list; {@code null} when the signature moved. */
    public static Method method(Class<?> owner, String name, Class<?>... parameterTypes) {
        if (owner == null) {
            return null;
        }
        try {
            Method m = HIDDEN_API_GATED
                    ? HiddenApiBypass.getDeclaredMethod(owner, name, parameterTypes)
                    : owner.getDeclaredMethod(name, parameterTypes);
            if (m == null) {
                return null;
            }
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            Log.w(TAG, "method unavailable: " + owner.getName() + "#" + name
                    + signature(parameterTypes) + " (" + t.getClass().getSimpleName() + ")");
            return null;
        }
    }

    public static Method method(String ownerName, String name, Class<?>... parameterTypes) {
        return method(clazz(ownerName), name, parameterTypes);
    }

    /** Declared constructor with an exact parameter list; {@code null} when absent. */
    public static Constructor<?> constructor(Class<?> owner, Class<?>... parameterTypes) {
        if (owner == null) {
            return null;
        }
        try {
            Constructor<?> c = HIDDEN_API_GATED
                    ? HiddenApiBypass.getDeclaredConstructor(owner, parameterTypes)
                    : owner.getDeclaredConstructor(parameterTypes);
            if (c == null) {
                return null;
            }
            c.setAccessible(true);
            return c;
        } catch (Throwable t) {
            Log.w(TAG, "constructor unavailable: " + owner.getName() + signature(parameterTypes));
            return null;
        }
    }

    /**
     * Every method named {@code name} that {@code owner} itself declares.
     *
     * <p>Deliberately does <b>not</b> walk superclasses. Requesting
     * {@code BaseDexClassLoader#findLibrary} and following the chain also yields
     * {@code java.lang.ClassLoader#findLibrary}, whose base implementation
     * returns {@code null}; hooking that breaks every {@code loadLibrary} call in
     * the process. Use {@link #inheritedMethodsByName} only when the target is
     * genuinely expected on a supertype.
     */
    @SuppressWarnings("unchecked")
    public static List<Method> methodsByName(Class<?> owner, String name) {
        return declaredMethodsByName(owner, name, false);
    }

    /** Same as {@link #methodsByName} but also searches superclasses. */
    public static List<Method> inheritedMethodsByName(Class<?> owner, String name) {
        return declaredMethodsByName(owner, name, true);
    }

    @SuppressWarnings("unchecked")
    private static List<Method> declaredMethodsByName(Class<?> owner, String name, boolean inherited) {
        List<Method> found = new ArrayList<>();
        if (owner == null || name == null) {
            return found;
        }
        for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
            // HiddenApiBypass.getDeclaredMethods returns a raw List that also
            // contains the class' Constructors, so every element needs a type
            // check before it can be treated as a Method.
            List<?> declared = HIDDEN_API_GATED
                    ? HiddenApiBypass.getDeclaredMethods(c)
                    : Arrays.asList(c.getDeclaredMethods());
            for (Object element : declared) {
                if (!(element instanceof Method)) {
                    continue;
                }
                Method m = (Method) element;
                if (m.getName().equals(name)) {
                    m.setAccessible(true);
                    found.add(m);
                }
            }
            if (!inherited) {
                break;
            }
        }
        if (found.isEmpty()) {
            Log.w(TAG, "no method named " + name + " in " + owner.getName()
                    + (inherited ? " (or its superclasses)" : ""));
        }
        return found;
    }

    /** Convenience: the single/first overload of {@code name}, or {@code null}. */
    public static Method firstMethodByName(Class<?> owner, String name) {
        List<Method> found = methodsByName(owner, name);
        return found.isEmpty() ? null : found.get(0);
    }

    private static String signature(Class<?>[] parameterTypes) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < parameterTypes.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(parameterTypes[i] == null ? "null" : parameterTypes[i].getSimpleName());
        }
        return sb.append(')').toString();
    }
}
