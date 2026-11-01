package org.mlmvpn.chat.bridge;

import org.mlmchat.core.ChatEvent;
import org.mlmchat.core.ChatNode;
import org.mlmchat.core.Group;
import org.mlmchat.core.GroupMessage;
import org.mlmchat.core.PollInfo;
import org.mlmchat.core.PollResults;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_update;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MLMCHAT: polls in groups and channels (m4, T69; SPEC 5 `Poll` and `PollVote`). A poll is a
 * message of the group; each vote goes to every member like a reaction, and each phone counts.
 * Telegram's own poll is filled from the core: its question and options, and the results counted
 * here. Anonymous as in Telegram unless its sender turned that off; then who chose what shows. A
 * quiz takes one answer, once; its explanation does not travel (SPEC has no field for it).
 */
final class GerehPolls {
    private GerehPolls() {
    }

    /** A poll's id in Telegram: from its message id, the same on every phone. */
    static long pollId(String messageId) {
        long v = 0;
        for (int i = 0; i < 16 && i < messageId.length(); i++) {
            v = (v << 4) | Character.digit(messageId.charAt(i), 16);
        }
        return v & Long.MAX_VALUE;
    }

    /** Telegram's poll for a group message that is one; null for one that is not. */
    static TLRPC.TL_messageMediaPoll media(ChatNode node, String gid, GroupMessage m) {
        PollInfo p = m.getPoll();
        if (p == null || node == null) {
            return null;
        }
        TLRPC.TL_messageMediaPoll media = new TLRPC.TL_messageMediaPoll();
        media.poll = poll(p, m);
        media.results = results(node, gid, m.getId(), p);
        return media;
    }

    private static TLRPC.TL_poll poll(PollInfo p, GroupMessage m) {
        TLRPC.TL_poll poll = new TLRPC.TL_poll();
        poll.id = pollId(m.getId());
        poll.question = text(p.getQuestion());
        List<String> options = p.getOptions();
        for (int i = 0; i < options.size(); i++) {
            TLRPC.TL_pollAnswer a = new TLRPC.TL_pollAnswer();
            a.text = text(options.get(i));
            a.option = new byte[]{(byte) i};
            poll.answers.add(a);
        }
        poll.public_voters = !p.getAnonymous();
        poll.quiz = p.getQuiz();
        poll.multiple_choice = p.getMultiple() && !p.getQuiz();
        poll.revoting_disabled = p.getQuiz();
        poll.creator = m.getOutgoing();
        if (p.getCloseMs() > 0) {
            poll.close_date = (int) (p.getCloseMs() / 1000);
            poll.flags |= 32;
            poll.closed = p.getCloseMs() <= System.currentTimeMillis();
        }
        return poll;
    }

    private static TLRPC.TL_textWithEntities text(String s) {
        TLRPC.TL_textWithEntities t = new TLRPC.TL_textWithEntities();
        t.text = s != null ? s : "";
        return t;
    }

    /** The results counted on this phone: votes for each option, ours chosen, and after answering a quiz, its right answer. */
    static TLRPC.TL_pollResults results(ChatNode node, String gid, String id, PollInfo p) {
        PollResults r;
        try {
            r = node.pollResults(gid, id);
        } catch (Throwable t) {
            r = null;
        }
        boolean voted = r != null && !r.getMine().isEmpty();
        TLRPC.TL_pollResults res = new TLRPC.TL_pollResults();
        for (int i = 0; i < p.getOptions().size(); i++) {
            TLRPC.TL_pollAnswerVoters v = new TLRPC.TL_pollAnswerVoters();
            v.option = new byte[]{(byte) i};
            v.voters = r != null && i < r.getCounts().size() ? r.getCounts().get(i) : 0;
            v.chosen = voted && r.getMine().contains(i);
            v.correct = p.getQuiz() && voted && i == p.getCorrect();
            v.recent_voters = new ArrayList<>();
            v.flags |= 4;
            res.results.add(v);
        }
        res.total_voters = r != null ? r.getVoters() : 0;
        res.flags |= 2 | 4;
        if (r != null && !p.getAnonymous() && !r.getWho().isEmpty()) {
            // the faces Telegram shows on a poll that is not anonymous: the last three who voted
            List<String> who = new ArrayList<>(r.getWho().keySet());
            for (int i = Math.max(0, who.size() - 3); i < who.size(); i++) {
                res.recent_voters.add(userPeer(who.get(i)));
            }
            res.flags |= 8;
        }
        return res;
    }

