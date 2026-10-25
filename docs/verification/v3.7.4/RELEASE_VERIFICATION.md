# v3.7.4-gpl 验证记录

构建源码提交：cc7ba51（版本号 90 / 3.7.4-gpl）。继承 v3.7.3 的五类审查修复与双平台续期。依用户要求不做真机安装或播放测试；只读核对已连接手机安装版本为 3.7.3-gpl / 89，没有取得可用于复现的相关播放日志。具体故障归因来自源码分析与独立模拟器验证。

## 故障路径与修复

1. **网易云被标成 QQ**：songRefOf(NETEASE) 按兼容约定输出 source=null；旧 Service 只在 sourceKey/sourceId/mediaId 非 null 时写字段。QQ→网易云时，旧 QQ 载荷仍在，buildCurrentMediaItem 因此构造 QQ mediaId，并经 transition 回调反向污染 ViewModel。新 URL 现在一次性替换完整身份，网易云空 source 经 TrackKey.of 正确解析，QQ 两个载荷清空；无 URL 的控制指令保留身份。
2. **按钮能点但音频不换**：SINGLE 下原本手动下一首/上一首直接 seekTo(0)，系统 NEXT 又与自然结束共用回调。现在手动导航走队列，只有自动曲终保持单曲循环，NEXT 通过独立回调传到应用。
3. **系统命令重复执行**：onPlayerCommandRequest 返回 RESULT_SUCCESS 只授权，不吞掉 Player 命令。旧代码在此派发应用队列后，Media3 仍能操作内部预载列表。现在 QueueSessionPlayer（ForwardingSimpleBasePlayer）在执行层分派四种 next/previous 命令，并为单项内部列表保持这些导航命令可用；普通 seek 和播放暂停继续转发。
4. **旧请求/旧预载覆盖新意图**：缓存快路径也取消旧 playJob，手动 playSong 撤销旧预载；IO→Main 后重新校验播放代次，已发送 Intent 到 Service 后再校验。预载既校验队列代次，也校验 owner TrackKey 与实际 MediaItem，不能在 B 尚取链时把 C 排到还在播放的 A 后面。
5. **主动续期阻塞切歌**：网易云主动续期改为后台按需单飞，不等待刷新服务器才发可播放请求；明确鉴权拒绝才等待刷新并最多重试一次。没有增加定时器、心跳或通用写请求重放。
6. **旧缓存迁移补充**：旧 FULL 记录缺少 file_epoch 时保持未验证，不作为完整播放上报依据；在线取得完整曲后，只有仍占用未验证旧字节的缓存 key 才隔离到独立已验证项。既有缓存保留供离线回退，避免旧试听字节被新 FULL 标签重新解释。

7. **局域网交接回归**：全量测试触发旧 CBC 错误密钥偶发通过填充校验的情况。保留兼容的线协议，解密后增加严格 UTF-8 与 MUSIC_U 会话结构检查，补充两条确定性回归；不将 CBC 填充称为完整性认证。

参考 Media3 官方文档：https://developer.android.com/media/media3/session/control-playback （Customize default player commands）。使用本地 Media3 1.5.0 的 ForwardingSimpleBasePlayer，不升级依赖。

## 模拟器组件测试

独立 AVD arris-regression374，API 33 / x86_64，后台无可见窗口；只使用随 debug 包提供的本地静音 WAV，不登录账号、不请求网络音频。正式 release 包不含 probe Activity 和 WAV。

实际播放服务验证：QQ 身份和两个 mid 安装；QQ→网易云清空旧 mid；已排队的旧播放 Intent 被拒绝；旧 owner 的预载被拒绝；手动 NEXT 替换实际媒体项并不触发自然曲终；MediaController 的 next、nextMediaItem、previous 在单项内部列表上各只派发一次。最终结果另存 emulator-playback-regression.json。

## 最终构建与自动检查

