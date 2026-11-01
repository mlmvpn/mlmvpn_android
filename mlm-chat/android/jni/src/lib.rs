//! JNI side of `org.mlmchat.core.NativeCore` (android/core): the identity (make one, name it, keep
//! it in the encrypted database, open it again), the direct transport, the p2p transport
//! (volunteers, private DHT, relay circuits; phase 3), cards and contacts, text messages with their
//! history, receipts and deleting, honest reachability, and the node's events. Data crosses as
//! JSON strings.
//!
//! Handles are raw pointers of boxed Rust values, owned by the Kotlin wrappers in android/core,
//! which free each exactly once. Every entry point catches panics and turns errors into
//! `org.mlmchat.core.CoreException`; an error message never carries a key, a name or a path (the
//! core's own logging rule). The core's log lines, Info and up, go to logcat under the tag
//! `mlmchat`: they carry only kinds and counts, never a key, a name, an address or message text.

use std::collections::HashMap;
use std::net::{Ipv6Addr, SocketAddr};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::PathBuf;
use std::sync::mpsc::{Receiver, RecvTimeoutError};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

use jni::objects::{JByteArray, JClass, JIntArray, JLongArray, JString};
use jni::sys::{jboolean, jint, jlong, jstring};
use jni::JNIEnv;
use mlmchat_core::envelope::{Body, Entity, Envelope};
use mlmchat_core::identity::Identity;
use mlmchat_core::node::{contact_display_name, Event, Me, Node, NodeOptions};
use mlmchat_core::pair::{self, PairConfig};
use mlmchat_core::store::{Contact, MessageRow};
use mlmchat_core::transport::direct::DirectConfig;
use mlmchat_core::transport::ble::{BleCmd, BleConfig, BleTransport};
use mlmchat_core::transport::p2p::{Multiaddr, P2pConfig};
use mlmchat_core::transport::scope::NetScope;
use mlmchat_core::transport::Transport;
use mlmchat_core::UserId;
use serde_json::{json, Value};

const EXCEPTION: &str = "org/mlmchat/core/CoreException";

/// The core's log lines to logcat (liblog is already linked, no extra library).
struct Logcat;

#[cfg(target_os = "android")]
#[link(name = "log")]
extern "C" {
    fn __android_log_write(prio: std::os::raw::c_int, tag: *const std::os::raw::c_char, text: *const std::os::raw::c_char) -> std::os::raw::c_int;
}

impl log::Log for Logcat {
    fn enabled(&self, m: &log::Metadata) -> bool {
        m.level() <= log::Level::Info && m.target().starts_with("mlmchat")
    }

    fn log(&self, r: &log::Record) {
        if !self.enabled(r.metadata()) {
            return;
        }
        #[cfg(target_os = "android")]
        {
            let prio = match r.level() {
                log::Level::Error => 6,
                log::Level::Warn => 5,
                _ => 4,
            };
            if let Ok(text) = std::ffi::CString::new(r.args().to_string().replace('\0', " ")) {
                // SAFETY: both strings are NUL-terminated and live for the call
                unsafe {
                    __android_log_write(prio, c"mlmchat".as_ptr(), text.as_ptr());
                }
            }
        }
    }

    fn flush(&self) {}
}

static LOGCAT: Logcat = Logcat;

/// Called once when the library is loaded: the core's log lines start reaching logcat.
#[no_mangle]
pub extern "system" fn JNI_OnLoad(_vm: *mut jni::sys::JavaVM, _reserved: *mut std::ffi::c_void) -> jint {
    if log::set_logger(&LOGCAT).is_ok() {
        log::set_max_level(log::LevelFilter::Info);
    }
    jni::sys::JNI_VERSION_1_6
}

/// Name an identity carries between `prepare` and `create`; never saved (create renames it).
const UNNAMED: &str = "-";

enum Fail {
    Core(mlmchat_core::Error),
    Jni,
    Arg(&'static str),
}

impl From<mlmchat_core::Error> for Fail {
    fn from(e: mlmchat_core::Error) -> Self {
        Fail::Core(e)
    }
}

impl From<jni::errors::Error> for Fail {
    fn from(_: jni::errors::Error) -> Self {
        Fail::Jni
    }
}

/// Runs `f`; a panic or an error becomes a CoreException and the call returns `fallback`. A call
/// of `SLOW_LOCK_MS` or more is logged with the line of its function here (no content), so the
/// app's slow calls into the core can be named.
#[track_caller]
fn guard<T>(env: &mut JNIEnv, fallback: T, f: impl FnOnce(&mut JNIEnv) -> Result<T, Fail>) -> T {
    let at = std::panic::Location::caller();
    let started = std::time::Instant::now();
    let out = guard_untimed(env, fallback, f);
    let ms = started.elapsed().as_millis();
    if ms >= mlmchat_core::util::SLOW_LOCK_MS {
        log::info!("jni: lib.rs:{} took {ms} ms", at.line());
    }
    out
}

/// [`guard`] without the timing, for a call that waits on purpose (`pollEvent`).
fn guard_untimed<T>(env: &mut JNIEnv, fallback: T, f: impl FnOnce(&mut JNIEnv) -> Result<T, Fail>) -> T {
    let message = match catch_unwind(AssertUnwindSafe(|| f(env))) {
        Ok(Ok(v)) => return v,
        Ok(Err(Fail::Core(e))) => e.to_string(),
        Ok(Err(Fail::Arg(what))) => format!("argument: {what}"),
        // A JNI error has already left a Java exception pending.
        Ok(Err(Fail::Jni)) => return fallback,
        Err(_) => "core panicked".to_string(),
    };
    if !env.exception_check().unwrap_or(false) {
        let _ = env.throw_new(EXCEPTION, message);
    }
    fallback
}

fn string(env: &mut JNIEnv, s: &JString) -> Result<String, Fail> {
    if s.is_null() {
        return Err(Fail::Arg("null string"));
    }
    Ok(env.get_string(s)?.into())
}

fn key(env: &mut JNIEnv, k: &JByteArray) -> Result<[u8; 32], Fail> {
    if k.is_null() {
        return Err(Fail::Arg("null key"));
    }
    let bytes = env.convert_byte_array(k)?;
    bytes.try_into().map_err(|_| Fail::Arg("the database key must be 32 bytes"))
}

/// # Safety-by-contract
/// `h` is a pointer from `prepare` that the caller has not freed.
fn prepared<'a>(h: jlong) -> Result<&'a mut Identity, Fail> {
    if h == 0 {
        return Err(Fail::Arg("closed identity"));
    }
    Ok(unsafe { &mut *(h as *mut Identity) })
}

fn node<'a>(h: jlong) -> Result<&'a Node, Fail> {
    if h == 0 {
        return Err(Fail::Arg("closed node"));
    }
    Ok(unsafe { &*(h as *const Node) })
}

fn jstr(env: &mut JNIEnv, s: &str) -> Result<jstring, Fail> {
    Ok(env.new_string(s)?.into_raw())
}

/// A new identity, keys made on this phone and kept only in memory until `create`.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_prepare(mut env: JNIEnv, _c: JClass) -> jlong {
    guard(&mut env, 0, |_| Ok(Box::into_raw(Box::new(Identity::new(UNNAMED)?)) as jlong))
}

/// The 30-digit half of every security number of this identity (SPEC 14.1).
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_preparedCode(mut env: JNIEnv, _c: JClass, h: jlong) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let code = prepared(h)?.code();
        jstr(env, &code)
    })
}

#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_dropPrepared(mut env: JNIEnv, _c: JClass, h: jlong) {
    guard(&mut env, (), |_| {
        if h != 0 {
            drop(unsafe { Box::from_raw(h as *mut Identity) });
        }
        Ok(())
    })
}

/// Names the prepared identity and saves it in a new database. Consumes `h` either way.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_create(
    mut env: JNIEnv,
    _c: JClass,
    h: jlong,
    db_path: JString,
    db_key: JByteArray,
    name: JString,
) -> jlong {
    guard(&mut env, 0, |env| {
        if h == 0 {
            return Err(Fail::Arg("closed identity"));
        }
        let mut identity = *unsafe { Box::from_raw(h as *mut Identity) };
        let path = PathBuf::from(string(env, &db_path)?);
        let key = key(env, &db_key)?;
        identity.set_name(&string(env, &name)?)?;
        let n = Node::create_with(&path, &key, identity, NodeOptions::default())?;
        Ok(Box::into_raw(Box::new(n)) as jlong)
    })
}

/// Opens the database an earlier `create` made.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_open(mut env: JNIEnv, _c: JClass, db_path: JString, db_key: JByteArray) -> jlong {
    guard(&mut env, 0, |env| {
        let path = PathBuf::from(string(env, &db_path)?);
        let key = key(env, &db_key)?;
        let n = Node::open(&path, &key, NodeOptions::default())?;
        Ok(Box::into_raw(Box::new(n)) as jlong)
    })
}

/// The node's own identity as JSON: {"sk": hex, "name", "displayName", "code"}.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_me(mut env: JNIEnv, _c: JClass, h: jlong) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let me = node(h)?.me();
        jstr(env, &me_json(&me).to_string())
    })
}

fn me_json(me: &Me) -> Value {
    json!({
        "sk": hex::encode(me.sk),
        "name": me.name,
        "displayName": me.display_name,
        "code": me.code,
    })
}

/// Renames this identity (the four digits stay) and sends the new card to every contact not
/// blocked, whose phones then show the new name. Returns the new `me`.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_setName(mut env: JNIEnv, _c: JClass, h: jlong, name: JString) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let name = string(env, &name)?;
        let me = node(h)?.set_name(&name)?;
        jstr(env, &me_json(&me).to_string())
    })
}

#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_close(mut env: JNIEnv, _c: JClass, h: jlong) {
    guard(&mut env, (), |_| {
        if h != 0 {
            events().lock().expect("events").remove(&h);
            let n = unsafe { Box::from_raw(h as *mut Node) };
            n.stop();
        }
        Ok(())
    })
}

#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_sqlcipherVersion(mut env: JNIEnv, _c: JClass) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| jstr(env, &mlmchat_core::sqlcipher_version()))
}

fn sk(env: &mut JNIEnv, s: &JString) -> Result<UserId, Fail> {
    let hexed = string(env, s)?;
    hex::decode(hexed).ok().and_then(|b| b.try_into().ok()).ok_or(Fail::Arg("an identity key is 64 hex digits"))
}

fn contact_json(c: &Contact) -> Value {
    json!({
        "sk": hex::encode(c.sk),
        "name": c.name,
        "displayName": contact_display_name(c),
        "byThem": c.by_them,
        "blocked": c.blocked,
        "verified": c.verified,
        "addedMs": c.added_ms,
    })
}

/// A file message's details for the app (SPEC 5 `Media`); the key stays in the core.
fn media_json(env: &Envelope) -> Value {
    let Some(Body::Media(m)) = &env.body else { return Value::Null };
    let Ok(id) = <[u8; 32]>::try_from(m.blob_id.as_slice()) else { return Value::Null };
    let chunks = mlmchat_core::blob::chunk_count_sealed(m.size);
    json!({
        "kind": m.kind,
        "mime": m.mime,
        "size": m.size,
        "plain": m.size.saturating_sub(u64::from(chunks) * mlmchat_core::blob::TAG as u64),
        "blob": hex::encode(id),
        "short": mlmchat_core::node::media_short_id(&id),
        "name": m.name,
        "w": m.width,
        "h": m.height,
        "durationMs": m.duration_ms,
        "thumb": hex::encode(&m.thumb),
        "waveform": hex::encode(&m.waveform),
        "caption": m.caption,
        // the album it is one of (SPEC 5), empty for a file on its own
        "album": hex::encode(&m.album_id[..m.album_id.len().min(mlmchat_core::node::ALBUM_ID_LEN)]),
    })
}

/// The message an envelope answers (SPEC 5 `reply_to`), as this phone's seq through `seq_of`;
/// 0 for none, and for a message that is not here.
fn reply_of(env: Option<&Envelope>, seq_of: impl Fn(&[u8]) -> i64) -> i64 {
    env.filter(|e| !e.reply_to.is_empty()).map(|e| seq_of(&e.reply_to)).unwrap_or(0)
}

