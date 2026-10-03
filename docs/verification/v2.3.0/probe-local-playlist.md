# 探针 ④：**本地歌单**的存储结构与编辑路径

> v2.3.0 · 2026-09-26 代码审计（`git show HEAD` = `67b7bdb`，与设备上 2.2.1-gpl 同源）。
> 审计命令与原始输出见本文各节的引用。

## 0. 结论先行

| 问题 | 结论 |
|---|---|
| 现在有没有「本地歌单」这个概念？ | **完全没有**。全仓只有**一句注释**提到它「尚不存在」 |
| 那现在的「歌单」是什么？ | **两个远程歌单的本地镜像**：ncm（**只在内存** `ContentCache`，不落盘曲目）+ QQ（落盘 `ncrust_qq_playlists`，**只读**） |
| 本地存储用什么？ | **SharedPreferences + Gson**。全仓**没有 Room**，没有 DataStore |
| 现有 `PlaylistTrack` 能不能直接扩展？ | **不能**。它是**远程镜像**的行，语义是「服务端第 n 首」；塞 `tombstoned` 进去会让「镜像」与「用户意图」两套语义纠缠。本版**另建** `LocalPlaylistTrack` |
| 有没有现成的本地编辑路径？ | **没有**。`PlaylistEditApi` 是**ncm 远程写**（`/eapi/playlist/...`），与本地存储无关；QQ 侧连远程写都没有 |
| 有没有可复用的迁移范式？ | **有**。`PlaylistCacheCodec`（v2.2.0）就是为此写的：DTO + `SCHEMA_VERSION` + 显式构造 + 丢弃式迁移 + 单测 |

---

## 1. 「本地歌单」概念存在性审计

```bash
$ grep -rn "本地歌单\|localPlaylist\|LocalPlaylist\|local_playlist" app/src/main/java --include=*.kt
app/src/main/java/com/takahashirinta/ncrust/qq/QqMusicSourceProvider.kt:38:
 *   - 不把 QQ 歌曲加进ncm歌单/收藏（那需要「本地歌单」这个尚不存在的概念，
```

**唯一一处命中是一句注释**（v2.1.0 写的），它明确说这个概念**尚不存在**。
没有任何类、字段、prefs key、路由与之对应。

## 2. 当前「歌单」的两条链路（都是远程镜像）

### 2.1 ncm：**内存镜像，曲目不落盘**

| 环节 | 实现 | 是否落盘 |
|---|---|---|
| 歌单列表 | `PlaylistApi.getUserPlaylists(uid)` → `LibraryScreen` 的 `playlists` state | ❌ 每次进 tab 重新拉 |
| 歌单曲目 | `PlaylistDetailScreen` → `ContentCache.getPlaylistSongs(id)` | ❌ **内存 LRU-32**，进程死即失 |
| 写操作 | `PlaylistEditApi`（`/eapi/playlist/create`、`/manipulate/tracks`…）+ `PlaylistWriteGate` | 远程写，**不是本地存储** |

证据：`PlaylistDetailScreen.kt:74` 读的是 `ContentCache`（`cache/ContentCache.kt` 是
「in-memory snapshot (not persistence)」，见 AGENTS.md）；全仓
`getSharedPreferences` 的调用点里没有任何一个属于 ncm 歌单。

### 2.2 qm：**落盘镜像，只读**

`QqPlaylistStore`（v2.2.0）把 QQ 歌单列表与详情写进
`ncrust_qq_playlists`（`Context.MODE_PRIVATE`）：

```
list:qqmusic:<ownerId>                 → PlaylistCacheCodec.encodeList(...)
list_at:qqmusic:<ownerId>              → savedAt
detail:qqmusic:<ownerId>:<tid>         → PlaylistCacheCodec.encodeDetail(...)
schema                                 → PlaylistCacheCodec.SCHEMA_VERSION
```

它是**只读镜像**：`QqPlaylistRepository` 只有 `loadList` / `loadDetail` / `clearAll` /
`clearCurrentOwner`，**没有**任何写入用户意图的入口。

## 3. 存储设施现状

| 项 | 事实 |
|---|---|
| 有没有 Room？ | **没有**（AGENTS.md「No Room; all persistence is SharedPreferences + Gson」，本次复查无变化） |
| 有没有 DataStore？ | **没有** |
| 有没有文件型存储？ | 有，但只用于**音频缓存**（`OfflineAudioCache` → media3 `SimpleCache`，`filesDir/offline/audio`）与背景图 |
| 全部 prefs 文件 | `ncrust_prefs`(cookie) / `ncrust_settings` / `ncrust_library` / `ncrust_playback_state` / `ncrust_lyrics_cache` / `search_history` / `ncrust_offline` / `ncrust_qq_playlists` / `ncrust_qq_prefs` / `ncrust_device` / `ncrust_home_cache` / `ncrust_netease_vip`（真机 `su -c ls /data/data/com.takahashirinta.ncrust/shared_prefs/` 实测，见 `probe-raw/device-prefs-list.txt`） |

⇒ 本版**沿用 SharedPreferences + Gson**，新增一个 `ncrust_local_playlists`。
理由与 `QqPlaylistStore` 相同：容量小（用户自建歌单通常个位数）、
`SharedPreferences` 是整文件读进内存 —— 所以**必须**有 LRU/条数上限（见 §6）。

## 4. `PlaylistTrack` 为什么**不**直接扩展

```kotlin
data class PlaylistTrack(
    val source: MusicSource,
    val trackKey: TrackKey,
    val order: Int,          // ← 「服务端返回顺序」，分页拼接时按它排序
    val addedAt: Long? = null,   // ← QQ 详情接口不返回，恒为 null
)
```

三个语义冲突：

