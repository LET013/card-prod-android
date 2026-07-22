package com.xingyao.card.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Android data-layer state store.
 *
 * All device/business truth that is exposed to the UI is written here first. Communication
 * adapters never talk to Vue directly. Vue receives immutable snapshots/events emitted after
 * the store has accepted a change.
 */
public final class DeviceStateStore {
    public interface Listener {
        void onStateChanged(String event, JSONObject data);
    }

    private static final int MAX_OPERATION_CACHE = 200;

    private final Object lock = new Object();
    private final LinkedHashMap<String, JSONObject> sections = new LinkedHashMap<>();
    private final LinkedHashMap<String, JSONObject> operations = new LinkedHashMap<>();
    private final SlotStateRepository slotRepository;
    private final DeviceDataRepository dataRepository;
    private final DeviceEventLogRepository eventLogRepository;
    private volatile Listener listener;

    public DeviceStateStore(SlotStateRepository slotRepository,
                            DeviceDataRepository dataRepository,
                            DeviceEventLogRepository eventLogRepository) {
        if (slotRepository == null) throw new IllegalArgumentException("slotRepository is required");
        if (dataRepository == null) throw new IllegalArgumentException("dataRepository is required");
        if (eventLogRepository == null) throw new IllegalArgumentException("eventLogRepository is required");
        this.slotRepository = slotRepository;
        this.dataRepository = dataRepository;
        this.eventLogRepository = eventLogRepository;
        seedDefaults();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void configure(JSONObject settings) {
        slotRepository.configure(settings == null ? new JSONObject() : settings);
    }

    public JSONObject updateSection(String section, String event, JSONObject value) {
        JSONObject copy = copy(value);
        synchronized (lock) {
            sections.put(section, copy);
        }
        record("state." + section, copy);
        emit(event, copy);
        return copy(copy);
    }

    public JSONObject updateSlot(JSONObject incoming) throws JSONException {
        JSONObject updated = slotRepository.updateSlot(incoming);
        if (updated == null) return null;
        record("state.slot", updated);
        emit("cabinet.slotStatus", updated);
        return updated;
    }

    public void recordOperation(String category, JSONObject payload) {
        JSONObject copy = copy(payload);
        String operationId = copy.optString("operationId", "").trim();
        if (!operationId.isEmpty()) {
            synchronized (lock) {
                operations.put(operationId, copy);
                while (operations.size() > MAX_OPERATION_CACHE) {
                    Iterator<String> iterator = operations.keySet().iterator();
                    if (!iterator.hasNext()) break;
                    iterator.next();
                    iterator.remove();
                }
            }
            emit("operation.changed", copy);
        }
        record(category, copy);
    }

    public void record(String category, JSONObject payload) {
        eventLogRepository.append(category, payload == null ? new JSONObject() : payload);
    }

    public void emit(String event, JSONObject data) {
        if (event == null || event.trim().isEmpty()) return;
        Listener current = listener;
        if (current == null) return;
        try {
            current.onStateChanged(event, copy(data));
        } catch (Exception ignored) {
            // UI delivery must never break the data layer.
        }
    }

    public JSONObject snapshot() throws JSONException {
        JSONObject result = new JSONObject();
        synchronized (lock) {
            for (Map.Entry<String, JSONObject> item : sections.entrySet()) {
                result.put(item.getKey(), copy(item.getValue()));
            }
            JSONArray activeOperations = new JSONArray();
            for (JSONObject operation : operations.values()) activeOperations.put(copy(operation));
            result.put("operations", activeOperations);
        }
        result.put("slots", slotRepository.snapshotSlots());
        return result;
    }

    public JSONObject section(String name) {
        synchronized (lock) {
            JSONObject value = sections.get(name);
            return value == null ? new JSONObject() : copy(value);
        }
    }

    public JSONObject slotsSnapshot() throws JSONException {
        return new JSONObject()
                .put("slots", slotRepository.snapshotSlots())
                .put("summary", slotRepository.summary());
    }

    public JSONArray backendSlots(int slotId) throws JSONException {
        return slotRepository.snapshotBackendSlots(slotId);
    }

    public JSONArray backendSlots() throws JSONException {
        return slotRepository.snapshotBackendSlots();
    }

    public JSONObject slotSummary() throws JSONException {
        return slotRepository.summary();
    }

    public JSONObject getSlot(int slotId) throws JSONException {
        return slotRepository.getSlot(slotId);
    }

    public int pickTakeSlot() {
        return slotRepository.pickTakeSlot();
    }

    public JSONArray searchEmployees(String query) throws JSONException {
        return dataRepository.searchEmployees(query);
    }

    public String deleteEmployee(String id) throws JSONException {
        String employeeId = dataRepository.deleteEmployee(id);
        if (!employeeId.isEmpty()) {
            JSONObject event = new JSONObject().put("success", true)
                    .put("id", id).put("employeeId", employeeId);
            record("state.employee.deleted", event);
            emit("sync.employeeChanged", event);
        }
        return employeeId;
    }

    public JSONObject businessDataSnapshot() throws JSONException {
        return dataRepository.snapshot();
    }


    private void seedDefaults() {
        synchronized (lock) {
            sections.put("serial", state("DISCONNECTED", "串口通信未启动"));
            sections.put("socket", state("DISCONNECTED", "MQTT通信未启动"));
            sections.put("http", state("PENDING", "HTTP通信未初始化"));
            sections.put("sync", state("PENDING", "业务数据尚未同步"));
            sections.put("recognitionEngine", state("STOPPED", "人脸引擎未启动"));
            sections.put("deviceAuthorization", state("AUTHORIZED", "已授权"));
        }
    }

    private static JSONObject state(String state, String message) {
        try {
            return new JSONObject().put("state", state).put("message", message);
        } catch (JSONException ignored) {
            return new JSONObject();
        }
    }

    private static JSONObject copy(JSONObject value) {
        if (value == null) return new JSONObject();
        try {
            return new JSONObject(value.toString());
        } catch (JSONException ignored) {
            return new JSONObject();
        }
    }
}
