#include <mcpe/Actor.h>

#include <cstring>
#include <string>

#include <mcpe/GameSDK.h>
#include <util/HookRules.h>
#include <mcpe/Level.h>
#include <mcpe/Dimension.h>
#include <mcpe/BlockSource.h>
#include <mcpe/GameMode.h>
#include <mcpe/ItemStack.h>
#include <mcpe/ClientInstance.h>

namespace mcpe {

using detail::inWritable;
using detail::inExec;
using detail::inGuestLib;
using detail::safeReadPtr;
using detail::safeReadF32;
using detail::safeReadU8;
using detail::safeReadU32;
using detail::safeReadI32;
using detail::dataPin;
using detail::fieldByPinOrScan;
using detail::readField;

namespace {

// Bedrock keeps an entity's positional state behind a component pointer rather
// than inline, and the ARM bodies prove which pointer and which inner offsets:
// Actor+0x208 is the movement component (getFiringPos loads it and reads three
// floats from +0x00; lerpMotion stores its argument at +0x18), so position and
// motion are one component apart. Both halves stay rule pins - the resolution
// here just applies the indirection the game itself applies.
bool movementComponent(uintptr_t self, uintptr_t *out) {
    uintptr_t off = 0;
    if (self == 0 || !dataPin("actorMovementPtr", &off)) return false;
    uintptr_t comp = 0;
    if (!safeReadPtr(self, off, &comp) || !inWritable(comp)) return false;
    *out = comp;
    return true;
}

bool readMovementVec3(uintptr_t self, const char *fieldKey, Vec3 *out) {
    uintptr_t comp = 0, off = 0;
    if (!movementComponent(self, &comp) || !dataPin(fieldKey, &off)) return false;
    float v[3] = {0.f, 0.f, 0.f};
    for (int i = 0; i < 3; i++) {
        if (!safeReadF32(comp, off + 4 * i, &v[i])) return false;
    }
    out->x = v[0];
    out->y = v[1];
    out->z = v[2];
    return true;
}

}  // namespace

// ---------------------------------------------------------------------------
// Actor
// ---------------------------------------------------------------------------

bool Actor::getPos(Vec3 *out) const {
    if (self_ == 0 || out == nullptr) return false;
    // Try virtual call first: vtable.actorGetPos=Actor:N
    // The virtual getter returns a Vec3* pointer.
    uintptr_t off = 0;
    if (dataPin("actorPositionField", &off)) {
        float x = 0.f, y = 0.f, z = 0.f;
        if (safeReadF32(self_, off, &x) && safeReadF32(self_, off + 4, &y) &&
            safeReadF32(self_, off + 8, &z)) {
            out->x = x; out->y = y; out->z = z;
            return true;
        }
    }
    // Movement component (1.26.45.1: actorMovementPtr + actorPosInMovement).
    if (readMovementVec3(self_, "actorPosInMovement", out)) return true;
    // Fallback: try vtable slot call if pinned
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot = 0;
    if (rule && rule->lookupVtable(std::string("actorGetPos"), nullptr, &slot)) {
        // Slot is dynamic (rule-pinned), so the callVirtual<> template cannot be
        // used; the manual path below applies the same region checks.
        uintptr_t vt = 0;
        if (safeReadPtr(self_, 0, &vt) && inGuestLib(vt)) {
            uintptr_t fn = 0;
            memcpy(&fn, reinterpret_cast<const void *>(vt + detail::kVtableHeaderSize + slot * 8), sizeof(fn));
            if (fn != 0 && inExec(fn)) {
                using Fn = uintptr_t (*)(void *);
                uintptr_t posPtr = reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_));
                if (posPtr != 0 && inWritable(posPtr)) {
                    float x = 0.f, y = 0.f, z = 0.f;
                    if (safeReadF32(posPtr, 0, &x) && safeReadF32(posPtr, 4, &y) &&
                        safeReadF32(posPtr, 8, &z)) {
                        out->x = x; out->y = y; out->z = z;
                        return true;
                    }
                }
            }
        }
    }
    return false;
}

bool Actor::getPosition(Vec3 *out) const {
    return getPos(out);
}

