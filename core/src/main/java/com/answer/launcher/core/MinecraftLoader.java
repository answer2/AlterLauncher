package com.answer.launcher.core;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Process;
import android.util.Log;
import com.answer.launcher.core.hook.ActivityThreadHook;
import com.answer.launcher.core.hook.EnvironmentHook;
import com.answer.launcher.core.hook.LoadLibraryHook;
import com.answer.launcher.core.hook.UnixFileSystemHook;
import com.answer.launcher.core.manager.Alternative;
import com.answer.launcher.core.Test;

public class MinecraftLoader {

  public static void load(Context context) {
    // init env
    EnvironmentHook.init(context);
    UnixFileSystemHook.init();
        
    // Init Alternative Core
    Alternative.getManager().init(context);
        
    LoadLibraryHook.init(context);
    Test.a("666");
        
  }
    
    private static String getProcessName(Context context) {
        int pid = Process.myPid();
        String processName = null;
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningAppProcessInfo info : am.getRunningAppProcesses()) {
            Log.d("getProcessName", ""+info.processName);
            if (info.pid == pid) {
                processName = info.processName;
                break;
            }
        }
        if (processName == null) {
            throw new RuntimeException("processName = null");
        }
        return processName;
    }

}
