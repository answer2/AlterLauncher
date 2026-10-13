#include <mcpe/client/ClientInstance.h>

#include <android/log.h>
#include <cstring>
#include <cstdio>
#include <algorithm>
#include <atomic>
#include <cmath>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

#include <mcpe/core/GameSDK.h>
#include <memory/HookRules.h>
#include <memory/RttiLookup.h>
#include <mcpe/client/GuiData.h>
#include <mcpe/core/Reflection.h>
#include <mcpe/core/Types.h>
#include <memory/SigScanner.h>

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
using detail::fieldByPin;
using detail::fieldByPinOrScan;

namespace {

#if defined(__aarch64__)
bool callVerifiedRegionGetter(uintptr_t self, uintptr_t fn, uintptr_t *out) {
    if (self == 0 || fn == 0 || out == nullptr || !inExec(fn)) return false;
    uint32_t w[14] = {};
    memcpy(w, reinterpret_cast<const void *>(fn), sizeof(w));
    // 1.26.45.1 ClientInstance:31. Fixed words prove the 0x50-byte frame and the
    // weak-reference object at this+0x218; only the two BL immediates are allowed
    // to relocate. The empty branch returns x0=0 and the resolved branch returns
    // BlockSource* in x0, so this is a normal pointer-return ABI.
    if (w[0] != 0xD10143FFu || w[1] != 0xA9037BFDu ||
        w[2] != 0xF90023F3u || w[3] != 0x9100C3FDu ||
        w[4] != 0xD53BD053u || w[5] != 0xAA0003E8u ||
        w[6] != 0x910023E0u || w[7] != 0xF9401669u ||
        w[8] != 0x91086101u || w[9] != 0xF81F83A9u ||
        (w[10] & 0xFC000000u) != 0x94000000u || w[11] != 0x910023E0u ||
        (w[12] & 0xFC000000u) != 0x94000000u || w[13] != 0x360001A0u)
        return false;
    typedef uintptr_t (*Fn)(void *);
    const uintptr_t result = reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self));
    if (result == 0 || !inWritable(result)) return false;
    const std::vector<uintptr_t> &tables = tablesFor("BlockSource");
    if (tables.empty() ||
        !Reflection::vtableMatchesAny(result, tables.data(), tables.size()))
        return false;
    *out = result;
    return true;
}
#endif

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

// Word read from a guest mapping that is NOT writable (vtables and typeinfo live
// in .data.rel.ro, so safeReadPtr's writable gate always refuses there).
bool readGuestWord(uintptr_t addr, const std::vector<RttiLookup::Region> &regions,
                   uintptr_t *out) {
    if (addr == 0 || addr % alignof(uintptr_t) != 0) return false;
    for (size_t i = 0; i < regions.size(); i++)
        if (addr >= regions[i].start && addr + sizeof(uintptr_t) <= regions[i].end) {
            memcpy(out, reinterpret_cast<const void *>(addr), sizeof(*out));
            return true;
        }
    return false;
}

// What the candidate says it is, next to every table findVtables actually
// returned. A bare "looksPlayer=0" cannot tell a wrong getter apart from a wrong
// table set, and on 1.26.45.1 both had been blamed for it.
std::string playerIdentity(uintptr_t cand) {
    const std::vector<RttiLookup::Region> regions = RttiLookup::regionsOf(detail::kGuestLib);
    const uintptr_t base = GameSdk::guestBase();
    char out[256];
    size_t used = 0;
    uintptr_t vt = 0;
    // The candidate is a HEAP object, so its own vptr word goes through the
    // writable-range reader; only the words it points at (typeinfo, name) live
    // in the guest's read-only RELRO. Reading the vptr with readGuestWord made
    // every probe report "vptr unreadable" and hid the one number that decides
    // the question.
    if (!safeReadPtr(cand, 0, &vt)) return "vptr unreadable";
    used += snprintf(out + used, sizeof(out) - used, "vtRva=0x%lx", (unsigned long)(vt - base));
    uintptr_t ti = 0, name = 0;
    if (readGuestWord(vt - sizeof(uintptr_t), regions, &ti) &&
        readGuestWord(ti + sizeof(uintptr_t), regions, &name)) {
        char nm[48] = {};
        for (size_t i = 0; i + 1 < sizeof(nm); i++) {
            bool inside = false;
            for (size_t r = 0; r < regions.size(); r++)
                if (name + i >= regions[r].start && name + i < regions[r].end) { inside = true; break; }
            if (!inside) break;
            const char c = *reinterpret_cast<const volatile char *>(name + i);
            if (c == '\0') break;
            nm[i] = c;
        }
        used += snprintf(out + used, sizeof(out) - used, " rtti='%s'", nm);
    } else {
        used += snprintf(out + used, sizeof(out) - used, " rtti=<unreadable>");
    }
    const std::vector<uintptr_t> &tables = detail::tablesFor("LocalPlayer");
    used += snprintf(out + used, sizeof(out) - used, " tables=[");
    for (size_t i = 0; i < tables.size() && used + 20 < sizeof(out); i++)
        used += snprintf(out + used, sizeof(out) - used, "%s0x%lx", i ? "," : "",
                         (unsigned long)(tables[i] - base));
    snprintf(out + used, sizeof(out) - used, "]");
    return std::string(out);
}

