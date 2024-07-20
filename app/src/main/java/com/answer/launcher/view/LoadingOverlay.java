package com.answer.launcher.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.util.AttributeSet;
import android.view.View;
import com.answer.launcher.R;
import com.answer.launcher.core.manager.Alternative;

/**
 * @Author AnswerDev
 * @Date 2024/07/09 10:09
 */

public class LoadingOverlay extends View {
    private Handler handler = new Handler();
    private static final int LOGO_BACKGROUND_COLOR = Color.argb(255, 239, 50, 61);
    private static final int LOGO_BACKGROUND_COLOR_DARK = Color.argb(255, 0, 0, 0);
    private static final float SMOOTHING = 0.95f;
    public static final long FADE_OUT_TIME = 1000L;
    public static final long FADE_IN_TIME = 500L;

    private Drawable logoDrawable;
    private float currentProgress;
    private long fadeOutStart = -1L;
    private long fadeInStart = -1L;
    
    private boolean fadeIn;
    private Paint backgroundPaint;
    private Paint progressPaint;
    private Paint borderPaint;

    private static final long BACKGROUND_PROGRESS_FADE_OUT_TIME = 500L;
    private static final long LOGO_FADE_OUT_TIME = 500L;
    private boolean isAnimationStarted = false;
    private OnFadeOutListener fadeOutListener;

    // 定义一个接口用于动画完成监听
    public interface OnFadeOutListener {
        void onFadeOutComplete();
    }
    

    public LoadingOverlay(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public LoadingOverlay(Context context) {
        super(context);
        init();
    }

    private void init() {
        logoDrawable = Alternative.getManager().mContext.getDrawable( R.drawable.mojangstudios);
        backgroundPaint = new Paint();
        backgroundPaint.setColor(LOGO_BACKGROUND_COLOR);
        progressPaint = new Paint();
        progressPaint.setColor(Color.WHITE);
        progressPaint.setStyle(Paint.Style.FILL);

        borderPaint = new Paint();
        borderPaint.setColor(Color.WHITE);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(5); // Border width
    }


    public void setProgress(float progress) {
        this.currentProgress = Math.max(0, Math.min(progress, 1)); // Clamp progress between 0 and 1
        invalidate(); // Redraw the view
    }
    
    public float getProgress(){
        return this.currentProgress;
    }

    public void setFadeIn(boolean fadeIn) {
        this.fadeIn = fadeIn;
    }

    public void setOnFadeOutListener(OnFadeOutListener listener) {
        this.fadeOutListener = listener;
    }

    public void startFadeOutAnimation() {
        if (!isAnimationStarted) {
            isAnimationStarted = true;
            fadeOutStart = System.currentTimeMillis();
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int width = getWidth();
        int height = getHeight();
        long currentTime = System.currentTimeMillis();

        float backgroundProgressAlpha = 1.0f;
        float logoAlpha = 1.0f;

        if (isAnimationStarted && fadeOutStart > -1L) {
            long elapsedTime = currentTime - fadeOutStart;

            // 背景和进度条的淡出
            if (elapsedTime < BACKGROUND_PROGRESS_FADE_OUT_TIME) {
                backgroundProgressAlpha = 1.0f - (float)elapsedTime / BACKGROUND_PROGRESS_FADE_OUT_TIME;
            } else {
                backgroundProgressAlpha = 0f;

                // Logo的淡出
                long logoElapsedTime = elapsedTime - BACKGROUND_PROGRESS_FADE_OUT_TIME;
                if (logoElapsedTime < LOGO_FADE_OUT_TIME) {
                    logoAlpha = 1.0f - (float)logoElapsedTime / LOGO_FADE_OUT_TIME;
                } else {
                    logoAlpha = 0f;
                    isAnimationStarted = false;
                    if (fadeOutListener != null) {
                        fadeOutListener.onFadeOutComplete();
                    }
                }
            }
        }

        // 绘制背景
        backgroundPaint.setAlpha((int) (backgroundProgressAlpha * 255));
        canvas.drawRect(0, 0, width, height, backgroundPaint);

        // 绘制logo
        if (logoDrawable != null) {
            int logoMaxWidth = (int) (width * 0.5);
            int logoMaxHeight = (int) (height * 0.25);
            int logoWidth = Math.min(logoMaxWidth, logoDrawable.getIntrinsicWidth());
            int logoHeight = Math.min(logoMaxHeight, logoDrawable.getIntrinsicHeight());
            int logoLeft = (width - logoWidth) / 2;
            int logoTop = (height - logoHeight) / 2;

            logoDrawable.setBounds(logoLeft, logoTop, logoLeft + logoWidth, logoTop + logoHeight);
            logoDrawable.setAlpha((int) (logoAlpha * 255));
            logoDrawable.draw(canvas);
        }

        // 绘制进度条和边框
        int progressBarWidth = (int) (width * 0.5);
        int progressBarHeight = 20;
        int borderHeight = progressBarHeight + progressBarHeight / 4;
        int progressBarLeft = (width - progressBarWidth) / 2;
        int progressBarTop = (int) (height * 0.75);
        int progressWidth = (int) (progressBarWidth * currentProgress);

        borderPaint.setAlpha((int) (backgroundProgressAlpha * 255));
        progressPaint.setAlpha((int) (backgroundProgressAlpha * 255));

        // 绘制边框
        canvas.drawRect(progressBarLeft, progressBarTop, progressBarLeft + progressBarWidth, progressBarTop + borderHeight, borderPaint);

        // 绘制进度条
        int padding = borderHeight - progressBarHeight;
        canvas.drawRect(progressBarLeft + padding, progressBarTop + padding, progressBarLeft + progressWidth - padding, progressBarTop + borderHeight - padding, progressPaint);

        // 如果动画还在进行，继续刷新视图
        if (isAnimationStarted) {
            invalidate();
        }
    }
}
