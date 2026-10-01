package com.answer.launcher.ui.fragment;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
import com.answer.launcher.databinding.FragmentWorldsBinding;
import com.answer.launcher.ui.PackZip;
import com.answer.launcher.ui.WorldAdapter;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/**
 * 世界 (task #18): lists {@code games/com.mojang/minecraftWorlds} and supports the three
 * things a player actually needs — import a {@code .mcworld}, hand one back out as a
 * {@code .mcworld}, and delete one.
 *
 * <p>Which installation that is comes from the {@code "slug"} argument the content header
 * sets (task #33); with no slug the active instance is used, as before.
 *
 * <p>All directory walking, unzipping and zipping happens on a worker thread; the SAF
 * launchers are registered in {@code onCreate} as the result API requires.
 */
public class WorldsFragment extends Fragment implements WorldAdapter.Listener {

    private static final String ARG_SLUG = "slug";

    private FragmentWorldsBinding binding;
    private WorldAdapter adapter;
    private ActivityResultLauncher<String[]> importLauncher;
    private ActivityResultLauncher<String> exportLauncher;
    private WorldAdapter.World pendingExport;
    private volatile boolean listing;
    private String slug;
    private final DateFormat dateFormat =
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);

    public static WorldsFragment newInstance(String slug) {
        WorldsFragment f = new WorldsFragment();
        if (slug != null && !slug.isEmpty()) {
            Bundle args = new Bundle();
            args.putString(ARG_SLUG, slug);
            f.setArguments(args);
        }
        return f;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle args = getArguments();
        slug = args == null ? null : args.getString(ARG_SLUG);
        importLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), this::onImportPicked);
        exportLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/zip"), this::onExportPicked);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        binding = FragmentWorldsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        adapter = new WorldAdapter(this);
        binding.worldList.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.worldList.setAdapter(adapter);
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

    /**
     * The managed instance's {@code minecraftWorlds} folder. Without a slug this is the active
     * instance, exactly as before; with one it is that instance's own root, which the game only
     * creates on its first launch — so the standard path is built here when it is still missing
     * rather than reported as "no instance".
     */
    private File worldsRoot(Context ctx) {
        if (slug == null || slug.isEmpty()) return GameDataStore.worlds(ctx);
        File root = GameDataStore.worldsFor(ctx, slug);
        return root != null ? root : GameDataStore.ensure(instancePath(ctx, "minecraftWorlds"));
    }

    /** {@code <dataDir>/mc/<slug>/games/com.mojang/<name>} — the same base the slug helpers use. */
    private File instancePath(Context ctx, String name) {
        return new File(IdentityStore.instanceRoot(ctx, slug), "games/com.mojang/" + name);
    }

    // ------------------------------------------------------------- list state

    /** Re-reads the worlds folder off the main thread and republishes it to the adapter. */
    private void refresh() {
        if (binding == null || listing) return;
        final Context ctx = requireContext();
        final File root = worldsRoot(ctx);
        listing = true;
        new Thread(() -> {
            final List<WorldAdapter.World> found = WorldAdapter.World.list(root);
            FragmentActivity act = getActivity();
            if (act == null) return;
            act.runOnUiThread(() -> {
                listing = false;
                if (binding == null) return;
                adapter.submit(found);
                boolean empty = found.isEmpty();
                binding.worldList.setVisibility(empty ? View.GONE : View.VISIBLE);
                binding.textEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
            });
        }, "world-list").start();
    }

    // ------------------------------------------------------------------ actions

    @Override
    public void onWorldClick(WorldAdapter.World world) {
        showOptions(world);
    }

    @Override
    public void onWorldMore(WorldAdapter.World world, View anchor) {
        showOptions(world);
    }

    private void showOptions(WorldAdapter.World world) {
        if (getContext() == null) return;
        String[] options = new String[]{
                getString(R.string.world_preview),
                getString(R.string.world_export),
                getString(R.string.common_delete)};
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(world.displayName())
                .setItems(options, (dialog, which) -> {
                    if (which == 0) showPreview(world);
                    else if (which == 1) startExport(world);
                    else showDelete(world);
                })
                .show();
    }

    private void showPreview(WorldAdapter.World world) {
        Context ctx = requireContext();
        String message = getString(R.string.content_label_name, world.displayName()) + "\n"
                + getString(R.string.content_label_folder, world.id) + "\n"
                + getString(R.string.content_label_modified,
                dateFormat.format(new Date(world.modified))) + "\n"
                + getString(R.string.content_label_size,
                Formatter.formatShortFileSize(ctx, world.size));
        new MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.world_preview)
                .setMessage(message)
                .setPositiveButton(R.string.common_got_it, null)
                .show();
    }

    private void startExport(WorldAdapter.World world) {
        pendingExport = world;
        exportLauncher.launch(world.id + ".mcworld");
    }

    private void onExportPicked(@Nullable Uri target) {
        WorldAdapter.World world = pendingExport;
        pendingExport = null;
        if (target == null || world == null || binding == null) return;
        final Context ctx = requireContext();
        toast(getString(R.string.common_working));
        new Thread(() -> {
            final boolean ok = PackZip.exportToZip(ctx, world.dir, target);
            post(() -> {
                if (binding == null) return;
                if (ok) {
                    String name = PackZip.displayName(ctx, target);
                    toast(getString(R.string.content_export_success,
                            name == null ? world.id + ".mcworld" : name));
                } else {
                    toast(getString(R.string.content_export_failed));
                }
            });
        }, "world-export").start();
    }

    private void onImportPicked(@Nullable Uri uri) {
        if (uri == null || binding == null) return;
        final Context ctx = requireContext();
        String name = PackZip.displayName(ctx, uri);
        String ext = PackZip.extension(name);
        if (name != null && !ext.isEmpty() && !ext.equals("mcworld") && !ext.equals("zip")) {
            toast(getString(R.string.world_import_type_error));
            return;
        }
        final File root = worldsRoot(ctx);
        if (root == null) {
            toast(getString(R.string.content_no_instance));
            return;
        }
        GameDataStore.ensure(root);
        toast(getString(R.string.common_working));
        new Thread(() -> {
            final PackZip.Result result = PackZip.importFromZip(ctx, uri, root);
            post(() -> {
                if (binding == null) return;
                toast(result.success()
                        ? getString(R.string.content_import_success, result.imported.size())
                        : result.error);
                refresh();
            });
        }, "world-import").start();
    }

    private void showDelete(WorldAdapter.World world) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.world_delete_title)
                .setMessage(getString(R.string.common_delete_confirm_message, world.displayName()))
                .setPositiveButton(R.string.common_delete, (dialog, which) -> deleteWorld(world))
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }

    private void deleteWorld(WorldAdapter.World world) {
        new Thread(() -> {
            final boolean ok = PackZip.deleteRec(world.dir);
            post(() -> {
                if (binding == null) return;
                toast(ok ? getString(R.string.instance_deleted)
                        : getString(R.string.content_delete_failed, world.id));
                refresh();
            });
        }, "world-delete").start();
    }

    // ---------------------------------------------------------------- plumbing

    private void post(Runnable ui) {
        FragmentActivity act = getActivity();
        if (act != null) act.runOnUiThread(ui);
    }

    private void toast(String message) {
        Context ctx = getContext();
        if (ctx != null && message != null) {
            Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show();
        }
    }
}
