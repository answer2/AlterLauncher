package com.answer.launcher.core.hook;

import android.app.Activity;
import android.app.ActivityThread;
import android.app.Application;
import android.app.ContentProviderHolder;
import android.app.Instrumentation;
import android.app.LoadedApk;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.webkit.WebView;
import com.answer.launcher.core.Constants;
import com.answer.launcher.core.env.AlterInstrumentation;
import com.answer.launcher.core.manager.Alternative;
import com.answer.launcher.core.manager.NativeCore;
import com.answer.launcher.core.tool.AssetOverrideManager;
import com.answer.launcher.core.tool.HotFix;
import com.answer.launcher.core.tool.Reflector;
import com.answer.launcher.core.tool.SystemUtil;
import com.mojang.minecraftpe.MainActivity;
import java.util.Arrays;
import java.util.List;
import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;
import top.canyie.pine.callback.MethodReplacement;

public class NeteaseHook {

  private static ClassLoader NewClassLoader;

  public static void init(final Context context, final LoadedApk loadedApkN) throws Exception {
/*
    Pine.hook(
        Context.class.getDeclaredMethod("getOpPackageName"),
        new MethodHook() {
          @Override
          public void beforeCall(Pine.CallFrame params) throws Throwable {
            super.beforeCall(params);
            Log.i("getOpPackageName", Arrays.toString(params.args));
            params.args[3] = Constants.MINECRAFTPACKAGE;
          }
        });*/

    /*


    private Intent registerReceiverInternal(BroadcastReceiver receiver, int userId,
            IntentFilter filter, String broadcastPermission,
            Handler scheduler, Context context, int flags) {

        */
    Pine.hook(
        Class.forName("android.app.ContextImpl")
            .getDeclaredMethod(
                "registerReceiverInternal",
                BroadcastReceiver.class,
                int.class,
                IntentFilter.class,
                String.class,
                Handler.class,
                Context.class,
                int.class),
        new MethodHook() {
          @Override
          public void beforeCall(Pine.CallFrame params) throws Throwable {
            super.beforeCall(params);
            Reflector field = Reflector.on("android.app.ContextImpl").field("mBasePackageName");
            Log.i("getOpPackageName", field.get(params.thisObject));
            field.set(params.thisObject, Constants.PACKAGE);
          }
        });


    // Provider Authority is Null
    //  at android.app.ActivityThread$ProviderClientRecord
    // android.app.ActivityThread.installProviderAuthoritiesLocked(android.content.IContentProvider,
    // android.content.ContentProvider, android.app.ContentProviderHolder)
    // (ActivityThread.java:7713)
    Pine.hook(
        ActivityThread.class.getDeclaredMethod(
            "installContentProviders", Context.class, List.class),
        new MethodHook() {
          @Override
          public void beforeCall(Pine.CallFrame params) throws Throwable {
            super.beforeCall(params);
            params.args[0] = context;
            params.args[1] = Alternative.mProviders;
          }
        });

    PackageInfo packageInfo =
        context
            .getApplicationContext()
            .getPackageManager()
            .getPackageInfo(Constants.MINECRAFTPACKAGE, PackageManager.GET_PROVIDERS);
    List<ProviderInfo> providers = Arrays.asList(packageInfo.providers);
    Pine.hook(
        ActivityThread.class.getDeclaredMethod(
            "installProviderAuthoritiesLocked",
            Class.forName("android.content.IContentProvider"),
            ContentProvider.class,
            ContentProviderHolder.class),
        new MethodHook() {
          @Override
          public void beforeCall(Pine.CallFrame params) throws Throwable {
            super.beforeCall(params);
            ContentProvider contentProvider = (ContentProvider) params.args[1];
            ContentProviderHolder holder = (ContentProviderHolder) params.args[2];
            if (holder.info.authority == null
                    && (holder.info.name.contains(
                        "com.reactnative.ivpusic.imagepicker.MCFileProvider"))
                || holder.info.name.contains("androidx.startup.InitializationProvider")
                || holder.info.name.contains(
                    "com.reactnative.ivpusic.imagepicker.MCFileProvider")) {
              holder.info.authority = Constants.MINECRAFTPACKAGE+".provider";
            }
          }
        });

    // Ues AlterInstrumentation
    Pine.hook(
        LoadedApk.class.getDeclaredMethod("makeApplication", boolean.class, Instrumentation.class),
        new MethodHook() {

          @Override
          public void beforeCall(Pine.CallFrame params) {
            try {
              super.beforeCall(params);
              if (params.args[1] != null && NewClassLoader != null) {
                LoadedApk loadedApk = (LoadedApk) params.thisObject;
                Reflector.on(LoadedApk.class)
                    .field("mClassLoader")
                    .set(loadedApk, context.getClassLoader());
                /*
                Reflector.on(LoadedApk.class)
                    .field("mPackageName")
                    .set(loadedApk, Constants.PACKAGE);
                Log.i("NeteaseHook", loadedApk.getPackageName());*/
                params.args[1] = AlterInstrumentation.get();
              }
            } catch (Throwable e) {
              e.printStackTrace();
            }
          }
        });

    if (SystemUtil.checkClass("com.netease.android.protect.StubApp")) {
            
    // check PackageName???
    Pine.hook(
        Class.forName("com.netease.android.protect.ShellSupporter")
            .getDeclaredMethod(
                "initNative", String.class, String.class, String.class, String.class, int.class),
        new MethodHook() {
          @Override
          public void beforeCall(Pine.CallFrame params) throws Throwable {
            super.beforeCall(params);
            Log.i("NeteaseHook", Arrays.toString(params.args));
            params.args[3] = Constants.PACKAGE;
          }
        });
            
      // Give Classloader For Native
      Pine.hook(
          Class.forName("com.netease.android.protect.StubApp")
              .getDeclaredMethod("II0IOI00", ClassLoader.class),
          new MethodHook() {
            @Override
            public void beforeCall(Pine.CallFrame params) throws Throwable {
              super.beforeCall(params);
              NewClassLoader = (ClassLoader) params.args[0];
              HotFix.hotFix(context, NewClassLoader);

              hook(context);
            }
          });

      // Check ApkFile Length
      // base.apk
      Pine.hook(
          Class.forName("com.netease.android.protect.a").getDeclaredMethod("b", Context.class),
          new MethodHook() {
            @Override
            public void afterCall(Pine.CallFrame params) throws Throwable {
              super.afterCall(params);
              params.setResult(true);
            }
          });

      // Check PackageName
      Pine.hook(
          Class.forName("IOoOo00o0II.a").getDeclaredMethod("c", Context.class),
          new MethodHook() {
            @Override
            public void afterCall(Pine.CallFrame params) throws Throwable {
              super.afterCall(params);
              params.setResult(true);
            }
          });

      // For Fix Resources
      Pine.hook(
          Class.forName("com.netease.android.protect.StubApp").getDeclaredMethod("onCreate"),
          new MethodHook() {
            @Override
            public void beforeCall(Pine.CallFrame params) {
              try {
                super.beforeCall(params);
                Application application = (Application) params.thisObject;
                Log.i("StubApp", application.getResources() + "");
                AssetOverrideManager.addAssetOverride(
                    application.getAssets(), application.getApplicationInfo().publicSourceDir);
              } catch (Throwable e) {
                e.printStackTrace();
              }
            }
          });
    }else{
        hook(context);
    }
  }