bool Actor::setPos(const Vec3 &pos) const {
    if (self_ == 0) return false;
    uintptr_t off = 0;
    if (!dataPin("actorPositionField", &off)) return false;
    if (!inWritable(self_)) return false;
    // Write the three floats directly.
    float x = pos.x, y = pos.y, z = pos.z;
    memcpy(reinterpret_cast<void *>(self_ + off), &x, 4);
    memcpy(reinterpret_cast<void *>(self_ + off + 4), &y, 4);
    memcpy(reinterpret_cast<void *>(self_ + off + 8), &z, 4);
    return true;
}

bool Actor::getViewAngles(Vec2 *out) const {
    if (self_ == 0 || out == nullptr) return false;
    uintptr_t off = 0;
    if (!dataPin("playerRotPtr", &off)) return false;
    uintptr_t rot = 0;
    if (!safeReadPtr(self_, off, &rot)) return false;
    float pitch = 0.f, yaw = 0.f;
    if (!safeReadF32(rot, 0x0, &pitch) || !safeReadF32(rot, 0x4, &yaw)) return false;
    out->x = pitch; out->y = yaw;
    return true;
}

bool Actor::getYaw(float *out) const {
    Vec2 angles;
    if (!getViewAngles(&angles)) return false;
    *out = angles.y;
    return true;
}

bool Actor::getPitch(float *out) const {
    Vec2 angles;
    if (!getViewAngles(&angles)) return false;
    *out = angles.x;
    return true;
}

bool Actor::getMotion(Vec3 *out) const {
    if (self_ == 0 || out == nullptr) return false;
    uintptr_t off = 0;
    if (dataPin("actorMotionField", &off)) {
        float x = 0.f, y = 0.f, z = 0.f;
        if (safeReadF32(self_, off, &x) && safeReadF32(self_, off + 4, &y) &&
            safeReadF32(self_, off + 8, &z)) {
            out->x = x; out->y = y; out->z = z;
            return true;
        }
    }
    // 1.26.45.1: motion is the second Vec3 in the movement component -
    // lerpMotion stores its argument as [c+0x18]=x,[c+0x1c]=y (one 8-byte
    // load/store pair) and [c+0x20]=z, where c = *(Actor+0x208).
    return readMovementVec3(self_, "actorMotionInMovement", out);
}

bool Actor::lerpMotion(const Vec3 &motion) const {
    if (self_ == 0) return false;
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot = 0;
    if (!rule || !rule->lookupVtable(std::string("actorLerpMotion"), nullptr, &slot)) return false;
    uintptr_t vt = 0;
    if (!safeReadPtr(self_, 0, &vt) || !inGuestLib(vt)) return false;
    uintptr_t fn = 0;
    memcpy(&fn, reinterpret_cast<const void *>(vt + detail::kVtableHeaderSize + slot * 8), sizeof(fn));
    if (fn == 0 || !inExec(fn)) return false;
    using Fn = void (*)(void *, const Vec3 *);
    reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_), &motion);
    return true;
}

bool Actor::jumpFromGround() const {
    if (self_ == 0) return false;
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot = 0;
    if (!rule || !rule->lookupVtable(std::string("actorJump"), nullptr, &slot)) return false;
    uintptr_t vt = 0;
    if (!safeReadPtr(self_, 0, &vt) || !inGuestLib(vt)) return false;
    uintptr_t fn = 0;
    memcpy(&fn, reinterpret_cast<const void *>(vt + detail::kVtableHeaderSize + slot * 8), sizeof(fn));
    if (fn == 0 || !inExec(fn)) return false;
    using Fn = void (*)(void *);
    reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_));
    return true;
}

