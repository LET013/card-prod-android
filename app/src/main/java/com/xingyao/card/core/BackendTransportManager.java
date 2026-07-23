package com.xingyao.card.core;

import android.util.Base64;

import com.xingyao.card.BuildConfig;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** MQTT/HTTP/legacy-TCP transport only. All credentials and settings are injected. */
public final class BackendTransportManager {
    public interface Listener {
        void onStatusChanged(JSONObject status);
        void onCommand(JSONObject command);
        void onMessage(JSONObject message);
        void onRuntimeToken(String token);
    }

    private static final long HEARTBEAT_INTERVAL_MS = 30000L;
    private static final long RECONNECT_DELAY_MS = 5000L;

    private final BackendHttpGateway httpGateway;
    private final Listener listener;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();

    private ScheduledFuture<?> reconnectTask;
    private ScheduledFuture<?> heartbeatTask;
    private MqttAsyncClient mqttClient;
    private Socket tcpSocket;
    private BufferedOutputStream tcpOutput;
    private volatile boolean running;
    private volatile boolean connecting;
    private volatile String state = "DISCONNECTED";
    private volatile String message = "后端通信未启动";
    private volatile String lastError = "";
    private String transportMode = BackendEndpointSettings.MODE_MQTT;
    private String deviceCode = "";
    private String clientId = "";
    private String brokerUri = "";
    private String mqttUsername = "";
    private String mqttPassword = "";
    private String signingKey = "";
    private String commandTopic = "";
    private String responseTopic = "";
    private String eventTopic = "";
    private String heartbeatTopic = "";
    private String tcpHost = "";
    private int tcpPort;
    private long sentMessages;
    private long receivedMessages;
    private long lastConnectedAt;
    private long authenticatedAt;
    private long lastMessageAt;
    private long heartbeatSequence;

    public BackendTransportManager(BackendHttpGateway httpGateway, Listener listener) {
        if (httpGateway == null) throw new IllegalArgumentException("httpGateway is required");
        this.httpGateway = httpGateway;
        this.listener = listener;
    }

    public synchronized void configure(JSONObject rawSettings) {
        JSONObject settings;
        try { settings = BackendEndpointSettings.normalize(rawSettings); }
        catch (JSONException error) { settings = rawSettings == null ? new JSONObject() : rawSettings; }
        deviceCode = settings.optString("deviceCode", "").trim();
        String mode = settings.optString("backendTransport", BackendEndpointSettings.MODE_MQTT)
                .trim().toUpperCase(Locale.US);
        transportMode = BackendEndpointSettings.MODE_HTTP.equals(mode)
                ? BackendEndpointSettings.MODE_HTTP
                : BackendEndpointSettings.MODE_TCP.equals(mode)
                ? BackendEndpointSettings.MODE_TCP : BackendEndpointSettings.MODE_MQTT;
        clientId = settings.optString("clientId", settings.optString("mqttClientId", "")).trim();
        mqttUsername = settings.optString("mqttUsername", "").trim();
        mqttPassword = settings.optString("mqttPassword", "");
        signingKey = settings.optString("signingKey", "");
        brokerUri = BackendEndpointSettings.mqttBrokerUrl(settings);
        tcpHost = BackendEndpointSettings.tcpHost(settings);
        tcpPort = settings.optInt("tcpPort", 0);
        commandTopic = deviceCode.isEmpty() ? "" : "card/" + deviceCode + "/down";
        responseTopic = deviceCode.isEmpty() ? "" : "card/" + deviceCode + "/down/response";
        eventTopic = deviceCode.isEmpty() ? "" : "card/" + deviceCode + "/up";
        heartbeatTopic = deviceCode.isEmpty() ? "" : "card/" + deviceCode + "/heartbeat";
        if (running) reconnectNow();
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        reconnectNow();
    }

    public synchronized void stop() {
        running = false;
        cancelReconnect();
        stopHeartbeat();
        closeTransports();
        executor.shutdownNow();
        heartbeatExecutor.shutdownNow();
        updateState("DISCONNECTED", "后端通信已停止", null);
    }

