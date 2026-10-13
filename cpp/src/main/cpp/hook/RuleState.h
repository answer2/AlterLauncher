#ifndef ALTER_NATIVE_HOOK_RULE_STATE_H
#define ALTER_NATIVE_HOOK_RULE_STATE_H

#include <string>

#include <memory/HookRules.h>

// The rule that covers the running Minecraft build, and how it got there.
//
// Java owns WHERE the rule text comes from (an asset today, a downloaded file
// tomorrow) and hands it in through NativeCore.applyHookRules before
// loadManager; this owns parsing it, selecting the block whose version window
// covers the guest, and publishing the winner to every consumer (the hook
// installer, GameSdk's ladders, SigScanner).
namespace RuleState {

// The winning rule. A copy, not a pointer into the parsed vector, so it stays
// valid however the vector is later reassigned.
extern HookRules::Rule rule;

// Distinguishes "no rule matched, use the compiled-in data" from "a rule matched
// and says this function has no pattern". Every consumer must honour the
// difference: the second case means the function stays unhooked on purpose.
extern bool hasRule;

// The guest version string the selection ran against, for logging.
extern std::string guestVersion;

// Parse + select + publish. Never fatal: an empty or unparseable text leaves the
// compiled-in defaults in charge, which is what this code did before the rule
// file existed. Safe to call exactly once per process, before Bootstrap::load().
void apply(const std::string &version, const std::string &rulesText);

}  // namespace RuleState

#endif  // ALTER_NATIVE_HOOK_RULE_STATE_H
