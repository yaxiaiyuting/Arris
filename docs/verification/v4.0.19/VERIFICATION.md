# Arris 4.0.19-gpl 发布验收

2026-11-09。Android 10 及以上，versionCode 222。构建源码 `ef24e58bd922a5f5ece09270100d92a3f03fd893`，app tree `c593270ebb941116ad0d328e22c5c458a40d7d2e`。

- 正式 APK `Arris-v4.0.19-gpl.apk`，10,621,996 字节，SHA-256 `18052dad4d20fc829713825d49cfb9302a6d8657f123b4a22ab63ba20d116541`。APK v2 签名通过，证书与此前签名包一致。后台模拟器内 APK 哈希一致。
- next-version 从 221 取 222。最终 assembleRelease、testReleaseUnitTest 和 lintRelease 成功。JVM 3539 项，3535 项通过，4 项跳过。Lint 零错误。
- Android 14 后台模拟器通过日推 16 首完整浏览、返回重进、点击第二首后保存完整 16 项队列且索引为 1。使用虚构曲目，测试不产生用户媒体播放。深色正常尺寸及浅色 320dp、130% 字号实图已查。
- 识曲真实 UI 验证了未登录提示、拒绝 Android 授权、主动停止、退到后台后停止和释放投射服务。禁止采集的合成播放源呈静音提示，没有进入识曲匹配。允许采集的合成源产出十秒、83,109 字节的 M4A，并进入现有匹配入口。临时 M4A 已清理。
- 识曲测试的模拟器关闭网络，并按 App UID 阻止非环回出站。会话使用虚构凭证。远端真实歌曲命中、厂商系统限制和用户真机性能未验收。
- 第一次授权验收发现旧代码直接比较不同的登录快照对象，导致误报账号变化。改用 `CookieManager.isCurrent` 后，同一授权和捕获流程复测通过。

## 正式发布核对

- GitHub `v4.0.19-gpl` 为正式主线版本，tag 指向 `138b6a2d079cf073e2fec730030e297b03a8e085`。从公开 Release 下载的 APK 与验收包逐字节一致。
- 官网首页已更新。版本直链、`latest.apk` 与 GitHub APK 的 SHA-256 一致。国内路径测试返回 `206` 和正确的 Range 数据。
- 官网实时更新源及静态回退均列出 `v4.0.19-gpl` 及对应官网直链。Cloudflare Worker 部署版本为 `ca6bcaac-9977-4e3d-87fb-0e2075254186`。

完整日志与原始结果位于 `/home/duanjb666/deepseek/arris/dist/v4.0.19-gpl`。模拟器和合成声源均为本地隔离测试。
