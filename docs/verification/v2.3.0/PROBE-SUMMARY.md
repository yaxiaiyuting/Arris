# v2.3.0 探针总结（PROBE-SUMMARY）

> 全部结论来自 2026-09-25/26 的**实测**（匿名网络探针 + 真机 adb），不是文档推断、不是任务书假设。
> 逐项证据：`probe-copyright.md` / `probe-source-attribution.md` / `probe-official-tag.md` /
> `probe-local-playlist.md` / `probe-lyric-landscape.md`；原始数据在 `probe-raw/`。

---

## 一、版权字段是否存在，若无如何降级

**存在，而且是逐曲内嵌的 —— 但没有任何一个「可播放」的布尔字段。**

| 事实 | 实测 |
|---|---|
| 字段位置 | `POST /api/cloudsearch/pc` 的 **`songs[i].privilege`**（不是 `result.privileges`，后者不存在）；艺人页 `hotSongs[i].privilege`；单曲详情顶层 `privileges[]` **240/240 全部存在** |
| 专辑页 | **需登录**：匿名 `GET /api/v1/album/{id}` 返回 `code:200` 但 `songs: []` |
| 可用判据 | `privilege.pl`（可播最高码率）、`privilege.st`（状态码）、`noCopyrightRcmd`（无版权时的替代推荐） |
| 「可播放」判据 | **`pl > 0`**：分层抽样 30 首，**30/30 实测可播，假阳性 0** |
| 「需会员」判据 | **`st == 0 && pl == 0 && fee ∈ {1,4}`**：30/30 实测不可播，且这 30 首 `fee` 全是 `1` |
| 「无版权」判据 | **`st == -200` 或 `noCopyrightRcmd != null`**：零假阳性，但整池 591 条里只有 2~3 条（**覆盖率 0.3%**） |
| **关键否定结论** | **`st == -1` 绝不可当判据**：30 首实测 **15 可播 / 15 不可播**（`fee=0` 的伴奏/纯音乐大量落在这一档，匿名就能播） |
| 取链预检代替？ | **不采用**：单曲 `player/url/v1` 实测中位数 **≈475ms**（448–515ms），搜索一页 30 首串行 ≈ **14.3s** |

**降级方案（本版实现）**：
1. 有确证判据才标注（可播放 / 需会员 / 无版权），**判不出来时整组文案一个都不出现**；
2. 播放失败时给一条「此源无版权，可切另一源」的提示（**有节流、不发请求、不重试、不改跳歌判定**）。

---

## 二、音源归属如何标识

**两源各自编号，接口里没有任何跨源标识（无 ISRC、无指纹）⇒ 跨源合并不可做。**

- `SearchViewModel.publish` 用 `distinctBy { it.trackKey }`，而两个源的 key 天然不同 ⇒
  同一首歌会出现**两行**（实测《晴天》：ncm `186016`，QQ `00083kc41YcFuR` 与 `002DMfDx1macLz`）；
- v2.1.0 只给 QQ 挂音源角标（`else -> ""`），在聚合列表里**用户判断不出哪一行是哪个源**；
- **本版改成两源都标**（`SongTags`），并保留「不合并、不隐藏」；
- QQ 侧**没有**任何可用的「可播放 / 无版权」字段：`action.switch` 的 bit0 在 **130/130** 条上恒为 1
  （零区分度），`action.alert` 语义无权威定义且**本轮没有金标准可验证** ⇒ **不用**，只保留已验证的
  `pay.pay_play == 1 ⇒ 会员专享`。
- 排序：**不做**按可播放性的全量重排（会把翻唱/Live 顶到精确匹配前面，负收益）；
  只把服务端**显式声明无版权**的 0.3% 行沉底（稳定分区，组内顺序不变）。

---

## 三、官方标签的定义与数据来源

**定义（本版拍板，收窄到能被服务端确证的那一种）**：

| 概念 | 是否实现 | 判据 |
|---|---|---|
| **原唱** | ✅ | ncm `originCoverType == 1` |
| **翻唱** | ✅（并显示原曲名/原唱者） | ncm `originCoverType == 2`（51% 另带 `originSongSimpleData`） |
| 官方音源 vs 用户上传 | ❌ | **无字段**（`copyright` / `resourceState` / `ftype` / `rtype` / `single` / `version` 全部无区分度或语义未知） |
| 官方发行 vs 用户自制 | ❌ | 同上 |
| QQ 的任何官方/原唱标签 | ❌ | **一个相关字段都没有**（`label`/`type`/`ov`/`singer[].type` 在 130 条上全部同值；`songtype` 不存在） |

