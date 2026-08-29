package com.xingyao.card.core.mqtt;

import android.util.Log;

import com.xingyao.card.core.bootstrap.CredentialStore;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MQTT 服务抽象基类。
 *
 * <p>为所有需要 MQTT 通信的业务模块提供统一的基础能力：
 * <ul>
 *   <li><b>EventBus 生命周期管理</b> — {@link #register()} / {@link #unregister()} 自动注册/注销 Subscriber</li>
 *   <li><b>签名信封发送</b> — {@link #sendSignedEnvelope(String, JSONObject)} 一键构建并发送带签名的 MQTT 消息</li>
 *   <li><b>心跳发送</b> — {@link #sendHeartbeat(JSONObject)} 通过统一上行 topic 发布</li>
 *   <li><b>请求-应答模式</b> — {@link #sendAndWaitReply(String, JSONObject, long, String)} 发送消息后阻塞等待指定 cmd 的响应</li>
 *   <li><b>消息分发</b> — {@link #handleMqttMessage(String, JSONObject, String)} 模板方法供子类处理下行消息</li>
 * </ul>
 *
 * <h3>设计约定</h3>
 * <ul>
 *   <li>子类通过构造函数注入 {@link XMqttClient}、deviceCode 和 {@link CredentialStore}</li>
 *   <li>所有 MQTT 消息统一通过 {@link MqttEnvelope.Builder} 构建</li>
 *   <li>业务消息和心跳均发布到 {@link MqttTopics#up(String)}</li>
 *   <li>下行消息通过 EventBus {@link MqttMessageEvent} 接收，基类自动分发到 {@link #handleMqttMessage}</li>
 * </ul>
 *
 * <h3>使用示例：心跳模块</h3>
 * <pre>{@code
 * public class HeartbeatManager extends BaseMqttService {
 *     private final Handler handler = new Handler(Looper.getMainLooper());
 *     private boolean running;
 *
 *     public HeartbeatManager(XMqttClient mqtt, String code, CredentialStore store) {
 *         super(mqtt, code, store);
 *     }
 *
 *     void start() {
 *         running = true;
 *         sendHeartbeat(new JSONObject());  // 继承的方法
 *         scheduleNext();
 *     }
 *
 *     private void scheduleNext() {
 *         if (running) handler.postDelayed(() -> {
 *             sendHeartbeat(new JSONObject());
 *             scheduleNext();
 *         }, 60_000);
 *     }
 * }
 * }</pre>
 *
 * <h3>使用示例：业务指令模块（带下行消息处理）</h3>
 * <pre>{@code
 * public class CardService extends BaseMqttService {
 *     public CardService(XMqttClient mqtt, String code, CredentialStore store) {
 *         super(mqtt, code, store);
 *         register();  // 开始接收 MQTT 下行消息
 *     }
 *
 *     public void ejectSlot(int slot) throws JSONException {
 *         JSONObject data = new JSONObject();
 *         data.put("slotId", slot);
 *         sendSignedEnvelope(MqttCmd.REMOTE_EJECT, data);
 *     }
 *
 *     // 重写以处理下行消息
 *     protected void handleMqttMessage(String cmd, JSONObject data, String topic) {
 *         switch (cmd) {
 *             case MqttCmd.CARD_EVENT_RESP:
 *                 // 处理取卡/还卡响应...
 *                 break;
 *         }
 *     }
 * }
 * }</pre>
 */
public abstract class BaseMqttService {
    private static final String TAG = "BaseMqttService";

    /** MQTT 客户端（可为 null，MQTT 未连接时发送会静默跳过） */
    protected volatile XMqttClient mqttClient;

    /** 设备编码（用于构造 topic 和 envelope） */
    protected final String deviceCode;

    /** 凭证存储（用于读取 signingKey 构造签名） */
    protected final CredentialStore credentialStore;

    protected BaseMqttService(XMqttClient mqttClient, String deviceCode,
                              CredentialStore credentialStore) {
        this.mqttClient = mqttClient;
        this.deviceCode = deviceCode;
        this.credentialStore = credentialStore;
    }

    // ==================== EventBus 生命周期 ====================

    /**
     * 注册 EventBus Subscriber（通常在构造函数或 onCreate 中调用）。
     * 注册后基类的 {@link #onMqttMessageEvent(MqttMessageEvent)} 会自动将
     * MQTT 消息分发到 {@link #handleMqttMessage(String, JSONObject, String)}。
     * <p>
     * <b>注意：必须与 {@link #unregister()} 配对调用。</b>
     */
    protected void register() {
        EventBus.getDefault().register(this);
    }

    /**
     * 注销 EventBus Subscriber（通常在 onDestroy / shutdown 中调用）。
     */
    protected void unregister() {
        EventBus.getDefault().unregister(this);
    }

    // ==================== EventBus 消息接收 → 子类分发 ====================

    /**
     * 接收所有 MQTT 下行消息，分发到子类的 {@link #handleMqttMessage}。
     * 子类不应重写此方法；如需处理特定 cmd，请重写 {@link #handleMqttMessage}。
     */
    @Subscribe(threadMode = ThreadMode.MAIN)
    public final void onMqttMessageEvent(MqttMessageEvent event) {
        if (event.cmd != null) {
            handleMqttMessage(event.cmd, event.data, event.topic);
        }
    }

    /**
     * 子类在此处理 MQTT 下行消息（基类提供空实现，按需重写）。
     * 在 EventBus 主线程回调。
     *
     * @param cmd   消息中的 cmd 字段
     * @param data  消息中的 data 字段（payload），可能为 null
     * @param topic 消息来源 topic
     */
    protected void handleMqttMessage(String cmd, JSONObject data, String topic) {
        // 默认空实现，子类按需重写
    }

    // ==================== 上行消息发送 ====================

    /**
     * 构建签名信封并发布到上行 topic ({@link MqttTopics#up(String)}, QoS 1)。
     * msgId 自动生成为 "mq_{8位UUID前缀}"。
     *
     * @param cmd  上行命令字（如 {@link MqttCmd#HEARTBEAT}）
     * @param data 消息 payload（不可为 null）
     * @throws JSONException 签名或序列化异常
     */
    protected void sendSignedEnvelope(String cmd, JSONObject data) throws JSONException {
        sendSignedEnvelope(cmd, "mq", data);
    }

    /**
     * 构建签名信封并发布到上行 topic，可自定义 msgId 前缀。
     *
     * @param cmd          上行命令字
     * @param msgIdPrefix  msgId 前缀（如 "hb" → "hb_a1b2c3d4"）
     * @param data         消息 payload
     * @throws JSONException 签名或序列化异常
     */
    protected void sendSignedEnvelope(String cmd, String msgIdPrefix,
                                      JSONObject data) throws JSONException {
        XMqttClient client = mqttClient;
        if (client == null || !client.isConnected()) {
            Log.w(TAG, "MQTT not connected, skip send: cmd=" + cmd);
            return;
        }

        try {
            MqttEnvelope envelope = new MqttEnvelope.Builder(cmd, deviceCode)
                    .msgId(msgIdPrefix + "_" + UUID.randomUUID().toString().substring(0, 8))
                    .signingKey(credentialStore.getSigningKey())
                    .data(data != null ? data : new JSONObject())
                    .build();

            String topic = MqttTopics.up(deviceCode);
            client.publish(topic, envelope.toBytes(), MqttTopics.QOS_UP, false);
            Log.d(TAG, "Sent " + cmd + " to " + topic + " msgId=" + envelope.msgId);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to build/send envelope for " + cmd, e);
            throw e;
        }
    }

    /** 心跳沿统一上行 Topic 发送，并在业务 raw 中携带客户端毫秒时间戳。 */
    protected void sendHeartbeat(JSONObject data) throws JSONException {
        JSONObject payload = data == null ? new JSONObject() : new JSONObject(data.toString());
        if (!payload.has("seq")) payload.put("seq", System.currentTimeMillis());
        sendSignedEnvelope(MqttCmd.HEARTBEAT, "hb", payload);
    }

    // ==================== 请求-应答模式 ====================

    /**
     * 发送 MQTT 请求并阻塞等待指定 cmd 的响应。
     *
     * <p>用于需要同步等待服务端应答的场景（如 MQTT 登录）。
     * <b>必须在后台线程调用</b>，内部使用 CountDownLatch 阻塞。
     *
     * @param cmd       上行命令字
     * @param data      请求 payload
     * @param timeoutMs 等待超时（毫秒）
     * @param replyCmd  期望的响应 cmd（如 {@link MqttCmd#LOGIN_RESP}）
     * @return 响应中的 data 字段，超时或失败的响应为 null
     * @throws InterruptedException 等待被中断
     * @throws JSONException        签名或序列化异常
     */
    protected MqttReplyResult sendAndWaitReply(String cmd, JSONObject data,
                                                long timeoutMs, String replyCmd)
            throws InterruptedException, JSONException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> replyMsg = new AtomicReference<>(null);
        AtomicReference<JSONObject> replyData = new AtomicReference<>(null);

        Object subscriber = new Object() {
            @Subscribe(threadMode = ThreadMode.MAIN)
            public void onReply(MqttMessageEvent event) {
                if (replyCmd.equals(event.cmd)) {
                    if (event.data != null) {
                        int code = event.data.optInt("code", -1);
                        if (code != 0) {
                            replyMsg.set(event.data.optString("message",
                                    event.data.optString("msg", "请求失败")));
                        }
                        replyData.set(event.data);
                    }
                    latch.countDown();
                }
            }
        };

        try {
            EventBus.getDefault().register(subscriber);
            sendSignedEnvelope(cmd, data);
            boolean received = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            if (!received) {
                return new MqttReplyResult(false, "请求超时（" + timeoutMs + "ms）", null);
            }
            if (replyMsg.get() != null) {
                return new MqttReplyResult(false, replyMsg.get(), replyData.get());
            }
            return new MqttReplyResult(true, null, replyData.get());
        } finally {
            EventBus.getDefault().unregister(subscriber);
        }
    }

    /**
     * 请求-应答返回的结果封装。
     */
    public static class MqttReplyResult {
        /** 请求是否成功 */
        public final boolean success;
        /** 失败时的错误描述；success 为 true 时为 null */
        public final String errorMessage;
        /** 响应 data（未解包，可能为 null） */
        public final JSONObject rawData;

        public MqttReplyResult(boolean success, String errorMessage, JSONObject rawData) {
            this.success = success;
            this.errorMessage = errorMessage;
            this.rawData = rawData;
        }
    }
}
