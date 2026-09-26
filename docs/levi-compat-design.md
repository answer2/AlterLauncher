# LeviLaunchroid compatibility — design for issues ⑤–⑧

Scope set by the user: *"参考 leviLaunroid，把版本导入做一下，同时也兼容 leviLaunroid 的 mod，然后给我们的 mod 协议修改和优化，更加现代化 (https://github.com/AlternativeTeam/AlterLauncher-Api)，同时登录的话也可以 leviLaunroid 在宿主层完成，登录数据也可以选择共享选择不共享"*

Four deliverables, one dependency chain:

| # | Issue | Depends on |
|---|---|---|
| ⑤ | APK / version import, multi-instance management | ④ (data root) |
| ⑥ | Load LeviLaunchroid native mods unmodified | ⑤ (per-instance `mods/`) |
| ⑦ | Modernize our own mod protocol (`:api`) | ⑥ (share the package shape) |
| ⑧ | Host-layer Microsoft login + share / don't-share switch | ④ (data root) |

Reference material: `build/levi-src/` (LeviLaunchroid Java/Kotlin), `build/preloader/` (LiteLDev/preloader-android 0.2.2), `build/levi-docs/` (their mod developer guide), `aosp16-src/` (Android 16 framework ground truth). All facts below are read from those sources or measured with the offline RE toolchain in `build/`; nothing is inferred from memory.

---

## 1. The two stacks are not the same kind of launcher

| | LeviLaunchroid | AlterLauncher |
|---|---|---|
| Game code | `System.load("…/libminecraftpe.so")` **into the launcher process**; guest dex never executed | guest `LoadedApk` + `GuestClassLoader` in a **`:game` child process** |
| Anchor install | **required** — `detectGamePackage() ?: throw IllegalStateException("Minecraft not found")` (`GamePackageManager.kt:65,88-99`) | required today (`Alternative.initPackage` reads the installed package), **removable** — we already build a `LoadedApk` from a path (`MultiVersionManager.createPackageContext(Context,int,String apkPath)`) |
| Resources | hand-made `AssetManager` + `addAssetPath` (`GamePackageManager.kt:420-456`) | `LoadedApk` → real `Resources`, swapped into the Activity (`AlterInstrumentation.exchangeResourcesOfActivity`) |
| Activity | their own `MinecraftLoadingActivity` / `MinecraftActivity` | the guest's `com.mojang.minecraftpe.MainActivity`, instantiated from the guest loader behind a launcher proxy |
| Concurrency | one game per process, `GamePackageManager` is a version-keyed singleton | one game per `:game` process; several instances can run in parallel in principle |
| Hooking | GlossHook (`lib/ARM64/libGlossHook.a`, 296 KB) inside `libpreloader.so` | shadowhook 2.0.1 + our own RTTI-slot and LongCall engines (C6/C12) |
| Isolation | pure path redirection: hand-built `ApplicationInfo.dataDir` + `EXTRA_STORAGE_*` intent extras | IOManager string rewrite (C13/C14) — **proven insufficient**, see §2 |

What we copy: the *product shape* (import an APK, one directory per installation, per-installation data, account management in the launcher, a mod directory). What we do not copy: the in-process execution model, the mandatory anchor install, the singleton.

---

## 2. Prerequisite (closes issue ④): give the guest its own data root

C14 (`07556e7`) proved the split-brain: with the redirect table isolated, `<iso>/games/com.mojang/…` is written by the Java side and the legacy `games/com.mojang/…` skeleton is *simultaneously* recreated by the native side. Mechanism: our rules fire in `java.io.UnixFileSystem` (`core/src/main/java/com/answer/launcher/core/hook/UnixFileSystemHook.java:58,60` → `IOManager.redirectPath()`), so `libminecraftpe.so` opening through libc is invisible to them. Minecraft spells its content root from the **process data dir**, which is the launcher's own — that is why the path contains `com.answer.launcher`.

Fix: stop rewriting paths, start telling the truth.

