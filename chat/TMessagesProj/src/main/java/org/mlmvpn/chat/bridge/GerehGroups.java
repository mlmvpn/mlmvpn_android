package org.mlmvpn.chat.bridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import org.mlmchat.core.ChatNode;
import org.mlmchat.core.Group;
import org.mlmchat.core.GroupMarks;
import org.mlmchat.core.GroupMember;
import org.mlmchat.core.GroupMessage;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_update;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MLMCHAT: groups (SPEC 7, phase 5) as Telegram's basic groups. They share the account's pts with
 * the one-to-one chats, so they fit the bridge's single "server" (`getDifference`) as they are.
 *
 * Ids: a group's chat id is 31 bits of SHA-256("mlmchat/tg-chat-id/1" || group id); a group
 * message's id is {@link #MSG_BASE} plus the core's own number for it, apart from the one-to-one
 * messages' numbers. Members who are not contacts show under the name their card gives.
 */
final class GerehGroups {
    private static final String TAG = "MLMCHAT";
    /** Group message ids start here; one-to-one ones (the core's seq) stay below. */
    static final int MSG_BASE = 1_500_000_000;
    /** Ids of the «created» line, one per group or channel, below all its messages (see createdLine). */
    static final int CREATED_BASE = 1_400_000_000;

    private static final Map<Long, String> gidByChat = new ConcurrentHashMap<>();

    private GerehGroups() {
    }

    /** Member rights, as the core's `group::member` bits (SPEC 7.1). */
    private static final int M_TEXT = 1, M_PHOTO = 2, M_VIDEO = 4, M_FILE = 8, M_VOICE = 16, M_ROUND = 32,
            M_STICKER = 64, M_POLL = 128, M_LINK = 256, M_ADD_MEMBERS = 512, M_PIN = 1024, M_CHANGE_INFO = 2048;
    private static final int M_DEFAULT = M_TEXT | M_PHOTO | M_VIDEO | M_FILE | M_VOICE | M_ROUND | M_STICKER | M_POLL | M_LINK | M_ADD_MEMBERS;

    static long chatIdFor(String gidHex) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            d.update("mlmchat/tg-chat-id/1".getBytes(StandardCharsets.US_ASCII));
            d.update(GerehIdentity.hex(gidHex));
            byte[] h = d.digest();
            long v = ((h[0] & 0x7FL) << 24) | ((h[1] & 0xFFL) << 16) | ((h[2] & 0xFFL) << 8) | (h[3] & 0xFFL);
            long id = v == 0 ? 1 : v;
            gidByChat.put(id, gidHex);
            return id;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The group a Telegram chat id stands for, if this phone has it. */
    static Group groupFor(ChatNode node, long chatId) {
        String gid = gidByChat.get(chatId);
        if (gid == null) {
            for (Group g : node.groups()) {
                chatIdFor(g.getId());
            }
            gid = gidByChat.get(chatId);
        }
        return gid == null ? null : node.groupState(gid);
    }

    /** True for the requests only groups bring (the shared ones are routed by their peer). */
    static boolean handles(TLObject req) {
        return GerehChannels.handles(req)
                || req instanceof TLRPC.TL_messages_createChat
                || req instanceof TLRPC.TL_messages_getFullChat
                || req instanceof TLRPC.TL_messages_addChatUser
                || req instanceof TLRPC.TL_messages_deleteChatUser
                || req instanceof TLRPC.TL_messages_editChatTitle
                || req instanceof TLRPC.TL_messages_editChatAdmin
                || req instanceof TLRPC.TL_messages_getChats
                || req instanceof TLRPC.TL_messages_editChatAbout
                || req instanceof TLRPC.TL_messages_editChatDefaultBannedRights
                || req instanceof TLRPC.TL_messages_deleteChat
                || req instanceof TLRPC.TL_messages_getMessageReadParticipants;
    }

    /** The input peer Telegram knows a group (a chat) or a channel by. */
    static TLRPC.InputPeer inputPeerFor(Group g) {
        long chat = chatIdFor(g.getId());
        if (g.getChannel()) {
            TLRPC.TL_inputPeerChannel p = new TLRPC.TL_inputPeerChannel();
            p.channel_id = chat;
            return p;
        }
        TLRPC.TL_inputPeerChat p = new TLRPC.TL_inputPeerChat();
        p.chat_id = chat;
        return p;
    }

    /** The chat or channel id an input peer names; 0 for a user. */
    static long chatOf(TLRPC.InputPeer p) {
        if (p instanceof TLRPC.TL_inputPeerChannel) {
            return ((TLRPC.TL_inputPeerChannel) p).channel_id;
        }
        return p instanceof TLRPC.TL_inputPeerChat ? ((TLRPC.TL_inputPeerChat) p).chat_id : 0;
    }

    // ---- Telegram objects ------------------------------------------------------------------

    private static String selfSk() {
        ChatNode node = GerehIdentity.node();
        return node == null ? "" : node.getMe().getSk();
    }

    /** A group as Telegram's basic group; a channel as Telegram's broadcast channel. */
    static TLRPC.Chat chatFor(Group g) {
        if (g.getChannel()) {
            return GerehChannels.channelFor(g, selfSk());
        }
        TLRPC.TL_chat c = new TLRPC.TL_chat();
        c.id = chatIdFor(g.getId());
        c.title = TextUtils.isEmpty(g.getName()) ? "?" : g.getName();
        c.photo = GerehPhotos.chatPhoto(g);
        c.participants_count = g.getMembers().size();
        c.date = (int) (System.currentTimeMillis() / 1000);
        c.version = 1;
        c.creator = g.getOwner().equals(selfSk());
        c.left = !g.getMember();
        c.deactivated = g.getDissolved();
        if (g.getAdmin() && !c.creator) {
            TLRPC.TL_chatAdminRights r = new TLRPC.TL_chatAdminRights();
            int a = g.getAdminRights();
            r.change_info = (a & 1) != 0;
            r.delete_messages = (a & 2) != 0;
            r.ban_users = (a & 4) != 0;
            r.invite_users = (a & 8) != 0;
            r.pin_messages = (a & 16) != 0;
            r.add_admins = (a & 32) != 0;
            r.anonymous = (a & 64) != 0;
            c.admin_rights = r;
            c.flags |= 1 << 14;
        }
        // what members may do: the group's defaults to its admins (the page that edits them) and to
        // an outsider; to a member, their own rights now, the defaults less a restriction of theirs
        // (SPEC 7.2 `Restrict`), since a basic group has no rights of one member alone
        int mine = g.getMyRights() >= 0 && g.getMyRights() <= Integer.MAX_VALUE ? (int) g.getMyRights() : g.getDefaults();
        c.default_banned_rights = bannedRights(g.getAdmin() || !g.getMember() ? g.getDefaults() : g.getCanPost() ? mine : 0);
        c.flags |= 1 << 18;
        // slow mode (q13, T73): one file at a time and no forwards of several, as Telegram's
        // screens hold a member to it when this is set
        c.slowmode_enabled = g.getSlowSeconds() > 0;
        return c;
    }

