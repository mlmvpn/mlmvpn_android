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
  // Config Studio's control plane, behind a per-installation random path segment.
  //
  // ORDERING IS LOAD-BEARING (plan R6). This sits above every transport matcher and above the
  // camouflage fallback, and both directions of getting it wrong are bad: below the transport
  // matchers the API would be shadowed by a tunnel path, and below the fallback it would answer
  // with the fake nginx page and HTTP 200 -- a success status carrying the wrong body, which is the
  // single worst failure mode in this file because nothing downstream can tell it went wrong.
  //
  // It is safe this high precisely because the prefix is a random hex segment: it cannot collide
  // with `/`, with the uuid-shaped xhttp paths, or with any reserved name. On a build-5
  // installation `STUDIO_ROUTE` is absent and this whole block is skipped, so nothing about the
  // existing routing changes.
  const studioRoute = env.STUDIO_ROUTE;
  const studioPrefix = studioRoute ? '/' + studioRoute : null;
  if (studioPrefix && (url.pathname === studioPrefix || url.pathname.startsWith(studioPrefix + '/'))) {
    return await studioHandle(request, env, ctx, url, studioPrefix);
  }

  // The subscription a person's client fetches. Public and token-gated, and NOT behind the random
  // route segment: the whole point is that it can be opened by someone who was handed nothing but
  // the link. The token is the secret, so it is guarded by being unguessable rather than by being
  // hidden -- and unlike the legacy `/sub/{username}` it can be rotated and revoked.
  if (studioRoute && url.pathname.startsWith('/s/')) {
    const token = url.pathname.slice(3);
    if (token) return await studioServeSubscription(request, env, ctx, token, url);
  }

  // The page. Same token, same rules -- it shows the person what their client cannot: why something
  // stopped, what the reset policy is, and how many devices they may use.
  if (studioRoute && url.pathname.startsWith('/p/')) {
    const token = url.pathname.slice(3);
    if (token) return await studioServePage(env, token, url);
  }

  // A Config Studio installation does not serve the legacy panel's public pages at all.
  //
  // They were the likeliest reason an account drew an abuse report minutes after a deploy, before
  // a single user existed: `/admin` is a password form on a fresh workers.dev name (what phishing
  // scanners look for), and behind it sat a panel whose password was `admin` unless somebody
  // changed it -- so bots that sweep workers.dev for default panels could log in, make users and
  // push their own traffic through the account. `/status/{name}` and `/sub/{name}` also handed a
  // user's credential to anyone who guessed a username. Everything Config Studio does goes through
  // the random route and the unguessable `/s/{token}`, so on such an installation these paths are
  // just the camouflage page. `/api/*` stays, for the app's older screen, behind the password.
  if (studioRoute && (url.pathname === '/admin' || url.pathname === '/locations' ||
    url.pathname.startsWith('/status/') || Router.isSubscriptionPath(url.pathname) ||
    // Until the app redeploys with a password of its own, the default one opens nothing.
    (url.pathname.startsWith('/api/') && String(env.ADMIN_PASSWORD || '') === 'admin'))) {
    return new Response(HTML_TEMPLATES.nginx, { headers: { "Content-Type": "text/html; charset=utf-8" } });
  }

  const reserved = url.pathname.startsWith('/api/') || url.pathname.startsWith('/sub/') ||
    url.pathname.startsWith('/feed/') || url.pathname.startsWith('/status/') ||
    url.pathname === '/admin' || url.pathname === '/locations' ||
    url.pathname.startsWith('/s/') || url.pathname.startsWith('/p/') ||
    (studioPrefix !== null && url.pathname.startsWith(studioPrefix));

  // XHTTP packet-up through the session's own Durable Object (build 23).
  //
  // A packet-up session is a download GET plus upload POSTs that must meet in ONE isolate, and on
  // workers.dev they did not: the POSTs landed in other isolates and were lost (measured
  // 2026-09-26), which is why XHTTP was taken out of every create flow. A Durable Object is the one
  // place Cloudflare guarantees they meet -- every request named `x:{session}` reaches the same
  // object, in the same isolate -- so each of them is handed to that object, which runs this very
  // routing again (marked, so it is not forwarded twice) and finds the session it already holds.
  // Without the SESSIONS binding (an install where Durable Objects were unavailable) nothing changes.
  if (env.SESSIONS && !reserved && request.headers.get(STUDIO_XHTTP_HOP) !== '1') {
    const m = url.pathname.match(/^\/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})(?:\/(0|[1-9][0-9]*))?$/i);
    if (m && ((request.method === 'GET' && m[2] === undefined) || (request.method === 'POST' && m[2] !== undefined))) {
      dbg(env, ctx, 'ROUTE xhttp-do ' + request.method + ' ' + url.pathname);
      const stub = env.SESSIONS.get(env.SESSIONS.idFromName('x:' + m[1].toLowerCase()));
      const hop = new Request(request);
      hop.headers.set(STUDIO_XHTTP_HOP, '1');
      return await stub.fetch(hop);
    }
  }

  // XHTTP packet-up's download: the GET runs the whole connection (09-xhttp.js).
  if (request.method === 'GET' && !reserved) {
    const matchGet = url.pathname.match(/^\/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$/i);
    if (matchGet) {
      dbg(env, ctx, 'ROUTE xhttp-down ' + url.pathname);
      return studioXhttpDownload(env, ctx, matchGet[1].toLowerCase());
    }
  }

  // XHTTP packet-up's upload POSTs after the first: read whole, delivered in sequence.
  if (request.method === 'POST' && !reserved) {
    const matchUp = url.pathname.match(/^\/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\/([1-9][0-9]*)$/i);
    if (matchUp) {
      const seq = parseInt(matchUp[2], 10);
      dbg(env, ctx, 'ROUTE xhttp-up ' + url.pathname);
      const session = getOrCreateSession(matchUp[1].toLowerCase());
      let body = new Uint8Array(0);
      if (request.body) {
        try { body = new Uint8Array(await request.arrayBuffer()); } catch (e) { }
      }
      studioXhttpDeliver(session, seq, body);
      return new Response("OK", { status: 200 });
    }
  }

  // کانال داده اصلی (هر درخواست POST دارای بدنه که مسیر کنترلی نیست)
  if (request.method === 'POST' && upgrade !== 'websocket' && request.body && !reserved) {
    dbg(env, ctx, 'ROUTE transport ' + url.pathname);
    return await Router.handleTransport(request, env, ctx);
  }

  // مسیر جایگزین وب‌سوکت (برای کلاینت‌های قدیمی)
  //
  // E1: `/` as it has always been, **plus** any path a config actually claims.
  //
  // Per-user paths were impossible while this tested `pathname === '/'`, and the shape of the fix
  // matters more than the fix. The obvious version -- accept a WebSocket upgrade on any
  // non-reserved path -- would answer 101 to an upgrade aimed anywhere, which is a fingerprint: the
  // camouflage page exists so that everything this worker does not serve looks like a web server,
  // and a 101 on a random path says otherwise to anyone who tries one.
  //
  // So an unknown path still gets nginx. Only a path some config has registered as its `route_key`
  // is a tunnel, matched on the unique index `ux_configs_route`.
  //
  // **The lookup only runs for a path that is not `/`.** Every config this app has ever emitted uses
  // the root, so the common case costs no extra read at all -- and a read here is a read per tunnel
  // connection, which is the one place in this worker where per-request cost is multiplied by every
  // person using it.
  if (Router.isWebSocketUpgrade(request) && !reserved) {
    if (url.pathname === '/') {
      return await Router.handleWebSocket(request, env, ctx);
    }
    if (studioRoute && await studioRouteKeyServed(env, url.pathname)) {
      dbg(env, ctx, 'ROUTE ws-path ' + url.pathname);
      return await Router.handleWebSocket(request, env, ctx);
    }
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
