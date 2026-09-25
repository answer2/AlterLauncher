package com.answer.launcher.core.hook;

import android.util.Log;

import com.answer.launcher.core.manager.Alternative;
import com.answer.launcher.core.manager.NativeCore;
import com.answer.launcher.core.tool.SystemUtil;

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
                NativeCore.loadManager();
            } catch (Throwable t) {
                nativeCoreReady.set(false);
                Log.e(TAG, "NativeCore.loadManager() failed", t);
            }
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
