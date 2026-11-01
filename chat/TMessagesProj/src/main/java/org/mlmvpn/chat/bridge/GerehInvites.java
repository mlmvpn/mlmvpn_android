package org.mlmvpn.chat.bridge;

import android.util.Log;
import android.widget.Toast;

import org.mlmchat.core.ChatNode;
import org.mlmchat.core.Group;
import org.mlmchat.core.Invite;
import org.mlmchat.core.InviteInfo;
import org.mlmchat.core.JoinRequest;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MLMCHAT: invite links for groups and channels (SPEC 7.4; step 6.3 of the work queue).
 *
 * A Gereh link is `mlmchat://g/…`; inside Telegram's screens it is shown as `tg://join?invite=…`
 * with the same payload, so a tap on it in any chat opens Telegram's own join dialog. Joining
 * sends a request to the inviter's phone (the core): a link without approval adds us as soon as
 * that phone is reached, one with approval waits for an admin (`messages.hideChatJoinRequest`).
 * Telegram's invite-link screens (make, list, revoke, requests) are answered from the core.
 */
final class GerehInvites {
    private static final String TAG = "MLMCHAT";
    static final String TG_PREFIX = "tg://join?invite=";
    private static final String CORE_PREFIX = "mlmchat://g/";
    /** How long Telegram's "Join" waits for the inviter's phone before saying the request is on its way. */
    private static final long JOIN_WAIT_MS = 20_000;
    /** Telegram's join dialogs waiting to become a member, by group id. */
    private static final Map<String, List<Runnable>> joinWaiters = new ConcurrentHashMap<>();

    private GerehInvites() {
    }

    static boolean handles(TLObject req) {
        return req instanceof TLRPC.TL_messages_exportChatInvite
                || req instanceof TLRPC.TL_messages_getExportedChatInvites
                || req instanceof TLRPC.TL_messages_getExportedChatInvite
                || req instanceof TLRPC.TL_messages_getAdminsWithInvites
                || req instanceof TLRPC.TL_messages_editExportedChatInvite
                || req instanceof TLRPC.TL_messages_deleteExportedChatInvite
                || req instanceof TLRPC.TL_messages_deleteRevokedExportedChatInvites
                || req instanceof TLRPC.TL_messages_getChatInviteImporters
                || req instanceof TLRPC.TL_messages_hideChatJoinRequest
                || req instanceof TLRPC.TL_messages_hideAllChatJoinRequests
                || req instanceof TLRPC.TL_messages_checkChatInvite
                || req instanceof TLRPC.TL_messages_importChatInvite;
    }

    // ---- links ----------------------------------------------------------------------------

    /** The link as Telegram shows and shares it. */
    static String tgLink(String coreLink) {
        return coreLink.startsWith(CORE_PREFIX) ? TG_PREFIX + coreLink.substring(CORE_PREFIX.length()) : coreLink;
    }

    /**
     * A message's text with its invite links as Telegram can open them. A link made on a computer
     * or pasted by hand is `mlmchat://g/…`, a scheme Telegram does not turn into a link, so it came
     * as dead text with no way to join (24 Oct 2026, A50 test).
     */
    static String shown(String text) {
        text = GerehGas.shown(text); // a Google relay link (SPEC 19) opens the add screen the same way
        return text == null || !text.contains(CORE_PREFIX) ? text : text.replace(CORE_PREFIX, TG_PREFIX);
    }

    /** The payload of a link in any of its forms (the core takes it bare or with its prefix). */
    private static String payload(String link) {
        if (link == null) {
            return "";
        }
        int i = link.indexOf("invite=");
        if (i >= 0) {
            return link.substring(i + "invite=".length());
        }
        if (link.startsWith(CORE_PREFIX)) {
            return link.substring(CORE_PREFIX.length());
        }
        int slash = link.lastIndexOf('/');
        return slash >= 0 && link.contains("joinchat") ? link.substring(slash + 1) : link;
    }

    /** When the official-chats note was last shown: the links screen asks several things at once. */
    private static volatile long officialNoteAt;