// Bytes pulled off a heap object in one go, so a layout scan costs one range
// check per 64-byte chunk instead of one per float. safeReadF32 goes through
// RttiLookup::inWritableRange, which takes a mutex and walks the rw- mapping
// list; a stride-4 scan of 0x2000 bytes is ~32k of those, which is what made a
// single failed projection read cost hundreds of milliseconds on the game
// thread. Non-finite floats become NaN so they can never satisfy a shape check.
const uintptr_t kChunk = 0x40;
const uintptr_t kScanBytes = 0x2000;

struct Snapshot {
    std::vector<float> words;
    std::vector<char> chunkOk;
    size_t bytes = 0;

    bool read(uintptr_t object, size_t len) {
        words.clear();
        chunkOk.clear();
        bytes = 0;
        if (object == 0 || len == 0) return false;
        const size_t chunks = (len + kChunk - 1) / kChunk;
        words.assign(chunks * (kChunk / 4), std::nanf(""));
        chunkOk.assign(chunks, 0);
        bool any = false;
        for (size_t i = 0; i < chunks; i++) {
            const uintptr_t at = object + i * kChunk;
            const size_t n = static_cast<size_t>(std::min<uintptr_t>(kChunk, len - i * kChunk));
            if (!RttiLookup::inWritableRange(at, n)) continue;
            memcpy(&words[i * (kChunk / 4)], reinterpret_cast<const void *>(at), n);
            for (size_t k = 0; k < n / 4; k++) {
                float &f = words[i * (kChunk / 4) + k];
                if (!std::isfinite(f)) f = std::nanf("");
            }
            chunkOk[i] = 1;
            any = true;
        }
        bytes = len;
        return any;
    }

    bool covers(uintptr_t off, size_t len) const {
        if (off % 4 != 0 || off + len > bytes || chunkOk.empty()) return false;
        for (size_t c = off / kChunk; c <= (off + len - 1) / kChunk; c++)
            if (c >= chunkOk.size() || !chunkOk[c]) return false;
        return true;
    }

    bool matrix(uintptr_t off, float *v) const {
        if (!covers(off, 64)) return false;
        memcpy(v, &words[off / 4], 64);
        return true;
    }
};

// A GL perspective matrix. m[0]/m[5] are the focal terms (f/aspect and f) and
// are positive; the -1 that turns view-space z into clip w sits at m[11] when
// the buffer is stored column-major and at m[14] when it is stored transposed.
// Bedrock's own storage order is not something the offline gate can prove, so
// the probe reports which of the two it saw: 0 = not a projection, 1 =
// column-major (glm's convention), 2 = row-major/transposed.
int perspectiveOrder(const float *v) {
    if (!(v[0] > 0.f) || !(v[5] > 0.f)) return 0;
    if (v[15] != 0.f) return 0;
    if (v[3] == 0.f && v[7] == 0.f && (v[11] == -1.f || v[11] == 1.f)) return 1;
    if (v[1] == 0.f && v[2] == 0.f && (v[14] == -1.f || v[14] == 1.f)) return 2;
    return 0;
}

