# 探针：QQ 曲目「加入库」刷新后消失（P0）

> 仓库：`/home/duanjb666/deepseek/ncrust-gpl/Ncrust`（Ncrust GPL fork）
> 基线：`HEAD = 7a4e33b`（`docs(v2.5.6): 回填设备验证结果与发布物信息（含未完成的平板复测）`，`versionName = 2.5.6-gpl`）
> 探针时间：2026-09-26 21:5x–22:0x CST
> 证据等级约定：**(a)** 读代码证明 ｜ **(b)** 执行命令证明 ｜ **(c)** 推断未执行

## ⚠️ 关于本报告的行号基准（必读）

探针运行期间（`22:00:55`），工作区的 `library/LibraryManager.kt` **被另一个会话改动**，并新增了两个未跟踪文件：

```
$ git status --short
 M app/src/main/java/com/takahashirinta/ncrust/library/LibraryManager.kt
?? app/src/main/java/com/takahashirinta/ncrust/library/SavedSongCodec.kt
?? app/src/main/java/com/takahashirinta/ncrust/library/SavedSongModels.kt
?? docs/verification/v2.4.0/__pycache__/
?? docs/verification/v2.6.0/
```

因此本报告的**全部 `file:line` 引用都以 `git show HEAD:<path>` 为准**（探针开始时工作区是干净的，`git status --short` 无输出）：

```
$ git show HEAD:app/src/main/java/com/takahashirinta/ncrust/library/LibraryManager.kt | wc -l
435
$ git show HEAD:app/src/main/java/com/takahashirinta/ncrust/library/LibraryManager.kt | md5sum
0ed2cf790645958f125a71c019bdce44  -
# 工作区版本（未提交 WIP，2026-09-26 22:00:55 那一刻的快照：27521 字节，580 行，md5 785ceaedaa754bc19afe2623d18d25ff）
# 此后 WIP 仍在继续变动（探针结束时又多了 SavedSongSyncTest.kt / SavedSongCodecTest.kt /
# LibraryImportPersistenceTest.kt，以及 PersistenceFieldNameContractTest.kt 的改动）
```

工作区那份 WIP **已经包含针对本 Bug 的修复方向**（`SavedSongEntry` 带 `origin/tombstoned`、`SavedSongSync.merge`、`flushNow`、`pushLike` 里的 `SourceIds.isQqId` 闸门）。本报告第 9 节据此写成「对照 + 缺口」，WIP 本身**未验证、未提交**，不计入结论。
**本次探针没有改动任何源码**；三个可执行探针写在仓库外 `/tmp/probe-qq/`。

---

## 1. 结论先行

**根因一句话**：「加入库」写的是 `LibraryManager` 的**ncm 红心歌单镜像**（`cachedSongs` → `ncrust_library/saved_songs` + 异步 `pushLike`），而 `refreshFromCloud` 用**云端 `likedIds` 顺序整体重建**这张表（`LibraryManager.kt:317-329`）。QQ 曲目的 id 由 `SourceIds.qqId` 合成、`bit62` 恒置位（`≥ 2^62`），而 ncm songId 是十进制百万~十亿量级（`< 2^40`）——两者**值域不相交**，所以 QQ 曲目**在结构上不可能**出现在 `likedIds` 里，重建时必然被 `?: continue` 静默丢弃；紧接着 `scheduleFlush` 把这个「丢了 QQ 曲目」的结果写回磁盘。用户看到的就是「点一下弹『已加入库』→ 下次刷新/切 tab/重启就没了」。

| # | 问题 | 答案 | 关键证据 |
|---|---|---|---|
| 1 | 「入库」写哪个数据结构？ | **内存** `cachedSongs: MutableList<SongItem>`（ncm 收藏镜像）+ 去抖落盘 `ncrust_library/saved_songs` + 异步 POST ncm `/eapi/radio/like`。**不是**本地歌单，**不是** QQ 歌单镜像 | `LibraryManager.kt:61,194-206,49-52,161,184-186`；`PlaylistApi.kt:690-710` |
| 2 | 立即落盘？路径/键/去抖？进程死在窗口内？ | **否**。`scheduleFlush` 去抖 **300ms**（`delay(300L)`），落盘用 `.apply()`（异步提交）。进程死在窗口内 ⇒ 该次（及窗口内全部）修改丢失 | `LibraryManager.kt:135-144,146-167` |
| 3 | 「刷新」触发什么？会覆盖本地新增吗？ | 四处触发 `refreshFromCloud`（进收藏页/切 tab、登录信号、登录成功、冷启动预热）。它**按 `likedIds` 整体重建** `cachedSongs` 并回写磁盘 ⇒ **覆盖并丢弃**本地新增 | `LibraryScreen.kt:165-168,171-176`；`MainActivity.kt:1959-1963`；`AppWarmup.kt:204-207`；`LibraryManager.kt:317-329,354` |
| 4 | 与 v2.2.0「只加不减 + tombstone」冲突？会被误判 tombstone？方向对吗？ | **两个互不相干的子系统**。`local/` 的 `merge` **只追加、从不删除/从不打 tombstone**，方向**正确**（可执行验证见 §6）。HEAD 的 `library/LibraryManager` **根本没有 origin/tombstone 概念**，「只加不减」这条纪律从未被应用到它身上 —— 它不是「误判成删除」，而是**直接物理丢弃** | `LocalPlaylistModels.kt:158-197,217-230`；`LibraryManager.kt:317-329` |
| 5 | QQ 特殊 `dirId`（我喜欢=201）导致写入被忽略？ | **否**（不是本 Bug 的成因）。`dirId` 只用于寻址与展示（`FAVORITE_DIR_ID=201`、`isFavorite`），写入路径拿到的是 `SongItem`，既不看 `dirId` 也不看 `source`。另外 **HEAD 的 QQ 歌单详情页菜单里没有「加入库」**（传 `emptyList()`），QQ 曲目可达的「加入库」是播放器卡片按钮与搜索结果菜单 | `QqPlaylistParser.kt:93-94,191-193,226`；`QqPlaylistDetailScreen.kt:257`；`PlayerCard.kt:737-750`；`SearchScreen.kt:299-315,502-506` |
| 6 | ncm 是否同样受影响？ | **同样会丢，但条件不同**。存活谓词是 `id ∈ getLikedTrackIds(uid)`：QQ 曲目**必然**丢（值域不相交）；ncm 曲目在 like 推送没生效（风控 `-460` / 离线 / 加的时候未登录）时**一样丢** | `LibraryManager.kt:308,317-329`；`PlaylistApi.kt:680-710`（KDoc 自述 like 四种协议变体被风控拦截） |
| 7 | 当前 QQ 歌单是只读镜像还是可写本地歌单？ | **只读镜像**。`QqPlaylistStore`（`ncrust_qq_playlists`）+ `QqPlaylistRepository` 只有 `loadList/loadDetail/clearAll/clearCurrentOwner`，没有任何用户意图写入口。可写的是 v2.3.0 另建的**本地歌单**（`ncrust_local_playlists`），而「入库」两者都不写 | `QqPlaylistStore.kt:52`；`QqPlaylistRepository.kt:80,199,285-293`；`LocalPlaylistStore.kt:56-66` |
| 8 | 队列 / 待播槽位受影响？ | **结构上不受影响**：队列判重与待播槽位以 `TrackKey(source,id)` 为身份（`TrackKey.equals` 只看 `(source,id)`），从不读 `LibraryManager`；已进队列的 QQ 曲目不会因刷新被移除。**间接耦合 3 处**：车机 `LIKED_ID` 节点、库页「播放全部」、口味匹配/「按专辑播放全部」（后两者无音源过滤，**跨源串味**） | `TrackKey.kt:75-78`；`PlaybackService.kt:861`；`MainActivity.kt:1803,2283-2290`；`ArtistReco.kt:112` |

---

## 2. 探针方法

### 2.1 读过的源码（HEAD 全量读）

