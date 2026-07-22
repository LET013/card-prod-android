package com.xingyao.card.core;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

public class NativeSettingsRepository {
    private static final String PREFS = "card_native_settings";
    private static final String KEY_SETTINGS = "settings_json";
    private final SharedPreferences preferences;

    public NativeSettingsRepository(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public JSONObject load() throws JSONException {
        String raw = preferences.getString(KEY_SETTINGS, "{}");
        return new JSONObject(raw == null ? "{}" : raw);
    }

    public JSONObject save(JSONObject settings) throws JSONException {
        JSONObject clean = settings == null ? new JSONObject() : new JSONObject(settings.toString());
        clean.put("initialized", true);
        clean.put("updatedAt", System.currentTimeMillis());
        preferences.edit().putString(KEY_SETTINGS, clean.toString()).apply();
        return clean;
    }
}
