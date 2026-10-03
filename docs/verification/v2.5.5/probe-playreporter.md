# 探针报告：PlayReporter 跨音源数据泄露（v2.5.5）

- 仓库：`/home/duanjb666/deepseek/ncrust-gpl/Ncrust`
- 探针对象（**提交态**）：`HEAD = 10e9df9`（`git describe = v2.5.4-gpl-3-g10e9df9`），
  即 **v2.5.4 / versionCode 45 的源码**
- 真机：`3B15CD00GB700000` = PLC110 / Android 16（已装 v2.5.4-gpl，versionCode 45）；
  `0715f763f54c023a` = 三星 S6 / Android 7.0（同版本，仅作对照）
- 日期：2026-09-26
- 原始证据：[`probe-raw/playreporter/`](probe-raw/playreporter/)
- 相关前置探针：`docs/verification/v2.5.4/probe-qq-fallback.md` §6.3 / §12.3
  （**本报告把它的「未验证项 3」推进了一步**）

---

## §0 结论先行

1. **上报路径**：`PlayReporter.reportPlay()`（`player/PlayReporter.kt:39-78`）在 ncm 登录态下，
   向 `POST https://clientlogusf.music.163.com/api/feedback/weblog?csrf_token=<__csrf>`
   发一条表单 `logs=[{"action":"play","json":{…,"id":<songId>,…}}]`。
   **不带任何音源信息、不区分上报目标**，UI 线程上过卫语句后丢给 `ncrust-weblog` 线程 fire-and-forget。

2. **当前没有任何音源闸门。** 入口卫语句只有三条（`PlayReporter.kt:47/48/50`）：
   `getCookie() != null` → `cookie.contains("MUSIC_U") && songId > 0` → `getCsrfToken() != null`。
   一个 QQ 合成 id 的实测十进制值是 **`4611686018784987997`**
   （= `(1L shl 62) or 357600093`，其中 `1L shl 62 = 4611686018427387904`），
   **符号为正、`songId > 0` 为 true**，因此**三条卫语句一条都拦不住它**
   （Python 与真实 JVM 各复算一遍，输出见 `bit62_guard_probe.out` / `GuardProbe.out`）。

3. **调用点只有两个，两个都可能传 QQ id**（喂进去的都是 `currentSongId.value`，
   它对 QQ 曲目就是那个 bit62 合成 id）：
   `PlayerViewModel.kt:562-564`（2Hz 进度 ticker 的 80% 路径，**真机未取到**）与
   `PlayerViewModel.kt:586-588`（`onPlaybackEnded` 路径，**真机实测已发生**，见第 4 条）。

4. **真机复现成功（这是本次探针的主要增量）**：PCL110 上，当前曲是 QQ 曲目
   （`NcrustTrack: restore -> track=qqmusic:4611686018784987997`）时触发应用的
   `onPlaybackEnded` 路径，logcat 实录
   **`D/PlayReporter(32055): weblog resp: 200 duration=0/0`** ——
   上报**确实发出去了，且服务端返回 HTTP 200**。完整证据链见
   [`probe-raw/playreporter/device-evidence.md`](probe-raw/playreporter/device-evidence.md)。

5. **反方向不成立**：仓库里**没有任何向 QQ 上报播放行为的实现**
   （`qq/QqMusicSourceProvider.kt:40` 明确写入 KDoc；全包 grep 无 weblog/report 出口），
   所以「ncm id 上报给 QQ」这条方向**没有实现，不成立**。

6. **闸门建议（并且本仓库工作区里已在按这个方向落地，见 §3.6）**：
   判据用 `SourceIds.isQqId()`（bit62 结构性事实，不用区间/散列启发式）；
   落点放在 **`PlayReporter.reportPlay` 的最外层**（唯一网络出口，最不容易漏）；
   拦截计数落 **单开的 prefs**（`QqProbeStore` 那套：只私有目录、绝不上报、
   播放关键路径上只做一次内存自增、落盘放 `onStop`）。

---

## §1 方法

| 手段 | 具体做法 | 产出 |
|---|---|---|
| 逐行读码 | 读 `PlayReporter.kt`（全文 84 行）、`PlayerViewModel.kt` 的上报两处与身份写入点、`PlaybackService.kt` 的两个回调发射点、`source/MusicSource.kt` 的 `SourceIds` | [`probe-raw/playreporter/code-excerpts.md`](probe-raw/playreporter/code-excerpts.md)（带真实行号的源码原文） |
| 纯逻辑复算（Python） | 把 `QQ_ID_FLAG`/`isQqId`/`qqId`/`qqRawId` 与三条卫语句、`reachedCompletion` 等价复算，样本含真机实测 id | `bit62_guard_probe.py` + `.out` |
| 纯逻辑复算（真实 JVM） | 同一套判定跑在真正的 64 位有符号 `long` 与 `toFloat()` 语义上，交叉验证 Python 的符号折叠 | `GuardProbe.java` + `.out`（`java GuardProbe.java`，JDK 26） |
| 真机复现 | PCL110：`logcat -v time` 全程捕获 + `dumpsys media_session` + 读 `ncrust_playback_state.xml`（root）；用应用**自己支持的** `action=next` 服务命令触发 `onPlaybackEnded` | `pcl110-logcat-raw.txt.gz`、`pcl110-app-log-excerpt.txt`、`pcl110-prefs-evidence.txt`、`device-evidence.md` |
| 反向检索 | 全仓库 grep `weblog`/`postWeblog`/`reportPlay`/`analytics`/`上报`，并按文件枚举 QQ 侧端点 | §2.8 |

