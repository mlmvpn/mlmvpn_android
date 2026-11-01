package com.mlmchat.netprobe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.mlmchat.spike.PqTls;
import com.mlmchat.spike.Spike;
import com.mlmchat.spike.SpikeIroh;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * netprobe: MLM CHAT phase-0 measurements on a real phone (docs/PHASE0-RESULTS.fa.md says which
 * buttons to press, in which order, on which networks). One screen, top to bottom:
 * observers, one-phone tests, two-phone tests, Wi-Fi Aware, report.
 */
public class MainActivity extends Activity {
    private static final int REQ_SCAN_OBSERVER = 11;
    private static final int REQ_SCAN_PEER = 12;
    private static final int REQ_BLE = 13;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean busy;
    private SharedPreferences prefs;
    private Report report;

    private TextView netLine, observersView, myCardView, peerView, status, result, log;
    private EditText labelEdit;
    /** Static: the native session lives as long as the process, not as long as this screen. */
    private static String myCard;

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(LinearLayout parent, String s, float sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(0xFF202020);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(4), 0, dp(4));
        t.setTextDirection(View.TEXT_DIRECTION_RTL);
        parent.addView(t);
        return t;
    }

    private void section(LinearLayout root, String title) {
        TextView t = text(root, title, 17, true);
        t.setTextColor(0xFF1E5BB8);
        t.setPadding(0, dp(18), 0, dp(4));
    }

    private void button(LinearLayout parent, String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        parent.addView(b, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("netprobe", MODE_PRIVATE);
        report = Report.get(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.WHITE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setPadding(dp(16), dp(16), dp(16), dp(32));
        scroll.addView(root);

        text(root, "سنجش شبکهٔ MLM CHAT · فاز ۰", 20, true);
        text(root, "ابزار آزمایشی جدا از MLMVPN. همهٔ ترافیک آزمون رمزشده است (Noise تا ناظر، TLS 1.3 و QUIC بین دو گوشی). گزارش هیچ نشانی IP ندارد.", 13, false);
        netLine = text(root, "", 14, true);
        labelEdit = new EditText(this);
        labelEdit.setHint("برچسب شبکه، مثلاً «همراه اول تهران» یا «وای‌فای خانه مخابرات»");
        labelEdit.setInputType(InputType.TYPE_CLASS_TEXT);
        labelEdit.setText(prefs.getString("label", ""));
        root.addView(labelEdit);

        section(root, "۱. ناظرها (رایانه‌های خانگی)");
        observersView = text(root, "", 14, false);
        button(root, "خواندن کارت ناظر با دوربین", v -> scan(REQ_SCAN_OBSERVER));
        button(root, "چسباندن کارت ناظر", v -> paste("کارت ناظر (mlmobs1:…)", this::addObserver));
        button(root, "پاک کردن همهٔ ناظرها", v -> {
            prefs.edit().remove("observers").apply();
            refresh();
        });

        section(root, "۲. آزمون‌های یک گوشی");
        button(root, "رفتار NAT، IPv6 و دسترسی به ناظر", v -> runNative("nat", obsJson(new JSONObject()), 0));
        button(root, "سرعت و تأخیر تا ناظر (۱ مگابایت)", v -> runNative("obs_speed", obsJson(new JSONObject()), 0));
        button(root, "عمر نگاشت UDP بی‌کار (حدود ۵ دقیقه)", v -> runNative("nat_lifetime", obsJson(put(new JSONObject(), "max_s", 300)), 0));
        button(root, "عمر اتصال TCP بی‌کار (حدود ۲۰ دقیقه)", v -> runNative("nat_lifetime", obsJson(put(put(new JSONObject(), "max_s", 1200), "tcp", true)), 0));
        button(root, "UPnP مودم (فقط روی وای‌فای خانه)", v -> confirmUpnp());

        section(root, "۳. آزمون دو گوشی");
        text(root, "هر دو گوشی «ساختن کارت من» را بزنند، کارت همدیگر را بخوانند، و بعد هر آزمون را روی هر دو گوشی با فاصلهٔ کمتر از ۱۰ ثانیه شروع کنند.", 13, false);
        myCardView = text(root, "", 13, false);
        button(root, "ساختن کارت من", v -> makeCard());
        button(root, "نشان دادن دوبارهٔ کارت من", v -> showCard());
        peerView = text(root, "", 13, false);
        button(root, "خواندن کارت گوشی دیگر با دوربین", v -> scan(REQ_SCAN_PEER));
        button(root, "چسباندن کارت گوشی دیگر", v -> paste("کارت گوشی دیگر (mlmnp1:…)", this::setPeer));
        button(root, "اتصال مستقیم (۴۰ ثانیه)", v -> runPeer("peer", 0));
        button(root, "سوراخ‌زنی با معرفی ناظر", v -> runPeer("punch", 0));
        button(root, "از راه ناظر (رله)", v -> runPeer("relay", 0));

        section(root, "۴. گفت‌وگوی دو گوشی با libp2p و iroh");
        text(root, "کارت تازهٔ ناظر لازم است (ناظر نسخهٔ جدید). کارت گوشی دیگر لازم نیست: دو گوشی همدیگر را از راه ناظر پیدا می‌کنند. هر دکمه را روی هر دو گوشی با فاصلهٔ کمتر از ۳۰ ثانیه بزنید. حداکثر ۲ دقیقه.", 13, false);
        button(root, "libp2p: گفت‌وگو از راه ناظر", v -> runChat("p2p_chat"));
        button(root, "iroh: گفت‌وگو از راه ناظر", v -> runChat("iroh_chat"));

        section(root, "۵. نزدیک، بی‌اینترنت، و باتری");
        button(root, "پشتیبانی وای‌فای آگاه", v -> awareCheck());
        text(root, "بلوتوث: کارت‌ها مثل بخش ۳ ساخته و خوانده شده باشند. دکمه را روی هر دو گوشی بزنید. بعد از سنجش سرعت، «حالت برد» شروع می‌شود: با یک گوشی آهسته دور شوید و فاصله را یادداشت کنید. «توقف آزمون» حالت برد را تمام می‌کند (حداکثر ۵ دقیقه).", 13, false);
        button(root, "بلوتوث: سرعت و برد", v -> startBle());
        text(root, "باتری: یک ساعت اتصال زنده به ناظر، مثل چتی که در پس‌زمینه منتظر پیام است. گوشی را از شارژ جدا کنید و صفحه را خاموش کنید.", 13, false);
        button(root, "باتری: یک ساعت اتصال زنده (libp2p)", v -> confirmBattery(3600));

        section(root, "۶. هزینهٔ رمزنگاری پساکوانتومی");
        text(root, "۱۰۰ دست‌دادن TLS 1.3 روی خود گوشی، بی‌اینترنت: یک‌بار با X25519 و یک‌بار با X25519MLKEM768. حدود یک دقیقه.", 13, false);
        button(root, "هزینهٔ TLS پساکوانتومی", v -> runPqTls(100));

        section(root, "۷. گزارش");
        button(root, "نمایش گزارش", v -> showReport());
        button(root, "فرستادن گزارش", v -> shareReport());
        button(root, "ذخیرهٔ گزارش در پوشهٔ برنامه", v -> exportReport());
        button(root, "رونوشت گزارش", v -> copy(report.text()));
        button(root, "شروع گزارش تازه", v -> new AlertDialog.Builder(this)
                .setMessage("همهٔ نتیجه‌های ثبت‌شده پاک شود؟")
                .setPositiveButton("پاک شود", (d, w) -> {
                    report.reset();
                    refresh();
                })
                .setNegativeButton("نه", null).show());

        section(root, "وضعیت");
        status = text(root, "", 14, true);
        button(root, "توقف آزمون", v -> {
            if (Native.LOADED) Native.cancel();
            if (Spike.LOADED) Spike.cancel();
            if (SpikeIroh.LOADED) SpikeIroh.cancel();
            if (ble != null) ble.stop();
        });
        result = text(root, "", 14, false);
        result.setTextIsSelectable(true);
        log = text(root, "", 11, false);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextDirection(View.TEXT_DIRECTION_LTR);

        setContentView(scroll);
        if (!Native.LOADED) status.setText("کتابخانهٔ سنجش بار نشد: " + Native.LOAD_ERROR);
        refresh();
        pollLog();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    /**
     * Runs from a computer, the same as pressing the buttons:
     *   adb shell am start -n com.mlmchat.netprobe/.MainActivity --es observer "mlmobs1:..." --es run nat
     * run = nat | obs_speed | upnp | make_card | aware | p2p_chat | iroh_chat | battery | pqtls | ble;
     * --es peer "mlmnp1:..." sets the other phone's card. Each result also goes to logcat (tag
     * "netprobe", no addresses), so it can be read with: adb logcat -s netprobe
     */
    private void handleIntent(Intent i) {
        if (i == null) return;
        String obs = i.getStringExtra("observer");
        if (obs != null) addObserver(obs);
        // The other phone's card, so a test pair can be set up from a computer without the camera.
        String peer = i.getStringExtra("peer");
        if (peer != null) setPeer(peer);
        String run = i.getStringExtra("run");
        i.removeExtra("run");
        i.removeExtra("observer");
        i.removeExtra("peer");
        if (run == null) return;
        OnResult toLog = r -> android.util.Log.i("netprobe", run + " " + r);
        switch (run) {
            case "make_card":
                runNative("make_card", obsJson(new JSONObject()), false, r -> {
                    if (r.optBoolean("ok")) {
                        myCard = r.optString("card");
                        report.add("make_card", NetInfo.snapshot(this, label()), stripCard(r));
                        refresh();
                        // Never into logcat (keys, addresses): into the app's private folder, readable
                        // from a computer only with `adb shell run-as com.mlmchat.netprobe cat files/card.txt`.
                        try (java.io.FileOutputStream f = new java.io.FileOutputStream(new File(getFilesDir(), "card.txt"))) {
                            f.write(myCard.getBytes("UTF-8"));
                        } catch (Exception ignored) {
                        }
                    }
                    toLog.got(stripCard(r));
                });
                break;
            case "ble":
                bleRangeSeconds = i.getIntExtra("range_s", 300);
                startBle();
                break;
            case "aware":
                awareCheck();
                android.util.Log.i("netprobe", "aware " + AwareCheck.check(this));
                break;
            case "p2p_chat":
            case "iroh_chat":
                runChat(run, toLog);
                break;
            case "battery":
                startBattery(i.getIntExtra("seconds", 3600));
                break;
            case "pqtls":
                runPqTls(i.getIntExtra("handshakes", 100));
                break;
            case "nat":
            case "obs_speed":
            case "upnp":
                runNative(run, obsJson(new JSONObject()), true, toLog);
                break;
            default:
                toast("فرمان ناشناخته: " + run);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        prefs.edit().putString("label", labelEdit.getText().toString().trim()).apply();
    }

    // ---- state ----

    private static JSONObject put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (Exception ignored) { }
        return o;
    }

    private JSONArray observers() {
        try {
            return new JSONArray(prefs.getString("observers", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private JSONObject obsJson(JSONObject o) {
        return put(o, "observers", observers());
    }

    private String label() {
        String l = labelEdit.getText().toString().trim();
        prefs.edit().putString("label", l).apply();
        return l;
    }

    private void addObserver(String card) {
        card = card.trim();
        if (!card.startsWith("mlmobs1:")) {
            toast("این کارت ناظر نیست (باید با mlmobs1: شروع شود).");
            return;
        }
        JSONArray a = observers();
        for (int i = 0; i < a.length(); i++) {
            if (card.equals(a.optString(i))) {
                toast("این ناظر قبلاً اضافه شده.");
                return;
            }
        }
        if (a.length() >= 2) {
            toast("دو ناظر کافی است؛ اول ناظرها را پاک کنید.");
            return;
        }
        a.put(card);
        prefs.edit().putString("observers", a.toString()).apply();
        refresh();
    }

    private void setPeer(String card) {
        card = card.trim();
        if (!card.startsWith("mlmnp1:")) {
            toast("این کارت گوشی نیست (باید با mlmnp1: شروع شود).");
            return;
        }
        if (card.equals(myCard)) {
            toast("این کارت خود همین گوشی است.");
            return;
        }
        prefs.edit().putString("peer", card).apply();
        refresh();
    }

    private static String observerName(String card) {
        try {
            String body = card.substring("mlmobs1:".length());
            int flags = android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP;
            JSONObject o = new JSONObject(new String(android.util.Base64.decode(body, flags), "UTF-8"));
            String n = o.optString("name");
            return n.isEmpty() ? "بی‌نام" : n;
        } catch (Exception e) {
            return "?";
        }
    }

    private void refresh() {
        netLine.setText(NetInfo.lineFa(NetInfo.snapshot(this, labelEdit.getText().toString().trim())));
        JSONArray a = observers();
        StringBuilder s = new StringBuilder();
        if (a.length() == 0) s.append("هنوز ناظری اضافه نشده. آزمون‌های NAT، سوراخ‌زنی و رله ناظر می‌خواهند.");
        for (int i = 0; i < a.length(); i++) s.append(i == 0 ? "ناظر اول: " : "\nناظر دوم: ").append(observerName(a.optString(i)));
        observersView.setText(s);
        myCardView.setText(myCard == null ? "کارت این گوشی هنوز ساخته نشده." : "کارت این گوشی آماده است.");
        peerView.setText(prefs.getString("peer", null) == null ? "کارت گوشی دیگر هنوز خوانده نشده." : "کارت گوشی دیگر خوانده شده.");
        if (!busy) status.setText("آماده · " + Fa.num(report.count()) + " نتیجه در گزارش");
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private void copy(String s) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("netprobe", s));
        toast("رونوشت شد.");
    }

    private interface OnText {
        void got(String s);
    }

    private void paste(String title, OnText then) {
        EditText e = new EditText(this);
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
            CharSequence c = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
            if (c != null) e.setText(c);
        }
        new AlertDialog.Builder(this).setTitle(title).setView(e)
                .setPositiveButton("ثبت", (d, w) -> then.got(e.getText().toString()))
                .setNegativeButton("انصراف", null).show();
    }

    private void scan(int req) {
        startActivityForResult(new Intent(this, QrScanActivity.class), req);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
        String text = data.getStringExtra("text");
        if (text == null) return;
        if (req == REQ_SCAN_OBSERVER) addObserver(text);
        if (req == REQ_SCAN_PEER) setPeer(text);
    }

    private void pollLog() {
        if (Native.LOADED) appendLog(Native.pollLog());
        if (Spike.LOADED) appendLog(Spike.pollLog());
        if (SpikeIroh.LOADED) appendLog(SpikeIroh.pollLog());
        ui.postDelayed(this::pollLog, 400);
    }

    private void appendLog(String s) {
        if (s == null || s.isEmpty()) return;
        String all = log.getText() + (log.length() > 0 ? "\n" : "") + s;
        if (all.length() > 6000) all = all.substring(all.length() - 6000);
        log.setText(all);
    }

    // ---- running tests ----

    private interface OnResult {
        void got(JSONObject r);
    }

    private void runNative(String cmd, JSONObject args, int unused) {
        runNative(cmd, args, true, null);
    }

    private interface Runner {
        String run(String json);
    }

    private void runNative(String cmd, JSONObject args, boolean record, OnResult then) {
        if (!Native.LOADED) {
            toast("کتابخانهٔ سنجش بار نشده است.");
            return;
        }
        runOn(Native::run, cmd, put(args, "cmd", cmd), record, then);
    }

    /**
     * libp2p or iroh, two phones through the first observer's relays. The card is decoded here
     * and handed over as JSON; the libraries never see probe-core's card format.
     */
    private void runChat(String cmd) {
        runChat(cmd, null);
    }

    private void runChat(String cmd, OnResult then) {
        boolean p2p = "p2p_chat".equals(cmd);
        if (p2p ? !Spike.LOADED : !SpikeIroh.LOADED) {
            toast("کتابخانهٔ " + (p2p ? "libp2p" : "iroh") + " روی این گوشی بار نشد: " + (p2p ? Spike.LOAD_ERROR : SpikeIroh.LOAD_ERROR));
            return;
        }
        JSONObject card = observerCard(p2p ? "p2p" : "iroh");
        if (card == null) return;
        JSONObject a = put(new JSONObject(), "cmd", "chat");
        put(a, "obs", card);
        put(a, "duration_s", 120);
        runOn(p2p ? Spike::run : SpikeIroh::run, cmd, a, true, then);
    }

    /** The first observer's card as JSON, or null (with a message) when it lacks `needs`. */
    private JSONObject observerCard(String needs) {
        JSONArray obs = observers();
        if (obs.length() == 0) {
            toast("اول کارت ناظر را بخوانید.");
            return null;
        }
        JSONObject card;
        try {
            String body = obs.optString(0).substring("mlmobs1:".length());
            int flags = android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP;
            card = new JSONObject(new String(android.util.Base64.decode(body, flags), "UTF-8"));
        } catch (Exception e) {
            toast("کارت ناظر خوانده نشد.");
            return null;
        }
        if (!card.has(needs)) {
            toast("این کارت مال ناظر قدیمی است. ناظر نسخهٔ جدید را روی رایانه اجرا کنید و کارتش را دوباره بخوانید.");
            return null;
        }
        return card;
    }

    // ---- Bluetooth ----

    private BleTest ble;
    /** How long the range mode runs; adb can shorten it (--ei range_s N) for a quick check. */
    private int bleRangeSeconds = 300;

    private void startBle() {
        if (busy) {
            toast("یک آزمون در حال اجراست؛ صبر کنید یا «توقف آزمون» را بزنید.");
            return;
        }
        String peer = prefs.getString("peer", null);
        if (myCard == null || peer == null) {
            toast("اول در بخش ۳ «ساختن کارت من» را بزنید و کارت گوشی دیگر را بخوانید.");
            return;
        }
        String[] need = android.os.Build.VERSION.SDK_INT >= 31
                ? new String[]{"android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_ADVERTISE"}
                : new String[]{"android.permission.ACCESS_FINE_LOCATION"};
        for (String p : need) {
            if (checkSelfPermission(p) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(need, REQ_BLE);
                return;
            }
        }
        busy = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        result.setText("");
        final JSONObject net = NetInfo.snapshot(this, label());
        ble = new BleTest(this, myCard, peer, bleRangeSeconds, line -> ui.post(() -> appendLog(line)), r -> ui.post(() -> {
            busy = false;
            ble = null;
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            report.add("ble", net, r);
            android.util.Log.i("netprobe", "ble " + r);
            result.setText(Fa.ble(r));
            refresh();
            status.setText((r.optBoolean("ok") ? "تمام شد: " : "ناموفق: ") + "بلوتوث");
        }));
        status.setText(ble.isServer() ? "بلوتوث: این گوشی سرور است…" : "بلوتوث: دنبال گوشی دیگر…");
        ble.start();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req != REQ_BLE) return;
        for (int r : res) {
            if (r != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                toast("بی اجازهٔ بلوتوث این آزمون اجرا نمی‌شود.");
                return;
            }
        }
        startBle();
    }

    private void runPqTls(int n) {
        if (!PqTls.LOADED) {
            toast("کتابخانهٔ TLS روی این گوشی بار نشد: " + PqTls.LOAD_ERROR);
            return;
        }
        runOn(json -> PqTls.run(n), "pqtls", new JSONObject(), true, r -> android.util.Log.i("netprobe", "pqtls " + r));
    }

    // ---- battery ----

    private void confirmBattery(int seconds) {
        new AlertDialog.Builder(this)
                .setTitle("آزمون باتری")
                .setMessage("یک ساعت طول می‌کشد. گوشی را از شارژ جدا کنید، صفحه را خاموش کنید و به گوشی دست نزنید. نتیجه بعد از یک ساعت خودش در گزارش می‌آید. ناظر روی رایانه باید تا آخر روشن بماند.")
                .setPositiveButton("شروع", (d, w) -> startBattery(seconds))
                .setNegativeButton("نه", null).show();
    }

    private void startBattery(int seconds) {
        if (BatteryService.running) {
            toast("آزمون باتری همین حالا در حال اجراست.");
            return;
        }
        if (!Spike.LOADED) {
            toast("کتابخانهٔ libp2p روی این گوشی بار نشد.");
            return;
        }
        JSONObject card = observerCard("p2p");
        if (card == null) return;
        JSONObject a = put(new JSONObject(), "cmd", "hold");
        put(a, "obs", card);
        put(a, "duration_s", seconds);
        Intent i = new Intent(this, BatteryService.class)
                .putExtra(BatteryService.EXTRA_ARGS, a.toString())
                .putExtra(BatteryService.EXTRA_LABEL, label());
        startForegroundService(i);
        toast("آزمون باتری شروع شد. اعلان آن را تا پایان می‌بینید.");
    }

    /** Runs one command of a native library on the worker thread and records it in the report. */
    private void runOn(Runner runner, String cmd, JSONObject args, boolean record, OnResult then) {
        if (busy) {
            toast("یک آزمون در حال اجراست؛ صبر کنید یا «توقف آزمون» را بزنید.");
            return;
        }
        final String lbl = label();
        busy = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        status.setText("در حال اجرا: " + cmd + " …");
        result.setText("");
        final JSONObject net = NetInfo.snapshot(this, lbl);
        final WifiManager.MulticastLock lock = "upnp".equals(cmd)
                ? ((WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE)).createMulticastLock("netprobe") : null;
        if (lock != null) lock.acquire();
        worker.execute(() -> {
            JSONObject r;
            try {
                r = new JSONObject(runner.run(args.toString()));
            } catch (Throwable t) {
                r = put(new JSONObject(), "ok", false);
                put(r, "error", String.valueOf(t));
            }
            final JSONObject fr = r;
            if (lock != null) lock.release();
            if (record) report.add(cmd, net, fr);
            ui.post(() -> {
                busy = false;
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                result.setText(Fa.summary(cmd, fr));
                refresh();
                status.setText((fr.optBoolean("ok") ? "تمام شد: " : "ناموفق: ") + cmd + " · " + Fa.num(fr.optLong("took_ms") / 1000.0) + " ثانیه");
                if (then != null) then.got(fr);
            });
        });
    }

    private void makeCard() {
        runNative("make_card", obsJson(new JSONObject()), false, r -> {
            if (!r.optBoolean("ok")) return;
            myCard = r.optString("card");
            report.add("make_card", NetInfo.snapshot(this, label()), stripCard(r));
            refresh();
            showCard();
        });
    }

    /**
     * The card itself (keys, addresses) never goes into the report. The session id stays: it is
     * random, names nobody, and lets scripts/summarize.py pair the two phones' reports.
     */
    private static JSONObject stripCard(JSONObject r) {
        try {
            JSONObject c = new JSONObject(r.toString());
            c.remove("card");
            return c;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void showCard() {
        if (myCard == null) {
            toast("اول «ساختن کارت من» را بزنید.");
            return;
        }
        try {
            int w = getResources().getDisplayMetrics().widthPixels, h = getResources().getDisplayMetrics().heightPixels;
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(Qr.encode(myCard, Math.min(w, h) - dp(48)));
            iv.setBackgroundColor(Color.WHITE);
            iv.setPadding(dp(8), dp(8), dp(8), dp(8));
            new AlertDialog.Builder(this).setTitle("کارت این گوشی").setView(iv)
                    .setPositiveButton("بستن", null)
                    .setNeutralButton("رونوشت متن کارت", (d, x) -> copy(myCard)).show();
        } catch (Exception e) {
            toast("ساختن QR نشد: " + e.getMessage());
        }
    }

    private void runPeer(String cmd, int unused) {
        String peer = prefs.getString("peer", null);
        if (myCard == null) {
            toast("اول «ساختن کارت من» را بزنید.");
            return;
        }
        if (peer == null) {
            toast("اول کارت گوشی دیگر را بخوانید.");
            return;
        }
        JSONObject a = put(new JSONObject(), "peer", peer);
        if ("peer".equals(cmd)) {
            put(a, "duration_s", 40);
        } else {
            JSONArray obs = observers();
            if (obs.length() == 0) {
                toast("این آزمون ناظر می‌خواهد.");
                return;
            }
            put(a, "observer", obs.optString(0));
        }
        runNative(cmd, a, true, null);
    }

    private void confirmUpnp() {
        new AlertDialog.Builder(this)
                .setTitle("UPnP مودم")
                .setMessage("UPnP رمزنگاری ندارد. این آزمون از داخل شبکهٔ خانه، بی‌رمز با مودم حرف می‌زند و فقط نشانی محلی گوشی، دو شمارهٔ درگاه و برچسب «netprobe» را می‌فرستد؛ هیچ پیام یا اطلاعات شخصی‌ای نمی‌رود. درگاه‌ها بعد از آزمون فوراً بسته می‌شوند. فقط روی وای‌فای خانهٔ خودتان اجرا کنید.")
                .setPositiveButton("اجرا کن", (d, w) -> runNative("upnp", obsJson(new JSONObject()), 0))
                .setNegativeButton("نه", null).show();
    }

    private void awareCheck() {
        JSONObject r = AwareCheck.check(this);
        put(r, "ok", true);
        report.add("wifi_aware", NetInfo.snapshot(this, label()), r);
        result.setText(Fa.aware(r));
        refresh();
    }

    // ---- report ----

    private void showReport() {
        TextView t = new TextView(this);
        t.setText(report.text());
        t.setTextSize(11);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        t.setPadding(dp(12), dp(12), dp(12), dp(12));
        ScrollView s = new ScrollView(this);
        s.addView(t);
        new AlertDialog.Builder(this).setTitle("گزارش (" + Fa.num(report.count()) + " نتیجه)").setView(s).setPositiveButton("بستن", null).show();
    }

    private void shareReport() {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, "netprobe " + label());
        i.putExtra(Intent.EXTRA_TEXT, report.text());
        startActivity(Intent.createChooser(i, "فرستادن گزارش"));
    }

    private void exportReport() {
        try {
            File f = report.export(this, label());
            new AlertDialog.Builder(this).setTitle("ذخیره شد")
                    .setMessage(f.getAbsolutePath() + "\n\nبا رایانه:\nadb pull " + f.getAbsolutePath())
                    .setPositiveButton("بستن", null).show();
        } catch (Exception e) {
            toast("ذخیره نشد: " + e.getMessage());
        }
    }
}
