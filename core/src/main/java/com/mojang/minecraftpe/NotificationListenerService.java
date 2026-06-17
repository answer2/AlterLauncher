package com.mojang.minecraftpe;

import android.os.Looper;
import android.util.Log;
import com.braze.Braze;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import com.microsoft.xbox.service.notification.NotificationHelper;
import com.microsoft.xbox.service.notification.NotificationResult;
import java.lang.reflect.Method;
import com.answer.launcher.core.manager.MinecraftManager;

@SuppressWarnings("JavaJniMissingFunction")
public class NotificationListenerService extends FirebaseMessagingService {
    private static String sDeviceRegistrationToken = "";

    public NotificationListenerService() {
      //  retrieveDeviceToken();
        System.out.println("NotificationListenerService 被注册");
    }

    public static String getDeviceRegistrationToken() {
        if (sDeviceRegistrationToken.isEmpty()) {
            retrieveDeviceToken();
        }
        return sDeviceRegistrationToken;
    }

    private static void retrieveDeviceToken() {
      /*  if (Thread.currentThread().equals(Looper.getMainLooper().getThread())) {
            Log.e("Alternative", "NotificationListenerService.retrieveDeviceToken() should not run on main thread.");
        }
        if (sDeviceRegistrationToken.isEmpty()) {
            
            FirebaseMessaging.getInstance().getToken().addOnCompleteListener(new OnCompleteListener<String>(){
                    @Override
                    public void onComplete(Task<String> task) {
                        String result = task.isSuccessful() ? task.getResult() : "";
                        if (result != null && !result.isEmpty()) {
                            Log.i("Minecraft", "Device Push Token: " + result);
                            sDeviceRegistrationToken = result;
                            token(sDeviceRegistrationToken);
                            Braze.getInstance(FirebaseApp.getInstance().getApplicationContext()).setRegisteredPushToken(sDeviceRegistrationToken);
                            return;
                        } 
                        Log.e("Alternative", "Unable to get Firebase Messaging token, trying again...");
                    }
                });
        }*/
    }

    @Override
    public void onNewToken(String string) {
        super.onNewToken(string);
        /*Log.i("Minecraft", "New Device Push Token: " + string);
        sDeviceRegistrationToken = string;
        Braze.getInstance(FirebaseApp.getInstance().getApplicationContext()).setRegisteredPushToken(sDeviceRegistrationToken);*/
    }

    native void nativePushNotificationReceived(final int type, String title, String description, String data);

    
    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
       // NotificationResult tryParseXboxLiveNotification = NotificationHelper.tryParseXboxLiveNotification(remoteMessage, this);
       // nativePushNotificationReceived(tryParseXboxLiveNotification.notificationType.ordinal(), tryParseXboxLiveNotification.title, tryParseXboxLiveNotification.body, tryParseXboxLiveNotification.data);
    }
    
    public static void token(String token){
        /*try{
            Method method = Class.forName("com.mojang.minecraftpe.MainActivity", false, NotificationListenerService.class.getClassLoader()).getDeclaredMethod("requestIntegrityToken", String.class);
            method.setAccessible(true);
            method.invoke(MinecraftManager.getManager().getActivity(), token);
        }catch(Exception e){
            e.printStackTrace();
        }*/
    }
}
