package com.answer.launcher.ui.splash;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;


import com.answer.launcher.MainActivity;
import com.answer.launcher.databinding.ActivitySplashBinding;
import com.answer.launcher.utils.ThemeUtils;

public class SplashActivity extends AppCompatActivity {

    private ActivitySplashBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        EdgeToEdge.enable(this);
        boolean isDarkMode = ThemeUtils.isSystemInDarkTheme(this);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        ThemeUtils.statusBarColor(this, Color.TRANSPARENT, isDarkMode);
        ThemeUtils.hideSystemUI(this);

        binding = ActivitySplashBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        ImageView logo = binding.imgLogo;
        TextView appText = binding.appText;

        // 设置初始透明度为0（完全透明）
        logo.setAlpha(0f);
        appText.setAlpha(0f);

        // 创建组合动画
        AnimatorSet animatorSet = new AnimatorSet();

        // Logo动画：渐变显示 + 缩放 + 旋转
        ObjectAnimator fadeInLogo = ObjectAnimator.ofFloat(logo, "alpha", 0f, 1f);
        ObjectAnimator scaleXLogo = ObjectAnimator.ofFloat(logo, "scaleX", 0.5f, 1f);
        ObjectAnimator scaleYLogo = ObjectAnimator.ofFloat(logo, "scaleY", 0.5f, 1f);
        ObjectAnimator rotateLogo = ObjectAnimator.ofFloat(logo, "rotation", -30f, 0f);

        // 文本动画：渐变显示 + 从下方平移
        ObjectAnimator fadeInText = ObjectAnimator.ofFloat(appText, "alpha", 0f, 1f);
        ObjectAnimator translateText = ObjectAnimator.ofFloat(
                appText, "translationY", 100f, 0f
        );

        // 设置动画时长
        fadeInLogo.setDuration(1200);
        scaleXLogo.setDuration(1200);
        scaleYLogo.setDuration(1200);
        rotateLogo.setDuration(1200);
        fadeInText.setDuration(1000);
        translateText.setDuration(1000);

        // 组合动画
        animatorSet.playTogether(
                fadeInLogo, scaleXLogo, scaleYLogo, rotateLogo
        );

        // 文本动画在logo动画之后播放
        animatorSet.play(fadeInText).with(translateText).after(400);

        // 启动动画
        animatorSet.start();

        // 3秒后跳转主界面
        new Handler().postDelayed(() -> {
            startActivity(new Intent(SplashActivity.this, MainActivity.class));
            finish();
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        }, 3000);
    }

}