    private static Group groupOf(ChatNode node, TLRPC.InputPeer peer) {
        long chat = GerehGroups.chatOf(peer);
        if (GerehFeeds.feedOf(chat) != null || GerehFeeds.feedOf(GerehChannels.channelOf(peer)) != null) {
            // The official chats have no links: everyone with the app is in them (SPEC 18). Their
            // links screen stayed empty and «copy» did nothing (Dr.Net's owner, 26 Oct 2026).
            String text = LocaleController.getString(R.string.GerehOfficialNoInvite);
            long now = System.currentTimeMillis();
            if (now - officialNoteAt > 3_000) {
                officialNoteAt = now;
                AndroidUtilities.runOnUIThread(() -> Toast.makeText(ApplicationLoader.applicationContext, text, Toast.LENGTH_LONG).show());
            }
            throw new Messenger.Refusal(text);
        }
        Group g = chat != 0 ? GerehGroups.groupFor(node, chat) : null;
        if (g == null) {
            throw new IllegalArgumentException("unknown group");
        }
        return g;
    }

    private static long uidOf(String sk) {
        ChatNode node = GerehIdentity.node();
        if (node != null && sk.equals(node.getMe().getSk())) {
            return UserConfig.getInstance(Messenger.account()).getClientUserId();
        }
        long uid = GerehIdentity.userIdFor(sk);
        Messenger.rememberUser(uid, sk);
        return uid;
    }

    private static TLRPC.TL_chatInviteExported exported(Invite inv, boolean revoked) {
        TLRPC.TL_chatInviteExported e = new TLRPC.TL_chatInviteExported();
        e.link = inv.getLink() != null ? tgLink(inv.getLink()) : "";
        e.admin_id = uidOf(inv.getBy());
        e.date = (int) (inv.getCreatedMs() / 1000);
        e.revoked = revoked;
        e.request_needed = inv.getNeedApproval();
        if (inv.getExpMs() > 0) {
            e.expire_date = (int) (inv.getExpMs() / 1000);
            e.flags |= 2;
        }
        if (inv.getMaxUses() > 0) {
            e.usage_limit = inv.getMaxUses();
            e.flags |= 4;
        }
        e.usage = inv.getUses();
        e.flags |= 8;
        if (revoked) {
            e.flags |= 1;
        }
        if (inv.getNeedApproval()) {
            e.flags |= 64;
        }
        return e;
    }

