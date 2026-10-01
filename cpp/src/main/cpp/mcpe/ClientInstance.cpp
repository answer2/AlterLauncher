#include <mcpe/ClientInstance.h>

#include <android/log.h>
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

namespace {

// A pointer that came out of a Bedrock accessor is only reported when the
// object behind it carries a vtable RTTI has validated for one of the entity
// classes. A stale slot, a mis-called function or a title-screen empty
// optional then yields no handle instead of a crash-grade pointer, which is
// what makes the pinned virtual call in getLocalPlayer() acceptable at all.
bool looksLikePlayer(uintptr_t cand) {
    if (cand == 0) return false;
    const char *const kClasses[] = {"LocalPlayer", "Player", "Actor"};
    for (size_t i = 0; i < sizeof(kClasses) / sizeof(kClasses[0]); i++) {
        const std::vector<uintptr_t> &tables = detail::tablesFor(kClasses[i]);
        if (!tables.empty() &&
            Reflection::vtableMatchesAny(cand, tables.data(), tables.size()))
            return true;
    }
    return false;
}

}  // namespace

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
            memcpy(&fn, reinterpret_cast<const void *>(vt + detail::kVtableHeaderSize + slot * 8), sizeof(fn));
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
    // 1. Inline sub-object. On 1.26.x the getter body is `add x0,x0,#<off>; ret`
    //    - Options is constructed in place inside ClientInstance, so the pinned
    //    offset is the SUB-OBJECT address, not a stored pointer. A pointer scan
    //    can never find it (it is not referenced by any pointer), which is why
    //    this getter used to fall straight into the fragile cross-version virtual
    //    and take the host down. Certify the vtable that sits AT ci+off before
    //    publishing it - the same structural, read-only rung GuiData uses, and no
    //    call into the game.
    uintptr_t subOff = 0;
    if (dataPin("optionsField", &subOff)) {
        uintptr_t cand = self_ + subOff;
        uintptr_t vt = 0;
        if (safeReadPtr(cand, 0, &vt) && inGuestLib(vt)) {
            const std::vector<uintptr_t> &tables = tablesFor("Options");
            if (tables.empty() ||
                Reflection::vtableMatchesAny(cand, tables.data(), tables.size()))
                return Handle{cand, "sub"};
        }
    }
    // 2. Pointer-member build (a version that stores Options* at the pin):
    //    fieldByPinOrScan dereferences + RTTI-validates, still read-only.
    Handle pinned = fieldByPinOrScan(self_, "optionsField", "Options");
    if (pinned.ptr != 0) return pinned;
    // 3. Last resort only: the pinned virtual, unchanged. A build whose getter
    //    neither inlines Options nor stores it as a scanned pointer still works,
    //    but no 1.26.x build ever reaches it now.
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot = 0;
    if (rule && rule->lookupVtable(std::string("clientInstanceGetOptions"), nullptr, &slot)) {
        uintptr_t vt = 0;
        if (safeReadPtr(self_, 0, &vt) && inGuestLib(vt)) {
            uintptr_t fn = 0;
            memcpy(&fn, reinterpret_cast<const void *>(vt + detail::kVtableHeaderSize + slot * 8), sizeof(fn));
            if (fn != 0 && inExec(fn)) {
                using Fn = uintptr_t (*)(void *);
                uintptr_t result = reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_));
                if (result != 0 && inWritable(result)) return Handle{result, "vcall"};
            }
        }
    }
    return Handle{};
}

Handle ClientInstance::getLocalPlayer() const {
    if (self_ == 0) return Handle{};
    // NO blind sig route: the BedrockTools ClientInstanceGetLocalPlayer pattern
    // resolves to base+0x9443364, which disassembly shows IS the game's own
    // getter (vtable slot 32). What made the earlier title-screen call unsafe
    // was trusting whatever it returned, not the function itself - so every
    // result here passes looksLikePlayer, and nothing is called at all unless
    // the client reports an in-game session.
    if (!GameSdk::isInGame()) return Handle{};

    // 1. Field pin, RTTI-validated inside fieldByPinOrScan: a build whose local
    //    player is a plain member needs no call at all.
    Handle pinned = fieldByPinOrScan(self_, "localPlayerField", "LocalPlayer");
    if (pinned.ptr != 0) return pinned;

    // 2. The same function by signature FIRST. The pattern is byte-matched
    //    against THIS exact mapping, so it lands on the real getter even where
    //    the reference/BedrockTools slot number has shifted - which is precisely
    //    what faulted the game process when the slot rung ran ahead of it
    //    (slot 32 is getLocalPlayer in the reference table, but not at offset
    //    32 of the runtime primary vtable on 1.26.45.1). Identical body, so the
    //    plain uintptr_t(*)(void*) trampoline is the correct ABI and every
    //    result still passes looksLikePlayer.
    uintptr_t sigFn = SigScanner::resolve(std::string("clientInstanceGetLocalPlayer"));
    if (sigFn != 0 && inExec(sigFn)) {
        using Fn = uintptr_t (*)(void *);
        uintptr_t result = reinterpret_cast<Fn>(sigFn)(reinterpret_cast<void *>(self_));
        uintptr_t vt = 0;
        bool vtOk = result != 0 && safeReadPtr(result, 0, &vt);
        __android_log_print(ANDROID_LOG_INFO, "AlterSelfTest",
                            "lp-sig probe: fn=%p result=%p writable=%d vt=%p guestVt=%d "
                            "looksPlayer=%d",
                            reinterpret_cast<void *>(sigFn), reinterpret_cast<void *>(result),
                            result != 0 && inWritable(result) ? 1 : 0, reinterpret_cast<void *>(vt),
                            vtOk && inGuestLib(vt) ? 1 : 0, looksLikePlayer(result) ? 1 : 0);
        if (result != 0 && inWritable(result) && looksLikePlayer(result))
            return Handle{result, "sig"};
    }
    // 3. Last resort: the pinned vtable virtual, only for a build that carries
    //    neither the pattern nor a plain member. Its AArch64 body (1.26.45.1,
    //    0x9443364, 112 bytes) is `mov x8,x0` (this scratch), copy the ref at
    //    this+0x218 into a 0x19-byte local, test the local's +0x18 flag, and on
    //    the empty path `mov x0,xzr; ret` - value leaves in x0, so the plain
    //    trampoline ABI holds.
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot = 0;
    if (rule && rule->lookupVtable(std::string("clientInstanceGetLocalPlayerVt"), nullptr, &slot)) {
        uintptr_t vt = 0;
        if (safeReadPtr(self_, 0, &vt) && inGuestLib(vt)) {
            uintptr_t fn2 = 0;
            memcpy(&fn2, reinterpret_cast<const void *>(vt + detail::kVtableHeaderSize + slot * 8), sizeof(fn2));
            if (fn2 != 0 && inExec(fn2)) {
                using Fn2 = uintptr_t (*)(void *);
                uintptr_t result = reinterpret_cast<Fn2>(fn2)(reinterpret_cast<void *>(self_));
                if (result != 0 && inWritable(result) && looksLikePlayer(result))
                    return Handle{result, "vcall"};
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
            memcpy(&fn, reinterpret_cast<const void *>(vt + detail::kVtableHeaderSize + slot * 8), sizeof(fn));
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
            memcpy(&fn, reinterpret_cast<const void *>(vt + detail::kVtableHeaderSize + slot * 8), sizeof(fn));
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