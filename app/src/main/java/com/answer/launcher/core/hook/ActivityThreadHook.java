package com.answer.launcher.core.hook;

import android.app.ActivityThread;
import android.app.ActivityThread.ActivityClientRecord;
import android.app.LoadedApk;
import android.content.Intent;
import com.answer.launcher.core.Constants;
import java.lang.reflect.Field;
import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;
import com.answer.launcher.core.manager.MinecraftManager;

/**
 * @Author AnswerDev
 * @Date 2024/07/08 16:48
 */
public class ActivityThreadHook {

    public static void init() {
        try {
            Pine.hook(ActivityThread.class.getDeclaredMethod("createBaseContextForActivity", Class.forName("android.app.ActivityThread$ActivityClientRecord")),
                new MethodHook(){
                    @Override
                    public void beforeCall(Pine.CallFrame callFrame) throws Throwable {

                        ActivityClientRecord record = (ActivityClientRecord) callFrame.args[0];
                        
                        Field intentF = Class.forName("android.app.ActivityThread$ActivityClientRecord").getDeclaredField("intent");
                        intentF.setAccessible(true);

                        Intent intent= (Intent) intentF.get(record); 
                        String name = intent.getComponent().getClassName();
                        
                        LoadedApk loadedApk = MinecraftManager.getManager().getLoadedApk();
                        if (name.contains(Constants.MINECRAFTACTIVITY)) {
                            LoadedApk loadedApkP = record.packageInfo;
                            setValue(loadedApkP, "mResources", loadedApk.getResources());
                            setValue(loadedApkP, "mResDir", loadedApk.getResDir());
                            setValue(loadedApkP, "mLibDir", loadedApk.getLibDir());
                            callFrame.args[0]  = record;
                        }

                    }
                });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private static void setValue(LoadedApk loadedApk, String field, Object value) throws Exception{
        Field mClassLoaderField = LoadedApk.class.getDeclaredField(field);
        mClassLoaderField.setAccessible(true);
        mClassLoaderField.set(loadedApk, value);
    }
}