| 文件 | 用途 |
|---|---|
| `library/LibraryManager.kt`（435 行，全读） | 写入/落盘/云端刷新三件事的唯一落点 |
| `ui/screen/LibraryScreen.kt`、`QqPlaylistDetailScreen.kt`、`SearchScreen.kt`、`LibraryPlaylistsTab.kt`、`LocalPlaylistDetailScreen.kt`、`PlayerCard.kt`（相关段） | 「入库」入口与刷新触发点 |
| `local/LocalPlaylistModels.kt` / `LocalPlaylistRepository.kt` / `LocalPlaylistStore.kt`（全读） | v2.2.0/v2.3.0「只加不减 + tombstone」子系统 |
| `qq/QqPlaylistRepository.kt` / `QqPlaylistParser.kt` / `QqSongMapper.kt` / `QqPlaylistStore.kt` / `QqCatalog.kt`(grep) / `QqMusicSourceProvider.kt` | QQ 侧身份、`dirId`、`isFavorite/isOwned` |
| `source/MusicSource.kt`（`SourceIds`）、`source/TrackKey.kt`、`source/PlaylistModels.kt`、`source/SongSourceExt.kt`、`source/MusicSourceProvider.kt` | bit62 合成 id、身份模型、音源抽象 |
| `network/PlaylistApi.kt`（`likeSong` / `getLikedTrackIds` 段）、`player/ReportGate.kt`、`warmup/AppWarmup.kt`、`MainActivity.kt`（菜单与刷新接线） | 跨源写闸门先例、刷新触发点、统一歌曲菜单 |

### 2.2 跑过的命令（全部只读）

```bash
git log --oneline -3 ; git status --short ; pwd
git log --oneline -20 -- app/src/main/java/com/takahashirinta/ncrust/library/LibraryManager.kt
git log --oneline -S "isQqId"  -- .../library/LibraryManager.kt      # 阴性结果，见 §4.2
git log --oneline -S "saveSong" -- .../library/LibraryManager.kt      # 阳性对照
git log --oneline -S "likedIds" -- .../library/LibraryManager.kt
git show 5634500:app/src/main/java/com/takahashirinta/ncrust/library/LibraryManager.kt
git show 6da6560 --stat
grep -rn "LibraryManager.saveSong|refreshFromCloud|loadMoreLikedSongs" app/src/main/java --include=*.kt
grep -rn "isQqId|qqRawId|sourceOfId|QQ_ID_FLAG" app/src/main/java --include=*.kt
grep -c "LibraryAdd" <QQ 歌单详情页 / ncm歌单详情页>
grep -n "Playlist(|isFavorite|isOwned|dirId" app/src/main/java/.../qq/QqCatalog.kt   # 阴性结果，见 §4.5
grep -rn "refreshFromCloud" docs --include=*.md                                      # 阴性结果，见 §4.6
grep -rn "入库|加入库" app/src/main/java app/src/main/res
# 可执行探针（仓库外 /tmp/probe-qq/，OpenJDK 26.0.2 单文件运行）
javac MergeProbe.java && java MergeProbe
javac PredicateProbe.java && java PredicateProbe
javac TombstoneProbe.java && java TombstoneProbe
```

### 2.3 可执行探针的定位（**必须读这一条**）

`LibraryManager` 依赖 Android `Context`/`SharedPreferences`，本机没有设备也没有 `kotlinc`，**无法直接运行 App 的那段 Kotlin**。所以我把 HEAD 的重建块 `LibraryManager.kt:317-329` **逐句转写成 Java**（`LinkedHashMap` / `associateBy` / `?: continue` 一一对应）后运行。它的证据力边界：

* **(b) 执行证明**：`bit62` 算术、「存活 ⟺ `id ∈ likedIds`」这条谓词、以及 tombstone 子系统的方向性；
* **(a) 读代码证明**：转写与原文逐句对应（下方给出原文与转写对照）；
* 它**不是**在跑 App 的字节码，因此不能证明「Kotlin 的 `associateBy` 与 Java 的 `putIfAbsent` 在重复 id 上语义完全一致」这类边角 —— 但本判定不依赖重复 id。

转写对照（原文 → 转写）：

```kotlin
// LibraryManager.kt:317-329（HEAD，逐字）
synchronized(songsLock) {
    val oldById = (cachedSongs ?: mutableListOf()).associateBy { it.id }
    val freshById = firstSongs.associateBy { it.id }
    val merged = LinkedHashMap<Long, SongItem>(likedIds.size)
    for (id in likedIds) {
        if (merged.containsKey(id)) continue
        val song = freshById[id] ?: oldById[id] ?: continue
        merged[id] = song
    }
    cachedSongs = merged.values.toMutableList()
    likedIdCursor = likedIds.takeWhile { merged.containsKey(it) }.size
}
```

```java
// /tmp/probe-qq/MergeProbe.java（逐句转写）
Map<Long, Long> oldById = new LinkedHashMap<>();
for (Long id : cachedSongs) oldById.putIfAbsent(id, id);
Map<Long, Long> freshById = new LinkedHashMap<>();
for (Long id : firstSongs) freshById.putIfAbsent(id, id);
Map<Long, Long> merged = new LinkedHashMap<>();
for (Long id : likedIds) {
    if (merged.containsKey(id)) continue;
    Long song = freshById.get(id);
    if (song == null) song = oldById.get(id);
    if (song == null) continue;               // Kotlin 的 `?: continue`
    merged.put(id, song);
}
return new ArrayList<>(merged.values());
```

---

## 3. 根因链（每一步都有 file:line）

### 步骤 1 — 「入库」的落点是一个**以云端为真源**的镜像

```kotlin
// app/src/main/java/com/takahashirinta/ncrust/library/LibraryManager.kt:34-47（类 KDoc，HEAD）
 * 云同步收藏库。
 * 语义（与ncm官方一致）：
 *  - 「收藏单曲」 = ncm「收藏/我喜欢」（weapi /api/radio/like）；
 * 本地用 SharedPreferences 缓存云端状态（收藏单曲 + 收藏专辑），保证：
 *   - 进收藏页/登录时后台拉取（refreshFromCloud）刷新，云端为真源；
```

```kotlin
// LibraryManager.kt:49-52, 61
private const val PREFS_NAME = "ncrust_library"
private const val KEY_SONGS = "saved_songs"
...
@Volatile private var cachedSongs: MutableList<SongItem>? = null
```

**(a)** 设计意图写得很清楚：这是**ncm 红心歌单的本地缓存**，`云端为真源`。它没有任何「本地独有条目」的概念 —— 这一点是全案的根据。

### 步骤 2 — `saveSong` 只把歌放进内存，然后去抖落盘 + 异步推送

```kotlin
// LibraryManager.kt:194-206
/** 收藏（收藏）单曲：先落本地缓存，再异步同步到云端。 */
fun saveSong(context: Context, song: SongItem) {
    val songs = ensureSongsLoaded(context)
    val added: Boolean
    synchronized(songsLock) {
        added = songs.none { it.id == song.id }
        if (added) songs.add(0, song)
    }
    if (added) {
        scheduleFlush(context)
        if (isLoggedIn(context)) pushLike(song.id, true)
    }
}
```

**(a)** 三个事实：
1. 判重只看 `it.id == song.id`（`Long`）——QQ 合成 id 带 bit62，所以**不会**与 ncm 曲目撞号（这一点是好的）；
2. `song` 里带着 `source = "qqmusic"` 与 `sourceId = songmid`（`QqSongMapper.kt:125-141`），但 `saveSong` **完全不看**它们；
3. `saveSong` 返回 `Unit` —— **调用方拿不到任何成败信息**，这是步骤 7「UI 说谎」的结构性前提。

### 步骤 3 — 落盘是 **300ms 去抖 + `apply()` 异步**

```kotlin
// LibraryManager.kt:135-144
private fun scheduleFlush(context: Context) {
    synchronized(flushLock) {
        pendingAppContext = context.applicationContext
        flushJob?.cancel()
        flushJob = ioScope.launch {
            delay(300L)
            flushToDisk()
        }
    }
}

// LibraryManager.kt:146-167
private fun flushToDisk() {
    ...
    synchronized(songsLock) { songsSnapshot = cachedSongs?.toList() ?: emptyList() }
    ...
    runCatching {
        prefs(ctx).edit()
            .putString(KEY_SONGS, gson.toJson(songsSnapshot))
            .putString(KEY_ALBUMS, SavedAlbumCodec.encode(albumsSnapshot))
            .putString(KEY_LIKED_IDS, gson.toJson(likedIdsSnapshot))
            .apply()                       // ← 异步提交，不等待落盘
    }
}
```

