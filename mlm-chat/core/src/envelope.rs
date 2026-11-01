//! Message envelope (SPEC 5): the protobuf `mlmchat.v1.Envelope`, encrypted inside Olm/Megolm.
//! Hand-written prost structs that mirror the SPEC field numbers exactly (no protoc needed).
//! `GroupOps` (field 25): a group's signed events (SPEC 7.2), in a pairwise Olm envelope.
//! Strict and capped (SPEC 0, PLAN 13.5): `validate` is applied to everything from the network.

use crate::util::{now_ms, random};
use crate::{Error, Result};
use prost::Message;

pub const ENVELOPE_VERSION: u32 = 1;
/// SPEC 5 says 64 KiB before encryption; 512 bytes are kept for Olm and the frame header so
/// every frame fits the largest padding bucket (SPEC 14.5).
pub const MAX_ENVELOPE: usize = 65536 - 512;
pub const MAX_THUMB: usize = 4096;
pub const MAX_IDS: usize = 256;
pub const MAX_ENTITIES: usize = 512;
pub const ID_LEN: usize = 16;

pub const FLAG_SILENT: u32 = 1;
pub const FLAG_PROTECTED: u32 = 2;
pub const FLAG_VIEW_ONCE: u32 = 4;

#[derive(Clone, PartialEq, Message)]
pub struct Envelope {
    #[prost(uint32, tag = "1")]
    pub v: u32,
    #[prost(bytes = "vec", tag = "2")]
    pub id: Vec<u8>,
    #[prost(uint64, tag = "3")]
    pub sent_ms: u64,
    #[prost(bytes = "vec", tag = "4")]
    pub group_id: Vec<u8>,
    #[prost(bytes = "vec", tag = "5")]
    pub reply_to: Vec<u8>,
    #[prost(message, optional, tag = "6")]
    pub quote: Option<Quote>,
    #[prost(message, optional, tag = "7")]
    pub forward: Option<Forward>,
    #[prost(uint32, tag = "8")]
    pub flags: u32,
    #[prost(uint32, tag = "9")]
    pub ttl_s: u32,
    #[prost(oneof = "Body", tags = "10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29")]
    pub body: Option<Body>,
    /// The app and version it was sent from (SPEC 5 `Client`, T75): every phone from draft 28 on
    /// puts its own ([`set_client`]); none, a phone from before. Only the chat's members see it.
    #[prost(message, optional, tag = "30")]
    pub client: Option<Client>,
}

/// The app a message came from (SPEC 5, draft 28, T75), so members see who has not updated: a
/// «نسخهٔ قدیمی» tag beside them (Ehsan, 31 Oct 2026).
#[derive(Clone, PartialEq, Eq, Message)]
pub struct Client {
    /// The app's own id, its Android package name.
    #[prost(string, tag = "1")]
    pub app: String,
    /// Its version code, without a per-processor offset.
    #[prost(uint32, tag = "2")]
    pub code: u32,
    /// Its version name, as its releases are tagged ("1.2.45").
    #[prost(string, tag = "3")]
    pub name: String,
}

pub const MAX_CLIENT_APP: usize = 64;
pub const MAX_CLIENT_NAME: usize = 32;

/// This phone's app, put on every envelope it makes.
static CLIENT: std::sync::RwLock<Option<Client>> = std::sync::RwLock::new(None);

/// The app this phone's envelopes and feed items say they come from (T75), set by the app when it
/// starts the node; one that does not fit goes as none.
pub fn set_client(client: Option<Client>) {
    *CLIENT.write().expect("client") = client.filter(|c| !c.app.is_empty() && client_fits(c));
}

/// This phone's own app ([`set_client`]); none before it was set.
pub fn own_client() -> Option<Client> {
    CLIENT.read().expect("client").clone()
}

fn client_fits(c: &Client) -> bool {
    c.app.len() <= MAX_CLIENT_APP && c.name.len() <= MAX_CLIENT_NAME
}