bool Actor::isAlive() const {
    if (self_ == 0) return false;
    // The virtual answers, because the flag underneath it has no stable
    // polarity: on 1.26.45.1 Actor slot 47 is `ldrb w8,[x0,#0x251]; cmp w8,#0;
    // cset w0,eq` - alive is 0 there, so a dat.actorAliveField pin would bake
    // one build's sense into an API that must mean the same thing everywhere.
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot = 0;
    if (rule && rule->lookupVtable(std::string("actorIsAlive"), nullptr, &slot)) {
        uintptr_t vt = 0;
        if (safeReadPtr(self_, 0, &vt) && inGuestLib(vt)) {
            uintptr_t fn = 0;
            memcpy(&fn, reinterpret_cast<const void *>(vt + detail::kVtableHeaderSize + slot * 8), sizeof(fn));
            if (fn != 0 && inExec(fn)) {
                using Fn = bool (*)(void *);
                return reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_));
            }
        }
    }
    uintptr_t off = 0;
    if (!dataPin("actorAliveField", &off)) return false;
    uint8_t val = 0;
    if (!safeReadU8(self_, off, &val)) return false;
    return val != 0;
}

bool Actor::isInvisible() const {
    if (self_ == 0) return false;
    uintptr_t off = 0;
    if (!dataPin("actorInvisibleField", &off)) return false;
    uint8_t val = 0;
    if (!safeReadU8(self_, off, &val)) return false;
    return val != 0;
}

bool Actor::isOnGround() const {
    if (self_ == 0) return false;
    uintptr_t off = 0;
    if (!dataPin("actorOnGroundField", &off)) return false;
    uint8_t val = 0;
    if (!safeReadU8(self_, off, &val)) return false;
    return val != 0;
}

bool Actor::swing() const {
    if (self_ == 0) return false;
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot = 0;
    if (!rule || !rule->lookupVtable(std::string("actorSwing"), nullptr, &slot)) return false;
    uintptr_t vt = 0;
    if (!safeReadPtr(self_, 0, &vt) || !inGuestLib(vt)) return false;
    uintptr_t fn = 0;
    memcpy(&fn, reinterpret_cast<const void *>(vt + detail::kVtableHeaderSize + slot * 8), sizeof(fn));
    if (fn == 0 || !inExec(fn)) return false;
    // Actor::swing(ActorSwingSource) - the source argument is register 1, so it
    // is passed explicitly (0 = the entity swung on its own).
    using Fn = void (*)(void *, int);
    reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_), 0);
    return true;
}

int Actor::getEntityTypeId() const {
    if (self_ == 0) return -1;
    uintptr_t off = 0;
    if (!dataPin("actorEntityTypeIdField", &off)) return -1;
    int32_t val = 0;
    if (!safeReadI32(self_, off, &val)) return -1;
    return val;
}

bool Actor::getRuntimeID(uint64_t *out) const {
    if (self_ == 0 || out == nullptr) return false;
    uintptr_t off = 0;
    if (!dataPin("actorRuntimeIdField", &off)) return false;
    if (!inWritable(self_)) return false;
    uint64_t val = 0;
    memcpy(&val, reinterpret_cast<const void *>(self_ + off), sizeof(val));
    *out = val;
    return true;
}

bool Actor::getUniqueID(uint64_t *out) const {
    if (self_ == 0 || out == nullptr) return false;
    uintptr_t off = 0;
    if (!dataPin("actorUniqueIdField", &off)) return false;
    if (!inWritable(self_)) return false;
    uint64_t val = 0;
    memcpy(&val, reinterpret_cast<const void *>(self_ + off), sizeof(val));
    *out = val;
    return true;
}

bool Actor::getHealth(float *out) const {
    if (self_ == 0 || out == nullptr) return false;
    uintptr_t off = 0;
    if (!dataPin("actorHealthField", &off)) return false;
    return safeReadF32(self_, off, out);
}

bool Actor::getMaxHealth(float *out) const {
    if (self_ == 0 || out == nullptr) return false;
    uintptr_t off = 0;
    if (!dataPin("actorMaxHealthField", &off)) return false;
    return safeReadF32(self_, off, out);
}

bool Actor::getAABB(AABB *out) const {
    if (self_ == 0 || out == nullptr) return false;
    uintptr_t off = 0;
    if (!dataPin("actorAABBField", &off)) return false;
    float vals[6] = {};
    for (int i = 0; i < 6; i++) {
        if (!safeReadF32(self_, off + 4 * i, &vals[i])) return false;
    }
    out->minX = vals[0]; out->minY = vals[1]; out->minZ = vals[2];
    out->maxX = vals[3]; out->maxY = vals[4]; out->maxZ = vals[5];
    return true;
}

