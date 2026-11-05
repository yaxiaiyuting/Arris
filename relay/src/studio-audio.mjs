import { serveRangedAsset } from "./asset-range.mjs";

export function serveStudioAudio(request, assets) {
  return serveRangedAsset(request, assets, request.url);
}
