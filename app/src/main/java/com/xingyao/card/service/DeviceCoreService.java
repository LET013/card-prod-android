package com.xingyao.card.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.xingyao.card.BuildConfig;
import com.xingyao.card.R;
import com.xingyao.card.core.ArcFaceManager;
import com.xingyao.card.core.BackendHttpClient;
import com.xingyao.card.core.DeviceDataRepository;
import com.xingyao.card.core.DeviceDataSyncManager;
import com.xingyao.card.core.DeviceEventLogRepository;
import com.xingyao.card.core.DeviceOperationEngine;
import com.xingyao.card.core.InboundCommandRepository;
import com.xingyao.card.core.NativeSettingsRepository;
import com.xingyao.card.core.SerialConnectionManager;
import com.xingyao.card.core.SlotStateRepository;
import com.xingyao.card.core.WebSocketConnectionManager;

import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class DeviceCoreService extends Service {
    public interface DeviceEventListener {
        void onDeviceEvent(String event, JSONObject data);
    }

    private static final String CHANNEL_ID = "device_core_service";
    private static final int NOTIFICATION_ID = 1001;
    private static DeviceCoreService instance;
    private static volatile DeviceEventListener eventListener;

    private WebSocketConnectionManager webSocketManager;
    private SerialConnectionManager serialManager;
    private ArcFaceManager arcFaceManager;
    private DeviceEventLogRepository eventLogRepository;
    private DeviceDataRepository dataRepository;
    private DeviceDataSyncManager dataSyncManager;
    private DeviceOperationEngine operationEngine;
    private InboundCommandRepository inboundCommandRepository;
    private final SlotStateRepository slotRepository = new SlotStateRepository();
    private final ExecutorService backendCommandExecutor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService slotReportExecutor = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> slotReportTask;
    private volatile boolean startupDataSyncRunning;
    private volatile boolean startupDataSyncCompleted;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        eventLogRepository = new DeviceEventLogRepository(this);
        NativeSettingsRepository settingsRepository = new NativeSettingsRepository(this);
        JSONObject settings;
        try { settings = settingsRepository.load(); }
        catch (JSONException error) { settings = new JSONObject(); }
        slotRepository.configure(settings);
        dataRepository = new DeviceDataRepository(this);
        arcFaceManager = new ArcFaceManager(this, status -> recordAndPublish("biometric.engine", "recognition.statusChanged", status));
        dataSyncManager = new DeviceDataSyncManager(this, settingsRepository, dataRepository, arcFaceManager);
        webSocketManager = new WebSocketConnectionManager(this, settingsRepository, new WebSocketConnectionManager.Listener() {
            @Override public void onStatusChanged(JSONObject status) { handleBackendStatusChanged(status); }
            @Override public void onCommand(JSONObject command) {
                try { backendCommandExecutor.execute(() -> handleSocketCommand(command)); }
                catch (RejectedExecutionException ignored) { }
            }
            @Override public void onMessage(JSONObject message) { recordAndPublish("socket.message", "socket.message", message); }
        });
        serialManager = new SerialConnectionManager(this, new SerialConnectionManager.Listener() {
            @Override public void onStatusChanged(JSONObject status) { recordAndPublish("serial.status", "serial.statusChanged", status); }
            @Override public void onDataReceived(JSONObject data) { recordAndPublish("serial.received", "serial.dataReceived", data); }
            @Override public void onSlotStatus(JSONObject slot) {
                JSONObject updated = cacheSlot(slot);
                if (updated != null) {
                    recordAndPublish("cabinet.slot.status", "cabinet.slotStatus", updated);
                    maybeReportHardwareFault(updated);
                }
            }
        });
        inboundCommandRepository = new InboundCommandRepository(this);
        operationEngine = new DeviceOperationEngine(new DeviceOperationEngine.SerialGateway() {
            @Override public JSONObject openDoor(int slotNumber, boolean administrator) throws Exception {
                return serialManager.openDoor(slotNumber, administrator);
            }

            @Override public JSONObject openAllDoors(boolean administrator) throws Exception {
                return serialManager.openAllDoors(administrator);
            }
        }, this::record);
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        webSocketManager.start();
        serialManager.start();
        arcFaceManager.start();
        startSlotStatusReporter(settings);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        webSocketManager.stop();
        serialManager.stop();
        stopSlotStatusReporter();
        backendCommandExecutor.shutdownNow();
        slotReportExecutor.shutdownNow();
        if (arcFaceManager != null) arcFaceManager.stop();
        instance = null;
        super.onDestroy();
    }

    public static JSONObject snapshot() throws JSONException {
        JSONObject result = new JSONObject();
        if (instance == null || instance.serialManager == null) {
            result.put("serial", new JSONObject().put("state", "DISCONNECTED").put("message", "原生服务未启动"));
            result.put("socket", new JSONObject().put("state", "DISCONNECTED").put("message", "原生服务未启动"));
        } else {
            result.put("serial", instance.serialManager.snapshot());
            result.put("socket", instance.webSocketManager.snapshot());
        }
        result.put("slots", instance == null ? new JSONArray() : instance.slotRepository.snapshotSlots());
        result.put("http", httpSnapshot());
        result.put("sync", syncSnapshot());
        result.put("deviceAuthorization", new JSONObject().put("state", "AUTHORIZED").put("message", "已授权"));
        result.put("recognitionEngine", instance == null || instance.arcFaceManager == null
                ? new JSONObject().put("state", "STOPPED").put("message", "虹软服务未启动")
                : instance.arcFaceManager.snapshot());
        return result;
    }

    public static void setDeviceEventListener(DeviceEventListener listener) {
        eventListener = listener;
    }

    public static void configureSerial(JSONObject settings) {
        if (instance != null) {
            instance.slotRepository.configure(settings);
            instance.startSlotStatusReporter(settings);
            instance.startupDataSyncCompleted = false;
        }
        if (instance != null && instance.serialManager != null) instance.serialManager.configure(settings);
        if (instance != null && instance.webSocketManager != null) instance.webSocketManager.configure(settings);
    }

    public static void reconnectSerial() {
        if (instance != null && instance.serialManager != null) instance.serialManager.reconnect();
    }

    public static JSONObject setSerialPolling(boolean enabled) throws JSONException {
        if (instance == null || instance.serialManager == null) {
            return new JSONObject().put("state", "DISCONNECTED").put("message", "原生服务未启动");
        }
        JSONObject result = instance.serialManager.setPollingEnabled(enabled);
        instance.record("serial.polling", result);
        return result;
    }

    public static JSONObject listSerialPorts() throws JSONException {
        return SerialConnectionManager.listAvailablePorts();
    }

    public static JSONObject sendSerial(String data, String encoding) throws Exception {
        if (instance == null || instance.serialManager == null) throw new IllegalStateException("原生设备服务未启动");
        JSONObject result = instance.serialManager.send(data, encoding);
        instance.record("serial.sent", result);
        return result;
    }

    public static JSONObject openDoor(int slotNumber, boolean administrator) throws Exception {
        return openDoor(slotNumber, administrator, "TAKE", administrator ? "ADMIN" : "FACE", "",
                administrator ? "ADMIN" : "FACE");
    }

    public static JSONObject openDoor(int slotNumber, boolean administrator, String eventType, String authType) throws Exception {
        return openDoor(slotNumber, administrator, eventType, authType, "",
                administrator ? "ADMIN" : "FACE");
    }

    public static JSONObject openDoor(int slotNumber, boolean administrator, String eventType, String authType,
                                      String requestMsgId, String source) throws Exception {
        if (instance == null || instance.operationEngine == null) throw new IllegalStateException("原生设备服务未启动");
        JSONObject result = instance.operationEngine.openDoor(slotNumber, administrator, source, requestMsgId, "");
        instance.record("cabinet.door.open", result);
        instance.sendCardEvent(slotNumber, eventType, authType, result.optString("operationId", ""),
                requestMsgId, "");
        return result;
    }

    public static JSONObject querySlot(int slotNumber) throws Exception {
        if (instance == null || instance.serialManager == null) throw new IllegalStateException("原生设备服务未启动");
        JSONObject result = instance.serialManager.querySlot(slotNumber);
        instance.record("cabinet.slot.query", result);
        return result;
    }

    public static JSONObject readBoardVersion(int slotNumber) throws Exception {
        if (instance == null || instance.serialManager == null) throw new IllegalStateException("原生设备服务未启动");
        JSONObject result = instance.serialManager.readVersion(slotNumber);
        instance.record("cabinet.board.version", result);
        return result;
    }

    public static JSONObject openAllDoors(boolean administrator) throws Exception {
        return openAllDoors(administrator, "", administrator ? "ADMIN" : "UI");
    }

    public static JSONObject openAllDoors(boolean administrator, String requestMsgId, String source) throws Exception {
        if (instance == null || instance.operationEngine == null) throw new IllegalStateException("原生设备服务未启动");
        JSONObject result = instance.operationEngine.openAllDoors(administrator, source, requestMsgId);
        instance.record("cabinet.door.openAll", result);
        return result;
    }

    public static JSONObject getSlots() throws JSONException {
        if (instance == null) return new JSONObject().put("slots", new JSONArray());
        return new JSONObject()
                .put("slots", instance.slotRepository.snapshotSlots())
                .put("summary", instance.slotRepository.summary());
    }

    public static void restartFaceRecognition() {
        if (instance != null && instance.arcFaceManager != null) instance.arcFaceManager.restart();
    }

    public static JSONObject enrollFace(String employeeId, String employeeName, byte[] frame, int width, int height) throws Exception {
        if (instance == null || instance.arcFaceManager == null) throw new IllegalStateException("虹软服务未启动");
        instance.arcFaceManager.awaitReady(8000L);
        JSONObject result = instance.arcFaceManager.enrollNv21(employeeId, employeeName, frame, width, height);
        instance.record("biometric.face.enrolled", result);
        return result;
    }

    public static JSONObject verifyFace(byte[] frame, int width, int height) throws Exception {
        if (instance == null || instance.arcFaceManager == null) throw new IllegalStateException("虹软服务未启动");
        instance.arcFaceManager.awaitReady(8000L);
        JSONObject result = instance.arcFaceManager.verifyNv21(frame, width, height);
        if (result.optBoolean("success", false)) {
            int slotNumber = instance.pickTakeSlot();
            if (slotNumber < 1) {
                result.put("doorOpen", false).put("status", "NO_AVAILABLE_CARD")
                        .put("message", "人脸识别成功，但当前没有可取卡槽");
                instance.record("biometric.face.noAvailableCard", result);
                return result;
            }
            String employeeId = result.optString("employeeId", "");
            try {
                JSONObject door = instance.operationEngine.openDoor(slotNumber, false, "FACE", "", employeeId);
                result.put("slotNumber", slotNumber).put("doorOpen", true).put("door", door)
                        .put("operationId", door.optString("operationId", ""));
                instance.sendCardEvent(slotNumber, "TAKE", "FACE", door.optString("operationId", ""),
                        "", employeeId);
            } catch (Exception error) {
                result.put("slotNumber", slotNumber).put("doorOpen", false).put("doorError", error.getMessage());
                throw new IllegalStateException("人脸识别成功，但开门失败：" + error.getMessage());
            }
        }
        instance.record("biometric.face.verified", result);
        return result;
    }

    public static void recordOperation(String category, JSONObject payload) {
        if (instance != null) instance.record(category, payload);
    }

    public static JSONArray searchEmployees(String query) throws JSONException {
        if (instance == null || instance.dataRepository == null) return new JSONArray();
        return instance.dataRepository.searchEmployees(query);
    }

    public static JSONObject deleteEmployee(String id) throws JSONException {
        if (instance == null || instance.dataRepository == null) {
            return new JSONObject().put("success", false).put("message", "原生设备服务未启动");
        }
        boolean deleted = instance.dataRepository.deleteEmployee(id);
        JSONObject result = new JSONObject().put("success", deleted).put("id", id);
        instance.recordAndPublish("sync.employee.deleted", "sync.employeeChanged", result);
        return result;
    }

    private void recordAndPublish(String category, String event, JSONObject data) {
        record(category, data);
        publish(event, data);
    }

    private void handleBackendStatusChanged(JSONObject status) {
        recordAndPublish("socket.status", "socket.statusChanged", status);
        if (status != null && "AUTHENTICATED".equals(status.optString("state"))) {
            runStartupDataSyncIfNeeded();
        }
    }

    private void runStartupDataSyncIfNeeded() {
        try {
            JSONObject settings = new NativeSettingsRepository(this).load();
            if (!settings.optBoolean("startupDataSyncEnabled", true)) return;
        } catch (Exception ignored) { }
        synchronized (this) {
            if (startupDataSyncRunning || startupDataSyncCompleted || dataSyncManager == null) return;
            startupDataSyncRunning = true;
        }
        recordAndPublish("sync.startup.start", "sync.statusChanged", eventState("SYNCING", "startup", "正在主动同步员工/人脸/指纹数据"));
        new Thread(() -> {
            try {
                JSONObject result = dataSyncManager.syncAll(new JSONObject().put("full", false).put("source", "startup"));
                JSONObject event = BackendHttpClient.copyWithout(result, "snapshot")
                        .put("state", "SUCCESS")
                        .put("cmd", "startup")
                        .put("message", "启动同步完成")
                        .put("snapshot", result.optJSONObject("snapshot"));
                startupDataSyncCompleted = true;
                recordAndPublish("sync.startup.success", "sync.completed", event);
            } catch (Exception error) {
                try {
                    recordAndPublish("sync.startup.failed", "sync.statusChanged",
                            eventState("ERROR", "startup", "启动同步失败：" + error.getMessage()));
                } catch (Exception ignored) { }
            } finally {
                startupDataSyncRunning = false;
            }
        }, "startup-data-sync").start();
    }

    private static JSONObject eventState(String state, String cmd, String message) {
        try {
            return new JSONObject().put("state", state).put("cmd", cmd).put("message", message);
        } catch (JSONException ignored) {
            return new JSONObject();
        }
    }

    private void handleSocketCommand(JSONObject command) {
        recordAndPublish("socket.command", "socket.command", command);
        String cmd = command == null ? "" : command.optString("cmd", "").trim();
        if (inboundCommandRepository == null) {
            record("socket.command.rejected", new JSONObject());
            return;
        }
        InboundCommandRepository.BeginResult begin = inboundCommandRepository.begin(command, currentDeviceCode());
        if (InboundCommandRepository.STATUS_DUPLICATE_COMPLETED.equals(begin.status)) {
            JSONObject cached = begin.response == null ? baseCommandResponse(command, cmd + "Resp") : begin.response;
            try {
                cached.put("duplicate", true).put("replayed", true);
                sendSocket(cached);
                record("socket.command.replayed", cached);
            } catch (Exception error) {
                recordCommandSendFailure(cached, error);
            }
            return;
        }
        if (InboundCommandRepository.STATUS_DUPLICATE_PROCESSING.equals(begin.status)) {
            JSONObject processing = baseCommandResponse(command, cmd + "Resp");
            try {
                processing.put("code", 202).put("status", "PROCESSING")
                        .put("msg", "相同指令正在处理中").put("duplicate", true);
                sendSocket(processing);
            } catch (Exception error) {
                recordCommandSendFailure(processing, error);
            }
            return;
        }
        if (InboundCommandRepository.STATUS_REJECTED.equals(begin.status)) {
            JSONObject rejected = baseCommandResponse(command, cmd.isEmpty() ? "commandResp" : cmd + "Resp");
            try {
                rejected.put("code", 4001).put("status", "REJECTED")
                        .put("errorCode", begin.code).put("msg", begin.message);
                sendSocket(rejected);
                record("security.command.rejected", rejected);
            } catch (Exception error) {
                recordCommandSendFailure(rejected, error);
            }
            return;
        }

        try {
            if ("remoteOpen".equals(cmd)) handleRemoteOpen(command);
            else if ("remoteEjectAll".equals(cmd)) handleRemoteEjectAll(command);
            else if ("queryStatus".equals(cmd)) handleQueryStatus(command);
            else if ("syncUser".equals(cmd)) handleSyncUser(command);
            else if ("syncEmployeeData".equals(cmd) || "syncFaceData".equals(cmd) || "syncFingerData".equals(cmd)) handleDataSyncCommand(command);
            else if ("syncConfig".equals(cmd)) handleSyncConfig(command);
            else if ("firmwareUpgrade".equals(cmd)) handleFirmwareUpgrade(command);
            else if ("cancelUpgrade".equals(cmd)) handleCancelUpgrade(command);
            else if ("deviceSelfCheck".equals(cmd)) handleDeviceSelfCheck(command);
            else if ("enableLogUpload".equals(cmd) || "disableLogUpload".equals(cmd)) handleLogUploadToggle(command);
            else if ("restartApp".equals(cmd)) handleRestartApp(command);
            else completeCommand(command, baseCommandResponse(command, cmd + "Resp")
                    .put("code", 9000).put("status", "UNSUPPORTED")
                    .put("msg", "unsupported command"), false);
        } catch (Exception error) {
            try {
                JSONObject response = baseCommandResponse(command, cmd.isEmpty() ? "commandResp" : cmd + "Resp")
                        .put("code", 9000).put("status", "FAILED")
                        .put("msg", safeMessage(error));
                if (error instanceof DeviceOperationEngine.OperationException) {
                    DeviceOperationEngine.OperationException operationError = (DeviceOperationEngine.OperationException) error;
                    response.put("operationId", operationError.getOperationId())
                            .put("errorCode", operationError.getFailureCode());
                }
                completeCommand(command, response, false);
            } catch (Exception ignored) { }
        }
    }

    private void handleRemoteOpen(JSONObject command) throws Exception {
        int slotId = command.optInt("slotId", -1);
        String requestMsgId = command.optString("msgId", "");
        JSONObject response = baseCommandResponse(command, "remoteOpenResp").put("slotId", slotId);
        try {
            JSONObject result = operationEngine.openDoor(slotId, true, "MQTT", requestMsgId, "");
            String operationId = result.optString("operationId", "");
            response.put("code", 0).put("status", "BOARD_ACKED")
                    .put("operationId", operationId)
                    .put("physicalConfirmationRequired", true)
                    .put("result", result);
            sendCardEvent(slotId, "TAKE", command.optString("authType", "REMOTE"), operationId,
                    requestMsgId, command.optString("employeeId", ""));
            completeCommand(command, response, true);
        } catch (Exception error) {
            response.put("code", 4003).put("status", "FAILED").put("msg", safeMessage(error));
            if (error instanceof DeviceOperationEngine.OperationException) {
                DeviceOperationEngine.OperationException operationError = (DeviceOperationEngine.OperationException) error;
                response.put("operationId", operationError.getOperationId())
                        .put("errorCode", operationError.getFailureCode());
            }
            completeCommand(command, response, false);
        }
    }

    private void handleSyncUser(JSONObject command) throws JSONException {
        recordAndPublish("sync.user.start", "sync.statusChanged", new JSONObject()
                .put("state", "SYNCING")
                .put("cmd", "syncUser")
                .put("message", "正在同步员工/人脸/指纹数据")
                .put("msgId", command.optString("msgId", "")));
        new Thread(() -> {
            try {
                JSONObject result = dataSyncManager.syncAll(command);
                JSONObject responseData = BackendHttpClient.copyWithout(result, "snapshot");
                JSONObject response = baseCommandResponse(command, "syncUserResp")
                        .put("code", 0).put("status", "SUCCESS").put("data", responseData);
                completeCommand(command, response, true);
                JSONObject event = new JSONObject(responseData.toString())
                        .put("state", "SUCCESS")
                        .put("message", "同步完成")
                        .put("snapshot", result.optJSONObject("snapshot"));
                recordAndPublish("sync.user.success", "sync.completed", event);
            } catch (Exception error) {
                try {
                    completeCommand(command, baseCommandResponse(command, "syncUserResp")
                            .put("code", 9000).put("status", "FAILED")
                            .put("msg", safeMessage(error)), false);
                    recordAndPublish("sync.user.failed", "sync.statusChanged", new JSONObject()
                            .put("state", "ERROR").put("cmd", "syncUser")
                            .put("message", safeMessage(error)));
                } catch (Exception ignored) { }
            }
        }, "sync-user").start();
    }

    private void handleDataSyncCommand(JSONObject command) throws JSONException {
        String cmd = command.optString("cmd", "");
        recordAndPublish("sync.data.start", "sync.statusChanged", new JSONObject()
                .put("state", "SYNCING")
                .put("cmd", cmd)
                .put("message", "正在执行 " + cmd)
                .put("msgId", command.optString("msgId", "")));
        new Thread(() -> {
            try {
                JSONObject result;
                if ("syncEmployeeData".equals(cmd)) result = dataSyncManager.syncEmployees(command);
                else if ("syncFaceData".equals(cmd)) result = dataSyncManager.syncFaces(command);
                else result = dataSyncManager.syncFingers(command);
                JSONObject responseData = BackendHttpClient.copyWithout(result, "snapshot");
                completeCommand(command, baseCommandResponse(command, cmd + "Resp")
                        .put("code", 0).put("status", "SUCCESS").put("data", responseData), true);
                JSONObject event = new JSONObject(responseData.toString())
                        .put("state", "SUCCESS")
                        .put("cmd", cmd)
                        .put("message", "同步完成")
                        .put("snapshot", result.optJSONObject("snapshot"));
                recordAndPublish("sync.data.success", "sync.completed", event);
            } catch (Exception error) {
                try {
                    completeCommand(command, baseCommandResponse(command, cmd + "Resp")
                            .put("code", 9000).put("status", "FAILED")
                            .put("msg", safeMessage(error)), false);
                    recordAndPublish("sync.data.failed", "sync.statusChanged", new JSONObject()
                            .put("state", "ERROR").put("cmd", cmd)
                            .put("message", safeMessage(error)));
                } catch (Exception ignored) { }
            }
        }, "sync-data").start();
    }

    private void handleRemoteEjectAll(JSONObject command) throws Exception {
        JSONObject response = baseCommandResponse(command, "remoteEjectAllResp");
        if (!command.optBoolean("confirm", false)) {
            completeCommand(command, response.put("code", 4001).put("status", "REJECTED")
                    .put("msg", "confirm required").put("ejectedCount", 0), false);
            return;
        }
        try {
            JSONObject result = operationEngine.openAllDoors(true, "MQTT", command.optString("msgId", ""));
            int successCount = result.optInt("successCount", 0);
            int failedCount = result.optInt("failedCount", 0);
            response.put("code", failedCount == 0 ? 0 : 4001)
                    .put("status", failedCount == 0 ? "BOARD_ACKED" : successCount > 0 ? "PARTIAL" : "FAILED")
                    .put("msg", failedCount == 0 ? "success" : "部分或全部单板未应答")
                    .put("operationId", result.optString("operationId", ""))
                    .put("physicalConfirmationRequired", successCount > 0)
                    .put("ejectedCount", successCount)
                    .put("failedCount", failedCount)
                    .put("failures", result.optJSONArray("failures"));
            completeCommand(command, response, failedCount == 0);
        } catch (Exception error) {
            response.put("code", 4003).put("status", "FAILED")
                    .put("msg", safeMessage(error)).put("ejectedCount", 0);
            if (error instanceof DeviceOperationEngine.OperationException) {
                response.put("operationId", ((DeviceOperationEngine.OperationException) error).getOperationId());
            }
            completeCommand(command, response, false);
        }
    }

    private void handleQueryStatus(JSONObject command) throws Exception {
        int slotId = command.optInt("slotId", -1);
        JSONArray data = slotRepository.snapshotBackendSlots(slotId);
        completeCommand(command, baseCommandResponse(command, "statusResp")
                .put("code", 0).put("status", "SUCCESS").put("data", data), true);
    }

    private void handleSyncConfig(JSONObject command) throws Exception {
        JSONObject settings = new NativeSettingsRepository(this).load();
        completeCommand(command, baseCommandResponse(command, "syncConfigResp")
                .put("code", 0).put("status", "SUCCESS").put("msg", "accepted")
                .put("deviceCode", currentDeviceCode())
                .put("configUpdatedAt", settings.optLong("provisionedAt", 0L)), true);
    }

    private void handleFirmwareUpgrade(JSONObject command) throws Exception {
        completeCommand(command, baseCommandResponse(command, "firmwareUpgradeResp")
                .put("code", 501).put("status", "NOT_SUPPORTED")
                .put("msg", "当前版本尚未实现固件下载安装，不会伪报accepted")
                .put("firmwareVersion", command.optString("firmwareVersion", command.optString("version", "")))
                .put("downloadUrl", absoluteBackendUrl(command.optString("downloadUrl", ""))), false);
    }

    private void handleCancelUpgrade(JSONObject command) throws Exception {
        completeCommand(command, baseCommandResponse(command, "cancelUpgradeResp")
                .put("code", 501).put("status", "NOT_SUPPORTED")
                .put("msg", "当前没有可取消的真实固件升级任务"), false);
    }

    private void handleDeviceSelfCheck(JSONObject command) throws Exception {
        JSONObject data = new JSONObject()
                .put("deviceCode", currentDeviceCode())
                .put("appVersion", BuildConfig.VERSION_NAME)
                .put("appVersionCode", BuildConfig.VERSION_CODE)
                .put("serial", serialManager == null ? JSONObject.NULL : serialManager.snapshot())
                .put("backend", webSocketManager == null ? JSONObject.NULL : webSocketManager.snapshot())
                .put("sync", syncSnapshot())
                .put("slotSummary", slotSummary())
                .put("timestamp", System.currentTimeMillis());
        reportRuntimeEvent("selfCheckReport", "/api/v1/device/selfcheck", data);
        reportRuntimeEvent("statisticsReport", "/api/v1/statistics/report", statisticsData(data.optJSONObject("slotSummary")));
        completeCommand(command, baseCommandResponse(command, "deviceSelfCheckResp")
                .put("code", 0).put("status", "SUCCESS").put("msg", "success")
                .put("data", data), true);
    }

    private void handleLogUploadToggle(JSONObject command) throws Exception {
        JSONObject settings = new NativeSettingsRepository(this).load();
        settings.put("logUploadEnabled", command.optBoolean("enabled",
                "enableLogUpload".equals(command.optString("cmd"))));
        new NativeSettingsRepository(this).save(settings);
        boolean enabled = settings.optBoolean("logUploadEnabled");
        record("socket.logUpload", new JSONObject().put("enabled", enabled)
                .put("operatorId", command.optString("operatorId", "")));
        reportLog("INFO", "LOG_UPLOAD", enabled ? "日志上传已开启" : "日志上传已关闭");
        completeCommand(command, baseCommandResponse(command, command.optString("cmd", "") + "Resp")
                .put("code", 0).put("status", "SUCCESS").put("enabled", enabled), true);
    }

    private void handleRestartApp(JSONObject command) throws Exception {
        completeCommand(command, baseCommandResponse(command, "restartAppResp")
                .put("code", 0).put("status", "ACCEPTED").put("msg", "restarting"), true);
        long delay = Math.max(0, command.optLong("delayMs", 3000L));
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(intent);
            }
        }, delay);
    }

    private JSONObject baseCommandResponse(JSONObject command, String responseCmd) {
        JSONObject response = new JSONObject();
        try {
            response.put("cmd", responseCmd == null || responseCmd.trim().isEmpty() ? "commandResp" : responseCmd)
                    .put("requestMsgId", command == null ? "" : command.optString("msgId", ""))
                    .put("deviceCode", currentDeviceCode())
                    .put("timestamp", System.currentTimeMillis());
        } catch (JSONException ignored) { }
        return response;
    }

    private void completeCommand(JSONObject command, JSONObject response, boolean success) {
        String msgId = command == null ? "" : command.optString("msgId", "");
        if (inboundCommandRepository != null) {
            boolean persisted = success
                    ? inboundCommandRepository.complete(msgId, response)
                    : inboundCommandRepository.fail(msgId, response);
            if (!persisted) {
                try {
                    record("socket.command.idempotency.persistFailed", new JSONObject()
                            .put("msgId", msgId)
                            .put("success", success)
                            .put("responseCmd", response == null ? "" : response.optString("cmd", "")));
                } catch (JSONException ignored) { }
            }
        }
        try {
            sendSocket(response);
        } catch (Exception error) {
            recordCommandSendFailure(response, error);
        }
    }

    private void recordCommandSendFailure(JSONObject response, Exception error) {
        try {
            record("socket.command.response.failed", new JSONObject()
                    .put("message", safeMessage(error))
                    .put("response", response == null ? JSONObject.NULL : response));
        } catch (JSONException ignored) { }
    }

    private static String safeMessage(Throwable error) {
        if (error == null) return "unknown";
        String value = error.getMessage();
        return value == null || value.trim().isEmpty() ? error.getClass().getSimpleName() : value;
    }

    private void reportSlotStatus(JSONObject slot) {
        try {
            JSONArray slots = new JSONArray().put(toBackendSlot(slot));
            sendSocket(new JSONObject()
                    .put("cmd", "statusReport")
                    .put("data", new JSONObject().put("slots", slots)));
            maybeReportHardwareFault(slot);
        } catch (Exception ignored) { }
    }

    private synchronized void startSlotStatusReporter(JSONObject settings) {
        stopSlotStatusReporter();
        long intervalMs = parsePositiveLong(settings == null ? null : settings.opt("slotStatusReportIntervalMs"), 10000L);
        slotReportTask = slotReportExecutor.scheduleAtFixedRate(this::reportSlotSnapshot, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    private synchronized void stopSlotStatusReporter() {
        if (slotReportTask != null) {
            slotReportTask.cancel(false);
            slotReportTask = null;
        }
    }

    private void reportSlotSnapshot() {
        if (webSocketManager == null || !webSocketManager.isAuthenticated()) return;
        try {
            JSONArray source = slotRepository.snapshotSlots();
            JSONArray known = new JSONArray();
            for (int index = 0; index < source.length(); index++) {
                JSONObject slot = source.getJSONObject(index);
                if (slot.optLong("updatedAt", 0L) > 0L) known.put(toBackendSlot(slot));
            }
            if (known.length() == 0) return;
            sendSocket(new JSONObject()
                    .put("cmd", "statusReport")
                    .put("data", new JSONObject()
                            .put("slots", known)
                            .put("summary", slotRepository.summary())
                            .put("timestamp", System.currentTimeMillis())));
        } catch (Exception error) {
            try { record("socket.status.report.failed", new JSONObject().put("message", error.getMessage())); }
            catch (JSONException ignored) { }
        }
    }

    private void maybeReportHardwareFault(JSONObject slot) {
        if (slot == null) return;
        String status = slot.optString("status", "");
        String faultCode = slot.optString("faultCode", "");
        boolean hasFault = "CHARGING_FAULT".equals(status) || "COMMUNICATION_FAULT".equals(status)
                || "ILLEGAL_CARD".equals(status) || (faultCode != null && !faultCode.trim().isEmpty());
        if (!hasFault) return;
        try {
            JSONObject data = new JSONObject()
                    .put("deviceId", currentDeviceCode())
                    .put("slotId", slot.optInt("slotNumber"))
                    .put("faultCode", parseFaultCode(faultCode))
                    .put("faultMsg", slot.optString("faultMessage", status))
                    .put("timestamp", System.currentTimeMillis());
            reportRuntimeEvent("hardwareFault", "/api/v1/fault/report", data);
        } catch (Exception ignored) { }
    }

    private void reportRuntimeEvent(String cmd, String httpPath, JSONObject data) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("cmd", cmd).put("data", data == null ? new JSONObject() : data);
            try { sendSocket(payload); }
            catch (Exception error) { record("socket." + cmd + ".failed", new JSONObject().put("message", error.getMessage()).put("payload", payload)); }
            if (httpPath != null && !httpPath.trim().isEmpty()) postRuntimeHttp(httpPath, data);
        } catch (JSONException ignored) { }
    }

    private void reportLog(String level, String tag, String content) {
        try {
            JSONObject settings = new com.xingyao.card.core.NativeSettingsRepository(this).load();
            if (!settings.optBoolean("logUploadEnabled", false)) return;
            JSONObject data = new JSONObject()
                    .put("level", level)
                    .put("tag", tag)
                    .put("content", content)
                    .put("timestamp", System.currentTimeMillis());
            reportRuntimeEvent("logReport", "/api/v1/log/report", data);
        } catch (Exception ignored) { }
    }

    private void postRuntimeHttp(String path, JSONObject data) {
        new Thread(() -> {
            try {
                JSONObject settings = new com.xingyao.card.core.NativeSettingsRepository(this).load();
                JSONObject result = new BackendHttpClient(httpBaseUrl(settings), settings.optString("deviceToken"))
                        .post(path, data == null ? new JSONObject() : data);
                record("http." + path.replace("/", "."), result);
            } catch (Exception error) {
                try { record("http." + path.replace("/", ".") + ".failed", new JSONObject().put("message", error.getMessage())); }
                catch (JSONException ignored) { }
            }
        }, "runtime-http").start();
    }

    private JSONObject slotSummary() throws JSONException { return slotRepository.summary(); }

    private JSONObject statisticsData(JSONObject summary) throws JSONException {
        JSONObject source = summary == null ? new JSONObject() : summary;
        return new JSONObject()
                .put("statDate", new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()))
                .put("takeCount", 0)
                .put("returnCount", 0)
                .put("occupiedCount", source.optInt("occupiedCount", 0))
                .put("emptyCount", source.optInt("emptyCount", 0))
                .put("faultCount", source.optInt("faultCount", 0))
                .put("chargingCount", source.optInt("chargingCount", 0))
                .put("fullCount", source.optInt("fullCount", 0));
    }

    private String absoluteBackendUrl(String value) {
        String raw = value == null ? "" : value.trim();
        if (raw.isEmpty() || raw.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) return raw;
        try {
            JSONObject settings = new com.xingyao.card.core.NativeSettingsRepository(this).load();
            String base = httpBaseUrl(settings);
            return base + (raw.startsWith("/") ? raw : "/" + raw);
        } catch (Exception ignored) {
            return raw;
        }
    }

    private void sendCardEvent(int slotId, String eventType, String authType, String operationId,
                               String requestMsgId, String employeeId) {
        try {
            JSONObject slot = slotRepository.getSlot(slotId);
            JSONObject data = new JSONObject()
                    .put("cardNo", slot == null ? "" : slot.optString("cardNumber", ""))
                    .put("eventType", eventType)
                    .put("slotId", slotId)
                    .put("timestamp", System.currentTimeMillis())
                    .put("authType", normalizeAuthType(authType))
                    .put("operationId", operationId == null ? "" : operationId)
                    .put("requestMsgId", requestMsgId == null ? "" : requestMsgId)
                    .put("employeeId", employeeId == null ? "" : employeeId)
                    .put("physicalConfirmed", false);
            JSONObject payload = new JSONObject().put("cmd", "cardEvent").put("data", data);
            try { sendSocket(payload); } catch (Exception error) {
                record("socket.card.event.failed", new JSONObject().put("message", safeMessage(error)).put("payload", payload));
            }
            postCardEventHttp(data);
        } catch (Exception error) {
            try { record("card.event.build.failed", new JSONObject().put("message", safeMessage(error))); }
            catch (JSONException ignored) { }
        }
    }

    private void postCardEventHttp(JSONObject eventData) {
        new Thread(() -> {
            try {
                JSONObject settings = new com.xingyao.card.core.NativeSettingsRepository(this).load();
                JSONObject result = new BackendHttpClient(httpBaseUrl(settings), settings.optString("deviceToken"))
                        .post("/api/v1/card/event", eventData);
                record("http.card.event", result);
            } catch (Exception error) {
                try { record("http.card.event.failed", new JSONObject().put("message", error.getMessage())); }
                catch (JSONException ignored) { }
            }
        }, "card-event-http").start();
    }

    private static String httpBaseUrl(JSONObject settings) {
        String address = settings == null ? "" : settings.optString("apiBaseUrl", settings.optString("serverAddress", "")).trim();
        return BackendHttpClient.normalizeBaseUrl(address);
    }

    private static int parsePort(String value, int fallback) {
        try {
            int port = Integer.parseInt(value);
            return port > 0 && port <= 65535 ? port : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private int pickTakeSlot() {
        return slotRepository.pickTakeSlot();
    }

    private void sendSocket(JSONObject payload) throws Exception {
        if (webSocketManager == null) throw new IllegalStateException("TCP长连接未启动");
        webSocketManager.send(payload);
        record("socket.sent", payload);
    }

    private JSONObject cacheSlot(JSONObject slot) {
        if (slot == null) return null;
        try { return slotRepository.updateSlot(slot); }
        catch (JSONException ignored) { return null; }
    }

    private JSONObject toBackendSlot(JSONObject slot) throws JSONException {
        return slotRepository.toBackendSlot(slot);
    }

    private String currentDeviceCode() {
        try {
            JSONObject settings = new com.xingyao.card.core.NativeSettingsRepository(this).load();
            return settings.optString("deviceCode", settings.optString("deviceId", "DEV001"));
        }
        catch (Exception ignored) { return "DEV001"; }
    }

    private static String normalizeAuthType(String value) {
        String authType = value == null ? "" : value.toUpperCase();
        if ("FINGER".equals(authType)) return "FINGERPRINT";
        if ("FACE".equals(authType) || "FINGERPRINT".equals(authType) || "ADMIN".equals(authType) || "CARD".equals(authType)) return authType;
        return "ADMIN";
    }

    private static String mapBackendStatus(String status) {
        if ("EMPTY".equals(status)) return "EMPTY";
        if ("CHARGING".equals(status)) return "CHARGING";
        if ("FULL".equals(status)) return "FULL";
        if ("OCCUPIED".equals(status)) return "OCCUPIED";
        if ("CHARGING_FAULT".equals(status) || "COMMUNICATION_FAULT".equals(status) || "ILLEGAL_CARD".equals(status)) return "FAULT";
        return "OCCUPIED";
    }

    private static String mapChargeStatus(String status, String workStatus) {
        if ("CHARGING".equals(status) || "充电中".equals(workStatus)) return "CHARGING";
        if ("FULL".equals(status) || "充电结束".equals(workStatus)) return "FULL";
        if ("EMPTY".equals(status)) return "IDLE";
        return "IDLE";
    }

    private static JSONObject httpSnapshot() throws JSONException {
        if (instance == null) return new JSONObject().put("state", "DISCONNECTED").put("message", "原生服务未启动");
        JSONObject settings = new com.xingyao.card.core.NativeSettingsRepository(instance).load();
        boolean ready = !settings.optString("deviceToken", "").isEmpty();
        return new JSONObject()
                .put("state", ready ? "READY" : "PENDING")
                .put("message", ready ? "HTTP Bearer Token 已保存" : "等待设备注册")
                .put("apiBaseUrl", httpBaseUrl(settings))
                .put("deviceCode", settings.optString("deviceCode", settings.optString("deviceId", "")));
    }

    private static JSONObject syncSnapshot() throws JSONException {
        if (instance == null || instance.dataRepository == null) {
            return new JSONObject().put("state", "PENDING").put("message", "同步服务未启动");
        }
        JSONObject snapshot = instance.dataRepository.snapshot();
        JSONArray employees = snapshot.optJSONArray("employees");
        JSONArray faces = snapshot.optJSONArray("faceFeatures");
        JSONArray fingers = snapshot.optJSONArray("fingerFeatures");
        JSONObject result = new JSONObject()
                .put("state", "READY")
                .put("message", "同步数据已就绪")
                .put("employeeCount", employees == null ? 0 : employees.length())
                .put("faceCount", faces == null ? 0 : faces.length())
                .put("fingerCount", fingers == null ? 0 : fingers.length())
                .put("employeeSyncVersion", snapshot.optLong("employeeSyncVersion", 0L))
                .put("faceSyncVersion", snapshot.optLong("faceSyncVersion", 0L))
                .put("fingerSyncVersion", snapshot.optLong("fingerSyncVersion", 0L))
                .put("updatedAt", snapshot.optLong("updatedAt", 0L));
        if (instance.arcFaceManager != null) {
            result.put("faceTemplateCount", instance.arcFaceManager.templateSummary().optInt("templateCount", 0));
        }
        return result;
    }

    private static int parseFaultCode(String value) {
        if (value == null || value.isEmpty()) return 0;
        try { return value.startsWith("0x") || value.startsWith("0X") ? Integer.parseInt(value.substring(2), 16) : Integer.parseInt(value); }
        catch (Exception ignored) { return 0; }
    }

    private static long parsePositiveLong(Object value, long fallback) {
        try {
            long parsed = Long.parseLong(String.valueOf(value));
            return parsed > 0L ? parsed : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private void record(String category, JSONObject payload) {
        if (eventLogRepository != null) eventLogRepository.append(category, payload);
    }

    private void publish(String event, JSONObject data) {
        DeviceEventListener currentListener = eventListener;
        if (currentListener != null) currentListener.onDeviceEvent(event, data);
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("工作卡柜设备服务")
                .setContentText("串口与长连接原生服务骨架正在运行")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "设备核心服务", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }
}