#[derive(Clone, PartialEq, prost::Oneof)]
pub enum Body {
    #[prost(message, tag = "10")]
    Text(Text),
    #[prost(message, tag = "11")]
    Media(Media),
    #[prost(message, tag = "12")]
    Sticker(Sticker),
    #[prost(message, tag = "13")]
    Location(Location),
    #[prost(message, tag = "14")]
    Live(LiveLocation),
    #[prost(message, tag = "15")]
    Contact(Contact),
    #[prost(message, tag = "16")]
    Poll(Poll),
    #[prost(message, tag = "17")]
    PollVote(PollVote),
    #[prost(message, tag = "18")]
    Edit(Edit),
    #[prost(message, tag = "19")]
    Delete(Delete),
    #[prost(message, tag = "20")]
    Reaction(Reaction),
    #[prost(message, tag = "21")]
    Pin(Pin),
    #[prost(message, tag = "22")]
    Receipt(Receipt),
    #[prost(message, tag = "23")]
    Profile(Profile),
    #[prost(message, tag = "24")]
    KeyUpdate(KeyUpdate),
    #[prost(message, tag = "25")]
    GroupOps(GroupOps),
    #[prost(message, tag = "26")]
    KeyShare(KeyShare),
    #[prost(message, tag = "27")]
    BlobRequest(BlobRequest),
    /// Official feed items passed on, and what a phone has (T38, `feed.rs`).
    #[prost(message, tag = "28")]
    Feed(FeedMsg),
    /// What the sender is doing in a one-to-one chat right now (SPEC 5.4, T56): transient.
    #[prost(message, tag = "29")]
    ChatAction(ChatAction),
}

/// «Typing», «sending a photo / a file», «recording a voice message», or the end of it (SPEC 5.4,
/// T56); «online» and «offline», for the last seen (SPEC 5.5, T57). Never stored, receipted or
/// queued: it goes only over a route open to the contact's own phone right now. A kind this phone
/// does not know is ignored, not refused.
#[derive(Clone, PartialEq, Message)]
pub struct ChatAction {
    #[prost(enumeration = "ChatActionKind", tag = "1")]
    pub kind: i32,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord, prost::Enumeration)]
#[repr(i32)]
pub enum ChatActionKind {
    Typing = 0,
    UploadPhoto = 1,
    UploadFile = 2,
    RecordVoice = 3,
    Cancel = 4,
    /// The sender's app is in front (SPEC 5.5).
    Online = 5,
    /// It is not any more.
    Offline = 6,
}

impl ChatActionKind {
    /// The name the app knows it by.
    pub fn name(&self) -> &'static str {
        match self {
            ChatActionKind::Typing => "typing",
            ChatActionKind::UploadPhoto => "upload_photo",
            ChatActionKind::UploadFile => "upload_file",
            ChatActionKind::RecordVoice => "record_voice",
            ChatActionKind::Cancel => "cancel",
            ChatActionKind::Online => "online",
            ChatActionKind::Offline => "offline",
        }
    }

    pub fn from_name(name: &str) -> Option<ChatActionKind> {
        [
            ChatActionKind::Typing,
            ChatActionKind::UploadPhoto,
            ChatActionKind::UploadFile,
            ChatActionKind::RecordVoice,
            ChatActionKind::Cancel,
            ChatActionKind::Online,
            ChatActionKind::Offline,
        ]
        .into_iter()
        .find(|k| k.name() == name)
    }
}

/// Chat action kinds a sender may use, known here or not (a newer phone's are ignored).
pub const MAX_CHAT_ACTION_KIND: i32 = 63;

/// Feed items for a peer to keep and pass on, and the newest ids we have of each feed, so the
/// peer can send what we lack.
#[derive(Clone, PartialEq, Message)]
pub struct FeedMsg {
    #[prost(message, repeated, tag = "1")]
    pub items: Vec<crate::feed::SignedFeedItem>,
    #[prost(message, repeated, tag = "2")]
    pub have: Vec<FeedHave>,
    /// The sender's member-count sketches of the official feeds (`census.rs`): this counting
    /// period's and the last one's. Absent from phones before it; they ignore it.
    #[prost(message, repeated, tag = "3")]
    pub census: Vec<CensusSketch>,
    /// Public relays the sender knows of (SPEC 19.12): mailboxes their owners opened to everyone,
    /// for phones with no relay of their own. Absent from phones before it; they ignore it.
    #[prost(message, repeated, tag = "4")]
    pub public_relays: Vec<SignedPublicRelay>,
}

/// A public relay as it travels: the encoded `PublicRelay` and its announcer's signature over
/// `"mlmchat/public-relay/1" || body`.
#[derive(Clone, PartialEq, Message)]
pub struct SignedPublicRelay {
    #[prost(bytes = "vec", tag = "1")]
    pub body: Vec<u8>,
    #[prost(bytes = "vec", tag = "2")]
    pub sig: Vec<u8>,
}

