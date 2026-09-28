# v3.2.4 探针摘要（PROBE-SUMMARY）

> 顺序：**探针 → 实现 → 测试 → 发布**。两个探针的完整版在
> [`probe-bili-playback.md`](probe-bili-playback.md) 与
> [`probe-waveform-jitter-hfr.md`](probe-waveform-jitter-hfr.md)，
> 原始证据在 [`evidence/`](evidence/) 与 [`verification/`](verification/)。

## P0 · B 站歌曲无法播放

| 项 | 结论 |
|---|---|
| **根因** | **媒体流请求的 `User-Agent` 不合格**：B 站媒体 CDN（openresty）对 UA 做**子串黑名单**（`android` / `dalvik` / `curl` / `python` / `vlc` …，大小写不敏感）且**要求 UA 存在**；App 走 media3 的默认路径（`DefaultDataSource.Factory(Context)` 造的是裸 `DefaultHttpDataSource.Factory()`，`userAgent == null`），实际发出去的是 `Dalvik/2.1.0 (Linux; U; Android 13; …)` —— 同时命中 `dalvik` 与 `android` ⇒ **403** |
| 真机证据 | 生产数据源链打真实直链：`as_is → InvalidResponseCodeException http=403`；**只加一个 UA**（其余逐字不变）`→ OK opened=100000 firstRead=4096`；再去掉 Referer `→ 403` |
| 头俘获证据 | 本地 `ServerSocket` 俘获生产链实际写的头：`Range` / `Accept-Encoding: identity` / `User-Agent: Dalvik/2.1.0 (…Android 13…)`，**无 Referer**（非 B 站 host） |
| 次要缺口 | header 注入按 **host 白名单**（`bilivideo.com` 等），PCDN 第三方域名（v3.1.0 实测抓到 `b-…edge.mountaintoys.cn`）**漏加** ⇒ 一并收口为「白名单 ∪ 取链见过的 host」 |
| **不是**根因 | TTL（`deadline` 优先，实测 7166s vs `timeout` 10800s，现有处理正确）、取链参数（`songid`/`quality`/`privilege`/`mid`/`platform` 与实测逐字一致）、音频/容器格式（AAC-in-MP4 / fMP4 DASH，ExoPlayer 配置已匹配）、登录态（匿名即可 320K） |
| v3.1.0 为什么漏了 | 它的 A/B 里**手工写死 `User-Agent: ExoPlayerLib/1.5.0`**（合格 UA），于是「UA」这个变量被固定住了，只剩「Referer」在变 ⇒ 得出「Referer 是唯一闸门」。真实记录就在 `docs/verification/v3.1.0/verification/EVIDENCE-bili-probe.md:26-32`（`A_with_referer -> 206` / `D_production -> 403`） |
| 修复方向 | ①`BiliCdn` 增加合格 UA 常量并纳入 `requestHeaders()`；②按 URI 选择**设了该 UA 的 `DefaultHttpDataSource.Factory`**（media3 的 `userAgent` 字段是 `final` 且**最后写入**，会覆盖 `DataSpec` 里的 UA，所以只能从工厂设）；③判据扩成「白名单 ∪ 取链见过的 host」；④其余音源逐字节不变 |

## P1 · 新设备（高刷新率）音频条抖动

| 项 | 结论 |
|---|---|
| **根因** | `MotionFrameClock` 的**帧闸门把重绘预算写死成 16ms**（`VISUALIZER_FRAME_INTERVAL_FAST_MS`），从不读设备刷新率。闸门判据 `now - 上次推进 >= 16ms` 只能落在**整数个 vsync** 上：60Hz 面板 1 个 vsync = 16.667ms ⇒ **每帧都推进（无量化）**；120Hz 面板 2 个 vsync = 16.667ms ⇒ **两帧一推进，余量只有 0.667ms**，frame pacing 一抖就变成 3 个 vsync（25ms） |
| 仿真证据（±0.6ms 抖动、不掉帧、隔离闸门量化） | Δφ_CV：60Hz **0.021** → 120Hz **0.125**（6 倍）；120Hz `dt_p99 = 24.63ms`（中位 16.76）；放大到 ±1.5ms 时 **30.3% 的推进多跨一个 vsync** |
| 推进率 | 90Hz 面板只跑 45fps（50%）、144Hz 只跑 48fps（33%）、120Hz 跑 57fps（48%）—— 都不是面板刷新率 |
| 设备证据 | 本环境**没有高刷真机**（模拟器 `supportedModes` 只有 60.000004Hz 一个模式）⇒「高刷真机抖动复现」**未验证**，代偿是四档（60/90/120/144）确定性单测 + release 包在 60Hz 上的不回归 |
| **不是**根因 | 插值（按真实 `dt` 推进，高刷只会更细）、音频缓冲粒度（实测 100.00ms / 11Hz，插值层就是为它而设）、渲染层重复重组（全仓只有一条 `withFrameNanos` 帧循环，波形是 draw 阶段读 generation） |
| 修复方向 | ①**显式读设备刷新率**（`Display.getRefreshRate()` + `DisplayManager.DisplayListener` 跟随切换）；②预算 = 一个刷新周期 ⇒ **每帧都推进**（`withFrameNanos` 本身就是 vsync 对齐的节拍器）；③只有低内存设备保留 33ms 上限；④把闸门抽成纯类，四档刷新率可单测 |

## 两个探针共同的方法论收获（写进 AGENTS.md 铁律 32/33）

1. **A/B 对照里混进的第二个变量，会让结论整体反过来。** v3.1.0 的「Referer 是唯一闸门」就是
   在一个两变量空间里只变了一个轴，而另一个轴被手工钉在「合格」上。
   ⇒ 铁律 32：**音频流请求必须携带平台所需的 header（Referer / User-Agent），不得假设默认 header 可用。**
2. **「帧率」不是一个可以拍脑袋的常量。** 16ms 在 60Hz 上恰好是「每帧」，在 120Hz 上就变成
   「每两帧、且随时会变三帧」——同一个数字在两种面板上语义完全不同。
   ⇒ 铁律 33：**帧率适配必须显式处理设备刷新率（60 / 90 / 120Hz），不得假设固定帧率。**

## 阻塞与未验证（诚实清单）

| # | 项 | 状态 |
|---|---|---|
| 1 | 120Hz / 90Hz **真机**上的抖动复现与修复后观感 | **未验证**（环境无高刷设备） |
| 2 | S6（SM-G9209 / Android 7.0）真机回归 | **未验证**（环境无该设备；用 60Hz 模拟器 + 四档单测代偿） |
| 3 | B 站**登录态**播放 | **未验证**（无 B 站账号；B 站不在登录音源列表里） |
| 4 | PCDN 第三方域名实流 | 未在本次流量里出现；用单测钉住判据 |
| 5 | 播放中跨过 URL `deadline`（>2h） | 未实测 |
