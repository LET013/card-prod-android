package com.xingyao.card.core.entity.http;

import org.json.JSONObject;

/**
 * 激活响应: POST /api/v1/device/activate 的 data 字段。
 *
 * <p>两种结果：
 * <ul>
 *   <li>{@code valid=true} — 直接激活，含 {@link #activationResult} MQTT 凭证</li>
 *   <li>{@code valid=false} — 需管理员输入激活码，含 {@link #registerCode} / {@link #expireTime}</li>
 * </ul>
 */
public class ActivateResponse {
    /** 是否已直接激活 */
    public final boolean valid;
    /** 注册码（valid=false 时有效，显示给管理员） */
    public final String registerCode;
    /** 注册码到期时间戳毫秒（valid=false 时有效） */
    public final long expireTime;
    /** 初始管理员密码（后端返回时仅透传给 Vue 展示和哈希缓存） */
    public final String initialAdminPassword;
    /** MQTT 凭证（valid=true 时有效） */
    public final ActivationResult activationResult;

    private ActivateResponse(boolean valid, String registerCode, long expireTime,
                             String initialAdminPassword, ActivationResult activationResult) {
        this.valid = valid;
        this.registerCode = registerCode;
        this.expireTime = expireTime;
        this.initialAdminPassword = initialAdminPassword;
        this.activationResult = activationResult;
    }

    /** 是否直接激活（不需要激活码） */
    public boolean isDirectActivated() { return valid; }

    /** 是否需要激活码 */
    public boolean needActivationCode() { return !valid; }

    public static ActivateResponse fromJson(JSONObject data) {
        boolean valid = data.optBoolean("valid", false);
        if (valid) {
            ActivationResult result = ActivationResult.parse(data).build();
            return new ActivateResponse(true, "", 0L, result.initialAdminPassword, result);
        } else {
            String registerCode = data.optString("registerCode", "");
            long expireTime = data.optLong("expireTime", 0L);
            String initialAdminPassword = ActivationResult.parseInitialAdminPassword(data);
            return new ActivateResponse(false, registerCode, expireTime, initialAdminPassword, null);
        }
    }
}
