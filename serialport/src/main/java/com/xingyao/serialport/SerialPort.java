package com.xingyao.serialport;

import android.util.Log;

import java.io.FileDescriptor;

/**
 * JNI 串口操作封装 — 纯 JNI 壳层
 */
public final class SerialPort {
    private static final String TAG = "SerialPort";

    private static volatile boolean libraryLoaded;
    private static volatile String libraryLoadError;

    static {
        try {
            System.loadLibrary("SerialPort");
            libraryLoaded = true;
            Log.d(TAG, "libSerialPort.so 加载成功");
        } catch (UnsatisfiedLinkError e) {
            libraryLoaded = false;
            libraryLoadError = e.getMessage();
            Log.e(TAG, "libSerialPort.so 加载失败: " + e.getMessage());
        }
    }

    public static boolean isAvailable() {
        return libraryLoaded;
    }

    public static String getLoadError() {
        return libraryLoadError;
    }

    protected FileDescriptor mFd;

    /** 启用 RS485 模式（内核自动方向控制，工控柜体常用） */
    public static final int FLAG_RS485 = 0x01;

    /**
     * 打开串口设备，返回文件描述符
     * @param path     设备路径，如 /dev/ttyS5
     * @param baudRate 波特率，如 57600
     * @param flags    标志位: 0=默认, FLAG_RS485=启用RS485模式
     */
    public synchronized native FileDescriptor open(String path, int baudRate, int flags);

    /** 关闭串口 */
    public synchronized native void close();

    /** 清空串口缓冲区 */
    public synchronized native void tcflush();
}
