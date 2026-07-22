package com.xingyao.card.core;

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
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

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
    private static final int MQTT_KEEP_ALIVE_SECONDS = 30;
    private static final int MQTT_CONNECTION_TIMEOUT_SECONDS = 10;

    private final NativeSettingsRepository settingsRepository;
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
    private String clientId = "";
    private String brokerUri = "";
    private String mqttUsername = "";
    private String mqttPassword = "";
    private String commandTopic = "";
    private String broadcastCommandTopic = "";
    private String responseTopic = "";
    private String eventTopic = "";
    private String tcpHost = "";
    private int tcpPort = 0;
    private long sentMessages;
    private long receivedMessages;
    private long lastConnectedAt;
    private long lastMessageAt;
    private String lastError = "";

    public WebSocketConnectionManager(NativeSettingsRepository settingsRepository, Listener listener) {
        this.settingsRepository = settingsRepository;
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
                .put("clientId", clientId)
                .put("brokerUri", brokerUri)
                .put("tcpHost", tcpHost)
                .put("tcpPort", tcpPort)
                .put("commandTopic", commandTopic)
                .put("responseTopic", responseTopic)
                .put("eventTopic", eventTopic)
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
        updateState("CONNECTING", "正在连接 MQTT " + brokerUri, null);
        executor.execute(() -> {
            try {
                MqttAsyncClient nextClient = new MqttAsyncClient(brokerUri, clientId, new MemoryPersistence());
                nextClient.setCallback(new MqttCallbackExtended() {
                    @Override public void connectComplete(boolean reconnect, String serverURI) {
                        try {
                            lastConnectedAt = System.currentTimeMillis();
                            updateState("CONNECTED", String.format(Locale.US, "MQTT已连接 %s", serverURI), null);
                            subscribeTopics(nextClient);
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
                synchronized (this) { mqttClient = nextClient; }
                MqttConnectOptions options = new MqttConnectOptions();
                options.setAutomaticReconnect(false);
                options.setCleanSession(true);
                options.setKeepAliveInterval(MQTT_KEEP_ALIVE_SECONDS);
                options.setConnectionTimeout(MQTT_CONNECTION_TIMEOUT_SECONDS);
                if (!mqttUsername.isEmpty()) options.setUserName(mqttUsername);
                if (!mqttPassword.isEmpty()) options.setPassword(mqttPassword.toCharArray());
                nextClient.connect(options).waitForCompletion();
                synchronized (this) { connecting = false; }
            } catch (Exception error) {
                synchronized (this) { connecting = false; }
                closeTransports();
                updateState("ERROR", "MQTT连接失败：" + safeMessage(error), error);
                scheduleReconnect();
            }
        });
    }

    private void subscribeTopics(MqttAsyncClient client) throws Exception {
        client.subscribe(commandTopic, 1).waitForCompletion();
        if (!broadcastCommandTopic.equals(commandTopic)) client.subscribe(broadcastCommandTopic, 1).waitForCompletion();
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
            if (!"heartbeatResp".equals(cmd) && !cmd.endsWith("Resp")) notifyCommand(payload);
        } catch (JSONException error) {
            updateState("ERROR", "后端消息不是合法JSON：" + text, error);
        }
    }

    private void sendLogin() throws Exception {
        send(new JSONObject()
                .put("cmd", "login")
                .put("deviceId", deviceId)
                .put("version", "1.0.0"));
    }

    private void startHeartbeat() {
        stopHeartbeat();
        heartbeatTask = executor.scheduleAtFixedRate(() -> {
            try {
                send(new JSONObject()
                        .put("cmd", "heartbeat")
                        .put("deviceId", deviceId)
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
        String topic = payload.optString("cmd", "").endsWith("Resp") ? responseTopic : eventTopic;
        MqttMessage message = new MqttMessage(payload.toString().getBytes(StandardCharsets.UTF_8));
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
        if (currentMqtt != null) {
            try { if (currentMqtt.isConnected()) currentMqtt.disconnectForcibly(1000, 1000); } catch (Exception ignored) { }
            try { currentMqtt.close(); } catch (Exception ignored) { }
        }
        try { if (tcpOutput != null) tcpOutput.close(); } catch (Exception ignored) { }
        try { if (tcpSocket != null) tcpSocket.close(); } catch (Exception ignored) { }
        tcpOutput = null;
        tcpSocket = null;
    }

    private void applySettings(JSONObject settings) {
        deviceId = optString(settings, "deviceId", "DEV001");
        String configuredMode = optString(settings, "backendTransport", MODE_MQTT).trim().toUpperCase(Locale.US);
        transportMode = MODE_TCP.equals(configuredMode) ? MODE_TCP : MODE_MQTT;

        String configuredClientId = optString(settings, "mqttClientId", "").trim();
        clientId = configuredClientId.isEmpty() ? "card-terminal-" + deviceId : configuredClientId;
        mqttUsername = optString(settings, "mqttUsername", "").trim();
        mqttPassword = optString(settings, "mqttPassword", "");
        brokerUri = buildBrokerUri(settings);
        commandTopic = topic(settings, "mqttCommandTopic", "card/" + deviceId + "/cmd");
        broadcastCommandTopic = topic(settings, "mqttBroadcastCommandTopic", "card/all/cmd");
        responseTopic = topic(settings, "mqttResponseTopic", "card/" + deviceId + "/resp");
        eventTopic = topic(settings, "mqttEventTopic", "card/" + deviceId + "/event");

        HostPort tcp = buildTcpHostPort(settings);
        tcpHost = tcp.host;
        tcpPort = tcp.port;
    }

    private String buildBrokerUri(JSONObject settings) {
        String explicit = optString(settings, "mqttBrokerUrl", "").trim();
        if (!explicit.isEmpty()) return normalizeBrokerUri(explicit, 1883);
        String address = optString(settings, "serverAddress", "").trim();
        int port = parsePort(optString(settings, "mqttPort", ""), 1883);
        if (address.isEmpty()) return "";
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
