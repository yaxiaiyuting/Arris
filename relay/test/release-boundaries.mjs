import assert from 'node:assert/strict';
import worker, { Room, Directory } from '../src/index.mjs';
import { DL_SIZE } from '../src/dl-manifest.mjs';

const data = new Uint8Array(DL_SIZE['latest.apk']);
data.set([17, 18, 19], data.length - 3);
const mp3 = Uint8Array.of(11, 22, 33, 44, 55);
let weak = false;
const bindings = { ASSETS: { async fetch(request) {
  const body = new URL(request.url).pathname.endsWith('.mp3') ? mp3 : data;
  const etag = weak ? 'W/"new"' : '"new"';
  if (request.headers.get('if-none-match') === etag) return new Response(null, { status: 304, headers: { etag } });
  return new Response(request.method === 'HEAD' ? null : body, { headers: { etag, 'cache-control': 'no-store' } });
} } };
const get = (path, headers) => worker.fetch(new Request('https://audit.invalid' + path, { headers }), bindings);
const apk = '/get/latest.apk';
const head = await worker.fetch(new Request('https://audit.invalid' + apk, { method: 'HEAD' }), bindings);
assert.equal(head.headers.get('content-length'), String(data.length));
assert.equal(await head.text(), '');
let r = await get(apk, { range: 'bytes=-3' });
assert.equal(r.status, 206);
assert.equal(r.headers.get('content-range'), `bytes ${data.length - 3}-${data.length - 1}/${data.length}`);
assert.deepEqual([...new Uint8Array(await r.arrayBuffer())], [17, 18, 19]);
assert.equal(r.headers.get('cache-control'), 'no-store');
r = await get(apk, { range: 'bytes=1-2', 'if-range': '"old"' });
assert.equal(r.status, 200);
assert.equal((await r.arrayBuffer()).byteLength, data.length);
assert.equal((await get(apk, { 'if-none-match': '"new"' })).status, 304);
assert.equal((await get(apk, { range: 'bytes=1-2', 'if-none-match': 'W/"new"' })).status, 304);
weak = true;
assert.equal((await get(apk + '?weak', { range: 'bytes=1-2', 'if-range': 'W/"new"' })).status, 200);
weak = false;
assert.equal((await get('/assets/after-the-rain.mp3', { range: 'bytes=1-2', 'if-range': '"old"' })).status, 200);
assert.equal((await get('/assets/after-the-rain.mp3', { range: 'bytes=1-2', 'if-none-match': '"new"' })).status, 304);
console.log('I05: APK and MP3 conditional ranges and rolling-cache behavior passed.');

const store = () => {
  const map = new Map();
  let alarm;
  return {
    async get(key) { return structuredClone(map.get(key)); },
    async put(key, value) { map.set(key, structuredClone(value)); },
    async getAlarm() { return alarm; },
    async setAlarm(value) { alarm = value; },
  };
};
const directory = new Directory({ storage: store() }, {});
const dirs = { idFromName: id => id, get: () => ({
  fetch: (url, options) => directory.fetch(new Request(url, options)),
}) };
const list = async () => (await (await directory.fetch(new Request('https://directory/list'))).json()).rooms;
const sockets = [];
const room = new Room({ storage: store(), acceptWebSocket(ws) { sockets.push(ws); },
  getWebSockets() { return sockets.filter(ws => !ws.closed); } }, { DIRECTORY: dirs });
const socket = () => ({
  received: [], attachment: null, closed: false,
  send(message) { this.received.push(JSON.parse(message)); },
  close() { this.closed = true; },
  serializeAttachment(value) { this.attachment = value; },
  deserializeAttachment() { return this.attachment; },
});
const OriginalResponse = globalThis.Response;
const OriginalPair = globalThis.WebSocketPair;
// Node has no Cloudflare WebSocket upgrade response; substitute only the platform adapters.
globalThis.Response = class extends OriginalResponse {
  constructor(body, options) {
    if (options?.status === 101) return { status: 101, webSocket: options.webSocket };
    super(body, options);
  }
};
globalThis.WebSocketPair = class { constructor() { return { 0: socket(), 1: socket() }; } };
try {
  const secret = '1'.repeat(32);
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(secret));
  const roomId = [...new Uint8Array(digest).slice(0, 12)].map(n => n.toString(16).padStart(2, '0')).join('');
  const join = (role, credential) => room.fetch(new Request(
    'https://audit.invalid/room/' + roomId + '?role=' + role,
    { headers: credential ? { authorization: 'Bearer ' + credential } : {} },
  ));
  assert.equal((await join('member')).status, 404);
  assert.equal((await join('host', '2'.repeat(32))).status, 403);
  assert.equal((await join('host', secret)).status, 101);
  assert.equal((await join('host', secret)).status, 409);
  assert.equal((await join('member')).status, 101);
  assert.equal((await join('member')).status, 101);
  const [host, member, observer] = sockets;
  assert.equal((await list())[0].roomId, roomId);
  const send = (from, msg) => room.webSocketMessage(from, JSON.stringify(msg));
  observer.received.length = 0;
  await send(member, { t: 'closed', reason: 'forged' });
  await send(member, { t: 'snapshot', state: { roomId } });
  await send(member, { t: 'presence', members: 99 });
  assert.deepEqual(observer.received, []);
  await send(host, { t: 'snapshot', state: { roomId } });
  assert.equal(observer.received[0].t, 'snapshot');
  await room.webSocketClose(host, 1000, 'leave');
  assert.equal(member.closed, true);
  assert.equal(observer.closed, true);
  assert.deepEqual(await list(), []);
  assert.equal((await join('member')).status, 404);
  await send(member, { t: 'ping', t0: 1 });
  assert.deepEqual(await list(), []);
  console.log('I11/I12: verified host ownership, forged member messages, and hostless/closed room behavior.');
} finally {
  globalThis.Response = OriginalResponse;
  if (OriginalPair === undefined) delete globalThis.WebSocketPair;
  else globalThis.WebSocketPair = OriginalPair;
}
