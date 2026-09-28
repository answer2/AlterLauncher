#include "Reflection.h"

#include <cstdio>
#include <cstring>
#include <fstream>
#include <string>
#include <vector>

// Reflection.h explains the design; this file is the mechanics. The region
// list is parsed straight from /proc/self/maps — we are IN the game process,
// so every guest pointer is our own pointer and can be checked against our
// own mappings before it is touched.
namespace Reflection {

namespace {

struct Range {
    uintptr_t start;
    uintptr_t end;
};

typedef std::vector<Range> Ranges;

// Parse /proc/self/maps once per call site (cheap: a few hundred lines, and
// discovery runs at most a handful of times per session). `writableOnly` keeps
// only ' rw-' style mappings; otherwise all readable mappings count.
Ranges loadMaps(bool writableOnly) {
    Ranges out;
    std::ifstream maps("/proc/self/maps");
    std::string line;
    while (std::getline(maps, line)) {
        uintptr_t s = 0, e = 0;
        if (sscanf(line.c_str(), "%lx-%lx", &s, &e) != 2) continue;
        const size_t perms = line.find(' ', line.find_first_not_of("0123456789abcdefABCDEF", 16));
        if (perms == std::string::npos) continue;
        const bool w = line.size() > perms + 2 && line[perms + 2] == 'w';
        const bool r = line.size() > perms && line[perms] == 'r';
        if (!r) continue;
        if (writableOnly && !w) continue;
        out.push_back(Range{s, e});
    }
    return out;
}

bool inRanges(const Ranges &ranges, uintptr_t addr) {
    for (size_t i = 0; i < ranges.size(); i++)
        if (addr >= ranges[i].start && addr + 8 <= ranges[i].end) return true;
    return false;
}

// A vtable pointer must live in some READABLE mapping (vtables sit in the
// library's RELRO data segment, not in exec); an object pointer must live in a
// WRITABLE mapping (the heap). Both checks run before any dereference.
bool looksLikeVtable(uintptr_t vt, const Ranges &readable) {
    if (vt == 0 || (vt & 0x7) != 0) return false;
    return inRanges(readable, vt);
}

}  // namespace

uintptr_t readPointer(uintptr_t object, uintptr_t offset) {
    if (object == 0) return 0;
    uintptr_t value = 0;
    memcpy(&value, reinterpret_cast<const void *>(object + offset), sizeof(value));
    if (value == 0 || (value & 0x7) != 0) return 0;
    return value;
}

bool vtableMatchesAny(uintptr_t object, const uintptr_t *tables, size_t tableCount) {
    if (object == 0 || tables == nullptr || tableCount == 0) return false;
    static const Ranges readable = loadMaps(false);
    uintptr_t vt = readPointer(object, 0);
    if (!looksLikeVtable(vt, readable)) return false;
    for (size_t i = 0; i < tableCount; i++)
        if (vt == tables[i]) return true;
    return false;
}

uintptr_t findMember(uintptr_t object, const std::vector<uintptr_t> &tables, size_t maxOffset) {
    if (object == 0 || tables.empty()) return 0;
    static const Ranges writable = loadMaps(true);
    static const Ranges readable = loadMaps(false);
    if (!inRanges(writable, object)) return 0;  // the base object itself must be heap
    for (uintptr_t off = 0; off + 8 <= maxOffset; off += 8) {
        uintptr_t cand = readPointer(object, off);
        if (cand == 0 || !inRanges(writable, cand)) continue;
        uintptr_t vt = readPointer(cand, 0);
        if (!looksLikeVtable(vt, readable)) continue;
        for (size_t t = 0; t < tables.size(); t++)
            if (vt == tables[t]) return cand;
    }
    return 0;
}

std::vector<uintptr_t> findAllMembers(uintptr_t object, const std::vector<uintptr_t> &tables,
                                      size_t maxOffset) {
    std::vector<uintptr_t> out;
    if (object == 0 || tables.empty()) return out;
    static const Ranges writable = loadMaps(true);
    static const Ranges readable = loadMaps(false);
    if (!inRanges(writable, object)) return out;
    for (uintptr_t off = 0; off + 8 <= maxOffset; off += 8) {
        uintptr_t cand = readPointer(object, off);
        if (cand == 0 || !inRanges(writable, cand)) continue;
        uintptr_t vt = readPointer(cand, 0);
        if (!looksLikeVtable(vt, readable)) continue;
        for (size_t t = 0; t < tables.size(); t++) {
            if (vt == tables[t]) {
                out.push_back(cand);
                break;
            }
        }
    }
    return out;
}

}  // namespace Reflection