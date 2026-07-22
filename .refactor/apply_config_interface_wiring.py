from pathlib import Path


def replace(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    if old not in text:
        raise RuntimeError(f"missing replacement in {path}: {old[:140]!r}")
    file.write_text(text.replace(old, new), encoding="utf-8")


# Data layer no longer carries an unused Context, and the operation state schema has one writer.
replace("app/src/main/java/com/xingyao/card/core/DeviceDataLayer.java",
        "import android.content.Context;\n\n", "")
replace("app/src/main/java/com/xingyao/card/core/DeviceDataLayer.java",
        "    private final Context context;\n", "")
replace("app/src/main/java/com/xingyao/card/core/DeviceDataLayer.java",
        "    public DeviceDataLayer(Context context,\n", "    public DeviceDataLayer(\n")
replace("app/src/main/java/com/xingyao/card/core/DeviceDataLayer.java",
        "        this.context = context.getApplicationContext();\n", "")
replace("app/src/main/java/com/xingyao/card/service/DeviceCoreService.java",
        "        dataLayer = new DeviceDataLayer(this, settingsRepository, stateStore, dataRepository,\n",
        "        dataLayer = new DeviceDataLayer(settingsRepository, stateStore, dataRepository,\n")
replace("app/src/main/java/com/xingyao/card/core/DeviceDataLayer.java",
        '''        stateStore.recordOperation("operation.openDoor.result", result);
''', "")
replace("app/src/main/java/com/xingyao/card/core/DeviceDataLayer.java",
        '''        stateStore.recordOperation("operation.openAll.result", result);
''', "")
replace("app/src/main/java/com/xingyao/card/core/DeviceDataLayer.java",
        '''    private void applyRemoteSettings(JSONObject settings) throws JSONException {
        applySettingsInternal(settings, false);
    }
''',
        '''    private void applyRemoteSettings(JSONObject settings) throws JSONException {
        applySettingsInternal(settings, true);
    }
''')

# Remote config response is sent on the existing connection before applying a setting that may reconnect it.
replace("app/src/main/java/com/xingyao/card/core/DeviceCommandCoordinator.java",
        '''        JSONObject saved = settingsRepository.save(DeviceConfigMapper.apply(current, remote));
        if (configControl != null) configControl.apply(saved);
        complete(command, baseResponse(command, "syncConfigResp")
                .put("code", 0).put("status", "SUCCESS").put("msg", "success")
                .put("deviceCode", currentDeviceCode())
                .put("configUpdatedAt", saved.optLong("remoteConfigUpdatedAt", 0L)), true);
''',
        '''        JSONObject saved = settingsRepository.save(DeviceConfigMapper.apply(current, remote));
        complete(command, baseResponse(command, "syncConfigResp")
                .put("code", 0).put("status", "SUCCESS").put("msg", "success")
                .put("deviceCode", currentDeviceCode())
                .put("configUpdatedAt", saved.optLong("remoteConfigUpdatedAt", 0L)), true);
        if (configControl != null) configControl.apply(saved);
''')

# Suppress repeated identical hardware faults and emit one explicit recovery edge.
replace("app/src/main/java/com/xingyao/card/core/DeviceCommandCoordinator.java",
        "import java.util.Locale;\n", "import java.util.LinkedHashMap;\nimport java.util.Locale;\n")
replace("app/src/main/java/com/xingyao/card/core/DeviceCommandCoordinator.java",
        '''    private final ConfigControl configControl;
''',
        '''    private final ConfigControl configControl;
    private final LinkedHashMap<Integer, String> activeFaults = new LinkedHashMap<>();
''')
old_fault = '''    public void reportHardwareFault(JSONObject slot) {
        if (slot == null) return;
        String status = slot.optString("status", "");
        String faultCode = slot.optString("faultCode", "");
        boolean hasFault = "CHARGING_FAULT".equals(status)
                || "COMMUNICATION_FAULT".equals(status)
                || "ILLEGAL_CARD".equals(status)
                || !faultCode.trim().isEmpty();
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
'''
new_fault = '''    public synchronized void reportHardwareFault(JSONObject slot) {
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
'''
replace("app/src/main/java/com/xingyao/card/core/DeviceCommandCoordinator.java", old_fault, new_fault)
replace("app/src/main/java/com/xingyao/card/core/DeviceCommandCoordinator.java",
        'reportRuntimeEvent("logReport", "/api/v1/log/report",',
        'reportRuntimeEvent("logReport", BackendHttpGateway.LOG_REPORT,')

# Runtime HTTP login token is distinct from the registration device token.
replace("app/src/main/java/com/xingyao/card/core/NativeSettingsRepository.java",
        '''            "deviceToken", "mqttPassword", "signingKey", "machineId", "clientId",
''',
        '''            "deviceToken", "runtimeToken", "mqttPassword", "signingKey", "machineId", "clientId",
''')
replace("app/src/main/java/com/xingyao/card/core/NativeSettingsRepository.java",
        '''                .put("activationCode", "")
''',
        '''                .put("activationCode", "")
                .put("runtimeToken", "")
''')
replace("app/src/main/java/com/xingyao/card/core/BackendHttpGateway.java",
        '''        return new BackendHttpClient(base, settings.optString("deviceToken", ""));
''',
        '''        String token = settings.optString("runtimeToken", "").trim();
        if (token.isEmpty()) token = settings.optString("deviceToken", "");
        return new BackendHttpClient(base, token);
''')
replace("app/src/main/java/com/xingyao/card/core/WebSocketConnectionManager.java",
        '''                JSONObject login = httpGateway.postData(BackendHttpGateway.DEVICE_LOGIN,
                        new JSONObject().put("version", BuildConfig.VERSION_NAME));
                requireLoginSuccess(login, "HTTP");
''',
        '''                JSONObject login = httpGateway.postData(BackendHttpGateway.DEVICE_LOGIN,
                        new JSONObject().put("version", BuildConfig.VERSION_NAME));
                requireLoginSuccess(login, "HTTP");
                String runtimeToken = login.optString("token", "").trim();
                if (!runtimeToken.isEmpty()) {
                    JSONObject tokenSettings = settingsRepository.load();
                    tokenSettings.put("runtimeToken", runtimeToken);
                    settingsRepository.save(tokenSettings);
                }
''')

# If server-side /device/config changes communicationMode, leave the current connector cleanly.
replace("app/src/main/java/com/xingyao/card/core/WebSocketConnectionManager.java",
        '''                synchronized (this) { applySettings(provisioned); }
                updateState("LOGIN_SENT", "正在执行HTTP设备登录", null);
''',
        '''                synchronized (this) { applySettings(provisioned); }
                if (!MODE_HTTP.equals(transportMode)) {
                    synchronized (this) { connecting = false; }
                    reconnectNow();
                    return;
                }
                updateState("LOGIN_SENT", "正在执行HTTP设备登录", null);
''')
replace("app/src/main/java/com/xingyao/card/core/WebSocketConnectionManager.java",
        '''                synchronized (this) { applySettings(provisioned); }
                try {
                    connectMqttWithAvailableCredentials();
''',
        '''                synchronized (this) { applySettings(provisioned); }
                if (!MODE_MQTT.equals(transportMode)) {
                    synchronized (this) { connecting = false; }
                    reconnectNow();
                    return;
                }
                try {
                    connectMqttWithAvailableCredentials();
''')
replace("app/src/main/java/com/xingyao/card/core/WebSocketConnectionManager.java",
        '''                    synchronized (this) { applySettings(refreshed); }
                    connectMqttWithAvailableCredentials();
''',
        '''                    synchronized (this) { applySettings(refreshed); }
                    if (!MODE_MQTT.equals(transportMode)) {
                        synchronized (this) { connecting = false; }
                        reconnectNow();
                        return;
                    }
                    connectMqttWithAvailableCredentials();
''')
replace("app/src/main/java/com/xingyao/card/core/WebSocketConnectionManager.java",
        '''                synchronized (this) { applySettings(provisioned); }
                Socket nextSocket = new Socket(tcpHost, tcpPort);
''',
        '''                synchronized (this) { applySettings(provisioned); }
                if (!MODE_TCP.equals(transportMode)) {
                    synchronized (this) { connecting = false; }
                    reconnectNow();
                    return;
                }
                Socket nextSocket = new Socket(tcpHost, tcpPort);
''')

# Persist and expose actual activation/auth status from V4.1 HTTP.
replace("app/src/main/java/com/xingyao/card/core/DeviceProvisioningManager.java",
        '''    private static final String API_CONFIG = "/api/v1/device/config";
''',
        '''    private static final String API_CONFIG = "/api/v1/device/config";
    private static final String API_AUTH_STATUS = "/api/v1/device/auth/status";
''')
replace("app/src/main/java/com/xingyao/card/core/DeviceProvisioningManager.java",
        '''        settings.put("provisionedAt", System.currentTimeMillis());
        return settingsRepository.save(settings);
''',
        '''        try {
            JSONObject authorization = authStatus(apiBaseUrl, settings);
            settings.put("deviceAuthorization", authorization);
        } catch (Exception authorizationError) {
            settings.put("deviceAuthorization", new JSONObject()
                    .put("state", "UNKNOWN")
                    .put("message", "授权状态查询失败：" + authorizationError.getMessage()));
        }
        settings.put("provisionedAt", System.currentTimeMillis());
        return settingsRepository.save(settings);
''', 1)
# The first occurrence above is in ensureProvisioned; add method near config().
replace("app/src/main/java/com/xingyao/card/core/DeviceProvisioningManager.java",
        '''    private JSONObject config(String apiBaseUrl, JSONObject settings) throws Exception {
        return BackendHttpClient.dataObject(new BackendHttpClient(apiBaseUrl,
                settings.optString("deviceToken")).get(API_CONFIG));
    }
''',
        '''    private JSONObject config(String apiBaseUrl, JSONObject settings) throws Exception {
        return BackendHttpClient.dataObject(new BackendHttpClient(apiBaseUrl,
                settings.optString("deviceToken")).get(API_CONFIG));
    }

    private JSONObject authStatus(String apiBaseUrl, JSONObject settings) throws Exception {
        JSONObject data = BackendHttpClient.dataObject(new BackendHttpClient(apiBaseUrl,
                settings.optString("deviceToken")).get(API_AUTH_STATUS));
        boolean authorized = data.optBoolean("authorized", false);
        return new JSONObject().put("state", authorized ? "AUTHORIZED" : "UNAUTHORIZED")
                .put("authorized", authorized)
                .put("authExpireTime", data.optLong("authExpireTime", 0L))
                .put("authType", data.optString("authType", ""))
                .put("message", authorized ? "设备授权有效" : "设备未授权或授权已过期");
    }
''')

# Data layer initializes and refreshes the authorization section from Android settings.
replace("app/src/main/java/com/xingyao/card/core/DeviceDataLayer.java",
        '''        stateStore.configure(safeSettings);
        try { stateStore.updateSection("serial", "serial.statusChanged", serialPort.snapshot()); }
''',
        '''        stateStore.configure(safeSettings);
        updateAuthorizationSection(safeSettings);
        try { stateStore.updateSection("serial", "serial.statusChanged", serialPort.snapshot()); }
''')
replace("app/src/main/java/com/xingyao/card/core/DeviceDataLayer.java",
        '''        stateStore.configure(safeSettings);
        serialPort.configure(safeSettings);
''',
        '''        stateStore.configure(safeSettings);
        updateAuthorizationSection(safeSettings);
        serialPort.configure(safeSettings);
''')
replace("app/src/main/java/com/xingyao/card/core/DeviceDataLayer.java",
        '''    private static JSONObject eventState(String state, String cmd, String message) {
''',
        '''    private void updateAuthorizationSection(JSONObject settings) {
        JSONObject authorization = settings == null ? null : settings.optJSONObject("deviceAuthorization");
        if (authorization == null) {
            String activation = settings == null ? "" : settings.optString("activationStatus", "");
            authorization = eventState("ACTIVATED".equalsIgnoreCase(activation)
                    ? "AUTHORIZED" : "PENDING", "authorization",
                    "ACTIVATED".equalsIgnoreCase(activation) ? "设备已激活" : "等待设备授权查询");
        }
        stateStore.updateSection("deviceAuthorization", "authorization.statusChanged", authorization);
    }

    private static JSONObject eventState(String state, String cmd, String message) {
''')

# removeThrough must preserve the queue when the backend ACK id is unknown.
replace("app/src/main/java/com/xingyao/card/core/DeviceEventLogRepository.java",
        '''    public synchronized void removeThrough(String eventId) throws JSONException {
        JSONArray events = pendingEvents();
        JSONArray remaining = new JSONArray();
        boolean acknowledged = false;
        for (int index = 0; index < events.length(); index++) {
            JSONObject item = events.getJSONObject(index);
            if (!acknowledged) {
                acknowledged = eventId.equals(item.optString("eventId"));
                continue;
            }
            remaining.put(item);
        }
        preferences.edit().putString(KEY_EVENTS, remaining.toString()).apply();
    }
''',
        '''    public synchronized boolean removeThrough(String eventId) throws JSONException {
        JSONArray events = pendingEvents();
        int matchedIndex = -1;
        for (int index = 0; index < events.length(); index++) {
            if (eventId != null && eventId.equals(events.getJSONObject(index).optString("eventId"))) {
                matchedIndex = index;
                break;
            }
        }
        if (matchedIndex < 0) return false;
        JSONArray remaining = new JSONArray();
        for (int index = matchedIndex + 1; index < events.length(); index++) {
            remaining.put(events.getJSONObject(index));
        }
        if (!preferences.edit().putString(KEY_EVENTS, remaining.toString()).commit()) {
            throw new IllegalStateException("无法提交诊断事件ACK");
        }
        return true;
    }
''')

print("post-validation runtime fixes applied")
