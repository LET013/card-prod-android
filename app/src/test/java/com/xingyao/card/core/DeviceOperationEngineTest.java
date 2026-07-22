package com.xingyao.card.core;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DeviceOperationEngineTest {
    @Test
    public void openDoorStopsAtBoardAcknowledgedUntilPhysicalConfirmationExists() throws Exception {
        List<String> states = new ArrayList<>();
        DeviceOperationEngine engine = new DeviceOperationEngine(new DeviceOperationEngine.SerialGateway() {
            @Override public JSONObject openDoor(int slotNumber, boolean administrator) throws Exception {
                return new JSONObject().put("ack", true).put("slotNumber", slotNumber);
            }

            @Override public JSONObject openAllDoors(boolean administrator) {
                return new JSONObject();
            }
        }, (category, payload) -> states.add(payload.optString("state")));

        JSONObject result = engine.openDoor(8, true, "MQTT", "MSG-1", "EMP-1");

        assertFalse(result.optString("operationId").isEmpty());
        assertEquals("BOARD_ACKED", result.optString("operationState"));
        assertTrue(result.optBoolean("physicalConfirmationRequired"));
        assertTrue(states.contains("RECEIVED"));
        assertTrue(states.contains("SERIAL_DISPATCHED"));
        assertTrue(states.contains("BOARD_ACKED"));
        assertFalse(states.contains("COMPLETED"));
    }

    @Test
    public void invalidSlotFailsBeforeSerialDispatch() throws Exception {
        final boolean[] serialCalled = {false};
        DeviceOperationEngine engine = new DeviceOperationEngine(new DeviceOperationEngine.SerialGateway() {
            @Override public JSONObject openDoor(int slotNumber, boolean administrator) {
                serialCalled[0] = true;
                return new JSONObject();
            }

            @Override public JSONObject openAllDoors(boolean administrator) {
                return new JSONObject();
            }
        }, (category, payload) -> { });

        try {
            engine.openDoor(-1, true, "UI", "WEB-1", "");
            fail("Expected invalid slot failure");
        } catch (DeviceOperationEngine.OperationException error) {
            assertEquals("INVALID_SLOT", error.getFailureCode());
            assertNotNull(error.getOperationId());
        }
        assertFalse(serialCalled[0]);
    }
    @Test
    public void openAllReportsPartialBoardAcknowledgement() throws Exception {
        DeviceOperationEngine engine = new DeviceOperationEngine(new DeviceOperationEngine.SerialGateway() {
            @Override public JSONObject openDoor(int slotNumber, boolean administrator) {
                return new JSONObject();
            }

            @Override public JSONObject openAllDoors(boolean administrator) throws Exception {
                return new JSONObject().put("successCount", 2).put("failedCount", 1);
            }
        }, (category, payload) -> { });

        JSONObject result = engine.openAllDoors(true, "MQTT", "MSG-ALL");

        assertEquals("PARTIAL_BOARD_ACK", result.optString("operationState"));
        assertTrue(result.optBoolean("physicalConfirmationRequired"));
    }

}
