package org.mlmchat.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File

/** The user's own identity, as the core reports it. */
data class Me(
    /** The identity key `sk` (SPEC 2), hex. It is the user id on the network. */
    val sk: String,
    /** The name the user chose. */
    val name: String,
    /** The short name everyone sees (T14): name, «·» and four digits of [code], e.g. `ehsan·4821`. */
    val displayName: String,
    /** The user's 30-digit half of every security number (SPEC 14.1). */
    val code: String,
)

/**
 * A new identity whose keys are already made, on this phone, but which is not named or saved yet.
 * An app shows [tag] while the user is still choosing a name, then calls [create].
 */
class PendingIdentity : Closeable {
    private var handle: Long = NativeCore.prepare()

    /** The 30-digit half of this identity's security numbers. */
    val code: String = NativeCore.preparedCode(handle)

    /** The four digits that follow the name (T14). */
    val tag: String get() = code.substring(0, 4)

    /** Names the identity and saves it, encrypted, under [ChatNode.dir]. Usable once. */
    @Synchronized
    fun create(context: Context, name: String): ChatNode {
        check(handle != 0L) { "this identity is already created or closed" }
        val h = handle
        handle = 0L // create() consumes it even when it fails
        val dir = ChatNode.dir(context).apply { mkdirs() }
        return ChatNode(NativeCore.create(h, File(dir, ChatNode.DB).path, DbKey.get(context), name)).apply { tellDevice(context); tellClient(context) }
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) {
            NativeCore.dropPrepared(handle)
            handle = 0L
        }
    }
}

/** One user's node: identity, contacts and messages in the encrypted database (SPEC, PLAN 3). */
class ChatNode internal constructor(private var handle: Long) : Closeable {

    /**
     * Read once and kept: only [setName] changes it, and a bridge asks for it per message shown,
     * which waited behind every slow core call (mlmvpn/crashes #2884, #2986: freezes in `me`).
     */
    @Volatile
    private var meKept: Me? = null

    val me: Me
        get() = meKept ?: synchronized(this) {
            check(handle != 0L) { "closed" }
            meFrom(JSONObject(NativeCore.me(handle))).also { meKept = it }
        }

    /**
     * Renames this identity; the four digits stay (T14). The new card goes to every contact not
     * blocked, whose phones show the new name once it arrives ([ChatEvent.ContactChanged] there).
     * Returns the new [Me]. Throws [CoreException] for a name the core refuses.
     */
    @Synchronized
    fun setName(name: String): Me = meFrom(JSONObject(NativeCore.setName(live(), name))).also { meKept = it }

    private fun meFrom(j: JSONObject) = Me(j.getString("sk"), j.getString("name"), j.getString("displayName"), j.getString("code"))

    /**
     * Starts the phase-1 direct transport (LAN with private beacons, direct IPv6) and delivery,
     * listening on [port] when it is free (keep the same one across starts, so the cards contacts
     * already hold stay valid), else on any free port; 0 = any. Returns the addresses this
     * phone's cards carry, `ip:port` each. Call once.
     */
    @Synchronized
    fun startDirect(port: Int = 0): List<String> {
        val a = JSONArray(NativeCore.startDirect(live(), port))
        return List(a.length()) { a.getString(it) }
    }

    /**
     * Starts the p2p transport (phase 3): reservations on volunteers (from the contacts' cards and
     * [boot]), this phone's presence record in the private DHT, and delivery to contacts on other
     * networks, directly when possible and through a volunteer when not. Everything is TLS 1.3
     * with the hybrid post-quantum exchange underneath, Noise inside. [port] 0 = any. A phone is
     * not a volunteer unless the user chose so ([volunteer], PLAN 11). Call once, after [startDirect].
     */
    @Synchronized
    fun startP2p(port: Int = 0, boot: List<String> = emptyList(), volunteer: Boolean = false): NetStatus {
        val cfg = JSONObject().put("port", port).put("boot", JSONArray(boot)).put("volunteer", volunteer).put("upnp", volunteer)
        return NetStatus.from(JSONObject(NativeCore.startP2p(live(), cfg.toString())))
    }

    /** Stops the p2p transport, to start it again with another config (the volunteer role turned on or off). */
    @Synchronized
    fun stopP2p() = NativeCore.stopP2p(live())

    /**
     * Limits every connection, both ways, to these address ranges (`a.b.c.d/n`, SPEC 15.10); the
     * local network is always allowed. An empty list lifts the limit. Takes effect at once: open
     * connections drop and the volunteers are climbed to again within the limit. Returns how many
     * ranges are in force (merged).
     */
    @Synchronized
    fun setScope(ranges: List<String>): Int = NativeCore.setScope(live(), ranges.joinToString("\n"))

    /**
     * Could this phone be a volunteer on the network it is on now (SPEC 15.11)? Asks the router
     * read-only questions only, a couple of seconds: call it off the main thread. Not synchronized,
     * so the other calls are not held up meanwhile.
     */
    fun volunteerCheck(): VolunteerCheck = VolunteerCheck.from(JSONObject(NativeCore.volunteerCheck(live())))

    // ---- Bluetooth LE (SPEC 16): for the radio code ([org.mlmchat.core.ble.BleRadio]) ----

    /** Starts the Bluetooth transport («nearby» on); the radio then feeds it. Safe to call again. */
    @Synchronized
    fun bleStart() = NativeCore.bleStart(live())

    @Synchronized
    fun bleStop() = NativeCore.bleStop(live())

    /** The 8-byte tag to advertise now; it changes every 10 minutes. */
    @Synchronized
    fun bleAdvertTag(): ByteArray = NativeCore.bleAdvertTag(live())

