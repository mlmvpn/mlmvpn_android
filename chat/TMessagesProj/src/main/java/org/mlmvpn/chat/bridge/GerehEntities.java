package org.mlmvpn.chat.bridge;

import android.text.SpannableString;
import android.text.style.URLSpan;
import android.text.util.Linkify;

import org.mlmchat.core.ChatNode;
import org.mlmchat.core.TextEntity;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.List;

/**
 * MLMCHAT: a message's formatting, mentions and links (SPEC 5 `Entity`). With a server, Telegram
 * gets them back in the answer to a send and in every message it loads, and the server finds the
 * links in plain text. Here nobody did: the answer to a send carried none, and Telegram puts the
 * answer's list in place of its own copy's, so a bold word, a link on text or a mention showed
 * only while the message was going, on both phones and in both apps (26 Oct 2026, a Dr.Net
 * phone and an MLMVPN phone). Now the core carries them, and this side finds the web links.
 */
final class GerehEntities {

    private GerehEntities() {
    }

    /** A send request's entities as the core keeps them: the kinds SPEC 5 has; a mention of someone without a key here is left out. */
    static List<TextEntity> toCore(ArrayList<TLRPC.MessageEntity> list) {
        ArrayList<TextEntity> out = new ArrayList<>();
        if (list == null) {
            return out;
        }
        for (TLRPC.MessageEntity e : list) {
            String url = "";
            String sk = "";
            String lang = "";
            int type;
            if (e instanceof TLRPC.TL_messageEntityBold) {
                type = TextEntity.BOLD;
            } else if (e instanceof TLRPC.TL_messageEntityItalic) {
                type = TextEntity.ITALIC;
            } else if (e instanceof TLRPC.TL_messageEntityUnderline) {
                type = TextEntity.UNDERLINE;
            } else if (e instanceof TLRPC.TL_messageEntityStrike) {
                type = TextEntity.STRIKE;
            } else if (e instanceof TLRPC.TL_messageEntitySpoiler) {
                type = TextEntity.SPOILER;
            } else if (e instanceof TLRPC.TL_messageEntityCode) {
                type = TextEntity.CODE;
            } else if (e instanceof TLRPC.TL_messageEntityPre) {
                type = TextEntity.PRE;
                lang = e.language != null ? e.language : "";
            } else if (e instanceof TLRPC.TL_messageEntityBlockquote) {
                type = TextEntity.QUOTE;
            } else if (e instanceof TLRPC.TL_messageEntityTextUrl) {
                type = TextEntity.LINK;
                url = e.url != null ? e.url : "";
            } else if (e instanceof TLRPC.TL_messageEntityHashtag) {
                type = TextEntity.HASHTAG;
            } else if (e instanceof TLRPC.TL_messageEntityMentionName) {
                type = TextEntity.MENTION;
                sk = skOf(((TLRPC.TL_messageEntityMentionName) e).user_id, false);
            } else if (e instanceof TLRPC.TL_inputMessageEntityMentionName) {
                TLRPC.InputUser u = ((TLRPC.TL_inputMessageEntityMentionName) e).user_id;
                type = TextEntity.MENTION;
                sk = u == null ? null : skOf(u.user_id, u instanceof TLRPC.TL_inputUserSelf);
            } else {
                continue; // links in plain text, custom emoji and the rest: found again on each phone, or not in SPEC 5
            }
            if (sk == null) {
                continue;
            }
            out.add(new TextEntity(type, e.offset, e.length, url, sk, lang));
        }
        return out;
    }

    private static String skOf(long uid, boolean self) {
        ChatNode node = GerehIdentity.node();
        if (node != null && (self || uid == UserConfig.getInstance(Messenger.account()).getClientUserId())) {
            return node.getMe().getSk();
        }
        return Messenger.skForUser(uid);
    }

    /**
     * What Telegram draws on a message: the core's entities for `raw` (the text as sent), moved
     * onto `shown` (the text as Telegram gets it; {@link GerehInvites#shown} may rewrite links in
     * it), and the web links in `shown`, as a server would add them.
     */
    static ArrayList<TLRPC.MessageEntity> forTl(List<TextEntity> list, String raw, String shown) {
        ArrayList<TLRPC.MessageEntity> out = new ArrayList<>();
        if (shown == null || shown.isEmpty()) {
            return out;
        }
        if (raw == null) {
            raw = shown;
        }
        // Only a run inside the unchanged start or end of the text keeps its place.
        int head = 0;
        while (head < raw.length() && head < shown.length() && raw.charAt(head) == shown.charAt(head)) {
            head++;
        }
        int tail = 0;
        while (tail < raw.length() - head && tail < shown.length() - head
                && raw.charAt(raw.length() - 1 - tail) == shown.charAt(shown.length() - 1 - tail)) {
            tail++;
        }
        int shift = shown.length() - raw.length();
        if (list != null) {
            for (TextEntity t : list) {
                boolean before = t.getOffset() + t.getLength() <= head;
                if (!before && t.getOffset() < raw.length() - tail) {
                    continue;
                }
                TLRPC.MessageEntity e = toTl(t);
                if (e != null) {
                    e.offset = before ? t.getOffset() : t.getOffset() + shift;
                    e.length = t.getLength();
                    out.add(e);
                }
            }
        }
        addWebLinks(shown, out);
        return out;
    }

