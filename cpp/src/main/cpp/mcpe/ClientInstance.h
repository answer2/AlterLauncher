#ifndef ALTER_LAUNCHER_MCPE_CLIENT_INSTANCE_H
#define ALTER_LAUNCHER_MCPE_CLIENT_INSTANCE_H

// ClientInstance - captured live by the update hook; see GameSdk::clientInstance.
// Everything else in the tree roots here.
#include <mcpe/ClassCommon.h>

namespace mcpe {

class GuiData;

class ClientInstance {
public:
    explicit ClientInstance(uintptr_t self) : self_(self) {}

    uintptr_t self() const { return self_; }
    bool valid() const { return self_ != 0; }

    // GuiData. Defers to the SDK ladder (virtual getter -> rule pin ->
    // validated scan -> sig getter -> hook-seen instance) rather than trusting
    // the reference layout's +0x688 blindly.
    Handle guiData() const;

    // Subsystems by pinned field, each with a scan fallback against the
    // class's own RTTI tables. Keys: minecraftField (+0xB0 ref),
    // levelRendererField (+0xC0), loopbackPacketSenderField (+0xD0).
    Handle minecraft() const;
    Handle levelRenderer() const;
    Handle loopbackPacketSender() const;

    // NEW: typed subsystem accessors (all fail-closed when unpinned).
    // getCamera: CI vtable slot or dat.cameraField.
    Handle getCamera() const;
    // getOptions: CI vtable slot (reference: slot 201).
    Handle getOptions() const;
    // getLocalPlayer: sig.clientInstanceGetLocalPlayer (BedrockTools pattern).
    Handle getLocalPlayer() const;
    // getRegion: CI vtable slot (reference: BT slot 31).
    Handle getRegion() const;
    // getLevel: dat.levelField or vtable slot.
    Handle getLevel() const;

    // Projection data lives inline in ClientInstance on 1.26.x: matrix at
    // +0x458 (column-major), fov pair at +0x498 / +0x4AC (per-axis tangent
    // halves). Pins dat.projectionMatrix / dat.fovXY. False when unpinned AND
    // the raw floats fail sanity (tangents > 0 and < 100, finite diagonal).
    bool readProjection(Mat4 *matrix, Vec2 *fovTangents) const;

    // Full world->screen through the captured projection: transform
    // (pos - camera) by the matrix, reject z >= 0, project with the half-angle
    // tangents and the GuiData window size.
    bool worldToScreenWithCamera(const Vec3 &cameraPos, const Vec3 &worldPos, Vec2 *out) const;

private:
    uintptr_t self_;
};

}  // namespace mcpe

#endif  // ALTER_LAUNCHER_MCPE_CLIENT_INSTANCE_H
