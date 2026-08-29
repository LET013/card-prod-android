# 串口轮询方案重设计

## 一、当前实现的问题

当前 `SerialConnectionManager.pollNext()`（第 266-307 行）存在以下问题：

### 1. 轮询线程直接做同步 I/O

```java
// 当前代码 — 问题所在
private void pollNext() {
    // ... 超时判断、地址计算 ...
    writeRaw(WorkCardProtocol.query(address), "poll.query"); // ← 在轮询线程中同步 write
}
```

`writeRaw()` → `serialManager.send()` 直接在 `ScheduledExecutorService` 的线程里做同步串口写操作。如果串口写入阻塞（如驱动层 buffer 满），整个轮询周期被延迟，可能导致连锁超时。

### 2. 轮询与手动命令互斥竞争

轮询和手动命令（`openDoor`、`querySlot` 等）共用 `writeRaw()` 和 `synchronized` 锁，但没有任何排队机制：
- 手动命令的到来时机不可预测，可能与轮询冲突
- `pendingAddress` 只有 1 个槽位，但两个路径都可能设置它
- 手动命令不会被轮询的超时机制正确跟踪

### 3. 单槽位响应匹配不可靠

```java
private int pendingAddress = -1;  // 只有一个！
```

同时只有一个 `pendingAddress`，当轮询和手动命令并发时，响应可能被错误匹配。

### 4. 超时检测依赖轮询周期

超时判断在 `pollNext()` 中执行（每 350ms 一次），实际超时发生到被检测之间最多有 350ms 的额外延迟。响应超时应该是事件驱动的，不应依赖定时器碰巧执行。

### 5. 无法区分响应类型

`handleFrame()` 只根据 `function` 码分发，无法区分同一 address 的查询响应和开门响应——如果之前发了一次手动开门后立刻收到下一轮轮询的响应，解析可能会混淆。

### 6. 单槽位顺序轮询效率低

当前每个周期只轮询一个槽位（1, 2, 3, ...），100 个槽位需要 100 × 350ms = 35s 才能完成一轮。实际需求是对一段范围（如 1-100）的所有槽位快速轮询，但需防止同一槽位在队列中重复堆积。

---

## 二、新方案核心思路

**设计原则：** `SerialConnectionManager` 只做纯串口字节收发。所有协议感知逻辑（轮询、命令构造、帧匹配、优先级调度）全部上移到 `DeviceSerialManager`。

**三个关键变化：**

1. **解耦发送与调度** — 发送队列 + 独立工作线程，定时器只加队列不做 I/O
2. **双队列实现优先级** — 手动命令立即插队，轮询任务排队
3. **批量范围轮询 + 去重** — 一次性加入 `[start, end]` 范围内所有槽位，每个槽位最多一个待处理任务

**所有权变更：**

| 逻辑 | 之前位置 | 之后位置 |
|------|---------|---------|
| 串口连接/断开/重连 | SerialConnectionManager | SerialConnectionManager（不变） |
| 字节写入（write） | SerialConnectionManager.writeRaw() | SerialConnectionManager.write(byte[]) ✅ 纯字节 |
| 帧构造（WorkCardProtocol） | SerialConnectionManager | DeviceSerialManager ⬆️ 上移 |
| 双队列 + SendWorker + 去重 | —（新设计） | DeviceSerialManager ⬆️ 上移 |
| 轮询调度 + enqueuePollBatch | —（新设计） | DeviceSerialManager ⬆️ 上移 |
| 手动命令（openDoor等） | SerialConnectionManager | DeviceSerialManager ⬆️ 上移 |
| 帧解析与路由 | SerialConnectionManager.handleFrame | SerialMessageRouter ➡️ 新建 |
| 卡槽状态缓存 + 订阅 | —（新设计） | SlotStateManager ➡️ 新建 |

```
┌───────────────────────────────────────────────────────────────────────────┐
│                        DeviceSerialManager                                 │
│  ┌─────────────────────────────────────────────────────────────────────┐  │
│  │  Polling Scheduler (ScheduledExecutor, 350ms)                        │  │
│  │  enqueuePollBatch() → 遍历 [start, end] → offer → pollQueue          │  │
│  └───────────────────────────┬─────────────────────────────────────────┘  │
│                              │ offer(SendTask)                             │
│  ┌──────────────────┐       ▼       ┌───────────────────┐                 │
│  │  手动命令         │  put()       │   pollQueue        │  低优先级       │
│  │  openDoor()      │─────▶─────▶  │  ArrayBlockingQueue │                 │
│  │  querySlot()     │              └────────┬──────────┘                 │
│  │  readVersion()   │── put() ──▶          │                              │
│  │                  │  ┌───────────────────┐│ poll(1s)                     │
│  │                  │  │  manualQueue      │◀┘                             │
│  │                  │  │  ArrayBlockingQueue│ 高优先级                     │
│  │                  │  └────────┬──────────┘                              │
│  └──────────────────┘           │ poll() 非阻塞，每次循环先检查            │
│                                 ▼                                         │
│                      ┌─────────────────────────┐                          │
│                      │  SendWorker Thread       │  DeviceSerialManager 内部 │
│                      │  1. manualQueue.poll()   │  先检查手动命令(非阻塞)   │
│                      │  2. pollQueue.poll(1s)   │  再等轮询任务(阻塞超时)   │
│                      │  3. currentTask.set(task) │  设置当前上下文           │
│                      │  4. serialManager.write()│  纯字节写入(阻塞直到发出)  │
│                      │  5. latch.await()        │  等待匹配响应(仅手动)      │
│                      └────────────┬────────────┘                          │
│                                   │ write(byte[])                          │
│  ┌────────────────────────────────┼────────────────────────────────────┐  │
│  │  SerialMessageRouter           │                                    │  │
│  │  onRawBytes() → decode         │                                    │  │
│  │  → route(frame)                │                                    │  │
│  │    QUERY → SlotStateManager    │◀── onDataReceived callback          │  │
│  │    DOOR  → matchTask + latch   │                                    │  │
│  └────────────────────────────────┼────────────────────────────────────┘  │
│  ┌────────────────────────────────┼────────────────────────────────────┐  │
│  │  SlotStateManager              │                                    │  │
│  │  cache: Map<addr, SlotState>   │                                    │  │
│  │  subscribe(intervalMs) → 定时全量推送                                │  │
│  └────────────────────────────────┘                                    │  │
└───────────────────────────────────┬───────────────────────────────────────┘
                                    │ write(byte[])   onDataReceived(byte[])
                                    ▼
┌───────────────────────────────────────────────────────────────────────────┐
│                    SerialConnectionManager (纯串口收发)                      │
│                                                                           │
│  职责: 连接/断开串口、线程安全写字节、异步收字节通知、统计                       │
│  不知: WorkCardProtocol、帧、地址、槽位、轮询、命令、响应匹配                  │
│                                                                           │
│  ┌─────────────────┐         ┌──────────────────┐                         │
│  │  write(byte[])   │────────▶│  WriteWorker      │  单线程保证串行         │
│  │  (阻塞直到发出)    │         │  SerialManager    │                         │
│  └─────────────────┘         │  .send(data)      │                         │
│                              └────────┬─────────┘                         │
│  ┌─────────────────┐                  │ 异步回调(ReadThread)                │
│  │  onDataReceived  │◀────────────────┘                                   │
│  │  (byte[] callback)│                                                      │
│  └─────────────────┘                                                      │
│  公开方法: write(byte[]), open, close, reconnect, isOpen, snapshot, stats  │
└───────────────────────────────────────────────────────────────────────────┘
```

### 关键设计决策

| 决策 | 选择 | 理由 |
|------|------|------|
| 优先级机制 | **双队列**（manualQueue + pollQueue） | 无需 PriorityBlockingQueue 的堆开销；手动命令 `poll()` 非阻塞检查，零延迟 |
| 轮询入队 | **批量范围 + 去重** | 一次性加入 [start, end] 全部槽位；`pendingPollSlots[]` 防止同一槽位重复入队 |
| 发送线程 | **独立 Thread**（非 ScheduledExecutor） | `queue.take()` 阻塞等待，空闲零 CPU；超时通过 `CountDownLatch.await(timeout)` 管理 |
| 轮询调度 | **独立 ScheduledExecutor** | 只看队列状态和 pending 标记，加任务或跳过；与发送逻辑完全解耦 |
| 队列类型 | **ArrayBlockingQueue**（手动/轮询各一个） | 有界防止 OOM；手动用 `put()`（阻塞保证不丢），轮询用 `offer()`（满则丢弃） |
| 响应匹配 | **AtomicReference\<SendTask\>** | 替换 `pendingAddress`；存储完整任务上下文（地址、功能码、回调） |
| 手动命令同步 | **CountDownLatch** | 调用方 `task.latch.await(timeout)`，收到响应或超时后返回 |

### 一次完整的轮询周期示例

以 100 个槽位（地址 1-100）为例：

```
T=0ms    Polling Scheduler 触发: 遍历 1..100 → offer 100 个任务到 pollQueue
         pendingPollSlots[1..100] = true
         SendWorker: 从 pollQueue 取 slot=1 → pending[1]=false → 发送 → 异步收响应

T=350ms  Polling Scheduler 触发: 遍历 1..100
         slot=1: pending[1]=false → offer 入队 → pending[1]=true  （重新排队）
         slot=2: pending[2]=true  → 跳过（还在队列里）
         slot=3: pending[3]=true  → 跳过
         ...（大部分都在队列里，跳过）
         slot=N: 如果已被 SendWorker 消费 → pending[N]=false → 重新入队

         此时 SendWorker 已发出约 350/ΔT 个轮询（假设每帧 5ms，则约 70 个）
         这些已发出的槽位 pending 已清除，新周期会重新入队
```

