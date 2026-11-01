package org.mlmchat.core

/**
 * The Rust core's entry points (android/jni/src/lib.rs). Handles are owned by [PendingIdentity]
 * and [ChatNode], which free each exactly once; nothing else calls these.
 */
internal object NativeCore {
    init {
        System.loadLibrary("mlmchat")
    }

    @JvmStatic external fun prepare(): Long
    @JvmStatic external fun preparedCode(handle: Long): String
    @JvmStatic external fun dropPrepared(handle: Long)
    @JvmStatic external fun create(handle: Long, dbPath: String, dbKey: ByteArray, name: String): Long
    @JvmStatic external fun open(dbPath: String, dbKey: ByteArray): Long
    @JvmStatic external fun me(handle: Long): String
    @JvmStatic external fun setName(handle: Long, name: String): String
    @JvmStatic external fun close(handle: Long)
    @JvmStatic external fun sqlcipherVersion(): String

    @JvmStatic external fun startDirect(handle: Long, port: Int): String
    @JvmStatic external fun startP2p(handle: Long, config: String): String
    @JvmStatic external fun stopP2p(handle: Long)
    @JvmStatic external fun setScope(handle: Long, ranges: String): Int
    @JvmStatic external fun volunteerCheck(handle: Long): String
    @JvmStatic external fun bleStart(handle: Long)
    @JvmStatic external fun bleStop(handle: Long)
    @JvmStatic external fun bleAdvertTag(handle: Long): ByteArray
    @JvmStatic external fun bleNext(handle: Long, timeoutMs: Int): String?
    @JvmStatic external fun bleSeen(handle: Long, addr: String, tag: ByteArray)
    @JvmStatic external fun bleConnected(handle: Long, link: Int, inbound: Boolean)
    @JvmStatic external fun bleData(handle: Long, link: Int, data: ByteArray)
    @JvmStatic external fun bleClosed(handle: Long, link: Int)
    @JvmStatic external fun bleNearby(handle: Long): Int
    @JvmStatic external fun pairStart(handle: Long, name: String, wifi: Boolean): Boolean
    @JvmStatic external fun pairState(handle: Long): String
    @JvmStatic external fun pairAsk(handle: Long, id: String): Boolean
    @JvmStatic external fun pairConfirm(handle: Long, id: String): Boolean
    @JvmStatic external fun pairDecline(handle: Long, id: String): Boolean
    @JvmStatic external fun pairStop(handle: Long)
    @JvmStatic external fun groupCreate(handle: Long, name: String, about: String, channel: Boolean): String
    @JvmStatic external fun groups(handle: Long): String
    @JvmStatic external fun groupState(handle: Long, group: String): String?
    @JvmStatic external fun groupAdd(handle: Long, group: String, peer: String)
    @JvmStatic external fun groupRemove(handle: Long, group: String, peer: String, ban: Boolean)
    @JvmStatic external fun groupUnban(handle: Long, group: String, peer: String)
    @JvmStatic external fun groupSetSlow(handle: Long, group: String, seconds: Int)
    @JvmStatic external fun groupSetAdmin(handle: Long, group: String, peer: String, admin: Boolean, rights: Int, title: String)
    @JvmStatic external fun groupSetInfo(handle: Long, group: String, name: String, about: String)
    @JvmStatic external fun groupSendText(handle: Long, group: String, text: String): String
    @JvmStatic external fun groupSendTextRich(handle: Long, group: String, text: String, entities: String, reply: Long): String
    @JvmStatic external fun groupMessages(handle: Long, group: String, limit: Int): String
    @JvmStatic external fun groupMessagesPage(handle: Long, group: String, before: Long, after: Long, limit: Int): String
    @JvmStatic external fun groupMessage(handle: Long, group: String, id: String, seq: Long): String
    @JvmStatic external fun groupMessagesSince(handle: Long, after: Long, limit: Int): String
    @JvmStatic external fun groupMarks(handle: Long, group: String, after: Long): String
    @JvmStatic external fun sendMedia(handle: Long, peer: String, path: String, info: String): String
    @JvmStatic external fun sendMediaReply(handle: Long, peer: String, path: String, info: String, reply: Long): String
    @JvmStatic external fun groupSendMedia(handle: Long, group: String, path: String, info: String): String
    @JvmStatic external fun groupSendMediaReply(handle: Long, group: String, path: String, info: String, reply: Long): String
    @JvmStatic external fun fetchMedia(handle: Long, blob: String): Boolean
    @JvmStatic external fun mediaByShort(handle: Long, short: Long): String?
    @JvmStatic external fun openMediaShort(handle: Long, short: Long, out: String): Long
    @JvmStatic external fun photoOf(handle: Long, peer: String): String?
    @JvmStatic external fun setPhoto(handle: Long, path: String, thumb: String): String?
    @JvmStatic external fun groupSetPhoto(handle: Long, group: String, path: String, thumb: String): String
    @JvmStatic external fun groupSetDefaults(handle: Long, group: String, rights: Int): String
    @JvmStatic external fun groupRestrict(handle: Long, group: String, peer: String, rights: Int, untilMs: Long): String
    @JvmStatic external fun gasRelaySetPublic(handle: Long, rid: String, on: Boolean)
    @JvmStatic external fun gasPublicList(handle: Long): String
    @JvmStatic external fun gasBorrowAuto(handle: Long): Boolean
    @JvmStatic external fun gasSetBorrowAuto(handle: Long, on: Boolean)
    @JvmStatic external fun groupDissolve(handle: Long, group: String): String
    @JvmStatic external fun groupInviteCreate(handle: Long, group: String, needApproval: Boolean, expMs: Long, maxUses: Int): String
    @JvmStatic external fun groupInvites(handle: Long, group: String): String
    @JvmStatic external fun groupInviteRevoke(handle: Long, group: String, invite: String)
    @JvmStatic external fun inviteInfo(handle: Long, link: String): String
    @JvmStatic external fun groupJoin(handle: Long, link: String): String
    @JvmStatic external fun groupJoinRequests(handle: Long, group: String): String
    @JvmStatic external fun groupJoinDecide(handle: Long, group: String, peer: String, approve: Boolean)
    @JvmStatic external fun setFeeds(handle: Long, config: String)
    @JvmStatic external fun feedInfo(handle: Long, feed: String): String?
    @JvmStatic external fun feedPosts(handle: Long, feed: String, limit: Int): String
    @JvmStatic external fun feedSendText(handle: Long, feed: String, text: String): String
    @JvmStatic external fun feedSendMedia(handle: Long, feed: String, path: String, info: String): String
    @JvmStatic external fun feedDelete(handle: Long, feed: String, post: String)
    @JvmStatic external fun feedEdit(handle: Long, feed: String, post: String, text: String, entities: String)
    @JvmStatic external fun feedSetPhoto(handle: Long, feed: String, path: String, thumb: String)
    @JvmStatic external fun feedBan(handle: Long, feed: String, peer: String, untilMs: Long)
    @JvmStatic external fun feedOwnerImport(handle: Long, secretHex: String)
    @JvmStatic external fun feedOwnerHeld(handle: Long, feed: String): Boolean
    @JvmStatic external fun feedSetAdmins(handle: Long, feed: String, admins: String)
    @JvmStatic external fun feedSyncNow(handle: Long)
    @JvmStatic external fun feedWatch(handle: Long)
    @JvmStatic external fun feedPin(handle: Long, feed: String, post: String, unpin: Boolean)
    @JvmStatic external fun feedSetSlow(handle: Long, feed: String, seconds: Int)
    @JvmStatic external fun setDevice(handle: Long, device: ByteArray)
    @JvmStatic external fun setClient(handle: Long, app: String, code: Int, name: String)
    @JvmStatic external fun clientsSince(handle: Long, sinceMs: Long): String
    @JvmStatic external fun dirSearch(handle: Long, query: String, limit: Int): String
    @JvmStatic external fun dirRefresh(handle: Long): Int
    @JvmStatic external fun dirStale(handle: Long): Boolean
    @JvmStatic external fun dirPublishDue(handle: Long): Boolean
    @JvmStatic external fun dirPublish(handle: Long): Int
    @JvmStatic external fun dirListed(handle: Long, group: String): Boolean
    @JvmStatic external fun dirSetListed(handle: Long, group: String, listed: Boolean)
    @JvmStatic external fun dirSetPhoto(handle: Long, group: String, jpeg: ByteArray): Boolean
    @JvmStatic external fun dirPhoto(handle: Long, group: String): ByteArray?
    @JvmStatic external fun dirFetchPhotos(handle: Long, groupsJson: String): Int
    @JvmStatic external fun pin(handle: Long, peer: String, seq: Long, unpin: Boolean)
    @JvmStatic external fun pinned(handle: Long, peer: String): String
    @JvmStatic external fun groupPin(handle: Long, group: String, seq: Long, unpin: Boolean)
    @JvmStatic external fun groupPinned(handle: Long, group: String): String
    @JvmStatic external fun feedMemberCount(handle: Long): Long
    @JvmStatic external fun feedSetMeta(handle: Long, feed: String, title: String, about: String)
    @JvmStatic external fun feedReact(handle: Long, feed: String, post: String, emoji: String)
    @JvmStatic external fun react(handle: Long, peer: String, id: String, emoji: String)
    @JvmStatic external fun reactions(handle: Long, peer: String): String
    @JvmStatic external fun groupReact(handle: Long, group: String, id: String, emoji: String)
    @JvmStatic external fun groupReactions(handle: Long, group: String): String
    @JvmStatic external fun groupSendPoll(handle: Long, group: String, poll: String, replyTo: Long): String
    @JvmStatic external fun groupVote(handle: Long, group: String, poll: String, choices: IntArray)
    @JvmStatic external fun pollResults(handle: Long, group: String, poll: String): String
    @JvmStatic external fun deleteForEveryone(handle: Long, seqs: LongArray): Int
    @JvmStatic external fun groupDeleteSeqs(handle: Long, seqs: LongArray, everyone: Boolean): Int
    @JvmStatic external fun editText(handle: Long, seq: Long, text: String, entities: String)
    @JvmStatic external fun groupEditText(handle: Long, seq: Long, text: String, entities: String)
    @JvmStatic external fun relayStatus(handle: Long): String
    @JvmStatic external fun relayKick(handle: Long, name: String): Boolean
    @JvmStatic external fun netStatus(handle: Long): String
    @JvmStatic external fun contactStatus(handle: Long, peer: String): String
    @JvmStatic external fun setNetwork(handle: Long, label: String)
    @JvmStatic external fun networkChanged(handle: Long)
    @JvmStatic external fun pollEvent(handle: Long, timeoutMs: Int): String?
    @JvmStatic external fun cardLink(handle: Long): String
    @JvmStatic external fun addContact(handle: Long, link: String): String
    @JvmStatic external fun addMemberContact(handle: Long, peer: String): String
    @JvmStatic external fun contacts(handle: Long): String
    @JvmStatic external fun sendText(handle: Long, peer: String, text: String): String
    @JvmStatic external fun sendTextRich(handle: Long, peer: String, text: String, entities: String, reply: Long): String
    @JvmStatic external fun history(handle: Long, peer: String, limit: Int): String
    @JvmStatic external fun historyPage(handle: Long, peer: String, before: Long, after: Long, limit: Int): String
    @JvmStatic external fun message(handle: Long, peer: String, id: String, seq: Long): String
    @JvmStatic external fun messagesSince(handle: Long, after: Long, limit: Int): String
    @JvmStatic external fun chatMarks(handle: Long, peer: String): String
    @JvmStatic external fun markRead(handle: Long, peer: String): Int
    @JvmStatic external fun chatAction(handle: Long, peer: String, kind: String): Boolean
    @JvmStatic external fun setOnline(handle: Long, online: Boolean): Int
    @JvmStatic external fun setLastSeenVisible(handle: Long, visible: Boolean)
    @JvmStatic external fun lastSeenVisible(handle: Long): Boolean
    @JvmStatic external fun groupMarkRead(handle: Long, group: String, maxSeq: Long): Int
    @JvmStatic external fun groupReads(handle: Long, seq: Long): String
    @JvmStatic external fun securityNumber(handle: Long, peer: String): String
    @JvmStatic external fun setBlocked(handle: Long, peer: String, blocked: Boolean)
    @JvmStatic external fun deleteMessages(handle: Long, seqs: LongArray): Int
    @JvmStatic external fun clearHistory(handle: Long, peer: String, maxSeq: Long): Int
    @JvmStatic external fun clearAllMessages(handle: Long): Int
    @JvmStatic external fun outboxLen(handle: Long): Int
    @JvmStatic external fun gasStart(handle: Long, config: String): String
    @JvmStatic external fun gasStop(handle: Long)
    @JvmStatic external fun gasForeground(handle: Long, on: Boolean)
    @JvmStatic external fun gasOverview(handle: Long): String
    @JvmStatic external fun feedSendTextRich(handle: Long, feed: String, text: String, entities: String, reply: String): String
    @JvmStatic external fun feedSendMediaReply(handle: Long, feed: String, path: String, info: String, reply: String): String
    @JvmStatic external fun nostrStart(handle: Long, config: String): String
    @JvmStatic external fun nostrStop(handle: Long)
    @JvmStatic external fun nostrRelays(handle: Long): String
    @JvmStatic external fun nostrChoices(handle: Long): String
    @JvmStatic external fun nostrMeasure(handle: Long): String
    @JvmStatic external fun nostrPrefer(handle: Long, url: String)
    @JvmStatic external fun gasRelayNew(handle: Long, name: String): String
    @JvmStatic external fun gasRelayScript(handle: Long, rid: String): String
    @JvmStatic external fun gasComboNew(handle: Long, name: String): String
    @JvmStatic external fun gasComboScript(handle: Long, rid: String): String
    @JvmStatic external fun gasComboWorker(handle: Long, rid: String, host: String): String
    @JvmStatic external fun gasRelayFinish(handle: Long, rid: String, deployment: String): String
    @JvmStatic external fun gasShareInfo(link: String): String
    @JvmStatic external fun gasRelayAddShare(handle: Long, link: String): String
    @JvmStatic external fun gasRelayShare(handle: Long, rid: String): String
    @JvmStatic external fun gasRelaySet(handle: Long, rid: String, enabled: Int, announce: Int, name: String?)
    @JvmStatic external fun gasRelayRemove(handle: Long, rid: String)
    @JvmStatic external fun gasRelayTest(handle: Long, rid: String): String
    @JvmStatic external fun gasRelayServerStats(handle: Long, rid: String): String
    @JvmStatic external fun gasRelayAdmin(handle: Long, rid: String, paused: Int, maxClients: Int): String
    @JvmStatic external fun gasScriptCall(handle: Long, deployment: String, body: String, timeoutS: Int): String
    @JvmStatic external fun appGet(handle: Long, key: String): String?
    @JvmStatic external fun appPut(handle: Long, key: String, value: String?)
}

/** An error from the core. The message never carries a key, a name or a path. */
class CoreException(message: String) : Exception(message)
