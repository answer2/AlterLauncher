// Implements mcpe/ModApi.h — the mod-facing umbrella over GameSdk's ladder,
// ClassTree's typed accessors, and SigScanner's rule-driven pattern cache.
#include <mcpe/api/ModApi.h>

#include <android/log.h>
#include <cstdio>
#include <cstring>

#include <atomic>
#include <chrono>
#include <cmath>
#include <mutex>
#include <thread>
#include <vector>

#include <mcpe/api/SdkHook.h>
#include <mcpe/core/ClassTree.h>
#include <mcpe/client/ClientInstance.h>
#include <mcpe/core/GameSDK.h>
#include <mcpe/client/GuiData.h>
#include <mcpe/actor/Actor.h>
#include <mcpe/client/MinecraftGame.h>
#include <mcpe/world/Level.h>
#include <mcpe/world/Dimension.h>
#include <mcpe/client/Camera.h>
#include <mcpe/client/Options.h>
#include <mcpe/world/BlockSource.h>
#include <mcpe/client/ClientInstanceScreenModel.h>
#include <memory/HookRules.h>
#include <memory/RttiLookup.h>
#include <memory/SigScanner.h>

namespace {
bool inGuestExec(uintptr_t addr) {
    static const std::string kGuestLib("libminecraftpe.so");
    return RttiLookup::inRegions(RttiLookup::regionsOf(kGuestLib), addr, true);
}

// The vtable pointer of a C++ object lives in .data.rel.ro, which on 1.26.45.1 is
// the SECOND PT_LOAD (vaddr 0x11c47220..) and therefore never inside the giant
// r-xp text mapping (0..0x11c43220). Testing exec-ness there rejected every
// genuine object, which made this helper — and everything routed through it —
// permanently fail closed. Any mapping of the guest library is a valid vtable
// home; only the CALLABLE address has to be exec. Same split GameSDK.cpp:51-66
// already draws.
bool inGuestLib(uintptr_t addr) {
    static const std::string kGuestLib("libminecraftpe.so");
    return RttiLookup::inRegions(RttiLookup::regionsOf(kGuestLib), addr, false);
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

bool playerState(PlayerState *out) {
    if (out == nullptr) return false;
    PlayerState s;
    ObjectHandle lp = localPlayer();
    if (handleValid(lp)) {
        LocalPlayer p(lp.ptr);
        Vec3 pos;
        if (p.getPos(&pos)) {
            s.hasPos = 1;
            s.posX = pos.x; s.posY = pos.y; s.posZ = pos.z;
        }
        Vec2 rot;
        if (p.getViewAngles(&rot)) {
            s.hasRot = 1;
            s.pitch = rot.x; s.yaw = rot.y;
        }
        float hp = 0.f;
        if (p.getHealth(&hp)) {
            s.hasHealth = 1;
            s.health = hp;
        }
        int dim = 0;
        Handle d = p.getDimension();
        if (d.valid() && Dimension(d.ptr).getDimensionId(&dim)) {
            s.hasDimension = 1;
            s.dimensionId = dim;
        }
    }
    // Anything that resolved counts as success; the bits say which parts. A
    // caller that needs all of them checks the bits, not the return value.
    bool any = s.hasPos || s.hasRot || s.hasHealth || s.hasDimension;
    if (any) *out = s;
    return any;
}

bool windowMetrics(WindowMetrics *out) {
    if (out == nullptr) return false;
    Vec2 game, real;
    if (!windowSize(&game, &real)) return false;
    WindowMetrics m;
    m.hasSize = 1;
    m.gameWidth = game.x; m.gameHeight = game.y;
    m.realWidth = real.x; m.realHeight = real.y;
    *out = m;
    return true;
}

bool readiness(SdkReadiness *out) {
    if (out == nullptr) return false;
    SdkReadiness r;
    r.clientInstance = GameSdk::clientInstance() != 0;
    r.guiData = guiData().ptr != 0;
    r.minecraftGame = minecraftGame().ptr != 0;
    r.localPlayer = localPlayer().ptr != 0;
    r.levelRenderer = levelRenderer().ptr != 0;
    r.level = level().ptr != 0;
    r.camera = camera().ptr != 0;
    r.options = options().ptr != 0;
    r.region = region().ptr != 0;
    r.screenModel = screenModel().ptr != 0;
    r.packetSender = packetSender().ptr != 0;
    r.inGame = GameSdk::isInGame();
    *out = r;
    return true;
}

// --- MinecraftGame convenience ---

bool minecraftGameIsInGame() {
    // The public question is session state, and GameSdk already samples that on
    // the game thread using a pinned ClientInstance predicate when present or
    // the HudScreen lifetime witness otherwise. Calling MinecraftGame's wrapper
    // here silently returned false on 1.26.45.1 only because its own vtable slot
    // is intentionally unpinned, even while the HUD and LocalPlayer prove a
    // world is active.
    return GameSdk::isInGame();
}

bool minecraftGameCanUseKeys() {
    MinecraftGameHandle mg = MinecraftGameHandle::fromSdk();
    if (!mg.valid()) return false;
    return mg.canUseKeys();
}

// --- Hooks on SDK-located virtuals ---

bool sdkHook(const char *key, void *handler) { return hookVirtualRaw(key, handler); }

bool sdkUnhook(const char *key) { return unhookVirtual(key); }

uintptr_t sdkOriginal(const char *key, uintptr_t object) {
    return originalFor(key, object);
}

uintptr_t sdkResolve(const char *key, uintptr_t object) {
    if (key == nullptr) return 0;
    const HookRules::Rule *rule = GameSdk::rule();

    // Rung 1: a vtable pin. Needs the class to check out, otherwise a slot
    // index that drifted would hand back whatever function now sits there.
    if (rule != nullptr) {
        std::string cls;
        size_t slot = 0;
        if (rule->lookupVtable(std::string(key), &cls, &slot)) {
            const std::vector<uintptr_t> &tables = detail::tablesFor(cls.c_str());
            uintptr_t table = 0;
            if (object != 0) {
                uintptr_t vt = 0;
                if (detail::safeReadPtr(object, 0, &vt) && detail::inGuestLib(vt)) {
                    for (size_t i = 0; i < tables.size(); i++) {
                        if (RttiLookup::addressPoint(tables[i]) == vt) { table = tables[i]; break; }
                    }
                }
            } else if (!tables.empty()) {
                // No object to disambiguate with: the primary table is the only
                // defensible choice, and findVtables() publishes it first.
                table = tables[0];
            }
            if (table != 0 &&
                RttiLookup::slotCountOf(detail::kGuestLib, table) > slot) {
                uintptr_t fn = RttiLookup::slotAt(table, slot);
                if (fn != 0 && detail::inExec(fn)) return fn;
            }
        }
    }

    // Rung 2: a byte pattern. The route to everything that is not virtual.
    uintptr_t bySig = SigScanner::resolve(std::string(key));
    if (bySig != 0 && inGuestExec(bySig)) return bySig;

    // Rung 3: a raw RVA.
    return resolveOffset(std::string(key));
}

void sdkInventory(const SdkEntry **entries, size_t *count) {
    static const SdkEntry kTable[] = {
        {"client", "uintptr_t ()", "ClientInstance pointer, 0 until a hook captures it"},
        {"inGame", "bool ()", "session state sampled on the game thread"},
        {"describe", "void (char *buffer, size_t capacity)", "one-line provenance dump"},
        {"buildInfo", "BuildInfo ()", "version / rule name / library base"},
        {"guiData", "ObjectHandle ()", "GuiData, or invalid"},
        {"minecraftGame", "ObjectHandle ()", "MinecraftGame, or invalid"},
        {"localPlayer", "ObjectHandle ()", "LocalPlayer, or invalid"},
        {"packetSender", "ObjectHandle ()", "LoopbackPacketSender"},
        {"levelRenderer", "ObjectHandle ()", "LevelRenderer"},
        {"level", "ObjectHandle ()", "Level"},
        {"camera", "ObjectHandle ()", "Camera"},
        {"options", "ObjectHandle ()", "Options (no vtable on 1.26 - field access only)"},
        {"region", "ObjectHandle ()", "Region"},
        {"screenModel", "ObjectHandle ()", "ClientInstanceScreenModel"},
        {"readiness", "bool (SdkReadiness *)", "which handles resolved on this build"},
        {"playerState", "bool (PlayerState *)", "pos/rot/health/dimension with has-bits"},
        {"windowMetrics", "bool (WindowMetrics *)", "logical and physical window size"},
        {"windowSize", "bool (Vec2 *game, Vec2 *real)", "same, split"},
        {"worldToScreen", "bool (const Vec3 &cam, const Vec3 &world, Vec2 *out)", "projection"},
        {"worldToScreenAtPlayer", "bool (const Vec3 &world, Vec2 *out)", "camera at the player"},
        {"sendChatMessage", "bool (const std::string &)", "chat, via the screen model"},
        {"sendChatMessageRaw", "bool (const char *utf8)", "same, C string"},
        {"pumpCount", "size_t ()", "messages pumped so far"},
        {"getPlayerPos", "bool (Vec3 *)", "player position"},
        {"getPlayerRotation", "bool (Vec2 *)", "pitch/yaw in degrees"},
        {"getPlayerHealth", "bool (float *)", "current health"},
        {"getPlayerDimension", "bool (int *)", "dimension id"},
        {"minecraftGameIsInGame", "bool ()", "session state, not the wrapper's own slot"},
        {"minecraftGameCanUseKeys", "bool ()", "input focus"},
        {"sdkHook", "bool (const char *key, void *handler)", "patch every vtable of a pinned key"},
        {"sdkUnhook", "bool (const char *key)", "restore all patched tables"},
        {"sdkOriginal", "uintptr_t (const char *key, uintptr_t object)", "call-through"},
        {"sdkResolve", "uintptr_t (const char *key, uintptr_t object)",
         "vtable pin -> sig -> rva; 0 when nothing matches"},
        {"sdkVersion", "uint32_t ()", "MCPE_SDK_VERSION this library was built with"},
        {"resolveSignature", "uintptr_t (const std::string &key)", "rung 2 on its own"},
        {"resolveOffset", "uintptr_t (const std::string &key)", "rung 3 on its own"},
        {"callPointerGetter", "uintptr_t (uintptr_t object, uintptr_t getter)", "verified getter call"},
        {"selfTestWarmup", "void ()", "start the cold walks on another thread"},
        {"selfTestWarm", "bool ()", "true once the warmup finished"},
        {"selfTest", "void ()", "exercise every ladder, log OK/FAIL per entry"},
    };
    if (entries != nullptr) *entries = kTable;
    if (count != nullptr) *count = sizeof(kTable) / sizeof(kTable[0]);
}

uint32_t sdkVersion() { return MCPE_SDK_VERSION; }

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
    if (vt == 0 || !inGuestLib(vt)) return 0;
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
        "Camera", "Level", "GuiData", "Actor", "Player", "LocalPlayer", "GameRenderer",
        "LevelRendererCamera",
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

    // Projection math through GameRenderer's frame matrices.
    Mat4 m{};
    Vec2 fov{};
    if (ci.readViewProjection(&m, &fov)) {
        STLOG("readViewProjection OK focal=%.5f,%.5f view15=%.3f", fov.x, fov.y, m.m[15]);
        // One axis point is meaningless: whichever direction the camera happens to
        // face decides it. Project all six and report how many land in front.
        const Vec3 kDirs[6] = {{4.f, 0.f, 0.f},   {-4.f, 0.f, 0.f}, {0.f, 4.f, 0.f},
                               {0.f, -4.f, 0.f}, {0.f, 0.f, 4.f},  {0.f, 0.f, -4.f}};
        int hits = 0;
        Vec2 out{};
        for (int i = 0; i < 6; i++)
            if (ci.worldToScreenWithCamera(Vec3{0.f, 0.f, 0.f}, kDirs[i], &out)) hits++;
        STLOG("worldToScreen: %d/6 axis points project, last=%.1f,%.1f", hits, out.x, out.y);
        // Exactly 3 is the only correct answer: one of {+x,-x} is in front of any
        // camera, likewise for y and z, and mcpe::worldToScreen rejects nothing
        // else. Anything else names the reason instead of leaving it ambiguous -
        // a 0/6 seen here once came from the window size being unavailable, not
        // from the matrices.
        if (hits != 3) {
            Vec2 win{};
            uintptr_t gdPtr = GameSdk::guiData();
            const bool hasWin = gdPtr != 0 && GuiData(gdPtr).windowSize(&win, nullptr);
            STLOG("worldToScreen expected 3/6: guiData=%p windowSize=%d %.1f,%.1f focal=%.5f,%.5f",
                  reinterpret_cast<void *>(gdPtr), hasWin ? 1 : 0, win.x, win.y, fov.x, fov.y);
        }
    } else {
        STLOG("readViewProjection FAIL (pins dat.grViewMatrix/grProjectionMatrix)");
        // Name which rung broke: no MinecraftGame, no GameRenderer member, or
        // matrices that are not where the pins say.
        uintptr_t mg = GameSdk::minecraftGame();
        STLOG("gameRenderer probe: mg=%p", reinterpret_cast<void *>(mg));
        if (mg != 0) {
            Handle gr = detail::fieldByPinOrScan(mg, "gameRendererField", "GameRenderer");
            STLOG("gameRenderer probe: gr=%s %p (%s)", gr.valid() ? "OK" : "--",
                  reinterpret_cast<void *>(gr.ptr), gr.source);
            if (gr.valid()) probeProjectionLayout(gr.ptr, "GameRenderer");
            probeProjectionLayout(mg, "MinecraftGame");
        }
        Handle lr = ci.levelRenderer();
        if (lr.valid()) probeProjectionLayout(lr.ptr, "LevelRenderer");
    }

    // Second opinion on the frame matrices, from a different owner. Runs on both
    // outcomes: agreement is worth recording, and disagreement is the more
    // interesting result of the two.
    crossCheckFrameMatrices(GameSdk::clientInstance());

    // Actor surface on the local player.
    Handle lpH = ci.getLocalPlayer();
    uintptr_t lp = lpH.ptr;
    STLOG("localPlayer: %p via %s", reinterpret_cast<void *>(lp), lpH.source);
    if (lp != 0) {
        Actor actor(lp);
        Vec3 pos{};
        STLOG("actor.getPos: %s (%.2f,%.2f,%.2f)", actor.getPos(&pos) ? "OK" : "--",
              pos.x, pos.y, pos.z);
        probeGetPosVirtual(lp, pos);
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
        HealthWalkTrace tw{};
        const bool haveHp = traceHealthWalk(lp, &hp, &tw);
        STLOG("health diagnostic walk: %s value=%.2f (not published as Actor::getHealth)",
              haveHp ? "reached element" : "--", haveHp ? static_cast<double>(hp) : 0.0);
        STLOG("health walk stage '%s': hash=0x%llx registry=%p id=%u buckets=%p..%p "
              "pools=%p link=0x%llx pool=%p poolHash=0x%llx storage=%p element=%p links=%zu",
              tw.stage, static_cast<unsigned long long>(tw.hash),
              reinterpret_cast<void *>(tw.registry), tw.id,
              reinterpret_cast<void *>(tw.bucketBegin), reinterpret_cast<void *>(tw.bucketEnd),
              reinterpret_cast<void *>(tw.poolBegin),
              static_cast<unsigned long long>(tw.linkValue),
              reinterpret_cast<void *>(tw.pool),
              static_cast<unsigned long long>(tw.poolHash), reinterpret_cast<void *>(tw.storage),
              reinterpret_cast<void *>(tw.element), tw.links);
        if (!haveHp) {
            std::string img;
            for (size_t i = 0; i < 8; i++) {
                char part[24];
                int n = snprintf(part, sizeof(part), "%s%llx", i ? " " : "",
                                 static_cast<unsigned long long>(tw.words[i]));
                if (n > 0) img += part;
            }
            STLOG("actor words +0x00: %s", img.c_str());
        }
        if (haveHp) probeHealthGetter(lp, hp);
        probeComponentKeys(lp);
        uint64_t rid = 0;
        STLOG("actor.getRuntimeID: %s", actor.getRuntimeID(&rid) ? "OK" : "-- (pin)");
        STLOG("actor.getNameTag: '%s'", actor.getNameTag().c_str());
        // Actor::isAlive() decodes all four slot words before dispatching, so a
        // shifted Actor:47 now fails closed rather than faulting the self-test.
        STLOG("actor.isAlive: %d (four-word decoded virtual)", actor.isAlive() ? 1 : 0);
        STLOG("actor.getDimension: %p", reinterpret_cast<void *>(actor.getDimension().ptr));
        STLOG("actor.getRegion: %p", reinterpret_cast<void *>(actor.getRegion().ptr));

        // Read-only reachability probe for the 1.26 attribute system, before
        // anyone reimplements entt's sparse-set paging. The 1.21 chain is
        // ActorAttribute{registry @+8, entity @+0x10} -> assure<AttributesComponent>
        // (hash 0xFD3B0613) -> 96-byte component stride -> BaseAttributeMap::
        // getInstance(Attribute const&) -> AttributeInstance::getCurrentValue ->
        // ceil. Neither ActorAttribute nor BaseAttributeMap appears in 1.26.45.1
        // at all (0 occurrences of either name in the .so), so the chain's shape
        // may have changed - but AttributeInstance does have RTTI, which makes it
        // a certifiable needle. Two bounded hops out of the player, every read
        // region-checked, nothing called.
        {
            const std::vector<uintptr_t> &aiTables = detail::tablesFor("AttributeInstance");
            if (aiTables.empty()) {
                STLOG("attr probe: AttributeInstance has no RTTI table in this build");
            } else {
                int found = 0;
                for (uintptr_t o1 = 0x8; o1 <= 0x400 && found < 6; o1 += 8) {
                    uintptr_t hop = 0, hopVt = 0;
                    if (!detail::safeReadPtr(lp, o1, &hop) || !detail::inWritable(hop)) continue;
                    if (detail::safeReadPtr(hop, 0, &hopVt)) {
                        for (size_t t = 0; t < aiTables.size(); t++) {
                            if (hopVt == RttiLookup::addressPoint(aiTables[t])) {
                                STLOG("attr probe: actor+0x%x is an AttributeInstance %p",
                                      (unsigned) o1, reinterpret_cast<void *>(hop));
                                found++;
                                break;
                            }
                        }
                    }
                    for (uintptr_t o2 = 0x8; o2 <= 0x100 && found < 6; o2 += 8) {
                        uintptr_t obj = 0, vt = 0;
                        if (!detail::safeReadPtr(hop, o2, &obj) || !detail::inWritable(obj)) continue;
                        if (!detail::safeReadPtr(obj, 0, &vt)) continue;
                        for (size_t t = 0; t < aiTables.size(); t++) {
                            if (vt == RttiLookup::addressPoint(aiTables[t])) {
                                STLOG("attr probe: actor+0x%x -> +0x%x = AttributeInstance %p",
                                      (unsigned) o1, (unsigned) o2, reinterpret_cast<void *>(obj));
                                found++;
                                break;
                            }
                        }
                    }
                }
                if (found == 0)
                    STLOG("attr probe: no AttributeInstance within 2 hops of the player");
            }
        }
        LocalPlayer me(lp);
        STLOG("lp.getGameMode: %p", reinterpret_cast<void *>(me.getGameMode().ptr));
        STLOG("lp.getHeldItem: %p", reinterpret_cast<void *>(me.getHeldItem().ptr));
        STLOG("lp.getMoveInputHandler: %p", reinterpret_cast<void *>(me.getMoveInputHandler().ptr));
    } else {
        STLOG("localPlayer UNRESOLVED (sig.clientInstanceGetLocalPlayer empty; the "
              "ClientInstance slot rung is retired - see the rule note)");
    }

    // Network surface, read-only. The game's own factory builds a real
    // CommandRequestPacket and we ask it about itself through the rule-backed
    // virtuals; nothing is ever sent, so a self-test cannot write to a live
    // server session. The shared_ptr leaves scope here and the game's control
    // block frees the packet - the same ownership path sendCommand() uses.
    Handle senderH = ci.loopbackPacketSender();
    if (senderH.valid()) {
        std::optional<bool> init = LoopbackPacketSender(senderH.ptr).isInitialized();
        STLOG("packetSender.isInitialized: %s",
              !init ? "-- (virtual unresolved or refused)" : (*init ? "OK true" : "OK false"));
    } else {
        STLOG("packetSender.isInitialized: -- (no sender)");
    }
    {
        std::shared_ptr<void> probe = createPacket(0x4d);
        if (!probe) {
            STLOG("createPacket(0x4D): -- (factory unresolved or returned a non-game object)");
        } else {
            Packet p(reinterpret_cast<uintptr_t>(probe.get()));
            std::optional<int> id = p.getId();
            std::optional<size_t> maxSize = p.getMaxSize();
            std::optional<bool> valid = p.isValid();
            STLOG("createPacket(0x4D): %p id=%d name='%s' maxSize=%zu valid=%d",
                  probe.get(), id ? *id : -1, p.getName().c_str(),
                  maxSize ? *maxSize : 0, valid ? (*valid ? 1 : 0) : -1);
        }
    }

    // ModApi convenience layer.
    Vec3 p{};
    STLOG("modapi.getPlayerPos: %s", getPlayerPos(&p) ? "OK" : "--");
    Vec2 r{};
    STLOG("modapi.getPlayerRotation: %s", getPlayerRotation(&r) ? "OK" : "--");
    float health = 0.f;
    STLOG("modapi.getPlayerHealth: %s", getPlayerHealth(&health) ? "OK" : "--");
    int dim = -1;
    // Print the value, not just the verdict: "OK" from a chain of derived offsets
    // proves nothing if it reads the wrong field, and the overworld is 0.
    STLOG("modapi.getPlayerDimension: %s dim=%d", getPlayerDimension(&dim) ? "OK" : "--", dim);
    STLOG("modapi.minecraftGameIsInGame: %d", minecraftGameIsInGame() ? 1 : 0);
    STLOG("modapi.minecraftGameCanUseKeys: %d", minecraftGameCanUseKeys() ? 1 : 0);
    Vec2 gs{}, rs{};
    STLOG("modapi.windowSize: %s", windowSize(&gs, &rs) ? "OK" : "--");
    if (lp != 0) {
        Vec2 scr{};
        // Project a point 10 blocks along the player's own look vector. That is
        // the one input whose correct answer needs no second opinion: it must be
        // in front of the camera and land near the screen centre. The previous
        // test projected the world origin from the player's feet, which is
        // simply behind the camera, so "--" there proved nothing either way.
        const float pitch = r.x * 0.0174532924f, yaw = r.y * 0.0174532924f;
        Vec3 dir(-sinf(yaw) * cosf(pitch), -sinf(pitch), cosf(yaw) * cosf(pitch));
        Vec3 ahead(p.x + dir.x * 10.f, p.y + dir.y * 10.f, p.z + dir.z * 10.f);
        const bool got = worldToScreenAtPlayer(ahead, &scr);
        STLOG("modapi.worldToScreenAtPlayer: %s -> %.1f,%.1f (rot %.1f,%.1f expect near %.1f,%.1f)",
              got ? "OK" : "--", scr.x, scr.y, r.x, r.y, gs.x * 0.5f, gs.y * 0.5f);
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