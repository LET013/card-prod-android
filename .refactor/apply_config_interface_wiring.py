from pathlib import Path


def replace(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    if old not in text:
        raise RuntimeError(f"missing replacement in {path}: {old[:120]!r}")
    file.write_text(text.replace(old, new), encoding="utf-8")


# Native settings v3 -> v4 migration must preserve custom legacy endpoints, not the new defaults.
replace(
    "app/src/main/java/com/xingyao/card/core/NativeSettingsRepository.java",
    '''            String legacyServer = settings.optString("serverAddress", "").trim();
            if (settings.optString("httpServerAddress", "").trim().isEmpty()
                    && (legacyServer.startsWith("http://") || legacyServer.startsWith("https://"))) {
                settings.put("httpServerAddress", legacyServer);
            }
            if (settings.optString("tcpServerAddress", "").trim().isEmpty()
                    && !legacyServer.startsWith("http://") && !legacyServer.startsWith("https://")) {
                settings.put("tcpServerAddress", legacyServer);
            }
''',
    '''            String legacyServer = settings.optString("serverAddress", "").trim();
            String legacyHttp = settings.optString("apiBaseUrl", "").trim();
            String legacyMqtt = settings.optString("mqttBrokerUrl", "").trim();
            if (!legacyHttp.isEmpty()) settings.put("httpServerAddress", legacyHttp);
            else if (legacyServer.startsWith("http://") || legacyServer.startsWith("https://")) {
                settings.put("httpServerAddress", legacyServer);
            }
            if (!legacyMqtt.isEmpty()) settings.put("mqttServerAddress", legacyMqtt);
            if (!legacyServer.isEmpty()
                    && !legacyServer.startsWith("http://") && !legacyServer.startsWith("https://")) {
                settings.put("tcpServerAddress", legacyServer);
            }
''')

# Face template cleaner is a separate native storage adapter used by sync and employee deletion.
replace(
    "app/src/main/java/com/xingyao/card/core/DeviceDataSyncManager.java",
    '''    private final ArcFaceManager arcFaceManager;
    private final BackendHttpGateway httpGateway;
''',
    '''    private final ArcFaceManager arcFaceManager;
    private final ArcFaceTemplateCleaner templateCleaner;
    private final BackendHttpGateway httpGateway;
''')
replace(
    "app/src/main/java/com/xingyao/card/core/DeviceDataSyncManager.java",
    '''                                 DeviceDataRepository dataRepository,
                                 ArcFaceManager arcFaceManager,
                                 BackendHttpGateway httpGateway) {
''',
    '''                                 DeviceDataRepository dataRepository,
                                 ArcFaceManager arcFaceManager,
                                 ArcFaceTemplateCleaner templateCleaner,
                                 BackendHttpGateway httpGateway) {
''')
replace(
    "app/src/main/java/com/xingyao/card/core/DeviceDataSyncManager.java",
    '''        if (httpGateway == null) throw new IllegalArgumentException("httpGateway is required");
        this.settingsRepository = settingsRepository;
        this.dataRepository = dataRepository;
        this.arcFaceManager = arcFaceManager;
        this.httpGateway = httpGateway;
''',
    '''        if (templateCleaner == null) throw new IllegalArgumentException("templateCleaner is required");
        if (httpGateway == null) throw new IllegalArgumentException("httpGateway is required");
        this.settingsRepository = settingsRepository;
        this.dataRepository = dataRepository;
        this.arcFaceManager = arcFaceManager;
        this.templateCleaner = templateCleaner;
        this.httpGateway = httpGateway;
''')
replace(
    "app/src/main/java/com/xingyao/card/core/DeviceDataSyncManager.java",
    "arcFaceManager.deleteTemplate(employeeId);",
    "templateCleaner.deleteTemplate(employeeId);")
replace(
    "app/src/main/java/com/xingyao/card/core/DeviceDataSyncManager.java",
    "arcFaceManager.deleteTemplatesNotIn(activeEmployeeIds)",
    "templateCleaner.deleteTemplatesNotIn(activeEmployeeIds)")
replace(
    "app/src/main/java/com/xingyao/card/core/DeviceDataSyncManager.java",
    '''        if (arcFaceManager == null || deletedEmployeeIds == null) return;
''',
    '''        if (deletedEmployeeIds == null) return;
''')
replace(
    "app/src/main/java/com/xingyao/card/core/DeviceDataSyncManager.java",
    "try { arcFaceManager.deleteTemplate(id); }",
    "try { templateCleaner.deleteTemplate(id); }")

# State store returns the canonical employee id so the data layer can remove native templates.
replace(
    "app/src/main/java/com/xingyao/card/core/DeviceStateStore.java",
    '''    public boolean deleteEmployee(String id) throws JSONException {
        boolean deleted = dataRepository.deleteEmployee(id);
        if (deleted) {
            JSONObject event = new JSONObject().put("success", true).put("id", id);
            record("state.employee.deleted", event);
            emit("sync.employeeChanged", event);
        }
        return deleted;
    }
''',
    '''    public String deleteEmployee(String id) throws JSONException {
        String employeeId = dataRepository.deleteEmployee(id);
        if (!employeeId.isEmpty()) {
            JSONObject event = new JSONObject().put("success", true)
                    .put("id", id).put("employeeId", employeeId);
            record("state.employee.deleted", event);
            emit("sync.employeeChanged", event);
        }
        return employeeId;
    }
''')
replace(
    "app/src/main/java/com/xingyao/card/core/DeviceStateStore.java",
    '''
    public DeviceDataRepository dataRepository() {
        return dataRepository;
    }

    public SlotStateRepository slotRepository() {
        return slotRepository;
    }
''',
    "\n")

# Data layer owns employee deletion, system-biometric semantics and remote config application.
replace(
    "app/src/main/java/com/xingyao/card/core/DeviceDataLayer.java",
    '''    public interface BackendPort extends DeviceCommandCoordinator.BackendPort {
        JSONObject snapshot() throws JSONException;
        void configure(JSONObject settings);
    }
''',
    '''    public interface BackendPort extends DeviceCommandCoordinator.BackendPort {
        JSONObject snapshot() throws JSONException;
        void configure(JSONObject settings);
        String transportMode();
    }
''')
replace(
    "app/src/main/java/com/xingyao/card/core/DeviceDataLayer.java",
    '''    private final ArcFaceManager arcFaceManager;
    private final BackendHttpGateway httpGateway;
''',
    '''    private final ArcFaceManager arcFaceManager;
    private final ArcFaceTemplateCleaner templateCleaner;
    private final BackendHttpGateway httpGateway;
''')
replace(
    "app/src/main/java/com/xingyao/card/core/DeviceDataLayer.java",
    '''                           BackendPort backendPort,
                           ArcFaceManager arcFaceManager,
                           BackendHttpGateway httpGateway,
''',
    '''                           BackendPort backendPort,
                           ArcFaceManager arcFaceManager,
                           ArcFaceTemplateCleaner templateCleaner,
                           BackendHttpGateway httpGateway,
''')
replace(
    "app/src/main/java/com/xingyao/card/core/DeviceDataLayer.java",
    '''        this.arcFaceManager = arcFaceManager;
        this.httpGateway = httpGateway;
''',
    '''        this.arcFaceManager = arcFaceManager;
        this.templateCleaner = templateCleaner;
        this.httpGateway = httpGateway;
''')
replace(
    "app/src/main/java/com/xingyao/card/core/DeviceDataLayer.java",
    '''        this.commandCoordinator = new DeviceCommandCoordinator(stateStore, settingsRepository,
                inboundRepository, syncManager, operationEngine, backendPort, httpGateway, appControl);
''',
    '''        this.commandCoordinator = new DeviceCommandCoordinator(stateStore, settingsRepository,
                inboundRepository, syncManager, operationEngine, backendPort, httpGateway,
                appControl, this::applyRemoteSettings);
''')
replace(
    "app/src/main/java/com/xingyao/card/core/DeviceDataLayer.java",
    '''    public JSONObject deleteEmployee(String id) throws JSONException {
        boolean deleted = stateStore.deleteEmployee(id);
        return new JSONObject().put("success", deleted).put("id", id);
    }
''',
    '''    public JSONObject deleteEmployee(String id) throws JSONException {
        String employeeId = stateStore.deleteEmployee(id);
        if (!employeeId.isEmpty()) templateCleaner.deleteTemplate(employeeId);
        return new JSONObject().put("success", !employeeId.isEmpty())
                .put("id", id).put("employeeId", employeeId);
    }
''')
replace(
    "app/src/main/java/com/xingyao/card/core/DeviceDataLayer.java",
    '''    public void applySettings(JSONObject settings) throws JSONException {
        JSONObject safeSettings = settings == null ? new JSONObject() : settings;
        stateStore.configure(safeSettings);
        serialPort.configure(safeSettings);
        backendPort.configure(safeSettings);
        startupSyncCompleted = false;
        startSlotReporter(safeSettings);
        stateStore.updateSection("http", "http.statusChanged", httpGateway.snapshot());
    }
''',
    '''    public void applySettings(JSONObject settings) throws JSONException {
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
''')
replace(
    "app/src/main/java/com/xingyao/card/core/DeviceDataLayer.java",
    '''            dataRepository.markFingerprintRegistered(employeeId, employeeName, true);
            JSONObject event = new JSONObject().put("employeeId", employeeId)
                    .put("fingerprintRegistered", true)
                    .put("scope", "SYSTEM_DEVICE_BIOMETRIC");
''',
    '''            dataRepository.markSystemBiometricAuthorized(employeeId, employeeName, true);
            JSONObject event = new JSONObject().put("employeeId", employeeId)
                    .put("systemBiometricAuthorized", true)
                    .put("fingerprintRegistered", false)
                    .put("scope", "SYSTEM_DEVICE_BIOMETRIC");
''')

# Coordinator applies real /device/config, routes HTTP as a transport, and avoids duplicate HTTP reports.
replace(
    "app/src/main/java/com/xingyao/card/core/DeviceCommandCoordinator.java",
    '''    public interface BackendPort {
        void send(JSONObject payload) throws Exception;
        boolean isAuthenticated();
    }
''',
    '''    public interface BackendPort {
        void send(JSONObject payload) throws Exception;
        boolean isAuthenticated();
        String transportMode();
    }

    public interface ConfigControl {
        void apply(JSONObject settings) throws Exception;
    }
''')
replace(
    "app/src/main/java/com/xingyao/card/core/DeviceCommandCoordinator.java",
    '''    private final AppControl appControl;
''',
    '''    private final AppControl appControl;
    private final ConfigControl configControl;
''')
replace(
    "app/src/main/java/com/xingyao/card/core/DeviceCommandCoordinator.java",
    '''                                    BackendHttpGateway httpGateway,
                                    AppControl appControl) {
''',
    '''                                    BackendHttpGateway httpGateway,
                                    AppControl appControl,
                                    ConfigControl configControl) {
''')
replace(
    "app/src/main/java/com/xingyao/card/core/DeviceCommandCoordinator.java",
    '''        this.appControl = appControl;
''',
    '''        this.appControl = appControl;
        this.configControl = configControl;
''')
replace(
    "app/src/main/java/com/xingyao/card/core/DeviceCommandCoordinator.java",
    '''    private void handleSyncConfig(JSONObject command) throws Exception {
        JSONObject settings = settingsRepository.load();
        complete(command, baseResponse(command, "syncConfigResp")
                .put("code", 0).put("status", "SUCCESS").put("msg", "accepted")
                .put("deviceCode", currentDeviceCode())
                .put("configUpdatedAt", settings.optLong("provisionedAt", 0L)), true);
    }
''',
    '''    private void handleSyncConfig(JSONObject command) throws Exception {
        JSONObject current = settingsRepository.load();
        JSONObject remote = httpGateway.getData(BackendHttpGateway.DEVICE_CONFIG);
        JSONObject saved = settingsRepository.save(DeviceConfigMapper.apply(current, remote));
        if (configControl != null) configControl.apply(saved);
        complete(command, baseResponse(command, "syncConfigResp")
                .put("code", 0).put("status", "SUCCESS").put("msg", "success")
                .put("deviceCode", currentDeviceCode())
                .put("configUpdatedAt", saved.optLong("remoteConfigUpdatedAt", 0L)), true);
    }
''')
replace(
    "app/src/main/java/com/xingyao/card/core/DeviceCommandCoordinator.java",
    '''        reportRuntimeEvent("selfCheckReport", "/api/v1/device/selfcheck", data);
        reportRuntimeEvent("statisticsReport", "/api/v1/statistics/report",
                statisticsData(data.optJSONObject("slotSummary")));
''',
    '''        reportRuntimeEvent("selfCheckReport", BackendHttpGateway.DEVICE_SELF_CHECK, data);
''')
replace(
    "app/src/main/java/com/xingyao/card/core/DeviceCommandCoordinator.java",
    '''            try { send(payload); }
            catch (Exception error) {
                stateStore.record("backend." + cmd + ".failed", new JSONObject()
                        .put("message", safeMessage(error)).put("payload", payload));
            }
            if (httpPath != null && !httpPath.trim().isEmpty()) {
                postAsync(httpPath, data, "http." + cmd);
            }
''',
    '''            boolean sent = false;
            try { send(payload); sent = true; }
            catch (Exception error) {
                stateStore.record("backend." + cmd + ".failed", new JSONObject()
                        .put("message", safeMessage(error)).put("payload", payload));
            }
            if (!sent && !BackendEndpointSettings.MODE_HTTP.equals(backendPort.transportMode())
                    && httpPath != null && !httpPath.trim().isEmpty()) {
                postAsync(httpPath, data, "http." + cmd + ".fallback");
            }
''')
replace(
    "app/src/main/java/com/xingyao/card/core/DeviceCommandCoordinator.java",
    '''            JSONObject payload = new JSONObject().put("cmd", "cardEvent").put("data", data);
            try { send(payload); }
            catch (Exception error) {
                stateStore.record("backend.cardEvent.failed", new JSONObject()
                        .put("message", safeMessage(error)).put("payload", payload));
            }
            postAsync("/api/v1/card/event", data, "http.card.event");
''',
    '''            JSONObject payload = new JSONObject().put("cmd", "cardEvent").put("data", data);
            boolean sent = false;
            try { send(payload); sent = true; }
            catch (Exception error) {
                stateStore.record("backend.cardEvent.failed", new JSONObject()
                        .put("message", safeMessage(error)).put("payload", payload));
            }
            if (!sent && !BackendEndpointSettings.MODE_HTTP.equals(backendPort.transportMode())) {
                postAsync(BackendHttpGateway.CARD_EVENT, data, "http.card.event.fallback");
            }
''')
replace(
    "app/src/main/java/com/xingyao/card/core/DeviceCommandCoordinator.java",
    '''        if ("FACE".equals(authType) || "FINGERPRINT".equals(authType)
                || "ADMIN".equals(authType) || "CARD".equals(authType)) return authType;
        return "ADMIN";
''',
    '''        if ("FACE".equals(authType) || "FINGERPRINT".equals(authType)
                || "ADMIN".equals(authType) || "CARD".equals(authType)
                || "REMOTE".equals(authType)) return authType;
        return "UNKNOWN";
''')
# Remove fake statistics helper and imports once unused.
replace(
    "app/src/main/java/com/xingyao/card/core/DeviceCommandCoordinator.java",
    '''import java.text.SimpleDateFormat;
import java.util.Date;
''',
    "")
start = '''    private static JSONObject statisticsData(JSONObject summary) throws JSONException {
        JSONObject source = summary == null ? new JSONObject() : summary;
        return new JSONObject()
                .put("statDate", new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()))
                .put("takeCount", 0).put("returnCount", 0)
                .put("occupiedCount", source.optInt("occupiedCount", 0))
                .put("emptyCount", source.optInt("emptyCount", 0))
                .put("faultCount", source.optInt("faultCount", 0))
                .put("chargingCount", source.optInt("chargingCount", 0))
                .put("fullCount", source.optInt("fullCount", 0));
    }

'''
replace("app/src/main/java/com/xingyao/card/core/DeviceCommandCoordinator.java", start, "")

# Service injects the cleaner and exposes transport mode through the port.
replace(
    "app/src/main/java/com/xingyao/card/service/DeviceCoreService.java",
    '''import com.xingyao.card.core.ArcFaceManager;
''',
    '''import com.xingyao.card.core.ArcFaceManager;
import com.xingyao.card.core.ArcFaceTemplateCleaner;
''')
replace(
    "app/src/main/java/com/xingyao/card/service/DeviceCoreService.java",
    '''        arcFaceManager = new ArcFaceManager(this, status -> {
            if (holder[0] != null) holder[0].onRecognitionStatus(status);
        });
        DeviceDataSyncManager syncManager = new DeviceDataSyncManager(settingsRepository,
                dataRepository, arcFaceManager, httpGateway);
''',
    '''        arcFaceManager = new ArcFaceManager(this, status -> {
            if (holder[0] != null) holder[0].onRecognitionStatus(status);
        });
        ArcFaceTemplateCleaner templateCleaner = new ArcFaceTemplateCleaner(this);
        DeviceDataSyncManager syncManager = new DeviceDataSyncManager(settingsRepository,
                dataRepository, arcFaceManager, templateCleaner, httpGateway);
''')
replace(
    "app/src/main/java/com/xingyao/card/service/DeviceCoreService.java",
    '''            @Override public void send(JSONObject payload) throws Exception { backendManager.send(payload); }
            @Override public boolean isAuthenticated() { return backendManager.isAuthenticated(); }
''',
    '''            @Override public void send(JSONObject payload) throws Exception { backendManager.send(payload); }
            @Override public boolean isAuthenticated() { return backendManager.isAuthenticated(); }
            @Override public String transportMode() { return backendManager.transportMode(); }
''')
replace(
    "app/src/main/java/com/xingyao/card/service/DeviceCoreService.java",
    '''                syncManager, serialPort, backendPort, arcFaceManager, httpGateway,
''',
    '''                syncManager, serialPort, backendPort, arcFaceManager, templateCleaner, httpGateway,
''')

# Serial V1.5: direct one-byte slave addresses, group size is display metadata only.
replace(
    "app/src/main/java/com/xingyao/card/core/SerialConnectionManager.java",
    '''    private long pollingIntervalMs = POLLING_INTERVAL_MS;
''',
    '''    private long pollingIntervalMs = POLLING_INTERVAL_MS;
    private String cardNumberMode = "VISIBLE";
''')
replace(
    "app/src/main/java/com/xingyao/card/core/SerialConnectionManager.java",
    '''        pollingEnabled = settings != null && settings.optBoolean("serialPollingEnabled",
                settings.optBoolean("singleGroupPollingEnabled", false));
        open(configuredPort.isEmpty() ? DEFAULT_PORT : configuredPort, configuredBaud);
''',
    '''        pollingEnabled = settings != null && settings.optBoolean("serialPollingEnabled", true);
        cardNumberMode = settings == null ? "VISIBLE"
                : settings.optString("cardNumberMode", "VISIBLE").trim().toUpperCase(Locale.US);
        if (!"PHYSICAL".equals(cardNumberMode)) cardNumberMode = "VISIBLE";
        open(configuredPort.isEmpty() ? DEFAULT_PORT : configuredPort, configuredBaud);
''')
replace(
    "app/src/main/java/com/xingyao/card/core/SerialConnectionManager.java",
    '''                .put("commandGapMs", commandGapMs)
''',
    '''                .put("commandGapMs", commandGapMs)
                .put("cardNumberMode", cardNumberMode)
                .put("addressMode", "DIRECT")
''')
replace(
    "app/src/main/java/com/xingyao/card/core/SerialConnectionManager.java",
    '''        String cardNo = new String(frame.data, offset + 4, 15, StandardCharsets.US_ASCII).replace("\\u0000", "").trim();
''',
    '''        byte[] cardBytes = new byte[15];
        System.arraycopy(frame.data, offset + 4, cardBytes, 0, cardBytes.length);
        String rawCardHex = WorkCardProtocol.hex(cardBytes);
        String cardNo = "PHYSICAL".equals(cardNumberMode) ? rawCardHex
                : new String(cardBytes, StandardCharsets.US_ASCII).replace("\\u0000", "").trim();
''')
replace(
    "app/src/main/java/com/xingyao/card/core/SerialConnectionManager.java",
    '''                .put("doorStatus", mapDoor(door)).put("cardNumber", cardNo)
''',
    '''                .put("doorStatus", mapDoor(door)).put("cardNumber", cardNo)
                .put("rawCardHex", rawCardHex).put("cardNumberMode", cardNumberMode)
''')
replace(
    "app/src/main/java/com/xingyao/card/core/SerialConnectionManager.java",
    '''    private int serialAddressForSlot(int slotNumber) {
        int groupSize = Math.max(1, singleGroupCount);
        if (groupSize >= totalSlots) return slotNumber;
        return ((slotNumber - 1) % groupSize) + 1;
    }
    private int pollingAddressLimit() { return Math.max(1, Math.min(totalSlots, singleGroupCount)); }
''',
    '''    private int serialAddressForSlot(int slotNumber) {
        if (slotNumber < 1 || slotNumber > 255) {
            throw new IllegalArgumentException("V1.5从机地址必须在1至255之间");
        }
        return slotNumber;
    }
    private int pollingAddressLimit() { return Math.max(1, Math.min(totalSlots, 255)); }
''')
replace(
    "app/src/main/java/com/xingyao/card/core/SerialConnectionManager.java",
    '''                    .put("message", "已按单组数量发送全部卡门开门指令");
''',
    '''                    .put("message", "已按V1.5直接从机地址逐个发送开门指令");
''')

# Camera display rotation now follows the saved configuration.
replace(
    "app/src/main/java/com/xingyao/card/FaceEnrollmentActivity.java",
    '''import com.xingyao.card.core.DeviceRuntimeRegistry;
''',
    '''import com.xingyao.card.core.DeviceRuntimeRegistry;
import com.xingyao.card.core.NativeSettingsRepository;
''')
replace(
    "app/src/main/java/com/xingyao/card/FaceEnrollmentActivity.java",
    '''            camera.setDisplayOrientation(90);
''',
    '''            camera.setDisplayOrientation(configuredCameraRotation());
''')
replace(
    "app/src/main/java/com/xingyao/card/FaceEnrollmentActivity.java",
    '''    private int findFrontCamera() {
''',
    '''    private int configuredCameraRotation() {
        try {
            int rotation = new NativeSettingsRepository(this).load().optInt("cameraRotation", 90);
            return rotation == 0 || rotation == 90 || rotation == 180 || rotation == 270
                    ? rotation : 90;
        } catch (Exception ignored) {
            return 90;
        }
    }

    private int findFrontCamera() {
''')

print("configuration and interface wiring applied")
