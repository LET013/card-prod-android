package com.xingyao.card.core.maintenance;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 接收延迟重启闹钟，并在保存执行凭据后重启应用进程。 */
public final class AppRestartReceiver extends BroadcastReceiver {
    public static final String ACTION_EXECUTE_RESTART = "com.xingyao.card.action.EXECUTE_APP_RESTART";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_EXECUTE_RESTART.equals(intent.getAction())) return;
        String operationId = intent.getStringExtra(AppRestartManager.EXTRA_OPERATION_ID);
        if (operationId == null || operationId.trim().isEmpty()) return;
        AppRestartManager.execute(context, operationId.trim());
    }
}
