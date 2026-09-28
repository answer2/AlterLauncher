#ifndef ALTER_LAUNCHER_MCPE_ACTOR_H
#define ALTER_LAUNCHER_MCPE_ACTOR_H

// Actor-space: the full Bedrock entity hierarchy. Named for the actual
// Minecraft classes (Actor → Player → LocalPlayer). Method names match
// MC's real API one-to-one; implementations use our safe ladder approach
// (dataPin + safeReadPtr + callVirtual) rather than hardcoded offsets.
#include <string>
#include <mcpe/ClassCommon.h>

namespace mcpe {

class ClientInstance;
class Dimension;
class BlockSource;
class GameMode;
class ItemStack;
class MobEffectInstance;
class LevelRendererPlayer;

// ---------------------------------------------------------------------------
// Actor - the base entity class. All entities (players, mobs, items,
// projectiles) inherit from Actor.
// ---------------------------------------------------------------------------
class Actor {
public:
    explicit Actor(uintptr_t self = 0) : self_(self) {}
    uintptr_t self() const { return self_; }
    bool valid() const { return self_ != 0; }

    // Position: virtual getter getPos() returns Vec3*.
    // Rule pin: vtable.actorGetPos=Actor:N
    bool getPos(Vec3 *out) const;

    // Position (alternative getter, same data).
    bool getPosition(Vec3 *out) const;

    // Set position: virtual void setPos(const Vec3&).
    // Rule pin: vtable.actorSetPos=Actor:N
    bool setPos(const Vec3 &pos) const;

    // View angles (pitch, yaw) as a Vec2. Reference reads through a
    // component vector double-indirection; we pin dat.playerRotPtr.
    bool getViewAngles(Vec2 *out) const;

    // Yaw / pitch as separate floats.
    bool getYaw(float *out) const;
    bool getPitch(float *out) const;

    // Motion vector (velocity). Virtual getter getMotion() returns Vec3*.
    bool getMotion(Vec3 *out) const;

    // Lerp motion (apply velocity change).
    bool lerpMotion(const Vec3 &motion) const;

    // Jump from ground.
    bool jumpFromGround() const;

    // Is the actor alive (not dead/removed).
    bool isAlive() const;

    // Is the actor invisible.
    bool isInvisible() const;

    // Is the actor on ground.
    bool isOnGround() const;

    // Swing arm (attack animation).
    bool swing() const;

    // Get entity type ID.
    int getEntityTypeId() const;

    // Get runtime ID (unique per session).
    bool getRuntimeID(uint64_t *out) const;

    // Get unique ID (persistent).
    bool getUniqueID(uint64_t *out) const;

    // Get health (current HP).
    bool getHealth(float *out) const;

    // Get max health.
    bool getMaxHealth(float *out) const;

    // Get bounding box.
    bool getAABB(AABB *out) const;

    // Get width and height.
    bool getWidth(float *out) const;
    bool getHeight(float *out) const;

    // Set size.
    bool setSize(float width, float height) const;

    // Get dimension.
    Handle getDimension() const;

    // Get region (BlockSource).
    Handle getRegion() const;

    // Get vehicle actor (null if not riding).
    Handle getVehicle() const;

    // Stop riding vehicle.
    bool stopRiding() const;

    // Teleport to position.
    bool actorTeleportTo(const Vec3 &pos) const;

    // Start/stop spin attack (trident riptide).
    bool startSpinAttack() const;
    bool stopSpinAttack() const;

    // Start/stop swimming.
    bool startSwimming() const;
    bool stopSwimming() const;

    // Get eyes position (position + eye height).
    bool getEyesPos(Vec3 *out) const;

    // Get selected hotbar slot index.
    bool getSelectedSlot(int *out) const;

    // Is the actor removed from the level.
    bool isRemoved() const;

    // Get name tag.
    std::string getNameTag() const;

    // Get the actor's vtable pointer (for identity checks).
    uintptr_t vtable() const;

protected:
    uintptr_t self_;
};

// ---------------------------------------------------------------------------
// Player : Actor - adds inventory, game type, container access.
// ---------------------------------------------------------------------------
class Player : public Actor {
public:
    explicit Player(uintptr_t self = 0) : Actor(self) {}

    // Set player game type: 0=survival, 1=creative, 2=adventure, 3=spectator.
    bool setPlayerGameType(int type) const;

    // Get current game type.
    bool getPlayerGameType(int *out) const;

    // Get supplies (inventory) container.
    Handle getSupplies() const;

    // Get armor container.
    Handle getArmorContainer() const;

    // Set armor in slot (0=helmet,1=chest,2=legs,3=boots).
    bool setArmor(int slot, uintptr_t itemStack) const;

    // Get armor item in slot.
    Handle getArmor(int slot) const;

    // Teleport player (uses player-specific teleport).
    bool playerTeleportTo(const Vec3 &pos) const;

    // Can open container screen.
    bool canOpenContainerScreen() const;
};

// ---------------------------------------------------------------------------
// LocalPlayer : Player - the client-side player with input and abilities.
// ---------------------------------------------------------------------------
class LocalPlayer : public Player {
public:
    explicit LocalPlayer(uintptr_t self = 0) : Player(self) {}

    // Get owning ClientInstance.
    Handle getClientInstance() const;

    // Get GameMode (attack/build/destroy).
    Handle getGameMode() const;

    // Get LayeredAbilities.
    Handle getAbilities() const;

    // Snap head rotation to pitch/yaw.
    bool snapToHeadRot(float pitch, float yaw) const;

    // Set sprinting state.
    bool setSprinting(bool enable) const;

    // Apply turn delta to view angles.
    bool applyTurnDelta(float pitchDelta, float yawDelta) const;

    // Add mob effect.
    bool addEffect(uintptr_t effectInstance) const;

    // Remove all mob effects.
    bool removeAllEffects() const;

    // Get MoveInputHandler (input state).
    Handle getMoveInputHandler() const;

    // Get the held item stack (main hand).
    Handle getHeldItem() const;

    // Get item stack in specific slot.
    Handle getItemStack(int slot) const;

    // Legacy aliases kept for the ClassTree facade and ModApi.
    bool position(Vec3 *out) const;
    bool rotation(Vec2 *pitchYaw) const;
};

}  // namespace mcpe

#endif  // ALTER_LAUNCHER_MCPE_ACTOR_H
