#include <mcpe/actor/Actor.h>

#include <android/log.h>
#include <cstring>
#include <atomic>
#include <string>

#include <mcpe/core/GameSDK.h>
#include <mcpe/core/Reflection.h>
#include <memory/HookRules.h>
#include <memory/RttiLookup.h>
#include <memory/SigScanner.h>
#include <mcpe/world/Level.h>
#include <mcpe/world/Dimension.h>
#include <mcpe/world/BlockSource.h>
#include <mcpe/actor/GameMode.h>
#include <mcpe/inventory/ItemStack.h>
#include <mcpe/client/ClientInstance.h>

namespace mcpe {

using detail::tablesFor;
using detail::inWritable;
using detail::inExec;
using detail::inGuestLib;
using detail::safeReadPtr;
using detail::safeReadF32;
using detail::safeReadU8;
using detail::safeReadU32;
using detail::safeReadU64;
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

#if defined(__aarch64__)
struct Float3Ret { float x, y, z; };

bool callVerifiedPosGetter(uintptr_t self, uintptr_t fn, Vec3 *out) {
    if (self == 0 || fn == 0 || out == nullptr || !inExec(fn)) return false;
    uint32_t w[4] = {0, 0, 0, 0};
    memcpy(w, reinterpret_cast<const void *>(fn), sizeof(w));
    // NDK clang positive control:
    // f9410408 ldr x8,[x0,#0x208]; 2d400500 ldp s0,s1,[x8];
    // bd400902 ldr s2,[x8,#8]; d65f03c0 ret.
    if ((w[0] & 0xFFC003FFu) != 0xF9400008u ||
        w[1] != 0x2D400500u || w[2] != 0xBD400902u || w[3] != 0xD65F03C0u)
        return false;
    typedef Float3Ret (*Fn)(void *);
    const Float3Ret r = reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self));
    out->x = r.x; out->y = r.y; out->z = r.z;
    return true;
}
#endif

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
    // Fallback: the measured 1.26.45.1 Actor:13 body returns a Vec3 HFA in
    // s0-s2, not a pointer. A stale rule must not turn that ABI distinction into
    // a guest call, so decode all four words before dispatching it.
#if defined(__aarch64__)
    const HookRules::Rule *rule = GameSdk::rule();
    std::string cls;
    size_t slot = 0;
    if (rule && rule->lookupVtable(std::string("actorGetPos"), &cls, &slot) && cls == "Actor") {
        uintptr_t vt = 0;
        if (safeReadPtr(self_, 0, &vt) && inGuestLib(vt)) {
            uintptr_t fn = 0;
            memcpy(&fn, reinterpret_cast<const void *>(vt + slot * sizeof(uintptr_t)), sizeof(fn));
            if (callVerifiedPosGetter(self_, fn, out)) return true;
        }
    }
#endif
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
    memcpy(&fn, reinterpret_cast<const void *>(vt + slot * sizeof(uintptr_t)), sizeof(fn));
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
    memcpy(&fn, reinterpret_cast<const void *>(vt + slot * sizeof(uintptr_t)), sizeof(fn));
    if (fn == 0 || !inExec(fn)) return false;
    using Fn = void (*)(void *);
    reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_));
    return true;
}

bool Actor::isAlive() const {
    if (self_ == 0) return false;
#if defined(__aarch64__)
    // The flag's polarity is part of the function body, not a stable field API.
    // Accept only the body assembled from the measured 1.26.45.1 semantics:
    //   ldrb w8,[x0,#0x251]; cmp w8,#0; cset w0,eq; ret.
    const HookRules::Rule *rule = GameSdk::rule();
    std::string cls;
    size_t slot = 0;
    if (rule && rule->lookupVtable(std::string("actorIsAlive"), &cls, &slot) && cls == "Actor") {
        uintptr_t vt = 0;
        if (safeReadPtr(self_, 0, &vt) && inGuestLib(vt)) {
            uintptr_t fn = 0;
            memcpy(&fn, reinterpret_cast<const void *>(vt + slot * sizeof(uintptr_t)), sizeof(fn));
            if (fn != 0 && inExec(fn)) {
                uint32_t w[4] = {};
                memcpy(w, reinterpret_cast<const void *>(fn), sizeof(w));
                if (w[0] == 0x39494408u && w[1] == 0x7100011Fu &&
                    w[2] == 0x1A9F17E0u && w[3] == 0xD65F03C0u) {
                    using Fn = bool (*)(void *);
                    return reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_));
                }
            }
        }
    }
