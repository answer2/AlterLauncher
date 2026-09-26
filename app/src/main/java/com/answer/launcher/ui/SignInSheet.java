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
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.answer.launcher.auth.AccountStore;
import com.answer.launcher.auth.HostAuthManager;
import com.answer.launcher.auth.XalExporter;
import com.answer.launcher.core.bridge.GameBridgeClient;
import com.answer.launcher.core.bridge.GameRuntime;
import com.answer.launcher.core.manager.IdentityStore;

import net.raphimc.minecraftauth.bedrock.BedrockAuthManager;
import net.raphimc.minecraftauth.msa.model.MsaDeviceCode;

import java.io.File;
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

        TextView status = new TextView(activity);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        status.setText("正在向 Xbox Live 申请登录代码…");

        TextView code = new TextView(activity);
        code.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f);
        code.setLetterSpacing(0.18f);
        code.setTypeface(code.getTypeface(), android.graphics.Typeface.BOLD);
        code.setGravity(Gravity.CENTER);
        code.setTextIsSelectable(true);
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
        box.addView(code);
        box.addView(link);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("登录 Microsoft 账户")
                .setView(box)
                .setCancelable(true)
                .setNegativeButton("取消", null)
                .setNeutralButton("复制代码", null)
                .setPositiveButton("打开网页", null)
                .create();
        dialog.show();

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
            if (deviceCode == null) {
                return;
            }
            try {
                activity.startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse(deviceCode.getDirectVerificationUri())));
            } catch (Throwable ignored) {
                // No browser able to take the intent; the code is on screen and copyable.
            }
        });

        Thread worker = new Thread(() -> run(activity, app, ui, dialog, status, code, link,
                pending, callback), "microsoft-signin");
        worker.setDaemon(true);
        worker.start();
    }

    private static void run(Activity activity, Context app, Handler ui, AlertDialog dialog,
                            TextView status, TextView code, TextView link,
                            AtomicReference<MsaDeviceCode> pending, Callback callback) {
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
                    }));

            File root = IdentityStore.accountRoot(app);
            String localId = XalExporter.export(root, manager);
            remember(app, root, manager, localId);
            ui.post(() -> {
                dialog.dismiss();
                callback.onFinished(true, null);
            });
        } catch (Throwable failure) {
            String message = describe(failure);
            ui.post(() -> {
                if (dialog.isShowing()) {
                    status.setText(message);
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
        String message = HostAuthManager.lastError();
        if (message == null || message.isEmpty()) {
            message = failure.getClass().getSimpleName()
                    + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
        }
        return message;
    }

    /** The guest's own version name — XAL ties the token request to the title build. */
    private static String gameVersion(Context app) {
        try {
            return app.getPackageManager()
                    .getPackageInfo("com.mojang.minecraftpe", 0).versionName;
        } catch (Throwable ignored) {
            return "1.0.0";
        }
    }

    private static String userAgent(String version) {
        return "Mozilla/5.0 (Linux; Android " + Build.VERSION.RELEASE + "; " + Build.MODEL
                + ") Minecraft/" + version;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
