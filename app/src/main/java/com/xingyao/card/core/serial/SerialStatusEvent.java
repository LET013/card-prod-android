package com.xingyao.card.core.serial;

import org.json.JSONObject;

/** Native serial runtime status forwarded to the Vue capability consumer. */
public final class SerialStatusEvent {
    public final JSONObject status;

    public SerialStatusEvent(JSONObject status) {
        this.status = status;
    }
}
