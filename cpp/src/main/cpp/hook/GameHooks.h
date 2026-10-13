#ifndef ALTER_NATIVE_HOOK_GAME_HOOKS_H
#define ALTER_NATIVE_HOOK_GAME_HOOKS_H

#include <atomic>
#include <cstdint>
#include <optional>
#include <string>

// The game-side hook handlers and everything they publish.
//
// Split out of main.cpp so the JNI surface, the bootstrap and the patching
// machinery are separate concerns: this file is only "what runs when the game
// calls a function we own", plus the state those handlers observe. The
// installer (hook/HookInstaller.h) writes the `*_real` bridges and reads the
// vtable/instance fields; Java reads the menu flags through menuState().
//
// Every handler runs on the GAME thread. The flags Java polls are atomics
// because menuState() reads them from the UI thread.
namespace GameHooks {

// Captured by ClientInstance::update (RTTI path) or Minecraft::update
// (signature path). Non-null means a game instance has been seen at least once.
extern void *clientInstance;
extern uintptr_t clientInstanceVtable;
extern uintptr_t guiDataVtable;
extern long updateCalls;

// The MinecraftGame the update hook was handed, or nullptr. Only ever a
// liveness witness — nothing dereferences it.
void *minecraftGame();

// Menu state, mirroring preloader-android's GameHooks.cpp flags (Apache-2.0,
// see LICENSES/preloader-android.txt + NOTICE): PauseMenu and HudScreen
// ctor/dtor plus ClientInstance::isShowingMenu together answer "is a menu on
// screen right now?", which is what the Levi mod surface (mod menu, input
// routing) keys off.
extern std::atomic<bool> isPauseMenuOpen;
extern std::atomic<bool> isHudScreenOpen;
extern std::atomic<bool> isShowingMenu;
extern long pauseMenuEvents;
extern long hudScreenEvents;
extern long showingMenuCalls;

// World-entry witness: joinMultiplayerGame (the game's own "start loading a
// world" call, single-player hosts included), a real chat line, or the HUD
// being constructed. The SDK self-test only runs after this, never on the
// title screen.
bool worldEntered();

// ---- handlers ------------------------------------------------------------
// Signatures are the game's own; the installer casts them to void* for the
// hook table, so they must stay exact.

// MinecraftGame::update — the signature-path fallback for capturing the game
// instance when RTTI did not resolve ClientInstance::update.
void *minecraftUpdateHook(void *minecraftGame);
extern void *(*minecraftUpdateReal)(void *);

// ARM64 chat wrapper (45.1: 0x923ab54; 52.3: 0x96108a4): x3 references
// optional<string>, whose engagement flag is at +24. No duration argument.
#if defined(__aarch64__)
static_assert(sizeof(std::string) == 24, "Chat ABI requires a 24-byte string");
static_assert(sizeof(std::optional<std::string>) == 32, "Chat ABI requires a 32-byte optional");
#endif
void guiDataAddMessageHook(void *guiData, const std::string &sender, const std::string &message,
                           const std::optional<std::string> &metadata,
                           const std::string &extra1, const std::string &extra2);
extern void (*guiDataAddMessageReal)(void *, const std::string &, const std::string &,
                                     const std::optional<std::string> &,
                                     const std::string &, const std::string &);

void *guiDataSendClientMessageHook(void *self, const std::string &msg, const std::string &msg2);
extern void *(*guiDataSendClientMessageReal)(void *, const std::string &, const std::string &);

void *joinMultiplayerGameHook(void *minecraftGame, const std::string &a1, void *a2);
extern void *(*joinMultiplayerGameReal)(void *, const std::string &, void *);

void pauseMenuDtorHook(void *self);
extern void (*pauseMenuDtorReal)(void *);
void pauseMenuOpenHook(void *self);
extern void (*pauseMenuOpenReal)(void *);
void hudScreenDtorHook(void *self);
extern void (*hudScreenDtorReal)(void *);
// The HUD is constructed when a world finishes loading and destroyed when the
// client leaves it, so its lifetime is the in-game witness GameSdk::isInGame()
// falls back to while no vtable.isInGame pin exists.
void hudScreenOpenHook(void *self);
extern void (*hudScreenOpenReal)(void *);

// ClientInstance virtuals, patched at their RTTI vtable slot (an inline hook
// has no island inside the guest's single 298 MB exec mapping).
uintptr_t clientInstanceUpdateHook(void *self, bool paused);
extern uintptr_t (*clientInstanceUpdateReal)(void *, bool);
bool clientInstanceIsShowingMenuHook(void *self);
extern bool (*clientInstanceIsShowingMenuReal)(void *);

}  // namespace GameHooks

#endif  // ALTER_NATIVE_HOOK_GAME_HOOKS_H
