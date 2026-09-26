package com.answer.launcher.auth;

import java.nio.charset.StandardCharsets;

/**
 * How the game's own XAL SDK names the files it keeps in its storage directory.
 *
 * <p>The directory looks like a pile of random 19- and 20-digit numbers:
 * {@code xal/1734634999945796391}, {@code xal/13687287496199607557}. They are not ids. XAL asks
 * its platform layer to store a value under a <em>key</em>, and the Android platform turns that
 * key into a file name by hashing it. The key grammar is visible in
 * {@code libminecraftpe.so} as the format strings the C++ layer passes down:
 *
 * <pre>
 *   Xal.%llu.%s.Default              Xal.%llu.%s.%s.User.%s     Xal.%llu.%s.Msa.%s
 *   Xal.%llu.%s.%s.T                 Xal.%s.%s.DeviceIdentity   Xal.%s.%s.D
 *   Xal.%s.Msa.Foci.1
 * </pre>
 *
 * <p>The hash is 64-bit FNV-1 with the usual basis and prime, rendered as an unsigned decimal.
 * That was confirmed the hard way: {@code name("Xal.1739947436.Production.Default")} equals
 * {@code 1734634999945796391}, which is the file on the device whose content is
 * {@code {"default":"07cc73f690e1e574"}} — exactly what a default-user pointer should be. One
 * 64-bit collision is not a coincidence. (The xor-then-multiply variant, FNV-1a, does not match;
 * the order is load-bearing.)
 *
 * <p>Two things fall out of that. The title id inside these keys is {@link #TITLE_ID_XAL} in
 * decimal, which is <em>not</em> the {@code ms-xal-0000000048183522} client id in the manifest —
 * the redirect scheme carries the Azure application id, the storage keys carry the XAL title id,
 * and conflating them writes files the game will never look for. And the account segment inside
 * the per-user keys is not the id itself but its base64url form ({@link #accountSegment}), so
 * hashing the raw id produces a name the game never reads: the device's
 * {@code MDdjYzczZjY5MGUxZTU3NA} is base64url of {@code 07cc73f690e1e574}.
 *
 * <p>Knowing the naming is what makes a host-side sign-in possible at all: the launcher can write
 * the same files the game reads, instead of having to reverse the token format from a directory of
 * numbers.
 */
public final class XalStore {

    /** The XAL title id, as it appears inside the storage keys. Bedrock Android, in decimal. */
    public static final long TITLE_ID_XAL = 1739947436L;

    /** The environment segment. Everything shipping uses Production; Test/Stage exist in the SDK. */
    public static final String ENVIRONMENT = "Production";

    /** The sandbox segment, which on retail builds is RETAIL. */
    public static final String SANDBOX = "RETAIL";

    /** The one key the platform stores without hashing it. */
    public static final String CLOCK_SKEW = "Skew";

    private XalStore() {
    }

    /** {@code Xal.<titleId>.<environment>.Default} — which user the title should boot as. */
    public static String defaultUserKey() {
        return "Xal." + TITLE_ID_XAL + "." + ENVIRONMENT + ".Default";
    }

    /** {@code Xal.<titleId>.<environment>.Msa.<webAccountId>} — the MSA refresh ticket store. */
    public static String msaKey(String webAccountId) {
        return "Xal." + TITLE_ID_XAL + "." + ENVIRONMENT + ".Msa." + webAccountId;
    }

    /**
     * The account segment as it appears inside the per-user keys: base64url of the
     * id with the padding stripped. {@code 07cc73f690e1e574} becomes
     * {@code MDdjYzczZjY5MGUxZTU3NA}, which is the segment on the device. Pass the
     * result to {@link #msaKey} or {@link #userKey} — hashing the raw id produces a
     * file name the game will never look for.
     */
    public static String accountSegment(String accountId) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(accountId.getBytes(StandardCharsets.UTF_8));
    }

    /** {@code Xal.<environment>.<sandbox>.D} — the cached device token. */
    public static String deviceTokenKey() {
        return "Xal." + ENVIRONMENT + "." + SANDBOX + ".D";
    }

    /** {@code Xal.<titleId>.<environment>.<sandbox>.T} — the cached title token. */
    public static String titleTokenKey() {
        return "Xal." + TITLE_ID_XAL + "." + ENVIRONMENT + "." + SANDBOX + ".T";
    }

    /**
     * {@code Xal.<titleId>.<environment>.<sandbox>.User.<webAccountId>} — the Xbox token
     * envelopes: the XSTS tokens for xboxlive.com, PlayFab, Realms, and the Utoken.
     */
    public static String userKey(String webAccountId) {
        return userKey(SANDBOX, webAccountId);
    }

    public static String userKey(String sandbox, String webAccountId) {
        return "Xal." + TITLE_ID_XAL + "." + ENVIRONMENT + "." + sandbox + ".User." + webAccountId;
    }

    /** {@code Xal.<environment>.<sandbox>.DeviceIdentity} — the device id and its key reference. */
    public static String deviceIdentityKey() {
        return deviceIdentityKey(SANDBOX);
    }

    public static String deviceIdentityKey(String sandbox) {
        return "Xal." + ENVIRONMENT + "." + sandbox + ".DeviceIdentity";
    }

    /** {@code Xal.<environment>.Msa.Foci.1} — the family-of-ids cache that makes re-login silent. */
    public static String fociKey() {
        return "Xal." + ENVIRONMENT + ".Msa.Foci.1";
    }

    /** The file name XAL will look for when it asks for {@code key}. */
    public static String fileName(String key) {
        return Long.toUnsignedString(fnv1a64(key.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * FNV-1a, 64 bit. Offset basis {@code 2^64 - 2^24 + 435} and prime {@code 2^40 + 435}, the
     * standard parameters — the multiply-then-xor order is what the device files matched.
     */
    public static long fnv1a64(byte[] bytes) {
        long hash = -3750763034362895579L; // 0xCBF29CE484222325
        for (byte b : bytes) {
            hash *= 0x100000001B3L;
            hash ^= (b & 0xFF);
        }
        return hash;
    }
}
