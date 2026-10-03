# 探针 · 队列管理（v2.5.0 · 特性 D）

> 目标：回答任务书 §2.2 五问 —— ① `Player` 实例怎么访问；② 有没有 `addMediaItem` 调用点；
> ③ 队列有几种播放模式；④ 当前歌是队列最后一首时 `addMediaItem` 后行为是什么；
> ⑤（原表追加）「添加到下一首播放」在本工程里到底还缺什么。
>
> 方法：只读源码 + `grep` 实测。**不采信任务书的初步判断** —— 这一节的结论与任务书
> §6.2 的写法有两处实质差异，都写在下面。

## 结论（先行）

| 问题 | 实测答案 |
|---|---|
| Player 实例怎么访问 | 不直接访问。经 `PlayerViewModel`（`ui/viewmodel/PlayerViewModel.kt`）→ `PlaybackService`（media3 `MediaLibraryService`） |
| 队列归谁管 | **应用自己**。`MainScreen` 里的 `playbackQueue: List<SongItem>` + `currentQueueIndex`（`MainActivity.kt:926/939`），落盘走 `PlaybackStateManager.saveQueue` |
| 有 `addMediaItem` 吗 | **有两套不同的"队列"**，任务书把它们当成了一套（见 P2） |
| 播放模式 | **5 种**：`CYCLE=0 / SINGLE=1 / SHUFFLE=2 / LINE=3 / INFINITY=4`（`MainActivity.kt:627`） |
| 最后一首时 `addMediaItem` | 对**应用队列**无影响（它不碰 ExoPlayer 列表）；对**待播槽位**会形成 `[当前, 待播]` 两项 |
| 「添加到下一首播放」是否已存在 | **已存在**（`MainActivity.insertNext`），且核心不变量写对了；但**有三个真实缺陷**（见 P5） |

## P1. 队列的归属：应用层，不是 ExoPlayer

```
MainScreen
 ├── playbackQueue: List<SongItem>        ← 真正的队列（唯一事实源）
 ├── currentQueueIndex: Int               ← 关键不变量：playbackQueue[currentQueueIndex] == 正在播的那首
 ├── shuffledIndices / shuffledPosition   ← 乱序排列（契约见 ShuffleRound）
 └── playMode: Int                        ← QueueModes 之一

PlaybackService (media3 MediaLibraryService + ExoPlayer)
 └── ExoPlayer 播放列表 == [当前项] 或 [当前项, 待播项]   ← **不是**队列
```

`AGENTS.md`「Queue Management」把这条写清楚了：所有队列操作都在 `MainScreen`，
**不在** `PlayerViewModel`：`replaceQueueAndPlay` / `playSongItem` / `insertNext` /
`appendToQueue` / `insertAllNext` / `appendAllToQueue` / `removeFromQueue` / `moveInQueue` /
`playFromQueue` / `playNext` / `playPrevious` / `generateShuffledIndices` / `launchInfinity` / `startFm`。

### 1.1 关键不变量（`AGENTS.md` 原文）

> **Critical invariant:** `playbackQueue[currentQueueIndex]` must always equal the currently
> playing song. When deduping a queue mutation, record the current song's id first, filter,
> then re-locate the index — a naive `.filter` can drop an earlier duplicate and point
> `currentQueueIndex` at the wrong item.

本版把它抽成 `QueueInsert.plan` 的**可测事实**（`QueueInsertTest` 有一条用例专门钉住
「当前歌之前有重复项时指针不能指错」）。

## P2. ⚠️ 任务书 §6.2 的第一处偏差：「使用 `player.addMediaItem(index, mediaItem)`」

**这句在本工程里不成立。**

本工程**没有**「把队列交给 ExoPlayer」这回事。ExoPlayer 的播放列表只承载
**当前项 + 至多一首待播项**，由 `PlaybackService` + `PreloadSlot` 维护（v1.5.2 的串台修复把它
定成了硬不变量）：

