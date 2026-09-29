#include <mcpe/LevelRendererPlayer.h>
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

bool LevelRendererPlayer::getCameraPos(Vec3 *out) const {
    if (self_ == 0 || out == nullptr) return false;
    // Reference: LevelRendererPlayer::getCameraPos is a virtual call.
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot = 0;
    if (!rule || !rule->lookupVtable(std::string("lrpGetCameraPos"), nullptr, &slot)) {
        // Fallback: try direct field read via cameraPosField pin.
        uintptr_t off = 0;
        if (!dataPin("lrpCameraPosField", &off)) return false;
        float x = 0.f, y = 0.f, z = 0.f;
        if (!safeReadF32(self_, off, &x) || !safeReadF32(self_, off+4, &y) ||
            !safeReadF32(self_, off+8, &z)) return false;
        out->x = x; out->y = y; out->z = z;
        return true;
    }
    uintptr_t vt = 0;
    if (!safeReadPtr(self_, 0, &vt) || !inGuestLib(vt)) return false;
    uintptr_t fn = 0;
    memcpy(&fn, reinterpret_cast<const void *>(vt + slot * 8), sizeof(fn));
    if (fn == 0 || !inExec(fn)) return false;
    using Fn = void (*)(void *, Vec3 *);
    reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_), out);
    return true;
}

bool LevelRendererPlayer::getFov(float *out) const {
    if (self_ == 0 || out == nullptr) return false;
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot = 0;
    if (!rule || !rule->lookupVtable(std::string("lrpGetFov"), nullptr, &slot)) return false;
    uintptr_t vt = 0;
    if (!safeReadPtr(self_, 0, &vt) || !inGuestLib(vt)) return false;
    uintptr_t fn = 0;
    memcpy(&fn, reinterpret_cast<const void *>(vt + slot * 8), sizeof(fn));
    if (fn == 0 || !inExec(fn)) return false;
    using Fn = float (*)(void *);
    *out = reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_));
    return true;
}

bool LevelRendererPlayer::getMat4(Mat4 *out) const {
    if (self_ == 0 || out == nullptr) return false;
    uintptr_t off = 0;
    if (!dataPin("lrpMatrixField", &off)) return false;
    if (!inWritable(self_ + off)) return false;
    float vals[16];
    for (int i = 0; i < 16; i++) {
        if (!safeReadF32(self_, off + i * 4, &vals[i])) return false;
    }
    memcpy(out->m, vals, sizeof(vals));
    return true;
}

}  // namespace mcpe