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



// Set by getModuleInfo: every executable mapping of the module currently being
// resolved. Kept for callers that want to scan one region at a time.
static std::vector<std::pair<uintptr_t, size_t>> g_moduleRegions;

// 获取模块信息
ModuleInfo FindSignature::getModuleInfo(const char* moduleName) {
    ModuleInfo info = {0, 0};
    g_moduleRegions.clear();
    char filename[32];
    sprintf(filename, "/proc/%d/maps", Main::myPid());
    FILE* fp = fopen(filename, "r");
    if (fp) {
        size_t nameLen = strlen(moduleName);
        char line[512];
        while (fgets(line, sizeof(line), fp)) {
            if (!strstr(line, "r-xp")) continue;
            // The module name must terminate the line ("/.../libminecraftpe.so" plus
            // an optional newline). Match only the tail — not strrchr(' ') — because
            // Android install paths contain spaces
            // ("/data/app/~~hash==/com.mojang.minecraftpe-hash==/lib/arm64/..."),
            // which made a space-delimited basename parse miss every mapping.
            // fgets keeps the newline; sscanf("%c") on a consumed buffer is UB-free.
            size_t used = strcspn(line, "\r\n");
            if (used < nameLen + 2) continue;
            if (strncmp(line + used - nameLen, moduleName, nameLen) != 0) continue;
            if (line[used - nameLen - 1] != '/') continue;
            unsigned long start, end;
            if (sscanf(line, "%lx-%lx", &start, &end) == 2 && end > start) {
                if (info.base == 0) {
                    info.base = static_cast<uintptr_t>(start);
                    info.size = static_cast<size_t>(end - start);
                }
                info.regions.emplace_back(static_cast<uintptr_t>(start),
                                          static_cast<size_t>(end - start));
                g_moduleRegions.push_back(info.regions.back());
            }
        }
        fclose(fp);
    }

    return info;
}

// Region-scoped matcher defined below.
static inline uintptr_t FindSig(uintptr_t moduleBase, size_t moduleSize, const std::string& signature);

size_t FindSignature::regionCount(const ModuleInfo& info) {
    return info.regions.size();
}

uintptr_t FindSignature::findSignatureInRegion(const ModuleInfo& info, size_t regionIndex, const char* signature) {
    if (regionIndex >= info.regions.size()) return 0;
    const auto& r = info.regions[regionIndex];
    return FindSig(r.first, r.second, signature);
}

// 在内存中查找签名
static inline uintptr_t FindSig(uintptr_t moduleBase, size_t moduleSize, const std::string& signature) {
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

    // Pass 1 anchors on the first concrete byte: wildcards cannot anchor, so
    // walk candidate offsets instead of letting the running index drift when a
    // wildcard prefix half-matches at unrelated addresses.
    size_t anchor = 0;
    while (anchor < pattern.size() && (pattern[anchor] & 0xFF00)) anchor++;
    if (anchor == pattern.size()) {
        return moduleBase;  // fully wildcarded pattern matches anywhere
    }
    const uint8_t anchorByte = static_cast<uint8_t>(pattern[anchor] & 0xFF);
    const size_t patLen = pattern.size();

    auto scanRegion = [&](uintptr_t base, size_t size) -> uintptr_t {
        if (size < patLen) return 0;
        const uintptr_t last = base + size - patLen;
        for (uintptr_t start = base; start <= last; start++) {
            if (*reinterpret_cast<uint8_t*>(start + anchor) != anchorByte) continue;
            for (size_t j = 0; j < patLen; j++) {
                const uint16_t p = pattern[j];
                if (p & 0xFF00) continue;
                if (*reinterpret_cast<uint8_t*>(start + j) != static_cast<uint8_t>(p & 0xFF)) break;
                if (j == patLen - 1) return start;
            }
        }
        return 0;
    };

    return scanRegion(moduleBase, moduleSize);
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
    if (info.base != 0 && info.size != 0) {
        return FindSig(info.base, info.size, signature);
    }
    return 0;
}


uintptr_t FindSignature::findSignatureInModule(ModuleInfo info, const char* signature) {
    // The linker splits libminecraftpe.so into several r-xp mappings, so
    // info.base/info.size covers only the first one. Walk all of them.
    for (const auto& r : info.regions) {
        uintptr_t hit = FindSig(r.first, r.second, signature);
        if (hit) return hit;
    }
    if (info.base != 0 && info.size != 0) {
        return FindSig(info.base, info.size, signature);
    }
    return 0;
}

// 在模块中查找多个签名
uintptr_t FindSignature::findSignaturesInModule(const char* moduleName, const std::vector<std::string>& signatures) {
    ModuleInfo info = getModuleInfo(moduleName);
    for (const auto& r : info.regions) {
        uintptr_t hit = FindSigs(r.first, r.second, signatures);
        if (hit) return hit;
    }
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

