package com.xingyao.card.core.log;

import android.util.Log;

import com.xingyao.card.core.mqtt.MqttCmd;
import com.xingyao.card.core.mqtt.XMqttClient;

import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/** 只记录应用显式提交的关键日志，并按后台开关决定是否先通过 MQTT 上传。 */
public final class AppLog {
    private static final String INTERNAL_TAG = "AppLog";
    private static final int MAX_TAG_LENGTH = 23;
    private static final int MAX_MESSAGE_LENGTH = 2048;
    private static final int MAX_CRASH_FILE_LENGTH = 16 * 1024;
    private static final AtomicBoolean UPLOAD_ENABLED = new AtomicBoolean(false);
    private static final Pattern SENSITIVE_VALUE = Pattern.compile(
            "(?i)\"?(password|passwd|token|authorization|signingKey|sign|secret|faceImage|faceImagePath|faceFeature|fingerFeature|fileHash|employeeId|employeeCode|employeeName|cardNo|phone|email)\"?"
                    + "\\s*[:=]\\s*(\"[^\"]*\"|[^,;\\s}]+)");
    private static final Pattern SENSITIVE_QUERY_PARAMETER = Pattern.compile(
            "(?i)([?&](?:password|passwd|token|authorization|signingKey|sign|secret)=)[^&#\\s]+"
    );

    private static volatile XMqttClient mqttClient;

    private AppLog() { }

    public static void setMqttClient(XMqttClient client) {
        mqttClient = client;
        if (client != null) {
            CrashLogStore.uploadPending(client);
        }
    }

    public static void setUploadEnabled(boolean enabled) {
        UPLOAD_ENABLED.set(enabled);
    }

    public static boolean isUploadEnabled() {
        return UPLOAD_ENABLED.get();
    }

    public static void d(String tag, String message) {
        write("DEBUG", tag, message, null);
    }

    public static void i(String tag, String message) {
        write("INFO", tag, message, null);
    }

    public static void w(String tag, String message) {
        write("WARN", tag, message, null);
    }

    public static void e(String tag, String message) {
        write("ERROR", tag, message, null);
    }

    public static void e(String tag, String message, Throwable error) {
        write("ERROR", tag, message, error);
    }

    public static void write(String level, String tag, String message) {
        write(level, tag, message, null);
    }

    /** Native diagnostics must be visible in cloud analysis even when Vue has not enabled optional log upload. */
    public static void diagnosticD(String tag, String message) {
        write("DEBUG", tag, message, null, true);
    }

    public static void diagnosticI(String tag, String message) {
        write("INFO", tag, message, null, true);
    }

    public static void diagnosticW(String tag, String message) {
        write("WARN", tag, message, null, true);
    }

    public static void diagnosticW(String tag, String message, Throwable error) {
        write("WARN", tag, message, error, true);
    }

    public static void diagnosticE(String tag, String message) {
        write("ERROR", tag, message, null, true);
    }

    public static void diagnosticE(String tag, String message, Throwable error) {
        write("ERROR", tag, message, error, true);
    }

    static String sanitizeMessage(String message) {
        return sanitize(message, MAX_MESSAGE_LENGTH, false);
    }

    private static void write(String level, String tag, String message, Throwable error) {
        write(level, tag, message, error, false);
    }

    static String sanitizeCrashText(String message) {
        return sanitize(message, MAX_CRASH_FILE_LENGTH, true);
    }

    private static void write(String level, String tag, String message, Throwable error, boolean forceUpload) {
        String safeLevel = normalizeLevel(level);
        String safeTag = sanitizeTag(tag);
        String safeMessage = sanitizeMessage(message);
        if (error != null) {
            String cause = sanitizeMessage(error.getClass().getSimpleName() + ": " + error.getMessage());
            safeMessage = sanitizeMessage(safeMessage + (cause.isEmpty() ? "" : " | " + cause));
        }

        if (forceUpload || UPLOAD_ENABLED.get()) {
            upload(safeLevel, safeTag, safeMessage);
        }
        printLocal(safeLevel, safeTag, safeMessage);
    }

    private static String sanitize(String message, int maxLength, boolean preserveLineBreaks) {
        String safe = message == null ? "" : message;
        safe = preserveLineBreaks
                ? safe.replace("\r\n", "\n").replace('\r', '\n').trim()
                : safe.replace('\n', ' ').replace('\r', ' ').trim();
        safe = SENSITIVE_VALUE.matcher(safe).replaceAll("$1=***");
        safe = SENSITIVE_QUERY_PARAMETER.matcher(safe).replaceAll("$1***");
        if (safe.length() > maxLength) {
            safe = safe.substring(0, maxLength) + "...[truncated]";
        }
        return safe;
    }

    private static void upload(String level, String tag, String message) {
        XMqttClient client = mqttClient;
        if (client == null || !client.isConnected()) return;
        try {
            JSONObject data = new JSONObject();
            data.put("level", "DEBUG".equals(level) ? "INFO" : level);
            data.put("message", "[" + tag + "] " + message);
            data.put("timestamp", System.currentTimeMillis());
            client.sendMessage(MqttCmd.LOG_REPORT, data);
        } catch (Exception error) {
            Log.w(INTERNAL_TAG, "MQTT log upload failed: " + error.getClass().getSimpleName());
        }
    }

    private static void printLocal(String level, String tag, String message) {
        switch (level) {
            case "ERROR":
                Log.e(tag, message);
                break;
            case "WARN":
                Log.w(tag, message);
                break;
            case "DEBUG":
                Log.d(tag, message);
                break;
            default:
                Log.i(tag, message);
        }
    }

    private static String normalizeLevel(String level) {
        String normalized = level == null ? "INFO" : level.trim().toUpperCase(Locale.ROOT);
        if ("DEBUG".equals(normalized) || "INFO".equals(normalized)
                || "WARN".equals(normalized) || "ERROR".equals(normalized)) {
            return normalized;
        }
        return "INFO";
    }

    private static String sanitizeTag(String tag) {
        String safe = tag == null ? "APP" : tag.replaceAll("[^A-Za-z0-9_.-]", "_").trim();
        if (safe.isEmpty()) safe = "APP";
        return safe.length() > MAX_TAG_LENGTH ? safe.substring(0, MAX_TAG_LENGTH) : safe;
    }
}
