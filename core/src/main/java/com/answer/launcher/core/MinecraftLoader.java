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
import com.answer.launcher.core.bridge.GameRuntime;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import me.weishu.reflection.Reflection;
import org.lsposed.hiddenapibypass.HiddenApiBypass;
import dev.answer.altercore.AlterConfig;

public class MinecraftLoader {

  private static final String TAG = "MinecraftLoader";

  /** Upper bound for blocking the calling thread while WebView boots on the main thread. */
  private static final long WEBVIEW_INIT_TIMEOUT_MS = 3000L;

  /** At most one assembly attempt per process. */
  private static volatile boolean assembled;

  static {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
          HiddenApiBypass.setHiddenApiExemptions("L");
      }

      // Single hook engine for the process. AlterCore's debug flag also gates
      // its per-call logging, which matters because several hooks sit on paths
      // as hot as java.io.File.<init>.
      AlterConfig.isDebug = LauncherConstants.isDebug;
  }

  /**
   * Assemble the Minecraft runtime in this process.
   *
   * <p>Runs from {@code :game}'s {@code Application#onCreate}, which is the only
   * hook early enough that the guest LoadedApk, its dex and the hooks are all in
   * place before the framework creates the first activity of that process. The
   * state transitions are published to {@link GameRuntime} so the UI process can
   * watch them over the bridge instead of guessing.
   *
   * <p>Idempotent: {@code GameHostActivity} keeps a fallback call for the case
   * where it is somehow started before the assembly ran, and a second pass through
   * {@code Alternative#init} would build a second guest LoadedApk.
   */
  public static void load(Context context) {
    if (assembled) {
      Log.i(TAG, "runtime already assembled in this process, skipping");
      return;
    }
    synchronized (MinecraftLoader.class) {
      if (assembled) {
        return;
      }
      assembled = true;
    }

    GameRuntime.setAssembling();
    try {
      // WebView must be initialised BEFORE the game identity is installed.
      // WebView binds its sandboxed renderer through
      // ActivityThread.currentApplication(), and Alternative#initEnv replaces
      // mInitialApplication with the game's Application. Deferring this with
      // Handler.post() (as before) let the queued Runnable run after that swap,
      // so the bind reported "com.mojang.minecraftpe" under the launcher's UID
      // and Android 16 killed the process with
      //   SecurityException: BIND_EXTERNAL_SERVICE failed,
      //       calling package not owned by calling UID
      initializeWebView(context);

      Reflection.unseal(context);
      // init Environment
      PluginManager.getManager(context);
      NativePluginManager.getManager(context);
      // Init Alternative Core
      Alternative.getManager().init(context);

      GameRuntime.setReady();

    } catch (Throwable e) {
      /*
       * Throwable, not Exception: an UnsatisfiedLinkError or VerifyError partway
       * through used to escape a catch that only covered Exception, which left a
       * half-assembled process that still looked healthy to every caller because
       * nobody had recorded the failure anywhere.
       */
      e.printStackTrace();
      GameRuntime.setFailed(e.toString());
    }
  }

  /**
   * Forces WebView (and its Chromium renderer service binding) to come up
   * while this process still presents its real package identity.
   */
  private static void initializeWebView(Context context) {
    Context supplied = context.getApplicationContext();
    if (supplied == null) {
      supplied = context;
    }
    final Context appContext = supplied;
    try {
      if (Looper.myLooper() == Looper.getMainLooper()) {
        new WebView(appContext);
      } else {
        // WebView must be created on the main thread; block until it is done
        // so the binding happens before the identity swap below.
        final CountDownLatch done = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
          try {
            new WebView(appContext);
          } catch (Throwable t) {
            Log.e(TAG, "WebView init failed", t);
          } finally {
            done.countDown();
          }
        });
        awaitWebViewReady(done);
      }
    } catch (Throwable t) {
      Log.e(TAG, "WebView init failed", t);
    }
  }

  private static void awaitWebViewReady(CountDownLatch done) {
    try {
      if (!done.await(WEBVIEW_INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
        Log.w(TAG, "WebView init timed out after " + WEBVIEW_INIT_TIMEOUT_MS + "ms");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      Log.w(TAG, "Interrupted waiting for WebView init", e);
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
