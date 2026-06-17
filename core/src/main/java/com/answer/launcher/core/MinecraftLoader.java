package com.answer.launcher.core;

import android.app.ActivityManager;
import android.app.ActivityThread;
import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.WebView;
import com.answer.launcher.core.hook.ActivityThreadHook;
import com.answer.launcher.core.hook.EnvironmentHook;
import com.answer.launcher.core.hook.LoadLibraryHook;
import com.answer.launcher.core.hook.UnixFileSystemHook;
import com.answer.launcher.core.manager.Alternative;
import com.answer.launcher.core.manager.NativePluginManager;
import com.answer.launcher.core.manager.PluginManager;
import com.answer.launcher.core.tool.Reflector;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import android.content.ContentResolver;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import android.annotation.SuppressLint;
import java.util.List;
import me.weishu.reflection.Reflection;
import org.lsposed.hiddenapibypass.HiddenApiBypass;
import top.canyie.pine.PineConfig;

public class MinecraftLoader {

  static {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
          HiddenApiBypass.setHiddenApiExemptions("L");
      }

      // initial Hook Framework
    PineConfig.debug = false;
    PineConfig.debuggable = true;
  }

  public static void load(Context context) {
    try {
            
      // Init WebView
        new Handler(Looper.getMainLooper()).post(()->{
            new WebView(context);
        });
      Reflection.unseal(context);
      // init Environment
      PluginManager.getManager(context);
      NativePluginManager.getManager(context);
      // Init Alternative Core
      Alternative.getManager().init(context);
            

    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private static String getProcessName(Context context) {
    int pid = Process.myPid();
    String processName = null;
    ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
    for (ActivityManager.RunningAppProcessInfo info : am.getRunningAppProcesses()) {
      Log.d("getProcessName", "" + info.processName);
      if (info.pid == pid) {
        processName = info.processName;
        break;
      }
    }
    if (processName == null) {
      throw new RuntimeException("processName = null");
    }
    return processName;
  }

  @SuppressLint("PrivateApi")
  private static void hookGlobalProviderHolder(final Context context) throws Exception {
    Class<?> mIContentProviderClass = Class.forName("android.content.IContentProvider");

    Field sProviderHolderFiled = Settings.Global.class.getDeclaredField("sProviderHolder");
    sProviderHolderFiled.setAccessible(true);
    Object sProviderHolder = sProviderHolderFiled.get(null);
    Method getProviderMethod =
        sProviderHolder.getClass().getDeclaredMethod("getProvider", ContentResolver.class);
    getProviderMethod.setAccessible(true);
    final Object iContentProvider =
        getProviderMethod.invoke(sProviderHolder, context.getContentResolver());
    Field mContentProviderFiled = sProviderHolder.getClass().getDeclaredField("mContentProvider");
    mContentProviderFiled.setAccessible(true);

    Object mContentProviderProxy =
        Proxy.newProxyInstance(
            context.getClassLoader(),
            new Class[] {mIContentProviderClass},
            new InvocationHandler() {
              @Override
              public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                if (method.getName().equals("call")) {
                  args[0] = context.getPackageName();
                }
                return method.invoke(iContentProvider, args);
              }
            });
    mContentProviderFiled.set(sProviderHolder, mContentProviderProxy);
  }

  private static void hookSystemProviderHolder(final Context context) throws Exception {
    Class<?> mIContentProviderClass = Class.forName("android.content.IContentProvider");

    Field sProviderHolderFiled = Settings.System.class.getDeclaredField("sProviderHolder");
    sProviderHolderFiled.setAccessible(true);
    Object sProviderHolder = sProviderHolderFiled.get(null);
    Method getProviderMethod =
        sProviderHolder.getClass().getDeclaredMethod("getProvider", ContentResolver.class);
    getProviderMethod.setAccessible(true);
    final Object iContentProvider =
        getProviderMethod.invoke(sProviderHolder, context.getContentResolver());
    Field mContentProviderFiled = sProviderHolder.getClass().getDeclaredField("mContentProvider");
    mContentProviderFiled.setAccessible(true);

    Object mContentProviderProxy =
        Proxy.newProxyInstance(
            context.getClassLoader(),
            new Class[] {mIContentProviderClass},
            new InvocationHandler() {
              @Override
              public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                if (method.getName().equals("call")) {
                  args[0] = context.getPackageName();
                }
                return method.invoke(iContentProvider, args);
              }
            });
    mContentProviderFiled.set(sProviderHolder, mContentProviderProxy);
  }
    
    private static String aqM = "";
    
    public static String getProcessName2(Context context) {
        List<ActivityManager.RunningAppProcessInfo> runningAppProcesses;
        if (!TextUtils.isEmpty(aqM)) {
            return aqM;
        }
        String str = "";
        String processName = Build.VERSION.SDK_INT >= 28 ? Application.getProcessName() : "";
        aqM = processName;
        if (!TextUtils.isEmpty(processName)) {
            return aqM;
        }
        return "";
    }

}
