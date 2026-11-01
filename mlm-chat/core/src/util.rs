use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use std::time::{SystemTime, UNIX_EPOCH};

/// Bytes from the operating system's generator.
pub fn random<const N: usize>() -> [u8; N] {
    let mut b = [0u8; N];
    getrandom::fill(&mut b).expect("operating system random generator");
    b
}

pub fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0)
}

pub fn now_s() -> u64 {
    now_ms() / 1000
}

pub fn b64url_encode(bytes: &[u8]) -> String {
    URL_SAFE_NO_PAD.encode(bytes)
}

pub fn b64url_decode(text: &str) -> Option<Vec<u8>> {
    URL_SAFE_NO_PAD.decode(text.as_bytes()).ok()
}

pub fn array<const N: usize>(bytes: &[u8]) -> Option<[u8; N]> {
    bytes.try_into().ok()
}

/// A short, non-identifying label for log lines: the first 4 bytes of a random message id.
pub fn short_id(id: &[u8]) -> String {
    hex::encode(&id[..id.len().min(4)])
}

/// A wait for a lock, or a hold of it, this long or longer is logged with the place in the code
/// where the lock was taken (`TimedMutex`); the app's calls into the core too (JNI).
pub const SLOW_LOCK_MS: u128 = 50;

/// The file name and line of a place in the code, for a log line (no folders).
pub fn code_place(at: &std::panic::Location<'_>) -> String {
    format!("{}:{}", at.file().rsplit(['/', '\\']).next().unwrap_or(""), at.line())
}

/// A mutex that says where it is slow: a caller that waited `SLOW_LOCK_MS` or more for it, and a
/// holder that kept it that long, are logged with the source line that took it (no content). The
/// node's state sits behind one lock that the app's calls, the transports and the deliveries
/// share; this shows which of them keeps the others waiting (28 Oct 2026: on the A50 a new
/// message took 60 to 100 ms to reach the screen, reading only one row; the lines showed the
/// personal code worked out again and again, `identity::personal_code`).
pub struct TimedMutex<T> {
    inner: std::sync::Mutex<T>,
}

pub struct TimedGuard<'a, T> {
    guard: std::sync::MutexGuard<'a, T>,
    at: &'static std::panic::Location<'static>,
    since: std::time::Instant,
}

impl<T> TimedMutex<T> {
    pub fn new(value: T) -> Self {
        TimedMutex { inner: std::sync::Mutex::new(value) }
    }

    #[track_caller]
    pub fn lock(&self) -> std::sync::LockResult<TimedGuard<'_, T>> {
        let at = std::panic::Location::caller();
        let asked = std::time::Instant::now();
        let got = self.inner.lock();
        let waited = asked.elapsed().as_millis();
        if waited >= SLOW_LOCK_MS {
            log::info!("lock: waited {waited} ms at {}", code_place(at));
        }
        let since = std::time::Instant::now();
        match got {
            Ok(guard) => Ok(TimedGuard { guard, at, since }),
            Err(p) => Err(std::sync::PoisonError::new(TimedGuard { guard: p.into_inner(), at, since })),
        }
    }
}

impl<T> std::ops::Deref for TimedGuard<'_, T> {
    type Target = T;
    fn deref(&self) -> &T {
        &self.guard
    }
}

impl<T> std::ops::DerefMut for TimedGuard<'_, T> {
    fn deref_mut(&mut self) -> &mut T {
        &mut self.guard
    }
}

impl<T> Drop for TimedGuard<'_, T> {
    fn drop(&mut self) {
        let held = self.since.elapsed().as_millis();
        if held >= SLOW_LOCK_MS {
            log::info!("lock: held {held} ms at {}", code_place(self.at));
        }
    }
}
