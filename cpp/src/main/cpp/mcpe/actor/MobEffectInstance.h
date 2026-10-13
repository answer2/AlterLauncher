#ifndef ALTER_LAUNCHER_MCPE_MOB_EFFECT_H
#define ALTER_LAUNCHER_MCPE_MOB_EFFECT_H

// MobEffectInstance - a status effect on an actor.
#include <mcpe/core/ClassCommon.h>

namespace mcpe {

class MobEffectInstance {
public:
    explicit MobEffectInstance(uintptr_t self = 0) : self_(self) {}
    uintptr_t self() const { return self_; }
    bool valid() const { return self_ != 0; }

    // Get effect ID (1=speed, 2=slowness, etc). -1 when unpinned.
    int getId() const;

    // Get amplifier (level - 1). -1 when unpinned.
    int getAmplifier() const;

    // Get duration in ticks. -1 when unpinned.
    int getDuration() const;

    // Is ambient (beacon) effect?
    bool isAmbient() const;

    // Show particles?
    bool showParticles() const;

private:
    uintptr_t self_;
};

}  // namespace mcpe

#endif  // ALTER_LAUNCHER_MCPE_MOB_EFFECT_H
