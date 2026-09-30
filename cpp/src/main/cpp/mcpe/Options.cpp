#include <mcpe/Options.h>
#include <cstring>
#include <mcpe/GameSDK.h>
#include <util/HookRules.h>

namespace mcpe {
using detail::safeReadPtr;
using detail::inExec;
using detail::inGuestLib;

bool Options::setPlayerViewPerspective(int value) const {
    if (self_ == 0) return false;
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot = 0;
    if (!rule || !rule->lookupVtable(std::string("optionsSetPerspective"), nullptr, &slot)) return false;
    uintptr_t vt = 0;
    if (!safeReadPtr(self_, 0, &vt) || !inGuestLib(vt)) return false;
    uintptr_t fn = 0;
    memcpy(&fn, reinterpret_cast<const void *>(vt + detail::kVtableHeaderSize + slot * 8), sizeof(fn));
    if (fn == 0 || !inExec(fn)) return false;
    using Fn = void (*)(void *, int);
    reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_), value);
    return true;
}

bool Options::getPlayerViewPerspective(int *out) const {
    if (self_ == 0 || out == nullptr) return false;
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot = 0;
    if (!rule || !rule->lookupVtable(std::string("optionsGetPerspective"), nullptr, &slot)) return false;
    uintptr_t vt = 0;
    if (!safeReadPtr(self_, 0, &vt) || !inGuestLib(vt)) return false;
    uintptr_t fn = 0;
    memcpy(&fn, reinterpret_cast<const void *>(vt + detail::kVtableHeaderSize + slot * 8), sizeof(fn));
    if (fn == 0 || !inExec(fn)) return false;
    using Fn = int (*)(void *);
    *out = reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_));
    return true;
}

}  // namespace mcpe