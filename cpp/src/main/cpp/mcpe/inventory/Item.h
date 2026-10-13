#ifndef ALTER_LAUNCHER_MCPE_ITEM_H
#define ALTER_LAUNCHER_MCPE_ITEM_H

#include <string>
#include <mcpe/core/ClassCommon.h>

namespace mcpe {

class Item {
public:
    explicit Item(uintptr_t self = 0) : self_(self) {}
    uintptr_t self() const { return self_; }
    bool valid() const { return self_ != 0; }

    // Get item name (e.g. "minecraft:diamond_sword").
    std::string getName() const;

    // Get namespace (e.g. "minecraft").
    std::string getNamespace() const;

    // Get max stack size.
    bool getMaxStackSize(int *out) const;

    // Get max durability.
    bool getMaxDurability(int *out) const;

    // Is this item food?
    bool isFood() const;

    // Is this item a weapon?
    bool isWeapon() const;

    // Get armor slot (-1 if not armor, 0-3 for armor pieces).
    bool getArmorSlot(int *out) const;

private:
    uintptr_t self_;
};

}  // namespace mcpe

#endif  // ALTER_LAUNCHER_MCPE_ITEM_H
