# Arris 4.1.14-gpl 发布验收

2026-11-27。Android 10 及以上，versionCode 248，versionName `4.1.14-gpl`。

- 源码：`refactor/arris-source`，构建提交见 tag `v4.1.14-gpl`。
- APK 10,835,308 字节，SHA-256 `07ea76d8c3cd41bb49af11181aeb16702ebfc8853a97671b53c3d72fbeade4d8`。
- 签名：v2 通过，证书 `e62eca39…bbec82`（与 4.1.0–4.1.13 同一把）。
- 制品自证：`assembleRelease` 出的包与手机上已装测试包（248）**同哈希**。
- 全量 JVM：**3795 例 / 0 失败 / 6 跳过**（373 suites）。

## 内容

三处改动，全部来自 2026-11-27 真机现场（后台播放被网络超时打断）：

1. **网络恢复立刻重试**：服务订阅 `DefaultNetworkObserver.changes()`，路由 online 时清零复活退避。
2. **退避不再被「重推同一首」清零**：`updateTrackMetadata` 只在曲目身份（id 或音源）变化时清零。
3. **播放启停日志**：`playWhenReady` 打 reason 名称 + `suppression` + `state`；`suppression` 变化单独一条。

## 真机证据（PLC110 / Android 17 / ColorOS 16）

现场（23:43）：取链对 `interface3.music.163.com` 两次 8 秒超时（`NcrustHttpTiming total=800xms failed=InterruptedIOException`）→ 16 秒后 `quality pipeline gave up` → `pause_failed` 按停。复活机制随后重试 3 次（15s/30s/60s）均超时空手而归；期间 IPv6 地址 TCP 443 一直超时，IPv4 时通时断。

修复后验证：断网 → 切歌强制取链 → 触发 `pause_failed` → 恢复网络 →
`playWhenReady=true` 后 `level=jymaster ok in 1186ms`，**约 1.5 秒恢复播放**；`wifi_on=1` / `mobile_data=1` / `ping` 正常。

## 未做 / 未证实

- 「换歌时 1.7 秒空档」（`无缝播放` 默认关闭 ⇒ 不预取下一首）本版未改，仍待决定是否改成「只预取 URL、不预插播放项」。
- `interface3.music.163.com` 的 IPv6 地址在该网络长期不可达（TCP 443 超时），本版没动地址族策略：`AddressHealth` 已会把该 host 的记忆压到 IPv4。
- 播放启停日志仍是 `Log.d`，release 包可见（`logcat` 默认输出），随 16 MiB 环形缓冲一起用于排障。
