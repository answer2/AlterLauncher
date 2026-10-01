package com.answer.launcher.ui;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client for the MC 版本库 API (docs.mcarc.top). It answers one thing the launcher needs: which
 * Minecraft Bedrock builds exist and where to download each. {@code last_version} returns the
 * whole catalog (stable + beta) with per-网盘, per-ABI links, so it alone drives the download
 * screen; {@code search_version} exists but is server-side flaky, so it is not relied on.
 *
 * <p>Per the user's request the BACKUP domain is used first; the primary is only a fallback. The
 * backup is plain HTTP, which the app's network security config already permits. Every call is
 * blocking — callers own the thread.
 */
public final class VersionApi {

    /** Backup domain, used first as requested. */
    public static final String BASE_BACKUP = "http://bbk.endyun.ltd:9000/api";
    /** Primary domain, used only if the backup is unreachable. */
    public static final String BASE_PRIMARY = "https://api.mcarc.top";

    private VersionApi() {
    }

    /** One downloadable build with its网盘 links (provider -> {ARMv7 url, ARMv8 url}). */
    public static final class Version {
        public final String versionAll;
        public final boolean beta;
        public final String updateTime;
        public final String fileSize;
        public final LinkedHashMap<String, String[]> links;

        Version(String versionAll, boolean beta, String updateTime, String fileSize,
                LinkedHashMap<String, String[]> links) {
            this.versionAll = versionAll;
            this.beta = beta;
            this.updateTime = updateTime;
            this.fileSize = fileSize;
            this.links = links;
        }
    }

    /** Fetches the full version list, trying the backup domain then the primary. */
    public static List<Version> lastVersions() throws IOException {
        IOException first = null;
        for (String base : new String[]{BASE_BACKUP, BASE_PRIMARY}) {
            try {
                return parse(postForm(base + "/last_version", ""));
            } catch (IOException e) {
                if (first == null) {
                    first = e;
                }
            }
        }
        throw first != null ? first : new IOException("版本接口不可用");
    }

    private static List<Version> parse(String body) throws IOException {
        List<Version> out = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(body);
            if (root.optInt("status") != 200) {
                throw new IOException(root.optString("message", "接口返回错误"));
            }
            JSONArray message = root.optJSONArray("message");
            if (message == null) {
                return out;
            }
            for (int i = 0; i < message.length(); i++) {
                JSONObject v = message.optJSONObject(i);
                if (v == null) {
                    continue;
                }
                LinkedHashMap<String, String[]> links = new LinkedHashMap<>();
                JSONObject link = v.optJSONObject("link");
                if (link != null) {
                    for (Iterator<String> it = link.keys(); it.hasNext(); ) {
                        String provider = it.next();
                        JSONObject abi = link.optJSONObject(provider);
                        if (abi != null) {
                            links.put(provider, new String[]{
                                    abi.optString("ARMv7", ""), abi.optString("ARMv8", "")});
                        }
                    }
                }
                out.add(new Version(v.optString("version_all", v.optString("version")),
                        v.optInt("is_beta") == 1, v.optString("update_time", ""),
                        v.optString("file_size", ""), links));
            }
        } catch (org.json.JSONException e) {
            throw new IOException("响应解析失败: " + e.getMessage());
        }
        return out;
    }

    private static String postForm(String endpoint, String form) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            byte[] payload = form.getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }
            int code = conn.getResponseCode();
            InputStream in = code >= 200 && code < 400 ? conn.getInputStream() : conn.getErrorStream();
            String body = readAll(in);
            if (code < 200 || code >= 400) {
                throw new IOException("HTTP " + code + (body.isEmpty() ? "" : ": " + trim(body)));
            }
            return body;
        } finally {
            conn.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private static String trim(String s) {
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }
}
