//! HTTP/2 to the Google fronts (RFC 9113, HPACK RFC 7541), blocking, one request at a time on a
//! connection: the same calls as HTTP/1.1, with Google's answer heads compressed. Those heads are
//! about 3 KB of every call (mostly a `reporting-endpoints` header); with HPACK a call took 2.6 KB
//! in instead of 4.1 KB on Irancell (field test, issue 7, 26 Oct 2026). `http.rs` uses this only
//! when the front's ALPN answer is `h2`, and falls back to HTTP/1.1 otherwise.
//!
//! Limits against a malformed answer: frames of at most 16 KB (we never raise the size), a header
//! block and its decoded list of at most 64 KB each, a dynamic table of at most 4 KB (what we
//! advertise), and a body of at most 8 MB.

use super::huffman::{CODES, LENS};
use std::collections::HashMap;
use std::io::{self, Read, Write};
use std::sync::OnceLock;

use super::http::{HttpError, Response};

const PREFACE: &[u8] = b"PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n";
const MAX_FRAME: usize = 16_384;
const TABLE: usize = 4096;
const MAX_HEADERS: usize = 64 * 1024;
const MAX_BODY: usize = 8 * 1024 * 1024;
/// What the server may send us before a window update: per stream (SETTINGS) and per connection.
const WINDOW: u32 = 16 << 20;
/// A value this long (the GET's path with its one-time key) is not worth a place in the table.
const NO_INDEX_LEN: usize = 200;

const DATA: u8 = 0;
const HEADERS: u8 = 1;
const RST_STREAM: u8 = 3;
const SETTINGS: u8 = 4;
const PUSH_PROMISE: u8 = 5;
const PING: u8 = 6;
const GOAWAY: u8 = 7;
const WINDOW_UPDATE: u8 = 8;
const CONTINUATION: u8 = 9;

const END_STREAM: u8 = 0x1;
const ACK: u8 = 0x1;
const END_HEADERS: u8 = 0x4;
const PADDED: u8 = 0x8;
const PRIORITY: u8 = 0x20;

/// The HPACK static table (RFC 7541, Appendix A); index 0 is unused.
const STATIC: [(&str, &str); 62] = [
    ("", ""),
    (":authority", ""),
    (":method", "GET"),
    (":method", "POST"),
    (":path", "/"),
    (":path", "/index.html"),
    (":scheme", "http"),
    (":scheme", "https"),
    (":status", "200"),
    (":status", "204"),
    (":status", "206"),
    (":status", "304"),
    (":status", "400"),
    (":status", "404"),
    (":status", "500"),
    ("accept-charset", ""),
    ("accept-encoding", "gzip, deflate"),
    ("accept-language", ""),
    ("accept-ranges", ""),
    ("accept", ""),
    ("access-control-allow-origin", ""),
    ("age", ""),
    ("allow", ""),
    ("authorization", ""),
    ("cache-control", ""),
    ("content-disposition", ""),
    ("content-encoding", ""),
    ("content-language", ""),
    ("content-length", ""),
    ("content-location", ""),
    ("content-range", ""),
    ("content-type", ""),
    ("cookie", ""),
    ("date", ""),
    ("etag", ""),
    ("expect", ""),
    ("expires", ""),
    ("from", ""),
    ("host", ""),
    ("if-match", ""),
    ("if-modified-since", ""),
    ("if-none-match", ""),
    ("if-range", ""),
    ("if-unmodified-since", ""),
    ("last-modified", ""),
    ("link", ""),
    ("location", ""),
    ("max-forwards", ""),
    ("proxy-authenticate", ""),
    ("proxy-authorization", ""),
    ("range", ""),
    ("referer", ""),
    ("refresh", ""),
    ("retry-after", ""),
    ("server", ""),
    ("set-cookie", ""),
    ("strict-transport-security", ""),
    ("transfer-encoding", ""),
    ("user-agent", ""),
    ("vary", ""),
    ("via", ""),
    ("www-authenticate", ""),
];

/// A malformed or unexpected answer; the connection is not used again.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(super) struct Bad;

fn entry_size(name: &str, value: &str) -> usize {
    name.len() + value.len() + 32
}

