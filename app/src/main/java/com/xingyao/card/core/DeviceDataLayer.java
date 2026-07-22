package com.xingyao.card.core;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Android data/business layer. This is the only layer used by the WebView facade.
 *
 * It owns business state, validation and orchestration. Serial/MQTT/HTTP adapters are injected
 * ports and never publish to Vue directly.
 */
public final class DeviceDataLayer {
    public interface SerialPort {
        JSONObject snapshot() throws JSONException;
        void configure(JSONObject settings);
        void reconnect();
        JSONObject setPollingEnabled(boolean enabled) throws JSONException;
        JSONObject listPorts() throws JSONException;
        JSONObject send(String data, String encoding) throws Exception;
        JSONObject openDoor(int slotNumber, boolean administrator) throws Exception;
        JSONObject querySlot(int slotNumber) throws Exception;
        JSONObject readVersion(int slotNumber) throws Exception;
        JSONObject openAllDoors(boolean administrator) throws Exception;
    }

    public interface BackendPort extends DeviceCommandCoordinator.BackendPort {
        JSONObject snapshot() throws JSONException;
        void configure(JSONObject settings);
        String transportMode();
    }

    private final Context context;
    private final NativeSettingsRepository settingsRepository;
    private final DeviceStateStore stateStore;
    private final DeviceDataRepository dataRepository;
    private final DeviceDataSyncManager syncManager;
    private final SerialPort serialPort;
    private final BackendPort backendPort;
    private final ArcFaceManager arcFaceManager;
    private final ArcFaceTemplateCleaner templateCleaner;
    private final BackendHttpGateway httpGateway;
    private final DeviceCommandCoordinator commandCoordinator;
    private final DeviceOperationEngine operationEngine;
    private final ExecutorService backendCommandExecutor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService reportExecutor = Executors.newSingleThreadScheduledExecutor();

    private ScheduledFuture<?> slotReportTask;
    private volatile boolean startupSyncRunning;
    private volatile boolean startupSyncCompleted;
    private volatile boolean stopped;

    public DeviceDataLayer(Context context,
                           NativeSettingsRepository settingsRepository,
                           DeviceStateStore stateStore,
                           DeviceDataRepository dataRepository,
                           DeviceDataSyncManager syncManager,
                           SerialPort serialPort,
                           BackendPort backendPort,
                           ArcFaceManager arcFaceManager,
                           ArcFaceTemplateCleaner templateCleaner,
                           BackendHttpGateway httpGateway,
                           InboundCommandRepository inboundRepository,
                           DeviceCommandCoordinator.AppControl appControl) {
        this.context = context.getApplicationContext();
        this.settingsRepository = settingsRepository;
        this.stateStore = stateStore;
        this.dataRepository = dataRepository;
        this.syncManager = syncManager;
        this.serialPort = serialPort;
        this.backendPort = backendPort;
        this.arcFaceManager = arcFaceManager;
        this.templateCleaner = templateCleaner;
        this.httpGateway = httpGateway;
        this.operationEngine = new DeviceOperationEngine(new DeviceOperationEngine.SerialGateway() {
            @Override public JSONObject openDoor(int slotNumber, boolean administrator) throws Exception {
                return DeviceDataLayer.this.serialPort.openDoor(slotNumber, administrator);
            }

            @Override public JSONObject openAllDoors(boolean administrator) throws Exception {
                return DeviceDataLayer.this.serialPort.openAllDoors(administrator);
            }
        }, stateStore::recordOperation);
        this.commandCoordinator = new DeviceCommandCoordinator(stateStore, settingsRepository,
                inboundRepository, syncManager, operationEngine, backendPort, httpGateway,
                appControl, this::applyRemoteSettings);
    }

