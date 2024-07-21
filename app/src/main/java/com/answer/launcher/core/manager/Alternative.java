package com.answer.launcher.core.manager;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityThread;
import android.app.Application;
import android.app.LoadedApk;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import com.answer.launcher.core.hook.ActivityThreadHook;
import com.answer.launcher.core.tool.SystemUtil;
import java.lang.reflect.Method;
import android.app.ActivityManagerNative;
import java.lang.reflect.InvocationTargetException;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.ProviderInfo;
import android.content.res.AssetManager;
import com.answer.launcher.core.Constants;
import com.answer.launcher.core.env.MinecraftEnv;
import com.answer.launcher.core.hook.XboxHook;
import com.answer.launcher.core.env.VirtualRuntime;
import com.answer.launcher.core.tool.ContextFixer;
import com.answer.launcher.core.tool.HotFix;
import com.answer.launcher.core.tool.Reflector;
import com.answer.launcher.core.env.AlterEnvironment;
import com.answer.launcher.core.env.AlterInstrumentation;
import dalvik.system.DexClassLoader;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * @Author AnswerDev 
 * @Date 2024/04/05 08:34
 */
public class Alternative {
  private static volatile Alternative manager;
  private static List<ProviderInfo> mProviders = new ArrayList<>();

  public Context mContext;

  private ApplicationInfo applicationInfo;
  private Object boundApplication;
  private Context packageContext;
  private Application application;
  private LoadedApk loadedApk;
  private AppBindData bindData;
    public static String nativepath;
    
    public static native long findLibBaseAddress(String library);
    public static native long loadLibrary(String libPath);
    public static native void unloadLibrary(long handle);

    public static native void initAssets(AssetManager manager);
    public static native void hookDlopen();
    public static native void loadManager();

  public void init(Context context) {
    this.mContext = context;
    try{
        createLoadedApk();
        createApplication();
        initEnv();
       }catch(Exception e){
           e.printStackTrace();
       }
  }

  private Alternative() {}

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
    
    public static void LaunchMinecraft(Activity activity) {
    	try {
            Class<?> clazz =
                Class.forName("com.answer.launcher.ui.activity.LauncherActivity", false, activity.getClassLoader());
            Intent intent = new Intent(activity, clazz);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if(SystemUtil.checkClass(Constants.MINECRAFTACTIVITY)) AlterInstrumentation.get().startActivity(intent);
          } catch (Exception e) {
            e.printStackTrace();
          }
    }

  @SuppressWarnings("unchecked")
  private void createLoadedApk() throws Exception {
    // Get current ActivityThread instance
    ActivityThread currentActivityThread =
        (ActivityThread) Reflector.on(ActivityThread.class).field("sCurrentActivityThread").get();
    // Get mBoundApplication field
    boundApplication =
        Reflector.on(ActivityThread.class).field("mBoundApplication").get(currentActivityThread);

    // Get package info for the target application
    PackageInfo packageInfo =
        mContext
            .getApplicationContext()
            .getPackageManager()
            .getPackageInfo(Constants.MINECRAFTPACKAGE, PackageManager.GET_PROVIDERS);
    applicationInfo = packageInfo.applicationInfo;

    // init info
    Constants.MINECRAFT_VERSION = packageInfo.versionName;
        nativepath = packageInfo.applicationInfo.nativeLibraryDir;

    // Add providers to mProviders list
    if (packageInfo.providers == null) {
      packageInfo.providers = new ProviderInfo[] {};
    }
    mProviders.addAll(Arrays.asList(packageInfo.providers));

    // Create package context
    packageContext =
        mContext.createPackageContext(
            Constants.MINECRAFTPACKAGE,
            Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);

    // Get LoadedApk instance
    loadedApk =
        (LoadedApk)
            Reflector.on("android.app.ContextImpl").field("mPackageInfo").get(packageContext);

    // Set necessary fields in loadedApk
    Reflector.on(LoadedApk.class).field("mSecurityViolation").set(loadedApk, false);
    Reflector.on(LoadedApk.class).field("mApplicationInfo").set(loadedApk, applicationInfo);
  }

  private void createApplication() throws Exception {

    // Create AppBindData instance
    bindData = new AppBindData();
    bindData.appInfo = applicationInfo;
    bindData.processName = "Minecraft-AlterCore";
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

    // create application
    application = loadedApk.makeApplication(false, AlterInstrumentation.get());

    // load apk
    String optimizedDirectory = mContext.getDir("plugin", Context.MODE_PRIVATE).getAbsolutePath();
    DexClassLoader classLoader =
        new DexClassLoader(
            applicationInfo.sourceDir,
            optimizedDirectory,
            loadedApk.getLibDir(),
            mContext.getClassLoader());
    // hotfix
    HotFix.hotFix(mContext, classLoader);

    // set ClassLoader
    Reflector.on(LoadedApk.class).field("mClassLoader").set(loadedApk, mContext.getClassLoader());
  }

  private void initEnv() throws Exception {

    MinecraftManager.getManager()
        .setLoadedApk(loadedApk)
        .setApplication(application)
        .setContext(packageContext)
        .init();

    // Replace mInitialApplication
    Reflector.on(ActivityThread.class)
        .field("mInitialApplication")
        .set(ActivityThread.currentActivityThread(), application);

    // Fix context if necessary
    ContextFixer.fix(application);

    IOManager.get().enableRedirect(application.getBaseContext());

    // Hook Instrumentation
    AlterInstrumentation.get().initHook();

    // Call Application
    AlterInstrumentation.get().callApplicationOnCreate(application);

    // Establish AlterEnvironment
    AlterEnvironment.establishEnv(mContext, application.getBaseContext());

    // Establish Minecraft Env
    MinecraftEnv.establishEnv(application.getBaseContext());

    // hook
    XboxHook.init();

    System.loadLibrary("alternative_core");
        
    ActivityThreadHook.init();

    // init assets
    initAssets(loadedApk.getAssets());
        
    

    // Establish VM Runtime
    VirtualRuntime.setupRuntime(bindData.processName, bindData.appInfo);
  }
    
    public Service createService(String name) {
        try {
            // 使用反射实例化Service对象
            Service service = (Service) MinecraftManager.getManager().getContext().getClassLoader().loadClass(name).newInstance();

            // 通过反射获取ContextImpl的setOuterContext方法并设置为可访问
            Method setOuterContext = Class.forName("android.app.ContextImpl").getDeclaredMethod("setOuterContext", Context.class);
            setOuterContext.setAccessible(true);

            // 通过反射获取Service类的attach方法并设置为可访问
            Method attach = Service.class.getDeclaredMethod("attach",
                                                            android.content.Context.class, 
                                                            android.app.ActivityThread.class,
                                                            String.class, 
                                                            android.os.IBinder.class, 
                                                            android.app.Application.class, 
                                                            Object.class);
            attach.setAccessible(true);

            // 调用attach方法进行初始化
            attach.invoke(service, 
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
        return(IBinder) ActivityThread.currentActivityThread().getApplicationThread();
    }

  public static class AppBindData {
    int vpid;
    String processName;
    ApplicationInfo appInfo;
    List<ProviderInfo> providers;
    LoadedApk info;
  }
}
