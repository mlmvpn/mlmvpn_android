//! A small in-memory log the phone screen polls. Lines never carry an IP address, a key or a
//! card: callers pass addresses through `util::alias` first, and `log` scrubs whatever slips by.

use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{LazyLock, Mutex};

static LINES: LazyLock<Mutex<VecDeque<String>>> = LazyLock::new(|| Mutex::new(VecDeque::new()));
static ECHO: AtomicBool = AtomicBool::new(false);

/// Desktop tools print every line to stderr as well.
pub fn set_echo(on: bool) {
    ECHO.store(on, Ordering::Relaxed);
}

pub fn log(line: impl AsRef<str>) {
    let ms = crate::util::now_ms();
    let secs = (ms / 1000) % 86_400;
    let line = format!(
        "{:02}:{:02}:{:02} {}",
        secs / 3600,
        (secs / 60) % 60,
        secs % 60,
        crate::util::scrub(line.as_ref())
    );
    if ECHO.load(Ordering::Relaxed) {
        eprintln!("{line}");
    }
    let mut q = LINES.lock().unwrap();
    q.push_back(line);
    while q.len() > 2000 {
        q.pop_front();
    }
}

/// Everything logged since the last call, one line per entry.
pub fn drain() -> String {
    let mut q = LINES.lock().unwrap();
    let out: Vec<String> = q.drain(..).collect();
    out.join("\n")
}

#[macro_export]
macro_rules! plog {
    ($($t:tt)*) => { $crate::log::log(format!($($t)*)) };
}