/// An HPACK dynamic table (newest first) with its size cap.
#[derive(Default)]
struct Table {
    entries: Vec<(String, String)>,
    size: usize,
    max: usize,
}

impl Table {
    fn new(max: usize) -> Self {
        Table { entries: Vec::new(), size: 0, max }
    }

    fn evict(&mut self) {
        while self.size > self.max {
            let Some((n, v)) = self.entries.pop() else { break };
            self.size -= entry_size(&n, &v);
        }
    }

    fn set_max(&mut self, max: usize) {
        self.max = max;
        self.evict();
    }

    fn add(&mut self, name: String, value: String) {
        let s = entry_size(&name, &value);
        if s > self.max {
            // an entry larger than the table empties it (RFC 7541, 4.4)
            self.entries.clear();
            self.size = 0;
            return;
        }
        self.entries.insert(0, (name, value));
        self.size += s;
        self.evict();
    }

    fn get(&self, index: usize) -> Option<(String, String)> {
        if index == 0 {
            return None;
        }
        if index < STATIC.len() {
            let (n, v) = STATIC[index];
            return Some((n.to_string(), v.to_string()));
        }
        self.entries.get(index - STATIC.len()).cloned()
    }
}

fn huffman_symbols() -> &'static HashMap<(u8, u32), u8> {
    static MAP: OnceLock<HashMap<(u8, u32), u8>> = OnceLock::new();
    MAP.get_or_init(|| (0..256).map(|i| ((LENS[i], CODES[i]), i as u8)).collect())
}

fn huffman_decode(bits: &[u8]) -> Result<Vec<u8>, Bad> {
    let map = huffman_symbols();
    let mut out = Vec::with_capacity(bits.len() * 8 / 5);
    let (mut code, mut len) = (0u32, 0u8);
    for byte in bits {
        for i in (0..8).rev() {
            code = (code << 1) | u32::from((byte >> i) & 1);
            len += 1;
            if let Some(&sym) = map.get(&(len, code)) {
                out.push(sym);
                code = 0;
                len = 0;
            } else if len >= 30 {
                return Err(Bad);
            }
        }
    }
    // what is left is padding: at most 7 bits, all ones (the start of EOS)
    if len > 7 || code != (1u32 << len) - 1 {
        return Err(Bad);
    }
    Ok(out)
}

/// An HPACK integer with an `n`-bit prefix; `first` holds the bits above the prefix.
fn put_int(out: &mut Vec<u8>, mut v: usize, n: u8, first: u8) {
    let max = (1usize << n) - 1;
    if v < max {
        out.push(first | v as u8);
        return;
    }
    out.push(first | max as u8);
    v -= max;
    while v >= 128 {
        out.push((v & 0x7f) as u8 | 0x80);
        v >>= 7;
    }
    out.push(v as u8);
}

fn put_str(out: &mut Vec<u8>, s: &str) {
    put_int(out, s.len(), 7, 0);
    out.extend_from_slice(s.as_bytes());
}

/// Encodes our request heads. Fields that repeat (method, scheme, authority, the POST's path, user
/// agent) are added to the table and go as one or two bytes from the second request on.
struct Encoder {
    table: Table,
    /// A size update to announce at the start of the next block (the server's limit went down).
    update: Option<usize>,
}

impl Encoder {
    fn new() -> Self {
        Encoder { table: Table::new(TABLE), update: None }
    }

    /// The server's SETTINGS_HEADER_TABLE_SIZE: our table never grows past 4 KB, and shrinks when
    /// the server asks for less.
    fn server_max(&mut self, v: usize) {
        let m = v.min(TABLE);
        if m != self.table.max {
            self.table.set_max(m);
            self.update = Some(m);
        }
    }

