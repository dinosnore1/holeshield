package com.dino.holeshield;

import android.content.Intent;
import android.net.VpnService;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile that mirrors the protection state. */
public class ShieldTileService extends TileService {

    @Override public void onStartListening() {
        super.onStartListening();
        update();
    }

    @Override public void onClick() {
        super.onClick();
        Prefs prefs = Prefs.load(this);
        if (DnsVpnService.running && !prefs.blockingActive()) {
            Intent service = new Intent(this, DnsVpnService.class);
            service.setAction(DnsVpnService.ACTION_RESUME);
            startService(service);
        } else if (DnsVpnService.running) {
            Intent service = new Intent(this, DnsVpnService.class);
            service.setAction(DnsVpnService.ACTION_STOP);
            startService(service);
        } else if (VpnService.prepare(this) != null) {
            Intent open = new Intent(this, MainActivity.class)
                    .putExtra("start_protection", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivityAndCollapse(open);
        } else {
            prefs.protectionWanted = true;
            prefs.save(this);
            startForegroundService(new Intent(this, DnsVpnService.class));
        }
        update();
    }

    private void update() {
        Tile tile = getQsTile();
        if (tile == null) return;
        if (DnsVpnService.running) {
            Prefs prefs = Prefs.load(this);
            tile.setState(prefs.blockingActive() ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            tile.setLabel(prefs.blockingActive() ? "Hole Shield" : "Hole Shield paused");
        } else if (DnsVpnService.starting) {
            tile.setState(Tile.STATE_UNAVAILABLE);
            tile.setLabel("Hole Shield starting");
        } else {
            tile.setState(Tile.STATE_INACTIVE);
            tile.setLabel("Hole Shield off");
        }
        tile.updateTile();
    }
}
