#include <mcpe/ClientInstance.h>

#include <cstring>
#include <vector>

#include <mcpe/GameSDK.h>
#include <util/HookRules.h>
#include <mcpe/GuiData.h>
#include <mcpe/Reflection.h>
#include <mcpe/Types.h>
#include <util/SigScanner.h>

// ClientInstance method bodies, verbatim from the original single-tree file:
// same names, same signatures, same ladder order. Only the file moved - the
// tree is split by Minecraft class, not redesigned.
namespace mcpe {

using detail::inWritable;
using detail::inExec;
using detail::inGuestLib;
using detail::safeReadPtr;
using detail::safeReadF32;
using detail::dataPin;
using detail::tablesFor;
using detail::fieldByPinOrScan;

Handle ClientInstance::guiData() const {
    Handle h;
    uintptr_t gd = GameSdk::guiData();
    if (gd != 0) {
        h.ptr = gd;
        h.source = GameSdk::guiDataSource();
    }
    return h;
}

Handle ClientInstance::minecraft() const {
    return fieldByPinOrScan(self_, "minecraftField", "Minecraft");
}

Handle ClientInstance::levelRenderer() const {
    return fieldByPinOrScan(self_, "levelRendererField", "LevelRenderer");
}

Handle ClientInstance::loopbackPacketSender() const {
    return fieldByPinOrScan(self_, "loopbackPacketSenderField", "LoopbackPacketSender");
}

Handle ClientInstance::getCamera() const {
    if (self_ == 0) return Handle{};
    // Try vtable slot first (reference: CI vtable slot for getCamera).
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot = 0;
    if (rule && rule->lookupVtable(std::string("clientInstanceGetCamera"), nullptr, &slot)) {
        uintptr_t vt = 0;
        if (safeReadPtr(self_, 0, &vt) && inGuestLib(vt)) {
            uintptr_t fn = 0;
            memcpy(&fn, reinterpret_cast<const void *>(vt + slot * 8), sizeof(fn));
            if (fn != 0 && inExec(fn)) {
                using Fn = uintptr_t (*)(void *);
                uintptr_t result = reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_));
                if (result != 0 && inWritable(result)) return Handle{result, "vcall"};
            }
        }
    }
    // Fallback: pinned field.
    return fieldByPinOrScan(self_, "cameraField", "Camera");
}

Handle ClientInstance::getOptions() const {
    if (self_ == 0) return Handle{};
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot = 0;
    if (rule && rule->lookupVtable(std::string("clientInstanceGetOptions"), nullptr, &slot)) {
        uintptr_t vt = 0;
        if (safeReadPtr(self_, 0, &vt) && inGuestLib(vt)) {
            uintptr_t fn = 0;
            memcpy(&fn, reinterpret_cast<const void *>(vt + slot * 8), sizeof(fn));
            if (fn != 0 && inExec(fn)) {
                using Fn = uintptr_t (*)(void *);
                uintptr_t result = reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_));
                if (result != 0 && inWritable(result)) return Handle{result, "vcall"};
            }
        }
    }
    return fieldByPinOrScan(self_, "optionsField", "Options");
}

Handle ClientInstance::getLocalPlayer() const {
    if (self_ == 0) return Handle{};
    // NO sig route here: the BedrockTools ClientInstanceGetLocalPlayer pattern
    // is weak (mostly wildcards) and on 1.26.45.1 it matched a different
    // function at base+0x9443364 (reads ci+0x218, calls into std::optional
    // helpers) - calling it on the title screen crashed the game. Until a
    // version-verified pin exists, only the vtable ladder below may run, and
    // it is fail-closed while clientInstanceGetLocalPlayerVt is commented out.
    // Fallback: vtable slot.
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot = 0;
    if (rule && rule->lookupVtable(std::string("clientInstanceGetLocalPlayerVt"), nullptr, &slot)) {
        uintptr_t vt = 0;
        if (safeReadPtr(self_, 0, &vt) && inGuestLib(vt)) {
            uintptr_t fn2 = 0;
            memcpy(&fn2, reinterpret_cast<const void *>(vt + slot * 8), sizeof(fn2));
            if (fn2 != 0 && inExec(fn2)) {
                using Fn2 = uintptr_t (*)(void *);
                uintptr_t result = reinterpret_cast<Fn2>(fn2)(reinterpret_cast<void *>(self_));
                if (result != 0 && inWritable(result)) return Handle{result, "vcall"};
            }
        }
    }
    return Handle{};
}

