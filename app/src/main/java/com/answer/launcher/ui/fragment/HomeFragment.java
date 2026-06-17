package com.answer.launcher.ui.fragment;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.answer.launcher.core.MinecraftLoader;
import com.answer.launcher.core.manager.Alternative;
import com.answer.launcher.core.manager.MultiVersionManager;
import com.answer.launcher.databinding.FragmentHomeBinding;
import com.answer.launcher.ui.activity.CrashActivity;
import com.hjq.permissions.OnPermissionCallback;
import com.hjq.permissions.Permission;
import com.hjq.permissions.XXPermissions;


import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;



public class HomeFragment extends Fragment {
    private FragmentHomeBinding binding;

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

               MinecraftLoader.load(requireActivity().getApplication().getBaseContext());
                Alternative.getManager().LaunchMinecraft(requireActivity());
            }).start();
        });

        binding.versionSetting.setOnClickListener(view -> {
            Intent intent = new Intent(getActivity(), CrashActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra("CRASH_LOG", "错误🤣测试🤣🤣🤣错误🤣测试🤣🤣🤣错误🤣测试🤣🤣🤣错误🤣测试🤣🤣🤣错误🤣测试🤣🤣🤣错误🤣测试🤣🤣🤣错误🤣测试🤣🤣🤣错误🤣测试🤣🤣🤣错误🤣测试🤣🤣🤣错误🤣测试🤣🤣🤣错误🤣测试🤣🤣🤣错误🤣测试🤣🤣🤣错误🤣测试🤣🤣🤣错误🤣测试🤣🤣🤣错误🤣测试🤣🤣🤣错误🤣测试🤣🤣🤣错误🤣测试🤣🤣🤣错误🤣测试🤣🤣🤣错误🤣测试🤣🤣🤣错误🤣测试🤣🤣🤣错误🤣测试🤣🤣🤣错误🤣测试🤣🤣🤣错误🤣测试🤣🤣🤣错误🤣测试🤣🤣🤣");
            getActivity().startActivity(intent);
        });

        binding.selectVersion.setOnClickListener(v->{
            try {
                Context c =  MultiVersionManager.getManager()
                        .createPackageContext(requireActivity().getApplication().getBaseContext(),
                        Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY,
                                "/sdcard/base.apk");
                System.out.println(c);
                System.out.println(c.getPackageName());
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