/// A text's or a caption's formatting and mentions (SPEC 5 `Entity`) for the app: type, offset
/// and length in UTF-16 units, url, `sk` (hex) of whom a mention names, lang of a code block.
fn entities_json(list: &[Entity]) -> Value {
    Value::Array(
        list.iter()
            .map(|e| json!({"type": e.r#type, "offset": e.offset, "length": e.length, "url": e.url, "sk": hex::encode(&e.mention_sk), "lang": e.lang}))
            .collect(),
    )
}

/// The entities of a message's text or caption.
fn message_entities(env: Option<&Envelope>) -> Value {
    match env.and_then(|e| e.body.as_ref()) {
        Some(Body::Text(t)) => entities_json(&t.entities),
        Some(Body::Media(m)) => entities_json(&m.caption_entities),
        _ => Value::Array(Vec::new()),
    }
}

/// Entities from the app, in [`entities_json`]'s shape; the core drops any it cannot send.
fn entities_from(v: Option<&Value>) -> Vec<Entity> {
    let n = |e: &Value, k: &str| e.get(k).and_then(Value::as_u64).unwrap_or(0) as u32;
    let s = |e: &Value, k: &str| e.get(k).and_then(Value::as_str).unwrap_or_default().to_string();
    v.and_then(Value::as_array)
        .map(|a| {
            a.iter()
                .map(|e| Entity {
                    r#type: e.get("type").and_then(Value::as_i64).unwrap_or(-1) as i32,
                    offset: n(e, "offset"),
                    length: n(e, "length"),
                    url: s(e, "url"),
                    mention_sk: hex::decode(s(e, "sk")).unwrap_or_default(),
                    lang: s(e, "lang"),
                })
                .collect()
        })
        .unwrap_or_default()
}

fn entities_arg(env: &mut JNIEnv, entities: &JString) -> Result<Vec<Entity>, Fail> {
    let v: Value = serde_json::from_str(&string(env, entities)?).map_err(|_| Fail::Arg("entities"))?;
    Ok(entities_from(Some(&v)))
}

fn message_json(m: &MessageRow, seq_of: impl Fn(&[u8]) -> i64, edited_ms: u64) -> Value {
    let env = Envelope::from_bytes(&m.envelope).ok();
    let media = env.as_ref().map(media_json).unwrap_or(Value::Null);
    let reply = reply_of(env.as_ref(), seq_of);
    let entities = message_entities(env.as_ref());
    let text = env.and_then(|e| match e.body {
        Some(Body::Text(t)) => Some(t.body),
        _ => None,
    });
    json!({
        "media": media,
        "id": hex::encode(m.id),
        "peer": hex::encode(m.peer),
        "seq": m.seq,
        "out": m.outgoing,
        "sentMs": m.sent_ms,
        "atMs": m.at_ms,
        "kind": m.kind,
        "text": text,
        "state": m.state.name(),
        "replyTo": reply,
        "entities": entities,
        "editedMs": edited_ms,
    })
}

fn event_json(e: &Event) -> Value {
    match e {
        Event::Message { peer, id, kind, text } => json!({"type": "message", "peer": hex::encode(peer), "id": hex::encode(id), "kind": kind, "text": text}),
        Event::State { peer, id, state } => json!({"type": "state", "peer": hex::encode(peer), "id": hex::encode(id), "state": state.name()}),
        Event::Queued { peer, reason } => json!({"type": "queued", "peer": hex::encode(peer), "reason": reason}),
        Event::Contact { peer, by_them } => json!({"type": "contact", "peer": hex::encode(peer), "byThem": by_them}),
        Event::Seen { peer, via } => json!({"type": "seen", "peer": hex::encode(peer), "via": via}),
        Event::ContactChanged { peer } => json!({"type": "contactChanged", "peer": hex::encode(peer)}),
        Event::Reachable { peer, via } => json!({"type": "reachable", "peer": hex::encode(peer), "via": via}),
        Event::Group { gid } => json!({"type": "group", "group": hex::encode(gid)}),
        Event::Blob { id, have, total } => json!({"type": "blob", "peer": "", "blob": hex::encode(id), "have": have, "total": total}),
        Event::GroupMessage { gid, sender, id, kind, text } => {
            json!({"type": "group_message", "group": hex::encode(gid), "peer": hex::encode(sender), "id": hex::encode(id), "kind": kind, "text": text})
        }
        Event::Profile { peer } => json!({"type": "profile", "peer": hex::encode(peer)}),
        Event::JoinRequest { gid, peer } => json!({"type": "join_request", "group": hex::encode(gid), "peer": hex::encode(peer)}),
        Event::Feed { feed_id } => json!({"type": "feed", "peer": "", "feed": hex::encode(feed_id)}),
        Event::Reaction { chat, group, id, peer } => json!({"type": "reaction", "peer": hex::encode(peer), "chat": hex::encode(chat), "group": group, "id": hex::encode(id)}),
        Event::PollVote { chat, group, id, peer } => json!({"type": "poll_vote", "peer": hex::encode(peer), "chat": hex::encode(chat), "group": group, "id": hex::encode(id)}),
        Event::Deleted { chat, group, seqs } => json!({"type": "deleted", "peer": "", "chat": hex::encode(chat), "group": group, "seqs": seqs}),
        Event::Edited { chat, group, seq } => json!({"type": "edited", "peer": "", "chat": hex::encode(chat), "group": group, "seq": seq}),
        Event::Pinned { peer } => json!({"type": "pinned", "peer": hex::encode(peer)}),
        Event::ChatAction { peer, kind } => json!({"type": "chat_action", "peer": hex::encode(peer), "kind": kind}),
        Event::Presence { peer, online } => json!({"type": "presence", "peer": hex::encode(peer), "online": online}),
        Event::GroupRead { gid, seq, peer } => json!({"type": "group_read", "peer": hex::encode(peer), "group": hex::encode(gid), "seq": seq}),
        Event::Client { peer } => json!({"type": "client", "peer": hex::encode(peer)}),
    }
}

fn seq_list(env: &mut JNIEnv, seqs: &JLongArray) -> Result<Vec<i64>, Fail> {
    let len = env.get_array_length(seqs).map_err(|_| Fail::Arg("seqs"))? as usize;
    let mut buf = vec![0i64; len];
    env.get_long_array_region(seqs, 0, &mut buf).map_err(|_| Fail::Arg("seqs"))?;
    Ok(buf)
}

/// One-to-one messages by seq, for both sides (SPEC 5.2): each contact takes its copy down too.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_deleteForEveryone(mut env: JNIEnv, _c: JClass, h: jlong, seqs: JLongArray) -> jint {
    guard(&mut env, 0, |env| {
        let list = seq_list(env, &seqs)?;
        Ok(node(h)?.delete_for_everyone(&list)? as jint)
    })
}

/// A new text for a one-to-one message we sent (by seq), or a new caption for a media message, on
/// both sides (SPEC 5 `Edit`). [entities]: a JSON array, as for a send.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_editText(mut env: JNIEnv, _c: JClass, h: jlong, seq: jlong, text: JString, entities: JString) {
    guard(&mut env, (), |env| {
        let text = string(env, &text)?;
        let entities = entities_arg(env, &entities)?;
        node(h)?.edit_text(seq, &text, entities)?;
        Ok(())
    })
}

/// The same for a group or channel message we sent, for every member.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_groupEditText(mut env: JNIEnv, _c: JClass, h: jlong, seq: jlong, text: JString, entities: JString) {
    guard(&mut env, (), |env| {
        let text = string(env, &text)?;
        let entities = entities_arg(env, &entities)?;
        node(h)?.group_edit_text(seq, &text, entities)?;
        Ok(())
    })
}

/// Group or channel messages by seq: for every member when `everyone` (ours, or anyone's for an
/// admin with the right), else on this phone only.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_groupDeleteSeqs(mut env: JNIEnv, _c: JClass, h: jlong, seqs: JLongArray, everyone: jboolean) -> jint {
    guard(&mut env, 0, |env| {
        let list = seq_list(env, &seqs)?;
        Ok(node(h)?.group_delete_seqs(&list, everyone != 0)? as jint)
    })
}

/// Reactions standing in a chat or group, as `[{"id","peer","emoji"}]` (oldest first).
fn reactions_json(list: &[([u8; 16], UserId, String)]) -> String {
    Value::Array(list.iter().map(|(id, who, emoji)| json!({"id": hex::encode(id), "peer": hex::encode(who), "emoji": emoji})).collect()).to_string()
}

fn msg_id(env: &mut JNIEnv, s: &JString) -> Result<[u8; 16], Fail> {
    let s = string(env, s)?;
    hex::decode(s).ok().and_then(|b| <[u8; 16]>::try_from(b.as_slice()).ok()).ok_or(Fail::Arg("message id"))
}

/// Our reaction to a message of the chat with `peer` (`emoji` empty: take it back).
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_react(mut env: JNIEnv, _c: JClass, h: jlong, peer: JString, id: JString, emoji: JString) {
    guard(&mut env, (), |env| {
        let p = sk(env, &peer)?;
        let target = msg_id(env, &id)?;
        let e = string(env, &emoji)?;
        node(h)?.react(&p, &target, &e)?;
        Ok(())
    })
}

#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_reactions(mut env: JNIEnv, _c: JClass, h: jlong, peer: JString) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let p = sk(env, &peer)?;
        let list = node(h)?.reactions(&p)?;
        jstr(env, &reactions_json(&list))
    })
}

/// Our reaction to a message of a group or channel (`emoji` empty: take it back).
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_groupReact(mut env: JNIEnv, _c: JClass, h: jlong, group: JString, id: JString, emoji: JString) {
    guard(&mut env, (), |env| {
        let g = gid(env, &group)?;
        let target = msg_id(env, &id)?;
        let e = string(env, &emoji)?;
        node(h)?.group_react(&g, &target, &e)?;
        Ok(())
    })
}

#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_groupReactions(mut env: JNIEnv, _c: JClass, h: jlong, group: JString) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let g = gid(env, &group)?;
        let list = node(h)?.group_reactions(&g)?;
        jstr(env, &reactions_json(&list))
    })
}

/// A message's poll (SPEC 5 `Poll`, T69), or null for a message that is none.
fn poll_json(e: &Envelope) -> Value {
    match &e.body {
        Some(Body::Poll(p)) => json!({
            "question": p.question,
            "options": p.options,
            "anonymous": p.anonymous,
            "multiple": p.multiple,
            "quiz": p.quiz,
            "correct": p.correct,
            "closeMs": p.close_ms,
        }),
        _ => Value::Null,
    }
}

/// A poll in a group or channel, from its JSON (as [`poll_json`] writes it); returns its id.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_groupSendPoll(mut env: JNIEnv, _c: JClass, h: jlong, group: JString, poll: JString, reply_to: jlong) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let g = gid(env, &group)?;
        let v: Value = serde_json::from_str(&string(env, &poll)?).map_err(|_| Fail::Arg("poll"))?;
        let p = mlmchat_core::envelope::Poll {
            question: v.get("question").and_then(Value::as_str).unwrap_or_default().to_string(),
            options: v.get("options").and_then(Value::as_array).map(|a| a.iter().filter_map(Value::as_str).map(str::to_string).collect()).unwrap_or_default(),
            anonymous: v.get("anonymous").and_then(Value::as_bool).unwrap_or(true),
            multiple: v.get("multiple").and_then(Value::as_bool).unwrap_or(false),
            quiz: v.get("quiz").and_then(Value::as_bool).unwrap_or(false),
            correct: v.get("correct").and_then(Value::as_u64).unwrap_or(0) as u32,
            close_ms: v.get("closeMs").and_then(Value::as_u64).unwrap_or(0),
        };
        let id = node(h)?.group_send_poll(&g, p, reply_to)?;
        jstr(env, &hex::encode(id))
    })
}

/// Our vote on a poll of a group or channel: the options chosen (none: take it back).
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_groupVote(mut env: JNIEnv, _c: JClass, h: jlong, group: JString, poll: JString, choices: JIntArray) {
    guard(&mut env, (), |env| {
        let g = gid(env, &group)?;
        let p = msg_id(env, &poll)?;
        let n = env.get_array_length(&choices)? as usize;
        let mut c = vec![0i32; n];
        env.get_int_array_region(&choices, 0, &mut c)?;
        if c.iter().any(|&x| x < 0) {
            return Err(Fail::Arg("choices"));
        }
        node(h)?.group_vote(&g, &p, &c.iter().map(|&x| x as u32).collect::<Vec<_>>())?;
        Ok(())
    })
}

/// Where a poll stands: {counts, voters, mine, who: [{sk, choices}]} (who only when not anonymous).
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_pollResults(mut env: JNIEnv, _c: JClass, h: jlong, group: JString, poll: JString) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let g = gid(env, &group)?;
        let p = msg_id(env, &poll)?;
        let r = node(h)?.poll_results(&g, &p)?;
        let who: Vec<Value> = r.who.iter().map(|(sk, c)| json!({"sk": hex::encode(sk), "choices": c})).collect();
        jstr(env, &json!({"counts": r.counts, "voters": r.voters, "mine": r.mine, "who": who}).to_string())
    })
}

type EventSlot = Arc<Mutex<Receiver<Event>>>;

/// Each open node's event receiver, kept apart from the node so one thread can wait on it.
fn events() -> &'static Mutex<HashMap<jlong, EventSlot>> {
    static EVENTS: OnceLock<Mutex<HashMap<jlong, EventSlot>>> = OnceLock::new();
    EVENTS.get_or_init(|| Mutex::new(HashMap::new()))
}

/// Starts the phase-1 direct transport (LAN with beacons, direct IPv6) and the delivery loop, on
/// `port` when it is free (the same port every start keeps the cards contacts hold valid), else
/// on any free port. Returns the addresses our cards carry, as a JSON array.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_startDirect(mut env: JNIEnv, _c: JClass, h: jlong, port: jint) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let n = node(h)?;
        events().lock().expect("events").entry(h).or_insert_with(|| Arc::new(Mutex::new(n.events())));
        let mut cfg = DirectConfig::default();
        if (1..=65535).contains(&port) {
            cfg.listen = SocketAddr::from((Ipv6Addr::UNSPECIFIED, port as u16));
        }
        let t = match n.start_direct(cfg) {
            Ok(t) => t,
            Err(_) if port > 0 => n.start_direct(DirectConfig::default())?,
            Err(e) => return Err(e.into()),
        };
        let addrs: Vec<String> = Transport::advertised_addrs(&*t).iter().map(|a| a.to_string()).collect();
        jstr(env, &json!(addrs).to_string())
    })
}