bool Actor::getWidth(float *out) const {
    if (self_ == 0 || out == nullptr) return false;
    uintptr_t off = 0;
    if (!dataPin("actorWidthField", &off)) return false;
    return safeReadF32(self_, off, out);
}

bool Actor::getHeight(float *out) const {
    if (self_ == 0 || out == nullptr) return false;
    uintptr_t off = 0;
    if (!dataPin("actorHeightField", &off)) return false;
    return safeReadF32(self_, off, out);
}

bool Actor::setSize(float width, float height) const {
    if (self_ == 0 || !inWritable(self_)) return false;
    uintptr_t wOff = 0, hOff = 0;
    if (!dataPin("actorWidthField", &wOff)) return false;
    if (!dataPin("actorHeightField", &hOff)) return false;
    memcpy(reinterpret_cast<void *>(self_ + wOff), &width, 4);
    memcpy(reinterpret_cast<void *>(self_ + hOff), &height, 4);
    return true;
}

Handle Actor::getDimension() const {
    if (self_ == 0) return Handle{};
    uintptr_t off = 0;
    if (!dataPin("actorDimensionField", &off)) return Handle{};
    uintptr_t dim = 0;
    if (!safeReadPtr(self_, off, &dim) || !inWritable(dim)) return Handle{};
    return Handle{dim, "pin"};
}

Handle Actor::getRegion() const {
    if (self_ == 0) return Handle{};
    uintptr_t off = 0;
    if (!dataPin("actorRegionField", &off)) return Handle{};
    uintptr_t reg = 0;
    if (!safeReadPtr(self_, off, &reg) || !inWritable(reg)) return Handle{};
    return Handle{reg, "pin"};
}

Handle Actor::getVehicle() const {
    if (self_ == 0) return Handle{};
    uintptr_t off = 0;
    if (!dataPin("actorVehicleField", &off)) return Handle{};
    uintptr_t veh = 0;
    if (!safeReadPtr(self_, off, &veh) || veh == 0) return Handle{};
    if (!inWritable(veh)) return Handle{};
    return Handle{veh, "pin"};
}

bool Actor::stopRiding() const {
    if (self_ == 0) return false;
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot = 0;
    if (!rule || !rule->lookupVtable(std::string("actorStopRiding"), nullptr, &slot)) return false;
    uintptr_t vt = 0;
    if (!safeReadPtr(self_, 0, &vt) || !inGuestLib(vt)) return false;
    uintptr_t fn = 0;
    memcpy(&fn, reinterpret_cast<const void *>(vt + detail::kVtableHeaderSize + slot * 8), sizeof(fn));
    if (fn == 0 || !inExec(fn)) return false;
    using Fn = void (*)(void *);
    reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_));
    return true;
}

bool Actor::actorTeleportTo(const Vec3 &pos) const {
    if (self_ == 0) return false;
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot = 0;
    if (!rule || !rule->lookupVtable(std::string("actorTeleportTo"), nullptr, &slot)) return false;
    uintptr_t vt = 0;
    if (!safeReadPtr(self_, 0, &vt) || !inGuestLib(vt)) return false;
    uintptr_t fn = 0;
    memcpy(&fn, reinterpret_cast<const void *>(vt + detail::kVtableHeaderSize + slot * 8), sizeof(fn));
    if (fn == 0 || !inExec(fn)) return false;
    // Actor::teleportTo(Vec3 const&, bool, int, int, bool): the reference table
    // gives five parameters, and slot 22's own prologue keeps w5 (`mov w21,w5`)
    // and branches on w2 (`tbz w2,#0`), so the two-instruction call this used to
    // make handed the game whatever the caller's registers happened to hold.
    // Every flag goes in as zero - the least-surprising form - and mods that
    // need a server-authoritative move should send the packet instead.
    using Fn = void (*)(void *, const Vec3 *, int, int, int, int);
    reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_), &pos, 0, 0, 0, 0);
    return true;
}

