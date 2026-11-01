package org.mlmvpn.chat.bridge;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.tgnet.ConnectionsManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MLMCHAT: what a «گره» home-screen widget shows: the unread count, the name of whoever sent the
 * newest unread message, and one word for the network -- never a message's text. Written to a small
 * file in the app's own files folder, which both processes share, and read by the app's widget in
 * its own process and look ({@link #read}); the widget the app named ({@link GerehHost#setWidget})
 * is told only while one is placed, so a phone without one never has the app's main process woken
 * for it. Shared by every app that carries Gereh (moved out of MLMVPN's own widget, both teams,
 * issue 7, 28 Oct 2026); the drawing stays each app's.
 */
public final class GerehWidgetState {
    /** The file in the app's files folder. */
    public static final String STATE_FILE = "gereh_widget.json";
    /** Sent, explicitly, to the app's widget when what it shows changed. */
    public static final String ACTION_CHANGED = "org.mlmvpn.chat.bridge.GEREH_WIDGET_CHANGED";
    /** The widget hides a network word older than this: Gereh may have stopped since it was written. */
    public static final long NET_FRESH_MS = 15 * 60_000L;
    /** A live network word is written again this often, so it never goes stale while true. */
    private static final long REFRESH_MS = 5 * 60_000L;

    private static final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "gereh-widget");
        t.setDaemon(true);
        return t;
    });

    private static int unread = 0;
    private static String from = "";
    private static String net = "";
    private static long writtenAt = 0;

    /** What a widget shows. */
    public static final class State {
        /** Unread messages, every chat together. */
        public final int unread;
        /** Who sent the newest unread one ("" with none). */
        public final String from;
        /**
         * One of {@code online}, {@code connecting}, {@code local} (the same Wi-Fi or hotspot),
         * {@code ble} (Bluetooth), {@code waiting} (no network), or "" when unknown or stale.
         */
        public final String net;

        public State(int unread, String from, String net) {
            this.unread = unread;
            this.from = from;
            this.net = net;
        }
    }

    private GerehWidgetState() {
    }

    /** What the chat last wrote, in any process; nothing written yet (or unreadable): nothing new, network unknown. */
    public static State read(Context context) {
        try (FileInputStream in = new FileInputStream(new File(context.getFilesDir(), STATE_FILE))) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bytes.write(buf, 0, n);
            }
            JSONObject o = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            boolean fresh = System.currentTimeMillis() - o.optLong("at", 0L) < NET_FRESH_MS;
            String sender = o.optString("from");
            return new State(Math.max(0, o.optInt("unread", 0)), sender.length() > 40 ? sender.substring(0, 40) : sender, fresh ? o.optString("net") : "");
        } catch (Throwable e) {
            return new State(0, "", "");
        }
    }

    /** From NotificationsController.setBadge: the total unread, and the newest unread's sender. */
    public static synchronized void unread(int count, String sender) {
        String name = sender == null ? "" : sender;
        if (count == unread && name.equals(from)) {
            return;
        }
        unread = Math.max(0, count);
        from = unread > 0 ? name : "";
        save(true);
    }

    /** From Messenger.updateConnection, every few seconds: the state line at the top. */
    public static synchronized void net(int connectionState) {
        String word;
        if (connectionState == ConnectionsManager.ConnectionStateConnected) {
            word = "online";
        } else if (connectionState == ConnectionsManager.ConnectionStateConnecting) {
            word = "connecting";
        } else if (connectionState == ConnectionsManager.ConnectionStateLocal) {
            word = "local";
        } else if (connectionState == ConnectionsManager.ConnectionStateBluetooth) {
            word = "ble";
        } else {
            word = "waiting";
        }
        if (word.equals(net)) {
            if (System.currentTimeMillis() - writtenAt > REFRESH_MS) {
                save(false);
            }
            return;
        }
        net = word;
        save(true);
    }

    private static void save(boolean changed) {
        final String text;
        try {
            text = new JSONObject()
                    .put("unread", unread)
                    .put("from", from)
                    .put("net", net)
                    .put("at", System.currentTimeMillis())
                    .toString();
        } catch (Exception e) {
            return;
        }
        writtenAt = System.currentTimeMillis();
        io.execute(() -> {
            Context context = ApplicationLoader.applicationContext;
            if (context == null) {
                return;
            }
            try {
                File dir = context.getFilesDir();
                File tmp = new File(dir, STATE_FILE + ".tmp");
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write(text.getBytes(StandardCharsets.UTF_8));
                }
                if (!tmp.renameTo(new File(dir, STATE_FILE))) {
                    tmp.delete();
                    return;
                }
                String widget = GerehHost.widget();
                if (!changed || widget == null) {
                    return;
                }
                ComponentName provider = new ComponentName(context.getPackageName(), widget);
                int[] ids = AppWidgetManager.getInstance(context).getAppWidgetIds(provider);
                if (ids != null && ids.length > 0) {
                    context.sendBroadcast(new Intent(ACTION_CHANGED).setComponent(provider));
                }
            } catch (Throwable ignored) {
                // a widget that misses one update is better than a chat that stops
            }
        });
    }
}