    public void start(JSONObject settings) {
        stopped = false;
        JSONObject safeSettings = settings == null ? new JSONObject() : settings;
        stateStore.configure(safeSettings);
        try { stateStore.updateSection("serial", "serial.statusChanged", serialPort.snapshot()); }
        catch (Exception ignored) { }
        try { stateStore.updateSection("socket", "socket.statusChanged", backendPort.snapshot()); }
        catch (Exception ignored) { }
        try { stateStore.updateSection("http", "http.statusChanged", httpGateway.snapshot()); }
        catch (Exception ignored) { }
        try { stateStore.updateSection("recognitionEngine", "recognition.statusChanged", arcFaceManager.snapshot()); }
        catch (Exception ignored) { }
        refreshSyncSection();
        startSlotReporter(safeSettings);
    }

    public void stop() {
        stopped = true;
        stopSlotReporter();
        backendCommandExecutor.shutdownNow();
        reportExecutor.shutdownNow();
        stateStore.setListener(null);
    }

    public void setUiListener(DeviceStateStore.Listener listener) {
        stateStore.setListener(listener);
    }

    public JSONObject snapshot() throws JSONException {
        return stateStore.snapshot();
    }

    public JSONObject serialStatus() {
        return stateStore.section("serial");
    }

    public JSONObject backendStatus() {
        return stateStore.section("socket");
    }

    public JSONObject recognitionStatus() {
        return stateStore.section("recognitionEngine");
    }

    public JSONObject slots() throws JSONException {
        return stateStore.slotsSnapshot();
    }

    public JSONArray searchEmployees(String query) throws JSONException {
        return stateStore.searchEmployees(query);
    }

    public JSONObject deleteEmployee(String id) throws JSONException {
        String employeeId = stateStore.deleteEmployee(id);
        if (!employeeId.isEmpty()) templateCleaner.deleteTemplate(employeeId);
        return new JSONObject().put("success", !employeeId.isEmpty())
                .put("id", id).put("employeeId", employeeId);
    }

    public void applySettings(JSONObject settings) throws JSONException {
        applySettingsInternal(settings, true);
    }

    private void applyRemoteSettings(JSONObject settings) throws JSONException {
        applySettingsInternal(settings, false);
    }

    private void applySettingsInternal(JSONObject settings, boolean reconnectBackend) throws JSONException {
        JSONObject safeSettings = settings == null ? new JSONObject() : settings;
        stateStore.configure(safeSettings);
        serialPort.configure(safeSettings);
        if (reconnectBackend) backendPort.configure(safeSettings);
        startupSyncCompleted = false;
        startSlotReporter(safeSettings);
        stateStore.updateSection("http", "http.statusChanged", httpGateway.snapshot());
        stateStore.emit("settings.changed", settingsRepository.loadForUi());
    }

    public void reconnectSerial() {
        serialPort.reconnect();
    }

    public JSONObject setSerialPolling(boolean enabled) throws JSONException {
        JSONObject result = serialPort.setPollingEnabled(enabled);
        stateStore.updateSection("serial", "serial.statusChanged", result);
        stateStore.record("serial.polling.changed", result);
        return result;
    }

    public JSONObject listSerialPorts() throws JSONException {
        return serialPort.listPorts();
    }

    public JSONObject sendSerial(String data, String encoding) throws Exception {
        JSONObject result = serialPort.send(data, encoding);
        stateStore.record("serial.manual.sent", result);
        return result;
    }

    public JSONObject openDoor(int slotNumber, boolean administrator, String eventType,
                               String authType, String requestMsgId, String source,
                               String employeeId) throws Exception {
        JSONObject result = operationEngine.openDoor(slotNumber, administrator, source,
                requestMsgId, employeeId);
        stateStore.recordOperation("operation.openDoor.result", result);
        commandCoordinator.reportCardEvent(slotNumber, eventType, authType,
                result.optString("operationId", ""), requestMsgId, employeeId);
        return result;
    }

    public JSONObject querySlot(int slotNumber) throws Exception {
        JSONObject result = serialPort.querySlot(slotNumber);
        stateStore.record("serial.slot.query", result);
        return result;
    }

    public JSONObject readBoardVersion(int slotNumber) throws Exception {
        JSONObject result = serialPort.readVersion(slotNumber);
        stateStore.record("serial.board.version", result);
        return result;
    }

