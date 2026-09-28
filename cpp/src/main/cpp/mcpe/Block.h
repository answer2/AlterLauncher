#ifndef ALTER_LAUNCHER_MCPE_BLOCK_H
#define ALTER_LAUNCHER_MCPE_BLOCK_H

#include <string>
#include <mcpe/ClassCommon.h>

namespace mcpe {

class BlockLegacy;

class Block {
public:
    explicit Block(uintptr_t self = 0) : self_(self) {}
    uintptr_t self() const { return self_; }
    bool valid() const { return self_ != 0; }

    // Get the block name (e.g. "minecraft:stone").
    std::string getName() const;

    // Get block variant.
    bool getVariant(int *out) const;

    // Get BlockLegacy (the block definition).
    // Reference: BlockLegacy* at +0x38.
    Handle toLegacy() const;

private:
    uintptr_t self_;
};

class BlockLegacy {
public:
    explicit BlockLegacy(uintptr_t self = 0) : self_(self) {}
    uintptr_t self() const { return self_; }
    bool valid() const { return self_ != 0; }

    std::string getName() const;

private:
    uintptr_t self_;
};

}  // namespace mcpe

#endif  // ALTER_LAUNCHER_MCPE_BLOCK_H
