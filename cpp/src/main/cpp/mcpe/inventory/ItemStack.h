#ifndef ALTER_LAUNCHER_MCPE_ITEMSTACK_H
#define ALTER_LAUNCHER_MCPE_ITEMSTACK_H

#include <string>
#include <mcpe/core/ClassCommon.h>

namespace mcpe {

class Item;
class CompoundTag;

class ItemStack {
public:
    explicit ItemStack(uintptr_t self = 0) : self_(self) {}
    uintptr_t self() const { return self_; }
    bool valid() const { return self_ != 0; }

    // Get the Item definition pointer.
    // Reference: InternalItemDescriptor::getItem() at +0x8.
    Handle getItem() const;

    // Get item count (stack size).
    bool getCount(int *out) const;

    // Set item count.
    bool setCount(int count) const;

    // Get damage (durability used).
    bool getDamage(int *out) const;

    // Set damage.
    bool setDamage(int damage) const;

    // Get item name string (e.g. "minecraft:diamond_sword").
    std::string getName() const;

    // Check if the stack is empty (air/null).
    bool isEmpty() const;

    // Get the item's NBT tag (CompoundTag).
    Handle getTag() const;

private:
    uintptr_t self_;
};

}  // namespace mcpe

#endif  // ALTER_LAUNCHER_MCPE_ITEMSTACK_H