**探针纪律**：没有改 `app/` 下的任何源码（工作区里的改动来自**另一条并行工作流**，见 §3.6）；
没有碰华为控制中心卡片；没有把任何测试、日志、频率数字写成没测到的样子。

---

## §2 逐问回答

### 2.1 上报路径是什么？（URL / 方法 / 字段 / 触发条件 / 线程模型）

**URL 与方法**（`PlayReporter.kt:24-25, 50-51, 71`）

```
POST https://clientlogusf.music.163.com/api/feedback/weblog?csrf_token=<__csrf 的值>
Content-Type: application/x-www-form-urlencoded
body: logs=<JSON 数组串>
```

- `csrf_token` 取自 cookie 串里的 `__csrf=` 字段（`RetrofitClient.getCsrfToken()`，`network/RetrofitClient.kt:223-231`），
  取不到就直接 `return`（`PlayReporter.kt:50`）——**不发请求**。
- 请求头由 `RetrofitClient.postWeblog` 统一加：`User-Agent`（PC UA）、`Referer: https://music.163.com/`、
  `Cookie: <当前ncm cookie>`（`RetrofitClient.kt:212-220`）。**不加密**，不走 eapi/weapi。

**字段**（`PlayReporter.kt:53-66`，字段顺序即源码顺序）

```json
[{"action":"play","json":{
  "type":"song", "wifi":0|1, "download":0, "id":<songId>,
  "time":<playedMs>, "end":"playend", "mainsite":"1", "mainsiteWeb":"1",
  "alg":"<strategy>"        // 仅当 strategy 非空才出现；两个调用点都没传 ⇒ 实际不出现
}}]
```

- `wifi` 是**反的**：`if (isWifi) 0 else 1`（`PlayReporter.kt:55`），Wi-Fi ⇒ 0。
- `end` 两个调用点都显式传 `"playend"`；KDoc 里提到的 `interrupt`/`ui`/`exception` **无调用方**。
- `download` 恒 0（与下载功能无关，历史字段）。
- `time` = 已播毫秒。

**触发条件**（两个，都在 `PlayerViewModel`）

| # | 位置 | 条件 | 传参 |
|---|---|---|---|
| A | `PlayerViewModel.kt:561-565` | 2Hz ticker 回调里：`sid > 0 && sid != lastReportedSongId && PlayReporter.reachedCompletion(pos, dur)`，即 `dur > 0 && pos/dur ≥ 0.8` | `reportPlay(sid, pos, dur, end="playend", isWifi=isOnWifi())` |
| B | `PlayerViewModel.kt:583-589` | `onPlaybackEnded` 回调里：`sid > 0 && sid != lastReportedSongId` ——**没有任何进度/时长条件** | `reportPlay(sid, duration.value, duration.value, end="playend", isWifi=isOnWifi())` |

`onPlaybackEnded` 的三个发射点（都在 `PlaybackService`）：
ExoPlayer `STATE_ENDED`（`PlaybackService.kt:426-429`）、
媒体键/通知「下一首」（`onPlayerCommandRequest` → `APP_NEXT`，`PlaybackService.kt:765-784`）、
服务命令 `extra action="next"`（`PlaybackService.kt:677`）。
`onProgressUpdate` 的发射点在 `PlaybackService` 里只有两处：
`startProgressUpdates` 的 `delay(500)` 循环，**只在 `player.isPlaying` 时广播**（`PlaybackService.kt:1497-1511`）；
以及 `publishProgressNow()` 在 seek 后补发一次（`:1492-1495`）。

**线程模型**

- 卫语句与 JSON 拼装：**在调用方线程同步执行**。两个调用点都来自
  `PlaybackService.scope = CoroutineScope(Dispatchers.Main + SupervisorJob())`（`PlaybackService.kt:114`）
  发起的回调 ⇒ **主线程**。这一段只有 cookie 字符串判断、`split(';')` 与 `JSONObject` 拼装，量级是微秒。
