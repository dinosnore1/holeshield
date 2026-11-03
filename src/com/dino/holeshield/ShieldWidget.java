package com.dino.holeshield;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.widget.RemoteViews;

/** Home-screen widget: status plus pause/resume and open actions. */
public class ShieldWidget extends AppWidgetProvider {

    static final String ACTION_PAUSE = "com.dino.holeshield.WIDGET_PAUSE";
    static final String ACTION_TOGGLE = "com.dino.holeshield.WIDGET_TOGGLE";

    @Override public void onUpdate(Context context, AppWidgetManager manager,
                                   int[] appWidgetIds) {
        updateAll(context);
    }

    @Override public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        String action = intent == null ? null : intent.getAction();
        if (ACTION_PAUSE.equals(action)) {
            Intent service = new Intent(context, DnsVpnService.class);
            service.setAction(DnsVpnService.ACTION_PAUSE_30);
            context.startService(service);
        } else if (ACTION_TOGGLE.equals(action)) {
            Prefs prefs = Prefs.load(context);
            if (DnsVpnService.running && !prefs.blockingActive()) {
                Intent service = new Intent(context, DnsVpnService.class);
                service.setAction(DnsVpnService.ACTION_RESUME);
                context.startService(service);
            } else if (DnsVpnService.running) {
                Intent service = new Intent(context, DnsVpnService.class);
                service.setAction(DnsVpnService.ACTION_STOP);
                context.startService(service);
            } else if (VpnService.prepare(context) != null) {
                Intent open = new Intent(context, MainActivity.class)
                        .putExtra("start_protection", true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(open);
            } else {
                prefs.protectionWanted = true;
                prefs.save(context);
                context.startForegroundService(
                        new Intent(context, DnsVpnService.class));
            }
        }
        updateAll(context);
    }

    static void updateAll(Context context) {
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            ComponentName name = new ComponentName(context, ShieldWidget.class);
            int[] ids = manager.getAppWidgetIds(name);
            for (int id : ids) update(context, manager, id);
        } catch (Exception ignored) { }
    }

    private static void update(Context context, AppWidgetManager manager, int id) {
        Prefs prefs = Prefs.load(context);
        boolean running = DnsVpnService.running;
        boolean blocking = running && prefs.blockingActive();
        String status;
        String sub;
        if (running && blocking) {
            status = "Protected";
            sub = Stats.blocked() + " blocked today";
        } else if (running && prefs.scheduleEnabled && !prefs.scheduleActiveNow()) {
            status = "Outside hours";
            sub = "Blocking resumes at " + Prefs.timeLabel(prefs.scheduleStart);
        } else if (running) {
            status = "Paused";
            sub = "Tap Resume to block again";
        } else {
            status = "Protection off";
            sub = "Tap to turn on";
        }

        RemoteViews views = new RemoteViews(context.getPackageName(),
                R.layout.shield_widget);
        views.setTextViewText(R.id.widgetStatus, status);
        views.setTextViewText(R.id.widgetSub, sub);
        views.setTextViewText(R.id.widgetPause, blocking ? "Pause 30 min" : "Resume");

        Intent open = new Intent(context, MainActivity.class);
        views.setOnClickPendingIntent(R.id.widgetOpen, PendingIntent.getActivity(
                context, 11, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

        Intent pause = new Intent(context, ShieldWidget.class)
                .setAction(blocking ? ACTION_PAUSE : ACTION_TOGGLE);
        views.setOnClickPendingIntent(R.id.widgetPause, PendingIntent.getBroadcast(
                context, 12, pause,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

        manager.updateAppWidget(id, views);
    }
}
