#ifndef ALTER_HOOK_ENGINE_H
#define ALTER_HOOK_ENGINE_H

// One seam over the two inline-hook frameworks present in the process:
// shadowhook (what the launcher always used) and GlossHook (what the Levi
// preloader — and through it every native mod — carries inside
// libpreloader.so). Both patch the linker's soinfo::call_constructors, and
// two frameworks fighting over that one function is what produced the
// reload-loop SIGSEGV; selecting the Gloss backend removes the second
// framework entirely, while the shadowhook backend keeps the claim-order
// workaround in main.cpp. Every hook in this project goes through hookAddr
// so the choice is made in exactly one place.
namespace HookEngine {

enum Backend { kShadowhook = 0, kGloss = 1 };

// Chosen before init(); main.cpp reads it from the launcher settings.
void select(Backend backend);
Backend selected();

// Must run before the first hookAddr(). `modsOn` mirrors the native-mods
// switch: the Gloss backend then initialises GlossHook WITH its linker
// claim (the preloader's later GlossInit is first-call-only and would
// otherwise never install it); without mods the plain init suffices.
// Returns false when the framework could not be brought up.
bool init(bool modsOn);

// Inline-hook `target` so calls enter `replace`. On success returns a
// non-null handle and, if `original` is non-null, writes the entry point
// to keep calling the pre-hook function there. Null on failure; see
// lastError(). Signature-compatible with shadowhook_hook_sym_addr and
// GlossHook, which is the point.
void *hookAddr(void *target, void *replace, void **original);

// Same, for a raw function address that is not necessarily a dynamic symbol
// (game functions reached by signature). shadowhook distinguishes the two
// (hook_func_addr vs hook_sym_addr); GlossHook does not, so on the Gloss
// backend this is identical to hookAddr.
void *hookFuncAddr(void *target, void *replace, void **original);

// Message for the most recent hookAddr()/init() failure.
const char *lastError();

}  // namespace HookEngine

#endif  // ALTER_HOOK_ENGINE_H
