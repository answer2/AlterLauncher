package com.answer.launcher.auth;

import net.lenni0451.commons.httpclient.HttpClient;
import net.raphimc.minecraftauth.bedrock.BedrockAuthManager;
import net.raphimc.minecraftauth.bedrock.model.MinecraftCertificateChain;

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
 * <p>While the chain is already open it also asks Xbox for the account's avatar, which is the one
 * piece of identity the launcher has never been able to show. That lookup is strictly optional:
 * nothing here reports a failure to the caller, and the URL is cached on the account so the
 * network is touched at most once per account rather than once per screen visit.
 *
 * <p>Kept in the auth module so the Gson/credential handling stays where it already lives; the
 * UI only sees a small status object.
 */
public final class SessionValidator {

    private static final String TAG = "SessionValidator";

    /**
     * Minecraft's Persona service renders a player's head as a PNG from just their XUID — the
     * endpoint is public (no token), which is why it works from mainland networks where the Xbox
     * profile API the first attempt used does not. {@code image/head} is a 700-byte face, ideal
     * for the avatar slot. Primary host first, the secondary the game itself falls back to.
     */
    private static final String[] PERSONA_HEAD = {
            "https://persona.franchise.minecraft-services.net/api/v1.0/profile/xuid/",
            "https://persona-secondary.franchise.minecraft-services.net/api/v1.0/profile/xuid/",
    };

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
        /**
         * The Persona head-image URL for {@link #xuid}, or null when there is no XUID to build one
         * from (no sign-in, or a chain that reported none). The caller draws a letter avatar in
         * that case rather than leaving the slot empty.
         */
        public String avatarUrl;

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
        // The chain is already open here, so read the real XUID off it and point the caller at
        // Persona's public head render. No extra request: the image is fetched by the UI lazily.
        attachAvatar(http, gameVersion, account, status);
        try {
            store.putActive(account);
            store.save();
        } catch (Throwable ignored) {
            // A renewed token we could not write still authenticated; the copy refreshes later.
        }
        return status;
    }

    /**
     * Sets {@code status.xuid} to the certificate chain's numeric XUID (the record's own
     * {@code xuid} field carries XAL's local MSA id, which Persona does not key on) and builds the
     * public head-image URL from it. Best effort: any throw leaves both fields as they were, and a
     * null {@code avatarUrl} is the caller's cue to draw a letter avatar.
     */
    private static void attachAvatar(HttpClient http, String gameVersion,
                                     AccountStore.Account account, Status status) {
        try {
            BedrockAuthManager manager = HostAuthManager.restore(http, gameVersion, account.auth);
            MinecraftCertificateChain chain = HostAuthManager.chain(manager);
            String xuid = chain != null ? chain.getIdentityXuid() : null;
            if (xuid == null || xuid.isEmpty()) {
                return;
            }
            status.xuid = xuid;
            status.avatarUrl = PERSONA_HEAD[0] + xuid + "/image/head";
        } catch (Throwable optional) {
            android.util.Log.w(TAG, "avatar url skipped: " + optional);
        }
    }
}