> ExoPlayer 的播放列表里，当前项之后**至多允许一首**预载项，且 `PlaybackService` 的
> `pendingNext*` 元数据必须与它一一对应。（`player/PreloadSlot.kt:14-30`）

`addMediaItem` 的实际调用点只有 `PlaybackService` 内部（预载），**调用方只有一个入口**：
`PlayerViewModel.preloadNextSong(...)`（`ui/viewmodel/PlayerViewModel.kt:1513`），
全应用 **4 处**调用它：

| # | 位置 | 时机 |
|---|---|---|
| 1 | `MainActivity.kt:1118` | `playFromQueue` —— 切歌瞬间 |
| 2 | `MainActivity.kt:1313` | `needsPreload` 心跳 —— 进入最后 60s |
| 3 | `MainActivity.kt:1390` | `songTransitioned` —— ExoPlayer 自动过渡后 |
| 4 | `MainActivity.kt:1169` | `launchInfinity` —— INFINITY 续播 |

**推论（本版的设计依据）**：既然队列在应用层，那么"添加到下一首播放"的正确做法是
**改应用队列 + 重新同步待播槽位**，而不是 `player.addMediaItem(index, …)`。
后者会往 ExoPlayer 列表里塞第三项，**直接破坏 P2 的不变量**。

> 这条差异不是文字游戏：真按任务书写法实现，会做出 v1.5.2 那个「耳朵里是上一首、
> 通知栏显示下一首」的 bug。

## P3. ⚠️ 任务书 §6.2 的第二处偏差：「当前歌是队列最后一首时，`addMediaItem` 后行为是什么」

对**应用队列**而言这个问题不适用（应用队列不是 ExoPlayer 列表）。真正需要回答的是
「插到末尾之后，各播放模式会不会播到它」。实测 `needsPreload` 的模式分支
（`MainActivity.kt:1280-1309`）与 `playFromQueue`（`:1100-1114`）：

| 模式 | 队尾时的"下一首" | 插到末尾后 |
|---|---|---|
| `CYCLE`(0) | 回绕到下标 0（单曲队列除外） | 当前歌不再是最后一首 ⇒ 播新插入的 ✅ |
| `SINGLE`(1) | 自己（`allowCurrent=true` 实现无缝单曲循环） | **不播**新插入的 —— 该模式语义就是永远重播当前曲（见 P5-c） |
| `SHUFFLE`(2) | `shuffledIndices[shuffledPosition+1]` | **只有改编排序列才会播**，否则播原来那首随机歌 |
| `LINE`(3) | `-1`（停） | 当前歌不再是最后一首 ⇒ 播新插入的 ✅ |
| `INFINITY`(4) | `-1` + 触发 `launchInfinity()` | 播新插入的（且不会触发续播）✅ |

**结论**：`coerceIn` 到末尾的写法对 4/5 个模式天然正确，**不需要为"最后一首"写模式特例**；
真正需要特例的只有 `SHUFFLE`（排列修正）与 `SINGLE`（不重同步槽位）。

## P4. 乱序模式：排列契约（这是 SHUFFLE 边界的关键）

`player/ShuffleRound.kt:44-66` 写明：

> `currentIndex` **钉在下标 0**：这是本应用既有的事实契约（`shuffledIndices[0]` 恒等于
> 正在播的那首），预载/手动下一首/无缝过渡都依赖它。

推广到一轮播放中途，事实契约是：
**`shuffledIndices[shuffledPosition] == currentQueueIndex`**（`MainActivity.kt:1083`
在 `playFromQueue` 里用 `indexOf` 维护它）。

所以「添加到下一首播放」在乱序下的正确语义是：
把新插入项放到 **`shuffledPosition + 1`** 的位置。本版实现为
`QueueInsert.shuffleAfterInsert(...)`，纯函数、可单测，且**任何无法保证结果是合法排列的情况
都返回 null**（调用方回退到"重洗一轮"）—— 宁可随机性变一次，也绝不给出一个错的播放顺序。

