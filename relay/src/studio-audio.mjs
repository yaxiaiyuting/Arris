/**
 * The static asset binding may answer Range with 200. Firefox needs reliable
 * byte ranges to seek this MP3 before the whole file is buffered.
 */
export async function serveStudioAudio(request, assets) {
  const range = request.headers.get("range");
  const shouldSlice = request.method === "GET" && range !== null;
  const assetRequest = new Request(request.url, {
    method: request.method,
    headers: shouldSlice ? undefined : request.headers,
  });
  const asset = await assets.fetch(assetRequest);
  if (!asset.ok) return asset;
  const headers = new Headers(asset.headers);
  headers.set("accept-ranges", "bytes");
  if (!shouldSlice) return new Response(asset.body, { status: asset.status, headers });

  // The one supported asset is about half a MiB. Use its actual length so a
  // future re-render cannot leave an out-of-date size constant in the Worker.
  const file = await asset.arrayBuffer();
  const size = file.byteLength;
  const bounds = /^bytes=(\d*)-(\d*)$/i.exec(range.trim());
  let start, end;
  if (bounds && (bounds[1] || bounds[2])) {
    if (!bounds[1]) {
      const suffix = Number(bounds[2]);
      start = Math.max(0, size - suffix);
      end = size - 1;
    } else {
      start = Number(bounds[1]);
      end = bounds[2] ? Math.min(Number(bounds[2]), size - 1) : size - 1;
    }
  }
  if (!Number.isSafeInteger(start) || start >= size || start > end) {
    headers.delete("content-encoding");
    headers.set("content-range", `bytes */${size}`);
    headers.set("content-length", "0");
    return new Response(null, { status: 416, headers });
  }
  headers.delete("content-encoding");
  headers.set("content-range", `bytes ${start}-${end}/${size}`);
  headers.set("content-length", String(end - start + 1));
  return new Response(file.slice(start, end + 1), { status: 206, headers });
}
