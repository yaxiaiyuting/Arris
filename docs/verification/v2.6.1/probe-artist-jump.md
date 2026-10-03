# v2.6.1 探针 P3：真机复现与 A/B 对照

> 目标版本：`v2.6.0-gpl`（versionCode 48，未修复）。
> 复现日期：2026-09-26。
> 全部结论来自**当次真机操作 + 截图**，不是代码推断。

## 0. 复现结论速览

| 设备 | 通道 | 当前歌曲的数据来源 | 点「转到歌手」的结果 | 稳定性 |
|---|---|---|---|---|
| **WGR-W09**（华为平板，Android 12） | 搜索结果长按 → 二级菜单 | 搜索响应**新鲜**加载 | **跳到「马洪波」**（ncm 4558） | **5/5 稳定复现** |
| **PCL110**（OPPO 手机，Android 16） | 同上 | 同上 | **跳到「马洪波」** | **2/2 稳定复现** |
| **PCL110**（同一台手机） | 全屏播放器点歌名 → 二级菜单 | **冷启动恢复**（`PlaybackStateManager`） | **毫无反应**（菜单关闭，页面不变） | **2/2 稳定复现** |
| S6（G9209，Android 7） | 搜索结果长按 | 新鲜加载 | 长按被播放器手势层吃掉，未取到菜单（见 §6） | — |

> **关键结论：这不是平台差异，是数据来源差异。** 同一台 PCL110 上，
> 「新鲜加载的 QQ 曲目」跳错人、「冷启动恢复的 QQ 曲目」没反应 —— 见 §5 的交叉 A/B。

## 1. 复现步骤（可重跑，固定分辨率）

设备：PCL110 / `3B15CD00GB700000` / 1272×2800 / density 560 / Android 16。
（WGR-W09 用 `uiautomator dump` 按**文字**找坐标，见 §4，不写死坐标。）

```bash
S=3B15CD00GB700000
cd /home/duanjb666/deepseek/ncrust-gpl/Ncrust

# 1) 冷启动，让 last song 从 ncrust_playback_state 恢复
adb -s $S shell am force-stop com.takahashirinta.ncrust
adb -s $S shell am start -n com.takahashirinta.ncrust/.MainActivity ; sleep 12

# 2) 进搜索 tab，点搜索框，输入 ASCII 关键词（中文没法用 input text，见 §6）
adb -s $S shell input tap 800 2685      # 底部导航「搜索」
adb -s $S shell input tap 636 268       # 搜索框
adb -s $S shell input text "Jay%sChou"  # → "Jay Chou"
sleep 9
adb -s $S shell input keyevent KEYCODE_BACK   # 收键盘

# 3) 长按 QQ 结果行《晴天》（周杰伦 · 叶惠美 · qm）
adb -s $S shell input swipe 420 1068 420 1068 1000 ; sleep 4
#    截图确认菜单出现，且第一项是「加入歌单」、倒数第三项是「转到歌手」

# 4) 点「转到歌手」
adb -s $S shell input tap 299 2231 ; sleep 7
adb -s $S exec-out screencap -p > after.png
```

截图：`screenshots/before-pcl-01-qq-song-menu.png`（菜单）、
`before-02-tray-artist-QQ.png`。

## 2. 复现结果：跳到「马洪波」

`screenshots/before-wgr-02-wrong-artist-mahongbo.png`（WGR-W09，语义树同时存档在
`probe-raw/wgr-before-wrong-artist-tree.txt`）：

```
528   277  clickable=false  马洪波
476   343  clickable=false  专辑: 5
606   343  clickable=false  单曲: 40
760   436  clickable=false  双源
...
1146  1491  clickable=true  周杰伦          ← 底部托盘仍在播 QQ 的《稻香》
2202  1491  clickable=false  qm
```

页面标题是**马洪波**、专辑 5 / 单曲 40，而底部播放器里明明是
**稻香 · 周杰伦 · qm**。这与用户报告完全一致。