1. Build a guest `ApplicationInfo` whose `dataDir` (and `deviceProtectedDataDir`) is the instance root, **before** any `LoadedApk` exists. On Android 16 `LoadedApk.setApplicationInfo` (`frameworks/base/core/java/android/app/LoadedApk.java:411-416`) derives `mAppDataDir`/`mDataDir` from `applicationInfo`, and `ActivityThread.getPackageInfoNoCheck(ApplicationInfo, CompatibilityInfo)` (`:3068`) is the single entry — so a correct `ApplicationInfo` yields a correct `ContextImpl` with no reflection at all. (This is the same seam B3 already uses; `MultiVersionManager.createLoadedApkViaActivityThread` at `:398` already fixes `FLAG_HAS_CODE` at `:403-408`.)
2. `AlterEnvironment.establishEnv(Context app, Context plugin)` (`core/src/main/java/com/answer/launcher/core/env/AlterEnvironment.java:72-97`) currently copies `mDatabasesDir, mPreferencesDir, mFilesDir, mCratesDir, mNoBackupFilesDir, mCacheDir, mCodeCacheDir` **verbatim from the launcher's ContextImpl**. Those become the instance root's own dirs (the copy stays, the values change).
3. `IOManager` keeps its rules only for the *external* roots (which Android derives from the uid, not from `dataDir`) and for `/lib`; the internal rules collapse to identity. `enableRedirect(Context)` without a slug remains the "shared" mode.

`primaryCpuAbi` must be set on the synthesized `ApplicationInfo` (a bare `nativeLibraryDir` is ignored by nativeloader), together with `sourceDir`, `publicSourceDir`, `splitSourceDirs`, `packageName`, `versionName`, `versionCode`, `flags`, `enabled`, `targetSdkVersion`, `uid`/`groupId` (copied from the host — we run as the host uid), and `className`/`processName` as needed. Levi's `createFakeApplicationInfo` (`MinecraftLauncher.java:52-79`) is the checklist for the minimum viable set; we need strictly more of it because our ApplicationInfo reaches the real framework.

---

## 3. ⑤ Instance store and APK import

Levi's layout (measured from `LauncherStorage.java`):

```
Android/media/<launcherPkg>/minecraft/<profileId>/
    base.apk.levi  splits/*.apk.levi  mods/  metadata/  data/
    internal/games/com.mojang   external/games/com.mojang
    <cacheDir>/minecraft/<profileId>          (always separate)
    <filesDir>/minecraft_libs/<profileId>/<abi>/
```

Ours — same idea, our names, and one level of indirection that Levi lacks (a profile is not a version: two profiles can point at one APK):

```
files/instances/<instanceId>/            instanceId = <guest package name>[@<versionName>][#<n>]
                                                             (C13 slug rule; the *whole* package
                                                             name, never an abbreviation, so two
                                                             forks of the same version can never
                                                             land in one directory)
    instance.json                        { id, label, sourceApk, versionName, versionCode,
                                           abi, createdAt, isolation, account, enabledMods[] }
    base.apk  splits/*.apk               the imported archive, renamed, never modified
    lib/<abi>/*.so                       extracted once, read-only (see 16 KB note below)
    data/                                the guest's dataDir  → ApplicationInfo.dataDir
    mods/<modId>/{manifest.json, *.so}   Levi-shaped (§4)
    logs/
files/instances/_shared/                 the "don't isolate" target (login, prefs, packs)
```

### 3.1 Naming and identity (decided 2026-09-26)

Two names exist per instance and they are not the same thing:

| | what it is | default | collision policy |
|---|---|---|---|
| `id` | the filesystem + `dataDir` identity; the slug C13/C15 isolate under | `<package>@<versionName>` | **cannot** collide: the importer appends `#2`, `#3`, … until the directory is free. The package name is always spelled in full — an id that is only a version name would put two forks of `1.26.45.1` in one tree. |
| `label` | what the UI shows and what the user types | `versionName` (e.g. `1.26.45.1`) | **checked at creation**: if another instance already carries this label, the create/import dialog says so and offers a distinct label (`1.26.45.1 (2)`). A duplicate label never silently reuses an id. |

That answers the question "what if two imports have the same version name": the *label* may repeat only after the user confirms it; the *id* never repeats, because the id is `package@version#n` and the directory is claimed before the copy starts. Renaming a label later touches only `instance.json` — the id, and therefore the data, does not move.

`IOManager.isolationSlug(package, versionName)` (`core/src/main/java/com/answer/launcher/core/manager/IOManager.java`) stays the sanitizer for the *derived* default id; the importer owns the uniqueness loop, so the slug function must not be the only place a `#n` can appear.

Import flow: SAF `OpenDocument` → verify it is a Minecraft APK (`getPackageArchiveInfo(path, GET_ACTIVITIES|GET_PROVIDERS)` and `packageName == com.mojang.minecraftpe`; Levi checks only the version name — we check the package too) → confirm the label (default `versionName`, rejected if taken) and the resulting id → copy to `base.apk` → extract `lib/<abi>/` → write `instance.json` → offer "share login with this instance?".

