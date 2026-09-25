# Baseline probe — AlterLauncher on Android 16 (RMX8899, API 36, MC 1.26.45.1)

Capture method: `am start -n com.answer.launcher/.core.manager.ProcessIntentManager`
(exported=true, `onStart()` -> `MinecraftLoader.load()`), logcat cleared first.

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

## Fatal (kills the process ~130ms after "initialized successfully")

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
