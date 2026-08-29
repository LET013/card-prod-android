# SerialPort - Android 串口通信库

基于 JNI（C 语言）实现的 Android 串口通信库，提供阻塞 I/O 收发能力，可编译为 AAR 供其他项目直接使用。

## 技术栈

| 项目 | 版本 / 说明 |
|------|-------------|
| minSdk / targetSdk | 21 / 33 |
| compileSdk | 34 |
| NDK | 22.1.7171670 |
| CMake | 3.22.1 |
| 支持 ABI | `arm64-v8a`, `armeabi-v7a` |
| JNI 库名 | `libSerialPort.so` |
| Java 包名 | `com.xingyao.serialport` |

## 模块结构

```
serialport/src/main/
├── AndroidManifest.xml
├── cpp/
│   ├── CMakeLists.txt
│   ├── SerialPort.h          # JNI 头文件
│   └── SerialPort.c          # native 串口操作 (open/close/tcflush)
└── java/com/xingyao/serialport/
    ├── SerialPort.java        # JNI 壳层 (System.loadLibrary)
    └── SerialManager.java     # 阻塞 I/O 收发管理器
```

## 引入方式

### 方式一：作为 Gradle 模块引入

1. 将整个 `serialport/` 目录复制到目标项目根目录。

2. 在目标项目的 `settings.gradle` 中添加：

```groovy
include ':serialport'
```

3. 在 app 模块的 `build.gradle` 中添加依赖：

```groovy
dependencies {
    implementation project(':serialport')
}
```

### 方式二：引入编译好的 AAR

1. 编译 AAR（见下方"编译 AAR"章节），得到 `serialport-release.aar`。

2. 将 `.aar` 文件放入目标项目的 `app/libs/` 目录。

3. 在 app 模块的 `build.gradle` 中添加：

```groovy
dependencies {
    implementation fileTree(dir: 'libs', include: ['*.aar'])
}
```

## 主要接口

### SerialPort — JNI 壳层

```java
package com.xingyao.serialport;

public final class SerialPort {
    // 检查 native 库是否加载成功
    public static boolean isAvailable();

    // 获取库加载失败原因（未加载成功时）
    public static String getLoadError();

    // 打开串口设备，返回文件描述符
    // path:     设备路径，如 /dev/ttyS5
    // baudRate: 波特率，如 57600, 115200
    // flags:    标志位，一般传 0
    protected native FileDescriptor open(String path, int baudRate, int flags);

    // 关闭串口
    protected native void close();

    // 清空串口缓冲区
    public native void tcflush();
}
```

- `System.loadLibrary("SerialPort")` 在 static 块中自动执行。
- 通过 `SerialPort.isAvailable()` 可在调用前确认 native 库是否就绪。

### SerialManager — 阻塞 I/O 收发管理器

```java
package com.xingyao.serialport;

public class SerialManager {
    // 数据接收回调
    public interface OnDataReceivedListener {
        void onDataReceived(byte[] data);
    }

    // 设置数据接收监听器（必设，否则收不到数据回调）
    public void setOnDataReceivedListener(OnDataReceivedListener listener);

    // 打开串口
    // devicePath: 设备路径，如 "/dev/ttyS5"
    // baudRate:   波特率，如 57600
    // 返回 true 表示打开成功
    public boolean open(String devicePath, int baudRate);

    // 关闭串口并释放资源（包括停止读线程）
    public void close();

    // 串口是否已打开
    public boolean isOpen();

    // 发送字节数据（线程安全，可在任意线程调用）
    public void send(byte[] data);
}
```

**读取策略**：`SerialManager` 内部启动独立 `ReadThread`，使用 `FileInputStream.read()` **阻塞等待**数据，数据到达内核缓冲区后立即返回，无轮询开销。

## 使用方法

### 基本示例

```java
// 1. 检查 native 库
if (!SerialPort.isAvailable()) {
    Log.e(TAG, "串口库加载失败: " + SerialPort.getLoadError());
    return;
}

// 2. 创建管理器并设置监听
SerialManager serialManager = new SerialManager();
serialManager.setOnDataReceivedListener(new SerialManager.OnDataReceivedListener() {
    @Override
    public void onDataReceived(byte[] data) {
        // 处理收到的数据
        Log.d(TAG, "收到 " + data.length + " 字节: " + bytesToHex(data));
    }
});

// 3. 打开串口
boolean ok = serialManager.open("/dev/ttyS5", 57600);
if (!ok) {
    Log.e(TAG, "串口打开失败，请检查设备路径和权限");
    return;
}

// 4. 发送数据
byte[] cmd = new byte[]{0x01, 0x02, 0x03};
serialManager.send(cmd);

// 5. 关闭
serialManager.close();
```

### 完整生命周期示例

```java
public class SerialHelper {
    private SerialManager serialManager;

    public void start() {
        if (serialManager != null) return;

        serialManager = new SerialManager();
        serialManager.setOnDataReceivedListener(data -> {
            // 回调在 ReadThread 中执行，如需更新 UI 请 post 到主线程
            mainHandler.post(() -> onSerialData(data));
        });

        String devicePath = "/dev/ttyS5";  // 根据实际硬件调整
        int baudRate = 57600;

        if (!serialManager.open(devicePath, baudRate)) {
            Log.e(TAG, "串口打开失败");
            serialManager = null;
        }
    }

    public void send(byte[] data) {
        if (serialManager != null && serialManager.isOpen()) {
            serialManager.send(data);
        }
    }

    public void stop() {
        if (serialManager != null) {
            serialManager.close();
            serialManager = null;
        }
    }
}
```

## 编译 AAR

在项目根目录执行：

```bash
./gradlew :serialport:assembleRelease
```

输出路径：

```
serialport/build/outputs/aar/serialport-release-1.0.aar
```

AAR 内包含 `libSerialPort.so`（`arm64-v8a` 和 `armeabi-v7a`），第三方项目引入后无需安装 NDK 即可直接使用。

### 编译要求

- Android SDK Platform 34
- NDK 22.1.7171670（`ANDROID_HOME/ndk/22.1.7171670`）
- CMake 3.22.1（SDK Manager 中安装）

## 常见问题

| 问题 | 原因 / 解决 |
|------|-------------|
| `libSerialPort.so 加载失败` | 设备 ABI 不在 `arm64-v8a` / `armeabi-v7a` 范围内；或 so 未打包进 APK |
| `设备不存在` | 检查设备路径是否正确，确认 `/dev/ttyS*` 是否存在 |
| `串口权限不足` | Android 串口需要 root 权限或 SELinux 策略放行；开发阶段可 `adb root && adb remount && chmod 666 /dev/ttyS*` |
| 收不到数据 | 确认 `setOnDataReceivedListener` 已设置；检查硬件接线和波特率是否匹配 |
| 多个串口同时使用 | 每个串口创建一个独立 `SerialManager` 实例即可 |

## 协议实现

串口通信的应用层协议（如卡片读写协议 `WorkCardProtocol`）不在本库范围内。本库仅提供基础的串口打开、收发、关闭能力，上层协议解析请在业务代码中基于 `OnDataReceivedListener` 回调实现。
