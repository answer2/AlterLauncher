// Standalone device regression; link the actual memory/FindSignature.cpp with
// -ffunction-sections -Wl,--gc-sections -static-libstdc++ -llog so unused
// module-discovery/JNI functions do not require Main or the app runtime.
#include <memory/FindSignature.h>

#include <cstdio>
#include <cstring>

namespace {
int checks = 0;
int failures = 0;

void expectAddress(uintptr_t actual, uintptr_t expected, const char *message) {
  ++checks;
  const bool passed = actual == expected;
  if (!passed) ++failures;
  std::printf("[%s] %s", passed ? "PASS" : "FAIL", message);
  if (!passed) {
    std::printf(" (expected %p, got %p)", reinterpret_cast<void *>(expected),
                reinterpret_cast<void *>(actual));
  }
  std::printf("\n");
}

void expectBoth(const ModuleInfo &info, size_t region, const char *pattern,
                uintptr_t expected, const char *message) {
  std::printf("Pattern case: %s\n", message);
  expectAddress(FindSignature::findSignatureInModule(info, pattern), expected,
                "module scan");
  expectAddress(FindSignature::findSignatureInRegion(info, region, pattern),
                expected, "region scan");
}

void run() {
  alignas(4) unsigned char first[16] = {};
  alignas(4) unsigned char second[16] = {};
  const unsigned char bytes[] = {0xAA, 0xBB, 0xCC, 0xDD};
  const uintptr_t firstBase = reinterpret_cast<uintptr_t>(first);
  const uintptr_t secondBase = reinterpret_cast<uintptr_t>(second);
  const ModuleInfo info = {firstBase, sizeof(first),
                          {{firstBase, sizeof(first)},
                           {secondBase, sizeof(second)}}};

  // The only match is in the second region, beyond ModuleInfo::base/size.
  std::memcpy(second + 4, bytes, sizeof(bytes));
  expectBoth(info, 1, "AA BB CC DD", secondBase + 4,
             "unique aligned match in second region");
  expectBoth(info, 1, "AA ?? CC DD", secondBase + 4,
             "whitespace-delimited wildcard accepted");
  expectBoth(info, 1, " \tAA\tBB\nCC\r\nDD\t ", secondBase + 4,
             "tabs/newlines and surrounding whitespace accepted");

  // Each region independently has one hit; the module must reject ambiguity.
  std::memcpy(first + 8, bytes, sizeof(bytes));
  expectAddress(FindSignature::findSignatureInRegion(info, 0, "AA BB CC DD"),
                firstBase + 8, "first duplicate is individually unique");
  expectAddress(FindSignature::findSignatureInRegion(info, 1, "AA BB CC DD"),
                secondBase + 4, "second duplicate is individually unique");
  expectAddress(FindSignature::findSignatureInModule(info, "AA BB CC DD"), 0,
                "duplicate across regions rejected");

  std::memset(first, 0, sizeof(first));
  std::memset(second, 0, sizeof(second));
  std::memcpy(second + 1, bytes, sizeof(bytes));
  expectBoth(info, 1, "AA BB CC DD", 0, "unaligned match rejected");

  // Only one aligned candidate exists here, so all-wildcard rejection cannot
  // pass merely because multiple candidate addresses are ambiguous.
  alignas(4) unsigned char pair[] = {0xAA, 0xBB};
  const uintptr_t pairBase = reinterpret_cast<uintptr_t>(pair);
  const ModuleInfo small = {pairBase, sizeof(pair), {{pairBase, sizeof(pair)}}};
  expectBoth(small, 0, "AA BB", pairBase, "joined-hex positive control");
  expectBoth(small, 0, "AA ??", pairBase, "joined-wildcard positive control");
  expectBoth(small, 0, "? BB", pairBase, "single wildcard accepted");
  expectBoth(small, 0, nullptr, 0, "null rejected");
  expectBoth(small, 0, "", 0, "empty rejected");
  expectBoth(small, 0, " \t\r\n ", 0, "whitespace-only rejected");
  expectBoth(small, 0, "? ??", 0, "all wildcards rejected");
  expectBoth(small, 0, "AA??", 0, "joined byte/wildcard tokens rejected");
  expectBoth(small, 0, "AABB", 0, "joined hex tokens rejected");
}
}  // namespace

int main() {
  run();
  std::printf("FindSignature regression: %d checks, %d failures\n", checks,
              failures);
  return failures == 0 ? 0 : 1;
}
