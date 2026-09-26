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
     * <p>Passing the version name is what turns isolation on: the table then aims
     * the guest's own paths at {@code <launcher dataDir>/mc/<slug>} instead of the
     * launcher's data dir root. The slug carries the package name as well as the
     * version because a version string alone is not an identity — the same version
     * can be installed more than once (different channels, different APKs that
     * happen to report the same versionName), while Android guarantees one install
     * per package, so package+version is unique per installation by construction.
     */
    public void enableRedirect(Context context, String versionName) {
        installRedirectRules(context, isolationSlug(context.getPackageName(), versionName));
    }

    /**
     * Names one installation. Kept to characters that are safe on every filesystem
     * we can end up on (internal storage is ext4/f2fs, but the external roots can be
     * sdcardfs over anything), so nothing has to be escaped further downstream.
     */
    private static String isolationSlug(String guestPackage, String versionName) {
        String raw = guestPackage + "@" + (TextUtils.isEmpty(versionName) ? "unknown" : versionName);
        // '@' survives because it is the separator that makes the name unambiguous —
        // '_' is legal inside a package name, so a slug built from package + version
        // with only underscores could in principle be produced by two different
        // pairs. Android's own paths carry '~' and '=', so '@' is not a character a
        // mounted filesystem is allowed to reject.
        return raw.replaceAll("[^A-Za-z0-9._+@-]", "_");
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
     * <p>Only the redirect table decides isolation. The directories the guest gets
     * from its {@code Context} fields ({@code mFilesDir}, {@code mPreferencesDir},
     * {@code mDatabasesDir}, …) were copied verbatim from the launcher by
     * {@code AlterEnvironment.establishEnv()}, so they never contain the guest's
     * package name and never match a rule: they stay shared on purpose. That keeps
     * the launcher's own preferences, plugin storage and WebView profile where the
     * launcher expects them, and signs the guest in once for every installation.
     * The one exception is the subtree the game derives from those roots and writes
     * its content into — {@code <dataDir>/games} — which is claimed by name below.
     */
    public void enableRedirect(Context context) {
        installRedirectRules(context, null);
    }

    private void installRedirectRules(Context context, String slug) {
        Map<String, String> rule = new LinkedHashMap<>();
        String packageName = context.getPackageName();

        try {
            ApplicationInfo packageInfo = context.getPackageManager().getApplicationInfo(LauncherConstants.PACKAGE, 0);
            // The library directory is the launcher's own, shared by every
            // installation: it holds the .so files this process already mapped,
            // and isolating it would only duplicate them per version.
            rule.put("/data/data/" + packageName + "/lib", packageInfo.nativeLibraryDir);
            rule.put("/data/user/0/" + packageName + "/lib", packageInfo.nativeLibraryDir);

            String internalRoot = isolate(new File(packageInfo.dataDir), slug).getAbsolutePath();
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

            // The game does not only spell paths out of its own package name, and this
            // is the one that matters. Its content root is derived from the Context
            // directories that AlterEnvironment.establishEnv() copied from the launcher
            // — so at runtime it opens <launcher dataDir>/games/com.mojang/..., a path
            // carrying the *launcher's* package, which no guest-package rule can ever
            // match. Measured on the device after the first isolated build: the worlds
            // moved into the isolated root correctly, and the game then created a fresh
            // empty minecraftWorlds next to the launcher's own data dir. Redirecting
            // this subtree is what actually isolates worlds, packs and options; it is
            // also the only name at the launcher's root that the launcher itself never
            // writes, which is what makes it safe to claim wholesale.
            if (slug != null) {
                String gamesTarget = new File(internalRoot, "games").getAbsolutePath();
                rule.put(packageInfo.dataDir + "/games", gamesTarget);
                rule.put("/data/user/0/" + LauncherConstants.PACKAGE + "/games", gamesTarget);
                if (hostData != null) {
                    rule.put(new File(hostData, "files/games").getAbsolutePath(),
                            new File(isolate(hostData, slug), "files/games").getAbsolutePath());
                }
            }

            // Worlds, packs and options are the one thing that must survive being
            // pointed somewhere new, and "games" is the only name in the old
            // location the launcher can never have written itself: the game spells
            // it below its own data dir, so it is unambiguously the guest's.
            // Everything else that used to land in the shared root — login state,
            // SDK caches, telemetry files — stays shared, because those paths come
            // from the copied Context fields and carry no guest package name to
            // match a rule on. Signing in once therefore serves every installation.
            if (slug != null) {
                migrate(new File(packageInfo.dataDir, "games"), new File(internalRoot, "games"));
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
