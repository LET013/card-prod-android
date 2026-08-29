package com.xingyao.card.core.mqtt;

/**
 * MQTT 连接状态事件，通过 EventBus 分发。
 * 订阅者通过 {@code @Subscribe} 接收连接/断开事件。
 *
 * <pre>{@code
 * @Subscribe(threadMode = ThreadMode.MAIN)
 * public void onMqttConnection(MqttConnectionEvent event) {
 *     if (event.connected) {
 *         // 已连接
 *     } else {
 *         // 已断开
 *     }
 * }
 * }</pre>
 */
public class MqttConnectionEvent {
    /** 当前是否已连接 */
    public final boolean connected;
    /** Broker 地址 */
    public final String brokerUrl;

    public MqttConnectionEvent(boolean connected, String brokerUrl) {
        this.connected = connected;
        this.brokerUrl = brokerUrl;
    }

    @Override
    public String toString() {
        return "MqttConnectionEvent{connected=" + connected + ", broker=" + brokerUrl + "}";
    }
}
