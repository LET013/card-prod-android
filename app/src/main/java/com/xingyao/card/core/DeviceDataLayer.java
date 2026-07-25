package com.xingyao.card.core;

import android.graphics.Bitmap;

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
        void start();
        void stop();
        String transportMode();
    }

    private final NativeSettingsRepository settingsRepository;
    private final DeviceStateStore stateStore;
    private final DeviceDataRepository dataRepository;
    private final DeviceDataSyncManager syncManager;
    private final SerialPort serialPort;
    private final BackendPort backendPort;
    private final FaceAiManager faceAiManager;
    private final BackendHttpGateway httpGateway;
    private final DeviceProvisioningManager provisioningManager;
    private final DocumentedBackendService documentedBackendService;
    private final DeviceCommandCoordinator commandCoordinator;
    private final DeviceOperationEngine operationEngine;
    private final ExecutorService backendCommandExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService provisioningExecutor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService reportExecutor = Executors.newSingleThreadScheduledExecutor();

    private ScheduledFuture<?> slotReportTask;
    private volatile boolean startupSyncRunning;
    private volatile boolean startupSyncCompleted;
    private volatile boolean stopped;

    public DeviceDataLayer(
                           NativeSettingsRepository settingsRepository,
                           DeviceStateStore stateStore,
                           DeviceDataRepository dataRepository,
                           DeviceDataSyncManager syncManager,
                           SerialPort serialPort,
                           BackendPort backendPort,
                           FaceAiManager faceAiManager,
                           BackendHttpGateway httpGateway,
                           DeviceProvisioningManager provisioningManager,
                           DocumentedBackendService documentedBackendService,
                           InboundCommandRepository inboundRepository,
                           DeviceCommandCoordinator.AppControl appControl) {
        this.settingsRepository = settingsRepository;
        this.stateStore = stateStore;
        this.dataRepository = dataRepository;
        this.syncManager = syncManager;
        this.serialPort = serialPort;
        this.backendPort = backendPort;
        this.faceAiManager = faceAiManager;
        this.httpGateway = httpGateway;
        this.provisioningManager = provisioningManager;
        this.documentedBackendService = documentedBackendService;
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
        updateAuthorizationSection(safeSettings);
        try { stateStore.updateSection("serial", "serial.statusChanged", serialPort.snapshot()); }
        catch (Exception ignored) { }
        try { stateStore.updateSection("socket", "socket.statusChanged", backendPort.snapshot()); }
        catch (Exception ignored) { }
        try { stateStore.updateSection("http", "http.statusChanged", httpGateway.snapshot()); }
        catch (Exception ignored) { }
        try { stateStore.updateSection("recognitionEngine", "recognition.statusChanged", faceAiManager.snapshot()); }
        catch (Exception ignored) { }
        refreshSyncSection();
        startSlotReporter(safeSettings);
        provisionAndStartBackend(false);
    }

    public void stop() {
        stopped = true;
        stopSlotReporter();
        backendPort.stop();
        backendCommandExecutor.shutdownNow();
        provisioningExecutor.shutdownNow();
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

    public JSONObject reportStatusNow() {
        return commandCoordinator.reportSlotSnapshotNow();
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

    public JSONObject deleteEmployee(String id) throws Exception {
        JSONObject employee = dataRepository.employee(id);
        if (employee == null) return new JSONObject().put("success", false).put("id", id);
        String employeeId = employee.optString("employeeId", "").trim();
        JSONObject backend = documentedBackendService.disableEmployee(employeeId);
        String removed = stateStore.deleteEmployee(employeeId);
        if (!removed.isEmpty()) faceAiManager.deleteTemplate(removed);
        JSONObject result = new JSONObject().put("success", !removed.isEmpty())
                .put("id", id).put("employeeId", removed).put("backend", backend);
        stateStore.record("employee.disabled", result);
        return result;
    }

    public JSONObject upsertEmployee(JSONObject request) throws Exception {
        JSONObject result = documentedBackendService.upsertEmployee(request);
        stateStore.record("employee.upserted", result);
        return result;
    }

    public JSONObject upsertFaceFeature(JSONObject request) throws Exception {
        JSONObject result = documentedBackendService.upsertFaceFeature(request);
        stateStore.record("employee.face.upserted", result);
        return result;
    }

    public JSONArray registeredFaceEmployeeIds() throws Exception {
        JSONArray result = documentedBackendService.registeredFaceEmployeeIds();
        stateStore.record("employee.face.registered.loaded",
                new JSONObject().put("employeeIds", result));
        return result;
    }

    public JSONObject uploadFingerprintFeature(JSONObject request) throws Exception {
        JSONObject result = documentedBackendService.uploadFingerprint(request);
        stateStore.record("employee.fingerprint.uploaded", result);
        return result;
    }

    public JSONObject uploadFaceImage(String userId, String filePath,
                                      String faceFeature) throws Exception {
        JSONObject result = documentedBackendService.uploadFaceImage(userId, filePath, faceFeature);
        stateStore.record("employee.face.imageUploaded", result);
        return result;
    }

    public JSONObject uploadLogsBatch(JSONArray logs) throws Exception {
        JSONObject settings = settingsRepository.load();
        JSONObject result = documentedBackendService.uploadLogsBatch(
                settings.optString("deviceCode", ""), logs);
        stateStore.record("logs.batch.uploaded", result);
        return result;
    }

    public JSONObject downloadFirmware(String firmwareId, boolean resume) throws Exception {
        JSONObject result = documentedBackendService.downloadFirmware(firmwareId, resume);
        stateStore.record("firmware.downloaded", result);
        return result;
    }

    public JSONObject reportConfirmedTake(String cardNo, int slotId, String authType) throws Exception {
        return documentedBackendService.reportTake(cardNo, slotId, authType);
    }

    public JSONObject reportConfirmedReturn(String cardNo, int slotId, String authType) throws Exception {
        return documentedBackendService.reportReturn(cardNo, slotId, authType);
    }

    public void applySettings(JSONObject settings) throws JSONException {
        applySettingsInternal(settings, true);
    }

    private void applyRemoteSettings(JSONObject settings) throws JSONException {
        applySettingsInternal(settings, true);
    }

    private void applySettingsInternal(JSONObject settings, boolean reconnectBackend) throws JSONException {
        JSONObject safeSettings = settings == null ? new JSONObject() : settings;
        stateStore.configure(safeSettings);
        updateAuthorizationSection(safeSettings);
        serialPort.configure(safeSettings);
        httpGateway.configure(safeSettings);
        if (reconnectBackend) provisionAndStartBackend(false);
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
        return result;
    }

    public void restartFaceRecognition() {
        if (!stopped && faceAiManager != null) faceAiManager.restart();
    }

    public String extractFaceFeature(Bitmap bitmap) {
        return faceAiManager.extractFaceFeature(bitmap);
    }

    public float faceRecognitionThreshold() {
        try {
            double value = settingsRepository.load().optDouble("faceRecognitionThreshold", 0.8D);
            return (float) Math.max(0D, Math.min(1D, value));
        } catch (Exception ignored) {
            return 0.8F;
        }
    }

    public JSONObject completeFaceEnrollment(String employeeId, String employeeName,
                                             String faceFeature, float score) throws Exception {
        String id = employeeId == null ? "" : employeeId.trim();
        if (!dataRepository.hasEmployee(id)) {
            throw new IllegalStateException("员工不存在，禁止仅凭本机人脸录入创建后台员工资料");
        }
        JSONObject employee = dataRepository.employee(id);
        String resolvedName = employeeName == null || employeeName.trim().isEmpty()
                ? employee == null ? "" : employee.optString("employeeName", "")
                : employeeName.trim();
        faceAiManager.awaitReady(8000L);
        JSONObject backend = documentedBackendService.upsertFaceFeature(new JSONObject()
                .put("employeeId", id).put("faceFeature", faceFeature));
        JSONObject result = faceAiManager.enrollFeature(id, resolvedName, faceFeature, "LOCAL_CAMERA")
                .put("similarity", score).put("engine", "FaceAISDK")
                .put("backend", backend);
        dataRepository.markFaceRegistered(id, resolvedName, true);
        stateStore.record("biometric.face.enrolled", result);
        stateStore.emit("sync.employeeChanged", new JSONObject()
                .put("employeeId", id).put("faceRegistered", true));
        return result;
    }

    public JSONObject completeFaceVerification(String employeeId, float score) throws Exception {
        String id = employeeId == null ? "" : employeeId.trim();
        if (!dataRepository.hasEmployee(id)) {
            throw new IllegalStateException("FaceAISDK识别结果没有对应的Android员工数据：" + id);
        }
        JSONObject result = new JSONObject().put("success", true)
                .put("employeeId", id).put("similarity", score).put("engine", "FaceAISDK");
        int slotNumber = stateStore.pickTakeSlot();
        if (slotNumber < 1) {
            result.put("doorOpen", false).put("status", "NO_AVAILABLE_CARD")
                    .put("message", "人脸识别成功，但当前没有已确认可取的卡槽");
        } else {
            try {
                JSONObject door = openDoor(slotNumber, false, "TAKE", "FACE",
                        "", "FACE", id);
                result.put("doorOpen", true).put("slotNumber", slotNumber).put("door", door);
            } catch (IllegalStateException topologyError) {
                result.put("doorOpen", false).put("slotNumber", slotNumber)
                        .put("status", "SERIAL_TOPOLOGY_UNCONFIRMED")
                        .put("message", topologyError.getMessage());
            }
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

    public void onRuntimeToken(String token) {
        if (token == null || token.trim().isEmpty()) return;
        try {
            JSONObject settings = settingsRepository.load();
            settings.put("runtimeToken", token.trim());
            settingsRepository.save(settings);
            stateStore.record("backend.runtimeToken.received",
                    new JSONObject().put("present", true));
        } catch (Exception error) {
            stateStore.record("backend.runtimeToken.persistFailed", message(error));
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

    private void provisionAndStartBackend(boolean refreshCredentials) {
        if (stopped || provisioningExecutor.isShutdown()) return;
        stateStore.updateSection("socket", "socket.statusChanged",
                eventState("PROVISIONING", "backend", "正在执行设备注册、激活、配置和授权查询"));
        provisioningExecutor.execute(() -> {
            try {
                JSONObject saved = refreshCredentials
                        ? provisioningManager.refreshCredentials()
                        : provisioningManager.ensureProvisioned();
                if (stopped) return;
                httpGateway.configure(saved);
                stateStore.configure(saved);
                updateAuthorizationSection(saved);
                serialPort.configure(saved);
                backendPort.configure(saved);
                backendPort.start();
                stateStore.updateSection("http", "http.statusChanged", httpGateway.snapshot());
                stateStore.emit("settings.changed", settingsRepository.loadForUi());
            } catch (Exception error) {
                stateStore.updateSection("socket", "socket.statusChanged",
                        eventState("ERROR", "backend", safeMessage(error)));
                stateStore.record("backend.provisioning.failed", message(error));
            }
        });
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
                JSONObject result = syncManager.syncAll(false);
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
            if (faceAiManager != null) {
                sync.put("faceTemplateCount",
                        faceAiManager.templateSummary().optInt("templateCount", 0));
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

    private void updateAuthorizationSection(JSONObject settings) {
        JSONObject authorization = settings == null ? null : settings.optJSONObject("deviceAuthorization");
        if (authorization == null) {
            authorization = eventState("PENDING", "authorization", "等待设备授权查询");
        }
        stateStore.updateSection("deviceAuthorization", "authorization.statusChanged", authorization);
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
