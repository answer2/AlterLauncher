package com.answer.launcher.core.manager;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.net.Uri;
import android.widget.Toast;
import com.answer.launcher.core.LauncherConstants;
import com.answer.launcher.core.MinecraftLoader;
import com.answer.launcher.core.tool.SystemUtil;

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
        if (Alternative.getManager().getContext() == null) {
            MinecraftLoader.load(getApplication().getBaseContext());
        }
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
            if ("file".equalsIgnoreCase(scheme)) {
                String path = data.getPath();
                if (path.endsWith(".so")) {
                    NativePluginManager.getManager(this).addNativePlugin(path);
                    Toast.makeText(getApplication(), "导入Native插件中...", Toast.LENGTH_SHORT).show();
                } else if (path.endsWith(".apk") || path.endsWith(".dex")) {
                    PluginManager.getManager(this).addPlugin(path);
                    Toast.makeText(getApplication(), "导入Dex/Apk插件中...", Toast.LENGTH_SHORT).show();
                }
            }

            Class<?> clazz = Class.forName("com.answer.launcher.ui.activity.LauncherActivity", false, getClassLoader());
            intent.setClass(this, clazz);
            if(SystemUtil.checkClass(LauncherConstants.MINECRAFTACTIVITY))startActivity(intent);
            finish();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }


}
