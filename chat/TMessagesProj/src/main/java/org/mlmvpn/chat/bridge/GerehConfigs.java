package org.mlmvpn.chat.bridge;

import android.app.Activity;
import android.util.Log;

import org.mlmvpn.chat.ui.GerehConfigSheet;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.utils.tlutils.TLKeyboardHelper;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_keyboard;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * MLMCHAT: VPN configs in chats (Ehsan, 28 Oct 2026, n5: «سیستم گره کاملا با موتورهای MLMVPN
 * هماهنگ باشه»). A message, or file, that may hold configs the host app takes gets two
 * buttons under it, the way a bot's message has them:
 * <ul>
 * <li>«سنجش»: each config's real delay through it, here and now; only on that tap, never by itself;
 * <li>«انتقال»: they go to the app's own section for them, which then asks whether to open it.
 * </ul>
 * A text file may hold hundreds: all are measured, and the sheet offers to move the ones that
 * connect. The chat cannot see the app's code (the app depends on it, not the other way round), so
 * the app sets {@link #set} when the chat's process starts; with no app behind it no button shows.
 */
public final class GerehConfigs {
    private static final String TAG = "MLMCHAT";

    /** One config found in a message or a file. */
    public static final class Config {
        /** The app's section that takes it (MLMVPN: its screen's id). */
        public final String kind;
        /** That section's name, to show. */
        public final String section;
        /** What to call it: its own name, or the file's. */
        public final String name;
        /** The config: a link, a whole file's text, or (a file read as bytes) the file's path. */
        public final String raw;
        /** Whether it can be measured here; the rest are measured in their section. */
        public final boolean testable;

        public Config(String kind, String section, String name, String raw, boolean testable) {
            this.kind = kind;
            this.section = section;
            this.name = name;
            this.raw = raw;
            this.testable = testable;
        }
    }

    /** What the host app gives. */
    public interface Hook {
        /** The app's name, for the button: «انتقال به …». */
        String appName();

        /**
         * The configs in a message's `text`, or in a file: its text (empty for one read as bytes),
         * name and path on this phone. In the order written; fast and offline; on a background thread.
         */
        List<Config> find(String text, String fileName, String filePath);

        /** The real delay through `c` in ms, 0 when nothing came back. Blocks up to ~10 s: off the main thread. */
        long delay(Config c);

        /** Gives `configs` to their sections, then asks over `activity` whether to open the one most went to. */
        void transfer(Activity activity, List<Config> configs);

        /**
         * Whether the app takes configs of this kind ({@link GerehConfigs#kindOf}, KIND_*): «سنجش» and «انتقال» show
         * only under those. An NPV Tunnel file still gets «باز کردن در NPV Tunnel», which needs no
         * engine of the app's. MLMVPN takes every kind; Dr.Net only links and lists of them, and its
         * «انتقال» under a WireGuard or OpenVPN file could only say it takes none (Dr.Net, 31 Oct 2026).
         */
        default boolean takes(int kind) {
            return true;
        }
    }

    private static volatile Hook hook;

    /** Data of the two buttons. */
    private static final String TEST = "gereh:cfg:test";
    private static final String MOVE = "gereh:cfg:move";

    /** A text that may hold one: a link of a kind some app takes, a WireGuard or an OpenVPN file. */
    private static final Pattern MAYBE = Pattern.compile(
            "(?i)(\\b(vless|vmess|trojan|ss|ssconf|socks|hysteria2|hy2|tuic)://\\S{6,})|\\[Interface]|(^|\\n)remote\\s");
    /** Files that may hold them; what is in them is read on a tap. */
    private static final List<String> FILES = Arrays.asList(".txt", ".ovpn", ".conf", ".npvt", ".npvs", ".json");
    /** Of those, the ones read as text. */
    private static final List<String> TEXT_FILES = Arrays.asList(".txt", ".ovpn", ".conf", ".json");
    /** NPV Tunnel's own files: opened in it, not read here. */
    private static final List<String> NPV_FILES = Arrays.asList(".npvt", ".npvs");
    private static final String OPEN_NPV = "gereh:cfg:npv";
    /** A file read on a tap: larger is no list of configs. */
    private static final long MAX_FILE = 4_000_000;

    private GerehConfigs() {
    }

    public static void set(Hook h) {
        hook = h;
    }

    /**
     * A message, as Telegram gets it, gets the two buttons when its text or file may hold configs:
     * ours too (Ehsan tried one he had sent himself, 28 Oct 2026), as a bot's buttons show to all.
     */
    static void dress(TLRPC.Message msg) {
        if (GerehLinks.dress(msg)) {
            return; // a tile or setting of the app's (q11, q12): its own card
        }
        if (msg == null || msg.reply_markup != null) {
            return;
        }
        TLRPC.TL_replyInlineMarkup markup = new TLRPC.TL_replyInlineMarkup();
        // a Telegram proxy (MTProto, SOCKS5, web): «باز کردن در تلگرام», never one for Gereh (GerehProxy)
        GerehProxy.addRows(msg, markup.rows);
        Hook h = hook;
        String file = fileName(msg);
        if (h != null && mayHold(msg.message, file)) {
            // an NPV Tunnel file is sealed with NPV Tunnel's own key and no engine in this app runs it:
            // its first button opens it in NPV Tunnel (Ehsan, 31 Oct 2026)
            boolean npv = file != null && endsWithAny(file, NPV_FILES);
            // only what this app takes gets «سنجش» and «انتقال» (Hook.takes)
            boolean takes = h.takes(kindOf(msg));
            if (takes || npv) {
                TL_keyboard.TL_keyboardInlineButtonRow row = new TL_keyboard.TL_keyboardInlineButtonRow();
                row.buttons.add(npv ? button(LocaleController.getString(R.string.GerehCfgOpenNpv), OPEN_NPV) : button(LocaleController.getString(R.string.GerehCfgTest), TEST));
                if (takes) {
                    row.buttons.add(button(LocaleController.formatString(R.string.GerehCfgMove, h.appName()), MOVE));
                }
                markup.rows.add(row);
            }
        }
        if (markup.rows.isEmpty()) {
            return;
        }
        msg.reply_markup = markup;
        msg.flags |= 64;
    }

    /** One's own message as it is being sent (SendMessagesHelper): the same buttons at once. */
    public static void dressOwn(TLRPC.Message msg) {
        dress(msg);
    }

    private static String fileName(TLRPC.Message msg) {
        if (msg.media == null || msg.media.document == null || msg.media.document.attributes == null) {
            return null;
        }
        for (TLRPC.DocumentAttribute a : msg.media.document.attributes) {
            if (a instanceof TLRPC.TL_documentAttributeFilename) {
                return a.file_name;
            }
        }
        return null;
    }

    /** A chat's «کانفیگ‌ها» tab (GerehConfigsTab) lists a message under one of these. */
    public static final int KIND_LINKS = 0, KIND_NPV = 1, KIND_OVPN = 2, KIND_WG = 3, KIND_LISTS = 4;
    /** A config link, as MAYBE finds one. */
    private static final Pattern LINK = Pattern.compile("(?i)\\b(vless|vmess|trojan|ss|ssconf|socks|hysteria2|hy2|tuic)://\\S{6,}");

    /**
     * The kind a chat's configs page lists `msg` under (q1, Ehsan, 30 Oct 2026: text configs, NPV
     * and OpenVPN files, each engine's files apart), or -1. Files go by their name, texts by what
     * they hold; nothing is read or downloaded.
     */
    public static int kindOf(TLRPC.Message msg) {
        if (msg == null || msg instanceof TLRPC.TL_messageService) {
            return -1;
        }
        String file = fileName(msg);
        if (file != null) {
            String f = file.toLowerCase(Locale.ROOT);
            if (f.endsWith(".npvt") || f.endsWith(".npvs")) {
                return KIND_NPV;
            } else if (f.endsWith(".ovpn")) {
                return KIND_OVPN;
            } else if (f.endsWith(".conf")) {
                return KIND_WG;
            } else if (f.endsWith(".txt") || f.endsWith(".json")) {
                return KIND_LISTS;
            }
        }
        String text = msg.message;
        if (text == null || text.isEmpty() || !MAYBE.matcher(text).find()) {
            return -1;
        }
        if (text.contains("[Interface]")) {
            return KIND_WG;
        }
        return LINK.matcher(text).find() ? KIND_LINKS : KIND_OVPN; // else an OpenVPN profile's «remote» lines
    }

    /** The config links in a text, in order. */
    public static List<String> links(String text) {
        List<String> out = new java.util.ArrayList<>();
        if (text != null) {
            java.util.regex.Matcher m = LINK.matcher(text);
            while (m.find()) {
                out.add(m.group());
            }
        }
        return out;
    }

    /** What to call a config link: its own name (after «#», or a VMess link's «ps»), else its scheme and host. */
    public static String linkName(String link) {
        int hash = link.indexOf('#');
        if (hash >= 0 && hash < link.length() - 1) {
            try {
                return java.net.URLDecoder.decode(link.substring(hash + 1), "UTF-8").trim();
            } catch (Exception e) {
                return link.substring(hash + 1);
            }
        }
        int colon = link.indexOf("://");
        String scheme = link.substring(0, colon).toLowerCase(Locale.ROOT);
        String rest = link.substring(colon + 3);
        if (scheme.equals("vmess")) {
            try {
                String json = new String(android.util.Base64.decode(rest, android.util.Base64.DEFAULT), StandardCharsets.UTF_8);
                String ps = new org.json.JSONObject(json).optString("ps", "");
                if (!ps.isEmpty()) {
                    return ps;
                }
            } catch (Exception ignored) {
            }
        }
        int at = rest.lastIndexOf('@');
        String host = rest.substring(at + 1);
        int end = 0;
        while (end < host.length() && "/?#".indexOf(host.charAt(end)) < 0) {
            end++;
        }
        return scheme + "://" + host.substring(0, end);
    }

    /** The name of the file a message carries, or null. */
    public static String fileNameOf(TLRPC.Message msg) {
        return fileName(msg);
    }

    static boolean mayHold(String text, String fileName) {
        if (text != null && !text.isEmpty() && MAYBE.matcher(text).find()) {
            return true;
        }
        return fileName != null && endsWithAny(fileName, FILES);
    }

    private static boolean endsWithAny(String name, List<String> exts) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : exts) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    static TL_keyboard.TL_keyboardInlineButton button(String text, String data) {
        TL_keyboard.TL_inlineButtonTypeCallback type = new TL_keyboard.TL_inlineButtonTypeCallback();
        type.data = data.getBytes(StandardCharsets.UTF_8);
        TL_keyboard.TL_keyboardInlineButton b = new TL_keyboard.TL_keyboardInlineButton();
        b.text = text;
        b.type = type;
        return b;
    }

    /** A press on a message's button (ChatActivity): true when it was one of ours; nothing goes to the network. */
    public static boolean press(BaseFragment fragment, MessageObject m, TL_keyboard.KeyboardButtonProto button) {
        TL_keyboard.TL_inlineButtonTypeCallback cb = TLKeyboardHelper.getType(button, TL_keyboard.TL_inlineButtonTypeCallback.class);
        if (cb == null || cb.data == null || fragment == null || m == null || m.messageOwner == null) {
            return false;
        }
        String data = new String(cb.data, StandardCharsets.UTF_8);
        if (GerehLinks.press(fragment, data)) {
            return true; // a tile's or setting's «open» (q11, q12)
        }
        if (GerehTg.pressDoc(fragment, m, data)) {
            return true; // a Telegram channel post's «دریافت» (T71)
        }
        if (GerehProxy.press(fragment, m, data)) {
            return true; // a Telegram proxy's «باز کردن در تلگرام»
        }
        if (OPEN_NPV.equals(data)) {
            // NPV Tunnel reads, tests and runs it; no other app can (its key is NPV Tunnel's)
            File f = m.getDocument() != null ? FileLoader.getInstance(m.currentAccount).getPathToMessage(m.messageOwner) : null;
            if (f == null || !f.exists()) {
                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.GerehCfgDownloadFirst)).show();
            } else if (fragment.getParentActivity() == null || !AndroidUtilities.openForView(m, fragment.getParentActivity(), fragment.getResourceProvider(), false)) {
                // «انتقال» is offered only by an app that keeps NPV files (Hook.takes)
                Hook app = hook;
                String note = app != null && app.takes(KIND_NPV)
                        ? LocaleController.formatString(R.string.GerehCfgNoNpvApp, app.appName())
                        : LocaleController.getString(R.string.GerehCfgNoNpvAppAlone);
                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.info, note).show();
            }
            return true;
        }
        boolean test = TEST.equals(data);
        if (!test && !MOVE.equals(data)) {
            return false;
        }
        Hook h = hook;
        if (h == null) {
            return true;
        }
        String text = m.messageOwner.message;
        String fileName = fileName(m.messageOwner);
        File file = null;
        if (m.getDocument() != null) {
            file = FileLoader.getInstance(m.currentAccount).getPathToMessage(m.messageOwner);
            if (file == null || !file.exists()) {
                // a file is read only once it is on this phone
                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.GerehCfgDownloadFirst)).show();
                return true;
            }
        }
        final File f = file;
        Utilities.globalQueue.postRunnable(() -> {
            List<Config> found;
            try {
                if (f == null) {
                    found = h.find(text != null ? text : "", null, null);
                } else {
                    String body = fileName != null && endsWithAny(fileName, TEXT_FILES) ? read(f) : "";
                    found = body == null ? Collections.emptyList() : h.find(body, fileName, f.getAbsolutePath());
                }
            } catch (Throwable t) {
                Log.w(TAG, "configs not read: " + t.getClass().getSimpleName());
                found = Collections.emptyList();
            }
            final List<Config> configs = found;
            AndroidUtilities.runOnUIThread(() -> {
                Activity activity = fragment.getParentActivity();
                if (activity == null) {
                    return;
                }
                if (configs.isEmpty()) {
                    BulletinFactory.of(fragment).createSimpleBulletin(R.raw.info, LocaleController.formatString(R.string.GerehCfgNone, h.appName())).show();
                } else if (test) {
                    GerehConfigSheet.show(fragment, configs);
                } else {
                    h.transfer(activity, configs);
                }
            });
        });
        return true;
    }

    /** The sheet's measurement of one: ms, 0 when nothing came back; -1 when it is measured in its section. */
    public static long delay(Config c) {
        Hook h = hook;
        if (h == null || !c.testable) {
            return -1;
        }
        try {
            return h.delay(c);
        } catch (Throwable t) {
            return 0;
        }
    }

    public static void transfer(Activity activity, List<Config> configs) {
        Hook h = hook;
        if (h != null && activity != null && !configs.isEmpty()) {
            h.transfer(activity, configs);
        }
    }

    /** A text file's text; null when it is too large to be a list of configs. */
    private static String read(File file) throws java.io.IOException {
        if (file.length() > MAX_FILE) {
            return null;
        }
        byte[] bytes = new byte[(int) file.length()];
        try (FileInputStream in = new FileInputStream(file)) {
            int at = 0;
            while (at < bytes.length) {
                int n = in.read(bytes, at, bytes.length - at);
                if (n < 0) {
                    break;
                }
                at += n;
            }
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