    /**
     * The members with an exception of their own standing now (SPEC 7.2 `Restrict`), as Telegram's
     * restricted participants: the rights left them and until when (0: for good). For the group's
     * «Permissions» page, which lists them under its «exceptions».
     */
    static ArrayList<TLObject> restrictedParticipants(long chatId) {
        ArrayList<TLObject> out = new ArrayList<>();
        ChatNode node = GerehIdentity.node();
        Group g = node != null ? groupFor(node, chatId) : null;
        if (g == null) {
            return out;
        }
        long far = System.currentTimeMillis() + 366L * 86_400_000L;
        int date = (int) (System.currentTimeMillis() / 1000);
        for (GroupMember m : g.getMembers()) {
            if (m.getRestricted() < 0 || m.getAdmin() || m.getOwner()) {
                continue;
            }
            TLRPC.TL_channelParticipantBanned p = new TLRPC.TL_channelParticipantBanned();
            TLRPC.TL_peerUser pu = new TLRPC.TL_peerUser();
            pu.user_id = GerehIdentity.userIdFor(m.getSk());
            p.peer = pu;
            p.banned_rights = bannedRights(m.getRestricted() & g.getDefaults());
            p.banned_rights.until_date = m.getRestrictedUntil() > far ? 0 : (int) (m.getRestrictedUntil() / 1000);
            p.kicked_by = GerehIdentity.userIdFor(g.getOwner());
            p.date = date;
            out.add(p);
        }
        return out;
    }

    /**
     * Everyone banned from a group now (SPEC 7.1), as Telegram's removed participants, for the
     * «Removed users» page a basic group does not have by itself (Ehsan, 31 Oct 2026: there was no
     * place to see who was blocked and let them back). Named by the card each had when banned; their
     * users are put where the page reads names. Tapping one lifts the ban (`Unban`, T59).
     */
    static ArrayList<TLObject> bannedParticipants(long chatId) {
        ArrayList<TLObject> out = new ArrayList<>();
        ChatNode node = GerehIdentity.node();
        Group g = node != null ? groupFor(node, chatId) : null;
        if (g == null || !g.getAdmin()) {
            return out;
        }
        java.util.Map<String, org.mlmchat.core.Contact> contacts = new java.util.HashMap<>();
        for (org.mlmchat.core.Contact c : node.contacts()) {
            contacts.put(c.getSk(), c);
        }
        ArrayList<TLRPC.User> users = new ArrayList<>();
        long far = System.currentTimeMillis() + 366L * 86_400_000L;
        int date = (int) (System.currentTimeMillis() / 1000);
        for (java.util.Map.Entry<String, Long> e : g.getBanned().entrySet()) {
            TLRPC.TL_channelParticipantBanned p = new TLRPC.TL_channelParticipantBanned();
            TLRPC.TL_peerUser pu = new TLRPC.TL_peerUser();
            pu.user_id = GerehIdentity.userIdFor(e.getKey());
            p.peer = pu;
            p.left = true;
            p.banned_rights = new TLRPC.TL_chatBannedRights();
            p.banned_rights.view_messages = true;
            p.banned_rights.send_messages = true;
            p.banned_rights.until_date = e.getValue() > far ? 0 : (int) (e.getValue() / 1000);
            p.kicked_by = GerehIdentity.userIdFor(g.getOwner());
            p.date = date;
            out.add(p);
            String name = g.getBannedNames().get(e.getKey());
            users.add(userFor(new GroupMember(e.getKey(), name != null ? name : "", false, false, 0, "", 0), contacts));
        }
        if (!users.isEmpty()) {
            org.telegram.messenger.MessagesController.getInstance(Messenger.account()).putUsers(users, false);
        }
        return out;
    }

    /** Telegram's banned rights from the core's member rights (`group::member` bits). */
    static TLRPC.TL_chatBannedRights bannedRights(int rights) {
        TLRPC.TL_chatBannedRights b = new TLRPC.TL_chatBannedRights();
        b.send_plain = (rights & M_TEXT) == 0;
        b.send_messages = b.send_plain;
        b.send_photos = (rights & M_PHOTO) == 0;
        b.send_videos = (rights & M_VIDEO) == 0;
        b.send_docs = (rights & M_FILE) == 0;
        b.send_audios = b.send_docs;
        b.send_voices = (rights & M_VOICE) == 0;
        b.send_roundvideos = (rights & M_ROUND) == 0;
        b.send_stickers = (rights & M_STICKER) == 0;
        b.send_gifs = b.send_stickers;
        b.send_games = true;
        b.send_inline = true;
        b.send_polls = (rights & M_POLL) == 0;
        b.embed_links = (rights & M_LINK) == 0;
        b.invite_users = (rights & M_ADD_MEMBERS) == 0;
        b.pin_messages = (rights & M_PIN) == 0;
        b.change_info = (rights & M_CHANGE_INFO) == 0;
        b.send_media = b.send_photos && b.send_videos && b.send_docs && b.send_voices && b.send_roundvideos;
        return b;
    }

    /** A member with an exception of their own that leaves them no text, standing now (GerehMute). */
    static boolean mutedNow(GroupMember m, long nowMs) {
        return m.getRestricted() >= 0 && (m.getRestricted() & M_TEXT) == 0 && m.getRestrictedUntil() > nowMs;
    }

    /** The core's member rights from what Telegram's permission screen sets. */
    static int memberRights(TLRPC.TL_chatBannedRights b) {
        if (b == null) {
            return M_DEFAULT;
        }
        int r = 0;
        if (!b.send_plain && !b.send_messages) r |= M_TEXT;
        if (!b.send_photos && !b.send_media) r |= M_PHOTO;
        if (!b.send_videos && !b.send_media) r |= M_VIDEO;
        if (!b.send_docs && !b.send_media) r |= M_FILE;
        if (!b.send_voices && !b.send_media) r |= M_VOICE;
        if (!b.send_roundvideos && !b.send_media) r |= M_ROUND;
        if (!b.send_stickers) r |= M_STICKER;
        if (!b.send_polls) r |= M_POLL;
        if (!b.embed_links) r |= M_LINK;
        if (!b.invite_users) r |= M_ADD_MEMBERS;
        if (!b.pin_messages) r |= M_PIN;
        if (!b.change_info) r |= M_CHANGE_INFO;
        return r;
    }

