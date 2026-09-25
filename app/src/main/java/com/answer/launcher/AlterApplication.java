package com.answer.launcher;

import android.app.Application;
import android.os.Build;
import android.util.Log;

import com.answer.launcher.utils.CrashHandler;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

public class AlterApplication extends Application{

    private static final String TAG = "AlterApplication";
    
    @Override
    public void onCreate() {
        super.onCreate();
        // TODO: Implement this method
        // 注册全局崩溃处理器
        CrashHandler.getInstance().registerGlobal(this);
        // 注册主线程异常捕获
        CrashHandler.getInstance().registerPart(this);

        int pid = android.os.Process.myPid();
        Log.d(TAG, "MyApplication onCreate");
        Log.d(TAG, "MyApplication pid is " + pid);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("/","Landroid/app/ContextImpl", "Landroid/app/ActivityThread", "Landroid");
        }


       /* new Thread(()->{
            
        }).start();*/
    }
    
}
