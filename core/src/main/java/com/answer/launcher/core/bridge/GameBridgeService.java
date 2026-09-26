package com.answer.launcher.core.bridge;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Log;

import com.answer.launcher.api.PluginInfo;
import com.answer.launcher.core.LauncherConstants;
import com.answer.launcher.core.hook.HookProvider;
import com.answer.launcher.core.manager.Alternative;
import com.answer.launcher.core.manager.NativePluginManager;
import com.answer.launcher.core.manager.PluginManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The {@code :game} half of the bridge: a binder over the runtime state that the
 * UI process cannot read directly, because the guest LoadedApk, the guest class
 * loader and the hook tables all live in this process now.
 *
 * <p>Declared with {@code android:process=":game"} and not exported. Every method
 * answers with a best-effort value instead of throwing: a caller that binds during
 * assembly should see "assembling", not a dead binder.
 *
 * <p>Binder threads are not the main thread, so nothing here touches a View or a
 * Activity directly — it only reads the manager singletons, whose own state is
 * written from the main thread during {@code Application.onCreate}.
 */
public class GameBridgeService extends Service {

    private static final String TAG = "GameBridge";

    private final RemoteCallbackList<IGameBridgeCallback> callbacks =
            new RemoteCallbackList<>();

    /**
     * How long the process lingers once the game window is gone. The UI process
     * needs a moment to see the idle callback and drop its {@code BIND_AUTO_CREATE};
     * after that this process has nothing left to show for itself, and keeping it
     * alive would mean re-entering Minecraft on top of a runtime whose statics were
     * already torn down. So the process ends here and the next launch is a genuine
     * cold boot. The launcher's own process is never involved.
     */
    private static final long IDLE_REAP_DELAY_MS = 2500L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Runnable reaper = new Runnable() {
        @Override
        public void run() {
            if (!GameRuntime.IDLE.equals(GameRuntime.state())) {
                // Relaunched in the meantime - stay up for it.
                return;
            }
            Log.i(TAG, "runtime idle, reaping the game process (pid " + Process.myPid() + ")");
            Process.killProcess(Process.myPid());
        }
    };

    /**
     * Forwards assembly progress to whoever is watching. Registered in onCreate so
     * a runtime that finished before anyone bound is still reportable — late binders
     * get the current state pushed on register.
     */
    private final GameRuntime.StateListener pump = new GameRuntime.StateListener() {
        @Override
        public void onStateChanged(String state) {
            int n = callbacks.beginBroadcast();
            try {
                for (int i = 0; i < n; i++) {
                    try {
                        callbacks.getBroadcastItem(i).onRuntimeStateChanged(state);
                    } catch (RemoteException ignored) {
                        // The callback list drops dead clients on its own.
                    }
                }
            } finally {
                callbacks.finishBroadcast();
            }
            mainHandler.removeCallbacks(reaper);
            if (GameRuntime.IDLE.equals(state)) {
                mainHandler.postDelayed(reaper, IDLE_REAP_DELAY_MS);
            }
        }
    };

    private final IGameBridge.Stub binder = new IGameBridge.Stub() {

        @Override
        public String getRuntimeState() {
            return GameRuntime.state();
        }

        @Override
        public boolean isGameReady() {
            if (GameRuntime.isReady()) {
                return true;
            }
            try {
                return Alternative.getManager().isGameReady();
            } catch (Throwable t) {
                return false;
            }
        }

        @Override
        public String getHookStats() {
            try {
                return HookProvider.stats();
            } catch (Throwable t) {
                return "stats unavailable: " + t;
            }
        }

        @Override
        public List<String> getPluginFiles() {
            try {
                return new ArrayList<>(
                        PluginManager.getManager(getApplicationContext()).getPluginFiles());
            } catch (Throwable t) {
                Log.w(TAG, "plugin files unavailable: " + t);
                return new ArrayList<>();
            }
        }

        @Override
        public List<String> getNativePluginFiles() {
            try {
                return new ArrayList<>(
                        NativePluginManager.getManager(getApplicationContext()).getNativeFiles());
            } catch (Throwable t) {
                Log.w(TAG, "native plugin files unavailable: " + t);
                return new ArrayList<>();
            }
        }

        @Override
        public List<String> getPluginInfos() {
            List<String> out = new ArrayList<>();
            try {
                Map<String, PluginInfo> infos =
                        PluginManager.getManager(getApplicationContext()).getInfos();
                for (PluginInfo info : infos.values()) {
                    out.add(info.getName() + "|" + info.getAuthor()
                            + "|" + info.getVersion() + "|" + info.getDescription());
                }
            } catch (Throwable t) {
                Log.w(TAG, "plugin infos unavailable: " + t);
            }
            return out;
        }

        @Override
        public String getMinecraftVersion() {
            return LauncherConstants.MINECRAFT_VERSION;
        }

        @Override
        public int getGamePid() {
            return Process.myPid();
        }

        @Override
        public void importPluginFile(String absolutePath) {
            try {
                PluginManager.getManager(getApplicationContext()).addPlugin(absolutePath);
                notifyPluginsChanged();
            } catch (Throwable t) {
                Log.e(TAG, "importPluginFile(" + absolutePath + ") failed", t);
            }
        }

        @Override
        public void importNativePluginFile(String absolutePath) {
            try {
                NativePluginManager.getManager(getApplicationContext())
                        .addNativePlugin(absolutePath);
                notifyPluginsChanged();
            } catch (Throwable t) {
                Log.e(TAG, "importNativePluginFile(" + absolutePath + ") failed", t);
            }
        }

        @Override
        public void reloadPlugins() {
            try {
                /*
                 * Rescan first: the UI process imports by writing into shared app
                 * storage, and this process' list is only as fresh as its last scan.
                 */
                NativePluginManager.getManager(getApplicationContext()).rescan();
                PluginManager manager = PluginManager.getManager(getApplicationContext());
                manager.rescan();
                manager.loadPlugin();
            } catch (Throwable t) {
                // loadPlugin() needs the game activity to exist; before that it
                // reports the missing host rather than killing the binder.
                Log.w(TAG, "reloadPlugins deferred: " + t);
            }
            notifyPluginsChanged();
        }

        @Override
        public void registerCallback(IGameBridgeCallback callback) {
            if (callback == null) {
                return;
            }
            callbacks.register(callback);
            try {
                callback.onRuntimeStateChanged(GameRuntime.state());
            } catch (RemoteException ignored) {
            }
        }

        @Override
        public void unregisterCallback(IGameBridgeCallback callback) {
            if (callback != null) {
                callbacks.unregister(callback);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        GameRuntime.addStateListener(pump);
        Log.i(TAG, "bridge ready in pid " + Process.myPid()
                + ", runtime=" + GameRuntime.state());
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        mainHandler.removeCallbacks(reaper);
        GameRuntime.removeStateListener(pump);
        callbacks.kill();
        super.onDestroy();
    }

    private void notifyPluginsChanged() {
        int n = callbacks.beginBroadcast();
        try {
            for (int i = 0; i < n; i++) {
                try {
                    callbacks.getBroadcastItem(i).onPluginsChanged();
                } catch (RemoteException ignored) {
                }
            }
        } finally {
            callbacks.finishBroadcast();
        }
    }
}
