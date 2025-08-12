package com.answer.launcher.core.env;
import android.content.Context;
import android.util.ArrayMap;
import java.io.File;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import com.answer.launcher.core.LauncherConstants;

/**
 * @Author AnswerDev
 * @Date 2024/07/06 15:00
 */
public class AlterEnvironment {

    /*
     @GuardedBy("mSync")
     private File mDatabasesDir;
     @GuardedBy("mSync")
     @UnsupportedAppUsage
     private File mPreferencesDir;
     @GuardedBy("mSync")
     private File mFilesDir;
     @GuardedBy("mSync")
     private File mCratesDir;
     @GuardedBy("mSync")
     private File mNoBackupFilesDir;
     @GuardedBy("mSync")
     private File mCacheDir;
     @GuardedBy("mSync")
     private File mCodeCacheDir;
     */

    public static void establishEnv(Context app,  Context plugin) {
        try {
            // Context
            List fields = Arrays.asList("mDatabasesDir", "mPreferencesDir", "mFilesDir", "mCratesDir", "mNoBackupFilesDir", "mCacheDir", "mCodeCacheDir");
            for ( Field f : Class.forName("android.app.ContextImpl").getDeclaredFields() ) {
                replaceSharedPrefsPackageName(plugin, f, LauncherConstants.MINECRAFTPACKAGE, LauncherConstants.PACKAGE);
                if ( fields.contains(f.getName()) ) {
                    f.setAccessible(true);
                    f.set(plugin,f.get(app));
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    public static void replaceSharedPrefsPackageName(Context context,Field f, final String target, final String str) throws IllegalAccessException, IllegalArgumentException{
        if ("sSharedPrefsCache".equals(f.getName())) {
            f.setAccessible(true);
            
            // 获取共享偏好缓存
            @SuppressWarnings("unchecked")
                ArrayMap<String, ArrayMap<File, Object>> sharedPrefsCache = (ArrayMap<String, ArrayMap<File, Object>>) f.get(context);
                if(sharedPrefsCache==null)return;
            // 创建一个新的BiFunction实现来替换所有值
            BiFunction<String, ArrayMap<File, Object>, ArrayMap<File, Object>> replaceFunction = new BiFunction<String, ArrayMap<File, Object>, ArrayMap<File, Object>>() {

                @Override
                public <V extends Object> BiFunction<String, ArrayMap<File, Object>, V> andThen(Function<? super ArrayMap<File, Object>, ? extends V> after) {
                    return null;
                }

                @Override
                public ArrayMap<File, Object> apply(String key, ArrayMap<File, Object> oldMap) {
                    ArrayMap<File, Object> newMap = new ArrayMap<>();
                    for (File file : oldMap.keySet()) {
                        // 将路径中的包名替换
                        String newPath = file.getAbsolutePath().replace(target, str);
                        newMap.put(new File(newPath), oldMap.get(file));
                    }

                    return newMap;
                }
            };

            // 遍历并替换所有值
            for (Map.Entry<String, ArrayMap<File, Object>> entry : sharedPrefsCache.entrySet()) {
                if(entry==null)continue;
                String key = entry.getKey();
                ArrayMap<File, Object> oldMap = entry.getValue();
                sharedPrefsCache.put(key, replaceFunction.apply(key, oldMap));
            }
            
            f.set(context,sharedPrefsCache);

        }

    }


}
