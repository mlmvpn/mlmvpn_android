import { connect } from 'cloudflare:sockets';

// ==========================================================
// ۱. حافظه‌های موقت و متغیرهای سراسری (GLOBAL STATE)
// ==========================================================
const GLOBAL_TRAFFIC_CACHE = new Map();
const ACTIVE_CONNECTIONS_COUNT = new Map();
const GLOBAL_LAST_ACTIVE_WRITE = new Map();
const DNS_CACHE = new Map();

// ==========================================================
// ۲. ثوابت و تنظیمات اصلی (CONSTANTS)
// ==========================================================
const DNS_CACHE_TTL = 5 * 60 * 1000;
const DOH_RESOLVER = "https://cloudflare-dns.com/dns-query";
const UPSTREAM_BUNDLE_TARGET_BYTES = 16 * 1024;
const UPSTREAM_QUEUE_MAX_BYTES = 16 * 1024 * 1024;
const UPSTREAM_QUEUE_MAX_ITEMS = 4096;
const DOWNSTREAM_GRAIN_BYTES = 32 * 1024;
const DOWNSTREAM_GRAIN_TAIL_THRESHOLD = 512;
const DOWNSTREAM_GRAIN_SILENT_MS = 1;
const TCP_CONCURRENCY = 2;
const PRELOAD_RACE_DIAL = true;

// ==========================================================
// ۳. نقطه ورود اصلی ورکر (MAIN FETCH HANDLER)
// ==========================================================
export default {
  async fetch(request, env, ctx) {
    await DbService.ensureSchema(env.DB);
    const url = new URL(request.url);

    // بررسی درخواست WebSocket برای اتصال فیلترشکن
    if (Router.isWebSocketUpgrade(request) && url.pathname === '/') {
      return await Router.handleWebSocket(request, env, ctx);
    }

    // مسیرهای مربوط به ساب‌اسکریپشن (Sub / Feed)
    if (Router.isSubscriptionPath(url.pathname)) {
      return await Router.handleSubscription(url, env);
    }

    // مسیرهای مربوط به وب سرویس‌ها (API)
    if (url.pathname.startsWith('/api/') || url.pathname === '/locations') {
      return await Router.handleApi(request, url, env, ctx);
    }

    // لود کردن پوسته مدیریتی پنل ( /login نامِ مستعارِ /panel است )
    if (url.pathname === '/panel' || url.pathname === '/login') {
      return await Router.handlePanel(request, env);
    }

    // لود کردن صفحه وضعیت کاربر
    if (url.pathname.startsWith('/status/')) {
      return await Router.handleUserStatus(url, env);
    }

    // نمایش صفحه فیک Nginx برای تمامی مسیرهای متفرقه
    return new Response(HTML_TEMPLATES.nginx, {
      headers: { "Content-Type": "text/html; charset=utf-8" }
    });
  }
};

// ==========================================================
// ۴. روتر و هدایت‌کننده‌های آدرس (ROUTER & CONTROLLERS)
// ==========================================================
const Router = {
  isWebSocketUpgrade(request) {
    const upgradeHeader = (request.headers.get('Upgrade') || '').toLowerCase();
    return upgradeHeader === 'websocket';
  },

  isSubscriptionPath(pathname) {
    return pathname.startsWith('/sub/') || pathname.startsWith('/feed/');
  },

  async handleWebSocket(request, env, ctx) {
    try {
      let proxyIP = "proxyip.cmliussss.net";
      try {
        const proxyRow = await env.DB.prepare("SELECT value FROM settings WHERE key = 'proxy_ip'").first();
        if (proxyRow && proxyRow.value) {
          proxyIP = proxyRow.value;
        }
      } catch (e) {}

      const mockStoredData = { proxy_ip: proxyIP };
          return handleVLESS(env, mockStoredData, ctx);
        } catch (e) {
      return new Response("Internal Server Error", { status: 500 });
    }
  },

  async handleSubscription(url, env) {
    const isSubPath = url.pathname.startsWith('/sub/');
    const offset = isSubPath ? 5 : 6;
    let subUser = decodeURIComponent(url.pathname.slice(offset));
    const host = url.hostname;

    const isJson = !isSubPath && subUser.startsWith('json/');
    if (isJson) {
      subUser = subUser.slice(5);
    }

    try {
      const user = await env.DB.prepare("SELECT * FROM users WHERE username = ? OR uuid = ?").bind(subUser, subUser).first();
      if (!user || user.connection_type !== atob('dmxlc3M=')) {
        return new Response("Not Found", { status: 404 });
      }

      if (isJson) {
        return await SubscriptionService.generateJson(user, host, env);
      } else {
        return await SubscriptionService.generateText(user, host);
      }
    } catch (err) {
      return new Response("Error building config: " + err.message, { status: 500 });
    }
  },

  async handlePanel(request, env) {
    const hasPassword = await DbService.getPanelPassword(env.DB);
    if (!hasPassword) {
      return new Response(HTML_TEMPLATES.setup, {
        headers: { "Content-Type": "text/html; charset=utf-8" }
      });
    }

    const authorized = await DbService.verifyApiAuth(request, env);
    if (!authorized) {
      return new Response(HTML_TEMPLATES.login, {
        headers: { "Content-Type": "text/html; charset=utf-8" }
      });
    }

    return new Response(HTML_TEMPLATES.panel, {
      headers: { "Content-Type": "text/html; charset=utf-8" }
    });
  },

  async handleUserStatus(url, env) {
    const username = decodeURIComponent(url.pathname.slice(8));
    if (!username) {
      return new Response("Username is required", { status: 400 });
    }
    try {
      const user = await env.DB.prepare("SELECT * FROM users WHERE username = ? OR uuid = ?").bind(username, username).first();
      if (!user) {
        return new Response("User not found", { status: 404 });
      }
      const userJson = JSON.stringify({
        username: user.username,
        uuid: user.uuid,
        limit_gb: user.limit_gb,
        expiry_days: user.expiry_days,
        used_gb: user.used_gb,
        is_active: user.is_active,
        created_at: user.created_at,
        tls: user.tls,
        port: user.port,
        ips: user.ips,
        fingerprint: user.fingerprint || 'chrome'
      });
      const html = HTML_TEMPLATES.status.replace(
        "/* {{USER_DATA_PLACEHOLDER}} */",
        `window.statusUser = ${userJson};`
      );
      return new Response(html, {
        headers: { "Content-Type": "text/html; charset=utf-8" }
      });
    } catch (err) {
      return new Response("Error: " + err.message, { status: 500 });
    }
  },

  async handleApi(request, url, env, ctx) {
    const hasPassword = await DbService.getPanelPassword(env.DB);

    // API: تعریف رمز عبور اولیه
    if (url.pathname === '/api/setup-password' && request.method === 'POST') {
      if (hasPassword) {
        return new Response(JSON.stringify({ error: "رمز عبور از قبل تعریف شده است" }), { 
          status: 400, headers: { "Content-Type": "application/json; charset=utf-8" } 
        });
      }
      const { password } = await request.json();
      if (!password || password.length < 4) {
        return new Response(JSON.stringify({ error: "رمز عبور باید حداقل ۴ کاراکتر باشد" }), { 
          status: 400, headers: { "Content-Type": "application/json; charset=utf-8" } 
        });
      }
      const hashed = await DbService.sha256(password);
      await DbService.setPanelPassword(env.DB, hashed);
      return new Response(JSON.stringify({ success: true }), {
        headers: { 
          "Content-Type": "application/json; charset=utf-8",
          "Set-Cookie": "panel_session=" + hashed + "; Path=/; HttpOnly; Secure; SameSite=Lax"
        }
      });
    }

    // API: ورود به پنل
    if (url.pathname === '/api/login' && request.method === 'POST') {
      const { password } = await request.json();
      const hashedInput = await DbService.sha256(password);
      const storedHash = await DbService.getPanelPassword(env.DB);
      if (storedHash === hashedInput) {
        return new Response(JSON.stringify({ success: true }), {
          headers: { 
            "Content-Type": "application/json; charset=utf-8",
            "Set-Cookie": "panel_session=" + storedHash + "; Path=/; HttpOnly; Secure; SameSite=Lax"
          }
        });
      }
      return new Response(JSON.stringify({ error: "رمز عبور اشتباه است" }), { 
        status: 401, headers: { "Content-Type": "application/json; charset=utf-8" } 
      });
    }

    // API: خروج از پنل
    if (url.pathname === '/api/logout' && request.method === 'POST') {
      return new Response(JSON.stringify({ success: true }), {
        headers: {
          "Content-Type": "application/json; charset=utf-8",
          "Set-Cookie": "panel_session=; Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT; HttpOnly; Secure; SameSite=Lax"
        }
      });
    }

    // بررسی عمومی احراز هویت برای بقیه APIها
    const authorized = await DbService.verifyApiAuth(request, env);
    if (!authorized) {
      return new Response(JSON.stringify({ error: "Unauthorized" }), { 
        status: 401, headers: { "Content-Type": "application/json; charset=utf-8" } 
      });
    }

    // API: تغییر رمز عبور مدیریت
    if (url.pathname === '/api/change-password' && request.method === 'POST') {
      const { current_password, new_password } = await request.json();
      if (!current_password || !new_password) {
        return new Response(JSON.stringify({ error: "رمز عبور فعلی و جدید الزامی هستند" }), { 
          status: 400, headers: { "Content-Type": "application/json; charset=utf-8" } 
        });
      }
      const currentHash = await DbService.sha256(current_password);
      const storedHash = await DbService.getPanelPassword(env.DB);
      if (storedHash && storedHash !== currentHash) {
        return new Response(JSON.stringify({ error: "رمز عبور فعلی اشتباه است" }), { 
          status: 401, headers: { "Content-Type": "application/json; charset=utf-8" } 
        });
      }
      if (new_password.length < 4) {
        return new Response(JSON.stringify({ error: "رمز عبور جدید باید حداقل ۴ کاراکتر باشد" }), { 
          status: 400, headers: { "Content-Type": "application/json; charset=utf-8" } 
        });
      }
      const newHash = await DbService.sha256(new_password);
      await DbService.setPanelPassword(env.DB, newHash);
      return new Response(JSON.stringify({ success: true }), {
        headers: { 
          "Content-Type": "application/json; charset=utf-8",
          "Set-Cookie": "panel_session=" + newHash + "; Path=/; HttpOnly; Secure; SameSite=Lax"
        }
      });
    }

    // API: دریافت موقعیت‌های جغرافیایی کلودفلر
    if (url.pathname === '/locations') {
      try {
        const response = await fetch('https://speed.cloudflare.com/locations', {
          headers: { 'Referer': 'https://speed.cloudflare.com/' }
        });
        const data = await response.json();
        return new Response(JSON.stringify(data), {
          headers: { "Content-Type": "application/json; charset=utf-8", "Access-Control-Allow-Origin": "*" }
        });
      } catch (e) {
        return new Response(JSON.stringify({ error: e.message }), { status: 500, headers: { "Content-Type": "application/json" } });
      }
    }

    // API: تنظیمات آی‌پی پروکسی (GET & POST)
    if (url.pathname === '/api/proxy-ip') {
      if (request.method === 'POST') {
        const { proxy_ip, iata, frag_len, frag_int } = await request.json();
        if (proxy_ip) await env.DB.prepare("INSERT OR REPLACE INTO settings (key, value) VALUES ('proxy_ip', ?)").bind(proxy_ip).run();
        if (iata !== undefined) await env.DB.prepare("INSERT OR REPLACE INTO settings (key, value) VALUES ('proxy_location_iata', ?)").bind(iata).run();
        if (frag_len !== undefined) await env.DB.prepare("INSERT OR REPLACE INTO settings (key, value) VALUES ('frag_len', ?)").bind(frag_len).run();
        if (frag_int !== undefined) await env.DB.prepare("INSERT OR REPLACE INTO settings (key, value) VALUES ('frag_int', ?)").bind(frag_int).run();
        return new Response(JSON.stringify({ success: true }), { headers: { "Content-Type": "application/json" } });
      }

      if (request.method === 'GET') {
        const rowIp = await env.DB.prepare("SELECT value FROM settings WHERE key = 'proxy_ip'").first();
        const rowIata = await env.DB.prepare("SELECT value FROM settings WHERE key = 'proxy_location_iata'").first();
        const rowLen = await env.DB.prepare("SELECT value FROM settings WHERE key = 'frag_len'").first();
        const rowInt = await env.DB.prepare("SELECT value FROM settings WHERE key = 'frag_int'").first();
        return new Response(JSON.stringify({
          proxy_ip: rowIp ? rowIp.value : "proxyip.cmliussss.net",
          iata: rowIata ? rowIata.value : "",
          frag_len: rowLen ? rowLen.value : "20-30",
          frag_int: rowInt ? rowInt.value : "1-2"
        }), { headers: { "Content-Type": "application/json" } });
      }
    }

    // API: مدیریت کاربران
    if (url.pathname.startsWith('/api/users')) {
      const pathParts = url.pathname.split('/');
      const isUserAction = pathParts.length > 3; // /api/users/username

      if (isUserAction) {
        const username = decodeURIComponent(pathParts.pop());
        
        if (request.method === 'PUT') {
          const body = await request.json();
          if (body.toggle_only !== undefined) {
            await env.DB.prepare(
              "UPDATE users SET is_active = CASE WHEN is_active = 1 THEN 0 ELSE 1 END WHERE username = ?"
            ).bind(username).run();
            return new Response(JSON.stringify({ success: true }), { headers: { "Content-Type": "application/json" } });
          } else {
            const { limit_gb, expiry_days, ips, tls, port, fingerprint } = body;
            await env.DB.prepare(
              "UPDATE users SET limit_gb = ?, expiry_days = ?, ips = ?, tls = ?, port = ?, fingerprint = ? WHERE username = ?"
            ).bind(
              limit_gb ? parseFloat(limit_gb) : null, 
              expiry_days ? parseInt(expiry_days) : null, 
              ips || null, 
              tls, 
              port, 
              fingerprint || 'chrome',
              username
            ).run();
            return new Response(JSON.stringify({ success: true }), { headers: { "Content-Type": "application/json" } });
          }
        }

        if (request.method === 'DELETE') {
          await env.DB.prepare("DELETE FROM users WHERE username = ?").bind(username).run();
          return new Response(JSON.stringify({ success: true }), { headers: { "Content-Type": "application/json" } });
        }
      } else {
        if (request.method === 'GET') {
          try {
            await flushExpiredTraffic(env);
          } catch (e) {}
          const { results } = await env.DB.prepare("SELECT * FROM users ORDER BY id DESC").all();
          const now = Date.now();
          const enrichedUsers = (results || []).map(user => ({
            ...user,
            is_online: (user.last_active && (now - user.last_active) < 65000) ? 1 : 0
          }));
          return new Response(JSON.stringify({ users: enrichedUsers, serverTime: now }), {
            headers: { 
              "Content-Type": "application/json", 
              "Cache-Control": "no-store, no-cache, must-revalidate, max-age=0" 
            }
          });
        }

        if (request.method === 'POST') {
          const { username, limit_gb, expiry_days, ips, tls, port, fingerprint } = await request.json();
          if (!username) {
            return new Response(JSON.stringify({ error: "نام کاربری اجباری است" }), { status: 400, headers: { "Content-Type": "application/json" } });
          }
          const uuid = crypto.randomUUID();
          try {
            await env.DB.prepare(
              "INSERT INTO users (username, uuid, limit_gb, expiry_days, ips, connection_type, tls, port, fingerprint) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
            ).bind(
              username, 
              uuid,
              limit_gb ? parseFloat(limit_gb) : null, 
              expiry_days ? parseInt(expiry_days) : null, 
              ips || null, 
              atob('dmxlc3M='), 
              tls, 
              port,
              fingerprint || 'chrome'
            ).run();
            return new Response(JSON.stringify({ success: true }), { headers: { "Content-Type": "application/json" } });
          } catch (err) {
            let errorMsg = err.message;
            if (errorMsg.includes("UNIQUE constraint failed")) {
              errorMsg = "این نام کاربری از قبل وجود دارد.";
            }
            return new Response(JSON.stringify({ error: errorMsg }), { status: 500, headers: { "Content-Type": "application/json" } });
          }
        }
      }
    }

    return new Response(JSON.stringify({ error: "Not Found" }), { status: 404 });
  }
};

// ==========================================================
// ۵. مدیریت دیتابیس و اعتبارسنجی (DATABASE SERVICE)
// ==========================================================
let schemaEnsured = false;
let cachedPanelPassword = null;

const DbService = {
  async ensureSchema(db) {
    if (schemaEnsured) return;
    try {
      await db.prepare(`
        CREATE TABLE IF NOT EXISTS users (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          username TEXT UNIQUE,
          uuid TEXT,
          limit_gb REAL,
          expiry_days INTEGER,
          ips TEXT,
          connection_type TEXT,
          tls TEXT,
          port INTEGER,
          used_gb REAL DEFAULT 0,
          is_active INTEGER DEFAULT 1,
          last_active INTEGER,
          created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        )
      `).run();
    } catch (e) {}
    try { await db.prepare("ALTER TABLE users ADD COLUMN is_active INTEGER DEFAULT 1").run(); } catch (e) {}
    try { await db.prepare("ALTER TABLE users ADD COLUMN last_active INTEGER").run(); } catch (e) {}
    try { await db.prepare("ALTER TABLE users ADD COLUMN fingerprint TEXT DEFAULT 'chrome'").run(); } catch (e) {}
    try { await db.prepare("CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT)").run(); } catch (e) {}
    schemaEnsured = true;
  },

  async getPanelPassword(db) {
    if (cachedPanelPassword !== null) return cachedPanelPassword;
    try {
      const row = await db.prepare("SELECT value FROM settings WHERE key = 'panel_password'").first();
      cachedPanelPassword = row ? row.value : "";
      return cachedPanelPassword || null;
    } catch (e) {
      return null;
    }
  },

  async setPanelPassword(db, password) {
    await db.prepare("INSERT OR REPLACE INTO settings (key, value) VALUES ('panel_password', ?)").bind(password).run();
    cachedPanelPassword = password;
  },

  async verifyApiAuth(request, env) {
    const storedPasswordHash = await this.getPanelPassword(env.DB);
    if (!storedPasswordHash) return true;
    const cookies = request.headers.get('Cookie') || '';
    const sessionCookie = cookies.split(';').find(c => c.trim().startsWith('panel_session='));
    if (!sessionCookie) return false;
    const sessionToken = sessionCookie.split('=')[1].trim();
    return sessionToken === storedPasswordHash;
  },

  async sha256(message) {
    const msgBuffer = new TextEncoder().encode(message);
    const hashBuffer = await crypto.subtle.digest('SHA-256', msgBuffer);
    const hashArray = Array.from(new Uint8Array(hashBuffer));
    return hashArray.map(b => b.toString(16).padStart(2, '0')).join('');
  }
};

