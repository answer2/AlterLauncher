// Standalone device regression test; link the real memory/RttiLookup.cpp.
#include <memory/RttiLookup.h>

#include <sys/mman.h>
#include <unistd.h>

#include <chrono>
#include <cstdio>
#include <limits>
#include <thread>

namespace {
int checks = 0;
int failures = 0;

void expect(bool condition, const char *message) {
  ++checks;
  if (!condition) ++failures;
  std::printf("[%s] %s\n", condition ? "PASS" : "FAIL", message);
}

// Wait beyond the production cache's 250 ms lifetime. No timing-sensitive
// assertion about stale answers inside that window: scheduling can exceed it.
void expireSnapshot() {
  const auto start = std::chrono::steady_clock::now();
  std::this_thread::sleep_until(start + std::chrono::milliseconds(300));
  const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
      std::chrono::steady_clock::now() - start).count();
  std::printf("[WAIT] %lld ms (cache lifetime: 250 ms)\n",
              static_cast<long long>(elapsed));
}

bool protect(void *page, size_t size, int permissions) {
  if (mprotect(page, size, permissions) == 0) return true;
  std::perror("mprotect");
  ++failures;
  return false;
}

class Mapping {
 public:
  explicit Mapping(size_t size)
      : address(mmap(nullptr, size, PROT_NONE,
                     MAP_PRIVATE | MAP_ANONYMOUS, -1, 0)), size_(size) {}
  ~Mapping() {
    if (address != MAP_FAILED && munmap(address, size_) != 0) {
      std::perror("munmap");
      ++failures;
    }
  }
  Mapping(const Mapping &) = delete;
  Mapping &operator=(const Mapping &) = delete;
  void *address;

 private:
  size_t size_;
};

void run(size_t pageSize) {
  // Guard | initially inaccessible, then RW | R | guard. The guards prevent
  // unrelated adjacent writable mappings from weakening boundary assertions.
  Mapping mapping(4 * pageSize);
  if (mapping.address == MAP_FAILED) {
    std::perror("mmap");
    ++failures;
    return;
  }
  auto *writable = static_cast<char *>(mapping.address) + pageSize;
  auto *readOnly = writable + pageSize;
  const uintptr_t addr = reinterpret_cast<uintptr_t>(writable);
  const uintptr_t boundary = reinterpret_cast<uintptr_t>(readOnly);

  const auto before = RttiLookup::regionsOf("");
  expect(!RttiLookup::inRegions(before, addr, false),
         "regionsOf excludes PROT_NONE anonymous page");
  if (!protect(writable, pageSize, PROT_READ | PROT_WRITE) ||
      !protect(readOnly, pageSize, PROT_READ)) return;
  const auto after = RttiLookup::regionsOf("");
  expect(RttiLookup::inRegions(after, addr, false),
         "regionsOf refresh includes newly readable anonymous RW page");
  expect(RttiLookup::inRegions(after, boundary, false),
         "regionsOf includes anonymous read-only page");
  expect(!RttiLookup::inRegions(after, addr, true),
         "anonymous RW page is not executable");

  expect(RttiLookup::inWritableRange(addr, pageSize),
         "whole RW page ending exactly at boundary is writable");
  expect(RttiLookup::inWritableRange(boundary - 1, 1),
         "last byte before boundary is writable");
  expect(!RttiLookup::inWritableRange(boundary - 1, 2),
         "range crossing RW to read-only boundary is rejected");
  expect(!RttiLookup::inWritableRange(addr, pageSize + 1),
         "whole RW page plus read-only byte is rejected");
  expect(!RttiLookup::inWritableRange(boundary, 1),
         "read-only boundary itself is rejected");
  expect(!RttiLookup::inWritableRange(addr - 1, 2),
         "range crossing guard to RW boundary is rejected");
  expect(RttiLookup::findMemberByVtable(addr, 1, 2 * pageSize) == 0,
         "inline member scan stops at unreadable or read-only boundary");
  expect(!RttiLookup::inWritableRange(0, 1), "null address is rejected");
  expect(!RttiLookup::inWritableRange(addr, 0), "zero length is rejected");
  // A real writable start plus SIZE_MAX wraps even after AArch64 untagging.
  expect(!RttiLookup::inWritableRange(addr, std::numeric_limits<size_t>::max()),
         "overflow from a real writable address is rejected");

  expect(RttiLookup::inWritableRange(addr, pageSize),
         "seed positive writable answer before mprotect(R)");
  if (!protect(writable, pageSize, PROT_READ)) return;
  expireSnapshot();
  expect(!RttiLookup::inWritableRange(addr, pageSize),
         "positive answer expires after mprotect(R)");

  expect(!RttiLookup::inWritableRange(addr, pageSize),
         "seed negative writable answer before mprotect(RW)");
  if (!protect(writable, pageSize, PROT_READ | PROT_WRITE)) return;
  expireSnapshot();
  expect(RttiLookup::inWritableRange(addr, pageSize),
         "negative answer expires after mprotect(RW)");
}
}  // namespace

int main() {
  const long pageSize = sysconf(_SC_PAGESIZE);
  if (pageSize <= 0) {
    std::perror("sysconf(_SC_PAGESIZE)");
    return 1;
  }
  std::printf("RttiLookup regression: page size %ld\n", pageSize);
  run(static_cast<size_t>(pageSize));
  std::printf("RttiLookup regression: %d checks, %d failures\n", checks, failures);
  return failures == 0 ? 0 : 1;
}
