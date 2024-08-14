package com.answer.launcher.core.env;

import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import java.lang.reflect.Method;
import org.lsposed.hiddenapibypass.HiddenApiBypass;
import com.answer.launcher.core.tool.Reflector;

/**
 * @Author AnswerDev
 * @Date 2024/07/06 11:14
 */

public class VirtualRuntime {

    private static String sInitialPackageName;
    private static String sProcessName;
    
    public static String getProcessName() {
        return sProcessName;
    }

    public static String getInitialPackageName() {
        return sInitialPackageName;
    }


    public static void setupRuntime(String processName, ApplicationInfo appInfo) {
        if (sProcessName != null) {
            return;
        }
        sInitialPackageName = appInfo.packageName;
        sProcessName = processName;

        try {
            Reflector.on(android.os.Process.class)
                .method("setArgV0", String.class)
                .call(processName);

            //https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/core/java/android/ddm/DdmHandleAppName.java;l=75?q=android.ddm.DdmHandleAppName.setAppName
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                Reflector.on("android.ddm.DdmHandleAppName")
                    .method("setAppName", String.class, int.class)
                    .call(processName, 0);
            } 

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
