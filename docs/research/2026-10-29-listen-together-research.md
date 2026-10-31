# 《一起听》接入研究报告（Arris）

- 仓库：`/home/duanjb666/deepseek/Arris-playback-fixes`，分支 `fix/player-regression-v374`（HEAD `5462b53`）
- 工作目录：`/home/duanjb666/deepseek/research/listen-together/`（clone 在 `repos/`）
- 性质：**只调研，不改 app 源码**。本文件是本任务**唯一写出**的文件（`docs/research/` 下另有 `2026-10-29-minsdk-decision.md` / `2026-10-29-song-recognition-research.md` 两份并行任务产物，与本报告无关、未被本任务触碰）。
- 日期：2026-10-29

---

## 结论先行

**唯一推荐：状态同步（各成员本地取音源）+ 房主权威（host-authoritative）+ 无状态 WebSocket 中继。**
一句话理由：Arris 已经把「各自取音源」所需的全部零件造完了 —— 跨源身份 `source/TrackKey.kt:59`、跨源匹配 `crosssource/CrossSourceMatcher.kt:44`、唯一取链分叉点 `source/SourceRouter.kt:30` —— 缺的只有「谁在什么位置」这一条事实源；而 P2P 给 Android 端塞一个对本项目零复用的 libwebrtc、服务端转发音频把版权与带宽搬进服务端、官方房间协议把房间绑死在网易云一家并与「跨源」的产品前提直接冲突。

在 GPLv3 + 不自建媒体服务端的前提下，这条路线是唯一不引入新许可证冲突、不碰音频字节、不需要 DB 的选项。**GPLv3 兼容的可直接改造参考后端确实存在**：`bihua-university/alisten`（GPL-3.0，Go，5736 行，同时支持网易云/QQ/B 站房间）。

---

## 0. 证据台账

### 0.1 读过的 Arris 代码（全部为当前工作区文件，含未提交改动）

| 主题 | 文件 | 关键行 |
|---|---|---|
| 播放服务 | `app/src/main/java/com/takahashirinta/ncrust/player/PlaybackService.kt` | 171、674、943-950、968、1008、1217、1223、1238、1826、1851-1870 |
| 队列决策 | `player/PlaybackQueueLogic.kt` | 38、50、87、127、143 |
| 播放状态持久化 | `player/PlaybackStateManager.kt` | 31、195、229、251、316、319、411 |
| ViewModel | `ui/viewmodel/PlayerViewModel.kt` | 111-115、204、232、716、1027-1033、1488、1502、1509-1513、1552-1553、1668-1676、2200、3106 |
| 跨源身份 | `source/TrackKey.kt` | 59、67、75、78、99 |
| 音源枚举 | `source/MusicSource.kt` | 31、79 |
| 跨源匹配 | `crosssource/CrossSourceMatcher.kt` | 99、102、249、257-259、272-282、300 |
| 名称归一化 | `crosssource/NameNormalizer.kt` | 96、110、114、124、132-143、149 |
| 置信度 | `crosssource/CrossSourceModels.kt` | 37、61 |
| 可用性/版本标签 | `search/TrackAvailability.kt` | 51、104、151、209、224、231 |
| 取链路由 | `source/SourceRouter.kt` | 30、36-41 |
| 取链实现 | `player/SongUrlFetcher.kt` | 197、207-217 |
| ncm 会话 | `auth/CookieManager.kt` | 6、19、27、111、129 |
| QQ 会话 | `qq/QqAuthStore.kt` | 62、136 |
| B 站会话 | `bili/BiliAuthStore.kt` | 58、279、290 |
| 网络基座 | `network/RetrofitClient.kt` | 25-29、98、129-144、163-176、191-202、257-289 |
| eapi 加密 | `network/crypto/EapiCrypto.kt` | 9-16 |
| 依赖清单 | `app/build.gradle.kts` | 592-670 |
| 设置注册表 | `ui/settings/SettingsRegistry.kt` | 28、49、124、147、1205 |
| 文案容器 | `ui/i18n/Strings.kt` | 57、77、87、125、1632 |

### 0.2 clone 的开源项目（`repos/`）

| 目录 | 上游 | HEAD | 许可证 | 本次用途 |
|---|---|---|---|---|
| `cojam/` | `lucassantana-dev/cojam` | `3a9f269` (2026-10-22) | MIT | 状态同步 + 时钟/漂移算法的**唯一完整对照** |
| `jellyfin/` | `jellyfin/jellyfin`（sparse: SyncPlay） | `63464ab` | GPL-2.0 | 服务端权威 + 预案排期（`LastActivity` 未来时刻） |
| `jellyfin-mpv-shim/` | `jellyfin/jellyfin-mpv-shim` | `56b1e50` | GPL-3.0（整体） | **客户端**漂移补偿三档策略（speed/skip） |
| `listentogether-app/` | `jofr/listentogether-app`（gitlab.com/listentogether 镜像） | `78a126f` (2023-01-30) | GPL-3.0 | WebRTC 主机星型拓扑 + referenceTime/audioTime 模型 |
| `shareaux/` | `Protomothis/ShareAux` | `6dec932` | **AGPL-3.0** | 服务端 ffmpeg → WS 二进制转发（反面样本） |
| `alisten/` | `bihua-university/alisten` | 见 `repos/alisten` | **GPL-3.0** | 跨三源房间的**服务端参考实现**（协议形状可借，音频路径不可借） |
| `music-party/` | `tabidachinokaze/music-party` | `8a23d24` | AGPL-3.0（`licenses/folium-mod-music-party-AGPL-3.0.txt`） | 网易云官方 multi 协议的**完整端点表** |
| `folium-party/` | `tabidachinokaze/folium-mod-music-party` | `b01525f` | AGPL-3.0-only | 同上（vendor 副本 + 解析器） |
| `splayer-together/` | `wow-233/splayer-netease-together` | `96f07be` | **无 LICENSE 文件** | 官方 multi 协议的第二份独立实现 |
| `zoff/` | `zoff-music/zoff` | `d539a45` | 无（仅 README） | **失败项**，见下 |

### 0.3 失败项与替代路径（不阻塞）

1. **`clemousse/ListenTogether` 不存在**：`git ls-remote` 返回 `remote: Repository not found`。真实上游是 GitLab 的 `listentogether`，GitHub 只有只读镜像 `jofr/listentogether-app`，已 clone 到 `repos/listentogether-app`（GPL-3.0，含 `src/listening/{state,session,peer}.ts`）。镜像停在 2023-01-30，但仍是最完整的 WebRTC 一起听实现。
2. **`zoff-music/zoff` 是空壳**：仓库只有一个 107 字节的 README（"Zoff is once again alive on https://zoff.me"），**零源码**。因此本报告**无法**给出 Zoff 的任何代码级结论 —— 标记为未验证。
3. **`api.github.com` 全程 403**（`API rate limit exceeded`，共享代理出口 IP）。改用两条路绕开：`git ls-remote --heads <repo>` 判在/不在，`https://github.com/search?q=...&type=repositories` 的 HTML 里抓 `"hl_name"` 字段找仓库名。`ShareAux`、`splayer-netease-together`、`music-party` 都是这样找到的。
4. **`ShareAux` 不是状态同步**：README 与代码一致 —— `ffmpeg → fMP4 AAC → WebSocket 二进制 → 浏览器 MSE`（`repos/shareaux/server/src/player/player.service.ts:64` 的 `startedAt` 是给服务端推流用的，不是客户端同步基准）。它落在「服务端转发音频」一类。

### 0.4 四条必须先说清的事实

1. **Arris 没有任何 WebSocket 依赖**。`app/build.gradle.kts:630-634` 只有 retrofit / converter-gson / **logging-interceptor** / gson。但 `logging-interceptor:4.12.0` 传递引入 `okhttp` 4.12.0，而 `okhttp3.WebSocket` / `WebSocketListener` / `OkHttpClient.newWebSocket()` 就在 okhttp core 里 —— **传输层零新依赖可用**，且能直接复用 `RetrofitClient.baseClient`（`network/RetrofitClient.kt:129`）的连接池与 15s PING 保活（`:139`）。
2. **Arris 已有「从指定位置起播」的完整通路**：`PlayerViewModel.playSong(startPositionMs=…)`（`:1502`）→ intent extra `startPositionMs`（`:1674`）→ `PlaybackService` 读取（`:968`）→ `playUrl(url, startPositionMs)`（`:1223`）→ `player.setMediaItem(mediaItem, startPositionMs)`（`:1238`）。**房间同步不需要新造起播路径，只需要给它喂一个外部算出来的位置。**
3. **Arris 没有任何播放倍速 API 的使用**（`grep -rn "setPlaybackParameters|PlaybackParameters(" app/src` → 0 命中）。media3 1.5.0（`app/build.gradle.kts:616`）的 `Player.setPlaybackParameters(PlaybackParameters(speed, pitch))` 原生支持保音高变速 —— 这是「微调」策略的落点，但它是**新增能力**，不是既有能力。
4. **Arris 已有「请求代次」闸门模式**：`playGate.next()` / `playRevision`（`PlayerViewModel.kt:1513`、`:1668`、`:1786`；`PlaybackService.kt:956-957`）。房间同步的 `(epoch, seq)` 校验是**同一个形状的重用**，不是新概念。

---

## 1. 架构选型

### 1.1 四种方案对 Arris 的适配度

