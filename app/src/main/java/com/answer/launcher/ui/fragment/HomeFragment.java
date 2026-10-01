package com.answer.launcher.ui.fragment;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.answer.launcher.auth.SessionValidator;
import com.answer.launcher.core.MinecraftLoader;
import com.answer.launcher.core.LauncherConstants;
import com.answer.launcher.core.bridge.GameBridgeClient;
import com.answer.launcher.core.bridge.GameRuntime;
import com.answer.launcher.core.manager.Alternative;
import com.answer.launcher.core.manager.IdentityStore;
import com.answer.launcher.core.manager.MultiVersionManager;
import com.answer.launcher.databinding.FragmentHomeBinding;
import com.answer.launcher.ui.SignInSheet;
import com.answer.launcher.ui.LauncherPrefs;
import com.answer.launcher.ui.activity.CrashActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.hjq.permissions.OnPermissionCallback;
import com.hjq.permissions.Permission;
import com.hjq.permissions.XXPermissions;


import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;



public class HomeFragment extends Fragment {
    private FragmentHomeBinding binding;

    /** Guards against stacking a second network validity probe when one is already running. */
    private boolean accountCheckInFlight = false;

    /**
     * The bridge is the only source of truth about the runtime, and it now calls back on
     * the main thread whenever the state moves — including when {@code :game} kills
     * itself after the idle timeout, which is the transition that used to leave this page
     * showing a process that no longer existed.
     */
    private final GameBridgeClient.Listener bridgeListener =
            () -> requireActivity().runOnUiThread(this::refresh);

    @Override
    public void onResume() {
        super.onResume();
        GameBridgeClient.get().addListener(bridgeListener);
        refresh();
    }

    @Override
    public void onPause() {
        GameBridgeClient.get().removeListener(bridgeListener);
        super.onPause();
    }

    /** Redraws the account card, the version labels and the runtime line from live state. */
    private void refresh() {
        if (binding == null) {
            return;
        }
        Context host = requireContext().getApplicationContext();
        /*
         * The account card is the slow part: readAccount() walks two directories and
         * getPackageInfo is a binder call, and the honest "is this login still valid?" answer
         * is a network round trip the old code never made (it trusted a file-size heuristic).
         * So paint an in-between state now and let checkLogin() fill the card from a worker —
         * that keeps the UI responsive and finally reflects a session that expired since the
         * last look.
         */
        binding.accountLogin.setText("…");
        binding.userStatus.setText("正在检查登录状态…");
        checkLogin(host);

        String version = guestVersion(host);
        binding.versionLabel.setText(version == null ? "VERSION" : version);
        binding.archLabel.setText(Build.SUPPORTED_ABIS.length > 0
                ? (Build.SUPPORTED_ABIS[0].contains("64") ? "64 位" : "32 位") : "ARCH");

        String state = GameBridgeClient.get().runtimeState();
        int pid = GameBridgeClient.get().gamePid();
        String text;
        if (state.startsWith(GameRuntime.FAILED_PREFIX)) {
            text = "运行时装配失败：" + state.substring(GameRuntime.FAILED_PREFIX.length());
        } else if (GameRuntime.READY.equals(state)) {
            text = "运行中 · 进程 " + pid;
        } else if (GameRuntime.ASSEMBLING.equals(state)) {
            text = "正在装配运行时…";
        } else {
            text = "运行时未启动";
        }
        binding.runtimeStatus.setText(text);
        binding.loadGame.setText(GameRuntime.READY.equals(state) ? "回到游戏" : "启动");
    }

