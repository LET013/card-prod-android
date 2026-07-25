package com.xingyao.card.core;

import com.xingyao.card.BuildConfig;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Locale;

/** Data-layer business command coordinator for MQTT commands and backend reports. */
public final class DeviceCommandCoordinator {
    public interface BackendPort {
        void send(JSONObject payload) throws Exception;
        boolean isAuthenticated();
        String transportMode();
    }

    public interface ConfigControl {
        void apply(JSONObject settings) throws Exception;
    }

    public interface AppControl {
        void restart(long delayMs);
    }

    private final DeviceStateStore stateStore;
    private final NativeSettingsRepository settingsRepository;
    private final InboundCommandRepository inboundRepository;
    private final DeviceDataSyncManager syncManager;
    private final DeviceOperationEngine operationEngine;
    private final BackendPort backendPort;
    private final BackendHttpGateway httpGateway;
    private final AppControl appControl;
    private final ConfigControl configControl;
    private final LinkedHashMap<Integer, String> activeFaults = new LinkedHashMap<>();

    public DeviceCommandCoordinator(DeviceStateStore stateStore,
                                    NativeSettingsRepository settingsRepository,
                                    InboundCommandRepository inboundRepository,
                                    DeviceDataSyncManager syncManager,
                                    DeviceOperationEngine operationEngine,
                                    BackendPort backendPort,
                                    BackendHttpGateway httpGateway,
                                    AppControl appControl,
                                    ConfigControl configControl) {
        this.stateStore = stateStore;
        this.settingsRepository = settingsRepository;
        this.inboundRepository = inboundRepository;
        this.syncManager = syncManager;
        this.operationEngine = operationEngine;
        this.backendPort = backendPort;
        this.httpGateway = httpGateway;
        this.appControl = appControl;
        this.configControl = configControl;
    }

    public void handle(JSONObject command) {
        JSONObject safeCommand = command == null ? new JSONObject() : command;
        stateStore.record("backend.command.received", safeCommand);
        String cmd = safeCommand.optString("cmd", "").trim();
        InboundCommandRepository.BeginResult begin = inboundRepository.begin(safeCommand, currentDeviceCode());
        if (InboundCommandRepository.STATUS_DUPLICATE_COMPLETED.equals(begin.status)) {
            JSONObject cached = begin.response == null ? baseResponse(safeCommand, cmd + "Resp") : begin.response;
            try {
                if (!isLogToggle(cmd)) send(cached);
            } catch (Exception error) {
                recordSendFailure(cached, error);
            }
            return;
        }
        if (InboundCommandRepository.STATUS_DUPLICATE_PROCESSING.equals(begin.status)) {
            JSONObject processing = baseResponse(safeCommand, cmd + "Resp");
            try {
                processing.put("code", 500).put("msg", "相同指令正在处理中");
                send(processing);
            } catch (Exception error) {
                recordSendFailure(processing, error);
            }
            return;
        }
        if (InboundCommandRepository.STATUS_REJECTED.equals(begin.status)) {
            JSONObject rejected = baseResponse(safeCommand, cmd.isEmpty() ? "commandResp" : cmd + "Resp");
            try {
                rejected.put("code", 500).put("msg", begin.message);
                send(rejected);
                stateStore.record("security.command.rejected", rejected);
            } catch (Exception error) {
                recordSendFailure(rejected, error);
            }
            return;
        }

        try {
            switch (cmd) {
                case "remoteOpen": handleRemoteOpen(safeCommand); break;
                case "remoteEjectAll": handleRemoteEjectAll(safeCommand); break;
                case "syncUser": handleSync(safeCommand, "all"); break;
                case "syncConfig": handleSyncConfig(safeCommand); break;
                case "firmwareUpgrade": handleUnsupportedUpgrade(safeCommand, false); break;
                case "cancelUpgrade": handleUnsupportedUpgrade(safeCommand, true); break;
                case "deviceSelfCheck": handleDeviceSelfCheck(safeCommand); break;
                case "enableLogUpload":
                case "disableLogUpload": handleLogUploadToggle(safeCommand); break;
                case "restartApp": handleRestartApp(safeCommand); break;
                default:
                    complete(safeCommand, baseResponse(safeCommand,
                            cmd.isEmpty() ? "commandResp" : cmd + "Resp")
                            .put("code", 500).put("msg", "unsupported command"), false);
            }
        } catch (Exception error) {
            try {
                JSONObject response = baseResponse(safeCommand,
                        cmd.isEmpty() ? "commandResp" : cmd + "Resp")
                        .put("code", 500).put("msg", safeMessage(error));
                complete(safeCommand, response, false);
            } catch (Exception ignored) { }
        }
    }

