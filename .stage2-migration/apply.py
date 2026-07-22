from pathlib import Path
import re

ROOT = Path('.')


def read(path):
    return (ROOT / path).read_text(encoding='utf-8')


def write(path, content):
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding='utf-8')


def replace_once(path, old, new):
    value = read(path)
    count = value.count(old)
    if count != 1:
        raise RuntimeError(f'{path}: expected one occurrence, got {count}: {old[:140]!r}')
    write(path, value.replace(old, new, 1))


def replace_regex(path, pattern, replacement, flags=re.S):
    value = read(path)
    result, count = re.subn(pattern, replacement, value, count=1, flags=flags)
    if count != 1:
        raise RuntimeError(f'{path}: pattern not found: {pattern[:140]!r}')
    write(path, result)


# ---------------------------------------------------------------------------
# Pure HTTP communication layer. No settings repository and no implicit endpoint.
# ---------------------------------------------------------------------------
write('app/src/main/java/com/xingyao/card/core/BackendHttpClient.java', r'''package com.xingyao.card.core;

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
''')

write('app/src/main/java/com/xingyao/card/core/BackendHttpGateway.java', r'''package com.xingyao.card.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;

/** Pure V4.1 HTTP communication adapter. Configuration is injected by the Android data layer. */
public final class BackendHttpGateway implements DocumentedBackendService.Transport {
    public static final String APP_VERSION_CHECK = "/api/v1/app-version/check";
    public static final String DEVICE_REGISTER = "/api/v1/device/register";
    public static final String DEVICE_ACTIVATE = "/api/v1/device/activate";
    public static final String DEVICE_VERIFY = "/api/v1/device/verify";
    public static final String DEVICE_CONFIG = "/api/v1/device/config";
    public static final String DEVICE_LOGIN = "/api/v1/device/login";
    public static final String DEVICE_HEARTBEAT = "/api/v1/device/heartbeat";
    public static final String DEVICE_STATUS = "/api/v1/device/status";
    public static final String DEVICE_AUTH_CHANGE = "/api/v1/device/auth/change";
    public static final String DEVICE_AUTH_STATUS = "/api/v1/device/auth/status";
    public static final String DEVICE_SELF_CHECK = "/api/v1/device/selfcheck";
    public static final String DEVICE_BATCH_RESULT = "/api/v1/device/batch-result";
    public static final String EMPLOYEE_SYNC = "/api/v1/employee/sync";
    public static final String FACE_SYNC = "/api/v1/employee/face/sync";
    public static final String FINGER_SYNC = "/api/v1/employee/finger/sync";
    public static final String EMPLOYEE_UPSERT = "/api/v1/employee";
    public static final String EMPLOYEE_FACE_UPSERT = "/api/v1/employee/face";
    public static final String FACE_REGISTERED = "/api/v1/employee/face/registered";
    public static final String CARD_EVENT = "/api/v1/card/event";
    public static final String CARD_TAKE = "/api/v1/card/take";
    public static final String CARD_RETURN = "/api/v1/card/return";
    public static final String LOG_REPORT = "/api/v1/log/report";
    public static final String STATISTICS_REPORT = "/api/v1/statistics/report";
    public static final String FAULT_REPORT = "/api/v1/fault/report";
    public static final String UPGRADE_STATUS = "/api/v1/upgrade/status";
    public static final String FACE_UPLOAD = "/api/v1/face/upload";
    public static final String FINGERPRINT_UPLOAD = "/api/v1/fingerprint/upload";
    public static final String LOGS_BATCH = "/api/v1/logs/batch";

    private String baseUrl = "";
    private String deviceToken = "";
    private String deviceCode = "";
    private String httpServerAddress = "";
    private int httpPort;

    public synchronized void configure(JSONObject settings) {
        JSONObject safe = settings == null ? new JSONObject() : settings;
        baseUrl = BackendEndpointSettings.httpBaseUrl(safe);
        deviceToken = safe.optString("deviceToken", "").trim();
        deviceCode = safe.optString("deviceCode", "").trim();
        httpServerAddress = safe.optString("httpServerAddress", "").trim();
        httpPort = safe.optInt("httpPort", 0);
    }

    public synchronized JSONObject anonymousPost(String path, JSONObject body) throws Exception {
        return anonymousClient().post(path, body == null ? new JSONObject() : body);
    }

    public synchronized JSONObject anonymousPostData(String path, JSONObject body) throws Exception {
        return BackendHttpClient.dataObject(anonymousPost(path, body));
    }

    public synchronized JSONObject get(String path) throws Exception {
        return deviceClient().get(path);
    }

    public synchronized JSONObject getData(String path) throws Exception {
        return BackendHttpClient.dataObject(get(path));
    }

    public synchronized JSONArray getDataArray(String path) throws Exception {
        return BackendHttpClient.dataArray(get(path));
    }

    public synchronized JSONObject post(String path, JSONObject body) throws Exception {
        return deviceClient().post(path, body == null ? new JSONObject() : body);
    }

    @Override
    public synchronized JSONObject postData(String path, JSONObject body) throws Exception {
        return BackendHttpClient.dataObject(post(path, body));
    }

    public synchronized JSONObject postDeviceData(String path, JSONObject body) throws Exception {
        return postData(path, body);
    }

    @Override
    public synchronized JSONArray fetchArray(String path) throws Exception {
        return getDataArray(path);
    }

    @Override
    public synchronized JSONObject uploadFaceImage(String userId, File file,
                                                   String faceFeature) throws Exception {
        JSONObject fields = new JSONObject().put("userId", required(userId, "userId"));
        if (faceFeature != null && !faceFeature.trim().isEmpty()) {
            fields.put("faceFeature", faceFeature.trim());
        }
        return BackendHttpClient.dataObject(deviceClient()
                .uploadMultipart(FACE_UPLOAD, "file", file, fields));
    }

    @Override
    public synchronized JSONObject downloadFirmware(String firmwareId, File target,
                                                    long offset) throws Exception {
        String id = required(firmwareId, "firmwareId");
        if (!id.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("firmwareId包含非法字符");
        }
        return deviceClient().downloadToFile("/api/v1/firmware/" + id + "/download",
                target, offset);
    }

    public synchronized byte[] downloadBytes(String absoluteUrl, boolean withToken) throws Exception {
        return BackendHttpClient.downloadBytes(absoluteUrl, withToken ? deviceToken : "");
    }

    public synchronized String absoluteUrl(String value) {
        String raw = value == null ? "" : value.trim();
        if (raw.isEmpty() || raw.matches("^https?://.*")) return raw;
        requireConfigured();
        return baseUrl + (raw.startsWith("/") ? raw : "/" + raw);
    }

    public synchronized JSONObject sendCommand(JSONObject payload) throws Exception {
        if (payload == null) throw new IllegalArgumentException("HTTP消息不能为空");
        String cmd = payload.optString("cmd", "").trim();
        JSONObject data = payload.optJSONObject("data");
        if (data == null) data = copyWithoutEnvelope(payload);
        switch (cmd) {
            case "login": return postData(DEVICE_LOGIN, data);
            case "heartbeat": return postData(DEVICE_HEARTBEAT, data);
            case "cardEvent": return postData(CARD_EVENT, data);
            case "statusReport": return postData(DEVICE_STATUS, data);
            case "logReport": return postData(LOG_REPORT, data);
            case "hardwareFault": return postData(FAULT_REPORT, data);
            case "statisticsReport": return postData(STATISTICS_REPORT, data);
            case "authStatusChange": return postData(DEVICE_AUTH_CHANGE, data);
            case "selfCheckReport": return postData(DEVICE_SELF_CHECK, data);
            case "upgradeStatus": return postData(UPGRADE_STATUS, data);
            case "batchOperationResult": return postData(DEVICE_BATCH_RESULT, data);
            default: throw new IllegalArgumentException("HTTP模式没有对应端点：" + cmd);
        }
    }

    public synchronized JSONObject snapshot() throws JSONException {
        boolean endpointReady = !baseUrl.isEmpty();
        boolean tokenReady = !deviceToken.isEmpty();
        return new JSONObject()
                .put("state", endpointReady ? tokenReady ? "READY" : "PENDING_AUTH" : "NOT_CONFIGURED")
                .put("message", !endpointReady ? "HTTP域名/IP或协议未配置"
                        : tokenReady ? "HTTP端点与deviceToken已就绪" : "HTTP端点已配置，等待设备注册")
                .put("apiBaseUrl", baseUrl)
                .put("httpServerAddress", httpServerAddress)
                .put("httpPort", httpPort)
                .put("deviceTokenReady", tokenReady)
                .put("deviceCode", deviceCode);
    }

    public static String baseUrl(JSONObject settings) {
        return BackendEndpointSettings.httpBaseUrl(settings);
    }

    private BackendHttpClient anonymousClient() {
        requireConfigured();
        return new BackendHttpClient(baseUrl, "");
    }

    private BackendHttpClient deviceClient() {
        requireConfigured();
        if (deviceToken.isEmpty()) throw new IllegalStateException("deviceToken尚未由注册接口返回");
        return new BackendHttpClient(baseUrl, deviceToken);
    }

    private void requireConfigured() {
        if (baseUrl.isEmpty()) throw new IllegalStateException("HTTP域名/IP和协议尚未配置");
    }

    private static JSONObject copyWithoutEnvelope(JSONObject payload) throws JSONException {
        JSONObject result = new JSONObject(payload.toString());
        result.remove("cmd");
        result.remove("msgId");
        result.remove("timestamp");
        result.remove("deviceCode");
        result.remove("sign");
        return result;
    }

    private static String required(String value, String name) {
        String result = value == null ? "" : value.trim();
        if (result.isEmpty()) throw new IllegalArgumentException(name + " is required");
        return result;
    }
}
''')