    fn encode(&mut self, fields: &[(&str, &str)]) -> Vec<u8> {
        let mut out = Vec::new();
        if let Some(m) = self.update.take() {
            put_int(&mut out, m, 5, 0x20);
        }
        for (name, value) in fields {
            let exact = (1..STATIC.len()).find(|&i| STATIC[i] == (*name, *value)).or_else(|| {
                self.table.entries.iter().position(|(n, v)| n == name && v == value).map(|i| i + STATIC.len())
            });
            if let Some(i) = exact {
                put_int(&mut out, i, 7, 0x80);
                continue;
            }
            let name_index = (1..STATIC.len())
                .find(|&i| STATIC[i].0 == *name)
                .or_else(|| self.table.entries.iter().position(|(n, _)| n == name).map(|i| i + STATIC.len()))
                .unwrap_or(0);
            let index_it = *name != "content-length" && value.len() <= NO_INDEX_LEN;
            if index_it {
                put_int(&mut out, name_index, 6, 0x40);
            } else {
                put_int(&mut out, name_index, 4, 0x00);
            }
            if name_index == 0 {
                put_str(&mut out, name);
            }
            put_str(&mut out, value);
            if index_it {
                self.table.add(name.to_string(), value.to_string());
            }
        }
        out
    }
}

/// Decodes the server's heads.
struct Decoder {
    table: Table,
}

impl Decoder {
    fn new() -> Self {
        Decoder { table: Table::new(TABLE) }
    }

    fn decode(&mut self, block: &[u8]) -> Result<Vec<(String, String)>, Bad> {
        let mut pos = 0;
        let mut out = Vec::new();
        let mut total = 0usize;
        while pos < block.len() {
            let b = block[pos];
            let (name, value) = if b & 0x80 != 0 {
                let i = get_int(block, &mut pos, 7)?;
                self.table.get(i).ok_or(Bad)?
            } else if b & 0x40 != 0 {
                let i = get_int(block, &mut pos, 6)?;
                let name = if i == 0 { get_str(block, &mut pos)? } else { self.table.get(i).ok_or(Bad)?.0 };
                let value = get_str(block, &mut pos)?;
                self.table.add(name.clone(), value.clone());
                (name, value)
            } else if b & 0x20 != 0 {
                let m = get_int(block, &mut pos, 5)?;
                if m > TABLE {
                    return Err(Bad);
                }
                self.table.set_max(m);
                continue;
            } else {
                // without indexing (0000) or never indexed (0001)
                let i = get_int(block, &mut pos, 4)?;
                let name = if i == 0 { get_str(block, &mut pos)? } else { self.table.get(i).ok_or(Bad)?.0 };
                (name, get_str(block, &mut pos)?)
            };
            total += entry_size(&name, &value);
            if total > MAX_HEADERS {
                return Err(Bad);
            }
            out.push((name, value));
        }
        Ok(out)
    }
}

fn get_int(b: &[u8], pos: &mut usize, n: u8) -> Result<usize, Bad> {
    let max = (1usize << n) - 1;
    let mut v = (*b.get(*pos).ok_or(Bad)? as usize) & max;
    *pos += 1;
    if v < max {
        return Ok(v);
    }
    let mut shift = 0;
    loop {
        let byte = *b.get(*pos).ok_or(Bad)?;
        *pos += 1;
        if shift > 28 {
            return Err(Bad);
        }
        v += ((byte & 0x7f) as usize) << shift;
        shift += 7;
        if byte & 0x80 == 0 {
            return Ok(v);
        }
    }
}

fn get_str(b: &[u8], pos: &mut usize) -> Result<String, Bad> {
    let huffman = *b.get(*pos).ok_or(Bad)? & 0x80 != 0;
    let len = get_int(b, pos, 7)?;
    let end = pos.checked_add(len).filter(|&e| e <= b.len()).ok_or(Bad)?;
    let raw = &b[*pos..end];
    *pos = end;
    let bytes = if huffman { huffman_decode(raw)? } else { raw.to_vec() };
    Ok(String::from_utf8_lossy(&bytes).into_owned())
}

fn frame(kind: u8, flags: u8, stream: u32, payload: &[u8]) -> Vec<u8> {
    let mut f = Vec::with_capacity(9 + payload.len());
    f.extend_from_slice(&(payload.len() as u32).to_be_bytes()[1..]);
    f.push(kind);
    f.push(flags);
    f.extend_from_slice(&(stream & 0x7fff_ffff).to_be_bytes());
    f.extend_from_slice(payload);
    f
}