/// Starts the p2p transport (phase 3, SPEC 15): it keeps reservations on volunteers (from the
/// contacts' cards and `boot` in `config`), puts this phone's presence record in the private DHT,
/// and reaches contacts on other networks, directly when it can and through a volunteer when not.
/// `config`: JSON `{"port": n, "boot": ["/ip4/../tcp/../tls/p2p/.."], "volunteer": false,
/// "external": [...], "upnp": false}`, every field optional (`upnp`, also `door`: a volunteer
/// opens its own door on the router, SPEC 15.9). On `port` when it is free, else on any port.
/// Returns the network state (JSON), with the connection ladder's state (`ladder`, `rung`,
/// `tried`, `total`, `last_error`, `next_try_s`).
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_startP2p(mut env: JNIEnv, _c: JClass, h: jlong, config: JString) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let n = node(h)?;
        events().lock().expect("events").entry(h).or_insert_with(|| Arc::new(Mutex::new(n.events())));
        let text = string(env, &config)?;
        let c: Value = serde_json::from_str(&text).map_err(|_| Fail::Arg("p2p config is not JSON"))?;
        let addrs = |k: &str| -> Vec<Multiaddr> { c[k].as_array().map(|a| a.iter().filter_map(|v| v.as_str()?.parse().ok()).collect()).unwrap_or_default() };
        let cfg = P2pConfig {
            port: c["port"].as_u64().filter(|p| *p <= 65535).unwrap_or(0) as u16,
            volunteer: c["volunteer"].as_bool().unwrap_or(false),
            upnp: c["upnp"].as_bool().or(c["door"].as_bool()).unwrap_or(false),
            boot: addrs("boot"),
            external: addrs("external"),
            ..P2pConfig::default()
        };
        let retry = P2pConfig { port: 0, ..cfg.clone() };
        let wanted_port = cfg.port;
        match n.start_p2p(cfg) {
            Ok(_) => {}
            Err(_) if wanted_port > 0 => {
                n.start_p2p(retry)?;
            }
            Err(e) => return Err(e.into()),
        }
        jstr(env, &serde_json::to_string(&n.net_status()).unwrap_or_default())
    })
}

// ---- Bluetooth LE (SPEC 16): the platform's radio code drives these ----

fn ble(h: jlong) -> Result<Arc<BleTransport>, Fail> {
    node(h)?.ble().ok_or(Fail::Arg("bluetooth is not started"))
}

/// Starts the Bluetooth transport («nearby» on). Safe to call again.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_bleStart(mut env: JNIEnv, _c: JClass, h: jlong) {
    guard(&mut env, (), |_| {
        node(h)?.start_ble(BleConfig::default());
        Ok(())
    })
}

#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_bleStop(mut env: JNIEnv, _c: JClass, h: jlong) {
    guard(&mut env, (), |_| {
        node(h)?.stop_ble();
        Ok(())
    })
}

/// The service data to advertise now: the 8-byte contact tag (it changes every 10 minutes), or
/// `0x01 || id` (9 bytes) while «add nearby» is open (SPEC 16.4).
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_bleAdvertTag(mut env: JNIEnv, _c: JClass, h: jlong) -> jni::sys::jbyteArray {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let t = ble(h)?.advert_data();
        Ok(env.byte_array_from_slice(&t).map_err(|_| Fail::Arg("byte array"))?.into_raw())
    })
}

/// The next thing the radio must do, waiting up to `timeout_ms`; null when nothing. JSON:
/// `{"op":"connect","link":n,"addr":"..."}`, `{"op":"send","link":n,"data":"<hex>"}`,
/// `{"op":"close","link":n}`, `{"op":"advertise"}` (advertise again with `bleAdvertTag`).
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_bleNext(mut env: JNIEnv, _c: JClass, h: jlong, timeout_ms: jint) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let t = ble(h)?;
        let cmd = t.next_command(Duration::from_millis(timeout_ms.max(0) as u64));
        let v = match cmd {
            None => return Ok(std::ptr::null_mut()),
            Some(BleCmd::Connect { link, addr }) => json!({"op": "connect", "link": link, "addr": addr}),
            Some(BleCmd::Send { link, data }) => json!({"op": "send", "link": link, "data": hex::encode(data)}),
            Some(BleCmd::Close { link }) => json!({"op": "close", "link": link}),
            Some(BleCmd::Advertise) => json!({"op": "advertise"}),
        };
        jstr(env, &v.to_string())
    })
}

/// A Gereh device was seen advertising `tag` at `addr`.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_bleSeen(mut env: JNIEnv, _c: JClass, h: jlong, addr: JString, tag: JByteArray) {
    guard(&mut env, (), |env| {
        let a = string(env, &addr)?;
        let t = env.convert_byte_array(&tag).map_err(|_| Fail::Arg("tag"))?;
        ble(h)?.seen(&a, &t);
        Ok(())
    })
}

/// A link is open: one the core asked for (`inbound` false) or a device that connected to us.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_bleConnected(mut env: JNIEnv, _c: JClass, h: jlong, link: jint, inbound: jboolean) {
    guard(&mut env, (), |_| {
        ble(h)?.connected(link as u32, inbound != 0);
        Ok(())
    })
}

/// Bytes arrived on a link, in order.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_bleData(mut env: JNIEnv, _c: JClass, h: jlong, link: jint, data: JByteArray) {
    guard(&mut env, (), |env| {
        let d = env.convert_byte_array(&data).map_err(|_| Fail::Arg("data"))?;
        ble(h)?.data(link as u32, d);
        Ok(())
    })
}

/// A link closed, or could not be opened.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_bleClosed(mut env: JNIEnv, _c: JClass, h: jlong, link: jint) {
    guard(&mut env, (), |_| {
        ble(h)?.closed(link as u32);
        Ok(())
    })
}

/// Contacts beside us right now (0 when Bluetooth is off).
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_bleNearby(mut env: JNIEnv, _c: JClass, h: jlong) -> jint {
    guard(&mut env, 0, |_| Ok(node(h)?.ble().map_or(0, |t| t.nearby()) as jint))
}

// ---- «add nearby» (SPEC 16.4, T36) ----

/// Opens «add nearby» for two minutes under `name`: over Bluetooth if it runs, and over Wi-Fi
/// (the same network or a hotspot) when `wifi`. A second call closes the first.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_pairStart(mut env: JNIEnv, _c: JClass, h: jlong, name: JString, wifi: jboolean) -> jboolean {
    guard(&mut env, 0, |env| {
        let name = string(env, &name)?;
        let mut cfg = PairConfig { name, ..PairConfig::default() };
        if wifi == 0 {
            cfg.udp = None;
        }
        node(h)?.pair_start(cfg)?;
        Ok(1)
    })
}

/// The list: `{"open":bool,"left_s":n,"peers":[{"id","name","state","code","via"}]}`; `state` is
/// found, outgoing, incoming, waiting, done, declined or failed; `code` the four emoji once
/// someone asked.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_pairState(mut env: JNIEnv, _c: JClass, h: jlong) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let v = match node(h)?.pair() {
            None => json!({"open": false, "left_s": 0, "peers": []}),
            Some(p) => {
                let peers: Vec<_> = p
                    .peers()
                    .into_iter()
                    .map(|x| json!({"id": x.id, "name": x.name, "state": x.state.code(), "code": x.sas.map(|c| pair::sas_emoji(&c)), "via": x.via}))
                    .collect();
                json!({"open": true, "left_s": p.left_s(), "peers": peers})
            }
        };
        jstr(env, &v.to_string())
    })
}

fn pair_do(env: &mut JNIEnv, h: jlong, id: &JString, f: impl FnOnce(&pair::PairSession, &str) -> bool) -> jboolean {
    guard(env, 0, |env| {
        let id = string(env, id)?;
        Ok(node(h)?.pair().is_some_and(|p| f(&p, &id)) as jboolean)
    })
}

/// Ask someone in the list: both screens show the code.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_pairAsk(mut env: JNIEnv, _c: JClass, h: jlong, id: JString) -> jboolean {
    pair_do(&mut env, h, &id, |p, id| p.ask(id))
}

/// The person confirmed the codes match: our card goes, theirs is added once it is here.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_pairConfirm(mut env: JNIEnv, _c: JClass, h: jlong, id: JString) -> jboolean {
    pair_do(&mut env, h, &id, |p, id| p.confirm(id))
}

#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_pairDecline(mut env: JNIEnv, _c: JClass, h: jlong, id: JString) -> jboolean {
    pair_do(&mut env, h, &id, |p, id| p.decline(id))
}

#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_pairStop(mut env: JNIEnv, _c: JClass, h: jlong) {
    guard(&mut env, (), |_| {
        node(h)?.pair_stop();
        Ok(())
    })
}

/// Stops the p2p transport, to start it again with another config (the volunteer role turned on
/// or off). The direct transport and the queue go on.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_stopP2p(mut env: JNIEnv, _c: JClass, h: jlong) {
    guard(&mut env, (), |_| {
        node(h)?.stop_p2p();
        Ok(())
    })
}

/// Limits every connection, both ways, to the address ranges in `ranges` (one per line, SPEC
/// 15.10); the local network is always allowed. An empty string lifts the limit. Takes effect at
/// once. Returns how many ranges are in force.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_setScope(mut env: JNIEnv, _c: JClass, h: jlong, ranges: JString) -> jint {
    guard(&mut env, 0, |env| {
        let text = string(env, &ranges)?;
        let scope = NetScope::parse(text.lines());
        let n = scope.len();
        node(h)?.set_scope((!scope.is_empty()).then_some(scope));
        Ok(n as jint)
    })
}

/// Could this phone be a volunteer on the network it is on now (SPEC 15.11)? Read-only questions
/// to the router, a couple of seconds; call it off the main thread. JSON `{"verdict", "method",
/// "pinhole6"}`, no address.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_volunteerCheck(mut env: JNIEnv, _c: JClass, h: jlong) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let c = node(h)?.volunteer_check();
        jstr(env, &serde_json::to_string(&c).unwrap_or_default())
    })
}

/// A volunteer's ledger (SPEC 15.12), JSON: `volunteer`, `now`, `served`, `bytes_total`, `rx_run`,
/// `tx_run`, `peers` (`name`, `since_ms`, `rx`, `tx`, `reserved`, `circuits`). A peer's name is six
/// hex digits from a hash of its weekly network id: it says nothing about who it is.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_relayStatus(mut env: JNIEnv, _c: JClass, h: jlong) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let st = node(h)?.relay_status();
        jstr(env, &serde_json::to_string(&st).unwrap_or_default())
    })
}

/// Disconnect one peer of the ledger by its name, and keep it out for an hour.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_relayKick(mut env: JNIEnv, _c: JClass, h: jlong, name: JString) -> jboolean {
    guard(&mut env, 0, |env| {
        let n = string(env, &name)?;
        Ok(node(h)?.relay_kick(&n) as jboolean)
    })
}

/// The network state for the status line: volunteers known and connected, reservations held,
/// when our presence record last reached the DHT (JSON).
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_netStatus(mut env: JNIEnv, _c: JClass, h: jlong) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let st = node(h)?.net_status();
        jstr(env, &serde_json::to_string(&st).unwrap_or_default())
    })
}

/// What really happened with one contact: when a session with them last got through and how,
/// whether that is recent enough to call them online, and what is still queued (JSON).
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_contactStatus(mut env: JNIEnv, _c: JClass, h: jlong, peer: JString) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let p = sk(env, &peer)?;
        let st = node(h)?.contact_status(&p)?;
        jstr(env, &serde_json::to_string(&st).unwrap_or_default())
    })
}

/// The label routes are remembered under (SPEC 15.5): the network type and operator the phone is
/// on, e.g. `cell:43235` or `wifi:<hash>`. Never an SSID in the clear.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_setNetwork(mut env: JNIEnv, _c: JClass, h: jlong, label: JString) {
    guard(&mut env, (), |env| {
        let l = string(env, &label)?;
        node(h)?.set_network(&l);
        Ok(())
    })
}

/// The phone moved to another network: fresh connections, a fresh presence record, every queued
/// frame tried at once.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_networkChanged(mut env: JNIEnv, _c: JClass, h: jlong) {
    guard(&mut env, (), |_| {
        node(h)?.network_changed();
        Ok(())
    })
}

/// The next event as JSON, or null when none came within `timeout_ms`.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_pollEvent(mut env: JNIEnv, _c: JClass, h: jlong, timeout_ms: jint) -> jstring {
    guard_untimed(&mut env, std::ptr::null_mut(), |env| {
        let rx = events().lock().expect("events").get(&h).cloned().ok_or(Fail::Arg("events are not started"))?;
        let got = rx.lock().expect("receiver").recv_timeout(Duration::from_millis(timeout_ms.max(0) as u64));
        match got {
            Ok(e) => jstr(env, &event_json(&e).to_string()),
            Err(RecvTimeoutError::Timeout) => Ok(std::ptr::null_mut()),
            Err(RecvTimeoutError::Disconnected) => Err(Fail::Arg("the node is closed")),
        }
    })
}

/// A fresh face-to-face card (a new one-time key each time) as an `mlmchat://c/` link.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_cardLink(mut env: JNIEnv, _c: JClass, h: jlong) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let link = node(h)?.card_link()?;
        jstr(env, &link)
    })
}

#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_addContact(mut env: JNIEnv, _c: JClass, h: jlong, link: JString) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let link = string(env, &link)?;
        let c = node(h)?.add_contact(link.trim())?;
        jstr(env, &contact_json(&c).to_string())
    })
}

/// A member of one of our groups added as a contact («add contact» on their profile).
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_addMemberContact(mut env: JNIEnv, _c: JClass, h: jlong, peer: JString) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let peer = sk(env, &peer)?;
        let c = node(h)?.add_member_contact(&peer)?;
        jstr(env, &contact_json(&c).to_string())
    })
}

#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_contacts(mut env: JNIEnv, _c: JClass, h: jlong) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let list: Vec<Value> = node(h)?.contacts()?.iter().map(contact_json).collect();
        jstr(env, &Value::Array(list).to_string())
    })
}

/// Queues a text message; returns its id (hex). Its progress comes as "state" events.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_sendText(mut env: JNIEnv, _c: JClass, h: jlong, peer: JString, text: JString) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let peer = sk(env, &peer)?;
        let text = string(env, &text)?;
        let id = node(h)?.send_text(&peer, &text)?;
        jstr(env, &hex::encode(id))
    })
}

