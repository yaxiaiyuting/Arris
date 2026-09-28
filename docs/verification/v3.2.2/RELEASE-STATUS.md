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

## release 状态：**已创建（Draft，未发布）**

**https://github.com/yaxiaiyuting/Ncrust/releases/tag/v3.2.2-gpl**（draft）

这一版**故意不发**：它被 `v3.2.3-gpl` 取代（后者修了「默认档看不到频带」与「刷新率低」两条实测反馈）。
保留成 draft 只是让这个已推的 tag 有一份**产物记录**，供随时发布或删除。
产物与 sha256 都已上传，digest 与本地一致。

**请安装 v3.2.3-gpl**：https://github.com/yaxiaiyuting/Ncrust/releases/tag/v3.2.3-gpl

### 之前卡在哪（留档）

`gh release create` 曾两次失败：`api.github.com` 的 DNS 被污染（解析到 Meta 地址段）导致 TCP 超时，
且 `gh` 读不到 keyring 里的 token。最终用**本机 xray 代理 + `secret-tool` 直取 token** 解开
（完整命令见 `docs/verification/v3.2.3/RELEASE-STATUS.md`）。
