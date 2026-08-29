package com.xingyao.card.core.http;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

import okhttp3.Authenticator;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.Route;

/**
 * 基于 OkHttp 的 HTTP 请求公共类，提供：
 * <ul>
 *   <li><b>Token 管理</b> — 自动注入 Authorization 头，401 时自动刷新并重试</li>
 *   <li>{@link #get(String)} / {@link #getAsync(String, HttpCallback)} — GET 请求</li>
 *   <li>{@link #post(String, JSONObject)} / {@link #postAsync(String, JSONObject, HttpCallback)} — POST JSON 请求</li>
 *   <li>{@link #download(String, File, DownloadCallback)} — 文件下载（含进度回调）</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * HttpClientManager http = new HttpClientManager.Builder()
 *     .baseUrl("https://api.example.com")
 *     .tokenProvider(() -> tokenStore.getAccessToken(), () -> tokenStore.refresh())
 *     .connectTimeout(30, TimeUnit.SECONDS)
 *     .build();
 *
 * // 同步 GET
 * JSONObject data = http.get("/v1/status");
 *
 * // 异步 POST
 * http.postAsync("/v1/submit", payload, new HttpCallback() {
 *     public void onSuccess(JSONObject data) { ... }
 *     public void onFailure(int code, String msg) { ... }
 * });
 *
 * // 下载文件
 * http.download("/v1/files/report.pdf", new File("/sdcard/report.pdf"),
 *     new DownloadCallback() {
 *         public void onProgress(long downloaded, long total) { ... }
 *         public void onSuccess(File file) { ... }
 *         public void onFailure(int code, String msg) { ... }
 *     });
 * }</pre>
 */
public class HttpClientManager {
    private static final String TAG = "HttpClientManager";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient client;
    private final String baseUrl;
    private final Handler mainHandler;

    private HttpClientManager(Builder builder) {
        OkHttpClient.Builder clientBuilder = builder.baseClient != null
                ? builder.baseClient.newBuilder()
                : new OkHttpClient.Builder();

        clientBuilder.connectTimeout(builder.connectTimeout, builder.connectTimeoutUnit);
        clientBuilder.readTimeout(builder.readTimeout, builder.readTimeoutUnit);
        clientBuilder.writeTimeout(builder.writeTimeout, builder.writeTimeoutUnit);

        // Token 注入拦截器
        final TokenProvider tokenProvider = builder.tokenProvider;
        if (tokenProvider != null) {
            clientBuilder.addInterceptor(new Interceptor() {
                @Override
                public Response intercept(Chain chain) throws IOException {
                    Request original = chain.request();
                    String token = tokenProvider.getToken();
                    if (token != null && !token.isEmpty()) {
                        Request authReq = original.newBuilder()
                                .header("Authorization", "Bearer " + token)
                                .build();
                        return chain.proceed(authReq);
                    }
                    return chain.proceed(original);
                }
            });

            // 401 自动刷新 Token 并重试（仅重试一次）
            clientBuilder.authenticator(new Authenticator() {
                @Override
                public Request authenticate(Route route, Response response) throws IOException {
                    if (response.code() != 401) return null;

                    synchronized (tokenProvider) {
                        String currentToken = tokenProvider.getToken();
                        // 检查是否被其他请求已刷新过
                        String authHeader = response.request().header("Authorization");
                        if (authHeader != null && !authHeader.equals("Bearer " + currentToken)) {
                            // 已经是用新 token 的请求，不再重试
                            return null;
                        }

                        String newToken = tokenProvider.refreshToken();
                        if (newToken == null || newToken.isEmpty()) {
                            Log.w(TAG, "Token refresh failed, giving up");
                            return null;
                        }
                        Log.d(TAG, "Token refreshed, retrying request");
                        return response.request().newBuilder()
                                .header("Authorization", "Bearer " + newToken)
                                .build();
                    }
                }
            });
        }

        this.client = clientBuilder.build();
        this.baseUrl = builder.baseUrl != null ? builder.baseUrl : "";
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /* ==================== 同步 GET ==================== */

    /**
     * 同步 GET 请求，返回解析后的 JSON 对象。
     * <b>注意：在主线程调用会阻塞，请只用在后台线程。</b>
     */
    public JSONObject get(String path) throws IOException, JSONException {
        Request request = new Request.Builder()
                .url(resolveUrl(path))
                .get()
                .build();
        return executeSync(request);
    }

    /* ==================== 同步 POST ==================== */

    /**
     * 同步 POST JSON 请求，返回解析后的 JSON 对象。
     * <b>注意：在主线程调用会阻塞，请只用在后台线程。</b>
     */
    public JSONObject post(String path, JSONObject body) throws IOException, JSONException {
        RequestBody requestBody = RequestBody.create(JSON, body.toString());
        Request request = new Request.Builder()
                .url(resolveUrl(path))
                .post(requestBody)
                .build();
        return executeSync(request);
    }

    /**
     * 同步 multipart/form-data 请求。字段和文件内容由上层业务组装，本层只负责传输。
     */
    public JSONObject postMultipart(String path, JSONObject fields,
                                    String fileField, String fileName,
                                    String mimeType, byte[] fileBytes)
            throws IOException, JSONException {
        if (fileField == null || fileField.trim().isEmpty()) {
            throw new IllegalArgumentException("fileField is required");
        }
        if (fileBytes == null || fileBytes.length == 0) {
            throw new IllegalArgumentException("fileBytes is required");
        }

        MultipartBody.Builder bodyBuilder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM);
        if (fields != null) {
            Iterator<String> keys = fields.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = fields.opt(key);
                if (value != null && value != JSONObject.NULL) {
                    bodyBuilder.addFormDataPart(key, String.valueOf(value));
                }
            }
        }

