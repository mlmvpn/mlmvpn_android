package org.mlmvpn.chat.bridge;

import android.content.Context;
import android.content.pm.PackageInfo;

import org.mlmchat.core.ChatNode;
import org.mlmchat.core.PeerClient;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MLMCHAT: «نسخهٔ قدیمی» beside whoever runs an older app (SPEC 5 `Client`, T75; Ehsan, 31 Oct
 * 2026: «هرکس نسخه قدیمی داره توی گروه به بقیه و خودش نشون داده بشه»). From draft 28 every message
 * and official post says which app and version it came from, and the core keeps each sender's
 * newest. Someone is old when their newest came from a phone before draft 28 (no app said), or from
 * this same app at a version older than the newest one this phone knows: its own, or the newest
 * release its updates have seen ({@link GerehHost#setRelease}). Another app (Dr.Net's, for MLMVPN)
 * is judged by the first rule only: its releases are not known here. The user is told when their
 * own app is older than the newest release, since others then see the tag beside them.
 */
final class GerehVersion {
    /** At most this often, the reminder that one's own app is old. */
    private static final long SELF_EVERY_MS = 3_600_000L;
    /** The host's newest release is read again after this long. */
    private static final long LATEST_KEEP_MS = 60_000L;

    private static final ConcurrentHashMap<Long, PeerClient> byUid = new ConcurrentHashMap<>();
    private static volatile long since;
    private static volatile String ownApp;
    private static volatile String ownName;
    private static volatile String latest;
    private static volatile long latestAt;
    private static long selfShownAt;

    private GerehVersion() {
    }

    /** Reads what changed since the last read (everything the first time); on the event thread. */
    static void load(ChatNode node) {
        try {
            List<PeerClient> list = node.clientsSince(since);
            long newest = since;
            for (PeerClient c : list) {
                byUid.put(GerehIdentity.userIdFor(c.getSk()), c);
                newest = Math.max(newest, c.getAtMs());
            }
            since = newest;
        } catch (Throwable t) {
            // the tag waits for the next change
        }
    }

    /** Someone's app changed (ChatEvent.ClientChanged): read it, and the lines under names follow. */
    static void changed(ChatNode node) {
        load(node);
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(Messenger.account())
                .postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_STATUS));
    }

    /** Whether the user with this id runs an older app, as far as this phone knows. */
    static boolean isOld(long uid) {
        PeerClient c = byUid.get(uid);
        if (c == null) {
            return false;
        }
        if (c.getApp().isEmpty()) {
            return true;
        }
        own();
        return c.getApp().equals(ownApp) && newer(newest(), c.getName());
    }

    /** Whether this phone's own app is older than the newest release its updates have seen. */
    static boolean selfOld() {
        own();
        return newer(hostLatest(), ownName);
    }

    static String tag() {
        return LocaleController.getString(R.string.GerehOldVersion);
    }

    /** `line` with the tag after it when the user's app is old. */
    static String mark(long uid, String line) {
        return isOld(uid) ? line + " · " + tag() : line;
    }

    /** A Gereh chat is on the screen: once an hour at most, say so when one's own app is old. */
    static void chatShown(BaseFragment fragment) {
        if (fragment == null || fragment.getParentActivity() == null || !selfOld()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - selfShownAt < SELF_EVERY_MS) {
            return;
        }
        selfShownAt = now;
        Context context = fragment.getParentActivity();
        BulletinFactory.of(fragment).createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.GerehOldVersionSelf),
                LocaleController.getString(R.string.GerehOldVersionUpdate), () -> openUpdate(context)).show();
    }

    private static void openUpdate(Context context) {
        GerehHost.Release r = GerehHost.release();
        try {
            if (r != null && r.openUpdate(context)) {
                return;
            }
            android.content.Intent i = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            if (i != null) {
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(i);
            }
        } catch (Throwable t) {
            // nothing to open
        }
    }

    /** The newest version this phone knows of its own app: its own, or a newer release. */
    private static String newest() {
        String l = hostLatest();
        return newer(l, ownName) ? l : ownName;
    }

    private static String hostLatest() {
        long now = System.currentTimeMillis();
        if (now - latestAt > LATEST_KEEP_MS) {
            latestAt = now;
            GerehHost.Release r = GerehHost.release();
            try {
                latest = r != null ? r.latest() : null;
            } catch (Throwable t) {
                latest = null;
            }
        }
        return latest;
    }

    private static void own() {
        if (ownApp != null) {
            return;
        }
        Context context = ApplicationLoader.applicationContext;
        String name = "";
        try {
            PackageInfo pi = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            name = pi.versionName != null ? pi.versionName : "";
        } catch (Throwable t) {
            // no name: nobody is older than it
        }
        ownName = name;
        ownApp = context.getPackageName();
    }

    /** Whether version `a` is newer than `b` ("1.2.46" over "1.2.45"); a part that is no number counts as 0. */
    static boolean newer(String a, String b) {
        if (a == null || a.isEmpty() || b == null) {
            return false;
        }
        String[] x = a.split("[.\\-+]");
        String[] y = b.split("[.\\-+]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? number(x[i]) : 0;
            int q = i < y.length ? number(y[i]) : 0;
            if (p != q) {
                return p > q;
            }
        }
        return false;
    }

    private static int number(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
