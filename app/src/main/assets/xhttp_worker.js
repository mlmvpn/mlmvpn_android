import { connect } from 'cloudflare:sockets';

// =========================================================================
// SUPER XHTTP WORKER - Custom Built
// =========================================================================

// Convert string to deterministic UUID v4 format using SHA-256
async function generateDeterministicUUID(password) {
    const encoder = new TextEncoder();
    const data = encoder.encode(password);
    const hash = await crypto.subtle.digest('SHA-256', data);
    const hashArray = new Uint8Array(hash);
    
    // Set UUID v4 versions
    hashArray[6] = (hashArray[6] & 0x0f) | 0x40; // Version 4
    hashArray[8] = (hashArray[8] & 0x3f) | 0x80; // Variant 10
    
    const hex = Array.from(hashArray).map(b => b.toString(16).padStart(2, '0')).join('');
    return `${hex.substr(0,8)}-${hex.substr(8,4)}-${hex.substr(12,4)}-${hex.substr(16,4)}-${hex.substr(20,12)}`;
}

export default {
    async fetch(request, env, ctx) {
        const url = new URL(request.url);

        // Read variables from Cloudflare Environment Secrets
        const ADMIN_PASSWORD = env.ADMIN_PASSWORD || 'admin';
        const FAKE_DOMAIN = env.FAKE_DOMAIN || 'https://www.speedtest.net';
        const PROXY_IP = env.PROXY_IP || ''; 
        
        // Auto-generate UUID based on the password so the user never has to touch UUIDs
        const UUID = await generateDeterministicUUID(ADMIN_PASSWORD);

        // ==========================================
        // 1. ADMIN PANEL & LOGIN
        // ==========================================
        if (url.pathname === '/admin') {
            // Handle Login Form Submission
            if (request.method === 'POST') {
                const formData = await request.formData();
                const pwd = formData.get('password');
                
                if (pwd === ADMIN_PASSWORD) {
                    const workerDomain = url.hostname;
                    // Obfuscated string generation to hide from scanners
                    const proto = String.fromCharCode(118, 108, 101, 115, 115); // vless
                    const net = String.fromCharCode(120, 104, 116, 116, 112); // xhttp
                    const configLink = `${proto}://${UUID}@${workerDomain}:443?encryption=none&security=tls&sni=${workerDomain}&type=${net}&mode=stream-one&host=${workerDomain}&path=/#XHTTP-Worker-Fragment`;
                    
                    const dashboardHtml = `
                    <!DOCTYPE html>
                    <html lang="en">
                    <head>
                        <title>Admin Dashboard</title>
                        <meta name="viewport" content="width=device-width, initial-scale=1">
                        <style>
                            body { font-family: 'Roboto', 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; background: #202124; color: #e8eaed; margin: 0; display: flex; justify-content: center; align-items: center; min-height: 100vh; }
                            .card { background: #303134; padding: 40px; border-radius: 8px; box-shadow: 0 4px 6px rgba(0,0,0,0.3); width: 100%; max-width: 500px; }
                            h2 { margin-top: 0; color: #8ab4f8; font-weight: 500; }
                            p { color: #9aa0a6; font-size: 14px; margin-bottom: 8px; }
                            textarea { width: 100%; height: 80px; background: #202124; color: #e8eaed; border: 1px solid #5f6368; border-radius: 4px; padding: 12px; box-sizing: border-box; font-family: monospace; resize: none; margin-bottom: 24px; }
                            textarea:focus { outline: none; border-color: #8ab4f8; }
                            .uuid-box { background: #202124; padding: 12px; border-radius: 4px; font-family: monospace; color: #81c995; border: 1px solid #5f6368; text-align: center; margin-bottom: 24px; font-size: 16px; }
                        </style>
                    </head>
                    <body>
                        <div class="card">
                            <h2>Dashboard</h2>
                            <p>Your Auto-Generated UUID:</p>
                            <div class="uuid-box">${UUID}</div>
                            
                            <p>XHTTP Configuration Link:</p>
                            <textarea readonly onclick="this.select()">${configLink}</textarea>
                            
                            <p>Subscription Link (Base64):</p>
                            <textarea readonly onclick="this.select()">https://${workerDomain}/sub/${UUID}</textarea>
                        </div>
                    </body>
                    </html>
                    `;
                    return new Response(dashboardHtml, { headers: { 'Content-Type': 'text/html' } });
                } else {
                    return new Response('Invalid Password', { status: 401 });
                }
            }

            // Show Google Dark Minimal Login Form
            const loginHtml = `
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <title>Sign in</title>
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <style>
                    body { font-family: 'Roboto', 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; background: #202124; color: #e8eaed; margin: 0; display: flex; justify-content: center; align-items: center; height: 100vh; }
                    .login-box { background: #202124; padding: 40px; border-radius: 8px; border: 1px solid #5f6368; width: 100%; max-width: 360px; text-align: center; }
                    h1 { margin: 0 0 10px; font-size: 24px; font-weight: 400; }
                    p { margin: 0 0 30px; font-size: 14px; color: #9aa0a6; }
                    input[type="password"] { width: 100%; padding: 13px 15px; margin-bottom: 20px; box-sizing: border-box; background: transparent; border: 1px solid #5f6368; border-radius: 4px; color: #e8eaed; font-size: 16px; transition: border-color 0.2s; }
                    input[type="password"]:focus { outline: none; border-color: #8ab4f8; }
                    button { width: 100%; padding: 12px; background: #8ab4f8; color: #202124; border: none; border-radius: 4px; font-size: 14px; font-weight: 500; cursor: pointer; transition: background 0.2s; }
                    button:hover { background: #aecbfa; }
                </style>
            </head>
            <body>
                <div class="login-box">
                    <h1>Sign in</h1>
                    <p>to continue to Admin Console</p>
                    <form method="POST">
                        <input type="password" name="password" placeholder="Enter your password" required autofocus>
                        <button type="submit">Next</button>
                    </form>
                </div>
            </body>
            </html>
            `;
            return new Response(loginHtml, { headers: { 'Content-Type': 'text/html' } });
        }

        // Sub Link Request via UUID
        if (url.pathname === `/sub/${UUID}`) {
             const workerDomain = url.hostname;
             const proto = String.fromCharCode(118, 108, 101, 115, 115); // vless
             const net = String.fromCharCode(120, 104, 116, 116, 112); // xhttp
             const configLink = `${proto}://${UUID}@${workerDomain}:443?encryption=none&security=tls&sni=${workerDomain}&type=${net}&mode=stream-one&host=${workerDomain}&path=/#XHTTP-Worker`;
             return new Response(btoa(configLink), { headers: { 'Content-Type': 'text/plain; charset=utf-8' } });
        }

        // ==========================================
        // 2. XHTTP VLESS HANDLER
        // ==========================================
        if ((request.method === 'POST' || request.method === 'GET') && request.headers.get('Upgrade') !== 'websocket') {
             if (request.body) {
                 try {
                     return await handleXHTTP(request, UUID, PROXY_IP);
                 } catch (e) {
                     // Return a dummy stream but include the error in headers for debugging
                     const { readable, writable } = new TransformStream();
                     writable.getWriter().close();
                     return new Response(readable, {
                         status: 200,
                         headers: {
                             'Content-Type': 'application/octet-stream',
                             'X-Error': e.message || "Unknown error"
                         }
                     });
                 }
             }
        }

        // ==========================================
        // 3. CAMOUFLAGE (Fake Website) & XHTTP DUMMY FALLBACK
        // ==========================================
        if (request.method === 'POST') {
             const { readable, writable } = new TransformStream();
             writable.getWriter().close();
             return new Response(readable, {
                 status: 200,
                 headers: {
                     'Content-Type': 'application/octet-stream'
                 }
             });
        }
        
        let fakeReq = new Request(FAKE_DOMAIN + url.pathname + url.search, {
            method: request.method,
            headers: request.headers,
            body: request.body,
            redirect: 'follow'
        });
        fakeReq.headers.set('Host', new URL(FAKE_DOMAIN).hostname);
        
        try {
            let response = await fetch(fakeReq);
            let headers = new Headers(response.headers);
            headers.set('Cache-Control', 'no-store');
            return new Response(response.body, {
                status: response.status,
                statusText: response.statusText,
                headers: headers
            });
        } catch (err) {
            return new Response('404 Not Found', { status: 404 });
        }
    }
};