    /** A member as a Telegram user: a contact as Messenger shows them, anyone else from their card. */
    static TLRPC.User userFor(GroupMember m, Map<String, org.mlmchat.core.Contact> contacts) {
        if (m.getSk().equals(selfSk())) {
            return UserConfig.getInstance(Messenger.account()).getCurrentUser();
        }
        org.mlmchat.core.Contact c = contacts.get(m.getSk());
        if (c != null) {
            return Messenger.userFor(c);
        }
        TLRPC.TL_user u = new TLRPC.TL_user();
        u.id = GerehIdentity.userIdFor(m.getSk());
        Messenger.rememberUser(u.id, m.getSk());
        u.access_hash = 0;
        String name = m.getName();
        int dot = name.indexOf('·');
        u.flags |= 1;
        u.first_name = dot > 0 ? name.substring(0, dot) : (name.isEmpty() ? "?" : name);
        u.flags |= 2;
        u.phone = name;
        u.flags |= 16;
        u.contact = false;
        u.verified = GerehFeeds.isOfficialAdmin(m.getSk());
        GerehPhotos.dress(u, m.getSk());
        return (TLRPC.User) Messenger.roundTripObject(u);
    }

    static ArrayList<TLRPC.User> usersFor(ChatNode node, Group g) {
        Map<String, org.mlmchat.core.Contact> contacts = new java.util.HashMap<>();
        try {
            for (org.mlmchat.core.Contact c : node.contacts()) {
                contacts.put(c.getSk(), c);
            }
        } catch (Throwable ignored) {
        }
        ArrayList<TLRPC.User> out = new ArrayList<>();
        for (GroupMember m : g.getMembers()) {
            out.add(userFor(m, contacts));
        }
        return out;
    }

    static TLRPC.Message toTl(GroupMessage m, Group g) {
        long self = UserConfig.getInstance(Messenger.account()).getClientUserId();
        long chat = chatIdFor(g.getId());
        TLRPC.TL_message msg = new TLRPC.TL_message();
        msg.id = MSG_BASE + (int) m.getSeq();
        msg.out = m.getOutgoing();
        TLRPC.TL_peerChat peer = new TLRPC.TL_peerChat();
        peer.chat_id = chat;
        msg.peer_id = peer;
        TLRPC.TL_peerUser from = new TLRPC.TL_peerUser();
        from.user_id = m.getOutgoing() ? self : GerehIdentity.userIdFor(m.getSender());
        msg.from_id = from;
        msg.flags |= 256;
        msg.date = (int) (m.getAtMs() / 1000);
        msg.message = m.getText() != null ? GerehInvites.shown(m.getText()) : "";
        if (m.getMedia() != null) {
            msg.media = GerehMedia.toTl(m.getMedia(), (int) (m.getAtMs() / 1000));
            msg.flags |= 512;
            msg.message = m.getMedia().getCaption();
            GerehMedia.dressAlbum(msg, m.getMedia());
        }
        GerehEntities.dress(msg, m.getEntities(), m.getMedia() != null ? m.getMedia().getCaption() : m.getText());
        TLRPC.TL_messageMediaPoll poll = GerehPolls.media(GerehIdentity.node(), g.getId(), m);
        if (poll != null) {
            // a poll: Telegram's own, with the results counted here (m4, T69)
            msg.media = poll;
            msg.flags |= 512;
            msg.message = "";
        }
        markEdited(msg, m);
        msg.dialog_id = -chat;
        if (msg.out) {
            msg.flags |= 2;
        }
        if (m.getReplyTo() > 0) {
            Messenger.setReply(msg, MSG_BASE + (int) m.getReplyTo());
        }
        ChatNode node = GerehIdentity.node();
        if (node != null) {
            GerehReactions.dressFor(msg, node, g.getId(), true, m.getId(), !g.getChannel());
        }
        GerehConfigs.dress(msg); // configs in it: «سنجش» and «انتقال» (n5)
        return msg;
    }

    /**
     * The group seq of the message a request answers (a group or channel message's Telegram id is
     * MSG_BASE + its seq); 0 for none, and for a line of the bridge's own (the "created" line).
     */
    static long replySeq(TLRPC.InputReplyTo r) {
        int id = Messenger.replyMsgId(r);
        return id > MSG_BASE ? id - MSG_BASE : 0;
    }

    private static List<GroupMessage> shown(List<GroupMessage> rows) {
        List<GroupMessage> out = new ArrayList<>();
        for (GroupMessage m : rows) {
            if (m.getText() != null || m.getMedia() != null || m.getPoll() != null) {
                out.add(m);
            }
        }
        return out;
    }

    // ---- read marks, kept on this phone (groups have no read receipts in version 1) ----------

    static int createdId(Group g) {
        return CREATED_BASE + (int) (chatIdFor(g.getId()) % 90_000_000L);
    }

    /** When this phone first showed the group: the date of its «created» line, kept. */
    private static synchronized int createdDate(String gid) {
        SharedPreferences p = prefs();
        int at = p.getInt("created_" + gid, 0);
        if (at == 0) {
            at = (int) (System.currentTimeMillis() / 1000);
            p.edit().putInt("created_" + gid, at).apply();
        }
        return at;
    }

    /**
     * The first line of every group and channel, as Telegram's server sends it: «X created the
     * group» or «channel created». Without it a chat nobody wrote in yet has no message, and
     * Telegram leaves such a chat out of its list (Ehsan, 25 Oct 2026: a new channel or group was
     * gone after going back).
     */
    static TLRPC.Message createdLine(Group g) {
        long chat = chatIdFor(g.getId());
        TLRPC.TL_messageService msg = new TLRPC.TL_messageService();
        msg.id = createdId(g);
        msg.date = createdDate(g.getId());
        boolean mine = g.getOwner().equals(selfSk());
        if (g.getChannel()) {
            TLRPC.TL_peerChannel peer = new TLRPC.TL_peerChannel();
            peer.channel_id = chat;
            msg.peer_id = peer;
            msg.post = true;
            TLRPC.TL_messageActionChannelCreate a = new TLRPC.TL_messageActionChannelCreate();
            a.title = TextUtils.isEmpty(g.getName()) ? "?" : g.getName();
            msg.action = a;
        } else {
            TLRPC.TL_peerChat peer = new TLRPC.TL_peerChat();
            peer.chat_id = chat;
            msg.peer_id = peer;
            long owner = mine ? UserConfig.getInstance(Messenger.account()).getClientUserId() : GerehIdentity.userIdFor(g.getOwner());
            TLRPC.TL_peerUser from = new TLRPC.TL_peerUser();
            from.user_id = owner;
            msg.from_id = from;
            msg.flags |= 256;
            TLRPC.TL_messageActionChatCreate a = new TLRPC.TL_messageActionChatCreate();
            a.title = TextUtils.isEmpty(g.getName()) ? "?" : g.getName();
            a.users.add(owner);
            msg.action = a;
        }
        msg.out = mine;
        if (mine) {
            msg.flags |= 2;
        }
        msg.dialog_id = -chat;
        return msg;
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("gereh_groups", Context.MODE_PRIVATE);
    }