# ---------------------------------------------------------------------------
# Exact documented endpoint business service. No UI state and no transport internals.
# ---------------------------------------------------------------------------
write('app/src/main/java/com/xingyao/card/core/DocumentedBackendService.java', r'''package com.xingyao.card.core;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;

/** Android business entrypoints for V4.1 interfaces with fully documented request bodies. */
public final class DocumentedBackendService {
    public interface Transport {
        JSONObject postData(String path, JSONObject body) throws Exception;
        JSONArray fetchArray(String path) throws Exception;
        JSONObject uploadFaceImage(String userId, File file, String faceFeature) throws Exception;
        JSONObject downloadFirmware(String firmwareId, File target, long offset) throws Exception;
    }

    private final Context context;
    private final Transport transport;

    public DocumentedBackendService(Context context, Transport transport) {
        if (context == null) throw new IllegalArgumentException("context is required");
        if (transport == null) throw new IllegalArgumentException("transport is required");
        this.context = context.getApplicationContext();
        this.transport = transport;
    }

    public JSONObject upsertEmployee(JSONObject request) throws Exception {
        JSONObject source = request == null ? new JSONObject() : request;
        String action = source.optString("action", "add").trim().toLowerCase();
        if (!("add".equals(action) || "update".equals(action))) {
            throw new IllegalArgumentException("action必须为add或update");
        }
        JSONObject body = new JSONObject().put("action", action);
        if ("update".equals(action)) body.put("employeeId", requiredLong(source, "employeeId"));
        if ("add".equals(action)) {
            body.put("employeeCode", requiredString(source, "employeeCode"));
            body.put("employeeName", requiredString(source, "employeeName"));
        }
        copyOptional(source, body, "cardNo", "deptId", "phone", "email",
                "department", "position", "status");
        return transport.postData(BackendHttpGateway.EMPLOYEE_UPSERT, body);
    }

    public JSONObject disableEmployee(String employeeId) throws Exception {
        JSONObject body = new JSONObject().put("action", "update")
                .put("employeeId", parseRequiredLong(employeeId, "employeeId"))
                .put("status", "1");
        return transport.postData(BackendHttpGateway.EMPLOYEE_UPSERT, body);
    }

    public JSONObject upsertFaceFeature(JSONObject request) throws Exception {
        JSONObject source = request == null ? new JSONObject() : request;
        JSONObject body = new JSONObject()
                .put("employeeId", requiredLong(source, "employeeId"))
                .put("faceFeature", requiredString(source, "faceFeature"));
        copyOptional(source, body, "faceImagePath", "deviceId");
        return transport.postData(BackendHttpGateway.EMPLOYEE_FACE_UPSERT, body);
    }

    public JSONArray registeredFaceEmployeeIds() throws Exception {
        return transport.fetchArray(BackendHttpGateway.FACE_REGISTERED);
    }

    public JSONObject reportTake(String cardNo, int slotId, String authType) throws Exception {
        return transport.postData(BackendHttpGateway.CARD_TAKE,
                cardBody(cardNo, slotId, authType));
    }

    public JSONObject reportReturn(String cardNo, int slotId, String authType) throws Exception {
        return transport.postData(BackendHttpGateway.CARD_RETURN,
                cardBody(cardNo, slotId, authType));
    }

    public JSONObject uploadFingerprint(JSONObject request) throws Exception {
        JSONObject source = request == null ? new JSONObject() : request;
        JSONObject body = new JSONObject()
                .put("userId", requiredString(source, "userId"))
                .put("fingerFeature", requiredString(source, "fingerFeature"))
                .put("fingerIndex", requiredInt(source, "fingerIndex"));
        copyOptional(source, body, "deviceId");
        return transport.postData(BackendHttpGateway.FINGERPRINT_UPLOAD, body);
    }

    public JSONObject uploadLogsBatch(String deviceId, JSONArray logs) throws Exception {
        String id = requiredString(deviceId, "deviceId");
        if (logs == null) throw new IllegalArgumentException("logs is required");
        JSONArray validated = new JSONArray();
        for (int index = 0; index < logs.length(); index++) {
            JSONObject source = logs.optJSONObject(index);
            if (source == null) throw new IllegalArgumentException("logs[" + index + "]必须为对象");
            validated.put(new JSONObject()
                    .put("level", requiredString(source, "level"))
                    .put("tag", requiredString(source, "tag"))
                    .put("content", requiredString(source, "content"))
                    .put("timestamp", requiredLong(source, "timestamp")));
        }
        return transport.postData(BackendHttpGateway.LOGS_BATCH,
                new JSONObject().put("deviceId", id).put("logs", validated));
    }

    public JSONObject uploadFaceImage(String userId, File file, String faceFeature) throws Exception {
        requirePrivateFile(file);
        return transport.uploadFaceImage(requiredString(userId, "userId"), file, faceFeature);
    }

    public JSONObject downloadFirmware(String firmwareId, boolean resume) throws Exception {
        String id = requiredString(firmwareId, "firmwareId");
        File directory = new File(context.getFilesDir(), "firmware");
        File target = new File(directory, id + ".bin");
        long offset = resume && target.isFile() ? target.length() : 0L;
        return transport.downloadFirmware(id, target, offset);
    }

    private JSONObject cardBody(String cardNo, int slotId, String authType) throws JSONException {
        if (slotId < 1) throw new IllegalArgumentException("slotId必须大于0");
        return new JSONObject().put("cardNo", requiredString(cardNo, "cardNo"))
                .put("slotId", slotId)
                .put("authType", requiredString(authType, "authType"));
    }

    private void requirePrivateFile(File file) throws Exception {
        if (file == null || !file.isFile()) throw new IllegalArgumentException("人脸图片文件不存在");
        String candidate = file.getCanonicalPath();
        String files = context.getFilesDir().getCanonicalPath() + File.separator;
        String cache = context.getCacheDir().getCanonicalPath() + File.separator;
        if (!(candidate.startsWith(files) || candidate.startsWith(cache))) {
            throw new SecurityException("只允许上传APP私有目录中的人脸图片");
        }
    }

    private static void copyOptional(JSONObject source, JSONObject target, String... fields)
            throws JSONException {
        for (String field : fields) {
            if (source.has(field) && !source.isNull(field)) target.put(field, source.opt(field));
        }
    }

    private static String requiredString(JSONObject source, String field) {
        return requiredString(source == null ? "" : source.optString(field, ""), field);
    }

    private static String requiredString(String value, String field) {
        String result = value == null ? "" : value.trim();
        if (result.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return result;
    }

    private static long requiredLong(JSONObject source, String field) {
        Object value = source == null ? null : source.opt(field);
        return parseRequiredLong(String.valueOf(value == null ? "" : value), field);
    }

    private static long parseRequiredLong(String value, String field) {
        try { return Long.parseLong(requiredString(value, field)); }
        catch (NumberFormatException error) { throw new IllegalArgumentException(field + "必须为整数"); }
    }

    private static int requiredInt(JSONObject source, String field) {
        long value = requiredLong(source, field);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(field + "超出Integer范围");
        }
        return (int) value;
    }
}
''')

