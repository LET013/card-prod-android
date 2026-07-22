package com.xingyao.card.core;

import android.content.Context;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Backend transport adapter.
 *
 * MQTT supplies real-time bidirectional commands. HTTP supplies provisioning, login, heartbeat and
 * documented upload endpoints but has no downlink command endpoint in V4.1. Plain TCP is retained
 * only for legacy deployments.
 */
public final class WebSocketConnectionManager {
    public interface Listener {
        void onStatusChanged(JSONObject status);
        void onCommand(JSONObject command);
        void onMessage(JSONObject message);
    }

    private static final String MODE_MQTT = BackendEndpointSettings.MODE_MQTT;
    private static final String MODE_HTTP = BackendEndpointSettings.MODE_HTTP;
    private static final String MODE_TCP = BackendEndpointSettings.MODE_TCP;
    private static final long DEFAULT_HEARTBEAT_INTERVAL_MS = 30000L;
    private static final long RECONNECT_DELAY_MS = 5000L;
    private static final long LOGIN_TIMEOUT_MS = 15000L;
    private static final int MQTT_KEEP_ALIVE_SECONDS = 60;
    private static final int MQTT_CONNECTION_TIMEOUT_SECONDS = 10;

    private final NativeSettingsRepository settingsRepository;
    private final DeviceProvisioningManager provisioningManager;
    private final BackendHttpGateway httpGateway;
    private final Listener listener;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();

    private ScheduledFuture<?> reconnectTask;
    private ScheduledFuture<?> heartbeatTask;
    private ScheduledFuture<?> loginTimeoutTask;
    private long loginAttemptGeneration;
    private MqttAsyncClient mqttClient;
    private Socket tcpSocket;
    private BufferedOutputStream tcpOutput;
    private volatile boolean running;
    private volatile boolean connecting;
    private volatile String state = "DISCONNECTED";
    private volatile String message = "后端通信未启动";
    private volatile boolean forceCredentialRefresh;

    private String transportMode = MODE_MQTT;
    private String deviceId = "";
    private String deviceCode = "";
    private String clientId = "";
    private String brokerUri = "";
    private String mqttUsername = "";
    private boolean mqttUsernameConfigured;
    private String mqttPassword = "";
    private String signingKey = "";
    private String commandTopic = "";
    private String responseTopic = "";
    private String eventTopic = "";
    private String heartbeatTopic = "";
    private String tcpHost = "";
    private int tcpPort;
    private String httpBaseUrl = "";
    private long heartbeatIntervalMs = DEFAULT_HEARTBEAT_INTERVAL_MS;
    private long heartbeatSequence;
    private long sentMessages;
    private long receivedMessages;
    private volatile long lastConnectedAt;
    private volatile long authenticatedAt;
    private volatile long lastMessageAt;
    private volatile String lastError = "";

    public WebSocketConnectionManager(Context context, NativeSettingsRepository settingsRepository,
                                      Listener listener) {
        this.settingsRepository = settingsRepository;
        this.provisioningManager = new DeviceProvisioningManager(context, settingsRepository);
        this.httpGateway = new BackendHttpGateway(settingsRepository);
        this.listener = listener;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        configure(loadSettingsQuietly());
    }

    public synchronized void configure(JSONObject settings) {
        applySettings(settings);
        if (running) reconnectNow();
    }

    public synchronized void stop() {
        running = false;
        connecting = false;
        cancelReconnect();
        stopHeartbeat();
        cancelLoginTimeout();
        closeTransports();
        heartbeatExecutor.shutdownNow();
        executor.shutdownNow();
        updateState("DISCONNECTED", "后端通信已停止", null);
    }

