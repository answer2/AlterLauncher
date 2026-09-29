#include <jni.h>
#include <string>
#include <sstream>
#include <dlfcn.h>
#include <cstdio>
#include <stdint.h>
#include <atomic>
#include <sys/system_properties.h>
#include <android/asset_manager_jni.h>

// Project specific includes
#include <main.h>
#include <hook/fake_asset.h>
#include <hook/fake_file.h>
#include <hook/fake_dlopen.h>
#include <hook/manager_minecraft.h>
#include <util/stringUtil.h>
#include <util/fake_dlfcn.h>
#include <util/Utils.h>
#include <util/FindSignature.h>
#include <util/RttiLookup.h>
#include <util/LongCall.h>
#include <util/HookRules.h>
#include <mcpe/GameSDK.h>
#include <mcpe/ModApi.h>
#include <mcpe/ClassTree.h>
#include <util/SigScanner.h>
#include <android/android_log.h>
#include <dl_internal.h>

#include <shadowhook/shadowhook.h>
#include <mcpe/client/MinecraftGame.h>
#include <util/tranceUtils.h>
#include <util/NativeInvoker.h>

// Initialize variables
JavaVM *Main::vm = nullptr;
long Main::baseAddress = 0L;
AAssetManager *Main::manager = nullptr;
const char *corePath = "com/answer/launcher/core/manager/NativeCore";
static MinecraftGame *minecraftGame = nullptr;

// State published by the runtime vtable resolver further down. It lives at file
// scope because sendMessage reports it.
void *clientInstance = nullptr;
uintptr_t clientInstanceVtable = 0;
uintptr_t guiDataVtable = 0;
long updateCalls = 0;

// Menu state, mirroring preloader-android's GameHooks.cpp flags (Apache-2.0,
// see LICENSES/preloader-android.txt + NOTICE): PauseMenu and HudScreen
// ctor/dtor plus ClientInstance::isShowingMenu together answer "is a menu on
// screen right now?", which is what the Levi mod surface (mod menu, input
// routing) keys off. Written from hook handlers on the game thread, read by
// Java through NativeCore.menuState() — atomics so that read is well defined.
std::atomic<bool> g_isPauseMenuOpen{false};
std::atomic<bool> g_isHudScreenOpen{false};
std::atomic<bool> g_isShowingMenu{false};
long pauseMenuEvents = 0, hudScreenEvents = 0, showingMenuCalls = 0;

// The hook rule that covers the running Minecraft build, if any. A copy, not a
// pointer into the parsed vector, so it stays valid however the vector is later
// reassigned. `gHasRule` is what distinguishes "no rule matched, use the
// compiled-in data" from "a rule matched and says this function has no pattern".
HookRules::Rule gRule;
bool gHasRule = false;
std::string gGuestVersion;

// Function declarations
void initAssets(JNIEnv *env, jobject obj, jobject assetManager);

jlong loadLibrary_java(JNIEnv *env, jobject obj, jstring libraryPath);

void unloadLibrary_java(JNIEnv *env, jobject obj, jlong handle);

long findLibBaseAddress_java(JNIEnv *env, jobject obj, jstring library);

void load(JNIEnv *env, jobject obj);

void loadManager(JNIEnv *env, jobject obj);

// Must run before loadManager: it decides which slot index and which byte
// patterns the hooks are built from.
void applyHookRules_java(JNIEnv *env, jobject obj, jstring version, jstring rules);

// Declared here because load() calls it and its definition follows load().
void installSignatureHooks();

void sendMessage(JNIEnv *env, jobject obj, jstring msg);

jstring menuState_java(JNIEnv *env, jobject obj);

void hookDlopen_java(JNIEnv *env, jobject obj);

// JNI methods array
static JNINativeMethod MethodCalled[] = {
        {"initAssets",         "(Landroid/content/res/AssetManager;)V", (void *) initAssets},
        {"loadLibrary",        "(Ljava/lang/String;)J",                 (void *) loadLibrary_java},
        {"unloadLibrary",      "(J)V",                                  (void *) unloadLibrary_java},
        {"findLibBaseAddress", "(Ljava/lang/String;)J",                 (void *) findLibBaseAddress_java},
        {"loadManager",        "()V",                                   (void *) loadManager},
        {"applyHookRules",     "(Ljava/lang/String;Ljava/lang/String;)V",
         (void *) applyHookRules_java},
        {"sendMessage",        "(Ljava/lang/String;)V",                 (void *) sendMessage},
        {"menuState",          "()Ljava/lang/String;",                  (void *) menuState_java},
        // NativeCore.hookDlopen was declared on the Java side but never
        // registered here, so any call threw UnsatisfiedLinkError.
        {"hookDlopen",         "()V",                                   (void *) hookDlopen_java}
};

// JNI OnLoad
JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    Main::vm = vm;
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        LOGE("JNI_VERSION is not 1.6");
        return JNI_ERR;
    }

    if (!Utils::registerNativeMethods(env, corePath, MethodCalled,
                                      sizeof(MethodCalled) / sizeof(MethodCalled[0]))) {
        LOGD("Register Failed");
    }

    //initFakeFile(env);
    return JNI_VERSION_1_6;
}

// JNI functions
void initAssets(JNIEnv *env, jobject obj, jobject assetManager) {
    Main::manager = AAssetManager_fromJava(env, assetManager);
    initFakeAssets(env, Main::manager);
}