    public void reportCardEvent(int slotId, String eventType, String authType,
                                String operationId, String requestMsgId, String employeeId) {
        try {
            stateStore.record("card.event.awaitingPhysicalConfirmation", new JSONObject()
                    .put("slotId", slotId)
                    .put("eventType", safe(eventType))
                    .put("authType", safe(authType))
                    .put("operationId", safe(operationId))
                    .put("requestMsgId", safe(requestMsgId))
                    .put("employeeId", safe(employeeId)));
        } catch (Exception ignored) { }
    }

    public void reportSlotSnapshot() {
        submitSlotSnapshot(false);
    }

    public JSONObject reportSlotSnapshotNow() {
        return submitSlotSnapshot(true);
    }

    private JSONObject submitSlotSnapshot(boolean recordManualResult) {
        long requestedAt = System.currentTimeMillis();
        if (!backendPort.isAuthenticated()) {
            return finishStatusReportResult(recordManualResult, statusReportResult(
                    "BLOCKED", "BACKEND_NOT_AUTHENTICATED",
                    "后端业务会话未认证，暂时无法提交状态上报", requestedAt));
        }
        try {
            JSONArray source = stateStore.backendSlots();
            JSONArray known = new JSONArray();
            JSONArray nativeSlots = stateStore.slotsSnapshot().optJSONArray("slots");
            for (int index = 0; nativeSlots != null && index < nativeSlots.length(); index++) {
                JSONObject nativeSlot = nativeSlots.getJSONObject(index);
                if (nativeSlot.optLong("updatedAt", 0L) > 0L && index < source.length()) {
                    known.put(source.getJSONObject(index));
                }
            }
            if (known.length() == 0) {
                return finishStatusReportResult(recordManualResult, statusReportResult(
                        "NO_DATA", "NO_KNOWN_SLOT_STATE",
                        "当前没有可上报的已确认卡槽状态", requestedAt));
            }
            send(new JSONObject().put("cmd", "statusReport")
                    .put("data", new JSONObject().put("slots", known)));
            return finishStatusReportResult(recordManualResult, statusReportResult(
                    "SUBMITTED", "", "已提交上报请求", requestedAt)
                    .put("knownSlotCount", known.length())
                    .put("ackTracked", false));
        } catch (Exception error) {
            JSONObject result = statusReportResult("FAILED", "STATUS_REPORT_SEND_FAILED",
                    "客户端本地发送失败：" + safeMessage(error), requestedAt);
            stateStore.record("backend.status.report.failed", result);
            return finishStatusReportResult(recordManualResult, result);
        }
    }

    private JSONObject finishStatusReportResult(boolean recordManualResult, JSONObject result) {
        if (recordManualResult) stateStore.record("backend.status.report.requested", result);
        return result;
    }

    private static JSONObject statusReportResult(String state, String code, String message,
                                                 long requestedAt) {
        try {
            return new JSONObject()
                    .put("state", state)
                    .put("code", code == null ? "" : code)
                    .put("message", message == null ? "" : message)
                    .put("requestedAt", requestedAt)
                    .put("knownSlotCount", 0)
                    .put("ackTracked", false);
        } catch (JSONException ignored) {
            return new JSONObject();
        }
    }

