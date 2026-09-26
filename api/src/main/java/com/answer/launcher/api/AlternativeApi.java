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

    /*
     * The interface is matched BY NAME on purpose: a plugin dex carries its own
     * copy of PluginInit loaded by the plugin's ClassLoader, so it is a different
     * Class object from the one in this module and `instanceof` / `==` on the Class
     * would reject a perfectly valid plugin. What was never valid is `==` on the
     * two name Strings - interned literals happen to make it work for the caller's
     * own constant, but a name coming out of a loaded dex is not interned and the
     * test then silently returns false, so the plugin is skipped without a word.
     */
    private static boolean checkIsPluginInit(Class<?> clazz) {
        return implementsByName(clazz, PluginInit.class.getName());
    }

    /* Direct interfaces only was enough while every plugin implemented PluginInit
     * itself; anything that reaches it through an intermediate interface was
     * invisible. Walk the hierarchy instead - cheap, and it cannot loop because a
     * class's interface graph is acyclic. */
    private static boolean implementsByName(Class<?> clazz, String name) {
        if (clazz == null) return false;
        for (Class<?> interfaced : clazz.getInterfaces()) {
            if (interfaced.getName().equals(name)) return true;
            if (implementsByName(interfaced, name)) return true;
        }
        return implementsByName(clazz.getSuperclass(), name);
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
