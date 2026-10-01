package com.answer.launcher.core.manager;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.net.Uri;
import android.widget.Toast;
import com.answer.launcher.core.LauncherConstants;
import com.answer.launcher.core.bridge.GameBridgeClient;

/**
 * @Author AnswerDev
 * @Date 2024/07/08 20:06
 */

public class ProcessIntentManager extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onStart() {
        /*
         * This activity is declared in the default process, so it must not assemble
         * anything: the runtime belongs to :game. processIntent() below wakes the
         * bridge and starts the proxy, and the proxy's Application#onCreate does the
         * assembly.
         */
        super.onStart();
        processIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        processIntent(intent);
    }

    private void processIntent(Intent intent) {
        
        try {
            String scheme = intent.getScheme();
            Uri data = intent.getData();
            if ("file".equalsIgnoreCase(scheme) && data != null) {
                String path = data.getPath();
                if (path != null && path.endsWith(".so")) {
                    importPlugin(path, true);
                    Toast.makeText(getApplication(), "导入Native插件中...", Toast.LENGTH_SHORT).show();
                } else if (path != null && (path.endsWith(".apk") || path.endsWith(".dex"))) {
                    importPlugin(path, false);
                    Toast.makeText(getApplication(), "导入Dex/Apk插件中...", Toast.LENGTH_SHORT).show();
                }
            }

            /*
             * The proxy is referenced by name: resolving it through this loader
             * would drag the game's activity class into the launcher's loader,
             * which is exactly what puts the game's libraries in the wrong linker
             * namespace. AlterInstrumentation.newActivity() swaps this component
             * for the real com.mojang.minecraftpe.MainActivity.
             *
             * Waking the bridge first is what brings :game up here. The user asked
             * to play, so this is one of the places allowed to cold-boot it; the
             * proxy is declared with android:process=":game" and would start the
             * same process a moment later anyway.
             */
            intent.setComponent(new ComponentName(
                    LauncherConstants.SELF_PACKAGE,
                    LauncherConstants.GAME_HOST_ACTIVITY));

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            GameBridgeClient.get().wake(this);

            startActivity(intent);
            finish();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Copy an imported file into the shared plugin directory, then tell the runtime
     * process about it if that process happens to be up.
     *
     * <p>Both processes see the same {@code filesDir}, so the copy is enough when
     * {@code :game} is not running — its managers scan those directories when they
     * are constructed during the assembly. When it is running, the bridge is the
     * only way to make it rescan and load without a restart.
     */
    private void importPlugin(String path, boolean nativeLibrary) {
        if (nativeLibrary) {
            NativePluginManager.getManager(this).addNativePlugin(path);
        } else {
            PluginManager.getManager(this).addPlugin(path);
        }

        GameBridgeClient client = GameBridgeClient.get();
        if (client.isConnected()) {
            client.reloadPlugins();
        }
    }

}
