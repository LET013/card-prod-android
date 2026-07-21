package com.xingyao.card;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.IOException;
import java.io.InputStream;

public class WebViewManager {

    private static final String TAG = "WebViewManager";
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
                Log.d("WebViewManager", "shouldInterceptRequest (old): " + url);
                String assetPath = getAssetPathFromUrl(url);
                if (assetPath != null) {
                    Log.d("WebViewManager", "Loading asset: " + assetPath);
                    try {
                        InputStream inputStream = context.getAssets().open(assetPath);
                        String mimeType = getMimeType(assetPath);
                        Log.d("WebViewManager", "MIME type: " + mimeType);
                        return new WebResourceResponse(mimeType, "UTF-8", inputStream);
                    } catch (IOException e) {
                        Log.e("WebViewManager", "Failed to load asset (old API): " + assetPath, e);
                    }
                }
                return super.shouldInterceptRequest(view, url);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                Log.d("WebViewManager", "shouldInterceptRequest (new): " + url);
                String assetPath = getAssetPathFromUrl(url);
                if (assetPath != null) {
                    Log.d("WebViewManager", "Loading asset: " + assetPath);
                    try {
                        InputStream inputStream = context.getAssets().open(assetPath);
                        String mimeType = getMimeType(assetPath);
                        Log.d("WebViewManager", "MIME type: " + mimeType);
                        return new WebResourceResponse(mimeType, "UTF-8", inputStream);
                    } catch (IOException e) {
                        Log.e("WebViewManager", "Failed to load asset: " + assetPath, e);
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
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request != null && !request.isForMainFrame()) return;
                String accept = request == null ? "" : request.getRequestHeaders().get("Accept");
                if((error.getErrorCode() == 404 || error.getErrorCode() == -1) && accept != null && accept.contains("image")){
                    Log.d("WebView Load Resouce", "FILE_NOT_FOUND: "+request.getUrl());
                    return;
                }
                handleError(error.getErrorCode(), error.getDescription().toString() +",url="+request.getUrl());
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, android.webkit.WebResourceResponse errorResponse) {
                super.onReceivedHttpError(view, request, errorResponse);
                if (request != null && !request.isForMainFrame()) return;
                int statusCode = errorResponse.getStatusCode();
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