## P5. 「添加到下一首播放」的真实缺口（本版要修的三条 + 一条决策）

### 5.1 既有实现是**对的那一半**

`MainActivity.insertNext(song)`（v2.5.0 之前就存在，`MainActivity.kt:1466` 附近）已经做对了：

- ✅ 先记录当前歌 id → 去重 → **重新定位** `currentQueueIndex`（不沿用旧下标）；
- ✅ 写入 `PlaybackStateManager.saveQueue`；
- ✅ 不改变正在播的那首歌、不打断播放。

### 5.2 缺陷 a：**队列为空时不起播**

```kotlin
val newCurrentIndex = if (currentId != null) … else -1
val insertPos = (newCurrentIndex + 1).coerceIn(0, filtered.size)   // -1 + 1 = 0
filtered.add(insertPos, song)
currentQueueIndex = if (newCurrentIndex < 0) 0 else newCurrentIndex
// ← 到此为止：没有 playFromQueue
```

空队列下 `currentQueueIndex` 被设成 0，但**从不调用 `playFromQueue`** ——
用户点「添加到下一首播放」在"什么都没有在播"的状态下**完全没有反应**。
任务书 §6.2 明确要求这一条：**「队列为空 → 直接 `setMediaItem` + `prepare` + `play`」**。

**修法**：`QueueInsert.Outcome.START_FRESH` + `MainScreen` 侧真的 `playFromQueue(0)`。

### 5.3 缺陷 b：**乱序模式下功能静默失效**

既有实现在 SHUFFLE 下调用 `generateShuffledIndices()` —— 那是**重新洗一整轮**，
新插入的歌**不保证**排在当前歌之后。用户点「添加到下一首播放」，下一首仍然是原来那首随机歌。

**修法**：`QueueInsert.shuffleAfterInsert` 把插入项接到当前歌的播放顺序之后（见 P4）。

### 5.4 缺陷 c：**不重新同步待播槽位**（最隐蔽，也是本版最重要的一条）

所有既有的队列写入（`insertNext` / `insertAllNext` / `appendToQueue` / `appendAllToQueue`）
**都不碰待播槽位**。平时这没暴露问题，因为槽位会在「进入最后 60s」的 `needsPreload`
心跳里被 `PreloadSlot.Decision.REPLACE` 纠正。但「添加到下一首播放」是**立即**语义：

```
1. A 在播，B 已被预载          → ExoPlayer 列表 = [A, B]
2. 用户把 C 加到下一首          → 应用队列 = [A, C, B]，ExoPlayer 列表**仍是** [A, B]
3. A 播完                      → ExoPlayer 播的**是 B**，而队列面板显示下一首是 C
```

**这就是 v1.5.2「串台」的形状，只不过这次是本版新增的入口触发的。**

**修法**：插入后立刻用新的下一首调 `preloadNextSong(...)`，让 `PreloadSlot.decide` 走 REPLACE。
`QueueInsert.shouldPreloadAfterInsert(playMode)` 给出「要不要同步」的纯判定。

### 5.5 需要**确认**的一条：同一首歌已在队列下一首 → 去重还是允许重复

任务书 §6.2 把这条列为「需确认」。**本版的确认结果是：去重，并且幂等。**

三条理由（完整版写在 `QueueInsert` 的 KDoc 里）：

1. **重复项直接踩 v1.5.2 的串台形状** —— 那一版 bug 的根因链第一步就是 ExoPlayer
   列表里出现了 `[当前, 下一首, 下一首']`；
2. **本应用所有队列写入都去重**（`insertNext` / `appendToQueue` / `insertAllNext` /
   `appendAllToQueue` 四处全部 `filter { it.id != … }`），只有这一处允许重复会让
   「队列里同一首歌最多一份」这条事实不变量失效，而下游（队列面板、保存为歌单、
   INFINITY 的 `existingIds` 过滤）都默认它成立；
3. 用户意图上，「添加到下一首播放」表达的是**位置**，不是**次数**；想重复听有单曲循环。