**(a)** 回答第 2 问：
* on-disk 路径：`SharedPreferences` 名 `ncrust_library`，键 `saved_songs`（`data/data/com.takahashirinta.ncrust/shared_prefs/ncrust_library.xml`）；
* **是异步 + 去抖**：最少 `300ms` 延迟，且 `.apply()` 本身不保证返回时已写盘；
* 进程死在窗口内 ⇒ 该次修改（以及窗口内所有修改）**全部丢失**，但 UI 上因为读的是内存 `cachedSongs` 而**看起来已经加好了**。这正是用户报告里「重启后消失」的第一条独立成因（与步骤 4-6 的刷新丢歌是两条不同的路）。

> 对照组：本仓库的**本地歌单**子系统为同一件事做出了**相反**的取舍 ——
> `LocalPlaylistStore.kt:46-53`：`apply()` 是异步的：进程在它落盘前被杀，用户会看到「删掉的歌又回来了」…所以本文件一律 `commit()`。
> 也就是说「用户明确动作必须立即落盘」这条纪律仓库里已经有了，只是 `LibraryManager` 没遵守。

### 步骤 4 — 「刷新」的四个触发点

| 触发点 | 位置 | 条件 |
|---|---|---|
| 进收藏页 / 切 tab（`LibraryScreen` 会被卸载重建，见 `MainActivity.kt:2233` 的 `when (selectedTab)`） | `LibraryScreen.kt:165-168` | 无条件（函数内部再判 cookie） |
| 登录后刷新信号 `refreshTrigger` 变化 | `LibraryScreen.kt:171-176` | `refreshTrigger > 0` |
| ncm 登录成功 | `MainActivity.kt:1959-1963` | `cookieRefreshTrigger > 0` |
| **冷启动预热**（App 启动就发） | `AppWarmup.kt:204-207` | `CookieManager.hasCookie(app)` |

```kotlin
// AppWarmup.kt:204-207
// 阶段三：收藏库刷新(已登录时)。3 次额外网络往返，同样谁都不等。
if (CookieManager.hasCookie(app)) {
    scope.launch { runCatchingCancellable { LibraryManager.refreshFromCloud(app) } }
}
```

**(a)** 「切换 tab / 重启 App」两条用户描述都能被解释：切 tab 让 `LibraryScreen` 重挂载 ⇒ `LaunchedEffect(Unit)` 再跑一次；重启则由 `AppWarmup` 在冷启动阶段三直接调 `refreshFromCloud`。

### 步骤 5 — `refreshFromCloud` **按 `likedIds` 重建**，`likedIds` 只含 ncm 曲目

```kotlin
// LibraryManager.kt:291-312
suspend fun refreshFromCloud(context: Context): Boolean = withContext(Dispatchers.IO) {
    if (!CookieManager.hasCookie(context)) { ... return@withContext false }        // ← 未登录：直接返回，不碰缓存
    val uid: Long = try { PlaylistApi.getCurrentUserId() } catch (e: Exception) { ... return@withContext false }
    var anySuccess = false
    try {
        val likedIds = PlaylistApi.getLikedTrackIds(uid)                          // ← ncm「我喜欢的音乐」trackIds
        Log.i(TAG, "refreshFromCloud: likedIds=${likedIds.size}")
        synchronized(idsLock) { cachedLikedIds = likedIds }
        val firstBatch = if (likedIds.size > LIKED_BATCH_SIZE) likedIds.take(LIKED_BATCH_SIZE) else likedIds
        val firstSongs = if (firstBatch.isNotEmpty()) PlaylistApi.getSongsByIds(firstBatch) else emptyList()
```

`getLikedTrackIds` 的来源是 ncm 红心歌单（`PlaylistApi.kt:623-660`，走 `/eapi/v6/playlist/detail` 的 `trackIds` + 截断补齐），其 id 空间就是 ncm songId。

### 步骤 6 — 重建循环：**不在 `likedIds` 里 = 当场丢弃**（本 Bug 的致命一步）

```kotlin
// LibraryManager.kt:313-329
// Bug2-②：合并而不是整体替换。旧实现每次只写回首屏 50 首，而收藏页每次进入都会
// 调用本方法，导致用户已翻页加载的歌曲被反复丢弃。
// 以云端 likedIds 顺序为准重建：新详情优先、缺失的用本地已加载项补齐；
// 用 Map 天然去重，同时剔除已取消收藏的歌（云端为真源）。
synchronized(songsLock) {
    val oldById = (cachedSongs ?: mutableListOf()).associateBy { it.id }
    val freshById = firstSongs.associateBy { it.id }
    val merged = LinkedHashMap<Long, SongItem>(likedIds.size)
    for (id in likedIds) {                       // ← 只遍历云端 id
        if (merged.containsKey(id)) continue
        val song = freshById[id] ?: oldById[id] ?: continue   // ← 云端没有的 id 连循环都进不来
        merged[id] = song
    }
    cachedSongs = merged.values.toMutableList()  // ← 本地独有条目在这一行被静默抹掉
    likedIdCursor = likedIds.takeWhile { merged.containsKey(it) }.size
}
```

**(a)** `oldById[id]` 这个「本地兜底」**只在 `id ∈ likedIds` 时才会被查询**。本地新增的 QQ 曲目 **id 不在 `likedIds` 里 ⇒ 从来不是候选 ⇒ 一定出局**。注释里那句「同时剔除已取消收藏的歌（云端为真源）」正是这个行为的自我说明：这条规则对 ncm 是对的，对**不属于这个云端的曲目**（QQ 合成 id）就是纯数据丢失。

### 步骤 7 — 丢完还写回磁盘

```kotlin
// LibraryManager.kt:354
if (anySuccess) scheduleFlush(context)
```

**(a)** 刷新成功 ⇒ 立刻 `scheduleFlush`，把「已丢歌」的内存状态盖到 `saved_songs` 上。所以这不是「这次显示错了、重启还能回来」，而是**真丢**。

### 步骤 8 — 「已加入库」Toast 是无条件的（UI 说谎）

| 调用点 | UI 文案来源 | 是否分支 |
|---|---|---|
| 播放器卡片「加入库」按钮 | `PlayerCard.kt:745-746` → `strings.addedToLibrary`（`zh_CN.kt:323` = `已加入库`） | ❌ 无条件 |
| 搜索结果长按菜单 | `SearchScreen.kt:502-506` | ❌ 无条件 |
| 搜索历史菜单 | `SearchScreen.kt:299-315` | ❌ 无条件 |
| 库页单曲长按菜单 | `LibraryScreen.kt:327-330` | ❌ 无条件 |
| 首页 / 专辑 / 歌手 / ncm 歌单详情 | `HomeScreen.kt:297-300`、`AlbumDetailScreen.kt:312-315`、`ArtistDetailScreen.kt:290-293`、`PlaylistDetailScreen.kt:270-273` | ❌ 无条件 |

也就是说：**Toast 只反映「内存 `MutableList` 里多了一条」，不反映「云端收藏成功」「不会被下次刷新抹掉」**。用户报告的「UI 短暂显示已加入」就是这一句 Toast。

---

## 4. 证据（原始命令输出）

### 4.1 `git log --oneline -20` —— 何时被引入

```
$ git log --oneline -20 -- app/src/main/java/com/takahashirinta/ncrust/library/LibraryManager.kt
2a46f63 fix(r8): 三个持久化结构的字段名不再交给 R8（离线曲目 / 续播进度 / 收藏专辑）
6da6560 fix(library): 我喜欢的歌可分页加载完整并支持播放全部
50bb447 perf: 冷启动不再重复拉首页 + 收藏库/缓存统计移出主线程
e3e9a4e fix: 收藏页专辑栏纯云端 + 单曲分页懒加载
12f2814 fix: 收藏同步单曲/专辑解耦，失败不再连坐清空另一栏
6dd6e73 fix: 收藏单曲改走红心歌单 playlist-detail，修复库仍空
9e92205 fix: 修 weapi 收藏接口路径错误导致收藏页单曲/专辑为空
5634500 feat: 接入云端收藏库——单曲=云端喜欢、专辑=云端专辑
11f10f3 perf: AlbumInfo @Immutable，补齐列表项稳定性
d4a4ba2 perf: 削峰低端机主线程与重组抖动，向 Apple Music 的流畅度靠拢
2bc7a43 Initial commit
EXIT=0
```

