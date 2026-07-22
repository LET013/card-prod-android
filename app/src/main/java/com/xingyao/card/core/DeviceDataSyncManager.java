package com.xingyao.card.core;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Pulls paged employee, face, and fingerprint data after a backend sync command. */
public final class DeviceDataSyncManager {
    private static final int EMPLOYEE_PAGE_SIZE = 50;
    private static final int FACE_PAGE_SIZE = 10;
    private static final int FINGER_PAGE_SIZE = 20;

    private final NativeSettingsRepository settingsRepository;
    private final DeviceDataRepository dataRepository;
    private final ArcFaceManager arcFaceManager;
    private final BackendHttpGateway httpGateway;

    public DeviceDataSyncManager(NativeSettingsRepository settingsRepository,
                                 DeviceDataRepository dataRepository,
                                 ArcFaceManager arcFaceManager,
                                 BackendHttpGateway httpGateway) {
        if (settingsRepository == null) throw new IllegalArgumentException("settingsRepository is required");
        if (dataRepository == null) throw new IllegalArgumentException("dataRepository is required");
        if (httpGateway == null) throw new IllegalArgumentException("httpGateway is required");
        this.settingsRepository = settingsRepository;
        this.dataRepository = dataRepository;
        this.arcFaceManager = arcFaceManager;
        this.httpGateway = httpGateway;
    }

    public JSONObject syncAll(JSONObject command) throws Exception {
        JSONObject settings = settingsRepository.load();
        String apiBaseUrl = httpBaseUrl(settings);
        String token = settings.optString("deviceToken", "");
        boolean full = command != null && (command.optBoolean("full", false) || command.optBoolean("fullSync", false));
        long employeeSince = full ? 0L : dataRepository.employeeSyncVersion();
        long faceSince = full ? 0L : dataRepository.faceSyncVersion();
        long fingerSince = full ? 0L : dataRepository.fingerSyncVersion();
        JSONObject deviceScope = new JSONObject().put("deviceCode", settings.optString("deviceCode", settings.optString("deviceId", "")));

        PageResult employees = pullPaged("/api/v1/employee/sync", "employees", employeeSince, EMPLOYEE_PAGE_SIZE, deviceScope);
        PageResult faces = pullPaged("/api/v1/employee/face/sync", "faceFeatures", faceSince, FACE_PAGE_SIZE,
                new JSONObject(deviceScope.toString()).put("includeFlags", settings.optInt("faceSyncIncludeFlags", 3)));
        PageResult fingers = pullPaged("/api/v1/employee/finger/sync", "fingerFeatures", fingerSince, FINGER_PAGE_SIZE, deviceScope);

        JSONArray normalizedEmployees = normalizeEmployees(employees.items, faces.items, fingers.items, apiBaseUrl);
        JSONArray normalizedFaces = normalizeFaceFeatures(faces.items, apiBaseUrl);
        JSONObject snapshot = dataRepository.saveSyncResult(
                normalizedEmployees, employees.syncVersion,
                normalizedFaces, faces.syncVersion,
                fingers.items, fingers.syncVersion);
        JSONObject faceTemplateImport = importFaceTemplates(normalizedFaces, normalizedEmployees, token);
        return new JSONObject()
                .put("code", 0)
                .put("msg", "success")
                .put("employeeCount", normalizedEmployees.length())
                .put("faceCount", normalizedFaces.length())
                .put("faceTemplateImport", faceTemplateImport)
                .put("fingerCount", fingers.items.length())
                .put("employeeSyncVersion", employees.syncVersion)
                .put("faceSyncVersion", faces.syncVersion)
                .put("fingerSyncVersion", fingers.syncVersion)
                .put("snapshot", snapshot);
    }

    public JSONObject syncEmployees(JSONObject command) throws Exception {
        JSONObject settings = settingsRepository.load();
        String apiBaseUrl = httpBaseUrl(settings);
        boolean full = command != null && (command.optBoolean("full", false) || command.optBoolean("fullSync", false));
        long employeeSince = full ? 0L : dataRepository.employeeSyncVersion();
        JSONObject deviceScope = new JSONObject().put("deviceCode", settings.optString("deviceCode", settings.optString("deviceId", "")));
        PageResult employees = pullPaged("/api/v1/employee/sync", "employees", employeeSince, EMPLOYEE_PAGE_SIZE, deviceScope);
        JSONObject current = dataRepository.snapshot();
        JSONArray faces = current.optJSONArray("faceFeatures");
        JSONArray fingers = current.optJSONArray("fingerFeatures");
        JSONArray normalizedEmployees = normalizeEmployees(employees.items,
                faces == null ? new JSONArray() : faces,
                fingers == null ? new JSONArray() : fingers,
                apiBaseUrl);
        JSONObject snapshot = dataRepository.saveSyncResult(normalizedEmployees, employees.syncVersion, null, 0L, null, 0L);
        return new JSONObject()
                .put("code", 0)
                .put("msg", "success")
                .put("employeeCount", normalizedEmployees.length())
                .put("employeeSyncVersion", employees.syncVersion)
                .put("snapshot", snapshot);
    }

