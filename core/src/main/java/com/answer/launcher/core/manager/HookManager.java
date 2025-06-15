package com.answer.launcher.core.manager;

import com.answer.launcher.core.hook.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class HookManager {
    private static volatile HookManager manager;

    private static List<BaseHook> hooks = new ArrayList<>();
    private static List<Integer> hooked = new ArrayList<>();
    private static List<Integer> pausedHooks = new ArrayList<>();

    static {
        hooks.add(new ActivityThreadHook());
        hooks.add(new EnvironmentHook());
        hooks.add(new LoadLibraryHook());
        hooks.add(new SplashScreenHook());
        hooks.add(new UnixFileSystemHook());
        hooks.add(new XboxHook());
    }

    private HookManager() {
        // private constructor to prevent instantiation
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

    public void inject() {
        int i = 0;
        for (BaseHook hook : hooks) {
            if (hook != null && !hooked.contains(i) && !pausedHooks.contains(i)) {
                hook.init();
                hooked.add(i);
            }
            i++;
        }
    }

    public void inject(int site) {
        if (site >= 0 && site < hooks.size()) {
            if (!pausedHooks.contains(site)) {
                hooks.get(site).init();
                hooked.add(site);
            } else {
                System.out.println("Hook at site " + site + " is paused");
            }
        } else {
            System.out.println("Invalid hook site");
        }
    }

    public void pauseHook(int site) {
        if (site >= 0 && site < hooks.size() && !pausedHooks.contains(site)) {
            pausedHooks.add(site);
        } else {
            System.out.println("Invalid hook site or hook already paused");
        }
    }

    public void resumeHook(int site) {
        if (pausedHooks.contains(site)) {
            pausedHooks.remove(Integer.valueOf(site));
        } else {
            System.out.println("Hook at site " + site + " is not paused");
        }
    }

    public void injectAndRemovePaused(int site) {
        if (site >= 0 && site < hooks.size()) {
            hooks.get(site).init();
            hooked.add(site);
            if (pausedHooks.contains(site)) {
                pausedHooks.remove(Integer.valueOf(site));
            }
        } else {
            System.out.println("Invalid hook site");
        }
    }
}
