package com.answer.launcher.core.manager;

import android.content.Context;
import android.system.Os;
import android.util.Log;

import com.answer.launcher.core.LauncherConstants;

import java.io.File;

/**
 * Where the Microsoft sign-in state lives, and who is allowed to write it.
 *
 * <p>The state is a handful of paths inside the data root — {@code xal/} (the token
 * envelopes), {@code shared_prefs/} (the device keypair that decrypts them),
 * {@code app_webview/} (the cookies the browser flow needs), {@code Flighting},
 * {@code bootstrap_settings.json} and the {@code .ent} licence proofs. C13 gave each
 * installation its own data root, which by itself also gave each installation its own
 * copy of that state, and C15 seeded the copy.
 *
 * <p>Copying an identity turned out to be wrong, and this is the measured reason: XBL
 * refresh tokens rotate. The instance was seeded at 20:40, the user signed in again at
 * 21:08 into the launcher's tree, and that later login invalidated the copy — so every
 * boot since reads a token the server has already rejected, the silent refresh fails
 * with no browser involved, and the title screen comes up signed out while the SDK
 * rewrites the very files it just refused to accept. A copy of a rotating secret is a
 * stale secret.
 *
 * <p>So the identity is either <em>shared</em> (the default: every installation points at
 * one tree, through a symlink rather than a copy, so there is exactly one live token
 * store and rotation cannot split it) or <em>isolated</em> (a tree this installation owns
 * exclusively, which costs a sign-in on first launch — the point of the mode, not an
 * accident). The mode is the user's choice, recorded in
 * {@code <dataDir>/mc/.identity-mode}; the switch is in the settings page and takes
 * effect on the next launch.
 *
 * <p>The symlink direction matters: the instance path is the link and the launcher's own
 * data directory is the target. That way the shared store is a plain directory the
 * launcher can read for its account card without following anything, and deleting an
 * installation removes links only — never the identity itself.
 */
public final class IdentityStore {

    private static final String TAG = "IdentityStore";

    public static final String MODE_SHARED = "shared";
    public static final String MODE_ISOLATED = "isolated";

    /** Written by {@code :game} on every boot so the UI can name the live installation. */
    private static final String ACTIVE_FILE = ".active";
    private static final String MODE_FILE = ".identity-mode";

    private IdentityStore() {
    }

    public static File instancesDir(Context host) {
        return new File(host.getDataDir(), "mc");
    }

    public static File instanceRoot(Context host, String slug) {
        return new File(instancesDir(host), slug);
    }

    /** The mode the next launch will use. Anything unreadable falls back to sharing. */
    public static String mode(Context host) {
        String text = read(new File(instancesDir(host), MODE_FILE));
        return MODE_ISOLATED.equals(text) ? MODE_ISOLATED : MODE_SHARED;
    }

    public static void setMode(Context host, String mode) {
        File file = new File(instancesDir(host), MODE_FILE);
        //noinspection ResultOfMethodCallIgnored
        file.getParentFile().mkdirs();
        write(file, MODE_ISOLATED.equals(mode) ? MODE_ISOLATED : MODE_SHARED);
        Log.i(TAG, "identity mode = " + mode(host));
    }

    public static String activeSlug(Context host) {
        String slug = read(new File(instancesDir(host), ACTIVE_FILE));
        return slug == null || slug.isEmpty() ? null : slug;
    }

    public static void setActiveSlug(Context host, String slug) {
        File file = new File(instancesDir(host), ACTIVE_FILE);
        //noinspection ResultOfMethodCallIgnored
        file.getParentFile().mkdirs();
        write(file, slug);
    }

