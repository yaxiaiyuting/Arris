# v2.6.2 探针 · 真机复现（QQ 曲目「转到专辑」）

> 修复前版本：**S6 = `v2.6.0-gpl`（versionCode 48）**，**PCL110 = `v2.6.1-gpl`（versionCode 49）**，
> **WGR-W09 = `v2.6.1-gpl`**。
> 两台设备各自独立复现，**页面上看到的错专辑不是同一张** —— 说明这不是"某个固定 fallback 值"，
> 而是"QQ 的数字 id 恰好命中了 ncm 的哪张专辑"。
> 方法：`adb shell input` 驱动 + `uiautomator dump` 读语义树（坐标由当刻布局算出，不写死）。

---

## 1. 最短复现路径

> 搜索 `Eason Chan`（或中文 `富士山下`）→ 结果里**同时**出现 ncm 与 qm 两条同名曲目 →
> **长按 QQ 那一行** → 二级菜单 → 点「转到专辑」→ 落地页是一张**毫不相干的**专辑。

### 1.1 PCL110（OPPO / Android 16 / `v2.6.1-gpl` / versionCode 49）

| 项 | 值 |
|---|---|
| 曲目 | 《葡萄成熟时》/ 陈奕迅 / **qm** / 专辑 `U 87` |
| QQ `album.id` | `7879` |
| 落地页 | **《爱的供养》/ 邓杰**（发行 2011-05-20，13 首歌曲） |
| 稳定性 | **2/2** |

证据：
- `screenshots/before-pcl-01-qq-song-menu.png`（菜单：曲目头是 `葡萄成熟时 / 陈奕迅 / U 87`，
  菜单项 `转到专辑` 在最后第三行）
- `screenshots/before-pcl-02-goto-album-result.png`（落地页《爱的供养》/ 邓杰，**页面完全正常**）
- `logcat-before-pcl-album-jump.txt` —— 这一条是硬证据：

```
I NcrustHttpTiming: path=/api/v1/album/7879 ttfb=305ms body=3ms total=310ms
I NcrustHttpTiming: path=/api/v1/album/7879 ttfb=201ms body=3ms total=205ms
I NcrustHttpTiming: path=/api/v1/album/7879 ttfb=295ms body=5ms total=302ms
```

`7879` 是 **QQ 的 `album.id`**，而 `/api/v1/album/` 是**ncm**的接口 —— 一行日志同时坐实了
根因的 ②③ 两环（不看 source、老路由写死 ncm）。

### 1.2 S6（三星 G9209 / Android 7.0 / `v2.6.0-gpl` / versionCode 48）

| 项 | 值 |
|---|---|
| 曲目 | 《富士山下》/ 陈奕迅 / **qm** / 专辑 `What's Going On...?` |
| QQ `album.id` | `22276` |
| 落地页 | **《百万金曲 陈小云2 苦恋梦 免失志》/ 陈小云**（发行 2010-01-03，13 首歌曲） |
| 稳定性 | **2/2** |

证据：
- `screenshots/before-s6-01-qq-song-menu.png`（菜单：曲目头是 `富士山下 / 陈奕迅 / What's Going On...?`）
- `screenshots/before-s6-02-goto-album-result.png`（落地页《百万金曲 陈小云2 苦恋梦 免失志》）
- `probe-raw/s6-before-menu-tree.txt` / `probe-raw/s6-before-album-tree.txt`（语义树原文：
  菜单里 `转到专辑` 在 `y=2480`；落地页标题节点是 `百万金曲 陈小云2 苦恋梦 免失志`，
  副标题 `陈小云`（`clickable=true`），`13首歌曲`）

**两台设备、两首不同的歌、两张不同的错专辑** —— 这排除了"某个写死的 fallback 值"，
与 `probe-album-cross-domain.py` 的接口预测**逐字对上**（见 `probe-raw/probe-album-cross-domain.out.txt`）。

---

## 2. 第二种症状：**点了没反应**

同一台 S6、同一个菜单、同一组坐标，**只把数据换成"老队列里恢复出来的 QQ 曲目"**：

| 让 QQ《稻香》成为当前曲目的方式 | `song.album` 的形状 | 结果 |
|---|---|---|
| 在搜索结果里点一下那一行（新鲜加载） | `{"id":36062,"mid":"002Neh8l0uciQZ","name":"稻香"}` | 走 ncm ⇒ `404`（错页面） |
| **冷启动从 `ncrust_playback_state` 恢复** | `{"picUrl":"…T002R500x500M000002Neh8l0uciQZ_3.jpg"}` | **静默无反应**（`albumId == null`） |

第二行的形状**不是我构造的**：它是 S6 真机上**真实存在**的队列第 847 项，
原文见 `probe-raw/s6-persisted-qq-queue-entry.json`：

```json
{
  "al": { "picUrl": "https://y.qq.com/music/photo_new/T002R500x500M000002Neh8l0uciQZ_3.jpg" },
  "ar": [ { "name": "周杰伦" } ],
  "id": 4611686018427837109,
  "media_id": "0020wJDo3cx0j3",
  "name": "稻香",
  "source": "qqmusic",
  "mid": "003aAYrm3GE0Ac"
}
```

`al` 的 key 集合是 **`{picUrl}`** —— 没有 `id`，没有 `name`。
把它放回 `ncrust_playback_state` / 冷启动，mini bar 正常显示「稻香 / 周杰伦 / qm」，
点菜单里的「转到专辑」**页面完全不变、Toast 也没有、logcat 一条不留**。

