package com.xingyao.card.core.biz.http;

import com.xingyao.card.core.entity.http.ActivateRequest;
import com.xingyao.card.core.entity.http.ActivateResponse;
import com.xingyao.card.core.entity.http.AuthChangeRequest;
import com.xingyao.card.core.entity.http.AuthStatusResponse;
import com.xingyao.card.core.entity.http.BatchOperationResultRequest;
import com.xingyao.card.core.entity.http.DeviceConfigResponse;
import com.xingyao.card.core.entity.http.HeartbeatRequest;
import com.xingyao.card.core.entity.http.HeartbeatResponse;
import com.xingyao.card.core.entity.http.LoginRequest;
import com.xingyao.card.core.entity.http.RegisterRequest;
import com.xingyao.card.core.entity.http.RegisterResponse;
import com.xingyao.card.core.entity.http.SelfCheckRequest;
import com.xingyao.card.core.entity.http.StatusReportRequest;
import com.xingyao.card.core.entity.http.VerifyCodeRequest;
import com.xingyao.card.core.entity.http.VerifyCodeResponse;
import com.xingyao.card.core.entity.http.VersionCheckResponse;
import com.xingyao.card.core.entity.mqtt.MqttLoginResp;
import com.xingyao.card.core.http.BaseApiService;
import com.xingyao.card.core.http.ApiResponseUtil;
import com.xingyao.card.core.http.HttpClientManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

/**
 * 设备端 HTTP API 服务（{@link BaseApiService} 的类型化封装）。
 *
 * <p>将 bootstrap 流程所需的所有 HTTP 调用封装为带类型化请求/响应对象的方法，
 * 供 {@code DeviceBootstrapManager} 和其他模块复用。
 * 每个方法内部自动完成请求、信封解包、JSON 解析，调用方只需关心业务逻辑。
 *
 * <h3>使用示例（在其他模块中）</h3>
 * <pre>{@code
 * HttpClientManager httpClient = ...; // 从 DeviceBootstrapManager.getHttpClient() 获取
 * DeviceApiService api = new DeviceApiService(httpClient);
 * RegisterResponse resp = api.register(request);
 * ActivateResponse actResp = api.activate(activateRequest);
 * DeviceConfigResponse cfg = api.getConfig();
 * }</pre>
 *
 * <h3>线程安全</h3>
 * 所有方法均为同步阻塞调用，必须在后台线程使用。
 */
public class DeviceApiService extends BaseApiService {

    /**
     * @param http 已配置好 baseUrl、token 的 {@link HttpClientManager}
     */
    public DeviceApiService(HttpClientManager http) {
        super(http, "/api/v1");
    }

