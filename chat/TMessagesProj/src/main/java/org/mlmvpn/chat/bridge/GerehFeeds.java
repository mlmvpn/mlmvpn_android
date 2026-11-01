package org.mlmvpn.chat.bridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.mlmchat.core.ChatNode;
import org.mlmchat.core.FeedInfo;
import org.mlmchat.core.FeedPost;
import org.telegram.SQLite.SQLiteCursor;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_update;
import org.telegram.ui.ChatActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MLMCHAT: the official channel and the official group (T38; step 6.4 of the work queue). Every
 * Gereh user is in both from the first start (Ehsan, 24 Oct 2026: «هرکه نرم افزار رو نصب میکنه
 * باید اتوماتیک وارد هردو بشه»), and cannot leave them, only mute them.
 *
 * They are the core's official feeds (`feed.rs`): no member list and no server; posts pass from
 * phone to phone. Their ids and the owner key ship with the app ({@link #CONFIG}); the owner key's
 * secret half stays offline with the owner and only signs the admin list. Here they look like
 * Telegram's own: the channel a broadcast channel, the group a supergroup, both marked verified.
 * Telegram's message ids for posts are kept per feed on this phone (a post's own id is 16 random
 * bytes).
 *
 * Their member count is everyone who used Gereh lately, estimated phone to phone (core
 * `census.rs`; Ehsan, 25 Oct 2026). Nobody joins or leaves them by hand, and no «X joined» line
 * is ever shown in them, only the count changes (Ehsan, 25 Oct 2026: that would be spam).
 */
final class GerehFeeds {
    private static final String TAG = "MLMCHAT";
    /**
     * Message ids of feed posts: this plus the phone's own count of them, per feed. Below
     * 0x60000000: ids from there to 0x6FFFFFFF are this Telegram's «ephemeral» (bot-only) messages
     * (MessageObject.isEphemeralMessageId), and the old base, 1 800 000 000, was one of them. Every
     * official post then carried an «only visible to you» bar, could not be selected or reacted to,
     * and a delete stayed on this phone (Telegram sent its own ephemeral delete, never ours), so the
     * post came back (Ehsan, 27 Oct 2026). {@link #renumber} moves the posts of an older build once.
     */
    private static final int FEED_BASE = 1_300_000_000;
    /** The keys of those ids (per post, the count per feed, the read mark): the old ones held the old range. */
    private static final String ID_KEY = "id2_", COUNT_KEY = "n2_", READ_KEY = "read2_";
    private static final int HISTORY_MAX = 500;
    /** Seconds after which Telegram asks again for an official chat on the screen (core feedWatch). */
    private static final int WATCH_POLL_S = 5;
    /** When the official chats began (25 Oct 2026, 00:00 UTC): every phone is a member since then. */
    private static final int OFFICIAL_SINCE_S = 1_792_886_400;

    /**
     * The admins of the official channel and group (their signed lists), by sk: they carry the blue
     * tick in every chat, one-to-one too (Ehsan, 27 Oct 2026). Read again at most once a minute, and
     * after any change to the official chats (onChanged).
     */
    private static volatile Set<String> officialAdmins = Collections.emptySet();
    private static volatile long adminsReadAt;
    /** A ban on this phone is logged once a run. */
    private static volatile boolean banLogged;

    /** Whether `sk` is an admin of the official channel or group: Telegram's `verified` for their user. */
    static boolean isOfficialAdmin(String sk) {
        long now = System.currentTimeMillis();
        ChatNode node = GerehIdentity.node();
        if (node != null && now - adminsReadAt > 60_000) {
            adminsReadAt = now;
            Set<String> s = new HashSet<>();
            for (String feed : new String[]{CHANNEL, GROUP}) {
                try {
                    FeedInfo info = node.feedInfo(feed);
                    if (info != null) {
                        s.addAll(info.getAdmins());
                    }
                } catch (Throwable ignored) {
                }
            }
            officialAdmins = s;
        }
        return sk != null && officialAdmins.contains(sk);
    }

    /**
     * The app's official channel and group: MLMVPN's ({@link org.mlmchat.core.Official}) unless the
     * app gave its own through {@link GerehHost#setFeeds} before this class was first used.
     */
    static final String CHANNEL = GerehHost.feedId(true);
    static final String GROUP = GerehHost.feedId(false);

    /**
     * Post ids already handed to Telegram, per feed, to tell new ones and taken-down ones. Kept on
     * the phone too: Telegram shows its own cached copy after a restart without asking, so a post
     * taken down meanwhile (on another phone) used to stay forever (Ehsan, 25 Oct 2026: «پیام
     * گذاشتم داخلش حذف میکنم حذف نمیشه»).
     */
    private static final Map<String, Set<String>> shownIds = new HashMap<>();
    /** Each shown post's reactions as last handed to Telegram ("" none), per feed: what changed goes as an update. */
    private static final Map<String, Map<String, String>> shownReactions = new HashMap<>();
    /** Each shown post's edit time as last handed to Telegram (0 never edited), per feed: a newer edit goes as an update. */
    private static final Map<String, Map<String, Long>> shownEdits = new HashMap<>();

    private GerehFeeds() {
    }

    /** Before the transports start (Messenger.run). */
    static void install(ChatNode node) {
        try {
            node.setFeeds(GerehHost.feeds());
        } catch (Throwable t) {
            Log.e(TAG, "official feeds not set: " + t.getClass().getSimpleName());
        }
        renumber(node);
    }

    static boolean isChannel(String feed) {
        return CHANNEL.equals(feed);
    }

    /** The feed a Telegram chat id names, or null. */
    static String feedOf(long chatId) {
        if (chatId == 0) {
            return null;
        }
        if (chatId == GerehGroups.chatIdFor(CHANNEL)) {
            return CHANNEL;
        }
        if (chatId == GerehGroups.chatIdFor(GROUP)) {
            return GROUP;
        }
        return null;
    }

    private static String feedOfPeer(TLRPC.InputPeer p) {
        return feedOf(GerehChannels.channelOf(p));
    }

    /** The official feed a request's peer is, or null (GerehMedia's files sent together). */
    static String feedOfInput(TLRPC.InputPeer p) {
        return p instanceof TLRPC.TL_inputPeerChannel ? feedOfPeer(p) : null;
    }

    /**
     * One file of several sent together, posted in the feed as `messages.sendMedia` posts one:
     * within a member's hourly count, refused with the reason when it may not be posted.
     */
    static String sendMediaPost(ChatNode node, String feed, String path, org.json.JSONObject info, TLRPC.InputReplyTo reply) {
        checkRate(node, feed);
        try {
            return node.feedSendMedia(feed, path, info, replyPost(node, feed, reply));
        } catch (Throwable t) {
            throw new Messenger.Refusal(LocaleController.getString(R.string.GerehGroupCannotPost));
        }
    }

    // ---- ids ------------------------------------------------------------------------------

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("gereh_feeds", Context.MODE_PRIVATE);
    }

    /**
     * Room left between two posts' ids, for a post that comes late: Telegram shows a chat in id
     * order, and a post given the next id after those that came before it showed under them (Ehsan,
     * 31 Oct 2026: taha's post came from the official copy after the replies to it had come from
     * phones, and sat under them). Ids up to {@link #TOP_KEY}'s first value went one by one.
     */
    private static final int STEP = 16;
    /** The highest id given in a feed since ids leave room (the old count, COUNT_KEY, stays as it was). */
    private static final String TOP_KEY = "top2_";
    /** Below Telegram's ephemeral ids (see FEED_BASE): past this, ids go one by one again. */
    private static final int ID_CEILING = 0x60000000 - 1;

    private static int topId(SharedPreferences p, String feed) {
        return Math.max(p.getInt(TOP_KEY + feed, 0), FEED_BASE + p.getInt(COUNT_KEY + feed, 0));
    }

    private static int after(int top) {
        return top <= ID_CEILING - STEP ? top + STEP : top + 1;
    }

    /**
     * Telegram's message id for a post: given once, kept. Each feed counts from FEED_BASE, so the
     * channel and the group give the same ids to different posts. A post with none yet goes after
     * every post (one that came late gets its place in {@link #idsInOrder} first).
     */
    private static synchronized int msgId(String feed, String postId) {
        SharedPreferences p = prefs();
        int id = p.getInt(ID_KEY + postId, 0);
        if (id == 0) {
            id = after(topId(p, feed));
            p.edit().putInt(TOP_KEY + feed, id).putInt(ID_KEY + postId, id).apply();
        }
        return id;
    }

    /**
     * Ids for new posts in their time order (rows, oldest first): one newer than every post with an
     * id goes after them all; one older than some goes between its neighbours, where they left
     * room, so Telegram shows it in its place and not under posts that came before it.
     */
    private static synchronized void idsInOrder(String feed, List<FeedPost> rows, Set<String> fresh) {
        SharedPreferences p = prefs();
        int[] ids = new int[rows.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = p.getInt(ID_KEY + rows.get(i).getId(), 0);
        }
        int top = topId(p, feed);
        SharedPreferences.Editor e = null;
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] != 0 || !fresh.contains(rows.get(i).getId())) {
                continue;
            }
            int prev = FEED_BASE;
            for (int j = i - 1; j >= 0; j--) {
                if (ids[j] != 0) {
                    prev = ids[j];
                    break;
                }
            }
            int next = 0;
            for (int j = i + 1; j < ids.length; j++) {
                if (ids[j] != 0) {
                    next = ids[j];
                    break;
                }
            }
            int id = next != 0 && next - prev > 1 ? prev + (next - prev) / 2 : after(Math.max(top, prev));
            ids[i] = id;
            top = Math.max(top, id);
            if (e == null) {
                e = p.edit();
            }
            e.putInt(ID_KEY + rows.get(i).getId(), id);
        }
        if (e != null) {
            e.putInt(TOP_KEY + feed, top).apply();
        }
    }

    /**
     * The post behind Telegram's id in this feed. Looked up among the feed's own posts: the old
     * reverse key ("p_" + id) was shared by both feeds, so deleting a post in one of them took
     * down a post id of the other, which the core refused, and nothing went (Ehsan, 25 Oct 2026).
     */
    private static String postOf(ChatNode node, String feed, int msgId) {
        SharedPreferences p = prefs();
        for (FeedPost post : node.feedPosts(feed, HISTORY_MAX)) {
            if (p.getInt(ID_KEY + post.getId(), 0) == msgId) {
                return post.getId();
            }
        }
        return null;
    }

    /** Pin Telegram's message `msgId` of an official chat for everyone, or take its pin off (admins; T65). */
    static void pin(ChatNode node, String feed, int msgId, boolean unpin) {
        String post = postOf(node, feed, msgId);
        if (post == null) {
            throw new IllegalArgumentException("unknown post");
        }
        try {
            node.feedPin(feed, post, unpin);
        } catch (Throwable t) {
            throw new Messenger.Refusal(LocaleController.getString(R.string.GerehOfficialFixed));
        }
    }

    /** Telegram's ids of an official chat's pinned posts, the newest last. */
    static List<Integer> pinnedIds(ChatNode node, String feed) {
        List<Integer> out = new ArrayList<>();
        FeedInfo fi = node.feedInfo(feed);
        if (fi != null) {
            for (String post : fi.getPinned()) {
                int id = prefs().getInt(ID_KEY + post, 0);
                if (id != 0) {
                    out.add(id);
                }
            }
        }
        return out;
    }

    /** The official chat's posts behind Telegram's `ids`, as {@code channels.getMessages} answers. */
    static TLRPC.TL_messages_channelMessages messagesById(ChatNode node, String feed, Set<Integer> ids) {
        List<FeedPost> found = new ArrayList<>();
        TLRPC.TL_messages_channelMessages r = new TLRPC.TL_messages_channelMessages();
        for (FeedPost p : posts(node, feed)) {
            int id = prefs().getInt(ID_KEY + p.getId(), 0);
            if (id != 0 && ids.contains(id)) {
                r.messages.add(toTl(node, feed, p));
                found.add(p);
            }
        }
        r.count = r.messages.size();
        r.pts = pts(feed);
        r.chats.add(chatFor(node, feed));
        r.users.addAll(usersOf(node, found));
        return r;
    }

    /** The next pts of an official chat, for an update another class gives Telegram (a pin, T65). */
    static int nextPts(String feed) {
        return bump(feed);
    }

    /** The post a send answers (Telegram's reply_to in this feed), or null. */
    private static String replyPost(ChatNode node, String feed, TLRPC.InputReplyTo r) {
        int id = Messenger.replyMsgId(r);
        return id > 0 ? postOf(node, feed, id) : null;
    }

    private static synchronized Set<String> shown(String feed) {
        Set<String> s = shownIds.get(feed);
        if (s == null) {
            Set<String> kept = prefs().getStringSet("shown_" + feed, null);
            if (kept != null) {
                s = new HashSet<>(kept);
                shownIds.put(feed, s);
            }
        }
        return s;
    }

    private static synchronized int pts(String feed) {
        return Math.max(1, prefs().getInt("pts_" + feed, 1));
    }

    private static synchronized int bump(String feed) {
        int p = pts(feed) + 1;
        prefs().edit().putInt("pts_" + feed, p).apply();
        return p;
    }

    private static int readMax(String feed) {
        return prefs().getInt(READ_KEY + feed, 0);
    }

    private static void setRead(String feed, int maxId) {
        if (maxId > readMax(feed)) {
            prefs().edit().putInt(READ_KEY + feed, maxId).apply();
        }
    }

    /**
     * Once, after a build whose post ids were in Telegram's ephemeral range (see FEED_BASE): every
     * post gets its new id, oldest first, so ids still follow time; what was read stays read; and
     * Telegram's copies under the old ids go with the next chat list (dropStale).
     */
    private static void renumber(ChatNode node) {
        SharedPreferences p = prefs();
        if (p.getBoolean("ids2", false)) {
            return;
        }
        boolean older = false;
        try {
            for (String feed : new String[]{CHANNEL, GROUP}) {
                if (feed.isEmpty()) {
                    continue;
                }
                older |= p.getInt("n_" + feed, 0) > 0;
                List<FeedPost> rows = new ArrayList<>(node.feedPosts(feed, HISTORY_MAX));
                Collections.sort(rows, (a, b) -> Long.compare(a.getTsMs(), b.getTsMs()));
                int oldRead = p.getInt("read_" + feed, 0);
                int read = 0;
                for (FeedPost row : rows) {
                    int id = msgId(feed, row.getId());
                    int old = p.getInt("m_" + row.getId(), 0);
                    if (old != 0 && old <= oldRead) {
                        read = id;
                    }
                }
                setRead(feed, read);
            }
        } catch (Throwable t) {
            Log.e(TAG, "official posts not renumbered: " + t.getClass().getSimpleName());
            return;
        }
        p.edit().putBoolean("ids2", true).putBoolean("ids2_purge", older).apply();
    }

    // ---- what Telegram shows --------------------------------------------------------------

    /**
     * Whether `sk` is shown as the creator: the first admin of the owner's signed list (version 2
     * on: Ehsan's own identity, ehsan·3599, Ehsan 25 Oct 2026: «حتما این کاربر توی گروه و کانال
     * رسمی سازنده باشه»). The owner key itself is no one's identity, so it cannot be shown.
     */
    private static boolean isCreator(FeedInfo info, String sk) {
        return info != null && !info.getAdmins().isEmpty() && info.getAdmins().get(0).equals(sk);
    }

    /** The member count (see the class comment), rounded to hundreds past a thousand: it is an estimate. */
    /** The member count last shown: the estimate's own small swings never take it back. */
    private static long shownMembers;

    private static int memberCount(ChatNode node) {
        long n;
        try {
            n = node.feedMemberCount();
        } catch (Throwable t) {
            return 0;
        }
        // one by one, not in hundreds (Ehsan, 30 Oct 2026); the estimate is good to about 2%, so a
        // dip within that keeps the number shown, and a real fall shows
        synchronized (GerehFeeds.class) {
            if (n >= shownMembers || n < shownMembers - shownMembers / 50) {
                shownMembers = n;
            }
            n = shownMembers;
        }
        return (int) Math.min(n, Integer.MAX_VALUE);
    }

    static TLRPC.Chat chatFor(ChatNode node, String feed) {
        FeedInfo info = null;
        try {
            info = node.feedInfo(feed);
        } catch (Throwable ignored) {
        }
        TLRPC.TL_channel c = new TLRPC.TL_channel();
        c.id = GerehGroups.chatIdFor(feed);
        c.access_hash = c.id; // not 0, or Telegram never asks for the dialog (see GerehChannels.channelFor)
        c.title = titleOf(info, feed);
        c.photo = GerehPhotos.chatPhotoOf(info != null ? info.getPhoto() : 0, info != null ? info.getPhotoThumb() : null);
        c.date = (int) (System.currentTimeMillis() / 1000);
        c.broadcast = isChannel(feed);
        c.megagroup = !isChannel(feed);
        c.verified = true;
        c.left = false;
        c.creator = isCreator(info, node.getMe().getSk());
        c.participants_count = memberCount(node);
        c.flags |= 1 << 17;
        boolean admin = info != null && info.getAdmin();
        boolean owner = ownerHeld(node, feed);
        if (admin || owner) {
            TLRPC.TL_chatAdminRights r = new TLRPC.TL_chatAdminRights();
            r.post_messages = admin;
            r.delete_messages = admin;
            r.ban_users = admin;
            r.change_info = admin; // name, description and photo (feed.rs Meta and Info)
            r.pin_messages = admin; // an admin pins posts for everyone (feed.rs Pin, T65)
            r.add_admins = owner; // the owner key on this phone names the admins (editAdmin)
            c.admin_rights = r;
            c.flags |= 1 << 14;
        }
        if (!isChannel(feed)) {
            // the group's default rights, always: with none, this Telegram takes sending as barred
            // for everyone (ChatObject.canUserDoAction), so every member read «the admins restricted
            // writing here» although the group lets anyone not banned post (Ehsan, 27 Oct 2026).
            // Members add no one (everyone is in already: no «add member» row), pin nothing and
            // change no name or photo; that is the admins' (feed.rs Meta, Info)
            TLRPC.TL_chatBannedRights d = new TLRPC.TL_chatBannedRights();
            d.invite_users = true;
            d.pin_messages = true;
            d.change_info = true;
            c.default_banned_rights = d;
            c.flags |= 1 << 18;
        }
        if (info != null && !info.getCanPost() && !isChannel(feed)) {
            TLRPC.TL_chatBannedRights b = new TLRPC.TL_chatBannedRights();
            b.send_messages = true;
            b.send_plain = true;
            b.send_media = true;
            long until = info.getBannedUntil();
            if (until > 0) {
                // a ban on this phone (an admin's signed item): Telegram's own "restricted until",
                // not "the admins restricted writing" for everyone (Ehsan, 27 Oct 2026)
                b.until_date = until > System.currentTimeMillis() + 366L * 86_400_000L ? 0 : (int) (until / 1000);
                c.banned_rights = b;
                c.flags |= 1 << 15;
                if (!banLogged) {
                    banLogged = true;
                    Log.i(TAG, "official group: this phone is banned " + (b.until_date == 0 ? "for good" : "until " + b.until_date));
                }
            } else {
                c.default_banned_rights = b;
                c.flags |= 1 << 18;
            }
        }
        return c;
    }

    /**
     * An admin's name for the chat, else the app's own (in the phone's language); then as this app
     * names the chats it shares (GerehHost.setOfficialTitle, ت۷۲). An admin sees the name itself:
     * what they edit and sign is what every app reads.
     */
    private static String titleOf(FeedInfo info, String feed) {
        String own = info != null && !info.getTitle().isEmpty()
                ? info.getTitle()
                : LocaleController.getString(isChannel(feed) ? R.string.GerehOfficialChannel : R.string.GerehOfficialGroup);
        return info != null && info.getAdmin() ? own : GerehHost.officialTitle(isChannel(feed), own);
    }

    private static String aboutOf(FeedInfo info, String feed) {
        if (info != null && !info.getAbout().isEmpty()) {
            return info.getAbout();
        }
        return LocaleController.getString(isChannel(feed) ? R.string.GerehOfficialChannelAbout : R.string.GerehOfficialGroupAbout);
    }

    private static long uidOf(ChatNode node, String sk) {
        if (sk.equals(node.getMe().getSk())) {
            return UserConfig.getInstance(Messenger.account()).getClientUserId();
        }
        long uid = GerehIdentity.userIdFor(sk);
        Messenger.rememberUser(uid, sk);
        return uid;
    }

    /** Whether Telegram's message `msgId` of official chat `channelId` is this phone's own post: only those are edited (T60). */
    static boolean ownPost(long channelId, int msgId) {
        ChatNode node = GerehIdentity.node();
        String feed = feedOf(channelId);
        String post = node != null && feed != null ? postOf(node, feed, msgId) : null;
        if (post == null) {
            return false;
        }
        for (FeedPost p : node.feedPosts(feed, HISTORY_MAX)) {
            if (p.getId().equals(post)) {
                return p.getAuthor().equals(node.getMe().getSk());
            }
        }
        return false;
    }

    /** Whether this phone keeps the owner key of `feed` (GerehAddContact, `mlmchat://o/`). */
    private static boolean ownerHeld(ChatNode node, String feed) {
        try {
            return node.feedOwnerHeld(feed);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * What an admin of an official chat may do, the same for every one of them: the signed list names
     * admins, not their rights (feed.rs). Post (in the channel), delete posts, ban, and the chat's
     * name, description and photo; naming admins is the owner key's (editAdmin). The lists showed
     * only «post», so an admin saw every right of theirs off (Ehsan, 28 Oct 2026).
     */
    private static TLRPC.TL_chatAdminRights adminRights(boolean owner) {
        TLRPC.TL_chatAdminRights r = new TLRPC.TL_chatAdminRights();
        r.post_messages = true;
        r.delete_messages = true;
        r.ban_users = true;
        r.change_info = true;
        r.add_admins = owner;
        return r;
    }

    /** The owner key was just kept here: both official chats are shown again, now with «add admin». */
    static void ownerKeyKept() {
        ChatNode node = GerehIdentity.node();
        if (node == null) {
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            for (String feed : new String[]{CHANNEL, GROUP}) {
                try {
                    onChanged(node, feed);
                } catch (Throwable t) {
                    Log.w(TAG, "official chat not refreshed: " + t.getClass().getSimpleName());
                }
            }
        });
    }

    /** One person as a Telegram user: this phone's own, a post's author by the post, else as usersOfUnknown. */
    private static ArrayList<TLRPC.User> userOf(ChatNode node, String sk, List<FeedPost> posts) {
        if (sk.equals(node.getMe().getSk())) {
            ArrayList<TLRPC.User> me = new ArrayList<>();
            me.add(UserConfig.getInstance(Messenger.account()).getCurrentUser());
            return me;
        }
        for (FeedPost p : posts) {
            if (p.getAuthor().equals(sk)) {
                return usersOf(node, java.util.Collections.singletonList(p));
            }
        }
        return usersOfUnknown(node, sk);
    }

    /**
     * The names of those this phone saw post in the official chats, kept after their posts are gone
     * (deleted, or pushed out by newer ones): an admin with no post left was shown by his key (Ehsan's
     * A50, 28 Oct 2026: «kairo» as «5fc19d8f»). A name comes only from a signed post of that key.
     */
    private static Map<String, String> names;
    private static final int NAMES_MAX = 5000;

    private static synchronized Map<String, String> names() {
        if (names == null) {
            names = new HashMap<>();
            for (Map.Entry<String, ?> e : namesPrefs().getAll().entrySet()) {
                if (e.getValue() instanceof String) {
                    names.put(e.getKey(), (String) e.getValue());
                }
            }
        }
        return names;
    }

    private static synchronized void rememberName(String sk, String name) {
        if (name == null || name.isEmpty() || name.equals(names().get(sk)) || names().size() >= NAMES_MAX && !names().containsKey(sk)) {
            return;
        }
        names().put(sk, name);
        namesPrefs().edit().putString(sk, name).apply();
    }

    private static SharedPreferences namesPrefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("gereh_feed_names", Context.MODE_PRIVATE);
    }

    /** Someone with no post this phone keeps: a contact as Messenger shows them, else by a name seen before, else by their key. */
    private static ArrayList<TLRPC.User> usersOfUnknown(ChatNode node, String sk) {
        ArrayList<TLRPC.User> out = new ArrayList<>();
        try {
            for (org.mlmchat.core.Contact c : node.contacts()) {
                if (c.getSk().equals(sk)) {
                    out.add(Messenger.userFor(c));
                    return out;
                }
            }
        } catch (Throwable ignored) {
        }
        TLRPC.TL_user u = new TLRPC.TL_user();
        u.id = uidOf(node, sk);
        u.access_hash = 0;
        u.flags |= 1;
        String known = names().get(sk);
        if (known != null) {
            // the name of an earlier post of theirs, now gone
            int dot = known.indexOf('·');
            u.first_name = dot > 0 ? known.substring(0, dot) : known;
            u.phone = known;
            u.flags |= 16;
        } else {
            u.first_name = sk.substring(0, Math.min(8, sk.length()));
        }
        u.flags |= 2;
        u.verified = isOfficialAdmin(sk);
        GerehPhotos.dress(u, sk);
        out.add((TLRPC.User) Messenger.roundTripObject(u));
        return out;
    }

    /** The authors of these posts as Telegram users: a contact as Messenger shows them, else from the name in the post. */
    private static ArrayList<TLRPC.User> usersOf(ChatNode node, List<FeedPost> posts) {
        Map<String, org.mlmchat.core.Contact> contacts = new HashMap<>();
        try {
            for (org.mlmchat.core.Contact c : node.contacts()) {
                contacts.put(c.getSk(), c);
            }
        } catch (Throwable ignored) {
        }
        ArrayList<TLRPC.User> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String me = node.getMe().getSk();
        for (FeedPost p : posts) {
            if (!seen.add(p.getAuthor())) {
                continue;
            }
            if (p.getAuthor().equals(me)) {
                out.add(UserConfig.getInstance(Messenger.account()).getCurrentUser());
                continue;
            }
            org.mlmchat.core.Contact c = contacts.get(p.getAuthor());
            if (c != null) {
                out.add(Messenger.userFor(c));
                continue;
            }
            TLRPC.TL_user u = new TLRPC.TL_user();
            u.id = uidOf(node, p.getAuthor());
            u.access_hash = 0;
            String name = p.getName();
            rememberName(p.getAuthor(), name);
            int dot = name.indexOf('·');
            u.flags |= 1;
            u.first_name = dot > 0 ? name.substring(0, dot) : (name.isEmpty() ? "?" : name);
            u.flags |= 2;
            u.phone = name;
            u.flags |= 16;
            u.verified = isOfficialAdmin(p.getAuthor());
            GerehPhotos.dress(u, p.getAuthor());
            out.add((TLRPC.User) Messenger.roundTripObject(u));
        }
        return out;
    }

    private static TLRPC.Message toTl(ChatNode node, String feed, FeedPost p) {
        long chan = GerehGroups.chatIdFor(feed);
        TLRPC.TL_message msg = new TLRPC.TL_message();
        msg.id = msgId(feed, p.getId());
        msg.out = p.getOutgoing();
        TLRPC.TL_peerChannel peer = new TLRPC.TL_peerChannel();
        peer.channel_id = chan;
        msg.peer_id = peer;
        if (isChannel(feed)) {
            msg.post = true;
        } else {
            TLRPC.TL_peerUser from = new TLRPC.TL_peerUser();
            from.user_id = uidOf(node, p.getAuthor());
            msg.from_id = from;
            msg.flags |= 256;
        }
        msg.date = (int) (p.getTsMs() / 1000);
        msg.message = p.getText() != null ? GerehInvites.shown(p.getText()) : "";
        if (p.getMedia() != null) {
            msg.media = GerehMedia.toTl(p.getMedia(), msg.date);
            msg.flags |= 512;
            msg.message = p.getMedia().getCaption();
            GerehMedia.dressAlbum(msg, p.getMedia());
        }
        GerehEntities.dress(msg, p.getMedia() != null ? null : p.getEntities(), p.getMedia() != null ? p.getMedia().getCaption() : p.getText());
        // the post it answers, if this phone shows it (SPEC 18, the envelope's reply_to)
        if (p.getReplyTo() != null) {
            int rid = prefs().getInt(ID_KEY + p.getReplyTo(), 0);
            if (rid != 0) {
                Messenger.setReply(msg, rid);
            }
        }
        msg.dialog_id = -chan;
        if (msg.out) {
            msg.flags |= 2;
        }
        if (p.getEditedMs() > 0) {
            // «edited» beside the time, as for a server's edited message (T60; the text is the new one)
            msg.edit_date = (int) (p.getEditedMs() / 1000);
            msg.flags |= TLRPC.MESSAGE_FLAG_EDITED;
        }
        GerehReactions.dress(msg, p.getReactions(), node.getMe().getSk());
        GerehConfigs.dress(msg); // configs in it: «سنجش» and «انتقال» (n5)
        return msg;
    }

    /** A post's reactions as one string, to tell when they changed. */
    private static String reactionKey(FeedPost p) {
        StringBuilder sb = new StringBuilder();
        for (org.mlmchat.core.Reaction r : p.getReactions()) {
            sb.append(r.getPeer(), 0, 8).append(r.getEmoji()).append(';');
        }
        return sb.toString();
    }

    /**
     * The first line of each official chat: «channel created» / «group created», kept at id
     * FEED_BASE (posts start after it). It is what puts the chat in the list on a phone that had
     * Gereh before this build: Telegram reads its list from its own cache there and never asks for
     * the dialogs again, so the chats arrive as a new message instead ({@link #show}).
     */
    private static TLRPC.Message welcome(String feed) {
        long chan = GerehGroups.chatIdFor(feed);
        TLRPC.TL_messageService msg = new TLRPC.TL_messageService();
        msg.id = FEED_BASE;
        TLRPC.TL_peerChannel peer = new TLRPC.TL_peerChannel();
        peer.channel_id = chan;
        msg.peer_id = peer;
        msg.post = isChannel(feed);
        TLRPC.TL_messageActionChannelCreate a = new TLRPC.TL_messageActionChannelCreate();
        a.title = LocaleController.getString(isChannel(feed) ? R.string.GerehOfficialChannel : R.string.GerehOfficialGroup);
        msg.action = a;
        // not a post: no reactions on it (Telegram would offer them on a service line otherwise)
        msg.reactions_are_possible = false;
        msg.date = welcomeDate();
        msg.dialog_id = -chan;
        return msg;
    }

    private static long syncedAt;

    /**
     * An official chat was opened: what is new comes now, from contacts and from the official
     * relay (core feed_sync_now), at most once a minute. Its posts and count arrive as feed events.
     */
    private static void syncSoon(ChatNode node) {
        long now = System.currentTimeMillis();
        synchronized (GerehFeeds.class) {
            if (now - syncedAt < 60_000) {
                return;
            }
            syncedAt = now;
        }
        Utilities.globalQueue.postRunnable(() -> {
            try {
                node.feedSyncNow();
            } catch (Throwable t) {
                Log.w(TAG, "official sync not asked: " + t.getClass().getSimpleName());
            }
        });
    }

    private static synchronized int welcomeDate() {
        SharedPreferences p = prefs();
        int at = p.getInt("welcome_at", 0);
        if (at == 0) {
            at = (int) (System.currentTimeMillis() / 1000);
            p.edit().putInt("welcome_at", at).apply();
        }
        return at;
    }

    /** Puts the official chats in Telegram's list when they are not there yet (an existing install). */
    static void show(ChatNode node) {
        org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
            MessagesController mc = MessagesController.getInstance(Messenger.account());
            // once (renumber): Telegram's copy of each official chat holds the posts under the old,
            // ephemeral ids; it goes, and the chat comes back as below, its posts from the bridge
            boolean purge = prefs().getBoolean("ids2_purge", false);
            if (purge) {
                prefs().edit().remove("ids2_purge").apply();
            }
            // once: what older builds left where that purge does not reach (dropLeftovers)
            boolean leftovers = !prefs().getBoolean("leftovers1", false);
            if (leftovers) {
                prefs().edit().putBoolean("leftovers1", true).apply();
            }
            for (String feed : new String[]{CHANNEL, GROUP}) {
                long did = -GerehGroups.chatIdFor(feed);
                if (leftovers) {
                    dropLeftovers(Messenger.account(), did);
                }
                if (purge) {
                    Log.i(TAG, "official chat under the old ids cleared");
                    mc.removeDialogLocally(did);
                } else if (mc.dialogs_dict.get(did) != null) {
                    // Telegram keeps its saved copy of the chat until told: this build's, so rights
                    // added since an older build saved its own (an admin's pin, T65) show at once.
                    // Off the main thread: chatFor waits for the core, and a main thread held at
                    // start kept Gereh's service from starting in time (1.2.44, 30 Oct 2026)
                    Utilities.globalQueue.postRunnable(() -> {
                        final TLRPC.Chat fresh = chatFor(node, feed);
                        Utilities.stageQueue.postRunnable(() -> {
                            TL_update.TL_updateChannel uc = new TL_update.TL_updateChannel();
                            uc.channel_id = fresh.id;
                            TLRPC.TL_updates ups = new TLRPC.TL_updates();
                            ups.updates.add(uc);
                            ups.chats.add(fresh);
                            ups.date = (int) (System.currentTimeMillis() / 1000);
                            ups.seq = 0;
                            mc.processUpdates(ups, false);
                        });
                    });
                    continue;
                }
                final TLRPC.Chat chat = chatFor(node, feed);
                final TLRPC.Message msg = welcome(feed);
                setRead(feed, FEED_BASE);
                Utilities.stageQueue.postRunnable(() -> {
                    TL_update.TL_updateNewChannelMessage u = new TL_update.TL_updateNewChannelMessage();
                    u.message = msg;
                    u.pts = bump(feed);
                    u.pts_count = 1;
                    TLRPC.TL_updates ups = new TLRPC.TL_updates();
                    ups.updates.add(u);
                    ups.chats.add(chat);
                    ups.date = (int) (System.currentTimeMillis() / 1000);
                    ups.seq = 0;
                    mc.processUpdates(ups, false);
                });
            }
            // what changed while the app was closed (posts taken down, reactions, the name, the
            // count): Telegram shows its own copy until told
            Utilities.globalQueue.postRunnable(() -> {
                for (String feed : new String[]{CHANNEL, GROUP}) {
                    try {
                        onChanged(node, feed);
                    } catch (Throwable t) {
                        Log.w(TAG, "official chat not refreshed: " + t.getClass().getSimpleName());
                    }
                }
            }, 3000);
        }, 5000);
    }

    /**
     * What older builds left in Telegram's own store of an official chat, out of the bridge's
     * reach: the posts it kept under the ephemeral ids (FEED_BASE) in its table of ephemeral
     * messages, which removeDialogLocally does not empty, so such a post still read «only visible
     * to you» and one taken down came back (Ehsan, 27 Oct 2026); and the «‹admin› added you» lines
     * of the builds before OFFICIAL_SINCE_S. Both go from this phone only.
     */
    private static void dropLeftovers(int acc, long did) {
        MessagesStorage ms = MessagesStorage.getInstance(acc);
        ms.getStorageQueue().postRunnable(() -> {
            ArrayList<Integer> ids = new ArrayList<>();
            SQLiteCursor c = null;
            try {
                c = ms.getDatabase().queryFinalized("SELECT id FROM ephemeral_messages WHERE dialog_id = " + did);
                while (c.next()) {
                    ids.add(MessageObject.ephemeralMessageIdPack(c.intValue(0)));
                }
                c.dispose();
                c = null;
                ms.getDatabase().executeFast("DELETE FROM ephemeral_messages WHERE dialog_id = " + did).stepThis().dispose();
                c = ms.getDatabase().queryFinalized("SELECT mid, data FROM messages_v2 WHERE uid = " + did + " AND mid < 0 AND out = 0");
                while (c.next()) {
                    NativeByteBuffer data = c.byteBufferValue(1);
                    if (data == null) {
                        continue;
                    }
                    TLRPC.Message m = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                    data.reuse();
                    if (m != null && m.action instanceof TLRPC.TL_messageActionChatAddUser) {
                        ids.add(c.intValue(0));
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "official chat leftovers not cleared: " + t.getClass().getSimpleName());
            } finally {
                if (c != null) {
                    c.dispose();
                }
            }
            if (!ids.isEmpty()) {
                Log.i(TAG, "official chat: " + ids.size() + " leftovers of older builds cleared");
                // cacheOnly: nothing is asked of the bridge, the copies on this phone go and the chat redraws
                org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> MessagesController.getInstance(acc)
                        .deleteMessages(ids, null, null, did, 0, false, ChatActivity.MODE_DEFAULT, true));
            }
        });
    }

    private static List<FeedPost> posts(ChatNode node, String feed) {
        List<FeedPost> out = new ArrayList<>();
        for (FeedPost p : node.feedPosts(feed, HISTORY_MAX)) {
            if (p.getText() != null || p.getMedia() != null) {
                out.add(p);
            }
        }
        return out;
    }

    private static TLRPC.TL_dialog dialogFor(ChatNode node, String feed, List<FeedPost> rows, ArrayList<TLRPC.Message> messages) {
        FeedPost top = rows.isEmpty() ? null : rows.get(rows.size() - 1);
        TLRPC.TL_dialog d = new TLRPC.TL_dialog();
        TLRPC.TL_peerChannel pc = new TLRPC.TL_peerChannel();
        pc.channel_id = GerehGroups.chatIdFor(feed);
        d.peer = pc;
        int topId = FEED_BASE; // the welcome line, when there is no post yet
        int read = readMax(feed);
        int unread = 0;
        for (FeedPost p : rows) {
            int id = msgId(feed, p.getId());
            topId = Math.max(topId, id);
            if (!p.getOutgoing() && id > read) {
                unread++;
            }
        }
        d.top_message = topId;
        d.read_inbox_max_id = read;
        d.unread_count = unread;
        d.pts = pts(feed);
        d.flags |= 1;
        d.notify_settings = GerehNotify.of(-pc.channel_id);
        if (messages != null) {
            messages.add(top != null ? toTl(node, feed, top) : welcome(feed));
        }
        return d;
    }

    /**
     * Official chats Telegram still lists from its own copy but that are not this app's any more,
     * out of the list. The core follows only the feeds of the config it was given (install), but the
     * dialogs Telegram saved before stay in its database, and a full list from the bridge never names
     * them, so nothing took them out: Dr.Net's phone, built once with MLMVPN's default config, kept
     * «MLMVPN رسمی» channel and group beside its own after the config changed (27 Oct 2026). An
     * official chat is the one chat the bridge marks verified (dialogFor), so a verified chat that is
     * neither feed of the config now is such a leftover. Removed on this phone only: there is no
     * server to tell, and the core holds nothing of them.
     */
    static void dropStale(int account) {
        org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
            MessagesController mc = MessagesController.getInstance(account);
            ArrayList<Long> stale = new ArrayList<>();
            for (TLRPC.Dialog d : new ArrayList<>(mc.getAllDialogs())) {
                if (d.id >= 0) {
                    continue;
                }
                TLRPC.Chat chat = mc.getChat(-d.id);
                if (chat != null && chat.verified && feedOf(-d.id) == null) {
                    stale.add(d.id);
                }
            }
            for (long did : stale) {
                Log.i(TAG, "official chat no longer in the config, dropped: " + did);
                mc.removeDialogLocally(did);
            }
        });
    }

    /** The two official chats in the dialog list (all of them, or those in `only`). */
    static void addDialogs(ChatNode node, ArrayList<TLRPC.Dialog> dialogs, ArrayList<TLRPC.Message> messages, ArrayList<TLRPC.Chat> chats, ArrayList<TLRPC.User> users, Set<Long> only) {
        Set<Long> seen = new HashSet<>();
        for (TLRPC.User u : users) {
            seen.add(u.id);
        }
        for (String feed : new String[]{CHANNEL, GROUP}) {
            long chat = GerehGroups.chatIdFor(feed);
            if (only != null && !only.contains(chat)) {
                continue;
            }
            try {
                List<FeedPost> rows = posts(node, feed);
                chats.add(chatFor(node, feed));
                dialogs.add(dialogFor(node, feed, rows, messages));
                for (TLRPC.User u : usersOf(node, rows.isEmpty() ? rows : rows.subList(rows.size() - 1, rows.size()))) {
                    if (seen.add(u.id)) {
                        users.add(u);
                    }
                }
                remember(feed, rows);
            } catch (Throwable t) {
                Log.w(TAG, "official dialog: " + t.getClass().getSimpleName());
            }
        }
    }

    private static synchronized void remember(String feed, List<FeedPost> rows) {
        Set<String> s = new HashSet<>();
        Map<String, String> r = new HashMap<>();
        Map<String, Long> e = new HashMap<>();
        for (FeedPost p : rows) {
            s.add(p.getId());
            r.put(p.getId(), reactionKey(p));
            e.put(p.getId(), p.getEditedMs());
        }
        shownIds.put(feed, s);
        shownReactions.put(feed, r);
        shownEdits.put(feed, e);
        prefs().edit().putStringSet("shown_" + feed, new HashSet<>(s)).apply();
    }

    /**
     * A feed changed (Messenger.onEvent, and once after each start): new posts into Telegram,
     * taken-down ones out, changed reactions, and the name, photo and member count.
     */
    static void onChanged(ChatNode node, String feed) {
        if (feedOf(GerehGroups.chatIdFor(feed)) == null) {
            return;
        }
        adminsReadAt = 0; // the admin list may have changed: the blue ticks are read again
        List<FeedPost> rows = posts(node, feed);
        Set<String> before = shown(feed);
        Map<String, String> reactedBefore;
        Map<String, Long> editedBefore;
        synchronized (GerehFeeds.class) {
            reactedBefore = shownReactions.get(feed);
            editedBefore = shownEdits.get(feed);
        }
        Set<String> now = new HashSet<>();
        for (FeedPost p : rows) {
            now.add(p.getId());
        }
        final List<FeedPost> fresh = new ArrayList<>();
        final List<FeedPost> reacted = new ArrayList<>();
        final List<FeedPost> edited = new ArrayList<>();
        final ArrayList<Integer> gone = new ArrayList<>();
        for (FeedPost p : rows) {
            // with no list kept yet (the first start of this build), a post Telegram got once has its id
            boolean known = before != null ? before.contains(p.getId()) : prefs().getInt(ID_KEY + p.getId(), 0) != 0;
            if (!known) {
                fresh.add(p);
                continue;
            }
            if (reactedBefore != null ? !reactionKey(p).equals(reactedBefore.get(p.getId())) : !p.getReactions().isEmpty()) {
                // after a start nothing is known of what Telegram shows: the posts with reactions go again
                reacted.add(p);
            }
            Long was = editedBefore != null ? editedBefore.get(p.getId()) : null;
            if (was != null ? p.getEditedMs() != was : p.getEditedMs() > 0) {
                // an edit by its author on another phone (T60), or, after a start, any edited post again
                edited.add(p);
            }
        }
        // a reply shown before the post it answers was here went without it (Ehsan, 29 Oct 2026: his
        // reply came at once from phone to phone, the post it answered only later from the official
        // copy, and everyone saw a plain message): shown again, now with it. Every new post gets its
        // id first, so a reply among them finds its post whatever their clocks say.
        Set<String> freshIds = new HashSet<>();
        for (FeedPost p : fresh) {
            freshIds.add(p.getId());
        }
        // in their time order: a post that came late goes in its place, not under newer ones
        idsInOrder(feed, rows, freshIds);
        if (!freshIds.isEmpty()) {
            for (FeedPost p : rows) {
                if (p.getReplyTo() != null && freshIds.contains(p.getReplyTo()) && !freshIds.contains(p.getId()) && !edited.contains(p)) {
                    edited.add(p);
                }
            }
        }
        // the oldest post kept now: one below it only aged out of this phone's window (the core keeps
        // the newest posts), nobody took it down, and Telegram keeps showing its copy (Ehsan, 30 Oct
        // 2026: messages seen a minute before, his own too, were gone on coming back to the group)
        int oldestLive = Integer.MAX_VALUE;
        for (String id : now) {
            oldestLive = Math.min(oldestLive, msgId(feed, id));
        }
        if (before != null) {
            for (String id : before) {
                if (!now.contains(id) && msgId(feed, id) > oldestLive) {
                    gone.add(msgId(feed, id));
                }
            }
        }
        if (!prefs().getBoolean("swept_" + feed, false)) {
            // once, on the first start of this build: every id this feed ever gave out (FEED_BASE +
            // 1..n, its own) that is no post now, so posts taken down before it leave Telegram's copy too
            prefs().edit().putBoolean("swept_" + feed, true).apply();
            Set<Integer> live = new HashSet<>();
            for (FeedPost p : rows) {
                live.add(msgId(feed, p.getId()));
            }
            int n = prefs().getInt(COUNT_KEY + feed, 0);
            for (int k = 1; k <= n; k++) {
                if (!live.contains(FEED_BASE + k) && FEED_BASE + k > oldestLive) {
                    gone.add(FEED_BASE + k);
                }
            }
        }
        remember(feed, rows);
        final long chan = GerehGroups.chatIdFor(feed);
        final TLRPC.Chat chat = chatFor(node, feed);
        final ArrayList<TLRPC.User> users = usersOf(node, fresh);
        users.addAll(usersOf(node, edited));
        final ArrayList<TLRPC.Message> msgs = new ArrayList<>();
        for (FeedPost p : fresh) {
            msgs.add(toTl(node, feed, p));
        }
        final ArrayList<TLRPC.Message> edits = new ArrayList<>();
        for (FeedPost p : edited.subList(Math.max(0, edited.size() - 100), edited.size())) {
            edits.add(toTl(node, feed, p));
        }
        final ArrayList<TLRPC.Update> reactions = new ArrayList<>();
        String me = node.getMe().getSk();
        for (FeedPost p : reacted.subList(Math.max(0, reacted.size() - 100), reacted.size())) {
            TL_update.TL_updateMessageReactions u = new TL_update.TL_updateMessageReactions();
            u.peer = GerehReactions.channelPeer(chan);
            u.msg_id = msgId(feed, p.getId());
            TLRPC.TL_messageReactions s = GerehReactions.summary(p.getReactions(), me);
            u.reactions = s != null ? s : new TLRPC.TL_messageReactions();
            reactions.add(u);
        }
        Utilities.stageQueue.postRunnable(() -> {
            TLRPC.TL_updates ups = new TLRPC.TL_updates();
            for (TLRPC.Message m : msgs) {
                TL_update.TL_updateNewChannelMessage u = new TL_update.TL_updateNewChannelMessage();
                u.message = m;
                u.pts = bump(feed);
                u.pts_count = 1;
                ups.updates.add(u);
            }
            if (!gone.isEmpty()) {
                TL_update.TL_updateDeleteChannelMessages d = new TL_update.TL_updateDeleteChannelMessages();
                d.channel_id = chan;
                d.messages = gone;
                d.pts = bump(feed);
                d.pts_count = 1;
                ups.updates.add(d);
            }
            for (TLRPC.Message m : edits) {
                TL_update.TL_updateEditChannelMessage u = new TL_update.TL_updateEditChannelMessage();
                u.message = m;
                u.pts = bump(feed);
                u.pts_count = 1;
                ups.updates.add(u);
            }
            ups.updates.addAll(reactions);
            // the chat itself (name, photo, member count) every time: Telegram then reads its info
            // again, and puts the chat back in the list if it is missing there (MessagesController)
            TL_update.TL_updateChannel uc = new TL_update.TL_updateChannel();
            uc.channel_id = chan;
            ups.updates.add(uc);
            ups.chats.add(chat);
            ups.users.addAll(users);
            ups.date = (int) (System.currentTimeMillis() / 1000);
            ups.seq = 0;
            MessagesController.getInstance(Messenger.account()).processUpdates(ups, false);
        });
    }

    // ---- requests -------------------------------------------------------------------------

    /** True when the request is about an official chat. */
    static boolean handles(TLObject req) {
        String f = null;
        if (req instanceof TLRPC.TL_channels_getFullChannel) {
            f = feedOf(GerehChannels.channelOf(((TLRPC.TL_channels_getFullChannel) req).channel));
        } else if (req instanceof TLRPC.TL_channels_getParticipants) {
            f = feedOf(GerehChannels.channelOf(((TLRPC.TL_channels_getParticipants) req).channel));
        } else if (req instanceof TLRPC.TL_channels_getParticipant) {
            f = feedOf(GerehChannels.channelOf(((TLRPC.TL_channels_getParticipant) req).channel));
        } else if (req instanceof TLRPC.TL_channels_leaveChannel) {
            f = feedOf(GerehChannels.channelOf(((TLRPC.TL_channels_leaveChannel) req).channel));
        } else if (req instanceof TLRPC.TL_channels_readHistory) {
            f = feedOf(GerehChannels.channelOf(((TLRPC.TL_channels_readHistory) req).channel));
        } else if (req instanceof TLRPC.TL_channels_deleteMessages) {
            f = feedOf(GerehChannels.channelOf(((TLRPC.TL_channels_deleteMessages) req).channel));
        } else if (req instanceof TLRPC.TL_channels_editTitle) {
            f = feedOf(GerehChannels.channelOf(((TLRPC.TL_channels_editTitle) req).channel));
        } else if (req instanceof TLRPC.TL_messages_editChatAbout) {
            f = feedOfPeer(((TLRPC.TL_messages_editChatAbout) req).peer);
        } else if (req instanceof TLRPC.TL_channels_editPhoto) {
            f = feedOf(GerehChannels.channelOf(((TLRPC.TL_channels_editPhoto) req).channel));
        } else if (req instanceof TLRPC.TL_channels_editAdmin) {
            f = feedOf(GerehChannels.channelOf(((TLRPC.TL_channels_editAdmin) req).channel));
        } else if (req instanceof TLRPC.TL_channels_editBanned) {
            f = feedOf(GerehChannels.channelOf(((TLRPC.TL_channels_editBanned) req).channel));
        } else if (req instanceof TLRPC.TL_channels_toggleSlowMode) {
            f = feedOf(GerehChannels.channelOf(((TLRPC.TL_channels_toggleSlowMode) req).channel));
        } else if (req instanceof TLRPC.TL_channels_deleteParticipantHistory) {
            f = feedOf(GerehChannels.channelOf(((TLRPC.TL_channels_deleteParticipantHistory) req).channel));
        } else if (req instanceof TLRPC.TL_updates_getChannelDifference) {
            f = feedOf(GerehChannels.channelOf(((TLRPC.TL_updates_getChannelDifference) req).channel));
        } else if (req instanceof TLRPC.TL_messages_getHistory) {
            f = feedOfPeer(((TLRPC.TL_messages_getHistory) req).peer);
        } else if (req instanceof TLRPC.TL_channels_getMessages) {
            f = feedOf(GerehChannels.channelOf(((TLRPC.TL_channels_getMessages) req).channel));
        } else if (req instanceof TLRPC.TL_messages_sendMessage) {
            f = feedOfPeer(((TLRPC.TL_messages_sendMessage) req).peer);
        } else if (req instanceof TLRPC.TL_messages_editMessage) {
            f = feedOfPeer(((TLRPC.TL_messages_editMessage) req).peer);
        } else if (req instanceof TLRPC.TL_messages_sendMedia) {
            f = feedOfPeer(((TLRPC.TL_messages_sendMedia) req).peer);
        } else if (req instanceof TLRPC.TL_channels_getChannels) {
            for (TLRPC.InputChannel ic : ((TLRPC.TL_channels_getChannels) req).id) {
                if (feedOf(GerehChannels.channelOf(ic)) != null) {
                    return true;
                }
            }
        }
        return f != null;
    }

    static TLObject answer(int acc, ChatNode node, TLObject req, RequestDelegate done) throws Exception {
        if (req instanceof TLRPC.TL_channels_getChannels) {
            TLRPC.TL_messages_chats r = new TLRPC.TL_messages_chats();
            for (TLRPC.InputChannel ic : ((TLRPC.TL_channels_getChannels) req).id) {
                String f = feedOf(GerehChannels.channelOf(ic));
                if (f != null) {
                    r.chats.add(chatFor(node, f));
                } else {
                    org.mlmchat.core.Group g = GerehGroups.groupFor(node, GerehChannels.channelOf(ic));
                    if (g != null && g.getChannel()) {
                        r.chats.add(GerehChannels.channelFor(node, g));
                    }
                }
            }
            return r;
        }
        if (req instanceof TLRPC.TL_channels_getFullChannel) {
            String feed = feedOf(GerehChannels.channelOf(((TLRPC.TL_channels_getFullChannel) req).channel));
            TLRPC.TL_channelFull f = new TLRPC.TL_channelFull();
            f.id = GerehGroups.chatIdFor(feed);
            FeedInfo fi = node.feedInfo(feed);
            f.about = aboutOf(fi, feed);
            f.participants_count = memberCount(node);
            // the count travels only with its flag: without it the answer came back with 0 members
            // and Telegram wrote «گروه خصوصی» / «کانال خصوصی» in place of it (Ehsan, 27 Oct 2026)
            f.flags |= 1;
            f.read_inbox_max_id = readMax(feed);
            f.read_outbox_max_id = Integer.MAX_VALUE;
            f.available_reactions = GerehReactions.allowed();
            f.flags |= 1 << 30;
            f.chat_photo = fi != null && fi.getPhoto() != 0 ? GerehPhotos.photo(fi.getPhoto(), GerehIdentity.hex(fi.getPhotoThumb())) : new TLRPC.TL_photoEmpty();
            f.notify_settings = GerehNotify.of(-f.id);
            List<Integer> pins = pinnedIds(node, feed);
            if (!pins.isEmpty()) {
                f.pinned_msg_id = pins.get(pins.size() - 1);
                f.flags |= 32;
            }
            if (fi != null && fi.getSlowSeconds() > 0) {
                // slow mode (T67): Telegram's own timer on the send button, and its row for admins
                f.slowmode_seconds = fi.getSlowSeconds();
                f.flags |= 1 << 17;
                if (fi.getSlowNextMs() > 0) {
                    f.slowmode_next_send_date = (int) (fi.getSlowNextMs() / 1000);
                    f.flags |= 1 << 18;
                }
            }
            f.pts = pts(feed);
            // the members this phone knows are shown (getParticipants): in the group to everyone, in
            // the channel to its admins, as Telegram shows a channel's subscribers (Ehsan, 28 Oct
            // 2026, after the list was hidden on 25 Oct)
            f.can_view_participants = !isChannel(feed) || fi != null && fi.getAdmin() || ownerHeld(node, feed);
            f.participants_hidden = false;
            TLRPC.TL_messages_chatFull r = new TLRPC.TL_messages_chatFull();
            r.full_chat = f;
            r.chats.add(chatFor(node, feed));
            return r;
        }
        if (req instanceof TLRPC.TL_channels_getParticipants) {
            // no member list behind the count: the admins, who are public, then the members this
            // phone knows (below); the admins' list of bans apart
            TLRPC.TL_channels_getParticipants q = (TLRPC.TL_channels_getParticipants) req;
            String feed = feedOf(GerehChannels.channelOf(q.channel));
            TLRPC.TL_channels_channelParticipants r = new TLRPC.TL_channels_channelParticipants();
            FeedInfo info = node.feedInfo(feed);
            boolean bans = q.filter instanceof TLRPC.TL_channelParticipantsKicked || q.filter instanceof TLRPC.TL_channelParticipantsBanned;
            if (bans) {
                // the admins' "removed users": every ban standing now, which an admin may lift
                // (editBanned with no rights, feedBan until 0)
                if (info != null && info.getAdmin() && q.offset == 0) {
                    List<FeedPost> posts = posts(node, feed);
                    for (Map.Entry<String, Long> e : info.getBanned().entrySet()) {
                        TLRPC.TL_channelParticipantBanned p = new TLRPC.TL_channelParticipantBanned();
                        TLRPC.TL_peerUser pu = new TLRPC.TL_peerUser();
                        pu.user_id = uidOf(node, e.getKey());
                        p.peer = pu;
                        p.banned_rights = new TLRPC.TL_chatBannedRights();
                        p.banned_rights.view_messages = q.filter instanceof TLRPC.TL_channelParticipantsKicked;
                        p.banned_rights.send_messages = true;
                        p.banned_rights.until_date = e.getValue() > System.currentTimeMillis() + 366L * 86_400_000L ? 0 : (int) (e.getValue() / 1000);
                        p.date = OFFICIAL_SINCE_S;
                        r.participants.add(p);
                        FeedPost by = null;
                        for (FeedPost fp : posts) {
                            if (fp.getAuthor().equals(e.getKey())) {
                                by = fp;
                            }
                        }
                        r.users.addAll(by != null ? usersOf(node, java.util.Collections.singletonList(by)) : usersOfUnknown(node, e.getKey()));
                    }
                }
                r.count = r.participants.size();
                return r;
            }
            if (info != null && q.offset == 0) {
                List<FeedPost> posts = posts(node, feed);
                for (String sk : info.getAdmins()) {
                    TLRPC.ChannelParticipant a = isCreator(info, sk) ? new TLRPC.TL_channelParticipantCreator() : new TLRPC.TL_channelParticipantAdmin();
                    a.admin_rights = adminRights(false);
                    TLRPC.TL_peerUser pu = new TLRPC.TL_peerUser();
                    pu.user_id = uidOf(node, sk);
                    a.peer = pu;
                    a.date = (int) (System.currentTimeMillis() / 1000);
                    r.participants.add(a);
                    r.users.addAll(userOf(node, sk, posts));
                }
                if (!(q.filter instanceof TLRPC.TL_channelParticipantsAdmins)) {
                    // the members this phone knows (Ehsan, 28 Oct 2026: «مشاهده کاربران گروه و کانال،
                    // برای رسمی ها هم»): every Gereh user is in both chats and there is no list behind
                    // the count, so after the admins: whoever posted here, then its contacts (all of
                    // them members). The owner phone picks new admins from the same list
                    String search = q.filter != null && q.filter.q != null ? q.filter.q.trim().toLowerCase() : "";
                    boolean contactsOnly = q.filter instanceof TLRPC.TL_channelParticipantsContacts;
                    Set<String> listed = new HashSet<>(info.getAdmins());
                    List<String> candidates = new ArrayList<>();
                    if (!contactsOnly) {
                        for (int i = posts.size() - 1; i >= 0; i--) {
                            candidates.add(posts.get(i).getAuthor()); // the newest posters first
                        }
                    }
                    for (org.mlmchat.core.Contact c : node.contacts()) {
                        candidates.add(c.getSk());
                    }
                    for (String sk : candidates) {
                        if (sk.equals(node.getMe().getSk()) || !listed.add(sk)) {
                            continue;
                        }
                        ArrayList<TLRPC.User> us = userOf(node, sk, posts);
                        TLRPC.User u = us.get(0);
                        String name = ((u.first_name != null ? u.first_name : "") + " " + (u.last_name != null ? u.last_name : "")).toLowerCase();
                        if (!search.isEmpty() && !name.contains(search)) {
                            continue;
                        }
                        TLRPC.TL_channelParticipant p = new TLRPC.TL_channelParticipant();
                        TLRPC.TL_peerUser pu = new TLRPC.TL_peerUser();
                        pu.user_id = uidOf(node, sk);
                        p.peer = pu;
                        p.date = OFFICIAL_SINCE_S;
                        r.participants.add(p);
                        r.users.addAll(us);
                    }
                }
            }
            r.count = r.participants.size();
            return r;
        }
        if (req instanceof TLRPC.TL_channels_getParticipant) {
            String feed = feedOf(GerehChannels.channelOf(((TLRPC.TL_channels_getParticipant) req).channel));
            FeedInfo info = node.feedInfo(feed);
            TLRPC.TL_channels_channelParticipant r = new TLRPC.TL_channels_channelParticipant();
            TLRPC.ChannelParticipant p;
            if (info != null && info.getAdmin()) {
                TLRPC.ChannelParticipant a = isCreator(info, node.getMe().getSk()) ? new TLRPC.TL_channelParticipantCreator() : new TLRPC.TL_channelParticipantAdmin();
                a.admin_rights = adminRights(ownerHeld(node, feed));
                p = a;
            } else {
                p = new TLRPC.TL_channelParticipantSelf();
            }
            TLRPC.TL_peerUser pu = new TLRPC.TL_peerUser();
            pu.user_id = UserConfig.getInstance(acc).getClientUserId();
            p.peer = pu;
            // a member since the official chats began: for a membership under a day old Telegram writes
            // «… added you to this channel» (MessagesController.checkChatInviter), and the official
            // chats show no join lines (SPEC 18); every new user saw one (Ehsan, 27 Oct 2026)
            p.date = OFFICIAL_SINCE_S;
            r.participant = p;
            r.users.add(UserConfig.getInstance(acc).getCurrentUser());
            return r;
        }
        if (req instanceof TLRPC.TL_channels_leaveChannel) {
            throw new Messenger.Refusal(LocaleController.getString(R.string.GerehOfficialNoLeave));
        }
        if (req instanceof TLRPC.TL_channels_editTitle || req instanceof TLRPC.TL_messages_editChatAbout) {
            // the name and description: an admin's signed item every phone keeps (feed.rs Meta). It was
            // refused before, and Telegram drops that error without a word (Ehsan, 25 Oct 2026:
            // «اسمشون عوض میکنم عوض نمیشه»)
            boolean title = req instanceof TLRPC.TL_channels_editTitle;
            String feed = title ? feedOf(GerehChannels.channelOf(((TLRPC.TL_channels_editTitle) req).channel))
                    : feedOfPeer(((TLRPC.TL_messages_editChatAbout) req).peer);
            FeedInfo info = node.feedInfo(feed);
            if (info == null || !info.getAdmin()) {
                throw new Messenger.Refusal(LocaleController.getString(R.string.GerehOfficialFixed));
            }
            String newTitle = title ? ((TLRPC.TL_channels_editTitle) req).title : info.getTitle();
            String newAbout = title ? info.getAbout() : ((TLRPC.TL_messages_editChatAbout) req).about;
            try {
                node.feedSetMeta(feed, newTitle != null ? newTitle : "", newAbout != null ? newAbout : "");
            } catch (Throwable t) {
                throw new Messenger.Refusal(LocaleController.getString(R.string.GerehOfficialFixed));
            }
            if (!title) {
                return new TLRPC.TL_boolTrue();
            }
            TLRPC.TL_updates ups = new TLRPC.TL_updates();
            ups.chats.add(chatFor(node, feed));
            ups.date = (int) (System.currentTimeMillis() / 1000);
            ups.seq = 0;
            return ups;
        }
        if (req instanceof TLRPC.TL_messages_editMessage) {
            // an official post's new text or caption, by its own author (T60, feed.rs Edit; Ehsan,
            // 27 Oct 2026). Another's post is refused by the core; a new photo is no edit Gereh carries
            TLRPC.TL_messages_editMessage q = (TLRPC.TL_messages_editMessage) req;
            String feed = feedOfPeer(q.peer);
            String post = postOf(node, feed, q.id);
            if (post == null || q.media != null || q.message == null) {
                throw new IllegalArgumentException("only the text of one's own post is edited");
            }
            node.feedEdit(feed, post, q.message, GerehEntities.toCore(q.entities));
            TLRPC.TL_updates ups = new TLRPC.TL_updates();
            for (FeedPost p : posts(node, feed)) {
                if (p.getId().equals(post)) {
                    TL_update.TL_updateEditChannelMessage u = new TL_update.TL_updateEditChannelMessage();
                    u.message = toTl(node, feed, p);
                    u.pts = bump(feed);
                    u.pts_count = 1;
                    ups.updates.add(u);
                    ups.users.addAll(usersOf(node, java.util.Collections.singletonList(p)));
                    synchronized (GerehFeeds.class) {
                        Map<String, Long> e = shownEdits.get(feed);
                        if (e != null) {
                            e.put(post, p.getEditedMs()); // shown already: onChanged sends it no second time
                        }
                    }
                }
            }
            ups.chats.add(chatFor(node, feed));
            ups.date = (int) (System.currentTimeMillis() / 1000);
            ups.seq = 0;
            return ups;
        }
        if (req instanceof TLRPC.TL_channels_editAdmin) {
            // the official chats' admins: a new list signed with the owner key, on the one phone
            // that keeps it (Ehsan, 27 Oct 2026); everywhere else they stay as the owner signed them
            TLRPC.TL_channels_editAdmin q = (TLRPC.TL_channels_editAdmin) req;
            String feed = feedOf(GerehChannels.channelOf(q.channel));
            FeedInfo info = node.feedInfo(feed);
            if (info == null || !ownerHeld(node, feed)) {
                // the owner's own phone without the key is shown «add admin» (Telegram's creator):
                // say how to bring the key, not only that it is not here (Ehsan, 28 Oct 2026, p4)
                throw new Messenger.Refusal(LocaleController.getString(info != null && isCreator(info, node.getMe().getSk())
                        ? R.string.GerehOfficialNeedsOwnerKey : R.string.GerehOfficialFixed));
            }
            long uid = q.user_id instanceof TLRPC.TL_inputUser ? ((TLRPC.TL_inputUser) q.user_id).user_id
                    : q.user_id instanceof TLRPC.TL_inputUserSelf ? UserConfig.getInstance(acc).getClientUserId() : 0;
            String sk = uid == UserConfig.getInstance(acc).getClientUserId() ? node.getMe().getSk() : Messenger.skForUser(uid);
            if (sk == null) {
                throw new IllegalArgumentException("unknown user");
            }
            TLRPC.TL_chatAdminRights ar = q.admin_rights;
            boolean admin = ar != null && (ar.post_messages || ar.edit_messages || ar.delete_messages || ar.ban_users
                    || ar.change_info || ar.invite_users || ar.pin_messages || ar.add_admins || ar.manage_call);
            List<String> admins = new ArrayList<>(info.getAdmins());
            if (admin && !admins.contains(sk)) {
                admins.add(sk); // after the others: the first stays the one shown as creator
            } else if (!admin) {
                admins.remove(sk);
            }
            if (admins.isEmpty()) {
                throw new Messenger.Refusal(LocaleController.getString(R.string.GerehOfficialLastAdmin));
            }
            node.feedSetAdmins(feed, admins);
            adminsReadAt = 0; // the blue ticks follow
            TLRPC.TL_updates ups = new TLRPC.TL_updates();
            ups.chats.add(chatFor(node, feed));
            ups.users.addAll(userOf(node, sk, posts(node, feed)));
            ups.date = (int) (System.currentTimeMillis() / 1000);
            return ups;
        }
        if (req instanceof TLRPC.TL_channels_editPhoto) {
            // the official chat's photo: an admin sets it, a signed item every phone keeps (feed.rs
            // Info). It had no way in before and was refused (Ehsan, 25 Oct 2026)
            TLRPC.TL_channels_editPhoto q = (TLRPC.TL_channels_editPhoto) req;
            String feed = feedOf(GerehChannels.channelOf(q.channel));
            FeedInfo info = node.feedInfo(feed);
            if (info == null || !info.getAdmin()) {
                throw new Messenger.Refusal(LocaleController.getString(R.string.GerehOfficialFixed));
            }
            if (q.photo instanceof TLRPC.TL_inputChatPhotoEmpty) {
                node.feedSetPhoto(feed, null, "");
            } else if (q.photo instanceof TLRPC.TL_inputChatUploadedPhoto && ((TLRPC.TL_inputChatUploadedPhoto) q.photo).file != null) {
                File file = GerehMedia.assemble(((TLRPC.TL_inputChatUploadedPhoto) q.photo).file);
                try {
                    node.feedSetPhoto(feed, file.getAbsolutePath(), GerehMedia.hex(GerehMedia.thumbOf(file)));
                } finally {
                    file.delete();
                }
            } else {
                throw new Messenger.Refusal(LocaleController.getString(R.string.GerehPhotoOnly));
            }
            TLRPC.TL_updates ups = new TLRPC.TL_updates();
            ups.chats.add(chatFor(node, feed));
            ups.date = (int) (System.currentTimeMillis() / 1000);
            ups.seq = 0;
            return ups;
        }
        if (req instanceof TLRPC.TL_channels_readHistory) {
            TLRPC.TL_channels_readHistory q = (TLRPC.TL_channels_readHistory) req;
            setRead(feedOf(GerehChannels.channelOf(q.channel)), q.max_id);
            return new TLRPC.TL_boolTrue();
        }
        if (req instanceof TLRPC.TL_channels_deleteMessages) {
            TLRPC.TL_channels_deleteMessages q = (TLRPC.TL_channels_deleteMessages) req;
            String feed = feedOf(GerehChannels.channelOf(q.channel));
            for (Integer id : q.id) {
                String post = postOf(node, feed, id);
                if (post != null) {
                    try {
                        node.feedDelete(feed, post);
                    } catch (Throwable t) {
                        throw new Messenger.Refusal(LocaleController.getString(R.string.GerehOfficialNoDelete));
                    }
                }
            }
            TLRPC.TL_messages_affectedMessages r = new TLRPC.TL_messages_affectedMessages();
            r.pts = bump(feed);
            r.pts_count = 1;
            return r;
        }
        if (req instanceof TLRPC.TL_channels_deleteParticipantHistory) {
            // an admin's «delete all from this person» (the delete dialog's box): each of their posts
            TLRPC.TL_channels_deleteParticipantHistory q = (TLRPC.TL_channels_deleteParticipantHistory) req;
            String feed = feedOf(GerehChannels.channelOf(q.channel));
            FeedInfo info = node.feedInfo(feed);
            String sk = q.participant instanceof TLRPC.TL_inputPeerUser ? Messenger.skForUser(((TLRPC.TL_inputPeerUser) q.participant).user_id) : null;
            if (info == null || !info.getAdmin() || sk == null) {
                throw new Messenger.Refusal(LocaleController.getString(R.string.GerehOfficialNoDelete));
            }
            for (FeedPost p : node.feedPosts(feed, HISTORY_MAX)) {
                if (p.getAuthor().equals(sk)) {
                    try {
                        node.feedDelete(feed, p.getId());
                    } catch (Throwable t) {
                        Log.w(TAG, "official post not taken down: " + t.getClass().getSimpleName());
                    }
                }
            }
            TLRPC.TL_messages_affectedHistory r = new TLRPC.TL_messages_affectedHistory();
            r.pts = bump(feed);
            r.pts_count = 1;
            r.offset = 0;
            return r;
        }
        if (req instanceof TLRPC.TL_channels_toggleSlowMode) {
            // slow mode in the official group (T67): an admin's signed item, kept by every phone
            TLRPC.TL_channels_toggleSlowMode q = (TLRPC.TL_channels_toggleSlowMode) req;
            String feed = feedOf(GerehChannels.channelOf(q.channel));
            node.feedSetSlow(feed, Math.max(0, q.seconds));
            TLRPC.TL_updates ups = new TLRPC.TL_updates();
            ups.chats.add(chatFor(node, feed));
            ups.date = (int) (System.currentTimeMillis() / 1000);
            return ups;
        }
        if (req instanceof TLRPC.TL_channels_editBanned) {
            TLRPC.TL_channels_editBanned q = (TLRPC.TL_channels_editBanned) req;
            String feed = feedOf(GerehChannels.channelOf(q.channel));
            long uid = q.participant instanceof TLRPC.TL_inputPeerUser ? ((TLRPC.TL_inputPeerUser) q.participant).user_id : 0;
            String sk = Messenger.skForUser(uid);
            if (sk == null) {
                throw new IllegalArgumentException("unknown user");
            }
            boolean ban = q.banned_rights != null && (q.banned_rights.view_messages || q.banned_rights.send_messages);
            long until = !ban ? 0 : q.banned_rights.until_date > 0 ? q.banned_rights.until_date * 1000L : Long.MAX_VALUE / 2;
            node.feedBan(feed, sk, until);
            TLRPC.TL_updates ups = new TLRPC.TL_updates();
            ups.chats.add(chatFor(node, feed));
            ups.date = (int) (System.currentTimeMillis() / 1000);
            return ups;
        }
        if (req instanceof TLRPC.TL_updates_getChannelDifference) {
            TLRPC.TL_updates_getChannelDifference q = (TLRPC.TL_updates_getChannelDifference) req;
            String feed = feedOf(GerehChannels.channelOf(q.channel));
            // Telegram asks this while the chat is on the screen, again every `timeout` seconds of
            // the answer: the core reads the official copy every ten seconds meanwhile, and what is
            // new comes as feed events (Ehsan, 29 Oct 2026: posts came tens of minutes late)
            node.feedWatch();
            int now = pts(feed);
            if (q.pts >= now) {
                TLRPC.TL_updates_channelDifferenceEmpty e = new TLRPC.TL_updates_channelDifferenceEmpty();
                e.pts = now;
                e.isFinal = true;
                e.flags |= 2;
                e.timeout = WATCH_POLL_S;
                return e;
            }
            List<FeedPost> rows = posts(node, feed);
            TLRPC.TL_updates_channelDifferenceTooLong t = new TLRPC.TL_updates_channelDifferenceTooLong();
            ArrayList<TLRPC.Message> top = new ArrayList<>();
            t.dialog = dialogFor(node, feed, rows, top);
            List<FeedPost> last = rows.subList(Math.max(0, rows.size() - 50), rows.size());
            for (FeedPost p : last) {
                t.messages.add(toTl(node, feed, p));
            }
            Collections.reverse(t.messages);
            t.chats.add(chatFor(node, feed));
            t.users.addAll(usersOf(node, last));
            t.isFinal = true;
            t.flags |= 2;
            t.timeout = WATCH_POLL_S;
            return t;
        }
        if (req instanceof TLRPC.TL_channels_getMessages) {
            // posts by their ids here: Telegram asks for the post a reply answers when it does not
            // have it at hand, and the reply showed without it while this went unanswered
            TLRPC.TL_channels_getMessages q = (TLRPC.TL_channels_getMessages) req;
            return messagesById(node, feedOf(GerehChannels.channelOf(q.channel)), new HashSet<>(q.id));
        }
        if (req instanceof TLRPC.TL_messages_getHistory) {
            TLRPC.TL_messages_getHistory q = (TLRPC.TL_messages_getHistory) req;
            String feed = feedOfPeer(q.peer);
            syncSoon(node);
            List<FeedPost> rows = posts(node, feed);
            remember(feed, rows);
            List<TLRPC.Message> all = new ArrayList<>();
            for (FeedPost p : rows) {
                all.add(toTl(node, feed, p));
            }
            all.add(welcome(feed));
            Collections.sort(all, (x, y) -> Integer.compare(y.id, x.id));
            TLRPC.TL_messages_channelMessages r = new TLRPC.TL_messages_channelMessages();
            r.messages.addAll(Messenger.pageOf(all, q.offset_id, q.add_offset, q.limit, q.max_id, q.min_id));
            r.count = all.size();
            r.pts = pts(feed);
            r.chats.add(chatFor(node, feed));
            r.users.addAll(usersOf(node, rows));
            return r;
        }
        if (req instanceof TLRPC.TL_messages_sendMessage) {
            TLRPC.TL_messages_sendMessage q = (TLRPC.TL_messages_sendMessage) req;
            String feed = feedOfPeer(q.peer);
            checkRate(node, feed);
            String id;
            try {
                id = node.feedSendText(feed, q.message, replyPost(node, feed, q.reply_to), GerehEntities.toCore(q.entities));
            } catch (Throwable t) {
                throw new Messenger.Refusal(LocaleController.getString(R.string.GerehGroupCannotPost));
            }
            return sent(node, feed, id, q.random_id, done);
        }
        if (req instanceof TLRPC.TL_messages_sendMedia) {
            TLRPC.TL_messages_sendMedia q = (TLRPC.TL_messages_sendMedia) req;
            String feed = feedOfPeer(q.peer);
            if (q.media == null || q.media.file == null) {
                throw new IllegalArgumentException("only uploaded files are sent");
            }
            checkRate(node, feed);
            File file = GerehMedia.assemble(q.media.file);
            try {
                String id;
                try {
                    id = node.feedSendMedia(feed, file.getAbsolutePath(), GerehMedia.infoOf(q.media, file, q.message), replyPost(node, feed, q.reply_to));
                } catch (Throwable t) {
                    throw new Messenger.Refusal(LocaleController.getString(R.string.GerehGroupCannotPost));
                }
                return sent(node, feed, id, q.random_id, done);
            } finally {
                file.delete();
            }
        }
        throw new IllegalArgumentException("not an official chat request");
    }

    /**
     * In the official group a member who is not an admin posts at most 10 times an hour (core
     * `feed::GROUP_RATE`); one more was kept but never counted, so Telegram showed it sending
     * forever. Now it is refused with the reason.
     */
    private static void checkRate(ChatNode node, String feed) {
        FeedInfo info = node.feedInfo(feed);
        if (isChannel(feed) || info == null || info.getAdmin()) {
            return;
        }
        long hourAgo = System.currentTimeMillis() - 3_600_000L;
        int mine = 0;
        for (FeedPost p : node.feedPosts(feed, HISTORY_MAX)) {
            if (p.getOutgoing() && p.getTsMs() > hourAgo) {
                mine++;
            }
        }
        if (mine >= 10) {
            throw new Messenger.Refusal(LocaleController.getString(R.string.GerehOfficialRate));
        }
    }

    /** Our reaction to a post (GerehReactions); the post's reactions as they stand now. */
    static TLRPC.TL_messageReactions react(ChatNode node, String feed, int msgId, String emoji) {
        String post = postOf(node, feed, msgId);
        if (post == null) {
            throw new IllegalArgumentException("unknown post");
        }
        try {
            node.feedReact(feed, post, emoji);
        } catch (Throwable t) {
            throw new Messenger.Refusal(LocaleController.getString(R.string.GerehGroupCannotPost));
        }
        for (FeedPost p : node.feedPosts(feed, HISTORY_MAX)) {
            if (p.getId().equals(post)) {
                synchronized (GerehFeeds.class) {
                    Map<String, String> r = shownReactions.get(feed);
                    if (r != null) {
                        r.put(post, reactionKey(p)); // the answer carries it: no second update
                    }
                }
                return GerehReactions.summary(p.getReactions(), node.getMe().getSk());
            }
        }
        return null;
    }

    /** The answer to our own post: its id for Telegram's copy, and the post with the feed's next pts. */
    private static TLObject sent(ChatNode node, String feed, String postId, long randomId, RequestDelegate done) {
        return sentAll(node, feed, java.util.Collections.singletonList(postId), java.util.Collections.singletonList(randomId), done);
    }

    /** The answer to posts just made, one or several sent together (`messages.sendMultiMedia`). */
    static TLObject sentAll(ChatNode node, String feed, List<String> postIds, List<Long> randomIds, RequestDelegate done) {
        List<FeedPost> recent = node.feedPosts(feed, Math.max(20, postIds.size() + 10));
        final List<FeedPost> posts = new ArrayList<>();
        final List<Long> randoms = new ArrayList<>();
        for (int i = 0; i < postIds.size(); i++) {
            for (FeedPost p : recent) {
                if (p.getId().equals(postIds.get(i))) {
                    posts.add(p);
                    randoms.add(randomIds.get(i));
                    break;
                }
            }
        }
        synchronized (GerehFeeds.class) {
            Set<String> s = shownIds.get(feed);
            if (s != null) {
                for (FeedPost p : posts) {
                    s.add(p.getId());
                }
            }
        }
        Utilities.stageQueue.postRunnable(() -> {
            TLRPC.TL_updates ups = new TLRPC.TL_updates();
            for (int i = 0; i < posts.size(); i++) {
                FeedPost post = posts.get(i);
                TL_update.TL_updateMessageID mid = new TL_update.TL_updateMessageID();
                mid.id = msgId(feed, post.getId());
                mid.random_id = randoms.get(i);
                ups.updates.add(mid);
                TL_update.TL_updateNewChannelMessage u = new TL_update.TL_updateNewChannelMessage();
                u.message = toTl(node, feed, post);
                u.pts = bump(feed);
                u.pts_count = 1;
                ups.updates.add(u);
            }
            ups.chats.add(chatFor(node, feed));
            ups.date = (int) (System.currentTimeMillis() / 1000);
            ups.seq = 0;
            done.run(ups, null);
        });
        return null;
    }
}
