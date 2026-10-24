# v3.7.2-gpl 验证记录

产物源码：88e4a6b557c4fc4ab941b31cea72ebcfab8c48e9。versionName 3.7.2-gpl / versionCode 88；Kanesumi 仍为 acddf3e53d6a90f6b7d19d6a0445631457c3bda5。版本递增已在修改前通过三源脚本核对，见 next-version.json。

## 按钮修复

Tux 彩蛋及原生生日结束页最多将结束动作抬高 48dp，只使用插画高于 72dp 地板的可用空间；总布局高度保持视口高度，短屏自动缩小间距。保留既有 320ms 平滑过渡。网页生日祝福组竖屏锚点上移，动作行间距 24→16px，测量时留出 48..72px 底部余量。没有改动生日 48 秒时间线、麦克风、吹气判据或音频路由。

## 自动验证

- testDebugUnitTest、lintDebug、assembleDebug、assembleRelease 构建成功，构建复核输出见 build-check.txt。
- JVM：3029 tests / 245 classes / 0 failures / 0 errors。新增几何回归覆盖完整视口、短屏余量不足、无余量与未知高度。
- Lint：0 Error / 0 Fatal / 55 Warning（既有警告保留）。
- 庆祝功能 DEX 范围 684 个方法，最大 942 code units，全部 <=2000。本轮没有宣称全仓历史超预算方法已经消除；v3.7.1 记录的四处预算债务仍在范围外。
- aapt2 核对安装包版本 3.7.2-gpl / 88；release 签名通过，证书 SHA-256 为 e62eca39b7e5e36bc9a05f83688dcfecc11fc5589a4097f7dbbaaa7ddfbbec82；debug/release 的 zipalign -c 4 均通过。
- R8 mapping 已在下一次构建前归档；复核构建后 mapping 原文与归档一致（SHA-256 d2baf402db711c406d671261d0991c1b0daa54666ac7d6783d621fe828e02b79）。原文在仓库外 dist/mapping，压缩件及其 SHA-256 在本目录。

## 布局与交互

网页由 headless Chromium 检查 360×800、412×915、320×568、320×480、800×360、915×412、640×320、1280×720 八种视口。结束按钮全部在文案之后、视口内、点击高度 >=48px；最小实测底部余量 51.16px。继续触发 CLOSE，重播收起结束按钮并重新进入 26.2s 吹气门控；时间线 48 秒，零 pageerror。截图及几何结果见 web-layout/。未打开可见浏览器或自动播放视频。

API 33 模拟器 emulator-5556 上以正式签名覆盖安装，实际经“关于 Arris→版本连续点击7次”打开 Tux 彩蛋，静态截图已人工查看。720×1560 / density300 下按钮完整显示、无重叠；点击“继续听歌”退出彩蛋。截图、节点及可点击区域见 tux-720x1560.png、native-layout.json。测试时媒体音量设为0、播放暂停。本轮未操作任何物理手机。

## 审查边界与产物

队列预载、离线缓存及试听属性的三项审查发现见 docs/reviews/2026-10-24-playback-adjacent.md；网易云参考实现、跨会话响应及 QQ 歌单错误分类见 docs/reviews/2026-10-25-api-enhanced-comparison.md。这些业务问题是本轮审查发现，尚未修复，不能把本版本描述成已接入自动网易云续期或已修复该五类问题。

release APK 12039884 B，SHA-256 e95c8a6d2f2138a398e67c46125360880f38a772f0b25eeb2c5a045ee20a1053。完整文件大小与哈希见 artifacts.json / SHA256SUMS。草稿发布只上传构建产物，不发布正式 Release；远端结果另存 remote-release-verification.json。
