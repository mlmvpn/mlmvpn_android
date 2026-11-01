package org.mlmvpn.chat.bridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.SystemClock;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import org.mlmchat.core.ChatEvent;
import org.mlmchat.core.ChatMarks;
import org.mlmchat.core.ChatNode;
import org.mlmchat.core.Contact;
import org.mlmchat.core.ContactStatus;
import org.mlmchat.core.GasRelayView;
import org.mlmchat.core.Me;
import org.mlmchat.core.Message;
import org.mlmchat.core.MessageState;
import org.mlmchat.core.NetStatus;
import org.mlmchat.core.NostrRelay;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.Vector;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.tgnet.tl.TL_update;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MLMCHAT: one-to-one chat through the Gereh core (PLAN 13.5, phase 2).
 *
 * Telegram's screens ask for contacts, dialogs, history and sending as if a server were there;
 * these answers come from the core instead, and the core's events become Telegram updates. Ids:
 * a contact's Telegram user id comes from their key ({@link GerehIdentity#userIdFor}); a
 * message's Telegram id is the core's own number for it ({@code seq}), which is unique across
 * all chats on this phone and never changes, as Telegram wants for private chats.
 *
 * Honest states (CLAUDE.md rule 4): a message keeps Telegram's clock until the core says it is
 * out of this phone ("sent": the other phone has it, or a mailbox holds it for them, a Google
 * relay or a Nostr relay, SPEC 19 and 20); only then does Telegram get its answer and draw one
 * check. To a contact whose phone is off, the core puts it in a mailbox at once, so the clock
 * stays only while this phone can reach no route at all. Two checks only on the other side's read
 * receipt. A queued message stays a clock, with no time limit, because the core keeps trying.
 */
public final class Messenger {
    private static final String TAG = "MLMCHAT";
    /** Rows read beyond what a page needs, for those Telegram does not show (our own still queued). */
    private static final int PAGE_SLACK = 20;
    /** At most this many new messages in one difference; the rest come in Telegram's next round. */
    static final int DIFFERENCE_MAX = 1000;

    private static volatile boolean started;
    private static int account;

    /**
     * When a session with each contact last got through, either way (the core keeps it across
     * restarts, SPEC 15.6), by sk; and how (lan, direct, p2p, relay).
     */
    private static final Map<String, Long> reachableAt = new ConcurrentHashMap<>();
    private static final Map<String, String> reachVia = new ConcurrentHashMap<>();
    /** The connection state last given to Telegram, so it is told only on a change. */
    private static volatile int shownState = -1;
    private static volatile NetStatus lastNet;
    /**
     * When this network came (or the core started on it), by the wall clock the core's times are
     * on; and the last moment a road to other networks was seen working on it (roadWorks), by the
     * clock that never steps back. What a road did before the network came proves nothing here.
     */
    private static volatile long networkSince;
    private static volatile long roadAt;
    /**
     * Once every road stops answering, the title waits this long before «Connecting…»: a Nostr
     * relay reconnecting, or the next one taking over, takes a moment. Telegram's own line does
     * not wait (ConnectionsManager.cpp: its one connection either answers or closes), but Gereh
     * has several roads, and a hand-over between two of them is no news.
     */
    private static final long ROAD_DOWN_MS = 3_000;
    /**
     * A Nostr relay counts once it has stayed open this long: answering the handshake and dropping
     * at once is what a filtered network does to some of them, and that is no road.
     */
    private static final long RELAY_STEADY_MS = 2_000;
    /** Nostr relay url -> when it was first seen open, by the clock that never steps back. */
    private static final Map<String, Long> relayOpenSince = new ConcurrentHashMap<>();
    private static String ladderLogged = "";
    /** Contacts whose frames are queued now, and why (a transport error code), by sk. */
    private static final Map<String, String> unreachable = new ConcurrentHashMap<>();
    /** Contacts whose newest card names no Google relay of theirs (see noRelay). */
    private static final java.util.Set<String> noRelay = ConcurrentHashMap.newKeySet();
    /**
     * When a frame last passed through a relay with each contact, either way: a Google mailbox
     * (SPEC 19) or a Nostr relay (SPEC 20), which the core notes alike. The line says «a relay».
     */
    private static final Map<String, Long> mailboxAt = new ConcurrentHashMap<>();
    /** When a frame last came from each contact through a Google mailbox or a Nostr relay: their phone was up then. */
    private static final Map<String, Long> mailboxInAt = new ConcurrentHashMap<>();
    /** Telegram user id -> sk, for the status line. */
    private static final Map<Long, String> skByUid = new ConcurrentHashMap<>();
    /**
     * Last seen (SPEC 5.5, T57), by sk and by this phone's clock: when each contact's app last said
     * «online» (gone after its «offline»), and when they were last seen in the app. Empty while our
     * own last seen is hidden: then nobody's is shown here (as in Telegram).
     */
    private static final Map<String, Long> onlineAt = new ConcurrentHashMap<>();
    private static final Map<String, Long> lastSeenAt = new ConcurrentHashMap<>();
    /** «Online» holds this long after the last one came (the core's PRESENCE_TTL_MS). */
    private static final long PRESENCE_TTL_MS = 150_000;
    private static final long REACHABLE_MS = 3 * 60 * 1000;
    /** How long a Google-relay exchange is still worth naming as the route (it is a route, not «reachable»). */
    private static final long MAILBOX_MS = 60 * 60 * 1000;

    /** Sends still waiting for the other phone, by core message id. */
    private static final Map<String, Pending> pending = new ConcurrentHashMap<>();

    private static final class Pending {
        final String peer;
        final long randomId;
        final RequestDelegate done;
        /** Telegram's request token, by which it cancels the send (SendMessagesHelper.cancelSendingMessage). */
        final int token;

        Pending(String peer, long randomId, RequestDelegate done, int token) {
            this.peer = peer;
            this.randomId = randomId;
            this.done = done;
            this.token = token;
        }
    }

    private Messenger() {
    }

    // ---- lifecycle -----------------------------------------------------------------------

    /** Starts the core's transport and the event loop for this account. Safe to call again. */
    public static synchronized void start(int acc) {
        if (started) {
            return;
        }
        ChatNode node = GerehIdentity.node();
        if (node == null) {
            return;
        }
        account = acc;
        started = true;
        Thread t = new Thread(() -> run(node), "gereh-core");
        t.setDaemon(true);
        t.start();
    }