// ==========================================================
// ۶. مدیریت تولید کانفیگ‌ها (SUBSCRIPTION SERVICE)
// ==========================================================
const SubscriptionService = {
  async generateJson(user, host, env) {
    let ips = [host];
    if (user.ips) {
      const parsedIps = user.ips.split('\n').map(ip => ip.trim()).filter(ip => ip.length > 0);
      if (parsedIps.length > 0) ips = parsedIps;
    }
    
    const ports = String(user.port || '443').split(',').map(p => p.trim()).filter(p => p.length > 0);
    const fp = user.fingerprint || 'chrome';
    
    let fragLen = "20-30";
    let fragInt = "1-2";
    try {
      const rowLen = await env.DB.prepare("SELECT value FROM settings WHERE key = 'frag_len'").first();
      if (rowLen && rowLen.value) fragLen = rowLen.value;
      const rowInt = await env.DB.prepare("SELECT value FROM settings WHERE key = 'frag_int'").first();
      if (rowInt && rowInt.value) fragInt = rowInt.value;
    } catch(e) {}

    const configArray = [];
    ips.forEach((ip, ipIndex) => {
      ports.forEach((portStr) => {
        const isTlsPort = ['443', '2053', '2083', '2087', '2096', '8443'].includes(portStr);
        const tlsVal = isTlsPort ? 'tls' : 'none';
        const remark = ips.length > 1 ? `${user.username} - IP ${ipIndex + 1} - Port ${portStr}` : `${user.username} - Port ${portStr}`;
        
        const configObj = {
          remarks: remark,
          version: { min: "25.10.15" },
          log: { loglevel: "none" },
          dns: {
            servers: [
              { address: "https://8.8.8.8/dns-query", tag: "remote-dns" },
              { address: "8.8.8.8", domains: ["full:" + host], skipFallback: true }
            ],
            queryStrategy: "UseIP",
            tag: "dns"
          },
          inbounds: [
            {
              listen: "127.0.0.1", port: 10808, protocol: "socks",
              settings: { auth: "noauth", udp: true },
              sniffing: { destOverride: ["http", "tls"], enabled: true, routeOnly: true },
              tag: "mixed-in"
            },
            {
              listen: "127.0.0.1", port: 10853, protocol: "dokodemo-door",
              settings: { address: "1.1.1.1", network: "tcp,udp", port: 53 },
              tag: "dns-in"
            }
          ],
          outbounds: [
            {
              protocol: "vle" + "ss",
              settings: {
                ["vne" + "xt"]: [{
                  address: ip,
                  port: parseInt(portStr),
                  users: [{ id: user.uuid, encryption: "none" }]
                }]
              },
              ["stream" + "Settings"]: {
                network: "ws",
                ["ws" + "Settings"]: { host: host, path: "/" },
                security: tlsVal,
                sockopt: { ["dialer" + "Proxy"]: "fragment" }
              },
              tag: "proxy"
            },
            {
              protocol: "freedom",
              settings: {
                fragment: { packets: "tlshello", length: fragLen, interval: fragInt }
              },
              ["stream" + "Settings"]: {
                sockopt: {
                  domainStrategy: "UseIP",
                  happyEyeballs: { tryDelayMs: 250, prioritizeIPv6: false, interleave: 2, maxConcurrentTry: 4 }
                }
              },
              tag: "fragment"
            },
            { protocol: "dns", settings: { nonIPQuery: "reject" }, tag: "dns-out" },
            { protocol: "freedom", settings: { domainStrategy: "UseIP" }, tag: "direct" },
            { protocol: "blackhole", settings: { response: { type: "http" } }, tag: "block" }
          ],
          routing: {
            domainStrategy: "IPIfNonMatch",
            rules: [
              { inboundTag: ["mixed-in"], port: 53, outboundTag: "dns-out", type: "field" },
              { inboundTag: ["dns-in"], outboundTag: "dns-out", type: "field" },
              { inboundTag: ["remote-dns"], outboundTag: "proxy", type: "field" },
              { inboundTag: ["dns"], outboundTag: "direct", type: "field" },
              { domain: ["geosite:private"], outboundTag: "direct", type: "field" },
              { ip: ["geoip:private"], outboundTag: "direct", type: "field" },
              { network: "udp", outboundTag: "block", type: "field" },
              { network: "tcp", outboundTag: "proxy", type: "field" }
            ]
          }
        };

        if (tlsVal === 'tls') {
          configObj.outbounds[0]["stream" + "Settings"]["tls" + "Settings"] = {
            serverName: host,
            fingerprint: fp,
            alpn: ["http/1.1"],
            allowInsecure: false
          };
        }
        configArray.push(configObj);
      });
    });

    return new Response(JSON.stringify(configArray, null, 2), {
      headers: { 
        "Content-Type": "text/plain; charset=utf-8",
        "Access-Control-Allow-Origin": "*",
        "Cache-Control": "no-store"
      }
    });
  },

  async generateText(user, host) {
    let ips = [host];
    if (user.ips) {
      const parsedIps = user.ips.split('\n').map(ip => ip.trim()).filter(ip => ip.length > 0);
      if (parsedIps.length > 0) ips = parsedIps;
    }
    const ports = String(user.port || '443').split(',').map(p => p.trim()).filter(p => p.length > 0);
    const fp = user.fingerprint || 'chrome';
    const links = [];

    ips.forEach((ip, ipIndex) => {
      ports.forEach((portStr) => {
        const isTlsPort = ['443', '2053', '2083', '2087', '2096', '8443'].includes(portStr);
        const tlsVal = isTlsPort ? 'tls' : 'none';
        const remark = ips.length > 1 
          ? `${user.username}-${ipIndex + 1}-${portStr}` 
          : `${user.username}-${portStr}`;
        
        links.push(atob('dmxlc3M6Ly8=') + user.uuid + '@' + ip + ':' + portStr + '?path=%2F&security=' + tlsVal + '&encryption=none&insecure=0&host=' + host + '&fp=' + fp + '&type=ws&allowInsecure=0&sni=' + host + '#' + encodeURIComponent(remark));
      });
    });

    const noise = [
      "# System Update Feed: OK",
      "# Sync Code: " + Math.random().toString(36).slice(2, 10),
      "# Version: 2.10.1",
      "# Description: Secure Node Configurations",
      ""
    ].join('\n');

    const plainContent = noise + links.join('\n');
    const subContent = btoa(unescape(encodeURIComponent(plainContent)));

    return new Response(subContent, {
      headers: { 
        "Content-Type": "text/plain; charset=utf-8",
        "Access-Control-Allow-Origin": "*",
        "Cache-Control": "no-store"
      }
    });
  }
};

// ==========================================================
// ۷. موتور اتصال فیلترشکن و مدیریت ترافیک (VLESS CORE ENGINE)
// ==========================================================
async function flushExpiredTraffic(env) {
  const now = Date.now();
  for (const [uname, cachedBytes] of GLOBAL_TRAFFIC_CACHE.entries()) {
    if (cachedBytes <= 0) continue;
    const lastActive = GLOBAL_LAST_ACTIVE_WRITE.get(uname) || 0;
    const activeCount = ACTIVE_CONNECTIONS_COUNT.get(uname) || 0;
    if (activeCount <= 0 || (now - lastActive > 65000)) {
      GLOBAL_TRAFFIC_CACHE.set(uname, 0);
      const deltaGb = cachedBytes / (1024 * 1024 * 1024);
      try {
        await env.DB.prepare("UPDATE users SET used_gb = used_gb + ? WHERE username = ?").bind(deltaGb, uname).run();
      } catch (e) {
        let recovered = GLOBAL_TRAFFIC_CACHE.get(uname) || 0;
        GLOBAL_TRAFFIC_CACHE.set(uname, recovered + cachedBytes);
      }
    }
  }
}

async function handleVLESS(env, storedData = null, ctx = null) {
  const socketPair = new WebSocketPair();
  const [clientSock, serverSock] = Object.values(socketPair);
  serverSock.accept();
  serverSock.binaryType = 'arraybuffer';

  let username = null;
  let tickCount = 0;
  let validUUID = null;

  function addBytes(bytes) {
    if (bytes <= 0 || !username) return;
    
    let current = GLOBAL_TRAFFIC_CACHE.get(username) || 0;
    current += bytes;
    
    GLOBAL_LAST_ACTIVE_WRITE.set(username, Date.now());
    
    const threshold = 50 * 1024 * 1024;
    if (current >= threshold) { 
      const chunksOf50MB = Math.floor(current / threshold);
      const bytesToCommit = chunksOf50MB * threshold;
      const deltaGb = bytesToCommit / (1024 * 1024 * 1024);
      const leftover = current - bytesToCommit;
      
      GLOBAL_TRAFFIC_CACHE.set(username, leftover); 

      const writeTask = async () => {
        try {
          await env.DB.prepare("UPDATE users SET used_gb = used_gb + ? WHERE username = ?").bind(deltaGb, username).run();
        } catch (e) {
          let recovered = GLOBAL_TRAFFIC_CACHE.get(username) || 0;
          GLOBAL_TRAFFIC_CACHE.set(username, recovered + bytesToCommit);
        }
      };

      if (ctx) {
        ctx.waitUntil(writeTask());
      } else {
        writeTask();
      }
    } else {
      GLOBAL_TRAFFIC_CACHE.set(username, current);
    }
  }

  let isOfflineSet = false;
  const setOffline = () => {
    if (isOfflineSet) return;
    isOfflineSet = true;
    
    const uname = username;
    if (!uname) return;

    let activeCount = ACTIVE_CONNECTIONS_COUNT.get(uname) || 1;
    activeCount = activeCount - 1;
    
    if (activeCount <= 0) {
      ACTIVE_CONNECTIONS_COUNT.delete(uname);
      let cachedBytes = GLOBAL_TRAFFIC_CACHE.get(uname) || 0;
      if (cachedBytes > 0) {
        GLOBAL_TRAFFIC_CACHE.set(uname, 0);
        const deltaGb = cachedBytes / (1024 * 1024 * 1024);
        
        const writeTask = async () => {
          try {
            await env.DB.prepare("UPDATE users SET used_gb = used_gb + ? WHERE username = ?").bind(deltaGb, uname).run();
          } catch (e) {
            let recovered = GLOBAL_TRAFFIC_CACHE.get(uname) || 0;
            GLOBAL_TRAFFIC_CACHE.set(uname, recovered + cachedBytes);
          }
        };
        
        if (ctx) {
          ctx.waitUntil(writeTask());
        } else {
          writeTask();
        }
      }
    } else {
      ACTIVE_CONNECTIONS_COUNT.set(uname, activeCount);
    }
  };

  const heartbeat = setInterval(async () => {
    if (serverSock.readyState === WebSocket.OPEN) {
      try {
        serverSock.send(new Uint8Array(0));
        if (!validUUID) return;
        
        tickCount++;
        if (tickCount >= 4) {
          tickCount = 0;
          const user = await env.DB.prepare("SELECT is_active, limit_gb, used_gb, expiry_days, created_at FROM users WHERE uuid = ?").bind(validUUID).first();
          
          let isExpired = false;
          if (!user || user.is_active === 0) {
            isExpired = true;
          } else {
            if (user.limit_gb && user.used_gb >= user.limit_gb) {
              isExpired = true;
            }
            if (user.expiry_days && user.created_at) {
              const created = new Date(user.created_at);
              const expiryDate = new Date(created.getTime() + (user.expiry_days * 24 * 60 * 60 * 1000));
              if (new Date() > expiryDate) {
                isExpired = true;
              }
            }
          }

          if (isExpired) {
            await env.DB.prepare("UPDATE users SET is_active = 0, last_active = 0 WHERE uuid = ?").bind(validUUID).run();
            clearInterval(heartbeat);
            closeSocketQuietly(serverSock);
            return;
          }

          const now = Date.now();
          const lastRecorded = GLOBAL_LAST_ACTIVE_WRITE.get(username) || 0;
          if (now - lastRecorded > 60000) {
            GLOBAL_LAST_ACTIVE_WRITE.set(username, now);
            await env.DB.prepare("UPDATE users SET last_active = ? WHERE username = ?").bind(now, username).run();
          }
        }
      } catch (e) {}
    } else {
      clearInterval(heartbeat);
    }
  }, 15000);

  let remoteConnWrapper = { socket: null, connectingPromise: null, retryConnect: null };
  let reqUUID = null;
  let isHeaderParsed = false;
  let isDnsQuery = false;
  let chunkBuffer = new Uint8Array(0);
  const proxyIP = storedData?.proxy_ip || "proxyip.cmliussss.net";

  let wsChain = Promise.resolve();
  let wsStopped = false, wsFailed = false, wsFinished = false;
  let wsQueueBytes = 0, wsQueueItems = 0;
  let currentSocketWriter = null, activeRemoteWriter = null;

  const releaseRemoteWriter = () => {
    if (activeRemoteWriter) {
      try { activeRemoteWriter.releaseLock(); } catch (e) {}
      activeRemoteWriter = null;
    }
    currentSocketWriter = null;
  };

  const getRemoteWriter = () => {
    const s = remoteConnWrapper.socket;
    if (!s) return null;
    if (s !== currentSocketWriter) {
      releaseRemoteWriter();
      currentSocketWriter = s;
      activeRemoteWriter = s.writable.getWriter();
    }
    return activeRemoteWriter;
  };

  const upstreamQueue = createUpstreamQueue({
    getWriter: getRemoteWriter,
    releaseWriter: releaseRemoteWriter,
    retryConnect: async () => {
      if (typeof remoteConnWrapper.retryConnect === 'function') {
        await remoteConnWrapper.retryConnect();
      }
    },
    closeConnection: () => {
      try { remoteConnWrapper.socket?.close(); } catch (e) {}
      closeSocketQuietly(serverSock);
    },
    name: 'VlessWSQueue'
  });

  const writeToRemote = async (chunk, allowRetry = true) => {
    return upstreamQueue.writeAndAwait(chunk, allowRetry);
  };

  const processWsMessage = async (chunk) => {
    const bytes = chunk.byteLength || 0;
    await addBytes(bytes);

    if (isDnsQuery) {
      await forwardVlessUDP(chunk, serverSock, null);
      return;
    }

    if (await writeToRemote(chunk)) return;

    if (!isHeaderParsed) {
      chunkBuffer = concatBytes(chunkBuffer, chunk);
      if (chunkBuffer.byteLength < 24) return;

      reqUUID = extractUUIDFromVless(chunkBuffer);
      if (!reqUUID) {
        serverSock.close();
        return;
      }

      let user = null;
      try {
        user = await env.DB.prepare("SELECT * FROM users WHERE uuid = ?").bind(reqUUID).first();
      } catch (e) {}

      if (!user || user.is_active === 0) {
        serverSock.close();
        return;
      }

      if (user.limit_gb && user.used_gb >= user.limit_gb) {
        serverSock.close();
        return;
      }

      if (user.expiry_days && user.created_at) {
        const created = new Date(user.created_at);
        const expiryDate = new Date(created.getTime() + (user.expiry_days * 24 * 60 * 60 * 1000));
        if (new Date() > expiryDate) {
          try {
            await env.DB.prepare("UPDATE users SET is_active = 0, last_active = 0 WHERE uuid = ?").bind(reqUUID).run();
          } catch (e) {}
          serverSock.close();
          return;
        }
      }

      validUUID = reqUUID;
      username = user.username;
      isHeaderParsed = true;

      let activeCount = ACTIVE_CONNECTIONS_COUNT.get(username) || 0;
      ACTIVE_CONNECTIONS_COUNT.set(username, activeCount + 1);
      if (activeCount === 0) {
        const setOnlineTask = async () => {
          try {
            const now = Date.now();
            GLOBAL_LAST_ACTIVE_WRITE.set(username, now);
            await env.DB.prepare("UPDATE users SET last_active = ? WHERE username = ?").bind(now, username).run();
          } catch (e) {}
        };
        if (ctx) ctx.waitUntil(setOnlineTask());
        else setOnlineTask();
      }

      try {
        let offset = 17;
        const optLen = chunkBuffer[offset++];
        offset += optLen;
        const cmd = chunkBuffer[offset++];
        const port = (chunkBuffer[offset++] << 8) | chunkBuffer[offset++];
        const addrType = chunkBuffer[offset++];

        let addr = '';
        if (addrType === 1) {
          addr = `${chunkBuffer[offset++]}.${chunkBuffer[offset++]}.${chunkBuffer[offset++]}.${chunkBuffer[offset++]}`;
        } else if (addrType === 2) {
          const domainLen = chunkBuffer[offset++];
          addr = new TextDecoder().decode(chunkBuffer.slice(offset, offset + domainLen));
          offset += domainLen;
        } else if (addrType === 3) {
          offset += 16;
          addr = "ipv6-unsupported";
        }

        const rawData = chunkBuffer.slice(offset);
        const respHeader = new Uint8Array([chunkBuffer[0], 0]);

        if (cmd === 2) {
          if (port === 53) {
            isDnsQuery = true;
            await forwardVlessUDP(rawData, serverSock, respHeader);
          } else {
            serverSock.close();
          }
          return;
        }

        const connectTCP = async (dataPayload = null, useFallback = true) => {
          if (remoteConnWrapper.connectingPromise) {
            await remoteConnWrapper.connectingPromise;
            return;
          }
          const task = (async () => {
            let s = null;
            try {
              s = await connectDirect(addr, port, dataPayload);
            } catch (err) {
              if (useFallback && proxyIP) {
                s = await connectDirect(proxyIP, port, dataPayload);
              } else {
                throw err;
              }
            }
            remoteConnWrapper.socket = s; 
            s.closed.catch(() => {}).finally(() => closeSocketQuietly(serverSock));
            connectStreams(s, serverSock, respHeader, null, (b) => { addBytes(b); });
          })();
          remoteConnWrapper.connectingPromise = task;
          try {
            await task;
          } finally {
            if (remoteConnWrapper.connectingPromise === task) {
              remoteConnWrapper.connectingPromise = null;
            }
          }
        };

        remoteConnWrapper.retryConnect = async () => connectTCP(null, false);
        await connectTCP(rawData, true);

      } catch (e) {
        serverSock.close();
      }
    }
  };

  const handleWsError = (err) => {
    if (wsFailed) return;
    wsFailed = true;
    wsStopped = true;
    wsQueueBytes = 0;
    wsQueueItems = 0;
    upstreamQueue.clear();
    releaseRemoteWriter();
    closeSocketQuietly(serverSock);
    setOffline();
  };

  const pushToChain = (task) => {
    wsChain = wsChain.then(task).catch(handleWsError);
  };

  serverSock.addEventListener('message', (event) => {
    if (wsStopped || wsFailed) return;
    const size = event.data.byteLength || 0;
    const nextBytes = wsQueueBytes + size;
    const nextItems = wsQueueItems + 1;
    if (nextBytes > UPSTREAM_QUEUE_MAX_BYTES || nextItems > UPSTREAM_QUEUE_MAX_ITEMS) {
      handleWsError(new Error('ws queue overflow'));
      return;
    }
    wsQueueBytes = nextBytes;
    wsQueueItems = nextItems;
    pushToChain(async () => {
      wsQueueBytes = Math.max(0, wsQueueBytes - size);
      wsQueueItems = Math.max(0, wsQueueItems - 1);
      if (wsFailed) return;
      await processWsMessage(event.data);
    });
  });

  serverSock.addEventListener('close', () => {
    clearInterval(heartbeat);
    closeSocketQuietly(serverSock);
    setOffline();
    if (wsFinished) return;
    wsFinished = true;
    wsStopped = true;
    pushToChain(async () => {
      if (wsFailed) return;
      await upstreamQueue.awaitEmpty();
      releaseRemoteWriter();
    });
  });

  serverSock.addEventListener('error', (err) => {
    handleWsError(err);
  });

  return new Response(null, { status: 101, webSocket: clientSock });
}

// ==========================================================
// ۸. توابع کمکی موتور VLESS (UTILITIES & HELPERS)
// ==========================================================
function isIPv4(value) {
  const parts = String(value || '').split('.');
  return parts.length === 4 && parts.every(part => /^\d{1,3}$/.test(part) && Number(part) >= 0 && Number(part) <= 255);
}

function stripIPv6Brackets(hostname = '') {
  const host = String(hostname || '').trim();
  return host.startsWith('[') && host.endsWith(']') ? host.slice(1, -1) : host;
}

function isIPHostname(hostname = '') {
  const host = stripIPv6Brackets(hostname);
  if (isIPv4(host)) return true;
  if (!host.includes(':')) return false;
  try {
    new URL(`http://[${host}]/`);
    return true;
  } catch (e) {
    return false;
  }
}

function convertToUint8Array(data) {
  if (data instanceof Uint8Array) return data;
  if (data instanceof ArrayBuffer) return new Uint8Array(data);
  if (ArrayBuffer.isView(data)) return new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
  return new Uint8Array(data || 0);
}

function concatBytes(...chunkList) {
  const chunks = chunkList.map(convertToUint8Array);
  const total = chunks.reduce((sum, c) => sum + c.byteLength, 0);
  const result = new Uint8Array(total);
  let offset = 0;
  for (const c of chunks) {
    result.set(c, offset);
    offset += c.byteLength;
  }
  return result;
}

function closeSocketQuietly(socket) {
  try {
    if (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CLOSING) {
      socket.close();
    }
  } catch (e) {}
}