    public synchronized JSONObject snapshot() throws JSONException {
        return new JSONObject().put("state", state).put("message", message)
                .put("transportMode", transportMode)
                .put("deviceCode", deviceCode).put("clientId", clientId)
                .put("brokerUri", brokerUri).put("tcpHost", tcpHost).put("tcpPort", tcpPort)
                .put("commandTopic", commandTopic).put("responseTopic", responseTopic)
                .put("eventTopic", eventTopic).put("heartbeatTopic", heartbeatTopic)
                .put("httpDownlinkSupported", false)
                .put("sentMessages", sentMessages).put("receivedMessages", receivedMessages)
                .put("transportConnected", isTransportConnected())
                .put("authenticated", "AUTHENTICATED".equals(state))
                .put("lastConnectedAt", lastConnectedAt == 0L ? JSONObject.NULL : lastConnectedAt)
                .put("authenticatedAt", authenticatedAt == 0L ? JSONObject.NULL : authenticatedAt)
                .put("lastMessageAt", lastMessageAt == 0L ? JSONObject.NULL : lastMessageAt)
                .put("lastError", lastError.isEmpty() ? JSONObject.NULL : lastError);
    }

    public synchronized boolean isAuthenticated() { return "AUTHENTICATED".equals(state); }
    public synchronized String transportMode() { return transportMode; }

    public void send(JSONObject payload) throws Exception {
        String cmd = payload == null ? "" : payload.optString("cmd", "").trim();
        if (cmd.isEmpty()) throw new IllegalArgumentException("后端消息缺少cmd");
        boolean lifecycle = "login".equals(cmd) || "heartbeat".equals(cmd);
        if (!lifecycle && !"AUTHENTICATED".equals(state)) {
            throw new IllegalStateException("后端业务会话尚未认证，当前状态：" + state);
        }
        if (BackendEndpointSettings.MODE_HTTP.equals(transportMode)) sendHttp(payload);
        else if (BackendEndpointSettings.MODE_TCP.equals(transportMode)) sendTcp(payload);
        else publishMqtt(payload);
    }

    private synchronized void reconnectNow() {
        cancelReconnect();
        stopHeartbeat();
        closeTransports();
        connecting = false;
        if (!running) return;
        if (BackendEndpointSettings.MODE_HTTP.equals(transportMode)) connectHttp();
        else if (BackendEndpointSettings.MODE_TCP.equals(transportMode)) connectTcp();
        else connectMqtt();
    }

    private void connectHttp() {
        if (connecting) return;
        connecting = true;
        updateState("LOGIN_SENT", "正在执行HTTP设备登录", null);
        executor.execute(() -> {
            try {
                JSONObject login = httpGateway.postData(BackendHttpGateway.DEVICE_LOGIN,
                        new JSONObject().put("version", BuildConfig.VERSION_NAME));
                requireLoginSuccess(login, "HTTP");
                String token = login.optString("token", "").trim();
                if (!token.isEmpty() && listener != null) listener.onRuntimeToken(token);
                connecting = false;
                lastConnectedAt = System.currentTimeMillis();
                authenticatedAt = lastConnectedAt;
                updateState("AUTHENTICATED",
                        "HTTP业务登录成功；V4.1未定义HTTP下行指令", null);
                startHeartbeat();
            } catch (Exception error) {
                connecting = false;
                updateState("ERROR", "HTTP登录失败：" + safeMessage(error), error);
                scheduleReconnect();
            }
        });
    }

    private void connectMqtt() {
        if (brokerUri.isEmpty()) { updateState("DISCONNECTED", "MQTT域名/IP、端口或协议未配置", null); return; }
        if (deviceCode.isEmpty() || clientId.isEmpty() || mqttPassword.isEmpty() || signingKey.isEmpty()) {
            updateState("PENDING_CREDENTIALS", "MQTT等待deviceCode/clientId/password/signingKey", null);
            return;
        }
        if (connecting) return;
        connecting = true;
        updateState("CONNECTING", "正在连接MQTT " + brokerUri, null);
        executor.execute(() -> {
            MqttAsyncClient next = null;
            try {
                next = new MqttAsyncClient(brokerUri, clientId, new MemoryPersistence());
                final MqttAsyncClient active = next;
                next.setCallback(new MqttCallbackExtended() {
                    @Override public void connectComplete(boolean reconnect, String serverURI) {
                        try {
                            lastConnectedAt = System.currentTimeMillis();
                            updateState("TRANSPORT_CONNECTED", "MQTT传输已连接 " + serverURI, null);
                            active.subscribe(commandTopic, 1).waitForCompletion();
                            active.subscribe(responseTopic, 1).waitForCompletion();
                            updateState("SUBSCRIBED", "MQTT Topic订阅完成", null);
                            sendLogin();
                            updateState("LOGIN_SENT", "MQTT登录消息已发送", null);
                        } catch (Exception error) {
                            updateState("ERROR", "MQTT订阅或登录失败：" + safeMessage(error), error);
                            closeTransports();
                            scheduleReconnect();
                        }
                    }
                    @Override public void connectionLost(Throwable cause) {
                        authenticatedAt = 0L;
                        stopHeartbeat();
                        updateState("ERROR", "MQTT连接断开：" + safeMessage(cause),
                                cause instanceof Exception ? (Exception) cause : null);
                        scheduleReconnect();
                    }
                    @Override public void messageArrived(String topic, MqttMessage mqttMessage) {
                        handleIncoming("mqtt:" + topic, mqttMessage == null ? ""
                                : new String(mqttMessage.getPayload(), StandardCharsets.UTF_8));
                    }
                    @Override public void deliveryComplete(IMqttDeliveryToken token) { }
                });
                synchronized (this) { mqttClient = next; }
                MqttConnectOptions options = new MqttConnectOptions();
                options.setAutomaticReconnect(false);
                options.setCleanSession(true);
                options.setKeepAliveInterval(60);
                options.setConnectionTimeout(10);
                if (!mqttUsername.isEmpty()) options.setUserName(mqttUsername);
                options.setPassword(mqttPassword.toCharArray());
                next.connect(options).waitForCompletion();
                connecting = false;
            } catch (Exception error) {
                connecting = false;
                closeMqtt(next);
                updateState("ERROR", "MQTT连接失败：" + safeMessage(error), error);
                scheduleReconnect();
            }
        });
    }

