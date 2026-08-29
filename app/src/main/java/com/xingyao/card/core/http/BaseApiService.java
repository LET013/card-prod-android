package com.xingyao.card.core.http;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

/**
 * HTTP API 服务抽象基类。
 *
 * <p>封装了 {@link HttpClientManager}，提供自动解包标准 API 信封的便捷方法。
 * 所有后端接口统一返回 {@code {"msg":"...","code":200,"data":{...}}}，本类方法内部
 * 调用 {@link ApiResponseUtil#unwrap(JSONObject, String)} 自动校验 code 并提取 data。
 *
 * <p>子类只需关心业务实体的组装与解析，无需重复处理 HTTP 调用和解包逻辑。
 *
 * <h3>设计约定</h3>
 * <ul>
 *   <li>子类通过构造函数接收 {@link HttpClientManager} 和可选的 API 前缀路径</li>
 *   <li>{@link #apiGet(String)} / {@link #apiPost(String, JSONObject)} 返回的永远是 data 字段内容</li>
 *   <li>若 code != 200，自动抛出带 {@code msg} 信息的 IOException</li>
 *   <li>同步调用，必须在后台线程使用</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * public class DeviceApiService extends BaseApiService {
 *     public DeviceApiService(HttpClientManager http) {
 *         super(http, "/api/v1");  // API 路径前缀
 *     }
 *
 *     public RegisterResponse register(RegisterRequest req) throws IOException {
 *         // /api/v1/device/register → apiPost 自动拼接 prefix
 *         JSONObject data = apiPost("/device/register", req.toJson());
 *         return RegisterResponse.fromJson(data);
 *     }
 *
 *     public DeviceConfigResponse getConfig() throws IOException {
 *         return DeviceConfigResponse.fromJson(apiGet("/device/config"));
 *     }
 * }
 * }</pre>
 */
public abstract class BaseApiService {

    /** HTTP 客户端（由子类注入） */
    protected final HttpClientManager http;

    /** API 路径前缀，如 "/api/v1"；为空时直接使用传入的 path */
    protected final String apiPrefix;

    /**
     * @param http     已配置好的 HTTP 客户端
     * @param apiPrefix API 路径前缀（不含尾部 /），如 "/api/v1"；可为空字符串
     */
    protected BaseApiService(HttpClientManager http, String apiPrefix) {
        this.http = http;
        this.apiPrefix = (apiPrefix != null) ? apiPrefix : "";
    }

    /**
     * 不带前缀的便捷构造。
     */
    protected BaseApiService(HttpClientManager http) {
        this(http, "");
    }

    /**
     * 同步 GET 请求并自动解包标准 API 信封。
     *
     * @param path 相对于 baseUrl（和 apiPrefix）的路径，如 "/device/config"
     * @return 响应信封中的 data 字段
     * @throws IOException 网络异常或 code != 200
     */
    protected JSONObject apiGet(String path) throws IOException, JSONException {
        JSONObject envelope = http.get(resolvePath(path));
        return ApiResponseUtil.unwrap(envelope, apiLabel(path));
    }

    /**
     * 同步 POST JSON 请求并自动解包标准 API 信封。
     *
     * @param path 相对于 baseUrl（和 apiPrefix）的路径
     * @param body 请求体 JSON（非 null）
     * @return 响应信封中的 data 字段
     * @throws IOException 网络异常或 code != 200
     */
    protected JSONObject apiPost(String path, JSONObject body) throws IOException, JSONException {
        JSONObject envelope = http.post(resolvePath(path), body);
        return ApiResponseUtil.unwrap(envelope, apiLabel(path));
    }

    // ---- 内部工具 ----

    private String resolvePath(String path) {
        if (apiPrefix.isEmpty()) return path;
        return apiPrefix + path;
    }

    /** 从路径中提取简短的接口名（用于错误日志），如 "/device/register" → "register" */
    private static String apiLabel(String path) {
        if (path == null || path.isEmpty()) return "api";
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < path.length() - 1) {
            return path.substring(lastSlash + 1);
        }
        return path.startsWith("/") ? path.substring(1) : path;
    }
}
