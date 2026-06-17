package com.answer.launcher.ui.activity;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;

import com.answer.launcher.databinding.ActivityCrashBinding;
import com.answer.launcher.utils.ThemeUtils;

public class CrashActivity extends AppCompatActivity {
    private ActivityCrashBinding binding;
    private String crashLog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        crashLog = getIntent().getStringExtra("CRASH_LOG");

        // set statusBar color
        EdgeToEdge.enable(this);
        boolean isDarkMode = ThemeUtils.isSystemInDarkTheme(this);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        ThemeUtils.statusBarColor(this, Color.TRANSPARENT, isDarkMode);
        ThemeUtils.hideSystemUI(this);

        binding = ActivityCrashBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setSupportActionBar(binding.toolbar);
        setTitle("AlterLauncher Crashed");

        binding.crashText.setText(crashLog);

        binding.copyButton.setOnClickListener(view -> copyErrorLog());
        binding.restartButton.setOnClickListener(view -> restartApp());
    }

    private void copyErrorLog() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("Crash Log", crashLog));
    }

    private void restartApp() {
        Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
        }
        finish();
        System.exit(0);
    }
}