/// Queues a text message with its formatting and mentions (`entities`: a JSON array, see
/// [`entities_json`]), in answer to message `reply` of the chat (its seq; 0 for none).
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_sendTextRich(mut env: JNIEnv, _c: JClass, h: jlong, peer: JString, text: JString, entities: JString, reply: jlong) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let peer = sk(env, &peer)?;
        let text = string(env, &text)?;
        let entities = entities_arg(env, &entities)?;
        let id = node(h)?.send_text_rich(&peer, &text, entities, reply)?;
        jstr(env, &hex::encode(id))
    })
}

/// The newest `limit` messages with one contact, oldest first, as a JSON array.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_history(mut env: JNIEnv, _c: JClass, h: jlong, peer: JString, limit: jint) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let peer = sk(env, &peer)?;
        let n = node(h)?;
        let list: Vec<Value> = n.history(&peer, limit.max(0) as usize)?.iter().map(|m| message_json(m, |r| n.reply_seq(&peer, r).unwrap_or(0), n.edited_ms(&m.id).unwrap_or(0))).collect();
        jstr(env, &Value::Array(list).to_string())
    })
}

/// A page of the chat with one contact, oldest first, as a JSON array: with `after` below 0 the
/// newest `limit` before seq `before` (0: the newest of all), else the oldest `limit` after seq
/// `after` (0: from the first).
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_historyPage(mut env: JNIEnv, _c: JClass, h: jlong, peer: JString, before: jlong, after: jlong, limit: jint) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let peer = sk(env, &peer)?;
        let n = node(h)?;
        let list: Vec<Value> =
            n.history_page(&peer, before, after, limit.max(0) as usize)?.iter().map(|m| message_json(m, |r| n.reply_seq(&peer, r).unwrap_or(0), n.edited_ms(&m.id).unwrap_or(0))).collect();
        jstr(env, &Value::Array(list).to_string())
    })
}

/// One message of the chat with one contact, by its id (hex), or by its seq when `id` is empty;
/// JSON `null` when it is not there.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_message(mut env: JNIEnv, _c: JClass, h: jlong, peer: JString, id: JString, seq: jlong) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let peer = sk(env, &peer)?;
        let id = if string(env, &id)?.is_empty() { None } else { Some(msg_id(env, &id)?) };
        let n = node(h)?;
        let v = match n.message(&peer, id.as_ref(), seq)? {
            Some(m) => message_json(&m, |r| n.reply_seq(&peer, r).unwrap_or(0), n.edited_ms(&m.id).unwrap_or(0)),
            None => Value::Null,
        };
        jstr(env, &v.to_string())
    })
}

/// The one-to-one messages of every chat stored after seq `after`, oldest first, at most `limit`,
/// as a JSON array; each names its contact (`peer`).
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_messagesSince(mut env: JNIEnv, _c: JClass, h: jlong, after: jlong, limit: jint) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let n = node(h)?;
        let list: Vec<Value> =
            n.messages_since(after, limit.max(0) as usize)?.iter().map(|m| message_json(m, |r| n.reply_seq(&m.peer, r).unwrap_or(0), n.edited_ms(&m.id).unwrap_or(0))).collect();
        jstr(env, &Value::Array(list).to_string())
    })
}

/// What a chat list shows of the chat with one contact besides its last message:
/// `{"unread","readIn","readOut"}`.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_chatMarks(mut env: JNIEnv, _c: JClass, h: jlong, peer: JString) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let peer = sk(env, &peer)?;
        let m = node(h)?.chat_marks(&peer)?;
        jstr(env, &json!({"unread": m.unread, "readIn": m.read_in, "readOut": m.read_out}).to_string())
    })
}

#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_markRead(mut env: JNIEnv, _c: JClass, h: jlong, peer: JString) -> jint {
    guard(&mut env, 0, |env| {
        let peer = sk(env, &peer)?;
        Ok(node(h)?.mark_read(&peer)? as jint)
    })
}

/// What we are doing in the chat with `peer` right now (SPEC 5.4): `typing`, `upload_photo`,
/// `upload_file`, `record_voice` or `cancel`. True if it went; one that cannot go now (too soon,
/// no route open to their phone) is dropped, never queued.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_chatAction(mut env: JNIEnv, _c: JClass, h: jlong, peer: JString, kind: JString) -> jboolean {
    guard(&mut env, 0, |env| {
        let peer = sk(env, &peer)?;
        let kind = string(env, &kind)?;
        let kind = mlmchat_core::envelope::ChatActionKind::from_name(&kind).ok_or(Fail::Arg("chat action"))?;
        Ok(node(h)?.chat_action(&peer, kind)? as jboolean)
    })
}

/// The app came to the front (`online`; said again about every 55 s while it stays there) or left
/// it (SPEC 5.5): contacts with a route open to this phone hear it, unless our last seen is
/// hidden. Returns how many were told.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_setOnline(mut env: JNIEnv, _c: JClass, h: jlong, online: jboolean) -> jint {
    guard(&mut env, 0, |_| Ok(node(h)?.set_online(online != 0)? as jint))
}

/// Show our last seen (the default) or hide it, and with it everyone else's (SPEC 5.5).
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_setLastSeenVisible(mut env: JNIEnv, _c: JClass, h: jlong, visible: jboolean) {
    guard(&mut env, (), |_| {
        node(h)?.set_last_seen_visible(visible != 0)?;
        Ok(())
    })
}

#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_lastSeenVisible(mut env: JNIEnv, _c: JClass, h: jlong) -> jboolean {
    guard(&mut env, 1, |_| Ok(node(h)?.last_seen_visible() as jboolean))
}

#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_securityNumber(mut env: JNIEnv, _c: JClass, h: jlong, peer: JString) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let peer = sk(env, &peer)?;
        let n = node(h)?.security_number(&peer)?;
        jstr(env, &n)
    })
}

/// Blocking stays on this phone (SPEC 10): their frames are dropped and nothing is sent to them.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_setBlocked(mut env: JNIEnv, _c: JClass, h: jlong, peer: JString, blocked: jboolean) {
    guard(&mut env, (), |env| {
        let peer = sk(env, &peer)?;
        node(h)?.set_blocked(&peer, blocked != 0)?;
        Ok(())
    })
}

/// Deletes messages by seq on this phone only (the other phone keeps its copy); a message deleted
/// while still queued is not sent. Returns how many were deleted.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_deleteMessages(mut env: JNIEnv, _c: JClass, h: jlong, seqs: JLongArray) -> jint {
    guard(&mut env, 0, |env| {
        let mut buf = vec![0i64; env.get_array_length(&seqs)? as usize];
        env.get_long_array_region(&seqs, 0, &mut buf)?;
        Ok(node(h)?.delete_messages(&buf)? as jint)
    })
}

/// Every message on this phone goes, with the files only they carried; the account, contacts,
/// groups, channels and the official feeds stay (q3). Returns how many messages went.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_clearAllMessages(mut env: JNIEnv, _c: JClass, h: jlong) -> jint {
    guard(&mut env, 0, |_env| Ok(node(h)?.clear_all_messages()? as jint))
}

/// How many frames wait to go out, to every peer: deleting the account waits for its leaves.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_outboxLen(mut env: JNIEnv, _c: JClass, h: jlong) -> jint {
    guard(&mut env, 0, |_env| Ok(node(h)?.outbox_len()? as jint))
}

/// Deletes the conversation with `peer` up to `max_seq` (all of it for 0), on this phone only.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_clearHistory(mut env: JNIEnv, _c: JClass, h: jlong, peer: JString, max_seq: jlong) -> jint {
    guard(&mut env, 0, |env| {
        let peer = sk(env, &peer)?;
        Ok(node(h)?.clear_history(&peer, max_seq)? as jint)
    })
}

// ---- groups and channels (SPEC 7, phase 5) ----

fn gid(env: &mut JNIEnv, s: &JString) -> Result<Vec<u8>, Fail> {
    let s = string(env, s)?;
    match hex::decode(s) {
        Ok(b) if b.len() == mlmchat_core::group::GROUP_ID_LEN => Ok(b),
        _ => Err(Fail::Arg("group id")),
    }
}

/// The Gereh name a card gives (T14), empty for a card that does not read.
fn card_name(card: &[u8]) -> String {
    mlmchat_core::card::Card::from_bytes(card)
        .ok()
        .map(|c| mlmchat_core::identity::display_name(c.name.as_deref().unwrap_or("?"), &mlmchat_core::identity::personal_code(&c.sk, &c.ik)))
        .unwrap_or_default()
}

/// A group as the app shows it: name, kind, owner, our own rights, the members with the names
/// their cards give, and every ban standing now.
fn group_json(st: &mlmchat_core::group::GroupState, me: &UserId) -> Value {
    let now = mlmchat_core::util::now_ms();
    let members: Vec<Value> = st
        .members
        .iter()
        .map(|(sk, m)| {
            let name = card_name(&m.card);
            // a restriction standing now (SPEC 7.2 `Restrict`): the member rights left, and until when
            let (restricted, until) = match m.restricted {
                Some((rights, until)) if until > now => (rights as i64, until.min(i64::MAX as u64)),
                _ => (-1, 0),
            };
            json!({
                "sk": hex::encode(sk),
                "name": name,
                "owner": *sk == st.owner,
                "admin": st.is_admin(sk),
                "rights": st.admin_rights(sk),
                "title": m.admin.as_ref().map(|a| a.title.clone()).unwrap_or_default(),
                "joined": m.joined,
                "joinedMs": m.joined_ms.min(i64::MAX as u64),
                "restricted": restricted,
                "restrictedUntil": until,
            })
        })
        .collect();
    json!({
        "id": hex::encode(&st.group_id),
        "name": st.name,
        "about": st.about,
        "photo": st.photo.as_ref().and_then(|(p, _)| <[u8; 32]>::try_from(p.blob_id.as_slice()).ok()).map(|id| mlmchat_core::node::media_short_id(&id)).unwrap_or(0),
        // its tiny preview, shown until the photo itself is here (q16)
        "photoThumb": st.photo.as_ref().map(|(p, _)| hex::encode(&p.thumb)).unwrap_or_default(),
        "channel": st.broadcast,
        "owner": hex::encode(st.owner),
        "dissolved": st.dissolved,
        "member": st.is_member(me),
        "admin": st.is_admin(me),
        "adminRights": st.admin_rights(me),
        "canPost": st.can_post(me, now),
        "defaults": st.defaults,
        // this phone's own member rights now: the defaults, less a restriction on it
        "myRights": st.member_rights(me, now),
        // slow mode (q13, T73), in seconds; 0 for none
        "slowSeconds": st.slow_s,
        "members": members,
        // every ban standing now, in the shape of an official feed's (until in ms; a ban for good is
        // kept within a Java long), with the name of the card they had when banned, for the admins'
        // «removed users»
        "banned": st
            .banned
            .iter()
            .filter(|(_, until)| **until > now)
            .map(|(sk, until)| json!({"sk": hex::encode(sk), "until": (*until).min(i64::MAX as u64), "name": st.banned_cards.get(sk).map(|c| card_name(c)).unwrap_or_default()}))
            .collect::<Vec<_>>(),
    })
}

fn group_message_json(m: &mlmchat_core::store::GroupMessageRow, seq_of: impl Fn(&[u8]) -> i64, edited_ms: u64) -> Value {
    let env = Envelope::from_bytes(&m.envelope).ok();
    let media = env.as_ref().map(media_json).unwrap_or(Value::Null);
    let reply = reply_of(env.as_ref(), seq_of);
    let entities = message_entities(env.as_ref());
    let poll = env.as_ref().map(poll_json).unwrap_or(Value::Null);
    let text = env.and_then(|e| match e.body {
        Some(Body::Text(t)) => Some(t.body),
        _ => None,
    });
    json!({
        "media": media,
        "poll": poll,
        "id": hex::encode(m.id),
        "group": hex::encode(&m.gid),
        "seq": m.seq,
        "out": m.outgoing,
        "sender": hex::encode(m.sender),
        "sentMs": m.sent_ms,
        "atMs": m.at_ms,
        "kind": m.kind,
        "text": text,
        "state": m.state.name(),
        "replyTo": reply,
        "entities": entities,
        "editedMs": edited_ms,
    })
}

/// Creates a group, or a channel (`channel`: only the owner and admins post). Returns its id.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_groupCreate(mut env: JNIEnv, _c: JClass, h: jlong, name: JString, about: JString, channel: jboolean) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let name = string(env, &name)?;
        let about = string(env, &about)?;
        let g = node(h)?.group_create(&name, &about, channel != 0)?;
        jstr(env, &hex::encode(g))
    })
}

/// The groups and channels this phone is in, as a JSON array.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_groups(mut env: JNIEnv, _c: JClass, h: jlong) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let n = node(h)?;
        let me = n.me().sk;
        let list: Vec<Value> = n.groups()?.iter().map(|s| group_json(s, &me)).collect();
        jstr(env, &Value::Array(list).to_string())
    })
}

/// One group's state as JSON, or null if this phone does not have it.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_groupState(mut env: JNIEnv, _c: JClass, h: jlong, group: JString) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let g = gid(env, &group)?;
        let n = node(h)?;
        let me = n.me().sk;
        match n.group_state(&g)? {
            Some(s) => {
                let mut j = group_json(&s, &me);
                // when this phone may post again under slow mode (q13, T73); only here, not in the list
                j["slowNextMs"] = json!(if s.slow_s > 0 { n.group_slow_next(&g)? } else { 0 });
                jstr(env, &j.to_string())
            }
            None => Ok(std::ptr::null_mut()),
        }
    })
}