| 维度 | ① 状态同步（各自取源） | ② P2P 会话（WebRTC DataChannel） | ③ 服务端转发音频 | ④ 官方房间协议 |
|---|---|---|---|---|
| 需要什么服务端 | 极简 relay（可无状态） | 仅 signaling + STUN/TURN | 音频转发 + 取链 + 带宽 | 无（用网易云的） |
| Android 端新增依赖 | **0**（OkHttp WS 已在） | **libwebrtc**（~10-20MB/ABI） | 0（HTTP 流） | 0 |
| 与 Arris 现有零件的复用 | **TrackKey / CrossSourceMatcher / SourceRouter / SongUrlFetcher 全复用** | 只复用 TrackKey | 取链被搬到服务端 ⇒ `SourceRouter` 与 `CookieManager` 废弃 | 只对 ncm 一源有意义 |
| 跨源（ncm/QQ/B站） | **天然支持**：每人按自己的源解析 | 天然支持 | 服务端要持三家的 cookie | **不支持**：官方房间只认 ncm songId |
| 音频字节是否过服务端 | 否 | 否 | **是** | 否 |
| GPLv3 仓库可直接借码 | alisten（GPL-3.0）✅ | listen-together（GPL-3.0）✅ | ShareAux 是 AGPL ❌ | music-party/folium 是 AGPL ❌ |
| 「无自建服务端」下的现实性 | **高**：relay 可替换/可自建/可纯内存 | 中：省掉 relay 但要 TURN | 低：版权 + 带宽 | 高但代价是产品绑死 |

### 1.2 结论：为什么只有 ①

**① 状态同步**。四条理由，每条都有代码依据：

1. **它是唯一能复用 Arris 已有复杂度的方案。** `SourceRouter` 的类文档写死了它的职责：「全应用**唯一**允许按音源分叉的地方……让所有取链都经过这里，「忘了带音源」就变成一个可以在这个文件里一眼看完的问题」（`source/SourceRouter.kt:22-29`）。服务端转发音频（③）会把取链搬到服务端，这条纪律当场作废，`CookieManager` / `QqAuthStore` / `BiliAuthStore` 三套会话管理在房间场景全部失效 —— 等于为了一个功能重写整个取链层。
2. **它是唯一与「跨源」产品前提自洽的方案。** Arris 的核心资产是 `CrossSourceMatcher` 的三步走（召回→校验→分级，`crosssource/CrossSourceMatcher.kt:20-27`）和 `MatchConfidence.mergeable` 这个唯一合并闸门（`crosssource/CrossSourceModels.kt:61`）。官方房间协议（④）的房间模型里只有一个 `songId`（`repos/folium-party/vendor/music-party/src/shared/multiplayer.ts:36`），**没有「这首歌在 QQ 上是哪一首」的位置** —— 选 ④ 等于宣告跨源在房间里不可用。
3. **② 的收益在 Arris 的场景里是负的。** P2P 省掉的是「一个只转发几百字节/秒的内存 relay」。而代价是 libwebrtc + NAT 穿透 + 移动网络下的打洞失败率。listen-together 自己就承认必须保留 TURN：「If no direct communication is possible the connection is relayed through a TURN server」（`repos/listentogether-app/README.md`）。**TURN 比 relay 贵得多**，而且它转发的是同样的字节。② 在浏览器里成立，在「Android-only + 三源取链」里不成立。
4. **③ 会引入本项目刻意回避的两样东西：音频字节与版权判断。** ShareAux 的栈就是 ③ 的完整样子（NestJS + Postgres + ffmpeg fMP4 + WS 二进制）。它对 Arris 不可行的硬理由不是技术而是结构：音频一旦过服务端，服务端就必须有取链凭据，而 Arris 的整个会话模型是「凭据只在本机」（`auth/CookieManager.kt:19-27` 的 `NeteaseSessionSnapshot`、`qq/QqAuthStore.kt:136`、`bili/BiliAuthStore.kt:290` 的 `requestCookieHeader`）。把 cookie 交给任何服务端都是把「本机凭据」这条边界打破。

### 1.3 权威方选谁：**房主**，不是服务端

CoJam / Jellyfin / alisten 三家都是**服务端权威**。Arris 应当选**房主权威**，理由是可验证的：

- 房主是一个**真实播放器**：`PlaybackService.player.currentPosition` 就是事实。服务端权威必须自己模拟「播放进度随时间推进」并处理 pause/seek/buffer 的全部分支（Jellyfin 为此写了 5 个 state 类：`IdleGroupState`/`PausedGroupState`/`PlayingGroupState`/`WaitingGroupState`，另有 18 个 `*GroupRequest.cs`）。
- 房主权威让 relay **退化成纯 fan-out**：不需要 DB、不需要定时器、不需要队列推进逻辑，只需要「房间成员表 + 转发 + 房主选举」。这直接对应 §5 的成本答案。
- 代价（房主掉线）有成熟解法：epoch 递增 + 房主迁移。listen-together 的做法值得直接抄：listener 检测到与 host 连接 `CLOSED` 就把自己升级成 host，用当前状态继续（`repos/listentogether-app/src/listening/session.ts:88-92` 的 `transformToHost()`）。

**但要接受一个事实上的例外**：房主权威意味着「房主暂停时，成员的本地播放也在等指令」，如果房主网络抖动，全体跟着跳。缓解办法是把「房主」理解成**逻辑角色而非固定设备**：掉线 3s 内不迁移（CoJam/mpv-shim 的心跳间隔是 20s/5s 量级，见 `repos/splayer-together/src/sync.ts:19` 的 `DEFAULT_HEARTBEAT_MS = 20_000`），超过阈值才迁移。

---

## 2. 跨音源映射

### 2.1 房间里不能存 `TrackKey`

`TrackKey` 的身份是 `(source, id)`，并且类文档明确写了为什么载荷不能进相等性（`source/TrackKey.kt:44-56`）。但 `TrackKey` **是「在某一个源上的身份」**：A 手里的 `TrackKey(NETEASE, 123)` 对 B 没有任何意义（B 可能没有 ncm 账号，或者那个 id 在他那儿是另一首歌 —— 正是 `TrackKey` 要修的跨源撞号）。

所以房间状态里必须存一个**与源无关的规范描述**（CanonicalTrack），再加上**公开曲目标识**用于同源直连。

```kotlin
// 建议位置：新增包 com.takahashirinta.ncrust.listen（纯逻辑、无 Android 依赖、JVM 可单测）
data class RoomTrack(
    /** 房间内 id（UUID）。**不是** TrackKey —— 同一首歌对不同成员是不同的 TrackKey。 */
    val trackId: String,
    val canonical: CanonicalTrack,
    val addedByUserId: String,
)

data class CanonicalTrack(
    /** 原文标题，**不归一化**：归一化只发生在匹配算法内部（NameNormalizer.kt:13-32 的分工）。 */
    val title: String,
    val artists: List<String>,
    val album: String?,
    /** 必需。CrossSourceMatcher 的时长判据靠它（CrossSourceMatcher.kt:102 DURATION_TOLERANCE_MS）。 */
    val durationMs: Long,
    /**
     * 版本种类快照 = NameNormalizer.editionKinds(title) 的结果（NameNormalizer.kt:124）。
     * **在入房那一刻算好并随状态传播**，而不是各成员各自用本地的 title 重算 ——
     * 这样「版本判定」在全房间只有一个答案，不会出现 A 认为是原版、B 认为是 Live。
     */
    val editionKinds: Set<String>,
    /**
     * 公开曲目标识，**只含公开 id，不含任何 URL / cookie / authSecret**：
     *   netease -> "123"
     *   qqmusic -> "0039MnYb0qxYhV"（songmid，本身就是 URL 参数，公开）
     *   bilibili -> "au:1234567" / "bv:BV1xx:cid"
     * 同源成员据此零搜索直连；跨源成员忽略它，走 CrossSourceMatcher。
     */
    val publicIds: Map<String, String>,
    /** 提案方音源，仅用于 UI 标注「由 XX 的网易云加入」。 */
    val originSource: String,
)
```

**合规边界的一句话**：`publicIds` 里的三样都是**已公开的曲目标识**（ncm songId 出现在 `music.163.com/song?id=` 里；songmid 出现在 QQ 分享链接里；B 站 au/bv 是公开稿件号）。取链 URL 带签名与 `authSecret`（`PlaybackService.kt:1224` 的注释：「取链 URL 带签名参数与 authSecret，不能进 logcat」），**绝对不进房间状态**。

### 2.2 成员侧解析：`RoomTrackResolver`

```kotlin
object RoomTrackResolver {

    sealed interface Resolution {
        /** 本机可播。direct=true 表示命中了 publicIds 的同源直连（零搜索）。 */
        data class Playable(val key: TrackKey, val direct: Boolean, val verdict: MatchVerdict?) : Resolution
        /** 搜到了但置信度不够（LOW/NONE）—— 不播，提示「本机音源未收录」。 */
        data class Unmatched(val best: MatchVerdict?) : Resolution
        /** 本机没有该音源（未登录/未启用）。 */
        data object SourceUnavailable : Resolution
    }

    fun resolve(
        track: CanonicalTrack,
        localSource: MusicSource,
        searchLocal: (CanonicalTrack) -> List<CrossSourceMatcher.TrackCandidate>,
    ): Resolution {
        // ① 同源直连：publicIds 里有我这一源的公开 id，直接构造 TrackKey，零搜索零匹配。
        track.publicIds[localSource.key]?.let { id ->
            return Resolution.Playable(
                key = TrackKey.of(localSource.key, id.toSongId(), sourceId = id.takeIf { localSource == MusicSource.QQMUSIC }),
                direct = true, verdict = null,
            )
        }
        // ② 跨源：召回 → 校验 → 分级，完全复用 CrossSourceMatcher.gradeTrack。
        val anchor = CrossSourceMatcher.TrackCandidate(
            key = TrackKey.of(track.originSource, 0L),  // 仅作锚点占位，不参与判定
            name = track.title,
            artists = track.artists,
            durationMs = track.durationMs,
            albumName = track.album,
        )
        val best = searchLocal(track)
            .map { it to CrossSourceMatcher.gradeTrack(anchor, it) }
            .maxByOrNull { it.second.confidence.rank() }
            ?: return Resolution.Unmatched(null)

        return if (best.second.confidence.mergeable) {
            Resolution.Playable(best.first.key, direct = false, verdict = best.second)
        } else {
            Resolution.Unmatched(best.second)
        }
    }
}
```

