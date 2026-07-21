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
import java.util.concurrent.Executors;
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
    private final SlotStateRepository slotRepository = new SlotStateRepository();
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
            @Override public void onCommand(JSONObject command) { handleSocketCommand(command); }
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
        return openDoor(slotNumber, administrator, "TAKE", administrator ? "ADMIN" : "FACE");
    }

    public static JSONObject openDoor(int slotNumber, boolean administrator, String eventType, String authType) throws Exception {
        if (instance == null || instance.serialManager == null) throw new IllegalStateException("原生设备服务未启动");
        JSONObject result = instance.serialManager.openDoor(slotNumber, administrator);
        instance.record("cabinet.door.open", result);
        instance.sendCardEvent(slotNumber, eventType, authType);
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
        if (instance == null || instance.serialManager == null) throw new IllegalStateException("原生设备服务未启动");
        JSONObject result = instance.serialManager.openAllDoors(administrator);
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
            try {
                JSONObject door = instance.serialManager.openDoor(slotNumber, false);
                result.put("slotNumber", slotNumber).put("doorOpen", true).put("door", door);
                instance.sendCardEvent(slotNumber, "TAKE", "FACE");
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
        if (status != null && "CONNECTED".equals(status.optString("state"))) {
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
        String cmd = command.optString("cmd", "");
        try {
            if ("remoteOpen".equals(cmd)) handleRemoteOpen(command);
            else if ("remoteEjectAll".equals(cmd)) handleRemoteEjectAll(command);
            else if ("queryStatus".equals(cmd)) handleQueryStatus(command);
            else if ("syncUser".equals(cmd)) handleSyncUser(command);
            else if ("syncEmployeeData".equals(cmd) || "syncFaceData".equals(cmd) || "syncFingerData".equals(cmd)) handleDataSyncCommand(command);
            else if ("syncConfig".equals(cmd)) handleSyncConfig();
            else if ("firmwareUpgrade".equals(cmd)) handleFirmwareUpgrade(command);
            else if ("cancelUpgrade".equals(cmd)) handleCancelUpgrade(command);
            else if ("deviceSelfCheck".equals(cmd)) handleDeviceSelfCheck(command);
            else if ("enableLogUpload".equals(cmd) || "disableLogUpload".equals(cmd)) handleLogUploadToggle(command);
            else if ("restartApp".equals(cmd)) handleRestartApp(command);
            else sendSocket(new JSONObject().put("cmd", cmd + "Resp").put("code", 9000).put("msg", "unsupported command"));
        } catch (Exception error) {
            try {
                sendSocket(new JSONObject().put("cmd", cmd + "Resp").put("code", 9000).put("msg", error.getMessage()));
            } catch (Exception ignored) { }
        }
    }

    private void handleRemoteOpen(JSONObject command) throws Exception {
        int slotId = command.optInt("slotId", -1);
        JSONObject response = new JSONObject().put("cmd", "remoteOpenResp").put("slotId", slotId);
        try {
            serialManager.openDoor(slotId, true);
            response.put("code", 0).put("status", "OPENED");
            sendCardEvent(slotId, "TAKE", command.optString("authType", "REMOTE"));
        } catch (Exception error) {
            response.put("code", 4003).put("status", "FAILED").put("msg", error.getMessage());
        }
        sendSocket(response);
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
                sendSocket(new JSONObject()
                        .put("cmd", "syncUserResp")
                        .put("data", responseData));
                JSONObject event = new JSONObject(responseData.toString())
                        .put("state", "SUCCESS")
                        .put("message", "同步完成")
                        .put("snapshot", result.optJSONObject("snapshot"));
                recordAndPublish("sync.user.success", "sync.completed", event);
            } catch (Exception error) {
                try {
                    sendSocket(new JSONObject()
                            .put("cmd", "syncUserResp")
                            .put("data", new JSONObject()
                                    .put("code", 9000)
                                    .put("msg", error.getMessage())));
                    recordAndPublish("sync.user.failed", "sync.statusChanged", new JSONObject()
                            .put("state", "ERROR")
                            .put("cmd", "syncUser")
                            .put("message", error.getMessage()));
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
                sendSocket(new JSONObject()
                        .put("cmd", cmd + "Resp")
                        .put("data", responseData));
                JSONObject event = new JSONObject(responseData.toString())
                        .put("state", "SUCCESS")
                        .put("cmd", cmd)
                        .put("message", "同步完成")
                        .put("snapshot", result.optJSONObject("snapshot"));
                recordAndPublish("sync.data.success", "sync.completed", event);
            } catch (Exception error) {
                try {
                    sendSocket(new JSONObject()
                            .put("cmd", cmd + "Resp")
                            .put("data", new JSONObject()
                                    .put("code", 9000)
                                    .put("msg", error.getMessage())));
                    recordAndPublish("sync.data.failed", "sync.statusChanged", new JSONObject()
                            .put("state", "ERROR")
                            .put("cmd", cmd)
                            .put("message", error.getMessage()));
                } catch (Exception ignored) { }
            }
        }, "sync-data").start();
    }

    private void handleRemoteEjectAll(JSONObject command) throws Exception {
        JSONObject response = new JSONObject().put("cmd", "remoteEjectAllResp");
        if (!command.optBoolean("confirm", false)) {
            sendSocket(response.put("code", 9000).put("msg", "confirm required").put("ejectedCount", 0));
            return;
        }
        try {
            JSONObject result = serialManager.openAllDoors(true);
            int successCount = result.optInt("successCount", 0);
            int failedCount = result.optInt("failedCount", 0);
            sendSocket(response
                    .put("code", failedCount == 0 ? 0 : 4001)
                    .put("msg", failedCount == 0 ? "success" : "部分或全部单板未应答")
                    .put("ejectedCount", successCount)
                    .put("failedCount", failedCount)
                    .put("failures", result.optJSONArray("failures")));
        } catch (Exception error) {
            sendSocket(response.put("code", 4003).put("msg", error.getMessage()).put("ejectedCount", 0));
        }
    }

    private void handleQueryStatus(JSONObject command) throws Exception {
        int slotId = command.optInt("slotId", -1);
        JSONArray data = slotRepository.snapshotBackendSlots(slotId);
        sendSocket(new JSONObject().put("cmd", "statusResp").put("data", data));
    }

    private void handleSyncConfig() throws Exception {
        JSONObject settings = new com.xingyao.card.core.NativeSettingsRepository(this).load();
        sendSocket(new JSONObject().put("cmd", "syncConfigResp").put("code", 0).put("msg", "accepted")
                .put("deviceCode", currentDeviceCode()).put("configUpdatedAt", settings.optLong("provisionedAt", 0L)));
    }

    private void handleFirmwareUpgrade(JSONObject command) throws Exception {
        JSONObject response = new JSONObject()
                .put("cmd", "firmwareUpgradeResp")
                .put("code", 0)
                .put("msg", "accepted")
                .put("firmwareVersion", command.optString("firmwareVersion", command.optString("version", "")))
                .put("downloadUrl", absoluteBackendUrl(command.optString("downloadUrl", "")));
        sendSocket(response);
        JSONObject status = new JSONObject()
                .put("firmwareVersion", response.optString("firmwareVersion", ""))
                .put("status", "FAILED")
                .put("progress", 0)
                .put("errorMsg", "当前调试版仅确认收到升级指令，未执行固件下载安装");
        reportRuntimeEvent("upgradeStatus", "/api/v1/upgrade/status", status);
    }

    private void handleCancelUpgrade(JSONObject command) throws Exception {
        sendSocket(new JSONObject()
                .put("cmd", "cancelUpgradeResp")
                .put("code", 0)
                .put("msg", "cancelled"));
        reportRuntimeEvent("upgradeStatus", "/api/v1/upgrade/status", new JSONObject()
                .put("firmwareVersion", command.optString("firmwareVersion", ""))
                .put("status", "CANCELLED")
                .put("progress", 0)
                .put("errorMsg", JSONObject.NULL));
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
        sendSocket(new JSONObject()
                .put("cmd", "deviceSelfCheckResp")
                .put("code", 0)
                .put("msg", "success")
                .put("data", data));
    }

    private void handleLogUploadToggle(JSONObject command) throws Exception {
        JSONObject settings = new com.xingyao.card.core.NativeSettingsRepository(this).load();
        settings.put("logUploadEnabled", command.optBoolean("enabled", "enableLogUpload".equals(command.optString("cmd"))));
        new com.xingyao.card.core.NativeSettingsRepository(this).save(settings);
        record("socket.logUpload", new JSONObject()
                .put("enabled", settings.optBoolean("logUploadEnabled"))
                .put("operatorId", command.optString("operatorId", "")));
        reportLog(settings.optBoolean("logUploadEnabled") ? "INFO" : "INFO", "LOG_UPLOAD",
                settings.optBoolean("logUploadEnabled") ? "日志上传已开启" : "日志上传已关闭");
    }

    private void handleRestartApp(JSONObject command) throws Exception {
        sendSocket(new JSONObject().put("cmd", "restartAppResp").put("code", 0).put("msg", "restarting"));
        long delay = Math.max(0, command.optLong("delayMs", 3000L));
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(intent);
            }
        }, delay);
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

    private void sendCardEvent(int slotId, String eventType, String authType) {
        try {
            JSONObject slot = slotRepository.getSlot(slotId);
            JSONObject data = new JSONObject()
                    .put("cardNo", slot == null ? "" : slot.optString("cardNumber", ""))
                    .put("eventType", eventType)
                    .put("slotId", slotId)
                    .put("timestamp", System.currentTimeMillis())
                    .put("authType", normalizeAuthType(authType));
            JSONObject payload = new JSONObject().put("cmd", "cardEvent").put("data", data);
            try { sendSocket(payload); } catch (Exception error) {
                record("socket.card.event.failed", new JSONObject().put("message", error.getMessage()).put("payload", payload));
            }
            postCardEventHttp(data);
        } catch (Exception ignored) { }
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
