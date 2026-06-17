package com.answer.launcher.core.manager;

import android.app.Activity;
import android.app.ActivityManagerNative;
import android.app.ActivityThread;
import android.app.Application;
import android.app.LoadedApk;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.os.IBinder;
import android.util.Log;

import com.answer.launcher.core.LauncherConstants;
import com.answer.launcher.core.env.AlterEnvironment;
import com.answer.launcher.core.env.AlterInstrumentation;
import com.answer.launcher.core.env.MinecraftEnv;
import com.answer.launcher.core.env.VirtualRuntime;
import com.answer.launcher.core.hook.BaseHook;
import com.answer.launcher.core.hook.SplashScreenHook;
import com.answer.launcher.core.hook.XboxHook;
import com.answer.launcher.core.tool.AbiUtils;
import com.answer.launcher.core.tool.AssetOverrideManager;
import com.answer.launcher.core.tool.ContextFixer;
import com.answer.launcher.core.tool.HotFix;
import com.answer.launcher.core.tool.Reflector;

import dalvik.system.DexClassLoader;

import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.Security;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * @Author AnswerDev
 * @Date 2024/04/05 08:34
 */
public class Alternative {
    private static volatile Alternative manager;
    public static List<ProviderInfo> mProviders = new ArrayList<>();

    public Context mContext;

    private PackageInfo packageInfo;
    private ApplicationInfo applicationInfo;
    private Object boundApplication;
    private Context packageContext;
    private Application application;
    private LoadedApk loadedApk;
    private AppBindData bindData;
    private DexClassLoader classLoader;
    public static String nativepath;

    private boolean isSupport = false;

