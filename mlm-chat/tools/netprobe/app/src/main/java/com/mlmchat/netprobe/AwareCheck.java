package com.mlmchat.netprobe;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.net.wifi.aware.Characteristics;
import android.net.wifi.aware.WifiAwareManager;
import android.os.Build;

import org.json.JSONObject;

/** Does this phone support Wi-Fi Aware? Reads what the system reports; sends nothing. */
final class AwareCheck {
    private AwareCheck() {}

    static JSONObject check(Context c) {
        JSONObject o = new JSONObject();
        try {
            o.put("sdk", Build.VERSION.SDK_INT);
            WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            o.put("wifi_on", wm != null && wm.isWifiEnabled());
            if (Build.VERSION.SDK_INT < 26) {
                o.put("feature", false);
                o.put("why", "Android older than 8.0");
                return o;
            }
            boolean feature = c.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE);
            o.put("feature", feature);
            if (!feature) return o;
            WifiAwareManager m = (WifiAwareManager) c.getSystemService(Context.WIFI_AWARE_SERVICE);
            if (m == null) {
                o.put("available", false);
                return o;
            }
            // Unavailable while Wi-Fi is off, and on some phones while a hotspot or location is off.
            o.put("available", m.isAvailable());
            Characteristics ch = m.getCharacteristics();
            if (ch != null) {
                o.put("max_service_name", ch.getMaxServiceNameLength());
                o.put("max_service_info", ch.getMaxServiceSpecificInfoLength());
                o.put("max_match_filter", ch.getMaxMatchFilterLength());
                if (Build.VERSION.SDK_INT >= 30) o.put("cipher_suites", ch.getSupportedCipherSuites());
                if (Build.VERSION.SDK_INT >= 31) o.put("instant_mode", ch.isInstantCommunicationModeSupported());
                if (Build.VERSION.SDK_INT >= 33) {
                    o.put("data_paths", ch.getNumberOfSupportedDataPaths());
                    o.put("publish_sessions", ch.getNumberOfSupportedPublishSessions());
                    o.put("subscribe_sessions", ch.getNumberOfSupportedSubscribeSessions());
                }
            }
        } catch (Throwable t) {
            try { o.put("error", String.valueOf(t)); } catch (Exception ignored) { }
        }
        return o;
    }
}
