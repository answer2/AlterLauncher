package com.answer.launcher;

import android.app.Application;

public class AlterApplication extends Application{
    
    @Override
    public void onCreate() {
        super.onCreate();
        // TODO: Implement this method
       // CrashHelper.init();
       /* new Thread(()->{
            
        }).start();*/
    }
    
}
