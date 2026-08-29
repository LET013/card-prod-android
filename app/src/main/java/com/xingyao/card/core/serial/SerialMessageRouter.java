package com.xingyao.card.core.serial;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 串口消息路由器：接收原始字节、解码帧、按功能码路由。
 *
 * 帧解析（WorkCardProtocol.decode）和业务路由逻辑从原 SerialConnectionManager 迁移至此。
 * 对功能码 QUERY → SlotStateManager.onQueryResponse()
 * 对功能码 OPEN_DOOR / VERSION → 通过回调通知上层。
 */
public final class SerialMessageRouter {
    private static final String TAG = "SerialMessageRouter";

    public interface FrameCallback {
        /** 查询应答帧（卡槽状态） */
        void onQueryResponse(int address, JSONObject slotStatus);
        /** 开门应答帧 */
        void onDoorResponse(int address, boolean accepted, JSONObject frameInfo);
        /** 版本应答帧 */
        void onVersionResponse(int address, String version, JSONObject frameInfo);
        /** 收到任意帧时的调试/日志回调 */
        void onAnyFrame(JSONObject frameInfo);
    }

    private final List<Byte> inboundBuffer = new ArrayList<>();
    private FrameCallback callback;

    /** 设置帧路由回调（通常由 DeviceSerialManager 注入） */
    public void setCallback(FrameCallback callback) {
        this.callback = callback;
    }

    /**
     * 接收原始字节流，解码并路由。
     * 调用方（DeviceSerialManager）不需要关心帧边界或 CRC。
     */
    public void onRawData(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return;

        List<WorkCardProtocol.Frame> frames;
        synchronized (inboundBuffer) {
            for (byte value : bytes) inboundBuffer.add(value);
            frames = WorkCardProtocol.decode(inboundBuffer);
        }

        for (WorkCardProtocol.Frame frame : frames) {
            route(frame);
        }
    }

    /** 解析并路由单帧 */
    private void route(WorkCardProtocol.Frame frame) {
        try {
            JSONObject frameInfo = new JSONObject()
                    .put("type", "serialFrame")
                    .put("address", frame.slaveAddress)
                    .put("function", String.format("0x%02X", frame.function));
            // 查询状态帧可能每组连续返回多个，生产路径不为其构造完整 HEX。
            // 开门和版本等低频关键帧仍携带原始报文以便关联故障诊断。
            if (frame.function != WorkCardProtocol.FUNCTION_QUERY) {
                frameInfo.put("hex", WorkCardProtocol.hex(frame.raw));
            }
            if (callback != null) callback.onAnyFrame(frameInfo);

            switch (frame.function) {
                case WorkCardProtocol.FUNCTION_QUERY:
                    JSONObject slot = parseSlotStatus(frame);
                    if (callback != null) callback.onQueryResponse(frame.slaveAddress, slot);
                    break;
                case WorkCardProtocol.FUNCTION_OPEN_DOOR:
                    boolean accepted = isAccepted(frame.data);
                    frameInfo.put("command", "openDoor").put("accepted", accepted);
                    if (callback != null) callback.onDoorResponse(frame.slaveAddress, accepted, frameInfo);
                    break;
                case WorkCardProtocol.FUNCTION_VERSION:
                    String version = parseVersion(frame.data);
                    frameInfo.put("command", "version").put("version", version);
                    if (callback != null) callback.onVersionResponse(frame.slaveAddress, version, frameInfo);
                    break;
                default:
                    Log.d(TAG, "Unhandled function: 0x" + Integer.toHexString(frame.function));
                    break;
            }
        } catch (JSONException e) {
            Log.e(TAG, "Frame routing error", e);
        }
    }

    // ────────────────────── 帧解析 ──────────────────────