bool unitTriple(float a, float b, float c) {
    const float len2 = a * a + b * b + c * c;
    return len2 > 0.98f && len2 < 1.02f;
}

// A view matrix: orthonormal 3x3 plus m[15] == 1. Rows are tested as well as
// columns because a rotation is orthonormal either way, so this cannot tell the
// storage order apart - perspectiveOrder on the neighbouring projection does.
// The ci+0x458 block the old pin pointed at fails this on m[15] == 0 and on a
// zero first row, which is how the probe identified it as a basis, not a frame.
bool rigidShaped(const float *v) {
    if (!(v[15] > 0.999f && v[15] < 1.001f)) return false;
    const bool rows = unitTriple(v[0], v[1], v[2]) && unitTriple(v[4], v[5], v[6]) &&
                      unitTriple(v[8], v[9], v[10]);
    const bool cols = unitTriple(v[0], v[4], v[8]) && unitTriple(v[1], v[5], v[9]) &&
                      unitTriple(v[2], v[6], v[10]);
    return rows || cols;
}

void transpose(Mat4 *m) {
    for (int c = 0; c < 4; c++)
        for (int r = c + 1; r < 4; r++) std::swap(m->m[c * 4 + r], m->m[r * 4 + c]);
}

// Latite's GameRenderer keeps lastViewMatrix at +0x388 and lastProjectionMatrix
// at +0x408, so 0x80 is the gap to try first; 0x40 covers a build that drops the
// padding. Returns the projection offset, or 0 when the object holds no
// perspective block with a rigid block that far behind it.
uintptr_t discoverProjection(const Snapshot &snap, uintptr_t *gap) {
    const uintptr_t kGaps[2] = {0x80, 0x40};
    for (uintptr_t off = 0x40; off + 64 <= snap.bytes; off += 4) {
        float v[16];
        if (!snap.matrix(off, v) || perspectiveOrder(v) == 0) continue;
        for (int g = 0; g < 2; g++) {
            if (off < kGaps[g]) continue;
            float n[16];
            if (!snap.matrix(off - kGaps[g], n) || !rigidShaped(n)) continue;
            *gap = kGaps[g];
            return off;
        }
    }
    return 0;
}

// What the pinned offsets actually hold. A rejected read used to tell us nothing
// about which float broke, so the pin could never be corrected from a log line.
void dumpMatrix(const Snapshot &snap, uintptr_t off, const char *what) {
    char slot[40];
    std::string dump;
    for (int i = 0; i < 16; i++) {
        float f = std::nanf("");
        const bool ok = snap.covers(off + 4 * i, 4);
        if (ok) f = snap.words[(off + 4 * i) / 4];
        if (ok && std::isfinite(f))
            snprintf(slot, sizeof(slot), " [%d]%.5g", i, (double) f);
        else
            snprintf(slot, sizeof(slot), " [%d]<bad>", i);
        dump += slot;
    }
    __android_log_print(ANDROID_LOG_INFO, "AlterSelfTest", "projection probe: %s +0x%x =%s", what,
                        (unsigned) off, dump.c_str());
}

// Discovered layout, remembered per process. These are offsets, never pointers:
// a GameRenderer address is only valid for as long as the game keeps that object
// alive, while the offset holds for the whole build.
const uintptr_t kNoOffset = ~static_cast<uintptr_t>(0);
std::atomic<uintptr_t> g_gameRendererOff{kNoOffset};
std::atomic<uintptr_t> g_projOff{0};
std::atomic<uintptr_t> g_viewGap{0};