`git log --oneline -S "likedIds" -- .../LibraryManager.kt` → `5634500 6dd6e73 9e92205 e3e9a4e 6da6560`（`EXIT=0`）。

**「按云端 id 重建」是 v1.0 的第一版就有的结构，不是 v2.2.0 引入的回归**：

```
$ git show 5634500:app/src/main/java/com/takahashirinta/ncrust/library/LibraryManager.kt | sed -n '262,272p'
        // 重建收藏单曲：保留已有缓存元数据，缺失的批量补详情。
        val existing = getSavedSongs(context)
        val byId = existing.associateBy { it.id }
        val missingIds = likedIds.filter { it !in byId }
        val fetched = if (missingIds.isNotEmpty()) {
            runCatching { PlaylistApi.getSongsByIds(missingIds) }.getOrDefault(emptyList())
        } else emptyList()
        val fetchedById = fetched.associateBy { it.id }
        val rebuilt = likedIds.mapNotNull { byId[it] ?: fetchedById[it] }

        synchronized(songsLock) { cachedSongs = rebuilt.toMutableList() }
EXIT=0
```

`6da6560`（v2.5.x）把它从 `likedIds.mapNotNull { ... }` 改成 `for (id in likedIds)` 的合并版（`commit message` 自述「② refreshFromCloud 改为按 likedIds 顺序合并…同时剔除已取消收藏的歌」），**丢非云端条的语义一次都没变**。

### 4.2 `git log -S "isQqId"` —— 跨源闸门从未出现在这个文件里

```
$ git log --oneline -S "isQqId" -- app/src/main/java/com/takahashirinta/ncrust/library/LibraryManager.kt
EXIT=0
--stderr-- (empty)
```

**阴性结果声明**：`stdout` 为空、`stderr` 为空、退出码 `0`（`git log -S` 无命中时正常退出 0）。阳性对照（同一命令、同一文件、换符号）：

```
$ git log --oneline -S "saveSong" -- app/src/main/java/com/takahashirinta/ncrust/library/LibraryManager.kt
5634500 feat: 接入云端收藏库——单曲=云端喜欢、专辑=云端专辑
d4a4ba2 perf: 削峰低端机主线程与重组抖动，向 Apple Music 的流畅度靠拢
2bc7a43 Initial commit
EXIT=0
```

全仓 `isQqId` 的命中（`grep -rn "isQqId|qqRawId|sourceOfId|QQ_ID_FLAG" app/src/main/java --include=*.kt`，`EXIT=0`）里**没有一处**在 `library/` 的收藏写路径上：`library/SearchHistoryCodec.kt:283`（**搜索历史**的 id 形状判定，与收藏无关）、`SearchHistoryMigration.kt:63`、`ReportGate.kt:76,92,193`、`SongDetailScreen.kt:255`、`PlayerViewModel.kt:331`、`QqApi.kt:393`、`QqSongMapper.kt:100`、`TrackKey.kt:106,135`、`MainActivity.kt:694,703`。**`PlaylistApi.likeSong` 也没有**（见 4.4）。

### 4.3 可执行探针 ① —— 重建后 QQ 曲目消失

```
$ cd /tmp/probe-qq && javac MergeProbe.java ; echo "JAVAC_EXIT=$?"
JAVAC_EXIT=0
$ java MergeProbe ; echo "JAVA_EXIT=$?"
QQ 合成 id           = 4611686019661955794  (isQqId=true)
QQ_ID_FLAG           = 4611686018427387904
ncm id 上界(2^40) = 1099511627776
rebuild 前 cached    = [4611686019661955794, 101, 999]
rebuild 后 cached    = [101]
QQ 曲目存活?         = false
ncm 999 存活?     = false
ncm 101 存活?     = true
JAVA_EXIT=0
```

> 首个版本里我把「存活 ⟺ ∈ likedIds」直接对 5 个样本断言，其中 `102/103` 是**云端新歌、本地原本没有**，被 `?: continue` 跳过后判为「不成立」——那是我的样本不合谓词前提，不是谓词错。修正后的探针只断言「**刷新前已缓存**的 id」，见下。

### 4.4 可执行探针 ② —— 精确的存活谓词

```
$ cd /tmp/probe-qq && javac PredicateProbe.java ; echo "JAVAC_EXIT=$?"
JAVAC_EXIT=0
$ java PredicateProbe ; echo "JAVA_EXIT=$?"
cached id=4611686019661955794    survived=false in likedIds=false
cached id=155                    survived=true  in likedIds=true 
cached id=999                    survived=false in likedIds=false
cached id=101                    survived=true  in likedIds=true 
谓词「刷新前已缓存 X 存活 <=> X ∈ likedIds」成立? = true
max QQ 合成 id 是否可能落在 likedIds 值域? false
JAVA_EXIT=0
```

样本集：`likedIds = 101..160`（首屏详情 = 101..150），`cachedSongs = [QQ 合成 id, 155(云端第 2 批), 999(本地新增但 like 未生效), 101]`。

**存活谓词（精确表述）**：

> 设刷新前 `cachedSongs` 含 id = X。当且仅当下列之一成立时 X 存活：
> 1. `refreshFromCloud` 在 `LibraryManager.kt:292-295`（无 cookie）或 `:296-301`（取 uid 失败）提前 return；或
> 2. `getLikedTrackIds(uid)` 抛异常（`:331-333` 的 catch，此时 `cachedSongs` 不动）；或
> 3. **X ∈ `getLikedTrackIds(uid)`**。
>
> 注意 `firstSongs`/`oldById` 两条兜底都要先 `id ∈ likedIds` 才可能被查到（`:321-324`），所以它们**不构成**额外豁免。

第 3 条对 QQ 曲目恒假：`SourceIds.qqId` 的定义是 `QQ_ID_FLAG or raw`（`MusicSource.kt:198-201`），`QQ_ID_FLAG = 1L shl 62 = 4611686018427387904`（`:164`），而 ncm songId 按仓库自己的实测结论「十进制百万~十亿量级（远小于 `2^40`）」（`MusicSource.kt:170-174`、`ReportGate.kt:38-48`）。值域不相交 ⇒ **QQ 曲目 100% 被丢**。

### 4.5 `pushLike` 会把 QQ 合成 id 发给 ncm，且**没有任何闸门**

```kotlin
// LibraryManager.kt:184-186（HEAD）
private fun pushLike(songId: Long, like: Boolean) {
    ioScope.launch { runCatching { PlaylistApi.likeSong(songId, like) } }   // ← 返回值被丢弃
}
```

```kotlin
// network/PlaylistApi.kt:690-710
suspend fun likeSong(songId: Long, like: Boolean): Boolean = withContext(Dispatchers.IO) {
    val payload = mapOf(
        "alg" to "itembased",
        "trackId" to songId.toString(),          // ← 没有任何 id 校验：QQ 合成 id 原样发出
        "like" to like.toString(),
        "time" to (System.currentTimeMillis() / 1000).toString(),
        "e_r" to "TRUE",
        "csrf_token" to (RetrofitClient.getCsrfToken().orEmpty())
    )
    val http = try {
        RetrofitClient.eapiPostOfficial("/eapi/radio/like", payload)
    } catch (e: Throwable) { ...; return@withContext false }
    ...
    code == 200
}
```

**(a)** 客户端后果（可证）：
1. `pushLike` 走的是 `runCatching { }` + 丢掉布尔返回值 ⇒ **失败静默**，用户只看得到 Toast；
2. `likeSong` 的 KDoc 自述（`PlaylistApi.kt:680-689`）：「like 写操作受网易账号/IP 级风险控制，本账号四种协议变体…均被 `-460「检测到您的网络环境存在风险」` 或异常响应拦截而读取全部正常」——**即使是正常的 ncm 曲目，like 也可能根本没生效**；
3. 服务端后果 **未验证**（见 §10）——本机没有登录态、不做任何网络请求。

