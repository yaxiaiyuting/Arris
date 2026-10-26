# Arris v3.7.7-gpl 验收记录

日期：2026-10-26。构建源码 `4e9d763`，versionCode **93**。定号先带 fetch 运行三源脚本，最大值 92；另用 aapt2 核对 v3.7.6 发布目录 APK 确为 92。

## 修复与回归

1. **重试归属**：`PlaybackRetryScheduler` 合并网络/降档的待执行任务；捕获完整曲目载荷和播放代次，醒来先核对当前 owner。新播放、同歌重播、换档、暂停、停止撤销旧任务，系统媒体暂停通过执行层回调撤销。调度回归 8 项。
2. **收藏初始化与写盘**：读改写前在同一把锁内确保首次加载，返回缓存真源实例；flush 快照和写盘串行。冷写不会把未加载误当空库，首次专辑修改不再落到副本。
3. **末次网络异常**：有限循环重试，第二次 IOException 同样输出 NETWORK 并启用单调时钟冷却；取消不重放，业务拒绝不冒充网络故障。真实取链入口夹具回归 4 项。
4. **网易专辑完整性**：必须 HTTP 成功、code 200、具有数组；按 hasMore/满页继续有界翻页，失败或重复停滞页保留旧库，确认空表正常提交。刷新返回实际结果。
5. **QQ 附加列表**：按 50 条分页；附加失败合并保留同账号旧收藏和已拿到的新项，返回降级、以过期时间保存，下一次可重试。缺失数组不是成功空列表。收藏相关 Manager/API 集成回归共 14 项。

## 自动验证

- Gradle `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease --offline`：成功，构建耗时 2m53s。完整日志见 build-check.txt。
- **3,100 tests / 253 classes / 0 failures / 0 errors / 0 skipped**。新增 26 项。
- Lint：**0 Error / 0 Fatal / 60 Warning / 5 Information**，未扩大既有 baseline。
- 独立无窗口 API 33 AVD `arris-regression374`，仅本地静音 WAV：8 项检查通过，覆盖 QQ→网易云清载荷、旧播放 Intent、旧 owner 预载、应用手动下一首与系统 next/nextMediaItem/previous。检查后已关闭模拟器。
- 未连接真实账号、未做真机安装或播放验证；调度器行为由定向 JVM 测试覆盖，不能把模拟器既有 8 项检查描述成真实网络重试的端到端复现。

## 产物与可追溯性

- Release APK：12,072,652 B，SHA-256 `2ef8496e61c6a83eb99d38d7a34aeba8fcfade3c50f00be0f5f43a0685c627f8`。
- versionName / versionCode 的 aapt2 实测：`3.7.7-gpl / 93`。
- apksigner 校验通过，证书 SHA-256 `e62eca39b7e5e36bc9a05f83688dcfecc11fc5589a4097f7dbbaaa7ddfbbec82`，与前版一致。
- release/debug 均通过 `zipalign -c -P 16 4`；正式包不包含 debug 探针或静音 WAV。
- R8 mapping 在构建成功后立即保存：仓库内 gzip + sha256，原文在仓库外 `dist/mapping/mapping-v3.7.7-gpl-4e9d763.txt`。所有附件大小与哈希见 artifacts.json / SHA256SUMS。
- 方法检查范围为 PlayerViewModel、player、library、PlaylistApi、QqPlaylist：1207 方法。**没有新增 >2000 code unit 的方法**；既有 ViewModel 的 2050 code unit 方法保持不变，完整扫描命令因此仍返回 1，不能宣称所有方法预算全绿。PlaybackService.onStartCommand 为 1666（前版1650）。两版原始报告均保留。
- 修复历史归档：仓库原 v3.7.6 mapping 是 0 字节，已从发布目录中与远端附件哈希一致的 5,820,885 B gzip 恢复；解压原文 79,105,253 B。未移动历史标签或替换历史 Release 附件。

标签指向构建源码，master 可以在后续仅文档提交上前进。GitHub 上传后应以 numeric release id 核对草稿状态及全部 5 个附件的大小、state、SHA-256，记录于 remote-release-verification.json。

远端已核验：Release ID 423757498，保持 draft=true，5 个附件均为 uploaded，大小和 SHA-256 全部与本地一致。草稿链接：https://github.com/yaxiaiyuting/Arris/releases/tag/untagged-e49fb25864c4771b4b38。首次 CLI 上传的正式 APK 连接超时，最终使用 curl HTTP/2 成功上传；其余 4 个附件未被替换。