「马洪波」的身份与来源见 `probe-artist-id-collision.md`：
他就是**ncm 的艺人 4558**，而 4558 恰好是**QQ 的周杰伦**的数字 `singerID`。

## 3. 波及范围（换艺人验证）

`probe-raw/probe-artist-id-collision.out.txt`（接口侧）+ 真机换艺人：

| QQ 歌手 | QQ `singer.id` | 被当成 ncm id 查出来的 | 用户看到什么 |
|---|---|---|---|
| 周杰伦 | `4558` | **马洪波**（专辑 1 / 单曲 32） | **一个有内容的、毫不相干的歌手页** |
| 林俊杰 | `4286` | 刘子译（专辑 0 / 单曲 0） | 一个空艺人页 |
| 陈奕迅 | `143` | **404** | 报错 |

所以「只有周杰伦」不成立 —— 只是周杰伦这一档**看起来最像真的**，
因此也最容易被用户理解成「应用的艺人数据整体是错的」（AGENTS.md 铁律 21）。

## 4. WGR-W09 上的受控复现（按语义树找坐标，不写死）

WGR-W09 的 `uiautomator` 可用，因此这一台用的是**按文字找坐标**的口径：

```bash
S=WVQ6R22124000968
U=docs/verification/v2.6.1/verification/ui-drive.sh   # 与 v2.6.0 同一份口径

$U $S tap "搜索"          # 侧边栏
$U $S tap "稻香"          # 历史 chip → 执行搜索
$U $S dump                # 读结果行的中心坐标
adb -s $S shell input swipe 924 1266 924 1266 1000    # 长按 QQ《晴天》
$U $S dump                # 确认菜单里出现「转到歌手」及其坐标
adb -s $S shell input tap 176 1320                    # 点「转到歌手」
$U $S dump
# ⇒ 528 277 马洪波 / 专辑: 5 / 单曲: 40
```

菜单语义树（长按后）：

```
176   648  clickable=false  加入歌单
208   760  clickable=false  加入本地歌单
160   872  clickable=false  加入库
144   984  clickable=false  插播
176  1096  clickable=false  最后播放
240  1208  clickable=false  添加到下一首播放
176  1320  clickable=false  转到歌手      ← 本 P0 的入口
176  1432  clickable=false  转到专辑
176  1544  clickable=false  单曲信息
```

### 4.1 任务书用词偏差（照本仓库纪律记录）

| 任务书写 | 仓库实际 | 处置 |
|---|---|---|
| 二级菜单项叫「查看艺人」 | 实际文案是 **「转到歌手」**（`Strings.actionGoToArtist`，`ui/i18n/zh_CN.kt:239`）。全仓库 8 个语言文件里**不存在**「查看艺人」 | 按实际文案取证；不改文案（改文案会让 8 个语言文件一起动，与本 P0 无关） |
| 「qm 歌曲（如《稻香》）」 | 《稻香》在 QQ 搜索里存在且有版权态；本次同时用了《稻香》与《晴天》两首，结论一致 | 两首都留了证据 |

## 5. 交叉 A/B：**同一台手机上切换数据来源，症状就切换**

这是本次探针最重要的一个对照。设备、账号、页面、点击坐标**全部不变**，
只改「当前歌曲这个 `SongItem` 是怎么来的」：

| 轮次 | 让 QQ《晴天》成为当前歌曲的方式 | `artists[0]` 的实际内容 | 点「转到歌手」 | 截图 |
|---|---|---|---|---|
| **A** | 冷启动后由 `PlaybackStateManager` 恢复（force-stop → start） | `ArtistItem(name="周杰伦")`，**`id == null`** | **没反应**（菜单关闭、停在原页） | `/tmp/pcl/p28_small.png`（会话内） |
| **B** | 在搜索结果里点一下那行（新鲜 `SongItem`） | `ArtistItem(id=4558, name="周杰伦")` | **跳到马洪波** | `screenshots/before-02-tray-artist-QQ.png` 之后的取证截图 |