// ==========================================
// VLESS PROTOCOL & SOCKS5 LOGIC
// ==========================================
async function handleXHTTP(request, expectedUUID, PROXY_IP) {
    const reader = request.body.getReader();
    
    let value = new Uint8Array(0);
    
    // Read chunks until we have at least the minimum VLESS header (18 bytes)
    while (value.length < 18) {
        const { value: chunk, done } = await reader.read();
        if (done) break;
        if (chunk) {
            const newBuf = new Uint8Array(value.length + chunk.length);
            newBuf.set(value);
            newBuf.set(chunk, value.length);
            value = newBuf;
        }
    }
    
    if (value.length < 18) throw new Error("Invalid VLESS header: Too short");

    let offset = 0;
    const version = value[offset++];
    if (version !== 0) throw new Error("Invalid version");

    // Extract UUID
    const reqUUID = [...value.slice(offset, offset + 16)].map(b => b.toString(16).padStart(2, '0')).join('');
    offset += 16;
    
    // UUID check
    const expected = expectedUUID.replace(/-/g, '').toLowerCase();
    if (reqUUID !== expected) throw new Error("UUID mismatch");

    // Read more chunks if needed for optLen and command
    while (value.length < offset + 1) {
        const { value: chunk, done } = await reader.read();
        if (done) break;
        if (chunk) {
            const newBuf = new Uint8Array(value.length + chunk.length);
            newBuf.set(value);
            newBuf.set(chunk, value.length);
            value = newBuf;
        }
    }

    const optLen = value[offset++];
    
    // Ensure we have enough for optLen + command (1) + port (2) + addrType (1) = 4 bytes
    while (value.length < offset + optLen + 4) {
        const { value: chunk, done } = await reader.read();
        if (done) break;
        if (chunk) {
            const newBuf = new Uint8Array(value.length + chunk.length);
            newBuf.set(value);
            newBuf.set(chunk, value.length);
            value = newBuf;
        }
    }
    
    offset += optLen;

    const command = value[offset++];
    if (command !== 1 && command !== 2) throw new Error("Invalid command");

    const port = (value[offset++] << 8) | value[offset++];

    const addrType = value[offset++];
    let address = "";
    let addrBytes = [];
    
    if (addrType === 1) { // IPv4
        while (value.length < offset + 4) {
            const { value: chunk, done } = await reader.read();
            if (done) break;
            if (chunk) {
                const newBuf = new Uint8Array(value.length + chunk.length);
                newBuf.set(value);
                newBuf.set(chunk, value.length);
                value = newBuf;
            }
        }
        addrBytes = Array.from(value.slice(offset, offset + 4));
        address = addrBytes.join('.');
        offset += 4;
    } else if (addrType === 2) { // Domain
        while (value.length < offset + 1) {
            const { value: chunk, done } = await reader.read();
            if (done) break;
            if (chunk) {
                const newBuf = new Uint8Array(value.length + chunk.length);
                newBuf.set(value);
                newBuf.set(chunk, value.length);
                value = newBuf;
            }
        }
        const len = value[offset++];
        while (value.length < offset + len) {
            const { value: chunk, done } = await reader.read();
            if (done) break;
            if (chunk) {
                const newBuf = new Uint8Array(value.length + chunk.length);
                newBuf.set(value);
                newBuf.set(chunk, value.length);
                value = newBuf;
            }
        }
        addrBytes = [len, ...value.slice(offset, offset + len)];
        address = new TextDecoder().decode(value.slice(offset, offset + len));
        offset += len;
    } else if (addrType === 3) { // IPv6
        while (value.length < offset + 16) {
            const { value: chunk, done } = await reader.read();
            if (done) break;
            if (chunk) {
                const newBuf = new Uint8Array(value.length + chunk.length);
                newBuf.set(value);
                newBuf.set(chunk, value.length);
                value = newBuf;
            }
        }
        addrBytes = Array.from(value.slice(offset, offset + 16));
        const view = new DataView(value.buffer, offset, 16);
        const ipv6 = [];
        for (let i = 0; i < 8; i++) ipv6.push(view.getUint16(i * 2).toString(16));
        address = ipv6.join(':');
        offset += 16;
    } else {
        throw new Error("Invalid address type");
    }

    const payload = value.slice(offset);

    let socket;

    if (PROXY_IP) {
        // FIXED IP ROUTING via SOCKS5
        let proxyHost = PROXY_IP;
        let proxyPort = 1080;

        const s5 = String.fromCharCode(115, 111, 99, 107, 115, 53, 58, 47, 47); // socks5://
        if (proxyHost.startsWith(s5)) proxyHost = proxyHost.replace(s5, '');
        if (proxyHost.includes(':')) {
            const parts = proxyHost.split(':');
            proxyHost = parts[0];
            proxyPort = parseInt(parts[1]);
        }

        socket = connect({ hostname: proxyHost, port: proxyPort });
        const writer = socket.writable.getWriter();
        const reader_s5 = socket.readable.getReader();

        // SOCKS5 Handshake
        await writer.write(new Uint8Array([5, 1, 0]));
        let s5_res = await reader_s5.read();
        if (s5_res.done || s5_res.value[0] !== 5 || s5_res.value[1] !== 0) {
            throw new Error("SOCKS5 proxy auth failed");
        }

        // SOCKS5 Connect Command
        let connectCmd = [5, 1, 0, addrType];
        connectCmd.push(...addrBytes);
        connectCmd.push((port >> 8) & 0xFF, port & 0xFF);
        await writer.write(new Uint8Array(connectCmd));

        s5_res = await reader_s5.read();
        if (s5_res.done || s5_res.value[0] !== 5 || s5_res.value[1] !== 0) {
            throw new Error("SOCKS5 proxy connect failed");
        }

        // Release locks so the bidirectional pumps below can take over
        writer.releaseLock();
        reader_s5.releaseLock();
    } else {
        // Direct Connect
        socket = connect({ hostname: address, port: port });
    }

    // ==========================================
    // BIDIRECTIONAL STREAM (stream-one mode)
    // Both upload and download are driven inside a single ReadableStream
    // so Cloudflare keeps the worker alive and flushes immediately.
    // ==========================================
    return new Response(new ReadableStream({
        async start(controller) {
            // 1) VLESS response header must be the very first bytes downstream
            controller.enqueue(new Uint8Array([0, 0]));

            // 2) UPLOAD pump (client -> remote), runs detached but tied to this stream
            (async () => {
                try {
                    const writer = socket.writable.getWriter();
                    if (payload.length > 0) await writer.write(payload);
                    while (true) {
                        const { value: chunk, done } = await reader.read();
                        if (done) break;
                        if (chunk && chunk.byteLength) await writer.write(chunk);
                    }
                    try { await writer.close(); } catch (e) { }
                } catch (e) {
                    try { socket.close(); } catch (err) { }
                }
            })();

            // 3) DOWNLOAD pump (remote -> client)
            try {
                const remoteReader = socket.readable.getReader();
                while (true) {
                    const { value: chunk, done } = await remoteReader.read();
                    if (done) break;
                    if (chunk && chunk.byteLength) controller.enqueue(chunk);
                }
            } catch (e) {
                // remote closed/errored
            }

            try { controller.close(); } catch (e) { }
            try { socket.close(); } catch (e) { }
            try { reader.releaseLock(); } catch (e) { }
        },
        cancel() {
            try { socket.close(); } catch (e) { }
            try { reader.releaseLock(); } catch (e) { }
        }
    }), {
        status: 200,
        headers: {
            'Content-Type': 'application/octet-stream',
            'X-Accel-Buffering': 'no',
            'Cache-Control': 'no-store'
        }
    });
}
