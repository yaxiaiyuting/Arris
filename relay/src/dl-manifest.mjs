// 由 tools/sync-dl-manifest.mjs 生成；改动了 public/dl/*.apk 之后重跑一次。
export const HOSTED_APK = [
  "Arris-v3.7.14-gpl.apk",
  "Arris-v3.7.15-gpl.apk",
  "Arris-v3.8.2-gpl.apk",
  "Arris-v3.8.3-gpl.apk",
  "Arris-v3.8.4-gpl.apk",
  "latest.apk",
  "lts-latest.apk"
];
/** 文件名 → 字节数。Worker 里实现 Range 时需要总长，而 ASSETS 的响应既没有
 * content-length 也不认 Range，只能靠部署时算好。 */
export const DL_SIZE = {
  "Arris-v3.7.14-gpl.apk": 12121852,
  "Arris-v3.7.15-gpl.apk": 12121852,
  "Arris-v3.8.2-gpl.apk": 16052392,
  "Arris-v3.8.3-gpl.apk": 16052616,
  "Arris-v3.8.4-gpl.apk": 16052616,
  "latest.apk": 16052616,
  "lts-latest.apk": 12121852
};