    private static TLRPC.Peer userPeer(String sk) {
        TLRPC.TL_peerUser p = new TLRPC.TL_peerUser();
        p.user_id = sk.equals(myKey()) ? UserConfig.getInstance(Messenger.account()).getClientUserId() : GerehIdentity.userIdFor(sk);
        return p;
    }

    private static String myKey() {
        ChatNode node = GerehIdentity.node();
        try {
            return node != null ? GerehReactions.me(node) : "";
        } catch (Throwable t) {
            return "";
        }
    }

    // ---- requests -------------------------------------------------------------------------

    static boolean handles(TLObject req) {
        return req instanceof TLRPC.TL_messages_sendVote || req instanceof TLRPC.TL_messages_getPollResults || req instanceof TLRPC.TL_messages_getPollVotes
                || req instanceof TLRPC.TL_messages_sendMedia && ((TLRPC.TL_messages_sendMedia) req).media instanceof TLRPC.TL_inputMediaPoll;
    }

    /** A poll sent, a vote, a poll's results or its voters. A poll's send is answered through `done` (null here). */
    static TLObject answer(ChatNode node, TLObject req, RequestDelegate done) {
        if (req instanceof TLRPC.TL_messages_sendMedia) {
            return send(node, (TLRPC.TL_messages_sendMedia) req, done);
        }
        if (req instanceof TLRPC.TL_messages_getPollVotes) {
            return voters(node, (TLRPC.TL_messages_getPollVotes) req);
        }
        TLRPC.InputPeer peer;
        int msgId;
        if (req instanceof TLRPC.TL_messages_sendVote) {
            TLRPC.TL_messages_sendVote q = (TLRPC.TL_messages_sendVote) req;
            peer = q.peer;
            msgId = q.msg_id;
        } else {
            TLRPC.TL_messages_getPollResults q = (TLRPC.TL_messages_getPollResults) req;
            peer = q.peer;
            msgId = q.msg_id;
        }
        long chat = GerehGroups.chatOf(peer);
        Group g = chat != 0 ? GerehGroups.groupFor(node, chat) : null;
        GroupMessage m = g == null ? null : node.groupMessage(g.getId(), "", msgId - GerehGroups.MSG_BASE);
        if (m == null || m.getPoll() == null) {
            throw new IllegalArgumentException("unknown poll");
        }
        if (req instanceof TLRPC.TL_messages_sendVote) {
            List<Integer> choices = new ArrayList<>();
            for (byte[] o : ((TLRPC.TL_messages_sendVote) req).options) {
                if (o != null && o.length > 0) {
                    choices.add(o[0] & 0xFF);
                }
            }
            node.groupVote(g.getId(), m.getId(), choices);
        }
        TLRPC.TL_updates ups = new TLRPC.TL_updates();
        ups.updates.add(update(node, g, m));
        ups.date = (int) (System.currentTimeMillis() / 1000);
        ups.seq = 0;
        return ups;
    }

    /** messages.sendMedia with a poll, in a group or channel: the poll goes to the members like a post. */
    private static TLObject send(ChatNode node, TLRPC.TL_messages_sendMedia q, RequestDelegate done) {
        long chat = GerehGroups.chatOf(q.peer);
        Group g = chat != 0 ? GerehGroups.groupFor(node, chat) : null;
        if (g == null) {
            throw new IllegalArgumentException("polls are for groups and channels");
        }
        TLRPC.TL_inputMediaPoll in = (TLRPC.TL_inputMediaPoll) q.media;
        List<String> options = new ArrayList<>();
        for (TLRPC.PollAnswer a : in.poll.answers) {
            options.add(a.text != null ? a.text.text : "");
        }
        int correct = 0;
        if (in.poll.quiz && in.correct_answers != null && !in.correct_answers.isEmpty()) {
            correct = in.correct_answers.get(0);
        }
        long closeMs = in.poll.close_date > 0 ? in.poll.close_date * 1000L
                : in.poll.close_period > 0 ? System.currentTimeMillis() + in.poll.close_period * 1000L : 0;
        PollInfo p = new PollInfo(in.poll.question != null ? in.poll.question.text : "", options, !in.poll.public_voters,
                in.poll.multiple_choice, in.poll.quiz, correct, closeMs);
        String id = node.groupSendPoll(g.getId(), p, GerehGroups.replySeq(q.reply_to));
        return GerehGroups.sentAllInGroup(node, g, Collections.singletonList(id), Collections.singletonList(q.random_id), done);
    }

