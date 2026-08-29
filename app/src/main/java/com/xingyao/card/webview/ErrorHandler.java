package com.xingyao.card.webview;

import android.content.Context;
import android.widget.Toast;

import com.xingyao.card.R;
import com.xingyao.card.core.log.AppLog;

public class ErrorHandler {

    public static final int ERROR_404 = 1;
    public static final int ERROR_SERVER = 2;
    public static final int ERROR_NETWORK = 3;
    public static final int ERROR_UNKNOWN = 4;
    public static final int ERROR_JS = 5;

    private Context context;
    private OnErrorListener onErrorListener;
    private OnDismissListener onDismissListener;

    public interface OnErrorListener {
        void onError(int errorType, String message);
    }

    public interface OnDismissListener {
        void onDismiss();
    }

    public ErrorHandler(Context context, OnErrorListener onErrorListener, OnDismissListener onDismissListener) {
        this.context = context;
        this.onErrorListener = onErrorListener;
        this.onDismissListener = onDismissListener;
    }

    public void handleError(int errorType, String message) {
        AppLog.diagnosticE("ErrorHandler", "Error: " + errorType + " - " + message);
        
        if (onErrorListener != null) {
            onErrorListener.onError(errorType, message);
        }

        String toastMessage;
        switch (errorType) {
            case ERROR_404:
                toastMessage = context.getString(R.string.error_404);
                break;
            case ERROR_SERVER:
                toastMessage = context.getString(R.string.error_server);
                break;
            case ERROR_NETWORK:
                toastMessage = context.getString(R.string.error_network);
                break;
            default:
                toastMessage = context.getString(R.string.error_unknown);
                break;
        }

        showToast(toastMessage);
    }

    public void handleJsError(String message) {
        AppLog.diagnosticE("ErrorHandler", "JS Error: " + message);
        
        if (onErrorListener != null) {
            onErrorListener.onError(ERROR_JS, message);
        }

        showToast("JavaScript错误: " + message);
    }

    public void showError(int errorType, String message) {
        // 可以在这里实现更复杂的错误UI展示
        AppLog.diagnosticE("ErrorHandler", "Show Error: " + errorType + " - " + message);
    }

    private void showToast(String message) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show();
    }

    public void dismiss() {
        if (onDismissListener != null) {
            onDismissListener.onDismiss();
        }
    }
}
