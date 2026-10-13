#ifndef ALTER_LAUNCHER_RTTI_LOOKUP_H
#define ALTER_LAUNCHER_RTTI_LOOKUP_H

#include <stddef.h>
#include <stdint.h>

#include <string>
#include <vector>

// Locates a class's primary vtable inside an already-loaded library by walking
// the Itanium RTTI chain in memory:
//
//   mangled type name  ->  __*_class_type_info  ->  vtable
//
// The guest binary is stripped of .symtab and Mojang recompiles its functions
// on every release, so byte signatures rot within a few builds; the class names
// baked into the RTTI do not. That makes this the only handle on the game that
// survives an update without re-deriving anything.
//
// No dlopen is involved. libalternative_core.so lives in the launcher's linker
// namespace and cannot dlopen libminecraftpe.so at all, but the guest's pages
// are mapped into this same address space, so reading them directly works and
// creates no second soinfo.
namespace RttiLookup {

struct Region {
    uintptr_t start;
    uintptr_t end;
    bool executable;
};

// Every readable mapping whose /proc/self/maps path contains `libName`.
std::vector<Region> regionsOf(const std::string &libName);

// True when `addr` lies inside one of `regions`.
bool inRegions(const std::vector<Region> &regions, uintptr_t addr, bool executableOnly);

// Checks a 250 ms RW mapping snapshot; callers must still guarantee object lifetime.
bool inWritableRange(uintptr_t addr, size_t len);

// Convert a header-start lookup result to the address point stored in an
// object's vptr. Null lookup results stay null. This expresses the two-pointer
// header convention; it does not establish support for another guest ABI.
inline uintptr_t addressPoint(uintptr_t header) {
    return header == 0 ? 0 : header + 2 * sizeof(uintptr_t);
}

// Find the primary vtable of a class by its identifier — the bare class name,
// e.g. "ClientInstance"; the decimal length prefix is added here. Returns 0 when
// no candidate validates. When found, `*slotCount` receives the number of
// consecutive function pointers that follow the two-slot header. The returned
// address is the HEADER START, not the address point stored in an object.
uintptr_t findVtable(const std::string &libName, const std::string &mangledName,
                     size_t *slotCount);

// vtable[index] with index 0 = the first virtual function. The Itanium layout
// puts offset_to_top at +0 and the typeinfo pointer at +8.
uintptr_t slotAt(uintptr_t vtable, size_t index);

// Replace the pointer stored at `slotAddress` (a vtable slot) with
// `replacement`, and report the previous value through `original`. The data
// segment holding vtables is mapped read-only after RELRO, so this temporarily
// makes the page writable and restores the protection afterwards.
//
// This is how the game's virtual functions get patched. Inline hooking them is
// not an option here — see islandReachable(). A vtable patch needs no
// trampoline at all, and the mapping is MAP_PRIVATE, so the change is confined
// to this process.
bool patchSlot(uintptr_t slotAddress, uintptr_t replacement, uintptr_t *original);

// Same, addressed as vtable[index] (the +16 header is applied here).
bool patchVtableSlot(uintptr_t vtable, size_t index, uintptr_t replacement,
                     uintptr_t *original);

// Whether shadowhook can inline-hook `target` at all.
//
// An AArch64 direct branch reaches only +/-2^26 words, so shadowhook must place
// its trampoline island on a free page within +/-128 MB of the hooked address.
// libminecraftpe.so is mapped as one enormous r-xp region — Mojang merged every
// loadable segment into a single PT_LOAD, and on 1.26.45.1 that region is 298 MB
// wide — so functions in its middle band have no such page anywhere in range
// and `shadowhook_hook_func_addr` fails with SHADOWHOOK_ERRNO_HOOK_ISLAND_EXIT
// ("Alloc island for exit failed"). This is a property of where the function
// sits, not of the pattern matching, and it is why the game's virtuals are
// patched through their vtable slot instead.
//
// Returns true when a free page exists in the window. `*reason` always receives
// a one-line explanation, for the log.
bool islandReachable(uintptr_t target, std::string *reason);

// Scan inline storage for a word equal to `vtable`, which must be an ADDRESS
// POINT (convert findVtable's header with addressPoint first). Returns
// the member address, or 0. The caller must guarantee the object's lifetime.
uintptr_t findMemberByVtable(uintptr_t object, uintptr_t vtable, size_t maxOffset);

// For a virtual function whose BODY cannot be patched (no island in reach and
// LongCall refuses its prologue), find the vtable slot that dispatches to it.
// `fnAddress` is a signature match on the function entry; `className` is the
// bare class name as findVtable takes it ("PauseMenu"). Walks the same RTTI
// chain, but instead of picking one "best" table it checks EVERY table whose
// typeinfo names this class (primary, complete-object and deleting-destructor
// variants all share the identifier prefix) for a slot equal to fnAddress.
// Returns true and fills *vtable/*slotIndex on the first hit; read-only.
bool findVirtualSlotForFunction(const std::string &libName, const std::string &className,
                                uintptr_t fnAddress, uintptr_t *vtable, size_t *slotIndex);

// How many consecutive function pointers follow the two-slot header of
// `vtableHeader` (a header-start address, as findVtable/findVtables return).
//
// Needed before patching: patchVtableSlot() and slotAt() do no bounds checking,
// and a class does not have to own tables of equal length — findVtables() hands
// back primary, complete-object and base-subobject variants side by side. An
// index that is valid for the primary table can run off the end of a shorter
// aux table and land in whatever read-only structure follows it, so a caller
// that patches every variant has to measure each one. Returns 0 when the
// header is unmapped or the first slot is already not a function.
size_t slotCountOf(const std::string &libName, uintptr_t vtableHeader);

// Same header-start convention as findVtable; returns EVERY validated table whose
// typeinfo names this class — primary (offset_to_top 0), complete-object and
// aux/subobject variants included. GuiData on 1.26.x is a base subobject with
// three live tables; an object pointer observed in the game may carry any of
// them, so identity checks need the full set, not just the longest one.
size_t findVtables(const std::string &libName, const std::string &mangledName,
                   uintptr_t *outTables, size_t capacity);

}  // namespace RttiLookup

#endif  // ALTER_LAUNCHER_RTTI_LOOKUP_H
