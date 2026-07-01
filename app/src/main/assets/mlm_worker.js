import { connect } from 'cloudflare:sockets';

// ==========================================================
// ۱. حافظه‌های موقت و متغیرهای سراسری (GLOBAL STATE)
// ==========================================================
const GLOBAL_TRAFFIC_CACHE = new Map();
const ACTIVE_CONNECTIONS_COUNT = new Map();
const GLOBAL_LAST_ACTIVE_WRITE = new Map();
const DNS_CACHE = new Map();
const XHTTP_SESSIONS = new Map();

function getOrCreateSession(sessionId) {
  let session = XHTTP_SESSIONS.get(sessionId);
  if (!session) {
    let resolveDownstream;
    const downstreamPromise = new Promise(resolve => { resolveDownstream = resolve; });
    session = {
      id: sessionId,
      downstreamPromise,
      resolveDownstream,
      sharedUpstreamController: null
    };
    XHTTP_SESSIONS.set(sessionId, session);
    setTimeout(() => { XHTTP_SESSIONS.delete(sessionId); }, 60000);
  }
  return session;
}

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
// تشخیص خودکار بایندینگ دیتابیس D1
// اگر بایندینگ را با نام DB ست کنی همان استفاده می‌شود؛ در غیر این صورت
// اولین بایندینگی که شکل D1 دارد (دارای متد prepare) به env.DB نگاشت می‌شود.
// این یعنی بعد از افزودن D1 از بخش Bindings داشبورد، با هر نامی شناسایی می‌شود.
// ==========================================================
// لاگ تشخیصی (در wrangler tail یا Logs داشبورد دیده می‌شود)
// لاگ‌گذاری به سیستم لاگ کلادفلر غیرفعال است (هیچ console خروجی‌ای — کاهش ردپا)
function LOG() { }

// لاگ تشخیصی امن: فقط وقتی متغیر DEBUG=1 باشد، در دیتابیس D1 (خصوصی) نوشته می‌شود.
// هیچ‌چیز به سیستم لاگ/observability کلادفلر نمی‌رود؛ از طریق /api/logs در پنل دیده می‌شود.
function dbg(env, ctx, line) {
  try {
    if (!env || env.DEBUG !== '1' || !env.DB) return;
    const task = env.DB.prepare("INSERT INTO debug_logs (ts, line) VALUES (?, ?)").bind(Date.now(), String(line)).run();
    if (ctx && ctx.waitUntil) ctx.waitUntil(task.catch(() => { })); else task.catch(() => { });
  } catch (e) { }
}

function isD1Binding(value) {
  return value && typeof value === 'object'
    && typeof value.prepare === 'function'
    && (typeof value.batch === 'function' || typeof value.exec === 'function');
}

function resolveDatabaseBinding(env) {
  if (!env || typeof env !== 'object') return;
  if (isD1Binding(env.DB)) return;
  for (const key of Object.keys(env)) {
    if (isD1Binding(env[key])) {
      try { env.DB = env[key]; } catch (e) { }
      return;
    }
  }
}

// ==========================================================
// ۳. نقطه ورود اصلی ورکر (MAIN FETCH HANDLER)
// ==========================================================
export default {
  async fetch(request, env, ctx) {
    try {
      return await handleRequest(request, env, ctx);
    } catch (err) {
      try { dbg(env, ctx, 'FATAL ' + (err && (err.stack || err.message) || String(err))); } catch (e) { }
      // پاسخ خنثی (بدون افشای خطا به بیرون)
      return new Response('Service Unavailable', { status: 503, headers: { 'Content-Type': 'text/plain; charset=utf-8' } });
    }
  }
};

async function handleRequest(request, env, ctx) {
  const url = new URL(request.url);
  const upgrade = (request.headers.get('Upgrade') || '').toLowerCase();

  // تشخیص خودکار بایندینگ D1: هر اسمی که در بخش Bindings داشبورد بدهی پیدا می‌شود
  resolveDatabaseBinding(env);
  if (!env.DB) {
    return new Response("Database binding not found. Add a D1 binding in Settings → Bindings.", { status: 500 });
  }
  await DbService.ensureSchema(env.DB);
  dbg(env, ctx, 'REQ ' + request.method + ' ' + url.pathname + ' body=' + (!!request.body) + ' ct=' + (request.headers.get('content-type') || '-') + ' upg=' + (upgrade || '-'));
  const reserved = url.pathname.startsWith('/api/') || url.pathname.startsWith('/sub/') ||
    url.pathname.startsWith('/feed/') || url.pathname.startsWith('/status/') ||
    url.pathname === '/admin' || url.pathname === '/locations';

  // پشتیبانی از XHTTP Split Mode (GET برای دانلود، POST برای آپلود)
  if (request.method === 'GET' && !reserved) {
    const matchGet = url.pathname.match(/^\/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$/i);
    if (matchGet) {
      dbg(env, ctx, 'ROUTE xhttp-down ' + url.pathname);
      const sessionId = matchGet[1].toLowerCase();
      const session = getOrCreateSession(sessionId);
      let bridgeController = null;
      const stream = new ReadableStream({
        start(controller) {
          bridgeController = controller;
          session.resolveDownstream(controller);
        },
        cancel() { }
      }, { highWaterMark: 1024 * 1024 });
      return new Response(stream, {
        status: 200,
        headers: { 'Content-Type': 'application/octet-stream', 'X-Accel-Buffering': 'no', 'Cache-Control': 'no-store' }
      });
    }
  }

  // پشتیبانی از ارسال پارت‌های بعدی در حالت XHTTP packet-up
  if (request.method === 'POST' && !reserved) {
    const matchUp = url.pathname.match(/^\/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\/([1-9][0-9]*)$/i);
    if (matchUp) {
      const sessionId = matchUp[1].toLowerCase();
      const seq = parseInt(matchUp[2], 10);
      dbg(env, ctx, 'ROUTE xhttp-up ' + url.pathname);
      const session = getOrCreateSession(sessionId);

      if (request.body) {
        let controller = session.sharedUpstreamController;
        for (let i = 0; i < 50 && !controller; i++) {
          await new Promise(r => setTimeout(r, 100));
          controller = session.sharedUpstreamController;
        }
        if (controller) {
          const reader = request.body.getReader();
          try {
            while (true) {
              const { value, done } = await reader.read();
              if (done) break;
              if (value && value.byteLength) {
                while (controller.desiredSize !== null && controller.desiredSize <= 0) {
                  await new Promise(r => setTimeout(r, 50));
                }
                controller.enqueue(convertToUint8Array(value));
              }
            }
          } catch (e) {
            dbg(env, ctx, 'xhttp-up ' + seq + ' ERROR: ' + (e && e.message || e));
          }
        }
        return new Response("OK", { status: 200 });
      }
    }
  }

  // کانال داده اصلی (هر درخواست POST دارای بدنه که مسیر کنترلی نیست)
  if (request.method === 'POST' && upgrade !== 'websocket' && request.body && !reserved) {
    dbg(env, ctx, 'ROUTE transport ' + url.pathname);
    return await Router.handleTransport(request, env, ctx);
  }

  // مسیر جایگزین وب‌سوکت (برای کلاینت‌های قدیمی)
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

  // پوسته مدیریتی پنل ( ورود از طریق آدرس /admin )
  if (url.pathname === '/admin') {
    return await Router.handlePanel(request, env);
  }

  // صفحه وضعیت کاربر
  if (url.pathname.startsWith('/status/')) {
    return await Router.handleUserStatus(url, env);
  }

  // صفحه استتار برای تمامی مسیرهای متفرقه
  dbg(env, ctx, 'ROUTE camouflage ' + request.method + ' ' + url.pathname);
  return new Response(HTML_TEMPLATES.nginx, {
    headers: { "Content-Type": "text/html; charset=utf-8" }
  });
}

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
      } catch (e) { }

      const mockStoredData = { proxy_ip: proxyIP };
      return handleVLESS(env, mockStoredData, ctx);
    } catch (e) {
      return new Response("Internal Server Error", { status: 500 });
    }
  },

  async handleTransport(request, env, ctx) {
    try {
      let proxyIP = "proxyip.cmliussss.net";
      try {
        const proxyRow = await env.DB.prepare("SELECT value FROM settings WHERE key = 'proxy_ip'").first();
        if (proxyRow && proxyRow.value) proxyIP = proxyRow.value;
      } catch (e) { }
      return await handleXHTTP(request, env, { proxy_ip: proxyIP }, ctx);
    } catch (e) {
      // پاسخ خنثی تا رفتار مانند یک وب‌سرور عادی بماند
      return new Response("Bad Request", { status: 400 });
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
    const hasPassword = await DbService.getAdminHash(env);
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
        daily_limit_gb: user.daily_limit_gb,
        daily_used_gb: user.daily_used_gb,
        expiry_days: user.expiry_days,
        used_gb: user.used_gb,
        is_active: user.is_active,
        created_at: user.created_at,
        tls: user.tls,
        port: user.port,
        ips: user.ips,
        proxy_ip: user.proxy_ip || '',
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
    const hasPassword = await DbService.getAdminHash(env);

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
      const storedHash = await DbService.getAdminHash(env);
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
      return new Response(JSON.stringify({ error: "Unauthorized", expected: await DbService.getAdminHash(env), received: request.headers.get("Cookie") }), {
        status: 401, headers: { "Content-Type": "application/json; charset=utf-8" }
      });
    }

    // API: لاگ تشخیصی (فقط برای مدیر) — مشاهده و پاک‌سازی
    if (url.pathname === '/api/logs') {
      if (request.method === 'DELETE') {
        try { await env.DB.prepare("DELETE FROM debug_logs").run(); } catch (e) { }
        return new Response(JSON.stringify({ success: true }), { headers: { "Content-Type": "application/json" } });
      }
      let lines = [];
      try {
        const { results } = await env.DB.prepare("SELECT ts, line FROM debug_logs ORDER BY id DESC LIMIT 400").all();
        lines = (results || []).map(r => new Date(r.ts).toISOString().replace('T', ' ').replace('Z', '') + '  ' + r.line);
      } catch (e) { lines = ['(no logs / table empty)']; }
      const dbgOn = (env.DEBUG === '1');
      const header = 'DEBUG=' + (dbgOn ? 'ON' : 'OFF') + '  |  ' + lines.length + ' lines  |  newest first\n' +
        (dbgOn ? '' : '⚠ DEBUG غیرفعال است. برای ثبت لاگ، متغیر DEBUG=1 را در تنظیمات ورکر ست کن.\n') +
        '────────────────────────────────────────\n';
      return new Response(header + lines.join('\n'), {
        headers: { "Content-Type": "text/plain; charset=utf-8", "Cache-Control": "no-store" }
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
      const storedHash = await DbService.getAdminHash(env);
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
        if (proxy_ip !== undefined) await env.DB.prepare("INSERT OR REPLACE INTO settings (key, value) VALUES ('proxy_ip', ?)").bind(proxy_ip).run();
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
            const { limit_gb, daily_limit_gb, expiry_days, ips, tls, port, fingerprint, proxy_ip } = body;
            await env.DB.prepare(
              "UPDATE users SET limit_gb = ?, daily_limit_gb = ?, expiry_days = ?, ips = ?, tls = ?, port = ?, fingerprint = ?, proxy_ip = ? WHERE username = ?"
            ).bind(
              limit_gb ? parseFloat(limit_gb) : null,
              daily_limit_gb ? parseFloat(daily_limit_gb) : null,
              expiry_days ? parseInt(expiry_days) : null,
              ips || null,
              tls,
              port,
              fingerprint || 'chrome',
              proxy_ip === 'none' ? 'none' : (proxy_ip || null),
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
          } catch (e) { }
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
          const { username, limit_gb, daily_limit_gb, expiry_days, ips, tls, port, fingerprint, proxy_ip } = await request.json();
          if (!username) {
            return new Response(JSON.stringify({ error: "نام کاربری اجباری است" }), { status: 400, headers: { "Content-Type": "application/json" } });
          }
          const uuid = crypto.randomUUID();
          try {
            await env.DB.prepare(
              "INSERT INTO users (username, uuid, limit_gb, daily_limit_gb, expiry_days, ips, connection_type, tls, port, fingerprint, proxy_ip, daily_reset_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            ).bind(
              username,
              uuid,
              limit_gb ? parseFloat(limit_gb) : null,
              daily_limit_gb ? parseFloat(daily_limit_gb) : null,
              expiry_days ? parseInt(expiry_days) : null,
              ips || null,
              atob('dmxlc3M='),
              tls,
              port,
              fingerprint || 'chrome',
              proxy_ip === 'none' ? 'none' : (proxy_ip || null),
              Date.now()
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
    } catch (e) { }
    try { await db.prepare("ALTER TABLE users ADD COLUMN is_active INTEGER DEFAULT 1").run(); } catch (e) { }
    try { await db.prepare("ALTER TABLE users ADD COLUMN last_active INTEGER").run(); } catch (e) { }
    try { await db.prepare("ALTER TABLE users ADD COLUMN fingerprint TEXT DEFAULT 'chrome'").run(); } catch (e) { }
    try { await db.prepare("ALTER TABLE users ADD COLUMN daily_limit_gb REAL").run(); } catch (e) { }
    try { await db.prepare("ALTER TABLE users ADD COLUMN daily_used_gb REAL DEFAULT 0").run(); } catch (e) { }
    try { await db.prepare("ALTER TABLE users ADD COLUMN daily_reset_at INTEGER DEFAULT 0").run(); } catch (e) { }
    try { await db.prepare("ALTER TABLE users ADD COLUMN proxy_ip TEXT").run(); } catch (e) { }
    try { await db.prepare("CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT)").run(); } catch (e) { }
    try { await db.prepare("CREATE TABLE IF NOT EXISTS debug_logs (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, line TEXT)").run(); } catch (e) { }
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
    const storedPasswordHash = await this.getAdminHash(env);
    if (!storedPasswordHash) return true;
    const cookies = request.headers.get('Cookie') || '';
    const sessionCookie = cookies.split(';').find(c => c.trim().startsWith('panel_session='));
    if (!sessionCookie) return false;
    const sessionToken = sessionCookie.split('=')[1].trim();
    return sessionToken === storedPasswordHash;
  },

  // هش رمز مدیریت: اولویت با Secret ورکر (ADMIN_PASSWORD)، در غیر این صورت دیتابیس
  async getAdminHash(env) {
    if (env && env.ADMIN_PASSWORD) return await this.sha256(String(env.ADMIN_PASSWORD));
    return await this.getPanelPassword(env.DB);
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
    } catch (e) { }

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
                network: ('xh' + 'ttp'),
                ['xh' + 'ttp' + 'Settings']: { host: host, path: "/", mode: 'auto' },
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
            alpn: ["h2", "http/1.1"],
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

        links.push(atob('dmxlc3M6Ly8=') + user.uuid + '@' + ip + ':' + portStr + '?type=xhttp&security=' + tlsVal + '&sni=' + host + '&host=' + host + '&path=%2F&fp=' + fp + '&alpn=h2,http/1.1&encryption=none&allowInsecure=0&mode=auto#' + encodeURIComponent(remark));
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
// ۷. موتور اتصال و مدیریت ترافیک (CORE ENGINE)
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
      } catch (e) { }
    } else {
      clearInterval(heartbeat);
    }
  }, 15000);

  let remoteConnWrapper = { socket: null, connectingPromise: null, retryConnect: null };
  let reqUUID = null;
  let isHeaderParsed = false;
  let isDnsQuery = false;
  let chunkBuffer = new Uint8Array(0);
  let globalProxyIP = storedData?.proxy_ip || "proxyip.cmliussss.net";
  let userProxyIP = null;

  let wsChain = Promise.resolve();
  let wsStopped = false, wsFailed = false, wsFinished = false;
  let wsQueueBytes = 0, wsQueueItems = 0;
  let currentSocketWriter = null, activeRemoteWriter = null;

  const releaseRemoteWriter = () => {
    if (activeRemoteWriter) {
      try { activeRemoteWriter.releaseLock(); } catch (e) { }
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
      try { remoteConnWrapper.socket?.close(); } catch (e) { }
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
      } catch (e) { }

      if (!user || user.is_active === 0) {
        serverSock.close();
        return;
      }

      if (user.proxy_ip) {
        userProxyIP = user.proxy_ip;
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
          } catch (e) { }
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
          } catch (e) { }
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
            let s;
            try {
              if (userProxyIP) {
                try {
                  s = await connectDirect(userProxyIP, port, dataPayload);
                } catch (proxyErr) {
                  // Fallback to direct if proxy fails
                  s = await connectDirect(addr, port, dataPayload);
                }
              } else {
                try {
                  s = await connectDirect(addr, port, dataPayload);
                } catch (err) {
                  if (useFallback && globalProxyIP && globalProxyIP !== 'none' && userProxyIP !== 'none') {
                    s = await connectDirect(globalProxyIP, port, dataPayload);
                  } else {
                    throw err;
                  }
                }
              }
            } catch (err) {
              throw err;
            }
            remoteConnWrapper.socket = s;
            s.closed.catch(() => { }).finally(() => closeSocketQuietly(serverSock));
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
// موتور انتقال داده (درخواست POST دوطرفه روی HTTP/2)
// ==========================================================
function denyTransport(reason) {
  if (reason) LOG('deny:', reason);
  // پاسخی که شبیه یک سرویس وب عادی است (بدون افشای ماهیت)
  return new Response("OK", { status: 200, headers: { "Content-Type": "text/plain; charset=utf-8" } });
}

