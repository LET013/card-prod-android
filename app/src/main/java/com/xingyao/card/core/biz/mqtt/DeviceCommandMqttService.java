package com.xingyao.card.core.biz.mqtt;

import android.util.Log;

import com.xingyao.card.core.bootstrap.CredentialStore;
import com.xingyao.card.core.mqtt.BaseMqttService;
import com.xingyao.card.core.mqtt.MqttCmd;
import com.xingyao.card.core.mqtt.XMqttClient;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 下行命令处理 MQTT 服务。
 *
 * <p>负责处理服务端下发的设备控制指令：
 * <ul>
 *   <li>{@code restartApp} — 重启 APP</li>
 *   <li>{@code syncUser} — 触发用户数据同步</li>
 *   <li>{@code syncConfig} — 触发配置同步</li>
 *   <li>{@code deviceSelfCheck} — 触发设备自检</li>
 *   <li>{@code firmwareUpgrade} — 触发固件升级</li>
 *   <li>{@code cancelUpgrade} — 取消固件升级</li>
 *   <li>{@code enableLogUpload} — 开启日志上传</li>
 *   <li>{@code disableLogUpload} — 关闭日志上传</li>
 * </ul>
 *
 * <p>文档 V4.2 §4.4.1 下行指令。
 */
public class DeviceCommandMqttService extends BaseMqttService {

    private static final String TAG = "DeviceCommandMqttService";

    /** 下行命令回调 */
    public interface Callback {
        /** 重启 APP */
        void onRestartApp();

        /** 同步用户数据 */
        void onSyncUser();

        /** 同步配置 */
        void onSyncConfig();

        /**
         * 设备自检。
         * @return 自检结果 JSONObject，用于构造 deviceSelfCheckResp
         */
        JSONObject onDeviceSelfCheck();

        /**
         * 固件升级。
         * @param url            固件下载 URL
         * @param firmwareVersion 目标版本号
         * @param md5            校验 MD5
         */
        void onFirmwareUpgrade(String url, String firmwareVersion, String md5);

        /** 取消固件升级 */
        void onCancelUpgrade();

        /** 开启日志上传 */
        void onEnableLogUpload();

        /** 关闭日志上传 */
        void onDisableLogUpload();
    }

    private final Callback callback;

    public DeviceCommandMqttService(XMqttClient mqttClient, String deviceCode,
                                    CredentialStore credentialStore, Callback callback) {
        super(mqttClient, deviceCode, credentialStore);
        this.callback = callback;
        register();
    }

    /** 发送响应（不携带业务数据） */
    public void sendSimpleResp(String cmd) {
        try {
            sendSignedEnvelope(cmd, new JSONObject());
        } catch (JSONException e) {
            Log.e(TAG, "sendSimpleResp JSON error for " + cmd, e);
        }
    }

    @Override
    public void handleMqttMessage(String cmd, JSONObject data, String topic) {
        if (callback == null) return;

        try {
            switch (cmd) {
                case MqttCmd.RESTART_APP:
                    Log.i(TAG, "restartApp");
                    callback.onRestartApp();
                    sendSimpleResp(MqttCmd.RESTART_APP_RESP);
                    break;

                case MqttCmd.SYNC_USER:
                    Log.i(TAG, "syncUser");
                    callback.onSyncUser();
                    sendSimpleResp(MqttCmd.SYNC_USER_RESP);
                    break;

                case MqttCmd.SYNC_CONFIG:
                    Log.i(TAG, "syncConfig");
                    callback.onSyncConfig();
                    sendSimpleResp(MqttCmd.SYNC_CONFIG_RESP);
                    break;

                case MqttCmd.DEVICE_SELF_CHECK:
                    Log.i(TAG, "deviceSelfCheck");
                    JSONObject checkResult = callback.onDeviceSelfCheck();
                    sendSignedEnvelope(MqttCmd.DEVICE_SELF_CHECK_RESP,
                            checkResult != null ? checkResult : new JSONObject());
                    break;

                case MqttCmd.FIRMWARE_UPGRADE: {
                    String url = data.optString("url");
                    String version = data.optString("firmwareVersion");
                    String md5 = data.optString("md5");
                    Log.i(TAG, "firmwareUpgrade url=" + url + " version=" + version);
                    callback.onFirmwareUpgrade(url, version, md5);
                    sendSimpleResp(MqttCmd.FIRMWARE_UPGRADE_RESP);
                    break;
                }

                case MqttCmd.CANCEL_UPGRADE:
                    Log.i(TAG, "cancelUpgrade");
                    callback.onCancelUpgrade();
                    sendSimpleResp(MqttCmd.CANCEL_UPGRADE_RESP);
                    break;

                case MqttCmd.ENABLE_LOG_UPLOAD:
                    Log.i(TAG, "enableLogUpload");
                    callback.onEnableLogUpload();
                    // 文档未要求回复，但仍发送已收到
                    break;

                case MqttCmd.DISABLE_LOG_UPLOAD:
                    Log.i(TAG, "disableLogUpload");
                    callback.onDisableLogUpload();
                    break;

                default:
                    Log.d(TAG, "Unhandled cmd: " + cmd);
            }
        } catch (Exception e) {
            Log.e(TAG, "handleMqttMessage error for cmd=" + cmd, e);
        }
    }
}
