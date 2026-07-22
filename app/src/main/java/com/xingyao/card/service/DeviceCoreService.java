package com.xingyao.card.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.xingyao.card.R;
import com.xingyao.card.core.DeviceEventLogRepository;
import com.xingyao.card.core.FaceAiManager;
import com.xingyao.card.core.SerialConnectionManager;
import com.xingyao.card.core.WebSocketConnectionManager;

import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;

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
    private FaceAiManager faceAiManager;
    private DeviceEventLogRepository eventLogRepository;
    private final JSONObject slotCache = new JSONObject();

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d("DeviceCoreService", "onCreate START");
        instance = this;
        eventLogRepository = new DeviceEventLogRepository(this);
        webSocketManager = new WebSocketConnectionManager(new com.xingyao.card.core.NativeSettingsRepository(this), new WebSocketConnectionManager.Listener() {
            @Override public void onStatusChanged(JSONObject status) { recordAndPublish("socket.status", "socket.statusChanged", status); }
            @Override public void onCommand(JSONObject command) { handleSocketCommand(command); }
            @Override public void onMessage(JSONObject message) { recordAndPublish("socket.message", "socket.message", message); }
        });
        serialManager = new SerialConnectionManager(this, new SerialConnectionManager.Listener() {
            @Override public void onStatusChanged(JSONObject status) { recordAndPublish("serial.status", "serial.statusChanged", status); }
            @Override public void onDataReceived(JSONObject data) {
                Log.e("ddd", "onDataReceived: "+data.toString());
                recordAndPublish("serial.received", "serial.dataReceived", data);
            }
            @Override public void onSlotStatus(JSONObject slot) { cacheSlot(slot); recordAndPublish("cabinet.slot.status", "cabinet.slotStatus", slot); reportSlotStatus(slot); }
        });
        faceAiManager = FaceAiManager.getInstance();
        faceAiManager.init(this);
        try {
            recordAndPublish("biometric.engine", "recognition.statusChanged",
                    new JSONObject().put("state", "INITIALIZED").put("message", "FaceAISDK 引擎已初始化"));
        } catch (JSONException ignored) {
            // 状态上报失败不影响服务启动
        }
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        Log.d("DeviceCoreService", "onCreate webSocketManager.start...");
        webSocketManager.start();
        Log.d("DeviceCoreService", "onCreate serialManager.start...");
        serialManager.start();
        Log.d("DeviceCoreService", "onCreate END");
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
        if (faceAiManager != null) faceAiManager.release();
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
        result.put("http", new JSONObject().put("state", "DISABLED").put("message", "当前阶段未接入HTTP"));
        result.put("deviceAuthorization", new JSONObject().put("state", "AUTHORIZED").put("message", "已授权"));
        result.put("recognitionEngine", instance == null || instance.faceAiManager == null
                ? new JSONObject().put("state", "STOPPED").put("message", "FaceAISDK 服务未启动")
                : new JSONObject().put("state", instance.faceAiManager.isInitialized() ? "INITIALIZED" : "STOPPED")
                        .put("message", instance.faceAiManager.isInitialized() ? "FaceAISDK 引擎运行中" : "FaceAISDK 未初始化")
                        .put("faceCount", instance.faceAiManager.getFaceCount()));
        return result;
    }

    public static void setDeviceEventListener(DeviceEventListener listener) {
        eventListener = listener;
    }

    public static void configureSerial(JSONObject settings) {
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
        if (instance == null || instance.serialManager == null) throw new IllegalStateException("原生设备服务未启动");
        JSONObject result = instance.serialManager.openDoor(slotNumber, administrator);
        instance.record("cabinet.door.open", result);
        instance.sendCardEvent(slotNumber, "TAKE", administrator ? "ADMIN" : "FACE");
        return result;
    }

    public static JSONObject openAllDoors(boolean administrator) throws Exception {
        if (instance == null || instance.serialManager == null) throw new IllegalStateException("原生设备服务未启动");
        JSONObject result = instance.serialManager.openAllDoors(administrator);
        instance.record("cabinet.door.openAll", result);
        return result;
    }

    public static void restartFaceRecognition() {
        if (instance != null && instance.faceAiManager != null) instance.faceAiManager.release();
        if (instance != null) instance.faceAiManager.init(instance);
    }

    /**
     * @deprecated FaceAISDK 使用 CameraX + face_overlay UI 方式录入人脸. 请通过 {@link com.xingyao.card.FaceEnrollmentController} 启动录入.
     */
    @Deprecated
    public static JSONObject enrollFace(String employeeId, String employeeName, byte[] frame, int width, int height) throws Exception {
        throw new UnsupportedOperationException(
                "FaceAISDK does not support NV21 frame enrollment. Use FaceEnrollmentController instead.");
    }

    /**
     * @deprecated FaceAISDK 使用 CameraX + face_overlay UI 方式进行人脸检索. 请通过 {@link com.xingyao.card.FaceEnrollmentController} 启动检索.
     */
    @Deprecated
    public static JSONObject verifyFace(byte[] frame, int width, int height) throws Exception {
        throw new UnsupportedOperationException(
                "FaceAISDK does not support NV21 frame verification. Use FaceEnrollmentController instead.");
    }

    public static void recordOperation(String category, JSONObject payload) {
        if (instance != null) instance.record(category, payload);
    }

    // ==================== Private methods (unchanged) ====================

    private void recordAndPublish(String category, String event, JSONObject data) {
        record(category, data);
        publish(event, data);
    }

    private void handleSocketCommand(JSONObject command) {
        recordAndPublish("socket.command", "socket.command", command);
        String cmd = command.optString("cmd", "");
        try {
            if ("remoteOpen".equals(cmd)) handleRemoteOpen(command);
            else if ("remoteEjectAll".equals(cmd)) handleRemoteEjectAll(command);
            else if ("queryStatus".equals(cmd)) handleQueryStatus(command);
            else if ("syncUser".equals(cmd)) sendSocket(new JSONObject().put("cmd", "syncUserResp").put("code", 0).put("msg", "accepted"));
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

    private void handleRemoteEjectAll(JSONObject command) throws Exception {
        JSONObject response = new JSONObject().put("cmd", "remoteEjectAllResp");
        if (!command.optBoolean("confirm", false)) {
            sendSocket(response.put("code", 9000).put("msg", "confirm required").put("ejectedCount", 0));
            return;
        }
        try {
            JSONObject result = serialManager.openAllDoors(true);
            sendSocket(response.put("code", 0).put("msg", "success").put("ejectedCount", result.optInt("successCount", 0)));
        } catch (Exception error) {
            sendSocket(response.put("code", 4003).put("msg", error.getMessage()).put("ejectedCount", 0));
        }
    }

    private void handleQueryStatus(JSONObject command) throws Exception {
        int slotId = command.optInt("slotId", -1);
        JSONArray data = new JSONArray();
        synchronized (slotCache) {
            JSONArray keys = slotCache.names();
            if (keys != null) {
                for (int index = 0; index < keys.length(); index++) {
                    String key = keys.getString(index);
                    JSONObject slot = slotCache.getJSONObject(key);
                    if (slotId < 0 || slot.optInt("slotNumber") == slotId) data.put(toBackendSlot(slot));
                }
            }
        }
        sendSocket(new JSONObject().put("cmd", "statusResp").put("data", data));
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
                    .put("deviceId", currentDeviceId())
                    .put("timestamp", System.currentTimeMillis())
                    .put("slots", slots));
        } catch (Exception ignored) { }
    }

    private void sendCardEvent(int slotId, String eventType, String authType) {
        try {
            JSONObject slot = null;
            synchronized (slotCache) { if (slotCache.has(String.valueOf(slotId))) slot = slotCache.getJSONObject(String.valueOf(slotId)); }
            JSONObject payload = new JSONObject()
                    .put("cmd", "cardEvent")
                    .put("deviceId", currentDeviceId())
                    .put("cardNo", slot == null ? "" : slot.optString("cardNumber", ""))
                    .put("eventType", eventType)
                    .put("slotId", slotId)
                    .put("timestamp", System.currentTimeMillis())
                    .put("authType", normalizeAuthType(authType));
            try { sendSocket(payload); } catch (Exception error) {
                record("socket.card.event.failed", new JSONObject().put("message", error.getMessage()).put("payload", payload));
            }
            postCardEventHttp(payload);
        } catch (Exception ignored) { }
    }

    private void postCardEventHttp(JSONObject socketPayload) {
        new Thread(() -> {
            java.net.HttpURLConnection connection = null;
            try {
                JSONObject settings = new com.xingyao.card.core.NativeSettingsRepository(this).load();
                String endpoint = httpBaseUrl(settings) + "/api/v1/card/event";
                JSONObject body = new JSONObject()
                        .put("deviceId", socketPayload.optString("deviceId"))
                        .put("cardNo", socketPayload.optString("cardNo"))
                        .put("eventType", socketPayload.optString("eventType"))
                        .put("slotId", socketPayload.optInt("slotId"))
                        .put("timestamp", socketPayload.optLong("timestamp"))
                        .put("authType", socketPayload.optString("authType"));
                connection = (java.net.HttpURLConnection) new java.net.URL(endpoint).openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                byte[] bytes = body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                try (java.io.OutputStream out = connection.getOutputStream()) { out.write(bytes); }
                JSONObject result = new JSONObject()
                        .put("url", endpoint)
                        .put("statusCode", connection.getResponseCode())
                        .put("payload", body);
                record("http.card.event", result);
            } catch (Exception error) {
                try { record("http.card.event.failed", new JSONObject().put("message", error.getMessage())); }
                catch (JSONException ignored) { }
            } finally {
                if (connection != null) connection.disconnect();
            }
        }, "card-event-http").start();
    }

    private static String httpBaseUrl(JSONObject settings) {
        String address = settings == null ? "" : settings.optString("serverAddress", "").trim();
        int httpPort = parsePort(settings == null ? "" : settings.optString("httpPort", ""), 8081);
        if (address.isEmpty()) return "http://127.0.0.1:" + httpPort;
        String base = address.replaceAll("/+$", "");
        if (!base.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) base = "http://" + base;
        try {
            java.net.URI uri = java.net.URI.create(base);
            if (uri.getPort() > 0) return base;
            return new java.net.URI(uri.getScheme(), uri.getUserInfo(), uri.getHost(), httpPort, uri.getPath(), uri.getQuery(), uri.getFragment()).toString().replaceAll("/+$", "");
        } catch (Exception ignored) {
            return base;
        }
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
        synchronized (slotCache) {
            JSONArray keys = slotCache.names();
            if (keys != null) {
                for (int index = 0; index < keys.length(); index++) {
                    try {
                        JSONObject slot = slotCache.getJSONObject(keys.getString(index));
                        String status = slot.optString("status", "");
                        if ("FULL".equals(status) || "CHARGING".equals(status) || "OCCUPIED".equals(status)) {
                            return slot.optInt("slotNumber", 1);
                        }
                    } catch (JSONException ignored) { }
                }
            }
        }
        return 1;
    }

    private void sendSocket(JSONObject payload) throws Exception {
        if (webSocketManager == null) throw new IllegalStateException("TCP长连接未启动");
        webSocketManager.send(payload);
        record("socket.sent", payload);
    }

    private void cacheSlot(JSONObject slot) {
        if (slot == null) return;
        synchronized (slotCache) {
            try { slotCache.put(String.valueOf(slot.optInt("slotNumber")), new JSONObject(slot.toString())); }
            catch (JSONException ignored) { }
        }
    }

    private JSONObject toBackendSlot(JSONObject slot) throws JSONException {
        return new JSONObject()
                .put("slotId", slot.optInt("slotNumber"))
                .put("status", mapBackendStatus(slot.optString("status")))
                .put("cardNo", slot.optString("cardNumber", ""))
                .put("workCode", slot.optInt("workCode", -1))
                .put("cardCode", slot.optInt("cardCode", -1))
                .put("doorCode", slot.optInt("doorCode", -1))
                .put("faultMask", slot.optInt("faultMask", parseFaultCode(slot.optString("faultCode", ""))))
                .put("workStatus", slot.optString("workStatus", ""))
                .put("presenceStatus", slot.optString("presenceStatus", ""))
                .put("doorStatus", slot.optString("doorStatus", ""))
                .put("voltage", slot.optDouble("voltage", 0D))
                .put("current", slot.optDouble("current", 0D))
                .put("faultCode", parseFaultCode(slot.optString("faultCode", "")));
    }

    private String currentDeviceId() {
        try { return new com.xingyao.card.core.NativeSettingsRepository(this).load().optString("deviceId", "DEV001"); }
        catch (Exception ignored) { return "DEV001"; }
    }

    private static String normalizeAuthType(String value) {
        String authType = value == null ? "" : value.toUpperCase();
        if ("FACE".equals(authType) || "FINGER".equals(authType) || "ADMIN".equals(authType) || "REMOTE".equals(authType)) return authType;
        return "REMOTE";
    }

    private static String mapBackendStatus(String status) {
        if ("EMPTY".equals(status)) return "EMPTY";
        if ("CHARGING".equals(status)) return "CHARGING";
        if ("FULL".equals(status)) return "FULL";
        if ("OCCUPIED".equals(status)) return "OCCUPIED";
        if ("CHARGING_FAULT".equals(status) || "COMMUNICATION_FAULT".equals(status) || "ILLEGAL_CARD".equals(status)) return "FAULT";
        return "OCCUPIED";
    }

    private static int parseFaultCode(String value) {
        if (value == null || value.isEmpty()) return 0;
        try { return value.startsWith("0x") || value.startsWith("0X") ? Integer.parseInt(value.substring(2), 16) : Integer.parseInt(value); }
        catch (Exception ignored) { return 0; }
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
