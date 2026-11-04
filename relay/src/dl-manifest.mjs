// 由 tools/sync-dl-manifest.mjs 生成；改动了 public/dl/*.apk 之后重跑一次。
export const HOSTED_APK = [
  "Arris-v3.7.25-lts-gpl.apk",
  "Arris-v4.0.2-gpl.apk",
  "latest.apk",
  "lts-latest.apk"
];
/** 文件名 → 字节数。Worker 里实现 Range 时需要总长，而 ASSETS 的响应既没有
 * content-length 也不认 Range，只能靠部署时算好。 */
export const DL_SIZE = {
  "Arris-v3.7.25-lts-gpl.apk": 12253961,
  "Arris-v4.0.2-gpl.apk": 10384492,
  "latest.apk": 10384492,
  "lts-latest.apk": 12253961
};
