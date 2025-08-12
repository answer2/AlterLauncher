package com.answer.launcher.ui.splash;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.PopupWindow;
import android.widget.LinearLayout.LayoutParams;
import com.answer.launcher.view.LoadingOverlay;

public class GameSplash {
  
  private static Handler handler = new Handler(Looper.getMainLooper());
    
  private static float progress;
  public static PopupWindow popupWindow;
  private static LoadingOverlay loadingOverlay;
  private static boolean isFinish = false;

  private void loadSplashScreenUI(final Activity activity) {
    popupWindow = new PopupWindow();
    loadingOverlay = new LoadingOverlay(activity);
    loadingOverlay.setKeepScreenOn(true);
    loadingOverlay.setOnFadeOutListener(() -> popupWindow.dismiss());

    popupWindow.setContentView(loadingOverlay);
    popupWindow.setWidth(LayoutParams.MATCH_PARENT);
    popupWindow.setHeight(LayoutParams.MATCH_PARENT);
    popupWindow.setTouchable(false);
    popupWindow.setFocusable(false);
    popupWindow.setOutsideTouchable(false);

    activity
        .getWindow()
        .getDecorView()
        .post(
            new Runnable() {
              @Override
              public void run() {
                popupWindow.showAtLocation(
                    activity.getWindow().getDecorView(), Gravity.CENTER, 0, 0);
              }
            });

    handler.postDelayed(
        new Runnable() {
          @Override
          public void run() {
            if (progress <= 0.9f || isFinish) {
              loadingOverlay.setProgress(progress);
              progress += 0.01f; // 每次增加1%的进度
              handler.postDelayed(this, 100); // 每100毫秒更新一次
            }
          }
        },
        100);
  }

  public static void finishUI() {
    if (popupWindow != null && loadingOverlay != null) {
      if (loadingOverlay.getProgress() < 1f) loadingOverlay.setProgress(1f);
      loadingOverlay.startFadeOutAnimation();
      isFinish = true;
    }
  }

  protected static int dip2pxInt(Activity activuty, float value) {
    final WindowManager windowManager =
        (WindowManager) activuty.getSystemService(Context.WINDOW_SERVICE);
    final DisplayMetrics displayMetrics = new DisplayMetrics();
    windowManager.getDefaultDisplay().getMetrics(displayMetrics);
    return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, displayMetrics);
  }
}