    private JSONObject parseSlotStatus(WorkCardProtocol.Frame frame) throws JSONException {
        int offset = WorkCardProtocol.hasFixedPrefix(frame.data) ? 4 : 0;
        if (frame.data.length < offset + 22) {
            return communicationFault(frame.slaveAddress, "状态帧长度不足");
        }
        int work = unsigned(frame.data[offset]);
        int door = unsigned(frame.data[offset + 1]);
        int card = unsigned(frame.data[offset + 2]);
        int changed = unsigned(frame.data[offset + 3]);
        String cardNo = new String(frame.data, offset + 4, 15, StandardCharsets.US_ASCII)
                .replace("\u0000", "").trim();
        int fault = unsigned(frame.data[offset + 19]);
        double voltage = unsigned(frame.data[offset + 20]) * 0.05D;
        double current = unsigned(frame.data[offset + 21]) * 0.01D;
        String status = mapStatus(work, card, fault, changed == 1, cardNo);
        return new JSONObject()
                .put("slotNumber", frame.slaveAddress)
                .put("status", status)
                .put("workCode", work)
                .put("doorCode", door)
                .put("cardCode", card)
                .put("faultMask", fault)
                .put("workStatus", mapWork(work))
                .put("presenceStatus", mapPresence(card))
                .put("doorStatus", mapDoor(door))
                .put("cardNumber", cardNo)
                .put("faultCode", fault == 0 ? "" : String.format("0x%02X", fault))
                .put("faultMessage", faultMessage(fault))
                .put("voltage", voltage)
                .put("current", current)
                .put("cardChanged", changed == 1)
                .put("updatedAt", System.currentTimeMillis());
    }

    public JSONObject communicationFault(int address, String reason) throws JSONException {
        return new JSONObject()
                .put("slotNumber", address)
                .put("status", "COMMUNICATION_FAULT")
                .put("workStatus", "通信超时")
                .put("workCode", 6)
                .put("doorCode", -1)
                .put("cardCode", -1)
                .put("faultMask", 0)
                .put("presenceStatus", "未知")
                .put("doorStatus", "未知")
                .put("faultCode", "COMM_TIMEOUT")
                .put("faultMessage", reason)
                .put("updatedAt", System.currentTimeMillis());
    }

    // ────────────────────── 协议辅助方法 ──────────────────────

    private static boolean isAccepted(byte[] data) {
        return data.length >= 5 && data[data.length - 1] == 0x11;
    }

    private static String parseVersion(byte[] data) {
        int offset = WorkCardProtocol.hasFixedPrefix(data) ? 4 : 0;
        return data.length >= offset + 4
                ? String.format("HW %d.%d / SW %d.%d",
                    unsigned(data[offset]), unsigned(data[offset + 1]),
                    unsigned(data[offset + 2]), unsigned(data[offset + 3]))
                : "未知";
    }

    static String mapStatus(int work, int card, int fault, boolean cardChanged, String cardNo) {
        if (card == 0) return "EMPTY";
        // 单板在卡片刚取走的变更帧中可能保留读卡异常码；没有卡号时以在位变化为准。
        if (card == 2 && cardChanged && (cardNo == null || cardNo.isEmpty())) return "EMPTY";
        if (fault != 0 || work == 4) return "CHARGING_FAULT";
        if (work == 6) return "COMMUNICATION_FAULT";
        if (card == 2) return "ILLEGAL_CARD";
        if (work == 2) return "CHARGING";
        if (work == 3) return "FULL";
        if (work == 5) return "CHARGING_FAULT";
        return card == 1 ? "OCCUPIED" : "EMPTY";
    }

    static String mapWork(int work) {
        return new String[]{"无效", "待机", "充电中", "充电结束", "故障", "授权到期", "通信超时"}
                [Math.min(Math.max(work, 0), 6)];
    }

    static String mapDoor(int door) {
        if (door == 1) return "开门状态";
        if (door == 2) return "关门状态";
        return "未知门状态(" + door + ")";
    }

    static String mapPresence(int card) {
        if (card == 1) return "有卡";
        if (card == 2) return "读卡错误/非法卡";
        return "无卡";
    }

    static String faultMessage(int fault) {
        if (fault == 0) return "";
        StringBuilder result = new StringBuilder();
        String[] names = {"插卡错误", "过流", "门控故障", "过压", "欠压"};
        for (int index = 0; index < names.length; index++) {
            if ((fault & (1 << index)) != 0) {
                if (result.length() > 0) result.append("、");
                result.append(names[index]);
            }
        }
        return result.toString();
    }

    private static int unsigned(byte value) { return value & 0xFF; }
}
