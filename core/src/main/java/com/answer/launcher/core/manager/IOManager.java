package com.answer.launcher.core.manager;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.text.TextUtils;
import android.util.Log;
import java.io.File;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.answer.launcher.core.LauncherConstants;

/**
 * @Author AnswerDev
 * @Date 2024/07/08 23:21
 */
public class IOManager {
    private static IOManager sIOManager = new IOManager();
    private Map<String, String> mRedirectMap = new LinkedHashMap<>();

    private static final String TAG = "IOManager";

    private static final Map<String, Map<String, String>> sCachePackageRedirect = new HashMap<>();

    /**
     * Per-installation data sets live under this directory in the launcher's own
     * data dir, one subdirectory per guest installation.
     */
    private static final String ISOLATION_DIR = "mc";

    /**
     * Top-level entries a new installation is seeded with, so that taking a data root
     * of its own does not cost the user a sign-in. {@code xal} is the Microsoft auth
     * state, {@code app_webview} the profile that carries its cookies,
     * {@code shared_prefs} holds the XAL device keypair, and {@code Flighting} plus the
     * {@code .ent} files are the licence proof.
     */
    static final String[] SEEDED_NAMES = {
            "xal", "app_webview", "shared_prefs", "databases", "Flighting",
            "bootstrap_settings.json"};

    static final String[] SEEDED_SUFFIXES = {".ent"};

    /**
     * Version isolation mode. When ON (default), each guest version keeps its own
     * {@code games/com.mojang} subtree — separate worlds, separate packs. When OFF,
     * the subtree is a symlink into one shared location so every version reads the
     * same worlds and packs. Stored as a plain file under the isolation root so the
     * {@code :game} process can read it before any UI exists.
     */
    private static final String ISOLATION_MODE_FILE = ".version-isolation";
    private static final String ISOLATION_SHARED_DIR = "shared";

    public static boolean versionIsolated(Context host) {
        String text = readText(new File(IdentityStore.instancesDir(host), ISOLATION_MODE_FILE));
        return text == null || !"0".equals(text.trim());
    }

    /**
     * Flip the isolation mode. This only writes the flag; the actual symlink/copy
     * migration happens the next time {@link #prepareDataRoot} runs for each instance.
     */
    public static void setVersionIsolated(Context host, boolean isolated) {
        File dir = IdentityStore.instancesDir(host);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        writeText(new File(dir, ISOLATION_MODE_FILE), isolated ? "1" : "0");
        Log.i(TAG, "version isolation = " + (isolated ? "on" : "off"));
    }

    /** The canonical shared games/com.mojang root used when isolation is off. */
    private static File sharedGamesRoot(Context host) {
        return new File(new File(IdentityStore.instancesDir(host), ISOLATION_SHARED_DIR), "games");
    }

    private static String readText(File f) {
        if (!f.isFile()) return null;
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[(int) Math.min(f.length(), 64)];
            int n = in.read(buf);
            return n <= 0 ? null : new String(buf, 0, n, "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    private static void writeText(File f, String s) {
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
            out.write(s.getBytes("UTF-8"));
        } catch (Throwable t) {
            Log.w(TAG, "cannot write " + f + ": " + t);
        }
    }

    public static IOManager get() {
        return sIOManager;
    }

    public void addRedirect(String origPath, String redirectPath) {
        if (TextUtils.isEmpty(origPath) || TextUtils.isEmpty(redirectPath) || mRedirectMap.get(origPath) != null)
            return;
        mRedirectMap.put(origPath, redirectPath);
        File redirectFile = new File(redirectPath);
        if (!redirectFile.exists()) {
            redirectFile.mkdirs();
        }
    }

    public String redirectPath(String path) {
        if (TextUtils.isEmpty(path))
            return path;
        for (String orig : mRedirectMap.keySet()) {
            if (path.startsWith(orig)) {
                path = path.replace(orig, Objects.requireNonNull(mRedirectMap.get(orig)));
                break;
            }
        }
        return path;
    }

    public File redirectPath(File path) {
        if (path == null)
            return null;
        String pathStr = path.getAbsolutePath();
        return new File(redirectPath(pathStr));
    }

    public String redirectPath(String path, Map<String, String> rule) {
        
        if (TextUtils.isEmpty(path))
            return path;
        for (String orig : rule.keySet()) {
            if (path.startsWith(orig)) {
                path = path.replace(orig, Objects.requireNonNull(rule.get(orig)));
                break;
            }
        }
        return path;
    }

