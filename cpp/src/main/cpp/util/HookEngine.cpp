#include "HookEngine.h"

#include <cstring>
#include <dlfcn.h>

#include <shadowhook/shadowhook.h>
#include <android/android_log.h>

namespace HookEngine {
namespace {

Backend gBackend = kShadowhook;

// GlossHook lives in libpreloader.so and exports a C ABI; resolving it by
// dlsym keeps alternative_core free of a link-time dependency on the
// preloader (the shadowhook prebuilt stays the only hook lib we link).
typedef void GlossInit_t(bool is_init_linker);
typedef void *GlossHook_t(void *sym_addr, void *new_func, void **old_func);
GlossInit_t *g_glossInit = nullptr;
GlossHook_t *g_glossHook = nullptr;

thread_local const char *g_lastError = "";

bool initGloss(bool modsOn) {
    void *handle = dlopen("libpreloader.so", RTLD_NOW | RTLD_GLOBAL);
    if (handle == nullptr) {
        g_lastError = dlerror();
        return false;
    }
    g_glossInit = (GlossInit_t *) dlsym(handle, "GlossInit");
    g_glossHook = (GlossHook_t *) dlsym(handle, "GlossHook");
    if (g_glossInit == nullptr || g_glossHook == nullptr) {
        g_lastError = "libpreloader.so exports no GlossInit/GlossHook";
        return false;
    }
    // First call wins inside GlossHook, so when mods may load we must be
    // the ones to request the linker claim (is_init_linker = true).
    g_glossInit(modsOn);
    return true;
}

}  // namespace

void select(Backend backend) {
    gBackend = backend;
}

Backend selected() {
    return gBackend;
}

bool init(bool modsOn) {
    if (gBackend == kGloss) {
        if (initGloss(modsOn)) {
            LOGI("HookEngine: GlossHook backend ready (linker=%s)",
                 modsOn ? "claimed" : "plain");
            return true;
        }
        LOGE("HookEngine: Gloss backend failed (%s), falling back to shadowhook",
             g_lastError);
        gBackend = kShadowhook;
    }
    if (shadowhook_init(SHADOWHOOK_MODE_UNIQUE, false) != SHADOWHOOK_ERRNO_OK) {
        g_lastError = shadowhook_to_errmsg(shadowhook_get_errno());
        return false;
    }
    return true;
}

void *hookAddr(void *target, void *replace, void **original) {
    if (gBackend == kGloss) {
        void *stub = g_glossHook ? g_glossHook(target, replace, original) : nullptr;
        if (stub == nullptr) {
            g_lastError = "GlossHook refused the target";
        }
        return stub;
    }
    void *stub = shadowhook_hook_sym_addr(target, replace, original);
    if (stub == nullptr) {
        g_lastError = shadowhook_to_errmsg(shadowhook_get_errno());
    }
    return stub;
}

void *hookFuncAddr(void *target, void *replace, void **original) {
    if (gBackend == kGloss) {
        // GlossHook has no sym/func distinction; it hooks the address given.
        return hookAddr(target, replace, original);
    }
    void *stub = shadowhook_hook_func_addr(target, replace, original);
    if (stub == nullptr) {
        g_lastError = shadowhook_to_errmsg(shadowhook_get_errno());
    }
    return stub;
}

const char *lastError() {
    return g_lastError;
}

}  // namespace HookEngine