// MinecraftGame -> GameRenderer. The RTTI-certified scan that first finds it
// walks 0x20000 bytes of MinecraftGame (~190 ms on device), so the offset it
// lands on is cached and later frames re-read and re-certify that one slot. A
// stale cache self-heals: a slot that stops carrying a GameRenderer vptr clears
// it and the next call rescans.
uintptr_t gameRendererOf(uintptr_t mg) {
    const std::vector<uintptr_t> &tables = tablesFor("GameRenderer");
    if (mg == 0 || tables.empty()) return 0;
    uintptr_t pinned = 0;
    const bool hasPin = dataPin("gameRendererField", &pinned);
    // Try the pin, then this process's measured offset: both are one validated
    // read, and a wrong pin simply falls through instead of being trusted.
    for (int i = 0; i < 2; i++) {
        const uintptr_t off = i == 0 ? pinned : g_gameRendererOff.load(std::memory_order_acquire);
        if (i == 0 && !hasPin) continue;
        if (off == kNoOffset) continue;
        uintptr_t cand = 0;
        if (safeReadPtr(mg, off, &cand) && inWritable(cand) &&
            Reflection::vtableMatchesAny(cand, tables.data(), tables.size()))
            return cand;
    }
    g_gameRendererOff.store(kNoOffset, std::memory_order_release);
    uintptr_t value = 0;
    const uintptr_t off = Reflection::findMemberOffset(mg, tables, 0x20000, &value);
    if (value == 0 || off >= 0x20000) return 0;
    g_gameRendererOff.store(off, std::memory_order_release);
    __android_log_print(ANDROID_LOG_INFO, "AlterSelfTest",
                        "projection probe: GameRenderer measured at MinecraftGame+0x%x%s "
                        "- pin dat.gameRendererField",
                        (unsigned) off, hasPin ? " [pin stale, using measurement]" : "");
    return value;
}

}  // namespace