bool Actor::startSpinAttack() const {
    if (self_ == 0) return false;
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot = 0;
    if (!rule || !rule->lookupVtable(std::string("actorStartSpinAttack"), nullptr, &slot)) return false;
    uintptr_t vt = 0;
    if (!safeReadPtr(self_, 0, &vt) || !inGuestLib(vt)) return false;
    uintptr_t fn = 0;
    memcpy(&fn, reinterpret_cast<const void *>(vt + detail::kVtableHeaderSize + slot * 8), sizeof(fn));
    if (fn == 0 || !inExec(fn)) return false;
    using Fn = void (*)(void *);
    reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_));
    return true;
}

bool Actor::stopSpinAttack() const {
    if (self_ == 0) return false;
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot = 0;
    if (!rule || !rule->lookupVtable(std::string("actorStopSpinAttack"), nullptr, &slot)) return false;
    uintptr_t vt = 0;
    if (!safeReadPtr(self_, 0, &vt) || !inGuestLib(vt)) return false;
    uintptr_t fn = 0;
    memcpy(&fn, reinterpret_cast<const void *>(vt + detail::kVtableHeaderSize + slot * 8), sizeof(fn));
    if (fn == 0 || !inExec(fn)) return false;
    using Fn = void (*)(void *);
    reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_));
    return true;
}

bool Actor::startSwimming() const {
    if (self_ == 0) return false;
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot = 0;
    if (!rule || !rule->lookupVtable(std::string("actorStartSwimming"), nullptr, &slot)) return false;
    uintptr_t vt = 0;
    if (!safeReadPtr(self_, 0, &vt) || !inGuestLib(vt)) return false;
    uintptr_t fn = 0;
    memcpy(&fn, reinterpret_cast<const void *>(vt + detail::kVtableHeaderSize + slot * 8), sizeof(fn));
    if (fn == 0 || !inExec(fn)) return false;
    using Fn = void (*)(void *);
    reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_));
    return true;
}

bool Actor::stopSwimming() const {
    if (self_ == 0) return false;
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot = 0;
    if (!rule || !rule->lookupVtable(std::string("actorStopSwimming"), nullptr, &slot)) return false;
    uintptr_t vt = 0;
    if (!safeReadPtr(self_, 0, &vt) || !inGuestLib(vt)) return false;
    uintptr_t fn = 0;
    memcpy(&fn, reinterpret_cast<const void *>(vt + detail::kVtableHeaderSize + slot * 8), sizeof(fn));
    if (fn == 0 || !inExec(fn)) return false;
    using Fn = void (*)(void *);
    reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_));
    return true;
}

bool Actor::getEyesPos(Vec3 *out) const {
    Vec3 pos;
    if (!getPos(&pos)) return false;
    float h = 0.f;
    if (!getHeight(&h)) return false;
    out->x = pos.x;
    out->y = pos.y + h * 0.85f;
    out->z = pos.z;
    return true;
}

bool Actor::getSelectedSlot(int *out) const {
    if (self_ == 0 || out == nullptr) return false;
    uintptr_t off = 0;
    if (!dataPin("actorSelectedSlotField", &off)) return false;
    int32_t val = 0;
    if (!safeReadI32(self_, off, &val)) return false;
    *out = val;
    return true;
}

bool Actor::isRemoved() const {
    if (self_ == 0) return false;
    uintptr_t off = 0;
    if (!dataPin("actorRemovedField", &off)) return false;
    uint8_t val = 0;
    if (!safeReadU8(self_, off, &val)) return false;
    return val != 0;
}