**代价（如实记录）**：用户无法用这个入口把同一首歌排两次。既有替代手段是单曲循环。

### 5.6 单曲循环模式的语义（本版决定，不是遗漏）

`QueueModes.SINGLE` 的无缝单曲循环是**靠把当前歌自己预载进槽位**实现的
（`allowCurrent = true`）。若在插入后改成预载「下一首」，ExoPlayer 会在播完后自动前进到它 ——
那等于**静默地把单曲循环改成了顺序播放**。

**本版决定**：单曲循环下照旧**只插队列、不动槽位**；退出单曲循环后该曲自然成为下一首。
提示文案仍然是「已添加到下一首播放」（就队列顺序而言这是真话）。
`QueueInsert.shouldPreloadAfterInsert(SINGLE) == false` 并由单测钉住。

## P6. 用于特性 D 的接口清单（本版新增/复用的唯一落点）

| 落点 | 作用 |
|---|---|
| `player/QueueInsert.kt`（**新增**） | 队列边界判定：`plan` / `shuffleAfterInsert` / `nextIndexAfterInsert` / `shouldPreloadAfterInsert`，纯逻辑 |
| `MainActivity.insertNext`（**改写**） | 装配 + 落盘 + 槽位重同步 + 反馈 |
| `MainActivity` 全局 `SongMenuSheet`（**新增一项**） | 「添加到下一首播放」入口 —— 覆盖全部 Screen（全应用唯一的歌曲长按入口） |
| `ui/screen/SongDetailScreen.kt`（**新增按钮**） | 单曲界面每一行版本一个入口（该页没有底部操作栏，见 P7） |
| `ui/components/AppSnackbar.kt`（**新增**） | 提示。本仓库此前**没有任何 Snackbar 设施**（`Snackbar` 命中 0 处，反馈一律 `Toast`） |

## P7. 「单曲界面：底部操作栏新增按钮」的落地偏差（如实记录）

任务书 §6.2 写「单曲界面：**底部操作栏**新增按钮」。实测 `ui/screen/SongDetailScreen.kt`
**没有底部操作栏** —— 它的结构是「顶部 scrim 返回键 + 曲目信息 + 逐行版本列表」，
每一行（`SongVersionRow`）自带一个 48dp 命中区的播放键。

而且本工程有一条**硬约束**（`AGENTS.md`「详情页播放器死带」，v1.3.0 实测）：
详情页底部 `y ≳ collapsedOffsetY` 一带的元素收不到事件，规则是
**「详情页的交互元素不得落在该带内；需要底部操作时用顶部 scrim 或列表行入口承载」**。

**所以本版的做法**：把按钮加在**每一行的播放键左侧**（48dp 命中区，与播放键同规格）。
这既符合"任务书的意图 = 能在单曲界面把某一行加到下一首"，
又满足本工程那条实测出来的触摸约束。新增一个底部操作栏会**直接把它放进死带**。

## P8. 未确认项

- `QueueModes.SINGLE` 下「添加到下一首播放」的**用户预期**未做真人验证：
  本版按"只插队列"处理并写明理由，但"用户在单曲循环下点这个按钮时到底想看到什么"
  是一个产品问题，不是代码问题。
- 队列身份用裸 `song.id`（与全部既有队列写入同一把尺子）。跨源**裸 id 撞号**
  （ncm 某首 vs QQ 某首）时会把其中一首当重复。这是**既有**风险、不是本版引入的
  （`PlayerViewModel` 另有更严格的 `TrackKey`，但队列去重从未用过它），
  本版刻意不单独升级判重强度以免两处身份语义不一致。列入未验证清单。
- ExoPlayer 播放列表在「插入后重同步」那一瞬间的实际内容（是否真的先 REMOVE 再 ADD）
  未在真机上抓过 logcat 逐帧确认 —— 本版按 `PreloadSlot.decide` 的代码路径推断，
  并把它列为需要真机验证的项。
