#include "Reflection.h"
#include <memory/RttiLookup.h>

#include <chrono>
#include <cstdio>
#include <cinttypes>
#include <cstring>
#include <algorithm>
#include <fstream>
#include <memory>
#include <mutex>
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

// /proc/self/maps lists ranges sorted and non-overlapping, so a membership test
// is a binary search. The linear walk this replaced cost ~2000 comparisons per
// candidate, and findMember tests 16k candidates per call — the difference is
// what took the game-thread self-test pass from 0.7 s to 7 s.
typedef std::vector<Range> Ranges;
typedef std::shared_ptr<const Ranges> RangesPtr;

// Scudo tags the top byte of every heap pointer (0xb4000073d628dc00) and the
// hardware ignores that byte on access (TBI), but /proc/self/maps lists the
// UNTAGGED range. Comparing a tagged pointer against the raw range therefore
// never matches, so every heap object — ClientInstance included — looked
// unmapped and each scan-based rung silently returned nothing. RttiLookup's
// inWritableRange has always masked it; this file did not.
uintptr_t untag(uintptr_t addr) {
#if defined(__aarch64__)
    return addr & 0x00ffffffffffffffULL;
#else
    return addr;
#endif
}

// Parse /proc/self/maps into a range list. `writableOnly` keeps
// only ' rw-' style mappings; otherwise all readable mappings count.
RangesPtr loadMaps(bool writableOnly) {
    std::shared_ptr<Ranges> out = std::make_shared<Ranges>();
    std::ifstream maps("/proc/self/maps");
    std::string line;
    while (std::getline(maps, line)) {
        uintptr_t s = 0, e = 0;
        char perms[5] = {};
        if (sscanf(line.c_str(), "%" SCNxPTR "-%" SCNxPTR " %4s", &s, &e, perms) != 3)
            continue;
        const bool w = perms[1] == 'w';
        const bool r = perms[0] == 'r';
        if (!r) continue;
        if (writableOnly && !w) continue;
        out->push_back(Range{s, e});
    }
    return out;
}

bool inRanges(const Ranges &ranges, uintptr_t addr) {
    const uintptr_t a = untag(addr);
    if (a == 0 || a + 8 < a) return false;
    // Last range whose start <= a; ranges are sorted and disjoint.
    size_t lo = 0, hi = ranges.size();
    while (lo < hi) {
        const size_t mid = lo + (hi - lo) / 2;
        if (ranges[mid].start <= a) lo = mid + 1;
        else hi = mid;
    }
    if (lo == 0) return false;
    const Range &r = ranges[lo - 1];
    return a >= r.start && a + 8 <= r.end;
}

// The mapping list is a snapshot of a moving heap: the game allocates
// continuously, so a list parsed once would reject every object created after
// that parse - which is exactly how a genuine LocalPlayer got refused. Refresh
// on the same 250 ms budget RttiLookup uses, and share the snapshot so a scan
// loop parses the file once instead of once per probe.
RangesPtr snapshot(bool writableOnly) {
    static std::mutex g_lock;
    static RangesPtr g_readable, g_writable;
    static std::chrono::steady_clock::time_point g_readableAt, g_writableAt;
    const auto now = std::chrono::steady_clock::now();
    std::lock_guard<std::mutex> guard(g_lock);
    if (writableOnly) {
        if (!g_writable || g_writable->empty() ||
            now - g_writableAt >= std::chrono::milliseconds(250)) {
            g_writable = loadMaps(true);
            g_writableAt = now;
        }
        return g_writable;
    }
    if (!g_readable || g_readable->empty() ||
        now - g_readableAt >= std::chrono::milliseconds(250)) {
        g_readable = loadMaps(false);
        g_readableAt = now;
    }
    return g_readable;
}

// A vtable pointer must live in some READABLE mapping (vtables sit in the
// library's RELRO data segment, not in exec); an object pointer must live in a
// WRITABLE mapping (the heap). Both checks run before any dereference.
bool looksLikeVtable(uintptr_t vt, const Ranges &readable) {
    if (vt == 0 || (vt & 0x7) != 0) return false;
    return inRanges(readable, vt);
}

