package com.xingyao.card.core;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** V4.1 employee/face/fingerprint synchronization coordinator. */
public final class DeviceDataSyncManager {
    private static final int EMPLOYEE_PAGE_SIZE = 50;
    private static final int FACE_PAGE_SIZE = 10;
    private static final int FINGER_PAGE_SIZE = 20;
    private static final int MAX_PAGES = 200;

    private final NativeSettingsRepository settingsRepository;
    private final DeviceDataRepository dataRepository;
    private final FaceAiManager faceAiManager;
    private final BackendHttpGateway httpGateway;

    public DeviceDataSyncManager(NativeSettingsRepository settingsRepository,
                                 DeviceDataRepository dataRepository,
                                 FaceAiManager faceAiManager,
                                 BackendHttpGateway httpGateway) {
        if (settingsRepository == null) throw new IllegalArgumentException("settingsRepository is required");
        if (dataRepository == null) throw new IllegalArgumentException("dataRepository is required");
        if (faceAiManager == null) throw new IllegalArgumentException("faceAiManager is required");
        if (httpGateway == null) throw new IllegalArgumentException("httpGateway is required");
        this.settingsRepository = settingsRepository;
        this.dataRepository = dataRepository;
        this.faceAiManager = faceAiManager;
        this.httpGateway = httpGateway;
    }

    public synchronized JSONObject syncAll(boolean full) throws Exception {
        JSONObject settings = settingsRepository.load();
        String apiBaseUrl = BackendHttpGateway.baseUrl(settings);

        PageResult employeePage = pullPaged(BackendHttpGateway.EMPLOYEE_SYNC, "employees",
                full ? 0L : dataRepository.employeeSyncVersion(), EMPLOYEE_PAGE_SIZE,
                null, "deletedEmployeeIds");
        PageResult facePage = pullPaged(BackendHttpGateway.FACE_SYNC, "faceFeatures",
                full ? 0L : dataRepository.faceSyncVersion(), FACE_PAGE_SIZE,
                new JSONObject().put("includeFlags",
                        settings.optInt("faceSyncIncludeFlags", 3)), null);
        PageResult fingerPage = pullPaged(BackendHttpGateway.FINGER_SYNC, "fingerFeatures",
                full ? 0L : dataRepository.fingerSyncVersion(), FINGER_PAGE_SIZE,
                null, null);

        JSONArray employees = normalizeEmployees(employeePage.items, facePage.items,
                fingerPage.items, apiBaseUrl);
        JSONArray faces = normalizeFaceFeatures(facePage.items, apiBaseUrl);
        deleteEmployeeTemplates(employeePage.deletedIds);
        dataRepository.applyEmployeeSync(employees, employeePage.deletedIds, full,
                employeePage.syncVersion);
        dataRepository.stageFaceSync(faces, full, facePage.syncVersion);
        JSONObject faceImport = importFaceTemplates(faces, dataRepository.employees(), full);
        if (faceImport.optInt("failedCount", 0) == 0) {
            dataRepository.markFaceApplied(facePage.syncVersion);
        }
        dataRepository.applyFingerSync(fingerPage.items, full, fingerPage.syncVersion);

        JSONObject snapshot = dataRepository.snapshot();
        return new JSONObject()
                .put("code", 0)
                .put("msg", "success")
                .put("full", full)
                .put("employeeCount", employees.length())
                .put("deletedEmployeeCount", employeePage.deletedIds.length())
                .put("faceCount", faces.length())
                .put("faceTemplateImport", faceImport)
                .put("fingerCount", fingerPage.items.length())
                .put("employeeSyncVersion", employeePage.syncVersion)
                .put("faceFetchedVersion", facePage.syncVersion)
                .put("faceAppliedVersion", snapshot.optLong("faceAppliedVersion", 0L))
                .put("fingerSyncVersion", fingerPage.syncVersion)
                .put("snapshot", snapshot);
    }

