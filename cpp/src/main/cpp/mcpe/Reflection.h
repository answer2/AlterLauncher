#ifndef ALTER_LAUNCHER_MCPE_REFLECTION_H
#define ALTER_LAUNCHER_MCPE_REFLECTION_H

#include <stddef.h>
#include <stdint.h>

#include <string>
#include <vector>

// Version-portable field discovery for the guest's classes.
//
// The reference SDKs (D:\逆向分析\1\cpp) hardcode member offsets per game
// version — GuiData at ClientInstance+0x688, Minecraft at +0xB0, LevelRenderer
// at +0xC0, LoopbackPacketSender at +0xD0, and every one of them rots on the
// next Mojang recompile. This module replaces that practice with structural
// probing: a candidate object pointer is only ever reported when its own
// vtable pointer matches a vtable RTTI has validated for the class we are
// looking for. Wrong offsets then simply yield no match instead of a crash,
// which is what makes it safe to run blind against any build.
//
// Everything here is read-only: it never writes guest memory and never calls
// into the game.
namespace Reflection {

// A qword read from `object + offset` that looks like a C++ object:
// 8-aligned, its first word non-null and 8-aligned. No mapping checks here —
// callers pair this with an exec-region or vtable-set validation before
// trusting the value.
uintptr_t readPointer(uintptr_t object, uintptr_t offset);

// True when the object's OWN vtable pointer equals one of `tables`.
bool vtableMatchesAny(uintptr_t object, const uintptr_t *tables, size_t tableCount);

// Scan the first `maxOffset` bytes of `object` (8-byte stride) for a pointer
// whose own first word is one of `tables`. Returns the member address, or 0.
// Every dereference stays inside the ranges given in `writable`; nothing is
// read from unmapped memory. This is the generic form of the GuiData scan
// GameSDK used to do with a single table.
uintptr_t findMember(uintptr_t object, const std::vector<uintptr_t> &tables,
                     size_t maxOffset);

// All members within `maxOffset` whose vtable is in `tables`, ascending by
// offset. GuiData-class bases can appear more than once on a big object, so
// the ladder may want the second opinion cheaply.
std::vector<uintptr_t> findAllMembers(uintptr_t object, const std::vector<uintptr_t> &tables,
                                      size_t maxOffset);

}  // namespace Reflection

#endif  // ALTER_LAUNCHER_MCPE_REFLECTION_H