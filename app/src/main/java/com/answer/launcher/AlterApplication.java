package com.answer.launcher;

import android.app.Application;
import android.os.Build;
import android.util.Log;

import com.answer.launcher.core.LauncherConstants;
import com.answer.launcher.core.MinecraftLoader;
import com.answer.launcher.core.bridge.GameBridgeClient;
import com.answer.launcher.utils.CrashHandler;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

public class AlterApplication extends Application{

    private static final String TAG = "AlterApplication";
    
    @Override
    public void onCreate() {
        super.onCreate();

        /*
         * Hidden-API exemptions and the crash handlers are per-process by design:
         * each of the two processes reflects into the framework on its own, and a
         * crash in :game has to be captured by :game before the system tears it
         * down. Both therefore run before the split below.
         */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("/","Landroid/app/ContextImpl", "Landroid/app/ActivityThread", "Landroid");
        }

        CrashHandler.getInstance().registerGlobal(this);
        CrashHandler.getInstance().registerPart(this);

        int pid = android.os.Process.myPid();
        String process = processName();
        boolean gameProcess = process != null
                && process.endsWith(LauncherConstants.GAME_PROCESS_SUFFIX);

        Log.i(TAG, "onCreate in " + process + " (pid " + pid + ")");

        if (gameProcess) {
            /*
             * The whole assembly — guest LoadedApk, the loader over the Minecraft
             * APK, the hook tables and the native libraries — has to be finished
             * here. Application#onCreate is the only hook that runs before the
             * framework creates this process' first activity, and that ordering is
             * what lets AlterInstrumentation.newActivity() substitute the game's
             * MainActivity for GameHostActivity and have the game's own classes
             * resolvable when it does.
             */
            MinecraftLoader.load(this);
        } else {
            /*
             * UI process: never assembles the runtime. It parks a read-only binding
             * on :game so plugin and hook state can be displayed live; the bind
             * deliberately does not create the service, so this cannot start the
             * game process by itself.
             */
            // Material You: opt-in via settings, read once here so it applies to every
            // activity created this process. A change needs a restart to take effect.
            if (com.answer.launcher.ui.LauncherPrefs.dynamicColor(this)) {
                com.google.android.material.color.DynamicColors
                        .applyToActivitiesIfAvailable(this);
            }
            GameBridgeClient.get().bind(this);
        }
    }

    /** Full process name ("com.answer.launcher" or "com.answer.launcher:game"). */
    private String processName() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return Application.getProcessName();
            } catch (Throwable t) {
                Log.w(TAG, "Application.getProcessName failed: " + t);
            }
        }
        /*
         * Only reachable below API 28 and the project's floor is 26, so this is a
         * formality — but a wrong answer here would silently disable the split.
         */
        try {
            return MinecraftLoader.getProcessName2(this);
        } catch (Throwable t) {
            return null;
        }
    }
    
}
