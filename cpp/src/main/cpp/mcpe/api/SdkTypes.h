#ifndef ALTER_LAUNCHER_MCPE_SDK_TYPES_H
#define ALTER_LAUNCHER_MCPE_SDK_TYPES_H

// What a native mod actually links against, and what it gets back.
//
// Two problems this header exists to solve:
//
// 1. alternative_core is built with -fvisibility=hidden (CMakeLists.txt:134),
//    so NONE of the mcpe:: symbols are exported and a .so dropped next to the
//    game cannot call the SDK at all — it can only be reached through JNI.
//    MCPE_API puts the marked entry points back in the dynamic symbol table.
//    Nothing else is exported; the ladder internals stay private.
//
// 2. The mod-facing surface used to hand back loose out-parameters
//    (getPlayerPos(Vec3*), getPlayerHealth(float*), ...). That tells a
//    developer nothing about what a player IS: there is no place to look up
//    "what can I read", so the only way to find out is to read the launcher's
//    source. The PODs below are the answer — one aggregate per thing the SDK
//    can observe, each field named, each with a `valid` bit per group so a
//    partially-resolved build reports partial data instead of zeros that look
//    like real coordinates.
//
// Everything here is plain data: no methods that touch the guest, no vtables,
// no pointers into game memory. A mod can memcpy it, log it, ship it across a
// socket. That is what keeps the ABI boring across launcher versions.
#include <stddef.h>
#include <stdint.h>

#define MCPE_API __attribute__((visibility("default")))

// Bumped whenever the shape below changes or an entry point is removed. A mod
// asks for the version it was built against; launcher answers 0 when it cannot
// serve it. Additive changes bump the minor only.
#define MCPE_SDK_VERSION 1

namespace mcpe {

// --------------------------------------------------------------------------
// Observed state, one aggregate per subject
// --------------------------------------------------------------------------

// Position / orientation / vitals of the local player. Each group resolves
// through its own ladder, so a build that pins rotation but not health returns
// hasPos=0 / hasHealth=0 rather than 0.0f that reads like a real value.
struct PlayerState {
    uint32_t hasPos : 1;
    uint32_t hasRot : 1;
    uint32_t hasHealth : 1;
    uint32_t hasDimension : 1;
    uint32_t hasName : 1;

    float posX, posY, posZ;      // hasPos
    float yaw, pitch;            // hasRot, degrees
    float health, maxHealth;     // hasHealth
    int32_t dimensionId;         // hasDimension
    char name[64];               // hasName, UTF-8, NUL-terminated

    PlayerState()
        : hasPos(0), hasRot(0), hasHealth(0), hasDimension(0), hasName(0),
          posX(0.f), posY(0.f), posZ(0.f), yaw(0.f), pitch(0.f),
          health(0.f), maxHealth(0.f), dimensionId(0) {
        name[0] = '\0';
    }
};

// GuiData's window metrics. `game*` is the logical UI size the game lays out
// in, `real*` is the physical surface — the two differ whenever the game
// applies a UI scale, and worldToScreen needs the right one.
struct WindowMetrics {
    uint32_t hasSize : 1;
    float gameWidth, gameHeight;
    float realWidth, realHeight;

    WindowMetrics()
        : hasSize(0), gameWidth(0.f), gameHeight(0.f),
          realWidth(0.f), realHeight(0.f) {}
};

// Which handles the SDK has actually proven on this build. A mod should gate on
// this rather than on `client() != 0`: the ClientInstance is captured by a hook
// and says nothing about whether anything derived from it resolved.
struct SdkReadiness {
    uint32_t clientInstance : 1;
    uint32_t guiData : 1;
    uint32_t minecraftGame : 1;
    uint32_t localPlayer : 1;
    uint32_t levelRenderer : 1;
    uint32_t level : 1;
    uint32_t camera : 1;
    uint32_t options : 1;
    uint32_t region : 1;
    uint32_t screenModel : 1;
    uint32_t packetSender : 1;
    uint32_t inGame : 1;

    SdkReadiness()
        : clientInstance(0), guiData(0), minecraftGame(0), localPlayer(0),
          levelRenderer(0), level(0), camera(0), options(0), region(0),
          screenModel(0), packetSender(0), inGame(0) {}
};

// One entry in the inventory: the name a mod passes, the C signature it has to
// match, and what it is for. See sdkInventory().
struct SdkEntry {
    const char *name;
    const char *signature;  // C declaration, e.g. "bool (const char *key)"
    const char *what;       // one line, for a --help dump
};

}  // namespace mcpe

#endif  // ALTER_LAUNCHER_MCPE_SDK_TYPES_H