    public synchronized JSONObject snapshot() throws JSONException {
        return new JSONObject()
                .put("state", state)
                .put("message", message)
                .put("transportMode", transportMode)
                .put("protocol", transportMode + "_JSON")
                .put("deviceId", deviceId)
                .put("deviceCode", deviceCode)
                .put("clientId", clientId)
                .put("brokerUri", brokerUri)
                .put("httpBaseUrl", httpBaseUrl)
                .put("tcpHost", tcpHost)
                .put("tcpPort", tcpPort)
                .put("commandTopic", commandTopic)
                .put("responseTopic", responseTopic)
                .put("eventTopic", eventTopic)
                .put("heartbeatTopic", heartbeatTopic)
                .put("heartbeatIntervalMs", heartbeatIntervalMs)
                .put("httpDownlinkSupported", false)
                .put("sentMessages", sentMessages)
                .put("receivedMessages", receivedMessages)
                .put("transportConnected", isTransportConnected())
                .put("authenticated", "AUTHENTICATED".equals(state))
                .put("lastConnectedAt", lastConnectedAt == 0 ? JSONObject.NULL : lastConnectedAt)
                .put("authenticatedAt", authenticatedAt == 0 ? JSONObject.NULL : authenticatedAt)
                .put("lastMessageAt", lastMessageAt == 0 ? JSONObject.NULL : lastMessageAt)
                .put("lastError", lastError.isEmpty() ? JSONObject.NULL : lastError);
    }

    public synchronized boolean isAuthenticated() {
        return "AUTHENTICATED".equals(state);
    }

    public synchronized String transportMode() {
        return transportMode;
    }

    public void send(JSONObject payload) throws Exception {
        String cmd = payload == null ? "" : payload.optString("cmd", "").trim();
        if (cmd.isEmpty()) throw new IllegalArgumentException("后端消息缺少cmd");
        boolean lifecycleMessage = "login".equals(cmd) || "heartbeat".equals(cmd);
        if (!lifecycleMessage && !"AUTHENTICATED".equals(state)) {
            throw new IllegalStateException("后端业务会话尚未认证，当前状态：" + state);
        }
        if (MODE_HTTP.equals(transportMode)) sendHttp(payload);
        else if (MODE_TCP.equals(transportMode)) sendTcp(payload);
        else publishMqtt(payload);
    }

    private void reconnectNow() {
        cancelReconnect();
        stopHeartbeat();
        cancelLoginTimeout();
        closeTransports();
        if (MODE_HTTP.equals(transportMode)) connectHttp();
        else if (MODE_TCP.equals(transportMode)) connectTcp();
        else connectMqtt();
    }

    private void connectHttp() {
        if (httpBaseUrl.isEmpty()) {
            updateState("DISCONNECTED", "HTTP域名/IP未配置", null);
            return;
        }
        if (connecting) return;
        connecting = true;
        updateState("CONNECTING", "正在执行HTTP注册、激活与配置流程", null);
        executor.execute(() -> {
            try {
                JSONObject provisioned = provisioningManager.ensureProvisioned();
                synchronized (this) { applySettings(provisioned); }
                if (!MODE_HTTP.equals(transportMode)) {
                    synchronized (this) { connecting = false; }
                    reconnectNow();
                    return;
                }
                updateState("LOGIN_SENT", "正在执行HTTP设备登录", null);
                JSONObject login = httpGateway.postData(BackendHttpGateway.DEVICE_LOGIN,
                        new JSONObject().put("version", BuildConfig.VERSION_NAME));
                requireLoginSuccess(login, "HTTP");
                String runtimeToken = login.optString("token", "").trim();
                if (!runtimeToken.isEmpty()) {
                    JSONObject tokenSettings = settingsRepository.load();
                    tokenSettings.put("runtimeToken", runtimeToken);
                    settingsRepository.save(tokenSettings);
                }
                synchronized (this) {
                    connecting = false;
                    lastConnectedAt = System.currentTimeMillis();
                    authenticatedAt = lastConnectedAt;
                }
                updateState("AUTHENTICATED",
                        "HTTP业务登录成功；V4.1未定义HTTP下行指令，远程开门需MQTT", null);
                startHeartbeat();
            } catch (Exception error) {
                synchronized (this) { connecting = false; }
                updateState("ERROR", "HTTP登录失败：" + safeMessage(error), error);
                scheduleReconnect();
            }
        });
    }

