#ifndef ALTER_LAUNCHER_MCPE_ACTOR_H
#define ALTER_LAUNCHER_MCPE_ACTOR_H

// Actor-space: the full Bedrock entity hierarchy. Named for the actual
// Minecraft classes (Actor → Player → LocalPlayer). Method names match
// MC's real API one-to-one; implementations use our safe ladder approach
// (dataPin + safeReadPtr + callVirtual) rather than hardcoded offsets.
#include <string>
#include <mcpe/core/ClassCommon.h>

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

    // Position. Primary path reads the movement component; optional
    // vtable.actorGetPos=Actor:N fallback is called only when its four words
    // decode as the AArch64 Vec3 HFA getter (s0-s2), never as a pointer return.
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

    // Game-thread only; returns an owned copy, or empty when the getter is unavailable.
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

    // Only the selected slot is supported until an inventory getter is resolved.
    Handle getItemStack(int slot) const;

    // Legacy aliases kept for the ClassTree facade and ModApi.
    bool position(Vec3 *out) const;
    bool rotation(Vec2 *pitchYaw) const;
};

// Self-test only: call Actor vtable slot 13 and compare its Vec3 against a
// result already obtained through the movement-component path. The slot is only
// called after its own instruction words decode to `ldr x8,[x0,#imm] ... ret`,
// so a build where that slot holds anything else is reported, not executed.
void probeGetPosVirtual(uintptr_t actor, const Vec3 &known);

// Self-test only: call the game's own Actor::getHealth() through the
// off.actorGetHealth pin and compare it with the entt component walk that
// Actor::getHealth() already did. The address is decoded as the health getter
// before being called, and the walk has already proved this entity carries the
// component - the getter itself reaches std::terminate when it does not.
void probeHealthGetter(uintptr_t actor, float walked);

// What step of the entt health walk stopped, and the values it had reached when
// it did. Actor::getHealth() reads only true/false; a pin that moved and an
// entity that genuinely has no health look identical from there, and telling
// those two apart is the whole job of this struct.
struct HealthWalkTrace {
    const char *stage = "not run";
    uintptr_t hash = 0;
    uintptr_t registry = 0;
    uintptr_t bucketBegin = 0, bucketEnd = 0, poolBegin = 0;
    uintptr_t pool = 0, storage = 0, element = 0;
    uint32_t id = 0, poolHash = 0, entry = 0;
    size_t links = 0;
    // The raw index word at the bucket head / chain link, kept because it is the
    // value that distinguishes an empty bucket (~0) from a real pool 0.
    uint64_t linkValue = 0;
    // The first eight words of the Actor itself. If the context pin moved, the
    // object image is what shows where the registry actually sits.
    uintptr_t words[8] = {0, 0, 0, 0, 0, 0, 0, 0};
};

// Run the walk once and fill the trace. health may be null to trace only.
bool traceHealthWalk(uintptr_t actor, float *health, HealthWalkTrace *trace);

// Self-test only: list every component key this entity carries (see the
// definition for why this names keys without relying on a name->hash function).
void probeComponentKeys(uintptr_t actor);

}  // namespace mcpe

#endif  // ALTER_LAUNCHER_MCPE_ACTOR_H
