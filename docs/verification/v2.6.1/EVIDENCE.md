# v2.6.1 证据索引（EVIDENCE）

> 每一条结论 → 它的事实来源。**没有来源的结论不写在这里。**
> 修复版：`v2.6.1-gpl`；被修版本：`v2.6.0-gpl`（versionCode 48，commit `6ae5de1`）。

## A. 接口取证（可匿名复现，无需账号）

| 结论 | 证据文件 | 原始命令 |
|---|---|---|
| QQ `singer[]` 条目**同时**给数字 `id` 与 base62 `mid` | `probe-raw/qq-search-稻香.json`、`-林俊杰.json`、`-陈奕迅.json` | `probe-raw/probe-artist-id-collision.sh` |
| 周杰伦 QQ `singer.id = 4558` / `singer.mid = 0025NhlN2yWrP4` | `probe-raw/probe-artist-id-collision.out.txt` | 同上 |
| ncm `api/artist/4558` = **马洪波**（专辑 1 / 单曲 32） | `probe-raw/probe-artist-id-collision.out.txt` | `curl -A '<UA>' -H 'Referer: https://music.163.com/' https://music.163.com/api/artist/4558` |
| ncm `api/artist/6452` = 周杰伦（专辑 41 / 单曲 568） | `probe-artist-jump-static.md` §3 | `curl 'https://music.163.com/api/search/get/web?s=周杰伦&type=100&limit=3'` |
| 林俊杰 `4286` → 刘子译（0/0）；陈奕迅 `143` → 404 | `probe-raw/probe-artist-id-collision.out.txt` | 同第一行 |
| QQ `singer[]` 字段集 = `{id, mid, name, pmid, title, title_highlight, type, uin}` | `probe-raw/qq-search-*.json`（脚本打印了 `sorted(sg.keys())`） | 同上 |

**这些是外部接口数据，随服务端变化可能失效。** 复现命令与脚本已入库，任何人可重跑；
`docs/verification/v2.4.0/probe-artist-mapping.md:80-83` 也有独立的同向记录
（`ncm周杰伦 id=6452` vs `QQ singerID=4558`，「两个编号空间各自独立」）。

## B. 真机取证（PCL110 / WGR-W09）

| 结论 | 证据文件 |
|---|---|
| WGR-W09：QQ《晴天》二级菜单「转到歌手」→ **马洪波**（专辑 5 / 单曲 40） | `screenshots/before-wgr-02-wrong-artist-mahongbo.png` |
| 同上，**语义树**（可 grep 的机器可读形态） | `probe-raw/wgr-before-wrong-artist-tree.txt` |
| 二级菜单本体（9 项，含「转到歌手」） | `screenshots/before-wgr-01-qq-song-menu.png` |
| PCL110：二级菜单本体（9 项） | `screenshots/before-pcl-01-qq-song-menu.png` |
| 会话开始前 WGR-W09 已停在马洪波页（说明这不是一次性偶发） | `screenshots/before-wgr-00-mahongbo-artist-page.png` |
| PCL110 上托盘显示的当前歌是 QQ《稻香 / 周杰伦》 | `screenshots/before-02-tray-artist-QQ.png` |
| 复现步骤（含精确坐标与分辨率） | `probe-artist-jump.md` §1 / §4 |

### B.1 同机交叉 A/B 的两次运行

| 轮次 | 当前歌来源 | 观测 | 记录位置 |
|---|---|---|---|
| A | 冷启动恢复 | 「转到歌手」无反应 | `probe-artist-jump.md` §5（截图在本轮会话内，未入库；语义树里页面未变化是判据） |
| B | 搜索结果新鲜加载 | 「转到歌手」→ 马洪波 | `screenshots/before-wgr-02-*.png` + `before-pcl-01-*.png` |

> **说明**：A 轮的判定依据是「点击前后 `screencap` 完全一致（同一页面、菜单已关闭、底部托盘歌曲未变）」，
> 它不是一张"能看出没反应"的静态图（静态图只能证明"停在原页"）。因此这里如实标注为
> **过程观测**，并把可复跑的命令写进 `probe-artist-jump.md` §5，而不是拿一张无信息量的截图充数。

## C. 代码事实（`6ae5de1`，可用 `git show` 逐行复核）

| 结论 | 位置 |
|---|---|
| 老路由把音源**写死**成 ncm | `app/src/main/java/com/takahashirinta/ncrust/ui/navigation/NavGraph.kt:196-203` |
| 带音源的两段路由存在，但只有 2 个调用点（都在专辑页，且被 gate 成 ncm） | `NavGraph.kt:215-237`、`AlbumDetailScreen.kt:173-181` |
| 二级菜单「转到歌手」入口 | `MainActivity.kt:2511-2513`（v2.6.0） |
| `resolveAndNavigate` 只取 `artists[0].id`、补 id 用 ncm 接口 | `MainActivity.kt:1838-1864`（v2.6.0） |
| QQ 映射丢掉 `singer.mid` | `qq/QqSongMapper.kt:110`（v2.6.0） |
| 播放页托盘只传裸 `Long` | `ui/player/PlayerCard.kt:123,1518` + `MainActivity.kt:2169`（v2.6.0） |
| 冷启动恢复的曲目 `artists[0].id` 恒为 null | `MainActivity.kt:917-932`（`artists = listOf(ArtistItem(name = artist))`） |
| QQ 合成 id 带 bit62 标志位 | `source/MusicSource.kt:164`（`QQ_ID_FLAG = 1L shl 62`）、`:198-201`（`qqId`） |
| 匹配缓存里存的是**正确**的 6452 ↔ 0025NhlN2yWrP4 | 真机 `ncrust_match_cache.xml`（两台设备一致），原文见 `PROBE-SUMMARY.md` §3.6 |
| 错误结论「QQ 一侧拿不到 singerMID」写在注释里 | `ui/screen/AlbumDetailScreen.kt:170-172`（v2.6.0） |