#endif
    return false;
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
    memcpy(&fn, reinterpret_cast<const void *>(vt + slot * sizeof(uintptr_t)), sizeof(fn));
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

namespace {

// Health is neither an Actor member nor a vtable slot. The game keeps it in an
// 8-byte entt component addressed by the farmhash of the name "minecraft:health"
// (0xce6b34f6), and Actor::getHealth() at 0xEC9B700 of libminecraftpe
// 1.26.45.1 is that lookup inlined - the walk below is a transcription of its
// own 24 words, with every load turned into a checked read.
//
// Transcribing it instead of calling it is what makes a generic
// Actor::getHealth() safe: all eight of its failure branches land on
// `bl 0xEC9B7CC`, whose first instruction is `b _ZSt9terminatev@plt`. An entity
// without the component (arrow, dropped item, experience orb) therefore ends the
// process from inside the game, and no catch(...) intercepts a direct terminate
// call. Here the same conditions just return false.
//
// The packing is entt's, so it is the same in every word of the getter: the
// entity id is (version << 18) | page slot, the page index is (id >> 11) & 0x7F
// over 1024 ids per page, and the dense entry carries the version in its top bits
// with index = block * 128 + offset-in-block, 8 bytes per element. Each literal
// below appears verbatim above it: 0x38/0x40/0x50/0x58 on the registry, stride 32
// per pool, +8 for the pool's type hash, pool+0x10 storage, storage 8/0x10 page
// array and 0x50 block array, then 0xFFFC0000/0x3FFFE/0x7FF/0x3FF8/0x7F.
// The second half of the getter's work, split out so the storage LIST can be
// walked with the same arithmetic: given a pool already chosen (by key, or by
// enumerating the list), resolve this entity's element in it. Every step is a
// range-checked read; a component the entity does not carry just returns false
// rather than reaching the terminate branch the game's own copy would take.
bool elementInPool(uintptr_t pool, uint32_t id, uintptr_t stride,
                   uintptr_t *out, HealthWalkTrace *trace) {
    uintptr_t storage = 0;
    trace->stage = "storage";
    if (!safeReadPtr(pool, 0x10, &storage) || !inWritable(storage)) return false;
    trace->storage = storage;
    uintptr_t pageBegin = 0, pageEnd = 0;
    trace->stage = "pageArray";
    if (!safeReadPtr(storage, 8, &pageBegin) || !safeReadPtr(storage, 0x10, &pageEnd)) return false;
    if (pageEnd <= pageBegin) return false;
    const uintptr_t page = (static_cast<uintptr_t>(id) >> 11) & 0x7F;
    trace->stage = "page";
    if (page >= static_cast<uintptr_t>((pageEnd - pageBegin) >> 3)) return false;
    uintptr_t chunk = 0;
    if (!safeReadPtr(pageBegin, page * 8, &chunk) || chunk == 0) return false;
    uint32_t entry = 0;
    trace->stage = "entityToIndex";
    if (!safeReadU32(chunk, (id & 0x7FF) * 4, &entry)) return false;
    trace->entry = entry;
    if ((entry ^ (id & 0xFFFC0000u)) > 0x3FFFEu) { trace->stage = "generation"; return false; }
    uintptr_t blockArray = 0;
    trace->stage = "blockArray";
    if (!safeReadPtr(storage, 0x50, &blockArray)) return false;
    uintptr_t block = 0;
    trace->stage = "block";
    if (!safeReadPtr(blockArray, (entry >> 4) & 0x3FF8u, &block) || block == 0) return false;
    trace->stage = "element";
    if (stride == 0 || stride > 0x1000) return false;
    *out = block + static_cast<uintptr_t>(entry & 0x7F) * stride;
    trace->element = *out;
    return true;
}

// The walk in one call, staged. Every early return names the step that stopped
// it and leaves the values seen so far in *trace, because "the pin is wrong" and
// "this entity has no health" both read as a bare false otherwise - and only one
// of those two is a property of the game.
bool healthComponentElement(uintptr_t self, uintptr_t *out, HealthWalkTrace *trace) {
    HealthWalkTrace local = {};
    if (trace == nullptr) trace = &local;
    trace->stage = "arguments";
    uintptr_t hash = 0, ctxOff = 0, idOff = 0;
    if (self == 0 || out == nullptr) return false;
    for (int i = 0; i < 8; i++) {
        uint64_t w = 0;
        if (safeReadU64(self, static_cast<uintptr_t>(i) * sizeof(uintptr_t), &w))
            trace->words[i] = static_cast<uintptr_t>(w);
    }
    trace->stage = "pins";
    if (!dataPin("healthComponentHash", &hash) ||
        !dataPin("actorEntityContextField", &ctxOff) ||
        !dataPin("actorEntityIdField", &idOff))
        return false;
    trace->hash = hash;
    uintptr_t registry = 0;
    uint32_t id = 0;
    trace->stage = "registry";
    if (!safeReadPtr(self, ctxOff, &registry) || !inWritable(registry)) return false;
    trace->registry = registry;
    if (!safeReadU32(self, idOff, &id)) return false;
    trace->id = id;
    uintptr_t bucketBegin = 0, bucketEnd = 0, poolBegin = 0;
    trace->stage = "bucketArray";
    if (!safeReadPtr(registry, 0x38, &bucketBegin) ||
        !safeReadPtr(registry, 0x40, &bucketEnd) ||
        !safeReadPtr(registry, 0x50, &poolBegin))
        return false;
    trace->bucketBegin = bucketBegin;
    trace->bucketEnd = bucketEnd;
    trace->poolBegin = poolBegin;
    if (bucketEnd <= bucketBegin) return false;
    const uintptr_t mask = ((bucketEnd - bucketBegin) >> 3) - 1;
    // Both of these words are entt *indices*, not pointers - the getter does a
    // bare `ldr x11,[x11]` and multiplies the result by the 32-byte pool stride.
    // Reading them through safeReadPtr was the bug this trace caught: it refuses
    // 0, and index 0 is the first pool, so a perfectly good health storage read
    // as a failure.
    uint64_t link = 0;
    trace->stage = "bucketHead";
    if (!safeReadU64(bucketBegin + (mask & hash) * 8, 0, &link)) return false;
    trace->linkValue = link;
    uintptr_t pool = 0;
    bool found = false;
    trace->stage = "poolChain";
    for (size_t steps = 0; steps < 4096 && !found; steps++) {
        if (link == ~static_cast<uint64_t>(0) || link > 0x100000ull) {
            trace->links = steps + 1;
            trace->linkValue = link;
            return false;
        }
        pool = poolBegin + static_cast<uintptr_t>(link) * 32;
        uint32_t poolHash = 0;
        if (!safeReadU32(pool, 8, &poolHash)) { trace->pool = pool; return false; }
        trace->pool = pool;
        trace->poolHash = poolHash;
        if (poolHash == hash) { found = true; break; }
        if (!safeReadU64(pool, 0, &link)) return false;
    }
    uintptr_t poolEnd = 0;
    trace->stage = "poolEnd";
    if (!found || !safeReadPtr(registry, 0x58, &poolEnd) || pool == poolEnd) return false;
    return elementInPool(pool, id, 8, out, trace);
}

}  // namespace

