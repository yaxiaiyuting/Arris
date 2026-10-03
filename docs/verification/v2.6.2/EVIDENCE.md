# v2.6.2 · 证据索引（EVIDENCE）

> 哪条结论对应哪个文件。**每条结论都能顺着一行命令复跑**，不需要信任本文档。
> 设备：PCL110（OPPO / Android 16）、S6（三星 G9209 / Android 7.0）、WGR-W09（华为平板 / Android 12）。

---

## 1. 复现与根因

| 结论 | 证据文件 | 怎么复核 |
|---|---|---|
| QQ 搜索响应里 `album` 对象的字段集是 `{id, mid, name, pmid, subtitle, time_public, title, title_highlight}` —— **`mid` 一直在** | `probe-raw/probe-album-id-collision.out.txt` / `.json` | `python3 tools/probe-album-id-collision.py`（匿名、无 cookie） |
| **`mid` 是身份、`pmid` 是封面照片 id**：QQ 专辑接口按 `mid` 命中；按 `pmid` 只是服务端碰巧容忍；按数字 `id` 直接 `code 104400` | `probe-raw/probe-album-detail-identity.out.txt` | `python3 tools/probe-album-detail-identity.py` |
| QQ 的数字 `album.id` 拿去查 ncm 会**跳到另一张真专辑**（`22276` → 陈小云《百万金曲…》；`7879` → 邓杰《爱的供养》），而不是"查不到" | `probe-raw/probe-album-cross-domain.out.txt` | `python3 tools/probe-album-cross-domain.py` |
| 真机复现（修复前）：PCL110 QQ《葡萄成熟时》→《爱的供养》/ 邓杰 | `screenshots/before-pcl-01-qq-song-menu.png` → `before-pcl-02-goto-album-result.png` | 见 `probe-album-jump.md` §1.1 |
| 真机复现（修复前）：S6 QQ《富士山下》→《百万金曲 陈小云2 苦恋梦 免失志》/ 陈小云 | `screenshots/before-s6-01-qq-song-menu.png` → `before-s6-02-goto-album-result.png` + `probe-raw/s6-before-{menu,album}-tree.txt` | 同上 §1.2 |
| 同机 A/B：同一台 S6、同一首歌、只换数据来源 —— ncm 那一行**正确**（无回归） | `screenshots/before-s6-03-netease-album-ok.png` + `probe-raw/s6-before-netease-album-tree.txt` | 同上 §4 |
| **修复前这条路径零日志**：`grep -icE "AlbumNav\|ArtistNav" logcat-before-pcl-album-jump.txt` = **0**，而同一次抓取里 `NcrustHttpTiming` 正常输出 | `logcat-before-pcl-album-jump.txt` | `probe-logcat.md` §2 |
| 修复前的错误请求路径：`/api/v1/album/7879` —— `7879` 是 **QQ** 的 album.id，`/api/v1/album/` 是**ncm**的接口 | 同上 | `probe-logcat.md` §1.1 |
| 真机上"老缓存"的真实形状：S6 `ncrust_playback_state` 队列第 847 项 `al` 的 key 集合只有 `{picUrl}` | `probe-raw/s6-persisted-qq-queue-entry.json` | `adb shell su -c "cat …/ncrust_playback_state.xml"`（见 `probe-album-jump.md` §2） |
| 第三种数据形态：`MainActivity.kt:944-958` 冷启动从 ViewModel 恢复时构造 `AlbumItem(id=null, name="", picUrl=artwork)` | `probe-album-jump-static.md` §2.3 的表 | 读源码 |

---

## 2. 修复后的验证（release 包，sha256 `10fd8204…`）

