package org.mlmvpn.chat.bridge;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;

import org.mlmvpn.chat.ui.GerehLinkPickActivity;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_keyboard;
import org.telegram.ui.ActionBar.BaseFragment;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MLMCHAT: the host app's tiles and settings in chats (q11, q12; Ehsan, 30 Oct 2026). From the
 * attach menu («MLMVPN», «تنظیمات») the user picks one, and it goes as a plain message: its name
 * and a link, {@code mlmvpn://tile/<id>} or {@code mlmvpn://setting/<key>} (another app's own
 * scheme, {@link Hook#scheme}). Nothing new travels between phones; an app with no such hook, or
 * of another scheme, shows the text as it is. Where the app is behind the chat, such a message
 * shows as a card, named in this phone's own language, with a button that opens that very tile or
 * settings page; the card's link does the same.
 */
public final class GerehLinks {
    /** One tile or setting the app offers to send. */
    public static final class Item {
        /** What the link carries: a tile's id, or a setting's key. */
        public final String key;
        public final String title;
        /** A setting's place, «تنظیمات › …»; empty for a tile. */
        public final String subtitle;
        /** A glyph drawable of the app's, drawn tinted; 0 for none. */
        public final int icon;

        public Item(String key, String title, String subtitle, int icon) {
            this.key = key;
            this.title = title;
            this.subtitle = subtitle;
            this.icon = icon;
        }
    }

    /** What the host app gives (MLMVPN: GerehAppLinks, set in the chat's process). */
    public interface Hook {
        String appName();

        List<Item> tiles();

        List<Item> settings();

        /** This phone's name for a tile or setting a message names; null for one this version lacks. */
        String titleOf(boolean tile, String key);

        /** Opens the app on that tile or settings page. */
        void open(Activity activity, boolean tile, String key);

        /** A tile's face as the app's home screen draws it, size by size pixels; null for none. */
        android.graphics.Bitmap tileFace(String key, int size);

        /**
         * The card a tile or setting shows in a chat, width by height pixels: its colours and icon,
         * a small line above its name (label), its name, and what a tap does (open), laid out right
         * to left when rtl. Null for none.
         */
        android.graphics.Bitmap card(boolean tile, String key, String label, String title, String open, boolean rtl, int width, int height);

        /**
         * The app's own link scheme, without "://". Each app reads only its own links, so a page
         * one app sends never opens a page of the same id in the other (Dr.Net, 31 Oct 2026).
         */
        default String scheme() {
            return DEFAULT_SCHEME;
        }
    }

    /** A card's size: a link preview's own shape (about 1.9 to 1). */
    private static final int CARD_W = 800, CARD_H = 420;
    /** Cards drawn once per tile or setting and language, kept as files (3: no button in the picture). */
    private static final int CARD_VERSION = 3;

    private static volatile Hook hook;

    /** MLMVPN's, for an app that names none. */
    static final String DEFAULT_SCHEME = "mlmvpn";
    /** The links this app reads and sends: its scheme's (set with the hook). */
    private static volatile String prefix = DEFAULT_SCHEME + "://";
    private static volatile Pattern link = linkPattern(prefix);
    /** A card's button. */
    private static final String OPEN = "gereh:open:";

    private GerehLinks() {
    }

    public static void set(Hook h) {
        String scheme = h != null ? h.scheme() : null;
        String p = (scheme == null || scheme.isEmpty() ? DEFAULT_SCHEME : scheme) + "://";
        link = linkPattern(p);
        prefix = p;
        hook = h;
    }

    private static Pattern linkPattern(String prefix) {
        return Pattern.compile(Pattern.quote(prefix) + "(tile|setting)/([A-Za-z0-9_.:%~-]+)");
    }

    /** Whether the attach menu offers the app's tiles and settings. */
    public static boolean available() {
        return hook != null;
    }

    public static String appName() {
        Hook h = hook;
        return h != null ? h.appName() : "";
    }

    /** The link for one tile or setting. */
    public static String linkOf(boolean tile, String key) {
        return prefix + (tile ? "tile/" : "setting/") + Uri.encode(key);
    }

    /**
     * A message, as Telegram gets it, that names a tile or a setting becomes a card with an
     * «open» button (GerehConfigs.dress calls this first). True when it was one.
     */
    static boolean dress(TLRPC.Message msg) {
        Hook h = hook;
        if (h == null || msg == null || msg.message == null || msg.reply_markup != null || !msg.message.contains(prefix)) {
            return false;
        }
        Matcher m = link.matcher(msg.message);
        if (!m.find()) {
            return false;
        }
        boolean tile = "tile".equals(m.group(1));
        String key = Uri.decode(m.group(2));
        String title = h.titleOf(tile, key);
        TLRPC.Photo card = null;
        if (msg.media == null || msg.media instanceof TLRPC.TL_messageMediaEmpty) {
            // only the card's picture, its glass button under it (Ehsan, 31 Oct 2026: «فقط عکس باشه
            // با دکمه شیشه ای زیرش»); a name this version lacks keeps a small preview saying so
            card = title != null ? cardPhoto(h, tile, key, title) : null;
            if (card != null) {
                TLRPC.TL_messageMediaPhoto media = new TLRPC.TL_messageMediaPhoto();
                media.photo = card;
                media.flags |= 1;
                msg.media = media;
            } else {
                TLRPC.TL_webPage page = new TLRPC.TL_webPage();
                page.id = (long) m.group().hashCode();
                page.url = m.group();
                page.display_url = m.group();
                page.type = "article";
                page.site_name = h.appName();
                page.title = title != null ? title : key;
                page.description = LocaleController.getString(title == null ? R.string.GerehLinkUnknown : tile ? R.string.GerehLinkTileKind : R.string.GerehLinkSettingKind);
                page.flags |= 1 | 2 | 4 | 8;
                TLRPC.TL_messageMediaWebPage media = new TLRPC.TL_messageMediaWebPage();
                media.webpage = page;
                msg.media = media;
            }
            msg.flags |= TLRPC.MESSAGE_FLAG_HAS_MEDIA;
        }
        if (title != null && (msg.entities == null || msg.entities.isEmpty())) {
            // the link itself is not shown: the card and its button open it
            msg.message = withoutLink(msg.message, m.group());
            if (card != null && msg.message.indexOf('\n') < 0 && msg.message.contains(h.appName()) && msg.message.length() <= 160) {
                // nor the line it came with («وارپ» در MLMVPN): the picture says it
                msg.message = "";
            }
        }
        if (title != null) {
            TL_keyboard.TL_inlineButtonTypeCallback type = new TL_keyboard.TL_inlineButtonTypeCallback();
            type.data = (OPEN + (tile ? "tile:" : "setting:") + key).getBytes(StandardCharsets.UTF_8);
            TL_keyboard.TL_keyboardInlineButton b = new TL_keyboard.TL_keyboardInlineButton();
            b.text = LocaleController.formatString(R.string.GerehLinkOpenNamed, title, h.appName());
            b.type = type;
            TL_keyboard.TL_keyboardInlineButtonRow row = new TL_keyboard.TL_keyboardInlineButtonRow();
            row.buttons.add(b);
            TLRPC.TL_replyInlineMarkup markup = new TLRPC.TL_replyInlineMarkup();
            markup.rows.add(row);
            msg.reply_markup = markup;
            msg.flags |= 64;
        }
        return true;
    }

    /** The card's picture for a tile or setting, drawn by the app once and kept; null when it cannot. */
    private static TLRPC.Photo cardPhoto(Hook h, boolean tile, String key, String title) {
        try {
            String lang = LocaleController.getInstance().getCurrentLocaleInfo() != null ? LocaleController.getInstance().getCurrentLocaleInfo().getLangCode() : "x";
            File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "gereh-links");
            dir.mkdirs();
            String name = (tile ? "t_" : "s_") + Integer.toHexString((key + "|" + title).hashCode()) + "_" + lang + "_" + CARD_VERSION + ".jpg";
            File f = new File(dir, name);
            if (!f.exists()) {
                String label = LocaleController.formatString(tile ? R.string.GerehLinkCardTile : R.string.GerehLinkCardSetting, h.appName());
                String open = LocaleController.formatString(R.string.GerehLinkOpen, h.appName());
                android.graphics.Bitmap b = h.card(tile, key, label, title, open, LocaleController.isRTL, CARD_W, CARD_H);
                if (b == null) {
                    return null;
                }
                File tmp = new File(dir, name + ".tmp");
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out);
                }
                if (!tmp.renameTo(f)) {
                    tmp.delete();
                    return null;
                }
            }
            return GerehLocalFiles.photo(f);
        } catch (Throwable t) {
            return null;
        }
    }

    /** The words of a message without its link: the card opens it. */
    private static String withoutLink(String text, String link) {
        int i = text.indexOf(link);
        if (i < 0) {
            return text;
        }
        return (text.substring(0, i) + text.substring(i + link.length())).trim();
    }

    /** A tap on a card's picture (ChatActivity.didPressImage): opens it as its button does; true when it was one. */
    public static boolean pressCard(BaseFragment fragment, TLRPC.Message msg) {
        if (msg == null || !(msg.media instanceof TLRPC.TL_messageMediaPhoto)) {
            return false;
        }
        String data = buttonOf(msg);
        return data != null && press(fragment, data);
    }

    /** The data of a message's first button, if it is a callback; else null. */
    private static String buttonOf(TLRPC.Message msg) {
        if (!(msg.reply_markup instanceof TLRPC.TL_replyInlineMarkup)) {
            return null;
        }
        TLRPC.TL_replyInlineMarkup markup = (TLRPC.TL_replyInlineMarkup) msg.reply_markup;
        if (markup.rows.isEmpty() || markup.rows.get(0).buttons.isEmpty() || !(markup.rows.get(0).buttons.get(0).type instanceof TL_keyboard.TL_inlineButtonTypeCallback)) {
            return null;
        }
        byte[] data = ((TL_keyboard.TL_inlineButtonTypeCallback) markup.rows.get(0).buttons.get(0).type).data;
        return data == null ? null : new String(data, StandardCharsets.UTF_8);
    }

    /**
     * A card's message as it went, its words and its link, for a forward (GerehForward): each
     * phone draws the picture from its own copy of the app, so it never travels (Ehsan, 31 Oct
     * 2026: «نیاز نیست دانلود بشه که کلی از رله استفاده بشه»). Before, a forward sent the drawn
     * picture as a photo, which everyone then downloaded through the relays. Null for no card.
     */
    public static String sentText(TLRPC.Message msg) {
        Hook h = hook;
        String data = h == null || msg == null ? null : buttonOf(msg);
        if (data == null || !data.startsWith(OPEN)) {
            return null;
        }
        String rest = data.substring(OPEN.length());
        int colon = rest.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        boolean tile = rest.substring(0, colon).equals("tile");
        String key = rest.substring(colon + 1);
        String words = msg.message == null ? "" : msg.message.trim();
        if (words.isEmpty()) {
            String title = h.titleOf(tile, key);
            words = LocaleController.formatString(tile ? R.string.GerehLinkTileText : R.string.GerehLinkSettingText, title != null ? title : key, h.appName());
        }
        return words + "\n" + linkOf(tile, key);
    }

    /**
     * A picture drawn on this phone (a card, a link preview from a file): Telegram shows it at once
     * whatever its download settings, as nothing comes from the network for it (DownloadController).
     */
    public static boolean drawnHere(TLRPC.Message msg) {
        return msg != null && msg.media instanceof TLRPC.TL_messageMediaPhoto && msg.media.photo != null && GerehLocalFiles.owns(msg.media.photo.id);
    }

    /** A card's button (GerehConfigs.press): true when it was one of ours. */
    static boolean press(BaseFragment fragment, String data) {
        if (data == null || !data.startsWith(OPEN)) {
            return false;
        }
        String rest = data.substring(OPEN.length());
        int colon = rest.indexOf(':');
        Hook h = hook;
        if (colon > 0 && h != null && fragment != null && fragment.getParentActivity() != null) {
            h.open(fragment.getParentActivity(), rest.substring(0, colon).equals("tile"), rest.substring(colon + 1));
        }
        return true;
    }

    /** A tap on the link itself (Browser.openUrl): true when it was ours. */
    public static boolean openUrl(Context context, String url) {
        if (url == null || !url.startsWith(prefix)) {
            return false;
        }
        Hook h = hook;
        Matcher m = link.matcher(url);
        if (h != null && m.find()) {
            h.open(context instanceof Activity ? (Activity) context : null, "tile".equals(m.group(1)), Uri.decode(m.group(2)));
        }
        return true;
    }

    /** The attach menu's «MLMVPN» or «تنظیمات»: the list to pick one from. */
    public static void pick(BaseFragment fragment, long dialogId, boolean tiles) {
        if (hook != null && fragment != null) {
            fragment.presentFragment(new GerehLinkPickActivity(dialogId, tiles));
        }
    }

    /** The items the list shows. */
    public static List<Item> items(boolean tiles) {
        Hook h = hook;
        if (h == null) {
            return java.util.Collections.emptyList();
        }
        return tiles ? h.tiles() : h.settings();
    }

    /** A tile's face for the picker's grid; null when the app draws none. */
    public static android.graphics.Bitmap tileFace(String key, int size) {
        Hook h = hook;
        try {
            return h != null ? h.tileFace(key, size) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Sends one: its name, in the sender's language, and its link. */
    public static void send(int account, long dialogId, boolean tile, Item item) {
        String text = LocaleController.formatString(tile ? R.string.GerehLinkTileText : R.string.GerehLinkSettingText, item.title, appName())
                + "\n" + linkOf(tile, item.key);
        SendMessagesHelper.getInstance(account).sendMessage(SendMessagesHelper.SendMessageParams.of(text, dialogId));
    }
}