    public synchronized void reportHardwareFault(JSONObject slot) {
        if (slot == null) return;
        int slotId = slot.optInt("slotNumber", -1);
        if (slotId < 1) return;
        String status = slot.optString("status", "");
        String faultCode = slot.optString("faultCode", "");
        boolean hasFault = "CHARGING_FAULT".equals(status)
                || "COMMUNICATION_FAULT".equals(status)
                || "ILLEGAL_CARD".equals(status)
                || !faultCode.trim().isEmpty();
        String previous = activeFaults.get(slotId);
        if (!hasFault) {
            if (previous != null) {
                activeFaults.remove(slotId);
                try {
                    stateStore.record("hardware.fault.cleared", new JSONObject()
                            .put("slotId", slotId).put("previous", previous));
                } catch (Exception ignored) { }
            }
            return;
        }
        String signature = status + "|" + faultCode + "|"
                + slot.optString("faultMessage", "");
        if (signature.equals(previous)) return;
        activeFaults.put(slotId, signature);
        try {
            JSONObject mqttData = new JSONObject()
                    .put("slotId", slotId)
                    .put("faultCode", parseFaultCode(faultCode))
                    .put("faultMsg", slot.optString("faultMessage", status))
                    .put("timestamp", System.currentTimeMillis());
            if (BackendEndpointSettings.MODE_HTTP.equals(backendPort.transportMode())) {
                JSONObject httpData = new JSONObject(mqttData.toString())
                        .put("deviceId", currentDeviceCode());
                postAsync(BackendHttpGateway.FAULT_REPORT, httpData, "http.hardwareFault");
                return;
            }
            try {
                send(new JSONObject().put("cmd", "hardwareFault").put("data", mqttData));
            } catch (Exception error) {
                stateStore.record("backend.hardwareFault.failed", message(error));
                JSONObject httpData = new JSONObject(mqttData.toString())
                        .put("deviceId", currentDeviceCode());
                postAsync(BackendHttpGateway.FAULT_REPORT, httpData,
                        "http.hardwareFault.fallback");
            }
        } catch (Exception error) {
            stateStore.record("hardware.fault.build.failed", message(error));
        }
    }

    private void handleRemoteOpen(JSONObject command) throws Exception {
        int slotId = command.optInt("slotId", -1);
        JSONObject response = baseResponse(command, "remoteOpenResp");
        try {
            JSONObject result = operationEngine.openDoor(slotId, true, "MQTT",
                    command.optString("msgId", ""), "");
            stateStore.record("operation.remoteOpen.boardAcked", result);
            complete(command, response.put("code", 0).put("msg", "success"), true);
        } catch (Exception error) {
            complete(command, response.put("code", 500)
                    .put("msg", safeMessage(error)), false);
        }
    }

    private void handleRemoteEjectAll(JSONObject command) throws Exception {
        JSONObject response = baseResponse(command, "remoteEjectAllResp");
        if (!command.optBoolean("confirm", false)) {
            complete(command, response.put("code", 500)
                    .put("msg", "confirm required"), false);
            return;
        }
        try {
            JSONObject result = operationEngine.openAllDoors(true, "MQTT",
                    command.optString("msgId", ""));
            stateStore.record("operation.remoteEjectAll.boardAcked", result);
            int failedCount = result.optInt("failedCount", 0);
            complete(command, response.put("code", failedCount == 0 ? 0 : 500)
                    .put("msg", failedCount == 0 ? "success"
                            : "部分或全部单板未应答"), failedCount == 0);
        } catch (Exception error) {
            complete(command, response.put("code", 500)
                    .put("msg", safeMessage(error)), false);
        }
    }

