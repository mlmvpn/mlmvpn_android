//! libnetprobe.so: the JNI surface of probe-core for com.mlmchat.netprobe.Native.
//! Every entry point catches panics: a bug here must end one test, never the app.

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jbyteArray, jlong, jstring};
use jni::JNIEnv;
use std::panic::catch_unwind;

fn to_jstring(env: &mut JNIEnv, s: String) -> jstring {
    env.new_string(s).map(|j| j.into_raw()).unwrap_or(std::ptr::null_mut())
}



#[no_mangle]
pub extern "system" fn Java_com_mlmchat_netprobe_Native_run<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>, input: JString<'l>) -> jstring {
    let s: String = match env.get_string(&input) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let out = catch_unwind(|| probe_core::run_command(&s))
        .unwrap_or_else(|_| r#"{"ok":false,"error":"internal error (panic)"}"#.to_string());
    to_jstring(&mut env, out)
}

#[no_mangle]
pub extern "system" fn Java_com_mlmchat_netprobe_Native_cancel<'l>(_env: JNIEnv<'l>, _c: JClass<'l>) {
    probe_core::cancel();
}

#[no_mangle]
pub extern "system" fn Java_com_mlmchat_netprobe_Native_pollLog<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>) -> jstring {
    let s = catch_unwind(probe_core::log::drain).unwrap_or_default();
    to_jstring(&mut env, s)
}

// ---- Bluetooth test: Noise_NKpsk0 sessions of probe_core::blenoise, by numeric handle ----

fn bytes_out(env: &mut JNIEnv, r: anyhow::Result<Vec<u8>>) -> jbyteArray {
    match r {
        Ok(v) => env.byte_array_from_slice(&v).map(|a| a.into_raw()).unwrap_or(std::ptr::null_mut()),
        Err(_) => std::ptr::null_mut(),
    }
}

/// GATT server side: responder with this session's Bluetooth key. 0 when there is no card yet.
#[no_mangle]
pub extern "system" fn Java_com_mlmchat_netprobe_Native_bleResponder<'l>(_env: JNIEnv<'l>, _c: JClass<'l>) -> jlong {
    catch_unwind(|| probe_core::blenoise::responder_for_session().map(|id| id as jlong).unwrap_or(0)).unwrap_or(0)
}

/// GATT client side: initiator toward the other phone's card (its `bk` and `bpsk`).
#[no_mangle]
pub extern "system" fn Java_com_mlmchat_netprobe_Native_bleInitiator<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>, card: JString<'l>) -> jlong {
    let c: String = match env.get_string(&card) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    catch_unwind(|| probe_core::blenoise::initiator_for_card(&c).map(|id| id as jlong).unwrap_or(0)).unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_mlmchat_netprobe_Native_bleWriteHs<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>, id: jlong, payload: JByteArray<'l>) -> jbyteArray {
    let p = env.convert_byte_array(&payload).unwrap_or_default();
    let r = catch_unwind(|| probe_core::blenoise::write_handshake(id as u64, &p)).unwrap_or_else(|_| Err(anyhow::anyhow!("panic")));
    bytes_out(&mut env, r)
}

#[no_mangle]
pub extern "system" fn Java_com_mlmchat_netprobe_Native_bleReadHs<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>, id: jlong, msg: JByteArray<'l>) -> jbyteArray {
    let m = env.convert_byte_array(&msg).unwrap_or_default();
    let r = catch_unwind(|| probe_core::blenoise::read_handshake(id as u64, &m)).unwrap_or_else(|_| Err(anyhow::anyhow!("panic")));
    bytes_out(&mut env, r)
}

#[no_mangle]
pub extern "system" fn Java_com_mlmchat_netprobe_Native_bleSeal<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>, id: jlong, nonce: jlong, pt: JByteArray<'l>) -> jbyteArray {
    let p = env.convert_byte_array(&pt).unwrap_or_default();
    let r = catch_unwind(|| probe_core::blenoise::seal(id as u64, nonce as u64, &p)).unwrap_or_else(|_| Err(anyhow::anyhow!("panic")));
    bytes_out(&mut env, r)
}

/// null when the packet is forged, damaged, replayed or old.
#[no_mangle]
pub extern "system" fn Java_com_mlmchat_netprobe_Native_bleOpen<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>, id: jlong, pkt: JByteArray<'l>) -> jbyteArray {
    let p = env.convert_byte_array(&pkt).unwrap_or_default();
    let r = catch_unwind(|| probe_core::blenoise::open(id as u64, &p)).unwrap_or_else(|_| Err(anyhow::anyhow!("panic")));
    bytes_out(&mut env, r)
}

#[no_mangle]
pub extern "system" fn Java_com_mlmchat_netprobe_Native_bleFree<'l>(_env: JNIEnv<'l>, _c: JClass<'l>, id: jlong) {
    let _ = catch_unwind(|| probe_core::blenoise::free(id as u64));
}