// NativeCore.applyHookRules: hand the native layer the version-gated hook data.
// Java owns where it comes from (an asset today, a downloaded file tomorrow) and
// passes the text in; this only parses and selects. Called before loadManager,
// and never fatal — an empty or unparseable file leaves the compiled-in defaults
// in charge, which is what this code did before the file existed.
void applyHookRules_java(JNIEnv *env, jobject obj, jstring version, jstring rules) {
    const char *versionChars = version == nullptr ? nullptr : env->GetStringUTFChars(version, nullptr);
    gGuestVersion = versionChars == nullptr ? "" : versionChars;
    GameSdk::setVersion(gGuestVersion.c_str());
    if (versionChars != nullptr) env->ReleaseStringUTFChars(version, versionChars);

    std::string text;
    if (rules != nullptr) {
        const char *rulesChars = env->GetStringUTFChars(rules, nullptr);
        if (rulesChars != nullptr) {
            text = rulesChars;
            env->ReleaseStringUTFChars(rules, rulesChars);
        }
    }

    gHasRule = false;
    gRule = HookRules::Rule();
    if (text.empty()) {
        LOGD("hook rules: none supplied for Minecraft %s, using the compiled-in data",
             gGuestVersion.c_str());
        return;
    }

    std::vector<std::string> errors;
    std::vector<HookRules::Rule> parsed = HookRules::parse(text, &errors);
    for (size_t i = 0; i < errors.size(); i++) {
        LOGW("hook rules: %s", errors[i].c_str());
    }
    if (parsed.empty()) {
        LOGW("hook rules: the file parsed to zero rules (%zu byte(s)), using the compiled-in data",
             text.size());
        return;
    }

    const HookRules::Rule *found = HookRules::select(parsed, gGuestVersion);
    if (found == nullptr) {
        LOGW("hook rules: none of the %zu rule(s) covers Minecraft %s; the RTTI path still runs "
             "and the byte signatures fall back to the compiled-in ones",
             parsed.size(), gGuestVersion.c_str());
        return;
    }
    gRule = *found;
    gHasRule = true;
    GameSdk::setRule(&gRule, gHasRule);
    SigScanner::init(&gRule);  // same rule, now also pattern-resolvable
    LOGD("hook rules: rule '%s' active, vtable=%zu signature=%zu",
         gRule.name.c_str(), gRule.vtable.size(), gRule.signatures.size());
}

// NativeCore.loadLibrary is declared `public static native long` and its JNI
// signature is "(Ljava/lang/String;)J", but the implementation was `void`: the
// Java caller read whatever x0 happened to hold after the call and handed that
// to unloadLibrary. Return the real handle.
jlong loadLibrary_java(JNIEnv *env, jobject obj, jstring libraryPath) {
    // NativeCore.loadLibrary is declared `public static native long` and this
    // entry's signature is "(Ljava/lang/String;)J"; returning void here left
    // the Java caller reading whatever x0 happened to hold after the call and
    // handing that value to unloadLibrary.
    return reinterpret_cast<jlong>(Utils::loadLibrary(env, libraryPath));
}

void hookDlopen_java(JNIEnv *env, jobject obj) {
    initFakeDlopen();
}

void loadManager(JNIEnv *env, jobject obj) {
    load(env, obj);
}

void unloadLibrary_java(JNIEnv *env, jobject obj, jlong handle) {
    Utils::unloadLibrary((void *) handle);
}

long findLibBaseAddress_java(JNIEnv *env, jobject obj, jstring library) {
    return Main::findLibBaseAddress(jstringToCharArr(env, library));
}

void dumpAdder(void **handle) {
    FILE *fp = fopen("/sdcard/1.txt", "w");

    auto appPlat = (void **) handle;
    auto raw = &appPlat[2];

    fprintf(fp, "AppPlatform : \n");

    for (int i = 0; raw[i] && raw[i] != (void *) 0xffffffffffffffe8; i++) {
        Dl_info data;
        fprintf(fp, "%p (%s)    :    %lu\n", raw[i],
                dladdr(raw[i], &data) ? data.dli_sname : "(unknown)",
                ((uintptr_t) raw[i] - (uintptr_t) data.dli_fbase));
    }

    fclose(fp);
}

void *SetHook(void *handle, uintptr_t address) {
    uintptr_t baseAddr = ((soinfo2 *) handle)->base;
    return (void *) (baseAddr + address);
}


// 定义目标函数指针类型
typedef int (*Minecraft_update)(MinecraftGame *minecraftGame);

// 保存目标函数的原始地址
void *(*Minecraft_update_real)(MinecraftGame *minecraftGame);

// Hook函数实现
void *Minecraft_update_hook(MinecraftGame *minecraftGame_) {
    //LOGD("Minecraft Hook Update : %p", minecraftGame_);
    minecraftGame = minecraftGame_;
    GameSdk::setMinecraftGameFromHook(reinterpret_cast<uintptr_t>(minecraftGame_));
    Minecraft_update_real(minecraftGame_);
    return nullptr;//
}

