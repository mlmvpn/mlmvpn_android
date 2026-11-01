//! The Google mailbox relays in the database (SPEC 19): the user's list, how much each was used
//! (a row per relay per day, kept 60 days), and the frames put in a mailbox that were not seen
//! taken yet.

use super::{arr, opt_arr, Db};
use crate::transport::gas::{Pending, StatDelta};
use crate::Result;
use rusqlite::{params, OptionalExtension};

/// A public relay's record as kept (SPEC 19.12).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PublicRow {
    pub relay: Vec<u8>,
    pub owner: crate::UserId,
    pub body: Vec<u8>,
    pub sig: Vec<u8>,
    pub ts_ms: u64,
    pub until_ms: u64,
    pub withdrawn: bool,
    pub avoid_until: u64,
}

/// One line of the user's list. A draft has no deployment id yet (`id` empty): the keys are made
/// and the script is shown, the user has not pasted the deployment back.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GasRelayRow {
    pub rid: String,
    pub id: String,
    pub use_key: [u8; 16],
    pub owner_key: Option<[u8; 16]>,
    pub name: String,
    pub added_ms: u64,
    pub enabled: bool,
    /// Named in our card, so contacts leave frames for us there.
    pub announce: bool,
    pub pos: i64,
    /// Made here, with our own script (we hold the owner key).
    pub mine: bool,
    /// A combined relay (SPEC 19.13): its Worker's host, empty while the Worker is not made yet;
    /// `None` for a simple relay. Kept beside the row (`gas_workers`, by the key of use, which a
    /// relay never changes), so the list's table stays as older versions made it.
    pub worker: Option<String>,
}

impl GasRelayRow {
    /// The relay as cards, links and the transport name it; a combined relay's Worker only once
    /// it is made.
    pub fn relay_ref(&self) -> crate::transport::gas::proto::RelayRef {
        crate::transport::gas::proto::RelayRef { id: self.id.clone(), key: self.use_key, worker: self.worker.clone().filter(|w| !w.is_empty()) }
    }
}

const RELAY_COLS: &str = "r.rid, r.id, r.use_key, r.owner_key, r.name, r.added_ms, r.enabled, r.announce, r.pos, r.mine, w.worker";
const RELAY_FROM: &str = "gas_relays r LEFT JOIN gas_workers w ON w.use_key = r.use_key";

fn relay_row(r: &rusqlite::Row) -> rusqlite::Result<GasRelayRow> {
    Ok(GasRelayRow {
        rid: r.get(0)?,
        id: r.get(1)?,
        use_key: arr::<16>(r, 2)?,
        owner_key: opt_arr::<16>(r, 3)?,
        name: r.get(4)?,
        added_ms: r.get::<_, i64>(5)? as u64,
        enabled: r.get::<_, i64>(6)? != 0,
        announce: r.get::<_, i64>(7)? != 0,
        pos: r.get(8)?,
        mine: r.get::<_, i64>(9)? != 0,
        worker: r.get(10)?,
    })
}

