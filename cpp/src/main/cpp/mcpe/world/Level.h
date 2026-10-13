#ifndef ALTER_LAUNCHER_MCPE_LEVEL_H
#define ALTER_LAUNCHER_MCPE_LEVEL_H

// Level - the world/session object holding dimensions, entities, and blocks.
// Accessed through Minecraft or ClientInstance.
#include <mcpe/core/ClassCommon.h>

namespace mcpe {

class Level {
public:
    explicit Level(uintptr_t self = 0) : self_(self) {}
    uintptr_t self() const { return self_; }
    bool valid() const { return self_ != 0; }

    // Get the BlockSource (region) for block access.
    Handle getRegion() const;

    // Get current dimension ID.
    bool getDimensionId(int *out) const;

    // Get the spawn position.
    bool getSpawnPosition(Vec3 *out) const;

    // Get the level's time of day (0-24000 ticks).
    bool getTime(int64_t *out) const;

private:
    uintptr_t self_;
};

}  // namespace mcpe

#endif  // ALTER_LAUNCHER_MCPE_LEVEL_H
