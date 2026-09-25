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
import android.os.Process;
import android.util.Log;

import com.answer.launcher.core.LauncherConstants;
import com.answer.launcher.core.env.AlterEnvironment;
import com.answer.launcher.core.env.AlterInstrumentation;
import com.answer.launcher.core.env.MinecraftEnv;
import com.answer.launcher.core.env.VirtualRuntime;
import com.answer.launcher.core.hook.BaseHook;
import com.answer.launcher.core.hook.HookProvider;
import com.answer.launcher.core.tool.AbiUtils;
import com.answer.launcher.core.tool.AssetOverrideManager;
import com.answer.launcher.core.tool.ContextFixer;
import com.answer.launcher.core.tool.HotFix;
import com.answer.launcher.core.tool.Reflector;

import android.content.res.CompatibilityInfo;

import dalvik.system.DexClassLoader;

import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.Security;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Minecraft Alternative Runtime Manager
 *
 * @Author AnswerDev
 * @Date 2024/04/05 08:34
 */
public class Alternative {

    /**
     * Minecraft Provider 列表
     */
    public static final List<ProviderInfo> mProviders = new ArrayList<>();
    private static final String TAG = "Alternative";
    /**
     * Minecraft nativeLibraryDir
     */
    public static String nativepath;
    private static volatile Alternative manager;
    /**
     * Launcher Context
     */
    public Context mContext;
    /**
     * Minecraft PackageInfo
     */
    private PackageInfo packageInfo;
    /**
     * Minecraft ApplicationInfo
     */
    private ApplicationInfo applicationInfo;
    /**
     * ActivityThread.mBoundApplication
     */
    private Object boundApplication;
    /**
     * Minecraft ContextImpl
     * <p>
     * 注意：
     * 这里不再通过 createPackageContext() 创建。
     */
    private Context packageContext;
    /**
     * Minecraft Application
     */
    private Application application;
    /**
     * Minecraft LoadedApk
     */
    private LoadedApk loadedApk;
    /**
     * AppBindData
     */
    private AppBindData bindData;
    /**
     * Minecraft DexClassLoader
     */
    private DexClassLoader classLoader;
    /**
     * 是否 64 位
     */
    private boolean isSupport = false;

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

    public Application getApplication() {
        return application;
    }

    public LoadedApk getLoadedApk() {
        return loadedApk;
    }

    public ClassLoader getClassLoader() {
        return classLoader;
    }

    public Context getPackageContext() {
        return packageContext;
    }

    /**
     * Neutralise {@code ContextImpl#enforce} for the hosted package.
     *
     * <p>Resolved by name across overloads instead of one fixed parameter list:
     * the identity argument moved from {@code int uid} to
     * {@code AttributionSource} in recent releases, and a hard-coded lookup
     * simply threw and left permission checks live.
     */
    private void neutraliseEnforce() {
        Class<?> contextImpl = HookProvider.clazz("android.app.ContextImpl");
        if (contextImpl == null) {
            return;
        }
        int matched = 0;
        for (Method overload : HookProvider.methodsByName(contextImpl, "enforce")) {
            Class<?>[] types = overload.getParameterTypes();
            if (types.length < 3 || types[0] != String.class
                    || types[1] != int.class || types[2] != boolean.class) {
                continue;
            }
            matched++;
            HookProvider.replace(overload, params -> {
                if (LauncherConstants.isDebug) {
                    Log.v(TAG, "enforce " + Arrays.toString(params.args));
                }
                return null;
            });
        }
        if (matched == 0) {
            Log.w(TAG, "no ContextImpl.enforce overload matched");
        }
    }

    /**
     * 初始化 Minecraft Runtime
     */
    public void init(Context context) {
        this.mContext = context.getApplicationContext();

        try {
            Log.e(TAG, "========================================");
            Log.e(TAG, "Initializing Minecraft Runtime");
            Log.e(TAG, "Host package = " + mContext.getPackageName());
            Log.e(TAG, "Host uid = " + Process.myUid());
            Log.e(TAG, "========================================");

            neutraliseEnforce();

            initPackage();

            createLoadedApk();

            createApplication();

            initEnv();

            Log.e(TAG, "Minecraft Runtime initialized successfully");

        } catch (Throwable e) {
            Log.e(TAG, "Failed to initialize Minecraft Runtime", e);
            throw new RuntimeException("Failed to initialize Minecraft Runtime", e);
        }
    }

