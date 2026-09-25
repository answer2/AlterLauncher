package com.answer.launcher.core.manager;

import android.util.Log;

import com.answer.launcher.core.hook.ActivityThreadHook;
import com.answer.launcher.core.hook.BaseHook;
import com.answer.launcher.core.hook.EnvironmentHook;
import com.answer.launcher.core.hook.HookProvider;
import com.answer.launcher.core.hook.LoadLibraryHook;
import com.answer.launcher.core.hook.SplashScreenHook;
import com.answer.launcher.core.hook.UnixFileSystemHook;
import com.answer.launcher.core.hook.XboxHook;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Owns the hook set and the order in which it is installed.
 *
 * <p>Each hook is now installed at most once, and a hook that throws (including
 * an {@link Error} - {@code UnsatisfiedLinkError} and {@code NoClassDefFoundError}
 * are the common ones when a target class only exists in the game's dex) no
 * longer prevents the remaining hooks from running. Previously {@code inject()}
 * caught {@code Exception} only, so a single {@code Error} aborted the loop and
 * every later hook silently never installed.
 */
public class HookManager {

    private static final String TAG = "HookManager";

    private static volatile HookManager manager;

    private final List<BaseHook> hooks = new ArrayList<>();
    private final List<Integer> paused = new ArrayList<>();

    private HookManager() {
        hooks.add(new ActivityThreadHook());
        hooks.add(new EnvironmentHook());
        hooks.add(new LoadLibraryHook());
        hooks.add(new SplashScreenHook());
        hooks.add(new UnixFileSystemHook());
        hooks.add(new XboxHook());
    }

    public static HookManager getManager() {
        if (manager == null) {
            synchronized (HookManager.class) {
                if (manager == null) {
                    manager = new HookManager();
                }
            }
        }
        return manager;
    }

    /**
     * Installs the framework-level groups. Safe to call before the game's dex
     * has been merged; groups that need it are deferred to
     * {@link #injectGameHooks()}.
     */
    public void inject() {
        for (int site = 0; site < hooks.size(); site++) {
            BaseHook hook = hooks.get(site);
            if (hook != null && !hook.requiresGameDex()) {
                injectSite(site);
            }
        }
        Log.i(TAG, "framework hooks done - " + HookProvider.stats());
    }

    /**
     * Installs the groups whose targets live in the game's dex. Must run after
     * {@code HotFix.hotFix()} has merged {@code dexElements}, otherwise
     * {@code MainActivity} is not loadable and the game's {@code onCreate} is
     * never hooked.
     */
    public void injectGameHooks() {
        for (int site = 0; site < hooks.size(); site++) {
            BaseHook hook = hooks.get(site);
            if (hook != null && hook.requiresGameDex()) {
                injectSite(site);
            }
        }
        Log.i(TAG, "game hooks done - " + HookProvider.stats());
    }

    /** Installs one hook by position, forcing a re-run even if it already ran. */
    public void inject(int site) {
        if (site < 0 || site >= hooks.size()) {
            Log.w(TAG, "invalid hook site " + site);
            return;
        }
        if (paused.contains(site)) {
            Log.i(TAG, "hook site " + site + " is paused");
            return;
        }
        BaseHook hook = hooks.get(site);
        hook.setInjected(false);
        apply(hook);
    }

    private void injectSite(int site) {
        BaseHook hook = hooks.get(site);
        if (hook == null || hook.isInjected() || paused.contains(site)) {
            return;
        }
        apply(hook);
    }

    private void apply(BaseHook hook) {
        try {
            hook.init();
        } catch (Throwable t) {
            Log.e(TAG, "hook " + hook.getClass().getSimpleName() + " failed", t);
        } finally {
            // Marked even on failure: a hook that threw once is not going to
            // succeed on a second pass within the same load, and re-running a
            // partially-applied hook is worse than running it once.
            hook.setInjected(true);
        }
    }

    public void pauseHook(int site) {
        if (site >= 0 && site < hooks.size() && !paused.contains(site)) {
            paused.add(site);
        } else {
            Log.w(TAG, "invalid hook site or hook already paused");
        }
    }

    public void resumeHook(int site) {
        paused.remove(Integer.valueOf(site));
    }

    public void injectAndRemovePaused(int site) {
        if (site < 0 || site >= hooks.size()) {
            Log.w(TAG, "invalid hook site");
            return;
        }
        paused.remove(Integer.valueOf(site));
        inject(site);
    }

    public List<BaseHook> getHooks() {
        return Collections.unmodifiableList(hooks);
    }
}
