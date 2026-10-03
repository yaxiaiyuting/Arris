# v2.6.1 真机验证记录（release 包）

> 被验产物：`Ncrust-v2.6.1-gpl-release.apk`
> `sha256 = f331b863cab0af92616611fcf2e93da3d428ba2a3c70ecb178dfa2dc644623d1`
> `versionCode = 49` / `versionName = 2.6.1-gpl`（aapt2 badging 实测）
> 安装方式：`adb install -r`（覆盖安装，**不清数据** —— 这一点很重要：
> 修复要能在**已有旧数据**的真机上生效，而不是只在干净安装上）

## 0. 结论表

| # | 入口 | 设备 | 期望 | 实测 | 证据 |
|---|---|---|---|---|---|
| V1 | 二级菜单「转到歌手」（QQ 曲目，搜索结果长按） | WGR-W09 | 进 **QQ 源的周杰伦** | ✅ 标题「周杰伦」，专辑 43 / 单曲 30，专辑行全标 **qm** | `after-wgr-02-qq-artist-page.png` / `-tree.txt` |
| V2 | 同上 | PCL110 | 同上 | ✅ 标题「周杰伦」，专辑 43 / 单曲 30 | `after-pcl-01-qq-artist-page.png` |
| V3 | 二级菜单「转到歌手」（**ncm**曲目，回归） | WGR-W09 | 进 ncm 周杰伦，**无回归** | ✅ 标题「周杰伦」，专辑 54 / 单曲 60（双源聚合） | `after-wgr-03-netease-artist-tree.txt` |
| V4 | 专辑页副标题（ncm） | WGR-W09 | 进 ncm 周杰伦 | ✅ 专辑 54 / 单曲 60 | `after-wgr-06-album-page-artist-tree.txt` |
| V5 | **播放页托盘作者名**（QQ 曲目，新鲜加载） | PCL110 | 进 QQ 源的这位歌手 | ✅ 「草东没有派对」专辑 3 / 单曲 30，专辑行全标 qm | `after-pcl-03-qq-artist-from-tray.png` |
| V6 | 二级菜单/托盘（**冷启动恢复**的歌，只有名字） | WGR-W09 | **跳搜索 + 提示**（不是静默失败） | ✅ 切到搜索 tab、预填「伍佰 & China Blue」、提示「未找到该歌手的准确身份，已为你搜索」 | `after-wgr-04-search-fallback*.png` / `after-wgr-05-search-fallback-snackbar.png` |
| V7 | 同上 | PCL110 | 同上 | ✅ 预填「草东没有派对」+ 同一条提示 | `after-pcl-02-search-fallback.png` |

**修复前后对照（同一条路径、同一台设备）**：

| | 修复前 | 修复后 |
|---|---|---|
| QQ 曲目二级菜单 → 转到歌手 | **马洪波**（专辑 5 / 单曲 40） | **周杰伦**（专辑 43 / 单曲 30，QQ 源） |
| 冷启动恢复的歌 → 转到歌手 | **毫无反应** | 切搜索 + 预填 + 提示 |

## 1. 日志证据（这条路径**第一次**有日志）

修复前：这四类日志一条都没有（见 `../probe-logcat.md`）。
修复后：

```
WGR-W09:
09-26 23:27:01.799 21860 21860 I ArtistNav: 转到歌手降级为搜索 reason=MISSING_ID song=qqmusic:156374 keyword=伍佰 & China Blue

PCL110:
09-26 23:29:09.254  6163  6163 I ArtistNav: 转到歌手降级为搜索 reason=MISSING_ID song=netease:2035320743 keyword=草东没有派对
```

两条都是 `reason=MISSING_ID`（冷启动恢复的曲目没有艺人 id）。
**走的不是降级时没有日志** —— `Direct` 成功路径刻意不打日志（正常路径不该刷日志），
所以 V1–V5 的 `logcat -s ArtistNav` 是**空**的，这本身就是「没有降级」的证据。

## 2. 复现/验证步骤（可重跑）

设备与分辨率（坐标只对这两台成立；WGR-W09 走语义树找坐标，不写死）：

| 设备 | serial | 分辨率 | Android | uiautomator |
|---|---|---|---|---|
| PCL110（OPPO 手机） | `3B15CD00GB700000` | 1272×2800 | 16 | ❌ 被 SIGKILL，只能读图定位 |
| WGR-W09（华为平板） | `WVQ6R22124000968` | 2560×1600 | 12 | ✅ 可用 |

### PCL110

```bash
S=3B15CD00GB700000
APK=/home/duanjb666/deepseek/ncrust-gpl/dist/Ncrust-v2.6.1-gpl-release.apk
adb -s $S install -r "$APK"

# V2：QQ 曲目二级菜单
adb -s $S shell am force-stop com.takahashirinta.ncrust
adb -s $S shell am start -n com.takahashirinta.ncrust/.MainActivity ; sleep 13
adb -s $S shell input tap 800 2685        # 底部导航「搜索」
adb -s $S shell input tap 636 268         # 搜索框
adb -s $S shell input text "Jay%sChou" ; sleep 10
adb -s $S shell input keyevent KEYCODE_BACK ; sleep 3
adb -s $S shell input swipe 420 1068 420 1068 1000   # 长按 QQ《晴天》
sleep 4
adb -s $S shell input tap 299 2231        # 转到歌手
sleep 9 ; adb -s $S exec-out screencap -p > V2.png
# ⇒ 标题「周杰伦」
```

### WGR-W09

```bash
S=WVQ6R22124000968
U=docs/verification/v2.6.1/verification/ui-drive.sh
$U $S tap "搜索" ; $U $S tap "稻香"          # 或输入关键词
adb -s $S shell input swipe 924 1266 924 1266 1000   # 长按 QQ 行
$U $S dump                                   # 读「转到歌手」的坐标
adb -s $S shell input tap 176 1320
$U $S dump                                   # ⇒ 528 277 周杰伦
```

## 3. 本记录**没有**覆盖的（与 release notes 的未验证清单一致）

| 未验证 | 说明 |
|---|---|
| 搜索页「艺人」tab → 艺人页 | 该 tab 至今只由 ncm 的 `cloudsearch/pc type=100` 填充（探针已证），本版把它改成**显式**带 `MusicSource.NETEASE`，行为与修复前逐字节相同；未单独做真机点击。守卫是 `ArtistRouteContractTest` 的源码扫描 |
| 「单曲信息」页 → 艺人 | 该页**没有**艺人入口（`SongDetailScreen` 里艺人是纯文本），所以没有可验的路径 |
| QQ 歌单详情 / 本地歌单 / 收藏页 长按 → 转到歌手 | 与已验证的「搜索结果长按」走**同一个** `SongMenuSheet` → 同一个 `navigateToArtist`，代码路径唯一；但确实没有逐个页面点过 |
| S6（Android 7.0） | 该机上长按歌曲行被播放器拖拽层吃掉，取不到二级菜单；本版**未**在 S6 上复测 |
| `PlaylistCacheCodec` 老缓存的自愈时序 | 单测覆盖「缺 mid 读成 null」，但「下一次联网刷新写回 mid」没有真机时序证据 |
| 「转到专辑」对 QQ 曲目仍然错 | 本版明确未修（见 `../CHANGELOG-v2.6.1.md`） |
