#ifndef ALTER_LAUNCHER_MCPE_MOD_API_H
#define ALTER_LAUNCHER_MCPE_MOD_API_H

// The stable, mod-facing surface of the launcher's game SDK.
//
// This header is what a native mod (a .so dropped next to the game) links
// against. It deliberately exposes NOTHING raw: no vtables, no RVAs,
// no casting helpers into guest memory. Every acquisition goes through the
// fail-closed ladders in GameSDK.h / ClassTree.h, so a mod built today keeps
// working on tomorrow's recompile or degrades to invalid handles - never to
// a wrong object or a crash.
#include <stddef.h>
#include <stdint.h>

#include <string>

#include <mcpe/Types.h>
#include <mcpe/ClassCommon.h>

namespace mcpe {

// Mod-facing handle: same shape as mcpe::Handle but kept as a
// separate type so the ABI surface is explicit.
struct ObjectHandle {
    uintptr_t ptr = 0;
    const char *source = "-";
    bool valid() const { return ptr != 0; }
};

inline bool handleValid(const ObjectHandle &h) { return h.ptr != 0; }


// Identity of the running build, for logs and for a mod's own version gate.
struct BuildInfo {
    const char *version;      // dotted Minecraft version the rule matched, empty if none
    const char *ruleName;     // active game-hooks.ini rule block, empty if defaults
    uintptr_t libraryBase;    // runtime base of libminecraftpe.so in THIS process
    bool ruleActive;          // false = compiled-in defaults are in charge
};

// --------------------------------------------------------------------------
// Client + session state
// --------------------------------------------------------------------------

uintptr_t client();
bool inGame();
void describe(char *buffer, size_t capacity);
BuildInfo buildInfo();

// --------------------------------------------------------------------------
// Subsystems - each resolves through its ladder; invalid handle when nothing
// has proven the object yet.
// --------------------------------------------------------------------------

ObjectHandle guiData();
ObjectHandle minecraftGame();
ObjectHandle localPlayer();
ObjectHandle packetSender();
ObjectHandle levelRenderer();
ObjectHandle level();
ObjectHandle camera();
ObjectHandle options();
ObjectHandle region();
ObjectHandle screenModel();

// --------------------------------------------------------------------------
// GuiData window metrics
// --------------------------------------------------------------------------

bool windowSize(Vec2 *gameSize, Vec2 *realSize);

// --------------------------------------------------------------------------
// Projection: world -> screen
// --------------------------------------------------------------------------

bool worldToScreen(const Vec3 &cameraPos, const Vec3 &worldPos, Vec2 *outScreen);
bool worldToScreenAtPlayer(const Vec3 &worldPos, Vec2 *outScreen);

// --------------------------------------------------------------------------
// Chat
// --------------------------------------------------------------------------

bool sendChatMessage(const std::string &text);
bool sendChatMessageRaw(const char *utf8);
size_t pumpCount();

// --------------------------------------------------------------------------
// Actor/Player convenience (all fail-closed when unpinned)
// --------------------------------------------------------------------------

bool getPlayerPos(Vec3 *out);
bool getPlayerRotation(Vec2 *out);
bool getPlayerHealth(float *out);
bool getPlayerDimension(int *out);

// --------------------------------------------------------------------------
// MinecraftGame convenience
// --------------------------------------------------------------------------

bool minecraftGameIsInGame();
bool minecraftGameCanUseKeys();

// --------------------------------------------------------------------------
// Function finding (the BedrockTools-style toolbox, rule-file powered)
// --------------------------------------------------------------------------

uintptr_t resolveSignature(const std::string &key);
uintptr_t resolveOffset(const std::string &key);
uintptr_t callPointerGetter(uintptr_t object, uintptr_t getterAddress);

// One-shot SDK self-test: exercises every acquisition ladder and
// mod-facing entry point from the game thread, logging OK/FAIL per function.
// Triggered once by the ClientInstance::update hook when a local player is
// live (i.e. really in a world), so the numbers mean something.
void selfTest();

}  // namespace mcpe

#endif  // ALTER_LAUNCHER_MCPE_MOD_API_H