    /**
     * The entities for the answer to a send: Telegram's own copy holds the text as it was typed,
     * and takes this list in place of its own (SendMessagesHelper, `updateShortSentMessage`).
     */
    static ArrayList<TLRPC.MessageEntity> forSent(List<TextEntity> list, String raw) {
        return forTl(list, raw, raw);
    }

    /** The core's entities and the web links on a Telegram message (its text or caption, already set). */
    static void dress(TLRPC.Message msg, List<TextEntity> list, String raw) {
        ArrayList<TLRPC.MessageEntity> entities = forTl(list, raw, msg.message);
        if (!entities.isEmpty()) {
            msg.entities = entities;
            msg.flags |= TLRPC.MESSAGE_FLAG_HAS_ENTITIES;
        }
    }

    private static TLRPC.MessageEntity toTl(TextEntity t) {
        switch (t.getType()) {
            case TextEntity.BOLD:
                return new TLRPC.TL_messageEntityBold();
            case TextEntity.ITALIC:
                return new TLRPC.TL_messageEntityItalic();
            case TextEntity.UNDERLINE:
                return new TLRPC.TL_messageEntityUnderline();
            case TextEntity.STRIKE:
                return new TLRPC.TL_messageEntityStrike();
            case TextEntity.SPOILER:
                return new TLRPC.TL_messageEntitySpoiler();
            case TextEntity.CODE:
                return new TLRPC.TL_messageEntityCode();
            case TextEntity.PRE: {
                TLRPC.TL_messageEntityPre e = new TLRPC.TL_messageEntityPre();
                e.language = t.getLang();
                return e;
            }
            case TextEntity.QUOTE:
                return new TLRPC.TL_messageEntityBlockquote();
            case TextEntity.LINK: {
                if (t.getUrl().isEmpty()) {
                    return new TLRPC.TL_messageEntityUrl();
                }
                TLRPC.TL_messageEntityTextUrl e = new TLRPC.TL_messageEntityTextUrl();
                e.url = t.getUrl();
                return e;
            }
            case TextEntity.MENTION: {
                if (t.getSk().isEmpty()) {
                    return null;
                }
                TLRPC.TL_messageEntityMentionName e = new TLRPC.TL_messageEntityMentionName();
                e.user_id = GerehIdentity.userIdFor(t.getSk());
                return e;
            }
            case TextEntity.HASHTAG:
                return new TLRPC.TL_messageEntityHashtag();
            default:
                return null;
        }
    }

    /** Web links in plain text, found as Telegram finds them in a message still being sent; none inside code or a link already there. */
    private static void addWebLinks(String text, ArrayList<TLRPC.MessageEntity> into) {
        if (!MessageObject.containsUrls(text)) {
            return;
        }
        SpannableString s = new SpannableString(text);
        if (!AndroidUtilities.addLinksSafe(s, Linkify.WEB_URLS, false, false)) {
            return;
        }
        int already = into.size();
        for (URLSpan span : s.getSpans(0, s.length(), URLSpan.class)) {
            int start = s.getSpanStart(span);
            int end = s.getSpanEnd(span);
            if (start < 0 || end <= start || covered(into, already, start, end)) {
                continue;
            }
            TLRPC.TL_messageEntityUrl e = new TLRPC.TL_messageEntityUrl();
            e.offset = start;
            e.length = end - start;
            into.add(e);
        }
    }

    private static boolean covered(ArrayList<TLRPC.MessageEntity> list, int count, int start, int end) {
        for (int i = 0; i < count; i++) {
            TLRPC.MessageEntity e = list.get(i);
            boolean blocks = e instanceof TLRPC.TL_messageEntityCode || e instanceof TLRPC.TL_messageEntityPre
                    || e instanceof TLRPC.TL_messageEntityTextUrl || e instanceof TLRPC.TL_messageEntityUrl
                    || e instanceof TLRPC.TL_messageEntityMentionName;
            if (blocks && start < e.offset + e.length && e.offset < end) {
                return true;
            }
        }
        return false;
    }
}
