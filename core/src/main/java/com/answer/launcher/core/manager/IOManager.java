package com.answer.launcher.core.manager;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.text.TextUtils;
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
        Map<String, String> rule = new LinkedHashMap<>();
        String packageName = context.getPackageName();

        try {
            ApplicationInfo packageInfo = context.getPackageManager().getApplicationInfo(LauncherConstants.PACKAGE, 0);
            rule.put("/data/data/" + packageName + "/lib", packageInfo.nativeLibraryDir);
            rule.put("/data/user/0/" + packageName + "/lib", packageInfo.nativeLibraryDir);

            rule.put("/data/data/" + packageName, packageInfo.dataDir);
            rule.put("/data/user/0/" + packageName, packageInfo.dataDir);

            rule.put("/data/user/0/" + packageName +"/app_ntp0", packageInfo.dataDir + "/app_ntp0");

            if (Alternative.getManager().mContext.getExternalCacheDir() != null && context.getExternalCacheDir() != null) {
                File external = context.getExternalCacheDir().getParentFile();

                // sdcard
                rule.put("/sdcard/Android/data/" + packageName,
                        external.getAbsolutePath());
                rule.put("/sdcard/android/data/" + packageName, external.getAbsolutePath());

                rule.put("/storage/emulated/0/android/data/" + packageName,
                        external.getAbsolutePath());
                rule.put("/storage/emulated/0/Android/data/" + packageName + "/files",
                        new File(external.getAbsolutePath(), "files").getAbsolutePath());
                rule.put("/storage/emulated/0/Android/data/" + packageName + "/cache",
                        new File(external.getAbsolutePath(), "cache").getAbsolutePath());
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
        for (String key : rule.keySet()) {
            get().addRedirect(key, rule.get(key));
        }
    }
    
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
            
            if (Alternative.getManager().mContext.getExternalCacheDir() != null && context.getExternalCacheDir() != null) {
                File external = context.getExternalCacheDir().getParentFile();

                // sdcard
                rule.put("/sdcard/Android/data/" + packageName,
                         external.getAbsolutePath());
                rule.put("/sdcard/android/data/" + packageName, external.getAbsolutePath());

                rule.put("/storage/emulated/0/android/data/" + packageName,
                         external.getAbsolutePath());
                         
                rule.put("/storage/emulated/0/Android/data/" + packageName + "/files",
                         new File(external.getAbsolutePath(), "files").getAbsolutePath());
                rule.put("/storage/emulated/0/Android/data/" + packageName + "/cache",
                         new File(external.getAbsolutePath(), "cache").getAbsolutePath());
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
        for (String key : rule.keySet()) {
            get().addRedirect(key, rule.get(key));
        }
    }

}
