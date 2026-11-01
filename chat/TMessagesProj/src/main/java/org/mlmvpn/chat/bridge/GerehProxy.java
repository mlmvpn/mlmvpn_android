package org.mlmvpn.chat.bridge;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_keyboard;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.utils.proxy.ProxySettings;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MLMCHAT: Telegram's proxy links (MTProto, SOCKS5 and the HTTPS web proxy; `tg://proxy?…` and
 * `https://t.me/proxy?…` alike) belong to the Telegram app, never to Gereh (Ehsan, 31 Oct 2026:
 * «ما اصلا نیاز به پروکسی نداریم … جاشو با رله گوگل عوض کردیم»). A tap on one, or on the
 * «باز کردن در تلگرام» button under a message that holds one, hands it to the Telegram app; Gereh
 * never takes a proxy for itself, and one taken before is turned off. Only links Telegram's own
 * parser takes as a proxy count, so Gereh's own invites (`tg://join?invite=…`) never do.
 */
final class GerehProxy {
    /** A button's data: this, then the link's place among the message's proxies. */
    private static final String OPEN = "gereh:proxy:";
    /** What may be a proxy link; Telegram's parser decides (ProxySettings.fromUri). */
    private static final Pattern LINK = Pattern.compile(
            "(?i)(?:tg:(?://)?(?:proxy|socks|webproxy)\\?|https?://(?:www\\.)?(?:t\\.me|telegram\\.me|telegram\\.dog)/(?:proxy|socks|webproxy)\\?)[^\\s<>\"'«»]+");
    /** Buttons under one message, at most. */
    private static final int MAX = 8;

    private GerehProxy() {
    }

    /** The proxy links of a message, in its text and behind its links, each once and in order. */
    static List<String> linksOf(TLRPC.Message msg) {
        LinkedHashSet<String> found = new LinkedHashSet<>();
        if (msg != null) {
            scan(msg.message, found);
            if (msg.entities != null) {
                for (TLRPC.MessageEntity e : msg.entities) {
                    if (e instanceof TLRPC.TL_messageEntityTextUrl) {
                        scan(e.url, found);
                    }
                }
            }
        }
        List<String> out = new ArrayList<>();
        for (String link : found) {
            if (isProxy(link)) {
                out.add(link);
                if (out.size() == MAX) {
                    break;
                }
            }
        }
        return out;
    }

    private static void scan(String text, LinkedHashSet<String> into) {
        if (text == null || text.isEmpty()) {
            return;
        }
        Matcher m = LINK.matcher(text);
        while (m.find()) {
            // a sentence's last mark is not the link's
            into.add(m.group().replaceAll("[.,;:!?)\\]]+$", ""));
        }
    }

    private static boolean isProxy(String link) {
        try {
            ProxySettings p = ProxySettings.fromUri(Uri.parse(link));
            return p != null && p.isValid();
        } catch (Throwable t) {
            return false;
        }
    }

    /** One row of «باز کردن در تلگرام» per proxy link of the message. */
    static void addRows(TLRPC.Message msg, ArrayList<TL_keyboard.KeyboardInlineButtonRow> rows) {
        List<String> links = linksOf(msg);
        for (int i = 0; i < links.size(); i++) {
            String label = links.size() == 1
                    ? LocaleController.getString(R.string.GerehProxyOpen)
                    : LocaleController.formatString(R.string.GerehProxyOpenNumbered, LocaleController.formatNumber(i + 1, ','));
            TL_keyboard.TL_keyboardInlineButtonRow row = new TL_keyboard.TL_keyboardInlineButtonRow();
            row.buttons.add(GerehConfigs.button(label, OPEN + i));
            rows.add(row);
        }
    }

    /** A press on one of those buttons: true when it was one. */
    static boolean press(BaseFragment fragment, MessageObject m, String data) {
        if (!data.startsWith(OPEN)) {
            return false;
        }
        int i;
        try {
            i = Integer.parseInt(data.substring(OPEN.length()));
        } catch (NumberFormatException e) {
            return true;
        }
        List<String> links = linksOf(m.messageOwner);
        if (i >= 0 && i < links.size()) {
            open(fragment, Uri.parse(links.get(i)));
        }
        return true;
    }

    /** A Telegram proxy link goes to the Telegram app, as `tg://` (a browser would take `https://t.me`). */
    static void open(BaseFragment fragment, Uri link) {
        Activity activity = fragment != null ? fragment.getParentActivity() : null;
        open(activity, link, fragment);
    }

    static void open(Activity activity, Uri link, BaseFragment fragment) {
        if (activity == null || link == null) {
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, asTg(link));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(i);
        } catch (ActivityNotFoundException e) {
            String text = LocaleController.getString(R.string.GerehProxyNoTelegram);
            if (fragment != null) {
                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.info, text).show();
            } else {
                BulletinFactory.global().createSimpleBulletin(R.raw.info, text).show();
            }
        }
    }

    /** `https://t.me/proxy?a=b` as `tg://proxy?a=b`; a `tg:` link as it is. */
    static Uri asTg(Uri link) {
        if ("tg".equalsIgnoreCase(link.getScheme())) {
            return link;
        }
        String path = link.getPath() != null ? link.getPath().toLowerCase(Locale.ROOT) : "";
        String kind = path.startsWith("/socks") ? "socks" : path.startsWith("/webproxy") ? "webproxy" : "proxy";
        String query = link.getEncodedQuery();
        return Uri.parse("tg://" + kind + (query != null ? "?" + query : ""));
    }

    /**
     * A proxy taken before this (a tap on Telegram's «connect proxy») is turned off: Gereh never used
     * it (tgnet does not run here) but showed «connecting to proxy», with no page left to turn it off
     * (its place is the Google relays'). Only the setting; Telegram's network code is not called.
     */
    static void turnOff() {
        try {
            SharedPreferences p = MessagesController.getGlobalMainSettings();
            if (p.getBoolean("proxy_enabled", false)) {
                p.edit().putBoolean("proxy_enabled", false).apply();
                SharedConfig.currentProxy = null;
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
            }
        } catch (Throwable t) {
            // left as it was
        }
    }
}