- 网络：`thread(name = "ncrust-weblog") { … }`（`PlayReporter.kt:69-77`）——**新建裸线程**，fire-and-forget，
  异常被 `catch (e: Exception)` 吞掉只打日志。**播放链路不等它、也不受它影响**。
- 去重：`private var lastReportedSongId = -1L`（`PlayerViewModel.kt:219`），**进程内、永不重置**；
  同一进程里同一首歌只上报一次（换歌才换 key）。进程被杀重启后去重表清零。

### 2.2 当前有没有音源闸门？逐条卫语句 + QQ id 能不能过？

**没有。**`reportPlay` 的全部入口卫语句（`PlayReporter.kt:47-51`）：

| # | 源码 | 判据 | 一个 bit62 的 QQ id 能过吗 |
|---|---|---|---|
| 1 | `val cookie = RetrofitClient.getCookie() ?: return` | 有没有 ncm cookie | **能过**（只要用户登录了 ncm） |
| 2 | `if (!cookie.contains("MUSIC_U") \|\| songId <= 0) return` | cookie 含 `MUSIC_U` **且** `songId > 0` | **能过**：QQ id 是**正数**（见下），`contains` 与音源无关 |
| 3 | `val csrf = RetrofitClient.getCsrfToken() ?: return` | cookie 里有没有 `__csrf=` | **能过**（登录态通常都有） |

之后 `songId` 被**原样**写进 JSON（`.put("id", songId)`，`PlayReporter.kt:57`），**没有任何分支或改写**。

**算一遍（真机实测样本 + 边界样本）**

`QQ_ID_FLAG = 1L shl 62 = 4611686018427387904`；真机（PCL110）落盘的 QQ 曲目 id：

| 样本 | 十进制值 | 符号 | `> 0` | `isQqId` | `sourceOfId` | `qqRawId` |
|---|---|---|---|---|---|---|
| 真机 QQ《怪我太天真》/ 苏谭谭 | **4611686018784987997** | + | true | true | QQMUSIC | 357600093 |
| 其反解出的裸 songid | 357600093 | + | true | **false** | NETEASE | null |
| ncm 样本（S6 落盘） | 557920 | + | true | false | NETEASE | null |
| 只有标志位 `1L shl 62` | 4611686018427387904 | + | true | true | QQMUSIC | 0 |

> 关键：**bit62 置位后符号位（bit63）仍然是 0**，所以 `Long` 依旧是正数。
> `songId <= 0` 这条卫语句在语义上就**不可能**拦住 QQ 合成 id —— 它不是「没判住」，是判据本身不覆盖这个维度。

复算输出（节选，全量见 `probe-raw/playreporter/bit62_guard_probe.out` 与 `GuardProbe.out`）：

```
样本                                十进制值     符号    > 0 ?    isQqId sourceOfId        qqRawId
真机实测 QQ 曲目（怪我太天真 / 苏谭谭）  4611686018784987997      +      yes      True   QQMUSIC      357600093
--- 样本：真机实测 QQ 曲目（怪我太天真 / 苏谭谭）
    [PASS] L47  getCookie() != null
    [PASS] L48a cookie.contains("MUSIC_U")
    [PASS] L48b songId <= 0    songId=4611686018784987997 ⇒ songId<=0 为 False
    [PASS] L50  getCsrfToken() != null
    => 通过全部卫语句，**会发出上报**
```

### 2.3 谁调用 `reportPlay`？逐个判断能不能传 QQ id

全仓库 grep（`--include=*.kt`，含 `benchmark/`）只有 **2 个调用点**，都在 `PlayerViewModel`：

| 调用点 | 路径 | 传进去的 id | 能传 QQ id 吗 | 依据 |
|---|---|---|---|---|
| A | `PlayerViewModel.kt:562-564` | `currentSongId.value` | **能** | `currentSongId` 的每一个写入点都可能写 QQ 合成 id（见下） |
| B | `PlayerViewModel.kt:586-588` | `currentSongId.value` | **能**（**真机已实测发生**） | `pcl110-app-log-excerpt.txt` 18:35:12 → 18:35:20 三段日志 |

`currentSongId.value`（`PlayerViewModel.kt:181`，`MutableStateFlow<Long?>`）的**全部**写入点，
以及它们能不能拿到 QQ id：

