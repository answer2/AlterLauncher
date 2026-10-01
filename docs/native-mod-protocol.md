# Native mod protocol — AlterLauncher (`alter-native`) and Levi compatibility

Status: **protocol + management layer implemented; the native registration/lifecycle engine is
the next milestone and is gated** (see §6). This document is the contract both the launcher's
loader and third-party native mods code against.

There are two ecosystems to serve at once:

- **LeviLaunchroid** mods — `.so` files linked against a `libpreloader.so` that exports a `pl::`
  C++ surface. Loading them *unmodified* means shipping a library with the same SONAME, the same
  exported mangled symbols, and the same struct layouts. That is a large, exacting C++ target.
- **NModAPI** (`github.com/AlternativeTeam/NModAPI`) — a much smaller, C-ABI, three-callback
  protocol documented only (the repo ships no headers).

We define **one first-party protocol (`alter-native`)** that is a clean C ABI with a size-gated
registration struct, and a **compat shim** that recognises Levi's package shape and (once the
preloader exists) its symbol surface.

---

## 1. Levi's ABI, measured (what "compatible" has to match)

From `build/preloader/include/pl/Mod.hpp` and `build/levi-src` (see `docs/levi-compat-design.md`
§4 and the research pass that produced it):

- Mods link `libpreloader.so` (`-fvisibility=hidden`), so every mod records
  `DT_NEEDED libpreloader.so` and leaves `pl::`/`ll::` symbols **undefined**. Our equivalent must
  carry **SONAME `libpreloader.so`** or `dlopen` fails before any mod code runs.
- Entry symbol: `extern "C" PLGetModRegistration()` (unmangled) returning `ModRegistration*`.
- `ModRegistration` (arm64 LP64, 40 bytes, no size/version field):
  ```cpp
  struct ModRegistration {
      void *instance;
      bool (*load)(void *instance, ModContext &ctx);
      bool (*enable)(void *instance, ModContext &ctx);
      bool (*disable)(void *instance, ModContext &ctx);
      bool (*unload)(void *instance, ModContext &ctx);
  };
  ```
- Loader probe order: `PLGetModRegistration` → C `PLMod_Load/Enable/Disable/Unload`
  (`bool(*)(JavaVM*, const PLModInfo*)`, where `PLModInfo` is size-gated: `uint32_t size` +
  10 `const char*`) → legacy `LeviMod_Load`. `dlopen` flags `RTLD_NOW|RTLD_GLOBAL`.
- Package: one directory per mod under a per-version `mods/`, directory name == mod id;
  `manifest.json` with `type:"preload-native"`, `entry` (relative `.so`), `name`, `author`,
  `version`, `icon`, `description`, `minecraft_versions` (array or scalar, `*`/`X`/`>=` matching).
  Enable/disable/order persist in `<modsDir>/mods_config.json`: `[{name, enabled, order}]`.

The full `pl::` export surface a real mod might import (`pl::memory`, `pl::modmenu`, `pl::input`,
`pl::config`, `pl::platform`, `NativeMod::current()`) is **not** enumerated here on purpose — the
exact set must come from `llvm-readelf --dyn-syms` over real mods, and none are on disk yet (§6).

## 2. NModAPI's protocol (documented, no headers)

- Three free C functions, no registration struct:
  `NMod_OnLoad(JavaVM*, JNIEnv*, const char* mcVersion, const char* nmodApiVersion, const char* pathToLibminecraftpe)`,
  `NMod_OnActivityCreate(JNIEnv*, jobject this, jobject savedState)`, `NMod_OnActivityFinish(JNIEnv*, jobject)`.
- Hooking is self-service: the mod links `libsubstrate.so` + `libminecraftpe.so` and calls
  `MSHookFunction` itself. Manifest `nmod_manifest.json` inside an APK/zip; only `armeabi-v7a`
  and `x86` libs are read.

NMod is trivially hostable (three `dlsym` calls, no shared struct), but it is 32-bit-only and
offers none of the lifecycle/menu/config ergonomics. We keep it in mind but do not adopt it.

## 3. `alter-native` — our first-party protocol