    /** The next thing the radio must do, waiting up to [timeoutMs]. Not synchronized: it waits. */
    fun bleNext(timeoutMs: Int): BleCommand? = NativeCore.bleNext(live(), timeoutMs)?.let { BleCommand.from(JSONObject(it)) }

    /** The radio's callbacks: not synchronized, they come from Bluetooth threads and must not wait. */
    fun bleSeen(addr: String, tag: ByteArray) = NativeCore.bleSeen(live(), addr, tag)
    fun bleConnected(link: Int, inbound: Boolean) = NativeCore.bleConnected(live(), link, inbound)
    fun bleData(link: Int, data: ByteArray) = NativeCore.bleData(live(), link, data)
    fun bleClosed(link: Int) = NativeCore.bleClosed(live(), link)

    /** Contacts beside this phone right now (0 when Bluetooth is off). */
    @Synchronized
    fun bleNearby(): Int = NativeCore.bleNearby(live())

    // ---- «add nearby» (SPEC 16.4): strangers side by side add each other, no QR ----

    /** Open the list for two minutes under [name]: over Bluetooth while it runs, and over Wi-Fi (the same network or a hotspot) when [wifi]. */
    fun pairStart(name: String, wifi: Boolean): Boolean = NativeCore.pairStart(live(), name, wifi)

    fun pairState(): PairState = PairState.from(JSONObject(NativeCore.pairState(live())))

    /** Ask someone in the list: both screens show the same four emoji. */
    fun pairAsk(id: String): Boolean = NativeCore.pairAsk(live(), id)

    /** The person said the emoji match: our card goes to them, theirs is added once it is here. */
    fun pairConfirm(id: String): Boolean = NativeCore.pairConfirm(live(), id)

    fun pairDecline(id: String): Boolean = NativeCore.pairDecline(live(), id)

    fun pairStop() = NativeCore.pairStop(live())

    // ---- groups and channels (SPEC 7) ----

    /** A new group, or a channel ([channel]: only the owner and admins post); its id (hex). */
    fun groupCreate(name: String, about: String, channel: Boolean): String = NativeCore.groupCreate(live(), name, about, channel)

    fun groups(): List<Group> {
        val a = JSONArray(NativeCore.groups(live()))
        return List(a.length()) { Group.from(a.getJSONObject(it)) }
    }

    fun groupState(group: String): Group? = NativeCore.groupState(live(), group)?.let { Group.from(JSONObject(it)) }

    /** Add a contact: they get the group and the members' keys. */
    fun groupAdd(group: String, peer: String) = NativeCore.groupAdd(live(), group, peer)

    /** Remove a member ([ban]: for good); with our own sk, leave. */
    fun groupRemove(group: String, peer: String, ban: Boolean) = NativeCore.groupRemove(live(), group, peer, ban)

    /** Lift a ban without taking the member back (an admin with the right to ban); they may be added again. */
    fun groupUnban(group: String, peer: String) = NativeCore.groupUnban(live(), group, peer)

    /** A group's slow mode in seconds (at most an hour), 0 to end it (an admin with the right to ban; q13). */
    fun groupSetSlow(group: String, seconds: Int) = NativeCore.groupSetSlow(live(), group, seconds)

    fun groupSetAdmin(group: String, peer: String, admin: Boolean, rights: Int, title: String) = NativeCore.groupSetAdmin(live(), group, peer, admin, rights, title)

    fun groupSetInfo(group: String, name: String, about: String) = NativeCore.groupSetInfo(live(), group, name, about)

    /**
     * A post in the group; [replyTo] is the seq of the group message it answers (0: none), and
     * [entities] its formatting and mentions.
     */
    @JvmOverloads
    fun groupSendText(group: String, text: String, replyTo: Long = 0, entities: List<TextEntity> = emptyList()): String =
        if (replyTo > 0 || entities.isNotEmpty()) {
            NativeCore.groupSendTextRich(live(), group, text, TextEntity.toJson(entities).toString(), replyTo)
        } else {
            NativeCore.groupSendText(live(), group, text)
        }

    // ---- files (SPEC 12, 17) ----

    /**
     * Send a file: [info] is JSON (kind, mime, name, caption, entities of the caption as
     * [TextEntity.toJson], thumb hex, w, h, durationMs, waveform hex). Returns the message id.
     * [replyTo] is the seq of the message it answers (0: none).
     */
    @JvmOverloads
    fun sendMedia(peer: String, path: String, info: JSONObject, replyTo: Long = 0): String =
        if (replyTo > 0) NativeCore.sendMediaReply(live(), peer, path, info.toString(), replyTo) else NativeCore.sendMedia(live(), peer, path, info.toString())

    @JvmOverloads
    fun groupSendMedia(group: String, path: String, info: JSONObject, replyTo: Long = 0): String =
        if (replyTo > 0) NativeCore.groupSendMediaReply(live(), group, path, info.toString(), replyTo) else NativeCore.groupSendMedia(live(), group, path, info.toString())

    /** Ask for a received file now (files over 1 MB wait for this). */
    fun fetchMedia(blob: String): Boolean = NativeCore.fetchMedia(live(), blob)

    /** `{"blob","have","total","complete"}` for the file an app short id names, or null. */
    fun mediaByShort(short: Long): JSONObject? = NativeCore.mediaByShort(live(), short)?.let { JSONObject(it) }

    /** Write the plain file named by [short] to [out] (it must be whole); its size. */
    fun openMediaShort(short: Long, out: String): Long = NativeCore.openMediaShort(live(), short, out)

    // ---- profile, group and channel photos (SPEC 10, 7.2) ----

