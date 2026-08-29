package com.xingyao.card.webview;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import com.xingyao.card.BuildConfig;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

public class WebViewManager {

    private static final String TAG = "WebViewManager";
    private static final int LOCAL_HTTP_PORT = 8088;
    private static final String TRUSTED_HOST = "127.0.0.1";
    private static final String LEGACY_TRUSTED_HOST = "localhost";

    private final Context context;
    private final ViewGroup container;
    private final JsBridgeV2 jsBridge;
    private final ErrorHandler errorHandler;
    private final OnPageFinishedListener onPageFinishedListener;
    private WebView webView;
    private boolean isLoading = false;
    private boolean originScopedBridgeEnabled;

    public interface OnPageFinishedListener {
        void onPageFinished();
    }

    public WebViewManager(Context context, ViewGroup container, JsBridgeV2 jsBridge,
                          ErrorHandler errorHandler, OnPageFinishedListener listener) {
        this.context = context;
        this.container = container;
        this.jsBridge = jsBridge;
        this.errorHandler = errorHandler;
        this.onPageFinishedListener = listener;
        logDeviceInfo();
        initWebView();
    }

    private void logDeviceInfo() {
        Log.i(TAG, "========== Device Info ==========");
        WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        Display display = windowManager.getDefaultDisplay();
        DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);
        Log.i(TAG, "Screen Resolution: " + metrics.widthPixels + " x " + metrics.heightPixels);
        Log.i(TAG, "Density: " + metrics.density);
        Log.i(TAG, "Density DPI: " + metrics.densityDpi);
        Log.i(TAG, "Width DPI: " + metrics.xdpi);
        Log.i(TAG, "Height DPI: " + metrics.ydpi);
        Log.i(TAG, "Build Manufacturer: " + Build.MANUFACTURER);
        Log.i(TAG, "Build Model: " + Build.MODEL);
        Log.i(TAG, "Android Version: " + Build.VERSION.RELEASE);
        Log.i(TAG, "SDK Level: " + Build.VERSION.SDK_INT);
        Log.i(TAG, "=================================");
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void initWebView() {
        webView = new WebView(context);
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
        WebSettings settings = webView.getSettings();

        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            settings.setAllowFileAccessFromFileURLs(false);
            settings.setAllowUniversalAccessFromFileURLs(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }
        installBridge();

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                isLoading = true;
                if (!isTrustedUrl(url)) {
                    Log.e(TAG, "Blocked untrusted main-frame navigation: " + url);
                    view.stopLoading();
                    handleError(-10, "已阻止非本机页面访问原生设备能力");
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                isLoading = false;
                if (isTrustedUrl(url) && onPageFinishedListener != null) {
                    onPageFinishedListener.onPageFinished();
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request == null || request.getUrl() == null ? "" : request.getUrl().toString();
                return blockUntrustedNavigation(url);
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return blockUntrustedNavigation(url);
            }

            @Override
            @SuppressWarnings("deprecation")
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                WebResourceResponse response = localAssetResponse(url);
                return response == null ? super.shouldInterceptRequest(view, url) : response;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request == null || request.getUrl() == null ? "" : request.getUrl().toString();
                WebResourceResponse response = localAssetResponse(url);
                return response == null ? super.shouldInterceptRequest(view, request) : response;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request != null && !request.isForMainFrame()) return;
                int code = error == null ? ErrorHandler.ERROR_UNKNOWN : error.getErrorCode();
                String description = error == null || error.getDescription() == null
                        ? "WebView加载失败" : error.getDescription().toString();
                String url = request == null || request.getUrl() == null ? "" : request.getUrl().toString();
                handleError(code, description + ",url=" + url);
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
                super.onReceivedHttpError(view, request, errorResponse);
                if (request != null && !request.isForMainFrame()) return;
                int statusCode = errorResponse == null ? 500 : errorResponse.getStatusCode();
                handleError(statusCode, "HTTP Error " + statusCode);
            }