  private static void hook(Context context) {
    try {
      // public void attachInfo(Context context, ProviderInfo providerInfo) {
      Pine.hook(
          Class.forName("androidx.core.content.FileProvider")
              .getDeclaredMethod("attachInfo", Context.class, ProviderInfo.class),
          new MethodHook() {
            @Override
            public void beforeCall(Pine.CallFrame params) throws Throwable {
              super.beforeCall(params);
              Log.i("NeteaseHook", Arrays.toString(params.args));
              ProviderInfo info = (ProviderInfo) params.args[1];
              if (info.authority == null) info.authority = Constants.MINECRAFTPACKAGE +".provider";
            }
          });
            
            //1723340567.030 10940 15471 15471 W System.err: 	at com.netease.ntunisdk.external.protocol.view.UniWebView$Config.getJS(UniWebView.java:633)
         /*   Pine.hook(
          Class.forName("com.netease.ntunisdk.external.protocol.view.UniWebView$Config")
              .getDeclaredMethod("getJS", Context.class, String.class),
          new MethodHook() {
            @Override
            public void beforeCall(Pine.CallFrame params) throws Throwable {
              super.beforeCall(params);
              Log.i("NeteaseHook-getJS", Arrays.toString(params.args));
                    params.args[0] = context;
              }
          });*/


      Pine.hook(
          Class.forName("com.mojang.minecraftpe.AppContext")
              .getDeclaredMethod("attachBaseContext", Context.class),
          new MethodHook() {
            @Override
            public void beforeCall(Pine.CallFrame params) {
              try {
                super.beforeCall(params);
                Context application = (Context) params.args[0];
                fixSplashEnv(application);
                AssetOverrideManager.addAssetOverride(
                    application.getAssets(), application.getApplicationInfo().publicSourceDir);
                Log.d("AppContext", "Loaded");
                            /*
                            Reflector.on("com.netease.androidcrashhandler.BuildConfig").field("DEBUG").set(null,true);
                         Object o = Reflector.on("com.netease.androidcrashhandler.AndroidCrashHandler").method("getInstance").call();
                            Reflector.on("com.netease.androidcrashhandler.AndroidCrashHandler").method("start", Context.class).callByCaller(o, application);
                            
                            Object oo = Reflector.on("com.netease.androidcrashhandler.init.InitProxy").method("getInstance").call();
                            Reflector.on("com.netease.androidcrashhandler.init").method("init", Context.class).callByCaller(oo,application);*/
              } catch (Throwable e) {
                e.printStackTrace();
              }
            }

            @Override
            public void afterCall(Pine.CallFrame params) {
              try {
                super.beforeCall(params);
                Application application = (Application) params.thisObject;

              } catch (Throwable e) {
                e.printStackTrace();
              }
            }
          });

      // Fix Splash Resources Bug
      Pine.hook(
          Class.forName("com.netease.ntunisdk.external.protocol.utils.ResUtils")
              .getDeclaredMethod("getResId", Context.class, String.class, String.class),
          new MethodReplacement() {
            @Override
            public Object replaceCall(Pine.CallFrame params) {
              try {
                //context.getResources().getIdentifier(str, str2, context.getPackageName());
                Context context = (Context) params.args[0];
                String name = (String) params.args[1];
                String type = (String) params.args[2];
                Class<?> clazz = Class.forName(Constants.MINECRAFTPACKAGE+".R$" + type);
                int id = (int) Reflector.on(clazz).field(name).get();
                return id;
              } catch (Exception e) {
                return 0;
              }
            }
          });

    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private static void fixSplashEnv(final Context context) throws Exception {

    // Alternative.getManager().createService(context,"com.netease.pushservice.PushService");

    // strange WebView
    Pine.hook(
        Class.forName("com.netease.ntunisdk.ApplicationNGWebview")
            .getDeclaredMethod("handleOnApplicationOnCreate", Context.class, Application.class),
        MethodReplacement.DO_NOTHING);

        Pine.hook(
        Class.forName("com.mojang.minecraftpe.MainActivity")
            .getDeclaredMethod("nativeResize", int.class, int.class),
        new MethodHook() {
          @Override
          public void beforeCall(Pine.CallFrame params) {
            try {
              super.beforeCall(params);
              Log.i("MCP", Arrays.toString(params.args));
              Log.i("MCP", "nativeResize");
            } catch (Throwable e) {
              e.printStackTrace();
            }
          }
        });
        
        Pine.hook(
        Class.forName("com.mojang.minecraftpe.MainActivity")
            .getDeclaredMethod("onStart"),
        new MethodHook() {
          @Override
          public void beforeCall(Pine.CallFrame params) {
            try {
              super.beforeCall(params);
              Log.i("MCP", Arrays.toString(params.args));
              Log.i("MCP", "onStart" +Reflector.on("com.mojang.minecraftpe.MainActivity").field("activityInited").get(params.thisObject));
            } catch (Throwable e) {
              e.printStackTrace();
            }
          }
        });
        
        
    Pine.hook(
        Class.forName("com.mojang.minecraftpe.MainActivity")
            .getDeclaredMethod("onCreate", Bundle.class),
        new MethodHook() {
          @Override
          public void beforeCall(Pine.CallFrame params) {
            try {
              super.beforeCall(params);
              MainActivity activity = (MainActivity)params.thisObject;
             // Reflector.on(Activity.class).method("nativeResize", int.class, int.class).callByCaller(activity,500,500);
              
                        NativeCore.loadManager();
              
            } catch (Throwable e) {
              e.printStackTrace();
            }
          }
        });

    Pine.hook(
        Class.forName("com.mojang.minecraftpe.NetworkMonitor")
            .getDeclaredMethod(
                "nativeUpdateNetworkStatus", boolean.class, boolean.class, boolean.class),
        new MethodHook() {
          @Override
          public void beforeCall(Pine.CallFrame params) {
            try {
              super.beforeCall(params);
              Log.i("MCP", Arrays.toString(params.args));
              Log.i("MCP", "nativeUpdateNetworkStatus");
            } catch (Throwable e) {
              e.printStackTrace();
            }
          }
        });

    /*

    Pine.hook(
      Class.forName("com.mojang.minecraftpe.MainActivity").getDeclaredMethod("nativeWaitCrashManagementSetupComplete"),
      MethodReplacement.DO_NOTHING);

    Pine.hook(
      Class.forName("com.mojang.minecraftpe.MainActivity").getDeclaredMethod("configureBrazeAtRuntime"),
      MethodReplacement.DO_NOTHING);*/
    /*
    Pine.hook(
      Class.forName("com.mojang.minecraftpe.MainActivity").getDeclaredMethod("nativeCopyInnerAsset"),
      MethodReplacement.DO_NOTHING);*/

    Pine.hook(
        Class.forName("com.mojang.minecraftpe.MainActivity")
            .getDeclaredMethod("initLibHTTPClient", Context.class),
        new MethodHook() {
          @Override
          public void beforeCall(Pine.CallFrame params) {
            try {
              super.beforeCall(params);
              Log.i("MCP", Arrays.toString(params.args));
                        Log.i("MCP", "loadManager");
                        
              
              Log.i("MCP", "initLibHTTPClient:"+Reflector.on("com.mojang.minecraftpe.MainActivity").field("activityInited").get(params.thisObject));
            } catch (Throwable e) {
              e.printStackTrace();
            }
          }
        });

    
  }
}