    /**
     * Clears the sign-in under the account root: the {@code xal/} token store, the crypto prefs
     * that hold the device keypair, and the launcher's own {@code accounts.json}. This is
     * deliberately destructive and irreversible — the tokens are not recoverable without a fresh
     * browser sign-in — so callers must confirm with the user and refuse while {@code :game} is
     * running (XAL keeps the store open and would rewrite it underneath us).
     *
     * <p>In shared mode the account root is the one live tree every installation links to, so this
     * signs all of them out at once — which is the correct meaning of "log out" for a shared
     * identity, not an accident.
     */
    public static boolean signOut(Context host) {
        File root = accountRoot(host);
        boolean ok = deleteRecursive(new File(root, "xal"));
        File crypto = new File(root, "shared_prefs/com.microsoft.xal.crypto.xml");
        if (crypto.isFile()) {
            ok &= crypto.delete();
        }
        File accounts = new File(root, AccountStoreName);
        if (accounts.isFile()) {
            ok &= accounts.delete();
        }
        Log.i(TAG, "signed out " + root + " ok=" + ok);
        return ok;
    }

    /** {@code accounts.json} lives beside {@code xal/}; the launcher owns it, the game does not. */
    private static final String AccountStoreName = "accounts.json";

    private static boolean deleteRecursive(File file) {
        if (!file.exists()) {
            return true;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        return file.delete();
    }

    /**
     * The root the account card should read: the live installation, falling back to the
     * launcher's own directory before the first isolated boot (and to a slug recomputed
     * from the installed package when {@code :game} has not recorded one yet).
     */
    public static File accountRoot(Context host) {
        String slug = activeSlug(host);
        if (slug == null) {
            slug = guessedSlug(host);
        }
        if (slug != null) {
            File candidate = instanceRoot(host, slug);
            if (new File(candidate, "xal").exists()) {
                return candidate;
            }
        }
        return host.getDataDir();
    }

    /**
     * Reads what the identity store can prove without decrypting anything. The token
     * envelopes are JWEs sealed with the title key, so no gamertag is available here —
     * the readable facts are the default-user pointer, the XUID carried by the file
     * names, and whether a token store exists at all.
     */
    public static Account readAccount(Context host) {
        File root = accountRoot(host);
        boolean shared = MODE_SHARED.equals(mode(host));
        try {
            File xal = new File(root, "xal");
            String msa = null;
            boolean hasTokens = false;
            File[] entries = xal.listFiles();
            if (entries != null) {
                for (File entry : entries) {
                    if (entry.length() > 1024) {
                        hasTokens = true;
                    }
                }
                msa = defaultUser(xal);
            }
            String xuid = xuid(root);
            return new Account(shared, root, msa, xuid, hasTokens && msa != null);
        } catch (Throwable t) {
            Log.w(TAG, "account read failed: " + t);
            return new Account(shared, root, null, null, false);
        }
    }

    /** The immutable description of the identity as far as the launcher can see it. */
    public static final class Account {
        public final boolean shared;
        public final File root;
        public final String msaUserId;
        public final String xuid;
        public final boolean signedIn;

        Account(boolean shared, File root, String msaUserId, String xuid, boolean signedIn) {
            this.shared = shared;
            this.root = root;
            this.msaUserId = msaUserId;
            this.xuid = xuid;
            this.signedIn = signedIn;
        }

        /** Short form for the account card: {@code 07cc…e574}. */
        public String maskedAccount() {
            if (msaUserId == null || msaUserId.length() < 10) {
                return msaUserId;
            }
            return msaUserId.substring(0, 4) + "…" + msaUserId.substring(msaUserId.length() - 4);
        }
    }

    /**
     * Points one installation's identity entries at the launcher's shared tree.
     *
     * <p>Returns true when every entry is a link, which is what tells
     * {@code IOManager} to skip the copy-seeding: seeding a directory that is about to
     * become a link would write into the shared tree.
     *
     * <p>Where both sides already have content the newer one wins and the loser is moved
     * — never deleted, because this tree has held the user's only copy of a working
     * sign-in before — into {@code <instance>/.identity-parked/}, outside the shared
     * directory so the SDK's directory scans cannot see it.
     */
    public static boolean link(File sharedRoot, File instanceRoot) {
        boolean complete = true;
        for (String name : IOManager.SEEDED_NAMES) {
            if (!linkOne(new File(sharedRoot, name), new File(instanceRoot, name), instanceRoot)) {
                complete = false;
            }
        }
        File[] licences = sharedRoot.listFiles((dir, name) -> endsWithAny(name, IOManager.SEEDED_SUFFIXES));
        if (licences != null) {
            for (File licence : licences) {
                if (!linkOne(licence, new File(instanceRoot, licence.getName()), instanceRoot)) {
                    complete = false;
                }
            }
        }
        return complete;
    }

    private static boolean linkOne(File shared, File instance, File instanceRoot) {
        try {
            String target = readlink(instance);
            if (target != null) {
                if (target.equals(shared.getAbsolutePath())) {
                    return true;
                }
                Os.remove(instance.getAbsolutePath());
            } else if (instance.exists() && shared.exists()) {
                /*
                 * Both sides have content, and this is the case a whole-directory
                 * comparison gets wrong: {@code shared_prefs} holds dozens of
                 * independent files, and the launcher's own analytics preferences are
                 * touched more often than the game's XAL ones — picking a side by
                 * directory mtime would park the very file that decrypts the tokens and
                 * sign the user out again. So the merge is per file: newest wins, the
                 * loser is moved aside, nothing is deleted.
                 */
                File parked = new File(instanceRoot, ".identity-parked");
                if (!mergeEntry(shared, instance, new File(parked, instance.getName()))) {
                    return false;
                }
            } else if (instance.exists()) {
                // Only this installation has the entry: adopt it into the shared tree.
                //noinspection ResultOfMethodCallIgnored
                shared.getParentFile().mkdirs();
                if (!instance.renameTo(shared)) {
                    return false;
                }
            }
            if (!shared.exists()) {
                // Neither side has it — nothing to share, and not a failure.
                return true;
            }
            if (readlink(instance) != null || instance.exists()) {
                return false;
            }
            Os.symlink(shared.getAbsolutePath(), instance.getAbsolutePath());
            Log.i(TAG, "identity shared: " + instance + " -> " + shared);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "cannot share " + instance + ": " + t);
            return false;
        }
    }