// The pinned chat entry on 1.26.45.1 (off.guiDataAddMessage = 0x923ab54) is the
// TYPE-1 chat wrapper: it builds a GuiMessage{type=1, sender, message, ...} on the
// stack and hands it to GuiData::addMessageWithFilter. Its first argument is the
// REAL GuiData — the strongest proof of identity the game can hand us. (The old
// pin 0x923a5d4 was the GuiMessage CONSTRUCTOR: forwarding an 11-arg addMessage
// shape into it dereferenced our nullptr string and crashed the game.)
// World-entry witness: MinecraftGame::joinMultiplayerGame is the game's own
// "start loading a world" call (single-player hosts go through it too), and
// the chat wrapper firing proves a live HUD. Either one flips this flag; the
// SDK self-test only runs after it, never on the title screen.
static bool g_worldEntered = false;

void (*GuiData_addMessage_real)(void *guiData,
                                const std::string &sender,
                                const std::string &message,
                                const std::string &s3,
                                const std::string &s4,
                                const std::string &s5,
                                float duration);

// 保存目标函数的原始地址
void *(*GuiDate_sendClientMessage_real)(void *void1, const std::string &msg, const std::string &msg2);
// Hook函数实现
void GuiData_addMessage_hook(void *guiData,
                             const std::string &sender,
                             const std::string &message,
                             const std::string &s3,
                             const std::string &s4,
                             const std::string &s5,
                             float duration) {
    LOGD("GuiData::addChatMessage gd=%p sender=%s is : %s", guiData, sender.c_str(),
         message.c_str());
    // The game hands us its own GuiData on every chat line. Publish it through
    // the SDK's validated setter (it re-checks the vtable against the RTTI
    // tables) and flush any queued line while we are on the game thread.
    g_worldEntered = true;  // a live chat line means a live HUD
    if (GameSdk::clientInstance() != 0) {
        GameSdk::setGuiDataFromHook(reinterpret_cast<uintptr_t>(guiData));
        GameSdk::pumpFromAddMessageHook();
    }
    //print_callstack_unwind();

    if (GuiData_addMessage_real)
        GuiData_addMessage_real(guiData, sender, message, s3, s4, s5, duration);
}

// Hook函数实现
void *GuiDate_sendClientMessage_hook(void *void1, const std::string &msg, const std::string &msg2) {
    LOGD("Minecraft Hook GuiDate_sendClientMessage : %lx  %s %s", void1, msg.c_str(), msg2.c_str());

    GuiDate_sendClientMessage_real(void1, msg, msg2);
    return nullptr;//
}


void sendMessage(JNIEnv *env, jobject obj, jstring msg) {
    const char *text = env->GetStringUTFChars(msg, nullptr);
    if (text == nullptr) return;
    if (clientInstance == nullptr && minecraftGame == nullptr) {
        LOGW("sendMessage(\"%s\"): no game instance captured yet, ClientInstance::update has "
             "not fired", text);
        env->ReleaseStringUTFChars(msg, text);
        return;
    }
    // The chat route through the SDK layer: the request is parked and delivered
    // on the next game-thread tick via the validated GuiData instance and the
    // published addMessage pointer. No blind vtable indexes anywhere.
    const bool queued = GameSdk::requestMessage(text);
    char sdk[192];
    GameSdk::describe(sdk, sizeof(sdk));
    LOGI("sendMessage(\"%s\"): %s queued=%d updateCalls=%ld", text, sdk, queued ? 1 : 0,
         updateCalls);
    env->ReleaseStringUTFChars(msg, text);
}

// Menu state for Java (NativeCore.menuState()). The About page surfaces it so
// a device test can read the flags without logcat archaeology. Format is
// fixed: "pause=<0|1>,hud=<0|1>,showingMenu=<0|1>,pauseEvents=<n>,
// hudEvents=<n>,menuCalls=<n>".
jstring menuState_java(JNIEnv *env, jobject obj) {
    char buf[160];
    snprintf(buf, sizeof(buf), "pause=%d,hud=%d,showingMenu=%d,pauseEvents=%ld,hudEvents=%ld,menuCalls=%ld",
             g_isPauseMenuOpen.load() ? 1 : 0,
             g_isHudScreenOpen.load() ? 1 : 0,
             g_isShowingMenu.load() ? 1 : 0,
             pauseMenuEvents, hudScreenEvents, showingMenuCalls);
    char full[420];
    char sdk[192];
    GameSdk::describe(sdk, sizeof(sdk));
    snprintf(full, sizeof(full), "%s,%s", buf, sdk);
    return env->NewStringUTF(full);
}

void* (*MinecraftGame__joinMultiplayerGame_real)(void*, std::string const&, void*);
void *MinecraftGame__joinMultiplayerGame_hook(void* minecraftGame, std::string const& a1, void* a2){
    LOGD("Minecraft Hook MinecraftGame__joinMultiplayerGame : %p  %s %p", minecraftGame, a1.c_str(), a2);
    g_worldEntered = true;
    MinecraftGame__joinMultiplayerGame_real(minecraftGame, a1, a2);
    return nullptr;//
}


// ---------------------------------------------------------------------------
// Menu-state hooks (M4, docs/levi-compat-design.md 4.4)
//
// Same semantics as preloader-android's GameHooks.cpp (Apache-2.0, vendored
// under LICENSES/ + NOTICE): the four non-virtual PauseMenu/HudScreen
// functions are reached by byte pattern through hookBySignature; the virtual
// ClientInstance::isShowingMenu is patched at its RTTI vtable slot like
// clientInstanceUpdate. Every handler forwards to the original first — a
// wrong pattern costs a missed flag, never the game.
// ---------------------------------------------------------------------------

