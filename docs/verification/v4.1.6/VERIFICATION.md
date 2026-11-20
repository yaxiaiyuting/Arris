# Arris 4.1.6-gpl 发布验收

2026-11-20。Android 10 及以上，versionCode 233，versionName `4.1.6-gpl`。

- 源码：`refactor/arris-source`，构建提交 `b1ee13f`（自 `v4.1.5-gpl` 起 3 个提交）。
- APK 10,753,388 字节，SHA-256 `49eddb14a1d2090951c3b4d0c596ca16673815665e9d0521502b5ca3f3a43265`。
- 签名：v2 通过，证书 `e62eca39…bbec82`（与 4.1.0–4.1.5 同一把）。
- 全量 JVM：通过；`assembleRelease` 成功。

## 内容

- 生日祝福视觉重构（`CelebrationDesign` 调色板、声波呼吸环、双层信笺卡片、错峰淡入、音乐典藏卡、时光生日信函、极圆角日期对话框）
- 舞台几何恒定：`footerReserve` 210/140dp、`footerLift = 0`；灭烛到暗幕平滑承接 0.67 → 1.0
- WebView 祝福页去掉石台动态抬升（`#svg-scene` 静态 `translateX(-50%)`、删 `--final-rise/--final-shrink` 与 `--final-frame` 补间）
- QQ 扫码登录：ping 失败先重连、`Disconnected` 分支、`ON_RESUME` 即时探测、阈值 15 次/30 秒、带凭证跳过 `removeAllCookies`、Cookie 两域合并

## 已做 / 未做

- 已做：签名构建、包名/版本/API 下限复核、全量 JVM、装进 cell 后的界面路径人工确认（多选、封面、下拉刷新、生日流程）
- 未做：弱网与频繁切网、荣耀/小米胶囊、厂商冻结进程；QQ 扫码需要真实扫码确认，只在代码层验证
