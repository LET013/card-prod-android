package com.xingyao.card.core.serial;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class DeviceSerialManagerTest {
    private DeviceSerialManager manager;

    @After
    public void tearDown() throws Exception {
        if (manager == null) return;
        manager.stop();
        Field field = DeviceSerialManager.class.getDeclaredField("pollingExecutor");
        field.setAccessible(true);
        ((ScheduledExecutorService) field.get(manager)).shutdownNow();
    }

    @Test
    public void configureStartsPollingWithServerIntervalOnFirstStartup() throws Exception {
        manager = new DeviceSerialManager(true);
        JSONObject settings = new JSONObject()
                .put("serialPort", "/dev/ttyS5")
                .put("baudRate", 57600)
                .put("totalSlots", 100)
                .put("serialPollingEnabled", true)
                .put("serialPollInterval", 1000);

        manager.configure(settings);

        JSONObject snapshot = manager.snapshot();
        assertTrue(snapshot.getBoolean("pollingEnabled"));
        assertTrue(snapshot.getBoolean("polling"));
        assertEquals(1000L, snapshot.getLong("pollingIntervalMs"));
    }

    @Test
    public void startupBroadcastEmitsOneCompleteSnapshotAfterQuietWindow() throws Exception {
        manager = new DeviceSerialManager(true);
        CountDownLatch snapshotReceived = new CountDownLatch(1);
        int[] individualSlotEvents = {0};
        manager.setListener(new DeviceSerialManager.Listener() {
            @Override public void onStatusChanged(JSONObject status) { }
            @Override public void onDataReceived(JSONObject data) { }
            @Override public void onSlotStatus(JSONObject slot) { individualSlotEvents[0]++; }
            @Override public void onSlotsSnapshot(org.json.JSONArray slots) {
                if (slots.length() == SimulatedSerialTransport.SLOT_COUNT) snapshotReceived.countDown();
            }
        });
        manager.start();
        manager.configure(new JSONObject().put("serialPollingEnabled", false));

        assertTrue("startup broadcast should emit the full snapshot after the quiet window",
                snapshotReceived.await(2, TimeUnit.SECONDS));
        assertEquals("startup broadcast should not refresh slots one by one", 0, individualSlotEvents[0]);
    }

    @Test
    public void pollingForwardsChangedSlotsAsOneNativeBatch() throws Exception {
        manager = new DeviceSerialManager(true);
        CountDownLatch batchReceived = new CountDownLatch(1);
        int[] individualSlotEvents = {0};
        manager.setListener(new DeviceSerialManager.Listener() {
            @Override public void onStatusChanged(JSONObject status) { }
            @Override public void onDataReceived(JSONObject data) { }
            @Override public void onSlotStatus(JSONObject slot) { individualSlotEvents[0]++; }
            @Override public void onSlotStatusBatch(org.json.JSONArray slots) {
                if (slots.length() > 0) batchReceived.countDown();
            }
        });
        manager.start();
        manager.configure(new JSONObject()
                .put("serialPollingEnabled", true)
                .put("totalSlots", 10)
                .put("groupSize", 10)
                .put("pollingMode", "GROUP")
                .put("serialPollInterval", 50)
                .put("serialResponseTimeout", 100));

        assertTrue("normal polling should reach the listener in one batch",
                batchReceived.await(2, TimeUnit.SECONDS));
        assertEquals("the legacy one-event-per-slot callback must not be used", 0, individualSlotEvents[0]);
    }
}
