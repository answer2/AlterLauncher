// Implements mcpe/ModApi.h — the mod-facing umbrella over GameSdk's ladder,
// ClassTree's typed accessors, and SigScanner's rule-driven pattern cache.
#include <mcpe/ModApi.h>

#include <cstring>

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

}  // namespace mcpe