    /**
     * Reads what the on-disk store can prove, then — only when it claims a sign-in — asks Xbox
     * whether the persisted chain still authenticates. Both steps block on file/binder/network,
     * so they run on a worker and the result is posted back before the card is repainted.
     */
    private void checkLogin(Context host) {
        if (accountCheckInFlight) {
            return;
        }
        accountCheckInFlight = true;
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            IdentityStore.Account disk = IdentityStore.readAccount(host);
            SessionValidator.Status validity = new SessionValidator.Status();
            try {
                // Only spend a network round trip when the user opted in; otherwise the card
                // reflects the local record alone.
                if (disk.signedIn && LauncherPrefs.autoValidateLogin(host)) {
                    String version = SignInSheet.gameVersion(host);
                    validity = SessionValidator.check(IdentityStore.accountRoot(host), version,
                            SignInSheet.userAgent(version));
                }
            } catch (Throwable probeFailure) {
                // The card must never strand on "checking": fall back to whatever the disk
                // proved and let the next resume retry the network answer.
                Log.w("HomeFragment", "login validity probe failed", probeFailure);
            }
            final IdentityStore.Account diskRef = disk;
            final SessionValidator.Status validityRef = validity;
            main.post(() -> {
                accountCheckInFlight = false;
                if (binding == null || !isAdded()) {
                    return;
                }
                applyAccountCard(diskRef, validityRef);
            });
        }, "login-check").start();
    }

    /**
     * Paints the account card from the cached store plus the live validity answer. A persisted
     * manager that fails to authenticate is shown as expired and prompts a re-login; a sign-in
     * that was written by the game rather than the launcher has no manager here to test, so it
     * stays "signed in" instead of falsely reporting an expiry we cannot actually prove.
     */
    private void applyAccountCard(IdentityStore.Account account, SessionValidator.Status validity) {
        boolean expired = account.signedIn && validity.hasAccount && !validity.valid;
        boolean signedIn = account.signedIn && !expired;
        if (signedIn) {
            binding.userId.setText(validity.gamertag != null ? validity.gamertag
                    : (account.xuid != null ? "XUID " + account.xuid : "已登录"));
            binding.userStatus.setText(account.shared
                    ? "所有版本共用这份登录状态"
                    : "仅本版本使用这份登录状态");
            binding.accountLogin.setText("重新登录");
        } else if (expired) {
            binding.userId.setText("登录已失效");
            binding.userStatus.setText("登录已过期或被撤销，请重新登录");
            binding.accountLogin.setText("重新登录");
        } else {
            binding.userId.setText("未登录");
            binding.userStatus.setText("未登录 · 点击下方按钮使用微软账号登录");
            binding.accountLogin.setText("登录");
        }
        binding.accountSignout.setVisibility(signedIn ? View.VISIBLE : View.GONE);
    }

    /**
     * Sign-out is destructive (the saved tokens are gone until a fresh browser login), so it is
     * gated on the game not running and confirmed first; the file wipe itself runs off the main
     * thread.
     */
    private void confirmSignOut() {
        String state = GameBridgeClient.get().runtimeState();
        if (GameRuntime.READY.equals(state) || GameRuntime.ASSEMBLING.equals(state)) {
            Toast.makeText(requireContext(), "游戏正在运行，先退出游戏再操作", Toast.LENGTH_SHORT).show();
            return;
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("退出登录")
                .setMessage("将清除本机保存的微软登录状态（共享模式下会同时影响所有版本），需要重新在浏览器登录才能恢复。确定退出？")
                .setNegativeButton("取消", null)
                .setPositiveButton("退出登录", (d, w) -> {
                    Context host = requireContext().getApplicationContext();
                    new Thread(() -> {
                        boolean ok = IdentityStore.signOut(host);
                        requireActivity().runOnUiThread(() -> {
                            Toast.makeText(requireContext(),
                                    ok ? "已退出登录" : "已退出，但部分文件未能清除",
                                    Toast.LENGTH_SHORT).show();
                            refresh();
                        });
                    }, "signout").start();
                })
                .show();
    }

    private String guestVersion(Context host) {
        try {
            return host.getPackageManager()
                    .getPackageInfo(LauncherConstants.MINECRAFTPACKAGE, 0).versionName;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentHomeBinding.inflate(inflater, container, false);

        binding.loadGame.setOnClickListener(view->{
            XXPermissions.with(this)
                    // 申请多个权限
                    .permission(Permission.Group.STORAGE)
                    // 设置不触发错误检测机制（局部设置）
                    .unchecked()
                    .request(
                            new OnPermissionCallback() {
                                @Override
                                public void onGranted(@NonNull List<String> permissions, boolean allGranted) {
                                    if (!allGranted) {
                                        Log.d("Main", "获取部分权限成功，但部分权限未正常授予");
                                        return;
                                    }
                                    Log.d("Main", "获取存储成功");
                                }

                                @Override
                                public void onDenied(@NonNull List<String> permissions, boolean doNotAskAgain) {
                                    if (doNotAskAgain) {
                                        Log.d("Main", "被永久拒绝授权，请手动授予存储权限");
                                        // 如果是被永久拒绝就跳转到应用权限系统设置页面
                                        XXPermissions.startPermissionActivity(getActivity(), permissions);
                                    } else {
                                        Log.d("Main", "获取录音和日历权限失败");
                                    }
                                }
                            });



            new Thread(()->{
//                try {
//                    Constants.MINECRAFTPACKAGE = "com.mojang.minecrafta";
//
//                    AssestPackageManager.getManager().setPackagePath("/sdcard/base.apk");
//                    AssestPackageManager.getManager().setFixPackagePath("/sdcard/main.apk");
//                    AssestPackageManager.getManager().load();
//
//                } catch (Exception e) {
//                    e.printStackTrace();
//                }

               /*
                 * The runtime is assembled inside :game by that process' Application,
                 * so this side only asks for the window: LaunchMinecraft() starts the
                 * launcher-owned proxy, which is the cold-boot trigger.
                 */
                requireActivity().runOnUiThread(
                        () -> Alternative.getManager().LaunchMinecraft(requireActivity()));
            }).start();
        });

        /*
         * Sign-in used to be the game's job because the token envelopes the SDK writes are
         * JWEs sealed with the title key and this device's keypair. It is not: the launcher can
         * run the device-code flow itself and hand XAL the store it expects to find, which is
         * what this button does now. The card above still reads the state back from disk, so
         * what the player sees here is what the game will boot into either way.
         */
        binding.accountLogin.setOnClickListener(view -> SignInSheet.show(requireActivity(),
                (success, message) -> {
                    Toast.makeText(requireContext(),
                            success ? "登录完成，启动游戏即是已登录状态" : "登录失败：" + message,
                            Toast.LENGTH_LONG).show();
                    refresh();
                }));

        binding.accountSignout.setOnClickListener(view -> confirmSignOut());

        binding.versionSetting.setOnClickListener(view -> {
            Intent intent = new Intent(getActivity(), CrashActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra("CRASH_LOG", "错误🤣测试🤣🤣🤣错误🤣测试🤣🤣🤣错误🤣测试🤣🤣🤣错误🤣测试🤣🤣🤣错误🤣测试🤣🤣🤣错误🤣测试🤣🤣🤣错误🤣测试🤣🤣🤣错误🤣测试🤣🤣🤣错误🤣测试🤣🤣🤣错误🤣测试🤣🤣🤣错误🤣测试🤣🤣🤣错误🤣测试🤣🤣🤣错误🤣测试🤣🤣🤣错误🤣测试🤣🤣🤣错误🤣测试🤣🤣🤣错误🤣测试🤣🤣🤣错误🤣测试🤣🤣🤣错误🤣测试🤣🤣🤣错误🤣测试🤣🤣🤣错误🤣测试🤣🤣🤣错误🤣测试🤣🤣🤣错误🤣测试🤣🤣🤣错误🤣测试🤣🤣🤣错误🤣测试🤣🤣🤣");
            getActivity().startActivity(intent);
        });

        binding.selectVersion.setOnClickListener(v->{
            try {
                File file = new File("/sdcard/base.apk");
                if (!file.exists()){
                    Toast.makeText(getActivity(), "似乎没有导入安装包", Toast.LENGTH_LONG).show();
                    return;
                }

                Context c =  MultiVersionManager.getManager()
                        .createPackageContext(requireActivity().getApplication().getBaseContext(),
                        Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY,
                                "/sdcard/base.apk");
                System.out.println(c);
                System.out.println(c.getPackageName());
                Toast.makeText(getActivity(), "获取到Context ：" + c +"  包名：" + c.getPackageName(), Toast.LENGTH_LONG).show();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    System.out.println(c.getOpPackageName());
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });


        /*
        binding.button.setOnClickListener(view->{
            if (!TextUtils.isEmpty(binding.edit.getText()))
                Constants.MINECRAFTPACKAGE = binding.edit.getText().toString();


        });

        binding.laucher.setOnClickListener(view->{

        });

         */
        return binding.getRoot();
    }


    public String getFromAssets(InputStream is) {
        StringBuilder result = new StringBuilder();
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(is));
            String line;
            while ((line = br.readLine()) != null) {
                result.append(line);
            }
            is.close();
            br.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return result.toString();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        binding = null;
    }
}