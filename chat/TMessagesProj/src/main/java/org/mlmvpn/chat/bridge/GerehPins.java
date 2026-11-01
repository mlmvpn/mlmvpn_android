package org.mlmvpn.chat.bridge;

import org.mlmchat.core.ChatNode;
import org.mlmchat.core.Contact;
import org.mlmchat.core.Group;
import org.mlmchat.core.GroupMessage;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_update;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MLMCHAT: pinned messages (T65; Ehsan, 29 Oct 2026: pinning in groups and channels, official
 * or not, by the owner and the admins, and in one-to-one chats). A pin goes to the core: in a
 * one-to-one chat a {@code Pin} envelope to the contact, in a group or channel a signed event of
 * its log, in an official chat an admin's item. Telegram's pinned bar reads them with a search of
 * the pinned filter, and a pin made on another phone comes as Telegram's own pinned updates.
 */
final class GerehPins {

    /** What Telegram was last told is pinned in each dialog: a change is told as a difference. */
    private static final Map<Long, Set<Integer>> told = new ConcurrentHashMap<>();

    private GerehPins() {
    }

    static boolean handles(TLObject req) {
        return req instanceof TLRPC.TL_messages_updatePinnedMessage
                || req instanceof TLRPC.TL_messages_search && ((TLRPC.TL_messages_search) req).filter instanceof TLRPC.TL_inputMessagesFilterPinned;
    }

    /** One chat as the core knows it: an official feed, a group or channel, or a contact. */
    private static final class Chat {
        String feed;
        Group group;
        Contact contact;
        long dialog;
    }

    private static Chat chatOf(ChatNode node, TLRPC.InputPeer peer) {
        Chat c = new Chat();
        c.dialog = org.telegram.messenger.DialogObject.getPeerDialogId(peer);
        c.feed = GerehFeeds.feedOfInput(peer);
        if (c.feed != null) {
            return c;
        }
        long chat = GerehGroups.chatOf(peer);
        if (chat != 0) {
            c.group = GerehGroups.groupFor(node, chat);
            if (c.group == null) {
                throw new IllegalArgumentException("unknown group");
            }
            return c;
        }
        c.contact = Messenger.contactOf(node, Messenger.peerUser(peer));
        if (c.contact == null) {
            throw new IllegalArgumentException("not a contact");
        }
        return c;
    }

    /** Telegram's ids of the chat's pinned messages, the newest last. */
    private static List<Integer> pinned(ChatNode node, Chat c) {
        List<Integer> out = new ArrayList<>();
        if (c.feed != null) {
            out.addAll(GerehFeeds.pinnedIds(node, c.feed));
        } else if (c.group != null) {
            for (long seq : node.groupPinned(c.group.getId())) {
                out.add(GerehGroups.MSG_BASE + (int) seq);
            }
        } else {
            for (long seq : node.pinned(c.contact.getSk())) {
                out.add((int) seq);
            }
        }
        return out;
    }

    static TLObject answer(ChatNode node, TLObject req) {
        if (req instanceof TLRPC.TL_messages_updatePinnedMessage) {
            TLRPC.TL_messages_updatePinnedMessage q = (TLRPC.TL_messages_updatePinnedMessage) req;
            Chat c = chatOf(node, q.peer);
            if (c.feed != null) {
                GerehFeeds.pin(node, c.feed, q.id, q.unpin);
            } else if (c.group != null) {
                node.groupPin(c.group.getId(), q.id - GerehGroups.MSG_BASE, q.unpin);
            } else {
                node.pin(c.contact.getSk(), q.id, q.unpin);
            }
            Set<Integer> now = new HashSet<>(pinned(node, c));
            told.put(c.dialog, now);
            ArrayList<Integer> ids = new ArrayList<>();
            ids.add(q.id);
            TLRPC.TL_updates ups = new TLRPC.TL_updates();
            ups.updates.add(update(c, ids, !q.unpin));
            ups.date = (int) (System.currentTimeMillis() / 1000);
            return ups;
        }
        // the pinned bar: the chat's pinned messages, newest first
        TLRPC.TL_messages_search q = (TLRPC.TL_messages_search) req;
        Chat c = chatOf(node, q.peer);
        List<Integer> ids = pinned(node, c);
        told.put(c.dialog, new HashSet<>(ids));
        if (c.feed != null) {
            TLRPC.TL_messages_channelMessages r = GerehFeeds.messagesById(node, c.feed, new HashSet<>(ids));
            r.messages.sort((x, y) -> Integer.compare(y.id, x.id));
            r.count = r.messages.size();
            return r;
        }
        TLRPC.messages_Messages r = c.group != null && c.group.getChannel() ? new TLRPC.TL_messages_channelMessages() : new TLRPC.TL_messages_messages();
        for (int i = ids.size() - 1; i >= 0; i--) {
            int id = ids.get(i);
            if (c.group != null) {
                GroupMessage m = GerehGroups.bySeq(node, c.group, id - GerehGroups.MSG_BASE);
                if (m != null) {
                    r.messages.add(c.group.getChannel() ? GerehChannels.toTl(node, m, c.group) : GerehGroups.toTl(m, c.group));
                }
            } else {
                TLRPC.Message m = Messenger.messageFor(node, c.contact, id);
                if (m != null) {
                    r.messages.add(m);
                }
            }
        }
        if (c.contact != null) {
            r.users.add(Messenger.userFor(c.contact));
        }
        r.count = r.messages.size();
        return r;
    }

