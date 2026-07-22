package com.xingyao.card;

import android.util.Log;
import com.xingyao.card.core.NativeActionPolicy;
import com.xingyao.card.core.NativeAuthManager;
import com.xingyao.card.core.NativeSettingsRepository;
import com.xingyao.card.service.DeviceCoreService;

import org.json.JSONException;
import org.json.JSONObject;

public class JsBridge {
    private static final String TAG = "JsBridge";
    private final MainActivity activity;
    private final NativeAuthManager authManager;
    private final NativeSettingsRepository settingsRepository;

    public JsBridge(MainActivity activity) {
        this.activity = activity;
        this.authManager = new NativeAuthManager(activity);
        this.settingsRepository = new NativeSettingsRepository(activity);
    }

    /** Called only by WebViewCompat.WebMessageListener after exact origin and main-frame validation. */
    public void handleTrustedMessage(String rawMessage) {
        dispatch(rawMessage);
    }

    private void dispatch(String rawMessage) {
        String requestId = "";
        try {
            JSONObject request = new JSONObject(rawMessage == null ? "{}" : rawMessage);
            requestId = request.optString("requestId", "");
            String action = request.optString("action", "").trim();
            JSONObject payload = request.optJSONObject("payload");
            if (payload == null) payload = new JSONObject();
            if (action.isEmpty()) {
                sendError(requestId, "ACTION_REQUIRED", "原生请求缺少action");
                return;
            }
            if (!NativeActionPolicy.isKnownAction(action)) {
                sendError(requestId, "NOT_IMPLEMENTED", "NOT_IMPLEMENTED: Android原生层未注册该动作");
                return;
            }
            boolean bootstrapSettingsSave = "settings.save".equals(action) && !settingsRepository.isInitialized();
            String permission = bootstrapSettingsSave ? null : NativeActionPolicy.requiredPermission(action);
            String authorizationError = authManager.authorize(permission);
            if (authorizationError != null) {
                String message = NativeAuthManager.AUTH_REQUIRED.equals(authorizationError)
                        ? "管理员会话不存在或已过期，请重新验证密码"
                        : "当前管理员角色无权执行该操作：" + permission;
                sendError(requestId, authorizationError, message);
                DeviceCoreService.recordOperation("security.bridge.denied", new JSONObject()
                        .put("action", action)
                        .put("permission", permission)
                        .put("code", authorizationError));
                return;
            }
            handleRequest(requestId, action, payload);
        } catch (Exception error) {
            Log.e(TAG, "Invalid bridge request", error);
            sendError(requestId, "INVALID_REQUEST", safeMessage(error));
        }
    }

