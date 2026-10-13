#include <mcpe/client/Camera.h>
#include <cstring>
#include <mcpe/core/GameSDK.h>

namespace mcpe {
using detail::safeReadPtr;
using detail::safeReadF32;
using detail::dataPin;
using detail::inWritable;

bool Camera::getPos(Vec3 *out) const {
    if (self_ == 0 || out == nullptr) return false;
    uintptr_t off = 0;
    if (!dataPin("cameraPosField", &off)) return false;
    float x = 0.f, y = 0.f, z = 0.f;
    if (!safeReadF32(self_, off, &x) || !safeReadF32(self_, off+4, &y) ||
        !safeReadF32(self_, off+8, &z)) return false;
    out->x = x; out->y = y; out->z = z;
    return true;
}

bool Camera::setPos(const Vec3 &pos) const {
    if (self_ == 0 || !inWritable(self_)) return false;
    uintptr_t off = 0;
    if (!dataPin("cameraPosField", &off)) return false;
    memcpy(reinterpret_cast<void *>(self_ + off), &pos.x, 4);
    memcpy(reinterpret_cast<void *>(self_ + off + 4), &pos.y, 4);
    memcpy(reinterpret_cast<void *>(self_ + off + 8), &pos.z, 4);
    return true;
}

bool Camera::depart() const {
    if (self_ == 0 || !inWritable(self_)) return false;
    uintptr_t off = 0;
    if (!dataPin("cameraStateField", &off)) return false;
    int32_t val = 2;
    memcpy(reinterpret_cast<void *>(self_ + off), &val, sizeof(val));
    return true;
}

bool Camera::lock() const {
    if (self_ == 0 || !inWritable(self_)) return false;
    uintptr_t off = 0;
    if (!dataPin("cameraStateField", &off)) return false;
    int32_t val = 1;
    memcpy(reinterpret_cast<void *>(self_ + off), &val, sizeof(val));
    return true;
}

bool Camera::upDepart() const {
    if (self_ == 0 || !inWritable(self_)) return false;
    uintptr_t off = 0;
    if (!dataPin("cameraStateField", &off)) return false;
    int32_t val = 0;
    memcpy(reinterpret_cast<void *>(self_ + off), &val, sizeof(val));
    return true;
}

bool Camera::unLock() const {
    return upDepart();
}

bool Camera::setOffset(const Vec3 &offset) const {
    if (self_ == 0 || !inWritable(self_)) return false;
    uintptr_t off = 0;
    if (!dataPin("cameraOffsetField", &off)) return false;
    memcpy(reinterpret_cast<void *>(self_ + off), &offset.x, 4);
    memcpy(reinterpret_cast<void *>(self_ + off + 4), &offset.y, 4);
    memcpy(reinterpret_cast<void *>(self_ + off + 8), &offset.z, 4);
    return true;
}

}  // namespace mcpe
