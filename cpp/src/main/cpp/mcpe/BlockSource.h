#ifndef ALTER_LAUNCHER_MCPE_BLOCKSOURCE_H
#define ALTER_LAUNCHER_MCPE_BLOCKSOURCE_H

// BlockSource (Region) - block get/set access in the world.
#include <mcpe/ClassCommon.h>

namespace mcpe {

class BlockSource {
public:
    explicit BlockSource(uintptr_t self = 0) : self_(self) {}
    uintptr_t self() const { return self_; }
    bool valid() const { return self_ != 0; }

    // Get block at position. Returns a Block* handle or invalid.
    Handle getBlock(int x, int y, int z) const;

    // Get block at Vec3i position.
    Handle getBlock(const Vec3i &pos) const;

    // Check if chunks are loaded at position.
    bool hasChunksAt(const Vec3i &pos) const;

    // Get dimension ID from the block source.
    bool getDimensionId(int *out) const;

private:
    uintptr_t self_;
};

}  // namespace mcpe

#endif  // ALTER_LAUNCHER_MCPE_BLOCKSOURCE_H
