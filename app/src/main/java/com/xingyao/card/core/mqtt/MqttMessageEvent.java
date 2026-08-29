package com.xingyao.card.core.mqtt;

import org.json.JSONObject;

/**
 * MQTT 消息事件，通过 EventBus 分发。
 * 订阅者通过 {@code @Subscribe} 接收，根据 {@link #cmd} 判断是否处理。
 *
 * <pre>{@code
 * @Subscribe(threadMode = ThreadMode.MAIN)
 * public void onMqttMessage(MqttMessageEvent event) {
 *     if ("someCommand".equals(event.cmd)) {
 *         JSONObject payload = event.data;
 *         // handle...
 *     }
 * }
 * }</pre>
 */
public class MqttMessageEvent {
    /** 消息中的 cmd 字段，用于路由到对应处理器 */
    public final String cmd;
    /** 消息中的 data 字段（payload），可能为 null */
    public final JSONObject data;
    /** 消息来源 MQTT topic */
    public final String topic;
    /** 消息唯一标识（来自 payload 的 msgId），用于幂等/去重/日志追踪，可能为 null */
    public final String msgId;
    /** 服务端时间戳（毫秒），可能为 0 */
    public final long timestamp;

    public MqttMessageEvent(String cmd, JSONObject data, String topic,
                            String msgId, long timestamp) {
        this.cmd = cmd;
        this.data = data;
        this.topic = topic;
        this.msgId = msgId;
        this.timestamp = timestamp;
    }

    @Override
    public String toString() {
        return "MqttMessageEvent{cmd=" + cmd + ", msgId=" + msgId + ", topic=" + topic + "}";
    }
}
