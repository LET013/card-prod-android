package com.xingyao.card.core.log;

import android.content.Context;
import android.util.Log;

import com.xingyao.card.core.mqtt.MqttCmd;
import com.xingyao.card.core.mqtt.XMqttClient;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

/** Persists the latest uncaught Java crash until the next authenticated MQTT session can report it. */
public final class CrashLogStore {
    private static final String TAG = "CrashLogStore";
    private static final String CRASH_FILE_NAME = "pending-crash.log";
    private static final String TEMP_FILE_NAME = "pending-crash.tmp";
    private static final Object LOCK = new Object();

    private static volatile File crashFile;

    private CrashLogStore() { }

    public static void install(Context context) {
        if (context == null) return;
        crashFile = new File(context.getApplicationContext().getFilesDir(), CRASH_FILE_NAME);
        final Thread.UncaughtExceptionHandler previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            persist(thread, error);
            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, error);
            }
        });
    }

    static void persist(Thread thread, Throwable error) {
        File target = crashFile;
        if (target == null || error == null) return;
        try {
            StringWriter stack = new StringWriter();
            error.printStackTrace(new PrintWriter(stack));
            String record = "timestamp=" + System.currentTimeMillis()
                    + "\nthread=" + (thread == null ? "unknown" : thread.getName())
                    + "\n" + stack;
            writeAtomically(target, AppLog.sanitizeCrashText(record));
        } catch (Throwable ignored) {
            // Never let the crash recorder replace the original process crash.
        }
    }

    public static void uploadPending(XMqttClient client) {
        File target = crashFile;
        if (target == null || client == null || !client.isConnected() || !target.isFile()) return;
        synchronized (LOCK) {
            if (!target.isFile()) return;
            try {
                byte[] bytes = readFile(target);
                String crashText = new String(bytes, StandardCharsets.UTF_8);
                JSONObject data = new JSONObject();
                data.put("level", "ERROR");
                data.put("message", "[Crash] " + AppLog.sanitizeMessage(crashText));
                data.put("timestamp", System.currentTimeMillis());
                client.sendMessage(MqttCmd.LOG_REPORT, data);
                if (!target.delete()) {
                    Log.w(TAG, "Crash log was published but could not be deleted");
                }
            } catch (Exception error) {
                Log.w(TAG, "Pending crash log upload deferred: " + error.getClass().getSimpleName());
            }
        }
    }

    private static void writeAtomically(File target, String content) throws Exception {
        synchronized (LOCK) {
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) return;
            File temp = new File(target.getParentFile(), TEMP_FILE_NAME);
            try (FileOutputStream output = new FileOutputStream(temp, false)) {
                output.write(content.getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
            if (target.exists() && !target.delete()) return;
            if (!temp.renameTo(target)) {
                try (FileOutputStream output = new FileOutputStream(target, false)) {
                    output.write(content.getBytes(StandardCharsets.UTF_8));
                    output.getFD().sync();
                }
                temp.delete();
            }
        }
    }

    private static byte[] readFile(File target) throws Exception {
        int length = (int) Math.min(target.length(), 16L * 1024L);
        byte[] bytes = new byte[length];
        int offset = 0;
        try (FileInputStream input = new FileInputStream(target)) {
            while (offset < length) {
                int count = input.read(bytes, offset, length - offset);
                if (count < 0) break;
                offset += count;
            }
        }
        if (offset == length) return bytes;
        byte[] result = new byte[offset];
        System.arraycopy(bytes, 0, result, 0, offset);
        return result;
    }
}
