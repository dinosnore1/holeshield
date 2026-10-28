package com.dino.holeshield;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * Resumes protection after a reboot, and after this app is updated, when the user asked for it.
 *
 * Two things this TV does make that unreliable without help (VU/Hisense Android 11, 2026-10-29):
 * - it queues BOOT_COMPLETED receivers behind its own boot work (HiActivityManagerService), so the start
 *   here can land ~50 s after power-on or be refused outright on a busy boot;
 * - updating the app kills its process and leaves the VPN down until someone opens the app.
 *
 * So: try now, and on a boot that did not take, try once more a little later.
 */
public class BootReceiver extends BroadcastReceiver {
    /** Second attempt at a boot whose first start was refused or killed; DnsVpnService.running tells. */
    private static final String ACTION_RETRY = "com.dino.holeshield.BOOT_RETRY";
    private static final long RETRY_MS = 60_000;
    private static final int RETRY_REQUEST = 1;

    @Override public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action);
        Prefs p = Prefs.load(context);
        if (!p.protectionWanted || !p.startOnBoot) return;
        if (DnsVpnService.running) return;

        try {
            context.startForegroundService(new Intent(context, DnsVpnService.class));
        } catch (Exception ignored) {
            // Refused on some firmware; the alarm below is the second try.
        }
        // The first start can be refused, or killed while the TV is still booting: a boot gets one more go.
        // A package replacement is already the retry after an update, so it never schedules one.
        if (boot) scheduleRetry(context);
    }

    private static void scheduleRetry(Context context) {
        AlarmManager am = context.getSystemService(AlarmManager.class);
        if (am == null) return;
        PendingIntent retry = PendingIntent.getBroadcast(context, RETRY_REQUEST,
                new Intent(context, BootReceiver.class).setAction(ACTION_RETRY),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            // Inexact on purpose: no SCHEDULE_EXACT_ALARM needed, and a minute either way is fine here.
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + RETRY_MS, retry);
        } catch (Exception ignored) {
        }
    }
}
