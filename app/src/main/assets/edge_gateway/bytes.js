// Byte helpers shared by the gateway modules. Dependency-free: only Uint8Array, atob and
// TextDecoder, which the Workers runtime and Node both provide.

/** A binary WebSocket payload as bytes. Text frames are not part of this protocol → null. */
export function toBytes(data) {
  if (data instanceof Uint8Array) return data;
  if (data instanceof ArrayBuffer) return new Uint8Array(data);
  if (ArrayBuffer.isView(data)) return new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
  return null;
}

export function concatBytes(a, b) {
  if (!a || a.byteLength === 0) return b;
  if (!b || b.byteLength === 0) return a;
  const out = new Uint8Array(a.byteLength + b.byteLength);
  out.set(a, 0);
  out.set(b, a.byteLength);
  return out;
}

/**
 * base64url, padded or not (Xray writes early data unpadded) → bytes. The plain base64 alphabet is
 * accepted too; anything else is null.
 */
export function decodeBase64Url(text) {
  if (typeof text !== 'string' || !/^[A-Za-z0-9_+/-]+={0,2}$/.test(text)) return null;
  let b64 = text.replace(/=+$/, '').replace(/-/g, '+').replace(/_/g, '/');
  if (b64.length % 4 === 1) return null;
  while (b64.length % 4) b64 += '=';
  try {
    const bin = atob(b64);
    const out = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  } catch {
    return null;
  }
}

const UUID_RE = /^[0-9a-f]{8}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{12}$/i;

/** A UUID (dashes optional) as its 16 bytes; null when it is not one. */
export function parseUuid(text) {
  if (typeof text !== 'string') return null;
  const t = text.trim();
  if (!UUID_RE.test(t)) return null;
  const hex = t.replace(/-/g, '');
  const out = new Uint8Array(16);
  for (let i = 0; i < 16; i++) out[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16);
  return out;
}

/** Equal-length comparison that always looks at every byte. */
export function bytesEqual(a, b) {
  if (a.byteLength !== b.byteLength) return false;
  let diff = 0;
  for (let i = 0; i < a.byteLength; i++) diff |= a[i] ^ b[i];
  return diff === 0;
}
