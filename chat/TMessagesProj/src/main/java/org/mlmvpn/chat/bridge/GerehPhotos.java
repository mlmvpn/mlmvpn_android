package org.mlmvpn.chat.bridge;

import android.util.Log;

import org.mlmchat.core.ChatNode;
import org.mlmchat.core.Group;
import org.mlmchat.core.Me;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;

/**
 * MLMCHAT: profile, group and channel photos (SPEC 10, 7.2; step 6.2 of the work queue).
 *
 * A photo is a file like any other (GerehMedia): Telegram uploads it in parts, the core seals it
 * and tells every contact (a profile photo) or every member (a group's or channel's). Telegram
 * knows it by its photo id, which is the core's short id for the file, so its avatar loader
 * fetches it through `upload.getFile` like a photo in a chat. Only photos: no video avatars.
 */
final class GerehPhotos {
    private static final String TAG = "MLMCHAT";

    private GerehPhotos() {
    }

    static boolean handles(TLObject req) {
        return req instanceof TLRPC.TL_photos_uploadProfilePhoto
                || req instanceof TLRPC.TL_photos_updateProfilePhoto
                || req instanceof TLRPC.TL_photos_deletePhotos
                || req instanceof TLRPC.TL_photos_getUserPhotos
                || req instanceof TLRPC.TL_messages_editChatPhoto
                || req instanceof TLRPC.TL_channels_editPhoto;
    }

    // ---- what Telegram shows ------------------------------------------------------------

