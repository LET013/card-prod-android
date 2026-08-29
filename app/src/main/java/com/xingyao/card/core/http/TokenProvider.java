package com.xingyao.card.core.http;

/**
 * Token 提供者接口。
 * 由调用方实现，注入 {@link HttpClientManager}，用于让 HTTP 客户端
 * 自动在请求头中携带 Token 并在 401 时自动刷新。
 */
public interface TokenProvider {

    /**
     * 返回当前有效的 Token，可能为 null。
     * 此方法会在每次请求前调用，应快速返回（不要阻塞）。
     */
    String getToken();

    /**
     * 同步刷新 Token。由 OkHttp 的 Authenticator 在后台线程调用，
     * 可以执行网络请求（阻塞）。返回新的 Token，或 null 表示刷新失败。
     *
     * @return 新的 Token，或 null（放弃重试）
     */
    String refreshToken();
}
