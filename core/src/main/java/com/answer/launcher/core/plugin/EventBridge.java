package com.answer.launcher.core.plugin;

import com.answer.launcher.api.ChatMessageEvent;
import com.answer.launcher.api.GameEvents;

/**
 * Entry points the native layer (cpp/main.cpp) calls back into when a
 * signature-hooked game function fires. The chat-message hook (C11/C12,
 * offsets guiDataSendClientMessage / guiDataAddMessage in game-hooks.ini) is
 * native and has no Java Member, so the only way a plugin can see it is through
 * this bridge.
 *
 * <p>TODO(native): wire the JNI upcall from the shadowhook trampoline in
 * main.cpp to {@link #emitChatMessage}. Until that lands, CHAT_MESSAGE has
 * subscribers but no emitter; ACTIVITY_CREATED is emitted purely from Java.
 */
public final class EventBridge {

    private EventBridge() {
    }

    /** Called (once the native upcall is wired) for every chat / system message. */
    public static void emitChatMessage(String message) {
        if (message == null) return;
        PluginEventBus.emit(GameEvents.CHAT_MESSAGE, new ChatMessageEvent(message));
    }
}