#[derive(Clone, PartialEq, Message)]
pub struct PublicRelay {
    /// The relay as a card names it (`1 || key of use || deployment id`).
    #[prost(bytes = "vec", tag = "1")]
    pub relay: Vec<u8>,
    #[prost(string, tag = "2")]
    pub name: String,
    /// Who announces it (its owner's identity; the signature is theirs).
    #[prost(bytes = "vec", tag = "3")]
    pub owner: Vec<u8>,
    #[prost(uint64, tag = "4")]
    pub ts_ms: u64,
    /// Good until then; the owner's phone says it again every day while it stays public.
    #[prost(uint64, tag = "5")]
    pub until_ms: u64,
    /// The owner closed it again: phones forget it.
    #[prost(bool, tag = "6")]
    pub withdrawn: bool,
}

/// Public relays in one `FeedMsg`.
pub const MAX_PUBLIC_RELAYS: usize = 24;

#[derive(Clone, PartialEq, Message)]
pub struct CensusSketch {
    #[prost(uint32, tag = "1")]
    pub epoch: u32,
    /// `census::PACKED_LEN` bytes.
    #[prost(bytes = "vec", tag = "2")]
    pub regs: Vec<u8>,
}

#[derive(Clone, PartialEq, Message)]
pub struct FeedHave {
    #[prost(bytes = "vec", tag = "1")]
    pub feed_id: Vec<u8>,
    #[prost(bytes = "vec", repeated, tag = "2")]
    pub ids: Vec<Vec<u8>>,
}

/// Items in one `FeedMsg`, and ids in one `FeedHave`.
pub const MAX_FEED_ITEMS: usize = 64;
pub const MAX_FEED_IDS: usize = 200;

/// Ask for the chunks of a file from chunk `from` on (SPEC 17).
#[derive(Clone, PartialEq, Message)]
pub struct BlobRequest {
    #[prost(bytes = "vec", tag = "1")]
    pub blob_id: Vec<u8>,
    #[prost(uint32, tag = "2")]
    pub from: u32,
}

/// A group's signed events (SPEC 7.2), each exactly as its author signed it.
#[derive(Clone, PartialEq, Message)]
pub struct GroupOps {
    #[prost(bytes = "vec", tag = "1")]
    pub group_id: Vec<u8>,
    #[prost(message, repeated, tag = "2")]
    pub ops: Vec<crate::group::SignedOp>,
}

/// Events per envelope, so a whole group's log for a new member fits in a few.
pub const MAX_GROUP_OPS: usize = 64;

#[derive(Clone, PartialEq, Message)]
pub struct Text {
    #[prost(string, tag = "1")]
    pub body: String,
    #[prost(message, repeated, tag = "2")]
    pub entities: Vec<Entity>,
    #[prost(message, optional, tag = "3")]
    pub preview: Option<LinkPreview>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord, prost::Enumeration)]
#[repr(i32)]
pub enum EntityType {
    Bold = 0,
    Italic = 1,
    Underline = 2,
    Strike = 3,
    Spoiler = 4,
    Code = 5,
    Pre = 6,
    Quote = 7,
    Link = 8,
    Mention = 9,
    Hashtag = 10,
}

#[derive(Clone, PartialEq, Message)]
pub struct Entity {
    #[prost(enumeration = "EntityType", tag = "1")]
    pub r#type: i32,
    #[prost(uint32, tag = "2")]
    pub offset: u32,
    #[prost(uint32, tag = "3")]
    pub length: u32,
    #[prost(string, tag = "4")]
    pub url: String,
    #[prost(bytes = "vec", tag = "5")]
    pub mention_sk: Vec<u8>,
    #[prost(string, tag = "6")]
    pub lang: String,
}

#[derive(Clone, PartialEq, Message)]
pub struct LinkPreview {
    #[prost(string, tag = "1")]
    pub url: String,
    #[prost(string, tag = "2")]
    pub title: String,
    #[prost(string, tag = "3")]
    pub description: String,
    #[prost(bytes = "vec", tag = "4")]
    pub thumb: Vec<u8>,
}

#[derive(Clone, PartialEq, Message)]
pub struct Quote {
    #[prost(string, tag = "1")]
    pub text: String,
    #[prost(uint32, tag = "2")]
    pub offset: u32,
}

#[derive(Clone, PartialEq, Message)]
pub struct Forward {
    #[prost(bytes = "vec", tag = "1")]
    pub from_sk: Vec<u8>,
    #[prost(string, tag = "2")]
    pub from_name: String,
    #[prost(uint64, tag = "3")]
    pub orig_ms: u64,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord, prost::Enumeration)]
#[repr(i32)]
pub enum MediaKind {
    File = 0,
    Photo = 1,
    Video = 2,
    Voice = 3,
    VideoNote = 4,
    Gif = 5,
}