// Read-only layout probe: report every float block in `object` that carries a GL
// perspective signature, plus whether the block 0x40/0x80 before it is a rigid
// (view-shaped) matrix. Heap layout is the one class of fact the offline gate
// cannot prove (no getter reads a matrix), so the live object is the evidence.
// Runs once per label; it never changes the answer any caller gets.
void probeProjectionLayout(uintptr_t object, const char *label) {
    static std::mutex g_probeMutex;
    static std::vector<std::string> g_done;
    {
        std::lock_guard<std::mutex> guard(g_probeMutex);
        for (size_t i = 0; i < g_done.size(); i++)
            if (g_done[i] == label) return;
        g_done.push_back(label);
    }
    if (label == nullptr) return;
    Snapshot snap;
    if (object == 0 || !snap.read(object, kScanBytes)) {
        // Say so: a silent bail here is indistinguishable from "scanned, found
        // nothing", and the first on-device run of the GameRenderer probe lost
        // its answer to exactly that.
        __android_log_print(ANDROID_LOG_INFO, "AlterSelfTest",
                            "projection probe: %s unreadable (%p)", label,
                            reinterpret_cast<void *>(object));
        return;
    }

    int reported = 0;
    for (uintptr_t off = 0; off + 64 <= snap.bytes && reported < 6; off += 4) {
        float v[16];
        if (!snap.matrix(off, v) || perspectiveOrder(v) == 0) continue;
        // A perspective block alone does not say which object owns the frame; the
        // view matrix next to it does, and its gap is the pin we need.
        char neighbours[64] = "";
        size_t used = 0;
        const uintptr_t gaps[2] = {0x40, 0x80};
        for (int g = 0; g < 2; g++) {
            if (off < gaps[g]) continue;
            float n[16];
            if (!snap.matrix(off - gaps[g], n)) continue;
            used += snprintf(neighbours + used, sizeof(neighbours) - used, " -0x%lx=%s",
                             (unsigned long) gaps[g], rigidShaped(n) ? "view" : "other");
        }
        __android_log_print(ANDROID_LOG_INFO, "AlterSelfTest",
                            "projection probe: %s+0x%x m0=%.5g m5=%.5g m10=%.5g m11=%.5g "
                            "m14=%.5g m15=%.5g%s",
                            label, (unsigned) off, (double) v[0], (double) v[5], (double) v[10],
                            (double) v[11], (double) v[14], (double) v[15], neighbours);
        reported++;
    }
    if (reported == 0)
        __android_log_print(ANDROID_LOG_INFO, "AlterSelfTest",
                            "projection probe: %s has no matrix-shaped float block in +0..0x%zx",
                            label, snap.bytes);
}

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
            memcpy(&fn, reinterpret_cast<const void *>(vt + slot * sizeof(uintptr_t)), sizeof(fn));
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
            memcpy(&fn, reinterpret_cast<const void *>(vt + slot * sizeof(uintptr_t)), sizeof(fn));
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

    // 1. Field pin, RTTI-validated: a build whose local player is a plain member
    //    needs no call at all.
    Handle pinned = fieldByPin(self_, "localPlayerField", "LocalPlayer");
    if (pinned.ptr != 0) return pinned;

    // 2. The same function by signature NEXT, ahead of the member scan. The
    //    pattern is byte-matched against THIS exact mapping, so it lands on the
    //    real getter even where the reference/BedrockTools slot number has
    //    shifted - which is precisely what faulted the game process when the
    //    slot rung ran ahead of it (slot 32 is getLocalPlayer in the reference
    //    table, but not at offset 32 of the runtime primary vtable on
    //    1.26.45.1). Identical body, so the plain uintptr_t(*)(void*) trampoline
    //    is the correct ABI and every result still passes looksLikePlayer.
    //    Ordering matters for cost too: the scan below walks 0x20000 of
    //    ClientInstance and on 1.26.45.1 never finds the player, so running it
    //    first made every single getPlayerPos pay for it.
    uintptr_t sigFn = SigScanner::resolve(std::string("clientInstanceGetLocalPlayer"));
    if (sigFn != 0 && inExec(sigFn)) {
        using Fn = uintptr_t (*)(void *);
        uintptr_t result = reinterpret_cast<Fn>(sigFn)(reinterpret_cast<void *>(self_));
        uintptr_t vt = 0;
        bool vtOk = result != 0 && safeReadPtr(result, 0, &vt);
        __android_log_print(ANDROID_LOG_INFO, "AlterSelfTest",
                            "lp-sig probe: fn=%p result=%p writable=%d vt=%p guestVt=%d "
                            "looksPlayer=%d %s",
                            reinterpret_cast<void *>(sigFn), reinterpret_cast<void *>(result),
                            result != 0 && inWritable(result) ? 1 : 0, reinterpret_cast<void *>(vt),
                            vtOk && inGuestLib(vt) ? 1 : 0, looksLikePlayer(result) ? 1 : 0,
                            playerIdentity(result).c_str());
        if (result != 0 && inWritable(result) && looksLikePlayer(result))
            return Handle{result, "sig"};
    }

    // 3. Read-only member scan for a build that stores the player inline and
    //    carries neither pin nor pattern.
    Handle scanned = fieldByPinOrScan(self_, "localPlayerField", "LocalPlayer");
    if (scanned.ptr != 0) return scanned;

    // 4. Last resort: the pinned vtable virtual, only for a build that carries
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
            memcpy(&fn2, reinterpret_cast<const void *>(vt + slot * sizeof(uintptr_t)), sizeof(fn2));
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
#if defined(__aarch64__)
    const HookRules::Rule *rule = GameSdk::rule();
    std::string cls;
    size_t slot = 0;
    if (rule &&
        rule->lookupVtable(std::string("clientInstanceGetRegion"), &cls, &slot) &&
        cls == "ClientInstance") {
        uintptr_t vt = 0;
        if (safeReadPtr(self_, 0, &vt) && inGuestLib(vt)) {
            uintptr_t fn = 0, result = 0;
            memcpy(&fn, reinterpret_cast<const void *>(vt + slot * sizeof(uintptr_t)), sizeof(fn));
            if (callVerifiedRegionGetter(self_, fn, &result))
                return Handle{result, "vcall:decoded"};
        }
    }
