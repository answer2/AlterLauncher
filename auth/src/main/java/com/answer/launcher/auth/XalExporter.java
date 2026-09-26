package com.answer.launcher.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.answer.launcher.auth.XalStore;

import net.raphimc.minecraftauth.bedrock.BedrockAuthManager;
import net.raphimc.minecraftauth.bedrock.model.MinecraftCertificateChain;
import net.raphimc.minecraftauth.msa.model.MsaToken;
import net.raphimc.minecraftauth.xbl.model.XblDeviceToken;
import net.raphimc.minecraftauth.xbl.model.XblTitleToken;
import net.raphimc.minecraftauth.xbl.model.XblUserToken;
import net.raphimc.minecraftauth.xbl.model.XblUserProfile;
import net.raphimc.minecraftauth.xbl.model.XblXstsToken;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.util.Base64;
import java.util.Map;

/**
 * Writes the sign-in state that Minecraft's own XAL layer reads off disk, so the
 * game boots already logged in and the player never sees a browser.
 *
 * <p>The layout below is not a guess and not Levi's reverse-engineering — it was
 * read out of a running game on the device. XAL names every file with a 64-bit
 * FNV-1 hash of a key built from the templates in {@link XalStore}, so the names
 * follow from the ids and nothing else has to be discovered at runtime.
 *
 * <pre>
 *   xal/&lt;fnv("Xal.&lt;tid&gt;.Production.Default")&gt;               {"default":"&lt;localId&gt;"}
 *   xal/&lt;fnv("Xal.Production.RETAIL.DeviceIdentity")&gt;       {"Id":"{guid}","Key":"Serialized to SharedPreferences"}
 *   xal/&lt;fnv("Xal.&lt;tid&gt;.Production.Msa.&lt;b64(localId)&gt;")&gt;     {"user_id","refresh_token","foci","access_tokens":[…]}
 *   xal/&lt;fnv("Xal.&lt;tid&gt;.Production.RETAIL.User.&lt;b64(id)&gt;")&gt;  {"deviceId","tokens":[4 envelopes]}
 *   xal/&lt;fnv("Xal.Production.RETAIL.D")&gt;                   {"deviceId","token":{Dtoken envelope}}
 *   xal/&lt;fnv("Xal.&lt;tid&gt;.Production.RETAIL.T")&gt;            {"deviceId","tokens":[Ttoken envelope]}
 *   shared_prefs/com.microsoft.xal.crypto.xml                private / public / id
 * </pre>
 *
 * <p>The device and title tokens ({@code …RETAIL.D} and {@code …RETAIL.T}) are
 * written too. They were left out first, on the theory that XAL mints them from
 * the keypair — and it does, which is how the export was proven to work: with
 * only the four files above on disk, the game signed in and then wrote the two
 * token caches and its clock skew itself, under the names this class chose. But
 * minting them costs the first launch a tap on the sign-in button, because the
 * silent restore has no device token to resume from. MinecraftAuth holds real
 * ones, so there is no reason to make the player wait for it.
 *
 * <p>Everything here takes an identity root rather than a {@code Context},
 * because the root depends on the user's choice in the settings page: the
 * shared store when installations share a login, the per-installation
 * directory when they do not. The caller resolves that; this class just
 * writes.
 */
public final class XalExporter {

    /** The relying parties, in the order the game itself stores them. */
    private static final String RP_XBOX_LIVE = "http://xboxlive.com";
    private static final String RP_PLAYFAB = "https://b980a380.minecraft.playfabapi.com/";
    private static final String RP_REALMS = "https://pocket.realms.minecraft.net/";
    private static final String RP_AUTH = "http://auth.xboxlive.com";

    /**
     * Scope string as XAL writes it. {@code MsaConstants.SCOPE_TITLE_AUTH} is
     * the same scope in upper case; the game lower-cases it before storing, and
     * matching the stored form avoids a comparison failing on case.
     */
    private static final String SCOPE_STORED = "service::user.auth.xboxlive.com::mbi_ssl";

    /** Prefs file XAL's crypto layer uses for the device keypair. */
    public static final String CRYPTO_PREFS = "com.microsoft.xal.crypto";

