#include <mcpe/MinecraftGame.h>

#include <cstring>

#include <mcpe/GameSDK.h>
#include <util/HookRules.h>

// MinecraftGame + Minecraft method bodies.
namespace mcpe {

using detail::inWritable;
using detail::inExec;
using detail::safeReadPtr;
using detail::safeReadF32;
using detail::safeReadU8;
using detail::dataPin;

MinecraftGameHandle MinecraftGameHandle::fromSdk() {
    MinecraftGameHandle g;
    uintptr_t mg = GameSdk::minecraftGame();
    if (mg != 0) {
        g.h.ptr = mg;
        g.h.source = "sdk";
    }
    return g;
}

bool MinecraftGameHandle::canUseKeys() const {
    uintptr_t off = 0;
    if (!dataPin("canUseKeysField", &off)) return false;  // d0x190 on 3.4
    if (h.ptr == 0 || !inWritable(h.ptr)) return false;
    uint8_t value = 0;
    if (!safeReadU8(h.ptr, off, &value)) return false;
    return value != 0;
}

bool MinecraftGameHandle::isInGame() const {
    if (h.ptr == 0) return false;
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot = 0;
    if (!rule || !rule->lookupVtable(std::string("minecraftGameIsInGame"), nullptr, &slot)) return false;
    uintptr_t vt = 0;
    if (!safeReadPtr(h.ptr, 0, &vt) || !inExec(vt)) return false;
    uintptr_t fn = 0;
    memcpy(&fn, reinterpret_cast<const void *>(vt + slot * 8), sizeof(fn));
    if (fn == 0 || !inExec(fn)) return false;
    using Fn = bool (*)(void *);
    return reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(h.ptr));
}

bool MinecraftGameHandle::isPrimaryLocalPlayerInGame() const {
    if (h.ptr == 0) return false;
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot = 0;
    if (!rule || !rule->lookupVtable(std::string("minecraftGameIsPrimaryLocalPlayerInGame"), nullptr, &slot)) return false;
    uintptr_t vt = 0;
    if (!safeReadPtr(h.ptr, 0, &vt) || !inExec(vt)) return false;
    uintptr_t fn = 0;
    memcpy(&fn, reinterpret_cast<const void *>(vt + slot * 8), sizeof(fn));
    if (fn == 0 || !inExec(fn)) return false;
    using Fn = bool (*)(void *);
    return reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(h.ptr));
}

bool Minecraft::timerTime(float *time, float *speed) const {
    uintptr_t timerOff = 0;
    if (!dataPin("minecraftTimerField", &timerOff)) return false;  // d0xD0 ref
    uintptr_t timer = 0;
    if (!safeReadPtr(self_, timerOff, &timer)) return false;
    float t = 0.f, s = 0.f;
    if (!safeReadF32(timer, 0x18, &t)) return false;
    if (!safeReadF32(timer, 0xC, &s)) return false;
    if (time != nullptr) *time = t;
    if (speed != nullptr) *speed = s;
    return true;
}

}  // namespace mcpe