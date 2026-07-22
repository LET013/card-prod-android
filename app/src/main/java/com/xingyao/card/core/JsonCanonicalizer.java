package com.xingyao.card.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/** Stable JSON representation used for idempotency and future contract signature tests. */
public final class JsonCanonicalizer {
    private JsonCanonicalizer() { }

    public static String canonicalize(JSONObject source) throws JSONException {
        return object(source == null ? new JSONObject() : source);
    }

    private static String object(JSONObject source) throws JSONException {
        List<String> keys = new ArrayList<>();
        Iterator<String> iterator = source.keys();
        while (iterator.hasNext()) {
            String key = iterator.next();
            if (key == null || key.startsWith("_")) continue;
            keys.add(key);
        }
        Collections.sort(keys);
        StringBuilder result = new StringBuilder("{");
        for (int index = 0; index < keys.size(); index++) {
            if (index > 0) result.append(',');
            String key = keys.get(index);
            result.append(JSONObject.quote(key)).append(':').append(value(source.opt(key)));
        }
        return result.append('}').toString();
    }

    private static String array(JSONArray source) throws JSONException {
        StringBuilder result = new StringBuilder("[");
        for (int index = 0; index < source.length(); index++) {
            if (index > 0) result.append(',');
            result.append(value(source.opt(index)));
        }
        return result.append(']').toString();
    }

    private static String value(Object value) throws JSONException {
        if (value == null || value == JSONObject.NULL) return "null";
        if (value instanceof JSONObject) return object((JSONObject) value);
        if (value instanceof JSONArray) return array((JSONArray) value);
        if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
        return JSONObject.quote(String.valueOf(value));
    }
}