    private void connectTcp() {
        if (tcpHost.isEmpty() || tcpPort < 1) {
            updateState("DISCONNECTED", "兼容TCP地址或端口未配置", null); return;
        }
        if (connecting) return;
        connecting = true;
        executor.execute(() -> {
            try {
                Socket socket = new Socket(tcpHost, tcpPort);
                socket.setKeepAlive(true);
                socket.setTcpNoDelay(true);
                synchronized (this) {
                    tcpSocket = socket;
                    tcpOutput = new BufferedOutputStream(socket.getOutputStream());
                    connecting = false;
                    lastConnectedAt = System.currentTimeMillis();
                }
                updateState("TRANSPORT_CONNECTED", "兼容TCP已连接", null);
                sendLogin();
                updateState("LOGIN_SENT", "兼容TCP登录消息已发送", null);
                readTcpLoop(socket);
            } catch (Exception error) {
                connecting = false;
                closeTransports();
                updateState("ERROR", "兼容TCP连接失败：" + safeMessage(error), error);
                scheduleReconnect();
            }
        });
    }

    private void readTcpLoop(Socket socket) {
        byte[] bytes = new byte[4096];
        StringBuilder buffer = new StringBuilder();
        try (BufferedInputStream input = new BufferedInputStream(socket.getInputStream())) {
            while (running && !socket.isClosed()) {
                int count = input.read(bytes);
                if (count < 0) break;
                if (count > 0) {
                    buffer.append(new String(bytes, 0, count, StandardCharsets.UTF_8));
                    consumeJson(buffer);
                }
            }
        } catch (Exception error) {
            if (running) updateState("ERROR", "兼容TCP读取失败：" + safeMessage(error), error);
        } finally {
            closeTransports();
            stopHeartbeat();
            scheduleReconnect();
        }
    }

    private void consumeJson(StringBuilder buffer) {
        while (true) {
            int start = findJsonStart(buffer);
            if (start < 0) { buffer.setLength(0); return; }
            if (start > 0) buffer.delete(0, start);
            int end = findJsonEnd(buffer);
            if (end < 0) return;
            String raw = buffer.substring(0, end + 1);
            buffer.delete(0, end + 1);
            handleIncoming("tcp", raw);
        }
    }

    private void handleIncoming(String source, String raw) {
        try {
            JSONObject envelope = new JSONObject(raw == null ? "" : raw.trim());
            receivedMessages++;
            lastMessageAt = System.currentTimeMillis();
            notifyMessage(summary(envelope, source));
            String cmd = envelope.optString("cmd", "");
            if ("loginResp".equals(cmd)) {
                requireLoginSuccess(envelope, "MQTT/TCP");
                JSONObject loginData = envelope.optJSONObject("data");
                String runtimeToken = envelope.optString("token", "").trim();
                if (runtimeToken.isEmpty() && loginData != null) {
                    runtimeToken = loginData.optString("token", "").trim();
                }
                if (!runtimeToken.isEmpty() && listener != null) {
                    listener.onRuntimeToken(runtimeToken);
                }
                authenticatedAt = System.currentTimeMillis();
                updateState("AUTHENTICATED", "后台业务登录成功", null);
                startHeartbeat();
                return;
            }
            if ("heartbeatResp".equals(cmd) || cmd.endsWith("Resp")) return;
            if (!"AUTHENTICATED".equals(state)) {
                updateState("AUTH_REQUIRED", "收到下行指令但设备尚未登录", null); return;
            }
            if (listener != null) listener.onCommand(commandFromEnvelope(envelope, source));
        } catch (Exception error) {
            updateState("ERROR", "后端消息处理失败：" + safeMessage(error),
                    error instanceof Exception ? (Exception) error : null);
        }
    }

