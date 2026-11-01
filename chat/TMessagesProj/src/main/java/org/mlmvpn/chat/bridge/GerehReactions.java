package org.mlmvpn.chat.bridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.mlmchat.core.ChatNode;
import org.mlmchat.core.ChatEvent;
import org.mlmchat.core.Group;
import org.mlmchat.core.GroupMessage;
import org.mlmchat.core.Message;
import org.mlmchat.core.Reaction;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_update;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MLMCHAT: reactions (Ehsan, 25 Oct 2026: «توی چت ها هم ری اکشن اصلا نداریم، توی گروه و کانال هم
 * همینطور»). One per person per message, as Telegram without Premium. They pass from phone to phone
 * like messages (SPEC 5 `Reaction`; in the official chats a signed feed item, GerehFeeds), and are
 * drawn with the app's own emoji: Telegram's animated ones live on its servers, which Gereh never
 * asks (ImageLocation, ReactionsLayoutInBubble, ReactionsContainerLayout).
 */
final class GerehReactions {
    private static final String TAG = "MLMCHAT";
    /** The reactions offered: Telegram's free set, in its order. */
    private static final String[] EMOJI = {
            "👍", "👎", "❤", "🔥", "🥰", "👏", "😁", "🤔", "🤯", "😱", "🤬", "😢", "🎉", "🤩", "🤮", "💩", "🙏", "👌",
            "🕊", "🤡", "🥱", "🥴", "😍", "🐳", "❤‍🔥", "🌚", "🌭", "💯", "🤣", "⚡", "🍌", "🏆", "💔", "🤨", "😐", "🍓",
            "🍾", "💋", "😈", "😴", "😭", "🤓", "👻", "👨‍💻", "👀", "🎃", "🙈", "😇", "😨", "🤝", "✍", "🤗", "🫡",
            "🎅", "🎄", "☃", "💅", "🤪", "🗿", "🆒", "💘", "🙉", "🦄", "😘", "💊", "🙊", "😎", "👾", "🤷‍♂", "🤷",
            "🤷‍♀", "😡",
    };

    /** The reactions of each chat or group (key: its hex id), by message id; read once, dropped on a change. */
    private static final Map<String, Map<String, List<Reaction>>> cache = new HashMap<>();

    private GerehReactions() {
    }

    // ---- what Telegram shows --------------------------------------------------------------

    /** messages.getAvailableReactions: the set above, with no animation (Bridge.build round-trips it). */
    static TLObject available() {
        TLRPC.TL_messages_availableReactions r = new TLRPC.TL_messages_availableReactions();
        r.hash = EMOJI.length;
        for (String e : EMOJI) {
            TLRPC.TL_availableReaction a = new TLRPC.TL_availableReaction();
            a.reaction = e;
            a.title = e;
            // no files: ImageLocation gives none for an empty document, and the screens draw the emoji
            a.static_icon = new TLRPC.TL_documentEmpty();
            a.appear_animation = new TLRPC.TL_documentEmpty();
            a.select_animation = new TLRPC.TL_documentEmpty();
            a.activate_animation = new TLRPC.TL_documentEmpty();
            a.effect_animation = new TLRPC.TL_documentEmpty();
            r.reactions.add(a);
        }
        return r;
    }

    /**
     * The set's emoji, loaded ahead: the bar draws the app's emoji, and one whose picture is not
     * loaded yet is drawn empty until it is (Ehsan's A50, 28 Oct 2026: the first bar after a start
     * was empty).
     */
    static void preload() {
        for (String e : EMOJI) {
            Emoji.preloadEmoji(Emoji.fixEmoji(e));
            Emoji.preloadEmoji(e);
        }
    }

    /** Every chat's info says all of them may be used (null there hides the bar in groups and channels). */
    static TLRPC.ChatReactions allowed() {
        TLRPC.TL_chatReactionsAll a = new TLRPC.TL_chatReactionsAll();
        a.allow_custom = false;
        return a;
    }