| 场景 | 位置 | 能不能是 QQ id | 说明 |
|---|---|---|---|
| **切歌（正常开播）** | `:1241`（取链后开播）、`:1180`（预载缓存命中快路径） | **能** | 入参 `songId` 来自 `MainScreen.playFromQueue` 的 `song.id`（`MainActivity.kt:1157-1166`）；队列里的 QQ 项由 `QqSongMapper.fromSongObject` 造：`SourceIds.qqId(rawId, mid)`（`qq/QqSongMapper.kt:94`，另见 `qq/QqCatalog.kt:351`） |
| **切歌（预载接管）** | `:1631` | **能** | 同一 `songId`，接管也是一次开播 |
| **自动接续（gapless）** | `:632`（`nextTrack.id`，来自 `PlaybackService.onTrackTransitioned` 的载荷） | **能** | 身份由 `PreloadSlot.identityFromMediaId(item.mediaId)` 反解（`PlaybackService.kt:476-478`），mediaId 里带音源 |
| **`PlaybackStateManager` 恢复路径** | `:680`（`currentSongId.value = savedState.songId`） | **能（真机实测就是这条）** | `ncrust_playback_state.xml` 的 `song_id` 存的就是合成 id：PCL110 实测 `4611686018784987997` + `song_source=qqmusic`；恢复日志 `restore -> track=qqmusic:4611686018784987997`（`PlayerViewModel.kt:674-679`） |
| 剪贴板单曲（只载元数据不播） | `:2199`（`prepareSongWithoutPlay`） | **能** | `TrackKey.of(null, songId)` 用 bit62 反推音源（`source/TrackKey.kt:105-109`） |
| 清空 | `:2275`（`null`） | — | — |

**逐条回答任务点名的四条路径**：

- **切歌路径**：`playSong`（`:1180`/`:1241`/`:1631`）→ **能**。QQ 曲目一旦进队列，`song.id` 就是合成 id。
- **播放结束路径**：`PlaybackService.kt:426-429 / :677 / :765-784` → `PlayerViewModel.kt:581` 的
  `onPlaybackEnded` → **能**，而且**没有进度条件**。**本次真机就是走这条**（`action=next`）。
- **`onMediaItemTransition`**：它**不直接**调 `reportPlay`。AUTO transition 时
  `PlaybackService.kt:467-478` 解析出 item 身份 → `onTrackTransitioned` → `PlayerViewModel.kt:611-618`
  存进 `transitionedTrack` → `onSongTransitioned`（`:619-655`）写 `currentSongId`（`:632`）。
  于是**下一拍 ticker 就带着 QQ id 进上报判据**（路径 A）。所以它是**间接**上游，能传 QQ id。
- **进度 ticker**：`PlaybackService.kt:1497-1511` → `PlayerViewModel.kt:538-580` 的回调，
  同一段里就是调用点 A。**能**。
- **`PlaybackStateManager` 恢复路径**：`:680`。**能**，且 PCL110 实测正是这条把 QQ id 放进
  `currentSongId`（`restore -> track=qqmusic:4611686018784987997`），随后 8 秒就被上报了。

### 2.4 上报字段里哪些涉及音源？有没有字段能区分音源？

| 字段 | 是否携带音源信息 | 说明 |
|---|---|---|
| `id` | **间接**（唯一） | 值就是 `songId`。对 QQ 曲目是 bit62 合成 id ⇒ **看数字能看出是 QQ**，但请求里**没有任何字段说明这一点** |
| `alg` | 否 | 推荐策略标识。**两个调用点都没传 `strategy`**（`grep strategy` 只命中 `PlayReporter.kt` 自身）⇒ 实际**从不出现** |
| `type` | 否 | 恒 `"song"` |
| `wifi` | 否 | 网络类型（Wi-Fi ⇒ 0，反的） |
| `download` | 否 | 恒 0 |
| `time` | 否 | 已播毫秒 |
| `end` | 否 | 恒 `"playend"` |
| `mainsite` / `mainsiteWeb` | 否 | 恒 `"1"` |
| （HTTP 头） | 否 | UA / Referer 都是 ncm PC 身份；Cookie 是 ncm 登录态 |

**结论**：没有任何字段能显式区分音源；服务端**只能**靠 `id` 落在哪个 id 空间来猜。
而 bit62 合成 id（`4611686018784987997`）落在 ncm id 空间之外 —— 也就是说，
**ncm 收到的是一条「id 不存在」的播放记录**，而不是「一首 QQ 歌的播放记录」。
（服务端怎么处理这条记录，客户端无法观测，见 §4。）

### 2.5 `reachedCompletion` 会不会让 QQ 曲目更容易/更不容易触发上报？

`reachedCompletion(positionMs, durationMs)`（`PlayReporter.kt:81-83`）**只吃两个 Long**，
`durationMs > 0 && positionMs.toFloat() / durationMs.toFloat() >= 0.8f` ——
**判据本身与音源完全无关**，对两个音源同构。真机实测的边界：
`(160000, 200000) = true`、`(159999, 200000) = false`。

真正的不对称在**路径**上，不在阈值上：

