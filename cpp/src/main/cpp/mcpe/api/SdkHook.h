#ifndef ALTER_LAUNCHER_MCPE_SDK_HOOK_H
#define ALTER_LAUNCHER_MCPE_SDK_HOOK_H

// Hooking the functions the SDK has already located.
//
// The SDK itself stays read-only (GameSDK.h: "Nothing here patches anything").
// What this module adds is the bridge from a rule pin to a patch: a rule entry
// `vtable.<key> = Class:slot` already states exactly where a function lives,
// and RttiLookup::patchVtableSlot() already knows how to install a replacement
// without a trampoline. Nothing new had to be invented — the missing piece was
// that nobody connected the two, so a mod could CALL a pinned virtual but never
// intercept one.
//
// Why vtable slots and not shadowhook:
// libminecraftpe.so is mapped as one ~298 MB r-xp region, so an AArch64 direct
// branch cannot reach a trampoline island for functions in its middle band
// (RttiLookup::islandReachable explains the errno). A vtable slot patch needs
// no island at all. That also means this only reaches VIRTUAL functions — for
// a non-virtual one there is no slot to patch and HookInstaller
// ::hookGameFunction() (signature + long-reach) is still the route.
//
// Two things a caller gets wrong if left to do it by hand, both handled here:
//
//   1. A class has MORE THAN ONE vtable. findVtables() reports the primary, the
//      complete-object and every base-subobject variant, and an object observed
//      in the game may carry any of them — this is the same lesson GuiData
//      taught (GameSDK.h: setGuiDataTables). Patching only tablesFor()[0]
//      produces a hook that fires for some instances and silently misses
//      others. Every table is patched, and the count is reported.
//
//   2. Those tables are NOT the same length. An index valid for the primary can
//      run past the end of a shorter subobject table into whatever read-only
//      structure follows. patchVtableSlot() does no bounds checking, so each
//      table is measured with RttiLookup::slotCountOf() first and a too-short
//      one is skipped and reported rather than patched.
//
// Every refusal is logged and returns false. A hook that did not install must
// never look like one that did.
#include <stddef.h>
#include <stdint.h>

#include <string>
#include <vector>

namespace mcpe {

// One installed hook. `originals` is parallel to `tables`: a class whose
// variants shared the slot value has identical entries, a class where a
// subobject overrides it does not — which is exactly why call-through has to
// pick by object, not by key.
struct SdkHookInfo {
    std::string key;             // rule key, empty for a by-slot hook
    std::string className;
    size_t slot = 0;
    uintptr_t handler = 0;
    std::vector<uintptr_t> tables;      // header-start addresses that were patched
    std::vector<uintptr_t> originals;   // parallel to tables
    size_t tablesFound = 0;             // all tables RTTI reports for the class
    size_t skipped = 0;                 // too short, or slot not a function

    bool installed() const { return !tables.empty(); }
};

// Patch every vtable of the class named by rule key `vtable.<key>`, replacing
// slot `slot` with `handler`. `key` is the rule key ("actorGetPos"), not the
// class name: the class and the index both come from the rule, so one call
// covers every build whose rule block is selected.
//
// False, with a log line, when the key is not pinned, the class has no RTTI
// table in this process, or no table could be patched.
bool hookVirtualRaw(const char *key, void *handler);

// Same, for a class/slot pair measured at runtime instead of pinned in a rule.
// `key` is only the registry name used for call-through and unhook; pass null
// to register under "<Class>:<slot>".
bool hookVirtualIn(const char *className, size_t slot, void *handler, const char *key);

// Restore every table a hook patched. The handler stays registered but is no
// longer reachable; hookInfo() keeps reporting the originals so a caller can
// re-install without a second walk.
bool unhookVirtual(const char *key);

// The record for an installed hook, or nullptr.
const SdkHookInfo *hookInfo(const char *key);

// Every installed hook, for selfTest() and the describe() dump.
std::vector<std::string> hookedKeys();

// Call-through.
//
// `object` is the `this` the call is being made on, and it decides WHICH
// original is returned: the object's vptr is matched against the patched
// tables so a subobject that overrides the slot gets its own original rather
// than the primary's. Returns 0 for an unknown key, an unhooked key, or an
// object whose vtable is not one we patched (in that last case the call never
// went through us anyway, so 0 is the honest answer).
uintptr_t originalFor(const char *key, uintptr_t object);

// Typed registration: the handler's prototype is checked against the call, so
// a signature that does not match the guest's ABI is a compile error rather
// than a corrupted register file at runtime. `this` arrives in x0 like any
// other AArch64 member function; declare the first parameter as void*.
//
//   static bool (*real)(void *, int) = nullptr;
//   mcpe::hookVirtual("playerCanOpenContainer", myHandler, &real);
template <typename Ret, typename... Args>
bool hookVirtual(const char *key, Ret (*handler)(void *, Args...),
                 Ret (**original)(void *, Args...)) {
    if (!hookVirtualRaw(key, reinterpret_cast<void *>(handler))) return false;
    if (original != nullptr) {
        // The primary table's original. A base-subobject variant that overrides
        // the slot has a different one, which is why call-through takes the
        // object: this pointer is the convenient form, not the exact one.
        const SdkHookInfo *info = hookInfo(key);
        *original = (info == nullptr || info->originals.empty())
                        ? nullptr
                        : reinterpret_cast<Ret (*)(void *, Args...)>(info->originals[0]);
    }
    return true;
}

// Typed call-through for the same shape.
template <typename Ret, typename... Args>
Ret callOriginal(const char *key, void *self, Args... args) {
    uintptr_t fn = originalFor(key, reinterpret_cast<uintptr_t>(self));
    if (fn == 0) return Ret{};
    return reinterpret_cast<Ret (*)(void *, Args...)>(fn)(self, args...);
}

}  // namespace mcpe

#endif  // ALTER_LAUNCHER_MCPE_SDK_HOOK_H
