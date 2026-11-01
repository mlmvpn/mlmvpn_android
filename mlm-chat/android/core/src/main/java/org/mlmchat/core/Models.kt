package org.mlmchat.core

import org.json.JSONArray
import org.json.JSONObject

/** A contact, as the core keeps it (SPEC 3). */
data class Contact(
    /** Their identity key `sk`, hex: their user id on the network. */
    val sk: String,
    /** The name their card suggests. */
    val name: String,
    /** The short name everyone sees (T14), e.g. `ali·4821`. */
    val displayName: String,
    /** They added us first: a request. */
    val byThem: Boolean,
    val blocked: Boolean,
    /** The security number was compared. */
    val verified: Boolean,
    val addedMs: Long,
) {
    internal companion object {
        fun from(j: JSONObject) = Contact(
            j.getString("sk"), j.getString("name"), j.getString("displayName"),
            j.getBoolean("byThem"), j.getBoolean("blocked"), j.getBoolean("verified"), j.getLong("addedMs"),
        )
    }
}

/**
 * A message's state. Outgoing ones only move forward, and none is shown as sent or delivered
 * before it really is (SPEC 0).
 */
enum class MessageState {
    QUEUED, SENT, DELIVERED, READ, FAILED, RECEIVED, SEEN;

    internal companion object {
        fun of(name: String): MessageState = when (name) {
            "queued" -> QUEUED
            "sent" -> SENT
            "delivered" -> DELIVERED
            "read" -> READ
            "received" -> RECEIVED
            "seen" -> SEEN
            else -> FAILED
        }
    }
}

data class Message(
    /** The message id, hex (16 bytes). */
    val id: String,
    /** This phone's own number for it, in the order it was stored; never changes. */
    val seq: Long,
    val outgoing: Boolean,
    /** When the sender wrote it (their clock). */
    val sentMs: Long,
    /** When this phone stored it. */
    val atMs: Long,
    val kind: String,
    /** Set for text messages. */
    val text: String?,
    val state: MessageState,
    /** Set for a file, photo, video or voice message. */
    val media: MediaRef? = null,
    /** The seq here of the message this one answers (SPEC 5 `reply_to`); 0 for none, or when that message is not here. */
    val replyTo: Long = 0,
    /** The formatting and mentions of [text], or of the caption of [media]. */
    val entities: List<TextEntity> = emptyList(),
    /** When it was last edited (SPEC 5 `Edit`, by its sender's clock); 0 if never. */
    val editedMs: Long = 0,
    /** The contact whose chat it is (hex sk). */
    val peer: String = "",
) {
    internal companion object {
        fun from(j: JSONObject) = Message(
            j.getString("id"), j.getLong("seq"), j.getBoolean("out"), j.getLong("sentMs"), j.getLong("atMs"),
            j.getString("kind"), if (j.isNull("text")) null else j.getString("text"), MessageState.of(j.getString("state")),
            MediaRef.from(j.optJSONObject("media")), j.optLong("replyTo", 0), TextEntity.list(j.optJSONArray("entities")),
            j.optLong("editedMs", 0), j.optString("peer"),
        )
    }
}

/**
 * What a chat list shows of a one-to-one chat besides its last message ([ChatNode.chatMarks]):
 * incoming messages not read yet, the newest incoming seq read here, and the newest outgoing seq
 * the contact has read (0: none).
 */
data class ChatMarks(val unread: Int, val readIn: Long, val readOut: Long) {
    internal companion object {
        fun from(j: JSONObject) = ChatMarks(j.optInt("unread"), j.optLong("readIn"), j.optLong("readOut"))
    }
}

/**
 * What a chat list and a screen show of a group or channel besides its last message
 * ([ChatNode.groupMarks]): incoming messages after the last one read, our newest message a member
 * has read (SPEC 7.6; its seq, 0: none), and how many messages it has.
 */
data class GroupMarks(val unread: Int, val readOut: Long, val total: Int) {
    internal companion object {
        fun from(j: JSONObject) = GroupMarks(j.optInt("unread"), j.optLong("readOut"), j.optInt("total"))
    }
}

/**
 * A run of formatting or a mention in a text or a caption (SPEC 5 `Entity`). [offset] and
 * [length] count UTF-16 units, as Java strings and Telegram do. [url] is set for [LINK], [sk]
 * (hex) for [MENTION]: whom it names; [lang] for [PRE]. The core drops an entity it cannot send.
 */
data class TextEntity(
    val type: Int,
    val offset: Int,
    val length: Int,
    val url: String = "",
    val sk: String = "",
    val lang: String = "",
) {
    fun toJson(): JSONObject =
        JSONObject().put("type", type).put("offset", offset).put("length", length).put("url", url).put("sk", sk).put("lang", lang)

    companion object {
        const val BOLD = 0
        const val ITALIC = 1
        const val UNDERLINE = 2
        const val STRIKE = 3
        const val SPOILER = 4
        const val CODE = 5
        const val PRE = 6
        const val QUOTE = 7
        const val LINK = 8
        const val MENTION = 9
        const val HASHTAG = 10

        /** [list] as the core takes it: for sending, and in a file's info under "entities". */
        @JvmStatic
        fun toJson(list: List<TextEntity>): JSONArray = JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }

        internal fun list(a: JSONArray?): List<TextEntity> = if (a == null) emptyList() else List(a.length()) {
            val j = a.getJSONObject(it)
            TextEntity(j.optInt("type"), j.optInt("offset"), j.optInt("length"), j.optString("url"), j.optString("sk"), j.optString("lang"))
        }
    }
}

/**
 * A file message (SPEC 5 `Media`), without its key (that stays in the core). [kind]: 0 file,
 * 1 photo, 2 video, 3 voice, 4 round video, 5 GIF. [short]: the id an app shows it under (Telegram's
 * photo or document id). [plain]: the file's own size; [size] what travels. [thumb] and
 * [waveform] are hex.
 */
