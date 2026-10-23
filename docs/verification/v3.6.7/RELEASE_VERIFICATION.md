# v3.6.7-gpl 验证

versionName = 3.6.7-gpl / versionCode = 83。v3.6.6 的 master/tag 已推送，图标使用新版本，不移动旧标签。

## Tux 图标

`tools/icons/generate_tux_launcher.py` 从 `tux_listener.xml` 复用面部、眼睛、喙、耳机和围巾路径，生成桌面头像、单色主题图标和通知栏图标。无远程图片、图像服务或新增运行时依赖。Tux 署名沿用 Larry Ewing / The GIMP，Arris 重绘与生成器 GPL-3.0，见 THIRD-PARTY-LICENSES.md。

- 自适应前景 alpha 最大半径 33.261dp，低于本项目 34dp 安全阈值；108dp 画布中心 72dp 区内完整。
- 检查圆形、圆角方形、方圆形和单色主题预览，含 48/32/24px 小尺寸。预览与 SVG 原稿在 artwork/launcher/。
- 10 个旧安卓 PNG 尺寸核对：48、72、96、144、192px，各有普通/圆形版本。roundIcon 修正到独立 ic_launcher_round。
- API 33 模拟器5554 debug83 应用抽屉已显示新 Tux，头部与耳机完整，没有使用实体手机。
- 启动窗口颜色 window_background 不变；图标背景使用独立 ic_launcher_background 颜色。
- 关于页复用新头像；媒体通知与 Live Update 使用无背景的 24dp alpha Tux，保留播放/暂停动作的语义。

## 回归与构建

本版本继承 v3.6.6 的生日按钮与波形修复。对应的 8 种视口点击检查、缓冲/长卡顿历史单测、API33 WebView 检查与 R8 播放窗口，见 ../v3.6.6/RELEASE_VERIFICATION.md。
本轮再次运行全量 JVM、lintDebug、assembleDebug、assembleRelease；最终二进制、源码提交、APK签名和校验和见同目录报告。

- JVM：3001 tests / 239 suites / failures=0 / errors=0 / skipped=0。
- R8：监控波形、时钟、生日与背景相关 160 个方法，最大方法 1231 code units，超过 2000 的为 0。
- 原始 mapping 在仓库外 dist/mapping/，压缩件及其 SHA-256 在本目录并随 Release 提供。
- 不把模拟器/debug 的图标核对当作实体手机验证；没有实体手机安装或测试。
- 旧版 WebView 的 tile memory 告警仍在上一版验证说明中记录，不能把这次图标和结束按钮验证理解为所有设备整段动画均无合成压力。

最终构建提交：b2b18a1ee8e07603362bb832f5ce9083ca0ab525。Lint: {'Information': 5, 'Warning': 55}，错误为0。所有产物 SHA-256 见 SHA256SUMS 与 BUILD_VERIFICATION.json。
