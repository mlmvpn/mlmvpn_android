package org.mlmvpn.chat.bridge;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MLMCHAT: a public Telegram channel's web page (`t.me/s/NAME`, T68) read on this phone into the
 * channel's name, photo and about, and its posts: text with its formatting, photos, videos, voice
 * notes, round videos, stickers, and a line for what the web page does not carry (files, polls,
 * videos too big for it). The page comes from the user's own reader script (GerehTg); this class
 * only reads text, so a change in Telegram's page needs an app update, never a new script.
 */
final class GerehTgParse {
    static final int PHOTO = 1, VIDEO = 2, VOICE = 3, ROUND = 4;
    static final int BOLD = 1, ITALIC = 2, UNDERLINE = 3, STRIKE = 4, CODE = 5, PRE = 6, TEXT_URL = 7, SPOILER = 8, QUOTE = 9;

    static final class Page {
        String name = "";
        String title = "";
        String about = "";
        String photo = "";
        long subscribers;
        final List<Post> posts = new ArrayList<>();
    }

    static final class Post {
        int id;
        int date;
        String text = "";
        final List<Ent> ents = new ArrayList<>();
        String author = "";
        /** Forwarded from (a name, maybe empty); null when not forwarded. */
        String fwd;
        int reply;
        long views;
        boolean edited;
        final List<Media> media = new ArrayList<>();
        /** Lines for what the web page shows but does not carry (a file, a poll, a place). */
        String extra = "";
        /** Files (documents) and videos too big for the web page: shown, not carried. */
        int docs, tooBig;
        /** The post's files (documents): got on a tap, through the file fetcher (T71). */
        final List<Doc> files = new ArrayList<>();
    }

    /** A document of a post as the web page names it: its name, its size as written, and its own post id. */
    static final class Doc {
        String name = "";
        String size = "";
        int item;
    }

    static final class Media {
        int kind;
        /** The post id this item has in Telegram (an album's items each have their own). */
        int item;
        String url = "";
        String thumb = "";
        int w, h, dur;
    }

    static final class Ent {
        final int type, off, len;
        final String url;

        Ent(int type, int off, int len, String url) {
            this.type = type;
            this.off = off;
            this.len = len;
            this.url = url;
        }
    }

