package com.xingyao.card.core.http;

import org.json.JSONObject;

/**
 * 异步 HTTP 请求回调（GET / POST）。
 * 回调在主线程执行，可直接更新 UI。
 */
public interface HttpCallback {

    /**
     * 请求成功，返回解析后的 JSON 响应体。
     * @param data 响应体 JSON 对象（非 null）
     */
    void onSuccess(JSONObject data);

    /**
     * 请求失败。
     * @param code    HTTP 状态码（非 2xx），或 -1 表示网络/解析异常
     * @param message 错误描述
     */
    void onFailure(int code, String message);
}