    /**
     * Lifetime of each token type, used only to reconstruct {@code IssueInstant}
     * — measured from the game's own files (16 h user XSTS, 4 d user token,
     * 14 d device and title tokens). XAL validates {@code NotAfter}; the issue
     * stamp is informational, but writing a plausible one keeps the file
     * indistinguishable from one the game produced.
     */
    private static final long LIFE_XSTS_MS = 16L * 3600_000L;
    private static final long LIFE_UTOKEN_MS = 4L * 24 * 3600_000L;
    private static final long LIFE_XBL_MS = 14L * 24 * 3600_000L;
    /** The XBL device id form: 16 hex digits, as it appears in {@code xdi.did}. */
    private static final java.util.regex.Pattern DEVICE_ID_PATTERN =
            java.util.regex.Pattern.compile("[0-9A-Fa-f]{16}");
    /** A title id is a decimal integer, as it appears in {@code xti.tid}. */
    private static final java.util.regex.Pattern TITLE_ID_PATTERN =
            java.util.regex.Pattern.compile("[0-9]{1,19}");

    private XalExporter() {
    }

    /**
     * Exports the sign-in state held by {@code manager} into {@code identityRoot}.
     *
     * @param identityRoot the directory that holds {@code xal/} and
     *                     {@code shared_prefs/} for the target installation
     * @return the local id the state was written under
     */
    public static String export(File identityRoot, BedrockAuthManager manager) throws IOException {
        MsaToken msa = manager.getMsaToken().getUpToDate();
        XblXstsToken xboxLive = manager.getXboxLiveXstsToken().getUpToDate();
        XblXstsToken playFab = manager.getPlayFabXstsToken().getUpToDate();
        XblXstsToken realms = manager.getRealmsXstsToken().getUpToDate();
        XblUserToken userToken = manager.getXblUserToken().getUpToDate();
        MinecraftCertificateChain chain = manager.getMinecraftCertificateChain().getUpToDate();

        // The local id is the account's handle inside XAL's store. It comes from
        // the Xbox profile, exactly as the game's own sign-in flow records it;
        // the certificate chain's xuid is the fallback Levi uses when the
        // profile request is refused.
        String localId = null;
        String gamertag = chain.getIdentityDisplayName();
        String xuid = chain.getIdentityXuid();
        String agg = "";
        XblUserProfile profile = null;
        try {
            profile = manager.getXboxUserProfile().getUpToDate();
        } catch (Throwable ignored) {
            // A refused profile request costs us the aggregate-age claim, not
            // the sign-in. Everything else here is already in hand.
        }
        if (profile != null) {
            if (profile.getId() != null && !profile.getId().isEmpty()) {
                localId = profile.getId();
            }
            Map<String, String> settings = profile.getSettings();
            if (settings != null) {
                String tag = settings.get("Gamertag");
                if (tag != null && !tag.isEmpty()) {
                    gamertag = tag;
                }
                String xuidSetting = settings.get("Xuid");
                if (xuidSetting != null && !xuidSetting.isEmpty()) {
                    xuid = xuidSetting;
                }
                String a = settings.get("Agg");
                if (a != null) {
                    agg = a;
                }
            }
        }
        if (localId == null || localId.isEmpty()) {
            localId = xuid;
        }
        if (localId == null || localId.isEmpty()) {
            throw new IOException("no account id to store the sign-in under");
        }

        String deviceId = "{" + manager.getDeviceId() + "}";
        // Inside the per-user keys the id is base64url-encoded; inside the file
        // contents it is the raw id. Getting these two mixed up produces files
        // that read perfectly and are never looked for.
        String segment = XalStore.accountSegment(localId);
        File xal = new File(identityRoot, "xal");
        if (!xal.isDirectory() && !xal.mkdirs()) {
            throw new IOException("cannot create " + xal);
        }

        write(new File(xal, XalStore.fileName(XalStore.defaultUserKey())),
                toJson(pick(new JsonObject(), "default", localId)));

        JsonObject deviceIdentity = new JsonObject();
        deviceIdentity.addProperty("Id", deviceId);
        // Not a placeholder: this is the literal string the game writes, and its
        // crypto layer reads it back as the pointer to the prefs entry below.
        deviceIdentity.addProperty("Key", "Serialized to SharedPreferences");
        write(new File(xal, XalStore.fileName(XalStore.deviceIdentityKey())), toJson(deviceIdentity));

        write(new File(xal, XalStore.fileName(XalStore.msaKey(segment))),
                toJson(msaStore(localId, msa)));

        JsonArray envelopes = new JsonArray();
        envelopes.add(envelope(localId, true, "Xtoken", RP_XBOX_LIVE,
                xboxLive.getToken(), xboxLive.getExpireTimeMs(), LIFE_XSTS_MS,
                claims(xboxLive.getUserHash(), gamertag, xuid, agg, true)));
        envelopes.add(envelope(localId, false, "Xtoken", RP_PLAYFAB,
                playFab.getToken(), playFab.getExpireTimeMs(), LIFE_XSTS_MS,
                claims(playFab.getUserHash(), "", "0", "", false)));
        envelopes.add(envelope(localId, false, "Xtoken", RP_REALMS,
                realms.getToken(), realms.getExpireTimeMs(), LIFE_XSTS_MS,
                claims(realms.getUserHash(), "", "0", "", false)));
        envelopes.add(envelope(localId, false, "Utoken", RP_AUTH,
                userToken.getToken(), userToken.getExpireTimeMs(), LIFE_UTOKEN_MS,
                claims(userToken.getUserHash(), "", "0", "", false)));

        JsonObject userStore = new JsonObject();
        userStore.addProperty("deviceId", deviceId);
        userStore.add("tokens", envelopes);
        write(new File(xal, XalStore.fileName(XalStore.userKey(segment))), toJson(userStore));

        // The device and title caches. Same envelope shape as the user ones, but
        // not scoped to an account, and the claims name the device or the title
        // instead of a player. Written because XAL's silent restore looks for
        // them: without them the first launch falls back to an interactive
        // sign-in — which does succeed, and then writes them itself.
        XblDeviceToken device = null;
        try {
            device = manager.getXblDeviceToken().getUpToDate();
        } catch (Throwable ignored) {
            // A missing device token costs the silent first boot, not the login.
        }
        if (device != null && notEmpty(device.getToken())
                && device.getDeviceId() != null && DEVICE_ID_PATTERN.matcher(device.getDeviceId()).matches()) {
            // The claim XAL checks is the 64-bit hex device id XBL puts in the
            // token, not the GUID we named the keypair by. If the library ever
            // hands us something else, writing it would poison the device
            // identity — so the shape is checked, and the file left out rather
            // than guessed at.
            String did = device.getDeviceId();
            JsonObject claims = claims("", "", "0", "", false);
            JsonObject xdi = new JsonObject();
            xdi.addProperty("did", did);
            claims.add("xdi", xdi);
            JsonObject store = new JsonObject();
            store.addProperty("deviceId", deviceId);
            store.add("token", envelope(null, false, "Dtoken", RP_AUTH, device.getToken(),
                    device.getExpireTimeMs(), LIFE_XBL_MS, claims));
            write(new File(xal, XalStore.fileName(XalStore.deviceTokenKey())), toJson(store));
        }

        XblTitleToken title = null;
        try {
            title = manager.getXblTitleToken().getUpToDate();
        } catch (Throwable ignored) {
        }
        if (title != null && notEmpty(title.getToken())
                && title.getTitleId() != null && TITLE_ID_PATTERN.matcher(title.getTitleId()).matches()) {
            JsonObject claims = claims("", "", "0", "", false);
            JsonObject xti = new JsonObject();
            // XBL echoes the title the token was minted for, so this stays
            // consistent with the token even if a future build asks for another.
            xti.addProperty("tid", title.getTitleId());
            claims.add("xti", xti);
            JsonArray titleTokens = new JsonArray();
            titleTokens.add(envelope(null, false, "Ttoken", RP_AUTH, title.getToken(),
                    title.getExpireTimeMs(), LIFE_XBL_MS, claims));
            JsonObject store = new JsonObject();
            store.addProperty("deviceId", deviceId);
            store.add("tokens", titleTokens);
            write(new File(xal, XalStore.fileName(XalStore.titleTokenKey())), toJson(store));
        }

        writeCryptoPrefs(new File(identityRoot, "shared_prefs"), deviceId, manager.getDeviceKeyPair());

        return localId;
    }

