package com.answer.launcher.ui.fragment;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
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
import com.answer.launcher.core.manager.Instance;
import com.answer.launcher.core.manager.InstanceStore;
import com.answer.launcher.core.tool.AbiUtils;
import com.answer.launcher.R;
import com.answer.launcher.databinding.FragmentHomeBinding;
import com.answer.launcher.ui.AvatarLoader;
import com.answer.launcher.ui.InstanceSpinnerAdapter;
import com.answer.launcher.ui.SignInSheet;
import com.answer.launcher.ui.LauncherPrefs;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.hjq.permissions.OnPermissionCallback;
import com.hjq.permissions.Permission;
import com.hjq.permissions.XXPermissions;


import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;



public class HomeFragment extends Fragment {
    private FragmentHomeBinding binding;

    /** The launch-page version dropdown. Its rows are rebuilt on every {@link #refresh()}. */
    private InstanceSpinnerAdapter versionAdapter;
    /** True while {@link #refreshVersionSpinner} reprograms the selection, so the listener stays quiet. */
    private boolean spinnerSuppress = false;
    /** The instance id the dropdown currently reflects; used to ignore no-op re-selections. */
    private String appliedActiveId = null;

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
        binding.userStatus.setText(R.string.account_checking_status);
        checkLogin(host);

        String version = activeVersionOrSystem(host);
        binding.versionLabel.setText(version == null ? "VERSION" : version);
        binding.archLabel.setText(archLabel(host));

