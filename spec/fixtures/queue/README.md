# fixtures/queue —— 队列状态机

队列操作后「队列内容 + 当前索引」的标准答案（见 `spec/README.md`）。语义基线是 Android
`MainScreen` 的队列函数，逐条对照（remove 曾有偏差，已修正，见文末）。

## 关键不变量

`queue[current]` 恒等于正在播放的歌。任何去重操作必须先记下当前歌 id，过滤后**重新定位**
当前索引，不能只 `filter`。`SHUFFLE` 模式下每次修改都要重新生成打乱表。

## schema

`queue.json` 是用例数组，`kind` 固定为 `op`：

```
input:  { "queue": [id...], "current": <int, -1 表示无当前>, "mode": "CYCLE|SINGLE|SHUFFLE|LINE|INFINITY", "op": { ... } }
expect: { "queue": [id...], "current": <int>, "shuffleValid": <bool, 仅 SHUFFLE 用例> }
```

`op.type`：

| type | 字段 | 语义 |
|---|---|---|
| `insertNext` | `id` | 去重后插到当前歌的下一首；当前歌不变 |
| `append` | `id` | 去重后加到队尾；当前歌不变 |
| `playSong` | `id` | = insertNext 后把当前切到该曲 |
| `insertAllNext` | `ids` | 去重后整批插到当前歌之后；当前歌不变；无当前时等价 replace |
| `appendAll` | `ids` | 只追加队列里没有的；当前歌不变；无当前时等价 replace |
| `remove` | `index` | 删除该索引 |
| `move` | `from`,`to` | 拖拽排序，当前项跟随其歌曲移动 |
| `playFrom` | `index` | 直接切当前到该索引 |
| `replace` | `ids` | 整队替换，当前置 0 |

`shuffleValid: true` 表示断言：打乱表长度 == 队列长度、是 `0..n-1` 的排列、首项 == 当前索引、
`shuffledPosition == 0`。

## remove 的规格修正

Android `removeFromQueue` 只做 `currentIndex >= size` 的 clamp：删掉当前曲**之前**的歌后，
`currentIndex` 数值不变，于是指向了原来当前曲的**后一首**，破坏上述不变量。本夹具按正确的
行为定义：**删除后按歌曲身份重新定位当前索引**（删除当前曲本身时，落到它原本所在槽位的下一项，
队尾则前移一格）。Android 侧已按本夹具修正（`PlaybackQueueLogic.removeAt`，由 `PlaybackQueueSpecFixtureTest` 锁定；曾是 issue #32「播错歌」的一条路径）。

## 来源

Android：`app/.../MainActivity.kt` 的 `insertNext` / `appendToQueue` / `playSongItem` /
`insertAllNext` / `appendAllToQueue` / `removeFromQueue` / `moveInQueue` / `playFromQueue` /
`generateShuffledIndices`；打乱表结构见 `PlaybackQueueLogic.kt`。

## Arris 适配说明（本 Fork）

1. **身份不是裸 `Long`，而是 `TrackKey(source, id)`**（`source/TrackKey.kt`）。
   夹具里的 `1 / 2 / 3` 在单测里映射成 ncm 的 `TrackKey(NETEASE, 1)`；
   跨源同号是两首歌，按裸 id 回查会把「QQ 123」认成「ncm 123」。
2. **`remove` 的规格修正已落地**：`PlaybackQueueLogic.removeAt` 按歌曲身份重新定位当前索引
   （删当前曲本身时退化为槽位语义：后一首顶上、队尾前移）。夹具 `remove.*` 五条逐条对应。
3. **实现落点与上游不同**（`insertNext` / `playSong` / `remove` / `move` 是纯函数，
   其余三个批量操作是 `MainScreen` 里的局部函数）：

| op | 本 Fork 的纯逻辑落点 |
|---|---|
| `insertNext` | `QueueInsert.plan(keys, current, newKey)` |
| `playSong` | `QueueKeys.planPlayItem(queue, current, song)` |
| `remove` | `PlaybackQueueLogic.removeAt(queue, current, index)` |
| `move` | `reorderByIndices(queue, current, from, to)`（`moveInQueue` 的身份版是 `reorderByIdentity`） |
| `append` | `QueueKeys.dedupe` + `QueueKeys.rebuild` + `QueueKeys.indexOfCurrent` |
| `insertAllNext` | `QueueKeys.excludeCurrent` + `QueueKeys.without` + `QueueKeys.indexOfCurrent` + `QueueKeys.rebuild` |
| `appendAll` | `QueueKeys.songsOutside` + 尾接 |
| `playFrom` / `replace` | 无独立纯函数（就地改游标 / 整队替换后 `current = 0`） |

   最后三行（`append` / `insertAllNext` / `appendAll`）的落点是 `MainScreen` 的局部函数，
   JVM 单测调不到，所以 `QueueSpecFixtureTest` 按生产代码的**同一顺序**组合上面这些纯函数，
   并用一条**源码接线守卫**断言生产侧仍然是这段组合 —— 生产改了组合而没改夹具，守卫会红。
4. **乱序不是「数组末尾回绕到 0」**：`ShuffleRound.newRound` 把当前曲钉在轮次下标 0，
   轮末由 `ShuffleRound` 开新一轮（`PlaybackQueueLogic.nextIndex` 在轮末返回 -1）。
   因此 `shuffleValid` 在本 Fork 断言的是：打乱表长度 == 队列长度、是 `0..n-1` 的排列、
   首项 == 当前索引（等价于 `shuffledPosition == 0`）。
