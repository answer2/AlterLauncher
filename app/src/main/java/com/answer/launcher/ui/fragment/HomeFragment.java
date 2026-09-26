package com.answer.launcher.ui.fragment;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.answer.launcher.core.MinecraftLoader;
import com.answer.launcher.core.LauncherConstants;
import com.answer.launcher.core.bridge.GameBridgeClient;
import com.answer.launcher.core.bridge.GameRuntime;
import com.answer.launcher.core.manager.Alternative;
import com.answer.launcher.core.manager.IdentityStore;
import com.answer.launcher.core.manager.MultiVersionManager;
import com.answer.launcher.databinding.FragmentHomeBinding;
import com.answer.launcher.ui.activity.CrashActivity;
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
        IdentityStore.Account account = IdentityStore.readAccount(host);
        if (account.signedIn) {
            binding.userId.setText(account.xuid != null
                    ? "XUID " + account.xuid : "已登录");
            binding.userStatus.setText(account.shared
                    ? "所有版本共用这份登录状态"
                    : "仅本版本使用这份登录状态");
            binding.accountLogin.setText("重新登录");
        } else {
            binding.userId.setText("未登录");
            binding.userStatus.setText("登录在游戏内完成，启动器只负责保管和显示状态");
            binding.accountLogin.setText("登录");
        }
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
         * Sign-in is a conversation between the game and Xbox Live: the token envelopes
         * the SDK writes are JWEs sealed with the title key and this device's keypair, so
         * the launcher cannot mint them and does not try to. What it can do is start the
         * runtime that owns the flow and report the result afterwards, which is what this
         * button does — and the account card above it reads the store back, so the state
         * the user sees here is the state the game will boot into.
         */
        binding.accountLogin.setOnClickListener(view -> {
            Toast.makeText(requireContext(), "登录在游戏内完成，完成后这里会显示账号",
                    Toast.LENGTH_LONG).show();
            Alternative.getManager().LaunchMinecraft(requireActivity());
        });

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