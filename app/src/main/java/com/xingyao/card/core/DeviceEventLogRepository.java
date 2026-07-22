package com.xingyao.card.core;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Durable, bounded queue for device diagnostics.  It stores event metadata and
 * raw serial payloads, but never face images or biometric template bytes.
 */
public final class DeviceEventLogRepository {
    private static final String PREFS = "device_event_log";
    private static final String KEY_EVENTS = "events";
    private static final int MAX_EVENTS = 2000;
    private final SharedPreferences preferences;

    public DeviceEventLogRepository(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized void append(String category, JSONObject payload) {
        try {
            JSONArray events = new JSONArray(preferences.getString(KEY_EVENTS, "[]"));
            JSONObject event = new JSONObject()
                    .put("eventId", "EV-" + System.currentTimeMillis() + "-" + events.length())
                    .put("timestamp", System.currentTimeMillis())
                    .put("category", category)
                    .put("payload", payload == null ? JSONObject.NULL : new JSONObject(payload.toString()));
            events.put(event);
            while (events.length() > MAX_EVENTS) events.remove(0);
            preferences.edit().putString(KEY_EVENTS, events.toString()).apply();
        } catch (JSONException ignored) {
            // Diagnostic recording must never break the device workflow.
        }
    }

    public synchronized JSONArray pendingEvents() throws JSONException {
        return new JSONArray(preferences.getString(KEY_EVENTS, "[]"));
    }

    public synchronized void removeThrough(String eventId) throws JSONException {
        JSONArray events = pendingEvents();
        JSONArray remaining = new JSONArray();
        boolean acknowledged = false;
        for (int index = 0; index < events.length(); index++) {
            JSONObject item = events.getJSONObject(index);
            if (!acknowledged) {
                acknowledged = eventId.equals(item.optString("eventId"));
                continue;
            }
            remaining.put(item);
        }
        preferences.edit().putString(KEY_EVENTS, remaining.toString()).apply();
    }
}
