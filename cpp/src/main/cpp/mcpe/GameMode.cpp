#include <mcpe/GameMode.h>
#include <cstring>
#include <mcpe/GameSDK.h>
#include <util/HookRules.h>

namespace mcpe {
using detail::safeReadPtr;
using detail::safeReadF32;
using detail::dataPin;
using detail::inWritable;
using detail::inExec;
using detail::inGuestLib;

bool GameMode::attack(uintptr_t targetActor) const {
    if (self_ == 0 || targetActor == 0) return false;
    if (!inWritable(targetActor)) return false;
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot = 0;
    if (!rule || !rule->lookupVtable(std::string("gameModeAttack"), nullptr, &slot)) return false;
    uintptr_t vt = 0;
    if (!safeReadPtr(self_, 0, &vt) || !inGuestLib(vt)) return false;
    uintptr_t fn = 0;
    memcpy(&fn, reinterpret_cast<const void *>(vt + slot * 8), sizeof(fn));
    if (fn == 0 || !inExec(fn)) return false;
    using Fn = void (*)(void *, void *);
    reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_),
                              reinterpret_cast<void *>(targetActor));
    return true;
}

bool GameMode::buildBlock(const Vec3i &pos, uint8_t side) const {
    if (self_ == 0) return false;
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot = 0;
    if (!rule || !rule->lookupVtable(std::string("gameModeBuildBlock"), nullptr, &slot)) return false;
    uintptr_t vt = 0;
    if (!safeReadPtr(self_, 0, &vt) || !inGuestLib(vt)) return false;
    uintptr_t fn = 0;
    memcpy(&fn, reinterpret_cast<const void *>(vt + slot * 8), sizeof(fn));
    if (fn == 0 || !inExec(fn)) return false;
    using Fn = bool (*)(void *, const Vec3i *, uint8_t);
    return reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_), &pos, side);
}

bool GameMode::destroyBlock(const Vec3i &pos, uint8_t side) const {
    if (self_ == 0) return false;
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot = 0;
    if (!rule || !rule->lookupVtable(std::string("gameModeDestroyBlock"), nullptr, &slot)) return false;
    uintptr_t vt = 0;
    if (!safeReadPtr(self_, 0, &vt) || !inGuestLib(vt)) return false;
    uintptr_t fn = 0;
    memcpy(&fn, reinterpret_cast<const void *>(vt + slot * 8), sizeof(fn));
    if (fn == 0 || !inExec(fn)) return false;
    using Fn = bool (*)(void *, const Vec3i *, uint8_t);
    return reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_), &pos, side);
}

bool GameMode::startDestroyBlock(const Vec3i &pos, uint8_t side) const {
    if (self_ == 0) return false;
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot = 0;
    if (!rule || !rule->lookupVtable(std::string("gameModeStartDestroyBlock"), nullptr, &slot)) return false;
    uintptr_t vt = 0;
    if (!safeReadPtr(self_, 0, &vt) || !inGuestLib(vt)) return false;
    uintptr_t fn = 0;
    memcpy(&fn, reinterpret_cast<const void *>(vt + slot * 8), sizeof(fn));
    if (fn == 0 || !inExec(fn)) return false;
    using Fn = bool (*)(void *, const Vec3i *, uint8_t);
    return reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_), &pos, side);
}

bool GameMode::setAttackTime(int time) const {
    if (self_ == 0) return false;
    uintptr_t off = 0;
    if (!dataPin("gameModeAttackTimeField", &off)) return false;
    if (!inWritable(self_)) return false;
    int32_t val = time;
    memcpy(reinterpret_cast<void *>(self_ + off), &val, sizeof(val));
    return true;
}

bool GameMode::getPickRange(float *out) const {
    if (self_ == 0 || out == nullptr) return false;
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot = 0;
    if (!rule || !rule->lookupVtable(std::string("gameModeGetPickRange"), nullptr, &slot)) return false;
    uintptr_t vt = 0;
    if (!safeReadPtr(self_, 0, &vt) || !inGuestLib(vt)) return false;
    uintptr_t fn = 0;
    memcpy(&fn, reinterpret_cast<const void *>(vt + slot * 8), sizeof(fn));
    if (fn == 0 || !inExec(fn)) return false;
    using Fn = float (*)(void *);
    *out = reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_));
    return true;
}

}  // namespace mcpe