    /**
     * 获取 Minecraft PackageInfo
     * <p>
     * 这里仅仅通过 PackageManager 获取 APK 信息。
     * <p>
     * 注意：
     * 不在这里调用 createPackageContext().
     */
    @SuppressWarnings("deprecation")
    private void initPackage() throws Exception {

        PackageManager packageManager =
                mContext.getPackageManager();

        packageInfo =
                packageManager.getPackageInfo(
                        LauncherConstants.MINECRAFTPACKAGE,
                        PackageManager.GET_PROVIDERS
                );

        if (packageInfo == null) {
            throw new IllegalStateException(
                    "Minecraft PackageInfo is null: "
                            + LauncherConstants.MINECRAFTPACKAGE
            );
        }

        applicationInfo = packageInfo.applicationInfo;

        if (applicationInfo == null) {
            throw new IllegalStateException(
                    "Minecraft ApplicationInfo is null for "
                            + LauncherConstants.MINECRAFTPACKAGE
            );
        }

        // Must run before anything reads applicationInfo.uid: LoadedApk
        // computes mSecurityViolation from a uid comparison, and the
        // ClassLoader/data-dir setup below assumes the hosted app shares our uid.
        applicationInfo.uid = Process.myUid();

        LauncherConstants.MINECRAFT_VERSION =
                packageInfo.versionName;

        nativepath =
                applicationInfo.nativeLibraryDir;

        isSupport =
                new AbiUtils(
                        new File(applicationInfo.publicSourceDir)
                ).is64Bit();

        /*
         * Debug UID 信息
         *
         * 非常重要：
         *
         * Launcher:
         *     Process.myUid()
         *
         * Minecraft:
         *     applicationInfo.uid
         *
         * 两者不同是正常的。
         *
         * 这里不要简单地修改 applicationInfo.uid。
         */
        Log.e(
                TAG,
                "Host package = "
                        + mContext.getPackageName()
        );

        Log.e(
                TAG,
                "Host uid = "
                        + Process.myUid()
        );

        Log.e(
                TAG,
                "Minecraft package = "
                        + applicationInfo.packageName
        );

        Log.e(
                TAG,
                "Minecraft uid = "
                        + applicationInfo.uid
        );

        Log.e(
                TAG,
                "Minecraft sourceDir = "
                        + applicationInfo.sourceDir
        );

        Log.e(
                TAG,
                "Minecraft publicSourceDir = "
                        + applicationInfo.publicSourceDir
        );

        Log.e(
                TAG,
                "Minecraft nativeLibraryDir = "
                        + applicationInfo.nativeLibraryDir
        );

        Log.e(
                TAG,
                "Minecraft version = "
                        + packageInfo.versionName
        );

        Log.e(
                TAG,
                "Minecraft 64 bit = "
                        + isSupport
        );

        /*
         * 加载自己的 native core
         */
        System.loadLibrary("alternative_core");
    }

    /**
     * 创建 Minecraft DexClassLoader
     */
    private void createClassLoader() throws Exception {

        String optimizedDirectory =
                mContext
                        .getDir(
                                "plugin",
                                Context.MODE_PRIVATE
                        )
                        .getAbsolutePath();

        File optimizedDir =
                new File(optimizedDirectory);

        if (!optimizedDir.exists()) {
            boolean result = optimizedDir.mkdirs();

            if (!result && !optimizedDir.exists()) {
                throw new IllegalStateException(
                        "Unable to create optimized directory: "
                                + optimizedDir
                );
            }
        }

        classLoader =
                new DexClassLoader(
                        applicationInfo.sourceDir,
                        optimizedDirectory,
                        applicationInfo.nativeLibraryDir,
                        mContext.getClassLoader()
                );

        Log.e(
                TAG,
                "Minecraft ClassLoader = "
                        + classLoader
        );
    }