**「合并」闸门只有一处**：`MatchConfidence.mergeable`（`crosssource/CrossSourceModels.kt:61`），`EXACT`/`HIGH`/`MEDIUM` 为 true。类文档明确要求「不要在调用方写 `confidence >= HIGH`」—— 房间侧也必须只问 `mergeable`。

### 2.3 「Live / 伴奏 / 翻唱不能算同一首」怎么落地

**能，而且现成的判据已经够用，但需要把 `TrackVersionTag` 和 `editionKinds` 分清。**

- `NameNormalizer.editionKinds(text)`（`NameNormalizer.kt:124-143`）把版本标记归成有限种类：`instrumental`（伴奏/纯音乐/钢琴版/吉他版）、`live`、`remix`、`acoustic`、`cover`、`demo`、`vocal`、`edit`、`deluxe`、`remaster`。**只有种类集合相等才算同一版本**。
- `CrossSourceMatcher.gradeTrack` 已经把它用在最关键的一处：时长一致但版本种类不同 → 只给 `LOW`（`CrossSourceMatcher.kt:275-282`），注释写明理由是「合并会让用户点到另一版」。而 `LOW` 的 `mergeable == false`，房间侧自动不播。

所以房间里的落地是**三步**，没有新算法：

| 步骤 | 判据 | 代码位置 | 房间侧行为 |
|---|---|---|---|
| 入房时快照 | `editionKinds(title)` | `NameNormalizer.kt:124` | 写进 `CanonicalTrack.editionKinds`，全房间共用一个答案 |
| 解析时校验 | 时长差 ≤ 2000ms **且** editions 相等 → `EXACT`；时长差 ≤ 2000ms 但 editions 不等 → `LOW` | `CrossSourceMatcher.kt:272-282`、`:102` | `LOW` ⇒ `Unmatched`，不播、不静音跟随 |
| 提示 | `MatchVerdict.reason` 已是人话 | `CrossSourceMatcher.kt:249-289` | 直接显示「本机只有现场版，与房间版本不同」 |

### 2.4 `TrackVersionTag` 能用吗：**部分能，且必须扩**

现状（`search/TrackAvailability.kt:209-243`）：

```kotlin
enum class TrackVersionTag { ORIGINAL, COVER, UNKNOWN }
fun of(song: SongItem): TrackVersionTag = when (song.musicSource) {
    MusicSource.QQMUSIC -> UNKNOWN   // :232  QQ 无字段，恒 UNKNOWN
    MusicSource.NETEASE -> ofOriginCoverType(song.originCoverType)
    MusicSource.BILIBILI -> UNKNOWN  // :235
}
```

结论分三层：

1. **`COVER` 这一档可以直接用，而且要和 `editionKinds` 的 `"cover"` 种类对齐。** 现状是两套判据并存：`TrackVersionTag.COVER` 来自服务端结构化字段 `originCoverType == 2`（零假阳性，实测 96.7% 自洽，`TrackAvailability.kt:193-196`），`editionKinds` 的 `"cover"` 来自标题里的「翻唱/cover」字样（启发式）。**房间里必须以服务端判据为准、标题判据为兜底**，且两者冲突时判为不可合并（保守方向）。
2. **`TrackVersionTag` 只有原唱/翻唱二分，缺 `LIVE` / `INSTRUMENTAL` / `REMIX`。** 靠它判「伴奏不能算同一首」是做不到的 —— 伴奏的 `originCoverType` 通常是 `0`（无信息）⇒ `UNKNOWN`。所以房间侧**判据主力必须是 `editionKinds`**，`TrackVersionTag` 只在 `COVER` 这一档提供额外的服务端确证。
3. **`TrackVersionTag.of()` 对 QQ/B 站恒 `UNKNOWN`（`:232`、`:235`），这是既有事实，不要在房间侧"修"它。** 房间侧的兜底是 `editionKinds`，它对 QQ/B 站的标题同样有效（它只看字符串）。

**建议（PR2 范围）**：不修改 `TrackVersionTag` 本身（它会牵动搜索页角标与 `SearchRankingTest`），而是新增一个房间侧的组合判据：

```kotlin
// listen/VersionGuard.kt —— 纯逻辑
object VersionGuard {
    /** 返回 null = 可合并；非 null = 不可合并的理由（直接给 UI）。 */
    fun reject(
        anchorTitle: String, anchorTag: TrackVersionTag,
        candTitle: String, candTag: TrackVersionTag,
        durationDeltaMs: Long?,
    ): String? {
        val a = NameNormalizer.editionKinds(anchorTitle)
        val b = NameNormalizer.editionKinds(candTitle)
        if (a != b) return "版本不同（${a.ifEmpty { setOf("原版") }} vs ${b.ifEmpty { setOf("原版") }}）"
        // 服务端已确证一侧是翻唱、另一侧是原唱 ⇒ 即便标题里没有「翻唱」字样也不合并。
        if (anchorTag == TrackVersionTag.COVER && candTag == TrackVersionTag.ORIGINAL) return "一侧为翻唱"
        if (durationDeltaMs != null && durationDeltaMs > CrossSourceMatcher.DURATION_TOLERANCE_MS)
            return "时长差 ${durationDeltaMs}ms，疑似不同版本"
        return null
    }
}
```

### 2.5 顺带解决一件事：跨源房间的**响度**差

`repos/alisten` 的 `PlaybackData` 带一个字段 `volumeCoefficient`（`protocol/types.gen.go:100-105`），来源是 `h.backend.Loudness(order.source, order.id)`（`cmd/alisten/house.go:303`）。这是跨源房间的一个真实问题：**同一首歌在网易云是 -9 LUFS，在 B 站是 -14 LUFS，A、B 两人听到的音量差 5dB，会让「一起听」的体验直接崩**。

Arris 已有 `player/AudioFeatureExtractor.kt` 与 `TransparentWaveformSink.kt`，做的是波形而不是 LUFS，不能直接给出 `volumeCoefficient`。**本报告的建议是**：PR3 先不做自动响度归一（需要 `MediaExtractor` + 一次 EBU R128 计算，成本远超一个 PR），而是在房间协议里**预留** `volumeCoefficient: Float = 1.0` 字段，PR3 只用「房主可手动设置房间音量系数、全体跟随」这一档（ExoPlayer 侧就是 `player.volume = roomVolume * coefficient`，零成本）。自动测量留到后续版本。

---

## 3. 同步协议

### 3.1 房间状态数据结构

```kotlin
// listen/RoomProtocol.kt —— 纯数据 + 纯函数，无 Android 依赖
data class RoomState(
    val protocolVersion: Int = 1,        // 不匹配直接拒绝（同 alisten 的 UNSUPPORTED_VERSION）
    val roomId: String,
    /** 房主会话代号。换房主 / 房主重连 ⇒ 换 epoch，旧 epoch 的一切消息作废。 */
    val epoch: String,
    /** 权威方单调递增的操作序号。**在同一 epoch 内严格递增**，是竞态的唯一仲裁。 */
    val seq: Long,
    val hostUserId: String,
    val members: List<RoomMember>,
    /** 已播队列 + 待播队列。待播由房主维护；已播保留最近 50 条（同 CoJam history 上限）。 */
    val queue: List<RoomTrack>,
    val nowPlayingId: String?,
    val transport: Transport,
    /** 权威方打戳时刻，单位 = relay 时钟（ms）。见 §3.3。 */
    val stampedAtMs: Long,
    val volumeCoefficient: Float = 1.0f,
)

data class Transport(
    val playing: Boolean,
    /** 基准位置（ms）。playing=false 时它就是「暂停在哪」，playing=true 时它是 stampedAtMs 那一刻的位置。 */
    val positionMs: Long,
    val rate: Float = 1.0f,
)

data class RoomMember(
    val userId: String,
    val displayName: String,
    val platform: String,          // "netease" / "qqmusic" / "bilibili"，仅用于 UI 显示
    val online: Boolean,
    val joinedAtMs: Long,
)
```

对照三家参考实现，这套字段的每一格都有出处：

| 字段 | 出处 | 说明 |
|---|---|---|
| `epoch` | alisten `house.go:227,306` 的 `generation` | 「代次」闸门：异步任务返回时 `generation != 当前` 就丢弃结果 |
| `seq` | CoJam `RoomState.version`（`packages/shared/src/protocol.ts:86`）、alisten `info.version`（`vendor/music-party/src/shared/multiplayer.ts:43`） | 每次变更 `+1`；客户端只用 `>=` 接受 |
| `nowPlayingId` | CoJam `RoomState.nowPlayingId`（`protocol.ts:66`） | 用房间内 id，不用 TrackKey |
| `transport.positionMs + stampedAtMs` | CoJam `TransportState{state, positionMs, updatedAtServerMs}`（`protocol.ts:55-59`）；listen-together `PlaybackState{referenceTime, audioTime}`（`src/listening/state.ts:8-14`） | 基准位置 + 基准时刻，两家的字段名不同但模型完全一致 |
| `rate` | listen-together `playbackRate`（`state.ts:12`） | 预留给倍速房间；v1 恒 1.0 |
| `volumeCoefficient` | alisten `PlaybackData.volumeCoefficient`（`types.gen.go:104`） | 见 §2.5 |

