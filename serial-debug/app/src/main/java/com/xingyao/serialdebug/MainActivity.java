package com.xingyao.serialdebug;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import androidx.appcompat.app.AppCompatActivity;

/**
 * 串口调试工具入口
 */
public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button btnDebug = findViewById(R.id.btnEnterDebug);
        btnDebug.setOnClickListener(v -> startActivity(new Intent(this, SerialDebugActivity.class)));
    }
}