// True when every byte of [object+off, object+off+8) is inside a mapped range.
// findMember walks a fixed 0x20000 window that is usually far larger than the
// object it was handed, so without this the scan reads past the end of the
// allocation - harmless inside a big scudo region, a SIGSEGV when the object
// happens to sit at the last page of one.
bool slotMapped(const Ranges &ranges, uintptr_t object, uintptr_t off) {
    return inRanges(ranges, object + off);
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
    RangesPtr readable = snapshot(false);
    // A miss is usually a mapping created after the cached parse, not a bad
    // pointer: re-read once before refusing, so a packet or entity the game
    // allocated a microsecond ago is not rejected.
    if (!inRanges(*readable, object)) readable = loadMaps(false);
    if (!inRanges(*readable, object)) return false;
    uintptr_t vt = readPointer(object, 0);
    if (!looksLikeVtable(vt, *readable)) return false;
    for (size_t i = 0; i < tableCount; i++)
        if (untag(vt) == RttiLookup::addressPoint(tables[i])) return true;
    return false;
}

uintptr_t findMemberOffset(uintptr_t object, const std::vector<uintptr_t> &tables,
                           size_t maxOffset, uintptr_t *outValue) {
    if (object == 0 || tables.empty()) return maxOffset;
    RangesPtr writable = snapshot(true);
    RangesPtr readable = snapshot(false);
    if (!inRanges(*writable, object)) {  // see vtableMatchesAny: the heap is growing
        writable = loadMaps(true);
        readable = loadMaps(false);
    }
    if (!inRanges(*writable, object)) return maxOffset;  // the base object must be heap
    bool reloaded = false;
    for (uintptr_t off = 0; off + 8 <= maxOffset; off += 8) {
        if (!slotMapped(*writable, object, off)) continue;
        uintptr_t cand = readPointer(object, off);
        if (cand == 0) continue;
        if (!inRanges(*writable, cand)) {
            if (!reloaded) {  // a member the game allocated after the snapshot
                writable = loadMaps(true);
                readable = loadMaps(false);
                reloaded = true;
            }
            if (!inRanges(*writable, cand)) continue;
        }
        uintptr_t vt = readPointer(cand, 0);
        if (!looksLikeVtable(vt, *readable)) continue;
        for (size_t t = 0; t < tables.size(); t++) {
            if (untag(vt) == RttiLookup::addressPoint(tables[t])) {
                if (outValue != nullptr) *outValue = cand;
                return off;
            }
        }
    }
    return maxOffset;
}

uintptr_t findMember(uintptr_t object, const std::vector<uintptr_t> &tables, size_t maxOffset) {
    uintptr_t value = 0;
    if (findMemberOffset(object, tables, maxOffset, &value) >= maxOffset) return 0;
    return value;
}

std::vector<uintptr_t> findAllMembers(uintptr_t object, const std::vector<uintptr_t> &tables,
                                      size_t maxOffset) {
    std::vector<uintptr_t> out;
    if (object == 0 || tables.empty()) return out;
    RangesPtr writable = snapshot(true);
    RangesPtr readable = snapshot(false);
    if (!inRanges(*writable, object)) {
        writable = loadMaps(true);
        readable = loadMaps(false);
    }
    if (!inRanges(*writable, object)) return out;
    bool reloaded = false;
    for (uintptr_t off = 0; off + 8 <= maxOffset; off += 8) {
        if (!slotMapped(*writable, object, off)) continue;
        uintptr_t cand = readPointer(object, off);
        if (cand == 0) continue;
        if (!inRanges(*writable, cand)) {
            if (!reloaded) {  // see findMember
                writable = loadMaps(true);
                readable = loadMaps(false);
                reloaded = true;
            }
            if (!inRanges(*writable, cand)) continue;
        }
        uintptr_t vt = readPointer(cand, 0);
        if (!looksLikeVtable(vt, *readable)) continue;
        for (size_t t = 0; t < tables.size(); t++) {
            if (untag(vt) == RttiLookup::addressPoint(tables[t])) {
                out.push_back(cand);
                break;
            }
        }
    }
    return out;
}

}  // namespace Reflection