    public void init(Context context) {
        this.mContext = context;

        try {
            initPackage();
            createLoadedApk();
            createApplication();
            initEnv();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private Alternative() {
    }

    public static Alternative getManager() {
        if (manager == null) {
            synchronized (Alternative.class) {
                if (manager == null) {
                    manager = new Alternative();
                }
            }
        }
        return manager;
    }

    public Context getContext() {
        return mContext;
    }

    public void LaunchMinecraft(Activity activity) {
        try {
            String LauncherClass ="com.answer.launcher.ui.activity.LauncherActivity";
            Class<?> clazz = Class.forName(LauncherClass, true, mContext.getClassLoader());
            Intent intent = new Intent(activity, clazz);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (clazz != null) AlterInstrumentation.get().startActivity(intent);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @SuppressWarnings("unchecked")
    private void initPackage() throws Exception {
        // Get package info for the target application

        packageInfo =
                mContext
                        .getApplicationContext()
                        .getPackageManager()
                        .getPackageInfo(LauncherConstants.MINECRAFTPACKAGE, PackageManager.GET_PROVIDERS);
        applicationInfo = packageInfo.applicationInfo;

        // init info
        LauncherConstants.MINECRAFT_VERSION = packageInfo.versionName;
        nativepath = packageInfo.applicationInfo.nativeLibraryDir;
        isSupport = new AbiUtils(new File(applicationInfo.publicSourceDir)).is64Bit();

        System.loadLibrary("alternative_core");
    }

    @SuppressWarnings("unchecked")
    private void createLoadedApk() throws Exception {
        // Get current ActivityThread instance
        ActivityThread currentActivityThread =
                (ActivityThread) Reflector.on(ActivityThread.class).field("sCurrentActivityThread").get();
        // Get mBoundApplication field
        boundApplication =
                Reflector.on(ActivityThread.class).field("mBoundApplication").get(currentActivityThread);

        // Add providers to mProviders list
        if (packageInfo.providers == null) {
            packageInfo.providers = new ProviderInfo[]{};
        }
        mProviders.addAll(Arrays.asList(packageInfo.providers));

        // Create package context
        // 待解决
        packageContext =
                mContext.createPackageContext(
                        LauncherConstants.MINECRAFTPACKAGE,
                        Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);

        // Hook
        BaseHook.setContext(packageContext);
        HookManager.getManager().inject();
        AlterInstrumentation.get().initHook();


        // Get LoadedApk instance
        loadedApk =
                (LoadedApk)
                        Reflector.on("android.app.ContextImpl").field("mPackageInfo").get(packageContext);


        Reflector.on(LoadedApk.class).field("mPackageName").set(loadedApk, LauncherConstants.PACKAGE);

        // Replace Data Dir
        Reflector.on(LoadedApk.class).field("mDataDirFile").set(loadedApk, mContext.getDataDir());
        Reflector.on(LoadedApk.class)
                .field("mDataDir")
                .set(loadedApk, mContext.getDataDir().getAbsolutePath());


        // Set necessary fields in loadedApk
        Reflector.on(LoadedApk.class).field("mSecurityViolation").set(loadedApk, false);
        Reflector.on(LoadedApk.class).field("mApplicationInfo").set(loadedApk, applicationInfo);

        if (AssestPackageManager.getManager().getPackagePath()!=null){
//            var path = AssestManager.getManager().getPackagePath();
//            AssetOverrideManager assetOverrideManager = AssetOverrideManager.getInstance();
//            assetOverrideManager.addAssetOverride(path);
            Log.e("Testt", AssetOverrideManager.getInstance().getAssetManager()+"");
            NativeCore.initAssets(AssetOverrideManager.getInstance().getAssetManager());
        }else {
            AssetOverrideManager.addAssetOverride(mContext.getAssets(), applicationInfo.publicSourceDir);
            NativeCore.initAssets(loadedApk.getAssets());
        }
    }

    private void createApplication() throws Exception {

        // Create AppBindData instance
        bindData = new AppBindData();
        bindData.appInfo = applicationInfo;
        bindData.processName = LauncherConstants.MINECRAFTPACKAGE;
        bindData.info = loadedApk;
        bindData.providers = mProviders;

        // init application info
        Reflector.on("android.app.ActivityThread$AppBindData")
                .fields("instrumentationName", "appInfo", "info", "processName", "providers")
                .sets(
                        boundApplication,
                        null,
                        new ComponentName(bindData.appInfo.packageName, AlterInstrumentation.class.getName()),
                        bindData.appInfo,
                        bindData.info,
                        bindData.processName,
                        bindData.providers);

        // load apk
        String optimizedDirectory = mContext.getDir("plugin", Context.MODE_PRIVATE).getAbsolutePath();
        classLoader =
                new DexClassLoader(
                        applicationInfo.sourceDir,
                        optimizedDirectory,
                        applicationInfo.nativeLibraryDir,
                        mContext.getClassLoader());

        // set ClassLoader
        Reflector.on(LoadedApk.class).field("mClassLoader").set(loadedApk, mContext.getClassLoader());
        Reflector.on(LoadedApk.class).field("mDefaultClassLoader").set(loadedApk, mContext.getClassLoader());

        // Hotfix
        //HotFix.addDexPath(classLoader, applicationInfo.sourceDir, new File(optimizedDirectory));
        HotFix.hotFix(mContext, classLoader);
        HotFix.hotFixNative(mContext, nativepath);

        // 路径hook 为后续的版本隔离
        IOManager.get().enableRedirect(packageContext);

        // create application
        application = loadedApk.makeApplication(false, AlterInstrumentation.get());


        Map<String, WeakReference<LoadedApk>> mPackages =
                Reflector.on(ActivityThread.class)
                        .field("mPackages")
                        .get(ActivityThread.currentActivityThread());
        WeakReference<LoadedApk> weakReference = new WeakReference<>(loadedApk);
        // 根据插件包名加入缓存
        mPackages.put(LauncherConstants.MINECRAFTPACKAGE, weakReference);
    }

    private void initEnv() throws Exception {


        MinecraftManager.getManager()
                .setLoadedApk(loadedApk)
                .setApplication(application)
                .setContext(packageContext)
                .init();

        // SSL适配
        Security.removeProvider("AndroidNSSP");
        Reflector.on("android.security.net.config.NetworkSecurityConfigProvider").method("install", Context.class).call(packageContext);

            // private void installContentProviders(Context context, List<ProviderInfo> list) {
        //    private void installContentProviders(Context var1, List<ProviderInfo> var2) {


//            Reflector.on(ActivityThread.class)
//                    .method("installContentProviders", Context.class, List.class)
//                    .callByCaller(ActivityThread.currentActivityThread(), mContext, mProviders);

            // Init WebView
            // new WebView(mContext);

            // Replace mInitialApplication
            Reflector.on(ActivityThread.class)
                    .field("mInitialApplication")
                    .set(ActivityThread.currentActivityThread(), application);

            new SplashScreenHook().init();
            new XboxHook().init();

        // Fix context if necessary
        ContextFixer.fix(application);

        // Establish AlterEnvironment
        AlterEnvironment.establishEnv(mContext, application.getBaseContext());

        // Establish Minecraft Env
        MinecraftEnv.establishEnv(application.getBaseContext());

        VirtualRuntime.setupRuntime(bindData.processName, bindData.appInfo);
    }

    public Service createService(String name) {
        try {
            // 使用反射实例化Service对象
            Service service =
                    (Service)
                            MinecraftManager.getManager()
                                    .getContext()
                                    .getClassLoader()
                                    .loadClass(name)
                                    .newInstance();

            // 通过反射获取ContextImpl的setOuterContext方法并设置为可访问
            Method setOuterContext =
                    Class.forName("android.app.ContextImpl")
                            .getDeclaredMethod("setOuterContext", Context.class);
            setOuterContext.setAccessible(true);
            setOuterContext.invoke(packageContext, service);

            // 通过反射获取Service类的attach方法并设置为可访问
            Method attach =
                    Service.class.getDeclaredMethod(
                            "attach",
                            android.content.Context.class,
                            android.app.ActivityThread.class,
                            String.class,
                            android.os.IBinder.class,
                            android.app.Application.class,
                            Object.class);
            attach.setAccessible(true);

            // 调用attach方法进行初始化
            attach.invoke(
                    service,
                    MinecraftManager.getManager().getContext(),
                    ActivityThread.currentActivityThread(),
                    name,
                    getActivityThread(),
                    MinecraftManager.getManager().getApplication(),
                    ActivityManagerNative.getDefault());
            return service;
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Service class not found: " + name, e);
        } catch (InstantiationException | IllegalAccessException e) {
            throw new RuntimeException("Unable to instantiate service: " + name, e);
        } catch (NoSuchMethodException | InvocationTargetException e) {
            throw new RuntimeException("Error setting up service: " + name, e);
        }
    }

    public Service createService(Context context, String name) {
        try {
            // 使用反射实例化Service对象
            Service service =
                    (Service)
                            MinecraftManager.getManager()
                                    .getContext()
                                    .getClassLoader()
                                    .loadClass(name)
                                    .newInstance();

            // 通过反射获取ContextImpl的setOuterContext方法并设置为可访问
            Method setOuterContext =
                    Class.forName("android.app.ContextImpl")
                            .getDeclaredMethod("setOuterContext", Context.class);
            setOuterContext.setAccessible(true);
            setOuterContext.invoke(context, service);

            // 通过反射获取Service类的attach方法并设置为可访问
            Method attach =
                    Service.class.getDeclaredMethod(
                            "attach",
                            android.content.Context.class,
                            android.app.ActivityThread.class,
                            String.class,
                            android.os.IBinder.class,
                            android.app.Application.class,
                            Object.class);
            attach.setAccessible(true);

            // 调用attach方法进行初始化
            attach.invoke(
                    service,
                    MinecraftManager.getManager().getContext(),
                    ActivityThread.currentActivityThread(),
                    name,
                    getActivityThread(),
                    MinecraftManager.getManager().getApplication(),
                    ActivityManagerNative.getDefault());
            return service;
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Service class not found: " + name, e);
        } catch (InstantiationException | IllegalAccessException e) {
            throw new RuntimeException("Unable to instantiate service: " + name, e);
        } catch (NoSuchMethodException | InvocationTargetException e) {
            throw new RuntimeException("Error setting up service: " + name, e);
        }
    }

    public IBinder getActivityThread() {
        return (IBinder) ActivityThread.currentActivityThread().getApplicationThread();
    }

    public static class AppBindData {
        int vpid;
        String processName;
        ApplicationInfo appInfo;
        List<ProviderInfo> providers;
        LoadedApk info;
    }
}