    /** A contact's or group member's profile photo ([peer] = our own sk: ours), or null. */
    fun photoOf(peer: String): Photo? = NativeCore.photoOf(live(), peer)?.let { Photo.from(JSONObject(it)) }

    /** Our profile photo from an image file ([path] null: removed), with its tiny preview ([thumb] hex). Every contact gets it. */
    fun setPhoto(path: String?, thumb: String): Photo? = NativeCore.setPhoto(live(), path ?: "", thumb)?.let { Photo.from(JSONObject(it)) }

    /** A group's or channel's photo ([path] null: removed). Needs the right to change its info. */
    fun groupSetPhoto(group: String, path: String?, thumb: String): Group = Group.from(JSONObject(NativeCore.groupSetPhoto(live(), group, path ?: "", thumb)))

    /** Members' default rights ([Group.defaults] bits); needs the right to ban. */
    fun groupSetDefaults(group: String, rights: Int): Group = Group.from(JSONObject(NativeCore.groupSetDefaults(live(), group, rights)))

    /**
     * One member's own rights (SPEC 7.2 `Restrict`; needs the right to ban): [rights] are the member
     * bits left to them, on top of the defaults, until [untilMs]; [untilMs] 0 lifts it. Not for the
     * owner or an admin.
     */
    fun groupRestrict(group: String, peer: String, rights: Int, untilMs: Long): Group =
        Group.from(JSONObject(NativeCore.groupRestrict(live(), group, peer, rights, untilMs)))

    /** Ends a group or channel for everyone; its owner only. */
    fun groupDissolve(group: String): Group = Group.from(JSONObject(NativeCore.groupDissolve(live(), group)))

    // ---- invite links (SPEC 7.4) ----

    /** A new `mlmchat://g/` invite link ([expMs] 0: never expires; [maxUses] 0: no limit). Needs the right to invite. */
    fun groupInviteCreate(group: String, needApproval: Boolean, expMs: Long = 0, maxUses: Int = 0): String =
        NativeCore.groupInviteCreate(live(), group, needApproval, expMs, maxUses)

    /** The group's valid invites; [Invite.link] only for those this phone made. */
    fun groupInvites(group: String): List<Invite> {
        val a = JSONArray(NativeCore.groupInvites(live(), group))
        return List(a.length()) { Invite.from(a.getJSONObject(it)) }
    }

    fun groupInviteRevoke(group: String, invite: String) = NativeCore.groupInviteRevoke(live(), group, invite)

    /** What a link says, without joining. Throws [CoreException] for a link that is not an invite. */
    fun inviteInfo(link: String): InviteInfo = InviteInfo.from(JSONObject(NativeCore.inviteInfo(live(), link)))

    /** Ask to join by a link; we are a member once the inviter (or an admin) adds us. Returns the group id. */
    fun groupJoin(link: String): String = NativeCore.groupJoin(live(), link)

    fun groupJoinRequests(group: String): List<JoinRequest> {
        val a = JSONArray(NativeCore.groupJoinRequests(live(), group))
        return List(a.length()) { a.getJSONObject(it).let { j -> JoinRequest(j.getString("sk"), j.optString("name"), j.optLong("atMs")) } }
    }

    fun groupJoinDecide(group: String, peer: String, approve: Boolean) = NativeCore.groupJoinDecide(live(), group, peer, approve)

    // ---- official feeds (T38) ----

    /** The feeds the app ships, as `{"feeds":[{"id","broadcast","name","owner"}],"seed":[hex]}`. Call once, before the transports. */
    fun setFeeds(config: JSONObject) = NativeCore.setFeeds(live(), config.toString())

    fun feedInfo(feed: String): FeedInfo? = NativeCore.feedInfo(live(), feed)?.let { FeedInfo.from(JSONObject(it)) }

    /** The newest [limit] posts that count, oldest first. */
    fun feedPosts(feed: String, limit: Int): List<FeedPost> {
        val a = JSONArray(NativeCore.feedPosts(live(), feed, limit))
        return List(a.length()) { FeedPost.from(a.getJSONObject(it)) }
    }

    fun feedSendText(feed: String, text: String): String = NativeCore.feedSendText(live(), feed, text)

    /** A post with its formatting, answering post [replyTo] (its id; null for none) of the same feed. */
    fun feedSendText(feed: String, text: String, replyTo: String?, entities: List<TextEntity>): String =
        NativeCore.feedSendTextRich(live(), feed, text, TextEntity.toJson(entities).toString(), replyTo ?: "")

    fun feedSendMedia(feed: String, path: String, info: JSONObject): String = NativeCore.feedSendMedia(live(), feed, path, info.toString())

    /** A file post answering post [replyTo] (its id; null for none) of the same feed. */
    fun feedSendMedia(feed: String, path: String, info: JSONObject, replyTo: String?): String =
        NativeCore.feedSendMediaReply(live(), feed, path, info.toString(), replyTo ?: "")

    fun feedDelete(feed: String, post: String) = NativeCore.feedDelete(live(), feed, post)

    /** New text for one of our own posts in [feed] (a photo's or file's caption), with its formatting (T61). */
    fun feedEdit(feed: String, post: String, text: String, entities: List<TextEntity> = emptyList()) =
        NativeCore.feedEdit(live(), feed, post, text, TextEntity.toJson(entities).toString())

    /** The feed's photo from an image file (null: none), with its preview (hex). Admins only. */
    fun feedSetPhoto(feed: String, path: String?, thumb: String) = NativeCore.feedSetPhoto(live(), feed, path ?: "", thumb)

    fun feedBan(feed: String, peer: String, untilMs: Long) = NativeCore.feedBan(live(), feed, peer, untilMs)

