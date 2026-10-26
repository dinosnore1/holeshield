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
}