1. **路径 B（`onPlaybackEnded`）根本不看 `reachedCompletion`。** 只要「结束」事件来了、
   当前曲 id 与上次不同，就立刻上报 —— 即使只播了 1 秒、甚至 `duration` 还是 0。
   真机证据：`weblog resp: 200 duration=0/0`。
   QQ 曲目在这一点上**更容易**触发：QQ 取链失败率高
   （未登录 / `104003` / 缓冲卡死；本次探针里腾讯音源的 vkey 链路就把一首歌卡在
   `BUFFERING` 165 秒没动），用户按一下耳机/通知栏「下一首」就直接产生一条上报。
2. **路径 A（80%）需要这首歌真的播到 80%。** 一首取不到链的 QQ 曲目**根本不会**走到这里
   （失败 → 跳歌）。所以「放不出来的 QQ 曲目」在路径 A 上是**更不容易**上报的。
3. **自动跳歌不上报**：`handlePlaybackError` / `auto skip` 走的是队列推进，不经过 `onPlaybackEnded`。
   真机证据：`auto skip #1 for songId=186016`（18:35:32.588）**后面没有** `PlayReporter` 行。
4. **去重是「进程内按 id」**：`lastReportedSongId`（`:219`）不区分音源，也不因换歌重置；
   一首 QQ 曲目与一首同裸 id 的 ncm 曲目是**两个不同的 Long**，去重表不会互相顶掉。

一句话：**阈值是中立的；让 QQ 曲目「更早、更容易」被上报的是 `onPlaybackEnded` 那条没有进度条件的旁路。**

### 2.6 有没有可以真机复现的方式？

**有，已复现**（PCL110 / Android 16 / v2.5.4-gpl / versionCode 45）。完整步骤、
逐条日志与「为什么能断定上报的是 QQ id」的论证见
[`probe-raw/playreporter/device-evidence.md`](probe-raw/playreporter/device-evidence.md)。
最短版本：

```bash
D=3B15CD00GB700000
adb -s $D shell "su -c 'cat /data/data/com.takahashirinta.ncrust/shared_prefs/ncrust_playback_state.xml'"  # 当前曲 id
adb -s $D logcat -c && adb -s $D logcat -v time > raw.txt &
adb -s $D shell "am startservice -n com.takahashirinta.ncrust/.player.PlaybackService --es action next"
grep PlayReporter raw.txt
```

实测结果（原文）：

```
09-26 18:35:12.270 I/NcrustTrack(32055): restore -> track=qqmusic:4611686018784987997 mid=001A7neE3LB7Ki media=000g0rin0v8VU2 (sourceKey=qqmusic)
09-26 18:35:20.097 D/PlaybackService(32055): onStartCommand action=next
09-26 18:35:20.127 I/NcrustTrack(32055): playSong -> currentTrack=netease:503572          ← 队列这时才推进
09-26 18:35:20.390 D/PlayReporter(32055): weblog resp: 200 duration=0/0                    ← 上报成功
```

**能断定上报的是 QQ id，有三条独立依据**（详见 device-evidence §2）：
① `sid` 的读取发生在队列推进**之前**（`reportPlay` 在 `onSongEndedCallback` 之前，同主线程顺序执行）；
② `duration=0/0` 是**路径 B 的指纹** —— 路径 A 因 `reachedCompletion` 要求 `dur > 0`，
不可能打出 0/0；而路径 B 恰好传 `reportPlay(sid, duration.value, duration.value)`；
③ `.put("id", songId)` 从形参到请求体没有分支或改写。

**卡在哪 / 没做到什么（如实写）**：

- **抓包级（报文里 `id` 字段的字节）没做到**：设备走 TLS，未装 MITM 根证书
  （targetSdk 36 默认不信任用户 CA），所以目前是「时机证据 + 无分支数据流」的合力结论。
- **80% 自然达成路径 + QQ id 没在真机取到**：探针期间应用被**另一个 adb 进程反复 force-stop**
  （`Killing … (adj 0): stop com.takahashirinta.ncrust due to from pid 2632 / 3198`），
  加上那首 QQ 曲目卡在 `BUFFERING`（position 165461 不再前进），到 80% 的窗口始终没打开。
  **没有编造任何频率数字**：本报告不提供「多久发生一次」「占比多少」这类数字。
- **S6（Android 7）未复现**：该机落盘的当前曲是 ncm（`song_id=557920`）；要在它上面造出
  「当前曲是 QQ 曲目」必须写应用的持久化状态，按「尽量只读」原则**没有做**（也避免干扰同机其他探针）。

### 2.7 加闸门后的建议行为（详见 §3）

简答：判据用 `SourceIds.isQqId()`；非本源 id 一律不上报、本源 id 照常上报；
被拦次数落**单开的 prefs**（照 `QqProbeStore` 的做法）；
闸门放在 `PlayReporter.reportPlay` 的**最外层**——它已经在播放关键路径之外。

### 2.8 反方向：ncm id 会不会被上报给 QQ？

**不会 —— QQ 侧无上报实现，该方向不成立。**

证据（都是检索/读码，不是推断）：