    private void sendLogin() throws Exception {
        send(new JSONObject().put("cmd", "login")
                .put("data", new JSONObject().put("version", BuildConfig.VERSION_NAME)));
    }

    private synchronized void startHeartbeat() {
        stopHeartbeat();
        heartbeatTask = heartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                if (BackendEndpointSettings.MODE_HTTP.equals(transportMode)) {
                    JSONObject result = httpGateway.postData(BackendHttpGateway.DEVICE_HEARTBEAT,
                            new JSONObject().put("seq", ++heartbeatSequence));
                    receivedMessages++;
                    lastMessageAt = System.currentTimeMillis();
                    notifyMessage(summary(new JSONObject().put("cmd", "heartbeatResp")
                            .put("data", result), "http"));
                } else {
                    send(new JSONObject().put("cmd", "heartbeat").put("data", new JSONObject()));
                }
            } catch (Exception error) {
                updateState("ERROR", "心跳失败：" + safeMessage(error), error);
                closeTransports();
                scheduleReconnect();
            }
        }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private synchronized void stopHeartbeat() {
        if (heartbeatTask != null) { heartbeatTask.cancel(false); heartbeatTask = null; }
    }

    private synchronized void publishMqtt(JSONObject payload) throws Exception {
        if (mqttClient == null || !mqttClient.isConnected()) throw new IllegalStateException("MQTT未连接");
        JSONObject envelope = buildEnvelope(payload);
        boolean heartbeat = "heartbeat".equals(envelope.optString("cmd"));
        MqttMessage message = new MqttMessage(envelope.toString().getBytes(StandardCharsets.UTF_8));
        message.setQos(heartbeat ? 0 : 1);
        message.setRetained(false);
        mqttClient.publish(heartbeat ? heartbeatTopic : eventTopic, message).waitForCompletion();
        sentMessages++;
    }

    private synchronized void sendTcp(JSONObject payload) throws Exception {
        if (tcpSocket == null || tcpSocket.isClosed() || tcpOutput == null) {
            throw new IllegalStateException("兼容TCP未连接");
        }
        tcpOutput.write((payload.toString() + "\n").getBytes(StandardCharsets.UTF_8));
        tcpOutput.flush();
        sentMessages++;
    }

    private void sendHttp(JSONObject payload) throws Exception {
        JSONObject result = httpGateway.sendCommand(payload);
        sentMessages++;
        receivedMessages++;
        lastMessageAt = System.currentTimeMillis();
        notifyMessage(summary(new JSONObject().put("cmd", payload.optString("cmd") + "Resp")
                .put("data", result), "http"));
    }

    private JSONObject buildEnvelope(JSONObject payload) throws Exception {
        String cmd = payload.optString("cmd", "").trim();
        JSONObject data = payload.optJSONObject("data");
        if (data == null) data = mqttData(payload);
        long timestamp = System.currentTimeMillis();
        String msgId = payload.optString("msgId", "").trim();
        if (msgId.isEmpty()) msgId = "msg_" + timestamp
                + String.format(Locale.US, "%03d", new Random().nextInt(1000));
        String canonical = data.length() == 0 ? "{}" : data.toString();
        return new JSONObject().put("msgId", msgId).put("cmd", cmd)
                .put("timestamp", timestamp).put("deviceCode", deviceCode)
                .put("sign", sign(msgId, cmd, timestamp, canonical)).put("data", data);
    }

    private JSONObject commandFromEnvelope(JSONObject envelope, String source) throws JSONException {
        JSONObject data = envelope.optJSONObject("data");
        JSONObject command = data == null ? new JSONObject() : new JSONObject(data.toString());
        return command.put("cmd", envelope.optString("cmd", ""))
                .put("msgId", envelope.optString("msgId", ""))
                .put("timestamp", envelope.optLong("timestamp", 0L))
                .put("_source", source == null ? "" : source);
    }

    private static JSONObject mqttData(JSONObject payload) throws JSONException {
        JSONObject data = new JSONObject();
        java.util.Iterator<String> keys = payload.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if ("cmd".equals(key) || "msgId".equals(key) || "timestamp".equals(key)
                    || "deviceCode".equals(key) || "sign".equals(key) || key.startsWith("_")) continue;
            data.put(key, payload.opt(key));
        }
        return data;
    }

    private String sign(String msgId, String cmd, long timestamp, String canonical) throws Exception {
        if (signingKey.trim().isEmpty()) throw new IllegalStateException("MQTT signingKey为空");
        String input = msgId + ":" + cmd + ":" + timestamp + ":" + canonical;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(signingKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.encodeToString(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    }

    private static void requireLoginSuccess(JSONObject payload, String channel) {
        JSONObject data = payload == null ? null : payload.optJSONObject("data");
        Integer code = payload != null && payload.has("code") ? payload.optInt("code")
                : data != null && data.has("code") ? data.optInt("code") : null;
        if (code == null || code != 0) {
            String text = payload == null ? "" : payload.optString("msg", "");
            if (text.isEmpty() && data != null) text = data.optString("msg", "");
            throw new IllegalStateException(text.isEmpty()
                    ? channel + "登录响应缺少明确code=0" : text);
        }
    }

    private synchronized boolean isTransportConnected() {
        if (BackendEndpointSettings.MODE_HTTP.equals(transportMode)) return "AUTHENTICATED".equals(state);
        if (BackendEndpointSettings.MODE_TCP.equals(transportMode)) return tcpSocket != null && !tcpSocket.isClosed();
        return mqttClient != null && mqttClient.isConnected();
    }

    private synchronized void closeTransports() {
        MqttAsyncClient current = mqttClient;
        mqttClient = null;
        closeMqtt(current);
        try { if (tcpOutput != null) tcpOutput.close(); } catch (Exception ignored) { }
        try { if (tcpSocket != null) tcpSocket.close(); } catch (Exception ignored) { }
        tcpOutput = null;
        tcpSocket = null;
        authenticatedAt = 0L;
    }

    private static void closeMqtt(MqttAsyncClient client) {
        if (client == null) return;
        try { if (client.isConnected()) client.disconnectForcibly(1000, 1000); }
        catch (Exception ignored) { }
        try { client.close(); } catch (Exception ignored) { }
    }

    private synchronized void scheduleReconnect() {
        if (!running || executor.isShutdown()) return;
        cancelReconnect();
        reconnectTask = executor.schedule(this::reconnectNow, RECONNECT_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private synchronized void cancelReconnect() {
        if (reconnectTask != null) { reconnectTask.cancel(false); reconnectTask = null; }
    }

    private synchronized void updateState(String next, String nextMessage, Exception error) {
        state = next;
        message = nextMessage;
        lastError = error == null ? "" : safeMessage(error);
        if (listener != null) {
            try { listener.onStatusChanged(snapshot()); }
            catch (JSONException ignored) { }
        }
    }

    private void notifyMessage(JSONObject value) {
        if (listener != null) listener.onMessage(value);
    }

    private static JSONObject summary(JSONObject payload, String source) throws JSONException {
        return new JSONObject().put("source", source == null ? "" : source)
                .put("cmd", payload == null ? "" : payload.optString("cmd", ""))
                .put("msgId", payload == null ? "" : payload.optString("msgId", ""))
                .put("timestamp", payload == null ? 0L : payload.optLong("timestamp", 0L));
    }

    private static int findJsonStart(StringBuilder buffer) {
        for (int index = 0; index < buffer.length(); index++) if (buffer.charAt(index) == '{') return index;
        return -1;
    }

    private static int findJsonEnd(StringBuilder buffer) {
        boolean string = false;
        boolean escaped = false;
        int depth = 0;
        for (int index = 0; index < buffer.length(); index++) {
            char ch = buffer.charAt(index);
            if (escaped) { escaped = false; continue; }
            if (ch == '\\') { escaped = string; continue; }
            if (ch == '"') { string = !string; continue; }
            if (string) continue;
            if (ch == '{') depth++;
            if (ch == '}' && --depth == 0) return index;
        }
        return -1;
    }

    private static String safeMessage(Throwable error) {
        String value = error == null ? "" : error.getMessage();
        return value == null || value.trim().isEmpty()
                ? error == null ? "unknown" : error.getClass().getSimpleName() : value;
    }
}
