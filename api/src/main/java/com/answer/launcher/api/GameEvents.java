package com.answer.launcher.api;

/** Canonical {@link EventType} tokens and the event implementations. */
public final class GameEvents {

    public static final EventType<ActivityCreatedEvent> ACTIVITY_CREATED =
            EventType.of("activity_created");
    public static final EventType<ChatMessageEvent> CHAT_MESSAGE =
            EventType.of("chat_message");

    private GameEvents() {
    }
}
