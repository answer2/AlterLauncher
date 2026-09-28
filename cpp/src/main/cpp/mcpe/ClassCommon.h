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

#include <mcpe/Types.h>

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

// Region-checked reads. inWritable gates every dereference of a heap object;
// safeReadPtr/safeReadF32 refuse non-canonical or non-finite values.
bool inWritable(uintptr_t addr);
bool inExec(uintptr_t addr);
bool safeReadPtr(uintptr_t object, uintptr_t offset, uintptr_t *out);
bool safeReadF32(uintptr_t object, uintptr_t offset, float *out);
bool safeReadU8(uintptr_t object, uintptr_t offset, uint8_t *out);
bool safeReadU32(uintptr_t object, uintptr_t offset, uint32_t *out);
bool safeReadI32(uintptr_t object, uintptr_t offset, int32_t *out);

// The rule pin for a dat.<key> entry (object-relative offset, 'd' marker).
bool dataPin(const char *key, uintptr_t *offset);

// Every RTTI table of a class, cached per name (primary + aux variants).
const std::vector<uintptr_t> &tablesFor(const char *className);

// Generic subsystem-field ladder shared by ClientInstance's accessors:
// rule data-pin first, then a vtable-validated member scan against the
// class's RTTI tables. Unpinned and unscannable -> invalid handle.
Handle fieldByPinOrScan(uintptr_t ci, const char *pinKey, const char *scanClass);

// Virtual call through a vtable slot with full region validation.
// Returns default-constructed Ret when the call cannot be safely made.
// Slot number is the vtable index (NOT byte offset; multiply by 8 internally).
template <int Slot, typename Ret, typename... Args>
Ret callVirtual(uintptr_t object, Args... args) {
    if (object == 0 || !inWritable(object)) return Ret{};
    uintptr_t vt = 0;
    memcpy(&vt, reinterpret_cast<const void *>(object), sizeof(vt));
    if (vt == 0 || !inExec(vt)) return Ret{};
    uintptr_t fn = 0;
    memcpy(&fn, reinterpret_cast<const void *>(vt + Slot * sizeof(void *)), sizeof(fn));
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