    public JSONObject openAllDoors(boolean administrator, String requestMsgId, String source)
            throws Exception {
        JSONObject result = operationEngine.openAllDoors(administrator, source, requestMsgId);
        stateStore.recordOperation("operation.openAll.result", result);
        return result;
    }

    public void restartFaceRecognition() {
        if (!stopped && arcFaceManager != null) arcFaceManager.restart();
    }

    public JSONObject enrollFace(String employeeId, String employeeName, byte[] frame,
                                 int width, int height) throws Exception {
        arcFaceManager.awaitReady(8000L);
        JSONObject result = arcFaceManager.enrollNv21(employeeId, employeeName, frame, width, height);
        dataRepository.markFaceRegistered(employeeId, employeeName, true);
        stateStore.record("biometric.face.enrolled", result);
        stateStore.emit("sync.employeeChanged", new JSONObject()
                .put("employeeId", employeeId).put("faceRegistered", true));
        return result;
    }

    public JSONObject verifyFace(byte[] frame, int width, int height) throws Exception {
        arcFaceManager.awaitReady(8000L);
        JSONObject result = arcFaceManager.verifyNv21(frame, width, height);
        if (result.optBoolean("success", false)) {
            int slotNumber = stateStore.pickTakeSlot();
            if (slotNumber < 1) {
                result.put("doorOpen", false).put("status", "NO_AVAILABLE_CARD")
                        .put("message", "人脸识别成功，但当前没有可取卡槽");
                stateStore.record("biometric.face.noAvailableCard", result);
                return result;
            }
            String employeeId = result.optString("employeeId", "");
            JSONObject door = openDoor(slotNumber, false, "TAKE", "FACE", "", "FACE", employeeId);
            result.put("slotNumber", slotNumber).put("doorOpen", true).put("door", door)
                    .put("operationId", door.optString("operationId", ""));
        }
        stateStore.record("biometric.face.verified", result);
        return result;
    }

    public void markFingerprintAuthorized(String employeeId, String employeeName) {
        try {
            dataRepository.markSystemBiometricAuthorized(employeeId, employeeName, true);
            JSONObject event = new JSONObject().put("employeeId", employeeId)
                    .put("systemBiometricAuthorized", true)
                    .put("fingerprintRegistered", false)
                    .put("scope", "SYSTEM_DEVICE_BIOMETRIC");
            stateStore.record("biometric.fingerprint.authorized", event);
            stateStore.emit("sync.employeeChanged", event);
        } catch (Exception error) {
            stateStore.record("biometric.fingerprint.employeeUpdateFailed", message(error));
        }
    }

    public void recordOperation(String category, JSONObject payload) {
        stateStore.recordOperation(category, payload);
    }

    public void onSerialStatus(JSONObject status) {
        stateStore.updateSection("serial", "serial.statusChanged", status);
    }

    public void onSerialData(JSONObject data) {
        stateStore.record("serial.traffic", data);
        stateStore.emit("serial.dataReceived", data);
    }

    public void onSlotStatus(JSONObject slot) {
        try {
            JSONObject updated = stateStore.updateSlot(slot);
            if (updated != null) commandCoordinator.reportHardwareFault(updated);
        } catch (Exception error) {
            stateStore.record("state.slot.updateFailed", message(error));
        }
    }

    public void onBackendStatus(JSONObject status) {
        stateStore.updateSection("socket", "socket.statusChanged", status);
        if (status != null && "AUTHENTICATED".equals(status.optString("state"))) {
            runStartupSyncIfNeeded();
        }
    }

    public void onBackendCommand(JSONObject command) {
        try {
            backendCommandExecutor.execute(() -> commandCoordinator.handle(command));
        } catch (RejectedExecutionException error) {
            stateStore.record("backend.command.executorRejected", message(error));
        }
    }

    public void onBackendMessage(JSONObject message) {
        stateStore.record("backend.message", message);
        stateStore.emit("socket.message", message);
    }

    public void onRecognitionStatus(JSONObject status) {
        stateStore.updateSection("recognitionEngine", "recognition.statusChanged", status);
    }

