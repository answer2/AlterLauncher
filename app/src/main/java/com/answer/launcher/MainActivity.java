package com.answer.launcher;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.fragment.app.Fragment;

import com.answer.launcher.core.Constants;
import com.answer.launcher.core.MinecraftLoader;
import com.answer.launcher.core.manager.Alternative;
import com.answer.launcher.core.manager.NativePluginManager;
import com.answer.launcher.core.manager.PluginManager;
import com.answer.launcher.databinding.ActivityMainBinding;
import com.answer.launcher.ui.fragment.DownloadFragment;
import com.answer.launcher.ui.fragment.HomeFragment;
import com.answer.launcher.ui.fragment.HomePagerAdapter;
import com.answer.launcher.ui.fragment.SettingFragment;
import com.answer.launcher.utils.ThemeUtils;
import com.google.android.material.tabs.TabLayoutMediator;
import com.hjq.permissions.OnPermissionCallback;
import com.hjq.permissions.Permission;
import com.hjq.permissions.XXPermissions;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
  private ActivityMainBinding binding;

  @SuppressLint("SuspiciousIndentation")
  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
      // set statusBar color
      boolean isDarkMode = ThemeUtils.isSystemInDarkTheme(this);
      WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
      ThemeUtils.statusBarColor(this, Color.TRANSPARENT, isDarkMode);
      ThemeUtils.hideSystemUI(this);

    // Inflate and get instance of binding
    binding = ActivityMainBinding.inflate(getLayoutInflater());

    // set content view to binding's root
    setContentView(binding.getRoot());

    initView();



/**
    binding.load.setOnClickListener(
        v -> {
          Alternative.getManager().LaunchMinecraft(this);
        });

    binding.loadNative.setOnClickListener(
        v ->
            NativePluginManager.getManager(this)
                .addNativePlugin(binding.edit.getText().toString()));
    binding.loadDex.setOnClickListener(
        v -> PluginManager.getManager(this).addPlugin(binding.edit.getText().toString() + ""));

    binding.set.setOnClickListener(
        v -> {
          if (!TextUtils.isEmpty(binding.edit.getText()))
            Constants.MINECRAFTPACKAGE = binding.edit.getText().toString();
          
            MinecraftLoader.load(getApplication().getBaseContext());
                  
        });
      binding.please.setOnClickListener(
              v -> {
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
                                              XXPermissions.startPermissionActivity(MainActivity.this, permissions);
                                          } else {
                                              Log.d("Main", "获取录音和日历权限失败");
                                          }
                                      }
                                  });
              });**/
  }

  private void initView(){
      List<Fragment> fragments = new ArrayList<>();
      fragments.add(new HomeFragment());
      fragments.add(new DownloadFragment());
      fragments.add(new SettingFragment());

      HomePagerAdapter adapter = new HomePagerAdapter(this, fragments);
      binding.viewPager.setAdapter(adapter);

      new TabLayoutMediator(binding.tabLayout, binding.viewPager, (tab, position) -> {
          LinearLayout linearLayout = new LinearLayout(this);
          linearLayout.setGravity(Gravity.CENTER);
          linearLayout.setOrientation(LinearLayout.HORIZONTAL);

          ImageView imageView = new ImageView(this);
          LinearLayout.LayoutParams layoutParams =new LinearLayout.LayoutParams(-2, -2);
          layoutParams.setMargins(10, 10, 10, 10);
          imageView.setLayoutParams(layoutParams);

          TextView tabView = new TextView(this);

          switch (position){
              case 0:
                  imageView.setImageResource(R.drawable.ic_action_launch);
                  tabView.setText(R.string.title_launch);
                  break;
              case 1:
                  imageView.setImageResource(R.drawable.ic_action_download);
                  tabView.setText(R.string.title_download);
                  break;
              case 2:
                  imageView.setImageResource(R.drawable.ic_action_setting);
                  tabView.setText(R.string.title_setting);
                  break;
          }

          linearLayout.addView(imageView);
          linearLayout.addView(tabView);

          tab.setCustomView(linearLayout);
      }).attach();

      //new TabLayoutMediator(binding.tabLayout, binding.viewPager, (tab, position) -> tab.setText("Tab " + (position + 1))).attach();
      //binding.tabLayout.setSelectedTabIndicator(R.drawable.cat_tabs_pill_indicator);
  }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        this.binding = null;
    }
}
