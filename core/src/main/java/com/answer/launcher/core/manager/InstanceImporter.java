package com.answer.launcher.core.manager;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import com.answer.launcher.core.tool.Reflector;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Imports a Minecraft APK (picked via SAF) into the instance store
 * (levi-compat-design.md §3). Flow: copy the content Uri to a cache file →
 * verify it is {@code com.mojang.minecraftpe} via
 * {@link PackageManager#getPackageArchiveInfo} → derive the instance id from
 * the isolation slug (+ {@code -n} uniqueness) → move base.apk → extract
 * {@code lib/<abi>/*.so} → 16KB page-alignment check → write instance.json.
 */
public class InstanceImporter {

    private static final String TAG = "InstanceImporter";
    public static final String MINECRAFT_PACKAGE = "com.mojang.minecraftpe";

    /** Outcome of an import attempt. {@code instance} is null on failure. */
    public static final class Result {
        public final Instance instance;
        public final String error;
        public final boolean aligned16kb;
        Result(Instance i, String e, boolean a) { instance = i; error = e; aligned16kb = a; }
        public boolean success() { return instance != null; }
    }

    public static Result import_(Context ctx, Uri apkUri, String label) {
        File cache = null;
        try {
            cache = copyToCache(ctx, apkUri);
            if (cache == null) return new Result(null, "无法读取所选文件", false);

            PackageInfo pi = ctx.getPackageManager().getPackageArchiveInfo(
                    cache.getAbsolutePath(),
                    PackageManager.GET_ACTIVITIES | PackageManager.GET_PROVIDERS);
            if (pi == null || pi.applicationInfo == null) {
                return new Result(null, "这不是有效的 APK 安装包", false);
            }
            if (!MINECRAFT_PACKAGE.equals(pi.packageName)) {
                return new Result(null, "包名不是 Minecraft (" + pi.packageName + ")", false);
            }
            String versionName = pi.versionName == null ? "unknown" : pi.versionName;
            long versionCode = pi.getLongVersionCode();

            String abi = pickAbi(ctx, cache);
            if (abi == null) {
                return new Result(null, "APK 内未找到适配本机的 native 库 (abi 不匹配)", false);
            }

            String id = uniqueId(ctx, versionName);
            File root = InstanceStore.instancesRoot(ctx);
            File dir = new File(root, id);
            if (!dir.exists()) dir.mkdirs();

            File baseApk = new File(dir, "base.apk");
            if (!cache.renameTo(baseApk)) {
                copyFile(cache, baseApk);
                cache.delete();
            }
            // ART refuses to load dex from a file the app can write
            // ("Writable dex file ... is not allowed", SecurityException in
            // DexFile.openDexFileNative). The archive must be read-only before
            // any boot; unlink still works later because deletion is governed
            // by the (writable) parent directory, not the file mode.
            baseApk.setReadOnly();

            boolean aligned = true;
            if (abi.isEmpty()) {
                // No lib/ entries at all (rare for the base APK). Proceed without libs.
                aligned = false;
            } else {
                new File(dir, "lib/" + abi).mkdirs();
                aligned = extractLibs(baseApk, abi, new File(dir, "lib/" + abi));
            }

            String displayLabel = (label == null || label.trim().isEmpty())
                    ? versionName : label.trim();
            boolean isolation = true; // default: per-instance data root
            Instance in = new Instance(id, displayLabel, apkUri.toString(),
                    versionName, versionCode, abi, System.currentTimeMillis(),
                    isolation, "", new java.util.ArrayList<>());
            in.aligned16kb = aligned;
            InstanceStore.writeJson(ctx, in);
            return new Result(in, null, aligned);
        } catch (Exception e) {
            Log.e(TAG, "import failed", e);
            return new Result(null, "导入失败：" + e.getMessage(), false);
        } finally {
            if (cache != null) cache.delete();
        }
    }

    private static File copyToCache(Context ctx, Uri uri) throws IOException {
        File out = File.createTempFile("import-", ".apk", ctx.getCacheDir());
        try (InputStream is = ctx.getContentResolver().openInputStream(uri);
             OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
            if (is == null) { out.delete(); return null; }
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
        }
        return out;
    }

    private static String uniqueId(Context ctx, String versionName) {
        String base = IOManager.isolationSlug(MINECRAFT_PACKAGE, versionName);
        File root = InstanceStore.instancesRoot(ctx);
        if (!new File(root, base).exists()) return base;
        for (int n = 2; n < 1000; n++) {
            String cand = base + "-" + n;
            if (!new File(root, cand).exists()) return cand;
        }
        return base + "-" + System.currentTimeMillis();
    }

    /**
     * First ABI this process can actually load that also has lib entries; "" if the APK has no
     * libs; null if libs exist but none match.
     *
     * <p>The preference list leads with the running process's own primary ABI, not the device's.
     * {@code Build.SUPPORTED_ABIS} reports the <em>device</em> list (arm64-v8a first on a 64-bit
     * phone) regardless of the process bitness, so a 32-bit build would otherwise pick arm64-v8a
     * out of a universal APK and then fail to {@code dlopen} it. Preferring {@code primaryCpuAbi}
     * makes the 32-bit build import universal/32-bit packages as armeabi-v7a (issue #45).
     */
    private static String pickAbi(Context ctx, File apk) {
        Set<String> abisInApk = new LinkedHashSet<>();
        boolean hasLibs = false;
        try (ZipFile zf = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> e = zf.entries();
            while (e.hasMoreElements()) {
                ZipEntry ze = e.nextElement();
                String n = ze.getName();
                if (n.startsWith("lib/")) {
                    hasLibs = true;
                    int slash = n.indexOf('/', 4);
                    if (slash > 0) abisInApk.add(n.substring(4, slash));
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "pickAbi zip read failed", e);
            return null;
        }
        if (!hasLibs) return "";
        for (String abi : abiPreference(ctx)) {
            if (abisInApk.contains(abi)) return abi;
        }
        return null;
    }

    /** The ABIs this process can load, best first: its own primary ABI, then the device list. */
    private static List<String> abiPreference(Context ctx) {
        LinkedHashSet<String> pref = new LinkedHashSet<>();
        try {
            // primaryCpuAbi is @hide, so it is read reflectively — but it is the only field that
            // names the ABI this process actually runs as (Build.SUPPORTED_ABIS is the device's
            // full list regardless of process bitness).
            Object primary = Reflector.on(ApplicationInfo.class)
                    .field("primaryCpuAbi").get(ctx.getApplicationInfo());
            if (primary instanceof String && !((String) primary).isEmpty()) {
                pref.add((String) primary);
            }
        } catch (Throwable ignored) {
            // Unreadable early on; the device list below still applies.
        }
        for (String abi : Build.SUPPORTED_ABIS) pref.add(abi);
        return new ArrayList<>(pref);
    }

    private static boolean extractLibs(File apk, String abi, File dest) {
        boolean allAligned = true;
        try (ZipFile zf = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> e = zf.entries();
            while (e.hasMoreElements()) {
                ZipEntry ze = e.nextElement();
                String n = ze.getName();
                if (!n.startsWith("lib/" + abi + "/") || !n.endsWith(".so")) continue;
                File out = new File(dest, new File(n).getName());
                try (InputStream is = zf.getInputStream(ze);
                     OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
                    byte[] buf = new byte[8192];
                    int r;
                    while ((r = is.read(buf)) > 0) os.write(buf, 0, r);
                }
                if (!is16kbAligned(out)) {
                    allAligned = false;
                    Log.w(TAG, "16KB-unaligned .so: " + out.getName());
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "extractLibs failed", e);
            return false;
        }
        return allAligned;
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (InputStream is = new java.io.FileInputStream(src);
             OutputStream os = new BufferedOutputStream(new FileOutputStream(dst))) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
        }
    }

    /**
     * A .so is 16KB-page compatible iff every PT_LOAD segment has
     * {@code p_align >= 0x4000} (Android 15+ requirement). Reads the 64-bit ELF
     * program-header table directly; 32-bit ELFs predate the 16KB rule and
     * are reported as aligned.
     */
    static boolean is16kbAligned(File so) {
        try (RandomAccessFile raf = new RandomAccessFile(so, "r")) {
            byte[] ident = new byte[5];
            raf.readFully(ident);
            if (ident[0] != 0x7f || ident[1] != 'E' || ident[2] != 'L' || ident[3] != 'F')
                return false;
            if (ident[4] != 2) return true; // ELFCLASS32 — predates 16KB rule
            long phoff = readLong(raf, 32);
            int phentsize = readShort(raf, 54);
            int phnum = readShort(raf, 56);
            for (int i = 0; i < phnum; i++) {
                long phdrBase = phoff + (long) i * phentsize;
                int pType = readInt(raf, phdrBase);
                if (pType != 1) continue; // PT_LOAD
                long pAlign = readLong(raf, phdrBase + 48);
                if (pAlign < 0x4000L) return false;
            }
            return true;
        } catch (IOException e) {
            Log.w(TAG, "is16kbAligned read failed for " + so.getName(), e);
            return false;
        }
    }

    private static long readLong(RandomAccessFile raf, long off) throws IOException {
        raf.seek(off);
        long v = 0;
        for (int i = 7; i >= 0; i--) v = (v << 8) | (raf.readByte() & 0xFF);
        return v;
    }

    private static int readShort(RandomAccessFile raf, int off) throws IOException {
        raf.seek(off);
        return (raf.readByte() & 0xFF) | ((raf.readByte() & 0xFF) << 8);
    }

    private static int readInt(RandomAccessFile raf, long off) throws IOException {
        raf.seek(off);
        return (raf.readByte() & 0xFF) | ((raf.readByte() & 0xFF) << 8)
                | ((raf.readByte() & 0xFF) << 16) | ((raf.readByte() & 0xFF) << 24);
    }
}