    private void handleSync(JSONObject command, String scope) throws JSONException {
        JSONObject state = new JSONObject().put("state", "SYNCING")
                .put("cmd", command.optString("cmd", ""))
                .put("message", "正在同步设备业务数据")
                .put("msgId", command.optString("msgId", ""));
        stateStore.updateSection("sync", "sync.statusChanged", state);
        new Thread(() -> {
            try {
                JSONObject result;
                if ("employees".equals(scope)) result = syncManager.syncEmployees(false);
                else if ("faces".equals(scope)) result = syncManager.syncFaces(false);
                else if ("fingers".equals(scope)) result = syncManager.syncFingers(false);
                else result = syncManager.syncAll(false);
                JSONObject responseData = BackendHttpClient.copyWithout(result, "snapshot");
                complete(command, baseResponse(command, command.optString("cmd", "sync") + "Resp")
                        .put("code", 0).put("msg", "success"), true);
                JSONObject event = new JSONObject(responseData.toString())
                        .put("state", "SUCCESS")
                        .put("message", "同步完成")
                        .put("snapshot", result.optJSONObject("snapshot"));
                stateStore.updateSection("sync", "sync.completed", event);
            } catch (Exception error) {
                try {
                    complete(command, baseResponse(command,
                            command.optString("cmd", "sync") + "Resp")
                            .put("code", 500)
                            .put("msg", safeMessage(error)), false);
                    stateStore.updateSection("sync", "sync.statusChanged",
                            new JSONObject().put("state", "ERROR")
                                    .put("cmd", command.optString("cmd", ""))
                                    .put("message", safeMessage(error)));
                } catch (Exception ignored) { }
            }
        }, "device-data-sync").start();
    }

    private void handleSyncConfig(JSONObject command) throws Exception {
        JSONObject current = settingsRepository.load();
        JSONObject remote = httpGateway.getData(BackendHttpGateway.DEVICE_CONFIG);
        JSONObject saved = settingsRepository.save(DeviceConfigMapper.apply(current, remote));
        complete(command, baseResponse(command, "syncConfigResp")
                .put("code", 0).put("msg", "success"), true);
        if (configControl != null) configControl.apply(saved);
    }

    private void handleUnsupportedUpgrade(JSONObject command, boolean cancel) throws Exception {
        complete(command, baseResponse(command,
                cancel ? "cancelUpgradeResp" : "firmwareUpgradeResp")
                .put("code", 500)
                .put("msg", cancel ? "当前没有可取消的真实固件升级任务"
                        : "当前版本尚未实现固件下载安装"), false);
    }

    private void handleDeviceSelfCheck(JSONObject command) throws Exception {
        JSONObject snapshot = stateStore.snapshot();
        JSONObject data = new JSONObject()
                .put("deviceCode", currentDeviceCode())
                .put("appVersion", BuildConfig.VERSION_NAME)
                .put("appVersionCode", BuildConfig.VERSION_CODE)
                .put("serial", snapshot.opt("serial"))
                .put("backend", snapshot.opt("socket"))
                .put("sync", snapshot.opt("sync"))
                .put("slotSummary", stateStore.slotSummary())
                .put("timestamp", System.currentTimeMillis());
        reportRuntimeEvent("selfCheckReport", BackendHttpGateway.DEVICE_SELF_CHECK, data);
        complete(command, baseResponse(command, "deviceSelfCheckResp")
                .put("code", 0).put("msg", "success"), true);
    }

    private void handleLogUploadToggle(JSONObject command) throws Exception {
        JSONObject settings = settingsRepository.load();
        boolean enabled = command.optBoolean("enabled",
                "enableLogUpload".equals(command.optString("cmd")));
        settings.put("logUploadEnabled", enabled);
        settingsRepository.save(settings);
        stateStore.record("backend.logUpload", new JSONObject().put("enabled", enabled)
                .put("operatorId", command.optString("operatorId", "")));
        // V4.1 explicitly defines no response for enableLogUpload/disableLogUpload.
        inboundRepository.complete(command.optString("msgId", ""), null);
    }

    private void handleRestartApp(JSONObject command) throws Exception {
        complete(command, baseResponse(command, "restartAppResp")
                .put("code", 0).put("msg", "restarting"), true);
        appControl.restart(Math.max(0L, command.optLong("delayMs", 3000L)));
    }