**这不是启发式，是接口字段**，所以准确率可以量化：

- **自洽性检验**（不靠我当裁判）：对 60 条「翻唱」回头查它声称的原曲，
  **58 条（96.7%）** 的 `originCoverType == 1`，剩下 2 条是 `0`（无信息）——
  **没有一条指向另一个「翻唱」**；
- **反向对照**：`originCoverType == 1` 的 429 条里，**0 条**带 `originSongSimpleData`；
- **反例（为什么不能用启发式）**：`晴天 (原唱 周杰伦)` 标题里自称「原唱」，
  而服务端标注 `originCoverType = 2` —— **它是翻唱**，字符串关键字判断会 100% 判反。

⇒ 只在 `{1, 2}` 时显示角标；`0`/`3`/字段缺失/QQ 全部**留白**。

---

## 四、本地歌单的现有结构

**此前完全没有这个概念** —— 全仓只有**一句注释**说它「尚不存在」
（`QqMusicSourceProvider.kt:38`，v2.1.0 写的）。现有「歌单」是两个远程镜像：

| | ncm | qm |
|---|---|---|
| 列表 | 每次进 tab 重新拉（内存 state） | 落盘 `ncrust_qq_playlists`（按 source+ownerId+tid 隔离） |
| 曲目 | **只在内存** `ContentCache`（LRU-32，进程死即失） | 落盘，**只读镜像** |
| 写入 | `PlaylistEditApi`（**远程写**，与本版无关） | **没有**任何写接口 |

- 存储设施：**没有 Room、没有 DataStore**，全部 SharedPreferences + Gson；
- `PlaylistTrack` **不能直接扩展**（`order` 是服务端的、`addedAt` 在 QQ 侧恒为 null、没有 `tombstoned`）
  ⇒ 另建 `LocalPlaylistTrack`，只复用 `PlaylistKey` / `TrackKey` 两个身份类型；
- 可复用的迁移范式：`PlaylistCacheCodec`（v2.2.0，DTO + `SCHEMA_VERSION` + 显式构造 + 单测）；
- **一处必须与 v2.2.0 相反的决定**：`tombstoned` 缺字段补 **`false`**（v2.2.0 对缺 `ownerId` 是**丢弃**）——
  补 `true` 会让整张歌单同步不进任何歌，丢弃会让用户数据消失。

真机确认：`su -c ls …/shared_prefs/` 12 个文件里**没有任何** local-playlist 相关项
（`probe-raw/device-prefs-list.txt`）。

---

## 五、横屏歌词的当前行为与问题根因

**问题根因（真机复现，决定性）**：`NcrustLyricsPanel.kt` 的 5s 超时分支
**只放旗子、不滚动**：

```kotlin
} else if (!listState.isScrollInProgress && userScrolling) {
    delay(5000)
    userScrolling = false          // 只做这两件事
    lastAutoScrolledIndex = -1     // —— 没有任何滚动调用
}
```

唯一的跟随路径是 `LaunchedEffect(currentIndex)`，而它**只在换行时**才跑。

**决定性实测**（暂停态，当前行全程同一句 `めき煌めきと君も`，屏幕用三连拍 md5 相同证明静止）：

| 时刻 | `frac_itemtop` |
|---|---|
| 滑动前（自动位置） | **0.3599** |
| 滑动后 +1.64 s | **0.7565**（+367.9px = +39.65% 面板高） |
| 静置 +8.57 s | **0.7565**（不变） |
| 静置 +20.61 s | **0.7565**（不变；PNG 与 +8.57s **md5 相同**） |

⇒ 8.6s / 20.6s（超时的 1.7× / 4.1×）**一次都没回正**。另两条独立复现：
下滑 `0.3599→0.7543→0.7543(+37.3s)→0.7543(+51.9s)`；**上滑** `0.3599→0.1649→0.1649(+10.6s)`。

**几何**：横屏面板 1274×**928px = 232.0dp**，只放得下 **3 行**；0.36 ⇒ 当前行在面板内 83.5dp 处，
下方只剩约 1.4 行 —— 视觉重心偏上（这就是「有时不正」）。
**但自动定位本身是精确的**：6 次独立基线（4 首歌、播放与暂停、单行与折行）**全部 `frac = 0.3599`**。