    private static int readMax(String gid) {
        return prefs().getInt("read_" + gid, 0);
    }

    private static void setRead(String gid, int maxId) {
        if (maxId > readMax(gid)) {
            prefs().edit().putInt("read_" + gid, maxId).apply();
        }
    }

    static long syncedSeq() {
        return prefs().getLong("seq_" + Messenger.account(), 0);
    }

    static synchronized void markSynced(long seq) {
        if (seq > syncedSeq()) {
            prefs().edit().putLong("seq_" + Messenger.account(), seq).apply();
        }
    }

    // ---- dialogs, history, difference -------------------------------------------------------

    /** Every group as a dialog (even with no message yet, so a group just joined shows). */
    static void addDialogs(ChatNode node, ArrayList<TLRPC.Dialog> dialogs, ArrayList<TLRPC.Message> messages, ArrayList<TLRPC.Chat> chats, ArrayList<TLRPC.User> users, Set<Long> only) {
        Set<Long> seen = new HashSet<>();
        for (TLRPC.User u : users) {
            seen.add(u.id);
        }
        for (Group g : node.groups()) {
            long chat = chatIdFor(g.getId());
            if (only != null && !only.contains(chat)) {
                continue;
            }
            chats.add(chatFor(g));
            for (TLRPC.User u : usersFor(node, g)) {
                if (seen.add(u.id)) {
                    users.add(u);
                }
            }
            if (g.getChannel()) {
                dialogs.add(GerehChannels.dialogFor(node, g, messages));
                continue;
            }
            // the last message, and the counts the core keeps: the group itself is not read
            GroupMessage top = newestShown(node, g.getId());
            TLRPC.TL_dialog d = new TLRPC.TL_dialog();
            TLRPC.TL_peerChat pc = new TLRPC.TL_peerChat();
            pc.chat_id = chat;
            d.peer = pc;
            d.top_message = top != null ? MSG_BASE + (int) top.getSeq() : createdId(g);
            int read = readMax(g.getId());
            GroupMarks marks = node.groupMarks(g.getId(), read > MSG_BASE ? read - MSG_BASE : 0);
            // a member's read receipt came for our newest read post (SPEC 7.6): two checks
            d.read_outbox_max_id = marks.getReadOut() > 0 ? MSG_BASE + (int) marks.getReadOut() : 0;
            d.read_inbox_max_id = read;
            d.unread_count = marks.getUnread();
            d.unread_reactions_count = GerehReactions.unseenIn(g.getId());
            d.notify_settings = GerehNotify.of(-pc.chat_id);
            dialogs.add(d);
            messages.add(top != null ? toTl(top, g) : createdLine(g));
        }
    }

    /** Rows read beyond what a page needs, for those Telegram does not show. */
    private static final int PAGE_SLACK = 20;

    /**
     * Telegram's page of group or channel [g] (messages.getHistory), read from the core a page at
     * a time as a one-to-one chat's is (Messenger.historyPage): before, every page read the
     * group's newest 1000 messages. The «created» line is the oldest message, below every
     * message's id; it joins the window once the page reaches the group's first message.
     */
    static List<TLRPC.Message> historyPage(ChatNode node, Group g, Utilities.CallbackReturn<GroupMessage, TLRPC.Message> tl, int offsetId, int addOffset, int limit, int maxId, int minId) {
        int want = Math.max(1, limit);
        String gid = g.getId();
        TLRPC.Message created = createdLine(g);
        List<TLRPC.Message> window = new ArrayList<>(); // newest first
        if (offsetId != 0 && addOffset < 0) {
            // the -addOffset messages from offsetId on, nearest it last in the newest-first window
            List<TLRPC.Message> near = new ArrayList<>(); // oldest first
            if (created.id >= offsetId) {
                near.add(created);
            }
            long after = offsetId > MSG_BASE ? offsetId - MSG_BASE - 1 : 0;
            for (GroupMessage m : shown(node.groupMessagesPage(gid, 0, after, -addOffset + PAGE_SLACK))) {
                near.add(tl.run(m));
            }
            for (int i = Math.min(near.size(), -addOffset) - 1; i >= 0; i--) {
                window.add(near.get(i));
            }
        }
        if (offsetId != 0 && offsetId <= MSG_BASE) {
            // nothing but the «created» line is older than an id below every message's
            if (created.id < offsetId) {
                window.add(created);
            }
            return Messenger.pageOf(window, offsetId, addOffset, limit, maxId, minId);
        }
        long before = offsetId != 0 ? offsetId - MSG_BASE : 0;
        int ask = Math.max(0, addOffset) + want + PAGE_SLACK;
        while (true) {
            List<GroupMessage> rows = node.groupMessagesPage(gid, before, -1, ask);
            for (int i = rows.size() - 1; i >= 0; i--) {
                GroupMessage m = rows.get(i);
                if (m.getText() != null || m.getMedia() != null || m.getPoll() != null) {
                    window.add(tl.run(m));
                }
            }
            boolean first = rows.size() < ask;
            if (first) {
                window.add(created); // the group's first message reached: its «created» line
            }
            List<TLRPC.Message> out = Messenger.pageOf(window, offsetId, addOffset, limit, maxId, minId);
            if (out.size() >= want || first || minId > MSG_BASE && MSG_BASE + rows.get(0).getSeq() <= minId) {
                return out;
            }
            before = rows.get(0).getSeq();
            ask = want + PAGE_SLACK;
        }
    }

    /** The newest message of group [gid] that Telegram shows; null for none. */
    static GroupMessage newestShown(ChatNode node, String gid) {
        long before = 0;
        for (int round = 0; round < 50; round++) {
            List<GroupMessage> rows = node.groupMessagesPage(gid, before, -1, PAGE_SLACK);
            for (int i = rows.size() - 1; i >= 0; i--) {
                if (rows.get(i).getText() != null || rows.get(i).getMedia() != null) {
                    return rows.get(i);
                }
            }
            if (rows.size() < PAGE_SLACK) {
                return null;
            }
            before = rows.get(0).getSeq();
        }
        return null;
    }

    /**
     * New group messages Telegram has not had, for the difference: only what came after the last
     * one handed over, from every group at once. True when more are waiting than one round takes.
     */
    static boolean addDifference(ChatNode node, TLRPC.TL_updates_difference d) {
        long synced = syncedSeq();
        long max = synced;
        Map<String, Group> groups = new HashMap<>();
        for (Group g : node.groups()) {
            if (!g.getChannel()) {
                groups.put(g.getId(), g); // a channel's messages come with its own pts (GerehChannels)
            }
        }
        Set<String> named = new HashSet<>();
        List<GroupMessage> since = node.groupMessagesSince(synced, Messenger.DIFFERENCE_MAX);
        for (GroupMessage m : shown(since)) {
            Group g = groups.get(m.getGroup());
            if (g == null) {
                continue;
            }
            d.new_messages.add(toTl(m, g));
            max = Math.max(max, m.getSeq());
            if (named.add(g.getId())) {
                d.chats.add(chatFor(g));
                d.users.addAll(usersFor(node, g));
            }
        }
        if (!since.isEmpty()) {
            // past every row read, a channel's and one never shown too: none of them is ever due
            // here, and a round of nothing but those must not be read again and again
            max = Math.max(max, since.get(since.size() - 1).getSeq());
        }
        markSynced(max);
        return since.size() >= Messenger.DIFFERENCE_MAX;
    }

