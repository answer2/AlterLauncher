#ifndef ALTER_NATIVE_FINDSIGNATURE_H
#define ALTER_NATIVE_FINDSIGNATURE_H

#include <jni.h>
#include <string>
#include <cstdint>
#include <vector>

struct ModuleInfo {
    uintptr_t base;
    size_t size;
};


class FindSignature {
public:
    FindSignature() = delete;

    static ModuleInfo getModuleInfo(const char* moduleName);
    
    //static uintptr_t findSignature(uintptr_t moduleBase, size_t moduleSize, const std::string& signature);

    static uintptr_t findSignatureInModule(const char* moduleName, const char* signature);

    static uintptr_t findSignaturesInModule(const char* moduleName, const std::vector<std::string>& signatures);

    static jlong findSignatureInModule(JNIEnv* env, jstring moduleName, jstring signature);
    
    static jlong findSignaturesInModule(JNIEnv* env, jstring moduleName, jobjectArray signatures);

    static uintptr_t findSignatureInModule(ModuleInfo info, const char *signature);
};

#endif //ALTER_NATIVE_FINDSIGNATURE_H

