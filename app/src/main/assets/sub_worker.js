export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    const path = url.pathname;

    // Health check / version endpoint
    if (path === '/_health') {
        return new Response(JSON.stringify({ version: "1.0.0", status: "ok" }), {
            headers: { 'Content-Type': 'application/json' }
        });
    }

    // Sub link endpoint
    if (path.startsWith('/sub/')) {
      const slug = path.replace('/sub/', '').trim();
      if (!slug) return new Response('Invalid slug', { status: 400 });

      // Fetch from KV
      const dataStr = await env.kv.get(`sub_${slug}`);
      if (!dataStr) return new Response('Not Found', { status: 404 });

      let data;
      try {
        data = JSON.parse(dataStr);
      } catch(e) {
        data = { configs: dataStr, expiry: 0 };
      }

      // Check expiry
      if (data.expiry && data.expiry > 0 && Date.now() > data.expiry) {
        const expiredConfig = 'vless://127.0.0.1:443?encryption=none&security=none&type=tcp#%E2%9B%94%20%D9%84%DB%8C%D9%86%DA%A9%20%D8%B4%D9%85%D8%A7%20%D9%85%D9%86%D9%82%D8%B6%DB%8C%20%D8%B4%D8%AF%D9%87%20%D8%A7%D8%B3%D8%AA';
        const b64 = btoa(expiredConfig);
        return new Response(b64, {
            headers: { 'Content-Type': 'text/plain; charset=utf-8' }
        });
      }

      // Increment stats asynchronously
      ctx.waitUntil(incrementStats(env.kv, slug));

      // Return configs
      const configsStr = data.configs || '';
      
      // If client is requesting raw configs
      if (url.searchParams.has('raw')) {
          return new Response(configsStr, {
              headers: { 'Content-Type': 'text/plain; charset=utf-8' }
          });
      }

      // Base64 encode for v2rayNG etc
      const utf8Bytes = new TextEncoder().encode(configsStr);
      let binString = '';
      // Safe chunking to avoid Maximum call stack size exceeded
      const chunkSize = 8192;
      for (let i = 0; i < utf8Bytes.length; i += chunkSize) {
        binString += String.fromCharCode.apply(null, utf8Bytes.subarray(i, i + chunkSize));
      }
      const b64 = btoa(binString);
      
      return new Response(b64, {
          headers: { 
              'Content-Type': 'text/plain; charset=utf-8',
              'Cache-Control': 'no-cache, no-store, must-revalidate',
              'Profile-Update-Interval': '12',
              'Subscription-Userinfo': `upload=0; download=0; total=1073741824000; expire=${data.expiry ? Math.floor(data.expiry/1000) : 0}`
          }
      });
    }

    return new Response('Mlmvpn Sub Generator Service Running', { status: 200 });
  }
}

async function incrementStats(kv, slug) {
    try {
        let hits = await kv.get(`stat_${slug}`) || "0";
        let newHits = parseInt(hits) + 1;
        await kv.put(`stat_${slug}`, newHits.toString());
    } catch(e) {}
}
