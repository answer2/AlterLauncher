#include <mcpe/ItemStack.h>
#include <cstring>
#include <mcpe/GameSDK.h>
#include <mcpe/Item.h>

namespace mcpe {
using detail::safeReadPtr;
using detail::safeReadI32;
using detail::dataPin;
using detail::inWritable;

Handle ItemStack::getItem() const {
    if (self_ == 0) return Handle{};
    // Reference: InternalItemDescriptor::getItem() at +0x8.
    uintptr_t off = 0;
    if (!dataPin("itemStackItemField", &off)) {
        // Fallback: reference hardcodes +0x8 for the item pointer.
        off = 0x8;
    }
    uintptr_t val = 0;
    if (!safeReadPtr(self_, off, &val) || !inWritable(val)) return Handle{};
    return Handle{val, "pin"};
}

bool ItemStack::getCount(int *out) const {
    if (self_ == 0 || out == nullptr) return false;
    uintptr_t off = 0;
    if (!dataPin("itemStackCountField", &off)) return false;
    int32_t val = 0;
    if (!safeReadI32(self_, off, &val)) return false;
    *out = val;
    return true;
}

bool ItemStack::setCount(int count) const {
    if (self_ == 0 || !inWritable(self_)) return false;
    uintptr_t off = 0;
    if (!dataPin("itemStackCountField", &off)) return false;
    int32_t val = count;
    memcpy(reinterpret_cast<void *>(self_ + off), &val, sizeof(val));
    return true;
}

bool ItemStack::getDamage(int *out) const {
    if (self_ == 0 || out == nullptr) return false;
    uintptr_t off = 0;
    if (!dataPin("itemStackDamageField", &off)) return false;
    int32_t val = 0;
    if (!safeReadI32(self_, off, &val)) return false;
    *out = val;
    return true;
}

bool ItemStack::setDamage(int damage) const {
    if (self_ == 0 || !inWritable(self_)) return false;
    uintptr_t off = 0;
    if (!dataPin("itemStackDamageField", &off)) return false;
    int32_t val = damage;
    memcpy(reinterpret_cast<void *>(self_ + off), &val, sizeof(val));
    return true;
}

std::string ItemStack::getName() const {
    Handle h = getItem();
    if (!h.valid()) return "";
    return Item(h.ptr).getName();
}

bool ItemStack::isEmpty() const {
    if (self_ == 0) return true;
    int count = 0;
    if (!getCount(&count)) return true;
    return count <= 0;
}

Handle ItemStack::getTag() const {
    if (self_ == 0) return Handle{};
    uintptr_t off = 0;
    if (!dataPin("itemStackTagField", &off)) return Handle{};
    uintptr_t val = 0;
    if (!safeReadPtr(self_, off, &val) || !inWritable(val)) return Handle{};
    return Handle{val, "pin"};
}

}  // namespace mcpe
