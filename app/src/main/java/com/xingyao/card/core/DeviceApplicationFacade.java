package com.xingyao.card.core;

import com.xingyao.card.MainActivity;
import com.xingyao.card.service.DeviceCoreService;

import org.json.JSONObject;

/**
 * Single Android application/data entry point exposed to the trusted WebView bridge.
 *
 * JsBridge only parses and serializes messages. Authorization, data access and device
 * commands are routed through this facade so Vue cannot address repositories, services
 * or communication managers directly.
 */
public final class DeviceApplicationFacade {
    public static final class ActionResult {
        private final JSONObject data;
        private final boolean deferred;

        private ActionResult(JSONObject data, boolean deferred) {
            this.data = data == null ? new JSONObject() : data;
            this.deferred = deferred;
        }

        public static ActionResult immediate(JSONObject data) {
            return new ActionResult(data, false);
        }

        public static ActionResult deferred() {
            return new ActionResult(new JSONObject(), true);
        }

        public JSONObject getData() {
            return data;
        }

        public boolean isDeferred() {
            return deferred;
        }
    }

    public static final class FacadeException extends Exception {
        private final String code;

        public FacadeException(String code, String message) {
            super(message);
            this.code = code;
        }

        public FacadeException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }

    private final MainActivity activity;
    private final NativeAuthManager authManager;
    private final NativeSettingsRepository settingsRepository;

    public DeviceApplicationFacade(MainActivity activity) {
        if (activity == null) throw new IllegalArgumentException("activity is required");
        this.activity = activity;
        this.authManager = new NativeAuthManager(activity);
        this.settingsRepository = new NativeSettingsRepository(activity);
    }

    public ActionResult execute(String action, JSONObject payload, String requestId) throws FacadeException {
        String normalizedAction = action == null ? "" : action.trim();
        JSONObject safePayload = payload == null ? new JSONObject() : payload;
        if (normalizedAction.isEmpty()) {
            throw new FacadeException("ACTION_REQUIRED", "原生请求缺少action");
        }
        if (!NativeActionPolicy.isKnownAction(normalizedAction)) {
            throw new FacadeException("NOT_IMPLEMENTED", "NOT_IMPLEMENTED: Android原生层未注册该动作");
        }

        boolean bootstrapSettingsSave = "settings.save".equals(normalizedAction)
                && !settingsRepository.isInitialized();
        String permission = bootstrapSettingsSave ? null : NativeActionPolicy.requiredPermission(normalizedAction);
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
                            .put("native", true)
                            .put("platform", "android")
                            .put("bridgeVersion", 3)
                            .put("originScoped", activity.isOriginScopedBridgeEnabled()));
                case "settings.load":
                    return ActionResult.immediate(settingsRepository.loadForUi());
                case "settings.save": {
                    boolean bootstrap = !settingsRepository.isInitialized();
                    JSONObject savedSettings = settingsRepository.saveFromUi(safePayload);
                    DeviceCoreService.configureSerial(settingsRepository.load());
                    DeviceCoreService.recordOperation(bootstrap
                            ? "settings.bootstrap.saved" : "settings.saved", savedSettings);
                    return ActionResult.immediate(savedSettings);
                }
                case "auth.login": {
                    JSONObject session = authManager.login(safePayload.optString("password", ""));
                    if (session == null) throw new FacadeException("AUTH_FAILED", "密码错误");
                    return ActionResult.immediate(session);
                }
                case "auth.logout":
                    authManager.logout();
                    return success();
                case "auth.changePassword":
                    if (!authManager.changePassword(safePayload.optString("role"),
                            safePayload.optString("password"))) {
                        throw new FacadeException("PASSWORD_INVALID", "请输入有效的6位密码");
                    }
                    return success();
                case "device.snapshot":
                    return ActionResult.immediate(DeviceCoreService.snapshot());
                case "serial.getStatus":
                    return ActionResult.immediate(DeviceCoreService.snapshot().getJSONObject("serial"));
                case "serial.reconnect":
                    DeviceCoreService.reconnectSerial();
                    return ActionResult.immediate(DeviceCoreService.snapshot().getJSONObject("serial"));
                case "serial.setPolling":
                    return ActionResult.immediate(DeviceCoreService.setSerialPolling(
                            safePayload.optBoolean("enabled", false)));
                case "serial.listPorts":
                    return ActionResult.immediate(DeviceCoreService.listSerialPorts());
                case "serial.send":
                    return ActionResult.immediate(DeviceCoreService.sendSerial(
                            safePayload.optString("data", ""),
                            safePayload.optString("encoding", "TEXT")));
                case "cabinet.unlockDoor":
                    return ActionResult.immediate(DeviceCoreService.openDoor(
                            safePayload.optInt("slotNumber", -1), true,
                            "TAKE", "ADMIN", requestId, "UI"));
                case "cabinet.takeCard":
                    return ActionResult.immediate(DeviceCoreService.openDoor(
                            safePayload.optInt("slotNumber", -1), false,
                            "TAKE", "FACE", requestId, "UI"));
                case "cabinet.returnCard":
                    return ActionResult.immediate(DeviceCoreService.openDoor(
                            safePayload.optInt("slotNumber", -1), true,
                            "RETURN", "ADMIN", requestId, "UI"));
                case "cabinet.querySlot":
                    return ActionResult.immediate(DeviceCoreService.querySlot(
                            safePayload.optInt("slotNumber", -1)));
                case "cabinet.readVersion":
                    return ActionResult.immediate(DeviceCoreService.readBoardVersion(
                            safePayload.optInt("slotNumber", -1)));
                case "cabinet.unlockAll":
                    return ActionResult.immediate(DeviceCoreService.openAllDoors(true, requestId, "UI"));
                case "cabinet.getSlots":
                    return ActionResult.immediate(DeviceCoreService.getSlots());
                case "face.getStatus":
                    return ActionResult.immediate(DeviceCoreService.snapshot()
                            .getJSONObject("recognitionEngine"));
                case "face.reactivate":
                    DeviceCoreService.restartFaceRecognition();
                    return success();
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
                    activity.cancelFingerprintAuthentication();
                    return success();
                case "socket.getStatus":
                    return ActionResult.immediate(DeviceCoreService.snapshot().getJSONObject("socket"));
                case "employee.search":
                    return ActionResult.immediate(new JSONObject().put("employees",
                            DeviceCoreService.searchEmployees(safePayload.optString("query", ""))));
                case "employee.delete":
                    return ActionResult.immediate(DeviceCoreService.deleteEmployee(
                            safePayload.optString("id", "")));
                case "app.restart":
                    activity.scheduleRecreate();
                    return success();
                default:
                    throw new FacadeException("NOT_IMPLEMENTED",
                            "NOT_IMPLEMENTED: Android原生层未注册该动作");
            }
        } catch (FacadeException error) {
            throw error;
        } catch (Exception error) {
            throw new FacadeException(errorCodeFor(normalizedAction), safeMessage(error), error);
        }
    }

    private ActionResult success() throws Exception {
        return ActionResult.immediate(new JSONObject().put("success", true));
    }

    private void recordDenied(String action, String permission, String code) {
        try {
            DeviceCoreService.recordOperation("security.bridge.denied", new JSONObject()
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
