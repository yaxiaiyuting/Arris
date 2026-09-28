# v3.2.0-gpl 发布状态（回填）

> 采集时间：2026-09-28 19:15 CST

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
| HEAD == 产物源码 | HEAD = `85c4a2d`，最后一次改动 `app/` 的提交在构建之前；`git diff HEAD --stat -- app` 为空 | 构建后执行 |
| 本地 tag | `v3.2.0-gpl`（附注 tag）指向 `85c4a2d` | `git show v3.2.0-gpl:app/build.gradle.kts` |

## 2. ⛔ 阻塞：推送与创建 release 无法完成（网络 + 凭据）

| 阻塞项 | 实测 | 影响 |
|---|---|---|
| 到 GitHub 的网络 | `curl https://api.github.com/` ⇒ **000**（直连不可达）；`curl -x http://127.0.0.1:10808 https://api.github.com/` ⇒ **000**（`ghx.sh` 配置的本地代理端口**没有在监听**） | `git push origin master` 与 `git push origin v3.2.0-gpl` 均失败：`Failed to connect to github.com:443 over proxy 127.0.0.1 after 0 ms` |
| GitHub 凭据 | `ghx.sh auth status` ⇒ `X Failed to log in to github.com account yaxiaiyuting (keyring)` / `The token in keyring is invalid` | 即使网络恢复，`gh release create` 也需要先 `gh auth refresh` |

**因此：**

- **tag `v3.2.0-gpl` 只存在于本地，尚未推送**。它不是「已发布的 tag」——
  按纪律，**未推送的 tag 不属于「已发布」，本版不去移动它**；网络恢复后直接
  `git push origin master && git push origin v3.2.0-gpl` 即可。
- **release 未创建**，也没有 draft。
- 已发布的 tag（`v3.1.0-gpl` 及更早）**一个都没有被移动**：
  `git tag --sort=-v:refname | head -3` ⇒ `v3.2.0-gpl / v3.1.0-gpl / v3.0.0-gpl`，
  且 `v3.1.0-gpl` 仍指向 `08e2641`（本版所有提交都在它之后）。

## 3. 网络恢复后的收尾命令（逐条可照抄）

```bash
cd /home/duanjb666/deepseek/Ncrust
git push origin master
git push origin v3.2.0-gpl
# 自证 tag 落在产出这批 APK 的那个提交上
git show v3.2.0-gpl:app/build.gradle.kts | grep -E '^        version(Code|Name)'
gh release create v3.2.0-gpl \
  /home/duanjb666/deepseek/dist/Ncrust-v3.2.0-gpl-release.apk \
  --repo yaxiaiyuting/Ncrust --title "Ncrust v3.2.0-gpl" \
  --notes-file docs/verification/v3.2.0/CHANGELOG-v3.2.0.md
```

（若 `gh` 报凭据无效：先 `gh auth refresh -h github.com`，再执行上面两条 push 与 release。）
