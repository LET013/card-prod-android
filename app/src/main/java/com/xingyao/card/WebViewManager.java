package com.xingyao.card;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.http.SslError;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

public class WebViewManager {

    private static final String TAG = "WebViewManager";

    /**
     * 1x1 透明 PNG 的字节数组，用于替换无法加载的 CDN 远程图片，
     * 避免 WebView 因设备无外网 / SSL 失败而反复报错。
     */
    private static final byte[] TRANSPARENT_PNG = new byte[] {
        (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, // PNG signature
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,       // IHDR chunk
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,       // 1x1 px
        0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, (byte) 0xC4, (byte) 0x89,
        0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41, 0x54,       // IDAT chunk
        0x78, (byte) 0x9C, 0x62, 0x00, 0x00, 0x00, 0x02, 0x00,
        0x01, (byte) 0xE5, 0x27, (byte) 0xDE, (byte) 0xFC,
        0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44,       // IEND chunk
        (byte) 0xAE, 0x42, 0x60, (byte) 0x82
    };
    private Context context;
    private ViewGroup container;
    private WebView webView;
    private JsBridge jsBridge;
    private ErrorHandler errorHandler;
    private boolean isLoading = false;
    private OnPageFinishedListener onPageFinishedListener;

    public interface OnPageFinishedListener {
        void onPageFinished();
    }

    public WebViewManager(Context context, ViewGroup container, JsBridge jsBridge, ErrorHandler errorHandler, OnPageFinishedListener listener) {
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
        Log.i(TAG, "Device Pixel Ratio: " + metrics.density);
        Log.i(TAG, "Width DPI: " + metrics.xdpi);
        Log.i(TAG, "Height DPI: " + metrics.ydpi);

        int statusBarHeight = 0;
        int resourceId = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            statusBarHeight = context.getResources().getDimensionPixelSize(resourceId);
        }
        Log.i(TAG, "StatusBar Height: " + statusBarHeight);

        DisplayMetrics appMetrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(appMetrics);
        Log.i(TAG, "App Window Size: " + appMetrics.widthPixels + " x " + appMetrics.heightPixels);

        Log.i(TAG, "Build Manufacturer: " + Build.MANUFACTURER);
        Log.i(TAG, "Build Model: " + Build.MODEL);
        Log.i(TAG, "Build Product: " + Build.PRODUCT);
        Log.i(TAG, "Android Version: " + Build.VERSION.RELEASE);
        Log.i(TAG, "SDK Level: " + Build.VERSION.SDK_INT);
        Log.i(TAG, "=================================");
    }

    @SuppressLint("JavascriptInterface")
    private void initWebView() {
        webView = new WebView(context);
        WebSettings settings = webView.getSettings();

        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            settings.setAllowFileAccessFromFileURLs(true);
            settings.setAllowUniversalAccessFromFileURLs(true);
        }