#[derive(Clone, PartialEq, Message)]
pub struct Media {
    #[prost(enumeration = "MediaKind", tag = "1")]
    pub kind: i32,
    #[prost(string, tag = "2")]
    pub mime: String,
    #[prost(uint64, tag = "3")]
    pub size: u64,
    #[prost(bytes = "vec", tag = "4")]
    pub blob_id: Vec<u8>,
    #[prost(bytes = "vec", tag = "5")]
    pub key: Vec<u8>,
    #[prost(bytes = "vec", tag = "6")]
    pub thumb: Vec<u8>,
    #[prost(uint32, tag = "7")]
    pub width: u32,
    #[prost(uint32, tag = "8")]
    pub height: u32,
    #[prost(uint32, tag = "9")]
    pub duration_ms: u32,
    #[prost(string, tag = "10")]
    pub name: String,
    #[prost(bytes = "vec", tag = "11")]
    pub waveform: Vec<u8>,
    #[prost(string, tag = "12")]
    pub caption: String,
    #[prost(message, repeated, tag = "13")]
    pub caption_entities: Vec<Entity>,
    #[prost(bytes = "vec", tag = "14")]
    pub album_id: Vec<u8>,
}

#[derive(Clone, PartialEq, Message)]
pub struct Sticker {
    #[prost(bytes = "vec", tag = "1")]
    pub pack_id: Vec<u8>,
    #[prost(bytes = "vec", tag = "2")]
    pub sticker_id: Vec<u8>,
    #[prost(message, optional, tag = "3")]
    pub media: Option<Media>,
    #[prost(string, tag = "4")]
    pub emoji: String,
}

#[derive(Clone, PartialEq, Message)]
pub struct Location {
    #[prost(double, tag = "1")]
    pub lat: f64,
    #[prost(double, tag = "2")]
    pub lon: f64,
    #[prost(float, tag = "3")]
    pub accuracy_m: f32,
    #[prost(string, tag = "4")]
    pub title: String,
}

#[derive(Clone, PartialEq, Message)]
pub struct LiveLocation {
    #[prost(bytes = "vec", tag = "1")]
    pub session: Vec<u8>,
    #[prost(double, tag = "2")]
    pub lat: f64,
    #[prost(double, tag = "3")]
    pub lon: f64,
    #[prost(uint32, tag = "4")]
    pub period_s: u32,
    #[prost(bool, tag = "5")]
    pub stop: bool,
}

#[derive(Clone, PartialEq, Message)]
pub struct Contact {
    #[prost(bytes = "vec", tag = "1")]
    pub card: Vec<u8>,
    #[prost(string, tag = "2")]
    pub name: String,
}

#[derive(Clone, PartialEq, Message)]
pub struct Poll {
    #[prost(string, tag = "1")]
    pub question: String,
    #[prost(string, repeated, tag = "2")]
    pub options: Vec<String>,
    #[prost(bool, tag = "3")]
    pub anonymous: bool,
    #[prost(bool, tag = "4")]
    pub multiple: bool,
    #[prost(bool, tag = "5")]
    pub quiz: bool,
    #[prost(uint32, tag = "6")]
    pub correct: u32,
    #[prost(uint64, tag = "7")]
    pub close_ms: u64,
}

#[derive(Clone, PartialEq, Message)]
pub struct PollVote {
    #[prost(bytes = "vec", tag = "1")]
    pub poll_id: Vec<u8>,
    #[prost(uint32, repeated, tag = "2")]
    pub choices: Vec<u32>,
}

impl Poll {
    /// Whether a vote's choices fit this poll (T69): each one of its options, none twice, one
    /// only unless it takes several; none at all takes a vote back (never in a quiz, whose first
    /// answer stays).
    pub fn accepts(&self, choices: &[u32]) -> bool {
        let n = self.options.len() as u32;
        let mut seen = 0u32;
        for &c in choices {
            if c >= n || c >= 32 || seen & (1 << c) != 0 {
                return false;
            }
            seen |= 1 << c;
        }
        match choices.len() {
            0 => !self.quiz,
            1 => true,
            _ => self.multiple && !self.quiz,
        }
    }

    /// Whether it took votes at `at_ms` (its sender's clock): until its close time, if it has one.
    pub fn open_at(&self, at_ms: u64) -> bool {
        self.close_ms == 0 || at_ms <= self.close_ms
    }
}

#[derive(Clone, PartialEq, Message)]
pub struct Edit {
    #[prost(bytes = "vec", tag = "1")]
    pub target: Vec<u8>,
    #[prost(message, optional, tag = "2")]
    pub text: Option<Text>,
    #[prost(string, tag = "3")]
    pub caption: String,
}