    /** The MSA ticket store: the refresh token is what makes the login survive. */
    private static JsonObject msaStore(String localId, MsaToken msa) {
        JsonObject ticket = new JsonObject();
        ticket.addProperty("access_token", msa.getAccessToken());
        ticket.addProperty("xal_expires", format(msa.getExpireTimeMs()));
        ticket.addProperty("scopes", SCOPE_STORED);
        JsonArray tickets = new JsonArray();
        tickets.add(ticket);

        JsonObject store = new JsonObject();
        store.addProperty("user_id", localId);
        store.addProperty("refresh_token", msa.getRefreshToken());
        // "foci" is the family-of-ids client id; the game leaves it empty when
        // the sign-in came through the title flow rather than a browser.
        store.addProperty("foci", "");
        store.add("access_tokens", tickets);
        return store;
    }

    /**
     * One cached token envelope, in the field order the game writes.
     *
     * <p>{@code localId} is null for the device and title caches: those are not
     * scoped to an account, and the game's own files really do omit the field —
     * adding it would be the one visible difference between our store and its.
     */
    private static JsonObject envelope(String localId, boolean displayClaims, String identityType,
                                       String relyingParty, String token, long notAfterMs,
                                       long lifetimeMs, JsonObject claims) {
        JsonObject body = new JsonObject();
        body.addProperty("Token", token);
        body.addProperty("NotAfter", format(notAfterMs));
        body.addProperty("IssueInstant", format(notAfterMs - lifetimeMs));
        body.addProperty("ClientAttested", false);
        body.add("DisplayClaims", claims);

        JsonObject envelope = new JsonObject();
        if (localId != null) {
            envelope.addProperty("MsaUserId", localId);
        }
        envelope.addProperty("HasSignInDisplayClaims", displayClaims);
        envelope.addProperty("IdentityType", identityType);
        envelope.addProperty("Environment", XalStore.ENVIRONMENT);
        envelope.addProperty("Sandbox", XalStore.SANDBOX);
        envelope.addProperty("TokenType", "JWT");
        envelope.addProperty("RelyingParty", relyingParty);
        envelope.addProperty("SubRelyingParty", "");
        envelope.add("TokenData", body);
        return envelope;
    }

