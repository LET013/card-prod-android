package com.xingyao.card.core;

import android.util.Log;

import com.xingyao.card.core.serial.SerialMessageRouter;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 卡槽实时状态管理器。
 *
 * - 缓存所有卡槽的最新状态（Map<Integer, JSONObject>）。
 * - 通过 SerialMessageRouter 的查询应答更新状态。
 * - 提供订阅机制：订阅者定时收到完整快照，替代逐条 notifySlot。
 * - 兜底机制：无论是否有变化，每 60s 推送一次全量快照。
 */
public final class SlotStateManager {
    private static final String TAG = "SlotStateManager";
    private static final long DEFAULT_PUSH_INTERVAL_MS = 60_000L; // 60s 全量快照

    /** 全量订阅者回调 */
    public interface Subscriber {
        void onSnapshot(Map<Integer, JSONObject> allSlots);
    }

    private final ConcurrentHashMap<Integer, JSONObject> slotStates = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<SubscriberEntry> subscribers = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService pushExecutor = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> pushTask;

    /** 订阅者条目 */
    private static class SubscriberEntry {
        final Subscriber subscriber;
        final long intervalMs;
        volatile long lastPushAt;

        SubscriberEntry(Subscriber subscriber, long intervalMs) {
            this.subscriber = subscriber;
            this.intervalMs = intervalMs;
            this.lastPushAt = System.currentTimeMillis();
        }
    }

    // ────────────────────── 状态更新 ──────────────────────

    /**
     * 收到查询应答时更新单个卡槽状态。
     * 由 DeviceSerialManager 调用（在 SerialMessageRouter 的 onQueryResponse 回调中）。
     */
    public void updateSlot(int address, JSONObject slotStatus) {
        slotStates.put(address, slotStatus);
    }

    /**
     * 通信超时时标记卡槽为通信故障。
     * 由 DeviceSerialManager 的 PollingScheduler 调用。
     */
    public void markCommunicationFault(int address, String reason) {
        try {
            JSONObject fault = new SerialMessageRouter().communicationFault(address, reason);
            slotStates.put(address, fault);
        } catch (JSONException e) {
            Log.e(TAG, "markCommunicationFault error", e);
        }
    }

    /** 获取单个卡槽的当前缓存状态 */
    public JSONObject getSlot(int address) {
        return slotStates.get(address);
    }

    /** 获取全量卡槽状态快照（不可变副本） */
    public Map<Integer, JSONObject> getSnapshot() {
        return new HashMap<>(slotStates);
    }

    /** 卡槽总数（已有状态记录的槽位数） */
    public int slotCount() {
        return slotStates.size();
    }

    // ────────────────────── 订阅 ──────────────────────

    /**
     * 订阅全量卡槽状态推送。
     * 订阅后立即收到一次当前快照，之后每 intervalMs 收到全量快照。
     *
     * @param subscriber  订阅回调
     * @param intervalMs  推送间隔（毫秒），默认 60s
     */
    public void subscribe(Subscriber subscriber, long intervalMs) {
        long interval = intervalMs > 0 ? intervalMs : DEFAULT_PUSH_INTERVAL_MS;
        SubscriberEntry entry = new SubscriberEntry(subscriber, interval);
        subscribers.add(entry);
        // 立即推送一次当前快照
        try {
            subscriber.onSnapshot(getSnapshot());
        } catch (Exception e) {
            Log.e(TAG, "subscribe initial push error", e);
        }
        ensurePushTaskRunning();
    }

    /** 取消订阅 */
    public void unsubscribe(Subscriber subscriber) {
        for (SubscriberEntry entry : subscribers) {
            if (entry.subscriber == subscriber) {
                subscribers.remove(entry);
            }
        }
        if (subscribers.isEmpty()) stopPushTask();
    }

    /**
     * 立即向所有订阅者推送当前快照（忽略间隔限制）。
     * 用于首次轮询完成后立即上报，不等待定期推送。
     */
    public void pushSnapshotImmediate() {
        Map<Integer, JSONObject> snapshot = getSnapshot();
        if (snapshot.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (SubscriberEntry entry : subscribers) {
            try {
                entry.subscriber.onSnapshot(snapshot);
            } catch (Exception e) {
                Log.e(TAG, "pushSnapshotImmediate error", e);
            }
            entry.lastPushAt = now;
        }
    }

    // ────────────────────── 内部推送调度 ──────────────────────

    private synchronized void ensurePushTaskRunning() {
        if (pushTask != null && !pushTask.isCancelled()) return;
        pushTask = pushExecutor.scheduleAtFixedRate(
                this::pushToSubscribers,
                DEFAULT_PUSH_INTERVAL_MS,
                DEFAULT_PUSH_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
    }

    private synchronized void stopPushTask() {
        if (pushTask != null) {
            pushTask.cancel(false);
            pushTask = null;
        }
    }

    private void pushToSubscribers() {
        Map<Integer, JSONObject> snapshot = getSnapshot();
        long now = System.currentTimeMillis();
        for (SubscriberEntry entry : subscribers) {
            if (now - entry.lastPushAt >= entry.intervalMs) {
                try {
                    entry.subscriber.onSnapshot(snapshot);
                } catch (Exception e) {
                    Log.e(TAG, "pushToSubscribers error", e);
                }
                entry.lastPushAt = now;
            }
        }
    }

    // ────────────────────── 生命周期 ──────────────────────

    public void start() {
        // 订阅推送在首次 subscribe 时自动启动
    }

    public void stop() {
        stopPushTask();
        pushExecutor.shutdown();
        slotStates.clear();
        subscribers.clear();
    }
}
