package com.answer.launcher.auth;

import net.lenni0451.commons.httpclient.HttpClient;
import net.raphimc.minecraftauth.msa.model.MsaApplicationConfig;
import net.raphimc.minecraftauth.msa.model.MsaDeviceCode;
import net.raphimc.minecraftauth.msa.model.MsaToken;
import net.raphimc.minecraftauth.msa.service.impl.DeviceCodeMsaAuthService;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import javax.net.ssl.SSLException;

/**
 * The device-code poll, with the part Microsoft's own spec describes and the library omits:
 * a network that drops is retried instead of ending the sign-in.
 *
 * <p>{@code login.live.com} is reached over whatever the player's phone is connected to, and
 * on many networks a long-lived keep-alive polling request simply gets reset — the observed
 * failure is {@code java.net.SocketException: Software caused connection abort} while reading
 * the response headers, several minutes into a poll that was otherwise going fine. The code
 * the player typed is still valid at that moment and the sign-in is one request away from
 * succeeding, so throwing it away is the wrong trade.
 *
 * <p>Only connection-level faults are retried. A response that says "no" — an expired code, a
 * rejected grant — comes back as a different exception and propagates untouched, because
 * repeating those only delays the message the player needs to see.
 *
 * <p>The retry budget is the code's own lifetime rather than a fixed count, with a capped
 * exponential backoff, matching the pacing the device-code flow asks for (and the shape
 * LeviLaunchroid uses for the same problem).
 */
public final class RetryingDeviceCodeMsaService extends DeviceCodeMsaAuthService {

    /** First wait after a dropped connection; doubles from here. */
    private static final long RETRY_DELAY_MS = 1_000L;
    /** Never sleep longer than this between attempts, however long the backoff has run. */
    private static final long MAX_RETRY_DELAY_MS = 10_000L;
    /**
     * Consecutive connection failures tolerated before the sign-in gives up. The poll inside
     * {@code super.getToken} is a black box that may already have succeeded several times
     * before the link drops, so this is a blunt bound on how long a dead network is chased;
     * the code's own expiry bounds it from the other side.
     */
    private static final int MAX_CONSECUTIVE_FAILURES = 6;

    public RetryingDeviceCodeMsaService(HttpClient httpClient, MsaApplicationConfig config,
                                        Consumer<MsaDeviceCode> codeSink, int lifetimeMs) {
        super(httpClient, config, codeSink, lifetimeMs);
    }

    @Override
    public MsaToken getToken(MsaDeviceCode deviceCode)
            throws IOException, InterruptedException, TimeoutException {
        long deadline = deviceCode.getExpireTimeMs();
        long delay = RETRY_DELAY_MS;
        int consecutiveFailures = 0;
        while (true) {
            try {
                MsaToken token = super.getToken(deviceCode);
                return token;
            } catch (IOException connection) {
                if (!isRetryable(connection) || ++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES
                        || System.currentTimeMillis() + delay >= deadline) {
                    throw connection;
                }
                Thread.sleep(delay);
                delay = Math.min(delay * 2, MAX_RETRY_DELAY_MS);
            }
        }
    }

    /** True for "the wire failed", false for "the server answered". */
    private static boolean isRetryable(IOException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketException
                    || cause instanceof SocketTimeoutException
                    || cause instanceof UnknownHostException
                    || cause instanceof SSLException
                    || cause instanceof InterruptedIOException) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }
}
