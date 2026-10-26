package com.dino.holeshield;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Resumes protection after a reboot when the user asked for it. */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        Prefs p = Prefs.load(context);
        if (!p.protectionWanted || !p.startOnBoot) return;
        try {
            context.startForegroundService(new Intent(context, DnsVpnService.class));
        } catch (Exception ignored) {
            // Some TV firmware blocks background service starts; opening the
            // app will start protection instead.
        }
    }
}
