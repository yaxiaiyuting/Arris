# Arris 3.7.38-gpl（LTS）发布验收

2026-11-20。Android 7 及以上，versionCode 231，versionName `3.7.38-gpl`。

- 源码：`lts/3.7-audio-motion`，构建提交 `73db1c1`（自 3.7.37 起 9 个提交）。
- APK 12,352,233 字节，SHA-256 `0b199665e2cf5fcddec1a16293fd5fdf9fd059d0b9cc95d89dc945066f96e1f4`。
- 签名：v2 通过，证书 `e62eca39…bbec82`（与主线同一把）。
- 全量 JVM：3465 项 / 0 失败 / 4 跳过（既有 `assumeTrue` 探针守卫）；`assembleRelease` 成功。

## 内容（回移主线 4.x）

- 云端与本地歌单长按多选（`PlaylistSelection` 状态机 + 顶部选择条 + 行尾「⋯」；非自建 → 下一首播放，本地 → 从歌单移除）
- 歌单封面：第一首歌专辑图优先，空歌单用名字占位
- 下拉刷新修复：纯跟手指示器 + 底板、刷新中不累计并封顶、加载结束清标志；本地歌单页移除下拉刷新
- 离线可播闸门：失败标记跟随 URL、`onIsPlayingChanged` 成真解除、换号清空
- 生日祝福视觉重构、WebView 去石台抬升、QQ 扫码可靠性（均与主线同源）

## 刻意未做

- `cache/OfflineCacheDownload.kt`（LTS 无下载入口，会成死代码）
- `OfflineUrlStore.keysBySong` 与新 `recallRecord` 签名（与 LTS 的 `preferredKeys` 召回顺序冲突）
- 一起听 / 听歌识曲：属于整块功能线（`listen/` 2518 行、`recognize/` 1985 行），本轮不移植

## 版本号说明

与主线共用包名 `com.takahashirinta.ncrust`。LTS 231 < 主线 233：从主线切到 LTS 需要 `adb install -d` 或先卸载（数据会丢）；
从 3.7.37（225）升上来是正常升级。
