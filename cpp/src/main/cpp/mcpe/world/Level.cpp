#include <mcpe/world/Level.h>
#include <cstring>
#include <mcpe/core/GameSDK.h>

namespace mcpe {
using detail::safeReadPtr;
using detail::safeReadI32;
using detail::dataPin;
using detail::inWritable;

Handle Level::getRegion() const {
    if (self_ == 0) return Handle{};
    uintptr_t off = 0;
    if (!dataPin("levelRegionField", &off)) return Handle{};
    uintptr_t val = 0;
    if (!safeReadPtr(self_, off, &val) || !inWritable(val)) return Handle{};
    return Handle{val, "pin"};
}

bool Level::getDimensionId(int *out) const {
    if (self_ == 0 || out == nullptr) return false;
    uintptr_t off = 0;
    if (!dataPin("levelDimensionIdField", &off)) return false;
    int32_t val = 0;
    if (!safeReadI32(self_, off, &val)) return false;
    *out = val;
    return true;
}

bool Level::getSpawnPosition(Vec3 *out) const {
    if (self_ == 0 || out == nullptr) return false;
    uintptr_t off = 0;
    if (!dataPin("levelSpawnPosField", &off)) return false;
    float x = 0.f, y = 0.f, z = 0.f;
    if (!detail::safeReadF32(self_, off, &x) || !detail::safeReadF32(self_, off+4, &y) ||
        !detail::safeReadF32(self_, off+8, &z)) return false;
    out->x = x; out->y = y; out->z = z;
    return true;
}

bool Level::getTime(int64_t *out) const {
    if (self_ == 0 || out == nullptr) return false;
    uintptr_t off = 0;
    if (!dataPin("levelTimeField", &off)) return false;
    if (!inWritable(self_)) return false;
    int64_t val = 0;
    memcpy(&val, reinterpret_cast<const void *>(self_ + off), sizeof(val));
    *out = val;
    return true;
}

}  // namespace mcpe
