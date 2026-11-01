package org.mlmvpn.chat.bridge;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import org.json.JSONObject;
import org.mlmchat.core.ChatNode;
import org.mlmchat.core.MediaRef;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MLMCHAT: photos, videos, voice and files (SPEC 12, 17; phase 4) for Telegram's screens.
 *
 * Sending: Telegram uploads a file in parts (`upload.saveFilePart`); the parts are put together
 * here and the core seals and sends the file (`messages.sendMedia`), with a tiny preview this
 * phone makes. Receiving: a file message shows at once with its preview; Telegram's loader asks
 * for the file by the photo's or document's id (`upload.getFile`), which is the core's short id
 * for it: the answer comes from the file once it is here (the core fetches it; the request waits).
 * The plain file is kept only in this app's private cache.
 */
final class GerehMedia {
    private static final String TAG = "MLMCHAT";
    /** The preview inside the message (SPEC 5): at most 4 KiB. */
    private static final int THUMB_MAX = 4096;

    /** Telegram's file requests waiting for a file to arrive, by short id. */
    private static final Map<Long, List<Runnable>> waiting = new ConcurrentHashMap<>();

    private GerehMedia() {
    }

    static boolean handles(TLObject req) {
        return req instanceof TLRPC.TL_upload_saveFilePart
                || req instanceof TLRPC.TL_upload_saveBigFilePart
                || req instanceof TLRPC.TL_upload_getFile
                || req instanceof TLRPC.TL_messages_sendMedia
                || req instanceof TLRPC.TL_messages_uploadMedia
                || req instanceof TLRPC.TL_messages_sendMultiMedia;
    }

    // ---- several files at once (an album) -----------------------------------------------

    /**
     * Files sent together: Telegram hands each one over by itself first (`messages.uploadMedia`)
     * and then sends them as one (`messages.sendMultiMedia`), naming each by the id that answer
     * gave it. Neither was answered, so several photos or files at once all failed (Ehsan, 28
     * Oct 2026). A file waits here, put together and described, until it is sent; at most an hour.
     */
    private static final Map<Long, Uploaded> uploaded = new ConcurrentHashMap<>();
    private static final long UPLOADED_KEEP_MS = 60 * 60 * 1000L;

    private static final class Uploaded {
        final File file;
        final JSONObject info;
        final long at = System.currentTimeMillis();

        Uploaded(File file, JSONObject info) {
            this.file = file;
            this.info = info;
        }
    }

    /** `messages.uploadMedia`: the file waits here, and Telegram gets an id to send it by. */
    static TLObject uploadMedia(TLRPC.TL_messages_uploadMedia q) throws Exception {
        if (q.media == null || q.media.file == null) {
            throw new IllegalArgumentException("only uploaded files are sent");
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<Long, Uploaded> e : uploaded.entrySet()) {
            if (now - e.getValue().at > UPLOADED_KEEP_MS && uploaded.remove(e.getKey(), e.getValue())) {
                e.getValue().file.delete();
            }
        }
        File file = assemble(q.media.file);
        JSONObject info = infoOf(q.media, file, "");
        long id;
        do {
            id = Utilities.random.nextLong() & Long.MAX_VALUE;
        } while (id == 0 || uploaded.containsKey(id));
        uploaded.put(id, new Uploaded(file, info));
        int date = (int) (now / 1000);
        if (q.media instanceof TLRPC.TL_inputMediaUploadedPhoto) {
            TLRPC.TL_photo p = new TLRPC.TL_photo();
            p.id = id;
            p.access_hash = 0;
            p.file_reference = new byte[]{0};
            p.date = date;
            p.dc_id = 2;
            TLRPC.TL_photoSize s = new TLRPC.TL_photoSize();
            s.type = "y";
            s.w = Math.max(1, info.optInt("w"));
            s.h = Math.max(1, info.optInt("h"));
            s.size = (int) Math.min(Integer.MAX_VALUE, file.length());
            p.sizes.add(s);
            TLRPC.TL_messageMediaPhoto mm = new TLRPC.TL_messageMediaPhoto();
            mm.photo = roundTripPhoto(p);
            mm.flags |= 1;
            return mm;
        }
        TLRPC.TL_document d = new TLRPC.TL_document();
        d.id = id;
        d.access_hash = 0;
        d.file_reference = new byte[]{0};
        d.date = date;
        d.mime_type = info.optString("mime", "application/octet-stream");
        d.size = file.length();
        d.dc_id = 2;
        d.attributes.addAll(q.media.attributes);
        TLRPC.TL_messageMediaDocument mm = new TLRPC.TL_messageMediaDocument();
        mm.document = roundTripDocument(d);
        mm.flags |= 1;
        return mm;
    }

