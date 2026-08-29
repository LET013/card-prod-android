package com.xingyao.card.webview;

import com.xingyao.card.core.log.AppLog;

/** Routes WebView, bridge and local-server diagnostics through the cloud diagnostic logger. */
final class Log {
    private Log() { }

    static int d(String tag, String message) {
        AppLog.diagnosticD(tag, message);
        return 0;
    }

    static int i(String tag, String message) {
        AppLog.diagnosticI(tag, message);
        return 0;
    }

    static int w(String tag, String message) {
        AppLog.diagnosticW(tag, message);
        return 0;
    }

    static int w(String tag, String message, Throwable error) {
        AppLog.diagnosticW(tag, message, error);
        return 0;
    }

    static int e(String tag, String message) {
        AppLog.diagnosticE(tag, message);
        return 0;
    }

    static int e(String tag, String message, Throwable error) {
        AppLog.diagnosticE(tag, message, error);
        return 0;
    }
}
