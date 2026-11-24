package com.fntv.app;

import android.app.Activity;
import android.app.UiModeManager;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Build;

/**
 * 比亚迪 DiLink 车机横竖屏适配助手。
 *
 * 设计原则（对应“打开时不可自动旋转，APP 去适配车机横竖屏状态”的需求）：
 *   1. 启动时只读取“当前所在屏幕”的横竖屏状态并锁定，绝不请求与当前状态不同的方向，
 *      因此不会触发车机整屏旋转。
 *   2. 当 App 被车机在“主屏 / 副驾屏 / 分屏”之间移动时，activity 的 resources 已切换为
 *      目标屏的 Configuration，再次调用本方法即可自适应目标屏方向（支持屏幕双显）。
 *   3. 不使用 sensor / SENSOR_PORTRAIT，避免车机被反向强制旋转到竖屏且无法切回。
 */
public final class OrientationHelper {

    private OrientationHelper() {}

    /** 锁定到“当前所在屏幕”的横竖屏状态（读取当前 Configuration.orientation）。 */
    public static void lockToCurrentDisplay(Activity a) {
        if (a == null) return;
        int orientation = a.getResources().getConfiguration().orientation;
        a.setRequestedOrientation(orientation == Configuration.ORIENTATION_LANDSCAPE
                ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    }

    /** 在横屏 / 竖屏之间显式切换（用户手动旋转用，不使用 sensor）。 */
    public static void toggleOrientation(Activity a) {
        if (a == null) return;
        int orientation = a.getResources().getConfiguration().orientation;
        a.setRequestedOrientation(orientation == Configuration.ORIENTATION_LANDSCAPE
                ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
    }

    /** 是否为车机（Android Automotive 或 car UI mode）。 */
    public static boolean isCarDevice(Context c) {
        if (c == null) return false;
        UiModeManager um = (UiModeManager) c.getSystemService(Context.UI_MODE_SERVICE);
        if (um != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH
                && um.getCurrentModeType() == Configuration.UI_MODE_TYPE_CAR) {
            return true;
        }
        return c.getPackageManager().hasSystemFeature("android.hardware.type.automotive");
    }
}
