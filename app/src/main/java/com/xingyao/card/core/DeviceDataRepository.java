package com.xingyao.card.core;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/** Stores backend-synchronized business data for the native service and WebView UI. */
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

    public DeviceDataRepository(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized JSONArray employees() throws JSONException {
        return new JSONArray(preferences.getString(KEY_EMPLOYEES, "[]"));
    }

    public synchronized JSONArray searchEmployees(String query) throws JSONException {
        String keyword = query == null ? "" : query.trim().toLowerCase(Locale.US);
        JSONArray source = employees();
        JSONArray result = new JSONArray();
        for (int index = 0; index < source.length(); index++) {
            JSONObject employee = source.getJSONObject(index);
            if (keyword.isEmpty() || contains(employee, keyword)) result.put(employee);
        }
        return result;
    }

    public synchronized boolean deleteEmployee(String id) throws JSONException {
        JSONArray source = employees();
        JSONArray kept = new JSONArray();
        boolean deleted = false;
        for (int index = 0; index < source.length(); index++) {
            JSONObject employee = source.getJSONObject(index);
            String employeeId = employee.optString("id", employee.optString("employeeId", ""));
            if (employeeId.equals(id) || employee.optString("employeeId", "").equals(id)) {
                deleted = true;
                continue;
            }
            kept.put(employee);
        }
        if (deleted) preferences.edit().putString(KEY_EMPLOYEES, kept.toString()).putLong(KEY_UPDATED_AT, System.currentTimeMillis()).apply();
        return deleted;
    }

    public synchronized JSONObject snapshot() throws JSONException {
        return new JSONObject()
                .put("employees", employees())
                .put("faceFeatures", new JSONArray(preferences.getString(KEY_FACE_FEATURES, "[]")))
                .put("fingerFeatures", new JSONArray(preferences.getString(KEY_FINGER_FEATURES, "[]")))
                .put("employeeSyncVersion", preferences.getLong(KEY_EMPLOYEE_SYNC_VERSION, 0L))
                .put("faceSyncVersion", preferences.getLong(KEY_FACE_SYNC_VERSION, 0L))
                .put("fingerSyncVersion", preferences.getLong(KEY_FINGER_SYNC_VERSION, 0L))
                .put("updatedAt", preferences.getLong(KEY_UPDATED_AT, 0L));
    }

    public synchronized JSONObject saveSyncResult(JSONArray employees, long employeeSyncVersion,
                                                  JSONArray faceFeatures, long faceSyncVersion,
                                                  JSONArray fingerFeatures, long fingerSyncVersion) throws JSONException {
        SharedPreferences.Editor editor = preferences.edit()
                .putLong(KEY_UPDATED_AT, System.currentTimeMillis());
        if (employees != null) {
            editor.putString(KEY_EMPLOYEES, employees.toString());
            if (employeeSyncVersion > 0L) editor.putLong(KEY_EMPLOYEE_SYNC_VERSION, employeeSyncVersion);
        }
        if (faceFeatures != null) {
            editor.putString(KEY_FACE_FEATURES, faceFeatures.toString());
            if (faceSyncVersion > 0L) editor.putLong(KEY_FACE_SYNC_VERSION, faceSyncVersion);
        }
        if (fingerFeatures != null) {
            editor.putString(KEY_FINGER_FEATURES, fingerFeatures.toString());
            if (fingerSyncVersion > 0L) editor.putLong(KEY_FINGER_SYNC_VERSION, fingerSyncVersion);
        }
        editor.apply();
        return snapshot();
    }

    public long employeeSyncVersion() {
        return preferences.getLong(KEY_EMPLOYEE_SYNC_VERSION, 0L);
    }

    public long faceSyncVersion() {
        return preferences.getLong(KEY_FACE_SYNC_VERSION, 0L);
    }

    public long fingerSyncVersion() {
        return preferences.getLong(KEY_FINGER_SYNC_VERSION, 0L);
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
