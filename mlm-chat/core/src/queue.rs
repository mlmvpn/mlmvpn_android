//! The outbox policy (SPEC 5 receipts, SPEC 0 honesty). A message is encrypted once, stored as a
//! padded frame, and offered again with growing pauses until the contact's device acknowledges
//! it. Until then it is "queued", with the last reason; nothing claims "sent" early.

use crate::store::OutRow;
use crate::UserId;
use std::sync::{Condvar, Mutex};
use std::time::Duration;

/// Pause before the next try, by the number of tries so far (seconds).
pub const BACKOFF_S: [u64; 7] = [2, 5, 15, 30, 60, 120, 300];

pub fn next_try_ms(attempts: u32, now_ms: u64) -> u64 {
    let i = (attempts as usize).min(BACKOFF_S.len() - 1);
    now_ms + BACKOFF_S[i] * 1000
}

/// Due frames grouped by contact, each group in the order it was made.
pub fn group_by_peer(rows: Vec<OutRow>) -> Vec<(UserId, Vec<OutRow>)> {
    let mut out: Vec<(UserId, Vec<OutRow>)> = Vec::new();
    for r in rows {
        match out.iter_mut().find(|(p, _)| *p == r.peer) {
            Some((_, v)) => v.push(r),
            None => out.push((r.peer, vec![r])),
        }
    }
    out
}

/// Wakes the delivery loop early (a new frame, a contact seen on the LAN).
#[derive(Default)]
pub struct Waker {
    flag: Mutex<bool>,
    cv: Condvar,
}

impl Waker {
    pub fn wake(&self) {
        *self.flag.lock().expect("waker") = true;
        self.cv.notify_all();
    }

    /// Wait until woken or `timeout` passes.
    pub fn wait(&self, timeout: Duration) {
        let guard = self.flag.lock().expect("waker");
        let (mut guard, _) = self.cv.wait_timeout_while(guard, timeout, |woken| !*woken).expect("waker");
        *guard = false;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn backoff_grows_and_caps() {
        assert_eq!(next_try_ms(0, 1000), 3000);
        assert_eq!(next_try_ms(3, 0), 30_000);
        assert_eq!(next_try_ms(99, 0), 300_000);
    }

    #[test]
    fn groups_keep_order() {
        let row = |peer: u8, id: u8| OutRow { frame_id: [id; 16], peer: [peer; 32], msg_id: None, frame: vec![], attempts: 0, next_ms: 0, last_error: None };
        let g = group_by_peer(vec![row(1, 1), row(2, 2), row(1, 3)]);
        assert_eq!(g.len(), 2);
        assert_eq!(g[0].1.iter().map(|r| r.frame_id[0]).collect::<Vec<_>>(), vec![1, 3]);
    }
}