    /** Reconciles two real copies of one entry so the shared tree can take it over. */
    private static boolean mergeEntry(File shared, File instance, File parked) {
        if (shared.isDirectory() && instance.isDirectory()) {
            return mergeDir(instance, shared, parked) && instance.delete();
        }
        if (shared.isFile() && instance.isFile()) {
            if (instance.lastModified() > shared.lastModified()) {
                return move(shared, new File(parked, shared.getName()))
                        && move(instance, shared);
            }
            return move(instance, new File(parked, instance.getName()));
        }
        // Different shapes — one side is a file and the other a directory. The shared
        // tree keeps its form and the odd one out is parked rather than merged badly.
        return move(instance, new File(parked, instance.getName() + ".mismatched"));
    }

    /**
     * Moves every file of {@code from} into {@code to}, newest file winning, and parks
     * each loser under {@code parked} with the same relative name. Returns false when
     * anything could not be moved, which stops the caller from linking over content it
     * has not safely relocated.
     */
    private static boolean mergeDir(File from, File to, File parked) {
        File[] children = from.listFiles();
        if (children == null) {
            return false;
        }
        for (File child : children) {
            File target = new File(to, child.getName());
            File slot = new File(parked, child.getName());
            if (child.isDirectory()) {
                if (target.isFile() && !move(child, new File(parked, child.getName() + ".file"))) {
                    return false;
                }
                //noinspection ResultOfMethodCallIgnored
                target.mkdirs();
                if (!mergeDir(child, target, slot)) {
                    return false;
                }
                if (!child.delete()) {
                    return false;
                }
                continue;
            }
            if (target.isDirectory() && !move(child, new File(parked, child.getName() + ".dir"))) {
                return false;
            }
            boolean mine = !target.exists() || child.lastModified() > target.lastModified();
            if (mine) {
                if (target.exists() && !move(target, slot)) {
                    return false;
                }
                if (!move(child, target)) {
                    return false;
                }
            } else if (!move(child, slot)) {
                return false;
            }
        }
        return true;
    }