    /**
     * Keeps the official feeds' owner key on this phone, from its secret half ([secretHex]): the
     * owner's main phone then names the admins here. Throws unless it owns a feed this app ships.
     */
    fun feedOwnerImport(secretHex: String) = NativeCore.feedOwnerImport(live(), secretHex)

    /** Whether this phone holds the owner key of [feed]. */
    fun feedOwnerHeld(feed: String): Boolean = NativeCore.feedOwnerHeld(live(), feed)

    /** Names [feed]'s admins (sk hex; the first shown as its creator). Only with the owner key here. */
    fun feedSetAdmins(feed: String, admins: List<String>) = NativeCore.feedSetAdmins(live(), feed, admins.joinToString(","))

    fun feedSyncNow() = NativeCore.feedSyncNow(live())

    /**
     * An official chat is on the screen: say so every few seconds while it stays there, and the
     * official copy is read every ten seconds (not every twenty minutes).
     */
    fun feedWatch() = NativeCore.feedWatch(live())

    /** Everyone in the official feeds (all Gereh users active lately), estimated phone to phone. */
    fun feedMemberCount(): Long = NativeCore.feedMemberCount(live())

    /** The feed's name and description (empty: the app's own). Admins only. */
    fun feedSetMeta(feed: String, title: String, about: String) = NativeCore.feedSetMeta(live(), feed, title, about)

    /** Our reaction to a post; an empty [emoji] takes it back. */
    fun feedReact(feed: String, post: String, emoji: String) = NativeCore.feedReact(live(), feed, post, emoji)

    /** Pin a post of an official feed for everyone, or take its pin off (admins; T65). */
    fun feedPin(feed: String, post: String, unpin: Boolean) = NativeCore.feedPin(live(), feed, post, unpin)

    /** An admin sets the official group's slow mode in seconds, 0 to end it (T67). */
    fun feedSetSlow(feed: String, seconds: Int) = NativeCore.feedSetSlow(live(), feed, seconds)

    /** Gives the core this phone's own value ([deviceOf]); at each open, before any post (T74). */
    internal fun tellDevice(context: Context) = NativeCore.setDevice(live(), deviceOf(context))

    /**
     * Gives the core the app it runs in (SPEC 5 `Client`, T75): its package, its version code without
     * a per-processor offset, and its version name, put on everything this phone sends; at each open.
     */
    internal fun tellClient(context: Context) {
        runCatching {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            @Suppress("DEPRECATION")
            val code = if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
            NativeCore.setClient(live(), context.packageName, (code % 1_000_000).toInt(), pi.versionName.orEmpty())
        }
    }

    /** The apps people write from (T75), changed at or after [sinceMs] by this phone's clock; everyone for 0. */
    fun clientsSince(sinceMs: Long): List<PeerClient> {
        val a = JSONArray(NativeCore.clientsSince(live(), sinceMs))
        return List(a.length()) { PeerClient.from(a.getJSONObject(it)) }
    }

    /** The public list of channels and groups (q6, T70): what a search finds, best first. */
    fun dirSearch(query: String, limit: Int = 30): List<DirResult> {
        val a = JSONArray(NativeCore.dirSearch(live(), query, limit))
        return (0 until a.length()).map { DirResult.from(a.getJSONObject(it)) }
    }

    /** Reads the list from our relays; blocks on the network. How many cards are kept, -1 for none reached. */
    fun dirRefresh(): Int = NativeCore.dirRefresh(live())

    /** Whether the list kept here is older than an hour. */
    fun dirStale(): Boolean = NativeCore.dirStale(live())

    /** Whether this phone's chats' cards are due to be renewed. */
    fun dirPublishDue(): Boolean = NativeCore.dirPublishDue(live())

    /** Renews the cards of the chats this phone owns; blocks on the network. */
    fun dirPublish(): Int = NativeCore.dirPublish(live())

    /** Whether a chat of ours is in the public list (all are, until the owner takes one out). */
    fun dirListed(group: String): Boolean = NativeCore.dirListed(live(), group)

    /** The owner puts a chat in the public list or takes it out. */
    fun dirSetListed(group: String, listed: Boolean) = NativeCore.dirSetListed(live(), group, listed)

    /** An owned chat's small list photo, a JPEG ([jpeg] null: none); it goes with the next cards. Whether it changed. */
    fun dirSetPhoto(group: String, jpeg: ByteArray?): Boolean = NativeCore.dirSetPhoto(live(), group, jpeg ?: ByteArray(0))

    /** A listed chat's photo, once [dirFetchPhotos] read it; null otherwise. */
    fun dirPhoto(group: String): ByteArray? = NativeCore.dirPhoto(live(), group)

    /** Reads from our relays the photos of these chats (a search's results) not here yet. Blocks on the network. */
    fun dirFetchPhotos(groups: List<String>): Int = NativeCore.dirFetchPhotos(live(), org.json.JSONArray(groups).toString())

    /** Pin message [seq] of the chat with [peer] for both, or take its pin off (T65). */
    fun pin(peer: String, seq: Long, unpin: Boolean) = NativeCore.pin(live(), peer, seq, unpin)

    /** The chat's pinned messages, by seq, the newest last. */
    fun pinned(peer: String): List<Long> = JSONArray(NativeCore.pinned(live(), peer)).let { a -> List(a.length()) { a.getLong(it) } }

    /** Pin message [seq] of a group for every member, or take its pin off (T65). */
    fun groupPin(group: String, seq: Long, unpin: Boolean) = NativeCore.groupPin(live(), group, seq, unpin)