**播放/暂停/seek 是否重置计时（探针回答）**：**都不会**。
按播放 +2.0s 仍 `0.4170`，+5.6s **换行**才回到 `0.3599`；seek 后 24 秒 7 次采样全部 `0.4041`。
⇒ 唯一的回正触发是**换行**；播放时每 2–3 秒换行，所以 bug 被掩盖；暂停/间奏/长句时永久暴露。

**本版据此收窄交互判据**：**只有手指落在面板上才算交互**
（seek 已有自己的重定位路径；播放/暂停不移动列表，把它算作交互会让「暂停后翻看歌词」
这个最需要回正的场景永远等不到回正）。

---

## 六、探针 → 实现的对照表（一条不落）

| 探针结论 | 本版实现 | 单测 |
|---|---|---|
| `pl > 0` ⇒ 可播放（30/30，假阳性 0） | `TrackAvailability.PLAYABLE` + 「可播放」角标 | `TrackAvailabilityTest` / `SongTagsTest` |
| `st==0 && pl==0 && fee∈{1,4}` ⇒ 需会员 | `TrackAvailability.MEMBER_ONLY` | 同上 |
| `st==-200` / `noCopyrightRcmd` ⇒ 无版权 | `TrackAvailability.NO_COPYRIGHT` | 同上 |
| `st==-1` 不可用 | **一律 `UNKNOWN` + 不显示** | 两条反向断言 |
| QQ 无可用字段 | QQ 只判会员，其余留白 | `QQ 的 pay_play 等于 0 不判为可播放` |
| 跨源同一性不存在 | **不做**跨源配对；两源都标音源 | `同名不同源的两行角标不同` |
| 不按可播放性全量重排 | 只把 `NO_COPYRIGHT` 沉底（稳定分区） | `SearchRankingTest`（含一条反向断言） |
| `originCoverType` 1/2 可用，0/3 不可用 | `TrackVersionTag` + 角标 + 原曲副标题 | `TrackAvailabilityTest` / `SongTagsTest` |
| QQ 无官方字段 | QQ 恒 `UNKNOWN` | 两条用例 |
| 本地歌单不存在，需新建 | `local/` 四个文件 + 编辑页 | `LocalPlaylistSyncTest`（7 规则）/ `LocalPlaylistCodecTest` |
| `tombstoned` 缺字段补 false | 迁移显式表达 + 两条反向断言 | `LocalPlaylistCodecTest` |
| 5s 超时只放旗子不滚动 | 世代计时器 + 真的滚回目标 | `LyricsAutoCenterTest` |
| 拖拽中不打断 | `shouldRecenter(pointerDown=...)` | 专门的用例 |
| 横屏 3 行视口 36% 偏上 | 横屏目标改 0.5 | `LyricsPanelScrollTest`（6 条新用例） |
| 播放/暂停/seek 不重置计时 | 只认触摸 | 设计依据写在 `NcrustLyricsPanel` 的注释里 |

---

## 七、探针之外、由实现过程发现的**新**问题（如实补记）

**`Strings` 的构造参数上限是 245，不是 AGENTS.md 里写的「余量 ~9」。**

本版按惯例「拆成两个嵌套组、给 `Strings` 只加两个参数」→ **编译全绿**，
但单测在类加载期抛：

```
java.lang.ClassFormatError: Too many arguments in method signature in class class com/takahashirinta/ncrust/ui/i18n/Strings
```

真实算式（本版实测）：

```
槽位 = this(1) + 参数 N + 默认值 mask ceil(N/32) + DefaultConstructorMarker(1)
N = 245 ⇒ 1 + 245 + 8 + 1 = 255  ← 刚好用满（HEAD 的真实值，v2.2.1 已经是 245）
N = 246 ⇒ 1 + 246 + 8 + 1 = 256  ← 溢出
```

**⇒ `Strings` 的可用余量是 0。** 本版的处置：把 `networkOfflineTitle` /
`networkOfflineHint` 搬进语义上本来就属于它的 `OfflineStrings`（腾出 2 个位置），
再用转发属性保住全部调用点；并补了一个 **`StringsConstructorBudgetTest`** 把这条钉住
（下一次有人加参数会得到一个红色用例，而不是一台启动即崩的真机）。
