package com.xingyao.card.core;

import com.xingyao.card.MainActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONArray;
import org.json.JSONObject;

/** Single Android data-layer entry exposed to the trusted WebView bridge. */
public final class DeviceApplicationFacade {
    public static final class ActionResult {
        private final JSONObject data;
        private final boolean deferred;

        private ActionResult(JSONObject data, boolean deferred) {
            this.data = data == null ? new JSONObject() : data;
            this.deferred = deferred;
        }

        public static ActionResult immediate(JSONObject data) { return new ActionResult(data, false); }
        public static ActionResult deferred() { return new ActionResult(new JSONObject(), true); }
        public JSONObject getData() { return data; }
        public boolean isDeferred() { return deferred; }
    }

    public static final class FacadeException extends Exception {
        private final String code;
        public FacadeException(String code, String message) { super(message); this.code = code; }
        public FacadeException(String code, String message, Throwable cause) {
            super(message, cause); this.code = code;
        }
        public String getCode() { return code; }
    }

    private final MainActivity activity;
    private final NativeAuthManager authManager;
    private final NativeSettingsRepository settingsRepository;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    public DeviceApplicationFacade(MainActivity activity) {
        if (activity == null) throw new IllegalArgumentException("activity is required");
        this.activity = activity;
        this.authManager = new NativeAuthManager(activity);
        this.settingsRepository = new NativeSettingsRepository(activity);
    }