# ---------------------------------------------------------------------------
# Provisioning is Android business orchestration; all transport goes through the gateway.
# ---------------------------------------------------------------------------
write('app/src/main/java/com/xingyao/card/core/DeviceProvisioningManager.java', r'''package com.xingyao.card.core;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;

import com.xingyao.card.BuildConfig;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Android data-layer coordinator for the exact V4.1 startup and activation sequence. */
public final class DeviceProvisioningManager {
    private final Context context;
    private final NativeSettingsRepository settingsRepository;
    private final BackendHttpGateway httpGateway;

    public DeviceProvisioningManager(Context context, NativeSettingsRepository settingsRepository,
                                     BackendHttpGateway httpGateway) {
        if (context == null) throw new IllegalArgumentException("context is required");
        if (settingsRepository == null) throw new IllegalArgumentException("settingsRepository is required");
        if (httpGateway == null) throw new IllegalArgumentException("httpGateway is required");
        this.context = context.getApplicationContext();
        this.settingsRepository = settingsRepository;
        this.httpGateway = httpGateway;
    }

    public synchronized JSONObject ensureProvisioned() throws Exception {
        return ensureProvisioned(false);
    }

    public synchronized JSONObject refreshCredentials() throws Exception {
        return ensureProvisioned(true);
    }

    public synchronized JSONObject refreshRemoteConfig() throws Exception {
        JSONObject settings = settingsRepository.load();
        httpGateway.configure(settings);
        JSONObject remote = httpGateway.getData(BackendHttpGateway.DEVICE_CONFIG);
        JSONObject mapped = DeviceConfigMapper.apply(settings, remote);
        mapped.put("deviceAuthorization", queryAuthorization());
        mapped.put("provisionedAt", System.currentTimeMillis());
        JSONObject saved = settingsRepository.save(mapped);
        httpGateway.configure(saved);
        return saved;
    }

    private JSONObject ensureProvisioned(boolean forceCredentialRefresh) throws Exception {
        JSONObject settings = settingsRepository.load();
        settings.put("machineId", machineId(settings));
        httpGateway.configure(settings);
        checkAppVersion(settings);

        if (settings.optString("deviceToken", "").trim().isEmpty()
                || settings.optString("deviceCode", "").trim().isEmpty()) {
            JSONObject registered = register(settings);
            merge(settings, registered, "deviceToken", "deviceCode", "isNew");
            if (!settings.optString("deviceCode", "").trim().isEmpty()) {
                settings.put("deviceId", settings.optString("deviceCode"));
            }
            settings = settingsRepository.save(settings);
            httpGateway.configure(settings);
        }

        boolean mqttRequested = BackendEndpointSettings.MODE_MQTT.equalsIgnoreCase(
                settings.optString("backendTransport", BackendEndpointSettings.MODE_MQTT));
        if (forceCredentialRefresh || (mqttRequested && !hasMqttCredentials(settings))) {
            settings = performActivation(settings);
            httpGateway.configure(settings);
        }

        JSONObject remote = httpGateway.getData(BackendHttpGateway.DEVICE_CONFIG);
        settings = DeviceConfigMapper.apply(settings, remote);
        httpGateway.configure(settings);

        if (BackendEndpointSettings.MODE_MQTT.equalsIgnoreCase(
                settings.optString("backendTransport", "")) && !hasMqttCredentials(settings)) {
            settings = performActivation(settings);
            httpGateway.configure(settings);
        }

        try {
            settings.put("deviceAuthorization", queryAuthorization());
        } catch (Exception error) {
            settings.put("deviceAuthorization", new JSONObject()
                    .put("state", "UNKNOWN")
                    .put("message", "授权状态查询失败：" + safeMessage(error)));
        }
        settings.put("provisionedAt", System.currentTimeMillis());
        JSONObject saved = settingsRepository.save(settings);
        httpGateway.configure(saved);
        return saved;
    }

    private JSONObject performActivation(JSONObject settings) throws Exception {
        JSONObject activated = httpGateway.postData(BackendHttpGateway.DEVICE_ACTIVATE,
                deviceBody(settings).put("deviceId", required(settings.optString("deviceCode"), "deviceCode")));
        if (hasMqttCredentials(activated)) {
            mergeCredentials(settings, activated);
            return settingsRepository.save(settings);
        }
        if (!activated.has("registerCode")) {
            throw new IllegalStateException("设备激活响应缺少mqtt凭证或registerCode");
        }
        settings.put("registerCode", activated.optString("registerCode", ""))
                .put("activationStatus", activated.optString("status", ""))
                .put("registerCodeExpireTime", activated.optLong("expireTime", 0L));
        String activeKey = settings.optString("activationCode", "").trim();
        if (activeKey.isEmpty()) {
            settingsRepository.save(settings);
            throw new IllegalStateException("设备待激活，请填写激活码；registerCode="
                    + settings.optString("registerCode"));
        }
        JSONObject verified = httpGateway.postData(BackendHttpGateway.DEVICE_VERIFY,
                new JSONObject().put("registerCode", required(settings.optString("registerCode"), "registerCode"))
                        .put("activeKey", activeKey));
        if (!verified.optBoolean("valid", false)) {
            settingsRepository.save(settings);
            throw new IllegalStateException(verified.optString("msg", "设备激活码验证失败"));
        }
        mergeCredentials(settings, verified);
        return settingsRepository.save(settings);
    }

    private JSONObject register(JSONObject settings) throws Exception {
        JSONObject body = deviceBody(settings)
                .put("versionCode", BuildConfig.VERSION_CODE)
                .put("channelId", required(settings.optString("channelId", "official"), "channelId"));
        return httpGateway.anonymousPostData(BackendHttpGateway.DEVICE_REGISTER, body);
    }

    private void checkAppVersion(JSONObject settings) throws Exception {
        JSONObject body = new JSONObject()
                .put("currentVersionCode", BuildConfig.VERSION_CODE)
                .put("channelId", required(settings.optString("channelId", "official"), "channelId"));
        String deviceCode = settings.optString("deviceCode", "").trim();
        if (!deviceCode.isEmpty()) body.put("deviceCode", deviceCode);
        JSONObject response = httpGateway.anonymousPost(BackendHttpGateway.APP_VERSION_CHECK, body);
        Object data = BackendHttpClient.dataValue(response);
        if (!(data instanceof JSONObject)) {
            settings.put("forceUpdate", false).remove("versionInfo");
            settingsRepository.save(settings);
            return;
        }
        JSONObject version = (JSONObject) data;
        settings.put("forceUpdate", version.optBoolean("forceUpdate", false))
                .put("versionInfo", version);
        settingsRepository.save(settings);
        if (version.optBoolean("forceUpdate", false)) {
            throw new IllegalStateException("当前APP版本存在强制更新，请先升级到 "
                    + version.optString("versionName", "最新") + " 版本后再启动");
        }
    }

    private JSONObject queryAuthorization() throws Exception {
        JSONObject data = httpGateway.getData(BackendHttpGateway.DEVICE_AUTH_STATUS);
        boolean authorized = data.optBoolean("authorized", false);
        JSONArray features = data.optJSONArray("features");
        return new JSONObject().put("state", authorized ? "AUTHORIZED" : "UNAUTHORIZED")
                .put("authorized", authorized)
                .put("authorizedUntil", data.optLong("authorizedUntil", 0L))
                .put("daysRemaining", data.optLong("daysRemaining", 0L))
                .put("features", features == null ? new JSONArray() : features)
                .put("message", authorized ? "设备授权有效" : "设备未授权或授权已过期");
    }

    private JSONObject deviceBody(JSONObject settings) throws JSONException {
        return new JSONObject()
                .put("machineId", required(settings.optString("machineId"), "machineId"))
                .put("mac", settings.optString("mac", ""))
                .put("model", Build.MANUFACTURER + " " + Build.MODEL)
                .put("osType", "ANDROID")
                .put("osVersion", Build.VERSION.RELEASE)
                .put("version", BuildConfig.VERSION_NAME);
    }

    private void mergeCredentials(JSONObject settings, JSONObject data) throws JSONException {
        merge(settings, data, "mqttPassword", "signingKey", "clientId", "expireTime",
                "deviceName", "deviceCode");
        settings.put("activationStatus", "ACTIVATED");
        if (!settings.optString("deviceCode", "").trim().isEmpty()) {
            settings.put("deviceId", settings.optString("deviceCode"));
        }
        if (!settings.optString("clientId", "").trim().isEmpty()) {
            settings.put("mqttClientId", settings.optString("clientId"));
        }
    }

    private static boolean hasMqttCredentials(JSONObject source) {
        return source != null
                && !source.optString("mqttPassword", "").trim().isEmpty()
                && !source.optString("signingKey", "").trim().isEmpty()
                && !source.optString("clientId", "").trim().isEmpty();
    }

    private static void merge(JSONObject target, JSONObject source, String... keys) throws JSONException {
        for (String key : keys) {
            if (source != null && source.has(key) && !source.isNull(key)) target.put(key, source.opt(key));
        }
    }

    private String machineId(JSONObject settings) {
        String configured = settings.optString("machineId", "").trim();
        if (!configured.isEmpty()) return configured;
        String androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (androidId != null && !androidId.trim().isEmpty()) return "ANDROID_" + androidId.trim();
        String serial = Build.SERIAL == null ? "" : Build.SERIAL.trim();
        if (!serial.isEmpty() && !"unknown".equalsIgnoreCase(serial)) return "SERIAL_" + serial;
        throw new IllegalStateException("无法获取AndroidID或设备序列号，不能执行设备注册");
    }

    private static String required(String value, String field) {
        String result = value == null ? "" : value.trim();
        if (result.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return result;
    }

    private static String safeMessage(Throwable error) {
        String value = error == null ? "" : error.getMessage();
        return value == null || value.trim().isEmpty()
                ? error == null ? "unknown" : error.getClass().getSimpleName() : value;
    }
}
''')