data class MediaRef(
    val kind: Int,
    val mime: String,
    val size: Long,
    val plain: Long,
    val blob: String,
    val short: Long,
    val name: String,
    val width: Int,
    val height: Int,
    val durationMs: Int,
    val thumb: String,
    val waveform: String,
    val caption: String,
    /** The album it is one of (SPEC 5 `album_id`, hex): files sent together share it; empty for one on its own. */
    val album: String = "",
) {
    internal companion object {
        fun from(j: JSONObject?): MediaRef? = j?.let {
            MediaRef(
                it.optInt("kind"), it.optString("mime"), it.optLong("size"), it.optLong("plain"), it.optString("blob"), it.optLong("short"),
                it.optString("name"), it.optInt("w"), it.optInt("h"), it.optInt("durationMs"), it.optString("thumb"), it.optString("waveform"),
                it.optString("caption"), it.optString("album"),
            )
        }
    }
}

/** What the node tells the app, as it happens. */
sealed class ChatEvent {
    abstract val peer: String

    /** A message arrived; [text] is set for text messages. */
    data class NewMessage(override val peer: String, val id: String, val kind: String, val text: String?) : ChatEvent()
    /** One of our messages moved forward, or failed. */
    data class State(override val peer: String, val id: String, val state: MessageState) : ChatEvent()
    /** Frames for this contact are still queued, and why (a transport error code). */
    data class Queued(override val peer: String, val reason: String) : ChatEvent()
    /** A new contact: added by us, or they added us first. */
    data class NewContact(override val peer: String, val byThem: Boolean) : ChatEvent()
    /** A contact is beside us or on the same network ([via]: `lan` or `ble`): its queue is retried. */
    data class Seen(override val peer: String, val via: String) : ChatEvent()
    /** A contact sent a newer card; their name may have changed. */
    data class ContactChanged(override val peer: String) : ChatEvent()
    /**
     * A session with this contact just got through, either way: they are reachable right now.
     * [via]: `lan`, `direct`, `p2p` (straight to them) or `relay` (through a volunteer).
     */
    data class Reachable(override val peer: String, val via: String) : ChatEvent()

    /** A group or channel changed: created, joined, someone added or removed, renamed, or we were removed. [peer] is empty. */
    data class GroupChanged(override val peer: String, val group: String) : ChatEvent()

    /** A message in a group or channel: [peer] is the sender, [group] the group id (hex). */
    data class GroupMessage(override val peer: String, val group: String, val id: String, val kind: String, val text: String?) : ChatEvent()

    /** Chunks of a file arrived: [have] of [total]; whole and checked when they are equal. [peer] is empty. */
    data class Blob(override val peer: String, val blob: String, val have: Int, val total: Int) : ChatEvent()

    /** A contact or group member sent a newer profile: their photo changed or went ([ChatNode.photoOf]). */
    data class Profile(override val peer: String) : ChatEvent()

    /** [peer] asked to join [group] by a link that needs approval ([ChatNode.groupJoinRequests]). */
    data class JoinRequested(override val peer: String, val group: String) : ChatEvent()

    /** An official feed got new items ([ChatNode.feedPosts]), or its member count may have changed. [peer] is empty. */
    data class FeedChanged(override val peer: String, val feed: String) : ChatEvent()

    /**
     * [peer]'s reaction to message [id] changed: in the one-to-one chat with [chat] (a contact's
     * key), or in group [chat] when [group]. [peer] may be us.
     */
    data class ReactionChanged(override val peer: String, val chat: String, val group: Boolean, val id: String) : ChatEvent()

    /** [peer]'s vote on poll [id] of group [chat] changed (T69); [peer] may be us. */
    data class PollVoted(override val peer: String, val chat: String, val group: Boolean, val id: String) : ChatEvent()

    /**
     * The other side took messages down for everyone: in the one-to-one chat with [chat], or in
     * group [chat] when [group]. [seqs] are this phone's numbers for them (the rows are gone). [peer] is empty.
     */
    data class Deleted(override val peer: String, val chat: String, val group: Boolean, val seqs: List<Long>) : ChatEvent()

    /**
     * A message's text or caption was edited (SPEC 5 `Edit`), by us or its sender: in the one-to-one
     * chat with [chat], or in group [chat] when [group]. [seq] is this phone's number for it. [peer] is empty.
     */
    data class Edited(override val peer: String, val chat: String, val group: Boolean, val seq: Long) : ChatEvent()

    /** The pinned messages of the one-to-one chat with [peer] changed ([ChatNode.pinned], T65). */
    data class Pinned(override val peer: String) : ChatEvent()

    /**
     * [peer] is doing this in our one-to-one chat right now (SPEC 5.4): [kind] is `typing`,
     * `upload_photo`, `upload_file`, `record_voice`, or `cancel` (no longer). Shown until a newer
     * one comes, or for six seconds.
     */
    data class ChatAction(override val peer: String, val kind: String) : ChatEvent()

    /**
     * [peer]'s app came to the front ([online]) or left it (SPEC 5.5); only while our own last seen
     * is shown. [ContactStatus.lastSeenMs] and [ContactStatus.inApp] say when, and whether it holds.
     */
    data class Presence(override val peer: String, val online: Boolean) : ChatEvent()

    /** [peer] read our message [seq] in [group] (SPEC 7.6): [ChatNode.groupReads] has who and when. */
    data class GroupRead(override val peer: String, val group: String, val seq: Long) : ChatEvent()

    /** What is known of the app [peer] writes from changed (SPEC 5 `Client`, T75): [ChatNode.clientsSince]. */
    data class ClientChanged(override val peer: String) : ChatEvent()

