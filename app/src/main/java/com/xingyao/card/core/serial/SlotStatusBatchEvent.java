package com.xingyao.card.core.serial;

import org.json.JSONArray;

/** A bounded batch of changed slot states from the native serial capability. */
public final class SlotStatusBatchEvent {
    public final JSONArray slots;

    public SlotStatusBatchEvent(JSONArray slots) {
        this.slots = slots;
    }
}
