#include <stdio.h>
#include <jni.h>
#include <string>
#include <iostream>
#include <android/android_log.h>
#include <shadowhook/shadowhook.h>



JNIEnv* Jnienv_;

const char* redirectPath(JNIEnv* env, const char* path) {
    // 转换 const char* 到 JNI jstring
    jstring jPath = env->NewStringUTF(path);

    // 获取 IOManager 类
    jclass ioManagerClass = env->FindClass("com/answer/launcher/core/manager/IOManager");
    if (ioManagerClass == nullptr) {
        env->DeleteLocalRef(jPath);
        return nullptr;
    }

    // 获取 IOManager.get() 方法 ID
    jmethodID getMethodID = env->GetStaticMethodID(ioManagerClass, "get", "()Lcom/answer/launcher/core/manager/IOManager;");
    if (getMethodID == nullptr) {
        env->DeleteLocalRef(ioManagerClass);
        env->DeleteLocalRef(jPath);
        return nullptr;
    }

    // 调用 IOManager.get() 获取实例
    jobject ioManagerInstance = env->CallStaticObjectMethod(ioManagerClass, getMethodID);
    if (ioManagerInstance == nullptr) {
        env->DeleteLocalRef(ioManagerClass);
        env->DeleteLocalRef(jPath);
        return nullptr;
    }

    // 获取 redirectPath 方法 ID
    jmethodID redirectPathMethodID = env->GetMethodID(ioManagerClass, "redirectPath", "(Ljava/lang/String;)Ljava/lang/String;");
    if (redirectPathMethodID == nullptr) {
        env->DeleteLocalRef(ioManagerInstance);
        env->DeleteLocalRef(ioManagerClass);
        env->DeleteLocalRef(jPath);
        return nullptr;
    }

    // 调用 redirectPath 方法
    jstring resultJString = (jstring) env->CallObjectMethod(ioManagerInstance, redirectPathMethodID, jPath);

    // 清理本地引用
    env->DeleteLocalRef(ioManagerInstance);
    env->DeleteLocalRef(ioManagerClass);
    env->DeleteLocalRef(jPath);

    if (resultJString == nullptr) {
        return nullptr;
    }

    // 将 jstring 转换为 const char*
    //
    // The pointer we hand back must outlive the JNI local frame, so we copy
    // into a thread-owned buffer instead of returning the GetStringUTFChars
    // pointer after releasing it (the previous code read freed memory).
    // Callers (fopen_hook) use the result synchronously before the next
    // redirect on the same thread, which is what makes a thread_local buffer
    // safe here: the C hook path never re-enters redirectPath while a pointer
    // is still in use, because the redirected path is consumed by the real
    // fopen before it returns.
    const char* utf = env->GetStringUTFChars(resultJString, nullptr);
    if (utf == nullptr) {
        env->DeleteLocalRef(resultJString);
        return nullptr;
    }

    static thread_local std::string redirected;
    redirected.assign(utf);

    env->ReleaseStringUTFChars(resultJString, utf);
    env->DeleteLocalRef(resultJString);

    return redirected.c_str();
}



typedef FILE* (*fopen_t)(const char* filename, const char* mode);

fopen_t fopen_real;

FILE* fopen_hook(const char* filename, const char* mode) {
    LOGD("OpenFile : %s", filename);

    // 你可以在这里调用你的重定向函数
    const char* redirected_path = redirectPath(Jnienv_, filename);
    // A failed redirect returns nullptr; passing that to the real fopen would
    // be a null-deref inside libc, so fall back to the original path.
    if (redirected_path == nullptr) {
        return fopen_real(filename, mode);
    }
    return fopen_real(redirected_path, mode);
}

void initFakeFile(JNIEnv *env) {
    Jnienv_ = env;
    LOGI("initFakeFile 加载成功");

    // Hook fopen
    shadowhook_hook_sym_addr((void*)fopen, (void*)fopen_hook, (void**)&fopen_real);
}
