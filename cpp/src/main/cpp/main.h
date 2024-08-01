#ifndef ALTER_NATIVE_MAIN_H
#define ALTER_NATIVE_MAIN_H

#include <jni.h>
#include <string>
#include <fstream>
#include <sstream>
#include <android/asset_manager_jni.h>



class Main {
public:
    Main() = delete;

    static JavaVM *vm;

    static long baseAddress;
    
    static AAssetManager *manager;

    static JNIEnv *currentEnv();

    static int myPid();

    static long findLibBaseAddress(std::string libName);

    static jobject getApplication();

    static void showToast(std::string message);

    static std::string toHexString(long value);

    static inline void *absoluteAddress(long offset) {
        return reinterpret_cast<void *>(Main::baseAddress + offset);
    }
};



#endif //ALTER_NATIVE_MAIN_H