# ---------------------------------------------------------------------------
# Pure backend transport: no Settings repository and no provisioning/data orchestration.
# ---------------------------------------------------------------------------
write('app/src/main/java/com/xingyao/card/core/BackendTransportManager.java', r'''package com.xingyao.card.core;

import android.util.Base64;

import com.xingyao.card.BuildConfig;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** MQTT/HTTP/legacy-TCP transport only. All credentials and settings are injected. */
public final class BackendTransportManager {
    public interface Listener {
        void onStatusChanged(JSONObject status);
        void onCommand(JSONObject command);
        void onMessage(JSONObject message);
        void onRuntimeToken(String token);
    }

    private static final long HEARTBEAT_INTERVAL_MS = 30000L;
    private static final long RECONNECT_DELAY_MS = 5000L;

    private final BackendHttpGateway httpGateway;
    private final Listener listener;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();

    private ScheduledFuture<?> reconnectTask;
    private ScheduledFuture<?> heartbeatTask;
    private MqttAsyncClient mqttClient;
    private Socket tcpSocket;
    private BufferedOutputStream tcpOutput;
    private volatile boolean running;
    private volatile boolean connecting;
    private volatile String state = "DISCONNECTED";
    private volatile String message = "后端通信未启动";
    private volatile String lastError = "";
    private String transportMode = BackendEndpointSettings.MODE_MQTT;
    private String deviceCode = "";
    private String clientId = "";
    private String brokerUri = "";
    private String mqttUsername = "";
    private String mqttPassword = "";
    private String signingKey = "";
    private String commandTopic = "";
    private String responseTopic = "";
    private String eventTopic = "";
    private String heartbeatTopic = "";
    private String tcpHost = "";
    private int tcpPort;
    private long sentMessages;
    private long receivedMessages;
    private long lastConnectedAt;
    private long authenticatedAt;
    private long lastMessageAt;
    private long heartbeatSequence;

    public BackendTransportManager(BackendHttpGateway httpGateway, Listener listener) {
        if (httpGateway == null) throw new IllegalArgumentException("httpGateway is required");
        this.httpGateway = httpGateway;
        this.listener = listener;
    }

    public synchronized void configure(JSONObject rawSettings) {
        JSONObject settings;
        try { settings = BackendEndpointSettings.normalize(rawSettings); }
        catch (JSONException error) { settings = rawSettings == null ? new JSONObject() : rawSettings; }
        deviceCode = settings.optString("deviceCode", "").trim();
        String mode = settings.optString("backendTransport", BackendEndpointSettings.MODE_MQTT)
                .trim().toUpperCase(Locale.US);
        transportMode = BackendEndpointSettings.MODE_HTTP.equals(mode)
                ? BackendEndpointSettings.MODE_HTTP
                : BackendEndpointSettings.MODE_TCP.equals(mode)
                ? BackendEndpointSettings.MODE_TCP : BackendEndpointSettings.MODE_MQTT;
        clientId = settings.optString("clientId", settings.optString("mqttClientId", "")).trim();
        mqttUsername = settings.optString("mqttUsername", "").trim();
        mqttPassword = settings.optString("mqttPassword", "");
        signingKey = settings.optString("signingKey", "");
        brokerUri = BackendEndpointSettings.mqttBrokerUrl(settings);
        tcpHost = BackendEndpointSettings.tcpHost(settings);
        tcpPort = settings.optInt("tcpPort", 0);
        commandTopic = deviceCode.isEmpty() ? "" : "card/" + deviceCode + "/down";
        responseTopic = deviceCode.isEmpty() ? "" : "card/" + deviceCode + "/down/response";
        eventTopic = deviceCode.isEmpty() ? "" : "card/" + deviceCode + "/up";
        heartbeatTopic = deviceCode.isEmpty() ? "" : "card/" + deviceCode + "/heartbeat";
        if (running) reconnectNow();
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        reconnectNow();
    }

    public synchronized void stop() {
        running = false;
        cancelReconnect();
        stopHeartbeat();
        closeTransports();
        executor.shutdownNow();
        heartbeatExecutor.shutdownNow();
        updateState("DISCONNECTED", "后端通信已停止", null);
    }

    public synchronized JSONObject snapshot() throws JSONException {
        return new JSONObject().put("state", state).put("message", message)
                .put("transportMode", transportMode)
                .put("deviceCode", deviceCode).put("clientId", clientId)
                .put("brokerUri", brokerUri).put("tcpHost", tcpHost).put("tcpPort", tcpPort)
                .put("commandTopic", commandTopic).put("responseTopic", responseTopic)
                .put("eventTopic", eventTopic).put("heartbeatTopic", heartbeatTopic)
                .put("httpDownlinkSupported", false)
                .put("sentMessages", sentMessages).put("receivedMessages", receivedMessages)
                .put("transportConnected", isTransportConnected())
                .put("authenticated", "AUTHENTICATED".equals(state))
                .put("lastConnectedAt", lastConnectedAt == 0L ? JSONObject.NULL : lastConnectedAt)
                .put("authenticatedAt", authenticatedAt == 0L ? JSONObject.NULL : authenticatedAt)
                .put("lastMessageAt", lastMessageAt == 0L ? JSONObject.NULL : lastMessageAt)
                .put("lastError", lastError.isEmpty() ? JSONObject.NULL : lastError);
    }

    public synchronized boolean isAuthenticated() { return "AUTHENTICATED".equals(state); }
    public synchronized String transportMode() { return transportMode; }

    public void send(JSONObject payload) throws Exception {
        String cmd = payload == null ? "" : payload.optString("cmd", "").trim();
        if (cmd.isEmpty()) throw new IllegalArgumentException("后端消息缺少cmd");
        boolean lifecycle = "login".equals(cmd) || "heartbeat".equals(cmd);
        if (!lifecycle && !"AUTHENTICATED".equals(state)) {
            throw new IllegalStateException("后端业务会话尚未认证，当前状态：" + state);
        }
        if (BackendEndpointSettings.MODE_HTTP.equals(transportMode)) sendHttp(payload);
        else if (BackendEndpointSettings.MODE_TCP.equals(transportMode)) sendTcp(payload);
        else publishMqtt(payload);
    }

    private synchronized void reconnectNow() {
        cancelReconnect();
        stopHeartbeat();
        closeTransports();
        if (!running) return;
        if (BackendEndpointSettings.MODE_HTTP.equals(transportMode)) connectHttp();
        else if (BackendEndpointSettings.MODE_TCP.equals(transportMode)) connectTcp();
        else connectMqtt();
    }

    private void connectHttp() {
        if (connecting) return;
        connecting = true;
        updateState("LOGIN_SENT", "正在执行HTTP设备登录", null);
        executor.execute(() -> {
            try {
                JSONObject login = httpGateway.postData(BackendHttpGateway.DEVICE_LOGIN,
                        new JSONObject().put("version", BuildConfig.VERSION_NAME));
                requireLoginSuccess(login, "HTTP");
                String token = login.optString("token", "").trim();
                if (!token.isEmpty() && listener != null) listener.onRuntimeToken(token);
                connecting = false;
                lastConnectedAt = System.currentTimeMillis();
                authenticatedAt = lastConnectedAt;
                updateState("AUTHENTICATED",
                        "HTTP业务登录成功；V4.1未定义HTTP下行指令", null);
                startHeartbeat();
            } catch (Exception error) {
                connecting = false;
                updateState("ERROR", "HTTP登录失败：" + safeMessage(error), error);
                scheduleReconnect();
            }
        });
    }

    private void connectMqtt() {
        if (brokerUri.isEmpty()) { updateState("DISCONNECTED", "MQTT域名/IP、端口或协议未配置", null); return; }
        if (deviceCode.isEmpty() || clientId.isEmpty() || mqttPassword.isEmpty() || signingKey.isEmpty()) {
            updateState("PENDING_CREDENTIALS", "MQTT等待deviceCode/clientId/password/signingKey", null);
            return;
        }
        if (connecting) return;
        connecting = true;
        updateState("CONNECTING", "正在连接MQTT " + brokerUri, null);
        executor.execute(() -> {
            MqttAsyncClient next = null;
            try {
                next = new MqttAsyncClient(brokerUri, clientId, new MemoryPersistence());
                final MqttAsyncClient active = next;
                next.setCallback(new MqttCallbackExtended() {
                    @Override public void connectComplete(boolean reconnect, String serverURI) {
                        try {
                            lastConnectedAt = System.currentTimeMillis();
                            updateState("TRANSPORT_CONNECTED", "MQTT传输已连接 " + serverURI, null);
                            active.subscribe(commandTopic, 1).waitForCompletion();
                            active.subscribe(responseTopic, 1).waitForCompletion();
                            updateState("SUBSCRIBED", "MQTT Topic订阅完成", null);
                            sendLogin();
                            updateState("LOGIN_SENT", "MQTT登录消息已发送", null);
                        } catch (Exception error) {
                            updateState("ERROR", "MQTT订阅或登录失败：" + safeMessage(error), error);
                            closeTransports();
                            scheduleReconnect();
                        }
                    }
                    @Override public void connectionLost(Throwable cause) {
                        authenticatedAt = 0L;
                        stopHeartbeat();
                        updateState("ERROR", "MQTT连接断开：" + safeMessage(cause),
                                cause instanceof Exception ? (Exception) cause : null);
                        scheduleReconnect();
                    }
                    @Override public void messageArrived(String topic, MqttMessage mqttMessage) {
                        handleIncoming("mqtt:" + topic, mqttMessage == null ? ""
                                : new String(mqttMessage.getPayload(), StandardCharsets.UTF_8));
                    }
                    @Override public void deliveryComplete(IMqttDeliveryToken token) { }
                });
                synchronized (this) { mqttClient = next; }
                MqttConnectOptions options = new MqttConnectOptions();
                options.setAutomaticReconnect(false);
                options.setCleanSession(true);
                options.setKeepAliveInterval(60);
                options.setConnectionTimeout(10);
                if (!mqttUsername.isEmpty()) options.setUserName(mqttUsername);
                options.setPassword(mqttPassword.toCharArray());
                next.connect(options).waitForCompletion();
                connecting = false;
            } catch (Exception error) {
                connecting = false;
                closeMqtt(next);
                updateState("ERROR", "MQTT连接失败：" + safeMessage(error), error);
                scheduleReconnect();
            }
        });
    }

    private void connectTcp() {
        if (tcpHost.isEmpty() || tcpPort < 1) {
            updateState("DISCONNECTED", "兼容TCP地址或端口未配置", null); return;
        }
        if (connecting) return;
        connecting = true;
        executor.execute(() -> {
            try {
                Socket socket = new Socket(tcpHost, tcpPort);
                socket.setKeepAlive(true);
                socket.setTcpNoDelay(true);
                synchronized (this) {
                    tcpSocket = socket;
                    tcpOutput = new BufferedOutputStream(socket.getOutputStream());
                    connecting = false;
                    lastConnectedAt = System.currentTimeMillis();
                }
                updateState("TRANSPORT_CONNECTED", "兼容TCP已连接", null);
                sendLogin();
                updateState("LOGIN_SENT", "兼容TCP登录消息已发送", null);
                readTcpLoop(socket);
            } catch (Exception error) {
                connecting = false;
                closeTransports();
                updateState("ERROR", "兼容TCP连接失败：" + safeMessage(error), error);
                scheduleReconnect();
            }
        });
    }

    private void readTcpLoop(Socket socket) {
        byte[] bytes = new byte[4096];
        StringBuilder buffer = new StringBuilder();
        try (BufferedInputStream input = new BufferedInputStream(socket.getInputStream())) {
            while (running && !socket.isClosed()) {
                int count = input.read(bytes);
                if (count < 0) break;
                if (count > 0) {
                    buffer.append(new String(bytes, 0, count, StandardCharsets.UTF_8));
                    consumeJson(buffer);
                }
            }
        } catch (Exception error) {
            if (running) updateState("ERROR", "兼容TCP读取失败：" + safeMessage(error), error);
        } finally {
            closeTransports();
            stopHeartbeat();
            scheduleReconnect();
        }
    }

    private void consumeJson(StringBuilder buffer) {
        while (true) {
            int start = findJsonStart(buffer);
            if (start < 0) { buffer.setLength(0); return; }
            if (start > 0) buffer.delete(0, start);
            int end = findJsonEnd(buffer);
            if (end < 0) return;
            String raw = buffer.substring(0, end + 1);
            buffer.delete(0, end + 1);
            handleIncoming("tcp", raw);
        }
    }

    private void handleIncoming(String source, String raw) {
        try {
            JSONObject envelope = new JSONObject(raw == null ? "" : raw.trim());
            receivedMessages++;
            lastMessageAt = System.currentTimeMillis();
            notifyMessage(summary(envelope, source));
            String cmd = envelope.optString("cmd", "");
            if ("loginResp".equals(cmd)) {
                requireLoginSuccess(envelope, "MQTT/TCP");
                authenticatedAt = System.currentTimeMillis();
                updateState("AUTHENTICATED", "后台业务登录成功", null);
                startHeartbeat();
                return;
            }
            if ("heartbeatResp".equals(cmd) || cmd.endsWith("Resp")) return;
            if (!"AUTHENTICATED".equals(state)) {
                updateState("AUTH_REQUIRED", "收到下行指令但设备尚未登录", null); return;
            }
            if (listener != null) listener.onCommand(commandFromEnvelope(envelope, source));
        } catch (Exception error) {
            updateState("ERROR", "后端消息处理失败：" + safeMessage(error),
                    error instanceof Exception ? (Exception) error : null);
        }
    }

    private void sendLogin() throws Exception {
        send(new JSONObject().put("cmd", "login")
                .put("data", new JSONObject().put("version", BuildConfig.VERSION_NAME)));
    }

    private synchronized void startHeartbeat() {
        stopHeartbeat();
        heartbeatTask = heartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                if (BackendEndpointSettings.MODE_HTTP.equals(transportMode)) {
                    JSONObject result = httpGateway.postData(BackendHttpGateway.DEVICE_HEARTBEAT,
                            new JSONObject().put("seq", ++heartbeatSequence));
                    receivedMessages++;
                    lastMessageAt = System.currentTimeMillis();
                    notifyMessage(summary(new JSONObject().put("cmd", "heartbeatResp")
                            .put("data", result), "http"));
                } else {
                    send(new JSONObject().put("cmd", "heartbeat").put("data", new JSONObject()));
                }
            } catch (Exception error) {
                updateState("ERROR", "心跳失败：" + safeMessage(error), error);
                closeTransports();
                scheduleReconnect();
            }
        }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private synchronized void stopHeartbeat() {
        if (heartbeatTask != null) { heartbeatTask.cancel(false); heartbeatTask = null; }
    }

    private synchronized void publishMqtt(JSONObject payload) throws Exception {
        if (mqttClient == null || !mqttClient.isConnected()) throw new IllegalStateException("MQTT未连接");
        JSONObject envelope = buildEnvelope(payload);
        boolean heartbeat = "heartbeat".equals(envelope.optString("cmd"));
        MqttMessage message = new MqttMessage(envelope.toString().getBytes(StandardCharsets.UTF_8));
        message.setQos(heartbeat ? 0 : 1);
        message.setRetained(false);
        mqttClient.publish(heartbeat ? heartbeatTopic : eventTopic, message).waitForCompletion();
        sentMessages++;
    }

    private synchronized void sendTcp(JSONObject payload) throws Exception {
        if (tcpSocket == null || tcpSocket.isClosed() || tcpOutput == null) {
            throw new IllegalStateException("兼容TCP未连接");
        }
        tcpOutput.write((payload.toString() + "\n").getBytes(StandardCharsets.UTF_8));
        tcpOutput.flush();
        sentMessages++;
    }

    private void sendHttp(JSONObject payload) throws Exception {
        JSONObject result = httpGateway.sendCommand(payload);
        sentMessages++;
        receivedMessages++;
        lastMessageAt = System.currentTimeMillis();
        notifyMessage(summary(new JSONObject().put("cmd", payload.optString("cmd") + "Resp")
                .put("data", result), "http"));
    }

    private JSONObject buildEnvelope(JSONObject payload) throws Exception {
        String cmd = payload.optString("cmd", "").trim();
        JSONObject data = payload.optJSONObject("data");
        if (data == null) data = mqttData(payload);
        long timestamp = System.currentTimeMillis();
        String msgId = payload.optString("msgId", "").trim();
        if (msgId.isEmpty()) msgId = "msg_" + timestamp
                + String.format(Locale.US, "%03d", new Random().nextInt(1000));
        String canonical = data.length() == 0 ? "{}" : data.toString();
        return new JSONObject().put("msgId", msgId).put("cmd", cmd)
                .put("timestamp", timestamp).put("deviceCode", deviceCode)
                .put("sign", sign(msgId, cmd, timestamp, canonical)).put("data", data);
    }

    private JSONObject commandFromEnvelope(JSONObject envelope, String source) throws JSONException {
        JSONObject data = envelope.optJSONObject("data");
        JSONObject command = data == null ? new JSONObject() : new JSONObject(data.toString());
        return command.put("cmd", envelope.optString("cmd", ""))
                .put("msgId", envelope.optString("msgId", ""))
                .put("timestamp", envelope.optLong("timestamp", 0L))
                .put("_source", source == null ? "" : source);
    }

    private static JSONObject mqttData(JSONObject payload) throws JSONException {
        JSONObject data = new JSONObject();
        java.util.Iterator<String> keys = payload.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if ("cmd".equals(key) || "msgId".equals(key) || "timestamp".equals(key)
                    || "deviceCode".equals(key) || "sign".equals(key) || key.startsWith("_")) continue;
            data.put(key, payload.opt(key));
        }
        return data;
    }

    private String sign(String msgId, String cmd, long timestamp, String canonical) throws Exception {
        if (signingKey.trim().isEmpty()) throw new IllegalStateException("MQTT signingKey为空");
        String input = msgId + ":" + cmd + ":" + timestamp + ":" + canonical;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(signingKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.encodeToString(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    }

    private static void requireLoginSuccess(JSONObject payload, String channel) {
        JSONObject data = payload == null ? null : payload.optJSONObject("data");
        Integer code = payload != null && payload.has("code") ? payload.optInt("code")
                : data != null && data.has("code") ? data.optInt("code") : null;
        if (code == null || code != 0) {
            String text = payload == null ? "" : payload.optString("msg", "");
            if (text.isEmpty() && data != null) text = data.optString("msg", "");
            throw new IllegalStateException(text.isEmpty()
                    ? channel + "登录响应缺少明确code=0" : text);
        }
    }

    private synchronized boolean isTransportConnected() {
        if (BackendEndpointSettings.MODE_HTTP.equals(transportMode)) return "AUTHENTICATED".equals(state);
        if (BackendEndpointSettings.MODE_TCP.equals(transportMode)) return tcpSocket != null && !tcpSocket.isClosed();
        return mqttClient != null && mqttClient.isConnected();
    }

    private synchronized void closeTransports() {
        MqttAsyncClient current = mqttClient;
        mqttClient = null;
        closeMqtt(current);
        try { if (tcpOutput != null) tcpOutput.close(); } catch (Exception ignored) { }
        try { if (tcpSocket != null) tcpSocket.close(); } catch (Exception ignored) { }
        tcpOutput = null;
        tcpSocket = null;
        authenticatedAt = 0L;
    }

    private static void closeMqtt(MqttAsyncClient client) {
        if (client == null) return;
        try { if (client.isConnected()) client.disconnectForcibly(1000, 1000); }
        catch (Exception ignored) { }
        try { client.close(); } catch (Exception ignored) { }
    }

    private synchronized void scheduleReconnect() {
        if (!running || executor.isShutdown()) return;
        cancelReconnect();
        reconnectTask = executor.schedule(this::reconnectNow, RECONNECT_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private synchronized void cancelReconnect() {
        if (reconnectTask != null) { reconnectTask.cancel(false); reconnectTask = null; }
    }

    private synchronized void updateState(String next, String nextMessage, Exception error) {
        state = next;
        message = nextMessage;
        lastError = error == null ? "" : safeMessage(error);
        if (listener != null) {
            try { listener.onStatusChanged(snapshot()); }
            catch (JSONException ignored) { }
        }
    }

    private void notifyMessage(JSONObject value) {
        if (listener != null) listener.onMessage(value);
    }

    private static JSONObject summary(JSONObject payload, String source) throws JSONException {
        return new JSONObject().put("source", source == null ? "" : source)
                .put("cmd", payload == null ? "" : payload.optString("cmd", ""))
                .put("msgId", payload == null ? "" : payload.optString("msgId", ""))
                .put("timestamp", payload == null ? 0L : payload.optLong("timestamp", 0L));
    }

    private static int findJsonStart(StringBuilder buffer) {
        for (int index = 0; index < buffer.length(); index++) if (buffer.charAt(index) == '{') return index;
        return -1;
    }

    private static int findJsonEnd(StringBuilder buffer) {
        boolean string = false;
        boolean escaped = false;
        int depth = 0;
        for (int index = 0; index < buffer.length(); index++) {
            char ch = buffer.charAt(index);
            if (escaped) { escaped = false; continue; }
            if (ch == '\\') { escaped = string; continue; }
            if (ch == '"') { string = !string; continue; }
            if (string) continue;
            if (ch == '{') depth++;
            if (ch == '}' && --depth == 0) return index;
        }
        return -1;
    }

    private static String safeMessage(Throwable error) {
        String value = error == null ? "" : error.getMessage();
        return value == null || value.trim().isEmpty()
                ? error == null ? "unknown" : error.getClass().getSimpleName() : value;
    }
}
''')

