package com.xingyao.serialdebug;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.xingyao.serialdebug.protocol.WorkCardProtocol;
import com.xingyao.serialport.SerialManager;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

/**
 * 串口通信调试面板
 * - 串口配置（路径、波特率、连接/断开）
 * - 协议指令按钮（查询、开门、LED、版本）
 * - 原始 HEX 发送
 * - 自动轮询
 * - 实时日志
 *
 * 使用 serialport AAR 库（com.xingyao.serialport.SerialManager）进行串口 I/O，
 * 协议编解码由 WorkCardProtocol 处理。
 */
public class SerialDebugActivity extends AppCompatActivity {

    private SerialManager serialManager;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    // 协议接收缓冲 —— 原始字节 → WorkCardProtocol 帧解码
    private final List<Byte> receiveBuffer = new LinkedList<>();
    private String currentPort;
    private int currentBaudRate;

    // UI
    private EditText etPort, etBaudRate, etSlaveAddress, etRawHex;
    private Button btnConnect, btnQuery, btnOpenDoor, btnLed, btnVersion, btnSendRaw, btnAdminOpen, btnClearLog, btnAutoPoll;
    private TextView tvStatus, tvLog;
    private ScrollView svLog;

    private boolean autoPolling = false;
    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!autoPolling || !serialManager.isOpen()) return;
            int addr = getSlaveAddress();
            sendQuery(addr);
            uiHandler.postDelayed(this, 2000);
        }
    };

    // 日志行数限制
    private static final int MAX_LOG_LINES = 500;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_serial_debug);

        // 直接使用 AAR 的 SerialManager
        serialManager = new SerialManager();
        serialManager.setOnDataReceivedListener(data -> {
            // 原始数据 → 缓冲 → WorkCardProtocol 帧解码
            synchronized (receiveBuffer) {
                for (byte b : data) receiveBuffer.add(b);
                List<WorkCardProtocol.Frame> frames = WorkCardProtocol.decode(receiveBuffer);
                for (WorkCardProtocol.Frame f : frames) {
                    runOnUi(() -> {
                        addLog(String.format("← [从=0x%02X 功能=0x%02X] 数据=%s",
                                f.slaveAddress, f.function, WorkCardProtocol.hex(f.data)));
                        addLog("  原始: " + WorkCardProtocol.hex(f.raw));
                    });
                }
            }
        });

        initViews();
    }

    private void initViews() {
        etPort = findViewById(R.id.etPort);
        etBaudRate = findViewById(R.id.etBaudRate);
        etSlaveAddress = findViewById(R.id.etSlaveAddress);
        etRawHex = findViewById(R.id.etRawHex);

        btnConnect = findViewById(R.id.btnConnect);
        btnQuery = findViewById(R.id.btnQuery);
        btnOpenDoor = findViewById(R.id.btnOpenDoor);
        btnLed = findViewById(R.id.btnLed);
        btnVersion = findViewById(R.id.btnVersion);
        btnSendRaw = findViewById(R.id.btnSendRaw);
        btnAdminOpen = findViewById(R.id.btnAdminOpen);
        btnClearLog = findViewById(R.id.btnClearLog);
        btnAutoPoll = findViewById(R.id.btnAutoPoll);

        tvStatus = findViewById(R.id.tvStatus);
        tvLog = findViewById(R.id.tvLog);
        svLog = findViewById(R.id.svLog);
        tvLog.setMovementMethod(ScrollingMovementMethod.getInstance());

        // 连接/断开
        btnConnect.setOnClickListener(v -> {
            if (serialManager.isOpen()) {
                // 断开
                serialManager.close();
                synchronized (receiveBuffer) { receiveBuffer.clear(); }
                onDisconnected("手动断开");
            } else {
                // 连接
                String port = etPort.getText().toString().trim();
                int baudRate;
                try { baudRate = Integer.parseInt(etBaudRate.getText().toString().trim()); }
                catch (NumberFormatException e) { baudRate = 57600; }
                if (port.isEmpty()) {
                    toast("请输入串口路径");
                    return;
                }
                if (serialManager.open(port, baudRate)) {
                    currentPort = port;
                    currentBaudRate = baudRate;
                    onConnected(port, baudRate);
                } else {
                    onError("连接失败，请检查设备路径和权限");
                }
            }
        });

        // 协议指令
        btnQuery.setOnClickListener(v -> {
            if (!checkConnected()) return;
            sendQuery(getSlaveAddress());
        });
        btnOpenDoor.setOnClickListener(v -> {
            if (!checkConnected()) return;
            sendOpenDoor(getSlaveAddress(), false);
        });
        btnAdminOpen.setOnClickListener(v -> {
            if (!checkConnected()) return;
            sendOpenDoor(getSlaveAddress(), true);
        });
        btnLed.setOnClickListener(v -> {
            if (!checkConnected()) return;
            int duty = 80;
            sendSetLed(getSlaveAddress(), duty);
        });
        btnVersion.setOnClickListener(v -> {
            if (!checkConnected()) return;
            sendReadVersion(getSlaveAddress());
        });

        // 原始 HEX 发送
        btnSendRaw.setOnClickListener(v -> {
            if (!checkConnected()) return;
            String hex = etRawHex.getText().toString().replaceAll("\\s+", "");
            if (hex.isEmpty()) { toast("请输入HEX数据"); return; }
            try {
                byte[] raw = hexToBytes(hex);
                sendRaw(raw);
            } catch (Exception e) {
                toast("HEX格式错误: " + e.getMessage());
            }
        });

        // 自动轮询
        btnAutoPoll.setOnClickListener(v -> {
            autoPolling = !autoPolling;
            btnAutoPoll.setText(autoPolling ? "停止轮询" : "自动轮询");
            btnAutoPoll.setTextColor(autoPolling ? 0xFF00FF88 : 0xFF888888);
            if (autoPolling) {
                if (!checkConnected()) { autoPolling = false; return; }
                uiHandler.post(pollRunnable);
                addLog(">>> 自动轮询已开启 (2秒/次)");
            } else {
                uiHandler.removeCallbacks(pollRunnable);
                addLog(">>> 自动轮询已停止");
            }
        });

        // 清空日志
        btnClearLog.setOnClickListener(v -> tvLog.setText(""));
    }

    // ---- 协议发送（内联 WorkCardProtocol，直接用 AAR SerialManager.send） ----

    private void sendQuery(int address) {
        sendFrame(WorkCardProtocol.query(address), "查询: 地址=0x" + hexB(address));
    }

    private void sendOpenDoor(int address, boolean admin) {
        sendFrame(WorkCardProtocol.openDoor(address, admin),
                "开门: 地址=0x" + hexB(address) + " " + (admin ? "管理员" : "普通"));
    }

    private void sendSetLed(int address, int duty) {
        if (duty < 30 || duty > 100) {
            onError("LED占空比必须在30~100之间");
            return;
        }
        sendFrame(WorkCardProtocol.setLedDutyCycle(address, duty),
                "设置LED: 地址=0x" + hexB(address) + " 占空比=" + duty + "%");
    }

    private void sendReadVersion(int address) {
        sendFrame(WorkCardProtocol.readVersion(address), "读取版本: 地址=0x" + hexB(address));
    }

    private void sendRaw(byte[] raw) {
        sendFrame(raw, WorkCardProtocol.hex(raw));
    }

    private void sendFrame(byte[] data, String description) {
        try {
            serialManager.send(data);
        } catch (IOException e) {
            onError("发送失败: " + e.getMessage());
            return;
        }
        onDataSent(data);
        addLog("→ 发送: " + description + " → " + WorkCardProtocol.hex(data));
    }

    // ---- UI 事件回调（替代 SerialEventListener 接口） ----

    private void onConnected(String port, int baudRate) {
        runOnUi(() -> {
            btnConnect.setText("断开");
            btnConnect.setBackgroundColor(0xFFFF4444);
            tvStatus.setText("已连接 " + port + " @ " + baudRate);
            tvStatus.setTextColor(0xFF00FF88);
            setCommandEnabled(true);
            addLog("已连接 " + port + " @ " + baudRate);
        });
    }

    private void onDisconnected(String reason) {
        runOnUi(() -> {
            stopPollIfActive();
            btnConnect.setText("连接");
            btnConnect.setBackgroundColor(0xFF00FF88);
            tvStatus.setText(reason);
            tvStatus.setTextColor(0xFFFF4444);
            setCommandEnabled(false);
            addLog(reason);
        });
    }

    private void onDataSent(byte[] raw) {
        runOnUi(() -> {
            addLog("→ 发送: " + WorkCardProtocol.hex(raw));
        });
    }

    private void onError(String error) {
        runOnUi(() -> {
            addLog("⚠ 错误: " + error);
        });
    }

    // ---- 工具方法 ----

    private boolean checkConnected() {
        if (!serialManager.isOpen()) {
            toast("请先连接串口");
            return false;
        }
        return true;
    }

    private int getSlaveAddress() {
        try {
            return Integer.parseInt(etSlaveAddress.getText().toString().trim(), 16);
        } catch (NumberFormatException e) {
            return 0x01;
        }
    }

    private void stopPollIfActive() {
        if (autoPolling) {
            autoPolling = false;
            uiHandler.removeCallbacks(pollRunnable);
            btnAutoPoll.setText("自动轮询");
            btnAutoPoll.setTextColor(0xFF888888);
        }
    }

    private void setCommandEnabled(boolean enabled) {
        btnQuery.setEnabled(enabled);
        btnOpenDoor.setEnabled(enabled);
        btnAdminOpen.setEnabled(enabled);
        btnLed.setEnabled(enabled);
        btnVersion.setEnabled(enabled);
        btnSendRaw.setEnabled(enabled);
    }

    private void addLog(String msg) {
        String timestamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
        String line = timestamp + " " + msg + "\n";
        tvLog.append(line);

        // 限制日志行数
        String text = tvLog.getText().toString();
        String[] lines = text.split("\n");
        if (lines.length > MAX_LOG_LINES) {
            StringBuilder trimmed = new StringBuilder();
            for (int i = lines.length - MAX_LOG_LINES; i < lines.length; i++) {
                trimmed.append(lines[i]).append("\n");
            }
            tvLog.setText(trimmed.toString());
        }

        svLog.post(() -> svLog.fullScroll(View.FOCUS_DOWN));
    }

    private byte[] hexToBytes(String hex) {
        if (hex.length() % 2 != 0) throw new IllegalArgumentException("HEX长度必须为偶数");
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < hex.length(); i += 2) {
            bytes[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
        }
        return bytes;
    }

    private static String hexB(int value) {
        return String.format("%02X", value & 0xFF);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void runOnUi(Runnable r) {
        uiHandler.post(r);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPollIfActive();
        if (serialManager.isOpen()) serialManager.close();
    }
}