- testDebugUnitTest / lintDebug / assembleDebug / assembleRelease 同一源码构建成功。JVM **3074 tests / 250 classes / 0 failures / 0 errors / 0 skipped**。相对 v3.7.3 新增 16 条回归（导航与身份 12、旧缓存迁移 2、凭证解密文本 2）。
- Lint Debug **0 Error / 0 Fatal / 60 Warning**。baseline 中已有的过滤项目未在本轮放宽。
- 版本 aapt2 核对 **3.7.4-gpl / 90**。apksigner 验证通过，正式证书 SHA-256 仍为 e62eca39b7e5e36bc9a05f83688dcfecc11fc5589a4097f7dbbaaa7ddfbbec82，支持覆盖安装。
- debug/release 的 zipalign -c -P 16 4 均通过；正式包不含 PlaybackRegressionProbeActivity 和静音 WAV。
- 最终 R8 mapping 已立即归档：原文 dist/mapping/mapping-v3.7.4-gpl-cc7ba51.txt，SHA-256 03958632c873f4907ed7ca3f57de207660f4ed65b99ce5f2254df04ca5cf7bb3；压缩件与校验随本目录提交。预验证构建20b8684的原文也单独保留，未覆盖混用。
- 播放服务、会话、缓存及新辅助方法范围 247 个 DEX 方法均 <=2000 code units，最大1650。扩大到 MainActivity / PlayerViewModel / LyricsView 后仍有历史超预算方法；报告单独保留，不将功能范围通过说成全仓预算通过。
- API33后台模拟器的8项组件检查在最终源码cc7ba51的debug包上重跑成功，详见emulator-playback-regression.json。

## 产物及发布

正式APK 12072652 B，SHA-256 `861800757ffe4c146bf5ef1af8134373e7dac581933c73b7f44a669799675544`。其他文件见artifacts.json和SHA256SUMS。应用源码与构建提交cc7ba51一致；标签指向该构建源码，后续仅文档提交可推进master。

远端资产核验（release id `423136080`，已发布于 `https://github.com/yaxiaiyuting/Arris/releases/tag/v3.7.4-gpl`（发布前为 draft，附件核验通过后转公开））：

| 附件 | 字节 | 远端 digest | 与本地一致 |
|---|---|---|---|
| `mapping-v3.7.4-gpl.txt.gz` | 5721775 | `sha256:df60f195c59e0f766682d505553779a6085dcc4d778e56317ed7fa6a7ebd9b42` | 是 |
| `Ncrust-v3.7.4-gpl-debug.apk` | 34315819 | `sha256:5c0f1d79df4547e850e9a4bb83f94665500e417ddbfacbe15cc929da1943ca3d` | 是 |
| `Ncrust-v3.7.4-gpl-release.apk` | 12072652 | `sha256:861800757ffe4c146bf5ef1af8134373e7dac581933c73b7f44a669799675544` | 是 |
| `RELEASE_NOTES.md` | 1796 | `sha256:7c22be68678e9811caca2e51d76d34fb05e2f2a33979771874557d3963944806` | 是 |
| `SHA256SUMS` | 365 | `sha256:28aca46493274f5cbe83f3b1ba8dbc6aedf4b8e01f7668306ea670ac3f9d3370` | 是 |

五个附件远端 `size` 与 `sha256` 全部与本地一致（`True`）；
另按 asset id 下载正式包含量校验：`sha256 = 861800757ffe4c146bf5ef1af8134373e7dac581933c73b7f44a669799675544`，与本地一致（`True`）。
链接：https://github.com/yaxiaiyuting/Arris/releases/tag/v3.7.4-gpl

模拟器组件验证不等同于真实账号、真实音乐网络或真机复测。本轮没有向真实账号发送续期或播放上报请求。模拟器组件验证不等同于真实账号、真实音乐网络或真机复测。本轮没有向真实账号发送续期或播放上报请求。