    /**
     * 创建 LoadedApk
     * <p>
     * 核心修改：
     * <p>
     * 原来：
     * <p>
     * createPackageContext()
     * ↓
     * LoadedApk
     * <p>
     * 现在：
     * <p>
     * PackageInfo
     * ↓
     * DexClassLoader
     * ↓
     * LoadedApk
     * ↓
     * ContextImpl
     */
    @SuppressWarnings("unchecked")
    private void createLoadedApk() throws Exception {

        ActivityThread activityThread =
                ActivityThread.currentActivityThread();

        if (activityThread == null) {
            throw new IllegalStateException(
                    "ActivityThread.currentActivityThread() == null"
            );
        }

        /*
         * 获取 mBoundApplication
         */
        boundApplication =
                Reflector
                        .on(ActivityThread.class)
                        .field("mBoundApplication")
                        .get(activityThread);

        if (boundApplication == null) {
            throw new IllegalStateException(
                    "ActivityThread.mBoundApplication == null"
            );
        }

        /*
         * Data directories must be set on the ApplicationInfo *before* the
         * LoadedApk is built: on API 36 LoadedApk derives mDataDirFile,
         * mDeviceProtectedDataDirFile and mCredentialProtectedDataDirFile only
         * inside setApplicationInfo(), and setDataDirInfoLocked() no longer
         * exists. Writing the LoadedApk fields afterwards is both too late and
         * (for mSecurityViolation/mPackageName) impossible, as they are final.
         */
        String hostDataDir = mContext.getDataDir().getAbsolutePath();
        applicationInfo.dataDir = hostDataDir;
        applicationInfo.deviceProtectedDataDir = deviceProtectedDirPath();
        // ApplicationInfo.credentialProtectedDataDir is @hide in the SDK, so it
        // has to be written reflectively; LoadedApk tolerates a null one.
        try {
            Reflector.on(ApplicationInfo.class)
                    .field("credentialProtectedDataDir")
                    .set(applicationInfo, hostDataDir);
        } catch (Throwable ignored) {
        }

        /*
         * Provider
         */
        mProviders.clear();

        if (packageInfo.providers != null) {
            mProviders.addAll(
                    Arrays.asList(
                            packageInfo.providers
                    )
            );
        }

        Log.e(
                TAG,
                "Minecraft providers = "
                        + mProviders.size()
        );

        /*
         * 创建 ClassLoader
         */
        createClassLoader();

        /*
         * 创建 LoadedApk
         */
        /*
         * Create the game LoadedApk.
         *
         * Preferred path is the framework's own factory,
         * ActivityThread#getPackageInfoNoCheck(ApplicationInfo, CompatibilityInfo):
         * it builds the real ClassLoader (shared libraries, split paths,
         * overlay dirs, the correct base loader) and it does NOT perform the
         * "does this package belong to my uid" check that createPackageContext()
         * does, so it needs no final-field surgery afterwards. It is also the
         * method ActivityThread itself uses for activities and services.
         */
        loadedApk = MultiVersionManager.getManager()
                .createLoadedApkViaActivityThread(applicationInfo);

        if (loadedApk == null) {
            // Older/modified ROMs may not expose the hidden method; fall back.
            loadedApk = createLoadedApkReflectively(activityThread, applicationInfo, classLoader);
        }

        if (loadedApk == null) {
            throw new IllegalStateException(
                    "Unable to create Minecraft LoadedApk"
            );
        }

        Log.e(
                TAG,
                "LoadedApk created = "
                        + loadedApk
        );

        /*
         * 设置 LoadedApk 的关键字段
         */
        setupLoadedApk();

        /*
         * 创建 ContextImpl
         *
         * 这里是整个修复最重要的地方。
         *
         * 不再调用：
         *
         *     mContext.createPackageContext(...)
         *
         * 因此不会因为：
         *
         *     Package com.mojang.minecraftpe
         *     does not belong to 10429
         *
         * 而在这里直接炸掉。
         */
        packageContext =
                createAppContext(
                        activityThread,
                        loadedApk
                );

        if (packageContext == null) {
            throw new IllegalStateException(
                    "Minecraft ContextImpl is null"
            );
        }

        Log.e(
                TAG,
                "Minecraft Context = "
                        + packageContext
        );

        Log.e(
                TAG,
                "Minecraft Context package = "
                        + packageContext.getPackageName()
        );

        /*
         * Hook
         */
        BaseHook.setContext(packageContext);

        HookManager
                .getManager()
                .inject();

        AlterInstrumentation
                .get()
                .initHook();

        /*
         * Asset
         */
        setupAssets();
    }

