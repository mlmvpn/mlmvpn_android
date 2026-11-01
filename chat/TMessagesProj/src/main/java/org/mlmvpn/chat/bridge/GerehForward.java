package org.mlmvpn.chat.bridge;

import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.BulletinFactory;

import java.io.File;
import java.util.ArrayList;

/**
 * MLMCHAT: forwarding (q27). Gereh's network has no forwarded message (SPEC has no «forwarded
 * from» for it), so Telegram's messages.forwardMessages had no answer and every forward ended in
 * «!», in every direction (Ehsan, 31 Oct 2026). A forward goes again from this phone instead: the
 * same text and formatting, the same file (uploaded again from where it lies on this phone), as a
 * new message of mine -- Telegram's own «forward without the sender's name», through the same
 * sending every chat already uses, so it reaches chats, groups, channels and Saved Messages alike.
 */
public final class GerehForward {

    private GerehForward() {
    }

    /** Sends {@code messages} to {@code peer} again, in their order. Telegram's codes: 0 is sent. */
    public static int send(SendMessagesHelper h, int account, ArrayList<MessageObject> messages, long peer,
                           boolean hideCaption, boolean notify, int scheduleDate, MessageObject replyToTopMsg) {
        int noFile = 0;
        int notYet = 0;
        for (MessageObject m : messages) {
            TLRPC.Message o = m == null ? null : m.messageOwner;
            if (o == null || o instanceof TLRPC.TL_messageService) {
                continue;
            }
            // a tile or setting of the app's goes as its words and link, never as its picture: each
            // phone draws the card from its own copy of the app (GerehLinks.sentText)
            String card = GerehLinks.sentText(o);
            if (card != null) {
                h.sendMessage(SendMessagesHelper.SendMessageParams.of(card, peer, null, replyToTopMsg, null, true,
                        null, null, null, notify, scheduleDate, 0, null, false));
                continue;
            }
            TLRPC.MessageMedia media = o.media;
            String caption = hideCaption ? null : o.message;
            ArrayList<TLRPC.MessageEntity> entities = hideCaption ? null : o.entities;
            if (media == null || media instanceof TLRPC.TL_messageMediaEmpty || media instanceof TLRPC.TL_messageMediaWebPage) {
                if (TextUtils.isEmpty(o.message)) {
                    continue;
                }
                h.sendMessage(SendMessagesHelper.SendMessageParams.of(o.message, peer, null, replyToTopMsg, null, true,
                        o.entities, null, null, notify, scheduleDate, 0, null, false));
            } else if (media.photo instanceof TLRPC.TL_photo) {
                if (fileOf(account, o) == null) {
                    noFile++;
                    continue;
                }
                h.sendMessage(SendMessagesHelper.SendMessageParams.of((TLRPC.TL_photo) media.photo, null, peer, null, replyToTopMsg,
                        caption, entities, null, null, notify, scheduleDate, 0, media.ttl_seconds, m, false));
            } else if (media.document instanceof TLRPC.TL_document) {
                File f = fileOf(account, o);
                if (f == null) {
                    noFile++;
                    continue;
                }
                h.sendMessage(SendMessagesHelper.SendMessageParams.of((TLRPC.TL_document) media.document, null, f.getAbsolutePath(), peer,
                        null, replyToTopMsg, caption, entities, null, null, notify, scheduleDate, 0, media.ttl_seconds, m, null, false));
            } else if (media instanceof TLRPC.TL_messageMediaPoll && !DialogObject.isUserDialog(peer) && !((TLRPC.TL_messageMediaPoll) media).poll.quiz) {
                // a new poll with the same question and options (m4); a quiz's right answer is its maker's
                h.sendMessage(SendMessagesHelper.SendMessageParams.of(newPoll((TLRPC.TL_messageMediaPoll) media), peer, null, replyToTopMsg,
                        null, null, notify, scheduleDate, 0));
            } else {
                notYet++;
            }
        }
        if (noFile > 0 || notYet > 0) {
            String text = LocaleController.getString(noFile > 0 ? R.string.GerehForwardNoFile : R.string.GerehForwardNotYet);
            AndroidUtilities.runOnUIThread(() -> BulletinFactory.global().createSimpleBulletin(R.raw.error, text).show());
        }
        return 0;
    }

    /** The message's file where it lies on this phone (sent from here, or downloaded), else null. */
    private static File fileOf(int account, TLRPC.Message o) {
        if (!TextUtils.isEmpty(o.attachPath)) {
            File f = new File(o.attachPath);
            if (f.exists()) {
                return f;
            }
        }
        File f = FileLoader.getInstance(account).getPathToMessage(o);
        return f != null && f.exists() && f.length() > 0 ? f : null;
    }

    private static TLRPC.TL_messageMediaPoll newPoll(TLRPC.TL_messageMediaPoll from) {
        SerializedData out = new SerializedData(from.poll.getObjectSize());
        from.poll.serializeToStream(out);
        SerializedData in = new SerializedData(out.toByteArray());
        TLRPC.Poll copy = TLRPC.Poll.TLdeserialize(in, in.readInt32(true), true);
        out.cleanup();
        in.cleanup();
        copy.id = 0;
        copy.closed = false;
        copy.close_date = 0;
        copy.close_period = 0;
        TLRPC.TL_messageMediaPoll p = new TLRPC.TL_messageMediaPoll();
        p.poll = copy;
        p.results = new TLRPC.TL_pollResults();
        return p;
    }
}
