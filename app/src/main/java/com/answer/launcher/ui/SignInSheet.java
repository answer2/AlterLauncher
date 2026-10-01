package com.answer.launcher.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.answer.launcher.auth.AccountStore;
import com.answer.launcher.auth.HostAuthManager;
import com.answer.launcher.auth.XalExporter;
import com.answer.launcher.core.LauncherConstants;
import com.answer.launcher.core.bridge.GameBridgeClient;
import com.answer.launcher.core.bridge.GameRuntime;
import com.answer.launcher.core.manager.IdentityStore;
import com.answer.launcher.ui.activity.WebViewLoginActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import net.raphimc.minecraftauth.bedrock.BedrockAuthManager;
import net.raphimc.minecraftauth.msa.model.MsaDeviceCode;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Microsoft sign-in that happens in the launcher, not in the game.
 *
 * <p>The device-code flow is the only one that works from a process with no browser of its
 * own: we ask Xbox Live for a short code, the player types it into any browser they already
 * have — phone, laptop, whoever is nearby — and this process polls until it is redeemed.
 * Nothing is intercepted, no WebView is opened, and the credentials never pass through us.
 *
 * <p>What we do with the result is the interesting part. {@link XalExporter} writes it into
 * the on-disk contract the game's own XAL layer reads at boot, so the game comes up already
 * signed in. The root it writes into comes from {@link IdentityStore#accountRoot(Context)},
 * which is the same switch the rest of the isolation story uses: shared mode points at the
 * one store every installed version reads, isolated mode at a per-version directory. Choosing
 * to share login data is therefore not a special case here — it is only where the files land.
 *
 * <p>The game process must not be running. XAL keeps this store open and rewrites it on
 * sign-in and on exit, so exporting underneath it is a race the player would lose.
 */
public final class SignInSheet {

    private static final String TAG = "SignInSheet";

    /** Reports the outcome on the main thread. {@code message} is null on success. */
    public interface Callback {
        void onFinished(boolean success, @Nullable String message);
    }

    private SignInSheet() {
    }

    public static void show(@NonNull Activity activity, @NonNull Callback callback) {
        String state = GameBridgeClient.get().runtimeState();
        if (GameRuntime.READY.equals(state) || GameRuntime.ASSEMBLING.equals(state)) {
            callback.onFinished(false, "游戏正在运行，先退出游戏再登录");
            return;
        }

        Context app = activity.getApplicationContext();
        Handler ui = new Handler(Looper.getMainLooper());
        AtomicReference<MsaDeviceCode> pending = new AtomicReference<>();
        AtomicReference<Thread> workerRef = new AtomicReference<>();
        AtomicBoolean cancelled = new AtomicBoolean(false);

        TextView status = new TextView(activity);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        status.setText("正在向 Xbox Live 申请登录代码…");

        ProgressBar spinner = new ProgressBar(activity);
        spinner.setIndeterminate(true);
        LinearLayout.LayoutParams spinnerLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        spinnerLp.gravity = Gravity.CENTER;
        spinnerLp.topMargin = dp(activity, 10);
        spinnerLp.bottomMargin = dp(activity, 4);

        TextView code = new TextView(activity);
        code.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f);
        code.setLetterSpacing(0.18f);
        code.setTypeface(code.getTypeface(), android.graphics.Typeface.BOLD);
        code.setGravity(Gravity.CENTER);
        code.setPadding(0, dp(activity, 14), 0, dp(activity, 4));
        code.setVisibility(View.GONE);

        TextView link = new TextView(activity);
        link.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        link.setGravity(Gravity.CENTER);
        link.setVisibility(View.GONE);

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 24);
        box.setPadding(pad, pad, pad, 0);
        box.addView(status);
        box.addView(spinner, spinnerLp);
        box.addView(code);
        box.addView(link);

        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle("登录 Microsoft 账户")
                .setView(box)
                .setCancelable(true)
                .setNegativeButton("取消", null)
                .setNeutralButton("复制代码", null)
                .setPositiveButton("打开网页", null)
                .create();

        /*
         * The poll inside the library sleeps between attempts and is only stopped by an
         * interrupt; without a way to cancel it a player who backs out keeps a daemon thread
         * chasing a redeem for up to fifteen minutes. Interruption is the contract the flow
         * already documents (getToken throws InterruptedException), so route cancel through it.
         */
        Runnable abort = () -> {
            cancelled.set(true);
            Thread worker = workerRef.get();
            if (worker != null) {
                worker.interrupt();
            }
        };
        dialog.setOnCancelListener(d -> abort.run());
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
            abort.run();
            dialog.dismiss();
        });

        /*
         * The default listeners dismiss the dialog, and neither of those actions ends the
         * sign-in — the player may want to copy the code twice. Replacing them after show()
         * is the documented way to keep a dialog open across a button press.
         */
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            MsaDeviceCode deviceCode = pending.get();
            if (deviceCode == null) {
                return;
            }
            ClipboardManager clipboard =
                    (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(
                        ClipData.newPlainText("Microsoft 登录代码", deviceCode.getUserCode()));
                status.setText("代码已复制，在任意浏览器里粘贴即可");
            }
        });
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            MsaDeviceCode deviceCode = pending.get();
            if (deviceCode != null) {
                openVerification(activity, deviceCode);
            }
        });

        // While the code waits to be redeemed, show elapsed time so the wait reads as progress,
        // not a freeze. Stopped as soon as the redemption lands (see the sink below).
        Runnable[] ticker = new Runnable[1];
        long[] startedAt = {0L};
        boolean[] redeemWait = {false};
        ticker[0] = () -> {
            if (!dialog.isShowing() || cancelled.get()) {
                return;
            }
            if (redeemWait[0] && startedAt[0] != 0L) {
                long secs = (System.currentTimeMillis() - startedAt[0]) / 1000L;
                status.setText("等待你在浏览器完成登录…（已等待 " + secs + " 秒，检测中）");
            }
            ui.postDelayed(ticker[0], 1000L);
        };

        Thread worker = new Thread(() -> run(activity, app, ui, dialog, spinner, status, code,
                link, pending, cancelled, callback, ticker, startedAt, redeemWait),
                "microsoft-signin");
        workerRef.set(worker);
        worker.setDaemon(true);
        worker.start();
        ui.post(ticker[0]);
    }

    private static void run(Activity activity, Context app, Handler ui, AlertDialog dialog,
                            ProgressBar spinner, TextView status, TextView code, TextView link,
                            AtomicReference<MsaDeviceCode> pending, AtomicBoolean cancelled,
                            Callback callback, Runnable[] ticker, long[] startedAt,
                            boolean[] redeemWait) {
        String version = gameVersion(app);
        try {
            BedrockAuthManager manager = HostAuthManager.signInDeviceCode(
                    HostAuthManager.newHttpClient(userAgent(version)), version,
                    deviceCode -> ui.post(() -> {
                        pending.set(deviceCode);
                        code.setText(deviceCode.getUserCode());
                        code.setVisibility(View.VISIBLE);
                        link.setText(deviceCode.getVerificationUri());
                        link.setVisibility(View.VISIBLE);
                        status.setText("在任意设备上打开这个地址并输入下面的代码，然后等待即可。");
                        // Begin the elapsed wait indicator: from here the network poll runs.
                        startedAt[0] = System.currentTimeMillis();
                        redeemWait[0] = true;
                    }));

            // The call returned, which only happens once the code has been redeemed: stop the
            // waiting ticker, tell any open in-app WebView to close itself now that login is
            // done, and move into the account-sync phase (XalExporter makes several more
            // network calls here, which is where a bare dialog looked like it had hung).
            redeemWait[0] = false;
            LoginSession.ACTIVE.finish(true);
            ui.post(() -> {
                if (!dialog.isShowing()) {
                    return;
                }
                spinner.setVisibility(View.VISIBLE);
                status.setText("登录已验证，正在同步账户信息，请稍候…");
            });

            File root = IdentityStore.accountRoot(app);
            String localId = XalExporter.export(root, manager);
            remember(app, root, manager, localId);
            ui.post(() -> {
                dialog.dismiss();
                callback.onFinished(true, null);
            });
        } catch (Throwable failure) {
            Log.w(TAG, "device-code sign-in failed for version " + version, failure);
            // Close any open in-app WebView too: the redeem never completed, so it has nothing
            // left to show and would otherwise sit on a stale page.
            LoginSession.ACTIVE.finish(false);
            if (cancelled.get()) {
                ui.post(() -> callback.onFinished(false, "已取消登录"));
                return;
            }
            String message = describe(failure);
            ui.post(() -> {
                if (dialog.isShowing()) {
                    status.setText(message);
                    callback.onFinished(false, message);
                } else {
                    callback.onFinished(false, message);
                }
            });
        }
    }

    /**
     * Keeps the serialised manager next to the exported store. XAL can refresh the tokens it
     * was handed, but it cannot re-authenticate a device that has been wiped, and neither can
     * we from the files alone — this is what lets a later build offer "log in again" without a
     * browser, and what an account switcher would list.
     */
    private static void remember(Context app, File root, BedrockAuthManager manager,
                                 String localId) {
        try {
            AccountStore store = new AccountStore(new File(root, AccountStore.FILE_NAME));
            AccountStore.Account account = new AccountStore.Account();
            account.xuid = localId;
            account.gamertag = HostAuthManager.chain(manager).getIdentityDisplayName();
            account.updatedAt = System.currentTimeMillis();
            account.auth = HostAuthManager.serialize(manager);
            store.putActive(account);
            store.save();
        } catch (Throwable ignored) {
            // The game is signed in either way; the bookkeeping can wait for the next login.
        }
    }

    private static String describe(Throwable failure) {
        String direct = failure.getClass().getSimpleName()
                + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
        String detail = HostAuthManager.lastError();
        /*
         * lastError() is only written by the refresh/chain helpers, so on the sign-in path it
         * is still the empty-slot placeholder — and preferring it printed "登录失败：unknown"
         * over the exception that actually said what went wrong.
         */
        if (detail == null || detail.isEmpty() || "unknown".equals(detail)) {
            return direct;
        }
        return direct + "（" + detail + "）";
    }

    /**
     * The guest's own version name, trimmed to the three parts an XAL request carries. The
     * installed build reports a fourth hotfix component; every sign-in that has ever worked
     * from this code path sent the release ("1.26.45"), and the store endpoint is picky.
     */
    public static String gameVersion(Context app) {
        String version = null;
        try {
            version = app.getPackageManager()
                    .getPackageInfo(LauncherConstants.MINECRAFTPACKAGE, 0).versionName;
        } catch (Throwable ignored) {
            // Not installed as its own package; fall through to whatever we can guess.
        }
        if (version == null || version.isEmpty()) {
            return "1.0.0";
        }
        String[] parts = version.split("\\.");
        return parts.length >= 4 ? parts[0] + "." + parts[1] + "." + parts[2] : version;
    }

    public static String userAgent(String version) {
        return "Mozilla/5.0 (Linux; Android " + Build.VERSION.RELEASE + "; " + Build.MODEL
                + ") Minecraft/" + version;
    }

    /**
     * Opens the verification page the way the player chose in settings: the in-app WebView when
     * enabled, otherwise the system browser. The WebView auto-falls back if Microsoft rejects it,
     * and the code redemption is detected by the sign-in worker's poll regardless of where the
     * page was opened.
     */
    private static void openVerification(Activity activity, MsaDeviceCode deviceCode) {
        String url = deviceCode.getDirectVerificationUri();
        if (LauncherPrefs.loginInWebView(activity.getApplicationContext())) {
            Intent intent = new Intent(activity, WebViewLoginActivity.class);
            intent.putExtra(WebViewLoginActivity.EXTRA_URL, url);
            intent.putExtra(WebViewLoginActivity.EXTRA_CODE, deviceCode.getUserCode());
            activity.startActivity(intent);
            return;
        }
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable ignored) {
            // No browser able to take the intent; the code is on screen and copyable.
        }
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
