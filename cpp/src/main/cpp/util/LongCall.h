#ifndef ALTER_LAUNCHER_LONG_CALL_H
#define ALTER_LAUNCHER_LONG_CALL_H

#include <stdint.h>

#include <string>

// Inline hooking for functions that shadowhook cannot reach.
//
// An AArch64 direct branch encodes its target as a signed 26-bit word offset, so
// a hook installed with `B`/`BL` can only jump +/-128 MB. shadowhook therefore
// has to place its trampoline island on a free page inside that window, and
// inside libminecraftpe.so there is no such page: Mojang merges every loadable
// segment into one PT_LOAD, which on 1.26.45.1 is a single 284 MiB r-xp mapping,
// so any function between 128 MB and 156 MB into the segment is unreachable
// (`shadowhook_hook_func_addr` fails with SHADOWHOOK_ERRNO_HOOK_ISLAND_EXIT).
// That band is exactly where the interesting functions live: ClientInstance::update
// sits at +148 MB, GuiData::addMessage at +146 MB.
//
// This file replaces the branch with an indirect one. An 8-byte `LDR X16, #+8`
// plus `BR X16` loads a full 64-bit address from the two words that follow, which
// reaches anywhere in the address space, so the handler may live in an anonymous
// page of our own. The cost is that the patch is 16 bytes wide and therefore
// displaces the first four instructions of the target; those are copied into a
// bridge page that ends with the same indirect jump back to target+16, and that
// bridge is what the handler calls as "the original function".
//
// Copying instructions is only sound when none of them is PC-relative, so the
// entry is inspected first and refused with a reason when it is not.
namespace LongCall {

// Install a long-reach hook at `target`, transferring control to `handler`.
// On success `*original` receives the bridge address, callable with exactly the
// signature of the target. `*reason` always describes what happened, for the log.
//
// Not thread safe against another patch of the same 16 bytes, and the hooks are
// installed while the game is still starting up; see the note on write ordering
// in the implementation.
bool hook(uintptr_t target, void *handler, void **original, std::string *reason);

}  // namespace LongCall

#endif  // ALTER_LAUNCHER_LONG_CALL_H