async function dohQuery(domain, recordType) { 
  const cacheKey = `${domain}:${recordType}`;
  if (DNS_CACHE.has(cacheKey)) {
    const cached = DNS_CACHE.get(cacheKey);
    if (Date.now() < cached.expires) return cached.data;
    DNS_CACHE.delete(cacheKey);
  }
  try {
    const typeMap = { 'A': 1, 'AAAA': 28 };
    const qtype = typeMap[recordType.toUpperCase()] || 1;

    const encodeDomain = (name) => {
      const parts = name.endsWith('.') ? name.slice(0, -1).split('.') : name.split('.');
      const bufs = [];
      for (const label of parts) {
        const enc = new TextEncoder().encode(label);
        bufs.push(new Uint8Array([enc.length]), enc);
      }
      bufs.push(new Uint8Array([0]));
      return concatBytes(...bufs);
    };

    const qname = encodeDomain(domain);
    const query = new Uint8Array(12 + qname.length + 4);
    const qview = new DataView(query.buffer);
    qview.setUint16(0, crypto.getRandomValues(new Uint16Array(1))[0]);
    qview.setUint16(2, 0x0100); 
    qview.setUint16(4, 1); 
    query.set(qname, 12);
    qview.setUint16(12 + qname.length, qtype);
    qview.setUint16(12 + qname.length + 2, 1);

    const response = await fetch(DOH_RESOLVER, { 
      method: 'POST',
      headers: {
        'Content-Type': 'application/dns-message',
        'Accept': 'application/dns-message',
      },
      body: query,
    });

    if (!response.ok) return [];

    const buf = new Uint8Array(await response.arrayBuffer());
    const dv = new DataView(buf.buffer);
    const qdcount = dv.getUint16(4);
    const ancount = dv.getUint16(6);

    const parseName = (pos) => {
      const labels = [];
      let p = pos, jumped = false, endPos = -1, safe = 128;
      while (p < buf.length && safe-- > 0) {
        const len = buf[p];
        if (len === 0) { if (!jumped) endPos = p + 1; break; }
        if ((len & 0xC0) === 0xC0) {
          if (!jumped) endPos = p + 2;
          p = ((len & 0x3F) << 8) | buf[p + 1];
          jumped = true;
          continue;
        } 
        labels.push(new TextDecoder().decode(buf.slice(p + 1, p + 1 + len)));
        p += len + 1;
      }
      if (endPos === -1) endPos = p + 1;
      return [labels.join('.'), endPos];
    };

    let offset = 12;
    for (let i = 0; i < qdcount; i++) {
      const [, end] = parseName(offset);
      offset = Number(end) + 4;
    }

    const answers = [];
    for (let i = 0; i < ancount && offset < buf.length; i++) {
      const [name, nameEnd] = parseName(offset);
      offset = Number(nameEnd);
      const type = dv.getUint16(offset); offset += 2;
      offset += 2; 
      const ttl = dv.getUint32(offset); offset += 4;
      const rdlen = dv.getUint16(offset); offset += 2;
      const rdata = buf.slice(offset, offset + rdlen);
      offset += rdlen;

      let data;
      if (type === 1 && rdlen === 4) {
        data = `${rdata[0]}.${rdata[1]}.${rdata[2]}.${rdata[3]}`;
      } else if (type === 28 && rdlen === 16) {
        const segs = [];
        for (let j = 0; j < 16; j += 2) segs.push(((rdata[j] << 8) | rdata[j + 1]).toString(16));
        data = segs.join(':');
      } else {
        data = Array.from(rdata).map(b => b.toString(16).padStart(2, '0')).join('');
      }
      answers.push({ name, type, TTL: ttl, data });
    }
    DNS_CACHE.set(cacheKey, { data: answers, expires: Date.now() + DNS_CACHE_TTL });
    return answers;
  } catch (e) {
    return [];
  }
}

function createUpstreamQueue({ getWriter, releaseWriter, retryConnect, closeConnection, name = 'UpstreamQueue' }) {
  let chunks = [];
  let head = 0;
  let queuedBytes = 0;
  let draining = false;
  let closed = false;
  let bundleBuffer = null;
  let idleResolvers = [];
  let activeCompletions = null;

  const settleCompletions = (completions, err = null) => {
    if (!completions) return;
    for (const comp of completions) {
      if (comp) {
        if (err) comp.reject(err);
        else comp.resolve();
      }
    }
  };

  const rejectQueued = (err) => {
    for (let i = head; i < chunks.length; i++) {
      const item = chunks[i];
      if (item && item.completions) settleCompletions(item.completions, err);
    }
  };

  const compact = () => {
    if (head > 32 && head * 2 >= chunks.length) {
      chunks = chunks.slice(head);
      head = 0;
    }
  };

  const resolveIdle = () => {
    if (queuedBytes || draining || !idleResolvers.length) return;
    const resolvers = idleResolvers;
    idleResolvers = [];
    for (const resolve of resolvers) resolve();
  };

  const clear = (err = null) => {
    const closeErr = err || (closed ? new Error(`${name}: queue closed`) : null);
    if (closeErr) {
      rejectQueued(closeErr);
      settleCompletions(activeCompletions, closeErr);
      activeCompletions = null;
    }
    chunks = [];
    head = 0;
    queuedBytes = 0;
    resolveIdle();
  };

  const shift = () => {
    if (head >= chunks.length) return null;
    const item = chunks[head];
    chunks[head++] = undefined;
    queuedBytes -= item.chunk.byteLength;
    compact();
    return item;
  };

  const bundle = () => {
    const first = shift();
    if (!first) return null;
    if (head >= chunks.length || first.chunk.byteLength >= UPSTREAM_BUNDLE_TARGET_BYTES) return first;

    let byteLength = first.chunk.byteLength;
    let end = head;
    let allowRetry = first.allowRetry;
    let completions = first.completions || null;
    while (end < chunks.length) {
      const next = chunks[end];
      const nextLength = byteLength + next.chunk.byteLength;
      if (nextLength > UPSTREAM_BUNDLE_TARGET_BYTES) break;
      byteLength = nextLength;
      allowRetry = allowRetry && next.allowRetry;
      if (next.completions) completions = completions ? completions.concat(next.completions) : next.completions;
      end++;
    }
    if (end === head) return first;

    const output = (bundleBuffer ||= new Uint8Array(UPSTREAM_BUNDLE_TARGET_BYTES));
    output.set(first.chunk);
    let offset = first.chunk.byteLength;
    while (head < end) {
      const next = chunks[head];
      chunks[head++] = undefined;
      queuedBytes -= next.chunk.byteLength;
      output.set(next.chunk, offset);
      offset += next.chunk.byteLength;
    }
    compact();
    return { chunk: output.subarray(0, byteLength), allowRetry, completions };
  };

  const drain = async () => {
    if (draining || closed) return;
    draining = true;
    try {
      for (; ;) {
        if (closed) break;
        const item = bundle();
        if (!item) break;
        let writer = getWriter();
        if (!writer) throw new Error(`${name}: remote writer unavailable`);
        const completions = item.completions || null;
        activeCompletions = completions;
        try {
          try {
            await writer.write(item.chunk);
          } catch (err) {
            releaseWriter?.();
            if (!item.allowRetry || typeof retryConnect !== 'function') throw err;
            await retryConnect();
            writer = getWriter();
            if (!writer) throw err;
            await writer.write(item.chunk);
          }
          settleCompletions(completions);
        } catch (err) {
          settleCompletions(completions, err);
          throw err;
        } finally {
          if (activeCompletions === completions) activeCompletions = null;
        }
      }
    } catch (err) {
      closed = true;
      clear(err);
      try { closeConnection?.(err); } catch (_) {}
    } finally {
      draining = false;
      if (!closed && head < chunks.length) queueMicrotask(drain);
      else resolveIdle();
    }
  };

  const enqueue = (data, allowRetry = true, waitForFlush = false) => {
    if (closed) return false;
    if (!getWriter()) return false;
    const chunk = convertToUint8Array(data);
    if (!chunk.byteLength) return true;
    const nextBytes = queuedBytes + chunk.byteLength;
    const nextItems = chunks.length - head + 1;
    if (nextBytes > UPSTREAM_QUEUE_MAX_BYTES || nextItems > UPSTREAM_QUEUE_MAX_ITEMS) {
      closed = true;
      const err = Object.assign(new Error(`${name}: upload queue overflow (${nextBytes}B/${nextItems})`), { isQueueOverflow: true });
      clear(err);
      try { closeConnection?.(err); } catch (_) {}
      throw err;
    }
    let completionPromise = null;
    let completions = null;
    if (waitForFlush) {
      completions = [];
      completionPromise = new Promise((resolve, reject) => completions.push({ resolve, reject }));
    }
    chunks.push({ chunk, allowRetry, completions });
    queuedBytes = nextBytes;
    if (!draining) queueMicrotask(drain);
    return waitForFlush ? completionPromise.then(() => true) : true;
  };

  return {
    writeAndAwait(data, allowRetry = true) { return enqueue(data, allowRetry, true); },
    async awaitEmpty() {
      if (!queuedBytes && !draining) return;
      await new Promise(resolve => idleResolvers.push(resolve));
    },
    clear() { closed = true; clear(); }
  };
}

function createDownstreamSender(webSocket, headerData = null) {
  const packetCap = DOWNSTREAM_GRAIN_BYTES;
  const tailBytes = DOWNSTREAM_GRAIN_TAIL_THRESHOLD;
  const lowWaterBytes = Math.max(4096, tailBytes << 3);
  let header = headerData;
  let pendingBuffer = new Uint8Array(packetCap);
  let pendingBytes = 0;
  let flushTimer = null;
  let microtaskQueued = false;
  let generation = 0;
  let scheduledGeneration = 0;
  let waitRounds = 0;
  let flushPromise = null;

  const sendRawChunk = async (chunk) => {
    if (webSocket.readyState !== WebSocket.OPEN) throw new Error('ws.readyState is not open');
    webSocket.send(chunk);
  };

  const attachResponseHeader = (chunk) => {
    if (!header) return chunk;
    const merged = new Uint8Array(header.length + chunk.byteLength);
    merged.set(header, 0);
    merged.set(chunk, header.length);
    header = null;
    return merged;
  };

  const flush = async () => {
    while (flushPromise) await flushPromise;
    if (flushTimer) clearTimeout(flushTimer);
    flushTimer = null;
    microtaskQueued = false;
    if (!pendingBytes) return;
    const output = pendingBuffer.subarray(0, pendingBytes).slice();
    pendingBuffer = new Uint8Array(packetCap);
    pendingBytes = 0;
    waitRounds = 0;
    flushPromise = sendRawChunk(output).finally(() => { flushPromise = null; });
    return flushPromise;
  };

  const scheduleFlush = () => {
    if (flushTimer || microtaskQueued) return;
    microtaskQueued = true;
    scheduledGeneration = generation;
    queueMicrotask(() => {
      microtaskQueued = false;
      if (!pendingBytes || flushTimer) return;
      if (packetCap - pendingBytes < tailBytes) {
        flush().catch(() => closeSocketQuietly(webSocket));
        return;
      }
      flushTimer = setTimeout(() => {
        flushTimer = null;
        if (!pendingBytes) return;
        if (packetCap - pendingBytes < tailBytes) {
          flush().catch(() => closeSocketQuietly(webSocket));
          return;
        }
        if (waitRounds < 2 && (generation !== scheduledGeneration || pendingBytes < lowWaterBytes)) {
          waitRounds++;
          scheduledGeneration = generation;
          scheduleFlush();
          return;
        }
        flush().catch(() => closeSocketQuietly(webSocket));
      }, Math.max(DOWNSTREAM_GRAIN_SILENT_MS, 1));
    });
  };

  return {
    async sendDirect(data) {
      let chunk = convertToUint8Array(data);
      if (!chunk.byteLength) return;
      chunk = attachResponseHeader(chunk);
      await sendRawChunk(chunk);
    },
    async send(data) {
      let chunk = convertToUint8Array(data);
      if (!chunk.byteLength) return;
      chunk = attachResponseHeader(chunk);
      let offset = 0;
      const totalBytes = chunk.byteLength;
      while (offset < totalBytes) {
        if (!pendingBytes && totalBytes - offset >= packetCap) {
          const sendBytes = Math.min(packetCap, totalBytes - offset);
          const view = offset || sendBytes !== totalBytes ? chunk.subarray(offset, offset + sendBytes) : chunk;
          await sendRawChunk(view);
          offset += sendBytes;
          continue;
        }
        const copyBytes = Math.min(packetCap - pendingBytes, totalBytes - offset);
        pendingBuffer.set(chunk.subarray(offset, offset + copyBytes), pendingBytes);
        pendingBytes += copyBytes;
        offset += copyBytes;
        generation++;
        if (pendingBytes === packetCap || packetCap - pendingBytes < tailBytes) await flush();
        else scheduleFlush();
      }
    },
    flush
  };
}

async function waitForBackpressure(ws) {
  if (typeof ws.bufferedAmount === 'number') {
    while (ws.bufferedAmount > 256 * 1024) {
      await new Promise(r => setTimeout(r, 100));
    }
  }
}

async function connectStreams(remoteSocket, webSocket, headerData, retryFunc, onBytes) {
  let header = headerData, hasData = false, reader, useBYOB = false;
  const BYOB_LIMIT = 64 * 1024;
  const downstreamSender = createDownstreamSender(webSocket, header);
  header = null;

  try { 
    reader = remoteSocket.readable.getReader({ mode: 'byob' }); 
    useBYOB = true; 
  } catch (e) { 
    reader = remoteSocket.readable.getReader(); 
  }

  try {
    if (!useBYOB) {
      while (true) {
        await waitForBackpressure(webSocket);
        const { done, value } = await reader.read();
        if (done) break;
        if (!value || value.byteLength === 0) continue;
        hasData = true;
        if (typeof onBytes === 'function') onBytes(value.byteLength);
        await downstreamSender.send(value);
      }
    } else {
      let readBuffer = new ArrayBuffer(BYOB_LIMIT);
      while (true) {
        await waitForBackpressure(webSocket);
        const { done, value } = await reader.read(new Uint8Array(readBuffer, 0, BYOB_LIMIT));
        if (done) break;
        if (!value || value.byteLength === 0) continue;
        hasData = true;
        if (typeof onBytes === 'function') onBytes(value.byteLength);
        if (value.byteLength >= DOWNSTREAM_GRAIN_BYTES) {
          await downstreamSender.flush();
          await downstreamSender.sendDirect(value);
          readBuffer = new ArrayBuffer(BYOB_LIMIT);
        } else {
          await downstreamSender.send(value);
          readBuffer = value.buffer.byteLength >= BYOB_LIMIT ? value.buffer : new ArrayBuffer(BYOB_LIMIT);
        }
      }
    }
    await downstreamSender.flush();
  } catch (err) { 
    closeSocketQuietly(webSocket);
  } finally { 
    try { reader.cancel(); } catch (e) {} 
    try { reader.releaseLock(); } catch (e) {} 
  }
  if (!hasData && retryFunc) await retryFunc();
}

async function buildRaceCandidates(address, port) {
  if (!PRELOAD_RACE_DIAL || isIPHostname(address)) return null;
  const [aRecords, aaaaRecords] = await Promise.all([
    dohQuery(address, 'A'),
    dohQuery(address, 'AAAA')
  ]);
  const ipv4List = [...new Set(aRecords.flatMap(r => {
    return r.type === 1 && typeof r.data === 'string' && isIPv4(r.data) ? [r.data] : [];
  }))];
  const ipv6List = [...new Set(aaaaRecords.flatMap(r => {
    return r.type === 28 && typeof r.data === 'string' && isIPHostname(r.data) ? [r.data] : [];
  }))];
  const limit = Math.max(1, TCP_CONCURRENCY | 0);
  const ipList = ipv4List.length >= limit
    ? ipv4List.slice(0, limit)
    : ipv4List.concat(ipv6List.slice(0, limit - ipv4List.length));
  if (ipList.length === 0) return null;
  return ipList.map((hostname, attempt) => ({ hostname, port, attempt, resolvedFrom: address }));
}

async function connectDirect(address, port, initialData = null) {
  const raceCandidates = await buildRaceCandidates(address, port);
  const candidates = raceCandidates || Array.from({ length: TCP_CONCURRENCY }, () => ({ hostname: address, port }));

  const openConnection = async (host, prt) => {
    const socket = connect({ hostname: host, port: prt });
    await Promise.race([
      socket.opened,
      new Promise((_, reject) => setTimeout(() => reject(new Error('timeout')), 1000))
    ]);
    return socket;
  };

  if (candidates.length === 1) {
    const s = await openConnection(candidates[0].hostname, candidates[0].port);
    if (initialData && initialData.byteLength > 0) {
      const w = s.writable.getWriter();
      await w.write(convertToUint8Array(initialData));
      w.releaseLock();
    }
    return s;
  }

  const attempts = candidates.map(c => openConnection(c.hostname, c.port).then(socket => ({ socket, candidate: c })));
  let winner = null;
  try {
    winner = await Promise.any(attempts);
    if (initialData && initialData.byteLength > 0) {
      const w = winner.socket.writable.getWriter();
      await w.write(convertToUint8Array(initialData));
      w.releaseLock();
    }
    return winner.socket;
  } finally {
    if (winner) {
      for (const attempt of attempts) {
        attempt.then(({ socket }) => {
          if (socket !== winner.socket) {
            try { socket.close(); } catch (e) {}
          }
        }).catch(() => {});
      }
    }
  }
}

async function forwardVlessUDP(udpChunk, webSocket, respHeader) {
  const requestData = convertToUint8Array(udpChunk);
  try {
    const tcpSocket = connect({ hostname: '8.8.4.4', port: 53 });
    let vlessHeader = respHeader;
    const writer = tcpSocket.writable.getWriter();
    await writer.write(requestData);
    writer.releaseLock();

    await tcpSocket.readable.pipeTo(new WritableStream({
      async write(chunk) {
        const response = convertToUint8Array(chunk);
        if (webSocket.readyState !== WebSocket.OPEN) return;
        if (vlessHeader) {
          const merged = new Uint8Array(vlessHeader.length + response.byteLength);
          merged.set(vlessHeader, 0);
          merged.set(response, vlessHeader.length);
          webSocket.send(merged.buffer);
          vlessHeader = null;
        } else {
          webSocket.send(response);
        }
      }
    }));
  } catch (e) {}
}

function extractUUIDFromVless(data) {
  if (data.byteLength < 17) return null;
  const hex = [...data.slice(1, 17)].map(b => b.toString(16).padStart(2, '0')).join('');
  return `${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}`;
}

