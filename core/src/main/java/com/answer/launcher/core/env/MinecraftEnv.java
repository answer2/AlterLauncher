package com.answer.launcher.core.env;

import android.content.Context;
import android.util.Log;
import com.answer.launcher.core.manager.Alternative;
import java.lang.reflect.Method;
import static com.answer.launcher.core.tool.SystemUtil.checkClass;

/**
 * @Author AnswerDev
 * @Date 2024/07/06 11:53
 */
public class MinecraftEnv {

  private static final String TAG = "MinecraftEnv";

  /**
   * Bootstraps the Firebase half of the guest runtime.
   *
   * <p>Everything below is resolved by name through the loader that defines the game
   * instead of being referenced at compile time. This class is packaged in the
   * launcher's own dex, so a direct reference such as {@code FirebaseApp.initializeApp}
   * is linked against the launcher's loader -- which cannot see the game dex now that
   * the game classes are no longer merged into it, and the whole of initEnv used to die
   * with {@code NoClassDefFoundError: Failed resolution of:
   * Lcom/google/firebase/FirebaseApp;} right after the guest application had been
   * created. A name probe still succeeded, because {@link
   * com.answer.launcher.core.tool.SystemUtil#checkClass} does consult the guest loader:
   * the guard and the body had been asking two different loaders.
   *
   * <p>{@code NoClassDefFoundError} is an {@code Error}, so the previous {@code catch
   * (Exception)} never contained it either.
   */
  public static void establishEnv(Context context) {

    try {
      ClassLoader guest = Alternative.getManager().getClassLoader();
      if (guest == null) {
        guest = MinecraftEnv.class.getClassLoader();
      }

      // setup FirebaseInitProvider
      if (checkClass("com.google.android.gms.common.R$string")
          && checkClass("com.google.firebase.FirebaseApp")) {
        Class<?> firebaseApp = Class.forName("com.google.firebase.FirebaseApp", false, guest);
        Method initializeApp = firebaseApp.getMethod("initializeApp", Context.class);
        if (initializeApp.invoke(null, context) == null) return;
        Log.i(TAG, "FirebaseApp initialization successful");
      }

      // setup ComponentDiscoveryService
      if (checkClass("com.google.firebase.components.ComponentDiscoveryService"))
        Alternative.getManager().createService(
            "com.google.firebase.components.ComponentDiscoveryService");

      // setup FirebaseMessagingService
      if (checkClass("com.google.firebase.messaging.FirebaseMessagingService"))
        Alternative.getManager().createService(
            "com.google.firebase.messaging.FirebaseMessagingService");

      // setup NotificationFirebaseMessagingService
      if (checkClass(
          "com.microsoft.xbox.service.notification.NotificationFirebaseMessagingService"))
        Alternative.getManager().createService(
            "com.microsoft.xbox.service.notification.NotificationFirebaseMessagingService");

    } catch (Throwable e) {
      e.printStackTrace();
    }
  }
}
