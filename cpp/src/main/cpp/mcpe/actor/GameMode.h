#ifndef ALTER_LAUNCHER_MCPE_GAMEMODE_H
#define ALTER_LAUNCHER_MCPE_GAMEMODE_H

// GameMode - player's interaction mode (survival/creative/etc actions).
#include <mcpe/core/ClassCommon.h>

namespace mcpe {

class Actor;

class GameMode {
public:
    explicit GameMode(uintptr_t self = 0) : self_(self) {}
    uintptr_t self() const { return self_; }
    bool valid() const { return self_ != 0; }

    // Attack an actor.
    bool attack(uintptr_t targetActor) const;

    // Place a block at position on the given side.
    bool buildBlock(const Vec3i &pos, uint8_t side) const;

    // Destroy a block at position.
    bool destroyBlock(const Vec3i &pos, uint8_t side) const;

    // Start destroying a block (break animation).
    bool startDestroyBlock(const Vec3i &pos, uint8_t side) const;

    // Set attack cooldown timer.
    bool setAttackTime(int time) const;

    // Get pick range (how far the player can reach).
    bool getPickRange(float *out) const;

private:
    uintptr_t self_;
};

}  // namespace mcpe

#endif  // ALTER_LAUNCHER_MCPE_GAMEMODE_H
