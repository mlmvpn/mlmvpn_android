package com.mlmchat.netprobe;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

/**
 * Bluetooth LE between two phones (PLAN 17.1): round trip, throughput both ways, then range
 * (a ping and the signal strength every second until the link drops or the user stops).
 *
 * Both phones have read each other's card. The phone whose card has the smaller session id is
 * the GATT server and advertises; the other scans for it and connects. Everything after the
 * advertisement is Noise_NKpsk0 (probe-core blenoise) with the server's card key: two handshake
 * messages, then every packet is nonce + ciphertext (decision T18). The advertisement itself
 * carries only the service id and 6 random bytes of the server's session id.
 */
@SuppressLint("MissingPermission")
final class BleTest {
    interface Log {
        void line(String s);
    }

    interface Done {
        void got(JSONObject r);
    }

    static final UUID SVC = UUID.fromString("6d6c6d63-6861-7430-0000-000000000001");
    static final UUID RX = UUID.fromString("6d6c6d63-6861-7430-0000-000000000002");
    static final UUID TX = UUID.fromString("6d6c6d63-6861-7430-0000-000000000003");
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final int MANUFACTURER_TEST_ID = 0xFFFF;
    private static final int BULK_BYTES = 64 * 1024;

    // Plaintext message kinds inside Noise.
    private static final byte PING = 'P', PONG = 'p', DATA = 'D', END = 'E', END_ACK = 'e', GET = 'G', DOWN = 'd', GOT_ALL = 'g';

    private final Context ctx;
    private final String peerCard;
    private final boolean server;
    private final byte[] serverTag;
    private final int rangeSeconds;
    private final Log log;
    private final Done done;
    private final HandlerThread thread = new HandlerThread("ble-test");
    private Handler h;

    private final JSONObject r = new JSONObject();
    private final long t0 = SystemClock.elapsedRealtime();
    private long noise;
    private long txNonce = 1;
    private boolean ready, finished, stopRequested;
    private int mtu = 23;

    // client
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic rx;
    private final ArrayDeque<byte[]> writes = new ArrayDeque<>();
    private boolean writing;
    private ScanCallback scanCb;

    // server
    private BluetoothGattServer gattServer;
    private BluetoothDevice client;
    private BluetoothGattCharacteristic tx;
    private final ArrayDeque<byte[]> notes = new ArrayDeque<>();
    private boolean notifying;
    private AdvertiseCallback advCb;
    private long upFirst, upLast;
    private int upBytes;

    // client measurements
    private final ArrayList<Long> rtts = new ArrayList<>();
    private int pingSeq;
    private long pingSentAt;
    private long bulkStart;
    private int downBytes;
    private final JSONArray range = new JSONArray();
    private int lastRssi;

    BleTest(Context ctx, String myCard, String peerCard, int rangeSeconds, Log log, Done done) {
        this.ctx = ctx.getApplicationContext();
        this.peerCard = peerCard;
        String mySid = sid(myCard), peerSid = sid(peerCard);
        this.server = mySid.compareTo(peerSid) < 0;
        this.serverTag = Arrays.copyOf((server ? mySid : peerSid).getBytes(), 6);
        this.rangeSeconds = rangeSeconds;
        this.log = log;
        this.done = done;
        put("role", server ? "server" : "client");
    }

    static String sid(String card) {
        try {
            String body = card.substring(card.indexOf(':') + 1);
            int f = Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP;
            return new JSONObject(new String(Base64.decode(body, f), "UTF-8")).optString("sid");
        } catch (Exception e) {
            return "";
        }
    }

    boolean isServer() {
        return server;
    }

    private void put(String k, Object v) {
        try {
            r.put(k, v);
        } catch (Exception ignored) {
        }
    }

    private long ms() {
        return SystemClock.elapsedRealtime() - t0;
    }

    void start() {
        thread.start();
        h = new Handler(thread.getLooper());
        h.post(() -> {
            BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
            if (ad == null || !ad.isEnabled()) {
                fail("بلوتوث خاموش است یا این گوشی ندارد");
                return;
            }
            put("le_2m_phy", ad.isLe2MPhySupported());
            put("le_coded_phy", ad.isLeCodedPhySupported());
            put("extended_advertising", ad.isLeExtendedAdvertisingSupported());
            if (server) startServer(bm, ad);
            else startClient(ad);
            h.postDelayed(() -> {
                if (!ready) fail("در ۹۰ ثانیه اتصال امن برقرار نشد");
            }, 90_000);
        });
    }