    /** The group's pinned messages on this phone, by seq, the newest last. */
    fun groupPinned(group: String): List<Long> = JSONArray(NativeCore.groupPinned(live(), group)).let { a -> List(a.length()) { a.getLong(it) } }

    // ---- reactions (SPEC 5 `Reaction`) ----

    /** Our reaction to message [id] (hex) of the chat with [peer]; an empty [emoji] takes it back. */
    fun react(peer: String, id: String, emoji: String) = NativeCore.react(live(), peer, id, emoji)

    /** The reactions standing in the chat with [peer], oldest first. */
    fun reactions(peer: String): List<Reaction> = Reaction.list(NativeCore.reactions(live(), peer))

    /** Our reaction to message [id] (hex) of a group or channel; an empty [emoji] takes it back. */
    fun groupReact(group: String, id: String, emoji: String) = NativeCore.groupReact(live(), group, id, emoji)

    fun groupReactions(group: String): List<Reaction> = Reaction.list(NativeCore.groupReactions(live(), group))

    // ---- polls (m4, T69; SPEC 5 Poll and PollVote) ----
    /** A poll in a group or channel, in answer to message [replyTo] (its seq; 0 for none). Returns its id (hex). */
    fun groupSendPoll(group: String, poll: PollInfo, replyTo: Long = 0): String =
        NativeCore.groupSendPoll(live(), group, poll.toJson().toString(), replyTo)

    /** Our vote on poll [poll] (hex id) of a group or channel; no [choices] takes it back. */
    fun groupVote(group: String, poll: String, choices: List<Int>) = NativeCore.groupVote(live(), group, poll, choices.toIntArray())

    /** Where poll [poll] of a group or channel stands, counted on this phone. */
    fun pollResults(group: String, poll: String): PollResults = PollResults.from(JSONObject(NativeCore.pollResults(live(), group, poll)))

    // ---- delete for everyone (SPEC 5.2) ----

    /** One-to-one messages by [seqs], for both sides: each contact takes its copy down too. */
    fun deleteForEveryone(seqs: LongArray): Int = NativeCore.deleteForEveryone(live(), seqs)

    /** Group or channel messages by [seqs]: for every member when [everyone] (ours, or anyone's for an admin), else here only. */
    fun groupDeleteSeqs(seqs: LongArray, everyone: Boolean): Int = NativeCore.groupDeleteSeqs(live(), seqs, everyone)

    // ---- edit (SPEC 5 `Edit`) ----

    /** A new [text] for a one-to-one message we sent (by [seq]), or a caption for a media message, on both sides. */
    @Synchronized
    @JvmOverloads
    fun editText(seq: Long, text: String, entities: List<TextEntity> = emptyList()) =
        NativeCore.editText(live(), seq, text, TextEntity.toJson(entities).toString())

    /** The same for a group or channel message we sent, for every member. */
    @Synchronized
    @JvmOverloads
    fun groupEditText(seq: Long, text: String, entities: List<TextEntity> = emptyList()) =
        NativeCore.groupEditText(live(), seq, text, TextEntity.toJson(entities).toString())

    /** The newest [limit] messages of a group, oldest first. */
    fun groupMessages(group: String, limit: Int): List<GroupMessage> {
        val a = JSONArray(NativeCore.groupMessages(live(), group, limit))
        return List(a.length()) { GroupMessage.from(a.getJSONObject(it)) }
    }

    /** A page of a group or channel, oldest first (see [historyPage]). */
    fun groupMessagesPage(group: String, before: Long, after: Long, limit: Int): List<GroupMessage> {
        val a = JSONArray(NativeCore.groupMessagesPage(live(), group, before, after, limit))
        return List(a.length()) { GroupMessage.from(a.getJSONObject(it)) }
    }

    /** One message of a group or channel: by its [id] (hex), or by its [seq] when [id] is empty; null when it is not there. */
    fun groupMessage(group: String, id: String, seq: Long): GroupMessage? {
        val j = NativeCore.groupMessage(live(), group, id, seq)
        return if (j == "null") null else GroupMessage.from(JSONObject(j))
    }

    /** The messages of every group and channel stored after seq [after], oldest first, at most [limit]; [GroupMessage.group] says which. */
    fun groupMessagesSince(after: Long, limit: Int): List<GroupMessage> {
        val a = JSONArray(NativeCore.groupMessagesSince(live(), after, limit))
        return List(a.length()) { GroupMessage.from(a.getJSONObject(it)) }
    }

    /** What a chat list shows of [group] besides its last message, [after] being the last seq read here; counted, not read. */
    fun groupMarks(group: String, after: Long): GroupMarks = GroupMarks.from(JSONObject(NativeCore.groupMarks(live(), group, after)))

    /**
     * A volunteer's ledger (SPEC 15.12): peers using this phone now with their traffic, and the
     * kept totals (different peers served, bytes moved since the role was first on).
     */
    @Synchronized
    fun relayStatus(): RelayStatus = RelayStatus.from(JSONObject(NativeCore.relayStatus(live())))

    /** Disconnect one peer of the ledger by its [RelayPeer.name], and keep it out for an hour. */
    @Synchronized
    fun relayKick(name: String): Boolean = NativeCore.relayKick(live(), name)

    // ---- Nostr relays (SPEC 20) -----------------------------------------------------------

    /** Starts the Nostr transport (once): reads from the first relays that open here, writes to those a contact's card names. */
    @Synchronized
    fun nostrStart(config: String = "{}") {
        NativeCore.nostrStart(live(), config)
    }

    @Synchronized
    fun nostrStop() = NativeCore.nostrStop(live())

