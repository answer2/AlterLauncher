#ifndef ALTER_LAUNCHER_UTIL_SIGSCANNER_H
#define ALTER_LAUNCHER_UTIL_SIGSCANNER_H

#include <stddef.h>
#include <stdint.h>

#include <string>
#include <vector>

namespace HookRules { struct Rule; }

// Batch signature resolution for the guest library, modelled on
// BedrockTools' memory::Signatures (D:\逆向分析\BedrockTools) but fed by OUR
// version-gated rule file instead of a compiled-in enum table. The lesson
// carried over from that project is structural, not textual: one scan pass
// per key, results cached forever, and every consumer asks by NAME
// ("guiDataAddMessage") so a new build is covered by editing game-hooks.ini,
// never by touching code.
//
// Why this exists next to hookBySignature(): a hook needs an address it can
// PATCH, which for far-out functions fails on the island dead band (see
// RttiLookup::islandReachable). The SDK layer only needs an address it can
// CALL - GuiData::addMessage's body is perfectly callable even when shadowhook
// cannot reach it. So every sig.* key in the active rule becomes resolvable
// here regardless of whether its hook installed, which is what lets the chat
// route survive without the hook.
//
// Thread-safety: resolve() may run on any thread after init(); the first
// caller of a key scans (a few hundred ms across the ~284 MiB mapping), every
// later caller reads the cache. Scanning reads the live mappings directly
// (FindSignature::getModuleInfo walks /proc/self/maps), so no dlopen is
// involved and the launcher's linker namespace does not matter.
namespace SigScanner {

// Hand the scanner the winning rule (may be null = nothing to do; every
// resolve then returns 0 with one log line). Idempotent; calling it again
// with a different rule clears the cache.
void init(const HookRules::Rule *rule);

// Resolve `key` against the rule's sig.<key> pattern. Returns the runtime
// address, or 0 when the key has no pattern, the pattern matched nothing, or
// the scanner has no rule. Cached either way - a miss is not rescanned per
// frame. This mirrors bedrocktools::memory::resolve(SignatureId).
uintptr_t resolve(const std::string &key);

// Seed the cache with an address the caller already knows, so no scan is ever
// run for it. Two reasons this is not just an optimisation:
//
//   - a hook rewrites the target's prologue (shadowhook) or replaces its vtable
//     slot, after which the sig.<key> pattern anchored at the entry no longer
//     matches. installSignatureHooks() therefore publishes the address it is
//     about to patch; without that, every SDK route to a hooked function would
//     resolve to 0 from the moment the hook lands.
//   - an off.<key> pin in the rule is already the answer. Scanning 297 MB to
//     rediscover it is pure waste (warmCaches() asks for all 41 keys).
//
// First writer wins: an address already handed out by resolve() is never
// swapped under a caller, but a divergence between pin and pattern is logged,
// since it means the rule file and the build disagree.
void publish(const std::string &key, uintptr_t address);

// All keys the rule defines under sig.*, for logging and for the mod-facing
// dump in menuState(). Names only; addresses come from resolve().
std::vector<std::string> keys();

// How many keys have been scanned and how many hit, for one summary line.
size_t scannedCount();
size_t hitCount();

}  // namespace SigScanner

#endif  // ALTER_LAUNCHER_UTIL_SIGSCANNER_H
