package com.xingyao.card.core.mqtt;

import android.util.Log;

import com.xingyao.card.core.log.AppLog;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.greenrobot.eventbus.EventBus;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * MQTT 客户端，基于 Eclipse Paho {@link MqttAsyncClient}，提供：
 * <ul>
 *   <li>构造时注入连接信息（brokerUrl / clientId / username / password）</li>
 *   <li>断线自动重连（指数退避：1s → 2s → 4s → ... → 60s 上限）</li>
 *   <li>收到消息后解析 JSON 的 cmd 字段，通过 EventBus 分发 {@link MqttMessageEvent}</li>
 *   <li>连接状态变化通过 EventBus 分发 {@link MqttConnectionEvent}</li>
 *   <li>全局发送方法 {@link #sendMessage(String, JSONObject)}（cmd + data → envelope → publish）</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * XMqttClient client = new XMqttClient("tcp://10.0.0.1:1883", "device001", "user", "pass");
 * client.setPublishTopic("card/device001/up", 1);
 * client.addSubscribeTopic("card/device001/down", 1);
 * client.connect();
 *
 * // 发送消息（自动携带 msgId、时间戳、deviceCode 和 HMAC-SHA256 签名）
 * String msgId = client.sendMessage("heartbeat", new JSONObject());
 *
 * // 接收消息（在任意类中注册 EventBus）
 * EventBus.getDefault().register(this);
 *
 * @Subscribe(threadMode = ThreadMode.MAIN)
 * public void onMqttMessage(MqttMessageEvent event) {
 *     switch (event.cmd) {
 *         case "remoteOpen": handleRemoteOpen(event.data); break;
 *         // ...
 *     }
 * }
 * }</pre>
 */
public class XMqttClient {
    private static final String TAG = "XMqttClient";

    // ── 连接信息 ──
    private final String brokerUrl;
    private final String clientId;
    private final String username;
    private final String password;

    // ── V4.2 签名凭证（由 Bootstrap 在 MQTT 登录后注入）──
    private volatile String deviceCode;
    private volatile String signingKey;

    // ── Topic 配置 ──
    private final List<TopicEntry> subscribeTopics = new ArrayList<>();
    private String publishTopic;
    private int publishQos = 1;

    // ── Paho 客户端 ──
    private MqttAsyncClient mqttClient;

    // ── 状态 ──
    private volatile boolean connected = false;
    private volatile boolean manualDisconnect = false;
    private volatile boolean released = false;

    // ── 重连（指数退避）──
    private final ScheduledExecutorService reconnectExecutor;
    private ScheduledFuture<?> reconnectFuture;
    private int reconnectAttempts = 0;
    private static final long INITIAL_RECONNECT_DELAY_MS = 1000L;
    private static final long MAX_RECONNECT_DELAY_MS = 60_000L;

    /** 服务端 config 可覆盖: {@code mqttReconnectInitialInterval} 默认 1000ms */
    private volatile long reconnectInitialDelayMs = INITIAL_RECONNECT_DELAY_MS;
    /** 服务端 config 可覆盖: {@code mqttReconnectMaxInterval} 默认 60000ms */
    private volatile long reconnectMaxDelayMs = MAX_RECONNECT_DELAY_MS;

    // ── 连接配置 ──
    private int keepAliveSeconds = 60;
    private int connectionTimeoutSeconds = 10;
    private boolean cleanSession = true;

    // ── 全局单例（可选）──
    private static volatile XMqttClient sInstance;

    /* ==================== 构造 ==================== */

