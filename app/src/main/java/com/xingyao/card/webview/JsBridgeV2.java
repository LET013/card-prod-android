package com.xingyao.card.webview;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import com.xingyao.card.MainActivity;
import com.xingyao.card.core.FaceAiManager;
import com.xingyao.card.core.bootstrap.BootstrapEvent;
import com.xingyao.card.core.bootstrap.CredentialStore;
import com.xingyao.card.core.bootstrap.DeviceBootstrapManager;
import com.xingyao.card.core.entity.http.DeviceConfigEntity;
import com.xingyao.card.core.http.DownloadCallback;
import com.xingyao.card.core.http.HttpClientManager;
import com.xingyao.card.core.log.AppLog;
import com.xingyao.card.core.maintenance.AppRestartManager;
import com.xingyao.card.core.maintenance.AppUpdateManager;
import com.xingyao.card.core.mqtt.MqttConnectionEvent;
import com.xingyao.card.core.mqtt.MqttMessageEvent;
import com.xingyao.card.core.mqtt.MqttTrafficEvent;
import com.xingyao.card.core.mqtt.XMqttClient;
import com.xingyao.card.core.serial.DeviceSerialManager;
import com.xingyao.card.core.serial.SerialDataReceivedEvent;
import com.xingyao.card.core.serial.SerialRuntimeRegistry;
import com.xingyao.card.core.serial.SerialStatusEvent;
import com.xingyao.card.core.serial.SlotSnapshotEvent;
import com.xingyao.card.core.serial.SlotStatusBatchEvent;
import com.xingyao.card.core.serial.SlotStatusEvent;
import com.xingyao.card.core.tts.TtsManager;
import com.ai.face.faceSearch.search.FaceSearchFeature;
import com.ai.face.faceSearch.search.FaceSearchFeatureManger;
import com.xingyao.card.face.FaceEnrollmentController;
import com.xingyao.card.face.FaceFeatureExtractor;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JsBridge V2：以六大原始能力通道替代旧 Facade 的 action 路由。
 *
 * <p>六大通道：启动流程 / HTTP / MQTT / 串口 / SQLite 持久化 / 人脸识别。
 * 全部业务逻辑归还 Vue，JsBridge 只充当能力暴露层。
 *
 * <p>消息格式（Vue → Java）：
 * <pre>{@code
 * {"id":"req_001","action":"http.get","payload":{"path":"/device/config"}}
 * }</pre>
 *
 * <p>响应格式（Java → Vue）：
 * <pre>{@code
 * {"requestId":"req_001","success":true,"data":{...}}
 * }</pre>
 *
 * <p>事件格式（Java → Vue，主动推送）：
 * <pre>{@code
 * {"type":"event","event":"face.recognized","data":{...}}
 * }</pre>
 */
public class JsBridgeV2 {

    private static final String TAG = "JsBridgeV2";
    private static final int MAX_FACE_IMAGE_BYTES = 10 * 1024 * 1024;
    private static final String FACE_IMAGE_MIME_TYPE = "image/jpeg";
    private static final long SLOT_EVENT_BATCH_WINDOW_MS = 120L;

