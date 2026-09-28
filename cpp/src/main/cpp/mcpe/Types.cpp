#include <mcpe/Types.h>

#include <cmath>
#include <cstdio>
#include <string>

// Vec2/Vec3/UUID are declared at global scope in Types.h; their out-of-line
// members must be defined at global scope too. worldToScreen lives in
// namespace mcpe (see the declaration in Types.h's mcpe block).

float Vec2::magnitude() const {
    return std::sqrt(x * x + y * y);
}

float Vec3::magnitude() const {
    return std::sqrt(x * x + y * y + z * z);
}

std::string UUID::toString() const {
    char buf[37];
    std::snprintf(buf, sizeof(buf),
                  "%08x-%04x-%04x-%04x-%04x%08x",
                  static_cast<uint32_t>(most >> 32),
                  static_cast<uint16_t>(most >> 16),
                  static_cast<uint16_t>(most),
                  static_cast<uint16_t>(least >> 48),
                  static_cast<uint16_t>(least >> 32),
                  static_cast<uint32_t>(least));
    return std::string(buf);
}

namespace mcpe {

bool worldToScreen(const Vec3 &viewPos,
                   float tanHalfFovX,
                   float tanHalfFovY,
                   float screenWidth,
                   float screenHeight,
                   Vec2 *outScreen) {
    if (outScreen == nullptr) return false;
    if (tanHalfFovX <= 0.f || tanHalfFovY <= 0.f) return false;
    if (screenWidth <= 0.f || screenHeight <= 0.f) return false;
    // The view-space point must sit in front of the camera (negative z).
    if (viewPos.z >= 0.f) return false;
    const float invZ = 1.f / (-viewPos.z);
    outScreen->x = screenWidth * 0.5f + (screenWidth * 0.5f) * (viewPos.x * invZ * tanHalfFovX);
    outScreen->y = screenHeight * 0.5f - (screenHeight * 0.5f) * (viewPos.y * invZ * tanHalfFovY);
    return true;
}

}  // namespace mcpe
