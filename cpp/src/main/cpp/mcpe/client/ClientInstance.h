#ifndef ALTER_LAUNCHER_MCPE_CLIENT_INSTANCE_H
#define ALTER_LAUNCHER_MCPE_CLIENT_INSTANCE_H

// ClientInstance - captured live by the update hook; see GameSdk::clientInstance.
// Everything else in the tree roots here.
#include <mcpe/core/ClassCommon.h>

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

    // The frame's matrices, read off GameRenderer (MinecraftGame ->
    // gameRenderer), which is what actually owns them: Latite reads
    // lastViewMatrix / lastProjectionMatrix there and no reference project reads
    // either as a ClientInstance member. Both pins are dat.gr*Matrix; the view
    // matrix comes back in glm's column-major order regardless of how the game
    // stores it, and `focal` is the projection's two diagonal terms, which are
    // exactly the coefficients mcpe::worldToScreen multiplies by. False when
    // unpinned, when GameRenderer is not found, or when either block fails its
    // shape check - and in that case the layout probe logs what is really there.
    bool readViewProjection(Mat4 *view, Vec2 *focal) const;

    // Full world->screen: rotate (worldPos - cameraPos) by the view matrix,
    // reject z >= 0, project with the focal terms and the GuiData window size.
    bool worldToScreenWithCamera(const Vec3 &cameraPos, const Vec3 &worldPos, Vec2 *out) const;

private:
    uintptr_t self_;
};

// Read-only heap-layout probe: log every float block in `object` that carries a
// GL perspective signature, once per label, together with whether the block
// 0x40/0x80 before it is view-shaped (Latite's GameRenderer keeps the pair
// exactly 0x80 apart). No getter proves the layout offline, so this is how the
// dat.gr*Matrix pins get measured. It changes no answer any caller gets.
void probeProjectionLayout(uintptr_t object, const char *label);

// Read-only second opinion: locate the LevelRendererCamera by its own RTTI,
// re-measure its view/projection blocks by shape (no version's offsets assumed),
// and compare them numerically against GameRenderer's. Logs only; changes no
// answer any caller gets.
void crossCheckFrameMatrices(uintptr_t clientInstance);

}  // namespace mcpe

#endif  // ALTER_LAUNCHER_MCPE_CLIENT_INSTANCE_H
