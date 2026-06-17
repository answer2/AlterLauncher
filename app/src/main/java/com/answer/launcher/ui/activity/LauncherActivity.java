package com.answer.launcher.ui.activity;

import android.annotation.TargetApi;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.PopupWindow;

import com.answer.launcher.core.manager.Alternative;
import com.answer.launcher.core.manager.MinecraftManager;
import com.answer.launcher.core.manager.NativeCore;
import com.mojang.minecraftpe.MainActivity;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.stream.Collectors;

public class LauncherActivity extends MainActivity {

  private SurfaceHolder mHolder;
  private DrawGameSurface mThread;
  private boolean mIsRunning;
  private int width;
  private int height;

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

  }

  @Override
  public Resources getResources() {
    return Alternative.getManager().getContext().getResources();
  }

  @Override
  public AssetManager getAssets() {
    return Alternative.getManager().getContext().getAssets();
  }

  @Override
  protected void onDestroy() {
    // TODO fix unregisterReceiver error
    if (Arrays.stream(MainActivity.class.getDeclaredFields()).map(Field::getName).collect(Collectors.toList()).contains("mBatteryMonitor")){
      Log.d("LauncherActivity", " Success removed receivers.");
      unregisterReceiver(getBatteryMonitor());
      unregisterReceiver(getThermalMonitor());
    }
    super.onDestroy();
  }

  @Override
  protected void onResume() {
    super.onResume();
    // TODO: Implement this method
        Log.d("LauncherActivity", "onResume");
  }

  @Override
  public void surfaceCreated(SurfaceHolder holder) {
    super.surfaceCreated(holder);
    this.mHolder = holder;
    this.mIsRunning = true;
    this.width = holder.getSurfaceFrame().width();
    this.height = holder.getSurfaceFrame().height();
    if (mHolder != null) startThread();
  }

  @Override
  public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    super.surfaceChanged(holder, format, width, height);
  }

  // 需要重新绘制表面
  @Override
  public void surfaceRedrawNeeded(SurfaceHolder holder) {
    super.surfaceRedrawNeeded(holder);
  }

  // 需要异步地重新绘制表面
  @Override
  public void surfaceRedrawNeededAsync(SurfaceHolder holder, Runnable drawingFinished) {
    super.surfaceRedrawNeededAsync(holder, drawingFinished);
  }

  @Override
  public void surfaceDestroyed(SurfaceHolder holder) {
    super.surfaceDestroyed(holder);
    mIsRunning = false;
    mThread.interrupt();
  }

  public void startThread() {
    mThread = new DrawGameSurface(0, 0, width, height, mHolder.getSurface());
    mThread.setName("DrawGameSurface");
    // mThread.start();
  }

  private class DrawGameSurface extends Thread {

    private final Handler.Callback mCallback =
            msg -> {
              if (null != msg) {
                if (msg.what == 1) {
                  MinecraftManager.getManager()
                      .getCallbacks()
                      .forEach(callback -> callback.onRedraw((Bitmap) msg.obj));
                }
              }
              return true;
            };

    private Handler mHandler = new Handler(Looper.getMainLooper(), mCallback);
    private final int left;
    private final int top;
    private final int right;
    private final int bottom;
    private Surface surface;
    private Bitmap mScreenBitmap;

    private void sendMessage(Object obj) {
      Message msg = Message.obtain();
      msg.obj = obj;
      msg.what = 1;
      this.mHandler.sendMessage(msg);
    }

    public DrawGameSurface(int left, int top, int right, int bottom, Surface surface) {
      this.left = left;
      this.top = top;
      this.right = right;
      this.bottom = bottom;
      this.surface = surface;
    }

    @TargetApi(Build.VERSION_CODES.O)
    @Override
    public void run() {
      while (mIsRunning) {
        if (mScreenBitmap == null) {
          mScreenBitmap = Bitmap.createBitmap(right - left, bottom - top, Bitmap.Config.ARGB_8888);
        }

        Rect tempRect = new Rect(left, top, right, bottom);

        PixelCopy.request(
            surface,
            tempRect,
            mScreenBitmap,
                copyResult -> {
                  if (PixelCopy.SUCCESS == copyResult) {
                    sendMessage(mScreenBitmap);
                  } else {
                    Log.d("drawGameSurface", "request scapy mapview failed");
                  }
                },
            mHandler);

        try {
          //noinspection BusyWait
          sleep(15);
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
      }
      mScreenBitmap.recycle();
      mHandler.removeCallbacksAndMessages(null);
      mScreenBitmap = null;
      mHandler = null;
    }
  }
}
