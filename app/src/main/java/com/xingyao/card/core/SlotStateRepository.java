package com.xingyao.card.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Android-side card-slot state cache. Serial/MQTT code updates this repository,
 * while UI and backend reports read snapshots from it.
 */
public final class SlotStateRepository {
    private final Object lock = new Object();
    private final LinkedHashMap<Integer, JSONObject> slots = new LinkedHashMap<>();
    private int totalSlots = 100;
    private int singleGroupCount = 10;

    public void configure(JSONObject settings) {
        synchronized (lock) {
            totalSlots = parsePositiveInt(settings == null ? null : settings.opt("totalCount"), 100);
            singleGroupCount = parsePositiveInt(settings == null ? null : settings.opt("singleGroupCount"), 10);
            if (singleGroupCount > totalSlots) singleGroupCount = totalSlots;
            ensureSlotsLocked();
        }
    }

    public JSONObject updateSlot(JSONObject incoming) throws JSONException {
        if (incoming == null) return null;
        int slotNumber = incoming.optInt("slotNumber", incoming.optInt("slotId", -1));
        if (slotNumber < 1) return null;
        synchronized (lock) {
            if (slotNumber > totalSlots) totalSlots = slotNumber;
            ensureSlotsLocked();
            JSONObject merged = slots.containsKey(slotNumber)
                    ? new JSONObject(slots.get(slotNumber).toString())
                    : defaultSlot(slotNumber);
            copyFields(incoming, merged);
            enrichSlotLocked(merged, slotNumber);
            slots.put(slotNumber, merged);
            return new JSONObject(merged.toString());
        }
    }

    public JSONObject getSlot(int slotNumber) throws JSONException {
        synchronized (lock) {
            ensureSlotsLocked();
            JSONObject slot = slots.get(slotNumber);
            return slot == null ? null : new JSONObject(slot.toString());
        }
    }

    public JSONArray snapshotSlots() throws JSONException {
        synchronized (lock) {
            ensureSlotsLocked();
            JSONArray result = new JSONArray();
            for (int slotNumber = 1; slotNumber <= totalSlots; slotNumber++) {
                JSONObject slot = slots.get(slotNumber);
                if (slot != null) result.put(new JSONObject(slot.toString()));
            }
            return result;
        }
    }

    public JSONArray snapshotBackendSlots() throws JSONException {
        JSONArray source = snapshotSlots();
        JSONArray result = new JSONArray();
        for (int index = 0; index < source.length(); index++) result.put(toBackendSlot(source.getJSONObject(index)));
        return result;
    }

    public JSONArray snapshotBackendSlots(int slotId) throws JSONException {
        if (slotId > 0) {
            JSONObject slot = getSlot(slotId);
            JSONArray result = new JSONArray();
            if (slot != null) result.put(toBackendSlot(slot));
            return result;
        }
        return snapshotBackendSlots();
    }

    public JSONObject summary() throws JSONException {
        JSONArray source = snapshotSlots();
        int empty = 0;
        int occupied = 0;
        int charging = 0;
        int full = 0;
        int fault = 0;
        int unknown = 0;
        int known = 0;
        for (int index = 0; index < source.length(); index++) {
            JSONObject slot = source.getJSONObject(index);
            if (slot.optLong("updatedAt", 0L) > 0L) known++;
            String status = mapBackendStatus(slot.optString("status"));
            if ("EMPTY".equals(status)) empty++;
            else if ("CHARGING".equals(status)) charging++;
            else if ("FULL".equals(status)) full++;
            else if ("FAULT".equals(status)) fault++;
            else if ("UNKNOWN".equals(status)) unknown++;
            else occupied++;
        }
        return new JSONObject()
                .put("knownSlots", known)
                .put("totalSlots", totalSlots)
                .put("singleGroupCount", singleGroupCount)
                .put("emptyCount", empty)
                .put("occupiedCount", occupied)
                .put("chargingCount", charging)
                .put("fullCount", full)
                .put("faultCount", fault)
                .put("unknownCount", unknown);
    }