    private void handleRequest(String requestId, String action, JSONObject payload) throws JSONException {
        switch (action) {
            case "app.ready":
                sendSuccess(requestId, new JSONObject()
                        .put("native", true)
                        .put("platform", "android")
                        .put("bridgeVersion", 2)
                        .put("originScoped", activity.isOriginScopedBridgeEnabled()));
                activity.sendBridgeEvent("native.ready", new JSONObject().put("ready", true));
                break;
            case "settings.load":
                sendSuccess(requestId, settingsRepository.loadForUi());
                break;
            case "settings.save": {
                boolean bootstrap = !settingsRepository.isInitialized();
                JSONObject savedSettings = settingsRepository.saveFromUi(payload);
                DeviceCoreService.configureSerial(settingsRepository.load());
                DeviceCoreService.recordOperation(bootstrap ? "settings.bootstrap.saved" : "settings.saved", savedSettings);
                sendSuccess(requestId, savedSettings);
                break;
            }
            case "auth.login": {
                JSONObject session = authManager.login(payload.optString("password", ""));
                if (session == null) sendError(requestId, "AUTH_FAILED", "密码错误");
                else sendSuccess(requestId, session);
                break;
            }
            case "auth.logout":
                authManager.logout();
                sendSuccess(requestId, new JSONObject().put("success", true));
                break;
            case "auth.changePassword": {
                boolean changed = authManager.changePassword(payload.optString("role"), payload.optString("password"));
                if (!changed) sendError(requestId, "PASSWORD_INVALID", "请输入有效的6位密码");
                else sendSuccess(requestId, new JSONObject().put("success", true));
                break;
            }
            case "device.snapshot":
                sendSuccess(requestId, DeviceCoreService.snapshot());
                break;
            case "serial.getStatus":
                sendSuccess(requestId, DeviceCoreService.snapshot().getJSONObject("serial"));
                break;
            case "serial.reconnect":
                DeviceCoreService.reconnectSerial();
                sendSuccess(requestId, DeviceCoreService.snapshot().getJSONObject("serial"));
                break;
            case "serial.setPolling":
                sendSuccess(requestId, DeviceCoreService.setSerialPolling(payload.optBoolean("enabled", false)));
                break;
            case "serial.listPorts":
                sendSuccess(requestId, DeviceCoreService.listSerialPorts());
                break;
            case "serial.send":
                try {
                    sendSuccess(requestId, DeviceCoreService.sendSerial(
                            payload.optString("data", ""), payload.optString("encoding", "TEXT")));
                } catch (Exception error) {
                    sendError(requestId, "SERIAL_SEND_FAILED", safeMessage(error));
                }
                break;
            case "cabinet.unlockDoor":
                try {
                    sendSuccess(requestId, DeviceCoreService.openDoor(payload.optInt("slotNumber", -1), true,
                            "TAKE", "ADMIN", requestId, "UI"));
                } catch (Exception error) {
                    sendError(requestId, "DOOR_OPEN_FAILED", safeMessage(error));
                }
                break;
            case "cabinet.takeCard":
                try {
                    sendSuccess(requestId, DeviceCoreService.openDoor(payload.optInt("slotNumber", -1), false,
                            "TAKE", "FACE", requestId, "UI"));
                } catch (Exception error) {
                    sendError(requestId, "DOOR_OPEN_FAILED", safeMessage(error));
                }
                break;
            case "cabinet.returnCard":
                try {
                    sendSuccess(requestId, DeviceCoreService.openDoor(payload.optInt("slotNumber", -1), true,
                            "RETURN", "ADMIN", requestId, "UI"));
                } catch (Exception error) {
                    sendError(requestId, "DOOR_OPEN_FAILED", safeMessage(error));
                }
                break;
            case "cabinet.querySlot":
                try {
                    sendSuccess(requestId, DeviceCoreService.querySlot(payload.optInt("slotNumber", -1)));
                } catch (Exception error) {
                    sendError(requestId, "SLOT_QUERY_FAILED", safeMessage(error));
                }
                break;
            case "cabinet.readVersion":
                try {
                    sendSuccess(requestId, DeviceCoreService.readBoardVersion(payload.optInt("slotNumber", -1)));
                } catch (Exception error) {
                    sendError(requestId, "VERSION_READ_FAILED", safeMessage(error));
                }
                break;
            case "cabinet.unlockAll":
                try {
                    sendSuccess(requestId, DeviceCoreService.openAllDoors(true, requestId, "UI"));
                } catch (Exception error) {
                    sendError(requestId, "DOOR_OPEN_ALL_FAILED", safeMessage(error));
                }
                break;
            case "cabinet.getSlots":
                sendSuccess(requestId, DeviceCoreService.getSlots());
                break;
            case "face.getStatus":
                sendSuccess(requestId, DeviceCoreService.snapshot().getJSONObject("recognitionEngine"));
                break;
            case "face.reactivate":
                DeviceCoreService.restartFaceRecognition();
                sendSuccess(requestId, new JSONObject().put("success", true));
                break;
            case "face.enroll":
                activity.startFaceEnrollment(requestId, payload);
                break;
            case "face.verify":
                activity.startFaceVerification(requestId);
                break;
            case "fingerprint.getStatus":
                sendSuccess(requestId, activity.fingerprintStatus());
                break;
            case "fingerprint.enroll":
                activity.startFingerprintAuthentication(requestId, payload, true);
                break;
            case "fingerprint.verify":
                activity.startFingerprintAuthentication(requestId, payload, false);
                break;
            case "fingerprint.cancel":
                activity.cancelFingerprintAuthentication();
                sendSuccess(requestId, new JSONObject().put("success", true));
                break;
            case "socket.getStatus":
                sendSuccess(requestId, DeviceCoreService.snapshot().getJSONObject("socket"));
                break;
            case "employee.search":
                sendSuccess(requestId, new JSONObject()
                        .put("employees", DeviceCoreService.searchEmployees(payload.optString("query", ""))));
                break;
            case "employee.delete":
                sendSuccess(requestId, DeviceCoreService.deleteEmployee(payload.optString("id", "")));
                break;
            case "app.restart":
                sendSuccess(requestId, new JSONObject().put("success", true));
                activity.scheduleRecreate();
                break;
            default:
                sendError(requestId, "NOT_IMPLEMENTED", "NOT_IMPLEMENTED: Android原生层未注册该动作");
                break;
        }
    }

    private void sendSuccess(String requestId, JSONObject data) {
        try {
            JSONObject response = new JSONObject()
                    .put("type", "response")
                    .put("requestId", requestId)
                    .put("success", true)
                    .put("data", data == null ? JSONObject.NULL : data);
            activity.sendBridgeResponse(response);
        } catch (JSONException error) {
            Log.e(TAG, "Unable to create success response", error);
        }
    }

    private void sendError(String requestId, String code, String message) {
        try {
            JSONObject response = new JSONObject()
                    .put("type", "response")
                    .put("requestId", requestId)
                    .put("success", false)
                    .put("code", code)
                    .put("message", message == null ? code : message);
            activity.sendBridgeResponse(response);
        } catch (JSONException error) {
            Log.e(TAG, "Unable to create error response", error);
        }
    }

    private static String safeMessage(Throwable error) {
        if (error == null) return "unknown";
        String value = error.getMessage();
        return value == null || value.trim().isEmpty() ? error.getClass().getSimpleName() : value;
    }
}
