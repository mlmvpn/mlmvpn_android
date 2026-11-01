package org.mlmvpn.chat.bridge;

import org.mlmchat.core.ChatNode;
import org.mlmchat.core.FeedInfo;
import org.mlmchat.core.Group;
import org.mlmchat.core.GroupMember;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.BulletinFactory;

/**
 * MLMCHAT: «سکوت» on a member's message in a group (Ehsan, 30 Oct 2026: muting meant finding the
 * member in the list and opening their permissions; in the official group every admin mutes). It
 * is the request the permissions page sends (ChatRightsEditActivity): Telegram's editBanned, for a
 * Gereh group a member's own exception with nothing left to send (GerehChannels, SPEC 7.2
 * Restrict), for the official group an admin's ban item (GerehFeeds, feed.rs takes one from every
 * admin). The same durations as that page.
 */
public final class GerehMute {
    /** This phone may not mute them (not its right, they are an admin, or it is not a group). */
    public static final int NO = -1;
    public static final int CAN = 0;
    /** Muted now: the option lifts it. */
    public static final int MUTED = 1;

    private static final int[] SECONDS = {30 * 60, 60 * 60, 6 * 3600, 86400, 3 * 86400, 7 * 86400, 0};

    private GerehMute() {
    }

    /** Whether this phone may mute that user in that chat, and whether they are muted now. */
    public static int state(long chatId, long userId) {
        try {
            ChatNode node = GerehIdentity.node();
            String sk = userId > 0 ? Messenger.skForUser(userId) : null;
            if (node == null || sk == null || sk.equals(node.getMe().getSk())) {
                return NO;
            }
            long now = System.currentTimeMillis();
            String feed = GerehFeeds.feedOf(chatId);
            if (feed != null) {
                FeedInfo info = node.feedInfo(feed);
                if (info == null || info.getBroadcast() || !info.getAdmin() || info.getAdmins().contains(sk)) {
                    return NO;
                }
                Long until = info.getBanned().get(sk);
                return until != null && until > now ? MUTED : CAN;
            }
            Group g = GerehGroups.groupFor(node, chatId);
            if (g == null || g.getChannel() || !g.getMember() || g.getDissolved()) {
                return NO;
            }
            boolean owner = g.getOwner().equals(node.getMe().getSk());
            if (!owner && !(g.getAdmin() && (g.getAdminRights() & 4) != 0)) {
                return NO;
            }
            for (GroupMember m : g.getMembers()) {
                if (m.getSk().equals(sk)) {
                    if (m.getOwner() || m.getAdmin()) {
                        return NO;
                    }
                    return GerehGroups.mutedNow(m, now) ? MUTED : CAN;
                }
            }
            return NO;
        } catch (Throwable t) {
            return NO;
        }
    }

    /** The option: how long, then the request; a muted member is let speak again at once. */
    public static void choose(BaseFragment fragment, int account, TLRPC.Chat chat, TLRPC.User user, boolean muted) {
        if (fragment == null || fragment.getParentActivity() == null || chat == null || user == null) {
            return;
        }
        if (muted) {
            send(fragment, account, chat, user, -1);
            return;
        }
        CharSequence[] names = new CharSequence[SECONDS.length];
        for (int i = 0; i < SECONDS.length; i++) {
            int s = SECONDS[i];
            names[i] = s == 0 ? LocaleController.getString(R.string.UserRestrictionsUntilForever)
                    : s < 3600 ? LocaleController.formatPluralString("Minutes", s / 60)
                    : s < 86400 ? LocaleController.formatPluralString("Hours", s / 3600)
                    : s == 7 * 86400 ? LocaleController.formatPluralString("Weeks", 1)
                    : LocaleController.formatPluralString("Days", s / 86400);
        }
        BottomSheet.Builder sheet = new BottomSheet.Builder(fragment.getParentActivity());
        sheet.setTitle(LocaleController.formatString(R.string.GerehMuteMemberTitle, org.telegram.messenger.UserObject.getUserName(user)));
        sheet.setItems(names, (d, which) -> send(fragment, account, chat, user, SECONDS[which]));
        fragment.showDialog(sheet.create());
    }

    /** seconds: how long (0 for good), -1 lifts it. */
    private static void send(BaseFragment fragment, int account, TLRPC.Chat chat, TLRPC.User user, int seconds) {
        TLRPC.TL_chatBannedRights b = new TLRPC.TL_chatBannedRights();
        if (seconds >= 0) {
            b.send_messages = true;
            b.send_plain = true;
            b.send_media = true;
            b.send_photos = true;
            b.send_videos = true;
            b.send_roundvideos = true;
            b.send_audios = true;
            b.send_voices = true;
            b.send_docs = true;
            b.send_stickers = true;
            b.send_gifs = true;
            b.send_games = true;
            b.send_inline = true;
            b.send_polls = true;
            b.embed_links = true;
            b.invite_users = true;
            b.pin_messages = true;
            b.change_info = true;
            b.until_date = seconds == 0 ? 0 : ConnectionsManager.getInstance(account).getCurrentTime() + seconds;
        }
        // as the permissions page names a group (TL_chat): a channel id, which Gereh reads either way
        TLRPC.TL_channels_editBanned req = new TLRPC.TL_channels_editBanned();
        TLRPC.TL_inputChannel channel = new TLRPC.TL_inputChannel();
        channel.channel_id = chat.id;
        channel.access_hash = chat.access_hash;
        req.channel = channel;
        req.participant = MessagesController.getInstance(account).getInputPeer(user);
        req.banned_rights = b;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            if (error == null) {
                MessagesController.getInstance(account).processUpdates((TLRPC.Updates) response, false);
                AndroidUtilities.runOnUIThread(() -> {
                    MessagesController.getInstance(account).loadFullChat(chat.id, 0, true);
                    if (fragment.getParentActivity() != null) {
                        String name = org.telegram.messenger.UserObject.getFirstName(user);
                        BulletinFactory.of(fragment).createSimpleBulletin(seconds >= 0 ? R.raw.ic_mute : R.raw.ic_unmute,
                                LocaleController.formatString(seconds >= 0 ? R.string.GerehMutedMember : R.string.GerehUnmutedMember, name)).show();
                    }
                });
            } else {
                AndroidUtilities.runOnUIThread(() -> AlertsCreator.processError(account, error, fragment, req, false));
            }
        });
    }
}
