package com.answer.launcher.ui.fragment;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.system.ErrnoException;
import android.system.Os;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.answer.launcher.R;
import com.answer.launcher.core.manager.GameDataStore;
import com.answer.launcher.core.manager.IdentityStore;
import com.answer.launcher.databinding.FragmentPacksBinding;
import com.answer.launcher.ui.PackAdapter;
import com.answer.launcher.ui.PackZip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/**
 * 资源包 / 行为包 (task #19): one screen class for both pack roots, picked by
 * {@link #newInstance(int)}, and both routed to the instance the content header chose
 * (task #33) through the {@code "slug"} argument — with no slug the active instance is used.
 *
 * <p>Besides import/export/delete it carries the enable switch, which publishes a pack into
 * {@code games/com.mojang/packs} so the game applies it to every world.
 *
 * <p>{@code packs/<id>} is created as a symlink back into the packs root: it is what
 * the game (and task #20's global-resource mechanism) expects to find there, and it
 * keeps the enabled state in sync with the pack instead of duplicating files. A copy
 * is used as a fallback where symlinks are refused.
 */
public class PacksFragment extends Fragment implements PackAdapter.Listener {

    public static final int KIND_RESOURCE = 0;
    public static final int KIND_BEHAVIOR = 1;

    private static final String ARG_KIND = "kind";
    private static final String ARG_SLUG = "slug";

