package com.answer.launcher.core.plugin;

import com.answer.launcher.api.EventType;
import com.answer.launcher.api.GameEvent;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Single in-process bus for {@link GameEvent}s. {@code EventManagerImpl}
 * subscribes plugins to it; the game hooks post to it — {@link
 * com.answer.launcher.core.hook.LoadedAfterHook} emits ACTIVITY_CREATED purely
 * from Java, {@link EventBridge#emitChatMessage} is the upcall target the native
 * chat hook (C11/C12) will call once wired. Handlers run on the posting thread.
 */
public final class PluginEventBus {

    private static final String TAG = "PluginEventBus";
    private static final Map<EventType<?>, List<Consumer<?>>> HANDLERS = new ConcurrentHashMap<>();

    private PluginEventBus() {
    }

    public static <E extends GameEvent> void subscribe(EventType<E> type, Consumer<E> handler) {
        if (type == null || handler == null) return;
        HANDLERS.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(handler);
    }

    @SuppressWarnings("unchecked")
    public static <E extends GameEvent> void emit(EventType<E> type, E event) {
        List<Consumer<?>> list = HANDLERS.get(type);
        if (list == null || list.isEmpty()) return;
        for (Consumer<?> c : new ArrayList<>(list)) {
            try {
                ((Consumer<E>) c).accept(event);
            } catch (Throwable t) {
                Log.w(TAG, "handler for " + type + " threw", t);
            }
        }
    }

    public static void clear() {
        HANDLERS.clear();
    }
}
