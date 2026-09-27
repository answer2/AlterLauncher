package com.answer.launcher.api;

/** Fired when the game pushes a chat / system message. Source: the C11/C12 chat hooks. */
public final class ChatMessageEvent implements GameEvent {

    private final String message;

    public ChatMessageEvent(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}