    /** One group message as it arrives: straight into Telegram, like a one-to-one message. */
    static void onMessage(ChatNode node, String gid, String id) {
        Group g = node.groupState(gid);
        if (g == null) {
            return;
        }
        GroupMessage row = node.groupMessage(gid, id, 0);
        if (row == null || row.getText() == null && row.getMedia() == null) {
            return;
        }
        if (g.getChannel()) {
            GerehChannels.onMessage(node, g, row);
            return;
        }
        final TLRPC.Message msg = toTl(row, g);
        final TLRPC.Chat chat = chatFor(g);
        final ArrayList<TLRPC.User> users = usersFor(node, g);
        final long seq = row.getSeq();
        Utilities.stageQueue.postRunnable(() -> {
            TL_update.TL_updateNewMessage u = new TL_update.TL_updateNewMessage();
            u.message = msg;
            u.pts = Messenger.nextPtsValue();
            u.pts_count = 1;
            TLRPC.TL_updates ups = new TLRPC.TL_updates();
            ups.updates.add(u);
            ups.users.addAll(users);
            ups.chats.add(chat);
            ups.date = (int) (System.currentTimeMillis() / 1000);
            ups.seq = 0;
            MessagesController.getInstance(Messenger.account()).processUpdates(ups, false);
            markSynced(seq);
        });
    }

    /**
     * The other side took messages down for everyone (SPEC 5.2, Messenger.onEvent): out of
     * Telegram's copy, in a group or a channel.
     */
    static void onDeleted(ChatNode node, String gid, java.util.List<Long> seqs) {
        Group g = node.groupState(gid);
        if (g == null || seqs.isEmpty()) {
            return;
        }
        if (g.getChannel()) {
            GerehChannels.onDeleted(g, seqs);
            return;
        }
        final ArrayList<Integer> ids = new ArrayList<>();
        for (Long s : seqs) {
            ids.add(MSG_BASE + s.intValue());
        }
        Utilities.stageQueue.postRunnable(() -> {
            TL_update.TL_updateDeleteMessages d = new TL_update.TL_updateDeleteMessages();
            d.messages = ids;
            d.pts = Messenger.nextPtsValue();
            d.pts_count = 1;
            TLRPC.TL_updates ups = new TLRPC.TL_updates();
            ups.updates.add(d);
            ups.date = (int) (System.currentTimeMillis() / 1000);
            ups.seq = 0;
            MessagesController.getInstance(Messenger.account()).processUpdates(ups, false);
        });
    }

    /** A group changed (joined, renamed, someone added or removed): Telegram gets the chat anew. */
    static void onChanged(ChatNode node, String gid) {
        Group g = node.groupState(gid);
        if (g == null) {
            return;
        }
        final TLRPC.Chat chat = chatFor(g);
        final ArrayList<TLRPC.User> users = usersFor(node, g);
        final TLRPC.Message created = g.getMember() ? createdLine(g) : null;
        AndroidUtilities.runOnUIThread(() -> {
            MessagesController mc = MessagesController.getInstance(Messenger.account());
            mc.putUsers(users, false);
            // slow mode set or lifted from another phone (q13, T73): its seconds and the send
            // button's timer are in the group's full info, which only a reload brings to an open chat
            TLRPC.Chat before = mc.getChat(chat.id);
            boolean slowChanged = before != null && before.slowmode_enabled != chat.slowmode_enabled;
            ArrayList<TLRPC.Chat> one = new ArrayList<>();
            one.add(chat);
            mc.putChats(one, false);
            if (slowChanged) {
                mc.loadFullChat(chat.id, 0, true);
            }
            if (created != null && mc.dialogs_dict.get(created.dialog_id) == null) {
                // A group just joined has no message yet, and the dialog list only reloads from
                // Telegram's own copy (loadDialogs from cache), which has no row for it: the group
                // stayed out of the list until its first message (owner's phone, 27 Oct 2026). Its
                // «created» line puts it there at once, as for a group made on this phone.
                Utilities.stageQueue.postRunnable(() -> {
                    TL_update.TL_updateNewMessage u = new TL_update.TL_updateNewMessage();
                    u.message = created;
                    u.pts = Messenger.nextPtsValue();
                    u.pts_count = 1;
                    TLRPC.TL_updates ups = new TLRPC.TL_updates();
                    ups.updates.add(u);
                    ups.users.addAll(users);
                    ups.chats.add(chat);
                    ups.date = (int) (System.currentTimeMillis() / 1000);
                    ups.seq = 0;
                    mc.processUpdates(ups, false);
                });
            }
            org.telegram.messenger.NotificationCenter.getInstance(Messenger.account())
                    .postNotificationName(org.telegram.messenger.NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_CHAT | MessagesController.UPDATE_MASK_AVATAR);
        });
    }

    // ---- requests ---------------------------------------------------------------------------

    static TLRPC.TL_updates chatUpdates(ChatNode node, Group g) {
        TLRPC.TL_updates ups = new TLRPC.TL_updates();
        ups.chats.add(chatFor(g));
        ups.users.addAll(usersFor(node, g));
        ups.date = (int) (System.currentTimeMillis() / 1000);
        ups.seq = 0;
        return ups;
    }

    /**
     * The rights and title the admin screen chose for a group's admin, by "chat:user", for the
     * messages.editChatAdmin that follows. Telegram would first turn a basic group into a supergroup
     * (messages.migrateChat), which a Gereh group does not need, so making an admin failed with «not
     * built yet» (Ehsan, 27 Oct 2026); ChatRightsEditActivity now skips that and calls this.
     */
    private static final Map<String, Object[]> chosenRights = new ConcurrentHashMap<>();

    static void chooseAdminRights(long chatId, long userId, TLRPC.TL_chatAdminRights r, String rank) {
        chosenRights.put(chatId + ":" + userId, new Object[]{rightsOf(r), rank != null ? rank : ""});
    }

    /** Telegram's admin rights as the core's (group.rs `admin`). */
    static int rightsOf(TLRPC.TL_chatAdminRights r) {
        if (r == null) {
            return 0;
        }
        return (r.change_info ? 1 : 0) | (r.delete_messages ? 2 : 0) | (r.ban_users ? 4 : 0)
                | (r.invite_users ? 8 : 0) | (r.pin_messages ? 16 : 0) | (r.add_admins ? 32 : 0);
    }

