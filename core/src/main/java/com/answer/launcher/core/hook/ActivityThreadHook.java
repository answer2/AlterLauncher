package com.answer.launcher.core.hook;

import android.app.LoadedApk;

import java.lang.reflect.Field;

/**
 * @Author AnswerDev 
 * @Date 2024/07/08 16:48
 */
public class ActivityThreadHook extends BaseHook{

  public void init() {
    try {
      /*
      Pine.hook(ActivityThread.class.getDeclaredMethod("createBaseContextForActivity", Class.forName("android.app.ActivityThread$ActivityClientRecord")),
          new MethodHook(){
              @Override
              public void afterCall(Pine.CallFrame callFrame) throws Throwable {

                  ActivityClientRecord record = (ActivityClientRecord) callFrame.args[0];

                  Field intentF = Class.forName("android.app.ActivityThread$ActivityClientRecord").getDeclaredField("intent");
                  intentF.setAccessible(true);

                  Intent intent= (Intent) intentF.get(record);
                  String name = intent.getComponent().getClassName();
                  Log.i("createBaseContextForActivity", name);
                  LoadedApk loadedApk = MinecraftManager.getManager().getLoadedApk();
                  if ( name.contains("xbox") || name.contains("microsoft")){
                      Log.i("performLaunchActivity", "true");
                      callFrame.setResult(Alternative.getManager().mContext);
                      Reflector
                      .on("android.app.ContextImpl")
                      .method("setResources", Resources.class)
                      .callByCaller(callFrame.getResult(), MinecraftManager.getManager().getContext().getResources());

                  }

              }
          });*/

      //  public final LoadedApk getPackageInfo(ApplicationInfo ai, CompatibilityInfo compatInfo,
      //  int flags) {
      // com.mojang.minecraftpf
            /*
      Pine.hook(
          ActivityThread.class.getDeclaredMethod(
              "getPackageInfo",
              ApplicationInfo.class,
              Class.forName("android.content.res.CompatibilityInfo"),
              int.class),
          new MethodHook() {
            @Override
            public void beforeCall(Pine.CallFrame callFrame) throws Throwable {
              ApplicationInfo info = (ApplicationInfo) callFrame.args[0];

              LoadedApk loadedApk = MinecraftManager.getManager().getLoadedApk();
              if (info.packageName.contains(Constants.MINECRAFTPACKAGE)) {
                Log.i("performLaunchActivity", "true");
                loadedApk.getApplicationInfo().uid =
                    Alternative.getManager().mContext.getApplicationInfo().uid;
                callFrame.setResult(loadedApk);
              }
            }
          });*/
            /*

      Pine.hook(
          ActivityThread.class.getDeclaredMethod(
              "performLaunchActivity",
              Class.forName("android.app.ActivityThread$ActivityClientRecord"),
              Intent.class),
          new MethodHook() {
            @Override
            public void beforeCall(Pine.CallFrame callFrame) throws Throwable {
              ActivityClientRecord record = (ActivityClientRecord) callFrame.args[0];

              Field intentF =
                  Class.forName("android.app.ActivityThread$ActivityClientRecord")
                      .getDeclaredField("intent");
              intentF.setAccessible(true);

              Intent intent = (Intent) intentF.get(record);
              String name = intent.getComponent().getClassName();

              LoadedApk loadedApk = MinecraftManager.getManager().getLoadedApk();
              Log.i("performLaunchActivity", name);
            /*  if (name.contains(Constants.MINECRAFTACTIVITY)
                  || name.contains("xbox")
                  || name.contains("microsoft")) {
                Log.i("performLaunchActivity", "true");
                record.packageInfo = loadedApk;
              }
            }
          });
            */
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private static void setValue(LoadedApk loadedApk, String field, Object value) throws Exception {
    Field mClassLoaderField = LoadedApk.class.getDeclaredField(field);
    mClassLoaderField.setAccessible(true);
    mClassLoaderField.set(loadedApk, value);
  }
}
