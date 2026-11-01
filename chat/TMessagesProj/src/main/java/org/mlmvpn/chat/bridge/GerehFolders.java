package org.mlmvpn.chat.bridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.List;

/**
 * MLMCHAT: chat folders. Telegram keeps folders on its server and loads them again from there
 * (every time the folder settings open, among others), dropping any folder the server does not
 * list. The bridge answered "no folders", so a new folder showed for a while and then went
 * (Ehsan, 24 Oct 2026: «sometimes it works, sometimes not»). Here this phone is that server:
 * each folder is kept as Telegram sent it, with the order and the tags switch, in the chat
 * process's private preferences. Folders never leave the phone.
 */
final class GerehFolders {
    private static final String PREFS = "gereh_folders";
    private static final String ORDER = "order";
    private static final String TAGS = "tags";

    private GerehFolders() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The answer to a folder request, or null when it is not one. */
    static synchronized TLObject answer(TLObject req) {
        if (req instanceof TLRPC.TL_messages_getDialogFilters) {
            return list();
        }
        if (req instanceof TLRPC.TL_messages_updateDialogFilter) {
            importLocal();
            TLRPC.TL_messages_updateDialogFilter q = (TLRPC.TL_messages_updateDialogFilter) req;
            List<Integer> order = order();
            SharedPreferences.Editor e = prefs().edit();
            if (q.filter == null) {
                e.remove(key(q.id));
                order.remove(Integer.valueOf(q.id));
            } else {
                e.putString(key(q.id), encode(q.filter));
                if (!order.contains(q.id)) {
                    order.add(q.id);
                }
            }
            e.putString(ORDER, join(order)).apply();
            return new TLRPC.TL_boolTrue();
        }
        if (req instanceof TLRPC.TL_messages_updateDialogFiltersOrder) {
            importLocal();
            prefs().edit().putString(ORDER, join(((TLRPC.TL_messages_updateDialogFiltersOrder) req).order)).apply();
            return new TLRPC.TL_boolTrue();
        }
        if (req instanceof TLRPC.TL_messages_toggleDialogFilterTags) {
            importLocal();
            prefs().edit().putBoolean(TAGS, ((TLRPC.TL_messages_toggleDialogFilterTags) req).enabled).apply();
            return new TLRPC.TL_boolTrue();
        }
        return null;
    }

    /**
     * The first time (this store never written): the folders Telegram already has on this phone
     * are taken in, so folders made before the store existed are kept, not dropped by the reload.
     */
    private static void importLocal() {
        SharedPreferences p = prefs();
        if (p.contains(ORDER)) {
            return;
        }
        SharedPreferences.Editor e = p.edit();
        List<Integer> order = new ArrayList<>();
        try {
            MessagesController mc = MessagesController.getInstance(Messenger.account());
            for (MessagesController.DialogFilter f : new ArrayList<>(mc.getDialogFilters())) {
                order.add(f.id);
                if (f.isDefault()) {
                    continue;
                }
                e.putString(key(f.id), encode(fromLocal(mc, f)));
            }
        } catch (Throwable t) {
            android.util.Log.w("MLMCHAT", "folders import: " + t.getClass().getSimpleName());
        }
        e.putString(ORDER, join(order)).apply();
    }

    /** A folder as Telegram keeps it on the phone, as it would send it to its server. */
    private static TLRPC.TL_dialogFilter fromLocal(MessagesController mc, MessagesController.DialogFilter f) {
        TLRPC.TL_dialogFilter d = new TLRPC.TL_dialogFilter();
        d.id = f.id;
        d.contacts = (f.flags & MessagesController.DIALOG_FILTER_FLAG_CONTACTS) != 0;
        d.non_contacts = (f.flags & MessagesController.DIALOG_FILTER_FLAG_NON_CONTACTS) != 0;
        d.groups = (f.flags & MessagesController.DIALOG_FILTER_FLAG_GROUPS) != 0;
        d.broadcasts = (f.flags & MessagesController.DIALOG_FILTER_FLAG_CHANNELS) != 0;
        d.bots = (f.flags & MessagesController.DIALOG_FILTER_FLAG_BOTS) != 0;
        d.exclude_muted = (f.flags & MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_MUTED) != 0;
        d.exclude_read = (f.flags & MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_READ) != 0;
        d.exclude_archived = (f.flags & MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_ARCHIVED) != 0;
        d.title = new TLRPC.TL_textWithEntities();
        d.title.text = f.name != null ? f.name : "";
        if (f.entities != null) {
            d.title.entities = f.entities;
        }
        d.title_noanimate = f.title_noanimate;
        if (f.color >= 0) {
            d.flags |= 134217728;
            d.color = f.color;
        }
        for (int b = 0; b < 3; b++) {
            List<Long> from = new ArrayList<>();
            ArrayList<TLRPC.InputPeer> to = b == 0 ? d.include_peers : b == 1 ? d.exclude_peers : d.pinned_peers;
            if (b == 0) {
                from.addAll(f.alwaysShow);
            } else if (b == 1) {
                from.addAll(f.neverShow);
            } else {
                for (int i = 0; i < f.pinnedDialogs.size(); i++) {
                    from.add(f.pinnedDialogs.keyAt(i));
                }
            }
            for (long did : from) {
                if (b == 0 && f.pinnedDialogs.indexOfKey(did) >= 0) {
                    continue;
                }
                TLRPC.InputPeer peer = mc.getInputPeer(did);
                if (peer != null && !(peer instanceof TLRPC.TL_inputPeerEmpty)) {
                    to.add(peer);
                }
            }
        }
        return d;
    }

    /** The folders in their order; "All chats" (id 0) where the user put it. */
    private static TLRPC.TL_messages_dialogFilters list() {
        importLocal();
        SharedPreferences p = prefs();
        TLRPC.TL_messages_dialogFilters r = new TLRPC.TL_messages_dialogFilters();
        r.tags_enabled = p.getBoolean(TAGS, false);
        List<Integer> order = order();
        if (!order.contains(0)) {
            order.add(0, 0); // as Telegram's server: "All chats" is always listed, else the app drops it
        }
        for (Integer id : order) {
            TLRPC.DialogFilter f = id == 0 ? new TLRPC.TL_dialogFilterDefault() : decode(p.getString(key(id), null));
            if (f != null) {
                r.filters.add(f);
            }
        }
        return r;
    }

    private static String key(int id) {
        return "f" + id;
    }

    private static List<Integer> order() {
        List<Integer> out = new ArrayList<>();
        for (String s : prefs().getString(ORDER, "").split(",")) {
            try {
                if (!s.isEmpty()) {
                    out.add(Integer.parseInt(s));
                }
            } catch (NumberFormatException ignored) {
                // a broken entry is skipped
            }
        }
        return out;
    }

    private static String join(List<Integer> ids) {
        StringBuilder sb = new StringBuilder();
        for (Integer id : ids) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(id);
        }
        return sb.toString();
    }

    private static String encode(TLRPC.DialogFilter f) {
        SerializedData out = new SerializedData(f.getObjectSize());
        f.serializeToStream(out);
        String s = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        out.cleanup();
        return s;
    }

    private static TLRPC.DialogFilter decode(String s) {
        if (s == null) {
            return null;
        }
        SerializedData in = new SerializedData(Base64.decode(s, Base64.NO_WRAP));
        try {
            return TLRPC.DialogFilter.TLdeserialize(in, in.readInt32(false), false);
        } finally {
            in.cleanup();
        }
    }
}