void (*PauseMenu_dtor_real)(void *);
void PauseMenu_dtor_hook(void *self) {
    g_isPauseMenuOpen.store(false);
    pauseMenuEvents++;
    LOGD("PauseMenu dtor: menu closed");
    PauseMenu_dtor_real(self);
}

void (*PauseMenu_open_real)(void *);
void PauseMenu_open_hook(void *self) {
    g_isPauseMenuOpen.store(true);
    g_isShowingMenu.store(true);
    pauseMenuEvents++;
    LOGD("PauseMenu open");
    PauseMenu_open_real(self);
}

void (*HudScreen_dtor_real)(void *);
void HudScreen_dtor_hook(void *self) {
    g_isHudScreenOpen.store(false);
    g_isPauseMenuOpen.store(false);
    g_isShowingMenu.store(false);
    hudScreenEvents++;
    LOGD("HudScreen dtor: screens gone");
    HudScreen_dtor_real(self);
}

void (*HudScreen_open_real)(void *);
void HudScreen_open_hook(void *self) {
    g_isHudScreenOpen.store(true);
    g_isPauseMenuOpen.store(false);
    g_isShowingMenu.store(false);
    hudScreenEvents++;
    g_worldEntered = true;  // single-player world load ends with the HUD
    LOGD("HudScreen open");
    HudScreen_open_real(self);
}

typedef bool (*ClientInstance_isShowingMenu)(void *);
ClientInstance_isShowingMenu ClientInstance_isShowingMenu_real = nullptr;
bool ClientInstance_isShowingMenu_hook(void *self) {
    bool res = ClientInstance_isShowingMenu_real(self);
    g_isShowingMenu.store(res);
    showingMenuCalls++;
    return res;
}

// The signatures below are pinned to one Minecraft build. A build that does not
// contain the pattern yields address 0, and handing that to shadowhook used to be
// invisible because shadowhook itself could not initialise on Android 16 (errno 12)
// and every call failed anyway. Now that inline hooking works, report each miss and
// each failed install instead of pretending the function was patched.
static bool hookGameFunction(uintptr_t address, void *handler, void **real,
                             const char *name) {
    if (address == 0) {
        LOGW("signature not found for %s: this Minecraft build is not covered, hook skipped", name);
        return false;
    }
    // Ask the address space before asking shadowhook. On a library mapped as one
    // huge PT_LOAD the trampoline island has nowhere to go, and that failure is
    // about where the function sits, not about whether the pattern was right —
    // without this line the log blames a stale signature for an unmappable hook.
    std::string reach;
    if (!RttiLookup::islandReachable(address, &reach)) {
        // shadowhook's island must sit within +/-128 MB of the target because it
        // gets there with a direct branch, and inside libminecraftpe.so no free
        // page does. LongCall drops the direct branch: it places its bridge on a
        // page of our own and reaches the handler through a register, which works
        // at any address in the address space.
        std::string why;
        if (LongCall::hook(address, handler, real, &why)) {
            LOGD("hooked %s at 0x%lx by long-reach patch (%s)", name,
                 (unsigned long) address, why.c_str());
            return true;
        }
        LOGW("cannot inline-hook %s at 0x%lx: %s; long-reach patch refused: %s", name,
             (unsigned long) address, reach.c_str(), why.c_str());
        return false;
    }
    if (shadowhook_hook_func_addr((void *) address, handler, real) == nullptr) {
        LOGE("failed to hook %s at 0x%lx: %s", name, (unsigned long) address,
             shadowhook_to_errmsg(shadowhook_get_errno()));
        return false;
    }
    LOGD("hooked %s at 0x%lx (%s)", name, (unsigned long) address, reach.c_str());
    return true;
}

