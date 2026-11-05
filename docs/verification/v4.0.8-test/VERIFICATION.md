# Arris 4.0.8-gpl 测试包

- APK：`dist/v4.0.8-test/Arris-v4.0.8-gpl.apk`，`versionName 4.0.8-gpl` / `versionCode 208`，minSdk 29。
- SHA-256：`a5035e69d6b52b236165b45206424fb936f7d1646b339ca5cad110b99b302e71`（见同目录 `SHA256SUMS`）。
- 构建：`./gradlew --offline --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process :app:assembleRelease`，`BUILD SUCCESSFUL`。
- 签名：`apksigner verify --verbose` 通过，**v2 scheme = true**、单一签名者，与 4.0.6 / 4.0.5 同一密钥，可覆盖安装。
- 本版内容：竖屏「留白跟唱」（`KaraokeScreen` + 竖屏嵌入模式）、竖屏歌词与进度条合并、切歌时只挂载一个歌词视口。

## 未做

- 真机流畅度 / 耗电核验（本轮只做构建与签名校验）
- LTS 侧**跟唱功能**未回移：`KaraokeScreen` / `PortraitFocusLyrics` / `KaraokeTimeline` 依赖主线 4.0.7 的
  播放器布局重构，cherry-pick 在 LTS 上产生 18 处冲突；3.7.29 只回移了可机械迁移的
  「切歌单视口」修复。跟唱回移需单独立项。