对照仓库已有的同类闸门（这正是本 Bug 的形状已经被修过一次的地方）：

```kotlin
// player/ReportGate.kt:74-80
fun mayReport(target: Target, songId: Long): Boolean {
    if (songId <= 0L) return false
    val isQq = SourceIds.isQqId(songId)
    return when (target) {
        Target.NETEASE_WEBLOG -> !isQq
        Target.QQ -> isQq
    }
}
```

`ReportGate` 的 KDoc（`ReportGate.kt:20-40`）把根因写得比本报告还清楚：**「一个 QQ 曲目的合成 id 会原样通过那两条卫语句」**——v2.5.5 只把它修在 `PlayReporter`（webLog 上报）这一条链路上，**`/eapi/radio/like` 这条写链路当时没有一起修**。

`qq/QqMusicSourceProvider.kt:36-41` 还留着一句设计声明，与本 Bug 直接矛盾：

```kotlin
 * ## 本版**不做**的事（避免误以为已支持）
 * - 不把 QQ 歌曲加进ncm歌单/收藏（那需要「本地歌单」这个尚不存在的概念，
 *   而且ncm的歌单写接口会拒绝外部曲目）；
```

（这句话写于 v2.1.0；v2.3.0 已经建好了「本地歌单」，但**没有回头把这条约束接到 `LibraryManager` 上**。）

### 4.6 QQ 侧：`dirId` / `isFavorite` 与写入无关（阴性结果 + 阳性对照）

```
$ grep -n "Playlist(|isFavorite|isOwned|dirId" app/src/main/java/.../qq/QqCatalog.kt
EXIT=1
--stdout-- (empty)
--stderr-- (empty)
$ grep -c "Playlist(" app/src/main/java/.../qq/QqPlaylistParser.kt     # 阳性对照
1
EXIT=0
```

说明：`QqCatalog.kt` 里**没有** `Playlist` 构造、`isFavorite`、`isOwned`、`dirId` 的任何出现（`EXIT=1`、`stderr` 空、阳性对照命中 1 次）。这些字段只在 `QqPlaylistParser.kt` 里生成：

```
app/src/main/java/com/takahashirinta/ncrust/qq/QqPlaylistParser.kt
 93:    /** 「我喜欢」的固定目录号（实测 + 参考实现 `like_song(dirid=201)` 一致）。 */
 94:    const val FAVORITE_DIR_ID = 201L
191:        val dirId = o.optLong("dirId", 0L).takeIf { it > 0L }
192:            ?: o.optLong("dirid", 0L).takeIf { it > 0L }
226:            isFavorite = dirId == FAVORITE_DIR_ID,
227:            dirId = dirId,
```

`dirId` 的下游消费者只有两处：`QqPlaylistRepository.loadDetail(dirId=...)`（**寻址**，`:199-233`）与 `fetchFavoritesSafely`（取 `encrypt_uin`，`:163-188`）；`LibraryLibraryManager`... 准确说 `LibraryManager` **一次都没读到它**。所以「QQ 特殊 dirId 让写入被忽略」**不成立**。

### 4.7 QQ 歌单详情页在 HEAD **没有**「加入库」入口

```
$ grep -c "LibraryAdd" app/src/main/java/.../ui/screen/QqPlaylistDetailScreen.kt
0
EXIT=1
$ grep -c "LibraryAdd" app/src/main/java/.../ui/screen/PlaylistDetailScreen.kt   # 阳性对照
2
EXIT=0
$ git show 85b2486:app/.../ui/screen/QqPlaylistDetailScreen.kt | grep -c LibraryAdd
0
EXIT=1
$ grep -n "onShowSongMenu" app/src/main/java/.../ui/screen/QqPlaylistDetailScreen.kt
79:    onShowSongMenu: (SongItem, List<SongMenuAction>) -> Unit = { _, _ -> },
257:                onShowMenu = { onShowSongMenu(song, emptyList()) },
EXIT=0
```

`git log --oneline -S "actionAddToLibrary" -- .../QqPlaylistDetailScreen.kt` → 空输出、`EXIT=0`、`stderr` 空；阳性对照 `-S "onShowSongMenu"` → `85b2486 feat(ui): QQ 歌单列表与详情页（按源隔离、离线可看、手动刷新）`。

**(a)** 结论：v2.2.0 起至今，QQ 歌单详情页把 `emptyList()` 交给全局菜单（`MainActivity.kt:2475-2524` 追加「加入歌单 / **加入本地歌单** / 添加到下一首播放 / 转到歌手 / 转到专辑 / 单曲信息」），所以那张菜单里**没有「加入库」**。QQ 曲目能点到「加入库」的入口只有：

* **播放器卡片**（`PlayerCard.kt:737-750`，不管在放谁的歌）；
* **搜索结果 / 搜索历史**（`SearchScreen.kt:502-506`、`:299-315`，聚合搜索会混入 QQ 结果）。

> **给复现的人**：如果你在 QQ 歌单详情里点的是「**加入本地歌单**」（`strings.localPlaylistAddTrack`），那条路走的是 §6 的本地歌单子系统，**不会**掉——两条路的用户可见文案只差两个字，务必分清。若确实在 QQ 歌单列表里看到了「加入库」，那说明设备上的包比 HEAD 新或旧，请附上 `versionName` 与菜单截图。

### 4.8 文档里从未记录过这条链路

```
$ grep -rn "refreshFromCloud" docs --include=*.md
EXIT=1
--stdout-- (empty)
--stderr-- (empty)
$ grep -rl "LibraryManager" docs --include=*.md            # 阳性对照
docs/verification/v2.5.0/probe-cover.md
docs/verification/v2.5.4/probe-search-history.md
docs/verification/v2.5.4/EVIDENCE.md
docs/verification/v2.5.5/probe-raw/playreporter/code-excerpts.md
docs/verification/v2.5.5/probe-r8-keys.md
EXIT=0
```

### 4.9 次要缺陷（顺带发现，同一文件）：未加载的缓存会被 flush 成 `[]`

```kotlin
// LibraryManager.kt:156-165
synchronized(songsLock) { songsSnapshot = cachedSongs?.toList() ?: emptyList() }   // ← null ⇒ []
...
.putString(KEY_SONGS, gson.toJson(songsSnapshot))                                  // ← 把 [] 写进 saved_songs
```

`subscribeAlbum` / `removeAlbum`（`:261-283`）只调 `ensureAlbumsLoaded`，**不调** `ensureSongsLoaded`，然后 `scheduleFlush`。因此在「`cachedSongs` 尚为 null」的窗口里收藏一张专辑，会把 `saved_songs` 覆盖成 `[]`；若 `LibraryManager.preload`（`AppWarmup.kt:109-110`）随后才跑，`ensureSongsLoaded` 会**从被清空的盘上读回空表** ⇒ 收藏单曲整表消失（直到下一次 `refreshFromCloud` 才回来）。

证据等级：**(a)** 读代码证明写入路径；**(c)** 具体时序可达性（需要 preload 与订阅动作竞争）未在真机复现。修复只需「缓存为 null 时不要写这个键」。

---

## 5. ncm 是否同样受影响

**受影响，但判定条件不同。**

| 情形 | 加入库时 | like 推送结果 | 下次 `refreshFromCloud` | 结果 |
|---|---|---|---|---|
| QQ 曲目（合成 id） | 加进 `cachedSongs` | 必然无效（跨源 id；客户端无闸门、服务端不认） | `id ∉ likedIds`（值域不相交） | **必然消失** |
| ncm 曲目，已登录且 like 成功 | 加进 `cachedSongs` | `code == 200`，云端红心新增 | `id ∈ likedIds` | 存活 |
| ncm 曲目，like 被风控拦（`-460`，KDoc 自述 v1.3.0 实测该账号四种协议变体均被拦） | 加进 `cachedSongs` | 失败，静默 | `id ∉ likedIds` | **消失** |
| ncm 曲目，加的时候**未登录** | 加进 `cachedSongs` | `pushLike` 根本不发（`saveSong:204` 的 `isLoggedIn` 卫语句） | 登录后 `refreshFromCloud` 跑起来 | **消失** |
| ncm 曲目，离线加 | 加进 `cachedSongs` | 请求失败（`runCatching` 吞） | 同第 3 行 | **消失** |

