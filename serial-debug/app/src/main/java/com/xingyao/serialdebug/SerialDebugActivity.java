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
import com.xingyao.serialdebug.serial.SerialManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 串口通信调试面板
 * - 串口配置（路径、波特率、连接/断开）
 * - 协议指令按钮（查询、开门、LED、版本）
 * - 原始 HEX 发送
 * - 自动轮询
 * - 实时日志
 */
public class SerialDebugActivity extends AppCompatActivity implements SerialManager.SerialEventListener {

    private SerialManager serialManager;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    // UI
    private EditText etPort, etBaudRate, etSlaveAddress, etRawHex;
    private Button btnConnect, btnQuery, btnOpenDoor, btnLed, btnVersion, btnSendRaw, btnAdminOpen, btnClearLog, btnAutoPoll;
    private TextView tvStatus, tvLog;
    private ScrollView svLog;

    private boolean autoPolling = false;
    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!autoPolling || !serialManager.isConnected()) return;
            int addr = getSlaveAddress();
            serialManager.sendQuery(addr);
            uiHandler.postDelayed(this, 2000); // 2秒轮询
        }
    };

    // 日志行数限制
    private static final int MAX_LOG_LINES = 500;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_serial_debug);

        serialManager = new SerialManager();
        serialManager.setEventListener(this);
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
            if (serialManager.isConnected()) {
                serialManager.disconnect();
            } else {
                String port = etPort.getText().toString().trim();
                int baudRate;
                try { baudRate = Integer.parseInt(etBaudRate.getText().toString().trim()); }
                catch (NumberFormatException e) { baudRate = 57600; }
                if (port.isEmpty()) {
                    toast("请输入串口路径");
                    return;
                }
                serialManager.connect(port, baudRate);
            }
        });

        // 协议指令
        btnQuery.setOnClickListener(v -> {
            if (!checkConnected()) return;
            serialManager.sendQuery(getSlaveAddress());
        });
        btnOpenDoor.setOnClickListener(v -> {
            if (!checkConnected()) return;
            serialManager.sendOpenDoor(getSlaveAddress(), false);
        });
        btnAdminOpen.setOnClickListener(v -> {
            if (!checkConnected()) return;
            serialManager.sendOpenDoor(getSlaveAddress(), true);
        });
        btnLed.setOnClickListener(v -> {
            if (!checkConnected()) return;
            int duty = 80; // 默认80%
            serialManager.sendSetLed(getSlaveAddress(), duty);
        });
        btnVersion.setOnClickListener(v -> {
            if (!checkConnected()) return;
            serialManager.sendReadVersion(getSlaveAddress());
        });

        // 原始 HEX 发送
        btnSendRaw.setOnClickListener(v -> {
            if (!checkConnected()) return;
            String hex = etRawHex.getText().toString().replaceAll("\\s+", "");
            if (hex.isEmpty()) { toast("请输入HEX数据"); return; }
            try {
                byte[] raw = hexToBytes(hex);
                serialManager.sendRawBytes(raw);
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

    // ---- SerialEventListener ----

    @Override public void onConnected(String port, int baudRate) {
        runOnUi(() -> {
            btnConnect.setText("断开");
            btnConnect.setBackgroundColor(0xFFFF4444);
            tvStatus.setText("已连接 " + port + " @ " + baudRate);
            tvStatus.setTextColor(0xFF00FF88);
            setCommandEnabled(true);
        });
    }

    @Override public void onDisconnected(String reason) {
        runOnUi(() -> {
            stopPollIfActive();
            btnConnect.setText("连接");
            btnConnect.setBackgroundColor(0xFF00FF88);
            tvStatus.setText(reason);
            tvStatus.setTextColor(0xFFFF4444);
            setCommandEnabled(false);
        });
    }

    @Override
    public void onDataReceived(WorkCardProtocol.Frame frame) {
        runOnUi(() -> {
            addLog(String.format("← [从=0x%02X 功能=0x%02X] 数据=%s",
                    frame.slaveAddress, frame.function, WorkCardProtocol.hex(frame.data)));
            addLog("  原始: " + WorkCardProtocol.hex(frame.raw));
        });
    }

    @Override public void onRawDataReceived(byte[] raw) {
        // 原始数据已通过 onDataReceived 展示，此处不重复
    }

    @Override public void onDataSent(byte[] raw) {
        runOnUi(() -> {
            addLog("→ 发送: " + WorkCardProtocol.hex(raw));
        });
    }

    @Override public void onError(String error) {
        runOnUi(() -> {
            addLog("⚠ 错误: " + error);
        });
    }

    @Override public void onLog(String log) {
        runOnUi(() -> {
            addLog(log);
        });
    }

    // ---- 工具方法 ----

    private boolean checkConnected() {
        if (!serialManager.isConnected()) {
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
        if (serialManager.isConnected()) serialManager.disconnect();
    }
}
