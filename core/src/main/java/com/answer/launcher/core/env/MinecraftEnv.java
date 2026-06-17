package com.answer.launcher.core.env;

import android.content.Context;
import android.util.Log;
import com.answer.launcher.core.manager.Alternative;
import com.answer.launcher.core.tool.ContentProviderCompat;
import static com.answer.launcher.core.tool.SystemUtil.checkClass;
import com.google.firebase.FirebaseApp;
import com.google.firebase.components.ComponentDiscoveryService;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.microsoft.xbox.service.notification.NotificationFirebaseMessagingService;

/**
 * @Author AnswerDev
 * @Date 2024/07/06 11:53
 */
public class MinecraftEnv {

  private static final String TAG = "MinecraftEnv";

  public static void establishEnv(Context context) {

    try {
      // setup FirebaseInitProvider
      if (checkClass("com.google.android.gms.common.R$string")&&checkClass("com.google.firebase.FirebaseApp")) {
           if(FirebaseApp.initializeApp(context) ==null) return;
        Log.i(TAG, "FirebaseApp initialization successful");
      }

      // setup ComponentDiscoveryService
      if (checkClass("com.google.firebase.components.ComponentDiscoveryService"))
        Alternative.getManager().createService(ComponentDiscoveryService.class.getName());

      // setup FirebaseMessagingService
      if (checkClass("com.google.firebase.messaging.FirebaseMessagingService"))
        Alternative.getManager().createService(FirebaseMessagingService.class.getName());

      // setup NotificationFirebaseMessagingService
      if (checkClass(
          "com.microsoft.xbox.service.notification.NotificationFirebaseMessagingService"))
        Alternative.getManager()
            .createService(NotificationFirebaseMessagingService.class.getName());

    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}
