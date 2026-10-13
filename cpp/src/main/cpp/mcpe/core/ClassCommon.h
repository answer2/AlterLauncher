#ifndef ALTER_LAUNCHER_MCPE_CLASS_COMMON_H
#define ALTER_LAUNCHER_MCPE_CLASS_COMMON_H

// Shared handle type + detail mechanics for the per-class modules. Every
// mcpe/<Class>.h includes this; none of it is mod-facing (mods include
// ModApi.h). The implementations live once, in ClassTree.cpp, so all class
// modules share one region cache and one RTTI table cache.
#include <stddef.h>
#include <stdint.h>
#include <string.h>

#include <string>
#include <vector>

#include <mcpe/core/Types.h>

namespace mcpe {

// One resolved handle: the pointer plus how we found it, for describe() and
// for the logs. Source strings: "pin" (rule offset), "scan" (vtable-
// validated member scan), "vcall" (authorized virtual getter), "sig"
// (pattern-resolved getter), "hook" (published by a live hook), "seen"
// (observed as `this` in a hooked call), "-" (unresolved).
struct Handle {
    uintptr_t ptr = 0;
    const char *source = "-";

    bool valid() const { return ptr != 0; }
    operator uintptr_t() const { return ptr; }
};

namespace detail {

extern const char *kGuestLib;  // "libminecraftpe.so"

// An object's vptr is already the address point (the first virtual function),
// past the offset_to_top/typeinfo header returned by RttiLookup. Never add that
// header when dispatching through a vptr read from an object.

// Region-checked reads. inWritable gates every dereference of a heap object;
// safeReadPtr/safeReadF32 refuse non-canonical or non-finite values.
bool inWritable(uintptr_t addr);
bool inExec(uintptr_t addr);
// Vtables live in .data.rel.ro (relocated, non-exec): validate a vtable
// pointer against ANY mapping of the guest library, not just exec ones.
bool inGuestLib(uintptr_t addr);
bool safeReadPtr(uintptr_t object, uintptr_t offset, uintptr_t *out);
bool safeReadF32(uintptr_t object, uintptr_t offset, float *out);
bool safeReadU8(uintptr_t object, uintptr_t offset, uint8_t *out);
bool safeReadU32(uintptr_t object, uintptr_t offset, uint32_t *out);
// For a word that is not a pointer (an index, a packed id, a raw object image):
// range-checked but with no value predicate, because safeReadPtr's refusal of 0
// and unaligned words is a pointer rule, and index 0 is a real position.
bool safeReadU64(uintptr_t object, uintptr_t offset, uint64_t *out);
bool safeReadI32(uintptr_t object, uintptr_t offset, int32_t *out);

// The rule pin for a dat.<key> entry (object-relative offset, 'd' marker).
//
// Also honours the measuring form `dat.<key> = gClass:slot`: rather than
// trusting a literal offset measured on one build, it reads that slot's body
// off the guest's own mappings and takes the offset out of the immediate,
// accepting it only for the pure field-read shapes. See HookRules.h.
bool dataPin(const char *key, uintptr_t *offset);

// The offset a measuring pin decodes to, with no object to pick a table with.
// Accepts an answer only when EVERY RTTI table of the class agrees; a class
// whose tables disagree is ambiguous and fails. Callers that hold the object
// should use verifiedGetterOffset instead, which can tell the tables apart.
bool getterOffsetByClass(const char *className, size_t slot, uintptr_t *outOffset);

// Every RTTI table of a class, cached per name (primary + aux variants).
const std::vector<uintptr_t> &tablesFor(const char *className);

// The rule data-pin rung on its own: read `ci + pinKey` and RTTI-certify it
// against `scanClass`. Split out of fieldByPinOrScan because the documented
// ladder is pin -> signature -> read-only scan -> virtual, and an accessor that
// has a byte-exact signature for its getter must not pay for a 0x20000-wide
// member scan first.
Handle fieldByPin(uintptr_t ci, const char *pinKey, const char *scanClass);

// Generic subsystem-field ladder shared by ClientInstance's accessors:
// rule data-pin first, then a vtable-validated member scan against the
// class's RTTI tables. Unpinned and unscannable -> invalid handle.
Handle fieldByPinOrScan(uintptr_t ci, const char *pinKey, const char *scanClass);

// Verify a vtable slot's BODY on device and report the field offset it reads,
// without ever calling it. The slot number in the rule is only a pointer to
// somewhere to look: this reads the two instruction words at that slot's
// function and accepts them only for the shapes
//   ldr w0, [x0, #imm] ; ret      (*outWide = false)
//   ldr x0, [x0, #imm] ; ret      (*outWide = true)
// which are pure, argument-free, side-effect-free field reads. Anything else -
// a real function, a stub returning a constant, a slot that shifted in this
// build - fails and nothing is read. That is what makes a second-party slot
// index usable at all: the index says where to look, the bytes decide.
bool verifiedGetterOffset(uintptr_t object, const char *className, size_t slot, bool wide,
                          uintptr_t *outOffset);

// Virtual call through a vtable slot with full region validation.
// Returns default-constructed Ret when the call cannot be safely made.
// Slot 0 is the first function at the object's address point.
template <int Slot, typename Ret, typename... Args>
Ret callVirtual(uintptr_t object, Args... args) {
    if (object == 0 || !inWritable(object)) return Ret{};
    uintptr_t vt = 0;
    memcpy(&vt, reinterpret_cast<const void *>(object), sizeof(vt));
    if (vt == 0 || !inGuestLib(vt)) return Ret{};
    uintptr_t fn = 0;
    memcpy(&fn, reinterpret_cast<const void *>(vt + Slot * sizeof(uintptr_t)),
           sizeof(fn));
    if (fn == 0 || !inExec(fn)) return Ret{};
    return reinterpret_cast<Ret (*)(void *, Args...)>(fn)(
        reinterpret_cast<void *>(object), args...);
}

// Read a field at a rule-pinned offset, returning the value or 0 on failure.
inline uintptr_t readField(uintptr_t object, const char *pinKey) {
    uintptr_t off = 0;
    if (!dataPin(pinKey, &off)) return 0;
    uintptr_t val = 0;
    if (!safeReadPtr(object, off, &val)) return 0;
    return val;
}

}  // namespace detail

}  // namespace mcpe

#endif  // ALTER_LAUNCHER_MCPE_CLASS_COMMON_H
