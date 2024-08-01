package com.answer.launcher.api;

import android.util.Log;
import java.lang.reflect.Method;

/**
 * @Author AnswerDev
 * @Date 2024/07/11 21:16
 */
public class AlternativeApi {

    private static PluginInfo mPluginInfo;
    private static LauncherInfo mLauncherInfo;

    private static PluginInfo init(LauncherInfo LauncherInfo) {
        if (mLauncherInfo == null) mLauncherInfo = LauncherInfo;
        checkLauncherIsNull();
        try {
            Class<?> clazz = Class.forName(mLauncherInfo.getInit(), true, LauncherInfo.getClassLoader());
            if (checkIsPluginInit(clazz)) {
                PluginInit object = (PluginInit) clazz.newInstance();
                object.load(mLauncherInfo);
                if (object.initInfo() == null) mPluginInfo = new PluginInfo();
                else mPluginInfo = object.initInfo();
            }

        } catch (Exception e) {
            Log.e("PluginLoader", "Error", e);
        }
        return mPluginInfo;
    }

    /*
     * @name 插件名称
     * @author 插件作者
     * @version 插件版本
     * @init 插件的入口
     */
    private static void initInfo(String name, String author, String version, String description) {
        if (mPluginInfo == null) mPluginInfo = new PluginInfo(name, author, version, description);
    }

    private static void checkLauncherIsNull() {
        if (mLauncherInfo == null) {
            throw new NullPointerException("mLauncherInfo cannot be null");
        }
    }

    private static boolean checkIsPluginInit(Class<?> clazz) {
        for (Class<?> interfaced : clazz.getInterfaces())
            if (interfaced.getName() == PluginInit.class.getName()) return true;
        return false;
    }

    public static void addCallback(SurfaceCallback callback) {
        if(callback != null){
            try{
            Class<?> clazz = Class.forName("com.answer.launcher.core.manager.MinecraftManager");
            Method manager = clazz.getDeclaredMethod("getManager");
            Method add = clazz.getDeclaredMethod("addCallbacks", SurfaceCallback.class);
            add.invoke(manager.invoke(null), callback);
         }catch(Exception e){
             e.printStackTrace();
         }
        } 
    }
}
