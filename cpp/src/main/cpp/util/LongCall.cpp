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

// Classes of displaced word for the bridge emitter below.
enum WordKind {
    kPlain,          // position-independent: copied verbatim into the trampoline
    kMaterialize,    // ADRP/ADR/LDR-literal: rewritten as an absolute-address load
    kRefusedBranch,  // B/BL/B.cond/CBZ/TBZ/CBNZ/TBNZ: fixed-pc-range branches
    kRefusedOther,   // anything else whose meaning depends on its own address
};

// Classify one instruction word. The three PC-relative addressing forms are
// NOT refused outright any more: their effective address is a pure function of
// the instruction itself plus its (known, fixed) location, so the bridge can
// materialize that address absolutely instead -- see emitTrampoline. Branches
// stay refused because a relocated branch would need the *target* within
// +/-128 MB of the bridge, which is exactly what a far bridge cannot promise.
int classifyWord(uint32_t w) {
    if ((w & 0xFC000000u) == 0x14000000u) return kRefusedBranch;  // B
    if ((w & 0xFC000000u) == 0x94000000u) return kRefusedBranch;  // BL
    if ((w & 0x7F000000u) == 0x54000000u) return kRefusedBranch;  // B.cond
    if ((w & 0x7E000000u) == 0x34000000u) return kRefusedBranch;  // CBZ/TBZ
    if ((w & 0x7E000000u) == 0x36000000u) return kRefusedBranch;  // CBNZ/TBNZ
    if ((w & 0x9F000000u) == 0x10000000u) return kMaterialize;    // ADR
    if ((w & 0x9F000000u) == 0x90000000u) return kMaterialize;    // ADRP
    if ((w & 0x3F000000u) == 0x18000000u) return kRefusedOther;    // LDR lit
    if ((w & 0x3F000000u) == 0x1C000000u) return kRefusedOther;    // LDRSW/PRFM
    if ((w & 0xFFFFFC00u) >= 0xD5032000u && (w & 0xFFFFFC00u) <= 0xD5032400u)
        return kRefusedOther;  // HINT/BTI/PAC: address-sensitive
    return kPlain;
}

const char *refusalName(int kind, uint32_t w) {
    if (kind == kRefusedBranch) return "branch (fixed pc range)";
    if ((w & 0x3F000000u) == 0x18000000u || (w & 0x3F000000u) == 0x1C000000u)
        return "PC-relative literal load";
    return "address-sensitive hint";
}

// Sign-extend a value occupying the low `bits` bits.
int64_t signExtend(uint64_t value, unsigned bits) {
    uint64_t top = UINT64_C(1) << (bits - 1);
    value &= (UINT64_C(1) << bits) - 1;
    return (int64_t)((value ^ top) - top);
}

// The absolute address a kMaterialize-class word computes when executed at
// `at`. Valid only for the classes classifyWord tags kMaterialize.
uint64_t pcRelativeTarget(uint32_t w, uintptr_t at) {
    if ((w & 0x9F000000u) == 0x90000000u) {  // ADRP: immhi:immlo << 12 + page(PC)
        uint64_t imm = (((uint64_t)(w >> 5) & 0x7FFFFu) << 2) | ((w >> 29) & 3u);
        return (uint64_t)((uintptr_t)(at & ~(uintptr_t) 0xFFF) +
                          signExtend(imm, 21) * 4096);
    }
    if ((w & 0x9F000000u) == 0x10000000u) {  // ADR: immhi:immlo + PC
        uint64_t imm = (((uint64_t)(w >> 5) & 0x7FFFFu) << 2) | ((w >> 29) & 3u);
        return (uint64_t)((uintptr_t) at + signExtend(imm, 21));
    }
    // LDR (literal) / LDRSW / PRFM: signed imm7 * scale (4 or 8 bytes).
    uint64_t imm7 = (w >> 5) & 0x7Fu;
    uint64_t scale = ((w >> 30) & 3u) == 0 ? 4u : 8u;  // size=00 -> 32-bit, else 64
    return (uint64_t)((uintptr_t) at + signExtend(imm7, 7) * (int64_t) scale);
}