    public ActionResult execute(String action, JSONObject payload, String requestId)
            throws FacadeException {
        String normalizedAction = action == null ? "" : action.trim();
        JSONObject safePayload = payload == null ? new JSONObject() : payload;
        if (normalizedAction.isEmpty()) throw new FacadeException("ACTION_REQUIRED", "原生请求缺少action");
        if (!NativeActionPolicy.isKnownAction(normalizedAction)) {
            throw new FacadeException("NOT_IMPLEMENTED", "NOT_IMPLEMENTED: Android数据层未注册该动作");
        }

        boolean bootstrapSettingsSave = "settings.save".equals(normalizedAction)
                && !settingsRepository.isInitialized();
        String permission = bootstrapSettingsSave ? null
                : NativeActionPolicy.requiredPermission(normalizedAction);
        String authorizationError = authManager.authorize(permission);
        if (authorizationError != null) {
            String message = NativeAuthManager.AUTH_REQUIRED.equals(authorizationError)
                    ? "管理员会话不存在或已过期，请重新验证密码"
                    : "当前管理员角色无权执行该操作：" + permission;
            recordDenied(normalizedAction, permission, authorizationError);
            throw new FacadeException(authorizationError, message);
        }

        try {
            switch (normalizedAction) {
                case "app.ready":
                    return ActionResult.immediate(new JSONObject()
                            .put("native", true).put("platform", "android")
                            .put("bridgeVersion", 4)
                            .put("dataLayerReady", DeviceRuntimeRegistry.get() != null)
                            .put("originScoped", activity.isOriginScopedBridgeEnabled()));
                case "settings.load":
                    return ActionResult.immediate(settingsRepository.loadForUi());
                case "settings.save": {
                    boolean bootstrap = !settingsRepository.isInitialized();
                    JSONObject saved = settingsRepository.saveFromUi(safePayload);
                    DeviceDataLayer layer = runtime();
                    layer.applySettings(settingsRepository.load());
                    layer.recordOperation(bootstrap ? "settings.bootstrap.saved" : "settings.saved", saved);
                    return ActionResult.immediate(saved);
                }
                case "auth.login": {
                    JSONObject session = authManager.login(safePayload.optString("password", ""));
                    if (session == null) throw new FacadeException("AUTH_FAILED", "密码错误");
                    return ActionResult.immediate(session);
                }
                case "auth.logout": authManager.logout(); return success();
                case "auth.changePassword":
                    if (!authManager.changePassword(safePayload.optString("role"),
                            safePayload.optString("password"))) {
                        throw new FacadeException("PASSWORD_INVALID", "请输入有效的6位密码");
                    }
                    return success();
                case "device.snapshot": return ActionResult.immediate(runtime().snapshot());
                case "serial.getStatus": return ActionResult.immediate(runtime().serialStatus());
                case "serial.reconnect":
                    runtime().reconnectSerial();
                    return ActionResult.immediate(runtime().serialStatus());
                case "serial.setPolling":
                    return ActionResult.immediate(runtime().setSerialPolling(
                            safePayload.optBoolean("enabled", false)));
                case "serial.listPorts": return ActionResult.immediate(runtime().listSerialPorts());
                case "serial.send":
                    return ActionResult.immediate(runtime().sendSerial(
                            safePayload.optString("data", ""),
                            safePayload.optString("encoding", "TEXT")));
                case "cabinet.unlockDoor":
                    return ActionResult.immediate(runtime().openDoor(
                            safePayload.optInt("slotNumber", -1), true,
                            "TAKE", "ADMIN", requestId, "UI", ""));
                case "cabinet.takeCard":
                    return ActionResult.immediate(runtime().openDoor(
                            safePayload.optInt("slotNumber", -1), false,
                            "TAKE", "FACE", requestId, "UI", safePayload.optString("employeeId", "")));
                case "cabinet.returnCard":
                    return ActionResult.immediate(runtime().openDoor(
                            safePayload.optInt("slotNumber", -1), true,
                            "RETURN", "ADMIN", requestId, "UI", safePayload.optString("employeeId", "")));
                case "cabinet.querySlot":
                    return ActionResult.immediate(runtime().querySlot(safePayload.optInt("slotNumber", -1)));
                case "cabinet.readVersion":
                    return ActionResult.immediate(runtime().readBoardVersion(safePayload.optInt("slotNumber", -1)));
                case "cabinet.unlockAll":
                    return ActionResult.immediate(runtime().openAllDoors(true, requestId, "UI"));
                case "cabinet.getSlots": return ActionResult.immediate(runtime().slots());
                case "status.reportNow":
                    return deferred(requestId, "STATUS_REPORT_FAILED", () -> runtime().reportStatusNow());
                case "face.getStatus": return ActionResult.immediate(runtime().recognitionStatus());
                case "face.reactivate": runtime().restartFaceRecognition(); return success();
                case "face.enroll":
                    activity.startFaceEnrollment(requestId, safePayload);
                    return ActionResult.deferred();
                case "face.verify":
                    activity.startFaceVerification(requestId);
                    return ActionResult.deferred();
                case "fingerprint.getStatus":
                    return ActionResult.immediate(activity.fingerprintStatus());
                case "fingerprint.enroll":
                    activity.startFingerprintAuthentication(requestId, safePayload, true);
                    return ActionResult.deferred();
                case "fingerprint.verify":
                    activity.startFingerprintAuthentication(requestId, safePayload, false);
                    return ActionResult.deferred();
                case "fingerprint.cancel":
                    activity.cancelFingerprintAuthentication(); return success();
                case "socket.getStatus": return ActionResult.immediate(runtime().backendStatus());
                case "employee.search":
                    return ActionResult.immediate(new JSONObject().put("employees",
                            runtime().searchEmployees(safePayload.optString("query", ""))));
                case "employee.delete":
                    return deferred(requestId, "EMPLOYEE_DELETE_FAILED",
                            () -> runtime().deleteEmployee(safePayload.optString("id", "")));
                case "employee.upsert":
                    return deferred(requestId, "EMPLOYEE_UPSERT_FAILED",
                            () -> runtime().upsertEmployee(safePayload));
                case "employee.face.upsert":
                    return deferred(requestId, "FACE_UPLOAD_FAILED",
                            () -> runtime().upsertFaceFeature(safePayload));
                case "employee.face.registered":
                    return deferred(requestId, "FACE_REGISTERED_QUERY_FAILED",
                            () -> new JSONObject().put("employeeIds",
                                    runtime().registeredFaceEmployeeIds()));
                case "face.uploadImage":
                    return deferred(requestId, "FACE_IMAGE_UPLOAD_FAILED",
                            () -> runtime().uploadFaceImage(
                                    safePayload.optString("userId", ""),
                                    safePayload.optString("filePath", ""),
                                    safePayload.optString("faceFeature", "")));
                case "fingerprint.uploadFeature":
                    return deferred(requestId, "FINGERPRINT_UPLOAD_FAILED",
                            () -> runtime().uploadFingerprintFeature(safePayload));
                case "logs.uploadBatch":
                    return deferred(requestId, "LOG_BATCH_UPLOAD_FAILED",
                            () -> runtime().uploadLogsBatch(safePayload.optJSONArray("logs")));
                case "firmware.download":
                    return deferred(requestId, "FIRMWARE_DOWNLOAD_FAILED",
                            () -> runtime().downloadFirmware(
                                    safePayload.optString("firmwareId", ""),
                                    safePayload.optBoolean("resume", true)));
                case "app.restart": activity.scheduleRecreate(); return success();
                default:
                    throw new FacadeException("NOT_IMPLEMENTED", "NOT_IMPLEMENTED: Android数据层未注册该动作");
            }
        } catch (FacadeException error) {
            throw error;
        } catch (Exception error) {
            throw new FacadeException(errorCodeFor(normalizedAction), safeMessage(error), error);
        }
    }

