package com.xingyao.card.core.log;

import com.xingyao.card.core.mqtt.XMqttClient;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class CrashLogStoreTest {
    @Test
    public void persistsSanitizedCrashAndDeletesItAfterMqttAcceptsReport() throws Exception {
        File crashFile = File.createTempFile("pending-crash", ".log");
        crashFile.delete();
        Field field = CrashLogStore.class.getDeclaredField("crashFile");
        field.setAccessible(true);
        Object previous = field.get(null);
        field.set(null, crashFile);
        try {
            CrashLogStore.persist(Thread.currentThread(), new RuntimeException("token=secret-value"));
            String stored = readUtf8(crashFile);
            assertTrue(crashFile.isFile());
            assertTrue(stored.contains("token=***"));
            assertFalse(stored.contains("secret-value"));

            RecordingMqttClient client = new RecordingMqttClient();
            CrashLogStore.uploadPending(client);

            assertNotNull(client.data);
            assertTrue(client.data.optString("message").contains("[Crash]"));
            assertFalse(crashFile.exists());
        } finally {
            field.set(null, previous);
            crashFile.delete();
        }
    }

    private static String readUtf8(File file) throws Exception {
        byte[] bytes = new byte[(int) file.length()];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < bytes.length) {
                int count = input.read(bytes, offset, bytes.length - offset);
                if (count < 0) break;
                offset += count;
            }
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static final class RecordingMqttClient extends XMqttClient {
        private JSONObject data;

        RecordingMqttClient() {
            super("tcp://test", "test", null, null);
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public String sendMessage(String cmd, JSONObject data) {
            this.data = data;
            return "test-msg-id";
        }
    }
}