/// The group's messages up to `max_seq` were read here (SPEC 7.6): marked seen, and each author
/// told when read receipts are on and the group is small enough. Returns how many were marked.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_groupMarkRead(mut env: JNIEnv, _c: JClass, h: jlong, group: JString, max_seq: jlong) -> jint {
    guard(&mut env, 0, |env| {
        let g = gid(env, &group)?;
        Ok(node(h)?.group_mark_read(&g, max_seq)? as jint)
    })
}

/// Who read our group message `seq` and when, the first first: `[{"sk", "atMs"}]` (SPEC 7.6).
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_groupReads(mut env: JNIEnv, _c: JClass, h: jlong, seq: jlong) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let list: Vec<Value> = node(h)?.group_reads(seq)?.iter().map(|(sk, at)| json!({"sk": hex::encode(sk), "atMs": at})).collect();
        jstr(env, &Value::Array(list).to_string())
    })
}

/// Adds a contact to the group (needs the right to invite or add).
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_groupAdd(mut env: JNIEnv, _c: JClass, h: jlong, group: JString, peer: JString) {
    guard(&mut env, (), |env| {
        let g = gid(env, &group)?;
        let p = sk(env, &peer)?;
        node(h)?.group_add(&g, &p)?;
        Ok(())
    })
}

/// Removes a member (`ban`: for good); with our own key, leaves.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_groupRemove(mut env: JNIEnv, _c: JClass, h: jlong, group: JString, peer: JString, ban: jboolean) {
    guard(&mut env, (), |env| {
        let g = gid(env, &group)?;
        let p = sk(env, &peer)?;
        node(h)?.group_remove(&g, &p, ban != 0)?;
        Ok(())
    })
}

/// Lifts a ban without taking the member back (SPEC 7.2 `Unban`; an admin with the right to ban).
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_groupUnban(mut env: JNIEnv, _c: JClass, h: jlong, group: JString, peer: JString) {
    guard(&mut env, (), |env| {
        let g = gid(env, &group)?;
        let p = sk(env, &peer)?;
        node(h)?.group_unban(&g, &p)?;
        Ok(())
    })
}

#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_groupSetAdmin(mut env: JNIEnv, _c: JClass, h: jlong, group: JString, peer: JString, admin: jboolean, rights: jint, title: JString) {
    guard(&mut env, (), |env| {
        let g = gid(env, &group)?;
        let p = sk(env, &peer)?;
        let t = string(env, &title)?;
        node(h)?.group_set_admin(&g, &p, admin != 0, rights as u32, &t)?;
        Ok(())
    })
}

#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_groupSetInfo(mut env: JNIEnv, _c: JClass, h: jlong, group: JString, name: JString, about: JString) {
    guard(&mut env, (), |env| {
        let g = gid(env, &group)?;
        let n = string(env, &name)?;
        let a = string(env, &about)?;
        node(h)?.group_set_info(&g, &n, &a)?;
        Ok(())
    })
}

/// Posts a text in the group; returns its id (hex).
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_groupSendText(mut env: JNIEnv, _c: JClass, h: jlong, group: JString, text: JString) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let g = gid(env, &group)?;
        let t = string(env, &text)?;
        let id = node(h)?.group_send_text(&g, &t)?;
        jstr(env, &hex::encode(id))
    })
}

/// Posts a text in the group with its formatting and mentions (`entities`, as for
/// `sendTextRich`), in answer to its message `reply` (its seq; 0 for none).
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_groupSendTextRich(mut env: JNIEnv, _c: JClass, h: jlong, group: JString, text: JString, entities: JString, reply: jlong) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let g = gid(env, &group)?;
        let t = string(env, &text)?;
        let entities = entities_arg(env, &entities)?;
        let id = node(h)?.group_send_text_rich(&g, &t, entities, reply)?;
        jstr(env, &hex::encode(id))
    })
}

/// The newest `limit` messages of a group, oldest first, as a JSON array.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_groupMessages(mut env: JNIEnv, _c: JClass, h: jlong, group: JString, limit: jint) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let g = gid(env, &group)?;
        let n = node(h)?;
        let list: Vec<Value> = n.group_messages(&g, limit.max(0) as usize)?.iter().map(|m| group_message_json(m, |r| n.group_reply_seq(&g, r).unwrap_or(0), n.edited_ms(&m.id).unwrap_or(0))).collect();
        jstr(env, &Value::Array(list).to_string())
    })
}

/// A page of a group or channel, oldest first, as a JSON array (see `historyPage`).
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_groupMessagesPage(mut env: JNIEnv, _c: JClass, h: jlong, group: JString, before: jlong, after: jlong, limit: jint) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let g = gid(env, &group)?;
        let n = node(h)?;
        let list: Vec<Value> = n
            .group_messages_page(&g, before, after, limit.max(0) as usize)?
            .iter()
            .map(|m| group_message_json(m, |r| n.group_reply_seq(&g, r).unwrap_or(0), n.edited_ms(&m.id).unwrap_or(0)))
            .collect();
        jstr(env, &Value::Array(list).to_string())
    })
}

/// One message of a group or channel, by its id (hex), or by its seq when `id` is empty; JSON
/// `null` when it is not there (or was taken down).
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_groupMessage(mut env: JNIEnv, _c: JClass, h: jlong, group: JString, id: JString, seq: jlong) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let g = gid(env, &group)?;
        let id = if string(env, &id)?.is_empty() { None } else { Some(msg_id(env, &id)?) };
        let n = node(h)?;
        let v = match n.group_message(&g, id.as_ref(), seq)? {
            Some(m) => group_message_json(&m, |r| n.group_reply_seq(&g, r).unwrap_or(0), n.edited_ms(&m.id).unwrap_or(0)),
            None => Value::Null,
        };
        jstr(env, &v.to_string())
    })
}

/// The messages of every group and channel stored after seq `after`, oldest first, at most
/// `limit`, as a JSON array; each names its group (`group`).
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_groupMessagesSince(mut env: JNIEnv, _c: JClass, h: jlong, after: jlong, limit: jint) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let n = node(h)?;
        let list: Vec<Value> = n
            .group_messages_since(after, limit.max(0) as usize)?
            .iter()
            .map(|m| group_message_json(m, |r| n.group_reply_seq(&m.gid, r).unwrap_or(0), n.edited_ms(&m.id).unwrap_or(0)))
            .collect();
        jstr(env, &Value::Array(list).to_string())
    })
}

/// What a chat list shows of a group or channel besides its last message, `after` being the
/// last seq read here: `{"unread","readOut","total"}`.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_groupMarks(mut env: JNIEnv, _c: JClass, h: jlong, group: JString, after: jlong) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let g = gid(env, &group)?;
        let m = node(h)?.group_marks(&g, after)?;
        jstr(env, &json!({"unread": m.unread, "readOut": m.read_out, "total": m.total}).to_string())
    })
}

// ---- files (SPEC 12, 17; phase 4) ----

fn media_info(env: &mut JNIEnv, info: &JString) -> Result<mlmchat_core::node::MediaInfo, Fail> {
    let v: Value = serde_json::from_str(&string(env, info)?).map_err(|_| Fail::Arg("media info"))?;
    let s = |k: &str| v.get(k).and_then(Value::as_str).unwrap_or_default().to_string();
    let n = |k: &str| v.get(k).and_then(Value::as_u64).unwrap_or(0) as u32;
    let b = |k: &str| hex::decode(v.get(k).and_then(Value::as_str).unwrap_or_default()).unwrap_or_default();
    Ok(mlmchat_core::node::MediaInfo {
        kind: v.get("kind").and_then(Value::as_i64).unwrap_or(0) as i32,
        mime: s("mime"),
        name: s("name"),
        caption: s("caption"),
        caption_entities: entities_from(v.get("entities")),
        thumb: b("thumb"),
        width: n("w"),
        height: n("h"),
        duration_ms: n("durationMs"),
        waveform: b("waveform"),
        album_id: b("album"),
    })
}

fn blob_id(env: &mut JNIEnv, s: &JString) -> Result<[u8; 32], Fail> {
    let s = string(env, s)?;
    hex::decode(s).ok().and_then(|b| <[u8; 32]>::try_from(b.as_slice()).ok()).ok_or(Fail::Arg("file id"))
}

/// Sends a file to a contact: `info` is JSON (kind, mime, name, caption, entities of the caption,
/// thumb hex, w, h, durationMs, waveform hex). Returns the message id (hex).
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_sendMedia(mut env: JNIEnv, _c: JClass, h: jlong, peer: JString, path: JString, info: JString) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let peer = sk(env, &peer)?;
        let path = string(env, &path)?;
        let info = media_info(env, &info)?;
        let id = node(h)?.send_media(&peer, std::path::Path::new(&path), &info)?;
        jstr(env, &hex::encode(id))
    })
}

/// Sends a file to a contact in answer to message `reply` of the chat (its seq; 0 for none).
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_sendMediaReply(mut env: JNIEnv, _c: JClass, h: jlong, peer: JString, path: JString, info: JString, reply: jlong) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let peer = sk(env, &peer)?;
        let path = string(env, &path)?;
        let info = media_info(env, &info)?;
        let id = node(h)?.send_media_reply(&peer, std::path::Path::new(&path), &info, reply)?;
        jstr(env, &hex::encode(id))
    })
}

/// Posts a file in a group or channel. Returns the message id (hex).
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_groupSendMedia(mut env: JNIEnv, _c: JClass, h: jlong, group: JString, path: JString, info: JString) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let g = gid(env, &group)?;
        let path = string(env, &path)?;
        let info = media_info(env, &info)?;
        let id = node(h)?.group_send_media(&g, std::path::Path::new(&path), &info)?;
        jstr(env, &hex::encode(id))
    })
}

/// Posts a file in a group or channel in answer to its message `reply` (its seq; 0 for none).
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_groupSendMediaReply(mut env: JNIEnv, _c: JClass, h: jlong, group: JString, path: JString, info: JString, reply: jlong) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let g = gid(env, &group)?;
        let path = string(env, &path)?;
        let info = media_info(env, &info)?;
        let id = node(h)?.group_send_media_reply(&g, std::path::Path::new(&path), &info, reply)?;
        jstr(env, &hex::encode(id))
    })
}

/// Asks for a received file now (larger ones wait for this). True if asked.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_fetchMedia(mut env: JNIEnv, _c: JClass, h: jlong, blob: JString) -> jboolean {
    guard(&mut env, 0, |env| {
        let id = blob_id(env, &blob)?;
        Ok(node(h)?.fetch_media(&id)? as jboolean)
    })
}

/// The file an app short id names: `{"blob","have","total","complete"}`, or null.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_mediaByShort(mut env: JNIEnv, _c: JClass, h: jlong, short: jlong) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let n = node(h)?;
        let Some((id, _, _)) = n.media_by_short(short)? else { return Ok(std::ptr::null_mut()) };
        let (have, total, complete) = n.media_status(&id)?.unwrap_or((0, 0, false));
        jstr(env, &json!({"blob": hex::encode(id), "have": have, "total": total, "complete": complete}).to_string())
    })
}

/// Writes the plain file of a whole received or sent file to `out`; returns its size.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_openMediaShort(mut env: JNIEnv, _c: JClass, h: jlong, short: jlong, out: JString) -> jlong {
    guard(&mut env, -1, |env| {
        let out = string(env, &out)?;
        let n = node(h)?;
        let (id, key, _) = n.media_by_short(short)?.ok_or(Fail::Arg("unknown file"))?;
        Ok(n.open_media(&id, &key, std::path::Path::new(&out))? as jlong)
    })
}

// ---- profile, group and channel photos (SPEC 10, 7.2; step 6.2) ----

/// A photo for the app: `{"short","blob","thumb" hex,"complete"}` (the file may still be on its way).
fn photo_json(n: &Node, p: &mlmchat_core::node::Photo) -> Result<Value, Fail> {
    let complete = n.media_status(&p.blob_id)?.is_some_and(|(_, _, whole)| whole);
    Ok(json!({"short": p.short, "blob": hex::encode(p.blob_id), "thumb": hex::encode(&p.thumb), "complete": complete}))
}

fn photo_path(env: &mut JNIEnv, path: &JString) -> Result<Option<std::path::PathBuf>, Fail> {
    let p = string(env, path)?;
    Ok((!p.is_empty()).then(|| std::path::PathBuf::from(p)))
}

/// A contact's or group member's profile photo (ours under our own key), or null.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_photoOf(mut env: JNIEnv, _c: JClass, h: jlong, peer: JString) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let peer = sk(env, &peer)?;
        let n = node(h)?;
        match n.photo_of(&peer)? {
            Some(p) => jstr(env, &photo_json(n, &p)?.to_string()),
            None => Ok(std::ptr::null_mut()),
        }
    })
}

/// Our profile photo from an image file (`path` empty: removes it), with its tiny preview (hex);
/// every contact gets it. Returns the photo, or null when removed.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_setPhoto(mut env: JNIEnv, _c: JClass, h: jlong, path: JString, thumb: JString) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let path = photo_path(env, &path)?;
        let thumb = hex::decode(string(env, &thumb)?).unwrap_or_default();
        let n = node(h)?;
        match n.set_photo(path.as_deref(), &thumb)? {
            Some(p) => jstr(env, &photo_json(n, &p)?.to_string()),
            None => Ok(std::ptr::null_mut()),
        }
    })
}

