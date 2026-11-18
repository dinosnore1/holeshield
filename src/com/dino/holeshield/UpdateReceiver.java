package com.dino.holeshield;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Receives the install result from PackageInstaller for the in-app updater. */
public final class UpdateReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && UpdateEngine.ACTION_STATUS.equals(intent.getAction())) {
            UpdateEngine.onInstallStatus(context, intent);
        }
    }
}
