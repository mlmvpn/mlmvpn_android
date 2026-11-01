package org.mlmvpn.chat.bridge;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;

import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MLMCHAT: the watchdog of the chat process ({@code :chat}), for every app that carries Gereh
 * (moved here from MLMVPN's CrashReporter.watchChatQueues; both teams, issue 7, 28 Oct 2026). On
 * Ehsan's S26 (27 Oct 2026) Telegram's storage queue stopped answering: every chat sat on its
 * loading circle -- each chat reads its messages through that queue -- and closing the app did not
 * help, because Gereh's foreground service keeps the process alive. A release build has no
 * debugger, so nothing showed where it was stuck.
 *
 * So the storage queue of the account in use and the stage queue (every answer of the bridge
 * passes it) are asked to answer every {@link #STUCK_MS} / 2 or so. One silent for
 * {@link #STUCK_MS} goes to the app with its whole stack ({@link Host#stalled}); still silent at
 * {@link #RESTART_MS}, the app hears of it once more ({@link Host#stillStuck}) and the process is
 * restarted (Gereh's service is sticky, so it comes back), and a chat screen that was showing is
 * opened again; not twice in {@link #RESTART_GAP_MS}. Never during Telegram's database upgrade,
 * which may take long on a big old database. What a write-up becomes is the app's: MLMVPN files
 * and sends it, Dr. Net keeps it on the phone for the user to send or not.
 */
public final class GerehWatchdog {
    /** A queue unanswered this long is handed to the app, with the stack it is stuck in... */
    public static final long STUCK_MS = 20_000L;
    /** ...and this long gets the chat process restarted... */
    public static final long RESTART_MS = 60_000L;
    /** ...but not twice in this long: a stall that comes straight back is reported, not looped. */
    public static final long RESTART_GAP_MS = 30L * 60 * 1000;

    private static final String THREAD = "gereh-watchdog";
    private static final String PREFS = "gereh_watchdog";
    private static final String KEY_RESTART_AT = "restart_at";
    /** Frames of these are the platform's own, never where a queue waits. */
    private static final String[] PLATFORM = {"java.", "javax.", "android.", "dalvik.", "libcore.", "com.android.", "kotlin.", "sun."};

    /** What the app does with a stall. Every call comes on the watchdog's own thread. */
    public interface Host {
        /** Queues silent for {@link #STUCK_MS}, written up while they still wait where they stopped. Once per stall. */
        void stalled(Stall[] stalls);

        /** The same stalls, still silent at {@link #RESTART_MS}; the process restarts right after this returns, unless it did lately. */
        void stillStuck(Stall[] stalls);

        /** True while the app's own crash handler runs: nothing is reported or restarted then. */
        boolean crashing();

        /** A line for the app's own log. */
        void note(String line);
    }

    /** One queue that stopped answering. */
    public static final class Stall {
        /** "storage" or "stage". */
        public final String queue;
        /** Where it waits: the first frame past the platform's own and the queue's loop ("Class.method"), or null. */
        public final String where;
        /** How long it had been silent when this was written, ms. */
        public final long stuckMs;
        /** The queue thread's whole stack: a "--- name (state) ---" line, then one "\tat frame" line each. */
        public final String stack;
        /** A "--- other threads (top frames) ---" line, then every other thread's top, the waiting ones first. */
        public final String others;

        Stall(String queue, String where, long stuckMs, String stack, String others) {
            this.queue = queue;
            this.where = where;
            this.stuckMs = stuckMs;
            this.stack = stack;
            this.others = others;
        }
    }

    private static volatile boolean started;
    /** Activities of the chat process on screen: one is opened again after a restart. */
    private static volatile int visible;

    private GerehWatchdog() {
    }

    /** In the chat process only, after Telegram's own start-up (ApplicationLoader.onCreate); once. */
    public static synchronized void start(Application app, Host host) {
        if (started || app == null || host == null) {
            return;
        }
        started = true;
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(Activity a) {
                visible++;
            }

            @Override
            public void onActivityPaused(Activity a) {
                visible = Math.max(0, visible - 1);
            }

            @Override
            public void onActivityCreated(Activity a, Bundle b) {
            }

            @Override
            public void onActivityStarted(Activity a) {
            }

            @Override
            public void onActivityStopped(Activity a) {
            }

            @Override
            public void onActivitySaveInstanceState(Activity a, Bundle b) {
            }

            @Override
            public void onActivityDestroyed(Activity a) {
            }
        });
        Thread t = new Thread(() -> {
            try {
                // start-up is not a stall: the database opens (or upgrades) on the storage queue first
                Thread.sleep(30_000);
                while (true) {
                    try {
                        round(app, host);
                    } catch (InterruptedException e) {
                        return;
                    } catch (Throwable ignored) {
                        // the next round asks again
                    }
                    Thread.sleep(STUCK_MS / 2);
                }
            } catch (InterruptedException ignored) {
            }
        }, THREAD);
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** One question to every queue, and what follows when one does not answer. */
    private static void round(Application app, Host host) throws InterruptedException {
        List<String> names = new ArrayList<>();
        List<DispatchQueue> queues = new ArrayList<>();
        try {
            DispatchQueue storage = MessagesStorage.getInstance(UserConfig.selectedAccount).getStorageQueue();
            if (storage != null) {
                names.add("storage");
                queues.add(storage);
            }
        } catch (Throwable ignored) {
        }
        DispatchQueue stage = Utilities.stageQueue;
        if (stage != null) {
            names.add("stage");
            queues.add(stage);
        }
        AtomicBoolean[] answered = new AtomicBoolean[queues.size()];
        for (int i = 0; i < queues.size(); i++) {
            AtomicBoolean a = new AtomicBoolean(false);
            answered[i] = a;
            try {
                queues.get(i).postRunnable(() -> a.set(true));
            } catch (Throwable ignored) {
            }
        }
        long asked = SystemClock.uptimeMillis();
        while (anySilent(answered, null) && SystemClock.uptimeMillis() - asked < STUCK_MS) {
            Thread.sleep(500);
        }
        List<Integer> stuck = new ArrayList<>();
        for (int i = 0; i < answered.length; i++) {
            if (!answered[i].get()) {
                stuck.add(i);
            }
        }
        if (stuck.isEmpty() || host.crashing() || databaseUpgrading()) {
            return;
        }
        // written up now, while the queues still wait where they stopped
        long waited = SystemClock.uptimeMillis() - asked;
        Stall[] stalls = new Stall[stuck.size()];
        for (int k = 0; k < stuck.size(); k++) {
            int i = stuck.get(k);
            stalls[k] = stallOf(names.get(i), queues.get(i), waited);
        }
        host.stalled(stalls);
        while (anySilent(answered, stuck) && SystemClock.uptimeMillis() - asked < RESTART_MS) {
            Thread.sleep(500);
        }
        if (anySilent(answered, stuck) && !host.crashing()) {
            StringBuilder which = new StringBuilder();
            for (int i : stuck) {
                if (!answered[i].get()) {
                    which.append(which.length() > 0 ? "," : "").append(names.get(i));
                }
            }
            host.stillStuck(stalls);
            restart(app, host, which.toString());
        }
        // one write-up per stall: wait for it to end
        while (anySilent(answered, stuck)) {
            Thread.sleep(1_000);
        }
        host.note("chat queue answered again after " + (SystemClock.uptimeMillis() - asked) / 1000 + " s");
    }

    /** True while one of the queues (all of them, or those in `only`) has not answered. */
    private static boolean anySilent(AtomicBoolean[] answered, List<Integer> only) {
        for (int i = 0; i < answered.length; i++) {
            if ((only == null || only.contains(i)) && !answered[i].get()) {
                return true;
            }
        }
        return false;
    }

    private static boolean databaseUpgrading() {
        try {
            return MessagesStorage.getInstance(UserConfig.selectedAccount).isDatabaseMigrationInProgress();
        } catch (Throwable e) {
            return false;
        }
    }

    private static Stall stallOf(String name, Thread queue, long stuckMs) {
        StackTraceElement[] frames = queue.getStackTrace();
        StringBuilder stack = new StringBuilder();
        stack.append("--- ").append(queue.getName()).append(" (").append(queue.getState()).append(") ---\n");
        for (StackTraceElement f : frames) {
            stack.append("\tat ").append(f).append('\n');
        }
        return new Stall(name, where(queue, frames), stuckMs, stack.toString(), others(queue));
    }

    /**
     * The first frame past the platform's own and the queue's loop: where the queue waits (a queue
     * idle in its loop has none; a release build renames classes, so the loop is known by the
     * queue's own class).
     */
    private static String where(Thread queue, StackTraceElement[] frames) {
        for (StackTraceElement f : frames) {
            String c = f.getClassName();
            if (c.equals(queue.getClass().getName())) {
                continue;
            }
            boolean platform = false;
            for (String p : PLATFORM) {
                if (c.startsWith(p)) {
                    platform = true;
                    break;
                }
            }
            if (!platform) {
                return c + "." + f.getMethodName();
            }
        }
        return null;
    }

    /** Every other thread's top frames, the ones that wait first: what a stuck queue waits for is usually stuck too. */
    private static String others(Thread queue) {
        List<Map.Entry<Thread, StackTraceElement[]>> list = new ArrayList<>();
        for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
            Thread t = e.getKey();
            if (t != queue && e.getValue().length > 0 && !THREAD.equals(t.getName())) {
                list.add(e);
            }
        }
        java.util.Collections.sort(list, (a, b) -> Boolean.compare(waits(b.getKey()), waits(a.getKey())));
        StringBuilder out = new StringBuilder("--- other threads (top frames) ---\n");
        for (int i = 0; i < Math.min(60, list.size()); i++) {
            Map.Entry<Thread, StackTraceElement[]> e = list.get(i);
            out.append('"').append(e.getKey().getName()).append("\" ").append(e.getKey().getState()).append('\n');
            StackTraceElement[] st = e.getValue();
            for (int j = 0; j < Math.min(14, st.length); j++) {
                out.append("\t  ").append(st[j]).append('\n');
            }
        }
        return out.toString();
    }

    private static boolean waits(Thread t) {
        Thread.State s = t.getState();
        return s == Thread.State.BLOCKED || s == Thread.State.WAITING;
    }

    /** Restarts the chat process: a queue that stopped never answers again in it. */
    private static void restart(Application app, Host host, String queues) throws InterruptedException {
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (now - prefs.getLong(KEY_RESTART_AT, 0L) < RESTART_GAP_MS) {
            host.note("chat queue (" + queues + ") still stuck; restarted lately, so reported only");
            return;
        }
        prefs.edit().putLong(KEY_RESTART_AT, now).commit();
        host.note("chat process restarted: the " + queues + " queue stopped answering");
        if (visible > 0) {
            try {
                app.startActivity(new Intent(app, org.telegram.ui.LaunchActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            } catch (Throwable ignored) {
            }
            // the system takes the start before the process goes, and starts a fresh one for it
            Thread.sleep(400);
        }
        android.os.Process.killProcess(android.os.Process.myPid());
    }
}
