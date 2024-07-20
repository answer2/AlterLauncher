package com.answer.launcher.core.hook;

import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;
import top.canyie.pine.callback.MethodReplacement;
import com.mojang.minecraftpe.MainActivity;
import android.view.ContextThemeWrapper;
import android.content.ContextWrapper;
import com.answer.launcher.core.manager.Alternative;
import com.mojang.minecraftpe.NotificationListenerService;

/**
 * @Author AnswerDev
 * @Date 2024/07/07 09:26
 */
public class LoadedAfterHook {

    public static void init() {
        try {
            Pine.hook(MainActivity.class.getDeclaredMethod("isBrazeEnabled"),
                new MethodReplacement(){
                    @Override
                    protected Object replaceCall(Pine.CallFrame callFrame) throws Throwable {
                        return false;
                    }
                });

            Alternative.getManager().createService(NotificationListenerService.class.getName());
            
            // Get libminecraftpe.so
            Alternative.load();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