    private interface IoCall { JSONObject run() throws Exception; }

    private ActionResult deferred(String requestId, String errorCode, IoCall call) {
        ioExecutor.execute(() -> {
            try {
                JSONObject response = new JSONObject().put("type", "response")
                        .put("requestId", requestId).put("success", true)
                        .put("data", call.run());
                activity.sendBridgeResponse(response);
            } catch (Exception error) {
                try {
                    activity.sendBridgeResponse(new JSONObject().put("type", "response")
                            .put("requestId", requestId).put("success", false)
                            .put("code", errorCode).put("message", safeMessage(error)));
                } catch (Exception ignored) { }
            }
        });
        return ActionResult.deferred();
    }

    public void close() {
        ioExecutor.shutdownNow();
    }

    private DeviceDataLayer runtime() throws FacadeException {
        DeviceDataLayer layer = DeviceRuntimeRegistry.get();
        if (layer == null) throw new FacadeException("DATA_LAYER_NOT_READY", "Android数据层尚未启动");
        return layer;
    }

    private ActionResult success() throws Exception {
        return ActionResult.immediate(new JSONObject().put("success", true));
    }

    private void recordDenied(String action, String permission, String code) {
        try {
            DeviceRuntimeRegistry.record("security.bridge.denied", new JSONObject()
                    .put("action", action)
                    .put("permission", permission == null ? JSONObject.NULL : permission)
                    .put("code", code));
        } catch (Exception ignored) { }
    }

    private static String errorCodeFor(String action) {
        if (action == null) return "NATIVE_ACTION_FAILED";
        if (action.startsWith("serial.")) return "SERIAL_ACTION_FAILED";
        if (action.startsWith("cabinet.")) return "CABINET_ACTION_FAILED";
        if (action.startsWith("face.")) return "FACE_ACTION_FAILED";
        if (action.startsWith("fingerprint.")) return "FINGERPRINT_ACTION_FAILED";
        if (action.startsWith("employee.")) return "EMPLOYEE_ACTION_FAILED";
        if (action.startsWith("settings.")) return "SETTINGS_ACTION_FAILED";
        return "NATIVE_ACTION_FAILED";
    }

    private static String safeMessage(Throwable error) {
        if (error == null) return "unknown";
        String value = error.getMessage();
        return value == null || value.trim().isEmpty()
                ? error.getClass().getSimpleName() : value;
    }
}
