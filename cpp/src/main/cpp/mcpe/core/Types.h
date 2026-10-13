#ifndef ALTER_LAUNCHER_MCPE_TYPES_H
#define ALTER_LAUNCHER_MCPE_TYPES_H

#include <stdint.h>
#include <string>

// Minimal value types for the guest's math structs. The layouts come from the
// reference Android build (SDK/types.h of D:\\逆向分析\\1\\cpp) and match what the
// device shows. Only what the SDK surface needs is declared here; every field is
// position-independent plain data.

struct Vec2 {
    float x;
    float y;

    Vec2() : x(0.f), y(0.f) {}
    Vec2(float x_, float y_) : x(x_), y(y_) {}
    Vec2 &operator=(const Vec2 &o) { x = o.x; y = o.y; return *this; }
    bool operator==(const Vec2 &o) const { return x == o.x && y == o.y; }
    bool operator!=(const Vec2 &o) const { return !(*this == o); }
    Vec2 operator+(const Vec2 &o) const { return Vec2(x + o.x, y + o.y); }
    Vec2 operator-(const Vec2 &o) const { return Vec2(x - o.x, y - o.y); }
    Vec2 operator*(float f) const { return Vec2(x * f, y * f); }
    float magnitude() const;
};

struct Vec3 {
    float x;
    float y;
    float z;

    Vec3() : x(0.f), y(0.f), z(0.f) {}
    Vec3(float x_, float y_, float z_) : x(x_), y(y_), z(z_) {}
    Vec3 &operator=(const Vec3 &o) { x = o.x; y = o.y; z = o.z; return *this; }
    bool operator==(const Vec3 &o) const { return x == o.x && y == o.y && z == o.z; }
    bool operator!=(const Vec3 &o) const { return !(*this == o); }
    Vec3 operator+(const Vec3 &o) const { return Vec3(x + o.x, y + o.y, z + o.z); }
    Vec3 operator-(const Vec3 &o) const { return Vec3(x - o.x, y - o.y, z - o.z); }
    Vec3 operator*(float f) const { return Vec3(x * f, y * f, z * f); }
    float dot(const Vec3 &o) const { return x * o.x + y * o.y + z * o.z; }
    float squaredLength() const { return x * x + y * y + z * z; }
    float magnitude() const;
};

// Integer-position vector for block coordinates.
struct Vec3i {
    int x;
    int y;
    int z;

    Vec3i() : x(0), y(0), z(0) {}
    Vec3i(int x_, int y_, int z_) : x(x_), y(y_), z(z_) {}
    bool operator==(const Vec3i &o) const { return x == o.x && y == o.y && z == o.z; }
    bool operator!=(const Vec3i &o) const { return !(*this == o); }
};

// Axis-aligned bounding box, matching the game's AABB layout.
struct AABB {
    float minX, minY, minZ, maxX, maxY, maxZ;

    AABB() : minX(0), minY(0), minZ(0), maxX(0), maxY(0), maxZ(0) {}
    AABB(float x1, float y1, float z1, float x2, float y2, float z2)
        : minX(x1), minY(y1), minZ(z1), maxX(x2), maxY(y2), maxZ(z2) {}
    float width() const { return maxX - minX; }
    float height() const { return maxY - minY; }
    float depth() const { return maxZ - minZ; }
    Vec3 center() const { return Vec3((minX+maxX)*0.5f, (minY+maxY)*0.5f, (minZ+maxZ)*0.5f); }
};

// Column-major 4x4, same memory as glm::mat4 (the game stores it that way).
struct Mat4 {
    float m[16];

    float &at(int col, int row) { return m[col * 4 + row]; }
    float at(int col, int row) const { return m[col * 4 + row]; }
};

// RGBA color matching mce::Color from the reference SDK.
struct Color {
    float r, g, b, a;
    Color() : r(0.f), g(0.f), b(0.f), a(1.f) {}
    Color(float r_, float g_, float b_, float a_ = 1.f) : r(r_), g(g_), b(b_), a(a_) {}
};

// UUID matching mce::UUID / Minecraft's two-uint64 representation.
struct UUID {
    uint64_t most;   // mLow
    uint64_t least;  // mHigh
    UUID() : most(0), least(0) {}
    UUID(uint64_t m, uint64_t l) : most(m), least(l) {}
    std::string toString() const;
};

namespace mcpe {

// Project a camera-space offset through the perspective half-angles, exactly
// as the reference OWorldToScreen does:
//   screen.x = w/2 + (w/2 * x / -z * tanX)
//   screen.y = h/2 - (h/2 * y / -z * tanY)
// `view` is world minus camera position; points at or behind the camera
// (view.z >= 0) are rejected. Returns false when nothing can be drawn.
bool worldToScreen(const Vec3 &view, float tanHalfFovX, float tanHalfFovY,
                   float screenWidth, float screenHeight, Vec2 *outScreen);

}  // namespace mcpe

#endif  // ALTER_LAUNCHER_MCPE_TYPES_H
