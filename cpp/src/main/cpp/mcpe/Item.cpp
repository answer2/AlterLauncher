#include <mcpe/Item.h>
#include <cstring>
#include <mcpe/GameSDK.h>
#include <util/HookRules.h>

namespace mcpe {
using detail::safeReadF32;
using detail::safeReadI32;
using detail::safeReadU8;
using detail::dataPin;
using detail::inWritable;

std::string Item::getName() const {
    if (self_ == 0) return "";
    uintptr_t off = 0;
    if (!dataPin("itemNameField", &off)) return "";
    if (!inWritable(self_ + off)) return "";
    // libc++ std::string SSO layout: flag byte at +0, inline or heap data
    uint8_t flag = 0;
    if (!safeReadU8(self_, off, &flag)) return "";
    uint32_t len = 0;
    if (!detail::safeReadU32(self_, off + 0x18, &len)) return "";
    if (len == 0 || len > 256) return "";
    const char *data = nullptr;
    if ((flag & 1) != 0) {
        uintptr_t ptr = 0;
        memcpy(&ptr, reinterpret_cast<const void *>(self_ + off), sizeof(ptr));
        ptr &= ~static_cast<uintptr_t>(1);
        data = reinterpret_cast<const char *>(ptr);
    } else {
        data = reinterpret_cast<const char *>(self_ + off + 1);
    }
    if (!inWritable(reinterpret_cast<uintptr_t>(data))) return "";
    return std::string(data, len);
}

std::string Item::getNamespace() const {
    if (self_ == 0) return "";
    uintptr_t off = 0;
    if (!dataPin("itemNamespaceField", &off)) return "";
    return "minecraft";
}

bool Item::getMaxStackSize(int *out) const {
    if (self_ == 0 || out == nullptr) return false;
    uintptr_t off = 0;
    if (!dataPin("itemMaxStackSizeField", &off)) return false;
    uint8_t val = 0;
    if (!safeReadU8(self_, off, &val)) return false;
    *out = val;
    return true;
}

bool Item::getMaxDurability(int *out) const {
    if (self_ == 0 || out == nullptr) return false;
    uintptr_t off = 0;
    if (!dataPin("itemMaxDurabilityField", &off)) return false;
    int32_t val = 0;
    if (!safeReadI32(self_, off, &val)) return false;
    *out = val;
    return true;
}

bool Item::isFood() const {
    if (self_ == 0) return false;
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot = 0;
    if (!rule || !rule->lookupVtable(std::string("itemIsFood"), nullptr, &slot)) return false;
    uintptr_t vt = 0;
    if (!detail::safeReadPtr(self_, 0, &vt) || !detail::inGuestLib(vt)) return false;
    uintptr_t fn = 0;
    memcpy(&fn, reinterpret_cast<const void *>(vt + detail::kVtableHeaderSize + slot * 8), sizeof(fn));
    if (fn == 0 || !detail::inExec(fn)) return false;
    using Fn = bool (*)(void *);
    return reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_));
}

bool Item::isWeapon() const {
    if (self_ == 0) return false;
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot = 0;
    if (!rule || !rule->lookupVtable(std::string("itemIsWeapon"), nullptr, &slot)) return false;
    uintptr_t vt = 0;
    if (!detail::safeReadPtr(self_, 0, &vt) || !detail::inGuestLib(vt)) return false;
    uintptr_t fn = 0;
    memcpy(&fn, reinterpret_cast<const void *>(vt + detail::kVtableHeaderSize + slot * 8), sizeof(fn));
    if (fn == 0 || !detail::inExec(fn)) return false;
    using Fn = bool (*)(void *);
    return reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_));
}

bool Item::getArmorSlot(int *out) const {
    if (self_ == 0 || out == nullptr) return false;
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot = 0;
    if (!rule || !rule->lookupVtable(std::string("itemGetArmorSlot"), nullptr, &slot)) return false;
    uintptr_t vt = 0;
    if (!detail::safeReadPtr(self_, 0, &vt) || !detail::inGuestLib(vt)) return false;
    uintptr_t fn = 0;
    memcpy(&fn, reinterpret_cast<const void *>(vt + detail::kVtableHeaderSize + slot * 8), sizeof(fn));
    if (fn == 0 || !detail::inExec(fn)) return false;
    using Fn = int (*)(void *);
    *out = reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_));
    return true;
}

}  // namespace mcpe