    private void connectMqtt() {
        if (brokerUri.isEmpty()) {
            updateState("DISCONNECTED", "MQTT域名/IP未配置", null);
            return;
        }
        if (connecting) return;
        connecting = true;
        updateState("CONNECTING", "正在执行HTTP注册、激活与MQTT凭证获取", null);
        executor.execute(() -> {
            try {
                JSONObject provisioned = forceCredentialRefresh
                        ? provisioningManager.refreshCredentials()
                        : provisioningManager.ensureProvisioned();
                forceCredentialRefresh = false;
                synchronized (this) { applySettings(provisioned); }
                if (!MODE_MQTT.equals(transportMode)) {
                    synchronized (this) { connecting = false; }
                    reconnectNow();
                    return;
                }
                try {
                    connectMqttWithAvailableCredentials();
                } catch (Exception transportAuthError) {
                    updateState("CONNECTING", "MQTT连接认证失败，正在刷新凭证", transportAuthError);
                    JSONObject refreshed = provisioningManager.refreshCredentials();
                    synchronized (this) { applySettings(refreshed); }
                    if (!MODE_MQTT.equals(transportMode)) {
                        synchronized (this) { connecting = false; }
                        reconnectNow();
                        return;
                    }
                    connectMqttWithAvailableCredentials();
                }
                synchronized (this) { connecting = false; }
            } catch (Exception error) {
                synchronized (this) { connecting = false; }
                closeTransports();
                updateState("ERROR", "MQTT连接失败：" + safeMessage(error), error);
                scheduleReconnect();
            }
        });
    }

    private void connectMqttWithAvailableCredentials() throws Exception {
        if (brokerUri.isEmpty()) throw new IllegalStateException("MQTT域名/IP未配置");
        if (clientId.isEmpty()) throw new IllegalStateException("MQTT clientId尚未由激活接口下发");
        if (mqttPassword.isEmpty()) throw new IllegalStateException("MQTT密码尚未由激活接口下发");
        if (signingKey.isEmpty()) throw new IllegalStateException("MQTT签名密钥尚未由激活接口下发");

        Exception lastException = null;
        for (String username : mqttUsernameCandidates()) {
            MqttAsyncClient nextClient = null;
            String authLabel = mqttAuthLabel(username);
            try {
                updateState("CONNECTING", "正在连接MQTT " + brokerUri + " auth=" + authLabel, null);
                nextClient = new MqttAsyncClient(brokerUri, clientId, new MemoryPersistence());
                final MqttAsyncClient callbackClient = nextClient;
                nextClient.setCallback(new MqttCallbackExtended() {
                    @Override public void connectComplete(boolean reconnect, String serverURI) {
                        try {
                            lastConnectedAt = System.currentTimeMillis();
                            authenticatedAt = 0L;
                            updateState("TRANSPORT_CONNECTED", "MQTT传输已连接 " + serverURI, null);
                            subscribeTopics(callbackClient);
                            updateState("SUBSCRIBED", "MQTT下行与响应Topic订阅完成", null);
                            updateState("LOGIN_SENT", "正在发送MQTT业务登录", null);
                            sendLogin();
                            startLoginTimeout();
                        } catch (Exception error) {
                            updateState("ERROR", "MQTT订阅/登录失败：" + safeMessage(error), error);
                            closeTransports();
                            if (running) scheduleReconnect();
                        }
                    }

                    @Override public void connectionLost(Throwable cause) {
                        stopHeartbeat();
                        cancelLoginTimeout();
                        authenticatedAt = 0L;
                        updateState("ERROR", "MQTT连接断开：" + safeMessage(cause),
                                cause instanceof Exception ? (Exception) cause : null);
                        if (running) scheduleReconnect();
                    }

                    @Override public void messageArrived(String topic, MqttMessage mqttMessage) {
                        handleIncoming("mqtt:" + topic, mqttMessage == null ? null
                                : new String(mqttMessage.getPayload(), StandardCharsets.UTF_8));
                    }

                    @Override public void deliveryComplete(IMqttDeliveryToken token) { }
                });
                synchronized (this) {
                    mqttClient = nextClient;
                    mqttUsername = username;
                }
                MqttConnectOptions options = new MqttConnectOptions();
                options.setAutomaticReconnect(false);
                options.setCleanSession(true);
                options.setKeepAliveInterval(MQTT_KEEP_ALIVE_SECONDS);
                options.setConnectionTimeout(MQTT_CONNECTION_TIMEOUT_SECONDS);
                if (!username.isEmpty()) options.setUserName(username);
                options.setPassword(mqttPassword.toCharArray());
                nextClient.connect(options).waitForCompletion();
                return;
            } catch (Exception error) {
                lastException = error;
                synchronized (this) {
                    if (mqttClient == nextClient) mqttClient = null;
                }
                closeMqttQuietly(nextClient);
                updateState("CONNECTING", "MQTT认证失败 auth=" + authLabel + "："
                        + safeMessage(error), error);
                try { Thread.sleep(500L); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw interrupted;
                }
            }
        }
        throw lastException == null ? new IllegalStateException("MQTT连接失败") : lastException;
    }