    /** The relays, open or not (empty when Nostr is off). */
    @Synchronized
    fun nostrRelays(): List<NostrRelay> {
        val a = JSONArray(NativeCore.nostrRelays(live()))
        return List(a.length()) { NostrRelay.from(a.getJSONObject(it)) }
    }

    /** Every relay of the list for the relay picker (SPEC 20.6), in the order they are used. */
    @Synchronized
    fun nostrChoices(): List<NostrChoice> = NostrChoice.list(NativeCore.nostrChoices(live()))

    /** Measures every relay now (the user's tap; never by itself) and gives the list again. Blocks for seconds. */
    fun nostrMeasure(): List<NostrChoice> = NostrChoice.list(NativeCore.nostrMeasure(live()))

    /** The relay the user prefers (always read and first; the others stay as backup), or null for automatic. Kept for the next start. */
    @Synchronized
    fun nostrPrefer(relay: String?) = NativeCore.nostrPrefer(live(), relay ?: "")

    // ---- Google mailbox relays (SPEC 19) ---------------------------------------------------
    // gasRelayTest, gasRelayServerStats and gasRelayAdmin call Google and block for seconds:
    // call them off the main thread.

    /** Starts the mailbox transport (once). Takes from every relay switched on; sends when nothing faster reaches a contact. */
    @Synchronized
    fun gasStart(config: String = "{}") {
        NativeCore.gasStart(live(), config)
    }

    @Synchronized
    fun gasStop() = NativeCore.gasStop(live())

    /** The app is in use (takes wait at the mailbox, so messages arrive in about a second) or in the background. */
    fun gasForeground(on: Boolean) = NativeCore.gasForeground(live(), on)

    @Synchronized
    fun gasOverview(): GasOverview = GasOverview.from(JSONObject(NativeCore.gasOverview(live())))

    /** Makes a relay: keys and the script to paste at script.google.com. The draft is kept. */
    @Synchronized
    fun gasRelayNew(name: String): GasDraft {
        val j = JSONObject(NativeCore.gasRelayNew(live(), name))
        return GasDraft(j.getString("rid"), j.getString("script"))
    }

    /** The script to paste at script.google.com; for a combined relay, its Google door (once its Worker is made). */
    @Synchronized
    fun gasRelayScript(rid: String): String = NativeCore.gasRelayScript(live(), rid)

    /** Makes a combined relay (SPEC 19.13): keys and the Worker for the owner's Cloudflare account. The draft is kept. */
    @Synchronized
    fun gasComboNew(name: String): GasComboDraft {
        val j = JSONObject(NativeCore.gasComboNew(live(), name))
        return GasComboDraft(j.getString("rid"), j.getString("worker_script"))
    }

    /** The Worker of a combined relay made here, to put on the account again. */
    @Synchronized
    fun gasComboScript(rid: String): String = NativeCore.gasComboScript(live(), rid)

    /** The Worker is on Cloudflare at [host] (or its whole address): kept; returns the Google door's script. */
    @Synchronized
    fun gasComboWorker(rid: String, host: String): String = NativeCore.gasComboWorker(live(), rid, host)

    /** A draft's deployment was pasted (the id, or the whole web-app address): the relay is on. Returns its new rid. */
    @Synchronized
    fun gasRelayFinish(rid: String, deployment: String): String = NativeCore.gasRelayFinish(live(), rid, deployment)

    /** What a share link holds, before it is added (throws on a bad link). */
    fun gasShareInfo(link: String): GasShareInfo {
        val j = JSONObject(NativeCore.gasShareInfo(link))
        return GasShareInfo(j.optString("name"), j.optString("tail"), j.optString("rid"))
    }

    /** Adds a friend's relay from its share link; returns its rid (the same one if it was in the list). */
    @Synchronized
    fun gasRelayAddShare(link: String): String = NativeCore.gasRelayAddShare(live(), link)

    /** The link to give a friend. It never carries the owner's key. */
    @Synchronized
    fun gasRelayShare(rid: String): String = NativeCore.gasRelayShare(live(), rid)

    /** Switch a relay on or off, name it in our cards or not, rename it. `null` leaves a field as it is. */
    @Synchronized
    fun gasRelaySet(rid: String, enabled: Boolean? = null, announce: Boolean? = null, name: String? = null) =
        NativeCore.gasRelaySet(live(), rid, enabled?.let { if (it) 1 else 0 } ?: -1, announce?.let { if (it) 1 else 0 } ?: -1, name)

    @Synchronized
    fun gasRelayRemove(rid: String) = NativeCore.gasRelayRemove(live(), rid)

    /** Tests a relay: the way to Google, the script and its key, a round trip through the mailbox. Blocks. */
    fun gasRelayTest(rid: String): GasTestReport = GasTestReport.from(JSONObject(NativeCore.gasRelayTest(live(), rid)))

    /** Reads the script's own statistics (relays made here only); they show in the list afterwards. Blocks. */
    fun gasRelayServerStats(rid: String): GasServer? = GasServer.from(JSONObject(NativeCore.gasRelayServerStats(live(), rid)))

    /** Pauses a relay made here for everyone but its owner (`paused`), and/or sets how many devices may use it a day (0 = no limit). Blocks. */
    fun gasRelayAdmin(rid: String, paused: Boolean? = null, maxClients: Int? = null) {
        NativeCore.gasRelayAdmin(live(), rid, paused?.let { if (it) 1 else 0 } ?: -1, maxClients ?: -1)
    }

    /**
     * One call to the user's own Telegram reader script (T68), apart from the relays, the same way
     * to Google: `{"r": answer, "up": bytes sent, "down": bytes received}`. Throws [CoreException]
     * with the relay's error code (`front`, `timeout`, `auth`, `quota`, `bad` …). Blocks.
     */
    fun gasScriptCall(deployment: String, body: JSONObject, timeoutS: Int): JSONObject =
        JSONObject(NativeCore.gasScriptCall(live(), deployment, body.toString(), timeoutS))