    private static final Pattern POST = Pattern.compile("data-post=\"([A-Za-z0-9_]+)/(\\d+)\"");
    private static final Pattern TIME = Pattern.compile("<time datetime=\"(\\d{4})-(\\d\\d)-(\\d\\d)T(\\d\\d):(\\d\\d):(\\d\\d)(?:\\.\\d+)?(Z|[+-]\\d\\d:?\\d\\d)?\"");
    private static final Pattern VIEWS = Pattern.compile("<span class=\"tgme_widget_message_views\">([^<]+)</span>");
    private static final Pattern AUTHOR = Pattern.compile("<span class=\"tgme_widget_message_from_author\"[^>]*>([^<]*)</span>");
    private static final Pattern REPLY = Pattern.compile("<a class=\"tgme_widget_message_reply\"[^>]*href=\"https://t\\.me/([A-Za-z0-9_]+)/(\\d+)");
    private static final Pattern BG = Pattern.compile("background-image:url\\('([^']+)'\\)");
    private static final Pattern WIDTH = Pattern.compile("width:(\\d+)px");
    private static final Pattern PAD = Pattern.compile("padding-top:([\\d.]+)%");
    private static final Pattern HREF_ID = Pattern.compile("href=\"https://t\\.me/[A-Za-z0-9_]+/(\\d+)");
    private static final Pattern VIDEO_SRC = Pattern.compile("<video[^>]*\\ssrc=\"([^\"]+)\"");
    private static final Pattern DURATION = Pattern.compile("duration[^\"]*\">(\\d+(?::\\d+){1,2})</time>");
    private static final Pattern AUDIO = Pattern.compile("<audio[^>]*class=\"tgme_widget_message_voice[^\"]*\"[^>]*src=\"([^\"]+)\"|<audio[^>]*src=\"([^\"]+)\"[^>]*class=\"tgme_widget_message_voice");
    private static final Pattern STICKER = Pattern.compile("tgme_widget_message_sticker[^>]*data-webp=\"([^\"]+)\"");
    private static final Pattern DOC_TITLE = Pattern.compile("tgme_widget_message_document_title[^>]*>(.*?)</div>", Pattern.DOTALL);
    private static final Pattern DOC_WRAP = Pattern.compile("tgme_widget_message_document_wrap\"[^>]*href=\"https://t\\.me/([A-Za-z0-9_]+)/(\\d+)\"");
    private static final Pattern DOC_EXTRA = Pattern.compile("tgme_widget_message_document_extra[^>]*>(.*?)</div>", Pattern.DOTALL);
    private static final Pattern POLL_Q = Pattern.compile("tgme_widget_message_poll_question[^>]*>(.*?)</div>", Pattern.DOTALL);
    private static final Pattern POLL_OPT = Pattern.compile("tgme_widget_message_poll_option_percent[^>]*>([^<]*)</div>.*?tgme_widget_message_poll_option_text[^>]*>(.*?)</div>", Pattern.DOTALL);
    private static final Pattern LOCATION = Pattern.compile("tgme_widget_message_location_wrap[^>]*href=\"([^\"]+)\"");
    private static final Pattern TITLE = Pattern.compile("tgme_channel_info_header_title\"[^>]*>(.*?)</div>", Pattern.DOTALL);
    private static final Pattern ABOUT = Pattern.compile("tgme_channel_info_description\"[^>]*>(.*?)</div>", Pattern.DOTALL);
    private static final Pattern AVATAR = Pattern.compile("tgme_page_photo_image[^>]*>\\s*<img src=\"([^\"]+)\"");
    private static final Pattern SUBS = Pattern.compile("counter_value\">([^<]+)</span>\\s*<span class=\"counter_type\">subscribers?");
    private static final Pattern TAG = Pattern.compile("<(/?)([a-zA-Z][a-zA-Z0-9-]*)([^>]*)>");
    private static final Pattern ATTR_HREF = Pattern.compile("href=\"([^\"]*)\"");
    private static final Pattern ATTR_CLASS = Pattern.compile("class=\"([^\"]*)\"");
    private static final Pattern ATTR_ALT = Pattern.compile("alt=\"([^\"]*)\"");

    private GerehTgParse() {
    }

    /** One page. Posts come in the page's order (oldest first). */
    static Page page(String html) {
        Page pg = new Page();
        Matcher m = TITLE.matcher(html);
        if (m.find()) {
            pg.title = plain(m.group(1));
        }
        m = ABOUT.matcher(html);
        if (m.find()) {
            pg.about = plain(m.group(1));
        }
        m = AVATAR.matcher(html);
        if (m.find()) {
            pg.photo = decode(m.group(1));
        }
        m = SUBS.matcher(html);
        if (m.find()) {
            pg.subscribers = count(m.group(1));
        }
        String[] blocks = html.split("<div class=\"tgme_widget_message_wrap");
        for (int i = 1; i < blocks.length; i++) {
            Post p = post(blocks[i], pg);
            if (p != null) {
                pg.posts.add(p);
            }
        }
        return pg;
    }

