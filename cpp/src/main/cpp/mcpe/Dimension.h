#ifndef ALTER_LAUNCHER_MCPE_DIMENSION_H
#define ALTER_LAUNCHER_MCPE_DIMENSION_H

// Dimension - overworld/nether/the_end access.
#include <mcpe/ClassCommon.h>

namespace mcpe {

class Dimension {
public:
    explicit Dimension(uintptr_t self = 0) : self_(self) {}
    uintptr_t self() const { return self_; }
    bool valid() const { return self_ != 0; }

    // Get dimension ID: 0=overworld, 1=nether, 2=the_end.
    // Reference pin: +0x140 (3.4), +0x138 (3.3.15).
    bool getDimensionId(int *out) const;

private:
    uintptr_t self_;
};

}  // namespace mcpe

#endif  // ALTER_LAUNCHER_MCPE_DIMENSION_H