| 结论 | 证据文件 |
|---|---|
| PCL110 搜索页入口：QQ 曲目 → **QQ《What's Going On...?》** | `screenshots/after-pcl-01-search-qq-song-menu.png`、`after-pcl-02-qq-goto-album-ok.png` |
| PCL110 库页入口：**老缓存** QQ 曲目 → **跳搜索**（关键词 = 专辑名），没跳错专辑 | `screenshots/after-pcl-02-library-oldcache-search-fallback.png` |
| PCL110 A/B：ncm 曲目 → **ncm《U 87》**（14 首 / 默认播放源：ncm） | `screenshots/after-pcl-03-netease-album-ok.png` |
| S6 搜索页入口：QQ 曲目 → **QQ《What's Going On...?》**（10 首，全部 qm 源） | `screenshots/after-s6-01-qq-song-menu.png`、`after-s6-02-qq-goto-album-ok.png` |
| S6 库页入口：**老形状**（`al` 只有 `picUrl`）→ 跳搜索 + **提示**，关键词回落到「曲名 + 艺人名」 | `screenshots/after-s6-03-oldcache-search-fallback.png`、`after-s6-04-oldcache-search-toast.png`（toast 原文「未找到该专辑的准确身份，已为你搜索」） |
| WGR-W09 搜索页入口：QQ 曲目 → **QQ《What's Going On...?》** | `screenshots/after-wgr-02-qq-goto-album-ok.png` |
| 五个 `AlbumNav` 日志原文（Direct 与两种降级各若干条） | `logcat-after-pcl-album-jump.txt`、`logcat-after-pcl-library-oldcache.txt`、`logcat-after-s6-album-jump.txt`、`logcat-after-s6-oldcache.txt`、`logcat-after-wgr-album-jump.txt` |
| 三台设备的安装版本 / 场景总表 / 未验证缺口 | `verification/DEVICE-VERIFICATION.md` |

---

## 3. 静态与接口审计

| 结论 | 证据文件 |
|---|---|
| 逐条回答任务书 §2.2 六问（映射层丢字段的位置、路由不带 source、**没有回落**、老 DTO 缺字段、与 v2.6.1 同构、9 个入口 1 个出口） | `probe-album-jump-static.md` |
| 值域与撞号矩阵（两个编号空间互不相通） | 同上 §3 + `probe-raw/probe-album-cross-domain.out.txt` |
| 「入口 9 个、出口 1 个」的穷举命令与结果 | 同上 §2.6 |
| WGR-W09 修复前**未取到干净复现**的原因（键盘遮挡 + 折叠态卡的命中带） | `probe-album-jump.md` §5 |

---

## 4. 构建与测试

| 结论 | 证据文件 |
|---|---|
| `./gradlew clean testDebugUnitTest lint assembleDebug assembleRelease` → BUILD SUCCESSFUL（4m15s） | `verification/gradle-fullbuild-round1.log` |
| 最终一轮（含 `AlbumNav` 成功日志那一行）→ BUILD SUCCESSFUL（2m10s） | `verification/gradle-fullbuild-final.log` |
| 单测 111 suites / 1525 tests / 0 failures / 0 errors / 0 skipped | 同一批日志（`app/build/test-results/testDebugUnitTest/`） |
| lint **0 errors / 0 Fatal** / 8 warnings（其余被既有基线过滤） | `verification/lint-results-debug.txt` / `.xml` |
| versionCode 三源交叉校验（fetch 后）：tag 49 / dist badging 49 / 仓库上一版 49 ⇒ **50** | `verification/next-version.txt` |
| 产物的 versionCode/versionName 与源码一致 | `verification/version-check.txt`、`verification/HEAD-vs-artifact.txt` |

---

## 5. 可重跑的探针脚本（在**仓库外**的 `ncrust-gpl/tools/`，按本仓库惯例不入 git）

| 脚本 | 回答什么 |
|---|---|
| `probe-album-id-collision.py` | QQ `album` 对象有哪些字段、三个身份候选各是什么 |
| `probe-album-detail-identity.py` | QQ 专辑接口到底认哪个字段当身份（`mid` / `pmid` / 数字 id 各请求一次） |
| `probe-album-cross-domain.py` | QQ 数字 album id 拿去查 ncm 会落到哪张专辑（撞号矩阵） |

三个脚本都**不依赖账号与 cookie**，匿名可复现，只读公开接口。