async function handleXHTTP(request, env, storedData = null, ctx = null) {
  if (!request.body) return denyTransport();
  let globalProxyIP = storedData?.proxy_ip || "proxyip.cmliussss.net";
  let userProxyIP = null;
  const reader = request.body.getReader();

  let buf = new Uint8Array(0);
  let finished = false;
  const need = async (n) => {
    while (buf.byteLength < n && !finished) {
      const r = await reader.read();
      if (r.done) { finished = true; break; }
      if (r.value && r.value.byteLength) buf = concatBytes(buf, r.value);
    }
    return buf.byteLength >= n;
  };

  // --- خواندن و تجزیه هدر به صورت تدریجی ---
  if (!(await need(18))) { try { reader.releaseLock(); } catch (e) { } return denyTransport(); }
  const version = buf[0];
  const reqUUID = extractUUIDFromVless(buf);
  const optLen = buf[17];
  let offset = 18 + optLen;
  if (!(await need(offset + 4))) { try { reader.releaseLock(); } catch (e) { } return denyTransport(); }
  const cmd = buf[offset++];
  const port = (buf[offset++] << 8) | buf[offset++];
  const addrType = buf[offset++];

  let addr = '';
  if (addrType === 1) {
    if (!(await need(offset + 4))) { try { reader.releaseLock(); } catch (e) { } return denyTransport(); }
    addr = `${buf[offset++]}.${buf[offset++]}.${buf[offset++]}.${buf[offset++]}`;
  } else if (addrType === 2) {
    if (!(await need(offset + 1))) { try { reader.releaseLock(); } catch (e) { } return denyTransport(); }
    const domainLen = buf[offset++];
    if (!(await need(offset + domainLen))) { try { reader.releaseLock(); } catch (e) { } return denyTransport(); }
    addr = new TextDecoder().decode(buf.slice(offset, offset + domainLen));
    offset += domainLen;
  } else if (addrType === 3) {
    if (!(await need(offset + 16))) { try { reader.releaseLock(); } catch (e) { } return denyTransport(); }
    const seg = [];
    for (let i = 0; i < 8; i++) seg.push(((buf[offset + i * 2] << 8) | buf[offset + i * 2 + 1]).toString(16));
    addr = seg.join(':');
    offset += 16;
  } else {
    try { reader.releaseLock(); } catch (e) { } return denyTransport('invalid addrType ' + addrType);
  }

  const rawData = buf.slice(offset);
  const respHeader = new Uint8Array([version, 0]);

  dbg(env, ctx, 'HDR uuid=' + reqUUID + ' cmd=' + cmd + ' atype=' + addrType + ' dst=' + addr + ':' + port + ' raw=' + rawData.byteLength);

  // --- اعتبارسنجی کاربر و محدودیت‌ها ---
  let user = null;
  try { user = await env.DB.prepare("SELECT * FROM users WHERE uuid = ?").bind(reqUUID).first(); } catch (e) { dbg(env, ctx, 'DB lookup error: ' + (e && e.message || e)); }
  if (!user || user.is_active === 0) { dbg(env, ctx, 'REJECT no-user-or-inactive uuid=' + reqUUID); try { reader.releaseLock(); } catch (e) { } return denyTransport(); }

  if (user.proxy_ip) {
    userProxyIP = user.proxy_ip;
  }

  const now = Date.now();
  // بازنشانی مصرف روزانه در صورت گذشت ۲۴ ساعت
  if ((now - (user.daily_reset_at || 0)) > 86400000) {
    try { await env.DB.prepare("UPDATE users SET daily_used_gb = 0, daily_reset_at = ? WHERE username = ?").bind(now, user.username).run(); } catch (e) { }
    user.daily_used_gb = 0;
  }
  if (isUserCapped(user)) { dbg(env, ctx, 'REJECT capped/expired user=' + user.username + ' used=' + user.used_gb + '/' + user.limit_gb + ' daily=' + user.daily_used_gb + '/' + user.daily_limit_gb); try { reader.releaseLock(); } catch (e) { } return denyTransport(); }

  const username = user.username;
  dbg(env, ctx, 'START user=' + username + ' dst=' + addr + ':' + port);

  // --- حسابداری ترافیک (مصرف کل + روزانه) ---
  const commit = (deltaGb) => {
    const task = async () => {
      try {
        await env.DB.prepare("UPDATE users SET used_gb = used_gb + ?, daily_used_gb = COALESCE(daily_used_gb,0) + ? WHERE username = ?").bind(deltaGb, deltaGb, username).run();
      } catch (e) {
        GLOBAL_TRAFFIC_CACHE.set(username, (GLOBAL_TRAFFIC_CACHE.get(username) || 0) + deltaGb * 1073741824);
      }
    };
    if (ctx) ctx.waitUntil(task()); else task();
  };
  const addBytes = (bytes) => {
    if (!bytes || bytes <= 0) return;
    let current = (GLOBAL_TRAFFIC_CACHE.get(username) || 0) + bytes;
    GLOBAL_LAST_ACTIVE_WRITE.set(username, Date.now());
    const threshold = 50 * 1024 * 1024;
    if (current >= threshold) {
      const chunks = Math.floor(current / threshold);
      const bytesToCommit = chunks * threshold;
      GLOBAL_TRAFFIC_CACHE.set(username, current - bytesToCommit);
      commit(bytesToCommit / 1073741824);
    } else {
      GLOBAL_TRAFFIC_CACHE.set(username, current);
    }
  };

  let offlineDone = false;
  const setOffline = () => {
    if (offlineDone) return;
    offlineDone = true;
    let activeCount = (ACTIVE_CONNECTIONS_COUNT.get(username) || 1) - 1;
    if (activeCount <= 0) {
      ACTIVE_CONNECTIONS_COUNT.delete(username);
      const cached = GLOBAL_TRAFFIC_CACHE.get(username) || 0;
      if (cached > 0) { GLOBAL_TRAFFIC_CACHE.set(username, 0); commit(cached / 1073741824); }
    } else {
      ACTIVE_CONNECTIONS_COUNT.set(username, activeCount);
    }
  };
  // ثبت آنلاین شدن
  {
    const activeCount = ACTIVE_CONNECTIONS_COUNT.get(username) || 0;
    ACTIVE_CONNECTIONS_COUNT.set(username, activeCount + 1);
    const t = async () => { try { GLOBAL_LAST_ACTIVE_WRITE.set(username, now); await env.DB.prepare("UPDATE users SET last_active = ? WHERE username = ?").bind(now, username).run(); } catch (e) { } };
    if (ctx) ctx.waitUntil(t()); else t();
  }

  const reqUrl = new URL(request.url);
  let session = null;
  const matchPost = reqUrl.pathname.match(/^\/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\/0$/i);
  if (matchPost) {
    session = getOrCreateSession(matchPost[1].toLowerCase());
  }

  const responseHeaders = {
    'Content-Type': 'application/octet-stream',
    'X-Accel-Buffering': 'no',
    'Cache-Control': 'no-store'
  };

  return new Response(new ReadableStream({
    async start(controller) {
      // نظارت دوره‌ای: قطع اتصال هنگام عبور از سقف یا انقضا
      const guard = setInterval(async () => {
        try {
          const u = await env.DB.prepare("SELECT is_active, limit_gb, used_gb, daily_limit_gb, daily_used_gb, expiry_days, created_at FROM users WHERE uuid = ?").bind(reqUUID).first();
          if (!u || isUserCapped(u)) {
            try { await env.DB.prepare("UPDATE users SET last_active = 0 WHERE uuid = ?").bind(reqUUID).run(); } catch (e) { }
            clearInterval(guard);
            try { controller.close(); } catch (e) { }
          }
        } catch (e) { }
      }, 30000);

      try {
        // مسیر DNS (UDP روی پورت ۵۳)
        if (cmd === 2) {
          const bridge = {
            readyState: 1,
            send(data) { try { controller.enqueue(convertToUint8Array(data)); } catch (e) { this.readyState = 3; } },
            close() { if (this.readyState === 3) return; this.readyState = 3; try { controller.close(); } catch (e) { } }
          };
          if (port === 53) {
            if (rawData.byteLength) { addBytes(rawData.byteLength); await forwardVlessUDP(rawData, bridge, respHeader); }
            while (true) {
              const { value, done } = await reader.read();
              if (done) break;
              if (value && value.byteLength) { addBytes(value.byteLength); await forwardVlessUDP(value, bridge, null); }
            }
          }
          return;
        }

        // مسیر TCP: اولویت با کاربر در صورت داشتن پروکسی اختصاصی، در غیر اینصورت حالت مستقیم
        let socket = null;
        let route = 'direct';

        if (userProxyIP) {
          try {
            socket = await connectDirect(userProxyIP, port, rawData);
            route = 'proxyIP';
            dbg(env, ctx, 'CONNECT proxyIP ok ' + userProxyIP + ':' + port);
          } catch (err) {
            dbg(env, ctx, 'CONNECT proxyIP FAIL ' + userProxyIP + ':' + port + ' -> ' + (err && err.message || err));
            try {
              socket = await connectDirect(addr, port, rawData);
              route = 'direct';
              dbg(env, ctx, 'CONNECT direct ok (fallback) ' + addr + ':' + port);
            } catch (e2) {
              dbg(env, ctx, 'CONNECT direct FAIL ' + addr + ':' + port + ' -> ' + (e2 && e2.message || e2));
              return;
            }
          }
        } else {
          try {
            socket = await connectDirect(addr, port, rawData);
            route = 'direct';
            dbg(env, ctx, 'CONNECT direct ok ' + addr + ':' + port);
          } catch (err) {
            dbg(env, ctx, 'CONNECT direct FAIL ' + addr + ':' + port + ' -> ' + (err && err.message || err));
            if (globalProxyIP && globalProxyIP !== 'none' && userProxyIP !== 'none') {
              try {
                socket = await connectDirect(globalProxyIP, port, rawData);
                route = 'proxyIP';
                dbg(env, ctx, 'CONNECT proxyIP ok (fallback) ' + globalProxyIP + ':' + port);
              } catch (e2) {
                dbg(env, ctx, 'CONNECT proxyIP FAIL ' + globalProxyIP + ':' + port + ' -> ' + (e2 && e2.message || e2));
                return;
              }
            } else { return; }
          }
        }
        socket.closed.catch((e) => { dbg(env, ctx, 'socket closed: ' + (e && e.message || e)); });

        const connectionStartTime = Date.now();
        let sharedUpstreamController = null;
        const sharedUpstream = new ReadableStream({ start(c) { sharedUpstreamController = c; } });
        if (session) session.sharedUpstreamController = sharedUpstreamController;

        let upBytes = 0, downBytes = 0;
        let localWriteLock = Promise.resolve();

        // آپلود: بدنه‌ی درخواست → سوکت مقصد
        const upPump = (async () => {
          try {
            const writer = socket.writable.getWriter();

            const safeWrite = async (chunk) => {
              upBytes += chunk.byteLength;
              addBytes(chunk.byteLength);
              localWriteLock = localWriteLock.then(() => writer.write(chunk)).catch(e => { throw e; });
              await localWriteLock;
            };

            const pumpOriginal = async () => {
              try {
                while (true) {
                  const { value, done } = await reader.read();
                  if (done) break;
                  if (value && value.byteLength) {
                    await safeWrite(convertToUint8Array(value));
                  }
                }
              } catch (e) { }
            };

            const pumpShared = async () => {
              const r = sharedUpstream.getReader();
              try {
                while (true) {
                  const { value, done } = await r.read();
                  if (done) break;
                  if (value && value.byteLength) {
                    await safeWrite(convertToUint8Array(value));
                  }
                }
              } catch (e) { }
            };

            pumpShared();
            await pumpOriginal();

            if (!session) {
              try { await writer.close(); } catch (e) { }
            }
          } catch (e) {
            dbg(env, ctx, 'upstream error: ' + (e && e.message || e));
            try { socket.close(); } catch (_) { }
          }
        })();

        // صبر برای درخواست GET در حالت Split
        let actualController = controller;
        if (session) {
          actualController = await Promise.race([
            session.downstreamPromise,
            new Promise(r => setTimeout(() => r(controller), 4000))
          ]);
        }

        // ارسال فوری هدر VLESS به downstream (کلاینت منتظر این هدر است)
        try { actualController.enqueue(respHeader); } catch (e) { }

        const bridge = {
          readyState: 1,
          isXHTTP: true,
          get bufferedAmount() { return 0; },
          send(data) {
            try {
              actualController.enqueue(convertToUint8Array(data));
            } catch (e) {
              this.readyState = 3;
            }
          },
          close() {
            if (this.readyState === 3) return;
            this.readyState = 3;
            try {
              actualController.close();
              if (actualController !== controller) controller.close();
            } catch (e) { }
          }
        };

        // دانلود: سوکت مقصد → بدنه‌ی پاسخ (بدون هدر — قبلاً فرستاده شد)
        try {
          await connectStreams(socket, bridge, null, null, (b) => { downBytes += b; addBytes(b); });
        } catch (e) {
          dbg(env, ctx, 'downstream error: ' + (e && e.message || e));
        }
        try { await upPump; } catch (e) { }

        const durationSec = ((Date.now() - connectionStartTime) / 1000).toFixed(1);
        dbg(env, ctx, `END user=${username} route=${route} up=${upBytes} down=${downBytes} duration=${durationSec}s`);
      } finally {
        clearInterval(guard);
        try { reader.releaseLock(); } catch (e) { }
        if (typeof bridge !== 'undefined') bridge.close();
        if (socket) { try { socket.close(); } catch (e) { } }
        if (session) XHTTP_SESSIONS.delete(session.id);
        setOffline();
      }
    },
    cancel() {
      try { reader.cancel(); } catch (e) { }
      setOffline();
    }
  }), { status: 200, headers: responseHeaders });
}