    /**
     * The display-claims block. Only the primary Xbox Live envelope carries the
     * real gamertag and xuid; the game stores the others with empty claims and
     * a zero xuid, which is what they are — service tickets, not identities.
     *
     * <p>{@code prv} (the privilege list) is left empty. The game's own file has
     * a long string there, but it is a claim inside the token itself: XAL
     * re-reads privileges from the service, and inventing a list would be both
     * wrong and the sort of thing a title check could reject.
     */
    private static JsonObject claims(String userHash, String gamertag, String xuid,
                                     String agg, boolean full) {
        JsonObject xui = new JsonObject();
        xui.addProperty("uhs", userHash == null ? "" : userHash);
        xui.addProperty("gtg", full ? nz(gamertag) : "");
        xui.addProperty("mgt", full ? nz(gamertag) : "");
        xui.addProperty("mgs", "");
        xui.addProperty("umg", full ? nz(gamertag) : "");
        xui.addProperty("xid", full ? nz(xuid) : "0");
        xui.addProperty("agg", full ? nz(agg) : "");
        xui.addProperty("prv", "");
        xui.addProperty("usr", "");
        xui.addProperty("uer", "");
        xui.addProperty("utr", "");
        xui.addProperty("ugc", "");
        JsonArray xuiList = new JsonArray();
        xuiList.add(xui);
        JsonObject claims = new JsonObject();
        claims.add("xui", xuiList);
        return claims;
    }

