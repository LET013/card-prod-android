package com.xingyao.card.core.bootstrap;

import android.util.Log;

import com.xingyao.card.core.biz.http.DeviceApiService;
import com.xingyao.card.core.entity.http.HeartbeatRequest;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** HTTP 降级模式心跳。只在 HTTP 登录成功后启动，网络请求始终在后台线程执行。 */
public final class HttpHeartbeatManager {
    private static final String TAG = "HttpHeartbeatManager";
    private static final long DEFAULT_HEARTBEAT_INTERVAL_MS = 60_000L;

    private final DeviceApiService apiService;
    private final AtomicInteger sequence = new AtomicInteger(0);
    private volatile long heartbeatIntervalMs = DEFAULT_HEARTBEAT_INTERVAL_MS;
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> heartbeatFuture;

    public HttpHeartbeatManager(DeviceApiService apiService) {
        this.apiService = apiService;
    }

    public void setHeartbeatInterval(long intervalMs) {
        heartbeatIntervalMs = intervalMs >= 1000 ? intervalMs : DEFAULT_HEARTBEAT_INTERVAL_MS;
    }

    public synchronized void start() {
        if (heartbeatFuture != null && !heartbeatFuture.isDone()) return;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "Http-Heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        heartbeatFuture = executor.scheduleWithFixedDelay(
                this::sendHeartbeat,
                0L,
                heartbeatIntervalMs,
                TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(true);
            heartbeatFuture = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private void sendHeartbeat() {
        try {
            apiService.heartbeat(new HeartbeatRequest(sequence.incrementAndGet()));
        } catch (Exception error) {
            Log.w(TAG, "HTTP heartbeat failed: " + error.getMessage());
        }
    }
}