// ---------------------------------------------------------------------------
// Runtime vtable resolution (the version-portable path)
// ---------------------------------------------------------------------------
//
// The byte signatures below are pinned to one Minecraft build and Mojang
// recompiles the functions on every release, so all four of them miss on
// 1.26.45.1 and no game hook installs at all. RTTI survives: the guest keeps
// `14ClientInstance` type names in .rodata even though .symtab is stripped, so
// the class's vtable can be found by walking name -> typeinfo -> vtable in the
// live mappings, and slot 25 of ClientInstance's table is its per-frame
// update(bool). Hooking a virtual function hands us `this` for free, which is
// the only reliable way to get a handle on the running game.
//
// The index is not guessed blindly: slots 18..55 of the 1.26.45.1 table match
// the symbolised 1.21.131 build slot for slot by function size (tick/endFrame
// are 4-byte stubs in both, update is the largest of the group), and even a
// wrong index is survivable because the hook forwards to the original.
namespace {

constexpr const char *kGuestLib = "libminecraftpe.so";
// The 1.26.45.1 answer, verified on device against the offline anchors: slots
// 18..55 of this table match the symbolised 1.21.131 build slot for slot by
// function size, and update(bool) is by far the largest of the group. A rule
// file may override both the class and the index for other builds.
constexpr const char *kClientInstanceClass = "ClientInstance";
constexpr size_t kClientInstanceUpdateSlot = 25;

typedef uintptr_t (*ClientInstance_update)(void *, bool);
ClientInstance_update ClientInstance_update_real = nullptr;

uintptr_t ClientInstance_update_hook(void *self, bool paused) {
    if (clientInstance == nullptr) {
        clientInstance = self;
        GameSdk::setClientInstance(reinterpret_cast<uintptr_t>(self));
        LOGI("ClientInstance captured at %p (vtable %p, guiData vtable %p)", self,
             reinterpret_cast<void *>(clientInstanceVtable),
             reinterpret_cast<void *>(guiDataVtable));
    }
    updateCalls++;
    GameSdk::pumpMessages();  // chat requests parked by Java land here, on the game thread
    // One-shot SDK self-test: fires the first time we are past boot AND the
    // game itself has witnessed world entry (joinMultiplayerGame or a real
    // chat line), so every ladder is queried in the state a mod would see.
    // Never probe unresolved sig getters just to decide this - calling the
    // mis-matched clientInstanceGetLocalPlayer pattern on the title screen
    // crashed the game.
    static bool s_selfTestDone = false;
    // TEMP DIAG: selfTest's four cold RTTI walks stall the game thread ~5s
    // (ANR-adjacent; the last two runs died right after it). Park it until the
    // GuiData sub-object pin is settled, then restore the >60 threshold.
    if (!s_selfTestDone && g_worldEntered && GameSdk::pumpCount() > 1000000) {
        s_selfTestDone = true;
        mcpe::selfTest();
    }
    return ClientInstance_update_real(self, paused);
}

// Checks that a resolved vtable really has `index` slots and hands back the
// function stored in it, or 0.
uintptr_t resolveVirtual(uintptr_t vtable, size_t index, size_t slots) {
    if (vtable == 0) return 0;
    if (slots <= index) {
        LOGW("the vtable has only %zu virtual slots, index %zu is out of range", slots, index);
        return 0;
    }
    uintptr_t fn = RttiLookup::slotAt(vtable, index);
    std::vector<RttiLookup::Region> regions = RttiLookup::regionsOf(kGuestLib);
    if (!RttiLookup::inRegions(regions, fn, true)) {
        LOGW("slot %zu (0x%lx) is not executable", index, (unsigned long) fn);
        return 0;
    }
    return fn;
}

// Which class holds the per-frame update and at which vtable index. The rule
// file may name both; otherwise the built-in pair applies.
struct VirtualTarget {
    std::string className;
    size_t slot;
    const char *key;
};

VirtualTarget virtualTarget(const char *key, const char *fallbackClass, size_t fallbackSlot) {
    VirtualTarget target;
    target.className = fallbackClass;
    target.slot = fallbackSlot;
    target.key = key;
    if (!gHasRule) return target;
    if (!gRule.lookupVtable(key, &target.className, &target.slot)) {
        LOGW("rule '%s' names no %s, using %s:%zu", gRule.name.c_str(), key, fallbackClass,
             fallbackSlot);
        target.className = fallbackClass;
        target.slot = fallbackSlot;
    }
    return target;
}

void installRttiHooks() {
    size_t slots = 0;
    guiDataVtable = RttiLookup::findVtable(kGuestLib, "GuiData", NULL);
    // Collect every validated GuiData table (primary + aux/subobject variants):
    // on 1.26.x the live instance carries one of the aux tables, so identity
    // checks need the full set. The primary answer above still seeds gdVtable.
    uintptr_t gdTables[8];
    size_t gdTableCount = RttiLookup::findVtables(kGuestLib, "GuiData", gdTables, 8);
    LOGD("GuiData RTTI: %zu validated table(s), primary %p", gdTableCount,
         reinterpret_cast<void *>(guiDataVtable));

    VirtualTarget update_target =
            virtualTarget("clientInstanceUpdate", kClientInstanceClass, kClientInstanceUpdateSlot);
    LOGD("resolving %s:%zu through RTTI", update_target.className.c_str(), update_target.slot);
    clientInstanceVtable = RttiLookup::findVtable(kGuestLib, update_target.className, &slots);
    uintptr_t update = resolveVirtual(clientInstanceVtable, update_target.slot, slots);
    if (update == 0) {
        LOGW("%s::update is not resolvable by RTTI in this build",
             update_target.className.c_str());
        clientInstanceVtable = 0;
        return;
    }
    LOGD("resolved %s vtable %p (%zu slots), update(bool) at 0x%lx",
         update_target.className.c_str(), reinterpret_cast<void *>(clientInstanceVtable), slots,
         (unsigned long) update);

    // The vtable slot is patched rather than the function body: an inline hook
    // needs a free page within +/-128 MB of the target for shadowhook's
    // trampoline island, and inside the guest's single 298 MB executable
    // mapping there is none. Say so on the device instead of only here.
    std::string reachReason;
    bool reachable = RttiLookup::islandReachable(update, &reachReason);
    LOGD("an inline hook of update at 0x%lx would be %s: %s", (unsigned long) update,
         reachable ? "possible" : "impossible", reachReason.c_str());

    // For a virtual function the slot is the natural place to intercept anyway:
    // no trampoline, and the mapping is MAP_PRIVATE so the patch is local to
    // this process.
    uintptr_t original = 0;
    if (!RttiLookup::patchVtableSlot(clientInstanceVtable, update_target.slot,
                                     reinterpret_cast<uintptr_t>(ClientInstance_update_hook),
                                     &original)) {
        LOGE("failed to patch %s::update slot %zu", update_target.className.c_str(),
             update_target.slot);
        clientInstanceVtable = 0;
        return;
    }
    if (original != update) {
        LOGE("%s::update slot changed under us: expected 0x%lx, found 0x%lx",
             update_target.className.c_str(), (unsigned long) update, (unsigned long) original);
        RttiLookup::patchVtableSlot(clientInstanceVtable, update_target.slot, original, nullptr);
        clientInstanceVtable = 0;
        return;
    }
    ClientInstance_update_real = reinterpret_cast<ClientInstance_update>(original);
    LOGD("hooked %s::update: original 0x%lx -> handler %p", update_target.className.c_str(),
         (unsigned long) original,
         reinterpret_cast<void *>(ClientInstance_update_hook));

    // Hand the validated tables to the SDK layer so its getters resolve through
    // exactly what the hooks proved live, not a second RTTI walk.
    GameSdk::setClientInstanceTables(clientInstanceVtable, slots, guiDataVtable);
    if (gdTableCount != 0) GameSdk::setGuiDataTables(gdTables, gdTableCount);

    // ClientInstance::isShowingMenu — virtual, so it goes through the SAME vtable
    // we just validated. The slot index comes from the rule file only (Levi's
    // windows name it 150/151; there is no safe built-in guess, and a wrong slot
    // patched blindly would corrupt every call through it). No rule, no key →
    // skipped with a log, exactly like the sig path.
    if (gHasRule) {
        VirtualTarget menu_target;
        std::string cls;
        size_t slot = 0;
        if (gRule.lookupVtable("isShowingMenu", &cls, &slot)) {
            if (cls == update_target.className) {
                menu_target.className = cls;
                menu_target.slot = slot;
                menu_target.key = "isShowingMenu";
            } else {
                LOGW("isShowingMenu names class '%s' but update resolved '%s': not one table, "
                     "skipping the vtable patch", cls.c_str(), update_target.className.c_str());
            }
        } else {
            LOGD("rule '%s' defines no vtable.isShowingMenu: menu-state flag stays at its "
                 "PauseMenu/HudScreen estimate", gRule.name.c_str());
        }
        if (!menu_target.className.empty()) {
            uintptr_t fn = resolveVirtual(clientInstanceVtable, menu_target.slot, slots);
            uintptr_t orig2 = 0;
            if (fn != 0 && RttiLookup::patchVtableSlot(clientInstanceVtable, menu_target.slot,
                                                       reinterpret_cast<uintptr_t>(
                                                               ClientInstance_isShowingMenu_hook),
                                                       &orig2) && orig2 == fn) {
                ClientInstance_isShowingMenu_real =
                        reinterpret_cast<ClientInstance_isShowingMenu>(orig2);
                LOGD("hooked %s::isShowingMenu slot %zu: original 0x%lx",
                     menu_target.className.c_str(), menu_target.slot, (unsigned long) orig2);
            } else if (fn != 0) {
                if (orig2 != 0 && orig2 != fn) {
                    RttiLookup::patchVtableSlot(clientInstanceVtable, menu_target.slot, orig2,
                                                nullptr);  // restore what we touched
                }
                LOGW("isShowingMenu slot %zu (0x%lx) could not be patched safely",
                     menu_target.slot, (unsigned long) fn);
            }
        }
    }
}

}  // namespace

