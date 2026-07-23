package com.xingyao.card;

import android.util.Log;

import com.xingyao.card.core.DeviceApplicationFacade;

import org.json.JSONException;
import org.json.JSONObject;

/** Trusted WebView message adapter. Business/data decisions live in DeviceApplicationFacade. */
public class JsBridge {
    private static final String TAG = "JsBridge";

    private final MainActivity activity;
    private final DeviceApplicationFacade facade;

    public JsBridge(MainActivity activity) {
        this.activity = activity;
        this.facade = new DeviceApplicationFacade(activity);
    }

    /** Called only by WebViewCompat.WebMessageListener after exact origin and main-frame validation. */
    public void handleTrustedMessage(String rawMessage) {
        dispatch(rawMessage);
    }

    private void dispatch(String rawMessage) {
        String requestId = "";
        try {
            JSONObject request = new JSONObject(rawMessage == null ? "{}" : rawMessage);
            requestId = request.optString("requestId", "");
            String action = request.optString("action", "").trim();
            JSONObject payload = request.optJSONObject("payload");
            if (payload == null) payload = new JSONObject();

            DeviceApplicationFacade.ActionResult result = facade.execute(action, payload, requestId);
            if (result.isDeferred()) return;

            sendSuccess(requestId, result.getData());
            if ("app.ready".equals(action)) {
                activity.sendBridgeEvent("native.ready", new JSONObject().put("ready", true));
            }
        } catch (DeviceApplicationFacade.FacadeException error) {
            sendError(requestId, error.getCode(), safeMessage(error));
        } catch (Exception error) {
            Log.e(TAG, "Invalid bridge request", error);
            sendError(requestId, "INVALID_REQUEST", safeMessage(error));
        }
    }

    private void sendSuccess(String requestId, JSONObject data) {
        try {
            JSONObject response = new JSONObject()
                    .put("type", "response")
                    .put("requestId", requestId)
                    .put("success", true)
                    .put("data", data == null ? JSONObject.NULL : data);
            activity.sendBridgeResponse(response);
        } catch (JSONException error) {
            Log.e(TAG, "Unable to create success response", error);
        }
    }

    private void sendError(String requestId, String code, String message) {
        try {
            JSONObject response = new JSONObject()
                    .put("type", "response")
                    .put("requestId", requestId)
                    .put("success", false)
                    .put("code", code)
                    .put("message", message == null ? code : message);
            activity.sendBridgeResponse(response);
        } catch (JSONException error) {
            Log.e(TAG, "Unable to create error response", error);
        }
    }

    public void close() {
        facade.close();
    }

    private static String safeMessage(Throwable error) {
        if (error == null) return "unknown";
        String value = error.getMessage();
        return value == null || value.trim().isEmpty()
                ? error.getClass().getSimpleName() : value;
    }
}
