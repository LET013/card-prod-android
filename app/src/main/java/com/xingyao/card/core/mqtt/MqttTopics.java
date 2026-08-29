package com.xingyao.card.core.mqtt;

/**
 * MQTT Topic 统一管理。
 *
 * <p>Topic 模式: {@code card/{deviceCode}/{suffix}}
 *
 * <p>使用方式：
 * <pre>{@code
 * mqttClient.setPublishTopic(MqttTopics.up(deviceCode), 1);
 * mqttClient.addSubscribeTopic(MqttTopics.down(deviceCode), 1);
 * mqttClient.publish(MqttTopics.up(deviceCode), payload, 1, false);
 * }</pre>
 */
public final class MqttTopics {
    private MqttTopics() {}

    /** Topic 前缀 */
    public static final String PREFIX = "card";

    // --- 后缀常量 ---
    public static final String SUFFIX_UP = "up";
    public static final String SUFFIX_DOWN = "down";
    public static final String SUFFIX_DOWN_RESPONSE = "down/response";
    public static final String SUFFIX_HEARTBEAT = "heartbeat";

    // --- QoS 默认值 ---
    public static final int QOS_UP = 1;
    public static final int QOS_DOWN = 1;
    public static final int QOS_DOWN_RESPONSE = 1;
    public static final int QOS_HEARTBEAT = 0;

    // --- 完整 Topic 构造方法 ---

    /** 上行发布 Topic: {@code card/{deviceCode}/up} */
    public static String up(String deviceCode) {
        return PREFIX + "/" + deviceCode + "/" + SUFFIX_UP;
    }

    /** 下行订阅 Topic: {@code card/{deviceCode}/down} */
    public static String down(String deviceCode) {
        return PREFIX + "/" + deviceCode + "/" + SUFFIX_DOWN;
    }

    /** 下行响应订阅 Topic: {@code card/{deviceCode}/down/response} */
    public static String downResponse(String deviceCode) {
        return PREFIX + "/" + deviceCode + "/" + SUFFIX_DOWN_RESPONSE;
    }

    /** 历史心跳 Topic 兼容辅助方法；当前心跳统一使用 {@link #up(String)}。 */
    public static String heartbeat(String deviceCode) {
        return PREFIX + "/" + deviceCode + "/" + SUFFIX_HEARTBEAT;
    }
}