impl Db<'_> {
    pub fn gas_relays(&self) -> Result<Vec<GasRelayRow>> {
        let mut st = self.0.prepare(&format!("SELECT {RELAY_COLS} FROM {RELAY_FROM} ORDER BY r.pos, r.added_ms"))?;
        let rows = st.query_map([], relay_row)?;
        Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
    }

    pub fn gas_relay(&self, rid: &str) -> Result<Option<GasRelayRow>> {
        Ok(self.0.query_row(&format!("SELECT {RELAY_COLS} FROM {RELAY_FROM} WHERE r.rid = ?1"), [rid], relay_row).optional()?)
    }

    pub fn gas_relay_by_id(&self, id: &str) -> Result<Option<GasRelayRow>> {
        Ok(self.0.query_row(&format!("SELECT {RELAY_COLS} FROM {RELAY_FROM} WHERE r.id = ?1 AND r.id != ''"), [id], relay_row).optional()?)
    }

    pub fn gas_relay_save(&self, r: &GasRelayRow) -> Result<()> {
        self.0.execute(
            "INSERT INTO gas_relays (rid, id, use_key, owner_key, name, added_ms, enabled, announce, pos, mine) VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10)
             ON CONFLICT(rid) DO UPDATE SET id = excluded.id, use_key = excluded.use_key, owner_key = excluded.owner_key, name = excluded.name,
               enabled = excluded.enabled, announce = excluded.announce, pos = excluded.pos, mine = excluded.mine",
            params![r.rid, r.id, &r.use_key[..], r.owner_key.as_ref().map(|k| &k[..]), r.name, r.added_ms as i64, r.enabled as i64, r.announce as i64, r.pos, r.mine as i64],
        )?;
        match &r.worker {
            Some(w) => self.0.execute(
                "INSERT INTO gas_workers (use_key, worker) VALUES (?1, ?2) ON CONFLICT(use_key) DO UPDATE SET worker = excluded.worker",
                params![&r.use_key[..], w],
            )?,
            None => self.0.execute("DELETE FROM gas_workers WHERE use_key = ?1", [&r.use_key[..]])?,
        };
        Ok(())
    }

    /// A draft became a relay: its row takes the name derived from the deployment id, and the
    /// numbers it already has (none yet) stay with it.
    pub fn gas_relay_rename(&self, old: &str, new: &str, id: &str) -> Result<()> {
        self.0.execute("UPDATE gas_relays SET rid = ?2, id = ?3 WHERE rid = ?1", params![old, new, id])?;
        Ok(())
    }

    pub fn gas_relay_delete(&self, rid: &str) -> Result<()> {
        self.0.execute("DELETE FROM gas_workers WHERE use_key IN (SELECT use_key FROM gas_relays WHERE rid = ?1)", [rid])?;
        self.0.execute("DELETE FROM gas_relays WHERE rid = ?1", [rid])?;
        self.0.execute("DELETE FROM gas_stats WHERE rid = ?1", [rid])?;
        Ok(())
    }

    pub fn gas_stats_add(&self, rid: &str, day: u64, d: &StatDelta) -> Result<()> {
        self.0.execute(
            "INSERT INTO gas_stats (rid, day, req, puts, takes, items_out, items_in, bytes_out, bytes_in, errors, ms_sum, ms_n) VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12)
             ON CONFLICT(rid, day) DO UPDATE SET req = req + excluded.req, puts = puts + excluded.puts, takes = takes + excluded.takes,
               items_out = items_out + excluded.items_out, items_in = items_in + excluded.items_in, bytes_out = bytes_out + excluded.bytes_out,
               bytes_in = bytes_in + excluded.bytes_in, errors = errors + excluded.errors, ms_sum = ms_sum + excluded.ms_sum, ms_n = ms_n + excluded.ms_n",
            params![rid, day as i64, d.req as i64, d.puts as i64, d.takes as i64, d.items_out as i64, d.items_in as i64, d.bytes_out as i64, d.bytes_in as i64, d.errors as i64, d.ms_sum as i64, d.ms_n as i64],
        )?;
        Ok(())
    }

    /// One relay's rows since `since_day`, oldest first.
    pub fn gas_stats(&self, rid: &str, since_day: u64) -> Result<Vec<(u64, StatDelta)>> {
        let mut st = self.0.prepare("SELECT day, req, puts, takes, items_out, items_in, bytes_out, bytes_in, errors, ms_sum, ms_n FROM gas_stats WHERE rid = ?1 AND day >= ?2 ORDER BY day")?;
        let rows = st.query_map(params![rid, since_day as i64], |r| {
            let g = |i: usize| r.get::<_, i64>(i).map(|v| v as u64);
            Ok((g(0)?, StatDelta { req: g(1)?, puts: g(2)?, takes: g(3)?, items_out: g(4)?, items_in: g(5)?, bytes_out: g(6)?, bytes_in: g(7)?, errors: g(8)?, ms_sum: g(9)?, ms_n: g(10)? }))
        })?;
        Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
    }

    pub fn gas_prune(&self, before_day: u64) -> Result<()> {
        self.0.execute("DELETE FROM gas_stats WHERE day < ?1", [before_day as i64])?;
        Ok(())
    }

    /// A frame from this contact came out of a mailbox of ours (`inbound`), or one of ours went
    /// into theirs.
    pub fn gas_peer_seen(&self, peer: &[u8; 32], inbound: bool, now_ms: u64) -> Result<()> {
        self.0.execute("INSERT OR IGNORE INTO gas_peers (peer) VALUES (?1)", [&peer[..]])?;
        let sql = if inbound { "UPDATE gas_peers SET in_ms = ?2 WHERE peer = ?1" } else { "UPDATE gas_peers SET out_ms = ?2 WHERE peer = ?1" };
        self.0.execute(sql, params![&peer[..], now_ms as i64])?;
        Ok(())
    }

    /// Contacts a frame came from through one of our mailboxes since `since_ms` (they are there,
    /// taking and sending, even if nothing faster reaches them).
    pub fn gas_peers_since(&self, since_ms: u64) -> Result<Vec<crate::UserId>> {
        let mut st = self.0.prepare("SELECT peer FROM gas_peers WHERE in_ms >= ?1")?;
        let rows = st.query_map([since_ms as i64], |r| arr::<32>(r, 0))?;
        Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
    }

    /// When a frame last came from them, and last went to them, through a mailbox.
    pub fn gas_peer(&self, peer: &[u8; 32]) -> Result<(u64, u64)> {
        Ok(self
            .0
            .query_row("SELECT in_ms, out_ms FROM gas_peers WHERE peer = ?1", [&peer[..]], |r| Ok((r.get::<_, i64>(0)? as u64, r.get::<_, i64>(1)? as u64)))
            .optional()?
            .unwrap_or((0, 0)))
    }

    pub fn gas_pending_add(&self, p: &Pending) -> Result<()> {
        self.0.execute(
            "INSERT OR REPLACE INTO gas_pending (id, peer, loc, hk, relay, frame, first_ms, put_ms, next_ms, tries, day) VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11)",
            params![&p.id[..], &p.peer[..], &p.loc[..], &p.hk[..], p.relay, p.frame, p.first_ms as i64, p.put_ms as i64, p.next_ms as i64, p.tries, p.day as i64],
        )?;
        Ok(())
    }

    pub fn gas_pending_due(&self, now_ms: u64, limit: usize) -> Result<Vec<Pending>> {
        let mut st = self.0.prepare("SELECT id, peer, loc, hk, relay, frame, first_ms, put_ms, next_ms, tries, day FROM gas_pending WHERE next_ms <= ?1 ORDER BY next_ms LIMIT ?2")?;
        let rows = st.query_map(params![now_ms.min(i64::MAX as u64) as i64, limit as i64], |r| {
            Ok(Pending {
                id: arr::<16>(r, 0)?,
                peer: arr::<32>(r, 1)?,
                loc: arr::<32>(r, 2)?,
                hk: arr::<32>(r, 3)?,
                relay: r.get(4)?,
                frame: r.get(5)?,
                first_ms: r.get::<_, i64>(6)? as u64,
                put_ms: r.get::<_, i64>(7)? as u64,
                next_ms: r.get::<_, i64>(8)? as u64,
                tries: r.get(9)?,
                day: r.get::<_, i64>(10)? as u64,
            })
        })?;
        Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
    }

    pub fn gas_pending_update(&self, id: &[u8; 16], next_ms: u64, tries: u32) -> Result<()> {
        self.0.execute("UPDATE gas_pending SET next_ms = ?2, tries = ?3 WHERE id = ?1", params![&id[..], next_ms as i64, tries])?;
        Ok(())
    }

    pub fn gas_pending_done(&self, id: &[u8; 16]) -> Result<()> {
        self.0.execute("DELETE FROM gas_pending WHERE id = ?1", [&id[..]])?;
        Ok(())
    }

    pub fn gas_pending_count(&self) -> Result<usize> {
        Ok(self.0.query_row("SELECT count(*) FROM gas_pending", [], |r| r.get::<_, i64>(0))? as usize)
    }

    /// How many frames wait in mailboxes for each peer (for the screens).
    pub fn gas_pending_by_relay(&self) -> Result<Vec<(Vec<u8>, usize)>> {
        let mut st = self.0.prepare("SELECT relay, count(*) FROM gas_pending GROUP BY relay")?;
        let rows = st.query_map([], |r| Ok((r.get::<_, Vec<u8>>(0)?, r.get::<_, i64>(1)? as usize)))?;
        Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
    }

    // ---- public relays (SPEC 19.12) ----

    /// Keep a public relay's record (its signature already checked) if it is newer than the one
    /// kept for that relay. True if it changed what is kept.
    #[allow(clippy::too_many_arguments)]
    pub fn gas_public_save(&self, relay: &[u8], owner: &crate::UserId, body: &[u8], sig: &[u8], ts_ms: u64, until_ms: u64, withdrawn: bool) -> Result<bool> {
        let n = self.0.execute(
            "INSERT INTO gas_public (relay, owner, body, sig, ts_ms, until_ms, withdrawn) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)
             ON CONFLICT(relay) DO UPDATE SET owner = excluded.owner, body = excluded.body, sig = excluded.sig, ts_ms = excluded.ts_ms,
               until_ms = excluded.until_ms, withdrawn = excluded.withdrawn WHERE excluded.ts_ms > gas_public.ts_ms",
            params![relay, &owner[..], body, sig, ts_ms as i64, until_ms as i64, withdrawn],
        )?;
        Ok(n > 0)
    }

    /// Every public relay record kept, newest first.
    pub fn gas_public_all(&self) -> Result<Vec<PublicRow>> {
        let mut st = self.0.prepare("SELECT relay, owner, body, sig, ts_ms, until_ms, withdrawn, avoid_until FROM gas_public ORDER BY ts_ms DESC")?;
        let rows = st.query_map([], |r| {
            Ok(PublicRow {
                relay: r.get(0)?,
                owner: arr::<32>(r, 1)?,
                body: r.get(2)?,
                sig: r.get(3)?,
                ts_ms: r.get::<_, i64>(4)? as u64,
                until_ms: r.get::<_, i64>(5)? as u64,
                withdrawn: r.get::<_, i64>(6)? != 0,
                avoid_until: r.get::<_, i64>(7)? as u64,
            })
        })?;
        Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
    }

    /// Do not borrow this relay before then (it failed, or said it is not public).
    pub fn gas_public_avoid(&self, relay: &[u8], until_ms: u64) -> Result<()> {
        self.0.execute("UPDATE gas_public SET avoid_until = ?2 WHERE relay = ?1", params![relay, until_ms as i64])?;
        Ok(())
    }

    /// Forget records a day past their end, and all but the newest `keep`.
    pub fn gas_public_prune(&self, now_ms: u64, keep: usize) -> Result<()> {
        self.0.execute("DELETE FROM gas_public WHERE until_ms < ?1", [now_ms.saturating_sub(crate::transport::gas::proto::DAY_MS) as i64])?;
        self.0.execute("DELETE FROM gas_public WHERE relay NOT IN (SELECT relay FROM gas_public ORDER BY ts_ms DESC LIMIT ?1)", [keep as i64])?;
        Ok(())
    }

    /// A public relay this phone borrowed (it is in the list as a relay of someone else's).
    pub fn gas_borrowed_add(&self, rid: &str, relay: &[u8], now_ms: u64) -> Result<()> {
        self.0.execute(
            "INSERT OR REPLACE INTO gas_borrowed (rid, relay, since_ms, checked_ms) VALUES (?1, ?2, ?3, ?3)",
            params![rid, relay, now_ms as i64],
        )?;
        Ok(())
    }

    /// The relays borrowed: (rid, relay, since, last checked).
    pub fn gas_borrowed_all(&self) -> Result<Vec<(String, Vec<u8>, u64, u64)>> {
        let mut st = self.0.prepare("SELECT rid, relay, since_ms, checked_ms FROM gas_borrowed ORDER BY since_ms")?;
        let rows = st.query_map([], |r| Ok((r.get(0)?, r.get(1)?, r.get::<_, i64>(2)? as u64, r.get::<_, i64>(3)? as u64)))?;
        Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
    }

    pub fn gas_borrowed_checked(&self, rid: &str, now_ms: u64) -> Result<()> {
        self.0.execute("UPDATE gas_borrowed SET checked_ms = ?2 WHERE rid = ?1", params![rid, now_ms as i64])?;
        Ok(())
    }

    pub fn gas_borrowed_remove(&self, rid: &str) -> Result<()> {
        self.0.execute("DELETE FROM gas_borrowed WHERE rid = ?1", [rid])?;
        Ok(())
    }

    /// Keep one part of a frame that came through a mailbox (`frame::KIND_PART`). When the last
    /// of its parts is here, returns all their bytes in order and forgets them. Parts older than
    /// `keep_ms` go, and the oldest go past `max_parts` (a phone that never sends the rest).
    pub fn gas_part_add(&self, sender: &crate::UserId, part: &crate::frame::Part, now_ms: u64, keep_ms: u64, max_parts: usize) -> Result<Option<Vec<Vec<u8>>>> {
        self.0.execute("DELETE FROM gas_parts WHERE at_ms < ?1", [now_ms.saturating_sub(keep_ms) as i64])?;
        self.0.execute(
            "INSERT OR IGNORE INTO gas_parts (sender, fid, idx, count, data, at_ms) VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
            params![&sender[..], &part.id[..], part.index, part.count, part.data, now_ms as i64],
        )?;
        let mut st = self.0.prepare("SELECT idx, count, data FROM gas_parts WHERE sender = ?1 AND fid = ?2 ORDER BY idx")?;
        let rows = st.query_map(params![&sender[..], &part.id[..]], |r| Ok((r.get::<_, i64>(0)?, r.get::<_, i64>(1)?, r.get::<_, Vec<u8>>(2)?)))?;
        let rows = rows.collect::<rusqlite::Result<Vec<_>>>()?;
        let whole = rows.len() == part.count as usize && rows.iter().enumerate().all(|(i, (idx, count, _))| *idx == i as i64 && *count == part.count as i64);
        let done = rows.len() >= part.count as usize;
        if done {
            // all here (or parts that disagree on their count: no frame will come of them)
            self.0.execute("DELETE FROM gas_parts WHERE sender = ?1 AND fid = ?2", params![&sender[..], &part.id[..]])?;
        } else {
            let n: i64 = self.0.query_row("SELECT count(*) FROM gas_parts", [], |r| r.get(0))?;
            if n as usize > max_parts {
                self.0.execute("DELETE FROM gas_parts WHERE rowid IN (SELECT rowid FROM gas_parts ORDER BY at_ms LIMIT ?1)", [n - max_parts as i64])?;
            }
        }
        Ok(whole.then(|| rows.into_iter().map(|(_, _, d)| d).collect()))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::store::Store;

    fn relay(rid: &str, id: &str, pos: i64) -> GasRelayRow {
        GasRelayRow { rid: rid.into(), id: id.into(), use_key: [1; 16], owner_key: Some([2; 16]), name: "r".into(), added_ms: 10, enabled: true, announce: true, pos, mine: true, worker: None }
    }

    #[test]
    fn a_combined_relay_keeps_its_worker_and_loses_it_with_the_row() {
        let s = Store::open_in_memory(&[1; 32]).unwrap();
        let db = s.db();
        let mut r = relay("d-1", "", 1);
        r.worker = Some(String::new());
        db.gas_relay_save(&r).unwrap();
        assert_eq!(db.gas_relay("d-1").unwrap().unwrap().worker.as_deref(), Some(""));
        r.worker = Some("box.a.workers.dev".into());
        db.gas_relay_save(&r).unwrap();
        db.gas_relay_rename("d-1", "c", "idc").unwrap();
        assert_eq!(db.gas_relay_by_id("idc").unwrap().unwrap().worker.as_deref(), Some("box.a.workers.dev"));
        assert_eq!(db.gas_relays().unwrap()[0].worker.as_deref(), Some("box.a.workers.dev"));
        db.gas_relay_delete("c").unwrap();
        let mut plain = relay("p", "idp", 2);
        plain.use_key = [1; 16];
        db.gas_relay_save(&plain).unwrap();
        assert_eq!(db.gas_relay("p").unwrap().unwrap().worker, None, "the old row's worker went with it");
    }

    #[test]
    fn relays_are_listed_in_order_and_a_draft_can_become_one() {
        let s = Store::open_in_memory(&[1; 32]).unwrap();
        let db = s.db();
        db.gas_relay_save(&relay("b", "idb", 2)).unwrap();
        db.gas_relay_save(&relay("a", "ida", 1)).unwrap();
        db.gas_relay_save(&relay("d-1", "", 3)).unwrap();
        assert_eq!(db.gas_relays().unwrap().iter().map(|r| r.rid.as_str()).collect::<Vec<_>>(), vec!["a", "b", "d-1"]);
        db.gas_relay_rename("d-1", "c", "idc").unwrap();
        assert_eq!(db.gas_relay_by_id("idc").unwrap().unwrap().rid, "c");
        assert!(db.gas_relay_by_id("").unwrap().is_none());
        db.gas_relay_delete("a").unwrap();
        assert_eq!(db.gas_relays().unwrap().len(), 2);
    }

    #[test]
    fn stats_add_up_per_day() {
        let s = Store::open_in_memory(&[1; 32]).unwrap();
        let db = s.db();
        let d = StatDelta { req: 2, takes: 2, items_in: 1, ms_sum: 300, ms_n: 2, ..StatDelta::default() };
        db.gas_stats_add("a", 100, &d).unwrap();
        db.gas_stats_add("a", 100, &d).unwrap();
        db.gas_stats_add("a", 101, &d).unwrap();
        let rows = db.gas_stats("a", 100).unwrap();
        assert_eq!(rows.len(), 2);
        assert_eq!(rows[0].1.req, 4);
        assert_eq!(rows[0].1.ms_n, 4);
        db.gas_prune(101).unwrap();
        assert_eq!(db.gas_stats("a", 0).unwrap().len(), 1);
    }

    #[test]
    fn the_last_mailbox_exchange_with_a_contact_is_kept() {
        let s = Store::open_in_memory(&[1; 32]).unwrap();
        let db = s.db();
        assert_eq!(db.gas_peer(&[3; 32]).unwrap(), (0, 0));
        db.gas_peer_seen(&[3; 32], true, 500).unwrap();
        db.gas_peer_seen(&[3; 32], false, 700).unwrap();
        db.gas_peer_seen(&[3; 32], true, 900).unwrap();
        assert_eq!(db.gas_peer(&[3; 32]).unwrap(), (900, 700));
    }

    #[test]
    fn a_public_record_is_kept_when_newer_and_borrowed_relays_are_listed() {
        let s = Store::open_in_memory(&[1; 32]).unwrap();
        let db = s.db();
        assert!(db.gas_public_save(b"relay-1", &[2; 32], b"b1", &[0; 64], 100, 900, false).unwrap());
        assert!(!db.gas_public_save(b"relay-1", &[2; 32], b"b0", &[0; 64], 50, 900, false).unwrap(), "an older record changes nothing");
        assert!(db.gas_public_save(b"relay-1", &[2; 32], b"b2", &[0; 64], 200, 900, true).unwrap());
        let all = db.gas_public_all().unwrap();
        assert_eq!((all.len(), all[0].body.as_slice(), all[0].withdrawn), (1, b"b2".as_slice(), true));
        db.gas_public_avoid(b"relay-1", 777).unwrap();
        assert_eq!(db.gas_public_all().unwrap()[0].avoid_until, 777);
        db.gas_public_save(b"relay-2", &[3; 32], b"c", &[0; 64], 300, 400, false).unwrap();
        db.gas_public_prune(400 + crate::transport::gas::proto::DAY_MS + 1, 10).unwrap();
        assert_eq!(db.gas_public_all().unwrap().len(), 1, "a record a day past its end goes");
        db.gas_borrowed_add("r1", b"relay-1", 10).unwrap();
        db.gas_borrowed_checked("r1", 20).unwrap();
        assert_eq!(db.gas_borrowed_all().unwrap(), vec![("r1".to_string(), b"relay-1".to_vec(), 10, 20)]);
        db.gas_borrowed_remove("r1").unwrap();
        assert!(db.gas_borrowed_all().unwrap().is_empty());
    }

    #[test]
    fn parts_come_together_in_order_once_and_old_ones_go() {
        let s = Store::open_in_memory(&[1; 32]).unwrap();
        let db = s.db();
        let part = |id: u8, index: u8, data: &[u8]| crate::frame::Part { id: [id; 8], index, count: 3, data: data.to_vec() };
        assert_eq!(db.gas_part_add(&[5; 32], &part(1, 2, b"c"), 100, 1000, 50).unwrap(), None);
        assert_eq!(db.gas_part_add(&[5; 32], &part(1, 0, b"a"), 110, 1000, 50).unwrap(), None);
        assert_eq!(db.gas_part_add(&[5; 32], &part(1, 0, b"a"), 115, 1000, 50).unwrap(), None, "a part twice counts once");
        assert_eq!(db.gas_part_add(&[6; 32], &part(1, 1, b"x"), 118, 1000, 50).unwrap(), None, "another sender's part is not mixed in");
        assert_eq!(db.gas_part_add(&[5; 32], &part(1, 1, b"b"), 120, 1000, 50).unwrap(), Some(vec![b"a".to_vec(), b"b".to_vec(), b"c".to_vec()]));
        assert_eq!(db.gas_part_add(&[5; 32], &part(1, 1, b"b"), 125, 1000, 50).unwrap(), None, "taken apart once only");
        // old parts go
        db.gas_part_add(&[7; 32], &part(2, 0, b"a"), 200, 1000, 50).unwrap();
        assert_eq!(db.gas_part_add(&[7; 32], &part(2, 1, b"b"), 5000, 1000, 50).unwrap(), None);
        let n: i64 = db.0.query_row("SELECT count(*) FROM gas_parts WHERE sender = ?1", [&[7u8; 32][..]], |r| r.get(0)).unwrap();
        assert_eq!(n, 1, "the part of an hour ago went");
    }

    #[test]
    fn pending_frames_come_due_in_order_and_go() {
        let s = Store::open_in_memory(&[1; 32]).unwrap();
        let db = s.db();
        let p = |n: u8, next: u64| Pending { id: [n; 16], peer: [9; 32], loc: [8; 32], hk: [7; 32], relay: vec![1, 2], frame: vec![n; 4], first_ms: 1, put_ms: 1, next_ms: next, tries: 0, day: 5 };
        db.gas_pending_add(&p(1, 300)).unwrap();
        db.gas_pending_add(&p(2, 100)).unwrap();
        assert_eq!(db.gas_pending_due(50, 10).unwrap().len(), 0);
        assert_eq!(db.gas_pending_due(200, 10).unwrap()[0].id, [2; 16]);
        db.gas_pending_update(&[2; 16], 900, 1).unwrap();
        assert_eq!(db.gas_pending_due(400, 10).unwrap()[0].id, [1; 16]);
        assert_eq!(db.gas_pending_count().unwrap(), 2);
        db.gas_pending_done(&[1; 16]).unwrap();
        assert_eq!(db.gas_pending_count().unwrap(), 1);
        assert_eq!(db.gas_pending_by_relay().unwrap(), vec![(vec![1, 2], 1)]);
    }
}