#[derive(Clone, PartialEq, Message)]
pub struct Delete {
    #[prost(bytes = "vec", repeated, tag = "1")]
    pub ids: Vec<Vec<u8>>,
    #[prost(bool, tag = "2")]
    pub for_everyone: bool,
}

#[derive(Clone, PartialEq, Message)]
pub struct Reaction {
    #[prost(bytes = "vec", tag = "1")]
    pub target: Vec<u8>,
    #[prost(string, tag = "2")]
    pub emoji: String,
    #[prost(bool, tag = "3")]
    pub remove: bool,
}

#[derive(Clone, PartialEq, Message)]
pub struct Pin {
    #[prost(bytes = "vec", tag = "1")]
    pub target: Vec<u8>,
    #[prost(bool, tag = "2")]
    pub unpin: bool,
    #[prost(bool, tag = "3")]
    pub silent: bool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord, prost::Enumeration)]
#[repr(i32)]
pub enum ReceiptKind {
    Delivered = 0,
    Read = 1,
}

#[derive(Clone, PartialEq, Message)]
pub struct Receipt {
    #[prost(enumeration = "ReceiptKind", tag = "1")]
    pub kind: i32,
    #[prost(bytes = "vec", repeated, tag = "2")]
    pub ids: Vec<Vec<u8>>,
}

#[derive(Clone, PartialEq, Message)]
pub struct Profile {
    #[prost(string, tag = "1")]
    pub first_name: String,
    #[prost(string, tag = "2")]
    pub last_name: String,
    #[prost(string, tag = "3")]
    pub bio: String,
    #[prost(bytes = "vec", tag = "4")]
    pub photo_thumb: Vec<u8>,
    #[prost(bytes = "vec", tag = "5")]
    pub photo_blob: Vec<u8>,
    #[prost(bytes = "vec", tag = "6")]
    pub photo_key: Vec<u8>,
    #[prost(string, tag = "7")]
    pub birthday: String,
    #[prost(string, tag = "8")]
    pub emoji_status: String,
    #[prost(uint32, tag = "9")]
    pub name_color: u32,
    #[prost(uint64, tag = "10")]
    pub version: u64,
    /// Sealed size of the photo file (`photo_blob`), so its chunks can be asked for.
    #[prost(uint64, tag = "11")]
    pub photo_size: u64,
}

#[derive(Clone, PartialEq, Message)]
pub struct KeyUpdate {
    #[prost(bytes = "vec", tag = "1")]
    pub loc: Vec<u8>,
    #[prost(bytes = "vec", tag = "2")]
    pub card: Vec<u8>,
    /// Asking to join a group with an invite link (SPEC 7.4): the card above is the joiner's.
    #[prost(message, optional, tag = "3")]
    pub join: Option<JoinRequest>,
}

/// The proof of holding an invite link: its group, invite and secret (SPEC 7.4).
#[derive(Clone, PartialEq, Message)]
pub struct JoinRequest {
    #[prost(bytes = "vec", tag = "1")]
    pub group_id: Vec<u8>,
    #[prost(bytes = "vec", tag = "2")]
    pub invite_id: Vec<u8>,
    #[prost(bytes = "vec", tag = "3")]
    pub secret: Vec<u8>,
}

#[derive(Clone, PartialEq, Message)]
pub struct KeyShare {
    #[prost(bytes = "vec", tag = "1")]
    pub group_id: Vec<u8>,
    #[prost(bytes = "vec", tag = "2")]
    pub session_id: Vec<u8>,
    #[prost(bytes = "vec", tag = "3")]
    pub session_key: Vec<u8>,
    #[prost(uint32, tag = "4")]
    pub first_index: u32,
}

impl Envelope {
    pub fn new(body: Body) -> Envelope {
        Envelope { v: ENVELOPE_VERSION, id: random::<ID_LEN>().to_vec(), sent_ms: now_ms(), body: Some(body), client: own_client(), ..Default::default() }
    }

    pub fn text(body: &str) -> Envelope {
        Envelope::rich_text(body, Vec::new())
    }

    /// A text with its formatting and mentions (SPEC 5 `Entity`); bad entities are dropped.
    pub fn rich_text(body: &str, entities: Vec<Entity>) -> Envelope {
        let entities = clean_entities(body, entities);
        Envelope::new(Body::Text(Text { body: body.to_string(), entities, ..Default::default() }))
    }

    pub fn receipt(kind: ReceiptKind, ids: Vec<Vec<u8>>) -> Envelope {
        Envelope::new(Body::Receipt(Receipt { kind: kind as i32, ids }))
    }