/// A group's or channel's photo from an image file (`path` empty: removes it). Returns the group.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_groupSetPhoto(mut env: JNIEnv, _c: JClass, h: jlong, group: JString, path: JString, thumb: JString) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let g = gid(env, &group)?;
        let path = photo_path(env, &path)?;
        let thumb = hex::decode(string(env, &thumb)?).unwrap_or_default();
        let n = node(h)?;
        let st = n.group_set_photo(&g, path.as_deref(), &thumb)?;
        jstr(env, &group_json(&st, &n.me().sk).to_string())
    })
}

/// Members' default rights (core `group::member` bits; needs the right to ban). Returns the group.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_groupSetDefaults(mut env: JNIEnv, _c: JClass, h: jlong, group: JString, rights: jint) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let g = gid(env, &group)?;
        let n = node(h)?;
        let st = n.group_op(&g, mlmchat_core::group::Op::Defaults(mlmchat_core::group::SetDefaults { rights: rights as u32 }))?;
        jstr(env, &group_json(&st, &n.me().sk).to_string())
    })
}

/// One member's own rights (SPEC 7.2 `Restrict`; needs the right to ban): `rights` are the member
/// bits left to them, on top of the defaults, until `until_ms`; `until_ms` 0 lifts it. Not for the
/// owner or an admin. Returns the group.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_groupRestrict(mut env: JNIEnv, _c: JClass, h: jlong, group: JString, peer: JString, rights: jint, until_ms: jlong) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let g = gid(env, &group)?;
        let p = sk(env, &peer)?;
        let n = node(h)?;
        let restrict = mlmchat_core::group::Restrict { sk: p.to_vec(), rights: rights as u32, until_ms: until_ms.max(0) as u64 };
        let st = n.group_op(&g, mlmchat_core::group::Op::Restrict(restrict))?;
        jstr(env, &group_json(&st, &n.me().sk).to_string())
    })
}

/// Ends a group or channel for everyone (its owner only). Returns the group.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_groupDissolve(mut env: JNIEnv, _c: JClass, h: jlong, group: JString) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let g = gid(env, &group)?;
        let n = node(h)?;
        let st = n.group_op(&g, mlmchat_core::group::Op::Dissolve(mlmchat_core::group::Dissolve {}))?;
        jstr(env, &group_json(&st, &n.me().sk).to_string())
    })
}

// ---- invite links (SPEC 7.4; step 6.3) ----

/// A new invite link for a group or channel (`exp_ms` 0: never expires; `max_uses` 0: no limit).
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_groupInviteCreate(mut env: JNIEnv, _c: JClass, h: jlong, group: JString, need_approval: jboolean, exp_ms: jlong, max_uses: jint) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let g = gid(env, &group)?;
        let link = node(h)?.group_invite_create(&g, need_approval != 0, exp_ms.max(0) as u64, max_uses.max(0) as u32)?;
        jstr(env, &link)
    })
}

/// The group's valid invites: `[{"id","link"|null,"by","expMs","maxUses","uses","needApproval","createdMs"}]`.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_groupInvites(mut env: JNIEnv, _c: JClass, h: jlong, group: JString) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let g = gid(env, &group)?;
        let list: Vec<Value> = node(h)?
            .group_invites(&g)?
            .iter()
            .map(|v| {
                json!({
                    "id": hex::encode(&v.invite_id),
                    "link": v.link,
                    "by": hex::encode(v.by),
                    "expMs": v.exp_ms,
                    "maxUses": v.max_uses,
                    "uses": v.uses,
                    "needApproval": v.need_approval,
                    "createdMs": v.created_ms,
                })
            })
            .collect();
        jstr(env, &Value::Array(list).to_string())
    })
}

#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_groupInviteRevoke(mut env: JNIEnv, _c: JClass, h: jlong, group: JString, invite: JString) {
    guard(&mut env, (), |env| {
        let g = gid(env, &group)?;
        let id = hex::decode(string(env, &invite)?).map_err(|_| Fail::Arg("invite id"))?;
        node(h)?.group_invite_revoke(&g, &id)?;
        Ok(())
    })
}

/// What a link says: `{"group","invite","name","channel","needApproval","inviter","expMs","member","asked"}`.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_inviteInfo(mut env: JNIEnv, _c: JClass, h: jlong, link: JString) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let link = string(env, &link)?;
        let i = node(h)?.invite_info(&link)?;
        jstr(
            env,
            &json!({
                "group": hex::encode(&i.group_id),
                "invite": hex::encode(&i.invite_id),
                "name": i.name,
                "channel": i.broadcast,
                "needApproval": i.need_approval,
                "inviter": hex::encode(i.inviter),
                "expMs": i.exp_ms,
                "member": i.member,
                "asked": i.asked,
            })
            .to_string(),
        )
    })
}

/// Asks to join by a link (queued for the inviter's phone). Returns the group id.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_groupJoin(mut env: JNIEnv, _c: JClass, h: jlong, link: JString) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let link = string(env, &link)?;
        let g = node(h)?.group_join(&link)?;
        jstr(env, &hex::encode(g))
    })
}

/// Requests waiting for approval: `[{"sk","name","atMs"}]`.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_groupJoinRequests(mut env: JNIEnv, _c: JClass, h: jlong, group: JString) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let g = gid(env, &group)?;
        let list: Vec<Value> = node(h)?.group_join_requests(&g)?.iter().map(|r| json!({"sk": hex::encode(r.sk), "name": r.name, "atMs": r.at_ms})).collect();
        jstr(env, &Value::Array(list).to_string())
    })
}

#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_groupJoinDecide(mut env: JNIEnv, _c: JClass, h: jlong, group: JString, peer: JString, approve: jboolean) {
    guard(&mut env, (), |env| {
        let g = gid(env, &group)?;
        let p = sk(env, &peer)?;
        node(h)?.group_join_decide(&g, &p, approve != 0)?;
        Ok(())
    })
}

// ---- official feeds (T38; step 6.4) ----

fn feed_id(env: &mut JNIEnv, s: &JString) -> Result<Vec<u8>, Fail> {
    let s = string(env, s)?;
    match hex::decode(s) {
        Ok(b) if b.len() == mlmchat_core::feed::FEED_ID_LEN => Ok(b),
        _ => Err(Fail::Arg("feed id")),
    }
}

/// The feeds this app ships: `{"feeds":[{"id","broadcast","name","owner"}],"seed":[hex of SignedFeedItem]}`.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_setFeeds(mut env: JNIEnv, _c: JClass, h: jlong, config: JString) {
    guard(&mut env, (), |env| {
        let v: Value = serde_json::from_str(&string(env, &config)?).map_err(|_| Fail::Arg("feed config"))?;
        let mut feeds = Vec::new();
        for f in v.get("feeds").and_then(Value::as_array).cloned().unwrap_or_default() {
            let id = hex::decode(f.get("id").and_then(Value::as_str).unwrap_or_default()).map_err(|_| Fail::Arg("feed id"))?;
            let owner = hex::decode(f.get("owner").and_then(Value::as_str).unwrap_or_default()).ok().and_then(|b| <[u8; 32]>::try_from(b.as_slice()).ok()).ok_or(Fail::Arg("feed owner"))?;
            feeds.push(mlmchat_core::feed::FeedDescriptor {
                feed_id: id,
                broadcast: f.get("broadcast").and_then(Value::as_bool).unwrap_or(false),
                name: f.get("name").and_then(Value::as_str).unwrap_or_default().to_string(),
                owner,
            });
        }
        let mut seed = Vec::new();
        for s in v.get("seed").and_then(Value::as_array).cloned().unwrap_or_default() {
            let bytes = hex::decode(s.as_str().unwrap_or_default()).map_err(|_| Fail::Arg("feed seed"))?;
            seed.push(mlmchat_core::feed::SignedFeedItem::from_bytes(&bytes).map_err(|_| Fail::Arg("feed seed"))?);
        }
        let n = node(h)?;
        n.set_feeds(feeds, &seed)?;
        // the official feeds' copy in a Google relay (SPEC 18.2): a new phone reads posts and the
        // member count there before it has any contact
        n.set_feed_mirror(v.get("mirror").and_then(Value::as_str).filter(|l| !l.is_empty()))?;
        Ok(())
    })
}

/// What the app read of the phone itself (Android's `ANDROID_ID`; T74), at each start: official
/// posts carry its hash with the feed, so a ban holds the phone, not only the account.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_setDevice(mut env: JNIEnv, _c: JClass, h: jlong, device: JByteArray) {
    guard(&mut env, (), |env| {
        let d = env.convert_byte_array(&device)?;
        node(h)?.set_device(&d);
        Ok(())
    })
}

/// The app this phone runs in, put on everything it sends (SPEC 5 `Client`, T75).
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_setClient(mut env: JNIEnv, _c: JClass, h: jlong, app: JString, code: jint, name: JString) {
    guard(&mut env, (), |env| {
        let a = string(env, &app)?;
        let n = string(env, &name)?;
        node(h)?.set_client(&a, code.max(0) as u32, &n);
        Ok(())
    })
}

/// The apps people write from, changed at or after `since_ms` (T75):
/// `[{"sk","app","code","name","sentMs","atMs"}]`; an empty app is a phone from before it.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_clientsSince(mut env: JNIEnv, _c: JClass, h: jlong, since_ms: jlong) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let list: Vec<Value> = node(h)?
            .clients_since(since_ms.max(0) as u64)?
            .iter()
            .map(|c| json!({"sk": hex::encode(c.sk), "app": c.app, "code": c.code, "name": c.name, "sentMs": c.sent_ms, "atMs": c.at_ms}))
            .collect();
        jstr(env, &Value::Array(list).to_string())
    })
}

/// A feed as the app shows it: `{"id","broadcast","name","admin","canPost","admins":[hex]}`, or null.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_feedInfo(mut env: JNIEnv, _c: JClass, h: jlong, feed: JString) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let f = feed_id(env, &feed)?;
        let n = node(h)?;
        let Some(d) = n.feeds().into_iter().find(|d| d.feed_id == f) else { return Ok(std::ptr::null_mut()) };
        let Some(st) = n.feed_state(&f)? else { return Ok(std::ptr::null_mut()) };
        let me = n.me().sk;
        let now = mlmchat_core::util::now_ms();
        // this account's ban, or this phone's under any account (T74)
        let banned_until = n.feed_banned_until(&f)?;
        jstr(
            env,
            &json!({
                "id": hex::encode(&d.feed_id),
                "broadcast": d.broadcast,
                "name": d.name,
                "admin": st.is_admin(&me),
                "canPost": st.can_post(&d, &me, now) && banned_until == 0,
                // a ban on this phone (ms; 0: none), and every ban standing now, for the admins' list
                "bannedUntil": banned_until,
                "banned": st.banned.iter().filter(|(_, u)| **u > now).map(|(sk, u)| json!({"sk": hex::encode(sk), "until": u})).collect::<Vec<_>>(),
                "admins": st.admins.iter().map(hex::encode).collect::<Vec<_>>(),
                // the photo as the app knows files: its short id (0: none) and preview
                "photo": st.photo.as_ref().and_then(|(p, _)| <[u8; 32]>::try_from(p.blob_id.as_slice()).ok()).map_or(0, |id| mlmchat_core::node::media_short_id(&id)),
                "photoThumb": st.photo.as_ref().map(|(p, _)| hex::encode(&p.thumb)).unwrap_or_default(),
                // an admin's name and description; empty: the app's own
                "title": st.title.clone().unwrap_or_default(),
                "about": st.about.clone().unwrap_or_default(),
                // pinned posts' ids, the newest last (T65)
                "pinned": st.pinned.iter().map(hex::encode).collect::<Vec<_>>(),
                // slow mode (T67), and when this phone may post again (0: now)
                "slow": st.slow_s,
                "slowNext": if st.slow_s == 0 || st.is_admin(&me) { 0 } else {
                    st.posts.iter().filter(|p| p.author == me).map(|p| p.ts_ms + u64::from(st.slow_s) * 1000).max().filter(|t| *t > now).unwrap_or(0)
                },
            })
            .to_string(),
        )
    })
}

/// Pins a post of a feed for everyone, or takes its pin off (admins; T65).
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_feedPin(mut env: JNIEnv, _c: JClass, h: jlong, feed: JString, post: JString, unpin: jboolean) {
    guard(&mut env, (), |env| {
        let f = feed_id(env, &feed)?;
        let id = hex::decode(string(env, &post)?).map_err(|_| Fail::Arg("post id"))?;
        node(h)?.feed_pin(&f, &id, unpin != 0)?;
        Ok(())
    })
}

/// An admin with the right to ban sets a group's slow mode in seconds, 0 to end it (q13, T73).
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_groupSetSlow(mut env: JNIEnv, _c: JClass, h: jlong, group: JString, seconds: jint) {
    guard(&mut env, (), |env| {
        let g = gid(env, &group)?;
        node(h)?.group_set_slow(&g, seconds.max(0) as u32)?;
        Ok(())
    })
}

/// An admin sets the official group's slow mode in seconds, 0 to end it (T67).
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_feedSetSlow(mut env: JNIEnv, _c: JClass, h: jlong, feed: JString, seconds: jint) {
    guard(&mut env, (), |env| {
        let f = feed_id(env, &feed)?;
        node(h)?.feed_set_slow(&f, seconds.max(0) as u32)?;
        Ok(())
    })
}

/// Pins message `seq` of a one-to-one chat for both, or takes its pin off (SPEC 5 `Pin`, T65).
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_pin(mut env: JNIEnv, _c: JClass, h: jlong, peer: JString, seq: jlong, unpin: jboolean) {
    guard(&mut env, (), |env| {
        let p = sk(env, &peer)?;
        node(h)?.pin(&p, seq, unpin != 0)?;
        Ok(())
    })
}

