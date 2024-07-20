package com.answer.launcher.core.tool;

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
        }catch(Exception e){
            return false;
        }
        return true;
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

}