**精确存活谓词**（与 §4.4 同一句）：

```
刷新前已缓存 X 存活  ⟺  X ∈ PlaylistApi.getLikedTrackIds(uid)
                       （或 refreshFromCloud 在取 likedIds 之前就 return/抛异常）
```

**「ncm 也会丢」和「QQ 必然丢」的差别只在第一条谓词的可满足性**：QQ 的合成 id 永远无法进入 ncm 的 `likedIds`，ncm 的 id 只要 like 真的生效就能进入。所以：

* 用户报告的「QQ 曲目入库后消失」是**确定性缺陷**，不需要任何额外条件（只要他还登录着 ncm——`refreshFromCloud` 在无 cookie 时提前 return，反而「不会丢」）；
* 「ncm 曲目入库后消失」是**条件性缺陷**，触发条件是 like 写失败/未登录——而 `PlaylistApi.kt:686-688` 的 KDoc 说明这个条件在真机上**并不罕见**。

一个可以立刻用眼睛验证的旁证：库页单曲 tab 的表头计数用的是 `likedTotal = LibraryManager.getLikedSongIds(context).size`（ncm 红心歌单长度，`LibraryScreen.kt:109,151,309`），而列表渲染的是 `savedSongs`（`LibraryScreen.kt:319`）。**在「加入库」之后、下一次刷新之前，列表条数会大于表头声明的总数** —— 那个差值就是马上要被丢掉的本地新增条目。

---

## 6. 与 v2.2.0「只加不减 + tombstone」的关系

**结论：tombstone 逻辑与此 Bug 无关，也没有被误用；它所在的子系统在 HEAD 是正确且自洽的。真正的问题是 `library/LibraryManager` 从来没有获得过「只加不减」这条纪律。**

### 6.1 两个子系统对照

| 维度 | `library/LibraryManager.kt`（本 Bug 现场） | `local/LocalPlaylist*.kt`（v2.2.0/v2.3.0 子系统） |
|---|---|---|
| 语义 | **ncm 红心歌单镜像**（KDoc：`云端为真源`） | **本地歌单**（可混装两源，只加不减） |
| prefs 文件 / 键 | `ncrust_library` / `saved_songs`、`saved_albums`、`liked_ids` | `ncrust_local_playlists` / `playlists`、`tracks:<source>:<ownerId>:<id>` |
| 身份 | 裸 `Long` id（`cachedSongs.none { it.id == song.id }`，`:199`） | `TrackKey(source, id)`（`LocalPlaylistModels.kt:93-102`，判等只看 `(source,id)`，`TrackKey.kt:75-78`） |
| 有条目来源标记？ | **没有**（HEAD） | **有**：`LocalTrackOrigin.REMOTE / LOCAL`（`LocalPlaylistModels.kt:24-30`） |
| 有 tombstone？ | **没有**（刷新即物理丢弃） | **有**：`tombstoned` 留在列表里（`:93-102`、`:199-230`） |
| 同步合并函数 | `refreshFromCloud` 内联的重建循环（`:317-329`）—— **以云端 id 为唯一遍历源** | `LocalPlaylistSync.merge`（`:158-197`）—— **以本地列表为输出基底，只追加** |
| 用户动作落盘 | `scheduleFlush` = 300ms 去抖 + `apply()`（`:135-144,165`） | `LocalPlaylistStore` 一律 `commit()`（`LocalPlaylistStore.kt:46-53`） |
| 崩坏方式 | **静默丢失**（连记录都不留，用户无法解释） | tombstone 只增不减 ⇒ 靠 `trimToLimit` 有界回收（`:302-313`） |

### 6.2 tombstone 的方向性：**正确**（可执行验证）

`LocalPlaylistSync.merge` 的实现要点（`LocalPlaylistModels.kt:163-196`）：先把 `existing` **原样全部放进输出**（`out.add(t)`，`:170`），只有「本地完全没见过」的远程曲目才可能被追加（`:175-195`），且命中 tombstone 只 `skipped++` 而不改任何本地条目（`:182-183`）。`remove` 是**唯一**写 `tombstoned = true` 的地方（`:217-230`）。

```
$ cd /tmp/probe-qq && javac TombstoneProbe.java ; echo "JAVAC_EXIT=$?"
JAVAC_EXIT=0
$ java TombstoneProbe ; echo "JAVA_EXIT=$?"
同步后            = [4611686019661955794(REMOTE), 555(REMOTE), 556(REMOTE)]
手动加入 9999 后  = [4611686019661955794(REMOTE), 555(REMOTE), 556(REMOTE), 9999(LOCAL)]
远程缩到 1 首后   = [4611686019661955794(REMOTE), 555(REMOTE), 556(REMOTE), 9999(LOCAL)]
手动加入的 9999 仍可见? = true ；列表里出现 tombstone? = false
删 555 后再同步   = [4611686019661955794(REMOTE), 555(REMOTE,TOMB), 556(REMOTE), 9999(LOCAL)]
555 仍被 tombstone 挡住? = true
手动加入的 9999 仍可见? = true
JAVA_EXIT=0
```

同一批规则在仓库里已有 JVM 单测钉住（`app/src/test/java/com/takahashirinta/ncrust/local/LocalPlaylistSyncTest.kt`，**27** 个 `@Test`（`grep -c "@Test" …` → `27`，`EXIT=0`），其中包括与本题最相关的四条（`grep -n "@Test" -A1 …` 的真实输出）：

```
 91:    fun `rule3 远程没有的歌 本地保留（只加不减）`()
100:    fun `rule3 远程整体消失时 本地一首都不删`()
147:    fun `rule5 手动新增为 LOCAL 且同步时永远保留`()
124:    fun `rule4 tombstone 对 LOCAL 来源同样生效`()
```

**(a)+(b)** 因此对第 4 问的四个子问题：

1. **本地加入的歌会被误判成「远程删除」并 tombstone 吗？** 在 `local/` 里**不会**（`merge` 从不调用 `remove`，也从不写 `tombstoned`）；
2. **tombstone 方向对吗？** 对：只有用户显式 `remove` 才打 tombstone，且只影响「远程有、本地 tombstone」的跳过判定（规则 4），不影响 LOCAL 条目的存活；
3. **那本 Bug 与它有关吗？** 只有在**语义层面**有关：`LibraryManager` 缺的正是 `local/` 已经实现的三件事 —— `origin` 标记、只追加的合并、以及「用户动作立即落盘」。它丢歌的方式比 tombstone **更粗暴**（物理丢弃，连"删过"的记录都不留）；
4. **有没有可能用户其实是在本地歌单里丢了歌？** 本次探针**没有找到**这样一条链路：`LocalPlaylistRepository.addTrack`（`:167-177`）→ `LocalPlaylistSync.addManual`（origin=LOCAL）→ `LocalPlaylistStore.writePlaylistWithTracks`（`commit()`），全链路没有删除点。若真机上确实存在「本地歌单里的歌也消失」，那需要另一轮探针（本次未复现，列入 §10）。

---

## 7. QQ 特殊 `dirId` / 收藏歌单是否导致写入被忽略

**不导致。** 逐条给判据：

