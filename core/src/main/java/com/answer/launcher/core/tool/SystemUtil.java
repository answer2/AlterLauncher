package com.answer.launcher.core.tool;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.util.AbstractMap;
import java.util.Map;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Objects;
import java.util.Collections;

public class SystemUtil {
    public static boolean checkClass(String clazz) {
    	// The launcher loader is tried first, then the guest loader: since the game
    	// dex stopped being merged into the launcher's PathClassLoader, classes that
    	// live only there (com.mojang.minecraftpe.*, com.microsoft.xal.*) resolve
    	// exclusively through the guest DexClassLoader.
    	if (present(clazz, SystemUtil.class.getClassLoader())) {
            return true;
        }
        ClassLoader guest = com.answer.launcher.core.hook.HookProvider.getGameClassLoader();
        return guest != null && guest != SystemUtil.class.getClassLoader()
                && present(clazz, guest);
    }

    private static boolean present(String clazz, ClassLoader loader) {
    	try {
            Class.forName(clazz, false, loader);
            return true;
        }catch(Throwable t){
            // NoClassDefFoundError, not just ClassNotFoundException: a class whose
            // super-class is absent (com.mojang.minecraftpe.NotificationListenerService
            // extends com.google.firebase.messaging.FirebaseMessagingService, which the
            // launcher never ships) arrives as an Error. The old "catch (Exception)"
            // let it escape, out of LoadedAfterHook.init and out of
            // Instrumentation.callActivityOnCreate, aborting activity creation.
            return false;
        }
    }
    
    public static boolean checkMethod(Class<?> clazz, String name, Class<?>[] params) {
    	try {
    		clazz.getDeclaredMethod(name, params);
            return true;
    	} catch(Exception err) {
            return false;
    	}
    }
    
    /**
     * 获取手机支持的api
     */
    public static String SystemBitInfo() throws Throwable {
        Object object = Reflector.on("dalvik.system.VMRuntime")
        .method("getRuntime").call();
        String set = Reflector.on("dalvik.system.VMRuntime")
        .method("vmInstructionSet").call(object);
        String str = (String) analysisData().get(set);
        return str;
    }

    /**
     * 解析从系统中获取的数据
     */
    @SuppressWarnings("unchecked")
    public static Map analysisData() {
        Map.Entry[] entryArr = {new AbstractMap.SimpleEntry("arm", "armeabi-v7a"), new AbstractMap.SimpleEntry("arm64", "arm64-v8a"), new AbstractMap.SimpleEntry("x86", "x86"), new AbstractMap.SimpleEntry("x86_64", "x86_64")};
        HashMap hashMap = new HashMap(4);
        for (int i = 0; i < 4; i++) {
            Map.Entry entry = entryArr[i];
            Object key = entry.getKey();
            Objects.requireNonNull(key);
            Object value = entry.getValue();
            Objects.requireNonNull(value);
            if (hashMap.put(key, value) != null) {
                throw new IllegalArgumentException("duplicate key: " + key);
            }
        }
        return Collections.unmodifiableMap(hashMap);
    }


    public static PackageInfo getApkInfo(Context context, String path) {
        PackageManager pm = context.getPackageManager();
        PackageInfo pi = pm.getPackageArchiveInfo(path, PackageManager.GET_ACTIVITIES);
        if (pi != null) {
            return pi;
        }
        return null;
    }

}
