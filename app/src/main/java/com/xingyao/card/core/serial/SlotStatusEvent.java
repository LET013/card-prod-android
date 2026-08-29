package com.xingyao.card.core.serial;

import org.json.JSONObject;

/**
 * 卡槽状态变化事件，通过 EventBus 从 {@code DeviceCoreService} 分发到 {@code JsBridgeV2}。
 *
 * <p>订阅者通过 {@code @Subscribe} 接收，将单槽状态推送到 Vue 层：
 *
 * <pre>{@code
 * @Subscribe(threadMode = ThreadMode.MAIN)
 * public void onSlotStatus(SlotStatusEvent event) {
 *     emit("slot.status", event.slot);
 * }
 * }</pre>
 */
public class SlotStatusEvent {
    /** 单个卡槽的完整状态 JSON，字段与串口协议一致 */
    public final JSONObject slot;

    public SlotStatusEvent(JSONObject slot) {
        this.slot = slot;
    }

    @Override
    public String toString() {
        int slotNumber = slot != null ? slot.optInt("slotNumber", -1) : -1;
        String status = slot != null ? slot.optString("status", "?") : "null";
        return "SlotStatusEvent{slot=" + slotNumber + ", status=" + status + "}";
    }
}