#endif
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
            memcpy(&fn, reinterpret_cast<const void *>(vt + slot * sizeof(uintptr_t)), sizeof(fn));
            if (fn != 0 && inExec(fn)) {
                using Fn = uintptr_t (*)(void *);
                uintptr_t result = reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_));
                if (result != 0 && inWritable(result)) return Handle{result, "vcall"};
            }
        }
    }

    // The client getter is named getLevel but its concrete object is a Level
    // subclass (1.26.45.1 RTTI gives ClientLevel @0x11edd900, Level @0x122b2188,
    // ServerLevel), not the exact base table. fieldByPin(...,"Level") correctly
    // rejected that vptr; accepting these explicit identities keeps the stale-pin
    // safety boundary while letting the real object through. When even these
    // refuse, log the live vptr as an RVA so the class can be identified offline
    // instead of guessing at another name.
    uintptr_t off = 0, candidate = 0;
    if (dataPin("levelField", &off) && safeReadPtr(self_, off, &candidate) && inWritable(candidate)) {
        static const char *const kLevelClasses[] = {"ClientLevel", "Level", "ServerLevel"};
        for (size_t i = 0; i < 3; i++) {
            const std::vector<uintptr_t> &tables = tablesFor(kLevelClasses[i]);
            if (!tables.empty() &&
                Reflection::vtableMatchesAny(candidate, tables.data(), tables.size()))
                return Handle{candidate, kLevelClasses[i]};
        }
        uintptr_t vt = 0;
        if (safeReadPtr(candidate, 0, &vt)) {
            static std::atomic<bool> logged(false);
            if (!logged.exchange(true))
                __android_log_print(ANDROID_LOG_INFO, "AlterSelfTest",
                                    "level probe: %p+0x%x -> %p carries vptr 0x%lx (rva 0x%lx), "
                                    "not ClientLevel/Level/ServerLevel",
                                    reinterpret_cast<void *>(self_), (unsigned) off,
                                    reinterpret_cast<void *>(candidate), (unsigned long) vt,
                                    (unsigned long) (vt - GameSdk::guestBase()));
        }
    }
    return Handle{};
}

bool ClientInstance::readViewProjection(Mat4 *view, Vec2 *focal) const {
    if (view == nullptr || focal == nullptr) return false;

    // The frame matrices live on GameRenderer, reached as
    // MinecraftGame -> gameRenderer: Latite reads lastViewMatrix/
    // lastProjectionMatrix off it and multiplies proj * view, and no reference
    // project reads either one as a ClientInstance member. The old ci+0x458 pin
    // was measured on-device and holds a 3x3 basis with a zero first row, not a
    // view matrix, so it is retired rather than re-pinned.
    const uintptr_t mg = GameSdk::minecraftGame();
    if (mg == 0) return false;
    const uintptr_t gr = gameRendererOf(mg);
    if (gr == 0) {
        static std::atomic<bool> logged(false);
        if (!logged.exchange(true))
            __android_log_print(ANDROID_LOG_INFO, "AlterSelfTest",
                                "projection probe: MinecraftGame has no GameRenderer member");
        return false;
    }

    Snapshot snap;
    if (!snap.read(gr, kScanBytes)) {
        probeProjectionLayout(gr, "GameRenderer");
        return false;
    }

    // Three ways to learn where the pair lives, in descending order of trust:
    // this build's rule pin, the offsets this process measured earlier, and a
    // fresh shape scan. Every candidate is re-validated against the live blocks
    // before it is used, so a pin that does not hold - the 32-bit compile of the
    // same game version, whose layout was never measured here - falls through to
    // measurement instead of switching the feature off for the whole session.
    uintptr_t pinnedView = 0, pinnedProj = 0;
    const bool hasPin =
        dataPin("grViewMatrix", &pinnedView) && dataPin("grProjectionMatrix", &pinnedProj);
    uintptr_t cacheView = 0, cacheProj = 0;
    const uintptr_t cachedProj = g_projOff.load(std::memory_order_acquire);
    const uintptr_t cachedGap = g_viewGap.load(std::memory_order_acquire);
    if (cachedProj != 0 && cachedGap != 0 && cachedProj >= cachedGap) {
        cacheProj = cachedProj;
        cacheView = cachedProj - cachedGap;
    }
    uintptr_t foundProj = 0, foundGap = 0;
    foundProj = discoverProjection(snap, &foundGap);

    Mat4 v, p;
    int order = 0;
    bool ok = false;
    const struct {
        uintptr_t view, proj;
    } candidates[3] = {{pinnedView, pinnedProj}, {cacheView, cacheProj},
                       {foundProj ? foundProj - foundGap : 0, foundProj}};
    for (int i = 0; i < 3 && !ok; i++) {
        const uintptr_t cv = candidates[i].view, cp = candidates[i].proj;
        if (cv == 0 || cp == 0) continue;
        float mv[16], mp[16];
        if (!snap.matrix(cv, mv) || !snap.matrix(cp, mp)) continue;
        const int o = perspectiveOrder(mp);
        if (o == 0 || !rigidShaped(mv)) continue;
        memcpy(v.m, mv, sizeof(mv));
        memcpy(p.m, mp, sizeof(mp));
        order = o;
        ok = true;
        const bool fromPin = hasPin && cv == pinnedView && cp == pinnedProj;
        if (!fromPin) {
            g_viewGap.store(cp - cv, std::memory_order_release);
            g_projOff.store(cp, std::memory_order_release);
            static std::atomic<bool> logged(false);
            if (!logged.exchange(true))
                __android_log_print(
                    ANDROID_LOG_INFO, "AlterSelfTest",
                    "projection probe: GameRenderer measured view=+0x%x projection=+0x%x (gap 0x%x)%s "
                    "- pin dat.grViewMatrix/dat.grProjectionMatrix",
                    (unsigned) cv, (unsigned) cp, (unsigned)(cp - cv),
                    hasPin ? " [pin stale, using measurement]" : "");
        }
    }
    if (!ok) {
        static std::atomic<bool> dumped(false);
        if (!dumped.exchange(true) && hasPin) {
            dumpMatrix(snap, pinnedView, "grViewMatrix");
            dumpMatrix(snap, pinnedProj, "grProjectionMatrix");
        }
        // A cached answer that stopped validating is wrong, not sacred.
        g_projOff.store(0, std::memory_order_release);
        g_viewGap.store(0, std::memory_order_release);
        probeProjectionLayout(gr, "GameRenderer");
        return false;
    }
    // Hand callers one convention. A rotation is orthonormal either way, so the
    // view block cannot classify itself; the projection's storage order decides
    // for both, they being written by the same render pass.
    if (order == 2) {
        transpose(&v);
        transpose(&p);
    }
    *view = v;
    // clip = P * viewPos with P[3] = P[7] = P[15] = 0 gives clip.w = -viewPos.z
    // and ndc.x = P[0] * viewPos.x / -viewPos.z, which is exactly the form
    // mcpe::worldToScreen applies its "tan" arguments in - so the focal terms of
    // the projection ARE those arguments and no separate fov pin is needed. The
    // diagonal is invariant under the transpose above.
    focal->x = p.m[0];
    focal->y = p.m[5];
    return true;
}

