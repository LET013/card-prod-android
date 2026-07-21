package com.xingyao.card.core;

import android.content.Context;
import android.util.Base64;

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
import java.net.URI;
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

/** Backend JSON transport. Supports MQTT and the legacy plain TCP socket protocol. */
public class WebSocketConnectionManager {
    public interface Listener {
        void onStatusChanged(JSONObject status);
        void onCommand(JSONObject command);
        void onMessage(JSONObject message);
    }

    private static final String MODE_MQTT = "MQTT";
    private static final String MODE_TCP = "TCP";
    private static final long HEARTBEAT_INTERVAL_MS = 30000L;
    private static final long RECONNECT_DELAY_MS = 5000L;
    private static final int MQTT_KEEP_ALIVE_SECONDS = 60;
    private static final int MQTT_CONNECTION_TIMEOUT_SECONDS = 10;

    private final NativeSettingsRepository settingsRepository;
    private final DeviceProvisioningManager provisioningManager;
    private final Listener listener;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    private ScheduledFuture<?> reconnectTask;
    private ScheduledFuture<?> heartbeatTask;
    private MqttAsyncClient mqttClient;
    private Socket tcpSocket;
    private BufferedOutputStream tcpOutput;
    private volatile boolean running;
    private volatile boolean connecting;
    private String state = "DISCONNECTED";
    private String message = "后端通信未启动";
    private String transportMode = MODE_MQTT;
    private String deviceId = "DEV001";
    private String deviceCode = "DEV001";
    private String clientId = "";
    private String brokerUri = "";
    private String mqttUsername = "";
    private boolean mqttUsernameConfigured = false;
    private String mqttPassword = "";
    private String signingKey = "";
    private String commandTopic = "";
    private String responseTopic = "";
    private String eventTopic = "";
    private String heartbeatTopic = "";
    private String tcpHost = "";
    private int tcpPort = 0;
    private long sentMessages;
    private long receivedMessages;
    private long lastConnectedAt;
    private long lastMessageAt;
    private String lastError = "";

    public WebSocketConnectionManager(Context context, NativeSettingsRepository settingsRepository, Listener listener) {
        this.settingsRepository = settingsRepository;
        this.provisioningManager = new DeviceProvisioningManager(context, settingsRepository);
        this.listener = listener;
    }

    public synchronized void start() {
        running = true;
        configure(loadSettingsQuietly());
    }

    public synchronized void configure(JSONObject settings) {
        applySettings(settings);
        if (!running) return;
        reconnectNow();
    }

    public synchronized void stop() {
        running = false;
        cancelReconnect();
        stopHeartbeat();
        closeTransports();
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
                .put("tcpHost", tcpHost)
                .put("tcpPort", tcpPort)
                .put("commandTopic", commandTopic)
                .put("responseTopic", responseTopic)
                .put("eventTopic", eventTopic)
                .put("heartbeatTopic", heartbeatTopic)
                .put("sentMessages", sentMessages)
                .put("receivedMessages", receivedMessages)
                .put("lastConnectedAt", lastConnectedAt == 0 ? JSONObject.NULL : lastConnectedAt)
                .put("lastMessageAt", lastMessageAt == 0 ? JSONObject.NULL : lastMessageAt)
                .put("lastError", lastError.isEmpty() ? JSONObject.NULL : lastError);
    }

    public void send(JSONObject payload) throws Exception {
        if (MODE_TCP.equals(transportMode)) sendTcp(payload);
        else publishMqtt(payload);
    }

    private void reconnectNow() {
        cancelReconnect();
        stopHeartbeat();
        closeTransports();
        if (MODE_TCP.equals(transportMode)) connectTcp();
        else connectMqtt();
    }

