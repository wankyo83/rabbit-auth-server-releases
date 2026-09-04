import http from 'node:http';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { WebSocket, WebSocketServer } from 'ws';
import { authorized, LabError } from './core.mjs';
import { Jobs } from './jobs.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const sourceUploadPendingImageUrl = 'https://raw.githubusercontent.com/wankyo83/rabbit-auth-server-releases/main/assets/source-upload-pending.png';
const paidContentLockedImageUrl = 'https://raw.githubusercontent.com/wankyo83/rabbit-auth-server-releases/main/assets/source-paid-locked.png';
const types = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.json': 'application/json', '.woff': 'font/woff', '.woff2': 'font/woff2', '.ico': 'image/x-icon' };

function json(res, status, data) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify(data));
}

function sameOrigin(req) {
  // Browser cross-site requests must not rely on a cached Basic credential.
  // Native extension clients omit Fetch Metadata and still use their explicit key.
  if (req.headers['sec-fetch-site'] === 'cross-site') return false;
  return !req.headers.origin || req.headers.origin === `http://${req.headers.host}` ||
    req.headers.origin === `https://${req.headers.host}`;
}

function trustedHost(req, allowedHosts) {
  try {
    const host = req.headers.host;
    if (!host || /[\s/@\\?#]/.test(host)) return false;
    const hostname = new URL(`http://${host}`).hostname.toLowerCase();
    return !!hostname && (allowedHosts.has('*') || allowedHosts.has(hostname));
  } catch { return false; }
}

async function body(req) {
  if (!req.headers['content-type']?.startsWith('application/json') || req.headers['x-lab-request'] !== '1')
    throw new LabError(415, 'json_and_x_lab_request_required');
  const chunks = [];
  let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > 4096) throw new LabError(413, 'body_too_large');
    chunks.push(chunk);
  }
  try { return JSON.parse(Buffer.concat(chunks).toString()); }
  catch { throw new LabError(400, 'invalid_json'); }
}

async function staticFile(res, base, name) {
  const target = path.resolve(base, name);
  if (!target.startsWith(path.resolve(base) + path.sep)) throw new LabError(404, 'not_found');
  let data;
  try { data = await fs.readFile(target); } catch { throw new LabError(404, 'not_found'); }
  res.writeHead(200, { 'Content-Type': types[path.extname(target)] || 'application/octet-stream' });
  res.end(data);
}

