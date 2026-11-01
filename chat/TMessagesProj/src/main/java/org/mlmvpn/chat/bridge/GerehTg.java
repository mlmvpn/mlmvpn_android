package org.mlmvpn.chat.bridge;

import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.mlmchat.core.ChatNode;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_keyboard;
import org.telegram.tgnet.tl.TL_update;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * MLMCHAT: «کانال تلگرام محبوب» (T68, SPEC 21). A public Telegram channel copied onto this phone
 * only, with its name, photo and about, read through the user's own Telegram reader script, a
 * Google script apart from the Gereh relays (assets/gereh_tg.gs): the phone never connects to
 * Telegram. The channel is a channel in Telegram's screens like the official ones (GerehFeeds),
 * but nothing of it goes to anyone: no frame, no card, no feed item.
 * <p>
 * Kept in the core's encrypted database (`appGet`/`appPut`): the reader's deployment and key,
 * the channels and their posts. Files are kept in this app's private files, as Gereh's plain
 * copies are. Posts and files come only when the user asks: adding, «به‌روزرسانی», or the timer
 * they set on the channel (at least 15 minutes).
 */
public final class GerehTg {
    private static final String TAG = "MLMCHAT";
    private static final String READER_KEY = "tg:reader";
    private static final String LIST_KEY = "tg:list";
    private static final String CHANNEL_KEY = "tg:c:";
    /** Chat ids past every Gereh group's and official chat's (31 bits there). */
    private static final long ID_BASE = 1L << 32;
    public static final int[] COUNTS = {20, 50, 100};
    public static final int[] CAPS_MB = {1, 5, 10, 20};
    /** Minutes between checks; 0 = by hand only. */
    public static final int[] EVERY = {30, 60, 300, 1440};
    public static final int MIN_EVERY = 15;
    /** Posts kept per channel; the oldest go. */
    private static final int KEEP = 500;
    /** At most this much in one answer of the script (its own limit, SPEC 21). */
    private static final long PIECE = 2L * 1024 * 1024;
    /** A file the user asks for by a tap may be bigger than the channel's limit, up to this. */
    private static final long BY_HAND_MAX = 50L * 1024 * 1024;
    /** What a call costs besides its body: TLS and HTTP headers both ways and Google's redirect (an estimate). */
    private static final int UP_EXTRA = 700, DOWN_EXTRA = 1500;
    private static final int CALL_S = 45, FILE_S = 90, SEARCH_S = 25;
    private static final Pattern NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{3,31}$");
    private static final Pattern LINK = Pattern.compile("(?i)^(?:https?://)?(?:www\\.)?(?:t|telegram)\\.(?:me|dog)/(?:s/)?([A-Za-z][A-Za-z0-9_]{3,31})(?:[/?#].*)?$");

    public static final int STEP_CONNECT = 0, STEP_POSTS = 1, STEP_PHOTO = 2, STEP_SIZES = 3, STEP_FILES = 4, STEP_BUILD = 5;

    /** How a channel is added and kept up to date (the add sheet, and the channel's menu later). */
    public static final class Options {
        public int count = 50;
        public boolean files = true;
        public boolean later;
        public int capMb = 5;
        public int every = 60;
    }

    /** What one add or update did: for the user's report (requirement 2 of Ehsan's 30 Oct list). */
    public static final class Result {
        public boolean ok;
        public String error = "";
        public long chatId;
        public String title = "";
        public int posts, files, fresh, requests;
        public long up, down;
    }

    public interface Progress {
        /** On the main thread: the step, how far in it (done of total), and the files' bytes so far. */
        void on(int step, int done, int total, long bytes, long bytesTotal);
    }

    public interface Done {
        /** On the main thread. */
        void on(Result r);
    }

    public static final class Job {
        volatile boolean cancelled;

        public void cancel() {
            cancelled = true;
        }
    }

    private static final class Fail extends Exception {
        final String code;

        Fail(String code) {
            super(code);
            this.code = code;
        }
    }

    /** One channel. Posts are never changed in place: an update makes a new list. */
    private static final class Chan {
        final String key;
        volatile String name = "", title = "", about = "", photo = "";
        volatile long photoId, subs, next, last, up, down;
        volatile int count = 50, capMb = 5, every = 60, requests, read, joined;
        volatile boolean files = true, later;
        final AtomicInteger pts = new AtomicInteger(1);
        volatile List<JSONObject> posts = Collections.emptyList();

        Chan(String key) {
            this.key = key;
        }

        long chat() {
            return chatIdOf(key);
        }

        int top() {
            List<JSONObject> p = posts;
            return p.isEmpty() ? 0 : p.get(p.size() - 1).optInt("i");
        }
    }

    private static final class Counter {
        long up, down;
        int requests;
    }

