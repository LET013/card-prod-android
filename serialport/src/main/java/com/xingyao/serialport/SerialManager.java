package com.xingyao.serialport;

import android.util.Log;

import com.xingyao.serialport.SerialPort;

import java.io.File;
import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * 串口管理 — 基于 JNI SerialPort 实现的发包/收包
 * 使用阻塞 I/O 读取：独立 ReadThread 阻塞在 inputStream.read()，
 * 数据一到内核缓冲区立即返回，无轮询延迟。
 */
public class SerialManager {
    private static final String TAG = "SerialManager";
    private static final long EMPTY_READ_BACKOFF_MS = 5L;

    private SerialPort serialPort;
    private FileInputStream fileInputStream;
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
        close();
        if (!SerialPort.isAvailable()) {
            Log.e(TAG, "JNI 串口库不可用: " + SerialPort.getLoadError());
            return false;
        }
        if (!new File(devicePath).exists()) {
            Log.e(TAG, "设备不存在: " + devicePath);
            return false;
        }
        try {
            serialPort = new SerialPort();
            FileDescriptor fd = serialPort.open(devicePath, baudRate, SerialPort.FLAG_RS485);
            if (fd == null) {
                Log.e(TAG, "native open 返回 null fd");
                return false;
            }
            Log.d(TAG, "fd.valid()=" + fd.valid() + ", fd=" + fd);
            // 清空串口残留数据，确保从干净状态开始读取
            serialPort.tcflush();
            fileOutputStream = new FileOutputStream(fd);
            outputStream = fileOutputStream;
            fileInputStream = new FileInputStream(fd);
            startReader(fileInputStream);
            Log.i(TAG, "串口打开成功: " + devicePath + " @ " + baudRate);
            return true;
        } catch (Exception e) {
            close();
            Log.e(TAG, "串口打开失败: " + devicePath, e);
            return false;
        }
    }

    public void close() {
        ReadThread reader = readThread;
        if (reader != null) reader.requestStop();
        if (serialPort != null) {
            serialPort.close();
            serialPort = null;
        }
        if (reader != null) reader.awaitStopped();
        readThread = null;
        closeQuietly(fileOutputStream);
        closeQuietly(fileInputStream);
        outputStream = null;
        fileOutputStream = null;
        fileInputStream = null;
        Log.i(TAG, "串口已关闭");
    }

    public boolean isOpen() {
        return serialPort != null;
    }

    // --- 发送 ---

    public void send(byte[] data) throws IOException {
        if (serialPort == null || outputStream == null) {
            throw new IOException("串口未打开，无法发送数据");
        }
        outputStream.write(data);
        outputStream.flush();
//            Log.d(TAG, "发送 " + data.length + " 字节: " + bytesToHex(data));
    }

    // --- 阻塞读取线程 ---

    private void startReader(FileInputStream inputStream) {
        readThread = new ReadThread(inputStream);
        readThread.start();
    }

    private static void closeQuietly(Closeable stream) {
        if (stream == null) return;
        try {
            stream.close();
        } catch (IOException ignored) {
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
            Log.d(TAG, "读取线程已启动, tid=" + getId());
            Log.d(TAG, "进入阻塞 read() 等待数据...");
            while (running) {
                try {
                    // 阻塞等待数据 — 数据到达内核缓冲区后立即返回
                    int size = inputStream.read(readBuffer);
                    if (size < 0) break;
                    if (size == 0) {
                        // 某些底层驱动会在无数据时快速返回 0；仅此时短暂退避，
                        // 正常报文仍立即交给协议层，不能影响开门 ACK 实时性。
                        try {
                            Thread.sleep(EMPTY_READ_BACKOFF_MS);
                        } catch (InterruptedException error) {
                            if (!running) break;
                        }
                        continue;
                    }

                    byte[] raw = new byte[size];
                    System.arraycopy(readBuffer, 0, raw, 0, size);

                    if (dataListener != null) {
                        dataListener.onDataReceived(raw);
                    }
                } catch (IOException e) {
                    if (running) {
                        Log.e(TAG, "串口读取异常", e);
                    } else {
                        Log.d(TAG, "读取线程正常关闭: " + e.getMessage());
                    }
                    break;
                }
            }
            Log.d(TAG, "读取线程已退出");
        }

        void requestStop() {
            running = false;
            interrupt();
        }

        void awaitStopped() {
            try {
                join(500L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