    /**
     * `messages.sendMultiMedia`: each file handed over before goes as a message of its own, all
     * with one album id (SPEC 5 `album_id`) so both phones show them together, each with its own
     * caption; an answer names the first message only. Telegram is answered once for them all, as
     * for one: a contact's once every one has left this phone, a group's or channel's at once.
     */
    static TLObject sendMultiMedia(ChatNode node, TLRPC.TL_messages_sendMultiMedia q, RequestDelegate done) throws Exception {
        List<Uploaded> files = new ArrayList<>();
        for (TLRPC.TL_inputSingleMedia sm : q.multi_media) {
            long id = 0;
            if (sm.media instanceof TLRPC.TL_inputMediaPhoto && ((TLRPC.TL_inputMediaPhoto) sm.media).id != null) {
                id = ((TLRPC.TL_inputMediaPhoto) sm.media).id.id;
            } else if (sm.media instanceof TLRPC.TL_inputMediaDocument && ((TLRPC.TL_inputMediaDocument) sm.media).id != null) {
                id = ((TLRPC.TL_inputMediaDocument) sm.media).id.id;
            }
            Uploaded u = uploaded.remove(id);
            if (u == null) {
                for (Uploaded f : files) {
                    f.file.delete();
                }
                throw new IllegalArgumentException("a file that was not handed over");
            }
            files.add(u);
        }
        byte[] albumId = new byte[16];
        Utilities.random.nextBytes(albumId);
        String album = files.size() > 1 ? hex(albumId) : "";
        List<String> ids = new ArrayList<>();
        List<Long> randomIds = new ArrayList<>();
        String feed = GerehFeeds.feedOfInput(q.peer);
        long chat = feed == null ? GerehGroups.chatOf(q.peer) : 0;
        org.mlmchat.core.Group g = chat != 0 ? GerehGroups.groupFor(node, chat) : null;
        org.mlmchat.core.Contact to = feed == null && chat == 0 ? Messenger.contactFor(node, null, Messenger.peerUser(q.peer)) : null;
        String sk = to != null ? to.getSk() : null;
        if (feed == null && (chat != 0 ? g == null : sk == null)) {
            throw new IllegalArgumentException(chat != 0 ? "unknown group" : "not a contact");
        }
        if (g != null && !g.getCanPost()) {
            throw new Messenger.Refusal(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.GerehGroupCannotPost));
        }
        try {
            for (int i = 0; i < files.size(); i++) {
                TLRPC.TL_inputSingleMedia sm = q.multi_media.get(i);
                Uploaded u = files.get(i);
                JSONObject info = u.info;
                info.put("caption", sm.message != null ? sm.message : "");
                info.put("entities", org.mlmchat.core.TextEntity.toJson(GerehEntities.toCore(sm.entities)));
                if (!album.isEmpty()) {
                    info.put("album", album);
                }
                String path = u.file.getAbsolutePath();
                String id;
                if (feed != null) {
                    id = GerehFeeds.sendMediaPost(node, feed, path, info, i == 0 ? q.reply_to : null);
                } else if (g != null) {
                    id = node.groupSendMedia(g.getId(), path, info, i == 0 ? GerehGroups.replySeq(q.reply_to) : 0);
                } else {
                    id = node.sendMedia(sk, path, info, i == 0 ? Messenger.replyMsgId(q.reply_to) : 0);
                }
                ids.add(id);
                randomIds.add(sm.random_id);
            }
        } finally {
            for (Uploaded u : files) {
                u.file.delete();
            }
        }
        if (feed != null) {
            return GerehFeeds.sentAll(node, feed, ids, randomIds, done);
        }
        if (g != null) {
            return GerehGroups.sentAllInGroup(node, g, ids, randomIds, done);
        }
        Messenger.awaitSentAll(node, sk, ids, randomIds, done);
        return null;
    }

    /**
     * Files sent together show together (SPEC 5 `album_id`): Telegram groups the messages that
     * share a `grouped_id`, made here from the album id's first 8 bytes.
     */
    static void dressAlbum(TLRPC.Message msg, MediaRef m) {
        String a = m != null ? m.getAlbum() : null;
        if (a == null || a.length() < 16) {
            return;
        }
        long g = 0;
        for (int i = 0; i < 16; i++) {
            g = (g << 4) | Character.digit(a.charAt(i), 16);
        }
        if (g != 0) {
            msg.grouped_id = g;
            msg.flags |= 131072;
        }
    }

    private static File upDir() {
        File d = new File(ApplicationLoader.applicationContext.getCacheDir(), "gereh-up");
        d.mkdirs();
        return d;
    }

    private static File plainDir() {
        File d = new File(ApplicationLoader.applicationContext.getCacheDir(), "gereh-media");
        d.mkdirs();
        return d;
    }

    // ---- uploads ----------------------------------------------------------------------------

    /** One part of a file Telegram is uploading: kept until the file is sent. */
    static TLObject savePart(TLObject req) throws Exception {
        long fileId;
        int part;
        NativeByteBuffer bytes;
        if (req instanceof TLRPC.TL_upload_saveBigFilePart) {
            TLRPC.TL_upload_saveBigFilePart q = (TLRPC.TL_upload_saveBigFilePart) req;
            fileId = q.file_id;
            part = q.file_part;
            bytes = q.bytes;
        } else {
            TLRPC.TL_upload_saveFilePart q = (TLRPC.TL_upload_saveFilePart) req;
            fileId = q.file_id;
            part = q.file_part;
            bytes = q.bytes;
        }
        ByteBuffer b = bytes.buffer;
        byte[] data = new byte[b.limit()];
        b.position(0);
        b.get(data);
        b.position(0);
        File dir = new File(upDir(), Long.toString(fileId));
        dir.mkdirs();
        try (FileOutputStream o = new FileOutputStream(new File(dir, Integer.toString(part)))) {
            o.write(data);
        }
        return new TLRPC.TL_boolTrue();
    }

    /** The uploaded parts of `f`, in order, as one file. */
    static File assemble(TLRPC.InputFile f) throws Exception {
        File dir = new File(upDir(), Long.toString(f.id));
        File out = new File(upDir(), f.id + ".bin");
        byte[] buf = new byte[64 * 1024];
        try (FileOutputStream o = new FileOutputStream(out)) {
            for (int i = 0; i < f.parts; i++) {
                File p = new File(dir, Integer.toString(i));
                try (FileInputStream in = new FileInputStream(p)) {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        o.write(buf, 0, n);
                    }
                }
                p.delete();
            }
        }
        dir.delete();
        return out;
    }

    /** A JPEG of at most 90 px a side and 4 KiB, for the message's preview. */
    static byte[] thumbOf(File image) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(image.getAbsolutePath(), o);
            int side = Math.max(o.outWidth, o.outHeight);
            if (side <= 0) {
                return new byte[0];
            }
            o.inJustDecodeBounds = false;
            o.inSampleSize = Math.max(1, Integer.highestOneBit(side / 90));
            Bitmap b = BitmapFactory.decodeFile(image.getAbsolutePath(), o);
            if (b == null) {
                return new byte[0];
            }
            float scale = 90f / Math.max(b.getWidth(), b.getHeight());
            Bitmap s = scale < 1 ? Bitmap.createScaledBitmap(b, Math.max(1, (int) (b.getWidth() * scale)), Math.max(1, (int) (b.getHeight() * scale)), true) : b;
            for (int q = 60; q >= 10; q -= 10) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                s.compress(Bitmap.CompressFormat.JPEG, q, bos);
                if (bos.size() <= THUMB_MAX) {
                    return bos.toByteArray();
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "preview: " + t.getClass().getSimpleName());
        }
        return new byte[0];
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
        }
        return sb.toString();
    }

    private static byte[] unhex(String s) {
        if (s == null || s.isEmpty()) {
            return new byte[0];
        }
        return GerehIdentity.hex(s);
    }

    /** What the core needs to know about a file Telegram sends. */
    static JSONObject infoOf(TLRPC.InputMedia m, File file, String caption) throws Exception {
        JSONObject j = new JSONObject();
        j.put("caption", caption != null ? caption : "");
        if (m instanceof TLRPC.TL_inputMediaUploadedPhoto) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), o);
            j.put("kind", 1).put("mime", "image/jpeg").put("w", Math.max(0, o.outWidth)).put("h", Math.max(0, o.outHeight));
            j.put("thumb", hex(thumbOf(file)));
            return j;
        }
        int kind = 0;
        String name = "";
        for (TLRPC.DocumentAttribute a : m.attributes) {
            if (a instanceof TLRPC.TL_documentAttributeFilename) {
                name = a.file_name;
            } else if (a instanceof TLRPC.TL_documentAttributeAudio) {
                if (a.voice) {
                    kind = 3;
                }
                j.put("durationMs", (int) (a.duration * 1000));
                if (a.waveform != null) {
                    j.put("waveform", hex(a.waveform));
                }
            } else if (a instanceof TLRPC.TL_documentAttributeVideo) {
                kind = a.round_message ? 4 : 2;
                j.put("durationMs", (int) (a.duration * 1000)).put("w", a.w).put("h", a.h);
            } else if (a instanceof TLRPC.TL_documentAttributeAnimated) {
                kind = 5;
            }
        }
        if (m.force_file) {
            kind = 0;
        }
        j.put("kind", kind).put("mime", m.mime_type != null ? m.mime_type : "application/octet-stream").put("name", name);
        if (m.thumb != null) {
            File t = assemble(m.thumb);
            j.put("thumb", hex(thumbOf(t)));
            t.delete();
        }
        return j;
    }

    // ---- sending ----------------------------------------------------------------------------

    /**
     * `messages.sendMedia`: the uploaded file goes to the core. A contact's answer comes when the
     * message reaches their phone (as for text); a group's or channel's at once.
     */
    static TLObject sendMedia(ChatNode node, TLRPC.TL_messages_sendMedia q, RequestDelegate done, int token) throws Exception {
        if (q.media == null || q.media.file == null) {
            throw new IllegalArgumentException("only uploaded files are sent");
        }
        File file = assemble(q.media.file);
        JSONObject info = infoOf(q.media, file, q.message);
        info.put("entities", org.mlmchat.core.TextEntity.toJson(GerehEntities.toCore(q.entities)));
        try {
            long chat = GerehGroups.chatOf(q.peer);
            if (chat != 0) {
                org.mlmchat.core.Group g = GerehGroups.groupFor(node, chat);
                if (g == null) {
                    throw new IllegalArgumentException("unknown group");
                }
                if (!g.getCanPost()) {
                    throw new Messenger.Refusal(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.GerehGroupCannotPost));
                }
                String id = node.groupSendMedia(g.getId(), file.getAbsolutePath(), info, GerehGroups.replySeq(q.reply_to));
                return GerehGroups.sentInGroup(node, g, id, q.random_id, done);
            }
            org.mlmchat.core.Contact to = Messenger.contactFor(node, null, Messenger.peerUser(q.peer));
            String sk = to != null ? to.getSk() : null;
            if (sk == null) {
                throw new IllegalArgumentException("not a contact");
            }
            String id = node.sendMedia(sk, file.getAbsolutePath(), info, Messenger.replyMsgId(q.reply_to));
            Messenger.awaitSent(node, sk, id, q.random_id, done, token);
            return null;
        } finally {
            file.delete();
        }
    }

    // ---- showing --------------------------------------------------------------------------

    static TLRPC.Photo roundTripPhoto(TLRPC.Photo p) {
        SerializedData out = new SerializedData(p.getObjectSize());
        p.serializeToStream(out);
        SerializedData in = new SerializedData(out.toByteArray());
        TLRPC.Photo r = TLRPC.Photo.TLdeserialize(in, in.readInt32(true), true);
        out.cleanup();
        in.cleanup();
        return r;
    }

    static TLRPC.Document roundTripDocument(TLRPC.Document d) {
        SerializedData out = new SerializedData(d.getObjectSize());
        d.serializeToStream(out);
        SerializedData in = new SerializedData(out.toByteArray());
        TLRPC.Document r = TLRPC.Document.TLdeserialize(in, in.readInt32(true), true);
        out.cleanup();
        in.cleanup();
        return r;
    }

    /** A file message's media as Telegram shows it: a photo, or a document (video, voice, file). */
    static TLRPC.MessageMedia toTl(MediaRef m, int date) {
        byte[] thumb = unhex(m.getThumb());
        if (m.getKind() == 1) {
            TLRPC.TL_photo p = new TLRPC.TL_photo();
            p.id = m.getShort();
            p.access_hash = 0;
            p.file_reference = new byte[]{0};
            p.date = date;
            p.dc_id = 2;
            if (thumb.length > 0) {
                TLRPC.TL_photoCachedSize c = new TLRPC.TL_photoCachedSize();
                c.type = "s";
                c.w = Math.min(90, Math.max(1, m.getWidth()));
                c.h = Math.min(90, Math.max(1, m.getHeight()));
                c.bytes = thumb;
                p.sizes.add(c);
            }
            TLRPC.TL_photoSize s = new TLRPC.TL_photoSize();
            s.type = "y";
            s.w = Math.max(1, m.getWidth());
            s.h = Math.max(1, m.getHeight());
            s.size = (int) Math.min(Integer.MAX_VALUE, m.getPlain());
            p.sizes.add(s);
            TLRPC.TL_messageMediaPhoto mm = new TLRPC.TL_messageMediaPhoto();
            mm.photo = roundTripPhoto(p);
            mm.flags |= 1;
            return mm;
        }
        TLRPC.TL_document d = new TLRPC.TL_document();
        d.id = m.getShort();
        d.access_hash = 0;
        d.file_reference = new byte[]{0};
        d.date = date;
        d.mime_type = m.getMime().isEmpty() ? "application/octet-stream" : m.getMime();
        d.size = m.getPlain();
        d.dc_id = 2;
        if (thumb.length > 0) {
            TLRPC.TL_photoCachedSize c = new TLRPC.TL_photoCachedSize();
            c.type = "s";
            c.w = 90;
            c.h = 90;
            c.bytes = thumb;
            d.thumbs.add(c);
            d.flags |= 1;
        }
        int kind = m.getKind();
        if (kind == 3) {
            TLRPC.TL_documentAttributeAudio a = new TLRPC.TL_documentAttributeAudio();
            a.voice = true;
            a.duration = m.getDurationMs() / 1000.0;
            a.waveform = unhex(m.getWaveform());
            a.flags |= 1024 | 4;
            d.attributes.add(a);
        } else if (kind == 2 || kind == 4 || kind == 5) {
            TLRPC.TL_documentAttributeVideo v = new TLRPC.TL_documentAttributeVideo();
            v.round_message = kind == 4;
            v.supports_streaming = false;
            v.duration = m.getDurationMs() / 1000.0;
            v.w = m.getWidth();
            v.h = m.getHeight();
            d.attributes.add(v);
            if (kind == 5) {
                d.attributes.add(new TLRPC.TL_documentAttributeAnimated());
            }
        }
        TLRPC.TL_documentAttributeFilename f = new TLRPC.TL_documentAttributeFilename();
        f.file_name = m.getName().isEmpty() ? "file" : m.getName();
        d.attributes.add(f);
        TLRPC.TL_messageMediaDocument mm = new TLRPC.TL_messageMediaDocument();
        mm.document = roundTripDocument(d);
        mm.flags |= 1;
        return mm;
    }

    // ---- downloads ------------------------------------------------------------------------

    /** `upload.getFile`: a part of a file, from the plain copy once the file is whole. */
    static TLObject getFile(ChatNode node, TLRPC.TL_upload_getFile q, RequestDelegate done) throws Exception {
        // a photo in a chat (its id); an avatar (TL_inputPeerPhotoFileLocation: its photo id)
        long shortId = q.location instanceof TLRPC.TL_inputPeerPhotoFileLocation
                ? ((TLRPC.TL_inputPeerPhotoFileLocation) q.location).photo_id
                : q.location != null ? q.location.id : 0;
        JSONObject st = node.mediaByShort(shortId);
        if (st == null) {
            throw new IllegalArgumentException("unknown file");
        }
        Runnable answer = () -> {
            try {
                done.run(part(node, shortId, q.offset, q.limit), null);
            } catch (Throwable t) {
                TLRPC.TL_error e = new TLRPC.TL_error();
                e.code = 400;
                e.text = "FILE_ID_INVALID";
                done.run(null, e);
            }
        };
        if (st.optBoolean("complete")) {
            Utilities.stageQueue.postRunnable(answer);
            return null;
        }
        // not here yet: ask for it, and answer when it is whole (Messenger.onEvent)
        waiting.computeIfAbsent(shortId, k -> new ArrayList<>()).add(answer);
        node.fetchMedia(st.optString("blob"));
        return null;
    }

    private static TLRPC.TL_upload_file part(ChatNode node, long shortId, long offset, int limit) throws Exception {
        File plain = new File(plainDir(), shortId + ".plain");
        if (!plain.exists()) {
            File tmp = new File(plainDir(), shortId + ".tmp");
            node.openMediaShort(shortId, tmp.getAbsolutePath());
            tmp.renameTo(plain);
        }
        long size = plain.length();
        int len = (int) Math.max(0, Math.min(limit, size - offset));
        byte[] data = new byte[len];
        if (len > 0) {
            try (RandomAccessFile f = new RandomAccessFile(plain, "r")) {
                f.seek(offset);
                f.readFully(data);
            }
        }
        TLRPC.TL_upload_file r = new TLRPC.TL_upload_file();
        r.type = new TLRPC.TL_storage_filePartial();
        r.mtime = (int) (System.currentTimeMillis() / 1000);
        r.bytes = new NativeByteBuffer(len);
        r.bytes.writeBytes(data);
        r.bytes.position(0);
        return r;
    }

    /** A file became whole: answer the requests that waited for it. */
    static void onBlob(String blobHex, long shortId, boolean complete) {
        if (!complete) {
            return;
        }
        List<Runnable> w = waiting.remove(shortId);
        if (w != null) {
            for (Runnable r : w) {
                Utilities.stageQueue.postRunnable(r);
            }
        }
    }

    /** The short id Telegram knows a file by, from its id (the core's rule: first 8 bytes, positive). */
    static long shortOf(String blobHex) {
        byte[] b = GerehIdentity.hex(blobHex);
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (b[i] & 0xFF);
        }
        return Math.max(1, v & Long.MAX_VALUE);
    }
}
