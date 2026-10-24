# v3.7.1-gpl 验证记录

源码提交：`02c7df281185e03f730face9372e4845c33fe923`；versionCode 87。Kanesumi：`acddf3e53d6a90f6b7d19d6a0445631457c3bda5`（构建时工作区干净）。本轮使用独立工作树，保留其他工作树已有修改。

## 修复与证据

1. **断网恢复后的歌词计时**：缓冲、暂停、隐藏恢复重新以真实位置锚定；采样过期最多外推 750ms。常驻采样协程通过 rememberUpdatedState 读取最新播放状态。迟到的取词任务在 begin 前确认完整 TrackKey。LyricClockTest 覆盖一分钟断网等待、正常采样、恢复锚点和单调时钟边界；未做真实音频断网交互复测。
2. **会员日期**：QQ 解析完整时间与日期，严格解析、不静默滚动非法日期；普通绿钻用 identity.overdate，SVIP 用 data.send，缺失日期不借用另一种权益。QQ 资料写回绑定 auth revision；B站 vip.due_date 归一到毫秒并存储 vip_expire_at_ms，旧条目缺失为 0。账号页按 TTL 刷新、失败保留缓存，8 种语言补齐未知/到期文案。测试覆盖记录的 QQ 字段形状和构造的 B站 nav 样本；本轮未调用真实会员账号在线验证。
3. **码率补齐**：接口之外增加当前选中音频 Format 与完整文件大小/时长，禁止使用缓存片段长度或 PCM 输出参数冒充文件码率。以当前 MediaItem 的音源与 id 绑定发布，预载不提前修改当前曲目。接口与实际流均缺少可用元数据时仍省略。StreamBitrateTest 覆盖文件平均码率、格式优先、未知时长和大文件边界。
4. **缓存扩容**：默认 1024 MiB、最大 16384 MiB，增加 12/16 GiB 候选，两个入口共用同一列表；缺省值不落盘，已明确设置的容量保留。缓存容量更改下次启动生效。OfflineCacheBudgetTest 检查注册表与运行时配置一致。
5. **随机播放**：没有发现固定种子，实际使用 Random.Default。旧“最多剩余艺人优先”使轮首偏向同一批艺人；改为按可选歌曲数随机抽取，仅在必要时优先占多数的艺人，保留可满足的分散约束。新增固定种子集合的行为回归。shuffle_round_v1 按完整队列身份保存顺序、游标，字段全部 SerializedName；队列变化或快照损坏回落新轮。ShuffleSnapshotTest 覆盖重启恢复、跨源同号、错位、损坏与稳定字段名。轮末手动下一首复用 pendingRound。

## 自动检查

- JVM：**3027 tests / 245 classes / 0 failures / 0 errors / 0 skipped**。
- Lint Debug：0 Error / 0 Fatal / 55 Warning；未把既有警告描述为零警告。
- testDebugUnitTest、lintDebug、assembleDebug、assembleRelease 最终同一轮构建成功。
- aapt2 核对 release 版本：3.7.1-gpl / 87；apksigner verify 通过；签名 SHA-256：`e62eca39b7e5e36bc9a05f83688dcfecc11fc5589a4097f7dbbaaa7ddfbbec82`；zipalign -c 4 通过。
- mapping 在最终 R8 后立即归档，原文保留于仓库外 dist/mapping，压缩件与校验入本目录；首轮审阅前构建也单独保留 mapping，不会混用。

## 方法大小扫描的实际边界

feature 报告 239 个方法全部 <= 2000 code units，最大 1558。扩大扫描到 MainActivity、LyricsView、PlayerViewModel 和注册表后，仍有 **4 处原本存在的超预算方法**，全量门禁未全绿。与 v3.7.0 APK 的同范围基线对照：主界面合成方法 4297→4291，LyricsView 3160→3156，歌词源选择方法 2050→2050；合并后的静态初始化器 5629→5645（新增账号文案）。没有新增超预算方法，未放宽阈值。该历史预算债务记录于完整报告，不把功能范围扫描冒充整个播放器通过预算门禁。

## 未验证范围

依用户要求不做真机测试，也未启动模拟器或浏览器自动播放。真实断网恢复交互、真实会员接口的当前账号字段、不同格式音频的解码元数据仍需设备场景确认；合成与纯逻辑测试不等同于设备实测。

release APK：12039856 B，SHA-256 `bf18ec647063053d4f92349af4a0f802ca26004729baf2f97e6be8fc84651497`。其他产物见 artifacts.json 与 dist 的 SHA256SUMS。
