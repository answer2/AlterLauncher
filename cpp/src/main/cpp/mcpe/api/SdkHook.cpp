#include "SdkHook.h"

#include <android/log.h>
#include <string.h>

#include <map>
#include <mutex>
#include <string>

#include <mcpe/core/ClassCommon.h>
#include <mcpe/core/ClassTree.h>
#include <mcpe/core/GameSDK.h>
#include <memory/HookRules.h>
#include <memory/RttiLookup.h>

namespace mcpe {
namespace {

#define SLOG(PRIO, ...) \
    __android_log_print(PRIO, "Alternative_Core", "SdkHook: " __VA_ARGS__)

std::mutex &registryMutex() {
    static std::mutex m;
    return m;
}

std::map<std::string, SdkHookInfo> &registry() {
    static std::map<std::string, SdkHookInfo> r;
    return r;
}

// The object's vptr, validated the same way ClassCommon::callVirtual validates
// it, because a call-through address is about to be chosen with it.
uintptr_t vptrOf(uintptr_t object) {
    if (object == 0 || !detail::inWritable(object)) return 0;
    uintptr_t vt = 0;
    memcpy(&vt, reinterpret_cast<const void *>(object), sizeof(vt));
    if (vt == 0 || !detail::inGuestLib(vt)) return 0;
    return vt;
}

bool install(const char *key, const std::string &className, size_t slot,
             void *handler, SdkHookInfo *out) {
    const HookRules::Rule *rule = GameSdk::rule();
    std::string cls = className;
    size_t idx = slot;

    // Rule-driven: both the class and the index come from the file, so the same
    // call works on every build that has a matching block.
    if (key != nullptr && className.empty()) {
        if (rule == nullptr) {
            SLOG(ANDROID_LOG_WARN, "no rule loaded, cannot resolve key '%s'", key);
            return false;
        }
        if (!rule->lookupVtable(std::string(key), &cls, &idx)) {
            SLOG(ANDROID_LOG_WARN, "key '%s' is not pinned in the active rule", key);
            return false;
        }
    }
    if (cls.empty()) {
        SLOG(ANDROID_LOG_WARN, "no class for key '%s'", key ? key : "(by slot)");
        return false;
    }

    const std::vector<uintptr_t> &tables = detail::tablesFor(cls.c_str());
    if (tables.empty()) {
        // Not "the class does not exist": it means RTTI could not validate a
        // table for it in THIS process. Options is the standing example — it has
        // no virtual functions at all, so no amount of slot measuring will help.
        SLOG(ANDROID_LOG_WARN,
             "no RTTI vtable for class '%s' (key '%s'): nothing to patch",
             cls.c_str(), key ? key : "(by slot)");
        return false;
    }

    SdkHookInfo info;
    info.key = key ? key : (cls + ":" + std::to_string(idx));
    info.className = cls;
    info.slot = idx;
    info.handler = reinterpret_cast<uintptr_t>(handler);
    info.tablesFound = tables.size();

    for (size_t i = 0; i < tables.size(); i++) {
        uintptr_t header = tables[i];
        // Bounds before anything else: patchVtableSlot() would happily write
        // past the end of a short subobject table.
        size_t count = RttiLookup::slotCountOf(detail::kGuestLib, header);
        if (count <= idx) {
            info.skipped++;
            SLOG(ANDROID_LOG_WARN,
                 "%s: table %zu/%zu has %zu slots, index %zu is out of range - skipped",
                 cls.c_str(), i + 1, tables.size(), count, idx);
            continue;
        }
        uintptr_t current = RttiLookup::slotAt(header, idx);
        if (current == 0 || !detail::inExec(current)) {
            // A pure-virtual slot, or an index that drifted onto data. Patching
            // it would replace something that was never called.
            info.skipped++;
            SLOG(ANDROID_LOG_WARN,
                 "%s: slot %zu of table %zu holds 0x%lx, not a function - skipped",
                 cls.c_str(), idx, i + 1, (unsigned long) current);
            continue;
        }
        uintptr_t original = 0;
        if (!RttiLookup::patchVtableSlot(header, idx,
                                         reinterpret_cast<uintptr_t>(handler),
                                         &original)) {
            info.skipped++;
            SLOG(ANDROID_LOG_WARN, "%s: patchVtableSlot(%zu) failed on table %zu",
                 cls.c_str(), idx, i + 1);
            continue;
        }
        info.tables.push_back(header);
        info.originals.push_back(original);
    }

    if (info.tables.empty()) {
        SLOG(ANDROID_LOG_WARN, "%s: no table could be patched for slot %zu",
             cls.c_str(), idx);
        return false;
    }
    SLOG(ANDROID_LOG_INFO,
         "hooked %s slot %zu -> %p on %zu/%zu table(s)%s",
         cls.c_str(), idx, handler, info.tables.size(), tables.size(),
         info.skipped ? " (some skipped, see warnings above)" : "");
    if (out != nullptr) *out = info;
    return true;
}

}  // namespace

bool hookVirtualRaw(const char *key, void *handler) {
    if (key == nullptr || handler == nullptr) return false;
    SdkHookInfo info;
    {
        std::lock_guard<std::mutex> guard(registryMutex());
        if (registry().count(key)) {
            SLOG(ANDROID_LOG_WARN, "key '%s' is already hooked", key);
            return false;
        }
        if (!install(key, std::string(), 0, handler, &info)) return false;
        registry()[info.key] = info;
    }
    return true;
}

bool hookVirtualIn(const char *className, size_t slot, void *handler, const char *key) {
    if (className == nullptr || handler == nullptr) return false;
    SdkHookInfo info;
    {
        std::lock_guard<std::mutex> guard(registryMutex());
        if (!install(key, std::string(className), slot, handler, &info)) return false;
        registry()[info.key] = info;
    }
    return true;
}

bool unhookVirtual(const char *key) {
    if (key == nullptr) return false;
    std::lock_guard<std::mutex> guard(registryMutex());
    std::map<std::string, SdkHookInfo>::iterator it = registry().find(key);
    if (it == registry().end()) return false;
    size_t done = 0;
    for (size_t i = 0; i < it->second.tables.size(); i++) {
        uintptr_t ignored = 0;
        if (RttiLookup::patchVtableSlot(it->second.tables[i], it->second.slot,
                                        it->second.originals[i], &ignored))
            done++;
    }
    SLOG(ANDROID_LOG_INFO, "unhooked %s: %zu/%zu table(s) restored",
         key, done, it->second.tables.size());
    // Keep the record: the originals are the only way back, and call-through
    // after an unhook must report 0 rather than hand out the handler.
    it->second.tables.clear();
    return done != 0;
}

const SdkHookInfo *hookInfo(const char *key) {
    if (key == nullptr) return nullptr;
    std::lock_guard<std::mutex> guard(registryMutex());
    std::map<std::string, SdkHookInfo>::const_iterator it = registry().find(key);
    return it == registry().end() ? nullptr : &it->second;
}

std::vector<std::string> hookedKeys() {
    std::lock_guard<std::mutex> guard(registryMutex());
    std::vector<std::string> out;
    for (std::map<std::string, SdkHookInfo>::const_iterator it = registry().begin();
         it != registry().end(); ++it) {
        if (it->second.installed()) out.push_back(it->first);
    }
    return out;
}

uintptr_t originalFor(const char *key, uintptr_t object) {
    const SdkHookInfo *info = hookInfo(key);
    if (info == nullptr || !info->installed()) return 0;
    if (info->originals.empty()) return 0;

    uintptr_t vt = vptrOf(object);
    if (vt == 0) return 0;
    for (size_t i = 0; i < info->tables.size(); i++) {
        // tables[] holds header-start; an object carries the address point.
        if (RttiLookup::addressPoint(info->tables[i]) == vt) return info->originals[i];
    }
    return 0;
}

}  // namespace mcpe
