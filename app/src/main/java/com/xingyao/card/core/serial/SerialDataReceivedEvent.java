package com.xingyao.card.core.serial;

import org.json.JSONObject;

/**
 * 串口数据接收事件，通过 EventBus 从 {@code DeviceCoreService} 分发到 {@code JsBridgeV2}。
 *
 * <p>订阅者通过 {@code @Subscribe} 接收，将串口接收帧推送到 Vue 层：
 *
 * <pre>{@code
 * @Subscribe(threadMode = ThreadMode.MAIN)
 * public void onSerialDataReceived(SerialDataReceivedEvent event) {
 *     emit("serial.dataReceived", event.data);
 * }
 * }</pre>
 *
 * <p>数据格式与 {@code DeviceSerialManager.notifyDataReceived} 传入的 JSON 一致，
 * 包含 frame、hex、timestamp 等字段。
 */
public class SerialDataReceivedEvent {
    /** 串口接收的数据帧（JSON 格式，字段取决于上游序列化） */
    public final JSONObject data;

    public SerialDataReceivedEvent(JSONObject data) {
        this.data = data;
    }

    @Override
    public String toString() {
        return "SerialDataReceivedEvent{data=" + (data != null ? data.toString() : "null") + "}";
    }
}