    void stop() {
        if (h == null) return;
        h.post(() -> {
            stopRequested = true;
            if (!server && ready) {
                finish();
            } else {
                fail("متوقف شد");
            }
        });
    }

    private void fail(String why) {
        if (finished) return;
        put("error", why);
        put("ok", false);
        log.line("بلوتوث: " + why);
        finish();
    }

    private void finish() {
        if (finished) return;
        finished = true;
        put("took_ms", ms());
        put("mtu", mtu);
        if (!r.has("ok")) put("ok", ready);
        try {
            if (scanCb != null) ((BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter().getBluetoothLeScanner().stopScan(scanCb);
        } catch (Throwable ignored) {
        }
        try {
            if (advCb != null) ((BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter().getBluetoothLeAdvertiser().stopAdvertising(advCb);
        } catch (Throwable ignored) {
        }
        try {
            if (gatt != null) gatt.close();
        } catch (Throwable ignored) {
        }
        try {
            if (gattServer != null) gattServer.close();
        } catch (Throwable ignored) {
        }
        if (noise != 0) Native.bleFree(noise);
        noise = 0;
        done.got(r);
        thread.quitSafely();
    }

    // ---- Noise ----

    private byte[] seal(byte[] pt) {
        return Native.bleSeal(noise, txNonce++, pt);
    }

    /** Payload bytes per packet after the kind byte. */
    private int payloadMax() {
        // ATT payload is MTU - 3, and no attribute value may exceed 512 bytes (Android 13+ throws).
        // Noise adds an 8-byte nonce and a 16-byte tag; one byte is the message kind.
        return Math.max(1, Math.min(mtu - 3, 512) - 24 - 1);
    }

    private void onPacket(byte[] pkt) {
        if (finished) return;
        if (!ready) {
            if (server) {
                // Handshake message 1 from the client; answer with message 2.
                if (Native.bleReadHs(noise, pkt) == null) {
                    fail("دست‌دادن Noise رد شد (کارت‌ها جور نیستند)");
                    return;
                }
                byte[] m2 = Native.bleWriteHs(noise, new byte[0]);
                ready = true;
                put("secure_ms", ms());
                log.line("بلوتوث: اتصال امن آماده شد");
                notifyRaw(m2);
            } else {
                if (Native.bleReadHs(noise, pkt) == null) {
                    fail("دست‌دادن Noise رد شد");
                    return;
                }
                ready = true;
                put("secure_ms", ms());
                log.line("بلوتوث: اتصال امن آماده شد");
                clientPing();
            }
            return;
        }
        byte[] m = Native.bleOpen(noise, pkt);
        if (m == null || m.length == 0) {
            log.line("بلوتوث: بستهٔ نامعتبر کنار گذاشته شد");
            return;
        }
        if (server) serverMessage(m);
        else clientMessage(m);
    }

    // ---- server ----

    private void startServer(BluetoothManager bm, BluetoothAdapter ad) {
        noise = Native.bleResponder();
        if (noise == 0) {
            fail("کارت این گوشی ساخته نشده");
            return;
        }
        gattServer = bm.openGattServer(ctx, new BluetoothGattServerCallback() {
            @Override
            public void onServiceAdded(int status, BluetoothGattService service) {
                h.post(() -> advertise(ad));
            }

            @Override
            public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
                h.post(() -> {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        client = device;
                        put("connected_ms", ms());
                        log.line("بلوتوث: گوشی دیگر وصل شد");
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED && device.equals(client)) {
                        put("disconnected_ms", ms());
                        if (ready) finish();
                    }
                });
            }

            @Override
            public void onMtuChanged(BluetoothDevice device, int m) {
                h.post(() -> mtu = m);
            }

            @Override
            public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId, BluetoothGattCharacteristic c, boolean prepared, boolean responseNeeded, int offset, byte[] value) {
                if (responseNeeded) gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
                byte[] v = value == null ? new byte[0] : value.clone();
                h.post(() -> onPacket(v));
            }

            @Override
            public void onDescriptorWriteRequest(BluetoothDevice device, int requestId, BluetoothGattDescriptor d, boolean prepared, boolean responseNeeded, int offset, byte[] value) {
                if (responseNeeded) gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
            }

            @Override
            public void onNotificationSent(BluetoothDevice device, int status) {
                h.post(() -> {
                    notifying = false;
                    pumpNotes();
                });
            }
        });
        if (gattServer == null) {
            fail("سرور GATT باز نشد");
            return;
        }
        BluetoothGattService s = new BluetoothGattService(SVC, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        BluetoothGattCharacteristic rxc = new BluetoothGattCharacteristic(RX,
                BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);
        tx = new BluetoothGattCharacteristic(TX, BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ);
        tx.addDescriptor(new BluetoothGattDescriptor(CCCD, BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE));
        s.addCharacteristic(rxc);
        s.addCharacteristic(tx);
        gattServer.addService(s);
    }

    private void advertise(BluetoothAdapter ad) {
        if (ad.getBluetoothLeAdvertiser() == null) {
            fail("این گوشی نمی‌تواند تبلیغ بلوتوث بفرستد (نقش سرور)");
            return;
        }
        AdvertiseSettings st = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .build();
        AdvertiseData data = new AdvertiseData.Builder().addServiceUuid(new ParcelUuid(SVC)).build();
        AdvertiseData scanResp = new AdvertiseData.Builder().addManufacturerData(MANUFACTURER_TEST_ID, serverTag).build();
        advCb = new AdvertiseCallback() {
            @Override
            public void onStartSuccess(AdvertiseSettings s) {
                log.line("بلوتوث: این گوشی سرور است و منتظر گوشی دیگر");
            }

            @Override
            public void onStartFailure(int errorCode) {
                h.post(() -> fail("تبلیغ بلوتوث شروع نشد (کد " + errorCode + ")"));
            }
        };
        ad.getBluetoothLeAdvertiser().startAdvertising(st, data, scanResp, advCb);
    }

    private void notifyRaw(byte[] v) {
        notes.add(v);
        pumpNotes();
    }

    @SuppressWarnings("deprecation")
    private void pumpNotes() {
        if (notifying || notes.isEmpty() || client == null) return;
        byte[] v = notes.poll();
        notifying = true;
        boolean ok;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                ok = gattServer.notifyCharacteristicChanged(client, tx, false, v) == BluetoothGatt.GATT_SUCCESS;
            } else {
                tx.setValue(v);
                ok = gattServer.notifyCharacteristicChanged(client, tx, false);
            }
        } catch (RuntimeException e) {
            fail("فرستادن اعلان بلوتوث رد شد: " + e.getClass().getSimpleName());
            return;
        }
        if (!ok) {
            // The stack is busy: try the same packet again shortly.
            notes.addFirst(v);
            notifying = false;
            h.postDelayed(this::pumpNotes, 5);
        }
    }

