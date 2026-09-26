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
`a9809c1` C10 external-storage rules -> `24eb03c` C11 `off.<key>` rule support ->
C12 long-reach inline hook (see the last section).

Two things the baseline could not have known:

- **Inline hooks inside `libminecraftpe.so` are geometrically impossible in the middle of the
  mapping.** Mojang merges every loadable segment into one contiguous 284.2 MiB (`0x11C44000`)
  `r-xp` PT_LOAD, and an AArch64 direct branch reaches only +/-128 MiB, so shadowhook has nowhere
  to put its trampoline island for any function between 128 MiB and 156.2 MiB into that segment.
  `ClientInstance::update` (0x943EC54, 148.3 MiB) lands in that band; 1.21's `GuiData::addMessage`
  (0x5FAEEF0, 95.7 MiB into that build's segment) did not, which is why the old build worked.
  Virtual entry points are patched in the vtable instead (`RttiLookup::patchVtableSlot`), and
  `RttiLookup::islandReachable()` now refuses an inline hook with that explanation rather than
  letting an engine error look like a stale signature. `LongCall` (last section) is what
   finally reaches inside the band.
- **The signature scan is no longer compiled in.** `core/src/main/assets/game-hooks.ini` carries a
  version window plus `vtable.<key> = Class:slot`, `sig.<key> = IDA pattern` and
  `off.<key> = 0xRVA` entries, applied by `NativeCore.applyHookRules()` before `loadManager()`.
  An `off.` beats a `sig.` beats the built-in pattern. No matching rule keeps the built-in data;
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

## Crossing a function between Minecraft builds (issue ②)

The four interesting functions are not exported, the classes are stripped, and two of the three
are not even virtual, so there is no name and no vtable slot to follow. Byte patterns die on every
recompile. What does survive a recompile is *structure*, and this is the recipe that worked —
`build/fpdump.py`, `build/neigh.py`, `build/a64dis.py` (capstone), `build/mcport.py`,
`build/ehframe.py`, all gitignored, all offline, no IDA required (the `.i64` packs for these
binaries are 2.7–3.4 GB and this machine has 1.9 GB free).

1. Start from a build where the pattern still matches. Two old ARM builds in `D:\逆向分析\` do:
   1.21.91-era `libminecraftpe-1291.so`, 1.21.101-era `libminecraftpe-121101.so`.
2. Take the function's *callee multiset* (resolve `b #x` thunks first) and its size, and look for
   the same multiset of *relative* counts in the new build. Counts transfer; addresses do not.
3. Take the function's neighbours: the same source file compiles to the same order, so a window of
   function sizes around the target is a second, independent fingerprint.
4. Where the function does touch literals, they transfer verbatim: `joining_realm_game`,
   `joining_xboxLive_game`, `ServiceProvider` and the self-naming
   `MinecraftGame::_joinMultiplayerGame(...)` telemetry string each have exactly one materialising
   function in 1.26.45.1, and they all agree. That is how `joinMultiplayerGame` was pinned;
   `addMessage` materialises no literal at all, so it rests on steps 2 and 3 plus the fact that
   `sendClientMessage` calls it at the same `+0x118` offset in both builds.
5. Write the result as `off.<key>` inside a version window, and let `build/inicheck.py` prove it
   decodes as an instruction boundary in `.text` before anything runs on a device.

The offsets are in `core/src/main/assets/game-hooks.ini` and are now reachable: `hookGameFunction`
falls through to `LongCall::hook()` whenever `islandReachable()` says no, so a function in the dead
band is hooked by a patch of our own instead of being refused.

## Long-reach inline hooks (`LongCall`, C12)

shadowhook gets from the hooked function to its trampoline island with one AArch64 direct branch,
which reaches +/-128 MiB (`kBranchReach = 1 << 27`), and the header exposes no knob to change that —
only `SHADOWHOOK_ERRNO_HOOK_ISLAND_EXIT/_ENTER/_REWRITE` and the SHARED/UNIQUE/MULTI modes. Inside a
284 MiB mapping that is a hard wall. `cpp/src/main/cpp/util/LongCall.cpp` removes the wall by removing
the direct branch:

- **far placement** (no bridge page in reach): the entry becomes `LDR X16, #8` / `BR X16` /
  `.quad handler`, which reaches anywhere in the address space because the address is data, not an
  offset. The four displaced words are replayed on the bridge page, followed by the same
  `LDR/BR/.quad` pair back to `target + 16`.
- **near placement** (a page was found within +/-128 MiB): the entry is a single aligned word
  `B thunk`, so the patch is one 4-byte store — atomic, with no window in which another core can
  observe half an instruction. Only `word[0]` is displaced; the trampoline replays it and resumes at
  `target + 4`. A `BRK #0x800` keeps the following `LDR` 8-byte aligned, which `LDR (literal)`
  requires.
- **refusals**, each with a reason string that ends up in the log: a target that is not 4-aligned or
  whose 16-byte patch would cross a page boundary; any displaced word that is PC-relative
  (`B`/`BL`/`B.cond`/`CBZ`/`TBZ`/`CBNZ`/`TBNZ`/`ADR`/`ADRP`/`LDR`-literal/`LDRSW`/`PRFM`), a register
  branch, an exception instruction, or a HINT/BTI/PAC op — replaying those at a different address
  would silently change what they compute; a mapping that is **shared**, because writing into it
  would edit the file on disk; a target whose mapping is not executable.
- **store order** in the far case is `[3] [2] [0] [1]`: both halves of the address land before the
  `LDR` that reads them, and the `BR` that ends the prologue goes last. The residual window — a
  thread that was already sitting on the displaced tail when the patch went in — is accepted because
  these hooks are installed while the game is still starting up.

`build/longcall_sim.py` (gitignored) proves the encodings offline against the real bytes: it applies
the same classifier to the four words at each of `sendClientMessage` 0x923A22C, `addMessage`
0x923A5D4, `joinMultiplayerGame` 0x95975D4 and `update` 0x943EC54, then disassembles every word the
engine would emit. All four entries are plain stack setup (`stp x29, x30, [sp, #-0x30]!`,
`str d8, [sp, #-0x50]!`, `sub sp, sp, #0x90`, `stp x29, x30, [sp, #-0x60]!`) — nothing PC-relative,
nothing signed, so none is refused — and both placements decode back to the intended `ldr x16, #8` /
`br x16` / `b #<bridge>` with the literal at an 8-aligned address. 0 problems.

Proven on the device (RMX8899, pid 16609, log `build\verify-183425.log`, chat run
`build\logcat-chat.txt`). All three landed at exactly the pinned RVAs — library base
`0x70f7468000`, so `sendClientMessage` at `0x71006a222c`, `addMessage` at `0x71006a25d4`,
`joinMultiplayerGame` at `0x71009ff5d4` — and all three took the **far** placement: the kernel
never returned a hinted page inside +/-128 MiB (the whole window belongs to the 303 MB mapping),
so the bridges sit at `0x733e2c1000` / `0x733e2c0000` / `0x733e21c000`, roughly 150 GiB above the
library. The near placement is therefore implemented but unexercised; nothing depends on it.

`addMessage` was then driven with real traffic: five messages typed into the in-game chat each
produced `Minecraft Hook sendMessage 祖里§r -Type 1 UUID 465503536 …` from the handler *and*
rendered normally on screen (`build\c12-chat.png`), which means the four displaced words replayed,
the `br x16` back to `target + 16` worked, and the nine-argument signature survived the round trip.
No crash, no tombstone, `ClientInstance::update` still counting frames (693 → 8525), both processes
alive. `sendClientMessage` and `joinMultiplayerGame` are installed but have not been *called* yet:
chat typed in a local world is displayed through `addMessage` without a network send, and the join
path needs an actual multiplayer join. The expectation for those two stays "no handler log until
something calls them", not "broken".

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

## One data set per installation (issue ④, C13–C17)

A version string is not an identity: two different APKs can report the same `versionName`,
and a launcher that hosts more than one Minecraft build needs each one's worlds, packs and
options kept apart. `IOManager` therefore aims the guest's own paths at

```
<launcher dataDir>/mc/<guestPackage>@<versionName>
```

The package is part of the name because Android guarantees one install per package, so
package+version is unique per installation by construction — version alone is not. The slug
keeps `[A-Za-z0-9._+@-]` and folds everything else to `_`; `@` survives on purpose, because
`_` is legal inside a package name and a separator has to be one the two halves cannot
contain.

At this point isolation was still only a redirect table, and that turned out not to hold — see
"The root, not the rewrite" below. While it lasted, the directories the guest received from its
`Context` fields (`mFilesDir`, `mPreferencesDir`, `mDatabasesDir`, `mCacheDir`, …) were copied
verbatim from the launcher by `AlterEnvironment.establishEnv()`, so they never contained the
guest's package name and never matched a rule: they stayed shared on purpose, which kept the
launcher's own preferences and plugin storage where the launcher expects them and meant one
sign-in served every installation. `/lib` is still deliberately not isolated: it names the
launcher's real `nativeLibraryDir`, the libraries this process has already mapped.

### The path that broke the first isolated build

The first build with this table installed migrated the worlds correctly and was still broken:
the game came up with an empty world list. The reason is that the game's content root is not
spelled out of its own package name — it is derived from the copied `Context` directories, so
at runtime it opens

```
/data/data/com.answer.launcher/games/com.mojang/…      ← the launcher's package
```

and no rule keyed on `com.mojang.minecraftpe` can ever match that string. Measured on the
device: the isolated root held `minecraftWorlds/oUTWSHJuu9I=` (3.6 MB, mtimes from the previous
session) while the game wrote a fresh `options.txt` and an *empty* `minecraftWorlds` into the
old location. The fix claims that one subtree by name — `<dataDir>/games`, its
`/data/user/0/` alias and the external `files/games` all redirect into the isolated root —
which is safe for exactly the reason the migration was: `games` is the only name at the
launcher's data-dir root that the launcher itself never writes.

So the rule set is not purely "guest-package paths": it is guest-package paths **plus** the
game's content root. `<legacy dataDir>/games` is renamed into the isolated root the first time
it is created, because that is the one name in the old location the launcher can never have
written itself. A rename, so it is instant and atomic for the whole tree, and it only runs
while the destination is absent.

### The root, not the rewrite (C15, closes issue ④)

C14's rules could not hold, and the second isolated build proved why by measuring both trees in
the same run: the Java side wrote `<iso>/games/…` while `libminecraftpe.so` recreated
`/data/data/com.answer.launcher/games/com.mojang/…` with the same file set, minutes apart. The
redirect lives in `java.io.UnixFileSystem` (`UnixFileSystemHook` → `IOManager.redirectPath`), so
any path the engine opens through libc never reaches it. **String rewriting at the Java `File`
layer cannot isolate a native engine** — it can only isolate the parts of the game that ask the
framework.

So the isolation moved to the one place both sides have to agree on: the root the game is *told*
about. `Alternative.createLoadedApk()` now decides it once, before any LoadedApk exists —

```java
isolationSlug = IOManager.isolationSlug(MINECRAFTPACKAGE, MINECRAFT_VERSION);
guestDataRoot = IOManager.prepareDataRoot(mContext, isolationSlug);
applicationInfo.dataDir = guestRootPath;                       // <launcher dataDir>/mc/<slug>
applicationInfo.deviceProtectedDataDir = …prepareDeviceProtectedRoot(…);
```

and Android 16 derives everything else from it: `LoadedApk.setApplicationInfo()` computes
`mDataDirFile`, `mDeviceProtectedDataDirFile` and `mCredentialProtectedDataDirFile` from the
`ApplicationInfo`, `ActivityThread.getPackageInfoNoCheck()` (`LoadedApk.java:411-416`,
`:3068`) builds the LoadedApk around it, and `ContextImpl`'s seven directory fields come out of
that. The game's content root is a child of the root we set, so Java and native now disagree by
construction — impossible, rather than merely redirected.

Consequences, all of them deliberate:

* `AlterEnvironment.establishEnv()` no longer overwrites the guest's directories with the
  launcher's. It fills a field **only when it is null**, and logs `N already derived from its
  data root, M filled from the host` — if that line ever reads `0 already derived`, the root
  stopped taking effect and the log says so.
* The C14 `games` rules stay in the table as a safety net for anything that still spells the
  launcher's own path, but they are no longer what makes isolation work.
* `IOManager.prepareDataRoot()` **seeds** a new root by copying `xal/`, `app_webview/`,
  `shared_prefs/`, `databases/`, `Flighting/`, `bootstrap_settings.json` and `*.ent` out of the
  shared root, each only while the destination name is absent. Without that, taking a real data
  root would have signed the user out on first launch — the same regression C14 caused by other
  means, and the one the user has now been hit by twice. It is a one-time copy; issue ⑧ replaces
  it with an account the launcher exports itself, at which point "share / don't share" becomes a
  write target instead of a filesystem trick.
* The migration and seeding run before the LoadedApk is built, not inside `enableRedirect()`,
  which happens later in `createApplication()`.

### The host's root has to move too (C17, actually closes issue ④)

C15 was not enough, and it took three measurements on the device to see why. Each one refuted the
model before it:

1. *The activity's cached `File` fields are the leak.* `AlterEnvironment.adoptGuestDirs()` wrote
   all seven before `Activity.onCreate`, and the log confirmed it — while the engine went on
   writing `<launcher dataDir>/games/com.mojang/minecraftpe/options.txt`. The fields are not what
   the game asks.
2. *`ApplicationInfo.dataDir` is the string it asks.* Rewriting that from the activity's
   `ApplicationInfo` measured unchanged: the probe printed
   `activity filesDir=<isolated>/files dataDir=/data/user/0/com.answer.launcher`. A `ContextImpl`
   does not answer `getDataDir()` from the field — it asks the **LoadedApk it was bound to**, which
   derived its three directory `File`s once, in `setApplicationInfo()` (`LoadedApk.java:412-414`,
   read back at `:1355-1365`).
3. *Hook the accessor instead.* An installed `replace` on `ContextImpl#getDataDir()` was never
   reached: ART inlines the three-branch accessor into its callers inside the same class, so entry
   hooks observe no invocation (the sibling `ensurePrivateDirExists` has a real body and does
   fire — that is how the mangling was caught at all).

Bedrock builds its content root as `getDataDir() + "/games/com.mojang"`, and the context it asks
through is the one its `Application` runs on: `activity.getApplication()` is the **launcher's**
`AlterApplication`, bound to the launcher's LoadedApk, so it answered the launcher's real root no
matter what the guest's `ApplicationInfo` claimed. (It also refutes the claim carried over from the
stock `GameActivity` source that the engine is handed `absPath(getFilesDir())` — it is handed
nothing of the sort; the clone in `core/src/main/java/com/google/androidgamesdk/` is not even the
class that runs, it logs different strings.)

