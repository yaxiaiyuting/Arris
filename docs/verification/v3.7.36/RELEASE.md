# Arris LTS 3.7.36-gpl 签名版验收

2026-11-10。Android 7.0 及以上，versionCode 224。签名构建提交 `9ed759b93238fe923a4062aade8f799a4c2361d9`，app tree `b0a9ce31f9107fc502220ed62b158e54dd01f22f`。APK 为 12,303,093 字节，SHA-256 为 `4be314d2ecec37d698eacc5f11565437453bb80eb8c8c6e2dd2715e9e516a0b9`。按 API 24 验证 v2 签名，签名证书与 3.7.34 一致。

- `assembleRelease`、`testReleaseUnitTest`、`lintRelease` 全部完成。JVM 3427 项，3414 通过、13 跳过，零失败。Lint 零错误，81 条告警、10 条提示。测试环境只有回环网络。
- Android 7.0 模拟器从 3.7.34 正常覆盖安装。安装的 APK 与签名构建逐字节一致。离线样本覆盖日推完整列表、滚动到底、返回重开、点击第二首保存完整 16 首队列且索引为 1，以及只有榜单缓存时首页仍能显示榜单。
- QQ 音乐扫码采用相同实现的回环测试构建。Android 7.0 上验证换码关闭旧连接、按返回取消在途请求、Activity 重建沿用原二维码，以及后台确认后保存凭证并恢复账号显示。测试使用虚构凭证。

[GitHub Release](https://github.com/yaxiaiyuting/Arris/releases/tag/v3.7.36-gpl) · [官网版本直链](https://arris.de5.net/get/Arris-v3.7.36-gpl.apk)
