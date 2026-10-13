#include <memory/RttiLookup.h>

#include <ctype.h>
#include <errno.h>
#include <cinttypes>
#include <cstdio>
#include <fstream>
#include <sstream>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <map>
#include <mutex>
#include <utility>

#include <android/android_log.h>

namespace RttiLookup {

namespace {

// Distance from the pointer slot to the start of the structure that owns it:
// typeinfo keeps __name at +8, and a vtable keeps its typeinfo pointer at +8
// with the first virtual function at +16 (after offset_to_top).
constexpr size_t kNameDelta = 8;
constexpr size_t kVtableDelta = 8;
constexpr size_t kHeaderSize = 16;

bool parseLine(const std::string &line, Region *out, std::string *path) {
  uintptr_t start = 0, end = 0;
  std::istringstream stream(line);
  std::string range, perms;
  if (!(stream >> range >> perms)) return false;
  if (sscanf(range.c_str(), "%" SCNxPTR "-%" SCNxPTR, &start, &end) != 2) return false;
  if (perms.size() < 4 || perms[0] != 'r') return false;
  out->start = start;
  out->end = end;
  out->executable = perms[2] == 'x';
  std::string rest;
  std::getline(stream, rest);
  size_t name = rest.find('/');
  *path = name == std::string::npos ? std::string() : rest.substr(name);
  return true;
}

struct Hit {
  size_t wanted;  // index into the value list
  uintptr_t slot;
};

class Scanner {
 public:
  explicit Scanner(const std::vector<Region> &regions) : regions_(regions) {}

  bool readable(uintptr_t addr, size_t size) const {
    for (size_t i = 0; i < regions_.size(); i++) {
      const Region &r = regions_[i];
      if (addr >= r.start && addr <= r.end && size <= r.end - addr) return true;
    }
    return false;
  }

  bool executable(uintptr_t addr) const {
    for (size_t i = 0; i < regions_.size(); i++) {
      const Region &r = regions_[i];
      if (r.executable && addr >= r.start && addr < r.end) return true;
    }
    return false;
  }

  bool ptrAt(uintptr_t addr, uintptr_t *out) const {
    if (addr & 7) return false;
    if (!readable(addr, sizeof(uintptr_t))) return false;
    *out = *reinterpret_cast<const volatile uintptr_t *>(addr);
    return true;
  }

  // One pass over the data mappings collecting every 8-byte aligned slot whose
  // value equals one of `wanted`. The RTTI chain is walked with three such
  // passes, so keeping them batched is what makes lookup fast enough to run on
  // the load path (~180 MB of data segments per pass).
  void findAll(const std::vector<uintptr_t> &wanted, std::vector<Hit> *hits) const {
    if (wanted.empty()) return;
    for (size_t i = 0; i < regions_.size(); i++) {
      const Region &r = regions_[i];
      if (r.executable) continue;  // code never stores these pointers as qwords
      uintptr_t from = (r.start + 7) & ~static_cast<uintptr_t>(7);
      for (uintptr_t p = from; p + 8 <= r.end; p += 8) {
        uintptr_t value = *reinterpret_cast<const volatile uintptr_t *>(p);
        for (size_t k = 0; k < wanted.size(); k++) {
          if (value == wanted[k]) {
            Hit hit;
            hit.wanted = k;
            hit.slot = p;
            hits->push_back(hit);
          }
        }
      }
    }
  }