/// The one-to-one chat's pinned messages: a JSON array of seqs, the newest last.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_pinned(mut env: JNIEnv, _c: JClass, h: jlong, peer: JString) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let p = sk(env, &peer)?;
        let seqs = node(h)?.pinned(&p)?;
        jstr(env, &json!(seqs).to_string())
    })
}

/// Pins message `seq` of a group for every member, or takes its pin off (SPEC 7.2, T65).
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_groupPin(mut env: JNIEnv, _c: JClass, h: jlong, group: JString, seq: jlong, unpin: jboolean) {
    guard(&mut env, (), |env| {
        let g = gid(env, &group)?;
        node(h)?.group_pin(&g, seq, unpin != 0)?;
        Ok(())
    })
}

/// The group's pinned messages on this phone: a JSON array of seqs, the newest last.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_groupPinned(mut env: JNIEnv, _c: JClass, h: jlong, group: JString) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let g = gid(env, &group)?;
        let seqs = node(h)?.group_pinned(&g)?;
        jstr(env, &json!(seqs).to_string())
    })
}

/// The posts that count in a feed, oldest first (the newest `limit`):
/// `[{"id","author","name","tsMs","out","kind","text","media"}]`.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_feedPosts(mut env: JNIEnv, _c: JClass, h: jlong, feed: JString, limit: jint) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let f = feed_id(env, &feed)?;
        let n = node(h)?;
        let me = n.me().sk;
        let posts = n.feed_state(&f)?.map(|s| s.posts).unwrap_or_default();
        let skip = posts.len().saturating_sub(limit.max(0) as usize);
        let list: Vec<Value> = posts
            .iter()
            .skip(skip)
            .map(|p| {
                let e = Envelope::from_bytes(&p.envelope).ok();
                let media = e.as_ref().map(media_json).unwrap_or(Value::Null);
                let kind = e.as_ref().map(|e| e.kind_name()).unwrap_or("none");
                // the post it answers (hex), and the text's formatting and mentions
                let reply = e.as_ref().filter(|e| !e.reply_to.is_empty()).map(|e| hex::encode(&e.reply_to));
                let entities = e.as_ref().map(|e| match &e.body {
                    Some(Body::Text(t)) => entities_json(&t.entities),
                    _ => Value::Array(Vec::new()),
                });
                let text = e.and_then(|e| match e.body {
                    Some(Body::Text(t)) => Some(t.body),
                    _ => None,
                });
                json!({
                    "replyTo": reply,
                    "entities": entities,
                    "id": hex::encode(&p.id),
                    "author": hex::encode(p.author),
                    "name": p.name,
                    "tsMs": p.ts_ms,
                    "out": p.author == me,
                    "kind": kind,
                    "text": text,
                    "media": media,
                    "reactions": p.reactions.iter().map(|(who, emoji)| json!({"peer": hex::encode(who), "emoji": emoji})).collect::<Vec<_>>(),
                    // its author's newest edit is already in text and media (0: never edited)
                    "editedMs": p.edited_ms,
                })
            })
            .collect();
        jstr(env, &Value::Array(list).to_string())
    })
}

/// New text for one of our own posts in a feed (its caption for a photo or file), with its
/// formatting (JSON entities); every phone shows the newest (T60).
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_feedEdit(mut env: JNIEnv, _c: JClass, h: jlong, feed: JString, post: JString, text: JString, entities: JString) {
    guard(&mut env, (), |env| {
        let f = feed_id(env, &feed)?;
        let id = hex::decode(string(env, &post)?).map_err(|_| Fail::Arg("post id"))?;
        let t = string(env, &text)?;
        let entities = entities_arg(env, &entities)?;
        node(h)?.feed_edit(&f, &id, &t, entities)?;
        Ok(())
    })
}

/// The feed's name and description (empty: the app's own). Admins only.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_feedSetMeta(mut env: JNIEnv, _c: JClass, h: jlong, feed: JString, title: JString, about: JString) {
    guard(&mut env, (), |env| {
        let f = feed_id(env, &feed)?;
        let t = string(env, &title)?;
        let a = string(env, &about)?;
        node(h)?.feed_set_meta(&f, &t, &a)?;
        Ok(())
    })
}

/// Our reaction to a post (`emoji` empty: take it back).
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_feedReact(mut env: JNIEnv, _c: JClass, h: jlong, feed: JString, post: JString, emoji: JString) {
    guard(&mut env, (), |env| {
        let f = feed_id(env, &feed)?;
        let id = hex::decode(string(env, &post)?).map_err(|_| Fail::Arg("post id"))?;
        let e = string(env, &emoji)?;
        node(h)?.feed_react(&f, &id, &e)?;
        Ok(())
    })
}

/// Posts a text in a feed; returns the post id (hex).
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_feedSendText(mut env: JNIEnv, _c: JClass, h: jlong, feed: JString, text: JString) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let f = feed_id(env, &feed)?;
        let t = string(env, &text)?;
        let id = node(h)?.feed_send_text(&f, &t)?;
        jstr(env, &hex::encode(id))
    })
}

/// Posts a text in a feed with its formatting (`entities` as for sendTextRich), answering post
/// `reply` (hex; empty for none); returns the post id (hex).
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_feedSendTextRich(mut env: JNIEnv, _c: JClass, h: jlong, feed: JString, text: JString, entities: JString, reply: JString) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let f = feed_id(env, &feed)?;
        let t = string(env, &text)?;
        let entities = entities_arg(env, &entities)?;
        let r = hex::decode(string(env, &reply)?).unwrap_or_default();
        let id = node(h)?.feed_send_text_rich(&f, &t, entities, &r)?;
        jstr(env, &hex::encode(id))
    })
}

/// Posts a file in a feed answering post `reply` (hex; empty for none); returns the post id (hex).
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_feedSendMediaReply(mut env: JNIEnv, _c: JClass, h: jlong, feed: JString, path: JString, info: JString, reply: JString) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let f = feed_id(env, &feed)?;
        let path = string(env, &path)?;
        let info = media_info(env, &info)?;
        let r = hex::decode(string(env, &reply)?).unwrap_or_default();
        let id = node(h)?.feed_send_media_reply(&f, std::path::Path::new(&path), &info, &r)?;
        jstr(env, &hex::encode(id))
    })
}

/// Posts a file in a feed (`info` as for sendMedia); returns the post id (hex).
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_feedSendMedia(mut env: JNIEnv, _c: JClass, h: jlong, feed: JString, path: JString, info: JString) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let f = feed_id(env, &feed)?;
        let path = string(env, &path)?;
        let info = media_info(env, &info)?;
        let id = node(h)?.feed_send_media(&f, std::path::Path::new(&path), &info)?;
        jstr(env, &hex::encode(id))
    })
}

/// The feed's photo from an image file (`path` empty: no photo), with its preview (hex). Admins only.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_feedSetPhoto(mut env: JNIEnv, _c: JClass, h: jlong, feed: JString, path: JString, thumb: JString) {
    guard(&mut env, (), |env| {
        let f = feed_id(env, &feed)?;
        let path = photo_path(env, &path)?;
        let thumb = hex::decode(string(env, &thumb)?).unwrap_or_default();
        node(h)?.feed_set_photo(&f, path.as_deref(), &thumb)?;
        Ok(())
    })
}

/// Takes a post down (an admin's, or the post's author's).
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_feedDelete(mut env: JNIEnv, _c: JClass, h: jlong, feed: JString, post: JString) {
    guard(&mut env, (), |env| {
        let f = feed_id(env, &feed)?;
        let id = hex::decode(string(env, &post)?).map_err(|_| Fail::Arg("post id"))?;
        node(h)?.feed_delete(&f, &id)?;
        Ok(())
    })
}

/// Bans someone from a feed until `until_ms` (0 lifts it). Admins only.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_feedBan(mut env: JNIEnv, _c: JClass, h: jlong, feed: JString, peer: JString, until_ms: jlong) {
    guard(&mut env, (), |env| {
        let f = feed_id(env, &feed)?;
        let p = sk(env, &peer)?;
        node(h)?.feed_ban(&f, &p, until_ms.max(0) as u64)?;
        Ok(())
    })
}

/// Keeps the official feeds' owner key on this phone, from its secret half (hex); refused unless
/// it owns a feed this app ships.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_feedOwnerImport(mut env: JNIEnv, _c: JClass, h: jlong, secret: JString) {
    guard(&mut env, (), |env| {
        let mut s = hex::decode(string(env, &secret)?.trim()).map_err(|_| Fail::Arg("owner key"))?;
        let r = node(h)?.feed_owner_import(&s);
        s.iter_mut().for_each(|b| *b = 0);
        r?;
        Ok(())
    })
}

/// Whether this phone holds the owner key of the feed.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_feedOwnerHeld(mut env: JNIEnv, _c: JClass, h: jlong, feed: JString) -> jboolean {
    guard(&mut env, 0, |env| {
        let f = feed_id(env, &feed)?;
        Ok(node(h)?.feed_owner_held(&f) as jboolean)
    })
}

/// Names the feed's admins (sk hex, comma-separated; the first shown as its creator), signed with
/// the owner key on this phone.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_feedSetAdmins(mut env: JNIEnv, _c: JClass, h: jlong, feed: JString, admins: JString) {
    guard(&mut env, (), |env| {
        let f = feed_id(env, &feed)?;
        let list = string(env, &admins)?;
        let mut sks: Vec<mlmchat_core::UserId> = Vec::new();
        for a in list.split(',').map(str::trim).filter(|a| !a.is_empty()) {
            sks.push(hex::decode(a).ok().and_then(|b| b.try_into().ok()).ok_or(Fail::Arg("admin key"))?);
        }
        node(h)?.feed_set_admins(&f, &sks)?;
        Ok(())
    })
}

/// Compare feeds with the peers reached lately, now.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_feedSyncNow(mut env: JNIEnv, _c: JClass, h: jlong) {
    guard(&mut env, (), |_| {
        node(h)?.feed_sync_now();
        Ok(())
    })
}

/// An official chat is on the screen: the official copy is read every few seconds while the app
/// keeps saying so (core `MIRROR_WATCH_MS`).
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_feedWatch(mut env: JNIEnv, _c: JClass, h: jlong) {
    guard(&mut env, (), |_| {
        node(h)?.feed_watch();
        Ok(())
    })
}

/// How many are in the official feeds: everyone active in the last two to four weeks, estimated
/// phone to phone (core `census.rs`).
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_feedMemberCount(mut env: JNIEnv, _c: JClass, h: jlong) -> jlong {
    guard(&mut env, 0, |_| Ok(node(h)?.feed_member_count()?.min(i64::MAX as u64) as jlong))
}

// ---- Google mailbox relays (SPEC 19) ----------------------------------------------------------
// The user's list of Apps Script mailboxes: made here, added from a friend's link, switched on and
// off, tested, and what each did. Data crosses as JSON. `test`, `serverStats` and `admin` call
// Google and block for seconds: call them off the main thread.

fn gas_error(e: mlmchat_core::Error) -> Fail {
    Fail::Core(e)
}

/// Starts the mailbox transport. `config` is JSON with any of `wait_ms`, `idle_soon_s`,
/// `idle_late_s`; `{}` takes the defaults.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_gasStart(mut env: JNIEnv, _c: JClass, h: jlong, config: JString) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let text = string(env, &config)?;
        let c: Value = serde_json::from_str(&text).unwrap_or(Value::Null);
        let mut cfg = mlmchat_core::transport::gas::GasConfig::default();
        if let Some(v) = c["wait_ms"].as_u64() {
            cfg.wait_ms = v;
        }
        if let Some(v) = c["idle_soon_s"].as_u64() {
            cfg.idle_soon = Duration::from_secs(v);
        }
        if let Some(v) = c["idle_late_s"].as_u64() {
            cfg.idle_late = Duration::from_secs(v);
        }
        node(h)?.start_gas(cfg).map_err(gas_error)?;
        jstr(env, "ok")
    })
}

#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_gasStop(mut env: JNIEnv, _c: JClass, h: jlong) {
    guard(&mut env, (), |_| {
        node(h)?.stop_gas();
        Ok(())
    })
}

/// Starts the Nostr transport (SPEC 20). `config` is JSON with any of `keep` (connections kept
/// for reading) and `relays` (the list, best first); `{}` takes the defaults.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_nostrStart(mut env: JNIEnv, _c: JClass, h: jlong, config: JString) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let text = string(env, &config)?;
        let c: Value = serde_json::from_str(&text).unwrap_or(Value::Null);
        let mut cfg = mlmchat_core::transport::nostr::transport::NostrConfig::default();
        if let Some(v) = c["keep"].as_u64() {
            cfg.keep = v.clamp(1, 8) as usize;
        }
        if let Some(a) = c["relays"].as_array() {
            let list: Vec<String> = a.iter().filter_map(Value::as_str).filter(|u| u.starts_with("wss://")).map(String::from).collect();
            if !list.is_empty() {
                cfg.relays = list;
            }
        }
        node(h)?.start_nostr(cfg).map_err(gas_error)?;
        jstr(env, "ok")
    })
}

#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_nostrStop(mut env: JNIEnv, _c: JClass, h: jlong) {
    guard(&mut env, (), |_| {
        node(h)?.stop_nostr();
        Ok(())
    })
}

/// The Nostr relays: JSON list of `{relay, state, way, reads, writes, events_in, events_out}`.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_nostrRelays(mut env: JNIEnv, _c: JClass, h: jlong) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let r = node(h)?.nostr_relays();
        jstr(env, &serde_json::to_string(&r).unwrap_or_default())
    })
}

/// The relay picker's list (SPEC 20.6): JSON list of `{relay, name, own, preferred, state,
/// reads, writes, ms, failed, way, measured_ms}`, in the order the relays are used.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_nostrChoices(mut env: JNIEnv, _c: JClass, h: jlong) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let r = node(h)?.nostr_choices();
        jstr(env, &serde_json::to_string(&r).unwrap_or_default())
    })
}

