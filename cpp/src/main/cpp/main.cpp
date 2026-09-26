#include <jni.h>
#include <string>
#include <sstream>
#include <dlfcn.h>
#include <cstdio>
#include <stdint.h>
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

// Function declarations
void initAssets(JNIEnv *env, jobject obj, jobject assetManager);

jlong loadLibrary_java(JNIEnv *env, jobject obj, jstring libraryPath);

void unloadLibrary_java(JNIEnv *env, jobject obj, jlong handle);

long findLibBaseAddress_java(JNIEnv *env, jobject obj, jstring library);

void load(JNIEnv *env, jobject obj);

void loadManager(JNIEnv *env, jobject obj);

// Declared here because load() calls it and its definition follows load().
void installSignatureHooks();

void sendMessage(JNIEnv *env, jobject obj, jstring msg);

void hookDlopen_java(JNIEnv *env, jobject obj);

// JNI methods array
static JNINativeMethod MethodCalled[] = {
        {"initAssets",         "(Landroid/content/res/AssetManager;)V", (void *) initAssets},
        {"loadLibrary",        "(Ljava/lang/String;)J",                 (void *) loadLibrary_java},
        {"unloadLibrary",      "(J)V",                                  (void *) unloadLibrary_java},
        {"findLibBaseAddress", "(Ljava/lang/String;)J",                 (void *) findLibBaseAddress_java},
        {"loadManager",        "()V",                                   (void *) loadManager},
        {"sendMessage",        "(Ljava/lang/String;)V",                 (void *) sendMessage},
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
    Minecraft_update_real(minecraftGame_);
    return nullptr;//
}

// 保存目标函数的原始地址
void *(*GuiData_addMessage_real)(void *unknow, int type,
                                 const std::string &sender,
                                 const std::string &message,
                                 void *uuid,
                                 const std::string &ustring,
                                 void *isSystem,
                                 void *bbool,
                                 float duration,
                                 const std::string &senderTeam,
                                 const std::string &senderXUID);

// 保存目标函数的原始地址
void (*GuiData_addMessage)(
        void *unknow, int type,
        const std::string &sender,
        const std::string &message,
        void *uuid,
        const std::string &ustring,
        void *isSystem,
        void *bbool,
        float duration,
        const std::string &senderTeam,
        const std::string &senderXUID);

// 保存目标函数的原始地址
void *
(*GuiDate_sendClientMessage_real)(void *void1, const std::string &msg, const std::string &msg2);

static bool isfrist = false;

// Hook函数实现
void *GuiData_addMessage_hook(void *guidate,
                              int type,
                              const std::string &sender,
                              const std::string &message,
                              void *uuid,
                              const std::string &ustring,
                              void *isSystem,
                              void *bbool,
                              float duration,
                              const std::string &senderTeam,
                              const std::string &senderXUID) {
    LOGD("GuiData Address : %p", guidate);

    LOGD("Minecraft Hook sendMessage %s -Type %d UUID %d isSystem %s is %s: %s", sender.c_str(),
         type, uuid, senderTeam.c_str(), senderXUID.c_str(),
         message.c_str());
    //print_callstack_unwind();

    GuiData_addMessage_real(guidate, type, sender, message, uuid, ustring, isSystem, bbool,
                            duration,
                            senderTeam, senderXUID);


    return nullptr;//
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
    // The old code called vtable slot 218 of the captured game object blind.
    // Slot numbering drifts between builds — 1.21 puts getGuiData at 219/220,
    // 1.26 has 8-byte stubs there — and invoking an unknown virtual is a crash
    // waiting to happen, so the chat route has to be resolved explicitly before
    // this can do anything beyond reporting the state we reached.
    LOGI("sendMessage(\"%s\"): ClientInstance=%p MinecraftGame=%p updateCalls=%ld "
         "guiDataVtable=%p", text, clientInstance, minecraftGame, updateCalls,
         reinterpret_cast<void *>(guiDataVtable));
    env->ReleaseStringUTFChars(msg, text);
}

