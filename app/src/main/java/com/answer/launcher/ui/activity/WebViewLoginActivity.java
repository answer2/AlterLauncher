package com.answer.launcher.ui.activity;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.answer.launcher.databinding.ActivityWebviewLoginBinding;
import com.answer.launcher.ui.LoginSession;

import java.util.function.Consumer;

/**
 * Optional in-app surface for the Microsoft device-code verification page, used when the player
 * turns on "在应用内使用 WebView 登录" in settings. It only displays the page and lets the
 * player sign in; the actual redemption is still detected by the sign-in worker's poll running in
 * the background, so nothing here has to intercept the redirect.
 *
 * <p>Microsoft frequently refuses embedded WebViews ("此浏览器或应用可能不安全"), so a main-frame
 * load error or an SSL failure hands off to the system browser and closes — the WebView is a
 * convenience, never a dead end.
 */
public class WebViewLoginActivity extends AppCompatActivity {

    public static final String EXTRA_URL = "alter_login_url";
    public static final String EXTRA_CODE = "alter_login_code";

    private ActivityWebviewLoginBinding binding;
    private String url;
    private boolean fellBack;

    /**
     * Closes this window the instant the sign-in worker confirms the code was redeemed — the
     * worker is the authority, so the WebView never has to parse Microsoft's success page.
     */
    private final Consumer<Boolean> loginFinished = success -> runOnUiThread(() -> {
        if (isFinishing()) {
            return;
        }
        if (success) {
            Toast.makeText(this, "登录成功", Toast.LENGTH_SHORT).show();
        }
        finish();
    });

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityWebviewLoginBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.webviewToolbar);
        binding.webviewToolbar.setNavigationOnClickListener(v -> finish());
        LoginSession.ACTIVE.attach(loginFinished);

        url = getIntent().getStringExtra(EXTRA_URL);
        String code = getIntent().getStringExtra(EXTRA_CODE);
        if (code != null && !code.isEmpty()) {
            binding.webviewHint.setText("登录代码 " + code + " · 完成登录后返回即可，正在自动检测。");
        }

        WebView web = binding.loginWeb;
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (request.isForMainFrame()) {
                    fallbackToSystemBrowser();
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
                fallbackToSystemBrowser();
            }
        });
        web.setWebChromeClient(new WebChromeClient());

        binding.openExternal.setOnClickListener(v -> openInSystemBrowser());

        if (url == null || url.isEmpty()) {
            Toast.makeText(this, "没有可打开的登录地址", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        web.loadUrl(url);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (web.canGoBack()) {
                    web.goBack();
                } else {
                    finish();
                }
            }
        });
    }

    private void openInSystemBrowser() {
        if (url == null) {
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable ignored) {
            Toast.makeText(this, "没有可用的浏览器", Toast.LENGTH_SHORT).show();
        }
    }

    private void fallbackToSystemBrowser() {
        if (fellBack) {
            return;
        }
        fellBack = true;
        Toast.makeText(this, "内置浏览器不被支持，已改用系统浏览器", Toast.LENGTH_LONG).show();
        openInSystemBrowser();
        finish();
    }

    @Override
    protected void onDestroy() {
        LoginSession.ACTIVE.detach(loginFinished);
        CookieManager.getInstance().flush();
        if (binding != null) {
            binding.loginWeb.destroy();
        }
        super.onDestroy();
    }
}
