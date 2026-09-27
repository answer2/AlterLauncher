package com.answer.launcher.api;

import java.util.function.Consumer;

/**
 * Typed event bus. A plugin subscribes by {@link EventType} token; the loader
 * wires the handler to the concrete hook that emits that event. A missing event
 * source is a no-op rather than a crash.
 *
 * @see GameEvents
 */
public interface EventManager {

    <E extends GameEvent> void on(EventType<E> type, Consumer<E> handler);
}