    private void reportRuntimeEvent(String cmd, String httpPath, JSONObject data) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("cmd", cmd).put("data", data == null ? new JSONObject() : data);
            boolean sent = false;
            try { send(payload); sent = true; }
            catch (Exception error) {
                stateStore.record("backend." + cmd + ".failed", new JSONObject()
                        .put("message", safeMessage(error)).put("payload", payload));
            }
            if (!sent && !BackendEndpointSettings.MODE_HTTP.equals(backendPort.transportMode())
                    && httpPath != null && !httpPath.trim().isEmpty()) {
                postAsync(httpPath, data, "http." + cmd + ".fallback");
            }
        } catch (JSONException ignored) { }
    }

    private void postAsync(String path, JSONObject data, String category) {
        new Thread(() -> {
            try {
                JSONObject result = httpGateway.post(path, data == null ? new JSONObject() : data);
                stateStore.record(category, result);
            } catch (Exception error) {
                stateStore.record(category + ".failed", message(error));
            }
        }, "http-transport-" + Math.abs(path.hashCode())).start();
    }

    private void complete(JSONObject command, JSONObject response, boolean success) {
        String msgId = command == null ? "" : command.optString("msgId", "");
        boolean persisted = success
                ? inboundRepository.complete(msgId, response)
                : inboundRepository.fail(msgId, response);
        if (!persisted) {
            try {
                stateStore.record("backend.command.idempotency.persistFailed", new JSONObject()
                        .put("msgId", msgId).put("success", success)
                        .put("responseCmd", response == null ? "" : response.optString("cmd", "")));
            } catch (JSONException ignored) { }
        }
        try { send(response); }
        catch (Exception error) { recordSendFailure(response, error); }
    }

    private void send(JSONObject payload) throws Exception {
        backendPort.send(payload);
        stateStore.record("backend.sent", payload);
    }

    private void recordSendFailure(JSONObject response, Exception error) {
        try {
            stateStore.record("backend.command.response.failed", new JSONObject()
                    .put("message", safeMessage(error))
                    .put("response", response == null ? JSONObject.NULL : response));
        } catch (JSONException ignored) { }
    }

    private JSONObject baseResponse(JSONObject command, String responseCmd) {
        try {
            return new JSONObject()
                    .put("cmd", responseCmd == null || responseCmd.trim().isEmpty()
                            ? "commandResp" : responseCmd)
                    .put("msgId", command == null ? "" : command.optString("msgId", ""));
        } catch (JSONException ignored) {
            return new JSONObject();
        }
    }

    private static boolean isLogToggle(String cmd) {
        return "enableLogUpload".equals(cmd) || "disableLogUpload".equals(cmd);
    }

    private String currentDeviceCode() {
        try {
            JSONObject settings = settingsRepository.load();
            return settings.optString("deviceCode", "").trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static int parseFaultCode(String value) {
        if (value == null || value.isEmpty()) return 0;
        try {
            return value.startsWith("0x") || value.startsWith("0X")
                    ? Integer.parseInt(value.substring(2), 16) : Integer.parseInt(value);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static String normalizeAuthType(String value) {
        String authType = value == null ? "" : value.toUpperCase(Locale.US);
        if ("FINGER".equals(authType)) return "FINGERPRINT";
        if ("FACE".equals(authType) || "FINGERPRINT".equals(authType)
                || "ADMIN".equals(authType) || "CARD".equals(authType)
                || "REMOTE".equals(authType)) return authType;
        return "UNKNOWN";
    }

    private static JSONObject message(Throwable error) {
        try { return new JSONObject().put("message", safeMessage(error)); }
        catch (JSONException ignored) { return new JSONObject(); }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String safeMessage(Throwable error) {
        if (error == null) return "unknown";
        String value = error.getMessage();
        return value == null || value.trim().isEmpty()
                ? error.getClass().getSimpleName() : value;
    }
}
