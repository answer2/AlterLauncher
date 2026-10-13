#include "ClassTree.h"
#include <mcpe/client/ClientInstance.h>

#include <cmath>
#include <cinttypes>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <map>
#include <mutex>
#include <string>
#include <vector>

#include <mcpe/core/GameSDK.h>
#include <mcpe/core/Reflection.h>
#include <memory/HookRules.h>
#include <memory/RttiLookup.h>

// ClassTree.h explains the policy; this file is its mechanics. Rule pins and
// RTTI tables are read through GameSdk's accessors so there stays one source
// of truth per value.
namespace mcpe {

namespace detail {

const char *kGuestLib = "libminecraftpe.so";

struct Regions {
    std::vector<RttiLookup::Region> readable;
    std::vector<RttiLookup::Region> exec;
};

const Regions &regions() {
    // The self-test warms these caches on its own thread while the game thread
    // keeps reading them, so both caches are guarded. The lock is only taken to
    // check/ publish, never to run the walk.
    static std::mutex g_regionsMutex;
    static Regions r;
    std::lock_guard<std::mutex> guard(g_regionsMutex);
    if (r.readable.empty()) {
        std::ifstream maps("/proc/self/maps");
        std::string line;
        while (std::getline(maps, line)) {
            uintptr_t s = 0, e = 0;
            char perms[5] = {};
            if (sscanf(line.c_str(), "%" SCNxPTR "-%" SCNxPTR " %4s", &s, &e, perms) != 3)
                continue;
            if (perms[0] != 'r' || e <= s) continue;
            r.readable.push_back(RttiLookup::Region{s, e, perms[2] == 'x'});
            if (perms[2] == 'x')
                r.exec.push_back(RttiLookup::Region{s, e, true});
        }
    }
    return r;
}

bool inWritable(uintptr_t addr) {
    // One writable-snapshot policy for the whole SDK, owned by RttiLookup: this
    // used to be a permanent snapshot of the process's rw mappings, which
    // silently rejects every object the scudo heap allocates after the first
    // probe (the same stale-cache bug GameSdk documents, in the other direction).
    return RttiLookup::inWritableRange(addr, 1);
}

bool safeReadPtr(uintptr_t object, uintptr_t offset, uintptr_t *out) {
    if (object == 0 || out == nullptr || offset > UINTPTR_MAX - object ||
        !RttiLookup::inWritableRange(object + offset, sizeof(*out))) return false;
    uintptr_t value = 0;
    memcpy(&value, reinterpret_cast<const void *>(object + offset), sizeof(value));
    if (value == 0 || (value & (alignof(uintptr_t) - 1)) != 0) return false;
    *out = value;
    return true;
}

bool safeReadF32(uintptr_t object, uintptr_t offset, float *out) {
    if (object == 0 || out == nullptr || offset > UINTPTR_MAX - object ||
        !RttiLookup::inWritableRange(object + offset, sizeof(*out))) return false;
    float value = 0.f;
    memcpy(&value, reinterpret_cast<const void *>(object + offset), sizeof(value));
    if (!std::isfinite(value)) return false;
    *out = value;
    return true;
}

// The rule pin for a dat.<key> entry: an OBJECT-RELATIVE offset, never a
// library RVA. The 'd' marker in the file is what keeps the two kinds apart.
// Measuring pins are cached on success only. A failure is usually "the RTTI walk
// has not run yet for this class", which selfTestWarmup fixes a moment later, so
// a negative result must be retried rather than remembered.
bool dataPin(const char *key, uintptr_t *offset) {
    const HookRules::Rule *rule = GameSdk::rule();
    if (rule == nullptr || key == nullptr) return false;
    if (rule->lookupDataOffset(std::string(key), offset)) return true;

    std::string className;
    size_t slot = 0;
    if (!rule->lookupDataGetter(std::string(key), &className, &slot)) return false;

    static std::mutex g_getterMutex;
    static std::map<std::string, uintptr_t> g_getterCache;
    {
        std::lock_guard<std::mutex> guard(g_getterMutex);
        std::map<std::string, uintptr_t>::const_iterator it = g_getterCache.find(key);
        if (it != g_getterCache.end()) {
            if (offset != nullptr) *offset = it->second;
            return true;
        }
    }
    uintptr_t measured = 0;
    if (!getterOffsetByClass(className.c_str(), slot, &measured)) return false;
    {
        std::lock_guard<std::mutex> guard(g_getterMutex);
        g_getterCache[key] = measured;
    }
    if (offset != nullptr) *offset = measured;
    return true;
}

// Every RTTI table of a class, cached per name. Used by the scan fallbacks:
// a member candidate must carry one of these as its own vtable pointer.
const std::vector<uintptr_t> &tablesFor(const char *className) {
    static std::mutex g_tablesMutex;
    static std::map<std::string, std::vector<uintptr_t> > cache;
    std::vector<uintptr_t> tables;
    {
        std::lock_guard<std::mutex> guard(g_tablesMutex);
        std::map<std::string, std::vector<uintptr_t> >::iterator it = cache.find(className);
        if (it != cache.end()) return it->second;
    }
    // The walk itself runs outside the lock (it is what the self-test warms on a
    // second thread); two racers may compute the same answer, which is harmless
    // because the published vector is derived from read-only guest mappings.
    uintptr_t raw[8];
    size_t n = RttiLookup::findVtables(kGuestLib, className, raw, 8);
    for (size_t i = 0; i < n; i++) tables.push_back(raw[i]);
    std::lock_guard<std::mutex> guard(g_tablesMutex);
    return cache.insert(std::make_pair(std::string(className), tables)).first->second;
}

Handle fieldByPin(uintptr_t ci, const char *pinKey, const char *scanClass) {
    Handle h;
    if (ci == 0) return h;
    uintptr_t off = 0;
    if (!dataPin(pinKey, &off)) return h;
    uintptr_t cand = 0;
    if (!safeReadPtr(ci, off, &cand) || !inWritable(cand)) return h;
    // A pin is a claim about one build. When RTTI knows the class, the object
    // behind the pin has to carry one of its tables - otherwise a stale offset
    // would hand out a live pointer of the wrong type, which is crash-grade for
    // whoever dereferences it. Classes with no vtable (GuiData) stay on the
    // trust-the-pin path.
    const std::vector<uintptr_t> &tables = tablesFor(scanClass);
    if (!tables.empty() &&
        !Reflection::vtableMatchesAny(cand, tables.data(), tables.size()))
        return h;
    h.ptr = cand;
    h.source = "pin";
    return h;
}

// Generic subsystem-field ladder: try the rule data-pin first (fast, explicit
// — the reference SDKs' +0xB0/+0xC0/+0xD0 belong here, inside a version
// window), then a vtable-validated member scan against the class's RTTI
// tables. An unpinned, unscannable field returns an invalid handle instead of
// dereferencing a stale constant.
Handle fieldByPinOrScan(uintptr_t ci, const char *pinKey, const char *scanClass) {
    Handle h = fieldByPin(ci, pinKey, scanClass);
    if (h.ptr != 0) return h;
    const std::vector<uintptr_t> &tables = tablesFor(scanClass);
    if (!tables.empty()) {
        uintptr_t found = Reflection::findMember(ci, tables, 0x20000);
        if (found != 0) {
            h.ptr = found;
            h.source = "scan";
        }
    }
    return h;
}

// Encodings for the instruction words this check accepts.
//   ldr w0,[x0,#imm] : 0xB9400000 | (imm12 << 10), verified with mask 0xFFC003E0
//   ldr x0,[x0,#imm] : 0xF9400000 | (imm12 << 10), same mask
//   ret              : 0xD65F03C0 exactly
// Both loads scale imm12 by the access size (4 and 8), so the field offset is
// imm12 * size. Rt = w0/x0 (bits 4:0) and Rn = x0 (bits 9:5) are inside the mask,
// so a getter that returns through another register or reads another base fails.
// Scudo tags the top byte of heap pointers and the maps file lists them untagged;
// vtable addresses are untagged, so a comparison against one must strip the tag.
uintptr_t untagValue(uintptr_t addr) {
#if defined(__aarch64__)
    return addr & 0x00ffffffffffffffULL;
#else
    return addr;
#endif
}

const uint32_t kMaskLdr = 0xFFC003E0u;
const uint32_t kWordLdr32 = 0xB9400000u;
const uint32_t kWordLdr64 = 0xF9400000u;
const uint32_t kWordRet = 0xD65F03C0u;

// A qword out of a guest mapping that is NOT writable. Vtable slots live in
// .data.rel.ro, which safeReadPtr's writable gate always refuses, and the
// offline tools read these through the relocation addend while the device has to
// read the mapped value directly.
bool readRelroWord(uintptr_t addr, uintptr_t *out) {
    if (addr == 0 || out == nullptr || (addr % alignof(uintptr_t)) != 0) return false;
    const std::vector<RttiLookup::Region> &regs = RttiLookup::regionsOf(kGuestLib);
    if (!RttiLookup::inRegions(regs, addr, false)) return false;
    memcpy(out, reinterpret_cast<const void *>(addr), sizeof(*out));
    return *out != 0;
}

bool readCodeWord(uintptr_t addr, uint32_t *out) {
    if (addr == 0 || out == nullptr || !inExec(addr)) return false;
    memcpy(out, reinterpret_cast<const void *>(addr), sizeof(*out));
    return true;
}

bool verifiedGetterOffset(uintptr_t object, const char *className, size_t slot, bool wide,
                          uintptr_t *outOffset) {
    if (outOffset != nullptr) *outOffset = 0;
    if (object == 0 || className == nullptr) return false;
    const std::vector<uintptr_t> &tables = tablesFor(className);
    if (tables.empty()) return false;
    uintptr_t vptr = 0;
    if (!safeReadPtr(object, 0, &vptr)) return false;
    // The object's OWN vptr picks the table. tablesFor() returns the primary table
    // and every base/subobject table sharing that typeinfo (Dimension has four,
    // with offset_to_top 0 / -8 / -16 / -56), and slot 3 means something different
    // in each - all of them can decode to a well-formed `ldr w0,[x0,#imm]; ret`.
    // Taking the first that shape-matches would silently read a different field.
    for (size_t t = 0; t < tables.size(); t++) {
        if (untagValue(vptr) != RttiLookup::addressPoint(tables[t])) continue;
        uintptr_t fn = 0;
        if (!readRelroWord(RttiLookup::addressPoint(tables[t]) + slot * sizeof(uintptr_t), &fn))
            return false;
        if (!inExec(fn)) return false;
        uint32_t w0 = 0, w1 = 0;
        if (!readCodeWord(fn, &w0) || !readCodeWord(fn + 4, &w1)) return false;
        if (w1 != kWordRet) return false;
        if ((w0 & kMaskLdr) != (wide ? kWordLdr64 : kWordLdr32)) return false;
        const uintptr_t imm12 = (w0 >> 10) & 0xFFFu;
        if (outOffset != nullptr) *outOffset = imm12 * (wide ? 8u : 4u);
        return true;
    }
    return false;  // this object does not carry a table of that class
}

// The offset a measuring pin (`dat.<key> = gClass:slot`) reads out of its slot,
// with no object to disambiguate tables.
//
// verifiedGetterOffset() insists on the object's own vptr for a reason: a class
// with several tables (Dimension has four) gives `slot 3` a different meaning in
// each, and several of them can decode to a well-formed field read — taking the
// first that shape-matches would silently name a different field. With no object
// there is nothing to pick with, so the rule is the one scanGuiDataInline()
// already uses: decode every table and accept the answer only when they AGREE.
// Two tables that disagree mean this pin is genuinely ambiguous for the class,
// and a guess here is crash-grade, so it fails instead. Callers holding the
// object should keep using verifiedGetterOffset, which can tell them apart.
bool getterOffsetByClass(const char *className, size_t slot, uintptr_t *outOffset) {
    if (outOffset != nullptr) *outOffset = 0;
    if (className == nullptr) return false;
    const std::vector<uintptr_t> &tables = tablesFor(className);
    if (tables.empty()) return false;
    bool found = false;
    uintptr_t agreed = 0;
    for (size_t t = 0; t < tables.size(); t++) {
        for (int wide = 0; wide < 2; wide++) {
            uintptr_t fn = 0;
            if (!readRelroWord(RttiLookup::addressPoint(tables[t]) + slot * sizeof(uintptr_t),
                               &fn))
                continue;
            if (!inExec(fn)) continue;
            uint32_t w0 = 0, w1 = 0;
            if (!readCodeWord(fn, &w0) || !readCodeWord(fn + 4, &w1)) continue;
            if (w1 != kWordRet) continue;
            if ((w0 & kMaskLdr) != (wide ? kWordLdr64 : kWordLdr32)) continue;
            uintptr_t off = static_cast<uintptr_t>((w0 >> 10) & 0xFFFu) * (wide ? 8u : 4u);
            if (!found) {
                found = true;
                agreed = off;
            } else if (off != agreed) {
                return false;  // ambiguous across this class's tables
            }
        }
    }
    if (!found) return false;
    if (outOffset != nullptr) *outOffset = agreed;
    return true;
}

bool inExec(uintptr_t addr) {
    if (addr == 0) return false;
    const auto &regs = regions();
    for (const auto &r : regs.exec) {
        if (addr >= r.start && addr < r.end) return true;
    }
    return false;
}

bool inGuestLib(uintptr_t addr) {
    const auto regions = RttiLookup::regionsOf(kGuestLib);
    return RttiLookup::inRegions(regions, addr, false);
}

bool safeReadU8(uintptr_t addr, uintptr_t offset, uint8_t *out) {
    if (addr == 0 || out == nullptr || offset > UINTPTR_MAX - addr) return false;
    uintptr_t target = addr + offset;
    if (!RttiLookup::inWritableRange(target, sizeof(*out))) return false;
    memcpy(out, reinterpret_cast<const void *>(target), sizeof(*out));
    return true;
}

bool safeReadU32(uintptr_t addr, uintptr_t offset, uint32_t *out) {
    if (addr == 0 || out == nullptr || offset > UINTPTR_MAX - addr) return false;
    if (offset & 3) return false;
    uintptr_t target = addr + offset;
    if (!RttiLookup::inWritableRange(target, sizeof(*out))) return false;
    memcpy(out, reinterpret_cast<const void *>(target), sizeof(*out));
    return true;
}

// A word that is not a pointer - an entt page index, a packed entity id, the
// object image the self-test dumps. safeReadPtr refuses 0 and unaligned values,
// which is right for pointers and wrong here: 0 is a legitimate first index.
bool safeReadU64(uintptr_t addr, uintptr_t offset, uint64_t *out) {
    if (addr == 0 || out == nullptr || offset > UINTPTR_MAX - addr) return false;
    if (offset & 7) return false;
    uintptr_t target = addr + offset;
    if (!RttiLookup::inWritableRange(target, sizeof(*out))) return false;
    memcpy(out, reinterpret_cast<const void *>(target), sizeof(*out));
    return true;
}

bool safeReadI32(uintptr_t addr, uintptr_t offset, int32_t *out) {
    if (addr == 0 || out == nullptr || offset > UINTPTR_MAX - addr) return false;
    if (offset & 3) return false;
    uintptr_t target = addr + offset;
    if (!RttiLookup::inWritableRange(target, sizeof(*out))) return false;
    memcpy(out, reinterpret_cast<const void *>(target), sizeof(*out));
    return true;
}

}  // namespace detail

// ---------------------------------------------------------------------------
// GameTree
// ---------------------------------------------------------------------------

GameTree::GameTree() {
    uintptr_t ci = GameSdk::clientInstance();
    if (ci == 0) return;
    clientInstance = Handle{ci, "hook"};
    ClientInstance inst(ci);
    guiData = inst.guiData();
    minecraftGame = Handle{GameSdk::minecraftGame(), "sdk"};
    localPlayer = inst.getLocalPlayer();
    levelRenderer = inst.levelRenderer();
    level = inst.getLevel();
    camera = inst.getCamera();
    options = inst.getOptions();
    region = inst.getRegion();
    packetSender = inst.loopbackPacketSender();
}

}  // namespace mcpe