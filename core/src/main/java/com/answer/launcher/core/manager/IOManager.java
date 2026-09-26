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

    public void enableRedirect(Context context, String versionName) {
        enableRedirect(context);
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
     */
    public void enableRedirect(Context context) {
        Map<String, String> rule = new LinkedHashMap<>();
        String packageName = context.getPackageName();

        try {
            ApplicationInfo packageInfo = context.getPackageManager().getApplicationInfo(LauncherConstants.PACKAGE, 0);
            rule.put("/data/data/" + packageName + "/lib", packageInfo.nativeLibraryDir);
            rule.put("/data/user/0/" + packageName + "/lib", packageInfo.nativeLibraryDir);

            rule.put("/data/data/" + packageName, packageInfo.dataDir);
            rule.put("/data/user/0/" + packageName, packageInfo.dataDir);

            rule.put("/data/user/0/" + packageName +"/app_ntp0", packageInfo.dataDir + "/app_ntp0");

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
            addExternalRules(rule, packageName, "data", hostData);
            addExternalRules(rule, packageName, "obb", hostObb);
            addExternalRules(rule, packageName, "media", hostMediaRoot);

            // Keep the leaf rules the previous table had, for callers that build a
            // guest path out of an ApplicationInfo field instead of a Context getter.
            // They resolve to the same place; addRedirect() ignores duplicates.
            if (hostCache != null) {
                String guestData = "/storage/emulated/0/Android/data/" + packageName;
                rule.put(guestData + "/cache", new File(hostCache, "cache").getAbsolutePath());
                if (hostData != null) {
                    rule.put(guestData + "/files", new File(hostData, "files").getAbsolutePath());
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
