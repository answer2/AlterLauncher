#include "GameSDK.h"

#include <cmath>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

#include <android/log.h>

#include <mcpe/Reflection.h>
#include <util/HookRules.h>
#include <util/RttiLookup.h>
#include <util/SigScanner.h>

// GameSDK.h explains the ladders; this file is their implementation. The rule
// data and the RTTI tables are injected by main.cpp (which owns them) rather
// than duplicated here, so there is exactly one source of truth per value.
namespace GameSdk {

namespace {

constexpr const char *kGuestLib = "libminecraftpe.so";

struct State {
    uintptr_t base = 0;
    const HookRules::Rule *rule = nullptr;
    bool hasRule = false;
    uintptr_t ciVtable = 0;
    size_t ciSlots = 0;
    uintptr_t gdVtable = 0;
    // Every RTTI-validated GuiData table, primary first. The live instance's
    // own vtable pointer may be any of them (base-subobject variants).
    std::vector<uintptr_t> gdVtables;
    uintptr_t clientInstance = 0;
    uintptr_t minecraftGame = 0;
    uintptr_t guiData = 0;
    // Address of the real GuiData::addMessage once a hook or the rule installs it.
    uintptr_t addMessageRoute = 0;
    // Provenance for describe(): none / field / vcall / hook / scan.
    const char *mgSource = "-";
    const char *gdSource = "-";
    // The in-world witness from the HUD screen hooks (see isInGame).
    bool hudVisible = false;
    size_t pumps = 0;
} state;

bool inExec(uintptr_t addr) {
    std::vector<RttiLookup::Region> regions = RttiLookup::regionsOf(kGuestLib);
    return RttiLookup::inRegions(regions, addr, true);
}

// A C++ object's vtable pointer lands in .data.rel.ro, which the linker
// relocated and which maps as a *data* segment of the library, not an exec
// one. On this build the whole library is one contiguous r-xp text mapping and
// the vtables sit just past its end, so inExec() rejects every real vtable -
// that is why the field/getter ladders returned 0 for MinecraftGame and GuiData
// even though CI+0x178 / CI+0x660 hold live objects. Any address that belongs
// to *some* mapping of the guest library is a valid vtable home.
bool inGuestLib(uintptr_t addr) {
    std::vector<RttiLookup::Region> regions = RttiLookup::regionsOf(kGuestLib);
    return RttiLookup::inRegions(regions, addr, false);
}

// A pointer is a plausible object when it is 8-aligned and its first word is a
// vtable inside the guest library's data mapping.
bool looksLikeCppObject(uintptr_t cand) {
    if (cand == 0 || (cand & 0x7) != 0) return false;
    uintptr_t vt = 0;
    memcpy(&vt, reinterpret_cast<const void *>(cand), sizeof(vt));
    return inGuestLib(vt);
}

// A qword read from `object + offset` that points at something whose first word
// is an exec-mapping pointer: a plausible C++ object. Every field-read path
// starts here before checking anything more specific.
uintptr_t readObjectField(uintptr_t object, uintptr_t offset) {
    if (object == 0) return 0;
    uintptr_t value = 0;
    memcpy(&value, reinterpret_cast<const void *>(object + offset), sizeof(value));
    if (value == 0 || (value & 0x7) != 0) return 0;
    uintptr_t vt = 0;
    memcpy(&vt, reinterpret_cast<const void *>(value), sizeof(vt));
    if (!inGuestLib(vt)) return 0;
    return value;
}

// Calls a validated virtual getter that takes only `this` and returns a pointer.
typedef uintptr_t (*GetterFn)(void *);

uintptr_t callGetter(uintptr_t object, const char *key, const char *what) {
    if (object == 0 || !state.hasRule) return 0;
    std::string cls;
    size_t slot = 0;
    if (!state.rule->lookupVtable(std::string(key), &cls, &slot)) return 0;
    if (cls != "ClientInstance") {
        __android_log_print(ANDROID_LOG_WARN, "Alternative_Core",
                            "GameSdk: %s names class '%s', only ClientInstance is callable "
                            "here", what, cls.c_str());
        return 0;
    }
    if (state.ciSlots <= slot) {
        __android_log_print(ANDROID_LOG_WARN, "Alternative_Core",
                            "GameSdk: %s slot %zu out of range (%zu slots)", what, slot,
                            state.ciSlots);
        return 0;
    }
    uintptr_t fn = RttiLookup::slotAt(state.ciVtable, slot);
    if (!inExec(fn)) {
        __android_log_print(ANDROID_LOG_WARN, "Alternative_Core",
                            "GameSdk: %s slot %zu (0x%lx) is not executable", what, slot,
                            (unsigned long) fn);
        return 0;
    }
    GetterFn getter = reinterpret_cast<GetterFn>(fn);
    return getter(reinterpret_cast<void *>(object));
}

// Java-side requests park here; ClientInstance::update's next tick pumps them.
std::mutex g_pendingMutex;
std::vector<std::string> g_pending;

// True when `candidate`'s own vtable pointer matches ANY RTTI-validated
// GuiData table — the strongest cheap identity check available. The primary
// table alone is not enough: on 1.26.x the game's live GuiData carries one of
// the aux/subobject tables, so a strict primary-only check rejected a pointer
// the game itself had just used as `this`.
bool looksLikeGuiData(uintptr_t candidate) {
    if (candidate == 0) return false;
    uintptr_t vt = 0;
    memcpy(&vt, reinterpret_cast<const void *>(candidate), sizeof(vt));
    if (!state.gdVtables.empty()) {
        for (size_t i = 0; i < state.gdVtables.size(); i++)
            if (vt == state.gdVtables[i]) return true;
        return false;
    }
    return state.gdVtable != 0 && vt == state.gdVtable;
}

// Writable mappings of this process, parsed once. GuiData is non-polymorphic,
// so its identity is a data-layout test — and a data-layout test must not
// dereference an unmapped candidate. Every guest heap pointer is our own
// pointer here (we run inside the game process), so /proc/self/maps bounds it.
bool inWritable(uintptr_t addr, size_t len) {
    // Maps are parsed lazily and RE-parsed whenever a lookup misses: the heap
    // (scudo) grows while the game loads, so a snapshot taken at the first
    // probe permanently rejects addresses the allocator hands out later. That
    // stale-cache bug made the whole ci+0xDE0 sub-object ladder read as zeros.
    // Hits are cached; a miss re-parses once, and an address that STILL misses
    // is remembered so per-tick retries never re-read maps for a bad pointer.
    struct WRange { uintptr_t s, e; };
    // Scudo tags the top byte of every heap pointer (0xb4000070...). The
    // hardware ignores that byte on access (TBI) but /proc/self/maps lists the
    // UNTAGGED range, so a raw compare never matches and every bounded read
    // silently fails. Untag before checking.
    addr &= 0x00ffffffffffffffULL;
    static std::vector<WRange> ranges;
    static std::vector<uintptr_t> bad;
    static uint64_t generation = 0;
    static uint64_t badGen = 0;
    for (size_t i = 0; i < ranges.size(); i++)
        if (addr >= ranges[i].s && addr + len <= ranges[i].e) return true;
    if (badGen != generation) { bad.clear(); badGen = generation; }
    for (size_t i = 0; i < bad.size(); i++)
        if (bad[i] == addr) return false;
    ranges.clear();
    std::ifstream maps("/proc/self/maps");
    std::string line;
    while (std::getline(maps, line)) {
        uintptr_t s = 0, e = 0;
        if (sscanf(line.c_str(), "%lx-%lx", &s, &e) != 2) continue;
        if (line.find(" rw-") != std::string::npos ||
            line.find("rwxp") != std::string::npos)
            ranges.push_back(WRange{s, e});
    }
    generation++;
    for (size_t i = 0; i < ranges.size(); i++)
        if (addr >= ranges[i].s && addr + len <= ranges[i].e) return true;
    bad.push_back(addr);
    return false;
}

// GuiData identity WITHOUT a chat sighting. The live GuiData object is an
// inline member of ClientInstance (1.26.45.1: ci+0xDE0) whose own first word is
// NOT a vtable from the RTTI 'GuiData' set - so looksLikeGuiData can never
// certify it and the float heuristic alone was too weak. What DOES pin it are
// the fields the game's own chat functions dereference on 'this':
//   addChatMessage wrapper: ldr x0,[x0,#0xa0]; ldr x8,[x0]; ldr x8,[x8,#0x580]; blr
//       -> +0xA0 points at a polymorphic sub-object (vtable inside the guest lib)
//   sendClientMessage:      ldp x20,x8,[x0,#0x188]; cmp x8,x20
//       -> +0x188/+0x190 is a begin/end pointer pair (message deque)
//   BT GuiDataAddMessage:   ldp x8,x9,[x0,#0x140]
//       -> +0x140/+0x148 is a second begin/end pair
// A candidate is GuiData when at least one anchor holds, every read bounded by
// inWritable, and the legacy resolution-float heuristic kept as a last anchor.
bool plausibleGuiData(uintptr_t candidate) {
    if (candidate == 0 || (candidate & 0x7) != 0) return false;
    if (!inWritable(candidate, 0x1B0)) return false;

    // Anchor 1: polymorphic pane sub-object at +0xA0.
    uintptr_t pane = 0;
    memcpy(&pane, reinterpret_cast<const void *>(candidate + 0xA0), sizeof(pane));
    if (pane != 0 && (pane & 0x7) == 0 && inWritable(pane, sizeof(uintptr_t))) {
        uintptr_t vt = 0;
        memcpy(&vt, reinterpret_cast<const void *>(pane), sizeof(vt));
        if (vt != 0 && (vt & 0x7) == 0 && inGuestLib(vt)) return true;
    }

    // Anchor 2: begin/end deque pair - both plausible heap pointers, ordered.
    auto dequeOk = [](uintptr_t base) {
        uintptr_t b = 0, e = 0;
        memcpy(&b, reinterpret_cast<const void *>(base), sizeof(b));
        memcpy(&e, reinterpret_cast<const void *>(base + 8), sizeof(e));
        if (b == 0 || (b & 0x7) != 0 || e < b || (e - b) > 0x10000) return false;
        if (!inWritable(b, sizeof(uintptr_t))) return false;
        if (e != b && !inWritable(e - 8, sizeof(uintptr_t))) return false;
        return true;
    };
    if (dequeOk(candidate + 0x188) && dequeOk(candidate + 0x140)) return true;

    // Anchor 3 (legacy): three resolution vec2s at +0x40..+0x58.
    float v[6];
    memcpy(v, reinterpret_cast<const void *>(candidate + 0x40), sizeof(v));
    auto ok = [](float f) { return std::isfinite(f) && f >= 100.f && f <= 20000.f; };
    return (ok(v[0]) && ok(v[1])) || (ok(v[2]) && ok(v[3])) || (ok(v[4]) && ok(v[5]));
}

bool isGuiData(uintptr_t candidate) {
    return looksLikeGuiData(candidate) || plausibleGuiData(candidate);
}

// --- read-only structural discovery of the inline GuiData sub-object --------
// plausibleGuiData is a WEAK test (one anchor is enough) because it validates a
// pointer we already trust. For a blind scan over ClientInstance's bytes we need
// the opposite: a candidate is GuiData only when its independent anchors hold
// TOGETHER - a polymorphic pane sub-object at +0xA0 whose vtable lives in the
// guest image, BOTH begin/end deque pairs (+0x140 and +0x188), and a resolution
// vec2 inside +0x40..+0x58 carrying plausible screen dimensions. That exact
// conjunction is the 1.26.x HUD layout (verified at ci+0xDE0 on 1.26.45.1) and
// is specific enough that a single surviving match across the whole object is
// meaningful. Strictly read-only: every access is bounded by inWritable and no
// guest function is ever called.
static bool guiDequeAt(uintptr_t base) {
    uintptr_t b = 0, e = 0;
    if (!inWritable(base, 2 * sizeof(uintptr_t))) return false;
    memcpy(&b, reinterpret_cast<const void *>(base), sizeof(b));
    memcpy(&e, reinterpret_cast<const void *>(base + 8), sizeof(e));
    if (b == 0 || (b & 0x7) != 0 || e < b || (e - b) > 0x10000) return false;
    if (!inWritable(b, sizeof(uintptr_t))) return false;
    if (e != b && !inWritable(e - 8, sizeof(uintptr_t))) return false;
    return true;
}

static bool guiStructuralAnchors(uintptr_t cand) {
    if (cand == 0 || (cand & 0x7) != 0 || !inWritable(cand, 0x1B0)) return false;

    uintptr_t pane = 0;
    memcpy(&pane, reinterpret_cast<const void *>(cand + 0xA0), sizeof(pane));
    if (pane == 0 || (pane & 0x7) != 0 || !inWritable(pane, sizeof(uintptr_t))) return false;
    uintptr_t vt = 0;
    memcpy(&vt, reinterpret_cast<const void *>(pane), sizeof(vt));
    if (vt == 0 || (vt & 0x7) != 0 || !inGuestLib(vt)) return false;

    if (!guiDequeAt(cand + 0x140) || !guiDequeAt(cand + 0x188)) return false;

    float v[6];
    if (!inWritable(cand + 0x40, sizeof(v))) return false;
    memcpy(v, reinterpret_cast<const void *>(cand + 0x40), sizeof(v));
    auto ok = [](float f) { return std::isfinite(f) && f >= 100.f && f <= 20000.f; };
    if (!((ok(v[0]) && ok(v[1])) || (ok(v[2]) && ok(v[3])) || (ok(v[4]) && ok(v[5]))))
        return false;
    return true;
}

// Scan a bounded window of ClientInstance for exactly one structurally-GuiData
// region. More than one match is treated as ambiguous and rejected (fail closed),
// so this can only ever replace the pinned sub-object path, never guess between
// two candidates.
static uintptr_t scanGuiDataInline(uintptr_t ci) {
    uintptr_t found = 0;
    int matches = 0;
    for (uintptr_t off = 0x80; off <= 0x2000; off += 8) {
        if (guiStructuralAnchors(ci + off)) {
            if (++matches == 1) found = ci + off;
        }
    }
    return matches == 1 ? found : 0;
}


// The self argument of the game's OWN last addMessage call — a proven GuiData
// instance even when looksLikeGuiData could not certify it (subobject tables).
uintptr_t g_lastSeenGuiData = 0;

}  // namespace

void init(uintptr_t guestBase) {
    state.base = guestBase;
    __android_log_print(ANDROID_LOG_DEBUG, "Alternative_Core",
                        "GameSdk: initialized with guest base 0x%lx", (unsigned long) guestBase);
}
// The version string lives in a fixed buffer because ModApi hands it out as
// const char* across the mod boundary; copying at set time keeps the pointer
// stable for the process lifetime.
char g_version[64] = {0};

void setVersion(const char *versionName) {
    if (versionName == nullptr) return;
    std::snprintf(g_version, sizeof(g_version), "%s", versionName);
}

const char *version() { return g_version; }

void setRule(const HookRules::Rule *rule, bool hasRule) {
    state.rule = rule;
    state.hasRule = hasRule;
}

void setClientInstanceTables(uintptr_t vtable, size_t slots, uintptr_t guiDataVtable) {
    state.ciVtable = vtable;
    state.ciSlots = slots;
    state.gdVtable = guiDataVtable;
}

void setGuiDataTables(const uintptr_t *tables, size_t count) {
    state.gdVtables.assign(tables, tables + count);
    if (count != 0 && state.gdVtable == 0) state.gdVtable = tables[0];
}

uintptr_t clientInstance() { return state.clientInstance; }

void setClientInstance(uintptr_t ci) {
    if (ci == 0 || state.clientInstance != 0) return;  // first capture wins
    state.clientInstance = ci;
}

// The game's own addMessage call revealed its GuiData: publish it whenever it
// matches ANY validated RTTI table. g_lastSeenGuiData records the sighting even
// when no table certifies it, so deliverMessage can still trust live usage.
void setGuiDataFromHook(uintptr_t gd) {
    if (gd == 0) return;
    g_lastSeenGuiData = gd;
    // Self-calibration: the game just used this gd as a live GuiData 'this'.
    // If its own vtable is not in the RTTI-derived table set (subobject tables
    // the RTTI walk never enumerates), learn it so every static validator -
    // looksLikeGuiData, the member scan - can certify this exact class without
    // another chat sighting.
    {
        uintptr_t vt = 0;
        if (inWritable(gd, sizeof(vt))) {
            memcpy(&vt, reinterpret_cast<const void *>(gd), sizeof(vt));
            if (vt != 0 && (vt & 0x7) == 0 && inGuestLib(vt)) {
                bool known = false;
                for (size_t k = 0; k < state.gdVtables.size(); k++)
                    if (state.gdVtables[k] == vt) { known = true; break; }
                if (!known) {
                    state.gdVtables.push_back(vt);
                    __android_log_print(ANDROID_LOG_INFO, "Alternative_Core",
                                        "GameSdk: learned GuiData vtable 0x%lx from live "
                                        "instance (RTTI set missed it)", (unsigned long) vt);
                }
            }
        }
    }
    if (state.guiData != 0) return;
    // Two proofs of identity, either is enough:
    //   - its own vtable pointer matches a validated RTTI GuiData table, or
    //   - the game itself just dispatched addMessage on it AND we hold a proven
    //     real-addMessage route (the hook bridge): live usage with a matching
    //     callee is as strong as any static check, and covers subobject tables
    //     our RTTI walk never enumerated.
    if (looksLikeGuiData(gd) || state.addMessageRoute != 0) {
        state.guiData = gd;
        state.gdSource = looksLikeGuiData(gd) ? "hook" : "seen";
    }
}

// main.cpp publishes the real (pre-hook) addMessage address once known.
uintptr_t &addMessageRouteRef() { return state.addMessageRoute; }


// The HUD screen hooks publish the in-world witness; both handlers run on the
// game thread, and isInGame() only consults it when there is no pinned predicate.
void setHudVisible(bool visible) { state.hudVisible = visible; }
bool hudVisible() { return state.hudVisible; }

void setMinecraftGameFromHook(uintptr_t mg) {
    if (mg != 0 && state.minecraftGame == 0) {
        state.minecraftGame = mg;
        state.mgSource = "hook";
    }
}

uintptr_t minecraftGame() {
    if (state.minecraftGame != 0) return state.minecraftGame;
    uintptr_t ci = state.clientInstance;
    if (ci == 0) return 0;

    // Ladder step 1: the rule pins a field offset inside ClientInstance.
    uintptr_t rva = 0;
    if (state.hasRule && state.rule->lookupDataOffset(std::string("minecraftGameField"), &rva)) {
        uintptr_t candidate = readObjectField(ci, rva);
        if (candidate != 0) {
            state.minecraftGame = candidate;
            state.mgSource = "field";
            return candidate;
        }
        __android_log_print(ANDROID_LOG_WARN, "Alternative_Core",
                            "GameSdk: dat.minecraftGameField=0x%llx did not yield an object",
                            (unsigned long long) rva);
    }

    // Ladder step 2: the rule authorizes a virtual getter on the RTTI table.
    uintptr_t viaCall = callGetter(ci, "getMinecraftGame", "getMinecraftGame");
    if (viaCall != 0 && inGuestLib(*reinterpret_cast<uintptr_t *>(viaCall))) {
        state.minecraftGame = viaCall;
        state.mgSource = "vcall";
        return viaCall;
    }

    // Ladder step 3: the BedrockTools route — a signature-resolved getter that
    // takes only `this` and returns the pointer. getMinecraftGame is reachable
    // by pattern even where RTTI names nothing; the clientInstanceGetMinecraftGame
    // key carries it. The result is validated as a plausible object (8-aligned,
    // exec-region vtable) before publishing.
    uintptr_t fn = SigScanner::resolve(std::string("clientInstanceGetMinecraftGame"));
    if (fn != 0) {
        uintptr_t viaSig = reinterpret_cast<GetterFn>(fn)(reinterpret_cast<void *>(ci));
        if (viaSig != 0 && (viaSig & 0x7) == 0) {
            uintptr_t vt = 0;
            memcpy(&vt, reinterpret_cast<const void *>(viaSig), sizeof(vt));
            if (inGuestLib(vt)) {
                state.minecraftGame = viaSig;
                state.mgSource = "sig";
                return viaSig;
            }
        }
    }
    return 0;
}

// Sub-object pin re-check without any of the ladder's calls or scans; see the
// header for why this exists (GuiData is constructed late inside CI on 1.26.x).
void retryPinnedSubobject() {
    if (state.guiData != 0) return;
    uintptr_t ci = state.clientInstance;
    uintptr_t subOff = 0;
    if (ci == 0 || !state.hasRule) return;
    if (!state.rule->lookupDataOffset(std::string("guiDataSubobject"), &subOff)) return;
    if (isGuiData(ci + subOff)) {
        state.guiData = ci + subOff;
        state.gdSource = "sub";
        __android_log_print(ANDROID_LOG_INFO, "Alternative_Core",
                            "GameSdk: GuiData %p appeared at inline sub-object "
                            "ClientInstance+0x%llx (pump %zu)",
                            reinterpret_cast<void *>(state.guiData),
                            (unsigned long long) subOff, state.pumps);
    }
}

uintptr_t guiData() {
    if (state.guiData != 0) return state.guiData;
    uintptr_t ci = state.clientInstance;
    if (ci == 0) return 0;

    // Ladder step 1: virtual getter authorized by the rule, validated against
    // the RTTI GuiData table before publishing.
    uintptr_t viaCall = callGetter(ci, "getGuiData", "getGuiData");
    if (isGuiData(viaCall)) {
        state.guiData = viaCall;
        state.gdSource = "vcall";
        return viaCall;
    }

    // Ladder step 2: pinned field offset, same validation.
    uintptr_t rva = 0;
    if (state.hasRule && state.rule->lookupDataOffset(std::string("guiDataField"), &rva)) {
        uintptr_t candidate = 0;
        if (inWritable(ci + rva, sizeof(candidate)))
            memcpy(&candidate, reinterpret_cast<const void *>(ci + rva), sizeof(candidate));
        if (isGuiData(candidate)) {
            state.guiData = candidate;
            state.gdSource = "field";
            __android_log_print(ANDROID_LOG_DEBUG, "Alternative_Core",
                                "GameSdk: GuiData %p from pinned field ClientInstance+0x%llx",
                                reinterpret_cast<void *>(candidate), (unsigned long long) rva);
            return candidate;
        }
    }

    // Ladder step 2.5: the signature-resolved getter (BedrockTools' method for
    // everything RTTI cannot name). clientInstanceGetGuiData returns GuiData*
    // directly; validating the result against the RTTI tables keeps the same
    // fail-closed rule as every other rung.
    uintptr_t gdFn = SigScanner::resolve(std::string("clientInstanceGetGuiData"));
    if (gdFn != 0) {
        uintptr_t viaSig = reinterpret_cast<GetterFn>(gdFn)(reinterpret_cast<void *>(ci));
        if (isGuiData(viaSig)) {
            state.guiData = viaSig;
            state.gdSource = "sig";
            __android_log_print(ANDROID_LOG_DEBUG, "Alternative_Core",
                                "GameSdk: GuiData %p from the pattern-resolved getter",
                                reinterpret_cast<void *>(viaSig));
            return viaSig;
        }
    }


    // Ladder step 2.6: inline sub-object pin. On 1.26.45.1 GuiData is NOT a
    // pointer member at all - it is constructed in place inside ClientInstance
    // (the game's own addChatMessage 'this' sits at ci+0xDE0) - so no field or
    // scan rung can ever hit it. The rule pins the sub-object offset and the
    // structural validator certifies it, cold, without a chat sighting.
    uintptr_t subOff = 0;
    if (state.hasRule && state.rule->lookupDataOffset(std::string("guiDataSubobject"), &subOff)) {
        uintptr_t candidate = ci + subOff;
        // One-shot anchor dump: when the structural validator rejects the pinned
        // sub-object, log exactly what each anchor sees, bounded by inWritable.
        // Without this the rejection is a black box (which offset is wrong?).
        static size_t s_lastDiagPump = 0;
        if (state.pumps >= s_lastDiagPump + 240 && !isGuiData(candidate)) {
            s_lastDiagPump = state.pumps;
            auto rd = [](uintptr_t a) -> uintptr_t {
                uintptr_t v = 0;
                if (a && inWritable(a, sizeof(v))) memcpy(&v, reinterpret_cast<const void *>(a), sizeof(v));
                return v;
            };
            uintptr_t vt = rd(candidate);
            uintptr_t pane = rd(candidate + 0xA0);
            uintptr_t panevt = pane ? rd(pane) : 0;
            uintptr_t b1 = rd(candidate + 0x140), e1 = rd(candidate + 0x148);
            uintptr_t b2 = rd(candidate + 0x188), e2 = rd(candidate + 0x190);
            float fv[6] = {0};
            if (inWritable(candidate + 0x40, sizeof(fv)))
                memcpy(fv, reinterpret_cast<const void *>(candidate + 0x40), sizeof(fv));
            __android_log_print(ANDROID_LOG_INFO, "Alternative_Core",
                "GameSdk SUBDIAG p=%zu gd=%p vt=%lx(inlib=%d) pane=%p panevt=%lx(inlib=%d) "
                "dq140=%lx/%lx dq188=%lx/%lx f=[%.1f %.1f | %.1f %.1f | %.1f %.1f]",
                state.pumps, reinterpret_cast<void *>(candidate), (unsigned long)vt, inGuestLib(vt)?1:0,
                reinterpret_cast<void *>(pane), (unsigned long)panevt, inGuestLib(panevt)?1:0,
                (unsigned long)b1,(unsigned long)e1,(unsigned long)b2,(unsigned long)e2,
                fv[0],fv[1],fv[2],fv[3],fv[4],fv[5]);
        }
        if (isGuiData(candidate)) {
            state.guiData = candidate;
            state.gdSource = "sub";
            __android_log_print(ANDROID_LOG_DEBUG, "Alternative_Core",
                                "GameSdk: GuiData %p is the inline sub-object ClientInstance+0x%llx",
                                reinterpret_cast<void *>(candidate), (unsigned long long) subOff);
            return candidate;
        }
    }
    // REMOVED "empirical getter probe": it blindly CALLED the ClientInstance
    // vtable slots {101,219,223,224,249,352..355} with a GetterFn trampoline and
    // only then checked plausibleGuiData. A call is not a read: on any build
    // whose GuiData is not pinned by one of the deterministic rungs above, slot
    // 101 (the reference's getSkinRepositoryClientInterface, NOT a GuiData
    // getter) was invoked through the wrong ABI and segfaulted the host - fault
    // reproduced on-device for the 1.26.5x instance. inExec/plausibility guards
    // cannot catch it because the crash happens inside the call, before any
    // result exists. GuiData is now acquired ONLY from a rule pin (field /
    // inline sub-object), a validated signature, or the read-only RTTI member
    // scan below; if none certifies it, the ladder returns 0 and the feature
    // that needs it fails closed. Nothing guessed, and never a blind call.
    //
    // Ladder step 3: read-only member scan of the captured object against every
    // known GuiData table — works with no pin at all, which is why the reference
    // offsets never need hardcoding.
    for (size_t t = 0; t < state.gdVtables.size(); t++) {
        uintptr_t member = RttiLookup::findMemberByVtable(ci, state.gdVtables[t], 0x20000);
        if (member != 0) {
            state.guiData = member;
            state.gdSource = "scan";
            __android_log_print(ANDROID_LOG_DEBUG, "Alternative_Core",
                                "GameSdk: discovered GuiData at ClientInstance+0x%llx via table "
                                "%zu of %zu (pin it as dat.guiDataField to make future builds "
                                "explicit)",
                                (unsigned long long)(member - ci), t, state.gdVtables.size());
            return member;
        }
    }
    if (state.gdVtable != 0 && state.gdVtables.empty()) {
        uintptr_t member = RttiLookup::findMemberByVtable(ci, state.gdVtable, 0x20000);
        if (member != 0) {
            state.guiData = member;
            state.gdSource = "scan";
            return member;
        }
    }

    // Ladder step 3.5: read-only STRUCTURAL scan for the inline sub-object. The
    // 1.26.5x instances have no guiDataSubobject pin yet and their RTTI set does
    // not always carry the live object vptr, so step 3 can miss while GuiData is
    // plainly present in ClientInstance. This finds it by its layout alone -
    // unique match required, no call - and the discovered offset is logged so a
    // future rule block can pin dat.guiDataSubobject explicitly.
    {
        uintptr_t sub = scanGuiDataInline(ci);
        if (sub != 0) {
            state.guiData = sub;
            state.gdSource = "struct";
            __android_log_print(ANDROID_LOG_INFO, "Alternative_Core",
                                "GameSdk: GuiData %p via read-only structural scan at "
                                "ClientInstance+0x%llx (pin it as dat.guiDataSubobject)",
                                reinterpret_cast<void *>(sub),
                                (unsigned long long)(sub - ci));
            return sub;
        }
    }

    // The strict ladder found nothing. One-shot diagnostic through the shared
    // Reflection module: enumerate plausible member objects of ClientInstance
    // whose vtable matches ANY known GuiData table or CI's own table, so the
    // log names real classes without a second maps parser here. A wrong pin
    // costs the dump, never the game.
    static bool dumped = false;
    if (!dumped) {
        dumped = true;
        std::vector<uintptr_t> anyTables;
        for (size_t i = 0; i < state.gdVtables.size(); i++)
            anyTables.push_back(state.gdVtables[i]);
        if (state.ciVtable != 0) anyTables.push_back(state.ciVtable);
        std::vector<uintptr_t> members = Reflection::findAllMembers(ci, anyTables, 0x2000);
        __android_log_print(ANDROID_LOG_INFO, "Alternative_Core",
                            "GameSdk DIAG ci=%p tables=%zu members=%zu pumps=%zu",
                            reinterpret_cast<void *>(ci), anyTables.size(), members.size(),
                            state.pumps);
        for (size_t i = 0; i < members.size() && i < 60; i++) {
            uintptr_t vt = 0;
            memcpy(&vt, reinterpret_cast<const void *>(members[i]), sizeof(vt));
            __android_log_print(ANDROID_LOG_INFO, "Alternative_Core",
                                "GameSdk DIAG: ci+0x%x -> obj %p vt %lx",
                                (unsigned)(members[i] - ci), reinterpret_cast<void *>(members[i]),
                                (unsigned long) vt);
        }
    }
    return 0;
}


bool isInGame() {
    uintptr_t ci = state.clientInstance;
    // A pinned ClientInstance::isInGame is the authoritative answer: it is the
    // game's own predicate. Without the pin, fall back to the screen witness —
    // the HudScreen lifetime hooks (Levi's ARM64 patterns, see main.cpp) say the
    // HUD object exists, and in this game the HUD is constructed exactly when a
    // world finishes loading and destroyed when it leaves. That is derived from
    // observed game behaviour rather than guessed, but it is weaker than the
    // pin, so it is only used when there is no pin.
    std::string cls;
    size_t slot = 0;
    if (ci != 0 && state.hasRule &&
        state.rule->lookupVtable(std::string("isInGame"), &cls, &slot) &&
        cls == "ClientInstance" && state.ciSlots > slot) {
        uintptr_t fn = RttiLookup::slotAt(state.ciVtable, slot);
        if (inExec(fn)) {
            typedef bool (*BoolGetter)(void *);
            return reinterpret_cast<BoolGetter>(fn)(reinterpret_cast<void *>(ci));
        }
    }
    return state.hudVisible;
}

// The chat route: the game's own TYPE-1 chat wrapper (pinned as
// off.guiDataAddMessage, 0x923ab54 on 1.26.45.1), reached through the SDK layer
// rather than a blind vtable index. The wrapper takes the real GuiData pointer,
// five std::string references (sender, message, three trailing metadata strings
// the vanilla callers always fill) and a float duration, then constructs the
// GuiMessage and pushes it onto GuiData's deque itself — so string lifetime and
// the deque bookkeeping stay entirely inside game code. Two ways in, tried in
// order:

//   1. the hooked wrapper (its original-function bridge is a plain callable
//      with the game's own signature) on the discovered GuiData instance;
//   2. no hook installed yet -> call base+off.guiDataAddMessage directly on the
//      instance, which is what a mod loader would do.

// Both need guiData() to have resolved; otherwise this reports and returns
// false. The trailing strings are passed as real EMPTY std::string objects:
// the GuiMessage ctor dereferences every string argument unconditionally, so
// nullptr is never an acceptable stand-in (that was the 1.26.45.1 crash).
bool deliverMessage(const std::string &message) {
    uintptr_t gd = guiData();
    if (gd == 0 && g_lastSeenGuiData != 0 && state.addMessageRoute != 0) {
        // The strict ladder failed (GuiData is a base of ClientInstance, so its
        // pointer may sit at a subobject offset the scan cannot express), but
        // the game itself just called addMessage on this very object. Usage is
        // the strongest proof there is — trust it and cache.
        gd = g_lastSeenGuiData;
        state.guiData = gd;
        state.gdSource = "seen";
        __android_log_print(ANDROID_LOG_DEBUG, "Alternative_Core",
                            "GameSdk: routing via game-observed GuiData %p (scan could not pin it)",
                            reinterpret_cast<void *>(gd));
    }
    if (gd == 0) {
        __android_log_print(ANDROID_LOG_WARN, "Alternative_Core",
                            "GameSdk: no GuiData yet (ci=%p)",
                            reinterpret_cast<void *>(state.clientInstance));
        return false;
    }
    typedef void (*AddMessage)(void *, const std::string &, const std::string &,
                               const std::string &, const std::string &,
                               const std::string &, float);
    AddMessage fn = nullptr;
    if (state.addMessageRoute != 0) {
        fn = reinterpret_cast<AddMessage>(state.addMessageRoute);
    } else if (state.hasRule) {
        uintptr_t rva = 0;
        if (state.rule->lookupOffset(std::string("guiDataAddMessage"), &rva) && state.base != 0)
            fn = reinterpret_cast<AddMessage>(state.base + rva);
    }
    if (fn == nullptr) {
        // Last resort, and the reason SigScanner exists: the pattern for this
        // function resolves an address we can CALL even though shadowhook can
        // never PATCH it (island dead band). No hook ever fired, so there is no
        // bridge — but the body itself is a plain callable with the game's own
        // signature. Cached scan, one log line on the first miss per build.
        uintptr_t sig = SigScanner::resolve(std::string("guiDataAddMessage"));
        if (sig != 0) {
            state.addMessageRoute = sig;  // publish so the ladder stops re-resolving
            fn = reinterpret_cast<AddMessage>(sig);
            __android_log_print(ANDROID_LOG_DEBUG, "Alternative_Core",
                                "GameSdk: calling addMessage straight at 0x%lx (pattern match)",
                                (unsigned long) sig);
        }
    }
    if (fn == nullptr) {
        __android_log_print(ANDROID_LOG_WARN, "Alternative_Core",
                            "GameSdk: no route to GuiData::addMessage");
        return false;
    }
    static const std::string empty;
    fn(reinterpret_cast<void *>(gd), empty, message, empty, empty, empty, 5.0f);
    return true;
}

// Accessors for the typed facade (mcpe/ClassTree.h): same rule, same base,
// same identity — one source of truth per value.
const HookRules::Rule *rule() { return state.rule; }
// Whether a game-hooks.ini rule is in charge (vs compiled-in defaults).
bool ruleActive() { return state.hasRule; }

uintptr_t guestBase() { return state.base; }
const char *guiDataSource() { return state.guiData != 0 ? state.gdSource : "-"; }

void describe(char *buffer, size_t capacity) {
    // Refresh the ladder so the snapshot is live, not whatever a prior call
    // happened to cache. Both are idempotent (return early once published).
    minecraftGame();
    guiData();
    snprintf(buffer, capacity, "sdk ci=0x%lx mg=0x%lx(%s) gd=0x%lx(%s) inGame=%d",
             (unsigned long) state.clientInstance,
             (unsigned long) state.minecraftGame, state.mgSource,
             (unsigned long) state.guiData, state.gdSource,
             isInGame() ? 1 : 0);
}

bool requestMessage(const std::string &message) {
    if (state.clientInstance == 0) return false;  // no game thread will ever pump this
    std::lock_guard<std::mutex> guard(g_pendingMutex);
    g_pending.push_back(message);
    return true;
}

// How many times the game thread has pumped the queue: proof the update hook
// is live. The one-shot field dump waits for a healthy tick rate so it fires
// in-world, not on the title screen.
size_t pumpCount() { return state.pumps; }

void pumpMessages() {
    // Called from the update hook on the game thread. Swap the queue out under
    // the lock, deliver outside it: addMessage may itself trigger hooks that
    // re-enter requestMessage.
    state.pumps++;
    if ((state.pumps % 120) == 0) retryPinnedSubobject();  // one per game-thread tick, queued or not
    std::vector<std::string> batch;
    {
        std::lock_guard<std::mutex> guard(g_pendingMutex);
        if (g_pending.empty()) return;
        batch.swap(g_pending);
    }
    for (size_t i = 0; i < batch.size(); i++) deliverMessage(batch[i]);
}

void pumpFromAddMessageHook() {
    // Called by main.cpp's addMessage hook right after it publishes the game's
    // own GuiData. The update hook pumps every frame anyway; this only matters
    // for messages queued while guiData() could not yet resolve — they go out
    // on the first chat line the game itself renders.
    pumpMessages();
}

}  // namespace GameSdk