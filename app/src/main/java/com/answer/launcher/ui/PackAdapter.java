package com.answer.launcher.ui;

import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.answer.launcher.R;
import com.answer.launcher.databinding.ItemPackBinding;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * RecyclerView list for {@code resource_packs/} and {@code behavior_packs/} (task #19).
 * Both tabs share this adapter and differ only in the directory they are pointed at.
 *
 * <p>A pack is "enabled" when its folder id is reachable from
 * {@code games/com.mojang/packs/} — the global add-on store the game applies to every
 * world. The switch itself does not touch that store; it reports the intent to the
 * fragment, which owns the linking (task #20 refines the mechanism).
 */
public class PackAdapter extends RecyclerView.Adapter<PackAdapter.Holder> {

    /** One entry of a pack root. Immutable snapshot taken off the main thread. */
    public static final class Pack {
        public final File dir;
        /** Folder name — what {@code packs/} links are named after. */
        public final String id;
        public final String uuid;
        public final String name;
        public final String description;
        public final String version;
        public final int modules;
        public final boolean enabled;
        public final Bitmap icon;
        public final long modified;
        public final long size;

        private Pack(File dir, File globalDir) {
            this.dir = dir;
            this.id = dir.getName();
            JSONObject manifest = PackZip.readManifest(dir);
            this.uuid = firstNonEmpty(PackZip.uuidOf(manifest), id);
            this.name = firstNonEmpty(PackZip.textField(manifest, "name"), id);
            this.description = firstNonEmpty(PackZip.textField(manifest, "description"), "");
            this.version = firstNonEmpty(PackZip.versionOf(manifest), "");
            this.modules = PackZip.moduleCount(manifest);
            this.modified = dir.lastModified();
            this.size = PackZip.dirSize(dir);
            this.icon = PackZip.decodeSampled(findIcon(dir), 192);
            this.enabled = globalDir != null
                    && (new File(globalDir, id).exists() || new File(globalDir, uuid).exists());
        }

        @Nullable
        private static File findIcon(File dir) {
            File png = new File(dir, "pack_icon.png");
            if (png.isFile()) return png;
            File jpg = new File(dir, "pack_icon.jpg");
            if (jpg.isFile()) return jpg;
            File jpeg = new File(dir, "pack_icon.jpeg");
            return jpeg.isFile() ? jpeg : null;
        }

        private static String firstNonEmpty(String a, String b) {
            return a == null || a.trim().isEmpty() ? b : a.trim();
        }

        /**
         * Every sub-folder of {@code root} holding a manifest.json, plus the enabled
         * flag resolved against {@code globalDir} (both may be null).
         */
        public static List<Pack> list(@Nullable File root, @Nullable File globalDir) {
            if (root == null || !root.isDirectory()) return Collections.emptyList();
            File[] kids = root.listFiles();
            if (kids == null) return Collections.emptyList();
            List<Pack> out = new ArrayList<>();
            for (File dir : kids) {
                if (dir == null || !dir.isDirectory()) continue;
                if (!new File(dir, "manifest.json").isFile()) continue;
                out.add(new Pack(dir, globalDir));
            }
            Collections.sort(out, new Comparator<Pack>() {
                @Override
                public int compare(Pack a, Pack b) {
                    return a.name.compareToIgnoreCase(b.name);
                }
            });
            return out;
        }

        /** {@code 3f2a9c1b-4d5e-…} — long uuids are noise in a row, keep the head. */
        public String shortUuid() {
            if (uuid == null) return "";
            return uuid.length() <= 13 ? uuid : uuid.substring(0, 13);
        }
    }

    /** Row actions; every callback arrives on the main thread. */
    public interface Listener {
        void onPackClick(Pack pack);

        void onPackMore(Pack pack, View anchor);

        void onPackToggle(Pack pack, boolean enable);
    }

    private final List<Pack> items = new ArrayList<>();
    private final Listener listener;

    public PackAdapter(@NonNull Listener listener) {
        this.listener = listener;
    }

    public void submit(List<Pack> next) {
        items.clear();
        if (next != null) items.addAll(next);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(ItemPackBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        final Pack p = items.get(position);
        h.binding.packName.setText(p.name);
        h.binding.packDesc.setText(p.description);
        h.binding.packDesc.setVisibility(p.description.isEmpty() ? View.GONE : View.VISIBLE);
        StringBuilder meta = new StringBuilder();
        if (!p.version.isEmpty()) meta.append("v").append(p.version).append(" · ");
        meta.append(p.shortUuid());
        h.binding.packMeta.setText(meta.toString());
        if (p.icon != null) {
            h.binding.packIcon.setImageBitmap(p.icon);
        } else {
            h.binding.packIcon.setImageResource(R.drawable.ic_empty_pack);
        }

        // Detach while recycling, otherwise the setter fires the previous row's listener.
        h.binding.packEnabled.setOnCheckedChangeListener(null);
        h.binding.packEnabled.setChecked(p.enabled);
        h.binding.packEnabled.setOnCheckedChangeListener((button, checked) ->
                listener.onPackToggle(p, checked));

        h.binding.getRoot().setOnClickListener(v -> listener.onPackClick(p));
        h.binding.packMore.setOnClickListener(v -> listener.onPackMore(p, v));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ItemPackBinding binding;

        Holder(ItemPackBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
