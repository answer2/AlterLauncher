#ifndef ALTER_NATIVE_RUNTIME_PROCESS_NAME_H
#define ALTER_NATIVE_RUNTIME_PROCESS_NAME_H

#include <string>

// Levi-flavored mod runtimes (BedrockTools' Runtime::launcherContext) refuse to
// load unless /proc/self/cmdline's first token names the real game process. The
// kernel serves cmdline from the argv block at the very top of the main stack, so
// the spoof is a search-and-overwrite there — opt-in from settings, :game only,
// and it must run before any mod is loaded (MainActivity onCreate, where the
// plugin load happens, is far later than the call site).
namespace ProcessName {

// Overwrite argv[0] in place. Refuses (and logs) rather than corrupting the argv
// block when the target is longer than the current argv0 or the block cannot be
// located.
void spoof(const std::string &target);

}  // namespace ProcessName

#endif  // ALTER_NATIVE_RUNTIME_PROCESS_NAME_H
