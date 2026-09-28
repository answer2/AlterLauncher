#ifndef ALTER_LAUNCHER_MCPE_LEVEL_RENDERER_PLAYER_H
#define ALTER_LAUNCHER_MCPE_LEVEL_RENDERER_PLAYER_H

// LevelRendererPlayer - camera-side render data.
#include <mcpe/ClassCommon.h>

namespace mcpe {

class LevelRendererPlayer {
public:
    explicit LevelRendererPlayer(uintptr_t self = 0) : self_(self) {}
    uintptr_t self() const { return self_; }
    bool valid() const { return self_ != 0; }

    // Get camera world position. Vtable key: lrpGetCameraPos.
    // Fallback: dat.lrpCameraPosField direct read.
    bool getCameraPos(Vec3 *out) const;

    // Get FOV (field of view in degrees). Vtable key: lrpGetFov.
    bool getFov(float *out) const;

    // Get the view-projection matrix. Pin: dat.lrpMatrixField.
    bool getMat4(Mat4 *out) const;

private:
    uintptr_t self_;
};

}  // namespace mcpe

#endif  // ALTER_LAUNCHER_MCPE_LEVEL_RENDERER_PLAYER_H