    /**
     * 通过反射创建 LoadedApk
     * <p>
     * Android 不同版本的 LoadedApk 构造函数可能有所区别。
     * <p>
     * 常见形式：
     * <p>
     * LoadedApk(
     * ActivityThread,
     * ApplicationInfo,
     * CompatibilityInfo,
     * ClassLoader,
     * boolean,
     * boolean,
     * boolean
     * )
     * <p>
     * 所以这里不直接写死 Constructor。
     */
    private LoadedApk createLoadedApkReflectively(
            ActivityThread activityThread,
            ApplicationInfo appInfo,
            ClassLoader loader) throws Exception {

        Constructor<?>[] constructors =
                LoadedApk.class.getDeclaredConstructors();

        Log.e(
                TAG,
                "LoadedApk constructors = "
                        + constructors.length
        );

        int i;

        for (i = 0; i < constructors.length; i++) {

            Constructor<?> constructor =
                    constructors[i];

            Class<?>[] parameterTypes =
                    constructor.getParameterTypes();

            Log.e(
                    TAG,
                    "LoadedApk constructor["
                            + i
                            + "] parameter count = "
                            + parameterTypes.length
            );

            if (parameterTypes.length < 4) {
                continue;
            }

            Object[] args =
                    buildLoadedApkConstructorArguments(
                            parameterTypes,
                            activityThread,
                            appInfo,
                            loader
                    );

            if (args == null) {
                continue;
            }

            try {

                constructor.setAccessible(true);

                Object result =
                        constructor.newInstance(args);

                if (result instanceof LoadedApk) {

                    Log.e(
                            TAG,
                            "LoadedApk constructor matched: "
                                    + constructor
                    );

                    return (LoadedApk) result;
                }

            } catch (Throwable e) {

                Log.e(
                        TAG,
                        "Failed constructor: "
                                + constructor,
                        e
                );
            }
        }

        throw new NoSuchMethodException(
                "No compatible LoadedApk constructor found"
        );
    }

    /**
     * 根据 LoadedApk 构造函数参数类型创建参数。
     */
    private Object[] buildLoadedApkConstructorArguments(
            Class<?>[] parameterTypes,
            ActivityThread activityThread,
            ApplicationInfo appInfo,
            ClassLoader loader) {

        Object[] args =
                new Object[parameterTypes.length];

        boolean activityThreadUsed = false;
        boolean applicationInfoUsed = false;
        boolean compatibilityInfoUsed = false;
        boolean classLoaderUsed = false;

        int booleanCount = 0;

        int i;

        for (i = 0; i < parameterTypes.length; i++) {

            Class<?> type =
                    parameterTypes[i];

            /*
             * ActivityThread
             */
            if (type == ActivityThread.class) {

                if (activityThreadUsed) {
                    return null;
                }

                args[i] = activityThread;
                activityThreadUsed = true;

                continue;
            }

            /*
             * ApplicationInfo
             */
            if (type == ApplicationInfo.class) {

                if (applicationInfoUsed) {
                    return null;
                }

                args[i] = appInfo;
                applicationInfoUsed = true;

                continue;
            }

            /*
             * CompatibilityInfo
             */
            if (type == CompatibilityInfo.class) {

                if (compatibilityInfoUsed) {
                    return null;
                }

                args[i] =
                        CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO;

                compatibilityInfoUsed = true;

                continue;
            }

            /*
             * ClassLoader
             */
            if (type == ClassLoader.class) {

                if (classLoaderUsed) {
                    return null;
                }

                args[i] = loader;
                classLoaderUsed = true;

                continue;
            }

            /*
             * boolean
             */
            if (type == boolean.class) {

                /*
                 * 对 LoadedApk 来说：
                 *
                 * securityViolation
                 * includeCode
                 * registerPackage
                 *
                 * 都不能因为 unknown version 而传错。
                 *
                 * 这里统一：
                 *
                 * securityViolation = false
                 * includeCode = true
                 * registerPackage = false
                 *
                 * 由于构造函数本身只有 boolean，
                 * 按出现顺序处理。
                 */
                if (booleanCount == 0) {

                    args[i] = false;

                } else if (booleanCount == 1) {

                    args[i] = true;

                } else {

                    args[i] = false;
                }

                booleanCount++;

                continue;
            }

            /*
             * 某些 Android 版本可能增加其他参数。
             *
             * 当前版本无法安全判断。
             */
            return null;
        }

        /*
         * 至少需要这些核心参数。
         */
        if (!activityThreadUsed) {
            return null;
        }

        if (!applicationInfoUsed) {
            return null;
        }

        if (!classLoaderUsed) {
            return null;
        }

        return args;
    }