    /** A user's avatar from their photo, or null when they have none. */
    static TLRPC.UserProfilePhoto userPhoto(String sk) {
        ChatNode node = GerehIdentity.node();
        if (node == null || sk == null) {
            return null;
        }
        try {
            org.mlmchat.core.Photo p = node.photoOf(sk);
            if (p == null) {
                return null;
            }
            TLRPC.TL_userProfilePhoto ph = new TLRPC.TL_userProfilePhoto();
            ph.photo_id = p.getShort();
            ph.dc_id = 2;
            ph.photo_small = location(ph.photo_id, 'a');
            ph.photo_big = location(ph.photo_id, 'c');
            ph.strippedBitmap = preview(ph.photo_id, p.getThumb());
            return ph;
        } catch (Throwable t) {
            Log.w(TAG, "photo: " + t.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * Puts their avatar on a user being built (before it is round-tripped). Telegram loads no
     * avatar for a user whose access hash is 0 (ImageLocation.getForUser), and the bridge knows
     * users by id only, so the id serves as the hash, as for channels (GerehChannels.channelFor).
     * Every photo uploaded before this showed no thumbnail (Ehsan, 25 Oct 2026).
     */
    static void dress(TLRPC.TL_user u, String sk) {
        u.access_hash = u.id;
        u.flags |= 1;
        TLRPC.UserProfilePhoto ph = userPhoto(sk);
        if (ph != null) {
            u.photo = ph;
            u.flags |= 32;
        }
    }

    /** A group's or channel's avatar. */
    static TLRPC.ChatPhoto chatPhoto(Group g) {
        return chatPhotoOf(g.getPhoto(), g.getPhotoThumb());
    }

    /** A chat's avatar from its photo's short id (0: none); the official chats' too (GerehFeeds). */
    static TLRPC.ChatPhoto chatPhotoOf(long shortId) {
        return chatPhotoOf(shortId, null);
    }

    /**
     * A chat's avatar, with its tiny preview (hex JPEG) shown until the photo itself is here (q16;
     * Ehsan: «خیلی‌ها می‌پرسند چرا کانال و گروه رسمی عکس ندارد»): Telegram's avatar loader draws
     * `strippedBitmap` while it waits, so a photo that is slow to come, or no longer on any relay,
     * never leaves the chat with only its letter.
     */
    static TLRPC.ChatPhoto chatPhotoOf(long shortId, String thumbHex) {
        if (shortId == 0) {
            return new TLRPC.TL_chatPhotoEmpty();
        }
        TLRPC.TL_chatPhoto ph = new TLRPC.TL_chatPhoto();
        ph.photo_id = shortId;
        ph.dc_id = 2;
        ph.photo_small = location(ph.photo_id, 'a');
        ph.photo_big = location(ph.photo_id, 'c');
        ph.strippedBitmap = preview(shortId, thumbHex);
        return ph;
    }

    /** Each photo's preview, decoded once. */
    private static final android.util.LruCache<Long, android.graphics.drawable.BitmapDrawable> previews = new android.util.LruCache<>(256);

    /** A photo's preview as Telegram draws it before the photo, or null without one. */
    static android.graphics.drawable.BitmapDrawable preview(long shortId, String thumbHex) {
        if (shortId == 0 || thumbHex == null || thumbHex.isEmpty()) {
            return null;
        }
        android.graphics.drawable.BitmapDrawable d = previews.get(shortId);
        if (d != null) {
            return d;
        }
        try {
            byte[] jpeg = GerehIdentity.hex(thumbHex);
            android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            if (b == null) {
                return null;
            }
            d = new android.graphics.drawable.BitmapDrawable(org.telegram.messenger.ApplicationLoader.applicationContext.getResources(), b);
            previews.put(shortId, d);
            return d;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The small ('a') or big ('c') avatar file, as Telegram's parser fills it in. A chat reaches
     * Telegram as built, not round-tripped, so without this it had no file and no avatar
     * (ImageLocation.getForChat). Both sizes are the one file `upload.getFile` serves.
     */
    private static TLRPC.FileLocation location(long photoId, char size) {
        TLRPC.TL_fileLocationToBeDeprecated l = new TLRPC.TL_fileLocationToBeDeprecated();
        l.volume_id = -photoId;
        l.local_id = size;
        return l;
    }

    /** The full photo (for the gallery and info pages): one file serves every size. */
    static TLRPC.Photo photo(long shortId, byte[] thumb) {
        return photo(shortId, thumb, 0, 0);
    }

    /**
     * A photo of w by h pixels (a link preview's card): its sizes keep that shape, which Telegram
     * lays the picture out by; square when not known. A square one cropped the tile card to its
     * middle (Ehsan, 31 Oct 2026: «مربعی شده که باعث شده آیکون ناقص نمایش داده بشه»).
     */
    static TLRPC.Photo photo(long shortId, byte[] thumb, int w, int h) {
        boolean known = w > 0 && h > 0;
        TLRPC.TL_photo p = new TLRPC.TL_photo();
        p.id = shortId;
        p.access_hash = 0;
        p.file_reference = new byte[]{0};
        p.date = (int) (System.currentTimeMillis() / 1000);
        p.dc_id = 2;
        if (thumb != null && thumb.length > 0) {
            TLRPC.TL_photoCachedSize c = new TLRPC.TL_photoCachedSize();
            c.type = "s";
            c.w = 90;
            c.h = 90;
            c.bytes = thumb;
            p.sizes.add(c);
        }
        String[] types = {"a", "c"};
        int[] sides = {160, 640};
        for (int i = 0; i < types.length; i++) {
            TLRPC.TL_photoSize s = new TLRPC.TL_photoSize();
            s.type = types[i];
            s.w = !known || w >= h ? sides[i] : Math.max(1, sides[i] * w / h);
            s.h = !known || h >= w ? sides[i] : Math.max(1, sides[i] * h / w);
            s.size = 0;
            p.sizes.add(s);
        }
        return GerehMedia.roundTripPhoto(p);
    }

    static TLRPC.Photo chatFullPhoto(Group g) {
        // its preview too, drawn on the info page until the photo is here (q16)
        return g.getPhoto() == 0 ? new TLRPC.TL_photoEmpty() : photo(g.getPhoto(), g.getPhotoThumb().isEmpty() ? null : GerehIdentity.hex(g.getPhotoThumb()));
    }

    private static TLRPC.Photo userFullPhoto(String sk) {
        ChatNode node = GerehIdentity.node();
        try {
            org.mlmchat.core.Photo p = node != null ? node.photoOf(sk) : null;
            return p == null ? new TLRPC.TL_photoEmpty() : photo(p.getShort(), GerehIdentity.hex(p.getThumb()));
        } catch (Throwable t) {
            return new TLRPC.TL_photoEmpty();
        }
    }

    /** `users.getFullUser`: the profile photo of the page's user. */
    static void dressFull(TLRPC.UserFull f, String sk) {
        TLRPC.Photo p = userFullPhoto(sk);
        if (!(p instanceof TLRPC.TL_photoEmpty)) {
            f.profile_photo = p;
            f.flags |= 4;
        }
    }

    // ---- requests -------------------------------------------------------------------------

    static TLObject answer(int acc, ChatNode node, TLObject req) throws Exception {
        Me me = node.getMe();
        if (req instanceof TLRPC.TL_photos_uploadProfilePhoto) {
            TLRPC.TL_photos_uploadProfilePhoto q = (TLRPC.TL_photos_uploadProfilePhoto) req;
            if (q.file == null) {
                throw new Messenger.Refusal(LocaleController.getString(R.string.GerehPhotoOnly));
            }
            File file = GerehMedia.assemble(q.file);
            try {
                String thumb = GerehMedia.hex(GerehMedia.thumbOf(file));
                org.mlmchat.core.Photo p = node.setPhoto(file.getAbsolutePath(), thumb);
                return myPhotoAnswer(acc, me, p);
            } finally {
                file.delete();
            }
        }
        if (req instanceof TLRPC.TL_photos_updateProfilePhoto) {
            TLRPC.InputPhoto id = ((TLRPC.TL_photos_updateProfilePhoto) req).id;
            org.mlmchat.core.Photo now = node.photoOf(me.getSk());
            if (id instanceof TLRPC.TL_inputPhotoEmpty) {
                return myPhotoAnswer(acc, me, node.setPhoto(null, ""));
            }
            if (now != null && id != null && id.id == now.getShort()) {
                return myPhotoAnswer(acc, me, now);
            }
            // an older photo: only the current one is kept
            throw new Messenger.Refusal(LocaleController.getString(R.string.GerehPhotoOnlyCurrent));
        }
        if (req instanceof TLRPC.TL_photos_deletePhotos) {
            org.mlmchat.core.Photo now = node.photoOf(me.getSk());
            ArrayList<Long> deleted = new ArrayList<>();
            for (TLRPC.InputPhoto ip : ((TLRPC.TL_photos_deletePhotos) req).id) {
                if (now != null && ip != null && ip.id == now.getShort()) {
                    node.setPhoto(null, "");
                    activate(acc, node);
                    deleted.add(ip.id);
                }
            }
            return longVector(req, deleted);
        }
        if (req instanceof TLRPC.TL_photos_getUserPhotos) {
            TLRPC.TL_photos_getUserPhotos q = (TLRPC.TL_photos_getUserPhotos) req;
            String sk = skOf(acc, me, q.user_id);
            TLRPC.TL_photos_photos r = new TLRPC.TL_photos_photos();
            if (sk != null && q.offset == 0 && q.max_id == 0) {
                TLRPC.Photo p = userFullPhoto(sk);
                if (!(p instanceof TLRPC.TL_photoEmpty)) {
                    r.photos.add(p);
                }
            }
            return r;
        }
        if (req instanceof TLRPC.TL_messages_editChatPhoto) {
            TLRPC.TL_messages_editChatPhoto q = (TLRPC.TL_messages_editChatPhoto) req;
            Group g = GerehGroups.groupFor(node, q.chat_id);
            if (g == null) {
                throw new IllegalArgumentException("unknown group");
            }
            return GerehGroups.chatUpdates(node, setGroupPhoto(node, g, q.photo));
        }
        if (req instanceof TLRPC.TL_channels_editPhoto) {
            TLRPC.TL_channels_editPhoto q = (TLRPC.TL_channels_editPhoto) req;
            Group g = GerehGroups.groupFor(node, GerehChannels.channelOf(q.channel));
            if (g == null) {
                throw new IllegalArgumentException("unknown channel");
            }
            return GerehChannels.chatUpdates(node, setGroupPhoto(node, g, q.photo));
        }
        throw new IllegalArgumentException("not a photo request");
    }

    private static Group setGroupPhoto(ChatNode node, Group g, TLRPC.InputChatPhoto photo) throws Exception {
        if (photo instanceof TLRPC.TL_inputChatPhotoEmpty) {
            return node.groupSetPhoto(g.getId(), null, "");
        }
        if (!(photo instanceof TLRPC.TL_inputChatUploadedPhoto) || ((TLRPC.TL_inputChatUploadedPhoto) photo).file == null) {
            throw new Messenger.Refusal(LocaleController.getString(R.string.GerehPhotoOnly));
        }
        File file = GerehMedia.assemble(((TLRPC.TL_inputChatUploadedPhoto) photo).file);
        try {
            return node.groupSetPhoto(g.getId(), file.getAbsolutePath(), GerehMedia.hex(GerehMedia.thumbOf(file)));
        } finally {
            file.delete();
        }
    }

    /** `photos.photo` for our own new (or removed) photo, with our user as it is now. */
    private static TLRPC.TL_photos_photo myPhotoAnswer(int acc, Me me, org.mlmchat.core.Photo p) {
        ChatNode node = GerehIdentity.node();
        TLRPC.TL_photos_photo r = new TLRPC.TL_photos_photo();
        r.photo = p == null ? new TLRPC.TL_photoEmpty() : photo(p.getShort(), GerehIdentity.hex(p.getThumb()));
        GerehIdentity.activate(acc, me);
        r.users.add(UserConfig.getInstance(acc).getCurrentUser());
        if (node != null) {
            Messenger.refreshUser(acc, me.getSk());
        }
        return r;
    }

    private static void activate(int acc, ChatNode node) {
        GerehIdentity.activate(acc, node.getMe());
        AndroidUtilities.runOnUIThread(() -> {
            NotificationCenter nc = NotificationCenter.getInstance(acc);
            nc.postNotificationName(NotificationCenter.mainUserInfoChanged);
            nc.postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_AVATAR);
        });
    }

    private static String skOf(int acc, Me me, TLRPC.InputUser iu) {
        long uid = iu instanceof TLRPC.TL_inputUser ? ((TLRPC.TL_inputUser) iu).user_id : 0;
        if (iu instanceof TLRPC.TL_inputUserSelf || uid == UserConfig.getInstance(acc).getClientUserId()) {
            return me.getSk();
        }
        return Messenger.skForUser(uid);
    }

    /** A bare vector of longs, as `photos.deletePhotos` answers. */
    private static TLObject longVector(TLObject request, ArrayList<Long> ids) {
        SerializedData out = new SerializedData(8 + 8 * ids.size());
        out.writeInt32(0x1cb5c415);
        out.writeInt32(ids.size());
        for (Long id : ids) {
            out.writeInt64(id);
        }
        SerializedData in = new SerializedData(out.toByteArray());
        TLObject parsed = request.deserializeResponse(in, in.readInt32(true), true);
        out.cleanup();
        in.cleanup();
        return parsed;
    }
}
