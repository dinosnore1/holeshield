package com.dino.holeshield;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;

/** Small runtime helpers for the shared TV/phone application. */
final class Device {

    private Device() { }

    static boolean isTv(Context c) {
        if (c == null) return false;
        int mode = c.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_TYPE_MASK;
        return mode == Configuration.UI_MODE_TYPE_TELEVISION
                || c.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK);
    }

    static String appName(Context c) {
        return isTv(c) ? "Hole Shield TV" : "Hole Shield";
    }

    static String clientName(Context c) {
        return isTv(c) ? "this TV" : "this phone";
    }

    /** Version code from the installed package. */
    static int versionCode(Context c) {
        try {
            android.content.pm.PackageInfo info = c.getPackageManager()
                    .getPackageInfo(c.getPackageName(), 0);
            return android.os.Build.VERSION.SDK_INT >= 28
                    ? (int) info.getLongVersionCode() : info.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Version name from the installed package, avoiding duplicated strings. */
    static String versionName(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }
}
