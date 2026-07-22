package com.xingyao.card.core;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Android-owned employee, face and fingerprint Map with restart backing storage. */
public final class DeviceDataRepository {
    private static final String PREFS = "device_sync_data";
    private static final String KEY_EMPLOYEES = "employees";
    private static final String KEY_FACE_FEATURES = "faceFeatures";
    private static final String KEY_FINGER_FEATURES = "fingerFeatures";
    private static final String KEY_EMPLOYEE_SYNC_VERSION = "employeeSyncVersion";
    private static final String KEY_FACE_FETCHED_VERSION = "faceFetchedVersion";
    private static final String KEY_FACE_APPLIED_VERSION = "faceAppliedVersion";
    private static final String KEY_LEGACY_FACE_SYNC_VERSION = "faceSyncVersion";
    private static final String KEY_FINGER_SYNC_VERSION = "fingerSyncVersion";
    private static final String KEY_UPDATED_AT = "updatedAt";

    private final SharedPreferences preferences;
    private final LinkedHashMap<String, JSONObject> employees = new LinkedHashMap<>();
    private final LinkedHashMap<String, JSONObject> faceFeatures = new LinkedHashMap<>();
    private final LinkedHashMap<String, JSONObject> fingerFeatures = new LinkedHashMap<>();
    private long employeeSyncVersion;
    private long faceFetchedVersion;
    private long faceAppliedVersion;
    private long fingerSyncVersion;
    private long updatedAt;

    public DeviceDataRepository(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        loadBackingStore();
    }

    public synchronized JSONArray employees() throws JSONException {
        return mapValues(employees);
    }

    public synchronized JSONArray searchEmployees(String query) throws JSONException {
        String keyword = query == null ? "" : query.trim().toLowerCase(Locale.US);
        JSONArray result = new JSONArray();
        for (JSONObject employee : employees.values()) {
            if (keyword.isEmpty() || contains(employee, keyword)) result.put(copy(employee));
        }
        return result;
    }

    /** Returns the canonical employeeId that was removed, or an empty string when no match exists. */
    public synchronized String deleteEmployee(String id) throws JSONException {
        String target = id == null ? "" : id.trim();
        if (target.isEmpty()) return "";
        String matchedKey = findEmployeeKey(target);
        if (matchedKey == null) return "";
        JSONObject removed = employees.remove(matchedKey);
        String employeeId = removed == null ? matchedKey
                : removed.optString("employeeId", matchedKey);
        removeFeaturesForEmployee(faceFeatures, employeeId);
        removeFeaturesForEmployee(fingerFeatures, employeeId);
        touchAndPersist();
        return employeeId;
    }

    public synchronized void markFaceRegistered(String employeeId, String employeeName,
                                                 boolean registered) throws JSONException {
        markBiometric(employeeId, employeeName, "faceRegistered", registered);
    }

    /** System biometric authorization is not employee-level fingerprint registration. */
    public synchronized void markSystemBiometricAuthorized(String employeeId, String employeeName,
                                                            boolean authorized) throws JSONException {
        String id = employeeId == null ? "" : employeeId.trim();
        if (id.isEmpty()) return;
        JSONObject employee = employees.get(id);
        if (employee == null) return;
        employee = copy(employee);
        employee.put("systemBiometricAuthorized", authorized)
                .put("systemBiometricScope", "DEVICE_USER");
        if (employee.optString("employeeName", "").isEmpty()
                && employeeName != null && !employeeName.trim().isEmpty()) {
            employee.put("employeeName", employeeName.trim());
        }
        employees.put(id, employee);
        touchAndPersist();
    }

    public synchronized JSONObject applyEmployeeSync(JSONArray items, JSONArray deletedEmployeeIds,
                                                       boolean full, long syncVersion)
            throws JSONException {
        if (full) employees.clear();
        upsertMap(employees, items, "employeeId", "employeeCode", "id");
        if (deletedEmployeeIds != null) {
            for (int index = 0; index < deletedEmployeeIds.length(); index++) {
                String id = String.valueOf(deletedEmployeeIds.opt(index)).trim();
                String key = findEmployeeKey(id);
                if (key == null) continue;
                JSONObject removed = employees.remove(key);
                String employeeId = removed == null ? id : removed.optString("employeeId", id);
                removeFeaturesForEmployee(faceFeatures, employeeId);
                removeFeaturesForEmployee(fingerFeatures, employeeId);
            }
        }
        if (syncVersion > 0L) employeeSyncVersion = syncVersion;
        touchAndPersist();
        return snapshot();
    }

