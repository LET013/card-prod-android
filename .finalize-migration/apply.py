from pathlib import Path


def read(path):
    return Path(path).read_text(encoding='utf-8')


def write(path, value):
    Path(path).write_text(value, encoding='utf-8')


def replace_once(path, old, new):
    value = read(path)
    count = value.count(old)
    if count != 1:
        raise RuntimeError(f'{path}: expected one occurrence, got {count}: {old[:120]!r}')
    write(path, value.replace(old, new, 1))


# Device activation is a documented lifecycle step for both HTTP and MQTT modes.
path = 'app/src/main/java/com/xingyao/card/core/DeviceProvisioningManager.java'
replace_once(path,
'''        boolean mqttRequested = BackendEndpointSettings.MODE_MQTT.equalsIgnoreCase(
                settings.optString("backendTransport", BackendEndpointSettings.MODE_MQTT));
        if (forceCredentialRefresh || (mqttRequested && !hasMqttCredentials(settings))) {
            settings = performActivation(settings);
            httpGateway.configure(settings);
        }''',
'''        boolean mqttRequested = BackendEndpointSettings.MODE_MQTT.equalsIgnoreCase(
                settings.optString("backendTransport", BackendEndpointSettings.MODE_MQTT));
        boolean activationRequired = forceCredentialRefresh
                || !"ACTIVATED".equalsIgnoreCase(settings.optString("activationStatus", ""))
                || (mqttRequested && !hasMqttCredentials(settings));
        if (activationRequired) {
            settings = performActivation(settings);
            httpGateway.configure(settings);
        }''')

# Backend transport must reconnect cleanly after a configuration change and preserve the documented
# request msgId in its response envelope. MQTT login token is stored by the data layer exactly as the
# HTTP login token is, but is not used as a Bearer token.
path = 'app/src/main/java/com/xingyao/card/core/BackendTransportManager.java'
replace_once(path,
'''        closeTransports();
        if (!running) return;''',
'''        closeTransports();
        connecting = false;
        if (!running) return;''')
replace_once(path,
'''            if ("loginResp".equals(cmd)) {
                requireLoginSuccess(envelope, "MQTT/TCP");
                authenticatedAt = System.currentTimeMillis();''',
'''            if ("loginResp".equals(cmd)) {
                requireLoginSuccess(envelope, "MQTT/TCP");
                JSONObject loginData = envelope.optJSONObject("data");
                String runtimeToken = envelope.optString("token", "").trim();
                if (runtimeToken.isEmpty() && loginData != null) {
                    runtimeToken = loginData.optString("token", "").trim();
                }
                if (!runtimeToken.isEmpty() && listener != null) {
                    listener.onRuntimeToken(runtimeToken);
                }
                authenticatedAt = System.currentTimeMillis();''')

# Terminal responses use the original server-generated msgId as documented.
path = 'app/src/main/java/com/xingyao/card/core/DeviceCommandCoordinator.java'
replace_once(path,
'''            return new JSONObject().put("cmd",
                    responseCmd == null || responseCmd.trim().isEmpty()
                            ? "commandResp" : responseCmd);''',
'''            return new JSONObject()
                    .put("cmd", responseCmd == null || responseCmd.trim().isEmpty()
                            ? "commandResp" : responseCmd)
                    .put("msgId", command == null ? "" : command.optString("msgId", ""));''')

# Strict request types for fields explicitly typed in V4.1, plus the documented face-image upload.
path = 'app/src/main/java/com/xingyao/card/core/DocumentedBackendService.java'
replace_once(path,
'''        copyOptional(source, body, "cardNo", "deptId", "phone", "email",
                "department", "position", "status");''',
'''        copyOptional(source, body, "cardNo", "phone", "email", "department", "position");
        if (source.has("deptId") && !source.isNull("deptId")) {
            body.put("deptId", requiredLong(source, "deptId"));
        }
        if (source.has("status") && !source.isNull("status")) {
            body.put("status", status(source.optString("status", "")));
        }''')
replace_once(path,
'''        copyOptional(source, body, "faceImagePath", "deviceId");''',
'''        copyOptional(source, body, "faceImagePath");
        if (source.has("deviceId") && !source.isNull("deviceId")) {
            body.put("deviceId", requiredLong(source, "deviceId"));
        }''')
replace_once(path,
'''    public JSONObject uploadFaceImage(String userId, File file, String faceFeature) throws Exception {
        requirePrivateFile(file);
        return transport.uploadFaceImage(requiredString(userId, "userId"), file, faceFeature);
    }''',
'''    public JSONObject uploadFaceImage(String userId, File file, String faceFeature) throws Exception {
        requirePrivateFile(file);
        return transport.uploadFaceImage(requiredString(userId, "userId"), file, faceFeature);
    }

    public JSONObject uploadFaceImage(String userId, String filePath,
                                      String faceFeature) throws Exception {
        return uploadFaceImage(userId, new File(requiredString(filePath, "filePath")),
                faceFeature);
    }''')
replace_once(path,
'''        return new JSONObject().put("cardNo", requiredString(cardNo, "cardNo"))
                .put("slotId", slotId)
                .put("authType", requiredString(authType, "authType"));''',
'''        return new JSONObject().put("cardNo", requiredString(cardNo, "cardNo"))
                .put("slotId", slotId)
                .put("authType", authType(authType));''')
