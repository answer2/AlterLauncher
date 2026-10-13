#include <jni.h>

#include <ctime>
#include <string>

#include <dlfcn.h>
#include <sys/system_properties.h>
#include <android/asset_manager_jni.h>

#include <android/android_log.h>
#include <hook/GameHooks.h>
#include <hook/RuleState.h>
#include <hook/fake_asset.h>
#include <hook/fake_dlopen.h>
#include <hook/fake_file.h>
#include <hook/manager_minecraft.h>
#include <main.h>
#include <mcpe/core/GameSDK.h>
#include <mods/AlterPreloader.h>
#include <runtime/Bootstrap.h>
#include <runtime/ProcessName.h>
#include <util/HookEngine.h>
#include <util/Utils.h>
#include <util/stringUtil.h>

// The whole Java <-> native surface of libalternative_core.so: what JNI_OnLoad
// registers, and what each registered entry does. Everything that touches the
// game lives elsewhere (hook/, mcpe/, memory/, runtime/); this file only converts
// JNI arguments and forwards.
namespace {

const char *const kCorePath = "com/answer/launcher/core/manager/NativeCore";

// Read once in JNI_OnLoad from the system properties the launcher sets before
// loading this library (see Alternative.java). g_modsOn mirrors the
// native-mods switch; g_glossBackend selects the hook framework — when it is
// set, alternative_core hooks through GlossHook too, so shadowhook never
// enters the process and there is no second framework to fight the linker.
// g_alterPreloader picks our own mod loader over Levi's; g_modsDir is where
// the pre-launch symbol scan looks.
bool g_modsOn = false;
bool g_glossBackend = false;
bool g_alterPreloader = true;
std::string g_modsDir;

jstring readSystemProperty(JNIEnv *env, const char *key) {
    jclass sys = env->FindClass("java/lang/System");
    if (sys == nullptr) { env->ExceptionClear(); return nullptr; }
    jmethodID getProp = env->GetStaticMethodID(sys, "getProperty",
            "(Ljava/lang/String;)Ljava/lang/String;");
    if (getProp == nullptr) { env->ExceptionClear(); return nullptr; }
    jstring jkey = env->NewStringUTF(key);
    auto value = (jstring) env->CallStaticObjectMethod(sys, getProp, jkey);
    if (env->ExceptionCheck()) env->ExceptionClear();
    return value;
}

std::string readStringProperty(JNIEnv *env, const char *key) {
    jstring value = readSystemProperty(env, key);
    if (value == nullptr) return std::string();
    const char *text = env->GetStringUTFChars(value, nullptr);
    std::string out = text != nullptr ? text : "";
    if (text != nullptr) env->ReleaseStringUTFChars(value, text);
    return out;
}

void readLaunchFlags(JNIEnv *env) {
    if (env == nullptr) return;
    g_modsOn = readStringProperty(env, "alter.native_mods") == "1";
    g_glossBackend = readStringProperty(env, "alter.hook_backend") == "gloss";
    g_alterPreloader = readStringProperty(env, "alter.preloader") != "levi";
    g_modsDir = readStringProperty(env, "alter.mods_dir");
}

// shadowhook_init proxies the linker's soinfo::call_constructors, and the
// Levi mods' GlossHook hooks the very same function; whichever framework
// patches second ends up trampolining back into the first's proxy forever
// (stack-overflow SIGSEGV ~40s into loading, seen with ForceCloseOreUI).
// So whenever libpreloader.so is going to be in the process, GlossHook claims
// the linker hook first — GlossInit is first-call-only, so the preloader's
// later GlossInit(true) becomes a no-op and shadowhook chains cleanly on top.
//
// Whether it will be in the process is not known up front any more: with our
// own preloader selected, libpreloader.so is only mapped when a mod actually
// exports PLGetModRegistration. AlterPreloader answers that by reading each
// mod's dynamic symbol table off disk on a worker thread started here, and
// maps the library on the same thread when the answer is yes — the ~19 MB
// dlopen then overlaps the Java-side launch work instead of blocking on it.
// GlossInit(true), which patches the linker's text, stays on the main thread
// here just before the framework inits, so the patch can never race a
// concurrent dlopen.
//
// Blocks until the launch-time scan finished, then claims the linker hook on
// the calling (main) thread. No-op when the mods switch is off, when the Gloss
// backend is selected (HookEngine owns the claim then), or when the scan found
// nothing that needs the upstream preloader.
void claimUpstreamGloss() {
    if (!g_modsOn || g_glossBackend) return;
    void *handle = AlterPreloader::awaitUpstreamHandle();
    if (handle == nullptr) {
        LOGD("no mod needs libpreloader.so, GlossHook stays out of the process");
        return;
    }
    typedef void (*GlossInit_t)(bool);
    auto glossInit = (GlossInit_t) dlsym(handle, "GlossInit");
    if (glossInit == nullptr) {
        LOGD("libpreloader.so exports no GlossInit");
        return;
    }
    timespec t0{}, t1{};
    clock_gettime(CLOCK_MONOTONIC, &t0);
    glossInit(true);
    clock_gettime(CLOCK_MONOTONIC, &t1);
    LOGD("GlossHook claimed before shadowhook: GlossInit %ld ms",
         (t1.tv_sec - t0.tv_sec) * 1000L + (t1.tv_nsec - t0.tv_nsec) / 1000000L);
}

// ---------------------------------------------------------------------------
// NativeCore entry points
// ---------------------------------------------------------------------------

void initAssets(JNIEnv *env, jobject /*obj*/, jobject assetManager) {
    Main::manager = AAssetManager_fromJava(env, assetManager);
    // On the shadowhook backend the Gloss claim (if libpreloader.so turned out
    // to be needed) must land before the framework inits; on the Gloss backend
    // there is nothing to claim against and HookEngine::init does the GlossInit
    // itself.
    claimUpstreamGloss();
    HookEngine::select(g_glossBackend ? HookEngine::kGloss : HookEngine::kShadowhook);
    timespec ts0{}, ts1{};
    clock_gettime(CLOCK_MONOTONIC, &ts0);
    if (!HookEngine::init(g_modsOn)) {
        LOGD("hook framework init failed: %s\n", HookEngine::lastError());
    }
    clock_gettime(CLOCK_MONOTONIC, &ts1);
    LOGD("hook engine ready (backend=%s, %ld ms).\n",
         HookEngine::selected() == HookEngine::kGloss ? "gloss" : "shadowhook",
         (ts1.tv_sec - ts0.tv_sec) * 1000L + (ts1.tv_nsec - ts0.tv_nsec) / 1000000L);

    initFakeFile(env);
    initFakeAssets(env, Main::manager);
}

// NativeCore.loadLibrary is declared `public static native long` and this
// entry's signature is "(Ljava/lang/String;)J"; returning void here left the
// Java caller reading whatever x0 happened to hold after the call and handing
// that value to unloadLibrary.
jlong loadLibrary_java(JNIEnv *env, jobject /*obj*/, jstring libraryPath) {
    return reinterpret_cast<jlong>(Utils::loadLibrary(env, libraryPath));
}

void unloadLibrary_java(JNIEnv * /*env*/, jobject /*obj*/, jlong handle) {
    Utils::unloadLibrary((void *) handle);
}

jlong findLibBaseAddress_java(JNIEnv *env, jobject /*obj*/, jstring library) {
    return static_cast<jlong>(static_cast<uintptr_t>(
        Main::findLibBaseAddress(jstringToCharArr(env, library))));
}

void hookDlopen_java(JNIEnv * /*env*/, jobject /*obj*/) {
    initFakeDlopen();
}

void loadManager(JNIEnv *env, jobject obj) {
    Bootstrap::load();
}

// NativeCore.applyHookRules: hand the native layer the version-gated hook data.
// Must run before loadManager — it decides which slot indices and which byte
// patterns the hooks are built from. Never fatal; see hook/RuleState.h.
void applyHookRules_java(JNIEnv *env, jobject /*obj*/, jstring version, jstring rules) {
    std::string versionText;
    if (version != nullptr) {
        const char *chars = env->GetStringUTFChars(version, nullptr);
        if (chars != nullptr) {
            versionText = chars;
            env->ReleaseStringUTFChars(version, chars);
        }
    }
    std::string rulesText;
    if (rules != nullptr) {
        const char *chars = env->GetStringUTFChars(rules, nullptr);
        if (chars != nullptr) {
            rulesText = chars;
            env->ReleaseStringUTFChars(rules, chars);
        }
    }
    RuleState::apply(versionText, rulesText);
}

void sendMessage(JNIEnv *env, jobject /*obj*/, jstring msg) {
    const char *text = env->GetStringUTFChars(msg, nullptr);
    if (text == nullptr) return;
    if (GameHooks::clientInstance == nullptr && GameHooks::minecraftGame() == nullptr) {
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
         GameHooks::updateCalls);
    env->ReleaseStringUTFChars(msg, text);
}

// Menu state for Java (NativeCore.menuState()). The About page surfaces it so a
// device test can read the flags without logcat archaeology. Format is fixed:
// "pause=<0|1>,hud=<0|1>,showingMenu=<0|1>,pauseEvents=<n>,hudEvents=<n>,
// menuCalls=<n>". A pure read of state the game thread publishes — see
// GameSdk::describe().
jstring menuState_java(JNIEnv *env, jobject /*obj*/) {
    char buf[160];
    snprintf(buf, sizeof(buf), "pause=%d,hud=%d,showingMenu=%d,pauseEvents=%ld,hudEvents=%ld,menuCalls=%ld",
             GameHooks::isPauseMenuOpen.load() ? 1 : 0,
             GameHooks::isHudScreenOpen.load() ? 1 : 0,
             GameHooks::isShowingMenu.load() ? 1 : 0,
             GameHooks::pauseMenuEvents, GameHooks::hudScreenEvents, GameHooks::showingMenuCalls);
    char full[420];
    char sdk[192];
    GameSdk::describe(sdk, sizeof(sdk));
    snprintf(full, sizeof(full), "%s,%s", buf, sdk);
    return env->NewStringUTF(full);
}

void setProcessName_java(JNIEnv *env, jobject /*obj*/, jstring name) {
    const char *chars = env->GetStringUTFChars(name, nullptr);
    if (chars == nullptr) return;
    std::string target(chars);
    env->ReleaseStringUTFChars(name, chars);
    ProcessName::spoof(target);
}

JNINativeMethod kMethods[] = {
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
        {"hookDlopen",         "()V",                                   (void *) hookDlopen_java},
        // The fopen hook's redirect table, pushed once by IOManager after
        // the Java-side rules are complete (see hook/fake_file.cpp).
        {"setRedirectRules",   "([Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;)V",
         (void *) setRedirectRules_java},
        {"setProcessName",     "(Ljava/lang/String;)V",                 (void *) setProcessName_java},
        // Fires NativeCore.onFirstFrameNative once when the game swaps its
        // first EGL frame; only the in-game loading overlay installs it.
        {"installFirstFrameHook", "()V",                                (void *) installFirstFrameHook}
};

}  // namespace

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void * /*reserved*/) {
    Main::vm = vm;
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        LOGE("JNI_VERSION is not 1.6");
        return JNI_ERR;
    }

    if (!Utils::registerNativeMethods(env, kCorePath, kMethods,
                                      sizeof(kMethods) / sizeof(kMethods[0]))) {
        LOGD("Register Failed");
    }

    readLaunchFlags(env);
    AlterPreloader::setModsEnabled(g_modsOn);
    if (!AlterPreloader::registerNatives(env)) {
        LOGD("AlterModManager natives not registered");
    }
    if (g_modsOn && !g_glossBackend) {
        AlterPreloader::prepareAsync(g_modsDir, !g_alterPreloader);
    }

    // The game's native free-space probe (libc statvfs) must see this
    // installation's tree, not guest paths that do not exist on disk; a failed
    // statvfs reads as 0 bytes and trips Bedrock's storage-low gate.
    //InstallStatFsHooks();
    return JNI_VERSION_1_6;
}