    /** Telegram's counts for one message, ours marked chosen; null when nobody reacted. */
    static TLRPC.TL_messageReactions summary(List<Reaction> list, String me) {
        return summary(list, me, false);
    }

    /**
     * The same, and with `who` (a group, where everyone sees who reacted, as in Telegram, or a
     * one-to-one chat; a channel's stay counts) the reactions one by one: Telegram draws up to three
     * faces on the button and lists them all on a long press (messages.getMessageReactionsList).
     */
    static TLRPC.TL_messageReactions summary(List<Reaction> list, String me, boolean who) {
        return summary(list, me, who, false);
    }

    /** The same; `unseen`: the others' reactions are marked not seen yet (our own message, see [unseen]). */
    private static TLRPC.TL_messageReactions summary(List<Reaction> list, String me, boolean who, boolean unseen) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        Map<String, int[]> counts = new LinkedHashMap<>();
        String mine = null;
        for (Reaction r : list) {
            counts.computeIfAbsent(r.getEmoji(), k -> new int[1])[0]++;
            if (r.getPeer().equals(me)) {
                mine = r.getEmoji();
            }
        }
        TLRPC.TL_messageReactions out = new TLRPC.TL_messageReactions();
        out.can_see_list = who;
        if (who) {
            for (Reaction r : list) {
                TLRPC.TL_messagePeerReaction p = peerReaction(r);
                p.unread = unseen && !r.getPeer().equals(me);
                out.recent_reactions.add(p);
            }
            out.flags |= 2;
        }
        for (Map.Entry<String, int[]> e : counts.entrySet()) {
            TLRPC.TL_reactionCount c = new TLRPC.TL_reactionCount();
            TLRPC.TL_reactionEmoji re = new TLRPC.TL_reactionEmoji();
            re.emoticon = e.getKey();
            c.reaction = re;
            c.count = e.getValue()[0];
            if (e.getKey().equals(mine)) {
                c.chosen = true;
                c.chosen_order = 0;
                c.flags |= 1;
            }
            out.results.add(c);
        }
        return out;
    }

    /** A message as Telegram gets it, with its reactions. */
    static void dress(TLRPC.Message msg, List<Reaction> list, String me) {
        dress(msg, list, me, false);
    }

    /** The same, with who reacted when `who` (a group's message). */
    static void dress(TLRPC.Message msg, List<Reaction> list, String me, boolean who) {
        TLRPC.TL_messageReactions s = summary(list, me, who);
        if (s != null) {
            msg.reactions = s;
            msg.flags |= 1 << 20;
        }
    }

    /**
     * Message `id` of the chat with contact `scope` or of group `scope` (`group`) as Telegram gets it:
     * its reactions, who made them when `who` (not a channel's), and whether ours has some not seen.
     */
    static void dressFor(TLRPC.Message msg, ChatNode node, String scope, boolean group, String id, boolean who) {
        TLRPC.TL_messageReactions s = summary(of(node, scope, group, id), me(node), who, who && isUnseen(scope, id));
        if (s != null) {
            msg.reactions = s;
            msg.flags |= 1 << 20;
        }
    }

    // ---- reactions to our own messages, not seen yet -----------------------------------------

    /**
     * Our own messages someone else reacted to since we last looked, as Telegram counts them (the
     * mark on the chat in the list, and the button in the chat that goes to them): «scope|id», the
     * contact's key or the group's id and the message's own id, kept across starts.
     * messages.readReactions clears a chat's.
     */
    private static Set<String> unseen;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("gereh_reactions", Context.MODE_PRIVATE);
    }

    private static synchronized Set<String> unseen() {
        if (unseen == null) {
            unseen = new HashSet<>(prefs().getStringSet("unseen", Collections.emptySet()));
        }
        return unseen;
    }

    private static synchronized void saveUnseen() {
        prefs().edit().putStringSet("unseen", new HashSet<>(unseen())).apply();
    }

    private static synchronized boolean isUnseen(String scope, String id) {
        return unseen().contains(scope + "|" + id);
    }

    /** How many of a chat's messages have a reaction not seen yet: the dialog's count. */
    static synchronized int unseenIn(String scope) {
        int n = 0;
        for (String k : unseen()) {
            if (k.startsWith(scope + "|")) {
                n++;
            }
        }
        return n;
    }

    /** The messages (own ids) of a chat with a reaction not seen yet. */
    private static synchronized List<String> unseenOf(String scope) {
        List<String> out = new ArrayList<>();
        for (String k : unseen()) {
            if (k.startsWith(scope + "|")) {
                out.add(k.substring(scope.length() + 1));
            }
        }
        return out;
    }

    private static synchronized void seenAll(String scope) {
        boolean changed = false;
        for (java.util.Iterator<String> it = unseen().iterator(); it.hasNext(); ) {
            if (it.next().startsWith(scope + "|")) {
                it.remove();
                changed = true;
            }
        }
        if (changed) {
            saveUnseen();
        }
    }

    /** The reactions to one message of the chat with contact `sk`, or of group `gid` (`group`). */
    static synchronized List<Reaction> of(ChatNode node, String scope, boolean group, String messageId) {
        Map<String, List<Reaction>> byMsg = cache.get(scope);
        if (byMsg == null) {
            byMsg = new HashMap<>();
            try {
                for (Reaction r : group ? node.groupReactions(scope) : node.reactions(scope)) {
                    byMsg.computeIfAbsent(r.getId(), k -> new ArrayList<>()).add(r);
                }
            } catch (Throwable t) {
                Log.w(TAG, "reactions not read: " + t.getClass().getSimpleName());
            }
            cache.put(scope, byMsg);
        }
        return byMsg.get(messageId);
    }

    private static ChatNode meNode;
    private static String meSk;

    /** Our own key: asked of the core once per node, not once per message drawn. */
    static synchronized String me(ChatNode node) {
        if (node != meNode || meSk == null) {
            meSk = node.getMe().getSk();
            meNode = node;
        }
        return meSk;
    }

    // ---- requests -------------------------------------------------------------------------

    static boolean handles(TLObject req) {
        return req instanceof TLRPC.TL_messages_sendReaction || req instanceof TLRPC.TL_messages_getMessageReactionsList
                || req instanceof TLRPC.TL_messages_readReactions || req instanceof TLRPC.TL_messages_getUnreadReactions;
    }

    /** messages.sendReaction: ours on one message (none in the list takes it back); and who reacted. */
    static TLObject answer(ChatNode node, TLObject req) {
        if (req instanceof TLRPC.TL_messages_getMessageReactionsList) {
            return reactors(node, (TLRPC.TL_messages_getMessageReactionsList) req);
        }
        if (req instanceof TLRPC.TL_messages_readReactions) {
            // the chat was looked at: its reactions are seen (Telegram reads nothing of the answer)
            String scope = scopeOf(node, ((TLRPC.TL_messages_readReactions) req).peer);
            if (scope != null) {
                seenAll(scope);
            }
            return new TLRPC.TL_messages_affectedHistory();
        }
        if (req instanceof TLRPC.TL_messages_getUnreadReactions) {
            return unseenMessages(node, (TLRPC.TL_messages_getUnreadReactions) req);
        }
        TLRPC.TL_messages_sendReaction q = (TLRPC.TL_messages_sendReaction) req;
        String emoji = "";
        for (TLRPC.Reaction r : q.reaction) {
            if (r instanceof TLRPC.TL_reactionEmoji) {
                emoji = ((TLRPC.TL_reactionEmoji) r).emoticon; // one each: the last one chosen
            }
        }
        long chat = GerehGroups.chatOf(q.peer);
        TL_update.TL_updateMessageReactions u = new TL_update.TL_updateMessageReactions();
        u.msg_id = q.msg_id;
        String feed = GerehFeeds.feedOf(chat);
        if (feed != null) {
            u.reactions = GerehFeeds.react(node, feed, q.msg_id, emoji);
            u.peer = channelPeer(chat);
        } else if (chat != 0) {
            Group g = GerehGroups.groupFor(node, chat);
            GroupMessage m = g == null ? null : groupMessage(node, g.getId(), q.msg_id - GerehGroups.MSG_BASE, null);
            if (m == null) {
                throw new IllegalArgumentException("unknown group message");
            }
            node.groupReact(g.getId(), m.getId(), emoji);
            u.reactions = summaryNow(node, g.getId(), true, m.getId(), !g.getChannel());
            u.peer = g.getChannel() ? channelPeer(chat) : chatPeer(chat);
        } else {
            String sk = Messenger.skForUser(Messenger.peerUser(q.peer));
            Message m = sk == null ? null : message(node, sk, q.msg_id, null);
            if (m == null) {
                throw new IllegalArgumentException("unknown message");
            }
            node.react(sk, m.getId(), emoji);
            u.reactions = summaryNow(node, sk, false, m.getId(), false);
            TLRPC.TL_peerUser pu = new TLRPC.TL_peerUser();
            pu.user_id = GerehIdentity.userIdFor(sk);
            u.peer = pu;
        }
        if (u.reactions == null) {
            u.reactions = new TLRPC.TL_messageReactions();
        }
        TLRPC.TL_updates ups = new TLRPC.TL_updates();
        ups.updates.add(u);
        ups.date = (int) (System.currentTimeMillis() / 1000);
        ups.seq = 0;
        return ups;
    }

    // ---- events ---------------------------------------------------------------------------

    /** Someone's reaction changed (theirs arrived, or ours went out): the message in Telegram follows. */
    static void onEvent(ChatNode node, ChatEvent.ReactionChanged e) {
        synchronized (GerehReactions.class) {
            cache.remove(e.getChat());
        }
        TL_update.TL_updateMessageReactions u = new TL_update.TL_updateMessageReactions();
        boolean who = true;
        boolean ours = false;
        try {
            if (e.getGroup()) {
                GroupMessage m = groupMessage(node, e.getChat(), -1, e.getId());
                Group g = node.groupState(e.getChat());
                if (m == null || g == null) {
                    return;
                }
                who = !g.getChannel();
                ours = m.getOutgoing();
                long chat = GerehGroups.chatIdFor(g.getId());
                u.peer = g.getChannel() ? channelPeer(chat) : chatPeer(chat);
                u.msg_id = GerehGroups.MSG_BASE + (int) m.getSeq();
            } else {
                Message m = message(node, e.getChat(), -1, e.getId());
                if (m == null) {
                    return;
                }
                ours = m.getOutgoing();
                TLRPC.TL_peerUser pu = new TLRPC.TL_peerUser();
                pu.user_id = GerehIdentity.userIdFor(e.getChat());
                u.peer = pu;
                u.msg_id = (int) m.getSeq();
            }
        } catch (Throwable t) {
            Log.w(TAG, "reaction not shown: " + t.getClass().getSimpleName());
            return;
        }
        String me = me(node);
        List<Reaction> list = of(node, e.getChat(), e.getGroup(), e.getId());
        if (who && ours && !e.getPeer().equals(me)) {
            // someone else's on our own message: not seen until the chat is looked at; taken back,
            // it is not waiting any more unless another one still is
            boolean waiting = false;
            if (list != null) {
                for (Reaction r : list) {
                    waiting |= !r.getPeer().equals(me);
                }
            }
            synchronized (GerehReactions.class) {
                if (waiting ? unseen().add(e.getChat() + "|" + e.getId()) : unseen().remove(e.getChat() + "|" + e.getId())) {
                    saveUnseen();
                }
            }
        }
        u.reactions = summary(list, me, who, who && isUnseen(e.getChat(), e.getId()));
        if (u.reactions == null) {
            u.reactions = new TLRPC.TL_messageReactions();
        }
        push(u);
        if (e.getGroup() && who && ours && !e.getPeer().equals(me)) {
            // Telegram tells the chat list and the open chat of a new count only for private chats
            // and forums (MessagesController.checkUnreadReactionsInternal2): a group's mark showed
            // only on the next redraw (A50, 28 Oct 2026). Once its update is in, they hear it here.
            final long dialogId = MessageObject.getPeerId(u.peer);
            final int msgId = u.msg_id;
            final String scope = e.getChat();
            AndroidUtilities.runOnUIThread(() -> {
                TLRPC.Dialog d = MessagesController.getInstance(Messenger.account()).dialogs_dict.get(dialogId);
                int count = d != null ? d.unread_reactions_count : unseenIn(scope);
                ArrayList<Integer> ids = new ArrayList<>();
                ids.add(msgId);
                NotificationCenter.getInstance(Messenger.account()).postNotificationName(
                        NotificationCenter.dialogsUnreadReactionsCounterChanged, dialogId, 0L, count, ids);
            }, 500);
        }
    }

    /**
     * messages.getMessageReactionsList: who reacted to a message, all or with one emoji, in one page.
     * A group's or a one-to-one chat's; a channel's and the official chats' stay counts.
     */
    private static TLObject reactors(ChatNode node, TLRPC.TL_messages_getMessageReactionsList q) {
        TLRPC.TL_messages_messageReactionsList out = new TLRPC.TL_messages_messageReactionsList();
        if (q.offset != null && !q.offset.isEmpty()) {
            return out; // the first page held them all
        }
        long chat = GerehGroups.chatOf(q.peer);
        List<Reaction> list = null;
        Map<Long, TLRPC.User> users = new HashMap<>();
        if (chat != 0) {
            Group g = GerehFeeds.feedOf(chat) != null ? null : GerehGroups.groupFor(node, chat);
            GroupMessage m = g == null || g.getChannel() ? null : groupMessage(node, g.getId(), q.id - GerehGroups.MSG_BASE, null);
            if (m != null) {
                list = of(node, g.getId(), true, m.getId());
                for (TLRPC.User u : GerehGroups.usersFor(node, g)) {
                    users.put(u.id, u);
                }
            }
        } else {
            long uid = Messenger.peerUser(q.peer);
            String sk = Messenger.skForUser(uid);
            Message m = sk == null ? null : message(node, sk, q.id, null);
            if (m != null) {
                list = of(node, sk, false, m.getId());
                TLRPC.User self = UserConfig.getInstance(Messenger.account()).getCurrentUser();
                TLRPC.User them = MessagesController.getInstance(Messenger.account()).getUser(uid);
                if (self != null) {
                    users.put(self.id, self);
                }
                if (them != null) {
                    users.put(them.id, them);
                }
            }
        }
        if (list == null) {
            return out;
        }
        String want = q.reaction instanceof TLRPC.TL_reactionEmoji ? ((TLRPC.TL_reactionEmoji) q.reaction).emoticon : null;
        Set<Long> listed = new HashSet<>();
        for (Reaction r : list) {
            TLRPC.User u = users.get(GerehIdentity.userIdFor(r.getPeer()));
            if (u == null || want != null && !want.equals(r.getEmoji())) {
                continue; // one who left the group is not listed
            }
            out.reactions.add(peerReaction(r));
            if (listed.add(u.id)) {
                out.users.add(u);
            }
        }
        out.count = out.reactions.size();
        return out;
    }

    /** One person's reaction as Telegram lists it. */
    private static TLRPC.TL_messagePeerReaction peerReaction(Reaction r) {
        TLRPC.TL_messagePeerReaction p = new TLRPC.TL_messagePeerReaction();
        TLRPC.TL_peerUser u = new TLRPC.TL_peerUser();
        u.user_id = GerehIdentity.userIdFor(r.getPeer());
        p.peer_id = u;
        TLRPC.TL_reactionEmoji e = new TLRPC.TL_reactionEmoji();
        e.emoticon = r.getEmoji();
        p.reaction = e;
        return p;
    }

    /** Updates for Telegram, as a server would send them. */
    static void push(TLRPC.Update... list) {
        Utilities.stageQueue.postRunnable(() -> {
            TLRPC.TL_updates ups = new TLRPC.TL_updates();
            for (TLRPC.Update u : list) {
                ups.updates.add(u);
            }
            ups.date = (int) (System.currentTimeMillis() / 1000);
            ups.seq = 0;
            MessagesController.getInstance(Messenger.account()).processUpdates(ups, false);
        });
    }

    // ---- helpers --------------------------------------------------------------------------

    private static TLRPC.TL_messageReactions summaryNow(ChatNode node, String scope, boolean group, String id, boolean who) {
        synchronized (GerehReactions.class) {
            cache.remove(scope);
        }
        return summary(of(node, scope, group, id), me(node), who, who && isUnseen(scope, id));
    }

    /** The chat or group (its key or id) a request names; null for a channel or one not ours. */
    private static String scopeOf(ChatNode node, TLRPC.InputPeer peer) {
        long chat = GerehGroups.chatOf(peer);
        if (chat == 0) {
            return Messenger.skForUser(Messenger.peerUser(peer));
        }
        Group g = GerehFeeds.feedOf(chat) != null ? null : GerehGroups.groupFor(node, chat);
        return g == null || g.getChannel() ? null : g.getId();
    }

    /**
     * messages.getUnreadReactions: our messages in that chat with a reaction not seen yet, newest
     * first, from `add_offset` on (Telegram asks for the oldest one to go to, and takes its id).
     */
    private static TLObject unseenMessages(ChatNode node, TLRPC.TL_messages_getUnreadReactions q) {
        TLRPC.TL_messages_messages out = new TLRPC.TL_messages_messages();
        String scope = scopeOf(node, q.peer);
        if (scope == null) {
            return out;
        }
        long chat = GerehGroups.chatOf(q.peer);
        List<Integer> ids = new ArrayList<>();
        for (String id : unseenOf(scope)) {
            if (chat != 0) {
                GroupMessage m = groupMessage(node, scope, 0, id);
                if (m != null) {
                    ids.add(GerehGroups.MSG_BASE + (int) m.getSeq());
                }
            } else {
                Message m = message(node, scope, 0, id);
                if (m != null) {
                    ids.add((int) m.getSeq());
                }
            }
        }
        Collections.sort(ids, Collections.reverseOrder());
        int from = Math.max(0, q.add_offset);
        for (int i = from; i < ids.size() && i < from + Math.max(1, q.limit); i++) {
            TLRPC.TL_message m = new TLRPC.TL_message();
            m.id = ids.get(i);
            m.peer_id = chat != 0 ? chatPeer(chat) : userPeer(scope);
            m.message = "";
            m.out = true;
            out.messages.add(m);
        }
        return out;
    }

    private static TLRPC.Peer userPeer(String sk) {
        TLRPC.TL_peerUser p = new TLRPC.TL_peerUser();
        p.user_id = GerehIdentity.userIdFor(sk);
        return p;
    }

    /** A message of the chat with `sk` by its own id, or (`id` null) by Telegram id (`seq`): that row only. */
    private static Message message(ChatNode node, String sk, long seq, String id) {
        return node.message(sk, id != null ? id : "", id != null ? 0 : seq);
    }

    private static GroupMessage groupMessage(ChatNode node, String gid, long seq, String id) {
        return node.groupMessage(gid, id != null ? id : "", id != null ? 0 : seq);
    }

    static TLRPC.Peer channelPeer(long chat) {
        TLRPC.TL_peerChannel p = new TLRPC.TL_peerChannel();
        p.channel_id = chat;
        return p;
    }

    private static TLRPC.Peer chatPeer(long chat) {
        TLRPC.TL_peerChat p = new TLRPC.TL_peerChat();
        p.chat_id = chat;
        return p;
    }
}
