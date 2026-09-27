package com.answer.launcher.core.hook;

import android.content.Context;
import android.util.Log;

import com.answer.launcher.core.LauncherConstants;
import com.answer.launcher.core.manager.Alternative;
import com.answer.launcher.core.manager.NativeCore;
import com.answer.launcher.core.tool.SystemUtil;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Everything that must happen once the game's {@code MainActivity} exists:
 * disable the Braze SDK, attach the notification listener, then map
 * {@code libminecraftpe.so} and install the native signature hooks.
 *
 * <p>{@link #init()} is reachable from the {@code MainActivity#onCreate} hook,
 * which runs again on every recreation (rotation with a config that is not
 * handled, process restart). {@code NativeCore.loadManager()} re-ran
 * {@code System.load()} and the signature scans each time; the once-guard keeps
 * that from happening.
 */
public class LoadedAfterHook extends BaseHook {

    private static final String TAG = "LoadedAfterHook";

    private static final String MAIN_ACTIVITY = "com.mojang.minecraftpe.MainActivity";
    private static final String NOTIFICATION_LISTENER =
            "com.mojang.minecraftpe.NotificationListenerService";

    /** Shipped in {@code core/src/main/assets}, read by {@link #applyHookRules()}. */
    private static final String HOOK_RULES_ASSET = "game-hooks.ini";

    private final AtomicBoolean nativeCoreReady = new AtomicBoolean(false);

    @Override
    public void init() {
        hookBrazeDisabled();

        if (SystemUtil.checkClass(NOTIFICATION_LISTENER)) {
            try {
                Alternative.getManager().createService(NOTIFICATION_LISTENER);
            } catch (Throwable t) {
                // A manually attached service can fail without the game caring;
                // it must not stop the native library from being mapped.
                Log.e(TAG, "createService(" + NOTIFICATION_LISTENER + ") failed", t);
            }
        }

        if (nativeCoreReady.compareAndSet(false, true)) {
            try {
                applyHookRules();
                NativeCore.loadManager();
            } catch (Throwable t) {
                nativeCoreReady.set(false);
                Log.e(TAG, "NativeCore.loadManager() failed", t);
            }
        }

        // Surface the live game Activity to v2 plugins (ACTIVITY_CREATED). The
        // loader has already subscribed plugins at the splash, before this
        // onCreate runs, so handlers see it; on reload the bus is cleared and
        // plugins re-subscribe in onLoad.
        try {
            android.app.Activity activity =
                    com.answer.launcher.core.manager.MinecraftManager.getManager().getActivity();
            if (activity != null) {
                com.answer.launcher.core.plugin.PluginEventBus.emit(
                        com.answer.launcher.api.GameEvents.ACTIVITY_CREATED,
                        new com.answer.launcher.api.ActivityCreatedEvent(activity));
            }
        } catch (Throwable t) {
            Log.w(TAG, "ACTIVITY_CREATED emit failed", t);
        }
    }

    /**
     * Reads {@code assets/game-hooks.ini} and hands it to the native layer, which
     * decides from it which vtable slot to patch and which byte patterns to scan
     * for. The file is optional: passing null leaves the data compiled into
     * {@code main.cpp} in charge, which is what happened before it existed, so a
     * missing or unreadable asset is a warning and never a reason to skip
     * {@code loadManager()}.
     */
    private void applyHookRules() {
        String rules = null;
        try {
            Context context = Alternative.getManager().getContext();
            if (context != null) {
                InputStream in = context.getAssets().open(HOOK_RULES_ASSET);
                try {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                    }
                    rules = out.toString("UTF-8");
                } finally {
                    in.close();
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "no readable " + HOOK_RULES_ASSET + " asset, the built-in hook data "
                    + "stays in charge: " + t);
        }
        try {
            NativeCore.applyHookRules(LauncherConstants.MINECRAFT_VERSION, rules);
        } catch (Throwable t) {
            // The native method only stores what it parsed; failing here cannot
            // be worse than not having called it at all.
            Log.e(TAG, "NativeCore.applyHookRules() failed", t);
        }
    }

    private void hookBrazeDisabled() {
        Class<?> mainActivity = HookProvider.clazz(MAIN_ACTIVITY);
        if (mainActivity == null) {
            return;
        }
        Method isBrazeEnabled = HookProvider.firstMethodByName(mainActivity, "isBrazeEnabled");
        HookProvider.replace(isBrazeEnabled, params -> Boolean.FALSE);
    }
}