    /**
     * 设置 LoadedApk 字段
     */
    /**
     * Finish the guest LoadedApk.
     *
     * <p>Deliberately narrow. Everything else the old code wrote here is either
     * final on API 36 (mPackageName, mSecurityViolation, mBaseClassLoader) or
     * already derived by the constructor from ApplicationInfo
     * (mDataDir/mDataDirFile come from aInfo.dataDir, which createLoadedApk()
     * now sets *before* construction). Writing finals after the fact is a
     * crash waiting for a field rename, so it is gone.
     */
    private void setupLoadedApk() throws Exception {

        /*
         * ClassLoader: keep our DexClassLoader over the Minecraft APK.
         * mClassLoader is still assignable on API 36, and without this the
         * LoadedApk would expose the framework-built loader instead of the one
         * HotFix merges the game dex into.
         */
        if (classLoader != null) {
            setFieldIfExists(loadedApk, "mClassLoader", classLoader);
            setFieldIfExists(loadedApk, "mDefaultClassLoader", classLoader);
        }

        Log.e(
                TAG,
                "LoadedApk package = "
                        + loadedApk.getPackageName()
        );
    }

    /**
     * 创建 ContextImpl
     * <p>
     * 使用 Android 内部：
     * <p>
     * ContextImpl.createAppContext(
     * ActivityThread,
     * LoadedApk
     * )
     */
    private Context createAppContext(
            ActivityThread activityThread,
            LoadedApk apk) throws Exception {

        Class<?> contextImplClass =
                Class.forName(
                        "android.app.ContextImpl"
                );

        /*
         * Prefer the 3-arg overload ContextImpl.createAppContext(
         * ActivityThread, LoadedApk, String opPackageName).
         *
         * On API 36 mBasePackageName and mOpPackageName are final, so the old
         * "build the context, then rewrite its attribution source" trick is
         * impossible. The op package has to be decided at construction:
         * passing the *launcher* package makes permission checks, Settings
         * provider access and NotificationManager attribution resolve against
         * the UID that actually owns this process, while mPackageInfo keeps
         * pointing at Minecraft so resources and the class loader stay the
         * guest's.
         */
        Context result = null;
        try {
            Method threeArg =
                    contextImplClass.getDeclaredMethod(
                            "createAppContext",
                            ActivityThread.class,
                            LoadedApk.class,
                            String.class
                    );

            threeArg.setAccessible(true);

            Object viaOpPackage =
                    threeArg.invoke(
                            null,
                            activityThread,
                            apk,
                            LauncherConstants.PACKAGE
                    );

            if (viaOpPackage instanceof Context) {
                result = (Context) viaOpPackage;
            }
        } catch (Throwable t) {
            Log.i(TAG, "3-arg createAppContext unavailable, using 2-arg", t);
        }

        if (result == null) {
            Method method =
                    contextImplClass.getDeclaredMethod(
                            "createAppContext",
                            ActivityThread.class,
                            LoadedApk.class
                    );

            method.setAccessible(true);

            Object fallback =
                    method.invoke(
                            null,
                            activityThread,
                            apk
                    );

            if (!(fallback instanceof Context)) {
                throw new IllegalStateException(
                        "createAppContext returned: "
                                + fallback
                );
            }

            result = (Context) fallback;
        }

        return result;
    }