replace_once(path,
'''    private static long requiredLong(JSONObject source, String field) {''',
'''    private static String status(String value) {
        String result = requiredString(value, "status");
        if (!("0".equals(result) || "1".equals(result))) {
            throw new IllegalArgumentException("status必须为0或1");
        }
        return result;
    }

    private static String authType(String value) {
        String result = requiredString(value, "authType").toUpperCase();
        if (!("CARD".equals(result) || "FACE".equals(result)
                || "FINGERPRINT".equals(result))) {
            throw new IllegalArgumentException("authType必须为CARD、FACE或FINGERPRINT");
        }
        return result;
    }

    private static long requiredLong(JSONObject source, String field) {''')

# Data-layer entry for the documented multipart endpoint.
path = 'app/src/main/java/com/xingyao/card/core/DeviceDataLayer.java'
replace_once(path,
'''    public JSONObject uploadFingerprintFeature(JSONObject request) throws Exception {
        JSONObject result = documentedBackendService.uploadFingerprint(request);
        stateStore.record("employee.fingerprint.uploaded", result);
        return result;
    }''',
'''    public JSONObject uploadFingerprintFeature(JSONObject request) throws Exception {
        JSONObject result = documentedBackendService.uploadFingerprint(request);
        stateStore.record("employee.fingerprint.uploaded", result);
        return result;
    }

    public JSONObject uploadFaceImage(String userId, String filePath,
                                      String faceFeature) throws Exception {
        JSONObject result = documentedBackendService.uploadFaceImage(userId, filePath, faceFeature);
        stateStore.record("employee.face.imageUploaded", result);
        return result;
    }''')

# Native policy and facade expose the exact endpoint through a deferred Android data-layer action.
path = 'app/src/main/java/com/xingyao/card/core/NativeActionPolicy.java'
replace_once(path,
'''        permissions.put("employee.face.upsert", "biometric.register");''',
'''        permissions.put("employee.face.upsert", "biometric.register");
        permissions.put("face.uploadImage", "biometric.register");''')

path = 'app/src/main/java/com/xingyao/card/core/DeviceApplicationFacade.java'
replace_once(path,
'''                case "employee.face.registered":
                    return deferred(requestId, "FACE_REGISTERED_QUERY_FAILED",
                            () -> new JSONObject().put("employeeIds",
                                    runtime().registeredFaceEmployeeIds()));''',
'''                case "employee.face.registered":
                    return deferred(requestId, "FACE_REGISTERED_QUERY_FAILED",
                            () -> new JSONObject().put("employeeIds",
                                    runtime().registeredFaceEmployeeIds()));
                case "face.uploadImage":
                    return deferred(requestId, "FACE_IMAGE_UPLOAD_FAILED",
                            () -> runtime().uploadFaceImage(
                                    safePayload.optString("userId", ""),
                                    safePayload.optString("filePath", ""),
                                    safePayload.optString("faceFeature", "")));''')
replace_once(path,
'''    private DeviceDataLayer runtime() throws FacadeException {''',
'''    public void close() {
        ioExecutor.shutdownNow();
    }

    private DeviceDataLayer runtime() throws FacadeException {''')

# Bridge and Activity release the facade executor with the Activity lifecycle.
path = 'app/src/main/java/com/xingyao/card/JsBridge.java'
replace_once(path,
'''    private static String safeMessage(Throwable error) {''',
'''    public void close() {
        facade.close();
    }

    private static String safeMessage(Throwable error) {''')

path = 'app/src/main/java/com/xingyao/card/MainActivity.java'
replace_once(path,
'''        if (webViewManager != null) webViewManager.destroy();
        super.onDestroy();''',
'''        if (webViewManager != null) webViewManager.destroy();
        if (jsBridge != null) jsBridge.close();
        super.onDestroy();''')

path = 'uniapp/src/services/index.js'
replace_once(path,
'''  uploadFaceFeature: (payload) => nativeOrMock('employee.face.upsert', payload, async () => ({}), 20000),''',
'''  uploadFaceFeature: (payload) => nativeOrMock('employee.face.upsert', payload, async () => ({}), 20000),
  uploadFaceImage: (payload) => nativeOrMock('face.uploadImage', payload, async () => ({ uploadId: '', faceUrl: '', faceFeature: payload?.faceFeature || '' }), 60000),''')

# Regression tests for strict fields.
path = 'app/src/test/java/com/xingyao/card/core/DocumentedBackendServiceTest.java'
value = read(path)
insert = '''
    @Test(expected = IllegalArgumentException.class)
    public void invalidEmployeeStatusIsRejected() throws Exception {
        DocumentedBackendService service = new DocumentedBackendService(
                temp("files"), temp("cache"), new FakeTransport());
        service.upsertEmployee(new JSONObject().put("action", "add")
                .put("employeeCode", "EMP001").put("employeeName", "张三")
                .put("status", "ACTIVE"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidCardAuthTypeIsRejected() throws Exception {
        DocumentedBackendService service = new DocumentedBackendService(
                temp("files"), temp("cache"), new FakeTransport());
        service.reportTake("CARD001", 1, "ADMIN");
    }
'''
marker = '    private static final class FakeTransport'
if marker not in value:
    raise RuntimeError('DocumentedBackendServiceTest marker not found')
value = value.replace(marker, insert + '\n' + marker, 1)
write(path, value)

print('final runtime and documented interface fixes applied')