    private static final Map<String, Chan> channels = new ConcurrentHashMap<>();
    private static final List<String> order = new CopyOnWriteArrayList<>();
    private static final Map<Long, String> byChat = new ConcurrentHashMap<>();
    /** File id → {channel key, post item, kind}; kind 0 is the channel's photo. */
    private static final Map<Long, Object[]> fileRefs = new ConcurrentHashMap<>();
    private static final Set<Long> whole = ConcurrentHashMap.newKeySet();
    private static final Map<Long, List<Runnable>> waiting = new ConcurrentHashMap<>();
    private static final Set<String> busy = ConcurrentHashMap.newKeySet();
    private static volatile JSONObject reader;
    private static volatile boolean loaded;
    private static final ExecutorService work = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "gereh-tg");
        t.setDaemon(true);
        return t;
    });
    private static final ExecutorService taps = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "gereh-tg-file");
        t.setDaemon(true);
        return t;
    });
    private static ScheduledExecutorService timer;

    private GerehTg() {
    }

    // ---- start, store ---------------------------------------------------------------------

    /** With the bridge (Messenger.run): the channels from the database, then the timer. */
    static synchronized void start() {
        if (timer != null) {
            return;
        }
        work.execute(GerehTg::load);
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "gereh-tg-timer");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleWithFixedDelay(() -> {
            try {
                tick();
            } catch (Throwable t) {
                Log.w(TAG, "tg channel timer: " + t.getClass().getSimpleName());
            }
        }, 30, 60, TimeUnit.SECONDS);
    }

    private static void load() {
        ChatNode node = GerehIdentity.node();
        if (node == null) {
            return;
        }
        try {
            String r = node.appGet(READER_KEY);
            reader = r != null ? new JSONObject(r) : null;
            String list = node.appGet(LIST_KEY);
            JSONArray names = list != null ? new JSONArray(list) : new JSONArray();
            for (int i = 0; i < names.length(); i++) {
                String key = names.optString(i);
                String json = node.appGet(CHANNEL_KEY + key);
                if (json == null) {
                    continue;
                }
                Chan c = fromJson(key, new JSONObject(json));
                channels.put(key, c);
                order.add(key);
                index(c);
            }
            File[] fs = dir().listFiles();
            if (fs != null) {
                for (File f : fs) {
                    try {
                        whole.add(Long.parseLong(f.getName()));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            Log.i(TAG, "tg channels: " + channels.size() + (reader != null ? ", reader set" : ""));
        } catch (Throwable t) {
            Log.e(TAG, "tg channels not read: " + t.getClass().getSimpleName());
        }
        loaded = true;
    }

    private static void index(Chan c) {
        byChat.put(c.chat(), c.key);
        if (c.photoId != 0) {
            fileRefs.put(c.photoId, new Object[]{c.key, 0, 0});
        }
        for (JSONObject p : c.posts) {
            JSONArray m = p.optJSONArray("m");
            for (int i = 0; m != null && i < m.length(); i++) {
                JSONObject md = m.optJSONObject(i);
                fileRefs.put(md.optLong("f"), new Object[]{c.key, md.optInt("i"), md.optInt("k")});
            }
            JSONArray dl = p.optJSONArray("dl");
            for (int i = 0; dl != null && i < dl.length(); i++) {
                int item = dl.optJSONObject(i).optInt("i");
                fileRefs.put(fileIdOf(c.key, item, DOC), new Object[]{c.key, item, DOC});
            }
        }
    }

    private static void save(Chan c) throws Fail {
        ChatNode node = GerehIdentity.node();
        if (node == null) {
            throw new Fail("not_started");
        }
        try {
            node.appPut(CHANNEL_KEY + c.key, toJson(c).toString());
            if (!order.contains(c.key)) {
                order.add(c.key);
            }
            node.appPut(LIST_KEY, new JSONArray(order).toString());
        } catch (Throwable t) {
            throw new Fail("store");
        }
    }

    private static JSONObject toJson(Chan c) throws JSONException {
        JSONObject j = new JSONObject();
        j.put("n", c.name).put("title", c.title).put("about", c.about).put("photo", c.photo).put("photoId", c.photoId).put("subs", c.subs);
        j.put("count", c.count).put("files", c.files).put("later", c.later).put("cap", c.capMb).put("every", c.every);
        j.put("next", c.next).put("last", c.last).put("up", c.up).put("down", c.down).put("req", c.requests);
        j.put("read", c.read).put("joined", c.joined).put("pts", c.pts.get());
        j.put("p", new JSONArray(c.posts));
        return j;
    }

    private static Chan fromJson(String key, JSONObject j) {
        Chan c = new Chan(key);
        c.name = j.optString("n", key);
        c.title = j.optString("title");
        c.about = j.optString("about");
        c.photo = j.optString("photo");
        c.photoId = j.optLong("photoId");
        c.subs = j.optLong("subs");
        c.count = j.optInt("count", 50);
        c.files = j.optBoolean("files", true);
        c.later = j.optBoolean("later");
        c.capMb = j.optInt("cap", 5);
        c.every = j.optInt("every");
        c.next = j.optLong("next");
        c.last = j.optLong("last");
        c.up = j.optLong("up");
        c.down = j.optLong("down");
        c.requests = j.optInt("req");
        c.read = j.optInt("read");
        c.joined = j.optInt("joined");
        c.pts.set(Math.max(1, j.optInt("pts", 1)));
        List<JSONObject> posts = new ArrayList<>();
        JSONArray p = j.optJSONArray("p");
        for (int i = 0; p != null && i < p.length(); i++) {
            posts.add(p.optJSONObject(i));
        }
        c.posts = Collections.unmodifiableList(posts);
        return c;
    }

    private static File dir() {
        File d = new File(ApplicationLoader.applicationContext.getFilesDir(), "gereh_tg");
        if (!d.exists()) {
            d.mkdirs();
        }
        return d;
    }

    private static File local(long fileId) {
        return new File(dir(), Long.toString(fileId));
    }

    // ---- ids ------------------------------------------------------------------------------

    static long chatIdOf(String key) {
        byte[] h = sha("mlmchat/tg-channel/1|" + key);
        long v = ((h[0] & 0x7FL) << 24) | ((h[1] & 0xFFL) << 16) | ((h[2] & 0xFFL) << 8) | (h[3] & 0xFFL);
        return ID_BASE + v;
    }

    private static long fileIdOf(String key, int item, int kind) {
        return idOf("mlmchat/tg-file/1|" + key + "|" + item + "|" + kind);
    }

    private static long photoIdOf(String key, String url) {
        int q = url.indexOf('?');
        return idOf("mlmchat/tg-photo/1|" + key + "|" + (q >= 0 ? url.substring(0, q) : url));
    }

    private static long idOf(String s) {
        byte[] h = sha(s);
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (h[i] & 0xFF);
        }
        return Math.max(1, v & Long.MAX_VALUE);
    }

    private static byte[] sha(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- for the screens ------------------------------------------------------------------

    /** A channel's name from what the user wrote (`@name`, `t.me/name`, `https://t.me/s/name`), or null. */
    public static String nameFrom(String input) {
        if (input == null) {
            return null;
        }
        String s = input.trim();
        if (s.startsWith("@")) {
            s = s.substring(1);
        }
        Matcher m = LINK.matcher(s);
        if (m.matches()) {
            s = m.group(1);
        }
        return NAME.matcher(s).matches() ? s : null;
    }

    public static boolean isTg(long dialogId) {
        return dialogId < 0 && byChat.containsKey(-dialogId);
    }

    public static boolean loaded() {
        return loaded;
    }

    public static boolean hasReader() {
        return reader != null;
    }

    /** The chat of a channel already added under this name, or 0. */
    public static long chatOfName(String name) {
        Chan c = channels.get(name.toLowerCase(Locale.ROOT));
        return c != null ? c.chat() : 0;
    }

    public static boolean isBusy(long dialogId) {
        String k = byChat.get(-dialogId);
        return k != null && busy.contains(k);
    }

    public static Options options(long dialogId) {
        Options o = new Options();
        Chan c = chanOf(-dialogId);
        if (c != null) {
            o.count = c.count;
            o.files = c.files;
            o.later = c.later;
            o.capMb = c.capMb;
            o.every = c.every;
        }
        return o;
    }

    /** The channel's settings changed in its menu: kept, and the next check counted from now. */
    public static void setOptions(long dialogId, Options o) {
        Chan c = chanOf(-dialogId);
        if (c == null) {
            return;
        }
        c.files = o.files;
        c.later = o.later;
        c.capMb = o.capMb;
        c.every = o.every <= 0 ? 0 : Math.max(MIN_EVERY, o.every);
        c.next = c.every > 0 ? System.currentTimeMillis() + c.every * 60_000L : 0;
        work.execute(() -> {
            try {
                save(c);
            } catch (Fail f) {
                Log.w(TAG, "tg channel settings not kept: " + f.code);
            }
        });
    }

    /** The totals of a channel: requests, bytes up and down, since it was added. */
    public static long[] usage(long dialogId) {
        Chan c = chanOf(-dialogId);
        return c == null ? new long[3] : new long[]{c.requests, c.up, c.down};
    }

    private static Chan chanOf(long chatId) {
        String k = byChat.get(chatId);
        return k != null ? channels.get(k) : null;
    }

    /** The channel as Telegram's chat object (to open it at once after adding), or null. */
    public static TLRPC.Chat chat(long chatId) {
        Chan ch = chanOf(chatId);
        return ch != null ? chatFor(ch) : null;
    }

    /** The reader script with this phone's key in it, for the user to paste at script.google.com. */
    public static String readerScript(String key) throws Exception {
        try (InputStream in = ApplicationLoader.applicationContext.getAssets().open("gereh_tg.gs")) {
            return new String(readAll(in), StandardCharsets.UTF_8).replace("__GEREH_TG_KEY__", key);
        }
    }

    public static String newKey() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        StringBuilder s = new StringBuilder();
        for (byte x : b) {
            s.append(String.format(Locale.ROOT, "%02x", x));
        }
        return s.toString();
    }

    /** The deployment id from what was pasted: the id itself or the web app's address. */
    public static String deploymentFrom(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = Pattern.compile("/macros/s/([A-Za-z0-9_-]{20,110})").matcher(text);
        String id = m.find() ? m.group(1) : text.trim();
        return id.matches("[A-Za-z0-9_-]{20,110}") ? id : null;
    }

    /**
     * Step 3 of making the reader: asks the script with this key (`ping`); when it answers as a
     * Telegram reader, the deployment and key are kept on this phone.
     */
    public static void testReader(String deployment, String key, Done done) {
        work.execute(() -> {
            Result r = new Result();
            Counter c = new Counter();
            try {
                JSONObject a = call(c, deployment, key, new JSONObject().put("op", "ping"), CALL_S);
                if (!"tg".equals(a.optString("kind"))) {
                    throw new Fail("not_reader");
                }
                JSONObject rd = new JSONObject().put("d", deployment).put("k", key).put("v", a.optInt("ver")).put("at", System.currentTimeMillis());
                ChatNode node = GerehIdentity.node();
                if (node == null) {
                    throw new Fail("not_started");
                }
                node.appPut(READER_KEY, rd.toString());
                reader = rd;
                r.ok = true;
            } catch (Fail f) {
                r.error = f.code;
            } catch (Throwable t) {
                r.error = "bad";
            }
            finish(r, c, done);
        });
    }

    // ---- adding ---------------------------------------------------------------------------

    public static Job add(String name, Options o, Progress pr, Done done) {
        Job job = new Job();
        work.execute(() -> {
            Result r = new Result();
            Counter c = new Counter();
            List<File> made = new ArrayList<>();
            String key = name.toLowerCase(Locale.ROOT);
            busy.add(key);
            try {
                addNow(job, name, o, pr, c, r, made);
                r.ok = true;
            } catch (Fail f) {
                r.error = f.code;
                for (File f2 : made) {
                    f2.delete();
                }
            } catch (Throwable t) {
                Log.e(TAG, "tg channel not added: " + t.getClass().getSimpleName());
                r.error = "bad";
                for (File f2 : made) {
                    f2.delete();
                }
            } finally {
                busy.remove(key);
            }
            finish(r, c, done);
        });
        return job;
    }

    private static void addNow(Job job, String name, Options o, Progress pr, Counter c, Result r, List<File> made) throws Exception {
        String key = name.toLowerCase(Locale.ROOT);
        if (channels.containsKey(key)) {
            r.chatId = channels.get(key).chat();
            throw new Fail("exists");
        }
        progress(pr, STEP_CONNECT, 0, 1, 0, 0);
        JSONObject ping = call(c, new JSONObject().put("op", "ping"), CALL_S);
        if (!"tg".equals(ping.optString("kind"))) {
            throw new Fail("not_reader");
        }
        checkCancel(job);
        int count = Math.max(1, Math.min(100, o.count));
        int pagesWanted = Math.max(1, Math.min(5, (count + 19) / 20));
        progress(pr, STEP_POSTS, 0, count, 0, 0);
        List<GerehTgParse.Page> pages = pages(c, name, 0, 0, pagesWanted, null);
        if (pages.isEmpty()) {
            throw new Fail("telegram");
        }
        GerehTgParse.Page head = pages.get(0);
        TreeMap<Integer, GerehTgParse.Post> got = new TreeMap<>();
        for (GerehTgParse.Page pg : pages) {
            for (GerehTgParse.Post p : pg.posts) {
                got.put(p.id, p);
            }
        }
        while (got.size() > count) {
            got.pollFirstEntry();
        }
        progress(pr, STEP_POSTS, got.size(), count, 0, 0);
        checkCancel(job);
        Chan ch = new Chan(key);
        ch.name = head.name.isEmpty() ? name : head.name;
        ch.title = head.title.isEmpty() ? ch.name : head.title;
        ch.about = head.about;
        ch.photo = head.photo;
        ch.subs = head.subscribers;
        ch.count = count;
        ch.files = o.files;
        ch.later = o.later;
        ch.capMb = o.capMb;
        ch.every = o.every <= 0 ? 0 : Math.max(MIN_EVERY, o.every);
        List<JSONObject> posts = new ArrayList<>();
        for (GerehTgParse.Post p : got.values()) {
            posts.add(postJson(key, p));
        }
        // the channel's own photo, always: it is part of the channel (Ehsan's «همان عکس»)
        progress(pr, STEP_PHOTO, 0, 1, 0, 0);
        if (!ch.photo.isEmpty()) {
            long pid = photoIdOf(key, ch.photo);
            JSONObject md = new JSONObject().put("u", ch.photo).put("f", pid).put("k", 0);
            File f = local(pid);
            made.add(f);
            fetchFiles(job, c, ch, Collections.singletonList(md), 5L * 1024 * 1024, null, 0);
            if (whole.contains(pid)) {
                ch.photoId = pid;
            }
        }
        checkCancel(job);
        List<JSONObject> media = mediaOf(posts);
        if (ch.files && !media.isEmpty()) {
            sizes(job, c, ch, media, pr);
            if (!ch.later) {
                List<JSONObject> now = within(media, ch.capMb * 1024L * 1024L);
                for (JSONObject md : now) {
                    made.add(local(md.optLong("f")));
                }
                r.files = fetchFiles(job, c, ch, now, ch.capMb * 1024L * 1024L, pr, STEP_FILES);
            }
        }
        checkCancel(job);
        progress(pr, STEP_BUILD, 0, 1, 0, 0);
        ch.posts = Collections.unmodifiableList(posts);
        ch.joined = (int) (System.currentTimeMillis() / 1000);
        ch.read = ch.top();
        ch.last = System.currentTimeMillis();
        ch.next = ch.every > 0 ? ch.last + ch.every * 60_000L : 0;
        ch.up = c.up + UP_EXTRA;
        ch.down = c.down;
        ch.requests = c.requests;
        channels.put(key, ch);
        index(ch);
        save(ch);
        r.chatId = ch.chat();
        r.title = ch.title;
        r.posts = posts.size();
        show(ch, null);
    }

    // ---- updating -------------------------------------------------------------------------

    /** «به‌روزرسانی»: posts newer than the newest here, and their files as the channel says. */
    public static void update(long dialogId, Done done) {
        Chan ch = chanOf(-dialogId);
        if (ch == null) {
            return;
        }
        runUpdate(ch, done);
    }

    private static void runUpdate(Chan ch, Done done) {
        if (!busy.add(ch.key)) {
            if (done != null) {
                Result r = new Result();
                r.error = "busy";
                AndroidUtilities.runOnUIThread(() -> done.on(r));
            }
            return;
        }
        work.execute(() -> {
            Result r = new Result();
            Counter c = new Counter();
            try {
                updateNow(ch, c, r);
                r.ok = true;
            } catch (Fail f) {
                r.error = f.code;
            } catch (Throwable t) {
                Log.w(TAG, "tg channel not updated: " + t.getClass().getSimpleName());
                r.error = "bad";
            } finally {
                busy.remove(ch.key);
            }
            ch.up += c.up;
            ch.down += c.down;
            ch.requests += c.requests;
            ch.last = System.currentTimeMillis();
            try {
                save(ch);
            } catch (Fail ignored) {
            }
            if (done != null) {
                finish(r, c, done);
            }
        });
    }

    private static void updateNow(Chan ch, Counter c, Result r) throws Exception {
        int top = ch.top();
        TreeMap<Integer, GerehTgParse.Post> fresh = new TreeMap<>();
        GerehTgParse.Page head = null;
        boolean[] more = {false};
        for (int round = 0; round < 3; round++) {
            List<GerehTgParse.Page> pages = pages(c, ch.name, 0, top, 5, more);
            for (GerehTgParse.Page pg : pages) {
                if (head == null) {
                    head = pg;
                }
                for (GerehTgParse.Post p : pg.posts) {
                    if (p.id > ch.top()) {
                        fresh.put(p.id, p);
                    }
                }
            }
            if (!more[0] || fresh.isEmpty()) {
                break;
            }
            top = fresh.lastKey();
        }
        boolean changed = false;
        if (head != null && !head.title.isEmpty() && (!head.title.equals(ch.title) || !head.about.equals(ch.about))) {
            ch.title = head.title;
            ch.about = head.about;
            changed = true;
        }
        if (head != null && !head.photo.isEmpty() && photoIdOf(ch.key, head.photo) != ch.photoId) {
            long pid = photoIdOf(ch.key, head.photo);
            JSONObject md = new JSONObject().put("u", head.photo).put("f", pid).put("k", 0);
            fetchFiles(new Job(), c, ch, Collections.singletonList(md), 5L * 1024 * 1024, null, 0);
            if (whole.contains(pid)) {
                ch.photo = head.photo;
                ch.photoId = pid;
                fileRefs.put(pid, new Object[]{ch.key, 0, 0});
                changed = true;
            }
        }
        if (head != null && head.subscribers > 0) {
            ch.subs = head.subscribers;
        }
        List<JSONObject> added = new ArrayList<>();
        for (GerehTgParse.Post p : fresh.values()) {
            added.add(postJson(ch.key, p));
        }
        if (!added.isEmpty()) {
            List<JSONObject> media = mediaOf(added);
            if (ch.files && !media.isEmpty()) {
                sizes(new Job(), c, ch, media, null);
                if (!ch.later) {
                    r.files = fetchFiles(new Job(), c, ch, within(media, ch.capMb * 1024L * 1024L), ch.capMb * 1024L * 1024L, null, 0);
                }
            }
            List<JSONObject> all = new ArrayList<>(ch.posts);
            all.addAll(added);
            while (all.size() > KEEP) {
                all.remove(0);
            }
            ch.posts = Collections.unmodifiableList(all);
            Chan indexed = ch;
            index(indexed);
        }
        r.fresh = added.size();
        r.chatId = ch.chat();
        r.title = ch.title;
        if (!added.isEmpty() || changed) {
            show(ch, added);
        }
    }

    /** The timer: one channel whose time has come, each minute. */
    private static void tick() {
        if (!loaded || reader == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (String key : order) {
            Chan ch = channels.get(key);
            if (ch == null || ch.every <= 0 || busy.contains(key) || ch.next > now) {
                continue;
            }
            ch.next = now + Math.max(MIN_EVERY, ch.every) * 60_000L;
            Log.i(TAG, "tg channel: timed check");
            runUpdate(ch, null);
            return;
        }
    }

    // ---- search (q6, T70) -----------------------------------------------------------------

    /**
     * Telegram's public channels and groups for q, through the user's own script (its tgs, from
     * VERSION 2): r, each [name, title, members as the page writes them, "t" Telegram or "d" tg.me,
     * its small photo in base64], and ver, the script's VERSION (none before 4, whose search brings
     * every photo). Fails "no_reader" without a script, and with the script's own answer after
     * "script_" ("script_bad" from a script made before the search, "script_quota" when Google's
     * day is used up).
     */
    static JSONObject search(String q, java.util.Collection<String> havePhotos) throws Exception {
        JSONObject body = new JSONObject().put("op", "tgs").put("q", q);
        if (!havePhotos.isEmpty()) {
            body.put("h", new JSONArray(havePhotos));
        }
        JSONObject a = call(new Counter(), body, SEARCH_S);
        if (a.has("e")) {
            throw new Fail("script_" + a.optString("e"));
        }
        if (a.optJSONArray("r") == null) {
            a.put("r", new JSONArray());
        }
        return a;
    }

    // ---- the script -----------------------------------------------------------------------

    private static JSONObject call(Counter c, JSONObject body, int timeoutS) throws Fail {
        JSONObject rd = reader;
        if (rd == null) {
            throw new Fail("no_reader");
        }
        return call(c, rd.optString("d"), rd.optString("k"), body, timeoutS);
    }

    private static JSONObject call(Counter c, String deployment, String key, JSONObject body, int timeoutS) throws Fail {
        ChatNode node = GerehIdentity.node();
        if (node == null) {
            throw new Fail("not_started");
        }
        c.requests++;
        try {
            body.put("k", key);
            JSONObject r = node.gasScriptCall(deployment, body, timeoutS);
            c.up += r.optLong("up") + UP_EXTRA;
            c.down += r.optLong("down") + DOWN_EXTRA;
            JSONObject a = r.optJSONObject("r");
            if (a == null) {
                throw new Fail("bad_response");
            }
            return a;
        } catch (Fail f) {
            throw f;
        } catch (Throwable t) {
            c.up += body.toString().length() + UP_EXTRA;
            String m = t.getMessage() != null ? t.getMessage() : "";
            throw new Fail(m.startsWith("relay: ") ? m.substring(7) : "bad");
        }
    }

    /** Pages of the channel, read on this phone. `more`, when given, says whether the walk stopped at its page count. */
    private static List<GerehTgParse.Page> pages(Counter c, String name, int before, int after, int count, boolean[] more) throws Exception {
        JSONObject body = new JSONObject().put("op", "tg").put("n", name).put("p", count);
        if (before > 0) {
            body.put("b", before);
        }
        if (after > 0) {
            body.put("a", after);
        }
        JSONObject r = call(c, body, CALL_S);
        int s = r.optInt("s");
        if (s >= 300 && s < 500) {
            throw new Fail("not_public");
        }
        if (s != 200) {
            throw new Fail("telegram");
        }
        if (more != null) {
            more[0] = r.optInt("more") == 1;
        }
        List<GerehTgParse.Page> out = new ArrayList<>();
        JSONArray z = r.optJSONArray("z");
        for (int i = 0; z != null && i < z.length(); i++) {
            byte[] gz = Base64.decode(z.optString(i), Base64.DEFAULT);
            try (InputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(gz))) {
                out.add(GerehTgParse.page(new String(readAll(in), StandardCharsets.UTF_8)));
            }
        }
        return out;
    }

    private static JSONObject postJson(String key, GerehTgParse.Post p) throws JSONException {
        JSONObject j = new JSONObject();
        j.put("i", p.id).put("d", p.date).put("t", p.text);
        if (!p.ents.isEmpty()) {
            JSONArray e = new JSONArray();
            for (GerehTgParse.Ent x : p.ents) {
                JSONArray one = new JSONArray().put(x.type).put(x.off).put(x.len);
                if (x.url != null) {
                    one.put(x.url);
                }
                e.put(one);
            }
            j.put("e", e);
        }
        if (!p.author.isEmpty()) {
            j.put("a", p.author);
        }
        if (p.fwd != null) {
            j.put("f", p.fwd);
        }
        if (p.reply > 0) {
            j.put("r", p.reply);
        }
        if (p.views > 0) {
            j.put("v", p.views);
        }
        if (p.edited) {
            j.put("ed", 1);
        }
        if (!p.extra.isEmpty()) {
            j.put("x", p.extra);
        }
        if (p.docs > 0) {
            j.put("dc", p.docs);
        }
        if (p.tooBig > 0) {
            j.put("tb", p.tooBig);
        }
        if (!p.files.isEmpty()) {
            JSONArray dl = new JSONArray();
            for (GerehTgParse.Doc d : p.files) {
                dl.put(new JSONObject().put("n", d.name).put("z", d.size).put("i", d.item));
            }
            j.put("dl", dl);
        }
        if (!p.media.isEmpty()) {
            JSONArray m = new JSONArray();
            for (GerehTgParse.Media md : p.media) {
                m.put(new JSONObject().put("k", md.kind).put("i", md.item).put("u", md.url).put("th", md.thumb)
                        .put("w", md.w).put("h", md.h).put("du", md.dur).put("f", fileIdOf(key, md.item, md.kind)));
            }
            j.put("m", m);
        }
        return j;
    }

    private static List<JSONObject> mediaOf(List<JSONObject> posts) {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject p : posts) {
            JSONArray m = p.optJSONArray("m");
            for (int i = 0; m != null && i < m.length(); i++) {
                out.add(m.optJSONObject(i));
            }
        }
        return out;
    }

    private static List<JSONObject> within(List<JSONObject> media, long cap) {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject md : media) {
            long s = md.optLong("s");
            if (s == 0 && md.optInt("k") == GerehTgParse.PHOTO || s > 0 && s <= cap) {
                out.add(md);
            }
        }
        return out;
    }

    /** The whole size of each video and voice note (8 a call, nothing downloaded): for the limit and the screens. */
    private static void sizes(Job job, Counter c, Chan ch, List<JSONObject> media, Progress pr) throws Exception {
        List<JSONObject> ask = new ArrayList<>();
        for (JSONObject md : media) {
            if (md.optInt("k") != GerehTgParse.PHOTO && md.optLong("s") == 0) {
                ask.add(md);
            }
        }
        for (int i = 0; i < ask.size(); i += 8) {
            checkCancel(job);
            progress(pr, STEP_SIZES, i, ask.size(), 0, 0);
            List<JSONObject> part = ask.subList(i, Math.min(ask.size(), i + 8));
            JSONArray items = new JSONArray();
            for (JSONObject md : part) {
                items.put(new JSONArray().put(md.optString("u")).put(0).put(0));
            }
            JSONObject r = call(c, new JSONObject().put("op", "tgf").put("i", items), FILE_S);
            JSONArray res = r.optJSONArray("r");
            for (int k = 0; res != null && k < res.length() && k < part.size(); k++) {
                JSONArray one = res.optJSONArray(k);
                if (one != null && (one.optInt(0) == 200 || one.optInt(0) == 206)) {
                    part.get(k).put("s", one.optLong(1));
                }
            }
        }
        progress(pr, STEP_SIZES, ask.size(), ask.size(), 0, 0);
    }

    /**
     * Downloads these files whole into this phone's private files, 8 pieces and 2 MB a call; a file
     * that turns out bigger than `cap` is left. A link Telegram no longer takes (its token ran
     * out) is read again from its post's page, once. Returns how many files are whole.
     */
    private static int fetchFiles(Job job, Counter c, Chan ch, List<JSONObject> media, long cap, Progress pr, int step) throws Exception {
        List<JSONObject> left = new ArrayList<>();
        long total = 0;
        for (JSONObject md : media) {
            if (!whole.contains(md.optLong("f"))) {
                left.add(md);
                total += md.optLong("s");
            }
        }
        int n = media.size();
        int done = n - left.size();
        long bytes = 0;
        Map<Long, Long> at = new java.util.HashMap<>();
        Set<Long> refreshed = new HashSet<>();
        Map<Long, Integer> fails = new java.util.HashMap<>();
        progress(pr, step, done, n, 0, total);
        while (!left.isEmpty()) {
            checkCancel(job);
            JSONArray items = new JSONArray();
            List<JSONObject> batch = new ArrayList<>();
            long budget = PIECE;
            for (JSONObject md : left) {
                if (batch.size() == 8 || budget <= 0) {
                    break;
                }
                long id = md.optLong("f");
                long from = at.containsKey(id) ? at.get(id) : 0;
                long size = md.optLong("s");
                long want = size > 0 ? size - from : PIECE;
                long take = Math.min(Math.min(want, budget), PIECE);
                if (take <= 0) {
                    continue;
                }
                items.put(new JSONArray().put(md.optString("u")).put(from).put(from + take - 1));
                batch.add(md);
                budget -= take;
            }
            if (batch.isEmpty()) {
                break;
            }
            JSONObject r = call(c, new JSONObject().put("op", "tgf").put("i", items), FILE_S);
            JSONArray res = r.optJSONArray("r");
            for (int k = 0; k < batch.size(); k++) {
                JSONObject md = batch.get(k);
                long id = md.optLong("f");
                JSONArray one = res != null ? res.optJSONArray(k) : null;
                int s = one != null ? one.optInt(0) : 0;
                if (s == 200 || s == 206) {
                    byte[] b = Base64.decode(one.optString(3), Base64.DEFAULT);
                    long whole0 = one.optLong(1);
                    long from = at.containsKey(id) ? at.get(id) : 0;
                    if (whole0 > 0) {
                        md.put("s", whole0);
                    }
                    if (whole0 > cap) {
                        left.remove(md);
                        new File(dir(), id + ".part").delete();
                        continue;
                    }
                    File part = new File(dir(), id + ".part");
                    try (RandomAccessFile f = new RandomAccessFile(part, "rw")) {
                        f.seek(from);
                        f.write(b);
                    }
                    long now = from + b.length;
                    at.put(id, now);
                    bytes += b.length;
                    if (b.length == 0 || whole0 > 0 && now >= whole0 || whole0 == 0 && s == 200) {
                        part.renameTo(local(id));
                        whole.add(id);
                        left.remove(md);
                        done++;
                    }
                } else if ((s == 403 || s == 404 || s == 410) && refreshed.add(id)) {
                    String fresh = freshUrl(c, ch, md.optInt("i"), md.optInt("k"));
                    if (fresh != null) {
                        md.put("u", fresh);
                    } else {
                        left.remove(md);
                    }
                } else {
                    int f = fails.containsKey(id) ? fails.get(id) + 1 : 1;
                    fails.put(id, f);
                    if (f >= 2) {
                        left.remove(md);
                    }
                }
            }
            progress(pr, step, done, n, bytes, Math.max(total, bytes));
        }
        return done;
    }

    /** A file's link from its post's page again (Telegram's links to videos carry a token that runs out). */
    private static String freshUrl(Counter c, Chan ch, int item, int kind) {
        try {
            if (kind == 0) {
                List<GerehTgParse.Page> p = pages(c, ch.name, 0, 0, 1, null);
                return p.isEmpty() || p.get(0).photo.isEmpty() ? null : p.get(0).photo;
            }
            for (GerehTgParse.Page pg : pages(c, ch.name, item + 1, 0, 1, null)) {
                for (GerehTgParse.Post p : pg.posts) {
                    for (GerehTgParse.Media md : p.media) {
                        if (md.item == item && md.kind == kind) {
                            return md.url;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ---- Telegram's screens ---------------------------------------------------------------

    /** The new channel, or new posts, into Telegram's list: as updates, the way a server sends them. */
    private static void show(Chan ch, List<JSONObject> added) {
        List<JSONObject> posts = added == null ? (ch.posts.isEmpty() ? Collections.emptyList() : ch.posts.subList(ch.posts.size() - 1, ch.posts.size())) : added;
        ArrayList<TLRPC.Message> msgs = new ArrayList<>();
        for (JSONObject p : posts) {
            msgs.addAll(messagesOf(ch, p));
        }
        TLRPC.Chat chat = chatFor(ch);
        Utilities.stageQueue.postRunnable(() -> {
            MessagesController mc = MessagesController.getInstance(Messenger.account());
            TLRPC.TL_updates ups = new TLRPC.TL_updates();
            if (msgs.isEmpty()) {
                TL_update.TL_updateChannel uc = new TL_update.TL_updateChannel();
                uc.channel_id = chat.id;
                ups.updates.add(uc);
            }
            for (TLRPC.Message m : msgs) {
                TL_update.TL_updateNewChannelMessage u = new TL_update.TL_updateNewChannelMessage();
                u.message = m;
                u.pts = ch.pts.incrementAndGet();
                u.pts_count = 1;
                ups.updates.add(u);
            }
            ups.chats.add(chat);
            ups.date = (int) (System.currentTimeMillis() / 1000);
            ups.seq = 0;
            mc.processUpdates(ups, false);
        });
    }

    static TLRPC.Chat chatFor(Chan ch) {
        TLRPC.TL_channel c = new TLRPC.TL_channel();
        c.id = ch.chat();
        c.access_hash = c.id; // not 0, or Telegram never asks for the dialog (see GerehChannels.channelFor)
        c.title = ch.title;
        c.photo = GerehPhotos.chatPhotoOf(ch.photoId != 0 && whole.contains(ch.photoId) ? ch.photoId : 0);
        c.date = ch.joined;
        c.broadcast = true;
        c.left = false;
        c.participants_count = (int) Math.min(Integer.MAX_VALUE, ch.subs);
        c.flags |= 1 << 17;
        return c;
    }

    /** A post as Telegram's messages: one, or one for each item of an album (its own id there too). */
    private static List<TLRPC.Message> messagesOf(Chan ch, JSONObject p) {
        List<TLRPC.Message> out = mediaMessagesOf(ch, p);
        List<TL_keyboard.KeyboardInlineButtonRow> get = docs(ch, p, out);
        for (TLRPC.Message m : out) {
            // configs in the post or in a file of it that came: «سنجش» and «انتقال», as in Gereh's own
            // chats (n5; Ehsan, 31 Oct 2026: «برای کانال های افزوده شده تلگرامی هم بیاد»)
            GerehConfigs.dress(m);
        }
        if (!get.isEmpty() && !out.isEmpty()) {
            TLRPC.Message first = out.get(0);
            TLRPC.TL_replyInlineMarkup markup = first.reply_markup instanceof TLRPC.TL_replyInlineMarkup
                    ? (TLRPC.TL_replyInlineMarkup) first.reply_markup : new TLRPC.TL_replyInlineMarkup();
            markup.rows.addAll(get);
            first.reply_markup = markup;
            first.flags |= 64;
        }
        return out;
    }

    private static List<TLRPC.Message> mediaMessagesOf(Chan ch, JSONObject p) {
        List<TLRPC.Message> out = new ArrayList<>();
        JSONArray m = ch.files ? p.optJSONArray("m") : null;
        int n = m != null ? m.length() : 0;
        Set<Integer> items = new HashSet<>();
        for (int i = 0; i < n; i++) {
            items.add(m.optJSONObject(i).optInt("i"));
        }
        if (n <= 1 || items.size() != n) {
            TLRPC.TL_message msg = base(ch, p, p.optInt("i"));
            text(ch, p, msg, n > 1 ? 1 : 0);
            if (n > 0) {
                media(msg, m.optJSONObject(0));
            }
            out.add(msg);
            return out;
        }
        long group = idOf("mlmchat/tg-album/1|" + ch.key + "|" + p.optInt("i"));
        for (int i = 0; i < n; i++) {
            JSONObject md = m.optJSONObject(i);
            TLRPC.TL_message msg = base(ch, p, md.optInt("i"));
            if (i == 0) {
                text(ch, p, msg, 0);
            }
            media(msg, md);
            msg.grouped_id = group;
            msg.flags |= 131072;
            out.add(msg);
        }
        return out;
    }

    private static TLRPC.TL_message base(Chan ch, JSONObject p, int id) {
        TLRPC.TL_message msg = new TLRPC.TL_message();
        msg.id = id;
        TLRPC.TL_peerChannel peer = new TLRPC.TL_peerChannel();
        peer.channel_id = ch.chat();
        msg.peer_id = peer;
        msg.post = true;
        msg.date = p.optInt("d");
        msg.dialog_id = -ch.chat();
        msg.message = "";
        long v = p.optLong("v");
        if (v > 0) {
            msg.views = (int) Math.min(Integer.MAX_VALUE, v);
            msg.flags |= TLRPC.MESSAGE_FLAG_HAS_VIEWS;
        }
        String a = p.optString("a");
        if (!a.isEmpty()) {
            msg.post_author = a;
            msg.flags |= 65536;
        }
        if (p.has("f")) {
            TLRPC.TL_messageFwdHeader fh = new TLRPC.TL_messageFwdHeader();
            fh.from_name = p.optString("f").isEmpty() ? "…" : p.optString("f");
            fh.flags |= 32;
            fh.date = msg.date;
            msg.fwd_from = fh;
            msg.flags |= TLRPC.MESSAGE_FLAG_FWD;
        }
        int reply = p.optInt("r");
        if (reply > 0) {
            Messenger.setReply(msg, reply);
        }
        if (p.optInt("ed") == 1) {
            msg.edit_date = msg.date;
            msg.flags |= TLRPC.MESSAGE_FLAG_EDITED;
        }
        return msg;
    }

    /** The post's text with its formatting, and lines for what the web page does not carry. */
    private static void text(Chan ch, JSONObject p, TLRPC.TL_message msg, int moreItems) {
        StringBuilder t = new StringBuilder(p.optString("t"));
        int len = t.length();
        StringBuilder notes = new StringBuilder();
        String x = p.optString("x");
        if (!x.isEmpty()) {
            notes.append(x);
            if (p.optInt("dc") > 0) {
                notes.append('\n').append(LocaleController.getString(R.string.GerehTgOnlyInTelegram));
            }
        }
        if (p.optInt("tb") > 0) {
            if (notes.length() > 0) {
                notes.append('\n');
            }
            notes.append("🎬 ").append(LocaleController.getString(R.string.GerehTgVideoTooBig));
        }
        if (!ch.files) {
            JSONArray m = p.optJSONArray("m");
            for (int i = 0; m != null && i < m.length(); i++) {
                if (notes.length() > 0) {
                    notes.append('\n');
                }
                notes.append(kindLine(m.optJSONObject(i)));
            }
        }
        JSONArray dl = p.optJSONArray("dl");
        for (int i = 0; dl != null && i < dl.length(); i++) {
            JSONObject d = dl.optJSONObject(i);
            if (!hasDoc(ch, d.optInt("i"))) {
                // a file not here yet: its name and size; a button under the post gets it (T71)
                if (notes.length() > 0) {
                    notes.append('\n');
                }
                notes.append("📎 ").append(d.optString("n")).append(d.optString("z").isEmpty() ? "" : " · " + d.optString("z"));
            }
        }
        if (notes.length() > 0) {
            if (t.length() > 0) {
                t.append("\n\n");
            }
            t.append(notes);
        }
        msg.message = t.toString();
        JSONArray e = p.optJSONArray("e");
        for (int i = 0; e != null && i < e.length(); i++) {
            JSONArray one = e.optJSONArray(i);
            int off = one.optInt(1);
            int l = Math.min(one.optInt(2), len - off);
            if (off < 0 || l <= 0) {
                continue;
            }
            TLRPC.MessageEntity ent = entity(one.optInt(0), one.optString(3, ""));
            if (ent != null) {
                ent.offset = off;
                ent.length = l;
                msg.entities.add(ent);
            }
        }
        if (!msg.entities.isEmpty()) {
            msg.flags |= TLRPC.MESSAGE_FLAG_HAS_ENTITIES;
        }
    }

    private static String kindLine(JSONObject md) {
        int k = md.optInt("k");
        int du = md.optInt("du");
        String d = du > 0 ? " " + (du / 60) + ":" + String.format(Locale.ROOT, "%02d", du % 60) : "";
        if (k == GerehTgParse.PHOTO) {
            return "🖼 " + LocaleController.getString(R.string.GerehTgKindPhoto);
        }
        if (k == GerehTgParse.VOICE) {
            return "🎤 " + LocaleController.getString(R.string.GerehTgKindVoice) + d;
        }
        return "🎬 " + LocaleController.getString(R.string.GerehTgKindVideo) + d;
    }

    private static TLRPC.MessageEntity entity(int type, String url) {
        switch (type) {
            case GerehTgParse.BOLD: return new TLRPC.TL_messageEntityBold();
            case GerehTgParse.ITALIC: return new TLRPC.TL_messageEntityItalic();
            case GerehTgParse.UNDERLINE: return new TLRPC.TL_messageEntityUnderline();
            case GerehTgParse.STRIKE: return new TLRPC.TL_messageEntityStrike();
            case GerehTgParse.CODE: return new TLRPC.TL_messageEntityCode();
            case GerehTgParse.PRE: {
                TLRPC.TL_messageEntityPre e = new TLRPC.TL_messageEntityPre();
                e.language = "";
                return e;
            }
            case GerehTgParse.SPOILER: return new TLRPC.TL_messageEntitySpoiler();
            case GerehTgParse.QUOTE: return new TLRPC.TL_messageEntityBlockquote();
            case GerehTgParse.TEXT_URL: {
                if (url.isEmpty()) {
                    return null;
                }
                TLRPC.TL_messageEntityTextUrl e = new TLRPC.TL_messageEntityTextUrl();
                e.url = url;
                return e;
            }
            default:
                return null;
        }
    }

    private static void media(TLRPC.TL_message msg, JSONObject md) {
        long id = md.optLong("f");
        int kind = md.optInt("k");
        long size = md.optLong("s");
        if (kind == GerehTgParse.PHOTO) {
            TLRPC.TL_photo p = new TLRPC.TL_photo();
            p.id = id;
            p.access_hash = 0;
            p.file_reference = new byte[]{0};
            p.date = msg.date;
            p.dc_id = 2;
            TLRPC.TL_photoSize s = new TLRPC.TL_photoSize();
            s.type = "y";
            s.w = Math.max(1, md.optInt("w"));
            s.h = Math.max(1, md.optInt("h"));
            s.size = (int) Math.min(Integer.MAX_VALUE, size);
            p.sizes.add(s);
            TLRPC.TL_messageMediaPhoto mm = new TLRPC.TL_messageMediaPhoto();
            mm.photo = GerehMedia.roundTripPhoto(p);
            mm.flags |= 1;
            msg.media = mm;
        } else {
            TLRPC.TL_document d = new TLRPC.TL_document();
            d.id = id;
            d.access_hash = 0;
            d.file_reference = new byte[]{0};
            d.date = msg.date;
            d.mime_type = kind == GerehTgParse.VOICE ? "audio/ogg" : "video/mp4";
            d.size = size;
            d.dc_id = 2;
            if (kind == GerehTgParse.VOICE) {
                TLRPC.TL_documentAttributeAudio a = new TLRPC.TL_documentAttributeAudio();
                a.voice = true;
                a.duration = md.optInt("du");
                a.flags |= 1024;
                d.attributes.add(a);
            } else {
                TLRPC.TL_documentAttributeVideo v = new TLRPC.TL_documentAttributeVideo();
                v.round_message = kind == GerehTgParse.ROUND;
                v.supports_streaming = false;
                v.duration = md.optInt("du");
                v.w = Math.max(1, md.optInt("w"));
                v.h = Math.max(1, md.optInt("h"));
                d.attributes.add(v);
            }
            TLRPC.TL_documentAttributeFilename f = new TLRPC.TL_documentAttributeFilename();
            f.file_name = kind == GerehTgParse.VOICE ? "voice.ogg" : "video.mp4";
            d.attributes.add(f);
            TLRPC.TL_messageMediaDocument mm = new TLRPC.TL_messageMediaDocument();
            mm.document = GerehMedia.roundTripDocument(d);
            mm.flags |= 1;
            msg.media = mm;
        }
        msg.flags |= TLRPC.MESSAGE_FLAG_HAS_MEDIA;
    }

    private static List<TLRPC.Message> allMessages(Chan ch) {
        List<TLRPC.Message> all = new ArrayList<>();
        for (JSONObject p : ch.posts) {
            all.addAll(messagesOf(ch, p));
        }
        Collections.sort(all, (x, y) -> Integer.compare(y.id, x.id));
        return all;
    }

    private static TLRPC.TL_dialog dialogFor(Chan ch, ArrayList<TLRPC.Message> messages) {
        List<TLRPC.Message> all = allMessages(ch);
        TLRPC.TL_dialog d = new TLRPC.TL_dialog();
        TLRPC.TL_peerChannel pc = new TLRPC.TL_peerChannel();
        pc.channel_id = ch.chat();
        d.peer = pc;
        d.top_message = all.isEmpty() ? 0 : all.get(0).id;
        d.read_inbox_max_id = ch.read;
        int unread = 0;
        for (TLRPC.Message m : all) {
            if (m.id > ch.read) {
                unread++;
            }
        }
        d.unread_count = unread;
        d.pts = ch.pts.get();
        d.flags |= 1;
        d.notify_settings = GerehNotify.of(-pc.channel_id);
        if (messages != null && !all.isEmpty()) {
            messages.add(all.get(0));
        }
        return d;
    }

    /** The channels in the dialog list (all, or those in `only`). */
    static void addDialogs(ArrayList<TLRPC.Dialog> dialogs, ArrayList<TLRPC.Message> messages, ArrayList<TLRPC.Chat> chats, Set<Long> only) {
        for (String key : order) {
            Chan ch = channels.get(key);
            if (ch == null || only != null && !only.contains(ch.chat())) {
                continue;
            }
            try {
                chats.add(chatFor(ch));
                dialogs.add(dialogFor(ch, messages));
            } catch (Throwable t) {
                Log.w(TAG, "tg channel dialog: " + t.getClass().getSimpleName());
            }
        }
    }

    /** The channel a dialog request names, when it is one of these. */
    static long chatOf(TLRPC.InputPeer p) {
        long id = GerehChannels.channelOf(p);
        return id != 0 && byChat.containsKey(id) ? id : 0;
    }

    private static boolean ours(TLRPC.InputChannel c) {
        return byChat.containsKey(GerehChannels.channelOf(c));
    }

    private static boolean ours(TLRPC.InputPeer p) {
        return byChat.containsKey(GerehChannels.channelOf(p));
    }

    static boolean handles(TLObject req) {
        if (byChat.isEmpty()) {
            return false;
        }
        if (req instanceof TLRPC.TL_channels_getChannels) {
            for (TLRPC.InputChannel ic : ((TLRPC.TL_channels_getChannels) req).id) {
                if (ours(ic)) {
                    return true;
                }
            }
            return false;
        }
        return req instanceof TLRPC.TL_channels_getFullChannel && ours(((TLRPC.TL_channels_getFullChannel) req).channel)
                || req instanceof TLRPC.TL_channels_getParticipants && ours(((TLRPC.TL_channels_getParticipants) req).channel)
                || req instanceof TLRPC.TL_channels_getParticipant && ours(((TLRPC.TL_channels_getParticipant) req).channel)
                || req instanceof TLRPC.TL_channels_leaveChannel && ours(((TLRPC.TL_channels_leaveChannel) req).channel)
                || req instanceof TLRPC.TL_channels_readHistory && ours(((TLRPC.TL_channels_readHistory) req).channel)
                || req instanceof TLRPC.TL_channels_deleteMessages && ours(((TLRPC.TL_channels_deleteMessages) req).channel)
                || req instanceof TLRPC.TL_channels_getMessages && ours(((TLRPC.TL_channels_getMessages) req).channel)
                || req instanceof TLRPC.TL_updates_getChannelDifference && ours(((TLRPC.TL_updates_getChannelDifference) req).channel)
                || req instanceof TLRPC.TL_messages_getHistory && ours(((TLRPC.TL_messages_getHistory) req).peer)
                || req instanceof TLRPC.TL_messages_getMessagesViews && ours(((TLRPC.TL_messages_getMessagesViews) req).peer)
                || req instanceof TLRPC.TL_messages_sendMessage && ours(((TLRPC.TL_messages_sendMessage) req).peer)
                || req instanceof TLRPC.TL_messages_sendMedia && ours(((TLRPC.TL_messages_sendMedia) req).peer);
    }

    static TLObject answer(int acc, TLObject req, RequestDelegate done) throws Exception {
        if (req instanceof TLRPC.TL_channels_getChannels) {
            TLRPC.TL_messages_chats r = new TLRPC.TL_messages_chats();
            for (TLRPC.InputChannel ic : ((TLRPC.TL_channels_getChannels) req).id) {
                Chan ch = chanOf(GerehChannels.channelOf(ic));
                if (ch != null) {
                    r.chats.add(chatFor(ch));
                }
            }
            return r;
        }
        if (req instanceof TLRPC.TL_channels_getFullChannel) {
            Chan ch = need(GerehChannels.channelOf(((TLRPC.TL_channels_getFullChannel) req).channel));
            TLRPC.TL_channelFull f = new TLRPC.TL_channelFull();
            f.id = ch.chat();
            f.about = ch.about;
            f.participants_count = (int) Math.min(Integer.MAX_VALUE, ch.subs);
            f.flags |= 1;
            f.read_inbox_max_id = ch.read;
            f.read_outbox_max_id = Integer.MAX_VALUE;
            f.chat_photo = ch.photoId != 0 && whole.contains(ch.photoId) ? GerehPhotos.photo(ch.photoId, null) : new TLRPC.TL_photoEmpty();
            f.notify_settings = GerehNotify.of(-f.id);
            f.pts = ch.pts.get();
            f.can_view_participants = false;
            TLRPC.TL_messages_chatFull r = new TLRPC.TL_messages_chatFull();
            r.full_chat = f;
            r.chats.add(chatFor(ch));
            return r;
        }
        if (req instanceof TLRPC.TL_channels_getParticipants) {
            Chan ch = need(GerehChannels.channelOf(((TLRPC.TL_channels_getParticipants) req).channel));
            TLRPC.TL_channels_channelParticipants r = new TLRPC.TL_channels_channelParticipants();
            r.count = (int) Math.min(Integer.MAX_VALUE, ch.subs);
            return r;
        }
        if (req instanceof TLRPC.TL_channels_getParticipant) {
            Chan ch = need(GerehChannels.channelOf(((TLRPC.TL_channels_getParticipant) req).channel));
            TLRPC.TL_channels_channelParticipant r = new TLRPC.TL_channels_channelParticipant();
            TLRPC.TL_channelParticipantSelf p = new TLRPC.TL_channelParticipantSelf();
            TLRPC.TL_peerUser pu = new TLRPC.TL_peerUser();
            pu.user_id = UserConfig.getInstance(acc).getClientUserId();
            p.peer = pu;
            // a day back: for a membership under a day old Telegram writes «… added you»
            p.date = ch.joined - 86_400;
            r.participant = p;
            r.users.add(UserConfig.getInstance(acc).getCurrentUser());
            return r;
        }
        if (req instanceof TLRPC.TL_channels_leaveChannel) {
            Chan ch = need(GerehChannels.channelOf(((TLRPC.TL_channels_leaveChannel) req).channel));
            remove(ch);
            TLRPC.Chat chat = chatFor(ch);
            chat.left = true;
            TLRPC.TL_updates ups = new TLRPC.TL_updates();
            ups.chats.add(chat);
            ups.date = (int) (System.currentTimeMillis() / 1000);
            ups.seq = 0;
            return ups;
        }
        if (req instanceof TLRPC.TL_channels_readHistory) {
            TLRPC.TL_channels_readHistory q = (TLRPC.TL_channels_readHistory) req;
            Chan ch = need(GerehChannels.channelOf(q.channel));
            if (q.max_id > ch.read) {
                ch.read = q.max_id;
                work.execute(() -> {
                    try {
                        save(ch);
                    } catch (Fail ignored) {
                    }
                });
            }
            return new TLRPC.TL_boolTrue();
        }
        if (req instanceof TLRPC.TL_channels_deleteMessages) {
            TLRPC.TL_channels_deleteMessages q = (TLRPC.TL_channels_deleteMessages) req;
            Chan ch = need(GerehChannels.channelOf(q.channel));
            Set<Integer> gone = new HashSet<>(q.id);
            List<JSONObject> keep = new ArrayList<>();
            for (JSONObject p : ch.posts) {
                boolean drop = gone.contains(p.optInt("i"));
                JSONArray m = p.optJSONArray("m");
                for (int i = 0; !drop && m != null && i < m.length(); i++) {
                    drop = gone.contains(m.optJSONObject(i).optInt("i"));
                }
                if (!drop) {
                    keep.add(p);
                }
            }
            ch.posts = Collections.unmodifiableList(keep);
            work.execute(() -> {
                try {
                    save(ch);
                } catch (Fail ignored) {
                }
            });
            TLRPC.TL_messages_affectedMessages r = new TLRPC.TL_messages_affectedMessages();
            r.pts = ch.pts.addAndGet(q.id.size());
            r.pts_count = q.id.size();
            return r;
        }
        if (req instanceof TLRPC.TL_channels_getMessages) {
            TLRPC.TL_channels_getMessages q = (TLRPC.TL_channels_getMessages) req;
            Chan ch = need(GerehChannels.channelOf(q.channel));
            Set<Integer> want = new HashSet<>(q.id);
            TLRPC.TL_messages_channelMessages r = new TLRPC.TL_messages_channelMessages();
            for (TLRPC.Message m : allMessages(ch)) {
                if (want.contains(m.id)) {
                    r.messages.add(m);
                }
            }
            r.count = r.messages.size();
            r.pts = ch.pts.get();
            r.chats.add(chatFor(ch));
            return r;
        }
        if (req instanceof TLRPC.TL_updates_getChannelDifference) {
            Chan ch = need(GerehChannels.channelOf(((TLRPC.TL_updates_getChannelDifference) req).channel));
            TLRPC.TL_updates_channelDifferenceEmpty e = new TLRPC.TL_updates_channelDifferenceEmpty();
            e.pts = ch.pts.get();
            e.isFinal = true;
            e.flags |= 2;
            e.timeout = 60;
            return e;
        }
        if (req instanceof TLRPC.TL_messages_getHistory) {
            TLRPC.TL_messages_getHistory q = (TLRPC.TL_messages_getHistory) req;
            Chan ch = need(GerehChannels.channelOf(q.peer));
            List<TLRPC.Message> all = allMessages(ch);
            TLRPC.TL_messages_channelMessages r = new TLRPC.TL_messages_channelMessages();
            r.messages.addAll(Messenger.pageOf(all, q.offset_id, q.add_offset, q.limit, q.max_id, q.min_id));
            r.count = all.size();
            r.pts = ch.pts.get();
            r.chats.add(chatFor(ch));
            return r;
        }
        if (req instanceof TLRPC.TL_messages_getMessagesViews) {
            TLRPC.TL_messages_getMessagesViews q = (TLRPC.TL_messages_getMessagesViews) req;
            Chan ch = need(GerehChannels.channelOf(q.peer));
            Map<Integer, Integer> views = new java.util.HashMap<>();
            for (TLRPC.Message m : allMessages(ch)) {
                views.put(m.id, m.views);
            }
            TLRPC.TL_messages_messageViews r = new TLRPC.TL_messages_messageViews();
            for (int id : q.id) {
                TLRPC.TL_messageViews v = new TLRPC.TL_messageViews();
                Integer n = views.get(id);
                if (n != null && n > 0) {
                    v.views = n;
                    v.flags |= 1;
                }
                r.views.add(v);
            }
            r.chats.add(chatFor(ch));
            return r;
        }
        // nobody posts in a copy of someone else's channel
        throw new Messenger.Refusal(LocaleController.getString(R.string.GerehTgReadOnly));
    }

    private static Chan need(long chatId) {
        Chan ch = chanOf(chatId);
        if (ch == null) {
            throw new IllegalArgumentException("unknown channel");
        }
        return ch;
    }

    /** The user left the channel: it and its files go from this phone. */
    private static void remove(Chan ch) {
        channels.remove(ch.key);
        order.remove(ch.key);
        byChat.remove(ch.chat());
        work.execute(() -> {
            ChatNode node = GerehIdentity.node();
            List<Long> ids = new ArrayList<>();
            for (Map.Entry<Long, Object[]> e : fileRefs.entrySet()) {
                if (ch.key.equals(e.getValue()[0])) {
                    ids.add(e.getKey());
                }
            }
            for (long id : ids) {
                fileRefs.remove(id);
                whole.remove(id);
                local(id).delete();
                new File(dir(), id + ".part").delete();
            }
            try {
                if (node != null) {
                    node.appPut(CHANNEL_KEY + ch.key, null);
                    node.appPut(LIST_KEY, new JSONArray(order).toString());
                }
            } catch (Throwable t) {
                Log.w(TAG, "tg channel not removed: " + t.getClass().getSimpleName());
            }
            Log.i(TAG, "tg channel removed");
        });
    }

    // ---- files ----------------------------------------------------------------------------

    private static long fileOf(TLRPC.TL_upload_getFile q) {
        return q.location instanceof TLRPC.TL_inputPeerPhotoFileLocation
                ? ((TLRPC.TL_inputPeerPhotoFileLocation) q.location).photo_id
                : q.location != null ? q.location.id : 0;
    }

    static boolean ownsFile(TLRPC.TL_upload_getFile q) {
        return fileRefs.containsKey(fileOf(q));
    }

    /**
     * `upload.getFile` for a channel's file: from this phone's copy, or, when the user taps a file
     * that is not here yet, downloaded first through the reader (the answer waits for it).
     */
    static TLObject getFile(TLRPC.TL_upload_getFile q, RequestDelegate done) {
        long id = fileOf(q);
        Runnable answer = () -> {
            try {
                done.run(part(id, q.offset, q.limit), null);
            } catch (Throwable t) {
                TLRPC.TL_error e = new TLRPC.TL_error();
                e.code = 400;
                e.text = "FILE_ID_INVALID";
                done.run(null, e);
            }
        };
        if (whole.contains(id)) {
            Utilities.stageQueue.postRunnable(answer);
            return null;
        }
        boolean first;
        synchronized (waiting) {
            List<Runnable> w = waiting.get(id);
            first = w == null;
            if (first) {
                w = new ArrayList<>();
                waiting.put(id, w);
            }
            w.add(answer);
        }
        if (first) {
            taps.execute(() -> fetchByHand(id));
        }
        return null;
    }

    private static void fetchByHand(long id) {
        Object[] ref = fileRefs.get(id);
        Chan ch = ref != null ? channels.get((String) ref[0]) : null;
        if (ch != null) {
            JSONObject md = mediaById(ch, id);
            if (md != null) {
                Counter c = new Counter();
                try {
                    fetchFiles(new Job(), c, ch, Collections.singletonList(md), BY_HAND_MAX, null, 0);
                } catch (Throwable t) {
                    Log.w(TAG, "tg file not fetched: " + t.getClass().getSimpleName());
                }
                ch.up += c.up;
                ch.down += c.down;
                ch.requests += c.requests;
                work.execute(() -> {
                    try {
                        save(ch);
                    } catch (Fail ignored) {
                    }
                });
            }
        }
        List<Runnable> w;
        synchronized (waiting) {
            w = waiting.remove(id);
        }
        if (w != null) {
            for (Runnable r : w) {
                Utilities.stageQueue.postRunnable(r);
            }
        }
    }

    private static JSONObject mediaById(Chan ch, long id) {
        if (id == ch.photoId) {
            try {
                return new JSONObject().put("u", ch.photo).put("f", id).put("k", 0);
            } catch (JSONException e) {
                return null;
            }
        }
        for (JSONObject md : mediaOf(ch.posts)) {
            if (md.optLong("f") == id) {
                return md;
            }
        }
        return null;
    }

    private static TLRPC.TL_upload_file part(long id, long offset, int limit) throws Exception {
        File f = local(id);
        long size = f.length();
        if (!f.exists()) {
            throw new IllegalStateException("not here");
        }
        int len = (int) Math.max(0, Math.min(limit, size - offset));
        byte[] data = new byte[len];
        if (len > 0) {
            try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
                r.seek(offset);
                r.readFully(data);
            }
        }
        TLRPC.TL_upload_file r = new TLRPC.TL_upload_file();
        r.type = new TLRPC.TL_storage_filePartial();
        r.mtime = (int) (System.currentTimeMillis() / 1000);
        r.bytes = new NativeByteBuffer(len);
        r.bytes.writeBytes(data);
        r.bytes.position(0);
        return r;
    }

    // ---- a post's files, through the file fetcher (T71) -------------------------------------

    /**
     * The file fetchers (relay/tgfiles/TgFiles.gs): Google Apps Scripts in Ehsan's own Google
     * accounts, each holding the fetcher's Telegram account. The web page names a post's files but
     * does not carry them; a tap on «دریافت» asks one fetcher, chosen by the post, then the next
     * when one is busy. The phone never talks to Telegram.
     */
    private static final String[] FILES_SCRIPTS = {
            "redacted-files-fetcher-1",
            "redacted-files-fetcher-2",
            "redacted-files-fetcher-3",
            "redacted-files-fetcher-4",
    };
    private static final String FILES_KEY = "redacted-files-key";
    /** Files one phone gets a day (Ehsan, 31 Oct 2026: «روزانه هر کس فقط مثلا 20 فایل»); the fetchers count too. */
    private static final int FILES_DAY = 20;
    static final int DOC = 5;
    private static final String DOC_PRESS = "gereh:tgdoc:";

    private static boolean hasDoc(Chan ch, int item) {
        long id = fileIdOf(ch.key, item, DOC);
        return whole.contains(id) && local(id).exists();
    }

    /**
     * A post's files: each one here becomes a file of the post (the first in the post's own
     * message, more as an album beside it); for those not here, a «دریافت» button row each, returned.
     */
    private static List<TL_keyboard.KeyboardInlineButtonRow> docs(Chan ch, JSONObject p, List<TLRPC.Message> out) {
        TLRPC.TL_replyInlineMarkup markup = new TLRPC.TL_replyInlineMarkup();
        JSONArray dl = p.optJSONArray("dl");
        if (dl == null || dl.length() == 0 || out.isEmpty()) {
            return new ArrayList<>();
        }
        TLRPC.TL_message first = (TLRPC.TL_message) out.get(0);
        long group = idOf("mlmchat/tg-album/1|" + ch.key + "|" + p.optInt("i"));
        List<TLRPC.Message> more = new ArrayList<>();
        for (int i = 0; i < dl.length(); i++) {
            JSONObject d = dl.optJSONObject(i);
            int item = d.optInt("i");
            if (hasDoc(ch, item)) {
                if (first.media == null && out.size() == 1 && more.isEmpty()) {
                    docMedia(first, ch, d);
                } else {
                    TLRPC.TL_message msg = base(ch, p, item);
                    docMedia(msg, ch, d);
                    more.add(msg);
                }
                continue;
            }
            String name = d.optString("n");
            if (name.length() > 28) {
                name = name.substring(0, 27) + "…";
            }
            TL_keyboard.TL_inlineButtonTypeCallback type = new TL_keyboard.TL_inlineButtonTypeCallback();
            type.data = (DOC_PRESS + item).getBytes(StandardCharsets.UTF_8);
            TL_keyboard.TL_keyboardInlineButton b = new TL_keyboard.TL_keyboardInlineButton();
            b.text = LocaleController.formatString(R.string.GerehTgDocGet, name);
            b.type = type;
            TL_keyboard.TL_keyboardInlineButtonRow row = new TL_keyboard.TL_keyboardInlineButtonRow();
            row.buttons.add(b);
            markup.rows.add(row);
        }
        if (!more.isEmpty()) {
            for (TLRPC.Message m : out) {
                m.grouped_id = group;
                m.flags |= 131072;
            }
            for (TLRPC.Message m : more) {
                if (m.id == first.id) {
                    continue;
                }
                m.grouped_id = group;
                m.flags |= 131072;
                out.add(m);
            }
        }
        return markup.rows;
    }

    private static void docMedia(TLRPC.TL_message msg, Chan ch, JSONObject d) {
        long id = fileIdOf(ch.key, d.optInt("i"), DOC);
        TLRPC.TL_document doc = new TLRPC.TL_document();
        doc.id = id;
        doc.access_hash = 0;
        doc.file_reference = new byte[]{0};
        doc.date = msg.date;
        doc.mime_type = d.optString("mt", "application/octet-stream");
        doc.size = local(id).length();
        doc.dc_id = 2;
        TLRPC.TL_documentAttributeFilename f = new TLRPC.TL_documentAttributeFilename();
        String name = d.optString("n").replace('/', '_').replace('\\', '_').trim();
        f.file_name = name.isEmpty() ? "file" : name;
        doc.attributes.add(f);
        TLRPC.TL_messageMediaDocument mm = new TLRPC.TL_messageMediaDocument();
        mm.document = GerehMedia.roundTripDocument(doc);
        mm.flags |= 1;
        msg.media = mm;
        msg.flags |= TLRPC.MESSAGE_FLAG_HAS_MEDIA;
        handOver(mm.document, id);
    }

    /**
     * The file also where Telegram keeps a downloaded document, so the post shows it as here and
     * opens it on a tap. Telegram never fetches a plain document by itself: with only our copy, the
     * post sat «loading» with nothing loading (A50, 31 Oct 2026).
     */
    private static void handOver(TLRPC.Document doc, long id) {
        try {
            File from = local(id);
            File to = org.telegram.messenger.FileLoader.getInstance(Messenger.account()).getPathToAttach(doc, null, false, false);
            if (to == null || !from.exists() || to.exists() && to.length() == from.length()) {
                return;
            }
            File dirTo = to.getParentFile();
            if (dirTo != null) {
                dirTo.mkdirs();
            }
            File tmp = new File(to.getPath() + ".part");
            try (java.io.FileInputStream in = new java.io.FileInputStream(from); java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                }
            }
            if (!tmp.renameTo(to)) {
                tmp.delete();
            }
        } catch (Throwable t) {
            Log.w(TAG, "tg doc not handed to Telegram: " + t.getClass().getSimpleName());
        }
    }

    /** A tap on a post's «دریافت» (GerehConfigs.press): the file through a fetcher; true when it was one of ours. */
    static boolean pressDoc(org.telegram.ui.ActionBar.BaseFragment f, MessageObject m, String data) {
        if (data == null || !data.startsWith(DOC_PRESS)) {
            return false;
        }
        int item;
        try {
            item = Integer.parseInt(data.substring(DOC_PRESS.length()));
        } catch (NumberFormatException e) {
            return true;
        }
        Chan ch = m != null ? chanOf(-m.getDialogId()) : null;
        if (ch == null) {
            return true;
        }
        docNote(f, R.string.GerehTgDocGetting);
        taps.execute(() -> {
            int res = fetchDoc(ch, item);
            if (res != 0) {
                AndroidUtilities.runOnUIThread(() -> docNote(f, res));
            }
        });
        return true;
    }

    private static void docNote(org.telegram.ui.ActionBar.BaseFragment f, int text) {
        if (f != null && f.getParentActivity() != null) {
            org.telegram.ui.Components.BulletinFactory.of(f).createSimpleBulletin(R.raw.info, LocaleController.getString(text)).show();
        }
    }

    /** One file of a post, through the fetchers; 0 when it is here now, else what to say. */
    private static int fetchDoc(Chan ch, int item) {
        JSONObject post = null, doc = null;
        for (JSONObject p : ch.posts) {
            JSONArray dl = p.optJSONArray("dl");
            for (int i = 0; dl != null && i < dl.length(); i++) {
                if (dl.optJSONObject(i).optInt("i") == item) {
                    post = p;
                    doc = dl.optJSONObject(i);
                }
            }
        }
        if (doc == null) {
            return R.string.GerehTgDocFailed;
        }
        long id = fileIdOf(ch.key, item, DOC);
        if (hasDoc(ch, item)) {
            pushEdit(ch, post);
            return 0;
        }
        if (FILES_SCRIPTS.length == 0) {
            return R.string.GerehTgDocNotReady;
        }
        if (!takeOneToday()) {
            return R.string.GerehTgDocLimit;
        }
        String last = "error";
        int start = Math.floorMod((ch.name.toLowerCase(Locale.ROOT) + "/" + item).hashCode(), FILES_SCRIPTS.length);
        for (int k = 0; k < FILES_SCRIPTS.length; k++) {
            JSONObject a;
            try {
                JSONObject body = new JSONObject().put("op", "f").put("c", ch.name).put("p", item).put("u", dayId());
                a = call(new Counter(), FILES_SCRIPTS[(start + k) % FILES_SCRIPTS.length], FILES_KEY, body, FILE_S);
            } catch (Throwable t) {
                last = "network";
                continue;
            }
            if (a.optInt("ok") == 1) {
                try {
                    byte[] b = Base64.decode(a.optString("b"), Base64.DEFAULT);
                    File tmp = new File(dir(), id + ".part");
                    try (java.io.FileOutputStream o = new java.io.FileOutputStream(tmp)) {
                        o.write(b);
                    }
                    if (!tmp.renameTo(local(id))) {
                        tmp.delete();
                        throw new java.io.IOException("not kept");
                    }
                    whole.add(id);
                    fileRefs.put(id, new Object[]{ch.key, item, DOC});
                    doc.put("mt", a.optString("m", "application/octet-stream"));
                    save(ch);
                } catch (Throwable t) {
                    Log.w(TAG, "tg doc not kept: " + t.getClass().getSimpleName());
                    giveBackOne();
                    return R.string.GerehTgDocFailed;
                }
                pushEdit(ch, post);
                return 0;
            }
            last = a.optString("e");
            Log.i(TAG, "tg doc: fetcher says " + last);
            if (!last.equals("busy") && !last.equals("quota") && !last.equals("setup") && !last.equals("error")) {
                break; // the same anywhere: no use asking the next one
            }
        }
        if (!last.equals("limit")) {
            giveBackOne();
        }
        switch (last) {
            case "limit":
                return R.string.GerehTgDocLimit;
            case "busy":
            case "quota":
                return R.string.GerehTgDocBusy;
            case "nofile":
            case "nochannel":
                return R.string.GerehTgDocNone;
            case "big":
            case "notconfig":
                return R.string.GerehTgDocBig;
            default:
                return R.string.GerehTgDocFailed;
        }
    }

    private static android.content.SharedPreferences dlPrefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("gereh_tg", android.content.Context.MODE_PRIVATE);
    }

    /** One of today's FILES_DAY files, if any is left. */
    private static synchronized boolean takeOneToday() {
        android.content.SharedPreferences sp = dlPrefs();
        int day = (int) (System.currentTimeMillis() / 86400000L);
        int used = sp.getInt("dl_day", 0) == day ? sp.getInt("dl_used", 0) : 0;
        if (used >= FILES_DAY) {
            return false;
        }
        sp.edit().putInt("dl_day", day).putInt("dl_used", used + 1).apply();
        return true;
    }

    private static synchronized void giveBackOne() {
        android.content.SharedPreferences sp = dlPrefs();
        int day = (int) (System.currentTimeMillis() / 86400000L);
        if (sp.getInt("dl_day", 0) == day) {
            sp.edit().putInt("dl_used", Math.max(0, sp.getInt("dl_used", 0) - 1)).apply();
        }
    }

    /** This phone's id for today at the fetchers (they count files per phone): unlinkable day to day. */
    private static String dayId() throws Exception {
        android.content.SharedPreferences sp = dlPrefs();
        String secret = sp.getString("dl_secret", null);
        if (secret == null) {
            byte[] s = new byte[32];
            new SecureRandom().nextBytes(s);
            secret = Base64.encodeToString(s, Base64.NO_WRAP);
            sp.edit().putString("dl_secret", secret).apply();
        }
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(Base64.decode(secret, Base64.NO_WRAP), "HmacSHA256"));
        byte[] h = mac.doFinal(("gereh-tgfiles|" + (System.currentTimeMillis() / 86400000L)).getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            out.append(String.format(Locale.ROOT, "%02x", h[i]));
        }
        return out.toString();
    }

    /** A post shown again, now with its file: its own message edited, an album's new files added. */
    private static void pushEdit(Chan ch, JSONObject post) {
        List<TLRPC.Message> msgs = messagesOf(ch, post);
        TLRPC.Chat chat = chatFor(ch);
        int own = post.optInt("i");
        Utilities.stageQueue.postRunnable(() -> {
            TLRPC.TL_updates ups = new TLRPC.TL_updates();
            for (TLRPC.Message m : msgs) {
                if (m.id == own) {
                    TL_update.TL_updateEditChannelMessage u = new TL_update.TL_updateEditChannelMessage();
                    u.message = m;
                    u.pts = ch.pts.incrementAndGet();
                    u.pts_count = 1;
                    ups.updates.add(u);
                } else {
                    TL_update.TL_updateNewChannelMessage u = new TL_update.TL_updateNewChannelMessage();
                    u.message = m;
                    u.pts = ch.pts.incrementAndGet();
                    u.pts_count = 1;
                    ups.updates.add(u);
                }
            }
            ups.chats.add(chat);
            ups.date = (int) (System.currentTimeMillis() / 1000);
            ups.seq = 0;
            MessagesController.getInstance(Messenger.account()).processUpdates(ups, false);
        });
    }

    /**
     * Telegram's own «download by itself» for a channel's message: a file already on this phone,
     * yes; any other, no, so it shows its download button and comes only on a tap (Ehsan's
     * «بعداً خودش دانلود کنه»). -1 for messages of other chats.
     */
    public static int autoDownload(MessageObject mo) {
        if (mo == null || mo.messageOwner == null || !isTg(mo.getDialogId())) {
            return -1;
        }
        TLRPC.MessageMedia mm = mo.messageOwner.media;
        long id = mm instanceof TLRPC.TL_messageMediaPhoto && mm.photo != null ? mm.photo.id
                : mm instanceof TLRPC.TL_messageMediaDocument && mm.document != null ? mm.document.id : 0;
        Object[] ref = id != 0 ? fileRefs.get(id) : null;
        if (ref != null && ref.length > 2 && Integer.valueOf(DOC).equals(ref[2])) {
            return 0; // a post's file: handed to Telegram's own place when it came (handOver), never «loading»
        }
        return id != 0 && whole.contains(id) ? 1 : 0;
    }

    // ---- small things ---------------------------------------------------------------------

    private static void checkCancel(Job job) throws Fail {
        if (job != null && job.cancelled) {
            throw new Fail("cancelled");
        }
    }

    private static void progress(Progress pr, int step, int done, int total, long bytes, long bytesTotal) {
        if (pr != null) {
            AndroidUtilities.runOnUIThread(() -> pr.on(step, done, total, bytes, bytesTotal));
        }
    }

    private static void finish(Result r, Counter c, Done done) {
        r.up = c.up;
        r.down = c.down;
        r.requests = c.requests;
        if (done != null) {
            AndroidUtilities.runOnUIThread(() -> done.on(r));
        }
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
