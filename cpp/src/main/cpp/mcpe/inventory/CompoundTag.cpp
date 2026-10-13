#include <mcpe/inventory/CompoundTag.h>
#include <cstring>
#include <mcpe/core/GameSDK.h>
#include <memory/HookRules.h>

namespace mcpe {
using detail::safeReadPtr;
using detail::safeReadI32;
using detail::safeReadF32;
using detail::safeReadU8;
using detail::dataPin;
using detail::inWritable;
using detail::inExec;
using detail::inGuestLib;

// CompoundTag methods use virtual calls through the tag's vtable.
// Each method needs a vtable.<key> entry in the rule file.

std::string CompoundTag::getString(const std::string &key) const {
    if (self_ == 0) return "";
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot = 0;
    if (!rule || !rule->lookupVtable(std::string("compoundTagGetString"), nullptr, &slot)) return "";
    uintptr_t vt = 0;
    if (!safeReadPtr(self_, 0, &vt) || !inGuestLib(vt)) return "";
    uintptr_t fn = 0;
    memcpy(&fn, reinterpret_cast<const void *>(vt + slot * sizeof(uintptr_t)), sizeof(fn));
    if (fn == 0 || !inExec(fn)) return "";
    // Returns a std::string by value — too complex for safe calling here.
    // Mods should use resolveSignature + callPointerGetter for this.
    return "";
}

bool CompoundTag::getInt(const std::string &key, int *out) const {
    if (self_ == 0 || out == nullptr) return false;
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot = 0;
    if (!rule || !rule->lookupVtable(std::string("compoundTagGetInt"), nullptr, &slot)) return false;
    uintptr_t vt = 0;
    if (!safeReadPtr(self_, 0, &vt) || !inGuestLib(vt)) return false;
    uintptr_t fn = 0;
    memcpy(&fn, reinterpret_cast<const void *>(vt + slot * sizeof(uintptr_t)), sizeof(fn));
    if (fn == 0 || !inExec(fn)) return false;
    using Fn = int (*)(void *, const std::string *);
    *out = reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_), &key);
    return true;
}

bool CompoundTag::getFloat(const std::string &key, float *out) const {
    if (self_ == 0 || out == nullptr) return false;
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot = 0;
    if (!rule || !rule->lookupVtable(std::string("compoundTagGetFloat"), nullptr, &slot)) return false;
    uintptr_t vt = 0;
    if (!safeReadPtr(self_, 0, &vt) || !inGuestLib(vt)) return false;
    uintptr_t fn = 0;
    memcpy(&fn, reinterpret_cast<const void *>(vt + slot * sizeof(uintptr_t)), sizeof(fn));
    if (fn == 0 || !inExec(fn)) return false;
    using Fn = float (*)(void *, const std::string *);
    *out = reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_), &key);
    return true;
}

bool CompoundTag::getByte(const std::string &key, int8_t *out) const {
    if (self_ == 0 || out == nullptr) return false;
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot = 0;
    if (!rule || !rule->lookupVtable(std::string("compoundTagGetByte"), nullptr, &slot)) return false;
    uintptr_t vt = 0;
    if (!safeReadPtr(self_, 0, &vt) || !inGuestLib(vt)) return false;
    uintptr_t fn = 0;
    memcpy(&fn, reinterpret_cast<const void *>(vt + slot * sizeof(uintptr_t)), sizeof(fn));
    if (fn == 0 || !inExec(fn)) return false;
    using Fn = int8_t (*)(void *, const std::string *);
    *out = reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_), &key);
    return true;
}

bool CompoundTag::getShort(const std::string &key, int16_t *out) const {
    if (self_ == 0 || out == nullptr) return false;
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot = 0;
    if (!rule || !rule->lookupVtable(std::string("compoundTagGetShort"), nullptr, &slot)) return false;
    uintptr_t vt = 0;
    if (!safeReadPtr(self_, 0, &vt) || !inGuestLib(vt)) return false;
    uintptr_t fn = 0;
    memcpy(&fn, reinterpret_cast<const void *>(vt + slot * sizeof(uintptr_t)), sizeof(fn));
    if (fn == 0 || !inExec(fn)) return false;
    using Fn = int16_t (*)(void *, const std::string *);
    *out = reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_), &key);
    return true;
}

