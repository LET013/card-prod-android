package com.xingyao.card.core.utils;

import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * MQTT 消息 HMAC-SHA256 签名工具。
 * 所有 MQTT 上行统一签 msgId:cmd:timestamp:raw。
 */
public final class MqttSignUtil {
    private static final String TAG = "MqttSignUtil";
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private MqttSignUtil() {}

    /**
     * 计算签名。
     * @param signingKey 签名密钥（激活/验证返回的 32 位 UUID 无横线）
     * @param msgId      消息 ID
     * @param cmd        命令字
     * @param timestamp  13 位毫秒时间戳
     * @param raw        MQTT envelope 中原样发送的 raw 字符串
     * @return Base64 签名字符串
     */
    public static String sign(String signingKey, String msgId, String cmd,
                              long timestamp, String raw) {
        return signInput(signingKey, signingInput(msgId, cmd, timestamp, raw));
    }

    private static String signInput(String signingKey, String input) {
        if (signingKey == null || signingKey.isEmpty()) {
            Log.w(TAG, "signingKey is null/empty, returning empty sign");
            return "";
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(
                    signingKey.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
            mac.init(keySpec);
            byte[] hash = mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(hash, Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(TAG, "Sign failed", e);
            return "";
        }
    }

    static String signingInput(String msgId, String cmd, long timestamp, String raw) {
        String rawString = raw == null || raw.isEmpty() ? "{}" : raw;
        return msgId + ":" + cmd + ":" + timestamp + ":" + rawString;
    }
}