    pub fn key_update(loc: [u8; 32], card: Vec<u8>) -> Envelope {
        Envelope::new(Body::KeyUpdate(KeyUpdate { loc: loc.to_vec(), card, join: None }))
    }

    pub fn chat_action(kind: ChatActionKind) -> Envelope {
        Envelope::new(Body::ChatAction(ChatAction { kind: kind as i32 }))
    }

    pub fn to_bytes(&self) -> Result<Vec<u8>> {
        validate(self)?;
        Ok(self.encode_to_vec())
    }

    /// Decode and validate bytes that came from the network.
    pub fn from_bytes(bytes: &[u8]) -> Result<Envelope> {
        if bytes.len() > MAX_ENVELOPE {
            return Err(Error::Envelope("too large"));
        }
        let env = Envelope::decode(bytes)?;
        validate(&env)?;
        Ok(env)
    }

    /// Body kinds that get an automatic "delivered" receipt and show up in the chat. A reaction
    /// shows beside its message, never as one; a delete takes messages away (SPEC 5.2).
    pub fn is_visible(&self) -> bool {
        !matches!(
            self.body,
            Some(Body::Receipt(_))
                | Some(Body::KeyUpdate(_))
                | Some(Body::KeyShare(_))
                | Some(Body::GroupOps(_))
                | Some(Body::BlobRequest(_))
                | Some(Body::Profile(_))
                | Some(Body::Feed(_))
                | Some(Body::Reaction(_))
                | Some(Body::PollVote(_))
                | Some(Body::Delete(_))
                | Some(Body::ChatAction(_))
                | Some(Body::Pin(_))
                | None
        )
    }

    pub fn kind_name(&self) -> &'static str {
        match &self.body {
            Some(Body::Text(_)) => "text",
            Some(Body::Media(_)) => "media",
            Some(Body::Sticker(_)) => "sticker",
            Some(Body::Location(_)) => "location",
            Some(Body::Live(_)) => "live_location",
            Some(Body::Contact(_)) => "contact",
            Some(Body::Poll(_)) => "poll",
            Some(Body::PollVote(_)) => "poll_vote",
            Some(Body::Edit(_)) => "edit",
            Some(Body::Delete(_)) => "delete",
            Some(Body::Reaction(_)) => "reaction",
            Some(Body::Pin(_)) => "pin",
            Some(Body::Receipt(_)) => "receipt",
            Some(Body::Profile(_)) => "profile",
            Some(Body::KeyUpdate(_)) => "key_update",
            Some(Body::GroupOps(_)) => "group_ops",
            Some(Body::BlobRequest(_)) => "blob_request",
            Some(Body::Feed(_)) => "feed",
            Some(Body::KeyShare(_)) => "key_share",
            Some(Body::ChatAction(_)) => "chat_action",
            None => "none",
        }
    }
}

fn id_ok(b: &[u8]) -> bool {
    b.len() == ID_LEN
}

fn opt_id_ok(b: &[u8]) -> bool {
    b.is_empty() || id_ok(b)
}

