# v2.6.2 · 真机验证记录（release 包）

> 验证对象：**`dist/Ncrust-v2.6.2-gpl-release.apk`**
> sha256 `10fd82049921e908e4e645564f03260a424e8ebce8a8c1a47153ce53e254ade2`
> （= `app/build/outputs/apk/release/app-release.apk`，见 `HEAD-vs-artifact.txt`）
> `aapt2 dump badging` → `versionCode='50' versionName='2.6.2-gpl'`
>
> 三台设备**都装的是这一个包**（`adb install -r`，`dumpsys package` 复核 `versionName=2.6.2-gpl`）。

---

## 1. 结果总表

| # | 设备 | 系统 | 场景 | 结果 | 证据 |
|---|---|---|---|---|---|
| 1 | PCL110（OPPO） | Android 16 | **QQ 曲目**（搜索页入口）→「转到专辑」 | ✅ 进 **QQ《What's Going On...?》** | `after-pcl-02-qq-goto-album-ok.png` + `logcat-after-pcl-album-jump.txt` |
| 2 | PCL110 | Android 16 | **老缓存 QQ 曲目**（库页入口，`al` 无 `mid`）→「转到专辑」 | ✅ **跳搜索**，关键词 = 专辑名，**没跳错专辑** | `after-pcl-02-library-oldcache-search-fallback.png` + `logcat-after-pcl-library-oldcache.txt` |
| 3 | PCL110 | Android 16 | **ncm 曲目** →「转到专辑」（A/B 对照，无回归） | ✅ 进 **ncm《U 87》**（14 首 / 默认播放源：ncm） | `after-pcl-03-netease-album-ok.png` +（同一 `logcat-*` 文件） |
| 4 | S6（三星 G9209） | Android 7.0 | **QQ 曲目**（搜索页入口）→「转到专辑」 | ✅ 进 **QQ《What's Going On...?》**（10 首，全部 qm 源） | `after-s6-01-qq-song-menu.png`、`after-s6-02-qq-goto-album-ok.png` + `logcat-after-s6-album-jump.txt` |
| 5 | S6 | Android 7.0 | **老缓存 QQ 曲目**（库页入口，`al` 只剩 `picUrl`）→「转到专辑」 | ✅ **跳搜索 + 提示**，关键词回落到「曲名 + 艺人名」 | `after-s6-03-oldcache-search-fallback.png`、`after-s6-04-oldcache-search-toast.png` + `logcat-after-s6-oldcache.txt` |
| 6 | WGR-W09（华为平板） | Android 12 | **QQ 曲目**（搜索页入口）→「转到专辑」 | ✅ 进 **QQ《What's Going On...?》** | `after-wgr-02-qq-goto-album-ok.png` + `logcat-after-wgr-album-jump.txt` |

**修复前 vs 修复后（同一台 S6、同一首歌、同一个手势）**

| | 落地页 | 日志 |
|---|---|---|
| 修复前（`v2.6.0-gpl`） | 《百万金曲 陈小云2 苦恋梦 免失志》/ **陈小云** | **零日志**（`logcat-before-pcl-album-jump.txt` 里只有 ncm 的 `/api/v1/album/7879`） |
| 修复后（`v2.6.2-gpl`） | 《What's Going On...?》/ **陈奕迅** | `I AlbumNav: 转到专辑 source=qqmusic id=004Z85XP1c25b7 song=…` |

---

## 2. 逐条对照任务书 §3.4 的验收

| 验收点 | 结论 | 依据 |
|---|---|---|
| QQ 曲目「转到专辑」跳到正确专辑 | ✅ | #1 #4 #6 —— **三台设备**，落地页的曲目全部标 `qm` |
| ncm 曲目不回归 | ✅ | #3 —— 同机 A/B，落地页《U 87》/ 14 首 / `默认播放源：ncm` |
| 所有入口均正确 | ✅（结构上 + 抽测） | 全树只有 **1 个动作构造点**（`MainActivity.kt:2613`），9 个宿主共用同一个 `SongMenuSheet` 与同一个出口。抽测了 **搜索页**（#1/#6，QQ）+ **库页**（#2/#5，老缓存）两个宿主，以及**播放器卡的二级菜单**（探针阶段在 S6/PCL110 上取到过菜单展开态，见 `screenshots/before-*-01-*-song-menu.png`） |
| 老缓存跳搜索，不跳错误专辑 | ✅ | #2（PCL110 真机老条目）与 #5（S6 上把一条真条目的 `al` 打回老形状）。两次都切到搜索 tab、没有打开任何专辑页 |
| 单测覆盖各种路径 | ✅ | 59 条新增用例（见 `CHANGELOG-v2.6.2.md` §5） |
| release 包真机验证 | ✅ | 本文件，三台设备全部装的是 `Ncrust-v2.6.2-gpl-release.apk`（sha256 见页首） |

---

## 3. 逐条日志原文（`adb logcat -s AlbumNav`）

