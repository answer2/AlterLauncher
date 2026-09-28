#ifndef ALTER_LAUNCHER_HOOK_RULES_H
#define ALTER_LAUNCHER_HOOK_RULES_H

#include <stddef.h>
#include <stdint.h>

#include <map>
#include <string>
#include <vector>

// Version-gated data for the game hooks.
//
// Mojang recompiles libminecraftpe.so on every release, so both kinds of handle
// we use are versioned DATA rather than code: a vtable slot index (portable
// across the builds whose class layout is unchanged, which RTTI validates at
// runtime) and an IDA-style byte signature (the only route to the non-virtual
// functions). Keeping them in a shipped file means that covering a new Minecraft
// build is a data edit rather than a code change, and it turns "this build is
// not covered" into something the file states up front instead of something the
// binary discovers silently at every scan.
//
// The compiled-in defaults in main.cpp stay in force when no file is supplied,
// so a missing or unreadable asset degrades to the previous behaviour rather
// than to no hooks at all.
namespace HookRules {

struct Rule {
    std::string name;
    std::string minVersion;  // inclusive, empty = open ended
    std::string maxVersion;  // inclusive, empty = open ended

    // key -> "Class:slot"; the class is the bare C++ identifier, the RTTI
    // length prefix is added by RttiLookup.
    std::map<std::string, std::string> vtable;
    // key -> IDA-style pattern, e.g. "48 8B ?? ?? FF 23 ?? ??".
    std::map<std::string, std::string> signatures;
    // key -> file-relative offset (RVA) of the function inside the library. The
    // most direct handle there is: no scan, no pattern to rot. It is also the
    // least portable one, so it only ever applies to the exact build it was
    // recovered from, and the version window above is what keeps it honest.
    std::map<std::string, uintptr_t> offsets;
    // key -> object-relative data offset (a plain hex delta like d0x458), NOT a
    // library RVA. The prefix keeps the two kinds apart in one file: an off.*
    // entry is base+RVA and dies with the build; a dat.* entry is an offset
    // INSIDE a heap object (ClientInstance's projection matrix at +0x458 on
    // 1.26.x, GuiData's window size pair, ...) and only rots when Mojang
    // changes the class layout, which the version window still guards.
    std::map<std::string, uintptr_t> dataOffsets;

    // True when `version` falls inside [minVersion, maxVersion].
    bool covers(const std::string &version) const;

    // Split "ClientInstance:25" for `key`. Returns false when the key is absent
    // or malformed, leaving the out-params untouched.
    bool lookupVtable(const std::string &key, std::string *className, size_t *slot) const;

    // The pattern stored under `key`, or an empty string.
    std::string lookupSignature(const std::string &key) const;

    // The file-relative offset stored under `key` as `off.<key> = 0x...`. False
    // when the key is absent. An offset beats a pattern at use time: it is the
    // one handle that cannot silently stop matching.
    bool lookupOffset(const std::string &key, uintptr_t *rva) const;

    // The value stored under `key` as `dat.<key> = d0x...`. False when absent.
    bool lookupDataOffset(const std::string &key, uintptr_t *offset) const;
};

// Compare dotted versions numerically on the shared numeric prefix; a shorter
// version is padded with zeros, and any trailing text decides a tie, so
// "1.26.45.1" < "1.26.45.10" and "1.26.45.1-hotfix" > "1.26.45.1".
int compareVersions(const std::string &left, const std::string &right);

// Parse a whole rules file. Blank lines and `#`/`;` comments are ignored. Every
// rejected line is appended to `errors` (may be null) instead of aborting the
// parse, because one typo must not cost the launcher all of its hook data.
std::vector<Rule> parse(const std::string &text, std::vector<std::string> *errors);

// First rule in file order whose window contains `version`, or nullptr. Earlier
// rules win, so the file lists the specific windows first and the catch-all last.
const Rule *select(const std::vector<Rule> &rules, const std::string &version);

}  // namespace HookRules

#endif  // ALTER_LAUNCHER_HOOK_RULES_H