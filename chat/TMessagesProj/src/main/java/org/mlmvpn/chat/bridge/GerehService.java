package org.mlmvpn.chat.bridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.mlmchat.core.ChatNode;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;

/**
 * MLMCHAT: keeps this phone reachable for Gereh messages while no chat screen is open — the
 * «always reachable» receive mode (T7, PLAN 15).
 *
 * There is no server to hold messages and wake the phone (no FCM, by design), so the core must
 * be running to receive them: this foreground service, in the chat's own :chat process, runs it
 * and lets Telegram's own notifications show what arrives. Its notification is the honest price
 * of that: Android shows it for as long as the phone is listening.
 */
public class GerehService extends Service {
    private static final String TAG = "MLMCHAT";
    private static final String CHANNEL = "gereh_listening";
    private static final int NOTIFICATION_ID = 0x6E7E; // «gere»

    private static final String ACTION_LANGUAGE = "org.mlmvpn.chat.LANGUAGE";

    private static volatile GerehService running;

    /**
     * MLMVPN's language changed (its settings, in the main process): the listening service, and
     * the chat if it is open, follow it now rather than at the next opening of the chat.
     */
    public static void languageChanged(Context context) {
        if (!Bridge.ENABLED) {
            return;
        }
        try {
            if (ChatNode.exists(context)) {
                context.startService(new Intent(context, GerehService.class).setAction(ACTION_LANGUAGE));
            }
        } catch (Throwable t) {
            Log.w(TAG, "language not passed to the chat: " + t.getClass().getSimpleName());
        }
    }