bool CompoundTag::getInt64(const std::string &key, int64_t *out) const {
    if (self_ == 0 || out == nullptr) return false;
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot = 0;
    if (!rule || !rule->lookupVtable(std::string("compoundTagGetInt64"), nullptr, &slot)) return false;
    uintptr_t vt = 0;
    if (!safeReadPtr(self_, 0, &vt) || !inGuestLib(vt)) return false;
    uintptr_t fn = 0;
    memcpy(&fn, reinterpret_cast<const void *>(vt + slot * sizeof(uintptr_t)), sizeof(fn));
    if (fn == 0 || !inExec(fn)) return false;
    using Fn = int64_t (*)(void *, const std::string *);
    *out = reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_), &key);
    return true;
}

bool CompoundTag::getBoolean(const std::string &key, bool *out) const {
    int8_t val = 0;
    if (!getByte(key, &val)) return false;
    *out = (val != 0);
    return true;
}

bool CompoundTag::contains(const std::string &key) const {
    if (self_ == 0) return false;
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot = 0;
    if (!rule || !rule->lookupVtable(std::string("compoundTagContains"), nullptr, &slot)) return false;
    uintptr_t vt = 0;
    if (!safeReadPtr(self_, 0, &vt) || !inGuestLib(vt)) return false;
    uintptr_t fn = 0;
    memcpy(&fn, reinterpret_cast<const void *>(vt + slot * sizeof(uintptr_t)), sizeof(fn));
    if (fn == 0 || !inExec(fn)) return false;
    using Fn = bool (*)(void *, const std::string *);
    return reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_), &key);
}

Handle CompoundTag::getList(const std::string &key) const {
    if (self_ == 0) return Handle{};
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot = 0;
    if (!rule || !rule->lookupVtable(std::string("compoundTagGetList"), nullptr, &slot)) return Handle{};
    uintptr_t vt = 0;
    if (!safeReadPtr(self_, 0, &vt) || !inGuestLib(vt)) return Handle{};
    uintptr_t fn = 0;
    memcpy(&fn, reinterpret_cast<const void *>(vt + slot * sizeof(uintptr_t)), sizeof(fn));
    if (fn == 0 || !inExec(fn)) return Handle{};
    using Fn = uintptr_t (*)(void *, const std::string *);
    uintptr_t result = reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_), &key);
    if (result == 0 || !inWritable(result)) return Handle{};
    return Handle{result, "vcall"};
}

bool ListTag::size(int *out) const {
    if (self_ == 0 || out == nullptr) return false;
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot = 0;
    if (!rule || !rule->lookupVtable(std::string("listTagGetSize"), nullptr, &slot)) return false;
    uintptr_t vt = 0;
    if (!safeReadPtr(self_, 0, &vt) || !inGuestLib(vt)) return false;
    uintptr_t fn = 0;
    memcpy(&fn, reinterpret_cast<const void *>(vt + slot * sizeof(uintptr_t)), sizeof(fn));
    if (fn == 0 || !inExec(fn)) return false;
    using Fn = int (*)(void *);
    *out = reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_));
    return true;
}

Handle ListTag::getCompound(int index) const {
    if (self_ == 0) return Handle{};
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot = 0;
    if (!rule || !rule->lookupVtable(std::string("listTagGetCompound"), nullptr, &slot)) return Handle{};
    uintptr_t vt = 0;
    if (!safeReadPtr(self_, 0, &vt) || !inGuestLib(vt)) return Handle{};
    uintptr_t fn = 0;
    memcpy(&fn, reinterpret_cast<const void *>(vt + slot * sizeof(uintptr_t)), sizeof(fn));
    if (fn == 0 || !inExec(fn)) return Handle{};
    using Fn = uintptr_t (*)(void *, int);
    uintptr_t result = reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_), index);
    if (result == 0 || !inWritable(result)) return Handle{};
    return Handle{result, "vcall"};
}

}  // namespace mcpe