| 待验证的猜想 | 结论 | 证据 |
|---|---|---|
| `dirId == 201`（我喜欢）会让 `saveSong` 直接 return | **否** | `saveSong` 签名只收 `(Context, SongItem)`（`LibraryManager.kt:195`），`dirId` 根本传不进来 |
| QQ 收藏歌单（`isOwned=false`）的曲目 id 有特殊形态，被隐式过滤 | **否** | `Playlist`/`isFavorite`/`isOwned`/`dirId` 只在 `QqPlaylistParser.kt` 生成（`:191-228`），`QqSongMapper.fromSongObject` 对详情页与搜索页**同一套映射**（`QqPlaylistParser.kt:256-258` 显式要求复用），id 一律是 `SourceIds.qqId(rawId, mid)`（`QqSongMapper.kt:94`） |
| `isFavorite` 被某处当作「不可写」判据 | **否** | `grep -rn "isFavorite" app/src/main/java --include=*.kt`（`EXIT=0`，命中 11 处）的全部消费者只有：排序（`LibraryPlaylistsTab.kt:142,143`）、副标题（`:378`）、生成侧（`QqPlaylistParser.kt:226`）、取 `encrypt_uin` 用（`QqPlaylistRepository.kt:167`）、缓存 DTO（`PlaylistCacheCodec.kt:60,99,388,411`）、模型定义（`PlaylistModels.kt:70,81`）——**没有一处写入路径** |
| `dirId=0`（收藏歌单没有自己的目录号）让详情拉不到曲目，因此「没歌可加」 | **否**（有兜底） | `QqPlaylistRepository.loadDetail` 对 `dirId=0` 传 `disstid = key.id`（`:229-233`），注释说明「传 0 让服务端按 `disstid` 解析」 |

**唯一与「写入」有关的 QQ 侧事实是**：QQ 这边**没有**任何「收藏」写接口被实现（`QqMusicSourceProvider.kt:36-41` 明文声明不做云歌单/收藏），所以 QQ 曲目入库时**只能**落到 ncm 的 `like` 上——而那一定失败（§4.5）。换句话说：**不是 `dirId` 让写入被忽略，而是这条写入从一开始就写错了地方**。

---

## 8. 队列 / 待播槽位是否受影响

**结构上不受影响，但有三处间接耦合。**

### 8.1 不受影响的部分（可证）

* 队列判重与身份：`SongItem.dedupeKey = TrackKey.ofSong(this).tag`（`SongSourceExt.kt:68-69,77`），`TrackKey.equals` 只看 `(source, id)`（`TrackKey.kt:75-78`）——**从不读 `LibraryManager`**；
* 待播槽位：`PreloadSlot.decide(...)` 的判据是 `pendingSongId` / `pendingUrl` 与 `SourceIds.parseMediaId(mediaId)`（`PreloadSlot.kt:71-80`：`identityFromMediaId` / `decide`），同样不读收藏库；
* 因此：**一首已经进了队列的 QQ 曲目不会因为 `refreshFromCloud` 而被移除**；「入库消失」与「队列消失」是两个互不相干的症状。用户如果观察到队列里的歌也没了，那要找另一条根因（本次未发现）。

### 8.2 三处间接耦合（同一根因的下游表现）

| 耦合点 | 位置 | 表现 | 等级 |
|---|---|---|---|
| 车机 / Android Auto「我喜欢的音乐」节点 | `PlaybackService.kt:861` `LIKED_ID -> LibraryManager.getSavedSongs(this).map { songItem(it) }` | 「入库」后 QQ 曲目会**短暂出现在 ncm 的「我喜欢的音乐」节点**里，刷新后又消失；`songItem()` 用 `SourceIds.mediaId(song.mediaSource, song.id)` 编码音源（`:836-839`），所以**播放不会串源**，只是列表内容随刷新抖动 | (a) 读代码 |
| 库页「播放全部」`onPlayAllLiked` | `MainActivity.kt:2282-2303` | 先用 `getSavedSongs()`（**可能含 QQ 曲目**）`replaceQueueAndPlay`，再用 `loadAllLikedSongs()`（**只含 ncm 红心 id**，`LibraryManager.kt:400-419`）补队尾 ⇒ 首次会播到 QQ 曲目，补齐阶段又"不认识"它（靠 `known` 去重兜住，不会重复入队） | (a) 读代码 |
| 口味匹配 / 按专辑播放全部 | `ArtistReco.kt:112`（`getSavedSongs().flatMap { it.artists }` 直接拿 artist id 去比 ncm 相似艺人锚点）；`MainActivity.kt:1803`（`getSongsByAlbumId` 用 `it.album?.id == albumId`，**没有音源过滤**） | QQ 曲目的 **QQ artistId / QQ albumId** 被当作 ncm 的 id 使用：口味匹配可能命中错误的锚点；「按专辑播放全部」可能因为一首 QQ 曲目的 album.id 恰好等于某 ncm 专辑 id 而返回错误的曲目集合 | (a) 读代码；(c) 实际撞号概率未实测（`SongItem.album.id` 在 QQ 侧来自 `album.id`，`QqSongMapper.kt:117-123`） |

**(a)** 这三处都不是「用户丢数据」，但它们是**同一个设计缺口**的下游症状：`cachedSongs` 里混进了非 ncm 条目，而所有消费者都默认「这里面的 id 都是 ncm 的」。

---

## 9. 修复方向建议

> 写作时已对照工作区那份**未提交 WIP**（`SavedSongEntry` + `SavedSongSync.merge` + `flushNow` + `pushLike` 的 `isQqId` 闸门，见报告开头的 md5）。下面标注 **[WIP 已覆盖]** / **[WIP 未覆盖]**，未覆盖项才是剩余工作量。

### 9.1 最小充分修复（三件，缺一不成立）

**A. 给 like 写链路加跨源闸门 —— 复用 `ReportGate`，不要新造判据 [WIP 已覆盖]**

* 现状：`LibraryManager.kt:184-186` 直接 `PlaylistApi.likeSong(song.id, true)`，`PlaylistApi.kt:690-710` 无任何 id 校验；
* 改法（与 v2.5.5 · B 完全同形，判据只有 `SourceIds.isQqId`）：
  * 在 `ReportGate.Target` 增加一个目标（例如 `NETEASE_LIKE`），`mayReport` 里复用 `!isQq` 分支，`blockReason` 自动获得 `cross-source:qqmusic->NETEASE_LIKE` 文案；
  * `pushLike` 前置 `if (!ReportGate.mayReport(Target.NETEASE_LIKE, songId)) { blockedWrites.incrementAndGet(); return }`，被拦时**只记本地计数**（沿用 `ReportGateCounters` 的落盘纪律）。
* 为什么不是「悄悄不发」：拦下之后**必须**同步改 B/C，否则「入库」在本地也不会持久（见下）。

**B. 收藏库改为「镜像 ∪ 本地独有条目」—— 把 `local/` 的三条纪律搬过来 [WIP 已覆盖]**

* 现状：`LibraryManager.kt:317-329` 的遍历源只有 `likedIds`；
* 改法：给 `saved_songs` 的每条加 `origin ∈ {REMOTE, LOCAL}` 与 `tombstoned`（与 `LocalTrackOrigin` 语义一致但**不要复用该类型**——两个子系统各自演进，见 `LocalPlaylistModels.kt:18-30` 对 origin 的语义声明），然后把重建规则改成与 `LocalPlaylistSync.merge` 同构：

| # | 规则 | 落点 |
|---|---|---|
| 1 | 云端有 + 本地无 + 不在 tombstone → 追加 `origin=REMOTE` | merge |
| 2 | 云端有 + 本地有 → 原样保留（不改 origin/顺序） | merge |
| 3 | **云端无 + 本地有 → 一律保留**（不再问「云端为什么没有」） | merge（**这行不写，本 Bug 就还在**） |
| 4 | 云端有 + 本地 tombstone → 跳过 | merge |
| 5/7 | 用户「加入库」→ `origin=LOCAL`；已存在则清 tombstone 且**保持原下标** | `saveSong` |
| 6 | 用户「移出库」→ 打 tombstone，**不物理删**（否则下次刷新复活） | `removeSong` |

* 顺序与游标：`likedIdCursor` 必须只按**ncm** id 前缀计算（WIP 已经这么做了：只把 `trackKey.source == NETEASE` 的条目计入 `coveredIds`），否则 QQ 条目会把游标顶到错位处。

**C. 用户动作立即落盘 —— 去抖只留给批量补详情 [WIP 已覆盖]**

* 现状：`saveSong` → `scheduleFlush`（300ms + `apply()`）；
* 改法：新增单次用户动作专用的立即落盘路径（不 `delay`，仍在 IO 线程；WIP 的 `flushNow` 就是这个形状），并**保留** `scheduleFlush` 给 `loadMoreLikedSongs`（`LibraryManager.kt:389`）这类高频批量写；
* 若要在「立即」上再进一步，可像 `LocalPlaylistStore.kt:46-53` 那样用 `commit()`；但至少要保证 `.apply()` 之前不再有 300ms 的窗口。