    /**
     * Asset 初始化
     */
    private void setupAssets() throws Exception {

        if (AssestPackageManager
                .getManager()
                .getPackagePath() != null) {

            String path =
                    AssestPackageManager
                            .getManager()
                            .getPackagePath();

            AssetOverrideManager
                    assetOverrideManager =
                    AssetOverrideManager
                            .getInstance();

            assetOverrideManager
                    .addAssetOverride(path);

            Log.e(
                    TAG,
                    "AssetManager = "
                            + assetOverrideManager
                            .getAssetManager()
            );

            NativeCore.initAssets(
                    assetOverrideManager
                            .getAssetManager()
            );

        } else {

            AssetOverrideManager
                    .addAssetOverride(
                            mContext.getAssets(),
                            applicationInfo.publicSourceDir
                    );

            NativeCore.initAssets(
                    packageContext.getAssets()
            );
        }
    }

    /**
     * 创建 Minecraft Application
     */
    private void createApplication() throws Exception {

        /*
         * 创建 AppBindData
         */
        bindData =
                new AppBindData();

        bindData.appInfo =
                applicationInfo;

        bindData.processName =
                LauncherConstants.MINECRAFTPACKAGE;

        bindData.info =
                loadedApk;

        bindData.providers =
                mProviders;

        /*
         * 修改 ActivityThread.mBoundApplication
         */
        Reflector
                .on("android.app.ActivityThread$AppBindData")
                .fields(
                        "instrumentationName",
                        "appInfo",
                        "info",
                        "processName",
                        "providers"
                )
                .sets(
                        boundApplication,
                        null,
                        new ComponentName(
                                bindData.appInfo.packageName,
                                AlterInstrumentation.class.getName()
                        ),
                        bindData.appInfo,
                        bindData.info,
                        bindData.processName,
                        bindData.providers
                );

        /*
         * HotFix
         */
        HotFix.hotFix(
                mContext,
                classLoader
        );

        /*
         * IO Redirect
         */
        IOManager
                .get()
                .enableRedirect(packageContext);

        /*
         * The game's dex is now merged into this class loader, so hook groups
         * whose targets live in it can finally be installed. This used to be
         * done by calling SplashScreenHook/XboxHook a second time from
         * initEnv(), which also left them double-registered on the paths where
         * the first pass did succeed.
         */
        HookManager
                .getManager()
                .injectGameHooks();

        /*
         * 创建 Application
         */
        Log.e(
                TAG,
                "Creating Minecraft Application"
        );

        application =
                loadedApk.makeApplication(
                        false,
                        // On API 36 LoadedApk only invokes
                        // instrumentation.callApplicationOnCreate(app) when this
                        // argument is non-null (LoadedApk.java:1497). Passing
                        // null here silently skipped the game Application's
                        // onCreate() entirely.
                        AlterInstrumentation.get()
                );

        if (application == null) {
            throw new IllegalStateException(
                    "LoadedApk.makeApplication() returned null"
            );
        }

        Log.e(
                TAG,
                "Minecraft Application = "
                        + application
        );

        Log.e(
                TAG,
                "Application class = "
                        + application
                        .getClass()
                        .getName()
        );

        /*
         * Application BaseContext
         */
        Log.e(
                TAG,
                "Application package = "
                        + application
                        .getPackageName()
        );

        /*
         * ActivityThread.mPackages
         */
        ActivityThread activityThread =
                ActivityThread.currentActivityThread();

        Map<String, WeakReference<LoadedApk>> mPackages =
                Reflector
                        .on(ActivityThread.class)
                        .field("mPackages")
                        .get(activityThread);

        if (mPackages != null) {

            WeakReference<LoadedApk> reference =
                    new WeakReference<>(
                            loadedApk
                    );

            mPackages.put(
                    LauncherConstants.MINECRAFTPACKAGE,
                    reference
            );

            Log.e(
                    TAG,
                    "Minecraft LoadedApk inserted into ActivityThread.mPackages"
            );
        }
    }