void load(JNIEnv *env, jobject obj) {
    initManagers();

    Main::baseAddress = Main::findLibBaseAddress("libminecraftpe.so");
    if (Main::baseAddress <= 0L) {
        LOGE("Main::baseAddress <= 0L");
    }

    LOGD("Minecraft Handle : %lx", Main::baseAddress);
    GameSdk::init(static_cast<uintptr_t>(Main::baseAddress));

    // RTTI first: it works on any build whose class layout is unchanged, and
    // needs no dlopen of the guest library (which our linker namespace cannot
    // do anyway). The signatures below are the fallback for the functions that
    // are not virtual, and for builds where the RTTI chain does not validate.
    installRttiHooks();
    installSignatureHooks();
}

// The patterns this code shipped with. Every one of them belongs to a single
// pre-1.26 build; the rule file overrides them per Minecraft version, and a rule
// that names no pattern for a function means that function stays unhooked on
// purpose instead of pretending the scan was worth running.
static const char *kPatternMinecraftUpdate =
        "E8 0F ?? ?? FD 7B 01 A9 FD 43 00 91 FC 6F 02 A9 FA 67 03 A9 F8 5F 04 A9 F6 57 05 A9 F4 4F 06 A9 FF 03 0C D1 5A ?? ?? ?? F3 03 00 AA 48 17";
static const char *kPatternSendClientMessage =
        "FF C3 07 D1 FD 7B 1C A9 FD 03 07 91 FC 57 1D A9 F4 4F 1E A9 55 ?? ?? ?? F4 03 03 2A F3 03 00 AA A8 16 ?? ?? E4 03 02 AA E3 03 01 AA A8 83 ?? ?? FF 83 ?? ?? FF 53 ?? ?? FF 23 ?? ?? 45 ?? ?? ?? E6 03 1F 2A E7 03 1F 2A E8 43 00 91 E9 A3 00 91 A0 E3 02 D1 A5 ?? ?? ?? E2 03 01 91 00 ?? ?? ?? 41 ?? ?? ?? E9 23 00 A9 8F ?? ?? ??";
static const char *kPatternAddMessage =
        "? ? ? D1 ? ? ? A9 ? ? ? A9 ? ? ? A9 ? ? ? A9 ? ? ? A9 ? ? ? 91 59 D0 3B D5 F3 03 00 AA F4 03 05 AA 28 17 40 F9";
