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
    static std::vector<std::pair<uintptr_t, uintptr_t>> ranges;
    static bool loaded = false;
    if (!loaded) {
        loaded = true;
        std::ifstream maps("/proc/self/maps");
        std::string line;
        while (std::getline(maps, line)) {
            uintptr_t s = 0, e = 0;
            if (sscanf(line.c_str(), "%lx-%lx", &s, &e) != 2) continue;
            if (line.find(" rw-") != std::string::npos ||
                line.find("rwxp") != std::string::npos)
                ranges.push_back(std::make_pair(s, e));
        }
    }
    for (size_t i = 0; i < ranges.size(); i++)
        if (addr >= ranges[i].first && addr + len <= ranges[i].second) return true;
    return false;
}

// GuiData on 1.26.x carries no vtable, so looksLikeGuiData can never certify it
// — this is the whole reason the ladder returned 0 in-world. Its stable,
// version-portable signature is the three resolution vec2s the game keeps for
// layout: real / rounded / scaled screen size at +0x40, +0x48, +0x50. A
// candidate is GuiData when it is a heap pointer whose resolution floats are
// finite and plausible for a display, checked without any vtable assumption.
bool plausibleGuiData(uintptr_t candidate) {
    if (candidate == 0 || (candidate & 0x7) != 0) return false;
    if (!inWritable(candidate, 0x58)) return false;
    float v[6];
    memcpy(v, reinterpret_cast<const void *>(candidate + 0x40), sizeof(v));
    auto ok = [](float f) { return std::isfinite(f) && f >= 100.f && f <= 20000.f; };
    return (ok(v[0]) && ok(v[1])) || (ok(v[2]) && ok(v[3])) || (ok(v[4]) && ok(v[5]));
}

// The combined GuiData identity test: a real vtable match (polymorphic builds)
// OR the non-polymorphic resolution signature.
bool isGuiData(uintptr_t candidate) {
    return looksLikeGuiData(candidate) || plausibleGuiData(candidate);
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


    // Ladder step 2.7: empirical getter probe. No RTTI table or signature names
    // GuiData (non-polymorphic), but it is reachable as a ClientInstance field
    // behind a plain 'ldr x0,[x0,#off]; ret' getter. The offline .so analysis
    // (RTTI + getter scan of all 436 CI slots) flagged these slots; the winner
    // is logged with its slot so it can be pinned as
    // vtable.clientInstanceGetGuiData / dat.guiDataField.
    {
        static const size_t kCandidateSlots[] = {101, 219, 223, 224, 249, 352, 353, 354, 355};
        for (size_t i = 0; i < sizeof(kCandidateSlots) / sizeof(size_t); i++) {
            size_t slot = kCandidateSlots[i];
            if (state.ciSlots <= slot) continue;
            uintptr_t fn = RttiLookup::slotAt(state.ciVtable, slot);
            if (!inExec(fn)) continue;
            uintptr_t got = reinterpret_cast<GetterFn>(fn)(reinterpret_cast<void *>(ci));
            if (plausibleGuiData(got)) {
                state.guiData = got;
                state.gdSource = "probe";
                __android_log_print(ANDROID_LOG_INFO, "Alternative_Core",
                                    "GameSdk: GuiData %p via ClientInstance getter SLOT %zu "
                                    "(pin vtable.clientInstanceGetGuiData=ClientInstance:%zu)",
                                    reinterpret_cast<void *>(got), slot, slot);
                return got;
            }
        }
    }

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
    if (ci == 0 || !state.hasRule) return false;
    std::string cls;
    size_t slot = 0;
    if (!state.rule->lookupVtable(std::string("isInGame"), &cls, &slot)) return false;
    if (cls != "ClientInstance" || state.ciSlots <= slot) return false;
    uintptr_t fn = RttiLookup::slotAt(state.ciVtable, slot);
    if (!inExec(fn)) return false;
    typedef bool (*BoolGetter)(void *);
    return reinterpret_cast<BoolGetter>(fn)(reinterpret_cast<void *>(ci));
}

// The chat route: GuiData::addMessage, reached through the SDK layer rather
// than a blind vtable index. Two ways in, tried in order:

//   1. the hooked addMessage (its original-function bridge is a plain callable
//      with the game's own signature) on the discovered GuiData instance;
//   2. no hook installed yet -> call base+off.guiDataAddMessage directly on the
//      instance, which is what a mod loader would do.

// Both need guiData() to have resolved; otherwise this reports and returns
// false. Argument shapes mirror the game's own callers (the same eleven the
// addMessage hook forwards): type 1 = raw text line, empty sender, null UUID,
// not system, duration 5 seconds — matching vanilla chat pop-up behaviour.
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
    typedef void (*AddMessage)(void *, int, const std::string &, const std::string &, void *,
                               const std::string &, void *, void *, float, const std::string &,
                               const std::string &);
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
    static const std::string ustring;
    static const std::string team;
    static const std::string xuid;
    fn(reinterpret_cast<void *>(gd), 1, empty, message, nullptr, ustring, nullptr, nullptr,
       5.0f, team, xuid);
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
    state.pumps++;  // one per game-thread tick, queued or not
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