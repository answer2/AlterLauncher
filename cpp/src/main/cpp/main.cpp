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

// Initialize variables
JavaVM *Main::vm = nullptr;
long Main::baseAddress = 0L;
AAssetManager *Main::manager = nullptr;
const char* corePath = "com/answer/launcher/core/manager/NativeCore";

// Function declarations
void initAssets(JNIEnv *env, jobject obj, jobject assetManager);
void loadLibrary_java(JNIEnv *env, jobject obj, jstring libraryPath);
void unloadLibrary_java(JNIEnv *env, jobject obj, jlong handle);
long findLibBaseAddress_java(JNIEnv *env, jobject obj, jstring library);
void load(JNIEnv *env, jobject obj);
void loadManager(JNIEnv *env, jobject obj);

// JNI methods array
static JNINativeMethod MethodCalled[] = {
    {"initAssets", "(Landroid/content/res/AssetManager;)V", (void*)initAssets},
    {"loadLibrary", "(Ljava/lang/String;)J", (void*)loadLibrary_java},
    {"unloadLibrary", "(J)V", (void*)unloadLibrary_java},
    {"findLibBaseAddress", "(Ljava/lang/String;)J", (void*)findLibBaseAddress_java},
    {"loadManager", "()V", (void*)load}
};

// JNI OnLoad
JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    Main::vm = vm;
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        LOGE("JNI_VERSION is not 1.6");
        return JNI_ERR;
    }

    if (!Utils::registerNativeMethods(env, corePath, MethodCalled, sizeof(MethodCalled) / sizeof(MethodCalled[0]))) {
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
    initManagers();
}

void unloadLibrary_java(JNIEnv *env, jobject obj, jlong handle) {
    Utils::unloadLibrary((void*)handle);
}

long findLibBaseAddress_java(JNIEnv *env, jobject obj, jstring library) {
    return Main::findLibBaseAddress(jstringToCharArr(env, library));
}

void dumpAdder(void** handle) {
    FILE* fp = fopen("/sdcard/1.txt", "w");

    auto appPlat = (void**)handle;
    auto raw = &appPlat[2];

    fprintf(fp, "AppPlatform : \n");

    for (int i = 0; raw[i] && raw[i] != (void*)0xffffffffffffffe8; i++) {
        Dl_info data;
        fprintf(fp, "%p (%s)    :    %p\n", raw[i], dladdr(raw[i], &data) ? data.dli_sname : "(unknown)", ((uintptr_t)raw[i] - (uintptr_t)data.dli_fbase));
    }

    fclose(fp);
}

void* SetHook(void *handle, uintptr_t address) {
    uintptr_t baseAddr = ((soinfo2*)handle)->base;
    return (void*)(baseAddr + address);
}


// 定义目标函数指针类型
typedef int (*target_func_t)(int*, int*);

// 保存目标函数的原始地址
int (*target_func_real)(int*, int*);
// Hook函数实现
int hook_func(int *x0, int *x1) {
    LOGD("Alternative : Hook section_75063296 success");
    if (x0 == NULL) {
        LOGD("Warning: x0 is NULL, assigning default value.\n");
        // 修复x0，给它分配内存或赋予默认值
        x0 = (int*)malloc(sizeof(int));
        if (x0 == NULL) {
            LOGD( "Error: Memory allocation failed\n");
            return -1;
        }
        *x0 = 0x16; // 根据需要设置默认值
    }
    return target_func_real(x0, x1);
}


void load(JNIEnv *env, jobject obj) {
    // Example commented code, left for future implementation if needed
    
    void* handle = dlopen("libminecraftpe.so", RTLD_LAZY);
    if (handle == nullptr) {
        LOGE("open error %s", dlerror());
        return;
    }
    
    uintptr_t handleBase = ((soinfo2*)handle)->base;

    LOGD("Minecraft Handle : %p", handleBase);

    Main::baseAddress = Main::findLibBaseAddress("libminecraftpe.so");
    if (Main::baseAddress <= 0L) {
        LOGE("Main::baseAddress <= 0L");
    }

    LOGD("Minecraft Handle : %lx", Main::baseAddress);

/*
    uintptr_t p = FindSignature::findSignatureInModule("libminecraftpe.so", "60 3E 04 F0 00 00 00 91 2E 40 FF 15 C0 03 5F D6 59 50 44 15 40 00 00 B4 00 ");
    LOGD("SO Handle : %p", p);
    
    // 获取函数的地址，根据汇编的分析来确认函数的起始地址
    target_func_t original_func = (target_func_t)((uintptr_t)handle + 0x5d72678);
   
    LOGD("SO Handle : %lu", original_func);
    // void* EnchantVT = SetHook(handle, p);
    // dumpAdder((void**)Main::baseAddress);
    */
    
   // shadowhook_hook_sym_addr((void*)original_func, (void*)hook_func, (void**)&target_func_real);
    /*
    LOGD("SO Handle : %s", Utils::analyzeAppPlatformVTable(p).c_str());
    */
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
    jmethodID currentActivityThreadMethod = env->GetStaticMethodID(activityThreadClass, "currentActivityThread", "()Landroid/app/ActivityThread;");
    jobject activityThread = env->CallStaticObjectMethod(activityThreadClass, currentActivityThreadMethod);
    jmethodID getApplicationMethod = env->GetMethodID(activityThreadClass, "getApplication", "()Landroid/app/Application;");
    return env->CallObjectMethod(activityThread, getApplicationMethod);
}

void Main::showToast(std::string message) {
    JNIEnv *env = currentEnv();
    jclass toastClass = env->FindClass("android/widget/Toast");
    jmethodID makeTextMethod = env->GetStaticMethodID(toastClass, "makeText", "(Landroid/content/Context;Ljava/lang/CharSequence;I)Landroid/widget/Toast;");
    jstring messageStr = env->NewStringUTF(message.c_str());
    jobject toast = env->CallStaticObjectMethod(toastClass, makeTextMethod, getApplication(), messageStr, 0);
    jmethodID showMethod = env->GetMethodID(toastClass, "show", "()V");
    env->CallVoidMethod(toast, showMethod);
}

std::string Main::toHexString(long value) {
    std::ostringstream ostringstream;
    ostringstream << std::hex << value;
    return ostringstream.str();
}