    /**
     * APP 版本检测。POST /api/v1/app-version/check
     *
     * @return 版本检测结果（hasUpdate、forceUpdate、versionCode 等）
     */
    public VersionCheckResponse checkVersion(int currentVersionCode, String channelId,
                                             String deviceCode) throws IOException {
        try {
            JSONObject request = new JSONObject()
                    .put("currentVersionCode", currentVersionCode)
                    .put("channelId", channelId);
            if (deviceCode != null && !deviceCode.trim().isEmpty()) {
                request.put("deviceCode", deviceCode.trim());
            }
            JSONObject envelope = http.post("/api/v1/app-version/check", request);
            int code = envelope.optInt("code", -1);
            if (code != 200) {
                throw new IOException("checkVersion 返回错误: "
                        + envelope.optString("msg", "未知错误") + " (code=" + code + ")");
            }
            if (envelope.isNull("data")) {
                return VersionCheckResponse.noUpdate();
            }
            JSONObject data = envelope.optJSONObject("data");
            if (data == null) {
                throw new IOException("checkVersion 响应 data 不是对象");
            }
            return VersionCheckResponse.fromJson(data);
        } catch (JSONException e) {
            throw new IOException("checkVersion JSON 解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 设备注册（首次启动，无需 token）。POST /api/v1/device/register
     *
     * @param req 注册请求
     * @return 注册响应（deviceToken、deviceCode、isNew）
     */
    public RegisterResponse register(RegisterRequest req) throws IOException {
        try {
            JSONObject envelope = http.post("/api/v1/device/register", req.toJson());
            if (envelope.optBoolean("forceUpdate", false)) {
                throw new ForceUpdateRequiredException(
                        envelope.optString("msg", "当前 APP 版本需要升级"),
                        VersionCheckResponse.fromJson(envelope.optJSONObject("versionInfo")));
            }
            return RegisterResponse.fromJson(ApiResponseUtil.unwrap(envelope, "register"));
        } catch (JSONException e) {
            throw new IOException("register JSON 解析失败: " + e.getMessage(), e);
        }
    }

    public static final class ForceUpdateRequiredException extends IOException {
        public final VersionCheckResponse versionInfo;

        public ForceUpdateRequiredException(String message, VersionCheckResponse versionInfo) {
            super(message);
            this.versionInfo = versionInfo == null ? VersionCheckResponse.noUpdate() : versionInfo;
        }
    }

    /**
     * 设备激活。POST /api/v1/device/activate
     *
     * @param req 激活请求
     * @return 激活响应：
     *         {@link ActivateResponse#isDirectActivated()} 为 true 时直接激活含 MQTT 凭证，
     *         否则需通过 {@link #verifyCode(VerifyCodeRequest)} 传入激活码
     */
    public ActivateResponse activate(ActivateRequest req) throws IOException {
        try {
            return ActivateResponse.fromJson(apiPost("/device/activate", req.toJson()));
        } catch (JSONException e) {
            throw new IOException("activate JSON 解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 激活码验证。POST /api/v1/device/verify
     *
     * @param req 验证请求（含 registerCode + activeKey）
     * @return 验证响应：{@link VerifyCodeResponse#isSuccess()} 为 true 时含 MQTT 凭证
     */
    public VerifyCodeResponse verifyCode(VerifyCodeRequest req) throws IOException {
        try {
            return VerifyCodeResponse.fromJson(apiPost("/device/verify", req.toJson()));
        } catch (JSONException e) {
            throw new IOException("verifyCode JSON 解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取设备配置。GET /api/v1/device/config
     *
     * @return 设备配置（communicationMode + 完整原始 JSON）
     */
    public DeviceConfigResponse getConfig() throws IOException {
        try {
            return DeviceConfigResponse.fromJson(apiGet("/device/config"));
        } catch (JSONException e) {
            throw new IOException("getConfig JSON 解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * HTTP 模式设备登录。POST /api/v1/device/login
     *
     * @param req 登录请求
     * @return 与 MQTT loginResp 相同的业务响应
     */
    public MqttLoginResp loginHttp(LoginRequest req) throws IOException {
        try {
            // login 是直接业务响应例外，不经过通用 HTTP data 解包。
            return MqttLoginResp.fromJson(http.post("/api/v1/device/login", req.toJson()));
        } catch (JSONException e) {
            throw new IOException("loginHttp JSON 解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * HTTP 心跳。POST /api/v1/device/heartbeat
     * <p>用于 HTTP 通信模式下替代 MQTT 心跳。
     *
     * @param req 心跳请求（seq 可选）
     * @return 心跳响应（含 serverTime）
     */
    public HeartbeatResponse heartbeat(HeartbeatRequest req) throws IOException {
        try {
            return HeartbeatResponse.fromJson(apiPost("/device/heartbeat", req.toJson()));
        } catch (JSONException e) {
            throw new IOException("heartbeat JSON 解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * HTTP 模式卡槽状态上报。POST /api/v1/device/status
     * <p>MQTT 不可用时通过 HTTP 上报卡槽状态。
     *
     * @param req 状态上报请求
     */
    public void statusReport(StatusReportRequest req) throws IOException {
        try {
            apiPost("/device/status", req.toJson());
        } catch (JSONException e) {
            throw new IOException("statusReport JSON 解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 授权状态变更上报。POST /api/v1/device/auth/change
     *
     * @param req 授权变更请求
     */
    public void authChange(AuthChangeRequest req) throws IOException {
        try {
            apiPost("/device/auth/change", req.toJson());
        } catch (JSONException e) {
            throw new IOException("authChange JSON 解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 查询设备授权状态。GET /api/v1/device/auth/status
     *
     * @return 授权状态
     */
    public AuthStatusResponse authStatus() throws IOException {
        try {
            return AuthStatusResponse.fromJson(apiGet("/device/auth/status"));
        } catch (JSONException e) {
            throw new IOException("authStatus JSON 解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 设备自检结果上报。POST /api/v1/device/selfcheck
     *
     * @param req 自检请求
     */
    public void selfCheck(SelfCheckRequest req) throws IOException {
        try {
            apiPost("/device/selfcheck", req.toJson());
        } catch (JSONException e) {
            throw new IOException("selfCheck JSON 解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 批量操作结果上报。POST /api/v1/device/batch-result
     *
     * @param req 批量操作结果
     */
    public void batchOperationResult(BatchOperationResultRequest req) throws IOException {
        try {
            apiPost("/device/batch-result", req.toJson());
        } catch (JSONException e) {
            throw new IOException("batchOperationResult JSON 解析失败: " + e.getMessage(), e);
        }
    }
}
