/*
 * alt_mod.h — AlterLauncher native mod interface (`alter-native`).
 *
 * This is the whole contract between a native mod and the launcher's mod host. It is a pure C
 * ABI at the boundary on purpose: Levi's preloader exposes an unsized C++ struct (Mod.hpp), which
 * only works when the mod and the loader agree on a libc++/NDK layout. A C ABI plus an explicit
 * `size` field on every struct stays readable across toolchains.
 *
 * A mod ships exactly one exported symbol, AltGetModRegistration(); the host dlopen()s the
 * library, resolves that symbol, validates size/abi, and drives the lifecycle. Everything else is
 * plain C structs of function pointers.
 *
 * Lifecycle mirrors the launcher's Java plugin states (AlterPlugin / PluginState):
 *     Unloaded --onLoad--> Loaded --onEnable--> Enabled --onDisable--> Loaded --onUnload--> Unloaded
 *
 * See docs/native-mod-protocol.md for the manifest, the per-instance mods/ layout, and the
 * Levi `preload-native` compatibility plan.
 */
#ifndef ALTERLAUNCHER_ALT_MOD_H
#define ALTERLAUNCHER_ALT_MOD_H

#include <stdint.h>

#if defined(__cplusplus)
extern "C" {
#endif

#define ALT_MOD_ABI 1u

/* Visibility helper: only the registration symbol leaves the .so. */
#define ALT_EXPORT __attribute__((visibility("default")))

/*
 * Filled in by the host and handed to onLoad. Read-only for the mod. `size` is
 * sizeof(AltModInfo) so a newer host that appends fields is still safe to hand to an older mod:
 * the mod must not read past its own compiled sizeof, and must check `size` before touching any
 * field added after the one it knows.
 */
typedef struct AltModInfo {
    uint32_t    size;             /* sizeof(AltModInfo) as the host compiled it */
    const char *id;               /* the mod id == its package directory name */
    const char *name;             /* display name from manifest.json */
    const char *author;
    const char *version;
    const char *modRootPath;      /* absolute path of the mod's package directory */
    const char *dataDir;          /* <modRootPath>/data   (host-created, writable) */
    const char *configDir;        /* <modRootPath>/config (host-created, writable) */
    const char *resourceDir;      /* <modRootPath>/resources */
    const char *minecraftVersion; /* the booted guest's version name */
    const char *abi;              /* "arm64-v8a" | "armeabi-v7a" */
} AltModInfo;

/* Implemented by the mod. Same size-gating rule as AltModInfo. */
typedef struct AltMod {
    uint32_t size;                                   /* sizeof(AltMod) */
    uint32_t abi;                                    /* ALT_MOD_ABI the mod was built for */
    int   (*onLoad)(void *self, const AltModInfo *info); /* return 0 to accept, non-zero to fail */
    void  (*onEnable)(void *self);
    void  (*onDisable)(void *self);
    void  (*onUnload)(void *self);
} AltMod;

/* Returned by AltGetModRegistration. */
typedef struct AltModRegistration {
    uint32_t size;      /* sizeof(AltModRegistration) */
    uint32_t abi;       /* ALT_MOD_ABI the host should expect */
    AltMod  *mod;       /* the mod's callbacks; `self` passed back is this pointer */
} AltModRegistration;

/*
 * The one symbol the host looks for. A mod defines this, usually via ALT_REGISTER_MOD below.
 */
ALT_EXPORT const AltModRegistration *AltGetModRegistration(void);

/*
 * Define the mod object and emit the registration symbol.
 *
 *   static int my_load(void *self, const AltModInfo *info) { ... return 0; }
 *   static AltMod g_mod = { sizeof(AltMod), ALT_MOD_ABI, my_load, 0, 0, 0 };
 *   ALT_REGISTER_MOD(&g_mod)
 *
 * Missing onEnable/onDisable/onUnload are tolerated by the host (treated as no-ops), matching how
 * Levi's concepts accept a mod that only implements load().
 */
#define ALT_REGISTER_MOD(mod_ptr)                                                        \
    ALT_EXPORT const AltModRegistration *AltGetModRegistration(void) {                   \
        static AltModRegistration reg = { sizeof(AltModRegistration),                   \
                                          ALT_MOD_ABI, (mod_ptr) };                      \
        return &reg;                                                                      \
    }

#if defined(__cplusplus)
} /* extern "C" */
#endif

#endif /* ALTERLAUNCHER_ALT_MOD_H */