    public synchronized JSONObject syncEmployees(boolean full) throws Exception {
        JSONObject settings = settingsRepository.load();
        PageResult page = pullPaged(BackendHttpGateway.EMPLOYEE_SYNC, "employees",
                full ? 0L : dataRepository.employeeSyncVersion(), EMPLOYEE_PAGE_SIZE,
                null, "deletedEmployeeIds");
        JSONObject current = dataRepository.snapshot();
        JSONArray employees = normalizeEmployees(page.items,
                safeArray(current, "faceFeatures"), safeArray(current, "fingerFeatures"),
                BackendHttpGateway.baseUrl(settings));
        deleteEmployeeTemplates(page.deletedIds);
        JSONObject snapshot = dataRepository.applyEmployeeSync(employees, page.deletedIds,
                full, page.syncVersion);
        return new JSONObject().put("code", 0).put("msg", "success")
                .put("full", full)
                .put("employeeCount", employees.length())
                .put("deletedEmployeeCount", page.deletedIds.length())
                .put("employeeSyncVersion", page.syncVersion)
                .put("snapshot", snapshot);
    }

    public synchronized JSONObject syncFaces(boolean full) throws Exception {
        JSONObject settings = settingsRepository.load();
        JSONObject scope = new JSONObject().put("includeFlags",
                settings.optInt("faceSyncIncludeFlags", 3));
        PageResult page = pullPaged(BackendHttpGateway.FACE_SYNC, "faceFeatures",
                full ? 0L : dataRepository.faceSyncVersion(), FACE_PAGE_SIZE, scope, null);
        JSONArray faces = normalizeFaceFeatures(page.items, BackendHttpGateway.baseUrl(settings));
        dataRepository.stageFaceSync(faces, full, page.syncVersion);
        JSONObject faceImport = importFaceTemplates(faces, dataRepository.employees(), full);
        JSONObject snapshot = faceImport.optInt("failedCount", 0) == 0
                ? dataRepository.markFaceApplied(page.syncVersion)
                : dataRepository.snapshot();
        return new JSONObject().put("code", 0).put("msg", "success")
                .put("full", full)
                .put("faceCount", faces.length())
                .put("faceTemplateImport", faceImport)
                .put("faceFetchedVersion", page.syncVersion)
                .put("faceAppliedVersion", snapshot.optLong("faceAppliedVersion", 0L))
                .put("snapshot", snapshot);
    }

    public synchronized JSONObject syncFingers(boolean full) throws Exception {
        JSONObject settings = settingsRepository.load();
        PageResult page = pullPaged(BackendHttpGateway.FINGER_SYNC, "fingerFeatures",
                full ? 0L : dataRepository.fingerSyncVersion(), FINGER_PAGE_SIZE,
                null, null);
        JSONObject snapshot = dataRepository.applyFingerSync(page.items, full, page.syncVersion);
        return new JSONObject().put("code", 0).put("msg", "success")
                .put("full", full)
                .put("fingerCount", page.items.length())
                .put("fingerSyncVersion", page.syncVersion)
                .put("fingerprintHardwareApplied", false)
                .put("message", "指纹数据已缓存；员工级外接指纹模块尚未接入")
                .put("snapshot", snapshot);
    }

