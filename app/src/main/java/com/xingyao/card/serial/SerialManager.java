package com.xingyao.card.serial;

import android.util.Log;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * 串口管理 — 基于 JNI SerialPort 实现的发包/收包
 * 使用阻塞 I/O 读取：独立 ReadThread 阻塞在 inputStream.read()，
 * 数据一到内核缓冲区立即返回，无轮询延迟。
 *
 * 调用链（与 serial-debug 参考实现一致）：
 *   SerialManager.open() → new SerialPort() → serialPort.open() → fd → 流 → ReadThread
 */
public class SerialManager {
    private static final String TAG = "SerialManager";

    private SerialPort serialPort;
    private FileOutputStream fileOutputStream;
    private OutputStream outputStream;
    private ReadThread readThread;
    private OnDataReceivedListener dataListener;

    // --- 回调接口 ---

    public interface OnDataReceivedListener {
        void onDataReceived(byte[] data);
    }

    public void setOnDataReceivedListener(OnDataReceivedListener listener) {
        this.dataListener = listener;
    }

    // --- 打开 / 关闭 ---

    public boolean open(String devicePath, int baudRate) {
        if (!new File(devicePath).exists()) {
            Log.e(TAG, "设备不存在: " + devicePath);
            return false;
        }
        try {
            serialPort = new SerialPort();
            FileDescriptor fd = serialPort.open(devicePath, baudRate, 0);
            if (fd == null) {
                Log.e(TAG, "native open 返回 null fd");
                return false;
            }
            fileOutputStream = new FileOutputStream(fd);
            outputStream = fileOutputStream;
            FileInputStream inputStream = new FileInputStream(fd);
            startReader(inputStream);
            Log.i(TAG, "串口打开成功: " + devicePath + " @ " + baudRate);
            return true;
        } catch (SecurityException e) {
            Log.e(TAG, "串口权限不足: " + devicePath, e);
            return false;
        }
    }

    public void close() {
        stopReader();
        if (serialPort != null) {
            serialPort.close();
            serialPort = null;
        }
        outputStream = null;
        fileOutputStream = null;
        Log.i(TAG, "串口已关闭");
    }

    public boolean isOpen() {
        return serialPort != null;
    }

    // --- 发送 ---

    public void send(byte[] data) {
        if (serialPort == null || outputStream == null) {
            Log.e(TAG, "串口未打开，无法发送数据");
            return;
        }
        try {
            outputStream.write(data);
            outputStream.flush();
            Log.d(TAG, "发送 " + data.length + " 字节");
        } catch (IOException e) {
            Log.e(TAG, "串口发送失败", e);
        }
    }

    // --- 阻塞读取线程（参考 serial-debug 实现） ---

    private void startReader(FileInputStream inputStream) {
        readThread = new ReadThread(inputStream);
        readThread.start();
    }

    private void stopReader() {
        if (readThread != null) {
            readThread.release();
            readThread = null;
        }
    }

    private class ReadThread extends Thread {
        private final FileInputStream inputStream;
        private final byte[] readBuffer = new byte[4096];
        private volatile boolean running = true;

        ReadThread(FileInputStream inputStream) {
            super("SerialReadThread");
            this.inputStream = inputStream;
        }

        @Override
        public void run() {
            Log.d(TAG, "读取线程已启动");
            while (running) {
                try {
                    // 阻塞等待数据 — 数据到达内核缓冲区后立即返回
                    int size = inputStream.read(readBuffer);
                    if (size <= 0) continue;

                    byte[] raw = new byte[size];
                    System.arraycopy(readBuffer, 0, raw, 0, size);

                    Log.d(TAG, "收到 " + size + " 字节");
                    if (dataListener != null) {
                        dataListener.onDataReceived(raw);
                    }
                } catch (IOException e) {
                    if (running) {
                        Log.e(TAG, "串口读取异常: " + e.getMessage());
                    }
                    break;
                }
            }
            Log.d(TAG, "读取线程已退出");
        }

        void release() {
            running = false;
            interrupt();
            try {
                inputStream.close();
            } catch (IOException ignored) {
            }
        }
    }
}
