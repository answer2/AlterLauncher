#ifndef ALTER_LAUNCHER_MCPE_OPTIONS_H
#define ALTER_LAUNCHER_MCPE_OPTIONS_H

// Options - game settings (perspective, FOV, render distance, etc).
#include <mcpe/core/ClassCommon.h>

namespace mcpe {

class Options {
public:
    explicit Options(uintptr_t self = 0) : self_(self) {}
    uintptr_t self() const { return self_; }
    bool valid() const { return self_ != 0; }

    // Set player view perspective: 0=first, 1=third-back, 2=third-front.
    // Reference: virtual slot 138 (3.4), 139 (3.3.15).
    bool setPlayerViewPerspective(int value) const;

    // Get player view perspective.
    // Reference: virtual slot 139 (3.4), 140 (3.3.15).
    bool getPlayerViewPerspective(int *out) const;

private:
    uintptr_t self_;
};

}  // namespace mcpe

#endif  // ALTER_LAUNCHER_MCPE_OPTIONS_H