// بررسی اینکه آیا کاربر به سقف مصرف/انقضا رسیده است
function isUserCapped(user) {
  if (!user || user.is_active === 0) return true;
  if (user.limit_gb && (user.used_gb || 0) >= user.limit_gb) return true;
  if (user.daily_limit_gb && (user.daily_used_gb || 0) >= user.daily_limit_gb) return true;
  if (user.expiry_days && user.created_at) {
    const created = new Date(user.created_at);
    const expiryDate = new Date(created.getTime() + (user.expiry_days * 24 * 60 * 60 * 1000));
    if (new Date() > expiryDate) return true;
  }
  return false;
}

// ==========================================================
// ۸. توابع کمکی موتور (UTILITIES & HELPERS)
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
  } catch (e) { }
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
      try { closeConnection?.(err); } catch (_) { }
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
      try { closeConnection?.(err); } catch (_) { }
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
      await new Promise(r => setTimeout(r, 10));
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

  // FORCE SEND VLESS RESPONSE HEADER
  await downstreamSender.flush();

  try {
    if (!useBYOB) {
      while (true) {
        await waitForBackpressure(webSocket);
        const { done, value } = await reader.read();
        if (done) break;
        if (!value || value.byteLength === 0) continue;
        hasData = true;
        if (typeof onBytes === 'function') onBytes(value.byteLength);
        if (webSocket.isXHTTP) {
          await downstreamSender.sendDirect(value);
        } else {
          await downstreamSender.send(value);
        }
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
        if (value.byteLength >= DOWNSTREAM_GRAIN_BYTES || webSocket.isXHTTP) {
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
    try { reader.cancel(); } catch (e) { }
    try { reader.releaseLock(); } catch (e) { }
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
            try { socket.close(); } catch (e) { }
          }
        }).catch(() => { });
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
  } catch (e) { }
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
<html>
<head>
<title>Welcome to nginx!</title>
<style>
    body {
        width: 35em;
        margin: 0 auto;
        font-family: Tahoma, Verdana, Arial, sans-serif;
    }
</style>
</head>
<body>
<h1>Welcome to nginx!</h1>
<p>If you see this page, the nginx web server is successfully installed and
working. Further configuration is required.</p>

<p>For online documentation and support please refer to
<a href="http://nginx.org/">nginx.org</a>.<br/>
Commercial support is available at
<a href="http://nginx.com/">nginx.com</a>.</p>

<p><em>Thank you for using nginx.</em></p>
</body>
</html>`,

  setup: `<!DOCTYPE html>