    // ── 依赖 ──
    private final MainActivity activity;
    private final Context context;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "JsBridge-IO");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService storageExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "JsBridge-Storage");
        t.setDaemon(true);
        return t;
    });

    // HTTP
    private volatile HttpClientManager httpClient;

    // MQTT
    private volatile XMqttClient mqttClient;
    private final Set<String> mqttRegisteredCmds = new CopyOnWriteArraySet<>();

    // Serial
    private volatile DeviceSerialManager serialManager;
    private final SerialLogRingBuffer serialLogBuffer = new SerialLogRingBuffer(500);
    private final Set<Integer> subscribedSerialCmds = new CopyOnWriteArraySet<>();
    private final Object pendingSlotEventsLock = new Object();
    private final Map<Integer, JSONObject> pendingSlotEvents = new TreeMap<>();
    private final Handler slotEventHandler = new Handler(Looper.getMainLooper());
    private boolean slotEventFlushScheduled;
    private final Runnable flushSlotEventsRunnable = this::flushPendingSlotEvents;

    // SQLite Storage
    private volatile SQLiteDatabase db;

    // Bootstrap
    private volatile DeviceBootstrapManager bootstrapManager;
    private Thread bootstrapThread;
    private final AtomicBoolean bootstrapInProgress = new AtomicBoolean(false);
    private int lastEmittedMqttAuthenticationGeneration = -1;

    // Face
    private volatile FaceEnrollmentController currentFaceController;
    private volatile String currentFaceAction; // "recognition" | "enrollment" | null
    private final AtomicBoolean faceActive = new AtomicBoolean(false);
    private Handler faceTimeoutHandler;
    private Runnable faceTimeoutRunnable;
    private volatile String enrollmentPhotoBase64;
    private volatile int enrollmentPhotoSize;
    private final TtsManager ttsManager;

    // ── 构造 ──

    public JsBridgeV2(MainActivity activity) {
        this.activity = activity;
        this.context = activity.getApplicationContext();
        this.ttsManager = new TtsManager(context);
    }

    /* ==================== 依赖注入 ==================== */

    public void setHttpClient(HttpClientManager httpClient) {
        this.httpClient = httpClient;
    }

    public void setMqttClient(XMqttClient mqttClient) {
        this.mqttClient = mqttClient;
        AppLog.setMqttClient(mqttClient);
        Log.i(TAG, "setMqttClient: client=" + (mqttClient != null ? "non-null" : "NULL") + " connected=" + (mqttClient != null && mqttClient.isConnected()));
    }

    public void setSerialManager(DeviceSerialManager serialManager) {
        this.serialManager = serialManager;
    }

    /* ==================== 消息入口 ==================== */

    public void handleTrustedMessage(String json) {
        if (json == null || json.trim().isEmpty()) {
            Log.w(TAG, "Empty message, ignored");
            return;
        }
        try {
            JSONObject message = new JSONObject(json);
            String id = message.optString("id", "");
            String action = message.optString("action", "");
            JSONObject payload = message.optJSONObject("payload");
            if (payload == null) payload = new JSONObject();

//            Log.d(TAG, "→ handleTrustedMessage: action=" + action + " id=" + id);

            if (action.isEmpty()) {
                sendError(id, "INVALID_ACTION", "action is required");
                return;
            }
            dispatch(action, payload, id);
        } catch (JSONException e) {
            Log.e(TAG, "Invalid JSON message: " + json, e);
            sendError("", "PARSE_ERROR", e.getMessage());
        }
    }

    private void dispatch(String action, JSONObject payload, String requestId) {
        if (action.startsWith("bootstrap.")) {
            handleBootstrap(action, payload, requestId);
        } else if (action.startsWith("http.")) {
            handleHttp(action, payload, requestId);
        } else if (action.startsWith("mqtt.")) {
            handleMqtt(action, payload, requestId);
        } else if (action.startsWith("serial.")) {
            handleSerial(action, payload, requestId);
        } else if (action.startsWith("storage.")) {
            handleStorage(action, payload, requestId);
        } else if (action.startsWith("face.")) {
            handleFace(action, payload, requestId);
        } else if (action.startsWith("fingerprint.")) {
            handleFingerprint(action, payload, requestId);
        } else if (action.startsWith("offlineActivation.")) {
            handleOfflineActivation(action, payload, requestId);
        } else if (action.startsWith("diagnostics.")) {
            handleDiagnostics(action, payload, requestId);
        } else if (action.startsWith("tts.")) {
            handleTts(action, payload, requestId);
        } else if (action.startsWith("app.")) {
            handleApp(action, payload, requestId);
        } else {
            sendError(requestId, "UNKNOWN_ACTION", "Unknown action: " + action);
        }
    }

    /* ==================== 1b. 离线激活预留 ==================== */

    private void handleOfflineActivation(String action, JSONObject payload, String requestId) {
        try {
            JSONObject data = new JSONObject();
            data.put("available", false);
            data.put("mode", "ONLINE_ONLY");
            data.put("status", "NOT_AVAILABLE");
            data.put("message", "当前在线版未启用离线激活");

            if ("offlineActivation.status".equals(action)) {
                sendSuccess(requestId, data);
                return;
            }

            sendError(requestId, "OFFLINE_ACTIVATION_RESERVED",
                    "离线激活为未来离线版预留，当前在线版不执行离线激活或离线配置解密");
        } catch (JSONException e) {
            sendError(requestId, "INTERNAL_ERROR", e.getMessage());
        }
    }

    /* ==================== 1. 启动流程 ==================== */

    private void handleBootstrap(String action, JSONObject payload, String requestId) {
        switch (action) {
            case "bootstrap.start":
                handleBootstrapStart(payload, requestId);
                break;
            case "bootstrap.activate":
                handleBootstrapActivate(payload, requestId);
                break;
            case "bootstrap.retry":
                handleBootstrapRetry(payload, requestId);
                break;
            case "bootstrap.cancel":
                handleBootstrapCancel(payload, requestId);
                break;
            case "bootstrap.refreshCode":
                handleBootstrapRefreshCode(payload, requestId);
                break;
            case "bootstrap.deviceInfo":
                handleBootstrapDeviceInfo(payload, requestId);
                break;
            default:
                sendError(requestId, "UNKNOWN_ACTION", "Unknown bootstrap action: " + action);
        }
    }

    private void handleBootstrapStart(JSONObject payload, String requestId) {
        // 只接收 serverUrl；APP 渠道由 AndroidManifest 在打包时写入。
        // MQTT host/port 由 bootstrap getConfig 步骤下发，不依赖 Vue 端传入或本地硬编码。
        String serverUrl = payload.optString("serverUrl", "");
        Log.i(TAG, "bootstrap.start: requestId=" + requestId + " serverUrl=" + serverUrl);
        if (serverUrl.isEmpty()) {
            sendError(requestId, "INVALID_CONFIG", "serverUrl is required");
            return;
        }

        Log.d(TAG, "bootstrap.start: submitting to ioExecutor...");
        ioExecutor.execute(() -> {
            try {
                CredentialStore store = new CredentialStore(context);
                JSONObject settings = store.load();
                settings.put("serverUrl", serverUrl);
                store.save(settings);

                sendSuccessJson(requestId, "accepted", true);

                // Start bootstrap in background thread
                startBootstrapInBackground();
            } catch (Exception e) {
                Log.e(TAG, "Failed to start bootstrap", e);
                sendError(requestId, "BOOTSTRAP_START_ERROR", e.getMessage());
            }
        });
    }

    private void startBootstrapInBackground() {
        if (!bootstrapInProgress.compareAndSet(false, true)) {
            Log.w(TAG, "Bootstrap already in progress, ignoring duplicate start");
            return;
        }
        if (bootstrapManager != null && bootstrapManager.isBootstrapComplete()) {
            Log.w(TAG, "Bootstrap already complete, ignoring duplicate start");
            bootstrapInProgress.set(false);
            return;
        }
        // 先反注册再注册，避免重试时重复注册导致 EventBusException
        try { EventBus.getDefault().unregister(this); } catch (Exception ignored) {}
        EventBus.getDefault().register(this);
        bootstrapThread = new Thread(() -> {
            try {
                DeviceBootstrapManager mgr = new DeviceBootstrapManager(() -> context);
                bootstrapManager = mgr;
                Log.i(TAG, "Bootstrap started in background");
                mgr.bootstrap();
                // After bootstrap, make HTTP and MQTT clients available
                if (Thread.currentThread().isInterrupted()) {
                    Log.w(TAG, "Bootstrap thread interrupted, skipping client injection");
                    return;
                }
                if (mgr.isBootstrapComplete()) {
                    HttpClientManager hc = mgr.getHttpClient();
                    if (hc != null) setHttpClient(hc);
                    XMqttClient mc = mgr.getMqttClient();
                    if (mc != null) {
                        setMqttClient(mc);
                        emitAuthenticatedMqttConnected();
                        Log.i(TAG, "Bootstrap complete, MQTT client injected. connected=" + mc.isConnected());
                    } else {
                        Log.w(TAG, "Bootstrap complete but MQTT client is NULL from bootstrap manager");
                    }
                } else {
                    Log.w(TAG, "Bootstrap did NOT complete successfully, MQTT client NOT injected");
                }
            } finally {
                bootstrapInProgress.set(false);
            }
        }, "Bootstrap-Thread");
        bootstrapThread.setPriority(Thread.NORM_PRIORITY);
        bootstrapThread.start();
    }

    private void handleBootstrapActivate(JSONObject payload, String requestId) {
        String code = payload.optString("code", "");
        if (code.isEmpty()) {
            sendError(requestId, "INVALID_CODE", "code is required");
            return;
        }
        if (bootstrapManager != null) {
            bootstrapManager.verifyCode(code);
            sendSuccessJson(requestId, "accepted", true);
        } else {
            sendError(requestId, "BOOTSTRAP_NOT_READY", "Bootstrap manager not available");
        }
    }

    private void handleBootstrapRetry(JSONObject payload, String requestId) {
        if (bootstrapManager != null) {
            sendSuccessJson(requestId, "accepted", true);
            bootstrapManager.retry();
        } else {
            sendError(requestId, "BOOTSTRAP_NOT_READY", "Bootstrap manager not available");
        }
    }

    private void handleBootstrapRefreshCode(JSONObject payload, String requestId) {
        if (bootstrapManager != null) {
            sendSuccessJson(requestId, "accepted", true);
            bootstrapManager.refreshActivationCode();
        } else {
            sendError(requestId, "BOOTSTRAP_NOT_READY", "Bootstrap manager not available");
        }
    }

    private void handleBootstrapCancel(JSONObject payload, String requestId) {
        shutdownBootstrap();
        sendSuccessJson(requestId, "cancelled", true);
    }

    private void handleBootstrapDeviceInfo(JSONObject payload, String requestId) {
        try {
            JSONObject data = new JSONObject();

            // 设备号
            try {
                CredentialStore store = new CredentialStore(context);
                String deviceCode = store.getDeviceCode();
                data.put("deviceCode", deviceCode != null ? deviceCode : "");
                data.put("channelId", store.getChannelId());
            } catch (Exception e) {
                Log.w(TAG, "Failed to read deviceCode", e);
                data.put("deviceCode", "");
            }

            // 激活状态
            boolean activated = false;
            if (bootstrapManager != null) {
                try {
                    activated = bootstrapManager.isBootstrapComplete();
                } catch (Exception e) {
                    Log.w(TAG, "Failed to read bootstrap state", e);
                }
            }
            data.put("activated", activated);

            // MQTT 在线状态
            boolean mqttConnected = false;
            if (mqttClient != null) {
                try {
                    mqttConnected = mqttClient.isConnected()
                            && bootstrapManager != null && bootstrapManager.isMqttAuthenticated();
                } catch (Exception e) {
                    Log.w(TAG, "Failed to read MQTT state", e);
                }
            }
            data.put("mqttConnected", mqttConnected);

            sendSuccess(requestId, data);
        } catch (JSONException e) {
            sendError(requestId, "INTERNAL_ERROR", e.getMessage());
        }
    }

    // EventBus: 接收 BootstrapEvent 并转发给 Vue
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onBootstrapEvent(BootstrapEvent event) {
        try {
            JSONObject data = new JSONObject();
            data.put("phase", event.phase.name());
            data.put("message", event.message != null ? event.message : "");
            if (event.deviceCode != null) data.put("deviceCode", event.deviceCode);
            if (event.initialAdminPassword != null && !event.initialAdminPassword.isEmpty()) {
                data.put("initialAdminPassword", event.initialAdminPassword);
            }

            switch (event.phase) {
                case VERSION_CHECK:
                case REGISTERING:
                case REGISTERED:
                case ACTIVATING:
                case ACTIVATED:
                case VERIFYING_CODE:
                case CODE_VERIFY_FAILED:
                case GETTING_CONFIG:
                case CONNECTING_MQTT:
                case MQTT_CONNECTED:
                case LOGGING_IN:
                    emit("bootstrap.progress", data);
                    break;

                case LOGGED_IN:
                    emit("bootstrap.progress", data);
                    emitAuthenticatedMqttConnected();
                    break;

                case MQTT_SESSION_LOST:
                    JSONObject disconnectedData = new JSONObject();
                    disconnectedData.put("reason", event.message != null ? event.message : "设备连接已失效");
                    disconnectedData.put("timestamp", System.currentTimeMillis());
                    emit("mqtt.disconnected", disconnectedData);
                    break;

                case WAITING_ACTIVATION_CODE:
                    if (event.registerCode != null) {
                        data.put("registerCode", event.registerCode);
                        data.put("expireTime", event.registerCodeExpireTime);
                    }
                    emit("bootstrap.progress", data);
                    break;

                case FORCE_UPDATE:
                    if (event.versionInfo != null) data.put("releaseNotes", event.versionInfo);
                    if (event.extra instanceof JSONObject) {
                        data.put("versionInfo", event.extra);
                    }
                    emit("bootstrap.progress", data);
                    emit("bootstrap.error", data.put("code", "FORCE_UPDATE"));
                    break;

                case RUNNING:
                    emit("bootstrap.progress", data);
                    // Publish config data（完整 31 个参数，来自 DeviceConfigEntity）
                    try {
                        CredentialStore store = new CredentialStore(context);
                        DeviceConfigEntity configEntity = store.getDeviceConfigEntity();
                        JSONObject configData = new JSONObject();
                        if (configEntity != null) {
                            // 向外暴露完整配置（31 个参数，含所有默认值）
                            configData = configEntity.toJson();
                        } else {
                            // config 未就绪时的兜底
                            configData.put("communicationMode",
                                    store.getString("communicationMode"));
                        }
                        emit("bootstrap.config", configData);
                    } catch (Exception e) {
                        Log.w("JsBridgeV2", "config publish failed", e);
                    }

                    // Publish bootstrap success
                    JSONObject successData = new JSONObject();
                    successData.put("phase", "RUNNING");
                    if (event.deviceCode != null) successData.put("deviceCode", event.deviceCode);
                    emit("bootstrap.success", successData);

                    // 推送设备信息（激活完成 + 设备号）
                    emitDeviceInfo(event.deviceCode != null ? event.deviceCode : "");
                    break;

                case ERROR:
                    JSONObject errData = new JSONObject();
                    errData.put("phase", "ERROR");
                    errData.put("code", "BOOTSTRAP_ERROR");
                    errData.put("message", event.message != null ? event.message : "未知错误");
                    emit("bootstrap.error", errData);
                    break;
            }
        } catch (JSONException e) {
            Log.e(TAG, "Failed to build bootstrap event", e);
        }
    }

    /* ==================== 2. HTTP 通道 ==================== */

    private void handleHttp(String action, JSONObject payload, String requestId) {
        switch (action) {
            case "http.get":
            case "http.post":
            case "http.multipart":
            case "http.download":
                boolean async = "async".equalsIgnoreCase(payload.optString("mode", ""));
                String asyncId = payload.optString("requestId", requestId);
                if (async) {
                    handleHttpAsync(action, payload, requestId, asyncId);
                } else {
                    handleHttpSync(action, payload, requestId);
                }
                break;
            default:
                sendError(requestId, "UNKNOWN_ACTION", "Unknown http action: " + action);
        }
    }

    private void handleHttpSync(String action, JSONObject payload, String requestId) {
        ioExecutor.execute(() -> {
            try {
                JSONObject result = executeHttpSync(action, payload);
                sendSuccess(requestId, result);
            } catch (Exception e) {
                sendError(requestId, "HTTP_ERROR", e.getMessage());
            }
        });
    }

    private void handleHttpAsync(String action, JSONObject payload, String requestId, String asyncId) {
        ioExecutor.execute(() -> {
            try {
                // 立即确认
                JSONObject ack = new JSONObject();
                ack.put("accepted", true);
                ack.put("requestId", asyncId);
                sendSuccess(requestId, ack);

                // 执行实际请求
                JSONObject result = executeHttpSync(action, payload);
                result.put("requestId", asyncId);
                emit("http.result." + asyncId, result);
            } catch (Exception e) {
                try {
                    JSONObject err = new JSONObject();
                    err.put("status", 0);
                    err.put("error", e.getMessage());
                    err.put("requestId", asyncId);
                    emit("http.result." + asyncId, err);
                } catch (JSONException ignored) {}
                // Also send error to original requestId
                sendError(requestId, "HTTP_ASYNC_ERROR", e.getMessage());
            }
        });
    }

    private static final String API_PREFIX = "/api/v1";

    private JSONObject executeHttpSync(String action, JSONObject payload) throws Exception {
        HttpClientManager client = this.httpClient;
        if (client == null) {
            throw new IOException("HTTP client not available (bootstrap not completed)");
        }
        String path = payload.getString("path");
        if (!path.startsWith("http://") && !path.startsWith("https://") && !path.startsWith(API_PREFIX)) {
            path = API_PREFIX + path;
        }

        if ("http.download".equals(action)) {
            return executeDownload(client, path, payload);
        }

        // Use HttpClientManager's built-in methods (handles auth + baseUrl automatically)
        return executeViaClient(client, action, path, payload);
    }

    private JSONObject executeViaClient(HttpClientManager client, String action,
                                         String path, JSONObject payload) throws Exception {
        try {
            JSONObject body = null;
            if ("http.get".equals(action)) {
                body = client.get(path);
            } else if ("http.multipart".equals(action)) {
                JSONObject file = payload.optJSONObject("file");
                if (file == null) {
                    throw new IllegalArgumentException("multipart file is required");
                }
                JSONObject fields = payload.optJSONObject("fields");
                int fileSize = 0;
                byte[] fileBytes = decodeBase64Payload(file.optString("base64", ""));
                if (fileBytes != null) fileSize = fileBytes.length;
                Log.i(TAG, "HttpClient multipart REQ → " + path
                        + " fields=" + (fields != null ? fields.toString() : "null")
                        + " fileField=" + file.optString("fieldName", "file")
                        + " fileSize=" + fileSize + "B");
                body = client.postMultipart(
                        path,
                        fields,
                        file.optString("fieldName", "file"),
                        file.optString("fileName", "upload.jpg"),
                        file.optString("mimeType", "application/octet-stream"),
                        fileBytes);
                Log.i(TAG, "HttpClient multipart RES ← " + path
                        + " body=" + (body != null ? body.toString() : "null"));
            } else {
                Log.i(TAG, "HttpClient post REQ → " + path);
                body = client.post(path, payload.optJSONObject("body"));
                Log.i(TAG, "HttpClient post RES ← " + path
                        + " body=" + (body != null ? body.toString() : "null"));
            }
            JSONObject result = new JSONObject();
            result.put("status", 200);
            result.put("body", body != null ? body : JSONObject.NULL);
            return result;
        } catch (IOException e) {
            Log.w(TAG, "HttpClient " + action + " ERR ← " + path + " " + e.getMessage());
            // Extract HTTP status from IOException message ("HTTP 401: ...")
            String msg = e.getMessage();
            int status = 0;
            String bodyStr = null;
            if (msg != null && msg.startsWith("HTTP ")) {
                int colonIdx = msg.indexOf(": ");
                if (colonIdx > 5) {
                    try {
                        status = Integer.parseInt(msg.substring(5, colonIdx));
                    } catch (NumberFormatException ignored) {}
                    bodyStr = msg.substring(colonIdx + 2);
                }
            }
            JSONObject result = new JSONObject();
            result.put("status", status > 0 ? status : 0);
            if (bodyStr != null && !bodyStr.isEmpty()) {
                try {
                    result.put("body", new JSONObject(bodyStr));
                } catch (JSONException je) {
                    result.put("body", bodyStr);
                }
            } else {
                result.put("body", JSONObject.NULL);
            }
            result.put("error", msg != null ? msg : "Unknown HTTP error");
            return result;
        }
    }

    private JSONObject executeDownload(HttpClientManager client, String path, JSONObject payload) throws Exception {
        String targetDir = payload.optString("targetDir", "downloads").trim();
        File requestedDir = targetDir.isEmpty() ? new File(context.getFilesDir(), "downloads")
                : new File(targetDir);
        File dir = requestedDir.isAbsolute()
                ? requestedDir.getCanonicalFile()
                : new File(context.getFilesDir(), targetDir).getCanonicalFile();
        if (!isInsideAppStorage(dir)) throw new SecurityException("下载目录必须位于应用私有目录");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("无法创建应用私有下载目录");

        String cleanPath = path == null ? "" : path;
        int queryIndex = cleanPath.indexOf('?');
        if (queryIndex >= 0) cleanPath = cleanPath.substring(0, queryIndex);
        int fragmentIndex = cleanPath.indexOf('#');
        if (fragmentIndex >= 0) cleanPath = cleanPath.substring(0, fragmentIndex);
        String fileName = cleanPath.substring(cleanPath.lastIndexOf('/') + 1)
                .replaceAll("[^A-Za-z0-9._-]", "_");
        if (fileName.isEmpty()) fileName = "download.bin";
        File targetFile = new File(dir, fileName);

        // Use HttpClientManager's async download with CountDownLatch for sync behavior
        final File finalFile = targetFile;
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final String[] errorHolder = new String[1];

        client.download(path, targetFile, new DownloadCallback() {
            @Override
            public void onSuccess(File file) {
                latch.countDown();
            }
            @Override
            public void onFailure(int code, String msg) {
                errorHolder[0] = "HTTP " + code + ": " + msg;
                latch.countDown();
            }
            @Override
            public void onProgress(long bytes, long total) {}
        });

        boolean completed = latch.await(10, TimeUnit.MINUTES);
        if (errorHolder[0] != null) {
            throw new IOException(errorHolder[0]);
        }
        if (!completed) {
            throw new IOException("Download timeout (10 minutes)");
        }

        JSONObject result = new JSONObject();
        result.put("status", 200);
        result.put("filePath", finalFile.getAbsolutePath());
        result.put("size", finalFile.length());
        return result;
    }

    private boolean isInsideAppStorage(File file) throws IOException {
        File target = file.getCanonicalFile();
        File internalRoot = context.getFilesDir().getCanonicalFile();
        if (isInsideRoot(target, internalRoot)) return true;
        File externalFiles = context.getExternalFilesDir(null);
        return externalFiles != null && isInsideRoot(target, externalFiles.getCanonicalFile());
    }

    private static boolean isInsideRoot(File target, File root) {
        String targetPath = target.getPath();
        String rootPath = root.getPath();
        return targetPath.equals(rootPath) || targetPath.startsWith(rootPath + File.separator);
    }

    private File requirePrivateFirmwareFile(String filePath) throws IOException {
        String normalized = filePath == null ? "" : filePath.trim();
        if (normalized.isEmpty()) throw new IOException("缺少固件文件路径");
        File file = new File(normalized).getCanonicalFile();
        if (!isInsideAppStorage(file)) throw new SecurityException("固件文件必须位于应用私有目录");
        if (!file.isFile() || file.length() < 1L) throw new IOException("固件文件不存在或为空");
        return file;
    }

    /* ==================== 诊断通道 ==================== */

    private void handleDiagnostics(String action, JSONObject payload, String requestId) {
        try {
            if ("diagnostics.log.setUploadEnabled".equals(action)) {
                boolean enabled = payload.optBoolean("enabled", false);
                AppLog.setUploadEnabled(enabled);
                sendSuccess(requestId, new JSONObject().put("enabled", enabled));
                return;
            }
            if ("diagnostics.log.write".equals(action)) {
                AppLog.write(payload.optString("level", "INFO"),
                        payload.optString("tag", "Vue"),
                        payload.optString("message", ""));
                sendSuccess(requestId, new JSONObject().put("written", true));
                return;
            }
            sendError(requestId, "UNKNOWN_DIAGNOSTIC_ACTION", "未知的诊断动作: " + action);
        } catch (Exception e) {
            Log.e(TAG, "■ handleDiagnostics error: " + action, e);
            sendError(requestId, "DIAGNOSTIC_ERROR", e.getMessage());
        }
    }

    /* ==================== 应用维护通道 ==================== */

    private void handleApp(String action, JSONObject payload, String requestId) {
        try {
            if ("app.restart".equals(action)) {
                String operationId = payload.optString("operationId", "").trim();
                long delayMs = payload.optLong("delayMs", 3_000L);
                sendSuccess(requestId, AppRestartManager.schedule(context, operationId, delayMs));
                return;
            }
            if ("app.restartStatus".equals(action)) {
                sendSuccess(requestId, AppRestartManager.status(
                        context,
                        payload.optBoolean("clearExecuted", false)));
                return;
            }
            if ("app.updateInfo".equals(action)) {
                sendSuccess(requestId, AppUpdateManager.info(context));
                return;
            }
            if ("app.updateStatus".equals(action)) {
                sendSuccess(requestId, AppUpdateManager.status(
                        context,
                        payload.optBoolean("clearCompleted", false)));
                return;
            }
            if ("app.downloadUpdate".equals(action)) {
                ioExecutor.execute(() -> {
                    try {
                        JSONObject result = AppUpdateManager.downloadAndVerify(
                                context,
                                payload,
                                progress -> emit("app.updateProgress", progress));
                        sendSuccess(requestId, result);
                    } catch (Exception error) {
                        sendError(requestId, "APP_UPDATE_DOWNLOAD_FAILED", error.getMessage());
                    }
                });
                return;
            }
            if ("app.installUpdate".equals(action)) {
                ioExecutor.execute(() -> {
                    try {
                        sendSuccess(requestId, AppUpdateManager.install(
                                context,
                                payload.optString("operationId", "")));
                    } catch (Exception error) {
                        sendError(requestId, "APP_UPDATE_INSTALL_FAILED", error.getMessage());
                    }
                });
                return;
            }
            sendError(requestId, "UNKNOWN_ACTION", "Unknown app action: " + action);
        } catch (Exception e) {
            sendError(requestId, "APP_MAINTENANCE_ERROR", e.getMessage());
        }
    }

    /* ── HTTP helpers ── */

    /* ==================== 3. MQTT 通道 ==================== */

    private void handleMqtt(String action, JSONObject payload, String requestId) {
        switch (action) {
            case "mqtt.send":
                handleMqttSend(payload, requestId);
                break;
            case "mqtt.loginStatus":
                handleMqttLoginStatus(payload, requestId);
                break;
            case "mqtt.handleMessage":
                handleMqttHandleMessage(payload, requestId);
                break;
            default:
                sendError(requestId, "UNKNOWN_ACTION", "Unknown mqtt action: " + action);
        }
    }

    private void handleMqttSend(JSONObject payload, String requestId) {
        XMqttClient client = this.mqttClient;
        String cmd = payload.optString("cmd", "");
        if (client == null || !client.isConnected()) {
            String reason;
            if (client == null) {
                reason = "mqttClient is null (bootstrap not completed or failed)";
            } else {
                reason = "mqttClient.isConnected() returned false (network lost / reconnecting / not yet connected)";
            }
            Log.w(TAG, "handleMqttSend FAILED: cmd=" + cmd + " requestId=" + requestId + " reason=" + reason);
            sendError(requestId, "MQTT_NOT_CONNECTED", "MQTT client not connected");
            return;
        }
        if (bootstrapManager == null || !bootstrapManager.isMqttAuthenticated()) {
            sendError(requestId, "MQTT_NOT_AUTHENTICATED", "MQTT login has not completed");
            return;
        }
        if (cmd.isEmpty()) {
            sendError(requestId, "INVALID_CMD", "cmd is required");
            return;
        }
        JSONObject data = payload.optJSONObject("data");
        String msgId = payload.optString("msgId", null);
        try {
            String actualMsgId = client.sendMessage(cmd, data,
                    (msgId != null && !msgId.trim().isEmpty()) ? msgId.trim() : null);
            JSONObject result = new JSONObject();
            result.put("sent", true);
            result.put("msgId", actualMsgId);
            sendSuccess(requestId, result);
        } catch (Exception e) {
            Log.e(TAG, "handleMqttSend ERROR: cmd=" + cmd + " requestId=" + requestId + " error=" + e.getMessage());
            sendError(requestId, "MQTT_SEND_ERROR", e.getMessage());
        }
    }

    private void handleMqttLoginStatus(JSONObject payload, String requestId) {
        XMqttClient client = this.mqttClient;
        try {
            JSONObject result = new JSONObject();
            result.put("connected", client != null && client.isConnected());
            result.put("authenticated", bootstrapManager != null && bootstrapManager.isMqttAuthenticated());
            sendSuccess(requestId, result);
        } catch (Exception e) {
            sendError(requestId, "MQTT_ERROR", e.getMessage());
        }
    }

    private void handleMqttHandleMessage(JSONObject payload, String requestId) {
        String cmd = payload.optString("cmd", "");
        if (cmd.isEmpty()) {
            sendError(requestId, "INVALID_CMD", "cmd is required");
            return;
        }
        mqttRegisteredCmds.add(cmd);
        try {
            JSONObject regData = new JSONObject();
            regData.put("registered", true);
            regData.put("cmd", cmd);
            sendSuccess(requestId, regData);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to build registered response", e);
        }
    }

    // EventBus: MQTT 消息
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMqttMessage(MqttMessageEvent event) {
        if (event.cmd == null || event.cmd.isEmpty()) return;
        // 只转发已在 Vue 注册的 cmd
        if (!mqttRegisteredCmds.contains(event.cmd)) return;
        if ("remoteOpen".equals(event.cmd)) {
            AppLog.diagnosticI(TAG, "mqtt remoteOpen received msgId="
                    + (event.msgId == null ? "" : event.msgId));
        }
        try {
            JSONObject data = new JSONObject();
            data.put("cmd", event.cmd);
            if (event.msgId != null) data.put("msgId", event.msgId);
            data.put("timestamp", System.currentTimeMillis());
            if (event.data != null) {
                data.put("data", event.data);
            }
            emit("mqtt.message", data);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to build MQTT event", e);
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMqttTraffic(MqttTrafficEvent event) {
        try {
            JSONObject data = new JSONObject();
            data.put("direction", event.direction);
            data.put("cmd", event.cmd);
            if (event.msgId != null && !event.msgId.isEmpty()) data.put("msgId", event.msgId);
            data.put("payloadSize", event.payloadSize);
            data.put("timestamp", event.timestamp);
            emit("mqtt.traffic", data);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to build MQTT traffic event", e);
        }
    }

    // EventBus: MQTT 连接状态
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMqttConnection(MqttConnectionEvent event) {
        try {
            JSONObject data = new JSONObject();
            if (event.connected) {
                if (event.brokerUrl != null) data.put("broker", event.brokerUrl);
                data.put("timestamp", System.currentTimeMillis());
                // TCP 连接完成不代表服务器业务登录完成。
                emit("mqtt.transportConnected", data);
            } else {
                if (event.brokerUrl != null) data.put("broker", event.brokerUrl);
                data.put("timestamp", System.currentTimeMillis());
                emit("mqtt.disconnected", data);
            }
            if (event.connected) emitDeviceInfo(null);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to build MQTT connection event", e);
        }
    }

    /** 只有 MQTT 业务登录成功后才向 Vue 发布可发送事件。 */
    private synchronized void emitAuthenticatedMqttConnected() {
        XMqttClient client = this.mqttClient;
        DeviceBootstrapManager manager = this.bootstrapManager;
        if (client == null || !client.isConnected()
                || manager == null || !manager.isMqttAuthenticated()) {
            return;
        }
        int generation = manager.getMqttAuthenticationGeneration();
        if (generation == lastEmittedMqttAuthenticationGeneration) return;
        lastEmittedMqttAuthenticationGeneration = generation;
        try {
            JSONObject data = new JSONObject();
            data.put("authenticated", true);
            data.put("timestamp", System.currentTimeMillis());
            emit("mqtt.connected", data);
            emitDeviceInfo(null);
        } catch (JSONException e) {
            lastEmittedMqttAuthenticationGeneration = -1;
            Log.e(TAG, "Failed to build authenticated MQTT event", e);
        }
    }

    // EventBus: 卡槽状态（来自 DeviceCoreService → 串口轮询）
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onSlotStatus(SlotStatusEvent event) {
        if (event.slot == null) return;
        int slotNumber = event.slot.optInt("slotNumber",
                event.slot.optInt("slotId", event.slot.optInt("address", 0)));
        if (slotNumber < 1) return;
        synchronized (pendingSlotEventsLock) {
            pendingSlotEvents.put(slotNumber, event.slot);
            if (slotEventFlushScheduled) return;
            slotEventFlushScheduled = true;
        }
        // 将一组轮询帧合并为局部状态更新；完整快照仅由启动广播产生。
        slotEventHandler.postDelayed(flushSlotEventsRunnable, SLOT_EVENT_BATCH_WINDOW_MS);
    }

    /** Native serial capability has already coalesced this batch off the main thread. */
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onSlotStatusBatch(SlotStatusBatchEvent event) {
        if (event.slots == null || event.slots.length() == 0 || activity == null) return;
        try {
            emit("slot.status", new JSONObject().put("slots", event.slots));
        } catch (JSONException e) {
            Log.e(TAG, "Failed to emit native slot status batch", e);
        }
    }

    private void flushPendingSlotEvents() {
        JSONArray slots = new JSONArray();
        synchronized (pendingSlotEventsLock) {
            for (JSONObject slot : pendingSlotEvents.values()) slots.put(slot);
            pendingSlotEvents.clear();
            slotEventFlushScheduled = false;
        }
        if (slots.length() == 0 || activity == null) return;
        try {
            emit("slot.status", new JSONObject().put("slots", slots));
        } catch (JSONException e) {
            Log.e(TAG, "Failed to emit batched slot snapshot", e);
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onSlotSnapshot(SlotSnapshotEvent event) {
        if (event.slots == null || activity == null) return;
        try {
            emit("cabinet.slotsSnapshot", new JSONObject().put("slots", event.slots));
        } catch (JSONException e) {
            Log.e(TAG, "Failed to emit serial snapshot", e);
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onSerialStatus(SerialStatusEvent event) {
        if (event.status == null || activity == null) return;
        emit("serial.statusChanged", event.status);
    }

    // EventBus: 串口数据接收（来自 DeviceCoreService → 串口帧接收）
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onSerialDataReceived(SerialDataReceivedEvent event) {
        if (event.data == null) return;
        if (activity == null) {
            Log.e(TAG, "■ onSerialDataReceived: activity is null, cannot emit!");
            return;
        }
        if ("serialRxRaw".equals(event.data.optString("type"))
                || "poll".equals(event.data.optString("source"))) return;
        emit("serial.dataReceived", event.data);
    }

    /* ==================== 4. 串口通道 ==================== */

    private void handleSerial(String action, JSONObject payload, String requestId) {
        switch (action) {
            case "serial.send":
                Log.d(TAG, "handleSerial: "+payload.toString());
                handleSerialSend(payload, requestId);
                break;
            case "serial.status":
                handleSerialStatus(requestId);
                break;
            case "serial.slotsSnapshot":
                handleSerialSlotsSnapshot(requestId);
                break;
            case "serial.getLogs":
                handleSerialGetLogs(payload, requestId);
                break;
            case "serial.subscribe":
                handleSerialSubscribe(payload, requestId);
                break;
            case "serial.unsubscribe":
                handleSerialUnsubscribe(payload, requestId);
                break;
            case "serial.reconnect":
                handleSerialReconnect(payload, requestId);
                break;
            case "serial.disconnect":
                handleSerialDisconnect(requestId);
                break;
            case "serial.setPolling":
                handleSerialSetPolling(payload, requestId);
                break;
            case "serial.listPorts":
                handleSerialListPorts(requestId);
                break;
            case "serial.readVersion":
                handleSerialReadVersion(payload, requestId);
                break;
            case "serial.firmwareUpgrade":
                handleSerialFirmwareUpgrade(payload, requestId);
                break;
            case "serial.cancelFirmwareUpgrade":
                handleSerialCancelFirmwareUpgrade(requestId);
                break;
            case "serial.openDoor":
                handleSerialOpenDoor(payload, requestId);
                break;
            case "serial.querySlot":
                handleSerialQuerySlot(payload, requestId);
                break;
            case "serial.setLedDutyCycle":
                handleSerialSetLedDutyCycle(payload, requestId);
                break;
            case "serial.openAllDoors":
                handleSerialOpenAllDoors(payload, requestId);
                break;
            default:
                sendError(requestId, "UNKNOWN_ACTION", "Unknown serial action: " + action);
        }
    }

    private void handleSerialSend(JSONObject payload, String requestId) {
        DeviceSerialManager serial = resolveSerialManager();
        if (serial == null) {
            sendError(requestId, "SERIAL_NOT_READY", "Serial manager not available");
            return;
        }
        if (!serial.isOpen()) {
            sendError(requestId, "SERIAL_NOT_CONNECTED", "Serial transport is not connected");
            return;
        }
        String hex = payload.optString("hex", "");
        if (hex.isEmpty()) {
            sendError(requestId, "INVALID_DATA", "hex is required");
            return;
        }
        try {
            serial.send(hex, "HEX");
            sendSuccessJson(requestId, "sent", true);
        } catch (Exception e) {
            sendError(requestId, "SERIAL_SEND_ERROR", e.getMessage());
        }
    }

    private void handleSerialStatus(String requestId) {
        DeviceSerialManager serial = resolveSerialManager();
        if (serial == null) {
            sendError(requestId, "SERIAL_NOT_READY", "Serial manager not available");
            return;
        }
        try {
            sendSuccess(requestId, serial.snapshot());
        } catch (JSONException error) {
            sendError(requestId, "SERIAL_STATUS_ERROR", error.getMessage());
        }
    }

    private void handleSerialSlotsSnapshot(String requestId) {
        DeviceSerialManager serial = resolveSerialManager();
        if (serial == null) {
            sendError(requestId, "SERIAL_NOT_READY", "Serial manager not available");
            return;
        }
        if (!serial.isOpen()) {
            sendError(requestId, "SERIAL_NOT_CONNECTED", "Serial transport is not connected");
            return;
        }
        try {
            Map<Integer, JSONObject> snapshot = new TreeMap<>(
                    serial.getSlotStateManager().getSnapshot());
            JSONArray slots = new JSONArray();
            for (JSONObject slot : snapshot.values()) {
                if (slot != null) slots.put(slot);
            }
            sendSuccess(requestId, new JSONObject()
                    .put("capturedAt", System.currentTimeMillis())
                    .put("slots", slots));
        } catch (Exception error) {
            sendError(requestId, "SERIAL_SNAPSHOT_ERROR", error.getMessage());
        }
    }

    private DeviceSerialManager resolveSerialManager() {
        DeviceSerialManager injected = serialManager;
        return injected != null ? injected : SerialRuntimeRegistry.get();
    }

    private void handleSerialGetLogs(JSONObject payload, String requestId) {
        int count = payload.optInt("count", 100);
        try {
            JSONObject result = new JSONObject();
            JSONArray logs = serialLogBuffer.getLogs(count);
            result.put("logs", logs);
            sendSuccess(requestId, result);
        } catch (Exception e) {
            sendError(requestId, "SERIAL_LOG_ERROR", e.getMessage());
        }
    }

    private void handleSerialSubscribe(JSONObject payload, String requestId) {
        int cmd = payload.optInt("cmd", -1);
        if (cmd < 0) {
            sendError(requestId, "INVALID_CMD", "cmd is required (integer function code)");
            return;
        }
        subscribedSerialCmds.add(cmd);
        try {
            sendSuccess(requestId,
                    new JSONObject().put("subscribed", true).put("cmd", cmd));
        } catch (JSONException ignored) {}
    }

    private void handleSerialUnsubscribe(JSONObject payload, String requestId) {
        int cmd = payload.optInt("cmd", -1);
        if (cmd < 0) {
            sendError(requestId, "INVALID_CMD", "cmd is required (integer function code)");
            return;
        }
        subscribedSerialCmds.remove(cmd);
        try {
            sendSuccess(requestId,
                    new JSONObject().put("unsubscribed", true).put("cmd", cmd));
        } catch (JSONException ignored) {}
    }

    private void handleSerialReconnect(JSONObject payload, String requestId) {
        DeviceSerialManager serial = resolveSerialManager();
        if (serial == null) {
            sendError(requestId, "SERIAL_NOT_READY", "Serial manager not available");
            return;
        }
        try {
            serial.reconnect();
            sendSuccess(requestId, serial.snapshot());
        } catch (Exception e) {
            sendError(requestId, "SERIAL_RECONNECT_ERROR", e.getMessage());
        }
    }

    /* ==================== TTS 通道 ==================== */

    private void handleTts(String action, JSONObject payload, String requestId) {
        if (!"tts.speak".equals(action)) {
            sendError(requestId, "UNKNOWN_ACTION", "Unknown TTS action: " + action);
            return;
        }
        TtsManager.SpeakStatus result = ttsManager.speak(
                payload.optString("text", ""),
                payload.optBoolean("flush", true));
        if (!result.accepted) {
            sendError(requestId, result.errorCode, "TTS speech was not accepted");
            return;
        }
        try {
            JSONObject data = new JSONObject();
            data.put("accepted", true);
            data.put("queuedForInitialization", result.queuedForInitialization);
            data.put("voiceMode", result.voiceMode);
            sendSuccess(requestId, data);
        } catch (JSONException e) {
            sendError(requestId, "TTS_RESPONSE_ERROR", e.getMessage());
        }
    }

    private void handleSerialDisconnect(String requestId) {
        DeviceSerialManager serial = resolveSerialManager();
        if (serial == null) {
            sendError(requestId, "SERIAL_NOT_READY", "Serial manager not available");
            return;
        }
        try {
            sendSuccess(requestId, serial.disconnect());
        } catch (Exception e) {
            sendError(requestId, "SERIAL_DISCONNECT_ERROR", e.getMessage());
        }
    }

    private void handleSerialSetPolling(JSONObject payload, String requestId) {
        DeviceSerialManager serial = resolveSerialManager();
        if (serial == null) {
            sendError(requestId, "SERIAL_NOT_READY", "Serial manager not available");
            return;
        }
        boolean enabled = payload.optBoolean("enabled", true);
        try {
            JSONObject result = serial.setPollingEnabled(enabled);
            sendSuccess(requestId, result);
        } catch (Exception e) {
            sendError(requestId, "SERIAL_POLLING_ERROR", e.getMessage());
        }
    }

    private void handleSerialListPorts(String requestId) {
        try {
            JSONObject result = DeviceSerialManager.listAvailablePorts();
            sendSuccess(requestId, result);
        } catch (Exception e) {
            sendError(requestId, "SERIAL_PORT_LIST_ERROR", e.getMessage());
        }
    }

    private void handleSerialReadVersion(JSONObject payload, String requestId) {
        DeviceSerialManager serial = resolveSerialManager();
        if (serial == null) {
            sendError(requestId, "SERIAL_NOT_READY", "Serial manager not available");
            return;
        }
        if (!serial.isOpen()) {
            sendError(requestId, "SERIAL_NOT_CONNECTED", "Serial transport is not connected");
            return;
        }
        int slotNumber = payload.optInt("slotNumber", 1);
        try {
            JSONObject result = serial.readVersion(slotNumber);
            sendSuccess(requestId, result);
        } catch (Exception e) {
            sendError(requestId, "SERIAL_READ_VERSION_ERROR", e.getMessage());
        }
    }

    private void handleSerialFirmwareUpgrade(JSONObject payload, String requestId) {
        DeviceSerialManager serial = resolveSerialManager();
        if (serial == null) {
            sendError(requestId, "SERIAL_NOT_READY", "Serial manager not available");
            return;
        }
        if (!serial.isOpen()) {
            sendError(requestId, "SERIAL_NOT_CONNECTED", "Serial transport is not connected");
            return;
        }
        final File firmwareFile;
        try {
            firmwareFile = requirePrivateFirmwareFile(payload.optString("filePath", ""));
        } catch (Exception error) {
            sendError(requestId, "INVALID_FIRMWARE_FILE", error.getMessage());
            return;
        }
        String operationId = payload.optString("operationId", "").trim();
        if (operationId.isEmpty()) {
            sendError(requestId, "INVALID_OPERATION_ID", "operationId is required");
            return;
        }
        String firmwareVersion = payload.optString("firmwareVersion", "").trim();
        ioExecutor.execute(() -> {
            try {
                JSONObject result = serial.transferFirmware(firmwareFile, operationId, progress -> {
                    try {
                        progress.put("firmwareVersion", firmwareVersion);
                    } catch (JSONException ignored) { }
                    emit("serial.firmwareProgress", progress);
                });
                result.put("firmwareVersion", firmwareVersion);
                sendSuccess(requestId, result);
            } catch (DeviceSerialManager.FirmwareUpgradeCancelledException error) {
                sendError(requestId, "FIRMWARE_UPGRADE_CANCELLED", error.getMessage());
            } catch (Exception error) {
                sendError(requestId, "FIRMWARE_TRANSFER_ERROR", error.getMessage());
            }
        });
    }

    private void handleSerialCancelFirmwareUpgrade(String requestId) {
        DeviceSerialManager serial = resolveSerialManager();
        if (serial == null) {
            sendError(requestId, "SERIAL_NOT_READY", "Serial manager not available");
            return;
        }
        try {
            sendSuccess(requestId, serial.cancelFirmwareTransfer());
        } catch (JSONException error) {
            sendError(requestId, "FIRMWARE_CANCEL_ERROR", error.getMessage());
        }
    }

    private void handleSerialOpenDoor(JSONObject payload, String requestId) {
        DeviceSerialManager serial = resolveSerialManager();
        if (serial == null) {
            sendError(requestId, "SERIAL_NOT_READY", "Serial manager not available");
            return;
        }
        if (!serial.isOpen()) {
            sendError(requestId, "SERIAL_NOT_CONNECTED", "Serial transport is not connected");
            return;
        }
        int slotNumber = payload.optInt("slotNumber", -1);
        boolean administrator = payload.optBoolean("administrator", true);
        try {
            sendSuccess(requestId, serial.openDoor(slotNumber, administrator));
        } catch (Exception e) {
            sendError(requestId, "SERIAL_OPEN_DOOR_ERROR", e.getMessage());
        }
    }

    private void handleSerialQuerySlot(JSONObject payload, String requestId) {
        DeviceSerialManager serial = resolveSerialManager();
        if (serial == null) {
            sendError(requestId, "SERIAL_NOT_READY", "Serial manager not available");
            return;
        }
        if (!serial.isOpen()) {
            sendError(requestId, "SERIAL_NOT_CONNECTED", "Serial transport is not connected");
            return;
        }
        int slotNumber = payload.optInt("slotNumber", 1);
        try {
            sendSuccess(requestId, serial.querySlot(slotNumber));
        } catch (Exception e) {
            sendError(requestId, "SERIAL_QUERY_SLOT_ERROR", e.getMessage());
        }
    }

    private void handleSerialSetLedDutyCycle(JSONObject payload, String requestId) {
        DeviceSerialManager serial = resolveSerialManager();
        if (serial == null) {
            sendError(requestId, "SERIAL_NOT_READY", "Serial manager not available");
            return;
        }
        if (!serial.isOpen()) {
            sendError(requestId, "SERIAL_NOT_CONNECTED", "Serial transport is not connected");
            return;
        }
        int slotNumber = payload.optInt("slotNumber", -1);
        int dutyCycle = payload.optInt("dutyCycle", -1);
        try {
            sendSuccess(requestId, serial.setLedDutyCycle(slotNumber, dutyCycle));
        } catch (Exception e) {
            sendError(requestId, "SERIAL_LED_ERROR", e.getMessage());
        }
    }

    private void handleSerialOpenAllDoors(JSONObject payload, String requestId) {
        DeviceSerialManager serial = resolveSerialManager();
        if (serial == null) {
            sendError(requestId, "SERIAL_NOT_READY", "Serial manager not available");
            return;
        }
        if (!serial.isOpen()) {
            sendError(requestId, "SERIAL_NOT_CONNECTED", "Serial transport is not connected");
            return;
        }
        boolean administrator = payload.optBoolean("administrator", true);
        try {
            sendSuccess(requestId, serial.openAllDoors(administrator));
        } catch (Exception e) {
            sendError(requestId, "SERIAL_OPEN_ALL_DOORS_ERROR", e.getMessage());
        }
    }

    /** Called by serial listener to buffer log and forward to Vue */
    public void onSerialData(int cmd, String hex, String text, long timestamp) {
        serialLogBuffer.add(cmd, hex, text, timestamp);

        // Log event (always)
        try {
            JSONObject logEntry = new JSONObject();
            logEntry.put("type", "frame");
            logEntry.put("cmd", cmd);
            logEntry.put("timestamp", timestamp);
            logEntry.put("hex", hex);
            logEntry.put("text", text);
            emit("serial.log", logEntry);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to build serial log event", e);
        }

        // Subscribed frames
        if (subscribedSerialCmds.contains(cmd)) {
            try {
                JSONObject frame = new JSONObject();
                frame.put("cmd", cmd);
                frame.put("timestamp", timestamp);
                frame.put("data", text);
                emit("serial.frame", frame);
            } catch (JSONException e) {
                Log.e(TAG, "Failed to build serial frame event", e);
            }
        }
    }

    /* ==================== 5. SQLite 持久化 ==================== */

    private SQLiteDatabase getDb() {
        if (db == null) {
            synchronized (this) {
                if (db == null) {
                    try {
                        db = context.openOrCreateDatabase("card_vue.db",
                                Context.MODE_PRIVATE, null);
                        db.enableWriteAheadLogging();
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to open database", e);
                    }
                }
            }
        }
        return db;
    }

    private void handleStorage(String action, JSONObject payload, String requestId) {
        switch (action) {
            case "storage.query":
                handleStorageQuery(payload, requestId);
                break;
            case "storage.execute":
                handleStorageExecute(payload, requestId);
                break;
            default:
                sendError(requestId, "UNKNOWN_ACTION", "Unknown storage action: " + action);
        }
    }

    private void handleStorageQuery(JSONObject payload, String requestId) {
        Log.i(TAG, "storage.query queued requestId=" + requestId);
        storageExecutor.execute(() -> {
            Log.i(TAG, "storage.query started requestId=" + requestId);
            SQLiteDatabase database = getDb();
            if (database == null) {
                Log.w(TAG, "storage.query database unavailable requestId=" + requestId);
                sendError(requestId, "DB_ERROR", "Failed to open database");
                return;
            }
            Cursor cursor = null;
            try {
                String sql = payload.getString("sql");
                JSONArray paramsArray = payload.optJSONArray("params");
                String[] params = toParamsArray(paramsArray);

                cursor = database.rawQuery(sql, params);
                JSONArray rows = new JSONArray();
                String[] columnNames = cursor.getColumnNames();
                while (cursor.moveToNext()) {
                    JSONObject row = new JSONObject();
                    for (String col : columnNames) {
                        int index = cursor.getColumnIndex(col);
                        switch (cursor.getType(index)) {
                            case Cursor.FIELD_TYPE_NULL:
                                row.put(col, JSONObject.NULL);
                                break;
                            case Cursor.FIELD_TYPE_INTEGER:
                                row.put(col, cursor.getLong(index));
                                break;
                            case Cursor.FIELD_TYPE_FLOAT:
                                row.put(col, cursor.getDouble(index));
                                break;
                            case Cursor.FIELD_TYPE_STRING:
                                row.put(col, cursor.getString(index));
                                break;
                            case Cursor.FIELD_TYPE_BLOB:
                                row.put(col, "BLOB");
                                break;
                        }
                    }
                    rows.put(row);
                }
                JSONObject result = new JSONObject();
                result.put("rows", rows);
                result.put("count", rows.length());
                sendSuccess(requestId, result);
                Log.i(TAG, "storage.query completed requestId=" + requestId + " rowCount=" + rows.length());
            } catch (Exception e) {
                Log.w(TAG, "storage.query failed requestId=" + requestId
                        + " error=" + e.getClass().getSimpleName());
                sendError(requestId, "SQL_ERROR", e.getMessage());
            } finally {
                if (cursor != null) try { cursor.close(); } catch (Exception ignored) {}
            }
        });
    }

    private void handleStorageExecute(JSONObject payload, String requestId) {
        Log.i(TAG, "storage.execute queued requestId=" + requestId);
        storageExecutor.execute(() -> {
            Log.i(TAG, "storage.execute started requestId=" + requestId);
            SQLiteDatabase database = getDb();
            if (database == null) {
                Log.w(TAG, "storage.execute database unavailable requestId=" + requestId);
                sendError(requestId, "DB_ERROR", "Failed to open database");
                return;
            }
            try {
                String sql = payload.getString("sql");
                JSONArray paramsArray = payload.optJSONArray("params");
                Object[] bindArgs = toBindArgs(paramsArray);

                database.beginTransaction();
                try {
                    if (bindArgs == null) {
                        database.execSQL(sql);
                    } else {
                        database.execSQL(sql, bindArgs);
                    }
                    database.setTransactionSuccessful();
                    JSONObject result = new JSONObject();
                    result.put("affectedRows", 1);
                    sendSuccess(requestId, result);
                    Log.i(TAG, "storage.execute completed requestId=" + requestId);
                } finally {
                    database.endTransaction();
                }
            } catch (Exception e) {
                Log.w(TAG, "storage.execute failed requestId=" + requestId
                        + " error=" + e.getClass().getSimpleName());
                sendError(requestId, "SQL_EXECUTE_ERROR", e.getMessage());
            }
        });
    }

    private String[] toParamsArray(JSONArray paramsArray) {
        if (paramsArray == null || paramsArray.length() == 0) return null;
        String[] result = new String[paramsArray.length()];
        for (int i = 0; i < paramsArray.length(); i++) {
            result[i] = paramsArray.optString(i, "");
        }
        return result;
    }

    private Object[] toBindArgs(JSONArray paramsArray) {
        if (paramsArray == null || paramsArray.length() == 0) return null;
        Object[] result = new Object[paramsArray.length()];
        for (int i = 0; i < paramsArray.length(); i++) {
            Object val = paramsArray.opt(i);
            if (val == null || val == JSONObject.NULL) {
                result[i] = null;
            } else {
                result[i] = val.toString();
            }
        }
        return result;
    }

    /* ==================== 6. 人脸识别 ==================== */

    private void handleFace(String action, JSONObject payload, String requestId) {
        switch (action) {
            case "face.recognition.start":
                handleFaceRecognitionStart(payload, requestId);
                break;
            case "face.recognition.cancel":
                handleFaceRecognitionCancel(payload, requestId);
                break;
            case "face.enrollment.start":
                handleFaceEnrollmentStart(payload, requestId);
                break;
            case "face.enrollment.cancel":
                handleFaceEnrollmentCancel(payload, requestId);
                break;
            case "face.camera.config":
                handleFaceCameraConfig(payload, requestId);
                break;
            case "face.count":
                handleFaceCount(requestId);
                break;
            case "face.template.import":
                handleFaceTemplateImport(payload, requestId);
                break;
            case "face.template.remove":
                handleFaceTemplateRemove(payload, requestId);
                break;
            default:
                sendError(requestId, "UNKNOWN_ACTION", "Unknown face action: " + action);
        }
    }

    private void handleFaceRecognitionStart(JSONObject payload, String requestId) {
        if (!faceActive.compareAndSet(false, true)) {
            sendError(requestId, "FACE_BUSY", "已有正在进行的人脸操作");
            return;
        }
        clearEnrollmentPhoto();
        float threshold = (float) payload.optDouble("threshold", 0.8);
        String cameraFacing = normalizeCameraFacing(payload.optString("cameraFacing", "front"));
        boolean cameraMirror = payload.has("cameraMirror")
                ? payload.optBoolean("cameraMirror", "front".equals(cameraFacing))
                : "front".equals(cameraFacing);
        int cameraRotation = normalizeCameraRotation(payload.optInt("cameraRotation", 0));
        int cameraFrameWidth = Math.max(1, payload.optInt("cameraFrameWidth", 640));
        int cameraFrameHeight = Math.max(1, payload.optInt("cameraFrameHeight", 480));
        int faceRecognitionTimeout = positiveInt(payload.optInt("faceRecognitionTimeout", 30000), 30000);
        int searchTimeout = positiveInt(payload.optInt("searchTimeout", 15000), 15000);
        int searchIntervalTime = positiveInt(payload.optInt("searchIntervalTime", 3000), 3000);
        boolean needFaceLiveness = payload.optBoolean("needFaceLiveness", false);
        int captureTimeout = positiveInt(payload.optInt("captureTimeout", 8000), 8000);
        currentFaceAction = "recognition";
        int faceLibraryCount = FaceAiManager.getInstance().getFaceCount();
        Log.i(TAG, "进入人脸识别 — 当前FaceAI人脸库数量: " + faceLibraryCount);
        if (faceLibraryCount <= 0) {
            faceActive.set(false);
            currentFaceAction = null;
            sendError(requestId, "FACE_LIBRARY_EMPTY",
                    "人脸库中没有已录入的人脸，请先录入人脸后再尝试取卡");
            return;
        }
        sendSuccessJson(requestId, "accepted", true);

        // 必须先显示 face overlay（使 TextureView 的 SurfaceTexture 就绪），
        // 再配置摄像头参数（configureFaceCamera 可能触发 unbindAll + bindToLifecycle），
        // 否则 Preview 在 Surface 不可用时绑定会导致视频预览黑屏。
        activity.runOnUiThread(() -> {
            activity.showFaceContainer(null, false, this::cancelCurrentFaceOperation);
            activity.configureFaceCamera(cameraFacing, cameraMirror, cameraRotation,
                    cameraFrameWidth, cameraFrameHeight);
            currentFaceController = new FaceEnrollmentController(
                    activity,
                    false, // recognition mode
                    "", "",
                    activity.getTvFaceStatus(),
                    activity.getTvFaceCountdown(),
                    activity.getBtnFaceCapture(),
                    activity.getBtnFaceCancel(),
                    createFaceCallback(),
                    createFeatureExtractor(),
                    threshold,
                    cameraMirror,
                    cameraRotation,
                    needFaceLiveness,
                    searchIntervalTime,

                    searchTimeout,
                    captureTimeout
            );
            currentFaceController.setFrontCamera(
                    FaceEnrollmentController.isFrontCameraFacing(cameraFacing));
            currentFaceController.start();

            // 整体识别超时由 config.faceRecognitionTimeout 控制。
            cancelFaceTimeout();
            faceTimeoutHandler = new Handler(Looper.getMainLooper());
            faceTimeoutRunnable = () -> {
                if (faceActive.get()) {
                    Log.w(TAG, "Face recognition timeout – no match found, cancelling");
                    cancelCurrentFaceOperation();
                    emit("face.recognition.timeout", new JSONObject());
                }
            };
            faceTimeoutHandler.postDelayed(faceTimeoutRunnable, faceRecognitionTimeout);
        });
    }

    private void handleFaceEnrollmentStart(JSONObject payload, String requestId) {
        if (!faceActive.compareAndSet(false, true)) {
            sendError(requestId, "FACE_BUSY", "已有正在进行的人脸操作");
            return;
        }
        clearEnrollmentPhoto();
        String faceId = payload.optString("faceId", "");
        if (faceId.isEmpty()) {
            faceActive.set(false);
            sendError(requestId, "INVALID_FACE_ID", "faceId is required");
            return;
        }
        String cameraFacing = normalizeCameraFacing(payload.optString("cameraFacing", "front"));
        boolean cameraMirror = payload.has("cameraMirror")
                ? payload.optBoolean("cameraMirror", "front".equals(cameraFacing))
                : "front".equals(cameraFacing);
        int cameraRotation = normalizeCameraRotation(payload.optInt("cameraRotation", 0));
        int cameraFrameWidth = Math.max(1, payload.optInt("cameraFrameWidth", 640));
        int cameraFrameHeight = Math.max(1, payload.optInt("cameraFrameHeight", 480));
        int captureTimeout = positiveInt(payload.optInt("captureTimeout", 8000), 8000);
        currentFaceAction = "enrollment";
        sendSuccessJson(requestId, "accepted", true);
        Log.i(TAG, "进入人脸录入 — 当前FaceAI人脸库数量: " + FaceAiManager.getInstance().getFaceCount());

        String faceIdCapture = faceId;
        // 必须先显示 face overlay（使 TextureView 的 SurfaceTexture 就绪），
        // 再配置摄像头参数（configureFaceCamera 可能触发 unbindAll + bindToLifecycle），
        // 否则 Preview 在 Surface 不可用时绑定会导致视频预览黑屏。
        activity.runOnUiThread(() -> {
            activity.showFaceContainer(faceIdCapture, true, this::cancelCurrentFaceOperation);
            activity.configureFaceCamera(cameraFacing, cameraMirror, cameraRotation,
                    cameraFrameWidth, cameraFrameHeight);
            currentFaceController = new FaceEnrollmentController(
                    activity,
                    true, // enrollment mode
                    faceIdCapture,
                    "",
                    activity.getTvFaceStatus(),
                    activity.getTvFaceCountdown(),
                    activity.getBtnFaceCapture(),
                    activity.getBtnFaceCancel(),
                    createFaceCallback(),
                    createFeatureExtractor(),
                    0.8f,
                    cameraMirror,
                    cameraRotation,
                    false,
                    3000,
                    15000,
                    captureTimeout
            );
            currentFaceController.setFrontCamera(
                    FaceEnrollmentController.isFrontCameraFacing(cameraFacing));
            currentFaceController.start();

            // 录入超时兜底（60s，给用户充足时间）
            cancelFaceTimeout();
            faceTimeoutHandler = new Handler(Looper.getMainLooper());
            faceTimeoutRunnable = () -> {
                if (faceActive.get()) {
                    Log.w(TAG, "Face enrollment timeout, cancelling");
                    cancelCurrentFaceOperation();
                    emit("face.enrollment.timeout", new JSONObject());
                }
            };
            faceTimeoutHandler.postDelayed(faceTimeoutRunnable, 60_000);
        });
    }

    private static String normalizeCameraFacing(String value) {
        return "back".equalsIgnoreCase(value) ? "back" : "front";
    }

    private static int positiveInt(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private static int normalizeCameraRotation(int value) {
        return (value == 90 || value == 180 || value == 270) ? value : 0;
    }

    private void handleFaceRecognitionCancel(JSONObject payload, String requestId) {
        cancelCurrentFaceOperation();
        sendSuccessJson(requestId, "cancelled", true);
    }

    private void handleFaceEnrollmentCancel(JSONObject payload, String requestId) {
        cancelCurrentFaceOperation();
        sendSuccessJson(requestId, "cancelled", true);
    }

    private void handleFaceCameraConfig(JSONObject payload, String requestId) {
        try {
            String cameraFacing = payload.optString("cameraFacing", "front");
            boolean cameraMirror = payload.optBoolean("cameraMirror", false);
            int cameraRotation = payload.optInt("cameraRotation", 0);
            int cameraFrameWidth = payload.optInt("cameraFrameWidth", 640);
            int cameraFrameHeight = payload.optInt("cameraFrameHeight", 480);

            // CameraX 层镜头切换（facing / 分辨率）
            activity.configureFaceCamera(cameraFacing, cameraMirror, cameraRotation,
                    cameraFrameWidth, cameraFrameHeight);

            // 当前控制器内的帧变换参数
            if (currentFaceController != null) {
                currentFaceController.applyCameraConfig(cameraRotation, cameraMirror);
                currentFaceController.setFrontCamera(
                        FaceEnrollmentController.isFrontCameraFacing(cameraFacing));
            }

            sendSuccessJson(requestId, "cameraConfigured", true);
        } catch (Exception e) {
            Log.w(TAG, "Failed to apply camera config", e);
            sendError(requestId, "CAMERA_CONFIG_ERROR",
                    "应用摄像头配置失败: " + (e.getMessage() != null ? e.getMessage() : ""));
        }
    }

    private void handleFaceCount(String requestId) {
        try {
            int count = FaceAiManager.getInstance().getFaceCount();
            sendSuccessJson(requestId, "count", count);
        } catch (Exception e) {
            Log.w(TAG, "Failed to get face count", e);
            sendSuccessJson(requestId, "count", -1);
        }
    }

    private void handleFaceTemplateImport(JSONObject payload, String requestId) {
        if (faceActive.get()) {
            sendError(requestId, "FACE_BUSY", "已有正在进行的人脸操作");
            return;
        }
        ioExecutor.execute(() -> {
            try {
                String faceId = payload.optString("faceId", "").trim();
                if (faceId.isEmpty()) {
                    throw new IllegalArgumentException("faceId is required");
                }
                String faceFeature = payload.optString("faceFeature", "").trim();
                String sourceUrl = payload.optString("sourceUrl", "").trim();
                FaceAiManager manager = FaceAiManager.getInstance();

                if (faceFeature.isEmpty()) {
                    byte[] imageBytes = decodeBase64Payload(payload.optString("imageBase64", ""));
                    Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
                    if (bitmap == null) {
                        throw new IllegalArgumentException("face image cannot be decoded");
                    }
                    try {
                        faceFeature = manager.extractFaceFeature(bitmap);
                    } finally {
                        bitmap.recycle();
                    }
                }

                JSONObject enrollResult = manager.enrollFeature(faceId, faceId, faceFeature, sourceUrl);
                boolean enrolled = enrollResult != null && enrollResult.optBoolean("success", false);
                int faceCount = manager.getFaceCount();
                Log.i(TAG, "face.template.import faceId=" + faceId + " enrolled=" + enrolled
                        + " libraryCount=" + faceCount);
                if (!enrolled) {
                    Log.w(TAG, "FaceAiManager.enrollFeature returned success=false for faceId=" + faceId);
                }
                JSONObject result = new JSONObject();
                result.put("faceId", faceId);
                result.put("faceFeature", faceFeature);
                result.put("imported", enrolled);
                result.put("count", faceCount);
                sendSuccess(requestId, result);
            } catch (Exception e) {
                Log.e(TAG, "Failed to import face template", e);
                sendError(requestId, "FACE_TEMPLATE_IMPORT_FAILED", e.getMessage());
            }
        });
    }

    private void handleFaceTemplateRemove(JSONObject payload, String requestId) {
        if (faceActive.get()) {
            sendError(requestId, "FACE_BUSY", "已有正在进行的人脸操作");
            return;
        }
        ioExecutor.execute(() -> {
            try {
                String faceId = payload.optString("faceId", "").trim();
                if (faceId.isEmpty()) {
                    throw new IllegalArgumentException("faceId is required");
                }
                boolean removed = FaceAiManager.getInstance().deleteTemplate(faceId);
                sendSuccessJson(requestId, "removed", removed);
            } catch (Exception e) {
                Log.e(TAG, "Failed to remove face template", e);
                sendError(requestId, "FACE_TEMPLATE_REMOVE_FAILED", e.getMessage());
            }
        });
    }

    private void cancelFaceTimeout() {
        if (faceTimeoutHandler != null && faceTimeoutRunnable != null) {
            faceTimeoutHandler.removeCallbacks(faceTimeoutRunnable);
        }
        faceTimeoutHandler = null;
        faceTimeoutRunnable = null;
    }

    private void cancelCurrentFaceOperation() {
        cancelFaceTimeout();
        FaceEnrollmentController controller = currentFaceController;
        if (controller != null) {
            controller.stop();
            currentFaceController = null;
        }
        faceActive.set(false);
        currentFaceAction = null;
        clearEnrollmentPhoto();
        activity.hideFaceContainer();
    }

    /** Activity 切到后台时终止明确发起的人脸会话，避免返回应用后遗留相机覆盖层。 */
    public void cancelFaceOperationForActivityPause() {
        String action = currentFaceAction;
        boolean hadActiveSession = faceActive.get() || currentFaceController != null;
        cancelCurrentFaceOperation();
        if (!hadActiveSession || action == null) return;
        try {
            JSONObject data = new JSONObject();
            data.put("reason", "activity_background");
            emit("face." + action + ".cancelled", data);
        } catch (JSONException ignored) { }
    }

    /**
     * FaceAISDK 搜索回调返回的是 SDK 内部索引（如 "3_0"），但业务层以注册/同步时
     * 传入的 faceID 作为唯一标识。通过 FaceSearchFeatureManger 把内部索引反查回
     * 存储的 faceID，保证 Vue 能根据 faceId 找到员工。
     */
    private String resolveFaceSearchIndex(String searchIndex) {
        if (searchIndex == null || searchIndex.isEmpty()) {
            return searchIndex;
        }
        try {
            FaceSearchFeature feature = FaceSearchFeatureManger.getInstance(context)
                    .queryFaceFeatureByID(searchIndex);
            if (feature != null && feature.getFaceID() != null && !feature.getFaceID().isEmpty()) {
                String storedFaceId = feature.getFaceID();
                if (!storedFaceId.equals(searchIndex)) {
                    Log.i(TAG, "Resolved face search index " + searchIndex + " to stored faceId " + storedFaceId);
                }
                return storedFaceId;
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to resolve face search index " + searchIndex + ", using raw value", e);
        }
        return searchIndex;
    }

    private FaceEnrollmentController.FaceResultCallback createFaceCallback() {
        return new FaceEnrollmentController.FaceResultCallback() {
            @Override
            public void onFaceEnrolled(String faceId, String faceFeature, float score) {
                String photoBase64 = enrollmentPhotoBase64;
                int photoSize = enrollmentPhotoSize;
                clearEnrollmentPhoto();
                // 必须先 stop controller（清理 CameraX Analyzer + FaceSearchEngine），再置 null
                FaceEnrollmentController controller = currentFaceController;
                if (controller != null) {
                    controller.stop();
                }
                cancelFaceTimeout();
                currentFaceController = null;
                faceActive.set(false);
                currentFaceAction = null;
                activity.hideFaceContainer();

                if (photoBase64 == null || photoBase64.isEmpty() || photoSize <= 0) {
                    emitFaceEnrollmentFailure("FACE_IMAGE_MISSING", "未获取到录入照片");
                    return;
                }
                try {
                    FaceAiManager.getInstance().enrollFeature(faceId, faceId, faceFeature, "");
                } catch (Exception e) {
                    Log.e(TAG, "Failed to save enrolled face template", e);
                    emitFaceEnrollmentFailure("FACE_TEMPLATE_SAVE_FAILED", e.getMessage());
                    return;
                }
                try {
                    JSONObject data = new JSONObject();
                    data.put("faceId", faceId);
                    data.put("faceFeature", faceFeature != null ? faceFeature : "");
                    data.put("score", (double) score);
                    data.put("faceImageBase64", photoBase64);
                    data.put("faceImageSize", photoSize);
                    data.put("faceImageMimeType", FACE_IMAGE_MIME_TYPE);
                    emit("face.enrolled", data);
                } catch (JSONException ignored) {}
            }

            @Override
            public void onFaceVerified(String faceId, float score) {
                String resolvedFaceId = resolveFaceSearchIndex(faceId);
                cancelCurrentFaceOperation();
                try {
                    JSONObject data = new JSONObject();
                    data.put("faceId", resolvedFaceId);
                    data.put("score", (double) score);
                    emit("face.recognized", data);
                } catch (JSONException ignored) {}
            }

            @Override
            public void onCancelled() {
                // 必须先 stop controller（清理 CameraX Analyzer + FaceSearchEngine），再置 null
                FaceEnrollmentController controller = currentFaceController;
                if (controller != null) {
                    controller.stop();
                }
                cancelFaceTimeout();
                String action = currentFaceAction;
                currentFaceController = null;
                faceActive.set(false);
                currentFaceAction = null;
                clearEnrollmentPhoto();
                activity.hideFaceContainer();
                emit(action != null ? "face." + action + ".cancelled" : "face.cancelled",
                        new JSONObject());
            }
        };
    }

    private FaceFeatureExtractor createFeatureExtractor() {
        return bitmap -> {
            try {
                if ("enrollment".equals(currentFaceAction)) {
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) {
                        throw new IllegalStateException("failed to encode enrollment image");
                    }
                    byte[] imageBytes = output.toByteArray();
                    if (imageBytes.length == 0 || imageBytes.length > MAX_FACE_IMAGE_BYTES) {
                        throw new IllegalArgumentException("face image must not exceed 10 MB");
                    }
                    enrollmentPhotoBase64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP);
                    enrollmentPhotoSize = imageBytes.length;
                }
                return FaceAiManager.getInstance().extractFaceFeature(bitmap);
            } catch (Exception e) {
                Log.e(TAG, "Failed to extract face feature", e);
                clearEnrollmentPhoto();
                return null;
            }
        };
    }

    private void emitFaceEnrollmentFailure(String code, String message) {
        try {
            JSONObject data = new JSONObject();
            data.put("code", code);
            data.put("message", message == null ? "人脸录入失败" : message);
            emit("face.enrollment.failed", data);
        } catch (JSONException ignored) {}
    }

    private void clearEnrollmentPhoto() {
        enrollmentPhotoBase64 = null;
        enrollmentPhotoSize = 0;
    }

    private static byte[] decodeBase64Payload(String rawValue) {
        String value = rawValue == null ? "" : rawValue.trim();
        int comma = value.indexOf(',');
        if (value.startsWith("data:") && comma >= 0) {
            value = value.substring(comma + 1);
        }
        if (value.isEmpty()) {
            throw new IllegalArgumentException("base64 payload is required");
        }
        byte[] decoded;
        try {
            decoded = Base64.decode(value, Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid base64 payload", e);
        }
        if (decoded.length == 0 || decoded.length > MAX_FACE_IMAGE_BYTES) {
            throw new IllegalArgumentException("face image must not exceed 10 MB");
        }
        return decoded;
    }

    /* ==================== 6b. 系统指纹认证 ==================== */

    private void handleFingerprint(String action, JSONObject payload, String requestId) {
        switch (action) {
            case "fingerprint.getStatus":
                try {
                    sendSuccess(requestId, activity.fingerprintStatus());
                } catch (JSONException e) {
                    sendError(requestId, "FINGERPRINT_STATUS_ERROR", e.getMessage());
                }
                break;
            case "fingerprint.verify":
                activity.startFingerprintAuthentication(requestId, payload, false);
                break;
            case "fingerprint.enroll":
                activity.startFingerprintAuthentication(requestId, payload, true);
                break;
            case "fingerprint.cancel":
                activity.cancelFingerprintAuthentication();
                sendSuccessJson(requestId, "cancelled", true);
                break;
            default:
                sendError(requestId, "UNKNOWN_ACTION", "Unknown fingerprint action: " + action);
        }
    }

    /* ==================== Vue 通信 ==================== */

    private void sendSuccess(String requestId, JSONObject data) {
        if (activity == null || activity.isFinishing()) {
            Log.w(TAG, "sendSuccess skipped: activity=" + activity + " finishing=" + (activity != null && activity.isFinishing()));
            return;
        }
        try {
            JSONObject response = new JSONObject();
            response.put("type", "response");
            if (requestId != null && !requestId.isEmpty()) {
                response.put("requestId", requestId);
            }
            response.put("success", true);
            response.put("data", data != null ? data : new JSONObject());
//            Log.d(TAG, "← sendSuccess: requestId=" + requestId + " data=" + (data != null ? data.toString() : "null"));
            activity.runOnUiThread(() -> activity.sendBridgeResponse(response));
        } catch (JSONException e) {
            Log.e(TAG, "Failed to build success response", e);
        }
    }

    /** Convenience: send success with a single key-value pair. */
    private void sendSuccessJson(String requestId, String key, Object value) {
        try {
            JSONObject data = new JSONObject();
            data.put(key, value);
            sendSuccess(requestId, data);
        } catch (JSONException e) {
            Log.e(TAG, "sendSuccessJson failed", e);
        }
    }

    private void sendError(String requestId, String code, String message) {
        if (activity == null || activity.isFinishing()) return;
        try {
            JSONObject response = new JSONObject();
            response.put("type", "response");
            if (requestId != null && !requestId.isEmpty()) {
                response.put("requestId", requestId);
            }
            response.put("success", false);
            response.put("code", code != null ? code : "ERROR");
            response.put("message", message != null ? message : "");
            Log.d(TAG, "← sendError: requestId=" + requestId + " code=" + code + " msg=" + message);
            activity.runOnUiThread(() -> activity.sendBridgeResponse(response));
        } catch (JSONException e) {
            Log.e(TAG, "Failed to build error response", e);
        }
    }

    private void emit(String eventName, JSONObject data) {
        if (activity == null || activity.isFinishing()) return;
        activity.runOnUiThread(() -> activity.sendBridgeEvent(eventName, data));
    }

    /** 推送 device.info 事件到 Vue（设备号 + 激活状态 + MQTT 在线状态） */
    private void emitDeviceInfo(String overrideDeviceCode) {
        try {
            JSONObject data = new JSONObject();

            // 设备号：优先用传入值，否则从持久化存储读取
            String deviceCode = overrideDeviceCode;
            if (deviceCode == null || deviceCode.isEmpty()) {
                try {
                    CredentialStore store = new CredentialStore(context);
                    deviceCode = store.getDeviceCode();
                } catch (Exception e) {
                    Log.w(TAG, "emitDeviceInfo: failed to read deviceCode", e);
                }
            }
            data.put("deviceCode", deviceCode != null ? deviceCode : "");

            // 激活状态
            boolean activated = false;
            if (bootstrapManager != null) {
                try {
                    activated = bootstrapManager.isBootstrapComplete();
                } catch (Exception e) { /* ignore */ }
            }
            data.put("activated", activated);

            // MQTT 在线状态
            boolean mqttConnected = false;
            if (mqttClient != null) {
                try {
                    mqttConnected = mqttClient.isConnected()
                            && bootstrapManager != null && bootstrapManager.isMqttAuthenticated();
                } catch (Exception e) { /* ignore */ }
            }
            data.put("mqttConnected", mqttConnected);

            data.put("timestamp", System.currentTimeMillis());
            emit("device.info", data);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to build device.info event", e);
        }
    }

    /* ==================== 串口日志环形缓冲 ==================== */

    private static class SerialLogRingBuffer {
        private final int capacity;
        private final JSONObject[] buffer;
        private int writeIdx = 0;
        private int count = 0;

        SerialLogRingBuffer(int capacity) {
            this.capacity = capacity;
            this.buffer = new JSONObject[capacity];
        }

        synchronized void add(int cmd, String hex, String text, long timestamp) {
            try {
                JSONObject entry = new JSONObject();
                entry.put("type", "frame");
                entry.put("cmd", cmd);
                entry.put("timestamp", timestamp);
                entry.put("hex", hex != null ? hex : "");
                entry.put("text", text != null ? text : "");
                buffer[writeIdx] = entry;
                writeIdx = (writeIdx + 1) % capacity;
                if (count < capacity) count++;
            } catch (JSONException ignored) {}
        }

        synchronized JSONArray getLogs(int limit) {
            JSONArray result = new JSONArray();
            int actual = Math.min(limit, count);
            if (actual <= 0) return result;
            int startIdx = count < capacity ? 0 : writeIdx;
            for (int i = 0; i < actual; i++) {
                int idx = (startIdx + i) % capacity;
                if (buffer[idx] != null) {
                    result.put(buffer[idx]);
                }
            }
            return result;
        }
    }

    /* ==================== 生命周期 ==================== */

    public void close() {
        // Cancel face operations
        cancelCurrentFaceOperation();

        slotEventHandler.removeCallbacks(flushSlotEventsRunnable);
        synchronized (pendingSlotEventsLock) {
            pendingSlotEvents.clear();
            slotEventFlushScheduled = false;
        }

        ttsManager.close();

        // Shutdown bootstrap
        shutdownBootstrap();

        // Unregister EventBus
        try {
            EventBus.getDefault().unregister(this);
        } catch (Exception ignored) {}

        // Close database
        synchronized (this) {
            if (db != null) {
                try { db.close(); } catch (Exception ignored) {}
                db = null;
            }
        }

        // Shutdown executor
        ioExecutor.shutdown();
        try {
            ioExecutor.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            ioExecutor.shutdownNow();
        }
        storageExecutor.shutdown();
        try {
            storageExecutor.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            storageExecutor.shutdownNow();
        }

        // Clear state
        AppLog.setMqttClient(null);
        mqttRegisteredCmds.clear();
        subscribedSerialCmds.clear();
    }

    private void shutdownBootstrap() {
        if (bootstrapThread != null && bootstrapThread.isAlive()) {
            bootstrapThread.interrupt();
            try { bootstrapThread.join(500); } catch (InterruptedException ignored) {}
        }
        if (bootstrapManager != null) {
            try { bootstrapManager.shutdown(); } catch (Exception ignored) {}
            bootstrapManager = null;
        }
        bootstrapThread = null;
        bootstrapInProgress.set(false);
    }
}