    /** A rename that never overwrites: a taken destination gets a numbered suffix. */
    private static boolean move(File from, File to) {
        if (!from.exists()) {
            return true;
        }
        //noinspection ResultOfMethodCallIgnored
        to.getParentFile().mkdirs();
        File slot = to;
        for (int i = 1; slot.exists(); i++) {
            slot = new File(to.getParentFile(), to.getName() + "." + i);
        }
        if (from.renameTo(slot)) {
            return true;
        }
        Log.w(TAG, "cannot move " + from + " to " + slot);
        return false;
    }

    private static String readlink(File file) {
        try {
            return Os.readlink(file.getAbsolutePath());
        } catch (Throwable t) {
            return null;
        }
    }

    /** {@code xal/<msaUserId hash file>} holds {@code {"default":"<msaUserId>"}}. */
    private static String defaultUser(File xal) {
        File[] entries = xal.listFiles();
        if (entries == null) {
            return null;
        }
        for (File entry : entries) {
            if (entry.length() == 0 || entry.length() > 256) {
                continue;
            }
            String text = read(entry);
            if (text == null) {
                continue;
            }
            int key = text.indexOf("\"default\"");
            if (key < 0) {
                continue;
            }
            int open = text.indexOf('"', text.indexOf(':', key));
            int close = open < 0 ? -1 : text.indexOf('"', open + 1);
            if (open > 0 && close > open) {
                return text.substring(open + 1, close);
            }
        }
        return null;
    }

    /**
     * The XUID, from the names the engine itself writes: {@code games/com.mojang/
     * ud<XUID>.dat} and {@code <XUID>.ent}. The tokens that would carry it are sealed,
     * so the file system is the only place it can be read without the title key.
     */
    private static String xuid(File root) {
        File ud = new File(root, "games/com.mojang");
        String found = firstMatch(ud, "ud", ".dat");
        if (found != null) {
            return found;
        }
        return firstMatch(root, null, ".ent");
    }

    private static String firstMatch(File dir, String prefix, String suffix) {
        File[] entries = dir.listFiles();
        if (entries == null) {
            return null;
        }
        for (File entry : entries) {
            String name = entry.getName();
            if (!name.endsWith(suffix)) {
                continue;
            }
            String stem = name.substring(0, name.length() - suffix.length());
            if (prefix != null) {
                if (!stem.startsWith(prefix)) {
                    continue;
                }
                stem = stem.substring(prefix.length());
            }
            if (stem.matches("\\d{6,}")) {
                return stem;
            }
        }
        return null;
    }

    private static String guessedSlug(Context host) {
        try {
            String version = host.getPackageManager()
                    .getPackageInfo(LauncherConstants.MINECRAFTPACKAGE, 0).versionName;
            return IOManager.isolationSlug(LauncherConstants.MINECRAFTPACKAGE, version);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean endsWithAny(String name, String[] suffixes) {
        for (String suffix : suffixes) {
            if (name.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private static String read(File file) {
        if (!file.isFile()) {
            return null;
        }
        try (java.io.InputStream in = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[(int) Math.min(file.length(), 8192)];
            int off = 0;
            while (off < buffer.length) {
                int n = in.read(buffer, off, buffer.length - off);
                if (n <= 0) {
                    break;
                }
                off += n;
            }
            return new String(buffer, 0, off, java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void write(File file, String text) {
        try (java.io.OutputStream out = new java.io.FileOutputStream(file)) {
            out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable t) {
            Log.w(TAG, "cannot write " + file + ": " + t);
        }
    }
}
