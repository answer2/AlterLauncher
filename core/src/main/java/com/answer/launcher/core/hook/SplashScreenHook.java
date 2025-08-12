package com.answer.launcher.core.hook;

import android.content.Intent;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.PopupWindow;

import com.answer.launcher.core.LauncherConstants;
import com.answer.launcher.core.manager.Alternative;
import com.answer.launcher.core.manager.MinecraftManager;
import com.answer.launcher.core.manager.NativeCore;
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
            if (SystemUtil.checkClass(LauncherConstants.MINECRAFTACTIVITY)) {
                Class<?> minecraftClass = Class.forName(LauncherConstants.MINECRAFTACTIVITY, false, SplashScreenHook.class.getClassLoader());
                Pine.hook(minecraftClass.getDeclaredMethod("onCreate", Bundle.class),
                        new MethodHook() {
                            @Override
                            public void beforeCall(Pine.CallFrame callFrame) throws Throwable {
                                super.beforeCall(callFrame);
                                Log.d("66666", "成功6666");



                                MainActivity minecraft = (MainActivity) callFrame.thisObject;
                                MinecraftManager.getManager().setActivity(minecraft);


                                LinearLayout linearLayout = new LinearLayout(minecraft);
                                linearLayout.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                                Button button = new Button(minecraft);
                                button.setText("send");
                                button.setTextSize(18);
                                button.setOnClickListener(view -> {
                                    NativeCore.sendMessage("你好");
                                });
                                linearLayout.addView(button);

                                PopupWindow popupWindow = new PopupWindow();

                                popupWindow.setContentView(linearLayout);
                                popupWindow.setWidth(LinearLayout.LayoutParams.WRAP_CONTENT);
                                popupWindow.setHeight(LinearLayout.LayoutParams.WRAP_CONTENT);
                                popupWindow.setTouchable(true);
                                popupWindow.setFocusable(false);
                                // 设置点击外部区域可消失
                                popupWindow.setOutsideTouchable(false);
                                popupWindow.setTouchInterceptor(new View.OnTouchListener() {
                                    @Override
                                    public boolean onTouch(View v, MotionEvent event) {
                                        return false;
                                        // 这里如果返回true的话，touch事件将被拦截
                                        // 拦截后 PopupWindow的onTouchEvent不被调用，这样点击外部区域无法dismiss
                                    }
                                });

// 必须设置一个背景，否则setOutsideTouchable(true)无效
                                popupWindow.setBackgroundDrawable(new ColorDrawable(0x00000000));    //要为popWindow设置一个背景才有效


                                minecraft
                                        .getWindow()
                                        .getDecorView()
                                        .post(
                                                new Runnable() {
                                                    @Override
                                                    public void run() {
                                                        popupWindow.showAtLocation(
                                                                minecraft.getWindow().getDecorView(), Gravity.CENTER, 0, 0);
                                                    }
                                                });

                                if (SystemUtil.checkMethod(MainActivity.class, "disableBrazeSDK", (Class<?>[]) null))
                                    minecraft.disableBrazeSDK();

                                // load texture pavck
                                if (SystemUtil.checkClass(LauncherConstants.MINECRAFTACTIVITY) && minecraft.getIntent() != null) {
                                    Method processIntent = Class.forName(LauncherConstants.MINECRAFTACTIVITY).getDeclaredMethod("processIntent", Intent.class);
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
