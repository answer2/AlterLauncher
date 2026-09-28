#include <mcpe/LevelRenderer.h>

#include <cstring>

// LevelRenderer + camera-probe helpers, verbatim from the original
// single-tree file.
namespace mcpe {

using detail::inWritable;
using detail::safeReadPtr;
using detail::safeReadF32;
using detail::dataPin;

uintptr_t LevelRenderer::rendererPlayer() const {
    uintptr_t off = 0;
    if (!dataPin("levelRendererPlayerField", &off)) return 0;  // d0x3B0 ref
    uintptr_t cand = 0;
    if (!safeReadPtr(self_, off, &cand)) return 0;
    return inWritable(cand) ? cand : 0;
}

bool tryReadCameraPos(uintptr_t rendererPlayer, const Vec3 &nearPlayer, Vec3 *out) {
    if (rendererPlayer == 0 || out == nullptr || !inWritable(rendererPlayer)) return false;
    for (uintptr_t off = 0; off + 12 <= 0x100; off += 4) {
        float x = 0.f, y = 0.f, z = 0.f;
        if (!safeReadF32(rendererPlayer, off, &x)) continue;
        if (!safeReadF32(rendererPlayer, off + 4, &y)) continue;
        if (!safeReadF32(rendererPlayer, off + 8, &z)) continue;
        const float dx = x - nearPlayer.x, dy = y - nearPlayer.y, dz = z - nearPlayer.z;
        if (dx * dx + dy * dy + dz * dz > 16.f * 16.f) continue;
        out->x = x;
        out->y = y;
        out->z = z;
        return true;
    }
    return false;
}

}  // namespace mcpe