Two things Levi does that we should **not**:
- `setReadOnly()` on extracted `.so` files plus a `.extraction_manifest` token cache — we keep a manifest but compare on `(path, size, mtime)` like they do; the token in theirs is `NativeImageGuard.TOKEN`, which we do not have.
- **16 KB page alignment**: their `app/src/main/cpp/CMakeLists.txt` sets `-Wl,-z,max-page-size=16384`. Our `:cpp` must be checked (and re-check the *imported* `.so` set: an unaligned guest library fails to load on 16 KB devices, which is exactly the class of failure `NativeImageGuard` exists to paper over).

Verification: `build/verify.ps1` gains an expectation that the guest's `ContextImpl` dirs and `dataDir` all start with the instance root, and that no `games/com.mojang` skeleton reappears under the launcher's own data dir.

---

## 4. ⑥ Running LeviLaunchroid mods unmodified

### 4.1 What the ABI actually is

`preloader-android` builds `add_library(preloader SHARED)` → **`libpreloader.so`**, compiled with `-fvisibility=hidden`, so a mod's only compile-time dependency is its headers and the symbols it leaves *undefined*. Measured surface (from `include/pl/Mod.hpp`):

```cpp
inline constexpr const char *ModRegistrationSymbol = "PLGetModRegistration";
struct ModRegistration {
    void *instance{};
    LifecycleFunction load{}, enable{}, disable{}, unload{};   // bool (*)(void *instance, ModContext &context)
};
PL_EXPORT ::pl::mod::ModRegistration *PLGetModRegistration();  // emitted by PL_REGISTER_MOD(TYPE, INSTANCE_EXPR)
```

plus the `pl::` classes a mod calls: `ll::mod::NativeMod::current()` (id/name/author/version/entry/library/modDir/dataDir/configDir/resourceDir/manifestPath/state + `getJavaVM()`, `getLogger()`), `pl::modmenu::{ModuleBuilder,ButtonBuilder,registerImage,submitDrawCommands,DrawCommand}`, `pl::config::ConfigFile<T>` (Boost.PFR + magic_enum), `pl::memory::{hookFunction,resolveSignature,resolveVtableFunction,patchBytes}`, `pl::input::*`.

So "compatible with Levi mods" means, precisely: **ship a `libpreloader.so` that exports the same mangled symbols with the same class layouts and the same behaviour at those call sites**. A mod is dlopened, `PLGetModRegistration` is called, `load(instance, ctx)` runs, and every `pl::` call it makes resolves into our library. Anything less and it fails at dlopen with `undefined symbol: _ZN2pl3mod10NativeMod7currentEv`-style errors, which is a hard, unhelpful boundary.

### 4.2 Licensing is the first blocker, and it is real

Measured on 2026-09-26 from the GitHub API:

| repo | license | note |
|---|---|---|
| `LiteLDev/LeviLaunchroid` | **Apache-2.0** | its Java (import, storage, auth, `XalExporter`) and its `docs/` + `examples/full-cpp-mod` are copyable with attribution + NOTICE |
| `LiteLDev/preloader-android` | **NONE** | no `LICENSE`, no `README` (raw fetch 404), root contains only `.gitignore`, `CMakeLists.txt`, `include/`, `lib/`, `src/`. GitHub reports `license: null`. |
| `RaphiMC/minecraftauth` | **LGPL-3.0** | has `LICENSE`; usable as a separate module if we ship it as its own replaceable artifact + license notice |

"No license" means all rights reserved by default — we may not link or vendor `preloader-android` into a distributed APK, and being a *submodule* of an Apache-2.0 repo does not extend that licence to it (it is a separate work with its own history). This has to be resolved, not worked around silently. Three routes, in order of preference:

- **A. Ask for a license.** The omission looks accidental (the repo has no README either, and it exists only to be pinned by Levi). A one-line MIT/Apache-2.0 addition from LiteLDev unblocks everything below. Worth opening upstream before writing code.
- **B. Clean-room the documented surface.** Levi's own `docs/` (Apache-2.0, mirrored in `build/levi-docs/`: `api_mod.md`, `api_types-and-macros.md`, `api_hook.md`, `api_signature.md`, `api_patch.md`, `api_config.md`, `api_input.md`, `api_mod-menu.md`) specifies the mod-facing API in prose and examples. We implement that specification ourselves, exporting the same symbol names. The struct we must match exactly is small and is *behaviour*, not code: `ModRegistration{ void* instance; fn load; fn enable; fn disable; fn unload; }` with `bool (*)(void*, ModContext&)` — 5 pointers, 40 bytes.
- **C. Do not claim Levi compatibility.** Ship only our own v2 protocol (§5). Cheapest, but it is the feature the user asked for, so it is the last resort.

