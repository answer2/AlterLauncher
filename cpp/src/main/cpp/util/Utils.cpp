#include <jni.h>
#include <android/android_log.h>
#include <util/Utils.h>
#include <util/fake_dlfcn.h>
#include <util/stringUtil.h>
#include <dlfcn.h>
#include <string>
#include <vector>
#include <iostream>


// Structure to represent a virtual function
struct VirtualFunction {
    uintptr_t address;
    std::string name;
};


// Function to estimate the vtable address from a known virtual function address
uintptr_t* estimateVTableAddress(uintptr_t knownFuncAddr) {
    return reinterpret_cast<uintptr_t*>(*(reinterpret_cast<uintptr_t*>(knownFuncAddr - sizeof(uintptr_t))));
}

// Function to get addresses of virtual functions
std::vector<VirtualFunction> getVirtualFunctions(uintptr_t* estimatedVTable, int maxFunctions = 100) {
    std::vector<VirtualFunction> functions;
    for (int i = 0; i < maxFunctions; ++i) {
        // 增加检查 estimatedVTable 是否为空
        if (estimatedVTable == nullptr) break;
        
        uintptr_t funcAddr = estimatedVTable[i];
        // 检查 funcAddr 是否为非法地址
        if (funcAddr == 0 || funcAddr == (uintptr_t)-1) break; // Assume end of vtable
        functions.push_back({funcAddr, "Unknown_" + std::to_string(i)});
    }
    return functions;
}

std::string Utils::analyzeAppPlatformVTable( uintptr_t readAssetFileAddr) {
    uintptr_t* estimatedVTable = estimateVTableAddress(readAssetFileAddr);
    //std::vector<VirtualFunction> functions = getVirtualFunctions(estimatedVTable);
    std::string result = "";
    LOGD( "AppPlatform VTable Analysis:");
    LOGD("Estimated vtable address: %lu" ,reinterpret_cast<uintptr_t>(estimatedVTable));
    LOGD("readAssetFile address: %lu", readAssetFileAddr);

    /*
    // Define the known function names based on the AppPlatform class definition
    const char* knownFunctions[] = {
        "~AppPlatform", "getDataUrl", "getImagePath", "loadPNG", "loadTGA", "savePNG",
        "getKeyFromKeyCode", "hideKeyboard", "getKeyboardHeight", "hideMousePointer",
        "showMousePointer", "getPointerFocus", "setPointerFocus", "toggleSimulateTouchWithMouse",
        "captureScreen", "swapBuffers", "getSystemRegion", "getGraphicsVendor",
        "getGraphicsRenderer", "getGraphicsVersion", "getGraphicsExtensions", "pickImage",
        "setSleepEnabled", "getExternalStoragePath", "getInternalStoragePath", "getUserdataPath",
        "showDialog", "createUserInput", "getUserInputStatus", "getUserInput", "_tick",
        "getScreenWidth", "getScreenHeight", "getPixelsPerMillimeter", "updateTextBoxText",
        "isKeyboardVisible", "supportsVibration", "vibrate", "getAssetFileFullPath",
        "readAssetFile", "listAssetFilesIn", "getDateString", "checkLicense",
        "hasBuyButtonWhenInvalidLicense", "uploadPlatformDependentData", "isNetworkEnabled",
        "isPowerVR", "buyGame", "finish", "launchUri", "useMetadataDrivenScreens",
        "useXboxControlHelpers", "useCenteredGUI", "useHeightScaleGUI", "hasIDEProfiler",
        "getPlatformStringVar", "getApplicationId", "getAvailableMemory", "getTotalMemory",
        "getBroadcastAddresses", "getModelName", "getDeviceId", "createUUID",
        "isFirstSnoopLaunch", "hasHardwareInformationChanged", "isTablet", "registerUriListener",
        "unregisterUriListener", "setFullscreenMode", "isNetworkThrottled"
    };

    for (size_t i = 0; i < functions.size() && i < sizeof(knownFunctions)/sizeof(knownFunctions[0]); ++i) {
        result += "Function " + std::to_string(i) + ": 0x" + std::to_string(functions[i].address);
        result += " (" + std::string(knownFunctions[i]) + ")\n";
    }*/

    return result;
}

void* Utils::loadLibrary(JNIEnv *env, jstring libraryPath) {
    void* handle = dlopen(jstringToCharArr(env,libraryPath), RTLD_LAZY);
    if (handle == NULL) {
        // 处理加载失败的情况
        const char* error = dlerror();
        LOGD("LoadLibraryError :%s", error);
        //D Alternative_Core: LoadLibraryError :undefined symbol: Java_com_mojang_minecraftpe_MainActivity_isBrazeEnabled__
    }
    return handle;
}

// 卸载动态链接库
void Utils::unloadLibrary(void* handle) {
    if (handle != NULL) {
        dlclose_ex(handle);
    }
}

int Utils::registerNativeMethods(JNIEnv *env, const char *className, JNINativeMethod *gMethods,
                                 int numMethods) {
    jclass clazz = env->FindClass(className);
    if (clazz == NULL) {
        return JNI_FALSE;
    }
    if (env->RegisterNatives(clazz, gMethods, numMethods) < 0) {
        return JNI_FALSE;
    }
    return JNI_TRUE;
}


jobject Utils::getApplication(JNIEnv *env) {
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

void Utils::showToast(JNIEnv *env, std::string message) {
    jclass toastClass = env->FindClass("android/widget/Toast");
    jmethodID makeTextMethod = env->GetStaticMethodID(toastClass, "makeText",
                                                      "(Landroid/content/Context;Ljava/lang/CharSequence;I)Landroid/widget/Toast;");
    jstring messageStr = env->NewStringUTF(message.c_str());
    jobject toast = env->CallStaticObjectMethod(toastClass, makeTextMethod,
                                                getApplication(env), messageStr, 0);
    jmethodID showMethod = env->GetMethodID(toastClass, "show", "()V");
    env->CallVoidMethod(toast, showMethod);
}

