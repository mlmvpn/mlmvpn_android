package com.mlmchat.netprobe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.IBinder;
import android.os.SystemClock;

import com.mlmchat.spike.Spike;

import org.json.JSONObject;

/**
 * Battery test (PLAN 17.1): one hour of a foreground service that keeps a live libp2p connection
 * (with a relay reservation) to the home observer, the way MLM CHAT would wait for messages.
 * No wake lock: the phone sleeps as it would for a real chat app. The battery level and charge
 * counter are read before and after; the result goes into the report.
 */
public class BatteryService extends Service {
    static final String EXTRA_ARGS = "args";
    static final String EXTRA_LABEL = "label";
    static volatile boolean running;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static JSONObject battery(android.content.Context c) {
        JSONObject o = new JSONObject();
        try {
            BatteryManager bm = (BatteryManager) c.getSystemService(BATTERY_SERVICE);
            Intent st = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            o.put("percent", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY));
            int uah = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
            if (uah > 0) o.put("charge_uah", uah);
            o.put("plugged", st != null && st.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0);
            if (st != null) o.put("temp_c", st.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0);
        } catch (Exception ignored) {
        }
        return o;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (running || intent == null) return START_NOT_STICKY;
        running = true;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("battery", "آزمون باتری", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, "battery")
                .setSmallIcon(R.drawable.ic_probe)
                .setContentTitle("آزمون باتری netprobe")
                .setContentText("یک ساعت اتصال زنده به ناظر؛ گوشی را از شارژ جدا نگه دارید")
                .setOngoing(true)
                .build();
        startForeground(1, n);
        final String args = intent.getStringExtra(EXTRA_ARGS);
        final String label = intent.getStringExtra(EXTRA_LABEL);
        new Thread(() -> {
            JSONObject before = battery(this);
            long t = SystemClock.elapsedRealtime();
            JSONObject r;
            try {
                r = new JSONObject(Spike.run(args));
            } catch (Throwable e) {
                r = new JSONObject();
                try {
                    r.put("ok", false);
                    r.put("error", String.valueOf(e));
                } catch (Exception ignored) {
                }
            }
            JSONObject after = battery(this);
            try {
                r.put("battery_before", before);
                r.put("battery_after", after);
                double hours = (SystemClock.elapsedRealtime() - t) / 3_600_000.0;
                r.put("hours", Math.round(hours * 100) / 100.0);
                if (before.has("charge_uah") && after.has("charge_uah") && hours > 0) {
                    r.put("drain_mah_per_hour", Math.round((before.getInt("charge_uah") - after.getInt("charge_uah")) / 1000.0 / hours * 10) / 10.0);
                }
                r.put("percent_used", before.optInt("percent") - after.optInt("percent"));
                if (before.optBoolean("plugged") || after.optBoolean("plugged")) r.put("warning", "phone was charging: battery numbers are not valid");
            } catch (Exception ignored) {
            }
            Report.get(this).add("battery_hold", NetInfo.snapshot(this, label == null ? "" : label), r);
            android.util.Log.i("netprobe", "battery_hold " + r);
            running = false;
            stopForeground(true);
            stopSelf();
        }, "battery-test").start();
        return START_NOT_STICKY;
    }
}