    /** Stores fetched face records but deliberately does not advance the applied cursor. */
    public synchronized JSONObject stageFaceSync(JSONArray items, boolean full, long fetchedVersion)
            throws JSONException {
        if (full) faceFeatures.clear();
        applyFeatureDelta(faceFeatures, items, "faceId");
        if (fetchedVersion > 0L) faceFetchedVersion = fetchedVersion;
        touchAndPersist();
        return snapshot();
    }

    public synchronized JSONObject markFaceApplied(long appliedVersion) throws JSONException {
        if (appliedVersion > 0L) faceAppliedVersion = appliedVersion;
        if (faceFetchedVersion < faceAppliedVersion) faceFetchedVersion = faceAppliedVersion;
        touchAndPersist();
        return snapshot();
    }

    public synchronized JSONObject applyFingerSync(JSONArray items, boolean full, long syncVersion)
            throws JSONException {
        if (full) fingerFeatures.clear();
        applyFeatureDelta(fingerFeatures, items, "fingerId");
        if (syncVersion > 0L) fingerSyncVersion = syncVersion;
        touchAndPersist();
        return snapshot();
    }

    public synchronized JSONObject snapshot() throws JSONException {
        return new JSONObject()
                .put("employees", mapValues(employees))
                .put("faceFeatures", mapValues(faceFeatures))
                .put("fingerFeatures", mapValues(fingerFeatures))
                .put("employeeSyncVersion", employeeSyncVersion)
                .put("faceFetchedVersion", faceFetchedVersion)
                .put("faceAppliedVersion", faceAppliedVersion)
                .put("faceSyncVersion", faceAppliedVersion)
                .put("fingerSyncVersion", fingerSyncVersion)
                .put("updatedAt", updatedAt);
    }

    public synchronized long employeeSyncVersion() { return employeeSyncVersion; }
    public synchronized long faceSyncVersion() { return faceAppliedVersion; }
    public synchronized long faceFetchedVersion() { return faceFetchedVersion; }
    public synchronized long fingerSyncVersion() { return fingerSyncVersion; }

    private void loadBackingStore() {
        synchronized (this) {
            boolean employeeCorrupt = !loadMap(KEY_EMPLOYEES, employees,
                    "employeeId", "employeeCode", "id");
            boolean faceCorrupt = !loadMap(KEY_FACE_FEATURES, faceFeatures,
                    "faceId", "employeeId", "id");
            boolean fingerCorrupt = !loadMap(KEY_FINGER_FEATURES, fingerFeatures,
                    "fingerId", "employeeId", "id");

            employeeSyncVersion = employeeCorrupt ? 0L
                    : preferences.getLong(KEY_EMPLOYEE_SYNC_VERSION, 0L);
            long legacyFace = preferences.getLong(KEY_LEGACY_FACE_SYNC_VERSION, 0L);
            faceFetchedVersion = faceCorrupt ? 0L
                    : preferences.getLong(KEY_FACE_FETCHED_VERSION, legacyFace);
            faceAppliedVersion = faceCorrupt ? 0L
                    : preferences.getLong(KEY_FACE_APPLIED_VERSION, legacyFace);
            fingerSyncVersion = fingerCorrupt ? 0L
                    : preferences.getLong(KEY_FINGER_SYNC_VERSION, 0L);
            updatedAt = preferences.getLong(KEY_UPDATED_AT, 0L);
        }
    }

    private boolean loadMap(String key, LinkedHashMap<String, JSONObject> target,
                            String... preferredKeys) {
        target.clear();
        try {
            upsertMap(target, new JSONArray(preferences.getString(key, "[]")), preferredKeys);
            return true;
        } catch (Exception ignored) {
            target.clear();
            return false;
        }
    }

    private void markBiometric(String employeeId, String employeeName, String field,
                               boolean registered) throws JSONException {
        String id = employeeId == null ? "" : employeeId.trim();
        if (id.isEmpty()) throw new IllegalArgumentException("employeeId is required");
        JSONObject employee = employees.get(id);
        if (employee == null) {
            throw new IllegalStateException("员工不存在，禁止仅凭本机录入创建后台员工资料");
        }
        employee = copy(employee);
        employee.put(field, registered);
        employees.put(id, employee);
        touchAndPersist();
    }