bool ClientInstance::worldToScreenWithCamera(const Vec3 &cameraPos, const Vec3 &worldPos,
                                             Vec2 *out) const {
    if (out == nullptr) return false;
    Mat4 m;
    Vec2 fov;
    if (!readViewProjection(&m, &fov)) return false;

    Vec3 rel(worldPos.x - cameraPos.x, worldPos.y - cameraPos.y, worldPos.z - cameraPos.z);
    // Rotation only. The stored view matrix is R * T(-camera), so V * worldPos
    // and R * (worldPos - cameraPos) are the same point; adding the translation
    // column to an already camera-relative offset would subtract the camera
    // twice. Dropping it also keeps this independent of the renderer's floating
    // origin, which the caller does not have.
    const float *v = m.m;
    Vec3 view;
    view.x = rel.x * v[0] + rel.y * v[4] + rel.z * v[8];
    view.y = rel.x * v[1] + rel.y * v[5] + rel.z * v[9];
    view.z = rel.x * v[2] + rel.y * v[6] + rel.z * v[10];

    uintptr_t gdPtr = GameSdk::guiData();
    if (gdPtr == 0) return false;
    Vec2 win;
    if (!GuiData(gdPtr).windowSize(&win, nullptr)) return false;
    return mcpe::worldToScreen(view, fov.x, fov.y, win.x, win.y, out);
}

