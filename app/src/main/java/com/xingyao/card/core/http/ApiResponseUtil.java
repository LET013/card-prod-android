package com.xingyao.card.core.http;

import org.json.JSONObject;

import java.io.IOException;

/**
 * 标准 API 响应解包工具。
 *
 * <p>所有后端接口统一使用如下信封格式：
 * <pre>{@code
 * {
 *   "msg": "操作成功",
 *   "code": 200,
 *   "data": { ... }
 * }
 * }</pre>
 *
 * <p>本工具负责：
 * <ul>
 *   <li>校验 {@code code == 200}，非 200 时抛出含 {@code msg} 提示的 IOException</li>
 *   <li>提取 {@code data} 字段并返回</li>
 *   <li>支持泛型映射器，在解包同时将 data 转换为业务实体</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 方式一：无映射
 * JSONObject data = ApiResponseUtil.unwrap(response, "register");
 *
 * // 方式二：带映射
 * RegisterResponse resp = ApiResponseUtil.unwrap(response, "register",
 *         RegisterResponse::fromJson);
 * }</pre>
 */
public final class ApiResponseUtil {

    private ApiResponseUtil() {
        // 工具类，禁止实例化
    }

    /**
     * 解包标准 API 响应，提取 data 字段。
     *
     * @param envelope 完整的 HTTP 响应 JSON（包含 msg / code / data）
     * @param apiName  接口名称（用于错误日志提示）
     * @return data 字段对应的 JSONObject（非 null）
     * @throws IOException code != 200 或 data 字段缺失
     */
    public static JSONObject unwrap(JSONObject envelope, String apiName) throws IOException {
        int code = envelope.optInt("code", -1);
        if (code != 200) {
            String msg = envelope.optString("msg", "未知错误");
            throw new IOException(apiName + " 返回错误: " + msg + " (code=" + code + ")");
        }
        JSONObject data = envelope.optJSONObject("data");
        if (data == null) {
            throw new IOException(apiName + " 响应缺少 data 字段");
        }
        return data;
    }

    /**
     * 解包并在同一调用链中将 data 映射为业务实体。
     *
     * @param envelope 完整的 HTTP 响应 JSON
     * @param apiName  接口名称
     * @param mapper   data → 业务实体的映射函数
     * @param <T>      目标业务实体类型
     * @return 映射后的业务实体
     * @throws IOException code != 200 / data 字段缺失 / mapper 内部异常
     */
    public static <T> T unwrap(JSONObject envelope, String apiName,
                               JsonMapper<T> mapper) throws IOException {
        JSONObject data = unwrap(envelope, apiName);
        try {
            return mapper.map(data);
        } catch (Exception e) {
            if (e instanceof IOException) {
                throw (IOException) e;
            }
            throw new IOException(apiName + " 响应解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * JSONObject → 业务实体的映射函数。
     * 实现时可将 JSONException 等受检异常包装为 IOException 抛出，
     * {@link #unwrap(JSONObject, String, JsonMapper)} 会统一处理。
     */
    @FunctionalInterface
    public interface JsonMapper<T> {
        T map(JSONObject data) throws Exception;
    }
}