    /**
     * The device keypair, in the file XAL's crypto event handler reads. Written
     * by hand rather than through {@code SharedPreferences} because the target
     * is another installation's data directory, not this process's own.
     */
    private static void writeCryptoPrefs(File prefsDir, String deviceId, KeyPair keyPair)
            throws IOException {
        if (!prefsDir.isDirectory() && !prefsDir.mkdirs()) {
            throw new IOException("cannot create " + prefsDir);
        }
        File file = new File(prefsDir, CRYPTO_PREFS + ".xml");
        String priv = encode(keyPair.getPrivate().getEncoded());
        String pub = encode(keyPair.getPublic().getEncoded());

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n");
        // Preserve any other entry the file already holds: this prefs name is
        // XAL's, and dropping a key we did not write is how a working install
        // breaks in a way nobody can reproduce.
        Map<String, String> existing = readStringPrefs(file);
        for (Map.Entry<String, String> entry : existing.entrySet()) {
            String key = entry.getKey();
            if ("private".equals(key) || "public".equals(key) || "id".equals(key)) {
                continue;
            }
            xml.append("    <string name=\"").append(key).append("\">")
                    .append(escape(entry.getValue())).append("</string>\n");
        }
        xml.append("    <string name=\"private\">").append(priv).append("</string>\n");
        xml.append("    <string name=\"public\">").append(pub).append("</string>\n");
        xml.append("    <string name=\"id\">").append(escape(deviceId)).append("</string>\n");
        xml.append("</map>\n");
        atomicWrite(file, xml.toString());
    }

    /** Minimal reader for the {@code <string>} entries of a prefs file. */
    private static java.util.LinkedHashMap<String, String> readStringPrefs(File file) {
        java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>();
        if (!file.isFile()) {
            return out;
        }
        try {
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            int at = 0;
            while ((at = text.indexOf("<string name=\"", at)) >= 0) {
                int nameStart = at + "<string name=\"".length();
                int nameEnd = text.indexOf("\">", nameStart);
                int valueEnd = nameEnd < 0 ? -1 : text.indexOf("</string>", nameEnd);
                if (nameEnd < 0 || valueEnd < 0) {
                    break;
                }
                out.put(text.substring(nameStart, nameEnd),
                        unescape(text.substring(nameEnd + 2, valueEnd)));
                at = valueEnd;
            }
        } catch (IOException ignored) {
            // A prefs file we cannot read is treated as absent; the three keys
            // we care about are written unconditionally afterwards.
        }
        return out;
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String unescape(String value) {
        return value.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }

    /** XAL's date form: seven fractional digits, UTC, literal Z. */
    private static String format(long epochMs) {
        java.text.SimpleDateFormat fmt =
                new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        fmt.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        String base = fmt.format(new java.util.Date(epochMs));
        // Seven fractional digits, as the game writes them: SimpleDateFormat tops
        // out at milliseconds, so the remaining four are padding.
        return base.substring(0, base.length() - 1) + "0000Z";
    }

    /** Base64 URL-safe, no padding — the alphabet XAL writes the keys in. */
    private static String encode(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private static JsonObject pick(JsonObject target, String key, String value) {
        target.addProperty(key, value);
        return target;
    }

    private static JsonObject toJson(JsonObject value) {
        return value;
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private static boolean notEmpty(String value) {
        return value != null && !value.isEmpty();
    }

    private static void write(File file, JsonObject json) throws IOException {
        atomicWrite(file, json.toString());
    }

    /**
     * Write through a temporary file. The game reads these on its own thread
     * while the launcher may be writing them; a half-written file is a sign-in
     * that silently fails to load, which is the worst possible failure mode
     * here because it looks like a network problem.
     */
    private static void atomicWrite(File file, String content) throws IOException {
        File tmp = new File(file.getParentFile(), file.getName() + ".altertmp");
        Files.write(tmp.toPath(), content.getBytes(StandardCharsets.UTF_8));
        if (!tmp.renameTo(file)) {
            Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
            if (!tmp.delete()) {
                tmp.deleteOnExit();
            }
        }
    }
}
