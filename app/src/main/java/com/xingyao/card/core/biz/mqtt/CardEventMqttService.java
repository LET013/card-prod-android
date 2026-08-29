package com.xingyao.card.core.biz.mqtt;

import android.util.Log;

import com.xingyao.card.core.bootstrap.CredentialStore;
import com.xingyao.card.core.mqtt.BaseMqttService;
import com.xingyao.card.core.mqtt.MqttCmd;
import com.xingyao.card.core.mqtt.XMqttClient;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 卡片事件 MQTT 服务。
 *
 * <p>负责：
 * <ul>
 *   <li><b>上行</b>：{@code cardEvent} — 取卡/还卡事件</li>
 *   <li><b>下行处理</b>：{@code remoteOpen} — 远程开锁单卡槽；{@code remoteEjectAll} — 一键全弹</li>
 * </ul>
 *
 * <p>文档 V4.2 §4.4.1-4.4.2（MQTT 指令）及 §4.4.3（HTTP 降级）。
 */
public class CardEventMqttService extends BaseMqttService {

    private static final String TAG = "CardEventMqttService";

    /** 卡片事件回调 */
    public interface Callback {
        /**
         * 远程开锁指定卡槽。
         * @param slotId 卡槽编号
         * @param cardNo 卡号（可选，安全校验用）
         * @return 开锁结果，用于构造 remoteOpenResp 的 data
         */
        JSONObject onRemoteOpen(int slotId, String cardNo);

        /**
         * 一键弹出所有卡。
         * @return 全弹结果，用于构造 remoteEjectAllResp 的 data
         */
        JSONObject onRemoteEjectAll();

        /**
         * 卡片事件上报成功回调。
         */
        void onCardEventAck(String msgId);
    }

    private final Callback callback;

    public CardEventMqttService(XMqttClient mqttClient, String deviceCode,
                                CredentialStore credentialStore, Callback callback) {
        super(mqttClient, deviceCode, credentialStore);
        this.callback = callback;
        register();
    }

    /**
     * 发送卡片事件（上行走 MQTT cardEvent）。
     *
     * @param cardNo     卡号
     * @param eventType  事件类型：take / return
     * @param slotId     卡槽编号
     * @param timestamp  事件时间戳 (ms)
     * @param authType   验证方式：face / finger / card / manual
     */
    public void sendCardEvent(String cardNo, String eventType, int slotId,
                               long timestamp, String authType) {
        try {
            JSONObject data = new JSONObject();
            data.put("cardNo", cardNo);
            data.put("eventType", eventType);
            data.put("slotId", slotId);
            data.put("timestamp", timestamp);
            if (authType != null) {
                data.put("authType", authType);
            }
            sendSignedEnvelope(MqttCmd.CARD_EVENT, "card", data);
        } catch (JSONException e) {
            Log.e(TAG, "sendCardEvent JSON error", e);
        }
    }

    /**
     * 响应远程操作结果。
     */
    public void sendRemoteResult(String cmd, JSONObject result) {
        try {
            sendSignedEnvelope(cmd, result != null ? result : new JSONObject());
        } catch (JSONException e) {
            Log.e(TAG, "sendRemoteResult JSON error", e);
        }
    }

    @Override
    public void handleMqttMessage(String cmd, JSONObject data, String topic) {
        if (callback == null) return;

        try {
            switch (cmd) {
                case MqttCmd.CARD_EVENT_RESP:
                    Log.i(TAG, "Card event ack received");
                    callback.onCardEventAck(data.optString("msgId"));
                    break;

                case MqttCmd.REMOTE_OPEN: {
                    int slotId = data.optInt("slotId", -1);
                    String cardNo = data.optString("cardNo", null);
                    Log.i(TAG, "remoteOpen slotId=" + slotId + " cardNo=" + cardNo);
                    JSONObject result = callback.onRemoteOpen(slotId, cardNo);
                    sendRemoteResult(MqttCmd.REMOTE_OPEN_RESP, result);
                    break;
                }

                case MqttCmd.REMOTE_EJECT_ALL: {
                    Log.i(TAG, "remoteEjectAll");
                    JSONObject result = callback.onRemoteEjectAll();
                    sendRemoteResult(MqttCmd.REMOTE_EJECT_ALL_RESP, result);
                    break;
                }

                default:
                    Log.d(TAG, "Unhandled cmd: " + cmd);
            }
        } catch (Exception e) {
            Log.e(TAG, "handleMqttMessage error for cmd=" + cmd, e);
        }
    }
}