    private static Post post(String b, Page pg) {
        Matcher m = POST.matcher(b);
        if (!m.find() || b.contains("service_message")) {
            return null;
        }
        Post p = new Post();
        if (pg.name.isEmpty()) {
            pg.name = m.group(1);
        }
        p.id = Integer.parseInt(m.group(2));
        m = TIME.matcher(b);
        if (m.find()) {
            p.date = date(m);
        }
        m = VIEWS.matcher(b);
        if (m.find()) {
            p.views = count(m.group(1));
        }
        int meta = b.indexOf("tgme_widget_message_meta");
        p.edited = meta >= 0 && b.substring(meta, Math.min(b.length(), meta + 300)).contains("edited");
        m = AUTHOR.matcher(b);
        if (m.find()) {
            p.author = decode(m.group(1)).trim();
        }
        int fw = b.indexOf("tgme_widget_message_forwarded_from_name");
        if (fw >= 0) {
            int end = b.indexOf("</div>", fw);
            String inner = b.substring(b.indexOf('>', fw) + 1, end > fw ? end : b.length());
            p.fwd = plain(inner);
        }
        m = REPLY.matcher(b);
        if (m.find() && m.group(1).equalsIgnoreCase(pg.name)) {
            p.reply = Integer.parseInt(m.group(2));
        }
        media(b, p);
        int t = b.indexOf("<div class=\"tgme_widget_message_text");
        if (t >= 0) {
            int open = b.indexOf('>', t) + 1;
            text(b.substring(open, closeOf(b, open)), p);
        }
        StringBuilder extra = new StringBuilder();
        m = DOC_TITLE.matcher(b);
        Matcher wrap = DOC_WRAP.matcher(b);
        while (m.find()) {
            Matcher ex = DOC_EXTRA.matcher(b);
            Doc d = new Doc();
            d.name = plain(m.group(1));
            d.size = ex.find(m.end()) ? plain(ex.group(1)) : "";
            // each file links to its own post (an album's files each have one)
            d.item = wrap.find() && wrap.group(1).equalsIgnoreCase(pg.name) ? Integer.parseInt(wrap.group(2)) : p.id;
            p.files.add(d);
        }
        m = POLL_Q.matcher(b);
        if (m.find()) {
            line(extra, "📊 " + plain(m.group(1)));
            Matcher o = POLL_OPT.matcher(b);
            while (o.find()) {
                line(extra, "• " + plain(o.group(2)) + " — " + plain(o.group(1)));
            }
        }
        m = LOCATION.matcher(b);
        if (m.find()) {
            line(extra, "📍 " + decode(m.group(1)));
        }
        p.extra = extra.toString();
        return p;
    }

    /** Photos, videos (with their own id inside an album), voice notes, round videos, a sticker. */
    private static void media(String b, Post p) {
        int at = 0;
        while (true) {
            int ph = b.indexOf("<a class=\"tgme_widget_message_photo_wrap", at);
            int vi = b.indexOf("<a class=\"tgme_widget_message_video_player", at);
            int start = ph < 0 ? vi : vi < 0 ? ph : Math.min(ph, vi);
            if (start < 0) {
                break;
            }
            int next = nextItem(b, start + 10);
            String part = b.substring(start, next);
            Media md = new Media();
            Matcher id = HREF_ID.matcher(part);
            md.item = id.find() ? Integer.parseInt(id.group(1)) : p.id;
            if (start == ph) {
                md.kind = PHOTO;
                Matcher u = BG.matcher(part);
                if (u.find()) {
                    md.url = decode(u.group(1));
                }
                size(part, md);
            } else {
                md.kind = part.contains("roundvideo") ? ROUND : VIDEO;
                Matcher u = VIDEO_SRC.matcher(part);
                if (u.find()) {
                    md.url = decode(u.group(1));
                }
                Matcher th = BG.matcher(part);
                if (th.find()) {
                    md.thumb = decode(th.group(1));
                }
                Matcher d = DURATION.matcher(part);
                if (d.find()) {
                    md.dur = seconds(d.group(1));
                }
                size(part, md);
            }
            if (!md.url.isEmpty()) {
                p.media.add(md);
            } else if (md.kind != PHOTO) {
                p.tooBig++; // "Media is too big": the web page shows the video without its file
            }
            at = next;
        }
        int rv = b.indexOf("tgme_widget_message_roundvideo_player");
        if (rv >= 0) {
            String part = b.substring(rv, nextItem(b, rv + 10));
            Matcher u = VIDEO_SRC.matcher(part);
            if (u.find()) {
                Media md = new Media();
                md.kind = ROUND;
                md.item = p.id;
                md.url = decode(u.group(1));
                Matcher d = DURATION.matcher(part);
                if (d.find()) {
                    md.dur = seconds(d.group(1));
                }
                md.w = md.h = 240;
                p.media.add(md);
            }
        }
        Matcher a = AUDIO.matcher(b);
        if (a.find()) {
            Media md = new Media();
            md.kind = VOICE;
            md.item = p.id;
            md.url = decode(a.group(1) != null ? a.group(1) : a.group(2));
            Matcher d = DURATION.matcher(b.substring(a.start()));
            if (d.find()) {
                md.dur = seconds(d.group(1));
            }
            p.media.add(md);
        }
        Matcher s = STICKER.matcher(b);
        if (s.find() && p.media.isEmpty()) {
            Media md = new Media();
            md.kind = PHOTO;
            md.item = p.id;
            md.url = decode(s.group(1));
            md.w = md.h = 256;
            p.media.add(md);
        }
    }

