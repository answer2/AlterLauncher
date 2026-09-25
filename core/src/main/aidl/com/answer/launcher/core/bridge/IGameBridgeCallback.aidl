// Push side of the :game -> UI bridge. Registered through
// IGameBridge.registerCallback by whoever needs live updates.
package com.answer.launcher.core.bridge;

oneway interface IGameBridgeCallback {
    void onRuntimeStateChanged(String state);
    void onPluginsChanged();
}
