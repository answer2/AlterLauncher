package com.answer.launcher.core.hook;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.util.Log;
import dalvik.system.BaseDexClassLoader;
import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodReplacement;
import java.io.File;
import com.answer.launcher.core.manager.NativePluginManager;
import com.answer.launcher.core.manager.Alternative;
import com.answer.launcher.core.Constants;

/**
 * @Author AnswerDev
 * @Date 2024/07/06 10:04
 */
public class LoadLibraryHook {

    public static void init(final Context context) {
        try {

            final ApplicationInfo minecraft_info = context.getPackageManager().getApplicationInfo(Constants.MINECRAFTPACKAGE, 0);
            Pine.hook(BaseDexClassLoader.class.getDeclaredMethod("findLibrary", String.class), 
                new MethodReplacement() {
                    @Override 
                    public Object replaceCall(Pine.CallFrame param) throws Throwable {
                        String lib = (String) param.args[0];

                        if ("minecraftpe".equals(lib)) {
                            return minecraft_info.nativeLibraryDir + "/libminecraftpe.so";
                        } else if ("c++_shared".equals(lib)) {
                            return minecraft_info.nativeLibraryDir + "/libc++_shared.so";
                        } else if ("fmod".equals(lib)) {
                            return minecraft_info.nativeLibraryDir + "/libfmod.so";
                        }

                        return Pine.invokeOriginalMethod(param.method, param.thisObject, param.args);
                    }
                });

            Pine.hook(java.lang.Runtime.class.getDeclaredMethod("loadLibrary0", ClassLoader.class, Class.class, String.class),
                new MethodReplacement() {
                    @Override 
                    public Object replaceCall(Pine.CallFrame param) throws Throwable {
                        String lib = (String) param.args[2];
                        Log.d("AlterNativeCore", lib);

                        if ("minecraftpe".equals(lib)) {
                            System.load(minecraft_info.nativeLibraryDir + "/libminecraftpe.so");
                            return null;
                        } else if ("c++_shared".equals(lib)) {
                            System.load(minecraft_info.nativeLibraryDir + "/libc++_shared.so");
                            String path = minecraft_info.nativeLibraryDir + "/libMediaDecoders_Android.so";
                            if (checkExist(path)) {
                                System.load(path);
                                Log.d("AlterNativeCore", "MediaDecoders_Android");
                            }
                            return null;
                        } else if ("fmod".equals(lib)) {
                            System.load(minecraft_info.nativeLibraryDir + "/libfmod.so");
                            return null;
                        }

                        try {
                            param.invokeOriginalMethod();
                        } catch (Exception error) {
                            Log.d("AlterNativeCore", lib + " library not found");
                        }

                        return null;
                    }
                });

        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    private static boolean checkExist(String path) {
        File file = new File(path);
        return file.exists();
    }


}
