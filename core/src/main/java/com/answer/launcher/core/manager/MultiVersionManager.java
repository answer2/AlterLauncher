package com.answer.launcher.core.manager;

import android.app.ActivityThread;
import android.app.LoadedApk;
import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextParams;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.IBinder;
import android.os.UserHandle;
import android.util.Log;

import androidx.annotation.RequiresApi;

import com.answer.launcher.core.tool.PassHiddenUtils;
import com.answer.launcher.core.tool.Reflector;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.util.Arrays;
import java.util.List;

public class MultiVersionManager {
    public static final String TAG = "MultiVersionManager";
    private static MultiVersionManager manager;

    private MultiVersionManager() {
        // private constructor to prevent instantiation
    }

    public static MultiVersionManager getManager() {
        if (manager == null) {
            synchronized (MultiVersionManager.class) {
                if (manager == null) {
                    manager = new MultiVersionManager();
                }
            }
        }
        return manager;
    }

    /*
      @Override
    public Context createPackageContextAsUser(String packageName, int flags, UserHandle user)
            throws NameNotFoundException {
        if (packageName.equals("system") || packageName.equals("android")) {
            // The system resources are loaded in every application, so we can safely copy
            // the context without reloading Resources.
            return new ContextImpl(this, mMainThread, mPackageInfo, mParams,
                    mAttributionSource.getAttributionTag(), mAttributionSource.getNext(), null,
                    mToken, user, flags, null, null, mDeviceId, mIsExplicitDeviceId);
        }

        LoadedApk pi = mMainThread.getPackageInfo(packageName, mResources.getCompatibilityInfo(),
                flags | CONTEXT_REGISTER_PACKAGE, user.getIdentifier());
        if (pi != null) {
            ContextImpl c = new ContextImpl(this, mMainThread, pi, mParams,
                    mAttributionSource.getAttributionTag(), mAttributionSource.getNext(), null,
                    mToken, user, flags, null, null, mDeviceId, mIsExplicitDeviceId);

            final int displayId = getDisplayId();
            final Integer overrideDisplayId = mForceDisplayOverrideInResources
                    ? displayId : null;

            c.setResources(createResources(mToken, pi, null, overrideDisplayId, null,
                    getDisplayAdjustments(displayId).getCompatibilityInfo(), null));
            if (c.mResources != null) {
                return c;
            }
        }

        // Should be a better exception.
        throw new PackageManager.NameNotFoundException(
                "Application package " + packageName + " not found");
    }


    private ContextImpl( ContextImpl container,  ActivityThread mainThread,
             LoadedApk packageInfo,  ContextParams params,
             String attributionTag,  AttributionSource nextAttributionSource,
             String splitName,  IBinder token,  UserHandle user,
             int flags,  ClassLoader classLoader,
             String overrideOpPackageName,
            int deviceId, boolean isExplicitDeviceId) {

    private ContextImpl(ContextImpl container, ActivityThread mainThread,
            LoadedApk packageInfo, String attributionTag,
             String splitName, IBinder activityToken, UserHandle user,
             int flags, ClassLoader classLoader,
            String overrideOpPackageName) {


   private ContextImpl( ContextImpl,  ActivityThread,
             LoadedApk,  ContextParams,
             String,  AttributionSource,
             String,  IBinder,  UserHandle,
            int, ClassLoader,  String,
            int, boolean) {

             createResources(IBinder, LoadedApk, String,
             Integer, Configuration,
            CompatibilityInfo, List<ResourcesLoader>) {


            XposedHelpers.findAndHookConstructor("android.app.ContextImpl", classLoader, android.app.ContextImpl.class, android.app.ActivityThread.class, android.app.LoadedApk.class, android.content.ContextParams.class, String.class, android.content.AttributionSource.class, String.class, android.os.IBinder.class, android.os.UserHandle.class, int.class, ClassLoader.class, String.class, new XC_MethodHook() {
    @Override
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
        super.beforeHookedMethod(param);
    }
    @Override
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
        super.afterHookedMethod(param);
    }
});

    * */

    public static final int CONTEXT_REGISTER_PACKAGE = 0x40000000;
    private static final String CONTEXT_IMPL = "android.app.ContextImpl";

    public Context createPackageContext(Context context, int flags, String apkPath) throws Exception {
        PackageInfo info = getApkInfo(context, apkPath);
        return createPackageContext(context, info.applicationInfo, flags);
    }

