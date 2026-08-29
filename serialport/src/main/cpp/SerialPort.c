#include <jni.h>
#include <string.h>
#include <errno.h>
#include <unistd.h>
#include <fcntl.h>
#include <termios.h>
#include <sys/ioctl.h>
#include <linux/serial.h>
#include <android/log.h>

static const char *TAG = "SerialPort-JNI";
#define LOGD(fmt, args...) __android_log_print(ANDROID_LOG_DEBUG, TAG, fmt, ##args)
#define LOGE(fmt, args...) __android_log_print(ANDROID_LOG_ERROR, TAG, fmt, ##args)

static speed_t getBaudrate(jint baudrate) {
    switch (baudrate) {
        case 0:       return B0;
        case 50:      return B50;
        case 75:      return B75;
        case 110:     return B110;
        case 134:     return B134;
        case 150:     return B150;
        case 200:     return B200;
        case 300:     return B300;
        case 600:     return B600;
        case 1200:    return B1200;
        case 1800:    return B1800;
        case 2400:    return B2400;
        case 4800:    return B4800;
        case 9600:    return B9600;
        case 19200:   return B19200;
        case 38400:   return B38400;
        case 57600:   return B57600;
        case 115200:  return B115200;
        case 230400:  return B230400;
        case 460800:  return B460800;
        case 500000:  return B500000;
        case 576000:  return B576000;
        case 921600:  return B921600;
        case 1000000: return B1000000;
        case 1152000: return B1152000;
        case 1500000: return B1500000;
        case 2000000: return B2000000;
        case 2500000: return B2500000;
        case 3000000: return B3000000;
        case 3500000: return B3500000;
        case 4000000: return B4000000;
        default:      return -1;
    }
}

JNIEXPORT jobject JNICALL
Java_com_xingyao_serialport_SerialPort_open(JNIEnv *env, jobject thiz, jstring path, jint baudrate, jint flags) {
    int fd;
    speed_t speed;
    jobject mFileDescriptor;

    /* Check arguments */
    {
        speed = getBaudrate(baudrate);
        if (speed == -1) {
            LOGE("Invalid baudrate: %d", baudrate);
            return NULL;
        }
    }

    if (path == NULL) {
        LOGE("Serial port path is null");
        return NULL;
    }

    /* Opening device */
    {
        const char *path_utf = (*env)->GetStringUTFChars(env, path, NULL);
        if (path_utf == NULL) {
            LOGE("Cannot read serial port path");
            return NULL;
        }
        LOGD("Opening serial port %s", path_utf);
        fd = open(path_utf, O_RDWR | O_NOCTTY | O_NDELAY);
        (*env)->ReleaseStringUTFChars(env, path, path_utf);
        if (fd == -1) {
            LOGE("Cannot open port (errno=%d)", errno);
            return NULL;
        }
        LOGD("open() fd = %d", fd);
    }

    /* Configure device */
    {
        struct termios cfg;
        if (tcgetattr(fd, &cfg)) {
            LOGE("tcgetattr() failed (errno=%d)", errno);
            close(fd);
            return NULL;
        }
        LOGD("tcgetattr: c_cflag=0x%x iflag=0x%x oflag=0x%x lflag=0x%x",
             cfg.c_cflag, cfg.c_iflag, cfg.c_oflag, cfg.c_lflag);

        cfmakeraw(&cfg);
        cfsetispeed(&cfg, speed);
        cfsetospeed(&cfg, speed);

        // 8N1: 去掉奇偶校验、2 停止位，设 8 数据位
        cfg.c_cflag &= ~PARENB;
        cfg.c_cflag &= ~CSTOPB;
        cfg.c_cflag &= ~CSIZE;
        cfg.c_cflag |= CS8;

        // 启用接收器 + 忽略调制解调器控制线
        cfg.c_cflag |= CREAD | CLOCAL;

        // 禁用硬件流控（RTS/CTS），嵌入式串口常未接线导致收不到数据
        cfg.c_cflag &= ~CRTSCTS;

        if (tcsetattr(fd, TCSANOW, &cfg)) {
            LOGE("tcsetattr() failed (errno=%d)", errno);
            close(fd);
            return NULL;
        }

        // 验证配置生效
        {
            struct termios verify;
            if (tcgetattr(fd, &verify) == 0) {
                LOGD("配置生效: c_cflag=0x%x iflag=0x%x VMIN=%d VTIME=%d ispeed=%d ospeed=%d",
                     verify.c_cflag, verify.c_iflag,
                     verify.c_cc[VMIN], verify.c_cc[VTIME],
                     cfgetispeed(&verify), cfgetospeed(&verify));
                LOGD("标志检查: CREAD=%d CLOCAL=%d CS8=%d CRTSCTS=%d CSTOPB=%d PARENB=%d",
                     (verify.c_cflag & CREAD) ? 1 : 0,
                     (verify.c_cflag & CLOCAL) ? 1 : 0,
                     ((verify.c_cflag & CSIZE) == CS8) ? 1 : 0,
                     (verify.c_cflag & CRTSCTS) ? 1 : 0,
                     (verify.c_cflag & CSTOPB) ? 1 : 0,
                     (verify.c_cflag & PARENB) ? 1 : 0);
            }
        }

        // 清除 O_NONBLOCK，使 fd 变为阻塞模式
        {
            int fdFlags = fcntl(fd, F_GETFL, 0);
            if (fdFlags < 0) {
                LOGE("fcntl(F_GETFL) failed (errno=%d)", errno);
                close(fd);
                return NULL;
            }
            if (fcntl(fd, F_SETFL, fdFlags & ~O_NONBLOCK) < 0) {
                LOGE("fcntl(F_SETFL) failed (errno=%d)", errno);
                close(fd);
                return NULL;
            }
        }

        // 拉高 RTS + DTR：许多嵌入式设备需要主机先发出"就绪"信号才开始发送数据
        {
            int status;
            if (ioctl(fd, TIOCMGET, &status) < 0) {
                LOGD("TIOCMGET 失败 (errno=%d), 跳过RTS/DTR控制", errno);
            } else {
                LOGD("TIOCMGET 当前状态: RTS=%d DTR=%d CTS=%d DSR=%d",
                     (status & TIOCM_RTS) ? 1 : 0,
                     (status & TIOCM_DTR) ? 1 : 0,
                     (status & TIOCM_CTS) ? 1 : 0,
                     (status & TIOCM_DSR) ? 1 : 0);

                status |= TIOCM_RTS | TIOCM_DTR;
                if (ioctl(fd, TIOCMSET, &status) < 0) {
                    LOGD("TIOCMSET 失败 (errno=%d)", errno);
                } else {
                    LOGD("RTS + DTR 已拉高");
                }
            }
        }

        // RS485 模式处理已移除：内核 UART 驱动不支持 TIOCSRS485（errno=25），
        // 保留原有尝试只会打印失败日志，方向控制实际不生效。
        // 串口以普通模式打开，RTS/DTR 在下方拉高，方向控制依赖板卡 DE/RE 接线。

        LOGD("串口配置完成");
    }

    /* Create a corresponding FileDescriptor */
    {
        jclass cFileDescriptor = (*env)->FindClass(env, "java/io/FileDescriptor");
        if (cFileDescriptor == NULL) {
            close(fd);
            return NULL;
        }
        jmethodID iFileDescriptor = (*env)->GetMethodID(env, cFileDescriptor, "<init>", "()V");
        if (iFileDescriptor == NULL) {
            close(fd);
            return NULL;
        }
        jfieldID descriptorID = (*env)->GetFieldID(env, cFileDescriptor, "descriptor", "I");
        if (descriptorID == NULL) {
            close(fd);
            return NULL;
        }
        mFileDescriptor = (*env)->NewObject(env, cFileDescriptor, iFileDescriptor);
        if (mFileDescriptor == NULL) {
            close(fd);
            return NULL;
        }
        (*env)->SetIntField(env, mFileDescriptor, descriptorID, (jint) fd);
    }

    /* Store into SerialPort.mFd so close() can use it */
    {
        jclass SerialPortClass = (*env)->GetObjectClass(env, thiz);
        jfieldID mFdID = (*env)->GetFieldID(env, SerialPortClass, "mFd", "Ljava/io/FileDescriptor;");
        if (mFdID == NULL) {
            close(fd);
            return NULL;
        }
        (*env)->SetObjectField(env, thiz, mFdID, mFileDescriptor);
    }

    LOGD("串口打开成功: fd=%d, baudRate=%d", fd, baudrate);
    return mFileDescriptor;
}

