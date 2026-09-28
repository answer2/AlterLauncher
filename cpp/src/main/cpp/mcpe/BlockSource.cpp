#include <mcpe/BlockSource.h>
#include <cstring>
#include <mcpe/GameSDK.h>
#include <util/HookRules.h>

namespace mcpe {
using detail::safeReadPtr;
using detail::safeReadI32;
using detail::dataPin;
using detail::inWritable;
using detail::inExec;

Handle BlockSource::getBlock(int x, int y, int z) const {
    if (self_ == 0) return Handle{};
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot = 0;
    if (!rule || !rule->lookupVtable(std::string("blockSourceGetBlock"), nullptr, &slot)) return Handle{};
    uintptr_t vt = 0;
    if (!safeReadPtr(self_, 0, &vt) || !inExec(vt)) return Handle{};
    uintptr_t fn = 0;
    memcpy(&fn, reinterpret_cast<const void *>(vt + slot * 8), sizeof(fn));
    if (fn == 0 || !inExec(fn)) return Handle{};
    using Fn = uintptr_t (*)(void *, int, int, int);
    uintptr_t result = reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_), x, y, z);
    if (result == 0 || !inWritable(result)) return Handle{};
    return Handle{result, "vcall"};
}

Handle BlockSource::getBlock(const Vec3i &pos) const {
    return getBlock(pos.x, pos.y, pos.z);
}

bool BlockSource::hasChunksAt(const Vec3i &pos) const {
    if (self_ == 0) return false;
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot = 0;
    if (!rule || !rule->lookupVtable(std::string("blockSourceHasChunksAt"), nullptr, &slot)) return false;
    uintptr_t vt = 0;
    if (!safeReadPtr(self_, 0, &vt) || !inExec(vt)) return false;
    uintptr_t fn = 0;
    memcpy(&fn, reinterpret_cast<const void *>(vt + slot * 8), sizeof(fn));
    if (fn == 0 || !inExec(fn)) return false;
    using Fn = bool (*)(void *, const Vec3i *);
    return reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_), &pos);
}

bool BlockSource::getDimensionId(int *out) const {
    if (self_ == 0 || out == nullptr) return false;
    const HookRules::Rule *rule = GameSdk::rule();
    size_t slot = 0;
    if (!rule || !rule->lookupVtable(std::string("blockSourceGetDimensionId"), nullptr, &slot)) return false;
    uintptr_t vt = 0;
    if (!safeReadPtr(self_, 0, &vt) || !inExec(vt)) return false;
    uintptr_t fn = 0;
    memcpy(&fn, reinterpret_cast<const void *>(vt + slot * 8), sizeof(fn));
    if (fn == 0 || !inExec(fn)) return false;
    using Fn = int (*)(void *);
    *out = reinterpret_cast<Fn>(fn)(reinterpret_cast<void *>(self_));
    return true;
}

}  // namespace mcpe