# Delete the mixed old manager after Service migration.
old_transport = ROOT / 'app/src/main/java/com/xingyao/card/core/WebSocketConnectionManager.java'
if old_transport.exists(): old_transport.unlink()

# ---------------------------------------------------------------------------
# Data repository strict documented primary keys.
# ---------------------------------------------------------------------------
path = 'app/src/main/java/com/xingyao/card/core/DeviceDataRepository.java'
value = read(path)
value = value.replace('loadMap(KEY_EMPLOYEES, employees,\n                    "employeeId", "employeeCode", "id")',
                      'loadMap(KEY_EMPLOYEES, employees, "employeeId")')
value = value.replace('loadMap(KEY_FACE_FEATURES, faceFeatures,\n                    "faceId", "employeeId", "id")',
                      'loadMap(KEY_FACE_FEATURES, faceFeatures, "faceId")')
value = value.replace('loadMap(KEY_FINGER_FEATURES, fingerFeatures,\n                    "fingerId", "employeeId", "id")',
                      'loadMap(KEY_FINGER_FEATURES, fingerFeatures, "fingerId")')
value = value.replace('upsertMap(employees, items, "employeeId", "employeeCode", "id");',
                      'upsertMap(employees, items, "employeeId");')
value = value.replace('String key = firstKey(item, primaryKey, "id");',
                      'String key = firstKey(item, primaryKey);')
