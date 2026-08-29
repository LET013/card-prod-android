package com.xingyao.card.core.http;

import android.util.Log;

import com.xingyao.card.core.bootstrap.CredentialStore;

/**
 * 基于 {@link CredentialStore} 的 {@link TokenProvider} 实现。
 *
 * <p>从设备凭证存储中读取 deviceToken，注入到 {@link HttpClientManager}
 * 以自动携带 {@code Authorization: Bearer xxx} 头。
 *
 * <p>当前实现：
 * <ul>
 *   <li>{@link #getToken()} — 从 CredentialStore 实时读取 deviceToken</li>
 *   <li>{@link #refreshToken()} — 返回 null（当前注册/激活流程不支持在线刷新 Token；
 *       401 时需重新走注册流程获取新 token）</li>
 * </ul>
 *
 * <p>使用：
 * <pre>{@code
 * HttpClientManager http = new HttpClientManager.Builder()
 *         .baseUrl(serverUrl)
 *         .tokenProvider(new DeviceTokenProvider(credentialStore))
 *         .build();
 * }</pre>
 */
public class DeviceTokenProvider implements TokenProvider {

    private static final String TAG = "DeviceTokenProvider";

    private final CredentialStore credentialStore;

    public DeviceTokenProvider(CredentialStore credentialStore) {
        this.credentialStore = credentialStore;
    }

    @Override
    public String getToken() {
        try {
            return credentialStore.getDeviceToken();
        } catch (Exception e) {
            Log.w(TAG, "Failed to read deviceToken", e);
            return null;
        }
    }

    @Override
    public String refreshToken() {
        // 当前流程中 deviceToken 由注册接口返回，不支持运行时刷新
        return null;
    }
}
