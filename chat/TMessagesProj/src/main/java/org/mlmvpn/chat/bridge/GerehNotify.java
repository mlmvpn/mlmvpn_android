package org.mlmvpn.chat.bridge;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;

/**
 * MLMCHAT: each chat's notification settings (muted, until when, silent, previews), kept on this
 * phone only (Ehsan, 29 Oct 2026: users muted the official channel and group, and the
 * notifications came back). Telegram sends a chat's settings to its server
 * ({@code account.updateNotifySettings}), which went unanswered and was kept nowhere; and every
 * chat list and chat info page the bridge gave carried empty settings, which Telegram reads as
 * «not muted» and wrote over the user's choice (NotificationsSettingsFacade). Now the bridge keeps
 * what was sent and gives it back with every chat. Nothing goes to the network.
 */
final class GerehNotify {

    /** The settings kept: show_previews, silent and mute_until (Telegram keeps the sound itself). */
    private static final int KEPT = 1 | 2 | 4;

    private GerehNotify() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("gereh_notify", Context.MODE_PRIVATE);
    }

    /** The chat a request names; 0 for the settings of all users, groups or channels. */
    private static long dialogOf(TLRPC.InputNotifyPeer p) {
        return p instanceof TLRPC.TL_inputNotifyPeer ? DialogObject.getPeerDialogId(((TLRPC.TL_inputNotifyPeer) p).peer) : 0;
    }

    /** {@code account.updateNotifySettings} and {@code account.getNotifySettings} of one chat; null otherwise. */
    static TLObject answer(TLObject req) {
        if (req instanceof TL_account.updateNotifySettings) {
            TL_account.updateNotifySettings q = (TL_account.updateNotifySettings) req;
            long dialog = dialogOf(q.peer);
            if (dialog != 0 && q.settings != null) {
                TLRPC.TL_inputPeerNotifySettings s = q.settings;
                prefs().edit().putString(Long.toString(dialog), (s.flags & KEPT) + ":" + (s.show_previews ? 1 : 0) + ":" + (s.silent ? 1 : 0) + ":" + s.mute_until).apply();
            }
            // the settings of all chats of a kind stay Telegram's own, on this phone
            return new TLRPC.TL_boolTrue();
        }
        if (req instanceof TL_account.getNotifySettings) {
            long dialog = dialogOf(((TL_account.getNotifySettings) req).peer);
            return dialog != 0 ? of(dialog) : null;
        }
        return null;
    }

    /** What the user chose for this chat; empty (Telegram's defaults) when nothing was chosen. */
    static TLRPC.TL_peerNotifySettings of(long dialog) {
        TLRPC.TL_peerNotifySettings out = new TLRPC.TL_peerNotifySettings();
        String v = dialog != 0 ? prefs().getString(Long.toString(dialog), null) : null;
        if (v == null) {
            // the official group, where thousands write: muted until the user turns it on, or every
            // phone rang for every message (users, 30 Oct 2026); the official channel still rings
            if (dialog == -GerehGroups.chatIdFor(GerehFeeds.GROUP)) {
                out.flags |= 4;
                out.mute_until = Integer.MAX_VALUE;
            }
            return out;
        }
        String[] p = v.split(":");
        if (p.length != 4) {
            return out;
        }
        try {
            out.flags = Integer.parseInt(p[0]) & KEPT;
            out.show_previews = "1".equals(p[1]);
            out.silent = "1".equals(p[2]);
            out.mute_until = Integer.parseInt(p[3]);
        } catch (NumberFormatException e) {
            return new TLRPC.TL_peerNotifySettings();
        }
        return out;
    }
}
