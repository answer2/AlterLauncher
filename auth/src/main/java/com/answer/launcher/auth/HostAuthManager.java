package com.answer.launcher.auth;

import net.lenni0451.commons.httpclient.HttpClient;
import net.lenni0451.commons.httpclient.executor.ExecutorType;
import net.raphimc.minecraftauth.bedrock.BedrockAuthManager;
import net.raphimc.minecraftauth.msa.data.MsaConstants;
import net.raphimc.minecraftauth.msa.model.MsaApplicationConfig;
import net.raphimc.minecraftauth.msa.model.MsaDeviceCode;
// The library's own DeviceCodeMsaAuthService is used through RetryingDeviceCodeMsaService.
import net.raphimc.minecraftauth.util.CryptUtil;
import net.raphimc.minecraftauth.bedrock.model.MinecraftCertificateChain;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Microsoft / Xbox sign-in executed by the launcher process instead of the game (M3).
 *
 * <p>The guest used to own the whole sign-in: the title screen drew the 登录 button, the XAL SDK
 * inside {@code :game} ran the browser flow, and the tokens landed in {@code xal/}. That works but
 * it means the launcher cannot tell you who you are until a runtime has been assembled, it cannot
 * offer an account before the first launch, and signing out means killing the game. Running the
 * OAuth ourselves in the host process fixes all three, and it is what the 登录 button on the home
 * tab is supposed to mean.
 *
 * <p>This is possible because the token material XAL writes is <em>not</em> encrypted with a
 * Mojang secret. The envelope JSON files are cleartext; the JWE inside each one is the XSTS token
 * value, which is exactly what an Xbox STS hands out to any client holding a title id. The
 * <em>title</em> id here is the public Bedrock Android one, {@link #TITLE_ID}, which is the same
 * client id the launcher manifest already declares for the {@code ms-xal-…} callback scheme, so we
 * are asking for tokens the store app itself is entitled to.
 *
 * <p>Everything here blocks on the network, including {@link #signInDeviceCode}: the device-code
 * flow waits for the user to finish in a browser. Callers own the thread.
 *
 * <p>Backed by <a href="https://github.com/RaphiMC/MinecraftAuth">MinecraftAuth</a> (LGPL-3.0),
 * reached through {@link #newHttpClient()} rather than the library's own factory: the default
 * {@code AUTO} executor probes for {@code java.net.http.HttpClient}, which Android does not have,
 * and the fallback is decided by a {@code catch (Throwable)} we should not rely on.
 */
public final class HostAuthManager {

    /**
     * The Bedrock Android title id. MinecraftAuth calls it a title id, the MSAL world calls it a
     * client id, and the game's own manifest spells it as the {@code ms-xal-0000000048183522}
     * scheme — one identity, three names.
     */
    public static final String TITLE_ID = MsaConstants.BEDROCK_ANDROID_TITLE_ID;

    /** The scope the game asks for; anything narrower gets us a token the title will not accept. */
    public static final String SCOPE = MsaConstants.SCOPE_TITLE_AUTH;

    /** How long to wait for the user to enter the code: the code's own lifetime, fifteen minutes. */
    public static final int CODE_LIFETIME_MS = 15 * 60 * 1000;

    private HostAuthManager() {
    }

    /** An HTTP client that works on Android: URL connections, no {@code java.net.http} probing. */
    public static HttpClient newHttpClient(String userAgent) {
        HttpClient http = new HttpClient(ExecutorType.URL_CONNECTION);
        http.setHeader("User-Agent", userAgent);
        return http;
    }

    /**
     * The interactive sign-in: Microsoft shows a short code, the user types it at
     * {@code microsoft.com/link}, and this returns once they are done.
     *
     * @param codeSink called as soon as the code exists, so the UI can draw it while the call is
     *                 still blocked waiting for the user. Invoked on the caller's thread.
     * @throws IOException          network or a rejected token response
     * @throws TimeoutException     the code expired before anyone used it
     * @throws InterruptedException the caller gave up
     */
    public static BedrockAuthManager signInDeviceCode(HttpClient http, String gameVersion,
                                                     Consumer<MsaDeviceCode> codeSink)
            throws IOException, TimeoutException, InterruptedException {
        MsaApplicationConfig config = new MsaApplicationConfig(TITLE_ID, SCOPE);
        return BedrockAuthManager.create(http, gameVersion)
                .msaApplicationConfig(config)
                // The device key pair is what the certificate chain is signed with. A fresh one
                // per sign-in is fine — the game does the same on a new install — but it must be
                // persisted with the account (see AccountStore) or every refresh looks like a new
                // device to Xbox and the console-privacy checks start failing.
                .deviceKeyPair(CryptUtil.generateEcdsa256KeyPair())
                .deviceId(UUID.randomUUID())
                // Fifteen minutes, which is how long Microsoft's own code lives. The library's
                // default is five, and five minutes is not enough for someone to notice the code,
                // unlock their phone, open a browser and type it — the sign-in then dies on a
                // perfectly good code.
                .login((h, c) -> new RetryingDeviceCodeMsaService(h, c, codeSink::accept,
                        CODE_LIFETIME_MS));
    }

    /** Rebuilds a manager from {@link #serialize}, without touching the network. */
    public static BedrockAuthManager restore(HttpClient http, String gameVersion, JsonObject state) {
        return BedrockAuthManager.fromJson(http, gameVersion, state);
    }

    /** The persisted form of a signed-in manager: everything needed to act as that account. */
    public static JsonObject serialize(BedrockAuthManager manager) {
        return BedrockAuthManager.toJson(manager);
    }

    /**
     * Silent renewal. Every token in the chain has its own expiry and MinecraftAuth refreshes each
     * lazily, so "renew if needed" is just "walk the chain to the end and ask for it".
     *
     * @return true when the chain is usable now
     */
    public static boolean refresh(HttpClient http, String gameVersion, JsonObject state) {
        try {
            BedrockAuthManager manager = restore(http, gameVersion, state);
            manager.getMinecraftCertificateChain().getUpToDate();
            return true;
        } catch (Throwable t) {
            // A refresh that fails for the wrong reason looks identical to one that fails because
            // the account is gone, so the caller decides whether to retry; we only report.
            LastError.set(t);
            return false;
        }
    }

    /** The state to persist after a successful refresh, or null if the chain is unusable. */
    public static JsonObject refreshAndSerialize(HttpClient http, String gameVersion, JsonObject state) {
        try {
            BedrockAuthManager manager = restore(http, gameVersion, state);
            manager.getMinecraftCertificateChain().getUpToDate();
            return serialize(manager);
        } catch (Throwable t) {
            LastError.set(t);
            return null;
        }
    }

    /** Who we are, as the certificate chain says. Null when the chain was never fetched. */
    public static MinecraftCertificateChain chain(BedrockAuthManager manager) {
        try {
            return manager.getMinecraftCertificateChain().getUpToDate();
        } catch (Throwable t) {
            LastError.set(t);
            return null;
        }
    }

    /** The Xbox user hash, which is what the game keys its per-account state on. */
    public static String userHash(BedrockAuthManager manager) {
        try {
            return manager.getXblUserToken().getUpToDate().getUserHash();
        } catch (Throwable t) {
            LastError.set(t);
            return null;
        }
    }

    /** The signed-in account, or null if the chain could not be fetched. */
    public static AccountStore.Account toAccount(BedrockAuthManager manager) {
        MinecraftCertificateChain c = chain(manager);
        if (c == null) {
            return null;
        }
        return new AccountStore.Account(c.getIdentityXuid(), c.getIdentityDisplayName(),
                serialize(manager));
    }

    /** The most specific message a failed call produced, for the UI to show. */
    public static String lastError() {
        return LastError.text();
    }

    /** Why the last refresh or chain fetch failed; {@link #lastError()} reads it. */
    private static final class LastError {
        private static volatile String message;

        static void set(Throwable t) {
            Throwable root = t;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            String m = root.getClass().getSimpleName();
            if (root.getMessage() != null) {
                m += ": " + root.getMessage();
            }
            message = m;
        }

        static String text() {
            return message == null ? "unknown" : message;
        }
    }
}