    private static long uidOf(TLRPC.InputUser iu) {
        if (iu instanceof TLRPC.TL_inputUserSelf) {
            return UserConfig.getInstance(Messenger.account()).getClientUserId();
        }
        return iu instanceof TLRPC.TL_inputUser ? ((TLRPC.TL_inputUser) iu).user_id : 0;
    }

    private static String skOf(TLRPC.InputUser iu) {
        long uid = iu instanceof TLRPC.TL_inputUser ? ((TLRPC.TL_inputUser) iu).user_id : 0;
        if (iu instanceof TLRPC.TL_inputUserSelf || uid == UserConfig.getInstance(Messenger.account()).getClientUserId()) {
            return selfSk();
        }
        return Messenger.skForUser(uid);
    }

    private static Group need(ChatNode node, long chatId) {
        Group g = groupFor(node, chatId);
        if (g == null) {
            throw new IllegalArgumentException("unknown group");
        }
        return g;
    }

    /** A group or channel whose settings are being changed: the official ones are set by Gereh's makers. */
    private static Group needEditable(ChatNode node, long chatId) {
        if (GerehFeeds.feedOf(chatId) != null) {
            throw new Messenger.Refusal(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.GerehOfficialFixed));
        }
        return need(node, chatId);
    }

    /**
     * A request about groups, or one of the shared requests (dialogs, history, sending, reading)
     * whose peer is a group. Returns null when the answer goes through {@code done}; throws when
     * it is not a group request (the caller goes on with one-to-one chats).
     */
    static TLObject answer(ChatNode node, TLObject req, RequestDelegate done) throws Exception {
        try {
            return GerehChannels.answer(node, req, done);
        } catch (NotGroup ignored) {
            // not a channel request: a group's, or a one-to-one one
        }
        if (req instanceof TLRPC.TL_messages_getMessageReadParticipants) {
            // «seen by» (SPEC 7.6): the members whose read receipt for our post came, the first
            // first; Telegram asks only in a group of at most 100 members, for posts under 7 days
            TLRPC.TL_messages_getMessageReadParticipants q = (TLRPC.TL_messages_getMessageReadParticipants) req;
            ArrayList<TLRPC.TL_readParticipantDate> seen = new ArrayList<>();
            if (chatOf(q.peer) != 0 && q.msg_id >= MSG_BASE) {
                for (org.mlmchat.core.GroupRead r : node.groupReads(q.msg_id - MSG_BASE)) {
                    TLRPC.TL_readParticipantDate p = new TLRPC.TL_readParticipantDate();
                    p.user_id = GerehIdentity.userIdFor(r.getSk());
                    p.date = (int) (r.getAtMs() / 1000);
                    seen.add(p);
                }
            }
            return Messenger.roundTripVector(req, seen);
        }
        if (req instanceof TLRPC.TL_messages_createChat) {
            TLRPC.TL_messages_createChat q = (TLRPC.TL_messages_createChat) req;
            String gid = node.groupCreate(q.title != null ? q.title : "", "", false);
            for (TLRPC.InputUser iu : q.users) {
                String sk = skOf(iu);
                if (sk != null && !sk.equals(selfSk())) {
                    try {
                        node.groupAdd(gid, sk);
                    } catch (Throwable t) {
                        Log.w(TAG, "group add: " + t.getClass().getSimpleName());
                    }
                }
            }
            TLRPC.TL_messages_invitedUsers r = new TLRPC.TL_messages_invitedUsers();
            Group made = node.groupState(gid);
            r.updates = chatUpdates(node, made);
            // its first line, so the new group is in the list at once (see createdLine)
            TL_update.TL_updateNewMessage u = new TL_update.TL_updateNewMessage();
            u.message = createdLine(made);
            u.pts = Messenger.nextPtsValue();
            u.pts_count = 1;
            r.updates.updates.add(u);
            return r;
        }
        // the edit page (Ehsan, 25 Oct 2026: every group setting Telegram has, but those needing a server)
        if (req instanceof TLRPC.TL_messages_editChatAbout) {
            TLRPC.TL_messages_editChatAbout q = (TLRPC.TL_messages_editChatAbout) req;
            Group g = needEditable(node, chatOf(q.peer));
            node.groupSetInfo(g.getId(), g.getName(), q.about != null ? q.about : "");
            return new TLRPC.TL_boolTrue();
        }
        if (req instanceof TLRPC.TL_messages_editChatDefaultBannedRights) {
            TLRPC.TL_messages_editChatDefaultBannedRights q = (TLRPC.TL_messages_editChatDefaultBannedRights) req;
            Group g = needEditable(node, chatOf(q.peer));
            return chatUpdates(node, node.groupSetDefaults(g.getId(), memberRights(q.banned_rights)));
        }
        if (req instanceof TLRPC.TL_messages_deleteChat) {
            Group g = need(node, ((TLRPC.TL_messages_deleteChat) req).chat_id);
            node.groupDissolve(g.getId());
            return new TLRPC.TL_boolTrue();
        }
        if (req instanceof TLRPC.TL_messages_getFullChat) {
            Group g = need(node, ((TLRPC.TL_messages_getFullChat) req).chat_id);
            long chat = chatIdFor(g.getId());
            TLRPC.TL_chatFull f = new TLRPC.TL_chatFull();
            f.id = chat;
            f.about = g.getAbout();
            f.notify_settings = GerehNotify.of(-f.id);
            int pin = GerehPins.newest(node, inputPeerFor(g));
            if (pin != 0) {
                f.pinned_msg_id = pin;
                f.flags |= 64;
            }
            f.available_reactions = GerehReactions.allowed();
            f.flags |= 1 << 18;
            if (g.getSlowSeconds() > 0) {
                // slow mode (q13, T73): Telegram's own timer on the send button and the admins'
                // row read these; a basic group carries them on this phone only
                f.slowmode_seconds = g.getSlowSeconds();
                if (!g.getAdmin() && g.getSlowNextMs() > 0) {
                    f.slowmode_next_send_date = (int) (g.getSlowNextMs() / 1000);
                }
            }
            // its admins' «Removed users» row counts them (bannedParticipants)
            f.kicked_count = g.getBanned().size();
            TLRPC.TL_chatParticipants ps = new TLRPC.TL_chatParticipants();
            ps.chat_id = chat;
            ps.version = 1;
            int date = (int) (System.currentTimeMillis() / 1000);
            long ownerUid = GerehIdentity.userIdFor(g.getOwner());
            for (GroupMember m : g.getMembers()) {
                long uid = m.getSk().equals(selfSk()) ? UserConfig.getInstance(Messenger.account()).getClientUserId() : GerehIdentity.userIdFor(m.getSk());
                TLRPC.ChatParticipant p;
                if (m.getOwner()) {
                    p = new TLRPC.TL_chatParticipantCreator();
                } else if (m.getAdmin()) {
                    p = new TLRPC.TL_chatParticipantAdmin();
                    p.inviter_id = ownerUid;
                    p.date = date;
                } else {
                    p = new TLRPC.TL_chatParticipant();
                    p.inviter_id = ownerUid;
                    p.date = date;
                }
                p.user_id = uid;
                ps.participants.add(p);
            }
            f.participants = ps;
            if (g.getPhoto() != 0) {
                f.chat_photo = GerehPhotos.chatFullPhoto(g);
                f.flags |= 4;
            }
            GerehInvites.dressFull(f, node, g);
            TLRPC.TL_messages_chatFull r = new TLRPC.TL_messages_chatFull();
            r.full_chat = f;
            r.chats.add(chatFor(g));
            r.users.addAll(usersFor(node, g));
            return r;
        }
        if (req instanceof TLRPC.TL_messages_addChatUser) {
            TLRPC.TL_messages_addChatUser q = (TLRPC.TL_messages_addChatUser) req;
            Group g = need(node, q.chat_id);
            String sk = skOf(q.user_id);
            if (sk == null) {
                throw new IllegalArgumentException("not a contact");
            }
            // the admin screen adds the person first (MessagesController.setUserAdminRole): someone
            // already in the group stays as they are
            boolean member = false;
            for (GroupMember m : g.getMembers()) {
                member |= m.getSk().equals(sk);
            }
            if (!member) {
                node.groupAdd(g.getId(), sk);
            }
            TLRPC.TL_messages_invitedUsers r = new TLRPC.TL_messages_invitedUsers();
            r.updates = chatUpdates(node, node.groupState(g.getId()));
            return r;
        }
        if (req instanceof TLRPC.TL_messages_deleteChatUser) {
            TLRPC.TL_messages_deleteChatUser q = (TLRPC.TL_messages_deleteChatUser) req;
            Group g = need(node, q.chat_id);
            String sk = skOf(q.user_id);
            if (sk == null) {
                throw new IllegalArgumentException("not a member");
            }
            node.groupRemove(g.getId(), sk, false);
            Group after = node.groupState(g.getId());
            return chatUpdates(node, after != null ? after : g);
        }
        if (req instanceof TLRPC.TL_messages_editChatTitle) {
            TLRPC.TL_messages_editChatTitle q = (TLRPC.TL_messages_editChatTitle) req;
            Group g = need(node, q.chat_id);
            node.groupSetInfo(g.getId(), q.title, g.getAbout());
            return chatUpdates(node, node.groupState(g.getId()));
        }
        if (req instanceof TLRPC.TL_messages_editChatAdmin) {
            TLRPC.TL_messages_editChatAdmin q = (TLRPC.TL_messages_editChatAdmin) req;
            Group g = need(node, q.chat_id);
            String sk = skOf(q.user_id);
            if (sk == null) {
                throw new IllegalArgumentException("not a member");
            }
            // a basic group's request says only "admin or not": the rights and title come from the
            // admin screen (chooseAdminRights), else Telegram's one basic level
            Object[] chosen = chosenRights.remove(q.chat_id + ":" + uidOf(q.user_id));
            int rights = chosen != null ? (int) chosen[0] : (1 | 2 | 4 | 8 | 16);
            String title = chosen != null ? (String) chosen[1] : "";
            node.groupSetAdmin(g.getId(), sk, q.is_admin, q.is_admin ? rights : 0, q.is_admin ? title : "");
            return new TLRPC.TL_boolTrue();
        }
        if (req instanceof TLRPC.TL_messages_getChats) {
            TLRPC.TL_messages_chats r = new TLRPC.TL_messages_chats();
            for (Long id : ((TLRPC.TL_messages_getChats) req).id) {
                Group g = groupFor(node, id);
                if (g != null) {
                    r.chats.add(chatFor(g));
                }
            }
            return r;
        }
        if (req instanceof TLRPC.TL_messages_getHistory && chatOf(((TLRPC.TL_messages_getHistory) req).peer) != 0) {
            TLRPC.TL_messages_getHistory q = (TLRPC.TL_messages_getHistory) req;
            TLRPC.TL_messages_messages r = new TLRPC.TL_messages_messages();
            Group g = groupFor(node, chatOf(q.peer));
            if (g != null) {
                r.messages.addAll(historyPage(node, g, m -> toTl(m, g), q.offset_id, q.add_offset, q.limit, q.max_id, q.min_id));
                r.chats.add(chatFor(g));
                r.users.addAll(usersFor(node, g));
            }
            return r;
        }
        if (req instanceof TLRPC.TL_messages_sendMessage && chatOf(((TLRPC.TL_messages_sendMessage) req).peer) != 0) {
            TLRPC.TL_messages_sendMessage q = (TLRPC.TL_messages_sendMessage) req;
            Group g = need(node, chatOf(q.peer));
            if (TextUtils.isEmpty(q.message)) {
                throw new IllegalArgumentException("empty message");
            }
            if (!g.getCanPost()) {
                throw new Messenger.Refusal(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.GerehGroupCannotPost));
            }
            String id = node.groupSendText(g.getId(), q.message, replySeq(q.reply_to), GerehEntities.toCore(q.entities));
            return sentInGroup(node, g, id, q.random_id, done);
        }
        if (req instanceof TLRPC.TL_messages_readHistory && chatOf(((TLRPC.TL_messages_readHistory) req).peer) != 0) {
            TLRPC.TL_messages_readHistory q = (TLRPC.TL_messages_readHistory) req;
            Group g = groupFor(node, chatOf(q.peer));
            if (g != null) {
                setRead(g.getId(), q.max_id);
                // the authors of what was read hear it, for their «seen by» (SPEC 7.6)
                if (q.max_id >= MSG_BASE) {
                    final String gid = g.getId();
                    final long upto = q.max_id - MSG_BASE;
                    Utilities.globalQueue.postRunnable(() -> {
                        try {
                            node.groupMarkRead(gid, upto);
                        } catch (Throwable t) {
                            Log.w(TAG, "group read: " + t.getClass().getSimpleName());
                        }
                    });
                }
            }
            Utilities.stageQueue.postRunnable(() -> {
                TLRPC.TL_messages_affectedMessages r = new TLRPC.TL_messages_affectedMessages();
                r.pts = Messenger.nextPtsValue();
                r.pts_count = 1;
                done.run(Messenger.roundTripResponse(req, r), null);
            });
            return null;
        }
        if (req instanceof TLRPC.TL_messages_editMessage && chatOf(((TLRPC.TL_messages_editMessage) req).peer) != 0) {
            // Edit (SPEC 5): one's own post, text or caption only; every member gets it
            TLRPC.TL_messages_editMessage q = (TLRPC.TL_messages_editMessage) req;
            Group g = need(node, chatOf(q.peer));
            if (q.media != null || q.message == null) {
                throw new IllegalArgumentException("only the text is edited");
            }
            node.groupEditText(q.id - MSG_BASE, q.message, GerehEntities.toCore(q.entities));
            TLRPC.TL_updates ups = chatUpdates(node, g);
            GroupMessage row = bySeq(node, g, q.id - MSG_BASE);
            if (row != null) {
                TL_update.TL_updateEditMessage u = new TL_update.TL_updateEditMessage();
                u.message = toTl(row, g);
                u.pts = Messenger.nextPtsValue();
                u.pts_count = 1;
                ups.updates.add(u);
            }
            return ups;
        }
        throw new NotGroup();
    }

    /** A message of group [g] by this phone's seq; null when it is not there. */
    static GroupMessage bySeq(ChatNode node, Group g, long seq) {
        return node.groupMessage(g.getId(), "", seq);
    }

    /** «edited» beside the time (SPEC 5 `Edit`), as Telegram shows a server's edited message. */
    static void markEdited(TLRPC.Message msg, GroupMessage m) {
        if (m.getEditedMs() > 0) {
            msg.edit_date = (int) (m.getEditedMs() / 1000);
            msg.flags |= TLRPC.MESSAGE_FLAG_EDITED;
        }
    }

    /**
     * A member read our post [seq] (SPEC 7.6): Telegram's two checks on it and everything of ours
     * before it, and «seen by» in its menu. A channel has no «seen by».
     */
    static void onRead(ChatNode node, String gid, long seq) {
        Group g = node.groupState(gid);
        if (g == null || g.getChannel()) {
            return;
        }
        final long chat = chatIdFor(gid);
        final int max = MSG_BASE + (int) seq;
        Utilities.stageQueue.postRunnable(() -> {
            TL_update.TL_updateReadHistoryOutbox u = new TL_update.TL_updateReadHistoryOutbox();
            TLRPC.TL_peerChat pc = new TLRPC.TL_peerChat();
            pc.chat_id = chat;
            u.peer = pc;
            u.max_id = max;
            u.pts = Messenger.nextPtsValue();
            u.pts_count = 1;
            TLRPC.TL_updates ups = new TLRPC.TL_updates();
            ups.updates.add(u);
            ups.date = (int) (System.currentTimeMillis() / 1000);
            ups.seq = 0;
            MessagesController.getInstance(Messenger.account()).processUpdates(ups, false);
        });
    }

    /** A member edited a message of group [gid] (SPEC 5 `Edit`): Telegram's copy is replaced. */
    static void onEdited(ChatNode node, String gid, long seq) {
        Group g = node.groupState(gid);
        GroupMessage row = g == null ? null : bySeq(node, g, seq);
        if (row == null) {
            return;
        }
        if (g.getChannel()) {
            GerehChannels.onEdited(node, g, row);
            return;
        }
        final TLRPC.Message msg = toTl(row, g);
        final TLRPC.Chat chat = chatFor(g);
        final ArrayList<TLRPC.User> users = usersFor(node, g);
        Utilities.stageQueue.postRunnable(() -> {
            TL_update.TL_updateEditMessage u = new TL_update.TL_updateEditMessage();
            u.message = msg;
            u.pts = Messenger.nextPtsValue();
            u.pts_count = 1;
            TLRPC.TL_updates ups = new TLRPC.TL_updates();
            ups.updates.add(u);
            ups.users.addAll(users);
            ups.chats.add(chat);
            ups.date = (int) (System.currentTimeMillis() / 1000);
            ups.seq = 0;
            MessagesController.getInstance(Messenger.account()).processUpdates(ups, false);
        });
    }

    /** The answer to a message just posted in a group (text or file): a channel's own, a group's. */
    static TLObject sentInGroup(ChatNode node, Group g, String id, long randomId, RequestDelegate done) {
        GroupMessage row = node.groupMessage(g.getId(), id, 0);
        if (g.getChannel()) {
            return GerehChannels.sent(node, g, row, randomId, done);
        }
        final int msgId = MSG_BASE + (row != null ? (int) row.getSeq() : 0);
        final int date = row != null ? (int) (row.getAtMs() / 1000) : (int) (System.currentTimeMillis() / 1000);
        final long seq = row != null ? row.getSeq() : 0;
        final ArrayList<TLRPC.MessageEntity> entities = row == null ? new ArrayList<>()
                : GerehEntities.forSent(row.getEntities(), row.getMedia() != null ? row.getMedia().getCaption() : row.getText());
        Utilities.stageQueue.postRunnable(() -> {
            TLRPC.TL_updateShortSentMessage r = new TLRPC.TL_updateShortSentMessage();
            r.out = true;
            r.id = msgId;
            r.date = date;
            r.entities = entities;
            r.pts = Messenger.nextPtsValue();
            r.pts_count = 1;
            done.run(r, null);
            markSynced(seq);
        });
        return null;
    }

    /**
     * The answer to several files sent together in a group or channel (`messages.sendMultiMedia`,
     * GerehMedia): each message's id and the message itself, at once, as one is answered.
     */
    static TLObject sentAllInGroup(ChatNode node, Group g, List<String> ids, List<Long> randomIds, RequestDelegate done) {
        final List<GroupMessage> rows = new ArrayList<>();
        final List<Long> randoms = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            GroupMessage row = node.groupMessage(g.getId(), ids.get(i), 0);
            if (row != null) {
                rows.add(row);
                randoms.add(randomIds.get(i));
            }
        }
        if (g.getChannel()) {
            return GerehChannels.sentAll(node, g, rows, randoms, done);
        }
        Utilities.stageQueue.postRunnable(() -> {
            TLRPC.TL_updates ups = new TLRPC.TL_updates();
            long maxSeq = 0;
            for (int i = 0; i < rows.size(); i++) {
                GroupMessage row = rows.get(i);
                TL_update.TL_updateMessageID mid = new TL_update.TL_updateMessageID();
                mid.id = MSG_BASE + (int) row.getSeq();
                mid.random_id = randoms.get(i);
                ups.updates.add(mid);
                TL_update.TL_updateNewMessage u = new TL_update.TL_updateNewMessage();
                u.message = toTl(row, g);
                u.pts = Messenger.nextPtsValue();
                u.pts_count = 1;
                ups.updates.add(u);
                maxSeq = Math.max(maxSeq, row.getSeq());
            }
            ups.chats.add(chatFor(g));
            ups.date = (int) (System.currentTimeMillis() / 1000);
            ups.seq = 0;
            done.run(ups, null);
            markSynced(maxSeq);
        });
        return null;
    }

    /** Thrown by {@link #answer} for a request that is not about a group. */
    static final class NotGroup extends RuntimeException {
        NotGroup() {
            super("not a group request", null, false, false);
        }
    }
}