    private void connectTcp() {
        if (tcpHost.isEmpty() || tcpPort <= 0) {
            updateState("DISCONNECTED", "TCP域名/IP或端口未配置", null);
            return;
        }
        if (connecting) return;
        connecting = true;
        updateState("CONNECTING", "正在执行HTTP注册/激活后连接兼容TCP", null);
        executor.execute(() -> {
            try {
                JSONObject provisioned = provisioningManager.ensureProvisioned();
                synchronized (this) { applySettings(provisioned); }
                if (!MODE_TCP.equals(transportMode)) {
                    synchronized (this) { connecting = false; }
                    reconnectNow();
                    return;
                }
                Socket nextSocket = new Socket(tcpHost, tcpPort);
                nextSocket.setKeepAlive(true);
                nextSocket.setTcpNoDelay(true);
                synchronized (this) {
                    tcpSocket = nextSocket;
                    tcpOutput = new BufferedOutputStream(nextSocket.getOutputStream());
                    connecting = false;
                    lastConnectedAt = System.currentTimeMillis();
                }
                authenticatedAt = 0L;
                updateState("TRANSPORT_CONNECTED", String.format(Locale.US,
                        "TCP传输已连接 %s:%d", tcpHost, tcpPort), null);
                updateState("LOGIN_SENT", "正在发送TCP业务登录", null);
                sendLogin();
                startLoginTimeout();
                readTcpLoop(nextSocket);
            } catch (Exception error) {
                synchronized (this) { connecting = false; }
                closeTransports();
                updateState("ERROR", "TCP连接失败：" + safeMessage(error), error);
                scheduleReconnect();
            }
        });
    }

    private void readTcpLoop(Socket activeSocket) {
        byte[] buffer = new byte[4096];
        StringBuilder textBuffer = new StringBuilder();
        try (BufferedInputStream input = new BufferedInputStream(activeSocket.getInputStream())) {
            while (running && !activeSocket.isClosed()) {
                int count = input.read(buffer);
                if (count < 0) break;
                if (count == 0) continue;
                textBuffer.append(new String(buffer, 0, count, StandardCharsets.UTF_8));
                consumeJsonBuffer(textBuffer);
            }
        } catch (Exception error) {
            if (running) updateState("ERROR", "TCP读取失败：" + safeMessage(error), error);
        } finally {
            closeTransports();
            stopHeartbeat();
            cancelLoginTimeout();
            if (running) scheduleReconnect();
        }
    }

    private void consumeJsonBuffer(StringBuilder buffer) {
        while (true) {
            int start = findJsonStart(buffer);
            if (start < 0) {
                buffer.setLength(0);
                return;
            }
            if (start > 0) buffer.delete(0, start);
            int end = findJsonEnd(buffer);
            if (end < 0) return;
            String raw = buffer.substring(0, end + 1);
            buffer.delete(0, end + 1);
            handleIncoming("tcp", raw);
        }
    }

