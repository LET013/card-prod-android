package com.xingyao.card.core;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;
import java.util.UUID;

/**
 * The single entry point for physical cabinet operations.
 *
 * Batch 1 deliberately stops at BOARD_ACKED. A board acknowledgement means the
 * command was accepted; it does not mean a card was physically taken or returned.
 * Batch 2 will advance operations from PHYSICAL_PENDING using slot-state changes.
 */
public final class DeviceOperationEngine {
    public interface SerialGateway {
        JSONObject openDoor(int slotNumber, boolean administrator) throws Exception;
        JSONObject openAllDoors(boolean administrator) throws Exception;
    }

    public interface Recorder {
        void record(String category, JSONObject payload);
    }

    public static final class OperationException extends Exception {
        private final String operationId;
        private final String failureCode;

        OperationException(String operationId, String failureCode, String message, Throwable cause) {
            super(message, cause);
            this.operationId = operationId;
            this.failureCode = failureCode;
        }

        public String getOperationId() {
            return operationId;
        }

        public String getFailureCode() {
            return failureCode;
        }
    }

    private final SerialGateway serialGateway;
    private final Recorder recorder;

    public DeviceOperationEngine(SerialGateway serialGateway, Recorder recorder) {
        if (serialGateway == null) throw new IllegalArgumentException("serialGateway is required");
        this.serialGateway = serialGateway;
        this.recorder = recorder;
    }

    public JSONObject openDoor(int slotNumber, boolean administrator, String source,
                               String requestMsgId, String employeeId) throws OperationException {
        String operationId = newOperationId();
        long startedAt = System.currentTimeMillis();
        recordStage(operationId, "OPEN_DOOR", "RECEIVED", source, requestMsgId, employeeId,
                slotNumber, startedAt, null);
        if (slotNumber < 1) {
            OperationException error = new OperationException(operationId, "INVALID_SLOT",
                    "卡位号无效", null);
            recordFailure(operationId, "OPEN_DOOR", source, requestMsgId, employeeId,
                    slotNumber, startedAt, error);
            throw error;
        }
        recordStage(operationId, "OPEN_DOOR", "QUEUED", source, requestMsgId, employeeId,
                slotNumber, startedAt, null);
        try {
            recordStage(operationId, "OPEN_DOOR", "SERIAL_DISPATCHED", source, requestMsgId,
                    employeeId, slotNumber, startedAt, null);
            JSONObject result = serialGateway.openDoor(slotNumber, administrator);
            if (result == null) result = new JSONObject();
            result.put("operationId", operationId)
                    .put("operationState", "BOARD_ACKED")
                    .put("operationSource", safe(source))
                    .put("requestMsgId", safe(requestMsgId))
                    .put("physicalConfirmationRequired", true)
                    .put("startedAt", startedAt)
                    .put("boardAckedAt", System.currentTimeMillis());
            recordStage(operationId, "OPEN_DOOR", "BOARD_ACKED", source, requestMsgId,
                    employeeId, slotNumber, startedAt, result);
            return result;
        } catch (Exception error) {
            OperationException wrapped = error instanceof OperationException
                    ? (OperationException) error
                    : new OperationException(operationId, failureCode(error), safeMessage(error), error);
            recordFailure(operationId, "OPEN_DOOR", source, requestMsgId, employeeId,
                    slotNumber, startedAt, wrapped);
            throw wrapped;
        }
    }

    public JSONObject openAllDoors(boolean administrator, String source, String requestMsgId)
            throws OperationException {
        String operationId = newOperationId();
        long startedAt = System.currentTimeMillis();
        recordStage(operationId, "OPEN_ALL_DOORS", "RECEIVED", source, requestMsgId, "",
                -1, startedAt, null);
        try {
            recordStage(operationId, "OPEN_ALL_DOORS", "QUEUED", source, requestMsgId,
                    "", -1, startedAt, null);
            recordStage(operationId, "OPEN_ALL_DOORS", "SERIAL_DISPATCHED", source,
                    requestMsgId, "", -1, startedAt, null);
            JSONObject result = serialGateway.openAllDoors(administrator);
            if (result == null) result = new JSONObject();
            int successCount = result.optInt("successCount", result.optBoolean("success", false) ? 1 : 0);
            int failedCount = result.optInt("failedCount", 0);
            String operationState = failedCount == 0 ? "BOARD_ACKED"
                    : successCount > 0 ? "PARTIAL_BOARD_ACK" : "FAILED";
            result.put("operationId", operationId)
                    .put("operationState", operationState)
                    .put("operationSource", safe(source))
                    .put("requestMsgId", safe(requestMsgId))
                    .put("physicalConfirmationRequired", successCount > 0)
                    .put("startedAt", startedAt)
                    .put("boardAckedAt", successCount > 0 ? System.currentTimeMillis() : JSONObject.NULL);
            recordStage(operationId, "OPEN_ALL_DOORS", operationState, source, requestMsgId,
                    "", -1, startedAt, result);
            return result;
        } catch (Exception error) {
            OperationException wrapped = error instanceof OperationException
                    ? (OperationException) error
                    : new OperationException(operationId, failureCode(error), safeMessage(error), error);
            recordFailure(operationId, "OPEN_ALL_DOORS", source, requestMsgId, "",
                    -1, startedAt, wrapped);
            throw wrapped;
        }
    }

    private void recordFailure(String operationId, String operationType, String source,
                               String requestMsgId, String employeeId, int slotId,
                               long startedAt, OperationException error) {
        JSONObject details = new JSONObject();
        try {
            details.put("failureCode", error.getFailureCode())
                    .put("failureMessage", safeMessage(error));
        } catch (JSONException ignored) { }
        recordStage(operationId, operationType, "FAILED", source, requestMsgId, employeeId,
                slotId, startedAt, details);
    }

    private void recordStage(String operationId, String operationType, String state,
                             String source, String requestMsgId, String employeeId, int slotId,
                             long startedAt, JSONObject details) {
        if (recorder == null) return;
        try {
            JSONObject payload = new JSONObject()
                    .put("operationId", operationId)
                    .put("operationType", operationType)
                    .put("state", state)
                    .put("source", safe(source))
                    .put("requestMsgId", safe(requestMsgId))
                    .put("employeeId", safe(employeeId))
                    .put("slotId", slotId > 0 ? slotId : JSONObject.NULL)
                    .put("startedAt", startedAt)
                    .put("updatedAt", System.currentTimeMillis());
            if (details != null) payload.put("details", details);
            recorder.record("operation." + operationType.toLowerCase(Locale.US) + "." + state.toLowerCase(Locale.US), payload);
        } catch (Exception ignored) { }
    }

    private static String newOperationId() {
        return "OP-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.US);
    }

    private static String failureCode(Exception error) {
        if (error instanceof java.util.concurrent.TimeoutException) return "SERIAL_TIMEOUT";
        if (error instanceof IllegalArgumentException) return "INVALID_ARGUMENT";
        if (error instanceof IllegalStateException) return "INVALID_STATE";
        return "OPERATION_FAILED";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String safeMessage(Throwable error) {
        if (error == null) return "unknown";
        String value = error.getMessage();
        return value == null || value.trim().isEmpty() ? error.getClass().getSimpleName() : value;
    }
}
