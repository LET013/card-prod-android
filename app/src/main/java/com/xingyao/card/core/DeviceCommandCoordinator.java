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
                cached.put("duplicate", true).put("replayed", true);
                send(cached);
            } catch (Exception error) {
                recordSendFailure(cached, error);
            }
            return;
        }
        if (InboundCommandRepository.STATUS_DUPLICATE_PROCESSING.equals(begin.status)) {
            JSONObject processing = baseResponse(safeCommand, cmd + "Resp");
            try {
                processing.put("code", 202).put("status", "PROCESSING")
                        .put("msg", "相同指令正在处理中").put("duplicate", true);
                send(processing);
            } catch (Exception error) {
                recordSendFailure(processing, error);
            }
            return;
        }
        if (InboundCommandRepository.STATUS_REJECTED.equals(begin.status)) {
            JSONObject rejected = baseResponse(safeCommand, cmd.isEmpty() ? "commandResp" : cmd + "Resp");
            try {
                rejected.put("code", 4001).put("status", "REJECTED")
                        .put("errorCode", begin.code).put("msg", begin.message);
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
                case "queryStatus": handleQueryStatus(safeCommand); break;
                case "syncUser": handleSync(safeCommand, "all"); break;
                case "syncEmployeeData": handleSync(safeCommand, "employees"); break;
                case "syncFaceData": handleSync(safeCommand, "faces"); break;
                case "syncFingerData": handleSync(safeCommand, "fingers"); break;
                case "syncConfig": handleSyncConfig(safeCommand); break;
                case "firmwareUpgrade": handleUnsupportedUpgrade(safeCommand, false); break;
                case "cancelUpgrade": handleUnsupportedUpgrade(safeCommand, true); break;
                case "deviceSelfCheck": handleDeviceSelfCheck(safeCommand); break;
                case "enableLogUpload":
                case "disableLogUpload": handleLogUploadToggle(safeCommand); break;
                case "restartApp": handleRestartApp(safeCommand); break;
                default:
                    complete(safeCommand, baseResponse(safeCommand, cmd.isEmpty() ? "commandResp" : cmd + "Resp")
                            .put("code", 9000).put("status", "UNSUPPORTED")
                            .put("msg", "unsupported command"), false);
            }
        } catch (Exception error) {
            try {
                JSONObject response = baseResponse(safeCommand, cmd.isEmpty() ? "commandResp" : cmd + "Resp")
                        .put("code", 9000).put("status", "FAILED")
                        .put("msg", safeMessage(error));
                if (error instanceof DeviceOperationEngine.OperationException) {
                    DeviceOperationEngine.OperationException operationError =
                            (DeviceOperationEngine.OperationException) error;
                    response.put("operationId", operationError.getOperationId())
                            .put("errorCode", operationError.getFailureCode());
                }
                complete(safeCommand, response, false);
            } catch (Exception ignored) { }
        }
    }

    public void reportCardEvent(int slotId, String eventType, String authType,
                                String operationId, String requestMsgId, String employeeId) {
        try {
            JSONObject slot = stateStore.getSlot(slotId);
            JSONObject data = new JSONObject()
                    .put("cardNo", slot == null ? "" : slot.optString("cardNumber", ""))
                    .put("eventType", eventType)
                    .put("slotId", slotId)
                    .put("timestamp", System.currentTimeMillis())
                    .put("authType", normalizeAuthType(authType))
                    .put("operationId", safe(operationId))
                    .put("requestMsgId", safe(requestMsgId))
                    .put("employeeId", safe(employeeId))
                    .put("physicalConfirmed", false);
            JSONObject payload = new JSONObject().put("cmd", "cardEvent").put("data", data);
            boolean sent = false;
            try { send(payload); sent = true; }
            catch (Exception error) {
                stateStore.record("backend.cardEvent.failed", new JSONObject()
                        .put("message", safeMessage(error)).put("payload", payload));
            }
            if (!sent && !BackendEndpointSettings.MODE_HTTP.equals(backendPort.transportMode())) {
                postAsync(BackendHttpGateway.CARD_EVENT, data, "http.card.event.fallback");
            }
        } catch (Exception error) {
            stateStore.record("card.event.build.failed", message(error));
        }
    }

    public void reportSlotSnapshot() {
        if (!backendPort.isAuthenticated()) return;
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
            if (known.length() == 0) return;
            send(new JSONObject().put("cmd", "statusReport")
                    .put("data", new JSONObject()
                            .put("slots", known)
                            .put("summary", stateStore.slotSummary())
                            .put("timestamp", System.currentTimeMillis())));
        } catch (Exception error) {
            stateStore.record("backend.status.report.failed", message(error));
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
            if (previous == null) return;
            activeFaults.remove(slotId);
            try {
                reportRuntimeEvent("hardwareFault", BackendHttpGateway.FAULT_REPORT,
                        new JSONObject().put("deviceId", currentDeviceCode())
                                .put("slotId", slotId).put("faultCode", 0)
                                .put("faultMsg", "RECOVERED").put("recovered", true)
                                .put("timestamp", System.currentTimeMillis()));
            } catch (Exception ignored) { }
            return;
        }
        String signature = status + "|" + faultCode + "|" + slot.optString("faultMessage", "");
        if (signature.equals(previous)) return;
        activeFaults.put(slotId, signature);
        try {
            JSONObject data = new JSONObject()
                    .put("deviceId", currentDeviceCode())
                    .put("slotId", slotId)
                    .put("faultCode", parseFaultCode(faultCode))
                    .put("faultMsg", slot.optString("faultMessage", status))
                    .put("recovered", false)
                    .put("timestamp", System.currentTimeMillis());
            reportRuntimeEvent("hardwareFault", BackendHttpGateway.FAULT_REPORT, data);
        } catch (Exception ignored) { }
    }

    private void handleRemoteOpen(JSONObject command) throws Exception {
        int slotId = command.optInt("slotId", -1);
        String msgId = command.optString("msgId", "");
        JSONObject response = baseResponse(command, "remoteOpenResp").put("slotId", slotId);
        try {
            JSONObject result = operationEngine.openDoor(slotId, true, "MQTT", msgId,
                    command.optString("employeeId", ""));
            String operationId = result.optString("operationId", "");
            response.put("code", 0).put("status", "BOARD_ACKED")
                    .put("operationId", operationId)
                    .put("physicalConfirmationRequired", true)
                    .put("result", result);
            reportCardEvent(slotId, "TAKE", command.optString("authType", "REMOTE"),
                    operationId, msgId, command.optString("employeeId", ""));
            complete(command, response, true);
        } catch (Exception error) {
            response.put("code", 4003).put("status", "FAILED").put("msg", safeMessage(error));
            if (error instanceof DeviceOperationEngine.OperationException) {
                DeviceOperationEngine.OperationException operationError =
                        (DeviceOperationEngine.OperationException) error;
                response.put("operationId", operationError.getOperationId())
                        .put("errorCode", operationError.getFailureCode());
            }
            complete(command, response, false);
        }
    }

    private void handleRemoteEjectAll(JSONObject command) throws Exception {
        JSONObject response = baseResponse(command, "remoteEjectAllResp");
        if (!command.optBoolean("confirm", false)) {
            complete(command, response.put("code", 4001).put("status", "REJECTED")
                    .put("msg", "confirm required").put("ejectedCount", 0), false);
            return;
        }
        try {
            JSONObject result = operationEngine.openAllDoors(true, "MQTT",
                    command.optString("msgId", ""));
            int successCount = result.optInt("successCount", 0);
            int failedCount = result.optInt("failedCount", 0);
            response.put("code", failedCount == 0 ? 0 : 4001)
                    .put("status", failedCount == 0 ? "BOARD_ACKED"
                            : successCount > 0 ? "PARTIAL" : "FAILED")
                    .put("msg", failedCount == 0 ? "success" : "部分或全部单板未应答")
                    .put("operationId", result.optString("operationId", ""))
                    .put("physicalConfirmationRequired", successCount > 0)
                    .put("ejectedCount", successCount)
                    .put("failedCount", failedCount)
                    .put("failures", result.optJSONArray("failures"));
            complete(command, response, failedCount == 0);
        } catch (Exception error) {
            response.put("code", 4003).put("status", "FAILED")
                    .put("msg", safeMessage(error)).put("ejectedCount", 0);
            if (error instanceof DeviceOperationEngine.OperationException) {
                response.put("operationId",
                        ((DeviceOperationEngine.OperationException) error).getOperationId());
            }
            complete(command, response, false);
        }
    }

    private void handleQueryStatus(JSONObject command) throws Exception {
        complete(command, baseResponse(command, "statusResp")
                .put("code", 0).put("status", "SUCCESS")
                .put("data", stateStore.backendSlots(command.optInt("slotId", -1))), true);
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
                if ("employees".equals(scope)) result = syncManager.syncEmployees(command);
                else if ("faces".equals(scope)) result = syncManager.syncFaces(command);
                else if ("fingers".equals(scope)) result = syncManager.syncFingers(command);
                else result = syncManager.syncAll(command);
                JSONObject responseData = BackendHttpClient.copyWithout(result, "snapshot");
                complete(command, baseResponse(command, command.optString("cmd", "sync") + "Resp")
                        .put("code", 0).put("status", "SUCCESS")
                        .put("data", responseData), true);
                JSONObject event = new JSONObject(responseData.toString())
                        .put("state", "SUCCESS")
                        .put("message", "同步完成")
                        .put("snapshot", result.optJSONObject("snapshot"));
                stateStore.updateSection("sync", "sync.completed", event);
            } catch (Exception error) {
                try {
                    complete(command, baseResponse(command,
                            command.optString("cmd", "sync") + "Resp")
                            .put("code", 9000).put("status", "FAILED")
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
                .put("code", 0).put("status", "SUCCESS").put("msg", "success")
                .put("deviceCode", currentDeviceCode())
                .put("configUpdatedAt", saved.optLong("remoteConfigUpdatedAt", 0L)), true);
        if (configControl != null) configControl.apply(saved);
    }

    private void handleUnsupportedUpgrade(JSONObject command, boolean cancel) throws Exception {
        complete(command, baseResponse(command, cancel ? "cancelUpgradeResp" : "firmwareUpgradeResp")
                .put("code", 501).put("status", "NOT_SUPPORTED")
                .put("msg", cancel ? "当前没有可取消的真实固件升级任务"
                        : "当前版本尚未实现固件下载安装，不会伪报accepted")
                .put("firmwareVersion", command.optString("firmwareVersion",
                        command.optString("version", "")))
                .put("downloadUrl", httpGateway.absoluteUrl(command.optString("downloadUrl", ""))), false);
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
                .put("code", 0).put("status", "SUCCESS")
                .put("msg", "success").put("data", data), true);
    }

    private void handleLogUploadToggle(JSONObject command) throws Exception {
        JSONObject settings = settingsRepository.load();
        boolean enabled = command.optBoolean("enabled",
                "enableLogUpload".equals(command.optString("cmd")));
        settings.put("logUploadEnabled", enabled);
        settingsRepository.save(settings);
        stateStore.record("backend.logUpload", new JSONObject().put("enabled", enabled)
                .put("operatorId", command.optString("operatorId", "")));
        if (enabled) reportRuntimeEvent("logReport", BackendHttpGateway.LOG_REPORT,
                new JSONObject().put("level", "INFO").put("tag", "LOG_UPLOAD")
                        .put("content", "日志上传已开启")
                        .put("timestamp", System.currentTimeMillis()));
        complete(command, baseResponse(command, command.optString("cmd", "") + "Resp")
                .put("code", 0).put("status", "SUCCESS").put("enabled", enabled), true);
    }

    private void handleRestartApp(JSONObject command) throws Exception {
        complete(command, baseResponse(command, "restartAppResp")
                .put("code", 0).put("status", "ACCEPTED").put("msg", "restarting"), true);
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
                    .put("requestMsgId", command == null ? "" : command.optString("msgId", ""))
                    .put("deviceCode", currentDeviceCode())
                    .put("timestamp", System.currentTimeMillis());
        } catch (JSONException ignored) {
            return new JSONObject();
        }
    }

    private String currentDeviceCode() {
        try {
            JSONObject settings = settingsRepository.load();
            return settings.optString("deviceCode", settings.optString("deviceId", "DEV001"));
        } catch (Exception ignored) {
            return "DEV001";
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
