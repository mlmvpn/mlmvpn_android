package org.mlmvpn.chat.bridge;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import org.mlmchat.core.ChatNode;
import org.mlmchat.core.Me;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;

/**
 * MLMCHAT: the user's own Gereh identity inside Telegram's account model (PLAN 13.5, 15; T14).
 *
 * The identity itself (keys, name, the encrypted database) is the core's (org.mlmchat.core). This
 * class only shows it to Telegram's code: a stable Telegram user id made from the identity key,
 * the chosen name as the first name, and the Gereh name (T14, e.g. {@code ehsan·4821}) in the
 * field where Telegram keeps a phone number, so every screen that showed "+null" shows the Gereh
 * name instead ({@link #shownNumber}).
 */
public final class GerehIdentity {
    private static final String TAG = "MLMCHAT";

    /** The phase-0 spike's stand-in user, which had no keys. Replaced by a real identity. */
    private static final long SPIKE_STAND_IN_ID = 7_000_000_001L;

    /** Telegram user ids of Gereh users: 2^40 plus 40 bits of a hash of the identity key. */
    private static final long USER_ID_BASE = 1L << 40;

    private static ChatNode node;

    private GerehIdentity() {
    }

    /** The core node of this process, opened on first use. Null while there is no identity. */
    public static synchronized ChatNode node() {
        if (node == null) {
            Context context = ApplicationLoader.applicationContext;
            if (!ChatNode.exists(context)) {
                return null;
            }
            node = ChatNode.open(context);
        }
        return node;
    }

    /** The node a fresh identity made; it becomes this process's node. */
    public static synchronized void adopt(ChatNode created) {
        if (node != null && node != created) {
            node.close();
        }
        node = created;
    }

    /**
     * On every start of the chat. The phone's contacts are never read or sent (mlm-chat CLAUDE.md,
     * rule 2), so contact sync is off. The spike's stand-in account goes, so the identity screen
     * makes a real one; and an identity whose Telegram account is missing gets it back.
     */
    public static void onLaunch(int account) {
        UserConfig config = UserConfig.getInstance(account);
        if (config.isClientActivated() && config.getClientUserId() == SPIKE_STAND_IN_ID) {
            config.clearConfig();
            Log.i(TAG, "spike stand-in account removed");
        }
        if (config.syncContacts) {
            config.syncContacts = false;
            config.saveConfig(false);
        }
        try {
            if (!config.isClientActivated()) {
                ChatNode n = node();
                if (n != null) {
                    activate(account, n.getMe());
                    Log.i(TAG, "account restored from the identity");
                }
            } else if (config.getCurrentUser() != null && config.getCurrentUser().access_hash == 0) {
                // kept by a build before avatars had an access hash (GerehPhotos.dress): our own
                // photo showed nowhere until it is made again
                ChatNode n = node();
                if (n != null) {
                    activate(account, n.getMe());
                }
            }
            if (config.isClientActivated()) {
                Messenger.start(account);
                GerehService.startIfReady(ApplicationLoader.applicationContext);
            }
        } catch (Exception e) {
            Log.e(TAG, "identity could not be opened: " + e.getClass().getSimpleName());
        }
    }

    /** Makes the identity Telegram's logged-in user, the same steps LoginActivity.onAuthSuccess takes. */
    public static void activate(int account, Me me) {
        TLRPC.TL_user u = new TLRPC.TL_user();
        u.id = userIdFor(me.getSk());
        u.self = true;
        // Optional fields by their flag bits: access_hash 0, first_name 1, phone 4, status 6.
        u.access_hash = 0;
        u.flags |= 1;
        u.first_name = me.getName();
        u.flags |= 2;
        u.phone = me.getDisplayName();
        u.flags |= 16;
        u.status = new TLRPC.TL_userStatusOffline();
        u.flags |= 64;
        u.verified = GerehFeeds.isOfficialAdmin(me.getSk());
        GerehPhotos.dress(u, me.getSk());
        TLRPC.User user = roundTrip(u);
        UserConfig config = UserConfig.getInstance(account);
        config.syncContacts = false;
        ConnectionsManager.getInstance(account).setUserId(user.id);
        config.setCurrentUser(user);
        config.saveConfig(true);
        ArrayList<TLRPC.User> users = new ArrayList<>();
        users.add(user);
        MessagesStorage.getInstance(account).putUsersAndChats(users, null, true, true);
        MessagesController.getInstance(account).putUser(user, false);
    }

    /** A stable Telegram user id for an identity key (hex), in a range no Telegram id uses. */
    public static long userIdFor(String skHex) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            d.update("mlmchat/tg-user-id/1".getBytes(StandardCharsets.US_ASCII));
            d.update(hex(skHex));
            byte[] h = d.digest();
            long v = 0;
            for (int i = 0; i < 5; i++) {
                v = (v << 8) | (h[i] & 0xFF);
            }
            return USER_ID_BASE | v;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * What a "phone number" shows in Gereh (PhoneFormat.format): the Gereh name Telegram's code
     * keeps there, without the "+" it puts in front of every number, and nothing for no number.
     */
    public static String shownNumber(String orig) {
        if (orig == null) {
            return "";
        }
        String s = orig.startsWith("+") ? orig.substring(1) : orig;
        return TextUtils.isEmpty(s) || "null".equals(s) ? "" : s;
    }

    static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    private static TLRPC.User roundTrip(TLRPC.TL_user u) {
        SerializedData out = new SerializedData(u.getObjectSize());
        u.serializeToStream(out);
        SerializedData in = new SerializedData(out.toByteArray());
        TLRPC.User parsed = TLRPC.User.TLdeserialize(in, in.readInt32(true), true);
        out.cleanup();
        in.cleanup();
        return parsed;
    }
}
