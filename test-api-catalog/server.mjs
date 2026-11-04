import { createServer, request as httpRequest } from 'node:http';
import { readFile } from 'node:fs/promises';
import { generateKeyPairSync, sign } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const port = Number(process.env.PORT || 8090);
const issuer = `http://127.0.0.1:${port}`;
const keyId = 'catalog-local-test-key';
const { privateKey, publicKey } = generateKeyPairSync('rsa', {
  modulusLength: 2048,
  publicExponent: 0x10001,
});
const publicJwk = { ...publicKey.export({ format: 'jwk' }), kid: keyId, use: 'sig', alg: 'RS256' };

const encode = (value) => Buffer.from(JSON.stringify(value)).toString('base64url');

function issueToken({ role, tenantId }) {
  const now = Math.floor(Date.now() / 1000);
  const email = `${role.toLowerCase()}-tenant${tenantId}@catalog.local.test`;
  const head = encode({ alg: 'RS256', typ: 'JWT', kid: keyId });
  const claims = encode({
    iss: issuer,
    sub: email,
    email,
    tenant_id: Number(tenantId),
    role,
    iat: now,
    exp: now + 3600,
  });
  const input = `${head}.${claims}`;
  const signature = sign('RSA-SHA256', Buffer.from(input), privateKey).toString('base64url');
  return `${input}.${signature}`;
}

async function readJson(req) {
  let body = '';
  for await (const chunk of req) {
    body += chunk;
    if (body.length > 8192) throw new Error('Request body too large');
  }
  return JSON.parse(body || '{}');
}

function sendJson(res, status, value) {
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' });
  res.end(JSON.stringify(value, null, 2));
}

const server = createServer(async (req, res) => {
  const url = new URL(req.url, issuer);

  if (req.method === 'GET' && url.pathname === '/') {
    res.writeHead(200, { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' });
    res.end(await readFile(join(here, 'index.html')));
    return;
  }

  if (req.method === 'GET' && url.pathname === '/.well-known/jwks.json') {
    sendJson(res, 200, { keys: [publicJwk] });
    return;
  }

  if (req.method === 'POST' && url.pathname === '/dev/token') {
    try {
      const { role, tenantId } = await readJson(req);
      if (!['USER', 'ADMIN'].includes(role) || ![1, 2].includes(Number(tenantId))) {
        sendJson(res, 400, { error: 'Choose USER or ADMIN and tenant 1 or 2.' });
        return;
      }
      sendJson(res, 200, { accessToken: issueToken({ role, tenantId }), expiresIn: 3600, issuer });
    } catch {
      sendJson(res, 400, { error: 'Invalid token request.' });
    }
    return;
  }

  if (url.pathname === '/api' || url.pathname.startsWith('/api/')) {
    const targetPath = `${url.pathname.slice(4) || '/'}${url.search}`;
    const upstream = httpRequest({
      hostname: '127.0.0.1',
      port: 8081,
      method: req.method,
      path: targetPath,
      headers: { ...req.headers, host: 'localhost:8081' },
    }, (upstreamResponse) => {
      res.writeHead(upstreamResponse.statusCode || 502, upstreamResponse.headers);
      upstreamResponse.pipe(res);
    });
    upstream.on('error', () => sendJson(res, 502, { error: 'Catalog service is not reachable on port 8081.' }));
    req.pipe(upstream);
    return;
  }

  res.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' });
  res.end('Not found');
});

server.listen(port, '127.0.0.1', () => {
  console.log(`Catalog local test page: ${issuer}`);
  console.log(`Temporary JWKS issuer: ${issuer}`);
  console.log('This harness is for local API checks only; it is not the IAM service.');
});