1. **唯一的上报通道只有 ncm 这一条**：全仓库（含 `benchmark/`、`ncrust-api/`）搜
   `weblog` / `postWeblog` / `clientlogusf` / `reportPlay`，**提交态**命中全部落在
   `player/PlayReporter.kt` 与 `network/RetrofitClient.postWeblog`，**没有任何 QQ 对应物**
   （工作区里新增的 `player/ReportGate.kt` 是闸门本身，不是 QQ 上报实现，见 §3.6）。
2. **QQ 侧刻意不做上报，而且写进了 KDoc**：`qq/QqMusicSourceProvider.kt:40`
   「不把 QQ 的播放行为上报给任何一方（QQ 侧没有对应的 webLog 机制，也不该伪造）」；
   同文件 `:26` 也写明 QQ 侧「没有云歌单/收藏/播放上报的对应物」。
3. **QQ 侧的网络出口只有「取数据」这几类**：`QqClient.MUSICU_URL =
   https://u.y.qq.com/cgi-bin/musicu.fcg`（`qq/QqClient.kt:59`）承载搜索 / vkey / 歌词 /
   歌单 / 登录；`QqCatalog.LEGACY_HOST = https://c.y.qq.com`（`qq/QqCatalog.kt:90`）承载旧版搜索。
   **没有一条是「把播放行为写回去」**。
4. **QQ 侧的埋点全是本地计数**：`qq/QqProbeCounters` + `qq/QqProbeStore`
   （`QqProbeStore.kt:37-88`，prefs `ncrust_qq_probe`，`apply()` 落盘，无网络类型引用）——
   这正是本报告 §3 建议照抄的落盘范式。
5. 反过来说，QQ 侧确实会收到**用户的搜索词**与**曲目 mid**（那是数据来源本身），
   但那不是播放行为上报，也不含 ncm id；ncm 的数字 id 发到 QQ 没有任何意义，
   代码里也没有任何路径会这么发（取词/取链都按音源路由：`source/TrackKey.kt:105-109` 用 bit62 判源）。

---

## §3 闸门设计建议

### 3.1 判据：用纯函数，且只认结构性事实

**用 `SourceIds.isQqId(id)`**（`source/MusicSource.kt:167`，即 `(id and (1L shl 62)) != 0L`），
封装成一个纯函数，例如：

```kotlin
// 纯逻辑、无 Android 依赖、可 JVM 单测
fun mayReport(target: Target, songId: Long): Boolean =
    songId > 0L && (target == Target.NETEASE_WEBLOG) != SourceIds.isQqId(songId)
```

- ✅ **bit62 是结构性事实**：全部 QQ 曲目的 id 都由 `SourceIds.qqId()` 产出、bit62 恒置位；
  ncm id 是百万~十亿量级，永远触不到 2^62（`MusicSource.kt:147-163`）。
- ❌ **不要用 id 区间启发式**：QQ 裸 songid 与 ncm id 都是 9~10 位十进制，区间完全重叠
  （实测与论证见 `docs/verification/v2.5.4/probe-search-history.md:318`、`:327`）。
- ❌ **不要用散列反推**：`qqId` 在服务端没给 songid 时走 FNV 散列兜底，不可逆（`MusicSource.kt:198-201`）。
- ❌ **不要用 `currentTrack.source` 之类的旁路字段做主判据**：它是可变状态，
  v2.1.5 的跨源串台教训就是「id 更新了、音源字段留在上一首」。id 自带标志位，不需要第二份真相。
- ✅ 顺带覆盖 `songId <= 0`（脏数据），与既有 `songId > 0` 卫语句同向。

### 3.2 行为：本源照常、非本源不上报

- `NETEASE_WEBLOG` 目标：`isQqId(id) == false` ⇒ 行为与今天**逐字节不变**（同一 URL、同一字段、同一线程模型）。
- `isQqId(id) == true` ⇒ **在第 47 行那三条卫语句之前 return**（连 cookie 都不必读），不发请求、不起线程。
- 建议同时在 `Log.i` 打一条**可判定**的日志（`weblog blocked: songId=… reason=cross-source:qqmusic->NETEASE_WEBLOG`）：
  这是「修好了没有」的唯一真机可观测信号（见 §3.5 的验收方法）。

### 3.3 拦截计数落盘：照 `QqProbeStore` 的做法

