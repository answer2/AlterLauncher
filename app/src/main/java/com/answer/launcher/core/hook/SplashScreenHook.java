package com.answer.launcher.core.hook;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup.LayoutParams;
import android.view.WindowManager;
import android.widget.PopupWindow;

import com.answer.launcher.core.Constants;
import com.answer.launcher.core.tool.SystemUtil;
import java.lang.reflect.Method;

import com.answer.launcher.view.LoadingOverlay;
import com.mojang.minecraftpe.MainActivity;

import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodReplacement;
import com.answer.launcher.core.manager.Alternative;
import com.answer.launcher.core.manager.PluginManager;
import com.answer.launcher.core.manager.NativePluginManager;
import android.util.Log;
import android.os.Bundle;
import top.canyie.pine.callback.MethodHook;
import com.answer.launcher.core.manager.MinecraftManager;

/**
 * @Author AnswerDev
 * @Date 2024/07/07 08:20
 */
public class SplashScreenHook {

    public static PopupWindow popupWindow;
    private static LoadingOverlay loadingOverlay;
    private static Handler handler = new Handler();
    private static float progress;
    private static boolean isFinish = false;

    public static void init() {
        try {
            Class<?> minecraftClass = Class.forName("com.mojang.minecraftpe.MainActivity", false, SplashScreenHook.class.getClassLoader());
            Pine.hook(minecraftClass.getDeclaredMethod("onCreate", Bundle.class),
                new MethodHook(){
                    @Override
                    public void beforeCall(Pine.CallFrame callFrame) throws Throwable {
                        super.beforeCall(callFrame);
                        System.out.println("onCreate on com.mojang.minecraftpe.MainActivity");
                        MainActivity minecraft = (MainActivity) callFrame.thisObject;
                        
                        MinecraftManager.getManager().setActivity(minecraft);
                        if(MainActivity.class.getDeclaredMethod("disableBrazeSDK")!=null)minecraft.disableBrazeSDK();

                        if (SystemUtil.checkClass(Constants.MINECRAFTACTIVITY) &&minecraft.getIntent() != null) {
                            Method processIntent = Class.forName(Constants.MINECRAFTACTIVITY).getDeclaredMethod("processIntent", Intent.class);
                            processIntent.setAccessible(true);
                            processIntent.invoke(minecraft, minecraft.getIntent());
                        }
                        
                        LoadedAfterHook.init();
                        
                        loadSplashScreenUI(minecraft);

                        //Load Native
                        for (String path : NativePluginManager.getManager(Alternative.getManager().mContext).getNativeFiles()) {
                            System.load(path);
                            Log.d("NativePluginManager", "Loading " + path);
                        }

                        //Load Plugin
                        PluginManager.getManager(minecraft).loadPlugin();
                        
                    }

                });

            //仅限高版本
            /*
            Class<?> SplashScreenClass = Class.forName("androidx.core.splashscreen.SplashScreen");
            Pine.hook(SplashScreenClass.getDeclaredMethod("installSplashScreen", Activity.class),
                new MethodReplacement(){
                    @Override
                    protected Object replaceCall(Pine.CallFrame callFrame) throws Throwable {
                        MainActivity minecraft = (MainActivity) callFrame.args[0];
                        
                        return null;
                    }
                });*/
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void loadSplashScreenUI(final Activity activity) {
        popupWindow = new PopupWindow();
        loadingOverlay = new LoadingOverlay(activity);
        loadingOverlay.setKeepScreenOn(true);
        loadingOverlay.setOnFadeOutListener(new LoadingOverlay.OnFadeOutListener(){
                @Override
                public void onFadeOutComplete() {
                    popupWindow.dismiss();
                }
            });

        popupWindow.setContentView(loadingOverlay);
        popupWindow.setWidth(LayoutParams.MATCH_PARENT);
        popupWindow.setHeight(LayoutParams.MATCH_PARENT);
        popupWindow.setTouchable(false);
        popupWindow.setFocusable(false);
        popupWindow.setOutsideTouchable(false);

        activity.getWindow().getDecorView().post(new Runnable(){
                @Override
                public void run() {
                    popupWindow.showAtLocation(
                        activity.getWindow().getDecorView(),
                        Gravity.CENTER,
                        0,
                        0
                    );
                }
            });


        handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (progress <= 0.9f || isFinish) {
                        loadingOverlay.setProgress(progress);
                        progress += 0.01f; // 每次增加1%的进度
                        handler.postDelayed(this, 100); // 每100毫秒更新一次
                    }
                }
            }, 100);

    }

    public static void finishUI() {
        if (popupWindow != null &&  loadingOverlay != null) {
            if (loadingOverlay.getProgress() < 1f) loadingOverlay.setProgress(1f);
            loadingOverlay.startFadeOutAnimation();
            isFinish = true;

        }
    }

    protected static int dip2pxInt(Activity activuty, float value) {
        final WindowManager windowManager = (WindowManager) activuty.getSystemService(Context.WINDOW_SERVICE);
        final DisplayMetrics displayMetrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(displayMetrics);
        return (int) TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            displayMetrics
        );
    }



}