write(path, value)

# External downlink does not define full/fullSync; sync methods receive an internal boolean only.
path = 'app/src/main/java/com/xingyao/card/core/DeviceDataSyncManager.java'
value = read(path)
value = value.replace('syncAll(JSONObject command)', 'syncAll(boolean full)')
value = value.replace('syncEmployees(JSONObject command)', 'syncEmployees(boolean full)')
value = value.replace('syncFaces(JSONObject command)', 'syncFaces(boolean full)')
value = value.replace('syncFingers(JSONObject command)', 'syncFingers(boolean full)')
value = value.replace('        boolean full = isFull(command);\n', '')
value = value.replace('''        JSONObject scope = new JSONObject().put("includeFlags",
                command == null ? settings.optInt("faceSyncIncludeFlags", 3)
                        : command.optInt("includeFlags", settings.optInt("faceSyncIncludeFlags", 3)));''',
'''        JSONObject scope = new JSONObject().put("includeFlags",
                settings.optInt("faceSyncIncludeFlags", 3));''')
value = re.sub(r'''\n    private static boolean isFull\(JSONObject command\) \{.*?\n    \}\n''', '\n', value, count=1, flags=re.S)
write(path, value)

# ---------------------------------------------------------------------------
# Data layer owns provisioning and all documented interface entrypoints.
# ---------------------------------------------------------------------------
path = 'app/src/main/java/com/xingyao/card/core/DeviceDataLayer.java'
value = read(path)
value = value.replace('''    public interface BackendPort extends DeviceCommandCoordinator.BackendPort {
        JSONObject snapshot() throws JSONException;
        void configure(JSONObject settings);
        String transportMode();
    }''', '''    public interface BackendPort extends DeviceCommandCoordinator.BackendPort {
        JSONObject snapshot() throws JSONException;
        void configure(JSONObject settings);
        void start();
        void stop();
        String transportMode();
    }''')
value = value.replace('''    private final BackendHttpGateway httpGateway;
    private final DeviceCommandCoordinator commandCoordinator;''', '''    private final BackendHttpGateway httpGateway;
    private final DeviceProvisioningManager provisioningManager;
    private final DocumentedBackendService documentedBackendService;
    private final DeviceCommandCoordinator commandCoordinator;''')
value = value.replace('''    private final ExecutorService backendCommandExecutor = Executors.newSingleThreadExecutor();''', '''    private final ExecutorService backendCommandExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService provisioningExecutor = Executors.newSingleThreadExecutor();''')
value = value.replace('''                           FaceAiManager faceAiManager,
                           BackendHttpGateway httpGateway,
                           InboundCommandRepository inboundRepository,''', '''                           FaceAiManager faceAiManager,
                           BackendHttpGateway httpGateway,
                           DeviceProvisioningManager provisioningManager,
                           DocumentedBackendService documentedBackendService,
                           InboundCommandRepository inboundRepository,''')
value = value.replace('''        this.faceAiManager = faceAiManager;
        this.httpGateway = httpGateway;''', '''        this.faceAiManager = faceAiManager;
        this.httpGateway = httpGateway;
        this.provisioningManager = provisioningManager;
        this.documentedBackendService = documentedBackendService;''')
value = value.replace('''        refreshSyncSection();
        startSlotReporter(safeSettings);''', '''        refreshSyncSection();
        startSlotReporter(safeSettings);
        provisionAndStartBackend(false);''', 1)
value = value.replace('''        backendCommandExecutor.shutdownNow();
        reportExecutor.shutdownNow();''', '''        backendPort.stop();
        backendCommandExecutor.shutdownNow();
        provisioningExecutor.shutdownNow();
        reportExecutor.shutdownNow();''')
value = value.replace('''    public JSONObject deleteEmployee(String id) throws JSONException {
        String employeeId = stateStore.deleteEmployee(id);
        if (!employeeId.isEmpty()) faceAiManager.deleteTemplate(employeeId);
        return new JSONObject().put("success", !employeeId.isEmpty())
                .put("id", id).put("employeeId", employeeId);
    }''', '''    public JSONObject deleteEmployee(String id) throws Exception {
        JSONObject employee = dataRepository.employee(id);
        if (employee == null) return new JSONObject().put("success", false).put("id", id);
        String employeeId = employee.optString("employeeId", "").trim();
        JSONObject backend = documentedBackendService.disableEmployee(employeeId);
        String removed = stateStore.deleteEmployee(employeeId);
        if (!removed.isEmpty()) faceAiManager.deleteTemplate(removed);
        JSONObject result = new JSONObject().put("success", !removed.isEmpty())
                .put("id", id).put("employeeId", removed).put("backend", backend);
        stateStore.record("employee.disabled", result);
        return result;
    }

    public JSONObject upsertEmployee(JSONObject request) throws Exception {
        JSONObject result = documentedBackendService.upsertEmployee(request);
        stateStore.record("employee.upserted", result);
        return result;
    }

    public JSONObject upsertFaceFeature(JSONObject request) throws Exception {
        JSONObject result = documentedBackendService.upsertFaceFeature(request);
        stateStore.record("employee.face.upserted", result);
        return result;
    }

    public JSONArray registeredFaceEmployeeIds() throws Exception {
        JSONArray result = documentedBackendService.registeredFaceEmployeeIds();
        stateStore.record("employee.face.registered.loaded",
                new JSONObject().put("employeeIds", result));
        return result;
    }

    public JSONObject uploadFingerprintFeature(JSONObject request) throws Exception {
        JSONObject result = documentedBackendService.uploadFingerprint(request);
        stateStore.record("employee.fingerprint.uploaded", result);
        return result;
    }

    public JSONObject uploadLogsBatch(JSONArray logs) throws Exception {
        JSONObject settings = settingsRepository.load();
        JSONObject result = documentedBackendService.uploadLogsBatch(
                settings.optString("deviceCode", ""), logs);
        stateStore.record("logs.batch.uploaded", result);
        return result;
    }

    public JSONObject downloadFirmware(String firmwareId, boolean resume) throws Exception {
        JSONObject result = documentedBackendService.downloadFirmware(firmwareId, resume);
        stateStore.record("firmware.downloaded", result);
        return result;
    }

    public JSONObject reportConfirmedTake(String cardNo, int slotId, String authType) throws Exception {
        return documentedBackendService.reportTake(cardNo, slotId, authType);
    }

    public JSONObject reportConfirmedReturn(String cardNo, int slotId, String authType) throws Exception {
        return documentedBackendService.reportReturn(cardNo, slotId, authType);
    }''')