    private void handleIncoming(String source, String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) return;
        try {
            JSONObject payload = new JSONObject(text);
            receivedMessages++;
            lastMessageAt = System.currentTimeMillis();
            notifyMessage(messageSummary(payload, source));
            String cmd = payload.optString("cmd", "");
            if ("loginResp".equals(cmd)) {
                handleLoginResponse(payload);
                return;
            }
            if ("heartbeatResp".equals(cmd) || cmd.endsWith("Resp")) return;
            if (!"AUTHENTICATED".equals(state)) {
                updateState("AUTH_REQUIRED", "收到业务指令但设备尚未完成登录认证", null);
                return;
            }
            notifyCommand(commandFromEnvelope(payload, source));
        } catch (JSONException error) {
            updateState("ERROR", "后端消息不是合法JSON", error);
        }
    }

    private void handleLoginResponse(JSONObject payload) {
        synchronized (this) {
            if (!"LOGIN_SENT".equals(state)) return;
            cancelLoginTimeout();
        }
        try {
            requireLoginSuccess(payload, "MQTT/TCP");
            authenticatedAt = System.currentTimeMillis();
            updateState("AUTHENTICATED", "后台业务登录认证成功", null);
            startHeartbeat();
        } catch (Exception error) {
            stopHeartbeat();
            authenticatedAt = 0L;
            forceCredentialRefresh = true;
            updateState("AUTH_PROTOCOL_ERROR", safeMessage(error), error);
            closeTransports();
            if (running) scheduleReconnect();
        }
    }

    private void requireLoginSuccess(JSONObject payload, String channel) {
        JSONObject data = payload == null ? null : payload.optJSONObject("data");
        Integer code = null;
        if (payload != null && payload.has("code")) code = payload.optInt("code", Integer.MIN_VALUE);
        else if (data != null && data.has("code")) code = data.optInt("code", Integer.MIN_VALUE);
        if (code == null || (code != 0 && code != 200)) {
            String backendMessage = payload == null ? "" : payload.optString("msg",
                    payload.optString("message", ""));
            if (backendMessage.isEmpty() && data != null) {
                backendMessage = data.optString("msg", data.optString("message", ""));
            }
            throw new IllegalStateException(backendMessage.isEmpty()
                    ? channel + " loginResp缺少明确成功code" : backendMessage);
        }
    }

    private void sendLogin() throws Exception {
        send(new JSONObject().put("cmd", "login")
                .put("data", new JSONObject().put("version", BuildConfig.VERSION_NAME)));
    }

    private synchronized void startLoginTimeout() {
        cancelLoginTimeout();
        final long generation = ++loginAttemptGeneration;
        loginTimeoutTask = heartbeatExecutor.schedule(() -> {
            if (!markLoginTimeout(generation)) return;
            closeTransports();
            scheduleReconnect();
        }, LOGIN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private boolean markLoginTimeout(long generation) {
        synchronized (this) {
            if (generation != loginAttemptGeneration || !running || !"LOGIN_SENT".equals(state)) return false;
            loginAttemptGeneration++;
            authenticatedAt = 0L;
            state = "AUTH_TIMEOUT";
            message = "后台登录响应超时";
            lastError = "";
        }
        notifyStatus();
        return true;
    }

    private synchronized void cancelLoginTimeout() {
        loginAttemptGeneration++;
        if (loginTimeoutTask != null) {
            loginTimeoutTask.cancel(false);
            loginTimeoutTask = null;
        }
    }

    private void startHeartbeat() {
        stopHeartbeat();
        heartbeatTask = heartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                if (MODE_HTTP.equals(transportMode)) {
                    JSONObject result = httpGateway.postData(BackendHttpGateway.DEVICE_HEARTBEAT,
                            new JSONObject().put("seq", ++heartbeatSequence));
                    receivedMessages++;
                    lastMessageAt = System.currentTimeMillis();
                    notifyMessage(messageSummary(new JSONObject().put("cmd", "heartbeatResp")
                            .put("data", result), "http"));
                } else {
                    send(new JSONObject().put("cmd", "heartbeat")
                            .put("data", new JSONObject())
                            .put("timestamp", System.currentTimeMillis()));
                }
            } catch (Exception error) {
                updateState("ERROR", "心跳失败：" + safeMessage(error), error);
                closeTransports();
                if (running) scheduleReconnect();
            }
        }, heartbeatIntervalMs, heartbeatIntervalMs, TimeUnit.MILLISECONDS);
    }

    private synchronized void stopHeartbeat() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
            heartbeatTask = null;
        }
    }

    private synchronized void publishMqtt(JSONObject payload) throws Exception {
        if (mqttClient == null || !mqttClient.isConnected()) throw new IllegalStateException("MQTT未连接");
        JSONObject envelope = buildMqttEnvelope(payload);
        String topic = "heartbeat".equals(envelope.optString("cmd")) ? heartbeatTopic : eventTopic;
        MqttMessage mqttMessage = new MqttMessage(envelope.toString().getBytes(StandardCharsets.UTF_8));
        mqttMessage.setQos("heartbeat".equals(envelope.optString("cmd")) ? 0 : 1);
        mqttMessage.setRetained(false);
        mqttClient.publish(topic, mqttMessage).waitForCompletion();
        sentMessages++;
    }

    private synchronized void sendTcp(JSONObject payload) throws Exception {
        if (tcpSocket == null || tcpSocket.isClosed() || tcpOutput == null) {
            throw new IllegalStateException("TCP未连接");
        }
        byte[] bytes = (payload.toString() + "\n").getBytes(StandardCharsets.UTF_8);
        tcpOutput.write(bytes);
        tcpOutput.flush();
        sentMessages++;
    }

    private void sendHttp(JSONObject payload) throws Exception {
        JSONObject result = httpGateway.sendCommand(payload);
        sentMessages++;
        receivedMessages++;
        lastMessageAt = System.currentTimeMillis();
        notifyMessage(messageSummary(new JSONObject()
                .put("cmd", payload.optString("cmd") + "Resp")
                .put("data", result), "http"));
    }

    private synchronized boolean isTransportConnected() {
        if (MODE_HTTP.equals(transportMode)) return !httpBaseUrl.isEmpty() && "AUTHENTICATED".equals(state);
        if (MODE_TCP.equals(transportMode)) return tcpSocket != null && !tcpSocket.isClosed();
        return mqttClient != null && mqttClient.isConnected();
    }

    private void scheduleReconnect() {
        if (!running) return;
        cancelReconnect();
        reconnectTask = executor.schedule(() -> {
            if (running) reconnectNow();
        }, RECONNECT_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private synchronized void cancelReconnect() {
        if (reconnectTask != null) {
            reconnectTask.cancel(false);
            reconnectTask = null;
        }
    }

    private synchronized void closeTransports() {
        MqttAsyncClient currentMqtt = mqttClient;
        mqttClient = null;
        closeMqttQuietly(currentMqtt);
        try { if (tcpOutput != null) tcpOutput.close(); } catch (Exception ignored) { }
        try { if (tcpSocket != null) tcpSocket.close(); } catch (Exception ignored) { }
        tcpOutput = null;
        tcpSocket = null;
        authenticatedAt = 0L;
    }

    private void closeMqttQuietly(MqttAsyncClient currentMqtt) {
        if (currentMqtt == null) return;
        try {
            if (currentMqtt.isConnected()) currentMqtt.disconnectForcibly(1000, 1000);
        } catch (Exception ignored) { }
        try { currentMqtt.close(); } catch (Exception ignored) { }
    }

    private void applySettings(JSONObject rawSettings) {
        JSONObject settings;
        try { settings = BackendEndpointSettings.normalize(rawSettings); }
        catch (JSONException error) { settings = rawSettings == null ? new JSONObject() : rawSettings; }

        deviceCode = optString(settings, "deviceCode", optString(settings, "deviceId", "")).trim();
        deviceId = optString(settings, "deviceId", deviceCode).trim();
        String configuredMode = optString(settings, "backendTransport", MODE_MQTT)
                .trim().toUpperCase(Locale.US);
        transportMode = MODE_HTTP.equals(configuredMode) ? MODE_HTTP
                : MODE_TCP.equals(configuredMode) ? MODE_TCP : MODE_MQTT;

        clientId = optString(settings, "mqttClientId",
                optString(settings, "clientId", "")).trim();
        mqttUsername = optString(settings, "mqttUsername", "").trim();
        mqttUsernameConfigured = !mqttUsername.isEmpty();
        mqttPassword = optString(settings, "mqttPassword", "");
        signingKey = optString(settings, "signingKey", "");
        brokerUri = BackendEndpointSettings.mqttBrokerUrl(settings);
        httpBaseUrl = BackendEndpointSettings.httpBaseUrl(settings);
        tcpHost = BackendEndpointSettings.tcpHost(settings);
        tcpPort = parsePort(optString(settings, "tcpPort", ""), 9009);

        commandTopic = topic(settings, "mqttCommandTopic", "card/" + deviceCode + "/down");
        responseTopic = topic(settings, "mqttResponseTopic", "card/" + deviceCode + "/down/response");
        eventTopic = topic(settings, "mqttEventTopic", "card/" + deviceCode + "/up");
        heartbeatTopic = topic(settings, "mqttHeartbeatTopic", "card/" + deviceCode + "/heartbeat");
        heartbeatIntervalMs = parsePositiveLong(optString(settings,
                MODE_HTTP.equals(transportMode) ? "httpHeartbeatIntervalMs" : "mqttHeartbeatIntervalMs", ""),
                DEFAULT_HEARTBEAT_INTERVAL_MS);
    }

    private List<String> mqttUsernameCandidates() {
        ArrayList<String> candidates = new ArrayList<>();
        // V4.1 does not define MQTT username. Use an explicitly configured value only; otherwise
        // connect without a username and record the missing contract if the broker rejects it.
        addUnique(candidates, mqttUsernameConfigured ? mqttUsername : "");
        return candidates;
    }

    private static void addUnique(ArrayList<String> values, String value) {
        String normalized = value == null ? "" : value.trim();
        if (!values.contains(normalized)) values.add(normalized);
    }

    private String mqttAuthLabel(String username) {
        String value = username == null ? "" : username.trim();
        if (value.isEmpty()) return "none";
        return "configured";
    }

    private void subscribeTopics(MqttAsyncClient client) throws Exception {
        client.subscribe(commandTopic, 1).waitForCompletion();
        if (!responseTopic.equals(commandTopic)) client.subscribe(responseTopic, 1).waitForCompletion();
    }

    private JSONObject commandFromEnvelope(JSONObject envelope, String source) throws JSONException {
        JSONObject data = envelope.optJSONObject("data");
        JSONObject command = data == null ? new JSONObject() : new JSONObject(data.toString());
        command.put("cmd", envelope.optString("cmd", command.optString("cmd", "")))
                .put("msgId", envelope.optString("msgId", command.optString("msgId", "")))
                .put("timestamp", envelope.optLong("timestamp", command.optLong("timestamp", 0L)))
                .put("_source", source == null ? "" : source);
        // V4.1 downlink messages intentionally do not contain sign or deviceCode. Preserve them only
        // when a future/legacy server explicitly sends them.
        if (envelope.has("deviceCode")) command.put("deviceCode", envelope.optString("deviceCode", ""));
        if (envelope.has("sign")) command.put("sign", envelope.optString("sign", ""));
        return command;
    }

    private JSONObject buildMqttEnvelope(JSONObject payload) throws Exception {
        String cmd = payload == null ? "" : payload.optString("cmd", "");
        if (cmd.trim().isEmpty()) throw new IllegalArgumentException("MQTT消息缺少cmd");
        JSONObject data = mqttData(payload);
        long timestamp = System.currentTimeMillis();
        String msgId = payload.optString("msgId", "");
        if (msgId.trim().isEmpty()) {
            msgId = "msg_" + timestamp + String.format(Locale.US, "%03d", new Random().nextInt(1000));
        }
        String canonicalData = data.length() == 0 ? "{}" : data.toString();
        JSONObject envelope = new JSONObject()
                .put("msgId", msgId)
                .put("cmd", cmd)
                .put("timestamp", timestamp)
                .put("deviceCode", deviceCode)
                .put("data", data);
        envelope.put("sign", sign(msgId, cmd, timestamp, canonicalData));
        return envelope;
    }

    private static JSONObject mqttData(JSONObject payload) throws JSONException {
        JSONObject explicit = payload.optJSONObject("data");
        if (explicit != null) return new JSONObject(explicit.toString());
        JSONObject data = new JSONObject();
        java.util.Iterator<String> keys = payload.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if ("cmd".equals(key) || "msgId".equals(key) || "timestamp".equals(key)
                    || "deviceId".equals(key) || "deviceCode".equals(key) || "sign".equals(key)
                    || key.startsWith("_")) continue;
            data.put(key, payload.opt(key));
        }
        return data;
    }

    private String sign(String msgId, String cmd, long timestamp, String canonicalData) throws Exception {
        if (signingKey == null || signingKey.trim().isEmpty()) {
            throw new IllegalStateException("MQTT签名密钥为空");
        }
        String input = msgId + ":" + cmd + ":" + timestamp + ":" + canonicalData;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(signingKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.encodeToString(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    }

    private static JSONObject messageSummary(JSONObject payload, String source) throws JSONException {
        JSONObject result = new JSONObject()
                .put("source", source == null ? "" : source)
                .put("cmd", payload == null ? "" : payload.optString("cmd", ""))
                .put("msgId", payload == null ? "" : payload.optString("msgId", ""))
                .put("timestamp", payload == null ? 0L : payload.optLong("timestamp", 0L));
        if (payload != null && payload.has("code")) result.put("code", payload.opt("code"));
        if (payload != null && payload.has("status")) result.put("status", payload.opt("status"));
        return result;
    }

    private static int findJsonStart(StringBuilder buffer) {
        for (int index = 0; index < buffer.length(); index++) {
            if (buffer.charAt(index) == '{') return index;
        }
        return -1;
    }

    private static int findJsonEnd(StringBuilder buffer) {
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int index = 0; index < buffer.length(); index++) {
            char ch = buffer.charAt(index);
            if (escaped) { escaped = false; continue; }
            if (ch == '\\') { escaped = inString; continue; }
            if (ch == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (ch == '{') depth++;
            else if (ch == '}') {
                depth--;
                if (depth == 0) return index;
            }
        }
        return -1;
    }

    private JSONObject loadSettingsQuietly() {
        try { return settingsRepository.load(); }
        catch (Exception ignored) { return new JSONObject(); }
    }

    private void updateState(String nextState, String nextMessage, Exception error) {
        synchronized (this) {
            state = nextState;
            message = nextMessage;
            lastError = error == null ? "" : safeMessage(error);
        }
        notifyStatus();
    }

    private void notifyStatus() {
        if (listener == null) return;
        try { listener.onStatusChanged(snapshot()); }
        catch (JSONException ignored) { }
    }

    private void notifyCommand(JSONObject command) {
        if (listener != null) listener.onCommand(command);
    }

    private void notifyMessage(JSONObject data) {
        if (listener != null) listener.onMessage(data);
    }

    private static String topic(JSONObject object, String key, String fallback) {
        String value = optString(object, key, "").trim();
        return value.isEmpty() ? fallback : value;
    }

    private static String optString(JSONObject object, String key, String fallback) {
        return object == null ? fallback : object.optString(key, fallback);
    }

    private static int parsePort(String value, int fallback) {
        try {
            int port = Integer.parseInt(value);
            return port >= 1 && port <= 65535 ? port : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static long parsePositiveLong(String value, long fallback) {
        try {
            long result = Long.parseLong(value);
            return result > 0L ? result : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String safeMessage(Throwable error) {
        if (error == null) return "unknown";
        String value = error.getMessage();
        return value == null || value.trim().isEmpty()
                ? error.getClass().getSimpleName() : value;
    }
}