    public Context createPackageContext(Context context, ApplicationInfo info, int flags) throws Exception {

        Log.d(TAG, info + "");
        if (info == null) return null;

        // 1. 获取基础反射对象
        Reflector contextImplReflector = Reflector.on(CONTEXT_IMPL);
        UserHandle user = contextImplReflector.field("mUser").get(context);
        Object mToken = contextImplReflector.field("mToken").get(context);

        // 2. 获取LoadedApk
        CompatibilityInfo compatInfo = getResCompatibilityInfo();
        LoadedApk pi = getPackageInfoNoCheck(info, compatInfo);
        Log.d(TAG, pi + "LoadedApk");
        if (pi == null) return null;

        // 3. 获取显示相关参数
        int displayId = contextImplReflector.method("getDisplayId").callByCaller(context);
        Object displayAdjustments = contextImplReflector.method("getDisplayAdjustments", int.class)
                .callByCaller(context, displayId);
        Object compInfo = Reflector.on("android.view.DisplayAdjustments")
                .method("getCompatibilityInfo")
                .callByCaller(displayAdjustments);

        // 4. 创建上下文实例
        Object newContext;
        Integer overrideDisplayId = 0;
        System.out.println(Build.VERSION.SDK_INT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            Object mParams = contextImplReflector.field("mParams").get(context);
            boolean mForceDisplayOverride = contextImplReflector.field("mForceDisplayOverrideInResources").get(context);
            overrideDisplayId = mForceDisplayOverride ? displayId : null;

            // 创建ContextImpl实例
            //android.app.ActivityThread.class,android.app.LoadedApk.class,
            //         android.content.ContextParams.class, String.class,
            //         android.content.AttributionSource.class, String.class,
            //         android.os.IBinder.class,
            //        android.os.UserHandle.class, int.class,
            //         ClassLoader.class, String.class
            newContext = contextImplReflector.constructor(
                            Class.forName(CONTEXT_IMPL),
                            ActivityThread.class, LoadedApk.class,
                            ContextParams.class, String.class,
                            AttributionSource.class, String.class,
                            IBinder.class,
                            UserHandle.class, int.class,
                            ClassLoader.class, String.class)
                    .newInstance(
                            context,  // outerContext
                            ActivityThread.currentActivityThread(),
                            pi,       // packageInfo
                            mParams,      // params (ContextParams) this.mParams
                            getAttributionTag(context),  // attributionTag this.mAttributionSource.getAttributionTag
                            getAttributionNext(context), // attributionSourceNext
                            null,      // splitName
                            mToken,    // token
                            user,      // user
                            flags,     // flags
                            null,      // classLoader
                            null      // overrideOpPackageName
                    );

            Log.d(TAG, context + "");

        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            // 4.1 Android VANILLA_ICE_CREAM+ 版本
            int mDeviceId = contextImplReflector.field("mDeviceId").get(context);
            Object mParams = contextImplReflector.field("mParams").get(context);
            boolean mIsExplicitDeviceId = contextImplReflector.field("mIsExplicitDeviceId").get(context);
            boolean mForceDisplayOverride = contextImplReflector.field("mForceDisplayOverrideInResources").get(context);
            overrideDisplayId = mForceDisplayOverride ? displayId : null;

            // private ContextImpl(@Nullable ContextImpl container, @NonNull ActivityThread mainThread,
            //3472              @NonNull LoadedApk packageInfo, @NonNull ContextParams params,
            //3473              @Nullable String attributionTag, @Nullable AttributionSource nextAttributionSource,
            //3474              @Nullable String splitName, @Nullable IBinder token, @Nullable UserHandle user,
            //3475              int flags, @Nullable ClassLoader classLoader, @Nullable String overrideOpPackageName,
            //3476              int deviceId, boolean isExplicitDeviceId) {
            newContext = contextImplReflector.constructor(
                            Class.forName(CONTEXT_IMPL),
                            ActivityThread.class,
                            LoadedApk.class,
                            ContextParams.class,
                            String.class,
                            AttributionSource.class,
                            String.class,
                            IBinder.class,
                            UserHandle.class,
                            int.class,
                            ClassLoader.class,
                            String.class,
                            int.class,
                            boolean.class)
                    .newInstance(
                            context,  // outerContext
                            ActivityThread.currentActivityThread(),
                            pi,       // packageInfo
                            mParams,      // params (ContextParams)
                            getAttributionTag(context),  // attributionTag
                            getAttributionNext(context), // attributionSourceNext
                            null,      // splitName
                            mToken,    // token
                            user,      // user
                            flags,     // flags
                            null,      // classLoader
                            null,      // overrideOpPackageName
                            mDeviceId, // deviceId
                            mIsExplicitDeviceId
                    );
        } else {
            // 4.2 低版本处理
            newContext = contextImplReflector.constructorNoHidden(
                            Class.forName(CONTEXT_IMPL),
                            ActivityThread.class,
                            LoadedApk.class,
                            String.class,
                            String.class,
                            IBinder.class,
                            UserHandle.class,
                            int.class,
                            ClassLoader.class,
                            String.class)
                    .newInstance(
                            context,  // outerContext
                            ActivityThread.currentActivityThread(),
                            pi,       // packageInfo
                            getAttributionTag(context),  // attributionTag
                            null,      // nextAttributionSource (低版本无此参数)
                            mToken,    // token
                            user,      // user
                            flags,     // flags
                            null,      // classLoader
                            null       // overrideOpPackageName
                    );
        }

        // 5. 创建并设置资源
        Object resources;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            resources = contextImplReflector.method("createResources",
                            IBinder.class,
                            LoadedApk.class,
                            String.class,
                            Integer.class,
                            Configuration.class,
                            CompatibilityInfo.class,
                            List.class)
                    .call(// 静态方法调用
                            mToken,
                            pi,
                            null,     // resDir
                            overrideDisplayId,
                            null,     // overrideConfig
                            compInfo,
                            null      // sharedLibraries
                    );
        } else {
            resources = contextImplReflector.method("createResources",
                            IBinder.class,
                            LoadedApk.class,
                            String.class,
                            int.class,
                            Configuration.class,
                            CompatibilityInfo.class,
                            List.class)
                    .call(// 静态方法调用
                            mToken,
                            pi,
                            null,     // resDir
                            displayId,
                            null,     // overrideConfig
                            compInfo,
                            null      // sharedLibraries
                    );
        }

