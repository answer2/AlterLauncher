package com.answer.launcher.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.answer.launcher.R;
import com.answer.launcher.databinding.ItemWorldBinding;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

/**
 * RecyclerView list for {@code minecraftWorlds/} (task #18). The row shows the world's
 * own {@code world.png} when it has one and the {@code LevelName} stored inside
 * {@code level.dat}, falling back to the folder name — Minecraft itself does the same.
 *
 * <p>The list is built by {@link World#list(File)} which runs on a worker thread, so
 * the (small) icon bitmaps are decoded there too and simply handed to the holder.
 */
public class WorldAdapter extends RecyclerView.Adapter<WorldAdapter.Holder> {

    /** One entry of {@code minecraftWorlds/}. Immutable snapshot taken off the main thread. */
    public static final class World {
        public final File dir;
        public final String id;
        public final String name;
        public final long modified;
        public final long size;
        public final Bitmap icon;

        private World(File dir) {
            this.dir = dir;
            this.id = dir.getName();
            this.name = readLevelName(dir);
            this.modified = dir.lastModified();
            this.size = PackZip.dirSize(dir);
            this.icon = PackZip.decodeSampled(findIcon(dir), 192);
        }

        /**
         * Bedrock stores the thumbnail as {@code world_icon.jpeg} (older builds used
         * {@code world.png}); check both spellings so the card shows the real image
         * instead of the placeholder.
         */
        @Nullable
        private static File findIcon(File dir) {
            String[] names = {"world_icon.jpeg", "world_icon.jpg", "world_icon.png", "world.png"};
            for (String n : names) {
                File f = new File(dir, n);
                if (f.isFile()) return f;
            }
            return null;
        }

        public String displayName() {
            return name == null || name.trim().isEmpty() ? id : name.trim();
        }

        /**
         * Every sub-folder that looks like a world. Sorting is by display name so the
         * list does not reshuffle between two refreshes.
         */
        public static List<World> list(File root) {
            if (root == null || !root.isDirectory()) return Collections.emptyList();
            File[] kids = root.listFiles();
            if (kids == null) return Collections.emptyList();
            List<World> out = new ArrayList<>();
            for (File dir : kids) {
                if (dir == null || !dir.isDirectory()) continue;
                if (!new File(dir, "level.dat").isFile()) continue;
                out.add(new World(dir));
            }
            Collections.sort(out, new Comparator<World>() {
                @Override
                public int compare(World a, World b) {
                    return a.displayName().compareToIgnoreCase(b.displayName());
                }
            });
            return out;
        }

        // -------------------------------------------------------- level.dat (NBT)

        /** {@code LevelName} from the world's level.dat, or "" when absent/unreadable. */
        private static String readLevelName(File worldDir) {
            File legacy = new File(worldDir, "levelname.txt");
            byte[] nbt = readLimited(new File(worldDir, "level.dat"), MAX_NBT_BYTES);
            if (nbt != null) {
                Cursor c = new Cursor(nbt);
                try {
                    if (c.u8() == TAG_Compound) {
                        c.string();                       // the root compound is unnamed
                        String found = search(c, 0);
                        if (found != null && !found.trim().isEmpty()) return found;
                    }
                } catch (IndexOutOfBoundsException e) {
                    // Truncated or unexpected layout: fall through to the text file.
                }
            }
            if (legacy.isFile()) {
                byte[] raw = readLimited(legacy, 4096);
                if (raw != null) {
                    String s = new String(raw, StandardCharsets.UTF_8).trim();
                    if (!s.isEmpty()) return s;
                }
            }
            return "";
        }

        /** Depth-first hunt for the first {@code LevelName} string tag. */
        private static String search(Cursor c, int depth) {
            if (depth > MAX_DEPTH) return null;
            while (true) {
                int type = c.u8();
                if (type == TAG_End) return null;
                String name = c.string();
                switch (type) {
                    case TAG_String: {
                        String value = c.string();
                        if (TAG_LEVEL_NAME.equalsIgnoreCase(name) && !value.trim().isEmpty()) return value;
                        break;
                    }
                    case TAG_Compound: {
                        String nested = search(c, depth + 1);
                        if (nested != null) return nested;
                        break;
                    }
                    case TAG_List:
                        skipList(c, depth + 1);
                        break;
                    default:
                        skipPayload(c, type);
                        break;
                }
            }
        }