export function createServer({ password, authMode = 'password', jobs, novncDir = '/usr/share/novnc',
  allowedHosts = new Set(['127.0.0.1', 'localhost', '[::1]', 'rabbit-auth-server']),
  publicDir = path.join(root, 'public'), viewer = null, version = '0.3.9' }) {
  if (!['password', 'none'].includes(authMode)) throw new Error('Invalid SERVER_AUTH mode');
  // Generic destinations are safe only behind authentication. An explicit host
  // allowlist remains required for the optional unauthenticated local-test mode.
  if (allowedHosts.has('*') && authMode !== 'password')
    throw new Error('ALLOWED_HOSTS=* requires SERVER_AUTH=password');
  if (authMode === 'password' && (typeof password !== 'string' || password.length < 4 || password.startsWith('replace-with-')))
    throw new Error('SERVER_KEY must contain at least 4 characters and must not be the example value');
  // Disabling login requires explicit configuration; an absent password is not an opt-out.
  const canAccess = req => authMode === 'none' || authorized(req.headers.authorization, password);
  const server = http.createServer(async (req, res) => {
    // The private extension disables automatic retries (POSTs can create jobs).
    // On this NAS a reused idle HTTP socket was reset before the next request.
    // Explicitly retire REST sockets instead of retrying state-changing calls.
    // Desktop WebSocket upgrades are handled separately and stay connected.
    if (req.url === '/health' || req.url?.startsWith('/v1/')) res.setHeader('Connection', 'close');
    res.setHeader('Cache-Control', 'no-store');
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('Referrer-Policy', 'no-referrer');
    res.setHeader('Content-Security-Policy', "default-src 'self'; img-src 'self' data: blob:; style-src 'self' 'unsafe-inline'; connect-src 'self'; worker-src 'self' blob:; frame-ancestors 'self'");
    if (!trustedHost(req, allowedHosts)) return json(res, 403, { error: 'untrusted_host' });
    // This fixed, non-sensitive placeholder must be directly fetchable by the
    // reader's image client, which does not attach the server API credential.
    const assetPath = req.url?.split('?')[0];
    if (req.method === 'GET' && ['/assets/source-upload-pending.png','/assets/source-paid-locked.png'].includes(assetPath))
      return await staticFile(res, publicDir, assetPath.slice('/assets/'.length));
    if (!canAccess(req)) {
      res.setHeader('WWW-Authenticate', 'Basic realm="Rabbit Auth Server", charset="UTF-8"');
      return json(res, 401, { error: 'authentication_required' });
    }
    if (!sameOrigin(req)) return json(res, 403, { error: 'cross_origin_request_rejected' });
    try {
      const url = new URL(req.url, 'http://lab.invalid');
      if (req.method === 'GET' && url.pathname === '/health')
        return json(res, jobs.ready ? 200 : 503, {ready:jobs.ready,service:'rabbit-auth-server',version,protocol:1,concurrency:jobs.concurrency});
      if (viewer && await viewer({req,res,url,body:()=>body(req),json:(status,data)=>json(res,status,data)})) return;
      if (req.method === 'GET' && url.pathname === '/v1/jobs')
        return json(res, 200, {jobs:[...jobs.jobs.values()].map(j=>jobs.snapshot(j))});
      if (req.method === 'POST' && url.pathname === '/v1/jobs') {
        const data=await body(req);
        if (!jobs.ready) throw new LabError(503,'browser_not_ready');
        return json(res,202,jobs.open(data));
      }
      const match=url.pathname.match(/^\/v1\/jobs\/([a-f0-9-]{36})(?:\/(manifest|close|focus))?$/);
      if (match) {
        const [,id,action]=match;
        if (req.method === 'GET' && !action) return json(res,200,jobs.snapshot(jobs.get(id)));
        if (req.method === 'POST') {
          await body(req);
          if (action === 'manifest') {
            const manifest=jobs.manifest(id);
            if (manifest.notice === 'source_upload_pending' || manifest.notice === 'paid_content_locked') {
              // Reader extensions intentionally accept only public HTTPS image
              // destinations. Reuse one fixed public asset rather than creating
              // a per-chapter image or exposing the private HTTP server address.
              const imageUrl=manifest.notice === 'paid_content_locked' ? paidContentLockedImageUrl : sourceUploadPendingImageUrl;
              manifest.pages=[{page:1,urls:[imageUrl]}];
            }
            return json(res,200,manifest);
          }
          if (action === 'close') {await jobs.closeJob(id);return json(res,200,{state:'closed'});}
          if (action === 'focus') {
            const job=jobs.get(id); if (!job.page) throw new LabError(409,'browser_closed');
            await job.page.bringToFront();return json(res,200,{state:job.state});
          }
        }
      }
      if (!viewer && req.method === 'GET' && url.pathname.startsWith('/desktop/'))
        return await staticFile(res, novncDir, decodeURIComponent(url.pathname.slice('/desktop/'.length)));
      if (req.method === 'GET' && ['/', '/app.js', '/style.css'].includes(url.pathname))
        return await staticFile(res, publicDir, url.pathname === '/' ? 'index.html' : url.pathname.slice(1));
      throw new LabError(404, 'not_found');
    } catch (error) {
      if (!res.headersSent) json(res, error.status || 500, { error: error.code || 'internal_error' });
      else res.destroy();
    }
  });
  server.requestTimeout = 20_000;
  server.headersTimeout = 10_000;
  const wss = new WebSocketServer({ noServer: true, maxPayload: 4 * 1024 * 1024, perMessageDeflate: false });
  server.on('upgrade', (req, socket, head) => {
    if (viewer || req.url !== '/desktop/websockify' || !trustedHost(req, allowedHosts) || !canAccess(req) || !sameOrigin(req)) {
      socket.end('HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n'); return;
    }
    wss.handleUpgrade(req, socket, head, client => {
      const upstream = new WebSocket('ws://127.0.0.1:7901', client.protocol ? [client.protocol] : [], { perMessageDeflate: false });
      const pending = [];
      let pendingBytes = 0;
      const close = () => { client.terminate(); upstream.terminate(); };
      upstream.on('error', close); client.on('error', close);
      client.on('close', () => upstream.close()); upstream.on('close', () => client.close());
      client.on('message', (data, binary) => {
        if (upstream.readyState === WebSocket.OPEN) {
          if (upstream.bufferedAmount > 8 * 1024 * 1024) close();
          else upstream.send(data, { binary });
        } else {
          pendingBytes += data.length;
          if (pendingBytes > 4 * 1024 * 1024) close(); else pending.push([data, binary]);
        }
      });
      upstream.on('open', () => { for (const [data, binary] of pending) upstream.send(data, { binary }); pending.length = 0; });
      upstream.on('message', (data, binary) => {
        if (client.readyState === WebSocket.OPEN) {
          if (client.bufferedAmount > 8 * 1024 * 1024) close(); else client.send(data, { binary });
        }
      });
    });
  });
  server.on('close', () => { for (const client of wss.clients) client.terminate(); wss.close(); });
  return server;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const jobs=new Jobs({dataDir:process.env.DATA_DIR || '/data',concurrency:Number(process.env.AUTH_CONCURRENCY || 2)});
  const allowedHosts=new Set((process.env.ALLOWED_HOSTS || '*').split(',').map(x=>x.trim().toLowerCase()).filter(Boolean));
  allowedHosts.add('localhost');allowedHosts.add('127.0.0.1');
  const server=createServer({password:process.env.SERVER_KEY,authMode:process.env.SERVER_AUTH || 'password',jobs,allowedHosts,novncDir:process.env.NOVNC_DIR});
  let stopping=false,initialization;
  async function stop(){if(stopping)return;stopping=true;server.close();await initialization?.catch(()=>{});await jobs.close();process.exit(0);}
  process.on('SIGTERM',stop);process.on('SIGINT',stop);
  server.listen(Number(process.env.PORT || 9870),process.env.HOST || '0.0.0.0');
  try {initialization=jobs.init();await initialization;console.log('Rabbit Auth Server 0.3.9 ready (URL-only, concurrency '+jobs.concurrency+').');}
  catch {console.error('Browser startup failed. Check sandbox support and container logs; no site was opened.');await jobs.close();server.close();process.exitCode=1;}
}
