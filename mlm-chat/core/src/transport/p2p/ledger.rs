//! A volunteer's relay ledger (SPEC 15.12): who is using it now and how much, for the person whose
//! phone it is. Bytes are counted on the wire (TCP, under TLS) for every connection; the ones that
//! came in are tied to the peer they turned out to be. A peer is shown only by a short name made
//! from a hash of its weekly network id: the volunteer never learns who anyone is (SPEC 15.1),
//! and nothing here goes into a log.

use futures::{AsyncRead, AsyncWrite};
use libp2p::swarm::ConnectionId;
use libp2p::PeerId;
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::pin::Pin;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::task::{Context, Poll};

/// Bytes of one connection.
#[derive(Debug, Default)]
pub struct ConnBytes {
    pub rx: AtomicU64,
    pub tx: AtomicU64,
}

/// Every byte this node moved, and the counters of inbound connections not yet tied to a peer.
#[derive(Debug, Default)]
pub struct Traffic {
    pub rx: AtomicU64,
    pub tx: AtomicU64,
    pending: Mutex<HashMap<String, Arc<ConnBytes>>>,
}

impl Traffic {
    /// A counter for an inbound connection from `remote` (its multiaddr as text).
    pub fn inbound(&self, remote: String) -> Arc<ConnBytes> {
        let c = Arc::new(ConnBytes::default());
        let mut p = self.pending.lock().expect("pending");
        // a handshake that never finished leaves its counter behind: keep the map small
        if p.len() > 256 {
            p.clear();
        }
        p.insert(remote, c.clone());
        c
    }

    /// The counter of the inbound connection from `remote`, once the swarm knows its peer.
    pub fn take(&self, remote: &str) -> Option<Arc<ConnBytes>> {
        self.pending.lock().expect("pending").remove(remote)
    }
}

/// A stream that counts what passes, into its connection's counter (if any) and the totals.
pub struct Counted<S> {
    inner: S,
    conn: Option<Arc<ConnBytes>>,
    all: Arc<Traffic>,
}

impl<S> Counted<S> {
    pub fn new(inner: S, conn: Option<Arc<ConnBytes>>, all: Arc<Traffic>) -> Self {
        Counted { inner, conn, all }
    }
}

impl<S: AsyncRead + Unpin> AsyncRead for Counted<S> {
    fn poll_read(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &mut [u8]) -> Poll<std::io::Result<usize>> {
        let r = Pin::new(&mut self.inner).poll_read(cx, buf);
        if let Poll::Ready(Ok(n)) = &r {
            let n = *n as u64;
            self.all.rx.fetch_add(n, Ordering::Relaxed);
            if let Some(c) = &self.conn {
                c.rx.fetch_add(n, Ordering::Relaxed);
            }
        }
        r
    }
}

impl<S: AsyncWrite + Unpin> AsyncWrite for Counted<S> {
    fn poll_write(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &[u8]) -> Poll<std::io::Result<usize>> {
        let r = Pin::new(&mut self.inner).poll_write(cx, buf);
        if let Poll::Ready(Ok(n)) = &r {
            let n = *n as u64;
            self.all.tx.fetch_add(n, Ordering::Relaxed);
            if let Some(c) = &self.conn {
                c.tx.fetch_add(n, Ordering::Relaxed);
            }
        }
        r
    }

    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<std::io::Result<()>> {
        Pin::new(&mut self.inner).poll_flush(cx)
    }

    fn poll_close(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<std::io::Result<()>> {
        Pin::new(&mut self.inner).poll_close(cx)
    }
}

/// One peer using the volunteer right now, as the app shows it.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize)]
pub struct RelayPeer {
    /// Six hex digits from a hash of its weekly network id: stable for the week, says nothing
    /// about who it is.
    pub name: String,
    pub since_ms: u64,
    pub rx: u64,
    pub tx: u64,
    /// Holds a reservation here: others reach it through this volunteer.
    pub reserved: bool,
    /// Relayed connections open through here, to it or from it.
    pub circuits: usize,
}

/// The volunteer's ledger, for the app (SPEC 15.12).
#[derive(Debug, Clone, Default, PartialEq, Eq, serde::Serialize)]
pub struct RelayStatus {
    pub volunteer: bool,
    /// Peers connected to it now.
    pub now: usize,
    /// Different peers that ever came, since the role was first turned on (kept on the device).
    pub served: u64,
    /// Bytes moved since the role was first turned on, and in this run.
    pub bytes_total: u64,
    pub rx_run: u64,
    pub tx_run: u64,
    pub peers: Vec<RelayPeer>,
}

#[derive(Default)]
struct Entry {
    since_ms: u64,
    conns: HashMap<ConnectionId, Arc<ConnBytes>>,
    /// Bytes of its connections that already closed.
    rx_closed: u64,
    tx_closed: u64,
    reserved: bool,
    circuits: usize,
}

#[derive(Default)]
pub struct Ledger {
    peers: HashMap<PeerId, Entry>,
    /// Kept totals (SPEC 15.12), as the device last gave them back.
    served_kept: u64,
    bytes_kept: u64,
    /// New peers in this run, and how many of them (and of the run's bytes) the kept totals hold.
    run_new: u64,
    run_new_kept: u64,
    bytes_run_kept: u64,
}

pub fn short_name(p: &PeerId) -> String {
    hex::encode(&Sha256::digest(p.to_bytes())[..3])
}

impl Ledger {
    /// An inbound connection from `peer`.
    pub fn connected(&mut self, peer: PeerId, id: ConnectionId, bytes: Option<Arc<ConnBytes>>, now_ms: u64) {
        if !self.peers.contains_key(&peer) {
            self.run_new += 1;
        }
        let e = self.peers.entry(peer).or_insert_with(|| Entry { since_ms: now_ms, ..Default::default() });
        if let Some(b) = bytes {
            e.conns.insert(id, b);
        }
    }

