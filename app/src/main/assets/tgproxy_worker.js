/**
 * Telegram WEB proxy relay: one stateless script that runs as a Cloudflare Worker and, uploaded as
 * `_worker.js`, as a Cloudflare Pages project.
 *
 * Telegram clients (Android 12.10+, Desktop 7.1+) can use a "WEB proxy": instead of a raw TCP
 * connection to an MTProxy, the app opens the server's bridge page in a private WebView and talks
 * to it over HTTPS/WSS. Link: tg://webproxy?server=<host>&secret=<hex>. A Worker cannot accept raw
 * TCP, so this is the one Telegram proxy type it can carry.
 *
 * Written from Telegram's published protocol notes (WEB proxy protocol v1: bridge capability,
 * shared frames, websocket carrier); no third-party code is included. Only the `websocket`
 * carrier is implemented.
 *
 *   GET  /?bridge=<cap>     the bridge page; <cap> = base64url(HMAC-SHA256(secret, label + host))
 *   POST /api/v1/session    HELLO frame in, WELCOME out, session token in a header. The page's own
 *                           later sessions present a session token instead of the bootstrap.
 *   GET  /api/v1/ws         the multiplexed WebSocket (subprotocol tproxy-v1.<session token>)
 *   DELETE /api/v1/session  accepted; the socket dies with its page
 *   anything else           a plain 404 page. The secret is never shown anywhere.
 *
 * The page carries Telegram's streams over one or more such sessions ("lanes"), never letting the
 * app see a lane change: a lane close to its dial budget hands new streams to a fresh lane made
 * beforehand and drains, and a lane that dies closes only its own streams. Telegram reconnects
 * single streams by itself in a moment; a failed carrier, in contrast, costs it every connection, a
 * second's pause and a reload of this page, which is what shows as "connecting to proxy". Each
 * socket is pinged every 20 seconds with a fresh session token, which keeps the path awake, lets the
 * page notice a silently dead lane, and lets it open new lanes for as long as it lives.
 *
 * Behind it the relay acts as the MTProxy: it opens the client's obfuscated2 stream with the
 * secret, reads the data centre and transport tag from it, dials that data centre with connect()
 * and speaks obfuscated2 to it with a fresh key. The data centre's keystream is chosen to equal
 * the one the client expects, so the heavy direction (downloads) is copied through untouched; only
 * uploads are re-encrypted.
 *
 * Bindings (set by the app's deployer):
 *   PROXY_SECRET  secret_text  32 hex characters, or "dd" + 32 hex (random-padding mode)
 *   MLM_TGP_ID    plain_text   marks the resource as created by the app; not read here
 *   MAX_DIALS     plain_text   optional: data-centre connections per socket; past it a stream is
 *                              refused, and the page moves to a fresh socket a little before that
 *
 * Tokens are stateless (a timestamp plus a MAC) because the POST that issues one and the WebSocket
 * that uses it can land on different instances. Consequence: a bootstrap token can be replayed
 * inside its two minutes. That is the cost of not keeping state; it lets nobody in without the
 * secret, because the bootstrap itself is only handed to a caller who knows the capability.
 */
import { connect } from 'cloudflare:sockets';

const BRIDGE_CONTEXT = 'tdesktop-web-proxy-bridge-v1\n';
const TOKEN_CONTEXT = 'tgp-token-v1\0';
const KEY_CONTEXT = 'tgp-token-key-v1';

const T_OPEN = 1;
const T_DATA = 2;
const T_CLOSE = 3;
const T_WINDOW = 4;
const T_PING = 5;
const T_PONG = 6;
const T_HELLO = 0x10;
const T_WELCOME = 0x11;

const MAX_PAYLOAD = 1024 * 1024;
const DATA_CHUNK = 64 * 1024;
const INITIAL_WINDOW = 4 * 1024 * 1024;
const MAX_FRAMES = 4096;
const MAX_MESSAGE = 4 * 1024 * 1024;
const MAX_STREAMS = 48;
const MAX_USED = 4096;
const DEFAULT_DIALS = 45;
const DIAL_TIMEOUT_MS = 6000;
const PING_MS = 20000;

const BOOTSTRAP_TTL = 120;
const SESSION_TTL = 600;
/** How long a session token may open the page's next session; pings keep it far fresher. */
const RENEW_TTL = 3600;

/** Telegram's data centres, port 443. Media data centres (negative ids) use the same addresses. */
const DATA_CENTRES = {
  1: ['149.154.175.50'],
  2: ['149.154.167.51'],
  3: ['149.154.175.100'],
  4: ['149.154.167.91'],
  5: ['149.154.171.5'],
};