// Read-only second opinion on the frame matrices, for the self-test.
//
// A second, independent Android implementation documents the same pair living on
// LevelRendererCamera at +0x8b8 (a camera-to-world matrix, row-vector) and
// +0xfc0 (projection) - for 1.26.51.1. We are on 1.26.45.1, so neither the
// object's location nor those offsets are assumed here: the camera is found by
// its own RTTI table and both blocks are re-measured by shape, exactly the way
// GameRenderer's were. What comes back is a number, not a name - if the two
// objects' projections agree element-for-element then the GameRenderer route is
// corroborated by a second owner instead of resting on the on-centre test alone.
// Nothing here changes any answer a caller gets.
void crossCheckFrameMatrices(uintptr_t ci) {
    const char *tag = "AlterSelfTest";
    const std::vector<uintptr_t> &camTables = tablesFor("LevelRendererCamera");
    if (camTables.empty()) {
        __android_log_print(ANDROID_LOG_INFO, tag,
                            "camera cross-check: LevelRendererCamera has no RTTI table");
        return;
    }

    uintptr_t roots[3];
    roots[0] = ci;
    roots[1] = GameSdk::minecraftGame();
    roots[2] = ci == 0 ? 0 : ClientInstance(ci).levelRenderer().ptr;
    uintptr_t cam = 0;
    const char *via = "-";
    for (int i = 0; i < 3 && cam == 0; i++) {
        if (roots[i] == 0) continue;
        cam = Reflection::findMember(roots[i], camTables, 0x20000);
        via = i == 0 ? "ClientInstance" : (i == 1 ? "MinecraftGame" : "LevelRenderer");
    }
    if (cam == 0) {
        __android_log_print(ANDROID_LOG_INFO, tag,
                            "camera cross-check: no LevelRendererCamera as a pointer "
                            "member of clientInstance / minecraftGame / levelRenderer");
        return;
    }

    Snapshot snap;
    if (!snap.read(cam, kScanBytes)) {
        __android_log_print(ANDROID_LOG_INFO, tag,
                            "camera cross-check: camera %p (via %s) unreadable",
                            reinterpret_cast<void *>(cam), via);
        return;
    }
    uintptr_t gap = 0;
    const uintptr_t projOff = discoverProjection(snap, &gap);
    if (projOff == 0) {
        __android_log_print(ANDROID_LOG_INFO, tag,
                            "camera cross-check: camera %p (via %s) holds no "
                            "perspective+view pair in +0..0x%zx",
                            reinterpret_cast<void *>(cam), via, snap.bytes);
        return;
    }
    float pv[16], vv[16];
    if (!snap.matrix(projOff, pv) || !snap.matrix(projOff - gap, vv)) return;
    __android_log_print(ANDROID_LOG_INFO, tag,
                        "camera cross-check: camera=%p via %s  view=+0x%x proj=+0x%x (gap 0x%x)  "
                        "proj m0=%.5f m5=%.5f",
                        reinterpret_cast<void *>(cam), via, (unsigned)(projOff - gap),
                        (unsigned) projOff, (unsigned) gap, (double) pv[0], (double) pv[5]);

    Mat4 gv;
    Vec2 gf;
    if (!ClientInstance(ci).readViewProjection(&gv, &gf)) {
        __android_log_print(ANDROID_LOG_INFO, tag,
                            "camera cross-check: GameRenderer route unavailable, no comparison");
        return;
    }
    // Focal terms are the diagonal, so they survive the storage-order question -
    // which is the part worth comparing. The two view blocks are only compared
    // after both are read in the same order, so a row/column mismatch between the
    // objects shows up as a large difference rather than being silently hidden.
    const float d0 = pv[0] - gf.x, d5 = pv[5] - gf.y;
    float maxView = 0.f;
    for (int i = 0; i < 16; i++) {
        const float d = vv[i] - gv.m[i];
        const float a = d < 0.f ? -d : d;
        if (a > maxView) maxView = a;
    }
    __android_log_print(ANDROID_LOG_INFO, tag,
                        "camera cross-check: focal diff %.6f/%.6f (GameRenderer %.5f,%.5f) "
                        "view max|diff|=%.6f -> %s",
                        (double) d0, (double) d5, (double) gf.x, (double) gf.y,
                        (double) maxView,
                        (d0 < 1e-4f && d0 > -1e-4f && d5 < 1e-4f && d5 > -1e-4f)
                            ? "PROJECTIONS AGREE" : "projections differ");
}

}  // namespace mcpe