    /** A value the app keeps in this phone's encrypted database, or null. */
    fun appGet(key: String): String? = NativeCore.appGet(live(), key)

    /** Keeps a value for the app in this phone's encrypted database; null takes it away. */
    fun appPut(key: String, value: String?) = NativeCore.appPut(live(), key, value)

    /**
     * Opens a relay made here to everyone (a public relay, for phones with no relay of their own),
     * or closes it. Its script keeps the switch; one older than version 2 fails with `script_old`
     * (its code must be put in again). Blocks.
     */
    fun gasRelaySetPublic(rid: String, on: Boolean) = NativeCore.gasRelaySetPublic(live(), rid, on)

    /** The public relays this phone knows of, newest first. */
    fun gasPublicList(): List<GasPublicRelay> {
        val a = JSONArray(NativeCore.gasPublicList(live()))
        return List(a.length()) { GasPublicRelay.from(a.getJSONObject(it)) }
    }

    /** Whether public relays are borrowed while the user has no relay of their own (on unless switched off). */
    fun gasBorrowAuto(): Boolean = NativeCore.gasBorrowAuto(live())

    fun gasSetBorrowAuto(on: Boolean) = NativeCore.gasSetBorrowAuto(live(), on)

    /** The network state, for an honest status line (no «connected» that is not true). */
    @Synchronized
    fun netStatus(): NetStatus = NetStatus.from(JSONObject(NativeCore.netStatus(live())))

    /** What really happened with one contact, for the line under their name. */
    @Synchronized
    fun contactStatus(peer: String): ContactStatus = ContactStatus.from(JSONObject(NativeCore.contactStatus(live(), peer)))

    /**
     * Names the network the phone is on, for the route memory (SPEC 15.5): e.g. `cell:43235` for
     * Irancell data, `wifi:<hash>` for a Wi-Fi. Hash anything personal (an SSID) before passing it.
     */
    @Synchronized
    fun setNetwork(label: String) = NativeCore.setNetwork(live(), label)

    /** The phone moved to another network: reconnect, publish again, retry the queue now. */
    @Synchronized
    fun networkChanged() = NativeCore.networkChanged(live())

    /**
     * Waits up to [timeoutMs] for the next event; null when none came. Needs [startDirect] first.
     * Only one thread should wait at a time. Not synchronized, so it never blocks the other calls.
     */
    fun pollEvent(timeoutMs: Int): ChatEvent? =
        NativeCore.pollEvent(live(), timeoutMs)?.let { ChatEvent.from(JSONObject(it)) }

    /** A fresh face-to-face card as an `mlmchat://c/` link (for the QR), with a new one-time key. */
    @Synchronized
    fun cardLink(): String = NativeCore.cardLink(live())

    /** Adds a contact from their link or scanned QR, and sends them our card. */
    @Synchronized
    fun addContact(link: String): Contact = Contact.from(JSONObject(NativeCore.addContact(live(), link)))

    /**
     * Adds a member of one of our groups as a contact (their card from the group), and sends them
     * our card. Throws while no group has a card the member published themself.
     */
    @Synchronized
    fun addMemberContact(peer: String): Contact = Contact.from(JSONObject(NativeCore.addMemberContact(live(), peer)))

    @Synchronized
    fun contacts(): List<Contact> {
        val a = JSONArray(NativeCore.contacts(live()))
        return List(a.length()) { Contact.from(a.getJSONObject(it)) }
    }

    /**
     * Queues a text message; returns its id. Progress arrives as [ChatEvent.State]. [replyTo] is
     * the seq of the message it answers (0: none); the other phone sees it as an answer to the
     * same message ([Message.replyTo], SPEC 5 `reply_to`). [entities]: its formatting and
     * mentions ([Message.entities] on both phones).
     */
    @Synchronized
    @JvmOverloads
    fun sendText(peer: String, text: String, replyTo: Long = 0, entities: List<TextEntity> = emptyList()): String =
        if (replyTo > 0 || entities.isNotEmpty()) {
            NativeCore.sendTextRich(live(), peer, text, TextEntity.toJson(entities).toString(), replyTo)
        } else {
            NativeCore.sendText(live(), peer, text)
        }

    /** The newest [limit] messages with one contact, oldest first. */
    @Synchronized
    fun history(peer: String, limit: Int): List<Message> {
        val a = JSONArray(NativeCore.history(live(), peer, limit))
        return List(a.length()) { Message.from(a.getJSONObject(it)) }
    }

    /**
     * A page of the chat with [peer], oldest first: with [after] below 0 the newest [limit]
     * messages before seq [before] (0: the newest of all), else the oldest [limit] after seq
     * [after] (0: from the first). A screen asks for the page it shows: a long chat costs no more
     * than a short one, and no message is out of reach.
     */
    @Synchronized
    fun historyPage(peer: String, before: Long, after: Long, limit: Int): List<Message> {
        val a = JSONArray(NativeCore.historyPage(live(), peer, before, after, limit))
        return List(a.length()) { Message.from(a.getJSONObject(it)) }
    }

    /** One message of the chat with [peer]: by its [id] (hex), or by its [seq] when [id] is empty; null when it is not there. */
    @Synchronized
    fun message(peer: String, id: String, seq: Long): Message? {
        val j = NativeCore.message(live(), peer, id, seq)
        return if (j == "null") null else Message.from(JSONObject(j))
    }

