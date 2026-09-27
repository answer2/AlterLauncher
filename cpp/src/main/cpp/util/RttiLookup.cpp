#include <util/RttiLookup.h>

#include <ctype.h>
#include <errno.h>
#include <cstdio>
#include <fstream>
#include <sstream>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

#include <algorithm>

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
  if (sscanf(range.c_str(), "%lx-%lx", &start, &end) != 2) return false;
  if (perms.size() < 4 || perms[0] != 'r') return false;
  out->start = start;
  out->end = end;
  out->executable = perms[2] == 'x';
  std::string rest;
  std::getline(stream, rest);
  size_t name = rest.find('/');
  if (name == std::string::npos) return false;
  *path = rest.substr(name);
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
      if (addr >= r.start && addr + size <= r.end) return true;
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

  // Next occurrence of `pattern` at or after `after`, honouring region bounds.
  // Unlike findAll this has to cross executable mappings: Mojang's .so merges
  // .rodata into the same PT_LOAD as .text, so on the device the type-name
  // strings live inside a single `r-xp` mapping (70f663b000-710827f000 covers
  // file offsets 0..0x11c44000). Skipping those finds nothing at all.
  // Returns the match address and, through *resumeAt, the cursor the NEXT call
  // must use. The cursor cannot be derived from the returned address alone:
  // regions are not contiguous in the order scanned, so resuming at hit+1 would
  // re-enter the same region from its start on the next call whenever a later
  // region matched first — an infinite scan loop capped only by the caller's
  // own iteration limit.
  uintptr_t findBytes(const char *pattern, size_t length, uintptr_t after,
                      uintptr_t *resumeAt) const {
    uintptr_t resume = 0;
    for (size_t i = 0; i < regions_.size(); i++) {
      const Region &r = regions_[i];
      uintptr_t begin = r.start > after ? r.start : after;
      if (begin >= r.end) continue;
      if (begin + length > r.end) {
        resume = std::max(resume, r.end);  // fully consumed for this pattern
        continue;
      }
      resume = std::max(resume, r.end);
      const char *p = reinterpret_cast<const char *>(begin);
      const char *last = reinterpret_cast<const char *>(r.end - length);
      while (p <= last) {
        const char *hit = static_cast<const char *>(memchr(p, pattern[0], (last - p) + 1));
        if (hit == NULL) break;
        if (memcmp(hit, pattern, length) == 0) {
          if (resumeAt != NULL) *resumeAt = resume;
          return reinterpret_cast<uintptr_t>(hit);
        }
        p = hit + 1;
      }
    }
    if (resumeAt != NULL) *resumeAt = resume;
    return 0;
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

}  // namespace

std::vector<Region> regionsOf(const std::string &libName) {
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

bool inRegions(const std::vector<Region> &regions, uintptr_t addr, bool executableOnly) {
  for (size_t i = 0; i < regions.size(); i++) {
    const Region &r = regions[i];
    if (executableOnly && !r.executable) continue;
    if (addr >= r.start && addr < r.end) return true;
  }
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
  std::vector<uintptr_t> names;
  uintptr_t cursor = 0;
  while (names.size() < 16) {
    uintptr_t resumeAt = 0;
    uintptr_t name = scan.findBytes(needle.c_str(), needle.size(), cursor, &resumeAt);
    if (name == 0) break;
    cursor = std::max(cursor, resumeAt);
    char prev = ' ';
    if (scan.readable(name - 1, 1)) prev = *reinterpret_cast<const volatile char *>(name - 1);
    // Reject only the tail of a longer length-prefixed component (`114ClientInstance`
    // is `14ClientInstance` seen one byte late). A NUL before the name is NOT
    // required: the linker's identical-suffix merging puts real type names in the
    // middle of longer strings — GuiData's __name points at the `7GuiData` inside
    // `A7GuiData`, so insisting on a string start would lose it.
    if (isdigit(static_cast<unsigned char>(prev))) continue;
    char next = 'x';
    if (scan.readable(name + needle.size(), 1)) {
      next = *reinterpret_cast<const volatile char *>(name + needle.size());
    }
    if (next != '\0') continue;  // substring of a sentence, not an encoding
    names.push_back(name);
  }
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
  for (size_t off = 0; off + 8 <= maxOffset; off += 8) {
    uintptr_t value = *reinterpret_cast<const volatile uintptr_t *>(object + off);
    if (value == vtable) return object + off;
  }
  return 0;
}

// Every mapping in this process, sorted and merged. Unlike parseLine() this
// keeps the anonymous and non-readable ones too, because for the questions
// below they occupy address space just the same.
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
    if (sscanf(range.c_str(), "%lx-%lx", &start, &end) != 2) continue;
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
    if (sscanf(range.c_str(), "%lx-%lx", &start, &end) != 2) continue;
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

  std::vector<uintptr_t> names;
  uintptr_t cursor = 0;
  while (names.size() < 64) {
    uintptr_t resumeAt = 0;
    uintptr_t name = scan.findBytes(needle.c_str(), needle.size(), cursor, &resumeAt);
    if (name == 0) break;
    cursor = std::max(cursor, resumeAt);
    char prev = ' ';
    if (scan.readable(name - 1, 1)) prev = *reinterpret_cast<const volatile char *>(name - 1);
    if (isdigit(static_cast<unsigned char>(prev))) {
      LOGD("RttiLookup: %s: name copy at 0x%lx rejected, prev byte is digit '%c'", className.c_str(),
           (unsigned long) name, prev);
      continue;  // tail of a longer number
    }
    char next = 'x';
    if (scan.readable(name + needle.size(), 1)) {
      next = *reinterpret_cast<const volatile char *>(name + needle.size());
    }
    if (next != '\0') {
      LOGD("RttiLookup: %s: name copy at 0x%lx rejected, next byte is '%c' (0x%02x)",
           className.c_str(), (unsigned long) name, (next >= 32 && next < 127) ? next : '?',
           (unsigned) next);
      continue;  // substring of a sentence or a longer name
    }
    names.push_back(name);
  }
  if (names.empty()) {
    // Retry WITHOUT the standalone filters: Mojang's linker suffix-merging can
    // glue our component inside a longer string on EITHER side (a preceding
    // digit tail like "...19" before "9PauseMenu", or a following member name),
    // and GuiData proves the real __name pointer may sit mid-string ("A7GuiData").
    cursor = 0;
    while (names.size() < 64) {
      uintptr_t resumeAt = 0;
      uintptr_t name = scan.findBytes(needle.c_str(), needle.size(), cursor, &resumeAt);
      if (name == 0) break;
      cursor = std::max(cursor, resumeAt);
      char prev = ' ';
      if (scan.readable(name - 1, 1)) prev = *reinterpret_cast<const volatile char *>(name - 1);
      if (prev == '\0') continue;  // already accepted above as standalone
      LOGD("RttiLookup: %s: loose name copy candidate at 0x%lx (prev '%c'), accepting",
           className.c_str(), (unsigned long) name, (prev >= 32 && prev < 127) ? prev : '?');
      names.push_back(name);
    }
  }
  LOGD("RttiLookup: %s probe: step 1 kept %zu name copies of '%s'", className.c_str(),
       names.size(), needle.c_str());
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