// Self-test only: enumerate the registry's storage LIST and report, for each
// component this entity actually carries, the key at pool+8 and the first two
// words of its element.
//
// This enumerates real registered keys without trusting a name-hash function.
// Element values are read only for the two component strides this build's own
// getters prove (health=8, AttributesComponent=0x58); every other key stays a key
// only. That prevents a health-specific stride from manufacturing plausible but
// false values for unrelated components.
void probeComponentKeys(uintptr_t actor) {
    uintptr_t ctxOff = 0, idOff = 0, healthHash = 0;
    if (!dataPin("actorEntityContextField", &ctxOff) ||
        !dataPin("actorEntityIdField", &idOff))
        return;
    uintptr_t registry = 0;
    uint32_t id = 0;
    if (actor == 0 || !inWritable(actor)) return;
    if (!safeReadPtr(actor, ctxOff, &registry) || !inWritable(registry)) return;
    if (!safeReadU32(actor, idOff, &id)) return;
    uintptr_t poolBegin = 0, poolEnd = 0;
    if (!safeReadPtr(registry, 0x50, &poolBegin) || !safeReadPtr(registry, 0x58, &poolEnd)) return;
    if (poolEnd <= poolBegin || (poolEnd - poolBegin) % 32 != 0) {
        __android_log_print(ANDROID_LOG_INFO, "AlterSelfTest",
                            "component list: pool span %p..%p is not 32-byte strided",
                            reinterpret_cast<void *>(poolBegin), reinterpret_cast<void *>(poolEnd));
        return;
    }
    size_t count = static_cast<size_t>((poolEnd - poolBegin) / 32);
    if (count > 4096) count = 4096;
    dataPin("healthComponentHash", &healthHash);
    const uint32_t kAttributesKey = 0xFD3B0613u;
    // One line per key would push ~512 log entries and rotate the very lines
    // this probe exists to read (the dimension/level diagnostics share the
    // buffer), so the keys go out as a single hex run.
    std::string keys;
    char piece[12];
    size_t shown = 0, dumped = 0;
    for (size_t i = 0; i < count; i++) {
        const uintptr_t pool = poolBegin + i * 32;
        uint32_t key = 0;
        if (!safeReadU32(pool, 8, &key) || key == 0) continue;
        if (keys.size() < 24000) {
            snprintf(piece, sizeof(piece), "%08x ", key);
            keys += piece;
            shown++;
        }
        const bool interesting = key == healthHash || key == kAttributesKey;

        // Only these two strides come from this build's own getter bodies:
        // health uses lsl #3 (8 bytes), AttributesComponent uses mov w10,#0x58
        // followed by umaddl. Other pool values are deliberately not fabricated.
        const uintptr_t stride = key == healthHash ? 8 :
                                 (key == kAttributesKey ? 0x58 : 0);
        if (stride == 0 || !interesting || dumped >= 4) continue;
        dumped++;
        uintptr_t element = 0;
        HealthWalkTrace t{};
        if (!elementInPool(pool, id, stride, &element, &t)) {
            __android_log_print(ANDROID_LOG_INFO, "AlterSelfTest",
                                "component-element key=0x%08x pool=%u not carried (stage '%s')",
                                key, (unsigned) i, t.stage);
            continue;
        }
        __android_log_print(ANDROID_LOG_INFO, "AlterSelfTest",
                            "component-element key=0x%08x pool=%u stride=0x%zx element=%p",
                            key, (unsigned) i, static_cast<size_t>(stride),
                            reinterpret_cast<void *>(element));
        const size_t words = stride / 4;
        for (size_t base = 0; base < words; base += 4) {
            uint32_t w[4] = {};
            for (size_t j = 0; j < 4 && base + j < words; j++)
                safeReadU32(element, (base + j) * 4, &w[j]);
            __android_log_print(ANDROID_LOG_INFO, "AlterSelfTest",
                                "component-data key=0x%08x +0x%02zx: %08x %08x %08x %08x",
                                key, base * 4, w[0], w[1], w[2], w[3]);
        }
    }
    __android_log_print(ANDROID_LOG_INFO, "AlterSelfTest",
                        "component key list: %zu pools registered, %zu keys, entity id=%u",
                        count, shown, id);
    __android_log_print(ANDROID_LOG_INFO, "AlterSelfTest", "component keys: %s", keys.c_str());
}


