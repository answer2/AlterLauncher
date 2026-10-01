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
import com.answer.launcher.core.bridge.GameBridgeClient;
import com.answer.launcher.core.manager.PluginPaths;
import com.answer.launcher.databinding.FragmentPluginsBinding;
import com.answer.launcher.ui.PackZip;
import com.answer.launcher.ui.PluginAdapter;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * 插件 (task #25): the fifth content-tab. Lists both plugin kinds the game loaders pick up —
 * {@code files/plugin/} (dex/apk/jar, loaded by {@code PluginManager}) and
 * {@code files/nativePlugin/} ({@code .so}, loaded by {@code NativePluginManager}) — in one
 * RecyclerView, with import, delete and an enable/disable switch.
 *
 * <p>These directories live in shared app storage and are process-wide, not per-instance, so
 * the UI can write them directly. The base is resolved exactly the way the two managers resolve
 * it ({@link Alternative#getHostFilesDir()}, falling back to {@code getFilesDir()} while that is
 * still unset) so the UI and {@code :game} always look at the same tree. Enabling/disabling is a
 * rename to (or from) a {@code .off} suffix, which is the one lever the extension-scanning loaders
 * respond to; after every change we ask {@code :game} to rescan and remind the player it lands on
 * the next launch. All file I/O runs on a worker thread and hops back through the activity.
 */
public class PluginsFragment extends Fragment implements PluginAdapter.Listener {

    private static final String ARG_SLUG = "slug";

    private FragmentPluginsBinding binding;
    private PluginAdapter adapter;
    private ActivityResultLauncher<String[]> importLauncher;
    private volatile boolean listing;
    /** The installation whose plugins are shown; null means the shared (no-instance) dir. */
    private String slug;

    public static PluginsFragment newInstance(String slug) {
        PluginsFragment f = new PluginsFragment();
        Bundle args = new Bundle();
        args.putString(ARG_SLUG, slug);
        f.setArguments(args);
        return f;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            slug = getArguments().getString(ARG_SLUG);
        }
        importLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), this::onImportPicked);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        binding = FragmentPluginsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        adapter = new PluginAdapter(this);
        binding.pluginList.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.pluginList.setAdapter(adapter);
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
        listing = false;
    }

    // ----------------------------------------------------------------- paths

    /**
     * The two plugin dirs for the installation being managed. Resolved through
     * {@link PluginPaths} so the UI writes exactly where {@code :game} reads for that instance
     * (task #35): a null slug means the shared dir (system install / no imported instance).
     */
    private File dexDir() {
        return PluginPaths.dexDir(requireContext(), slug);
    }

    private File nativeDir() {
        return PluginPaths.nativeDir(requireContext(), slug);
    }

    private static void ensureDir(File dir) {
        if (dir != null && !dir.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
    }

    // ------------------------------------------------------------- list state

    private void refresh() {
        if (binding == null || listing) return;
        final File dex = dexDir();
        final File nativeDir = nativeDir();
        listing = true;
        new Thread(() -> {
            final List<PluginAdapter.Plugin> found = PluginAdapter.Plugin.list(dex, nativeDir);
            FragmentActivity act = getActivity();
            if (act == null) return;
            act.runOnUiThread(() -> {
                listing = false;
                if (binding == null) return;
                adapter.submit(found);
                boolean empty = found.isEmpty();
                binding.pluginList.setVisibility(empty ? View.GONE : View.VISIBLE);
                binding.textEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
            });
        }, "plugin-list").start();
    }

    // ------------------------------------------------------------------ actions

    @Override
    public void onPluginClick(PluginAdapter.Plugin plugin) {
        showOptions(plugin);
    }

    @Override
    public void onPluginMore(PluginAdapter.Plugin plugin, View anchor) {
        showOptions(plugin);
    }

    private void showOptions(PluginAdapter.Plugin plugin) {
        if (getContext() == null) return;
        String[] options = new String[]{
                getString(R.string.plugin_preview),
                getString(R.string.common_delete)};
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(plugin.displayName)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) showPreview(plugin);
                    else showDelete(plugin);
                })
                .show();
    }

    private void showPreview(PluginAdapter.Plugin plugin) {
        Context ctx = requireContext();
        String type = getString(plugin.kind == PluginAdapter.KIND_NATIVE
                ? R.string.plugin_kind_native : R.string.plugin_kind_dex);
        String message = getString(R.string.content_label_name, plugin.displayName) + "\n"
                + getString(R.string.plugin_label_type, type) + "\n"
                + getString(R.string.content_label_folder,
                plugin.file.getParentFile() == null ? "" : plugin.file.getParentFile().getName()) + "\n"
                + getString(R.string.content_label_size, Formatter.formatShortFileSize(ctx, plugin.size));
        new MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.plugin_preview)
                .setMessage(message)
                .setPositiveButton(R.string.common_got_it, null)
                .show();
    }

    private void onImportPicked(@Nullable Uri uri) {
        if (uri == null || binding == null) return;
        final Context ctx = requireContext();
        final String rawName = PackZip.displayName(ctx, uri);
        final String name = rawName == null ? null : new File(rawName).getName();
        final String ext = PackZip.extension(name);
        final File dir = dirForExtension(ext);
        if (dir == null) {
            toast(getString(R.string.plugin_import_type_error));
            return;
        }
        ensureDir(dir);
        toast(getString(R.string.common_working));
        new Thread(() -> {
            final String importedName = copyInto(ctx, uri, dir, name, ext);
            post(() -> {
                if (binding == null) return;
                if (importedName == null) {
                    toast(getString(R.string.content_unreadable));
                } else {
                    toast(getString(R.string.plugin_import_success, importedName));
                    GameBridgeClient.get().reloadPlugins();
                    toast(getString(R.string.plugin_change_effect));
                }
                refresh();
            });
        }, "plugin-import").start();
    }

    /** Picks the destination root from a lower-case extension, or null for an unsupported type. */
    @Nullable
    private File dirForExtension(@Nullable String ext) {
        if (ext == null || ext.isEmpty()) return null;
        if (ext.equals("so")) return nativeDir();
        if (ext.equals("apk") || ext.equals("dex") || ext.equals("jar")) return dexDir();
        return null;
    }

    /** Streams the SAF uri into {@code dir} under a de-duplicated name; returns the final name. */
    @Nullable
    private String copyInto(Context ctx, Uri uri, File dir, @Nullable String name, @Nullable String ext) {
        File temp = null;
        try {
            temp = PackZip.copyToCache(ctx, uri);
            if (temp == null || temp.length() == 0) return null;
            String base = name != null && !name.isEmpty() ? name : "plugin-" + System.nanoTime()
                    + (ext == null || ext.isEmpty() ? "" : "." + ext);
            File dest = uniqueIn(dir, base);
            PackZip.copyRec(temp, dest);
            return dest.getName();
        } catch (IOException | SecurityException e) {
            return null;
        } finally {
            if (temp != null) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
        }
    }

    private static File uniqueIn(File dir, String name) {
        File dest = new File(dir, name);
        if (!dest.exists()) return dest;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String suffix = dot > 0 ? name.substring(dot) : "";
        int n = 2;
        while (dest.exists()) {
            dest = new File(dir, stem + "-" + n++ + suffix);
        }
        return dest;
    }

    // ------------------------------------------------------- enable/disable

    @Override
    public void onPluginToggle(PluginAdapter.Plugin plugin, boolean enable) {
        final File file = plugin.file;
        new Thread(() -> {
            final boolean ok = renameToggle(file, enable);
            post(() -> {
                if (binding == null) return;
                if (!ok) {
                    toast(getString(R.string.plugin_toggle_failed, plugin.displayName));
                } else {
                    GameBridgeClient.get().reloadPlugins();
                    toast(getString(R.string.plugin_change_effect));
                }
                refresh();
            });
        }, "plugin-toggle").start();
    }

    /** Disable appends {@code .off} so the loaders stop matching; enable strips it back off. */
    private static boolean renameToggle(File file, boolean enable) {
        if (file == null || !file.exists()) return false;
        String name = file.getName();
        String target = enable ? PluginAdapter.stripOff(name) : name + PluginAdapter.OFF_SUFFIX;
        if (name.equals(target)) return true; // already in the requested state
        File dest = new File(file.getParentFile(), target);
        if (dest.exists()) return false;
        if (file.renameTo(dest)) return true;
        // renameTo can fail across some storage mounts; fall back to a copy + delete.
        try {
            PackZip.copyRec(file, dest);
            return dest.exists() && PackZip.deleteRec(file);
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- delete

    private void showDelete(PluginAdapter.Plugin plugin) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.plugin_delete_title)
                .setMessage(getString(R.string.common_delete_confirm_message, plugin.displayName))
                .setPositiveButton(R.string.common_delete, (dialog, which) -> deletePlugin(plugin))
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }

    private void deletePlugin(PluginAdapter.Plugin plugin) {
        final File file = plugin.file;
        new Thread(() -> {
            final boolean ok = PackZip.deleteRec(file);
            post(() -> {
                if (binding == null) return;
                toast(ok ? getString(R.string.instance_deleted)
                        : getString(R.string.content_delete_failed, file.getName()));
                GameBridgeClient.get().reloadPlugins();
                refresh();
            });
        }, "plugin-delete").start();
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