fn io_error(e: &io::Error) -> HttpError {
    if matches!(e.kind(), io::ErrorKind::TimedOut | io::ErrorKind::WouldBlock) {
        HttpError::Timeout
    } else {
        HttpError::Io
    }
}

/// One HTTP/2 connection. The error's flag of [`H2::request`] says, as in `http.rs`, whether any
/// part of the answer came (then a retry could repeat the call).
pub(super) struct H2<S: Read + Write> {
    io: S,
    buf: Vec<u8>,
    enc: Encoder,
    dec: Decoder,
    next_id: u32,
    started: bool,
    /// GOAWAY, a reset or a malformed frame: not to be used again.
    dead: bool,
    /// How much DATA the server lets us send: on the connection, and its initial per-stream window.
    send_conn: i64,
    send_init: i64,
    max_send_frame: usize,
    /// DATA taken in on the connection since our last window update.
    recv_unacked: u64,
}

struct Answer {
    status: u16,
    location: Option<String>,
    body: Vec<u8>,
}

impl<S: Read + Write> H2<S> {
    pub(super) fn new(io: S) -> Self {
        H2 {
            io,
            buf: Vec::new(),
            enc: Encoder::new(),
            dec: Decoder::new(),
            next_id: 1,
            started: false,
            dead: false,
            send_conn: 65_535,
            send_init: 65_535,
            max_send_frame: MAX_FRAME,
            recv_unacked: 0,
        }
    }

    pub(super) fn io(&self) -> &S {
        &self.io
    }

    /// Still good for another request.
    pub(super) fn usable(&self) -> bool {
        !self.dead && self.next_id < 0x7fff_0000
    }

    fn send(&mut self, bytes: &[u8]) -> io::Result<()> {
        self.io.write_all(bytes)?;
        self.io.flush()
    }

    fn start(&mut self) -> io::Result<()> {
        let mut settings = Vec::new();
        for (id, v) in [(1u16, TABLE as u32), (2, 0), (4, WINDOW), (6, MAX_HEADERS as u32)] {
            settings.extend_from_slice(&id.to_be_bytes());
            settings.extend_from_slice(&v.to_be_bytes());
        }
        let mut out = PREFACE.to_vec();
        out.extend(frame(SETTINGS, 0, 0, &settings));
        out.extend(frame(WINDOW_UPDATE, 0, 0, &(WINDOW - 65_535).to_be_bytes()));
        self.send(&out)?;
        self.started = true;
        Ok(())
    }

    /// The next frame: (kind, flags, stream, payload).
    fn read_frame(&mut self) -> Result<(u8, u8, u32, Vec<u8>), HttpError> {
        loop {
            if self.buf.len() >= 9 {
                let len = u32::from_be_bytes([0, self.buf[0], self.buf[1], self.buf[2]]) as usize;
                if len > MAX_FRAME {
                    return Err(HttpError::Protocol);
                }
                if self.buf.len() >= 9 + len {
                    let (kind, flags) = (self.buf[3], self.buf[4]);
                    let stream = u32::from_be_bytes([self.buf[5], self.buf[6], self.buf[7], self.buf[8]]) & 0x7fff_ffff;
                    let payload = self.buf[9..9 + len].to_vec();
                    self.buf.drain(..9 + len);
                    return Ok((kind, flags, stream, payload));
                }
            }
            let mut chunk = [0u8; 16 * 1024];
            match self.io.read(&mut chunk) {
                Ok(0) => return Err(HttpError::Io),
                Ok(n) => self.buf.extend_from_slice(&chunk[..n]),
                Err(e) => return Err(io_error(&e)),
            }
        }
    }

