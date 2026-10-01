package com.answer.launcher.ui.fragment;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
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
import com.answer.launcher.databinding.FragmentPacksBinding;
import com.answer.launcher.ui.GlobalPackManager;
import com.answer.launcher.ui.PackAdapter;
import com.answer.launcher.ui.PackZip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/**
 * Task #20: cross-version global packs. A pack imported here is stored once under
 * {@code <dataDir>/mc/global_packs/} and symlinked into every instance's
 * resource_packs or behavior_packs directory, so it is visible to every version.
 *
 * <p>This is distinct from the per-instance "globally enabled" {@code packs/} folder
 * (which controls whether a pack applies to every world inside one version): a pack
 * in this screen is shared across <em>versions</em>.
 *
 * <p>The content header still hands this screen a {@code "slug"} (task #33) so the page
 * and the header never disagree, but the storage root deliberately ignores it: shared
 * storage is not per-instance, and linking it into the picked instance only would defeat
 * the whole point.
 */
public class GlobalPacksFragment extends Fragment implements PackAdapter.Listener {

    private static final String ARG_SLUG = "slug";

    private FragmentPacksBinding binding;
    private PackAdapter adapter;
    private ActivityResultLauncher<String[]> importLauncher;
    private volatile boolean listing;
    /** The instance the header has selected; kept for the header's sake, not used for paths. */
    private String slug;
    private final DateFormat dateFormat =
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);

    public static GlobalPacksFragment newInstance(String slug) {
        GlobalPacksFragment f = new GlobalPacksFragment();
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
        listing = false;
    }

    // ----------------------------------------------------------------- paths

    /**
     * The shared pack storage. Unlike the worlds and pack screens this root never follows the
     * selected instance: a global pack lives once under {@code mc/global_packs/} and is linked
     * into every installation, so honouring {@code slug} here would quietly turn the screen into
     * a per-version one.
     */
    private File storageRoot(Context ctx) {
        return GameDataStore.sharedPackStorage(ctx);
    }

    private void refresh() {
        if (binding == null || listing) return;
        final Context ctx = requireContext();
        listing = true;
        new Thread(() -> {
            final File root = storageRoot(ctx);
            final List<PackAdapter.Pack> found = PackAdapter.Pack.list(root, null);
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
        }, "global-pack-list").start();
    }

    @Override
    public void onPackClick(PackAdapter.Pack pack) {
        showOptions(pack);
    }

    @Override
    public void onPackMore(PackAdapter.Pack pack, View anchor) {
        showOptions(pack);
    }

    /**
     * Global packs are always linked into every instance — there is no per-instance
     * enable/disable here. Toggling the switch is a no-op; the switch is hidden by
     * the adapter when the pack is already global.
     */
    @Override
    public void onPackToggle(PackAdapter.Pack pack, boolean enable) {
        // no-op: global packs are inherently enabled across all instances
    }

    private void showOptions(PackAdapter.Pack pack) {
        if (getContext() == null) return;
        String[] options = new String[]{
                getString(R.string.pack_preview),
                getString(R.string.common_delete)};
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(pack.name)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) showPreview(pack);
                    else showDelete(pack);
                })
                .show();
    }

    private void showPreview(PackAdapter.Pack pack) {
        final Context ctx = requireContext();
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.content_label_name, pack.name)).append('\n');
        if (!pack.description.isEmpty()) {
            sb.append(getString(R.string.content_label_description, pack.description)).append('\n');
        }
        sb.append(getString(R.string.content_label_version,
                pack.version.isEmpty() ? getString(R.string.content_unknown) : pack.version)).append('\n');
        sb.append(getString(R.string.content_label_uuid, pack.uuid)).append('\n');
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

    private void showDelete(PackAdapter.Pack pack) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.pack_delete_title)
                .setMessage(getString(R.string.common_delete_confirm_message, pack.name))
                .setNegativeButton(R.string.common_cancel, null)
                .setPositiveButton(R.string.common_delete, (d, w) -> {
                    final Context ctx = requireContext().getApplicationContext();
                    final File dir = pack.dir;
                    new Thread(() -> {
                        boolean ok = GlobalPackManager.removePack(ctx, dir);
                        FragmentActivity act = getActivity();
                        if (act == null) return;
                        act.runOnUiThread(() -> {
                            toast(ok ? getString(R.string.instance_deleted)
                                    : getString(R.string.content_delete_failed, pack.id));
                            refresh();
                        });
                    }, "global-pack-delete").start();
                })
                .show();
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
        final File storage = GameDataStore.ensure(storageRoot(ctx));
        if (storage == null) {
            toast(getString(R.string.content_import_empty));
            return;
        }
        toast(getString(R.string.common_working));
        new Thread(() -> {
            final File staging = new File(ctx.getCacheDir(), "gpk-" + System.currentTimeMillis());
            //noinspection ResultOfMethodCallIgnored
            staging.mkdirs();
            final boolean unzipped = "mcaddon".equals(ext)
                    ? PackZip.importFromAddon(ctx, uri, staging, staging).success()
                    : PackZip.importFromZip(ctx, uri, staging).success();
            if (!unzipped) {
                deleteRecursive(staging);
                post(() -> toast(getString(R.string.content_import_empty)));
                return;
            }
            int added = 0;
            File[] candidates = staging.listFiles(File::isDirectory);
            if (candidates != null) {
                for (File c : candidates) {
                    if (new File(c, "manifest.json").isFile()) {
                        if (GlobalPackManager.addPack(ctx, c)) added++;
                    }
                }
            }
            if (added == 0 && new File(staging, "manifest.json").isFile()) {
                if (GlobalPackManager.addPack(ctx, staging)) added = 1;
            }
            deleteRecursive(staging);
            final int count = added;
            post(() -> {
                if (count == 0) {
                    toast(getString(R.string.content_import_empty));
                } else {
                    toast(getString(R.string.global_pack_import_success, count));
                }
                refresh();
            });
        }, "global-pack-import").start();
    }

    private void post(Runnable r) {
        FragmentActivity act = getActivity();
        if (act != null) act.runOnUiThread(r);
    }

    private void toast(String s) {
        if (getContext() != null) Toast.makeText(getContext(), s, Toast.LENGTH_SHORT).show();
    }

    private static int dp(Context ctx, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics());
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