| 要求 | 做法 |
|---|---|
| 落哪儿 | **单开一个 prefs 文件**（不要塞进 `ncrust_offline`，它会随「清空离线缓存」被清掉；也不要塞 `ncrust_settings`）。参照 `QqProbeStore`：`PREFS = "ncrust_qq_probe"`、`KEY = "stats"` |
| 目录 | 只在应用私有目录（`Context.MODE_PRIVATE`），**没有任何网络出口**；评审时可以用一条 grep 自证（`QqFallbackStats.kt:46` 就是这么写的） |
| 关键路径上的开销 | **只做一次内存自增**（`AtomicLong.incrementAndGet` 级别）。不碰 IO、不碰网络、不分配、不加锁、不调度 |
| 什么时候落盘 | 只在 ① `MainActivity.onStop`（进程可能被杀）；② 诊断入口被点开时。参照 `MainActivity.kt:453` 对 `QqProbeStore.snapshotAndFlush` 的调用，以及 `QqMusicSourceProvider.kt:101` 的 `ensureSeeded` |
| 怎么写 | `prefs.edit().putString(...).apply()`（异步落盘、不阻塞调用线程），整段包 `try/catch` 并只打 `Log.w` |
| 迁移 | 新字段一律**可空 + 默认值**，读出来先过 `canonical()`（`null → 0`，语义 = 「没记到」）；加字段 = 加迁移逻辑 = 加单测 |
| 记什么 | 至少：被拦总数、其中「QQ id → ncm」的次数、放行并真的发出去的次数（做分母算拦截率）、首次/最后时间戳、schemaVersion |

### 3.4 落点层次：放在唯一网络出口，**不要**放进播放链路

- **落点 = `PlayReporter.reportPlay` 的第一行**（`player/` 包，fire-and-forget 组件）。
  理由：① 它是**唯一**的上报出口，调用点将来变多（自然播完 / 80% / 将来的 seek 上报）也不会漏；
  ② 它本来就在播放关键路径**之外** —— 失败只打日志（`PlayReporter.kt:12` 的既有承诺）；
  ③ 判据是纯函数，可单测。
- **不要**放在 `PlayerViewModel` 的两个调用点各判一次（两处口径会漂移，且下一个调用点会漏）。
- **不要**碰 `PlaybackService` / ExoPlayer / 队列 / 歌词：闸门与音频链路零耦合。
- **异常必须隔离**：闸门是纯位运算 + 一次原子自增，理论上不抛；即便如此也应与
  `QqProbeStore` 一致，把「计数/落盘」这一侧包在 `runCatching` / `try-catch` 里
  —— **统计是旁路，不是功能**。
- 顺序建议：**闸门 → 落盘计数 → 原有三条卫语句 → 构造 JSON → `thread{}` 发请求**。
  这样被拦的路径**连线程都不会创建**。

### 3.5 落地后怎么验收（可直接照抄）

同 §2.6 的复现步骤，在**加了闸门的包**上重跑，期望：

```
I/PlayReporter: weblog blocked: songId=4611686018784987997 reason=cross-source:qqmusic->NETEASE_WEBLOG
```

且 **不再出现** `D/PlayReporter: weblog resp: 200 duration=0/0`；
同时播一首 ncm 曲目，仍应出现 `weblog resp: 200 duration=<pos>/<dur>`（本源不受影响）。
另可读 `ncrust_report_gate` 的 `stats`（或实施时定的 prefs 名）核对被拦计数 ≥1。

### 3.6 ⚠️ 落地现状（**工作区里已经有实现了，且不是本探针写的**）

探针进行中（18:37），本仓库工作区出现了**另一条并行工作流**的未提交改动，方向与上面完全一致：

| 文件 | 状态 | 内容 |
|---|---|---|
| `app/src/main/java/…/player/ReportGate.kt` | 新增（未提交） | `object ReportGate`：`Target.{NETEASE_WEBLOG,QQ}`、`mayReport()`、`blockReason()`；判据正是 `SourceIds.isQqId`；`ReportGateCounters`（可空+默认值 + `canonical()`） |
| `app/src/main/java/…/player/ReportGateStore.kt` | 新增（未提交） | prefs **`ncrust_report_gate`** / key `stats`，`ensureSeeded` / `snapshotAndFlush`，`apply()` 落盘 |
| `app/src/main/java/…/player/PlayReporter.kt` | 已改（未提交） | 在 `reportPlay` **最外层**加闸门 + `onBlocked` 计数 + `Log.i("weblog blocked: …")`；`onReported()` 在线程内自增 |
| `app/src/main/java/…/MainActivity.kt` | 已改（未提交） | 与本探针无关（`TrayLayout` 托盘高度），**未看到**闸门相关的接线 |

**复核意见（给实施者，按重要性排序）**：

1. **`ReportGateStore` 的落盘目前是死代码**：截至 2026-09-26 18:39:42 复核，
   `ensureSeeded(...)` 与 `snapshotAndFlush(...)` 在整个 `app/` 下**没有任何调用方**
   （grep 只命中定义处与 KDoc；`UserScreen.kt:374` 那一处是 `QqProbeStore`，不是它），
   也没有 `MainActivity.onStop` / 诊断入口的接线 —— 也就是说计数**只会留在内存里、随进程消失**。
   照 `QqProbeStore` 的既有做法补两处：`ensureSeeded`（进程内首次触达，
   可放 `MainActivity.onCreate` 或 `AppWarmup`）与 `snapshotAndFlush`（`MainActivity.onStop`，
   参照 `MainActivity.kt:453` 对 `QqProbeStore` 的那一行）。
