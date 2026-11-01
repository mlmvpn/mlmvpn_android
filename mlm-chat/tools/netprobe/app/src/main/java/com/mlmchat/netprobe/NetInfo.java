package com.mlmchat.netprobe;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.telephony.TelephonyManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** What network this phone is on right now. Never records an address, only kinds and counts. */
final class NetInfo {
    private NetInfo() {}

    static String carrierFa(String mccmnc, String fallback) {
        if (mccmnc != null) {
            switch (mccmnc) {
                case "43211": return "همراه اول";
                case "43235": return "ایرانسل";
                case "43220": return "رایتل";
                case "43232": return "تالیا";
                case "43214": return "کیش";
                case "43219": return "اسپادان";
                default: break;
            }
        }
        return (fallback == null || fallback.isEmpty()) ? "نامعلوم" : fallback;
    }

    static boolean isGlobalV6(InetAddress a) {
        if (!(a instanceof Inet6Address)) return false;
        byte b0 = a.getAddress()[0];
        return (b0 & 0xe0) == 0x20;
    }

    private static JSONObject describe(ConnectivityManager cm, Network n) throws Exception {
        JSONObject o = new JSONObject();
        NetworkCapabilities nc = cm.getNetworkCapabilities(n);
        LinkProperties lp = cm.getLinkProperties(n);
        if (nc != null) {
            String t = nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ? "vpn"
                    : nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "wifi"
                    : nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "cellular"
                    : nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "ethernet"
                    : nc.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) ? "bluetooth" : "other";
            o.put("transport", t);
            o.put("validated", nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
            o.put("metered", !nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
            o.put("down_kbps", nc.getLinkDownstreamBandwidthKbps());
            o.put("up_kbps", nc.getLinkUpstreamBandwidthKbps());
        }
        if (lp != null) {
            int v4 = 0, v6g = 0, v6other = 0;
            for (LinkAddress la : lp.getLinkAddresses()) {
                InetAddress a = la.getAddress();
                if (a instanceof Inet6Address) {
                    if (isGlobalV6(a)) v6g++; else v6other++;
                } else {
                    v4++;
                }
            }
            o.put("ipv4_addresses", v4);
            o.put("ipv6_global", v6g);
            o.put("ipv6_other", v6other);
            o.put("interface", lp.getInterfaceName());
            if (Build.VERSION.SDK_INT >= 29) o.put("mtu", lp.getMtu());
            if (Build.VERSION.SDK_INT >= 28) o.put("private_dns", lp.isPrivateDnsActive());
        }
        return o;
    }

    static JSONObject snapshot(Context c, String label) {
        JSONObject o = new JSONObject();
        try {
            o.put("time", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(new Date()));
            o.put("label", label == null ? "" : label);
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network active = cm.getActiveNetwork();
            boolean vpn = false;
            JSONArray all = new JSONArray();
            for (Network n : cm.getAllNetworks()) {
                JSONObject d = describe(cm, n);
                if ("vpn".equals(d.optString("transport"))) vpn = true;
                d.put("active", n.equals(active));
                all.put(d);
            }
            o.put("networks", all);
            o.put("active", active == null ? JSONObject.NULL : describe(cm, active));
            o.put("vpn_on", vpn);
            TelephonyManager tm = (TelephonyManager) c.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm != null) {
                String mccmnc = tm.getNetworkOperator();
                o.put("mccmnc", mccmnc);
                o.put("sim_mccmnc", tm.getSimOperator());
                o.put("carrier", carrierFa(mccmnc, tm.getNetworkOperatorName()));
                o.put("sim_carrier", carrierFa(tm.getSimOperator(), tm.getSimOperatorName()));
                o.put("roaming", tm.isNetworkRoaming());
            }
        } catch (Exception e) {
            try { o.put("error", String.valueOf(e)); } catch (Exception ignored) { }
        }
        return o;
    }

    static JSONObject device() {
        JSONObject o = new JSONObject();
        try {
            o.put("maker", Build.MANUFACTURER);
            o.put("model", Build.MODEL);
            o.put("sdk", Build.VERSION.SDK_INT);
            o.put("android", Build.VERSION.RELEASE);
            o.put("abi", Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "");
        } catch (Exception ignored) { }
        return o;
    }

    /** One line for the top of the screen. */
    static String lineFa(JSONObject s) {
        JSONObject a = s.optJSONObject("active");
        String t = a == null ? "بی‌شبکه" : a.optString("transport");
        String kind;
        switch (t) {
            case "wifi": kind = "وای‌فای"; break;
            case "cellular": kind = "دادهٔ همراه"; break;
            case "vpn": kind = "VPN"; break;
            case "ethernet": kind = "اترنت"; break;
            case "بی‌شبکه": kind = t; break;
            default: kind = t;
        }
        int v6 = 0;
        JSONArray all = s.optJSONArray("networks");
        if (all != null) for (int i = 0; i < all.length(); i++) v6 = Math.max(v6, all.optJSONObject(i).optInt("ipv6_global"));
        String label = s.optString("label");
        return (label.isEmpty() ? "" : label + " · ") + s.optString("carrier", "?") + " · " + kind
                + " · IPv6 سراسری: " + (v6 > 0 ? "دارد" : "ندارد")
                + " · VPN: " + (s.optBoolean("vpn_on") ? "روشن" : "خاموش");
    }
}
