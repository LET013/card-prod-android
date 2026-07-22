package com.xingyao.serialdebug.serial;

import java.io.FileDescriptor;

/**
 * JNI 原生串口操作 —— 直接通过原生 open/close/tcflush 操作串口设备，
 * 返回 FileDescriptor 供 Java 层 FileInputStream/FileOutputStream 使用。
 *
 * 使用真正的阻塞 I/O（InputStream.read 阻塞直到数据到达），
 * 无轮询延迟，串口数据一到即可读出。
 */
public class SerialPort {

    static {
        System.loadLibrary("SerialPort");
    }

    protected FileDescriptor mFd;

    /** 打开串口设备，返回 FileDescriptor */
    protected native FileDescriptor open(String path, int baudRate, int flags);

    /** 关闭串口 */
    protected native void close();

    /** 清空串口缓冲区 */
    public native void tcflush();
}
