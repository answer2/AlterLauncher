package com.answer.launcher;

import android.app.ActivityThread;
import android.content.Intent;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import com.answer.launcher.core.manager.Alternative;
import com.answer.launcher.core.manager.MinecraftManager;
import com.answer.launcher.core.manager.NativePluginManager;
import com.answer.launcher.core.manager.PluginManager;
import com.answer.launcher.databinding.ActivityMainBinding;
import com.answer.launcher.core.env.AlterInstrumentation;
import com.hjq.permissions.OnPermissionCallback;
import com.hjq.permissions.Permission;
import com.hjq.permissions.XXPermissions;
import java.util.List;

public class MainActivity extends AppCompatActivity {
  private ActivityMainBinding binding;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    // Inflate and get instance of binding
    binding = ActivityMainBinding.inflate(getLayoutInflater());

    // set content view to binding's root
    setContentView(binding.getRoot());

    binding.load.setOnClickListener(
        v -> {
          Alternative.LaunchMinecraft(this);
        });
        
        binding.loadNative.setOnClickListener(v->NativePluginManager.getManager(this).addNativePlugin(binding.edit.getText().toString()));
        binding.loadDex.setOnClickListener(v->PluginManager.getManager(this).addPlugin(binding.edit.getText().toString() + ""));

    binding.please.setOnClickListener(
        v -> {
          XXPermissions.with(this)
              // 申请多个权限
              .permission(Permission.Group.STORAGE)
              // 设置不触发错误检测机制（局部设置）
              .unchecked()
                .request(new OnPermissionCallback() {
            @Override
            public void onGranted(@NonNull List<String> permissions, boolean allGranted) {
                if (!allGranted) {
                    Log.d("Main","获取部分权限成功，但部分权限未正常授予");
                    return;
                }
                Log.d("Main","获取存储成功");
            }

            @Override
            public void onDenied(@NonNull List<String> permissions, boolean doNotAskAgain) {
                if (doNotAskAgain) {
                    Log.d("Main","被永久拒绝授权，请手动授予存储权限");
                    // 如果是被永久拒绝就跳转到应用权限系统设置页面
                    XXPermissions.startPermissionActivity(MainActivity.this, permissions);
                } else {
                    Log.d("Main","获取录音和日历权限失败");
                }
            }
        });
        });
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    this.binding = null;
  }
}
