package com.xingyao.card.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;

/** Strict HTTP transport for endpoints explicitly documented by V4.1. */
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
        return requestJson("GET", path, null);
    }

    public JSONObject post(String path, JSONObject body) throws Exception {
        return requestJson("POST", path, body == null ? new JSONObject() : body);
    }

    public JSONObject uploadMultipart(String path, String fileField, File file,
                                      JSONObject textFields) throws Exception {
        if (file == null || !file.isFile()) throw new IllegalArgumentException("上传文件不存在");
        String boundary = "----CardCabinet" + UUID.randomUUID().toString().replace("-", "");
        HttpURLConnection connection = open(path);
        try {
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            applyBearer(connection);
            try (BufferedOutputStream output = new BufferedOutputStream(connection.getOutputStream())) {
                JSONObject fields = textFields == null ? new JSONObject() : textFields;
                Iterator<String> keys = fields.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    Object value = fields.opt(key);
                    if (value == null || value == JSONObject.NULL) continue;
                    writeUtf8(output, "--" + boundary + "\r\n");
                    writeUtf8(output, "Content-Disposition: form-data; name=\"" + quote(key) + "\"\r\n\r\n");
                    writeUtf8(output, String.valueOf(value) + "\r\n");
                }
                String field = fileField == null || fileField.trim().isEmpty() ? "file" : fileField.trim();
                String contentType = URLConnection.guessContentTypeFromName(file.getName());
                if (contentType == null) contentType = "application/octet-stream";
                writeUtf8(output, "--" + boundary + "\r\n");
                writeUtf8(output, "Content-Disposition: form-data; name=\"" + quote(field)
                        + "\"; filename=\"" + quote(file.getName()) + "\"\r\n");
                writeUtf8(output, "Content-Type: " + contentType + "\r\n\r\n");
                try (FileInputStream input = new FileInputStream(file)) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                }
                writeUtf8(output, "\r\n--" + boundary + "--\r\n");
                output.flush();
            }
            return readJsonResponse(connection);
        } finally {
            connection.disconnect();
        }
    }

    public JSONObject downloadToFile(String absoluteOrRelativeUrl, File target,
                                     long offset) throws Exception {
        if (target == null) throw new IllegalArgumentException("下载目标文件不能为空");
        long safeOffset = Math.max(0L, offset);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("无法创建下载目录：" + parent);
        }
        HttpURLConnection connection = open(absoluteOrRelativeUrl);
        try {
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/octet-stream");
            if (safeOffset > 0L) connection.setRequestProperty("Range", "bytes=" + safeOffset + "-");
            applyBearer(connection);
            int status = connection.getResponseCode();
            if (status >= 400) throw httpFailure(connection, status);
            if (safeOffset > 0L && status != HttpURLConnection.HTTP_PARTIAL) {
                throw new IllegalStateException("服务端未按Range返回206，禁止覆盖已有固件分片");
            }
            long written = 0L;
            try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
                 RandomAccessFile output = new RandomAccessFile(target, "rw")) {
                if (safeOffset == 0L) output.setLength(0L);
                output.seek(safeOffset);
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    written += count;
                }
            }
            return new JSONObject()
                    .put("filePath", target.getAbsolutePath())
                    .put("httpStatus", status)
                    .put("resumedFrom", safeOffset)
                    .put("bytesWritten", written)
                    .put("fileSize", target.length())
                    .put("firmwareVersion", nullableHeader(connection, "X-Firmware-Version"))
                    .put("contentDisposition", nullableHeader(connection, "Content-Disposition"));
        } finally {
            connection.disconnect();
        }
    }

    public static byte[] downloadBytes(String absoluteUrl, String token) throws Exception {
        String normalized = normalizeAbsoluteHttpUrl(absoluteUrl);
        HttpURLConnection connection = (HttpURLConnection) new URL(normalized).openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Accept", "*/*");
            String bearer = token == null ? "" : token.trim();
            if (!bearer.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + bearer);
            int status = connection.getResponseCode();
            if (status >= 400) throw httpFailure(connection, status);
            try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                return output.toByteArray();
            }
        } finally {
            connection.disconnect();
        }
    }

    private JSONObject requestJson(String method, String path, JSONObject body) throws Exception {
        HttpURLConnection connection = open(path);
        try {
            connection.setRequestMethod(method);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            applyBearer(connection);
            if (body != null) {
                connection.setDoOutput(true);
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            }
            return readJsonResponse(connection);
        } finally {
            connection.disconnect();
        }
    }

    private HttpURLConnection open(String pathOrUrl) throws Exception {
        String value = pathOrUrl == null ? "" : pathOrUrl.trim();
        String resolved = value.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")
                ? normalizeAbsoluteHttpUrl(value) : baseUrl + normalizePath(value);
        HttpURLConnection connection = (HttpURLConnection) new URL(resolved).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        return connection;
    }

    private void applyBearer(HttpURLConnection connection) {
        if (!token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
    }

    private static JSONObject readJsonResponse(HttpURLConnection connection) throws Exception {
        int status = connection.getResponseCode();
        String text = readBody(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
        JSONObject response;
        try { response = text.trim().isEmpty() ? new JSONObject() : new JSONObject(text); }
        catch (JSONException error) {
            throw new IllegalStateException("HTTP响应不是JSON，status=" + status, error);
        }
        response.put("_httpStatus", status);
        if (status >= 400) throw new IllegalStateException(response.optString("msg", "HTTP " + status));
        validateBusinessResponse(response);
        return response;
    }

    private static Exception httpFailure(HttpURLConnection connection, int status) throws Exception {
        String text = readBody(connection.getErrorStream());
        if (!text.trim().isEmpty()) {
            try { return new IllegalStateException(new JSONObject(text).optString("msg", "HTTP " + status)); }
            catch (JSONException ignored) { return new IllegalStateException("HTTP " + status + ": " + text); }
        }
        return new IllegalStateException("HTTP " + status);
    }

    private static void validateBusinessResponse(JSONObject response) throws JSONException {
        if (response.optBoolean("forceUpdate", false)) {
            throw new IllegalStateException(response.optString("msg", "当前APP版本存在强制更新，请先升级"));
        }
        if (!response.has("code")) return;
        int code = response.optInt("code", Integer.MIN_VALUE);
        // V4.1 contains both common HTTP code=200 and endpoint examples with code=0.
        if (code == 200 || code == 0) return;
        throw new IllegalStateException(response.optString("msg", "后端接口返回错误 code=" + code));
    }

    public static Object dataValue(JSONObject response) {
        return response == null ? null : response.opt("data");
    }

    public static JSONObject dataObject(JSONObject response) throws JSONException {
        Object data = dataValue(response);
        return data instanceof JSONObject ? (JSONObject) data : new JSONObject();
    }

    public static JSONArray dataArray(JSONObject response) {
        Object data = dataValue(response);
        return data instanceof JSONArray ? (JSONArray) data : new JSONArray();
    }

    public static JSONObject copyWithout(JSONObject source, String... excludedKeys) throws JSONException {
        JSONObject target = new JSONObject();
        if (source == null) return target;
        Iterator<String> keys = source.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            boolean excluded = false;
            for (String excludedKey : excludedKeys) {
                if (key.equals(excludedKey)) { excluded = true; break; }
            }
            if (!excluded) target.put(key, source.opt(key));
        }
        return target;
    }

    public static String normalizeBaseUrl(String value) {
        String raw = value == null ? "" : value.trim();
        if (raw.isEmpty()) throw new IllegalArgumentException("HTTP基础地址不能为空");
        return normalizeAbsoluteHttpUrl(raw).replaceAll("/+$", "");
    }

    private static String normalizeAbsoluteHttpUrl(String value) {
        String raw = value == null ? "" : value.trim();
        if (!raw.matches("^https?://.*")) {
            throw new IllegalArgumentException("HTTP地址必须明确包含http://或https://");
        }
        URI uri = URI.create(raw);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.US);
        if (!("http".equals(scheme) || "https".equals(scheme))
                || uri.getHost() == null || uri.getHost().trim().isEmpty()) {
            throw new IllegalArgumentException("无效HTTP地址：" + raw);
        }
        return uri.toString();
    }

    private static String normalizePath(String path) {
        String value = path == null ? "" : path.trim();
        if (value.isEmpty()) return "";
        return value.startsWith("/") ? value : "/" + value;
    }

    private static void writeUtf8(OutputStream output, String value) throws Exception {
        output.write(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String quote(String value) {
        return value == null ? "" : value.replace("\\", "_").replace("\"", "_")
                .replace("\r", "_").replace("\n", "_");
    }

    private static Object nullableHeader(HttpURLConnection connection, String name) {
        String value = connection.getHeaderField(name);
        return value == null ? JSONObject.NULL : value;
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
