package org.mlmvpn.chat.bridge;

import android.app.Service;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import org.mlmchat.core.ChatNode;
import org.mlmchat.core.Group;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * MLMCHAT: q3 (Ehsan, 31 Oct 2026), from the Gereh tile's menu in the app.
 * <ul>
 * <li>{@link #CLEAR}: every message goes, with the files it carried and every copy Telegram keeps;
 * the account, contacts, groups and channels stay, empty, and the official channel and group keep
 * their posts.</li>
 * <li>{@link #ACCOUNT}: this phone leaves every group and channel (the members see it), then
 * nothing of Gereh is left here; the next opening makes a new account. Gereh has no server, so
 * this is the whole account: contacts keep the old card.</li>
 * <li>{@link #REMOVE}: the same, and the app hides Gereh as if it never had it.</li>
 * </ul>
 * It runs here, in the chat's own process, which owns the databases. When done it writes
 * {@link #DONE} and ends this process, so nothing is left open on a deleted file; the app waits
 * for that file, deletes what a dying process may have written back ({@link #deleteAll}), and
 * for REMOVE hides Gereh.
 */
public final class GerehWipe {
    private static final String TAG = "MLMCHAT";

    public static final String ACTION = "org.mlmvpn.chat.WIPE";
    public static final String EXTRA_MODE = "mode";
    public static final int CLEAR = 1;
    public static final int ACCOUNT = 2;
    public static final int REMOVE = 3;

    /** In the files folder once this process is done; the app waits for it. */
    public static final String DONE = "gereh_wipe_done";

    /** Leaving every group sends a frame to each member: this long for them to go out. */
    private static final long LEAVE_WAIT_MS = 20_000;

    /** Telegram's and Gereh's settings files; the app's own (settings, app_settings, …) never match. */
    private static final Pattern PREFS = Pattern.compile(
            "(gereh_.*|crash_chat_watch|mainconfig\\d*|userconfig\\d*|userconfing|Notifications\\d*|drafts\\d*|\\d*voicedrafts_\\d+"
                    + "|themeconfig|chatthemeconfig.*|emoji.*|camera|uploadinfo|videoconvert)\\.xml");

    /** Telegram's and Gereh's files in the files folder; the app's engines and lists never match. */
    private static final Pattern FILES = Pattern.compile(
            "account\\d+|cache4\\.db.*|file_to_path.*\\.db.*|wallpaper\\.img|.*\\.attheme|Dark Blue_.*|c?thumb\\.jpg|icthumb\\.jpg"
                    + "|ShortcutInfoCompatSaver_share_targets|stats2\\.dat|gereh_tg|gereh-import");

    /** Gereh's caches in the app's cache folder (its «updates» and engines' files stay). */
    private static final String[] CACHES = {"gereh-media", "gereh-up", "gereh-found", "gereh-links"};

    private GerehWipe() {
    }

    static void start(Service service, int mode) {
        Context app = service.getApplicationContext();
        new Thread(() -> run(service, app, mode), "gereh-wipe").start();
    }

    private static void run(Service service, Context c, int mode) {
        Log.i(TAG, "wipe " + mode + " started");
        try {
            ChatNode node = ChatNode.exists(c) ? GerehIdentity.node() : null;
            if (mode == CLEAR) {
                if (node != null) {
                    Log.i(TAG, "wipe: " + node.clearAllMessages() + " messages");
                }
                clearTelegramCopy(UserConfig.selectedAccount);
                c.getSharedPreferences("gereh_sent_ids", Context.MODE_PRIVATE).edit().clear().commit();
                deleteMedia(c);
            } else {
                if (node != null) {
                    leaveAll(node);
                    try {
                        node.close();
                    } catch (Throwable ignored) {
                    }
                }
                deleteAll(c);
            }
        } catch (Throwable t) {
            Log.e(TAG, "wipe failed: " + t.getClass().getSimpleName());
        } finally {
            try (FileOutputStream out = new FileOutputStream(new File(c.getFilesDir(), DONE))) {
                out.write(Integer.toString(mode).getBytes(StandardCharsets.UTF_8));
            } catch (Throwable ignored) {
            }
            Log.i(TAG, "wipe " + mode + " done, chat process ends");
            // the service stops first: the system takes a process that ends in the middle of a
            // start for a crash and starts the service again with the same intent, so the wipe ran
            // three times in 2.5 minutes on an emulator (31 Oct 2026) and would have deleted an
            // account made after the first
            try {
                service.stopSelf();
            } catch (Throwable ignored) {
            }
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    }

    /** Leaves every group and channel this phone is in, then waits a while for the leaves to go out. */
    private static void leaveAll(ChatNode node) {
        int before = node.outboxLen();
        String me = node.getMe().getSk();
        int left = 0;
        for (Group g : node.groups()) {
            if (!g.getMember() || g.getDissolved()) {
                continue;
            }
            try {
                node.groupRemove(g.getId(), me, false);
                left++;
            } catch (Throwable t) {
                Log.w(TAG, "wipe: a group not left: " + t.getClass().getSimpleName());
            }
        }
        long end = System.currentTimeMillis() + LEAVE_WAIT_MS;
        while (left > 0 && System.currentTimeMillis() < end && node.outboxLen() > before) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                break;
            }
        }
        Log.i(TAG, "wipe: left " + left + " groups and channels");
    }

    /**
     * Telegram's own copy of the chats (cache4.db) goes the way Telegram drops it at logout, and
     * its dialog list starts from the beginning, so the chats are read again from Gereh.
     */
    private static void clearTelegramCopy(int account) throws InterruptedException {
        MessagesStorage storage = MessagesStorage.getInstance(account);
        storage.cleanup(false);
        CountDownLatch done = new CountDownLatch(1);
        storage.getStorageQueue().postRunnable(done::countDown);
        done.await(10, TimeUnit.SECONDS);
        UserConfig config = UserConfig.getInstance(account);
        for (int folder = 0; folder < 2; folder++) {
            config.setDialogsLoadOffset(folder, 0, 0, 0, 0, 0, 0);
            config.setTotalDialogsCount(folder, 0);
        }
    }

    /** Photos, videos, files and voice: Telegram's folders, Gereh's caches and the copied Telegram channels' posts. */
    private static void deleteMedia(Context c) {
        File ext = c.getExternalFilesDir(null);
        if (ext != null) {
            deleteChildren(new File(ext, "Telegram"));
        }
        deleteChildren(c.getExternalCacheDir());
        for (String name : CACHES) {
            deleteTree(new File(c.getCacheDir(), name));
        }
        deleteChildren(new File(c.getFilesDir(), "gereh_tg"));
    }

    /**
     * Everything Gereh and its Telegram keep on this phone: the core's folder (keys, database,
     * files), Telegram's databases, settings, themes, logs and media, and Gereh's caches. Safe to
     * call from the app's process too, once the chat's process is gone.
     */
    public static void deleteAll(Context c) {
        deleteTree(ChatNode.dir(c));
        File files = c.getFilesDir();
        File[] list = files.listFiles();
        if (list != null) {
            for (File f : list) {
                if (FILES.matcher(f.getName()).matches()) {
                    deleteTree(f);
                }
            }
        }
        File prefs = new File(c.getApplicationInfo().dataDir, "shared_prefs");
        list = prefs.listFiles();
        if (list != null) {
            for (File f : list) {
                if (PREFS.matcher(f.getName()).matches()) {
                    String name = f.getName().substring(0, f.getName().length() - ".xml".length());
                    if (Build.VERSION.SDK_INT >= 24) {
                        c.deleteSharedPreferences(name);
                    } else {
                        deleteTree(f);
                    }
                }
            }
        }
        File ext = c.getExternalFilesDir(null);
        if (ext != null) {
            deleteTree(new File(ext, "Telegram"));
            deleteTree(new File(ext, "logs"));
        }
        deleteMedia(c);
    }

    private static void deleteChildren(File dir) {
        File[] list = dir != null ? dir.listFiles() : null;
        if (list != null) {
            for (File f : list) {
                deleteTree(f);
            }
        }
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        if (f.isDirectory()) {
            deleteChildren(f);
        }
        if (!f.delete()) {
            Log.w(TAG, "wipe: not deleted " + f.getName());
        }
    }
}