        MediaType fileMediaType = MediaType.parse(
                mimeType == null || mimeType.trim().isEmpty()
                        ? "application/octet-stream"
                        : mimeType.trim());
        bodyBuilder.addFormDataPart(
                fileField.trim(),
                fileName == null || fileName.trim().isEmpty() ? "upload.bin" : fileName.trim(),
                RequestBody.create(fileMediaType, fileBytes));

        Request request = new Request.Builder()
                .url(resolveUrl(path))
                .post(bodyBuilder.build())
                .build();
        return executeSync(request);
    }

    /* ==================== 异步 GET ==================== */

    /**
     * 异步 GET 请求，回调在主线程。
     */
    public void getAsync(String path, HttpCallback callback) {
        Request request = new Request.Builder()
                .url(resolveUrl(path))
                .get()
                .build();
        enqueue(request, callback);
    }

    /* ==================== 异步 POST ==================== */

    /**
     * 异步 POST JSON 请求，回调在主线程。
     */
    public void postAsync(String path, JSONObject body, HttpCallback callback) {
        RequestBody requestBody = RequestBody.create(JSON, body != null ? body.toString() : "{}");
        Request request = new Request.Builder()
                .url(resolveUrl(path))
                .post(requestBody)
                .build();
        enqueue(request, callback);
    }

    /* ==================== 文件下载 ==================== */

    /**
     * 异步下载文件（带进度回调，回调在主线程）。
     *
     * @param path   请求路径（相对于 baseUrl），或完整 URL
     * @param target 目标文件
     * @param cb     进度/结果回调
     */
    public void download(String path, File target, DownloadCallback cb) {
        String url = resolveUrl(path);
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Download failed: " + url, e);
                postToMain(() -> cb.onFailure(-1, e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    int code = response.code();
                    String msg = safeBodyString(response);
                    response.close();
                    Log.w(TAG, "Download failed: HTTP " + code);
                    postToMain(() -> cb.onFailure(code, msg));
                    return;
                }

                try {
                    long contentLength = response.body().contentLength();
                    InputStream in = response.body().byteStream();
                    OutputStream out = new FileOutputStream(target);
                    byte[] buffer = new byte[8192];
                    long downloaded = 0;
                    int len;
                    long lastNotify = 0;

                    while ((len = in.read(buffer)) != -1) {
                        out.write(buffer, 0, len);
                        downloaded += len;

                        // 节流：每 100ms 或每 5% 通知一次进度
                        if (contentLength > 0) {
                            long now = System.currentTimeMillis();
                            long percent = downloaded * 100 / contentLength;
                            long lastPercent = lastNotify > 0 ? ((downloaded - len) * 100 / contentLength) : 0;
                            if (now - lastNotify > 100 || percent != lastPercent) {
                                lastNotify = now;
                                long finalDownloaded = downloaded;
                                postToMain(() -> cb.onProgress(finalDownloaded, contentLength));
                            }
                        }
                    }
                    out.flush();
                    out.close();
                    in.close();
                    response.close();

                    postToMain(() -> cb.onSuccess(target));
                } catch (Exception e) {
                    Log.e(TAG, "Download IO error", e);
                    response.close();
                    postToMain(() -> cb.onFailure(-1, e.getMessage()));
                }
            }
        });
    }

    /* ==================== 实用方法 ==================== */

    /**
     * 取消所有进行中的请求。
     */
    public void cancelAll() {
        client.dispatcher().cancelAll();
    }

    /**
     * 获取 OkHttpClient 实例（高级用法，直接执行自定义 Request）。
     * <pre>{@code
     * http.getClient().newCall(new Request.Builder()...build()).execute();
     * }</pre>
     */
    public OkHttpClient getClient() {
        return client;
    }

    /**
     * 解析 baseUrl + path 为完整 URL。
     */
    public String resolveUrl(String path) {
        if (path == null) return baseUrl;
        if (path.startsWith("http://") || path.startsWith("https://")) return path;
        if (path.startsWith("/")) return baseUrl + path;
        return baseUrl + "/" + path;
    }

    /* ==================== 内部实现 ==================== */

    private JSONObject executeSync(Request request) throws IOException, JSONException {
        Log.d(TAG, request.method() + " " + request.url());
        try (Response response = client.newCall(request).execute()) {
            int code = response.code();
            String respBody = safeBodyString(response);
            String bodySummary = "length=" + (respBody == null ? 0 : respBody.length());
            if (!response.isSuccessful()) {
                Log.w(TAG, "HTTP " + code + " " + request.url() + " BODY_" + bodySummary);
                throw new IOException("HTTP " + code);
            }
            Log.d(TAG, "HTTP " + code + " " + request.url() + " BODY_" + bodySummary);
            if (respBody == null || respBody.trim().isEmpty()) return new JSONObject();
            return new JSONObject(respBody);
        }
    }

    private void enqueue(Request request, HttpCallback callback) {
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Request failed: " + request.url(), e);
                postToMain(() -> callback.onFailure(-1, e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    if (!response.isSuccessful()) {
                        int code = response.code();
                        String msg = safeBodyString(response);
                        Log.w(TAG, "Request failed: HTTP " + code + " " + request.url());
                        postToMain(() -> callback.onFailure(code, msg));
                        return;
                    }
                    JSONObject data = parseBody(response);
                    postToMain(() -> callback.onSuccess(data));
                } catch (JSONException e) {
                    Log.e(TAG, "JSON parse error: " + request.url(), e);
                    postToMain(() -> callback.onFailure(-1, "JSON parse error: " + e.getMessage()));
                } finally {
                    response.close();
                }
            }
        });
    }

    private static JSONObject parseBody(Response response) throws IOException, JSONException {
        String body = response.body().string();
        if (body == null || body.trim().isEmpty()) return new JSONObject();
        return new JSONObject(body);
    }

    private static String safeBodyString(Response response) {
        try {
            return response.body() != null ? response.body().string() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private void postToMain(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            mainHandler.post(r);
        }
    }

    /* ==================== Builder ==================== */

    public static class Builder {
        private String baseUrl;
        private TokenProvider tokenProvider;
        private OkHttpClient baseClient;
        private long connectTimeout = 30;
        private long readTimeout = 30;
        private long writeTimeout = 30;
        private TimeUnit connectTimeoutUnit = TimeUnit.SECONDS;
        private TimeUnit readTimeoutUnit = TimeUnit.SECONDS;
        private TimeUnit writeTimeoutUnit = TimeUnit.SECONDS;

        /** 基础 URL（如 "https://api.example.com"），所有 path 将拼接在此之后 */
        public Builder baseUrl(String baseUrl) { this.baseUrl = baseUrl; return this; }

        /** Token 提供者，用于自动注入 Authorization 头和 401 时自动刷新 */
        public Builder tokenProvider(TokenProvider tokenProvider) { this.tokenProvider = tokenProvider; return this; }

        /** 复用已有 OkHttpClient（连接池、缓存等） */
        public Builder baseClient(OkHttpClient client) { this.baseClient = client; return this; }

        public Builder connectTimeout(long timeout, TimeUnit unit) {
            this.connectTimeout = timeout;
            this.connectTimeoutUnit = unit;
            return this;
        }

        public Builder readTimeout(long timeout, TimeUnit unit) {
            this.readTimeout = timeout;
            this.readTimeoutUnit = unit;
            return this;
        }

        public Builder writeTimeout(long timeout, TimeUnit unit) {
            this.writeTimeout = timeout;
            this.writeTimeoutUnit = unit;
            return this;
        }

        public HttpClientManager build() {
            return new HttpClientManager(this);
        }
    }
}
