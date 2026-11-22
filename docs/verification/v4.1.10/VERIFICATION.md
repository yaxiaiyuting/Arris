# Arris 4.1.10-gpl 发布验收

2026-11-22。Android 10 及以上，versionCode 243，versionName `4.1.10-gpl`。

- 源码：`refactor/arris-source`，构建提交 `4500378`（自 v4.1.8 起 11 个提交，含未发布的 4.1.9 网络修复）。
- APK 10,818,924 字节，SHA-256 `a561a5569c120f8c08d5437bfb603855b97387d1c7a94ad72b22761e4803bb46`。
- 签名：v2 通过，证书 `e62eca39…bbec82`（与 4.1.0–4.1.8 同一把）。
- 制品自证：从 `4500378` 重新 `assembleRelease` 出的 `app-release.apk` 与 `dist/` 内的包 **SHA-256 完全一致**；
  `aapt2 dump badging` = `versionCode='243' versionName='4.1.10-gpl'`，minSdk 29 / targetSdk 36。
- 装机自证：手机（PLC110）上 pull 回的 `base.apk` 与本包同哈希。

## 内容

- 取链连接失败后换地址族重试（3 分钟 IPv6 优先窗口）+ 两族都失败后 3 分钟冷却、不再来回翻面
- 地址族健康记忆（按 host 记成功/失败，`failover=ok/fail` 可统计回退胜率）
- 同一 `(songId, level)` 的取链在飞合并（单飞 + 发起方代管 + 等待方接管）
- `REFUSED_STREAM` 按流级重试；能力探测超时不再取消在飞请求；见过复位的 host 5 分钟走 h1
- 复用连接上的流级复位换新连接重试一次；`reused=` 改为粘性记录
- `NcrustNetDiag` 失败行给出 host / 解析出的全部地址 / 异常（release 可见，限流 5s/每 host、5 分钟 20 条）

## 测试

全量 JVM：**3758 例**，3755 通过、12 跳过、**3 条失败**。三条失败全部是 `Qq*ProbeTest` 里打真实 QQ 端点的
live 探针（`javax.net.ssl.SSLHandshakeException: Remote host terminated the handshake`），与本轮改动无关：

- 本机出口对 `y.qq.com:443` 的 TLS 握手会被重置，`curl https://y.qq.com/` 直接复现
  `error:0A000126:SSL routines::unexpected eof while reading`（同一时刻 `u.y.qq.com:443` 正常，`openssl s_client` 也能握手成功）；
- 每次跑失败的条目还不一样：首轮失败 `QqLiveProbeTest` 两条 + `QqScanLoginProbeTest` 一条，
  单独重跑这两个类时换成另外三条（`CreateQRCode 返回 PNG…`、`匿名能拿到歌词…`、`免费曲目匿名能取到可播放 URL…`）。

## 真机证据（PLC110 / Android 17 / ColorOS 16）

- 坏窗口（`interface3.music.163.com` 的 A 记录 SYN 黑洞）：
  修前 `elapsed=15001ms → failure=NETWORK`、播放被按停；
  修后同窗口 `conn=3271ms tls=72ms → level=jymaster ok in 10045ms`；正常网络 `ok in 1259ms`。
- 两族都失败现场（00:42–00:43 连续三首）：修前每首歌 2×8s；修后每首最多一次 8s，3 分钟冷却内不再翻面。
- 请求去重：3 分钟窗口内 `REFUSED_STREAM`/`CANCEL` 日志 3 → 1，每首歌请求数 2.0 → 1.67。
- 地址族回退胜率（v4 时通时断窗口）：**2/4**。
- 装 243 后同一坏窗口取链成功，`media_session state=PLAYING`。

## 未做 / 未证实

- 「熄屏期间复用连接被对端静默关闭」这一假设**被证伪**：整段会话 245 次失败全是新建连接（`reused=false`），
  8 次 `reused=true` 全是流级复位，没有一次「复用 + SocketException」。因此没有加 `Connection: close` 之类的东西。
- v4 长期黑洞时仍要每首歌付一次 8 秒（冷却只把它限制成「每 3 分钟翻一次面」）；要更快只能把连接超时从 8s 压到 3–4s，
  会影响慢网首播，本版未动。
- `clientlog.music.163.com` 在该网络 `UnknownHostException`（播放上报域名不可解析），与取链无关，未处理。
- `NetworkWaitBackoff` 的退避窗口（2s→30s，10 分钟）与依赖版本本版未改。
