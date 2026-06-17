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

// Function declarations
void initAssets(JNIEnv *env, jobject obj, jobject assetManager);

void loadLibrary_java(JNIEnv *env, jobject obj, jstring libraryPath);

void unloadLibrary_java(JNIEnv *env, jobject obj, jlong handle);

long findLibBaseAddress_java(JNIEnv *env, jobject obj, jstring library);

void load(JNIEnv *env, jobject obj);

void loadManager(JNIEnv *env, jobject obj);

void sendMessage(JNIEnv *env, jobject obj, jstring msg);

// JNI methods array
static JNINativeMethod MethodCalled[] = {
        {"initAssets",         "(Landroid/content/res/AssetManager;)V", (void *) initAssets},
        {"loadLibrary",        "(Ljava/lang/String;)J",                 (void *) loadLibrary_java},
        {"unloadLibrary",      "(J)V",                                  (void *) unloadLibrary_java},
        {"findLibBaseAddress", "(Ljava/lang/String;)J",                 (void *) findLibBaseAddress_java},
        {"loadManager",        "()V",                                   (void *) loadManager},
        {"sendMessage",        "(Ljava/lang/String;)V",                 (void *) sendMessage}
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

void loadLibrary_java(JNIEnv *env, jobject obj, jstring libraryPath) {
    Utils::loadLibrary(env, libraryPath);
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
    //LOGD("Minecraft Hook Update : %lx",(void*) minecraftGame->sub_method3() );
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
    //sendClientMessage(env->GetStringUTFChars(msg, 0));
    if (minecraftGame == nullptr) {
        LOGI("Got Fail");
        return;
    }

    void *addresss = NativeInvoker::callV<60, void *>
            (minecraftGame);
    LOGI("Got %d site at: %p", 60, &addresss);

}


void load(JNIEnv *env, jobject obj) {
    initManagers();

    Main::baseAddress = Main::findLibBaseAddress("libminecraftpe.so");
    if (Main::baseAddress <= 0L) {
        LOGE("Main::baseAddress <= 0L");
    }

    LOGD("Minecraft Handle : %lx", Main::baseAddress);

    ModuleInfo info = FindSignature::getModuleInfo("libminecraftpe.so");
    LOGD("TEST %lu", info.base);
    LOGD("TEST %zu", info.size);

    uintptr_t update = FindSignature::findSignatureInModule(info,
                                                            "E8 0F ?? ?? FD 7B 01 A9 FD 43 00 91 FC 6F 02 A9 FA 67 03 A9 F8 5F 04 A9 F6 57 05 A9 F4 4F 06 A9 FF ?3 0D D1 5A ?? ?? ?? F3 03 00 AA 48 17 ?? ?? A8 03 ?? ?? 60 12 ?? ?? ?? ?? ?? ?? C0 ?? ?? ?? E0 03 13 AA 6B ?? ?? ?? ?8 ?? ?? ?? ?? ?? ?? ?? 08 FD ?? ?? 48 ?? ?? ?? 3B ?? ?? ?? ?1 ?? ?? ?? ?? ?? ?? ?? E0 E3 04 91 ?? ?? ?? ?? ?2 03 ?? ?? ?? ?? ?? ?? ?4 ?? ?? ?? FC 03 00 AA");
    shadowhook_hook_func_addr((void *) update, (void *) Minecraft_update_hook,
                              (void **) &Minecraft_update_real);

    uintptr_t displayClientMessage = FindSignature::findSignatureInModule(info,
                                                                          "FF C3 07 D1 FD 7B 1C A9 FD 03 07 91 FC 57 1D A9 F4 4F 1E A9 55 ?? ?? ?? F4 03 03 2A F3 03 00 AA A8 16 ?? ?? E4 03 02 AA E3 03 01 AA A8 83 ?? ?? FF 83 ?? ?? FF 53 ?? ?? FF 23 ?? ?? 45 ?? ?? ?? E6 03 1F 2A E7 03 1F 2A E8 43 00 91 E9 A3 00 91 A0 E3 02 D1 A5 ?? ?? ?? E2 03 01 91 00 ?? ?? ?? 41 ?? ?? ?? E9 23 00 A9 8F ?? ?? ??");
    shadowhook_hook_func_addr((void *) displayClientMessage,
                              (void *) GuiDate_sendClientMessage_hook,
                              (void **) &GuiDate_sendClientMessage_real);

    uintptr_t addMessage = FindSignature::findSignatureInModule(info,
                                                                "E8 0F ?? ?? FD 7B 01 A9 FD 43 00 91 FA 67 02 A9 F8 5F 03 A9 F6 57 04 A9 F4 4F 05 A9 F5 03 00 AA 14 20 00 91 F3 03 00 AA E0 03 14 AA F7 03 07 2A F9 03 06 2A A1 06 ?? ?? E1 03 02 AA 08 40 20 1E FA 03 05 AA F8 03 04 AA F6 03 03 AA ?? ?? ?? ??");
    shadowhook_hook_func_addr((void *) addMessage, (void *) GuiData_addMessage_hook,
                              (void **) &GuiData_addMessage_real);


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