Design goals: a **C ABI only at the boundary** (so it survives libc++/NDK churn), a **size-gated**
registration (Levi's mistake was an unsized C++ struct), and the **same names as the Java v2 plugin
API** (`AlterPlugin`/`PluginState`) so a Java and a native mod read identically.

```c
// libaltermod.so exports exactly one symbol:
__attribute__((visibility("default")))
extern "C" const AltModRegistration *AltGetModRegistration(void);

#define ALT_MOD_ABI 1

typedef struct AltModInfo {          // filled by the host, read by the mod
    uint32_t   size;                 // sizeof(AltModInfo) — forward-compat gate
    const char *id;
    const char *name;
    const char *author;
    const char *version;
    const char *modRootPath;         // the package directory
    const char *dataDir;             // <modRootPath>/data
    const char *configDir;           // <modRootPath>/config
    const char *resourceDir;         // <modRootPath>/resources
    const char *minecraftVersion;
    const char *abi;                 // arm64-v8a | armeabi-v7a
} AltModInfo;

typedef struct AltMod {              // implemented by the mod
    uint32_t size;                   // sizeof(AltMod)
    uint32_t abi;                    // ALT_MOD_ABI
    int (*onLoad)(void *self, const AltModInfo *info);   // 0 = ok
    void (*onEnable)(void *self);
    void (*onDisable)(void *self);
    void (*onUnload)(void *self);
} AltMod;

typedef struct AltModRegistration {
    uint32_t size;                   // sizeof(AltModRegistration)
    uint32_t abi;                    // ALT_MOD_ABI
    AltMod  *mod;
} AltModRegistration;
```

- The host `dlopen(RTLD_NOW|RTLD_GLOBAL)` → `dlsym("AltGetModRegistration")` → checks
  `reg->size >= sizeof(...)` and `reg->abi == ALT_MOD_ABI` → `mod->onLoad(mod, &info)`.
- Lifecycle states mirror `PluginState`: `Unloaded → Loaded → Enabled` (disable → Loaded,
  unload → Unloaded).
- The mod gets the game library path and a `JavaVM*` through the info/handle, and hooks through
  **our** shadowhook-based engine (the C6/C12 seams) rather than linking the game's symbols — so a
  mod never needs `libminecraftpe.so` at link time.

## 4. Package + manifest (one parser, two `type`s)

`<instance>/mods/<modId>/manifest.json`:

```json
{ "type": "alter-native" | "preload-native",
  "id": "<dir name>", "name": "...", "author": "...", "version": "1.0.0",
  "entry": "lib<name>.so", "icon": "icon.png", "description": "...",
  "api": 1, "minGame": "1.21.0", "maxGame": "", "enabled": true,
  "minecraft_versions": ["1.21.*"] }
```

- `entry` is sanitised like Levi's: `\`→`/`, no `.`/`..`/absolute, must end `.so`.
- Loose top-level `.so` in `mods/` are wrapped into a generated package (Levi does the same).
- Enable/disable/order persist in `<instance>/mods/mods_config.json` (`[{name, enabled, order}]`),
  the same file shape Levi uses, so a `mods/` tree is portable between the two launchers.
- **Per instance** (task #35): mods live under the instance's own `mods/`, so a mod written for one
  version does not force-load into another.

`NativeModManager` (core) implements discovery, manifest parsing, the config file, and the
enable/disable/order state. It is the management layer for both `type`s.

## 5. What is implemented now

- `NativeMod` (model) + `NativeModManager` (discover `mods/`, parse `manifest.json` for both
  types, read/write `mods_config.json`, enable/disable/reorder, count) — the Java-side contract of
  §3/§4.
- The `.altpack` v2 archive already carries `mods/` per instance (task #36), so a whole instance's
  mods export and import with it.

## 6. Gated next milestone (do not claim done)

Loading a mod's code and running its lifecycle needs native work that is deliberately **not**
stubbed, because a half-wired loader that dlopens a lib and never calls it is worse than none:

1. **`alt_mod_host` in `:cpp`** — a `nativeLoadMod(path, root, info)` that `dlopen`s, probes
   `AltGetModRegistration` (and, later, `PLGetModRegistration`), checks size/abi, and drives
   `onLoad/onEnable/onDisable/onUnload`, returning a structured per-mod result.
2. **The `pl::` compat shim** — a `libpreloader.so`-SONAME library exporting the exact symbol +
   struct surface, so Levi `.so` mods resolve at `dlopen`. **Gated on a readelf survey of ≥2 real
   Levi mods** (none are on disk today): the minimum undefined `pl::`/`ll::` set is what we must
   export, and guessing it produces `undefined symbol` failures at the worst boundary.
3. **Licensing** — `preloader-android` has no license upstream (`docs/levi-compat-design.md` §4.2);
   vendoring vs clean-room is still an open decision.

Milestones: **⑥a** = mods load, register, hook, and toggle from the launcher UI; **⑥b** = the
in-game Mod Menu renders. Do not promise ⑥b before ⑥a runs on device.
