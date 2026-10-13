#include <hook/GameHooks.h>

#include <android/android_log.h>
#include <mcpe/api/ModApi.h>
#include <mcpe/core/GameSDK.h>
#include <mcpe/network/Network.h>

// Hook handler bodies. Every one of them forwards to the original: a wrong
// pattern or slot costs a missed flag, never the game.
namespace GameHooks {

void *clientInstance = nullptr;
uintptr_t clientInstanceVtable = 0;
uintptr_t guiDataVtable = 0;
long updateCalls = 0;

std::atomic<bool> isPauseMenuOpen{false};
std::atomic<bool> isHudScreenOpen{false};
std::atomic<bool> isShowingMenu{false};
long pauseMenuEvents = 0, hudScreenEvents = 0, showingMenuCalls = 0;

namespace {
void *g_minecraftGame = nullptr;
bool g_worldEntered = false;
std::atomic<bool> g_selfTestRequested{false};
}  // namespace

void *minecraftGame() { return g_minecraftGame; }
bool worldEntered() { return g_worldEntered; }

void *(*minecraftUpdateReal)(void *) = nullptr;

void *minecraftUpdateHook(void *minecraftGame_) {
    g_minecraftGame = minecraftGame_;
    GameSdk::setMinecraftGameFromHook(reinterpret_cast<uintptr_t>(minecraftGame_));
    if (minecraftUpdateReal != nullptr) minecraftUpdateReal(minecraftGame_);
    return nullptr;
}

void (*guiDataAddMessageReal)(void *, const std::string &, const std::string &,
                              const std::optional<std::string> &,
                              const std::string &, const std::string &) = nullptr;

void guiDataAddMessageHook(void *guiData, const std::string &sender, const std::string &message,
                           const std::optional<std::string> &metadata,
                           const std::string &extra1, const std::string &extra2) {
    LOGD("GuiData::addChatMessage gd=%p sender=%s is : %s", guiData, sender.c_str(),
         message.c_str());
    // The game hands us its own GuiData on every chat line. Publish it through
    // the SDK's validated setter (it re-checks the vtable against the RTTI
    // tables) and flush any queued line while we are on the game thread.
    g_worldEntered = true;  // a live chat line means a live HUD
    if (message == "666" && !g_selfTestRequested.exchange(true)) {
        __android_log_print(ANDROID_LOG_INFO, "AlterSelfTest", "manual self-test requested by chat");
        mcpe::selfTestWarmup();
        GameSdk::requestMessage("[SDK] Self-test queued; results: AlterSelfTest log.");
    }
    if (GameSdk::clientInstance() != 0) {
        GameSdk::setGuiDataFromHook(reinterpret_cast<uintptr_t>(guiData));
        GameSdk::pumpFromAddMessageHook();
    }
    if (guiDataAddMessageReal != nullptr)
        guiDataAddMessageReal(guiData, sender, message, metadata, extra1, extra2);
}

void *(*guiDataSendClientMessageReal)(void *, const std::string &, const std::string &) = nullptr;

void *guiDataSendClientMessageHook(void *self, const std::string &msg, const std::string &msg2) {
    LOGD("Minecraft Hook GuiData::sendClientMessage : %p  %s %s", self, msg.c_str(), msg2.c_str());
    if (guiDataSendClientMessageReal != nullptr) guiDataSendClientMessageReal(self, msg, msg2);
    return nullptr;
}

void *(*joinMultiplayerGameReal)(void *, const std::string &, void *) = nullptr;

void *joinMultiplayerGameHook(void *minecraftGame_, const std::string &a1, void *a2) {
    LOGD("Minecraft Hook MinecraftGame::joinMultiplayerGame : %p  %s %p", minecraftGame_, a1.c_str(),
         a2);
    g_worldEntered = true;
    if (joinMultiplayerGameReal != nullptr) joinMultiplayerGameReal(minecraftGame_, a1, a2);
    return nullptr;
}

// ---------------------------------------------------------------------------
// Menu-state hooks (M4, docs/levi-compat-design.md 4.4)
//
// Same semantics as preloader-android's GameHooks.cpp (Apache-2.0, vendored
// under LICENSES/ + NOTICE): the four non-virtual PauseMenu/HudScreen functions
// are reached by byte pattern through hookBySignature; the virtual
// ClientInstance::isShowingMenu is patched at its RTTI vtable slot like
// clientInstanceUpdate.
// ---------------------------------------------------------------------------

void (*pauseMenuDtorReal)(void *) = nullptr;
void pauseMenuDtorHook(void *self) {
    isPauseMenuOpen.store(false);
    pauseMenuEvents++;
    LOGD("PauseMenu dtor: menu closed");
    if (pauseMenuDtorReal != nullptr) pauseMenuDtorReal(self);
}

void (*pauseMenuOpenReal)(void *) = nullptr;
void pauseMenuOpenHook(void *self) {
    isPauseMenuOpen.store(true);
    isShowingMenu.store(true);
    pauseMenuEvents++;
    LOGD("PauseMenu open");
    if (pauseMenuOpenReal != nullptr) pauseMenuOpenReal(self);
}

void (*hudScreenDtorReal)(void *) = nullptr;
void hudScreenDtorHook(void *self) {
    isHudScreenOpen.store(false);
    isPauseMenuOpen.store(false);
    isShowingMenu.store(false);
    GameSdk::setHudVisible(false);
    hudScreenEvents++;
    LOGD("HudScreen dtor: screens gone");
    if (hudScreenDtorReal != nullptr) hudScreenDtorReal(self);
}

void (*hudScreenOpenReal)(void *) = nullptr;
void hudScreenOpenHook(void *self) {
    isHudScreenOpen.store(true);
    isPauseMenuOpen.store(false);
    isShowingMenu.store(false);
    hudScreenEvents++;
    g_worldEntered = true;  // single-player world load ends with the HUD
    GameSdk::setHudVisible(true);
    LOGD("HudScreen open");
    if (hudScreenOpenReal != nullptr) hudScreenOpenReal(self);
}

bool (*clientInstanceIsShowingMenuReal)(void *) = nullptr;
bool clientInstanceIsShowingMenuHook(void *self) {
    bool res = clientInstanceIsShowingMenuReal(self);
    isShowingMenu.store(res);
    showingMenuCalls++;
    return res;
}

uintptr_t (*clientInstanceUpdateReal)(void *, bool) = nullptr;

uintptr_t clientInstanceUpdateHook(void *self, bool paused) {
    if (clientInstance == nullptr) {
        clientInstance = self;
        GameSdk::setClientInstance(reinterpret_cast<uintptr_t>(self));
        LOGI("ClientInstance captured at %p (vtable %p, guiData vtable %p)", self,
             reinterpret_cast<void *>(clientInstanceVtable),
             reinterpret_cast<void *>(guiDataVtable));
    }
    updateCalls++;
    GameSdk::pumpMessages();  // chat requests parked by Java land here, on the game thread
    mcpe::pumpCommands();     // outbound CommandRequestPackets, same thread, same reason
    // One-shot SDK self-test: fires the first time we are past boot AND the
    // game itself has witnessed world entry (joinMultiplayerGame or a real
    // chat line), so every ladder is queried in the state a mod would see.
    // Never probe unresolved sig getters just to decide this - calling the
    // mis-matched clientInstanceGetLocalPlayer pattern on the title screen
    // crashed the game.
    static bool s_selfTestDone = false;
    static bool s_selfTestArmed = false;
    // The self-test is split: its cold RTTI walks and signature scans run on a
    // worker thread (inline they stalled this loop for ~5 s), and only the
    // object-touching half - the part that must be on the game thread because
    // it calls Bedrock - runs back here once that pass reports done.
    if ((!s_selfTestDone || g_selfTestRequested.load()) && g_worldEntered) {
        if (!s_selfTestArmed) {
            s_selfTestArmed = true;
            mcpe::selfTestWarmup();
        } else if (mcpe::selfTestWarm()) {
            s_selfTestDone = true;
            const bool manual = g_selfTestRequested.exchange(false);
            mcpe::selfTest();
            if (manual) GameSdk::requestMessage("[SDK] Self-test finished; see AlterSelfTest log for results.");
        }
    }
    return clientInstanceUpdateReal(self, paused);
}

}  // namespace GameHooks