    /**
     * 初始化 Minecraft Environment
     */
    private void initEnv() throws Exception {

        /*
         * MinecraftManager
         */
        MinecraftManager
                .getManager()
                .setLoadedApk(loadedApk)
                .setApplication(application)
                .setContext(packageContext)
                .init();

        /*
         * SSL
         */
        try {

            Security.removeProvider(
                    "AndroidNSSP"
            );

        } catch (Throwable e) {

            Log.e(
                    TAG,
                    "Failed to remove AndroidNSSP",
                    e
            );
        }

        try {

            Reflector
                    .on(
                            "android.security.net.config.NetworkSecurityConfigProvider"
                    )
                    .method(
                            "install",
                            Context.class
                    )
                    .call(
                            packageContext
                    );

        } catch (Throwable e) {

            Log.e(
                    TAG,
                    "NetworkSecurityConfigProvider install failed",
                    e
            );
        }

        /*
         * mInitialApplication is deliberately NOT overwritten.
         *
         * The guest Application is reachable through the guest LoadedApk
         * (getApp()) and through the app context returned by createAppContext,
         * which is what Minecraft actually uses. Overriding this field made
         * ActivityThread.currentApplication() report the Minecraft package for
         * a process whose real UID belongs to the launcher, and any framework
         * call that derives its calling package from it was rejected:
         *
         *   SecurityException: BIND_EXTERNAL_SERVICE failed,
         *       calling package not owned by calling UID
         *   at android.app.ContextImpl.bindServiceCommon(ContextImpl.java:2282)
         *
         * That is how the Chromium WebView renderer binding killed the process
         * in the pre-convergence baseline. The launcher's own Application stays
         * the process identity; the game keeps its own.
         */
        Log.d(TAG, "guest application registered separately, "
                + "mInitialApplication left as "
                + ActivityThread.currentActivityThread().getApplication());

        /*
         * Game-dex hook groups (MainActivity#onCreate, Xbox sign-in) were
         * already installed right after HotFix merged the dex; re-creating them
         * here registered the same targets a second time.
         */

        /*
         * Context Fix
         */
        ContextFixer.fix(application);

        /*
         * Alter Environment
         */
        AlterEnvironment.establishEnv(
                mContext,
                application.getBaseContext()
        );

        /*
         * Minecraft Environment
         */
        MinecraftEnv.establishEnv(
                application.getBaseContext()
        );

        /*
         * Virtual Runtime
         */
        VirtualRuntime.setupRuntime(
                bindData.processName,
                bindData.appInfo
        );
    }