std::string Actor::getNameTag() const {
    if (self_ == 0) return "";
    uintptr_t off = 0;
    if (!dataPin("actorNameTagField", &off)) return "";
    // std::string layout on arm64: SSO buffer at +0, length at +0x18, capacity at +0x20
    // For short strings (<=22 chars), data is inline at +0.
    // For long strings, data pointer is at +0, length at +0x18.
    uintptr_t flag = 0;
    if (!safeReadPtr(self_, off, &flag)) return "";
    uint32_t len = 0;
    if (!safeReadU32(self_, off + 0x18, &len)) return "";
    if (len == 0 || len > 1024) return "";
    const char *data = nullptr;
    if ((flag & 1) != 0) {
        // Long string: pointer stored in the flag word itself (libc++ layout)
        uintptr_t ptr = flag & ~static_cast<uintptr_t>(1);
        data = reinterpret_cast<const char *>(ptr);
    } else {
        // Short string: data inline after the flag byte
        data = reinterpret_cast<const char *>(self_ + off + 1);
    }
    if (data == nullptr) return "";
    if (!inWritable(reinterpret_cast<uintptr_t>(data))) return "";
    return std::string(data, len);
}

uintptr_t Actor::vtable() const {
    if (self_ == 0) return 0;
    uintptr_t vt = 0;
    safeReadPtr(self_, 0, &vt);
    return vt;
}

// ---------------------------------------------------------------------------
// Player : Actor
// ---------------------------------------------------------------------------

bool Player::setPlayerGameType(int type) const {
    if (self_ == 0) return false;
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot = 0;
    if (!rule || !rule->lookupVtable(std::string("playerSetGameType"), nullptr, &slot)) return false;
    uintptr_t vt = 0;
    if (!safeReadPtr(self_, 0, &vt) || !inGuestLib(vt)) return false;
    uintptr_t fn = 0;
    memcpy(&fn, reinterpret_cast<const void *>(vt + detail::kVtableHeaderSize + slot * 8), sizeof(fn));
    if (fn == 0 || !inExec(fn)) return false;
    using Fn = void (*)(void *, int);
    reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_), type);
    return true;
}

bool Player::getPlayerGameType(int *out) const {
    if (self_ == 0 || out == nullptr) return false;
    uintptr_t off = 0;
    if (!dataPin("playerGameTypeField", &off)) return false;
    int32_t val = 0;
    if (!safeReadI32(self_, off, &val)) return false;
    *out = val;
    return true;
}

Handle Player::getSupplies() const {
    if (self_ == 0) return Handle{};
    uintptr_t off = 0;
    if (!dataPin("playerSuppliesField", &off)) return Handle{};
    uintptr_t val = 0;
    if (!safeReadPtr(self_, off, &val) || !inWritable(val)) return Handle{};
    return Handle{val, "pin"};
}

Handle Player::getArmorContainer() const {
    if (self_ == 0) return Handle{};
    uintptr_t off = 0;
    if (!dataPin("playerArmorContainerField", &off)) return Handle{};
    uintptr_t val = 0;
    if (!safeReadPtr(self_, off, &val) || !inWritable(val)) return Handle{};
    return Handle{val, "pin"};
}

bool Player::setArmor(int slot, uintptr_t itemStack) const {
    if (self_ == 0) return false;
    const HookRules::Rule *rule = GameSdk::rule();
    uintptr_t vslot = 0;
    if (!rule || !rule->lookupVtable(std::string("playerSetArmor"), nullptr, &vslot)) return false;
    uintptr_t vt = 0;
    if (!safeReadPtr(self_, 0, &vt) || !inGuestLib(vt)) return false;
    uintptr_t fn = 0;
    memcpy(&fn, reinterpret_cast<const void *>(vt + detail::kVtableHeaderSize + vslot * 8), sizeof(fn));
    if (fn == 0 || !inExec(fn)) return false;
    using Fn = void (*)(void *, int, void *);
    reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_), slot,
                              reinterpret_cast<void *>(itemStack));
    return true;
}

Handle Player::getArmor(int slot) const {
    if (self_ == 0) return Handle{};
    const HookRules::Rule *rule = GameSdk::rule();
    uintptr_t vslot = 0;
    if (!rule || !rule->lookupVtable(std::string("playerGetArmor"), nullptr, &vslot)) return Handle{};
    uintptr_t vt = 0;
    if (!safeReadPtr(self_, 0, &vt) || !inGuestLib(vt)) return Handle{};
    uintptr_t fn = 0;
    memcpy(&fn, reinterpret_cast<const void *>(vt + detail::kVtableHeaderSize + vslot * 8), sizeof(fn));
    if (fn == 0 || !inExec(fn)) return Handle{};
    using Fn = uintptr_t (*)(void *, int);
    uintptr_t result = reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_), slot);
    if (result == 0 || !inWritable(result)) return Handle{};
    return Handle{result, "vcall"};
}