static const char *kPatternJoinMultiplayerGame =
        "FD 7B BA A9 FC 6F 01 A9 FD 03 00 91 FA 67 02 A9 F8 5F 03 A9 F6 57 04 A9 F4 4F 05 A9 FF 43 17 D1 53 ?? ?? ?? F8 03 08 AA 68 16 ?? ?? F7 03 02 AA F4 03 01 AA F5 03 00 AA A8 83";

// One entry of the byte-signature table: the key it is looked up under in the
// rule file, the name it is reported as, the built-in pattern, and the pair of
// handler/original pointers shadowhook fills in.
struct SignatureHook {
    const char *key;
    const char *pretty;
    const char *fallback;
    void *handler;
    void **real;
    // Bare class name for the RTTI fallback below, or "" when the function is
    // not virtual and a failed body patch simply means "skip this build".
    const char *rttiClass;
};

static void hookBySignature(const ModuleInfo &info, const SignatureHook &entry) {
    // An explicit offset is the strongest handle the rule file can carry: no
    // scan, no pattern to rot. It is also build-specific, which is why it only
    // ever sits inside a version window.
    uintptr_t rva = 0;
    if (gHasRule && gRule.lookupOffset(entry.key, &rva)) {
        uintptr_t address = info.base + rva;
        LOGD("%s: rule '%s' pins it at 0x%llx (library base + 0x%llx)", entry.pretty,
             gRule.name.c_str(), (unsigned long long) address, (unsigned long long) rva);
        hookGameFunction(address, entry.handler, entry.real, entry.pretty);
        return;
    }
    std::string pattern;
    if (gHasRule) {
        pattern = gRule.lookupSignature(entry.key);
        if (pattern.empty()) {
            LOGW("rule '%s' defines no pattern or offset for %s: this Minecraft build is not "
                 "covered, hook skipped", gRule.name.c_str(), entry.pretty);
            return;
        }
    } else {
        pattern = entry.fallback;
    }
    uintptr_t found = FindSignature::findSignatureInModule(info, pattern.c_str());
    if (found != 0) {
        LOGD("%s: pattern matched at 0x%lx (library base + 0x%llx)", entry.pretty,
             (unsigned long) found, (unsigned long long) (found - info.base));
    }
    if (hookGameFunction(found, entry.handler, entry.real, entry.pretty)) return;

    // The body refused the patch (island dead band + unrelocatable prologue, or a
    // shadowhook failure). If this is a virtual function — the rule author names
    // its class in the table — the RTTI chain still knows who dispatches to it:
    // find the vtable slot equal to the matched address and patch THAT instead.
    // One pointer store, no trampoline, no island, MAP_PRIVATE so process-local.
    if (found != 0 && entry.rttiClass != nullptr && entry.rttiClass[0] != '\0') {
        uintptr_t table = 0;
        size_t slot = 0;
        uintptr_t original = 0;
        if (RttiLookup::findVirtualSlotForFunction(kGuestLib, entry.rttiClass, found, &table,
                                                   &slot) &&
            RttiLookup::patchVtableSlot(table, slot,
                                        reinterpret_cast<uintptr_t>(entry.handler), &original) &&
            // The resolver guarantees the slot IS the matched body or its deleting-
            // destructor wrapper (which BLs into it), so any nonzero read-back is the
            // untouched table value and forwarding to it keeps both call kinds
            // covered — same semantics as Levi's body patch.
            original != 0) {
            *entry.real = reinterpret_cast<void *>(original);
            LOGD("hooked %s at slot %zu of vtable %p by vtable patch (body unpatchable)",
                 entry.pretty, slot, reinterpret_cast<void *>(table));
        } else if (original != 0 && original != found) {
            RttiLookup::patchVtableSlot(table, slot, original, nullptr);  // restore
            LOGW("%s: vtable slot %zu changed under us, restored", entry.pretty, slot);
        } else {
            LOGW("%s: no vtable slot of '%s' dispatches to 0x%lx; hook skipped", entry.pretty,
                 entry.rttiClass, (unsigned long) found);
        }
    }
}