        private static void skipList(Cursor c, int depth) {
            int childType = c.u8();
            int count = c.u32();
            if (count < 0 || count > MAX_ELEMENTS) throw new IndexOutOfBoundsException();
            for (int i = 0; i < count; i++) {
                if (childType == TAG_Compound) {
                    search(c, depth + 1);                  // consumes up to the TAG_End
                } else if (childType == TAG_List) {
                    skipList(c, depth + 1);
                } else {
                    skipPayload(c, childType);
                }
            }
        }

        private static void skipPayload(Cursor c, int type) {
            switch (type) {
                case TAG_Byte:
                    c.u8();
                    break;
                case TAG_String:
                    c.string();
                    break;
                case TAG_Short:
                    c.p += 2;
                    break;
                case TAG_Int:
                case TAG_Float:
                    c.p += 4;
                    break;
                case TAG_Long:
                case TAG_Double:
                    c.p += 8;
                    break;
                case TAG_ByteArray:
                    c.skipN(c.u32());
                    break;
                case TAG_IntArray:
                    c.skipN(c.u32() * 4);
                    break;
                case TAG_LongArray:
                    c.skipN(c.u32() * 8);
                    break;
                default:
                    throw new IndexOutOfBoundsException("unknown nbt tag " + type);
            }
        }

        private static byte[] readLimited(File f, int limit) {
            if (f == null || !f.isFile() || f.length() > limit) return null;
            try (InputStream in = new java.io.BufferedInputStream(new java.io.FileInputStream(f))) {
                byte[] buf = new byte[(int) f.length()];
                int off = 0;
                while (off < buf.length) {
                    int read = in.read(buf, off, buf.length - off);
                    if (read <= 0) break;
                    off += read;
                }
                return off == buf.length ? buf : null;
            } catch (IOException e) {
                return null;
            }
        }

        private static final int MAX_NBT_BYTES = 4 * 1024 * 1024;
        private static final int MAX_DEPTH = 6;
        private static final int MAX_ELEMENTS = 100000;
        private static final String TAG_LEVEL_NAME = "levelname";
        private static final int TAG_End = 0;
        private static final int TAG_Byte = 1;
        private static final int TAG_Short = 2;
        private static final int TAG_Int = 3;
        private static final int TAG_Long = 4;
        private static final int TAG_Float = 5;
        private static final int TAG_Double = 6;
        private static final int TAG_ByteArray = 7;
        private static final int TAG_String = 8;
        private static final int TAG_List = 9;
        private static final int TAG_Compound = 10;
        private static final int TAG_IntArray = 11;
        private static final int TAG_LongArray = 12;

        /** Bedrock writes level.dat as little-endian NBT, so DataInputStream won't do. */
        private static final class Cursor {
            final byte[] b;
            int p;

            Cursor(byte[] b) {
                this.b = b;
            }

            int u8() {
                return b[p++] & 0xFF;
            }

            int u16() {
                int v = (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8);
                p += 2;
                return v;
            }

            int u32() {
                long v = (b[p] & 0xFFL) | ((b[p + 1] & 0xFFL) << 8)
                        | ((b[p + 2] & 0xFFL) << 16) | ((b[p + 3] & 0xFFL) << 24);
                p += 4;
                return (int) v;
            }

            String string() {
                int len = u16();
                if (len < 0) throw new IndexOutOfBoundsException();
                String s = new String(b, p, len, StandardCharsets.UTF_8);
                p += len;
                return s;
            }

            void skipN(int n) {
                if (n < 0) throw new IndexOutOfBoundsException();
                p += n;
            }
        }
    }

    /** Row actions; every callback arrives on the main thread. */
    public interface Listener {
        void onWorldClick(World world);

        void onWorldMore(World world, View anchor);
    }

    private final List<World> items = new ArrayList<>();
    private final Listener listener;
    private final DateFormat dateFormat =
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);

    public WorldAdapter(@NonNull Listener listener) {
        this.listener = listener;
    }

    public void submit(List<World> next) {
        items.clear();
        if (next != null) items.addAll(next);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(ItemWorldBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        final World w = items.get(position);
        Context ctx = h.binding.getRoot().getContext();
        h.binding.worldName.setText(w.displayName());
        h.binding.worldMeta.setText(ctx.getString(R.string.world_meta_format,
                w.id, dateFormat.format(new Date(w.modified))));
        if (w.icon != null) {
            h.binding.worldIcon.setImageBitmap(w.icon);
        } else {
            h.binding.worldIcon.setImageResource(R.drawable.ic_empty_world);
        }
        h.binding.getRoot().setOnClickListener(v -> listener.onWorldClick(w));
        h.binding.worldMore.setOnClickListener(v -> listener.onWorldMore(w, v));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ItemWorldBinding binding;

        Holder(ItemWorldBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
