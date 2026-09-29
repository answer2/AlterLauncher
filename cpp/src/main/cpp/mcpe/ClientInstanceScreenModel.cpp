#include <mcpe/ClientInstanceScreenModel.h>
#include <cstring>
#include <mcpe/GameSDK.h>
#include <util/HookRules.h>
#include <util/SigScanner.h>

namespace mcpe {
using detail::safeReadPtr;
using detail::inWritable;
using detail::inExec;
using detail::inGuestLib;

Handle ClientInstanceScreenModel::get() {
    // ClientInstanceScreenModel::get() is a static method — resolve via signature.
    uintptr_t fn = SigScanner::resolve(std::string("clientInstanceScreenModelGet"));
    if (fn == 0 || !inExec(fn)) return Handle{};
    using Fn = uintptr_t (*)();
    uintptr_t result = reinterpret_cast<Fn>(fn)();
    if (result == 0 || !inWritable(result)) return Handle{};
    return Handle{result, "sig"};
}

bool ClientInstanceScreenModel::executeCommand(const std::string &command) const {
    if (self_ == 0) return false;
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot = 0;
    if (!rule || !rule->lookupVtable(std::string("screenModelExecuteCommand"), nullptr, &slot)) return false;
    uintptr_t vt = 0;
    if (!safeReadPtr(self_, 0, &vt) || !inGuestLib(vt)) return false;
    uintptr_t fn = 0;
    memcpy(&fn, reinterpret_cast<const void *>(vt + slot * 8), sizeof(fn));
    if (fn == 0 || !inExec(fn)) return false;
    using Fn = bool (*)(void *, const std::string *);
    return reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_), &command);
}

}  // namespace mcpe