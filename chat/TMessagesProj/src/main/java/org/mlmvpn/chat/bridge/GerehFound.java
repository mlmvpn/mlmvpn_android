package org.mlmvpn.chat.bridge;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.mlmchat.core.ChatNode;
import org.mlmchat.core.DirResult;
import org.mlmchat.core.Group;
import org.mlmvpn.chat.ui.GerehTgUi;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ChatActivity;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MLMCHAT: the chat list's search beyond this phone's own chats (q6, T70; Ehsan, 30 Oct 2026).
 * «جستجو در گره»: the public list of Gereh's channels and groups (the core's directory, cards on
 * our relays, ranked on the phone: the words, then size, then age). «جستجو در تلگرام»: Telegram's
 * public channels through the user's own Telegram script (T68's, its tgs: tg.me's search and t.me
 * itself, read by Google, never by this phone). Telegram's contacts.search is answered with the
 * Gereh part at once and, a moment later, with both (SearchAdapterHelper takes the second answer
 * through {@link #later}); every result is a chat this phone is not in, and a tap on one joins it
 * (its Gereh link, Telegram's join dialog) or adds it (GerehTgUi) instead of opening it.
 */
public final class GerehFound {
    private static final String TAG = "MLMCHAT";
    /** Fewer letters than this find too much to be worth the relay or the script. */
    private static final int MIN_CHARS = 2;
    private static final int TG_MIN_CHARS = 3;
    private static final int GEREH_MAX = 30;
    /** How long typing must rest before the script is asked (each ask is one of Google's calls). */
    private static final long PAUSE_MS = 700;
    /** Telegram's answer to the same words is kept this long. */
    private static final long KEEP_MS = 10 * 60_000L;
    /** After no relay answered, the list is not asked again before this. */
    private static final long RETRY_MS = 2 * 60_000L;
    /**
     * A search reads the list again after this (only cards signed since the last read come), not
     * only when the core says it is an hour old: a chat made a minute ago is found (Ehsan's test).
     */
    private static final long READ_AGAIN_MS = 2 * 60_000L;
    private static volatile long readAt;

    /** A Gereh chat, a Telegram channel, the row that opens the script's setup, Telegram's section row. */
    private static final int GEREH = 1, TELEGRAM = 2, SETUP = 3, STATUS = 4;

    /** What a found chat is, by its Telegram chat id. */
    private static final class Hit {
        final int kind;
        /** A Gereh invite link, or a Telegram channel's name. */
        final String value;
        /** A hint row's second line (null: Telegram's own, the members or «کانال خصوصی»). */
        final String line2;

        Hit(int kind, String value) {
            this(kind, value, null);
        }

        Hit(int kind, String value, String line2) {
            this.kind = kind;
            this.value = value;
            this.line2 = line2;
        }
    }

    /** One search's wish to have Telegram's part later, by its request (SearchAdapterHelper). */
    private static final class Late {
        final boolean wanted;
        final Utilities.Callback<TLObject> then;

        Late(boolean wanted, Utilities.Callback<TLObject> then) {
            this.wanted = wanted;
            this.then = then;
        }
    }

    /** Telegram's rows for some words, or where Telegram's part stands instead. */
    private static final class Kept {
        final long at = System.currentTimeMillis();
        final List<String[]> rows;
        /** DONE (the rows), or SEARCHING, FAILED, or a hint row: no script yet, or one made before the search. */
        final int state;
        /** The rows came from a script that searches but brings no photos (VERSION 2 or 3): a row asks to update it. */
        final boolean noPhotos;

        Kept(List<String[]> rows, int state) {
            this(rows, state, false);
        }

        Kept(List<String[]> rows, int state, boolean noPhotos) {
            this.rows = rows;
            this.state = state;
            this.noPhotos = noPhotos;
        }
    }

    private static final int DONE = 0, SEARCHING = 1, FAILED = 2, HINT_SETUP = 3, HINT_OLD = 4;
    /**
     * The script whose search brings each channel's photo (Telegram's own small copy). VERSION 3
     * asked tg.me only, and on Ehsan's phone none came (31 Oct 2026: «عکساشون نمیاد»).
     */
    private static final int TG_PHOTOS_VER = 4;
    private static final long HINT_ID = GerehTg.chatIdOf("\u0000search-hint");
    /** Telegram's section row, carried in an answer as a chat that {@link #sections} turns into one. */
    private static final long STATUS_ID = GerehTg.chatIdOf("\u0000search-status");

    /**
     * A section row inside the search's list, «جستجو در تلگرام», with where it stands on its other
     * side (DialogsSearchAdapter draws it like its own section rows). Both parts always show, even
     * empty (Ehsan, 31 Oct 2026: «دوتیکه هم نشده»).
     */
    public static final class Section extends TLObject {
        public final String title;
        /** «در حال جستجو…», «چیزی پیدا نشد», …; empty for none. */
        public final String note;

        Section(String title, String note) {
            this.title = title;
            this.note = note;
        }
    }

    private static final Map<Long, Hit> hits = new ConcurrentHashMap<>();
    private static final Map<TLObject, Late> waiting = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<String, Kept> kept = Collections.synchronizedMap(new LinkedHashMap<String, Kept>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Kept> e) {
            return size() > 40;
        }
    });
    private static final AtomicLong newest = new AtomicLong();
    private static final Object refreshing = new Object();
    private static volatile long failedAt;
    private static final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "gereh-found");
        t.setDaemon(true);
        return t;
    });

    /** How often this phone looks whether its own chats' cards are due (renewal or a change). */
    private static final long LOOK_MS = 20 * 60_000L;
    private static java.util.concurrent.ScheduledExecutorService timer;

    private GerehFound() {
    }

    // ---- this phone's own chats in the list ------------------------------------------------

    /**
     * The cards of the chats this phone owns: renewed when due (every six hours, the core's
     * DIR_PUBLISH_EVERY_MS), and at once when one is made, renamed or left out of the list.
     */
    static synchronized void start() {
        if (timer != null) {
            return;
        }
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "gereh-dir-timer");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleWithFixedDelay(GerehFound::publishIfDue, 2 * 60_000L, LOOK_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private static void publishIfDue() {
        ChatNode node = GerehIdentity.node();
        if (node == null) {
            return;
        }
        try {
            // each owned chat's small photo first: a new one makes the cards due
            ownPhotos(node);
            // what the cards say, of the chats this phone owns: a change goes now, not at the renewal
            StringBuilder sig = new StringBuilder();
            String me = node.getMe().getSk();
            for (Group g : node.groups()) {
                if (g.getOwner().equals(me) && !g.getDissolved()) {
                    sig.append(g.getId()).append('|').append(g.getName()).append('|').append(g.getAbout()).append('|').append(g.getChannel()).append(';');
                }
            }
            String now = Integer.toHexString(sig.toString().hashCode());
            android.content.SharedPreferences p = org.telegram.messenger.ApplicationLoader.applicationContext.getSharedPreferences("gereh_dir", android.content.Context.MODE_PRIVATE);
            if (!node.dirPublishDue() && now.equals(p.getString("sig", ""))) {
                return;
            }
            int took = node.dirPublish();
            if (took > 0) {
                p.edit().putString("sig", now).apply();
            }
        } catch (Throwable t) {
            Log.w(TAG, "directory publish: " + t.getClass().getSimpleName());
        }
    }

    /** -1: no switch here (not a Gereh chat this phone owns); else whether it is in the list. */
    static int listed(long chatId) {
        try {
            ChatNode node = GerehIdentity.node();
            if (node == null || GerehFeeds.feedOf(chatId) != null || GerehTg.isTg(-chatId)) {
                return -1;
            }
            Group g = GerehGroups.groupFor(node, chatId);
            if (g == null || g.getDissolved() || !g.getOwner().equals(node.getMe().getSk())) {
                return -1;
            }
            return node.dirListed(g.getId()) ? 1 : 0;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** The owner's switch: the card goes (or is withdrawn) now, off the screen's thread. */
    static void setListed(long chatId, boolean on) {
        pool.execute(() -> {
            try {
                ChatNode node = GerehIdentity.node();
                Group g = node != null ? GerehGroups.groupFor(node, chatId) : null;
                if (g == null) {
                    return;
                }
                node.dirSetListed(g.getId(), on);
                node.dirPublish();
            } catch (Throwable t) {
                Log.w(TAG, "directory switch: " + t.getClass().getSimpleName());
            }
        });
    }

    static boolean handles(TLObject req) {
        return req instanceof TLRPC.TL_contacts_search;
    }

    /** A search that shows chats it is not in (the chat list's), and where its later answer goes. */
    static void later(TLObject req, boolean wanted, Utilities.Callback<TLObject> then) {
        waiting.put(req, new Late(wanted, then));
    }

    static void answer(TLObject request, RequestDelegate done) {
        TLRPC.TL_contacts_search req = (TLRPC.TL_contacts_search) request;
        Late late = waiting.remove(req);
        String q = req.q != null ? req.q.trim() : "";
        if (late == null || !late.wanted || q.codePointCount(0, q.length()) < MIN_CHARS) {
            // a contact picker's search, or too little to look for: nothing beyond this phone
            reply(done, new TLRPC.TL_contacts_found());
            return;
        }
        long mine = newest.incrementAndGet();
        pool.execute(() -> {
            List<DirResult> gereh = gereh(q);
            String key = q.toLowerCase(Locale.ROOT);
            Kept k = worth(q) ? fresh(key) : null;
            boolean ask = false;
            if (k == null && worth(q)) {
                if (!GerehTg.hasReader()) {
                    k = new Kept(Collections.emptyList(), HINT_SETUP);
                } else {
                    // an old script is asked again every time: its «bad» costs Google nothing, and
                    // it may have just been updated (Ehsan, 31 Oct 2026: still «old» after updating)
                    k = new Kept(Collections.emptyList(), SEARCHING);
                    ask = true;
                }
            }
            reply(done, found(gereh, k));
            List<String> missing = missingPhotos(gereh);
            if (!ask && missing.isEmpty()) {
                return;
            }
            try {
                Thread.sleep(PAUSE_MS);
            } catch (InterruptedException e) {
                return;
            }
            if (newest.get() != mine) {
                return; // more was typed: that search asks
            }
            if (!missing.isEmpty()) {
                // the photos of the Gereh chats shown, from our relay (only those, Ehsan: «مثل خود تلگرام عکس»)
                try {
                    ChatNode node = GerehIdentity.node();
                    if (node != null && node.dirFetchPhotos(missing) > 0 && newest.get() == mine) {
                        late.then.run(found(gereh, k));
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "directory photos: " + t.getClass().getSimpleName());
                }
            }
            if (!ask) {
                return;
            }
            Kept t = telegram(q);
            if (t.state == DONE && !t.noPhotos) {
                // an old script's rows are not kept: once updated, the next search has the photos
                kept.put(key, t);
            }
            if (newest.get() == mine) {
                late.then.run(found(gereh, t));
            }
        });
    }

    private static boolean worth(String q) {
        return q.codePointCount(0, q.length()) >= TG_MIN_CHARS;
    }

    private static Kept fresh(String key) {
        Kept k = kept.get(key);
        return k != null && System.currentTimeMillis() - k.at < KEEP_MS ? k : null;
    }

    private static void reply(RequestDelegate done, TLObject r) {
        Utilities.stageQueue.postRunnable(() -> done.run(r, null));
    }

    /** Gereh's part: the list read again when it is old (once at a time), then the chats we are not in. */
    private static List<DirResult> gereh(String q) {
        ChatNode node = GerehIdentity.node();
        if (node == null) {
            return Collections.emptyList();
        }
        try {
            long now = System.currentTimeMillis();
            if ((now - readAt > READ_AGAIN_MS || node.dirStale()) && now - failedAt > RETRY_MS) {
                synchronized (refreshing) {
                    if (System.currentTimeMillis() - readAt > READ_AGAIN_MS || node.dirStale()) {
                        try {
                            int n = node.dirRefresh();
                            readAt = System.currentTimeMillis();
                            Log.i(TAG, "directory: " + n + " card(s)");
                        } catch (Throwable t) {
                            failedAt = System.currentTimeMillis();
                            Log.i(TAG, "directory: no relay answered");
                        }
                    }
                }
            }
            List<DirResult> out = new ArrayList<>();
            for (DirResult r : node.dirSearch(q, GEREH_MAX)) {
                if (!r.getMember()) {
                    out.add(r);
                }
            }
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "directory search: " + t.getClass().getSimpleName());
            return Collections.emptyList();
        }
    }

    /** Telegram's part from the user's script; FAILED when it could not be asked (the network, the quota). */
    private static Kept telegram(String q) {
        try {
            JSONObject a = GerehTg.search(q, telegramPhotosHere());
            JSONArray r = a.optJSONArray("r");
            List<String[]> rows = new ArrayList<>();
            int photos = 0;
            for (int i = 0; i < r.length(); i++) {
                JSONArray x = r.optJSONArray(i);
                String name = x != null ? GerehTg.nameFrom(x.optString(0)) : null;
                if (name != null) {
                    if (keepTelegramPhoto(name, x.optString(4))) {
                        photos++;
                    }
                    rows.add(new String[]{name, x.optString(1), x.optString(2)});
                }
            }
            int ver = a.optInt("ver");
            Log.i(TAG, "telegram search: " + rows.size() + " rows, " + photos + " photos, script " + ver);
            return new Kept(rows, DONE, ver < TG_PHOTOS_VER);
        } catch (Exception e) {
            String code = e.getMessage() != null ? e.getMessage() : "";
            Log.i(TAG, "telegram search: " + code);
            if (code.equals("no_reader")) {
                return new Kept(Collections.emptyList(), HINT_SETUP);
            }
            // a script of before the search answers {e: bad} for tgs, which the core passes on as "bad"
            if (code.equals("script_bad") || code.equals("bad")) {
                return new Kept(Collections.emptyList(), HINT_OLD);
            }
            return new Kept(Collections.emptyList(), FAILED);
        }
    }

    /** The answer Telegram's search takes: Gereh's chats, then Telegram's (or the hint). */
    private static TLRPC.TL_contacts_found found(List<DirResult> gereh, Kept tg) {
        TLRPC.TL_contacts_found f = new TLRPC.TL_contacts_found();
        int now = (int) (System.currentTimeMillis() / 1000);
        for (DirResult r : gereh) {
            TLRPC.Chat c;
            long id = GerehGroups.chatIdFor(r.getId());
            if (r.getBroadcast()) {
                TLRPC.TL_channel ch = new TLRPC.TL_channel();
                ch.broadcast = true;
                ch.access_hash = id;
                ch.participants_count = r.getMembers();
                ch.flags |= 1 << 17;
                c = ch;
            } else {
                TLRPC.TL_chat g = new TLRPC.TL_chat();
                g.version = 1;
                g.participants_count = r.getMembers();
                c = g;
            }
            c.id = id;
            c.title = r.getName().isEmpty() ? "?" : r.getName();
            c.left = true;
            c.date = now;
            c.photo = gerehPhoto(r);
            add(f, c, new Hit(GEREH, r.getLink()));
        }
        if (tg != null) {
            // Telegram's section row first, saying where it stands
            int shown = 0;
            for (String[] row : tg.rows) {
                if (GerehTg.chatOfName(row[0]) == 0) {
                    shown++;
                }
            }
            String note = tg.state == SEARCHING ? LocaleController.getString(R.string.GerehFoundSearching)
                    : tg.state == FAILED ? LocaleController.getString(R.string.GerehFoundTgFailed)
                    : tg.state == DONE && shown == 0 ? LocaleController.getString(R.string.GerehFoundNone) : "";
            TLRPC.TL_channel status = new TLRPC.TL_channel();
            status.id = STATUS_ID;
            status.access_hash = status.id;
            status.title = note;
            status.broadcast = true;
            status.left = true;
            status.date = now;
            status.photo = new TLRPC.TL_chatPhotoEmpty();
            add(f, status, new Hit(STATUS, ""));
            if (tg.state == DONE && tg.noPhotos && shown > 0) {
                // first, where it is seen: the photos need the script's new code (tap: its page)
                add(f, hintRow(LocaleController.getString(R.string.GerehFoundTgNoPhotosTitle), now),
                        new Hit(SETUP, "", LocaleController.getString(R.string.GerehFoundTgNoPhotos)));
            }
            for (String[] row : tg.rows) {
                if (GerehTg.chatOfName(row[0]) != 0) {
                    continue; // added already: among this phone's own chats
                }
                TLRPC.TL_channel c = new TLRPC.TL_channel();
                c.id = GerehTg.chatIdOf(row[0].toLowerCase(Locale.ROOT));
                c.access_hash = c.id;
                c.title = row[1].isEmpty() ? row[0] : row[1];
                c.username = row[0];
                c.flags |= 64;
                c.broadcast = true;
                c.left = true;
                c.participants_count = members(row[2]);
                c.flags |= 1 << 17;
                c.date = now;
                c.photo = photoOf(telegramPhoto(row[0]));
                add(f, c, new Hit(TELEGRAM, row[0]));
            }
            if (tg.state == HINT_SETUP || tg.state == HINT_OLD) {
                add(f, hintRow(LocaleController.getString(tg.state == HINT_OLD ? R.string.GerehFoundTgOld : R.string.GerehFoundTgSetup), now), new Hit(SETUP, ""));
            }
        }
        return f;
    }

    /** A row that says what the script needs; a tap opens the script's page. */
    private static TLRPC.TL_channel hintRow(String title, int now) {
        TLRPC.TL_channel c = new TLRPC.TL_channel();
        c.id = HINT_ID;
        c.access_hash = c.id;
        c.title = title;
        c.broadcast = true;
        c.left = true;
        c.date = now;
        c.photo = new TLRPC.TL_chatPhotoEmpty();
        return c;
    }

    private static void add(TLRPC.TL_contacts_found f, TLRPC.Chat c, Hit h) {
        hits.put(c.id, h);
        f.chats.add(c);
        if (c instanceof TLRPC.TL_channel) {
            TLRPC.TL_peerChannel p = new TLRPC.TL_peerChannel();
            p.channel_id = c.id;
            f.results.add(p);
        } else {
            TLRPC.TL_peerChat p = new TLRPC.TL_peerChat();
            p.chat_id = c.id;
            f.results.add(p);
        }
    }

    // ---- photos (Ehsan, 31 Oct 2026: «مثل خود تلگرام عکس نزاری که کلا خیلی زشت میشه») --------

    /** The chat photo each owned chat's list photo was made from, this run: made again only when it changes. */
    private static final Map<String, Long> ownPhotoFrom = new ConcurrentHashMap<>();
    private static final int OWN_PHOTO_SIDE = 160;

    private static File photoDir() {
        File d = new File(ApplicationLoader.applicationContext.getCacheDir(), "gereh-found");
        d.mkdirs();
        return d;
    }

    /** A chat photo Telegram shows from a file of ours (none: the letters). */
    private static TLRPC.ChatPhoto photoOf(File f) {
        return GerehLocalFiles.chatPhoto(f);
    }

    /** A Gereh result's photo, if read from the relay already (its card names it by hash). */
    private static TLRPC.ChatPhoto gerehPhoto(DirResult r) {
        if (r.getPhoto().isEmpty()) {
            return new TLRPC.TL_chatPhotoEmpty();
        }
        File f = new File(photoDir(), "g_" + r.getId() + "_" + r.getPhoto() + ".jpg");
        if (!f.exists()) {
            try {
                ChatNode node = GerehIdentity.node();
                byte[] jpeg = node != null ? node.dirPhoto(r.getId()) : null;
                if (jpeg == null) {
                    return new TLRPC.TL_chatPhotoEmpty();
                }
                write(f, jpeg);
            } catch (Throwable t) {
                return new TLRPC.TL_chatPhotoEmpty();
            }
        }
        return photoOf(f);
    }

    /** The Gereh results whose photo is named but not read yet. */
    private static List<String> missingPhotos(List<DirResult> gereh) {
        List<String> out = new ArrayList<>();
        for (DirResult r : gereh) {
            if (!r.getPhoto().isEmpty() && !new File(photoDir(), "g_" + r.getId() + "_" + r.getPhoto() + ".jpg").exists()) {
                out.add(r.getId());
            }
        }
        return out;
    }

    private static File telegramPhoto(String name) {
        return new File(photoDir(), "tg_" + name.toLowerCase(Locale.ROOT) + ".jpg");
    }

    /** Telegram channels whose photo is here: the script leaves them out. */
    private static List<String> telegramPhotosHere() {
        List<String> out = new ArrayList<>();
        String[] names = photoDir().list();
        if (names != null) {
            for (String n : names) {
                if (n.startsWith("tg_") && n.endsWith(".jpg") && out.size() < 64) {
                    out.add(n.substring(3, n.length() - 4));
                }
            }
        }
        return out;
    }

    /** Keeps a channel's photo the script sent; true when one was kept. */
    private static boolean keepTelegramPhoto(String name, String base64) {
        if (base64 == null || base64.isEmpty()) {
            return false;
        }
        try {
            byte[] jpeg = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
            if (jpeg.length > 0 && jpeg.length <= 49152) {
                write(telegramPhoto(name), jpeg);
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static void write(File f, byte[] bytes) throws java.io.IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(bytes);
        }
        if (!tmp.renameTo(f)) {
            tmp.delete();
        }
    }

    /**
     * Each owned chat's small list photo, made from its photo when that changed (a square of
     * OWN_PHOTO_SIDE pixels, about 10 KB): the core sends it with the chat's card.
     */
    private static void ownPhotos(ChatNode node) {
        String me = node.getMe().getSk();
        for (Group g : node.groups()) {
            if (!g.getOwner().equals(me) || g.getDissolved()) {
                continue;
            }
            long from = g.getPhoto();
            Long was = ownPhotoFrom.get(g.getId());
            if (was != null && was == from) {
                continue;
            }
            byte[] jpeg = from != 0 ? squareJpeg(node, from) : null;
            if (from != 0 && jpeg == null) {
                continue; // the photo is not whole here yet: next time
            }
            try {
                node.dirSetPhoto(g.getId(), jpeg);
                ownPhotoFrom.put(g.getId(), from);
            } catch (Throwable t) {
                Log.w(TAG, "directory photo: " + t.getClass().getSimpleName());
            }
        }
    }

    private static byte[] squareJpeg(ChatNode node, long shortId) {
        File tmp = new File(photoDir(), "own_" + shortId + ".tmp");
        try {
            org.json.JSONObject st = node.mediaByShort(shortId);
            if (st == null || !st.optBoolean("complete")) {
                return null;
            }
            node.openMediaShort(shortId, tmp.getAbsolutePath());
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(tmp.getAbsolutePath(), o);
            int sample = 1;
            while (Math.min(o.outWidth, o.outHeight) / (sample * 2) >= OWN_PHOTO_SIDE) {
                sample *= 2;
            }
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            Bitmap b = BitmapFactory.decodeFile(tmp.getAbsolutePath(), d);
            if (b == null) {
                return null;
            }
            int side = Math.min(b.getWidth(), b.getHeight());
            Bitmap sq = Bitmap.createBitmap(b, (b.getWidth() - side) / 2, (b.getHeight() - side) / 2, side, side);
            Bitmap small = Bitmap.createScaledBitmap(sq, OWN_PHOTO_SIDE, OWN_PHOTO_SIDE, true);
            for (int quality = 85; quality >= 40; quality -= 15) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                small.compress(Bitmap.CompressFormat.JPEG, quality, out);
                if (out.size() <= 30 * 1024) {
                    return out.toByteArray();
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        } finally {
            tmp.delete();
        }
    }

    /** Members as a directory page writes them: 980, 12 345, 12.5K, 1.2M. */
    static int members(String s) {
        if (s == null) {
            return 0;
        }
        String t = s.trim().toUpperCase(Locale.ROOT).replace(" ", "").replace(",", "").replace(" ", "");
        double mult = 1;
        if (t.endsWith("K")) {
            mult = 1_000;
            t = t.substring(0, t.length() - 1);
        } else if (t.endsWith("M")) {
            mult = 1_000_000;
            t = t.substring(0, t.length() - 1);
        }
        try {
            return (int) Math.min(Integer.MAX_VALUE, Math.round(Double.parseDouble(t) * mult));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int kindOf(TLObject o) {
        Hit h = o instanceof TLRPC.Chat ? hits.get(((TLRPC.Chat) o).id) : null;
        return h != null ? h.kind : 0;
    }

    /** Telegram's section row in the place of the chat that carries it (SearchAdapterHelper, after each answer). */
    static void sections(ArrayList<TLObject> list) {
        for (int i = 0; i < list.size(); i++) {
            if (kindOf(list.get(i)) == STATUS) {
                String note = ((TLRPC.Chat) list.get(i)).title;
                list.set(i, new Section(LocaleController.getString(R.string.GerehFoundTelegram), note != null ? note : ""));
            }
        }
    }

    /** A found row's second line of our own (DialogsSearchAdapter), or null for Telegram's. */
    public static CharSequence line2(long chatId) {
        Hit h = hits.get(chatId);
        return h != null ? h.line2 : null;
    }

    /** The first section's title: always Gereh's (Telegram's has its own row). */
    static String sectionTitle(ArrayList<TLObject> list) {
        return LocaleController.getString(R.string.GerehFoundGereh);
    }

    /** What the Gereh section's row says on its other side: «چیزی پیدا نشد» when it found none. */
    static String sectionNote(ArrayList<TLObject> list) {
        for (TLObject o : list) {
            if (kindOf(o) == GEREH) {
                return null;
            }
        }
        return LocaleController.getString(R.string.GerehFoundNone);
    }

    /** A tap on a found chat: joined, added or set up here; false for one Telegram opens itself. */
    static boolean open(BaseFragment f, long chatId) {
        Hit h = hits.get(chatId);
        if (h == null || f == null || f.getParentActivity() == null) {
            return false;
        }
        switch (h.kind) {
            case GEREH: {
                ChatNode node = GerehIdentity.node();
                Group g = node != null ? GerehGroups.groupFor(node, chatId) : null;
                if (g != null && g.getMember()) {
                    return false; // joined meanwhile: Telegram opens it as one of ours
                }
                Browser.openUrl(f.getParentActivity(), GerehInvites.tgLink(h.value));
                return true;
            }
            case TELEGRAM: {
                long had = GerehTg.chatOfName(h.value);
                if (had != 0) {
                    Bundle args = new Bundle();
                    args.putLong("chat_id", had);
                    f.presentFragment(new ChatActivity(args));
                } else {
                    GerehTgUi.startAdd(f, h.value);
                }
                return true;
            }
            case SETUP:
                GerehTgUi.openSetup(f);
                return true;
            default:
                return true; // a section row: nothing to open
        }
    }
}
