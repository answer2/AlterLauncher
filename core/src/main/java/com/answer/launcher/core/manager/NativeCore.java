package com.answer.launcher.core.manager;
import android.content.res.AssetManager;

public class NativeCore {
  public static native long findLibBaseAddress(String library);

  public static native long loadLibrary(String libPath);

  public static native void unloadLibrary(long handle);

  public static native void initAssets(AssetManager manager);

  public static native void hookDlopen();

  public static native void loadManager();

  public static native void sendMessage(String msg);

}