value = value.replace('''        serialPort.configure(safeSettings);
        if (reconnectBackend) backendPort.configure(safeSettings);''', '''        serialPort.configure(safeSettings);
        httpGateway.configure(safeSettings);
        if (reconnectBackend) provisionAndStartBackend(false);''')
value = value.replace('''        JSONObject result = faceAiManager.enrollFeature(id, resolvedName, faceFeature, "LOCAL_CAMERA")
                .put("similarity", score).put("engine", "FaceAISDK");''', '''        JSONObject backend = documentedBackendService.upsertFaceFeature(new JSONObject()
                .put("employeeId", id).put("faceFeature", faceFeature));
        JSONObject result = faceAiManager.enrollFeature(id, resolvedName, faceFeature, "LOCAL_CAMERA")
                .put("similarity", score).put("engine", "FaceAISDK")
                .put("backend", backend);''')
value = value.replace('''    public void onBackendStatus(JSONObject status) {
        stateStore.updateSection("socket", "socket.statusChanged", status);''', '''    public void onRuntimeToken(String token) {
        if (token == null || token.trim().isEmpty()) return;
        try {
            JSONObject settings = settingsRepository.load();
            settings.put("runtimeToken", token.trim());
            settingsRepository.save(settings);
            stateStore.record("backend.runtimeToken.received",
                    new JSONObject().put("present", true));
        } catch (Exception error) {
            stateStore.record("backend.runtimeToken.persistFailed", message(error));
        }
    }

    public void onBackendStatus(JSONObject status) {
        stateStore.updateSection("socket", "socket.statusChanged", status);''')
value = value.replace('syncManager.syncAll(new JSONObject()\n                        .put("full", false).put("source", "startup"))',
                      'syncManager.syncAll(false)')
value = value.replace('''        if (authorization == null) {
            String activation = settings == null ? "" : settings.optString("activationStatus", "");
            authorization = eventState("ACTIVATED".equalsIgnoreCase(activation)
                    ? "AUTHORIZED" : "PENDING", "authorization",
                    "ACTIVATED".equalsIgnoreCase(activation) ? "设备已激活" : "等待设备授权查询");
        }''', '''        if (authorization == null) {
            authorization = eventState("PENDING", "authorization", "等待设备授权查询");
        }''')
# Insert provisioning method before runStartupSyncIfNeeded.
marker = '    private void runStartupSyncIfNeeded() {'
if marker not in value:
    raise RuntimeError('DeviceDataLayer startup marker not found')
provision_method = '''    private void provisionAndStartBackend(boolean refreshCredentials) {
        if (stopped || provisioningExecutor.isShutdown()) return;
        stateStore.updateSection("socket", "socket.statusChanged",
                eventState("PROVISIONING", "backend", "正在执行设备注册、激活、配置和授权查询"));
        provisioningExecutor.execute(() -> {
            try {
                JSONObject saved = refreshCredentials
                        ? provisioningManager.refreshCredentials()
                        : provisioningManager.ensureProvisioned();
                if (stopped) return;
                httpGateway.configure(saved);
                stateStore.configure(saved);
                updateAuthorizationSection(saved);
                serialPort.configure(saved);
                backendPort.configure(saved);
                backendPort.start();
                stateStore.updateSection("http", "http.statusChanged", httpGateway.snapshot());
                stateStore.emit("settings.changed", settingsRepository.loadForUi());
            } catch (Exception error) {
                stateStore.updateSection("socket", "socket.statusChanged",
                        eventState("ERROR", "backend", safeMessage(error)));
                stateStore.record("backend.provisioning.failed", message(error));
            }
        });
    }

'''
value = value.replace(marker, provision_method + marker, 1)
write(path, value)

# ---------------------------------------------------------------------------
# Exact downlink response contract: code 0 or documented generic failure 500 only.
# ---------------------------------------------------------------------------
path = 'app/src/main/java/com/xingyao/card/core/DeviceCommandCoordinator.java'
value = read(path)
for old in ['.put("code", 202)', '.put("code", 4001)', '.put("code", 4003)',
            '.put("code", 501)', '.put("code", 9000)']:
    value = value.replace(old, '.put("code", 500)')
value = value.replace('failedCount == 0 ? 0 : 4001', 'failedCount == 0 ? 0 : 500')
value = value.replace('syncManager.syncEmployees(command)', 'syncManager.syncEmployees(false)')
value = value.replace('syncManager.syncFaces(command)', 'syncManager.syncFaces(false)')
value = value.replace('syncManager.syncFingers(command)', 'syncManager.syncFingers(false)')
value = value.replace('syncManager.syncAll(command)', 'syncManager.syncAll(false)')
value = value.replace('''                JSONObject responseData = BackendHttpClient.copyWithout(result, "snapshot");
                complete(command, baseResponse(command, command.optString("cmd", "sync") + "Resp")
                        .put("code", 0).put("status", "SUCCESS")
                        .put("data", responseData), true);
                JSONObject event = new JSONObject(responseData.toString())''', '''                JSONObject responseData = BackendHttpClient.copyWithout(result, "snapshot");
                complete(command, baseResponse(command, command.optString("cmd", "sync") + "Resp")
                        .put("code", 0).put("msg", "success"), true);
                JSONObject event = new JSONObject(responseData.toString())''')
value = value.replace('''.put("code", 500).put("status", "FAILED")
                            .put("msg", safeMessage(error))''', '''.put("code", 500)
                            .put("msg", safeMessage(error))''')
write(path, value)

# ---------------------------------------------------------------------------
# Service only wires lifecycle and dependencies.
# ---------------------------------------------------------------------------
path = 'app/src/main/java/com/xingyao/card/service/DeviceCoreService.java'
value = read(path)
value = value.replace('import com.xingyao.card.core.WebSocketConnectionManager;',
                      'import com.xingyao.card.core.BackendTransportManager;\nimport com.xingyao.card.core.DocumentedBackendService;\nimport com.xingyao.card.core.DeviceProvisioningManager;')
value = value.replace('private WebSocketConnectionManager backendManager;',
                      'private BackendTransportManager backendManager;')
value = value.replace('BackendHttpGateway httpGateway = new BackendHttpGateway(settingsRepository);',
                      '''BackendHttpGateway httpGateway = new BackendHttpGateway();
        httpGateway.configure(settings);
        DeviceProvisioningManager provisioningManager = new DeviceProvisioningManager(
                this, settingsRepository, httpGateway);
        DocumentedBackendService documentedBackendService = new DocumentedBackendService(
                this, httpGateway);''')
value = value.replace('''        backendManager = new WebSocketConnectionManager(this, settingsRepository,
                new WebSocketConnectionManager.Listener() {''', '''        backendManager = new BackendTransportManager(httpGateway,
                new BackendTransportManager.Listener() {''')
value = value.replace('''                    @Override public void onMessage(JSONObject message) {
                        if (holder[0] != null) holder[0].onBackendMessage(message);
                    }
                });''', '''                    @Override public void onMessage(JSONObject message) {
                        if (holder[0] != null) holder[0].onBackendMessage(message);
                    }
                    @Override public void onRuntimeToken(String token) {
                        if (holder[0] != null) holder[0].onRuntimeToken(token);
                    }
                });''')
value = value.replace('''            @Override public void configure(JSONObject value) { backendManager.configure(value); }
            @Override public void send(JSONObject payload) throws Exception { backendManager.send(payload); }''', '''            @Override public void configure(JSONObject value) { backendManager.configure(value); }
            @Override public void start() { backendManager.start(); }
            @Override public void stop() { backendManager.stop(); }
            @Override public void send(JSONObject payload) throws Exception { backendManager.send(payload); }''')
value = value.replace('''                syncManager, serialPort, backendPort, faceAiManager, httpGateway,
                new InboundCommandRepository(this), appControl);''', '''                syncManager, serialPort, backendPort, faceAiManager, httpGateway,
                provisioningManager, documentedBackendService,
                new InboundCommandRepository(this), appControl);''')
