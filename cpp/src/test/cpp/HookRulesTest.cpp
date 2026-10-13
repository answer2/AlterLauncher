// Host-side unit tests for the version-rule engine (memory/HookRules.cpp).
//
// This is the multi-version half of the test story: which [rule] block a given
// Minecraft version lands in, and what a typo in that data file costs. The
// engine is pure std C++, so it needs no game and no device semantics - only a
// toolchain with a C++ standard library. On this machine the NDK's aarch64
// clang++ supplies one (the desktop clang has no MSVC/libc++ headers), so
// cpp/src/test/run_tests.sh cross-compiles the binary, pushes it and the shipped
// rules file to the connected device over adb, and runs it there: same pure
// logic, real stdlib, no Android APIs beyond a log line. The shim under
// cpp/src/test/cpp/host is what lets a desktop toolchain that DOES ship C++
// headers build the same file with no device at all. The sibling offline check
// for the pin VALUES is build/audit_pins.py (against the stripped .so files).
//
//   hookrules_test [path/to/game-hooks.ini]
//
#include "memory/HookRules.h"

#include <ctype.h>
#include <stdio.h>
#include <string>
#include <vector>

namespace {

int g_checks = 0;
int g_failures = 0;
std::string g_assetPath;  // set from argv before run()

void check(bool ok, const std::string &what) {
    g_checks++;
    if (!ok) {
        g_failures++;
        printf("FAIL  %s\n", what.c_str());
    }
}

int cmp(int expected, const char *left, const char *right) {
    int got = HookRules::compareVersions(left, right);
    std::string what = std::string("compareVersions(") + left + ", " + right + ") == " +
                       (expected < 0 ? "<0" : expected > 0 ? ">0" : "0") + ", got " +
                       (got < 0 ? "<0" : got > 0 ? ">0" : "0");
    check((got < 0 ? -1 : got > 0 ? 1 : 0) == expected, what);
    return 0;
}

std::string readFile(const char *path, bool *ok) {
    FILE *f = fopen(path, "rb");
    *ok = f != nullptr;
    if (!f) return std::string();
    std::string out;
    char buf[4096];
    size_t n = 0;
    while ((n = fread(buf, 1, sizeof(buf), f)) > 0) out.append(buf, n);
    fclose(f);
    return out;
}

// --- the version arithmetic every window depends on -------------------------
void testCompareVersions() {
    cmp(-1, "1.26.45.1", "1.26.45.10");   // numeric, not lexical
    cmp(-1, "1.9", "1.10");
    cmp(0, "1.21.91", "1.21.91.0");       // shorter side pads with zeros
    cmp(1, "1.26.45.1-hotfix", "1.26.45.1");  // the tail breaks a tie
    cmp(-1, "1.26.45", "1.26.45.1-beta");
    cmp(1, "1.27.0.0", "1.26.99.99");
}

void testCovers() {
    HookRules::Rule r;
    r.name = "window";
    r.minVersion = "1.26.40.00";
    r.maxVersion = "1.26.49.99";
    check(r.covers("1.26.45.1"), "inside the window is covered");
    check(r.covers("1.26.40.00"), "min is inclusive");
    check(r.covers("1.26.49.99"), "max is inclusive");
    check(!r.covers("1.26.50.00"), "above the window is not covered");
    check(!r.covers("1.26.39.99"), "below the window is not covered");
    check(!r.covers(""), "an unknown version matches nothing");

    HookRules::Rule open;
    open.name = "open";
    open.minVersion = "1.26.0.0";
    check(open.covers("1.99.99.99"), "an empty max is open ended");
}

// --- block selection: first match wins, catch-all last ----------------------
void testSelect() {
    const char *text =
        "[rule specific]\nmin = 1.26.50.00\nmax =\nvtable.a = ClientInstance:151\n\n"
        "[rule window]\nmin = 1.26.40.00\nmax = 1.26.49.99\nvtable.a = ClientInstance:150\n\n"
        "[rule catch-all]\nmin = 1.26.0.0\nmax =\nvtable.a = ClientInstance:149\n";
    std::vector<std::string> errors;
    std::vector<HookRules::Rule> rules = HookRules::parse(text, &errors);
    check(errors.empty(),
          std::string("synthetic fixture parses clean: ") + (errors.empty() ? "" : errors[0]));
    check(rules.size() == 3, "three blocks");
    const HookRules::Rule *r = HookRules::select(rules, "1.26.52.3");
    check(r && r->name == "specific", "1.26.52.3 -> specific (listed first, opens at 1.26.50)");
    r = HookRules::select(rules, "1.26.45.1");
    check(r && r->name == "window", "1.26.45.1 -> the narrow window, not the catch-all");
    r = HookRules::select(rules, "1.26.30.5");
    check(r && r->name == "catch-all", "1.26.30.5 -> catch-all");
    r = HookRules::select(rules, "1.20.5.0");
    check(r == nullptr, "1.20.5 is covered by nothing: compiled-in defaults apply");
}

// --- the four kinds of handle, and what a typo does -------------------------
void testKeyKinds() {
    const char *text =
        "[rule kinds]\nmin = 1.0\nmax =\n"
        "vtable.tick = ClientInstance:25\n"
        "sig.chat = ? ? ? D1 FD 03 00\n"
        "off.chatCtor = 0x923a5d4\n"
        "dat.matrix = d0x458\n";
    std::vector<std::string> errors;
    std::vector<HookRules::Rule> rules = HookRules::parse(text, &errors);
    check(errors.empty(), "all four kinds parse clean");
    const HookRules::Rule &r = rules[0];

    std::string cls;
    size_t slot = 0;
    check(r.lookupVtable("tick", &cls, &slot) && cls == "ClientInstance" && slot == 25,
          "vtable splits into class + index");
    slot = 0;
    check(r.lookupVtable("tick", nullptr, &slot) && slot == 25,
          "slot-only lookup accepts a null class output");
    cls.clear();
    check(r.lookupVtable("tick", &cls, nullptr) && cls == "ClientInstance",
          "class-only lookup accepts a null slot output");
    check(r.lookupVtable("tick", nullptr, nullptr), "existence-only vtable lookup");
    check(!r.lookupVtable("missing", &cls, &slot), "an absent vtable key is not found");
    check(r.lookupSignature("chat") == "? ? ? D1 FD 03 00", "sig keeps its wildcards verbatim");

    uintptr_t rva = 0;
    check(r.lookupOffset("chatCtor", &rva) && rva == 0x923a5d4, "off.* is a library RVA");
    uintptr_t dataOff = 0;
    check(r.lookupDataOffset("matrix", &dataOff) && dataOff == 0x458, "dat.* is object-relative");
    // The two namespaces must not bleed into each other: confusing a +0x458 field
    // for an RVA would call into the middle of some unrelated function.
    check(!r.lookupOffset("matrix", &rva), "dat.* is not visible as an off.*");
    check(!r.lookupDataOffset("chatCtor", &dataOff), "off.* is not visible as a dat.*");
}

void testBadLinesAreReportedNotFatal() {
    const char *text =
        "[rule bad]\nmin = 1.0\nmax =\n"
        "dat.noMarker = 0x458\n"          // the 'd' prefix is mandatory
        "vtable.noIndex = ClientInstance:\n"
        "unknown.key = 1\n"
        "this is not a key=value line\n";
    std::vector<std::string> errors;
    std::vector<HookRules::Rule> rules = HookRules::parse(text, &errors);
    check(rules.size() == 1, "the block itself survives");
    check(errors.size() == 4, "each rejected line is reported once");
    uintptr_t value = 0;
    check(!rules[0].lookupDataOffset("noMarker", &value), "an unmarked dat value is dropped");
    std::string cls;
    size_t slot = 0;
    check(!rules[0].lookupVtable("noIndex", &cls, &slot), "an empty slot index is dropped");
}

// One duplicate key shadowing a verified pin is the exact bug this file has
// already had: a stale placeholder further down silently won over the real
// number, because std::map keeps the last write.
void testDuplicateKeyInsideOneBlock() {
    const char *text =
        "[rule dup]\nmin = 1.0\nmax =\n"
        "vtable.options = ClientInstance:174\n"
        "vtable.options = ClientInstance:199\n"
        "dat.optionsField = d0xC80\n"
        "dat.optionsField = d0x2E0\n";
    std::vector<std::string> errors;
    std::vector<HookRules::Rule> rules = HookRules::parse(text, &errors);
    check(errors.size() == 2, "both duplicates are reported");
    std::string cls;
    size_t slot = 0;
    check(rules[0].lookupVtable("options", &cls, &slot) && slot == 174,
          "the FIRST value is the one that stays in force");
    uintptr_t value = 0;
    check(rules[0].lookupDataOffset("optionsField", &value) && value == 0xC80,
          "same for a duplicated dat pin");
}

void testCarriageReturns() {
    const char *text = "[rule crlf]\r\nmin = 1.0\r\nmax =\r\nvtable.tick = ClientInstance:25\r\n";
    std::vector<std::string> errors;
    std::vector<HookRules::Rule> rules = HookRules::parse(text, &errors);
    std::string cls;
    size_t slot = 0;
    check(errors.empty() && rules.size() == 1 && rules[0].lookupVtable("tick", &cls, &slot) &&
              slot == 25,
          "a Windows-edited file still parses (a stray \\r would poison the index)");
}

// --- the file we actually ship ----------------------------------------------
void testShippedFile(const std::string &text) {
    std::vector<std::string> errors;
    std::vector<HookRules::Rule> rules = HookRules::parse(text, &errors);
    check(errors.empty(), "the shipped rules file parses with zero errors");
    for (size_t i = 0; i < errors.size() && i < 8; i++)
        printf("      %s\n", errors[i].c_str());
    check(rules.size() >= 5, "the shipped file carries one block per supported version");

    struct Expect {
        const char *version;
        const char *block;
    } expects[] = {
        {"1.21.91.02", "mcpe-1.21.91"},   {"1.21.101.01", "mcpe-1.21.101"},
        {"1.26.30.03", "levi-1.26.30"},   {"1.26.45.1", "levi-1.26.40"},
        {"1.26.49.99", "levi-1.26.40"},   {"1.26.50.02", "levi-1.26.50"},
        {"1.26.52.3", "levi-1.26.50"},    {"1.27.1.00", "minecraft-1.26"},
        {"1.20.15.02", NULL},             // nothing claims it: defaults apply
    };
    for (size_t i = 0; i < sizeof(expects) / sizeof(expects[0]); i++) {
        const HookRules::Rule *r = HookRules::select(rules, expects[i].version);
        std::string want = expects[i].block ? expects[i].block : "<none>";
        std::string got = r ? r->name : "<none>";
        check((r == nullptr) == (expects[i].block == nullptr) && (!r || got == want),
              std::string("Minecraft ") + expects[i].version + " selects " + want +
                  ", selected " + got);
    }

    check(!rules.empty() && rules[rules.size() - 1].name == "minecraft-1.26",
          "the catch-all stays LAST");

    // Every vtable entry must survive lookupVtable, and no block may pin a slot
    // beyond a class's reachable table length at parse time - the runtime check
    // for that is RttiLookup's, but a malformed value here is a data typo.
    size_t pins = 0;
    for (size_t i = 0; i < rules.size(); i++) {
        std::string cls;
        size_t slot = 0;
        for (std::map<std::string, std::string>::const_iterator it = rules[i].vtable.begin();
             it != rules[i].vtable.end(); ++it) {
            pins++;
            std::string key = it->first;
            check(rules[i].lookupVtable(key, &cls, &slot),
                  "vtable." + key + " in [" + rules[i].name + "] is Class:N");
            check(!cls.empty() && isupper((unsigned char) cls[0]) != 0,
                  "class name is the bare C++ identifier: " + cls);
        }
    }
    check(pins >= 30, "the shipped file carries the pins for every supported build");
}

int run() {
    testCompareVersions();
    testCovers();
    testSelect();
    testKeyKinds();
    testBadLinesAreReportedNotFatal();
    testDuplicateKeyInsideOneBlock();
    testCarriageReturns();

    bool ok = false;
    if (g_failures == 0) printf("  (unit cases pass)\n");
    std::string text = readFile(g_assetPath.c_str(), &ok);
    if (g_assetPath.empty()) {
        printf("NOTE  no rules file given; the shipped-file cases did not run\n");
    } else if (!ok) {
        g_checks++;
        g_failures++;
        printf("FAIL  cannot read %s\n", g_assetPath.c_str());
    } else {
        testShippedFile(text);
    }

    printf("%d checks, %d failure(s)\n", g_checks, g_failures);
    return g_failures == 0 ? 0 : 1;
}

}  // namespace

int main(int argc, char **argv) {
    if (argc > 1) g_assetPath = argv[1];
    return run();
}
