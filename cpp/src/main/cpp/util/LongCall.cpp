#include "LongCall.h"

#include <sys/mman.h>
#include <unistd.h>

#include <cerrno>
#include <cstdio>
#include <cstring>

namespace LongCall {

namespace {

// AArch64 encodings, spelled out rather than assembled so the reader can check
// them against a disassembler.
constexpr uint32_t kLdrX16Plus8 = 0x58000050;  // LDR X16, #+8  -> X16 = *(self+8)
constexpr uint32_t kBrX16 = 0xD61F0200;        // BR  X16
constexpr uint32_t kBranchOp = 0x14000000;     // B   #imm26 (BL differs by bit 31)
constexpr uint32_t kBrkCanary = 0xD4210000;    // BRK #0x1000, a padding word that
                                               // must never be executed
constexpr ptrdiff_t kBranchReach = 1 << 27;    // +/-128 MB, the direct-branch limit

constexpr size_t kWord = 4;
constexpr size_t kPatchWords = 4;  // LDR + BR + the 8-byte address they load

// Non-null when the instruction cannot be executed from another address.
//
// Everything PC-relative resolves against the instruction's own address, so a
// copy in another page would compute something else; and the HINT space (BTI,
// PACIA/AUTIA) is refused because those encode properties of the address they
// sit at, which a copy loses.
const char *relocationProblem(uint32_t w) {
    if ((w & 0xFC000000u) == 0x14000000u) return "B (direct branch)";
    if ((w & 0xFC000000u) == 0x94000000u) return "BL (direct call)";
    if ((w & 0x7F000000u) == 0x54000000u) return "B.cond (conditional branch)";
    if ((w & 0x7E000000u) == 0x34000000u) return "CBZ/TBZ (branch if zero)";
    if ((w & 0x7E000000u) == 0x36000000u) return "CBNZ/TBNZ (branch if nonzero)";
    if ((w & 0x9F000000u) == 0x10000000u) return "ADR (PC-relative address)";
    if ((w & 0x9F000000u) == 0x90000000u) return "ADRP (PC-relative page)";
    if ((w & 0x3F000000u) == 0x18000000u) return "LDR (PC-relative literal)";
    if ((w & 0x3F000000u) == 0x1C000000u) return "LDRSW/PRFM (PC-relative literal)";
    if ((w & 0xFFFFFC00u) >= 0xD5032000u && (w & 0xFFFFFC00u) <= 0xD5032400u)
        return "HINT/BTI/PAC (address-sensitive)";
    return nullptr;
}

// Non-null when the instruction transfers control away, which means the target
// is shorter than the patch and the words after it belong to another function.
const char *overrunProblem(uint32_t w) {
    if ((w & 0xFE000000u) == 0xD6000000u) return "BR/BLR/RET (register branch)";
    if ((w & 0xFFC00000u) == 0xD4000000u) return "SVC/BRK (exception)";
    return nullptr;
}

size_t pageSize() {
    static size_t cached = 0;
    if (cached == 0) {
        long ps = sysconf(_SC_PAGESIZE);
        cached = (ps > 0) ? (size_t) ps : 4096;
    }
    return cached;
}

bool inReach(uintptr_t from, uintptr_t to) {
    ptrdiff_t distance = (ptrdiff_t) to - (ptrdiff_t) from;
    return distance > -kBranchReach && distance < kBranchReach;
}

// One page per hook. Three hooks means three pages, and it keeps the write
// sequence simple: the page is filled while writable, then made executable once.
//
// The page holds both stubs. `thunk` is what the patched entry jumps to and it
// forwards to the handler; `trampoline` replays the displaced instructions and
// returns to the rest of the function, and it is the address the handler calls
// as "the original".
struct Bridge {
    uintptr_t address;
    bool reachable;  // a direct branch from the target lands here
};

Bridge takeBridge(uintptr_t target) {
    Bridge out = {0, false};
    const size_t page = pageSize();
    // Hints are tried nearest-first on both sides. mmap() honours the address as
    // a hint only, and returns the first free page at or above it, so a hint that
    // falls inside the library simply walks out of it; each candidate is measured
    // afterwards and rejected when the kernel could not keep it in reach. The
    // largest offsets stop short of the exact +/-128 MB limit.
    static const long kOffsets[] = {1, 16, 256, 1024, 4096, 16384, 31000,
                                    -1, -16, -256, -1024, -4096, -16384, -31000};
    for (size_t i = 0; i < sizeof(kOffsets) / sizeof(kOffsets[0]); ++i) {
        uintptr_t candidate =
            (uintptr_t)((ptrdiff_t) target + kOffsets[i] * (long) page) & ~(page - 1);
        void *p = mmap((void *) candidate, page, PROT_READ | PROT_WRITE,
                       MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        if (p == MAP_FAILED) continue;
        if (inReach(target, (uintptr_t) p)) {
            out.address = (uintptr_t) p;
            out.reachable = true;
            return out;
        }
        munmap(p, page);  // the kernel placed it out of reach; nothing of ours is in it
    }
    void *p = mmap(nullptr, page, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS,
                   -1, 0);
    if (p == MAP_FAILED) return out;
    out.address = (uintptr_t) p;
    return out;
}

// Protection flags of the mapping holding `addr`, from /proc/self/maps. A shared
// mapping is refused by the caller: writing through it would edit the file.
bool protectionOf(uintptr_t addr, int *prot, bool *shared) {
    FILE *maps = fopen("/proc/self/maps", "re");
    if (maps == nullptr) return false;
    char line[1024];
    bool found = false;
    while (fgets(line, sizeof(line), maps) != nullptr) {
        unsigned long start = 0, end = 0;
        char flags[8] = {0};
        if (sscanf(line, "%lx-%lx %4s", &start, &end, flags) != 3) continue;
        if (addr < (uintptr_t) start || addr >= (uintptr_t) end) continue;
        *prot = 0;
        if (flags[0] == 'r') *prot |= PROT_READ;
        if (flags[1] == 'w') *prot |= PROT_WRITE;
        if (flags[2] == 'x') *prot |= PROT_EXEC;
        *shared = (flags[3] == 's');
        found = true;
        break;
    }
    fclose(maps);
    return found;
}

}  // namespace

bool hook(uintptr_t target, void *handler, void **original, std::string *reason) {
    if (target == 0 || handler == nullptr || original == nullptr) {
        if (reason != nullptr) *reason = "bad arguments";
        return false;
    }
    const size_t page = pageSize();
    if ((target & (kWord - 1)) != 0) {
        if (reason != nullptr) *reason = "target is not instruction aligned";
        return false;
    }
    if ((target & (page - 1)) > page - kPatchWords * kWord) {
        if (reason != nullptr) *reason = "the 16-byte patch would cross a page boundary";
        return false;
    }

    uint32_t words[kPatchWords];
    memcpy(words, (const void *) target, sizeof(words));

    char detail[256];
    for (size_t i = 0; i < kPatchWords; ++i) {
        const char *problem = relocationProblem(words[i]);
        if (problem == nullptr) problem = overrunProblem(words[i]);
        if (problem != nullptr) {
            snprintf(detail, sizeof(detail), "word[%u]=0x%08x is %s", (unsigned) i,
                     words[i], problem);
            if (reason != nullptr) *reason = detail;
            return false;
        }
    }

    bool shared = false;
    int prot = 0;
    if (!protectionOf(target, &prot, &shared)) {
        if (reason != nullptr) *reason = "no mapping holds the target";
        return false;
    }
    if (shared) {
        if (reason != nullptr) *reason = "the target mapping is shared, patching it "
                                         "would edit the file on disk";
        return false;
    }
    if ((prot & PROT_EXEC) == 0) {
        if (reason != nullptr) *reason = "the target is not executable";
        return false;
    }

    Bridge bridge = takeBridge(target);
    if (bridge.address == 0) {
        if (reason != nullptr) *reason = "out of memory for the bridge page";
        return false;
    }

    // When the page is in direct-branch reach, the entry patch is one word and
    // only the first instruction is displaced. Otherwise the entry has to carry a
    // full 64-bit address itself, which costs four words.
    const size_t displaced = bridge.reachable ? 1 : kPatchWords;
    uintptr_t resume = target + displaced * kWord;

    // Layout, always 8-byte aligned so the literal loads are legal: `LDR X16, #+8`
    // reads the eight bytes after its own pair, so it must sit at an even word
    // index. A single displaced word therefore needs a padding slot before it.
    //
    //   reachable:   [0..3] thunk -> handler     [4..] trampoline
    //   far:         (no thunk)                  [0..] trampoline
    uint32_t *thunk = (uint32_t *) bridge.address;
    uint32_t *trampoline = bridge.reachable ? thunk + 4 : thunk;
    if (bridge.reachable) {
        thunk[0] = kLdrX16Plus8;
        thunk[1] = kBrX16;
        memcpy(thunk + 2, &handler, sizeof(handler));
    }
    memcpy(trampoline, words, displaced * kWord);
    size_t at = displaced;
    if ((at & 1) != 0) {
        trampoline[at] = kBrkCanary;
        ++at;
    }
    trampoline[at] = kLdrX16Plus8;
    trampoline[at + 1] = kBrX16;
    memcpy(trampoline + at + 2, &resume, sizeof(resume));
    const size_t bridgeWords = (bridge.reachable ? 4u : 0u) + at + 4u;
    __builtin___clear_cache((char *) bridge.address,
                            (char *) (bridge.address + bridgeWords * kWord));

    if (mprotect((void *) bridge.address, page, PROT_READ | PROT_EXEC) != 0) {
        snprintf(detail, sizeof(detail), "mprotect RX on the bridge failed: %s",
                 strerror(errno));
        if (reason != nullptr) *reason = detail;
        return false;
    }

    if (mprotect((void *) (target & ~(page - 1)), page, prot | PROT_WRITE) != 0) {
        snprintf(detail, sizeof(detail), "mprotect RW failed: %s", strerror(errno));
        if (reason != nullptr) *reason = detail;
        return false;
    }
    if (bridge.reachable) {
        // The whole patch is one aligned word, so the store is atomic on AArch64:
        // before it the function is untouched, after it every new call arrives at
        // the thunk. A thread already past the entry keeps running the original
        // instructions, which are still in place.
        ptrdiff_t distance = (ptrdiff_t) bridge.address - (ptrdiff_t) target;
        *(uint32_t *) target = kBranchOp | (((uint32_t)(distance >> 2)) & 0x03FFFFFFu);
    } else {
        // No page in reach, so the address lives in the entry and there is no
        // atomic store available. Written so that the two halves of the handler
        // address are complete before the LDR that reads them, and the BR that
        // ends the original prologue goes in last: until that store lands, an
        // entry still runs the untouched instructions, and an LDR that is already
        // in place only clobbers X16, an intra-procedure-call scratch register the
        // ABI lets a caller lose. The residual window is a thread sitting exactly
        // on the displaced tail while the address words are rewritten; these hooks
        // go in while the game is still starting, so it is accepted rather than
        // solved.
        uint64_t handlerBits = (uint64_t)(uintptr_t) handler;
        *(uint32_t *) (target + 3 * kWord) = (uint32_t)(handlerBits >> 32);
        *(uint32_t *) (target + 2 * kWord) = (uint32_t)(handlerBits & 0xFFFFFFFFu);
        *(uint32_t *) target = kLdrX16Plus8;
        *(uint32_t *) (target + kWord) = kBrX16;
    }
    mprotect((void *) (target & ~(page - 1)), page, prot);
    __builtin___clear_cache((char *) target, (char *) (target + displaced * kWord));

    *original = (void *) trampoline;
    snprintf(detail, sizeof(detail), "%s%u word%s patched, bridge at 0x%lx, original at 0x%lx",
             bridge.reachable ? "" : "far: ", (unsigned) displaced,
             displaced == 1 ? "" : "s", (unsigned long) bridge.address,
             (unsigned long) (uintptr_t) trampoline);
    if (reason != nullptr) *reason = detail;
    return true;
}

}  // namespace LongCall
