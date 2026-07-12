// Dedicated DNS relay worker (MLMVPN "DNS اختصاصی").
//
// Purpose: a per-user private resolver, deployed on the user's OWN Cloudflare
// account, that answers game-server DNS lookups while actively controlling the
// EDNS Client Subnet (ECS) option sent upstream -- so the authoritative server
// returns the game IP closest to a chosen region instead of guessing from the
// worker's own colo. Cloudflare's own 1.1.1.1 deliberately strips ECS, so this
// forwards to Google Public DNS (dns.google), which honours a client-supplied
// ECS option (RFC 7871). Transport is plain fetch() with the binary DoH
// wire-format (RFC 8484, application/dns-message) -- no experimental sockets.
//
// Two endpoints on the same worker:
//   GET  /resolve?domain=<host>&region=<code>  -> JSON {"Answer":[{"type":1,"data":"1.2.3.4"}, ...]}
//        (consumed by the app's DedicatedDnsResolver for region racing + ping)
//   GET|POST /dns-query?region=<code>          -> raw application/dns-message
//        (consumed directly by Xray-core's internal DoH client during a session)
//
// Bindings: KV (optional cache namespace), DEFAULT_REGION (plain text, e.g. "AE").

// Region code -> representative ECS subnet. Kept server-side so the client only
// ever sends a short region code, never an arbitrary spoofable subnet. Mirror of
// the DnsRegion table in DedicatedDnsResolver.kt -- keep the two in sync.
const REGIONS = {
  AE: { subnet: "94.200.0.0", prefix: 24 },   // UAE (Etisalat) -- default
  TR: { subnet: "78.160.0.0", prefix: 24 },   // Turkey (Turk Telekom)
  DE: { subnet: "85.214.0.0", prefix: 24 },   // Germany (Hetzner)
  SG: { subnet: "103.28.54.0", prefix: 24 },  // Singapore (APNIC)
  IN: { subnet: "103.21.244.0", prefix: 24 }, // India (APNIC)
  US: { subnet: "23.239.0.0", prefix: 24 },   // US East (Linode Newark)
};

const UPSTREAM = "https://dns.google/dns-query";
const CACHE_TTL_SECONDS = 300; // 5 minutes

export default {
  async fetch(request, env, ctx) {
    try {
      const url = new URL(request.url);
      const path = url.pathname.toLowerCase();
      const defaultRegion = (env.DEFAULT_REGION || "AE").toUpperCase();

      const wantsBinary =
        path.endsWith("/dns-query") ||
        (request.headers.get("Accept") || "").includes("application/dns-message") ||
        (request.headers.get("Content-Type") || "").includes("application/dns-message");

      const regionCode = (url.searchParams.get("region") || defaultRegion).toUpperCase();
      const region = REGIONS[regionCode] || REGIONS[defaultRegion] || REGIONS.AE;

      if (wantsBinary) {
        return await handleDohQuery(request, region);
      }
      return await handleResolve(url, region, regionCode, env, ctx);
    } catch (e) {
      return json({ Answer: [], error: String(e && e.message || e) }, 200);
    }
  },
};

// ---- /resolve : JSON contract (app-side racing/pinging) -------------------

async function handleResolve(url, region, regionCode, env, ctx) {
  const domain = (url.searchParams.get("domain") || "").trim().toLowerCase();
  if (!domain || !/^[a-z0-9.-]+$/.test(domain)) {
    return json({ Answer: [], error: "invalid domain" }, 400);
  }

  const cacheKey = `${regionCode}:${domain}`;
  if (env.KV) {
    try {
      const cached = await env.KV.get(cacheKey, "json");
      if (cached && Array.isArray(cached.Answer)) {
        return json({ Answer: cached.Answer, region: regionCode, cached: true }, 200);
      }
    } catch (_) { /* cache miss is non-fatal */ }
  }

  const query = buildQuery(domain, region);
  const respBytes = await postUpstream(query);
  const ips = respBytes ? parseAnswers(respBytes) : [];
  const answer = ips.map((ip) => ({ type: 1, data: ip }));

  if (env.KV && answer.length > 0) {
    ctx.waitUntil(
      env.KV.put(cacheKey, JSON.stringify({ Answer: answer }), {
        expirationTtl: CACHE_TTL_SECONDS,
      }).catch(() => {})
    );
  }

  const subnetStr = `${region.subnet}/${region.prefix}`;
  return json({ Answer: answer, region: regionCode, ecsSubnet: subnetStr, cached: false }, 200);
}

// ---- /dns-query : binary DoH passthrough (Xray internal resolver) ----------

async function handleDohQuery(request, region) {
  let questionBytes;
  if (request.method === "POST") {
    questionBytes = new Uint8Array(await request.arrayBuffer());
    // Re-wrap the client's query with our ECS option so region steering still
    // applies even when Xray sends its own wire-format query.
    const domain = firstQuestionName(questionBytes);
    if (domain) questionBytes = buildQuery(domain, region);
  } else {
    // GET dns-query carries the query in ?dns=<base64url>
    const url = new URL(request.url);
    const dnsParam = url.searchParams.get("dns");
    if (!dnsParam) return new Response("missing dns param", { status: 400 });
    const raw = base64UrlToBytes(dnsParam);
    const domain = firstQuestionName(raw);
    questionBytes = domain ? buildQuery(domain, region) : raw;
  }

  const respBytes = await postUpstream(questionBytes);
  if (!respBytes) return new Response("upstream failure", { status: 502 });
  return new Response(respBytes, {
    status: 200,
    headers: { "Content-Type": "application/dns-message" },
  });
}

