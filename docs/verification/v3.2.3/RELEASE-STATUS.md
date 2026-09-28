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

## release 已创建 ✅

**https://github.com/yaxiaiyuting/Ncrust/releases/tag/v3.2.3-gpl**（Latest，非 draft）

| 项 | 值 |
|---|---|
| 资产 | `Ncrust-v3.2.3-gpl-release.apk`（10 206 692 B）+ `.sha256` |
| 远端 digest（GitHub 自己算的） | `sha256:8f27ddb102794b75be417716ce2be2f9a6364fe05b7b3a82671d9171b82a1699` |
| 本地 APK sha256 | `8f27ddb102794b75be417716ce2be2f9a6364fe05b7b3a82671d9171b82a1699` ⇒ **逐字节一致** |

### 之前为什么建不了（以及最后怎么解开的）

前两次失败是**本机环境**问题，不是仓库问题：

1. `api.github.com` 的 **DNS 被污染**（解析到 `66.220.148.145` / `2a03:2880:…:face:b00c:…`，都是 Meta 的地址段），
   直连 TCP 443 超时；而**同一个环境里 `github.com` 是通的**（`git push` 一直正常）——
   即只有 API 主机被挡。中途 `curl --resolve api.github.com:443:140.82.113.6` 偶然通过一次（HTTP 200），
   之后再怎么重试都是 0/20 —— 典型的**按 SNI 间歇重置**。
2. `gh auth status` 报 `keyring` 超时 ⇒ `gh` 拿不到 token（但 `secret-tool` 能读出来，见下）。

**解开办法**（三次尝试的第三条路）：本机跑着 **xray**（`127.0.0.1:10808` SOCKS5），
走它 + 从 keyring 直接取 token：

```bash
export GH_TOKEN="$(secret-tool lookup service gh:github.com)"
export HTTPS_PROXY=socks5://127.0.0.1:10808 HTTP_PROXY=socks5://127.0.0.1:10808
gh release create v3.2.3-gpl --title … --notes-file … --latest dist/Ncrust-v3.2.3-gpl-release.apk …
```

注意 `gh`（Go）**只认 `HTTPS_PROXY` / `HTTP_PROXY`，不认 `ALL_PROXY`** ——
第一次只设 `ALL_PROXY` 时它仍然直连，报的是 `dial tcp 199.59.148.9:443: connection refused`。

> 纪律提醒（铁律 7）：`v3.2.3-gpl` 已发布，**绝不移动 tag**；要改代码另起 v3.2.4-gpl。