bool Actor::getHealth(float *out) const {
    // The ECS walk and the game's pinned getter agree bit-for-bit, but both
    // returned 0.0 for the same live LocalPlayer across three samples, including
    // one after damage. That proves the acquisition mechanics, not the semantic
    // claim that this client-side component is displayed HP. Keep the diagnostic
    // walk below, but do not publish an unverified zero through the SDK.
    (void)out;
    return false;
}

// Self-test entry: run the walk and report where it stopped. Reading the float is
// the caller's business, so a trace of a *successful* lookup still says which
// element it landed on without pretending the two failures are the same.
bool traceHealthWalk(uintptr_t actor, float *health, HealthWalkTrace *trace) {
    if (trace != nullptr) *trace = HealthWalkTrace{};
    uintptr_t element = 0;
    HealthWalkTrace scratch;
    if (!healthComponentElement(actor, &element, trace ? trace : &scratch)) return false;
    if (health == nullptr) return true;
    return safeReadF32(element, 0, health);
}

bool Actor::getMaxHealth(float *out) const {
    // The element the walk lands on is 8 bytes wide and health sits at +0, so the
    // second float is the only candidate for the maximum - but "only candidate" is
    // not evidence, and the pin stays unset until a build's own getter says so.
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

// The Dimension is not an Actor member, and the thing at Actor+0x1c8 is not a
// BlockSource either - the device said so, and its vtable (RVA 0x11d96858)
// identifies it as std::__shared_ptr_emplace<OverworldDimension>, i.e. the control
// block of a shared_ptr to the dimension. The OverworldDimension object itself is
// stored INSIDE that block, at an offset libc++ picks, so it is found by a bounded
// read-only scan of the block's first 0x100 bytes for a pointer carrying a
// Dimension-family vtable. Both names are consulted because OverworldDimension
// derives from Dimension and shares slot 3 with it (both tables point at
// 0xf97c108, `ldr w0,[x0,#0x170]; ret`), which is the second, independent
// confirmation that the id lives at Dimension+0x170.
Handle Actor::getDimension() const {
    if (self_ == 0) return Handle{};
    std::vector<uintptr_t> tables = tablesFor("Dimension");
    const std::vector<uintptr_t> &over = tablesFor("OverworldDimension");
    for (size_t i = 0; i < over.size(); i++) tables.push_back(over[i]);
    if (tables.empty()) return Handle{};

    uintptr_t block = 0;
    uintptr_t pin = 0;
    // These two pins were taken from a device that reported the object behind
    // them (a libc++ __shared_ptr_emplace<OverworldDimension> control block), yet
    // getDimension() answers 0 with no `dimension probe:` line - so it leaves
    // here, before any of the three checks below is reported. Name which one
    // refuses, and what it saw, instead of returning an indistinguishable 0.
    // Read-only: one bounded probe per process, no guest memory is written.
    static std::atomic<bool> s_logged{false};
    auto diag = [&](const char *stage, uintptr_t off, uintptr_t raw, uintptr_t got) {
        if (s_logged.exchange(true)) return;
        __android_log_print(ANDROID_LOG_INFO, "AlterSelfTest",
                            "dimension pin diag: stage=%s pin=%s offset=0x%llx raw=0x%llx "
                            "usable=0x%llx tables=%zu",
                            stage, off ? "ok" : "MISSING", (unsigned long long)off,
                            (unsigned long long)raw, (unsigned long long)got, tables.size());
    };
    if (!dataPin("actorDimensionControlBlock", &pin)) {
        diag("noPin", 0, 0, 0);
        return Handle{};
    }
    // Reproduce what safeReadPtr does, but report the raw word even when it is
    // rejected, so a zero / misaligned / unmapped answer can be told apart.
    uintptr_t raw = 0;
    const bool addrOk = pin <= UINTPTR_MAX - self_ &&
                        RttiLookup::inWritableRange(self_ + pin, sizeof(raw));
    if (addrOk) memcpy(&raw, reinterpret_cast<const void *>(self_ + pin), sizeof(raw));
    if (!addrOk) {
        diag("unreadable", pin, 0, 0);
        return Handle{};
    }
    if (!safeReadPtr(self_, pin, &block)) {
        diag(raw == 0 ? "rawIsZero" : "rejected", pin, raw, 0);
        return Handle{};
    }
    if (!inWritable(block)) {
        diag("blockNotWritable", pin, raw, block);
        return Handle{};
    }

    static std::atomic<uintptr_t> s_field{0};
    uintptr_t field = s_field.load(std::memory_order_acquire);
    if (field == 0) {
        uintptr_t pinned = 0;
        if (dataPin("actorDimensionField", &pinned)) field = pinned;
    }
    uintptr_t dim = 0;
    if (field != 0 && safeReadPtr(block, field, &dim) && inWritable(dim) &&
        Reflection::vtableMatchesAny(dim, tables.data(), tables.size()))
        return Handle{dim, "scan"};

    uintptr_t found = 0;
    const uintptr_t off =
        Reflection::findMemberOffset(block, tables, 0x100, &found);
    if (found == 0 || off >= 0x100) {
        static std::atomic<bool> logged(false);
        if (!logged.exchange(true))
            __android_log_print(ANDROID_LOG_INFO, "AlterSelfTest",
                                "dimension probe: no Dimension-family object in "
                                "control block %p+0..0x100 (tables=%zu)",
                                reinterpret_cast<void *>(block), tables.size());
        return Handle{};
    }
    s_field.store(off, std::memory_order_release);
    static std::atomic<bool> logged(false);
    if (!logged.exchange(true))
        __android_log_print(ANDROID_LOG_INFO, "AlterSelfTest",
                            "dimension probe: dimension object at controlBlock+0x%x "
                            "(from %p+0x%x) - pin dat.actorDimensionField = d0x%x",
                            (unsigned) off, reinterpret_cast<void *>(self_),
                            (unsigned) pin, (unsigned) off);
    return Handle{found, "scan"};
}
Handle Actor::getRegion() const {
    if (self_ == 0) return Handle{};
    const std::vector<uintptr_t> &bsTables = detail::tablesFor("BlockSource");
    uintptr_t off = 0;
    const bool hasPin = dataPin("actorRegionField", &off);

    uintptr_t cand[2] = {0, self_ + 0x1d8};
    size_t n = 0;
    if (hasPin && safeReadPtr(self_, off, &cand[0])) { /* pointer candidate */ }
    n = 2;

    for (size_t i = 0; i < n; i++) {
        const uintptr_t obj = cand[i];
        if (obj == 0 || !inWritable(obj)) continue;
        if (!bsTables.empty() &&
            !Reflection::vtableMatchesAny(obj, bsTables.data(), bsTables.size()))
            continue;
        return Handle{obj, i == 0 ? "pin" : "sub"};
    }
    static std::atomic<bool> logged(false);
    if (!logged.exchange(true)) {
        uintptr_t p = 0, vt = 0;
        if (hasPin) safeReadPtr(self_, off, &p);
        if (p) safeReadPtr(p, 0, &vt);
        __android_log_print(ANDROID_LOG_INFO, "AlterSelfTest",
                            "region probe: no BlockSource (pin=%s +0x%x=%p vt=0x%lx "
                            "inline+0x1d8 vt=0x%lx bsTables=%zu)",
                            hasPin ? "yes" : "no", (unsigned) off,
                            reinterpret_cast<void *>(p),
                            (unsigned long)(p && vt ? vt - GameSdk::guestBase() : 0),
                            (unsigned long)(safeReadPtr(self_ + 0x1d8, 0, &vt)
                                ? vt - GameSdk::guestBase() : 0),
                            bsTables.size());
    }
    return Handle{};
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
    memcpy(&fn, reinterpret_cast<const void *>(vt + slot * sizeof(uintptr_t)), sizeof(fn));
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
    memcpy(&fn, reinterpret_cast<const void *>(vt + slot * sizeof(uintptr_t)), sizeof(fn));
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
    memcpy(&fn, reinterpret_cast<const void *>(vt + slot * sizeof(uintptr_t)), sizeof(fn));
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
    memcpy(&fn, reinterpret_cast<const void *>(vt + slot * sizeof(uintptr_t)), sizeof(fn));
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
    memcpy(&fn, reinterpret_cast<const void *>(vt + slot * sizeof(uintptr_t)), sizeof(fn));
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
    memcpy(&fn, reinterpret_cast<const void *>(vt + slot * sizeof(uintptr_t)), sizeof(fn));
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
#if defined(__aarch64__)
    static_assert(sizeof(std::string) == 24, "Guest string ABI requires 24-byte libc++ string");
    if (self_ == 0 || !inWritable(self_)) return {};
    uintptr_t fn = SigScanner::resolve(std::string("actorGetNameTag"));
    if (fn == 0 || !inExec(fn)) return {};
    // The guest constructs its return value in the caller's x8 storage.
    using Fn = std::string (*)(const void *);
    return reinterpret_cast<Fn>(fn)(reinterpret_cast<const void *>(self_));
#else
    return {};
#endif
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
    memcpy(&fn, reinterpret_cast<const void *>(vt + slot * sizeof(uintptr_t)), sizeof(fn));
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
    memcpy(&fn, reinterpret_cast<const void *>(vt + vslot * sizeof(uintptr_t)), sizeof(fn));
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
    memcpy(&fn, reinterpret_cast<const void *>(vt + vslot * sizeof(uintptr_t)), sizeof(fn));
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
    memcpy(&fn, reinterpret_cast<const void *>(vt + slot * sizeof(uintptr_t)), sizeof(fn));
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
    memcpy(&fn, reinterpret_cast<const void *>(vt + slot * sizeof(uintptr_t)), sizeof(fn));
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
    memcpy(&fn, reinterpret_cast<const void *>(vt + slot * sizeof(uintptr_t)), sizeof(fn));
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
    memcpy(&fn, reinterpret_cast<const void *>(vt + slot * sizeof(uintptr_t)), sizeof(fn));
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
    memcpy(&fn, reinterpret_cast<const void *>(vt + slot * sizeof(uintptr_t)), sizeof(fn));
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
    memcpy(&fn, reinterpret_cast<const void *>(vt + slot * sizeof(uintptr_t)), sizeof(fn));
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
    const HookRules::Rule *rule = GameSdk::rule();
    uintptr_t vslot = 0;
    if (!rule || !rule->lookupVtable(std::string("playerGetCarriedItem"), nullptr, &vslot)) return Handle{};
    uintptr_t vt = 0;
    if (!safeReadPtr(self_, 0, &vt) || !inGuestLib(vt)) return Handle{};
    uintptr_t fn = 0;
    memcpy(&fn, reinterpret_cast<const void *>(vt + vslot * sizeof(uintptr_t)), sizeof(fn));
    if (fn == 0 || !inExec(fn)) return Handle{};
    using Fn = uintptr_t (*)(void *);
    uintptr_t result = reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_));
    if (result == 0 || !inWritable(result)) return Handle{};
    return Handle{result, "vcall"};
}

