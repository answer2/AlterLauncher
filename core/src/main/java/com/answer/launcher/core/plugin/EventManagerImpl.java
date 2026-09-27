package com.answer.launcher.core.plugin;

import com.answer.launcher.api.EventManager;
import com.answer.launcher.api.EventType;
import com.answer.launcher.api.GameEvent;
import java.util.function.Consumer;

final class EventManagerImpl implements EventManager {

    @Override
    public <E extends GameEvent> void on(EventType<E> type, Consumer<E> handler) {
        PluginEventBus.subscribe(type, handler);
    }
}
