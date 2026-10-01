package com.answer.launcher.ui.fragment;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.answer.launcher.core.bridge.GameBridgeClient;
import com.answer.launcher.core.manager.Instance;
import com.answer.launcher.core.manager.InstanceImporter;
import com.answer.launcher.core.manager.InstanceStore;
import com.answer.launcher.R;
import com.answer.launcher.databinding.FragmentInstancesBinding;
import com.answer.launcher.databinding.ItemInstanceBinding;
import com.answer.launcher.ui.ModPack;
import com.answer.launcher.ui.PackZip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * 实例管理 (levi-compat-design.md §3): lists imported Minecraft APKs, lets the
 * user import one via SAF, set the active instance the guest boots into, and
 * rename/delete. Backs {@link InstanceStore} (Tier-1) and is read by the
 * launch flow (Tier-3) via the {@code .active-instance} signal.
 *
 * <p>Task #31/#32 add the archive-level actions on top of that list: a modpack
 * ({@link ModPack}) can be imported into a chosen instance, and any instance can be
 * exported back out as one.
 */
public class InstancesFragment extends Fragment {

    private FragmentInstancesBinding binding;
    private InstanceAdapter adapter;
    private final List<Instance> instances = new ArrayList<>();
    private String activeId;
    private ActivityResultLauncher<String[]> importLauncher;
    private ActivityResultLauncher<String[]> modPackLauncher;
    private ActivityResultLauncher<String> packExportLauncher;
    private String pendingExportSlug;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Registered before STARTED, as required by the result API.
        importLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> { if (uri != null) startImport(uri); });
        modPackLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), this::onModPackPicked);
        packExportLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/zip"),
                this::onPackExportPicked);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentInstancesBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        adapter = new InstanceAdapter();
        binding.list.setAdapter(adapter);
        binding.list.setOnItemClickListener((parent, v, position, id) ->
                setActive(instances.get(position)));
        binding.list.setOnItemLongClickListener((parent, v, position, id) -> {
            showOptions(instances.get(position));
            return true;
        });
        // PCL-style left rail: the instance actions live here instead of a toolbar overflow.
        binding.sideImport.setOnClickListener(v ->
                importLauncher.launch(new String[]{"application/vnd.android.package-archive"}));
        binding.sideModpack.setOnClickListener(v -> modPackLauncher.launch(new String[]{"*/*"}));
        binding.sideAddfolder.setOnClickListener(v -> showAddFolderUnsupported());
        binding.sideSystem.setOnClickListener(v -> {
            InstanceStore.clearActive(requireContext());
            toast(getString(R.string.instance_switched_to_system));
            refresh();
        });
        refresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
        pendingExportSlug = null;
    }

    private void refresh() {
        if (binding == null) return;
        Context ctx = requireContext();
        instances.clear();
        instances.addAll(InstanceStore.list(ctx));
        activeId = InstanceStore.getActive(ctx);
        adapter.notifyDataSetChanged();
        boolean empty = instances.isEmpty();
        binding.headerTitle.setText(empty
                ? getString(R.string.title_instance)
                : getString(R.string.title_instance) + " (" + instances.size() + ")");
        binding.list.setVisibility(empty ? View.GONE : View.VISIBLE);
        binding.textEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    private void startImport(Uri uri) {
        Context ctx = requireContext();
        toast(getString(R.string.instance_importing));
        new Thread(() -> {
            InstanceImporter.Result r = InstanceImporter.import_(ctx, uri, null);
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                if (binding == null) return;
                if (r.success()) {
                    toast(getString(R.string.instance_import_success, r.instance.versionName)
                            + (r.aligned16kb ? "" : getString(R.string.instance_import_align_warning)));
                } else {
                    new MaterialAlertDialogBuilder(ctx)
                            .setTitle(R.string.instance_import_failed_title)
                            .setMessage(r.error == null ? getString(R.string.instance_unknown_error) : r.error)
                            .setPositiveButton(R.string.common_got_it, null)
                            .show();
                }
                refresh();
            });
        }).start();
    }

    private void setActive(Instance in) {
        if (in.isContentOnly()) {
            // A content-only instance has no APK to boot; it is managed from 内容, not launched.
            toast(getString(R.string.instance_content_not_launchable));
            return;
        }
        InstanceStore.setActive(requireContext(), in.id);
        toast(getString(R.string.instance_set_active, in.label));
        refresh();
    }

    private void showOptions(Instance in) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(in.label)
                .setItems(new String[]{getString(R.string.instance_option_set_active),
                        getString(R.string.instance_option_rename),
                        getString(R.string.modpack_export_button),
                        getString(R.string.instance_option_delete)}, (d, which) -> {
                    if (which == 0) setActive(in);
                    else if (which == 1) showRename(in);
                    else if (which == 2) startPackExport(in);
                    else if (which == 3) showDelete(in);
                })
                .show();
    }

    // ---------------------------------------------------------------- modpacks

    /**
     * 导入整合包 (task #32): the archive is picked first, then the player says which
     * installation it belongs to — an import writes into the target's worlds and packs,
     * so it is never guessed from whatever happens to be active.
     */
    private void onModPackPicked(@Nullable Uri uri) {
        if (uri == null || binding == null) return;
        Context ctx = requireContext();
        if (!ModPack.isModPackFile(PackZip.displayName(ctx, uri))) {
            toast(getString(R.string.modpack_import_type_error));
            return;
        }
        if (instances.isEmpty()) {
            toast(getString(R.string.modpack_import_no_target));
            return;
        }
        String[] items = new String[instances.size()];
        for (int i = 0; i < items.length; i++) {
            Instance in = instances.get(i);
            items[i] = displayName(in)
                    + (in.id.equals(activeId) ? " · " + getString(R.string.instance_active_chip) : "");
        }
        new MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.modpack_import_title)
                .setMessage(R.string.modpack_import_message)
                .setItems(items, (d, which) -> importModPack(uri, instances.get(which)))
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }

    /** The label the list already shows, so the picker and the list say the same thing. */
    private String displayName(Instance in) {
        String label = in.label == null || in.label.trim().isEmpty() ? in.id : in.label.trim();
        String version = in.versionName;
        return version == null || version.isEmpty() || version.equals(label)
                ? label : label + " · " + version;
    }

    /**
     * Installs the archive into one instance. No "新建实例目录" entry is offered here on
     * purpose: an instance is registered from an imported APK (see {@code InstanceImporter}),
     * and {@code Alternative.initPackage} falls an instance without {@code base.apk} back to
     * the <em>system</em> package — so a folder created by hand would be booted by a different
     * binary than the player picked, with a data root nothing else uses. Adding content-only
     * instances needs that distinction in core first.
     */
    private void importModPack(Uri uri, Instance target) {
        final Context ctx = requireContext().getApplicationContext();
        toast(getString(R.string.common_working));
        new Thread(() -> {
            final ModPack.Result r = ModPack.importPack(ctx, uri, target.id);
            post(() -> {
                if (binding == null) return;
                if (r.success()) {
                    int content = r.count(ModPack.KIND_DATA) + r.count(ModPack.KIND_WORLDS)
                            + r.count(ModPack.KIND_RESOURCE) + r.count(ModPack.KIND_BEHAVIOR);
                    toast(getString(R.string.modpack_import_success,
                            content, r.count(ModPack.KIND_PLUGINS), r.count(ModPack.KIND_MODS)));
                    if (r.count(ModPack.KIND_PLUGINS) > 0) {
                        // Plugins are read by :game from the instance's own dir, so a modpack that
                        // carried any has to tell it to look again, like the plugin screen does.
                        GameBridgeClient.get().reloadPlugins();
                        toast(getString(R.string.plugin_change_effect));
                    }
                } else {
                    toast(getString(R.string.modpack_import_failed, describe(r)));
                }
                refresh();
            });
        }, "modpack-import").start();
    }

    /** 导出整合包: the instance's worlds, packs and plugins as one {@code .altpack} archive. */
    private void startPackExport(Instance in) {
        pendingExportSlug = in.id;
        packExportLauncher.launch(ModPack.suggestedFileName(requireContext(), in.id));
    }

    private void onPackExportPicked(@Nullable Uri target) {
        String slug = pendingExportSlug;
        pendingExportSlug = null;
        if (target == null || slug == null || binding == null) return;
        final Context ctx = requireContext().getApplicationContext();
        toast(getString(R.string.common_working));
        new Thread(() -> {
            final ModPack.Result r = ModPack.exportPack(ctx, slug, target);
            final String name = PackZip.displayName(ctx, target);
            post(() -> {
                if (binding == null) return;
                if (r.success()) {
                    toast(getString(R.string.modpack_export_success,
                            name == null ? ModPack.suggestedFileName(ctx, slug) : name, r.total()));
                } else {
                    toast(getString(R.string.modpack_export_failed, describe(r)));
                }
            });
        }, "modpack-export").start();
    }

    /** The archive's own reason when it has one; the generic failure otherwise. */
    private String describe(ModPack.Result r) {
        return r.error == null ? getString(R.string.instance_unknown_error) : r.error;
    }

    /**
     * 添加已有文件夹 (task #32) is deliberately not implemented, and says so instead of
     * opening a picker that can only fail.
     *
     * <p>An instance here is an <em>installation</em>: {@code InstanceImporter} registers the
     * APK, extracts its libraries and writes {@code instance.json}, and the launch flow boots
     * exactly that tree. A picked folder carries none of that — only game data — so the two
     * honest options were both bad. Registering it as an instance would leave it without
     * {@code base.apk}, which {@code Alternative.initPackage} resolves by booting the system
     * package under a slug derived from the system version instead ({@code activeInstance}
     * stays null): the folder would look imported but never be read, and the game would come up
     * with a different version's data. Copying its {@code games/com.mojang} into an existing
     * instance is worse: it replaces worlds and packs of another version's shape with no
     * progress and no undo.
     *
     * <p>Both of those need a content-only instance kind in core. Until then the folder's
     * content can be brought in safely as a modpack under 导入整合包, which is what the dialog
     * points at.
     */
    private void showAddFolderUnsupported() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.modpack_add_folder_title)
                .setMessage(R.string.modpack_add_folder_message)
                .setPositiveButton(R.string.common_got_it, null)
                .show();
    }

    private void showRename(Instance in) {
        Context ctx = requireContext();
        TextInputLayout field = new TextInputLayout(ctx);
        field.setPadding(dp(24), dp(8), dp(24), 0);
        TextInputEditText et = new TextInputEditText(ctx);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setText(in.label);
        et.setSelection(in.label.length());
        field.addView(et);
        new MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.instance_rename_title)
                .setView(field)
                .setPositiveButton(R.string.common_confirm, (d, w) -> {
                    String nl = et.getText() == null ? "" : et.getText().toString().trim();
                    if (nl.isEmpty()) { toast(getString(R.string.instance_rename_empty_error)); return; }
                    InstanceStore.rename(requireContext(), in.id, nl);
                    refresh();
                })
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }

    private void showDelete(Instance in) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.instance_delete_title)
                .setMessage(getString(R.string.instance_delete_confirm_message, in.label))
                .setPositiveButton(R.string.common_delete, (d, w) -> {
                    InstanceStore.delete(requireContext(), in.id);
                    toast(getString(R.string.instance_deleted));
                    refresh();
                })
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** Hops back from a worker thread; the callers all re-check {@code binding}. */
    private void post(Runnable ui) {
        FragmentActivity act = getActivity();
        if (act != null) act.runOnUiThread(ui);
    }

    private void toast(String s) {
        Context ctx = getContext();
        if (ctx != null) Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show();
    }

    private class InstanceAdapter extends BaseAdapter {
        private final LayoutInflater inflater = LayoutInflater.from(requireContext());

        @Override
        public int getCount() { return instances.size(); }

        @Override
        public Object getItem(int position) { return instances.get(position); }

        @Override
        public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ItemInstanceBinding b;
            if (convertView == null) {
                b = ItemInstanceBinding.inflate(inflater, parent, false);
                convertView = b.getRoot();
                convertView.setTag(b);
            } else {
                b = (ItemInstanceBinding) convertView.getTag();
            }
            Instance in = instances.get(position);
            b.itemIcon.setImageResource(in.isContentOnly()
                    ? R.drawable.ic_box_content : R.drawable.mincraft);
            b.tvLabel.setText(in.label);
            String sub = getString(R.string.instance_subtitle_format, in.versionName,
                    in.abi == null || in.abi.isEmpty() ? getString(R.string.instance_no_native_libs) : in.abi,
                    in.versionCode);
            b.tvSubtitle.setText(sub);
            b.tvId.setText(in.id);
            boolean active = in.id.equals(activeId);
            b.chipActive.setVisibility(active ? View.VISIBLE : View.GONE);
            boolean aligned = in.aligned16kb;
            b.tvAlign.setVisibility(View.VISIBLE);
            b.tvAlign.setText(aligned ? "16KB" : getString(R.string.instance_not_16kb));
            b.tvAlign.setTextColor(aligned ? 0xFF2E7D32 : 0xFFC62828);
            final Instance current = in;
            b.btnMore.setOnClickListener(v -> showOptions(current));
            return convertView;
        }
    }
}
