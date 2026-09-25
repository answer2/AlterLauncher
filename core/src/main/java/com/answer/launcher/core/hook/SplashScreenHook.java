package com.answer.launcher.core.hook;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.PopupWindow;

import com.answer.launcher.core.LauncherConstants;
import com.answer.launcher.core.manager.Alternative;
import com.answer.launcher.core.manager.MinecraftManager;
import com.answer.launcher.core.manager.NativeCore;
import com.answer.launcher.core.manager.NativePluginManager;
import com.answer.launcher.core.manager.PluginManager;
import com.answer.launcher.core.tool.SystemUtil;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The game's {@code MainActivity#onCreate} is this launcher's real "the game is
 * alive" signal: it is where the activity is registered, deferred intents are
 * replayed, and both plugin loaders run.
 *
 * <p>Two structural changes compared to the previous version:
 * <ul>
 *   <li>The payload no longer lives inside the hook callback. It did, which
 *   meant this hook double-registering (it is installed by
 *   {@link HookManager#inject()} and was also called directly from
 *   {@code Alternative#initEnv}) ran the native plugin load and
 *   {@code PluginManager.loadPlugin()} twice per launch.</li>
 *   <li>The one-time steps are guarded explicitly rather than relying on
 *   {@code onCreate} happening exactly once.</li>
 * </ul>
 */
public class SplashScreenHook extends BaseHook {

    private static final String TAG = "SplashScreenHook";

    /** Set by {@link #init()}; the Instrumentation dispatches game activities to it. */
    private static volatile SplashScreenHook active;

    private final LoadedAfterHook loadedAfterHook = new LoadedAfterHook();
    private final AtomicBoolean pluginsLoaded = new AtomicBoolean(false);
    private final AtomicBoolean nativePluginsLoaded = new AtomicBoolean(false);

    /** No longer true: this group now observes the activity via Instrumentation. */
    @Override
    public boolean requiresGameDex() {
        return false;
    }

    /**
     * Activates this group.
     *
     * <p>Deliberately does NOT hook {@code MainActivity#onCreate}. Hooking it
     * makes AlterCore initialize the declaring class, whose {@code <clinit>}
     * calls {@code System.loadLibrary("c++_shared")}; Minecraft's native
     * libraries live under a *different* package's install directory, so
     * Android 16 refuses them inside the launcher's class-loader namespace:
     * <pre>
     * UnsatisfiedLinkError: dlopen failed: library
     *   "/system_ext/lib64/libc++_shared.so" needed or dlopened by
     *   "/apex/com.android.art/lib64/libnativeloader.so" is not accessible
     *   for the namespace "clns-9"
     * </pre>
     * Instead the already-installed {@code AlterInstrumentation} observes the
     * activity being created and calls {@link #onGameActivityCreated}. That is
     * a framework hook point, so it is stable across Android and game versions
     * and needs no game dex to be hookable.
     */
    @Override
    public void init() {
        active = this;
    }

    /** True for Minecraft's MainActivity and for subclasses of it (e.g. LauncherActivity). */
    public static boolean isGameActivity(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            if (c.getName().equals(LauncherConstants.MINECRAFTACTIVITY)) {
                return true;
            }
        }
        return false;
    }

    /** Invoked by {@code AlterInstrumentation#callActivityOnCreate}, before the game's own onCreate. */
    public void onGameActivityCreated(android.app.Activity game) {
        onGameActivityCreate(game);
    }

    /** Whether a game-activity observer is currently installed. */
    public static boolean isArmed() {
        return active != null;
    }

    /** Routes a created activity to the active instance, if any. */
    public static void dispatch(android.app.Activity game) {
        if (game == null) {
            return;
        }
        SplashScreenHook target = active;
        if (target == null || !isGameActivity(game.getClass())) {
            return;
        }
        try {
            target.onGameActivityCreated(game);
        } catch (Throwable t) {
            // Everything this hook does is cosmetic (splash teardown, replaying the
            // launch intent, loading plugins) or best-effort (creating the notification
            // service, whose super-class com.google.firebase.messaging.* the launcher
            // never ships). None of it may abort activity creation: an exception here
            // used to escape through Instrumentation.callActivityOnCreate.
            Log.e(TAG, "game activity create work failed for "
                    + game.getClass().getName() + ": " + t);
        }
    }

    private void onGameActivityCreate(Object thisObject) {
        if (!(thisObject instanceof android.app.Activity)) {
            Log.w(TAG, "unexpected onCreate receiver: " + thisObject);
            return;
        }
        android.app.Activity game = (android.app.Activity) thisObject;
        MinecraftManager.getManager().setActivity(game);

        replayIntent(game);
        loadedAfterHook.init();
        loadNativePlugins(game);
        loadPluginsOnce();

        if (LauncherConstants.isDebug) {
            showDebugOverlay(game);
        }
    }

    /**
     * The launcher starts the game activity itself, so the intent that carries
     * a world/resource-pack to import never reaches Minecraft's own
     * {@code processIntent}. Hand it over explicitly.
     */
    private void replayIntent(android.app.Activity game) {
        try {
            Intent intent = game.getIntent();
            if (intent == null) {
                return;
            }
            Method processIntent = HookProvider.method(
                    game.getClass(), "processIntent", Intent.class);
            if (processIntent != null) {
                processIntent.invoke(game, intent);
            }
        } catch (Throwable t) {
            Log.w(TAG, "processIntent replay unavailable", t);
        }
    }

    private void loadNativePlugins(android.app.Activity game) {
        if (!nativePluginsLoaded.compareAndSet(false, true)) {
            return;
        }
        try {
            for (String path : NativePluginManager.getManager(Alternative.getManager().mContext)
                    .getNativeFiles()) {
                System.load(path);
                Log.d(TAG, "loaded native plugin " + path);
            }
        } catch (Throwable t) {
            nativePluginsLoaded.set(false);
            Log.e(TAG, "native plugin load failed", t);
        }
    }

    private void loadPluginsOnce() {
        if (!pluginsLoaded.compareAndSet(false, true)) {
            return;
        }
        try {
            PluginManager.getManager(MinecraftManager.getManager().getContext()).loadPlugin();
        } catch (Throwable t) {
            pluginsLoaded.set(false);
            Log.e(TAG, "plugin load failed", t);
        }
    }

    /** Debug-only floating control; never allowed to break the game's onCreate. */
    private void showDebugOverlay(android.app.Activity game) {
        try {
            LinearLayout layout = new LinearLayout(game);
            layout.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            Button button = new Button(game);
            button.setText("send");
            button.setTextSize(18);
            button.setOnClickListener(v -> NativeCore.sendMessage("你好"));
            layout.addView(button);

            PopupWindow popup = new PopupWindow();
            popup.setContentView(layout);
            popup.setWidth(LinearLayout.LayoutParams.WRAP_CONTENT);
            popup.setHeight(LinearLayout.LayoutParams.WRAP_CONTENT);
            popup.setTouchable(true);
            popup.setFocusable(false);
            popup.setOutsideTouchable(false);
            popup.setTouchInterceptor((View v, MotionEvent event) -> false);
            popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));

            game.getWindow().getDecorView().post(() -> popup.showAtLocation(
                    game.getWindow().getDecorView(), Gravity.CENTER, 0, 0));
        } catch (Throwable t) {
            Log.w(TAG, "debug overlay skipped", t);
        }
    }
}