// MOVZ Xd, #imm16 shifted left by hw*16; MOVK keeps the other halves.
uint32_t movz(unsigned rd, uint16_t imm16, unsigned hw) {
    return 0xD2800000u | ((uint32_t) hw << 21) | ((uint32_t) imm16 << 5) | rd;
}
uint32_t movk(unsigned rd, uint16_t imm16, unsigned hw) {
    return 0xF2800000u | ((uint32_t) hw << 21) | ((uint32_t) imm16 << 5) | rd;
}

// Emit an absolute-address load of `value` into register `rd`, starting at
// `out`; returns how many words were written (4 or 5).
size_t emitLoadAbs(uint32_t *out, unsigned rd, uint64_t value) {
    // MOVZ/MOVK build bits [15:0], [31:16], [47:32]; the top half arrives via
    // a literal-free fourth pair when needed. AArch64 addresses use <= 48 bits
    // in practice, so three chunks plus this tail cover every real target.
    out[0] = movz(rd, (uint16_t)(value & 0xFFFFu), 0);
    out[1] = movk(rd, (uint16_t)((value >> 16) & 0xFFFFu), 1);
    out[2] = movk(rd, (uint16_t)((value >> 32) & 0xFFFFu), 2);
    uint16_t top = (uint16_t)((value >> 48) & 0xFFFFu);
    if (top != 0) {
        out[3] = movk(rd, top, 3);
        return 4;
    }
    return 3;
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
    // Effective address of each word at its true site, and whether we must
    // materialize it instead of copying (kMaterialize). Branches/literals/hints
    // are refused outright; register branches mean the function is shorter than
    // the patch.
    uint64_t eff[kPatchWords] = {0};
    bool materialize[kPatchWords] = {false};
    for (size_t i = 0; i < kPatchWords; ++i) {
        const uintptr_t at = target + i * kWord;
        const int kind = classifyWord(words[i]);
        if (kind == kRefusedBranch || kind == kRefusedOther) {
            snprintf(detail, sizeof(detail), "word[%u]=0x%08x is %s", (unsigned) i,
                     words[i], refusalName(kind, words[i]));
            if (reason != nullptr) *reason = detail;
            return false;
        }
        const char *over = overrunProblem(words[i]);
        if (over != nullptr) {
            snprintf(detail, sizeof(detail), "word[%u]=0x%08x is %s", (unsigned) i,
                     words[i], over);
            if (reason != nullptr) *reason = detail;
            return false;
        }
        if (kind == kMaterialize) {
            materialize[i] = true;
            eff[i] = pcRelativeTarget(words[i], at);
            if ((words[i] & 0x9F000000u) == 0x90000000u)
                eff[i] &= ~(uint64_t) 0xFFFu;  // ADRP yields a page base
            // The Rd field sits at bits [4:0] in both ADR/ADRP and LDR-literal.
            unsigned rd = words[i] & 0x1Fu;
            if (rd >= 29) {  // X29/X30 or Z/S view of them: cannot clobber scratch
                snprintf(detail, sizeof(detail),
                         "word[%u]=0x%08x materializes into a reserved register x%u",
                         (unsigned) i, words[i], rd);
                if (reason != nullptr) *reason = detail;
                return false;
            }
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
    // Replay the displaced words. Plain words copy verbatim; materialized ones
    // become an absolute-address load of the SAME effective address they would
    // have computed at the original site, which is position-independent by
    // construction. For ADRP the low-12 offset stays folded out -- the next
    // ADD/ldr imm12 restores it -- so we materialize the page base only.
    size_t at = 0;
    for (size_t i = 0; i < displaced; ++i) {
        if (materialize[i]) {
            at += emitLoadAbs(trampoline + at, words[i] & 0x1Fu, eff[i]);
        } else {
            trampoline[at++] = words[i];
        }
    }
    // The entry patch always displaces exactly kPatchWords bytes of the target,
    // so control resumes there regardless of how many words we replayed.
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