    /** Our newest invite of a group, for the "invite link" row of its info page; null if none. */
    static TLRPC.TL_chatInviteExported primary(ChatNode node, Group g) {
        try {
            List<Invite> list = node.groupInvites(g.getId());
            for (int i = list.size() - 1; i >= 0; i--) {
                Invite inv = list.get(i);
                if (inv.getLink() != null && !inv.getNeedApproval() && inv.getMaxUses() == 0 && inv.getExpMs() == 0) {
                    TLRPC.TL_chatInviteExported e = exported(inv, false);
                    e.permanent = true;
                    e.flags |= 32;
                    return e;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "invites: " + t.getClass().getSimpleName());
        }
        return null;
    }

    private static Invite byLink(ChatNode node, Group g, String link) {
        String id = node.inviteInfo(payload(link)).getInvite();
        for (Invite inv : node.groupInvites(g.getId())) {
            if (inv.getId().equals(id)) {
                return inv;
            }
        }
        return null;
    }

    private static TLRPC.User requesterUser(JoinRequest r) {
        TLRPC.TL_user u = new TLRPC.TL_user();
        u.id = uidOf(r.getSk());
        u.access_hash = 0;
        u.flags |= 1;
        u.first_name = r.getName().isEmpty() ? "?" : r.getName();
        u.flags |= 2; // without it the round trip drops the name
        u.contact = false;
        u.verified = GerehFeeds.isOfficialAdmin(r.getSk());
        GerehPhotos.dress(u, r.getSk());
        return (TLRPC.User) Messenger.roundTripObject(u);
    }

    // ---- requests -------------------------------------------------------------------------

    static TLObject answer(int acc, ChatNode node, TLObject req, RequestDelegate done) throws Exception {
        if (req instanceof TLRPC.TL_messages_checkChatInvite) {
            return check(node, ((TLRPC.TL_messages_checkChatInvite) req).hash);
        }
        if (req instanceof TLRPC.TL_messages_importChatInvite) {
            join(node, ((TLRPC.TL_messages_importChatInvite) req).hash, done);
            return null;
        }
        if (req instanceof TLRPC.TL_messages_exportChatInvite) {
            TLRPC.TL_messages_exportChatInvite q = (TLRPC.TL_messages_exportChatInvite) req;
            Group g = groupOf(node, q.peer);
            long exp = q.expire_date > 0 ? q.expire_date * 1000L : 0;
            String link = node.groupInviteCreate(g.getId(), q.request_needed, exp, Math.max(0, q.usage_limit));
            Invite inv = byLink(node, g, link);
            if (inv == null) {
                throw new IllegalStateException("invite not listed");
            }
            return exported(inv, false);
        }
        if (req instanceof TLRPC.TL_messages_getExportedChatInvites) {
            TLRPC.TL_messages_getExportedChatInvites q = (TLRPC.TL_messages_getExportedChatInvites) req;
            Group g = groupOf(node, q.peer);
            TLRPC.TL_messages_exportedChatInvites r = new TLRPC.TL_messages_exportedChatInvites();
            if (!q.revoked && q.offset_date == 0) {
                long admin = q.admin_id instanceof TLRPC.TL_inputUserSelf ? UserConfig.getInstance(acc).getClientUserId()
                        : q.admin_id instanceof TLRPC.TL_inputUser ? ((TLRPC.TL_inputUser) q.admin_id).user_id : 0;
                List<Invite> list = node.groupInvites(g.getId());
                for (int i = list.size() - 1; i >= 0; i--) {
                    Invite inv = list.get(i);
                    if (inv.getLink() != null && (admin == 0 || uidOf(inv.getBy()) == admin)) {
                        r.invites.add(exported(inv, false));
                    }
                }
            }
            r.count = r.invites.size();
            r.users.add(UserConfig.getInstance(acc).getCurrentUser());
            return r;
        }
        if (req instanceof TLRPC.TL_messages_getExportedChatInvite) {
            TLRPC.TL_messages_getExportedChatInvite q = (TLRPC.TL_messages_getExportedChatInvite) req;
            Group g = groupOf(node, q.peer);
            Invite inv = byLink(node, g, q.link);
            if (inv == null) {
                throw new Messenger.Refusal(LocaleController.getString(R.string.GerehInviteGone));
            }
            TLRPC.TL_messages_exportedChatInvite r = new TLRPC.TL_messages_exportedChatInvite();
            r.invite = exported(inv, false);
            r.users.add(UserConfig.getInstance(acc).getCurrentUser());
            return r;
        }
        if (req instanceof TLRPC.TL_messages_getAdminsWithInvites) {
            Group g = groupOf(node, ((TLRPC.TL_messages_getAdminsWithInvites) req).peer);
            Map<String, Integer> counts = new HashMap<>();
            for (Invite inv : node.groupInvites(g.getId())) {
                counts.merge(inv.getBy(), 1, Integer::sum);
            }
            TLRPC.TL_messages_chatAdminsWithInvites r = new TLRPC.TL_messages_chatAdminsWithInvites();
            String me = node.getMe().getSk();
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                if (e.getKey().equals(me)) {
                    continue; // ours are listed on their own
                }
                TLRPC.TL_chatAdminWithInvites a = new TLRPC.TL_chatAdminWithInvites();
                a.admin_id = uidOf(e.getKey());
                a.invites_count = e.getValue();
                r.admins.add(a);
            }
            r.users.addAll(GerehGroups.usersFor(node, g));
            return r;
        }
        if (req instanceof TLRPC.TL_messages_editExportedChatInvite) {
            TLRPC.TL_messages_editExportedChatInvite q = (TLRPC.TL_messages_editExportedChatInvite) req;
            Group g = groupOf(node, q.peer);
            Invite inv = byLink(node, g, q.link);
            if (inv == null) {
                throw new Messenger.Refusal(LocaleController.getString(R.string.GerehInviteGone));
            }
            if (!q.revoked) {
                // a link's limits are signed into the group's log: a changed link is a new link
                throw new Messenger.Refusal(LocaleController.getString(R.string.GerehInviteNoEdit));
            }
            node.groupInviteRevoke(g.getId(), inv.getId());
            TLRPC.TL_messages_exportedChatInvite r = new TLRPC.TL_messages_exportedChatInvite();
            r.invite = exported(inv, true);
            r.users.add(UserConfig.getInstance(acc).getCurrentUser());
            return r;
        }
        if (req instanceof TLRPC.TL_messages_deleteExportedChatInvite || req instanceof TLRPC.TL_messages_deleteRevokedExportedChatInvites) {
            return new TLRPC.TL_boolTrue(); // a revoked link is gone already
        }
        if (req instanceof TLRPC.TL_messages_getChatInviteImporters) {
            TLRPC.TL_messages_getChatInviteImporters q = (TLRPC.TL_messages_getChatInviteImporters) req;
            Group g = groupOf(node, q.peer);
            TLRPC.TL_messages_chatInviteImporters r = new TLRPC.TL_messages_chatInviteImporters();
            if (q.requested && q.offset_date == 0) {
                for (JoinRequest jr : node.groupJoinRequests(g.getId())) {
                    TLRPC.TL_chatInviteImporter im = new TLRPC.TL_chatInviteImporter();
                    im.requested = true;
                    im.user_id = uidOf(jr.getSk());
                    im.date = (int) (jr.getAtMs() / 1000);
                    r.importers.add(im);
                    r.users.add(requesterUser(jr));
                }
            }
            r.count = r.importers.size();
            return r;
        }
        if (req instanceof TLRPC.TL_messages_hideChatJoinRequest) {
            TLRPC.TL_messages_hideChatJoinRequest q = (TLRPC.TL_messages_hideChatJoinRequest) req;
            Group g = groupOf(node, q.peer);
            long uid = q.user_id instanceof TLRPC.TL_inputUser ? ((TLRPC.TL_inputUser) q.user_id).user_id : 0;
            String sk = Messenger.skForUser(uid);
            if (sk == null) {
                throw new IllegalArgumentException("unknown requester");
            }
            node.groupJoinDecide(g.getId(), sk, q.approved);
            return updatesFor(node, g.getId());
        }
        if (req instanceof TLRPC.TL_messages_hideAllChatJoinRequests) {
            TLRPC.TL_messages_hideAllChatJoinRequests q = (TLRPC.TL_messages_hideAllChatJoinRequests) req;
            Group g = groupOf(node, q.peer);
            for (JoinRequest jr : node.groupJoinRequests(g.getId())) {
                node.groupJoinDecide(g.getId(), jr.getSk(), q.approved);
            }
            return updatesFor(node, g.getId());
        }
        throw new IllegalArgumentException("not an invite request");
    }