    public File redirectPath(File path, Map<String, String> rule) {
        if (path == null)
            return null;
        String pathStr = path.getAbsolutePath();
        return new File(redirectPath(pathStr, rule));
    }

    /**
     * Builds the redirect table with one data set per guest installation.
     *
     * <p>The isolated root is <em>not</em> decided here: {@code Alternative.createLoadedApk()}
     * has already handed it to the guest's {@code ApplicationInfo.dataDir} by the time
     * this runs, so the table follows that decision instead of competing with it. What
     * is left for the table are the paths a root cannot move — the external storage
     * directories, which the framework derives from the calling package name at call
     * time and never from {@code dataDir}.
     */
    public void enableRedirect(Context context, String versionName) {
        Alternative manager = Alternative.getManager();
        installRedirectRules(context, manager.getIsolationSlug(), manager.getGuestDataRoot());
    }

    /**
     * Names one installation. Public because the data root is decided earlier than the
     * redirect table, and both have to agree — a version string alone is not an
     * identity, since the same version can be installed more than once, while Android
     * guarantees one install per package, so package+version is unique per installation
     * by construction.
     */
    public static String isolationSlug(String guestPackage, String versionName) {
        String raw = guestPackage + "@" + (TextUtils.isEmpty(versionName) ? "unknown" : versionName);
        // '@' survives because it is the separator that makes the name unambiguous —
        // '_' is legal inside a package name, so a slug built from package + version
        // with only underscores could in principle be produced by two different
        // pairs. Android's own paths carry '~' and '=', so '@' is not a character a
        // mounted filesystem is allowed to reject.
        return raw.replaceAll("[^A-Za-z0-9._+@-]", "_");
    }

    /**
     * Creates the internal data root one installation owns and brings across the state
     * it must not lose on the way. Returns the launcher's own directory when isolation
     * is off (no slug) or when the new root cannot be created, so a failure degrades to
     * the shared layout rather than to a crash.
     *
     * <p>This is the fix for what C13/C14 measured: rewriting paths in
     * {@code java.io.UnixFileSystem} cannot isolate a native engine, because
     * {@code libminecraftpe.so} opens its files through libc and never asks us. The
     * isolation therefore moved up to the root the game is <em>told</em> about, and
     * Android 16 derives the real {@code ContextImpl} directories from it
     * ({@code LoadedApk.setApplicationInfo()}), so the Java and native sides cannot
     * disagree any more — there is nothing left to redirect.
     */
    public static File prepareDataRoot(Context host, String slug) {
        if (slug == null) {
            return host.getDataDir();
        }
        File root = isolate(host.getDataDir(), slug);
        if (!root.exists() && !root.mkdirs()) {
            Log.w(TAG, "cannot create isolated root " + root + ", using the shared one");
            return host.getDataDir();
        }
        String identity;
        if (IdentityStore.MODE_SHARED.equals(IdentityStore.mode(host))) {
            /*
             * Default: the sign-in state is linked, never copied. A copy of a rotating
             * secret goes stale the moment the other half is used, which is what cost
             * the user their login across restarts (issue 5, see IdentityStore). Seeding
             * is still the fallback for any entry the link could not take over, so a
             * filesystem that rejects symlinks degrades to the old behaviour instead of
             * to a signed-out game.
             */
            boolean linked = IdentityStore.link(host.getDataDir(), root);
            int copied = linked ? 0 : seed(host.getDataDir(), root);
            identity = linked ? "identity shared" : "seeded " + copied + " entries";
        } else {
            int copied = seed(host.getDataDir(), root);
            identity = "identity kept local, seeded " + copied + " entries";
        }
        // "games" is the game's content subtree, and it moves rather than being
        // copied: two live copies of the same world is the one outcome nobody can
        // repair. It is also the only name at the launcher's root that the launcher
        // itself never writes, which is what makes it safe to claim wholesale.
        if (versionIsolated(host)) {
            migrate(new File(host.getDataDir(), "games"), new File(root, "games"));
        } else {
            /*
             * Shared mode: point every instance's games/ at one canonical location so
             * worlds and packs are the same across versions. If an instance already has
             * a real games/ directory from a previous isolated run, migrate it into the
             * shared root the first time (the shared location wins on conflict).
             */
            File shared = sharedGamesRoot(host);
            //noinspection ResultOfMethodCallIgnored
            shared.mkdirs();
            File local = new File(root, "games");
            if (local.isDirectory() && !SymlinkUtil.isSymlink(local)) {
                mergeInto(local, shared);
                deleteRecursive(local);
            }
            SymlinkUtil.symlink(shared, local);
            // Also migrate the launcher's own legacy games/ into the shared root once.
            migrate(new File(host.getDataDir(), "games"), shared);
        }
        Log.i(TAG, "guest data root " + root + " (" + identity + ")");
        return root;
    }