So `:game` repoints that LoadedApk. `Alternative.retargetHostLoadedApk()`, called at the end of
`createLoadedApk()` once the roots exist, sets `mCredentialProtectedDataDirFile` and `mDataDirFile`
to the instance's credential-protected root and `mDeviceProtectedDataDirFile` to its
device-protected one. It runs only in `:game`, which exists to serve one installation, so the state
that root holds in that process belongs to that installation; the launcher UI process keeps the real
root, and everything derived before the repoint (the legacy root used for seeding and migration, the
redirect rules) is already resolved into strings. Measured, one run after the last leak: the
instance tree holds a 14797-byte `options.txt` stamped 21:47 while the launcher's copy stayed frozen
at 21:38, and `PackManifestFactoryCache`, `premium_cache/`, `treatments/` and `app_plugin/` were
created inside the instance for the first time. `find <launcher dataDir> -type f -newermt '21:46'
-not -path '*/mc/*'` returns 15 files and not one of them is game content — `app_webview/**`,
`shared_prefs/WebViewChromiumPrefs.xml`, `no_backup/.webview/BrowserMetrics-spare.pma`,
`cache/Crash Reports/ANR Variations/<hash>`, `files/profileInstalled`, `oat/arm64/…vdex`.

That WebView residue is the one part of isolation still not per-installation, and it is not a
path-rewrite problem either: `MinecraftLoader.initializeWebView()` runs against the launcher's
Application context before a guest exists — an ordering that is itself the fix for the
`BIND_EXTERNAL_SERVICE` `SecurityException` — so Chromium captures its data directory before the
instance root is a thing. Making it follow the instance is exactly the question the login-data share
switch answers (`docs/levi-compat-design.md` §6.1): a decision about where the account lives, not
another filesystem trick.

What this buys for the features the user asked for: a per-installation root is exactly the
`VersionManager.Installation.paths()` shape, so ⑤ (import an APK, keep several versions) has
somewhere to put each one, and the same root is where an exported account lands for ⑧.


