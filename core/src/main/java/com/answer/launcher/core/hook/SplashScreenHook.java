package com.answer.launcher.core.hook;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import com.answer.launcher.core.Constants;
import com.answer.launcher.core.manager.Alternative;
import com.answer.launcher.core.manager.MinecraftManager;
import com.answer.launcher.core.manager.NativePluginManager;
import com.answer.launcher.core.manager.PluginManager;
import com.answer.launcher.core.tool.SystemUtil;
import com.mojang.minecraftpe.MainActivity;
import java.lang.reflect.Method;
import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;

/**
 * @Author AnswerDev
 * @Date 2024/07/07 08:20
 */
public class SplashScreenHook extends BaseHook {
    

    public void init() {
        try {
            if(SystemUtil.checkClass(Constants.MINECRAFTACTIVITY)){
            Class<?> minecraftClass = Class.forName(Constants.MINECRAFTACTIVITY, false, SplashScreenHook.class.getClassLoader());
            Pine.hook(minecraftClass.getDeclaredMethod("onCreate", Bundle.class),
                new MethodHook(){
                    @Override
                    public void beforeCall(Pine.CallFrame callFrame) throws Throwable {
                        super.beforeCall(callFrame);
                        
                        MainActivity minecraft = (MainActivity) callFrame.thisObject;
                        MinecraftManager.getManager().setActivity(minecraft);
                        
                        if(SystemUtil.checkMethod(MainActivity.class,"disableBrazeSDK", (Class<?>[]) null))minecraft.disableBrazeSDK();

                        // load texture pavck
                        if (SystemUtil.checkClass(Constants.MINECRAFTACTIVITY) &&minecraft.getIntent() != null) {
                            Method processIntent = Class.forName(Constants.MINECRAFTACTIVITY).getDeclaredMethod("processIntent", Intent.class);
                            processIntent.setAccessible(true);
                            processIntent.invoke(minecraft, minecraft.getIntent());
                        }
                        
                        new LoadedAfterHook().init();

                        //Load Native
                        for (String path : NativePluginManager.getManager(Alternative.getManager().mContext).getNativeFiles()) {
                            System.load(path);
                            Log.d("NativePluginManager", "Loading " + path);
                        }

                        //Load Plugin
                        PluginManager.getManager(minecraft).loadPlugin();
                        
                    }

                });
                }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


}