    private static int nextItem(String b, int from) {
        int n = b.length();
        for (String k : new String[]{"<a class=\"tgme_widget_message_photo_wrap", "<a class=\"tgme_widget_message_video_player", "<div class=\"tgme_widget_message_text", "tgme_widget_message_roundvideo_player"}) {
            int i = b.indexOf(k, from);
            if (i >= 0 && i < n) {
                n = i;
            }
        }
        return n;
    }

    private static void size(String part, Media md) {
        Matcher w = WIDTH.matcher(part);
        Matcher pad = PAD.matcher(part);
        if (w.find()) {
            md.w = Integer.parseInt(w.group(1));
            double pct = pad.find() ? Double.parseDouble(pad.group(1)) : 100;
            md.h = Math.max(1, (int) Math.round(md.w * pct / 100));
        }
        if (md.w <= 0) {
            md.w = md.h = 800;
        }
    }

    /** The index just past the `</div>` that closes the div opened before `from`. */
    private static int closeOf(String b, int from) {
        int depth = 1;
        int i = from;
        while (i < b.length()) {
            int open = b.indexOf("<div", i);
            int close = b.indexOf("</div>", i);
            if (close < 0) {
                return b.length();
            }
            if (open >= 0 && open < close) {
                depth++;
                i = open + 4;
            } else {
                depth--;
                if (depth == 0) {
                    return close;
                }
                i = close + 6;
            }
        }
        return b.length();
    }