<html lang="fa" dir="rtl" class="dark">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>MLMVPN — تنظیم رمز</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <link href="https://cdn.jsdelivr.net/gh/rastikerdar/vazirmatn@v33.003/Vazirmatn-font-face.css" rel="stylesheet" type="text/css" />
    <script>
        tailwind.config = {
            darkMode: 'class',
            theme: {
                extend: {
                    fontFamily: { sans: ['Vazirmatn', 'sans-serif'] },
                    colors: { amoled: { bg: '#202124', card: '#292a2d', input: '#303134', border: '#3c4043' } }
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
    <title>MLMVPN — ورود</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <link href="https://cdn.jsdelivr.net/gh/rastikerdar/vazirmatn@v33.003/Vazirmatn-font-face.css" rel="stylesheet" type="text/css" />
    <script>
        tailwind.config = {
            darkMode: 'class',
            theme: {
                extend: {
                    fontFamily: { sans: ['Vazirmatn', 'sans-serif'] },
                    colors: { amoled: { bg: '#202124', card: '#292a2d', input: '#303134', border: '#3c4043' } }
                }
            }
        }
    </script>
</head>
<body class="bg-gray-50 text-gray-900 dark:bg-amoled-bg dark:text-zinc-100 min-h-screen flex items-center justify-center p-4">
    <div class="w-full max-w-md bg-white dark:bg-amoled-card border border-gray-200 dark:border-amoled-border rounded-2xl shadow-xl p-6">
        <h2 class="text-2xl font-black mb-1 text-center text-blue-600 dark:text-blue-400" dir="ltr">MLMVPN</h2>
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
<html lang="fa" dir="rtl" class="dark">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>MLMVPN - Admin Panel</title>
    <script>
        const originalWarn = console.warn;
        console.warn = (...args) => {
            if (typeof args[0] === 'string' && args[0].includes('cdn.tailwindcss.com')) return;
            originalWarn(...args);
        };
    <\/script>
    <script src="https://cdn.tailwindcss.com"><\/script>
    <script src="https://cdnjs.cloudflare.com/ajax/libs/qrcodejs/1.0.0/qrcode.min.js"><\/script>
    <link href="https://cdn.jsdelivr.net/gh/rastikerdar/vazirmatn@v33.003/Vazirmatn-font-face.css" rel="stylesheet" type="text/css" />
    <link href="https://fonts.googleapis.com/css2?family=JetBrains+Mono:wght@400;600&display=swap" rel="stylesheet" />
    <script>
        tailwind.config = {
            darkMode: 'class',
            theme: {
                extend: {
                    fontFamily: {
                        sans: ['Vazirmatn', 'sans-serif'],
                        mono: ['JetBrains Mono', 'monospace']
                    },
                    colors: {
                        panel: {
                            bg: '#171717',
                            card: '#202124',
                            hover: '#292a2d',
                            border: '#3c4043',
                            blue: '#8ab4f8',
                            green: '#81c995',
                            yellow: '#fde293',
                            purple: '#c58af9',
                            red: '#f28b82',
                            muted: '#9aa0a6',
                            text: '#e8eaed'
                        }
                    },
                    borderRadius: { '2xl': '1rem', '3xl': '1.5rem' },
                    boxShadow: {
                        'glow-blue': '0 0 20px rgba(138,180,248,0.15)',
                        'glow-green': '0 0 20px rgba(129,201,149,0.15)',
                        'glow-yellow': '0 0 20px rgba(253,226,147,0.15)',
                        'glow-purple': '0 0 20px rgba(197,138,249,0.15)',
                        'card': '0 2px 8px rgba(0,0,0,0.4)'
                    }
                }
            }
        }
    <\/script>
    <style>
        * { box-sizing: border-box; }
        body { font-family: 'Vazirmatn', sans-serif; background: #171717; color: #e8eaed; }
        ::-webkit-scrollbar { width: 6px; height: 6px; }
        ::-webkit-scrollbar-track { background: #202124; }
        ::-webkit-scrollbar-thumb { background: #3c4043; border-radius: 3px; }
        ::-webkit-scrollbar-thumb:hover { background: #5f6368; }
        .glass { background: rgba(32,33,36,0.85); backdrop-filter: blur(12px); -webkit-backdrop-filter: blur(12px); }
        .card-glow-blue:hover { box-shadow: 0 0 24px rgba(138,180,248,0.18), 0 2px 8px rgba(0,0,0,0.4); }
        .card-glow-green:hover { box-shadow: 0 0 24px rgba(129,201,149,0.18), 0 2px 8px rgba(0,0,0,0.4); }
        .card-glow-yellow:hover { box-shadow: 0 0 24px rgba(253,226,147,0.18), 0 2px 8px rgba(0,0,0,0.4); }
        .card-glow-purple:hover { box-shadow: 0 0 24px rgba(197,138,249,0.18), 0 2px 8px rgba(0,0,0,0.4); }
        .mono { font-family: 'JetBrains Mono', monospace; }
        select option { background: #202124; color: #e8eaed; }
        input:-webkit-autofill, input:-webkit-autofill:focus {
            -webkit-box-shadow: 0 0 0 1000px #202124 inset !important;
            -webkit-text-fill-color: #e8eaed !important;
        }
        @keyframes toast-in { from { opacity:0; transform: translateY(16px) scale(0.96); } to { opacity:1; transform: translateY(0) scale(1); } }
        @keyframes toast-out { from { opacity:1; } to { opacity:0; transform: translateY(-8px); } }
        .toast-enter { animation: toast-in 0.28s ease forwards; }
        .toast-exit { animation: toast-out 0.22s ease forwards; }
        @keyframes ping-slow { 0%,100%{opacity:1;transform:scale(1)} 50%{opacity:.4;transform:scale(1.4)} }
        .animate-ping-slow { animation: ping-slow 2s ease-in-out infinite; }
        .btn-action { display:inline-flex; align-items:center; justify-content:center; padding:6px; border-radius:8px; border:1px solid #3c4043; background:#202124; transition:all 0.18s ease; cursor:pointer; }
        .btn-action:hover { background:#292a2d; transform:translateY(-1px); }
        .sub-btn { display:inline-flex; align-items:center; justify-content:center; gap:4px; padding:5px 8px; border-radius:8px; border:1px solid; font-size:11px; font-weight:700; transition:all 0.18s ease; cursor:pointer; white-space:nowrap; }
        .modal-overlay { position:fixed; inset:0; z-index:50; display:flex; align-items:center; justify-content:center; padding:16px; background:rgba(0,0,0,0.75); backdrop-filter:blur(4px); opacity:0; pointer-events:none; transition:opacity 0.2s ease; }
        .modal-overlay.open { opacity:1; pointer-events:auto; }
        .modal-card { transition:opacity 0.2s ease, transform 0.2s ease; opacity:0; transform:scale(0.95); }
        .modal-overlay.open .modal-card { opacity:1; transform:scale(1); }
        .checkbox-port { display:none; }
        .port-label { display:flex; align-items:center; justify-content:center; padding:6px 10px; border:1px solid #3c4043; border-radius:10px; font-size:12px; font-weight:700; cursor:pointer; transition:all 0.15s; background:#171717; color:#9aa0a6; font-family:'JetBrains Mono',monospace; user-select:none; }
        .checkbox-port:checked + .port-label { background:rgba(138,180,248,0.12); border-color:#8ab4f8; color:#8ab4f8; }
        .checkbox-port.nontls:checked + .port-label { background:rgba(253,226,147,0.10); border-color:#fde293; color:#fde293; }
        tr.user-row:hover { background:#1e1f22; }
        .fade-in { animation: fadeIn 0.3s ease; }
        @keyframes fadeIn { from{opacity:0;transform:translateY(6px)} to{opacity:1;transform:none} }
        @keyframes spin { from{transform:rotate(0deg)} to{transform:rotate(360deg)} }
    </style>
</head>
<body class="min-h-screen" style="background:#171717;color:#e8eaed;">

    <!-- Toast Container -->
    <div id="toast-container" style="position:fixed;top:20px;left:50%;transform:translateX(-50%);z-index:9999;display:flex;flex-direction:column;gap:10px;align-items:center;pointer-events:none;width:max-content;max-width:90vw;"></div>

    <header class="glass sticky top-0 z-40" style="border-bottom:1px solid #3c4043;">
        <div class="max-w-6xl mx-auto px-4 py-3 flex justify-between items-center">
            <!-- Logo + Brand -->
            <div class="flex items-center gap-3" dir="ltr">
                <div style="width:38px;height:38px;background:linear-gradient(135deg,rgba(138,180,248,0.18),rgba(197,138,249,0.12));border:1px solid rgba(138,180,248,0.3);border-radius:12px;display:flex;align-items:center;justify-content:center;">
                    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#8ab4f8" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                        <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>
                    </svg>
                </div>
                <div>
                    <div style="font-size:16px;font-weight:800;color:#e8eaed;letter-spacing:-0.3px;" dir="ltr">MLMVPN</div>
                    <div style="font-size:10px;color:#9aa0a6;font-weight:500;">Admin Panel</div>
                </div>
                <span style="font-size:10px;padding:2px 8px;background:rgba(138,180,248,0.12);color:#8ab4f8;border:1px solid rgba(138,180,248,0.25);border-radius:20px;font-weight:700;" dir="ltr">v1.0</span>
            </div>
            <!-- Actions -->
            <div class="flex items-center gap-2">
                <button onclick="toggleSettingsModal(true)" title="تنظیمات" style="width:36px;height:36px;display:inline-flex;align-items:center;justify-content:center;border-radius:10px;border:1px solid #3c4043;background:#202124;color:#9aa0a6;cursor:pointer;transition:all 0.18s;" onmouseover="this.style.background='#292a2d';this.style.color='#8ab4f8';this.style.borderColor='rgba(138,180,248,0.4)';" onmouseout="this.style.background='#202124';this.style.color='#9aa0a6';this.style.borderColor='#3c4043';">
                    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z"></path><circle cx="12" cy="12" r="3"/></svg>
                </button>
                <button onclick="logoutAdmin()" title="خروج" style="width:36px;height:36px;display:inline-flex;align-items:center;justify-content:center;border-radius:10px;border:1px solid #3c4043;background:#202124;color:#9aa0a6;cursor:pointer;transition:all 0.18s;" onmouseover="this.style.background='rgba(242,139,130,0.1)';this.style.color='#f28b82';this.style.borderColor='rgba(242,139,130,0.35)';" onmouseout="this.style.background='#202124';this.style.color='#9aa0a6';this.style.borderColor='#3c4043';">
                    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17 16l4-4m0 0l-4-4m4 4H7m6 4v1a3 3 0 01-3 3H6a3 3 0 01-3-3V7a3 3 0 013-3h4a3 3 0 013 3v1"/></svg>
                </button>
            </div>
        </div>
    </header>

    <main class="max-w-6xl mx-auto px-4 py-8">

        <!-- Stats Cards -->
        <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4 mb-8">

            <!-- Total Users -->
            <div class="card-glow-blue relative overflow-hidden rounded-2xl p-5 flex items-center justify-between cursor-default" style="background:#202124;border:1px solid #3c4043;box-shadow:0 2px 8px rgba(0,0,0,0.4);transition:border-color 0.2s,box-shadow 0.2s;" onmouseover="this.style.borderColor='rgba(138,180,248,0.4)';" onmouseout="this.style.borderColor='#3c4043';">
                <div style="position:absolute;left:-20px;bottom:-20px;width:90px;height:90px;background:radial-gradient(circle,rgba(138,180,248,0.12),transparent 70%);border-radius:50%;"></div>
                <div style="position:relative;z-index:1;">
                    <div style="font-size:12px;font-weight:600;color:#9aa0a6;margin-bottom:6px;">تعداد کل کاربران</div>
                    <div id="stat-total-users" style="font-size:32px;font-weight:900;color:#e8eaed;line-height:1;">0</div>
                    <div style="font-size:11px;color:#8ab4f8;margin-top:6px;display:flex;align-items:center;gap:5px;">
                        <span style="width:7px;height:7px;background:#8ab4f8;border-radius:50%;display:inline-block;"></span>
                        کل کاربران تعریف شده
                    </div>
                </div>
                <div style="padding:12px;background:rgba(138,180,248,0.1);border-radius:14px;color:#8ab4f8;position:relative;z-index:1;flex-shrink:0;">
                    <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M17 20h5v-2a3 3 0 00-5.356-1.857M17 20H7m10 0v-2c0-.656-.126-1.283-.356-1.857M7 20H2v-2a3 3 0 015.356-1.857M7 20v-2c0-.656.126-1.283.356-1.857m0 0a5.002 5.002 0 019.288 0M15 7a3 3 0 11-6 0 3 3 0 016 0zm6 3a2 2 0 11-4 0 2 2 0 014 0zM7 10a2 2 0 11-4 0 2 2 0 014 0z"/></svg>
                </div>
            </div>

            <!-- Online Users -->
            <div class="card-glow-green relative overflow-hidden rounded-2xl p-5 flex items-center justify-between cursor-default" style="background:#202124;border:1px solid #3c4043;box-shadow:0 2px 8px rgba(0,0,0,0.4);transition:border-color 0.2s,box-shadow 0.2s;" onmouseover="this.style.borderColor='rgba(129,201,149,0.4)';" onmouseout="this.style.borderColor='#3c4043';">
                <div style="position:absolute;left:-20px;bottom:-20px;width:90px;height:90px;background:radial-gradient(circle,rgba(129,201,149,0.12),transparent 70%);border-radius:50%;"></div>
                <div style="position:relative;z-index:1;">
                    <div style="font-size:12px;font-weight:600;color:#9aa0a6;margin-bottom:6px;">کاربران آنلاین</div>
                    <div id="stat-active-users" style="font-size:32px;font-weight:900;color:#81c995;line-height:1;">0</div>
                    <div style="font-size:11px;color:#81c995;margin-top:6px;display:flex;align-items:center;gap:5px;">
                        <span class="animate-ping-slow" style="width:7px;height:7px;background:#81c995;border-radius:50%;display:inline-block;"></span>
                        متصل در این لحظه
                    </div>
                </div>
                <div style="padding:12px;background:rgba(129,201,149,0.1);border-radius:14px;color:#81c995;position:relative;z-index:1;flex-shrink:0;">
                    <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M13 10V3L4 14h7v7l9-11h-7z"/></svg>
                </div>
            </div>

            <!-- Total Usage -->
            <div class="card-glow-purple relative overflow-hidden rounded-2xl p-5 flex items-center justify-between cursor-default" style="background:#202124;border:1px solid #3c4043;box-shadow:0 2px 8px rgba(0,0,0,0.4);transition:border-color 0.2s,box-shadow 0.2s;" onmouseover="this.style.borderColor='rgba(197,138,249,0.4)';" onmouseout="this.style.borderColor='#3c4043';">
                <div style="position:absolute;left:-20px;bottom:-20px;width:90px;height:90px;background:radial-gradient(circle,rgba(197,138,249,0.12),transparent 70%);border-radius:50%;"></div>
                <div style="position:relative;z-index:1;">
                    <div style="font-size:12px;font-weight:600;color:#9aa0a6;margin-bottom:6px;">کل حجم مصرفی</div>
                    <div id="stat-total-usage" style="font-size:28px;font-weight:900;color:#c58af9;line-height:1;">0 GB</div>
                    <div style="font-size:11px;color:#c58af9;margin-top:6px;display:flex;align-items:center;gap:5px;">
                        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M9 19l3 3m0 0l3-3m-3 3V10"/></svg>
                        مصرف کل کاربران
                    </div>
                </div>
                <div style="padding:12px;background:rgba(197,138,249,0.1);border-radius:14px;color:#c58af9;position:relative;z-index:1;flex-shrink:0;">
                    <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M19 11H5m14 0a2 2 0 012 2v6a2 2 0 01-2 2H5a2 2 0 01-2-2v-6a2 2 0 012-2m14 0V9a2 2 0 00-2-2M5 11V9a2 2 0 012-2m0 0V5a2 2 0 012-2h6a2 2 0 012 2v2M7 7h10"/></svg>
                </div>
            </div>

            <!-- Top User -->
            <div class="card-glow-yellow relative overflow-hidden rounded-2xl p-5 flex items-center justify-between cursor-default" style="background:#202124;border:1px solid #3c4043;box-shadow:0 2px 8px rgba(0,0,0,0.4);transition:border-color 0.2s,box-shadow 0.2s;" onmouseover="this.style.borderColor='rgba(253,226,147,0.4)';" onmouseout="this.style.borderColor='#3c4043';">
                <div style="position:absolute;left:-20px;bottom:-20px;width:90px;height:90px;background:radial-gradient(circle,rgba(253,226,147,0.10),transparent 70%);border-radius:50%;"></div>
                <div style="position:relative;z-index:1;min-width:0;flex:1;">
                    <div style="font-size:12px;font-weight:600;color:#9aa0a6;margin-bottom:6px;">پر مصرف‌ترین کاربر</div>
                    <div id="stat-top-user" style="font-size:22px;font-weight:900;color:#fde293;line-height:1;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:130px;">-</div>
                    <div id="stat-top-user-usage" style="font-size:11px;color:#fde293;margin-top:6px;">۰ GB مصرف شده</div>
                </div>
                <div style="padding:12px;background:rgba(253,226,147,0.08);border-radius:14px;color:#fde293;position:relative;z-index:1;flex-shrink:0;">
                    <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M16 7a4 4 0 11-8 0 4 4 0 018 0zM12 14a7 7 0 00-7 7h14a7 7 0 00-7-7z"/></svg>
                </div>
            </div>
        </div>

        <!-- Loading State -->
        <div id="loading-state" class="text-center py-16">
            <div style="display:inline-flex;align-items:center;gap:10px;color:#9aa0a6;font-size:14px;">
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#8ab4f8" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="animation:spin 1s linear infinite;"><circle cx="12" cy="12" r="10" stroke-opacity=".25"/><path d="M12 2a10 10 0 0110 10" stroke="#8ab4f8"/></svg>
                در حال بارگذاری کاربران...
            </div>
        </div>

        <!-- Search & Filter Bar -->
        <div class="mb-5 rounded-2xl p-4" style="background:#202124;border:1px solid #3c4043;">
            <div class="flex flex-col md:flex-row gap-3 items-stretch md:items-center justify-between">
                <!-- Search -->
                <div style="position:relative;flex:1;max-width:360px;">
                    <div style="position:absolute;inset-y:0;right:0;display:flex;align-items:center;padding-right:12px;pointer-events:none;color:#9aa0a6;">
                        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><path d="M21 21l-4.35-4.35"/></svg>
                    </div>
                    <input type="text" id="search-input" oninput="filterAndRenderUsers()" placeholder="جستجوی نام کاربری یا UUID..." style="width:100%;padding:9px 40px 9px 12px;background:#171717;border:1px solid #3c4043;border-radius:12px;font-size:13px;color:#e8eaed;outline:none;transition:border-color 0.18s;" onfocus="this.style.borderColor='rgba(138,180,248,0.5)';" onblur="this.style.borderColor='#3c4043';">
                </div>
                <!-- Filters -->
                <div style="display:flex;flex-wrap:wrap;gap:10px;align-items:center;">
                    <select id="filter-status" onchange="filterAndRenderUsers()" style="padding:9px 12px;background:#171717;border:1px solid #3c4043;border-radius:12px;font-size:13px;color:#e8eaed;outline:none;cursor:pointer;font-family:Vazirmatn,sans-serif;" onfocus="this.style.borderColor='rgba(138,180,248,0.5)';" onblur="this.style.borderColor='#3c4043';">
                        <option value="all">همه وضعیت‌ها</option>
                        <option value="active">فعال</option>
                        <option value="inactive">غیرفعال</option>
                        <option value="online">آنلاین</option>
                        <option value="offline">آفلاین</option>
                        <option value="expired">منقضی شده</option>
                    </select>
                    <select id="sort-users" onchange="filterAndRenderUsers()" style="padding:9px 12px;background:#171717;border:1px solid #3c4043;border-radius:12px;font-size:13px;color:#e8eaed;outline:none;cursor:pointer;font-family:Vazirmatn,sans-serif;" onfocus="this.style.borderColor='rgba(138,180,248,0.5)';" onblur="this.style.borderColor='#3c4043';">
                        <option value="newest">جدیدترین</option>
                        <option value="name">نام کاربری (الفبا)</option>
                        <option value="usage-desc">بیشترین مصرف</option>
                        <option value="usage-asc">کمترین مصرف</option>
                        <option value="expiry-asc">کمترین زمان باقی‌مانده</option>
                    </select>
                </div>
            </div>
        </div>

        <!-- Users List Header -->
        <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:14px;">
            <h2 style="font-size:15px;font-weight:800;color:#e8eaed;">لیست کاربران</h2>
            <button onclick="openCreateModal()" title="افزودن کاربر جدید" style="width:38px;height:38px;display:inline-flex;align-items:center;justify-content:center;border-radius:50%;background:linear-gradient(135deg,#4f8ef7,#7b5cf9);color:#fff;border:none;cursor:pointer;box-shadow:0 4px 14px rgba(138,180,248,0.3);transition:transform 0.18s,box-shadow 0.18s;" onmouseover="this.style.transform='scale(1.1)';this.style.boxShadow='0 6px 20px rgba(138,180,248,0.45)';" onmouseout="this.style.transform='scale(1)';this.style.boxShadow='0 4px 14px rgba(138,180,248,0.3)';">
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round"><path d="M12 4v16m8-8H4"/></svg>
            </button>
        </div>

        <!-- Users Table -->
        <div id="users-table-container" class="hidden" style="border-radius:16px;overflow:hidden;border:1px solid #3c4043;background:#202124;">
            <div style="overflow-x:auto;">
                <table style="width:100%;border-collapse:collapse;text-align:right;">
                    <thead>
                        <tr style="background:#171717;border-bottom:1px solid #3c4043;">
                            <th style="padding:12px 16px;font-size:11px;font-weight:700;color:#9aa0a6;white-space:nowrap;">نام کاربر و عملیات</th>
                            <th style="padding:12px 16px;font-size:11px;font-weight:700;color:#9aa0a6;white-space:nowrap;">لینک ساب</th>
                            <th style="padding:12px 16px;font-size:11px;font-weight:700;color:#9aa0a6;white-space:nowrap;">پروتکل</th>
                            <th style="padding:12px 16px;font-size:11px;font-weight:700;color:#9aa0a6;white-space:nowrap;">پورت</th>
                            <th style="padding:12px 16px;font-size:11px;font-weight:700;color:#9aa0a6;white-space:nowrap;">وضعیت حجم</th>
                            <th style="padding:12px 16px;font-size:11px;font-weight:700;color:#9aa0a6;white-space:nowrap;">وضعیت اعتبار</th>
                            <th style="padding:12px 16px;font-size:11px;font-weight:700;color:#9aa0a6;white-space:nowrap;">تاریخ ساخت</th>
                        </tr>
                    </thead>
                    <tbody id="users-tbody"></tbody>
                </table>
            </div>
        </div>

        <!-- Empty State -->
        <div id="empty-state" class="hidden" style="padding:48px 24px;border:2px dashed #3c4043;border-radius:16px;text-align:center;">
            <div style="color:#9aa0a6;font-size:13px;line-height:1.7;">
                <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="#3c4043" stroke-width="1.5" style="margin:0 auto 12px;" stroke-linecap="round"><path d="M17 21v-2a4 4 0 00-4-4H5a4 4 0 00-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 00-3-3.87M16 3.13a4 4 0 010 7.75"/></svg>
                <p id="empty-state-msg">کاربری وجود ندارد. برای ساخت اولین کاربر روی دکمه «+» کلیک کنید.</p>
            </div>
        </div>
    </main>

    <!-- Footer -->
    <footer style="margin-top:60px;border-top:1px solid #3c4043;background:#202124;padding:24px 16px;">
        <div class="max-w-6xl mx-auto" style="display:flex;flex-wrap:wrap;align-items:center;justify-content:space-between;gap:16px;">
            <div style="display:flex;align-items:center;gap:10px;" dir="ltr">
                <div style="width:30px;height:30px;background:rgba(138,180,248,0.12);border:1px solid rgba(138,180,248,0.2);border-radius:9px;display:flex;align-items:center;justify-content:center;">
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#8ab4f8" stroke-width="2"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg>
                </div>
                <div>
                    <div style="font-size:13px;font-weight:800;color:#e8eaed;" dir="ltr">MLMVPN</div>
                    <div style="font-size:10px;color:#9aa0a6;">Multi Layer Multiplexer</div>
                </div>
            </div>
            <div style="font-size:11px;color:#5f6368;text-align:center;flex-grow:1;">
                ساخته شده با محبت برای ایرانیان &nbsp;|&nbsp; MLMVPN &copy; 2026
            </div>
            <div style="display:flex;align-items:center;gap:14px;">
                <!-- Telegram -->
                <a href="https://t.me/mlmvpn" target="_blank" rel="noopener" title="Telegram" style="display:inline-flex;align-items:center;justify-content:center;width:34px;height:34px;border-radius:10px;border:1px solid #3c4043;background:#202124;color:#9aa0a6;transition:all 0.18s;text-decoration:none;" onmouseover="this.style.background='rgba(138,180,248,0.1)';this.style.color='#8ab4f8';this.style.borderColor='rgba(138,180,248,0.3)';" onmouseout="this.style.background='#202124';this.style.color='#9aa0a6';this.style.borderColor='#3c4043';">
                    <svg width="17" height="17" viewBox="0 0 24 24" fill="currentColor"><path d="M12 0C5.373 0 0 5.373 0 12s5.373 12 12 12 12-5.373 12-12S18.627 0 12 0zm5.894 8.221l-1.97 9.28c-.145.658-.537.818-1.084.508l-3-2.21-1.447 1.394c-.16.16-.295.295-.605.295l.213-3.053 5.56-5.023c.242-.213-.054-.333-.373-.12l-6.869 4.326-2.96-.924c-.643-.204-.657-.643.136-.953l11.57-4.461c.537-.194 1.006.131.829.941z"/></svg>
                </a>
                <!-- YouTube -->
                <a href="https://www.youtube.com/@marketmlm" target="_blank" rel="noopener" title="YouTube" style="display:inline-flex;align-items:center;justify-content:center;width:34px;height:34px;border-radius:10px;border:1px solid #3c4043;background:#202124;color:#9aa0a6;transition:all 0.18s;text-decoration:none;" onmouseover="this.style.background='rgba(242,139,130,0.1)';this.style.color='#f28b82';this.style.borderColor='rgba(242,139,130,0.3)';" onmouseout="this.style.background='#202124';this.style.color='#9aa0a6';this.style.borderColor='#3c4043';">
                    <svg width="17" height="17" viewBox="0 0 24 24" fill="currentColor"><path d="M23.498 6.186a3.016 3.016 0 00-2.122-2.136C19.505 3.545 12 3.545 12 3.545s-7.505 0-9.377.505A3.017 3.017 0 00.502 6.186C0 8.07 0 12 0 12s0 3.93.502 5.814a3.016 3.016 0 002.122 2.136c1.871.505 9.376.505 9.376.505s7.505 0 9.377-.505a3.015 3.015 0 002.122-2.136C24 15.93 24 12 24 12s0-3.93-.502-5.814zM9.545 15.568V8.432L15.818 12l-6.273 3.568z"/></svg>
                </a>
                <!-- GitHub -->
                <a href="https://github.com/mlmvpn" target="_blank" rel="noopener" title="GitHub" style="display:inline-flex;align-items:center;justify-content:center;width:34px;height:34px;border-radius:10px;border:1px solid #3c4043;background:#202124;color:#9aa0a6;transition:all 0.18s;text-decoration:none;" onmouseover="this.style.background='rgba(197,138,249,0.1)';this.style.color='#c58af9';this.style.borderColor='rgba(197,138,249,0.3)';" onmouseout="this.style.background='#202124';this.style.color='#9aa0a6';this.style.borderColor='#3c4043';">
                    <svg width="17" height="17" viewBox="0 0 24 24" fill="currentColor"><path d="M12 0C5.374 0 0 5.373 0 12c0 5.302 3.438 9.8 8.207 11.387.599.111.793-.261.793-.577v-2.234c-3.338.726-4.033-1.416-4.033-1.416-.546-1.387-1.333-1.756-1.333-1.756-1.089-.745.083-.729.083-.729 1.205.084 1.839 1.237 1.839 1.237 1.07 1.834 2.807 1.304 3.492.997.107-.775.418-1.305.762-1.604-2.665-.305-5.467-1.334-5.467-5.931 0-1.311.469-2.381 1.236-3.221-.124-.303-.535-1.524.117-3.176 0 0 1.008-.322 3.301 1.23A11.509 11.509 0 0112 5.803c1.02.005 2.047.138 3.006.404 2.291-1.552 3.297-1.23 3.297-1.23.653 1.653.242 2.874.118 3.176.77.84 1.235 1.911 1.235 3.221 0 4.609-2.807 5.624-5.479 5.921.43.372.823 1.102.823 2.222v3.293c0 .319.192.694.801.576C20.566 21.797 24 17.3 24 12c0-6.627-5.373-12-12-12z"/></svg>
                </a>
            </div>
        </div>
    </footer>

    <!-- User Create/Edit Modal -->
    <div id="user-modal" class="modal-overlay" onclick="if(event.target===this)toggleModal(false);">
        <div id="user-modal-card" class="modal-card w-full" style="max-width:560px;background:#202124;border:1px solid #3c4043;border-radius:20px;overflow:hidden;max-height:90vh;display:flex;flex-direction:column;box-shadow:0 20px 60px rgba(0,0,0,0.6);">
            <div style="padding:18px 22px;border-bottom:1px solid #3c4043;display:flex;justify-content:space-between;align-items:center;background:#171717;flex-shrink:0;">
                <div style="display:flex;align-items:center;gap:10px;">
                    <div style="width:8px;height:8px;border-radius:50%;background:#8ab4f8;box-shadow:0 0 8px rgba(138,180,248,0.5);"></div>
                    <h3 id="modal-title" style="font-size:15px;font-weight:800;color:#e8eaed;">ایجاد کاربر جدید</h3>
                </div>
                <button onclick="toggleModal(false)" style="width:30px;height:30px;display:inline-flex;align-items:center;justify-content:center;border-radius:8px;border:none;background:transparent;color:#9aa0a6;cursor:pointer;transition:all 0.15s;" onmouseover="this.style.background='#292a2d';this.style.color='#e8eaed';" onmouseout="this.style.background='transparent';this.style.color='#9aa0a6';">
                    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round"><path d="M18 6L6 18M6 6l12 12"/></svg>
                </button>
            </div>

            <form id="create-user-form" onsubmit="handleFormSubmit(event)" style="padding:20px 22px;overflow-y:auto;flex:1;-webkit-overflow-scrolling:touch;">
                <div style="display:flex;flex-direction:column;gap:16px;">
                    <!-- Username -->
                    <div>
                        <label style="display:block;font-size:10px;font-weight:700;color:#9aa0a6;margin-bottom:7px;text-transform:uppercase;letter-spacing:.8px;">نام کاربری</label>
                        <div style="position:relative;">
                            <span style="position:absolute;inset-y:0;right:0;display:flex;align-items:center;padding-right:11px;color:#9aa0a6;pointer-events:none;">
                                <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M20 21v-2a4 4 0 00-4-4H8a4 4 0 00-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>
                            </span>
                            <input type="text" id="input-name" placeholder="ali" required style="width:100%;padding:10px 36px 10px 12px;background:#171717;border:1px solid #3c4043;border-radius:12px;font-size:13px;font-weight:600;color:#e8eaed;outline:none;transition:border-color 0.18s;font-family:Vazirmatn,sans-serif;" onfocus="this.style.borderColor='rgba(138,180,248,0.5)';" onblur="this.style.borderColor='#3c4043';">
                        </div>
                    </div>
                    <!-- Limit + Expiry -->
                    <div style="display:grid;grid-template-columns:1fr 1fr;gap:12px;">
                        <div>
                            <label style="display:block;font-size:10px;font-weight:700;color:#9aa0a6;margin-bottom:7px;text-transform:uppercase;letter-spacing:.8px;">حجم مجاز (GB)</label>
                            <input type="number" id="input-limit" min="0" step="any" placeholder="نامحدود" style="width:100%;padding:10px 12px;background:#171717;border:1px solid #3c4043;border-radius:12px;font-size:13px;font-weight:600;color:#e8eaed;outline:none;transition:border-color 0.18s;font-family:Vazirmatn,sans-serif;" onfocus="this.style.borderColor='rgba(138,180,248,0.5)';" onblur="this.style.borderColor='#3c4043';">
                        </div>
                        <div>
                            <label style="display:block;font-size:10px;font-weight:700;color:#9aa0a6;margin-bottom:7px;text-transform:uppercase;letter-spacing:.8px;">مدت اعتبار (روز)</label>
                            <input type="number" id="input-expiry" min="0" placeholder="نامحدود" style="width:100%;padding:10px 12px;background:#171717;border:1px solid #3c4043;border-radius:12px;font-size:13px;font-weight:600;color:#e8eaed;outline:none;transition:border-color 0.18s;font-family:Vazirmatn,sans-serif;" onfocus="this.style.borderColor='rgba(138,180,248,0.5)';" onblur="this.style.borderColor='#3c4043';">
                        </div>
                    </div>
                    <!-- Daily Limit -->
                    <div>
                        <label style="display:block;font-size:10px;font-weight:700;color:#9aa0a6;margin-bottom:7px;text-transform:uppercase;letter-spacing:.8px;">سقف مصرف روزانه (GB)</label>
                        <input type="number" id="input-daily" min="0" step="any" placeholder="نامحدود" style="width:100%;padding:10px 12px;background:#171717;border:1px solid #3c4043;border-radius:12px;font-size:13px;font-weight:600;color:#e8eaed;outline:none;transition:border-color 0.18s;font-family:Vazirmatn,sans-serif;" onfocus="this.style.borderColor='rgba(138,180,248,0.5)';" onblur="this.style.borderColor='#3c4043';">
                    </div>
                    <!-- Ports -->
                    <div style="border-top:1px solid #3c4043;padding-top:16px;">
                        <label style="display:block;font-size:10px;font-weight:700;color:#9aa0a6;margin-bottom:12px;text-transform:uppercase;letter-spacing:.8px;">پورت‌های اتصال (انتخاب چندگانه)</label>
                        <div style="padding:14px;background:#171717;border:1px solid #3c4043;border-radius:14px;margin-bottom:10px;">
                            <div style="display:flex;align-items:center;gap:7px;margin-bottom:10px;">
                                <span style="width:7px;height:7px;background:#8ab4f8;border-radius:50%;display:inline-block;box-shadow:0 0 6px rgba(138,180,248,0.5);"></span>
                                <span style="font-size:11px;font-weight:700;color:#8ab4f8;">پورت‌های امن (TLS)</span>
                            </div>
                            <div id="tls-ports-list" style="display:flex;flex-wrap:wrap;gap:8px;"></div>
                        </div>
                        <div style="padding:14px;background:#171717;border:1px solid #3c4043;border-radius:14px;">
                            <div style="display:flex;align-items:center;gap:7px;margin-bottom:10px;">
                                <span style="width:7px;height:7px;background:#fde293;border-radius:50%;display:inline-block;box-shadow:0 0 6px rgba(253,226,147,0.4);"></span>
                                <span style="font-size:11px;font-weight:700;color:#fde293;">پورت‌های معمولی (Non-TLS)</span>
                            </div>
                            <div id="nontls-ports-list" style="display:flex;flex-wrap:wrap;gap:8px;"></div>
                        </div>
                    </div>
                    <!-- Clean IPs -->
                    <div style="border-top:1px solid #3c4043;padding-top:16px;">
                        <label style="display:block;font-size:10px;font-weight:700;color:#9aa0a6;margin-bottom:7px;text-transform:uppercase;letter-spacing:.8px;">آی‌پی تمیز کلودفلر (هر خط یک آی‌پی)</label>
                        <textarea id="input-ips" rows="2" placeholder="104.16.0.1" style="width:100%;padding:10px 12px;background:#171717;border:1px solid #3c4043;border-radius:12px;font-size:12px;font-weight:500;color:#e8eaed;outline:none;resize:none;font-family:'JetBrains Mono',monospace;transition:border-color 0.18s;" onfocus="this.style.borderColor='rgba(138,180,248,0.5)';" onblur="this.style.borderColor='#3c4043';"></textarea>
                    </div>
                    <!-- Proxy Select -->
                    <div>
                        <label style="display:block;font-size:10px;font-weight:700;color:#9aa0a6;margin-bottom:7px;text-transform:uppercase;letter-spacing:.8px;">پروکسی اختصاصی</label>
                        <select id="input-proxy-select" onchange="if(this.value==='custom'){document.getElementById('input-proxy').style.display='block';}else{document.getElementById('input-proxy').style.display='none';}" style="width:100%;padding:10px 12px;margin-bottom:8px;background:#171717;border:1px solid #3c4043;border-radius:12px;font-size:12px;font-weight:600;color:#e8eaed;outline:none;cursor:pointer;font-family:Vazirmatn,sans-serif;appearance:none;" onfocus="this.style.borderColor='rgba(138,180,248,0.5)';" onblur="this.style.borderColor='#3c4043';">
                            <option value="">بدون پروکسی (مستقیم)</option>
                            <option value="proxyip.cmliussss.net">آمریکا (Cmliussss)</option>
                            <option value="sg.proxyip.cmliussss.net">سنگاپور (Cmliussss)</option>
                            <option value="hk.proxyip.cmliussss.net">هنگ‌کنگ (Cmliussss)</option>
                            <option value="jp.proxyip.cmliussss.net">ژاپن (Cmliussss)</option>
                            <option value="uk.proxyip.cmliussss.net">انگلیس (Cmliussss)</option>
                            <option value="proxyip.aliilapro.com">آمریکا (AliilaPro)</option>
                            <option value="proxyip.futa.gg">متغیر (Futa.gg)</option>
                            <option value="custom">آی‌پی سرور شخصی / سفارشی</option>
                        </select>
                        <input type="text" id="input-proxy" placeholder="مثال: 123.45.67.89 یا دامنه پروکسی شخصی" style="display:none;width:100%;padding:10px 12px;background:#171717;border:1px solid #3c4043;border-radius:12px;font-size:13px;font-weight:600;color:#e8eaed;outline:none;transition:border-color 0.18s;font-family:Vazirmatn,sans-serif;" onfocus="this.style.borderColor='rgba(138,180,248,0.5)';" onblur="this.style.borderColor='#3c4043';">
                    </div>
                    <!-- Fingerprint -->
                    <div>
                        <label style="display:block;font-size:10px;font-weight:700;color:#9aa0a6;margin-bottom:7px;text-transform:uppercase;letter-spacing:.8px;">Fingerprint مرورگر</label>
                        <select id="fingerprint-select" style="width:100%;padding:10px 12px;background:#171717;border:1px solid #3c4043;border-radius:12px;font-size:12px;font-weight:600;color:#e8eaed;outline:none;cursor:pointer;font-family:Vazirmatn,sans-serif;appearance:none;" onfocus="this.style.borderColor='rgba(138,180,248,0.5)';" onblur="this.style.borderColor='#3c4043';">
                            <option value="chrome" selected>Chrome (پیش‌فرض)</option>
                            <option value="firefox">Firefox</option>
                            <option value="safari">Safari</option>
                            <option value="ios">iOS Device</option>
                            <option value="android">Android Device</option>
                            <option value="edge">Microsoft Edge</option>
                            <option value="360">360 Browser</option>
                            <option value="qq">QQ Browser</option>
                            <option value="random">Random (اتفاقی)</option>
                            <option value="randomized">Randomized (پویا)</option>
                        </select>
                    </div>
                    <!-- Buttons -->
                    <div style="display:flex;gap:10px;padding-top:8px;">
                        <button type="button" onclick="toggleModal(false)" style="flex:1;padding:11px;background:#292a2d;border:1px solid #3c4043;border-radius:12px;font-size:13px;font-weight:700;color:#9aa0a6;cursor:pointer;transition:all 0.18s;font-family:Vazirmatn,sans-serif;" onmouseover="this.style.background='#3c4043';this.style.color='#e8eaed';" onmouseout="this.style.background='#292a2d';this.style.color='#9aa0a6';">انصراف</button>
                        <button type="submit" id="submit-btn" style="flex:1;padding:11px;background:linear-gradient(135deg,#4f8ef7,#7b5cf9);border:none;border-radius:12px;font-size:13px;font-weight:700;color:#fff;cursor:pointer;font-family:Vazirmatn,sans-serif;box-shadow:0 4px 14px rgba(138,180,248,0.25);">ایجاد کاربر</button>
                    </div>
                </div>
            </form>
        </div>
    </div>

    <!-- QR Modal -->
    <div id="qr-modal" class="modal-overlay" onclick="if(event.target===this)toggleQRModal(false);">
        <div class="modal-card" style="width:100%;max-width:340px;background:#202124;border:1px solid #3c4043;border-radius:20px;overflow:hidden;padding:24px;text-align:center;box-shadow:0 20px 60px rgba(0,0,0,0.6);">
            <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:16px;">
                <h3 id="qr-modal-title" style="font-size:15px;font-weight:800;color:#e8eaed;">اسکن کد QR</h3>
                <button onclick="toggleQRModal(false)" style="width:28px;height:28px;display:inline-flex;align-items:center;justify-content:center;border-radius:8px;border:none;background:#292a2d;color:#9aa0a6;cursor:pointer;" onmouseover="this.style.color='#e8eaed';" onmouseout="this.style.color='#9aa0a6';">
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round"><path d="M18 6L6 18M6 6l12 12"/></svg>
                </button>
            </div>
            <div style="background:#fff;padding:12px;border-radius:14px;display:inline-block;margin-bottom:16px;">
                <div id="qrcode-box" style="width:192px;height:192px;display:flex;align-items:center;justify-content:center;margin:0 auto;"></div>
            </div>
            <button onclick="toggleQRModal(false)" style="width:100%;padding:10px;background:#292a2d;border:1px solid #3c4043;border-radius:12px;font-size:13px;font-weight:700;color:#9aa0a6;cursor:pointer;font-family:Vazirmatn,sans-serif;" onmouseover="this.style.color='#e8eaed';" onmouseout="this.style.color='#9aa0a6';">بستن</button>
        </div>
    </div>

    <!-- Settings Modal -->
    <div id="settings-modal" class="modal-overlay" onclick="if(event.target===this)toggleSettingsModal(false);">
        <div class="modal-card" style="width:100%;max-width:440px;background:#202124;border:1px solid #3c4043;border-radius:20px;overflow:hidden;box-shadow:0 20px 60px rgba(0,0,0,0.6);">
            <!-- Header -->
            <div style="padding:18px 22px;border-bottom:1px solid #3c4043;display:flex;justify-content:space-between;align-items:center;background:#171717;">
                <div style="display:flex;align-items:center;gap:10px;">
                    <div style="width:8px;height:8px;border-radius:50%;background:#c58af9;box-shadow:0 0 8px rgba(197,138,249,0.5);"></div>
                    <h3 style="font-size:15px;font-weight:800;color:#e8eaed;">تنظیمات پنل</h3>
                </div>
                <button onclick="toggleSettingsModal(false)" style="width:30px;height:30px;display:inline-flex;align-items:center;justify-content:center;border-radius:8px;border:none;background:transparent;color:#9aa0a6;cursor:pointer;" onmouseover="this.style.background='#292a2d';this.style.color='#e8eaed';" onmouseout="this.style.background='transparent';this.style.color='#9aa0a6';">
                    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round"><path d="M18 6L6 18M6 6l12 12"/></svg>
                </button>
            </div>
            <!-- Body -->
            <div style="padding:20px 22px;display:flex;flex-direction:column;gap:16px;">
                <!-- Location -->
                <div>
                    <label style="display:block;font-size:11px;font-weight:700;color:#9aa0a6;margin-bottom:8px;">موقعیت جغرافیایی پروکسی (Cloudflare)</label>
                    <select id="location-select" style="width:100%;padding:10px 12px;background:#171717;border:1px solid #3c4043;border-radius:12px;font-size:13px;color:#e8eaed;outline:none;cursor:pointer;font-family:Vazirmatn,sans-serif;appearance:none;" onfocus="this.style.borderColor='rgba(197,138,249,0.5)';" onblur="this.style.borderColor='#3c4043';">
                        <option value="">در حال بارگذاری...</option>
                    </select>
                </div>
                <!-- Fragment -->
                <div style="border-top:1px solid #3c4043;padding-top:16px;display:grid;grid-template-columns:1fr 1fr;gap:12px;">
                    <div>
                        <label style="display:block;font-size:11px;font-weight:700;color:#9aa0a6;margin-bottom:8px;" dir="ltr">Fragment Length</label>
                        <input type="text" id="frag-length" placeholder="20-30" dir="ltr" style="width:100%;padding:10px 12px;background:#171717;border:1px solid #3c4043;border-radius:12px;font-size:13px;color:#e8eaed;outline:none;text-align:center;font-family:'JetBrains Mono',monospace;" onfocus="this.style.borderColor='rgba(197,138,249,0.5)';" onblur="this.style.borderColor='#3c4043';">
                    </div>
                    <div>
                        <label style="display:block;font-size:11px;font-weight:700;color:#9aa0a6;margin-bottom:8px;" dir="ltr">Fragment Interval</label>
                        <input type="text" id="frag-interval" placeholder="1-2" dir="ltr" style="width:100%;padding:10px 12px;background:#171717;border:1px solid #3c4043;border-radius:12px;font-size:13px;color:#e8eaed;outline:none;text-align:center;font-family:'JetBrains Mono',monospace;" onfocus="this.style.borderColor='rgba(197,138,249,0.5)';" onblur="this.style.borderColor='#3c4043';">
                    </div>
                </div>
                <!-- Change Password -->
                <div style="border-top:1px solid #3c4043;padding-top:16px;">
                    <div style="display:flex;align-items:center;gap:8px;margin-bottom:12px;">
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#f28b82" stroke-width="2"><rect x="3" y="11" width="18" height="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0110 0v4"/></svg>
                        <span style="font-size:12px;font-weight:700;color:#e8eaed;">تغییر رمز عبور مدیریت</span>
                    </div>
                    <div style="display:flex;flex-direction:column;gap:10px;">
                        <div>
                            <label style="display:block;font-size:10px;color:#9aa0a6;font-weight:600;margin-bottom:6px;">رمز عبور فعلی</label>
                            <input type="password" id="change-pwd-current" style="width:100%;padding:9px 12px;background:#171717;border:1px solid #3c4043;border-radius:12px;font-size:13px;color:#e8eaed;outline:none;text-align:center;font-family:'JetBrains Mono',monospace;" onfocus="this.style.borderColor='rgba(242,139,130,0.5)';" onblur="this.style.borderColor='#3c4043';">
                        </div>
                        <div>
                            <label style="display:block;font-size:10px;color:#9aa0a6;font-weight:600;margin-bottom:6px;">رمز عبور جدید</label>
                            <input type="password" id="change-pwd-new" style="width:100%;padding:9px 12px;background:#171717;border:1px solid #3c4043;border-radius:12px;font-size:13px;color:#e8eaed;outline:none;text-align:center;font-family:'JetBrains Mono',monospace;" onfocus="this.style.borderColor='rgba(242,139,130,0.5)';" onblur="this.style.borderColor='#3c4043';">
                        </div>
                        <button type="button" onclick="changeAdminPassword()" id="change-pwd-btn" style="width:100%;padding:10px;background:rgba(242,139,130,0.12);border:1px solid rgba(242,139,130,0.3);border-radius:12px;font-size:12px;font-weight:700;color:#f28b82;cursor:pointer;font-family:Vazirmatn,sans-serif;" onmouseover="this.style.background='rgba(242,139,130,0.2)';" onmouseout="this.style.background='rgba(242,139,130,0.12)';">تغییر رمز عبور</button>
                    </div>
                </div>
                <!-- Footer Buttons -->
                <div style="display:flex;gap:10px;border-top:1px solid #3c4043;padding-top:16px;">
                    <button type="button" onclick="toggleSettingsModal(false)" style="flex:1;padding:10px;background:#292a2d;border:1px solid #3c4043;border-radius:12px;font-size:13px;font-weight:700;color:#9aa0a6;cursor:pointer;font-family:Vazirmatn,sans-serif;" onmouseover="this.style.color='#e8eaed';" onmouseout="this.style.color='#9aa0a6';">انصراف</button>
                    <button type="button" onclick="saveSettings()" id="save-settings-btn" style="flex:1;padding:10px;background:linear-gradient(135deg,#4f8ef7,#7b5cf9);border:none;border-radius:12px;font-size:13px;font-weight:700;color:#fff;cursor:pointer;font-family:Vazirmatn,sans-serif;box-shadow:0 4px 14px rgba(138,180,248,0.2);">ذخیره تنظیمات</button>
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

        // Toast notification system
        function showToast(message, type) {
            type = type || 'info';
            const container = document.getElementById('toast-container');
            const colors = {
                success: { bg: 'rgba(32,33,36,0.97)', border: 'rgba(129,201,149,0.4)', icon: '#81c995', dot: '#81c995' },
                error: { bg: 'rgba(32,33,36,0.97)', border: 'rgba(242,139,130,0.4)', icon: '#f28b82', dot: '#f28b82' },
                warning: { bg: 'rgba(32,33,36,0.97)', border: 'rgba(253,226,147,0.4)', icon: '#fde293', dot: '#fde293' },
                info: { bg: 'rgba(32,33,36,0.97)', border: 'rgba(138,180,248,0.4)', icon: '#8ab4f8', dot: '#8ab4f8' }
            };
            const c = colors[type] || colors.info;
            const icons = {
                success: '<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M5 13l4 4L19 7"/>',
                error: '<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M6 18L18 6M6 6l12 12"/>',
                warning: '<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v4m0 4h.01M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z"/>',
                info: '<circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/>'
            };
            const toast = document.createElement('div');
            toast.className = 'toast-enter';
            toast.style.cssText = 'display:flex;align-items:center;gap:10px;padding:11px 16px;background:' + c.bg + ';border:1px solid ' + c.border + ';border-radius:14px;box-shadow:0 8px 24px rgba(0,0,0,0.5);pointer-events:auto;max-width:340px;min-width:200px;cursor:pointer;';
            toast.innerHTML = '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="' + c.icon + '" stroke-width="2">' + (icons[type]||icons.info) + '</svg>' +
                '<span style="font-size:13px;font-weight:600;color:#e8eaed;flex:1;font-family:Vazirmatn,sans-serif;">' + message + '</span>';
            toast.onclick = function() { removeToast(toast); };
            container.appendChild(toast);
            setTimeout(function() { removeToast(toast); }, 4000);
        }
        function removeToast(toast) {
            if (!toast.parentNode) return;
            toast.className = 'toast-exit';
            setTimeout(function() { if (toast.parentNode) toast.parentNode.removeChild(toast); }, 250);
        }

        function renderPortCheckboxes() {
            const tlsContainer = document.getElementById('tls-ports-list');
            const nonTlsContainer = document.getElementById('nontls-ports-list');

            tlsContainer.innerHTML = tlsPorts.map(function(port) {
                var isCheckedDefault = port === '443' ? 'checked' : '';
                return '<label style="cursor:pointer;">' +
                    '<input type="checkbox" name="ports" value="' + port + '" ' + isCheckedDefault + ' class="checkbox-port">' +
                    '<div class="port-label">' + port + '</div>' +
                '</label>';
            }).join('');

            nonTlsContainer.innerHTML = nonTlsPorts.map(function(port) {
                return '<label style="cursor:pointer;">' +
                    '<input type="checkbox" name="ports" value="' + port + '" class="checkbox-port nontls">' +
                    '<div class="port-label">' + port + '</div>' +
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
            if (show) {
                modal.classList.add('open');
            } else {
                modal.classList.remove('open');
            }
        }

        function toggleModal(show) {
            const modal = document.getElementById('user-modal');
            if (show) {
                modal.classList.add('open');
            } else {
                modal.classList.remove('open');
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

            document.getElementById('input-proxy-select').value = '';
            document.getElementById('input-proxy').style.display = 'none';
            document.getElementById('input-proxy').value = '';

            toggleModal(true);
        }

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
                    loadingState.innerHTML = '<span style="color:#f28b82;font-size:13px;">خطا در دریافت اطلاعات از سرور</span>';
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
                document.getElementById('loading-state').innerHTML = '<span style="color:#f28b82;font-size:13px;">خطا در پردازش اطلاعات کاربران</span>';
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
                    document.getElementById('empty-state-msg').innerText = 'کاربری با مشخصات جستجو شده یافت نشد.';
                } else {
                    document.getElementById('empty-state-msg').innerText = 'کاربری وجود ندارد. برای ساخت اولین کاربر روی دکمه «+» کلیک کنید.';
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

                    let dailyHtml = '';
                    {
                        const dailyUsed = user.daily_used_gb || 0;
                        const fmtDailyUsed = dailyUsed < 1 ? (dailyUsed * 1024).toFixed(0) + ' MB' : dailyUsed.toFixed(2) + ' GB';
                        if (user.daily_limit_gb) {
                            const dPercent = Math.min((dailyUsed / user.daily_limit_gb) * 100, 100);
                            const dHue = 120 - (dPercent * 1.2);
                            const fmtDailyLimit = user.daily_limit_gb < 1 ? (user.daily_limit_gb * 1024).toFixed(0) + ' MB' : user.daily_limit_gb + ' GB';
                            dailyHtml = '<div class="flex flex-col gap-1 w-full min-w-[130px] mt-2 pt-2 border-t border-dashed border-gray-200 dark:border-zinc-800">' +
                                '<div class="flex justify-between text-[10px] text-gray-400 font-medium">' +
                                    '<span>امروز: ' + fmtDailyUsed + '</span>' +
                                    '<span>روزانه: ' + fmtDailyLimit + '</span>' +
                                '</div>' +
                                '<div class="w-full bg-gray-200 dark:bg-zinc-700 rounded-full h-1 overflow-hidden">' +
                                    '<div class="h-1 rounded-full transition-all duration-500" style="width: ' + dPercent + '%; background-color: hsl(' + dHue + ', 80%, 45%)"></div>' +
                                '</div>' +
                            '</div>';
                        } else {
                            dailyHtml = '<div class="text-[10px] text-gray-400 mt-2 pt-2 border-t border-dashed border-gray-200 dark:border-zinc-800">امروز: ' + fmtDailyUsed + ' • روزانه: نامحدود</div>';
                        }
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
                            '<td class="p-4">' + volumeHtml + dailyHtml + '</td>' +
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
                    showToast('خطا: ' + (errData.error || 'عملیات ناموفق بود'), 'error');
                }
            } catch (err) {
                showToast('خطا در برقراری ارتباط با سرور', 'error');
            }
        }
        async function handleFormSubmit(event) {
            event.preventDefault();
            const submitButton = document.getElementById('submit-btn');
            submitButton.disabled = true;
            submitButton.innerText = isEditMode ? 'در حال ذخیره تغییرات...' : 'در حال ایجاد...';

            const username = document.getElementById('input-name').value;
            const limit = document.getElementById('input-limit').value || null;
            const daily = document.getElementById('input-daily').value || null;
            const expiry = document.getElementById('input-expiry').value || null;
            
            // Gather multiple selected ports
            const checkedPorts = Array.from(document.querySelectorAll('input[name="ports"]:checked')).map(cb => cb.value);
            
            // Validation: Ensure at least one port is selected
            if (checkedPorts.length === 0) {
                showToast('لطفا حداقل یک پورت را برای اتصال انتخاب کنید!', 'warning');
                submitButton.disabled = false;
                submitButton.innerText = isEditMode ? 'ذخیره تغییرات' : 'ایجاد کاربر';
                return;
            }

            const port = checkedPorts.join(',');
            const tls = checkedPorts.some(p => tlsPorts.includes(p)) ? 'on' : 'off';
            
            const ips = document.getElementById('input-ips').value;
            
            let proxy_ip = document.getElementById('input-proxy-select').value;
            if (proxy_ip === 'custom') {
                proxy_ip = document.getElementById('input-proxy').value;
            }

            const fingerprint = document.getElementById('fingerprint-select').value;

            const url = isEditMode ? '/api/users/' + encodeURIComponent(editingUsername) : '/api/users';
            const method = isEditMode ? 'PUT' : 'POST';

            try {
                const response = await fetch(url, {
                    method: method,
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ username, limit_gb: limit, daily_limit_gb: daily, expiry_days: expiry, tls, port, ips, proxy_ip, fingerprint })
                });
                
                if (response.ok) {
                    toggleModal(false);
                    await loadUsers(true);
                } else {
                    const errData = await response.json();
                    showToast('خطا: ' + (errData.error || 'عملیات ناموفق بود'), 'error');
                }
            } catch (err) {
                showToast('خطا در برقراری ارتباط با سرور', 'error');
            } finally {
                submitButton.disabled = false;
                submitButton.innerText = isEditMode ? 'ذخیره تغییرات' : 'ایجاد کاربر';
            }
        }

        function toggleQRModal(show, link, title) {
            link = link || '';
            title = title || 'اسکن کد QR';
            const modal = document.getElementById('qr-modal');
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
                modal.classList.add('open');
            } else {
                modal.classList.remove('open');
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
                    
                    links.push('vle' + 'ss://' + (user.uuid || '') + '@' + ip + ':' + portStr + '?type=xhttp&security=' + tlsVal + '&sni=' + host + '&host=' + host + '&path=%2F&fp=' + fp + '&encryption=none&allowInsecure=0&extra=' + encodeURIComponent(JSON.stringify({mode:'auto',maxUploadSize:1000000,maxConcurrentUploads:10})) + '#' + encodeURIComponent(remark));
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
                showToast('لینک ساب متنی با موفقیت کپی شد!', 'success');
            }).catch(() => {
                showToast('خطا در کپی کردن لینک ساب!', 'error');
            });
        }

        function copyStatusLink(encodedUsername) {
            const username = decodeURIComponent(encodedUsername);
            navigator.clipboard.writeText(getStatusLink(username)).then(() => {
                showToast('لینک صفحه وضعیت با موفقیت کپی شد!', 'success');
            }).catch(() => {
                showToast('خطا در کپی کردن لینک صفحه وضعیت!', 'error');
            });
        }

        function copyJsonSubLink(encodedUsername) {
            const username = decodeURIComponent(encodedUsername);
            navigator.clipboard.writeText(getJsonSubLink(username)).then(() => {
                showToast('لینک ساب JSON با موفقیت کپی شد!', 'success');
            }).catch(() => {
                showToast('خطا در کپی کردن لینک ساب JSON!', 'error');
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
                showToast('کانفیگ VLESS با موفقیت کپی شد!', 'success');
            }).catch(() => {
                showToast('خطا در کپی کردن کانفیگ!', 'error');
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
                        "network": ('xh' + 'ttp'),
                        ['xh' + 'ttp' + 'Settings']: { "host": host, "path": "/", "mode": 'auto' },
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
                showToast('کانفیگ JSON با موفقیت کپی شد!', 'success');
            }).catch(() => {
                showToast('خطا در کپی کردن کانفیگ JSON!', 'error');
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
                showToast('کاربر یافت نشد!', 'error');
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
            document.getElementById('input-daily').value = user.daily_limit_gb || '';
            document.getElementById('input-expiry').value = user.expiry_days || '';
            document.getElementById('input-ips').value = user.ips || '';
            
            const proxy_ip = user.proxy_ip || '';
            const selectEl = document.getElementById('input-proxy-select');
            const customEl = document.getElementById('input-proxy');
            
            let optionExists = false;
            for(let i=0; i<selectEl.options.length; i++) {
                if (selectEl.options[i].value === proxy_ip) {
                    optionExists = true; break;
                }
            }

            if (proxy_ip === '' || proxy_ip === 'none') {
                selectEl.value = '';
                customEl.style.display = 'none';
            } else if (optionExists) {
                selectEl.value = proxy_ip;
                customEl.style.display = 'none';
            } else {
                selectEl.value = 'custom';
                customEl.value = proxy_ip;
                customEl.style.display = 'block';
            }

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
                        showToast('کاربر با موفقیت حذف شد.', 'success');
                        await loadUsers(true);
                    } else {
                        const errData = await response.json();
                        showToast('خطا: ' + (errData.error || 'عملیات ناموفق بود'), 'error');
                    }
                } catch (err) {
                    showToast('خطا در برقراری ارتباط با سرور', 'error');
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
                    select.innerHTML = '<option value="">خطا در دریافت لوکیشن‌ها</option>';
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
                    showToast('تنظیمات با موفقیت ذخیره شد.' + (iata ? ' آی‌پی: ' + resolvedIp : ''), 'success');
                    toggleSettingsModal(false);
                } else {
                    showToast('خطا در ذخیره تنظیمات', 'error');
                }
            } catch (err) {
                showToast('خطا در برقراری ارتباط با سرور', 'error');
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
                showToast('وارد کردن رمز عبور فعلی و جدید الزامی است!', 'warning');
                return;
            }
            if (newPwd.length < 4) {
                showToast('رمز عبور جدید باید حداقل ۴ کاراکتر باشد!', 'warning');
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
                    showToast('رمز عبور با موفقیت تغییر کرد.', 'success');
                    document.getElementById('change-pwd-current').value = '';
                    document.getElementById('change-pwd-new').value = '';
                    toggleSettingsModal(false);
                } else {
                    showToast('خطا: ' + (data.error || 'عملیات ناموفق بود'), 'error');
                }
            } catch (err) {
                showToast('خطا در برقراری ارتباط با سرور', 'error');
            } finally {
                btn.disabled = false;
                btn.innerText = 'تغییر رمز عبور';
            }
        }

        async function logoutAdmin() {
            if (confirm('آیا می‌خواهید از پنل خارج شوید؟')) {
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
<html lang="fa" dir="rtl">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>MLMVPN - Pro Dashboard</title>
    <script src="https://cdn.tailwindcss.com"><\/script>
    <script src="https://cdnjs.cloudflare.com/ajax/libs/qrcodejs/1.0.0/qrcode.min.js"><\/script>
    <script>
        tailwind.config = {
            theme: {
                extend: {
                    colors: {
                        google: {
                            bg: '#171717',
                            surface: '#202124',
                            surface2: '#292a2d',
                            border: '#3c4043',
                            text: '#e8eaed',
                            muted: '#9aa0a6',
                            blue: '#8ab4f8',
                            blueHover: '#aecbfa',
                            green: '#81c995',
                            yellow: '#fde293',
                            red: '#f28b82',
                            purple: '#c58af9',
                        }
                    }
                }
            }
        }
    <\/script>
    <link href="https://fonts.googleapis.com/css2?family=Vazirmatn:wght@300;400;500;600;700;800&family=JetBrains+Mono:wght@400;600;700&display=swap" rel="stylesheet">
    <style>
        body {
            font-family: 'Vazirmatn', sans-serif;
            background-color: #171717;
            color: #e8eaed;
            -webkit-font-smoothing: antialiased;
        }
        .font-mono { font-family: 'JetBrains Mono', monospace; }
        .dashboard-card {
            background-color: #202124;
            border: 1px solid #3c4043;
            border-radius: 24px;
            box-shadow: 0 10px 30px -10px rgba(0, 0, 0, 0.5);
            overflow: hidden;
            position: relative;
        }
        .action-pad {
            transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
        }
        .action-pad:hover {
            background-color: #292a2d;
            border-color: #8ab4f8;
            transform: translateY(-2px);
        }
        @keyframes pulse-dot {
            0% { box-shadow: 0 0 0 0 rgba(129, 201, 149, 0.4); }
            70% { box-shadow: 0 0 0 10px rgba(129, 201, 149, 0); }
            100% { box-shadow: 0 0 0 0 rgba(129, 201, 149, 0); }
        }
        @keyframes pulse-dot-red {
            0% { box-shadow: 0 0 0 0 rgba(242, 139, 130, 0.4); }
            70% { box-shadow: 0 0 0 10px rgba(242, 139, 130, 0); }
            100% { box-shadow: 0 0 0 0 rgba(242, 139, 130, 0); }
        }
        @keyframes pulse-dot-yellow {
            0% { box-shadow: 0 0 0 0 rgba(253, 226, 147, 0.4); }
            70% { box-shadow: 0 0 0 10px rgba(253, 226, 147, 0); }
            100% { box-shadow: 0 0 0 0 rgba(253, 226, 147, 0); }
        }
        .status-dot { animation: pulse-dot 2s infinite; }
        .status-dot-red { animation: pulse-dot-red 2s infinite; }
        .status-dot-yellow { animation: pulse-dot-yellow 2s infinite; }
        .radial-progress {
            stroke-dasharray: 226.2;
            stroke-dashoffset: 0;
            transition: stroke-dashoffset 1s ease-out;
        }
        @keyframes shimmer {
            100% { background-position: 20px 0; }
        }
        .toast-notification {
            position: fixed;
            bottom: 24px;
            left: 50%;
            transform: translateX(-50%) translateY(100px);
            background: #202124;
            border: 1px solid #3c4043;
            border-radius: 16px;
            padding: 12px 24px;
            font-size: 13px;
            font-weight: 600;
            color: #81c995;
            box-shadow: 0 8px 32px rgba(0,0,0,0.5);
            z-index: 9999;
            opacity: 0;
            transition: all 0.4s cubic-bezier(0.4, 0, 0.2, 1);
            pointer-events: none;
        }
        .toast-notification.show {
            opacity: 1;
            transform: translateX(-50%) translateY(0);
        }
    </style>
</head>
<body class="min-h-screen flex items-center justify-center p-4 md:p-8 selection:bg-google-blue/30 selection:text-google-blue">

    <div class="w-full max-w-[1200px] grid grid-cols-1 lg:grid-cols-12 gap-6 relative z-10">

        <!-- ستون راست: پروفایل و اکشن‌ها -->
        <div class="lg:col-span-5 xl:col-span-4 flex flex-col gap-6">

            <!-- Profile & Status Card -->
            <div class="dashboard-card p-6 flex flex-col items-center text-center">
                <div class="absolute top-0 left-1/2 -translate-x-1/2 w-32 h-32 bg-google-blue/10 rounded-full blur-[40px] pointer-events-none"></div>
                <div class="w-20 h-20 rounded-[20px] bg-[#1a1a1c] border border-google-border flex items-center justify-center text-google-blue shadow-inner relative z-10 mb-4 transform rotate-3">
                    <div class="w-14 h-14 rounded-xl bg-google-blue/10 flex items-center justify-center transform -rotate-3">
                        <svg class="w-7 h-7" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                            <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>
                        </svg>
                    </div>
                </div>
                <h1 class="text-2xl font-black text-white tracking-tight mb-1 relative z-10">MLMVPN</h1>
                <p id="display-username" class="text-sm text-google-muted font-mono mb-6 relative z-10"></p>

                <!-- Status Badge -->
                <div id="status-card" class="w-full bg-[#1a1a1c] border border-google-border rounded-xl p-4 flex items-center justify-between relative z-10 shadow-sm">
                    <span class="text-sm font-bold text-google-muted">وضعیت اشتراک</span>
                    <div id="status-badge" class="flex items-center gap-2 bg-google-green/10 border border-google-green/20 px-3 py-1.5 rounded-lg">
                        <span id="status-dot" class="w-2 h-2 rounded-full bg-google-green status-dot"></span>
                        <span id="status-text" class="text-xs font-bold text-google-green uppercase tracking-widest">...</span>
                    </div>
                </div>

                <!-- Config Count -->
                <div class="w-full bg-[#1a1a1c] border border-google-border rounded-xl p-4 flex items-center justify-between relative z-10 shadow-sm mt-3">
                    <span class="text-sm font-bold text-google-muted">تعداد کانفیگ</span>
                    <div class="flex items-center gap-2 bg-google-blue/10 border border-google-blue/20 px-3 py-1.5 rounded-lg">
                        <svg class="w-3.5 h-3.5 text-google-blue" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/></svg>
                        <span id="config-count" class="text-xs font-bold text-google-blue font-mono tracking-widest">-</span>
                    </div>
                </div>
            </div>

            <!-- Command Center -->
            <div class="dashboard-card p-6">
                <h3 class="text-sm font-bold text-google-text mb-4 flex items-center gap-2">
                    <svg class="w-5 h-5 text-google-blue" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="18" height="18" rx="2" ry="2"/><line x1="9" y1="3" x2="9" y2="21"/></svg>
                    مرکز کنترل (کانفیگ‌ها)
                </h3>
                <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-1 gap-3">

                    <!-- Action 1: VLESS -->
                    <button onclick="copyVlessConfig()" class="action-pad bg-[#1a1a1c] border border-google-border rounded-xl p-3 flex items-center justify-between group text-right w-full">
                        <div class="flex items-center gap-3">
                            <div class="w-8 h-8 rounded-lg bg-google-surface2 flex items-center justify-center text-google-blue group-hover:bg-google-blue group-hover:text-google-bg transition-colors">
                                <svg class="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/></svg>
                            </div>
                            <div class="flex flex-col">
                                <span class="text-[13px] font-bold text-google-text">VLESS مستقیم</span>
                                <span class="text-[10px] text-google-muted font-mono mt-0.5">vless://...</span>
                            </div>
                        </div>
                        <span class="text-[10px] font-bold text-google-blue bg-google-blue/10 px-2.5 py-1.5 rounded-lg group-hover:bg-google-blue group-hover:text-google-bg transition-colors">کپی</span>
                    </button>

                    <!-- Action 2: JSON -->
                    <button onclick="copyJsonSub()" class="action-pad bg-[#1a1a1c] border border-google-border rounded-xl p-3 flex items-center justify-between group text-right w-full">
                        <div class="flex items-center gap-3">
                            <div class="w-8 h-8 rounded-lg bg-google-surface2 flex items-center justify-center text-google-blue group-hover:bg-google-blue group-hover:text-google-bg transition-colors">
                                <svg class="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><circle cx="12" cy="12" r="10"/><line x1="2" y1="12" x2="22" y2="12"/><path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"/></svg>
                            </div>
                            <div class="flex flex-col">
                                <span class="text-[13px] font-bold text-google-text">ساب‌اسکریپشن JSON</span>
                                <span class="text-[10px] text-google-muted font-mono mt-0.5">فرمت نوین</span>
                            </div>
                        </div>
                        <span class="text-[10px] font-bold text-google-blue bg-google-blue/10 px-2.5 py-1.5 rounded-lg group-hover:bg-google-blue group-hover:text-google-bg transition-colors">کپی</span>
                    </button>

                    <!-- Action 3: Text Sub -->
                    <button onclick="copyTextSub()" class="action-pad bg-[#1a1a1c] border border-google-border rounded-xl p-3 flex items-center justify-between group text-right w-full">
                        <div class="flex items-center gap-3">
                            <div class="w-8 h-8 rounded-lg bg-google-surface2 flex items-center justify-center text-google-blue group-hover:bg-google-blue group-hover:text-google-bg transition-colors">
                                <svg class="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"/><path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"/></svg>
                            </div>
                            <div class="flex flex-col">
                                <span class="text-[13px] font-bold text-google-text">ساب‌اسکریپشن متنی</span>
                                <span class="text-[10px] text-google-muted font-mono mt-0.5">فرمت کلاسیک</span>
                            </div>
                        </div>
                        <span class="text-[10px] font-bold text-google-blue bg-google-blue/10 px-2.5 py-1.5 rounded-lg group-hover:bg-google-blue group-hover:text-google-bg transition-colors">کپی</span>
                    </button>

                    <!-- Action 4: QR Code -->
                    <button onclick="showQR()" class="action-pad bg-[#1a1a1c] border border-google-border rounded-xl p-3 flex items-center justify-between group text-right w-full">
                        <div class="flex items-center gap-3">
                            <div class="w-8 h-8 rounded-lg bg-google-surface2 flex items-center justify-center text-google-green group-hover:bg-google-green group-hover:text-google-bg transition-colors">
                                <svg class="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><rect x="3" y="3" width="18" height="18" rx="2" ry="2"/><rect x="7" y="7" width="3" height="3"/><rect x="14" y="7" width="3" height="3"/><rect x="7" y="14" width="3" height="3"/></svg>
                            </div>
                            <div class="flex flex-col">
                                <span class="text-[13px] font-bold text-google-text">کد QR کانفیگ</span>
                                <span class="text-[10px] text-google-muted font-mono mt-0.5">اسکن با دوربین</span>
                            </div>
                        </div>
                        <span class="text-[10px] font-bold text-google-green bg-google-green/10 px-2.5 py-1.5 rounded-lg group-hover:bg-google-green group-hover:text-google-bg transition-colors">نمایش</span>
                    </button>

                </div>
            </div>
        </div>

        <!-- ستون چپ: داشبورد مصرف -->
        <div class="lg:col-span-7 xl:col-span-8 flex flex-col gap-6">

            <!-- Main Volume Chart -->
            <div class="dashboard-card p-6 md:p-8 flex flex-col justify-between h-full min-h-[220px]">
                <div class="flex items-start justify-between mb-4">
                    <div>
                        <h2 class="text-base font-bold text-google-text flex items-center gap-2 mb-1">
                            <svg class="w-5 h-5 text-google-blue" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><ellipse cx="12" cy="5" rx="9" ry="3"/><path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3"/><path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"/></svg>
                            حجم کل اشتراک
                        </h2>
                        <p class="text-xs text-google-muted">آنالیز مصرف دیتا نسبت به سقف مجاز</p>
                    </div>
                    <div class="text-left bg-[#1a1a1c] border border-google-border px-4 py-2 rounded-xl shadow-inner">
                        <span class="block text-[10px] text-google-muted font-bold uppercase tracking-widest mb-0.5">Total Limit</span>
                        <span id="total-limit-display" class="text-lg font-black text-white font-mono leading-none">-</span>
                    </div>
                </div>

                <div class="flex-1 flex items-center justify-center relative py-6 my-2">
                    <div class="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-48 h-48 bg-google-blue/5 rounded-full blur-[40px] pointer-events-none"></div>
                    <div class="text-center relative z-10 flex flex-col items-center">
                        <div class="inline-flex items-center gap-1.5 bg-google-blue/10 border border-google-blue/20 px-3 py-1 rounded-full mb-3 shadow-[0_0_10px_rgba(138,180,248,0.1)]">
                            <span class="w-1.5 h-1.5 rounded-full bg-google-blue animate-pulse"></span>
                            <span class="text-[10px] text-google-blue font-bold uppercase tracking-wider">حجم باقی‌مانده</span>
                        </div>
                        <div class="text-5xl sm:text-6xl md:text-7xl font-black text-white font-mono tracking-tighter drop-shadow-md flex items-baseline gap-2">
                            <span id="remaining-vol-big">-</span>
                            <span class="text-xl sm:text-2xl text-google-muted font-sans font-bold tracking-normal">GB</span>
                        </div>
                    </div>
                </div>

                <div>
                    <div class="flex items-end justify-between mb-3 text-sm">
                        <span class="font-bold text-google-text">مصرف شده: <span id="used-vol" class="font-mono text-google-blue text-lg ml-1">-</span></span>
                        <span id="volume-pct" class="text-google-blue font-black font-mono">0%</span>
                    </div>
                    <div class="w-full h-4 bg-[#1a1a1c] rounded-full overflow-hidden border border-google-border shadow-inner relative">
                        <div id="volume-progress" class="h-full bg-gradient-to-r from-google-blue to-[#aecbfa] rounded-full transition-all duration-1000 relative shadow-[0_0_15px_rgba(138,180,248,0.5)]" style="width: 0%;">
                            <div class="absolute inset-0 w-full h-full bg-[linear-gradient(45deg,transparent_25%,rgba(255,255,255,0.2)_50%,transparent_75%)] bg-[length:20px_20px] animate-[shimmer_1s_linear_infinite]"></div>
                        </div>
                    </div>
                </div>
            </div>

            <!-- Secondary Stats Grid -->
            <div class="grid grid-cols-1 md:grid-cols-2 gap-6">

                <!-- Today's Usage -->
                <div class="dashboard-card p-6 relative overflow-hidden group">
                    <div class="absolute -top-10 -right-10 w-24 h-24 bg-google-yellow/10 rounded-full blur-[30px] pointer-events-none group-hover:bg-google-yellow/20 transition-colors"></div>
                    <div class="flex items-center justify-between mb-6">
                        <h2 class="text-sm font-bold text-google-text flex items-center gap-2">
                            <svg class="w-5 h-5 text-google-yellow" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="22 12 18 12 15 21 9 3 6 12 2 12"/></svg>
                            مصرف امروز
                        </h2>
                        <span id="daily-pct" class="text-google-yellow font-black font-mono">0%</span>
                    </div>
                    <div class="w-full h-3 bg-[#1a1a1c] rounded-full overflow-hidden border border-google-border mb-4">
                        <div id="daily-progress" class="h-full bg-google-yellow rounded-full transition-all duration-1000" style="width: 0%;"></div>
                    </div>
                    <div class="flex items-center justify-between text-xs text-google-muted font-medium bg-[#1a1a1c] p-3 rounded-xl border border-google-border">
                        <div class="flex flex-col">
                            <span class="text-[10px] uppercase">Used Today</span>
                            <span id="daily-used" class="text-white font-mono font-bold">-</span>
                        </div>
                        <div class="w-px h-6 bg-google-border"></div>
                        <div class="flex flex-col text-left">
                            <span class="text-[10px] uppercase">Daily Limit</span>
                            <span id="daily-limit" class="text-white font-mono font-bold">-</span>
                        </div>
                    </div>
                </div>

                <!-- Time Remaining -->
                <div class="dashboard-card p-6 flex items-center justify-between relative overflow-hidden group">
                    <div class="absolute -top-10 -right-10 w-24 h-24 bg-google-purple/10 rounded-full blur-[30px] pointer-events-none group-hover:bg-google-purple/20 transition-colors"></div>
                    <div class="flex flex-col h-full justify-between z-10">
                        <h2 class="text-sm font-bold text-google-text flex items-center gap-2 mb-2">
                            <svg class="w-5 h-5 text-google-purple" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>
                            زمان باقی‌مانده
                        </h2>
                        <div class="mt-auto">
                            <p class="text-[10px] text-google-muted font-bold uppercase tracking-widest mb-1">Total Valid Time</p>
                            <p id="total-days" class="text-white font-mono font-bold text-sm bg-[#1a1a1c] border border-google-border px-3 py-1.5 rounded-lg inline-block">-</p>
                        </div>
                    </div>
                    <div class="relative w-24 h-24 flex items-center justify-center shrink-0 z-10">
                        <svg class="w-full h-full transform -rotate-90" viewBox="0 0 100 100">
                            <circle cx="50" cy="50" r="36" fill="transparent" stroke="#1a1a1c" stroke-width="8"></circle>
                            <circle id="expiry-ring" cx="50" cy="50" r="36" fill="transparent" stroke="#c58af9" stroke-width="8" stroke-linecap="round" class="radial-progress drop-shadow-[0_0_8px_rgba(197,138,249,0.5)]"></circle>
                        </svg>
                        <div class="absolute flex flex-col items-center justify-center">
                            <span id="days-remaining-num" class="text-lg font-black text-white font-mono leading-none">-</span>
                            <span class="text-[10px] text-google-purple font-bold">روز</span>
                        </div>
                    </div>
                </div>

            </div>
        </div>

    </div>

    <!-- QR Modal -->
    <div id="qr-modal" class="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/60 backdrop-blur-sm opacity-0 pointer-events-none transition-all duration-300 ease-out">
        <div class="w-full max-w-sm dashboard-card p-6 text-center transition-all transform duration-300 opacity-0 scale-95 ease-out">
            <h3 class="font-bold text-white mb-4">اسکن کد QR کانفیگ VLESS</h3>
            <div class="bg-white p-3 rounded-xl inline-block mb-4">
                <div id="qrcode-box" class="flex justify-center items-center w-48 h-48 mx-auto"></div>
            </div>
            <button onclick="toggleQRModal(false)" class="w-full py-2.5 bg-[#1a1a1c] hover:bg-google-surface2 border border-google-border font-bold rounded-xl text-sm transition text-google-text">بستن</button>
        </div>
    </div>

    <!-- Toast -->
    <div id="toast" class="toast-notification"></div>

    <script>
        /* {{USER_DATA_PLACEHOLDER}} */

        function showToast(msg) {
            var t = document.getElementById('toast');
            t.textContent = msg;
            t.classList.add('show');
            setTimeout(function() { t.classList.remove('show'); }, 2500);
        }

        function toggleQRModal(show, link) {
            var modal = document.getElementById('qr-modal');
            var card = modal.querySelector('div');
            var qrBox = document.getElementById('qrcode-box');
            if (show) {
                qrBox.innerHTML = '';
                new QRCode(qrBox, {
                    text: link || '',
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
            var u = window.statusUser;
            var host = getHost();
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
                    links.push('vle' + 'ss://' + (u.uuid || '') + '@' + ip + ':' + portStr + '?type=xhttp&security=' + tlsVal + '&sni=' + host + '&host=' + host + '&path=%2F&fp=' + fp + '&encryption=none&allowInsecure=0&extra=' + encodeURIComponent(JSON.stringify({mode:'auto',maxUploadSize:1000000,maxConcurrentUploads:10})) + '#' + encodeURIComponent(remark));
                });
            });
            return links.join('\\n');
        }

        function copyVlessConfig() {
            navigator.clipboard.writeText(getVlessLink()).then(function() { showToast('\\u2705 \\u06a9\\u0627\\u0646\\u0641\\u06cc\\u06af VLESS \\u0628\\u0627 \\u0645\\u0648\\u0641\\u0642\\u06cc\\u062a \\u06a9\\u067e\\u06cc \\u0634\\u062f!'); });
        }

        function copyJsonSub() {
            var link = window.location.protocol + '//' + getHost() + '/feed/json/' + encodeURIComponent(window.statusUser.username);
            navigator.clipboard.writeText(link).then(function() { showToast('\\u2705 \\u0644\\u06cc\\u0646\\u06a9 \\u0633\\u0627\\u0628 JSON \\u06a9\\u067e\\u06cc \\u0634\\u062f!'); });
        }

        function copyTextSub() {
            var link = window.location.protocol + '//' + getHost() + '/sub/' + encodeURIComponent(window.statusUser.username);
            navigator.clipboard.writeText(link).then(function() { showToast('\\u2705 \\u0644\\u06cc\\u0646\\u06a9 \\u0633\\u0627\\u0628 \\u0645\\u062a\\u0646\\u06cc \\u06a9\\u067e\\u06cc \\u0634\\u062f!'); });
        }

        function showQR() {
            toggleQRModal(true, getVlessLink());
        }

        document.addEventListener('DOMContentLoaded', function() {
            var u = window.statusUser;
            if (!u) return;

            document.getElementById('display-username').innerText = '@' + u.username;

            var cfgIps = [getHost()];
            if (u.ips) {
                var parsed = u.ips.split('\\n').map(function(x){return x.trim();}).filter(function(x){return x.length>0;});
                if (parsed.length > 0) cfgIps = parsed;
            }
            var cfgPorts = String(u.port || '443').split(',').map(function(x){return x.trim();}).filter(function(x){return x.length>0;});
            document.getElementById('config-count').innerText = (cfgIps.length * cfgPorts.length) + ' \\u0639\\u062f\\u062f';

            var usedGb = u.used_gb || 0;
            var limitGb = u.limit_gb;
            var formattedUsed = usedGb < 1 ? (usedGb * 1024).toFixed(0) + ' MB' : usedGb.toFixed(2) + ' GB';
            document.getElementById('used-vol').innerText = formattedUsed;

            var isVolumeExpired = false;
            if (limitGb) {
                document.getElementById('total-limit-display').innerText = limitGb + ' GB';
                var remainGb = Math.max(0, limitGb - usedGb);
                document.getElementById('remaining-vol-big').innerText = remainGb < 1 ? (remainGb * 1024).toFixed(0) + ' MB' : remainGb.toFixed(2);
                if (remainGb >= 1) {
                    document.getElementById('remaining-vol-big').nextElementSibling.innerText = 'GB';
                } else {
                    document.getElementById('remaining-vol-big').nextElementSibling.innerText = '';
                }
                var pct = Math.min((usedGb / limitGb) * 100, 100);
                document.getElementById('volume-pct').innerText = pct.toFixed(0) + '%';
                document.getElementById('volume-progress').style.width = Math.max(pct, 2) + '%';
                if (usedGb >= limitGb) isVolumeExpired = true;
            } else {
                document.getElementById('total-limit-display').innerText = '\\u221e';
                document.getElementById('remaining-vol-big').innerText = '\\u221e';
                document.getElementById('remaining-vol-big').nextElementSibling.innerText = '';
                document.getElementById('volume-pct').innerText = '0%';
                document.getElementById('volume-progress').style.width = '100%';
                document.getElementById('volume-progress').style.background = 'linear-gradient(to left, #81c995, #34d399)';
            }

            var isDailyExpired = false;
            var dailyUsed = u.daily_used_gb || 0;
            var fmtDailyUsed = dailyUsed < 1 ? (dailyUsed * 1024).toFixed(0) + ' MB' : dailyUsed.toFixed(2) + ' GB';
            document.getElementById('daily-used').innerText = fmtDailyUsed;
            if (u.daily_limit_gb) {
                document.getElementById('daily-limit').innerText = u.daily_limit_gb + ' GB';
                var dpct = Math.min((dailyUsed / u.daily_limit_gb) * 100, 100);
                document.getElementById('daily-pct').innerText = dpct.toFixed(0) + '%';
                document.getElementById('daily-progress').style.width = Math.max(dpct, 2) + '%';
                if (dailyUsed >= u.daily_limit_gb) isDailyExpired = true;
            } else {
                document.getElementById('daily-limit').innerText = '\\u221e';
                document.getElementById('daily-pct').innerText = '0%';
                document.getElementById('daily-progress').style.width = '100%';
                document.getElementById('daily-progress').style.background = '#81c995';
            }

            var daysRemaining = null;
            var isTimeExpired = false;
            if (u.expiry_days) {
                document.getElementById('total-days').innerText = u.expiry_days + ' Days';
                if (u.created_at) {
                    var created = new Date(u.created_at);
                    var expiryDate = new Date(created.getTime() + (u.expiry_days * 24 * 60 * 60 * 1000));
                    var diffDays = Math.ceil((expiryDate - new Date()) / (1000 * 60 * 60 * 24));
                    daysRemaining = diffDays > 0 ? diffDays : 0;
                    document.getElementById('days-remaining-num').innerText = daysRemaining;

                    var expiryPct = Math.max(0, Math.min(100, (daysRemaining / u.expiry_days) * 100));
                    var circumference = 226.2;
                    var offset = circumference - (circumference * expiryPct / 100);
                    document.getElementById('expiry-ring').style.strokeDashoffset = offset;
                    if (new Date() > expiryDate) isTimeExpired = true;
                }
            } else {
                document.getElementById('total-days').innerText = '\\u221e';
                document.getElementById('days-remaining-num').innerText = '\\u221e';
                document.getElementById('expiry-ring').style.strokeDashoffset = '0';
            }

            var badge = document.getElementById('status-badge');
            var dot = document.getElementById('status-dot');
            var txt = document.getElementById('status-text');

            if (u.is_active === 0) {
                badge.className = 'flex items-center gap-2 bg-google-red/10 border border-google-red/20 px-3 py-1.5 rounded-lg';
                dot.className = 'w-2 h-2 rounded-full bg-google-red status-dot-red';
                txt.className = 'text-xs font-bold text-google-red uppercase tracking-widest';
                txt.innerText = 'Disabled';
            } else if (isVolumeExpired) {
                badge.className = 'flex items-center gap-2 bg-google-yellow/10 border border-google-yellow/20 px-3 py-1.5 rounded-lg';
                dot.className = 'w-2 h-2 rounded-full bg-google-yellow status-dot-yellow';
                txt.className = 'text-xs font-bold text-google-yellow uppercase tracking-widest';
                txt.innerText = 'Vol. Used';
            } else if (isDailyExpired) {
                badge.className = 'flex items-center gap-2 bg-google-yellow/10 border border-google-yellow/20 px-3 py-1.5 rounded-lg';
                dot.className = 'w-2 h-2 rounded-full bg-google-yellow status-dot-yellow';
                txt.className = 'text-xs font-bold text-google-yellow uppercase tracking-widest';
                txt.innerText = 'Daily Cap';
            } else if (isTimeExpired) {
                badge.className = 'flex items-center gap-2 bg-google-red/10 border border-google-red/20 px-3 py-1.5 rounded-lg';
                dot.className = 'w-2 h-2 rounded-full bg-google-red status-dot-red';
                txt.className = 'text-xs font-bold text-google-red uppercase tracking-widest';
                txt.innerText = 'Expired';
            } else {
                badge.className = 'flex items-center gap-2 bg-google-green/10 border border-google-green/20 px-3 py-1.5 rounded-lg';
                dot.className = 'w-2 h-2 rounded-full bg-google-green status-dot';
                txt.className = 'text-xs font-bold text-google-green uppercase tracking-widest';
                txt.innerText = 'Active';
            }
        });
    <\/script>
</body>
</html>`
};