> **这一档比"跳错专辑"更难被发现**：没有任何网络请求，所以连 `NcrustHttpTiming` 都没有。
> 修复前这条路径的 release 包**零日志**（见 `probe-logcat.md`）。

---

## 3. 老缓存的形状从哪来（逐条溯源）

不是"可能存在的历史数据"，是**当前真机上就有的**四类：

| 来源 | 代码位置 | `album` 的形状 |
|---|---|---|
| 老队列（本探针 §2 用它复现） | `ncrust_playback_state` / `queue` | `{picUrl}`（连 `id` 都没有） |
| ViewModel 状态恢复 | `MainActivity.kt:944-958` | `AlbumItem(id = null, name = "", picUrl = artwork)` |
| 搜索历史重开 | `library/SearchHistoryMigration.kt:103` | `AlbumItem(id = null, name = null, picUrl = item.coverUrl)` |
| 聚合器合成候选 | `crosssource/CatalogAggregator.kt:703` | `AlbumItem(id = null, name = albumName, picUrl = null)` |

---

## 4. A/B 对照：**症状随数据来源切换，不随设备切换**

同一台 S6、同一个搜索页、同一个长按手势、同一个菜单项，唯一变量是"那一行属于哪个源"：

| 点的是哪一行 | 曲目头（菜单里） | 落地页 | 判定 |
|---|---|---|---|
| **qm**《富士山下》 | `陈奕迅 / What's Going On...?` | 《百万金曲 陈小云2 苦恋梦 免失志》/ 陈小云 | ✗ **错专辑** |
| **ncm**《富士山下》 | `陈奕迅 / What's Going On…?` | 《What's Going On…?》/ 陈奕迅（17 首，默认播放源：ncm） | ✓ **正确，无回归** |

证据：`screenshots/before-s6-02-goto-album-result.png`（QQ 行 ⇒ 陈小云）
与 `screenshots/before-s6-03-netease-album-ok.png` + `probe-raw/s6-before-netease-album-tree.txt`（ncm 行 ⇒ 正确）。

这与 v2.6.1 的艺人 P0 是**同一个方法论**：一轮之内只改一个变量，排除了"平台差异/偶发"。

---

## 5. 第三台设备 WGR-W09 的诚实记录（**未取到干净的修复前复现**）

WGR-W09（华为平板 / Android 12 / `v2.6.1-gpl`）**没有 root**，无法像 S6/PCL110 那样
把 `ncrust_playback_state` 清空来卸掉折叠态播放器卡，因此遇到了两个**与本 P0 无关**的阻碍：

1. **软键盘遮挡底部菜单行**：搜索框保持焦点时百度输入法占住屏幕下半部，
   `转到专辑`（该机语义树 `y=1432`）整个落在键盘之下。第一次点击之所以"没反应"，
   根因是点在键盘上，不是点在菜单上 —— 这一条已经用 `加入歌单`（`y=648`，在键盘之上）
   能正常打开"加入歌单"面板**证伪了"菜单整体不可点"**。
2. **折叠态播放器卡的命中带**：关闭键盘后，同一张菜单里
   `y=648`（加入歌单）可点，而 `y>=1320`（转到歌手/转到专辑/单曲信息）打不到；
   该机折叠态 mini bar 位于 `y≈1408-1520`。
   **这与 `AGENTS.md`「Compose 触摸陷阱」第 2/5/6 条记录的"死带"同源**，
   是**既有缺陷、与本 P0 无关**，本版不修，已记入未修清单。

原始语义树：`probe-raw/wgr-before-search-tree.txt`、`probe-raw/wgr-before-menu-tree.txt`、
`probe-raw/wgr-before-album-tree.txt`。
WGR-W09 的**修复后**验证在 release 包上做（见 `verification/DEVICE-VERIFICATION.md`）。

> **不伪造**：本文件不声称在 WGR 上取到了修复前的错误专辑落地页。取到的是"菜单可点性被
> 两个已知的非本 P0 因素遮蔽"，以及 `加入歌单` 的可点性反证。S6 与 PCL110 两台已足够定性。

---

## 6. 复现用的原始命令（可重跑）

```bash
# 0) 卸掉折叠态播放器卡（否则菜单最后几行落在死带里）——S6/PCL110 已 root
adb -s <serial> shell am force-stop com.takahashirinta.ncrust
adb -s <serial> shell su -c "cp /data/data/com.takahashirinta.ncrust/shared_prefs/ncrust_playback_state.xml /sdcard/ncrust_playback_state.bak.xml"
adb -s <serial> push /tmp/empty_state.xml /sdcard/empty_state.xml
adb -s <serial> shell su -c "cp /sdcard/empty_state.xml /data/data/com.takahashirinta.ncrust/shared_prefs/ncrust_playback_state.xml"
adb -s <serial> shell am start -n com.takahashirinta.ncrust/.MainActivity

# 1) 搜索 + 长按 QQ 结果行 + 点「转到专辑」
adb -s <serial> shell input tap <searchBox> ; adb -s <serial> shell input text "Eason%sChan"
adb -s <serial> shell input swipe <x> <y> <x> <y> 1200      # 长按
adb -s <serial> shell input tap <转到专辑坐标>                 # 坐标由 ui-drive.sh dump 现算

# 2) 取证
adb -s <serial> shell screencap -p /sdcard/_s.png
adb -s <serial> shell logcat -d -v threadtime --pid=$(adb -s <serial> shell pidof com.takahashirinta.ncrust)
```

`ui-drive.sh`（本目录 `verification/`，从 v2.6.1 原样复制）提供
`dump` / `find "<文字>"` / `tap "<文字>"`：坐标来自当刻语义树，不写死。