  // Every occurrence of `pattern` in every region. This has to cross executable
  // mappings: Mojang's .so merges .rodata into the same PT_LOAD as .text, so on
  // the device the type-name strings live inside a single `r-xp` mapping
  // (70f663b000-710827f000 covers file offsets 0..0x11c44000).
  //
  // Deliberately no resumable cursor. The previous single-hit form reported
  // "end of the region that matched" as the next cursor, so a caller looping it
  // collected AT MOST ONE copy per region - and in 1.26.45.1 the first copy of
  // "11LocalPlayer" is a substring of a longer mangled name. The standalone
  // filter rejected it, the name list stayed empty, and LocalPlayer, Camera and
  // BlockSource looked unresolvable although their RTTI is intact. One pass here
  // also replaces what used to be up to sixteen full passes.
  void findBytesAll(const char *pattern, size_t length, std::vector<uintptr_t> *out,
                    size_t cap) const {
    if (pattern == NULL || length == 0 || out == NULL) return;
    for (size_t i = 0; i < regions_.size() && out->size() < cap; i++) {
      const Region &r = regions_[i];
      if (r.end - r.start < length) continue;
      const char *p = reinterpret_cast<const char *>(r.start);
      const char *last = reinterpret_cast<const char *>(r.end - length);
      while (p <= last) {
        const char *hit = static_cast<const char *>(memchr(p, pattern[0], (last - p) + 1));
        if (hit == NULL) break;
        if (memcmp(hit, pattern, length) == 0) {
          out->push_back(reinterpret_cast<uintptr_t>(hit));
          if (out->size() >= cap) return;
        }
        p = hit + 1;
      }
    }
  }