    public JSONObject syncFaces(JSONObject command) throws Exception {
        JSONObject settings = settingsRepository.load();
        String apiBaseUrl = httpBaseUrl(settings);
        boolean full = command != null && (command.optBoolean("full", false) || command.optBoolean("fullSync", false));
        long faceSince = full ? 0L : dataRepository.faceSyncVersion();
        JSONObject deviceScope = new JSONObject().put("deviceCode", settings.optString("deviceCode", settings.optString("deviceId", "")))
                .put("includeFlags", command == null ? settings.optInt("faceSyncIncludeFlags", 3)
                        : command.optInt("includeFlags", settings.optInt("faceSyncIncludeFlags", 3)));
        PageResult faces = pullPaged("/api/v1/employee/face/sync", "faceFeatures", faceSince, FACE_PAGE_SIZE, deviceScope);
        JSONArray normalizedFaces = normalizeFaceFeatures(faces.items, apiBaseUrl);
        JSONObject current = dataRepository.snapshot();
        JSONArray employees = current.optJSONArray("employees");
        JSONObject snapshot = dataRepository.saveSyncResult(null, 0L, normalizedFaces, faces.syncVersion, null, 0L);
        JSONObject faceTemplateImport = importFaceTemplates(normalizedFaces,
                employees == null ? new JSONArray() : employees, settings.optString("deviceToken", ""));
        return new JSONObject()
                .put("code", 0)
                .put("msg", "success")
                .put("faceCount", normalizedFaces.length())
                .put("faceTemplateImport", faceTemplateImport)
                .put("faceSyncVersion", faces.syncVersion)
                .put("snapshot", snapshot);
    }

    public JSONObject syncFingers(JSONObject command) throws Exception {
        JSONObject settings = settingsRepository.load();
        boolean full = command != null && (command.optBoolean("full", false) || command.optBoolean("fullSync", false));
        long fingerSince = full ? 0L : dataRepository.fingerSyncVersion();
        JSONObject deviceScope = new JSONObject().put("deviceCode", settings.optString("deviceCode", settings.optString("deviceId", "")));
        PageResult fingers = pullPaged("/api/v1/employee/finger/sync", "fingerFeatures", fingerSince, FINGER_PAGE_SIZE, deviceScope);
        JSONObject snapshot = dataRepository.saveSyncResult(null, 0L, null, 0L, fingers.items, fingers.syncVersion);
        return new JSONObject()
                .put("code", 0)
                .put("msg", "success")
                .put("fingerCount", fingers.items.length())
                .put("fingerSyncVersion", fingers.syncVersion)
                .put("snapshot", snapshot);
    }