    /// A frame for the connection or for another stream: settings, ping, window, goaway.
    fn on_control(&mut self, kind: u8, flags: u8, stream: u32, p: &[u8], ours: u32, window: &mut i64) -> Result<(), (bool, HttpError)> {
        match kind {
            SETTINGS if flags & ACK == 0 => {
                if p.len() % 6 != 0 {
                    return Err((false, HttpError::Protocol));
                }
                for c in p.chunks(6) {
                    let id = u16::from_be_bytes([c[0], c[1]]);
                    let v = u32::from_be_bytes([c[2], c[3], c[4], c[5]]);
                    match id {
                        1 => self.enc.server_max(v as usize),
                        4 => {
                            if v > 0x7fff_ffff {
                                return Err((false, HttpError::Protocol));
                            }
                            *window += i64::from(v) - self.send_init;
                            self.send_init = i64::from(v);
                        }
                        5 => self.max_send_frame = (v as usize).clamp(MAX_FRAME, 16_777_215),
                        _ => {}
                    }
                }
                self.send(&frame(SETTINGS, ACK, 0, &[])).map_err(|e| (false, io_error(&e)))?;
            }
            PING if flags & ACK == 0 => {
                self.send(&frame(PING, ACK, 0, p)).map_err(|e| (false, io_error(&e)))?;
            }
            WINDOW_UPDATE => {
                if p.len() != 4 {
                    return Err((false, HttpError::Protocol));
                }
                let inc = i64::from(u32::from_be_bytes([p[0], p[1], p[2], p[3]]) & 0x7fff_ffff);
                if stream == 0 {
                    self.send_conn += inc;
                } else if stream == ours {
                    *window += inc;
                }
            }
            GOAWAY => {
                self.dead = true;
                let last = p.get(..4).map(|b| u32::from_be_bytes([b[0], b[1], b[2], b[3]]) & 0x7fff_ffff).unwrap_or(0);
                if last < ours {
                    // our request was not taken: safe to send again on a fresh connection
                    return Err((false, HttpError::Io));
                }
            }
            PUSH_PROMISE => return Err((false, HttpError::Protocol)), // we turned push off
            _ => {}
        }
        Ok(())
    }

    /// One request and its whole answer.
    pub(super) fn request(&mut self, method: &str, host: &str, path: &str, body: Option<&[u8]>) -> Result<Response, (bool, HttpError)> {
        let r = self.exchange(method, host, path, body);
        if r.is_err() {
            self.dead = true;
        }
        r.map(|a| Response { status: a.status, location: a.location, body: a.body })
    }