## D. 「修复前零日志」的取证

| 结论 | 证据 |
|---|---|
| release 包在这条路径上没有任何应用日志 | `probe-logcat.md`（含可重跑命令与判据） |
| 四份全量 logcat 合计 252 MB，其中应用行数 = 0 | 同上；原始日志按该文的理由**未入库** |

## E. 修复后的验证（本文档的另一半）

见 `verification/` 目录与 `PROBE-SUMMARY.md` 的引用；关键产物：

| 产物 | 说明 |
|---|---|
| `verification/gradle-fullbuild-round1.log` | 全量构建（clean + test + lint + assembleDebug + assembleRelease） |
| `verification/gradle-fullbuild-round2-release.log` | 版本号提交之后的正式产物构建（tag 指向的源码） |
| `verification/next-version.txt` | `tools/next-version.sh` 三源交叉校验 |
| `verification/version-check.txt` | tag / APK badging / build.gradle 三源一致性 |
| `verification/after-*.png` + `after-*.txt` | 修复后真机各入口复测 |

## F. 明确的**未**验证项（与 release notes 一致）

1. **S6（Android 7.0 / SM-G9209）未参与本 P0 的结论**：该机上长按歌曲行会被播放器
   拖拽层吃掉，取不到二级菜单。修复后的复测同样未在 S6 上做（理由与处置见 §B 与 release notes）。
2. **QQ 艺人页在 QQ 侧的真实渲染未逐字段核对**：本版验证到「进入的是 QQ 源的
   `singerMID`、页面标题是周杰伦」为止；QQ 艺人页的专辑/单曲内容与官方 App 的逐条对比**没做**。
3. **`PlaylistCacheCodec` 老缓存的自愈路径未在真机上走完**：单测覆盖了「缺 mid 读成 null」，
   但「下一次联网刷新写回 mid」只在单测层面成立，没有真机时序证据。
4. **「转到专辑」对 QQ 曲目仍然错**（同类 bug，本版明确不修）——见
   `probe-artist-jump-static.md` §5。
5. **搜索页「艺人」tab 仍只有 ncm 结果**（缺失功能，不是错误跳转）。

---

## G. 发布物信息（回填，2026-09-26）

| 项 | 值 |
|---|---|
| tag | `v2.6.1-gpl`（**附注 tag**，对象 `9d95ebf1c04c6041b7c423fd6ea454052e55c1d6`） |
| tag → 提交 | `3ac0cacd62f54d07871e7d75e45762df8027d817`（`docs(v2.6.1): HEAD==产物源码自证`） |
| 产出产物的提交 | `5e2accf13a29faa0112f66b844bbb534822c2293` |
| 两者在 `app/` 上的差异 | **空**（tag 提交只动 `docs/`，见 `verification/HEAD-vs-artifact.txt`） |
| draft release | https://github.com/yaxiaiyuting/Ncrust/releases/tag/untagged-b1c544dc415c6af45ae1 |
| draft 的 `tag_name` | `v2.6.1-gpl`（`target_commitish=master`，与该 tag 已指向的提交一致 ⇒ 发布时**不会**新建/移动 tag） |
| release body | `docs/verification/v2.6.1/v2.6.1-release-body.md`（与 draft 上的正文同一份） |

### 附件（sha256 双源一致：本地实测 = GitHub `digest`）

| 文件 | 大小 | sha256 |
|---|---|---|
| `Ncrust-v2.6.1-gpl-release.apk` | 10 075 592 | `f331b863cab0af92616611fcf2e93da3d428ba2a3c70ecb178dfa2dc644623d1` |
| `Ncrust-v2.6.1-gpl-debug.apk` | 30 877 038 | `5d56bbf3df95834f8b830bdf15a2a75404b23cc7a66c2b1fe3f809c93cf344d4` |
| `SHA256SUMS-v2.6.1-gpl.txt` | 190 | `b0127be0dcb74201e5b5fcc477b5a98e67ed03994c9679b4ed62505dffcbc698` |

### 流程（与 v2.6.0 逐条一致）

1. `tools/next-version.sh`（**先 fetch**）三源交叉 → 49；记录在 `verification/next-version.txt`；
2. 全量验证构建（clean + test + lint + assembleDebug + assembleRelease）→ `gradle-fullbuild-final.log`；
3. `build: 升级至 v2.6.1-gpl（versionCode 49）` 提交；
4. 修复与文档提交；
5. 真机验证（两台，release 包）；
6. 工作区干净 + `HEAD == 产物源码` 自证 → 打 tag → push；
7. `gh release create --draft` 附 APK / CHANGELOG / 探针结论 / 测试证据路径 / 未验证缺口。

**未移动任何已发布 tag**：`git ls-remote --tags origin` 实测 `refs/tags/v2.6.1-gpl`
→ `9d95ebf…` → `3ac0cac…`，与本地 `git rev-parse v2.6.1-gpl^{commit}` 相同。
