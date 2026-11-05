function weakEqual(left, right) {
  return left.replace(/^W\//, '') === right.replace(/^W\//, '');
}

function matchesIfRange(value, headers) {
  const validator = value.trim();
  if (validator.startsWith('"')) return validator === headers.get('etag');
  const modified = headers.get('last-modified');
  const responseTime = Date.parse(headers.get('date') || '');
  return validator === modified && responseTime - Date.parse(modified) >= 60_000;
}

function sliceStream(body, start, end) {
  const reader = body.getReader();
  let offset = 0;
  return new ReadableStream({
    async pull(controller) {
      while (true) {
        const { value, done } = await reader.read();
        if (done) {
          controller.error(new Error('Asset ended before the requested range'));
          return;
        }
        const from = Math.max(0, start - offset);
        const to = Math.min(value.byteLength, end + 1 - offset);
        offset += value.byteLength;
        if (to > from) controller.enqueue(value.subarray(from, to));
        if (offset > end) {
          controller.close();
          await reader.cancel();
          return;
        }
        if (to > from) return;
      }
    },
    cancel(reason) { return reader.cancel(reason); },
  });
}

export async function serveRangedAsset(request, assets, assetUrl, { size, cacheControl } = {}) {
  if (request.method !== 'GET' && request.method !== 'HEAD') {
    return new Response(null, { status: 405, headers: { allow: 'GET, HEAD' } });
  }
  // Fetch one representation. The validator and range bytes must come from the same response.
  const asset = await assets.fetch(new Request(assetUrl, { method: request.method }));
  if (asset.status !== 200) return asset;

  const headers = new Headers(asset.headers);
  headers.set('accept-ranges', 'bytes');
  if (cacheControl) headers.set('cache-control', cacheControl);
  if (request.method === 'HEAD' && size != null && !headers.has('content-length')) {
    headers.set('content-length', String(size));
  }
  const etag = headers.get('etag');
  const noneMatch = request.headers.get('if-none-match');
  const tags = noneMatch?.match(/(?:W\/)?"[^"]*"|\*/g);
  if (tags?.some(tag => tag === '*' || (etag && weakEqual(tag, etag)))) {
    await asset.body?.cancel();
    headers.delete('content-length');
    return new Response(null, { status: 304, headers });
  }

  const range = request.headers.get('range');
  const ifRange = request.headers.get('if-range');
  const full = () => new Response(asset.body, { status: 200, headers });
  if (request.method === 'HEAD' || !range || !asset.body || headers.has('content-encoding') ||
      (ifRange && !matchesIfRange(ifRange, headers))) return full();
  const bounds = /^bytes=(\d*)-(\d*)$/i.exec(range.trim());
  if (!bounds || (!bounds[1] && !bounds[2])) return full();

  const declaredLength = Number(headers.get('content-length'));
  let total = declaredLength > 0 ? declaredLength : size;
  let bytes;
  if (total == null) {
    bytes = new Uint8Array(await asset.arrayBuffer());
    total = bytes.length;
  }
  const start = bounds[1] ? Number(bounds[1]) : Math.max(0, total - Number(bounds[2]));
  const end = bounds[1] && bounds[2] ? Math.min(Number(bounds[2]), total - 1) : total - 1;
  if (!Number.isSafeInteger(start) || start > end || start >= total) {
    if (!bytes) await asset.body.cancel();
    headers.set('content-range', `bytes */${total}`);
    headers.set('content-length', '0');
    return new Response(null, { status: 416, headers });
  }
  headers.set('content-range', `bytes ${start}-${end}/${total}`);
  headers.set('content-length', String(end - start + 1));
  return new Response(bytes ? bytes.subarray(start, end + 1) : sliceStream(asset.body, start, end), {
    status: 206, headers,
  });
}