`Transport` 里**刻意不存**的东西：`state: "stopped"`。Arris 的播放器只有「在播 / 暂停」两态对房间有意义 —— `STATE_ENDED` 在 Arris 里会自动 `nextIndex` 推进（`PlaybackQueueLogic.kt:87-117`），由房主的 `onSongEnded` 回调转成一次 `advance` 操作即可。

### 3.2 谁权威 + 切歌竞态

**房主权威，且房主侧所有变更串行化在一个 `Mutex` 里。**

竞态的三条具体场景与对应机制：

| 场景 | 机制 | 依据 |
|---|---|---|
| 房主连点「下一首」 | 房主侧 `RoomActor.mutate {}` 是串行队列 ⇒ 产生 `seq+1`、`seq+2` 两次状态，成员只看到最终态。**同时复用既有 `playGate`**：`playGate.next()`（`PlayerViewModel.kt:1513`）已经把过期请求作废，`PlaybackService` 侧还有 `playRevision` 复核（`:956-957`） | 既有 |
| 两人同时切歌 | 成员**不允许**直接改 `nowPlayingId`。成员发 `intent.play(trackId, expectedSeq)`；房主收到后校验 `expectedSeq == 当前 seq`，不等则**拒绝并回一个 `intent.rejected`**（带当前状态），由成员 UI 重新渲染。这比「后到者覆盖」对用户诚实：用户看到的是「房间已经切到别的歌了」，而不是自己点的歌闪一下又消失 | 同 mpv-shim 的 `attempts` 上限 + 提示（`syncplay.py:198-210`） |
| 房主自己切歌 vs 成员 `intent.play` 几乎同时 | 房主的 `mutate` 队列给出全序；`seq` 决定谁生效。成员的 `intent` 若 `expectedSeq` 已过期，直接 rejected | — |

`expectedSeq` 这个字段是我在三家参考实现里**都没见到**的（CoJam 用服务端全序默默覆盖，alisten 用 `generation` 丢弃异步结果，mpv-shim 用 attempts 上限）。之所以建议加上，是因为房主权威 + 成员切歌的组合里，「静默覆盖」会让成员以为自己点成功了。这是本报告的一处**设计增量**，需要在 PR1 的单测里钉住。

### 3.3 时钟对齐与漂移补偿（本节是重点，容易写错）

#### 3.3.1 先做时钟对齐，再算位置

**三家参考实现的做法**：

- CoJam：`sync.ping` RPC 返回 `{serverNowMs}`（`apps/server/internal/hub/hub.go:2742-2743`），客户端取**最小 RTT 样本**做 NTP-lite：`offset = serverNowMs - (t0 + t1) / 2`（`apps/web/lib/clockSync.ts:12-37`）。之后 `serverNow() = Date.now() + offset`（`:39-41`），位置 = `positionMs + (serverNow() - updatedAtServerMs)`（`apps/web/lib/playbackSync.ts:35-37`）。
- splayer-together：不做持续对齐，用**每次请求的 RTT/2** 当场补：`halfRttMs = max(0, (endedAt - startedAt) / 2)`（`src/sync.ts:727`），`expectedMultiPosition(playedTime, halfRtt) = playedTime + max(0, halfRtt)`（`:72-75`）。
- folium-party：折中，`parseRoomPlayback(info, (started + this.now()) / 2)`（`src/client/controller.ts:516`）—— 用请求中点当采样时刻，然后 `targetPosition = playedTime + max(0, now - sampledAt)`（`vendor/music-party/src/shared/multiplayer.ts:109-112`）。

**这三种做法的关系必须讲清，否则实现时一定会算重一次延迟：**

设真实时刻 `T`，权威方在 `T0` 采样得位置 `P0` 并在同一时刻打戳 `A = T0`，消息单向延迟 `d` 到达我，我的本地钟相对权威的偏差是 `ε`（`now_local = T + ε`）。

- 若 `ε` 已用 NTP 校正到 ≈0：目标位置 = `P0 + (now_local - A)`。**这里不能再减 `d`** —— `(now_local - A)` 已经就是真实流逝时间。
- 若 `ε` 未知且我们**假装** `ε = 0`：算出来的 `P0 + (now_local - A) = P0 + (T - T0) + d`，比真实多 `d`，所以**要减 `d`**。这就是「目标位置 = 基准位置 + (now − 基准时刻) − 单程延迟」这条公式的来历，它的前提是「没做时钟对齐」。

**结论（写死在这里，避免实现时踩两次）**：

```
我们走 CoJam 那条：先 NTP-lite 对齐，再
    targetMs = transport.positionMs + (relayNow() - state.stampedAtMs) * rate
其中 relayNow() = SystemClock.elapsedRealtime() + offsetToRelay，offsetToRelay 用 min-RTT 样本估。
**公式里没有 "- 单程延迟" 项**，因为 offset 已经把它吸收了。
广播 anchor 时用 relay 时钟（不是墙钟），理由见 §3.3.2。
```

`SystemClock.elapsedRealtime()` 而非 `System.currentTimeMillis()`：后者会被 NTP/用户改时间打断，`currentTimeMillis` 在房间存活期内跳变一次就足以毁掉整场同步。Arris 已经在用 `SystemClock`（`PlaybackService.kt:38` 的 import、`:1557` 的 `lastLyricNotifyAt`），这条是顺手的事。

#### 3.3.2 `offset` 的估计：最小 RTT，不是均值

直接照 `clockSync.ts:12-37`：取 N 个样本（建议 5 个，间隔 1s），**选 RTT 最小的那一个**做 `offset = serverNow - (t0 + t1)/2`，返回 `rttMs = minRtt`。理由在同一函数里写明：min-RTT 样本受到排队抖动污染最少。

Kotlin 侧：

```kotlin
// listen/RoomClock.kt —— 纯逻辑，注入 now() 以便单测
class RoomClock(private val now: () -> Long) {
    data class Sample(val t0: Long, val relayNowMs: Long, val t1: Long)

    @Volatile private var offsetMs: Long = 0L
    @Volatile private var minRttMs: Long = Long.MAX_VALUE

    fun offer(s: Sample) {
        val rtt = s.t1 - s.t0
        // 防御：relay 时钟回退 / 样本为负 ⇒ 丢弃（不对齐比对齐错更好）
        if (rtt < 0 || s.relayNowMs <= 0L) return
        if (rtt < minRttMs) {
            minRttMs = rtt
            offsetMs = s.relayNowMs - (s.t0 + s.t1) / 2
        }
    }

    fun relayNow(): Long = now() + offsetMs

    /** 未完成任何有效采样时为 false —— 此时**不允许**做位置裁剪，只能等。 */
    val synced: Boolean get() = minRttMs != Long.MAX_VALUE
}
```

单测 fixture 直接用 `apps/web/lib/clockSync.test.ts` 的四个用例（`cojam` 已 clone，用例在 `repos/cojam/apps/web/lib/clockSync.test.ts:8-47`，含「负 offset」与「RTT=0」的边界）。

#### 3.3.3 漂移阈值与微调策略

**三档，数值取三家实测值的交集，偏向 mpv-shim（它对 ExoPlayer 最可比）：**

| 档 | 阈值 | 动作 | 依据 |
|---|---|---|---|
| 忽略 | `|drift| ≤ 50ms` | 什么都不做 | mpv-shim `sync_max_delay_speed = 50`（`conf.py:538`） |
| **微调** | `50 < |drift| ≤ 300ms` | `player.setPlaybackParameters(PlaybackParameters(1 + drift/driftWindowMs, 1.0f))`，`driftWindowMs = 1000`；追上后立刻恢复 1.0 | mpv-shim `speed = 1 + diff_ms / sync_speed_time`（`syncplay.py:178`）、`sync_speed_time = 1000`（`conf.py:541`）、上限 `sync_max_delay_skip = 300`（`:539`） |
| **跳转** | `300 < |drift| ≤ 2000ms` | 立即 `seekTo(target)`，并进入 seek 冷却 | mpv-shim `sync_max_delay_skip = 300`；splayer `SEEK_THRESHOLD_MS = 2000`（`src/sync.ts:22`）；CoJam `DRIFT_THRESHOLD_MS = 1000`（`playbackSync.ts:9`，注释写「高于 ~500ms 的跨服务物理下限，避免来回抖」） |
| 重同步 | `|drift| > 2000ms` 或跨曲 | seek + 请求全量快照 | — |

**ExoPlayer 的一个优势必须点出**：`PlaybackParameters(speed, pitch)` 的第二参是 pitch。以 `(1.02f, 1.0f)` 播 1 秒能追 20ms 且**不变调**；mpv 的 `set_speed` 默认会变调（除非开 `audio-pitch-correction`）。这意味着 Arris 的微调档体验可以比 mpv-shim 更好，且不会像 splayer 那样「一有偏差就 seek」产生可听的断点。

**seek 冷却与退避**（照抄 CoJam，理由写在同一处注释里）：一次 seek 会清空缓冲，慢网下重新缓冲的代价比阈值还大 ⇒「seek → 又落后 → 再 seek」的死循环（CoJam 实测到 4.5s 卡顿循环）。

