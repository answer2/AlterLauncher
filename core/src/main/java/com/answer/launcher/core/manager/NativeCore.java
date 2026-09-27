package com.answer.launcher.core.manager;
import android.content.res.AssetManager;

public class NativeCore {
  public static native long findLibBaseAddress(String library);

  public static native long loadLibrary(String libPath);

  public static native void unloadLibrary(long handle);

  public static native void initAssets(AssetManager manager);

  public static native void hookDlopen();

  public static native void loadManager();

  /**
   * Hands the version-gated hook data to the native layer. Must run before
   * {@link #loadManager()}: it decides which vtable slot the RTTI resolver
   * patches and which byte patterns the scanner is allowed to look for.
   *
   * @param minecraftVersion the guest's versionName, e.g. {@code 1.26.45.1}.
   * @param rules            the rules file text, or null to keep the data
   *                         compiled into main.cpp.
   */
  public static native void applyHookRules(String minecraftVersion, String rules);

  public static native void sendMessage(String msg);

  /**
   * Menu state as the game sees it (M4): pause/hud/showingMenu flags plus event
   * counts, formatted by cpp/main.cpp menuState_java. Only meaningful in the
   * process that runs the game (:game).
   */
  public static native String menuState();

}
