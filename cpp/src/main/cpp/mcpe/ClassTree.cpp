#include "ClassTree.h"
#include <mcpe/ClientInstance.h>

#include <cmath>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <map>
#include <string>
#include <vector>

#include <mcpe/GameSDK.h>
#include <mcpe/Reflection.h>
#include <util/HookRules.h>
#include <util/RttiLookup.h>

// ClassTree.h explains the policy; this file is its mechanics. Rule pins and
// RTTI tables are read through GameSdk's accessors so there stays one source
// of truth per value.
namespace mcpe {

namespace detail {

const char *kGuestLib = "libminecraftpe.so";

struct Regions {
    std::vector<RttiLookup::Region> readable;
    std::vector<RttiLookup::Region> writable;
    std::vector<RttiLookup::Region> exec;
};

const Regions &regions() {
    static Regions r;
    if (r.readable.empty()) {
        std::ifstream maps("/proc/self/maps");
        std::string line;
        while (std::getline(maps, line)) {
            uintptr_t s = 0, e = 0;
            if (sscanf(line.c_str(), "%lx-%lx", &s, &e) != 2) continue;
            if (line.find(" r") == std::string::npos) continue;
            r.readable.push_back(RttiLookup::Region{s, e, false});
            if (line.find(" rw") != std::string::npos)
                r.writable.push_back(RttiLookup::Region{s, e, false});
            if (line.find(" x") != std::string::npos)
                r.exec.push_back(RttiLookup::Region{s, e, false});
        }
    }
    return r;
}

bool inWritable(uintptr_t addr) {
    // Strip the scudo top-byte tag: maps regions are recorded untagged.
    return RttiLookup::inRegions(regions().writable, addr & 0x00ffffffffffffffULL, false);
}

bool safeReadPtr(uintptr_t object, uintptr_t offset, uintptr_t *out) {
    if (object == 0 || !inWritable(object)) return false;
    uintptr_t value = 0;
    memcpy(&value, reinterpret_cast<const void *>(object + offset), sizeof(value));
    if (value == 0 || (value & 0x7) != 0) return false;
    *out = value;
    return true;
}

bool safeReadF32(uintptr_t object, uintptr_t offset, float *out) {
    if (object == 0 || !inWritable(object)) return false;
    float value = 0.f;
    memcpy(&value, reinterpret_cast<const void *>(object + offset), sizeof(value));
    if (!std::isfinite(value)) return false;
    *out = value;
    return true;
}

// The rule pin for a dat.<key> entry: an OBJECT-RELATIVE offset, never a
// library RVA. The 'd' marker in the file is what keeps the two kinds apart.
bool dataPin(const char *key, uintptr_t *offset) {
    const HookRules::Rule *rule = GameSdk::rule();
    if (rule == nullptr) return false;
    return rule->lookupDataOffset(std::string(key), offset);
}

// Every RTTI table of a class, cached per name. Used by the scan fallbacks:
// a member candidate must carry one of these as its own vtable pointer.
const std::vector<uintptr_t> &tablesFor(const char *className) {
    static std::map<std::string, std::vector<uintptr_t> > cache;
    std::map<std::string, std::vector<uintptr_t> >::iterator it = cache.find(className);
    if (it != cache.end()) return it->second;
    std::vector<uintptr_t> tables;
    uintptr_t raw[8];
    size_t n = RttiLookup::findVtables(kGuestLib, className, raw, 8);
    for (size_t i = 0; i < n; i++) tables.push_back(raw[i]);
    return cache.insert(std::make_pair(std::string(className), tables)).first->second;
}

// Generic subsystem-field ladder: try the rule data-pin first (fast, explicit
// — the reference SDKs' +0xB0/+0xC0/+0xD0 belong here, inside a version
// window), then a vtable-validated member scan against the class's RTTI
// tables. An unpinned, unscannable field returns an invalid handle instead of
// dereferencing a stale constant.
Handle fieldByPinOrScan(uintptr_t ci, const char *pinKey, const char *scanClass) {
    Handle h;
    if (ci == 0) return h;
    uintptr_t off = 0;
    if (dataPin(pinKey, &off)) {
        uintptr_t cand = 0;
        if (safeReadPtr(ci, off, &cand) && inWritable(cand)) {
            h.ptr = cand;
            h.source = "pin";
            return h;
        }
    }
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
    if (addr == 0 || out == nullptr) return false;
    uintptr_t target = addr + offset;
    if (!inWritable(target)) return false;
    memcpy(out, reinterpret_cast<const void *>(target), sizeof(*out));
    return true;
}

bool safeReadU32(uintptr_t addr, uintptr_t offset, uint32_t *out) {
    if (addr == 0 || out == nullptr) return false;
    if (offset & 3) return false;
    uintptr_t target = addr + offset;
    if (!inWritable(target)) return false;
    memcpy(out, reinterpret_cast<const void *>(target), sizeof(*out));
    return true;
}

bool safeReadI32(uintptr_t addr, uintptr_t offset, int32_t *out) {
    if (addr == 0 || out == nullptr) return false;
    if (offset & 3) return false;
    uintptr_t target = addr + offset;
    if (!inWritable(target)) return false;
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