2. **`ReportGate` 没有单测**：`app/src/test/java/…/player/` 下没有 `ReportGateTest.kt`，
   而 `ReportGate` 的 KDoc 自己写着「判据是纯函数、可单测」。建议至少覆盖：
   `1L shl 62` 本身 / bit62+1 / 真实 QQ 合成 id（`4611686018784987997`）/ ncm id（`557920`）/
   `0` / 负数 / `Long.MAX_VALUE`，以及两个方向（`NETEASE_WEBLOG` 与 `QQ`）的对称性。
3. **闸门位置正确**（最外层、在 cookie 判断之前）：被拦路径不读 cookie、不起线程，符合 §3.4。
4. `Log.i` 被拦日志建议保留 INFO 级（不要降成 DEBUG）：它是线上唯一能证明「闸门在工作」的信号。

> 说明：本探针**没有**修改这些文件，也不对它们的最终形态负责；上面只是按 §3 的口径做的
> 一致性复核。行号引用见 §2 的各处，全部指向**提交态 v2.5.4** 的源码。

---

## §4 未验证项

| # | 未验证的事 | 卡在哪 | 影响 |
|---|---|---|---|
| 1 | **上报报文里 `id` 字段的字节级内容** | 设备 TLS，未装 MITM 根证书（targetSdk 36 不信任用户 CA），也没做系统 CA 注入 | 目前的结论是「设备侧时机证据（`duration=0/0` 指纹 + 队列推进顺序）+ `.put("id", songId)` 无分支数据流」。要闭环只能上 MITM |
| 2 | **ncm 服务端拿到这个 id 之后做什么**（丢弃 / 当成不存在的歌记账 / 污染推荐画像） | 客户端无法观测 | 与 v2.5.4 探针 §12.3 的遗留项相同。这决定了泄露的**实际后果**，不影响「该不该加闸门」 |
| 3 | **80% 进度路径喂 QQ id 的真机证据** | 探针期间应用被另一进程反复 `force-stop`（18:35:48 起）；那首 QQ 曲目又卡在 `BUFFERING`（position 165461 不动） | 代码事实确定（`PlayerViewModel.kt:561-565`），JVM 复算已给；缺的是一次真机日志 |
| 4 | **上报频率 / 占比 / 拦截率** | 没有做频次统计，也不拿单次样本外推 | **本报告不提供任何频率数字**（不编造） |
| 5 | **S6（Android 7）上的复现** | 该机落盘当前曲是 ncm；要造 QQ 当前曲必须写应用持久化状态，按「尽量只读」原则没做 | 影响面：低（同一份代码、同一份 apk 版本） |
| 6 | **`action=next` 之外的媒体键路径**（`cmd media_session dispatch next`） | Android 16 + media3 上没有落到 `onPlayerCommandRequest`，应用侧无日志 | 只是触发手段的差异，不改变结论（`onPlaybackEnded` 的三个发射点是同一段代码路径） |
| 7 | 华为控制中心卡片 | **本探针完全没碰**（铁律） | — |

---

## §5 产出与原始证据清单

| 文件 | 内容 |
|---|---|
| `docs/verification/v2.5.5/probe-playreporter.md` | 本报告 |
| `probe-raw/playreporter/device-evidence.md` | 真机复现步骤 + 证据链 + 干扰记录 + 干扰来源分析 |
| `probe-raw/playreporter/pcl110-logcat-raw.txt.gz` | PCL110 全量 logcat（18:33:11–18:37:2x，2.4 MB，`gzip -t` 通过） |
| `probe-raw/playreporter/pcl110-app-log-excerpt.txt` | 同一捕获里只保留应用 tag 的行（未编辑、未重排） |
| `probe-raw/playreporter/pcl110-prefs-evidence.txt` | 探针前落盘状态（只摘身份字段；**登录凭证未采集**） |
| `probe-raw/playreporter/bit62_guard_probe.py` / `.out` | Python 复算：bit62 十进制/符号/`>0`、三条卫语句、`reachedCompletion` 边界 |
| `probe-raw/playreporter/GuardProbe.java` / `.out` | 同上，跑在真实 JVM 的 `long` / `float` 语义上（`java GuardProbe.java`） |
| `probe-raw/playreporter/code-excerpts.md` | 相关源码原文（真实行号），含 `PlayReporter` 全文、`SourceIds`、两个调用点、ticker、`onPlaybackEnded` 发射点、`QqSongMapper:94`、`QqProbeStore` 全文 |