// The pinned-prologue path, for the functions RTTI cannot reach because they are
// not virtual. `Minecraft::update` is only tried when RTTI did not hand us the
// game instance, so a build is never patched twice for the same purpose.
void installSignatureHooks() {
    ModuleInfo info = FindSignature::getModuleInfo(kGuestLib);
    size_t scannedBytes = 0;
    for (const auto &r : info.regions) scannedBytes += r.second;
    LOGD("scanning %s: %zu executable region(s), %zu bytes total (first at 0x%lx, %zu bytes) for %s",
         kGuestLib, info.regions.size(), scannedBytes, (unsigned long) info.base, info.size,
         gHasRule ? "the patterns named by the active rule" : "the built-in patterns");

    if (clientInstanceVtable == 0) {
        const SignatureHook update = {"minecraftUpdate", "Minecraft::update",
                                      kPatternMinecraftUpdate,
                                      (void *) Minecraft_update_hook,
                                      (void **) &Minecraft_update_real};
        hookBySignature(info, update);
    } else {
        LOGD("Minecraft::update signature skipped: the RTTI hook already owns the game instance");
    }

    const SignatureHook entries[] = {
            {"guiDataSendClientMessage", "GuiData::sendClientMessage", kPatternSendClientMessage,
             (void *) GuiDate_sendClientMessage_hook,
             (void **) &GuiDate_sendClientMessage_real, ""},
            {"guiDataAddMessage",        "GuiData::addChatMessage(wrapper)", kPatternAddMessage,
             (void *) GuiData_addMessage_hook,
             (void **) &GuiData_addMessage_real, ""},
            {"joinMultiplayerGame",      "MinecraftGame::joinMultiplayerGame",
             kPatternJoinMultiplayerGame,
             (void *) MinecraftGame__joinMultiplayerGame_hook,
             (void **) &MinecraftGame__joinMultiplayerGame_real, ""},
            // M4 menu-state hooks. No built-in fallback pattern: these keys only
            // exist in the levi-* rule windows, and outside them the hook is
            // skipped with a log rather than guessed. The dtors are virtual, so
            // they name their class for the vtable-slot fallback when the body
            // patch is refused (no island + unrelocatable prologue words).
            {"pauseMenuDtor",            "PauseMenu::~PauseMenu",           "",
             (void *) PauseMenu_dtor_hook,
             (void **) &PauseMenu_dtor_real, "PauseMenu"},
            {"pauseMenuOpen",            "PauseMenu::_openPauseScreen",     "",
             (void *) PauseMenu_open_hook,
             (void **) &PauseMenu_open_real, ""},
            {"hudScreenDtor",            "HudScreen::~HudScreen",           "",
             (void *) HudScreen_dtor_hook,
             (void **) &HudScreen_dtor_real, "HudScreen"},
            {"hudScreenOpen",            "HudScreen::_openHudScreen",       "",
             (void *) HudScreen_open_hook,
             (void **) &HudScreen_open_real, ""},
    };
    for (size_t i = 0; i < sizeof(entries) / sizeof(entries[0]); i++) {
        hookBySignature(info, entries[i]);
    }

    // Publish the chat route to the SDK layer: the real (pre-hook or bridge)
    // addMessage if the hook installed, else the rule-pinned address itself.
    if (reinterpret_cast<uintptr_t>(GuiData_addMessage_real) != 0) {
        GameSdk::addMessageRouteRef() = reinterpret_cast<uintptr_t>(GuiData_addMessage_real);
    } else {
        uintptr_t rva = 0;
        if (gHasRule && gRule.lookupOffset(std::string("guiDataAddMessage"), &rva)) {
            GameSdk::addMessageRouteRef() = info.base + rva;
        } else if (gHasRule) {
            // No hook bridge and no offset pin: the BedrockTools route. The
            // pattern scan resolves an address we can CALL even though the
            // island dead band forbids PATCHING it. One cached scan.
            uintptr_t sig = SigScanner::resolve(std::string("guiDataAddMessage"));
            if (sig != 0) GameSdk::addMessageRouteRef() = sig;
        }
    }
    LOGD("SigScanner: %zu/%zu signature key(s) resolved so far", SigScanner::hitCount(),
         SigScanner::scannedCount());
}

// Main functions
JNIEnv *Main::currentEnv() {
    JNIEnv *env = nullptr;
    vm->AttachCurrentThread(&env, nullptr);
    return env;
}

int Main::myPid() {
    JNIEnv *env = currentEnv();
    jclass processClass = env->FindClass("android/os/Process");
    jmethodID myPidMethod = env->GetStaticMethodID(processClass, "myPid", "()I");
    return env->CallStaticIntMethod(processClass, myPidMethod);
}

long Main::findLibBaseAddress(std::string libName) {
    int pid = myPid();
    std::ifstream ifstream;
    std::ostringstream ostringstream;
    ostringstream << "/proc/" << pid << "/maps";

    ifstream.open(ostringstream.str(), std::ios::in);
    if (ifstream.bad()) {
        ifstream.close();
        return 0L;
    }

    long localBaseAddress = 0L;
    std::string line;
    while (getline(ifstream, line)) {
        if (strstr(line.c_str(), libName.c_str()) != nullptr) {
            sscanf(line.c_str(), "%lx-%*lx", &localBaseAddress);
            break;
        }
    }

    ifstream.close();
    return localBaseAddress;
}

jobject Main::getApplication() {
    JNIEnv *env = currentEnv();
    jclass activityThreadClass = env->FindClass("android/app/ActivityThread");
    jmethodID currentActivityThreadMethod = env->GetStaticMethodID(activityThreadClass,
                                                                   "currentActivityThread",
                                                                   "()Landroid/app/ActivityThread;");
    jobject activityThread = env->CallStaticObjectMethod(activityThreadClass,
                                                         currentActivityThreadMethod);
    jmethodID getApplicationMethod = env->GetMethodID(activityThreadClass, "getApplication",
                                                      "()Landroid/app/Application;");
    return env->CallObjectMethod(activityThread, getApplicationMethod);
}

void Main::showToast(std::string message) {
    JNIEnv *env = currentEnv();
    jclass toastClass = env->FindClass("android/widget/Toast");
    jmethodID makeTextMethod = env->GetStaticMethodID(toastClass, "makeText",
                                                      "(Landroid/content/Context;Ljava/lang/CharSequence;I)Landroid/widget/Toast;");
    jstring messageStr = env->NewStringUTF(message.c_str());
    jobject toast = env->CallStaticObjectMethod(toastClass, makeTextMethod, getApplication(),
                                                messageStr, 0);
    jmethodID showMethod = env->GetMethodID(toastClass, "show", "()V");
    env->CallVoidMethod(toast, showMethod);
}

std::string Main::toHexString(long value) {
    std::ostringstream ostringstream;
    ostringstream << std::hex << value;
    return ostringstream.str();
}