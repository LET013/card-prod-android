package com.xingyao.card;

import android.app.Application;

import com.xingyao.card.core.log.CrashLogStore;

public final class CardApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        CrashLogStore.install(this);
    }
}