const TAGS = new Set([0xefefefef, 0xeeeeeeee, 0xdddddddd]);
const FORBIDDEN_FIRST_WORD = new Set([0x44414548, 0x54534f50, 0x20544547, 0x4954504f, 0xdddddddd, 0xeeeeeeee, 0x02010316]);

const encoder = new TextEncoder();

// ---------------------------------------------------------------------------------- bytes

function hexBytes(text) {
  const out = new Uint8Array(text.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(text.substr(i * 2, 2), 16);
  return out;
}

function concat(...parts) {
  let total = 0;
  for (const p of parts) total += p.length;
  const out = new Uint8Array(total);
  let at = 0;
  for (const p of parts) {
    out.set(p, at);
    at += p.length;
  }
  return out;
}

function reversed(bytes) {
  const out = new Uint8Array(bytes.length);
  for (let i = 0; i < bytes.length; i++) out[i] = bytes[bytes.length - 1 - i];
  return out;
}

function b64url(bytes) {
  let s = '';
  for (let i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function unb64url(text) {
  const s = text.replace(/-/g, '+').replace(/_/g, '/');
  const raw = atob(s + '='.repeat((4 - (s.length % 4)) % 4));
  const out = new Uint8Array(raw.length);
  for (let i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i);
  return out;
}

function sameBytes(a, b) {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
  return diff === 0;
}

function u32(value) {
  const out = new Uint8Array(4);
  new DataView(out.buffer).setUint32(0, value);
  return out;
}

function frame(type, id, payload) {
  const n = payload ? payload.length : 0;
  const out = new Uint8Array(8 + n);
  out[0] = type;
  out[1] = id >>> 16;
  out[2] = id >>> 8;
  out[3] = id;
  new DataView(out.buffer).setUint32(4, n);
  if (n) out.set(payload, 8);
  return out;
}

/** Splits a batch of complete frames; throws on anything malformed or oversized. */
function parseFrames(bytes) {
  const frames = [];
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  let at = 0;
  while (at < bytes.length) {
    if (frames.length >= MAX_FRAMES || bytes.length - at < 8) throw new Error('batch');
    const size = view.getUint32(at + 4);
    if (size > MAX_PAYLOAD || at + 8 + size > bytes.length) throw new Error('frame');
    frames.push({
      type: bytes[at],
      id: (bytes[at + 1] << 16) | (bytes[at + 2] << 8) | bytes[at + 3],
      payload: bytes.subarray(at + 8, at + 8 + size),
    });
    at += 8 + size;
  }
  if (!frames.length) throw new Error('empty');
  return frames;
}

/** The shapes a client may send; anything else ends the session. */
function checkClientFrame(f) {
  if (f.id === 0) {
    if (f.type !== T_PONG || f.payload.length > 64) throw new Error('stream 0');
    return;
  }
  if (f.type === T_OPEN || f.type === T_CLOSE) {
    if (f.payload.length !== 0) throw new Error('payload');
  } else if (f.type === T_DATA) {
    if (f.payload.length === 0) throw new Error('empty data');
  } else if (f.type === T_WINDOW) {
    if (f.payload.length !== 4 || new DataView(f.payload.buffer, f.payload.byteOffset, 4).getUint32(0) === 0) throw new Error('window');
  } else {
    throw new Error('type');
  }
}

// ---------------------------------------------------------------------------------- crypto

async function hmacKey(bytes) {
  return crypto.subtle.importKey('raw', bytes, { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
}

async function hmac(key, data) {
  return new Uint8Array(await crypto.subtle.sign('HMAC', key, data));
}

async function sha256(data) {
  return new Uint8Array(await crypto.subtle.digest('SHA-256', data));
}

/** The counter block `iv + blocks`, as a 128-bit big-endian number. */
function addCounter(iv, blocks) {
  const out = iv.slice();
  let carry = blocks;
  for (let i = 15; i >= 0 && carry > 0; i--) {
    const v = out[i] + (carry % 256);
    out[i] = v & 255;
    carry = Math.floor(carry / 256) + (v >> 8);
  }
  return out;
}

/** An AES-256-CTR keystream cipher, usable at any byte offset of the stream. */
async function ctrCipher(keyBytes, iv) {
  const key = await crypto.subtle.importKey('raw', keyBytes, 'AES-CTR', false, ['encrypt']);
  return { key, iv };
}

async function ctrApply(cipher, offset, data) {
  if (!data.length) return data;
  const pad = offset % 16;
  const counter = addCounter(cipher.iv, Math.floor(offset / 16));
  let input = data;
  if (pad) {
    input = new Uint8Array(pad + data.length);
    input.set(data, pad);
  }
  const out = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-CTR', counter, length: 128 }, cipher.key, input));
  return pad ? out.subarray(pad) : out;
}

// ---------------------------------------------------------------------------------- setup

let cached = null;

/** The parsed secret and the keys derived from it; null when the binding is missing or malformed. */
function getConfig(env) {
  const raw = typeof env.PROXY_SECRET === 'string' ? env.PROXY_SECRET.trim().toLowerCase() : '';
  if (!cached || cached.raw !== raw) cached = { raw, value: buildConfig(raw) };
  return cached.value;
}

async function buildConfig(raw) {
  const m = /^(dd)?([0-9a-f]{32})$/.exec(raw);
  if (!m) return null;
  const full = hexBytes(raw);
  const capKey = await hmacKey(full);
  const tokenKey = await hmacKey(await hmac(capKey, encoder.encode(KEY_CONTEXT)));
  return { full, key16: hexBytes(m[2]), padded: !!m[1], capKey, tokenKey };
}

function dialLimit(env) {
  const n = parseInt(env.MAX_DIALS, 10);
  return Number.isFinite(n) && n >= 4 ? Math.min(n, 100000) : DEFAULT_DIALS;
}

// ---------------------------------------------------------------------------------- tokens

async function mintToken(cfg, kind, now = Math.floor(Date.now() / 1000)) {
  const nonce = new Uint8Array(16);
  crypto.getRandomValues(nonce);
  new DataView(nonce.buffer).setUint32(0, now);
  const mac = (await hmac(cfg.tokenKey, concat(encoder.encode(TOKEN_CONTEXT), Uint8Array.of(kind), nonce))).subarray(0, 16);
  return b64url(concat(nonce, mac));
}

async function checkToken(cfg, text, kind, ttl, now = Math.floor(Date.now() / 1000)) {
  if (typeof text !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(text)) return false;
  const bytes = unb64url(text);
  if (bytes.length !== 32) return false;
  const nonce = bytes.subarray(0, 16);
  const mac = (await hmac(cfg.tokenKey, concat(encoder.encode(TOKEN_CONTEXT), Uint8Array.of(kind), nonce))).subarray(0, 16);
  if (!sameBytes(mac, bytes.subarray(16))) return false;
  const age = now - new DataView(nonce.buffer, nonce.byteOffset, 4).getUint32(0);
  return age >= -30 && age <= ttl;
}

function bearer(request) {
  const m = /^Bearer ([A-Za-z0-9_-]{43})$/.exec(request.headers.get('Authorization') || '');
  return m ? m[1] : '';
}

// ---------------------------------------------------------------------------------- responses

const NOT_FOUND_PAGE = '<!doctype html><html lang="en"><head><meta charset="utf-8"><title>404 Not Found</title></head><body><h1>Not Found</h1></body></html>';

function notFound() {
  return new Response(NOT_FOUND_PAGE, { status: 404, headers: { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' } });
}

const PERMISSIONS_POLICY =
  'accelerometer=(), autoplay=(), camera=(), clipboard-read=(), clipboard-write=(), display-capture=(), ' +
  'encrypted-media=(), fullscreen=(), geolocation=(), gyroscope=(), hid=(), idle-detection=(), magnetometer=(), ' +
  'microphone=(), midi=(), payment=(), picture-in-picture=(), screen-wake-lock=(), serial=(), usb=(), web-share=(), ' +
  'xr-spatial-tracking=()';

/** The bridge page's script. __BASE__ and __BOOT__ are filled with JSON string literals, __DIALS__ with a number. */
const BRIDGE_SCRIPT = String.raw`(()=>{
'use strict';
const BASE=__BASE__,BOOT=__BOOT__,DIALS=__DIALS__;
const found=/^#android=([A-Za-z0-9_-]{43})$/.exec(location.hash);
const androidNonce=found?found[1]:'';
history.replaceState(null,'',location.pathname);
const QUEUE_LIMIT=33554432,MESSAGE_LIMIT=262144,BUFFER_LIMIT=4194304,FRAME_LIMIT=4096,PAYLOAD_LIMIT=1048576;
const WARM_AT=Math.max(1,DIALS-12),SWITCH_AT=Math.max(1,DIALS-2),QUIET_MS=65000,TRIES=3,DEATHS=4,DEATH_WINDOW_MS=30000;
const HELLO=[16,0,0,0,0,0,0,1,1];
let closed=false,started=false,connected=false,token=BOOT,port=null,queued=0,cur=null,next=null,watch=0;
const lanes=new Set(),route=new Map(),deaths=[];
const post=(value)=>{if(port&&!closed)port.postMessage(value)};
const status=(state)=>post({t:'status',state});
const traffic=(up,down)=>post({t:'traffic',up,down});
const request=(method,token,body)=>({method,body,keepalive:method==='DELETE',mode:'same-origin',credentials:'omit',cache:'no-store',redirect:'error',referrerPolicy:'no-referrer',headers:Object.assign(token?{Authorization:'Bearer '+token}:{},body?{'Content-Type':'application/octet-stream'}:{})});
const pause=(ms)=>new Promise((resolve)=>setTimeout(resolve,ms));
function split(buffer){
 const view=new DataView(buffer),frames=[];let at=0;
 while(at<buffer.byteLength){
  if(buffer.byteLength-at<8||frames.length>=FRAME_LIMIT)throw new Error('batch');
  const size=view.getUint32(at+4),end=at+8+size;
  if(size>PAYLOAD_LIMIT||end>buffer.byteLength||(view.getUint8(at)===2&&!size))throw new Error('frame');
  frames.push(at===0&&end===buffer.byteLength?buffer:buffer.slice(at,end));at=end;
 }
 if(!frames.length)throw new Error('empty');
 return frames;
}
function frameOf(type,id,payload){
 const size=payload?payload.length:0,out=new Uint8Array(8+size),view=new DataView(out.buffer);
 view.setUint32(0,id);out[0]=type;view.setUint32(4,size);if(size)out.set(payload,8);
 return out.buffer;
}
const typeOf=(item)=>new DataView(item).getUint8(0);
const idOf=(item)=>new DataView(item).getUint32(0)&16777215;
function fail(){
 if(closed)return;
 status('failed');post({t:'close'});close(true);
}
function close(notify){
 if(closed)return;closed=true;
 if(watch)clearInterval(watch);
 for(const lane of lanes)shut(lane);
 if(notify&&token!==BOOT)fetch(BASE+'api/v1/session',request('DELETE',token,null)).catch(()=>{});
 if(port&&port.close)port.close();
}
// A lane: one session and its WebSocket. New streams go to cur; next is the lane being made.
function makeLane(){
 const lane={ws:null,ready:false,dead:false,opens:0,streams:new Set(),out:[],heard:0,pinged:false,timer:0};
 lanes.add(lane);return lane;
}
function shut(lane){
 if(lane.dead)return false;
 lane.dead=true;lane.ready=false;lanes.delete(lane);
 if(lane.timer)clearTimeout(lane.timer);
 for(const item of lane.out)queued-=item.byteLength;
 lane.out=[];
 try{if(lane.ws)lane.ws.close()}catch(e){}
 if(cur===lane)cur=null;
 if(next===lane)next=null;
 return true;
}
// A lane that failed by itself: the app hears that its streams closed, and reconnects them.
function lost(lane){
 if(!shut(lane)||closed)return;
 for(const id of lane.streams){if(route.get(id)===lane)route.delete(id);post(frameOf(3,id))}
 lane.streams.clear();
 const now=Date.now();deaths.push(now);
 while(now-deaths[0]>DEATH_WINDOW_MS)deaths.shift();
 if(deaths.length>=DEATHS){fail();return}
 if(connected&&!cur)warm();
}
// An old lane goes once its last stream has.
function retire(lane){
 if(lane!==cur&&lane!==next&&!lane.streams.size&&!lane.out.length)shut(lane);
}
function openSocket(lane,session){
 return new Promise((resolve,reject)=>{
  const ws=new WebSocket(BASE.replace(/^https:/,'wss:')+'api/v1/ws','tproxy-v1.'+session);
  lane.ws=ws;ws.binaryType='arraybuffer';
  ws.onopen=()=>{if(lane.ws!==ws||lane.dead){reject(new Error('gone'));return}lane.ready=true;lane.heard=Date.now();resolve()};
  ws.onerror=()=>{if(!lane.ready)reject(new Error('socket'))};
  ws.onclose=()=>{if(lane.ws!==ws)return;if(lane.ready)lost(lane);else reject(new Error('socket'))};
  ws.onmessage=(event)=>{if(lane.ws===ws)fromRelay(lane,event.data)};
 });
}
async function connectLane(lane,hello,tries){
 for(let i=1;;i++){
  try{
   const response=await fetch(BASE+'api/v1/session',request('POST',token,hello));
   if(response.status!==200||response.headers.get('X-Carrier-Mode')!=='websocket')throw new Error(response.status===404?'refused':'rejected');
   const session=response.headers.get('X-Session-Token')||'';
   if(!session)throw new Error('token');
   const welcome=await response.arrayBuffer();
   if(lane.dead)return null;
   token=session;
   await openSocket(lane,session);
   return welcome;
  }catch(e){
   if(lane.dead||i>=tries||e.message==='refused')throw e;
   try{if(lane.ws)lane.ws.close()}catch(x){}
   await pause(800*i);
  }
 }
}
function promote(lane){
 if(next===lane)next=null;
 const old=cur;cur=lane;
 flush(lane);
 if(old)retire(old);
}
// Makes the next lane before it is needed. If that fails while cur still has room, cur carries on.
function warm(){
 if(next||closed)return;
 const lane=makeLane();next=lane;
 connectLane(lane,new Uint8Array(HELLO).buffer,TRIES).then((welcome)=>{if(welcome&&!closed&&!lane.dead)promote(lane)},()=>{
  if(closed)return;
  if(!cur||cur.opens>=SWITCH_AT){fail();return}
  lost(lane);
 });
}
function laneFor(){
 if(cur&&cur.opens<SWITCH_AT)return cur;
 warm();
 return next;
}
function send(lane,item){
 if(lane.dead)return;
 if(queued+item.byteLength>QUEUE_LIMIT){fail();return}
 queued+=item.byteLength;lane.out.push(item);flush(lane);
}
function flush(lane){
 if(closed||!lane.ready)return;
 const ws=lane.ws;
 while(lane.out.length){
  if(ws.bufferedAmount>BUFFER_LIMIT){if(!lane.timer)lane.timer=setTimeout(()=>{lane.timer=0;flush(lane);retire(lane)},10);return}
  let total=0,count=0;
  while(count<lane.out.length&&count<FRAME_LIMIT){
   const size=lane.out[count].byteLength;
   if(count&&total+size>MESSAGE_LIMIT)break;
   total+=size;count++;
  }
  const body=new Uint8Array(total);let at=0;
  for(const item of lane.out.splice(0,count)){body.set(new Uint8Array(item),at);at+=item.byteLength}
  queued-=total;
  try{ws.send(body)}catch(e){lost(lane);return}
  traffic(total,0);
 }
}
function fromApp(item){
 const type=typeOf(item),id=idOf(item);
 if(!id)return;
 if(type===1){
  const lane=laneFor();
  if(!lane)return;
  route.set(id,lane);lane.streams.add(id);lane.opens++;
  send(lane,item);
  if(lane===cur&&lane.opens>=WARM_AT)warm();
  return;
 }
 const lane=route.get(id);
 if(!lane)return;
 if(type===3){route.delete(id);lane.streams.delete(id)}
 send(lane,item);
 if(type===3)retire(lane);
}
function fromRelay(lane,data){
 if(lane.dead||closed)return;
 if(!(data instanceof ArrayBuffer)||!data.byteLength){lost(lane);return}
 let frames;try{frames=split(data)}catch(e){lost(lane);return}
 lane.heard=Date.now();
 traffic(0,data.byteLength);
 for(const item of frames){
  const type=typeOf(item),id=idOf(item);
  if(!id){if(type===5)ping(lane,item);continue}
  if(type===3){lane.streams.delete(id);if(route.get(id)===lane)route.delete(id)}
  post(item);
 }
 retire(lane);
 flush(lane);
}
// Pings are the page's business: answered here, and their token opens the next lane.
function ping(lane,item){
 const echo=new Uint8Array(item,8);
 if(echo.length>64)return;
 lane.pinged=true;
 const text=String.fromCharCode.apply(null,echo);
 if(/^[A-Za-z0-9_-]{43}$/.test(text))token=text;
 send(lane,frameOf(6,0,echo));
}
// A lane that pings and then falls silent is dead even if its socket has not noticed.
function check(){
 const now=Date.now();
 for(const lane of lanes)if(lane.ready&&lane.pinged&&now-lane.heard>QUIET_MS)lost(lane);
}
async function start(hello){
 status('connecting');
 const lane=makeLane();next=lane;
 try{
  const welcome=await connectLane(lane,hello,1);
  if(!welcome||closed)return;
  connected=true;
  promote(lane);
  post(welcome);status('connected');
  watch=setInterval(check,15000);
 }catch(e){fail()}
}
function onApp(data){
 if(data instanceof ArrayBuffer){
  if(!started){started=true;start(data);return}
  let frames;try{frames=split(data)}catch(e){fail();return}
  for(const item of frames)fromApp(item);
 }else if(data&&data.t==='close')close(true);
}
const bridge=globalThis.TelegramWebProxy;
if(androidNonce&&bridge&&typeof bridge.postMessage==='function'){
 port={postMessage(value){bridge.postMessage(value instanceof ArrayBuffer?value:JSON.stringify(value))},close(){bridge.onmessage=null}};
 bridge.onmessage=(event)=>{
  let data=event.data;
  if(typeof data==='string'){try{data=JSON.parse(data)}catch(e){return}}
  onApp(data);
 };
 status('connecting');
 bridge.postMessage(JSON.stringify({t:'tproxy-android-init',v:1,nonce:androidNonce}));
}
addEventListener('message',(event)=>{
 if(port||event.source!==parent||event.data===null||typeof event.data!=='object')return;
 const keys=Object.keys(event.data).sort();
 if(keys.length!==2||keys[0]!=='t'||keys[1]!=='v'||event.data.t!=='tproxy-init'||event.data.v!==1||event.ports.length!==1)return;
 let from;try{from=new URL(event.origin)}catch(e){return}
 if(from.protocol!=='http:'||from.hostname!=='127.0.0.1'||!from.port||from.origin!==event.origin)return;
 const channel=event.ports[0];
 port={postMessage(value){if(value instanceof ArrayBuffer)channel.postMessage(value,[value]);else channel.postMessage(value)},close(){channel.close()}};
 channel.onmessage=(message)=>onApp(message.data);
 channel.start();status('connecting');
});
addEventListener('pagehide',()=>close(true),{once:true});
})();`;

function bridgePage(host, bootstrap, dials) {
  const nonceBytes = new Uint8Array(18);
  crypto.getRandomValues(nonceBytes);
  const nonce = b64url(nonceBytes);
  const script = BRIDGE_SCRIPT.replace('__BASE__', () => JSON.stringify('https://' + host + '/'))
    .replace('__BOOT__', () => JSON.stringify(bootstrap))
    .replace('__DIALS__', () => String(dials));
  const body =
    '<!doctype html><html lang="en"><head><meta charset="utf-8">' +
    '<meta name="viewport" content="width=device-width,initial-scale=1"><title>Connection</title></head>' +
    '<body><script nonce="' + nonce + '">' + script + '</script></body></html>';
  const csp = [
    "default-src 'none'", "base-uri 'none'", "child-src 'none'", "connect-src 'self' wss://" + host, "font-src 'none'",
    "form-action 'none'", 'frame-ancestors http://127.0.0.1:*', "frame-src 'none'", "img-src 'none'", "manifest-src 'none'",
    "media-src 'none'", "object-src 'none'", "script-src 'nonce-" + nonce + "'", "style-src 'none'", "worker-src 'none'",
    'sandbox allow-same-origin allow-scripts',
  ].join('; ');
  return new Response(body, {
    status: 200,
    headers: {
      'Content-Type': 'text/html; charset=utf-8',
      'Content-Security-Policy': csp,
      'Cache-Control': 'no-store',
      'Referrer-Policy': 'no-referrer',
      'X-Content-Type-Options': 'nosniff',
      'X-DNS-Prefetch-Control': 'off',
      'Permissions-Policy': PERMISSIONS_POLICY,
    },
  });
}

// ---------------------------------------------------------------------------------- http

const HELLO_FRAME = Uint8Array.of(T_HELLO, 0, 0, 0, 0, 0, 0, 1, 1);

async function handle(request, env) {
  const cfg = await getConfig(env);
  if (!cfg) return notFound();
  const url = new URL(request.url);
  const host = url.hostname.toLowerCase();

  if (url.pathname === '/' && request.method === 'GET' && /^\?bridge=[A-Za-z0-9_-]{43}$/.test(url.search)) {
    const expected = b64url(await hmac(cfg.capKey, encoder.encode(BRIDGE_CONTEXT + host)));
    if (!sameBytes(encoder.encode(url.search.slice(8)), encoder.encode(expected))) return notFound();
    return bridgePage(host, await mintToken(cfg, 1), dialLimit(env));
  }

  if (url.pathname === '/api/v1/session') {
    if (request.method === 'POST') {
      // The first session comes with the page's bootstrap, later ones with a session token.
      const auth = bearer(request);
      if (!(await checkToken(cfg, auth, 1, BOOTSTRAP_TTL)) && !(await checkToken(cfg, auth, 2, RENEW_TTL))) return notFound();
      const declared = parseInt(request.headers.get('Content-Length') || '0', 10);
      if (declared > 64) return notFound();
      const body = new Uint8Array(await request.arrayBuffer());
      if (!sameBytes(body, HELLO_FRAME)) return notFound();
      return new Response(frame(T_WELCOME, 0), {
        status: 200,
        headers: {
          'Content-Type': 'application/octet-stream',
          'Cache-Control': 'no-store',
          'X-Session-Token': await mintToken(cfg, 2),
          'X-Down-Cursor': '0',
          'X-Carrier-Mode': 'websocket',
        },
      });
    }
    if (request.method === 'DELETE') {
      if (!(await checkToken(cfg, bearer(request), 2, RENEW_TTL))) return notFound();
      return new Response(null, { status: 204, headers: { 'Cache-Control': 'no-store' } });
    }
    return notFound();
  }

  if (url.pathname === '/api/v1/ws' && request.method === 'GET' && (request.headers.get('Upgrade') || '').toLowerCase() === 'websocket') {
    const offered = (request.headers.get('Sec-WebSocket-Protocol') || '').trim();
    const m = /^tproxy-v1\.([A-Za-z0-9_-]{43})$/.exec(offered);
    if (!m || !(await checkToken(cfg, m[1], 2, SESSION_TTL))) return notFound();
    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    server.accept();
    serve(server, cfg, { maxDials: dialLimit(env), pingMs: PING_MS }, connect);
    return new Response(null, { status: 101, webSocket: client, headers: { 'Sec-WebSocket-Protocol': offered } });
  }

  return notFound();
}

// ---------------------------------------------------------------------------------- relay

/** Opens the client's obfuscated2 header: the keys both directions use, the data centre, the tag. */
async function readClientInit(cfg, init) {
  const upKey = await sha256(concat(init.subarray(8, 40), cfg.key16));
  const downSource = reversed(init.subarray(8, 56));
  const downKey = await sha256(concat(downSource.subarray(0, 32), cfg.key16));
  const downIv = downSource.slice(32, 48);
  const up = await ctrCipher(upKey, init.slice(40, 56));
  const opened = await ctrApply(up, 0, init);
  const tag = new DataView(opened.buffer, opened.byteOffset + 56, 4).getUint32(0);
  const dc = new DataView(opened.buffer, opened.byteOffset + 60, 2).getInt16(0, true);
  return { up, downKey, downIv, tag, dc };
}

/**
 * The 64-byte header toward the data centre. Its download keystream is the one the client expects
 * (reverse(init[8..56]) = downKey || downIv), so what the data centre sends needs no re-encryption.
 */
async function buildDcInit(client) {
  for (;;) {
    const init = new Uint8Array(64);
    crypto.getRandomValues(init);
    const first = new DataView(init.buffer).getUint32(0, true);
    if (init[0] === 0xef || FORBIDDEN_FIRST_WORD.has(first) || FORBIDDEN_FIRST_WORD.has(new DataView(init.buffer).getUint32(0)) || new DataView(init.buffer).getUint32(4) === 0) continue;
    init.set(reversed(concat(client.downKey, client.downIv)), 8);
    new DataView(init.buffer).setUint32(56, client.tag);
    new DataView(init.buffer).setInt16(60, client.dc, true);
    const up = await ctrCipher(init.slice(8, 40), init.slice(40, 56));
    const sealed = await ctrApply(up, 0, init);
    const wire = init.slice();
    wire.set(sealed.subarray(56, 64), 56);
    return { wire, up };
  }
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function dialDc(dial, dc) {
  const addresses = DATA_CENTRES[Math.abs(dc)];
  if (!addresses) throw new Error('dc');
  let last;
  for (const hostname of addresses) {
    try {
      const socket = dial({ hostname, port: 443 });
      if (socket.opened) {
        await Promise.race([socket.opened, sleep(DIAL_TIMEOUT_MS).then(() => Promise.reject(new Error('timeout')))]);
      }
      return socket;
    } catch (e) {
      last = e;
    }
  }
  throw last || new Error('dial');
}

/**
 * One WebSocket = one carrier session = many logical streams (one per Telegram TCP connection).
 * `dial` is connect() from cloudflare:sockets (a fake one in tests). `opts.pingMs`, when set, pings
 * the page that often; each PING carries a fresh session token for the page's next lane.
 */
function serve(ws, cfg, opts, dial) {
  const streams = new Map();
  const used = new Set();
  let dials = 0;
  let done = false;
  let pinger = 0;

  const send = (bytes) => {
    if (done) return;
    try {
      ws.send(bytes);
    } catch (e) {
      stop(4001);
    }
  };

  const kill = (s) => {
    if (s.dead) return;
    s.dead = true;
    if (s.wake) {
      const w = s.wake;
      s.wake = null;
      w();
    }
    try {
      if (s.socket) s.socket.close();
    } catch (e) {}
    try {
      if (s.reader) s.reader.cancel().catch(() => {});
    } catch (e) {}
  };

  const stop = (code) => {
    if (done) return;
    done = true;
    if (pinger) clearInterval(pinger);
    for (const s of streams.values()) kill(s);
    streams.clear();
    try {
      ws.close(code);
    } catch (e) {}
  };

  const drop = (s, tell) => {
    if (s.dead) return;
    kill(s);
    streams.delete(s.id);
    if (tell) send(frame(T_CLOSE, s.id));
  };

  const give = (s, n) => {
    s.grant += n;
    if (s.granting) return;
    s.granting = true;
    queueMicrotask(() => {
      s.granting = false;
      if (s.dead || done || s.grant <= 0) return;
      const g = s.grant;
      s.grant = 0;
      send(frame(T_WINDOW, s.id, u32(g)));
    });
  };

  /** Reads from the data centre and forwards it as DATA frames, never past the client's credit. */
  const pump = async (s) => {
    try {
      for (;;) {
        const { value, done: ended } = await s.reader.read();
        if (ended || s.dead) break;
        for (let at = 0; at < value.length; ) {
          while (s.credit <= 0 && !s.dead) await new Promise((resolve) => (s.wake = resolve));
          if (s.dead) return;
          const n = Math.min(DATA_CHUNK, value.length - at, s.credit);
          s.credit -= n;
          send(frame(T_DATA, s.id, value.subarray(at, at + n)));
          at += n;
        }
      }
    } catch (e) {
      // fall through: the stream is closed below
    }
    drop(s, true);
  };

  const begin = async (s) => {
    const client = await readClientInit(cfg, s.init);
    if (!TAGS.has(client.tag) || (cfg.padded && client.tag !== 0xdddddddd) || !DATA_CENTRES[Math.abs(client.dc)]) throw new Error('init');
    const toDc = await buildDcInit(client);
    s.socket = await dialDc(dial, client.dc);
    if (s.dead) {
      kill(s);
      return;
    }
    s.writer = s.socket.writable.getWriter();
    s.reader = s.socket.readable.getReader();
    await s.writer.write(toDc.wire);
    s.cli = client.up;
    s.dc = toDc.up;
    s.live = true;
    pump(s);
  };

  const uplink = async (s, chunk) => {
    if (s.dead) return;
    let rest = chunk;
    if (!s.live) {
      const take = Math.min(64 - s.got, rest.length);
      s.init.set(rest.subarray(0, take), s.got);
      s.got += take;
      rest = rest.subarray(take);
      if (s.got < 64) {
        give(s, chunk.length);
        return;
      }
      await begin(s);
      if (s.dead) return;
    }
    if (rest.length) {
      const plain = await ctrApply(s.cli, s.off, rest);
      const sealed = await ctrApply(s.dc, s.off, plain);
      s.off += rest.length;
      await s.writer.write(sealed);
    }
    give(s, chunk.length);
  };

  const onFrame = (f) => {
    if (f.id === 0) return;
    if (f.type === T_OPEN) {
      if (used.has(f.id) || used.size >= MAX_USED) return stop(4001);
      used.add(f.id);
      // Past the budget only this stream is refused; the page has normally moved to a fresh lane.
      if (dials >= opts.maxDials || streams.size >= MAX_STREAMS) return send(frame(T_CLOSE, f.id));
      dials++;
      streams.set(f.id, {
        id: f.id, dead: false, live: false, credit: INITIAL_WINDOW, wake: null, grant: 0, granting: false,
        init: new Uint8Array(64), got: 0, off: 64, cli: null, dc: null, socket: null, reader: null, writer: null,
        chain: Promise.resolve(),
      });
      return;
    }
    const s = streams.get(f.id);
    if (!s) {
      if (!used.has(f.id)) stop(4001);
      return; // a late frame for a stream that already closed
    }
    if (f.type === T_DATA) {
      const chunk = f.payload.slice();
      s.chain = s.chain.then(() => uplink(s, chunk)).catch(() => drop(s, true));
    } else if (f.type === T_CLOSE) {
      drop(s, false);
    } else if (f.type === T_WINDOW) {
      s.credit = Math.min(0x7fffffff, s.credit + new DataView(f.payload.buffer, f.payload.byteOffset, 4).getUint32(0));
      if (s.wake) {
        const w = s.wake;
        s.wake = null;
        w();
      }
    }
  };

  ws.addEventListener('message', (event) => {
    if (done) return;
    const data = event.data;
    if (!(data instanceof ArrayBuffer) || data.byteLength === 0 || data.byteLength > MAX_MESSAGE) return stop(4001);
    let frames;
    try {
      frames = parseFrames(new Uint8Array(data));
      frames.forEach(checkClientFrame);
    } catch (e) {
      return stop(4001);
    }
    for (const f of frames) {
      if (done) return;
      onFrame(f);
    }
  });
  ws.addEventListener('close', () => stop(1000));
  ws.addEventListener('error', () => stop(1000));
  if (opts.pingMs) {
    pinger = setInterval(() => {
      mintToken(cfg, 2).then((t) => send(frame(T_PING, 0, encoder.encode(t))), () => {});
    }, opts.pingMs);
  }
}

export default {
  async fetch(request, env) {
    try {
      return await handle(request, env);
    } catch (e) {
      return notFound();
    }
  },
};