    /** The one-to-one messages of every chat stored after seq [after], oldest first, at most [limit]; [Message.peer] says whose chat. */
    @Synchronized
    fun messagesSince(after: Long, limit: Int): List<Message> {
        val a = JSONArray(NativeCore.messagesSince(live(), after, limit))
        return List(a.length()) { Message.from(a.getJSONObject(it)) }
    }

    /** What a chat list shows of the chat with [peer] besides its last message, counted without reading the chat. */
    @Synchronized
    fun chatMarks(peer: String): ChatMarks = ChatMarks.from(JSONObject(NativeCore.chatMarks(live(), peer)))

    /** Marks everything from [peer] as read (and sends the read receipt); returns how many. */
    @Synchronized
    fun markRead(peer: String): Int = NativeCore.markRead(live(), peer)

    /**
     * Tells [peer] what we are doing in their chat right now (SPEC 5.4): [kind] is `typing`,
     * `upload_photo`, `upload_file`, `record_voice` or `cancel`. True if it went; one that cannot go
     * now (too soon after the last, no route open to their phone) is dropped, never queued.
     */
    fun chatAction(peer: String, kind: String): Boolean = NativeCore.chatAction(live(), peer, kind)

    /**
     * The app came to the front ([online]; say it again about every 55 s while it stays there) or
     * left it (SPEC 5.5): contacts with a route open to this phone hear «online» or «offline»,
     * unless our last seen is hidden. Returns how many were told.
     */
    fun setOnline(online: Boolean): Int = NativeCore.setOnline(live(), online)

    /** Show our last seen (the default) or hide it; hidden, nobody else's is shown here either. On this phone only. */
    @Synchronized
    fun setLastSeenVisible(visible: Boolean) = NativeCore.setLastSeenVisible(live(), visible)

    /** Whether our last seen is shown. */
    fun lastSeenVisible(): Boolean = NativeCore.lastSeenVisible(live())

    /**
     * The messages of [group] up to [maxSeq] were read here (SPEC 7.6): marked seen, and each one's
     * author told, in a group of at most 100 members, for messages under 7 days old. Returns how many.
     */
    @Synchronized
    fun groupMarkRead(group: String, maxSeq: Long): Int = NativeCore.groupMarkRead(live(), group, maxSeq)

    /** Who read our group message [seq] and when, the first first (Telegram's «seen by»). */
    fun groupReads(seq: Long): List<GroupRead> {
        val a = JSONArray(NativeCore.groupReads(live(), seq))
        return List(a.length()) { a.getJSONObject(it).let { r -> GroupRead(r.getString("sk"), r.getLong("atMs")) } }
    }

    /** The 60-digit number both people compare (SPEC 2). */
    @Synchronized
    fun securityNumber(peer: String): String = NativeCore.securityNumber(live(), peer)

    /**
     * Blocks or unblocks a contact. Blocking stays on this phone (SPEC 10): their messages are
     * dropped unread and nothing is sent to them; they are not told.
     */
    @Synchronized
    fun setBlocked(peer: String, blocked: Boolean) = NativeCore.setBlocked(live(), peer, blocked)

    /**
     * Deletes messages by [Message.seq] on this phone only; the other phone keeps its copy. A
     * message deleted while still queued is not sent. Returns how many were deleted.
     */
    @Synchronized
    fun deleteMessages(seqs: LongArray): Int = NativeCore.deleteMessages(live(), seqs)

    /** Deletes the conversation with [peer] up to [maxSeq] (all of it for 0), on this phone only. */
    @Synchronized
    fun clearHistory(peer: String, maxSeq: Long = 0): Int = NativeCore.clearHistory(live(), peer, maxSeq)

    /**
     * Every message on this phone goes, one-to-one and in groups and channels, with the files only
     * they carried; the account, contacts, groups, channels and the official feeds stay (q3).
     * Returns how many messages went.
     */
    @Synchronized
    fun clearAllMessages(): Int = NativeCore.clearAllMessages(live())

    /** How many frames wait to go out, to every peer. */
    @Synchronized
    fun outboxLen(): Int = NativeCore.outboxLen(live())

    private fun live(): Long {
        val h = handle
        check(h != 0L) { "closed" }
        return h
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) {
            NativeCore.close(handle)
            handle = 0L
        }
    }

    companion object {
        internal const val DB = "core.db"

        /** Where the core keeps its database: app-private storage, never backed up in the clear. */
        @JvmStatic fun dir(context: Context): File = File(context.noBackupFilesDir, "mlmchat")

        /** True once this phone has an identity. */
        @JvmStatic fun exists(context: Context): Boolean = File(dir(context), DB).isFile && DbKey.exists(context)

        /** Opens the identity an earlier [PendingIdentity.create] saved. */
        @JvmStatic fun open(context: Context): ChatNode =
            ChatNode(NativeCore.open(File(dir(context), DB).path, DbKey.get(context))).apply { tellDevice(context); tellClient(context) }

        /**
         * What Android tells of this phone that stays when the app's data is cleared or the app is
         * put on again: `ANDROID_ID`, new only after a factory reset (one per app signer since
         * Android 8). The core sends only its hash with each official feed, so an admin's ban holds
         * the phone, not only the account (T74). Empty when Android gives none.
         */
        @android.annotation.SuppressLint("HardwareIds")
        internal fun deviceOf(context: Context): ByteArray = runCatching {
            android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID).orEmpty().toByteArray(Charsets.UTF_8)
        }.getOrDefault(ByteArray(0))

        /** The bundled SQLCipher's version: a check that the native library is the right one. */
        @JvmStatic fun sqlcipherVersion(): String = NativeCore.sqlcipherVersion()
    }
}