    private PageResult pullPaged(String path, String arrayKey, long lastSyncTime,
                                 int pageSize, JSONObject extra, String deletedKey)
            throws Exception {
        JSONArray all = new JSONArray();
        JSONArray deleted = new JSONArray();
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
            appendObjects(all, data.optJSONArray(arrayKey));
            if (deletedKey != null) appendValues(deleted, data.optJSONArray(deletedKey));
            syncVersion = parseLong(data.opt("syncVersion"), syncVersion);
            hasMore = data.optBoolean("hasMore", false);
            if (hasMore && page >= MAX_PAGES) {
                throw new IllegalStateException("同步分页超过安全上限 " + MAX_PAGES + "，未提交本次游标");
            }
            page++;
        } while (hasMore);
        return new PageResult(all, deleted, syncVersion);
    }

    private JSONArray normalizeEmployees(JSONArray employees, JSONArray faces, JSONArray fingers,
                                         String apiBaseUrl) throws JSONException {
        JSONArray result = new JSONArray();
        for (int index = 0; index < employees.length(); index++) {
            JSONObject source = employees.optJSONObject(index);
            if (source == null) continue;
            String employeeId = source.optString("employeeId", "").trim();
            JSONObject item = new JSONObject(source.toString());
            if (employeeId.isEmpty()) continue;
            item.put("id", employeeId).put("employeeId", employeeId);
            if (source.has("employeeName")) item.put("employeeName", source.optString("employeeName", ""));
            String avatar = firstFaceImageForEmployee(faces, employeeId, apiBaseUrl);
            if (!avatar.isEmpty()) item.put("avatarUrl", avatar);
            if (source.has("faceRegistered")) item.put("faceRegistered",
                    truthy(source.opt("faceRegistered")) || hasFeature(faces, employeeId));
            if (source.has("fingerRegistered")) item.put("fingerprintRegistered",
                    truthy(source.opt("fingerRegistered")) || hasFeature(fingers, employeeId));
            if (source.has("status")) item.put("enabled", !isDisabled(source));
            result.put(item);
        }
        return result;
    }

    private JSONArray normalizeFaceFeatures(JSONArray faces, String apiBaseUrl) throws JSONException {
        JSONArray result = new JSONArray();
        for (int index = 0; index < faces.length(); index++) {
            JSONObject source = faces.optJSONObject(index);
            if (source == null) continue;
            JSONObject item = new JSONObject(source.toString());
            String image = item.optString("faceImage", "").trim();
            if (!image.isEmpty() && !isLikelyBase64(image)) {
                item.put("faceImageUrl", absoluteUrl(apiBaseUrl, image));
            }
            result.put(item);
        }
        return result;
    }

    private JSONObject importFaceTemplates(JSONArray faces, JSONArray employees, boolean full)
            throws JSONException {
        JSONObject result = new JSONObject().put("enabled", faceAiManager != null)
                .put("successCount", 0).put("failedCount", 0)
                .put("deletedCount", 0).put("failures", new JSONArray());
        if (faceAiManager == null) {
            return result.put("failedCount", faces == null ? 0 : faces.length())
                    .put("message", "FaceAISDK未接入");
        }
        try { faceAiManager.awaitReady(8000L); }
        catch (Exception error) {
            return result.put("failedCount", faces == null ? 0 : faces.length())
                    .put("message", "FaceAISDK未就绪：" + safeMessage(error));
        }

        int successCount = 0;
        int deletedCount = 0;
        JSONArray failures = new JSONArray();
        for (int index = 0; faces != null && index < faces.length(); index++) {
            JSONObject face = faces.optJSONObject(index);
            if (face == null) continue;
            String employeeId = face.optString("employeeId", "").trim();
            if (employeeId.isEmpty()) {
                failures.put(failure(face, "人脸同步数据缺少employeeId"));
                continue;
            }
            if (isDisabled(face)) {
                try {
                    faceAiManager.deleteTemplate(employeeId);
                    deletedCount++;
                } catch (Exception error) {
                    failures.put(failure(face, safeMessage(error)));
                }
                continue;
            }
            String imageUrl = face.optString("faceImageUrl", face.optString("faceImage", ""));
            String employeeName = employeeName(employees, employeeId);
            String feature = normalizeFaceFeatureValue(face);
            String imageBase64 = normalizeFaceImageBase64(face);
            if (imageBase64.isEmpty()) {
                String rawImage = face.optString("faceImage", "").trim();
                if (isLikelyBase64(rawImage)) {
                    imageBase64 = rawImage;
                    imageUrl = "";
                }
            }
            if (feature.isEmpty() && imageBase64.isEmpty() && imageUrl.isEmpty()) {
                failures.put(failure(face, "人脸同步数据缺少faceFeature或faceImage"));
                continue;
            }
            try {
                if (!feature.isEmpty()) {
                    faceAiManager.enrollFeature(employeeId, employeeName, feature, imageUrl);
                } else if (!imageBase64.isEmpty()) {
                    faceAiManager.enrollImage(employeeId, employeeName,
                            decodeBase64(imageBase64), imageUrl);
                } else {
                    faceAiManager.enrollImage(employeeId, employeeName,
                            httpGateway.downloadBytes(imageUrl, true), imageUrl);
                }
                successCount++;
            } catch (Exception error) {
                failures.put(failure(face, safeMessage(error)).put("imageUrl", imageUrl));
            }
        }
        return result.put("successCount", successCount)
                .put("deletedCount", deletedCount)
                .put("failedCount", failures.length())
                .put("failures", failures)
                .put("message", failures.length() == 0
                        ? "人脸模板已应用" : "部分人脸模板未应用，游标不会推进");
    }

    private void deleteEmployeeTemplates(JSONArray deletedEmployeeIds) {
        if (deletedEmployeeIds == null) return;
        for (int index = 0; index < deletedEmployeeIds.length(); index++) {
            String id = String.valueOf(deletedEmployeeIds.opt(index)).trim();
            if (id.isEmpty()) continue;
            faceAiManager.deleteTemplate(id);
        }
    }

    private static JSONObject failure(JSONObject source, String message) throws JSONException {
        return new JSONObject()
                .put("employeeId", source == null ? "" : source.optString("employeeId", ""))
                .put("faceId", source == null ? "" : source.optString("faceId", ""))
                .put("message", message == null ? "unknown" : message);
    }



    private static boolean isDisabled(JSONObject item) {
        String status = item == null ? "" : item.optString("status", "");
        return "1".equals(status);
    }

    private static boolean truthy(Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        String text = String.valueOf(value).trim();
        return "1".equals(text) || "true".equalsIgnoreCase(text);
    }

    private static boolean hasFeature(JSONArray items, String employeeId) {
        if (employeeId == null || employeeId.isEmpty()) return false;
        for (int index = 0; items != null && index < items.length(); index++) {
            JSONObject item = items.optJSONObject(index);
            if (item != null && !isDisabled(item)
                    && employeeId.equals(item.optString("employeeId", ""))) return true;
        }
        return false;
    }

    private static String firstFaceImageForEmployee(JSONArray faces, String employeeId,
                                                    String apiBaseUrl) {
        if (employeeId == null || employeeId.isEmpty()) return "";
        for (int index = 0; faces != null && index < faces.length(); index++) {
            JSONObject item = faces.optJSONObject(index);
            if (item == null || isDisabled(item)
                    || !employeeId.equals(item.optString("employeeId", ""))) continue;
            String image = item.optString("faceImage", "").trim();
            if (!image.isEmpty() && !isLikelyBase64(image)) return absoluteUrl(apiBaseUrl, image);
        }
        return "";
    }

    private static String employeeName(JSONArray employees, String employeeId) {
        for (int index = 0; employees != null && index < employees.length(); index++) {
            JSONObject employee = employees.optJSONObject(index);
            if (employee == null) continue;
            String id = employee.optString("employeeId", employee.optString("id", ""));
            if (employeeId.equals(id)) {
                return employee.optString("employeeName", employee.optString("name", employeeId));
            }
        }
        return employeeId;
    }

    private static String normalizeFaceFeatureValue(JSONObject face) {
        if (face == null) return "";
        Object raw = face.opt("faceFeature");
        if (raw == null || raw == JSONObject.NULL) return "";
        return stripDataPrefix(String.valueOf(raw)).replaceAll("\\s+", "");
    }

    private static String normalizeFaceImageBase64(JSONObject face) {
        if (face == null) return "";
        return stripDataPrefix(face.optString("faceImageBase64", ""))
                .replaceAll("\\s+", "");
    }


    private static String stripDataPrefix(String value) {
        String result = value == null ? "" : value.trim();
        if (result.startsWith("data:")) {
            int comma = result.indexOf(',');
            if (comma >= 0) result = result.substring(comma + 1);
        }
        return result;
    }

    private static boolean isLikelyBase64(String value) {
        if (value == null) return false;
        String normalized = stripDataPrefix(value).replaceAll("\\s+", "");
        return normalized.length() >= 128
                && normalized.matches("[A-Za-z0-9+/=_-]+");
    }

    private static byte[] decodeBase64(String value) {
        return Base64.decode(stripDataPrefix(value).replaceAll("\\s+", ""), Base64.DEFAULT);
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

    private static JSONArray safeArray(JSONObject source, String key) {
        JSONArray value = source == null ? null : source.optJSONArray(key);
        return value == null ? new JSONArray() : value;
    }

    private static void appendObjects(JSONArray target, JSONArray source) throws JSONException {
        for (int index = 0; source != null && index < source.length(); index++) {
            JSONObject item = source.optJSONObject(index);
            if (item != null) target.put(item);
        }
    }

    private static void appendValues(JSONArray target, JSONArray source) {
        for (int index = 0; source != null && index < source.length(); index++) {
            target.put(source.opt(index));
        }
    }

    private static String safeMessage(Throwable error) {
        if (error == null) return "unknown";
        String value = error.getMessage();
        return value == null || value.trim().isEmpty()
                ? error.getClass().getSimpleName() : value;
    }

    private static final class PageResult {
        final JSONArray items;
        final JSONArray deletedIds;
        final long syncVersion;

        PageResult(JSONArray items, JSONArray deletedIds, long syncVersion) {
            this.items = items == null ? new JSONArray() : items;
            this.deletedIds = deletedIds == null ? new JSONArray() : deletedIds;
            this.syncVersion = syncVersion;
        }
    }
}