```kotlin
// listen/DriftPolicy.kt —— 纯逻辑，JVM 可单测
object DriftPolicy {
    const val IGNORE_MS = 50L
    const val SPEED_MAX_MS = 300L
    const val SEEK_COOLDOWN_BASE_MS = 3_000L
    const val PAST_END_GRACE_MS = 2_000L

    sealed interface Action {
        data object None : Action
        data class Speed(val factor: Float) : Action     // 1.0 ± 0.02 量级
        data class Seek(val toMs: Long) : Action
    }

    fun decide(
        targetMs: Long, actualMs: Long, durationMs: Long,
        clockSynced: Boolean, consecutiveSeeks: Int, msSinceLastSeek: Long,
    ): Action {
        // 时钟没对齐时唯一的正确动作是「不动」——见 RoomClock.synced 的注释。
        if (!clockSynced) return Action.None
        // 已经越过曲尾：seek 过去只会立刻结束又落后一次（CoJam isPastEnd 的原话）。
        if (durationMs > 0 && targetMs >= durationMs + PAST_END_GRACE_MS) return Action.None

        val drift = targetMs - actualMs
        val abs = kotlin.math.abs(drift)
        return when {
            abs <= IGNORE_MS -> Action.None
            abs <= SPEED_MAX_MS -> Action.Speed(
                // 每秒追 |drift| / 1000，夹到 ±2%，避免可听的音高/节奏变化。
                (1.0 + (drift.toDouble() / 1000.0)).coerceIn(0.98, 1.02).toFloat(),
            )
            else -> {
                val cooldown = SEEK_COOLDOWN_BASE_MS shl consecutiveSeeks.coerceIn(0, 3) // 3s→24s
                if (msSinceLastSeek < cooldown) Action.None
                else Action.Seek(targetMs.coerceAtLeast(0L))
            }
        }
    }
}
```

`SPEED_MAX_MS` 与 CoJam 的 `DRIFT_THRESHOLD_MS = 1000` 不同是**有意的**：CoJam 没有 speed 档，只有「seek 或不 seek」，所以它的阈值必须高；Arris 有 speed 档，把 seek 阈值压到 300ms 可以让绝大多数漂移被无声吸收。这个差异必须在报告里说明，否则实现者会以为是抄错。

#### 3.3.4 断线重连与追赶

```
重连成功
  → 发 room.snapshot.request
  → 收到 (epoch, seq, state)
  → if epoch 变了: 无条件用新状态（房主换了），seek 到位，重置 consecutiveSeeks
  → elif seq <= 本地 seq: 丢弃（本地已更新）
  → else: 应用状态，按 DriftPolicy 决定动作
重连期间：**不暂停本地播放**。理由：暂停再恢复要重新 buffer，代价远大于事后一次 seek；
         而且成员听到的「卡一下」比「静音 5 秒」体验好得多。
```

`epoch` 变更必须**无条件 seek**（而不是走 `DriftPolicy`）：新 epoch 的 `stampedAtMs` 与旧 epoch 不可比（换了房主 = 换了时钟对齐的参照系）。

### 3.4 消息形状（relay 的完整帧表）

```
C→S  hello        { roomId, userId, displayName, platform, sinceSeq }
S→C  state        { state: RoomState }                    // 全量，只在 hello / seq 落后太多时
S→C  delta        { epoch, seq, state }                   // 也发全量，状态本身很小（< 4KB）
C→S  intent.play  { roomId, trackId, expectedSeq }         // 仅成员；房主收到后校验并广播
C→S  intent.add   { roomId, track: CanonicalTrack }        // 任何成员
C→S  intent.seek  { roomId, positionMs, expectedSeq }      // 仅房主
S→C  intent.rejected { reason }                            // expectedSeq 过期 / 无权限
C→S  ping         {} 
S→C  pong         { relayNowMs }                           // NTP-lite
C→S  heartbeat    {}
S→C  members      { members }                              // 与 state 分开发（高频、可丢）
```

**状态一律发全量而不是 diff**：`RoomState` 里的 queue 上限 200 首、`publicIds` 每条 ≤3 个短字符串，整包 < 4KB。CoJam 的做法是发全量 `RoomState`（`protocol.ts:117-120` 的 `RoomStatePub`），listen-together 也是「整块替换」（`src/listening/state.ts:155-159` 的 `applySyncMessage` 直接 `state[message.type] = message.data`）。**diff 只省字节，不省复杂度，而字节在这里不是瓶颈。**

---

## 4. 取链与会话安全

### 4.1 各自本地取链：Arris 已经是这个形状

房间侧**不需要**做任何取链工作，只要把「播哪首」翻译成一个 `SongItem`-equivalent 递进既有管道：

```
RoomTrackResolver → TrackKey
  → 构造 SongItem（或等价入参）
  → PlayerViewModel.playSong(..., sourceKey/sourceId/mediaId, startPositionMs = targetMs)
  → SourceRouter.providerFor(...) → SongUrlFetcher.fetch(...)
```

证据：`SourceRouter` 是「全应用唯一允许按音源分叉的地方」（`source/SourceRouter.kt:22`），`SongUrlFetcher.fetch` 走的是本地 cookie 会话（`player/SongUrlFetcher.kt:198` 的 `NeteaseTokenRefresher.scheduleIfNeeded()`、`:230` 的 `NeteaseSessionRequests.read`）。

**结论：房间功能零 cookie 触达。** 三套会话（`CookieManager` / `QqAuthStore` / `BiliAuthStore`）在房间路径上唯一的作用是「判断本机有没有这个源」，不参与任何跨设备传输。

### 4.2 成员无版权 / 无会员时的降级

`TrackAvailability` 已经把这件事判到四档（`search/TrackAvailability.kt:51-84`），且它的纪律正好是房间需要的：

| `TrackAvailability` | 判据出处 | 房间行为（建议） |
|---|---|---|
| `PLAYABLE` | ncm `privilege.pl > 0`（30/30 假阳性 0），`TrackAvailability.kt:113-115` | 正常播 |
| `UNKNOWN` | 判不出来（`:124`）| **试播**。取链失败再降级 —— 这是 `of()` 对 B 站恒 `UNKNOWN` 时的既有语义（`:152-156` 的注释：「B 站真正的可播性判据在取链那一步（拿不到 URL 就跳）」） |
| `MEMBER_ONLY` | `privilege.st==0 && pl==0 && fee∈{1,4}`（`:120-122`），QQ `pay.pay_play==1`（`:146`） | **弹一次提示 + 用户全局选择**：`跳过跟随`（默认）或 `静音跟随` |
| `NO_COPYRIGHT` | `st==-200` 或 `noCopyrightRcmd != null`（`:111`） | 直接跳过 + 提示，不给选项（服务端已明确说没有） |

**「静音跟随」怎么实现**：不要跳过、不要暂停，`player.volume = 0f`，位置照常按房间推进。ExoPlayer 侧零新增代码（`player.volume` 是 `Player` 接口成员）。这是三种降级里唯一「不打断同步、不产生空洞」的一种，代价是浪费一点流量 —— 所以默认为关。

**为什么默认「跳过」而不是「静音跟随」**：`TrackAvailability` 的类文档定了整个应用的基调 ——「判错方向：排序靠后（可容忍）/ 给用户一个假承诺（**不可容忍**）」（`:45`）。把会员墙内的歌静音播过去，用户看到的是「进度条在动但没有声音」，这比「这首跳过了，因为你没有会员」更接近「假承诺」。

### 4.3 房间内不转发音频的合规边界

写清三条可核对的事实边界：

1. **房间里流动的字节只有房间状态。** 最大 4KB/次，内容是 `CanonicalTrack`（标题/艺人/专辑/时长/版本种类/公开 id）+ 成员列表 + 传输状态。**不含**音频、不含 URL、不含 cookie、不含任何签名参数。
2. **取链 URL 出不了设备。** 现有代码已经把它当敏感数据：`PlaybackService.kt:1224` 注释「取链 URL 带签名参数与 authSecret，不能进 logcat（release 也会打）」；`OfflineUrlStore.rememberFromUrl` 是把它落进本机离线缓存（`:1231`）。房间协议里**没有**任何能放 URL 的字段 —— 这是结构性保证，不是纪律。
3. **房间状态不进日志。** `playUrl` 的日志只打 songId/标题/位置（`:1226-1228`），房间侧的新日志必须遵守同一条：打 `trackId` 与 `seq`，不打 `publicIds` 之外的任何标识，不打 displayName 之外的成员信息。

> 一句必须写进 PR 描述的话：**「一起听只同步『谁在听哪一首、听到第几秒』，不同步音频本身，也不共享任何账号凭据。」**

---

## 5. 服务端成本

### 5.1 最小服务端是什么

房主权威 + 全量状态 + 无 DB，最小实现只需要：

| 组成 | 代码量估算 | 说明 |
|---|---|---|
| WS 接入 + 房间成员表 | ~150 行 | `Map<roomId, {members, hostConn, lastState}>`，成员离开则删键 |
| 帧路由（§3.4 的 8 种帧） | ~200 行 | JSON 编解码 + 权限检查（谁能发 `intent.seek`） |
| `(epoch, seq)` 校验 + 房主迁移 | ~120 行 | 核心正确性都在这 |
| `ping/pong` 时钟 | ~30 行 | `{"relayNowMs": Date.now()}`，一行 |
| 限流（每连接 token bucket） | ~60 行 | 房间 id 是权限边界，必须防刷 |
| 心跳超时剔除 | ~40 行 | 30s 无 heartbeat 视为掉线 |
| **合计** | **~600 行** | 加配置与启动约 700 行 |

**校验这个估算的实证**：alisten 的「房间 + WS 房间逻辑」部分是 `cmd/alisten/house.go` 515 行 + `main.go` 275 行 + `context.go` 177 行 ≈ **967 行**，而它比上面的最小实现多做了「推荐下一首」（`recommendNext`）、「投票跳歌」（`Skip`/`VoteSkip`）、房间密码、pgsql 持久化、音频转发（`internal/mpipe`）。**剥掉这些之后落在 600-700 行是可信的。**

