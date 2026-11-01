package org.mlmvpn.chat.bridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;

import java.util.Locale;

/**
 * MLMCHAT: which language Gereh speaks (Ehsan, 23 Oct 2026: whoever sets MLMVPN to Persian gets
 * Gereh in Persian, English gets English, and Gereh's own settings may choose differently).
 *
 * Gereh's own choice is {@link #FOLLOW} (the default: whatever MLMVPN's settings say), "fa", "en",
 * "ru" or "zh". MLMVPN keeps its language in its main process (AppLocaleManager: prefs
 * {@code app_locale_prefs}, key {@code selected_language}, values auto / fa / en / ru / zh). The chat runs in
 * :chat, where an ordinary SharedPreferences is a copy cached at its first read and never sees the
 * main process change it, so that file is read again from disk each time it is asked.
 *
 * Telegram has no Persian of its own and fetches every other language from its servers, which
 * Gereh never reaches; LocaleController offers exactly the built-in languages (see its
 * constructor): Persian from values-fa (T21), Russian from values-ru and Chinese from values-zh.
 */
public final class GerehLocale {
    public static final String FOLLOW = "mlmvpn";
    public static final String FA = "fa";
    public static final String EN = "en";
    public static final String RU = "ru";
    public static final String ZH = "zh";

    private static final String PREFS = "gereh_locale";
    private static final String KEY = "choice";
    private static final String MLMVPN_PREFS = "app_locale_prefs";
    private static final String MLMVPN_KEY = "selected_language";

    private GerehLocale() {
    }

    /** A language Gereh speaks. */
    public static boolean known(String code) {
        return FA.equals(code) || EN.equals(code) || RU.equals(code) || ZH.equals(code);
    }

    /** Gereh's own setting: {@link #FOLLOW}, or one of the languages it speaks. */
    public static String choice() {
        String c = prefs().getString(KEY, FOLLOW);
        return known(c) ? c : FOLLOW;
    }

    public static void setChoice(String choice) {
        prefs().edit().putString(KEY, known(choice) ? choice : FOLLOW).commit();
    }

    /** The language MLMVPN shows right now: fa, en, ru or zh. «auto» there means the phone's language. */
    @SuppressWarnings("deprecation")
    public static String mlmvpnLanguage() {
        String l = "auto";
        try {
            // MODE_MULTI_PROCESS: reload the file if the main process wrote it since the last read.
            l = ApplicationLoader.applicationContext.getSharedPreferences(MLMVPN_PREFS, Context.MODE_MULTI_PROCESS).getString(MLMVPN_KEY, "auto");
        } catch (Throwable ignore) {
        }
        if (known(l)) {
            return l;
        }
        return phoneLanguage();
    }

    /**
     * The phone's own language as Gereh can speak it. Read from the system's resources: in :chat
     * Locale.getDefault() is whatever LocaleController last set, not the phone's.
     */
    public static String phoneLanguage() {
        Configuration c = Resources.getSystem().getConfiguration();
        Locale l = Build.VERSION.SDK_INT >= 24 ? c.getLocales().get(0) : c.locale;
        String code = l != null ? l.getLanguage() : EN;
        return known(code) ? code : EN;
    }

    /** Whether the chat is in Persian right now (Telegram's LocaleController decides what shows). */
    public static boolean persian() {
        LocaleController.LocaleInfo current = LocaleController.getInstance().getCurrentLocaleInfo();
        return current != null && FA.equals(current.shortName);
    }

    /** The language Gereh shows: its own choice, or MLMVPN's when it follows MLMVPN. */
    public static String resolved() {
        String c = choice();
        return FOLLOW.equals(c) ? mlmvpnLanguage() : c;
    }

    /**
     * Puts the chat in {@link #resolved()} if it is in another language: MLMVPN's language may have
     * changed while :chat stayed alive (the listening service keeps it). Telegram rebuilds its
     * screens on the reloadInterface this posts. Returns whether anything changed.
     */
    public static boolean sync(int account) {
        if (!Bridge.ENABLED) {
            return false;
        }
        String want = resolved();
        LocaleController lc = LocaleController.getInstance();
        LocaleController.LocaleInfo current = lc.getCurrentLocaleInfo();
        if (current != null && want.equals(current.shortName)) {
            return false;
        }
        LocaleController.LocaleInfo info = lc.getLanguageFromDict(want);
        if (info == null) {
            return false;
        }
        // override = true pins it: without it a rotation hands Telegram the phone's locale again
        // (LocaleController.onDeviceConfigurationChange).
        lc.applyLanguage(info, true, false, false, false, account, null);
        GerehService.relabel();
        return true;
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
