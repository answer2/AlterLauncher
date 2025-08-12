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
    	try{
            Class.forName(clazz);
            return true;
        }catch(Exception e){
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