1. **`order` 是服务端的**。本地歌单的「位置」是**用户意图**（新加的追加末尾、已存在的留在原位），
   与任何服务端顺序无关。共用 `order` 会让「同步后重排」变成必然（任务书 4.4 明确禁止整体重排）。
2. **`addedAt` 的语义被污染**：它在 QQ 侧恒为 null（服务端不给），
   而本地歌单的 `addedAt` 是**我们自己写的时间戳**，必须非空。
3. **没有 `tombstoned`**。加进去的话，`QqPlaylistStore` 落盘的镜像里会出现一堆
   `tombstoned=false` 的噪声字段，而 `PlaylistCacheCodec` 的 DTO 迁移规则
   （v2.2.0：老旧条目**丢弃**而不是补默认值）会把它们全部丢掉。

⇒ 本版**另建** `LocalPlaylist` / `LocalPlaylistTrack` / `LocalTrackOrigin`，
只**复用** `PlaylistKey` 与 `TrackKey` 两个身份类型（铁律 6：不另起一套身份模型）。

## 5. 可复用的迁移范式：`PlaylistCacheCodec`

v2.2.0 已经把「加字段 = 加迁移逻辑 = 加单测」落成了机制，本版直接照抄这套骨架：

| 机制 | v2.2.0 的做法 | 本版对应 |
|---|---|---|
| 落盘一律走 DTO | `PlaylistDto` / `SongDto`，字段**全可空 + 默认值** | `LocalPlaylistDto` / `LocalTrackDto` |
| 领域模型只由 DTO 显式构造 | 缺必填字段 ⇒ 返回 null ⇒ 丢弃该条目 | 同 |
| DTO 与领域模型不共享类 | 忘加迁移 = **编译错误** | 同 |
| 版本号落盘 | `SCHEMA_VERSION = 2`，未知/更高版本**拒绝解释** | `SCHEMA_VERSION = 1` 起步 |
| 单测 | `PlaylistCacheCodecTest` | `LocalPlaylistCodecTest` |

**与 v2.2.0 的关键差异（本版必须自己做对的一条）**：
v2.2.0 对缺字段的老条目是**丢弃**（因为补 `ownerId` 会把数据断言成属于当前账号，很危险）。
本版的 `tombstoned` 缺字段**不能丢弃**，也**不能补 `true`**：

- 补 `true` ⇒ 用户从没删过的歌全被当成「已删」，同步后整张歌单一首歌都进不来（灾难）；
- 丢弃 ⇒ 用户手动加的歌与已同步的歌全部消失（数据丢失）。

⇒ **`tombstoned` 缺字段一律补 `false`**（「没记录过删除」= 没删过），
并且这条要**单独一个单测**钉住（见 `LocalPlaylistCodecTest` 的「v0 → v1 迁移」用例）。

## 6. 容量与有界性（铁律 8：失败处理必须有界）

`SharedPreferences` 是**整个文件读进内存**的，所以本地歌单必须有上限，
且上限必须在**纯逻辑**里（可单测），不能靠「用户不会加那么多」：

| 上限 | 值 | 依据 |
|---|---|---|
| 单个歌单的曲目数 | 2000 | 远大于任何手工维护的歌单；超出时**丢弃最旧的 tombstone**，绝不丢活动条目 |
| 歌单个数 | 100 | 与 `QqPlaylistStore.MAX_DETAIL_ENTRIES = 30` 同一条思路 |
| tombstone 保留 | 跟随所属歌单的曲目上限 | 见上 |

「丢弃最旧的 tombstone」是有界性的关键：tombstone 只增不减，
若没有回收策略，一个反复加删的用户会让这个 prefs 文件无限增长。

## 7. 同步时机（任务书 4.5）

| 触发 | 是否发网络请求 |
|---|---|
| 用户下拉刷新 | ✅ 强制 |
| 进入歌单且 `now - lastSyncedAt > TTL` | ✅ |
| 进入歌单但 TTL 内 | ❌ |
| 其它任何时候 | ❌ **不做自动全量同步** |

TTL 沿用 `PlaylistCacheCodec.DEFAULT_TTL_MS = 10 分钟`（已有常量，不另造一个数）。

## 8. 本版的实现落点

| 文件 | 职责 |
|---|---|
| `local/LocalPlaylistModels.kt` | `LocalPlaylist` / `LocalPlaylistTrack` / `LocalTrackOrigin` + **7 条同步规则**的纯函数 |
| `local/LocalPlaylistCodec.kt` | DTO + `SCHEMA_VERSION` + 迁移 + 编解码（纯逻辑） |
| `local/LocalPlaylistStore.kt` | `ncrust_local_playlists` 的读写 + 容量裁剪 |
| `local/LocalPlaylistSync.kt` | 把「远程曲目」映射成本地同步输入（按源取数，不合并） |
| `ui/screen/LocalPlaylistDetailScreen.kt` | 编辑 UI（增 / 删 / 清空 / 下拉刷新） |

## 9. 未验证 / 边界（如实）

1. 本轮**没有**在真机上验证「清空 `ncrust_local_playlists` 后重启」以外的 prefs 损坏场景
   （例如写入过程中被杀进程）。`SharedPreferences` 的 `apply()` 是原子的，
   但**没有**做「半个 JSON」的恢复测试 —— 编解码层对坏 JSON 返回 `MALFORMED` 而不是抛，
   这一条有单测，真机未验。
2. 2000 / 100 两个上限是**设计值**，没有做压力测试（写入 2000 条后的 prefs 文件大小与读取耗时未测）。
3. `LocalPlaylistStore` 与 `QqPlaylistStore` 是**两个独立的 prefs 文件**，
   没有事务跨两者 —— 但两者之间也没有不变量，可以接受。
