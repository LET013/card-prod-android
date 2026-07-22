package com.xingyao.serialdebug.serial;

import android.util.Log;

import com.xingyao.serialdebug.protocol.WorkCardProtocol;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

/**
 * 串口通信管理器 —— JNI 原生 open 获取 FileDescriptor，
 * 用阻塞式 FileInputStream.read() 实现零延迟数据接收。
 *
 * 与 ntriptest 项目相同的 JNI + 阻塞读方案，数据一到立即触发回调。
 */
public class SerialManager {

    private static final String TAG = "SerialManager";

    public interface SerialEventListener {
        void onConnected(String port, int baudRate);
        void onDisconnected(String reason);
        void onDataReceived(WorkCardProtocol.Frame frame);
        void onRawDataReceived(byte[] raw);
        void onDataSent(byte[] raw);
        void onError(String error);
        void onLog(String log);
    }

    private SerialPort serialPort;
    private FileOutputStream outputStream;
    private ReadThread readThread;

    private String port;
    private int baudRate;
    private SerialEventListener listener;

    // 接收缓冲 → 协议解码
    private final List<Byte> receiveBuffer = new LinkedList<>();

    public SerialManager() { }

    public void setEventListener(SerialEventListener listener) {
        this.listener = listener;
    }

    public synchronized boolean isConnected() {
        return serialPort != null;
    }

    public String getPort() { return port; }
    public int getBaudRate() { return baudRate; }

    // ---- 连接 / 断开 ----

    public void connect(String port, int baudRate) {
        if (isConnected()) disconnect();
        this.port = port;
        this.baudRate = baudRate;

        try {
            serialPort = new SerialPort();
            FileDescriptor fd = serialPort.open(port, baudRate, 0);
            if (fd == null) throw new Exception("串口打开失败: " + port);

            outputStream = new FileOutputStream(fd);

            // 启动阻塞读取线程（真正的阻塞 I/O，无轮询延迟）
            readThread = new ReadThread(new FileInputStream(fd));
            readThread.start();

            if (listener != null) listener.onConnected(port, baudRate);
            log("已连接 " + port + " @ " + baudRate);
        } catch (Exception e) {
            serialPort = null;
            if (listener != null) listener.onError("连接失败: " + e.getMessage());
            log("连接失败: " + e.getMessage());
        }
    }

    public void disconnect() {
        if (readThread != null) {
            readThread.release();
            readThread = null;
        }
        if (outputStream != null) {
            try { outputStream.close(); } catch (IOException ignored) { }
            outputStream = null;
        }
        if (serialPort != null) {
            try { serialPort.close(); } catch (Exception ignored) { }
            serialPort = null;
        }
        port = null;
        baudRate = 0;
        synchronized (receiveBuffer) { receiveBuffer.clear(); }
        if (listener != null) listener.onDisconnected("手动断开");
        log("已断开连接");
    }

    // ---- 协议指令 ----

    public void sendQuery(int address) {
        send(WorkCardProtocol.query(address), "查询: 地址=0x" + hexB(address));
    }

    public void sendOpenDoor(int address, boolean admin) {
        send(WorkCardProtocol.openDoor(address, admin), "开门: 地址=0x" + hexB(address) + " " + (admin ? "管理员" : "普通"));
    }

    public void sendSetLed(int address, int duty) {
        if (duty < 30 || duty > 100) {
            if (listener != null) listener.onError("LED占空比必须在30~100之间");
            return;
        }
        send(WorkCardProtocol.setLedDutyCycle(address, duty), "设置LED: 地址=0x" + hexB(address) + " 占空比=" + duty + "%");
    }

    public void sendReadVersion(int address) {
        send(WorkCardProtocol.readVersion(address), "读取版本: 地址=0x" + hexB(address));
    }

    public void sendRawBytes(byte[] raw) {
        send(raw, hex(raw));
    }

    // ---- 内部发送 ----

    private void send(byte[] raw, String description) {
        if (!isConnected()) {
            Log.e(TAG, "发送失败(未连接): " + description);
            if (listener != null) listener.onError("串口未连接");
            log("发送失败(未连接): " + description);
            return;
        }
        String hexStr = hex(raw);
        Log.d(TAG, "写入串口: " + description + " → " + hexStr);
        try {
            outputStream.write(raw);
            outputStream.flush();
            Log.d(TAG, "写入成功: " + raw.length + " bytes → " + hexStr);
        } catch (IOException e) {
            Log.e(TAG, "写入失败: " + e.getMessage());
            if (listener != null) listener.onError("发送失败: " + e.getMessage());
            log("发送失败: " + e.getMessage());
            return;
        }
        if (listener != null) listener.onDataSent(raw);
        log("发送: " + description + " → " + hexStr);
    }

    // ---- 阻塞读取线程（真正的 I/O，非轮询） ----

    private class ReadThread extends Thread {
        private final FileInputStream inputStream;
        private final byte[] readBuffer = new byte[4096];
        private volatile boolean running = true;

        ReadThread(FileInputStream inputStream) {
            this.inputStream = inputStream;
        }

        @Override
        public void run() {
            log("读取线程已启动，阻塞等待数据...");
            Log.d(TAG, "读取线程已启动，阻塞等待数据...");
            while (running) {
                try {
                    // 阻塞等待数据 —— 数据一到内核缓冲区，read() 立即返回
                    int size = inputStream.read(readBuffer);
                    if (size <= 0) continue;

                    byte[] raw = new byte[size];
                    System.arraycopy(readBuffer, 0, raw, 0, size);

                    Log.d("SerialManager", "收到原始数据: " + hex(raw));
                    if (listener != null) listener.onRawDataReceived(raw);

                    synchronized (receiveBuffer) {
                        for (byte b : raw) receiveBuffer.add(b);
                        List<WorkCardProtocol.Frame> frames = WorkCardProtocol.decode(receiveBuffer);
                        for (WorkCardProtocol.Frame f : frames) {
                            if (listener != null) listener.onDataReceived(f);
                        }
                    }
                } catch (IOException e) {
                    if (running) {
                        log("读取线程IO异常: " + e.getMessage());
                        if (listener != null) listener.onError("读取异常: " + e.getMessage());
                    }
                    break;
                }
            }
            log("读取线程已终止");
        }

        void release() {
            running = false;
            interrupt();
            try {
                if (inputStream != null) inputStream.close();
            } catch (IOException ignored) { }
        }
    }

    // ---- 工具 ----

    public static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) builder.append(String.format("%02X", value & 0xFF));
        return builder.toString();
    }

    private static String hexB(int value) {
        return String.format("%02X", value & 0xFF);
    }

    private void log(String msg) {
        if (listener != null) listener.onLog(msg);
    }
}