fn entity_ok(units: u64, e: &Entity) -> bool {
    EntityType::try_from(e.r#type).is_ok()
        && u64::from(e.offset) + u64::from(e.length) <= units
        && (e.mention_sk.is_empty() || e.mention_sk.len() == 32)
        && e.url.len() <= 2048
        && e.lang.len() <= 32
}

fn entities_ok(text: &str, entities: &[Entity]) -> bool {
    let units = text.encode_utf16().count() as u64;
    entities.len() <= MAX_ENTITIES && entities.iter().all(|e| entity_ok(units, e))
}

/// The `Edit` that gives the message `stored` (its id `target`) the text `text`, or None for a
/// message that has no text to change (SPEC 5 `Edit`). A text message's new text goes in `text`.
/// A media message's new caption goes in `caption`, and also in `text`, with its formatting:
/// `caption` has no field for that.
pub fn edit_for(stored: &Envelope, target: &[u8], text: &str, entities: Vec<Entity>) -> Option<Edit> {
    let entities = clean_entities(text, entities);
    let new_text = Text { body: text.to_string(), entities, ..Default::default() };
    match stored.body.as_ref()? {
        Body::Text(_) if !text.is_empty() => Some(Edit { target: target.to_vec(), text: Some(new_text), caption: String::new() }),
        Body::Media(_) => Some(Edit { target: target.to_vec(), caption: text.to_string(), text: Some(new_text) }),
        _ => None,
    }
}

/// The message `stored` with `edit` made in it, or None when the edit does not fit it: a text
/// message takes the edit's text (its link preview goes, being of the old text); a media message
/// its caption, with the formatting the edit's text carries for the same caption. Its id, reply and
/// everything else stay.
pub fn apply_edit(stored: &Envelope, edit: &Edit) -> Option<Envelope> {
    let mut out = stored.clone();
    match out.body.as_mut()? {
        Body::Text(t) => {
            let new = edit.text.as_ref().filter(|n| !n.body.is_empty())?;
            t.body = new.body.clone();
            t.entities = clean_entities(&new.body, new.entities.clone());
            t.preview = None;
        }
        Body::Media(m) => {
            m.caption = edit.caption.clone();
            m.caption_entities = match edit.text.as_ref() {
                Some(n) if n.body == edit.caption => clean_entities(&edit.caption, n.entities.clone()),
                _ => Vec::new(),
            };
        }
        _ => return None,
    }
    Some(out)
}

/// The entities of `text` that [`validate`] accepts, at most [`MAX_ENTITIES`]: a send drops a
/// bad one from the app rather than failing the whole message.
pub fn clean_entities(text: &str, mut entities: Vec<Entity>) -> Vec<Entity> {
    let units = text.encode_utf16().count() as u64;
    entities.retain(|e| e.length > 0 && entity_ok(units, e));
    entities.truncate(MAX_ENTITIES);
    entities
}

/// Strict checks on every envelope. Anything outside the caps is rejected, not truncated.
pub fn validate(env: &Envelope) -> Result<()> {
    if env.v != ENVELOPE_VERSION {
        return Err(Error::Envelope("unsupported envelope version"));
    }
    if !id_ok(&env.id) {
        return Err(Error::Envelope("id must be 16 bytes"));
    }
    if env.group_id.len() > 32 || !opt_id_ok(&env.reply_to) {
        return Err(Error::Envelope("bad group_id or reply_to"));
    }
    if env.encoded_len() > MAX_ENVELOPE {
        return Err(Error::Envelope("too large"));
    }
    if let Some(f) = &env.forward {
        if !(f.from_sk.is_empty() || f.from_sk.len() == 32) || f.from_name.len() > 128 {
            return Err(Error::Envelope("bad forward"));
        }
    }
    if env.client.as_ref().is_some_and(|c| !client_fits(c)) {
        return Err(Error::Envelope("bad client"));
    }
    let ok = match env.body.as_ref().ok_or(Error::Envelope("missing or unsupported body"))? {
        Body::Text(t) => entities_ok(&t.body, &t.entities) && t.preview.as_ref().is_none_or(|p| p.thumb.len() <= MAX_THUMB),
        Body::Media(m) => {
            MediaKind::try_from(m.kind).is_ok()
                && m.key.len() == 32
                && m.blob_id.len() == 32
                && m.thumb.len() <= MAX_THUMB
                && m.mime.len() <= 128
                && m.name.len() <= 255
                && entities_ok(&m.caption, &m.caption_entities)
                && opt_id_ok(&m.album_id)
                && m.size <= 2 * 1024 * 1024 * 1024
        }
        Body::Sticker(s) => s.media.as_ref().is_none_or(|m| m.thumb.len() <= MAX_THUMB) && s.emoji.len() <= 32,
        Body::Location(l) => l.lat.abs() <= 90.0 && l.lon.abs() <= 180.0,
        Body::Live(l) => l.lat.abs() <= 90.0 && l.lon.abs() <= 180.0 && l.session.len() <= 32,
        Body::Contact(c) => c.card.len() <= crate::card::MAX_CARD_BYTES && c.name.len() <= 128,
        Body::Poll(p) => (2..=10).contains(&p.options.len()) && p.question.len() <= 1024 && p.options.iter().all(|o| o.len() <= 256),
        Body::PollVote(v) => id_ok(&v.poll_id) && v.choices.len() <= 10,
        Body::Edit(e) => id_ok(&e.target) && e.text.as_ref().is_none_or(|t| entities_ok(&t.body, &t.entities)),
        Body::Delete(d) => !d.ids.is_empty() && d.ids.len() <= MAX_IDS && d.ids.iter().all(|i| id_ok(i)),
        Body::Reaction(r) => id_ok(&r.target) && r.emoji.len() <= 32,
        Body::Pin(p) => id_ok(&p.target),
        Body::Receipt(r) => ReceiptKind::try_from(r.kind).is_ok() && !r.ids.is_empty() && r.ids.len() <= MAX_IDS && r.ids.iter().all(|i| id_ok(i)),
        Body::Profile(p) => {
            p.photo_thumb.len() <= MAX_THUMB
                && (p.photo_key.is_empty() || p.photo_key.len() == 32)
                && (p.photo_blob.is_empty() || p.photo_blob.len() == 32)
                && p.first_name.len() <= 128
        }
        Body::KeyUpdate(k) => {
            k.loc.len() == 32
                && k.card.len() <= crate::card::MAX_CARD_BYTES
                && k.join.as_ref().is_none_or(|j| j.group_id.len() == crate::group::GROUP_ID_LEN && j.invite_id.len() == 16 && j.secret.len() == 32)
        }
        Body::GroupOps(g) => {
            g.group_id.len() == crate::group::GROUP_ID_LEN
                && (1..=MAX_GROUP_OPS).contains(&g.ops.len())
                && g.ops.iter().all(|o| o.body.len() <= 8192 && o.sig.len() == 64)
        }
        Body::KeyShare(k) => k.group_id.len() <= 32 && k.session_key.len() <= 512,
        Body::BlobRequest(r) => r.blob_id.len() == 32,
        Body::Feed(f) => {
            f.items.len() <= MAX_FEED_ITEMS
                && f.items.iter().all(|i| i.body.len() <= crate::feed::MAX_POST + 1024 && i.sig.len() == 64)
                && f.have.len() <= 4
                && f.have.iter().all(|h| h.feed_id.len() == crate::feed::FEED_ID_LEN && h.ids.len() <= MAX_FEED_IDS && h.ids.iter().all(|i| i.len() == crate::feed::ITEM_ID_LEN))
                && f.census.len() <= 2
                && f.census.iter().all(|c| c.regs.len() == crate::census::PACKED_LEN)
                && f.public_relays.len() <= MAX_PUBLIC_RELAYS
                && f.public_relays.iter().all(|r| r.body.len() <= 512 && r.sig.len() == 64)
        }
        Body::ChatAction(a) => (0..=MAX_CHAT_ACTION_KIND).contains(&a.kind) && env.group_id.is_empty(),
    };
    if ok {
        Ok(())
    } else {
        Err(Error::Envelope("field outside the contract caps"))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn text_round_trips() {
        let e = Envelope::text("salam");
        let back = Envelope::from_bytes(&e.to_bytes().unwrap()).unwrap();
        assert_eq!(back, e);
        assert!(back.is_visible());
    }

    #[test]
    fn caps_are_enforced() {
        let mut e = Envelope::text("x");
        e.id = vec![1; 3];
        assert!(e.to_bytes().is_err());
        let big = Envelope::text(&"a".repeat(MAX_ENVELOPE));
        assert!(big.to_bytes().is_err());
        let mut bad_entity = Envelope::text("ab");
        if let Some(Body::Text(t)) = &mut bad_entity.body {
            t.entities.push(Entity { r#type: EntityType::Bold as i32, offset: 1, length: 5, ..Default::default() });
        }
        assert!(bad_entity.to_bytes().is_err());
    }

    #[test]
    fn chat_action_round_trips_and_is_no_message() {
        let e = Envelope::chat_action(ChatActionKind::Typing);
        let back = Envelope::from_bytes(&e.to_bytes().unwrap()).unwrap();
        assert_eq!(back, e);
        assert!(!back.is_visible(), "never a message of its own");
        assert_eq!(ChatActionKind::from_name("record_voice"), Some(ChatActionKind::RecordVoice));
        // a newer phone's kind passes (and is ignored by the node); a group's is refused
        let mut newer = e.clone();
        newer.body = Some(Body::ChatAction(ChatAction { kind: 9 }));
        assert!(Envelope::from_bytes(&newer.encode_to_vec()).is_ok());
        let mut in_group = e;
        in_group.group_id = vec![1; 32];
        assert!(in_group.to_bytes().is_err(), "one-to-one only");
    }

    #[test]
    fn unknown_body_is_rejected_not_guessed() {
        // field 30 is unknown: decodes as no body, and validation refuses it (a phone from before
        // field 29 drops a chat action the same way); an empty field 25 (group events) is refused
        // by its caps
        let mut bytes = Envelope::text("x").encode_to_vec();
        let mut no_body = Envelope::decode(&bytes[..]).unwrap();
        no_body.body = None;
        bytes = no_body.encode_to_vec();
        let mut unknown = bytes.clone();
        unknown.extend_from_slice(&[0xF2, 0x01, 0x00]); // field 30, length 0
        assert!(Envelope::from_bytes(&unknown).is_err());
        bytes.extend_from_slice(&[0xCA, 0x01, 0x00]); // field 25, length 0
        assert!(Envelope::from_bytes(&bytes).is_err());
    }
}
