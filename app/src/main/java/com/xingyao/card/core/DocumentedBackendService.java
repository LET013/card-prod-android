package com.xingyao.card.core;

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

    private final File filesDir;
    private final File cacheDir;
    private final Transport transport;

    public DocumentedBackendService(Context context, Transport transport) {
        this(requireContext(context).getFilesDir(), requireContext(context).getCacheDir(), transport);
    }

    DocumentedBackendService(File filesDir, File cacheDir, Transport transport) {
        if (filesDir == null || cacheDir == null) {
            throw new IllegalArgumentException("APP私有目录不能为空");
        }
        if (transport == null) throw new IllegalArgumentException("transport is required");
        this.filesDir = filesDir;
        this.cacheDir = cacheDir;
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
        copyOptional(source, body, "cardNo", "phone", "email", "department", "position");
        if (source.has("deptId") && !source.isNull("deptId")) {
            body.put("deptId", requiredLong(source, "deptId"));
        }
        if (source.has("status") && !source.isNull("status")) {
            body.put("status", status(source.optString("status", "")));
        }
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
        copyOptional(source, body, "faceImagePath");
        if (source.has("deviceId") && !source.isNull("deviceId")) {
            body.put("deviceId", requiredLong(source, "deviceId"));
        }
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

    public JSONObject uploadFaceImage(String userId, String filePath,
                                      String faceFeature) throws Exception {
        return uploadFaceImage(userId, new File(requiredString(filePath, "filePath")),
                faceFeature);
    }

    public JSONObject downloadFirmware(String firmwareId, boolean resume) throws Exception {
        String id = requiredString(firmwareId, "firmwareId");
        File directory = new File(filesDir, "firmware");
        File target = new File(directory, id + ".bin");
        long offset = resume && target.isFile() ? target.length() : 0L;
        return transport.downloadFirmware(id, target, offset);
    }

    private JSONObject cardBody(String cardNo, int slotId, String authType) throws JSONException {
        if (slotId < 1) throw new IllegalArgumentException("slotId必须大于0");
        return new JSONObject().put("cardNo", requiredString(cardNo, "cardNo"))
                .put("slotId", slotId)
                .put("authType", authType(authType));
    }

    private void requirePrivateFile(File file) throws Exception {
        if (file == null || !file.isFile()) throw new IllegalArgumentException("人脸图片文件不存在");
        String candidate = file.getCanonicalPath();
        String files = filesDir.getCanonicalPath() + File.separator;
        String cache = cacheDir.getCanonicalPath() + File.separator;
        if (!(candidate.startsWith(files) || candidate.startsWith(cache))) {
            throw new SecurityException("只允许上传APP私有目录中的人脸图片");
        }
    }

    private static Context requireContext(Context context) {
        if (context == null) throw new IllegalArgumentException("context is required");
        Context app = context.getApplicationContext();
        return app == null ? context : app;
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

    private static String status(String value) {
        String result = requiredString(value, "status");
        if (!("0".equals(result) || "1".equals(result))) {
            throw new IllegalArgumentException("status必须为0或1");
        }
        return result;
    }

    private static String authType(String value) {
        String result = requiredString(value, "authType").toUpperCase();
        if (!("CARD".equals(result) || "FACE".equals(result)
                || "FINGERPRINT".equals(result))) {
            throw new IllegalArgumentException("authType必须为CARD、FACE或FINGERPRINT");
        }
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
