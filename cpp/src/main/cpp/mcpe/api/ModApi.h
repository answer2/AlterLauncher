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

#include <mcpe/core/Types.h>
#include <mcpe/core/ClassCommon.h>
#include <mcpe/api/SdkTypes.h>

// Every declaration below carries MCPE_API because the library is built with
// -fvisibility=hidden: without it none of this is in the dynamic symbol table
// and a native mod cannot call the SDK directly. See SdkTypes.h.
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

MCPE_API uintptr_t client();
MCPE_API bool inGame();
MCPE_API void describe(char *buffer, size_t capacity);
MCPE_API BuildInfo buildInfo();

// --------------------------------------------------------------------------
// Subsystems - each resolves through its ladder; invalid handle when nothing
// has proven the object yet.
// --------------------------------------------------------------------------

MCPE_API ObjectHandle guiData();
MCPE_API ObjectHandle minecraftGame();
MCPE_API ObjectHandle localPlayer();
MCPE_API ObjectHandle packetSender();
MCPE_API ObjectHandle levelRenderer();
MCPE_API ObjectHandle level();
MCPE_API ObjectHandle camera();
MCPE_API ObjectHandle options();
MCPE_API ObjectHandle region();
MCPE_API ObjectHandle screenModel();

// --------------------------------------------------------------------------
// GuiData window metrics
// --------------------------------------------------------------------------

MCPE_API bool windowSize(Vec2 *gameSize, Vec2 *realSize);

// --------------------------------------------------------------------------
// Projection: world -> screen
// --------------------------------------------------------------------------

MCPE_API bool worldToScreen(const Vec3 &cameraPos, const Vec3 &worldPos, Vec2 *outScreen);
MCPE_API bool worldToScreenAtPlayer(const Vec3 &worldPos, Vec2 *outScreen);

// --------------------------------------------------------------------------
// Chat
// --------------------------------------------------------------------------

MCPE_API bool sendChatMessage(const std::string &text);
MCPE_API bool sendChatMessageRaw(const char *utf8);
MCPE_API size_t pumpCount();

// --------------------------------------------------------------------------
// Actor/Player convenience (all fail-closed when unpinned)
// --------------------------------------------------------------------------

MCPE_API bool getPlayerPos(Vec3 *out);
MCPE_API bool getPlayerRotation(Vec2 *out);
MCPE_API bool getPlayerHealth(float *out);
MCPE_API bool getPlayerDimension(int *out);

// The same observations as one aggregate, with a `has*` bit per group. Prefer
// this over the four loose getters: a build that resolves rotation but not
// health reports hasHealth=0 instead of 0.0f, which a caller cannot tell apart
// from "the player is at zero health".
MCPE_API bool playerState(PlayerState *out);

// GuiData window metrics as one aggregate (logical vs physical).
MCPE_API bool windowMetrics(WindowMetrics *out);

// Which handles have actually been proven on this build. Gate on this, not on
// `client() != 0`.
MCPE_API bool readiness(SdkReadiness *out);

// --------------------------------------------------------------------------
// MinecraftGame convenience
// --------------------------------------------------------------------------

MCPE_API bool minecraftGameIsInGame();
MCPE_API bool minecraftGameCanUseKeys();

// --------------------------------------------------------------------------
// Hooks on functions the SDK has already located.
//
// The SDK never patches on its own; this is the opt-in route. `key` is a
// `vtable.<key>` rule entry, so the class AND the slot index come from the rule
// file and one call covers every build that has a matching block. Only virtual
// functions are reachable this way — there is no slot to patch for the others,
// and libminecraftpe.so's single ~298 MB executable mapping leaves shadowhook
// no island for a body patch in its middle band.
//
// `sdkOriginal` needs the object because a class has several vtables (primary,
// complete-object, base-subobject) and a subobject that overrides the slot has
// a different original from the primary's.
//
// All three are safe to call from any thread, but a hook should be installed
// before the game starts calling through the slot.
// --------------------------------------------------------------------------

MCPE_API bool sdkHook(const char *key, void *handler);
MCPE_API bool sdkUnhook(const char *key);
MCPE_API uintptr_t sdkOriginal(const char *key, uintptr_t object);

// Locate a function by rule key, trying every handle the rule file can carry:
//
//   1. vtable.<key> = Class:slot   -> dispatched through `object`'s own vptr,
//                                     so the class is checked by RTTI first
//   2. sig.<key>    = <pattern>    -> byte scan; the only route to a function
//                                     that is not virtual, and the reason this
//                                     ladder exists at all: a vtable pin cannot
//                                     reach Actor::jump or Options::getPerspective
//                                     (neither is a virtual in any build we have
//                                     symbols for), but a pattern can
//   3. off.<key>    = <rva>        -> base + rva, the least portable and the
//                                     most direct
//
// `object` is required for rung 1 and ignored by 2 and 3; pass 0 to skip it.
// Returns 0, never a guess. A key that is pinned in more than one form uses the
// form the rule actually defines, in the order above.
MCPE_API uintptr_t sdkResolve(const char *key, uintptr_t object);

// The whole mod-facing surface, as data. A mod that does not want to read the
// launcher's headers can walk this at runtime and print its own --help:
//   const mcpe::SdkEntry *e; size_t n; mcpe::sdkInventory(&e, &n);
// The pointers are static and never move; do not free them.
MCPE_API void sdkInventory(const SdkEntry **entries, size_t *count);

// MCPE_SDK_VERSION this library was built with.
MCPE_API uint32_t sdkVersion();

// --------------------------------------------------------------------------
// Function finding (the BedrockTools-style toolbox, rule-file powered)
// --------------------------------------------------------------------------

MCPE_API uintptr_t resolveSignature(const std::string &key);
MCPE_API uintptr_t resolveOffset(const std::string &key);
MCPE_API uintptr_t callPointerGetter(uintptr_t object, uintptr_t getterAddress);

// Starts the off-thread half of the self-test (the cold RTTI chain walks and the
// rule signature scans — pure reads of the guest's file-backed mappings, so they
// cannot disturb the game). Non-blocking and idempotent; call it from the game
// thread once world entry has been witnessed.
MCPE_API void selfTestWarmup();

// True once that pass has finished, i.e. selfTest() has nothing left that costs
// more than a microsecond per probe.
MCPE_API bool selfTestWarm();

// One-shot SDK self-test: exercises every acquisition ladder and
// mod-facing entry point from the game thread, logging OK/FAIL per function.
// Triggered once by the ClientInstance::update hook when a local player is
// live (i.e. really in a world), so the numbers mean something.
MCPE_API void selfTest();

}  // namespace mcpe

#endif  // ALTER_LAUNCHER_MCPE_MOD_API_H