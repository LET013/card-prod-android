package com.xingyao.card.core.entity.http;

import org.json.JSONObject;

/**
 * 激活码验证响应: POST /api/v1/device/verify 的 data 字段。
 *
 * <p>两种结果：
 * <ul>
 *   <li>{@code valid=true} — 验证通过，含 {@link #activationResult} MQTT 凭证</li>
 *   <li>{@code valid=false} — 验证失败，含 {@link #message} 错误提示</li>
 * </ul>
 */
public class VerifyCodeResponse {
    /** 是否验证通过 */
    public final boolean valid;
    /** 失败消息（valid=false 时有效） */
    public final String message;
    /** MQTT 凭证（valid=true 时有效） */
    public final ActivationResult activationResult;

    private VerifyCodeResponse(boolean valid, String message, ActivationResult activationResult) {
        this.valid = valid;
        this.message = message;
        this.activationResult = activationResult;
    }

    public boolean isSuccess() { return valid; }

    public static VerifyCodeResponse fromJson(JSONObject data) {
        boolean valid = data.optBoolean("valid", false);
        if (valid) {
            ActivationResult result = ActivationResult.parse(data).build();
            return new VerifyCodeResponse(true, "", result);
        } else {
            String message = data.optString("message", "激活码验证失败");
            return new VerifyCodeResponse(false, message, null);
        }
    }
}
