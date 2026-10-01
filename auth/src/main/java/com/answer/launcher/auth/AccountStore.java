package com.answer.launcher.auth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The launcher's own list of signed-in accounts, as {@code <dataDir>/mc/accounts.json}.
 *
 * <p>Deliberately outside {@code xal/}: that directory belongs to the game's XAL SDK, which
 * enumerates it and treats every entry as a store item. A file we park there is a file the SDK
 * will try to parse, and a directory it cannot parse is how a login breaks with no error message.
 *
 * <p>One account is active at a time — the game can only be signed in as one person — but the rest
 * stay here so switching is a re-export instead of a fresh browser sign-in.
 *
 * <p>The serialized manager holds long-lived refresh tokens and the device key pair. That is
 * credential material, and it sits in cleartext in app-private storage, which is what the game
 * itself does with the same material today. Moving it behind the Android keystore is a real
 * improvement but it is not this class's job; see the note in docs/baseline-android16.md.
 */
public final class AccountStore {

    public static final String FILE_NAME = "accounts.json";

    /** One signed-in Microsoft account, with everything needed to act as it. */
    public static final class Account {
        public String xuid;
        public String gamertag;
        public long updatedAt;
        /** {@code BedrockAuthManager.toJson} output: the whole token chain plus the device keys. */
        public JsonObject auth;
        /**
         * The Xbox DisplayPicRaw image URL, once it has been fetched. It belongs to the account
         * rather than to a session, so caching it here keeps every avatar on the UI off the network
         * — the profile endpoint is only asked when this is still empty.
         */
        public String avatarUrl;

        public Account() {
        }

        Account(String xuid, String gamertag, JsonObject auth) {
            this.xuid = xuid;
            this.gamertag = gamertag;
            this.auth = auth;
            this.updatedAt = System.currentTimeMillis();
        }
    }

    private final File file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    private String activeXuid;
    private final List<Account> accounts = new ArrayList<>();

    public AccountStore(File file) {
        this.file = file;
        load();
    }

    private void load() {
        accounts.clear();
        activeXuid = null;
        if (!file.isFile()) {
            return;
        }
        try (Reader in = new java.io.InputStreamReader(
                new java.io.FileInputStream(file), StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(in);
            if (!root.isJsonObject()) {
                return;
            }
            JsonObject obj = root.getAsJsonObject();
            if (obj.has("active") && obj.get("active").isJsonPrimitive()) {
                activeXuid = obj.get("active").getAsString();
            }
            JsonArray list = obj.has("accounts") ? obj.getAsJsonArray("accounts") : null;
            if (list != null) {
                for (JsonElement e : list) {
                    if (!e.isJsonObject()) {
                        continue;
                    }
                    JsonObject a = e.getAsJsonObject();
                    Account acc = new Account();
                    acc.xuid = str(a, "xuid");
                    acc.gamertag = str(a, "gamertag");
                    acc.updatedAt = a.has("updatedAt") ? a.get("updatedAt").getAsLong() : 0L;
                    acc.avatarUrl = str(a, "avatarUrl");
                    if (a.has("auth") && a.get("auth").isJsonObject()) {
                        acc.auth = a.getAsJsonObject("auth");
                    }
                    if (acc.xuid != null && acc.auth != null) {
                        accounts.add(acc);
                    }
                }
            }
        } catch (Throwable t) {
            // A store we cannot read is not a reason to lose the store: keep the parsed prefix and
            // let the next successful sign-in rewrite the file whole.
            android.util.Log.w("AccountStore", "unreadable " + file + ": " + t);
        }
        if (activeXuid == null && !accounts.isEmpty()) {
            activeXuid = accounts.get(0).xuid;
        }
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }

    public synchronized void save() throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("cannot create " + parent);
        }
        JsonObject root = new JsonObject();
        if (activeXuid != null) {
            root.addProperty("active", activeXuid);
        }
        JsonArray list = new JsonArray();
        for (Account a : accounts) {
            JsonObject o = new JsonObject();
            o.addProperty("xuid", a.xuid);
            if (a.gamertag != null) {
                o.addProperty("gamertag", a.gamertag);
            }
            o.addProperty("updatedAt", a.updatedAt);
            if (a.avatarUrl != null) {
                o.addProperty("avatarUrl", a.avatarUrl);
            }
            o.add("auth", a.auth);
            list.add(o);
        }
        root.add("accounts", list);
        File tmp = new File(file.getAbsolutePath() + ".tmp");
        try (Writer out = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            gson.toJson(root, out);
        }
        if (!tmp.renameTo(file)) {
            // Rename across the same directory is atomic on ext4/f2fs; a failure here means the
            // file is on a different volume, which it never is. Fall back to writing in place.
            try (Writer out = new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(file), StandardCharsets.UTF_8)) {
                gson.toJson(root, out);
            }
            // Deliberately not deleted: if the in-place write is what succeeded, the temp file is
            // harmless litter, and deleting the wrong thing on a shared volume is not.
            tmp.delete();
        }
    }

    /** Adds or replaces an account and makes it the active one. */
    public synchronized void putActive(Account account) {
        accounts.removeIf(a -> a.xuid != null && a.xuid.equals(account.xuid));
        accounts.add(0, account);
        activeXuid = account.xuid;
    }

    public synchronized void setActive(String xuid) {
        for (Account a : accounts) {
            if (a.xuid != null && a.xuid.equals(xuid)) {
                activeXuid = xuid;
                return;
            }
        }
        activeXuid = null;
    }

    public synchronized Account active() {
        if (activeXuid == null) {
            return null;
        }
        for (Account a : accounts) {
            if (activeXuid.equals(a.xuid)) {
                return a;
            }
        }
        return null;
    }

    public synchronized List<Account> list() {
        return new ArrayList<>(accounts);
    }

    /** Drops an account from the launcher's list. It does not touch anything the game already wrote. */
    public synchronized void remove(String xuid) {
        accounts.removeIf(a -> a.xuid != null && a.xuid.equals(xuid));
        if (xuid != null && xuid.equals(activeXuid)) {
            activeXuid = accounts.isEmpty() ? null : accounts.get(0).xuid;
        }
    }

    public File file() {
        return file;
    }
}
