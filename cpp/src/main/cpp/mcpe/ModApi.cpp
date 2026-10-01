// Implements mcpe/ModApi.h — the mod-facing umbrella over GameSdk's ladder,
// ClassTree's typed accessors, and SigScanner's rule-driven pattern cache.
#include <mcpe/ModApi.h>

#include <android/log.h>
#include <cstring>

#include <atomic>
#include <chrono>
#include <mutex>
#include <thread>
#include <vector>

#include <mcpe/ClassTree.h>
#include <mcpe/ClientInstance.h>
#include <mcpe/GameSDK.h>
#include <mcpe/GuiData.h>
#include <mcpe/Actor.h>
#include <mcpe/MinecraftGame.h>
#include <mcpe/Level.h>
#include <mcpe/Dimension.h>
#include <mcpe/Camera.h>
#include <mcpe/Options.h>
#include <mcpe/BlockSource.h>
#include <mcpe/ClientInstanceScreenModel.h>
#include <util/HookRules.h>
#include <util/RttiLookup.h>
#include <util/SigScanner.h>

namespace {
bool inGuestExec(uintptr_t addr) {
    static const std::string kGuestLib("libminecraftpe.so");
    return RttiLookup::inRegions(RttiLookup::regionsOf(kGuestLib), addr, true);
}
}  // namespace