两轮之间**只**相隔一次「点搜索结果行」的操作，没有重启、没有换设备、没有清缓存。

### 5.1 这解释了用户报的「手机上没反应 / 平板上跳错」

用户看到的是**同一个 bug 的两种表现**，而分界不是机型而是：
- **平板（WGR-W09）**那一刻正在播的是**刚从搜索结果点出来的**歌 ⇒ 有数字 id ⇒ 跳错人；
- **手机（PCL110）**那一刻正在播的是**冷启动恢复**的歌 ⇒ 没有 id ⇒ 静默失败。

原因是 `MainActivity.kt:920` 的恢复路径只存了艺人**名字**：

```kotlin
currentSong = SongItem(
    id = songId,
    name = name,
    artists = if (artist != null) listOf(ArtistItem(name = artist)) else null,  // ← id 恒为 null
    ...
)
```

而 `resolveAndNavigate` 的补 id 回落打的是**ncm**的 `/eapi/v3/song/detail`，
QQ 的合成 id（bit62）在那里必然查不到 ⇒ 条件全不匹配 ⇒ 什么都不做。

> **纪律对照（AGENTS.md 铁律 6「平台假设必须 A/B 对照」）**：
> 用户的第一判断是「手机 vs 平板的平台差异」。本探针用**同机交叉**把它证伪了 ——
> 如果不做这一步，修复方向会跑去查 ColorOS / HarmonyOS 的差异，而真因在数据形状。

## 6. 本次复现踩到的环境问题（如实记录，供下一轮省时间）

| 问题 | 影响 | 处置 |
|---|---|---|
| PCL110（Android 16）上 `uiautomator dump` 被 **SIGKILL**（`EXIT=137`，`su` 下同样被杀） | 该机无法按语义树驱动 UI | 改用 `screencap` + 人工读图定位。**坐标因此是本机分辨率专用的**，已在本文写清 |
| `adb shell input text` 不支持中文 | 没法直接搜「稻香」 | 改用 ASCII 关键词 `Jay Chou`（两源都能召回周杰伦），顺带让路径可脚本化 |
| S6（Android 7）上长按歌曲行会被播放器卡片的拖拽层吃掉，弹出的是播放器而不是菜单 | S6 这一轮没取到菜单 | S6 在本 P0 上**不参与结论**；结论由 PCL110 + WGR-W09 两台构成 |
| PCL110 在触屏操作中一度进入横屏大屏播放器 | 截图形状变化 | 复现时先锁 `accelerometer_rotation=0`；该机 `auto_rotate` 应用内开关本身是关的 |
| release 包在这条路径上**零日志** | 无法用 logcat 定位 | 见 `probe-logcat.md`；修复里补了 `TAG_ARTIST_NAV` |
| Gradle 测试执行器在本机 JDK 上 fork 失败（`Spawn helper ran into JDK version mismatch`） | `testDebugUnitTest` 直接起不来 | 用 `JAVA_HOME=/usr/lib/jvm/java-21-openjdk` + `GRADLE_OPTS=-Djdk.lang.Process.launchMechanism=FORK`（见 `verification/gradle-fullbuild-round1.log` 的调用方式） |

## 7. ncm 侧 A/B 对照（回归基线）

同一台 WGR-W09、同一路径，换一首**ncm**曲目（搜索结果里带「ncm」角标的行）：

- 二级菜单 → 转到歌手 → **进入正确的 ncm 艺人页**（无回归）。
- 原因（静态）：ncm 曲目的 `artists[0].id` 就是 ncm 艺人 id，
  老路由写死的 `NETEASE` 恰好是对的 —— 这也说明**这个 bug 只在跨源时暴露**，
  单源时代（v2.1.0 之前）不可能被发现。

修复后在同一路径上复测的结果见 `verification/`。