**结果**：每个槽位始终在队列中保持恰好一个待发送任务，无重复堆积。

---

## 三、详细设计（DeviceSerialManager 内部）

> **注意：** 以下所有组件（SendTask、双队列、SendWorker、Polling Scheduler、去重机制）都是 `DeviceSerialManager` 的内部实现。`SerialConnectionManager` 只提供纯字节 I/O（见 [第十三节](#十三serialconnectionmanager--纯串口收发)）。

### 3.1 SendTask（DeviceSerialManager 内部类）

```java
/** 手动命令优先级（放入 manualQueue），被 SendWorker 优先消费 */
static final int Q_MANUAL = 0;
/** 轮询任务优先级（放入 pollQueue），手动命令空闲时才消费 */
static final int Q_POLL = 1;

static class SendTask {
    final byte[] bytes;            // 要发送的完整帧
    final String tag;              // 日志标签: "poll.query", "door.admin", "version.read"
    final int slaveAddress;        // 目标单板地址
    final int function;            // 预期响应的功能码
    final boolean awaitResponse;   // true = 手动命令(带 latch), false = 轮询(发后不管)
    final CountDownLatch latch;    // non-null 仅当 awaitResponse==true
    final int queue;               // Q_MANUAL 或 Q_POLL
    final long sendTime;           // 发送时间戳

    volatile boolean responded;    // handleReceived 设置
    volatile boolean timedOut;     // SendWorker 超时设置
    volatile JSONObject responseData; // 收到的响应数据
    volatile String errorMessage;  // 超时或异常信息

    SendTask(byte[] bytes, String tag, int slaveAddress, int function,
             boolean awaitResponse, int queue) {
        this.bytes = bytes;
        this.tag = tag;
        this.slaveAddress = slaveAddress;
        this.function = function;
        this.awaitResponse = awaitResponse;
        this.latch = awaitResponse ? new CountDownLatch(1) : null;
        this.queue = queue;
        this.sendTime = System.currentTimeMillis();
    }

    /** 是否来自轮询队列 */
    boolean isPoll() { return queue == Q_POLL; }
}
```

### 3.2 双队列 + SendWorker

```java
// === 双队列 ===
/** 手动命令队列 — 最高优先级，SendWorker 每次循环优先检查 */
private final BlockingQueue<SendTask> manualQueue = new ArrayBlockingQueue<>(32);
/** 轮询任务队列 — 低优先级，manualQueue 为空时才消费 */
private final BlockingQueue<SendTask> pollQueue = new ArrayBlockingQueue<>(256);

// === SendWorker ===
private volatile boolean workerRunning;
private Thread sendWorker;
private final AtomicReference<SendTask> currentTask = new AtomicReference<>();

private void startSendWorker() {
    workerRunning = true;
    sendWorker = new Thread(this::runSendWorker, "SerialSendWorker");
    sendWorker.setDaemon(true);
    sendWorker.start();
}

private void stopSendWorker() {
    workerRunning = false;
    if (sendWorker != null) {
        sendWorker.interrupt();
        try { sendWorker.join(2000); } catch (InterruptedException ignored) { }
        sendWorker = null;
    }
    // 释放所有等待中的手动命令
    SendTask task = currentTask.getAndSet(null);
    if (task != null && task.latch != null) {
        task.errorMessage = "串口已关闭";
        task.latch.countDown();
    }
    manualQueue.clear();
    pollQueue.clear();
    // 清除所有 pending 标记
    synchronized (this) {
        Arrays.fill(pendingPollSlots, false);
    }
}
```

**SendWorker 主循环 — 手动优先：**

```java
private void runSendWorker() {
    while (workerRunning) {
        SendTask task;

        // 1. 优先检查手动命令队列（非阻塞）
        task = manualQueue.poll();

        // 2. 无手动命令时，等待轮询任务（阻塞超时）
        if (task == null) {
            try {
                task = pollQueue.poll(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                break; // 正常关闭
            }
        }

        if (task == null) continue; // 超时醒来，检查 workerRunning

        // 3. 取出轮询任务后，清除 pending 标记（允许下轮重新入队）
        if (task.isPoll()) {
            clearPending(task.slaveAddress);
        }

        // 4. 发送
        if (serialManager == null || !serialManager.isOpen()) {
            failTask(task, "串口未连接");
            continue;
        }

        currentTask.set(task);
        task.sendTime = System.currentTimeMillis();

        try {
            serialManager.write(task.bytes);        // ← 纯字节写入 SerialConnectionManager
            synchronized (DeviceSerialManager.this) {
                sentBytes += task.bytes.length;
            }
            notifyTx(task);
        } catch (Exception e) {
            currentTask.set(null);
            failTask(task, "发送失败: " + safeMessage(e));
            continue;
        }

        // 5. 等待响应（仅手动命令）
        if (task.awaitResponse) {
            try {
                boolean received = task.latch.await(RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (!received) {
                    task.timedOut = true;
                    task.errorMessage = "单板 " + task.slaveAddress + " 响应超时(" + RESPONSE_TIMEOUT_MS + "ms)";
                }
            } catch (InterruptedException e) {
                task.errorMessage = "等待被中断";
                task.timedOut = true;
            }
        }
        // 轮询任务不等待 — handleReceived 异步处理

        currentTask.set(null);
    }
}

private void failTask(SendTask task, String error) {
    task.errorMessage = error;
    task.timedOut = true;
    if (task.latch != null) task.latch.countDown();
}
```

**关键行为说明：**

| 场景 | manualQueue | pollQueue | SendWorker 行为 |
|------|-------------|-----------|----------------|
| 只有轮询 | 空 | 有任务 | `manualQueue.poll()` 返回 null → `pollQueue.poll(1s)` 取轮询任务 |
| 手动命令来临时 | 有任务 | 有任务 | 下一个循环 `manualQueue.poll()` 立即拿到 → 优先发送 |
| 两个都空 | 空 | 空 | `pollQueue.poll(1s)` 阻塞等待 → 1s 超时醒来检查 `workerRunning` |
| 手动命令连续到达 | 有多个 | 有任务 | 先把 `manualQueue` 中所有排队的命令发完，再回去处理轮询 |

### 3.3 Polling Scheduler — 批量范围轮询 + 去重

```java
// === 轮询范围（可配置） ===
private int pollStartAddress = 1;
private int pollEndAddress = 100;

// === 去重标记：pendingPollSlots[addr] == true 表示该槽位已有任务在 pollQueue 中 ===
private final boolean[] pendingPollSlots;

// 构造函数中初始化:
// pendingPollSlots = new boolean[totalSlots + 1];  // 1-based 索引

private ScheduledExecutorService pollingScheduler;
private ScheduledFuture<?> pollingSchedule;

private void startPolling() {
    if (polling) return;
    polling = true;
    pollingScheduler = Executors.newSingleThreadScheduledExecutor();
    pollingSchedule = pollingScheduler.scheduleAtFixedRate(
            this::enqueuePollBatch, 0, POLLING_INTERVAL_MS, TimeUnit.MILLISECONDS);
}

private void stopPolling() {
    polling = false;
    if (pollingSchedule != null) {
        pollingSchedule.cancel(false);
        pollingSchedule = null;
    }
    if (pollingScheduler != null) {
        pollingScheduler.shutdown();
        pollingScheduler = null;
    }
    // 清除所有待处理标记
    synchronized (this) {
        Arrays.fill(pendingPollSlots, false);
    }
}

/**
 * 批量轮询入队 — 每隔 POLLING_INTERVAL_MS 触发一次。
 * 遍历 [pollStartAddress, pollEndAddress]，跳过已 pending 的槽位，
 * 其余全部 offer 入 pollQueue。
 */
private void enqueuePollBatch() {
    if (!polling || serialManager == null || !serialManager.isOpen()) return;

    int enqueued = 0;
    int skipped = 0;
    int dropped = 0;

    for (int addr = pollStartAddress; addr <= pollEndAddress; addr++) {
        // 去重：该槽位已有任务在 pollQueue 中（未被 SendWorker 取出）
        if (isPending(addr)) {
            skipped++;
            continue;
        }

        byte[] frame = WorkCardProtocol.query(addr);
        SendTask task = new SendTask(frame, "poll.query", addr,
                WorkCardProtocol.FUNCTION_QUERY, false, Q_POLL);

        if (pollQueue.offer(task)) {
            markPending(addr);   // 标记为 pending
            enqueued++;
        } else {
            dropped++;           // pollQueue 满（极少发生，说明发送严重滞后）
        }
    }

    // 可选日志
    // Log.d(TAG, "pollBatch: enqueued=" + enqueued + " skipped=" + skipped + " dropped=" + dropped);
}

// === 去重辅助方法（synchronized 保证可见性） ===
private synchronized boolean isPending(int addr) {
    return addr > 0 && addr <= totalSlots && pendingPollSlots[addr];
}

private synchronized void markPending(int addr) {
    if (addr > 0 && addr <= totalSlots) {
        pendingPollSlots[addr] = true;
    }
}

private synchronized void clearPending(int addr) {
    if (addr > 0 && addr <= totalSlots) {
        pendingPollSlots[addr] = false;
    }
}
```

**去重时序详解：**

```
T=0ms    Polling Scheduler: 遍历 1..100 → 100 个 offer → pending[1..100]=true
         SendWorker: pollQueue.take() → slot=1 → clearPending(1) → pending[1]=false → send
         （此时 pending[1]=false, pending[2..100]=true）

T=350ms  Polling Scheduler: 遍历 1..100
         slot=1:  pending[1]=false → offer → pending[1]=true  ✅ 重新入队
         slot=2:  pending[2]=true  → skip  ✅ 还在队列中
         ...
         slot=70: pending[70]=false → offer → pending[70]=true（已被 SendWorker 消费）
         slot=71: pending[71]=true  → skip（还在队列中等待）
         ...
```

**结果保证**：每个槽位在 `pollQueue` 中最多存在一个待发送任务。已被 SendWorker 取出（发送中或已收到响应）的槽位，下一轮会被重新加入。

### 3.4 手动命令改造（优先级发送）

手动命令使用 `put()` 入 `manualQueue`，SendWorker 立即优先消费：

```java
public JSONObject openDoor(int slotNumber, boolean administrator) throws Exception {
    if (slotNumber < 1 || slotNumber > totalSlots)
        throw new IllegalArgumentException("卡位号必须在 1 至 " + totalSlots + " 之间");

    byte[] frame = WorkCardProtocol.openDoor(slotNumber, administrator);
    String tag = administrator ? "door.admin" : "door.issue";
    SendTask task = new SendTask(frame, tag, slotNumber,
            WorkCardProtocol.FUNCTION_OPEN_DOOR, true, Q_MANUAL);

    return sendAndWait(task);
}

public JSONObject querySlot(int slotNumber) throws Exception {
    if (slotNumber < 1 || slotNumber > totalSlots)
        throw new IllegalArgumentException("卡位号必须在 1 至 " + totalSlots + " 之间");

    byte[] frame = WorkCardProtocol.query(slotNumber);
    SendTask task = new SendTask(frame, "query.slot", slotNumber,
            WorkCardProtocol.FUNCTION_QUERY, true, Q_MANUAL);

    return sendAndWait(task);
}

public JSONObject readVersion(int slotNumber) throws Exception {
    if (slotNumber < 1 || slotNumber > totalSlots)
        throw new IllegalArgumentException("卡位号必须在 1 至 " + totalSlots + " 之间");

    byte[] frame = WorkCardProtocol.readVersion(slotNumber);
    SendTask task = new SendTask(frame, "version.read", slotNumber,
            WorkCardProtocol.FUNCTION_VERSION, true, Q_MANUAL);

    return sendAndWait(task);
}

/**
 * 手动命令同步发送：
 * 1. put 入 manualQueue（阻塞等待队列空间）
 * 2. latch.await 等待 SendWorker 发送并收到响应或超时
 * 3. 返回响应数据或抛出异常
 */
private JSONObject sendAndWait(SendTask task) throws Exception {
    manualQueue.put(task);

    boolean received = task.latch.await(RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    if (received && !task.timedOut && task.responseData != null) {
        return task.responseData;
    }
    if (task.timedOut || task.errorMessage != null) {
        throw new IOException(task.errorMessage);
    }
    throw new IOException("未知错误");
}
```

**手动命令优先级验证：**

```
假设 SendWorker 正在处理 slot=1 的轮询（取出但尚未发送完成），此时 openDoor(5) 被调用：
  openDoor → manualQueue.put(task_door_5)
  
  SendWorker 完成轮询 slot=1 → currentTask.set(null) → 回到循环开始
  → manualQueue.poll() → 拿到 task_door_5 → 立即发送 ← 跳过了 pollQueue 中 99 个轮询
```

**批量开门（仍用 manualQueue，但不等待单个响应）：**

```java
public synchronized JSONObject openAllDoors(boolean administrator) throws Exception {
    int sent = 0;
    for (int address = pollStartAddress; address <= pollEndAddress; address++) {
        byte[] frame = WorkCardProtocol.openDoor(address, administrator);
        SendTask task = new SendTask(frame, "door.all", address,
                WorkCardProtocol.FUNCTION_OPEN_DOOR, false, Q_MANUAL);
        // offer 非阻塞 — 队列满就跳过该门
        if (manualQueue.offer(task)) {
            sent++;
        }
    }
    return new JSONObject()
            .put("success", true)
            .put("successCount", sent)
            .put("failedCount", (pollEndAddress - pollStartAddress + 1) - sent)
            .put("message", "已加入发送队列 " + sent + " 条开门指令");
}
```

### 3.5 handleReceived 微调

接收入口不变，仅增加 SendTask 匹配和 latch 释放：

```java
private void handleFrame(WorkCardProtocol.Frame frame) {
    SendTask task = currentTask.get();

    // 响应匹配：地址 + 功能码双重校验
    if (task != null && task.slaveAddress == frame.slaveAddress
            && task.function == frame.function) {

        task.responded = true;
        try {
            if (frame.function == WorkCardProtocol.FUNCTION_QUERY) {
                task.responseData = parseSlotStatus(frame);
            } else if (frame.function == WorkCardProtocol.FUNCTION_OPEN_DOOR) {
                task.responseData = new JSONObject()
                        .put("success", true)
                        .put("accepted", isAccepted(frame.data));
            } else if (frame.function == WorkCardProtocol.FUNCTION_VERSION) {
                task.responseData = new JSONObject()
                        .put("version", parseVersion(frame.data));
            }
        } catch (JSONException ignored) { }

        currentTask.set(null); // 释放引用

        // 释放等待方（手动命令的 latch）
        if (task.latch != null) {
            task.latch.countDown();
        }
    }

    // 无论是否匹配，都通知监听器（轮询状态、调试数据）
    try {
        JSONObject protocol = new JSONObject()
                .put("type", "serialFrame")
                .put("address", frame.slaveAddress)
                .put("function", String.format("0x%02X", frame.function))
                .put("hex", WorkCardProtocol.hex(frame.raw));
        notifyData(protocol);

        if (frame.function == WorkCardProtocol.FUNCTION_QUERY) {
            notifySlot(parseSlotStatus(frame));
        } else if (frame.function == WorkCardProtocol.FUNCTION_OPEN_DOOR) {
            notifyData(protocol.put("command", "openDoor")
                    .put("accepted", isAccepted(frame.data)));
        } else if (frame.function == WorkCardProtocol.FUNCTION_VERSION) {
            notifyData(protocol.put("command", "version")
                    .put("version", parseVersion(frame.data)));
        }
    } catch (JSONException ignored) { }
}
```

**改动要点：**
- 先用 `currentTask.get()` 检查是否匹配，匹配则填充 `responseData` 并释放 latch
- 无论匹配与否，仍然调用 `notifySlot` / `notifyData`（保留调试和 UI 通知能力）
- 轮询响应也会走到 `notifySlot`，但因为没有 latch 等待方，不会阻塞任何人

---

## 四、需要修改 / 新增的文件

| 文件 | 变更范围 | 说明 |
|------|----------|------|
| `SerialConnectionManager.java` | 中等重构 | 双队列 + SendWorker + Polling Scheduler + 去重；移除消息解析和业务通知，改为纯串口通信 |
| `WorkCardProtocol.java` | 无 | 协议层不涉及队列逻辑 |
| `SerialMessageRouter.java` | **新增** | 消息解析与路由，根据功能码分发到不同处理器 |
| `SlotStateManager.java` | **新增** | 卡槽实时状态缓存 + 订阅推送机制 |
| `DeviceSerialManager.java` | **新增** | 设备管理门面，对外暴露 API，包装 SerialConnectionManager |
| `DeviceCoreService.java` | 较小 | SerialPort 匿名实现改为委托 `DeviceSerialManager` |

### SerialConnectionManager.java 具体改动清单（纯 I/O 化后）

**保留（纯 I/O 职责）：**

| 保留项 | 说明 |
|------|------|
| `open(port, baudRate)` | 打开串口连接 |
| `close()` | 关闭串口 |
| `reconnect()` | 重连 |
| `isOpen()` | 连接状态 |
| `write(byte[] data)` | 线程安全的纯字节写入（内部自管理写队列，单线程写入硬件） |
| `setOnDataReceived(Consumer<byte[]>)` | 注册收字节回调（替代旧 Listener 接口） |
| `snapshot()` | 纯连接层快照（端口、波特率、连接状态、收发字节数、最后收数据时间） |
| `listAvailablePorts()` | 静态工具方法 |
| `configure(device, baudRate)` | 设置端口参数 |
| `ensureDeviceAccessible()`, `tryChmodWithRoot()` | 权限处理 |

**移除（全部上移到 DeviceSerialManager 或 SerialMessageRouter / SlotStateManager）：**

| 移除项 | 迁移目标 | 原因 |
|--------|---------|------|
| `pollingExecutor`, `pollingTask`, `pollNext()`, `nextAddress` | DeviceSerialManager | 轮询是业务调度逻辑，不属纯 I/O |
| `openDoor()`, `querySlot()`, `readVersion()`, `openAllDoors()` | DeviceSerialManager | 命令是业务操作，涉及 WorkCardProtocol |
| `writeRaw()`, `writeAsync()` | DeviceSerialManager（改为直接调用 `write(byte[])`） | 冗余中间层 |
| `handleFrame()`, `pendingAddress`, `pendingAt` | SerialMessageRouter (+ DeviceSerialManager.currentTask) | 帧解析和响应匹配 |
| `parseSlotStatus()`, `mapStatus`, `mapWork`, `mapDoor`, `mapPresence` | SlotStateManager | 卡槽状态映射 |
| `notifySlot()`, `notifyData()`, `notifyStatus()` | DeviceSerialManager + SlotStateManager 订阅替代 | 逐条推送改批量订阅 |
| `Listener` 接口（onStatusChanged/onDataReceived/onSlotStatus） | 替换为 `Consumer<byte[]>` | 单一回调，纯字节 |
| `faultMessage`, `communicationFault` | SlotStateManager | 健康状态跟踪 |
| `isAccepted()`, `parseVersion()` | DeviceSerialManager（辅助方法） | 协议级解析 |

### DeviceSerialManager.java 新增内容（所有上移的逻辑）

DeviceSerialManager 接收从 SerialConnectionManager 移出的所有协议感知逻辑，同时新增双队列、SendWorker、Polling Scheduler：

| 组件 | 说明 |
|------|------|
| `manualQueue` (ArrayBlockingQueue<32>) | 手动命令队列（高优先级） |
| `pollQueue` (ArrayBlockingQueue<256>) | 轮询任务队列（低优先级） |
| `SendWorker` (Thread) | 从双队列取任务，调用 `serialManager.write()` 写入硬件 |
| `Polling Scheduler` (ScheduledExecutor) | 350ms 触发 `enqueuePollBatch()` |
| `pendingPollSlots` (boolean[]) | 去重标记 |
| `currentTask` (AtomicReference<SendTask>) | 当前正在处理的任务上下文（用于响应匹配） |
| `openDoor/querySlot/readVersion/openAllDoors` | 对外命令 API |
| `SendTask` (内部类) | 待发送任务（帧字节 + 地址 + 功能码 + latch） |

---

## 五、并发安全分析

> **所有权：** SendWorker、Polling Scheduler、双队列、pendingPollSlots 都在 `DeviceSerialManager` 中。
> `SerialConnectionManager` 只有 WriteWorker（单线程从 LinkedBlockingQueue 取 byte[] 写入硬件）。

```
┌──────────────────────┐
│ 调用方线程 (UI/业务)  │  sendAndWait() → manualQueue.put() + latch.await()
│ 可能多个              │  线程安全: BlockingQueue + CountDownLatch(每任务独立)
└──────────┬───────────┘                          ← DeviceSerialManager
           │
           ▼
┌──────────────────────┐
│ Polling Scheduler    │  enqueuePollBatch() → pollQueue.offer()
│ 单线程               │  + markPending(addr) / isPending(addr)
│                      │  线程安全: BlockingQueue + synchronized
└──────────┬───────────┘                          ← DeviceSerialManager
           │
           ▼
┌──────────────────────┐
│ SendWorker Thread    │  1. manualQueue.poll() (非阻塞，优先)
│ 单线程, 无锁         │  2. pollQueue.poll(1s) (阻塞超时)
│ 唯一写串口的线程      │  3. clearPending(addr) 清除去重标记
│ (从 DeviceSerial     │  4. serialManager.write(byte[]) → SerialConnectionManager 内部队列
│  视角)               │  5. latch.await() 等待响应
│                      │  6. currentTask 通过 AtomicReference 读写
└──────────┬───────────┘                          ← DeviceSerialManager
           │ serialManager.write(byte[])  ──▶  LinkedBlockingQueue
           ▼
┌──────────────────────┐
│ WriteWorker Thread   │  writeQueue.take() → serialManager.send(data)
│ 单线程, 无锁         │  线程安全: LinkedBlockingQueue (单生产者多消费者安全)
│                      │                          ← SerialConnectionManager (纯 I/O)
└──────────┬───────────┘
           │ serialManager.send()
           ▼
┌──────────────────────┐
│ SerialManager (JNI)  │  ReadThread 异步回调
│ ReadThread 线程      │  → dataCallback.onDataReceived(bytes)
│                      │  → DeviceSerialManager.messageRouter.onRawBytes()
└──────────────────────┘                          ← serialport 模块
```

### 关键并发保证

1. **串口写互斥**：DeviceSerialManager.SendWorker 是唯一通过 `serialManager.write()` 发起写入的线程；SerialConnectionManager.WriteWorker 是唯一调用 JNI `send()` 的线程。
2. **currentTask 可见性**：`AtomicReference` 保证 DeviceSerialManager.SendWorker（写）和 ReadThread → MessageRouter（读）之间的 happens-before。
3. **latch 安全性**：每个手动命令有自己的 `new CountDownLatch(1)`，无共享状态
4. **队列边界**：`manualQueue(32)` + `pollQueue(256)` 有界，防止 OOM；手动命令 `put` 阻塞保证不丢失
5. **去重安全**：`pendingPollSlots[]` 通过 `synchronized(this)` 保护读写
   - Polling Scheduler：写 `true`（markPending），读（isPending）
   - SendWorker：写 `false`（clearPending）
   - 虽然每个槽位只有一条线程写、另一条线程清，但 synchronized 简化了可见性保证
6. **无死锁**：队列等待和 latch 等待在不同线程上，无循环依赖；`synchronized(isPending/markPending/clearPending)` 粒度极小且不持有锁等待其他资源

---

## 六、与旧方案对比

| 维度 | 旧方案 (pollNext) | 新方案 (双队列 + BatchPoll) |
|------|-------------------|---------------------------|
| 轮询方式 | 逐个地址顺序轮询，单槽位推进 | 批量范围轮询，遍历 [start, end] 一次性加入全部 |
| 去重 | 无（单槽位推进，不会重复） | `pendingPollSlots[]` 确保每个槽位最多一个待处理任务 |
| 轮询 & 手动命令隔离 | `synchronized` 方法互斥，无队列 | 双队列分离，manualQueue 优先于 pollQueue |
| 手动命令优先级 | 与轮询竞争同一锁 | **立即插队**：SendWorker 每个循环先检查 manualQueue |
| 响应匹配 | 单 `pendingAddress` | 完整 `SendTask` 上下文（地址 + 功能码 + latch） |
| 超时检测 | 依赖 350ms 定时器碰巧触发 | `CountDownLatch.await(timeout)` 精确超时 |
| 手动命令响应 | 发后不等，无超时 | 等待响应或超时，返回明确结果 |
| 线程模型 | 定时器线程做 I/O | 定时器只加队列（轻量），独立线程做 I/O |
| 串口写并发 | `synchronized` 方法锁 | 单线程自然互斥，无锁 |
| 批量开门 | 循环 `writeRaw` 阻塞 | `offer` 入 manualQueue，不阻塞调用方 |
| 空闲 CPU | 定时器每次唤醒 | 队列 `poll(1s)` 阻塞，零唤醒 |
| 100 槽位一轮耗时 | 100 × 350ms = 35s（最小） | 取决于串口速率（5ms/帧=0.5s），350ms 去重保证无重复 |

---

## 七、测试建议

### 单元测试（可脱离硬件）

1. **双队列优先级**：`manualQueue` 有任务时 SendWorker 不消费 `pollQueue`
2. **去重逻辑**：`markPending(5)` 后 `isPending(5)` 返回 true → `clearPending(5)` 后返回 false
3. **SendTask latch**：正常释放、超时释放、中断释放
4. **currentTask 原子性**：并发 set/get 不丢任务

### 集成测试（需连接硬件）

5. **批量轮询正常流程**：开启轮询 → 100 个槽位入队 → 连续收到响应 → `notifySlot` 被调用
6. **去重验证**：两轮 enqueuePollBatch 之间，已 pending 的槽位被跳过
7. **手动命令优先**：正在处理轮询时调用 `openDoor` → 手动命令插入到当前轮询完成后立即执行
8. **手动命令连续**：两个手动命令先后到达 → 按到达顺序优先于轮询执行
9. **批量开门**：`openAllDoors(100)` → 100 个任务入 `manualQueue` → 不阻塞调用方
10. **并发手动命令**：两个线程同时 `openDoor(1)` 和 `querySlot(2)` → 各自收到正确响应
11. **连接断开恢复**：串口关闭 → 手动命令 fail → pending 标记清除 → 重连后恢复正常

---

## 八、额外配置项

| 常量 | 建议值 | 说明 |
|------|--------|------|
| `POLLING_INTERVAL_MS` | 350ms | 轮询周期，决定去重刷新频率 |
| `RESPONSE_TIMEOUT_MS` | 2500ms | 手动命令超时，需大于硬件最慢响应时间 |
| `manualQueue` 容量 | 32 | 手动命令峰值，32 够 2 个并发调用方的临时堆积 |
| `pollQueue` 容量 | 256 | 需 > pollEndAddress - pollStartAddress，为 100 槽位留余量 |
| `pollStartAddress` | 1 | 轮询起始地址（可配置） |
| `pollEndAddress` | 100 | 轮询结束地址（可配置，需 ≤ totalSlots） |

---

## 九、注意

> **文件所有权提醒：** 根据 AGENTS.md，`SerialConnectionManager.java` 和 `WorkCardProtocol.java` 属于串口专业人员只读资产。本文档中的修改方案仅供设计和讨论，实际代码修改需在获得串口负责人明确授权后进行。

---

## 十、整体架构重构：四层分离

### 10.1 当前调用链的问题

```
当前：
  DeviceCoreService
    ├── 创建 SerialConnectionManager
    ├── 实现 SerialConnectionManager.Listener (onStatusChanged/onDataReceived/onSlotStatus)
    ├── 实现 SerialPort 接口 → 直接调用 serialManager.openDoor/querySlot/...
    └── onSlotStatus → DeviceDataLayer → SlotStateRepository → UI (一条一条推送)

问题：
  1. SerialConnectionManager 同时承担 串口通信 + 消息解析 + 状态映射 + 业务通知，职责过重
  2. 外部直接调用 SerialConnectionManager 的公开方法，没有隔离层
  3. notifySlot 逐条推送，100 个槽位每秒产生 100 次 UI 刷新
  4. 不存在缓存层——上层必须自己维护 SlotStateRepository，数据来源与串口紧耦合
  5. 如果未来增加 MQTT/缓存 等数据源，没有统一的状态管理入口
```

### 10.2 新架构总览

```
┌──────────────────────────────────────────────────────────────────┐
│ DeviceCoreService                                                │
│   serialPort (匿名实现) → 委托 DeviceSerialManager                │
│   slotStateHandler → DeviceSerialManager.subscribeAllSlots()     │
└──────────────────────┬───────────────────────────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────────────────────────┐
│ DeviceSerialManager (新 — 设备管理门面)                           │
│                                                                  │
│  公开 API（实现 SerialPort 语义）:                                │
│    openDoor(slot, admin) → 构造帧 → put manualQueue → 等 latch   │
│    querySlot(slot) → 构造帧 → put manualQueue → 等 latch         │
│    readVersion(slot) → 构造帧 → put manualQueue → 等 latch       │
│    openAllDoors(admin) → offer manualQueue (发后不管)            │
│    snapshot() → serialManager.snapshot() + slotCache.getSnapshot()│
│    listPorts() / configure() / reconnect() / setPollingEnabled()  │
│                                                                  │
│  订阅 API:                                                       │
│    subscribeAllSlots(listener, intervalMs) → SlotStateManager     │
│    subscribeSlots(listener, slotIds, intervalMs)                  │
│    unsubscribe(listener)                                         │
│                                                                  │
│  内部调度逻辑（SerialConnectionManager 完全没有这些）:              │
│    ├── manualQueue (32) + pollQueue (256) — 双队列优先级          │
│    ├── SendWorker Thread — 取队列 → write(byte[]) → latch.await   │
│    ├── Polling Scheduler (350ms) — enqueuePollBatch + 去重       │
│    ├── pendingPollSlots[] — 槽位级去重标记                        │
│    ├── currentTask (AtomicReference) — 响应匹配上下文              │
│    └── SendTask — 帧字节 + 地址 + 功能码 + latch + 超时          │
│                                                                  │
│  内部持有:                                                       │
│    ├── SerialConnectionManager (private, 纯字节 I/O)              │
│    ├── SerialMessageRouter                                       │
│    └── SlotStateManager                                          │
│                                                                  │
│  回调: serialManager.setOnDataReceived → messageRouter.onRawBytes │
│       → route(Frame) → SlotStateManager / onResponseMatched      │
└──────┬──────────────┬────────────────┬───────────────────────────┘
       │              │                │
       ▼              ▼                ▼
┌──────────────┐ ┌───────────────┐ ┌──────────────────┐
│ Serial       │ │ SerialMessage │ │ SlotStateManager │
│ Connection   │ │ Router (新)   │ │ (新 — 实时状态)   │
│ Manager      │ │               │ │                  │
│ (纯 I/O)     │ │ decode bytes  │ │ cache:           │
│              │ │ → Frame列表   │ │  Map<addr,State> │
│ 仅:          │ │ → 按func路由:  │ │                  │
│  open/close  │ │   QUERY →     │ │ onQueryResponse()│
│  write(bytes)│ │   SlotState   │ │ subscribeAll()   │
│  onDataRx    │ │   Manager     │ │ subscribeSlots() │
│  回调        │ │   DOOR/VER    │ │ 每 1s 检查推送    │
│  snapshot    │ │   → matchTask │ │ 兜底 60s 全量    │
└──────────────┘ └───────────────┘ └──────────────────┘
```

**依赖方向（严格向下）：**

```
DeviceCoreService → DeviceSerialManager → SerialConnectionManager (内部, 纯 I/O)
                                        → SerialMessageRouter
                                        → SlotStateManager

外部不可直接访问 SerialConnectionManager、SerialMessageRouter、SlotStateManager
```

### 10.3 SerialMessageRouter — 消息解析与路由

**类位置：** `core/SerialMessageRouter.java`

**职责：** 接收串口原始字节，解析为 `WorkCardProtocol.Frame`，根据功能码分发到不同的处理器。

```java
public class SerialMessageRouter {
    /**
     * 消息分发回调 — SerialConnectionManager 或其他调用方注册
     */
    public interface MessageHandler {
        /** 收到卡槽查询响应 */
        void onSlotQueryResponse(@NonNull WorkCardProtocol.Frame frame);
        /** 收到开门响应 — 需要释放 latch */
        void onDoorResponse(@NonNull WorkCardProtocol.Frame frame);
        /** 收到版本查询响应 — 需要释放 latch */
        void onVersionResponse(@NonNull WorkCardProtocol.Frame frame);
        /** 收到无法识别的功能码 */
        void onUnknownFrame(@NonNull WorkCardProtocol.Frame frame);
    }

    private final LinkedList<Byte> inboundBuffer = new LinkedList<>();
    private MessageHandler handler;
    private DataListener dataListener; // 调试/原始数据透传

    public void setHandler(MessageHandler handler);
    public void setDataListener(DataListener listener);

    /**
     * 串口收到数据时调用此方法（替代原来 SerialConnectionManager.handleReceived）
     *
     * @param rawBytes 原始字节
     */
    public void onRawBytes(@NonNull byte[] rawBytes) {
        // 1. 计算统计（需要在锁内完成，和 SerialConnectionManager 协调）
        // 2. 加入 inboundBuffer
        for (byte b : rawBytes) inboundBuffer.add(b);
        // 3. 解码
        List<WorkCardProtocol.Frame> frames = WorkCardProtocol.decode(inboundBuffer);
        // 4. 原始数据透传（调试用）
        if (dataListener != null) dataListener.onData(rawBytes);
        // 5. 逐帧路由
        for (WorkCardProtocol.Frame frame : frames) {
            route(frame);
        }
    }

    private void route(@NonNull WorkCardProtocol.Frame frame) {
        if (handler == null) return;

        switch (frame.function) {
            case WorkCardProtocol.FUNCTION_QUERY:
                handler.onSlotQueryResponse(frame);
                break;
            case WorkCardProtocol.FUNCTION_OPEN_DOOR:
                handler.onDoorResponse(frame);
                break;
            case WorkCardProtocol.FUNCTION_VERSION:
                handler.onVersionResponse(frame);
                break;
            default:
                handler.onUnknownFrame(frame);
                break;
        }
    }
}
```

**关键：** `SerialConnectionManager.handleReceived()` 改为：

```java
// 之前
private void handleReceived(byte[] bytes) {
    // ... 加入 inboundBuffer, decode, for each handleFrame, notifySlot, notifyData ...
}

// 之后
private void handleReceived(byte[] bytes) {
    synchronized (this) {
        receivedBytes += bytes.length;
        lastReceivedAt = System.currentTimeMillis();
    }
    messageRouter.onRawBytes(bytes);
    notifyStatus(); // 触发 onStatusChanged
}
```

### 10.4 SlotStateManager — 卡槽实时状态管理 + 订阅机制

**类位置：** `core/SlotStateManager.java`

**职责：**
1. 缓存每个卡槽的最新状态（来自串口查询响应）
2. 提供订阅机制——支持全量 / 指定槽位 / 自定义间隔
3. 每 60s 强制全量推送一次当前缓存

```java
public class SlotStateManager {
    // === 订阅接口 ===

    /**
     * 卡槽状态订阅者
     */
    public interface SlotSubscriber {
        /**
         * 状态更新回调
         * @param slots 本次推送的卡槽状态集合（可能是全量或指定槽位）
         * @param isFullSnapshot 是否全量快照（true=全量，false=增量）
         * @param timestamp 状态时间戳
         */
        void onSlotStatesUpdate(@NonNull List<SlotState> slots, boolean isFullSnapshot, long timestamp);
    }

    // === 内部数据结构 ===

    /** 卡槽状态 */
    public static class SlotState {
        public final int address;       // 槽位号
        public final int rawStatus;     // 原始状态
        public final boolean loaded;
        public final String workMode;   // on/offline/error
        public final String doorStatus; // closed/open/jammed
        public final boolean isPresent; // 有卡/无卡
        public final int work;          // 工作模式码
        public final long lastUpdated;  // 最后更新时间戳

        // toJson(), 用于跨线程传递
    }

    private final Map<Integer, SlotState> cache = new ConcurrentHashMap<>();

    // === 订阅管理 ===

    /**
     * 订阅全量卡槽状态，每 intervalMs 推送一次
     */
    public void subscribeAll(@NonNull SlotSubscriber subscriber, long intervalMs);

    /**
     * 订阅指定卡槽状态，每 intervalMs 推送一次
     */
    public void subscribeSlots(@NonNull SlotSubscriber subscriber,
                               @NonNull Set<Integer> slotIds, long intervalMs);

    /**
     * 取消订阅
     */
    public void unsubscribe(@NonNull SlotSubscriber subscriber);

    // === 状态更新入口 ===

    /**
     * SerialMessageRouter 分发来查询响应时调用
     */
    synchronized void onSlotQueryResponse(@NonNull WorkCardProtocol.Frame frame) {
        SlotState state = parse(frame);
        cache.put(frame.slaveAddress, state);
        // 不立即推送——等待定时器统一发送
    }

    // === 推送调度 ===

    private ScheduledExecutorService pushScheduler;
    private ScheduledFuture<?> pushTask;

    /**
     * 启动推送调度器。每 1s 检查一次所有订阅，到期则推送。
     * 也确保兜底的全量快照推送（默认 60s）。
     */
    public void start();

    public void stop();

    /**
     * 立即获取当前全量缓存快照（不触发订阅回调）
     */
    public List<SlotState> getSnapshot();

    /**
     * 立即强制推送一次全量快照给所有订阅者
     */
    public void forcePushAll();
}
```

**推送调度逻辑：**

```java
// 内部定时检查（每 1s）
private void checkAndPush() {
    long now = System.currentTimeMillis();
    List<SlotState> snapshot = null;

    for (Subscription sub : subscriptions) {
        if (now - sub.lastPushTime >= sub.intervalMs) {
            if (sub.slotIds == null) {
                // 全量订阅
                if (snapshot == null) snapshot = new ArrayList<>(cache.values());
                sub.subscriber.onSlotStatesUpdate(snapshot, true, now);
            } else {
                // 指定槽位订阅
                List<SlotState> filtered = new ArrayList<>();
                for (int id : sub.slotIds) {
                    SlotState s = cache.get(id);
                    if (s != null) filtered.add(s);
                }
                sub.subscriber.onSlotStatesUpdate(filtered, false, now);
            }
            sub.lastPushTime = now;
        }
    }

    // 兜底：每 60s 强制全量推送（即使无订阅者变化）
    if (now - lastFullPushTime >= 60_000) {
        if (snapshot == null) snapshot = new ArrayList<>(cache.values());
        forceFullPush(snapshot, now);
        lastFullPushTime = now;
    }
}
```

**典型订阅场景：**

| 场景 | 订阅方式 | intervalMs | 说明 |
|------|---------|-----------|------|
| UI 实时刷新 | `subscribeAll(uiHandler, 3000)` | 3s | UI 每 3s 收到全量 100 个槽位状态一次 |
| 故障监控 | `subscribeSlots(monitor, errSlots, 1000)` | 1s | 只监控故障槽位，高频推送 |
| 按需查询 | `getSnapshot()` | — | 不订阅，只在需要时主动拉取 |
| 持久化 | `subscribeAll(recorder, 60_000)` | 60s | 每分钟写一次日志 |

**SlotState 构造（parse 方法，从 SerialConnectionManager 移入）：**

```java
SlotState parse(WorkCardProtocol.Frame frame) {
    byte status = frame.data != null && frame.data.length > 0 ? frame.data[0] : 0;
    int rawStatus = status & 0xFF;
    String workMode = mapWork(rawStatus);
    String door = mapDoor(rawStatus);
    String presence = mapPresence(rawStatus);
    boolean loaded = presence.equals("有卡");
    boolean isPresent = presence.equals("有卡");
    int work = mapWorkCode(rawStatus);

    return new SlotState(frame.slaveAddress, rawStatus, loaded, workMode, door, isPresent, work, now);
}
```

### 10.5 DeviceSerialManager — 设备管理门面（完整实现）

**类位置：** `core/DeviceSerialManager.java`

**职责：** 对外统一的设备操作入口，持有双队列、SendWorker、Polling Scheduler 等全部调度逻辑。`SerialConnectionManager` 仅作为内部纯 I/O 渠道，不对外暴露。

```java
public class DeviceSerialManager {
    // ======================== 内部组件 ========================
    private final SerialConnectionManager serialManager;   // 纯字节 I/O（private）
    private final SerialMessageRouter messageRouter;       // 帧解析路由
    private final SlotStateManager slotStateManager;       // 状态缓存 + 订阅

    // ======================== 双队列 ========================
    /** 手动命令队列 — 最高优先级，SendWorker 每次循环优先检查 */
    private final BlockingQueue<SendTask> manualQueue = new ArrayBlockingQueue<>(32);
    /** 轮询任务队列 — 低优先级，manualQueue 为空时才消费 */
    private final BlockingQueue<SendTask> pollQueue = new ArrayBlockingQueue<>(256);

    // ======================== SendWorker ========================
    private volatile boolean workerRunning;
    private Thread sendWorker;
    /** 当前正在处理的任务 — 用于响应匹配 */
    private final AtomicReference<SendTask> currentTask = new AtomicReference<>();

    // ======================== Polling Scheduler ========================
    private ScheduledExecutorService pollingScheduler;
    private ScheduledFuture<?> pollingSchedule;
    private int pollStartAddress = 1;
    private int pollEndAddress = 100;
    /** 去重：true = 该槽位已有轮询任务在 pollQueue 中 */
    private final boolean[] pendingPollSlots;

    // ======================== 外部监听器 ========================
    private final List<SerialStatusListener> statusListeners = new CopyOnWriteArrayList<>();

    // ======================== 配置 ========================
    static final int POLLING_INTERVAL_MS = 350;
    static final int RESPONSE_TIMEOUT_MS = 2500;
    private long sentBytes = 0;

    public DeviceSerialManager(@NonNull Context context, int totalSlots) {
        this.pendingPollSlots = new boolean[totalSlots + 1];
        this.slotStateManager = new SlotStateManager();
        this.messageRouter = new SerialMessageRouter();
        this.serialManager = new SerialConnectionManager(context);

        // 连接串口收字节回调 → 消息路由器
        serialManager.setOnDataReceived(bytes -> messageRouter.onRawBytes(bytes));

        // 连接消息路由器 → 业务处理器
        messageRouter.setHandler(new SerialMessageRouter.MessageHandler() {
            @Override
            public void onSlotQueryResponse(WorkCardProtocol.Frame frame) {
                slotStateManager.onSlotQueryResponse(frame);     // 更新状态缓存
                onResponseMatched(frame);                         // 匹配等待任务
            }
            @Override
            public void onDoorResponse(WorkCardProtocol.Frame frame) {
                onResponseMatched(frame);
            }
            @Override
            public void onVersionResponse(WorkCardProtocol.Frame frame) {
                onResponseMatched(frame);
            }
            @Override
            public void onUnknownFrame(WorkCardProtocol.Frame frame) {
                Log.w(TAG, "未知帧: addr=" + frame.slaveAddress + " func=" + frame.function);
            }
        });
    }

    // ======================== 生命周期 ========================

    public void start() {
        serialManager.open(port, baudRate);
        slotStateManager.start();
        startSendWorker();
        startPolling();
    }

    public void stop() {
        stopPolling();
        stopSendWorker();
        slotStateManager.stop();
        serialManager.close();
    }

    // ======================== SendWorker ========================

    private void startSendWorker() {
        workerRunning = true;
        sendWorker = new Thread(this::runSendWorker, "SerialSendWorker");
        sendWorker.setDaemon(true);
        sendWorker.start();
    }

    private void stopSendWorker() {
        workerRunning = false;
        if (sendWorker != null) {
            sendWorker.interrupt();
            try { sendWorker.join(2000); } catch (InterruptedException ignored) {}
            sendWorker = null;
        }
        // 释放所有等待中的手动命令
        SendTask t = currentTask.getAndSet(null);
        if (t != null && t.latch != null) {
            t.errorMessage = "串口已关闭";
            t.latch.countDown();
        }
        manualQueue.clear();
        pollQueue.clear();
        synchronized (this) { Arrays.fill(pendingPollSlots, false); }
    }

    /** SendWorker 主循环 — 手动优先 */
    private void runSendWorker() {
        while (workerRunning) {
            SendTask task;
            task = manualQueue.poll();                     // 1. 优先检查手动命令
            if (task == null) {
                try {
                    task = pollQueue.poll(1, TimeUnit.SECONDS); // 2. 等轮询任务
                } catch (InterruptedException e) { break; }
            }
            if (task == null) continue;

            if (task.isPoll()) clearPending(task.slaveAddress); // 3. 清除去重标记

            if (!serialManager.isOpen()) {
                failTask(task, "串口未连接");
                continue;
            }

            currentTask.set(task);                                  // 4. 设置当前上下文
            try {
                serialManager.write(task.bytes);                    // 5. 纯字节写入
                sentBytes += task.bytes.length;
            } catch (Exception e) {
                currentTask.set(null);
                failTask(task, "发送失败: " + e.getMessage());
                continue;
            }

            if (task.awaitResponse) {                               // 6. 等响应
                try {
                    boolean ok = task.latch.await(RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    if (!ok) { task.timedOut = true; task.errorMessage = "超时"; }
                } catch (InterruptedException e) {
                    task.errorMessage = "被中断"; task.timedOut = true;
                }
            }
            currentTask.set(null);
        }
    }

    // ======================== Polling Scheduler ========================

    private void startPolling() {
        pollingScheduler = Executors.newSingleThreadScheduledExecutor();
        pollingSchedule = pollingScheduler.scheduleAtFixedRate(
                this::enqueuePollBatch, 0, POLLING_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void stopPolling() {
        if (pollingSchedule != null) { pollingSchedule.cancel(false); pollingSchedule = null; }
        if (pollingScheduler != null) { pollingScheduler.shutdown(); pollingScheduler = null; }
        synchronized (this) { Arrays.fill(pendingPollSlots, false); }
    }

    /** 批量范围轮询 + 去重 */
    private void enqueuePollBatch() {
        if (!serialManager.isOpen()) return;
        for (int addr = pollStartAddress; addr <= pollEndAddress; addr++) {
            if (isPending(addr)) continue;                  // 已有待处理任务
            byte[] frame = WorkCardProtocol.query(addr);
            SendTask task = new SendTask(frame, "poll", addr,
                    WorkCardProtocol.FUNCTION_QUERY, false, Q_POLL);
            if (pollQueue.offer(task)) markPending(addr);   // 入队成功 → 标记
        }
    }

    // ======================== 手动命令（入 manualQueue） ========================

    public JSONObject openDoor(int slot, boolean admin) throws Exception {
        if (slot < 1 || slot > totalSlots) throw new IllegalArgumentException("卡位号非法");
        byte[] frame = WorkCardProtocol.openDoor(slot, admin);
        SendTask task = new SendTask(frame, "door", slot,
                WorkCardProtocol.FUNCTION_OPEN_DOOR, true, Q_MANUAL);
        return sendAndWait(task);
    }

    public JSONObject querySlot(int slot) throws Exception {
        if (slot < 1 || slot > totalSlots) throw new IllegalArgumentException("卡位号非法");
        byte[] frame = WorkCardProtocol.query(slot);
        SendTask task = new SendTask(frame, "query", slot,
                WorkCardProtocol.FUNCTION_QUERY, true, Q_MANUAL);
        return sendAndWait(task);
    }

    public JSONObject readVersion(int slot) throws Exception {
        if (slot < 1 || slot > totalSlots) throw new IllegalArgumentException("卡位号非法");
        byte[] frame = WorkCardProtocol.readVersion(slot);
        SendTask task = new SendTask(frame, "version", slot,
                WorkCardProtocol.FUNCTION_VERSION, true, Q_MANUAL);
        return sendAndWait(task);
    }

    public JSONObject openAllDoors(boolean admin) {
        int sent = 0;
        for (int addr = pollStartAddress; addr <= pollEndAddress; addr++) {
            byte[] frame = WorkCardProtocol.openDoor(addr, admin);
            SendTask task = new SendTask(frame, "door.all", addr,
                    WorkCardProtocol.FUNCTION_OPEN_DOOR, false, Q_MANUAL);
            if (manualQueue.offer(task)) sent++;
        }
        return new JSONObject().put("success", true).put("sent", sent);
    }

    private JSONObject sendAndWait(SendTask task) throws Exception {
        manualQueue.put(task);
        task.latch.await(RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        if (task.timedOut || task.errorMessage != null)
            throw new IOException(task.errorMessage);
        return task.responseData != null ? task.responseData : new JSONObject();
    }

    // ======================== 响应匹配 ========================

    /** 消息路由器收到帧时调用，尝试匹配 currentTask */
    private void onResponseMatched(WorkCardProtocol.Frame frame) {
        SendTask task = currentTask.get();
        if (task != null && task.slaveAddress == frame.slaveAddress
                && task.function == frame.function) {
            task.responded = true;
            task.responseData = parseFrameResult(frame);
            currentTask.set(null);
            if (task.latch != null) task.latch.countDown();
        }
    }

    private JSONObject parseFrameResult(WorkCardProtocol.Frame frame) {
        // 委托给 SlotStateManager 中的 parse 逻辑或直接实现
        // 开门/版本/查询的结果解析
    }

    // ======================== 订阅 API (委托给 SlotStateManager) ========================

    public void subscribeAllSlots(SlotStateManager.SlotSubscriber sub, long intervalMs) {
        slotStateManager.subscribeAll(sub, intervalMs);
    }
    public void subscribeSlots(SlotStateManager.SlotSubscriber sub,
                                Set<Integer> ids, long intervalMs) {
        slotStateManager.subscribeSlots(sub, ids, intervalMs);
    }
    public void unsubscribe(SlotStateManager.SlotSubscriber sub) {
        slotStateManager.unsubscribe(sub);
    }

    // ======================== 配置与快照 ========================

    public void configure(String device, int baudRate) {
        serialManager.configure(device, baudRate);
    }
    public void reconnect() { serialManager.reconnect(); }
    public void setPollingEnabled(boolean on) { /* 控制 Polling Scheduler */ }
    public List<String> listPorts() { return SerialConnectionManager.listAvailablePorts(); }
    public JSONObject snapshot() {
        JSONObject s = serialManager.snapshot();  // 纯 I/O 快照
        List<SlotStateManager.SlotState> slots = slotStateManager.getSnapshot();
        // 组装完整快照...
        return s;
    }
    public void setPollRange(int start, int end) {
        this.pollStartAddress = start;
        this.pollEndAddress = end;
    }

    // ======================== 去重辅助 ========================

    private synchronized boolean isPending(int addr) {
        return addr > 0 && addr < pendingPollSlots.length && pendingPollSlots[addr];
    }
    private synchronized void markPending(int addr) {
        if (addr > 0 && addr < pendingPollSlots.length) pendingPollSlots[addr] = true;
    }
    private synchronized void clearPending(int addr) {
        if (addr > 0 && addr < pendingPollSlots.length) pendingPollSlots[addr] = false;
    }

    private void failTask(SendTask task, String error) {
        task.errorMessage = error; task.timedOut = true;
        if (task.latch != null) task.latch.countDown();
    }
}
```

### 10.6 DeviceCoreService 变更

**变更极小**——只需把 `SerialConnectionManager` 替换为 `DeviceSerialManager`：

```java
// 之前
private SerialConnectionManager serialManager;
private void initSerial() {
    serialManager = new SerialConnectionManager(this, new SerialConnectionManager.Listener() {
        @Override public void onStatusChanged(JSONObject status) { ... }
        @Override public void onDataReceived(JSONObject data) { ... }
        @Override public void onSlotStatus(JSONObject slot) {
            // 逐条推送，100 槽位 = 100 次调用/s
            dataLayer.onSlotStatus(slot);
        }
    });
    serialManager.start();
    serialPort = new SerialPort() {
        @Override public JSONObject openDoor(int slot, boolean admin) {
            return serialManager.openDoor(slot, admin);
        }
        // ... querySlot, readVersion, snapshot, listPorts ...
    };
}

// 之后
private DeviceSerialManager deviceSerial;
private void initSerial() {
    deviceSerial = new DeviceSerialManager(this);
    deviceSerial.start();

    // 订阅全量卡槽状态，每 3s 推送一次
    deviceSerial.subscribeAllSlots(new SlotStateManager.SlotSubscriber() {
        @Override
        public void onSlotStatesUpdate(List<SlotStateManager.SlotState> slots,
                                       boolean isFull, long ts) {
            // 一次收到 100 个槽位的全量状态
            dataLayer.onSlotsBatchUpdate(slots, isFull);
        }
    }, 3000);

    // 连接状态监听
    deviceSerial.addStatusListener(status -> { ... });

    // SerialPort 委托
    serialPort = new SerialPort() {
        @Override public JSONObject openDoor(int slot, boolean admin) throws Exception {
            return deviceSerial.openDoor(slot, admin);
        }
        @Override public JSONObject querySlot(int slot) throws Exception {
            return deviceSerial.querySlot(slot);
        }
        // ...
    };
}
```

**对比：**

| 维度 | 之前 | 之后 |
|------|------|------|
| 状态推送 | `onSlotStatus` 逐条触发，100 次/s | `subscribeAllSlots(3s)` 一次 100 条/3s |
| 外部依赖 | 直接引用 `SerialConnectionManager` | 只引用 `DeviceSerialManager` |
| 监听器 | 匿名类实现 3 个回调方法 | 订阅接口 + 状态监听，职责清晰 |
| 新增功能 | 无 | 可订阅指定槽位、自定义间隔、主动拉取快照 |

---

## 十一、新增文件的完整方法签名

### SerialMessageRouter

```
public class SerialMessageRouter:
  interface MessageHandler:
    onSlotQueryResponse(Frame)
    onDoorResponse(Frame)
    onVersionResponse(Frame)
    onUnknownFrame(Frame)
  interface DataListener:
    void onData(byte[] raw)

  setHandler(MessageHandler)
  setDataListener(DataListener)
  onRawBytes(byte[])
```

### SlotStateManager

```
public class SlotStateManager:
  class SlotState:
    int address, rawStatus, work
    boolean loaded, isPresent
    String workMode, doorStatus
    long lastUpdated
    JSONObject toJson()

  interface SlotSubscriber:
    onSlotStatesUpdate(List<SlotState> slots, boolean isFullSnapshot, long timestamp)

  subscribeAll(SlotSubscriber, long intervalMs)
  subscribeSlots(SlotSubscriber, Set<Integer> slotIds, long intervalMs)
  unsubscribe(SlotSubscriber)
  start()
  stop()
  getSnapshot() → List<SlotState>
  forcePushAll()
  onSlotQueryResponse(Frame)  // package-private, 由 DeviceSerialManager 内部调用
```

### DeviceSerialManager

```
public class DeviceSerialManager:
  // 命令
  openDoor(int slot, boolean admin) → JSONObject
  querySlot(int slot) → JSONObject
  readVersion(int slot) → JSONObject
  openAllDoors(boolean admin) → JSONObject
  snapshot() → JSONObject

  // 配置
  configure(String device, int baudRate)
  reconnect()
  setPollingEnabled(boolean)
  listPorts() → List<String>
  send(byte[] data)

  // 订阅
  subscribeAllSlots(SlotSubscriber, long intervalMs)
  subscribeSlots(SlotSubscriber, Set<Integer>, long intervalMs)
  unsubscribe(SlotSubscriber)

  // 连接状态
  addStatusListener(SerialStatusListener)
  removeStatusListener(SerialStatusListener)

  // 生命周期
  start()
  stop()
```

### SerialConnectionManager (纯化后)

```
public class SerialConnectionManager:
  // 回调
  interface DataCallback:
    void onDataReceived(byte[] data)

  // 连接
  open(String port, int baudRate)
  close()
  reconnect()
  isOpen() → boolean

  // 字节写入（线程安全，阻塞直到写入硬件）
  write(byte[]) throws IOException

  // 接收
  setOnDataReceived(DataCallback)

  // 配置 + 统计
  configure(String device, int baudRate)
  getSentBytes() → long
  getReceivedBytes() → long
  getLastReceivedAt() → long
  getPort() → String
  getBaudRate() → int
  snapshot() → JSONObject  // 纯连接层: {port, baudRate, state, sentBytes, receivedBytes, lastReceivedAt}
  listAvailablePorts() → List<String>  // static
```

---

## 十二、完整的消息流转示例（更新后）

```
[1] 串口收到数据
    SerialManager.ReadThread
      → SerialConnectionManager.handleReceived(bytes)
        → 统计 receivedBytes + lastReceivedAt
        → messageRouter.onRawBytes(bytes)

[2] 消息路由器解析
    SerialMessageRouter.onRawBytes:
      → inboundBuffer.add(bytes)
      → WorkCardProtocol.decode → List<Frame>
      → rawDataListener.onData(bytes)  // 调试透传

[3] 逐帧路由
    对每个 Frame:
      FUNCTION_QUERY → handler.onSlotQueryResponse(frame)
        ├── slotStateManager.onSlotQueryResponse(frame)
        │     → parse(frame) → SlotState
        │     → cache.put(addr, state)
        │     → (等待 pushScheduler 到时推送)
        │
        └── serialManager.matchResponse(frame)
              → currentTask.compare → 填充 responseData → latch.countDown()

      FUNCTION_OPEN_DOOR → handler.onDoorResponse(frame)
        └── serialManager.matchResponse(frame)
              → currentTask.compare → latch.countDown()

      FUNCTION_VERSION → handler.onVersionResponse(frame)
        └── serialManager.matchResponse(frame)
              → currentTask.compare → latch.countDown()

[4] 定时推送（SlotStateManager 的 pushScheduler, 每 1s 检查）
    checkAndPush():
      全量订阅者 (3s): lastPush > 3s → onSlotStatesUpdate(100条, true, ts)
      指定订阅者 (1s): lastPush > 1s → onSlotStatesUpdate(5条, false, ts)
      兜底 (60s): lastFullPush > 60s → forceFullPush(100条, ts)

[5] DeviceCoreService 收到推送
    dataLayer.onSlotsBatchUpdate(slots, isFull)
      → SlotStateRepository 批量更新 （非逐条 onSlotStatus）
      → UI 一次性刷新，而非 100 次局部更新
```

### 与之前的关键差异

| 步骤 | 之前 | 之后 |
|------|------|------|
| 解析路由 | `handleReceived` → `handleFrame` 混杂在 SerialConnectionManager | `SerialMessageRouter.route` 独立 |
| 状态缓存 | 无——上层自己维护 `SlotStateRepository` | `SlotStateManager.cache` 串口侧缓存 |
| 状态推送 | `notifySlot(JSONObject)` 逐条，每次查询触发 | 订阅定时推送，批量 `onSlotStatesUpdate(List)` |
| 外部调用 | `serialManager.openDoor()` 直接调用 | `deviceSerial.openDoor()` 门面 |
| 状态来源 | 只能来自串口 | 串口为主，可扩展 MQTT/缓存（通过 `SlotStateManager.onExternalUpdate`） |

---

## 十三、SerialConnectionManager — 纯串口收发

最终 `SerialConnectionManager` 被精简为**一个字节能见度**——只知道 `byte[]`，不知道 `Frame`、`Address`、`Function`、`Slot`。

### 13.1 最小接口

```java
/**
 * 纯串口字节收发管理器。
 * 不感知 WorkCardProtocol、不管理轮询、不构造帧、不匹配响应。
 * 唯一职责：打开/关闭串口、线程安全地写入字节、异步通知收到的字节。
 */
public class SerialConnectionManager {

    // ======================== 回调 ========================
    /** 串口收到数据时回调（来自 ReadThread） */
    public interface DataCallback {
        void onDataReceived(@NonNull byte[] data);
    }

    // ======================== 连接管理 ========================
    public void open(@NonNull String port, int baudRate);
    public void close();
    public void reconnect();
    public boolean isOpen();

    // ======================== 字节写入 ========================
    /**
     * 线程安全的字节写入。内部通过单线程 WriteWorker 串行发送到硬件。
     * 阻塞直到字节成功写入串口驱动（非阻塞直到收到响应——那属于上层逻辑）。
     *
     * @throws IOException 串口未打开或写入失败
     */
    public void write(@NonNull byte[] data) throws IOException;

    // ======================== 接收回调 ========================
    public void setOnDataReceived(@Nullable DataCallback callback);

    // ======================== 配置 ========================
    public void configure(@NonNull String device, int baudRate);

    // ======================== 统计 ========================
    public long getSentBytes();
    public long getReceivedBytes();
    public long getLastReceivedAt();
    public String getPort();
    public int getBaudRate();

    /**
     * 纯连接层快照，不包含任何业务状态。
     * 返回：{port, baudRate, state, connected, sentBytes, receivedBytes, lastReceivedAt}
     */
    @NonNull public JSONObject snapshot();

    // ======================== 工具 ========================
    @NonNull public static List<String> listAvailablePorts();
}
```

### 13.2 内部实现要点

```java
public class SerialConnectionManager {
    private SerialManager serialManager;       // JNI 串口驱动（只读）
    private DataCallback dataCallback;          // 接收回调
    private String port;
    private int baudRate;
    private String state = "CLOSED";
    private long sentBytes;
    private long receivedBytes;
    private long lastReceivedAt;

    // 内部写队列 — 保证 write(byte[]) 跨线程安全 + 串行写入硬件
    private final BlockingQueue<byte[]> writeQueue = new LinkedBlockingQueue<>();
    private Thread writeWorker;

    private void startWriteWorker() {
        writeWorker = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    byte[] data = writeQueue.take();
                    if (serialManager != null && serialManager.isOpen()) {
                        serialManager.send(data);              // 调用 JNI 驱动
                        sentBytes += data.length;
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "write error", e);
                }
            }
        }, "SerialWriteWorker");
        writeWorker.setDaemon(true);
        writeWorker.start();
    }

    // write() 是阻塞的 — 入队后等待 WriteWorker 发送完成
    public void write(@NonNull byte[] data) throws IOException {
        if (!isOpen()) throw new IOException("串口未连接");
        try {
            writeQueue.put(data);  // 阻塞直到 WriteWorker 取走（保证串行）
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("写入被中断");
        }
    }

    // 接收回调 — SerialManager.ReadThread 来数据时触发
    private void onSerialDataReceived(byte[] data) {
        receivedBytes += data.length;
        lastReceivedAt = System.currentTimeMillis();
        if (dataCallback != null) {
            dataCallback.onDataReceived(data);
        }
    }
}
```

### 13.3 对比：精简前 vs 精简后

| 维度 | 精简前 | 精简后 |
|------|--------|--------|
| 类行数 | ~400 行 | ~180 行（减少 55%） |
| 公开方法 | 15+（含 openDoor/querySlot 等） | 10（纯 I/O 方法） |
| 协议感知 | WorkCardProtocol、帧解析、地址、功能码 | 无——只看到 `byte[]` |
| 队列/线程 | ~~(无，直接同步写)~~ → 在设计中是双队列 | 单个 LinkedBlockingQueue + 单 WriteWorker |
| 回调接口 | Listener(3 个方法: status/data/slot) | DataCallback(1 个方法: onDataReceived) |
| 状态通知 | notifySlot/notifyData/notifyStatus | 无——上层通过回调自行处理 |
| 依赖 | WorkCardProtocol, JSONObject | 仅 SerialManager (JNI) |
| 可测试性 | 需 Mock 串口 + 协议帧 | 只需 Mock byte[] 收发 |

### 13.4 纯净度校验

```
SerialConnectionManager 对以下概念不知情：
  ❌ WorkCardProtocol      — 不构造/解析任何帧
  ❌ slaveAddress          — 不知道单板地址
  ❌ function code         — 不知道功能码 0x01/0x02/0x03
  ❌ slot / 槽位           — 不知道卡槽概念
  ❌ polling / 轮询        — 不知道轮询存在
  ❌ openDoor / querySlot   — 不知道业务命令
  ❌ SendTask              — 不知道任务上下文
  ❌ CountDownLatch        — 不知道同步等待机制
  ❌ pendingPollSlots      — 不知道去重
  ❌ SlotStateManager      — 不知道状态管理

SerialConnectionManager 仅知道：
  ✅ byte[]                — 发送和接收的都是原始字节
  ✅ SerialManager (JNI)   — 调用硬件驱动
  ✅ port / baudRate       — 连接参数
  ✅ open / close / isOpen — 连接状态
  ✅ sentBytes / receivedBytes — 统计
```