    internal companion object {
        fun from(j: JSONObject): ChatEvent? {
            val peer = j.optString("peer")
            return when (j.getString("type")) {
                "message" -> NewMessage(peer, j.getString("id"), j.getString("kind"), if (j.isNull("text")) null else j.getString("text"))
                "state" -> State(peer, j.getString("id"), MessageState.of(j.getString("state")))
                "queued" -> Queued(peer, j.getString("reason"))
                "contact" -> NewContact(peer, j.getBoolean("byThem"))
                "seen" -> Seen(peer, j.optString("via"))
                "contactChanged" -> ContactChanged(peer)
                "reachable" -> Reachable(peer, j.getString("via"))
                "group" -> GroupChanged(peer, j.getString("group"))
                "blob" -> Blob(peer, j.getString("blob"), j.optInt("have"), j.optInt("total"))
                "group_message" -> GroupMessage(peer, j.getString("group"), j.getString("id"), j.getString("kind"), if (j.isNull("text")) null else j.getString("text"))
                "profile" -> Profile(peer)
                "join_request" -> JoinRequested(peer, j.getString("group"))
                "feed" -> FeedChanged(peer, j.getString("feed"))
                "reaction" -> ReactionChanged(peer, j.getString("chat"), j.optBoolean("group"), j.getString("id"))
                "poll_vote" -> PollVoted(peer, j.getString("chat"), j.optBoolean("group"), j.getString("id"))
                "deleted" -> j.getJSONArray("seqs").let { a -> Deleted(peer, j.getString("chat"), j.optBoolean("group"), List(a.length()) { a.getLong(it) }) }
                "edited" -> Edited(peer, j.getString("chat"), j.optBoolean("group"), j.getLong("seq"))
                "pinned" -> Pinned(peer)
                "chat_action" -> ChatAction(peer, j.getString("kind"))
                "presence" -> Presence(peer, j.getBoolean("online"))
                "group_read" -> GroupRead(peer, j.getString("group"), j.getLong("seq"))
                "client" -> ClientChanged(peer)
                else -> null
            }
        }
    }
}

/** The p2p network as the core sees it (phase 3). Counts only. */
data class NetStatus(
    /** The label routes are remembered under. */
    val network: String,
    val p2pRunning: Boolean,
    val volunteer: Boolean,
    /** Volunteers known (cards, memory) and connected right now. */
    val volunteersKnown: Int,
    val volunteersConnected: Int,
    /** Relay reservations held: contacts on other networks can reach this phone through that many volunteers. */
    val reservations: Int,
    /** When this phone's presence record last reached the DHT (0 = never). */
    val publishedMs: Long,
    /**
     * The connection ladder (SPEC 15.9): `idle` (no volunteer known), `probing` (trying the ways
     * to a volunteer, in the order this network proved), `reachable` (a volunteer is connected),
     * `unreachable` (the last round reached none; the next starts in [nextTryS] seconds).
     */
    val ladder: String,
    /** The way in use: `v4:443`, `v6:40443`, `lan:40443`; empty if none. */
    val rung: String,
    /** Ways tried, and ways in the current round (else the last one). */
    val tried: Int,
    val total: Int,
    /** What most ways of the last round failed with: `timeout`, `refused`, `unreachable`, ... */
    val lastError: String,
    val nextTryS: Long,
    /** Address ranges the node is limited to ([ChatNode.setScope]); 0 = none. */
    val scope: Int,
    /** Volunteer addresses the last round left out because the scope does not allow them. */
    val outOfScope: Int,
    /** A volunteer's own addresses that a peer on another network really reached (announced). */
    val verified: List<String>,
    /** A volunteer's own full addresses (`.../p2p/<id>`) for its operator to hand out; empty otherwise. */
    val volunteerAddrs: List<String>,
    /** A volunteer's door on its router: `method=result` per method (`upnp4=opened`...). */
    val door: List<String>,
) {
    /** Reachable from other networks right now. */
    val online: Boolean get() = volunteer || reservations > 0

    internal companion object {
        fun from(j: JSONObject): NetStatus {
            val p = j.optJSONObject("p2p")
            fun strings(name: String): List<String> {
                val a = p?.optJSONArray(name) ?: return emptyList()
                return List(a.length()) { a.optString(it) }
            }
            val door = p?.optJSONArray("door")
            return NetStatus(
                j.optString("network"), p?.optBoolean("running") ?: false, p?.optBoolean("volunteer") ?: false,
                p?.optInt("volunteers_known") ?: 0, p?.optInt("volunteers_connected") ?: 0, p?.optInt("reservations") ?: 0,
                p?.optLong("published_ms") ?: 0L,
                p?.optString("ladder") ?: "idle", p?.optString("rung") ?: "", p?.optInt("tried") ?: 0, p?.optInt("total") ?: 0,
                p?.optString("last_error") ?: "", p?.optLong("next_try_s") ?: 0L,
                j.optInt("scope"), p?.optInt("out_of_scope") ?: 0, strings("verified"), strings("volunteer_addrs"),
                if (door == null) emptyList() else List(door.length()) { door.optJSONObject(it).let { d -> d.optString("method") + "=" + d.optString("result") } },
            )
        }
    }
}

/** What the Bluetooth radio must do next (SPEC 16.2). */
sealed class BleCommand {
    /** Connect to the device seen at [addr] as a GATT client, then report it on [link]. */
    data class Connect(val link: Int, val addr: String) : BleCommand()

    /** Write [data] on [link], in order, split to the MTU. */
    class Send(val link: Int, val data: ByteArray) : BleCommand()

    data class Close(val link: Int) : BleCommand()

    /** Advertise again with [ChatNode.bleAdvertTag]: «add nearby» opened or closed (SPEC 16.4). */
    object Advertise : BleCommand()

