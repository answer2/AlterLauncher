#ifndef ALTER_NATIVE_UTILS_H
#define ALTER_NATIVE_UTILS_H

#include <jni.h>
#include <string>



class Utils {
public:
    Utils() = delete;

    static int registerNativeMethods(JNIEnv *env, const char *className, JNINativeMethod *gMethods, int numMethods);

    static void* loadLibrary(JNIEnv *env, jstring libraryPath);

    static void unloadLibrary(void* handle);

    static jobject getApplication(JNIEnv *env);

    static void showToast(JNIEnv *env, std::string message);
    
    static std::string analyzeAppPlatformVTable( uintptr_t readAssetFileAddr);
};

#endif //ALTER_NATIVE_UTILS_H

