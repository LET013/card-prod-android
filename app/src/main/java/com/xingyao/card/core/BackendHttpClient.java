package com.xingyao.card.core;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;

/** Minimal JSON client for the V4.1 backend lifecycle and fallback APIs. */
public final class BackendHttpClient {
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 12000;

    private final String baseUrl;
    private final String token;

    public BackendHttpClient(String baseUrl, String token) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.token = token == null ? "" : token.trim();
    }

    public JSONObject get(String path) throws Exception {
        return request("GET", path, null);
    }

    public JSONObject post(String path, JSONObject body) throws Exception {
        return request("POST", path, body == null ? new JSONObject() : body);
    }

    public static byte[] downloadBytes(String absoluteUrl, String token) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(absoluteUrl).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Accept", "*/*");
            String bearer = token == null ? "" : token.trim();
            if (!bearer.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + bearer);
            int status = connection.getResponseCode();
            if (status >= 400) throw new IllegalStateException("资源下载失败 HTTP " + status);
            try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                return output.toByteArray();
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private JSONObject request(String method, String path, JSONObject body) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(baseUrl + normalizePath(path)).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            if (!token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
            if (body != null) {
                connection.setDoOutput(true);
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            }
            int status = connection.getResponseCode();
            String text = readBody(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
            JSONObject response = text.trim().isEmpty() ? new JSONObject() : new JSONObject(text);
            response.put("_httpStatus", status);
            validateBusinessResponse(response);
            return response;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void validateBusinessResponse(JSONObject response) throws JSONException {
        if (response.optBoolean("forceUpdate", false)) {
            throw new IllegalStateException(response.optString("msg", "当前APP版本存在强制更新，请先升级"));
        }
        if (!response.has("code")) return;
        int code = response.optInt("code", 200);
        if (code == 200 || code == 0) return;
        throw new IllegalStateException(response.optString("msg", "后端接口返回错误 code=" + code));
    }

    public static JSONObject dataObject(JSONObject response) throws JSONException {
        Object data = response == null ? null : response.opt("data");
        if (data instanceof JSONObject) return (JSONObject) data;
        return new JSONObject();
    }

    public static JSONObject copyWithout(JSONObject source, String... excludedKeys) throws JSONException {
        JSONObject target = new JSONObject();
        if (source == null) return target;
        Iterator<String> keys = source.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            boolean excluded = false;
            for (String excludedKey : excludedKeys) {
                if (key.equals(excludedKey)) {
                    excluded = true;
                    break;
                }
            }
            if (!excluded) target.put(key, source.opt(key));
        }
        return target;
    }

    public static String normalizeBaseUrl(String value) {
        String raw = value == null ? "" : value.trim();
        if (raw.isEmpty()) raw = "http://card-test.quyohui.com";
        if (!raw.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) raw = "http://" + raw;
        raw = raw.replaceAll("/+$", "");
        try {
            URI uri = URI.create(raw);
            String scheme = uri.getScheme() == null ? "http" : uri.getScheme().toLowerCase(Locale.US);
            String authority = uri.getRawAuthority();
            if (authority == null || authority.isEmpty()) return raw;
            String path = uri.getRawPath();
            return scheme + "://" + authority + (path == null ? "" : path).replaceAll("/+$", "");
        } catch (Exception ignored) {
            return raw;
        }
    }

    private static String normalizePath(String path) {
        String value = path == null ? "" : path.trim();
        if (value.isEmpty()) return "";
        return value.startsWith("/") ? value : "/" + value;
    }

    private static String readBody(java.io.InputStream stream) throws Exception {
        if (stream == null) return "";
        try (BufferedInputStream input = new BufferedInputStream(stream);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toString("UTF-8");
        }
    }
}
