# v3.6.6-gpl 验证

版本：3.6.6-gpl / versionCode 82。基于 v3.6.5-gpl（deb1a9c），未操作实体手机。

## 修复与触发条件

生日结束动作原来是原生 BottomCenter 按钮，祝福却在 HTML 内布局，两者没有同一个排版参照。现在结束动作直接置于祝福块内，间隔 24 CSS px；测量包含真实按钮高度，至少保留 32 CSS px 底部余量。按钮保持至少 48 CSS px 高度，支持键盘焦点、原生退出桥接、重播后再次停在许愿门控。真实吹气权限与录音仍由原生处理。八语种按钮文案继续由原生注入。

波形有两条可执行的触发路径：

- PlaybackService 在重新缓冲时也发布 isPlaying=false，原有 inactive 分支会清除全带目标历史并衰减。现在帧时钟独立读取 isBuffering，缓冲时冻结历史、弹道与滚动时钟，恢复时重新锚定；正常暂停衰减保持原行为。
- rawDelta > 250ms 或积压超过一屏会令 windowPrimed=false，一帧内消费多根柱甚至整屏替换。现在仅首次挂载预填；后续恢复保留屏幕历史，丢弃过时的待处理数据，最新一根在正常网格边界进入。重锚帧不补走卡顿期间的时间。

缺样保持诚实留空，长时间没有音频输入时原有历史仍会逐列滚出。这与一帧突然整窗替换不同。

## 自动验证

- 全量 JVM：3001 tests / 239 suites / 0 failures / 0 errors / 0 skipped；见 test-summary.json。
- 波形专项：156 tests / 22 suites 全过，含缓冲保留历史、恢复时不补走旧时间、卡顿不替换整窗、缺样峰值不继承。
- 八种浏览器视口：360×800、412×915、320×568、320×480、800×360、915×412、640×320、1280×720。测量和真实点击退出、重播、许愿门控均通过；master.duration=48，页面错误=0；见 layout-results.json。
- 可复跑：设置 ARRIS_PLAYWRIGHT_MODULE 为本机 Playwright 模块路径，运行 `node tools/celebration/verify-layout.cjs <输出目录>`，无需账号或外部网络。
- Android API 33 WebView / debug 82：394×799 CSS px，结束按钮 y=675.14、bottom=723.14，两个按钮均高 48；见 android-webview-82.json。候选与正式包网页资产逐文件核对。
- 调试预览入口只在 debug 清单。新增清单重复报告了 main 清单已有、由 lint-baseline.xml 记录的 MissingIntentFilterForMediaSearch；仅对该 debug 应用节点定点沿用抑制，未增改生产基线或虚构车机支持。
- R8 首次把波形推进内联进 MotionClock.frame，方法体 2176 code units。固定当前运行的 WaveformRing.pump 与 WaveformStore.pump 边界，其他未用成员继续裁剪与混淆；最终方法体监控见 method-release.json。

## 验证边界

本轮没有实体手机测试，不将模拟器或 debug 数据当作真机性能结论。API 33 旧版 WebView 在已有 SVG 场景渲染中仍记录 tile memory 告警；本轮检查证明结束按钮布局和退出行为，不能据此宣称所有设备的整段生日动画都没有合成压力。未修改既有电影叙事、烟花、录音判据或音频解码/输出链。

初次挂载仍可用已有音频样本填满窗口；长卡顿丢弃过时的待处理样本意味着暂停显示期间的少量音频细节不再补播，以保持当前响应与可见历史的连续性。

## 最终产物核对

- 构建提交：6112c9d；app 树和 SHA-256 记录在 BUILD_VERIFICATION.json。
- 正式 APK：11,948,324 B，SHA-256 `726d024e72f80d214eb62478241d44807b390c6a3a3f8ebaffbc409343b73f6d`。
- 签名证书 SHA-256：`e62eca39b7e5e36bc9a05f83688dcfecc11fc5589a4097f7dbbaaa7ddfbbec82`，与原正式包相同。
- Lint：0 errors / 61 warnings / 5 information，生产基线不变。完整 testDebugUnitTest、lintDebug、assembleDebug、assembleRelease 成功；最后只改 R8 规则后 assembleRelease 再次成功。
- 方法大小最终检查：涉及 WaveformRing、WaveformStore、MotionClock、BirthdayWebOverlay、PlayerCardBackdrop 的 160 个方法，最大 1231 code units，超过 2000 的方法为 0。
- 逐文件确认 APK 中 birthday 网页资产与提交源码完全一致。
- API 33 模拟器5554原安装的是 debug 签名；为保留其数据，R8 播放检查用正式构建重新签成同一 debug 证书的 QA 副本。该副本仍是不可调试的 release 清单；403 个 DEX、资源与资产条目与正式包逐字节相同。该 QA 副本不上传，用户下载的是原正式签名 APK。


R8 播放检查：API 33 / 1920×1080 横屏，连续 20 秒播放与波形画面抽帧检查，窗口内 1210 帧、janky 2 帧（0.17%）、p50 17ms、p95 18ms、p99 19ms、Missed Vsync 0。见 release-gfx-summary.txt。该窗口仅作模拟器回归记录，没有同设备相邻 A/B 基线，不能据此宣称真机性能提升或覆盖所有缓冲环境。