### 9.2 持久化形状：显式 `@SerializedName` DTO + 三形状读路径 + 纯逻辑 codec + JVM 单测 [WIP 部分覆盖]

`KEY_SONGS` 现在是**裸 `List<SongItem>` 数组**（`LibraryManager.kt:161` `gson.toJson(songsSnapshot)`），加了 `origin/tombstoned` 之后必须能读老数据：

1. **DTO 显式字段名**（R8 教训：`2a46f63 fix(r8): 三个持久化结构的字段名不再交给 R8`）：
   ```kotlin
   data class SavedSongDto(
       @SerializedName("track") val track: SongItem,
       @SerializedName("origin") val origin: String? = null,      // 缺失 ⇒ REMOTE（老数据都是镜像来的）
       @SerializedName("tombstoned") val tombstoned: Boolean? = null,
       @SerializedName("addedAt") val addedAt: Long? = null,
   )
   ```
   注意 `origin` 必须**可空 + 有默认值**：Gson 走 Unsafe、不调用构造函数（AGENTS.md「歌词缓存字段迁移策略」v1.9.0/v1.9.2 两次实测）。
2. **三形状读路径**（与 `SavedAlbumCodec` / `LocalPlaylistCodec` 同构，纯逻辑、无 Android 依赖）：
   * 形状 1：`null`/空串 ⇒ 空表；
   * 形状 2：`[ {...SongItem...}, ... ]`（v2.6.0 之前的裸数组）⇒ 全部按 `origin = REMOTE`；
   * 形状 3：`{ "version": 2, "entries": [ {...SavedSongDto...} ] }` ⇒ 逐条读，未知 origin 回落 REMOTE；
   * **逐条容错**（`SavedAlbumCodec` 的 v2.5.5 · A 教训：一个字节坏了不能让整张表消失）。
3. **纯函数 + 单测**：把合并规则抽成 `SavedSongSync.merge(existing, likedIds, freshById, now)`（WIP 已经这么做了），单测按规则表逐条钉死，命名风格照抄 `LocalPlaylistSyncTest`：
   `rule3 云端没有的本地条目一律保留（QQ 合成 id 不被刷新丢掉）`、
   `rule3 QQ 曲目在 refreshFromCloud 后仍然可见`、
   `rule5 加入库为 LOCAL 且刷新时永远保留`、
   `rule6 移出库只打 tombstone 不物理删除`、
   `rule1/2/4 ...`、
   `codec 裸数组（v1 形状）按 REMOTE 读入`、
   `codec 缺 origin 字段按 REMOTE 而不是丢弃`。
   **另需一条端到端纯逻辑用例**：`BIT62_QQ_ID ∉ likedIds(RANGE_NETEASE)` —— 把 §4.4 的探针固化成单测，防止有人日后把「值域不相交」当假设用错。
4. **有界失败**：
   * `origin=LOCAL` 的条目需要一个上界（例如每表最多 N 条 / 最老 T 天），到期**降级为 REMOTE**（若已在 `likedIds`）或提示用户「该曲目未能同步到云端」，**绝不静默丢弃**；
   * tombstone 回收沿用 `LocalPlaylistSync.trimToLimit` 的取舍：**先丢最旧的 tombstone，绝不丢活动条目**（`LocalPlaylistModels.kt:302-313`）；
   * `refreshFromCloud` 的两条失败分支（`:331-333`、`:350-352`）必须**继续不碰** `cachedSongs`（现在就是这样，别改坏）。

### 9.3 UI 诚实化 [WIP 未覆盖 —— 建议优先补]

`saveSong` 目前返回 `Unit`，调用方无从判断，于是 8 处 Toast（§3 步骤 8 的表，`saveSong` 调用点 8 个、Toast 也 8 个）全是无条件的。最小改法：

1. `saveSong` 改为返回一个小结果类型（`ADDED_REMOTE` / `ADDED_LOCAL_ONLY` / `ALREADY` / `REJECTED`），**不改任何既有语义**；
2. Toast 分支：
   * `ADDED_REMOTE` → 现有 `strings.addedToLibrary`（`已加入库`）；
   * `ADDED_LOCAL_ONLY` → 新文案（8 个语言文件都要加，i18n 铁律），例如「已加入库（本机，QQ 曲目不同步云端）」；
   * `REJECTED` → 明确拒绝文案；
3. **更省的替代方案**：对 QQ 曲目把菜单项/按钮文案直接换成已有的 `strings.localPlaylist.addTrack`（`加入本地歌单`，`zh_CN.kt:394`）并走 `LocalPlaylistRepository.addTrack` —— **零新增 i18n**、零新数据结构，而且那条路本来就正确（§6）。缺点是要在菜单构造处判断音源（`PlayerCard.kt:737`、`SearchScreen.kt:502`、全局菜单 `MainActivity.kt:2475-2524`），比 API 改动更分散。

### 9.4 顺带修掉的次要缺陷 [WIP 未覆盖]

* **`flushToDisk` 不要写未加载的缓存**（§4.9）：`cachedSongs == null` 时**跳过 `KEY_SONGS`**（`cachedAlbums`/`cachedLikedIds` 同理）。这是 3 行改动，能关掉「收藏专辑导致收藏单曲整表被清空」的窗口；
* **下游加音源过滤**（§8.2）：`ArtistReco.kt:112` 与 `MainActivity.kt:1803` 至少过滤 `musicSource == NETEASE`，或改用 `TrackKey` 做比较；
* **QQ 曲目入库的替代出路**：`QqPlaylistDetailScreen.kt:196-218` 已有「转存为本地歌单」，可在 QQ 曲目的菜单里把「加入库」替换为「加入本地歌单」，让用户有一条**真正持久**的路（这与 §9.3 的替代方案是同一件事）。

---

## 10. 未验证项

1. **未运行 App / 未跑 `./gradlew test`**：本机没有连接的设备；构建还依赖同级 `Kanesumi-sec-a` 复合构建与网络。§4.3/§4.4/§6.2 的探针是**逐句转写的 Java 程序**（OpenJDK 26.0.2），不是 App 字节码。
2. **服务端行为未实测**：QQ 合成 id 发到 `/eapi/radio/like` 之后 ncm 返回什么 code、是否会在账号上留下脏数据 —— 本机无登录态，**没有发任何网络请求**。
3. **`likeSong` 风控失败率未复测**：`PlaylistApi.kt:686-688` 的「四种协议变体均被 `-460` 拦截」是 v1.3.0 的实测结论，本次未重新测量 ⇒ §5 里「ncm 也会丢」的概率无法量化。
4. **用户实际点击路径未确认**：HEAD 的 QQ 歌单详情页菜单里**没有**「加入库」（§4.7）。用户报告里的「QQ 歌单列表长按 → 入库」与 HEAD 不符，可能是：(a) 他点的是「加入本地歌单」（那样本 Bug 不适用）；(b) 他是在播放器卡片或搜索结果里点的「加入库」；(c) 设备上的包不是 v2.5.6。**需要一张菜单截图 + `versionName` 才能定案。**
5. **`flushToDisk` 写空表（§4.9）的时序可达性**：只证明写入路径存在（`cachedSongs == null ⇒ 写 []`），未在真机上复现 `preload` 与 `subscribeAlbum` 的竞争。
6. **跨源 id 撞号风险（§8.2 的 `album.id` / `artist.id`）未实测**：QQ 与 ncm 的 album/artist id 是否真的会撞、撞上后「按专辑播放全部」给错歌集合的概率 —— 未做数据比对。
7. **本地歌单子系统是否有独立缺陷未证伪**：本次只做了规则级转写验证（§6.2）+ 读了 27 个既有单测的用例名，**没有**在真机上走一遍「QQ 歌单 → 转存 → 加歌 → 刷新」。
8. **工作区 WIP 的正确性未审计**：`SavedSongCodec.kt` / `SavedSongModels.kt` / 改后的 `LibraryManager.kt` 是别的会话正在进行中的改动（`22:00:55`），本报告只把它当作「修复方向的旁证」，**没有**评审其实现、也没有运行其单测。
