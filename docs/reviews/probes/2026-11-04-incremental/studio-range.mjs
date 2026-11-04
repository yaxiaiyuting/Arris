import assert from 'node:assert/strict';
import worker from '../../../../relay/src/index.mjs';

const representation = Uint8Array.from([10, 20, 30, 40, 50, 60]);
const observed = [];
const ASSETS = {
  async fetch(request) {
    observed.push(Object.fromEntries(request.headers));
    if (request.headers.get('if-none-match') === '"new"') {
      return new Response(null, { status: 304, headers: { etag: '"new"' } });
    }
    return new Response(representation, { headers: { etag: '"new"', 'content-type': 'audio/mpeg' } });
  },
};

const request = async (headers) => {
  const response = await worker.fetch(new Request('https://arris.test/assets/after-the-rain.mp3', { headers }), { ASSETS });
  return { status: response.status, etag: response.headers.get('etag'), bytes: [...new Uint8Array(await response.arrayBuffer())] };
};

assert.deepEqual(await request({ range: 'bytes=2-3', 'if-range': '"new"' }), { status: 206, etag: '"new"', bytes: [30, 40] });
const changed = await request({ range: 'bytes=2-3', 'if-range': '"old"' });
assert.deepEqual(changed, { status: 206, etag: '"new"', bytes: [30, 40] });
const notModified = await request({ range: 'bytes=2-3', 'if-none-match': '"new"' });
assert.equal(notModified.status, 206);
assert.deepEqual(observed, [{}, {}, {}]);
console.log('Control with matching ETag: 206. Defect: stale If-Range also 206 (expected full 200).');
console.log('Defect: Range plus matching If-None-Match also 206 (expected 304).');
console.log('Worker route executed with local ASSETS stub; no external network.');

const manifestTotal = 10384492;
const apk = new Uint8Array(manifestTotal);
apk.set([73, 74, 75], manifestTotal - 3);
const bindings = {
  ASSETS: {
    async fetch() {
      return new Response(apk, { headers: { etag: '"new-apk"', 'cache-control': 'no-store' } });
    },
  },
};
const apkRange = async (headers) => {
  const res = await worker.fetch(new Request('https://arris.test/dl/latest.apk', { headers }), bindings);
  return { status: res.status, headers: res.headers, bytes: new Uint8Array(await res.arrayBuffer()) };
};
const suffix = await apkRange({ range: 'bytes=-3' });
assert.equal(suffix.status, 206);
assert.deepEqual([...suffix.bytes], [0, 0, 0, 0]);
assert.equal(suffix.headers.get('content-range'), 'bytes 0-3/' + manifestTotal);
const staleApk = await apkRange({ range: 'bytes=0-3', 'if-range': '"old-apk"' });
assert.equal(staleApk.status, 206);
assert.equal(staleApk.headers.get('cache-control'), 'public, max-age=31536000, immutable');
console.log('Defect: APK fallback maps bytes=-3 to the first four bytes, not the final three.');
console.log('Defect: rolling APK alias gives stale If-Range a 206, and replaces upstream no-store with immutable.');