    /// What the kept totals do not hold yet: (new peers, bytes), given the run's bytes so far.
    pub fn unkept(&self, run_bytes: u64) -> (u64, u64) {
        (self.run_new - self.run_new_kept, run_bytes.saturating_sub(self.bytes_run_kept))
    }

    /// The device added `(served, bytes)` and now keeps these totals.
    pub fn kept(&mut self, added: (u64, u64), totals: (u64, u64)) {
        self.run_new_kept += added.0;
        self.bytes_run_kept += added.1;
        (self.served_kept, self.bytes_kept) = totals;
    }

    pub fn status(&self, volunteer: bool, rx_run: u64, tx_run: u64) -> RelayStatus {
        let peers = self.peers();
        let (new, bytes) = self.unkept(rx_run + tx_run);
        RelayStatus { volunteer, now: peers.len(), served: self.served_kept + new, bytes_total: self.bytes_kept + bytes, rx_run, tx_run, peers }
    }

    pub fn closed(&mut self, peer: &PeerId, id: ConnectionId, last: bool) {
        let Some(e) = self.peers.get_mut(peer) else { return };
        if let Some(b) = e.conns.remove(&id) {
            e.rx_closed += b.rx.load(Ordering::Relaxed);
            e.tx_closed += b.tx.load(Ordering::Relaxed);
        }
        if last {
            self.peers.remove(peer);
        }
    }

    pub fn reserved(&mut self, peer: &PeerId, on: bool) {
        if let Some(e) = self.peers.get_mut(peer) {
            e.reserved = on;
        }
    }

    pub fn circuit(&mut self, a: &PeerId, b: &PeerId, open: bool) {
        for p in [a, b] {
            if let Some(e) = self.peers.get_mut(p) {
                e.circuits = if open { e.circuits + 1 } else { e.circuits.saturating_sub(1) };
            }
        }
    }

    /// The peer behind a short name, to disconnect it.
    pub fn find(&self, name: &str) -> Option<PeerId> {
        self.peers.keys().find(|p| short_name(p) == name).copied()
    }

    pub fn peers(&self) -> Vec<RelayPeer> {
        let mut out: Vec<RelayPeer> = self
            .peers
            .iter()
            .map(|(p, e)| {
                let rx = e.rx_closed + e.conns.values().map(|b| b.rx.load(Ordering::Relaxed)).sum::<u64>();
                let tx = e.tx_closed + e.conns.values().map(|b| b.tx.load(Ordering::Relaxed)).sum::<u64>();
                RelayPeer { name: short_name(p), since_ms: e.since_ms, rx, tx, reserved: e.reserved, circuits: e.circuits }
            })
            .collect();
        // the busiest first
        out.sort_by_key(|r| std::cmp::Reverse(r.rx + r.tx));
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use futures::{AsyncReadExt, AsyncWriteExt};

    #[test]
    fn counts_both_ways_into_the_connection_and_the_totals() {
        let all = Arc::new(Traffic::default());
        let conn = all.inbound("/ip4/198.51.100.4/tcp/5555/tls".into());
        let mut s = Counted::new(futures::io::Cursor::new(b"world!".to_vec()), Some(conn.clone()), all.clone());
        futures::executor::block_on(async {
            let mut buf = [0u8; 6];
            s.read_exact(&mut buf).await.unwrap();
            s.write_all(b"hello").await.unwrap();
        });
        assert_eq!((conn.tx.load(Ordering::Relaxed), conn.rx.load(Ordering::Relaxed)), (5, 6));
        assert_eq!((all.tx.load(Ordering::Relaxed), all.rx.load(Ordering::Relaxed)), (5, 6));
        assert!(all.take("/ip4/198.51.100.4/tcp/5555/tls").is_some());
        assert!(all.take("/ip4/198.51.100.4/tcp/5555/tls").is_none());
    }

    #[test]
    fn a_peer_keeps_its_closed_bytes_until_its_last_connection_goes() {
        let mut l = Ledger::default();
        let p = PeerId::random();
        let b1 = Arc::new(ConnBytes::default());
        b1.rx.store(100, Ordering::Relaxed);
        l.connected(p, ConnectionId::new_unchecked(1), Some(b1), 7);
        let b2 = Arc::new(ConnBytes::default());
        b2.tx.store(40, Ordering::Relaxed);
        l.connected(p, ConnectionId::new_unchecked(2), Some(b2), 9);
        // one peer, however many connections
        assert_eq!(l.unkept(140), (1, 140));
        l.kept((1, 100), (5, 900));
        let st = l.status(true, 100, 40);
        assert_eq!((st.served, st.bytes_total, st.now), (5, 940, 1));
        l.reserved(&p, true);
        l.circuit(&p, &PeerId::random(), true);
        l.closed(&p, ConnectionId::new_unchecked(1), false);
        let v = l.peers();
        assert_eq!(v.len(), 1);
        assert_eq!((v[0].rx, v[0].tx, v[0].reserved, v[0].circuits, v[0].since_ms), (100, 40, true, 1, 7));
        assert_eq!(l.find(&v[0].name), Some(p));
        assert_eq!(v[0].name.len(), 6);
        l.closed(&p, ConnectionId::new_unchecked(2), true);
        assert!(l.peers().is_empty());
    }
}
