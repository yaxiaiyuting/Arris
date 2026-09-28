# v3.2.2-gpl 发布状态

## 已完成

| 项 | 值 |
|---|---|
| tag | **`v3.2.2-gpl`**（annotated，`4df04915002c81a90c8ba842e17068428f5fcfcc`） |
| tag 指向的提交 | `e36c521`（`docs(v3.2.2): 三条泳道的证据与文档回填`） |
| 产出这批 APK 的提交 | `98af858`（`feat(waveform): 波形改为三条频带泳道…`）—— 其后的提交只动 `docs/` 与 `AGENTS.md`，**`app/` 一个字节没变**（`git diff HEAD --stat -- app` 为空） |
| tag 自证 | `git show v3.2.2-gpl:app/build.gradle.kts \| grep version` ⇒ `versionCode = 57` / `versionName = "3.2.2-gpl"` |
| 推送 | `master` → `e36c521`（远端已自证）；tag → `refs/tags/v3.2.2-gpl` ✅ |
| release APK | `dist/Ncrust-v3.2.2-gpl-release.apk`，`aapt2 dump badging` ⇒ `versionCode=57 versionName=3.2.2-gpl`，大小 10 206 692 B |
| APK sha256 | `5fc226a268e94c91e4eb7af949108c9cb0cfafd879152f7746956c7b97ef8152` |
| debug APK | `dist/Ncrust-v3.2.2-gpl-debug.apk`（+ 同名 `.sha256`） |
| R8 mapping（铁律 27） | `mapping-v3.2.2.txt.gz`（sha256 `41e9429362ef9d810719ad02fef75f12600400bbac7925df6dffd690c85fdf3a`）+ `.sha256`；未压缩原文在仓库外 `dist/mapping/mapping-v3.2.2.txt` |
| versionCode 三源校验 | `next-version.txt`（tag v3.2.1=56 / dist APK 实测 56 / 工作区 56 ⇒ **57**） |
| 工作区 | `git status --porcelain` 为空（本文件提交前） |

## 阻塞：release **未创建**

`gh release create` 失败，两个原因都在本机环境、与本仓库无关：

```
$ gh release create v3.2.2-gpl --title "…" --notes-file … dist/Ncrust-v3.2.2-gpl-release.apk
Post "https://api.github.com/graphql": dial tcp 66.220.148.145:443: i/o timeout
```

1. **`api.github.com` 在本机不可达（DNS 被劫持）**：解析结果是 `66.220.148.145`
   （`66.220.148.0/24` 属于 Meta，不是 GitHub），TCP 443 连接超时；
   `curl -4 https://api.github.com/` 25 秒无响应。
   注意**同一个环境下 `github.com` 是通的**：`git ls-remote` / `git push` 全部成功
   （`github.com` ⇒ `199.59.149.235`，git over HTTPS 走 443 正常）。
   所以这不是"没有网络"，而是**只有 API 主机被挡**。
2. **`gh` 没有可用凭据**：`~/.config/gh/hosts.yml` 里没有 `oauth_token`
   （登录态在 keyring，而 keyring 读取也超时），本机环境变量里也没有 `GH_TOKEN` / `GITHUB_TOKEN`。

**因此：tag 已推、产物已构建并留档，但 release 页面没有创建**（不伪造）。

### 解除方式（三选一，都不需要重新构建）

1. 在能访问 `api.github.com` 的环境里跑：
   `gh release create v3.2.2-gpl --title "Ncrust v3.2.2-gpl —— 波形改造为三条频带泳道" --notes-file docs/verification/v3.2.2/CHANGELOG.md dist/Ncrust-v3.2.2-gpl-release.apk`
2. 在 GitHub 网页上对已推的 tag `v3.2.2-gpl` 手动建 release，上传
   `dist/Ncrust-v3.2.2-gpl-release.apk`（附 `dist/Ncrust-v3.2.2-gpl-release.apk.sha256` 供校验）。
3. 修好本机 DNS/代理后再跑第 1 条。

> 纪律提醒（铁律 7）：**`v3.2.2-gpl` 这个 tag 已经推上去了，绝不移动**。
> 若这一版还需要改代码，另起 `v3.2.3-gpl`，不要动它。
