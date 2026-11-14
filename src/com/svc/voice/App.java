package com.svc.voice;

import android.app.Application;
import android.content.Context;

/** 让 Service / Application 也用同一套 App 语言（不改系统语言） */
public class App extends Application {
    @Override public void onCreate() {
        super.onCreate();
        // QNN/HTP：注册解压后的 .so 目录（判定本安装包内到底有哪些 V*n*Skel）
        try { SysProps.setNativeLibDir(getApplicationInfo().nativeLibraryDir); } catch (Throwable ignore) {}
        BundledModels.init(this); BundledModels.request(this);
        // 打开时识别一次 SoC/架构并常驻内存常量（不在每次编译时重复读系统文件）
        HtpTable.attach(this);
        SocInfo.detect();
    }
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(I18n.wrap(base));
    }
}