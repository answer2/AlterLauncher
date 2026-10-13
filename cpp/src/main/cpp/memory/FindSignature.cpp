#include <jni.h>
#include <cstdint>
#include <cctype>
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
#include <memory/FindSignature.h>



// 获取模块信息
ModuleInfo FindSignature::getModuleInfo(const char* moduleName) {
    ModuleInfo info = {0, 0};
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
            }
        }
        fclose(fp);
    }

    return info;
}

// Region-scoped matcher defined below.
size_t FindSignature::regionCount(const ModuleInfo& info) {
    return info.regions.size();
}

// Parse "AA BB ?? C" into one entry per byte: 0x0000-0x00FF is a concrete byte,
// 0xFF00 is a wildcard. Rejects anything else instead of guessing:
//
//   - a token must be "?", "??" or exactly two hex digits. A trailing half-byte
//     used to be silently turned into a real byte by strtoul("C") == 0x0C, so a
//     typo in game-hooks.ini became a pattern that matched something else.
//   - an empty / all-whitespace pattern used to return `moduleBase` — i.e. the
//     library's own base address, which every caller then treated as a function
//     address and called or patched. Fail closed instead.
static bool parsePattern(const char* signature, std::vector<uint16_t>* pattern) {
    pattern->clear();
    if (signature == nullptr) return false;
    const std::string text(signature);
    size_t i = 0;
    while (i < text.size()) {
        while (i < text.size() && std::isspace(static_cast<unsigned char>(text[i]))) i++;
        if (i >= text.size()) break;
        if (text[i] == '?') {
            pattern->push_back(0xFF00);
            i++;
            if (i < text.size() && text[i] == '?') i++;
            if (i < text.size() && !std::isspace(static_cast<unsigned char>(text[i]))) return false;
            continue;
        }
        if (i + 1 >= text.size()) return false;
        char hi = text[i], lo = text[i + 1];
        auto isHex = [](char c) {
            return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
        };
        if (!isHex(hi) || !isHex(lo)) return false;
        char buf[3] = { hi, lo, 0 };
        pattern->push_back(static_cast<uint16_t>(strtoul(buf, nullptr, 16)));
        i += 2;
        if (i < text.size() && !std::isspace(static_cast<unsigned char>(text[i]))) return false;
    }
    return !pattern->empty();
}

// Append every match in [base, base+size) to *hits, stopping once it holds
// maxHits entries. Only 4-byte-aligned starts count: Mojang merged .rodata and
// .eh_frame into the same r-xp PT_LOAD as .text (1.26.45.1: one 297 MB segment
// covering 0..0x11c43220, with .text at 0x6121060..0x11b8ff34 and .rodata at
// 0x225fc40), so an unaligned hit inside read-only data is both possible and
// indistinguishable from a real entry by the caller's usual inExec() check.
static void FindSigHits(uintptr_t base, size_t size, const std::vector<uint16_t>& pattern,
                        std::vector<uintptr_t>* hits, size_t maxHits) {
    const size_t patLen = pattern.size();
    if (size < patLen) return;
    // Anchor on the first concrete byte so the C library's SIMD memchr skips the
    // candidate offsets instead of a scalar per-byte walk over the whole mapping
    // (that walk dominated startup).
    size_t anchor = 0;
    while (anchor < patLen && (pattern[anchor] & 0xFF00)) anchor++;
    if (anchor == patLen) return;  // fully wildcarded: matches everywhere, so nowhere usefully
    const uint8_t anchorByte = static_cast<uint8_t>(pattern[anchor] & 0xFF);
    const uintptr_t regionEnd = base + size;
    uintptr_t search = base + anchor;
    const uintptr_t anchorLast = regionEnd - patLen + anchor;
    while (search <= anchorLast && hits->size() < maxHits) {
        const size_t rem = static_cast<size_t>(anchorLast - search) + 1;
        const void* hit = memchr(reinterpret_cast<const void*>(search), anchorByte, rem);
        if (hit == nullptr) break;
        const uintptr_t start = reinterpret_cast<uintptr_t>(hit) - anchor;
        search = reinterpret_cast<uintptr_t>(hit) + 1;
        if ((start & 0x3) != 0) continue;
        bool matched = true;
        for (size_t j = 0; j < patLen; j++) {
            const uint16_t p = pattern[j];
            if (p & 0xFF00) continue;
            if (*reinterpret_cast<uint8_t*>(start + j) != static_cast<uint8_t>(p & 0xFF)) {
                matched = false;
                break;
            }
        }
        if (matched) hits->push_back(start);
    }
}

// Exactly one match across the whole module, or nothing. Uniqueness used to be a
// property of the offline generator alone (build/make_sig.py only emits a
// pattern that matches once in every build given), which made it an assumption
// about a build the runtime never re-checked; on an unsampled build a second
// match silently became "the function" and was then called. Two hits is cheap to
// detect — the scan already walks every candidate — so it is now fail-closed.
static uintptr_t uniqueMatch(const std::vector<uintptr_t>& hits, const char* what) {
    if (hits.empty()) return 0;
    if (hits.size() > 1) {
        LOGW("signature %s matched %zu times (%p, %p) - refusing to pick one",
             what, hits.size(), reinterpret_cast<void*>(hits[0]),
             reinterpret_cast<void*>(hits[1]));
        return 0;
    }
    return hits[0];
}

uintptr_t FindSignature::findSignatureInRegion(const ModuleInfo& info, size_t regionIndex, const char* signature) {
    if (regionIndex >= info.regions.size()) return 0;
    std::vector<uint16_t> pattern;
    if (!parsePattern(signature, &pattern)) {
        LOGW("region scan: pattern '%s' is malformed or empty", signature ? signature : "(null)");
        return 0;
    }
    const auto& r = info.regions[regionIndex];
    std::vector<uintptr_t> hits;
    FindSigHits(r.first, r.second, pattern, &hits, 2);
    return uniqueMatch(hits, "in-region pattern");
}

// 在模块中查找单个签名
uintptr_t FindSignature::findSignatureInModule(const char* moduleName, const char* signature) {
    ModuleInfo info = getModuleInfo(moduleName);
    return findSignatureInModule(info, signature);
}


uintptr_t FindSignature::findSignatureInModule(ModuleInfo info, const char* signature) {
    std::vector<uint16_t> pattern;
    if (!parsePattern(signature, &pattern)) {
        LOGW("signature '%s' is malformed or empty: nothing to scan for",
             signature ? signature : "(null)");
        return 0;
    }
    // The linker splits libminecraftpe.so into several r-xp mappings; a pattern
    // must be unique across ALL of them, not per region.
    std::vector<uintptr_t> hits;
    for (const auto& r : info.regions) FindSigHits(r.first, r.second, pattern, &hits, 2);
    return uniqueMatch(hits, signature);
}

// 在模块中查找多个签名
uintptr_t FindSignature::findSignaturesInModule(const char* moduleName, const std::vector<std::string>& signatures) {
    ModuleInfo info = getModuleInfo(moduleName);
    // First pattern that resolves to exactly one address wins. An ambiguous one
    // is skipped rather than trusted: it says nothing about the alternatives.
    for (const auto& sig : signatures) {
        uintptr_t ptr = findSignatureInModule(info, sig.c_str());
        if (ptr != 0) return ptr;
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

