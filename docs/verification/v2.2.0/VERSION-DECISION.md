# 版本线决策：为什么是独立的 v2.2.0-gpl，而不是并入 v2.1.6

- 决策时间：2026-09-25
- 决策依据：`tools/next-version.sh`（带 fetch）三源交叉验证 + `gh release list` 实测

---

## 1. 先说一个被**实测纠正**的前提

任务书写「如果 v2.1.6 尚未发布且本改动可安全并入，则并入 v2.1.6；否则独立 v2.2.0」。
我在会话开始时的判断是「v2.1.6 尚未打 tag」，**这个判断是错的**，而且错得很有代表性：

| 步骤 | 我做了什么 | 看到了什么 | 结论 |
|---|---|---|---|
| ① | `git tag`（本地） | 最高 `v2.1.5-gpl` | 以为 v2.1.6 未成立 |
| ② | `tools/next-version.sh --no-fetch` | max=36（来源：工作区 build.gradle） | 以为 36 只是未提交的 WIP |
| ③ | **`git fetch --all --tags`** | 出现 **`v2.1.6-gpl`** → `fae47b4` | **v2.1.6 早已打 tag** |
| ④ | `gh release list` | **`Ncrust v2.1.6-gpl … Draft`** | **v2.1.6 已有 draft release** |
| ⑤ | `git log origin/master` | `fae47b4` 就是 `origin/master` 的 HEAD | v2.1.6 已进主线 |
| ⑥ | `ls dist/*.apk` | `Ncrust-v2.1.6-gpl-{debug,release}.apk` = 36 | 制品也已产出 |

**`--no-fetch` 让三源交叉验证里的「tag」这一源变成残缺的。** 脚本本身默认会 fetch，
是我为了省时间加了 `--no-fetch` —— 这正是本仓库 v1.9.2 记录过的那类错误
（「动版本号之前永远先实测」），只是换了一个形式。

## 2. 决策

> **独立发布 `v2.2.0-gpl`，基线从 `v2.1.5-gpl` 改基到 `v2.1.6-gpl`，`versionCode = 37`。**

### 2.1 为什么不能并入 v2.1.6

1. **`v2.1.6-gpl` 的 tag 已经存在并已推送**（`fae47b4`）。把新改动并进去就必须移动这个 tag ——
   而「发布过的 tag 绝不移动」是本仓库的铁律；虽然它目前只是 draft（按 v2.1.3 的先例技术上
   可以删 tag 重建），但那需要**确认该 draft 从未被任何人下载/引用**，属于对他人工作的
   破坏性操作，不在本次自动化任务的授权范围内。
2. **v2.1.6 的 draft release 已经写好了它自己的发布说明**（媒体承重结构改造 / 华为白名单）。
   把我的功能并进去会让一份已经成稿的发布说明混进一个完全不同的功能竖切，
   而任务书第 14 条明确要求**不碰、不扩散**华为控制中心卡片问题。
3. 本次改动是**新功能竖切**（新接口 + 新模型 + 新缓存 + 新页面 + 8 语言文案），
   按语义化版本应当走 minor 号。

### 2.2 为什么必须改基到 v2.1.6（这是本次最关键的一步）

我最初的工作树是从 **`v2.1.5-gpl`** 拉出来的（因为当时误以为 v2.1.6 未成立）。
如果就这样以 `versionCode 37` 发布：

```
v2.1.6-gpl = 36   ← 已发布（draft）给用户装过的话
v2.2.0-gpl = 37   ← 装在 36 之上
```

而 v2.2.0 里**没有** v2.1.6 的改动 ⇒ 安装后会**静默回退** v2.1.6 的
「两条 MediaSession 合并为一条」等修复。这属于**发布事故**，不是风格问题。

因此把 5 个功能提交 `git rebase v2.1.6-gpl`（干净重放，无冲突），
使 `v2.1.6-gpl` 成为 v2.2.0 的祖先：

```
v2.1.6-gpl (fae47b4, 36)
  └─ docs:    探针结论与证据链
     └─ feat:  歌单跨源身份模型
        └─ feat:  世代竞态防护 + 下拉刷新阈值
           └─ feat:  歌单缓存（隔离 + 迁移）
              └─ feat:  只读接口 / 解析 / 仓库
                 └─ feat:  列表页 + 详情页 + 8 语言
                    └─ build: 升级至 v2.2.0-gpl（versionCode 37）  ← tag 打在这里
```

自证（发布前必做）：

```bash
git merge-base --is-ancestor v2.1.6-gpl HEAD && echo "含 v2.1.6 ✓"
git show v2.2.0-gpl:app/build.gradle.kts | grep -E "versionCode|versionName"
```

### 2.3 versionCode = 37 的三源交叉验证（带 fetch）

| 源 | 值 | 依据 |
|---|---|---|
| ① tag 指向的 `app/build.gradle.kts` | **36** | `v2.1.6-gpl` → `fae47b4` |
| ② `dist/*.apk` 的 `aapt2 dump badging` | **36** | `Ncrust-v2.1.6-gpl-debug.apk` / `-release.apk` |
| ③ 工作区 `app/build.gradle.kts` | **36** | v2.1.6 的值 |

`MAX = 36` ⇒ **下一个可用 = 37**。三个源一致，无分歧。

### 2.4 相对 v2.1.6 的改动面

| 类别 | 内容 |
|---|---|
| 新增文件 | 11 个（模型 / 协调器 / 缓存编解码 / 存储 / 解析 / 接口 / 仓库 / 2 个页面 / 1 个阈值 / 1 个类型契约） |
| 新增测试 | 4 个测试类 |
| 修改文件 | `QqRequests.kt`（加只读请求构造）、`Strings.kt` + 8 语言、`NavGraph.kt`、`LibraryScreen.kt`、`MainActivity.kt`（3 行接线）、`build.gradle.kts`（版本号） |
| **未触碰** | `PlaybackService.kt`、`MediaSessionMerge.kt`、`docs/verification/v2.1.6/**`、华为控制中心相关的一切、ncm 歌单的任何写路径 |