    private void touchAndPersist() throws JSONException {
        updatedAt = System.currentTimeMillis();
        SharedPreferences.Editor editor = preferences.edit()
                .putString(KEY_EMPLOYEES, mapValues(employees).toString())
                .putString(KEY_FACE_FEATURES, mapValues(faceFeatures).toString())
                .putString(KEY_FINGER_FEATURES, mapValues(fingerFeatures).toString())
                .putLong(KEY_EMPLOYEE_SYNC_VERSION, employeeSyncVersion)
                .putLong(KEY_FACE_FETCHED_VERSION, faceFetchedVersion)
                .putLong(KEY_FACE_APPLIED_VERSION, faceAppliedVersion)
                .putLong(KEY_LEGACY_FACE_SYNC_VERSION, faceAppliedVersion)
                .putLong(KEY_FINGER_SYNC_VERSION, fingerSyncVersion)
                .putLong(KEY_UPDATED_AT, updatedAt);
        if (!editor.commit()) throw new IllegalStateException("无法持久化Android业务缓存");
    }

    private String findEmployeeKey(String target) {
        for (Map.Entry<String, JSONObject> entry : employees.entrySet()) {
            JSONObject employee = entry.getValue();
            if (target.equals(entry.getKey())
                    || target.equals(employee.optString("id", ""))
                    || target.equals(employee.optString("employeeId", ""))
                    || target.equals(employee.optString("employeeCode", ""))) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static void applyFeatureDelta(LinkedHashMap<String, JSONObject> target,
                                          JSONArray items, String primaryKey) throws JSONException {
        if (items == null) return;
        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.optJSONObject(index);
            if (item == null) continue;
            String key = firstKey(item, primaryKey, "id");
            if (key.isEmpty()) continue;
            if (isDeleted(item)) {
                target.remove(key);
                continue;
            }
            JSONObject previous = target.get(key);
            target.put(key, merge(previous, item));
        }
    }

    private static void upsertMap(LinkedHashMap<String, JSONObject> target, JSONArray source,
                                  String... preferredKeys) throws JSONException {
        if (source == null) return;
        for (int index = 0; index < source.length(); index++) {
            JSONObject item = source.optJSONObject(index);
            if (item == null) continue;
            String key = firstKey(item, preferredKeys);
            if (key.isEmpty()) continue;
            JSONObject previous = target.get(key);
            target.put(key, merge(previous, item));
        }
    }

    private static JSONObject merge(JSONObject previous, JSONObject incoming) throws JSONException {
        JSONObject result = previous == null ? new JSONObject() : copy(previous);
        if (incoming == null) return result;
        java.util.Iterator<String> keys = incoming.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            result.put(key, incoming.opt(key));
        }
        return result;
    }

    private static boolean isDeleted(JSONObject item) {
        String status = item == null ? "" : item.optString("status", "");
        return "1".equals(status);
    }

    private static void removeFeaturesForEmployee(LinkedHashMap<String, JSONObject> target,
                                                   String employeeId) {
        if (employeeId == null || employeeId.isEmpty()) return;
        target.entrySet().removeIf(entry -> employeeId.equals(
                entry.getValue().optString("employeeId", "")));
    }

    private static JSONArray mapValues(LinkedHashMap<String, JSONObject> source)
            throws JSONException {
        JSONArray result = new JSONArray();
        for (JSONObject value : source.values()) result.put(copy(value));
        return result;
    }

    private static String firstKey(JSONObject item, String... preferredKeys) {
        if (preferredKeys != null) {
            for (String key : preferredKeys) {
                String value = item.optString(key, "").trim();
                if (!value.isEmpty()) return value;
            }
        }
        return "";
    }

    private static JSONObject copy(JSONObject value) throws JSONException {
        return value == null ? new JSONObject() : new JSONObject(value.toString());
    }

    private static boolean contains(JSONObject employee, String keyword) {
        String value = employee.optString("employeeName", "") + " "
                + employee.optString("employeeId", "") + " "
                + employee.optString("employeeCode", "") + " "
                + employee.optString("cardNo", "") + " "
                + employee.optString("department", "") + " "
                + employee.optString("position", "");
        return value.toLowerCase(Locale.US).contains(keyword);
    }
}
