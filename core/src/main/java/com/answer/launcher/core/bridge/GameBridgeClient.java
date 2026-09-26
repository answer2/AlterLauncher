package com.answer.launcher.core.bridge;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.answer.launcher.core.LauncherConstants;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The UI-process half of the bridge. One process-wide instance: it holds a single
 * connection to {@link GameBridgeService} and fans the callbacks out to listeners.
 *
 * <p>{@link #bind(Context)} parks a read-only binding with no
 * {@code BIND_AUTO_CREATE}: asking "is the game loaded?" must never be able to
 * cold-boot the game process, and the launcher home screen has no business paying
 * for a Minecraft runtime it is not showing. The connection stays parked and fires
 * as soon as the service exists, which is the live-read behaviour the UI needs.
 *
 * <p>{@link #wake(Context)} is the other half: only the paths where the user has
 * actually asked to play may bring {@code :game} up, and they upgrade the parked
 * binding with {@code BIND_AUTO_CREATE}. GameHostActivity would start the process a
 * moment later anyway — this just gets the service there first so the runtime is
 * warm and the state the UI reads is real.
 */
public final class GameBridgeClient {

    private static final String TAG = "GameBridgeClient";

    public interface Listener {
        /** Any reported state changed: re-read the getters. */
        void onBridgeChanged();
    }

    private static volatile GameBridgeClient instance;

    private volatile IGameBridge bridge;
    private volatile boolean binding;
    private volatile String runtimeState = GameRuntime.IDLE;

    /** The flags the current binding was asked for; 0 means "read-only park". */
    private int boundFlags;

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final List<Context> boundContexts = Collections.synchronizedList(new ArrayList<>());

    private final IGameBridgeCallback.Stub callback = new IGameBridgeCallback.Stub() {
        @Override
        public void onRuntimeStateChanged(String state) {
            runtimeState = state == null ? GameRuntime.IDLE : state;
            if (GameRuntime.IDLE.equals(runtimeState)) {
                /*
                 * The game window is gone. Holding BIND_AUTO_CREATE any longer would
                 * keep a dead runtime resident, and the next launch would land in
                 * that same stale process instead of a fresh one. Downgrade back to
                 * the parked read-only binding, which costs nothing and reconnects
                 * by itself whenever :game comes back.
                 */
                releaseWake();
                return;
            }
            notifyListeners();
        }

        @Override
        public void onPluginsChanged() {
            notifyListeners();
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            bridge = IGameBridge.Stub.asInterface(service);
            binding = false;
            try {
                bridge.registerCallback(callback);
                runtimeState = bridge.getRuntimeState();
            } catch (Throwable t) {
                Log.w(TAG, "handshake with " + name + " failed: " + t);
            }
            Log.i(TAG, "connected to the game runtime (" + runtimeState + ")");
            notifyListeners();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // :game died or was killed. The parked binding reconnects if it returns.
            bridge = null;
            binding = false;
            runtimeState = GameRuntime.IDLE;
            Log.w(TAG, "lost the game runtime (" + name + ")");
            notifyListeners();
        }

        @Override
        public void onBindingDied(ComponentName name) {
            bridge = null;
            binding = false;
            runtimeState = GameRuntime.IDLE;
            /*
             * The binding is gone on the AMS side too, so the bookkeeping has to go
             * with it: without this, a later wake() sees a matching boundFlags and
             * skips the rebind that would actually bring :game back.
             */
            boundContexts.clear();
            boundFlags = 0;
            notifyListeners();
        }
    };

    private GameBridgeClient() {
    }

    public static GameBridgeClient get() {
        GameBridgeClient local = instance;
        if (local == null) {
            synchronized (GameBridgeClient.class) {
                local = instance;
                if (local == null) {
                    local = instance = new GameBridgeClient();
                }
            }
        }
        return local;
    }

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public boolean isConnected() {
        return bridge != null;
    }

    /**
     * Park a read-only binding on {@code :game}. Safe to call repeatedly: the
     * connection is process-wide and idempotent per Context. Never starts the
     * process — see the class comment for why.
     */
    public synchronized void bind(Context context) {
        bindInternal(context, 0);
    }

    /**
     * Bring {@code :game} up and hold a binding on it. Call this from the launch
     * paths only, immediately before starting {@code GameHostActivity}.
     */
    public synchronized void wake(Context context) {
        bindInternal(context, Context.BIND_AUTO_CREATE);
    }

    private void bindInternal(Context context, int flags) {
        Context app = context == null ? null : context.getApplicationContext();
        if (app == null || bridge != null) {
            return;
        }
        boolean known = boundContexts.contains(app);
        if (known && boundFlags == flags) {
            // The same request is already outstanding; nothing to add.
            return;
        }
        if (known) {
            /*
             * AMS keeps the flags of the FIRST bind made with a given ServiceConnection,
             * so upgrading a parked read-only binding to BIND_AUTO_CREATE means dropping
             * it and asking again — otherwise wake() would never start :game.
             */
            try {
                app.unbindService(connection);
            } catch (IllegalArgumentException ignored) {
                // The binding was already gone (the process died, or it never registered).
            }
            boundContexts.remove(app);
        }
        boundContexts.add(app);
        boundFlags = flags;
        binding = true;
        try {
            app.bindService(serviceIntent(), connection, flags);
        } catch (Throwable t) {
            binding = false;
            boundFlags = 0;
            boundContexts.remove(app);
            Log.w(TAG, "bindService failed: " + t);
        }
    }

    public synchronized void unbind(Context context) {
        Context app = context == null ? null : context.getApplicationContext();
        if (app == null || !boundContexts.remove(app)) {
            return;
        }
        try {
            app.unbindService(connection);
        } catch (IllegalArgumentException ignored) {
            // Already gone (process restart, or the connection never registered).
        }
        if (boundContexts.isEmpty()) {
            bridge = null;
            binding = false;
            boundFlags = 0;
        }
    }

    /**
     * Downgrade the wake binding back to the parked read-only one, keeping the
     * same set of contexts so a later {@link #wake(Context)} still finds them
     * known and can upgrade again. Nothing here kills {@code :game} — it only
     * stops the UI process from insisting that it stay resident.
     */
    private synchronized void releaseWake() {
        if (boundFlags == 0) {
            return;
        }
        List<Context> parked = new ArrayList<>(boundContexts);
        for (Context app : parked) {
            try {
                app.unbindService(connection);
            } catch (IllegalArgumentException ignored) {
                // Already gone.
            }
        }
        boundContexts.clear();
        boundFlags = 0;
        bridge = null;
        binding = false;
        runtimeState = GameRuntime.IDLE;
        for (Context app : parked) {
            bindInternal(app, 0);
        }
        notifyListeners();
    }

    public static Intent serviceIntent() {
        return new Intent(LauncherConstants.GAME_BRIDGE_ACTION)
                .setPackage(LauncherConstants.PACKAGE);
    }

    /** Last state reported by :game, or "idle" while nothing is connected. */
    public String runtimeState() {
        IGameBridge local = bridge;
        if (local == null) {
            return runtimeState;
        }
        try {
            runtimeState = local.getRuntimeState();
        } catch (Throwable t) {
            Log.w(TAG, "getRuntimeState failed: " + t);
        }
        return runtimeState;
    }

    public boolean isGameReady() {
        IGameBridge local = bridge;
        if (local == null) {
            return false;
        }
        try {
            return local.isGameReady();
        } catch (Throwable t) {
            return false;
        }
    }

    public String hookStats() {
        return call(new Caller<String>() {
            @Override
            public String call(IGameBridge bridge) throws RemoteException {
                return bridge.getHookStats();
            }
        }, "no runtime connected");
    }

    public List<String> pluginFiles() {
        return call(new Caller<List<String>>() {
            @Override
            public List<String> call(IGameBridge bridge) throws RemoteException {
                return bridge.getPluginFiles();
            }
        }, new ArrayList<String>());
    }

    public List<String> nativePluginFiles() {
        return call(new Caller<List<String>>() {
            @Override
            public List<String> call(IGameBridge bridge) throws RemoteException {
                return bridge.getNativePluginFiles();
            }
        }, new ArrayList<String>());
    }

    /** Entries of the form "name|author|version|description". */
    public List<String> pluginInfos() {
        return call(new Caller<List<String>>() {
            @Override
            public List<String> call(IGameBridge bridge) throws RemoteException {
                return bridge.getPluginInfos();
            }
        }, new ArrayList<String>());
    }

    public String minecraftVersion() {
        return call(new Caller<String>() {
            @Override
            public String call(IGameBridge bridge) throws RemoteException {
                return bridge.getMinecraftVersion();
            }
        }, LauncherConstants.MINECRAFT_VERSION);
    }

    /** Pid of the runtime process, or -1 when it is not running. */
    public int gamePid() {
        IGameBridge local = bridge;
        if (local == null) {
            return -1;
        }
        try {
            return local.getGamePid();
        } catch (Throwable t) {
            return -1;
        }
    }

    public void importPluginFile(final String path) {
        call(new Caller<Void>() {
            @Override
            public Void call(IGameBridge bridge) throws RemoteException {
                bridge.importPluginFile(path);
                return null;
            }
        }, null);
    }

    public void importNativePluginFile(final String path) {
        call(new Caller<Void>() {
            @Override
            public Void call(IGameBridge bridge) throws RemoteException {
                bridge.importNativePluginFile(path);
                return null;
            }
        }, null);
    }

    public void reloadPlugins() {
        call(new Caller<Void>() {
            @Override
            public Void call(IGameBridge bridge) throws RemoteException {
                bridge.reloadPlugins();
                return null;
            }
        }, null);
    }

    private <T> T call(Caller<T> caller, T fallback) {
        IGameBridge local = bridge;
        if (local == null) {
            return fallback;
        }
        try {
            T value = caller.call(local);
            return value == null ? fallback : value;
        } catch (Throwable t) {
            Log.w(TAG, "bridge call failed: " + t);
            return fallback;
        }
    }

    private interface Caller<T> {
        T call(IGameBridge bridge) throws RemoteException;
    }

    private void notifyListeners() {
        for (Listener listener : listeners) {
            try {
                listener.onBridgeChanged();
            } catch (Throwable ignored) {
                // One bad listener must not stop the others.
            }
        }
    }
}
