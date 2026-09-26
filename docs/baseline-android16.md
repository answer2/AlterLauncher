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
`5947105` C8 island reachability pre-check -> `214df13` C9 island verdict on device ->
C10 external-storage rules (see the last section).

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
which needs a real game Activity launch; no longer true — `LoadedAfterHook` drives them and they
are covered by `build/verify.ps1`.

## The external-storage chicken-and-egg (issue ③, fixed in C10)

`IOManager.enableRedirect()` used to guard its external block with

```java
if (Alternative.getManager().mContext.getExternalCacheDir() != null
        && context.getExternalCacheDir() != null) {          // context is the GUEST
```

On API 36 the external dirs have no cached fields — every getter recomputes them from
`getPackageName()` (`ContextImpl.getExternalFilesDirs` :894, `getObbDirs` :912,
`getExternalCacheDirs` :962, `getExternalMediaDirs` :967) — and `getExternalCacheDirs()`
passes `tryCreateInProcess = false`, because cache dirs must go through
`StorageManagerService` for quota tracking. So `ensureExternalDirsExistOrFilter()`
(:3780) asks vold to make `/storage/emulated/0/Android/data/com.mojang.minecraftpe/cache`,
vold answers

```
SecurityException: Invalid mkdirs path: ... does not contain calling package com.answer.launcher
```

the getter returns null, the guard fails — **and the rules that would have made the getter
work are never installed.** The check asked the broken thing whether it was broken, and
`IOManager` logged nothing but `e.printStackTrace()`, so the whole block vanished silently.
The secondary defect was in the body: `external` came from the *guest* context, so even when
it ran the table mapped the guest's `Android/data` path onto itself.

Both sides are now computed without touching a guest external getter: the guest path is
spelled from its package name, the target from the launcher's own context. The bare root goes
in first, since `redirectPath()` takes the first matching prefix and one rule then covers
every subpath. This works without hooking `ContextImpl` at all: the existence check inside
`ensureExternalDirsExistOrFilter()` calls `File.exists()`, which goes through the hooked
`UnixFileSystem.getBooleanAttributes0`, so the guest's cache dir already "exists" (it is
ours), vold is never consulted, and the guest receives a usable `File`.

Measured (RMX8899, MC 1.26.45.1): `redirect rules installed: 19, guest external cache =
/storage/emulated/0/Android/data/com.mojang.minecraftpe/cache, usable=true`, and
`Failed to ensure` / `Invalid mkdirs` occurrences dropped to zero.

`usable=true` is worth understanding, because it says the block was never about filesystem
permissions. Our process holds `MANAGE_EXTERNAL_STORAGE` (appop `allow` for uid 10429), and
MediaProvider's FUSE layer grants that appop full access to `/storage/emulated/0` — it is a
capability check in the FUSE daemon, not a gid: `/proc/<pid>/status` lists only
`1077 3002 3003 9997 20429 50429 99909997`, with no `ext_data_rw`, yet `canWrite()` on the
guest's own path still returns true. What blocked us was purely the SDK's policy gate:
`StorageManagerService`/vold validates that the requested path contains the *calling* package
(`Invalid mkdirs path`), and that check has no idea we are allowed by capability. So the
redirect's real job is to keep the framework from ever making that call.

Data layout: guest external paths map into the launcher's own tree, matching what was already
on the device (`Android/data/com.answer.launcher/cache/crash/*.txt`). The alternative — the
direction the original code implied, guest onto guest — would write into the *real* Minecraft's
`Android/data/com.mojang.minecraftpe`, which collides with the installed retail package
(uid 10428) whenever both are present.

There is no two-tree split in practice: after this change the guest's own external dir still
shows mtime `2026-09-15 21:32` (untouched by any of our runs) while our tree's
`files/games` carries `2026-09-25 23:31` — Java-layer writes land in ours, and the game engine
never touches external storage at all, because its root comes from the redirected internal
`dataDir`.


### What stays, and why it is not a bug

- `E/AppOps: Specified package "com.mojang.minecraftpe" under uid 10429 but it is not` —
  the stack is `Notifier.notifyWakelockAcquisition` → `AppOpsManager.startOpNoThrow`, i.e.
  system_server's *observational* wakelock accounting. The lock is already held; only the
  stat is dropped.
- `W/Minecraft: NO LOG FILE!`, `E/EventsSDK.SQLiteDB: Failed to open database file: (14)`,
  `W/TransportRuntime: Error scheduling event uid 10429`, `W/GoogleApiManager`,
  `W/ServiceBindIntentUtils` — byte-for-byte unchanged across the fix, so they are not caused
  by the external-dir failure. JobScheduler and GMS validate package↔uid in system_server,
  which cannot be satisfied from inside a host process without reporting the launcher's own
  package name — and XAL/Microsoft OAuth depends on the guest name working. Changing
  `LoadedApk.mPackageName` is therefore deliberately off the table; redirecting storage paths
  is the surgical half.

