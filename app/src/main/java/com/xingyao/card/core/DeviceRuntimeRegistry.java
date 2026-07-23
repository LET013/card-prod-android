package com.xingyao.card.core;

/** Process-local registry connecting Activity/WebView UI to the Android data layer. */
public final class DeviceRuntimeRegistry {
    private static volatile DeviceDataLayer dataLayer;
    private static volatile DeviceStateStore.Listener uiListener;
    private static volatile boolean pendingFaceRestart;

    private DeviceRuntimeRegistry() { }

    public static synchronized void install(DeviceDataLayer layer) {
        dataLayer = layer;
        if (layer != null) {
            layer.setUiListener(uiListener);
            if (pendingFaceRestart) {
                pendingFaceRestart = false;
                layer.restartFaceRecognition();
            }
        }
    }

    public static synchronized void clear(DeviceDataLayer layer) {
        if (dataLayer == layer) dataLayer = null;
    }

    public static DeviceDataLayer get() {
        return dataLayer;
    }

    public static DeviceDataLayer require() {
        DeviceDataLayer current = dataLayer;
        if (current == null) throw new IllegalStateException("Android数据层尚未启动");
        return current;
    }

    public static synchronized void setUiListener(DeviceStateStore.Listener listener) {
        uiListener = listener;
        if (dataLayer != null) dataLayer.setUiListener(listener);
    }

    public static synchronized void requestFaceRestart() {
        if (dataLayer == null) pendingFaceRestart = true;
        else dataLayer.restartFaceRecognition();
    }

    public static void record(String category, org.json.JSONObject payload) {
        DeviceDataLayer current = dataLayer;
        if (current != null) current.recordOperation(category, payload);
    }
}