    private static void run(ChatNode node) {
        GerehReactions.preload();
        WifiManager.MulticastLock lock = null;
        try {
            // Private LAN beacons (SPEC 14.8) arrive as broadcast/multicast, which Android drops
            // unless an app holds this lock.
            WifiManager wifi = (WifiManager) ApplicationLoader.applicationContext.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                lock = wifi.createMulticastLock("gereh-beacons");
                lock.setReferenceCounted(false);
                // held only while Gereh is on screen (GerehNet.setFront): all day it woke the phone
                // for every broadcast on the network
                GerehNet.beaconLock(lock);
            }
        } catch (Throwable e) {
            Log.w(TAG, "no multicast lock: " + e.getClass().getSimpleName());
        }
        try {
            // The same port every start, so the cards contacts already hold keep working; the
            // core falls back to any free port when this one is taken.
            SharedPreferences net = ApplicationLoader.applicationContext.getSharedPreferences("gereh_net", Context.MODE_PRIVATE);
            int port = net.getInt("port", 0);
            if (port == 0) {
                port = 20000 + new java.security.SecureRandom().nextInt(40000);
            }
            // the official channel and group (T38), before any frame can bring their posts
            GerehFeeds.install(node);
            GerehFeeds.show(node);
            // popular Telegram channels on this phone (T68): read from the database, and their timer
            GerehTg.start();
            // this phone's own channels and groups in Gereh's public list (q6): renewed when due
            GerehFound.start();
            List<String> addrs = node.startDirect(port);
            int used = addrs.isEmpty() ? 0 : portOf(addrs.get(0));
            if (used > 0 && used != net.getInt("port", 0)) {
                net.edit().putInt("port", used).apply();
            }
            Log.i(TAG, "core transport up, " + addrs.size() + " card addresses");
        } catch (Throwable e) {
            Log.e(TAG, "core transport failed: " + e.getMessage());
            setConnection(ConnectionsManager.ConnectionStateConnecting);
            return;
        }
        // Phase 3: other networks, through volunteers (SPEC 15); this phone is one only if the user
        // chose so, and only on Wi-Fi with enough battery (GerehNet). A failure leaves the LAN path
        // working.
        GerehNet.start(node);
        // «nearby» (Bluetooth, SPEC 16): only if the user turned it on and gave the permissions
        GerehNet.applyNearby(node);
        loadReach(node);
        // who runs an older app (T75): what this phone has kept of everyone so far
        GerehVersion.load(node);
        watchNetwork(node);
        AndroidUtilities.runOnUIThread(() -> ContactsController.getInstance(account).loadContacts(false, 0));
        Utilities.stageQueue.postRunnable(() -> MessagesController.getInstance(account).getDifference(), 1500);
        long statusAt = 0;
        long conditionsAt = System.currentTimeMillis();
        while (true) {
            ChatEvent e;
            try {
                e = node.pollEvent(1000);
            } catch (Throwable t) {
                Log.e(TAG, "event loop ended: " + t.getMessage());
                return;
            }
            // each second: «Connecting…» comes and goes with the roads (ROAD_DOWN_MS, RELAY_STEADY_MS)
            if (System.currentTimeMillis() - statusAt > 1000) {
                statusAt = System.currentTimeMillis();
                updateConnection(node);
            }
            // the battery: a volunteer pauses below 30% unless charging, and comes back
            if (System.currentTimeMillis() - conditionsAt > 60_000) {
                conditionsAt = System.currentTimeMillis();
                GerehNet.conditionsChanged(node);
            }
            if (e != null) {
                long t0 = android.os.SystemClock.elapsedRealtime();
                try {
                    onEvent(node, e);
                } catch (Throwable t) {
                    Log.e(TAG, "event failed: " + t.getClass().getSimpleName());
                }
                // one slow event holds every event after it: its class and milliseconds only
                long ms = android.os.SystemClock.elapsedRealtime() - t0;
                if (ms >= 20) {
                    Log.d(TAG, "event " + e.getClass().getSimpleName() + " took " + ms + " ms");
                }
            }
        }
    }

    /**
     * The state line at the top, honest (CLAUDE.md rule 4) and the way Telegram's follows its
     * connection: "waiting for network" without one; "connecting" until a road to other networks
     * really works on this network, and again once none does (roadWorks); the title «Gereh» while
     * one does, and whether a given contact is reachable is said under that contact's name.
     * Without internet but with a contact on the same Wi-Fi or hotspot, «connected over Wi-Fi»;
     * else with one beside this phone, «connected over Bluetooth» (Ehsan, 23 Oct 2026): «waiting
     * for network» only when neither reaches anyone. A phone sharing its hotspot has no network of
     * its own, so its contacts on the hotspot count as Wi-Fi too.
     */
    private static void updateConnection(ChatNode node) {
        ConnectivityManager cm = (ConnectivityManager) ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        int state;
        boolean local = recently("lan");
        boolean bluetooth = GerehNet.nearbyCount() > 0 || recently("ble");
        if (cm == null || cm.getActiveNetwork() == null) {
            state = local ? ConnectionsManager.ConnectionStateLocal
                    : bluetooth ? ConnectionsManager.ConnectionStateBluetooth : ConnectionsManager.ConnectionStateWaitingForNetwork;
        } else {
            NetStatus st;
            try {
                st = node.netStatus();
            } catch (Throwable t) {
                return;
            }
            lastNet = st;
            logLadder(st);
            // «Connecting…» until a road really works, and again once none does (the Dr.Net owner,
            // 28 Oct 2026: until it really is connected it stays «Connecting…»). It used to follow
            // the volunteer ladder (Ehsan, 23 Oct): «Connecting…» for the whole first climb - five
            // minutes on a phone network with no volunteer in reach, over a chat that worked through
            // Nostr and the mailboxes - and the title after it, though nothing might work at all.
            long tick = SystemClock.elapsedRealtime();
            if (roadWorks(node, st, System.currentTimeMillis(), tick)) {
                roadAt = tick;
            }
            boolean road = roadAt > 0 && tick - roadAt < ROAD_DOWN_MS;
            state = road ? ConnectionsManager.ConnectionStateConnected : ConnectionsManager.ConnectionStateConnecting;
            // a network with no internet (Android's own check failed, and no road either): what
            // still works is the same Wi-Fi, or Bluetooth
            boolean internet = road || validated(cm);
            if (!internet && local) {
                state = ConnectionsManager.ConnectionStateLocal;
            } else if (!internet && bluetooth) {
                state = ConnectionsManager.ConnectionStateBluetooth;
            }
        }
        GerehWidgetState.net(state);
        if (state != shownState) {
            boolean lines = near(state) || near(shownState);
            shownState = state;
            setConnection(state);
            if (lines) {
                refreshStatus(); // the lines under contacts' names depend on it (statusLine)
            }
        }
    }

    /** Bluetooth or Wi-Fi without internet: only contacts beside us or on the same network are reachable. */
    private static boolean near(int state) {
        return state == ConnectionsManager.ConnectionStateBluetooth || state == ConnectionsManager.ConnectionStateLocal;
    }

    /** A contact was reached or seen this way in the last few minutes. */
    private static boolean recently(String via) {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> e : reachableAt.entrySet()) {
            if (now - e.getValue() < REACHABLE_MS && via.equals(reachVia.get(e.getKey()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * A road to other networks works now, proved since this network came (SPEC 15, 19, 20) - the
     * way Telegram calls itself connected only once its server's first answer is in: a volunteer
     * holds a reservation for this phone (or this phone is one) or is connected to it, a Nostr
     * relay kept open for our messages has stayed open RELAY_STEADY_MS, or a Google mailbox
     * relay's last call came back right. A contact reached a while ago is not one: that can be
     * minutes old, and the line under their name says it.
     */
    private static boolean roadWorks(ChatNode node, NetStatus st, long now, long tick) {
        if (st.getOnline() || "reachable".equals(st.getLadder())) {
            return true;
        }
        boolean steady = false;
        try {
            java.util.Set<String> open = new java.util.HashSet<>();
            for (NostrRelay r : node.nostrRelays()) {
                if (r.getReads() && "open".equals(r.getState())) {
                    open.add(r.getRelay());
                    Long since = relayOpenSince.putIfAbsent(r.getRelay(), tick);
                    steady |= since != null && tick - since >= RELAY_STEADY_MS;
                }
            }
            relayOpenSince.keySet().retainAll(open); // a relay that closed starts again from zero
        } catch (Throwable ignored) {
            // Nostr not started
        }
        if (steady) {
            return true;
        }
        try {
            for (GasRelayView r : node.gasOverview().getRelays()) {
                long ok = r.getLastOkMs();
                if (r.getEnabled() && !r.getDraft() && "ok".equals(r.getState()) && ok > networkSince && now - ok < REACHABLE_MS) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // the mailbox transport not started
        }
        return false;
    }

    /** Android's own check says this network reaches the internet. */
    private static boolean validated(ConnectivityManager cm) {
        try {
            NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Throwable e) {
            return true;
        }
    }

    /**
     * The connection ladder as last read (SPEC 15.9), for a status line that says more than
     * "connecting": `getLadder()` is `idle`, `probing`, `reachable` or `unreachable` (no volunteer
     * reachable from this network; the next try in `getNextTryS()` seconds), `getRung()` the way in
     * use (`v4:443`, `v6:40443`, `lan:40443`). Null before the p2p transport started.
     */
    public static NetStatus net() {
        return lastNet;
    }

    /** One logcat line per change of the ladder: kinds and counts only (CLAUDE.md rule 6). */
    private static void logLadder(NetStatus st) {
        String line = st.getLadder() + " way=" + st.getRung() + " tried=" + st.getTried() + "/" + st.getTotal()
                + " mostly=" + st.getLastError() + " volunteers=" + st.getVolunteersConnected() + "/" + st.getVolunteersKnown()
                + " reservations=" + st.getReservations() + " net=" + st.getNetwork()
                + " scope=" + st.getScope() + " outOfScope=" + st.getOutOfScope() + " volunteer=" + st.getVolunteer();
        if (!line.equals(ladderLogged)) {
            ladderLogged = line;
            Log.i(TAG, "ladder " + line + (st.getNextTryS() > 0 ? " next=" + st.getNextTryS() + "s" : ""));
        }
    }

    /**
     * Network changes: the state line, and the core told which network it is on now (its route
     * memory is kept per network, SPEC 15.5) and to reconnect at once.
     */
    private static void watchNetwork(ChatNode node) {
        ConnectivityManager cm = (ConnectivityManager) ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        networkSince = System.currentTimeMillis();
        updateConnection(node);
        if (cm == null) {
            return;
        }
        final String[] last = {null};
        cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                // another network (Wi-Fi to Wi-Fi too), or the same one under another label
                String label = networkLabel(caps);
                String key = network + "|" + label;
                if (!key.equals(last[0])) {
                    boolean first = last[0] == null;
                    last[0] = key;
                    try {
                        node.setNetwork(label);
                        if (!first) {
                            node.networkChanged();
                            // a new network: what the roads did on the old one proves nothing here;
                            // a title already up holds ROAD_DOWN_MS while this one proves a road
                            networkSince = System.currentTimeMillis();
                            relayOpenSince.clear();
                            long tick = SystemClock.elapsedRealtime();
                            roadAt = roadAt > 0 && tick - roadAt < ROAD_DOWN_MS ? tick : 0;
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "network change: " + t.getClass().getSimpleName());
                    }
                    // a volunteer only on Wi-Fi: pause on mobile data, back on Wi-Fi
                    GerehNet.conditionsChanged(node);
                }
                updateConnection(node);
            }

            @Override
            public void onLost(Network network) {
                AndroidUtilities.runOnUIThread(() -> updateConnection(node), 500);
                GerehNet.conditionsChanged(node);
            }
        });
    }

    /**
     * The route memory's label for a network: the operator's code on mobile data (MCC and MNC,
     * e.g. 43235 for Irancell; no permission needed), "wifi" plus nothing personal on Wi-Fi (the
     * core tells Wi-Fi networks apart by a hash of their address ranges).
     */
    private static String networkLabel(NetworkCapabilities caps) {
        if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            String op = "";
            try {
                TelephonyManager tm = (TelephonyManager) ApplicationLoader.applicationContext.getSystemService(Context.TELEPHONY_SERVICE);
                if (tm != null && tm.getSimOperator() != null) {
                    op = tm.getSimOperator();
                }
            } catch (Throwable ignored) {
            }
            return "cell:" + op;
        }
        return "";
    }

    /** What the core remembers of each contact's reachability, so the lines survive a restart. */
    private static void loadReach(ChatNode node) {
        try {
            for (Contact c : node.contacts()) {
                ContactStatus cs = node.contactStatus(c.getSk());
                if (cs.getLastReachedMs() > 0) {
                    reachableAt.put(c.getSk(), cs.getLastReachedMs());
                    reachVia.put(c.getSk(), cs.getVia());
                }
                long mailbox = Math.max(cs.getMailboxInMs(), cs.getMailboxOutMs());
                if (mailbox > 0) {
                    mailboxAt.put(c.getSk(), mailbox);
                }
                if (cs.getMailboxInMs() > 0) {
                    mailboxInAt.put(c.getSk(), cs.getMailboxInMs());
                }
                if (cs.getQueued() > 0 && !cs.getOnline() && cs.getReason() != null) {
                    unreachable.put(c.getSk(), cs.getReason());
                }
                noRelay(c.getSk(), cs);
                if (cs.getLastSeenMs() > 0) {
                    lastSeenAt.put(c.getSk(), cs.getLastSeenMs());
                    if (cs.getInApp()) {
                        onlineAt.put(c.getSk(), cs.getLastSeenMs());
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "reach: " + t.getClass().getSimpleName());
        }
    }

    /** The core's note of the last frame through a Google mailbox with this contact (SPEC 19). */
    private static void readMailbox(ChatNode node, String peer) {
        try {
            ContactStatus cs = node.contactStatus(peer);
            long mailbox = Math.max(cs.getMailboxInMs(), cs.getMailboxOutMs());
            if (mailbox > 0) {
                mailboxAt.put(peer, mailbox);
            }
            if (cs.getMailboxInMs() > 0) {
                mailboxInAt.put(peer, cs.getMailboxInMs());
            }
            noRelay(peer, cs);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Whether the contact's newest card names no Google relay of theirs (SPEC 19.3). With no other
     * route, nothing can reach them until a newer card of theirs arrives: the line under their name
     * says so and what to do, instead of only «not reachable» (Ehsan and a tester, 25 Oct 2026: both
     * had made relays, and messages never went).
     */
    private static void noRelay(String peer, ContactStatus cs) {
        if (cs.getTheirRelays() == 0) {
            noRelay.add(peer);
        } else {
            noRelay.remove(peer);
        }
    }

    private static int portOf(String addr) {
        try {
            return Integer.parseInt(addr.substring(addr.lastIndexOf(':') + 1));
        } catch (Exception e) {
            return 0;
        }
    }

    private static void setConnection(int state) {
        ConnectionsManager.onConnectionStateChanged(state, account);
    }

    // ---- events from the core --------------------------------------------------------------

    private static void onEvent(ChatNode node, ChatEvent e) {
        if (e instanceof ChatEvent.GroupMessage) {
            ChatEvent.GroupMessage g = (ChatEvent.GroupMessage) e;
            GerehGroups.onMessage(node, g.getGroup(), g.getId());
            return;
        }
        if (e instanceof ChatEvent.Blob) {
            ChatEvent.Blob b = (ChatEvent.Blob) e;
            GerehMedia.onBlob(b.getBlob(), GerehMedia.shortOf(b.getBlob()), b.getHave() == b.getTotal());
            return;
        }
        if (e instanceof ChatEvent.Profile) {
            refreshUser(account, e.getPeer());
            return;
        }
        if (e instanceof ChatEvent.ClientChanged) {
            GerehVersion.changed(node);
            return;
        }
        if (e instanceof ChatEvent.GroupChanged) {
            String gid = ((ChatEvent.GroupChanged) e).getGroup();
            GerehGroups.onChanged(node, gid);
            GerehInvites.onGroupChanged(node, gid);
            // a pin in the group's log (T65)
            org.mlmchat.core.Group g = node.groupState(gid);
            if (g != null) {
                GerehPins.changed(node, GerehGroups.inputPeerFor(g));
            }
            return;
        }
        if (e instanceof ChatEvent.FeedChanged) {
            String feed = ((ChatEvent.FeedChanged) e).getFeed();
            GerehFeeds.onChanged(node, feed);
            TLRPC.TL_inputPeerChannel fp = new TLRPC.TL_inputPeerChannel();
            fp.channel_id = GerehGroups.chatIdFor(feed);
            GerehPins.changed(node, fp);
            return;
        }
        if (e instanceof ChatEvent.Pinned) {
            // a contact pinned or took a pin off in our chat (T65)
            TLRPC.TL_inputPeerUser up = new TLRPC.TL_inputPeerUser();
            up.user_id = GerehIdentity.userIdFor(e.getPeer());
            GerehPins.changed(node, up);
            return;
        }
        if (e instanceof ChatEvent.PollVoted) {
            GerehPolls.onEvent(node, (ChatEvent.PollVoted) e);
            return;
        }
        if (e instanceof ChatEvent.ReactionChanged) {
            ChatEvent.ReactionChanged r = (ChatEvent.ReactionChanged) e;
            GerehReactions.onEvent(node, r);
            if (!r.getGroup() && r.getPeer().equals(r.getChat())) {
                // theirs came, through a relay as often as not: «last reachable» follows (A50, 28 Oct
                // 2026: a reaction through the Iran relay left it at «2 hours ago»)
                readMailbox(node, r.getPeer());
                refreshStatus();
            }
            return;
        }
        if (e instanceof ChatEvent.Deleted) {
            ChatEvent.Deleted d = (ChatEvent.Deleted) e;
            if (d.getGroup()) {
                GerehGroups.onDeleted(node, d.getChat(), d.getSeqs());
            } else {
                final ArrayList<Integer> ids = new ArrayList<>();
                for (Long s : d.getSeqs()) {
                    ids.add(s.intValue());
                }
                Utilities.stageQueue.postRunnable(() -> {
                    TL_update.TL_updateDeleteMessages u = new TL_update.TL_updateDeleteMessages();
                    u.messages = ids;
                    u.pts = nextPts();
                    u.pts_count = 1;
                    push(u, null);
                });
            }
            return;
        }
        if (e instanceof ChatEvent.Edited) {
            ChatEvent.Edited d = (ChatEvent.Edited) e;
            if (d.getGroup()) {
                GerehGroups.onEdited(node, d.getChat(), d.getSeq());
            } else {
                onEdited(node, d.getChat(), d.getSeq());
            }
            return;
        }
        if (e instanceof ChatEvent.JoinRequested) {
            // the info page's "join requests" row: the group as it is now
            GerehGroups.onChanged(node, ((ChatEvent.JoinRequested) e).getGroup());
            return;
        }
        if (e instanceof ChatEvent.ChatAction) {
            onAction(e.getPeer(), ((ChatEvent.ChatAction) e).getKind());
            return;
        }
        if (e instanceof ChatEvent.Presence) {
            onPresence(e.getPeer(), ((ChatEvent.Presence) e).getOnline());
            return;
        }
        if (e instanceof ChatEvent.GroupRead) {
            ChatEvent.GroupRead r = (ChatEvent.GroupRead) e;
            GerehGroups.onRead(node, r.getGroup(), r.getSeq());
            return;
        }
        if (e instanceof ChatEvent.Queued) {
            unreachable.put(e.getPeer(), ((ChatEvent.Queued) e).getReason());
            readMailbox(node, e.getPeer()); // and whether a relay of theirs is known
            refreshStatus();
            return;
        }
        if (e instanceof ChatEvent.Reachable) {
            reachableAt.put(e.getPeer(), System.currentTimeMillis());
            reachVia.put(e.getPeer(), ((ChatEvent.Reachable) e).getVia());
            unreachable.remove(e.getPeer());
            refreshStatus();
            return;
        }
        boolean seen = e instanceof ChatEvent.Seen;
        boolean progress = e instanceof ChatEvent.NewMessage
                || e instanceof ChatEvent.State && ((ChatEvent.State) e).getState() != MessageState.FAILED;
        if (seen || progress) {
            if (seen) {
                reachableAt.put(e.getPeer(), System.currentTimeMillis());
                reachVia.put(e.getPeer(), ((ChatEvent.Seen) e).getVia()); // on the same network (lan), or beside us (ble)
            } else {
                // a session that really got through says so itself (Reachable); a frame that went through
                // a Google mailbox leaves its own mark in the core, and that is the route to name
                readMailbox(node, e.getPeer());
            }
            unreachable.remove(e.getPeer());
            refreshStatus();
        }
        if (e instanceof ChatEvent.NewMessage) {
            ChatEvent.NewMessage m = (ChatEvent.NewMessage) e;
            Message row = find(node, m.getPeer(), m.getId());
            Contact c = contact(node, m.getPeer());
            if (row == null || c == null || row.getText() == null && row.getMedia() == null) {
                return;
            }
            final TLRPC.Message msg = toTl(row, c);
            final TLRPC.User user = userFor(c);
            Utilities.stageQueue.postRunnable(() -> {
                TL_update.TL_updateNewMessage u = new TL_update.TL_updateNewMessage();
                u.message = msg;
                u.pts = nextPts();
                u.pts_count = 1;
                push(u, user);
            });
        } else if (e instanceof ChatEvent.State) {
            ChatEvent.State s = (ChatEvent.State) e;
            onState(node, s.getPeer(), s.getId(), s.getState());
        } else if (e instanceof ChatEvent.NewContact) {
            AndroidUtilities.runOnUIThread(() -> ContactsController.getInstance(account).loadContacts(false, 0));
        } else if (e instanceof ChatEvent.ContactChanged) {
            // A newer card: their name (and Gereh name) as it is now, everywhere it shows, and
            // whether it names a relay of theirs now
            readMailbox(node, e.getPeer());
            refreshStatus();
            Contact c = contact(node, e.getPeer());
            if (c != null) {
                final TLRPC.User u = userFor(c);
                ArrayList<TLRPC.User> list = new ArrayList<>();
                list.add(u);
                MessagesStorage.getInstance(account).putUsersAndChats(list, null, false, true);
                AndroidUtilities.runOnUIThread(() -> {
                    MessagesController.getInstance(account).putUser(u, false);
                    org.telegram.messenger.NotificationCenter.getInstance(account).postNotificationName(
                            org.telegram.messenger.NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_NAME);
                    ContactsController.getInstance(account).loadContacts(false, 0);
                });
            }
        }
    }

    /**
     * A contact's chat action (SPEC 5.4) as Telegram's own line under their name («typing…»);
     * Telegram clears it by itself after six seconds, or when their message comes.
     */
    private static void onAction(String peer, String kind) {
        TLRPC.SendMessageAction action = actionOf(kind);
        if (action == null) {
            return;
        }
        final long uid = GerehIdentity.userIdFor(peer);
        Utilities.stageQueue.postRunnable(() -> {
            TL_update.TL_updateUserTyping u = new TL_update.TL_updateUserTyping();
            u.user_id = uid;
            u.action = action;
            push(u, null);
        });
    }

    /**
     * A contact's app came to the front or left it (SPEC 5.5): Telegram's own status for them (the
     * green dot, «online» in their chat), and the line under their name.
     */
    private static void onPresence(String peer, boolean online) {
        long now = System.currentTimeMillis();
        lastSeenAt.put(peer, now);
        if (online) {
            onlineAt.put(peer, now);
        } else {
            onlineAt.remove(peer);
        }
        pushStatus(peer, statusOf(peer));
        refreshStatus();
    }

    private static void pushStatus(String peer, TLRPC.UserStatus status) {
        final long uid = GerehIdentity.userIdFor(peer);
        Utilities.stageQueue.postRunnable(() -> {
            TL_update.TL_updateUserStatus u = new TL_update.TL_updateUserStatus();
            u.user_id = uid;
            u.status = status;
            push(u, null);
        });
    }

    /** A contact's app is in front now, as it said itself (SPEC 5.5). */
    private static boolean inApp(String sk) {
        Long on = onlineAt.get(sk);
        return on != null && System.currentTimeMillis() - on < PRESENCE_TTL_MS;
    }

    /** The line under this user's name says «online» (LocaleController draws it in the online colour). */
    public static boolean onlineNow(long uid) {
        String sk = skByUid.get(uid);
        return sk != null && inApp(sk);
    }

    /**
     * A contact's status for Telegram from what their app said (SPEC 5.5): online until the last
     * «online» runs out, else offline since they were last seen; empty when nothing is known.
     */
    private static TLRPC.UserStatus statusOf(String sk) {
        Long on = onlineAt.get(sk);
        if (on != null && inApp(sk)) {
            TLRPC.TL_userStatusOnline s = new TLRPC.TL_userStatusOnline();
            s.expires = (int) ((on + PRESENCE_TTL_MS) / 1000);
            return s;
        }
        Long seen = lastSeenAt.get(sk);
        if (seen != null && seen > 0) {
            TLRPC.TL_userStatusOffline s = new TLRPC.TL_userStatusOffline();
            s.expires = (int) (seen / 1000);
            return s;
        }
        return new TLRPC.TL_userStatusEmpty();
    }

    /** Telegram's «Last seen & online» setting: the one privacy rule Gereh keeps (SPEC 5.5). */
    private static boolean isLastSeenPrivacy(TLObject req) {
        TLRPC.InputPrivacyKey key = req instanceof TL_account.getPrivacy ? ((TL_account.getPrivacy) req).key
                : req instanceof TL_account.setPrivacy ? ((TL_account.setPrivacy) req).key : null;
        return key instanceof TLRPC.TL_inputPrivacyKeyStatusTimestamp;
    }

    /**
     * «Last seen & online» (SPEC 5.5): everybody (the default) or nobody, on this phone only. Every
     * Gereh user is a contact, so «my contacts» counts as everybody; there are no exceptions.
     * Hidden, nobody else's is shown here either.
     */
    private static TLObject lastSeenPrivacy(ChatNode node, TLObject req) {
        if (req instanceof TL_account.setPrivacy) {
            boolean shown = false;
            for (TLRPC.InputPrivacyRule r : ((TL_account.setPrivacy) req).rules) {
                if (r instanceof TLRPC.TL_inputPrivacyValueAllowAll || r instanceof TLRPC.TL_inputPrivacyValueAllowContacts) {
                    shown = true;
                }
            }
            node.setLastSeenVisible(shown);
            if (!shown) {
                for (String sk : new ArrayList<>(lastSeenAt.keySet())) {
                    pushStatus(sk, new TLRPC.TL_userStatusEmpty());
                }
                onlineAt.clear();
                lastSeenAt.clear();
                refreshStatus();
            }
        }
        TL_account.privacyRules r = new TL_account.privacyRules();
        r.rules.add(node.lastSeenVisible() ? new TLRPC.TL_privacyValueAllowAll() : new TLRPC.TL_privacyValueDisallowAll());
        return r;
    }

    /** Telegram's action as a Gereh chat action (SPEC 5.4), or null for one Gereh does not carry. */
    private static String actionKind(TLRPC.SendMessageAction a) {
        if (a instanceof TLRPC.TL_sendMessageTypingAction) {
            return "typing";
        }
        if (a instanceof TLRPC.TL_sendMessageRecordAudioAction) {
            return "record_voice";
        }
        if (a instanceof TLRPC.TL_sendMessageUploadPhotoAction) {
            return "upload_photo";
        }
        if (a instanceof TLRPC.TL_sendMessageUploadDocumentAction || a instanceof TLRPC.TL_sendMessageUploadVideoAction
                || a instanceof TLRPC.TL_sendMessageUploadAudioAction || a instanceof TLRPC.TL_sendMessageUploadRoundAction) {
            return "upload_file";
        }
        if (a instanceof TLRPC.TL_sendMessageCancelAction) {
            return "cancel";
        }
        return null;
    }

    /** A Gereh chat action as Telegram's, or null for a kind this app does not know. */
    private static TLRPC.SendMessageAction actionOf(String kind) {
        switch (kind) {
            case "typing":
                return new TLRPC.TL_sendMessageTypingAction();
            case "record_voice":
                return new TLRPC.TL_sendMessageRecordAudioAction();
            case "upload_photo":
                return new TLRPC.TL_sendMessageUploadPhotoAction();
            case "upload_file":
                return new TLRPC.TL_sendMessageUploadDocumentAction();
            case "cancel":
                return new TLRPC.TL_sendMessageCancelAction();
            default:
                return null;
        }
    }

    private static void onState(ChatNode node, String peer, String id, MessageState state) {
        if (pendingAll.containsKey(id)) {
            onStateAll(node, id, state);
        }
        Contact c = contact(node, peer);
        if (c == null && !peer.equals(node.getMe().getSk())) {
            // only a contact's, or Saved Messages' (p3: a message there kept its clock and came
            // back beside itself, never answered as sent)
            return;
        }
        if (state == MessageState.FAILED) {
            Pending p = pending.remove(id);
            if (p != null) {
                TLRPC.TL_error err = new TLRPC.TL_error();
                err.code = 400;
                err.text = LocaleController.getString(R.string.GerehSendFailed);
                Utilities.stageQueue.postRunnable(() -> p.done.run(null, err));
            }
            return;
        }
        Message row = find(node, peer, id);
        if (row == null) {
            return;
        }
        Pending p = pending.remove(id);
        if (p != null && state.compareTo(MessageState.QUEUED) > 0) {
            // Out of this phone (the other phone or a mailbox has it): now Telegram may draw one check.
            final int seq = (int) row.getSeq();
            final int date = (int) (row.getAtMs() / 1000);
            final ArrayList<TLRPC.MessageEntity> entities = GerehEntities.forSent(row.getEntities(), row.getMedia() != null ? row.getMedia().getCaption() : row.getText());
            Utilities.stageQueue.postRunnable(() -> {
                TLRPC.TL_updateShortSentMessage r = new TLRPC.TL_updateShortSentMessage();
                r.out = true;
                r.id = seq;
                r.date = date;
                r.entities = entities;
                r.pts = nextPts();
                r.pts_count = 1;
                p.done.run(r, null);
                markSynced(seq);
            });
        }
        if (state == MessageState.READ) {
            final long uid = GerehIdentity.userIdFor(peer);
            final int seq = (int) row.getSeq();
            Utilities.stageQueue.postRunnable(() -> {
                TL_update.TL_updateReadHistoryOutbox u = new TL_update.TL_updateReadHistoryOutbox();
                TLRPC.TL_peerUser pu = new TLRPC.TL_peerUser();
                pu.user_id = uid;
                u.peer = pu;
                u.max_id = seq;
                u.pts = nextPts();
                u.pts_count = 1;
                push(u, null);
            });
        }
    }

    /**
     * The line under a contact's name in a chat (LocaleController.formatUserStatus): whether their
     * phone can be reached now, as far as this phone knows, or null for «show their Gereh name».
     * Gereh has no server that watches anyone, so this is only what really happened: «reachable»
     * when a session with them got through in the last few minutes (and «through a volunteer»
     * when it went through one), «not reachable now» while our messages wait, else when a session
     * last got through. Never a guess.
     */
    public static String statusLine(long uid) {
        String sk = skByUid.get(uid);
        if (sk == null) {
            return null;
        }
        // last seen (SPEC 5.5): «online» while their app says it is in front
        if (inApp(sk)) {
            return LocaleController.getString(R.string.Online);
        }
        Long t = reachableAt.get(sk);
        if (near(shownState)) {
            // no internet: only the contacts beside this phone or on the same Wi-Fi are reachable
            String via = reachVia.get(sk);
            boolean fresh = t != null && System.currentTimeMillis() - t < REACHABLE_MS;
            if (fresh && "ble".equals(via)) {
                return LocaleController.getString(R.string.GerehPeerReachableBle);
            }
            if (fresh && "lan".equals(via)) {
                return LocaleController.getString(R.string.GerehPeerReachableLan);
            }
            return LocaleController.getString(R.string.WaitingForNetwork);
        }
        if (unreachable.containsKey(sk)) {
            return LocaleController.getString(noRelay.contains(sk) ? R.string.GerehPeerNoRelay : R.string.GerehPeerUnreachable);
        }
        // when their app last said it was in front or left it: Telegram's own «last seen …»
        Long seen = lastSeenAt.get(sk);
        if (seen != null && seen > 0) {
            return LocaleController.formatDateOnline(seen / 1000, null);
        }
        long now = System.currentTimeMillis();
        Long mailbox = mailboxAt.get(sk);
        if (t != null && t > 0 && now - t < REACHABLE_MS) {
            String via = reachVia.get(sk);
            return LocaleController.getString("relay".equals(via) ? R.string.GerehPeerReachableRelay
                    : "ble".equals(via) ? R.string.GerehPeerReachableBle
                    : "lan".equals(via) ? R.string.GerehPeerReachableLan : R.string.GerehPeerReachable);
        }
        // not reachable by a session now: if frames have been going through a Google mailbox since, say that
        // (a route, never «online»); else when a session last got through
        if (mailbox != null && mailbox > 0 && (t == null || mailbox > t) && now - mailbox < MAILBOX_MS) {
            return LocaleController.formatString(R.string.GerehPeerMailbox, ago(now - mailbox));
        }
        // their phone was last up when a session got through or a frame of theirs came through a
        // mailbox or Nostr, whichever is newer: a contact whose app is too old to say «online»
        // read «last reachable 2 days ago» after a chat through the Google relay that very day
        // (Ehsan, 28 Oct 2026, kairo)
        Long heardIn = mailboxInAt.get(sk);
        long heard = Math.max(t != null ? t : 0, heardIn != null ? heardIn : 0);
        if (heard <= 0) {
            return null;
        }
        return LocaleController.formatString(R.string.GerehPeerLastReached, ago(now - heard));
    }

    private static String ago(long ms) {
        long min = ms / 60000;
        if (min < 1) {
            return LocaleController.getString(R.string.GerehAgoNow);
        }
        if (min < 60) {
            return LocaleController.formatString(R.string.GerehAgoMinutes, (int) min);
        }
        if (min < 48 * 60) {
            return LocaleController.formatString(R.string.GerehAgoHours, (int) (min / 60));
        }
        return LocaleController.formatString(R.string.GerehAgoDays, (int) (min / (24 * 60)));
    }

    private static void refreshStatus() {
        AndroidUtilities.runOnUIThread(() -> org.telegram.messenger.NotificationCenter.getInstance(account)
                .postNotificationName(org.telegram.messenger.NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_STATUS));
    }

    /**
     * The pts after Telegram's own. Every update here is applied on the stage queue right after
     * this, so the sequence Telegram checks is always exactly one ahead and never needs the
     * server's "difference".
     */
    private static int nextPts() {
        return MessagesStorage.getInstance(account).getLastPtsValue() + 1;
    }

    // ---- for GerehGroups (groups share this "server") ----

    static int account() {
        return account;
    }

    static int nextPtsValue() {
        return nextPts();
    }

    static List<TLRPC.Message> pageOf(List<TLRPC.Message> newestFirst, int offsetId, int addOffset, int limit, int maxId, int minId) {
        return page(newestFirst, offsetId, addOffset, limit, maxId, minId);
    }

    static void rememberUser(long uid, String sk) {
        skByUid.put(uid, sk);
    }

    /**
     * The contact a one-to-one send goes to. Someone this phone has a card of but who is no
     * contact yet (a group member, or one who posted in an official chat with their card, SPEC
     * 18.4) becomes one now: the user opened a chat with them and wrote (Ehsan, 29 Oct 2026:
     * members of the official group could not write to each other). Null for no one this bridge
     * knows; a refusal that says so when their card is not here yet.
     */
    static Contact contactFor(ChatNode node, Map<Long, Contact> known, long uid) {
        Contact c = known != null ? known.get(uid) : null;
        if (c != null) {
            return c;
        }
        if (uid == UserConfig.getInstance(account()).getClientUserId()) {
            return selfContact(node); // Saved Messages
        }
        String sk = skForUser(uid);
        if (sk == null) {
            return null;
        }
        for (Contact x : node.contacts()) {
            if (x.getSk().equals(sk)) {
                return x;
            }
        }
        try {
            c = node.addMemberContact(sk);
        } catch (Throwable t) {
            throw new Refusal(LocaleController.getString(R.string.GerehNoMemberCard));
        }
        if (known != null) {
            known.put(uid, c);
        }
        return c;
    }

    /** The contact behind a Telegram user id, if they are one (nothing is added). */
    static Contact contactOf(ChatNode node, long uid) {
        for (Contact x : node.contacts()) {
            if (GerehIdentity.userIdFor(x.getSk()) == uid) {
                return x;
            }
        }
        return null;
    }

    /** Message `seq` of the chat with `c` as Telegram shows it, or null when it is not here. */
    static TLRPC.Message messageFor(ChatNode node, Contact c, long seq) {
        Message m = bySeq(node, c.getSk(), seq);
        return m == null ? null : toTl(m, c);
    }

    /** The sk behind a Telegram user id: a user this bridge made, or any contact. */
    static String skForUser(long uid) {
        String sk = skByUid.get(uid);
        if (sk != null) {
            return sk;
        }
        ChatNode node = GerehIdentity.node();
        if (node != null) {
            for (Contact c : node.contacts()) {
                if (GerehIdentity.userIdFor(c.getSk()) == uid) {
                    skByUid.put(uid, c.getSk());
                    return c.getSk();
                }
            }
        }
        return null;
    }

    private static void push(TLRPC.Update u, TLRPC.User user) {
        TLRPC.TL_updates ups = new TLRPC.TL_updates();
        ups.updates.add(u);
        if (user != null) {
            ups.users.add(user);
        }
        ups.date = now();
        ups.seq = 0;
        MessagesController.getInstance(account).processUpdates(ups, false);
        if (u instanceof TL_update.TL_updateNewMessage && ((TL_update.TL_updateNewMessage) u).message.id < GerehGroups.MSG_BASE) {
            markSynced(((TL_update.TL_updateNewMessage) u).message.id);
        }
    }

    // ---- requests from Telegram's screens -----------------------------------------------

    /** True when this request is one-to-one chat business; the answer comes through {@code done}. */
    public static boolean handles(TLObject req) {
        return req instanceof TLRPC.TL_contacts_getContacts
                || req instanceof TLRPC.TL_users_getUsers
                || req instanceof TLRPC.TL_users_getFullUser
                || req instanceof TLRPC.TL_messages_getDialogs
                || req instanceof TLRPC.TL_messages_getPeerDialogs
                || req instanceof TLRPC.TL_messages_getHistory
                || req instanceof TLRPC.TL_messages_sendMessage
                || req instanceof TLRPC.TL_messages_readHistory
                || req instanceof TLRPC.TL_messages_setTyping
                || req instanceof TLRPC.TL_messages_getPeerSettings
                || req instanceof TLRPC.TL_updates_getDifference
                || req instanceof TLRPC.TL_contacts_block
                || req instanceof TLRPC.TL_contacts_unblock
                || req instanceof TLRPC.TL_contacts_getBlocked
                || req instanceof TLRPC.TL_contacts_addContact
                || req instanceof TLRPC.TL_messages_deleteMessages
                || req instanceof TLRPC.TL_messages_editMessage
                || req instanceof TLRPC.TL_messages_deleteHistory
                || req instanceof TL_account.updateProfile
                || req instanceof TL_account.updateStatus
                || isLastSeenPrivacy(req)
                || GerehGroups.handles(req)
                || GerehMedia.handles(req)
                || GerehPhotos.handles(req)
                || GerehInvites.handles(req)
                || GerehReactions.handles(req)
                || GerehPolls.handles(req)
                || GerehPins.handles(req)
                || GerehFeeds.handles(req)
                || GerehTg.handles(req)
                || req instanceof TLRPC.TL_upload_getFile && GerehTg.ownsFile((TLRPC.TL_upload_getFile) req)
                || req instanceof TLRPC.TL_upload_getFile && GerehLocalFiles.ownsFile((TLRPC.TL_upload_getFile) req);
    }

    /** A request the core refuses for a reason the user can act on; Bridge shows its message. */
    public static final class Refusal extends RuntimeException {
        public Refusal(String message) {
            super(message);
        }
    }

    /**
     * Telegram cancels a send it has not had an answer for (the message still shows its clock)
     * when that message is deleted. If the core still has it queued, it goes from the core too
     * and never leaves the phone; one already on its way can still arrive.
     */
    public static void cancel(int token) {
        ChatNode node = GerehIdentity.node();
        if (node == null) {
            return;
        }
        for (Map.Entry<String, Pending> e : pending.entrySet()) {
            Pending p = e.getValue();
            if (p.token != token || !pending.remove(e.getKey(), p)) {
                continue;
            }
            try {
                Message row = find(node, p.peer, e.getKey());
                if (row != null && row.getState() == MessageState.QUEUED) {
                    node.deleteMessages(new long[]{row.getSeq()});
                }
                sentIds().edit().remove(Long.toString(p.randomId)).apply();
            } catch (Throwable t) {
                Log.e(TAG, "cancel failed: " + t.getClass().getSimpleName());
            }
            return;
        }
    }

    /**
     * Answers {@code req}. Returns the response for the caller to deliver, or null when the
     * answer is delivered later through {@code done} (sending, and requests that carry a pts).
     * Throws when the core cannot answer; the caller turns that into an error.
     */
    public static TLObject answer(int acc, TLObject req, RequestDelegate done, int token) throws Exception {
        ChatNode node = GerehIdentity.node();
        if (node == null) {
            throw new IllegalStateException("no identity");
        }
        if (req instanceof TLRPC.TL_updates_getDifference) {
            return difference(node, acc);
        }
        // «مقاله»: Telegram's rich editor sends its blocks beside an empty text; they go as that text
        // with its formatting, in every kind of chat (GerehRich)
        if (req instanceof TLRPC.TL_messages_sendMessage) {
            GerehRich.flatten((TLRPC.TL_messages_sendMessage) req);
        }
        // a popular Telegram channel copied onto this phone (T68): its own ids, before every other
        // chat's path, which would not find them
        if (GerehTg.handles(req)) {
            return GerehTg.answer(acc, req, done);
        }
        // files (SPEC 12, 17): uploads, sending, downloads
        if (req instanceof TLRPC.TL_upload_saveFilePart || req instanceof TLRPC.TL_upload_saveBigFilePart) {
            return GerehMedia.savePart(req);
        }
        if (req instanceof TLRPC.TL_upload_getFile && GerehTg.ownsFile((TLRPC.TL_upload_getFile) req)) {
            return GerehTg.getFile((TLRPC.TL_upload_getFile) req, done);
        }
        if (req instanceof TLRPC.TL_upload_getFile && GerehLocalFiles.ownsFile((TLRPC.TL_upload_getFile) req)) {
            // a picture from a file here: a found chat's photo, a tile's card (GerehLocalFiles)
            return GerehLocalFiles.getFile((TLRPC.TL_upload_getFile) req);
        }
        if (req instanceof TLRPC.TL_upload_getFile) {
            return GerehMedia.getFile(node, (TLRPC.TL_upload_getFile) req, done);
        }
        // pinned messages, in every kind of chat (T65)
        if (GerehPins.handles(req)) {
            return GerehPins.answer(node, req);
        }
        // reactions, in every kind of chat
        if (GerehReactions.handles(req)) {
            return GerehReactions.answer(node, req);
        }
        // polls, votes and results in groups and channels (m4, T69)
        if (GerehPolls.handles(req)) {
            return GerehPolls.answer(node, req, done);
        }
        // the official channel and group (T38): before groups, whose ids they would not find, and
        // before files: a photo posted there went to the groups' path and was refused as an
        // unknown group (Ehsan, 25 Oct 2026)
        if (GerehFeeds.handles(req)) {
            return GerehFeeds.answer(acc, node, req, done);
        }
        if (req instanceof TLRPC.TL_messages_sendMedia) {
            return GerehMedia.sendMedia(node, (TLRPC.TL_messages_sendMedia) req, done, token);
        }
        // several files at once (an album): each handed over first, then sent as one
        if (req instanceof TLRPC.TL_messages_uploadMedia) {
            return GerehMedia.uploadMedia((TLRPC.TL_messages_uploadMedia) req);
        }
        if (req instanceof TLRPC.TL_messages_sendMultiMedia) {
            return GerehMedia.sendMultiMedia(node, (TLRPC.TL_messages_sendMultiMedia) req, done);
        }
        // invite links (SPEC 7.4)
        if (GerehInvites.handles(req)) {
            return GerehInvites.answer(acc, node, req, done);
        }
        // profile, group and channel photos (SPEC 10, 7.2)
        if (GerehPhotos.handles(req)) {
            return GerehPhotos.answer(acc, node, req);
        }
        // groups (SPEC 7): their own requests, and the shared ones whose peer is a group
        try {
            return GerehGroups.answer(node, req, done);
        } catch (GerehGroups.NotGroup ignored) {
            // a one-to-one request: below
        }
        if (req instanceof TLRPC.TL_messages_setTyping) {
            // «typing…», «sending a photo…» in a one-to-one chat (SPEC 5.4, T56): to the contact's
            // phone over a route open now, or not at all; the core keeps the pace (one per 5 s).
            // A group's (one-to-one only) and actions Gereh does not carry are not sent.
            TLRPC.TL_messages_setTyping q = (TLRPC.TL_messages_setTyping) req;
            String kind = actionKind(q.action);
            long uid = peerUserId(q.peer);
            String sk = kind != null && uid != 0 ? skForUser(uid) : null;
            if (sk != null) {
                Utilities.globalQueue.postRunnable(() -> {
                    try {
                        node.chatAction(sk, kind);
                    } catch (Throwable t) {
                        Log.w(TAG, "chat action: " + t.getClass().getSimpleName());
                    }
                });
            }
            return new TLRPC.TL_boolTrue();
        }
        if (req instanceof TL_account.updateStatus) {
            // Telegram's «in the app» about every 55 s while in front, and «not any more» (SPEC 5.5):
            // contacts with a route open to this phone hear it, unless our last seen is hidden
            boolean online = !((TL_account.updateStatus) req).offline;
            GerehNet.setFront(online);
            Utilities.globalQueue.postRunnable(() -> {
                try {
                    node.setOnline(online);
                } catch (Throwable t) {
                    Log.w(TAG, "presence: " + t.getClass().getSimpleName());
                }
            });
            return new TLRPC.TL_boolTrue();
        }
        if (isLastSeenPrivacy(req)) {
            return lastSeenPrivacy(node, req);
        }
        if (req instanceof TL_account.updateProfile) {
            // The Account page's name is the Gereh name: the core renames the identity (the four
            // digits stay) and sends the new card to every contact. The bio is not kept (no
            // profiles yet; UserInfoActivity no longer asks for one).
            TL_account.updateProfile q = (TL_account.updateProfile) req;
            String name = ((q.first_name != null ? q.first_name : "") + " " + (q.last_name != null ? q.last_name : ""))
                    .trim().replaceAll("\\s+", " ");
            if (name.isEmpty() || name.contains("·") || name.getBytes(StandardCharsets.UTF_8).length > 64) {
                throw new Refusal(LocaleController.getString(R.string.GerehNameRule));
            }
            Me me = node.getMe();
            if (!name.equals(me.getName())) {
                me = node.setName(name);
            }
            GerehIdentity.activate(acc, me);
            AndroidUtilities.runOnUIThread(() -> {
                org.telegram.messenger.NotificationCenter nc = org.telegram.messenger.NotificationCenter.getInstance(acc);
                nc.postNotificationName(org.telegram.messenger.NotificationCenter.mainUserInfoChanged);
                nc.postNotificationName(org.telegram.messenger.NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_NAME);
            });
            return UserConfig.getInstance(acc).getCurrentUser();
        }
        if (req instanceof TLRPC.TL_messages_getPeerSettings) {
            TLRPC.TL_messages_peerSettings r = new TLRPC.TL_messages_peerSettings();
            r.settings = new TLRPC.TL_peerSettings();
            return r;
        }
        List<Contact> contacts = node.contacts();
        Map<Long, Contact> byUid = new HashMap<>();
        for (Contact c : contacts) {
            byUid.put(GerehIdentity.userIdFor(c.getSk()), c);
        }
        // Saved Messages (p3, Ehsan, 30 Oct 2026: a message there got «!»): the chat with oneself
        // is answered like a contact's, and the core keeps it on this phone (send_envelope); never
        // in the contact list, which comes from `contacts`
        Contact self = selfContact(node);
        byUid.put(GerehIdentity.userIdFor(self.getSk()), self);
        if (req instanceof TLRPC.TL_contacts_getContacts) {
            TLRPC.TL_contacts_contacts r = new TLRPC.TL_contacts_contacts();
            for (Contact c : contacts) {
                if (c.getBlocked()) {
                    continue;
                }
                TLRPC.TL_contact tc = new TLRPC.TL_contact();
                tc.user_id = GerehIdentity.userIdFor(c.getSk());
                tc.mutual = !c.getByThem();
                r.contacts.add(tc);
                r.users.add(userFor(c));
            }
            r.saved_count = 0;
            return r;
        }
        if (req instanceof TLRPC.TL_users_getUsers) {
            ArrayList<TLRPC.User> users = new ArrayList<>();
            for (TLRPC.InputUser iu : ((TLRPC.TL_users_getUsers) req).id) {
                TLRPC.User u = userForInput(acc, iu, byUid);
                if (u != null) {
                    users.add(u);
                }
            }
            return roundTripVector(req, users);
        }
        if (req instanceof TLRPC.TL_contacts_block || req instanceof TLRPC.TL_contacts_unblock) {
            // Blocking stays on this phone (SPEC 10): their frames are dropped, they are not told.
            boolean block = req instanceof TLRPC.TL_contacts_block;
            TLRPC.InputPeer p = block ? ((TLRPC.TL_contacts_block) req).id : ((TLRPC.TL_contacts_unblock) req).id;
            Contact c = byUid.get(peerUserId(p));
            if (c == null) {
                throw new IllegalArgumentException("not a contact");
            }
            node.setBlocked(c.getSk(), block);
            return new TLRPC.TL_boolTrue();
        }
        if (req instanceof TLRPC.TL_contacts_getBlocked) {
            TLRPC.TL_contacts_blocked r = new TLRPC.TL_contacts_blocked();
            for (Contact c : contacts) {
                if (c.getBlocked()) {
                    TLRPC.TL_peerBlocked pb = new TLRPC.TL_peerBlocked();
                    TLRPC.TL_peerUser pu = new TLRPC.TL_peerUser();
                    pu.user_id = GerehIdentity.userIdFor(c.getSk());
                    pb.peer_id = pu;
                    pb.date = now();
                    r.blocked.add(pb);
                    r.users.add(userFor(c));
                }
            }
            r.count = r.blocked.size();
            return r;
        }
        if (req instanceof TLRPC.TL_contacts_addContact) {
            // «Add contact» on a group member's profile: their card from the group. The bridge
            // answered with an error before, which Telegram drops after its screen has already
            // said «saved», so nothing was added (Dr.Net's owner, 26 Oct 2026, both apps). The
            // name typed on that screen is not kept: a contact's name is the one in their card.
            TLRPC.InputUser iu = ((TLRPC.TL_contacts_addContact) req).id;
            long uid = iu instanceof TLRPC.TL_inputUser ? ((TLRPC.TL_inputUser) iu).user_id : 0;
            Contact known = byUid.get(uid);
            String sk = known != null ? known.getSk() : skForUser(uid);
            Contact c = null;
            if (sk != null) {
                try {
                    c = node.addMemberContact(sk);
                } catch (Throwable t) {
                    Log.w(TAG, "add contact: " + t.getClass().getSimpleName());
                }
            }
            if (c == null) {
                // Telegram shows nothing for this error and has already said «saved»: say what is true
                String text = LocaleController.getString(R.string.GerehNoMemberCard);
                AndroidUtilities.runOnUIThread(() -> android.widget.Toast.makeText(ApplicationLoader.applicationContext, text, android.widget.Toast.LENGTH_LONG).show());
                throw new Refusal(text);
            }
            TLRPC.TL_updates r = new TLRPC.TL_updates();
            r.users.add(userFor(c));
            r.date = now();
            return r;
        }
        if (req instanceof TLRPC.TL_users_getFullUser) {
            TLRPC.User u = userForInput(acc, ((TLRPC.TL_users_getFullUser) req).id, byUid);
            if (u == null) {
                throw new IllegalArgumentException("unknown user");
            }
            TLRPC.TL_users_userFull r = new TLRPC.TL_users_userFull();
            TLRPC.TL_userFull f = new TLRPC.TL_userFull();
            f.id = u.id;
            f.settings = new TLRPC.TL_peerSettings();
            f.notify_settings = GerehNotify.of(f.id);
            TLRPC.TL_inputPeerUser pinPeer = new TLRPC.TL_inputPeerUser();
            pinPeer.user_id = f.id;
            int pin = GerehPins.newest(node, pinPeer);
            if (pin != 0) {
                f.pinned_msg_id = pin;
                f.flags |= 64;
            }
            f.common_chats_count = 0;
            Contact fc = byUid.get(u.id);
            f.blocked = fc != null && fc.getBlocked();
            // ChatActivity offers «Pin» in a one-to-one chat only with it (T65)
            f.can_pin_message = fc != null;
            String fsk = u.id == UserConfig.getInstance(acc).getClientUserId() ? node.getMe().getSk() : skForUser(u.id);
            if (fsk != null) {
                GerehPhotos.dressFull(f, fsk);
            }
            r.full_user = f;
            r.users.add(u);
            return r;
        }
        if (req instanceof TLRPC.TL_messages_getDialogs) {
            TLRPC.TL_messages_getDialogs q = (TLRPC.TL_messages_getDialogs) req;
            TLRPC.TL_messages_dialogs r = new TLRPC.TL_messages_dialogs();
            // Every dialog fits in the first page; the archive (folder 1) is empty.
            if (q.folder_id == 0 && q.offset_date == 0 && q.offset_id == 0) {
                for (Contact c : contacts) {
                    addDialog(node, c, r.dialogs, r.messages, r.users, false);
                }
                addDialog(node, self, r.dialogs, r.messages, r.users, false); // Saved Messages, once it holds one
                GerehGroups.addDialogs(node, r.dialogs, r.messages, r.chats, r.users, null);
                GerehFeeds.addDialogs(node, r.dialogs, r.messages, r.chats, r.users, null);
                GerehTg.addDialogs(r.dialogs, r.messages, r.chats, null);
                GerehFeeds.dropStale(acc);
            }
            return r;
        }
        if (req instanceof TLRPC.TL_messages_getPeerDialogs) {
            TLRPC.TL_messages_peerDialogs r = new TLRPC.TL_messages_peerDialogs();
            java.util.Set<Long> chats = new java.util.HashSet<>();
            for (TLRPC.InputDialogPeer idp : ((TLRPC.TL_messages_getPeerDialogs) req).peers) {
                if (idp instanceof TLRPC.TL_inputDialogPeer) {
                    TLRPC.InputPeer ip = ((TLRPC.TL_inputDialogPeer) idp).peer;
                    if (GerehGroups.chatOf(ip) != 0) {
                        chats.add(GerehGroups.chatOf(ip));
                        continue;
                    }
                    Contact c = byUid.get(peerUserId(ip));
                    if (c != null) {
                        addDialog(node, c, r.dialogs, r.messages, r.users, true);
                    }
                }
            }
            if (!chats.isEmpty()) {
                GerehGroups.addDialogs(node, r.dialogs, r.messages, r.chats, r.users, chats);
                GerehFeeds.addDialogs(node, r.dialogs, r.messages, r.chats, r.users, chats);
                GerehTg.addDialogs(r.dialogs, r.messages, r.chats, chats);
            }
            TLRPC.TL_updates_state st = new TLRPC.TL_updates_state();
            st.pts = MessagesStorage.getInstance(acc).getLastPtsValue();
            st.date = now();
            st.seq = 0;
            r.state = st;
            return r;
        }
        if (req instanceof TLRPC.TL_messages_getHistory) {
            TLRPC.TL_messages_getHistory q = (TLRPC.TL_messages_getHistory) req;
            TLRPC.TL_messages_messages r = new TLRPC.TL_messages_messages();
            Contact c = byUid.get(peerUserId(q.peer));
            if (c != null) {
                r.messages.addAll(historyPage(node, c, q.offset_id, q.add_offset, q.limit, q.max_id, q.min_id));
                r.users.add(userFor(c));
            }
            return r;
        }
        if (req instanceof TLRPC.TL_messages_readHistory) {
            TLRPC.TL_messages_readHistory q = (TLRPC.TL_messages_readHistory) req;
            Contact c = byUid.get(peerUserId(q.peer));
            if (c != null) {
                node.markRead(c.getSk());
            }
            Utilities.stageQueue.postRunnable(() -> {
                TLRPC.TL_messages_affectedMessages r = new TLRPC.TL_messages_affectedMessages();
                r.pts = nextPts();
                r.pts_count = 1;
                done.run(roundTripResponse(req, r), null);
            });
            return null;
        }
        if (req instanceof TLRPC.TL_messages_deleteMessages) {
            // A one-to-one message's id is its seq, a group's GerehGroups.MSG_BASE + its seq. With
            // «delete for everyone» (revoke) the other phones take their copies down too (SPEC 5.2;
            // Ehsan, 25 Oct 2026: «باید برای همه حذف بشه»), else on this phone only. A group
            // message used to go down the one-to-one path and nowhere, so it came back. Telegram's
            // own negative ids (unsent messages) are not the core's and are taken back by cancel().
            TLRPC.TL_messages_deleteMessages q = (TLRPC.TL_messages_deleteMessages) req;
            long[] seqs = new long[q.id.size()];
            long[] groupSeqs = new long[q.id.size()];
            int n = 0;
            int g = 0;
            for (Integer id : q.id) {
                if (id == null || id <= 0) {
                    continue;
                }
                if (id >= GerehGroups.MSG_BASE) {
                    groupSeqs[g++] = id - GerehGroups.MSG_BASE;
                } else if (id < GerehGroups.CREATED_BASE) {
                    seqs[n++] = id;
                }
            }
            if (n > 0) {
                long[] list = java.util.Arrays.copyOf(seqs, n);
                if (q.revoke) {
                    node.deleteForEveryone(list);
                } else {
                    node.deleteMessages(list);
                }
            }
            if (g > 0) {
                node.groupDeleteSeqs(java.util.Arrays.copyOf(groupSeqs, g), q.revoke);
            }
            Utilities.stageQueue.postRunnable(() -> {
                TLRPC.TL_messages_affectedMessages r = new TLRPC.TL_messages_affectedMessages();
                r.pts = nextPts();
                r.pts_count = 1;
                done.run(roundTripResponse(req, r), null);
            });
            return null;
        }
        if (req instanceof TLRPC.TL_messages_deleteHistory) {
            // «Clear history» and «Delete chat», on this phone only, up to the last message
            // Telegram has shown: one that arrived since stays. The contact stays a contact.
            TLRPC.TL_messages_deleteHistory q = (TLRPC.TL_messages_deleteHistory) req;
            Contact c = byUid.get(peerUserId(q.peer));
            if (c != null) {
                node.clearHistory(c.getSk(), q.max_id);
            }
            Utilities.stageQueue.postRunnable(() -> {
                TLRPC.TL_messages_affectedHistory r = new TLRPC.TL_messages_affectedHistory();
                r.pts = nextPts();
                r.pts_count = 1;
                r.offset = 0;
                done.run(roundTripResponse(req, r), null);
            });
            return null;
        }
        if (req instanceof TLRPC.TL_messages_sendMessage) {
            TLRPC.TL_messages_sendMessage q = (TLRPC.TL_messages_sendMessage) req;
            Contact c = contactFor(node, byUid, peerUserId(q.peer));
            if (c == null) {
                throw new IllegalArgumentException("not a contact");
            }
            if (TextUtils.isEmpty(q.message)) {
                throw new IllegalArgumentException("empty message");
            }
            // A retry of the same message (Telegram keeps its random_id) waits for the copy the
            // core already queued instead of queueing a second one.
            String id = sentIds().getString(Long.toString(q.random_id), null);
            if (id == null) {
                // A one-to-one message's Telegram id is its seq (toTl), so the answered one's is too.
                id = node.sendText(c.getSk(), q.message, replyMsgId(q.reply_to), GerehEntities.toCore(q.entities));
                sentIds().edit().putString(Long.toString(q.random_id), id).apply();
            }
            pending.put(id, new Pending(c.getSk(), q.random_id, done, token));
            Message row = find(node, c.getSk(), id);
            if (row != null && row.getState().compareTo(MessageState.QUEUED) > 0) {
                onState(node, c.getSk(), id, row.getState()); // it got there before we listened
            }
            return null;
        }
        if (req instanceof TLRPC.TL_messages_editMessage) {
            // Edit (SPEC 5): a one-to-one message's Telegram id is its seq (toTl). Text and a
            // caption only - a new photo in place of the old is not an edit Gereh carries.
            TLRPC.TL_messages_editMessage q = (TLRPC.TL_messages_editMessage) req;
            Contact c = byUid.get(peerUserId(q.peer));
            if (c == null) {
                throw new IllegalArgumentException("not a contact");
            }
            if (q.media != null || q.message == null) {
                throw new IllegalArgumentException("only the text is edited");
            }
            node.editText(q.id, q.message, GerehEntities.toCore(q.entities));
            Message row = bySeq(node, c.getSk(), q.id);
            TLRPC.TL_updates ups = new TLRPC.TL_updates();
            if (row != null) {
                TL_update.TL_updateEditMessage u = new TL_update.TL_updateEditMessage();
                u.message = toTl(row, c);
                u.pts = nextPts();
                u.pts_count = 1;
                ups.updates.add(u);
                ups.users.add(userFor(c));
            }
            ups.date = now();
            ups.seq = 0;
            return ups;
        }
        throw new IllegalStateException("not handled");
    }

    /** A one-to-one message of the chat with [sk] by this phone's seq (its Telegram id); null when it is not there. */
    private static Message bySeq(ChatNode node, String sk, long seq) {
        return node.message(sk, "", seq);
    }

    /**
     * Telegram's page of the chat with [c] (messages.getHistory), read from the core a page at a
     * time. Before, every page read the chat's newest 1000 messages and cut the page out of
     * them, on the queue that also carries every new message and tick: the longer the chat, the
     * slower everything, and nothing older than the newest 1000 could be shown (Ehsan, 28 Oct
     * 2026: «کندی چت شخصی نسبت به گروه»). The window holds what page() looks at: up to -addOffset
     * messages from offsetId on, then older ones until the page is full or the chat begins.
     */
    private static List<TLRPC.Message> historyPage(ChatNode node, Contact c, int offsetId, int addOffset, int limit, int maxId, int minId) {
        int want = Math.max(1, limit);
        List<TLRPC.Message> window = new ArrayList<>(); // newest first, as the server sends them
        if (offsetId != 0 && addOffset < 0) {
            List<Message> newer = shown(node.historyPage(c.getSk(), 0, offsetId - 1, -addOffset + PAGE_SLACK));
            for (int i = Math.min(newer.size(), -addOffset) - 1; i >= 0; i--) {
                window.add(toTl(newer.get(i), c));
            }
        }
        long before = offsetId;
        int ask = Math.max(0, addOffset) + want + PAGE_SLACK;
        while (true) {
            List<Message> rows = node.historyPage(c.getSk(), before, -1, ask);
            for (int i = rows.size() - 1; i >= 0; i--) {
                if (isShown(rows.get(i))) {
                    window.add(toTl(rows.get(i), c));
                }
            }
            List<TLRPC.Message> out = page(window, offsetId, addOffset, limit, maxId, minId);
            // full, the chat's first message reached, or nothing older can pass min_id
            if (out.size() >= want || rows.size() < ask || minId != 0 && rows.get(0).getSeq() <= minId) {
                return out;
            }
            before = rows.get(0).getSeq();
            ask = want + PAGE_SLACK;
        }
    }

    /** The newest message of the chat with [sk] that Telegram shows; null for none. */
    private static Message newestShown(ChatNode node, String sk) {
        long before = 0;
        for (int round = 0; round < 50; round++) {
            List<Message> rows = node.historyPage(sk, before, -1, PAGE_SLACK);
            for (int i = rows.size() - 1; i >= 0; i--) {
                if (isShown(rows.get(i))) {
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

    /** The other side edited a message of the chat with [sk] (SPEC 5 `Edit`): Telegram's copy is replaced. */
    private static void onEdited(ChatNode node, String sk, long seq) {
        Contact c = contact(node, sk);
        Message row = c == null ? null : bySeq(node, sk, seq);
        if (row == null) {
            return;
        }
        final TLRPC.Message msg = toTl(row, c);
        final TLRPC.User user = userFor(c);
        Utilities.stageQueue.postRunnable(() -> {
            TL_update.TL_updateEditMessage u = new TL_update.TL_updateEditMessage();
            u.message = msg;
            u.pts = nextPts();
            u.pts_count = 1;
            push(u, user);
        });
    }

    /**
     * Every shown message Telegram has not had yet (its core number above the last one handed
     * over), as Telegram's "difference". Messages arrive while the chat's screens are closed, or
     * before they listen; this is how they reach Telegram's own database and the chat list.
     */
    private static TLObject difference(ChatNode node, int acc) {
        long synced = syncedSeq();
        long max = synced;
        TLRPC.TL_updates_difference d = new TLRPC.TL_updates_difference();
        // only what came after the last one handed over, from every chat at once: before, each
        // contact's newest 1000 messages were read on every start and every return to the
        // app, holding Telegram's queue longer the more and the longer the chats
        Map<String, Contact> contacts = new HashMap<>();
        for (Contact c : node.contacts()) {
            contacts.put(c.getSk(), c);
        }
        Set<String> named = new HashSet<>();
        List<Message> since = node.messagesSince(synced, DIFFERENCE_MAX);
        for (Message m : shown(since)) {
            Contact c = contacts.get(m.getPeer());
            if (c == null) {
                continue; // no longer a contact
            }
            d.new_messages.add(toTl(m, c));
            max = Math.max(max, m.getSeq());
            if (named.add(c.getSk())) {
                d.users.add(userFor(c));
            }
        }
        boolean more = since.size() >= DIFFERENCE_MAX | GerehGroups.addDifference(node, d);
        if (d.new_messages.isEmpty()) {
            TLRPC.TL_updates_differenceEmpty e = new TLRPC.TL_updates_differenceEmpty();
            e.date = now();
            e.seq = 0;
            return e;
        }
        TLRPC.TL_updates_state st = new TLRPC.TL_updates_state();
        st.pts = MessagesStorage.getInstance(acc).getLastPtsValue() + 1;
        st.date = now();
        st.seq = 0;
        st.qts = 0;
        markSynced(max);
        Log.i(TAG, "difference: " + d.new_messages.size() + " messages" + (more ? ", more to come" : ""));
        if (more) {
            // the rest in the next round: Telegram asks again at once for a slice
            TLRPC.TL_updates_differenceSlice s = new TLRPC.TL_updates_differenceSlice();
            s.new_messages = d.new_messages;
            s.new_encrypted_messages = d.new_encrypted_messages;
            s.other_updates = d.other_updates;
            s.chats = d.chats;
            s.users = d.users;
            s.intermediate_state = st;
            return s;
        }
        d.state = st;
        return d;
    }

    private static SharedPreferences syncPrefs() {
        // The highest core message number handed to Telegram: a number only.
        return ApplicationLoader.applicationContext.getSharedPreferences("gereh_sync", Context.MODE_PRIVATE);
    }

    private static long syncedSeq() {
        return syncPrefs().getLong("seq_" + account, 0);
    }

    private static synchronized void markSynced(long seq) {
        if (seq > syncedSeq()) {
            syncPrefs().edit().putLong("seq_" + account, seq).apply();
        }
    }

    // ---- building Telegram objects -----------------------------------------------------

    private static void addDialog(ChatNode node, Contact c, ArrayList<TLRPC.Dialog> dialogs, ArrayList<TLRPC.Message> messages, ArrayList<TLRPC.User> users, boolean evenEmpty) {
        // the last message, and the counts the core keeps: the chat itself is not read
        Message top = newestShown(node, c.getSk());
        users.add(userFor(c));
        if (top == null && !evenEmpty) {
            return;
        }
        ChatMarks marks = node.chatMarks(c.getSk());
        TLRPC.TL_dialog d = new TLRPC.TL_dialog();
        TLRPC.TL_peerUser pu = new TLRPC.TL_peerUser();
        pu.user_id = GerehIdentity.userIdFor(c.getSk());
        d.peer = pu;
        d.top_message = top != null ? (int) top.getSeq() : 0;
        d.read_outbox_max_id = (int) marks.getReadOut();
        d.read_inbox_max_id = (int) marks.getReadIn();
        d.unread_count = marks.getUnread();
        d.unread_reactions_count = GerehReactions.unseenIn(c.getSk());
        d.notify_settings = GerehNotify.of(pu.user_id);
        dialogs.add(d);
        if (top != null) {
            messages.add(toTl(top, c));
        }
    }

    /** What Telegram may show: text messages, without our own still-queued ones (those keep
     * Telegram's own clock until the core says they are out of this phone). */
    private static List<Message> shown(List<Message> rows) {
        List<Message> out = new ArrayList<>();
        for (Message m : rows) {
            if (isShown(m)) {
                out.add(m);
            }
        }
        return out;
    }

    private static boolean isShown(Message m) {
        return (m.getText() != null || m.getMedia() != null) && !(m.getOutgoing() && m.getState() == MessageState.QUEUED);
    }

    private static List<TLRPC.Message> page(List<TLRPC.Message> newestFirst, int offsetId, int addOffset, int limit, int maxId, int minId) {
        int start = 0;
        if (offsetId != 0) {
            start = newestFirst.size();
            for (int i = 0; i < newestFirst.size(); i++) {
                if (newestFirst.get(i).id < offsetId) {
                    start = i;
                    break;
                }
            }
        }
        start = Math.max(0, start + addOffset);
        List<TLRPC.Message> out = new ArrayList<>();
        for (int i = start; i < newestFirst.size() && out.size() < Math.max(1, limit); i++) {
            TLRPC.Message m = newestFirst.get(i);
            if ((maxId == 0 || m.id < maxId) && (minId == 0 || m.id > minId)) {
                out.add(m);
            }
        }
        return out;
    }

    private static TLRPC.Message toTl(Message m, Contact c) {
        long self = UserConfig.getInstance(account).getClientUserId();
        long other = GerehIdentity.userIdFor(c.getSk());
        TLRPC.TL_message msg = new TLRPC.TL_message();
        msg.id = (int) m.getSeq();
        msg.out = m.getOutgoing();
        TLRPC.TL_peerUser peer = new TLRPC.TL_peerUser();
        peer.user_id = other;
        msg.peer_id = peer;
        if (m.getOutgoing()) {
            TLRPC.TL_peerUser from = new TLRPC.TL_peerUser();
            from.user_id = self;
            msg.from_id = from;
            msg.flags |= 256; // from_id
        }
        msg.date = (int) (m.getAtMs() / 1000);
        msg.message = m.getText() != null ? GerehInvites.shown(m.getText()) : "";
        if (m.getMedia() != null) {
            msg.media = GerehMedia.toTl(m.getMedia(), (int) (m.getAtMs() / 1000));
            msg.flags |= 512;
            msg.message = m.getMedia().getCaption();
            GerehMedia.dressAlbum(msg, m.getMedia());
        }
        GerehEntities.dress(msg, m.getEntities(), m.getMedia() != null ? m.getMedia().getCaption() : m.getText());
        if (m.getEditedMs() > 0) {
            // «edited» beside the time, as Telegram shows a server's edited message
            msg.edit_date = (int) (m.getEditedMs() / 1000);
            msg.flags |= TLRPC.MESSAGE_FLAG_EDITED;
        }
        msg.dialog_id = other;
        if (msg.out) {
            msg.flags |= 2; // the bit Telegram's own parser sets for out
        }
        if (m.getReplyTo() > 0) {
            setReply(msg, (int) m.getReplyTo());
        }
        ChatNode node = GerehIdentity.node();
        if (node != null) {
            GerehReactions.dressFor(msg, node, c.getSk(), false, m.getId(), true);
        }
        GerehConfigs.dress(msg); // configs in it: «سنجش» and «انتقال» (n5)
        // Built in memory, not round-tripped: TL_message.serializeToStream appends a local-only
        // attach path (for Telegram's database) that its parser never reads back, so a round trip
        // would shift every field after it.
        return msg;
    }

    /**
     * The Telegram id of the message a request answers (`reply_to` of messages.sendMessage or
     * sendMedia); 0 for none, and for an answer to anything but a message (a story).
     */
    static int replyMsgId(TLRPC.InputReplyTo r) {
        return r instanceof TLRPC.TL_inputReplyToMessage ? r.reply_to_msg_id : 0;
    }

    /**
     * Marks a message as an answer to message `replyId` of its chat (its Telegram id). Telegram
     * draws the quote above it from its own copy of that message. SPEC 5 `reply_to`: the core
     * gives the seq of the answered message on this phone (Message.replyTo).
     */
    static void setReply(TLRPC.Message msg, int replyId) {
        TLRPC.TL_messageReplyHeader h = new TLRPC.TL_messageReplyHeader();
        h.flags |= 16; // reply_to_msg_id
        h.reply_to_msg_id = replyId;
        msg.reply_to = h;
        msg.flags |= TLRPC.MESSAGE_FLAG_REPLY;
    }

    /** Oneself as a contact, for Saved Messages only (answer, `byUid`). */
    static Contact selfContact(ChatNode node) {
        org.mlmchat.core.Me me = node.getMe();
        return new Contact(me.getSk(), me.getName(), me.getDisplayName(), false, false, true, 0L);
    }

    /** A contact as a Telegram user: their chosen name, and their Gereh name where Telegram keeps a phone. */
    public static TLRPC.User userFor(Contact c) {
        TLRPC.User current = UserConfig.getInstance(account()).getCurrentUser();
        if (current != null && current.id == GerehIdentity.userIdFor(c.getSk())) {
            return current; // Saved Messages: Telegram's own user, `self` kept (UserObject.isUserSelf)
        }
        TLRPC.TL_user u = new TLRPC.TL_user();
        u.id = GerehIdentity.userIdFor(c.getSk());
        skByUid.put(u.id, c.getSk());
        u.access_hash = 0;
        u.flags |= 1;
        u.first_name = TextUtils.isEmpty(c.getName()) ? c.getDisplayName() : c.getName();
        u.flags |= 2;
        u.phone = c.getDisplayName();
        u.flags |= 16;
        u.contact = true;
        u.mutual_contact = !c.getByThem();
        // an admin of the official channel or group: the blue tick, in every chat (GerehFeeds)
        u.verified = GerehFeeds.isOfficialAdmin(c.getSk());
        // what their app last said of itself (SPEC 5.5): the green dot and «online»
        u.status = statusOf(c.getSk());
        u.flags |= 64;
        GerehPhotos.dress(u, c.getSk());
        return (TLRPC.User) roundTripObject(u);
    }

    /**
     * A contact's, a group member's or our own user changed (their photo): Telegram's copy is
     * replaced and every avatar of them drawn again.
     */
    static void refreshUser(int acc, String sk) {
        ChatNode node = GerehIdentity.node();
        if (node == null) {
            return;
        }
        TLRPC.User user = null;
        try {
            if (sk.equals(node.getMe().getSk())) {
                user = UserConfig.getInstance(acc).getCurrentUser();
            } else {
                Contact c = contact(node, sk);
                if (c != null) {
                    user = userFor(c);
                } else {
                    for (org.mlmchat.core.Group g : node.groups()) {
                        for (org.mlmchat.core.GroupMember m : g.getMembers()) {
                            if (m.getSk().equals(sk)) {
                                user = GerehGroups.userFor(m, new HashMap<>());
                                break;
                            }
                        }
                        if (user != null) {
                            break;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "refresh user: " + t.getClass().getSimpleName());
        }
        if (user == null) {
            return;
        }
        final TLRPC.User u = user;
        ArrayList<TLRPC.User> list = new ArrayList<>();
        list.add(u);
        MessagesStorage.getInstance(acc).putUsersAndChats(list, null, false, true);
        AndroidUtilities.runOnUIThread(() -> {
            MessagesController.getInstance(acc).putUser(u, false);
            org.telegram.messenger.NotificationCenter nc = org.telegram.messenger.NotificationCenter.getInstance(acc);
            nc.postNotificationName(org.telegram.messenger.NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_AVATAR);
            if (u.self) {
                nc.postNotificationName(org.telegram.messenger.NotificationCenter.mainUserInfoChanged);
            }
        });
    }

    private static TLRPC.User userForInput(int acc, TLRPC.InputUser iu, Map<Long, Contact> byUid) {
        if (iu instanceof TLRPC.TL_inputUserSelf) {
            return UserConfig.getInstance(acc).getCurrentUser();
        }
        long uid = iu instanceof TLRPC.TL_inputUser ? ((TLRPC.TL_inputUser) iu).user_id : 0;
        if (uid == UserConfig.getInstance(acc).getClientUserId()) {
            return UserConfig.getInstance(acc).getCurrentUser();
        }
        Contact c = byUid.get(uid);
        return c != null ? userFor(c) : null;
    }

    static long peerUser(TLRPC.InputPeer p) {
        return peerUserId(p);
    }

    /** A file message to a contact: answered, like text, when it reaches their phone. */
    /** Files sent to a contact together (GerehMedia.sendMultiMedia), by core message id. */
    private static final Map<String, PendingAll> pendingAll = new ConcurrentHashMap<>();

    private static final class PendingAll {
        final String sk;
        final List<String> ids;
        final List<Long> randomIds;
        final RequestDelegate done;
        final java.util.Set<String> out = ConcurrentHashMap.newKeySet();
        final java.util.concurrent.atomic.AtomicBoolean answered = new java.util.concurrent.atomic.AtomicBoolean();

        PendingAll(String sk, List<String> ids, List<Long> randomIds, RequestDelegate done) {
            this.sk = sk;
            this.ids = ids;
            this.randomIds = randomIds;
            this.done = done;
        }
    }

    /**
     * Several files sent to a contact together: Telegram is answered once for them all, when every
     * one has left this phone (one check, honest, as for a single message), or with the failure.
     */
    static void awaitSentAll(ChatNode node, String sk, List<String> ids, List<Long> randomIds, RequestDelegate done) {
        PendingAll p = new PendingAll(sk, ids, randomIds, done);
        for (String id : ids) {
            pendingAll.put(id, p);
        }
        for (String id : ids) {
            Message row = find(node, sk, id);
            if (row != null && row.getState().compareTo(MessageState.QUEUED) > 0) {
                onStateAll(node, id, row.getState());
            }
        }
    }

    private static void onStateAll(ChatNode node, String id, MessageState state) {
        PendingAll p = pendingAll.get(id);
        if (p == null) {
            return;
        }
        if (state == MessageState.FAILED) {
            if (p.answered.compareAndSet(false, true)) {
                for (String i : p.ids) {
                    pendingAll.remove(i);
                }
                TLRPC.TL_error err = new TLRPC.TL_error();
                err.code = 400;
                err.text = LocaleController.getString(R.string.GerehSendFailed);
                Utilities.stageQueue.postRunnable(() -> p.done.run(null, err));
            }
            return;
        }
        if (state.compareTo(MessageState.QUEUED) <= 0) {
            return;
        }
        p.out.add(id);
        if (p.out.size() < p.ids.size() || !p.answered.compareAndSet(false, true)) {
            return;
        }
        for (String i : p.ids) {
            pendingAll.remove(i);
        }
        Contact c = contact(node, p.sk);
        if (c == null) {
            return;
        }
        final List<TLRPC.Message> msgs = new ArrayList<>();
        final List<Long> randoms = new ArrayList<>();
        long max = 0;
        for (int i = 0; i < p.ids.size(); i++) {
            Message row = find(node, p.sk, p.ids.get(i));
            if (row != null) {
                msgs.add(toTl(row, c));
                randoms.add(p.randomIds.get(i));
                max = Math.max(max, row.getSeq());
            }
        }
        final TLRPC.User user = userFor(c);
        final long maxSeq = max;
        Utilities.stageQueue.postRunnable(() -> {
            TLRPC.TL_updates ups = new TLRPC.TL_updates();
            for (int i = 0; i < msgs.size(); i++) {
                TL_update.TL_updateMessageID mid = new TL_update.TL_updateMessageID();
                mid.id = msgs.get(i).id;
                mid.random_id = randoms.get(i);
                ups.updates.add(mid);
                TL_update.TL_updateNewMessage u = new TL_update.TL_updateNewMessage();
                u.message = msgs.get(i);
                u.pts = nextPts();
                u.pts_count = 1;
                ups.updates.add(u);
            }
            ups.users.add(user);
            ups.date = now();
            ups.seq = 0;
            p.done.run(ups, null);
            markSynced(maxSeq);
        });
    }

    static void awaitSent(ChatNode node, String sk, String id, long randomId, RequestDelegate done, int token) {
        pending.put(id, new Pending(sk, randomId, done, token));
        Message row = find(node, sk, id);
        if (row != null && row.getState().compareTo(MessageState.QUEUED) > 0) {
            onState(node, sk, id, row.getState());
        }
    }

    private static long peerUserId(TLRPC.InputPeer p) {
        if (p instanceof TLRPC.TL_inputPeerUser) {
            return ((TLRPC.TL_inputPeerUser) p).user_id;
        }
        if (p instanceof TLRPC.TL_inputPeerSelf) {
            // Saved Messages: Telegram names the chat with oneself so, and every message there
            // was refused as «not a contact» and got «!» (p3, Ehsan, 31 Oct 2026)
            return UserConfig.getInstance(account()).getClientUserId();
        }
        return 0;
    }

    private static Contact contact(ChatNode node, String sk) {
        for (Contact c : node.contacts()) {
            if (c.getSk().equals(sk)) {
                return c;
            }
        }
        return null;
    }

    /** A message of the chat with [peer] by its core id: the one row, never the chat around it. */
    private static Message find(ChatNode node, String peer, String id) {
        return node.message(peer, id, 0);
    }

    private static SharedPreferences sentIds() {
        // random_id -> core message id: numbers only, no text and no keys.
        return ApplicationLoader.applicationContext.getSharedPreferences("gereh_sent_ids", Context.MODE_PRIVATE);
    }

    private static int now() {
        return (int) (System.currentTimeMillis() / 1000);
    }

    // ---- serialization, so every object looks exactly like a server's ------------------

    static TLObject roundTripObject(TLObject o) {
        SerializedData out = new SerializedData(o.getObjectSize());
        o.serializeToStream(out);
        SerializedData in = new SerializedData(out.toByteArray());
        int magic = in.readInt32(true);
        TLObject parsed;
        if (o instanceof TLRPC.User) {
            parsed = TLRPC.User.TLdeserialize(in, magic, true);
        } else if (o instanceof TLRPC.Message) {
            parsed = TLRPC.Message.TLdeserialize(in, magic, true);
        } else {
            parsed = o;
        }
        out.cleanup();
        in.cleanup();
        return parsed;
    }

    static TLObject roundTripResponse(TLObject request, TLObject response) {
        SerializedData out = new SerializedData(response.getObjectSize());
        response.serializeToStream(out);
        SerializedData in = new SerializedData(out.toByteArray());
        TLObject parsed = request.deserializeResponse(in, in.readInt32(true), true);
        out.cleanup();
        in.cleanup();
        return parsed;
    }

    static <T extends TLObject> TLObject roundTripVector(TLObject request, ArrayList<T> items) {
        SerializedData size = new SerializedData(true);
        Vector.serialize(size, items);
        SerializedData out = new SerializedData(size.length());
        Vector.serialize(out, items);
        SerializedData in = new SerializedData(out.toByteArray());
        TLObject parsed = request.deserializeResponse(in, in.readInt32(true), true);
        size.cleanup();
        out.cleanup();
        in.cleanup();
        return parsed;
    }
}