bool Player::playerTeleportTo(const Vec3 &pos) const {
    return actorTeleportTo(pos);
}

bool Player::canOpenContainerScreen() const {
    if (self_ == 0) return false;
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot = 0;
    if (!rule || !rule->lookupVtable(std::string("playerCanOpenContainer"), nullptr, &slot)) return false;
    uintptr_t vt = 0;
    if (!safeReadPtr(self_, 0, &vt) || !inGuestLib(vt)) return false;
    uintptr_t fn = 0;
    memcpy(&fn, reinterpret_cast<const void *>(vt + detail::kVtableHeaderSize + slot * 8), sizeof(fn));
    if (fn == 0 || !inExec(fn)) return false;
    using Fn = bool (*)(void *);
    return reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_));
}

// ---------------------------------------------------------------------------
// LocalPlayer : Player
// ---------------------------------------------------------------------------

Handle LocalPlayer::getClientInstance() const {
    if (self_ == 0) return Handle{};
    uintptr_t off = 0;
    if (!dataPin("localPlayerClientInstanceField", &off)) return Handle{};
    uintptr_t val = 0;
    if (!safeReadPtr(self_, off, &val) || !inWritable(val)) return Handle{};
    return Handle{val, "pin"};
}

Handle LocalPlayer::getGameMode() const {
    if (self_ == 0) return Handle{};
    uintptr_t off = 0;
    if (!dataPin("localPlayerGameModeField", &off)) return Handle{};
    uintptr_t val = 0;
    if (!safeReadPtr(self_, off, &val) || !inWritable(val)) return Handle{};
    return Handle{val, "pin"};
}

Handle LocalPlayer::getAbilities() const {
    if (self_ == 0) return Handle{};
    uintptr_t off = 0;
    if (!dataPin("localPlayerAbilitiesField", &off)) return Handle{};
    uintptr_t val = 0;
    if (!safeReadPtr(self_, off, &val) || !inWritable(val)) return Handle{};
    return Handle{val, "pin"};
}

bool LocalPlayer::snapToHeadRot(float pitch, float yaw) const {
    if (self_ == 0) return false;
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot = 0;
    if (!rule || !rule->lookupVtable(std::string("localPlayerSnapToHeadRot"), nullptr, &slot)) return false;
    uintptr_t vt = 0;
    if (!safeReadPtr(self_, 0, &vt) || !inGuestLib(vt)) return false;
    uintptr_t fn = 0;
    memcpy(&fn, reinterpret_cast<const void *>(vt + detail::kVtableHeaderSize + slot * 8), sizeof(fn));
    if (fn == 0 || !inExec(fn)) return false;
    using Fn = void (*)(void *, float, float);
    reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_), pitch, yaw);
    return true;
}

bool LocalPlayer::setSprinting(bool enable) const {
    if (self_ == 0) return false;
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot = 0;
    if (!rule || !rule->lookupVtable(std::string("localPlayerSetSprinting"), nullptr, &slot)) return false;
    uintptr_t vt = 0;
    if (!safeReadPtr(self_, 0, &vt) || !inGuestLib(vt)) return false;
    uintptr_t fn = 0;
    memcpy(&fn, reinterpret_cast<const void *>(vt + detail::kVtableHeaderSize + slot * 8), sizeof(fn));
    if (fn == 0 || !inExec(fn)) return false;
    using Fn = void (*)(void *, bool);
    reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_), enable);
    return true;
}