    /** Who chose what on a poll that is not anonymous (an anonymous one shows nobody, T69). */
    private static TLObject voters(ChatNode node, TLRPC.TL_messages_getPollVotes q) {
        long chat = GerehGroups.chatOf(q.peer);
        Group g = chat != 0 ? GerehGroups.groupFor(node, chat) : null;
        GroupMessage m = g == null ? null : node.groupMessage(g.getId(), "", q.id - GerehGroups.MSG_BASE);
        if (m == null || m.getPoll() == null || m.getPoll().getAnonymous()) {
            throw new IllegalArgumentException("no voters to show");
        }
        PollResults r = node.pollResults(g.getId(), m.getId());
        int only = q.option != null && q.option.length > 0 ? q.option[0] & 0xFF : -1;
        int date = (int) (m.getAtMs() / 1000);
        TLRPC.TL_messages_votesList out = new TLRPC.TL_messages_votesList();
        for (Map.Entry<String, List<Integer>> e : r.getWho().entrySet()) {
            List<Integer> c = e.getValue();
            if (only >= 0 && !c.contains(only)) {
                continue;
            }
            if (c.size() == 1 || only >= 0) {
                TLRPC.TL_messagePeerVote v = new TLRPC.TL_messagePeerVote();
                v.peer = userPeer(e.getKey());
                v.option = new byte[]{(byte) (only >= 0 ? only : c.get(0))};
                v.date = date;
                out.votes.add(v);
            } else {
                TLRPC.TL_messagePeerVoteMultiple v = new TLRPC.TL_messagePeerVoteMultiple();
                v.peer = userPeer(e.getKey());
                for (int o : c) {
                    v.options.add(new byte[]{(byte) o});
                }
                v.date = date;
                out.votes.add(v);
            }
        }
        out.count = out.votes.size();
        Map<Long, TLRPC.User> users = new HashMap<>();
        for (TLRPC.User u : GerehGroups.usersFor(node, g)) {
            users.put(u.id, u);
        }
        TLRPC.User self = UserConfig.getInstance(Messenger.account()).getCurrentUser();
        if (self != null) {
            users.put(self.id, self);
        }
        out.users.addAll(users.values());
        return out;
    }

    // ---- events ---------------------------------------------------------------------------

    /** Someone's vote changed (theirs arrived, or ours went out): the poll in Telegram follows. */
    static void onEvent(ChatNode node, ChatEvent.PollVoted e) {
        try {
            Group g = node.groupState(e.getChat());
            GroupMessage m = g == null ? null : node.groupMessage(g.getId(), e.getId(), 0);
            if (m == null || m.getPoll() == null) {
                return;
            }
            GerehReactions.push(update(node, g, m));
        } catch (Throwable ignored) {
        }
    }

    private static TL_update.TL_updateMessagePoll update(ChatNode node, Group g, GroupMessage m) {
        long chat = GerehGroups.chatIdFor(g.getId());
        TL_update.TL_updateMessagePoll u = new TL_update.TL_updateMessagePoll();
        u.poll_id = pollId(m.getId());
        u.poll = poll(m.getPoll(), m);
        u.results = results(node, g.getId(), m.getId(), m.getPoll());
        if (g.getChannel()) {
            TLRPC.TL_peerChannel p = new TLRPC.TL_peerChannel();
            p.channel_id = chat;
            u.peer = p;
        } else {
            TLRPC.TL_peerChat p = new TLRPC.TL_peerChat();
            p.chat_id = chat;
            u.peer = p;
        }
        u.msg_id = GerehGroups.MSG_BASE + (int) m.getSeq();
        return u;
    }
}