**Scoping exercise that makes B tractable** (do this before writing any code): take real Levi mods and list their undefined `pl::`/`ll::` symbols — `llvm-readelf --dyn-syms lib<mod>.so | grep 'UND _Z'`. That yields the exact minimum surface a real mod needs, which is almost certainly a small fraction of their 60 KB Mod-Menu implementation. Until we have a sample mod in hand, B's cost is a guess.

**Decision (2026-09-26, user):** proceed as if `preloader-android` were Apache-2.0 like its parent — route A is treated as settled, so we may vendor and link it, and the licence no longer gates M4. The engineering consequences we take on from that:

- Ship the text of the Apache-2.0 licence in `LICENSES/preloader-android.txt`, list the vendored path in `NOTICE`, and keep the upstream copyright header on every imported file (§7 of the licence: unmodified files keep their headers — and files we edit must carry a prominent notice of the change, §4(b)).
- Add `LiteLDev/preloader-android` (and `LiteLDev/LeviLaunchroid`, and `RaphiMC/minecraftauth` with its LGPL-3.0 terms) to the attribution screen in the launcher's About page.
- Still open the upstream issue asking them to state a licence. It costs one message, and if they answer "keep it closed" we are the ones who have to un-vendor it — which is why this stays in writing here rather than only in a commit message.

### 4.3 If we build on their source (route A)

`preloader-android` is 675 KB of C++ with FetchContent dependencies (nlohmann_json 3.11.3, boost_pfr 1.88.0, magic_enum 0.9.7, fmt 11.2.0, lunasvg 3.5.0) and a vendored `libGlossHook.a` (296 KB, ARM64 + ARM). Building it as our own module gives exact symbol and layout compatibility for free, and Levi mods keep working when they update.

- New Gradle module `:preloader`, pinned tag `0.2.2` (what Levi ships today) behind a variable so we can move to `0.2.3`.
- GlossHook is theirs and self-contained; we do not merge it with shadowhook. Our own hooks (C6 RTTI slots, C12 LongCall) stay where they are; the preloader's hooks are only `PauseScreen` ctor/dtor, `HudScreen` ctor/dtor and `ClientInstance::isShowingMenu` (`src/pl/GameHooks.cpp`) — **zero overlap with our four game hooks**, so both engines can coexist in one process.
- The preloader's internals are hidden, so the host talks to it over **its JNI contract** (24 `Java_*` symbols, all under `org.levimc.launcher.core.mods.*` and `…minecraft.MinecraftRuntimePreparer`). To reuse it we must provide those Java classes with the same FQNs (a `:levicompat` Java source set), or patch their symbol prefixes. Same-FQN is cheaper and keeps the mods' own `getJavaVM()` paths behaving.
- `MinecraftRuntimePreparer.nativeSetupRuntime(String modsPath)` is a no-op in both 0.2.2 and main (it only logs) — do not rely on it; `ModManager.nativeLoadMod(String,String,Mod)` is the real entry.

### 4.4 Route B: signature rules we adopt either way

Their rules file (`resources/preloader/preloader_signature_rules_source.json`, revision 3) is `{schemaVersion, revision, rules:[{min,max,sigs:{pauseMenuDtorSig,pauseMenuOpenSig,hudScreenDtorSig,hudScreenOpenSig,isShowingMenuSig},isShowingMenuVtableIndex}]}`, first match wins, digit-run version compare. Our C7 `game-hooks.ini` (`core/src/main/assets/game-hooks.ini`, parsed by `cpp/src/main/cpp/util/HookRules.cpp`) already implements the same semantics and the *identical* pattern syntax (`?`-wildcarded hex, which `HookRules::parse_pattern` accepts).