        Log.d(TAG, resources + "");

        // 6. 设置资源并返回
        if (resources != null) {
            contextImplReflector.method("setResources", Resources.class)
                    .callByCaller(newContext, resources);
            return (Context) newContext;
        }


        return null;
    }

    // 辅助方法优化
    public String getAttributionTag(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                return context.getAttributionTag();
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    public AttributionSource getAttributionNext(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                Object source = Reflector.on(CONTEXT_IMPL)
                        .field("mAttributionSource")
                        .get(context);
                return (AttributionSource) Reflector.on(AttributionSource.class)
                        .method("getNext")
                        .callByCaller(source);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    public LoadedApk getPackageInfoNoCheck(ApplicationInfo info, CompatibilityInfo compatInfo) {
        try {
            return (LoadedApk) Reflector.on(ActivityThread.class)
                    .method("getPackageInfoNoCheck",
                            ApplicationInfo.class,
                            CompatibilityInfo.class)
                    .callByCaller(ActivityThread.currentActivityThread(), info, compatInfo);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public CompatibilityInfo getResCompatibilityInfo() {
        try {
            Object appBindData = Reflector.on(ActivityThread.class)
                    .field("mBoundApplication")
                    .get(ActivityThread.currentActivityThread());

            CompatibilityInfo fromBind = (CompatibilityInfo) Reflector.on("android.app.ActivityThread$AppBindData")
                    .field("compatInfo")
                    .get(appBindData);
            if (fromBind != null) {
                return fromBind;
            }
        } catch (Throwable ignored) {
            // AppBindData.compatInfo is @UnsupportedAppUsage(maxTargetSdk=P);
            // it is simply not readable once the host targets a later SDK.
        }

        try {
            // Same field handleLaunchActivity passes to getPackageInfoNoCheck.
            return (CompatibilityInfo) Reflector.on(ActivityThread.class)
                    .field("mCompatibilityInfo")
                    .get(ActivityThread.currentActivityThread());
        } catch (Throwable t) {
            Log.w(TAG, "CompatibilityInfo unavailable", t);
            return null;
        }
    }

    /**
     * Builds the guest LoadedApk through ActivityThread#getPackageInfoNoCheck.
     *
     * <p>Unlike a hand-rolled LoadedApk this comes with a framework-constructed
     * ClassLoader (shared libraries, splits, overlays, correct parent) and
     * without a security violation, because the uid was already aligned with
     * the host. Returns null when the hidden method is unavailable so callers
     * can fall back to reflection.
     */
    public LoadedApk createLoadedApkViaActivityThread(ApplicationInfo applicationInfo) {
        if (applicationInfo == null) {
            return null;
        }
        if (applicationInfo instanceof ApplicationInfo
                && (applicationInfo.flags & ApplicationInfo.FLAG_HAS_CODE) == 0) {
            // Without FLAG_HAS_CODE ActivityThread files the LoadedApk in the
            // resource-only map and hands back a class loader with no code in
            // it, so the game's classes silently fail to resolve.
            applicationInfo.flags |= ApplicationInfo.FLAG_HAS_CODE;
        }
        try {
            CompatibilityInfo compatInfo = getResCompatibilityInfo();
            if (compatInfo == null) {
                // LoadedApk's constructor feeds this straight into
                // mDisplayAdjustments.setCompatibilityInfo(); null NPEs there.
                Log.w(TAG, "no CompatibilityInfo available, using reflective path");
                return null;
            }
            LoadedApk apk = getPackageInfoNoCheck(applicationInfo, compatInfo);
            if (apk != null) {
                Log.i(TAG, "guest LoadedApk built by ActivityThread: " + apk);
            }
            return apk;
        } catch (Throwable t) {
            Log.w(TAG, "getPackageInfoNoCheck unavailable, falling back to reflection", t);
            return null;
        }
    }

    public LoadedApk createLoadedApk(ApplicationInfo applicationInfo){
        CompatibilityInfo compatInfo = getResCompatibilityInfo();
        LoadedApk pi = getPackageInfoNoCheck(applicationInfo, compatInfo);
        return pi;
    }

    public static PackageInfo getApkInfo(Context context, String path) {
        PackageManager pm = context.getPackageManager();
        PackageInfo pi = pm.getPackageArchiveInfo(path, PackageManager.GET_ACTIVITIES | PackageManager.GET_PROVIDERS);

        if (pi != null) {
            return pi;
        }
        return null;
    }


}