    private void serverMessage(byte[] m) {
        switch (m[0]) {
            case PING:
                m[0] = PONG;
                notifyRaw(seal(m));
                break;
            case DATA:
                long now = SystemClock.elapsedRealtime();
                if (upBytes == 0) upFirst = now;
                upLast = now;
                upBytes += m.length - 1;
                break;
            case END:
                ByteBuffer b = ByteBuffer.allocate(9).put(END_ACK).putInt(upBytes).putInt((int) (upLast - upFirst));
                notifyRaw(seal(b.array()));
                put("received_from_client_bytes", upBytes);
                upBytes = 0;
                break;
            case GET:
                int want = ByteBuffer.wrap(m, 1, 4).getInt();
                int chunk = payloadMax();
                for (int sent = 0; sent < want; sent += chunk) {
                    byte[] d = new byte[Math.min(chunk, want - sent) + 1];
                    d[0] = DOWN;
                    notifyRaw(seal(d));
                }
                notifyRaw(seal(new byte[]{GOT_ALL}));
                break;
            default:
                break;
        }
    }

    // ---- client ----

    private void startClient(BluetoothAdapter ad) {
        noise = Native.bleInitiator(peerCard);
        if (noise == 0) {
            fail("کارت گوشی دیگر برای بلوتوث کامل نیست");
            return;
        }
        if (ad.getBluetoothLeScanner() == null) {
            fail("جست‌وجوی بلوتوث در دسترس نیست");
            return;
        }
        // Android 11 and older silently return no scan results while location is off
        // ("Permission denial: Location is off" in the Bluetooth log).
        if (android.os.Build.VERSION.SDK_INT < 31) {
            android.location.LocationManager lm = (android.location.LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            if (lm != null && !lm.isLocationEnabled()) {
                fail("موقعیت مکانی این گوشی خاموش است؛ در اندروید ۱۱ و پایین‌تر جست‌وجوی بلوتوث بدون آن هیچ نتیجه‌ای نمی‌دهد. آن را روشن کنید و دوباره بزنید");
                return;
            }
        }
        scanCb = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult res) {
                byte[] tag = res.getScanRecord() == null ? null : res.getScanRecord().getManufacturerSpecificData(MANUFACTURER_TEST_ID);
                if (tag == null || !Arrays.equals(tag, serverTag)) return;
                h.post(() -> {
                    if (gatt != null) return;
                    put("found_ms", ms());
                    put("found_rssi", res.getRssi());
                    ad.getBluetoothLeScanner().stopScan(scanCb);
                    log.line("بلوتوث: گوشی دیگر پیدا شد (" + res.getRssi() + " dBm)؛ وصل می‌شوم");
                    connect(res.getDevice());
                });
            }

            @Override
            public void onScanFailed(int errorCode) {
                h.post(() -> fail("جست‌وجوی بلوتوث نشد (کد " + errorCode + ")"));
            }
        };
        ScanFilter f = new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SVC)).build();
        ScanSettings st = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        ad.getBluetoothLeScanner().startScan(Collections.singletonList(f), st, scanCb);
        log.line("بلوتوث: این گوشی دنبال گوشی دیگر می‌گردد");
    }

    private void connect(BluetoothDevice d) {
        gatt = d.connectGatt(ctx, false, new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
                h.post(() -> {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        put("connected_ms", ms());
                        g.requestMtu(517);
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        put("disconnected_ms", ms());
                        put("disconnect_status", status);
                        if (ready) finish();
                        else fail("اتصال بلوتوث قطع شد (کد " + status + ")");
                    }
                });
            }

            @Override
            public void onMtuChanged(BluetoothGatt g, int m, int status) {
                h.post(() -> {
                    mtu = m;
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                    g.discoverServices();
                });
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt g, int status) {
                h.post(() -> {
                    BluetoothGattService s = g.getService(SVC);
                    if (s == null) {
                        fail("سرویس آزمون روی گوشی دیگر نبود");
                        return;
                    }
                    rx = s.getCharacteristic(RX);
                    BluetoothGattCharacteristic txc = s.getCharacteristic(TX);
                    g.setCharacteristicNotification(txc, true);
                    BluetoothGattDescriptor cccd = txc.getDescriptor(CCCD);
                    writeDescriptor(g, cccd);
                });
            }

            @Override
            public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
                h.post(() -> {
                    // Notifications are on: send Noise handshake message 1.
                    byte[] m1 = Native.bleWriteHs(noise, new byte[0]);
                    if (m1 == null) {
                        fail("ساختن دست‌دادن Noise نشد");
                        return;
                    }
                    write(m1);
                });
            }

            @Override
            public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
                h.post(() -> {
                    writing = false;
                    pumpWrites();
                });
            }

            @Override
            @SuppressWarnings("deprecation")
            public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
                byte[] v = c.getValue() == null ? new byte[0] : c.getValue().clone();
                h.post(() -> onPacket(v));
            }

            @Override
            public void onReadRemoteRssi(BluetoothGatt g, int rssi, int status) {
                h.post(() -> lastRssi = rssi);
            }
        }, BluetoothDevice.TRANSPORT_LE);
    }

    @SuppressWarnings("deprecation")
    private void writeDescriptor(BluetoothGatt g, BluetoothGattDescriptor d) {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        } else {
            d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            g.writeDescriptor(d);
        }
    }

    private void write(byte[] v) {
        writes.add(v);
        pumpWrites();
    }

    /** One write at a time; the next one leaves on onCharacteristicWrite (also flow control). */
    @SuppressWarnings("deprecation")
    private void pumpWrites() {
        if (writing || writes.isEmpty() || gatt == null || rx == null) return;
        byte[] v = writes.peek();
        // Handshake and control messages ask for a response; bulk data does not.
        boolean bulk = ready && v.length > 64;
        int type = bulk ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
        boolean ok;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                ok = gatt.writeCharacteristic(rx, v, type) == BluetoothGatt.GATT_SUCCESS;
            } else {
                rx.setWriteType(type);
                rx.setValue(v);
                ok = gatt.writeCharacteristic(rx);
            }
        } catch (RuntimeException e) {
            // A Bluetooth error ends this test, never the app.
            fail("نوشتن بلوتوث رد شد: " + e.getClass().getSimpleName());
            return;
        }
        if (ok) {
            writes.poll();
            writing = true;
        } else {
            h.postDelayed(this::pumpWrites, 5);
        }
    }

    private void clientPing() {
        pingSeq++;
        pingSentAt = SystemClock.elapsedRealtime();
        byte[] p = ByteBuffer.allocate(5).put(PING).putInt(pingSeq).array();
        write(seal(p));
    }

    private void clientMessage(byte[] m) {
        long now = SystemClock.elapsedRealtime();
        switch (m[0]) {
            case PONG: {
                int seq = ByteBuffer.wrap(m, 1, 4).getInt();
                if (seq != pingSeq) return;
                long rtt = now - pingSentAt;
                if (rtts.size() < 5) {
                    rtts.add(rtt);
                    if (rtts.size() < 5) {
                        clientPing();
                    } else {
                        ArrayList<Long> s = new ArrayList<>(rtts);
                        Collections.sort(s);
                        put("rtt_median_ms", s.get(2));
                        log.line("بلوتوث: رفت‌وبرگشت " + s.get(2) + " میلی‌ثانیه؛ ارسال ۶۴ کیلوبایت");
                        upload();
                    }
                } else {
                    rangeSample(rtt);
                }
                break;
            }
            case END_ACK: {
                ByteBuffer b = ByteBuffer.wrap(m, 1, 8);
                int got = b.getInt();
                long took = now - bulkStart;
                put("upload_bytes", got);
                put("upload_ms", took);
                put("upload_kbps", Math.round(got * 8.0 / Math.max(1, took)));
                log.line("بلوتوث: ارسال " + Math.round(got * 8.0 / Math.max(1, took)) + " کیلوبیت بر ثانیه؛ دریافت ۶۴ کیلوبایت");
                downBytes = 0;
                bulkStart = now;
                write(seal(ByteBuffer.allocate(5).put(GET).putInt(BULK_BYTES).array()));
                break;
            }
            case DOWN:
                downBytes += m.length - 1;
                break;
            case GOT_ALL: {
                long took = now - bulkStart;
                put("download_bytes", downBytes);
                put("download_ms", took);
                put("download_kbps", Math.round(downBytes * 8.0 / Math.max(1, took)));
                log.line("بلوتوث: دریافت " + Math.round(downBytes * 8.0 / Math.max(1, took)) + " کیلوبیت بر ثانیه. حالت برد: با گوشی دور شوید");
                put("range_started_ms", ms());
                rangeTick();
                break;
            }
            default:
                break;
        }
    }

    private void upload() {
        int chunk = payloadMax();
        bulkStart = SystemClock.elapsedRealtime();
        for (int sent = 0; sent < BULK_BYTES; sent += chunk) {
            byte[] d = new byte[Math.min(chunk, BULK_BYTES - sent) + 1];
            d[0] = DATA;
            write(seal(d));
        }
        write(seal(new byte[]{END}));
    }

    private boolean pingOutstanding;

    /** Range: one ping and one RSSI read per second; a ping lost for 5 s counts as gone. */
    private void rangeTick() {
        if (finished) return;
        long sinceStart = ms() - r.optLong("range_started_ms");
        if (stopRequested || sinceStart > rangeSeconds * 1000L) {
            finish();
            return;
        }
        if (pingOutstanding && SystemClock.elapsedRealtime() - pingSentAt > 5000) {
            rangeSample(-1);
        }
        if (!pingOutstanding) {
            pingOutstanding = true;
            gatt.readRemoteRssi();
            clientPing();
        }
        h.postDelayed(this::rangeTick, 1000);
    }

    private void rangeSample(long rtt) {
        pingOutstanding = false;
        try {
            JSONObject s = new JSONObject();
            s.put("t_s", (ms() - r.optLong("range_started_ms")) / 1000);
            s.put("rssi", lastRssi);
            if (rtt >= 0) s.put("rtt_ms", rtt);
            else s.put("lost", true);
            range.put(s);
            put("range", range);
        } catch (Exception ignored) {
        }
        log.line("برد: " + lastRssi + " dBm" + (rtt >= 0 ? " · " + rtt + " ms" : " · پیام نرسید"));
    }
}
