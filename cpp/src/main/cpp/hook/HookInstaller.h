#ifndef ALTER_NATIVE_HOOK_HOOK_INSTALLER_H
#define ALTER_NATIVE_HOOK_HOOK_INSTALLER_H

#include <cstddef>
#include <cstdint>

// Patching the guest, and nothing else.
//
// Two routes, tried in this order because they fail differently:
//
//   installRttiHooks() walks name -> typeinfo -> vtable in the live mappings and
//   patches a SLOT. A vtable patch needs no trampoline, so it works inside the
//   guest's single 298 MB executable mapping where shadowhook's +/-128 MB island
//   has nowhere to go. It only reaches virtual functions, and a slot index that
//   drifted between builds is survivable because every handler forwards to the
//   original.
//
//   installSignatureHooks() byte-scans for the non-virtual functions (chat
//   wrapper, joinMultiplayerGame, PauseMenu/HudScreen) and patches the BODY,
//   falling back to a long-reach patch when the island is unreachable and to a
//   vtable-slot patch when the body is unrelocatable and the function is virtual
//   after all.
//
// Both are driven by the rule the launcher supplied (hook/RuleState.h); the
// built-in patterns below are the pre-1.26 fallback only.
namespace HookInstaller {

// Patch one function body: island check, long-reach fallback, then the selected
// hook engine. Reports every refusal instead of pretending the patch happened.
bool hookGameFunction(uintptr_t address, void *handler, void **real, const char *name);

void installRttiHooks();
void installSignatureHooks();

}  // namespace HookInstaller

#endif  // ALTER_NATIVE_HOOK_HOOK_INSTALLER_H
