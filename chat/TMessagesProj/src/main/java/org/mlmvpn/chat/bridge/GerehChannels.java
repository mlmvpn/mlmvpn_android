package org.mlmvpn.chat.bridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.mlmchat.core.ChatNode;
import org.mlmchat.core.Group;
import org.mlmchat.core.GroupMember;
import org.mlmchat.core.GroupMessage;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_update;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * MLMCHAT: channels (T37) as Telegram's broadcast channels: a Gereh group with `broadcast` set,
 * where only the owner and admins post.
 *
 * A channel has its own pts in Telegram: the bridge counts it per channel (one step per message
 * handed over) and answers {@code updates.getChannelDifference} with "nothing new" when the
 * screen is up to date, else "too long", so the screen loads the history again.
 */
final class GerehChannels {
    private GerehChannels() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("gereh_channels", Context.MODE_PRIVATE);
    }

    /** Posts the other side took down for everyone (Messenger.onEvent): out of Telegram's copy. */
    static void onDeleted(Group g, java.util.List<Long> seqs) {
        final ArrayList<Integer> ids = new ArrayList<>();
        for (Long s : seqs) {
            ids.add(GerehGroups.MSG_BASE + s.intValue());
        }
        final long chan = GerehGroups.chatIdFor(g.getId());
        final String gid = g.getId();
        Utilities.stageQueue.postRunnable(() -> {
            TL_update.TL_updateDeleteChannelMessages d = new TL_update.TL_updateDeleteChannelMessages();
            d.channel_id = chan;
            d.messages = ids;
            d.pts = bump(gid);
            d.pts_count = 1;
            TLRPC.TL_updates ups = new TLRPC.TL_updates();
            ups.updates.add(d);
            ups.date = (int) (System.currentTimeMillis() / 1000);
            ups.seq = 0;
            MessagesController.getInstance(Messenger.account()).processUpdates(ups, false);
        });
    }

    static synchronized int pts(String gid) {
        return Math.max(1, prefs().getInt("pts_" + gid, 1));
    }

    /** The next pts of a channel, for an update another class gives Telegram (a pin, T65). */
    static int nextPts(String gid) {
        return bump(gid);
    }

    private static synchronized int bump(String gid) {
        int p = pts(gid) + 1;
        prefs().edit().putInt("pts_" + gid, p).apply();
        return p;
    }

    private static int readMax(String gid) {
        return prefs().getInt("read_" + gid, 0);
    }

    private static void setRead(String gid, int maxId) {
        if (maxId > readMax(gid)) {
            prefs().edit().putInt("read_" + gid, maxId).apply();
        }
    }

    static long channelOf(TLRPC.InputPeer p) {
        return p instanceof TLRPC.TL_inputPeerChannel ? ((TLRPC.TL_inputPeerChannel) p).channel_id : 0;
    }

    static long channelOf(TLRPC.InputChannel c) {
        return c instanceof TLRPC.TL_inputChannel ? ((TLRPC.TL_inputChannel) c).channel_id : 0;
    }

    static boolean handles(TLObject req) {
        return req instanceof TLRPC.TL_channels_createChannel
                || req instanceof TLRPC.TL_channels_getFullChannel
                || req instanceof TLRPC.TL_channels_getParticipants
                || req instanceof TLRPC.TL_channels_getParticipant
                || req instanceof TLRPC.TL_channels_inviteToChannel
                || req instanceof TLRPC.TL_channels_leaveChannel
                || req instanceof TLRPC.TL_channels_deleteChannel
                || req instanceof TLRPC.TL_channels_editTitle
                || req instanceof TLRPC.TL_channels_editAdmin
                || req instanceof TLRPC.TL_channels_editBanned
                || req instanceof TLRPC.TL_channels_toggleSlowMode
                || req instanceof TLRPC.TL_channels_readHistory
                || req instanceof TLRPC.TL_channels_deleteMessages
                || req instanceof TLRPC.TL_channels_getChannels
                || req instanceof TLRPC.TL_updates_getChannelDifference;
    }

    private static String selfSk(ChatNode node) {
        return node.getMe().getSk();
    }

    static TLRPC.Chat channelFor(ChatNode node, Group g) {
        return channelFor(g, selfSk(node));
    }

    static TLRPC.Chat channelFor(Group g, String selfSk) {
        TLRPC.TL_channel c = new TLRPC.TL_channel();
        c.id = GerehGroups.chatIdFor(g.getId());
        // Not 0: Telegram drops a post in a channel it has no dialog for yet unless it can ask for
        // that dialog, and it will not ask with a zero access hash (loadUnknownChannel). The bridge
        // knows channels by id only, so any fixed value serves.
        c.access_hash = c.id;
        c.title = TextUtils.isEmpty(g.getName()) ? "?" : g.getName();
        c.photo = GerehPhotos.chatPhoto(g);
        c.date = (int) (System.currentTimeMillis() / 1000);
        c.broadcast = true;
        c.creator = g.getOwner().equals(selfSk);
        c.left = !g.getMember();
        c.participants_count = g.getMembers().size();
        c.flags |= 1 << 17;
        if (g.getAdmin() && !c.creator) {
            TLRPC.TL_chatAdminRights r = new TLRPC.TL_chatAdminRights();
            int a = g.getAdminRights();
            r.change_info = (a & 1) != 0;
            r.post_messages = true;
            r.edit_messages = true;
            r.delete_messages = (a & 2) != 0;
            r.ban_users = (a & 4) != 0;
            r.invite_users = (a & 8) != 0;
            r.pin_messages = (a & 16) != 0; // pins go in the channel's log (T65)
            r.add_admins = (a & 32) != 0;
            c.admin_rights = r;
            c.flags |= 1 << 14;
        }
        return c;
    }

    static TLRPC.Message toTl(ChatNode node, GroupMessage m, Group g) {
        long chan = GerehGroups.chatIdFor(g.getId());
        TLRPC.TL_message msg = new TLRPC.TL_message();
        msg.id = GerehGroups.MSG_BASE + (int) m.getSeq();
        msg.out = m.getOutgoing();
        msg.post = true;
        TLRPC.TL_peerChannel peer = new TLRPC.TL_peerChannel();
        peer.channel_id = chan;
        msg.peer_id = peer;
        msg.date = (int) (m.getAtMs() / 1000);
        msg.message = m.getText() != null ? GerehInvites.shown(m.getText()) : "";
        if (m.getMedia() != null) {
            msg.media = GerehMedia.toTl(m.getMedia(), (int) (m.getAtMs() / 1000));
            msg.flags |= 512;
            msg.message = m.getMedia().getCaption();
            GerehMedia.dressAlbum(msg, m.getMedia());
        }
        GerehEntities.dress(msg, m.getEntities(), m.getMedia() != null ? m.getMedia().getCaption() : m.getText());
        TLRPC.TL_messageMediaPoll poll = GerehPolls.media(node, g.getId(), m);
        if (poll != null) {
            // a poll: Telegram's own, with the results counted here (m4, T69)
            msg.media = poll;
            msg.flags |= 512;
            msg.message = "";
        }
        GerehGroups.markEdited(msg, m);
        msg.dialog_id = -chan;
        if (msg.out) {
            msg.flags |= 2;
        }
        if (m.getReplyTo() > 0) {
            Messenger.setReply(msg, GerehGroups.MSG_BASE + (int) m.getReplyTo());
        }
        GerehReactions.dress(msg, GerehReactions.of(node, g.getId(), true, m.getId()), GerehReactions.me(node));
        GerehConfigs.dress(msg); // configs in it: «سنجش» and «انتقال» (n5)
        return msg;
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

    static TLRPC.TL_dialog dialogFor(ChatNode node, Group g, ArrayList<TLRPC.Message> messages) {
        // the last post, and the unread count the core keeps: the channel itself is not read
        GroupMessage top = GerehGroups.newestShown(node, g.getId());
        TLRPC.TL_dialog d = new TLRPC.TL_dialog();
        TLRPC.TL_peerChannel pc = new TLRPC.TL_peerChannel();
        pc.channel_id = GerehGroups.chatIdFor(g.getId());
        d.peer = pc;
        d.top_message = top != null ? GerehGroups.MSG_BASE + (int) top.getSeq() : GerehGroups.createdId(g);
        int read = readMax(g.getId());
        d.read_inbox_max_id = read;
        d.unread_count = node.groupMarks(g.getId(), read > GerehGroups.MSG_BASE ? read - GerehGroups.MSG_BASE : 0).getUnread();
        d.pts = pts(g.getId());
        d.flags |= 1;
        d.notify_settings = GerehNotify.of(-pc.channel_id);
        if (messages != null) {
            messages.add(top != null ? toTl(node, top, g) : GerehGroups.createdLine(g));
        }
        return d;
    }

    /** A new message in a channel: straight into Telegram, with the channel's next pts. */
    static void onMessage(ChatNode node, Group g, GroupMessage row) {
        final TLRPC.Message msg = toTl(node, row, g);
        final TLRPC.Chat chat = channelFor(node, g);
        final String gid = g.getId();
        Utilities.stageQueue.postRunnable(() -> {
            TL_update.TL_updateNewChannelMessage u = new TL_update.TL_updateNewChannelMessage();
            u.message = msg;
            u.pts = bump(gid);
            u.pts_count = 1;
            TLRPC.TL_updates ups = new TLRPC.TL_updates();
            ups.updates.add(u);
            ups.chats.add(chat);
            ups.date = (int) (System.currentTimeMillis() / 1000);
            ups.seq = 0;
            MessagesController.getInstance(Messenger.account()).processUpdates(ups, false);
        });
    }

    /**
     * The users a channel's screens get. As in Telegram, only its admins see who subscribed (Ehsan,
     * 30 Oct 2026): anyone else gets the admins, who write its posts, and themself.
     */
    static ArrayList<TLRPC.User> usersFor(ChatNode node, Group g) {
        ArrayList<TLRPC.User> all = GerehGroups.usersFor(node, g);
        if (g.getAdmin()) {
            return all;
        }
        java.util.Set<Long> keep = new java.util.HashSet<>();
        String me = selfSk(node);
        for (GroupMember m : g.getMembers()) {
            if (m.getAdmin() || m.getSk().equals(me)) {
                keep.add(GerehIdentity.userIdFor(m.getSk()));
            }
        }
        all.removeIf(u -> !keep.contains(u.id));
        return all;
    }

    static TLRPC.TL_updates chatUpdates(ChatNode node, Group g) {
        TLRPC.TL_updates ups = new TLRPC.TL_updates();
        ups.chats.add(channelFor(node, g));
        ups.users.addAll(usersFor(node, g));
        ups.date = (int) (System.currentTimeMillis() / 1000);
        ups.seq = 0;
        return ups;
    }

    private static Group need(ChatNode node, long id) {
        Group g = GerehGroups.groupFor(node, id);
        if (g == null || !g.getChannel()) {
            throw new IllegalArgumentException("unknown channel");
        }
        return g;
    }

    private static TLRPC.ChannelParticipant participantFor(ChatNode node, Group g, GroupMember m) {
        long uid = m.getSk().equals(selfSk(node)) ? UserConfig.getInstance(Messenger.account()).getClientUserId() : GerehIdentity.userIdFor(m.getSk());
        // when they joined (the joining event's own time): a subscriber list read «joined just now»
        // for everyone when this was the moment of asking. Unknown (an older core): 0, and the
        // list shows the member's status line instead (ChatUsersActivity)
        int date = (int) (m.getJoinedMs() / 1000);
        TLRPC.ChannelParticipant p;
        if (m.getOwner()) {
            TLRPC.TL_channelParticipantCreator c = new TLRPC.TL_channelParticipantCreator();
            c.admin_rights = new TLRPC.TL_chatAdminRights();
            c.admin_rights.post_messages = true;
            c.admin_rights.change_info = true;
            c.admin_rights.invite_users = true;
            c.admin_rights.add_admins = true;
            p = c;
        } else if (m.getAdmin()) {
            TLRPC.TL_channelParticipantAdmin a = new TLRPC.TL_channelParticipantAdmin();
            a.admin_rights = new TLRPC.TL_chatAdminRights();
            a.admin_rights.post_messages = true;
            a.admin_rights.change_info = (m.getRights() & 1) != 0;
            a.admin_rights.invite_users = (m.getRights() & 8) != 0;
            a.promoted_by = g.getOwner().equals(selfSk(node)) ? UserConfig.getInstance(Messenger.account()).getClientUserId() : GerehIdentity.userIdFor(g.getOwner());
            a.date = date;
            if (!TextUtils.isEmpty(m.getTitle())) {
                a.rank = m.getTitle();
                a.flags |= 4;
            }
            p = a;
        } else {
            TLRPC.TL_channelParticipant c = new TLRPC.TL_channelParticipant();
            c.date = date;
            p = c;
        }
        TLRPC.TL_peerUser pu = new TLRPC.TL_peerUser();
        pu.user_id = uid;
        p.peer = pu;
        return p;
    }

    /** A channel request, or a shared one whose peer is a channel; throws NotGroup otherwise. */
    static TLObject answer(ChatNode node, TLObject req, RequestDelegate done) throws Exception {
        if (req instanceof TLRPC.TL_channels_createChannel) {
            TLRPC.TL_channels_createChannel q = (TLRPC.TL_channels_createChannel) req;
            if (!q.broadcast) {
                throw new IllegalArgumentException("supergroups are not made here");
            }
            String gid = node.groupCreate(q.title != null ? q.title : "", q.about != null ? q.about : "", true);
            Group made = node.groupState(gid);
            TLRPC.TL_updates ups = chatUpdates(node, made);
            // its first line, so the new channel is in the list at once (GerehGroups.createdLine)
            TL_update.TL_updateNewChannelMessage u = new TL_update.TL_updateNewChannelMessage();
            u.message = GerehGroups.createdLine(made);
            u.pts = bump(gid);
            u.pts_count = 1;
            ups.updates.add(u);
            return ups;
        }
        if (req instanceof TLRPC.TL_channels_getFullChannel) {
            Group g = need(node, channelOf(((TLRPC.TL_channels_getFullChannel) req).channel));
            TLRPC.TL_channelFull f = new TLRPC.TL_channelFull();
            f.id = GerehGroups.chatIdFor(g.getId());
            f.about = g.getAbout();
            f.available_reactions = GerehReactions.allowed();
            f.flags |= 1 << 30;
            // both counts travel only with their flags (bits 0 and 1): without them the answer came
            // back with 0 and the header read «کانال خصوصی» (Ehsan, 27 Oct 2026)
            f.participants_count = g.getMembers().size();
            f.flags |= 1;
            int admins = 0;
            for (GroupMember m : g.getMembers()) {
                if (m.getAdmin()) {
                    admins++;
                }
            }
            f.admins_count = admins;
            f.flags |= 2;
            if (g.getAdmin()) {
                // «removed users» on the edit page: the bans the list itself shows (SPEC 7.1); kicked
                // and banned counts travel with flag bit 2, without which it read 0
                f.kicked_count = g.getBanned().size();
                f.flags |= 4;
            }
            f.read_inbox_max_id = readMax(g.getId());
            f.read_outbox_max_id = Integer.MAX_VALUE;
            f.chat_photo = GerehPhotos.chatFullPhoto(g);
            GerehInvites.dressFull(f, node, g);
            f.notify_settings = GerehNotify.of(-f.id);
            int pin = GerehPins.newest(node, GerehGroups.inputPeerFor(g));
            if (pin != 0) {
                f.pinned_msg_id = pin;
                f.flags |= 32;
            }
            f.pts = pts(g.getId());
            f.can_view_participants = g.getAdmin();
            TLRPC.TL_messages_chatFull r = new TLRPC.TL_messages_chatFull();
            r.full_chat = f;
            r.chats.add(channelFor(node, g));
            r.users.addAll(usersFor(node, g));
            return r;
        }
        if (req instanceof TLRPC.TL_channels_getParticipants) {
            TLRPC.TL_channels_getParticipants q = (TLRPC.TL_channels_getParticipants) req;
            Group g = need(node, channelOf(q.channel));
            TLRPC.TL_channels_channelParticipants r = new TLRPC.TL_channels_channelParticipants();
            TLRPC.ChannelParticipantsFilter f = q.filter;
            if (f instanceof TLRPC.TL_channelParticipantsKicked || f instanceof TLRPC.TL_channelParticipantsBanned) {
                // the admins' «removed users»: every ban standing now (SPEC 7.1), named by the card
                // the member had when banned; one page holds them all
                if (g.getAdmin() && q.offset == 0) {
                    java.util.Map<String, org.mlmchat.core.Contact> contacts = new java.util.HashMap<>();
                    for (org.mlmchat.core.Contact c : node.contacts()) {
                        contacts.put(c.getSk(), c);
                    }
                    long far = System.currentTimeMillis() + 366L * 86_400_000L;
                    for (java.util.Map.Entry<String, Long> e : g.getBanned().entrySet()) {
                        TLRPC.TL_channelParticipantBanned p = new TLRPC.TL_channelParticipantBanned();
                        TLRPC.TL_peerUser pu = new TLRPC.TL_peerUser();
                        pu.user_id = GerehIdentity.userIdFor(e.getKey());
                        p.peer = pu;
                        p.left = true;
                        p.banned_rights = new TLRPC.TL_chatBannedRights();
                        p.banned_rights.view_messages = f instanceof TLRPC.TL_channelParticipantsKicked;
                        p.banned_rights.send_messages = true;
                        p.banned_rights.until_date = e.getValue() > far ? 0 : (int) (e.getValue() / 1000);
                        p.date = (int) (System.currentTimeMillis() / 1000);
                        r.participants.add(p);
                        String name = g.getBannedNames().get(e.getKey());
                        r.users.add(GerehGroups.userFor(new GroupMember(e.getKey(), name != null ? name : "", false, false, 0, "", 0), contacts));
                    }
                }
                r.count = r.participants.size();
                return r;
            }
            // Gereh has no bots; one page holds everyone
            boolean none = f instanceof TLRPC.TL_channelParticipantsBots || q.offset > 0;
            boolean adminsOnly = f instanceof TLRPC.TL_channelParticipantsAdmins;
            boolean contactsOnly = f instanceof TLRPC.TL_channelParticipantsContacts;
            String search = f != null && f.q != null ? f.q.trim().toLowerCase() : "";
            java.util.Set<String> contacts = new java.util.HashSet<>();
            if (contactsOnly) {
                for (org.mlmchat.core.Contact c : node.contacts()) {
                    contacts.add(c.getSk());
                }
            }
            String me = selfSk(node);
            for (GroupMember m : none ? java.util.Collections.<GroupMember>emptyList() : g.getMembers()) {
                if ((adminsOnly || !g.getAdmin()) && !m.getAdmin() && !m.getSk().equals(me)) {
                    continue; // subscribers are for the admins' eyes only (usersFor)
                }
                if (contactsOnly && !contacts.contains(m.getSk()) && !m.getSk().equals(selfSk(node))) {
                    continue;
                }
                if (!search.isEmpty() && !m.getName().toLowerCase().contains(search)) {
                    continue;
                }
                r.participants.add(participantFor(node, g, m));
            }
            r.count = r.participants.size();
            r.users.addAll(usersFor(node, g));
            return r;
        }
        if (req instanceof TLRPC.TL_channels_getParticipant) {
            TLRPC.TL_channels_getParticipant q = (TLRPC.TL_channels_getParticipant) req;
            Group g = need(node, channelOf(q.channel));
            TLRPC.TL_channels_channelParticipant r = new TLRPC.TL_channels_channelParticipant();
            for (GroupMember m : g.getMembers()) {
                if (m.getSk().equals(selfSk(node))) {
                    r.participant = participantFor(node, g, m);
                }
            }
            if (r.participant == null) {
                throw new IllegalArgumentException("not a member");
            }
            r.users.addAll(usersFor(node, g));
            return r;
        }
        if (req instanceof TLRPC.TL_channels_inviteToChannel) {
            TLRPC.TL_channels_inviteToChannel q = (TLRPC.TL_channels_inviteToChannel) req;
            Group g = need(node, channelOf(q.channel));
            java.util.Set<String> members = new java.util.HashSet<>();
            for (GroupMember m : g.getMembers()) {
                members.add(m.getSk());
            }
            for (TLRPC.InputUser iu : q.users) {
                long uid = iu instanceof TLRPC.TL_inputUser ? ((TLRPC.TL_inputUser) iu).user_id : 0;
                String sk = Messenger.skForUser(uid);
                // the admin screen invites the person first (setUserAdminRole): a member stays as they are
                if (sk != null && !members.contains(sk)) {
                    node.groupAdd(g.getId(), sk);
                }
            }
            TLRPC.TL_messages_invitedUsers r = new TLRPC.TL_messages_invitedUsers();
            r.updates = chatUpdates(node, node.groupState(g.getId()));
            return r;
        }
        if (req instanceof TLRPC.TL_channels_leaveChannel) {
            Group g = need(node, channelOf(((TLRPC.TL_channels_leaveChannel) req).channel));
            node.groupRemove(g.getId(), selfSk(node), false);
            Group after = node.groupState(g.getId());
            return chatUpdates(node, after != null ? after : g);
        }
        if (req instanceof TLRPC.TL_channels_deleteChannel) {
            // its owner ends it for everyone (SPEC 7: Dissolve)
            Group g = need(node, channelOf(((TLRPC.TL_channels_deleteChannel) req).channel));
            return chatUpdates(node, node.groupDissolve(g.getId()));
        }
        if (req instanceof TLRPC.TL_channels_editTitle) {
            TLRPC.TL_channels_editTitle q = (TLRPC.TL_channels_editTitle) req;
            Group g = need(node, channelOf(q.channel));
            node.groupSetInfo(g.getId(), q.title, g.getAbout());
            return chatUpdates(node, node.groupState(g.getId()));
        }
        if (req instanceof TLRPC.TL_channels_editAdmin) {
            TLRPC.TL_channels_editAdmin q = (TLRPC.TL_channels_editAdmin) req;
            Group g = need(node, channelOf(q.channel));
            long uid = q.user_id instanceof TLRPC.TL_inputUser ? ((TLRPC.TL_inputUser) q.user_id).user_id : 0;
            String sk = Messenger.skForUser(uid);
            if (sk == null) {
                throw new IllegalArgumentException("not a member");
            }
            TLRPC.TL_chatAdminRights r = q.admin_rights;
            int rights = 0;
            if (r != null) {
                rights |= r.change_info ? 1 : 0;
                rights |= r.delete_messages ? 2 : 0;
                rights |= r.ban_users ? 4 : 0;
                rights |= r.invite_users ? 8 : 0;
                rights |= r.pin_messages ? 16 : 0;
                rights |= r.add_admins ? 32 : 0;
            }
            boolean admin = r != null && (rights != 0 || r.post_messages);
            node.groupSetAdmin(g.getId(), sk, admin, rights, q.rank != null ? q.rank : "");
            return chatUpdates(node, node.groupState(g.getId()));
        }
        if (req instanceof TLRPC.TL_channels_editBanned) {
            // Telegram's «remove from the channel» (view_messages): out, and banned for good (SPEC
            // 7.1), so the admins' list of the removed has them. Before, nothing answered this and
            // a subscriber could not be removed at all. No rights at all for someone banned (the
            // list's «unban»): the ban is lifted and they stay out until added again (SPEC 7.2
            // `Unban`, T59). Any other rights are one member's own (SPEC 7.2 `Restrict`), in a
            // group too, whose «exception» comes here from ChatRightsEditActivity (Ehsan, 28 Oct
            // 2026: a member's permissions could not be changed): what is left them, until when;
            // none taken lifts it.
            TLRPC.TL_channels_editBanned q = (TLRPC.TL_channels_editBanned) req;
            Group g = GerehGroups.groupFor(node, channelOf(q.channel));
            if (g == null) {
                throw new IllegalArgumentException("unknown group");
            }
            long uid = q.participant instanceof TLRPC.TL_inputPeerUser ? ((TLRPC.TL_inputPeerUser) q.participant).user_id : 0;
            String sk = Messenger.skForUser(uid);
            if (sk == null) {
                throw new IllegalArgumentException("unknown user");
            }
            TLRPC.TL_chatBannedRights b = q.banned_rights;
            Group st;
            if (b != null && b.view_messages) {
                node.groupRemove(g.getId(), sk, true);
                st = node.groupState(g.getId());
            } else if (g.getBanned().containsKey(sk) && (b == null || !b.send_messages && !b.send_media)) {
                node.groupUnban(g.getId(), sk);
                st = node.groupState(g.getId());
            } else {
                int left = GerehGroups.memberRights(b);
                boolean none = b == null || (left & g.getDefaults()) == g.getDefaults();
                long far = System.currentTimeMillis() + 366L * 86_400_000L;
                long until = none ? 0 : b.until_date == 0 || b.until_date * 1000L > far ? Long.MAX_VALUE : b.until_date * 1000L;
                st = node.groupRestrict(g.getId(), sk, none ? -1 : left, until);
            }
            return g.getChannel() ? chatUpdates(node, st) : GerehGroups.chatUpdates(node, st);
        }
        if (req instanceof TLRPC.TL_channels_toggleSlowMode) {
            // slow mode in a Gereh group (q13, T73; SPEC 7.2 `SetSlowMode`): the permissions page's
            // choice, an admin's signed event every phone holds; a group names itself by its id
            // here as on the restrict sheet. A channel's members do not post: none there.
            TLRPC.TL_channels_toggleSlowMode q = (TLRPC.TL_channels_toggleSlowMode) req;
            Group g = GerehGroups.groupFor(node, channelOf(q.channel));
            if (g == null || g.getChannel()) {
                throw new IllegalArgumentException("unknown group");
            }
            node.groupSetSlow(g.getId(), Math.max(0, q.seconds));
            return GerehGroups.chatUpdates(node, node.groupState(g.getId()));
        }
        if (req instanceof TLRPC.TL_channels_readHistory) {
            TLRPC.TL_channels_readHistory q = (TLRPC.TL_channels_readHistory) req;
            Group g = GerehGroups.groupFor(node, channelOf(q.channel));
            if (g != null) {
                setRead(g.getId(), q.max_id);
            }
            return new TLRPC.TL_boolTrue();
        }
        if (req instanceof TLRPC.TL_channels_deleteMessages) {
            // a channel's posts go for every member (SPEC 5.2; Ehsan, 25 Oct 2026: «باید برای همه
            // حذف بشه»): an admin's with the right, or the author's own. Before, nothing answered
            // this, so a post went from the screen only and came back
            TLRPC.TL_channels_deleteMessages q = (TLRPC.TL_channels_deleteMessages) req;
            Group g = GerehGroups.groupFor(node, channelOf(q.channel));
            if (g == null) {
                throw new IllegalArgumentException("unknown channel");
            }
            long[] seqs = new long[q.id.size()];
            int n = 0;
            for (Integer id : q.id) {
                if (id != null && id >= GerehGroups.MSG_BASE) {
                    seqs[n++] = id - GerehGroups.MSG_BASE;
                }
            }
            if (n > 0) {
                node.groupDeleteSeqs(java.util.Arrays.copyOf(seqs, n), true);
            }
            TLRPC.TL_messages_affectedMessages r = new TLRPC.TL_messages_affectedMessages();
            r.pts = bump(g.getId());
            r.pts_count = 1;
            return r;
        }
        if (req instanceof TLRPC.TL_channels_getChannels) {
            TLRPC.TL_messages_chats r = new TLRPC.TL_messages_chats();
            for (TLRPC.InputChannel ic : ((TLRPC.TL_channels_getChannels) req).id) {
                Group g = GerehGroups.groupFor(node, channelOf(ic));
                if (g != null && g.getChannel()) {
                    r.chats.add(channelFor(node, g));
                }
            }
            return r;
        }
        if (req instanceof TLRPC.TL_updates_getChannelDifference) {
            TLRPC.TL_updates_getChannelDifference q = (TLRPC.TL_updates_getChannelDifference) req;
            Group g = need(node, channelOf(q.channel));
            int now = pts(g.getId());
            if (q.pts >= now) {
                TLRPC.TL_updates_channelDifferenceEmpty e = new TLRPC.TL_updates_channelDifferenceEmpty();
                e.pts = now;
                e.isFinal = true;
                return e;
            }
            TLRPC.TL_updates_channelDifferenceTooLong t = new TLRPC.TL_updates_channelDifferenceTooLong();
            ArrayList<TLRPC.Message> top = new ArrayList<>();
            t.dialog = dialogFor(node, g, top);
            List<GroupMessage> rows = shown(node.groupMessages(g.getId(), 50));
            for (GroupMessage m : rows) {
                t.messages.add(toTl(node, m, g));
            }
            Collections.reverse(t.messages);
            if (rows.size() < 50) {
                t.messages.add(GerehGroups.createdLine(g)); // the whole history: its first line too
            }
            t.chats.add(channelFor(node, g));
            t.users.addAll(usersFor(node, g));
            t.isFinal = true;
            return t;
        }
        if (req instanceof TLRPC.TL_messages_getHistory && channelOf(((TLRPC.TL_messages_getHistory) req).peer) != 0) {
            TLRPC.TL_messages_getHistory q = (TLRPC.TL_messages_getHistory) req;
            Group g = need(node, channelOf(q.peer));
            TLRPC.TL_messages_channelMessages r = new TLRPC.TL_messages_channelMessages();
            // a page at a time (GerehGroups.historyPage); the count is the core's, the «created» line too
            r.messages.addAll(GerehGroups.historyPage(node, g, m -> toTl(node, m, g), q.offset_id, q.add_offset, q.limit, q.max_id, q.min_id));
            r.count = node.groupMarks(g.getId(), 0).getTotal() + 1;
            r.pts = pts(g.getId());
            r.chats.add(channelFor(node, g));
            r.users.addAll(usersFor(node, g));
            return r;
        }
        if (req instanceof TLRPC.TL_messages_sendMessage && channelOf(((TLRPC.TL_messages_sendMessage) req).peer) != 0) {
            TLRPC.TL_messages_sendMessage q = (TLRPC.TL_messages_sendMessage) req;
            Group g = need(node, channelOf(q.peer));
            if (!g.getCanPost()) {
                throw new Messenger.Refusal(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.GerehGroupCannotPost));
            }
            String id = node.groupSendText(g.getId(), q.message, GerehGroups.replySeq(q.reply_to), GerehEntities.toCore(q.entities));
            return sent(node, g, node.groupMessage(g.getId(), id, 0), q.random_id, done);
        }
        if (req instanceof TLRPC.TL_messages_editMessage && channelOf(((TLRPC.TL_messages_editMessage) req).peer) != 0) {
            // Edit (SPEC 5): one's own post, text or caption only; every reader gets it
            TLRPC.TL_messages_editMessage q = (TLRPC.TL_messages_editMessage) req;
            Group g = need(node, channelOf(q.peer));
            if (q.media != null || q.message == null) {
                throw new IllegalArgumentException("only the text is edited");
            }
            node.groupEditText(q.id - GerehGroups.MSG_BASE, q.message, GerehEntities.toCore(q.entities));
            TLRPC.TL_updates ups = new TLRPC.TL_updates();
            GroupMessage row = GerehGroups.bySeq(node, g, q.id - GerehGroups.MSG_BASE);
            if (row != null) {
                ups.updates.add(editUpdate(node, g, row));
            }
            ups.chats.add(channelFor(node, g));
            ups.users.addAll(usersFor(node, g));
            ups.date = (int) (System.currentTimeMillis() / 1000);
            ups.seq = 0;
            return ups;
        }
        throw new GerehGroups.NotGroup();
    }

    private static TL_update.TL_updateEditChannelMessage editUpdate(ChatNode node, Group g, GroupMessage row) {
        TL_update.TL_updateEditChannelMessage u = new TL_update.TL_updateEditChannelMessage();
        u.message = toTl(node, row, g);
        u.pts = bump(g.getId());
        u.pts_count = 1;
        return u;
    }

    /** A post of channel [g] was edited by its author (SPEC 5 `Edit`): Telegram's copy is replaced. */
    static void onEdited(ChatNode node, Group g, GroupMessage row) {
        final TLRPC.Chat chat = channelFor(node, g);
        final ArrayList<TLRPC.User> users = usersFor(node, g);
        Utilities.stageQueue.postRunnable(() -> {
            TLRPC.TL_updates ups = new TLRPC.TL_updates();
            ups.updates.add(editUpdate(node, g, row));
            ups.chats.add(chat);
            ups.users.addAll(users);
            ups.date = (int) (System.currentTimeMillis() / 1000);
            ups.seq = 0;
            MessagesController.getInstance(Messenger.account()).processUpdates(ups, false);
        });
    }

    /** The answer to a post just made (text or file): its id for Telegram's copy, and the post with the channel's next pts. */
    static TLObject sent(ChatNode node, Group g, GroupMessage row, long randomId, RequestDelegate done) {
        return sentAll(node, g, row != null ? java.util.Collections.singletonList(row) : java.util.Collections.emptyList(),
                java.util.Collections.singletonList(randomId), done);
    }

    /** The answer to posts just made, one or several sent together (`messages.sendMultiMedia`). */
    static TLObject sentAll(ChatNode node, Group g, java.util.List<GroupMessage> rows, java.util.List<Long> randomIds, RequestDelegate done) {
        {
            Utilities.stageQueue.postRunnable(() -> {
                TLRPC.TL_updates ups = new TLRPC.TL_updates();
                for (int i = 0; i < rows.size(); i++) {
                    GroupMessage sent = rows.get(i);
                    TL_update.TL_updateMessageID mid = new TL_update.TL_updateMessageID();
                    mid.id = GerehGroups.MSG_BASE + (int) sent.getSeq();
                    mid.random_id = randomIds.get(i);
                    ups.updates.add(mid);
                    TL_update.TL_updateNewChannelMessage u = new TL_update.TL_updateNewChannelMessage();
                    u.message = toTl(node, sent, g);
                    u.pts = bump(g.getId());
                    u.pts_count = 1;
                    ups.updates.add(u);
                }
                ups.chats.add(channelFor(node, g));
                ups.date = (int) (System.currentTimeMillis() / 1000);
                ups.seq = 0;
                done.run(ups, null);
            });
            return null;
        }
    }
}