```
# PCL110 · 搜索页入口 · 新鲜的 QQ 曲目 ⇒ Direct
09-27 00:36:58.680 26055 26055 I AlbumNav: 转到专辑 source=qqmusic id=004Z85XP1c25b7 song=qqmusic:4611686018427648582

# PCL110 · 库页入口 · 老收藏的 QQ 曲目（al 无 mid）⇒ 跳搜索
09-27 00:35:42.501 26055 26055 I AlbumNav: 转到专辑降级为搜索 reason=AMBIGUOUS_NUMERIC_ID song=qqmusic:4611686018427648582 keyword=What's Going On...?

# S6 · 搜索页入口 · 新鲜的 QQ 曲目 ⇒ Direct
09-27 00:24:02.725 25922 25922 I AlbumNav: 转到专辑 source=qqmusic id=004Z85XP1c25b7 song=qqmusic:4611686018427648582

# S6 · 库页入口 · 老形状（al 只有 picUrl）⇒ 跳搜索，关键词回落到「曲名 + 艺人名」
09-27 00:28:02.634 26654 26654 I AlbumNav: 转到专辑降级为搜索 reason=MISSING_ALBUM_META song=qqmusic:4611686018997988687 keyword=Balada na Favela Krishan Singh

# WGR-W09 · 搜索页入口 · 新鲜的 QQ 曲目 ⇒ Direct
09-27 00:33:38.222  2248  2248 I AlbumNav: 转到专辑 source=qqmusic id=004Z85XP1c25b7 song=qqmusic:4611686018427648582
```

三台设备的 QQ 曲目拿到的都是 **`004Z85XP1c25b7`**（QQ 的 albumMID）——
`probe-album-cross-domain.py` 实测它与 ncm 的 `6451`（同名专辑的真身）是**两个不同的值**，
而修复前被送过去的是 `22276`（QQ 域数字 id ⇒ 陈小云那张）。

---

## 4. 验证方法（可重跑）

```bash
# 安装
adb -s <serial> install -r dist/Ncrust-v2.6.2-gpl-release.apk
adb -s <serial> shell dumpsys package com.takahashirinta.ncrust | grep versionName

# ① 日志：**必须在动手之前**开一个服务端过滤的 logcat。
#    PCL110 的 main ring buffer 只有 256 KiB，动作做完再 `logcat -d` 已经滚掉了
#    （本版踩过这个坑：`logcat -c` + 动作 + `logcat -d` 三次都拿到空文件）。
adb -s <serial> shell logcat -v threadtime -s AlbumNav:V > /tmp/albumnav.log &

# ② 驱动：ui-drive.sh（坐标来自当刻语义树，不写死）
docs/verification/v2.6.2/verification/ui-drive.sh <serial> dump
docs/verification/v2.6.2/verification/ui-drive.sh <serial> find "转到专辑"

# ③ 老缓存形状怎么造（S6/PCL110 已 root）
#    先正常「加入库」一首 QQ 曲目 ⇒ 再把 ncrust_library.xml 里那条的
#    al 改回 {"picUrl": …}（= v2.6.2 之前的真实落盘形状）⇒ force-stop 后冷启动。
```

---

## 5. 验证过程中的两个**环境**问题（已绕开，如实记录）

1. **WGR-W09 一度锁屏 / 黑屏**：`screencap` 返回 0 字节、通知栏抢焦点。
   绕开方式 `input keyevent 224` + `wm dismiss-keyguard`（**不是**本 P0 的阻塞，
   也不影响另外两台设备的结论）。
2. **WGR-W09 的软键盘遮住底部菜单行**：搜索框保持焦点时输入法占住下半屏，
   `转到专辑`（该机语义树 `y=1432`）整个落在键盘之下。绕开方式是把设备**转到竖屏**
   （1600×2560，菜单变高、`转到专辑` 落到 `y=2392`），再点即中。
   同一台机器上 `y=648` 的「加入歌单」可点，证明**菜单本身是可点的** ——
   底部那几行被折叠态播放器卡的命中带吃掉是**既有缺陷**（`AGENTS.md` 触摸陷阱第 2/5/6 条），
   与本 P0 无关，已记入未修清单。

---

## 6. 未验证缺口（**不伪造**）

| 缺口 | 说明 |
|---|---|
| 9 个宿主**逐个**真机点一遍 | 只抽测了 搜索页 / 库页 / 播放器卡二级菜单 三个。剩下的（首页 / 歌单 / QQ 歌单 / 本地歌单 / 专辑页 / 艺人页）**结构上**与抽测的完全同源：都经 `MainActivity.showSongMenu` 汇到同一张 `SongMenuSheet`，`SongItem` 也来自同一批映射函数；但**没有逐个点过** |
| 「收藏专辑表新增 `albumMid`」的**真机迁移** | 单测覆盖了三种落盘形状，但真机上收藏专辑列表里目前**全是 ncm 专辑**（`mid` 恒为 null），所以「老条目读出来 `mid=null`」这一条只在单测里验证过 |
| QQ 歌单 / 本地歌单 页里的 QQ 曲目 | 未抽测（同上，结构同源） |
| iOS/其他 ROM | 不适用 |

> 关于「跳错专辑」这条 P0 本身：**三台设备 × 三种数据形态（新鲜 / 老收藏 / 老队列）都验过**，
> 修复前的错误落地页也都在同一批设备上取到了对照截图。上面几条缺口是**入口覆盖面**的，
> 不是根因或修复有效性的。