### 5.2 能不能直接复用现成开源后端

| 候选 | 许可证 | 行数 | 能否直接用 | 结论 |
|---|---|---|---|---|
| **`bihua-university/alisten`** | **GPL-3.0** | 非测试 5736 行 / 46 文件；房间核心 ~967 行 | **许可证兼容，可以整段改造**。但它的音频路径是 `internal/mpipe/pipe.go:157` 的 `ServeHTTP` **服务端转发音频**，与本方案（本地取链）相反 ⇒ **只借协议形状与房间状态机，删掉 mpipe 与 provider** | **推荐：作为房间状态机的蓝本** |
| `lucassantana-dev/cojam` | MIT | 服务端 15923 行 / 66 文件 | 许可证兼容，但要 Postgres + `centrifugal/centrifuge`（`apps/server/go.mod`），且服务端权威、有 DB 迁移、有管理后台 | 不推荐直接部署；**只借 `clockSync.ts` / `playbackSync.ts` 两个算法与它们的单测** |
| `Protomothis/ShareAux` | **AGPL-3.0** | 大 | **许可证不兼容**（见 §8） | 排除 |
| `jellyfin/jellyfin`（SyncPlay） | GPL-2.0 | SyncPlay 子系统 30+ 文件 | **许可证不兼容**（GPLv2-only vs GPLv3） | 排除，只作设计参考 |

**明确回答「能否用现成开源后端直接复用（点名哪个项目、许可证）」**：

- **能，点 `bihua-university/alisten`，GPL-3.0**。它是本报告找到的唯一同时满足「跨网易云/QQ/B站三源」「房间状态机完整」「许可证与 Arris 的 GPLv3 兼容」的项目。改造方式：保留 `protocol/`（IDL + 编解码）与 `cmd/alisten/house.go` 的房间状态机与 `generation` 闸门，删掉 `internal/mpipe`（音频转发）、`internal/music/provider.go` 的取链部分（改为客户端本地取链）、pgsql 持久化。
- **不能直接用的两个**：CoJam（太重，且它自己的定位是「server 权威 + Postgres」，与「无自建服务端」的前提冲突）；ShareAux / Jellyfin（许可证）。

### 5.3 部署形态

三档，都不需要运维：

1. **局域网 / 同一 WiFi**：relay 跑在其中一台设备或一台树莓派上（alisten 用 `compose.yml` 一键起）。
2. **公网自建**：单核 512MB VPS 足够 —— 一个房间 10 人 × 每 5s 一次心跳 + 切歌时的 4KB 广播，日均流量以 MB 计。
3. **无 relay 的降级**：PR3 可以先把「一起听」限制在「同一 relay 地址」的显式配置下（设置页填 URL），不做服务发现。这符合「无自建服务端」的约束 —— **项目本身不提供公共 relay，用户自带。**

---

## 6. 官方 API 可行性

### 6.1 证据强度：**有源码实现，且有两份独立实现**

这是本报告证据最强的一节。两份互不相关的第三方实现都完整实现了网易云官方「多人一起听」协议：

**证据 A — `wow-233/splayer-netease-together`（`repos/splayer-together`，HEAD `96f07be`）**

- README 明说：「房间使用网易云官方 `multi` 协议和 `multishare` 邀请链接，可以容纳多人。旧的『耳机分你一半』双人链接会被明确拒绝。」
- 协议方法名（`src/neteaseApi.ts:15-22`）：
  `listentogether_multi_room_create` / `listentogether_multi_match_ack` / `listentogether_multi_heartbeat` / `listentogether_multi_match_exit` / `listentogether_multi_song_operate` / `listentogether_multi_match_status_get` / `listentogether_multi_match_msg_history` / `listentogether_security_token`
- 邀请链接形状（`src/sync.ts:59-67`）：`https://st.music.163.com/listen-together/multishare/index.html?roomId=…&inviterUid=…&songId=…`
- 心跳节奏（`src/sync.ts:19-21, 77-90`）：服务端给 `heartBeatDuration`（秒），夹到 2s–30s，默认 20s；退避上限 4 倍。
- 本地补偿（`src/sync.ts:69-75, 727`）：`expected = playedTime + halfRtt`，`halfRtt = (endedAt - startedAt)/2`，`|local - expected| > 2000ms` 才 seek。

**证据 B — `tabidachinokaze/music-party`（`repos/music-party`，HEAD `8a23d24`）+ `folium-mod-music-party`（`repos/folium-party`）**

- 完整端点表（`repos/folium-party/vendor/music-party/src/main/multi-api.ts:3-24`），路径形如：
  - `/api/listen/together/multi/room/create`
  - `/api/listen/together/multi/match`、`/match/ack`、`/match/exit`、`/match/cancel`
  - `/api/listen/together/multi/match/heartbeat`
  - `/api/listen/together/multi/match/status/get`
  - `/api/listen/together/multi/match/played/song/list`、`/match/wait/song/list`
  - `/api/listen/together/multi/played/song/info`
  - `/api/listen/together/multi/match/song/operate`
  - `/api/listen/together/multi/match/msg/history`
  - `/api/middle/im/chatroom/send`（房间聊天）
- 请求体逐字段（`multi-api.ts:26-87`），含 `operate` 的操作码：`add=1, up=2, like=3, next=4, redHeart=5, remove=7`；`exitType` 取 `NORMAL_END` / `CHANGE_ROOM`；`checkToken` 几乎每个写操作都要。
- 响应解析与字段语义（`vendor/music-party/src/shared/multiplayer.ts:41-58`）：`roomPlaySongInfo.{playSong, nextSongs, version, playedTime, songDuration, forceSync, waitSongCount, playingSongZanCnt}`；`version` 是单调递增的房间版本号（`:43` 明确校验 `>= 0`）。
- 注释自称「Protocol fields verified in the official multi-listen Android client linked by its share page」（`multi-api.ts:1`）。

### 6.2 但「能接入」不等于「Arris 能直接调」

关键差别：**这两份实现都不是自己签 eapi**。

- `music-party` 的所有请求都走一个外部 `ncm-api`（NeteaseCloudMusicApi 风格的容器）：`createHttpInvoker(base)` 就是 `POST JSON` 到配置的 API 地址，并在请求体里带 `crypto: 'eapi'`（`vendor/music-party/src/main/transport.ts:1-50`；`service.ts:370,376,382,426`）。**签名和 cookie 由那个容器负责。**
- `splayer-together` 走宿主 Electron 主进程的 `netease.call`（README 的宿主扩展清单；`src/types.ts` 的 `HostApi.netease.call`）。

**Arris 的处境比它们都好**：`RetrofitClient` 已经有**通用** eapi POST：

```kotlin
// network/RetrofitClient.kt:257-289
fun eapiPost(path: String, payload: Map<String, String>, useInterface: Boolean = false, …): Response
  ├─ EapiCrypto.encryptParams(fullUrl, payload)      // AES-ECB key "e82ckenh8dichen8"，MD5 magic
  ├─ FormBody(params=…)
  └─ header Cookie: <本地会话>   ← 不经过任何服务端
```

而 `EapiCrypto.encryptParams` 已经把 `/eapi/` 前缀映射成 `/api/` 做摘要（`network/crypto/EapiCrypto.kt:16`：`parsedUrl.path.replace("/eapi/", "/api/")`）—— 也就是说 **`/eapi/listen/together/multi/match/heartbeat` 这种路径与既有的 `/eapi/playlist/create`（`network/PlaylistEditApi.kt:64`）走的是同一条通路**，零新增依赖、零新增加密代码。

### 6.3 风险等级：**高**

逐条给出风险来源，不做泛泛而谈：

| 风险 | 具体是什么 | 为什么高 |
|---|---|---|
| `checkToken` 语义未公开 | `splayer-together` 调 `listentogether_security_token`（`src/neteaseApi.ts:22`）；`music-party` 调 `register_checktoken_v3`（`vendor/music-party/src/main/service.ts:394`），并把它塞进 `create`/`join`/`songOperate` 的每个写请求 | 这是官方风控令牌，**没有公开定义**。拿不到就没有写权限；拿到了它也会过期/轮换。绕过它的语义是明确的协议对抗 |
| 会话身份要求 | 官方 multi 接口大概率要求 eapi 的**客户端指纹**（Arris 已有：`ANDROID_UA`/`XEAPI_OS`，`network/RetrofitClient.kt:43-46`）与 `xeapi` 公钥会话（`network/XeapiState.kt:24-35`） | 指纹错了会撞 `-460` 风险码（`RetrofitClient.kt:28-29` 已经记录过这条：「写操作发错 host 会被判 -460 风险」） |
| 房间生命周期由官方控制 | 错误码 488 = 房间失效（`splayer-together/src/sync.ts:738-741`） | 房间可能被服务端单方面关闭；**离线/弱网时无降级路径** |
| 与「跨源」根本冲突 | 官方房间只认 ncm `songId`（`multiplayer.ts:36` 的 `songId/songBizId/songRcmdUid`） | 一旦走官方房间，QQ/B 站成员只能靠官方客户端的「跨平台匹配」（不存在），Arris 的 `CrossSourceMatcher` 在这场房间里无处可用 |
| 代码不能借 | 两份实现分别是 **无 LICENSE**（splayer）与 **AGPL-3.0**（music-party/folium） | 见 §8：GPLv3 仓库一个字节都不能搬 |