/// Measures every relay now (the user's tap) and gives the picker's list again. Blocks for seconds.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_nostrMeasure(mut env: JNIEnv, _c: JClass, h: jlong) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let r = node(h)?.nostr_measure();
        jstr(env, &serde_json::to_string(&r).unwrap_or_default())
    })
}

/// The relay the user prefers (its URL), kept for the next start; "" for automatic.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_nostrPrefer(mut env: JNIEnv, _c: JClass, h: jlong, url: JString) {
    guard(&mut env, (), |env| {
        let url = string(env, &url)?;
        node(h)?.set_nostr_preferred(Some(url.as_str())).map_err(gas_error)?;
        Ok(())
    })
}

/// The app is in use (true) or in the background.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_gasForeground(mut env: JNIEnv, _c: JClass, h: jlong, on: jboolean) {
    guard(&mut env, (), |_| {
        node(h)?.gas_foreground(on != 0);
        Ok(())
    })
}

/// The list with each relay's state and numbers, the way to Google found, and what is announced.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_gasOverview(mut env: JNIEnv, _c: JClass, h: jlong) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let o = node(h)?.gas_overview().map_err(gas_error)?;
        jstr(env, &serde_json::to_string(&o).unwrap_or_default())
    })
}

/// Makes a relay (a draft): JSON `{"rid", "script"}`.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_gasRelayNew(mut env: JNIEnv, _c: JClass, h: jlong, name: JString) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let n = string(env, &name)?;
        let d = node(h)?.gas_relay_new(&n).map_err(gas_error)?;
        jstr(env, &serde_json::to_string(&d).unwrap_or_default())
    })
}

#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_gasRelayScript(mut env: JNIEnv, _c: JClass, h: jlong, rid: JString) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let r = string(env, &rid)?;
        let s = node(h)?.gas_relay_script(&r).map_err(gas_error)?;
        jstr(env, &s)
    })
}

/// A combined relay (SPEC 19.13): keys and the Worker; JSON `{"rid", "worker_script"}`.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_gasComboNew(mut env: JNIEnv, _c: JClass, h: jlong, name: JString) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let n = string(env, &name)?;
        let d = node(h)?.gas_combo_new(&n).map_err(gas_error)?;
        jstr(env, &serde_json::to_string(&d).unwrap_or_default())
    })
}

#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_gasComboScript(mut env: JNIEnv, _c: JClass, h: jlong, rid: JString) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let r = string(env, &rid)?;
        let s = node(h)?.gas_combo_script(&r).map_err(gas_error)?;
        jstr(env, &s)
    })
}

/// The combined relay's Worker is at `host`: returns its Google door's script.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_gasComboWorker(mut env: JNIEnv, _c: JClass, h: jlong, rid: JString, host: JString) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let r = string(env, &rid)?;
        let w = string(env, &host)?;
        let s = node(h)?.gas_combo_worker(&r, &w).map_err(gas_error)?;
        jstr(env, &s)
    })
}

/// A draft's deployment (its id or the whole web-app address) was pasted: returns the new rid.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_gasRelayFinish(mut env: JNIEnv, _c: JClass, h: jlong, rid: JString, deployment: JString) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let r = string(env, &rid)?;
        let d = string(env, &deployment)?;
        let new = node(h)?.gas_relay_finish(&r, &d).map_err(gas_error)?;
        jstr(env, &new)
    })
}

/// What a share link holds, before adding it: JSON `{"name", "tail"}`, or an error.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_gasShareInfo(mut env: JNIEnv, _c: JClass, link: JString) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let l = string(env, &link)?;
        let s = mlmchat_core::transport::gas::proto::parse_share(&l).map_err(gas_error)?;
        let tail: String = s.relay.id.chars().rev().take(6).collect::<Vec<_>>().into_iter().rev().collect();
        jstr(env, &json!({ "name": s.name, "tail": tail, "rid": s.relay.rid() }).to_string())
    })
}

/// Adds a friend's relay from its share link: returns its rid (the same one if it was there).
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_gasRelayAddShare(mut env: JNIEnv, _c: JClass, h: jlong, link: JString) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let l = string(env, &link)?;
        let rid = node(h)?.gas_relay_add_share(&l).map_err(gas_error)?;
        jstr(env, &rid)
    })
}

/// The link to give a friend (never the owner's key).
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_gasRelayShare(mut env: JNIEnv, _c: JClass, h: jlong, rid: JString) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let r = string(env, &rid)?;
        let l = node(h)?.gas_relay_share(&r).map_err(gas_error)?;
        jstr(env, &l)
    })
}

/// Changes a relay. `enabled` and `announce`: -1 leave as is, 0 off, 1 on. `name` may be null.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_gasRelaySet(mut env: JNIEnv, _c: JClass, h: jlong, rid: JString, enabled: jint, announce: jint, name: JString) {
    guard(&mut env, (), |env| {
        let r = string(env, &rid)?;
        let n = if name.is_null() { None } else { Some(string(env, &name)?) };
        let tri = |v: jint| (v >= 0).then_some(v != 0);
        node(h)?.gas_relay_set(&r, tri(enabled), tri(announce), n.as_deref()).map_err(gas_error)?;
        Ok(())
    })
}

#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_gasRelayRemove(mut env: JNIEnv, _c: JClass, h: jlong, rid: JString) {
    guard(&mut env, (), |env| {
        let r = string(env, &rid)?;
        node(h)?.gas_relay_remove(&r).map_err(gas_error)?;
        Ok(())
    })
}

/// Tests a relay (blocking, up to ~30 s): JSON `{"steps": [{"step", "ok", "code"}], "front", ...}`.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_gasRelayTest(mut env: JNIEnv, _c: JClass, h: jlong, rid: JString) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let r = string(env, &rid)?;
        let rep = node(h)?.gas_relay_test(&r).map_err(gas_error)?;
        jstr(env, &serde_json::to_string(&rep).unwrap_or_default())
    })
}

/// The script's own statistics for a relay made here (blocking): JSON `{"at", "data"}`.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_gasRelayServerStats(mut env: JNIEnv, _c: JClass, h: jlong, rid: JString) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let r = string(env, &rid)?;
        let v = node(h)?.gas_relay_server_stats(&r).map_err(gas_error)?;
        jstr(env, &v.to_string())
    })
}

/// Opens a relay made here to everyone as a public relay, or closes it (blocking). Fails with
/// `script_old` when its script is older than version 2 (the code must be put in again).
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_gasRelaySetPublic(mut env: JNIEnv, _c: JClass, h: jlong, rid: JString, on: jboolean) {
    guard(&mut env, (), |env| {
        let r = string(env, &rid)?;
        node(h)?.gas_relay_set_public(&r, on != 0).map_err(gas_error)?;
        Ok(())
    })
}

/// The public relays this phone knows of, newest first (JSON array).
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_gasPublicList(mut env: JNIEnv, _c: JClass, h: jlong) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let list = node(h)?.gas_public_list()?;
        jstr(env, &serde_json::to_string(&list).unwrap_or_else(|_| "[]".into()))
    })
}

/// Whether public relays are borrowed while the user has no relay of their own.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_gasBorrowAuto(mut env: JNIEnv, _c: JClass, h: jlong) -> jboolean {
    guard(&mut env, 0, |_| Ok(node(h)?.gas_borrow_auto() as jboolean))
}

#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_gasSetBorrowAuto(mut env: JNIEnv, _c: JClass, h: jlong, on: jboolean) {
    guard(&mut env, (), |_| {
        node(h)?.gas_set_borrow_auto(on != 0)?;
        Ok(())
    })
}

/// Pauses a relay made here for everyone but its owner, or limits devices a day. `paused`: -1
/// leave, 0 resume, 1 pause. `max_clients`: -1 leave, 0 no limit (blocking).
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_gasRelayAdmin(mut env: JNIEnv, _c: JClass, h: jlong, rid: JString, paused: jint, max_clients: jint) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let r = string(env, &rid)?;
        let p = (paused >= 0).then_some(paused != 0);
        let m = (max_clients >= 0).then_some(max_clients as u32);
        let v = node(h)?.gas_relay_admin(&r, p, m).map_err(gas_error)?;
        jstr(env, &v.to_string())
    })
}

/// One call to the user's own Telegram reader script (T68), apart from the relays (blocking):
/// JSON `{"r", "up", "down"}`.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_gasScriptCall(mut env: JNIEnv, _c: JClass, h: jlong, deployment: JString, body: JString, timeout_s: jint) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let d = string(env, &deployment)?;
        let b = string(env, &body)?;
        let v = node(h)?.gas_script_call(&d, &b, timeout_s.max(0) as u64).map_err(gas_error)?;
        jstr(env, &v.to_string())
    })
}

/// A value the app keeps in the core's encrypted database, or null.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_appGet(mut env: JNIEnv, _c: JClass, h: jlong, key: JString) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let k = string(env, &key)?;
        match node(h)?.app_get(&k)? {
            Some(v) => jstr(env, &String::from_utf8_lossy(&v)),
            None => Ok(std::ptr::null_mut()),
        }
    })
}

/// Keeps a value for the app in the core's encrypted database; null or "" takes it away.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_appPut(mut env: JNIEnv, _c: JClass, h: jlong, key: JString, value: JString) {
    guard(&mut env, (), |env| {
        let k = string(env, &key)?;
        let v = if value.is_null() { String::new() } else { string(env, &value)? };
        node(h)?.app_put(&k, v.as_bytes())?;
        Ok(())
    })
}

// ---- the public list of channels and groups (q6, T70; node::directory) ----

/// The chats a search of the list finds, best first:
/// `[{"id","name","about","broadcast","members","link","createdMs","member"}]`.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_dirSearch(mut env: JNIEnv, _c: JClass, h: jlong, query: JString, limit: jint) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let q = string(env, &query)?;
        let list: Vec<Value> = node(h)?
            .dir_search(&q, limit.max(1) as usize)?
            .into_iter()
            .map(|r| json!({ "id": hex::encode(&r.group_id), "name": r.name, "about": r.about, "broadcast": r.broadcast, "members": r.members, "link": r.link, "createdMs": r.created_ms, "member": r.member, "photo": r.photo }))
            .collect();
        jstr(env, &Value::Array(list).to_string())
    })
}

/// Reads the list from our relays (blocks on the network): how many cards are kept.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_dirRefresh(mut env: JNIEnv, _c: JClass, h: jlong) -> jint {
    guard(&mut env, -1, |_| Ok(node(h)?.dir_refresh()? as jint))
}

/// Whether the cards kept here are older than an hour.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_dirStale(mut env: JNIEnv, _c: JClass, h: jlong) -> jboolean {
    guard(&mut env, 1, |_| Ok(node(h)?.dir_stale() as jboolean))
}

/// Whether this phone's chats' cards are due to be renewed.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_dirPublishDue(mut env: JNIEnv, _c: JClass, h: jlong) -> jboolean {
    guard(&mut env, 0, |_| Ok(node(h)?.dir_publish_due() as jboolean))
}

/// Renews the cards of every chat this phone owns (blocks on the network): how many relays took them.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_dirPublish(mut env: JNIEnv, _c: JClass, h: jlong) -> jint {
    guard(&mut env, -1, |_| Ok(node(h)?.dir_publish()? as jint))
}

/// Whether a chat of ours is in the public list.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_dirListed(mut env: JNIEnv, _c: JClass, h: jlong, group: JString) -> jboolean {
    guard(&mut env, 1, |env| {
        let g = gid(env, &group)?;
        Ok(node(h)?.dir_listed(&g)? as jboolean)
    })
}

/// The owner puts a chat in the public list or takes it out.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_dirSetListed(mut env: JNIEnv, _c: JClass, h: jlong, group: JString, listed: jboolean) {
    guard(&mut env, (), |env| {
        let g = gid(env, &group)?;
        node(h)?.dir_set_listed(&g, listed != 0)?;
        Ok(())
    })
}

/// An owned chat's small list photo (a JPEG; empty: none). Whether it changed.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_dirSetPhoto(mut env: JNIEnv, _c: JClass, h: jlong, group: JString, jpeg: JByteArray) -> jboolean {
    guard(&mut env, 0, |env| {
        let g = gid(env, &group)?;
        let j = env.convert_byte_array(&jpeg).map_err(|_| Fail::Arg("jpeg"))?;
        Ok(node(h)?.dir_set_photo(&g, (!j.is_empty()).then_some(j.as_slice()))? as jboolean)
    })
}

/// A listed chat's photo, if read already; null otherwise.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_dirPhoto(mut env: JNIEnv, _c: JClass, h: jlong, group: JString) -> jni::sys::jbyteArray {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let g = gid(env, &group)?;
        match node(h)?.dir_photo(&g)? {
            Some(j) => Ok(env.byte_array_from_slice(&j).map_err(|_| Fail::Arg("byte array"))?.into_raw()),
            None => Ok(std::ptr::null_mut()),
        }
    })
}

/// Reads the photos of these chats (a JSON array of hex ids) from our relays (blocks on the
/// network): how many came.
#[no_mangle]
pub extern "system" fn Java_org_mlmchat_core_NativeCore_dirFetchPhotos(mut env: JNIEnv, _c: JClass, h: jlong, groups: JString) -> jint {
    guard(&mut env, -1, |env| {
        let list: Vec<String> = serde_json::from_str(&string(env, &groups)?).map_err(|_| Fail::Arg("groups"))?;
        let gids: Vec<Vec<u8>> = list.iter().filter_map(|g| hex::decode(g).ok()).collect();
        Ok(node(h)?.dir_fetch_photos(&gids)? as jint)
    })
}