    private PageResult pullPaged(String path, String arrayKey, long lastSyncTime,
                                 int pageSize, JSONObject extra) throws Exception {
        JSONArray all = new JSONArray();
        long syncVersion = lastSyncTime;
        int page = 1;
        boolean hasMore;
        do {
            JSONObject body = new JSONObject()
                    .put("lastSyncTime", lastSyncTime)
                    .put("page", page)
                    .put("pageSize", pageSize);
            if (extra != null) {
                java.util.Iterator<String> keys = extra.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    body.put(key, extra.opt(key));
                }
            }
            JSONObject data = httpGateway.postData(path, body);
            JSONArray pageItems = data.optJSONArray(arrayKey);
            if (pageItems != null) {
                for (int index = 0; index < pageItems.length(); index++) all.put(pageItems.getJSONObject(index));
            }
            syncVersion = parseLong(data.opt("syncVersion"), syncVersion);
            hasMore = data.optBoolean("hasMore", false);
            page++;
        } while (hasMore && page <= 200);
        return new PageResult(all, syncVersion);
    }

    private JSONArray normalizeEmployees(JSONArray employees, JSONArray faces, JSONArray fingers, String apiBaseUrl) throws JSONException {
        JSONArray result = new JSONArray();
        for (int index = 0; index < employees.length(); index++) {
            JSONObject source = employees.getJSONObject(index);
            String employeeId = source.optString("employeeId");
            JSONObject item = new JSONObject(source.toString())
                    .put("id", employeeId.isEmpty() ? source.optString("employeeCode", "EMP-" + index) : employeeId)
                    .put("employeeId", employeeId)
                    .put("employeeCode", source.optString("employeeCode", employeeId))
                    .put("employeeName", source.optString("employeeName", source.optString("name", "")))
                    .put("avatarUrl", firstFaceImageForEmployee(faces, employeeId, apiBaseUrl))
                    .put("faceRegistered", "1".equals(source.optString("faceRegistered")) || hasFeature(faces, "employeeId", employeeId))
                    .put("fingerprintRegistered", "1".equals(source.optString("fingerRegistered")) || hasFeature(fingers, "employeeId", employeeId))
                    .put("enabled", !"1".equals(source.optString("status")));
            result.put(item);
        }
        return result;
    }

    private JSONArray normalizeFaceFeatures(JSONArray faces, String apiBaseUrl) throws JSONException {
        JSONArray result = new JSONArray();
        for (int index = 0; index < faces.length(); index++) {
            JSONObject item = new JSONObject(faces.getJSONObject(index).toString());
            String image = item.optString("faceImage", "");
            if (!image.isEmpty()) item.put("faceImageUrl", absoluteUrl(apiBaseUrl, image));
            result.put(item);
        }
        return result;
    }

    private static boolean hasFeature(JSONArray items, String key, String value) {
        if (value == null || value.isEmpty()) return false;
        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.optJSONObject(index);
            if (item != null && value.equals(item.optString(key))) return true;
        }
        return false;
    }

    private static String firstFaceImageForEmployee(JSONArray faces, String employeeId, String apiBaseUrl) {
        if (employeeId == null || employeeId.isEmpty()) return "/static/avatars/employee-1.jpg";
        for (int index = 0; index < faces.length(); index++) {
            JSONObject item = faces.optJSONObject(index);
            if (item == null || !employeeId.equals(item.optString("employeeId"))) continue;
            String image = item.optString("faceImage", "");
            if (!image.isEmpty()) return absoluteUrl(apiBaseUrl, image);
        }
        return "/static/avatars/employee-1.jpg";
    }

    private JSONObject importFaceTemplates(JSONArray faces, JSONArray employees, String token) throws JSONException {
        JSONObject result = new JSONObject().put("enabled", arcFaceManager != null)
                .put("successCount", 0)
                .put("failedCount", 0)
                .put("failures", new JSONArray());
        if (arcFaceManager == null || faces == null || faces.length() == 0) return result;
        try {
            arcFaceManager.awaitReady(8000L);
        } catch (Exception error) {
            return result.put("failedCount", faces.length())
                    .put("message", "虹软人脸引擎未就绪：" + safeMessage(error));
        }
        int successCount = 0;
        JSONArray failures = new JSONArray();
        for (int index = 0; index < faces.length(); index++) {
            JSONObject face = faces.optJSONObject(index);
            if (face == null) continue;
            String status = face.optString("status", "");
            if ("1".equals(status) || "DELETED".equalsIgnoreCase(status) || "DISABLED".equalsIgnoreCase(status)) continue;
            String employeeId = face.optString("employeeId", face.optString("employeeCode", ""));
            if (employeeId.isEmpty()) employeeId = face.optString("faceId", "");
            String imageUrl = face.optString("faceImageUrl", face.optString("faceImage", ""));
            String employeeName = employeeName(employees, employeeId);
            String faceFeature = normalizeFaceFeatureValue(face);
            String imageBase64 = normalizeFaceImageBase64(face);
            if (imageBase64.isEmpty()) {
                String rawImage = face.optString("faceImage", "").trim();
                if (isLikelyBase64(rawImage) && !rawImage.startsWith("/")) {
                    imageBase64 = rawImage;
                    imageUrl = "";
                }
            }
            if (employeeId.isEmpty() || (faceFeature.isEmpty() && imageBase64.isEmpty() && imageUrl.isEmpty())) {
                failures.put(new JSONObject().put("employeeId", employeeId)
                        .put("faceId", face.optString("faceId", ""))
                        .put("message", "人脸同步数据缺少 employeeId、faceFeature 或 faceImage"));
                continue;
            }
            try {
                if (!faceFeature.isEmpty()) {
                    arcFaceManager.enrollFeature(employeeId, employeeName, faceFeature, imageUrl);
                } else if (!imageBase64.isEmpty()) {
                    byte[] image = decodeBase64(imageBase64);
                    arcFaceManager.enrollImage(employeeId, employeeName, image, imageUrl);
                } else {
                    byte[] image = httpGateway.downloadBytes(imageUrl, true);
                    arcFaceManager.enrollImage(employeeId, employeeName, image, imageUrl);
                }
                successCount++;
            } catch (Exception error) {
                if (faceFeature.isEmpty() && imageBase64.isEmpty() && !imageUrl.isEmpty()) {
                    try {
                        byte[] image = httpGateway.downloadBytes(imageUrl, false);
                        arcFaceManager.enrollImage(employeeId, employeeName, image, imageUrl);
                        successCount++;
                        continue;
                    } catch (Exception ignored) {
                        // Retry without bearer token for public image endpoints.
                    }
                }
                failures.put(new JSONObject().put("employeeId", employeeId)
                        .put("faceId", face.optString("faceId", ""))
                        .put("imageUrl", imageUrl)
                        .put("message", safeMessage(error)));
            }
        }
        return result.put("successCount", successCount)
                .put("failedCount", failures.length())
                .put("failures", failures)
                .put("message", failures.length() == 0 ? "人脸模板已导入" : "部分人脸模板导入失败，将在后续同步重试");
    }

    private static String employeeName(JSONArray employees, String employeeId) {
        if (employees == null || employeeId == null) return employeeId == null ? "" : employeeId;
        for (int index = 0; index < employees.length(); index++) {
            JSONObject employee = employees.optJSONObject(index);
            if (employee == null || !employeeId.equals(employee.optString("employeeId", employee.optString("id", "")))) continue;
            return employee.optString("employeeName", employee.optString("name", employeeId));
        }
        return employeeId;
    }

    private static String normalizeFaceFeatureValue(JSONObject face) {
        String value = firstString(face, "faceFeature", "feature", "featureData", "template", "faceTemplate");
        if (value.startsWith("data:")) {
            int comma = value.indexOf(',');
            if (comma >= 0) value = value.substring(comma + 1);
        }
        return value.replaceAll("\\s+", "");
    }

    private static String normalizeFaceImageBase64(JSONObject face) {
        String value = firstString(face, "faceImageBase64", "imageBase64", "photoBase64");
        if (value.startsWith("data:")) {
            int comma = value.indexOf(',');
            if (comma >= 0) value = value.substring(comma + 1);
        }
        return value.replaceAll("\\s+", "");
    }

    private static String firstString(JSONObject source, String... keys) {
        if (source == null || keys == null) return "";
        for (String key : keys) {
            String value = source.optString(key, "").trim();
            if (!value.isEmpty()) return value;
        }
        return "";
    }

    private static boolean isLikelyBase64(String value) {
        if (value == null) return false;
        String normalized = value.replaceAll("\\s+", "");
        if (normalized.length() < 128 || normalized.length() % 4 != 0) return false;
        return normalized.matches("[A-Za-z0-9+/=]+") || normalized.startsWith("data:");
    }

    private static byte[] decodeBase64(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.startsWith("data:")) {
            int comma = normalized.indexOf(',');
            if (comma >= 0) normalized = normalized.substring(comma + 1);
        }
        return Base64.decode(normalized.replaceAll("\\s+", ""), Base64.DEFAULT);
    }

    private static String httpBaseUrl(JSONObject settings) {
        String value = settings.optString("apiBaseUrl", settings.optString("serverAddress", ""));
        return BackendHttpClient.normalizeBaseUrl(value);
    }

    private static String absoluteUrl(String baseUrl, String value) {
        String raw = value == null ? "" : value.trim();
        if (raw.isEmpty() || raw.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) return raw;
        return baseUrl + (raw.startsWith("/") ? raw : "/" + raw);
    }

    private static long parseLong(Object value, long fallback) {
        try { return Long.parseLong(String.valueOf(value)); }
        catch (Exception ignored) { return fallback; }
    }

    private static String safeMessage(Throwable error) {
        if (error == null) return "unknown";
        String value = error.getMessage();
        return value == null || value.trim().isEmpty() ? error.getClass().getSimpleName() : value;
    }

    private static final class PageResult {
        final JSONArray items;
        final long syncVersion;
        PageResult(JSONArray items, long syncVersion) {
            this.items = items == null ? new JSONArray() : items;
            this.syncVersion = syncVersion;
        }
    }
}