bool LocalPlayer::applyTurnDelta(float pitchDelta, float yawDelta) const {
    if (self_ == 0) return false;
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot = 0;
    if (!rule || !rule->lookupVtable(std::string("localPlayerApplyTurnDelta"), nullptr, &slot)) return false;
    uintptr_t vt = 0;
    if (!safeReadPtr(self_, 0, &vt) || !inGuestLib(vt)) return false;
    uintptr_t fn = 0;
    memcpy(&fn, reinterpret_cast<const void *>(vt + detail::kVtableHeaderSize + slot * 8), sizeof(fn));
    if (fn == 0 || !inExec(fn)) return false;
    Vec2 delta(pitchDelta, yawDelta);
    using Fn = void (*)(void *, const Vec2 *);
    reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_), &delta);
    return true;
}

bool LocalPlayer::addEffect(uintptr_t effectInstance) const {
    if (self_ == 0 || effectInstance == 0) return false;
    if (!inWritable(effectInstance)) return false;
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot = 0;
    if (!rule || !rule->lookupVtable(std::string("playerAddEffect"), nullptr, &slot)) return false;
    uintptr_t vt = 0;
    if (!safeReadPtr(self_, 0, &vt) || !inGuestLib(vt)) return false;
    uintptr_t fn = 0;
    memcpy(&fn, reinterpret_cast<const void *>(vt + detail::kVtableHeaderSize + slot * 8), sizeof(fn));
    if (fn == 0 || !inExec(fn)) return false;
    using Fn = void (*)(void *, void *);
    reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_),
                              reinterpret_cast<void *>(effectInstance));
    return true;
}

bool LocalPlayer::removeAllEffects() const {
    if (self_ == 0) return false;
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot = 0;
    if (!rule || !rule->lookupVtable(std::string("playerRemoveAllEffects"), nullptr, &slot)) return false;
    uintptr_t vt = 0;
    if (!safeReadPtr(self_, 0, &vt) || !inGuestLib(vt)) return false;
    uintptr_t fn = 0;
    memcpy(&fn, reinterpret_cast<const void *>(vt + detail::kVtableHeaderSize + slot * 8), sizeof(fn));
    if (fn == 0 || !inExec(fn)) return false;
    using Fn = void (*)(void *);
    reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_));
    return true;
}

Handle LocalPlayer::getMoveInputHandler() const {
    if (self_ == 0) return Handle{};
    uintptr_t off = 0;
    if (!dataPin("localPlayerMoveInputField", &off)) return Handle{};
    uintptr_t val = 0;
    if (!safeReadPtr(self_, off, &val) || !inWritable(val)) return Handle{};
    return Handle{val, "pin"};
}

Handle LocalPlayer::getHeldItem() const {
    if (self_ == 0) return Handle{};
    // Try through the supplies/inventory chain
    Handle supplies = getSupplies();
    if (!supplies.valid()) return Handle{};
    uintptr_t off = 0;
    if (!dataPin("suppliesSelectedItemSlot", &off)) return Handle{};
    int32_t slot = 0;
    if (!safeReadI32(supplies.ptr, off, &slot)) return Handle{};
    return getItemStack(slot);
}

Handle LocalPlayer::getItemStack(int slot) const {
    if (self_ == 0) return Handle{};
    const HookRules::Rule *rule = GameSdk::rule();
    uintptr_t vslot = 0;
    if (!rule || !rule->lookupVtable(std::string("playerGetCarriedItem"), nullptr, &vslot)) return Handle{};
    uintptr_t vt = 0;
    if (!safeReadPtr(self_, 0, &vt) || !inGuestLib(vt)) return Handle{};
    uintptr_t fn = 0;
    memcpy(&fn, reinterpret_cast<const void *>(vt + detail::kVtableHeaderSize + vslot * 8), sizeof(fn));
    if (fn == 0 || !inExec(fn)) return Handle{};
    using Fn = uintptr_t (*)(void *, int);
    uintptr_t result = reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_), slot);
    if (result == 0 || !inWritable(result)) return Handle{};
    return Handle{result, "vcall"};
}

// ---------------------------------------------------------------------------
// Backward-compatible LocalPlayer methods (old API names)
// ---------------------------------------------------------------------------

bool LocalPlayer::position(Vec3 *out) const {
    return getPos(out);
}

bool LocalPlayer::rotation(Vec2 *pitchYaw) const {
    return getViewAngles(pitchYaw);
}

}  // namespace mcpe