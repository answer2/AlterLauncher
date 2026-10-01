package com.answer.launcher.ui;

import android.content.Context;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.answer.launcher.R;
import com.answer.launcher.databinding.ItemPluginBinding;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * RecyclerView list for the plugin import screen (task #25). One list carries both plugin
 * kinds — {@code files/plugin/} (dex/apk, loaded by {@code PluginManager}) and
 * {@code files/nativePlugin/} ({@code .so}, loaded by {@code NativePluginManager}).
 *
 * <p>Enable state is encoded in the file name, not in a settings store: the loaders only pick
 * up a plugin whose name still ends in its real extension, so a file renamed to end in
 * {@code .off} is invisible to them and renders here as switched off. The switch itself does
 * not rename anything; it reports the intent to the fragment, which owns the file system.
 */
public class PluginAdapter extends RecyclerView.Adapter<PluginAdapter.Holder> {

    public static final int KIND_DEX = 0;
    public static final int KIND_NATIVE = 1;

    /** Marker appended to a plugin file name to keep the extension-based loaders from seeing it. */
    public static final String OFF_SUFFIX = ".off";

    /** One plugin file. Immutable snapshot taken off the main thread. */
    public static final class Plugin {
        public final File file;
        public final int kind;
        public final String displayName;
        public final boolean enabled;
        public final long size;

        private Plugin(File file, int kind) {
            this.file = file;
            this.kind = kind;
            this.enabled = !file.getName().endsWith(OFF_SUFFIX);
            this.displayName = enabled ? file.getName() : stripOff(file.getName());
            this.size = file.isFile() ? file.length() : 0L;
        }

        /** True when {@code name} is still a live plugin, i.e. a real extension not hidden by .off. */
        public boolean isDex() {
            return kind == KIND_DEX;
        }

        /**
         * Every loadable file in the two roots. Only recognised extensions become rows, so
         * stray helper files (odex, oat, partial downloads) do not show up as broken plugins.
         */
        public static List<Plugin> list(@Nullable File dexDir, @Nullable File nativeDir) {
            List<Plugin> out = new ArrayList<>();
            collect(out, dexDir, KIND_DEX);
            collect(out, nativeDir, KIND_NATIVE);
            Collections.sort(out, new Comparator<Plugin>() {
                @Override
                public int compare(Plugin a, Plugin b) {
                    return a.displayName.compareToIgnoreCase(b.displayName);
                }
            });
            return out;
        }

        private static void collect(List<Plugin> out, @Nullable File dir, int kind) {
            if (dir == null || !dir.isDirectory()) return;
            File[] kids = dir.listFiles();
            if (kids == null) return;
            for (File f : kids) {
                if (f == null || !f.isFile()) continue;
                String base = stripOff(f.getName()).toLowerCase(Locale.US);
                if (kind == KIND_NATIVE && base.endsWith(".so")) {
                    out.add(new Plugin(f, KIND_NATIVE));
                } else if (kind == KIND_DEX
                        && (base.endsWith(".dex") || base.endsWith(".apk") || base.endsWith(".jar"))) {
                    out.add(new Plugin(f, KIND_DEX));
                }
            }
        }
    }

    /** {@code foo.apk.off} -> {@code foo.apk}; anything else is returned unchanged. */
    public static String stripOff(String name) {
        return name != null && name.endsWith(OFF_SUFFIX)
                ? name.substring(0, name.length() - OFF_SUFFIX.length()) : name;
    }

    /** Row actions; every callback arrives on the main thread. */
    public interface Listener {
        void onPluginClick(Plugin plugin);

        void onPluginMore(Plugin plugin, View anchor);

        void onPluginToggle(Plugin plugin, boolean enable);
    }

    private final List<Plugin> items = new ArrayList<>();
    private final Listener listener;

    public PluginAdapter(@NonNull Listener listener) {
        this.listener = listener;
    }

    public void submit(List<Plugin> next) {
        items.clear();
        if (next != null) items.addAll(next);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(ItemPluginBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        final Plugin p = items.get(position);
        Context ctx = h.binding.getRoot().getContext();
        h.binding.pluginName.setText(p.displayName);
        h.binding.pluginIcon.setImageResource(p.kind == KIND_NATIVE
                ? R.drawable.ic_nav_global : R.drawable.ic_nav_behavior);
        String kindLabel = ctx.getString(p.kind == KIND_NATIVE
                ? R.string.plugin_kind_native : R.string.plugin_kind_dex);
        h.binding.pluginKind.setText(kindLabel + " · "
                + Formatter.formatShortFileSize(ctx, p.size));

        // Detach while recycling, otherwise the setter fires the previous row's listener.
        h.binding.pluginEnabled.setOnCheckedChangeListener(null);
        h.binding.pluginEnabled.setChecked(p.enabled);
        h.binding.pluginEnabled.setOnCheckedChangeListener((button, checked) ->
                listener.onPluginToggle(p, checked));

        h.binding.getRoot().setOnClickListener(v -> listener.onPluginClick(p));
        h.binding.pluginMore.setOnClickListener(v -> listener.onPluginMore(p, v));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ItemPluginBinding binding;

        Holder(ItemPluginBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
