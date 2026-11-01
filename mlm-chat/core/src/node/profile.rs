//! Profile photos (SPEC 10; step 6.2 of the work queue).
//!
//! - Ours: the image is sealed like any file (`media.rs`), and a `Profile` with its id, key,
//!   sealed size and tiny preview goes inside Olm to every contact and to the group members we
//!   reach (their hidden contact rows). A new contact, and a group member who just became
//!   reachable, get it too. Removing the photo sends a `Profile` without one.
//! - Theirs: a `Profile` replaces what we have only when its `version` is newer. The file is
//!   asked for from them (a photo is small) and shows once it is whole.

use super::{enqueue, Event, Node, State};
use crate::blob::BlobId;
use crate::envelope::{Body, Envelope, Profile, MAX_THUMB};
use crate::identity::Identity;
use crate::store::{Db, ProfileRow};
use crate::util::{array, now_ms};
use crate::{Result, UserId};
use std::path::Path;

/// A larger photo than this is not taken (a profile photo is a few hundred KiB).
pub const MAX_PHOTO: u64 = 5 * 1024 * 1024;

/// A profile or group photo as the app shows it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Photo {
    pub blob_id: BlobId,
    /// The id the app (Telegram) knows the file by: `media_short_id(blob_id)`.
    pub short: i64,
    /// Sealed size.
    pub size: u64,
    pub thumb: Vec<u8>,
}

impl Photo {
    pub fn new(blob_id: BlobId, size: u64, thumb: Vec<u8>) -> Photo {
        Photo { blob_id, short: super::media::short_id(&blob_id), size, thumb }
    }
}

/// A preview that fits a message (SPEC 5), or none.
pub(super) fn thumb_ok(thumb: &[u8]) -> Vec<u8> {
    if thumb.len() <= MAX_THUMB {
        thumb.to_vec()
    } else {
        Vec::new()
    }
}

fn body(p: &ProfileRow, name: &str) -> Profile {
    let mut out = Profile { first_name: name.into(), version: p.version, photo_thumb: p.thumb.clone(), ..Default::default() };
    if let Some((id, key, size)) = &p.photo {
        out.photo_blob = id.to_vec();
        out.photo_key = key.to_vec();
        out.photo_size = *size;
    }
    out
}

/// The `Profile` we send now, if we ever set a photo (or removed one).
pub(super) fn own_profile(db: &Db, identity: &Identity) -> Result<Option<Envelope>> {
    Ok(db.profile(&identity.sk())?.map(|p| Envelope::new(Body::Profile(body(&p, identity.name())))))
}

/// Inside the Olm transaction: a `Profile` from `from`. True if a request for its photo was
/// queued.
pub(super) fn on_profile(db: &Db, identity: &Identity, from: &UserId, p: &Profile, events: &mut Vec<Event>) -> Result<bool> {
    if db.profile(from)?.is_some_and(|old| old.version >= p.version) {
        return Ok(false);
    }
    let photo = match (array::<32>(&p.photo_blob), array::<32>(&p.photo_key)) {
        (Some(id), Some(key)) if p.photo_size > 0 && p.photo_size <= MAX_PHOTO => Some((id, key, p.photo_size)),
        _ => None,
    };
    let thumb = if photo.is_some() { p.photo_thumb.clone() } else { Vec::new() };
    db.profile_save(from, &ProfileRow { version: p.version, photo, thumb })?;
    events.push(Event::Profile { peer: *from });
    match photo {
        Some((id, key, size)) => super::media::expect_file(db, identity, from, &id, &key, size),
        None => Ok(false),
    }
}

impl Node {
    /// Our profile photo from an image file, with its tiny preview; `None` removes it. Every
    /// contact (and every group member we reach) gets the new profile. Returns the photo.
    pub fn set_photo(&self, path: Option<&Path>, thumb: &[u8]) -> Result<Option<Photo>> {
        let sealed = match path {
            Some(p) => Some(self.inner.seal_file(p)?),
            None => None,
        };
        {
            let mut guard = self.inner.state.lock().expect("state");
            let State { store, identity } = &mut *guard;
            let me = identity.sk();
            let old = store.db().profile(&me)?;
            let version = now_ms().max(old.map(|o| o.version + 1).unwrap_or(0));
            let row = ProfileRow {
                version,
                photo: sealed.as_ref().map(|s| (s.blob_id, s.key, s.size)),
                thumb: if sealed.is_some() { thumb_ok(thumb) } else { Vec::new() },
            };
            let env = Envelope::new(Body::Profile(body(&row, identity.name())));
            let contacts = store.db().contacts()?;
            let identity: &Identity = identity;
            store.transaction(|db| {
                db.profile_save(&me, &row)?;
                for c in contacts.iter().filter(|c| !c.blocked) {
                    enqueue(db, identity, c, &env, false)?;
                }
                Ok(())
            })?;
        }
        log::info!("node: profile photo changed");
        self.inner.waker.wake();
        let me = self.me().sk;
        self.photo_of(&me)
    }

    /// A contact's or member's profile photo (or ours, under our own key), if they have one.
    /// The file itself may still be on its way (`media_status`).
    pub fn photo_of(&self, sk: &UserId) -> Result<Option<Photo>> {
        let st = self.inner.state.lock().expect("state");
        Ok(st.store.db().profile(sk)?.and_then(|p| p.photo.map(|(id, _, size)| Photo::new(id, size, p.thumb))))
    }
}
