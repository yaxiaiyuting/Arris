import assert from 'node:assert/strict';
import worker, { Room, Directory } from '../../../../relay/src/index.mjs';
import { DL_SIZE } from '../../../../relay/src/dl-manifest.mjs';

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
let r = await get(apk, { range: 'bytes=-3' });
assert.equal(r.status, 206);
assert.equal(r.headers.get('content-range'), `bytes ${data.length - 3}-${data.length - 1}/${data.length}`);
assert.deepEqual([...new Uint8Array(await r.arrayBuffer())], [17, 18, 19]);
assert.equal(r.headers.get('cache-control'), 'public, max-age=120');
r = await get(apk, { range: 'bytes=1-2', 'if-range': '"old"' });
assert.equal(r.status, 200);
assert.equal((await r.arrayBuffer()).byteLength, data.length);
assert.equal((await get(apk, { 'if-none-match': '"new"' })).status, 304);
assert.equal((await get(apk, { range: 'bytes=1-2', 'if-none-match': '"new"' })).status, 206);
weak = true;
assert.equal((await get(apk + '?weak', { range: 'bytes=1-2', 'if-range': 'W/"new"' })).status, 206);
weak = false;
assert.equal((await get('/assets/after-the-rain.mp3', { range: 'bytes=1-2', 'if-range': '"old"' })).status, 206);
assert.equal((await get('/assets/after-the-rain.mp3', { range: 'bytes=1-2', 'if-none-match': '"new"' })).status, 206);
console.log('I05: APK suffix, rolling cache and stale strong If-Range controls pass; matching If-None-Match plus Range and weak If-Range still give 206.');
console.log('I05: MP3 still returns 206 for stale If-Range and matching If-None-Match.');

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
  const join = role => room.fetch(new Request('https://audit.invalid/room/654321?role=' + role));
  await join('host');
  await join('member');
  assert.equal((await list())[0].host !== undefined, true);
  await room.webSocketClose(sockets[0], 1000, 'leave');
  assert.equal(sockets[1].closed, true);
  assert.deepEqual(await list(), []);
  await join('member');
  const late = sockets.at(-1);
  assert.equal(late.closed, false);
  assert.equal(late.received[0].reason, 'no-host');
  assert.equal((await list())[0].host, '');
  await room.webSocketMessage(late, JSON.stringify({ t: 'ping', t0: 1 }));
  assert.equal((await list())[0].roomId, '654321');
  console.log('I12: host leave closes existing member and removes directory entry, but member rejoin publishes hostless room again.');
} finally {
  globalThis.Response = OriginalResponse;
  if (OriginalPair === undefined) delete globalThis.WebSocketPair;
  else globalThis.WebSocketPair = OriginalPair;
}
