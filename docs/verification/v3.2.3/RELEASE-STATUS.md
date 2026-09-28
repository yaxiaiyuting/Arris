# v3.2.3-gpl 发布状态

## 已完成

| 项 | 值 |
|---|---|
| 版本 | `versionName = "3.2.3-gpl"` / `versionCode = 58`（`tools/next-version.sh` 三源交叉校验：v3.2.2=57 / v3.2.1=56 / 工作区 57 ⇒ 58） |
| 全量门禁 | `./gradlew clean testDebugUnitTest lint assembleDebug assembleRelease` **BUILD SUCCESSFUL**，**2203 个用例 0 失败** |
| release APK | `dist/Ncrust-v3.2.3-gpl-release.apk`（`aapt2` 实测 `versionCode=58 versionName=3.2.3-gpl`） |
| APK sha256 | `8f27ddb102794b75be417716ce2be2f9a6364fe05b7b3a82671d9171b82a1699` |
| debug APK | `dist/Ncrust-v3.2.3-gpl-debug.apk`（+ `.sha256`） |
| R8 mapping（铁律 27） | `mapping-v3.2.3.txt.gz` + `.sha256`；未压缩原文在仓库外 `dist/mapping/mapping-v3.2.3.txt` |
| 探针 | `probe-refresh.md`（刷新率的两条原因、修法、实测对照） |
| 证据 | `verification/sample-gfx-*.txt`（每条采样的 `dumpsys gfxinfo` 原文）+ 三条泳道真机截图 |
| 设备 | S6 已安装本版（`versionCode=58`），登录态与离线缓存完好 |

## 阻塞：release 页面**未创建**（与 v3.2.2 同一原因）

`gh release create` 仍然失败：

```
Post "https://api.github.com/graphql": dial tcp 66.220.148.145:443: i/o timeout
```

- **`api.github.com` 在本机 DNS 被劫持**到 `66.220.148.145`（Meta 的地址段），TCP 443 超时；
  而同一个环境下 `github.com` 是通的（`git push` 成功）—— 即**只有 API 主机被挡**。
- `~/.config/gh/hosts.yml` 里没有 `oauth_token`（登录态在 keyring，读取也超时），
  环境变量里也没有 `GH_TOKEN` / `GITHUB_TOKEN`。

**tag 与 master 都已推上远端**（见下），产物已构建并留档 sha256 —— 只是 release 页面没有建。

## 解除方式（不需要重新构建）

1. 在能访问 `api.github.com` 的环境里：
   `gh release create v3.2.3-gpl --title "Ncrust v3.2.3-gpl —— 刷新率修复" --notes-file docs/verification/v3.2.3/CHANGELOG.md dist/Ncrust-v3.2.3-gpl-release.apk`
2. 或在 GitHub 网页上对已推的 tag 手动建 release 并上传 APK。
3. 修好本机 DNS/代理后再跑第 1 条。

> 纪律提醒（铁律 7）：`v3.2.3-gpl` 一旦推上去就**绝不移动**；要改代码另起 v3.2.4-gpl。