    /** Telegram's update for `ids` pinned (or not) in the chat. */
    private static TLRPC.Update update(Chat c, ArrayList<Integer> ids, boolean pinned) {
        if (c.feed != null || c.group != null && c.group.getChannel()) {
            TL_update.TL_updatePinnedChannelMessages u = new TL_update.TL_updatePinnedChannelMessages();
            u.channel_id = -c.dialog;
            u.messages = ids;
            u.pinned = pinned;
            u.pts = c.feed != null ? GerehFeeds.nextPts(c.feed) : GerehChannels.nextPts(c.group.getId());
            u.pts_count = 1;
            return u;
        }
        TL_update.TL_updatePinnedMessages u = new TL_update.TL_updatePinnedMessages();
        if (c.group != null) {
            TLRPC.TL_peerChat p = new TLRPC.TL_peerChat();
            p.chat_id = -c.dialog;
            u.peer = p;
        } else {
            TLRPC.TL_peerUser p = new TLRPC.TL_peerUser();
            p.user_id = c.dialog;
            u.peer = p;
        }
        u.messages = ids;
        u.pinned = pinned;
        u.pts = Messenger.nextPtsValue();
        u.pts_count = 1;
        return u;
    }

    /** The newest pinned message's id, for a chat's info page (0: none). */
    static int newest(ChatNode node, TLRPC.InputPeer peer) {
        try {
            List<Integer> ids = pinned(node, chatOf(node, peer));
            return ids.isEmpty() ? 0 : ids.get(ids.size() - 1);
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * Another phone pinned or took a pin off (a contact, a group's or channel's log, an official
     * chat's admin): Telegram is told what changed since it was last told.
     */
    static void changed(ChatNode node, TLRPC.InputPeer peer) {
        Chat c;
        List<Integer> ids;
        try {
            c = chatOf(node, peer);
            ids = pinned(node, c);
        } catch (Throwable t) {
            return;
        }
        Set<Integer> now = new HashSet<>(ids);
        Set<Integer> before = told.put(c.dialog, now);
        ArrayList<Integer> added = new ArrayList<>();
        ArrayList<Integer> gone = new ArrayList<>();
        for (int id : ids) {
            if (before == null || !before.contains(id)) {
                added.add(id);
            }
        }
        if (before != null) {
            for (int id : before) {
                if (!now.contains(id)) {
                    gone.add(id);
                }
            }
        }
        if (added.isEmpty() && gone.isEmpty()) {
            return;
        }
        final Chat chat = c;
        Utilities.stageQueue.postRunnable(() -> {
            TLRPC.TL_updates ups = new TLRPC.TL_updates();
            if (!added.isEmpty()) {
                ups.updates.add(update(chat, added, true));
            }
            if (!gone.isEmpty()) {
                ups.updates.add(update(chat, gone, false));
            }
            ups.date = (int) (System.currentTimeMillis() / 1000);
            MessagesController.getInstance(Messenger.account()).processUpdates(ups, false);
        });
    }
}
