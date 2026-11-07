// 由 tools/sync-dl-manifest.mjs 生成；改动了 public/dl/*.apk 之后重跑一次。
export const HOSTED_APK = [
  "Arris-v3.7.27-gpl.apk",
  "Arris-v3.7.29-lts-gpl.apk",
  "Arris-v3.7.30-lts-gpl.apk",
  "Arris-v3.7.31-lts-gpl.apk",
  "Arris-v3.7.32-lts-gpl.apk",
  "Arris-v3.7.33-lts-gpl.apk",
  "Arris-v3.7.34-gpl.apk",
  "Arris-v4.0.10-gpl.apk",
  "Arris-v4.0.11-gpl.apk",
  "Arris-v4.0.12-gpl.apk",
  "Arris-v4.0.13-gpl.apk",
  "Arris-v4.0.14-gpl.apk",
  "Arris-v4.0.15-gpl.apk",
  "Arris-v4.0.3-gpl.apk",
  "Arris-v4.0.8-gpl.apk",
  "latest.apk",
  "lts-latest.apk"
];
/** 文件名 → 字节数。Worker 里实现 Range 时需要总长，而 ASSETS 的响应既没有
 * content-length 也不认 Range，只能靠部署时算好。 */
export const DL_SIZE = {
  "Arris-v3.7.27-gpl.apk": 12270321,
  "Arris-v3.7.29-lts-gpl.apk": 12253937,
  "Arris-v3.7.30-lts-gpl.apk": 12286709,
  "Arris-v3.7.31-lts-gpl.apk": 12286705,
  "Arris-v3.7.32-lts-gpl.apk": 12303089,
  "Arris-v3.7.33-lts-gpl.apk": 12303089,
  "Arris-v3.7.34-gpl.apk": 12303093,
  "Arris-v4.0.10-gpl.apk": 10490856,
  "Arris-v4.0.11-gpl.apk": 10523624,
  "Arris-v4.0.12-gpl.apk": 10523624,
  "Arris-v4.0.13-gpl.apk": 10523624,
  "Arris-v4.0.14-gpl.apk": 10523624,
  "Arris-v4.0.15-gpl.apk": 10572776,
  "Arris-v4.0.3-gpl.apk": 10401136,
  "Arris-v4.0.8-gpl.apk": 10490852,
  "latest.apk": 10572776,
  "lts-latest.apk": 12303093
};
