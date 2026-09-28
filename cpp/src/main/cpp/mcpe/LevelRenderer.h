#ifndef ALTER_LAUNCHER_MCPE_LEVEL_RENDERER_H
#define ALTER_LAUNCHER_MCPE_LEVEL_RENDERER_H

// Render-space: the renderer and its camera-side helpers.
#include <mcpe/ClassCommon.h>

namespace mcpe {

class LevelRenderer {
public:
    explicit LevelRenderer(uintptr_t self) : self_(self) {}
    uintptr_t self() const { return self_; }
    bool valid() const { return self_ != 0; }

    // LevelRendererPlayer subobject at +0x3B0 per the reference. There is no
    // RTTI-validated table to scan against, so this stays a pure pin:
    // dat.levelRendererPlayerField=d0x3B0. Unpinned -> 0.
    uintptr_t rendererPlayer() const;

    // Typed Handle version of rendererPlayer().
    Handle getLevelRendererPlayer() const {
        uintptr_t rp = rendererPlayer();
        return rp != 0 ? Handle{rp, "pin"} : Handle{};
    }

private:
    uintptr_t self_;
};

// The camera position the reference obtains by calling a pinned function on
// LevelRendererPlayer (Vec3* getCameraPos()). Calling arbitrary pinned
// functions blind is exactly what this framework refuses to do, so instead:
// readProjection + a caller-supplied anchor. When the renderer-player pin IS
// present, tryReadCameraPos probes the first 0x100 bytes for three finite
// floats that match the last-known player position within 16 blocks - good
// enough for ESP math, and it can never crash because every read is region-
// checked. Returns false unless a candidate passes.
bool tryReadCameraPos(uintptr_t rendererPlayer, const Vec3 &nearPlayer, Vec3 *out);

}  // namespace mcpe

#endif  // ALTER_LAUNCHER_MCPE_LEVEL_RENDERER_H