void* (*MinecraftGame__joinMultiplayerGame_real)(void*, std::string const&, void*);
void *MinecraftGame__joinMultiplayerGame_hook(void* minecraftGame, std::string const& a1, void* a2){
    LOGD("Minecraft Hook MinecraftGame__joinMultiplayerGame : %p  %s %p", minecraftGame, a1.c_str(), a2);
    MinecraftGame__joinMultiplayerGame_real(minecraftGame, a1, a2);
    return nullptr;//
}


// The signatures below are pinned to one Minecraft build. A build that does not
// contain the pattern yields address 0, and handing that to shadowhook used to be
// invisible because shadowhook itself could not initialise on Android 16 (errno 12)
// and every call failed anyway. Now that inline hooking works, report each miss and
// each failed install instead of pretending the function was patched.
static void hookGameFunction(uintptr_t address, void *handler, void **real,
                             const char *name) {
    if (address == 0) {
        LOGW("signature not found for %s: this Minecraft build is not covered, hook skipped", name);
        return;
    }
    if (shadowhook_hook_func_addr((void *) address, handler, real) == nullptr) {
        LOGE("failed to hook %s at 0x%lx: %s", name, (unsigned long) address,
             shadowhook_to_errmsg(shadowhook_get_errno()));
        return;
    }
    LOGD("hooked %s at 0x%lx", name, (unsigned long) address);
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
constexpr size_t kClientInstanceUpdateSlot = 25;

typedef uintptr_t (*ClientInstance_update)(void *, bool);
ClientInstance_update ClientInstance_update_real = nullptr;

uintptr_t ClientInstance_update_hook(void *self, bool paused) {
    if (clientInstance == nullptr) {
        clientInstance = self;
        LOGI("ClientInstance captured at %p (vtable %p, guiData vtable %p)", self,
             reinterpret_cast<void *>(clientInstanceVtable),
             reinterpret_cast<void *>(guiDataVtable));
    }
    updateCalls++;
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

void installRttiHooks() {
    size_t slots = 0;
    guiDataVtable = RttiLookup::findVtable(kGuestLib, "GuiData", NULL);
    clientInstanceVtable = RttiLookup::findVtable(kGuestLib, "ClientInstance", &slots);
    uintptr_t update = resolveVirtual(clientInstanceVtable, kClientInstanceUpdateSlot, slots);
    if (update == 0) {
        LOGW("ClientInstance::update is not resolvable by RTTI in this build");
        clientInstanceVtable = 0;
        return;
    }
    LOGD("resolved ClientInstance vtable %p (%zu slots), update(bool) at 0x%lx",
         reinterpret_cast<void *>(clientInstanceVtable), slots, (unsigned long) update);

    // The vtable slot is patched rather than the function body: shadowhook
    // cannot allocate its trampoline island anywhere inside the guest's 298 MB
    // executable mapping ("Alloc island for exit failed"), and for a virtual
    // function the slot is the natural place to intercept anyway.
    uintptr_t original = 0;
    if (!RttiLookup::patchVtableSlot(clientInstanceVtable, kClientInstanceUpdateSlot,
                                     reinterpret_cast<uintptr_t>(ClientInstance_update_hook),
                                     &original)) {
        LOGE("failed to patch ClientInstance::update slot %zu", kClientInstanceUpdateSlot);
        clientInstanceVtable = 0;
        return;
    }
    if (original != update) {
        LOGE("ClientInstance::update slot changed under us: expected 0x%lx, found 0x%lx",
             (unsigned long) update, (unsigned long) original);
        RttiLookup::patchVtableSlot(clientInstanceVtable, kClientInstanceUpdateSlot, original,
                                    nullptr);
        clientInstanceVtable = 0;
        return;
    }
    ClientInstance_update_real = reinterpret_cast<ClientInstance_update>(original);
    LOGD("hooked ClientInstance::update: original 0x%lx -> handler %p", (unsigned long) original,
         reinterpret_cast<void *>(ClientInstance_update_hook));
}

}  // namespace

void load(JNIEnv *env, jobject obj) {
    initManagers();

    Main::baseAddress = Main::findLibBaseAddress("libminecraftpe.so");
    if (Main::baseAddress <= 0L) {
        LOGE("Main::baseAddress <= 0L");
    }

    LOGD("Minecraft Handle : %lx", Main::baseAddress);

    // RTTI first: it works on any build whose class layout is unchanged, and
    // needs no dlopen of the guest library (which our linker namespace cannot
    // do anyway). The signatures below are the fallback for the functions that
    // are not virtual, and for builds where the RTTI chain does not validate.
    installRttiHooks();
    installSignatureHooks();
}

// The pinned-prologue path. `Minecraft::update` is only tried when RTTI did not
// hand us the game instance, so a modern build is not patched twice; the chat
// and join-game functions are not virtual, so they have no RTTI equivalent and
// still depend on Mojang not having recompiled them.
void installSignatureHooks() {
    ModuleInfo info = FindSignature::getModuleInfo("libminecraftpe.so");
    LOGD("TEST %lu", info.base);
    LOGD("TEST %zu", info.size);

    if (clientInstanceVtable == 0) {
        uintptr_t update = FindSignature::findSignatureInModule(info,
                                                                "E8 0F ?? ?? FD 7B 01 A9 FD 43 00 91 FC 6F 02 A9 FA 67 03 A9 F8 5F 04 A9 F6 57 05 A9 F4 4F 06 A9 FF 03 0C D1 5A ?? ?? ?? F3 03 00 AA 48 17");
        hookGameFunction(update, (void *) Minecraft_update_hook,
                         (void **) &Minecraft_update_real, "Minecraft::update");
        LOGD("update ： %p", update);
    } else {
        LOGD("Minecraft::update signature skipped: the RTTI hook already owns the game instance");
    }
    uintptr_t displayClientMessage = FindSignature::findSignatureInModule(info,
                                                                          "FF C3 07 D1 FD 7B 1C A9 FD 03 07 91 FC 57 1D A9 F4 4F 1E A9 55 ?? ?? ?? F4 03 03 2A F3 03 00 AA A8 16 ?? ?? E4 03 02 AA E3 03 01 AA A8 83 ?? ?? FF 83 ?? ?? FF 53 ?? ?? FF 23 ?? ?? 45 ?? ?? ?? E6 03 1F 2A E7 03 1F 2A E8 43 00 91 E9 A3 00 91 A0 E3 02 D1 A5 ?? ?? ?? E2 03 01 91 00 ?? ?? ?? 41 ?? ?? ?? E9 23 00 A9 8F ?? ?? ??");
    hookGameFunction(displayClientMessage, (void *) GuiDate_sendClientMessage_hook,
                     (void **) &GuiDate_sendClientMessage_real,
                     "GuiData::sendClientMessage");

    LOGD("displayClientMessage ： %p", displayClientMessage);

    uintptr_t addMessage = FindSignature::findSignatureInModule(info,
                                                                "E8 0F ?? ?? FD 7B 01 A9 FD 43 00 91 FA 67 02 A9 F8 5F 03 A9 F6 57 04 A9 F4 4F 05 A9 F5 03 00 AA 14 20 00 91 F3 03 00 AA E0 03 14 AA F7 03 07 2A F9 03 06 2A A1 06 ?? ?? E1 03 02 AA 08 40 20 1E FA 03 05 AA F8 03 04 AA F6 03 03 AA ?? ?? ?? ??");
    hookGameFunction(addMessage, (void *) GuiData_addMessage_hook,
                     (void **) &GuiData_addMessage_real, "GuiData::addMessage");
    LOGD("addMessage ： %p", addMessage);

    uintptr_t MinecraftGame__joinMultiplayerGame = FindSignature::findSignatureInModule(info,
                                                                                        "FD 7B BA A9 FC 6F 01 A9 FD 03 00 91 FA 67 02 A9 F8 5F 03 A9 F6 57 04 A9 F4 4F 05 A9 FF 43 17 D1 53 ?? ?? ?? F8 03 08 AA 68 16 ?? ?? F7 03 02 AA F4 03 01 AA F5 03 00 AA A8 83");
    hookGameFunction(MinecraftGame__joinMultiplayerGame,
                     (void *) MinecraftGame__joinMultiplayerGame_hook,
                     (void **) &MinecraftGame__joinMultiplayerGame_real,
                     "MinecraftGame::joinMultiplayerGame");

    LOGD("MinecraftGame__joinMultiplayerGame ： %p", MinecraftGame__joinMultiplayerGame);

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
