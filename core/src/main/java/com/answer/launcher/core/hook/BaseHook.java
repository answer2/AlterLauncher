package com.answer.launcher.core.hook;

import android.content.Context;

/**
 * Base class for a group of hooks that belong to one concern.
 *
 * <p>The {@code injected} flag exists so that {@link com.answer.launcher.core.manager.HookManager}
 * can guarantee "at most once per load" per group. Registration itself is
 * additionally de-duplicated inside {@link HookProvider} per callback instance,
 * which covers groups that are re-entered through a hook callback (for example
 * {@code LoadedAfterHook}, invoked from the game activity's {@code onCreate}).
 */
public abstract class BaseHook {

    /** Context of the virtual environment; set before {@link HookManager} injects anything. */
    protected static Context context;

    private volatile boolean injected;

    public static void setContext(Context mContext) {
        context = mContext;
    }

    public static Context getContext() {
        return context;
    }

    public final boolean isInjected() {
        return injected;
    }

    public final void setInjected(boolean value) {
        this.injected = value;
    }

    /**
     * Whether every target of this group lives in the game's dex rather than in
     * the framework.
     *
     * <p>Groups that need the game dex are installed only after
     * {@code HotFix} has merged the game's {@code dexElements} into the launcher
     * class loader. Resolving them earlier fails (their classes are not
     * loadable yet), which is why a single flat install pass silently leaves
     * {@code MainActivity#onCreate} unhooked.
     */
    public boolean requiresGameDex() {
        return false;
    }

    /**
     * Installs this group's hooks. Implementations must not throw for a target
     * that simply is not present on the running platform - route every
     * registration through {@link HookProvider}, which already isolates
     * failures per target.
     */
    public abstract void init();
}
