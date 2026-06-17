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
        System.out.println( Build.VERSION.SDK_INT);
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.S_V2){
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

            Log.d(TAG, context +"");

        }else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // 4.1 Android S+ 版本
            int mDeviceId = contextImplReflector.field("mDeviceId").get(context);
            boolean mIsExplicitDeviceId = contextImplReflector.field("mIsExplicitDeviceId").get(context);
            boolean mForceDisplayOverride = contextImplReflector.field("mForceDisplayOverrideInResources").get(context);
             overrideDisplayId = mForceDisplayOverride ? displayId : null;

            // 创建ContextImpl实例
            newContext = contextImplReflector.constructor(
                            Class.forName(CONTEXT_IMPL),
                            Context.class,
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
                            null,      // params (ContextParams)
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

        Log.d(TAG, resources+"");

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
            } catch (Exception ignored) {}
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
            } catch (Exception ignored) {}
        }
        return null;
    }

    public LoadedApk getPackageInfoNoCheck(ApplicationInfo info, CompatibilityInfo compatInfo) {
        try {
            return (LoadedApk) Reflector.on(ActivityThread.class)
                    .method("getPackageInfoNoCheck",
                            ApplicationInfo.class,
                            CompatibilityInfo.class)
                    .callByCaller( ActivityThread.currentActivityThread(), info, compatInfo);
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

            return (CompatibilityInfo) Reflector.on("android.app.ActivityThread$AppBindData")
                    .field("compatInfo")
                    .get(appBindData);
        } catch (Exception e) {
            return null;
        }
    }

    public static PackageInfo getApkInfo(Context context, String path) {
        PackageManager pm = context.getPackageManager();
        PackageInfo pi = pm.getPackageArchiveInfo(path, PackageManager.GET_ACTIVITIES|PackageManager.GET_PROVIDERS);

        if (pi != null) {
            return pi;
        }
        return null;
    }



}