    /**
     * 启动 Launcher Activity
     */
    public void LaunchMinecraft(Activity activity) {

        try {

            String launcherClass =
                    "com.answer.launcher.ui.activity.LauncherActivity";

            Class<?> clazz =
                    Class.forName(
                            launcherClass,
                            true,
                            mContext.getClassLoader()
                    );

            Intent intent =
                    new Intent(
                            activity,
                            clazz
                    );

            intent.setFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
            );

            AlterInstrumentation
                    .get()
                    .startActivity(intent);

        } catch (Throwable e) {

            Log.e(
                    TAG,
                    "LaunchMinecraft failed",
                    e
            );
        }
    }

    /**
     * 创建 Service
     */
    @SuppressWarnings("deprecation")
    public Service createService(String name) {

        try {

            ClassLoader loader =
                    MinecraftManager
                            .getManager()
                            .getContext()
                            .getClassLoader();

            Class<?> serviceClass =
                    loader.loadClass(name);

            Service service =
                    (Service)
                            serviceClass
                                    .newInstance();

            /*
             * ContextImpl.setOuterContext()
             */
            Method setOuterContext =
                    Class.forName(
                            "android.app.ContextImpl"
                    ).getDeclaredMethod(
                            "setOuterContext",
                            Context.class
                    );

            setOuterContext.setAccessible(true);

            setOuterContext.invoke(
                    packageContext,
                    service
            );

            /*
             * Service.attach()
             */
            Method attach =
                    Service.class.getDeclaredMethod(
                            "attach",
                            Context.class,
                            ActivityThread.class,
                            String.class,
                            IBinder.class,
                            Application.class,
                            Object.class
                    );

            attach.setAccessible(true);

            attach.invoke(
                    service,
                    MinecraftManager
                            .getManager()
                            .getContext(),
                    ActivityThread.currentActivityThread(),
                    name,
                    getActivityThread(),
                    MinecraftManager
                            .getManager()
                            .getApplication(),
                    ActivityManagerNative.getDefault()
            );

            return service;

        } catch (ClassNotFoundException e) {

            throw new RuntimeException(
                    "Service class not found: "
                            + name,
                    e
            );

        } catch (
                InstantiationException |
                IllegalAccessException e
        ) {

            throw new RuntimeException(
                    "Unable to instantiate service: "
                            + name,
                    e
            );

        } catch (
                NoSuchMethodException |
                InvocationTargetException e
        ) {

            throw new RuntimeException(
                    "Error setting up service: "
                            + name,
                    e
            );

        } catch (Throwable e) {

            throw new RuntimeException(
                    "Unable to create service: "
                            + name,
                    e
            );
        }
    }

    /**
     * 创建 Service
     */
    @SuppressWarnings("deprecation")
    public Service createService(
            Context context,
            String name
    ) {

        try {

            ClassLoader loader =
                    MinecraftManager
                            .getManager()
                            .getContext()
                            .getClassLoader();

            Class<?> serviceClass =
                    loader.loadClass(name);

            Service service =
                    (Service)
                            serviceClass
                                    .newInstance();

            /*
             * ContextImpl.setOuterContext()
             */
            Method setOuterContext =
                    Class.forName(
                            "android.app.ContextImpl"
                    ).getDeclaredMethod(
                            "setOuterContext",
                            Context.class
                    );

            setOuterContext.setAccessible(true);

            setOuterContext.invoke(
                    context,
                    service
            );

            /*
             * Service.attach()
             */
            Method attach =
                    Service.class.getDeclaredMethod(
                            "attach",
                            Context.class,
                            ActivityThread.class,
                            String.class,
                            IBinder.class,
                            Application.class,
                            Object.class
                    );

            attach.setAccessible(true);

            attach.invoke(
                    service,
                    MinecraftManager
                            .getManager()
                            .getContext(),
                    ActivityThread.currentActivityThread(),
                    name,
                    getActivityThread(),
                    MinecraftManager
                            .getManager()
                            .getApplication(),
                    ActivityManagerNative.getDefault()
            );

            return service;

        } catch (ClassNotFoundException e) {

            throw new RuntimeException(
                    "Service class not found: "
                            + name,
                    e
            );

        } catch (
                InstantiationException |
                IllegalAccessException e
        ) {

            throw new RuntimeException(
                    "Unable to instantiate service: "
                            + name,
                    e
            );

        } catch (
                NoSuchMethodException |
                InvocationTargetException e
        ) {

            throw new RuntimeException(
                    "Error setting up service: "
                            + name,
                    e
            );

        } catch (Throwable e) {

            throw new RuntimeException(
                    "Unable to create service: "
                            + name,
                    e
            );
        }
    }

    /**
     * 获取 ApplicationThread Binder
     */
    public IBinder getActivityThread() {
        return (IBinder)
                ActivityThread
                        .currentActivityThread()
                        .getApplicationThread();
    }

    /**
     * Absolute device-protected data directory of the launcher, or null.
     * <p>
     * Context.getDeviceProtectedDataDir() is @hide in the compile SDK, so this
     * goes through the public createDeviceProtectedStorageContext() and reads
     * that context's data directory instead.
     */
    private String deviceProtectedDirPath() {
        try {
            Context deviceProtected = mContext.createDeviceProtectedStorageContext();
            if (deviceProtected != null && deviceProtected.getDataDir() != null) {
                return deviceProtected.getDataDir().getAbsolutePath();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 安全设置字段。
     * <p>
     * 某些 Android 版本没有对应字段时，
     * 不直接让整个 Runtime 崩溃。
     */
    private void setFieldIfExists(
            Object target,
            String fieldName,
            Object value
    ) {

        try {

            Class<?> clazz =
                    target.getClass();

            Field field = null;

            while (clazz != null) {

                try {

                    field =
                            clazz.getDeclaredField(
                                    fieldName
                            );

                    break;

                } catch (NoSuchFieldException e) {

                    clazz =
                            clazz.getSuperclass();
                }
            }

            if (field == null) {

                Log.w(
                        TAG,
                        "Field not found: "
                                + fieldName
                );

                return;
            }

            field.setAccessible(true);

            field.set(
                    target,
                    value
            );

        } catch (Throwable e) {

            Log.e(
                    TAG,
                    "Unable to set field: "
                            + fieldName,
                    e
            );
        }
    }

    /**
     * AppBindData
     */
    public static class AppBindData {

        int vpid;

        String processName;

        ApplicationInfo appInfo;

        List<ProviderInfo> providers;

        LoadedApk info;
    }
}