async function postUpstream(queryBytes) {
  try {
    const resp = await fetch(UPSTREAM, {
      method: "POST",
      headers: {
        "Content-Type": "application/dns-message",
        Accept: "application/dns-message",
      },
      body: queryBytes,
    });
    if (!resp.ok) return null;
    return new Uint8Array(await resp.arrayBuffer());
  } catch (_) {
    return null;
  }
}

// ---- DNS wire-format helpers ----------------------------------------------

// Build an A-record query for `domain` with an EDNS0 OPT record carrying an ECS
// option for `region`'s subnet (RFC 7871). Header ARCOUNT=1 for the OPT record.
function buildQuery(domain, region) {
  const bytes = [];

  // Header
  const id = (Math.random() * 0xffff) & 0xffff;
  bytes.push((id >> 8) & 0xff, id & 0xff);
  bytes.push(0x01, 0x00); // flags: standard query, recursion desired
  bytes.push(0x00, 0x01); // QDCOUNT = 1
  bytes.push(0x00, 0x00); // ANCOUNT = 0
  bytes.push(0x00, 0x00); // NSCOUNT = 0
  bytes.push(0x00, 0x01); // ARCOUNT = 1 (OPT record below)

  // Question: labels + type A (1) + class IN (1)
  for (const label of domain.split(".")) {
    if (!label) continue;
    const enc = new TextEncoder().encode(label);
    bytes.push(enc.length);
    for (const b of enc) bytes.push(b);
  }
  bytes.push(0x00); // end of name
  bytes.push(0x00, 0x01); // QTYPE A
  bytes.push(0x00, 0x01); // QCLASS IN

  // Additional: OPT pseudo-record with ECS option
  bytes.push(0x00);       // NAME = root
  bytes.push(0x00, 0x29); // TYPE = 41 (OPT)
  bytes.push(0x10, 0x00); // CLASS = UDP payload size 4096
  bytes.push(0x00, 0x00, 0x00, 0x00); // TTL (extended RCODE/version/flags) = 0

  // RDATA = one ECS option
  const addrBytes = ipv4ToBytes(region.subnet);
  const srcLen = region.prefix;
  const addrLen = Math.ceil(srcLen / 8);
  const optionData = [];
  optionData.push(0x00, 0x01); // FAMILY = 1 (IPv4)
  optionData.push(srcLen & 0xff); // SOURCE PREFIX-LENGTH
  optionData.push(0x00); // SCOPE PREFIX-LENGTH = 0 (query)
  for (let i = 0; i < addrLen; i++) optionData.push(addrBytes[i] || 0);

  const rdata = [];
  rdata.push(0x00, 0x08); // OPTION-CODE = 8 (ECS)
  rdata.push((optionData.length >> 8) & 0xff, optionData.length & 0xff); // OPTION-LENGTH
  for (const b of optionData) rdata.push(b);

  bytes.push((rdata.length >> 8) & 0xff, rdata.length & 0xff); // RDLENGTH
  for (const b of rdata) bytes.push(b);

  return new Uint8Array(bytes);
}

// Extract the first QNAME from a wire-format query (to rebuild it with ECS).
function firstQuestionName(data) {
  try {
    if (data.length < 12) return null;
    let pos = 12;
    const labels = [];
    while (pos < data.length) {
      const len = data[pos++];
      if (len === 0) break;
      if ((len & 0xc0) === 0xc0) return null; // compression not expected in a question
      let label = "";
      for (let i = 0; i < len; i++) label += String.fromCharCode(data[pos++]);
      labels.push(label);
    }
    return labels.length ? labels.join(".").toLowerCase() : null;
  } catch (_) {
    return null;
  }
}

// Collect every A record from a wire-format response.
function parseAnswers(data) {
  const ips = [];
  try {
    if (data.length < 12) return ips;
    const qdcount = (data[4] << 8) | data[5];
    const ancount = (data[6] << 8) | data[7];
    if (ancount === 0) return ips;

    let pos = 12;
    // skip questions
    for (let q = 0; q < qdcount; q++) {
      while (pos < data.length) {
        const len = data[pos++];
        if (len === 0) break;
        pos += len;
      }
      pos += 4; // type + class
    }
    // answers
    for (let a = 0; a < ancount; a++) {
      if (pos >= data.length) break;
      if ((data[pos] & 0xc0) === 0xc0) {
        pos += 2;
      } else {
        while (pos < data.length) {
          const len = data[pos++];
          if (len === 0) break;
          pos += len;
        }
      }
      if (pos + 10 > data.length) break;
      const type = (data[pos] << 8) | data[pos + 1];
      pos += 8; // type(2) + class(2) + ttl(4)
      const rdlength = (data[pos] << 8) | data[pos + 1];
      pos += 2;
      if (type === 1 && rdlength === 4) {
        ips.push(`${data[pos]}.${data[pos + 1]}.${data[pos + 2]}.${data[pos + 3]}`);
      }
      pos += rdlength;
    }
  } catch (_) { /* return whatever parsed so far */ }
  return ips;
}

function ipv4ToBytes(ip) {
  return ip.split(".").map((p) => parseInt(p, 10) & 0xff);
}

function base64UrlToBytes(b64url) {
  const b64 = b64url.replace(/-/g, "+").replace(/_/g, "/");
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

function json(obj, status) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { "Content-Type": "application/json;charset=utf-8" },
  });
}