**建议**：官方协议**不进主线**。它最多作为 PR4 的「协议探针」存在，且前置条件是 §9 的三条真机验证全部通过。产品上更合理的做法是：**把 Arris 的房间做成开放协议（§3），另提供一个「把 Arris 房间的播放位置同步给网易云官方房间」的单向桥**（读完就够，不写），这样既不赌 checkToken，也不牺牲跨源。

**QQ 音乐房间：未验证。** 我没有找到任何 QQ 音乐「一起听 / 房间」的公开协议实现或端点证据。搜索只返回了整合型 API 项目（如 `dezhishen/listen-music`）与产品介绍文章，**没有代码级证据**。原因：QQ 音乐的社交房间能力在其客户端内部，第三方客户端生态（本项目用的是自研 `qq/` 包）没有覆盖到这一层。**明确标记为未验证，不作为设计依据。**

---

## 7. 落地计划（3 个 PR）

拆分原则：**PR1 与 PR2 完全不碰 Android 与既有文件**，可单测；PR3 才接线。这样前两个 PR 的验收是 `./gradlew test` 全绿 + 零回归风险，PR3 的失败面被压到「只可能是接线错」。

### PR1 —— `listen/` 纯逻辑层（协议 + 时钟 + 漂移）

**范围**：新包 `com.takahashirinta.ncrust.listen`，4 个文件，全部纯 Kotlin（零 Android import）。

| 文件 | 内容 | 纯逻辑？ |
|---|---|---|
| `listen/RoomState.kt` | §3.1 的全部 data class + `CanonicalTrack` | ✅ |
| `listen/RoomProtocol.kt` | 帧的 JSON 编解码（Gson，与项目一致：`app/build.gradle.kts:634`）+ `(epoch, seq)` 接受判定 + `protocolVersion` 校验 | ✅ |
| `listen/RoomClock.kt` | §3.3.2 的 NTP-lite（注入 `now: () -> Long`） | ✅ |
| `listen/DriftPolicy.kt` | §3.3.3 的三档决策纯函数 | ✅ |

**涉及既有文件**：**零**。（不碰 `Strings.kt`、不碰 `SettingsRegistry.kt`、不碰 `PlayerViewModel.kt`。）

**单测**（新增 `app/src/test/java/com/takahashirinta/ncrust/listen/`）：

- `RoomClockTest` —— fixture 直接搬 `repos/cojam/apps/web/lib/clockSync.test.ts:8-47` 的四个用例（含负 offset、RTT=0、min-RTT 选择）。
- `RoomProtocolTest` —— 覆盖：`seq` 回退必须拒绝、`epoch` 变更必须接受、`protocolVersion` 不匹配必须拒绝、`expectedSeq` 过期必须 rejected。
- `DriftPolicyTest` —— 覆盖四档边界（50/300/2000/越尾）与 seek 退避 3s→24s。

**验收**：
```bash
./gradlew :app:testDebugUnitTest --tests 'com.takahashirinta.ncrust.listen.*'
```
全绿，且 `git diff --stat` 只显示 `listen/` 与对应 test 目录（可机器校验）。

**参考实现对照**：本 PR 的三份算法各有直接对照物（`cojam/apps/web/lib/clockSync.ts`、`playbackSync.ts`；`jellyfin-mpv-shim/jellyfin_mpv_shim/syncplay.py:132-215`；`repos/splayer-together/src/sync.ts:69-90`）。**照形状不照代码**（许可证原因，见 §8）。

### PR2 —— 房间内跨源解析

**范围**：新增 2 个文件，仍然纯逻辑。

| 文件 | 内容 |
|---|---|
| `listen/RoomTrackResolver.kt` | §2.2 的解析器（同源直连 → 跨源 `gradeTrack` → `mergeable` 闸门） |
| `listen/VersionGuard.kt` | §2.4 的版本拒否判据（`editionKinds` 为主、`TrackVersionTag` 为辅） |

**复用而非重写**：`CrossSourceMatcher.gradeTrack`（`crosssource/CrossSourceMatcher.kt:249`）、`NameNormalizer.editionKinds`（`:124`）、`MatchConfidence.mergeable`（`crosssource/CrossSourceModels.kt:61`）——**一行都不复制**，直接调用。

**涉及既有文件**：零（`TrackVersionTag` 不改，见 §2.4 第 3 点）。

**单测**（新增 `RoomTrackResolverTest` / `VersionGuardTest`）：

- 把 `CrossSourceMatcher` 既有的同名不同版本 fixture 搬过来，断言：`晴天` vs `晴天 (Live)` → `LOW` → `Unmatched`；`晴天` vs `晴天 (伴奏版)` → `LOW`；`晴天` vs `晴天 (钢琴版)` → `LOW`。
- 同源直连用例：`publicIds["netease"] = "186016"` + 本地源 ncm → `direct=true` 且**零次** `searchLocal` 调用（用调用计数断言）。
- 跨源用例：`publicIds` 只有 ncm，本地源 QQ → 走 `gradeTrack`，`MEDIUM` 可合并、`LOW` 不可。

**验收**：
```bash
./gradlew :app:testDebugUnitTest --tests '*RoomTrackResolverTest' --tests '*VersionGuardTest'
```
全绿；且断言 `CrossSourceMatcherTest` / `NameNormalizerTest` **一字节未变**（`git diff --stat` 校验）。

### PR3 —— 传输与接线（第一个碰 Android 的 PR）

**范围**：3 个新文件 + 4 处最小改动。

| 位置 | 改动 |
|---|---|
| `listen/RelayClient.kt`（新） | OkHttp `newWebSocket`（复用 `RetrofitClient.baseClient`），8 种帧的收发、心跳、重连退避 |
| `listen/RoomSession.kt`（新） | 状态机：`applyRemote(state)` → 调 `DriftPolicy` → 发 `seek` / `setPlaybackParameters` |
| `listen/RoomCommands.kt`（新） | 把 `RoomState` 翻译成既有 intent（复用 `playUrl` 的 `startPositionMs` 通路） |
| `player/PlaybackService.kt` | **仅新增**：`"roomSeek"` 与 `"roomRate"` 两个 action 分支（照 `:943-950` 的形状），以及把 `player.currentPosition` 暴露给 `RoomSession`（可复用既有 `publishingTrackKey()`，`:1217`） |
| `ui/viewmodel/PlayerViewModel.kt` | **仅新增**：`fun applyRoomTransport(playing: Boolean, positionMs: Long)`，内部调用既有 `seekTo`（`:3106`）/ `togglePlayPause`（`:3071`） |
| `ui/settings/SettingsRegistry.kt` | **仅新增**：`GENERAL` 组加一条 `SettingsEntryType.ACTION` 行「一起听」（不进 `SettingsGroup` 枚举 —— 一级卡片已有 7 张，`SettingsRegistry.kt:49-110`；新增第 8 张要动 8 个 locale 的 14 条分组文案 + 图标映射表，代价与收益不成比例） |
| `ui/i18n/Strings.kt` | **仅新增**：往 `SettingsStrings`（`:1632`）加字段 + 8 个 locale 各补一份 |

**`Strings.kt` 的硬约束必须遵守**（类文档 `:1-56` 写死了）：外层主构造参数当前 136，dex 槽 `1 + 136 + 5 + 1 = 143`，**余量 112**（`:37-38`）。**往 `SettingsStrings` 里加字段是安全的；往 `Strings` 主构造器加字段会吃掉余量。** `StringsConstructorBudgetTest` 会拦。

**验收**：
1. `./gradlew :app:testDebugUnitTest` 全绿（含既有的 `StringsConstructorBudgetTest`）。
2. 两台真机（建议不同 Android 版本，API 24 与 API 36 各一台）进同一房间，播放 10 分钟，每 5s 抽样 `|localPos - targetPos|`，**P95 < 300ms、无 seek 抖动（连续 seek ≤ 1 次）**。
3. 断网 10s 再恢复：房间回到一致状态，全程**无静音**、无 `player.stop()`。
4. 房主退出：30s 内自动迁移（或明确提示「房主已离开，房间结束」），不出现双房主。

### 7.1 哪些必须先做协议探针再动手

| 探针 | 前置 PR | 为什么必须先做 |
|---|---|---|
| **relay 时钟精度**：真机 4G / 5G / WiFi 下 `minRtt` 分布与 `offset` 稳定度（连续 5 分钟） | PR3 之前 | §3.3 的 50ms/300ms 阈值是从 mpv-shim（桌面、有线/稳定 WiFi）借来的。移动网络 RTT 抖动可能是 200-800ms ⇒ `minRtt/2` 的误差可能超过 300ms 阈值 ⇒ **seek 阈值要按实测重新定**。这是本报告里最可能被实测推翻的一个数字 |
| **`setPlaybackParameters` 在 API 24 的行为** | PR3 之前 | media3 1.5.0 支持，但 API 24 的 `AudioTrack` 路径与 FFmpeg 软解扩展（`app/build.gradle.kts:628`）的组合未验证。AR 最低支持到 API 24，这条必须先跑一次真机 |
| **官方 `checkToken` 是否可获取** | PR4（可选）之前 | 见 §6.3。若拿不到，官方协议整条路直接否决，不做任何实现 |
| **网易云官方 heartbeat 的 `playedTime` 语义**（是「现在在播哪」还是「上次心跳时在播哪」） | PR4 之前 | 决定 §3.3.1 里 `+halfRtt` 还是不加。两份参考实现对这一点**不一致**（splayer 加，folium 用请求中点），说明它们都没确证 |

---

## 8. 许可证

### 8.1 逐项（全部亲自读过 LICENSE 文件，不是看徽章）

