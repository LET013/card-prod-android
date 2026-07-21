package com.xingyao.card;

import android.util.Log;
import android.webkit.JavascriptInterface;

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

    @JavascriptInterface
    public void postMessage(String rawMessage) {
        try {
            JSONObject request = new JSONObject(rawMessage == null ? "{}" : rawMessage);
            String requestId = request.optString("requestId", "");
            String action = request.optString("action", "");
            JSONObject payload = request.optJSONObject("payload");
            if (payload == null) payload = new JSONObject();
            handleRequest(requestId, action, payload);
        } catch (Exception error) {
            Log.e(TAG, "Invalid bridge request", error);
            sendError("", "INVALID_REQUEST", error.getMessage());
        }
    }

    private void handleRequest(String requestId, String action, JSONObject payload) throws JSONException {
        switch (action) {
            case "app.ready":
                sendSuccess(requestId, new JSONObject()
                        .put("native", true)
                        .put("platform", "android")
                        .put("bridgeVersion", 1));
                activity.sendBridgeEvent("native.ready", new JSONObject().put("ready", true));
                break;
            case "settings.load":
                sendSuccess(requestId, settingsRepository.load());
                break;
            case "settings.save":
                JSONObject savedSettings = settingsRepository.save(payload);
                DeviceCoreService.configureSerial(savedSettings);
                DeviceCoreService.recordOperation("settings.saved", savedSettings);
                sendSuccess(requestId, savedSettings);
                break;
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
                    sendError(requestId, "SERIAL_SEND_FAILED", error.getMessage());
                }
                break;
            case "cabinet.unlockDoor":
                try {
                    sendSuccess(requestId, DeviceCoreService.openDoor(payload.optInt("slotNumber", -1), true, "TAKE", "ADMIN"));
                } catch (Exception error) {
                    sendError(requestId, "DOOR_OPEN_FAILED", error.getMessage());
                }
                break;
            case "cabinet.takeCard":
                try {
                    sendSuccess(requestId, DeviceCoreService.openDoor(payload.optInt("slotNumber", -1), false, "TAKE", "FACE"));
                } catch (Exception error) {
                    sendError(requestId, "DOOR_OPEN_FAILED", error.getMessage());
                }
                break;
            case "cabinet.returnCard":
                try {
                    sendSuccess(requestId, DeviceCoreService.openDoor(payload.optInt("slotNumber", -1), true, "RETURN", "ADMIN"));
                } catch (Exception error) {
                    sendError(requestId, "DOOR_OPEN_FAILED", error.getMessage());
                }
                break;
            case "cabinet.querySlot":
                try {
                    sendSuccess(requestId, DeviceCoreService.querySlot(payload.optInt("slotNumber", -1)));
                } catch (Exception error) {
                    sendError(requestId, "SLOT_QUERY_FAILED", error.getMessage());
                }
                break;
            case "cabinet.readVersion":
                try {
                    sendSuccess(requestId, DeviceCoreService.readBoardVersion(payload.optInt("slotNumber", -1)));
                } catch (Exception error) {
                    sendError(requestId, "VERSION_READ_FAILED", error.getMessage());
                }
                break;
            case "cabinet.unlockAll":
                try {
                    sendSuccess(requestId, DeviceCoreService.openAllDoors(true));
                } catch (Exception error) {
                    sendError(requestId, "DOOR_OPEN_ALL_FAILED", error.getMessage());
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
                sendError(requestId, "NOT_IMPLEMENTED", "NOT_IMPLEMENTED: 当前阶段由Web端Mock实现，后续在Android原生层接入");
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

    @JavascriptInterface public void log(String message) { Log.d(TAG, message); }
    @JavascriptInterface public void logInfo(String message) { Log.i(TAG, message); }
    @JavascriptInterface public void logWarn(String message) { Log.w(TAG, message); }
    @JavascriptInterface public void logError(String message) { Log.e(TAG, message); }
    @JavascriptInterface public void logVerbose(String message) { Log.v(TAG, message); }
    @JavascriptInterface public void logDebug(String tag, String message) { Log.d(tag, message); }
}
