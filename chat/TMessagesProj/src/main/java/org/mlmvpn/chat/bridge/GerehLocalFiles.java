package org.mlmvpn.chat.bridge;

import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MLMCHAT: pictures Telegram shows from a file on this phone that is no chat's media: the photos
 * of chats a search found (GerehFound) and the cards of the app's tiles and settings in chats
 * (GerehLinks). Each gets a photo id of its own; Telegram's image loader asks `upload.getFile`
 * for it, and the answer is the file (Messenger sends those requests here).
 */
final class GerehLocalFiles {
    private static final Map<Long, File> files = new ConcurrentHashMap<>();

    private GerehLocalFiles() {
    }

    /** The photo id a file is shown by: from its name and size, so a changed file is a new picture. */
    static long idOf(File f) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(("mlmchat/local-photo/1|" + f.getName() + "|" + f.length()).getBytes(StandardCharsets.UTF_8));
            long v = 0;
            for (int i = 0; i < 8; i++) {
                v = (v << 8) | (h[i] & 0xFF);
            }
            return Math.max(1, v & Long.MAX_VALUE);
        } catch (Exception e) {
            return 1;
        }
    }

    /** One of these pictures: it lies on this phone, nothing comes from the network for it. */
    static boolean owns(long id) {
        return files.containsKey(id);
    }

    /** A chat's avatar from a file (none: the letters). */
    static TLRPC.ChatPhoto chatPhoto(File f) {
        if (f == null || !f.exists() || f.length() == 0) {
            return new TLRPC.TL_chatPhotoEmpty();
        }
        long id = idOf(f);
        files.put(id, f);
        return GerehPhotos.chatPhotoOf(id);
    }

    /** A photo (a link preview's picture) from a file; null when there is none. */
    static TLRPC.Photo photo(File f) {
        if (f == null || !f.exists() || f.length() == 0) {
            return null;
        }
        // its own shape, read from the file: Telegram lays the preview out by it
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
        long id = idOf(f);
        files.put(id, f);
        return GerehPhotos.photo(id, null, o.outWidth, o.outHeight);
    }

    private static long idOf(TLRPC.TL_upload_getFile q) {
        return q.location instanceof TLRPC.TL_inputPeerPhotoFileLocation
                ? ((TLRPC.TL_inputPeerPhotoFileLocation) q.location).photo_id
                : q.location != null ? q.location.id : 0;
    }

    static boolean ownsFile(TLRPC.TL_upload_getFile q) {
        return files.containsKey(idOf(q));
    }

    /** `upload.getFile` for one of these pictures: from its file. */
    static TLObject getFile(TLRPC.TL_upload_getFile q) throws Exception {
        File f = files.get(idOf(q));
        if (f == null || !f.exists()) {
            throw new IllegalArgumentException("unknown file");
        }
        long size = f.length();
        int len = (int) Math.max(0, Math.min(q.limit, size - q.offset));
        byte[] data = new byte[len];
        if (len > 0) {
            try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
                r.seek(q.offset);
                r.readFully(data);
            }
        }
        TLRPC.TL_upload_file out = new TLRPC.TL_upload_file();
        out.type = new TLRPC.TL_storage_fileJpeg();
        out.mtime = (int) (System.currentTimeMillis() / 1000);
        out.bytes = new NativeByteBuffer(len);
        out.bytes.writeBytes(data);
        out.bytes.position(0);
        return out;
    }
}
