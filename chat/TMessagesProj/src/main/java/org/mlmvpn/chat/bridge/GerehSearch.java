package org.mlmvpn.chat.bridge;

import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;


/**
 * MLMCHAT: a chat's info page tabs (photos and videos, files, voice, music, links, GIFs) and their
 * counts, from the chat's own history (Ehsan, 30 Oct 2026: every tab was empty, as the bridge
 * answered each search with nothing). Bridge.answer turns the search into a history page of the
 * same chat, answered the usual way, and keeps the messages of the tab's kind.
 */
final class GerehSearch {
    /** How far back one search looks: the history page it reads. */
    private static final int PAGE = 1000;

    private GerehSearch() {
    }

    /** A tab's search (a kind of media, no text) or the tabs' counts. */
    static boolean handles(TLObject r) {
        if (r instanceof TLRPC.TL_messages_getSearchCounters) {
            return true;
        }
        if (!(r instanceof TLRPC.TL_messages_search)) {
            return false;
        }
        TLRPC.TL_messages_search q = (TLRPC.TL_messages_search) r;
        return q.filter != null && !(q.filter instanceof TLRPC.TL_inputMessagesFilterEmpty)
                && !(q.filter instanceof TLRPC.TL_inputMessagesFilterPinned) && (q.q == null || q.q.isEmpty());
    }

    /** The history page that answers it. */
    static TLRPC.TL_messages_getHistory history(TLObject r) {
        TLRPC.TL_messages_getHistory h = new TLRPC.TL_messages_getHistory();
        if (r instanceof TLRPC.TL_messages_search) {
            TLRPC.TL_messages_search q = (TLRPC.TL_messages_search) r;
            h.peer = q.peer;
            h.offset_id = q.offset_id;
        } else {
            h.peer = ((TLRPC.TL_messages_getSearchCounters) r).peer;
        }
        h.limit = PAGE;
        return h;
    }

    /** The search's answer from the history page: the tab's messages, or every tab's count. */
    static TLObject from(TLObject r, TLObject page) {
        if (!(page instanceof TLRPC.messages_Messages)) {
            return page;
        }
        TLRPC.messages_Messages m = (TLRPC.messages_Messages) page;
        if (r instanceof TLRPC.TL_messages_getSearchCounters) {
            TLRPC.TL_messages_getSearchCounters q = (TLRPC.TL_messages_getSearchCounters) r;
            SerializedData out = new SerializedData(16 + 16 * q.filters.size());
            out.writeInt32(0x1cb5c415);
            out.writeInt32(q.filters.size());
            for (TLRPC.MessagesFilter f : q.filters) {
                TLRPC.TL_messages_searchCounter c = new TLRPC.TL_messages_searchCounter();
                c.filter = f;
                for (TLRPC.Message msg : m.messages) {
                    if (matches(f, msg)) {
                        c.count++;
                    }
                }
                c.inexact = m.messages.size() >= PAGE;
                c.serializeToStream(out);
            }
            SerializedData in = new SerializedData(out.toByteArray());
            TLObject parsed = r.deserializeResponse(in, in.readInt32(true), true);
            out.cleanup();
            in.cleanup();
            return parsed;
        }
        TLRPC.TL_messages_search q = (TLRPC.TL_messages_search) r;
        TLRPC.TL_messages_messages res = new TLRPC.TL_messages_messages();
        int limit = q.limit > 0 ? q.limit : 50;
        for (TLRPC.Message msg : m.messages) {
            if (matches(q.filter, msg)) {
                res.messages.add(msg);
                if (res.messages.size() >= limit) {
                    break;
                }
            }
        }
        res.users.addAll(m.users);
        res.chats.addAll(m.chats);
        return res;
    }

    /** Whether a message belongs in the tab of filter `f`. */
    static boolean matches(TLRPC.MessagesFilter f, TLRPC.Message msg) {
        if (msg == null || msg instanceof TLRPC.TL_messageService) {
            return false;
        }
        TLRPC.MessageMedia media = msg.media;
        boolean photo = media instanceof TLRPC.TL_messageMediaPhoto;
        TLRPC.Document doc = media instanceof TLRPC.TL_messageMediaDocument ? media.document : null;
        boolean video = doc != null && MessageObject.isVideoDocument(doc);
        boolean round = doc != null && MessageObject.isRoundVideoDocument(doc);
        boolean voice = doc != null && MessageObject.isVoiceDocument(doc);
        boolean music = doc != null && MessageObject.isMusicDocument(doc);
        boolean gif = doc != null && MessageObject.isGifDocument(doc);
        boolean sticker = doc != null && MessageObject.isStickerDocument(doc);
        boolean file = doc != null && !video && !round && !voice && !music && !gif && !sticker;
        if (f instanceof TLRPC.TL_inputMessagesFilterPhotoVideo) {
            return photo || video;
        } else if (f instanceof TLRPC.TL_inputMessagesFilterPhotos) {
            return photo;
        } else if (f instanceof TLRPC.TL_inputMessagesFilterVideo) {
            return video;
        } else if (f instanceof TLRPC.TL_inputMessagesFilterDocument) {
            return file;
        } else if (f instanceof TLRPC.TL_inputMessagesFilterMusic) {
            return music;
        } else if (f instanceof TLRPC.TL_inputMessagesFilterVoice) {
            return voice;
        } else if (f instanceof TLRPC.TL_inputMessagesFilterRoundVoice) {
            return voice || round;
        } else if (f instanceof TLRPC.TL_inputMessagesFilterRoundVideo) {
            return round;
        } else if (f instanceof TLRPC.TL_inputMessagesFilterGif) {
            return gif;
        } else if (f instanceof TLRPC.TL_inputMessagesFilterUrl) {
            return hasLink(msg);
        }
        return false;
    }

    private static boolean hasLink(TLRPC.Message msg) {
        if (msg.media instanceof TLRPC.TL_messageMediaWebPage) {
            return true;
        }
        if (msg.entities != null) {
            for (TLRPC.MessageEntity e : msg.entities) {
                if (e instanceof TLRPC.TL_messageEntityUrl || e instanceof TLRPC.TL_messageEntityTextUrl) {
                    return true;
                }
            }
        }
        String t = msg.message;
        return t != null && (t.contains("://") || t.contains("www."));
    }
}