value = value.replace('''        serialManager.start();
        backendManager.start();
        faceAiManager.start();''', '''        serialManager.configure(settings);
        serialManager.start();
        faceAiManager.start();''')
value = value.replace('''        if (backendManager != null) backendManager.stop();
        if (serialManager != null) serialManager.stop();''', '''        if (serialManager != null) serialManager.stop();''')
write(path, value)

# ---------------------------------------------------------------------------
# Facade network actions are deferred; WebView never performs HTTP itself.
# ---------------------------------------------------------------------------
path = 'app/src/main/java/com/xingyao/card/core/NativeActionPolicy.java'
value = read(path)
value = value.replace('''        permissions.put("employee.delete", "employee.edit");''', '''        permissions.put("employee.delete", "employee.edit");
        permissions.put("employee.upsert", "employee.edit");
        permissions.put("employee.face.upsert", "biometric.register");
        permissions.put("employee.face.registered", "employee.view");
        permissions.put("fingerprint.uploadFeature", "biometric.register");
        permissions.put("logs.uploadBatch", "debug.command");
        permissions.put("firmware.download", "upgrade.firmware");''')
write(path, value)

path = 'app/src/main/java/com/xingyao/card/core/DeviceApplicationFacade.java'
value = read(path)
value = value.replace('import org.json.JSONObject;', 'import org.json.JSONArray;\nimport org.json.JSONObject;')
value = value.replace('''import com.xingyao.card.MainActivity;''', '''import com.xingyao.card.MainActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;''')
value = value.replace('''    private final NativeSettingsRepository settingsRepository;''', '''    private final NativeSettingsRepository settingsRepository;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();''')
value = value.replace('''                case "employee.delete":
                    return ActionResult.immediate(runtime().deleteEmployee(safePayload.optString("id", "")));
                case "app.restart":''', '''                case "employee.delete":
                    return deferred(requestId, "EMPLOYEE_DELETE_FAILED",
                            () -> runtime().deleteEmployee(safePayload.optString("id", "")));
                case "employee.upsert":
                    return deferred(requestId, "EMPLOYEE_UPSERT_FAILED",
                            () -> runtime().upsertEmployee(safePayload));
                case "employee.face.upsert":
                    return deferred(requestId, "FACE_UPLOAD_FAILED",
                            () -> runtime().upsertFaceFeature(safePayload));
                case "employee.face.registered":
                    return deferred(requestId, "FACE_REGISTERED_QUERY_FAILED",
                            () -> new JSONObject().put("employeeIds",
                                    runtime().registeredFaceEmployeeIds()));
                case "fingerprint.uploadFeature":
                    return deferred(requestId, "FINGERPRINT_UPLOAD_FAILED",
                            () -> runtime().uploadFingerprintFeature(safePayload));
                case "logs.uploadBatch":
                    return deferred(requestId, "LOG_BATCH_UPLOAD_FAILED",
                            () -> runtime().uploadLogsBatch(safePayload.optJSONArray("logs")));
                case "firmware.download":
                    return deferred(requestId, "FIRMWARE_DOWNLOAD_FAILED",
                            () -> runtime().downloadFirmware(
                                    safePayload.optString("firmwareId", ""),
                                    safePayload.optBoolean("resume", true)));
                case "app.restart":''')
# Insert deferred helpers before runtime().
marker = '    private DeviceDataLayer runtime() throws FacadeException {'
helpers = '''    private interface IoCall { JSONObject run() throws Exception; }

    private ActionResult deferred(String requestId, String errorCode, IoCall call) {
        ioExecutor.execute(() -> {
            try {
                JSONObject response = new JSONObject().put("type", "response")
                        .put("requestId", requestId).put("success", true)
                        .put("data", call.run());
                activity.sendBridgeResponse(response);
            } catch (Exception error) {
                try {
                    activity.sendBridgeResponse(new JSONObject().put("type", "response")
                            .put("requestId", requestId).put("success", false)
                            .put("code", errorCode).put("message", safeMessage(error)));
                } catch (Exception ignored) { }
            }
        });
        return ActionResult.deferred();
    }

'''
if marker not in value:
    raise RuntimeError('Facade runtime marker not found')
value = value.replace(marker, helpers + marker, 1)
write(path, value)

# Vue service functions call only the native facade.
path = 'uniapp/src/services/index.js'
value = read(path)
value = value.replace('''  async deleteEmployee(id) {
    const result = await nativeOrMock('employee.delete', { id }, () => mockService.deleteEmployee(id))
    await services.searchEmployees('')
    return result
  },''', '''  async deleteEmployee(id) {
    const result = await nativeOrMock('employee.delete', { id }, () => mockService.deleteEmployee(id), 20000)
    await services.searchEmployees('')
    return result
  },

  upsertEmployee: (employee) => nativeOrMock('employee.upsert', employee, async () => ({ employeeId: employee?.employeeId || 0, action: employee?.action || 'add' }), 20000),
  uploadFaceFeature: (payload) => nativeOrMock('employee.face.upsert', payload, async () => ({}), 20000),
  getRegisteredFaceEmployeeIds: () => nativeOrMock('employee.face.registered', {}, async () => ({ employeeIds: [] }), 20000),
  uploadFingerprintFeature: (payload) => nativeOrMock('fingerprint.uploadFeature', payload, async () => ({ uploadId: '' }), 20000),
  uploadLogsBatch: (logs) => nativeOrMock('logs.uploadBatch', { logs }, async () => ({ receivedCount: 0, failedCount: 0 }), 30000),
  downloadFirmware: (firmwareId, resume = true) => nativeOrMock('firmware.download', { firmwareId, resume }, async () => ({ filePath: '', bytesWritten: 0 }), 120000),''')
write(path, value)

# ---------------------------------------------------------------------------
# Tests for strict endpoints and request validation.
# ---------------------------------------------------------------------------
write('app/src/test/java/com/xingyao/card/core/BackendHttpClientTest.java', r'''package com.xingyao.card.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class BackendHttpClientTest {
    @Test(expected = IllegalArgumentException.class)
    public void blankBaseUrlIsRejected() {
        new BackendHttpClient("", "");
    }

    @Test(expected = IllegalArgumentException.class)
    public void schemeIsNeverGuessed() {
        new BackendHttpClient("api.example.com", "");
    }

    @Test
    public void explicitHttpUrlIsNormalizedWithoutTestFallback() {
        assertEquals("https://api.example.com/base",
                BackendHttpClient.normalizeBaseUrl("https://api.example.com/base/"));
    }
}
''')

write('app/src/test/java/com/xingyao/card/core/DocumentedBackendServiceTest.java', r'''package com.xingyao.card.core;

import android.test.mock.MockContext;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;

public class DocumentedBackendServiceTest {
    @Test
    public void employeeAddUsesExactDocumentedPathAndFields() throws Exception {
        FakeTransport transport = new FakeTransport();
        DocumentedBackendService service = new DocumentedBackendService(new TestContext(), transport);
        service.upsertEmployee(new JSONObject().put("action", "add")
                .put("employeeCode", "EMP001").put("employeeName", "张三")
                .put("cardNo", "CARD001"));
        assertEquals(BackendHttpGateway.EMPLOYEE_UPSERT, transport.path);
        assertEquals("add", transport.body.getString("action"));
        assertEquals("EMP001", transport.body.getString("employeeCode"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void employeeUpdateRequiresNumericEmployeeId() throws Exception {
        DocumentedBackendService service = new DocumentedBackendService(
                new TestContext(), new FakeTransport());
        service.upsertEmployee(new JSONObject().put("action", "update")
                .put("employeeId", "not-a-number"));
    }

    @Test
    public void faceFeatureUsesExactDocumentedPath() throws Exception {
        FakeTransport transport = new FakeTransport();
        DocumentedBackendService service = new DocumentedBackendService(new TestContext(), transport);
        service.upsertFaceFeature(new JSONObject().put("employeeId", 1)
                .put("faceFeature", "feature"));
        assertEquals(BackendHttpGateway.EMPLOYEE_FACE_UPSERT, transport.path);
        assertEquals(1L, transport.body.getLong("employeeId"));
    }

    private static final class FakeTransport implements DocumentedBackendService.Transport {
        String path;
        JSONObject body;
        @Override public JSONObject postData(String path, JSONObject body) {
            this.path = path; this.body = body; return new JSONObject();
        }
        @Override public JSONArray fetchArray(String path) { this.path = path; return new JSONArray(); }
        @Override public JSONObject uploadFaceImage(String userId, File file, String faceFeature) {
            return new JSONObject();
        }
        @Override public JSONObject downloadFirmware(String firmwareId, File target, long offset) {
            return new JSONObject();
        }
    }

    private static final class TestContext extends MockContext {
        @Override public android.content.Context getApplicationContext() { return this; }
        @Override public File getFilesDir() { return new File(System.getProperty("java.io.tmpdir"), "files"); }
        @Override public File getCacheDir() { return new File(System.getProperty("java.io.tmpdir"), "cache"); }
    }
}
''')

print('stage2 strict architecture and documented interfaces applied')
