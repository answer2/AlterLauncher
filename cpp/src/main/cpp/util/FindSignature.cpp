#include <jni.h>
#include <cstdint>
#include <vector>
#include <string>
#include <sstream>
#include <link.h>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <main.h>
#include <android/android_log.h>
#include <initializer_list>
#include <util/FindSignature.h>



// 获取模块信息
ModuleInfo FindSignature::getModuleInfo(const char* moduleName) {
    LOGD("TEST 开始");
    ModuleInfo info = {0, 0};
    char filename[32];
    sprintf(filename, "/proc/%d/maps", Main::myPid());
    FILE* fp = fopen(filename, "r");
    if (fp) {
        char line[512];
        while (fgets(line, sizeof(line), fp)) {
            if (strstr(line, moduleName) && strstr(line, "r-xp")) {
                uintptr_t start, end;
                if (sscanf(line, "%lx-%lx %*s %*s %*s %*s %*s %*s %*s", &start, &end) == 2) {
                    info.base = start;
                    info.size = end - start;
                    break;
                }
            }
        }
        fclose(fp);
    }

    return info;
}

// 在内存中查找签名
inline uintptr_t FindSig(uintptr_t moduleBase, size_t moduleSize, const std::string& signature) {
    std::vector<uint16_t> pattern;
    for (size_t i = 0; i < signature.size(); i++) {
        if (signature[i] == ' ')
            continue;
        if (signature[i] == '?') {
            pattern.push_back(0xFF00);
            i++;
        } else {
            char buf[3] { signature[i], signature[++i], 0 };
            pattern.push_back((uint16_t)strtoul(buf, nullptr, 16));
        }
    }

    if (pattern.empty()) {
        return moduleBase;
    }

    int patternIdx = 0;
    uintptr_t match = 0;
    for (uintptr_t i = moduleBase; i < moduleBase + moduleSize; i++) {
        uint8_t current = *reinterpret_cast<uint8_t*>(i);
        if (current == (pattern[patternIdx] & 0xFF) || (pattern[patternIdx] & 0xFF00)) {
            if (!match) {
                match = i;
            }
            patternIdx++;
            if (patternIdx == pattern.size()) {
                return match;
            }
        } else {
            if (match) {
                i--;
            }
            match = 0;
            patternIdx = 0;
        }
    }

    return 0;
}

inline uintptr_t FindSigs(uintptr_t moduleBase, size_t moduleSize, const std::vector<std::string>& signatures) {
    uintptr_t ptr = 0;
    for (const auto& sig : signatures) {
        if ((ptr = FindSig(moduleBase, moduleSize, sig))) {
            break;
        }
    }
    return ptr;
}

// 在模块中查找单个签名
uintptr_t FindSignature::findSignatureInModule(const char* moduleName, const char* signature) {
    ModuleInfo info = getModuleInfo(moduleName);
    LOGD("TEST %lu", info.base);
    LOGD("TEST %zu", info.size);
    if (info.base != 0 && info.size != 0) {
        return FindSig(info.base, info.size, signature);
    }
    return 0;
}


uintptr_t FindSignature::findSignatureInModule(ModuleInfo info, const char* signature) {
    if (info.base != 0 && info.size != 0) {
        return FindSig(info.base, info.size, signature);
    }
    return 0;
}

// 在模块中查找多个签名
uintptr_t FindSignature::findSignaturesInModule(const char* moduleName, const std::vector<std::string>& signatures) {
    ModuleInfo info = getModuleInfo(moduleName);
    if (info.base != 0 && info.size != 0) {
        return FindSigs(info.base, info.size, signatures);
    }
    return 0;
}

jlong FindSignature::findSignatureInModule(JNIEnv* env, jstring moduleName, jstring signature){
    const char* moduleNameChars = env->GetStringUTFChars(moduleName, nullptr);
    const char* signatureChars = env->GetStringUTFChars(signature, nullptr);

    jlong result = static_cast<jlong>(findSignatureInModule(moduleNameChars, signatureChars));
    
    env->ReleaseStringUTFChars(moduleName, moduleNameChars);
    env->ReleaseStringUTFChars(signature, signatureChars);

    return result;
}

jlong FindSignature::findSignaturesInModule(JNIEnv* env, jstring moduleName, jobjectArray signatures) {
const char* moduleNameChars = env->GetStringUTFChars(moduleName, nullptr);
    
    jsize sigCount = env->GetArrayLength(signatures);
    std::vector<std::string> sigVector;
    
    for (jsize i = 0; i < sigCount; i++) {
        jstring signature = (jstring)env->GetObjectArrayElement(signatures, i);
        const char* signatureChars = env->GetStringUTFChars(signature, nullptr);
        sigVector.push_back(signatureChars);
        env->ReleaseStringUTFChars(signature, signatureChars);
    }

    jlong result = static_cast<jlong>(FindSignature::findSignaturesInModule(moduleNameChars, sigVector));

    env->ReleaseStringUTFChars(moduleName, moduleNameChars);

    return result;
}