    /** A post's text: its HTML as plain text with Telegram's entities, offsets in UTF-16 units. */
    static void text(String html, Post p) {
        StringBuilder sb = new StringBuilder();
        List<Object[]> open = new ArrayList<>(); // {tag, type, start, url}
        int emoji = 0;
        int pre = 0;
        Matcher m = TAG.matcher(html);
        int at = 0;
        while (m.find()) {
            sb.append(decode(html.substring(at, m.start())));
            at = m.end();
            boolean close = !m.group(1).isEmpty();
            String tag = m.group(2).toLowerCase(Locale.ROOT);
            String attrs = m.group(3);
            if (tag.equals("br")) {
                sb.append('\n');
                continue;
            }
            if (tag.equals("img")) {
                Matcher alt = ATTR_ALT.matcher(attrs);
                if (alt.find()) {
                    sb.append(decode(alt.group(1)));
                }
                continue;
            }
            if (close) {
                for (int k = open.size() - 1; k >= 0; k--) {
                    Object[] o = open.get(k);
                    if (o[0].equals(tag)) {
                        open.remove(k);
                        int type = (Integer) o[1];
                        if (type == -1) {
                            emoji--;
                        } else if (type == PRE) {
                            pre--;
                        }
                        int start = (Integer) o[2];
                        if (type > 0 && sb.length() > start) {
                            p.ents.add(new Ent(type, start, sb.length() - start, (String) o[3]));
                        }
                        break;
                    }
                }
                continue;
            }
            if (attrs.endsWith("/")) {
                continue;
            }
            int type = 0;
            String url = null;
            String cls = "";
            Matcher c = ATTR_CLASS.matcher(attrs);
            if (c.find()) {
                cls = c.group(1);
            }
            switch (tag) {
                case "b":
                case "strong":
                    type = emoji > 0 ? 0 : BOLD;
                    break;
                case "i":
                case "em":
                    if (cls.contains("emoji")) {
                        type = -1;
                        emoji++;
                    } else {
                        type = ITALIC;
                    }
                    break;
                case "u":
                case "ins":
                    type = UNDERLINE;
                    break;
                case "s":
                case "del":
                case "strike":
                    type = STRIKE;
                    break;
                case "code":
                    type = pre > 0 ? 0 : CODE;
                    break;
                case "pre":
                    type = PRE;
                    pre++;
                    break;
                case "tg-spoiler":
                    type = SPOILER;
                    break;
                case "span":
                    type = cls.contains("tg-spoiler") ? SPOILER : 0;
                    break;
                case "blockquote":
                    type = QUOTE;
                    break;
                case "a":
                    Matcher h = ATTR_HREF.matcher(attrs);
                    if (h.find()) {
                        url = decode(h.group(1));
                        // the page's own search links (#tags) and links shown as they are need
                        // nothing: Telegram finds those in the text by itself
                        type = url.startsWith("?") ? 0 : TEXT_URL;
                    }
                    break;
                default:
                    break;
            }
            open.add(new Object[]{tag, type, sb.length(), url});
        }
        sb.append(decode(html.substring(at)));
        // a link whose text is the address itself goes as plain text
        List<Ent> keep = new ArrayList<>();
        String s = sb.toString();
        for (Ent e : p.ents) {
            if (e.type == TEXT_URL && e.url != null && s.substring(e.off, e.off + e.len).trim().equals(e.url.trim())) {
                continue;
            }
            keep.add(e);
        }
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) {
            end--;
        }
        p.ents.clear();
        for (Ent e : keep) {
            if (e.off < end) {
                p.ents.add(new Ent(e.type, e.off, Math.min(e.len, end - e.off), e.url));
            }
        }
        p.text = s.substring(0, end);
    }

    /** HTML as plain text: line breaks kept, tags gone, characters decoded. */
    static String plain(String html) {
        return decode(html.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("<[^>]+>", "")).trim();
    }

    static String decode(String s) {
        if (s.indexOf('&') < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char ch = s.charAt(i);
            int semi = ch == '&' ? s.indexOf(';', i) : -1;
            if (semi > i && semi - i <= 10) {
                String ent = s.substring(i + 1, semi);
                String rep = null;
                switch (ent) {
                    case "amp": rep = "&"; break;
                    case "lt": rep = "<"; break;
                    case "gt": rep = ">"; break;
                    case "quot": rep = "\""; break;
                    case "apos": rep = "'"; break;
                    case "nbsp": rep = " "; break;
                    default:
                        try {
                            if (ent.startsWith("#x") || ent.startsWith("#X")) {
                                rep = new String(Character.toChars(Integer.parseInt(ent.substring(2), 16)));
                            } else if (ent.startsWith("#")) {
                                rep = new String(Character.toChars(Integer.parseInt(ent.substring(1))));
                            }
                        } catch (RuntimeException ignored) {
                        }
                }
                if (rep != null) {
                    out.append(rep);
                    i = semi + 1;
                    continue;
                }
            }
            out.append(ch);
            i++;
        }
        return out.toString();
    }

    private static int date(Matcher m) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT);
        c.clear();
        c.set(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) - 1, Integer.parseInt(m.group(3)),
                Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6)));
        long s = c.getTimeInMillis() / 1000;
        String z = m.group(7);
        if (z != null && !z.equals("Z")) {
            String d = z.replace(":", "");
            int off = Integer.parseInt(d.substring(1, 3)) * 3600 + Integer.parseInt(d.substring(3, 5)) * 60;
            s += z.charAt(0) == '+' ? -off : off;
        }
        return (int) s;
    }

    /** "0:06" or "1:02:03" in seconds. */
    static int seconds(String t) {
        int s = 0;
        for (String part : t.split(":")) {
            s = s * 60 + Integer.parseInt(part);
        }
        return s;
    }

    /** "9.22M", "16.5K", "1 234" as a number. */
    static long count(String v) {
        String t = v.trim().replace(",", "").replace(" ", "").replace(" ", "");
        double mul = 1;
        if (t.endsWith("K")) {
            mul = 1e3;
        } else if (t.endsWith("M")) {
            mul = 1e6;
        } else if (t.endsWith("B")) {
            mul = 1e9;
        }
        if (mul > 1) {
            t = t.substring(0, t.length() - 1);
        }
        try {
            return Math.round(Double.parseDouble(t) * mul);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void line(StringBuilder sb, String s) {
        if (sb.length() > 0) {
            sb.append('\n');
        }
        sb.append(s);
    }
}
