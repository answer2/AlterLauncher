package com.answer.launcher.core.hook;

import android.content.Context;

import com.answer.launcher.core.manager.NativeCore;
import com.answer.launcher.core.tool.SystemUtil;

import dev.answer.pinetool.Pina;
import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;
import top.canyie.pine.callback.MethodReplacement;
import com.mojang.minecraftpe.MainActivity;
import android.view.ContextThemeWrapper;
import android.content.ContextWrapper;
import com.answer.launcher.core.manager.Alternative;
import com.mojang.minecraftpe.NotificationListenerService;

import java.util.stream.Stream;

/**
 * @Author AnswerDev
 * @Date 2024/07/07 09:26
 */
public class LoadedAfterHook extends BaseHook {

    public void init() {
        try {
            if(SystemUtil.checkMethod(MainActivity.class,"isBrazeEnabled", null)){
            Pine.hook(MainActivity.class.getDeclaredMethod("isBrazeEnabled"),
                new MethodReplacement(){
                    @Override
                    protected Object replaceCall(Pine.CallFrame callFrame) throws Throwable {
                        return false;
                    }
                });
            }
        
           if (SystemUtil.checkClass("com.mojang.minecraftpe.NotificationListenerService")) Alternative.getManager().createService("com.mojang.minecraftpe.NotificationListenerService");
            
            // Get libminecraftpe.so
            NativeCore.loadManager();


        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
