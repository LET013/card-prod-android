package com.xingyao.card.core.serial;

import org.json.JSONArray;

/** Full serial snapshot forwarded to the trusted WebView after native collection. */
public final class SlotSnapshotEvent {
    public final JSONArray slots;

    public SlotSnapshotEvent(JSONArray slots) {
        this.slots = slots;
    }
}