    private static TLRPC.TL_updates updatesFor(ChatNode node, String gid) {
        Group g = node.groupState(gid);
        return g.getChannel() ? GerehChannels.chatUpdates(node, g) : GerehGroups.chatUpdates(node, g);
    }

    /** `messages.checkChatInvite`: what the link says, for Telegram's join dialog. */
    private static TLObject check(ChatNode node, String hash) {
        InviteInfo i;
        try {
            i = node.inviteInfo(payload(hash));
        } catch (Throwable t) {
            throw new Messenger.Refusal(LocaleController.getString(R.string.GerehInviteBad));
        }
        if (i.getMember()) {
            Group g = node.groupState(i.getGroup());
            if (g != null) {
                TLRPC.TL_chatInviteAlready a = new TLRPC.TL_chatInviteAlready();
                a.chat = GerehGroups.chatFor(g);
                return a;
            }
        }
        TLRPC.TL_chatInvite c = new TLRPC.TL_chatInvite();
        c.title = i.getName().isEmpty() ? "?" : i.getName();
        c.about = "";
        c.channel = i.getChannel();
        c.broadcast = i.getChannel();
        c.megagroup = false;
        c.request_needed = i.getNeedApproval();
        c.photo = new TLRPC.TL_photoEmpty();
        c.participants_count = 0;
        if (i.getExpMs() > 0) {
            c.expires = (int) (i.getExpMs() / 1000);
        }
        return c;
    }