// ==========================================================
// ۹. پوسته ها و کدهای رابط کاربری (HTML TEMPLATES)
// ==========================================================
const HTML_TEMPLATES = {
  nginx: `<!DOCTYPE html>
<html lang="fa" dir="rtl" class="dark">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>دسترسی به پنل</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <link href="https://cdn.jsdelivr.net/gh/rastikerdar/vazirmatn@v33.003/Vazirmatn-font-face.css" rel="stylesheet" type="text/css" />
    <script>
        tailwind.config = {
            darkMode: 'class',
            theme: {
                extend: {
                    fontFamily: { sans: ['Vazirmatn', 'sans-serif'] },
                    colors: { amoled: { bg: '#000000', card: '#080b0f', input: '#0d1117', border: '#1c2330' } }
                }
            }
        }
    </script>
</head>
<body class="bg-gray-50 text-gray-900 dark:bg-amoled-bg dark:text-zinc-100 min-h-screen flex items-center justify-center p-4">
    <div class="w-full max-w-md bg-white dark:bg-amoled-card border border-gray-200 dark:border-amoled-border rounded-2xl shadow-xl p-8 text-center flex flex-col items-center gap-4">
        
        <div class="p-4 bg-blue-50 dark:bg-blue-900/20 text-blue-500 rounded-full mb-2">
            <svg class="w-8 h-8" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"></path></svg>
        </div>
        
        <h2 class="text-xl font-bold text-gray-900 dark:text-white">ورود به پنل مدیریت</h2>
        
        <p class="text-sm text-gray-600 dark:text-gray-400 leading-relaxed mt-2">
            برای ورود به پنل، لطفاً عبارت 
            <span class="inline-block px-2 py-1 bg-gray-100 dark:bg-amoled-input border border-gray-200 dark:border-zinc-800 rounded-md font-mono text-blue-500 font-bold mx-1 shadow-sm" dir="ltr">/panel</span> 
            را به انتهای آدرس مرورگر خود اضافه کنید.
        </p>
        
        <button onclick="window.location.href='/panel'" class="mt-4 w-full py-2.5 bg-blue-600 hover:bg-blue-700 text-white font-medium rounded-xl text-sm transition-colors duration-200 shadow-lg shadow-blue-600/20 font-bold">
            ورود به پنل
        </button>
        
    </div>
</body>
</html>`,

  setup: `<!DOCTYPE html>
<html lang="fa" dir="rtl" class="dark">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>تعریف رمز عبور پنل</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <link href="https://cdn.jsdelivr.net/gh/rastikerdar/vazirmatn@v33.003/Vazirmatn-font-face.css" rel="stylesheet" type="text/css" />
    <script>
        tailwind.config = {
            darkMode: 'class',
            theme: {
                extend: {
                    fontFamily: { sans: ['Vazirmatn', 'sans-serif'] },
                    colors: { amoled: { bg: '#000000', card: '#080b0f', input: '#0d1117', border: '#1c2330' } }
                }
            }
        }
    </script>
</head>
<body class="bg-gray-50 text-gray-900 dark:bg-amoled-bg dark:text-zinc-100 min-h-screen flex items-center justify-center p-4">
    <div class="w-full max-w-md bg-white dark:bg-amoled-card border border-gray-200 dark:border-amoled-border rounded-2xl shadow-xl p-6">
        <h2 class="text-xl font-bold mb-2 text-center text-blue-600 dark:text-blue-400">تنظیم رمز عبور جدید</h2>
        <p class="text-sm text-gray-500 dark:text-gray-400 text-center mb-6">این اولین ورود شما به پنل مدیریت است. لطفاً رمز عبور خود را تعیین کنید.</p>
        
        <form onsubmit="handleSetup(event)" class="space-y-4">
            <div>
                <label class="block text-sm font-medium mb-1.5">رمز عبور</label>
                <input type="password" id="password" class="w-full px-3 py-2 bg-gray-50 dark:bg-amoled-input border border-gray-300 dark:border-amoled-border rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500 text-sm text-center font-mono" required minlength="4">
            </div>
            <div>
                <label class="block text-sm font-medium mb-1.5">تکرار رمز عبور</label>
                <input type="password" id="confirm-password" class="w-full px-3 py-2 bg-gray-50 dark:bg-amoled-input border border-gray-300 dark:border-amoled-border rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500 text-sm text-center font-mono" required minlength="4">
            </div>
            <button type="submit" id="submit-btn" class="w-full py-2.5 bg-blue-600 hover:bg-blue-700 text-white font-medium rounded-lg text-sm transition font-bold">ثبت و ورود</button>
        </form>
    </div>

    <script>
        async function handleSetup(event) {
            event.preventDefault();
            const password = document.getElementById('password').value;
            const confirmPassword = document.getElementById('confirm-password').value;
            const btn = document.getElementById('submit-btn');

            if (password !== confirmPassword) {
                alert('⚠️ رمز عبور و تکرار آن مطابقت ندارند!');
                return;
            }

            btn.disabled = true;
            btn.innerText = 'در حال ثبت...';

            try {
                const res = await fetch('/api/setup-password', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ password })
                });
                const data = await res.json();
                if (res.ok && data.success) {
                    alert('✅ رمز عبور با موفقیت تنظیم شد. در حال ورود...');
                    window.location.reload();
                } else {
                    alert('خطا: ' + (data.error || 'عملیات ناموفق بود'));
                }
            } catch (err) {
                alert('خطا در ارتباط با سرور');
            } finally {
                btn.disabled = false;
                btn.innerText = 'ثبت و ورود';
            }
        }
    </script>
</body>
</html>`,

  login: `<!DOCTYPE html>
<html lang="fa" dir="rtl" class="dark">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>ورود به پنل مدیریت</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <link href="https://cdn.jsdelivr.net/gh/rastikerdar/vazirmatn@v33.003/Vazirmatn-font-face.css" rel="stylesheet" type="text/css" />
    <script>
        tailwind.config = {
            darkMode: 'class',
            theme: {
                extend: {
                    fontFamily: { sans: ['Vazirmatn', 'sans-serif'] },
                    colors: { amoled: { bg: '#000000', card: '#080b0f', input: '#0d1117', border: '#1c2330' } }
                }
            }
        }
    </script>
</head>
<body class="bg-gray-50 text-gray-900 dark:bg-amoled-bg dark:text-zinc-100 min-h-screen flex items-center justify-center p-4">
    <div class="w-full max-w-md bg-white dark:bg-amoled-card border border-gray-200 dark:border-amoled-border rounded-2xl shadow-xl p-6">
        <h2 class="text-xl font-bold mb-2 text-center text-blue-600 dark:text-blue-400">ورود به پنل مدیریت</h2>
        <p class="text-sm text-gray-500 dark:text-gray-400 text-center mb-6">برای دسترسی به پنل مدیریت، رمز عبور خود را وارد کنید.</p>
        
        <form onsubmit="handleLogin(event)" class="space-y-4">
            <div>
                <label class="block text-sm font-medium mb-1.5">رمز عبور</label>
                <input type="password" id="password" class="w-full px-3 py-2 bg-gray-50 dark:bg-amoled-input border border-gray-300 dark:border-amoled-border rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500 text-sm text-center font-mono" required>
            </div>
            <button type="submit" id="submit-btn" class="w-full py-2.5 bg-blue-600 hover:bg-blue-700 text-white font-medium rounded-lg text-sm transition font-bold">ورود</button>
        </form>
    </div>

    <script>
        async function handleLogin(event) {
            event.preventDefault();
            const password = document.getElementById('password').value;
            const btn = document.getElementById('submit-btn');

            btn.disabled = true;
            btn.innerText = 'در حال بررسی...';

            try {
                const res = await fetch('/api/login', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ password })
                });
                const data = await res.json();
                if (res.ok && data.success) {
                    window.location.reload();
                } else {
                    alert('❌ رمز عبور اشتباه است!');
                }
            } catch (err) {
                alert('خطا در ارتباط با سرور');
            } finally {
                btn.disabled = false;
                btn.innerText = 'ورود';
            }
        }
    </script>
</body>
</html>`,

  panel: `
<!DOCTYPE html>
<html lang="fa" dir="rtl">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>ZEUS Panel</title>
    <script>
        const originalWarn = console.warn;
        console.warn = (...args) => {
            if (typeof args[0] === 'string' && args[0].includes('cdn.tailwindcss.com')) return;
            originalWarn(...args);
        };
    </script>
    <script src="https://cdn.tailwindcss.com"></script>
    <script src="https://cdnjs.cloudflare.com/ajax/libs/qrcodejs/1.0.0/qrcode.min.js"></script>
    <link href="https://cdn.jsdelivr.net/gh/rastikerdar/vazirmatn@v33.003/Vazirmatn-font-face.css" rel="stylesheet" type="text/css" />
    <script>
        tailwind.config = {
            darkMode: 'class',
            theme: {
                extend: {
                    fontFamily: { sans: ['Vazirmatn', 'sans-serif'] },
                    colors: { amoled: { bg: '#000000', card: '#080b0f', input: '#0d1117', border: '#1c2330' } }
                }
            }
        }
    </script>
    <style>body { font-family: 'Vazirmatn', sans-serif; }</style>
</head>
<body class="bg-gray-50 text-gray-900 dark:bg-amoled-bg dark:text-zinc-100 min-h-screen transition-colors duration-200">

    <header class="border-b border-gray-200 dark:border-amoled-border bg-white dark:bg-amoled-card px-4 py-4">
        <div class="max-w-6xl mx-auto flex justify-between items-center">
            <div class="flex flex-col sm:flex-row sm:items-center gap-3">
                <h1 class="text-lg font-bold flex items-center gap-2" dir="ltr">
                    ZEUS Panel 
                    <span class="text-xs px-2 py-0.5 font-semibold bg-blue-100 text-blue-800 dark:bg-blue-900/30 dark:text-blue-400 rounded-full">v1.1.1</span>
                </h1>
                <div class="flex items-center gap-3 bg-gray-100 dark:bg-zinc-800/60 px-3 py-1.5 rounded-full border border-gray-200 dark:border-zinc-800/80 shadow-sm flex-shrink-0 w-fit">
                    <a href="https://github.com/macan-dev/EasySNI" target="_blank" rel="noopener noreferrer" class="text-gray-700 dark:text-zinc-300 hover:text-black dark:hover:text-white transition-all transform hover:scale-125 duration-200 flex-shrink-0" title="گیت‌هاب">
                        <svg class="w-[22px] h-[22px] flex-shrink-0" viewBox="0 0 24 24" fill="currentColor">
                            <path fill-rule="evenodd" clip-rule="evenodd" d="M12 2C6.477 2 2 6.477 2 12c0 4.42 2.87 8.17 6.84 9.5.5.08.66-.23.66-.5v-1.69c-2.77.6-3.36-1.34-3.36-1.34-.46-1.16-1.11-1.47-1.11-1.47-.91-.62.07-.6.07-.6 1 .07 1.53 1.03 1.53 1.03.87 1.52 2.34 1.07 2.91.83.09-.65.35-1.09.63-1.34-2.22-.25-4.55-1.11-4.55-4.92 0-1.11.38-2 1.03-2.71-.1-.25-.45-1.29.1-2.64 0 0 .84-.27 2.75 1.02.79-.22 1.65-.33 2.5-.33.85 0 1.71.11 2.5.33 1.91-1.29 2.75-1.02 2.75-1.02.55 1.35.2 2.39.1 2.64.65.71 1.03 1.6 1.03 2.71 0 3.82-2.34 4.66-4.57 4.91.36.31.69.92.69 1.85V21c0 .27.16.59.67.5C19.14 20.16 22 16.42 22 12A10 10 0 0012 2z"/>
                        </svg>
                    </a>
                    <span class="w-px h-4 bg-gray-300 dark:bg-zinc-700 flex-shrink-0"></span>
                    <a href="https://t.me/EzAccess1" target="_blank" rel="noopener noreferrer" class="text-sky-500 hover:text-sky-600 dark:hover:text-sky-400 transition-all transform hover:scale-125 duration-200 flex-shrink-0" title="تلگرام">
                        <svg class="w-[22px] h-[22px] flex-shrink-0" viewBox="0 0 24 24" fill="currentColor">
                            <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm4.64 6.8c-.15 1.58-.8 5.42-1.13 7.19-.14.75-.42 1-.68 1.03-.58.05-1.02-.38-1.58-.75-.88-.58-1.38-.94-2.23-1.5-.99-.65-.35-1.01.22-1.59.15-.15 2.71-2.48 2.76-2.69a.2.2 0 00-.05-.18c-.06-.05-.14-.03-.21-.02-.09.02-1.49.94-4.22 2.79-.4.27-.76.41-1.08.4-.36-.01-1.04-.2-1.55-.37-.63-.2-1.12-.31-1.08-.66.02-.18.27-.36.74-.55 2.92-1.27 4.86-2.11 5.83-2.51 2.78-1.16 3.35-1.36 3.73-1.37.08 0 .27.02.39.12.1.08.13.19.14.27-.01.06.01.24 0 .24z"/>
                        </svg>
                    </a>
                </div>
            </div>
            <div class="flex items-center gap-3">
                <button id="theme-toggle" class="p-2 rounded-lg bg-gray-100 dark:bg-amoled-input border border-gray-200 dark:border-amoled-border hover:bg-gray-200 dark:hover:bg-zinc-800 transition">
                    <svg id="sun-icon" class="w-5 h-5 hidden dark:block text-yellow-500" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 3v1m0 16v1m9-9h-1M4 12H3m15.364-6.364l-.707.707M6.343 17.657l-.707.707m12.728 0l-.707-.707M6.343 6.343l-.707-.707M14 12a2 2 0 11-4 0 2 2 0 014 0z"></path></svg>
                    <svg id="moon-icon" class="w-5 h-5 block dark:hidden text-gray-600" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M20.354 15.354A9 9 0 018.646 3.646 9.003 9.003 0 0012 21a9.003 9.003 0 008.354-5.646z"></path></svg>
                </button>
                <button onclick="toggleSettingsModal(true)" class="p-2 rounded-lg bg-gray-100 dark:bg-amoled-input border border-gray-200 dark:border-amoled-border hover:bg-gray-200 dark:hover:bg-zinc-800 transition text-gray-600 dark:text-gray-300 shadow-sm" title="تنظیمات">
                    <svg class="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z"></path><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"></path></svg>
                </button>
                <button onclick="logoutAdmin()" class="p-2 rounded-lg bg-gray-100 dark:bg-amoled-input border border-gray-200 dark:border-amoled-border hover:bg-red-50 dark:hover:bg-red-950/20 transition text-red-600 dark:text-red-400 shadow-sm" title="خروج از حساب">
                    <svg class="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M17 16l4-4m0 0l-4-4m4 4H7m6 4v1a3 3 0 01-3 3H6a3 3 0 01-3-3V7a3 3 0 013-3h4a3 3 0 013 3v1"></path></svg>
                </button>
            </div>
        </div>
    </header>

    <main class="max-w-6xl mx-auto px-4 py-8">
        <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-6 mb-8">
            <div class="bg-white dark:bg-amoled-card border border-gray-200 dark:border-amoled-border rounded-2xl p-6 shadow-sm flex items-center justify-between hover:shadow-md hover:border-indigo-400 dark:hover:border-indigo-500/50 transition duration-300 relative overflow-hidden group">
                <div class="absolute -right-4 -bottom-4 w-24 h-24 bg-indigo-500/10 rounded-full blur-xl group-hover:scale-150 transition duration-500"></div>
                <div class="space-y-2 relative z-10">
                    <span class="text-sm font-semibold text-gray-500 dark:text-zinc-400">تعداد کل کاربران</span>
                    <div class="text-3xl font-black text-gray-900 dark:text-zinc-100 transition-all" id="stat-total-users">0</div>
                    <span class="text-xs text-indigo-500 dark:text-indigo-400 flex items-center gap-1 font-medium">
                        <span class="w-1.5 h-1.5 bg-indigo-500 rounded-full animate-ping"></span>
                        کل کاربران تعریف شده
                    </span>
                </div>
                <div class="p-3 bg-indigo-50 dark:bg-indigo-950/30 text-indigo-600 dark:text-indigo-400 rounded-2xl relative z-10">
                    <svg class="w-8 h-8" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M17 20h5v-2a3 3 0 00-5.356-1.857M17 20H7m10 0v-2c0-.656-.126-1.283-.356-1.857M7 20H2v-2a3 3 0 015.356-1.857M7 20v-2c0-.656.126-1.283.356-1.857m0 0a5.002 5.002 0 019.288 0M15 7a3 3 0 11-6 0 3 3 0 016 0zm6 3a2 2 0 11-4 0 2 2 0 014 0zM7 10a2 2 0 11-4 0 2 2 0 014 0z"></path></svg>
                </div>
            </div>

            <div class="bg-white dark:bg-amoled-card border border-gray-200 dark:border-amoled-border rounded-2xl p-6 shadow-sm flex items-center justify-between hover:shadow-md hover:border-emerald-400 dark:hover:border-emerald-500/50 transition duration-300 relative overflow-hidden group">
                <div class="absolute -right-4 -bottom-4 w-24 h-24 bg-emerald-500/10 rounded-full blur-xl group-hover:scale-150 transition duration-500"></div>
                <div class="space-y-2 relative z-10">
                    <span class="text-sm font-semibold text-gray-500 dark:text-zinc-400">کاربران فعال (آنلاین)</span>
                    <div class="text-3xl font-black text-emerald-600 dark:text-emerald-400 transition-all" id="stat-active-users">0</div>
                    <span class="text-xs text-emerald-500 dark:text-emerald-400 flex items-center gap-1 font-medium">
                        <span class="w-2 h-2 bg-emerald-500 rounded-full animate-pulse"></span>
                        متصل در این لحظه
                    </span>
                </div>
                <div class="p-3 bg-emerald-50 dark:bg-emerald-950/30 text-emerald-600 dark:text-emerald-400 rounded-2xl relative z-10">
                    <svg class="w-8 h-8" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M13 10V3L4 14h7v7l9-11h-7z"></path></svg>
                </div>
            </div>

            <div class="bg-white dark:bg-amoled-card border border-gray-200 dark:border-amoled-border rounded-2xl p-6 shadow-sm flex items-center justify-between hover:shadow-md hover:border-blue-400 dark:hover:border-blue-500/50 transition duration-300 relative overflow-hidden group">
                <div class="absolute -right-4 -bottom-4 w-24 h-24 bg-blue-500/10 rounded-full blur-xl group-hover:scale-150 transition duration-500"></div>
                <div class="space-y-2 relative z-10">
                    <span class="text-sm font-semibold text-gray-500 dark:text-zinc-400">کل حجم مصرفی (۳۰ روز)</span>
                    <div class="text-3xl font-black text-blue-600 dark:text-blue-400 transition-all" id="stat-total-usage">0 GB</div>
                    <span class="text-xs text-blue-500 dark:text-blue-400 flex items-center gap-1 font-medium">
                        <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M9 19l3 3m0 0l3-3m-3 3V10"></path></svg>
                        مصرف کل کاربران
                    </span>
                </div>
                <div class="p-3 bg-blue-50 dark:bg-blue-950/30 text-blue-600 dark:text-blue-400 rounded-2xl relative z-10">
                    <svg class="w-8 h-8" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M19 11H5m14 0a2 2 0 012 2v6a2 2 0 01-2 2H5a2 2 0 01-2-2v-6a2 2 0 012-2m14 0V9a2 2 0 00-2-2M5 11V9a2 2 0 012-2m0 0V5a2 2 0 012-2h6a2 2 0 012 2v2M7 7h10"></path></svg>
                </div>
            </div>

            <div class="bg-white dark:bg-amoled-card border border-gray-200 dark:border-amoled-border rounded-2xl p-6 shadow-sm flex items-center justify-between hover:shadow-md hover:border-amber-400 dark:hover:border-amber-500/50 transition duration-300 relative overflow-hidden group">
                <div class="absolute -right-4 -bottom-4 w-24 h-24 bg-amber-500/10 rounded-full blur-xl group-hover:scale-150 transition duration-500"></div>
                <div class="space-y-2 relative z-10">
                    <span class="text-sm font-semibold text-gray-500 dark:text-zinc-400">پر مصرف‌ترین کاربر</span>
                    <div class="text-2xl font-black text-amber-600 dark:text-amber-400 transition-all truncate max-w-[150px]" id="stat-top-user">-</div>
                    <span class="text-xs text-amber-500 dark:text-amber-400 flex items-center gap-1 font-medium" id="stat-top-user-usage">۰ GB مصرف شده</span>
                </div>
                <div class="p-3 bg-amber-50 dark:bg-amber-950/30 text-amber-600 dark:text-amber-400 rounded-2xl relative z-10">
                    <svg class="w-8 h-8" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M16 7a4 4 0 11-8 0 4 4 0 018 0zM12 14a7 7 0 00-7 7h14a7 7 0 00-7-7z"></path></svg>
                </div>
            </div>
        </div>

        <div id="loading-state" class="text-center py-12">
            <span class="text-gray-500 dark:text-gray-400">در حال بارگذاری کاربران...</span>
        </div>

        <div class="mb-6 flex flex-col md:flex-row gap-4 justify-between items-center bg-white dark:bg-amoled-card border border-gray-200 dark:border-amoled-border rounded-2xl p-4 shadow-sm">
            <!-- Search Box -->
            <div class="relative w-full md:w-80">
                <input type="text" id="search-input" oninput="filterAndRenderUsers()" placeholder="جستجوی نام کاربری یا UUID..." class="w-full pl-3 pr-9 py-2.5 bg-gray-50 dark:bg-amoled-input border border-gray-300 dark:border-amoled-border rounded-xl focus:outline-none focus:ring-2 focus:ring-blue-500 text-sm">
                <div class="absolute inset-y-0 right-0 flex items-center pr-3 pointer-events-none text-gray-400">
                    <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"></path></svg>
                </div>
            </div>
            <!-- Filters & Sorting -->
            <div class="flex flex-wrap items-center gap-3 w-full md:w-auto">
                <!-- Status Filter -->
                <select id="filter-status" onchange="filterAndRenderUsers()" class="px-3 py-2.5 bg-gray-50 dark:bg-amoled-input border border-gray-300 dark:border-amoled-border rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 text-gray-700 dark:text-zinc-300 cursor-pointer">
                    <option value="all">🔍 همه وضعیت‌ها</option>
                    <option value="active">✅ فعال</option>
                    <option value="inactive">❌ غیرفعال</option>
                    <option value="online">⚡ آنلاین</option>
                    <option value="offline">💤 آفلاین</option>
                    <option value="expired">⏳ منقضی شده / تمام شده</option>
                </select>
                <!-- Sorting -->
                <select id="sort-users" onchange="filterAndRenderUsers()" class="px-3 py-2.5 bg-gray-50 dark:bg-amoled-input border border-gray-300 dark:border-amoled-border rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 text-gray-700 dark:text-zinc-300 cursor-pointer">
                    <option value="newest">📅 جدیدترین</option>
                    <option value="name">🔤 نام کاربری (الفبا)</option>
                    <option value="usage-desc">📊 بیشترین مصرف</option>
                    <option value="usage-asc">📈 کمترین مصرف</option>
                    <option value="expiry-asc">⏳ کمترین زمان باقی‌مانده</option>
                </select>
            </div>
        </div>

		<div class="flex items-center justify-between mb-4">
			<h2 class="text-lg font-bold text-gray-800 dark:text-zinc-200">لیست کاربران</h2>
			<button onclick="openCreateModal()" class="flex items-center justify-center w-10 h-10 bg-gradient-to-r from-blue-600 to-indigo-600 hover:from-blue-700 hover:to-indigo-700 text-white rounded-full shadow-md hover:shadow-lg hover:scale-110 transition-all duration-300">
				<svg class="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M12 4v16m8-8H4"></path></svg>
			</button>
		</div>
        
        <div id="users-table-container" class="hidden overflow-x-auto border border-gray-200 dark:border-amoled-border rounded-xl bg-white dark:bg-amoled-card">
            <table class="w-full text-right border-collapse">
                <thead>
                    <tr class="bg-gray-100 dark:bg-zinc-900/50 border-b border-gray-200 dark:border-amoled-border text-xs text-gray-500 dark:text-gray-400">
                        <th class="p-4">نام کاربر و عملیات</th>
                        <th class="p-4">لینک ساب</th>
                        <th class="p-4">پروتکل</th>
                        <th class="p-4">پورت (TLS)</th>
                        <th class="p-4">وضعیت حجم</th>
                        <th class="p-4">وضعیت اعتبار</th>
                        <th class="p-4">تاریخ ساخت</th>
                    </tr>
                </thead>
                <tbody id="users-tbody" class="divide-y divide-gray-150 dark:divide-amoled-border text-sm"></tbody>
            </table>
        </div>

        <div id="empty-state" class="hidden p-8 border border-dashed border-gray-300 dark:border-amoled-border rounded-2xl text-center">
            <p class="text-gray-500 dark:text-gray-400">کاربری وجود ندارد. برای ساخت اولین کاربر روی دکمه «افزودن کاربر جدید» کلیک کنید.</p>
        </div>
    </main>

    <div id="user-modal" class="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/70 opacity-0 pointer-events-none transition-opacity duration-200 ease-out">
        <div id="user-modal-card" class="w-full max-w-xl bg-white dark:bg-zinc-950 border border-gray-200 dark:border-zinc-850 rounded-2xl shadow-xl overflow-hidden transition-[opacity,transform] duration-200 opacity-0 scale-95 ease-out flex flex-col max-h-[90vh] transform-gpu" style="will-change: transform, opacity;">
            <div class="px-6 py-4 border-b border-gray-150 dark:border-zinc-800/80 flex justify-between items-center bg-gray-50/50 dark:bg-zinc-900/30">
                <div class="flex items-center gap-2">
                    <div class="w-2.5 h-2.5 rounded-full bg-blue-500"></div>
                    <h3 id="modal-title" class="font-bold text-gray-900 dark:text-zinc-100 text-base">ایجاد کاربر جدید</h3>
                </div>
                <button onclick="toggleModal(false)" class="p-1 rounded-lg hover:bg-gray-150 dark:hover:bg-zinc-800/60 text-gray-400 hover:text-gray-650 dark:hover:text-zinc-200 transition">
                    <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12"></path></svg>
                </button>
            </div>

            <form id="create-user-form" class="p-6 space-y-5 overflow-y-auto flex-1 overscroll-contain" style="-webkit-overflow-scrolling: touch; transform: translate3d(0,0,0); will-change: scroll-position, transform;" onsubmit="handleFormSubmit(event)">
                <div class="space-y-4">
                    <div>
                        <label class="block text-xs font-bold text-gray-500 dark:text-zinc-400 mb-2 uppercase tracking-wider">نام کاربری</label>
                        <div class="relative">
                            <span class="absolute inset-y-0 right-0 flex items-center pr-3.5 pointer-events-none text-gray-400">
                                <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M16 7a4 4 0 11-8 0 4 4 0 018 0zM12 14a7 7 0 00-7 7h14a7 7 0 00-7-7z"></path></svg>
                            </span>
                            <input type="text" id="input-name" placeholder="ali" class="w-full pl-3 pr-10 py-2.5 bg-gray-50 dark:bg-zinc-900 border border-gray-200 dark:border-zinc-800 rounded-xl focus:outline-none focus:ring-2 focus:ring-blue-500/50 text-sm font-semibold text-gray-800 dark:text-zinc-100 placeholder-gray-400/80 transition" required>
                        </div>
                    </div>

                    <div class="grid grid-cols-2 gap-4">
                        <div>
                            <label class="block text-xs font-bold text-gray-500 dark:text-zinc-400 mb-2 uppercase tracking-wider">حجم مجاز (GB)</label>
                            <div class="relative">
                                <span class="absolute inset-y-0 right-0 flex items-center pr-3.5 pointer-events-none text-gray-400">
                                    <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 11H5m14 0a2 2 0 012 2v6a2 2 0 01-2 2H5a2 2 0 01-2-2v-6a2 2 0 012-2m14 0V9a2 2 0 00-2-2M5 11V9a2 2 0 012-2m0 0V5a2 2 0 012-2h6a2 2 0 012 2v2M7 7h10"></path></svg>
                                </span>
                                <input type="number" id="input-limit" min="0" step="any" placeholder="نامحدود" class="w-full pl-3 pr-10 py-2.5 bg-gray-50 dark:bg-zinc-900 border border-gray-200 dark:border-zinc-800 rounded-xl focus:outline-none focus:ring-2 focus:ring-blue-500/50 text-sm font-semibold text-gray-800 dark:text-zinc-100 placeholder-gray-400/80 transition">
                            </div>
                        </div>
                        <div>
                            <label class="block text-xs font-bold text-gray-500 dark:text-zinc-400 mb-2 uppercase tracking-wider">مدت اعتبار (روز)</label>
                            <div class="relative">
                                <span class="absolute inset-y-0 right-0 flex items-center pr-3.5 pointer-events-none text-gray-400">
                                    <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z"></path></svg>
                                </span>
                                <input type="number" id="input-expiry" min="0" placeholder="نامحدود" class="w-full pl-3 pr-10 py-2.5 bg-gray-50 dark:bg-zinc-900 border border-gray-200 dark:border-zinc-800 rounded-xl focus:outline-none focus:ring-2 focus:ring-blue-500/50 text-sm font-semibold text-gray-800 dark:text-zinc-100 placeholder-gray-400/80 transition">
                            </div>
                        </div>
                    </div>
                </div>

                <div class="pt-2 border-t border-gray-100 dark:border-zinc-900">
                    <label class="block text-xs font-bold text-gray-500 dark:text-zinc-400 mb-3 uppercase tracking-wider">پورت‌های اتصال (انتخاب چندگانه)</label>
                    <div class="space-y-4">
                        <div class="p-4 bg-gray-50/50 dark:bg-zinc-900/20 border border-gray-200/60 dark:border-zinc-850 rounded-2xl shadow-sm">
                            <div class="flex items-center gap-1.5 mb-3">
                                <span class="flex h-2 w-2 rounded-full bg-blue-500 shadow-sm"></span>
                                <span class="text-xs font-bold text-blue-600 dark:text-blue-400">🔒 پورت‌های امن (TLS)</span>
                            </div>
                            <div class="grid grid-cols-3 sm:grid-cols-4 gap-2" id="tls-ports-list">
                                <!-- Filled dynamically -->
                            </div>
                        </div>

                        <div class="p-4 bg-gray-50/50 dark:bg-zinc-900/20 border border-gray-200/60 dark:border-zinc-850 rounded-2xl shadow-sm">
                            <div class="flex items-center gap-1.5 mb-3">
                                <span class="flex h-2 w-2 rounded-full bg-amber-500 shadow-sm"></span>
                                <span class="text-xs font-bold text-amber-600 dark:text-amber-400">🔓 پورت‌های معمولی (Non-TLS)</span>
                            </div>
                            <div class="grid grid-cols-3 sm:grid-cols-4 gap-2" id="nontls-ports-list">
                                <!-- Filled dynamically -->
                            </div>
                        </div>
                    </div>
                </div>

                <div class="pt-4 border-t border-gray-100 dark:border-zinc-900 space-y-4">
                    <div>
                        <label class="block text-xs font-bold text-gray-500 dark:text-zinc-400 mb-2 uppercase tracking-wider">آی‌پی تمیز کلودفلر (اختیاری - هر خط یک آی‌پی)</label>
                        <textarea id="input-ips" rows="2" placeholder="104.16.0.1" class="w-full px-3 py-2.5 bg-gray-50 dark:bg-zinc-900 border border-gray-200 dark:border-zinc-800 rounded-xl focus:outline-none focus:ring-2 focus:ring-blue-500/50 text-xs font-mono text-gray-800 dark:text-zinc-100 placeholder-gray-400/80 transition resize-none"></textarea>
                    </div>

                    <div>
                        <label class="block text-xs font-bold text-gray-500 dark:text-zinc-400 mb-2 uppercase tracking-wider">شبیه‌ساز اثر انگشت مرورگر (Fingerprint)</label>
                        <div class="relative">
                            <select id="fingerprint-select" class="w-full px-3 py-2.5 bg-gray-50 dark:bg-zinc-900 border border-gray-200 dark:border-zinc-800 rounded-xl focus:outline-none focus:ring-2 focus:ring-blue-500/50 text-xs font-semibold text-gray-700 dark:text-zinc-300 cursor-pointer appearance-none">
                                <option value="chrome" selected>🌐 Chrome (پیش‌فرض)</option>
                                <option value="firefox">🦊 Firefox</option>
                                <option value="safari">🧭 Safari</option>
                                <option value="ios">📱 iOS Device</option>
                                <option value="android">🤖 Android Device</option>
                                <option value="edge">🌀 Microsoft Edge</option>
                                <option value="360">🔒 360 Browser</option>
                                <option value="qq">💬 QQ Browser</option>
                                <option value="random">🎲 Random (اتفاقی)</option>
                                <option value="randomized">🎭 Randomized (پویا)</option>
                            </select>
                            <div class="pointer-events-none absolute inset-y-0 left-0 flex items-center pl-3 text-gray-500 dark:text-zinc-400">
                                <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7"></path></svg>
                            </div>
                        </div>
                    </div>
                </div>

                <div class="pt-4 flex gap-3">
                    <button type="button" onclick="toggleModal(false)" class="flex-1 py-3 bg-gray-100 hover:bg-gray-200 dark:bg-zinc-800 dark:hover:bg-zinc-700/80 text-gray-700 dark:text-zinc-300 font-bold rounded-xl text-sm transition duration-200">انصراف</button>
                    <button type="submit" id="submit-btn" class="flex-1 py-3 bg-gradient-to-r from-blue-600 to-indigo-600 hover:from-blue-700 hover:to-indigo-700 text-white font-bold rounded-xl text-sm transition duration-200 shadow-md shadow-blue-500/10 hover:shadow-lg">ایجاد کاربر</button>
                </div>
            </form>
        </div>
    </div>

    <div id="qr-modal" class="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/60 backdrop-blur-sm opacity-0 pointer-events-none transition-all duration-300 ease-out">
        <div class="w-full max-w-sm bg-white dark:bg-amoled-card border border-gray-200 dark:border-amoled-border rounded-2xl shadow-xl overflow-hidden p-6 text-center transition-all transform duration-300 opacity-0 scale-95 ease-out">
            <h3 id="qr-modal-title" class="font-bold text-gray-900 dark:text-zinc-100 mb-4">اسکن کد QR</h3>
            <div class="bg-white p-3 rounded-xl inline-block mb-4 border border-gray-100">
                <div id="qrcode-box" class="flex justify-center items-center w-48 h-48 mx-auto"></div>
            </div>
            <button onclick="toggleQRModal(false)" class="w-full py-2 bg-gray-100 hover:bg-gray-200 dark:bg-zinc-800 dark:hover:bg-zinc-700 font-medium rounded-lg text-sm transition text-gray-900 dark:text-zinc-100">بستن</button>
        </div>
    </div>

    <div id="settings-modal" class="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/60 backdrop-blur-sm opacity-0 pointer-events-none transition-all duration-300 ease-out">
        <div class="w-full max-w-md bg-white dark:bg-amoled-card border border-gray-200 dark:border-amoled-border rounded-2xl shadow-xl overflow-hidden transition-all transform duration-300 opacity-0 scale-95 ease-out">
            <div class="px-6 py-4 border-b border-gray-150 dark:border-amoled-border flex justify-between items-center bg-gray-50 dark:bg-zinc-900/50">
                <h3 class="font-bold text-gray-900 dark:text-zinc-100">تنظیمات پنل</h3>
                <button onclick="toggleSettingsModal(false)" class="text-gray-400 hover:text-gray-600 dark:hover:text-zinc-200">
                    <svg class="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12"></path></svg>
                </button>
            </div>
            <div class="p-6 space-y-4">
                <div>
                    <label class="block text-sm font-medium mb-1.5 text-gray-700 dark:text-zinc-300">موقعیت جغرافیایی پروکسی (Cloudflare)</label>
                    <div class="relative">
                        <select id="location-select" class="w-full pl-8 pr-3 py-2.5 bg-white dark:bg-amoled-input border border-gray-300 dark:border-amoled-border rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 text-gray-700 dark:text-zinc-200 cursor-pointer appearance-none">
                            <option value="">در حال بارگذاری...</option>
                        </select>
                        <div class="pointer-events-none absolute inset-y-0 left-0 flex items-center pl-3 text-gray-500 dark:text-zinc-400">
                            <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7"></path></svg>
                        </div>
                    </div>
                </div>
                <div class="grid grid-cols-2 gap-4 pt-2 border-t border-gray-100 dark:border-zinc-800">
                    <div>
                        <label class="block text-sm font-medium mb-1.5 text-gray-700 dark:text-zinc-300">Fragment Length</label>
                        <input type="text" id="frag-length" placeholder="20-30" class="w-full px-3 py-2.5 bg-white dark:bg-amoled-input border border-gray-300 dark:border-amoled-border rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500 text-sm text-center font-mono" dir="ltr">
                    </div>
                    <div>
                        <label class="block text-sm font-medium mb-1.5 text-gray-700 dark:text-zinc-300">Fragment Interval</label>
                        <input type="text" id="frag-interval" placeholder="1-2" class="w-full px-3 py-2.5 bg-white dark:bg-amoled-input border border-gray-300 dark:border-amoled-border rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500 text-sm text-center font-mono" dir="ltr">
                    </div>
                </div>
                <!-- Change Password Section -->
                <div class="pt-4 border-t border-gray-100 dark:border-zinc-800">
                    <h4 class="text-sm font-bold mb-3 text-gray-800 dark:text-zinc-200">🔒 تغییر رمز عبور مدیریت</h4>
                    <div class="space-y-3">
                        <div>
                            <label class="block text-[11px] text-gray-500 dark:text-gray-400 font-medium mb-1">رمز عبور فعلی</label>
                            <input type="password" id="change-pwd-current" class="w-full px-3 py-2 bg-white dark:bg-amoled-input border border-gray-300 dark:border-amoled-border rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500 text-xs font-mono text-center">
                        </div>
                        <div>
                            <label class="block text-[11px] text-gray-500 dark:text-gray-400 font-medium mb-1">رمز عبور جدید</label>
                            <input type="password" id="change-pwd-new" class="w-full px-3 py-2 bg-white dark:bg-amoled-input border border-gray-300 dark:border-amoled-border rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500 text-xs font-mono text-center">
                        </div>
                        <button type="button" onclick="changeAdminPassword()" id="change-pwd-btn" class="w-full py-2 bg-gradient-to-r from-blue-600 to-indigo-600 hover:from-blue-700 hover:to-indigo-700 text-white font-semibold rounded-lg text-xs transition-all shadow-sm">تغییر رمز عبور</button>
                    </div>
                </div>
                <div class="pt-4 flex gap-3">
                    <button type="button" onclick="toggleSettingsModal(false)" class="flex-1 py-2 bg-gray-100 hover:bg-gray-200 dark:bg-zinc-800 dark:hover:bg-zinc-700 font-medium rounded-lg text-sm transition">انصراف</button>
                    <button type="button" onclick="saveSettings()" id="save-settings-btn" class="flex-1 py-2 bg-blue-600 hover:bg-blue-700 text-white font-medium rounded-lg text-sm transition">ذخیره تنظیمات</button>
                </div>
            </div>
        </div>
    </div>

    <script>
        window.globalFragLen = "20-30";
        window.globalFragInt = "1-2";

        const tlsPorts = ['443', '2053', '2083', '2087', '2096', '8443'];
        const nonTlsPorts = ['80', '8080', '8880', '2052', '2082', '2086', '2095'];

        let isEditMode = false;
        let editingUsername = '';

        function renderPortCheckboxes() {
            const tlsContainer = document.getElementById('tls-ports-list');
            const nonTlsContainer = document.getElementById('nontls-ports-list');

            tlsContainer.innerHTML = tlsPorts.map(function(port) {
                const isCheckedDefault = port === '443' ? 'checked' : '';
                return '<label class="relative cursor-pointer">' +
                    '<input type="checkbox" name="ports" value="' + port + '" ' + isCheckedDefault + ' class="peer sr-only">' +
                    '<div class="flex items-center justify-center gap-2 px-3 py-2 border border-gray-200 dark:border-zinc-800/80 rounded-xl text-xs font-semibold select-none transition-all duration-200 hover:bg-gray-50 dark:hover:bg-zinc-800/40 text-gray-700 dark:text-zinc-300 peer-checked:bg-blue-50 dark:peer-checked:bg-blue-950/25 peer-checked:border-blue-500 dark:peer-checked:border-blue-500/70 peer-checked:text-blue-600 dark:peer-checked:text-blue-400 shadow-sm">' +
                        '<span>' + port + '</span>' +
                        '<svg class="w-4 h-4 hidden peer-checked:block text-blue-500" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M5 13l4 4L19 7"></path></svg>' +
                    '</div>' +
                '</label>';
            }).join('');

            nonTlsContainer.innerHTML = nonTlsPorts.map(function(port) {
                return '<label class="relative cursor-pointer">' +
                    '<input type="checkbox" name="ports" value="' + port + '" class="peer sr-only">' +
                    '<div class="flex items-center justify-center gap-2 px-3 py-2 border border-gray-200 dark:border-zinc-800/80 rounded-xl text-xs font-semibold select-none transition-all duration-200 hover:bg-gray-50 dark:hover:bg-zinc-800/40 text-gray-700 dark:text-zinc-300 peer-checked:bg-amber-50 dark:peer-checked:bg-amber-950/25 peer-checked:border-amber-500 dark:peer-checked:border-amber-500/70 peer-checked:text-amber-600 dark:peer-checked:text-amber-400 shadow-sm">' +
                        '<span>' + port + '</span>' +
                        '<svg class="w-4 h-4 hidden peer-checked:block text-amber-500" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M5 13l4 4L19 7"></path></svg>' +
                    '</div>' +
                '</label>';
            }).join('');
        }

        // Initialize 443 active state immediately
        setTimeout(function() {
            const cb443 = document.querySelector('input[name="ports"][value="443"]');
            if (cb443) cb443.checked = true;
        }, 100);

        function toggleSettingsModal(show) {
            const modal = document.getElementById('settings-modal');
            const card = modal.querySelector('div');
            if (show) {
                modal.classList.remove('opacity-0', 'pointer-events-none');
                modal.classList.add('opacity-100', 'pointer-events-auto');
                card.classList.remove('opacity-0', 'scale-95');
                card.classList.add('opacity-100', 'scale-100');
            } else {
                modal.classList.remove('opacity-100', 'pointer-events-auto');
                modal.classList.add('opacity-0', 'pointer-events-none');
                card.classList.remove('opacity-100', 'scale-100');
                card.classList.add('opacity-0', 'scale-95');
            }
        }

        function toggleModal(show) {
            const modal = document.getElementById('user-modal');
            const card = document.getElementById('user-modal-card');
            if (show) {
                modal.classList.remove('opacity-0', 'pointer-events-none');
                modal.classList.add('opacity-100', 'pointer-events-auto');
                card.classList.remove('opacity-0', 'scale-95');
                card.classList.add('opacity-100', 'scale-100');
            } else {
                modal.classList.remove('opacity-100', 'pointer-events-auto');
                modal.classList.add('opacity-0', 'pointer-events-none');
                card.classList.remove('opacity-100', 'scale-100');
                card.classList.add('opacity-0', 'scale-95');
                isEditMode = false;
                editingUsername = '';
                document.getElementById('modal-title').innerText = 'ایجاد کاربر جدید';
                document.getElementById('submit-btn').innerText = 'ایجاد کاربر';
                document.getElementById('input-name').disabled = false;
                document.getElementById('create-user-form').reset();
                // Ensure port 443 remains checked as default when form is reset
                const cb443 = document.querySelector('input[name="ports"][value="443"]');
                if (cb443) cb443.checked = true;
            }
        }

        function openCreateModal() {
            isEditMode = false;
            editingUsername = '';
            document.getElementById('modal-title').innerText = 'ایجاد کاربر جدید';
            document.getElementById('submit-btn').innerText = 'ایجاد کاربر';
            document.getElementById('input-name').disabled = false;
            document.getElementById('create-user-form').reset();
            toggleModal(true);
        }

        const themeToggleBtn = document.getElementById('theme-toggle');
		if (localStorage.getItem('color-theme') === 'dark' || (!('color-theme' in localStorage) && window.matchMedia('(prefers-color-scheme: dark)').matches)) {
            document.documentElement.classList.add('dark');
        } else {
            document.documentElement.classList.remove('dark');
        }

        themeToggleBtn.addEventListener('click', () => {
            if (document.documentElement.classList.contains('dark')) {
                document.documentElement.classList.remove('dark');
                localStorage.setItem('color-theme', 'light');
            } else {
                document.documentElement.classList.add('dark');
                localStorage.setItem('color-theme', 'dark');
            }
        });

        async function loadUsers(silent = false) {
            const loadingState = document.getElementById('loading-state');
            const tableContainer = document.getElementById('users-table-container');
            const emptyState = document.getElementById('empty-state');
            
            if (!silent) {
                loadingState.classList.remove('hidden');
                tableContainer.classList.add('hidden');
                emptyState.classList.add('hidden');
            }
            
            try {
                const res = await fetch('/api/users?t=' + Date.now());
                if (!res.ok) throw new Error();
                const data = await res.json();
                renderUsersUI(data);
            } catch (err) {
                if (!silent) {
                    loadingState.innerHTML = '<span class="text-red-500">خطا در دریافت اطلاعات از سرور</span>';
                }
            }
        }

        function renderUsersUI(data) {
            try {
                const users = data.users || [];
                window.allUsers = users;
                const serverTime = data.serverTime || Date.now();
                window.lastServerTime = serverTime;
                
                const totalUsersCount = users.length;
                const activeUsersCount = users.filter(u => u.is_online === 1).length;
                const totalGbUsage = users.reduce((sum, u) => sum + (u.used_gb || 0), 0);
                
                document.getElementById('stat-total-users').innerText = totalUsersCount;
                document.getElementById('stat-active-users').innerText = activeUsersCount;
                document.getElementById('stat-total-usage').innerText = totalGbUsage < 1 ? (totalGbUsage * 1024).toFixed(0) + ' MB' : totalGbUsage.toFixed(2) + ' GB';
                
                const topUser = users.reduce((max, u) => (u.used_gb || 0) > (max.used_gb || 0) ? u : max, { username: 'هیچکدام', used_gb: 0 });
                document.getElementById('stat-top-user').innerText = topUser.username;
                const topUsage = topUser.used_gb || 0;
                document.getElementById('stat-top-user-usage').innerText = topUsage < 1 ? (topUsage * 1024).toFixed(0) + ' MB مصرف شده' : topUsage.toFixed(2) + ' GB مصرف شده';

                filterAndRenderUsers();
            } catch (err) {
                document.getElementById('loading-state').innerHTML = '<span class="text-red-500">خطا در پردازش اطلاعات کاربران</span>';
            }
        }

        function filterAndRenderUsers() {
            if (!window.allUsers) return;
            const searchQuery = (document.getElementById('search-input').value || '').toLowerCase().trim();
            const filterStatus = document.getElementById('filter-status').value;
            const sortVal = document.getElementById('sort-users').value;
            const serverTime = window.lastServerTime || Date.now();
            
            let filtered = [...window.allUsers];
            
            // Search filter
            if (searchQuery) {
                filtered = filtered.filter(u => 
                    (u.username || '').toLowerCase().includes(searchQuery) || 
                    (u.uuid || '').toLowerCase().includes(searchQuery)
                );
            }
            
            // Status filter
            if (filterStatus !== 'all') {
                filtered = filtered.filter(u => {
                    const isOnline = u.is_online === 1;
                    const isActive = u.is_active === 1;
                    
                    let isExpired = false;
                    if (u.limit_gb && u.used_gb >= u.limit_gb) isExpired = true;
                    if (u.expiry_days && u.created_at) {
                        const created = new Date(u.created_at);
                        const expiryDate = new Date(created.getTime() + (u.expiry_days * 24 * 60 * 60 * 1000));
                        if (new Date(serverTime) > expiryDate) isExpired = true;
                    }
                    
                    if (filterStatus === 'active') return isActive && !isExpired;
                    if (filterStatus === 'inactive') return !isActive;
                    if (filterStatus === 'online') return isOnline;
                    if (filterStatus === 'offline') return !isOnline;
                    if (filterStatus === 'expired') return isExpired || !isActive;
                    return true;
                });
            }
            
            // Sort
            filtered.sort((a, b) => {
                if (sortVal === 'newest') {
                    return b.id - a.id;
                }
                if (sortVal === 'name') {
                    return (a.username || '').localeCompare(b.username || '');
                }
                if (sortVal === 'usage-desc') {
                    return (b.used_gb || 0) - (a.used_gb || 0);
                }
                if (sortVal === 'usage-asc') {
                    return (a.used_gb || 0) - (b.used_gb || 0);
                }
                if (sortVal === 'expiry-asc') {
                    const getRemaining = (u) => {
                        if (!u.expiry_days) return Infinity;
                        if (!u.created_at) return Infinity;
                        const created = new Date(u.created_at);
                        const expiryDate = new Date(created.getTime() + (u.expiry_days * 24 * 60 * 60 * 1000));
                        return expiryDate - new Date(serverTime);
                    };
                    return getRemaining(a) - getRemaining(b);
                }
                return 0;
            });
            
            renderFilteredUsers(filtered, serverTime);
        }

        function renderFilteredUsers(users, serverTime) {
            const loadingState = document.getElementById('loading-state');
            const tableContainer = document.getElementById('users-table-container');
            const emptyState = document.getElementById('empty-state');
            const tbody = document.getElementById('users-tbody');
            
            if (users.length === 0) {
                loadingState.classList.add('hidden');
                emptyState.classList.remove('hidden');
                tableContainer.classList.add('hidden');
                if (window.allUsers && window.allUsers.length > 0) {
                    emptyState.querySelector('p').innerText = 'کاربری با مشخصات جستجو شده یافت نشد.';
                } else {
                    emptyState.querySelector('p').innerText = 'کاربری وجود ندارد. برای ساخت اولین کاربر روی دکمه «افزودن کاربر جدید» کلیک کنید.';
                }
            } else {
                loadingState.classList.add('hidden');
                emptyState.classList.add('hidden');
                tableContainer.classList.remove('hidden');
                
                tbody.innerHTML = users.map(user => {
                    const createdDate = user.created_at ? new Date(user.created_at).toLocaleDateString('fa-IR') : '-';
                    let daysRemaining = 'نامحدود';
                    let daysPercent = 100;
                    if (user.expiry_days) {
                        if (user.created_at) {
                            const created = new Date(user.created_at);
                            const expiryDate = new Date(created.getTime() + (user.expiry_days * 24 * 60 * 60 * 1000));
                            const diffDays = Math.ceil((expiryDate - new Date(serverTime)) / (1000 * 60 * 60 * 24));
                            daysRemaining = diffDays > 0 ? diffDays : 0;
                            daysPercent = Math.max(0, Math.min(100, (daysRemaining / user.expiry_days) * 100));
                        } else {
                            daysRemaining = user.expiry_days;
                        }
                    }

                    const usedGb = user.used_gb || 0;
                    const formattedUsed = usedGb < 1 ? (usedGb * 1024).toFixed(0) + ' MB' : usedGb.toFixed(2) + ' GB';

                    let volumeHtml = '';
                    if (user.limit_gb) {
                        const limitPercent = Math.min((usedGb / user.limit_gb) * 100, 100);
                        const limitHue = 120 - (limitPercent * 1.2);
                        const formattedLimit = user.limit_gb < 1 ? (user.limit_gb * 1024).toFixed(0) + ' MB' : user.limit_gb + ' GB';
                        volumeHtml = '<div class="flex flex-col gap-1.5 w-full min-w-[130px]">' +
                            '<div class="flex justify-between text-[11px] text-gray-500 dark:text-gray-400 font-medium">' +
                                '<span>مصرف: ' + formattedUsed + '</span>' +
                                '<span>کل: ' + formattedLimit + '</span>' +
                            '</div>' +
                            '<div class="w-full bg-gray-200 dark:bg-zinc-700 rounded-full h-1.5 overflow-hidden">' +
                                '<div class="h-1.5 rounded-full transition-all duration-500" style="width: ' + limitPercent + '%; background-color: hsl(' + limitHue + ', 80%, 45%)"></div>' +
                            '</div>' +
                        '</div>';
                    } else {
                        volumeHtml = '<div class="flex flex-col gap-1.5 w-full min-w-[130px]">' +
                            '<div class="flex justify-between text-[11px] text-gray-500 dark:text-gray-400 font-medium">' +
                                '<span>مصرف: ' + formattedUsed + '</span>' +
                                '<span>کل: نامحدود</span>' +
                            '</div>' +
                            '<div class="w-full bg-gray-200 dark:bg-zinc-700 rounded-full h-1.5 overflow-hidden">' +
                                '<div class="bg-blue-500 h-1.5 rounded-full transition-all duration-500" style="width: 100%"></div>' +
                            '</div>' +
                        '</div>';
                    }

                    let expiryHtml = '';
                    if (user.expiry_days) {
                        const expiryHue = daysPercent * 1.2;
                        expiryHtml = '<div class="flex flex-col gap-1.5 w-full min-w-[130px]">' +
                            '<div class="flex justify-between text-[11px] text-gray-500 dark:text-gray-400 font-medium">' +
                                '<span>باقی‌مانده: ' + daysRemaining + ' روز</span>' +
                                '<span>کل: ' + user.expiry_days + ' روز</span>' +
                            '</div>' +
                            '<div class="w-full bg-gray-200 dark:bg-zinc-700 rounded-full h-1.5 overflow-hidden flex justify-end">' +
                                '<div class="h-1.5 rounded-full transition-all duration-500" style="width: ' + daysPercent + '%; background-color: hsl(' + expiryHue + ', 80%, 45%)"></div>' +
                            '</div>' +
                        '</div>';
                    } else {
                        expiryHtml = '<div class="flex flex-col gap-1.5 w-full min-w-[130px]">' +
                            '<div class="flex justify-between text-[11px] text-gray-500 dark:text-gray-400 font-medium">' +
                                '<span>باقی‌مانده: نامحدود</span>' +
                                '<span>کل: نامحدود</span>' +
                            '</div>' +
                            '<div class="w-full bg-gray-200 dark:bg-zinc-700 rounded-full h-1.5 overflow-hidden flex justify-end">' +
                                '<div class="bg-blue-500 h-1.5 rounded-full transition-all duration-500" style="width: 100%"></div>' +
                            '</div>' +
                        '</div>';
                    }

                    const statusBtnColor = user.is_active === 0 ? 'text-emerald-600 dark:text-emerald-400 hover:bg-emerald-50 dark:hover:bg-emerald-900/30' : 'text-amber-600 dark:text-amber-400 hover:bg-amber-50 dark:hover:bg-amber-900/30';
                    const statusBtnTitle = user.is_active === 0 ? 'فعال کردن کاربر' : 'قطع کردن کاربر';
                    const statusBtnIcon = user.is_active === 0 
                        ? '<svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M14.752 11.168l-3.197-2.132A1 1 0 0010 9.87v4.263a1 1 0 001.555.832l3.197-2.132a1 1 0 000-1.664z"></path><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 12a9 9 0 11-18 0 9 9 0 0118 0z"></path></svg>'
                        : '<svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10 9v6m4-6v6m7-3a9 9 0 11-18 0 9 9 0 0118 0z"></path></svg>';

                    return '<tr class="hover:bg-gray-50 dark:hover:bg-zinc-900/40 border-b border-gray-100 dark:border-zinc-800 last:border-0">' +
                            '<td class="p-4">' +
                                '<div class="flex flex-col gap-3">' +
                                    '<div class="flex items-center gap-2">' +
                                        '<span class="font-bold text-gray-900 dark:text-zinc-100">' + user.username + '</span>' +
                                        (user.is_active === 0 ? '<span class="px-1.5 py-0.5 text-[10px] font-medium bg-red-100 text-red-800 dark:bg-red-900/30 dark:text-red-400 rounded-md">قطع</span>' : '<span class="px-1.5 py-0.5 text-[10px] font-medium bg-green-100 text-green-800 dark:bg-green-900/30 dark:text-green-400 rounded-md">فعال</span>') +
                                        (user.is_online === 1 ? '<span class="px-1.5 py-0.5 text-[10px] font-medium bg-emerald-500 text-white rounded-md animate-pulse">● آنلاین</span>' : '<span class="px-1.5 py-0.5 text-[10px] font-medium bg-gray-200 text-gray-600 dark:bg-zinc-800 dark:text-zinc-400 rounded-md">آفلاین</span>') +
                                    '</div>' +
                                    '<div class="flex gap-1.5">' +
                                        '<button onclick="copyConfig(\\'' + encodeURIComponent(user.username) + '\\')" title="کپی کانفیگ" class="p-1.5 bg-white dark:bg-zinc-800 border border-gray-200 dark:border-zinc-700 hover:bg-blue-50 dark:hover:bg-blue-900/30 text-blue-600 dark:text-blue-400 rounded-md transition shadow-sm"><svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z"></path></svg></button>' +
                                        '<button onclick="copyJsonConfig(\\'' + encodeURIComponent(user.username) + '\\')" title="کپی JSON" class="p-1.5 bg-white dark:bg-zinc-800 border border-gray-200 dark:border-zinc-700 hover:bg-purple-50 dark:hover:bg-purple-900/30 text-purple-600 dark:text-purple-400 rounded-md transition shadow-sm"><svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10 20l4-16m4 4l4 4-4 4M6 16l-4-4 4-4"></path></svg></button>' +
                                        '<button onclick="showQR(\\'' + encodeURIComponent(user.username) + '\\')" title="کد QR" class="p-1.5 bg-white dark:bg-zinc-800 border border-gray-200 dark:border-zinc-700 hover:bg-green-50 dark:hover:bg-green-900/30 text-green-600 dark:text-green-400 rounded-md transition shadow-sm"><svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v1m6 11h2m-6 0h-2v4m0-11v3m0 0h.01M12 12h4.01M16 20h4M4 12h4m12 0h.01M5 8h2a1 1 0 001-1V5a1 1 0 00-1-1H5a1 1 0 00-1 1v2a1 1 0 001 1zm14 0h2a1 1 0 001-1V5a1 1 0 00-1-1h-2a1 1 0 00-1 1v2a1 1 0 001 1zM5 20h2a1 1 0 001-1v-2a1 1 0 00-1-1H5a1 1 0 00-1 1v2a1 1 0 001 1z"></path></svg></button>' +
                                        '<button onclick="toggleUserStatus(\\'' + encodeURIComponent(user.username) + '\\')" title="' + statusBtnTitle + '" class="p-1.5 bg-white dark:bg-zinc-800 border border-gray-200 dark:border-zinc-700 ' + statusBtnColor + ' rounded-md transition shadow-sm">' + statusBtnIcon + '</button>' +
                                        '<button onclick="editUser(\\'' + encodeURIComponent(user.username) + '\\')" title="ویرایش" class="p-1.5 bg-white dark:bg-zinc-800 border border-gray-200 dark:border-zinc-700 hover:bg-yellow-50 dark:hover:bg-yellow-900/30 text-yellow-600 dark:text-yellow-400 rounded-md transition shadow-sm"><svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z"></path></svg></button>' +
                                        '<button onclick="deleteUser(\\'' + encodeURIComponent(user.username) + '\\')" title="حذف" class="p-1.5 bg-white dark:bg-zinc-800 border border-gray-200 dark:border-zinc-700 hover:bg-red-50 dark:hover:bg-red-950/20 text-red-600 dark:text-red-400 rounded-md transition shadow-sm"><svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"></path></svg></button>' +
                                    '</div>' +
                                '</div>' +
                            '</td>' +
                            '<td class="p-4">' +
                                '<div class="flex flex-col gap-2 min-w-[140px]">' +
                                    '<div class="flex gap-1">' +
                                        '<button onclick="copySubLink(\\'' + encodeURIComponent(user.username) + '\\')" class="flex-1 flex items-center justify-center gap-1.5 px-2 py-1.5 bg-indigo-50 dark:bg-indigo-900/30 text-indigo-600 dark:text-indigo-400 hover:bg-indigo-100 dark:hover:bg-indigo-900/50 rounded-lg text-xs font-bold transition border border-indigo-200 dark:border-indigo-800">' +
                                            '<svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13.828 10.172a4 4 0 00-5.656 0l-4 4a4 4 0 105.656 5.656l1.102-1.101m-.758-4.899a4 4 0 005.656 0l4-4a4 4 0 00-5.656-5.656l-1.1 1.1"></path></svg>' +
                                            'ساب متنی' +
                                        '</button>' +
                                        '<button onclick="showSubQR(\\'' + encodeURIComponent(user.username) + '\\', \\'normal\\')" title="QR ساب متنی" class="px-2 py-1.5 bg-indigo-50 dark:bg-indigo-900/30 text-indigo-600 dark:text-indigo-400 hover:bg-indigo-100 dark:hover:bg-indigo-900/50 rounded-lg text-xs font-bold transition border border-indigo-200 dark:border-indigo-800">' +
                                            '<svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v1m6 11h2m-6 0h-2v4m0-11v3m0 0h.01M12 12h4.01M16 20h4M4 12h4m12 0h.01M5 8h2a1 1 0 001-1V5a1 1 0 00-1-1H5a1 1 0 00-1 1v2a1 1 0 001 1zm14 0h2a1 1 0 001-1V5a1 1 0 00-1-1h-2a1 1 0 00-1 1v2a1 1 0 001 1zM5 20h2a1 1 0 001-1v-2a1 1 0 00-1-1H5a1 1 0 00-1 1v2a1 1 0 001 1z"></path></svg>' +
                                        '</button>' +
                                    '</div>' +
                                    '<div class="flex gap-1">' +
                                        '<button onclick="copyJsonSubLink(\\'' + encodeURIComponent(user.username) + '\\')" class="flex-1 flex items-center justify-center gap-1.5 px-2 py-1.5 bg-purple-50 dark:bg-purple-900/30 text-purple-600 dark:text-purple-400 hover:bg-purple-100 dark:hover:bg-purple-900/50 rounded-lg text-xs font-bold transition border border-purple-200 dark:border-purple-800">' +
                                            '<svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10 20l4-16m4 4l4 4-4 4M6 16l-4-4 4-4"></path></svg>' +
                                            'ساب JSON' +
                                        '</button>' +
                                        '<button onclick="showSubQR(\\'' + encodeURIComponent(user.username) + '\\', \\'json\\')" title="QR ساب JSON" class="px-2 py-1.5 bg-purple-50 dark:bg-purple-900/30 text-purple-600 dark:text-purple-400 hover:bg-purple-100 dark:hover:bg-purple-900/50 rounded-lg text-xs font-bold transition border border-purple-200 dark:border-purple-800">' +
                                            '<svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v1m6 11h2m-6 0h-2v4m0-11v3m0 0h.01M12 12h4.01M16 20h4M4 12h4m12 0h.01M5 8h2a1 1 0 001-1V5a1 1 0 00-1-1H5a1 1 0 00-1 1v2a1 1 0 001 1zm14 0h2a1 1 0 001-1V5a1 1 0 00-1-1h-2a1 1 0 00-1 1v2a1 1 0 001 1zM5 20h2a1 1 0 001-1v-2a1 1 0 00-1-1H5a1 1 0 00-1 1v2a1 1 0 001 1z"></path></svg>' +
                                        '</button>' +
                                    '</div>' +
                                    '<div class="flex gap-1">' +
                                        '<button onclick="copyStatusLink(\\'' + encodeURIComponent(user.username) + '\\')" class="flex-1 flex items-center justify-center gap-1.5 px-2 py-1.5 bg-emerald-50 dark:bg-emerald-900/30 text-emerald-600 dark:text-emerald-400 hover:bg-emerald-100 dark:hover:bg-emerald-900/50 rounded-lg text-xs font-bold transition border border-emerald-200 dark:border-emerald-800">' +
                                            '<svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10 6H6a2 2 0 00-2 2v10a2 2 0 002 2h10a2 2 0 002-2v-4M14 4h6m0 0v6m0-6L10 14"></path></svg>' +
                                            'صفحه وضعیت' +
                                        '</button>' +
                                    '</div>' +
                                '</div>' +
                            '</td>' +
                            '<td class="p-4 text-xs font-mono uppercase text-blue-500 font-semibold">VLESS</td>' +
                            '<td class="p-4 text-xs">' + 
                                '<div class="flex flex-wrap gap-1 max-w-[160px]">' +
                                    String(user.port || "").split(",").map(function(p) {
                                        p = p.trim();
                                        if (!p) return "";
                                        var isTls = tlsPorts.includes(p);
                                        return '<span class="inline-block px-1.5 py-0.5 text-[10px] font-semibold rounded ' + (isTls ? 'bg-blue-100 text-blue-800 dark:bg-blue-900/30 dark:text-blue-400' : 'bg-amber-100 text-amber-800 dark:bg-amber-900/30 dark:text-amber-400') + '">' + p + '</span>';
                                    }).join("") +
                                '</div>' +
                            '</td>' +
                            '<td class="p-4">' + volumeHtml + '</td>' +
                            '<td class="p-4">' + expiryHtml + '</td>' +
                            '<td class="p-4 text-xs text-gray-500">' + createdDate + '</td>' +
                        '</tr>';
                }).join('');
            }
        }

        async function toggleUserStatus(encodedUsername) {
            const username = decodeURIComponent(encodedUsername);
            try {
                const response = await fetch('/api/users/' + encodeURIComponent(username), {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ toggle_only: true })
                });
                if (response.ok) {
                    await loadUsers(true);
                } else {
                    const errData = await response.json();
                    alert('خطا: ' + (errData.error || 'عملیات ناموفق بود'));
                }
            } catch (err) {
                alert('خطا در برقراری ارتباط با سرور');
            }
        }
        async function handleFormSubmit(event) {
            event.preventDefault();
            const submitButton = document.getElementById('submit-btn');
            submitButton.disabled = true;
            submitButton.innerText = isEditMode ? 'در حال ذخیره تغییرات...' : 'در حال ایجاد...';

            const username = document.getElementById('input-name').value;
            const limit = document.getElementById('input-limit').value || null;
            const expiry = document.getElementById('input-expiry').value || null;
            
            // Gather multiple selected ports
            const checkedPorts = Array.from(document.querySelectorAll('input[name="ports"]:checked')).map(cb => cb.value);
            
            // Validation: Ensure at least one port is selected
            if (checkedPorts.length === 0) {
                alert('⚠️ لطفا حداقل یک پورت را برای اتصال انتخاب کنید!');
                submitButton.disabled = false;
                submitButton.innerText = isEditMode ? 'ذخیره تغییرات' : 'ایجاد کاربر';
                return;
            }

            const port = checkedPorts.join(',');
            const tls = checkedPorts.some(p => tlsPorts.includes(p)) ? 'on' : 'off';
            
            const ips = document.getElementById('input-ips').value;
            const fingerprint = document.getElementById('fingerprint-select').value;

            const url = isEditMode ? '/api/users/' + encodeURIComponent(editingUsername) : '/api/users';
            const method = isEditMode ? 'PUT' : 'POST';

            try {
                const response = await fetch(url, {
                    method: method,
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ username, limit_gb: limit, expiry_days: expiry, tls, port, ips, fingerprint })
                });
                
                if (response.ok) {
                    toggleModal(false);
                    await loadUsers(true);
                } else {
                    const errData = await response.json();
                    alert('خطا: ' + (errData.error || 'عملیات ناموفق بود'));
                }
            } catch (err) {
                alert('خطا در برقراری ارتباط با سرور');
            } finally {
                submitButton.disabled = false;
                submitButton.innerText = isEditMode ? 'ذخیره تغییرات' : 'ایجاد کاربر';
            }
        }

        function toggleQRModal(show, link = '', title = 'اسکن کد QR') {
            const modal = document.getElementById('qr-modal');
            const card = modal.querySelector('div');
            const qrBox = document.getElementById('qrcode-box');
            const titleEl = document.getElementById('qr-modal-title');
            if (show) {
                titleEl.innerText = title;
                qrBox.innerHTML = '';
                new QRCode(qrBox, {
                    text: link,
                    width: 192,
                    height: 192,
                    colorDark : "#000000",
                    colorLight : "#ffffff",
                    correctLevel : QRCode.CorrectLevel.M
                });
                modal.classList.remove('opacity-0', 'pointer-events-none');
                modal.classList.add('opacity-100', 'pointer-events-auto');
                card.classList.remove('opacity-0', 'scale-95');
                card.classList.add('opacity-100', 'scale-100');
            } else {
                modal.classList.remove('opacity-100', 'pointer-events-auto');
                modal.classList.add('opacity-0', 'pointer-events-none');
                card.classList.remove('opacity-100', 'scale-100');
                card.classList.add('opacity-0', 'scale-95');
            }
        }

        function getVlessLink(username) {
            const user = window.allUsers.find(u => u.username === username);
            if (!user) return '';
            const host = window.location.hostname;
            
            let ips = [host];
            if (user.ips) {
                const parsedIps = user.ips.split('\\n').map(ip => ip.trim()).filter(ip => ip.length > 0);
                if (parsedIps.length > 0) ips = parsedIps;
            }
            
            const ports = String(user.port || '443').split(',').map(p => p.trim()).filter(p => p.length > 0);
            const fp = user.fingerprint || 'chrome';
            const links = [];

            ips.forEach((ip, ipIndex) => {
                ports.forEach((portStr) => {
                    const isTlsPort = tlsPorts.includes(portStr);
                    const tlsVal = isTlsPort ? 'tls' : 'none';
                    const remark = ips.length > 1 
                        ? (user.username + '-' + (ipIndex + 1) + '-' + portStr) 
                        : (user.username + '-' + portStr);
                    
                    links.push('vle' + 'ss://' + (user.uuid || '') + '@' + ip + ':' + portStr + '?path=%2F&security=' + tlsVal + '&encryption=none&insecure=0&host=' + host + '&fp=' + fp + '&type=ws&allowInsecure=0&sni=' + host + '#' + encodeURIComponent(remark));
                });
            });

            return links.join('\\n');
        }

        function getSubLink(username) {
            return window.location.origin + '/feed/' + encodeURIComponent(username);
        }

        function getJsonSubLink(username) {
            return window.location.origin + '/feed/json/' + encodeURIComponent(username);
        }

        function getStatusLink(username) {
            return window.location.origin + '/status/' + encodeURIComponent(username);
        }

        function copySubLink(encodedUsername) {
            const username = decodeURIComponent(encodedUsername);
            navigator.clipboard.writeText(getSubLink(username)).then(() => {
                alert('✅ لینک ساب متنی با موفقیت کپی شد!');
            }).catch(() => {
                alert('خطا در کپی کردن لینک ساب!');
            });
        }

        function copyStatusLink(encodedUsername) {
            const username = decodeURIComponent(encodedUsername);
            navigator.clipboard.writeText(getStatusLink(username)).then(() => {
                alert('✅ لینک صفحه وضعیت با موفقیت کپی شد!');
            }).catch(() => {
                alert('خطا در کپی کردن لینک صفحه وضعیت!');
            });
        }

        function copyJsonSubLink(encodedUsername) {
            const username = decodeURIComponent(encodedUsername);
            navigator.clipboard.writeText(getJsonSubLink(username)).then(() => {
                alert('✅ لینک ساب JSON با موفقیت کپی شد!');
            }).catch(() => {
                alert('خطا در کپی کردن لینک ساب JSON!');
            });
        }

        function showSubQR(encodedUsername, type) {
            const username = decodeURIComponent(encodedUsername);
            if (type === 'normal') {
                toggleQRModal(true, getSubLink(username), 'QR ساب متنی');
            } else if (type === 'json') {
                toggleQRModal(true, getJsonSubLink(username), 'QR ساب JSON');
            }
        }

        function copyConfig(encodedUsername) {
            const username = decodeURIComponent(encodedUsername);
            const link = getVlessLink(username);
            if (!link) return;
            navigator.clipboard.writeText(link).then(() => {
                alert('✅ کانفیگ VLESS با موفقیت کپی شد!');
            }).catch(() => {
                alert('خطا در کپی کردن کانفیگ!');
            });
        }

        function copyJsonConfig(encodedUsername) {
            const username = decodeURIComponent(encodedUsername);
            const user = window.allUsers.find(u => u.username === username);
            if (!user) return;
            const host = window.location.hostname;
            let ips = [host];
            if (user.ips) {
                ips = user.ips.split('\\n').map(ip => ip.trim()).filter(ip => ip.length > 0);
                if (ips.length === 0) ips = [host];
            }
            
            const ports = String(user.port || '443').split(',').map(p => p.trim()).filter(p => p.length > 0);
            const fp = user.fingerprint || 'chrome';

            const configArray = [];
            ips.forEach((ip, ipIndex) => {
              ports.forEach((portStr) => {
                const isTlsPort = tlsPorts.includes(portStr);
                const tlsVal = isTlsPort ? 'tls' : 'none';
                const remark = ips.length > 1 ? (user.username + ' - IP ' + (ipIndex + 1) + ' - Port ' + portStr) : (user.username + ' - Port ' + portStr);
                
                const jsonConfig = {
                  "remarks": remark,
                  "version": { "min": "25.10.15" },
                  "log": { "loglevel": "none" },
                  "dns": {
                    "servers": [
                      { "address": "https://8.8.8.8/dns-query", "tag": "remote-dns" },
                      { "address": "8.8.8.8", "domains": ["full:" + host], "skipFallback": true }
                    ],
                    "queryStrategy": "UseIP",
                    "tag": "dns"
                  },
                  "inbounds": [
                    {
                      "listen": "127.0.0.1", "port": 10808, "protocol": "socks",
                      "settings": { "auth": "noauth", "udp": true },
                      "sniffing": { "destOverride": ["http", "tls"], "enabled": true, "routeOnly": true },
                      "tag": "mixed-in"
                    },
                    {
                      "listen": "127.0.0.1", "port": 10853, "protocol": "dokodemo-door",
                      "settings": { "address": "1.1.1.1", "network": "tcp,udp", "port": 53 },
                      "tag": "dns-in"
                    }
                  ],
                  "outbounds": [
                    {
                      "protocol": "vle" + "ss",
                      "settings": {
                        ["vne" + "xt"]: [
                          { "address": ip, "port": parseInt(portStr), "users": [{ "id": user.uuid, "encryption": "none" }] }
                        ]
                      },
                      ["stream" + "Settings"]: {
                        "network": "ws",
                        ["ws" + "Settings"]: { "host": host, "path": "/" },
                        "security": tlsVal,
                        "sockopt": { ["dialer" + "Proxy"]: "fragment" }
                      },
                      "tag": "proxy"
                    },
                    {
                      "protocol": "freedom",
                      "settings": {
                        "fragment": {
                          "packets": "tlshello",
                          "length": window.globalFragLen || "20-30",
                          "interval": window.globalFragInt || "1-2"
                        }
                      },
                      "streamSettings": {
                        "sockopt": {
                          "domainStrategy": "UseIP",
                          "happyEyeballs": { "tryDelayMs": 250, "prioritizeIPv6": false, "interleave": 2, "maxConcurrentTry": 4 }
                        }
                      },
                      "tag": "fragment"
                    },
                    { "protocol": "dns", "settings": { "nonIPQuery": "reject" }, "tag": "dns-out" },
                    { "protocol": "freedom", "settings": { "domainStrategy": "UseIP" }, "tag": "direct" },
                    { "protocol": "blackhole", "settings": { "response": { "type": "http" } }, "tag": "block" }
                  ],
                  "routing": {
                    "domainStrategy": "IPIfNonMatch",
                    "rules": [
                      { "inboundTag": ["mixed-in"], "port": 53, "outboundTag": "dns-out", "type": "field" },
                      { "inboundTag": ["dns-in"], "outboundTag": "dns-out", "type": "field" },
                      { "inboundTag": ["remote-dns"], "outboundTag": "proxy", "type": "field" },
                      { "inboundTag": ["dns"], "outboundTag": "direct", "type": "field" },
                      { "domain": ["geosite:private"], "outboundTag": "direct", "type": "field" },
                      { "ip": ["geoip:private"], "outboundTag": "direct", "type": "field" },
                      { "network": "udp", "outboundTag": "block", "type": "field" },
                      { "network": "tcp", "outboundTag": "proxy", "type": "field" }
                    ]
                  }
                };
                
                if (tlsVal === 'tls') {
                  jsonConfig.outbounds[0]["stream" + "Settings"]["tls" + "Settings"] = {
                    "serverName": host, "fingerprint": fp, "alpn": ["http/1.1"], "allowInsecure": false
                  };
                }
                configArray.push(jsonConfig);
              });
            });

            navigator.clipboard.writeText(JSON.stringify(configArray, null, 2)).then(() => {
                alert('✅ کانفیگ JSON با موفقیت کپی شد!');
            }).catch(() => {
                alert('خطا در کپی کردن کانفیگ JSON!');
            });
        }

        function showQR(encodedUsername) {
            const username = decodeURIComponent(encodedUsername);
            const link = getVlessLink(username);
            if (!link) return;
            toggleQRModal(true, link, 'QR کانفیگ VLESS');
        }

        function editUser(encodedUsername) {
            const username = decodeURIComponent(encodedUsername);
            const user = window.allUsers.find(u => u.username === username);
            if (!user) {
                alert('کاربر یافت نشد!');
                return;
            }

            isEditMode = true;
            editingUsername = username;

            document.getElementById('modal-title').innerText = 'ویرایش کاربر: ' + username;
            document.getElementById('submit-btn').innerText = 'ذخیره تغییرات';

            const nameInput = document.getElementById('input-name');
            nameInput.value = username;
            nameInput.disabled = true;

            document.getElementById('input-limit').value = user.limit_gb || '';
            document.getElementById('input-expiry').value = user.expiry_days || '';
            document.getElementById('input-ips').value = user.ips || '';

            document.getElementById('fingerprint-select').value = user.fingerprint || 'chrome';

            const userPorts = String(user.port || '').split(',').map(p => p.trim());
            document.querySelectorAll('input[name="ports"]').forEach(cb => {
                cb.checked = userPorts.includes(cb.value);
            });

            toggleModal(true);
        }

        async function deleteUser(encodedUsername) {
            const username = decodeURIComponent(encodedUsername);
            if (confirm('آیا از حذف کاربر ' + username + ' مطمئن هستید؟')) {
                try {
                    const response = await fetch('/api/users/' + encodeURIComponent(username), { method: 'DELETE' });
                    if (response.ok) {
                        alert('✅ کاربر با موفقیت حذف شد.');
                        await loadUsers(true);
                    } else {
                        const errData = await response.json();
                        alert('خطا: ' + (errData.error || 'عملیات ناموفق بود'));
                    }
                } catch (err) {
                    alert('خطا در برقراری ارتباط با سرور');
                }
            }
        }

        function getFlagEmoji(countryCode) {
            if (!countryCode) return '🌐';
            const codePoints = countryCode.toUpperCase().split('').map(char => 127397 + char.charCodeAt(0));
            try {
                return String.fromCodePoint(...codePoints);
            } catch (e) {
                return '🌐';
            }
        }

        function renderLocationsUI(locations, activeIata) {
            const select = document.getElementById('location-select');
            locations.sort((a, b) => (a.cca2 || '').localeCompare(b.cca2 || ''));

            let html = '<option value="">🌐 پیش‌فرض (لوکیشن خودکار)</option>';
            locations.forEach(loc => {
                if (loc.iata && loc.city) {
                    const flag = getFlagEmoji(loc.cca2);
                    const isSelected = loc.iata.toUpperCase() === activeIata.toUpperCase() ? 'selected' : '';
                    html += '<option value="' + loc.iata + '" ' + isSelected + '>' + flag + ' ' + loc.city + ' (' + loc.iata + ')</option>';
                }
            });
            select.innerHTML = html;
        }

        async function loadLocations() {
            const select = document.getElementById('location-select');
            const cachedLocations = localStorage.getItem('cached_locations_list');
            const cachedActiveIata = localStorage.getItem('cached_active_iata') || '';
            let hasCachedLocs = false;
            
            if (cachedLocations) {
                try {
                    const parsedLocs = JSON.parse(cachedLocations);
                    if (Array.isArray(parsedLocs) && parsedLocs.length > 0) {
                        renderLocationsUI(parsedLocs, cachedActiveIata);
                        hasCachedLocs = true;
                    }
                } catch(e) {}
            }
            
            try {
                const statusRes = await fetch('/api/proxy-ip');
                let activeIata = '';
                if (statusRes.ok) {
                    const statusData = await statusRes.json();
                    activeIata = statusData.iata || '';
                    localStorage.setItem('cached_active_iata', activeIata);
                    
                    if(statusData.frag_len) {
                        window.globalFragLen = statusData.frag_len;
                        document.getElementById('frag-length').value = statusData.frag_len;
                    }
                    if(statusData.frag_int) {
                        window.globalFragInt = statusData.frag_int;
                        document.getElementById('frag-interval').value = statusData.frag_int;
                    }
                }

                const res = await fetch('/locations');
                if (!res.ok) throw new Error();
                const locations = await res.json();
                
                localStorage.setItem('cached_locations_list', JSON.stringify(locations));
                renderLocationsUI(locations, activeIata);
            } catch (err) {
                if (!hasCachedLocs) {
                    select.innerHTML = '<option value="">⚠️ خطا در دریافت لوکیشن‌ها</option>';
                }
            }
        }

        async function saveSettings() {
            const select = document.getElementById('location-select');
            const fragLen = document.getElementById('frag-length').value || "20-30";
            const fragInt = document.getElementById('frag-interval').value || "1-2";
            const iata = select.value;
            const btn = document.getElementById('save-settings-btn');
            
            btn.disabled = true;
            btn.innerText = 'در حال ذخیره...';
            
            try {
                let resolvedIp = 'proxyip.cmliussss.net';
                if (iata) {
                    const domain = iata.toLowerCase() + '.proxyip.cmliussss.net';
                    const dnsRes = await fetch('https://cloudflare-dns.com/dns-query?name=' + domain + '&type=A', {
                        headers: { 'accept': 'application/dns-json' }
                    });
                    resolvedIp = domain;
                    if (dnsRes.ok) {
                        const dnsData = await dnsRes.json();
                        if (dnsData.Answer && dnsData.Answer.length > 0) {
                            const ips = dnsData.Answer.filter(ans => ans.type === 1).map(ans => ans.data);
                            if (ips.length > 0) {
                                resolvedIp = ips[Math.floor(Math.random() * ips.length)];
                            }
                        }
                    }
                }

                const response = await fetch('/api/proxy-ip', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ proxy_ip: resolvedIp, iata: iata ? iata.toUpperCase() : '', frag_len: fragLen, frag_int: fragInt })
                });

                if (response.ok) {
                    window.globalFragLen = fragLen;
                    window.globalFragInt = fragInt;
                    alert('✅ تنظیمات با موفقیت ذخیره شد.\\n' + (iata ? 'آی‌پی پروکسی کلودفلر: ' + resolvedIp : 'آدرس پروکسی به حالت پیش‌فرض بازگشت.'));
                    toggleSettingsModal(false);
                } else {
                    alert('خطا در ذخیره تنظیمات');
                }
            } catch (err) {
                alert('خطا در برقراری ارتباط با سرور');
            } finally {
                btn.disabled = false;
                btn.innerText = 'ذخیره تنظیمات';
            }
        }

        async function changeAdminPassword() {
            const currentPwd = document.getElementById('change-pwd-current').value;
            const newPwd = document.getElementById('change-pwd-new').value;
            const btn = document.getElementById('change-pwd-btn');
            
            if (!currentPwd || !newPwd) {
                alert('⚠️ وارد کردن رمز عبور فعلی و جدید الزامی است!');
                return;
            }
            if (newPwd.length < 4) {
                alert('⚠️ رمز عبور جدید باید حداقل ۴ کاراکتر باشد!');
                return;
            }
            
            btn.disabled = true;
            btn.innerText = 'در حال تغییر...';
            
            try {
                const response = await fetch('/api/change-password', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ current_password: currentPwd, new_password: newPwd })
                });
                
                const data = await response.json();
                if (response.ok && data.success) {
                    alert('✅ رمز عبور با موفقیت تغییر کرد.');
                    document.getElementById('change-pwd-current').value = '';
                    document.getElementById('change-pwd-new').value = '';
                    toggleSettingsModal(false);
                } else {
                    alert('❌ خطا: ' + (data.error || 'عملیات ناموفق بود'));
                }
            } catch (err) {
                alert('خطا در برقراری ارتباط با سرور');
            } finally {
                btn.disabled = false;
                btn.innerText = 'تغییر رمز عبور';
            }
        }

        async function logoutAdmin() {
            if (confirm('⚠️ آیا می‌خواهید از پنل خارج شوید؟')) {
                try {
                    await fetch('/api/logout', { method: 'POST' });
                } catch (err) {}
                window.location.reload();
            }
        }

        document.addEventListener('DOMContentLoaded', () => {
            renderPortCheckboxes();
            loadUsers();
            loadLocations();
            setInterval(() => loadUsers(true), 60000);
        });
    </script>
</body>
</html>`,

  status: `<!DOCTYPE html>
<html lang="fa" dir="rtl" class="dark">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>وضعیت اشتراک کاربر</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <script src="https://cdnjs.cloudflare.com/ajax/libs/qrcodejs/1.0.0/qrcode.min.js"></script>
    <link href="https://cdn.jsdelivr.net/gh/rastikerdar/vazirmatn@v33.003/Vazirmatn-font-face.css" rel="stylesheet" type="text/css" />
    <script>
        tailwind.config = {
            darkMode: 'class',
            theme: {
                extend: {
                    fontFamily: { sans: ['Vazirmatn', 'sans-serif'] },
                    colors: { amoled: { bg: '#000000', card: '#080b0f', input: '#0d1117', border: '#1c2330' } }
                }
            }
        }
    </script>
    <style>
        body { font-family: 'Vazirmatn', sans-serif; }
        .glass {
            background: rgba(10, 10, 10, 0.6);
            backdrop-filter: blur(12px);
            -webkit-backdrop-filter: blur(12px);
            border: 1px solid rgba(255, 255, 255, 0.05);
        }
    </style>
</head>
<body class="bg-gray-50 text-gray-900 dark:bg-amoled-bg dark:text-zinc-100 min-h-screen flex items-center justify-center p-4">
    <div class="w-full max-w-xl glass rounded-3xl shadow-2xl p-6 md:p-8 relative overflow-hidden">
        <!-- Background Orbs -->
        <div class="absolute -left-12 -top-12 w-40 h-40 bg-blue-500/10 rounded-full blur-3xl pointer-events-none"></div>
        <div class="absolute -right-12 -bottom-12 w-40 h-40 bg-purple-500/10 rounded-full blur-3xl pointer-events-none"></div>

        <div class="text-center mb-8 relative z-10">
            <div class="inline-block p-3.5 bg-blue-600/10 text-blue-500 rounded-3xl mb-3 border border-blue-500/20 shadow-lg shadow-blue-500/5">
                <svg class="w-9 h-9" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M9 12l2 2 4-4m5.618-4.016A11.955 11.955 0 0112 2.944a11.955 11.955 0 01-8.618 3.04A12.02 12.02 0 003 9c0 5.591 3.824 10.29 9 11.622 5.176-1.332 9-6.03 9-11.622 0-1.042-.133-2.052-.382-3.016z"></path></svg>
            </div>
            <h1 class="text-xl font-bold tracking-tight text-gray-900 dark:text-white mb-1">پنل زئوس - وضعیت اشتراک</h1>
            <p id="display-username" class="text-sm font-bold text-blue-500 tracking-wide font-mono"></p>
        </div>

        <!-- Connection Status -->
        <div id="status-card" class="mb-6 rounded-2xl p-4 text-center border font-bold relative z-10 transition duration-300">
            <span id="status-text" class="text-sm">در حال بارگذاری وضعیت...</span>
        </div>

        <!-- Progress Cards -->
        <div class="space-y-5 mb-8 relative z-10">
            <!-- Traffic usage card -->
            <div class="bg-white/40 dark:bg-zinc-900/30 border border-gray-200 dark:border-amoled-border rounded-2xl p-5 shadow-sm">
                <div class="flex justify-between items-center mb-3">
                    <span class="text-xs font-semibold text-gray-500 dark:text-zinc-400 flex items-center gap-1.5">
                        <svg class="w-4 h-4 text-blue-500" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 11H5m14 0a2 2 0 012 2v6a2 2 0 01-2 2H5a2 2 0 01-2-2v-6a2 2 0 012-2m14 0V9a2 2 0 00-2-2M5 11V9a2 2 0 012-2m0 0V5a2 2 0 012-2h6a2 2 0 012 2v2M7 7h10"></path></svg>
                        میزان حجم مصرفی
                    </span>
                    <span id="volume-pct" class="text-xs font-bold text-blue-500">۰٪</span>
                </div>
                <div class="w-full bg-gray-200 dark:bg-zinc-800 rounded-full h-2.5 overflow-hidden mb-3">
                    <div id="volume-progress" class="bg-blue-600 h-2.5 rounded-full transition-all duration-1000" style="width: 0%"></div>
                </div>
                <div class="flex justify-between text-xs text-gray-500 dark:text-zinc-400 font-medium">
                    <span>مصرف شده: <span id="used-vol" class="font-bold text-gray-800 dark:text-zinc-200">-</span></span>
                    <span>حجم کل: <span id="limit-vol" class="font-bold text-gray-800 dark:text-zinc-200">-</span></span>
                </div>
            </div>

            <!-- Expiry card -->
            <div class="bg-white/40 dark:bg-zinc-900/30 border border-gray-200 dark:border-amoled-border rounded-2xl p-5 shadow-sm">
                <div class="flex justify-between items-center mb-3">
                    <span class="text-xs font-semibold text-gray-500 dark:text-zinc-400 flex items-center gap-1.5">
                        <svg class="w-4 h-4 text-purple-500" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z"></path></svg>
                        زمان باقی‌مانده اشتراک
                    </span>
                    <span id="expiry-pct" class="text-xs font-bold text-purple-500">۰٪</span>
                </div>
                <div class="w-full bg-gray-200 dark:bg-zinc-800 rounded-full h-2.5 overflow-hidden mb-3 flex justify-end">
                    <div id="expiry-progress" class="bg-purple-600 h-2.5 rounded-full transition-all duration-1000" style="width: 0%"></div>
                </div>
                <div class="flex justify-between text-xs text-gray-500 dark:text-zinc-400 font-medium">
                    <span>باقی‌مانده: <span id="days-remaining" class="font-bold text-gray-800 dark:text-zinc-200">-</span></span>
                    <span>کل اعتبار: <span id="total-days" class="font-bold text-gray-800 dark:text-zinc-200">-</span></span>
                </div>
            </div>
        </div>

        <!-- Configurations Card -->
        <div class="border-t border-gray-100 dark:border-zinc-800 pt-6 relative z-10">
            <h2 class="text-sm font-bold mb-4 flex items-center gap-2">
                <svg class="w-4 h-4 text-emerald-500" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z"></path></svg>
                دریافت کانفیگ و اشتراک‌ها
            </h2>
            <div class="space-y-3">
                <button onclick="copyVlessConfig()" class="w-full flex justify-between items-center px-4 py-3 bg-white dark:bg-amoled-card border border-gray-200 dark:border-amoled-border hover:border-blue-500 dark:hover:border-blue-500 rounded-xl text-xs font-medium transition shadow-sm">
                    <span class="flex items-center gap-2">🚀 کپی کانفیگ VLESS (مستقیم)</span>
                    <span class="text-blue-500">کپی</span>
                </button>
                <button onclick="copyJsonSub()" class="w-full flex justify-between items-center px-4 py-3 bg-white dark:bg-amoled-card border border-gray-200 dark:border-amoled-border hover:border-purple-500 dark:hover:border-purple-500 rounded-xl text-xs font-medium transition shadow-sm">
                    <span class="flex items-center gap-2">🌐 کپی لینک ساب‌اسکریپشن JSON (نوین)</span>
                    <span class="text-purple-500">کپی</span>
                </button>
                <button onclick="copyTextSub()" class="w-full flex justify-between items-center px-4 py-3 bg-white dark:bg-amoled-card border border-gray-200 dark:border-amoled-border hover:border-indigo-500 dark:hover:border-indigo-500 rounded-xl text-xs font-medium transition shadow-sm">
                    <span class="flex items-center gap-2">⛓️ کپی لینک ساب‌اسکریپشن متنی</span>
                    <span class="text-indigo-500">کپی</span>
                </button>
                <button onclick="showQR()" class="w-full flex justify-between items-center px-4 py-3 bg-white dark:bg-amoled-card border border-gray-200 dark:border-amoled-border hover:border-emerald-500 dark:hover:border-emerald-500 rounded-xl text-xs font-medium transition shadow-sm">
                    <span class="flex items-center gap-2">📱 نمایش کد QR کانفیگ VLESS</span>
                    <span class="text-emerald-500">مشاهده</span>
                </button>
            </div>
        </div>
    </div>

    <!-- QR Modal -->
    <div id="qr-modal" class="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/60 backdrop-blur-sm opacity-0 pointer-events-none transition-all duration-300 ease-out">
        <div class="w-full max-w-sm bg-white dark:bg-amoled-card border border-gray-200 dark:border-amoled-border rounded-2xl shadow-xl overflow-hidden p-6 text-center transition-all transform duration-300 opacity-0 scale-95 ease-out">
            <h3 class="font-bold text-gray-900 dark:text-zinc-100 mb-4">اسکن کد QR کانفیگ VLESS</h3>
            <div class="bg-white p-3 rounded-xl inline-block mb-4 border border-gray-100">
                <div id="qrcode-box" class="flex justify-center items-center w-48 h-48 mx-auto"></div>
            </div>
            <button onclick="toggleQRModal(false)" class="w-full py-2 bg-gray-100 hover:bg-gray-200 dark:bg-zinc-800 dark:hover:bg-zinc-700 font-medium rounded-lg text-sm transition text-gray-900 dark:text-zinc-100">بستن</button>
        </div>
    </div>

    <script>
        /* {{USER_DATA_PLACEHOLDER}} */
        
        function toggleQRModal(show, link = '') {
            const modal = document.getElementById('qr-modal');
            const card = modal.querySelector('div');
            const qrBox = document.getElementById('qrcode-box');
            if (show) {
                qrBox.innerHTML = '';
                new QRCode(qrBox, {
                    text: link,
                    width: 192,
                    height: 192,
                    colorDark : "#000000",
                    colorLight : "#ffffff",
                    correctLevel : QRCode.CorrectLevel.M
                });
                modal.classList.remove('opacity-0', 'pointer-events-none');
                modal.classList.add('opacity-100', 'pointer-events-auto');
                card.classList.remove('opacity-0', 'scale-95');
                card.classList.add('opacity-100', 'scale-100');
            } else {
                modal.classList.remove('opacity-100', 'pointer-events-auto');
                modal.classList.add('opacity-0', 'pointer-events-none');
                card.classList.remove('opacity-100', 'scale-100');
                card.classList.add('opacity-0', 'scale-95');
            }
        }

        function getHost() {
            return window.location.host;
        }

        function getVlessLink() {
            const u = window.statusUser;
            const host = getHost();
            var ips = [host];
            if (u.ips) {
                ips = u.ips.split('\\n').map(function(ip) { return ip.trim(); }).filter(function(ip) { return ip.length > 0; });
                if (ips.length === 0) ips = [host];
            }
            var ports = String(u.port || '443').split(',').map(function(p) { return p.trim(); }).filter(function(p) { return p.length > 0; });
            var fp = u.fingerprint || 'chrome';
            var links = [];
            ips.forEach(function(ip, ipIndex) {
                ports.forEach(function(portStr) {
                    var isTlsPort = ['443', '2053', '2083', '2087', '2096', '8443'].includes(portStr);
                    var tlsVal = isTlsPort ? 'tls' : 'none';
                    var remark = ips.length > 1 ? (u.username + '-' + (ipIndex + 1) + '-' + portStr) : (u.username + '-' + portStr);
                    links.push('vle' + 'ss://' + (u.uuid || '') + '@' + ip + ':' + portStr + '?path=%2F&security=' + tlsVal + '&encryption=none&insecure=0&host=' + host + '&fp=' + fp + '&type=ws&allowInsecure=0&sni=' + host + '#' + encodeURIComponent(remark));
                });
            });
            return links.join('\\n');
        }

        function copyVlessConfig() {
            navigator.clipboard.writeText(getVlessLink()).then(() => alert('✅ کانفیگ VLESS با موفقیت کپی شد!'));
        }

        function copyJsonSub() {
            const link = window.location.protocol + '//' + getHost() + '/feed/json/' + encodeURIComponent(window.statusUser.username);
            navigator.clipboard.writeText(link).then(() => alert('✅ لینک ساب JSON کپی شد!'));
        }

        function copyTextSub() {
            const link = window.location.protocol + '//' + getHost() + '/sub/' + encodeURIComponent(window.statusUser.username);
            navigator.clipboard.writeText(link).then(() => alert('✅ لینک ساب متنی کپی شد!'));
        }

        function showQR() {
            toggleQRModal(true, getVlessLink());
        }

        document.addEventListener('DOMContentLoaded', () => {
            const u = window.statusUser;
            if (!u) return;

            document.getElementById('display-username').innerText = '@' + u.username;

            // Compute volume
            const usedGb = u.used_gb || 0;
            const limitGb = u.limit_gb;
            const formattedUsed = usedGb < 1 ? (usedGb * 1024).toFixed(0) + ' MB' : usedGb.toFixed(2) + ' GB';
            document.getElementById('used-vol').innerText = formattedUsed;
            
            let isVolumeExpired = false;
            if (limitGb) {
                document.getElementById('limit-vol').innerText = limitGb + ' GB';
                const pct = Math.min((usedGb / limitGb) * 100, 100);
                document.getElementById('volume-pct').innerText = pct.toFixed(0) + '٪';
                document.getElementById('volume-progress').style.width = pct + '%';
                
                // Color bar
                const hue = 120 - (pct * 1.2);
                document.getElementById('volume-progress').style.backgroundColor = 'hsl(' + hue + ', 80%, 45%)';
                
                if (usedGb >= limitGb) isVolumeExpired = true;
            } else {
                document.getElementById('limit-vol').innerText = 'نامحدود';
                document.getElementById('volume-pct').innerText = '۰٪';
                document.getElementById('volume-progress').style.width = '100%';
                document.getElementById('volume-progress').style.backgroundColor = '#2dd4bf';
            }

            // Compute Expiry
            let daysRemaining = 'نامحدود';
            let totalDays = 'نامحدود';
            let isTimeExpired = false;
            
            if (u.expiry_days) {
                totalDays = u.expiry_days + ' روز';
                if (u.created_at) {
                    const created = new Date(u.created_at);
                    const expiryDate = new Date(created.getTime() + (u.expiry_days * 24 * 60 * 60 * 1000));
                    const diffDays = Math.ceil((expiryDate - new Date()) / (1000 * 60 * 60 * 24));
                    daysRemaining = diffDays > 0 ? diffDays : 0;
                    
                    const pct = Math.max(0, Math.min(100, (daysRemaining / u.expiry_days) * 100));
                    document.getElementById('expiry-pct').innerText = pct.toFixed(0) + '٪';
                    document.getElementById('expiry-progress').style.width = pct + '%';
                    
                    const hue = pct * 1.2;
                    document.getElementById('expiry-progress').style.backgroundColor = 'hsl(' + hue + ', 80%, 45%)';
                    
                    if (new Date() > expiryDate) isTimeExpired = true;
                }
            } else {
                document.getElementById('expiry-pct').innerText = '۰٪';
                document.getElementById('expiry-progress').style.width = '100%';
                document.getElementById('expiry-progress').style.backgroundColor = '#14b8a6';
            }
            
            document.getElementById('days-remaining').innerText = daysRemaining === 'نامحدود' ? 'نامحدود' : daysRemaining + ' روز';
            document.getElementById('total-days').innerText = totalDays;

            // Set Status
            const statusCard = document.getElementById('status-card');
            const statusText = document.getElementById('status-text');
            
            if (u.is_active === 0) {
                statusCard.className = 'mb-6 rounded-2xl p-4 text-center border font-bold relative z-10 bg-red-500/10 border-red-500/30 text-red-500 shadow-md shadow-red-500/5';
                statusCard.style.boxShadow = 'inset 0 0 12px rgba(239, 68, 68, 0.1)';
                statusText.innerText = '❌ وضعیت اشتراک: غیرفعال / مسدود دستی';
            } else if (isVolumeExpired) {
                statusCard.className = 'mb-6 rounded-2xl p-4 text-center border font-bold relative z-10 bg-yellow-500/10 border-yellow-500/30 text-yellow-500 shadow-md shadow-yellow-500/5';
                statusText.innerText = '⚠️ وضعیت اشتراک: تمام شدن حجم مجاز';
            } else if (isTimeExpired) {
                statusCard.className = 'mb-6 rounded-2xl p-4 text-center border font-bold relative z-10 bg-yellow-500/10 border-yellow-500/30 text-yellow-500 shadow-md shadow-yellow-500/5';
                statusText.innerText = '⏳ وضعیت اشتراک: منقضی شده (پایان زمان اعتبار)';
            } else {
                statusCard.className = 'mb-6 rounded-2xl p-4 text-center border font-bold relative z-10 bg-emerald-500/10 border-emerald-500/30 text-emerald-500 shadow-md shadow-emerald-500/5';
                statusText.innerText = '✅ وضعیت اشتراک: فعال و متصل';
            }
        });
    </script>
</body>
</html>`
};