        webView.addJavascriptInterface(jsBridge, "android");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                isLoading = true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                isLoading = false;
                if (onPageFinishedListener != null) {
                    onPageFinishedListener.onPageFinished();
                }
            }

            @Override
            @SuppressWarnings("deprecation")
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                // CDN 远程资源 → 返回透明占位图，避免 SSL/网络错误
                if (isCdnUrl(url)) {
                    return new WebResourceResponse("image/png", "UTF-8",
                            new ByteArrayInputStream(TRANSPARENT_PNG));
                }

                String assetPath = getAssetPathFromUrl(url);
                if (assetPath != null) {
                    try {
                        InputStream inputStream = context.getAssets().open(assetPath);
                        String mimeType = getMimeType(assetPath);
                        return new WebResourceResponse(mimeType, "UTF-8", inputStream);
                    } catch (IOException e) {
                        Log.d(TAG, "Asset not found: " + assetPath);
                        // 直接返回 404，避免再请求 localhost HTTP 服务器
                        return new WebResourceResponse("text/plain", "UTF-8",
                                new ByteArrayInputStream("404 Not Found".getBytes()));
                    }
                }
                return super.shouldInterceptRequest(view, url);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();

                // CDN 远程资源 → 返回透明占位图，避免 SSL/网络错误
                if (isCdnUrl(url)) {
                    return new WebResourceResponse("image/png", "UTF-8",
                            new ByteArrayInputStream(TRANSPARENT_PNG));
                }

                String assetPath = getAssetPathFromUrl(url);
                if (assetPath != null) {
                    try {
                        InputStream inputStream = context.getAssets().open(assetPath);
                        String mimeType = getMimeType(assetPath);
                        return new WebResourceResponse(mimeType, "UTF-8", inputStream);
                    } catch (IOException e) {
                        Log.d(TAG, "Asset not found: " + assetPath);
                        // 直接返回 404，避免再请求 localhost HTTP 服务器
                        return new WebResourceResponse("text/plain", "UTF-8",
                                new ByteArrayInputStream("404 Not Found".getBytes()));
                    }
                }
                return super.shouldInterceptRequest(view, request);
            }

            private String getAssetPathFromUrl(String url) {
                if (url.endsWith("favicon.ico")) {
                    return null;
                }
                if (url.startsWith("file:///android_asset/")) {
                    return url.replace("file:///android_asset/", "");
                } else if (url.startsWith("http://localhost:8088/")) {
                    return url.replace("http://localhost:8088/", "");
                }
                return null;
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                Log.w(TAG, "onReceivedSslError: " + error.getUrl() + " error=" + error.getPrimaryError());
                handler.proceed(); // 嵌入式设备无外网或时间不准，忽略 SSL 错误
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                String url = request.getUrl().toString();
                Log.d(TAG, "onReceivedError: code=" + error.getErrorCode() + " url=" + url + " isMainFrame=" + request.isForMainFrame());

                // 远程 CDN 资源加载失败不报错（设备无外网）
                if (isCdnUrl(url)) return;

                // 图片 404 忽略
                if ((error.getErrorCode() == 404 || error.getErrorCode() == -1)
                        && request.getRequestHeaders().containsKey("Accept")
                        && request.getRequestHeaders().get("Accept").contains("image")) {
                    Log.d("WebView Load Resouce", "FILE_NOT_FOUND: "+request.getUrl());
                    return;
                }

                // 子资源加载失败只记日志，不弹出错误页面（只有主框架失败才显示错误）
                if (!request.isForMainFrame()) {
                    Log.w(TAG, "子资源加载失败，忽略: " + url);
                    return;
                }

                handleError(error.getErrorCode(), error.getDescription().toString() +",url="+request.getUrl());
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, android.webkit.WebResourceResponse errorResponse) {
                super.onReceivedHttpError(view, request, errorResponse);
                int statusCode = errorResponse.getStatusCode();
                Log.d(TAG, "onReceivedHttpError: status=" + statusCode + " url=" + request.getUrl() + " isMainFrame=" + request.isForMainFrame());

                // 子资源的 HTTP 错误不弹出错误页面
                if (!request.isForMainFrame()) {
                    Log.w(TAG, "子资源 HTTP 错误，忽略: " + request.getUrl());
                    return;
                }
                handleError(statusCode, "HTTP Error " + statusCode);
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
                        errorHandler.handleJsError(log);
                        break;
                    case WARNING:
                        Log.w("WebView", log);
                        break;
                    case DEBUG:
                        Log.d("WebView", log);
                        break;
                    case LOG:
                        Log.i("WebView", log);
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
    }

    public void loadUrl(String url) {
        if (webView != null) {
            webView.loadUrl(url);
        }
    }

    public void evaluateJavascript(String script) {
        if (webView != null) {
            webView.post(() -> webView.evaluateJavascript(script, null));
        }
    }

    private String getMimeType(String path) {
        if (path.endsWith(".js")) {
            return "application/javascript";
        } else if (path.endsWith(".mjs")) {
            return "application/javascript";
        } else if (path.endsWith(".css")) {
            return "text/css";
        } else if (path.endsWith(".html")) {
            return "text/html";
        } else if (path.endsWith(".json")) {
            return "application/json";
        } else if (path.endsWith(".png")) {
            return "image/png";
        } else if (path.endsWith(".jpg") || path.endsWith(".jpeg")) {
            return "image/jpeg";
        } else if (path.endsWith(".gif")) {
            return "image/gif";
        } else if (path.endsWith(".svg")) {
            return "image/svg+xml";
        } else if (path.endsWith(".woff") || path.endsWith(".woff2")) {
            return "font/woff";
        } else if (path.endsWith(".ttf")) {
            return "font/ttf";
        } else if (path.endsWith(".ico")) {
            return "image/x-icon";
        }
        return "application/octet-stream";
    }

    /** 判断是否是 uni-app/dcloud CDN 远程资源 */
    private static boolean isCdnUrl(String url) {
        return url != null && (url.contains("cdn.dcloud.net.cn") ||
                               url.contains("cdn.dcloud.io"));
    }

    private void handleError(int errorCode, String description) {
        int errorType;
        if (errorCode == 404) {
            errorType = ErrorHandler.ERROR_404;
        } else if (errorCode >= 500 && errorCode < 600) {
            errorType = ErrorHandler.ERROR_SERVER;
        } else if (errorCode == -2 || errorCode == -10) {
            errorType = ErrorHandler.ERROR_NETWORK;
        } else {
            errorType = ErrorHandler.ERROR_UNKNOWN;
        }
        errorHandler.handleError(errorType, description);
    }

    public void destroy() {
        if (webView != null) {
            container.removeView(webView);
            webView.removeJavascriptInterface("android");
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