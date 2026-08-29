package com.xingyao.card.core.mqtt;

/** Safe transport metadata for the communication-status view; it never carries message content. */
public final class MqttTrafficEvent {
    public final String direction;
    public final String topic;
    public final String cmd;
    public final String msgId;
    public final int payloadSize;
    public final long timestamp;

    public MqttTrafficEvent(String direction, String topic, String cmd, String msgId, int payloadSize) {
        this.direction = direction == null ? "" : direction;
        this.topic = topic == null ? "" : topic;
        this.cmd = cmd == null || cmd.isEmpty() ? "unparsed" : cmd;
        this.msgId = msgId == null ? "" : msgId;
        this.payloadSize = Math.max(0, payloadSize);
        this.timestamp = System.currentTimeMillis();
    }
}
