#ifndef ALTER_NATIVE_FINDSIGNATURE_H
#define ALTER_NATIVE_FINDSIGNATURE_H

#include <jni.h>
#include <string>
#include <cstdint>
#include <utility>
#include <vector>

struct ModuleInfo {
    uintptr_t base;
    size_t size;
    // All executable mappings of the module. The linker splits a
    // large library like libminecraftpe.so into several r-xp regions, and
    // scanning only the first one loses every function beyond it.
    std::vector<std::pair<uintptr_t, size_t>> regions;
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

    // Scan one mapping of a multi-region module (see ModuleInfo::regions).
    static uintptr_t findSignatureInRegion(const ModuleInfo& info, size_t regionIndex, const char* signature);

    // Regions actually discovered by getModuleInfo, for logging.
    static size_t regionCount(const ModuleInfo& info);
};

#endif //ALTER_NATIVE_FINDSIGNATURE_H