    internal companion object {
        fun from(j: JSONObject): BleCommand? {
            val link = j.optInt("link")
            return when (j.optString("op")) {
                "connect" -> Connect(link, j.optString("addr"))
                "send" -> Send(link, hex(j.optString("data")))
                "close" -> Close(link)
                "advertise" -> Advertise
                else -> null
            }
        }

        private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}

/**
 * One peer using this phone as a volunteer now. [name]: six hex digits from a hash of its weekly
 * network id, nothing about who it is. [reserved]: others reach it through this phone.
 */
data class RelayPeer(val name: String, val sinceMs: Long, val rx: Long, val tx: Long, val reserved: Boolean, val circuits: Int)

/** A volunteer's ledger (SPEC 15.12). */
data class RelayStatus(
    val volunteer: Boolean,
    /** Peers connected now. */
    val now: Int,
    /** Different peers served, and bytes moved, since the role was first turned on (kept on the phone). */
    val served: Long,
    val bytesTotal: Long,
    /** Bytes received and sent in this run. */
    val rxRun: Long,
    val txRun: Long,
    val peers: List<RelayPeer>,
) {
    internal companion object {
        fun from(j: JSONObject): RelayStatus {
            val a = j.optJSONArray("peers")
            val peers = if (a == null) emptyList() else List(a.length()) {
                val p = a.getJSONObject(it)
                RelayPeer(p.optString("name"), p.optLong("since_ms"), p.optLong("rx"), p.optLong("tx"), p.optBoolean("reserved"), p.optInt("circuits"))
            }
            return RelayStatus(j.optBoolean("volunteer"), j.optInt("now"), j.optLong("served"), j.optLong("bytes_total"), j.optLong("rx_run"), j.optLong("tx_run"), peers)
        }
    }
}

/**
 * Whether this phone could be a volunteer where it is now (SPEC 15.11), from read-only questions
 * to the router. [verdict]: `suitable` (public IPv4 and a door: everyone reaches it);
 * `same_operator` (behind the operator's NAT, like LTE home modems in Iran, with a door there:
 * subscribers of the same operator may reach it); `ipv6_maybe` (only a global IPv6: it depends on
 * the router's IPv6 firewall); `no_router`; `no_address`; `private` (behind another router);
 * `outside_scope`. All but `suitable` are proven only once the role is on. [v4]: `public`,
 * `carrier_nat`, `private`, `outside_scope`, `none`; [v6]: this phone has a global IPv6.
 */
data class VolunteerCheck(val verdict: String, val method: String, val v4: String, val v6: Boolean, val pinhole6: Boolean) {
    val suitable: Boolean get() = verdict == "suitable"

    /** Worth turning the role on and letting peers on other networks prove it. */
    val worthTrying: Boolean get() = verdict == "suitable" || verdict == "same_operator" || verdict == "ipv6_maybe"

