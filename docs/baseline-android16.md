# Baseline probe — AlterLauncher on Android 16 (RMX8899, API 36, MC 1.26.45.1)

Capture method: `am start -n com.answer.launcher/.core.manager.ProcessIntentManager`
(exported=true, `onStart()` -> `MinecraftLoader.load()`), logcat cleared first.

## Status: this is a historical snapshot, not the current state

Everything below was true on 2026-09-25, before the Android 16 work started. The game now boots,
signs in through Microsoft, renders fullscreen in its own `:game` process and stays playable.
Where each finding ended up:

| Baseline finding | Outcome |
| --- | --- |
| Pine loads fine on API 36 | True, and moot — the engine converged onto AlterCore; `HookProvider` is the only caller. AlterCore 1.1.3 is on Maven Central. |
| `LoadedApk` keeps its public 7-arg ctor | Also true, but no longer used: B3 (`46a88b3`) builds the guest `LoadedApk` with `ActivityThread.getPackageInfoNoCheck` instead of calling the ctor. |
| `makeApplication` returned the real game Application | True; B4 (`552f757`) stopped overwriting `ActivityThread.mInitialApplication`, and `makeApplication(false, AlterInstrumentation.get())` now passes a real Instrumentation, so `callApplicationOnCreate` fires (it is skipped when that argument is null — `LoadedApk.java:1497`). |
| Fatal `SecurityException: BIND_EXTERNAL_SERVICE` | Fixed in C1 (`cd46b29`) by warming WebView synchronously at the top of `MinecraftLoader.load()` instead of posting it. The deeper fix is the guest class loader + guest-owned `Resources` (`exchangeResourcesOfActivity` uses `Alternative.getPackageContext()`, not `getContext()`). |
| `shadowhook init failed` | Root-caused: 1.0.8 looks up the linker mutex as `__dl__ZL10g_dl_mutex`, which Android 16 renamed to `__dl_g_dl_mutex`, so `SHADOWHOOK_ERRNO_INIT_LINKER` (12) was permanent. Fixed by the vendored 2.0.1 prebuilt in C3 (`f5ae4a8`); the device now reports `init-errno: 0, real-init: yes`. |
| `loadManager()` unreached | Reached. Its four byte patterns are all stale for 1.26.45.1, which drove C6/C7/C8 below. |

Current commit chain: `f6e6b43` C6 RTTI vtable resolution -> `e74120a` 16 KB max-page-size ->
`72a57f7` AlterCore 1.1.3 from Central -> `9343664` C7 version-gated hook data ->
`5947105` C8 island reachability pre-check.

Two things the baseline could not have known:

- **Inline hooks inside `libminecraftpe.so` are geometrically impossible in the middle of the
  mapping.** Mojang merges every loadable segment into one ~298 MB `r-xp` PT_LOAD, and an AArch64
  direct branch reaches only +/-128 MiB, so shadowhook has nowhere to put its trampoline island for
  any function between file offsets 128 MB and 170 MB. `ClientInstance::update` (0x943EC54) lands in
  that band; 1.21's `GuiData::addMessage` (0x5faeef0) did not, which is why the old build worked.
  Virtual entry points are patched in the vtable instead (`RttiLookup::patchVtableSlot`), and
  `RttiLookup::islandReachable()` now refuses an inline hook with that explanation rather than
  letting an engine error look like a stale signature.
- **The signature scan is no longer compiled in.** `core/src/main/assets/game-hooks.ini` carries a
  version window plus `vtable.<key> = Class:slot` and `sig.<key> = IDA pattern` entries, applied by
  `NativeCore.applyHookRules()` before `loadManager()`. No matching rule keeps the built-in data;
  a matching rule that omits a key skips that hook and says so.

## What actually works (contradicts the pre-work analysis)

1. **Pine loads and self-recovers on API 36.**
   `libpine.so ... ok` then `W/Pine: JavaVM offset mismatches default offsets, trying a linear search`
   / `Found JavaVM in Runtime at 640`. No Pine-attributable failure anywhere in the run.
   => Engine convergence is a maintainability/architecture goal, NOT the fix for the current crash.

2. **`LoadedApk` still exposes the public 7-arg constructor on this Android 16 build.**
   `LoadedApk constructors = 2`, `constructor[0] parameter count = 1`, `constructor[1] parameter count = 7`,
   `LoadedApk constructor matched: public android.app.LoadedApk(ActivityThread, ApplicationInfo,
   CompatibilityInfo, ClassLoader, boolean, boolean, boolean)`.
   => `createLoadedApkReflectively()` matches today. Confirms AOSP: 36 has exactly 2 ctors,
   the public 7-arg one is intact (DisplayAdjustments/List<ResourcesLoader> are NOT ctor params).

3. **`makeApplication` returned the real game Application.**
   `Minecraft Application = com.zihao_il.MinecraftApplication`, `Application package = com.mojang.minecraftpe`.
   => "one Application per LoadedApk" is not currently the blocking symptom.

## Fatal at baseline (killed the process ~130ms after "initialized successfully")

```
FATAL EXCEPTION: Chrome_ProcessLauncherThread   Process: com.mojang.minecraftpe, PID: 14211
java.lang.SecurityException: BIND_EXTERNAL_SERVICE failed, calling package not owned by calling UID
    at android.app.IActivityManager$Stub$Proxy.bindServiceInstance(IActivityManager.java:6572)
    at android.app.ContextImpl.bindServiceCommon(ContextImpl.java:2282)
    at android.app.ContextImpl.bindIsolatedService(ContextImpl.java:2169)
    at android.content.ContextWrapper.bindIsolatedService(ContextWrapper.java:925)  x2
    at WV.aq.b(chromium-SystemWebViewGoogle6432.aab-stable-792219903:35)  ... WV.yu.run
```
`ActivityManager: Process com.answer.launcher (pid 14211) has died: fg TOP`
`ApplicationExitInfo ... reason=2 (SIGNALED) status=9 description=exit_self_14211_10429`

Trigger is `MinecraftLoader.load()`'s own WebView warm-up (`new WebView(context)` posted to the main
looper, MinecraftLoader.java:51-53). Chromium binds its sandboxed renderer service reporting the
*guest* package, while the calling UID owns only the host package -> AMS rejects.
The guest package reaches process-wide state via `createApplication()` overwriting
`ActivityThread$AppBindData.appInfo`/`info` (Alternative.java:990-1001), which is what
`ActivityThread.currentPackageName()` reads.

## Silent degradation (no crash, feature dead)

4. `D/Alternative_Core: shadowhook init failed.` -> `initFakeAssets()` returns before installing
   `AAssetManager_open/openDir` hooks => asset interception (`AssestPackageManager` / resource-pack
   override) never takes effect. Only log line from that subsystem; no errno surfaced.

5. `W/System.err: at Alternative.initEnv(Alternative.java:1181)` — swallowed `printStackTrace()`
   inside `MinecraftLoader.load()`'s catch. Line 1181 is the `AlterEnvironment.establishEnv(...)` /
   `MinecraftEnv.establishEnv(...)` region.

6. `makeApplication(false, null)` passes a null Instrumentation; on 36 `callApplicationOnCreate`
   only fires when that arg is non-null (LoadedApk.java:1497-1499) => the game Application's
   `onCreate()` is never invoked.

## Unreached this run
`NativeCore.loadManager()` / MC `.so` signature scans are driven from `MainActivity.onCreate`,
which needs a real game Activity launch; not exercised by this probe.
