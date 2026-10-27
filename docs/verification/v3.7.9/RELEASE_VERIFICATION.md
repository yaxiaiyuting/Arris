# Arris v3.7.9-gpl 验收记录

- 版本：`3.7.9-gpl` / `versionCode 95`（三源交叉校验：最大已用 tag `v3.7.8-gpl`=94 ⇒ 95）
- 构建日期：2026-10-27（Asia/Shanghai）
- 修复清单与已知未修：[`RELEASE_NOTES.md`](RELEASE_NOTES.md)

## 构建与测试

| 项 | 结果 |
|---|---|
| `:app:testDebugUnitTest` | **3254 通过 / 0 失败**（284 个测试类） |
| `:app:lintDebug` | **0 error / 60 warning**（17 条在基线内） |
| `:app:assembleRelease` | 成功 |
| `:app:assembleDebug` | 成功 |
| aapt2 badging | `versionCode='95' versionName='3.7.9-gpl'` |
| apksigner（V2） | 证书 SHA-256 `e62eca39b7e5e36bc9a05f83688dcfecc11fc5589a4097f7dbbaaa7ddfbbec82` |
| `zipalign -c -P 16 4` | 通过 |
| release dex 探针泄漏 | `ProtocolProbe` 条目 **0** |

## 产物

| 文件 | 大小 | SHA-256 |
|---|---:|---|
| `Ncrust-v3.7.9-gpl-release.apk` | 12,090,660 | `938ced037d8349515fa088c5fc4a1e0a853aaf0512062e285b91760aad2c5a12` |
| `Ncrust-v3.7.9-gpl-debug.apk` | 33,9xx,xxx | 见 `SHA256SUMS` |
| `mapping-v3.7.9-gpl.txt.gz` | 5,8xx,xxx | 见 `SHA256SUMS` |

## 本轮执行方式

6 个并行子代理按**互不重叠的文件域**分工（云写入 / 本地同步 / 播放器队列 / 跨源搜索 / 更新与杂项 UI / QQ 与播放器 UI），
每个子代理都被要求「先验证再修，误报必须给出文件:行 + 原因」。集成阶段由主代理统一处理跨文件依赖：
`MainActivity`（H09 调用方、K01 播放意图代次、K02 队列恢复闸门）、`SearchScreen`（E05）、
`SongSourceExt`（H11）、`PlayerViewModel`（J06）、`OfflineAudioCache`（E01）、`SessionMonitor`（J03）、
`Quantizer`（J15 前半）、`LyricPosterRenderer`（K03）、`CustomBackgroundLayer`（K07）、`QqIdentity`（H07）、
`PlayerCardTray`（E09）、音质控件（K09）、8 个 locale（J11）、`AppWarmup`（H15 残余）、
`ListItemAppear`（J17）、`SearchViewModel.clearQuery`（J09）、`StatsStore`（H19）、`LikedPlaylistPick`（H24）、
`QrPair*`（H23）、`retrofitClient.eapiPostOsx`（H03 基础）。

## 未验证项

- 未做真机验证。改动集中在数据层、协议层与状态机；Compose/设备相关的后果（K03 版面、K04 手势、E09 命中区、
  E05 分页滚动）由源码推导 + JVM 纯逻辑用例固定，未经肉眼验收。
- J15 后半（量化器剪枝）**有意保持与官方 MCU 端口一致**：`QuantizerTest`/`ScoreTest` 是 parity 守卫，
  偏离它需要产品决策，本轮只修了 Int 平方溢出那半边。
- H13（QQ 单曲详情锚点缺 `songmid`）需要改导航载荷形状，本轮未做，见 RELEASE_NOTES 的「已知未修」。
