package com.answer.launcher.core.hook;

import android.annotation.SuppressLint;
import android.app.NativeActivity;
import android.content.res.AssetManager;
import android.os.MessageQueue;
import android.util.Log;
import com.answer.launcher.core.LauncherConstants;
import com.answer.launcher.core.manager.IOManager;
import java.io.File;
import java.util.Arrays;
import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;
import top.canyie.pine.callback.MethodReplacement;

/**
 * @Author AnswerDev 
 * @Date 2024/07/06 15:01
 */
public class EnvironmentHook extends BaseHook{

  public static final int EXECUTE_TRANSACTION = 159;
  public static final int LAUNCH_ACTIVITY = 100;

  @SuppressLint("BlockedPrivateApi")
  public void init() {

    try {
      Pine.hook(
          Class.forName("android.app.ContextImpl")
              .getDeclaredMethod(
                  "ensurePrivateDirExists", File.class, int.class, int.class, String.class),
          new MethodHook() {
            @Override
            public void beforeCall(Pine.CallFrame params) {
              File file = (File) params.args[0];
              if (file != null && file.getAbsolutePath().contains(LauncherConstants.MINECRAFTPACKAGE)) {
                params.args[0] =
                    new File(
                        file.getAbsolutePath()
                            .replace(LauncherConstants.MINECRAFTPACKAGE, LauncherConstants.PACKAGE));
              }
            }
          });

      Pine.hook(
          libcore.io.IoBridge.class.getDeclaredMethod("open", String.class, int.class),
          new MethodHook() {
            @Override
            public void beforeCall(Pine.CallFrame params) {
              String path = (String) params.args[0];
              if (path != null && path.contains(LauncherConstants.MINECRAFTPACKAGE)) {
                params.args[0] = IOManager.get().redirectPath(path);
              }
            }
          });

      Pine.hook(
          libcore.io.Linux.class.getDeclaredMethod("access", String.class, int.class),
          new MethodHook() {
            @Override
            public void beforeCall(Pine.CallFrame params) {
              String path = (String) params.args[0];
              if (path != null && path.contains(LauncherConstants.MINECRAFTPACKAGE)) {
                params.args[0] = IOManager.get().redirectPath(path);
              }
            }
          });

      Pine.hook(
          System.class.getDeclaredMethod("exit", int.class),
          new MethodReplacement() {
            @Override
            protected Object replaceCall(Pine.CallFrame callFrame) throws Throwable {
              if ((int) callFrame.args[0] != 0) {
                callFrame.invokeOriginalMethod();
              }
              return null;
            }
          });
            
          /*private native long loadNativeCode(String path, String funcname, MessageQueue queue,
            String internalDataPath, String obbPath, String externalDataPath, int sdkVersion,
            AssetManager assetMgr, byte[] savedState, ClassLoader classLoader, String libraryPath);*/
            Pine.hook(
          NativeActivity.class.getDeclaredMethod("loadNativeCode", String.class, String.class, MessageQueue.class,
                    String.class, String.class, String.class, int.class,
                    AssetManager.class, byte[].class, ClassLoader.class, String.class ),
          new MethodHook() {
            @Override
            public void beforeCall(Pine.CallFrame params) {
              Log.d("NativeActivity", Arrays.toString(params.args));
            }
          });

    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}
