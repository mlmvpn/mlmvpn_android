// VLESS request header, as sent by Xray / sing-box as the first bytes of the WebSocket stream:
//
//   version(1)=0  uuid(16)  addonsLen(1)  addons(addonsLen)  command(1)  port(2, big-endian)
//   addressType(1)  address  payload…
//
//   command       1 TCP · 2 UDP · 3 MUX (no port/address follow)
//   addressType   1 IPv4 (4 bytes) · 2 domain (length byte + bytes) · 3 IPv6 (16 bytes)
//
// The parser is a pure function over a byte array. It never reads past the end: a header that has
// not fully arrived is `incomplete` (the caller keeps what it has and waits), anything wrong is an
// `error` with a short code that stays on the server — the client is only ever told "rejected".

import { bytesEqual } from './bytes.js';

export const COMMAND = Object.freeze({ TCP: 1, UDP: 2, MUX: 3 });

const INCOMPLETE = Object.freeze({ status: 'incomplete' });
const fail = (code) => ({ status: 'error', code });

const HOST_RE = /^[A-Za-z0-9._-]+$/;
const decoder = new TextDecoder('utf-8', { fatal: true });

/** A domain from the wire, or null when it is not a plausible host name. */
function readDomain(bytes) {
  if (!bytes.byteLength) return null;
  let text;
  try {
    text = decoder.decode(bytes);
  } catch {
    return null;
  }
  return text.length <= 253 && HOST_RE.test(text) ? text : null;
}

function readIPv6(view) {
  const groups = [];
  for (let i = 0; i < 16; i += 2) groups.push(((view[i] << 8) | view[i + 1]).toString(16));
  return groups.join(':');
}

/**
 * @param {Uint8Array} buf    everything received so far
 * @param {Uint8Array[]} uuids  accepted user ids, 16 bytes each
 * @returns {{status:'incomplete'} | {status:'error', code:string} |
 *           {status:'ok', version:number, command:number, host:string, port:number, offset:number}}
 */
export function parseVlessRequest(buf, uuids) {
  const n = buf.byteLength;
  if (n < 1) return INCOMPLETE;
  if (buf[0] !== 0) return fail('bad_version');
  if (n < 17) return INCOMPLETE;
  const id = buf.subarray(1, 17);
  // Every id is compared, so the time taken does not say which (if any) matched.
  let known = false;
  for (const candidate of uuids) if (bytesEqual(id, candidate)) known = true;
  if (!known) return fail('unauthorized');
  if (n < 18) return INCOMPLETE;
  let p = 18 + buf[17];
  if (n < p + 1) return INCOMPLETE;
  const command = buf[p++];
  if (command === COMMAND.MUX) return fail('mux_unsupported');
  if (command !== COMMAND.TCP && command !== COMMAND.UDP) return fail('bad_command');
  if (n < p + 3) return INCOMPLETE;
  const port = (buf[p] << 8) | buf[p + 1];
  p += 2;
  if (port === 0) return fail('bad_port');
  const type = buf[p++];
  let host;
  if (type === 1) {
    if (n < p + 4) return INCOMPLETE;
    host = `${buf[p]}.${buf[p + 1]}.${buf[p + 2]}.${buf[p + 3]}`;
    p += 4;
  } else if (type === 2) {
    if (n < p + 1) return INCOMPLETE;
    const len = buf[p++];
    if (len === 0) return fail('bad_host');
    if (n < p + len) return INCOMPLETE;
    host = readDomain(buf.subarray(p, p + len));
    if (!host) return fail('bad_host');
    p += len;
  } else if (type === 3) {
    if (n < p + 16) return INCOMPLETE;
    host = readIPv6(buf.subarray(p, p + 16));
    p += 16;
  } else {
    return fail('bad_address_type');
  }
  return { status: 'ok', version: buf[0], command, host, port, offset: p };
}
