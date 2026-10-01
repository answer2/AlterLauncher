package com.answer.launcher.auth;

import android.util.Log;

import net.lenni0451.commons.httpclient.HttpClient;
import net.raphimc.minecraftauth.bedrock.BedrockAuthManager;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Multi-account operations over {@link AccountStore}. The store already keeps every signed-in
 * account; what a switch needs on top of flipping the {@code active} pointer is rewriting the
 * game's XAL directory so the next launch boots as the chosen person — the same
 * {@link XalExporter#export} the interactive sign-in runs, just fed a stored chain instead of a
 * fresh one.
 *
 * <p>Everything here blocks on the network (export refreshes the token chain), so callers own a
 * worker thread. The identity root is resolved by the caller (shared vs per-installation is an
 * {@code IdentityStore} decision); this class only writes where it is pointed.
 */
public final class AccountManager {

    private static final String TAG = "AccountManager";

    private AccountManager() {
    }

    private static AccountStore store(File accountRoot) {
        return new AccountStore(new File(accountRoot, AccountStore.FILE_NAME));
    }

    public static List<AccountStore.Account> list(File accountRoot) {
        return store(accountRoot).list();
    }

    public static AccountStore.Account active(File accountRoot) {
        return store(accountRoot).active();
    }

    /**
     * Make {@code xuid} the active account and re-export its XAL into {@code accountRoot}, so the
     * game boots signed-in as them. Returns the activated account, or null when no account with
     * that id exists. Throws when the chain cannot be restored or exported — the active pointer is
     * only moved after a successful export, so a failure leaves the previous account in place.
     */
    public static AccountStore.Account activate(File accountRoot, String xuid,
                                                String gameVersion, String userAgent)
            throws IOException {
        AccountStore store = store(accountRoot);
        AccountStore.Account target = null;
        for (AccountStore.Account a : store.list()) {
            if (a.xuid != null && a.xuid.equals(xuid)) {
                target = a;
                break;
            }
        }
        if (target == null || target.auth == null) {
            return null;
        }
        HttpClient http = HostAuthManager.newHttpClient(userAgent);
        BedrockAuthManager manager = HostAuthManager.restore(http, gameVersion, target.auth);
        if (manager == null) {
            throw new IOException("cannot restore the stored sign-in: " + HostAuthManager.lastError());
        }
        // Export refreshes the chain internally; persist the rotated tokens back so the next switch
        // or validation does not re-spend a stale refresh.
        String localId = XalExporter.export(accountRoot, manager);
        Log.i(TAG, "activated account " + xuid + " (local " + localId + ")");
        target.auth = HostAuthManager.serialize(manager);
        target.updatedAt = System.currentTimeMillis();
        store.putActive(target);
        store.save();
        return target;
    }

    /**
     * Remove an account from the launcher's list. Does not touch the game's on-disk XAL — if the
     * removed account is the active one the caller should sign another in (or export the new
     * active) before launching, or the game keeps the last-written store. Returns whether an
     * account with that id was actually present.
     */
    public static boolean remove(File accountRoot, String xuid) throws IOException {
        AccountStore store = store(accountRoot);
        boolean present = false;
        for (AccountStore.Account a : store.list()) {
            if (a.xuid != null && a.xuid.equals(xuid)) {
                present = true;
                break;
            }
        }
        if (!present) {
            return false;
        }
        store.remove(xuid);
        store.save();
        return true;
    }
}
