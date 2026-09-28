# v3.2.0-gpl 发布状态（回填）

> 采集时间：2026-09-28 19:15 CST ｜ **回填：2026-09-28 19:32 CST（发布已完成）**

## 1. 已完成（本地，全部可自证）

| 项 | 值 | 自证方式 |
|---|---|---|
| 版本号 | `versionName = "3.2.0-gpl"` / `versionCode = 55` | `git show v3.2.0-gpl:app/build.gradle.kts \| grep version` ⇒ `55` / `"3.2.0-gpl"` |
| 三源交叉校验 | 最大值 54 ⇒ 55 | `tools/next-version.sh`（fetch 后）输出：tag 54/53/52/51/50、dist APK 实测 50、工作区 54 |
| 单测 | **151 suite / 2099 用例 / 0 失败 / 0 错误 / 0 跳过** | `app/build/test-results/testDebugUnitTest/*.xml` 汇总 |
| lint | **0 error**（release 变体） | `app/build/reports/lint-results-release.xml` |
| 构建 | `assembleDebug` 31,188,393 B、`assembleRelease` 10,190,308 B | `app/build/outputs/apk/**` |
| 产物 | `dist/Ncrust-v3.2.0-gpl-release.apk` | sha256 `f929968c098dab06be63c1923d4e29f44220a0d9175d594c0c2ad55894b7255f` |
| 产物的版本号（红线 19：看二进制，不看标题） | `versionCode='55' versionName='3.2.0-gpl'` | `aapt2 dump badging dist/Ncrust-v3.2.0-gpl-release.apk` |
| 工作区干净 | `git status --porcelain` 输出 **0 行** | 构建**之后**再次执行 |
| HEAD == 产物源码 | 最后一次改动 `app/` 的提交在构建**之前**；构建后 `git status --porcelain` 为 0 行、`git diff HEAD --stat -- app` 为空。其后只有**文档**提交（docs/），`app/` 一个字节未变 | 构建后执行 |
| tag | `v3.2.0-gpl`（附注 tag）指向**产出这批 APK 的那个提交** `0a7319c` | `git rev-list -n1 v3.2.0-gpl` ⇒ `0a7319c`；`git show v3.2.0-gpl:app/build.gradle.kts \| grep version` ⇒ `55` / `"3.2.0-gpl"`。⚠️ 本文件这份回填提交在 tag **之后**（v3.1.0 同一做法），所以 `HEAD` 比 tag 多一个纯文档提交 —— `git diff 0a7319c..HEAD -- app` 为空 |

## 2. ✅ 已发布（原阻塞已解除，本节为回填）

2026-09-28 19:32 CST 复测：本地代理 `127.0.0.1:10808` 已恢复监听、`gh auth status` 显示
`✓ Logged in to github.com account yaxiaiyuting`（token scopes 含 `repo`）。

| 项 | 值 | 自证方式 |
|---|---|---|
| 远端 master | `0a7319c5ce264605933f1f3d557d3d5804bf4e2f` | `git rev-parse origin/master`（`08e2641..0a7319c` 快进推送） |
| 远端 tag | `v3.2.0-gpl` → **`0a7319c`**（附注 tag 对象 `22bb2518…`） | `gh api repos/…/git/tags/22bb2518… --jq .object.sha` |
| tag 可从 master 到达 | 是 | `git merge-base --is-ancestor v3.2.0-gpl^{commit} master`（exit 0） |
| release | **已发布（非 draft、非 prerelease）** | `gh release view v3.2.0-gpl --json isDraft,isPrerelease` ⇒ `false / false`，`publishedAt = 2026-09-28T11:32:00Z` |
| release URL | <https://github.com/yaxiaiyuting/Ncrust/releases/tag/v3.2.0-gpl> | 同上 |
| 上传的产物 | `Ncrust-v3.2.0-gpl-release.apk`，**10,190,308 B** | release asset 列表 |
| **产物 digest 与本地逐字节一致** | `sha256:f929968c098dab06be63c1923d4e29f44220a0d9175d594c0c2ad55894b7255f` | GitHub 自己算的 asset `digest` 字段 与本地 `sha256sum` **逐字相等** —— 这是「上传的包就是这批源码构建出来的那个包」的最强证据 |
| **已发布的历史 tag 一个都没有被移动** | `v3.1.0-gpl`=`28c1d9b`、`v3.0.0-gpl`=`0b7563d`、`v2.9.0-gpl`=`e4c5ca9`、`v2.8.0-gpl`=`06782e2` | 本地 `git rev-list -n1 <tag>` 与远端 `gh api repos/…/commits/<tag> --jq .sha` **逐字比对，四个全 OK** |

**tag 没有再被移动。** `v3.2.0-gpl` 在本文件上一版（未推送）时曾被重建过一次 —— 那次它
**从未推送过**，不属于「已发布的 tag」；自 `git push origin v3.2.0-gpl` 成功那一刻起它
就是已发布的 tag，本版与后续版本都**不会**再移动它。

（本文件这份回填提交发生在 tag **之后**，与 v3.1.0 的做法一致：tag 留在「产出这批 APK 的
那个提交」上，回填是它之后的一个纯文档提交。`git diff 0a7319c..HEAD -- app` 输出为空 ⇒
`app/` 一个字节都没变。）

## 3. 发布时使用的命令（已执行，留档可复现）

```bash
cd /home/duanjb666/deepseek/Ncrust
git fetch --tags --prune
git push origin master            # 08e2641..0a7319c  (fast-forward)
git push origin v3.2.0-gpl        # * [new tag] v3.2.0-gpl -> v3.2.0-gpl
cd /home/duanjb666/deepseek
./ghx.sh release create v3.2.0-gpl \
  dist/Ncrust-v3.2.0-gpl-release.apk \
  --repo yaxiaiyuting/Ncrust \
  --title "v3.2.0-gpl · 版权判定与失败路径修复 + 界面律动开关 + B站登录" \
  --notes-file Ncrust/docs/verification/v3.2.0/CHANGELOG-v3.2.0.md
```

## 4. 发布后的独立复核（任何时候可重跑）

```bash
cd /home/duanjb666/deepseek
./ghx.sh release view v3.2.0-gpl --repo yaxiaiyuting/Ncrust \
  --json isDraft,isPrerelease,publishedAt,assets \
  --jq '"draft=\(.isDraft) pre=\(.isPrerelease) at=\(.publishedAt)", (.assets[]|"\(.name) \(.size) \(.digest)")'
# 期望：draft=false pre=false；asset digest = sha256:f929968c…b7255f（与 dist/*.sha256 逐字相等）
git -C Ncrust show v3.2.0-gpl:app/build.gradle.kts | grep -E '^        version(Code|Name)'
# 期望：versionCode = 55 / versionName = "3.2.0-gpl"
```