    private void connectMqtt() {
        if (brokerUri.isEmpty()) {
            updateState("DISCONNECTED", "MQTT未配置 broker 地址", null);
            return;
        }
        if (connecting) return;
        connecting = true;
        updateState("CONNECTING", "正在执行后端注册/激活流程", null);
        executor.execute(() -> {
            try {
                JSONObject provisionedSettings = provisioningManager.refreshCredentials();
                synchronized (this) {
                    applySettings(provisionedSettings);
                }
                try {
                    connectMqttWithAvailableCredentials();
                } catch (Exception authError) {
                    updateState("CONNECTING", "MQTT认证失败，正在刷新后端下发凭证", authError);
                    JSONObject refreshedSettings = provisioningManager.refreshCredentials();
                    synchronized (this) {
                        applySettings(refreshedSettings);
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
        if (brokerUri.isEmpty()) throw new IllegalStateException("MQTT未配置 broker 地址");
        Exception lastError = null;
        for (String username : mqttUsernameCandidates()) {
            MqttAsyncClient nextClient = null;
            String authLabel = mqttAuthLabel(username);
            try {
                updateState("CONNECTING", "正在连接 MQTT " + brokerUri + " auth=" + authLabel, null);
                nextClient = new MqttAsyncClient(brokerUri, clientId, new MemoryPersistence());
                final MqttAsyncClient callbackClient = nextClient;
                nextClient.setCallback(new MqttCallbackExtended() {
                    @Override public void connectComplete(boolean reconnect, String serverURI) {
                        try {
                            lastConnectedAt = System.currentTimeMillis();
                            updateState("CONNECTED", String.format(Locale.US, "MQTT已连接 %s auth=%s", serverURI, authLabel), null);
                            subscribeTopics(callbackClient);
                            sendLogin();
                            startHeartbeat();
                        } catch (Exception error) {
                            updateState("ERROR", "MQTT订阅/登录失败：" + safeMessage(error), error);
                        }
                    }

                    @Override public void connectionLost(Throwable cause) {
                        updateState("ERROR", "MQTT连接断开：" + (cause == null ? "unknown" : cause.getMessage()), cause instanceof Exception ? (Exception) cause : null);
                        if (running) scheduleReconnect();
                    }

                    @Override public void messageArrived(String topic, MqttMessage mqttMessage) {
                        handleIncoming("mqtt:" + topic, mqttMessage == null ? null : new String(mqttMessage.getPayload(), StandardCharsets.UTF_8));
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
                if (!mqttPassword.isEmpty()) options.setPassword(mqttPassword.toCharArray());
                nextClient.connect(options).waitForCompletion();
                return;
            } catch (Exception error) {
                lastError = error;
                synchronized (this) {
                    if (mqttClient == nextClient) mqttClient = null;
                }
                closeMqttQuietly(nextClient);
                updateState("CONNECTING", "MQTT认证失败 auth=" + authLabel + "：" + safeMessage(error), error);
                try { Thread.sleep(800L); } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw interrupted;
                }
            }
        }
        throw lastError == null ? new IllegalStateException("MQTT连接失败") : lastError;
    }

    private List<String> mqttUsernameCandidates() {
        ArrayList<String> candidates = new ArrayList<>();
        if (mqttUsernameConfigured) addUnique(candidates, mqttUsername);
        addUnique(candidates, "device_" + deviceCode);
        addUnique(candidates, deviceCode);
        addUnique(candidates, clientId);
        addUnique(candidates, "");
        return candidates;
    }

    private void addUnique(ArrayList<String> values, String value) {
        String normalized = value == null ? "" : value.trim();
        if (!values.contains(normalized)) values.add(normalized);
    }

    private String mqttAuthLabel(String username) {
        String value = username == null ? "" : username.trim();
        if (value.isEmpty()) return "none";
        if (value.equals("device_" + deviceCode)) return "deviceCodePrefixed";
        if (value.equals(deviceCode)) return "deviceCode";
        if (value.equals(clientId)) return "clientId";
        return "configured";
    }

    private void subscribeTopics(MqttAsyncClient client) throws Exception {
        client.subscribe(commandTopic, 1).waitForCompletion();
        if (!responseTopic.equals(commandTopic)) client.subscribe(responseTopic, 1).waitForCompletion();
    }

    private void connectTcp() {
        if (tcpHost.isEmpty() || tcpPort <= 0) {
            updateState("DISCONNECTED", "TCP未配置服务器地址或端口", null);
            return;
        }
        if (connecting) return;
        connecting = true;
        updateState("CONNECTING", String.format(Locale.US, "正在连接 TCP %s:%d", tcpHost, tcpPort), null);
        executor.execute(() -> {
            try {
                Socket nextSocket = new Socket(tcpHost, tcpPort);
                nextSocket.setKeepAlive(true);
                nextSocket.setTcpNoDelay(true);
                synchronized (this) {
                    tcpSocket = nextSocket;
                    tcpOutput = new BufferedOutputStream(nextSocket.getOutputStream());
                    connecting = false;
                    lastConnectedAt = System.currentTimeMillis();
                }
                updateState("CONNECTED", String.format(Locale.US, "TCP已连接 %s:%d", tcpHost, tcpPort), null);
                sendLogin();
                startHeartbeat();
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
            payload.put("_source", source);
            notifyMessage(payload);
            String cmd = payload.optString("cmd", "");
            if (!"heartbeatResp".equals(cmd) && !cmd.endsWith("Resp")) notifyCommand(commandFromEnvelope(payload));
        } catch (JSONException error) {
            updateState("ERROR", "后端消息不是合法JSON：" + text, error);
        }
    }

    private void sendLogin() throws Exception {
        send(new JSONObject()
                .put("cmd", "login")
                .put("data", new JSONObject()
                        .put("version", "1.0.0")
                        .put("ip", "127.0.0.1")));
    }

    private void startHeartbeat() {
        stopHeartbeat();
        heartbeatTask = executor.scheduleAtFixedRate(() -> {
            try {
                send(new JSONObject()
                        .put("cmd", "heartbeat")
                        .put("data", new JSONObject())
                        .put("timestamp", System.currentTimeMillis()));
            } catch (Exception error) {
                updateState("ERROR", "心跳发送失败：" + safeMessage(error), error);
                closeTransports();
                scheduleReconnect();
            }
        }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
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
        MqttMessage message = new MqttMessage(envelope.toString().getBytes(StandardCharsets.UTF_8));
        message.setQos(1);
        message.setRetained(false);
        mqttClient.publish(topic, message).waitForCompletion();
        sentMessages++;
    }

    private synchronized void sendTcp(JSONObject payload) throws Exception {
        if (tcpSocket == null || tcpSocket.isClosed() || tcpOutput == null) throw new IllegalStateException("TCP未连接");
        byte[] bytes = (payload.toString() + "\n").getBytes(StandardCharsets.UTF_8);
        tcpOutput.write(bytes);
        tcpOutput.flush();
        sentMessages++;
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
    }

    private void closeMqttQuietly(MqttAsyncClient currentMqtt) {
        if (currentMqtt == null) return;
        try { if (currentMqtt.isConnected()) currentMqtt.disconnectForcibly(1000, 1000); } catch (Exception ignored) { }
        try { currentMqtt.close(); } catch (Exception ignored) { }
    }

    private void applySettings(JSONObject settings) {
        deviceCode = optString(settings, "deviceCode", optString(settings, "deviceId", "DEV001")).trim();
        if (deviceCode.isEmpty()) deviceCode = optString(settings, "deviceId", "DEV001").trim();
        if (deviceCode.isEmpty()) deviceCode = "DEV001";
        deviceId = deviceCode;
        String configuredMode = optString(settings, "backendTransport", MODE_MQTT).trim().toUpperCase(Locale.US);
        transportMode = MODE_TCP.equals(configuredMode) ? MODE_TCP : MODE_MQTT;

        String configuredClientId = optString(settings, "mqttClientId", optString(settings, "clientId", "")).trim();
        String machineId = optString(settings, "machineId", "").trim();
        clientId = configuredClientId.isEmpty() ? "device_" + (machineId.isEmpty() ? deviceCode : machineId) : configuredClientId;
        mqttUsername = optString(settings, "mqttUsername", "").trim();
        mqttUsernameConfigured = !mqttUsername.isEmpty();
        mqttPassword = optString(settings, "mqttPassword", "");
        signingKey = optString(settings, "signingKey", "");
        brokerUri = buildBrokerUri(settings);
        commandTopic = topic(settings, "mqttCommandTopic", "card/" + deviceCode + "/down");
        responseTopic = topic(settings, "mqttResponseTopic", "card/" + deviceCode + "/down/response");
        eventTopic = topic(settings, "mqttEventTopic", "card/" + deviceCode + "/up");
        heartbeatTopic = topic(settings, "mqttHeartbeatTopic", "card/" + deviceCode + "/heartbeat");

        HostPort tcp = buildTcpHostPort(settings);
        tcpHost = tcp.host;
        tcpPort = tcp.port;
    }

    private String buildBrokerUri(JSONObject settings) {
        String explicit = optString(settings, "mqttBrokerUrl", "").trim();
        if (!explicit.isEmpty()) return normalizeBrokerUri(explicit, 48419);
        String address = optString(settings, "serverAddress", "").trim();
        int port = parsePort(optString(settings, "mqttPort", ""), 48419);
        if (address.isEmpty()) return "tcp://119.146.88.108:48419";
        return normalizeBrokerUri(address, port);
    }

    private HostPort buildTcpHostPort(JSONObject settings) {
        String address = optString(settings, "serverAddress", "").trim();
        int port = parsePort(optString(settings, "tcpPort", ""), 9009);
        if (address.isEmpty()) return new HostPort("", port);
        try {
            URI uri = address.contains("://") ? URI.create(address) : URI.create("tcp://" + address);
            String host = uri.getHost();
            if (host == null || host.isEmpty()) host = stripHost(address);
            int parsedPort = "tcp".equalsIgnoreCase(uri.getScheme()) && uri.getPort() > 0 ? uri.getPort() : port;
            return new HostPort(host, parsedPort);
        } catch (Exception ignored) {
            return new HostPort(stripHost(address), port);
        }
    }

    private static String normalizeBrokerUri(String value, int fallbackPort) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) return "";
        try {
            URI uri = trimmed.contains("://") ? URI.create(trimmed) : URI.create("tcp://" + trimmed);
            String scheme = "ssl".equalsIgnoreCase(uri.getScheme()) || "mqtts".equalsIgnoreCase(uri.getScheme()) ? "ssl" : "tcp";
            String host = uri.getHost();
            if (host == null || host.isEmpty()) host = stripHost(trimmed);
            int port = ("tcp".equalsIgnoreCase(uri.getScheme()) || "ssl".equalsIgnoreCase(uri.getScheme())
                    || "mqtt".equalsIgnoreCase(uri.getScheme()) || "mqtts".equalsIgnoreCase(uri.getScheme()))
                    && uri.getPort() > 0 ? uri.getPort() : fallbackPort;
            return scheme + "://" + host + ":" + port;
        } catch (Exception ignored) {
            return "tcp://" + stripHost(trimmed) + ":" + fallbackPort;
        }
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
        if (listener != null) try { listener.onStatusChanged(snapshot()); } catch (JSONException ignored) { }
    }

    private void notifyCommand(JSONObject command) {
        if (listener != null) listener.onCommand(command);
    }

    private void notifyMessage(JSONObject data) {
        if (listener != null) listener.onMessage(data);
    }

    private static int findJsonStart(StringBuilder buffer) {
        for (int index = 0; index < buffer.length(); index++) if (buffer.charAt(index) == '{') return index;
        return -1;
    }

    private static int findJsonEnd(StringBuilder buffer) {
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int index = 0; index < buffer.length(); index++) {
            char ch = buffer.charAt(index);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (ch == '\\') {
                escaped = inString;
                continue;
            }
            if (ch == '"') {
                inString = !inString;
                continue;
            }
            if (inString) continue;
            if (ch == '{') depth++;
            else if (ch == '}') {
                depth--;
                if (depth == 0) return index;
            }
        }
        return -1;
    }

    private static String stripHost(String value) {
        String result = value.replaceFirst("^[a-zA-Z]+://", "");
        int slash = result.indexOf('/');
        if (slash >= 0) result = result.substring(0, slash);
        int colon = result.indexOf(':');
        if (colon >= 0) result = result.substring(0, colon);
        return result;
    }

    private static String topic(JSONObject object, String key, String fallback) {
        String value = optString(object, key, "").trim();
        return value.isEmpty() ? fallback : value;
    }

    private JSONObject commandFromEnvelope(JSONObject envelope) throws JSONException {
        JSONObject data = envelope.optJSONObject("data");
        JSONObject command = data == null ? new JSONObject() : new JSONObject(data.toString());
        command.put("cmd", envelope.optString("cmd", command.optString("cmd", "")));
        command.put("msgId", envelope.optString("msgId", command.optString("msgId", "")));
        command.put("timestamp", envelope.optLong("timestamp", command.optLong("timestamp", 0L)));
        command.put("deviceCode", envelope.optString("deviceCode", deviceCode));
        command.put("_source", envelope.optString("_source", ""));
        command.put("_envelope", envelope);
        return command;
    }

    private JSONObject buildMqttEnvelope(JSONObject payload) throws Exception {
        String cmd = payload == null ? "" : payload.optString("cmd", "");
        if (cmd.trim().isEmpty()) throw new IllegalArgumentException("MQTT消息缺少cmd");
        JSONObject data = mqttData(payload);
        long timestamp = System.currentTimeMillis();
        String msgId = payload.optString("msgId", "");
        if (msgId.trim().isEmpty()) msgId = "msg_" + timestamp + String.format(Locale.US, "%03d", new Random().nextInt(1000));
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

    private JSONObject mqttData(JSONObject payload) throws JSONException {
        JSONObject explicit = payload.optJSONObject("data");
        if (explicit != null) return explicit;
        JSONObject data = new JSONObject();
        java.util.Iterator<String> keys = payload.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if ("cmd".equals(key) || "msgId".equals(key) || "timestamp".equals(key) || "deviceId".equals(key)
                    || "deviceCode".equals(key) || "sign".equals(key) || "_source".equals(key) || "_envelope".equals(key)) {
                continue;
            }
            data.put(key, payload.opt(key));
        }
        return data;
    }

    private String sign(String msgId, String cmd, long timestamp, String canonicalData) throws Exception {
        if (signingKey == null || signingKey.trim().isEmpty()) throw new IllegalStateException("MQTT签名密钥为空");
        String input = msgId + ":" + cmd + ":" + timestamp + ":" + canonicalData;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(signingKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.encodeToString(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    }

    private static String optString(JSONObject object, String key, String fallback) {
        return object == null ? fallback : object.optString(key, fallback);
    }

    private static int parsePort(String value, int fallback) {
        try {
            int port = Integer.parseInt(value);
            return port > 0 && port <= 65535 ? port : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String safeMessage(Exception error) {
        String value = error == null ? "" : error.getMessage();
        return value == null || value.trim().isEmpty() ? (error == null ? "unknown" : error.getClass().getSimpleName()) : value;
    }

    private static final class HostPort {
        final String host;
        final int port;
        HostPort(String host, int port) {
            this.host = host == null ? "" : host;
            this.port = port;
        }
    }
}