    /**
     * @param brokerUrl MQTT broker 地址，如 {@code "tcp://10.0.0.1:1883"} 或 {@code "ssl://host:8883"}
     * @param clientId  客户端 ID（必须唯一）
     * @param username  MQTT 用户名（可为 null 或空串表示匿名）
     * @param password  MQTT 密码（可为 null 或空串）
     */
    public XMqttClient(String brokerUrl, String clientId, String username, String password) {
        this.brokerUrl = brokerUrl;
        this.clientId = clientId;
        this.username = username;
        this.password = password;
        this.reconnectExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "XMqttClient-Reconnect");
            t.setDaemon(true);
            return t;
        });
    }

    /* ==================== Topic 配置 ==================== */

    /**
     * 添加订阅 Topic（需在 {@link #connect()} 之前调用）。
     * 也支持 connect 后动态订阅（立即生效）。
     */
    public void addSubscribeTopic(String topic, int qos) {
        synchronized (subscribeTopics) {
            subscribeTopics.add(new TopicEntry(topic, qos));
        }
        // 如果已连接，立即订阅
        if (connected && mqttClient != null && mqttClient.isConnected()) {
            doSubscribe(topic, qos);
        }
    }

    /** 设置发布消息的默认 Topic。 */
    public void setPublishTopic(String topic, int qos) {
        this.publishTopic = topic;
        this.publishQos = qos;
    }

    /* ==================== 连接参数配置 ==================== */

    public void setKeepAliveSeconds(int seconds) { this.keepAliveSeconds = seconds; }
    public void setConnectionTimeoutSeconds(int seconds) { this.connectionTimeoutSeconds = seconds; }
    public void setCleanSession(boolean cleanSession) { this.cleanSession = cleanSession; }

    /* ==================== 生命周期 ==================== */

    /**
     * 连接 Broker，成功后自动订阅已注册的 Topic。
     * 断线会自动重连（指数退避）。
     */
    public synchronized void connect() {
        if (released) {
            Log.w(TAG, "Already released, cannot connect");
            return;
        }
        manualDisconnect = false;
        reconnectAttempts = 0;
        doConnect();
    }

    /**
     * 主动断开连接，不自动重连。
     */
    public synchronized void disconnect() {
        manualDisconnect = true;
        cancelReconnect();
        closeMqttClient();
        setConnected(false);
    }

    /**
     * 释放所有资源，此后不可再使用。
     */
    public synchronized void release() {
        released = true;
        disconnect();
        reconnectExecutor.shutdown();
        try {
            reconnectExecutor.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        if (sInstance == this) sInstance = null;
    }

    public boolean isConnected() {
        return connected && mqttClient != null && mqttClient.isConnected();
    }

    /* ==================== 凭证注入 ==================== */

    /**
     * 注入 V4.2 envelope 签名凭证。由 Bootstrap 在 MQTT 登录成功后调用。
     * 设置后，所有 sendMessage 调用自动携带 deviceCode 和 HMAC-SHA256 签名。
     */
    public void setCredentials(String deviceCode, String signingKey) {
        this.deviceCode = deviceCode;
        this.signingKey = signingKey;
        Log.d(TAG, "Credentials set: deviceCode=" + (deviceCode != null ? deviceCode : "NULL")
                + " signingKey=" + (signingKey != null ? "***" : "NULL"));
    }

    /* ==================== 发送消息 ==================== */

    /**
     * 发送一条 V4.2 envelope 上行消息（自动生成 msgId）。
     *
     * @see #sendMessage(String, JSONObject, String)
     */
    public String sendMessage(String cmd, JSONObject data) throws MqttException, JSONException {
        return sendMessage(cmd, data, null);
    }

    /**
     * 发送一条 V4.2 envelope 上行消息。
     * 若已通过 {@link #setCredentials} 注入签名凭证，则自动附加 deviceCode 和签名。
     * msgId 由调用方指定（用于请求-应答匹配），传 null 则自动生成。
     *
     * @param cmd   命令字（必填）
     * @param data  payload 数据（可为 null）
     * @param msgId 消息 ID（null 则自动生成）
     * @return 实际写入 envelope 的 msgId，供调用方做响应匹配
     * @throws MqttException 未连接或 topic 未设置时抛出
     * @throws JSONException 若签名计算过程中 JSON 序列化失败
     */
    public String sendMessage(String cmd, JSONObject data, String msgId) throws MqttException, JSONException {
        if (publishTopic == null || publishTopic.isEmpty()) {
            throw new MqttException(MqttException.REASON_CODE_CLIENT_EXCEPTION);
        }
        if (!isConnected()) {
            throw new MqttException(MqttException.REASON_CODE_CLIENT_NOT_CONNECTED);
        }
        MqttEnvelope.Builder builder = new MqttEnvelope.Builder(cmd)
                .msgId(msgId)
                .data(data);
        if (deviceCode != null && !deviceCode.isEmpty()) {
            builder.deviceCode(deviceCode);
        }
        if (signingKey != null && !signingKey.isEmpty()) {
            builder.signingKey(signingKey);
        }
        MqttEnvelope envelope = builder.build();
        MqttMessage message = new MqttMessage(envelope.toBytes());
        message.setQos(publishQos);
        message.setRetained(false);
        mqttClient.publish(publishTopic, message);
        postTraffic("TX", publishTopic, cmd, envelope.msgId, message.getPayload().length);
        Log.d(TAG, "Published to " + publishTopic + ": cmd=" + cmd
                + " msgId=" + envelope.msgId
                + " signed=" + (signingKey != null && !signingKey.isEmpty()));
        return envelope.msgId;
    }

    /**
     * 发布原始 MQTT 报文到指定 Topic。用于发送已签名/已组装的 envelope 消息。
     *
     * @param topic   目标 Topic
     * @param payload 报文 body（UTF-8 字节）
     * @param qos     QoS 等级（0/1/2）
     * @param retained 是否保留
     */
    public void publish(String topic, byte[] payload, int qos, boolean retained) {
        if (!isConnected()) {
            Log.w(TAG, "Not connected, cannot publish");
            return;
        }
        if (topic == null || topic.isEmpty()) {
            Log.w(TAG, "publish: topic is null/empty");
            return;
        }
        try {
            MqttMessage msg = new MqttMessage(payload != null ? payload : new byte[0]);
            msg.setQos(qos);
            msg.setRetained(retained);
            mqttClient.publish(topic, msg);
            postPayloadTraffic("TX", topic, msg.getPayload());
            Log.d(TAG, "Published raw to " + topic + " (" + (payload != null ? payload.length : 0) + " bytes)");
        } catch (MqttException e) {
            Log.e(TAG, "Failed to publish to " + topic, e);
        }
    }

    /* ==================== 全局单例（可选） ==================== */

    public static void setInstance(XMqttClient instance) { sInstance = instance; }

    public static XMqttClient getInstance() {
        if (sInstance == null) {
            throw new IllegalStateException("XMqttClient instance not set. Call XMqttClient.setInstance() first.");
        }
        return sInstance;
    }

    /* ==================== 内部实现 ==================== */

    private void doConnect() {
        closeMqttClient();
        try {
            mqttClient = new MqttAsyncClient(brokerUrl, clientId, new MemoryPersistence());
            mqttClient.setCallback(new MqttCallbackExtended() {
                @Override
                public void connectComplete(boolean reconnect, String serverURI) {
                    reconnectAttempts = 0;
                    subscribeInitialTopics(reconnect);
                }

                @Override
                public void connectionLost(Throwable cause) {
                    setConnected(false);
                    AppLog.w(TAG, "MQTT 连接断开: "
                            + (cause != null ? cause.getClass().getSimpleName() : "unknown"));
                    if (!manualDisconnect && !released) {
                        scheduleReconnect();
                    }
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    handleMessage(topic, message);
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                    // no-op
                }
            });

            MqttConnectOptions options = new MqttConnectOptions();
            options.setAutomaticReconnect(false); // 手动管理重连
            options.setCleanSession(cleanSession);
            options.setKeepAliveInterval(keepAliveSeconds);
            options.setConnectionTimeout(connectionTimeoutSeconds);
            if (username != null && !username.isEmpty()) {
                options.setUserName("device_"+username);
            }
            if (password != null && !password.isEmpty()) {
                options.setPassword(password.toCharArray());
            }

            Log.i(TAG, "Connecting to " + brokerUrl + " as " + clientId);
            mqttClient.connect(options); // 异步连接，回调在 connectComplete 中
        } catch (MqttException e) {
            AppLog.e(TAG, "MQTT 连接失败", e);
            setConnected(false);
            if (!manualDisconnect && !released) {
                scheduleReconnect();
            }
        }
    }

    private void doSubscribe(String topic, int qos) {
        if (mqttClient == null || !mqttClient.isConnected()) return;
        try {
            mqttClient.subscribe(topic, qos);
            Log.i(TAG, "Subscribed to " + topic + " (qos=" + qos + ")");
        } catch (MqttException e) {
            Log.e(TAG, "Failed to subscribe to " + topic, e);
        }
    }

    private void handleMessage(String topic, MqttMessage message) {
        byte[] payload = message != null && message.getPayload() != null ? message.getPayload() : new byte[0];
        try {
            String raw = new String(payload, StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(raw);
            String cmd = json.optString("cmd", "");
            JSONObject data = json.optJSONObject("data");
            String msgId = json.optString("msgId", null);
            long timestamp = json.optLong("timestamp", 0);
            postTraffic("RX", topic, cmd, msgId, payload.length);

            // 最新下行协议将业务字段平铺在 envelope 顶层，旧 data 仅作无损兼容读取。
            if (data == null) {
                data = new JSONObject(json.toString());
                data.remove("msgId");
                data.remove("cmd");
                data.remove("timestamp");
                data.remove("deviceCode");
                data.remove("sign");
                data.remove("raw");
            }

            // 通过 EventBus 分发
            MqttMessageEvent event = new MqttMessageEvent(cmd, data, topic, msgId, timestamp);
            EventBus.getDefault().post(event);
            Log.d(TAG, "Received MQTT message: " + event);
        } catch (JSONException e) {
            postTraffic("RX", topic, "unparsed", null, payload.length);
            Log.e(TAG, "Failed to parse incoming MQTT message as JSON", e);
        } catch (Exception e) {
            Log.e(TAG, "Failed to handle MQTT message", e);
        }
    }

    private void postPayloadTraffic(String direction, String topic, byte[] payload) {
        int payloadSize = payload == null ? 0 : payload.length;
        try {
            JSONObject envelope = new JSONObject(new String(payload == null ? new byte[0] : payload, StandardCharsets.UTF_8));
            postTraffic(direction, topic, envelope.optString("cmd", ""), envelope.optString("msgId", null), payloadSize);
        } catch (JSONException ignored) {
            postTraffic(direction, topic, "unparsed", null, payloadSize);
        }
    }

    private void postTraffic(String direction, String topic, String cmd, String msgId, int payloadSize) {
        EventBus.getDefault().post(new MqttTrafficEvent(direction, topic, cmd, msgId, payloadSize));
    }

    /** 初始下行订阅全部确认后，才允许 Bootstrap 发送 login。 */
    private void subscribeInitialTopics(boolean reconnect) {
        final MqttAsyncClient client = mqttClient;
        if (client == null || !client.isConnected()) return;
        final List<TopicEntry> initialTopics;
        synchronized (subscribeTopics) {
            initialTopics = new ArrayList<>(subscribeTopics);
        }
        if (initialTopics.isEmpty()) {
            completeInitialSubscriptions(client, reconnect);
            return;
        }

        final int[] pending = {initialTopics.size()};
        final boolean[] failed = {false};
        for (TopicEntry entry : initialTopics) {
            try {
                client.subscribe(entry.topic, entry.qos, null, new IMqttActionListener() {
                    @Override
                    public void onSuccess(org.eclipse.paho.client.mqttv3.IMqttToken asyncActionToken) {
                        onInitialSubscribeFinished(client, reconnect, pending, failed, false, entry.topic);
                    }

                    @Override
                    public void onFailure(org.eclipse.paho.client.mqttv3.IMqttToken asyncActionToken,
                                          Throwable exception) {
                        Log.e(TAG, "Failed to subscribe to " + entry.topic, exception);
                        onInitialSubscribeFinished(client, reconnect, pending, failed, true, entry.topic);
                    }
                });
            } catch (MqttException exception) {
                Log.e(TAG, "Failed to start subscription for " + entry.topic, exception);
                onInitialSubscribeFinished(client, reconnect, pending, failed, true, entry.topic);
            }
        }
    }

    private void onInitialSubscribeFinished(MqttAsyncClient client, boolean reconnect, int[] pending, boolean[] failed,
                                             boolean subscriptionFailed, String topic) {
        synchronized (pending) {
            failed[0] |= subscriptionFailed;
            pending[0]--;
            if (pending[0] > 0) return;
        }
        if (mqttClient != client) return;
        if (failed[0]) {
            setConnected(false);
            if (!manualDisconnect && !released) scheduleReconnect();
            return;
        }
        completeInitialSubscriptions(client, reconnect);
    }

    private void completeInitialSubscriptions(MqttAsyncClient client, boolean reconnect) {
        if (mqttClient != client) return;
        setConnected(true);
        AppLog.i(TAG, reconnect ? "MQTT 重连成功" : "MQTT 连接成功");
    }

    /**
     * 设置重连延迟参数（应在 connect() 之前调用）。
     * 服务端 config 默认值: {@code mqttReconnectInitialInterval=1000}, {@code mqttReconnectMaxInterval=60000}
     */
    public void setReconnectDelays(long initialMs, long maxMs) {
        if (initialMs >= 100 && maxMs >= initialMs) {
            this.reconnectInitialDelayMs = initialMs;
            this.reconnectMaxDelayMs = maxMs;
        } else {
            Log.w(TAG, "Invalid reconnect delays: init=" + initialMs + "ms, max=" + maxMs
                    + "ms, keeping defaults: init=" + INITIAL_RECONNECT_DELAY_MS
                    + "ms, max=" + MAX_RECONNECT_DELAY_MS + "ms");
        }
    }

    private void scheduleReconnect() {
        if (manualDisconnect || released) return;
        cancelReconnect();

        long delay = Math.min(
                reconnectInitialDelayMs * (1L << Math.min(reconnectAttempts, 10)),
                reconnectMaxDelayMs);
        reconnectAttempts++;

        Log.i(TAG, "Scheduling reconnect #" + reconnectAttempts + " in " + delay + "ms");
        reconnectFuture = reconnectExecutor.schedule(() -> {
            if (!manualDisconnect && !released) {
                Log.i(TAG, "Reconnecting (attempt " + reconnectAttempts + ")...");
                doConnect();
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void cancelReconnect() {
        if (reconnectFuture != null && !reconnectFuture.isDone()) {
            reconnectFuture.cancel(true);
            reconnectFuture = null;
        }
    }

    private void closeMqttClient() {
        MqttAsyncClient client = mqttClient;
        mqttClient = null;
        if (client != null) {
            try {
                if (client.isConnected()) client.disconnect();
            } catch (MqttException e) {
                Log.w(TAG, "Error disconnecting MQTT client", e);
            }
            try {
                client.close();
            } catch (MqttException e) {
                Log.w(TAG, "Error closing MQTT client", e);
            }
        }
    }

    private void setConnected(boolean isConnected) {
        if (connected != isConnected) {
            connected = isConnected;
            EventBus.getDefault().post(new MqttConnectionEvent(isConnected, brokerUrl));
        }
    }

    /* ==================== 内部类 ==================== */

    private static class TopicEntry {
        final String topic;
        final int qos;

        TopicEntry(String topic, int qos) {
            this.topic = topic;
            this.qos = qos;
        }
    }
}