namespace mcpe {

uintptr_t client() { return GameSdk::clientInstance(); }
bool inGame() { return GameSdk::isInGame(); }

void describe(char *buffer, size_t capacity) {
    if (buffer == nullptr || capacity == 0) return;
    buffer[0] = '\0';
    GameSdk::describe(buffer, capacity);
}

BuildInfo buildInfo() {
    BuildInfo info{};
    info.version = GameSdk::version();
    const HookRules::Rule *rule = GameSdk::rule();
    info.ruleName = (rule != nullptr && GameSdk::ruleActive()) ? rule->name.c_str() : "";
    info.libraryBase = GameSdk::guestBase();
    info.ruleActive = GameSdk::ruleActive();
    return info;
}

ObjectHandle guiData() {
    uintptr_t gd = GameSdk::guiData();
    return ObjectHandle{gd, gd != 0 ? GameSdk::guiDataSource() : "-"};
}

ObjectHandle minecraftGame() {
    MinecraftGameHandle mg = MinecraftGameHandle::fromSdk();
    return ObjectHandle{mg.h.ptr, mg.h.source};
}

ObjectHandle localPlayer() {
    uintptr_t ci = GameSdk::clientInstance();
    if (ci == 0) return ObjectHandle{0, "-"};
    Handle h = ClientInstance(ci).getLocalPlayer();
    return ObjectHandle{h.ptr, h.source};
}

ObjectHandle packetSender() {
    uintptr_t ci = GameSdk::clientInstance();
    if (ci == 0) return ObjectHandle{0, "-"};
    uintptr_t fn = SigScanner::resolve(std::string("clientInstanceGetPacketSender"));
    uintptr_t ps = callPointerGetter(ci, fn);
    return ObjectHandle{ps, ps != 0 ? "sig" : "-"};
}

ObjectHandle levelRenderer() {
    uintptr_t ci = GameSdk::clientInstance();
    if (ci == 0) return ObjectHandle{0, "-"};
    Handle h = ClientInstance(ci).levelRenderer();
    return ObjectHandle{h.ptr, h.source};
}

ObjectHandle level() {
    uintptr_t ci = GameSdk::clientInstance();
    if (ci == 0) return ObjectHandle{0, "-"};
    Handle h = ClientInstance(ci).getLevel();
    return ObjectHandle{h.ptr, h.source};
}

ObjectHandle camera() {
    uintptr_t ci = GameSdk::clientInstance();
    if (ci == 0) return ObjectHandle{0, "-"};
    Handle h = ClientInstance(ci).getCamera();
    return ObjectHandle{h.ptr, h.source};
}

ObjectHandle options() {
    uintptr_t ci = GameSdk::clientInstance();
    if (ci == 0) return ObjectHandle{0, "-"};
    Handle h = ClientInstance(ci).getOptions();
    return ObjectHandle{h.ptr, h.source};
}

ObjectHandle region() {
    uintptr_t ci = GameSdk::clientInstance();
    if (ci == 0) return ObjectHandle{0, "-"};
    Handle h = ClientInstance(ci).getRegion();
    return ObjectHandle{h.ptr, h.source};
}

ObjectHandle screenModel() {
    Handle h = ClientInstanceScreenModel::get();
    return ObjectHandle{h.ptr, h.source};
}

bool windowSize(Vec2 *gameSize, Vec2 *realSize) {
    uintptr_t gd = GameSdk::guiData();
    if (gd == 0) return false;
    return GuiData(gd).windowSize(gameSize, realSize);
}

bool worldToScreen(const Vec3 &cameraPos, const Vec3 &worldPos, Vec2 *outScreen) {
    uintptr_t ci = GameSdk::clientInstance();
    if (ci == 0) return false;
    return ClientInstance(ci).worldToScreenWithCamera(cameraPos, worldPos, outScreen);
}

bool worldToScreenAtPlayer(const Vec3 &worldPos, Vec2 *outScreen) {
    Vec3 cameraPos;
    if (!getPlayerPos(&cameraPos)) return false;
    return worldToScreen(cameraPos, worldPos, outScreen);
}

bool sendChatMessage(const std::string &text) { return GameSdk::requestMessage(text); }

bool sendChatMessageRaw(const char *utf8) {
    if (utf8 == nullptr) return false;
    return GameSdk::requestMessage(std::string(utf8));
}

size_t pumpCount() { return GameSdk::pumpCount(); }

// --- Actor/Player convenience ---

bool getPlayerPos(Vec3 *out) {
    ObjectHandle lp = localPlayer();
    if (!handleValid(lp)) return false;
    return LocalPlayer(lp.ptr).getPos(out);
}

bool getPlayerRotation(Vec2 *out) {
    ObjectHandle lp = localPlayer();
    if (!handleValid(lp)) return false;
    return LocalPlayer(lp.ptr).getViewAngles(out);
}

bool getPlayerHealth(float *out) {
    ObjectHandle lp = localPlayer();
    if (!handleValid(lp)) return false;
    return LocalPlayer(lp.ptr).getHealth(out);
}

bool getPlayerDimension(int *out) {
    ObjectHandle lp = localPlayer();
    if (!handleValid(lp)) return false;
    Handle dim = LocalPlayer(lp.ptr).getDimension();
    if (!dim.valid()) return false;
    return Dimension(dim.ptr).getDimensionId(out);
}

// --- MinecraftGame convenience ---

bool minecraftGameIsInGame() {
    MinecraftGameHandle mg = MinecraftGameHandle::fromSdk();
    if (!mg.valid()) return false;
    return mg.isInGame();
}

bool minecraftGameCanUseKeys() {
    MinecraftGameHandle mg = MinecraftGameHandle::fromSdk();
    if (!mg.valid()) return false;
    return mg.canUseKeys();
}

// --- Function finding ---

uintptr_t resolveSignature(const std::string &key) { return SigScanner::resolve(key); }

uintptr_t resolveOffset(const std::string &key) {
    const HookRules::Rule *rule = GameSdk::rule();
    if (rule == nullptr || !GameSdk::ruleActive()) return 0;
    uintptr_t rva = 0;
    if (!rule->lookupOffset(key, &rva)) return 0;
    return GameSdk::guestBase() + rva;
}

uintptr_t callPointerGetter(uintptr_t object, uintptr_t getterAddress) {
    if (object == 0 || getterAddress == 0) return 0;
    if ((object & 0x7) != 0 || (getterAddress & 0x3) != 0) return 0;
    if (!inGuestExec(getterAddress)) return 0;
    using GetterFn = uintptr_t (*)(void *);
    uintptr_t result = reinterpret_cast<GetterFn>(getterAddress)(reinterpret_cast<void *>(object));
    if (result == 0 || (result & 0x7) != 0) return 0;
    uintptr_t vt = 0;
    std::memcpy(&vt, reinterpret_cast<const void *>(result), sizeof(vt));
    if (vt == 0 || !inGuestExec(vt)) return 0;
    return result;
}


// --------------------------------------------------------------------------
// selfTest — one decisive pass over the whole API surface.
//
// Split in two because the pass has two very different kinds of work in it.
// The cold RTTI chain walks and the rule signature scans only read the guest's
// read-only mappings, so they run on their own thread (selfTestWarmup); the
// object-touching half calls into Bedrock virtuals, which are only safe from
// the game thread. Unsplit, the scans stalled the render loop for ~5 s.
// --------------------------------------------------------------------------
#define STLOG(...) __android_log_print(ANDROID_LOG_INFO, "AlterSelfTest", __VA_ARGS__)

namespace {

std::atomic<bool> g_selfTestWarm(false);

// Every class whose tables the scan fallbacks may have to build, and the
// whole rule signature set: both are pure memory work, so warming them costs
// the game thread nothing.
void warmCaches() {
    auto t0 = std::chrono::steady_clock::now();
    static const char *kScanClasses[] = {
        "Minecraft", "LevelRenderer", "LoopbackPacketSender", "Options", "BlockSource",
        "Camera", "Level", "GuiData", "Actor", "Player", "LocalPlayer",
    };
    for (size_t i = 0; i < sizeof(kScanClasses) / sizeof(kScanClasses[0]); i++)
        detail::tablesFor(kScanClasses[i]);
    std::vector<std::string> keys = SigScanner::keys();
    for (size_t i = 0; i < keys.size(); i++) SigScanner::resolve(keys[i]);
    STLOG("warm caches done: %zu class table(s), %zu signature(s) in %lld ms",
          sizeof(kScanClasses) / sizeof(kScanClasses[0]), keys.size(),
          (long long)std::chrono::duration_cast<std::chrono::milliseconds>(
              std::chrono::steady_clock::now() - t0).count());
    g_selfTestWarm.store(true, std::memory_order_release);
}

}  // namespace

void selfTestWarmup() {
    if (g_selfTestWarm.load(std::memory_order_acquire)) return;
    static std::once_flag once;
    std::call_once(once, [] { std::thread(warmCaches).detach(); });
}

bool selfTestWarm() { return g_selfTestWarm.load(std::memory_order_acquire); }

void selfTest() {
    auto t0 = std::chrono::steady_clock::now();
    char buf[256];
    GameSdk::describe(buf, sizeof(buf));
    STLOG("state: %s", buf);

    BuildInfo info = buildInfo();
    STLOG("buildInfo: version=%s rule=%s base=%p active=%d", info.version,
          info.ruleName, reinterpret_cast<void *>(info.libraryBase), info.ruleActive ? 1 : 0);

    // Root objects through the SDK ladders.
    uintptr_t mg = GameSdk::minecraftGame();
    STLOG("minecraftGame: %p", reinterpret_cast<void *>(mg));
    uintptr_t gd = GameSdk::guiData();
    STLOG("guiData: %p (%s)", reinterpret_cast<void *>(gd), GameSdk::guiDataSource());

    // GuiData metrics + send path.
    if (gd != 0) {
        GuiData gui(gd);
        Vec2 game{}, real{};
        if (gui.windowSize(&game, &real))
            STLOG("windowSize OK game=%.1f,%.1f real=%.1f,%.1f", game.x, game.y, real.x, real.y);
        else
            STLOG("windowSize FAIL (pin dat.guiDataWindowSize)");
    }

    // ClientInstance subsystem handles. Resolved ONE AT A TIME, each announced
    // before the call: some getters make a validated virtual call into the game,
    // and a diagnostic must never be able to take the host down without saying
    // which step it was on. The "calling" line is the crash boundary - the last
    // one without a matching result names the faulting getter.
    ClientInstance ci(GameSdk::clientInstance());
    auto probe = [&ci](const char *name, Handle (ClientInstance::*fn)() const) {
        STLOG("ci.%s: calling", name);
        Handle h = (ci.*fn)();
        STLOG("ci.%s: %s %p", name, h.valid() ? "OK" : "--", reinterpret_cast<void *>(h.ptr));
    };
    probe("minecraft", &ClientInstance::minecraft);
    probe("levelRenderer", &ClientInstance::levelRenderer);
    probe("loopbackPacketSender", &ClientInstance::loopbackPacketSender);
    probe("getOptions", &ClientInstance::getOptions);
    probe("getRegion", &ClientInstance::getRegion);
    probe("getCamera", &ClientInstance::getCamera);
    probe("getLevel", &ClientInstance::getLevel);
    probe("getLocalPlayer", &ClientInstance::getLocalPlayer);

    // Projection math with the pinned matrix/fov.
    Mat4 m{};
    Vec2 fov{};
    if (ci.readProjection(&m, &fov)) {
        STLOG("readProjection OK fov=%.3f,%.3f m00=%.3f", fov.x, fov.y, m.m[0]);
        Vec2 out{};
        if (ci.worldToScreenWithCamera(Vec3{0.f, 0.f, 0.f}, Vec3{0.f, 0.f, -4.f}, &out))
            STLOG("worldToScreen OK -> %.1f,%.1f", out.x, out.y);
        else
            STLOG("worldToScreen FAIL");
    } else {
        STLOG("readProjection FAIL (pins dat.projectionMatrix/fovXY)");
    }

    // Actor surface on the local player.
    Handle lpH = ci.getLocalPlayer();
    uintptr_t lp = lpH.ptr;
    STLOG("localPlayer: %p via %s", reinterpret_cast<void *>(lp), lpH.source);
    if (lp != 0) {
        Actor actor(lp);
        Vec3 pos{};
        STLOG("actor.getPos: %s (%.2f,%.2f,%.2f)", actor.getPos(&pos) ? "OK" : "--",
              pos.x, pos.y, pos.z);
        Vec2 ang{};
        STLOG("actor.getViewAngles: %s (%.2f,%.2f)", actor.getViewAngles(&ang) ? "OK" : "--",
              ang.x, ang.y);
        Vec3 mot{};
        STLOG("actor.getMotion: %s (%.3f,%.3f,%.3f)", actor.getMotion(&mot) ? "OK" : "--",
              mot.x, mot.y, mot.z);
        Vec2 chk{};
        if (actor.getViewAngles(&chk) && (chk.x < -90.f || chk.x > 90.f ||
                                          chk.y < -180.f || chk.y > 180.f))
            STLOG("actor.getViewAngles OUT OF RANGE - dat.playerRotPtr is not the rotation");
        float hp = 0.f;
        STLOG("actor.getHealth: %s", actor.getHealth(&hp) ? "OK" : "-- (pin dat.actorHealthField)");
        uint64_t rid = 0;
        STLOG("actor.getRuntimeID: %s", actor.getRuntimeID(&rid) ? "OK" : "-- (pin)");
        STLOG("actor.getNameTag: '%s'", actor.getNameTag().c_str());
        // Actor::isAlive() is a virtual reached through vtable.actorIsAlive=
        // Actor:47, an index derived by the same size-class alignment that
        // shifted the ClientInstance slots and faulted this process twice. A
        // self-test must never be able to take the host down, so the virtual is
        // reported UNVERIFIED here instead of called on the game thread; it is
        // static-audited in game-hooks.ini.
        STLOG("actor.isAlive: -- (virtual Actor:47 not called on the game thread)");
        STLOG("actor.getDimension: %p", reinterpret_cast<void *>(actor.getDimension().ptr));
        STLOG("actor.getRegion: %p", reinterpret_cast<void *>(actor.getRegion().ptr));
        LocalPlayer me(lp);
        STLOG("lp.getGameMode: %p", reinterpret_cast<void *>(me.getGameMode().ptr));
        STLOG("lp.getHeldItem: %p", reinterpret_cast<void *>(me.getHeldItem().ptr));
        STLOG("lp.getMoveInputHandler: %p", reinterpret_cast<void *>(me.getMoveInputHandler().ptr));
    } else {
        STLOG("localPlayer UNRESOLVED (sig.clientInstanceGetLocalPlayer empty; the "
              "ClientInstance slot rung is retired - see the rule note)");
    }

    // ModApi convenience layer.
    Vec3 p{};
    STLOG("modapi.getPlayerPos: %s", getPlayerPos(&p) ? "OK" : "--");
    Vec2 r{};
    STLOG("modapi.getPlayerRotation: %s", getPlayerRotation(&r) ? "OK" : "--");
    float health = 0.f;
    STLOG("modapi.getPlayerHealth: %s", getPlayerHealth(&health) ? "OK" : "--");
    int dim = -1;
    STLOG("modapi.getPlayerDimension: %s", getPlayerDimension(&dim) ? "OK" : "--");
    STLOG("modapi.minecraftGameIsInGame: %d", minecraftGameIsInGame() ? 1 : 0);
    STLOG("modapi.minecraftGameCanUseKeys: %d", minecraftGameCanUseKeys() ? 1 : 0);
    Vec2 gs{}, rs{};
    STLOG("modapi.windowSize: %s", windowSize(&gs, &rs) ? "OK" : "--");
    if (lp != 0) {
        Vec2 scr{};
        STLOG("modapi.worldToScreenAtPlayer: %s", worldToScreenAtPlayer(Vec3{0.f,0.f,0.f}, &scr) ? "OK" : "--");
    }

    // Signature toolbox.
    const char *keys[] = {"guiDataAddMessage", "clientInstanceGetLocalPlayer",
                          "clientInstanceGetPacketSender", "clientInstanceGetGuiData",
                          "clientInstanceGetMinecraftGame"};
    for (const char *k : keys) {
        uintptr_t a = resolveSignature(std::string(k));
        STLOG("sig.%s: %s 0x%lx", k, a ? "OK" : "--", (unsigned long) a);
    }
    STLOG("off.guiDataAddMessage: 0x%lx", (unsigned long) resolveOffset(std::string("guiDataAddMessage")));
    STLOG("pumpCount: %zu", pumpCount());
    STLOG("game-thread pass: %lld ms", (long long)std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - t0).count());
    STLOG("=== SELFTEST DONE ===");
}
}  // namespace mcpe