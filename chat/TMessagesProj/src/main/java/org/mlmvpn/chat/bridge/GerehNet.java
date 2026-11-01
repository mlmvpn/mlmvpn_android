package org.mlmvpn.chat.bridge;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.util.Log;

import org.mlmchat.core.ChatNode;
import org.mlmchat.core.NetStatus;
import org.mlmchat.core.VolunteerCheck;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MLMCHAT: the phone's place in the Gereh network (SPEC 15.10 to 15.12): the volunteer role and
 * «inside Iran only».
 *
 * <p>The volunteer role is the user's choice (PLAN 11), and it runs only where it can help and
 * costs little: on Wi-Fi (an operator never lets anyone reach a phone on its data network,
 * measured in PHASE3 §5) and with the phone charging or above 30%. Anywhere else it pauses by
 * itself and comes back when the conditions return. Turning it on or off restarts only the p2p
 * transport; the LAN path and the queue go on.
 *
 * <p>«Inside Iran only» limits every connection, both ways, to Iran's IPv4 ranges (MLMVPN's own
 * list, refreshed daily for its Smart Iran route) plus the operators' NAT space, and turns IPv6
 * off: what is left when the international internet is cut. The local network always works.
 *
 * <p>When Gereh opens on a Wi-Fi where the phone could be a volunteer and the user has not chosen
 * yet, it asks once ({@link #maybeSuggest}); a «later» is not asked again for a week on that network.
 */
public final class GerehNet {
    private static final String TAG = "MLMCHAT";
    private static final String PREFS = "gereh_net";
    private static final String VOLUNTEER = "volunteer";
    private static final String DOMESTIC = "domestic";
    private static final String VOL_PORT = "vol_port";
    /** Also on mobile data (off unless the user turns it on), at most this much a day. */
    private static final String VOLUNTEER_CELL = "volunteer_cell";
    private static final long CELL_CAP_BYTES = 100L * 1024 * 1024;
    private static final int DEFAULT_VOL_PORT = 40443;
    private static final int BATTERY_MIN = 30;
    private static final long ASK_AGAIN_MS = 7L * 24 * 3600 * 1000;
    private static final long CHECK_AGAIN_MS = 24L * 3600 * 1000;
    /** Iran's ranges: MLMVPN's Smart Iran list (filesDir copy refreshed daily, else the one in the APK). */
    private static final String IR_CIDR = "smart_iran/ir_cidr.txt";
    /** Hamrah-e Aval and Irancell number their carrier NAT here (PHASE3 §2): Iranian, though not on the list. */
    private static final String OPERATOR_NAT = "22.0.0.0/8";

    /** Whether the p2p transport runs as a volunteer now. */
    private static volatile boolean running;
    private static volatile boolean started;
    /** Why the chosen volunteer role is paused: "cell", "battery", or "". */
    private static volatile String paused = "";
    private static volatile boolean checking;
    private static boolean retryPosted;
    private static final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "gereh-net");
        t.setDaemon(true);
        return t;
    });

    private GerehNet() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---- the user's choices ----------------------------------------------------------------

    public static boolean volunteerChosen() {
        return prefs().getBoolean(VOLUNTEER, false);
    }

    public static void setVolunteer(boolean on) {
        prefs().edit().putBoolean(VOLUNTEER, on).apply();
        ChatNode node = GerehIdentity.node();
        if (node != null) {
            worker.execute(() -> apply(node));
        }
    }

    public static boolean domestic() {
        return prefs().getBoolean(DOMESTIC, false);
    }

    public static void setDomestic(boolean on) {
        prefs().edit().putBoolean(DOMESTIC, on).apply();
        ChatNode node = GerehIdentity.node();
        if (node != null) {
            worker.execute(() -> applyScope(node));
        }
    }

    /** Volunteer on mobile data too (SPEC 15.11): only a peer on another network proves it works there. */
    public static boolean volunteerOnCell() {
        return prefs().getBoolean(VOLUNTEER_CELL, false);
    }

    public static void setVolunteerOnCell(boolean on) {
        prefs().edit().putBoolean(VOLUNTEER_CELL, on).apply();
        ChatNode node = GerehIdentity.node();
        if (node != null) {
            worker.execute(() -> apply(node));
        }
    }

    /** "cell", "cap" (today's mobile-data limit reached) or "battery" while the chosen role is paused, else "". */
    public static String paused() {
        return paused;
    }

    public static boolean volunteerRunning() {
        return running;
    }

    // ---- the transport ---------------------------------------------------------------------

    /** Starts the p2p transport as the choices and conditions say (from the core's thread, once). */
    static void start(ChatNode node) {
        applyScope(node);
        boolean want = wantVolunteer(node);
        try {
            NetStatus st = node.startP2p(want ? volunteerPort() : 0, Collections.emptyList(), want);
            running = want;
            started = true;
            Log.i(TAG, "p2p up, volunteer " + want + ", volunteers known " + st.getVolunteersKnown());
        } catch (Throwable e) {
            Log.e(TAG, "p2p failed: " + e.getMessage());
        }
        // public Nostr relays (SPEC 20, T52): a way to a contact on another operator, at once
        try {
            node.nostrStart("{}");
            Log.i(TAG, "nostr relays up");
        } catch (Throwable e) {
            Log.e(TAG, "nostr relays failed: " + e.getMessage());
        }
        // the Google relays (SPEC 19): the last way to a contact on another operator
        GerehGas.start(node);
    }

    /** The network or the battery changed: start or pause the volunteer role if it should. */
    static void conditionsChanged(ChatNode node) {
        worker.execute(() -> {
            apply(node);
            // Bluetooth may have been turned on since (or off)
            applyNearby(node);
        });
    }

    private static synchronized void apply(ChatNode node) {
        if (!started) {
            return;
        }
        boolean want = wantVolunteer(node);
        if (want == running) {
            return;
        }
        try {
            node.stopP2p();
            node.startP2p(want ? volunteerPort() : 0, Collections.emptyList(), want);
            running = want;
            Log.i(TAG, "volunteer " + (want ? "on" : "off") + (paused.isEmpty() ? "" : " (paused: " + paused + ")"));
        } catch (Throwable e) {
            Log.e(TAG, "p2p restart failed: " + e.getMessage());
        }
    }

    private static void applyScope(ChatNode node) {
        try {
            int n = node.setScope(domestic() ? iranRanges() : Collections.emptyList());
            Log.i(TAG, "scope: " + (n == 0 ? "everywhere" : n + " ranges, IPv4 only"));
        } catch (Throwable e) {
            Log.e(TAG, "scope failed: " + e.getMessage());
        }
    }

    private static int volunteerPort() {
        return prefs().getInt(VOL_PORT, DEFAULT_VOL_PORT);
    }

    /**
     * The user chose the role, and the phone is on Wi-Fi (or on mobile data, if the user allowed
     * that, within today's limit) with enough battery.
     */
    private static boolean wantVolunteer(ChatNode node) {
        if (!volunteerChosen()) {
            paused = "";
            return false;
        }
        if (!onWifi()) {
            if (!volunteerOnCell()) {
                paused = "cell";
                return false;
            }
            if (cellBytesToday(node) >= CELL_CAP_BYTES) {
                paused = "cap";
                return false;
            }
        }
        if (!batteryOk()) {
            paused = "battery";
            return false;
        }
        paused = "";
        return true;
    }

    /**
     * What the role moved today, from the kept total: its value at the first look of the day is
     * the base. Counted on Wi-Fi too, so the limit is on the safe side.
     */
    private static long cellBytesToday(ChatNode node) {
        long total;
        try {
            total = node.relayStatus().getBytesTotal();
        } catch (Throwable e) {
            return 0;
        }
        String today = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(new java.util.Date());
        SharedPreferences p = prefs();
        if (!today.equals(p.getString("cap_day", ""))) {
            p.edit().putString("cap_day", today).putLong("cap_base", total).apply();
            return 0;
        }
        return Math.max(0, total - p.getLong("cap_base", total));
    }

    static boolean onWifi() {
        ConnectivityManager cm = (ConnectivityManager) ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return false;
        }
        Network n = cm.getActiveNetwork();
        NetworkCapabilities caps = n == null ? null : cm.getNetworkCapabilities(n);
        return caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
    }

    static boolean batteryOk() {
        Intent b = ApplicationLoader.applicationContext.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null) {
            return true;
        }
        int plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        return plugged != 0 || level < 0 || scale <= 0 || level * 100 / scale >= BATTERY_MIN;
    }

    /** Iran's IPv4 ranges and the operators' NAT space; no IPv6 (it is off in a shutdown). */
    static List<String> iranRanges() {
        List<String> out = new ArrayList<>();
        Context ctx = ApplicationLoader.applicationContext;
        File fresh = new File(ctx.getFilesDir(), IR_CIDR);
        try (InputStream in = fresh.length() > 10_000 ? new FileInputStream(fresh) : ctx.getAssets().open(IR_CIDR);
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#") && line.indexOf(':') < 0) {
                    out.add(line);
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "no Iran ranges: " + e.getClass().getSimpleName());
        }
        if (!out.isEmpty()) {
            out.add(OPERATOR_NAT);
        }
        return out;
    }

    // ---- «suitable for a volunteer?» ------------------------------------------------------

    /** The check's verdict as one sentence for the user (mobile data is answered without asking the router). */
    public static String verdictText(VolunteerCheck c) {
        if (c == null) {
            return LocaleController.getString(R.string.GerehCheckFailed);
        }
        switch (c.getVerdict()) {
            case "suitable": return LocaleController.getString(R.string.GerehCheckSuitable);
            case "same_operator": return LocaleController.getString(R.string.GerehCheckSameOperator);
            case "ipv6_maybe": return LocaleController.getString(R.string.GerehCheckIpv6Maybe);
            case "carrier_nat": return LocaleController.getString(R.string.GerehCheckCarrierNat);
            case "private": return LocaleController.getString(R.string.GerehCheckPrivate);
            case "no_address": return LocaleController.getString(R.string.GerehCheckNoAddress);
            case "outside_scope": return LocaleController.getString(R.string.GerehCheckOutsideScope);
            case "cell": return LocaleController.getString(R.string.GerehCheckCell);
            default: return LocaleController.getString(R.string.GerehCheckNoRouter);
        }
    }

    /**
     * What the user can do by hand on their own modem, for this verdict, with this phone's own
     * addresses and port filled in (Ehsan, 23 Oct 2026: no reports collected; tell the user
     * exactly what is going on and what to do). Shown on this phone only, never logged.
     */
    public static String manualSteps(VolunteerCheck c) {
        String[] mine = myAddresses();
        String lan = mine[0].isEmpty() ? "—" : mine[0];
        String v6 = mine[1].isEmpty() ? "—" : mine[1];
        // Latin digits: the user types these into the modem's settings
        String port = String.valueOf(volunteerPort());
        switch (c.getVerdict()) {
            case "suitable": return LocaleController.getString(R.string.GerehStepsSuitable);
            case "same_operator": return LocaleController.formatString(R.string.GerehStepsSameOperator, v6, port);
            case "ipv6_maybe": return LocaleController.formatString(R.string.GerehStepsIpv6, v6, port);
            case "private": return LocaleController.formatString(R.string.GerehStepsPrivate, lan, port);
            case "outside_scope": return LocaleController.getString(R.string.GerehStepsOutsideScope);
            case "cell": return LocaleController.getString(R.string.GerehStepsCell);
            default: return LocaleController.formatString(R.string.GerehStepsNoRouter, lan, port);
        }
    }

    /** This phone's own addresses and the volunteer port, to paste into the modem's settings. */
    public static String myAddressesText() {
        String[] mine = myAddresses();
        StringBuilder b = new StringBuilder();
        if (!mine[0].isEmpty()) {
            b.append("IPv4: ").append(mine[0]).append('\n');
        }
        if (!mine[1].isEmpty()) {
            b.append("IPv6: ").append(mine[1]).append("/128\n");
        }
        return b.append("TCP: ").append(volunteerPort()).toString();
    }

    /** [the LAN IPv4, a global IPv6] of the network in use; "" for one it does not have. */
    private static String[] myAddresses() {
        String lan = "", v6 = "", v6temp = "";
        try {
            ConnectivityManager cm = (ConnectivityManager) ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm == null ? null : cm.getActiveNetwork();
            android.net.LinkProperties lp = n == null ? null : cm.getLinkProperties(n);
            if (lp != null) {
                for (android.net.LinkAddress la : lp.getLinkAddresses()) {
                    java.net.InetAddress a = la.getAddress();
                    if (a instanceof java.net.Inet4Address && a.isSiteLocalAddress() && lan.isEmpty()) {
                        lan = a.getHostAddress();
                    } else if (a instanceof java.net.Inet6Address && (a.getAddress()[0] & 0xe0) == 0x20 && v6.isEmpty()) {
                        // a global address; the stable one (not a temporary privacy address) when Android says which
                        if ((la.getFlags() & 0x01 /* IFA_F_TEMPORARY */) == 0) {
                            v6 = a.getHostAddress();
                        } else if (v6temp.isEmpty()) {
                            v6temp = a.getHostAddress();
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return new String[]{lan, v6.isEmpty() ? v6temp : v6};
    }

    /** Runs the check off the main thread; `done` gets the result on the main thread (null if it failed). */
    public static void check(java.util.function.Consumer<VolunteerCheck> done) {
        ChatNode node = GerehIdentity.node();
        if (node == null) {
            done.accept(null);
            return;
        }
        if (!onWifi()) {
            done.accept(new VolunteerCheck("cell", "", "none", false, false));
            return;
        }
        new Thread(() -> {
            VolunteerCheck c = null;
            try {
                c = node.volunteerCheck();
            } catch (Throwable e) {
                Log.w(TAG, "volunteer check failed: " + e.getClass().getSimpleName());
            }
            VolunteerCheck r = c;
            AndroidUtilities.runOnUIThread(() -> done.accept(r));
        }, "gereh-volcheck").start();
    }

    /**
     * Gereh opened: on a Wi-Fi where this phone could be a volunteer, ask once whether to be one:
     * for everyone (a public address), for the same operator's subscribers (an LTE home modem
     * behind the operator's NAT), or for whoever has IPv6. Not on mobile data, not with a low
     * battery, not if the user already chose, and per network at most once a week (asked) or once
     * a day (checked and not worth it).
     */
    public static void maybeSuggest(Activity activity) {
        ChatNode node = GerehIdentity.node();
        if (node == null || checking || volunteerChosen() || !onWifi() || !batteryOk()) {
            return;
        }
        String net;
        try {
            net = node.netStatus().getNetwork();
        } catch (Throwable e) {
            return;
        }
        if (net.isEmpty()) {
            // the core has not named this network yet (Gereh just started): once more shortly
            if (!retryPosted) {
                retryPosted = true;
                AndroidUtilities.runOnUIThread(() -> {
                    retryPosted = false;
                    if (!activity.isFinishing()) {
                        maybeSuggest(activity);
                    }
                }, 5000);
            }
            return;
        }
        SharedPreferences p = prefs();
        long now = System.currentTimeMillis();
        if (now - p.getLong("asked:" + net, 0) < ASK_AGAIN_MS || now - p.getLong("checked:" + net, 0) < CHECK_AGAIN_MS) {
            return;
        }
        checking = true;
        check(c -> {
            checking = false;
            prefs().edit().putLong("checked:" + net, System.currentTimeMillis()).apply();
            if (c == null || !c.getWorthTrying() || activity.isFinishing() || volunteerChosen()) {
                return;
            }
            prefs().edit().putLong("asked:" + net, System.currentTimeMillis()).apply();
            int text = c.getSuitable() ? R.string.GerehSuggestText
                    : "same_operator".equals(c.getVerdict()) ? R.string.GerehSuggestTextSameOperator : R.string.GerehSuggestTextIpv6;
            new AlertDialog.Builder(activity)
                    .setTitle(LocaleController.getString(R.string.GerehSuggestTitle))
                    .setMessage(LocaleController.getString(text))
                    .setPositiveButton(LocaleController.getString(R.string.GerehSuggestOn), (d, w) -> setVolunteer(true))
                    .setNegativeButton(LocaleController.getString(R.string.GerehSuggestLater), null)
                    .show();
        });
    }

    // ---- «nearby»: Bluetooth LE (SPEC 16) -------------------------------------------------------

    private static final String NEARBY = "nearby";
    private static volatile org.mlmchat.core.ble.BleRadio radio;
    /** Gereh on screen (Telegram's account.updateStatus): the Bluetooth scan and the LAN beacon lock only then. */
    private static volatile boolean front;
    private static volatile android.net.wifi.WifiManager.MulticastLock beaconLock;

    /** The LAN beacon lock (Messenger), held only while Gereh is on screen. */
    static void beaconLock(android.net.wifi.WifiManager.MulticastLock lock) {
        beaconLock = lock;
        applyFront();
    }

    /**
     * Gereh came to the screen or left it. Off screen the Bluetooth scan stops (advertising and the
     * server stay, so a phone on screen still finds this one) and the LAN beacon lock goes: both
     * kept phones awake all day (users of 1.2.44, 30 Oct 2026).
     */
    static void setFront(boolean on) {
        if (front == on) {
            return;
        }
        front = on;
        applyFront();
    }

    private static void applyFront() {
        org.mlmchat.core.ble.BleRadio r = radio;
        if (r != null) {
            r.setScanning(front || pairing);
        }
        android.net.wifi.WifiManager.MulticastLock l = beaconLock;
        if (l != null) {
            try {
                if (front && !l.isHeld()) {
                    l.acquire();
                } else if (!front && l.isHeld()) {
                    l.release();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    public static boolean nearbyChosen() {
        android.content.SharedPreferences p = prefs();
        // once (1.2.45): Bluetooth starts off for everyone, now that its switch is in the profile;
        // whoever wants it turns it on there again (Ehsan, 30 Oct 2026)
        if (!p.getBoolean("nearby_reset_1245", false)) {
            p.edit().putBoolean("nearby_reset_1245", true).putBoolean(NEARBY, false).apply();
            return false;
        }
        return p.getBoolean(NEARBY, false);
    }

    /** The user's choice; the radio starts once Bluetooth is on and the permissions are given. */
    public static void setNearby(boolean on) {
        prefs().edit().putBoolean(NEARBY, on).apply();
        ChatNode node = GerehIdentity.node();
        if (node != null) {
            worker.execute(() -> applyNearby(node));
        }
    }

    /**
     * Start or stop the radio as the choice and the phone's state say (start, settings, resume).
     * «Add nearby» keeps it on while its screen is open, even with «nearby» off.
     */
    public static synchronized void applyNearby(ChatNode node) {
        Context ctx = ApplicationLoader.applicationContext;
        boolean want = (nearbyChosen() || pairing) && org.mlmchat.core.ble.BleRadio.Companion.hasPermissions(ctx);
        org.mlmchat.core.ble.BleRadio r = radio;
        if (want && r == null) {
            r = new org.mlmchat.core.ble.BleRadio(ctx, node);
            if (r.start()) {
                radio = r;
                r.setScanning(front || pairing);
            }
        } else if (!want && r != null) {
            r.stop();
            radio = null;
        }
    }

    /** "on", "off", "no_permission", "bt_off" or "location_off" (Android 11 and older scan only with Location on). */
    public static String nearbyState() {
        if (!nearbyChosen()) {
            return "off";
        }
        Context ctx = ApplicationLoader.applicationContext;
        if (!org.mlmchat.core.ble.BleRadio.Companion.hasPermissions(ctx)) {
            return "no_permission";
        }
        android.bluetooth.BluetoothManager bm = (android.bluetooth.BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm == null || bm.getAdapter() == null || !bm.getAdapter().isEnabled()) {
            return "bt_off";
        }
        if (android.os.Build.VERSION.SDK_INT < 31) {
            android.location.LocationManager lm = (android.location.LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            boolean located = lm != null && (android.os.Build.VERSION.SDK_INT >= 28 ? lm.isLocationEnabled()
                    : lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER));
            if (!located) {
                return "location_off";
            }
        }
        return radio != null ? "on" : "bt_off";
    }

    /** Contacts beside this phone right now. */
    public static int nearbyCount() {
        org.mlmchat.core.ble.BleRadio r = radio;
        return r == null ? 0 : r.nearby();
    }

    // ---- «add nearby» (SPEC 16.4, T36) -----------------------------------------------------------

    private static final String PAIR_NAME = "pair_name";
    private static volatile boolean pairing;

    /** The name shown on «add nearby»: the last one typed there, else the Gereh name without its digits. */
    public static String pairName() {
        String n = prefs().getString(PAIR_NAME, "");
        if (!n.isEmpty()) {
            return n;
        }
        ChatNode node = GerehIdentity.node();
        try {
            return node == null ? "" : node.getMe().getName();
        } catch (Throwable e) {
            return "";
        }
    }

    public static void setPairName(String name) {
        prefs().edit().putString(PAIR_NAME, name).apply();
    }

    /** Open (or reopen) «add nearby» for two minutes: Bluetooth if it can run, Wi-Fi always. */
    public static void startPairing(String name) {
        ChatNode node = GerehIdentity.node();
        if (node == null) {
            return;
        }
        pairing = true;
        applyFront();
        worker.execute(() -> {
            applyNearby(node);
            try {
                node.pairStart(name, true);
            } catch (Throwable e) {
                Log.w(TAG, "pairing: " + e.getClass().getSimpleName());
            }
        });
    }

    public static void stopPairing() {
        ChatNode node = GerehIdentity.node();
        pairing = false;
        applyFront();
        if (node == null) {
            return;
        }
        worker.execute(() -> {
            try {
                node.pairStop();
            } catch (Throwable ignored) {
            }
            applyNearby(node);
        });
    }

    /** Back from the permission dialog: start the radio for «add nearby» if it can now. */
    public static void pairingRadio() {
        ChatNode node = GerehIdentity.node();
        if (node != null && pairing) {
            worker.execute(() -> applyNearby(node));
        }
    }

    /** Whether «add nearby» also searches over Bluetooth now. */
    public static boolean pairingBluetooth() {
        return radio != null;
    }

    // ---- for the profile row -----------------------------------------------------------------

    /** One line under the Gereh name: off; on with how many are connected; or why it is paused. */
    public static String summary() {
        if (!volunteerChosen()) {
            return LocaleController.getString(R.string.GerehRelayOff);
        }
        if (!running && !paused.isEmpty()) {
            return LocaleController.getString("cell".equals(paused) ? R.string.GerehRelayPausedCell
                    : "cap".equals(paused) ? R.string.GerehRelayPausedCap : R.string.GerehRelayPausedBattery);
        }
        int now = 0;
        ChatNode node = GerehIdentity.node();
        try {
            if (node != null) {
                now = node.relayStatus().getNow();
            }
        } catch (Throwable ignored) {
        }
        return LocaleController.formatString(R.string.GerehRelayOnCount, now);
    }
}
