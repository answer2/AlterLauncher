package com.answer.launcher.ui;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The launcher's own small settings bag. Identity mode deliberately lives as a file under the
 * data tree (the {@code :game} process reads it before any UI exists), but a login-surface
 * preference is pure UI and belongs in the app's private prefs, not the shared tree.
 */
public final class LauncherPrefs {

    private static final String FILE = "alter_launcher_prefs";
    private static final String KEY_LOGIN_WEBVIEW = "login_in_webview";
    private static final String KEY_AUTO_VALIDATE = "auto_validate_login";
    private static final String KEY_DYNAMIC_COLOR = "dynamic_color";

    private LauncherPrefs() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** When true the verification URL opens in the in-app WebView instead of the system browser. */
    public static boolean loginInWebView(Context context) {
        return prefs(context).getBoolean(KEY_LOGIN_WEBVIEW, false);
    }

    public static void setLoginInWebView(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_LOGIN_WEBVIEW, enabled).apply();
    }

    /** Whether to re-check the saved token against Xbox on every screen visit. Default on. */
    public static boolean autoValidateLogin(Context context) {
        return prefs(context).getBoolean(KEY_AUTO_VALIDATE, true);
    }

    public static void setAutoValidateLogin(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_AUTO_VALIDATE, enabled).apply();
    }

    /**
     * Material You dynamic color. Off by default so the launcher ships with its own accent
     * palette; the user can opt into wallpaper-based color in settings. Read once at process
     * start, so a change needs a restart.
     */
    public static boolean dynamicColor(Context context) {
        return prefs(context).getBoolean(KEY_DYNAMIC_COLOR, false);
    }

    public static void setDynamicColor(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_DYNAMIC_COLOR, enabled).apply();
    }
}
