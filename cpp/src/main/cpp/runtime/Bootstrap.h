#ifndef ALTER_NATIVE_RUNTIME_BOOTSTRAP_H
#define ALTER_NATIVE_RUNTIME_BOOTSTRAP_H

// Guest bring-up, in the one order that works:
//
//   1. the launcher's own asset/file/manager shims (initManagers),
//   2. the guest library's base address, published to Main and GameSdk,
//   3. the RTTI hooks — they need no dlopen and survive a build whose patterns
//      moved, and they are what captures ClientInstance,
//   4. the signature hooks — the non-virtual functions, and the fallback
//      Minecraft::update when step 3 resolved nothing.
//
// RuleState::apply() must already have run (NativeCore.applyHookRules), because
// both installers read the winning rule.
namespace Bootstrap {

void load();

}  // namespace Bootstrap

#endif  // ALTER_NATIVE_RUNTIME_BOOTSTRAP_H