Handle ClientInstance::getRegion() const {
    if (self_ == 0) return Handle{};
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot = 0;
    if (rule && rule->lookupVtable(std::string("clientInstanceGetRegion"), nullptr, &slot)) {
        uintptr_t vt = 0;
        if (safeReadPtr(self_, 0, &vt) && inGuestLib(vt)) {
            uintptr_t fn = 0;
            memcpy(&fn, reinterpret_cast<const void *>(vt + slot * 8), sizeof(fn));
            if (fn != 0 && inExec(fn)) {
                using Fn = uintptr_t (*)(void *);
                uintptr_t result = reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_));
                if (result != 0 && inWritable(result)) return Handle{result, "vcall"};
            }
        }
    }
    return fieldByPinOrScan(self_, "regionField", "BlockSource");
}

Handle ClientInstance::getLevel() const {
    if (self_ == 0) return Handle{};
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot = 0;
    if (rule && rule->lookupVtable(std::string("clientInstanceGetLevel"), nullptr, &slot)) {
        uintptr_t vt = 0;
        if (safeReadPtr(self_, 0, &vt) && inGuestLib(vt)) {
            uintptr_t fn = 0;
            memcpy(&fn, reinterpret_cast<const void *>(vt + slot * 8), sizeof(fn));
            if (fn != 0 && inExec(fn)) {
                using Fn = uintptr_t (*)(void *);
                uintptr_t result = reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_));
                if (result != 0 && inWritable(result)) return Handle{result, "vcall"};
            }
        }
    }
    return fieldByPinOrScan(self_, "levelField", "Level");
}

bool ClientInstance::readProjection(Mat4 *matrix, Vec2 *fovTangents) const {
    if (self_ == 0 || matrix == nullptr || fovTangents == nullptr) return false;

    // Pins: dat.projectionMatrix=d0x458, dat.fovXY=d0x498 on 1.26.x (the
    // reference reads tangents at +0x498 and +0x4AC = +0x498+0x14). No pin,
    // no projection - we never guess where a matrix sits in someone's object.
    uintptr_t matrixOff = 0, fovOff = 0;
    if (!dataPin("projectionMatrix", &matrixOff)) return false;
    if (!dataPin("fovXY", &fovOff)) return false;

    Mat4 m;
    for (int i = 0; i < 16; i++) {
        if (!safeReadF32(self_, matrixOff + 4 * i, &m.m[i])) return false;
    }
    float tx = 0.f, ty = 0.f;
    if (!safeReadF32(self_, fovOff, &tx)) return false;
    if (!safeReadF32(self_, fovOff + 0x14, &ty)) return false;
    if (!(tx > 0.f && tx < 100.f) || !(ty > 0.f && ty < 100.f)) return false;
    *matrix = m;
    fovTangents->x = tx;
    fovTangents->y = ty;
    return true;
}

bool ClientInstance::worldToScreenWithCamera(const Vec3 &cameraPos, const Vec3 &worldPos,
                                             Vec2 *out) const {
    if (out == nullptr) return false;
    Mat4 m;
    Vec2 fov;
    if (!readProjection(&m, &fov)) return false;

    Vec3 rel(worldPos.x - cameraPos.x, worldPos.y - cameraPos.y, worldPos.z - cameraPos.z);
    // Reference transformx/y/z: row-major reads of the stored buffer.
    const float *v = m.m;
    Vec3 view;
    view.x = rel.x * v[0] + rel.y * v[4] + rel.z * v[8] + v[12];
    view.y = rel.x * v[1] + rel.y * v[5] + rel.z * v[9] + v[13];
    view.z = rel.x * v[2] + rel.y * v[6] + rel.z * v[10] + v[14];

    uintptr_t gdPtr = GameSdk::guiData();
    if (gdPtr == 0) return false;
    Vec2 win;
    if (!GuiData(gdPtr).windowSize(&win, nullptr)) return false;
    return mcpe::worldToScreen(view, fov.x, fov.y, win.x, win.y, out);
}

}  // namespace mcpe