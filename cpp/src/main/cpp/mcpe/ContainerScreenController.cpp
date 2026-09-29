#include <mcpe/ContainerScreenController.h>
#include <cstring>
#include <mcpe/GameSDK.h>
#include <util/HookRules.h>

namespace mcpe {
using detail::safeReadPtr;
using detail::inWritable;
using detail::inExec;
using detail::inGuestLib;

Handle ContainerScreenController::getItemStack(const std::string &containerName, int slot) const {
    if (self_ == 0) return Handle{};
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot_key = 0;
    if (!rule || !rule->lookupVtable(std::string("containerScreenGetItemStack"), nullptr, &slot_key)) return Handle{};
    uintptr_t vt = 0;
    if (!safeReadPtr(self_, 0, &vt) || !inGuestLib(vt)) return Handle{};
    uintptr_t fn = 0;
    memcpy(&fn, reinterpret_cast<const void *>(vt + slot_key * 8), sizeof(fn));
    if (fn == 0 || !inExec(fn)) return Handle{};
    using Fn = uintptr_t (*)(void *, const std::string *, int);
    uintptr_t result = reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_), &containerName, slot);
    if (result == 0 || !inWritable(result)) return Handle{};
    return Handle{result, "vcall"};
}

bool ContainerScreenController::handleAutoPlace(int containerSize, const std::string &containerName, int slot) const {
    if (self_ == 0) return false;
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot_key = 0;
    if (!rule || !rule->lookupVtable(std::string("containerScreenAutoPlace"), nullptr, &slot_key)) return false;
    uintptr_t vt = 0;
    if (!safeReadPtr(self_, 0, &vt) || !inGuestLib(vt)) return false;
    uintptr_t fn = 0;
    memcpy(&fn, reinterpret_cast<const void *>(vt + slot_key * 8), sizeof(fn));
    if (fn == 0 || !inExec(fn)) return false;
    using Fn = bool (*)(void *, int, const std::string *, int);
    return reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_), containerSize, &containerName, slot);
}

bool ContainerScreenController::handleSwap(const std::string &fromName, int fromSlot,
                                            const std::string &toName, int toSlot) const {
    if (self_ == 0) return false;
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot_key = 0;
    if (!rule || !rule->lookupVtable(std::string("containerScreenSwap"), nullptr, &slot_key)) return false;
    uintptr_t vt = 0;
    if (!safeReadPtr(self_, 0, &vt) || !inGuestLib(vt)) return false;
    uintptr_t fn = 0;
    memcpy(&fn, reinterpret_cast<const void *>(vt + slot_key * 8), sizeof(fn));
    if (fn == 0 || !inExec(fn)) return false;
    using Fn = bool (*)(void *, const std::string *, int, const std::string *, int);
    return reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_), &fromName, fromSlot, &toName, toSlot);
}

bool ContainerScreenController::tryExit() const {
    if (self_ == 0) return false;
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot_key = 0;
    if (!rule || !rule->lookupVtable(std::string("containerScreenTryExit"), nullptr, &slot_key)) return false;
    uintptr_t vt = 0;
    if (!safeReadPtr(self_, 0, &vt) || !inGuestLib(vt)) return false;
    uintptr_t fn = 0;
    memcpy(&fn, reinterpret_cast<const void *>(vt + slot_key * 8), sizeof(fn));
    if (fn == 0 || !inExec(fn)) return false;
    using Fn = bool (*)(void *);
    return reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_));
}

}  // namespace mcpe