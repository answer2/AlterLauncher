#ifndef ALTER_LAUNCHER_MCPE_MINECRAFT_GAME_H
#define ALTER_LAUNCHER_MCPE_MINECRAFT_GAME_H

// MinecraftGame - the app-level object that owns screens, input state and the
// timer chain. Currently published via its hooks; the ladder mirrors
// GameSdk::minecraftGame(). Split from ClassTree per feature area alongside
// Actor/GuiData/etc. so a mod touching only input never compiles against
// render internals.
#include <mcpe/core/ClassCommon.h>

namespace mcpe {

class MinecraftGameHandle {
public:
    Handle h;

    // Resolve through the SDK ladder (hook capture -> field pin -> virtual
    // getter -> pattern getter); invalid handle when nothing has proven it yet.
    static MinecraftGameHandle fromSdk();

    bool valid() const { return h.valid(); }

    // canUseKeys(): reference pin +0x190 (3.4; 3.3.15 was 0x178). Read-only
    // byte read; false when unpinned and unscannable.
    bool canUseKeys() const;

    // isInGame(): reference calls MinecraftGame::isInGame() virtual.
    // Vtable key: minecraftGameIsInGame.
    bool isInGame() const;

    // isPrimaryLocalPlayerInGame(): vtable key minecraftGameIsPrimaryLocalPlayerInGame.
    bool isPrimaryLocalPlayerInGame() const;
};

// Minecraft - the game-session object hanging off MinecraftGame/the client.
class Minecraft {
public:
    explicit Minecraft(uintptr_t self) : self_(self) {}
    uintptr_t self() const { return self_; }
    bool valid() const { return self_ != 0; }

    // Timer* at +0xD0 per the reference (its Minecraft::getTimer). Pin
    // dat.minecraftTimerField=d0xD0; float getTime at Timer+0x18, speed +0xC.
    bool timerTime(float *time, float *speed) const;

private:
    uintptr_t self_;
};

}  // namespace mcpe

#endif  // ALTER_LAUNCHER_MCPE_MINECRAFT_GAME_H
