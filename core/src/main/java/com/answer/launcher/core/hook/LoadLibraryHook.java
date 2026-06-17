package com.answer.launcher.core.hook;

import android.content.pm.ApplicationInfo;

import dalvik.system.BaseDexClassLoader;
import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodReplacement;
import java.io.File;

import com.answer.launcher.core.LauncherConstants;

/**
 * @Author AnswerDev
 * @Date 2024/07/06 10:04
 */
public class LoadLibraryHook extends BaseHook {

  public void init() {
    try {

      final ApplicationInfo minecraft_info =
          context.getPackageManager().getApplicationInfo(LauncherConstants.MINECRAFTPACKAGE, 0);

      Pine.hook(
          BaseDexClassLoader.class.getDeclaredMethod("findLibrary", String.class),
          new MethodReplacement() {
            @Override
            public Object replaceCall(Pine.CallFrame param) throws Throwable {
              String lib = (String) param.args[0];

              String path = minecraft_info.nativeLibraryDir + "/lib" + lib + ".so";
              if (lib.contains("c++_shared") && checkExist(path)) {
                String path_media = minecraft_info.nativeLibraryDir + "/libMediaDecoders_Android.so";
                if (checkExist(path_media)) System.load(path_media);
                return path;
              }

              var pathOrigin = Pine.invokeOriginalMethod(param.method, param.thisObject, param.args);
              return pathOrigin;
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