Handle LocalPlayer::getItemStack(int slot) const {
    if (slot < 0) return Handle{};
    Handle supplies = getSupplies();
    uintptr_t off = 0;
    if (!supplies.valid() || !dataPin("suppliesSelectedItemSlot", &off)) return Handle{};
    int32_t selected = 0;
    if (!safeReadI32(supplies.ptr, off, &selected) || slot != selected) return Handle{};
    return getHeldItem();
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

// Self-test only: prove that Actor vtable slot 13 is the position getter, by
// calling it and comparing against the path that already works.
//
// Offline, slot 13's body on 1.26.45.1 is
//   ldr x8, [x0, #0x208]      <- the same field dat.actorMovementPtr pins
//   ldp s0, s1, [x8]          <- Vec3.x, Vec3.y
//   ldr s2, [x8, #8]          <- Vec3.z
//   ret
// so it hops through the movement structure and returns three floats, which is
// an HFA: AArch64 hands those back in s0-s2, with no hidden return pointer. The
// claim worth testing is not the disassembly (that is already read) but whether
// calling it through the vtable yields exactly what readMovementVec3() reads.
//
// The call is guarded by decoding the slot's own words first: the first
// instruction must be `ldr x8,[x0,#imm]` (mask-checked, and the imm is reported
// so a shifted build shows what it hops through now) and a plain `ret` must
// follow within three instructions. Anything else is not this getter and is not
// called.
#if defined(__aarch64__)
namespace {

bool readRelro(uintptr_t addr, uintptr_t *out) {
    if (addr == 0 || (addr % alignof(uintptr_t)) != 0) return false;
    const std::vector<RttiLookup::Region> &regs = RttiLookup::regionsOf(detail::kGuestLib);
    if (!RttiLookup::inRegions(regs, addr, false)) return false;
    memcpy(out, reinterpret_cast<const void *>(addr), sizeof(*out));
    return *out != 0;
}

bool decodeHopGetter(uintptr_t fn, uintptr_t *outFieldOff) {
    if (fn == 0 || outFieldOff == nullptr || !inExec(fn)) return false;
    uint32_t w[4] = {0, 0, 0, 0};
    memcpy(w, reinterpret_cast<const void *>(fn), sizeof(w));
    // Same strict shape as getPos(): ldr x8,[x0,#imm]; ldp s0,s1,[x8];
    // ldr s2,[x8,#8]; ret. Accepting merely "a later ret" used to prove only
    // that the slot was short, not that calling it had the Vec3 HFA ABI.
    if ((w[0] & 0xFFC003FFu) != 0xF9400008u ||
        w[1] != 0x2D400500u || w[2] != 0xBD400902u || w[3] != 0xD65F03C0u)
        return false;
    *outFieldOff = ((w[0] >> 10) & 0xFFFu) * 8u;
    return true;
}

}  // namespace
#endif

void probeGetPosVirtual(uintptr_t actor, const Vec3 &known) {
#if !defined(__aarch64__)
    // The decode above is AArch64 word arithmetic. On the 32-bit variant the
    // shapes are unrelated encodings, so a match would be coincidence rather
    // than evidence - and this probe exists to produce evidence.
    (void)actor; (void)known;
    __android_log_print(ANDROID_LOG_INFO, "AlterSelfTest",
                        "getPos probe: aarch64-only, skipped on this ABI");
    return;
#else
    const size_t kSlot = 13;
    if (actor == 0 || !inWritable(actor)) return;
    uintptr_t vt = 0;
    if (!safeReadPtr(actor, 0, &vt) || !inGuestLib(vt)) {
        __android_log_print(ANDROID_LOG_INFO, "AlterSelfTest",
                            "getPos probe: actor carries no guest vtable");
        return;
    }
    uintptr_t fn = 0;
    if (!readRelro(vt + kSlot * sizeof(uintptr_t), &fn)) {
        __android_log_print(ANDROID_LOG_INFO, "AlterSelfTest",
                            "getPos probe: Actor:%zu unreadable", kSlot);
        return;
    }
    uintptr_t hopOff = 0;
    if (!decodeHopGetter(fn, &hopOff)) {
        __android_log_print(ANDROID_LOG_INFO, "AlterSelfTest",
                            "getPos probe: Actor:%zu fn=%p is not a hop getter, not called",
                            kSlot, reinterpret_cast<void *>(fn));
        return;
    }
    typedef Float3Ret (*GetPosFn)(void *);
    const Float3Ret r = reinterpret_cast<GetPosFn>(fn)(reinterpret_cast<void *>(actor));
    const float dx = r.x - known.x, dy = r.y - known.y, dz = r.z - known.z;
    __android_log_print(ANDROID_LOG_INFO, "AlterSelfTest",
                        "getPos probe: Actor:%zu hops this+0x%x -> (%.3f,%.3f,%.3f) "
                        "vs movement path (%.3f,%.3f,%.3f) diff %.5f/%.5f/%.5f -> %s",
                        kSlot, (unsigned) hopOff, (double) r.x, (double) r.y, (double) r.z,
                        (double) known.x, (double) known.y, (double) known.z,
                        (double) dx, (double) dy, (double) dz,
                        (dx == 0.f && dy == 0.f && dz == 0.f)
                            ? "IDENTICAL, pin vtable.actorGetPos = Actor:13" : "DIFFERS");
#endif
}

// The second source for the walk above: the game's own getter, reached through
// the off.actorGetHealth pin. The pin is only trusted after its own words decode
// as this getter - prologue, a load of the very registry offset the walk was
// given, the immediate pair for the *pinned* hash (so the check ties the pin to
// the pin rather than to a second copy of my transcription), then a float LOAD
// 0xC0 bytes in - not a store, which is all that separates getHealth from its
// setHealth twin. Only after that is it called, and by then the walk has proved
// the component exists, so the getter's terminate path is unreachable.
void probeHealthGetter(uintptr_t actor, float walked) {
#if !defined(__aarch64__)
    (void)actor; (void)walked;
    __android_log_print(ANDROID_LOG_INFO, "AlterSelfTest",
                        "health probe: aarch64-only, skipped on this ABI");
    return;
#else
    uintptr_t hash = 0, ctxOff = 0;
    if (!dataPin("healthComponentHash", &hash) || !dataPin("actorEntityContextField", &ctxOff))
        return;
    uintptr_t rva = 0;
    const HookRules::Rule *rule = GameSdk::rule();
    if (rule == nullptr || !GameSdk::ruleActive() || actor == 0 ||
        !rule->lookupOffset(std::string("actorGetHealth"), &rva))
        return;
    const uintptr_t fn = GameSdk::guestBase() + rva;
    if (!inExec(fn)) return;
    uint32_t w[51] = {};
    memcpy(w, reinterpret_cast<const void *>(fn), sizeof(w));
    const bool shape =
        ctxOff % 8 == 0 &&
        w[0] == 0xA9BF7BFDu && w[1] == 0x910003FDu &&
        (w[2] & 0xFFC0FFE0u) == (0xF9400000u | (((ctxOff >> 3) & 0xFFFu) << 10)) &&
        (w[3] & 0xFFE00000u) == 0x52800000u && ((w[3] >> 5) & 0xFFFFu) == (hash & 0xFFFFu) &&
        (w[4] & 0xFFE00000u) == 0x72A00000u && ((w[4] >> 5) & 0xFFFFu) == ((hash >> 16) & 0xFFFFu) &&
        (w[48] & 0xFFF0FE00u) == 0xBC606800u &&
        w[49] == 0xA8C17BFDu && w[50] == 0xD65F03C0u;
    if (!shape) {
        __android_log_print(ANDROID_LOG_INFO, "AlterSelfTest",
                            "health probe: off.actorGetHealth=0x%lx does not decode as the "
                            "health getter (%08x %08x %08x %08x %08x / %08x), not called",
                            (unsigned long) rva, w[0], w[1], w[2], w[3], w[4], w[48]);
        return;
    }
    typedef float (*GetHealthFn)(void *);
    const float got = reinterpret_cast<GetHealthFn>(fn)(reinterpret_cast<void *>(actor));
    __android_log_print(ANDROID_LOG_INFO, "AlterSelfTest",
                        "health probe: getter 0x%lx -> %.3f, entt walk -> %.3f -> %s",
                        (unsigned long) rva, (double) got, (double) walked,
                        (got == walked) ? "IDENTICAL" : "DIFFERS");
#endif
}


}  // namespace mcpe