    /**
     * `messages.importChatInvite`: the request goes to the inviter's phone. A link that needs
     * approval answers at once ("request sent"); otherwise Telegram's dialog waits a little for the
     * inviter's phone to add us, and else says the request is on its way.
     */
    private static void join(ChatNode node, String hash, RequestDelegate done) {
        InviteInfo i;
        String gid;
        try {
            i = node.inviteInfo(payload(hash));
            gid = node.groupJoin(payload(hash));
        } catch (Throwable t) {
            Log.w(TAG, "join: " + t.getClass().getSimpleName());
            Utilities.stageQueue.postRunnable(() -> done.run(null, error("INVITE_HASH_EXPIRED")));
            return;
        }
        if (i.getNeedApproval()) {
            Utilities.stageQueue.postRunnable(() -> done.run(null, error("INVITE_REQUEST_SENT")));
            return;
        }
        final boolean[] answered = new boolean[1];
        Runnable joined = () -> {
            synchronized (answered) {
                if (answered[0]) {
                    return;
                }
                answered[0] = true;
            }
            TLRPC.TL_chatInviteJoinResultOk ok = new TLRPC.TL_chatInviteJoinResultOk();
            ok.updates = updatesFor(node, gid);
            done.run(ok, null);
        };
        if (isMember(node, gid)) {
            Utilities.stageQueue.postRunnable(joined);
            return;
        }
        joinWaiters.computeIfAbsent(gid, k -> new ArrayList<>()).add(joined);
        Utilities.stageQueue.postRunnable(() -> {
            synchronized (answered) {
                if (answered[0]) {
                    return;
                }
                answered[0] = true;
            }
            List<Runnable> w = joinWaiters.get(gid);
            if (w != null) {
                w.remove(joined);
            }
            done.run(null, error("GEREH_JOIN_QUEUED"));
        }, JOIN_WAIT_MS);
    }

    private static boolean isMember(ChatNode node, String gid) {
        try {
            Group g = node.groupState(gid);
            return g != null && g.getMember();
        } catch (Throwable t) {
            return false;
        }
    }

    /** A group changed (Messenger.onEvent): join dialogs waiting for it answer once we are in. */
    static void onGroupChanged(ChatNode node, String gid) {
        if (!joinWaiters.containsKey(gid) || !isMember(node, gid)) {
            return;
        }
        List<Runnable> w = joinWaiters.remove(gid);
        if (w != null) {
            for (Runnable r : w) {
                Utilities.stageQueue.postRunnable(r);
            }
        }
    }

    private static TLRPC.TL_error error(String text) {
        TLRPC.TL_error e = new TLRPC.TL_error();
        e.code = 400;
        e.text = text;
        return e;
    }

    /** For the info page: requests waiting (admins), and their newest requesters. */
    static void dressFull(TLRPC.ChatFull f, ChatNode node, Group g) {
        if (!g.getAdmin()) {
            return;
        }
        TLRPC.TL_chatInviteExported primary = primary(node, g);
        if (primary == null) {
            // Telegram's server gives every group a main link of its own; its «invite links» page
            // waits for that one, and a new Gereh group had none, so the page said «loading…»
            // forever (Ehsan, 25 Oct 2026, S26). An admin who may invite gets it made here once.
            try {
                node.groupInviteCreate(g.getId(), false, 0, 0);
                primary = primary(node, g);
            } catch (Throwable t) {
                Log.w(TAG, "main invite link not made: " + t.getClass().getSimpleName());
            }
        }
        // each field travels only with its flag (the answer is serialized and parsed back, see
        // Bridge), and the bits differ: chatFull 13 and 17, channelFull 23 and 28
        boolean channel = f instanceof TLRPC.TL_channelFull;
        if (primary != null) {
            f.exported_invite = primary;
            f.flags |= channel ? 1 << 23 : 1 << 13;
        }
        try {
            List<JoinRequest> reqs = node.groupJoinRequests(g.getId());
            if (!reqs.isEmpty()) {
                f.requests_pending = reqs.size();
                for (int i = 0; i < Math.min(3, reqs.size()); i++) {
                    f.recent_requesters.add(uidOf(reqs.get(i).getSk()));
                }
                f.flags |= channel ? 1 << 28 : 1 << 17;
            }
        } catch (Throwable t) {
            Log.w(TAG, "requests: " + t.getClass().getSimpleName());
        }
    }
}