    /** Writes the listening notification again in the chat's language, after GerehLocale.sync changed it. */
    static void relabel() {
        GerehService s = running;
        NotificationManager nm = s == null ? null : (NotificationManager) s.getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, s.notification());
        }
    }

    /**
     * Flag files in the app's files folder, which both processes share (the Gereh tile's menu in
     * the app writes them; Ehsan, 30 Oct 2026): Gereh not in the background, and Gereh muted.
     */
    public static final String BACKGROUND_OFF = "gereh_background_off";
    public static final String MUTED = "gereh_muted";
    /** Gereh taken out of the app (q3, GerehWipe.REMOVE): nothing of it starts until it is brought back. */
    public static final String REMOVED = "gereh_removed";

    public static boolean flag(Context context, String name) {
        return new java.io.File(context.getFilesDir(), name).exists();
    }

    static void setFlag(Context context, String name, boolean on) {
        java.io.File f = new java.io.File(context.getFilesDir(), name);
        try {
            if (on) {
                f.createNewFile();
            } else {
                f.delete();
            }
        } catch (java.io.IOException e) {
            Log.w(TAG, "flag " + name + ": " + e.getClass().getSimpleName());
        }
    }

    /**
     * Gereh in the background, or only while it is open: off, the listening service stops and is
     * not started again (no messages while Gereh is closed, and no battery used for it).
     */
    public static void setBackground(Context context, boolean on) {
        setFlag(context, BACKGROUND_OFF, !on);
        if (on) {
            startIfReady(context);
        } else {
            try {
                context.stopService(new Intent(context, GerehService.class));
            } catch (Throwable t) {
                Log.w(TAG, "listening service not stopped: " + t.getClass().getSimpleName());
            }
        }
    }

    /** Every Gereh notification on or off (NotificationsController asks before showing any). */
    public static void setMuted(Context context, boolean muted) {
        setFlag(context, MUTED, muted);
    }

    /** Starts the service when this phone has a Gereh identity; a no-op otherwise. */
    public static void startIfReady(Context context) {
        if (!Bridge.ENABLED || flag(context, BACKGROUND_OFF) || flag(context, REMOVED)) {
            return;
        }
        if (running != null) {
            // up already: a second startForegroundService wants a second startForeground within
            // 10 s, and the service started itself again from its own onCreate (GerehIdentity.onLaunch)
            // -- 595 of the 640 reports of 1.2.43 and 1.2.44 (mlmvpn/crashes)
            return;
        }
        try {
            if (!ChatNode.exists(context)) {
                return;
            }
            Intent i = new Intent(context, GerehService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(i);
            } else {
                context.startService(i);
            }
        } catch (Throwable t) {
            // Android 12+ refuses a foreground start from the background; the next open of the
            // app (or the next boot) starts it.
            Log.w(TAG, "listening service not started: " + t.getClass().getSimpleName());
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (!goForeground()) {
            // onStartCommand, which still comes, stops it
            return;
        }
        running = this;
        ApplicationLoader.postInitApplication();
        GerehIdentity.onLaunch(UserConfig.selectedAccount);
        Log.i(TAG, "listening service up");
    }

    /**
     * False when Android 12+ refuses the foreground: a service started with startService (a
     * language change) or restarted as sticky while the app is in the background may not take it
     * (mlmvpn/crashes #2958, #3596). It is not listening then; the next open of Gereh starts it.
     */
    private boolean goForeground() {
        try {
            Notification n = notification();
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
            return true;
        } catch (RuntimeException e) {
            if (Build.VERSION.SDK_INT >= 31 && e instanceof android.app.ForegroundServiceStartNotAllowedException) {
                Log.w(TAG, "listening service may not be in the foreground now");
                return false;
            }
            throw e;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (running == null) {
            // the foreground was refused in onCreate: not listening now
            stopSelf();
            return START_NOT_STICKY;
        }
        // each start through startForegroundService wants its own startForeground, not only the
        // first: any start that still comes (another process, the system) is answered at once
        goForeground();
        if (intent != null && GerehWipe.ACTION.equals(intent.getAction())) {
            if ((flags & (START_FLAG_RETRY | START_FLAG_REDELIVERY)) != 0) {
                // the system's own second try at a wipe whose process ended before the system heard
                // the start was taken: it ran, so it is not run again, or it would delete what came
                // after it, such as an account made since (three runs on an emulator, 31 Oct 2026)
                Log.i(TAG, "wipe: a repeated start, not run again");
                stopSelf();
                return START_NOT_STICKY;
            }
            // the tile's menu (q3): this process clears or deletes, then ends itself
            GerehWipe.start(this, intent.getIntExtra(GerehWipe.EXTRA_MODE, 0));
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_LANGUAGE.equals(intent.getAction())) {
            GerehLocale.sync(UserConfig.selectedAccount);
        }
        if (flag(this, BACKGROUND_OFF)) {
            // the user turned Gereh off in the background (the tile's menu): a sticky restart ends here
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification notification() {
        // The chat's own language (GerehLocale: MLMVPN's, or Gereh's own choice), from
        // LocaleController like every other chat text; asking for it starts LocaleController in
        // that language.
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) { // creating it again only renames it (language)
            NotificationChannel ch = new NotificationChannel(CHANNEL, LocaleController.getString(R.string.GerehListeningChannel), NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent().setClassName(this, "org.telegram.ui.LaunchActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.gereh_mark)
                .setLargeIcon(logo())
                .setContentTitle(LocaleController.getString(R.string.AppName))
                .setContentText(LocaleController.getString(R.string.GerehListening))
                .setContentIntent(pi)
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            // Android does not let an app hide its own foreground-service notification, but the
            // user may switch this one channel off: the notification goes, the listening stays,
            // and message notifications (their own channels) are untouched.
            Intent hide = new Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName())
                    .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, CHANNEL)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent hp = PendingIntent.getActivity(this, 1, hide, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            b.addAction(0, LocaleController.getString(R.string.GerehListeningHide), hp);
        }
        return b.build();
    }

    /** The Gereh logo as the notification's picture, so it is not only the host app's icon. */
    private android.graphics.Bitmap logo() {
        int px = (int) (64 * getResources().getDisplayMetrics().density);
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.drawable.Drawable d = getResources().getDrawable(R.drawable.gereh_logo, getTheme());
        d.setBounds(0, 0, px, px);
        d.draw(new android.graphics.Canvas(bmp));
        return bmp;
    }
}
