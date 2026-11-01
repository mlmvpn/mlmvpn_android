package org.mlmvpn.chat.bridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * MLMCHAT: after the phone starts (or the app is updated), Gereh listens again without waiting
 * for someone to open the app; boot is one of the moments Android lets a foreground service start.
 */
public class GerehBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent != null ? intent.getAction() : null;
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            GerehService.startIfReady(context);
        }
    }
}