        String state = GameBridgeClient.get().runtimeState();
        int pid = GameBridgeClient.get().gamePid();
        String text;
        if (state.startsWith(GameRuntime.FAILED_PREFIX)) {
            text = getString(R.string.home_runtime_failed, state.substring(GameRuntime.FAILED_PREFIX.length()));
        } else if (GameRuntime.READY.equals(state)) {
            text = getString(R.string.home_runtime_running, pid);
        } else if (GameRuntime.ASSEMBLING.equals(state)) {
            text = getString(R.string.home_runtime_assembling);
        } else {
            text = getString(R.string.home_runtime_not_started);
        }
        binding.runtimeStatus.setText(text);
        refreshVersionSpinner(host, state);
        binding.loadGame.setText(GameRuntime.READY.equals(state) ? getString(R.string.home_back_to_game) : "启动");
    }

    /** The label the instance list uses, so the picker and the list say the same thing. */
    private String instanceDisplayName(Instance in) {
        String label = in.label == null || in.label.trim().isEmpty() ? in.id : in.label.trim();
        String version = in.versionName;
        return version == null || version.isEmpty() || version.equals(label)
                ? label : label + " · " + version;
    }

    /**
     * The version the 启动 button will boot, as a dropdown: the system install plus every
     * launchable instance. Content-only instances are left out (they have no APK to boot), and a
     * running game disables the control — {@code :game} resolved its data root at launch, so
     * switching now would not move the process that is already up.
     */
    private void refreshVersionSpinner(Context host, String state) {
        if (versionAdapter == null) {
            return;
        }
        spinnerSuppress = true;
        List<InstanceSpinnerAdapter.Item> items = new ArrayList<>();
        String sysVersion = guestVersion(host);
        items.add(new InstanceSpinnerAdapter.Item(null,
                sysVersion == null || sysVersion.isEmpty()
                        ? getString(R.string.home_version_system)
                        : getString(R.string.home_version_system) + " · " + sysVersion,
                R.drawable.mincraft));
        for (Instance in : InstanceStore.list(host)) {
            if (in.isLaunchable()) {
                items.add(new InstanceSpinnerAdapter.Item(in.id, instanceDisplayName(in),
                        R.drawable.mincraft));
            }
        }
        versionAdapter.setItems(items);
        String activeId = InstanceStore.getActive(host);
        boolean hasActive = activeId != null && !activeId.isEmpty();
        binding.versionSpinner.setSelection(versionAdapter.indexOfId(hasActive ? activeId : null));
        appliedActiveId = hasActive ? activeId : null;
        binding.versionSpinner.setEnabled(
                !GameRuntime.READY.equals(state) && !GameRuntime.ASSEMBLING.equals(state));
        spinnerSuppress = false;
    }

    private static boolean sameId(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /** The arch chip: the bitness of the instance that will actually boot, not the device's. */
    private String archLabel(Context host) {
        Instance active = InstanceStore.getActiveInstance(host);
        String abi = active != null && active.abi != null && !active.abi.isEmpty()
                ? active.abi
                : (Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "");
        if (abi.isEmpty()) {
            return "ARCH";
        }
        return getString(AbiUtils.is64BitAbi(abi) ? R.string.home_arch_64 : R.string.home_arch_32);
    }

    /** The version the card shows: the active instance's, so it agrees with the dropdown. */
    private String activeVersionOrSystem(Context host) {
        Instance active = InstanceStore.getActiveInstance(host);
        if (active != null && active.versionName != null && !active.versionName.isEmpty()) {
            return active.versionName;
        }
        return guestVersion(host);
    }

    /**
     * A readable reason the selected instance cannot boot in this process, or null when it can.
     *
     * <p>An Android app runs every one of its processes at a single bitness, chosen at install
     * from the ABIs it ships — so the {@code :game} process that hosts the guest is 64-bit here
     * because the launcher carries {@code arm64-v8a}. A game whose extracted libraries are only
     * 32-bit cannot be {@code dlopen}ed into that process (and the reverse). Rather than let the
     * launch die in a native SIGSEGV, the button refuses up front and says what to do about it.
     */
    private String abiMismatchMessage() {
        Context host = requireContext();
        Instance active = InstanceStore.getActiveInstance(host);
        if (active == null || active.abi == null || active.abi.isEmpty()) {
            return null;
        }
        boolean game64 = AbiUtils.is64BitAbi(active.abi);
        boolean proc64 = AbiUtils.is64BitCheck();
        if (game64 == proc64) {
            return null;
        }
        String gameArch = getString(game64 ? R.string.home_arch_64 : R.string.home_arch_32);
        String procArch = getString(proc64 ? R.string.home_arch_64 : R.string.home_arch_32);
        String label = active.label == null || active.label.trim().isEmpty()
                ? active.id : active.label.trim();
        return getString(R.string.home_arch_mismatch, label, gameArch, procArch);
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
     *
     * <p>The avatar comes along with the same answer: Xbox only gives up the picture to a live
     * session, so an expired or absent sign-in draws the placeholder instead.
     */
    private void applyAccountCard(IdentityStore.Account account, SessionValidator.Status validity) {
        boolean expired = account.signedIn && validity.hasAccount && !validity.valid;
        boolean signedIn = account.signedIn && !expired;
        accountStateSignedIn = signedIn;
        accountStateExpired = expired;
        if (signedIn) {
            binding.userId.setText(validity.gamertag != null ? validity.gamertag
                    : (account.xuid != null ? "XUID " + account.xuid : getString(R.string.account_signed_in_fallback)));
            binding.userStatus.setText(account.shared
                    ? getString(R.string.account_shared_status)
                    : getString(R.string.account_isolated_status));
        } else if (expired) {
            binding.userId.setText(R.string.account_expired_label);
            binding.userStatus.setText(R.string.account_expired_status);
        } else {
            binding.userId.setText(R.string.account_not_signed_in);
            binding.userStatus.setText(R.string.account_not_signed_in_hint);
        }
        /*
         * The slot the app has shipped with an icon in since the first commit now holds the real
         * face: a cached DisplayPicRaw URL, drawn as a circle, with a letter avatar underneath it
         * until the picture arrives. Passing a null URL (signed out, or a check that never got an
         * answer) also drops any download still in flight so it cannot land on the wrong account.
         */
        AvatarLoader.load(binding.userImage,
                account.signedIn ? validity.avatarUrl : null,
                account.signedIn ? validity.gamertag : null);
    }

    private boolean accountStateSignedIn;
    private boolean accountStateExpired;

    /**
     * The avatar is the single entry point for every account action. Options adapt to the
     * current state: signed-in offers re-login + sign-out, expired offers re-login only,
     * signed-out offers sign-in.
     */
    private void showAccountDialog() {
        if (accountStateSignedIn) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.account_section_header)
                    .setItems(new CharSequence[]{
                            getString(R.string.account_relogin_button),
                            getString(R.string.account_signout_button)
                    }, (d, which) -> {
                        if (which == 0) startSignIn();
                        else confirmSignOut();
                    })
                    .show();
        } else if (accountStateExpired) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.account_expired_label)
                    .setMessage(R.string.account_expired_status)
                    .setNegativeButton(R.string.common_cancel, null)
                    .setPositiveButton(R.string.account_relogin_button, (d, w) -> startSignIn())
                    .show();
        } else {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.account_not_signed_in)
                    .setMessage(R.string.account_not_signed_in_hint)
                    .setNegativeButton(R.string.common_cancel, null)
                    .setPositiveButton(R.string.account_login_button, (d, w) -> startSignIn())
                    .show();
        }
    }

    private void startSignIn() {
        SignInSheet.show(requireActivity(),
                (success, message) -> {
                    Toast.makeText(requireContext(),
                            success ? getString(R.string.sign_in_complete_toast) : getString(R.string.sign_in_failed_toast, message),
                            Toast.LENGTH_LONG).show();
                    refresh();
                });
    }

    /**
     * Sign-out is destructive (the saved tokens are gone until a fresh browser login), so it is
     * gated on the game not running and confirmed first; the file wipe itself runs off the main
     * thread.
     */
    private void confirmSignOut() {
        String state = GameBridgeClient.get().runtimeState();
        if (GameRuntime.READY.equals(state) || GameRuntime.ASSEMBLING.equals(state)) {
            Toast.makeText(requireContext(), R.string.account_game_running_block, Toast.LENGTH_SHORT).show();
            return;
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.account_signout_title)
                .setMessage(R.string.account_signout_confirm_message)
                .setNegativeButton(R.string.common_cancel, null)
                .setPositiveButton(R.string.account_signout_confirm_button, (d, w) -> {
                    Context host = requireContext().getApplicationContext();
                    new Thread(() -> {
                        boolean ok = IdentityStore.signOut(host);
                        requireActivity().runOnUiThread(() -> {
                            Toast.makeText(requireContext(),
                                    ok ? R.string.account_signout_success : R.string.account_signout_partial,
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
            String mismatch = abiMismatchMessage();
            if (mismatch != null) {
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.home_arch_mismatch_title)
                        .setMessage(mismatch)
                        .setPositiveButton(R.string.common_got_it, null)
                        .show();
                return;
            }
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
         * run the device-code flow itself and hand XAL the store it expects to find. The avatar
         * is the single entry point for every account action (sign-in, re-login, sign-out).
         */
        binding.userImage.setOnClickListener(view -> showAccountDialog());

        versionAdapter = new InstanceSpinnerAdapter(requireContext());
        binding.versionSpinner.setAdapter(versionAdapter);
        binding.versionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (spinnerSuppress) {
                    return;
                }
                InstanceSpinnerAdapter.Item item = versionAdapter.getItemAt(position);
                if (item == null || sameId(item.id, appliedActiveId)) {
                    return;
                }
                String state = GameBridgeClient.get().runtimeState();
                if (GameRuntime.READY.equals(state) || GameRuntime.ASSEMBLING.equals(state)) {
                    Toast.makeText(requireContext(), R.string.account_game_running_block,
                            Toast.LENGTH_SHORT).show();
                    refresh();
                    return;
                }
                Context host = requireContext();
                if (item.id == null) {
                    InstanceStore.clearActive(host);
                    Toast.makeText(host, R.string.instance_switched_to_system, Toast.LENGTH_SHORT).show();
                } else {
                    InstanceStore.setActive(host, item.id);
                    Toast.makeText(host, getString(R.string.instance_set_active, item.label),
                            Toast.LENGTH_SHORT).show();
                }
                appliedActiveId = item.id;
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

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