    /** The device-protected sibling, mirroring how Android keeps /data/user and /data/user_de apart. */
    public static File prepareDeviceProtectedRoot(File hostDeviceProtectedDir, String slug) {
        if (slug == null || hostDeviceProtectedDir == null) {
            return hostDeviceProtectedDir;
        }
        File de = isolate(hostDeviceProtectedDir, slug);
        if (!de.exists() && !de.mkdirs()) {
            Log.w(TAG, "cannot create " + de + ", leaving the device-protected root shared");
            return hostDeviceProtectedDir;
        }
        return de;
    }

    /**
     * Copies the sign-in state into a freshly created root: the XAL tree, the WebView
     * profile behind it, the preferences holding the device keypair, the databases, and
     * the licensing files. Copied, not moved — the launcher keeps its own copy and reads
     * it through its own context — and only while the destination name is still absent,
     * so a seeded root never overwrites state the installation produced itself.
     *
     * <p>Without this, giving the guest a real data root would sign the user out on the
     * first isolated launch, which is exactly the regression C14 caused by other means.
     * It is a one-time copy; issue 8 replaces it with an account the launcher exports
     * itself, and the share / don't-share switch becomes a write target.
     */
    private static int seed(File legacy, File root) {
        File[] children = legacy.listFiles();
        if (children == null) {
            return 0;
        }
        int copied = 0;
        for (File child : children) {
            if (!isSeeded(child.getName())) {
                continue;
            }
            File target = new File(root, child.getName());
            if (target.exists()) {
                continue;
            }
            try {
                copyTree(child, target);
                copied++;
            } catch (Throwable t) {
                Log.w(TAG, "cannot seed " + child.getName() + " into " + root + ": " + t);
            }
        }
        return copied;
    }

