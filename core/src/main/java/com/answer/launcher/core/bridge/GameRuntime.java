package com.answer.launcher.core.bridge;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Process-local record of how far the {@code :game} assembly got, so the bridge
 * service has something to report and the UI process can show it live.
 *
 * <p>Every mutator is safe to call from the assembly path: listener failures are
 * swallowed, because a broken listener must never look like a broken runtime.
 */
public final class GameRuntime {

    public static final String IDLE = "idle";
    public static final String ASSEMBLING = "assembling";
    public static final String READY = "ready";
    public static final String FAILED_PREFIX = "failed:";

    public interface StateListener {
        void onStateChanged(String state);
    }

    private static volatile String state = IDLE;

    private static final List<StateListener> listeners = new CopyOnWriteArrayList<>();

    private GameRuntime() {
    }

    public static String state() {
        return state;
    }

    public static boolean isReady() {
        return READY.equals(state);
    }

    public static void setAssembling() {
        set(ASSEMBLING);
    }

    public static void setReady() {
        set(READY);
    }

    public static void setFailed(String reason) {
        set(FAILED_PREFIX + (reason == null ? "unknown" : reason));
    }

    public static void addStateListener(StateListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public static void removeStateListener(StateListener listener) {
        listeners.remove(listener);
    }

    private static synchronized void set(String next) {
        if (next == null || next.equals(state)) {
            return;
        }
        state = next;
        for (StateListener listener : listeners) {
            try {
                listener.onStateChanged(next);
            } catch (Throwable ignored) {
                // A listener that throws must not abort the assembly it is watching.
            }
        }
    }
}
