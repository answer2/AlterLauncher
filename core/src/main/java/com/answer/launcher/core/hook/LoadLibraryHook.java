package com.answer.launcher.core.hook;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.util.Log;
import com.answer.launcher.core.tool.Reflector;
import dalvik.system.BaseDexClassLoader;
import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodReplacement;
import java.io.File;
import com.answer.launcher.core.manager.NativePluginManager;
import com.answer.launcher.core.manager.Alternative;
import com.answer.launcher.core.Constants;

/**
 * @Author AnswerDev @Date 2024/07/06 10:04
 */
public class LoadLibraryHook extends BaseHook {

  public void init() {
    try {

      final ApplicationInfo minecraft_info =
          context.getPackageManager().getApplicationInfo(Constants.MINECRAFTPACKAGE, 0);
      Pine.hook(
          BaseDexClassLoader.class.getDeclaredMethod("findLibrary", String.class),
          new MethodReplacement() {
            @Override
            public Object replaceCall(Pine.CallFrame param) throws Throwable {
              String lib = (String) param.args[0];
              String path = minecraft_info.nativeLibraryDir + "/lib" + lib + ".so";
              if (lib.contains("c++_shared") && checkExist(path)) {
                String path_ = minecraft_info.nativeLibraryDir + "/libMediaDecoders_Android.so";
                if (checkExist(path_)) System.load(path_);
                return path;
              }
              return Pine.invokeOriginalMethod(param.method, param.thisObject, param.args);
            }
          });

      /*
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
      */
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private static boolean checkExist(String path) {
    File file = new File(path);
    return file.exists();
  }
}