    fn exchange(&mut self, method: &str, host: &str, path: &str, body: Option<&[u8]>) -> Result<Answer, (bool, HttpError)> {
        if self.dead {
            return Err((false, HttpError::Io));
        }
        if !self.started {
            self.start().map_err(|e| (false, io_error(&e)))?;
        }
        let id = self.next_id;
        self.next_id += 2;
        let len = body.map(|b| b.len().to_string());
        let mut fields: Vec<(&str, &str)> = vec![(":method", method), (":scheme", "https"), (":path", path), (":authority", host), ("user-agent", "Mozilla/5.0"), ("accept", "*/*")];
        if let Some(l) = &len {
            fields.push(("content-type", "application/json"));
            fields.push(("content-length", l));
        }
        let block = self.enc.encode(&fields);
        let mut out = Vec::new();
        let pieces: Vec<&[u8]> = if block.is_empty() { vec![&block[..]] } else { block.chunks(self.max_send_frame).collect() };
        for (i, piece) in pieces.iter().enumerate() {
            let last = i + 1 == pieces.len();
            let mut flags = if last { END_HEADERS } else { 0 };
            if i == 0 && body.is_none() {
                flags |= END_STREAM;
            }
            out.extend(frame(if i == 0 { HEADERS } else { CONTINUATION }, flags, id, piece));
        }
        self.send(&out).map_err(|e| (false, io_error(&e)))?;
        let mut window = self.send_init;
        let mut got_any = false;
        let mut answer = Answer { status: 0, location: None, body: Vec::new() };
        let mut block: Vec<u8> = Vec::new();
        let mut block_ends = false;
        let mut block_stream = 0u32;
        let mut done = false;
        if let Some(b) = body {
            // DATA within the server's windows; wait for its window updates when they run out
            let mut sent = 0;
            while sent < b.len() || b.is_empty() {
                let room = window.min(self.send_conn).min(self.max_send_frame as i64);
                if room <= 0 {
                    let (kind, flags, stream, p) = self.read_frame().map_err(|e| (got_any, e))?;
                    if stream == id && matches!(kind, HEADERS | DATA | RST_STREAM) {
                        // the server answered before taking the whole body: a refusal
                        return Err((true, HttpError::Protocol));
                    }
                    self.on_control(kind, flags, stream, &p, id, &mut window)?;
                    continue;
                }
                let n = (b.len() - sent).min(room as usize);
                let end = sent + n == b.len();
                self.send(&frame(DATA, if end { END_STREAM } else { 0 }, id, &b[sent..sent + n])).map_err(|e| (false, io_error(&e)))?;
                sent += n;
                window -= n as i64;
                self.send_conn -= n as i64;
                if b.is_empty() {
                    break;
                }
            }
        }
        while !done {
            let (kind, flags, stream, p) = self.read_frame().map_err(|e| (got_any, e))?;
            match kind {
                HEADERS | CONTINUATION => {
                    let mut p = &p[..];
                    if kind == HEADERS {
                        block.clear();
                        block_stream = stream;
                        block_ends = flags & END_STREAM != 0;
                        if flags & PADDED != 0 {
                            let pad = *p.first().ok_or((got_any, HttpError::Protocol))? as usize;
                            if pad + 1 > p.len() {
                                return Err((got_any, HttpError::Protocol));
                            }
                            p = &p[1..p.len() - pad];
                        }
                        if flags & PRIORITY != 0 {
                            p = p.get(5..).ok_or((got_any, HttpError::Protocol))?;
                        }
                    } else if stream != block_stream {
                        return Err((got_any, HttpError::Protocol));
                    }
                    if block.len() + p.len() > MAX_HEADERS {
                        return Err((got_any, HttpError::Protocol));
                    }
                    block.extend_from_slice(p);
                    if flags & END_HEADERS == 0 {
                        continue;
                    }
                    // every block is decoded, ours or not, so the table stays in step with the server
                    let fields = self.dec.decode(&block).map_err(|_| (got_any, HttpError::Protocol))?;
                    if block_stream != id {
                        continue;
                    }
                    got_any = true;
                    let status = fields.iter().find(|(n, _)| n == ":status").and_then(|(_, v)| v.parse::<u16>().ok()).unwrap_or(0);
                    if (100..200).contains(&status) {
                        continue; // an informational answer; the real one follows
                    }
                    if answer.status == 0 {
                        answer.status = status;
                        answer.location = fields.iter().find(|(n, _)| n == "location").map(|(_, v)| v.clone());
                    }
                    done = block_ends;
                }
                DATA => {
                    self.recv_unacked += p.len() as u64;
                    if self.recv_unacked >= u64::from(WINDOW / 2) {
                        let inc = self.recv_unacked as u32;
                        self.recv_unacked = 0;
                        self.send(&frame(WINDOW_UPDATE, 0, 0, &inc.to_be_bytes())).map_err(|e| (got_any, io_error(&e)))?;
                    }
                    if stream != id {
                        continue;
                    }
                    got_any = true;
                    let mut p = &p[..];
                    if flags & PADDED != 0 {
                        let pad = *p.first().ok_or((got_any, HttpError::Protocol))? as usize;
                        if pad + 1 > p.len() {
                            return Err((got_any, HttpError::Protocol));
                        }
                        p = &p[1..p.len() - pad];
                    }
                    if answer.body.len() + p.len() > MAX_BODY {
                        return Err((got_any, HttpError::Protocol));
                    }
                    answer.body.extend_from_slice(p);
                    done = flags & END_STREAM != 0;
                }
                RST_STREAM if stream == id => {
                    let code = p.get(..4).map(|b| u32::from_be_bytes([b[0], b[1], b[2], b[3]])).unwrap_or(0);
                    // REFUSED_STREAM: the server did not touch it, so it may be sent again
                    return Err((got_any || code != 7, HttpError::Protocol));
                }
                _ => self.on_control(kind, flags, stream, &p, id, &mut window).map_err(|(g, e)| (g || got_any, e))?,
            }
        }
        if answer.status == 0 {
            return Err((true, HttpError::Protocol));
        }
        Ok(answer)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::{TcpListener, TcpStream};
    use std::thread;
    use std::time::Duration;

    /// Real frames from Google (216.239.38.120, SNI googleapis.com, Irancell, 26 Oct 2026) for two
    /// calls on one connection: POST /exec -> 302, GET googleusercontent -> 200, twice. The
    /// one-time `user_content_key` in them is long expired.
    const GOOGLE: &[u8] = include_bytes!("testdata/google-h2-frames.bin");

    fn frames(b: &[u8]) -> Vec<Vec<u8>> {
        let mut out = Vec::new();
        let mut i = 0;
        while i < b.len() {
            let len = u32::from_be_bytes([0, b[i], b[i + 1], b[i + 2]]) as usize;
            out.push(b[i..i + 9 + len].to_vec());
            i += 9 + len;
        }
        out
    }

    /// A server that answers each request (a client frame with END_STREAM) with the next group of
    /// recorded frames: everything up to and including the next frame that ends a stream.
    fn replay(recorded: &'static [u8]) -> TcpStream {
        let l = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = l.local_addr().unwrap();
        thread::spawn(move || {
            let (mut s, _) = l.accept().unwrap();
            let all = frames(recorded);
            let mut groups: Vec<Vec<u8>> = Vec::new();
            let mut cur = Vec::new();
            for f in all {
                let ends = (f[3] == HEADERS || f[3] == DATA) && f[4] & END_STREAM != 0;
                cur.extend_from_slice(&f);
                if ends {
                    groups.push(std::mem::take(&mut cur));
                }
            }
            let mut buf = Vec::new();
            let mut chunk = [0u8; 65536];
            let mut next = 0;
            let mut seen = 0;
            loop {
                let n = match s.read(&mut chunk) {
                    Ok(0) | Err(_) => return,
                    Ok(n) => n,
                };
                buf.extend_from_slice(&chunk[..n]);
                if seen == 0 && buf.starts_with(PREFACE) {
                    buf.drain(..PREFACE.len());
                    seen = 1;
                }
                while buf.len() >= 9 {
                    let len = u32::from_be_bytes([0, buf[0], buf[1], buf[2]]) as usize;
                    if buf.len() < 9 + len {
                        break;
                    }
                    let (kind, flags) = (buf[3], buf[4]);
                    buf.drain(..9 + len);
                    if (kind == HEADERS || kind == DATA) && flags & END_STREAM != 0 && next < groups.len() {
                        s.write_all(&groups[next]).unwrap();
                        next += 1;
                    }
                }
            }
        });
        let s = TcpStream::connect(addr).unwrap();
        s.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
        s
    }

    #[test]
    fn google_answers_decode_call_after_call() {
        let mut h = H2::new(replay(GOOGLE));
        for round in 0..2 {
            let post = h.request("POST", "script.google.com", "/macros/s/AKfycbx/exec", Some(b"{\"op\":\"ping\"}")).unwrap();
            assert_eq!(post.status, 302, "round {round}");
            let loc = post.location.expect("a location");
            assert!(loc.starts_with("https://script.googleusercontent.com/macros/echo?user_content_key="), "{loc}");
            let (host, path) = loc.strip_prefix("https://").unwrap().split_once('/').unwrap();
            let get = h.request("GET", host, &format!("/{path}"), None).unwrap();
            assert_eq!(get.status, 200);
            assert_eq!(get.body, br#"{"ok":1,"ver":3,"owner":0,"paused":0,"pub":0}"#);
            assert!(h.usable());
        }
    }

    #[test]
    fn huffman_matches_the_rfc_example() {
        // RFC 7541, C.4.1: "www.example.com"
        let bits = [0xf1, 0xe3, 0xc2, 0xe5, 0xf2, 0x3a, 0x6b, 0xa0, 0xab, 0x90, 0xf4, 0xff];
        assert_eq!(huffman_decode(&bits).unwrap(), b"www.example.com");
        // padding longer than 7 bits, or not all ones, is refused
        assert!(huffman_decode(&[0xf1, 0xe3, 0xc2, 0xe5, 0xf2, 0x3a, 0x6b, 0xa0, 0xab, 0x90, 0xf4, 0xff, 0xff]).is_err());
        assert!(huffman_decode(&[0xf1, 0xe3, 0xc2, 0xe5, 0xf2, 0x3a, 0x6b, 0xa0, 0xab, 0x90, 0xf4, 0xfe]).is_err());
    }

    #[test]
    fn our_heads_shrink_from_the_second_request_and_decode_back() {
        let mut enc = Encoder::new();
        let mut dec = Decoder::new();
        let fields = [(":method", "POST"), (":scheme", "https"), (":path", "/macros/s/AKfycbxxxxxxxxxxxxxxxx/exec"), (":authority", "script.google.com"), ("user-agent", "Mozilla/5.0"), ("accept", "*/*"), ("content-type", "application/json"), ("content-length", "123")];
        let first = enc.encode(&fields);
        let second = enc.encode(&fields);
        assert!(second.len() * 4 < first.len(), "{} then {}", first.len(), second.len());
        let want: Vec<(String, String)> = fields.iter().map(|(n, v)| (n.to_string(), v.to_string())).collect();
        assert_eq!(dec.decode(&first).unwrap(), want);
        assert_eq!(dec.decode(&second).unwrap(), want);
    }

    #[test]
    fn a_hostile_block_is_refused() {
        let mut dec = Decoder::new();
        // a table larger than the 4 KB we allow
        let mut b = Vec::new();
        put_int(&mut b, 5000, 5, 0x20);
        assert!(dec.decode(&b).is_err());
        // an index past both tables
        let mut b = Vec::new();
        put_int(&mut b, 200, 7, 0x80);
        assert!(dec.decode(&b).is_err());
        // a string longer than the block
        assert!(dec.decode(&[0x00, 0x05, b'a']).is_err());
        // more than 64 KB of decoded fields
        let mut b = Vec::new();
        for _ in 0..40 {
            b.push(0x00);
            put_str(&mut b, "x-big");
            put_str(&mut b, &"v".repeat(2000));
        }
        assert!(dec.decode(&b).is_err());
    }

    #[test]
    fn a_big_body_waits_for_the_window() {
        // a server with the default 64 KB windows that opens them as the body comes
        let l = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = l.local_addr().unwrap();
        let body_len = 200_000;
        let server = thread::spawn(move || {
            let (mut s, _) = l.accept().unwrap();
            let mut buf = Vec::new();
            let mut chunk = [0u8; 65536];
            let mut got = 0usize;
            let mut preface = true;
            s.write_all(&frame(SETTINGS, 0, 0, &[])).unwrap();
            loop {
                let n = s.read(&mut chunk).unwrap();
                if n == 0 {
                    return got;
                }
                buf.extend_from_slice(&chunk[..n]);
                if preface && buf.len() >= PREFACE.len() {
                    buf.drain(..PREFACE.len());
                    preface = false;
                }
                while buf.len() >= 9 {
                    let len = u32::from_be_bytes([0, buf[0], buf[1], buf[2]]) as usize;
                    if buf.len() < 9 + len {
                        break;
                    }
                    let (kind, flags) = (buf[3], buf[4]);
                    buf.drain(..9 + len);
                    if kind == DATA {
                        got += len;
                        let inc = (len as u32).to_be_bytes();
                        s.write_all(&[frame(WINDOW_UPDATE, 0, 0, &inc), frame(WINDOW_UPDATE, 0, 1, &inc)].concat()).unwrap();
                        if flags & END_STREAM != 0 {
                            // ":status: 200" from the static table, and a short body
                            s.write_all(&[frame(HEADERS, END_HEADERS, 1, &[0x88]), frame(DATA, END_STREAM, 1, b"ok")].concat()).unwrap();
                        }
                    }
                }
            }
        });
        let s = TcpStream::connect(addr).unwrap();
        s.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
        let mut h = H2::new(s);
        let body = vec![b'a'; body_len];
        let r = h.request("POST", "script.google.com", "/x", Some(&body)).unwrap();
        assert_eq!((r.status, r.body.as_slice()), (200, &b"ok"[..]));
        drop(h);
        assert_eq!(server.join().unwrap(), body_len);
    }
}
