package com.answer.launcher.api;

/**
 * Typed event token. Static constants live in {@link GameEvents}.
 */
public final class EventType<E extends GameEvent> {
    private final String name;

    private EventType(String name) {
        this.name = name;
    }

    public static <E extends GameEvent> EventType<E> of(String name) {
        return new EventType<>(name);
    }

    public String name() {
        return name;
    }

    @Override
    public String toString() {
        return "Event:" + name;
    }
}