    private void runStartupSyncIfNeeded() {
        try {
            JSONObject settings = settingsRepository.load();
            if (!settings.optBoolean("startupDataSyncEnabled", true)) return;
        } catch (Exception ignored) { }
        synchronized (this) {
            if (startupSyncRunning || startupSyncCompleted || stopped) return;
            startupSyncRunning = true;
        }
        stateStore.updateSection("sync", "sync.statusChanged",
                eventState("SYNCING", "startup", "正在主动同步员工/人脸/指纹数据"));
        new Thread(() -> {
            try {
                JSONObject result = syncManager.syncAll(new JSONObject()
                        .put("full", false).put("source", "startup"));
                JSONObject response = BackendHttpClient.copyWithout(result, "snapshot")
                        .put("state", "SUCCESS").put("cmd", "startup")
                        .put("message", "启动同步完成")
                        .put("snapshot", result.optJSONObject("snapshot"));
                startupSyncCompleted = true;
                stateStore.updateSection("sync", "sync.completed", response);
            } catch (Exception error) {
                stateStore.updateSection("sync", "sync.statusChanged",
                        eventState("ERROR", "startup", "启动同步失败：" + safeMessage(error)));
            } finally {
                startupSyncRunning = false;
            }
        }, "startup-data-sync").start();
    }

    private void refreshSyncSection() {
        try {
            JSONObject snapshot = stateStore.businessDataSnapshot();
            JSONArray employees = snapshot.optJSONArray("employees");
            JSONArray faces = snapshot.optJSONArray("faceFeatures");
            JSONArray fingers = snapshot.optJSONArray("fingerFeatures");
            JSONObject sync = new JSONObject().put("state", "READY")
                    .put("message", "同步数据已就绪")
                    .put("employeeCount", employees == null ? 0 : employees.length())
                    .put("faceCount", faces == null ? 0 : faces.length())
                    .put("fingerCount", fingers == null ? 0 : fingers.length())
                    .put("employeeSyncVersion", snapshot.optLong("employeeSyncVersion", 0L))
                    .put("faceSyncVersion", snapshot.optLong("faceSyncVersion", 0L))
                    .put("fingerSyncVersion", snapshot.optLong("fingerSyncVersion", 0L))
                    .put("updatedAt", snapshot.optLong("updatedAt", 0L));
            if (arcFaceManager != null) {
                sync.put("faceTemplateCount",
                        arcFaceManager.templateSummary().optInt("templateCount", 0));
            }
            stateStore.updateSection("sync", "sync.statusChanged", sync);
        } catch (Exception error) {
            stateStore.updateSection("sync", "sync.statusChanged",
                    eventState("ERROR", "snapshot", safeMessage(error)));
        }
    }

    private synchronized void startSlotReporter(JSONObject settings) {
        stopSlotReporter();
        long intervalMs = parsePositiveLong(settings == null ? null
                : settings.opt("slotStatusReportIntervalMs"), 10000L);
        slotReportTask = reportExecutor.scheduleAtFixedRate(commandCoordinator::reportSlotSnapshot,
                intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    private synchronized void stopSlotReporter() {
        if (slotReportTask != null) {
            slotReportTask.cancel(false);
            slotReportTask = null;
        }
    }

    private static JSONObject eventState(String state, String cmd, String message) {
        try {
            return new JSONObject().put("state", state).put("cmd", cmd).put("message", message);
        } catch (JSONException ignored) {
            return new JSONObject();
        }
    }

    private static long parsePositiveLong(Object value, long fallback) {
        try {
            long parsed = Long.parseLong(String.valueOf(value));
            return parsed > 0L ? parsed : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static JSONObject message(Throwable error) {
        try { return new JSONObject().put("message", safeMessage(error)); }
        catch (JSONException ignored) { return new JSONObject(); }
    }

    private static String safeMessage(Throwable error) {
        if (error == null) return "unknown";
        String value = error.getMessage();
        return value == null || value.trim().isEmpty()
                ? error.getClass().getSimpleName() : value;
    }
}
