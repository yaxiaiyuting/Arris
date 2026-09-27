# v3.0.0-gpl 发布记录（回填）

> 本文件在**打 tag 与创建 draft release 之后**补写，只记录事实：tag 对象、产物 digest、
> draft URL、以及「已发布的 tag 一个都没被移动」的逐条复核。
> 它是 docs-only 提交，**不含任何 app/ 源码改动** —— 所以 tag 指向的仍是产出这批 APK 的那个提交。

## 1. tag

```
$ git tag -a v3.0.0-gpl -m "v3.0.0-gpl (versionCode 53) ..."
$ git show v3.0.0-gpl:app/build.gradle.kts | grep -E "^\s+(versionCode|versionName)"
        versionCode = 53
        versionName = "3.0.0-gpl"
$ git log --oneline -1 v3.0.0-gpl
0b7563d docs(v3.0.0): 修正帧时间记录的采集差错（五个开关的状态）并补终版产物的对照

$ git rev-parse v3.0.0-gpl^{commit}
（见 §4 的远端一致性复核）
```

**打 tag 前的三条自检**（AGENTS.md 的 v2.1.3 教训）：
1. `git status --short` 为空；
2. `git show HEAD:app/build.gradle.kts` 的 versionCode/versionName = **53 / 3.0.0-gpl**；
3. tag 落在 `build: 升级至 v3.0.0-gpl (versionCode 53)` **之后**的提交上（`git log` 可验证）。

## 2. 产物

| 项 | 值 |
|---|---|
| 文件 | `Ncrust-v3.0.0-gpl-release.apk`（10 141 124 字节） |
| sha256 | `6d45737c2f3971be2d9a4784358235fc31ffe01010e3572aba118024d4042bff` |
| `aapt2 dump badging` | `versionCode='53' versionName='3.0.0-gpl'`，`minSdkVersion:'24'` |
| 构建命令 | `./gradlew clean testDebugUnitTest lint assembleDebug assembleRelease`（全绿）+ 之后的增量 `assembleRelease` |

**两次 release 构建的整包哈希不同（`892450cd…` vs `6d45737c…`），但源码相同**：
解压后 `classes.dex` / `AndroidManifest.xml` / `resources.arsc` 与文件清单**完全一致**，
差异只来自签名时间戳与 zip 元数据。
⇒ 全部真机数据对同一份源码有效。**以后比「产物是不是同一份」要比 dex，不要比整包 sha256。**

## 3. draft release

```
$ ghx.sh release view v3.0.0-gpl --repo yaxiaiyuting/Ncrust --json tagName,isDraft,name,assets
tag:   v3.0.0-gpl
draft: True
name:  v3.0.0-gpl · 音频特征驱动的动效（无 FFT）+ 取消自动降级
asset: Ncrust-v3.0.0-gpl-release.apk 10141124
```

创建命令（draft，未发布；由用户 smoke-test 后手动发布）：

```
ghx.sh release create v3.0.0-gpl --repo yaxiaiyuting/Ncrust --draft \
  --title "v3.0.0-gpl · 音频特征驱动的动效（无 FFT）+ 取消自动降级" \
  --notes-file /tmp/relnotes.md dist/Ncrust-v3.0.0-gpl-release.apk
```

## 4. 已发布的 tag 一个都没被移动（逐条复核）

`git ls-remote --tags origin` 与本地 `git rev-parse <tag>^{commit}` 逐条比对：

| tag | commit |
|---|---|
| v2.8.0-gpl | `06782e28c7ab280302a36893dffc1e53b19f8807` ✅ |
| v2.6.2-gpl | `744ff0e3d895c4d1e1ca576bc979705d0387b698` ✅ |
| v2.6.1-gpl | `3ac0cacd62f54d07871e7d75e45762df8027d817` ✅ |
| v2.6.0-gpl | `793702d6e18b32ecf60d8819de255b6422f207ac` ✅ |
| v2.5.6-gpl | `fb2aef1f3cb3b33f45bf668de86a0492fa4940d0` ✅ |
| v2.5.5-gpl | `de304d3835f8defa48d6d33e0d2a6029b624d563` ✅ |
| v2.5.4-gpl | `cc30c753a6792fc97e24bce328e2570e3c128aac` ✅ |
| v2.5.3-gpl | `7b0c663a5a50be467b23844e89d189dade010b25` ✅ |
| v2.5.2-gpl | `9e0f67ed03a10fcb1703075c33ba77da9af29572` ✅ |

（`v2.9.0-gpl` 与 `v3.0.0-gpl` 都是 **draft**，从未发布；`v2.1.1/v2.1.2` 的 draft 早已删除。）

## 5. 未做的发布动作

- **没有** `gh release edit --draft=false`：按项目惯例，draft 由用户 smoke-test 后手动发布。
- **没有**改动 `applicationId` / 权限 / 华为控制中心卡片。
- **没有**移动任何已发布的 tag。
