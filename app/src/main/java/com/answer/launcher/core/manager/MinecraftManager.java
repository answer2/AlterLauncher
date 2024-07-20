package com.answer.launcher.core.manager;

import android.app.LoadedApk;
import android.app.Application;
import android.app.ActivityThread;
import com.answer.launcher.api.SurfaceCallback;
import com.answer.launcher.core.tool.Reflector;
import android.app.Activity;
import android.content.Context;
import java.util.ArrayList;
import java.util.List;

/**
 * @Author AnswerDev 
 * @Date 2024/07/09 16:09
 */
public class MinecraftManager {

  private static volatile MinecraftManager manager;

  private LoadedApk loadedApk;
  private Application application;
  private ActivityThread currentActivityThread;
  private Activity mActivity;
  private Context mContext;
  private long handle;
  private List<SurfaceCallback> mCallbacks = new ArrayList<>();

  private MinecraftManager() {
    // private constructor to prevent instantiation
  }

  public static MinecraftManager getManager() {
    if (manager == null) {
      synchronized (MinecraftManager.class) {
        if (manager == null) {
          manager = new MinecraftManager();
        }
      }
    }
    return manager;
  }

  public void setHandle(long handle) {
    this.handle = handle;
  }

  public long getHandle() {
    return handle;
  }

  public MinecraftManager setActivity(Activity activity) {
    this.mActivity = activity;
    return this;
  }

  public Activity getActivity() {
    return mActivity;
  }

  public MinecraftManager setContext(Context context) {
    this.mContext = context;
    return this;
  }

  public Context getContext() {
    return mContext;
  }

  public MinecraftManager setLoadedApk(LoadedApk loadedApk) {
    this.loadedApk = loadedApk;
    return this;
  }

  public LoadedApk getLoadedApk() {
    return loadedApk;
  }

  public MinecraftManager setApplication(Application application) {
    this.application = application;
    return this;
  }

  public Application getApplication() {
    return application;
  }

  public void init() {
    try {
      this.currentActivityThread =
          Reflector.on(LoadedApk.class).field("mActivityThread").get(this.loadedApk);

    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  public void setActivityThread(ActivityThread currentActivityThread) {
    this.currentActivityThread = currentActivityThread;
  }

  public ActivityThread getActivityThread() {
    return currentActivityThread;
  }

  public List<SurfaceCallback> getCallbacks() {
    return this.mCallbacks;
  }

  public void addCallbacks(SurfaceCallback callback) {
    this.mCallbacks.add(callback);
  }
}