JNIEXPORT void JNICALL
Java_com_xingyao_serialport_SerialPort_close(JNIEnv *env, jobject thiz) {
    jclass SerialPortClass = (*env)->GetObjectClass(env, thiz);
    jclass FileDescriptorClass = (*env)->FindClass(env, "java/io/FileDescriptor");
    if (SerialPortClass == NULL || FileDescriptorClass == NULL) return;

    jfieldID mFdID = (*env)->GetFieldID(env, SerialPortClass, "mFd", "Ljava/io/FileDescriptor;");
    jfieldID descriptorID = (*env)->GetFieldID(env, FileDescriptorClass, "descriptor", "I");
    if (mFdID == NULL || descriptorID == NULL) return;

    jobject mFd = (*env)->GetObjectField(env, thiz, mFdID);
    if (mFd == NULL) {
        LOGD("close() called but mFd is null, already closed?");
        return;
    }
    jint descriptor = (*env)->GetIntField(env, mFd, descriptorID);

    // Java input/output streams share this descriptor. Clear it before closing so
    // their later cleanup cannot close an unrelated descriptor reused by the OS.
    (*env)->SetObjectField(env, thiz, mFdID, NULL);
    (*env)->SetIntField(env, mFd, descriptorID, -1);
    if (descriptor >= 0) {
        LOGD("close(fd = %d)", descriptor);
        close(descriptor);
    }
}

JNIEXPORT void JNICALL
Java_com_xingyao_serialport_SerialPort_tcflush(JNIEnv *env, jobject thiz) {
    jclass SerialPortClass = (*env)->GetObjectClass(env, thiz);
    jclass FileDescriptorClass = (*env)->FindClass(env, "java/io/FileDescriptor");
    if (SerialPortClass == NULL || FileDescriptorClass == NULL) return;

    jfieldID mFdID = (*env)->GetFieldID(env, SerialPortClass, "mFd", "Ljava/io/FileDescriptor;");
    jfieldID descriptorID = (*env)->GetFieldID(env, FileDescriptorClass, "descriptor", "I");
    if (mFdID == NULL || descriptorID == NULL) return;

    jobject mFd = (*env)->GetObjectField(env, thiz, mFdID);
    if (mFd != NULL) {
        jint descriptor = (*env)->GetIntField(env, mFd, descriptorID);
        if (descriptor >= 0) {
            tcflush(descriptor, TCIOFLUSH);
            LOGD("串口缓冲区已清空: fd=%d", descriptor);
        }
    }
}
