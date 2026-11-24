package com.fntv.app;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * 全局未捕获异常记录器：发生崩溃时将堆栈保存至 SharedPreferences，下次回到首页时弹窗展示，便于实车/投影仪定位。
 */
public class CrashGuard {

    private static final String PREF_CRASH = "last_crash_trace";
    private static boolean installed = false;

    public static synchronized void install(final Context ctx) {
        if (installed) return;
        installed = true;
        final Context appCtx = ctx.getApplicationContext();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    StringWriter sw = new StringWriter();
                    e.printStackTrace(new PrintWriter(sw));
                    String trace = "Thread=" + t.getName() + "\n" + sw.toString();
                    if (trace.length() > 3500) trace = trace.substring(0, 3500);
                    Log.e("CrashGuard", trace);
                    appCtx.getSharedPreferences("fntv_prefs", Context.MODE_PRIVATE)
                            .edit().putString(PREF_CRASH, trace).commit();
                } catch (Throwable ignored) {}
                if (prev != null) {
                    prev.uncaughtException(t, e);
                }
            }
        });
    }

    public static void showIfCrashed(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences("fntv_prefs", Context.MODE_PRIVATE);
            String trace = sp.getString(PREF_CRASH, "");
            if (trace != null && !trace.isEmpty()) {
                sp.edit().remove(PREF_CRASH).apply();
                new AlertDialog.Builder(ctx)
                        .setTitle("检测到上次播放异常退出")
                        .setMessage(trace)
                        .setPositiveButton("知道了", null)
                        .show();
            }
        } catch (Throwable ignored) {}
    }
}