    internal companion object {
        fun from(j: JSONObject) = VolunteerCheck(j.optString("verdict"), j.optString("method"), j.optString("v4"), j.optBoolean("v6"), j.optBoolean("pinhole6"))
    }
}

/** What really happened with one contact (SPEC 15.6): the app shows only this. */
data class ContactStatus(
    /** When a session with them last got through, either way (0 = never). */
    val lastReachedMs: Long,
    /** `lan`, `direct`, `p2p`, `relay`; empty if never. */
    val via: String,
    /** Reached within the last few minutes. */
    val online: Boolean,
    /** Our messages to them still waiting, and the last reason (a transport error code). */
    val queued: Int,
    val reason: String?,
    /**
     * When a frame last came from them through one of our Google mailboxes, and last went into
     * theirs (0 = never). A route, not «online»: a mailbox holds what was left there for a while.
     */
    val mailboxInMs: Long = 0,
    val mailboxOutMs: Long = 0,
    /** Google mailbox relays their newest card names; none and no other route: get their card again. */
    val theirRelays: Int = 0,
    /**
     * When they were last seen in the app (SPEC 5.5), by this phone's clock: 0 when not known, and
     * while our own last seen is hidden.
     */
    val lastSeenMs: Long = 0,
    /** In the app right now (their «online» still holds). */
    val inApp: Boolean = false,
) {
    internal companion object {
        fun from(j: JSONObject) = ContactStatus(
            j.getLong("last_reached_ms"), j.getString("via"), j.getBoolean("online"), j.getInt("queued"),
            if (j.isNull("reason")) null else j.getString("reason"),
            j.optLong("mailbox_in_ms"), j.optLong("mailbox_out_ms"), j.optInt("their_relays"),
            j.optLong("last_seen_ms"), j.optBoolean("in_app"),
        )
    }
}

/**
 * One person in the «add nearby» list (SPEC 16.4). [state]: `found`, `outgoing` (you asked),
 * `incoming` (they asked), `waiting` (you confirmed), `done`, `declined`, `failed`. [code]: the
 * four emoji both screens show once someone asked. [via]: `wifi` or `ble`.
 */
data class PairPeer(val id: String, val name: String, val state: String, val code: String?, val via: String)

/** The «add nearby» screen: whether it is open, seconds left, and the list. */
data class PairState(val open: Boolean, val leftS: Int, val peers: List<PairPeer>) {
    internal companion object {
        fun from(j: JSONObject): PairState {
            val arr = j.optJSONArray("peers")
            val peers = (0 until (arr?.length() ?: 0)).map { i ->
                val p = arr!!.getJSONObject(i)
                PairPeer(p.optString("id"), p.optString("name"), p.optString("state"), if (p.isNull("code")) null else p.optString("code"), p.optString("via"))
            }
            return PairState(j.optBoolean("open"), j.optInt("left_s"), peers)
        }
    }
}

/** A member of a group or channel (SPEC 7.1): [rights] are admin rights (all for the owner). */
/**
 * One member of a group. [restricted]: the member rights a restriction leaves them (SPEC 7.2
 * `Restrict`), -1 when none stands now; [restrictedUntil]: its end (ms; far off for good).
 * [joinedMs]: when the member joined, the joining event's own time (0 from an older core).
 */
data class GroupMember @JvmOverloads constructor(
    val sk: String,
    val name: String,
    val owner: Boolean,
    val admin: Boolean,
    val rights: Int,
    val title: String,
    val joined: Int,
    val restricted: Int = -1,
    val restrictedUntil: Long = 0,
    val joinedMs: Long = 0,
)

/** [sk] read one of our group messages at [atMs], by this phone's clock (SPEC 7.6). */
data class GroupRead(val sk: String, val atMs: Long)

/**
 * The app [sk] writes from, as their newest message or official post said it (SPEC 5 `Client`,
 * T75): [app] its package name, empty for a phone from before draft 28; [code] and [name] its
 * version. [atMs] is when this phone last saw it change.
 */
data class PeerClient(val sk: String, val app: String, val code: Int, val name: String, val sentMs: Long, val atMs: Long) {
    internal companion object {
        fun from(j: JSONObject) = PeerClient(j.getString("sk"), j.optString("app"), j.optInt("code"), j.optString("name"), j.optLong("sentMs"), j.optLong("atMs"))
    }
}

/**
 * A group, or a channel ([channel]: only the owner and admins post). [member], [admin],
 * [adminRights] and [canPost] are this phone's own place in it.
 */
data class Group(
    val id: String,
    val name: String,
    val about: String,
    val channel: Boolean,
    val owner: String,
    val dissolved: Boolean,
    val member: Boolean,
    val admin: Boolean,
    val adminRights: Int,
    val canPost: Boolean,
    val defaults: Int,
    val members: List<GroupMember>,
    /** The photo's short id (as [MediaRef.short]); 0: no photo. */
    val photo: Long = 0,
    /** Every ban standing now: sk (hex) to its end (ms; for good is far off), as [FeedInfo.banned]. */
    val banned: Map<String, Long> = emptyMap(),
    /** The Gereh name of each banned member, from the card they had when banned (may be empty). */
    val bannedNames: Map<String, String> = emptyMap(),
    /** This phone's own member rights now: the defaults less a restriction on it (all bits for an admin). */
    val myRights: Long = -1,
    /** Slow mode (q13), in seconds; 0 for none. */
    val slowSeconds: Int = 0,
    /** When this phone may post again under it (ms); 0 for now. Only from [ChatNode.groupState], not the list. */
    val slowNextMs: Long = 0,
    /** The photo's tiny preview (hex JPEG, at most 4 KiB), shown until the photo itself is here (q16). */
    val photoThumb: String = "",
) {
    internal companion object {
        fun from(j: JSONObject): Group {
            val a = j.optJSONArray("members")
            val members = (0 until (a?.length() ?: 0)).map { i ->
                val m = a!!.getJSONObject(i)
                GroupMember(
                    m.getString("sk"), m.optString("name"), m.optBoolean("owner"), m.optBoolean("admin"), m.optInt("rights"), m.optString("title"), m.optInt("joined"),
                    m.optLong("restricted", -1).toInt(), m.optLong("restrictedUntil"), m.optLong("joinedMs"),
                )
            }
            val b = j.optJSONArray("banned")
            val bans = (0 until (b?.length() ?: 0)).map { b!!.getJSONObject(it) }
            return Group(
                j.getString("id"), j.optString("name"), j.optString("about"), j.optBoolean("channel"), j.optString("owner"),
                j.optBoolean("dissolved"), j.optBoolean("member"), j.optBoolean("admin"), j.optInt("adminRights"), j.optBoolean("canPost"),
                j.optInt("defaults"), members, j.optLong("photo"),
                bans.associate { it.getString("sk") to it.optLong("until") },
                bans.associate { it.getString("sk") to it.optString("name") },
                j.optLong("myRights", -1),
                j.optInt("slowSeconds"),
                j.optLong("slowNextMs"),
                j.optString("photoThumb"),
            )
        }
    }
}

/**
 * A profile photo (SPEC 10): [short] is the id an app knows its file by (as [MediaRef.short]),
 * [thumb] its tiny preview (hex). [complete]: the file is here whole.
 */
data class Photo(val short: Long, val blob: String, val thumb: String, val complete: Boolean) {
    internal companion object {
        fun from(j: JSONObject) = Photo(j.getLong("short"), j.getString("blob"), j.optString("thumb"), j.optBoolean("complete"))
    }
}

/** A message in a group or channel: like [Message], with its [sender] (hex sk). */
data class GroupMessage(
    val id: String,
    val seq: Long,
    val outgoing: Boolean,
    val sender: String,
    val sentMs: Long,
    val atMs: Long,
    val kind: String,
    val text: String?,
    val state: MessageState,
    val media: MediaRef? = null,
    /** The seq here of the group message this one answers; 0 for none, or when it is not here. */
    val replyTo: Long = 0,
    val entities: List<TextEntity> = emptyList(),
    /** When it was last edited (SPEC 5 `Edit`, by its sender's clock); 0 if never. */
    val editedMs: Long = 0,
    /** The group or channel it is in (hex id). */
    val group: String = "",
    /** Set for a poll ([kind] "poll"); [ChatNode.pollResults] says where it stands. */
    val poll: PollInfo? = null,
) {
    internal companion object {
        fun from(j: JSONObject) = GroupMessage(
            j.getString("id"), j.getLong("seq"), j.getBoolean("out"), j.getString("sender"), j.getLong("sentMs"), j.getLong("atMs"),
            j.getString("kind"), if (j.isNull("text")) null else j.getString("text"), MessageState.of(j.getString("state")),
            MediaRef.from(j.optJSONObject("media")), j.optLong("replyTo", 0), TextEntity.list(j.optJSONArray("entities")),
            j.optLong("editedMs", 0), j.optString("group"), PollInfo.from(j.optJSONObject("poll")),
        )
    }
}

/**
 * A poll (SPEC 5 `Poll`, T69): 2 to 10 [options]. [anonymous] as in Telegram: no app shows who
 * voted. [multiple]: several answers; [quiz]: one answer, once, and [correct] is the right one's
 * index. [closeMs]: no votes after it (0: open).
 */
data class PollInfo(
    val question: String,
    val options: List<String>,
    val anonymous: Boolean = true,
    val multiple: Boolean = false,
    val quiz: Boolean = false,
    val correct: Int = 0,
    val closeMs: Long = 0,
) {
    internal fun toJson(): JSONObject = JSONObject().put("question", question).put("options", JSONArray(options))
        .put("anonymous", anonymous).put("multiple", multiple).put("quiz", quiz).put("correct", correct).put("closeMs", closeMs)

    internal companion object {
        fun from(j: JSONObject?): PollInfo? {
            if (j == null) return null
            val a = j.optJSONArray("options") ?: return null
            return PollInfo(
                j.optString("question"), List(a.length()) { a.getString(it) }, j.optBoolean("anonymous", true),
                j.optBoolean("multiple"), j.optBoolean("quiz"), j.optInt("correct"), j.optLong("closeMs"),
            )
        }
    }
}

/**
 * Where a poll stands, counted on this phone: votes for each option ([counts], in its order), how
 * many voted, our own [mine] (empty: not voted), and who chose what ([who], hex sk to choices;
 * only for a poll that is not anonymous).
 */
data class PollResults(val counts: List<Int>, val voters: Int, val mine: List<Int>, val who: Map<String, List<Int>>) {
    internal companion object {
        private fun ints(a: JSONArray?): List<Int> = if (a == null) emptyList() else List(a.length()) { a.getInt(it) }

        fun from(j: JSONObject): PollResults {
            val w = j.optJSONArray("who")
            val who = LinkedHashMap<String, List<Int>>()
            for (i in 0 until (w?.length() ?: 0)) {
                val o = w!!.getJSONObject(i)
                who[o.getString("sk")] = ints(o.optJSONArray("choices"))
            }
            return PollResults(ints(j.optJSONArray("counts")), j.optInt("voters"), ints(j.optJSONArray("mine")), who)
        }
    }
}

/** An invite of a group (SPEC 7.4): [link] only for invites this phone made. [expMs] 0: never; [maxUses] 0: no limit. */
data class Invite(
    val id: String,
    val link: String?,
    val by: String,
    val expMs: Long,
    val maxUses: Int,
    val uses: Int,
    val needApproval: Boolean,
    val createdMs: Long,
) {
    internal companion object {
        fun from(j: JSONObject) = Invite(
            j.getString("id"), if (j.isNull("link")) null else j.getString("link"), j.optString("by"), j.optLong("expMs"),
            j.optInt("maxUses"), j.optInt("uses"), j.optBoolean("needApproval"), j.optLong("createdMs"),
        )
    }
}

/** What an invite link says before joining. [member]: already in; [asked]: our request waits. */
data class InviteInfo(
    val group: String,
    val invite: String,
    val name: String,
    val channel: Boolean,
    val needApproval: Boolean,
    val inviter: String,
    val expMs: Long,
    val member: Boolean,
    val asked: Boolean,
) {
    internal companion object {
        fun from(j: JSONObject) = InviteInfo(
            j.getString("group"), j.getString("invite"), j.optString("name"), j.optBoolean("channel"), j.optBoolean("needApproval"),
            j.optString("inviter"), j.optLong("expMs"), j.optBoolean("member"), j.optBoolean("asked"),
        )
    }
}

/** Someone waiting for approval to join. */
data class JoinRequest(val sk: String, val name: String, val atMs: Long)

/** An official feed (T38): a channel ([broadcast]) or a group everyone is in. [admin]/[canPost]: this phone's place. */
/** An official feed: [photo] is the short id of its photo (0: none), [photoThumb] its preview (hex). */
data class FeedInfo(
    val id: String,
    val broadcast: Boolean,
    val name: String,
    val admin: Boolean,
    val canPost: Boolean,
    val admins: List<String>,
    val photo: Long = 0,
    val photoThumb: String = "",
    /** An admin's name and description; empty: the app's own. */
    val title: String = "",
    val about: String = "",
    /** Until when this phone is banned here (ms; 0: not banned). */
    val bannedUntil: Long = 0,
    /** Every ban standing now: sk (hex) to its end (ms). */
    val banned: Map<String, Long> = emptyMap(),
    /** Pinned posts' ids (hex), the newest last (T65). */
    val pinned: List<String> = emptyList(),
    /** The group's slow mode in seconds (T67), and when this phone may post again (ms; 0: now). */
    val slowSeconds: Int = 0,
    val slowNextMs: Long = 0,
) {
    internal companion object {
        fun from(j: JSONObject): FeedInfo {
            val a = j.optJSONArray("admins")
            val b = j.optJSONArray("banned")
            val p = j.optJSONArray("pinned")
            return FeedInfo(
                j.getString("id"), j.optBoolean("broadcast"), j.optString("name"), j.optBoolean("admin"), j.optBoolean("canPost"),
                List(a?.length() ?: 0) { a!!.getString(it) },
                j.optLong("photo"), j.optString("photoThumb"), j.optString("title"), j.optString("about"),
                j.optLong("bannedUntil"),
                (0 until (b?.length() ?: 0)).associate { i -> b!!.getJSONObject(i).let { it.getString("sk") to it.getLong("until") } },
                List(p?.length() ?: 0) { p!!.getString(it) },
                j.optInt("slow"), j.optLong("slowNext"),
            )
        }
    }
}

/** A post in a feed: [author] is a hex sk, [name] the name they gave; [outgoing] when it is ours. */
data class FeedPost(
    val id: String,
    val author: String,
    val name: String,
    val tsMs: Long,
    val outgoing: Boolean,
    val kind: String,
    val text: String?,
    val media: MediaRef? = null,
    /** Who reacted with what, one each, oldest first ([Reaction.id] is this post's). */
    val reactions: List<Reaction> = emptyList(),
    /** The id of the post of this feed it answers; null for none. */
    val replyTo: String? = null,
    /** The text's formatting, mentions and links. */
    val entities: List<TextEntity> = emptyList(),
    /** When its author last edited it (ms; 0: never); [text] is already the edited one. */
    val editedMs: Long = 0,
) {
    internal companion object {
        fun from(j: JSONObject): FeedPost {
            val id = j.getString("id")
            val r = j.optJSONArray("reactions")
            return FeedPost(
                id, j.getString("author"), j.optString("name"), j.optLong("tsMs"), j.optBoolean("out"), j.optString("kind"),
                if (j.isNull("text")) null else j.getString("text"), MediaRef.from(j.optJSONObject("media")),
                List(r?.length() ?: 0) { Reaction(id, r!!.getJSONObject(it).getString("peer"), r.getJSONObject(it).getString("emoji")) },
                if (j.isNull("replyTo")) null else j.optString("replyTo").ifEmpty { null },
                TextEntity.list(j.optJSONArray("entities")),
                j.optLong("editedMs"),
            )
        }
    }
}

/** [peer]'s reaction ([emoji]) to message or post [id] (hex). */
data class Reaction(val id: String, val peer: String, val emoji: String) {
    internal companion object {
        fun list(json: String): List<Reaction> {
            val a = JSONArray(json)
            return List(a.length()) { a.getJSONObject(it).let { o -> Reaction(o.getString("id"), o.getString("peer"), o.getString("emoji")) } }
        }
    }
}

// ---- Google mailbox relays (SPEC 19) -------------------------------------------------------

/** What one relay did over some days, on this phone. [ms] is the average round trip. */
data class GasDay(
    val req: Long, val puts: Long, val takes: Long, val itemsOut: Long, val itemsIn: Long,
    val bytesOut: Long, val bytesIn: Long, val errors: Long, val ms: Long,
) {
    internal companion object {
        fun from(j: JSONObject?) = GasDay(
            j?.optLong("req") ?: 0, j?.optLong("puts") ?: 0, j?.optLong("takes") ?: 0, j?.optLong("items_out") ?: 0,
            j?.optLong("items_in") ?: 0, j?.optLong("bytes_out") ?: 0, j?.optLong("bytes_in") ?: 0,
            j?.optLong("errors") ?: 0, j?.optLong("ms") ?: 0,
        )
    }
}

/** One day of the relay's own count, read from its script (the owner's view). */
data class GasServerDay(val day: Long, val requests: Long, val devices: Long, val recipients: Long)

/**
 * The relay's own statistics, read from its script with the owner's key: requests of everyone,
 * the devices it helped (the owner's own phone is not counted) and the recipients who had
 * something left there. The counts of devices and recipients are approximate (SPEC 19.7).
 */
data class GasServer(
    val atMs: Long, val version: Int, val paused: Boolean, val maxClients: Int,
    val requests: Long, val puts: Long, val takes: Long, val itemsIn: Long, val itemsOut: Long,
    val devices: Long, val recipients: Long, val days: List<GasServerDay>,
) {
    internal companion object {
        fun from(j: JSONObject?): GasServer? {
            if (j == null) return null
            val d = j.optJSONObject("data") ?: return null
            val t = d.optJSONObject("today")
            val cfg = d.optJSONObject("cfg")
            val a = d.optJSONArray("days")
            val days = if (a == null) emptyList() else List(a.length()) {
                val x = a.getJSONObject(it)
                GasServerDay(x.optLong("d"), x.optLong("r"), x.optLong("c"), x.optLong("g"))
            }
            return GasServer(
                j.optLong("at"), d.optInt("ver"), cfg?.optBoolean("paused") ?: false, cfg?.optInt("max_clients") ?: 0,
                t?.optLong("r") ?: 0, t?.optLong("p") ?: 0, t?.optLong("t") ?: 0, t?.optLong("io") ?: 0, t?.optLong("ii") ?: 0,
                t?.optLong("c") ?: 0, t?.optLong("g") ?: 0, days,
            )
        }
    }
}

/**
 * A mailbox relay of the user's list. [state]: `draft` (script shown, deployment not pasted),
 * `off`, `new` (not tried yet), `ok`, or the last failure: `front`, `network`, `timeout`,
 * `not_found`, `access`, `auth`, `paused`, `limit`, `quota`, `busy`, `remote`, `bad_response`.
 */
data class GasRelayView(
    val rid: String, val name: String, val mine: Boolean, val draft: Boolean, val enabled: Boolean,
    val announce: Boolean, val state: String, val lastOkMs: Long, val ms: Long, val version: Int,
    val today: GasDay, val week: GasDay, val total: GasDay, val waiting: Int, val server: GasServer?, val tail: String,
    /** One of ours, opened to everyone. */
    val isPublic: Boolean = false,
    /** Someone's public relay we borrowed, having none of our own. */
    val isBorrowed: Boolean = false,
    /** A combined relay (SPEC 19.13): a Cloudflare Worker with a Google door. */
    val combined: Boolean = false,
    /** Its Worker's host, empty until the Worker is made. */
    val worker: String = "",
    /** The door the last call went through here: `cloudflare`, `google`, or empty. */
    val door: String = "",
) {
    internal companion object {
        fun from(j: JSONObject) = GasRelayView(
            j.getString("rid"), j.optString("name"), j.optBoolean("mine"), j.optBoolean("draft"), j.optBoolean("enabled"),
            j.optBoolean("announce"), j.optString("state"), j.optLong("last_ok_ms"), j.optLong("ms"), j.optInt("version"),
            GasDay.from(j.optJSONObject("today")), GasDay.from(j.optJSONObject("week")), GasDay.from(j.optJSONObject("total")),
            j.optInt("waiting"), GasServer.from(j.optJSONObject("server")), j.optString("tail"),
            j.optBoolean("public"), j.optBoolean("borrowed"),
            j.optBoolean("combined"), j.optString("worker"), j.optString("door"),
        )
    }
}

/** A public relay this phone knows of: [owner] is the announcer's key (hex). */
data class GasPublicRelay(val rid: String, val name: String, val owner: String, val untilMs: Long, val mine: Boolean, val borrowed: Boolean, val avoidUntil: Long) {
    internal companion object {
        fun from(j: JSONObject) = GasPublicRelay(
            j.getString("rid"), j.optString("name"), j.optString("owner"), j.optLong("until_ms"), j.optBoolean("mine"), j.optBoolean("borrowed"), j.optLong("avoid_until"),
        )
    }
}

/** The list, the way to Google the phone found by itself, and which relays our cards name. */
data class GasOverview(
    val relays: List<GasRelayView>, val front: String, val frontMs: Long,
    val announced: List<String>, val running: Boolean, val waiting: Int,
    /** Public relays of others this phone knows of, and whether it borrows them while it has none. */
    val publicKnown: Int = 0, val borrowAuto: Boolean = true,
) {
    internal companion object {
        fun from(j: JSONObject): GasOverview {
            val a = j.optJSONArray("relays")
            val r = if (a == null) emptyList() else List(a.length()) { GasRelayView.from(a.getJSONObject(it)) }
            val n = j.optJSONArray("announced")
            val an = if (n == null) emptyList() else List(n.length()) { n.getString(it) }
            return GasOverview(r, j.optString("front"), j.optLong("front_ms"), an, j.optBoolean("running"), j.optInt("waiting"), j.optInt("public_known"), j.optBoolean("borrow_auto", true))
        }
    }
}

/**
 * One Nostr relay (SPEC 20). [state]: `open`, `connecting`, `resting`, or where it failed (`dns`,
 * `tcp`, `tls`, `ws`, `io`). [way]: `plain` or `record-split`. [reads]: kept open for our messages
 * (else only written to).
 */
data class NostrRelay(
    val relay: String, val state: String, val way: String, val reads: Boolean, val writes: Boolean,
    val eventsIn: Long, val eventsOut: Long,
) {
    internal companion object {
        fun from(j: JSONObject) = NostrRelay(
            j.optString("relay"), j.optString("state"), j.optString("way"), j.optBoolean("reads"), j.optBoolean("writes"),
            j.optLong("events_in"), j.optLong("events_out"),
        )
    }
}

/**
 * One relay of the list for the relay picker (SPEC 20.6). [own]: ours (T54); [preferred]: the one
 * the user chose (always read and first, the others its backup). [state]: `open`, `connecting`,
 * `resting`, `idle` (not in use now) or where its last connection failed (`dns`, `tcp`, `tls`,
 * `ws`, `io`). [ms]: the last measuring's time to an answer, null when it failed ([failed], the
 * stage) or was never measured on this network ([measuredMs] 0).
 */
data class NostrChoice(
    val relay: String, val name: String, val own: Boolean, val preferred: Boolean, val state: String,
    val reads: Boolean, val writes: Boolean, val ms: Long?, val failed: String?, val way: String, val measuredMs: Long,
) {
    internal companion object {
        fun from(j: JSONObject) = NostrChoice(
            j.optString("relay"), j.optString("name"), j.optBoolean("own"), j.optBoolean("preferred"), j.optString("state"),
            j.optBoolean("reads"), j.optBoolean("writes", true),
            if (j.isNull("ms")) null else j.optLong("ms"), if (j.isNull("failed")) null else j.optString("failed"),
            j.optString("way"), j.optLong("measured_ms"),
        )

        fun list(json: String): List<NostrChoice> {
            val a = JSONArray(json)
            return List(a.length()) { from(a.getJSONObject(it)) }
        }
    }
}

/** A relay just made: the script to paste at script.google.com. */
data class GasDraft(val rid: String, val script: String)

/**
 * A combined relay just made (SPEC 19.13): [workerScript] is the Worker for the owner's Cloudflare
 * account (an ES module with a Durable Object class `Mailbox`, bound as `MBOX`).
 */
data class GasComboDraft(val rid: String, val workerScript: String)

/**
 * One step of a relay test: `worker` (a combined relay's Worker straight), `front`, `script`,
 * `mailbox`. [code] is empty when it worked.
 */
data class GasTestStep(val step: String, val ok: Boolean, val code: String)

data class GasTestReport(
    val steps: List<GasTestStep>, val front: String, val frontMs: Long, val ms: Long,
    val owner: Boolean, val version: Int, val paused: Boolean, val ok: Boolean,
    /** The owner opened it to everyone (script version 2). */
    val isPublic: Boolean = false,
    /** A combined relay: how its Worker was reached straight, empty when not; whether that works here. */
    val worker: String = "",
    val workerOk: Boolean = false,
) {
    internal companion object {
        fun from(j: JSONObject): GasTestReport {
            val a = j.optJSONArray("steps")
            val steps = if (a == null) emptyList() else List(a.length()) {
                val x = a.getJSONObject(it)
                GasTestStep(x.optString("step"), x.optBoolean("ok"), x.optString("code"))
            }
            return GasTestReport(steps, j.optString("front"), j.optLong("front_ms"), j.optLong("ms"), j.optBoolean("owner"), j.optInt("version"), j.optBoolean("paused"), j.optBoolean("ok"), j.optBoolean("public"), j.optString("worker"), j.optBoolean("worker_ok"))
        }
    }
}

/** What a share link holds, before it is added. */
data class GasShareInfo(val name: String, val tail: String, val rid: String)

/** A channel or group the public list's search found (q6, T70): its card, and whether we are in it. */
data class DirResult(
    /** The chat's id, hex. */
    val id: String,
    val name: String,
    val about: String,
    /** A channel (only admins post) rather than a group. */
    val broadcast: Boolean,
    /** Members, as its owner's phone counts them. */
    val members: Int,
    /** An invite link (`mlmchat://g/…`) for it. */
    val link: String,
    val createdMs: Long,
    val member: Boolean,
    /** Its list photo's hash; empty for none ([ChatNode.dirPhoto] gives the photo once read). */
    val photo: String = "",
) {
    internal companion object {
        fun from(j: JSONObject) = DirResult(
            j.getString("id"), j.getString("name"), j.optString("about"), j.optBoolean("broadcast"),
            j.optInt("members"), j.getString("link"), j.optLong("createdMs"), j.optBoolean("member"),
            j.optString("photo"),
        )
    }
}