    public JSONObject toBackendSlot(JSONObject slot) throws JSONException {
        return new JSONObject()
                .put("slotId", slot.optInt("slotNumber"))
                .put("status", mapBackendStatus(slot.optString("status")))
                .put("cardNo", slot.optString("cardNumber", ""))
                .put("voltage", slot.optDouble("voltage", 0D))
                .put("current", slot.optDouble("current", 0D))
                .put("chargeStatus", mapChargeStatus(slot.optString("status"), slot.optString("workStatus", "")))
                .put("faultCode", parseFaultCode(slot.optString("faultCode", "")));
    }

    public int pickTakeSlot() {
        synchronized (lock) {
            ensureSlotsLocked();
            for (Map.Entry<Integer, JSONObject> entry : slots.entrySet()) {
                String status = entry.getValue().optString("status", "");
                if ("FULL".equals(status) || "CHARGING".equals(status) || "OCCUPIED".equals(status)) {
                    return entry.getKey();
                }
            }
        }
        return -1;
    }

    private void ensureSlotsLocked() {
        for (int slotNumber = 1; slotNumber <= totalSlots; slotNumber++) {
            if (!slots.containsKey(slotNumber)) {
                try { slots.put(slotNumber, defaultSlot(slotNumber)); }
                catch (JSONException ignored) { }
            }
        }
        slots.entrySet().removeIf(entry -> entry.getKey() > totalSlots);
    }

    private JSONObject defaultSlot(int slotNumber) throws JSONException {
        JSONObject slot = new JSONObject()
                .put("id", "slot-" + slotNumber)
                .put("slotNumber", slotNumber)
                .put("displayNumber", String.format(Locale.US, "%02d", slotNumber))
                .put("status", "UNKNOWN")
                .put("presenceStatus", "未知")
                .put("workStatus", "未知")
                .put("doorStatus", "未知")
                .put("cardNumber", "")
                .put("faultCode", "")
                .put("faultMessage", "")
                .put("voltage", JSONObject.NULL)
                .put("current", JSONObject.NULL)
                .put("updatedAt", 0L);
        enrichSlotLocked(slot, slotNumber);
        return slot;
    }

    private void enrichSlotLocked(JSONObject slot, int slotNumber) throws JSONException {
        int groupSize = Math.max(1, singleGroupCount);
        int groupNumber = ((slotNumber - 1) / groupSize) + 1;
        int groupSlotNumber = ((slotNumber - 1) % groupSize) + 1;
        slot.put("slotNumber", slotNumber)
                .put("displayNumber", String.format(Locale.US, "%02d", slotNumber))
                .put("groupNumber", groupNumber)
                .put("groupSlotNumber", groupSlotNumber)
                .put("boardAddress", groupSlotNumber)
                .put("boardAddressLabel", "BOARD-" + String.format(Locale.US, "%02d", groupSlotNumber));
    }

    private static void copyFields(JSONObject source, JSONObject target) throws JSONException {
        java.util.Iterator<String> keys = source.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            target.put(key, source.opt(key));
        }
    }

    private static String mapBackendStatus(String status) {
        if ("EMPTY".equals(status)) return "EMPTY";
        if ("CHARGING".equals(status)) return "CHARGING";
        if ("FULL".equals(status)) return "FULL";
        if ("OCCUPIED".equals(status)) return "OCCUPIED";
        if ("UNKNOWN".equals(status)) return "UNKNOWN";
        if ("CHARGING_FAULT".equals(status) || "COMMUNICATION_FAULT".equals(status) || "ILLEGAL_CARD".equals(status)) return "FAULT";
        return "UNKNOWN";
    }

    private static String mapChargeStatus(String status, String workStatus) {
        if ("CHARGING".equals(status) || "充电中".equals(workStatus)) return "CHARGING";
        if ("FULL".equals(status) || "充电结束".equals(workStatus)) return "FULL";
        if ("EMPTY".equals(status)) return "IDLE";
        return "IDLE";
    }

    private static int parseFaultCode(String value) {
        if (value == null || value.isEmpty()) return 0;
        try { return value.startsWith("0x") || value.startsWith("0X") ? Integer.parseInt(value.substring(2), 16) : Integer.parseInt(value); }
        catch (Exception ignored) { return 0; }
    }

    private static int parsePositiveInt(Object value, int fallback) {
        try {
            int result = Integer.parseInt(String.valueOf(value));
            return result > 0 ? result : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
