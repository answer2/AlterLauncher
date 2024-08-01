#include <jni.h>
#include <string>
#include <sstream>
#include <dlfcn.h>

#include <main.h>

#include <hook/fake_asset.h>
#include <hook/fake_dlopen.h>
#include <hook/manager_minecraft.h>

#include <util/stringUtil.h>
#include <util/fake_dlfcn.h>
#include <util/Utils.h>
#include <util/FindSignature.h>

#include <sys/system_properties.h>
#include <android/android_log.h>
#include <android/asset_manager_jni.h>

#include <stdio.h>
#include <stdint.h>
#include <dl_internal.h>


 // init 变量
JavaVM *Main::vm = nullptr;
long Main::baseAddress = 0L;
AAssetManager *Main::manager = nullptr;
const char* corePath = "com/answer/launcher/core/manager/Alternative";

void initAssets(JNIEnv *env, jobject obj, jobject assetManager);
void loadLibrary_java(JNIEnv *env, jobject obj, jstring libraryPath);
void unloadLibrary_java(JNIEnv *env, jobject obj,jlong handle);
long findLibBaseAddress_java(JNIEnv *env, jobject obj, jstring library);
void load(JNIEnv *env, jobject obj);
void loadManager(JNIEnv *env, jobject obj);

static JNINativeMethod MethodCalled[] = { 
    {"initAssets", "(Landroid/content/res/AssetManager;)V", (void*)initAssets},
    {"loadLibrary", "(Ljava/lang/String;)J", (void*)loadLibrary_java},
    {"unloadLibrary", "(J)V", (void*) unloadLibrary_java},
    {"findLibBaseAddress", "(Ljava/lang/String;)J", (void*)findLibBaseAddress_java},
    {"loadManager", "()V", (void*) loadManager}
};


JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    Main::vm = vm;
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        LOGE("JNI_VERSION is not 1.6");
        return JNI_ERR;
    }
    
    if(!Utils::registerNativeMethods(env, corePath, MethodCalled, sizeof(MethodCalled) / sizeof(MethodCalled[0]))){
        LOGD("Register Fila");
    }
    
    initFakeDlopen();
    
    return JNI_VERSION_1_6;
}

/*
JNI
*/
void initAssets(JNIEnv *env, jobject obj, jobject assetManager){
    Main::manager = AAssetManager_fromJava(env, assetManager);
    initFakeAssets(env, Main::manager);
}

void loadLibrary_java(JNIEnv *env, jobject obj, jstring libraryPath) {
    Utils::loadLibrary(env, libraryPath);
}

void loadManager(JNIEnv *env, jobject obj){
    initManagers();
};

void unloadLibrary_java(JNIEnv *env, jobject obj,jlong handle) {
    Utils::unloadLibrary((void*)handle);
}

long findLibBaseAddress_java(JNIEnv *env, jobject obj, jstring library) {
    return Main::findLibBaseAddress(jstringToCharArr(env, library));
}

void dumpAdder(void** handle){//整改后的方法,可利用与新版本的符号地址发现并使用
    FILE* fp = fopen("/sdcard/1.txt","w");
    
    auto appPlat = (void**)handle;
    auto raw = &appPlat[2]; 

    fprintf(fp,"AppPlatform : \n");
    
    for(int i = 0; raw[i] && raw[i] != (void*)0xffffffffffffffe8; i++) {
        Dl_info data;
        fprintf(fp,"%p (%s)    :    %p\n", raw[i], dladdr(raw[i], &data) ? data.dli_sname : "(unknown)",((uintptr_t)raw[i] - (uintptr_t)data.dli_fbase));
    }
    
    fclose(fp);
}

void* SetHook(void *handle,uintptr_t address){//函数地址(函数实际地址 = so文件基址 - 函数进程内存地址)
    uintptr_t baseAddr = ((soinfo2*)handle)->base;
    return (void*)(baseAddr + address);
}


void load(JNIEnv *env, jobject obj){
   /* void* handle = dlopen("libminecraftpe.so", RTLD_LAZY);
    if (handle == nullptr) {
        LOGE("open error %s", dlerror());
        return;
    }
    
    LOGD("Minecraft Handle : %p",  handle);
    
    Main::baseAddress = Main::findLibBaseAddress("libminecraftpe.so");
    if (Main::baseAddress <= 0L) {
        LOGE("Main::baseAddress <= 0L");
    }
    
    LOGD("Minecraft Handle : %lx",  Main::baseAddress);
    
    uintptr_t p = FindSignature::findSignatureInModule("libminecraftpe.so", "FF 83 04 D1 FD 7B 0E A9 FD 83 03 91 FC 5F 0F A9 F6 57 10 A9 F4 4F 11 A9 57 D0 3B D5 F3 03 08 AA E8 16 40 F9 A8 83 1F F8 FF FF 02 A9");
    LOGD("SO Handle : %p", p);*/
    
    
   // void* EnchantVT = SetHook(handle,p);
   // dumpAdder((void**)Main::baseAddress);
    
    /*
    LOGD("SO Handle : %s",  Utils::analyzeAppPlatformVTable(p).c_str());
    */
    
}
/*
 * Main Function
 */

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
    ostringstream.clear();
    line.clear();
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
    jmethodID getApplicationMethod = env->GetMethodID(activityThreadClass,
                                                      "getApplication",
                                                      "()Landroid/app/Application;");
    jobject application = env->CallObjectMethod(activityThread, getApplicationMethod);
    return application;
}

void Main::showToast(std::string message) {
    JNIEnv *env = currentEnv();
    jclass toastClass = env->FindClass("android/widget/Toast");
    jmethodID makeTextMethod = env->GetStaticMethodID(toastClass, "makeText",
                                                      "(Landroid/content/Context;Ljava/lang/CharSequence;I)Landroid/widget/Toast;");
    jstring messageStr = env->NewStringUTF(message.c_str());
    jobject toast = env->CallStaticObjectMethod(toastClass, makeTextMethod,
                                                getApplication(), messageStr, 0);
    jmethodID showMethod = env->GetMethodID(toastClass, "show", "()V");
    env->CallVoidMethod(toast, showMethod);
}

std::string Main::toHexString(long value) {
    std::ostringstream ostringstream;
    ostringstream << std::hex << value;
    return ostringstream.str();
}
