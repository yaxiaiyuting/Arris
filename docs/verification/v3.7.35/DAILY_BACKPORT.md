# Arris LTS 3.7.35-gpl 日推回移验收

2026-11-09。本地签名验收包，versionCode 223，minSdk 24。使用 LTS 自有的导航、首页和歌单组件回移「每日推荐」完整歌单，保留首页独立的播放全部入口。列表支持从选中的歌曲开始播放、插播、追加和收藏到库。

- 基线为 `9a997b4`，对应线上 3.7.34。回移提交 `f1124d6`，签名构建提交 `792c02b`，app tree `04b944a5acb60514c24a241ea4c84a7b7565eb60`。
- 版本号由 `next-version.sh` 取全局最大值 222 加一。APK `Arris-v3.7.35-gpl.apk` 为 12,303,093 字节，SHA-256 `4e393dbf633e5443533179b705556c93b50cf923dcc7759b96751599fee1b3de`。按 API 24 验证 APK 签名通过，签名证书与 3.7.34 一致。
- `assembleRelease`、`testReleaseUnitTest`、`lintRelease` 成功。JVM 共 3427 项，3423 项通过、4 项跳过、无失败。Lint 零错误，81 个警告、10 条提示。
- 后台 Android 7.0 模拟器上从正式 3.7.34 覆盖安装成功，安装 APK 和归档 APK 字节一致。使用 16 首虚构缓存曲目打开日推，滚动到最后一首、返回并重新进入。点击第二首保存 16 项队列且索引为 1，详情页播放全部保存 16 项队列且索引为 0。样本歌曲无音频地址，验收覆盖界面、导航及播放队列分派。
- 系统声音采集依赖 Android 10 的 `AudioPlaybackCapture`。本次 LTS 仅回移日推歌单，随后恢复按 Android 7 兼容条件筛选主线修复。

签名包、校验文件、截图和构建结果在 `/home/duanjb666/deepseek/arris/dist/v3.7.35-lts-daily`。UI 测试脚本及原始输出在 `/home/duanjb666/deepseek/arris/dist/lts-daily-qa`。
