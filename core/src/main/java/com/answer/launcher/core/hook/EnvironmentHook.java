package com.answer.launcher.core.hook;

import android.app.Activity;
import android.app.IApplicationThread;
import android.app.Instrumentation;
import android.app.ProfilerInfo;
import android.app.servertransaction.ClientTransaction;
import android.app.servertransaction.ClientTransactionItem;
import android.app.servertransaction.LaunchActivityItem;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Message;
import android.util.Log;
import com.answer.launcher.core.Constants;
import com.answer.launcher.core.tool.Reflector;
import com.answer.launcher.ui.activity.LoginStubActivity;
import java.io.File;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import org.lsposed.hiddenapibypass.HiddenApiBypass;
import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;
import top.canyie.pine.callback.MethodReplacement;
import top.canyie.pine.Pine.CallFrame;
import com.answer.launcher.core.manager.IOManager;

/**
 * @Author AnswerDev 
 * @Date 2024/07/06 15:01
 */
public class EnvironmentHook {

  public static final int EXECUTE_TRANSACTION = 159;
  public static final int LAUNCH_ACTIVITY = 100;

  public static void init(final Context context) {

    try {
      Pine.hook(
          Class.forName("android.app.ContextImpl")
              .getDeclaredMethod(
                  "ensurePrivateDirExists", File.class, int.class, int.class, String.class),
          new MethodHook() {
            @Override
            public void beforeCall(Pine.CallFrame params) {
              File file = (File) params.args[0];
              if (file != null && file.getAbsolutePath().contains(Constants.MINECRAFTPACKAGE)) {
                params.args[0] =
                    new File(
                        file.getAbsolutePath()
                            .replace(Constants.MINECRAFTPACKAGE, Constants.PACKAGE));
              }
            }
          });

      Pine.hook(
          libcore.io.IoBridge.class.getDeclaredMethod("open", String.class, int.class),
          new MethodHook() {
            @Override
            public void beforeCall(Pine.CallFrame params) {
              String path = (String) params.args[0];
              if (path != null && path.contains(Constants.MINECRAFTPACKAGE)) {
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
              if (path != null && path.contains(Constants.MINECRAFTPACKAGE)) {
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

      // android.app.IActivityTaskManager$Stub$Proxy.startActivity(android.app.IApplicationThread,java.lang.String,java.lang.String,android.content.Intent,java.lang.String,android.os.IBinder,java.lang.String,int,int,android.app.ProfilerInfo,android.os.Bundle) throws android.os.RemoteException
      // 错误码 -92 (fail +8)
      // 成功码 0

      Pine.hook(
          HiddenApiBypass.getDeclaredMethod(
              Class.forName("android.app.IActivityTaskManager$Stub$Proxy"),
              "startActivity",
              IApplicationThread.class,
              String.class,
              String.class,
              Intent.class,
              String.class,
              IBinder.class,
              String.class,
              int.class,
              int.class,
              ProfilerInfo.class,
              Bundle.class),
          new MethodHook() {
            @Override
            public void beforeCall(Pine.CallFrame params) {
              for (int i = 0; params.args.length > i; i++) {
                if (params.args[i] instanceof Intent) {
                  Intent intent0 = (Intent) params.args[i];
                  if (intent0.getComponent().getClassName().contains("xbox")
                      || intent0.getComponent().getClassName().contains("microsoft")) {
                    Intent intent = new Intent(context, LoginStubActivity.class);
                    intent.putExtra("actonIntent", intent0);
                    params.args[i] = intent;
                  }
                }
              }
            }
          });

      Pine.hook(
          HiddenApiBypass.getDeclaredMethod(
              Instrumentation.class,
              "execStartActivity",
              Context.class,
              IBinder.class,
              IBinder.class,
              Activity.class,
              Intent.class,
              int.class,
              Bundle.class),
          new MethodHook() {
            @Override
            public void afterCall(Pine.CallFrame params) {
              Intent intent = (Intent) params.args[4];
              Log.d("startActivity", "options:" + params.args[6]);
            }
          });

      Pine.hook(
          Class.forName("android.app.ActivityThread$H")
              .getDeclaredMethod("handleMessage", Message.class),
          new MethodHook() {
            @Override
            public void beforeCall(Pine.CallFrame params) {
              try {
                Message msg = (Message) params.args[0];
                if (msg.what == LAUNCH_ACTIVITY) {
                  Log.d("handleMessage", "LAUNCH_ACTIVITY-" + msg.obj);
                  Intent intent =
                      (Intent) Reflector.on(msg.obj.getClass()).field("intent").get(msg.obj);
                  Intent actonIntent = intent.getParcelableExtra("actonIntent");
                  ActivityInfo mActivityInfo =
                      (ActivityInfo)
                          Reflector.on(msg.obj.getClass()).field("activityInfo").get(msg.obj);
                                
                  Log.d("handleMessage", String.format("[mH]LAUNCH_ACTIVITY intent=%s", intent));
                  Log.d(
                      "handleMessage",
                      String.format("[mH]LAUNCH_ACTIVITY actonIntent=%s", actonIntent));
                  if (actonIntent != null) {
                    Reflector.on(msg.obj.getClass()).field("intent").set(msg.obj, actonIntent);
                  }
                } else if (msg.what == EXECUTE_TRANSACTION) {
                  Log.d("handleMessage", "EXECUTE_TRANSACTION-" + msg.obj);
                  ClientTransaction transaction = (ClientTransaction) msg.obj;
                  if (transaction.getCallbacks() != null && transaction.getCallbacks().size() > 0) {
                    ClientTransactionItem mClientTransactionItem =
                        transaction.getCallbacks().get(0);
                    if (LaunchActivityItem.class.isInstance(mClientTransactionItem)) {
                      LaunchActivityItem item = (LaunchActivityItem) mClientTransactionItem;
                      Intent intent =
                          (Intent) Reflector.on(item.getClass()).field("mIntent").get(item);
                      ActivityInfo mActivityInfo =
                          (ActivityInfo) Reflector.on(item.getClass()).field("mInfo").get(item);

                      Intent actonIntent = intent.getParcelableExtra("actonIntent");
                      if (actonIntent != null) {
                        Reflector.on(item.getClass()).field("mIntent").set(item, actonIntent);
                      }
                    }
                  }
                }
              } catch (Exception e) {
                e.printStackTrace();
              }
            }
          });

    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}