    private FragmentPacksBinding binding;
    private PackAdapter adapter;
    private ActivityResultLauncher<String[]> importLauncher;
    private ActivityResultLauncher<String> exportLauncher;
    private PackAdapter.Pack pendingExport;
    private volatile boolean listing;
    private final DateFormat dateFormat =
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);

    private int kind = KIND_RESOURCE;
    /** The instance being managed; null or empty means the active one. */
    private String slug;

    public static PacksFragment newInstance(int kind) {
        return newInstance(kind, null);
    }

    public static PacksFragment newInstance(int kind, String slug) {
        PacksFragment f = new PacksFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_KIND, kind);
        if (slug != null && !slug.isEmpty()) args.putString(ARG_SLUG, slug);
        f.setArguments(args);
        return f;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle args = getArguments();
        kind = args == null ? KIND_RESOURCE : args.getInt(ARG_KIND, KIND_RESOURCE);
        slug = args == null ? null : args.getString(ARG_SLUG);
        importLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), this::onImportPicked);
        exportLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/zip"), this::onExportPicked);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        binding = FragmentPacksBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        adapter = new PackAdapter(this);
        binding.packList.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.packList.setAdapter(adapter);
        binding.fabImport.setOnClickListener(v -> importLauncher.launch(new String[]{"*/*"}));
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
        pendingExport = null;
        // The worker may have been mid-flight when the view went away; never let its
        // in-flight guard outlive the view it was guarding.
        listing = false;
    }

    // ----------------------------------------------------------------- paths

    private File packsRoot(Context ctx) {
        return kind == KIND_BEHAVIOR ? behaviorRoot(ctx) : resourceRoot(ctx);
    }

    /** The managed instance's {@code resource_packs} folder (task #33). */
    private File resourceRoot(Context ctx) {
        if (slug == null || slug.isEmpty()) return GameDataStore.resourcePacks(ctx);
        File root = GameDataStore.resourcePacksFor(ctx, slug);
        return root != null ? root : GameDataStore.ensure(instancePath(ctx, "resource_packs"));
    }

    /** The managed instance's {@code behavior_packs} folder. */
    private File behaviorRoot(Context ctx) {
        if (slug == null || slug.isEmpty()) return GameDataStore.behaviorPacks(ctx);
        File root = GameDataStore.behaviorPacksFor(ctx, slug);
        return root != null ? root : GameDataStore.ensure(instancePath(ctx, "behavior_packs"));
    }

    /** The managed instance's in-game "globally enabled" {@code packs} folder. */
    private File globalRoot(Context ctx) {
        if (slug == null || slug.isEmpty()) return GameDataStore.globalPacks(ctx);
        File root = GameDataStore.globalPacksFor(ctx, slug);
        return root != null ? root : GameDataStore.ensure(instancePath(ctx, "packs"));
    }

    /**
     * {@code <dataDir>/mc/<slug>/games/com.mojang/<name>}. The {@code *For} helpers answer
     * {@code null} for an instance that has never been booted, because the game is what
     * creates that tree on its first launch — managing one still needs the path, so it is
     * built here from the same base the helpers use.
     */
    private File instancePath(Context ctx, String name) {
        return new File(IdentityStore.instanceRoot(ctx, slug), "games/com.mojang/" + name);
    }

    // ------------------------------------------------------------- list state

    private void refresh() {
        if (binding == null || listing) return;
        final Context ctx = requireContext();
        final File root = packsRoot(ctx);
        final File global = globalRoot(ctx);
        listing = true;
        new Thread(() -> {
            final List<PackAdapter.Pack> found = PackAdapter.Pack.list(root, global);
            FragmentActivity act = getActivity();
            if (act == null) return;
            act.runOnUiThread(() -> {
                listing = false;
                if (binding == null) return;
                adapter.submit(found);
                boolean empty = found.isEmpty();
                binding.packList.setVisibility(empty ? View.GONE : View.VISIBLE);
                binding.textEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
            });
        }, "pack-list").start();
    }

    // ----------------------------------------------------------------- actions

    @Override
    public void onPackClick(PackAdapter.Pack pack) {
        showOptions(pack);
    }

    @Override
    public void onPackMore(PackAdapter.Pack pack, View anchor) {
        showOptions(pack);
    }

    private void showOptions(PackAdapter.Pack pack) {
        if (getContext() == null) return;
        String[] options = new String[]{
                getString(R.string.pack_preview),
                getString(R.string.pack_export),
                getString(R.string.common_delete)};
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(pack.name)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) showPreview(pack);
                    else if (which == 1) startExport(pack);
                    else showDelete(pack);
                })
                .show();
    }

    /** The pack's own icon is part of its identity, so the dialog shows it. */
    private void showPreview(PackAdapter.Pack pack) {
        final Context ctx = requireContext();
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.content_label_name, pack.name)).append('\n');
        if (!pack.description.isEmpty()) {
            sb.append(getString(R.string.content_label_description, pack.description)).append('\n');
        }
        sb.append(getString(R.string.content_label_version,
                pack.version.isEmpty() ? getString(R.string.content_unknown) : pack.version)).append('\n');
        sb.append(getString(R.string.content_label_modules, pack.modules)).append('\n');
        sb.append(getString(R.string.content_label_uuid, pack.uuid)).append('\n');
        sb.append(getString(R.string.content_label_folder, pack.id)).append('\n');
        sb.append(getString(R.string.content_label_modified,
                dateFormat.format(new Date(pack.modified)))).append('\n');
        sb.append(getString(R.string.content_label_size,
                Formatter.formatShortFileSize(ctx, pack.size)));

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int pad = dp(ctx, 24);
        row.setPadding(pad, dp(ctx, 12), pad, 0);

        ImageView icon = new ImageView(ctx);
        int slot = dp(ctx, 64);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(slot, slot);
        iconLp.setMarginEnd(dp(ctx, 16));
        icon.setLayoutParams(iconLp);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        Bitmap bitmap = pack.icon;
        if (bitmap != null) icon.setImageBitmap(bitmap);
        else icon.setImageResource(R.drawable.ic_empty_pack);
        row.addView(icon);

        TextView text = new TextView(ctx);
        text.setText(sb);
        text.setTextAppearance(android.R.style.TextAppearance_Medium);
        LinearLayout.LayoutParams textLp =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        text.setLayoutParams(textLp);
        row.addView(text);

        ScrollView scroll = new ScrollView(ctx);
        scroll.addView(row);

        new MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.pack_preview)
                .setView(scroll)
                .setPositiveButton(R.string.common_got_it, null)
                .show();
    }

    private void startExport(PackAdapter.Pack pack) {
        pendingExport = pack;
        exportLauncher.launch(pack.id + ".mcpack");
    }

    private void onExportPicked(@Nullable Uri target) {
        PackAdapter.Pack pack = pendingExport;
        pendingExport = null;
        if (target == null || pack == null || binding == null) return;
        final Context ctx = requireContext();
        toast(getString(R.string.common_working));
        new Thread(() -> {
            final boolean ok = PackZip.exportToZip(ctx, pack.dir, target);
            post(() -> {
                if (binding == null) return;
                if (ok) {
                    String name = PackZip.displayName(ctx, target);
                    toast(getString(R.string.content_export_success,
                            name == null ? pack.id + ".mcpack" : name));
                } else {
                    toast(getString(R.string.content_export_failed));
                }
            });
        }, "pack-export").start();
    }

    private void onImportPicked(@Nullable Uri uri) {
        if (uri == null || binding == null) return;
        final Context ctx = requireContext();
        String name = PackZip.displayName(ctx, uri);
        String ext = PackZip.extension(name);
        if (name != null && !ext.isEmpty()
                && !ext.equals("mcpack") && !ext.equals("mcaddon") && !ext.equals("zip")) {
            toast(getString(R.string.pack_import_type_error));
            return;
        }
        final File root = packsRoot(ctx);
        if (root == null) {
            toast(getString(R.string.content_no_instance));
            return;
        }
        GameDataStore.ensure(root);
        // An .mcaddon carries both kinds, so let the manifest decide where each
        // half lands instead of forcing it into the tab the picker was opened on.
        final File resource = resourceRoot(ctx);
        final File behavior = behaviorRoot(ctx);
        final boolean splitAddon = "mcaddon".equals(ext) && resource != null && behavior != null;
        toast(getString(R.string.common_working));
        new Thread(() -> {
            final PackZip.Result result = splitAddon
                    ? PackZip.importFromAddon(ctx, uri, resource, behavior)
                    : PackZip.importFromZip(ctx, uri, root);
            post(() -> {
                if (binding == null) return;
                toast(result.success()
                        ? getString(R.string.content_import_success, result.imported.size())
                        : result.error);
                refresh();
            });
        }, "pack-import").start();
    }

    private void showDelete(PackAdapter.Pack pack) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.pack_delete_title)
                .setMessage(getString(R.string.common_delete_confirm_message, pack.name))
                .setPositiveButton(R.string.common_delete, (dialog, which) -> deletePack(pack))
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }

    /** Deleting a pack also revokes its global-packs entry, if it has one. */
    private void deletePack(PackAdapter.Pack pack) {
        final Context ctx = requireContext();
        final File global = globalRoot(ctx);
        new Thread(() -> {
            if (global != null) {
                PackZip.deleteRec(new File(global, pack.id));
                if (!pack.uuid.equals(pack.id)) PackZip.deleteRec(new File(global, pack.uuid));
            }
            final boolean ok = PackZip.deleteRec(pack.dir);
            post(() -> {
                if (binding == null) return;
                toast(ok ? getString(R.string.instance_deleted)
                        : getString(R.string.content_delete_failed, pack.id));
                refresh();
            });
        }, "pack-delete").start();
    }

    // ------------------------------------------------------- global enable flag

    @Override
    public void onPackToggle(PackAdapter.Pack pack, boolean enable) {
        final Context ctx = requireContext();
        final File global = globalRoot(ctx);
        if (global == null) {
            toast(getString(R.string.content_no_instance));
            refresh();
            return;
        }
        GameDataStore.ensure(global);
        new Thread(() -> {
            final boolean ok = enable ? linkInto(global, pack) : unlinkFrom(global, pack);
            post(() -> {
                if (binding == null) return;
                if (!ok) {
                    toast(getString(R.string.pack_toggle_failed, pack.name));
                } else {
                    toast(pack.name + " · " + getString(enable
                            ? R.string.pack_enable : R.string.pack_disable));
                }
                refresh();
            });
        }, "pack-toggle").start();
    }

    private static boolean linkInto(File globalDir, PackAdapter.Pack pack) {
        File link = new File(globalDir, pack.id);
        if (link.exists()) return true;
        try {
            Os.symlink(pack.dir.getAbsolutePath(), link.getAbsolutePath());
            return true;
        } catch (ErrnoException e) {
            // vfat-backed storage refuses symlinks; a copy reads as enabled all the same.
            try {
                PackZip.copyRec(pack.dir, link);
                return link.exists();
            } catch (IOException | SecurityException nested) {
                return false;
            }
        }
    }

    private static boolean unlinkFrom(File globalDir, PackAdapter.Pack pack) {
        File link = new File(globalDir, pack.id);
        if (!link.exists()) return true;
        // deleteRec never follows a symlink, so this unlinks instead of wiping the pack.
        return PackZip.deleteRec(link);
    }

    // ---------------------------------------------------------------- plumbing

    private void post(Runnable ui) {
        FragmentActivity act = getActivity();
        if (act != null) act.runOnUiThread(ui);
    }

    private int dp(Context ctx, int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, ctx.getResources().getDisplayMetrics()));
    }

    private void toast(String s) {
        Context ctx = getContext();
        if (ctx != null) Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show();
    }
}
