package org.mlmvpn.chat.bridge;

import android.util.Log;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.RequestDelegateTimestamp;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MLM CHAT phase-0 spike (PLAN section 17.4): Telegram's app with no Telegram server.
 *
 * Every request the app makes goes through ConnectionsManager.sendRequestInternal; there it is
 * handed to {@link #answer} instead of tgnet. This spike answers only what is needed for the empty
 * chat list to open: help.getConfig, help.getAppConfig, updates.getState, messages.getDialogs
 * (empty) and the langpack requests. Everything else gets a clear error, and only its class name
 * is logged (tag MLMCHAT), never its content.
 *
 * Each answer is serialized and parsed back with the request's own deserializeResponse, exactly
 * like a server answer, so the app sees the same object shape it would get from the network.
 */
public final class Bridge {
    public static final boolean ENABLED = true;
    private static final String TAG = "MLMCHAT";

    private static final Map<String, Integer> unanswered = new ConcurrentHashMap<>();

    private Bridge() {
    }

    /**
     * The official channel or group (GerehFeeds): everyone is in them and nobody leaves or deletes
     * them (Ehsan, 24 Oct 2026), so the screens offer neither. Takes a chat id or a dialog id.
     */
    public static boolean isOfficialChat(long chatId) {
        return ENABLED && GerehFeeds.feedOf(Math.abs(chatId)) != null;
    }

    /**
     * MLMCHAT: a reaction drawn with the app's own emoji. Telegram's are animated files on its
     * servers, which Gereh never asks (GerehReactions); null outside Gereh, so Telegram's own path runs.
     */
    public static android.graphics.drawable.Drawable reactionDrawable(String emoji) {
        if (!ENABLED || emoji == null) {
            return null;
        }
        android.graphics.drawable.Drawable d = org.telegram.messenger.Emoji.getEmojiDrawable(org.telegram.messenger.Emoji.fixEmoji(emoji));
        return d != null ? d : org.telegram.messenger.Emoji.getEmojiDrawable(emoji);
    }

    /**
     * MLMCHAT: the admin screen (ChatRightsEditActivity) saves a Gereh group's admin as it is, never
     * turning the group into a supergroup first; it hands the rights and title it chose here, for
     * the basic group's own request that follows (GerehGroups).
     */
    public static void chooseAdminRights(long chatId, long userId, org.telegram.tgnet.TLRPC.TL_chatAdminRights rights, String rank) {
        GerehGroups.chooseAdminRights(chatId, userId, rights, rank);
    }

    /**
     * MLMCHAT: official chats the app's config no longer names, out of a list Telegram just loaded
     * from this phone's own database (MessagesController.processLoadedDialogs). A full list from the
     * bridge drops them as well (GerehFeeds.dropStale), but Telegram mostly starts from its cache and
     * seldom asks for one, so on Dr.Net's phone the old «MLMVPN رسمی» pair stayed for days.
     */
    public static void dropStaleOfficialChats(int account) {
        if (ENABLED) {
            GerehFeeds.dropStale(account);
        }
    }

    /**
     * MLMCHAT: a Gereh group's members with an exception of their own (SPEC 7.2 `Restrict`), for its
     * «Permissions» page (ChatUsersActivity): a basic group's page lists none by itself.
     */
    public static java.util.ArrayList<org.telegram.tgnet.TLObject> restrictedInGroup(long chatId) {
        return GerehGroups.restrictedParticipants(chatId);
    }

    /** MLMCHAT: everyone banned from a Gereh group, for its «Removed users» page (a basic group has none). */
    public static java.util.ArrayList<org.telegram.tgnet.TLObject> bannedInGroup(long chatId) {
        return GerehGroups.bannedParticipants(chatId);
    }

    /** MLMCHAT: whether this user runs an older app than its newest release, as far as this phone knows (T75). */
    public static boolean isOldVersion(long userId) {
        return ENABLED && GerehVersion.isOld(userId);
    }

    /** MLMCHAT: the tag beside them, «نسخهٔ قدیمی» (T75). */
    public static String oldVersionTag() {
        return GerehVersion.tag();
    }

    /** MLMCHAT: `line` with «· نسخهٔ قدیمی» after it when this user's app is old (T75). */
    public static String markOldVersion(long userId, String line) {
        return ENABLED ? GerehVersion.mark(userId, line) : line;
    }

    /** MLMCHAT: a Telegram proxy link (MTProto, SOCKS5, web) goes to the Telegram app; Gereh takes none. */
    public static void openProxyInTelegram(android.app.Activity activity, android.net.Uri link) {
        GerehProxy.open(activity, link, null);
    }

    /** MLMCHAT: a proxy taken before is turned off; Gereh never used it and has no page for it. */
    public static void noProxy() {
        if (ENABLED) {
            GerehProxy.turnOff();
        }
    }

    /** MLMCHAT: a Gereh chat is on the screen: now and then, tell the user their own app is old (T75). */
    public static void chatShown(org.telegram.ui.ActionBar.BaseFragment fragment) {
        if (ENABLED) {
            GerehVersion.chatShown(fragment);
        }
    }

    /** MLMCHAT: the official chats' owner key was kept on this phone (GerehAddContact): they show «add admin» now. */
    public static void ownerKeyKept() {
        GerehFeeds.ownerKeyKept();
    }

    /** What a refused leave or delete of an official chat says. */
    public static void refuseOfficialLeave() {
        org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> android.widget.Toast.makeText(
                org.telegram.messenger.ApplicationLoader.applicationContext,
                org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.GerehOfficialNoLeave),
                android.widget.Toast.LENGTH_LONG).show());
    }

    private static final java.util.Set<Integer> clocks = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * MLMCHAT: the one-second tick tgnet gives Telegram (ConnectionsManager.onUpdate), which never
     * comes in Gereh, for the parts of MessagesController.updateTimerProc Gereh needs
     * (MessagesController.gerehTick): this phone's «online» and «offline» (SPEC 5.5), and the end
     * of a contact's chat action (SPEC 5.4) and of the «online» it implied. Once per account.
     */
    public static void startClock(int account) {
        if (!clocks.add(account)) {
            return;
        }
        Runnable[] tick = new Runnable[1];
        tick[0] = () -> {
            try {
                MessagesController.getInstance(account).gerehTick();
            } catch (Throwable t) {
                Log.w(TAG, "clock: " + t.getClass().getSimpleName());
            }
            Utilities.stageQueue.postRunnable(tick[0], 1000);
        };
        Utilities.stageQueue.postRunnable(tick[0], 1000);
    }

    /**
     * MLMCHAT: the chat list's search shows chats this phone is not in (GerehFound, q6): it says so
     * for its contacts.search, and where the answer with Telegram's part goes when it comes.
     */
    public static void searchLater(TLObject req, boolean wanted, Utilities.Callback<TLObject> then) {
        if (ENABLED) {
            GerehFound.later(req, wanted, then);
        }
    }

    /** MLMCHAT: the search's section row before Telegram's results (GerehFound). */
    public static void searchSections(java.util.ArrayList<TLObject> list) {
        if (ENABLED) {
            GerehFound.sections(list);
        }
    }

    /** MLMCHAT: the title over the search's chats beyond this phone: «جستجو در گره» or «جستجو در تلگرام». */
    public static String searchSectionTitle(java.util.ArrayList<TLObject> list) {
        return GerehFound.sectionTitle(list);
    }

    /** MLMCHAT: what the Gereh section's row says beside its title: «چیزی پیدا نشد» when empty, else null. */
    public static String searchSectionNote(java.util.ArrayList<TLObject> list) {
        return GerehFound.sectionNote(list);
    }

    /** MLMCHAT: the owner's «نمایش در جستجو» on the chat's edit page: -1 for none, else whether it is listed (GerehFound). */
    public static int dirListed(long chatId) {
        return ENABLED ? GerehFound.listed(chatId) : -1;
    }

    /** MLMCHAT: the owner puts the chat in Gereh's public list or takes it out (GerehFound). */
    public static void dirSetListed(long chatId, boolean listed) {
        if (ENABLED) {
            GerehFound.setListed(chatId, listed);
        }
    }

    /** MLMCHAT: a tap on a chat the search found: joined or added instead of opened (GerehFound). */
    public static boolean openFound(org.telegram.ui.ActionBar.BaseFragment fragment, TLRPC.Chat chat) {
        return ENABLED && chat != null && GerehFound.open(fragment, chat.id);
    }

    /** Replaces the network round trip of one request. Callbacks run on the stage queue, as for a server answer. */
    public static void answer(int account, TLObject request, RequestDelegate onComplete, RequestDelegateTimestamp onCompleteTimestamp, int token) {
        if (GerehFound.handles(request)) {
            // the chat list's search beyond this phone's chats: Gereh's list and Telegram's (q6)
            GerehFound.answer(request, (response, error) -> {
                if (onComplete != null) {
                    onComplete.run(response, error);
                } else if (onCompleteTimestamp != null) {
                    onCompleteTimestamp.run(response, error, System.currentTimeMillis());
                }
            });
            return;
        }
        if (GerehSearch.handles(request)) {
            // the info page's tabs and their counts: the chat's own history page, kept to the tab's
            // kind (GerehSearch; they were always empty)
            RequestDelegate tabs = (response, error) -> {
                TLObject out = response != null ? GerehSearch.from(request, response) : null;
                if (onComplete != null) {
                    onComplete.run(out, error);
                } else if (onCompleteTimestamp != null) {
                    onCompleteTimestamp.run(out, error, System.currentTimeMillis());
                }
            };
            answerChat(account, GerehSearch.history(request), tabs, null, token);
            return;
        }
        if (Messenger.handles(request)) {
            answerChat(account, request, onComplete, onCompleteTimestamp, token);
            return;
        }
        TLObject response = null;
        TLRPC.TL_error error = null;
        try {
            TLObject built = build(request);
            if (built != null) {
                response = roundTrip(request, built);
            } else if (request instanceof TLRPC.TL_messages_getSearchCounters || request instanceof TLRPC.TL_contacts_getStatuses
                    || request instanceof TLRPC.TL_messages_getSuggestedDialogFilters) {
                response = emptyVector(request);
            }
        } catch (Throwable t) {
            Log.e(TAG, "bridge failed for " + request.getClass().getSimpleName() + ": " + t.getClass().getSimpleName());
        }
        if (response == null) {
            String name = request.getClass().getSimpleName();
            error = new TLRPC.TL_error();
            // A read ("get…") is one a screen sends by itself, mostly in the background: 406 is the
            // code Telegram's alerts and bulletins show nothing for. The boost status every channel
            // and group asks for on opening flashed «this part of Gereh is not built yet» on the
            // chat and its info page (Ehsan, 25 Oct 2026). An action the user takes keeps 400.
            error.code = isRead(name) ? 406 : 400;
            // Shown as is by Telegram's error alert, so it says what is true in words.
            boolean group = request instanceof TLRPC.TL_messages_createChat || request instanceof TLRPC.TL_channels_createChannel;
            error.text = LocaleController.getString(group ? R.string.GerehGroupsLater : R.string.GerehNotBuiltYet);
            Integer n = unanswered.merge(name, 1, Integer::sum);
            if (n != null && n == 1) {
                Log.i(TAG, "unanswered request type: " + name);
            }
        } else {
            Log.d(TAG, "answered " + request.getClass().getSimpleName());
        }
        final TLObject finalResponse = response;
        final TLRPC.TL_error finalError = error;
        final long now = System.currentTimeMillis();
        Utilities.stageQueue.postRunnable(() -> {
            if (onComplete != null) {
                onComplete.run(finalResponse, finalError);
            } else if (onCompleteTimestamp != null) {
                onCompleteTimestamp.run(finalResponse, finalError, now);
            } else if (finalResponse instanceof TLRPC.Updates) {
                MessagesController.getInstance(account).processUpdates((TLRPC.Updates) finalResponse, false);
            }
        });
    }

    /** One-to-one chat requests: answered from the core ({@link Messenger}). */
    private static void answerChat(int account, TLObject request, RequestDelegate onComplete, RequestDelegateTimestamp onCompleteTimestamp, int token) {
        RequestDelegate done = (response, error) -> {
            if (onComplete != null) {
                onComplete.run(response, error);
            } else if (onCompleteTimestamp != null) {
                onCompleteTimestamp.run(response, error, System.currentTimeMillis());
            }
        };
        TLObject response = null;
        TLRPC.TL_error error = null;
        // how long the answer held Telegram's stage queue, which also carries every new message and
        // tick: the request's class and milliseconds only
        long t0 = android.os.SystemClock.elapsedRealtime();
        try {
            TLObject built = Messenger.answer(account, request, done, token);
            if (built == null) {
                Log.d(TAG, "chat " + request.getClass().getSimpleName() + " in " + (android.os.SystemClock.elapsedRealtime() - t0) + " ms, answered later");
                return; // answered later through done
            }
            // Handed over as built: messages must not be round-tripped (see Messenger.toTl).
            response = built;
            Log.d(TAG, "chat " + request.getClass().getSimpleName() + " in " + (android.os.SystemClock.elapsedRealtime() - t0) + " ms");
        } catch (Throwable t) {
            Log.e(TAG, "chat request failed: " + request.getClass().getSimpleName() + ": " + t.getClass().getSimpleName());
            error = new TLRPC.TL_error();
            error.code = 400;
            error.text = t instanceof Messenger.Refusal ? t.getMessage() : LocaleController.getString(R.string.GerehNotBuiltYet);
        }
        final TLObject finalResponse = response;
        final TLRPC.TL_error finalError = error;
        Utilities.stageQueue.postRunnable(() -> done.run(finalResponse, finalError));
    }

    /** True for a request class named for a read: TL_premium_getBoostsStatus, getSavedStarGifts, TL_getAlbums. */
    private static boolean isRead(String className) {
        return className.substring(className.lastIndexOf('_') + 1).startsWith("get");
    }

    private static int now() {
        return (int) (System.currentTimeMillis() / 1000);
    }

    private static TLObject build(TLObject request) {
        if (request instanceof TLRPC.TL_help_getConfig) {
            return config();
        }
        if (request instanceof TLRPC.TL_help_getAppConfig) {
            TLRPC.TL_help_appConfig c = new TLRPC.TL_help_appConfig();
            c.hash = 1;
            c.config = new TLRPC.TL_jsonObject();
            return c;
        }
        if (request instanceof TLRPC.TL_updates_getState) {
            TLRPC.TL_updates_state s = new TLRPC.TL_updates_state();
            s.pts = 1;
            s.qts = 0;
            s.date = now();
            s.seq = 1;
            s.unread_count = 0;
            return s;
        }
        if (request instanceof TLRPC.TL_messages_getDialogs) {
            return new TLRPC.TL_messages_dialogs();
        }
        if (request instanceof TLRPC.TL_messages_getAvailableReactions) {
            return GerehReactions.available();
        }
        if (request instanceof TLRPC.TL_messages_getMessageEditData) {
            // asked before an edit begins: unanswered, Telegram said «you cannot edit this message»
            // in every chat and never sent the edit (Ehsan, 27 Oct 2026). Gereh edits the text and
            // caption of one's own messages (SPEC 5 Edit), and of one's own official posts (SPEC
            // 18, T60): in an official chat only those, so another admin's post is refused here
            TLRPC.TL_messages_getMessageEditData q = (TLRPC.TL_messages_getMessageEditData) request;
            if (q.peer != null && q.peer.channel_id != 0 && isOfficialChat(q.peer.channel_id) && !GerehFeeds.ownPost(q.peer.channel_id, q.id)) {
                return null;
            }
            return new TLRPC.TL_messages_messageEditData();
        }
        // a chat's notification settings, kept on this phone (GerehNotify)
        TLObject notify = GerehNotify.answer(request);
        if (notify != null) {
            return notify;
        }
        TLObject folders = GerehFolders.answer(request);
        if (folders != null) {
            return folders;
        }
        TLObject none = empty(request);
        if (none != null) {
            return none;
        }
        if (request instanceof TLRPC.TL_langpack_getLangPack || request instanceof TLRPC.TL_langpack_getDifference) {
            TLRPC.TL_langPackDifference d = new TLRPC.TL_langPackDifference();
            String code = request instanceof TLRPC.TL_langpack_getLangPack
                    ? ((TLRPC.TL_langpack_getLangPack) request).lang_code
                    : ((TLRPC.TL_langpack_getDifference) request).lang_code;
            d.lang_code = code != null ? code : "en";
            d.from_version = 0;
            d.version = 1;
            return d;
        }
        return null;
    }

    /**
     * Background requests of screens Gereh keeps no server data for (a chat's shared media and
     * search, scheduled and saved messages, reactions, folders, recommendations, sponsored
     * messages): an honest empty answer. An error here reached the user as «this part of Gereh is
     * not built yet» on every group or channel info page (Ehsan, 24 Oct 2026).
     */
    private static TLObject empty(TLObject r) {
        if (r instanceof TLRPC.TL_messages_search || r instanceof TLRPC.TL_messages_getScheduledHistory
                || r instanceof TLRPC.TL_messages_getSavedHistory) {
            return new TLRPC.TL_messages_messages();
        }
        if (r instanceof TLRPC.TL_messages_getSearchResultsPositions) {
            TLRPC.TL_messages_searchResultsPositions p = new TLRPC.TL_messages_searchResultsPositions();
            p.count = 0;
            return p;
        }
        if (r instanceof TLRPC.TL_messages_getSavedDialogs) {
            return new TLRPC.TL_messages_savedDialogs();
        }
        if (r instanceof TLRPC.TL_messages_getPinnedDialogs) {
            TLRPC.TL_messages_peerDialogs d = new TLRPC.TL_messages_peerDialogs();
            TLRPC.TL_updates_state st = new TLRPC.TL_updates_state();
            st.pts = 1;
            st.date = now();
            st.seq = 0;
            d.state = st;
            return d;
        }
        if (r instanceof TLRPC.TL_channels_getChannelRecommendations) {
            return new TLRPC.TL_messages_chats();
        }
        if (r instanceof TLRPC.TL_messages_getSponsoredMessages) {
            return new TLRPC.TL_messages_sponsoredMessagesEmpty();
        }
        if (r instanceof TLRPC.TL_messages_reportReadMetrics) {
            return new TLRPC.TL_boolTrue();
        }
        if (r instanceof TLRPC.TL_messages_getMessagesReactions) {
            TLRPC.TL_updates u = new TLRPC.TL_updates();
            u.date = now();
            u.seq = 0;
            return u;
        }
        return null;
    }

    /** An empty vector, for the requests whose answer is a bare list. */
    private static TLObject emptyVector(TLObject request) {
        SerializedData out = new SerializedData(8);
        out.writeInt32(0x1cb5c415);
        out.writeInt32(0);
        SerializedData in = new SerializedData(out.toByteArray());
        TLObject parsed = request.deserializeResponse(in, in.readInt32(true), true);
        out.cleanup();
        in.cleanup();
        return parsed;
    }

    /** A config that names no data center and keeps Telegram's usual limits. */
    private static TLRPC.TL_config config() {
        TLRPC.TL_config c = new TLRPC.TL_config();
        c.date = now();
        c.expires = c.date + 3600;
        c.test_mode = false;
        c.this_dc = 2;
        c.dc_txt_domain_name = "";
        c.chat_size_max = 200;
        c.megagroup_size_max = 200000;
        c.forwarded_count_max = 100;
        c.online_update_period_ms = 210000;
        c.offline_blur_timeout_ms = 5000;
        c.offline_idle_timeout_ms = 30000;
        c.online_cloud_timeout_ms = 300000;
        c.notify_cloud_delay_ms = 30000;
        c.notify_default_delay_ms = 1500;
        c.push_chat_period_ms = 60000;
        c.push_chat_limit = 2;
        c.edit_time_limit = 172800;
        c.revoke_time_limit = Integer.MAX_VALUE;
        c.revoke_pm_time_limit = Integer.MAX_VALUE;
        c.rating_e_decay = 2419200;
        c.stickers_recent_limit = 200;
        c.channels_read_media_period = 604800;
        c.call_receive_timeout_ms = 20000;
        c.call_ring_timeout_ms = 90000;
        c.call_connect_timeout_ms = 30000;
        c.call_packet_timeout_ms = 10000;
        c.me_url_prefix = "";
        c.caption_length_max = 1024;
        c.message_length_max = 4096;
        c.webfile_dc_id = 4;
        return c;
    }

    private static TLObject roundTrip(TLObject request, TLObject response) {
        SerializedData out = new SerializedData(response.getObjectSize());
        response.serializeToStream(out);
        SerializedData in = new SerializedData(out.toByteArray());
        int magic = in.readInt32(true);
        TLObject parsed = request.deserializeResponse(in, magic, true);
        out.cleanup();
        in.cleanup();
        return parsed;
    }
}
