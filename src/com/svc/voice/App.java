package com.svc.voice;

import android.app.Application;
import android.content.Context;

/** 让 Service / Application 也用同一套 App 语言（不改系统语言） */
public class App extends Application {
    @Override public void onCreate() { super.onCreate(); BundledModels.init(this); BundledModels.request(this); }
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(I18n.wrap(base));
    }
}