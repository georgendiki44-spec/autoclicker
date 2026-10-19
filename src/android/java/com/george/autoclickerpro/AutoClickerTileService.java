package com.george.autoclickerpro;

import android.app.PendingIntent;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile that shows or hides the floating controls. */
public class AutoClickerTileService extends TileService {
    @Override public void onClick() {
        super.onClick();
        if (FloatingOverlayService.active) {
            FloatingOverlayService.shutdown(this);
            setTileState(false);
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            } else {
                startActivityAndCollapse(intent);
            }
            return;
        }
        FloatingOverlayService.launch(this);
        setTileState(true);
    }

    @Override public void onStartListening() {
        super.onStartListening();
        setTileState(FloatingOverlayService.active);
    }

    private void setTileState(boolean on) {
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setLabel("Auto Clicker");
        tile.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }
}
