package com.answer.launcher.auth;

import net.lenni0451.commons.httpclient.HttpClient;

import com.google.gson.JsonObject;

import java.io.File;

/**
 * Answers one question the launcher currently never asks: is the sign-in we wrote to disk
 * still good?
 *
 * <p>{@code IdentityStore} can only see the {@code xal/} file sizes, so "signed in" today means
 * "the envelopes exist" — it says nothing about whether Xbox still accepts the refresh token,
 * which is exactly the state that leaves a player on a title screen that silently boots signed
 * out. The real check already exists in {@link HostAuthManager#refreshAndSerialize}: walk the
 * token chain to the end and force each link up to date. This class is the missing caller: it
 * reads the persisted manager, runs that check off the main thread, and — because refresh
 * rotates tokens — writes the renewed state back so the on-disk copy is never left stale.
 *
 * <p>Kept in the auth module so the Gson/credential handling stays where it already lives; the
 * UI only sees a small status object.
 */
public final class SessionValidator {

    private SessionValidator() {
    }

    /** What the launcher can report about the stored sign-in after a check. */
    public static final class Status {
        /** An account was persisted here at all. */
        public boolean hasAccount;
        /** The stored chain still authenticates; false when it must be re-signed-in. */
        public boolean valid;
        public String gamertag;
        public String xuid;

        static Status none() {
            return new Status();
        }
    }

    /**
     * Validates the active account under {@code accountRoot} against Xbox Live. Blocking — call
     * it off the main thread. A null/blank {@code gameVersion} still runs the check; the version
     * only labels the request the same way the game's does.
     */
    public static Status check(File accountRoot, String gameVersion, String userAgent) {
        if (accountRoot == null) {
            return Status.none();
        }
        AccountStore store = new AccountStore(new File(accountRoot, AccountStore.FILE_NAME));
        AccountStore.Account account = store.active();
        Status status = new Status();
        if (account == null || account.auth == null) {
            return status; // nothing persisted to validate
        }
        status.hasAccount = true;
        status.gamertag = account.gamertag;
        status.xuid = account.xuid;

        HttpClient http = HostAuthManager.newHttpClient(userAgent);
        JsonObject renewed = HostAuthManager.refreshAndSerialize(http, gameVersion, account.auth);
        if (renewed == null) {
            return status; // chain refused: the caller shows "log in again"
        }
        status.valid = true;
        // Persist the rotated chain so the next boot does not re-spend the same refresh and the
        // game never resumes from a token the server has already advanced past.
        account.auth = renewed;
        account.updatedAt = System.currentTimeMillis();
        try {
            store.putActive(account);
            store.save();
        } catch (Throwable ignored) {
            // A renewed token we could not write still authenticated; the copy refreshes later.
        }
        return status;
    }
}
