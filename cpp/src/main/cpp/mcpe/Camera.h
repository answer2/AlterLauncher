#ifndef ALTER_LAUNCHER_MCPE_CAMERA_H
#define ALTER_LAUNCHER_MCPE_CAMERA_H

// Camera - the render camera position and state.
#include <mcpe/ClassCommon.h>

namespace mcpe {

class Camera {
public:
    explicit Camera(uintptr_t self = 0) : self_(self) {}
    uintptr_t self() const { return self_; }
    bool valid() const { return self_ != 0; }

    // Get camera world position. Reference: Vec3* at +0x348 or via
    // LevelRendererPlayer::getCameraPos().
    bool getPos(Vec3 *out) const;

    // Set camera position (for free-cam).
    bool setPos(const Vec3 &pos) const;

    // Depart/lock camera (for spectate mode).
    bool depart() const;
    bool lock() const;
    bool upDepart() const;
    bool unLock() const;

    // Set camera offset.
    bool setOffset(const Vec3 &offset) const;

private:
    uintptr_t self_;
};

}  // namespace mcpe

#endif  // ALTER_LAUNCHER_MCPE_CAMERA_H