Measured on our exact build (`build/levisigs.py` against `build/libminecraftpe-1.26.45.1.so`, MC 1.26.45.1 → rule #1, `min=1.26.40.00`):

```
pauseMenuDtorSig   0xA012CFC   (1 hit)
pauseMenuOpenSig   0xA012E88   (1 hit)
hudScreenDtorSig   0xA094F14   (1 hit)
hudScreenOpenSig   0xA0A181C   (1 hit)
isShowingMenuVtableIndex 150  -> vtable 0x11E3B2C8 + 16 + 8*150 -> 0x944EEA4
```

All four unique, and slot 25 of the same table is our device-proven `ClientInstance::update` — their vtable numbering and ours agree exactly. Consequence: we can key our own rules file on their names, and their live-updated JSON can be dropped in as an additional rule source for the four hooks we do not own. Caveat to remember: their scanner runs on the loaded image, ours on file bytes — safe only because every relocated ADRP/ADD word is fully wildcarded in their patterns.

### 4.5 Mod menu

`ModMenuBridge.cpp` is 62 KB of their UI (on-screen menu, buttons, HUD editor, image registry, draw commands, config schema rendering). Adopting the preloader gives us that UI as a side effect, but it draws with their assumptions about the render loop. Milestone split: ⑥a = mods load, register, hook, and can be enabled/disabled from the *launcher* UI; ⑥b = the in-game Mod Menu renders. Do not promise ⑥b before ⑥a runs on device.

---

## 5. ⑦ Our own mod protocol, modernized

Current state, read from the sources:

- Package = a loose `.dex`/`.apk` in `files/plugin/` (matched by `file.getName().contains(".dex")`, so `my.dex.bak` loads) or a loose `.so` in `files/nativePlugin/` (`System.load`, `SplashScreenHook.java:163-177`) — no manifest, no id, no version, no enable flag, no per-plugin directory, no ordering.
- Handshake: `PluginManager.loadPlugin()` (`:87-112`) makes a `DexClassLoader` per file whose parent is **core's own classloader**, then reflects `AlternativeApi.init(LauncherInfo)` **from the plugin's copy of the class**. `LauncherInfo.init` is filled with `plugin.getName()` (`:99`), and `AlternativeApi.init` does `Class.forName(mLauncherInfo.getInit(), …)` (`AlternativeApi.java:19`) — i.e. it looks up a class named `foo.dex`, which always throws, is swallowed at `:27-29`, returns a null `PluginInfo`, and `infos.put(pluginInfo.getName(), …)` (`:106`) NPEs into the outer catch. **The Java plugin path is dead as shipped.**
- Because the API class exists once per plugin loader, `instanceof` cannot work, which is why `checkIsPluginInit` matches interfaces *by name* (`AlternativeApi.java:58-73`) — a workaround for a structural mistake.
- `AlternativeApi.addCallback` (`:75-86`) reaches into core by string reflection (`Class.forName("…MinecraftManager")`), so the "API" has no compile-time contract with its implementation.

Proposal (v2, and the parts that stay for compatibility):

1. **One shared API classloader.** `:api` is loaded once by the launcher; plugins link against it as `compileOnly` and resolve it through their `DexClassLoader` parent. That deletes the name-matching hack and makes `instanceof`, generics and default methods usable.
2. **Manifest, not filename.** `assets/alter-plugin.json` inside the APK (or a sidecar `.json` for a bare `.dex`): `{id, name, version, author, api: 2, minGame, maxGame, entry, side: "game"|"launcher", enabled}`. Same *shape* as Levi's `manifest.json` (id = directory name, `minecraft_versions` wildcards) so one mental model serves both ecosystems.
3. **Lifecycle.** `interface AlterPlugin { void onLoad(PluginContext ctx); default void onEnable() {} default void onDisable() {} default void onUnload() {} }` — mirroring `PL_REGISTER_MOD`'s load/enable/disable/unload so a native and a Java plugin have the same states (`Unloaded/Loaded/Enabled`, exactly what `pl::mod::NativeMod::State` exposes).
4. **`PluginContext` instead of `LauncherInfo`.** Capability-scoped: `instance()` (dirs, version, guest classloader), `events()` (typed game events: activity created, tick, chat message — the hooks we already own in C6/C11/C12 are the natural event sources), `hooks()` (AlterCore facade, per-hook try/catch + fail-fast already built in `HookProvider`), `log()`, `config()` (a JSON file in the plugin's own dir, mirroring `pl::config::ConfigFile`), `resources()` (the plugin's own `AssetManager`).
5. **Load report.** Every load attempt yields a structured result (installed/failed/skipped + reason) that the UI shows — the same honesty rule we applied to `HookProvider.stats()` and to the `skip=` declarations in C7. No more silent `e.printStackTrace()`.
6. **Compat shim.** Keep `AlternativeApi.init(LauncherInfo)` with its exact signature so an existing plugin still resolves; it delegates to v2 and logs a deprecation. Fix the `init`-field bug by passing the real entry class name.
7. Publish from this repo to the `AlterLauncher-Api` mirror with a version catalog entry, so plugin authors get `dev.answer.alterlauncher:api:x.y.z` instead of a hand-copied jar.

---

## 6. ⑧ Login in the host process, shareable or not

Today the Microsoft/XAL flow runs **inside `:game`** (the guest's own WebView profile at `app_webview/`, its state in `xal/` (8 numbered files), `shared_prefs/com.microsoft.xal.crypto.xml`, and the identity file `games/com.mojang/minecraftpe/ud1995949509.dat`). Evidence that this coupling is fragile: while tidying for C14 I moved the legacy `games/` tree, `ud*.dat` (7,320 B) went with it, the session dropped, and 市场 came back as **E-0020** until the file was restored. E-0020 is a *profile/sign-in* failure, not a network one — the store hosts (`client.discovery.minecraft-services.net`, `cdn.layout.mktpl.minecraft-services.net`, `marketplace.minecraft.net`) all complete TLS from both device and PC, and signing in again fixed the marketplace.

### 6.1 How Levi actually does it (read from `MsftAuthManager.java`, `MsftAccountStore.java`, `XalExporter.java`)

They do **not** delegate login to the guest's XAL SDK and they do not capture the game's own login files. The launcher implements the whole Microsoft chain itself and then **writes the XAL SDK's on-disk contract**, so the game's vanilla SDK finds a signed-in state and skips the browser:

1. **OAuth in the launcher**, via the `RaphiMC/minecraftauth` library (`MsftAuthManager.java:14-29`). Two flows: auth-code through a custom-tab `buildAuthorizeUrl` (`:76-90`, `display=touch&prompt=select_account`) and **device code** (`:104-199`, poll tuning `MIN_POLL=1000ms`, `slow_down +5000ms`, `MAX_RETRY_DELAY=10000ms`). Client id = `MsaConstants.BEDROCK_ANDROID_TITLE_ID`, scope = `SCOPE_TITLE_AUTH`, environment = `MsaEnvironment.LIVE` (`:60-74`) — i.e. the *game's own* registered application config, which is what makes the tokens consumable by the game. No Xbox app, no system browser dependency.
2. **Token chain** after the MSA token (`:238-264`): XBL user token → XSTS (`getXboxLiveXstsToken:264`) → XBL profile DOI (gamertag, `xuid`, avatar) → **Minecraft certificate chain** → PlayFab XSTS + Realms XSTS best-effort (log-only on failure). The whole state is canonicalised with `BedrockAuthManager.toJson(authManager)` (`:266`) — one resumable blob holding `msaToken{access,refresh,expire}`, `xblUserToken`, the three XSTS tokens, the cert chain, `deviceId` and the `deviceKeyPair`.
3. **Account store**: `files/xal/Xal.Accounts.json`, a plain Gson list (no encryption — we must add some), fields `id, msUserId, xboxGamertag, minecraftUsername, xuid, xboxAvatarUrl, lastUpdated, active, serializedAuthManager`; first account auto-active; `setActive` is a flag flip + rewrite + re-export (`MsftAccountStore.java:89,121-128`).
4. **The export** (`XalExporter.java:73-157`), into `getFilesDir()/xal`, with `b64User = Base64.URL_SAFE|NO_PADDING(msUserId)` and title id **`1739947436`** hardcoded at `:81`:
   - `Xal.Production.RETAIL.DeviceIdentity.json` — `{Id:"{deviceId}", Key:"Serialized to SharedPreferences"}`
   - `Xal.1739947436.Production.Default.json` — `{default:<msaUserId>}` ← **this single file is the account switch**
   - `Xal.1739947436.Production.Msa.<b64User>.json` — `{user_id, refresh_token, foci:"", access_tokens:[{access_token:"t="+token, xal_expires, scopes:"service::user.auth.xboxlive.com::mbi_ssl"}]}`
   - `Xal.1739947436.Production.RETAIL.User.<b64User>.json` — `{deviceId, tokens:[≤4 envelopes]}`, one per relying party: `http://xboxlive.com` (XSTS), `https://b980a380.minecraft.playfabapi.com/`, `https://pocket.realms.minecraft.net/`, and a `Utoken` for `http://auth.xboxlive.com` (`:127-132`). Envelope fields: `MsaUserId, HasSignInDisplayClaims:true, IdentityType, Environment:"Production", Sandbox:"RETAIL", TokenType:"JWT", RelyingParty, TokenData{Token, NotAfter, IssueInstant = NotAfter − 8h (synthetic), ClientAttested:false, DisplayClaims.xui[0]={uhs,gtg,xid,…}}` (`:162-205`).
   - SharedPreferences `org.levimc.xal.crypto` — `{id:"{deviceId}", public, private}` = the device keypair, base64, **cleartext** (`:142-157`).
   - They write **no** `user.config.json`, no `ud*.dat`, no `<id>.ent` — the SDK regenerates those from XAL state on first boot. That matches our own observation (`ud1995949509.dat`, 7,320 B, appeared during the user's sign-in and is *derived* state), so identity material does **not** need to be copied around, only re-exported.
5. **Refresh**: `BedrockAuthManager.fromJson(http, GAME_VERSION, json)` then `getMinecraftCertificateChain().getUpToDate()` (`MsftAuthManager.java:201-210`) — transparent MSA refresh and re-mint, with 4-attempt backoff (`:212-224`). Their `GAME_VERSION` is pinned to `"1.21.110"` (`:54`); we would pin it per instance from the imported APK.
6. **Bug to not copy**: `clearXalData()` (`XalExporter.java:49-55`) recursively deletes `filesDir/xal`, which is also where `Xal.Accounts.json` lives (`MsftAccountStore.java:52`) — losing the active account silently destroys every saved account.

### 6.2 What the game actually stores (measured on device, 2026-09-26)

The scheme above is Levi's, and Levi's file names do **not** match what our guest
writes. Measured in `/data/user/0/com.answer.launcher/xal/`: eight entries with numeric
names (`13687287496199607557` 2565 B, `1734634999945796391` 33 B, `23267674985628`,
`7162984379564848478`, `Skew` 19 B, `default_lock` 0 B, `default_user`,
`Xal.1734634999945796391.Production`), plus `shared_prefs/com.microsoft.xal.crypto.xml`
(1561 B) and `games/com.mojang/ud1995949509.dat` (7319 B).

Two facts came out of that list.

**The names are hashes, and the hash is FNV-1 64-bit.** `libminecraftpe.so` carries the
key grammar as format strings — `Xal.%llu.%s.Default` (0x23ccfb4),
`Xal.%llu.%s.Msa.%s` (0x23f089a), `Xal.%llu.%s.%s.User.%s` (0x2387b62),
`Xal.%llu.%s.%s.T` (0x24a38e5), `Xal.%s.%s.DeviceIdentity` (0x262c5f9),
`Xal.%s.%s.D` (0x257aeb2), `Xal.%s.Msa.Foci.1` (0x257adb2) — and the Android platform
layer turns a key into a file name by hashing it with 64-bit FNV-1 (basis
`0xCBF29CE484222325`, prime `0x100000001B3`, **multiply-then-xor**, not the FNV-1a
variant), printed as an unsigned decimal. One exact hit confirms it:

```
fnv164("Xal.1739947436.Production.Default") == 1734634999945796391
```

which is the device file whose content is `{"default":"07cc73f690e1e574"}`. So the game's
XAL title id is **1739947436** — the same value Levi hardcodes at `XalExporter.java:81` —
and *not* the `ms-xal-0000000048183522` (= 1209513250) in our manifest: the redirect
scheme carries the Azure application id, the storage keys carry the XAL title id, and
confusing the two writes files the game will never look for. `Skew` is stored under its
literal key, so only namespaced keys are hashed. The implementation of the naming lives
in `auth/src/main/java/com/answer/launcher/auth/XalStore.java`; `build/xalhash.py`,
`build/xalnames.py` and `build/xalinvert.py` are the probes that found it.

**The contents are the schema §6.1 describes.** `13687287496199607557` opens with
`{"deviceId":"{85745666-…}","tokens":[{"MsaUserId":"07cc73f690e1e574",
"HasSignInDisplayClaims":true,"IdentityType":"MRSI","Environment":"Production",
"Sandbox":"RETAIL","TokenType":"JWT","RelyingParty":"http://xboxlive.com",
"TokenData":{"Token":"eyJ0eXAiOiJKV3ci…"` — Levi's `…RETAIL.User.<user>.json` shape
exactly. So the export is writable, and the device keypair has a known home
(`com.microsoft.xal.crypto.xml`, named after the SDK's own package, which answers the
"launcher-derived prefs name" guess in §6.2 below: it is not launcher-derived).

**What is still open, and it is the hard part.** Only the `Default` key has been matched.
The other seven names are not reproduced by any key built from `1739947436` +
`07cc73f690e1e574` + `RETAIL` (`build/xalhash2.py` enumerates the guesses and shows the
misses), so at least one `%s` segment is something else — most likely the *web* account id
(a GUID) or Levi's `Base64.URL_SAFE(msUserId)` rather than the local id, since
`XalUserGetLocalId` and `XalUserGetWebAccountId` are separate accessors in the exported
API. And one entry is only 14 digits (`23267674985628`), far too small to be a 64-bit
hash, so not everything in that directory is named this way. `build/xalstore.sh` dumps the
names with sizes and first bytes and is ready for the next device session. Until every
name is accounted for, an exported token could land under a name the game never looks for
— a silent failure, which is the kind this launcher has already been bitten by.

### 6.3 Our design

Same mechanism, adapted to a separate `:game` process and to §2's real data root:

- **Login lives in the launcher process**, in a plain WebView Activity (we already intercept the callback path: `AlterInstrumentation.consumeExtras` handles `com.microsoft.xal.browser.BrowserLaunchActivity$ShowUrlType`, and `hook/XboxHook.java` exists). Prefer the **device-code flow** first — it needs no redirect-uri registration and works on a phone without a custom tab contract.
- **Vendor `RaphiMC/minecraftauth`** (or port the four request/response shapes) so we own the chain instead of screen-scraping the game's SDK. Its endpoint literals and the title-id client config must be read from that library — not verifiable offline here. **License check required** before it goes into the APK.
- **Account store** `files/accounts/Xal.Accounts.json`, one record per account, `serializedAuthManager` encrypted (Android Keystore-backed) rather than cleartext.
- **Export, don't copy.** At launch, `:game` receives the chosen `accountKey` over the existing AIDL bridge (`GameBridgeService`/`GameBridgeClient` already carry plugin state both ways) and the launcher writes the five artifacts above **into the instance's own data root** — `<instance>/files/xal/…` plus the `<instance>/shared_prefs/<prefsName>.xml` keypair — before the Application is created. Because §2 makes the guest's `filesDir`/`shared_prefs` real paths under the instance root, the vanilla XAL SDK reads them with no hooking at all.
- **The share / don't-share switch is then just a write target**: `isolated` → write into `<instance>/files/xal`; `shared` → all instances' `files/xal` is the same directory (`files/instances/_shared/xal`), so one login serves every instance; `none` → write nothing, the game shows 登录 and 市场 fails with E-0020, which is now a correct and explainable state rather than an accident.
- **Prefs name is an open question**: Levi's `org.levimc.xal.crypto` is launcher-namespaced, so either their native layer redirects the SDK's prefs name or the SDK derives it from the host package. In our case the host package *is* the launcher, so the name our guest's SDK will ask for is `com.answer.launcher`-derived — measure it by signing in once and diffing `shared_prefs/`, then write to exactly that name.
- **Feedback loop**: Levi has none (blind export). We add the cheap version — after launch, read whether the guest created `ud*.dat` and whether 市场 loads; if the guest fell back to the login screen, mark the account `rejected` in the store and show it in the UI.

---

## 7. Order of work

1. **M1 — data root (§2).** Small, unblocks everything, and closes issue ④ honestly. Verify: no legacy `games/` skeleton, world list present, marketplace still works.
2. **M2 — ⑤ import + instances.** Verify: import a second APK, launch it, see two independent instance trees.
3. **M3 — ⑧ host login + share switch.** Verify: sign in once in the launcher, launch two instances, one shared / one isolated, both reach 市场 without E-0020.
4. **M4 — ⑥ Levi mod compatibility.** Gated on §4.2: open the license question upstream (route A) and in parallel run the readelf scoping exercise on a real mod to size route B. Verify once unblocked: `PLGetModRegistration` resolves, `load()` runs, its `PauseScreen`/`HudScreen` signatures hit (measured addresses in §4.4), and the mod's own log line appears in `:game`.
5. **M5 — ⑦ API v2 + compat shim.** Verify: one v1 plugin still loads through the shim; one v2 sample plugin gets an event from the C12 chat hook.

Each milestone is one commit (`C15`…) with the device measurement in its message, per the existing convention. M1 and M2 need no upstream answer; M3 needs only the LGPL-3.0 compliance chore (separate module + license notice); M4 is the one that can stall on someone else.

## 8. Open questions

1. **`preloader-android` has no license** (§4.2) — blocks shipping their source. Ask LiteLDev upstream; Levi itself is Apache-2.0, so the gap looks accidental. Fallback is a clean-room implementation scoped by the undefined-symbol survey of real mods.
2. `RaphiMC/minecraftauth` is **LGPL-3.0** — resolved, but with a chore: ship it as its own replaceable module, include the license text and a notice, and do not static-merge it into `:core`.
3. The exact XAL prefs name our guest's SDK reads (§6.2) — one device session to measure.
4. Do we keep any anchor install? Recommendation: no — §2/§3 make a path-only guest ApplicationInfo possible, and that is the one place we can be strictly better than Levi.
5. In-game Mod Menu (⑥b) in scope, or launcher-side enable/disable enough for now?
6. Instance storage location: private `files/instances` (safe, invisible, deleted on uninstall) vs `Android/media/<pkg>` (Levi's choice — survives uninstall, browsable by users who want to grab their worlds). Recommendation: `files/instances` + an export/import action, since ⑧'s account store must not be world-readable.
7. We have no Levi mod sample on disk. Everything in §4.4/§4.5 is sized from their headers and docs; the real cost of route B is unknown until a `.levipack` or a `full-cpp-mod` directory is inspected.