            private boolean blockUntrustedNavigation(String url) {
                if (isTrustedUrl(url)) return false;
                Log.w(TAG, "Blocked navigation outside trusted origin: " + url);
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                String message = consoleMessage.message();
                String sourceId = consoleMessage.sourceId();
                int lineNumber = consoleMessage.lineNumber();
                String level = consoleMessage.messageLevel().name();
                String log = String.format("[%s] [%s:%d] %s", level, sourceId, lineNumber, message);
                switch (consoleMessage.messageLevel()) {
                    case ERROR:
                        Log.e("WebView", log);
                        break;
                    case WARNING:
                        Log.w("WebView", log);
                        break;
                    case DEBUG:
                        Log.d("WebView", log);
                        break;
                    default:
                        Log.i("WebView", log);
                }
                return true;
            }
        });

        container.addView(webView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        // WebView 必须可触摸获取焦点，否则输入框点击无法弹出键盘
        webView.setFocusableInTouchMode(true);
        webView.requestFocus();
    }

    private void installBridge() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            Set<String> origins = new LinkedHashSet<>();
            origins.add("http://" + TRUSTED_HOST + ":" + LOCAL_HTTP_PORT);
            origins.add("http://" + LEGACY_TRUSTED_HOST + ":" + LOCAL_HTTP_PORT);
            WebViewCompat.addWebMessageListener(webView, "android", origins,
                    (view, message, sourceOrigin, isMainFrame, replyProxy) -> {
                        if (!isMainFrame || !isTrustedOrigin(sourceOrigin)) {
                            Log.w(TAG, "Rejected WebMessage from origin=" + sourceOrigin + ", mainFrame=" + isMainFrame);
                            return;
                        }
                        jsBridge.handleTrustedMessage(message == null ? null : message.getData());
                    });
            originScopedBridgeEnabled = true;
            return;
        }
        Log.e(TAG, "WEB_MESSAGE_LISTENER unavailable; native bridge disabled to avoid unscoped JavascriptInterface exposure");
        originScopedBridgeEnabled = false;
    }

    private WebResourceResponse localAssetResponse(String url) {
        String assetPath = getAssetPathFromUrl(url);
        if (assetPath == null) return null;
        try {
            InputStream inputStream = context.getAssets().open(assetPath);
            return new WebResourceResponse(getMimeType(assetPath), "UTF-8", inputStream);
        } catch (IOException error) {
            Log.e(TAG, "Failed to load local asset: " + assetPath, error);
            return new WebResourceResponse("text/plain", "UTF-8",
                    new ByteArrayInputStream("Not found".getBytes(StandardCharsets.UTF_8)));
        }
    }

    private String getAssetPathFromUrl(String url) {
        if (!isTrustedUrl(url)) return null;
        Uri uri = Uri.parse(url);
        String path = uri.getPath();
        if (path == null || path.isEmpty() || "/".equals(path)) path = "/index.html";
        if (path.endsWith("/favicon.ico") || path.contains("..")) return null;
        return path.startsWith("/") ? path.substring(1) : path;
    }

    public void loadUrl(String url) {
        if (webView == null) return;
        if (!isTrustedUrl(url)) {
            handleError(-10, "拒绝加载非本机页面：" + url);
            return;
        }
        webView.loadUrl(url);
    }

    public void evaluateJavascript(String script) {
        WebView currentWebView = webView;
        if (currentWebView != null) {
            currentWebView.post(() -> {
                if (webView == currentWebView) {
                    currentWebView.evaluateJavascript(script, null);
                }
            });
        }
    }

    public boolean isOriginScopedBridgeEnabled() {
        return originScopedBridgeEnabled;
    }

    private static boolean isTrustedUrl(String value) {
        if (value == null || value.trim().isEmpty()) return false;
        return isTrustedOrigin(Uri.parse(value));
    }

    private static boolean isTrustedOrigin(Uri uri) {
        if (uri == null || !"http".equalsIgnoreCase(uri.getScheme())) return false;
        String host = uri.getHost();
        boolean localHost = TRUSTED_HOST.equals(host) || LEGACY_TRUSTED_HOST.equalsIgnoreCase(host);
        return localHost && uri.getPort() == LOCAL_HTTP_PORT;
    }

    private String getMimeType(String path) {
        if (path.endsWith(".js") || path.endsWith(".mjs")) return "application/javascript";
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".html")) return "text/html";
        if (path.endsWith(".json")) return "application/json";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        if (path.endsWith(".gif")) return "image/gif";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".woff") || path.endsWith(".woff2")) return "font/woff";
        if (path.endsWith(".ttf")) return "font/ttf";
        if (path.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }

    private void handleError(int errorCode, String description) {
        int errorType;
        if (errorCode == 404) errorType = ErrorHandler.ERROR_404;
        else if (errorCode >= 500 && errorCode < 600) errorType = ErrorHandler.ERROR_SERVER;
        else if (errorCode == -2 || errorCode == -10) errorType = ErrorHandler.ERROR_NETWORK;
        else errorType = ErrorHandler.ERROR_UNKNOWN;
        errorHandler.handleError(errorType, description);
    }

    public void destroy() {
        if (webView != null) {
            container.removeView(webView);
            if (originScopedBridgeEnabled && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                WebViewCompat.removeWebMessageListener(webView, "android");
            }
            webView.stopLoading();
            webView.setWebViewClient(null);
            webView.setWebChromeClient(null);
            webView.destroy();
            webView = null;
        }
    }

    public boolean isLoading() {
        return isLoading;
    }
}
