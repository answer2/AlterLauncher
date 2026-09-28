#include "SigScanner.h"

#include <android/log.h>
#include <map>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

#include <util/FindSignature.h>
#include <util/HookRules.h>

// SigScanner.h explains the policy; this file is its mechanics.
namespace SigScanner {
namespace {

constexpr const char *kGuestLib = "libminecraftpe.so";

struct Cache {
    const HookRules::Rule *rule = nullptr;
    std::map<std::string, uintptr_t> resolved;
    size_t scanned = 0;
    size_t hits = 0;
} cache;
std::mutex g_mutex;

}  // namespace

void init(const HookRules::Rule *rule) {
    std::lock_guard<std::mutex> guard(g_mutex);
    if (cache.rule == rule) return;
    cache.rule = rule;
    cache.resolved.clear();
    cache.scanned = 0;
    cache.hits = 0;
    if (rule != nullptr) {
        __android_log_print(ANDROID_LOG_DEBUG, "Alternative_Core",
                            "SigScanner: armed with rule '%s' (%zu signature key(s))",
                            rule->name.c_str(), rule->signatures.size());
    }
}

uintptr_t resolve(const std::string &key) {
    std::lock_guard<std::mutex> guard(g_mutex);
    if (cache.rule == nullptr) return 0;
    std::map<std::string, uintptr_t>::iterator known = cache.resolved.find(key);
    if (known != cache.resolved.end()) return known->second;

    uintptr_t address = 0;
    const std::string pattern = cache.rule->lookupSignature(key);
    if (!pattern.empty()) {
        // One pass over every executable mapping of the guest. The scan reads
        // live memory (the linker may split the library across regions), so it
        // works whether or not shadowhook could ever patch what it finds.
        ModuleInfo info = FindSignature::getModuleInfo(kGuestLib);
        address = FindSignature::findSignatureInModule(info, pattern.c_str());
        cache.scanned++;
        if (address != 0) {
            cache.hits++;
            __android_log_print(ANDROID_LOG_DEBUG, "Alternative_Core",
                                "SigScanner: %s matched at 0x%lx (base + 0x%llx)", key.c_str(),
                                (unsigned long) address,
                                (unsigned long long) (address - info.base));
        } else {
            __android_log_print(ANDROID_LOG_WARN, "Alternative_Core",
                                "SigScanner: %s did not match in this build", key.c_str());
        }
    }
    cache.resolved.insert(std::make_pair(key, address));
    return address;
}

std::vector<std::string> keys() {
    std::lock_guard<std::mutex> guard(g_mutex);
    std::vector<std::string> out;
    if (cache.rule == nullptr) return out;
    for (std::map<std::string, std::string>::const_iterator it = cache.rule->signatures.begin();
         it != cache.rule->signatures.end(); ++it)
        out.push_back(it->first);
    return out;
}

size_t scannedCount() {
    std::lock_guard<std::mutex> guard(g_mutex);
    return cache.scanned;
}

size_t hitCount() {
    std::lock_guard<std::mutex> guard(g_mutex);
    return cache.hits;
}

}  // namespace SigScanner
