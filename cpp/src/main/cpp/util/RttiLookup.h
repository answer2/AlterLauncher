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

// Find the primary vtable of a class by its identifier — the bare class name,
// e.g. "ClientInstance"; the decimal length prefix is added here. Returns 0 when
// no candidate validates. When found, `*slotCount` receives the number of
// consecutive function pointers that follow the two-slot header.
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

// Scan the first `maxOffset` bytes of an object for a pointer to a known
// vtable, i.e. find a member of that class without calling anything. Returns
// the member address, or 0. Read-only, so it cannot crash the game.
uintptr_t findMemberByVtable(uintptr_t object, uintptr_t vtable, size_t maxOffset);

}  // namespace RttiLookup

#endif  // ALTER_LAUNCHER_RTTI_LOOKUP_H
