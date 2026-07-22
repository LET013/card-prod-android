package com.xingyao.card.core;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/** Persistent idempotency gate for V4.1 backend downlink commands. */
public final class InboundCommandRepository {
    public static final String STATUS_NEW = "NEW";
    public static final String STATUS_DUPLICATE_PROCESSING = "DUPLICATE_PROCESSING";
    public static final String STATUS_DUPLICATE_COMPLETED = "DUPLICATE_COMPLETED";
    public static final String STATUS_REJECTED = "REJECTED";

    private static final String PREFS = "card_inbound_commands";
    private static final String KEY_ENTRIES = "entries";
    private static final int MAX_TERMINAL_ENTRIES = 500;

    public static final class BeginResult {
        public final String status;
        public final String code;
        public final String message;
        public final String msgId;
        public final JSONObject response;

        BeginResult(String status, String code, String message, String msgId, JSONObject response) {
            this.status = status;
            this.code = code;
            this.message = message;
            this.msgId = msgId;
            this.response = response;
        }
    }

    private final SharedPreferences preferences;

    public InboundCommandRepository(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized BeginResult begin(JSONObject command, String expectedDeviceCode) {
        String msgId = command == null ? "" : command.optString("msgId", "").trim();
        if (msgId.isEmpty()) return rejected("MISSING_MSG_ID", "后台指令缺少msgId", msgId);

        long now = System.currentTimeMillis();
        long timestamp = command.optLong("timestamp", 0L);
        if (timestamp <= 0L) return rejected("MISSING_TIMESTAMP", "后台指令缺少timestamp", msgId);
        // V4.1 downlink does not require deviceCode. Reject only an explicitly supplied mismatch.
        String incomingDeviceCode = command.optString("deviceCode", "").trim();
        String localDeviceCode = expectedDeviceCode == null ? "" : expectedDeviceCode.trim();
        if (!incomingDeviceCode.isEmpty() && !localDeviceCode.isEmpty()
                && !incomingDeviceCode.equals(localDeviceCode)) {
            return rejected("DEVICE_MISMATCH", "后台指令deviceCode与本机不一致", msgId);
        }

        String payloadHash;
        try {
            payloadHash = sha256(JsonCanonicalizer.canonicalize(command));
        } catch (Exception error) {
            return rejected("COMMAND_HASH_FAILED", safeMessage(error), msgId);
        }

        JSONObject entries = loadEntries();
        JSONObject existing = entries.optJSONObject(msgId);
        if (existing != null) {
            String existingHash = existing.optString("payloadHash", "");
            if (!existingHash.isEmpty() && !existingHash.equals(payloadHash)) {
                return rejected("MSG_ID_CONFLICT", "相同msgId对应了不同指令内容", msgId);
            }
            String state = existing.optString("state", "PROCESSING");
            JSONObject response = existing.optJSONObject("response");
            if ("COMPLETED".equals(state) || "FAILED".equals(state)) {
                return new BeginResult(STATUS_DUPLICATE_COMPLETED, "DUPLICATE_COMMAND",
                        "指令已处理，返回缓存结果", msgId, copy(response));
            }
            return new BeginResult(STATUS_DUPLICATE_PROCESSING, "COMMAND_IN_PROGRESS",
                    "相同指令正在处理中", msgId, null);
        }

        try {
            JSONObject entry = new JSONObject()
                    .put("msgId", msgId)
                    .put("cmd", command.optString("cmd", ""))
                    .put("requestTimestamp", timestamp)
                    .put("receivedAt", now)
                    .put("updatedAt", now)
                    .put("payloadHash", payloadHash)
                    .put("state", "PROCESSING");
            entries.put(msgId, entry);
            trimTerminal(entries);
            persist(entries);
            return new BeginResult(STATUS_NEW, "", "", msgId, null);
        } catch (Exception error) {
            return rejected("IDEMPOTENCY_STORE_FAILED", safeMessage(error), msgId);
        }
    }

    public synchronized boolean complete(String msgId, JSONObject response) {
        return update(msgId, "COMPLETED", response);
    }

    public synchronized boolean fail(String msgId, JSONObject response) {
        return update(msgId, "FAILED", response);
    }

    private boolean update(String msgId, String state, JSONObject response) {
        if (msgId == null || msgId.trim().isEmpty()) return false;
        JSONObject entries = loadEntries();
        JSONObject entry = entries.optJSONObject(msgId);
        if (entry == null) return false;
        try {
            entry.put("state", state)
                    .put("updatedAt", System.currentTimeMillis())
                    .put("response", response == null ? JSONObject.NULL
                            : new JSONObject(response.toString()));
            entries.put(msgId, entry);
            trimTerminal(entries);
            persist(entries);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private JSONObject loadEntries() {
        try { return new JSONObject(preferences.getString(KEY_ENTRIES, "{}")); }
        catch (JSONException ignored) { return new JSONObject(); }
    }

    private void persist(JSONObject entries) {
        if (!preferences.edit().putString(KEY_ENTRIES, entries.toString()).commit()) {
            throw new IllegalStateException("无法持久化后台指令幂等记录");
        }
    }

    /** Never evicts PROCESSING records because doing so could allow a duplicate side effect. */
    private void trimTerminal(JSONObject entries) throws JSONException {
        List<JSONObject> terminal = new ArrayList<>();
        Iterator<String> keys = entries.keys();
        while (keys.hasNext()) {
            JSONObject value = entries.optJSONObject(keys.next());
            if (value == null) continue;
            String state = value.optString("state", "PROCESSING");
            if ("COMPLETED".equals(state) || "FAILED".equals(state)) terminal.add(value);
        }
        if (terminal.size() <= MAX_TERMINAL_ENTRIES) return;
        terminal.sort(Comparator.comparingLong(value -> value.optLong("updatedAt", 0L)));
        int removeCount = terminal.size() - MAX_TERMINAL_ENTRIES;
        for (int index = 0; index < removeCount; index++) {
            entries.remove(terminal.get(index).optString("msgId", ""));
        }
    }

    private static BeginResult rejected(String code, String message, String msgId) {
        return new BeginResult(STATUS_REJECTED, code,
                message == null || message.trim().isEmpty() ? code : message, msgId, null);
    }

    private static JSONObject copy(JSONObject value) {
        if (value == null) return null;
        try { return new JSONObject(value.toString()); }
        catch (JSONException ignored) { return null; }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((value == null ? "" : value)
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte item : bytes) builder.append(String.format(LocaleHolder.US, "%02x", item));
            return builder.toString();
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static String safeMessage(Throwable error) {
        if (error == null) return "unknown";
        String value = error.getMessage();
        return value == null || value.trim().isEmpty()
                ? error.getClass().getSimpleName() : value;
    }

    /** Avoids a mutable default Locale dependency in hash formatting. */
    private static final class LocaleHolder {
        static final java.util.Locale US = java.util.Locale.US;
    }
}
