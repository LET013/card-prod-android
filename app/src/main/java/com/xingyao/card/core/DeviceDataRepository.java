package com.xingyao.card.core;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Android business-data repository.
 *
 * The in-memory maps are the live source of truth. SharedPreferences is only the restart backing
 * store and is never read by Vue.
 */
public final class DeviceDataRepository {
    private static final String PREFS = "device_sync_data";
    private static final String KEY_EMPLOYEES = "employees";
    private static final String KEY_FACE_FEATURES = "faceFeatures";
    private static final String KEY_FINGER_FEATURES = "fingerFeatures";
    private static final String KEY_EMPLOYEE_SYNC_VERSION = "employeeSyncVersion";
    private static final String KEY_FACE_SYNC_VERSION = "faceSyncVersion";
    private static final String KEY_FINGER_SYNC_VERSION = "fingerSyncVersion";
    private static final String KEY_UPDATED_AT = "updatedAt";

    private final SharedPreferences preferences;
    private final LinkedHashMap<String, JSONObject> employees = new LinkedHashMap<>();
    private final LinkedHashMap<String, JSONObject> faceFeatures = new LinkedHashMap<>();
    private final LinkedHashMap<String, JSONObject> fingerFeatures = new LinkedHashMap<>();
    private long employeeSyncVersion;
    private long faceSyncVersion;
    private long fingerSyncVersion;
    private long updatedAt;

    public DeviceDataRepository(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
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

    public synchronized boolean deleteEmployee(String id) throws JSONException {
        String target = id == null ? "" : id.trim();
        if (target.isEmpty()) return false;
        String matchedKey = null;
        for (Map.Entry<String, JSONObject> entry : employees.entrySet()) {
            JSONObject employee = entry.getValue();
            if (target.equals(entry.getKey())
                    || target.equals(employee.optString("id", ""))
                    || target.equals(employee.optString("employeeId", ""))) {
                matchedKey = entry.getKey();
                break;
            }
        }
        if (matchedKey == null) return false;
        String employeeId = employees.get(matchedKey).optString("employeeId", matchedKey);
        employees.remove(matchedKey);
        removeFeaturesForEmployee(faceFeatures, employeeId);
        removeFeaturesForEmployee(fingerFeatures, employeeId);
        touchAndPersist();
        return true;
    }

    public synchronized void markFaceRegistered(String employeeId, String employeeName,
                                                boolean registered) throws JSONException {
        markBiometric(employeeId, employeeName, "faceRegistered", registered);
    }

    public synchronized void markFingerprintRegistered(String employeeId, String employeeName,
                                                       boolean registered) throws JSONException {
        markBiometric(employeeId, employeeName, "fingerprintRegistered", registered);
    }

    public synchronized JSONObject snapshot() throws JSONException {
        return new JSONObject()
                .put("employees", mapValues(employees))
                .put("faceFeatures", mapValues(faceFeatures))
                .put("fingerFeatures", mapValues(fingerFeatures))
                .put("employeeSyncVersion", employeeSyncVersion)
                .put("faceSyncVersion", faceSyncVersion)
                .put("fingerSyncVersion", fingerSyncVersion)
                .put("updatedAt", updatedAt);
    }

    public synchronized JSONObject saveSyncResult(JSONArray employeeItems, long employeeVersion,
                                                   JSONArray faceItems, long faceVersion,
                                                   JSONArray fingerItems, long fingerVersion)
            throws JSONException {
        if (employeeItems != null) {
            replaceMap(employees, employeeItems, "employeeId", "employeeCode", "id");
            if (employeeVersion > 0L) employeeSyncVersion = employeeVersion;
        }
        if (faceItems != null) {
            replaceMap(faceFeatures, faceItems, "faceId", "employeeId", "id");
            if (faceVersion > 0L) faceSyncVersion = faceVersion;
        }
        if (fingerItems != null) {
            replaceMap(fingerFeatures, fingerItems, "fingerId", "employeeId", "id");
            if (fingerVersion > 0L) fingerSyncVersion = fingerVersion;
        }
        touchAndPersist();
        return snapshot();
    }

    public synchronized long employeeSyncVersion() { return employeeSyncVersion; }
    public synchronized long faceSyncVersion() { return faceSyncVersion; }
    public synchronized long fingerSyncVersion() { return fingerSyncVersion; }

    private void loadBackingStore() {
        synchronized (this) {
            try {
                replaceMap(employees, new JSONArray(preferences.getString(KEY_EMPLOYEES, "[]")),
                        "employeeId", "employeeCode", "id");
                replaceMap(faceFeatures, new JSONArray(preferences.getString(KEY_FACE_FEATURES, "[]")),
                        "faceId", "employeeId", "id");
                replaceMap(fingerFeatures, new JSONArray(preferences.getString(KEY_FINGER_FEATURES, "[]")),
                        "fingerId", "employeeId", "id");
            } catch (JSONException ignored) {
                employees.clear();
                faceFeatures.clear();
                fingerFeatures.clear();
            }
            employeeSyncVersion = preferences.getLong(KEY_EMPLOYEE_SYNC_VERSION, 0L);
            faceSyncVersion = preferences.getLong(KEY_FACE_SYNC_VERSION, 0L);
            fingerSyncVersion = preferences.getLong(KEY_FINGER_SYNC_VERSION, 0L);
            updatedAt = preferences.getLong(KEY_UPDATED_AT, 0L);
        }
    }

    private void markBiometric(String employeeId, String employeeName, String field,
                               boolean registered) throws JSONException {
        String id = employeeId == null ? "" : employeeId.trim();
        if (id.isEmpty()) throw new IllegalArgumentException("employeeId is required");
        JSONObject employee = employees.get(id);
        if (employee == null) {
            employee = new JSONObject()
                    .put("id", id)
                    .put("employeeId", id)
                    .put("employeeCode", id)
                    .put("employeeName", employeeName == null ? "" : employeeName)
                    .put("faceRegistered", false)
                    .put("fingerprintRegistered", false)
                    .put("enabled", true);
        } else {
            employee = copy(employee);
            if (employee.optString("employeeName", "").isEmpty()
                    && employeeName != null && !employeeName.trim().isEmpty()) {
                employee.put("employeeName", employeeName.trim());
            }
        }
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
                .putLong(KEY_FACE_SYNC_VERSION, faceSyncVersion)
                .putLong(KEY_FINGER_SYNC_VERSION, fingerSyncVersion)
                .putLong(KEY_UPDATED_AT, updatedAt);
        if (!editor.commit()) throw new IllegalStateException("无法持久化Android业务缓存");
    }

    private static void replaceMap(LinkedHashMap<String, JSONObject> target, JSONArray source,
                                   String... preferredKeys) throws JSONException {
        target.clear();
        if (source == null) return;
        for (int index = 0; index < source.length(); index++) {
            JSONObject item = source.optJSONObject(index);
            if (item == null) continue;
            JSONObject copy = copy(item);
            String key = firstKey(copy, preferredKeys);
            if (key.isEmpty()) key = "ROW-" + index;
            target.put(key, copy);
        }
    }

    private static void removeFeaturesForEmployee(LinkedHashMap<String, JSONObject> target,
                                                  String employeeId) {
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
        String haystack = (employee.optString("employeeId") + " "
                + employee.optString("employeeCode") + " "
                + employee.optString("employeeName") + " "
                + employee.optString("cardNo") + " "
                + employee.optString("department")).toLowerCase(Locale.US);
        return haystack.contains(keyword);
    }
}