 private:
  const std::vector<Region> &regions_;
};

// Count the virtual functions that follow the header. A vtable ends where the
// slots stop being pointers into executable mappings, which is also where the
// next read-only structure begins.
size_t countSlots(const Scanner &scan, uintptr_t vtable) {
  size_t count = 0;
  uintptr_t fn = 0;
  while (scan.ptrAt(vtable + kHeaderSize + count * 8, &fn) && scan.executable(fn)) count++;
  return count;
}

// Keep the standalone "<len><name>" copies out of one findBytesAll pass: reject
// the tail of a longer length-prefixed component (`114ClientInstance` seen one
// byte late) and anything not terminated there. `loose` is the last-resort pass
// findVirtualSlotForFunction uses when suffix merging leaves no standalone copy
// behind: accept any copy that is not a string start of its own.
void collectTypeNames(const Scanner &scan, const std::vector<uintptr_t> &raw,
                      size_t needleLength, std::vector<uintptr_t> *names, size_t cap,
                      bool loose) {
  for (size_t i = 0; i < raw.size() && names->size() < cap; i++) {
    uintptr_t name = raw[i];
    char prev = ' ';
    if (scan.readable(name - 1, 1)) prev = *reinterpret_cast<const volatile char *>(name - 1);
    if (loose) {
      if (prev == '\0') continue;  // the strict pass already kept those
      names->push_back(name);
      continue;
    }
    if (isdigit(static_cast<unsigned char>(prev))) continue;
    char next = 'x';
    if (scan.readable(name + needleLength, 1))
      next = *reinterpret_cast<const volatile char *>(name + needleLength);
    if (next != '\0') continue;
    names->push_back(name);
  }
}

}  // namespace

static std::vector<Region> parseRegions(const std::string &libName) {
  std::vector<Region> regions;
  std::ifstream maps("/proc/self/maps");
  if (!maps.is_open()) {
    LOGE("RttiLookup: cannot open /proc/self/maps");
    return regions;
  }
  std::string line;
  while (std::getline(maps, line)) {
    Region region;
    std::string path;
    if (!parseLine(line, &region, &path)) continue;
    if (!libName.empty() && path.find(libName) == std::string::npos) continue;
    regions.push_back(region);
  }
  return regions;
}

std::vector<Region> regionsOf(const std::string &libName) {
  // A library's own mappings are fixed for the process lifetime (the linker
  // maps it once and never moves it), so the /proc/self/maps walk is memoised
  // per name. This is on the hot path of every validity predicate — inExec,
  // inGuestLib, looksLikeCppObject — and each of those used to re-read and
  // re-parse the whole maps file per call. Only non-empty names are cached:
  // regionsOf("") means "every readable mapping", which does change as the
  // scudo heap grows.
  if (!libName.empty()) {
    static std::mutex g_cacheMutex;
    static std::map<std::string, std::vector<Region> > g_cache;
    std::lock_guard<std::mutex> guard(g_cacheMutex);
    std::map<std::string, std::vector<Region> >::const_iterator it = g_cache.find(libName);
    if (it != g_cache.end()) return it->second;
    std::vector<Region> parsed = parseRegions(libName);
    // An empty answer means "not mapped YET" as often as it means "never" — the
    // preloader runs before the guest is relocated — so only a non-empty walk is
    // worth remembering. Caching the empty case would freeze every validity
    // predicate in this process at "nothing is the guest library".
    if (!parsed.empty()) g_cache.insert(std::make_pair(libName, parsed));
    return parsed;
  }
  return parseRegions(libName);
}

bool inRegions(const std::vector<Region> &regions, uintptr_t addr, bool executableOnly) {
  for (size_t i = 0; i < regions.size(); i++) {
    const Region &r = regions[i];
    if (executableOnly && !r.executable) continue;
    if (addr >= r.start && addr < r.end) return true;
  }
  return false;
}

bool inWritableRange(uintptr_t addr, size_t len) {
#if defined(__aarch64__)
  // Scudo tags the top byte of every heap pointer (0xb4000070...). The hardware
  // ignores that byte on access (TBI) but /proc/self/maps lists the UNTAGGED
  // range, so a raw compare never matches and every bounded read silently fails.
  addr &= 0x00ffffffffffffffULL;
#endif
  if (addr == 0 || len == 0) return false;
  if (addr + len < addr) return false;  // wrapped: not a range anyone mapped

  struct WRange { uintptr_t s, e; };
  constexpr uint64_t kReparseMs = 250;
  static std::mutex g_mutex;
  static std::vector<WRange> ranges;
  static uint64_t lastParseMs = 0;
  static bool parsed = false;
  std::lock_guard<std::mutex> guard(g_mutex);

  const uint64_t nowMs = static_cast<uint64_t>(
      std::chrono::duration_cast<std::chrono::milliseconds>(
          std::chrono::steady_clock::now().time_since_epoch())
          .count());
  if (!parsed || nowMs - lastParseMs >= kReparseMs) {
    ranges.clear();
    std::ifstream maps("/proc/self/maps");
    std::string line;
    while (std::getline(maps, line)) {
      uintptr_t s = 0, e = 0;
      char perms[5] = {};
      if (sscanf(line.c_str(), "%" SCNxPTR "-%" SCNxPTR " %4s", &s, &e, perms) != 3)
        continue;
      if (perms[0] == 'r' && perms[1] == 'w') ranges.push_back(WRange{s, e});
    }
    lastParseMs = nowMs;
    parsed = true;
  }
  for (size_t i = 0; i < ranges.size(); i++)
    if (addr >= ranges[i].s && addr + len <= ranges[i].e) return true;
  return false;
}

uintptr_t findVtable(const std::string &libName, const std::string &mangledName,
                     size_t *slotCount) {
  if (slotCount != NULL) *slotCount = 0;

  std::vector<Region> regions = regionsOf(libName);
  if (regions.empty()) {
    LOGW("RttiLookup: %s is not mapped in this process", libName.c_str());
    return 0;
  }
  Scanner scan(regions);

  std::ostringstream encoded;
  encoded << mangledName.size() << mangledName;
  const std::string needle = encoded.str();

  // Step 1: every standalone copy of the mangled type name.
  std::vector<uintptr_t> raw, names;
  scan.findBytesAll(needle.c_str(), needle.size(), &raw, 4096);
  collectTypeNames(scan, raw, needle.size(), &names, 16, false);
  if (names.empty()) {
    LOGW("RttiLookup: type name %s not found in %s", needle.c_str(), libName.c_str());
    return 0;
  }

  // Step 2: the slot holding the name lives inside the typeinfo object.
  std::vector<Hit> nameSlots;
  scan.findAll(names, &nameSlots);
  std::vector<uintptr_t> typeinfos;
  for (size_t i = 0; i < nameSlots.size(); i++) typeinfos.push_back(nameSlots[i].slot - kNameDelta);
  if (typeinfos.empty()) {
    LOGW("RttiLookup: nothing points at type name %s", needle.c_str());
    return 0;
  }

  // Step 3: the slot holding the typeinfo lives inside a vtable.
  std::vector<Hit> tiSlots;
  scan.findAll(typeinfos, &tiSlots);

  uintptr_t best = 0;
  size_t bestSlots = 0;
  for (size_t i = 0; i < tiSlots.size(); i++) {
    uintptr_t vtable = tiSlots[i].slot - kVtableDelta;
    uintptr_t typeinfo = typeinfos[tiSlots[i].wanted];
    uintptr_t offsetToTop = 0, backRef = 0;
    if (!scan.ptrAt(vtable, &offsetToTop)) continue;
    if (!scan.ptrAt(vtable + kNameDelta, &backRef) || backRef != typeinfo) continue;
    size_t slots = countSlots(scan, vtable);
    if (slots == 0) continue;
    LOGD("RttiLookup: %s -> typeinfo %p, vtable %p (offset_to_top %lu, %zu slots)",
         needle.c_str(), reinterpret_cast<void *>(typeinfo), reinterpret_cast<void *>(vtable),
         (unsigned long) offsetToTop, slots);
    // The primary vtable (offset_to_top == 0) is the one whose slot numbering
    // matches the class declaration; among those, the longest run is the class
    // body rather than an empty forwarding table.
    if (offsetToTop == 0 && slots > bestSlots) {
      best = vtable;
      bestSlots = slots;
    } else if (best == 0 && offsetToTop != 0) {
      best = vtable;
      bestSlots = slots;
    }
  }

  if (best == 0) {
    LOGW("RttiLookup: no validated vtable for %s in %s", mangledName.c_str(), libName.c_str());
  } else if (slotCount != NULL) {
    *slotCount = bestSlots;
  }
  return best;
}

uintptr_t slotAt(uintptr_t vtable, size_t index) {
  if (vtable == 0) return 0;
  return *reinterpret_cast<const volatile uintptr_t *>(vtable + kHeaderSize + index * 8);
}

uintptr_t findMemberByVtable(uintptr_t object, uintptr_t vtable, size_t maxOffset) {
  if (object == 0 || vtable == 0) return 0;
  for (size_t off = 0; off <= maxOffset && sizeof(uintptr_t) <= maxOffset - off;
       off += sizeof(uintptr_t)) {
    if (off > UINTPTR_MAX - object ||
        !inWritableRange(object + off, sizeof(uintptr_t))) break;
    uintptr_t value = 0;
    memcpy(&value, reinterpret_cast<const void *>(object + off), sizeof(value));
    if (value == vtable) return object + off;
  }
  return 0;
}

// Every mapping in this process, sorted and merged. Unlike parseLine() this
// keeps the non-readable ones too, because for the questions below they occupy
// address space just the same.
static bool allSpans(std::vector<uintptr_t> *starts, std::vector<uintptr_t> *ends) {
  std::ifstream maps("/proc/self/maps");
  if (!maps) return false;
  std::string line;
  std::vector<Region> raw;
  while (std::getline(maps, line)) {
    uintptr_t start = 0, end = 0;
    std::istringstream stream(line);
    std::string range;
    if (!(stream >> range)) continue;
    if (sscanf(range.c_str(), "%" SCNxPTR "-%" SCNxPTR, &start, &end) != 2) continue;
    if (end <= start) continue;
    raw.push_back(Region{start, end, false});
  }
  std::sort(raw.begin(), raw.end(), [](const Region &a, const Region &b) {
    return a.start < b.start;
  });
  for (size_t i = 0; i < raw.size(); i++) {
    uintptr_t start = raw[i].start, end = raw[i].end;
    while (i + 1 < raw.size() && raw[i + 1].start <= end) {
      if (raw[i + 1].end > end) end = raw[i + 1].end;
      i++;
    }
    starts->push_back(start);
    ends->push_back(end);
  }
  return !starts->empty();
}

bool islandReachable(uintptr_t target, std::string *reason) {
  // An AArch64 direct branch encodes a 26-bit word offset, so shadowhook's
  // trampoline island has to land within 128 MB of the address being hooked.
  const uintptr_t reach = 1u << 27;
  const uintptr_t pageSize = static_cast<uintptr_t>(sysconf(_SC_PAGESIZE));
  if (pageSize == 0) {
    if (reason != NULL) *reason = "no page size";
    return false;
  }
  std::vector<uintptr_t> starts, ends;
  if (!allSpans(&starts, &ends)) {
    if (reason != NULL) *reason = "cannot read /proc/self/maps";
    return false;
  }
  size_t own = starts.size();
  for (size_t i = 0; i < starts.size(); i++) {
    if (target >= starts[i] && target < ends[i]) own = i;
  }
  const uintptr_t from = target > reach ? target - reach : 0;
  uintptr_t cursor = from;
  for (size_t i = 0; i < starts.size(); i++) {
    if (ends[i] <= cursor) continue;
    if (starts[i] >= cursor + pageSize) break;  // a hole opens here
    cursor = ends[i];
    if (cursor + pageSize > target + reach) break;
  }
  if (cursor + pageSize <= target + reach && cursor >= from) {
    if (reason != NULL) {
      std::ostringstream detail;
      detail << "a free page exists at 0x" << std::hex << cursor;
      *reason = detail.str();
    }
    return true;
  }
  std::ostringstream detail;
  if (own < starts.size()) {
    detail << "0x" << std::hex << target << " sits inside the mapping 0x" << starts[own] << "-0x"
           << ends[own] << " (" << std::dec << ((ends[own] - starts[own]) >> 20)
           << " MB) and every page within +/-128 MB is already mapped, so an inline hook has "
              "nowhere to put its trampoline island";
  } else {
    detail << "every page within +/-128 MB of 0x" << std::hex << target << " is already mapped";
  }
  if (reason != NULL) *reason = detail.str();
  return false;
}

// Protection bits currently granted to the mapping that covers `addr`, read
// from /proc/self/maps so a patch can hand the page back exactly as it found
// it instead of guessing (a vtable sits in a read-only RELRO page, but the same
// helper has to be safe for anything else we might want to point at).
static int protectionOf(uintptr_t addr) {
  std::ifstream maps("/proc/self/maps");
  std::string line;
  while (std::getline(maps, line)) {
    uintptr_t start = 0, end = 0;
    std::istringstream stream(line);
    std::string range, perms;
    if (!(stream >> range >> perms)) continue;
    if (sscanf(range.c_str(), "%" SCNxPTR "-%" SCNxPTR, &start, &end) != 2) continue;
    if (addr < start || addr >= end) continue;
    if (perms.size() < 3) return 0;
    return (perms[0] == 'r' ? PROT_READ : 0) | (perms[1] == 'w' ? PROT_WRITE : 0) |
           (perms[2] == 'x' ? PROT_EXEC : 0);
  }
  return 0;
}

// For a virtual function whose body cannot be patched, locate the vtable slot
// that dispatches to it. See the header for the contract.
// Does the function at `candidate` contain a direct BL to `target` in its first
// `window` bytes? A deleting destructor is exactly that shape: prologue, one BL
// to the complete-object dtor, epilogue, RET. Scanning only a short window keeps
// this cheap and specific — an unrelated body rarely calls the target within its
// first 512 bytes AND ends in RET right after.
static bool callsFunction(const Scanner &scan, uintptr_t candidate, uintptr_t target) {
  if (candidate == 0 || candidate == target) return false;
  const size_t window = 512;
  for (size_t off = 0; off + 4 <= window; off += 4) {
    uintptr_t addr = candidate + off;
    if (!scan.readable(addr, 4)) break;
    uint32_t w = *reinterpret_cast<const volatile uint32_t *>(addr);
    if ((w & 0xFC000000u) != 0x94000000u) continue;  // not BL imm26
    int32_t offset = static_cast<int32_t>(w << 6) >> 4;  // sign-extended words
    if (static_cast<uintptr_t>((ptrdiff_t) addr + offset) == target) return true;
  }
  return false;
}

bool findVirtualSlotForFunction(const std::string &libName, const std::string &className,
                                uintptr_t fnAddress, uintptr_t *vtable, size_t *slotIndex) {
  LOGD("RttiLookup: %s probe ENTER (lib %s, target 0x%lx)", className.c_str(), libName.c_str(),
       (unsigned long) fnAddress);
  if (fnAddress == 0) return false;
  std::vector<Region> regions = regionsOf(libName);
  LOGD("RttiLookup: %s probe: %zu regions", className.c_str(), regions.size());
  if (regions.empty()) return false;
  Scanner scan(regions);

  // Step 1: every standalone copy of "<len><class>", the same spelling
  // findVtable validates. Destructor variants keep their own typeinfo objects
  // but share this identifier component, so one probe covers them all.
  std::ostringstream encoded;
  encoded << className.size() << className;
  const std::string needle = encoded.str();

  std::vector<uintptr_t> raw, names;
  scan.findBytesAll(needle.c_str(), needle.size(), &raw, 4096);
  collectTypeNames(scan, raw, needle.size(), &names, 64, false);
  if (names.empty()) {
    // Retry WITHOUT the standalone filters: Mojang's linker suffix-merging can
    // glue our component inside a longer string on EITHER side (a preceding
    // digit tail like "...19" before "9PauseMenu", or a following member name),
    // and GuiData proves the real __name pointer may sit mid-string ("A7GuiData").
    collectTypeNames(scan, raw, needle.size(), &names, 64, true);
  }
  LOGD("RttiLookup: %s probe: step 1 kept %zu name copies of '%s' (%zu raw)", className.c_str(),
       names.size(), needle.c_str(), raw.size());
  if (names.empty()) return false;

  // Step 2: the slot holding the name lives inside the typeinfo object.
  std::vector<Hit> nameSlots;
  scan.findAll(names, &nameSlots);
  std::vector<uintptr_t> typeinfos;
  for (size_t i = 0; i < nameSlots.size(); i++) typeinfos.push_back(nameSlots[i].slot - kNameDelta);
  if (typeinfos.empty()) return false;

  // Step 3: the slot holding the typeinfo lives inside a vtable. Unlike
  // findVtable there is no "best" table to pick: check every validated one for
  // a slot equal to fnAddress, because the matched function may sit in ANY of
  // the class's tables (primary vs. destructor variants).
  std::vector<Hit> tiSlots;
  scan.findAll(typeinfos, &tiSlots);
  LOGD("RttiLookup: %s probe: %zu name copies, %zu typeinfos, %zu tables, target 0x%lx",
       className.c_str(), names.size(), typeinfos.size(), tiSlots.size(),
       (unsigned long) fnAddress);
  for (size_t i = 0; i < tiSlots.size(); i++) {
    uintptr_t table = tiSlots[i].slot - kVtableDelta;
    uintptr_t offsetToTop = 0, backRef = 0;
    if (!scan.ptrAt(table, &offsetToTop)) continue;
    if (!scan.ptrAt(table + kNameDelta, &backRef) || backRef != typeinfos[tiSlots[i].wanted])
      continue;
    size_t slots = countSlots(scan, table);
    LOGD("RttiLookup: %s candidate table %p (offset_to_top %lu, %zu slots)", className.c_str(),
         reinterpret_cast<void *>(table), (unsigned long) offsetToTop, slots);
    for (size_t s = 0; s < slots; s++) {
      uintptr_t value = 0;
      if (!scan.ptrAt(table + kHeaderSize + s * 8, &value)) continue;
      // The matched function may sit in this slot directly...
      if (value == fnAddress) {
        if (vtable != NULL) *vtable = table;
        if (slotIndex != NULL) *slotIndex = s;
        LOGD("RttiLookup: %s slot %zu dispatches to 0x%lx (table %p, offset_to_top %lu)",
             className.c_str(), s, (unsigned long) fnAddress, reinterpret_cast<void *>(table),
             (unsigned long) offsetToTop);
        return true;
      }
      // ...or be reached THROUGH this slot: the Itanium ABI gives every virtual
      // destructor two bodies, and the vtable stores only the deleting dtor while
      // the signature names the complete-object one. Recognise that wrapper by
      // its prologue — SUB SP / STP x19,x30 / ... / BL <fn> / ... / RET — which
      // is exactly what LongCall::hook already refuses to relocate (the BL).
      if (callsFunction(scan, value, fnAddress)) {
        if (vtable != NULL) *vtable = table;
        if (slotIndex != NULL) *slotIndex = s;
        LOGD("RttiLookup: %s slot %zu is the deleting wrapper 0x%lx around 0x%lx "
             "(table %p, offset_to_top %lu)", className.c_str(), s, (unsigned long) value,
             (unsigned long) fnAddress, reinterpret_cast<void *>(table),
             (unsigned long) offsetToTop);
        return true;
      }
    }
  }
  return false;
}


size_t slotCountOf(const std::string &libName, uintptr_t vtableHeader) {
  if (vtableHeader == 0) return 0;
  std::vector<Region> regions = regionsOf(libName);
  if (regions.empty()) return 0;
  Scanner scan(regions);
  return countSlots(scan, vtableHeader);
}


// Enumerate every validated vtable of a class (see the header). Shares the
// chain walk of findVtable; the only difference is that no "best" table is
// picked — each validated candidate is reported, primary first.
size_t findVtables(const std::string &libName, const std::string &mangledName,
                   uintptr_t *outTables, size_t capacity) {
  if (outTables == NULL || capacity == 0) return 0;
  size_t found = 0;

  std::vector<Region> regions = regionsOf(libName);
  if (regions.empty()) return 0;
  Scanner scan(regions);

  std::ostringstream encoded;
  encoded << mangledName.size() << mangledName;
  const std::string needle = encoded.str();

  std::vector<uintptr_t> raw, names;
  scan.findBytesAll(needle.c_str(), needle.size(), &raw, 4096);
  collectTypeNames(scan, raw, needle.size(), &names, 16, false);
  if (names.empty()) return 0;

  std::vector<Hit> nameSlots;
  scan.findAll(names, &nameSlots);
  std::vector<uintptr_t> typeinfos;
  for (size_t i = 0; i < nameSlots.size(); i++) typeinfos.push_back(nameSlots[i].slot - kNameDelta);
  if (typeinfos.empty()) return 0;

  std::vector<Hit> tiSlots;
  scan.findAll(typeinfos, &tiSlots);
  for (size_t i = 0; i < tiSlots.size() && found < capacity; i++) {
    uintptr_t table = tiSlots[i].slot - kVtableDelta;
    uintptr_t offsetToTop = 0, backRef = 0;
    if (!scan.ptrAt(table, &offsetToTop)) continue;
    if (!scan.ptrAt(table + kNameDelta, &backRef) || backRef != typeinfos[tiSlots[i].wanted])
      continue;
    if (countSlots(scan, table) == 0) continue;
    // De-duplicate: several typeinfo copies can point at one table.
    bool seen = false;
    for (size_t k = 0; k < found; k++)
      if (outTables[k] == table) { seen = true; break; }
    if (seen) continue;
    outTables[found++] = table;
  }
  // Primary table first so callers can index [0] as "the" table when they want
  // findVtable's answer without walking twice.
  for (size_t i = 1; i < found; i++) {
    uintptr_t top = 0;
    if (!scan.ptrAt(outTables[i], &top) || top != 0) continue;
    for (size_t k = 0; k < i; k++) {
      uintptr_t kt = 0;
      if (scan.ptrAt(outTables[k], &kt) && kt == 0) continue;
      uintptr_t tmp = outTables[i]; outTables[i] = outTables[k]; outTables[k] = tmp;
      break;
    }
  }
  return found;
}

bool patchVtableSlot(uintptr_t vtable, size_t index, uintptr_t replacement,
                     uintptr_t *original) {
  if (vtable == 0) return false;
  return patchSlot(vtable + kHeaderSize + index * 8, replacement, original);
}

bool patchSlot(uintptr_t slotAddress, uintptr_t replacement, uintptr_t *original) {
  if (slotAddress & 7) return false;
  const uintptr_t pageSize = static_cast<uintptr_t>(sysconf(_SC_PAGESIZE));
  if (pageSize == 0) return false;
  const int previous = protectionOf(slotAddress);
  if (previous == 0) {
    LOGE("RttiLookup: 0x%lx is not inside any mapping", (unsigned long) slotAddress);
    return false;
  }
  void *page = reinterpret_cast<void *>(slotAddress & ~(pageSize - 1));
  if (mprotect(page, pageSize, previous | PROT_WRITE) != 0) {
    LOGE("RttiLookup: mprotect(%p) for a vtable slot failed: %s", page, strerror(errno));
    return false;
  }
  volatile uintptr_t *slot = reinterpret_cast<volatile uintptr_t *>(slotAddress);
  if (original != NULL) *original = *slot;
  *slot = replacement;
  __atomic_thread_fence(__ATOMIC_SEQ_CST);
  if (mprotect(page, pageSize, previous) != 0) {
    LOGW("RttiLookup: could not restore the protection of %p: %s", page, strerror(errno));
  }
  return true;
}

}  // namespace RttiLookup