| 项目 | 我读到的 | 许可证 | 能否搬进 GPLv3 的 Arris |
|---|---|---|---|
| `bihua-university/alisten` | `LICENSE` = GNU GPL **Version 3**, 29 June 2007 | **GPL-3.0** | ✅ **可以**（整段改造后仍以 GPLv3 分发；保留原版权声明与变更说明） |
| `jofr/listentogether-app` | `LICENSE` = GNU GPL **Version 3**, 29 June 2007 | **GPL-3.0** | ✅ 可以 |
| `jellyfin/jellyfin-mpv-shim` | `LICENSE.md` 开头：「…derivitive of Jellyfin Kodi, which is licensed under the terms of the GPLv3. **The entire work is subject to the terms of the GPLv3**, as this is the more restrictive license.」 | **GPL-3.0**（整体） | ✅ 可以（MIT 部分已并入 GPLv3 整体） |
| `lucassantana-dev/cojam` | `LICENSE` = MIT License, Copyright (c) 2026 Lucas Santana | **MIT** | ✅ 可以（保留版权与许可声明） |
| `jellyfin/jellyfin` | `LICENSE` = GNU GPL **Version 2**, June 1991；README 徽章写 "GPL 2.0 License" | **GPL-2.0** | ❌ **不可以**。GPLv2-only 与 GPLv3 不兼容（FSF 立场；GPLv3 的附加条款与 GPLv2 的「无附加限制」条款直接冲突）。**一位代码都不能进 Arris** |
| `Protomothis/ShareAux` | `LICENSE` = GNU **AFFERO** GPL **Version 3** | **AGPL-3.0** | ❌ **不可以**。AGPL 只能单向吸收 GPLv3 代码，反向不行：把 AGPL 代码并入 GPLv3 作品会让整个作品需要满足 AGPL §13（网络使用即分发），而 Arris 无法对 MIT 来源的部分（如 `LICENSE-MIT` 的 Ncrust 原始代码）施加 AGPL 的额外约束 |
| `tabidachinokaze/music-party` | `licenses/folium-mod-music-party-AGPL-3.0.txt`；`package.json` **无 `license` 字段** | **AGPL-3.0**（按仓库自带许可文件认定；manifest 缺失不改变结论） | ❌ 不可以 |
| `tabidachinokaze/folium-mod-music-party` | `LICENSE` = GNU AFFERO GPL **Version 3**；`package.json` `"license": "AGPL-3.0-only"` | **AGPL-3.0-only** | ❌ 不可以 |
| `wow-233/splayer-netease-together` | **无 LICENSE 文件**（`ls` 无匹配；`package.json` 无 `license` 字段） | **未授权 / 保留所有权利**（默认版权法） | ❌ **最严格的一个**。没有许可证 = 没有授予任何权利，连「参考它的端点表」在法律上都只是**读公开文档级的事实**（接口路径属于事实，不受版权保护），但**代码一行都不能复制** |
| `zoff-music/zoff` | 只有 107 字节 README，无许可证、无代码 | 无 | — 无代码可搬 |

### 8.2 一句话结论

**可以搬的只有三个：`alisten`（GPL-3.0）、`listentogether-app`（GPL-3.0）、`jellyfin-mpv-shim`（GPL-3.0）；`cojam`（MIT）也可以但要保留其版权声明。**
**绝对不碰的：`jellyfin`（GPL-2.0，版本不兼容）、`ShareAux` / `music-party` / `folium-party`（AGPL-3.0）、`splayer-netease-together`（无许可证）。**

### 8.3 对本报告的实际影响

- §3 的算法我引的是 CoJam（MIT）与 mpv-shim（GPL-3.0）—— **两者都可搬**，所以 PR1 可以合法地把 `clockSync.ts` 的逻辑**翻译**成 Kotlin（翻译后仍是衍生作品，需在文件头注明来源与许可证）。
- §1/§5 引的 alisten（GPL-3.0）—— **可搬**，PR3 的房间状态机可以基于 `house.go` 改造。
- §6 的官方端点表来自 AGPL 实现 —— **端点路径/参数名是接口事实**（不受版权保护，且它们本就来自官方客户端的公开行为），可以据此写探针；但**不能复制它们的任何代码**（包括 `multiPayload` 的 switch、`parseRoomPlayback` 的解析函数）。
- `ShareAux` / `jellyfin` 在本报告里**只作为反面/对照样本被描述**，不产生任何可搬代码。

**必须在 PR 里做的事**：如果 PR1/PR3 真的翻译了 CoJam 或 alisten 的代码，`THIRD-PARTY-LICENSES.md`（Arris 已有此文件）需要相应新增条目，文件头按既有风格标注来源（Arris 现有文件头格式见任一 `.kt` 的前 7 行）。

---

## 9. 未解决 / 需要真机或协议探针验证

按「会不会改变设计」排序，逐条给理由。

### 9.1 会改变设计的（必须先验）

1. **移动网络下 `minRtt` 的实际分布** —— 决定 §3.3.3 的 50/300ms 阈值是否成立。若 4G 下 `minRtt` 波动 > 600ms，则 `offset` 误差可能 > 300ms，「微调档」会一直触发 seek，必须改成「纯 seek + 更长冷却」或引入 relay 侧的时间戳回显（把 `offset` 估计改成双向四戳 NTP）。**未验证的原因**：需要真机 + 真实移动网络，本次只有静态代码分析。
2. **`Player.setPlaybackParameters` 在 API 24 + FFmpeg 软解扩展下的行为** —— 决定「微调档」能不能用。若在 API 24 上变速会导致音频中断或音高改变，则微调档要按 SDK 分级关闭，退化成「只有 seek」。**未验证的原因**：需要 API 24 真机/模拟器实跑（Arris 的 `minSdk = 24`，`app/build.gradle.kts:46`）。
3. **官方 `listentogether_security_token` / `register_checktoken_v3` 能否在 Arris 的 eapi 身份下拿到** —— 决定 §6 整条路是否成立。**未验证的原因**：需要在真机上用 Arris 的 cookie + `ANDROID_UA`（`network/RetrofitClient.kt:43`）实打一次；本次只做到「读通两份实现的代码」。**如果这条探针失败，官方协议直接否决，不做任何后续工作。**

### 9.2 不影响主设计但需要补证据的

4. **QQ 音乐是否有等价房间协议** —— 完全未验证。没有任何公开实现或端点证据（见 §6.3 末段）。设计上已按「QQ 只作为取链音源、不提供官方房间」处理，因此不阻塞。
5. **`Zoff` 的任何技术细节** —— 仓库是 README-only stub（`README.md` 107 字节），**零代码**。无法给出任何结论。
6. **`alisten` 的 `Loudness()` 实现精度** —— `internal/music/loudness/loudness.go`（353 行）我没读完，`volumeCoefficient` 的具体算法（EBU R128？采样峰值？）未确证。设计上 PR3 只用「房主手动设置」，所以不阻塞；若后续要做自动响度归一，需要先读完这个文件 + `internal/music/wav/decode.go`。
7. **`music-party` 的 `checkToken` 获取链路细节** —— 只看到 `service.ts:394` 调 `register_checktoken_v3`，它背后打到哪个 `/api/` 路径我没追（该调用走 `invoke('register_checktoken_v3')`，而 `multiEndpoints` 表里没有它，说明它属于另一个端点表）。**未验证的原因**：`service.ts` 的 `endpoints` 映射（`:44-46`）只覆盖 `multiEndpoints`，其余走宿主的通用 `netease.call`，实际 URL 在宿主侧。这条如果要做 PR4 必须补。

### 9.3 已知的设计缺口（本报告没有解决，如实记录）

8. **房主迁移时的「位置跳变」无法消除。** 新房主的 `currentPosition` 与旧房主的 `targetPosition` 一定有差（两者本来就有漂移），迁移瞬间全体会 seek 一次。`listen-together` 的 `transformToHost()` 也没解决这个问题（它只是「用当前状态继续」）。**接受这个缺口**，UI 上给一次「房主已切换，正在对齐」的提示。
9. **「静音跟随」的流量成本未量化。** `player.volume = 0f` 仍会下载完整音频。一首 320kbps 的歌静音播完约 9MB。若房间里多数成员都没会员，这个成本不可忽略。**未量化**：需要真机测 `CacheDataSource`（`app/build.gradle.kts:618-619`）在 volume=0 时是否仍然写入缓存 —— 如果写，成本可被离线缓存摊薄，那静音跟随就更划算。
10. **房间队列的「已播 50 条」上限是从 CoJam 抄的**（`protocol.ts:67-69`），没有针对 Arris 的队列语义验证。Arris 的队列有 `ShuffleRound`（`player/ShuffleNav.kt`、`ShuffleRound.kt`）与 `QueueModes` 五种模式，房间里是否复现「随机播放」未设计 —— **本报告建议 v1 房间固定为顺序播放**，把 `QueueModes` 留在本地（房主的随机只影响房主自己的下一首选择，不影响房间队列）。这条是设计选择，不是缺口，但需要产品确认。

---

## 附：本次调研没有做的事（边界声明）

- **没有改任何 app 源码**。`git status` 里的 10 个已修改文件（`app/build.gradle.kts`、`MainActivity.kt`、`QqAuthStore.kt`、`TrackAvailability.kt` 等）是本次调研开始前就存在的未提交改动，与本报告无关。
- **没有创建 git commit**。
- **只写了一个文件**：`docs/research/2026-10-29-listen-together-research.md`（及其父目录）。
- **没有跑任何构建**。所有 Arris 侧结论来自静态阅读；所有参考实现结论来自 `git clone` 后的源码阅读。
