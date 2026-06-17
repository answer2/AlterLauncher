package com.answer.launcher.core.env;

import android.app.Activity;
import android.app.ActivityThread;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import com.answer.launcher.core.LauncherConstants;
import com.answer.launcher.core.manager.Alternative;
import com.answer.launcher.core.tool.Reflector;
import java.lang.reflect.Field;

import com.answer.launcher.core.manager.MinecraftManager;

/**
 * @Author AnswerDev 
 * @Date 2024/07/06 11:17
 */

public class AlterInstrumentation extends Instrumentation {

  private static AlterInstrumentation instrumentation;
  private Instrumentation mBase;

  public static AlterInstrumentation get() {
    if (instrumentation == null) {
      synchronized (AlterInstrumentation.class) {
        if (instrumentation == null) {
          instrumentation = new AlterInstrumentation();
        }
      }
    }
    return instrumentation;
  }

  public AlterInstrumentation() {
    try {
      Reflector.on(Instrumentation.class)
          .method("basicInit", ActivityThread.class)
          .callByCaller(this, ActivityThread.currentActivityThread());
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  public void startActivity(Intent intent) {
    try {
      Reflector.on(Instrumentation.class)
          .method("execStartActivity",
              Context.class, IBinder.class, IBinder.class, Activity.class, Intent.class, int.class, Bundle.class)
          .callByCaller(this, MinecraftManager.getManager().getApplication().getBaseContext(),
              (IBinder) MinecraftManager.getManager().getActivityThread().getApplicationThread(),
              (IBinder) null,
              (Activity) null,
              intent,
              -1,
              (Bundle) null);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  @Override
  public void callApplicationOnCreate(Application app) {
    Log.d("callApplicationOnCreate", app.getPackageName());

    super.callApplicationOnCreate(app);
  }
    
    @Override
    public void callActivityOnCreate(Activity activity, Bundle arg1) {
        super.callActivityOnCreate(activity, arg1);
        // TODO: Implement this method
        if(activity!=null&&(!activity.getClass().getName().contains(LauncherConstants.PACKAGE))){
             exchangeResourcesOfActivity(activity);
        }
    }
    
    @Override
    public void callActivityOnNewIntent(Activity arg0, Intent arg1) {
        super.callActivityOnNewIntent(arg0, arg1);
        // TODO: Implement this method
    }
    
    @Override
    public boolean onException(Object arg0, Throwable arg1) {
        // TODO: Implement this method
        Log.d("onException",  arg0+"");
        arg1.printStackTrace();
        return super.onException(arg0, arg1);
    }
    
    @Override
    public Activity newActivity(ClassLoader arg0, String arg1, Intent arg2) throws
            ClassNotFoundException, IllegalAccessException, InstantiationException {
        // TODO: Implement this method
        Activity newActivity = super.newActivity(arg0, arg1, arg2);
        if(newActivity!=null&&!newActivity.getClass().getName().contains(LauncherConstants.PACKAGE)){
             exchangeResourcesOfActivity(newActivity);
        }
        return newActivity;
    }

  public void initHook() {
    try {
      Instrumentation mInstrumentation = getCurrInstrumentation();
      if (mInstrumentation == this || checkInstrumentation(mInstrumentation)) return;
      setField(
          ActivityThread.currentActivityThread(), ActivityThread.class, "mInstrumentation", this);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private Instrumentation getCurrInstrumentation() throws Exception {
    return (Instrumentation)
        getField(ActivityThread.class, "mInstrumentation", ActivityThread.currentActivityThread());
  }

  private static Object getField(Class<?> c, String fieldName, Object o) throws Exception {
        return Reflector.on(c)
        .field(fieldName)
        .get(o);
  }

  private static void setField(Object obj, Class<?> c, String fieldName, Object value)
      throws Exception {
     Reflector.on(c)
        .field(fieldName)
        .set(obj, value);
  }

  private boolean checkInstrumentation(Instrumentation instrumentation) {
    if (instrumentation instanceof AlterInstrumentation) {
      return true;
    }
    Class<?> clazz = instrumentation.getClass();
    if (Instrumentation.class.equals(clazz)) {
      return false;
    }
    do {
      Field[] fields = clazz.getDeclaredFields();
      if (fields != null) {
        for (Field field : fields) {
          if (Instrumentation.class.isAssignableFrom(field.getType())) {
            field.setAccessible(true);
            Object obj;
            try {
              obj = field.get(instrumentation);
            } catch (IllegalAccessException e) {
              return false;
            }
            if ((obj instanceof AlterInstrumentation)) {
              return true;
            }
          }
        }
      }
      clazz = clazz.getSuperclass();
    } while (!Instrumentation.class.equals(clazz));
    return false;
  }
    
    /**
     * 反射 Activity , 并设置 Activity 中 Resources 成员变量
     * @param activity
     */
    private void exchangeResourcesOfActivity(Activity activity) {
        try {
            Class<?> contextThemeWrapperClass = Class.forName("android.view.ContextThemeWrapper");
            Reflector.on(contextThemeWrapperClass)
            .field("mResources").set(activity, Alternative.getManager().getContext().getResources());
            
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