    private static boolean isSeeded(String name) {
        for (String seeded : SEEDED_NAMES) {
            if (seeded.equals(name)) {
                return true;
            }
        }
        for (String suffix : SEEDED_SUFFIXES) {
            if (name.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private static void copyTree(File source, File target) throws java.io.IOException {
        if (source.isDirectory()) {
            if (!target.mkdirs()) {
                throw new java.io.IOException("cannot create " + target);
            }
            File[] children = source.listFiles();
            if (children != null) {
                for (File child : children) {
                    copyTree(child, new File(target, child.getName()));
                }
            }
            return;
        }
        try (java.io.InputStream in = new java.io.FileInputStream(source);
                java.io.OutputStream out = new java.io.FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }
    }

    /**
     * Builds the path-rewrite table for one guest package.
     *
     * <p>The external block used to be guarded by
     * {@code context.getExternalCacheDir() != null}, where {@code context} is the
     * guest. On Android 16 that getter resolves to
     * {@code /storage/emulated/0/Android/data/<guestPackage>/cache} and asks vold to
     * create it — {@code ContextImpl.getExternalCacheDirs()} never creates cache dirs
     * in process, they go through StorageManagerService for quota tracking — and vold
     * answers
     *
     * <pre>SecurityException: Invalid mkdirs path: ... does not contain calling
     * package com.answer.launcher</pre>
     *
     * so the getter returns null, the guard fails, and the external rules never get
     * installed — which is exactly what would have made the getter work. The check
     * asked the broken thing whether it was broken, and this class logged nothing, so
     * the whole block disappeared without a trace.
     *
     * <p>Both sides are therefore computed without touching the guest's external
     * getters: the guest path is spelled from its package name, the target comes from
     * the launcher's own context, which owns that directory. The rewrite is then
     * transparent even from the framework's point of view, because the existence check
     * inside {@code ensureExternalDirsExistOrFilter()} calls {@code File.exists()}
     * itself — which goes through the hooked {@code UnixFileSystem
     * .getBooleanAttributes0} — so the guest's cache dir "exists" (it is ours), vold is
     * never consulted, and the guest receives a usable File whose every later access
     * lands in our tree.
     *
     * <p>Isolation is decided by the data root, not by this table: by the time the
     * rules are built, the guest's {@code ApplicationInfo.dataDir} already points at
     * its own directory, so {@code ContextImpl} hands the game files/cache/databases
     * paths inside it and there is nothing left to rewrite. What the table still owns
     * is the external storage, which the framework recomputes from the calling package
     * name on every call and never from {@code dataDir}, plus the launcher's own
     * library directory, which stays shared because it holds the {@code .so} files this
     * process already mapped.
     */
    public void enableRedirect(Context context) {
        Alternative manager = Alternative.getManager();
        installRedirectRules(context, null, manager.getGuestDataRoot());
    }

    private void installRedirectRules(Context context, String slug, File guestRoot) {
        Map<String, String> rule = new LinkedHashMap<>();
        String packageName = context.getPackageName();

        try {
            ApplicationInfo packageInfo = context.getPackageManager().getApplicationInfo(LauncherConstants.PACKAGE, 0);
            // The library directory is the launcher's own, shared by every
            // installation: it holds the .so files this process already mapped,
            // and isolating it would only duplicate them per version.
            rule.put("/data/data/" + packageName + "/lib", packageInfo.nativeLibraryDir);
            rule.put("/data/user/0/" + packageName + "/lib", packageInfo.nativeLibraryDir);

            String internalRoot = guestRoot.getAbsolutePath();
            rule.put("/data/data/" + packageName, internalRoot);
            rule.put("/data/user/0/" + packageName, internalRoot);

            rule.put("/data/user/0/" + packageName + "/app_ntp0", internalRoot + "/app_ntp0");

            Context host = Alternative.getManager().mContext;
            // Only the data getter appends a leaf ("/files", "/cache"), so only it
            // needs the parent step: getObbDir() and getExternalMediaDirs() already
            // end at the package directory itself.
            File hostData = parentOf(host.getExternalFilesDir(null));
            File hostCache = parentOf(host.getExternalCacheDir());
            File hostObb = host.getObbDir();
            File[] hostMedia = host.getExternalMediaDirs();
            File hostMediaRoot = hostMedia.length > 0 ? hostMedia[0] : null;

            // The external dirs have no cached fields on API 36 — every getter
            // recomputes them from getPackageName() at call time — so this table is
            // the only place they can be fixed. The bare roots go in first:
            // redirectPath() takes the first matching prefix, so one rule covers every
            // subpath (files/games/com.mojang/**, cache/**, …).
            addExternalRules(rule, packageName, "data", isolate(hostData, slug));
            addExternalRules(rule, packageName, "obb", isolate(hostObb, slug));
            addExternalRules(rule, packageName, "media", isolate(hostMediaRoot, slug));

            // Keep the leaf rules the previous table carried, for callers that build a
            // guest path out of an ApplicationInfo field instead of a Context getter.
            // They resolve to the same place; addRedirect() ignores duplicates.
            if (hostCache != null) {
                String guestData = "/storage/emulated/0/Android/data/" + packageName;
                File dataRoot = isolate(hostCache, slug);
                rule.put(guestData + "/cache", new File(dataRoot, "cache").getAbsolutePath());
                if (hostData != null) {
                    rule.put(guestData + "/files", new File(isolate(hostData, slug), "files").getAbsolutePath());
                }
            }

            // Two spellings of the game's content root survive here. The external one
            // is live: {@code Android/<kind>/…} is rebuilt from the calling package on
            // every getter, so a root change cannot reach it and the rule still does.
            // The internal {@code games} subtree is now correct on its own — it hangs
            // below the data root the guest was given — but C14 measured what happens
            // when it is not, so the two legacy spellings of the launcher's own path
            // stay in the table as a cheap safety net: if anything still opens
            // <launcher dataDir>/games, it lands in this installation's copy instead of
            // creating a second world list next to the first.
            if (slug != null) {
                String gamesTarget = new File(internalRoot, "games").getAbsolutePath();
                rule.put(packageInfo.dataDir + "/games", gamesTarget);
                rule.put("/data/user/0/" + LauncherConstants.PACKAGE + "/games", gamesTarget);
                if (hostData != null) {
                    rule.put(new File(hostData, "files/games").getAbsolutePath(),
                            new File(isolate(hostData, slug), "files/games").getAbsolutePath());
                }
            }

        } catch (Exception e) {
            Log.w(TAG, "redirect rules incomplete: " + e);
        }
        for (String key : rule.keySet()) {
            get().addRedirect(key, rule.get(key));
        }
        File guestCache = context.getExternalCacheDir();
        Log.i(TAG, "redirect rules installed: " + rule.size()
                + ", guest external cache = " + guestCache
                + ", usable=" + (guestCache != null && guestCache.canWrite()));
    }

    /**
     * Appends the installation segment to a host directory, or returns it unchanged
     * when isolation is off.
     */
    private static File isolate(File hostRoot, String slug) {
        if (hostRoot == null) {
            return null;
        }
        if (slug == null) {
            return hostRoot;
        }
        return new File(new File(hostRoot, ISOLATION_DIR), slug);
    }

    /**
     * True when {@code path} already lives under the root this installation was given.
     *
     * <p>The package-name substitutions elsewhere ({@code EnvironmentHook},
     * {@code AlterEnvironment}) exist because the framework computes paths from the
     * <em>guest</em> package name, which the host uid may not create. Once C15 handed
     * the guest a root of its own, the package name stopped being only a package name:
     * it is a piece of the installation slug. Rewriting that produced
     * {@code mc/com.answer.launcher@1.26.45.1/databases} — a directory nobody ever
     * created, measured as {@code W/ContextImpl: Failed to ensure ...} followed by
     * {@code SQLITE_CANTOPEN} in the Firebase transport database. A path already inside
     * our root is therefore left exactly as it is.
     */
    public static boolean isIsolated(String path) {
        if (path == null) {
            return false;
        }
        Alternative manager = Alternative.getManager();
        if (manager == null) {
            return false;
        }
        // Both credential stores: the device-protected one is a separate tree
        // (/data/user_de) and measured the same mangling one run after the first fix —
        // {@code W/ContextImpl: Failed to ensure .../mc/com.answer.launcher@1.26.45.1
        // /shared_prefs: mkdir failed: ENOENT}, where mkdir is not mkdirs and the
        // invented parent has no ancestor to create it.
        return under(manager.getIsolatedDataRoot(), path)
                || under(manager.getGuestDeviceProtectedRoot(), path);
    }

    private static boolean under(File root, String path) {
        if (root == null) {
            return false;
        }
        String prefix = root.getAbsolutePath();
        return path.equals(prefix) || path.startsWith(prefix + File.separator);
    }

    /**
     * One-shot move of the pre-isolation tree into the isolated one. A rename, so it
     * is instant and atomic for the whole directory, and it only ever runs while the
     * destination is still absent — after that both launcher and game only ever see
     * the new location.
     */
    private static void migrate(File legacy, File target) {
        if (legacy == null || !legacy.exists() || target.exists()) {
            return;
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            Log.w(TAG, "cannot create " + parent + ", leaving " + legacy + " in place");
            return;
        }
        if (legacy.renameTo(target)) {
            Log.i(TAG, "migrated " + legacy + " to " + target);
        } else {
            Log.w(TAG, "could not move " + legacy + " to " + target
                    + "; this installation starts with an empty world list");
        }
    }

    /**
     * Move every child of src into dst. When a name already exists in dst, the dst
     * copy wins (the shared location is authoritative) and the src copy is deleted.
     * Used when flipping an instance from isolated to shared mode.
     */
    private static void mergeInto(File src, File dst) {
        if (src == null || !src.isDirectory()) return;
        File[] kids = src.listFiles();
        if (kids == null) return;
        //noinspection ResultOfMethodCallIgnored
        dst.mkdirs();
        for (File k : kids) {
            File t = new File(dst, k.getName());
            if (t.exists()) {
                deleteRecursive(k);
            } else if (!k.renameTo(t)) {
                Log.w(TAG, "merge: could not move " + k + " to " + t);
            }
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        if (!f.delete()) Log.w(TAG, "delete failed: " + f);
    }

    /**
     * Maps every spelling of {@code /Android/<kind>/<guest>} onto the launcher's own
     * directory of that kind. A null root (no volume mounted) drops that one kind.
     * The lowercase aliases are the ones the previous table carried, so nothing that
     * worked before stops working.
     */
    private static void addExternalRules(Map<String, String> rule, String guestPackage,
            String kind, File hostRoot) {
        if (hostRoot == null) {
            return;
        }
        String target = hostRoot.getAbsolutePath();
        String canonical = "/storage/emulated/0/Android/" + kind + "/" + guestPackage;
        rule.put(canonical, target);
        rule.put("/sdcard/Android/" + kind + "/" + guestPackage, target);
        rule.put(canonical.toLowerCase(), target);
        rule.put(("/sdcard/Android/" + kind + "/" + guestPackage).toLowerCase(), target);
    }

    private static File parentOf(File file) {
        return file == null ? null : file.getParentFile();
    }

}
