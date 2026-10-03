# 真机复现记录（PlayReporter × QQ 合成 id）

设备：`3B15CD00GB700000` = PLC110 / Android 16 / KernelSU root
包：`com.takahashirinta.ncrust` **v2.5.4-gpl / versionCode 45**（release，已安装，非本仓库重新构建）
ncm 登录态：`ncrust_prefs.xml` 同时存在 `MUSIC_U` 与 `__csrf`（只检查了「键是否存在」，**未采集凭证值**）
QQ 登录态：`ncrust_qq_prefs.xml` 存在（`qq_cookie` / `qq_uid` / …，同样**未采集值**）
时间：2026-09-26 18:32 – 18:37（+08:00）

> ⚠️ 探针期间该设备同时被**另一个进程**反复 `force-stop`（见文末「干扰」），
> 因此只有 18:35:12 – 18:35:20 这一段窗口是干净可用的；80% 自然达成路径**未在真机上取到**。

---

## 1. 复现步骤（逐条可重放）

```bash
D=3B15CD00GB700000

# ① 探针前先看落盘状态：当前曲是不是 QQ 曲目（证据见 pcl110-prefs-evidence.txt）
adb -s $D shell "su -c 'cat /data/data/com.takahashirinta.ncrust/shared_prefs/ncrust_playback_state.xml'"

# ② 清空日志缓冲并开始抓取（探针全程只在读日志，不注入任何状态）
adb -s $D logcat -c
adb -s $D logcat -v time > pcl110-logcat-raw.txt &

# ③ 让「当前曲」确实是那首 QQ 曲目：进程被系统回收后重启即可（restore 路径会打印身份）
#    本次是进程自然重启后自动发生的；也可以 `am start -n com.takahashirinta.ncrust/.MainActivity`
#    日志证据：NcrustTrack: restore -> track=qqmusic:4611686018784987997 ...

# ④ 用应用自己支持的 "next" 命令触发 onPlaybackEnded（PlaybackService.kt:677）
adb -s $D shell "am startservice -n com.takahashirinta.ncrust/.player.PlaybackService --es action next"
```

④ 之所以能用：`PlaybackService.onStartCommand` 读的是 **extra** `action`
（`PlaybackService.kt:604-677`），`"next" -> onPlaybackEnded?.invoke()`。
这条分支与通知栏/媒体键的「下一首」走的是同一个 `onPlaybackEnded`
（媒体键 NEXT 的合并点在 `PlaybackService.kt:765-784` `onPlayerCommandRequest` → `APP_NEXT`）。
**它不是测试专用开关，是应用的真实命令入口。**

（尝试过但**没成功**的触发方式：`adb shell cmd media_session dispatch next`。
在 Android 16 + media3 会话上它没有落到 `onPlayerCommandRequest`，应用侧连
`NcrustMediaPanel: media button NEXT` 都没打。如实记录，不作为证据。）

---

## 2. 证据链（同一条链上每一步都有时间戳，全部来自上文的 logcat 原始捕获）

| # | 时间 | 日志（原文） | 说明 |
|---|---|---|---|
| 1 | 18:35:12.270 | `I/NcrustTrack(32055): restore -> track=qqmusic:4611686018784987997 mid=001A7neE3LB7Ki media=000g0rin0v8VU2 (sourceKey=qqmusic)` | 进程重启后**当前曲 = QQ 曲目**，`currentSongId.value = 4611686018784987997`（bit62 置位） |
| 2 | 18:35:12.817 | `I/NcrustTrack(32055): qq lyric APPLIED track=qqmusic:4611686018784987997 … lines=62` | 与 #1 同一身份（QQ 取词链路） |
| 3 | 18:35:19.999 | `D/PlaybackService(32055): onCreate` | 服务刚起来，**此前从未上报过本曲**（`lastReportedSongId = -1`） |
| 4 | 18:35:20.097 | `D/PlaybackService(32055): onStartCommand action=next` | 命中 `PlaybackService.kt:677` → `onPlaybackEnded?.invoke()` |
| 5 | 18:35:20.127 | `I/NcrustTrack(32055): playSong -> currentTrack=netease:503572 …` | **队列推进到下一首（ncm）**。发生在 #4 之后 —— 也就是说 #4 那一刻 `currentSongId` 仍是 QQ id |
| 6 | **18:35:20.390** | **`D/PlayReporter(32055): weblog resp: 200 duration=0/0`** | **上报真的发出去了，服务端返回 HTTP 200** |

### 为什么可以断定 #6 上报的是 **QQ id** 而不是随后的 ncm id

1. **读 id 的时刻在 #4，不在 #5。**
   `PlayerViewModel` 的 `onPlaybackEnded` 回调（`PlayerViewModel.kt:581-591`）先读
   `val sid = currentSongId.value`，再 `reportPlay(...)`，**最后**才 `onSongEndedCallback?.invoke()`
   —— 推进队列（#5）在 `reportPlay` 之后。同一个主线程顺序执行，没有竞态。
   `currentSongId` 上一次被写就是 #1（18:35:12），#5（18:35:20.127）才写 ncm id。
2. **`duration=0/0` 是这条分支的指纹。**
   另一个调用点（`PlayerViewModel.kt:562-564`，进度 ticker + `reachedCompletion`）**不可能**打出
   `duration=0/0`：`reachedCompletion` 的第一条就是 `durationMs > 0`（`PlayReporter.kt:82`）。
   而 `onPlaybackEnded` 分支传的是 `reportPlay(sid, duration.value, duration.value)`
   （`PlayerViewModel.kt:588`）—— 进程刚重启、`duration.value` 还是 0，于是正是 `0/0`。
   即：**这一行日志只可能来自「onPlaybackEnded + 当前曲是 ncm id 之前的那个身份」**。
3. `reportPlay` 内部把 `songId` 原样写进 JSON：`.put("id", songId)`（`PlayReporter.kt:57`），
   从形参到请求体**没有任何分支或改写**。

### 交叉印证（会话元数据）

同一时段 `adb shell dumpsys media_session`（18:33:50 直接输出，逐字复制）：

```
      package=com.takahashirinta.ncrust
      state=PlaybackState {state=PLAYING(3), position=153199, buffered position=164010, speed=1.0,
                           updated=68144201, actions=7339999, custom actions=[], active item id=0, error=null}
      metadata: size=11, description=所以我一等再等, 怪我太天真 · 苏谭谭, 怪我太天真
```

会话正在放的就是那首 QQ 曲目（`怪我太天真` / `苏谭谭`），与
`ncrust_playback_state.xml` 的 `song_source=qqmusic`、`song_id=4611686018784987997` 一致。

---

## 3. 这次复现**没有**证明的东西

| 项 | 状态 |
|---|---|
| `logs=` 表单里 `id` 字段的**字节级内容** | **未验证**。设备走 TLS，未安装 MITM 根证书（targetSdk 36 默认不信任用户 CA），未做抓包解密。目前是「设备侧时机证据 + 无分支数据流」的合力结论，不是报文级证据 |
| ncm 服务端拿到这个 id 之后做什么（丢弃 / 记账 / 报错） | **未验证**，客户端无法观测（与 v2.5.4 探针 §12.3 的遗留项一致） |
| 80% 进度路径喂 QQ id | **未在真机取到**（见「干扰」）。代码事实确定（`PlayerViewModel.kt:562-564`），JVM 复算见 `bit62_guard_probe.out` / `GuardProbe.out` |
| 老设备（S6 / Android 7） | **未复现**。该机落盘的当前曲是 ncm 曲目（`song_id=557920`）。要在它上面造出「当前曲是 QQ 曲目」，必须写应用的持久化状态 —— 本次探针按「尽量只读」原则**没有做**，也不排除会干扰同机上的其他探针 |
| 上报频率 / 占比 | **不提供**。没有做频次统计，也不拿单次样本外推 |

---

## 4. 干扰（如实记录）

18:35:48 起，本应用被反复杀死并重启（pid 32055 → 13412 → 32732 → 1571 → 1912 → 2139 → 2335 → 2588 → …），
系统日志给出的原因是**外部进程主动 stop**：

```
09-26 18:36:17.373 I/ActivityManager( 1742): Killing 2139:com.takahashirinta.ncrust/u0a347 (adj 0):
                                             stop com.takahashirinta.ncrust due to from pid 2632
09-26 18:36:34.317 I/ActivityManager( 1742): Killing 2335:com.takahashirinta.ncrust/u0a347 (adj 0):
                                             stop com.takahashirinta.ncrust due to from pid 3198
```

`from pid 2632/3198` 是**另一个 adb 进程**（本探针全程没有执行过 `am force-stop`，
也没有写任何 prefs）。crash buffer 为空，不是应用自己崩。
**最可能的来源**：同一仓库的另一条并行工作流正在同一台设备上做验证
（同期工作区里出现了未提交的 `player/ReportGate.kt`、`ReportGateStore.kt`,
以及 `MainActivity.kt` / `PlayerCard.kt` 的改动）—— 但本探针**不对其身份下结论**，
只记录「有外部进程在 stop 这个包」这一事实与时间线。
受此影响：后续想等一首歌自然到 80%（或先播起来再 seek 到 80%）的尝试全部被打断，
故「80% 路径 + QQ id」在真机上**没有取到**。这一段窗口（18:35:48 之后）的日志只作干扰记录，不作证据。

---

## 5. 原始文件清单

| 文件 | 内容 |
|---|---|
| `pcl110-logcat-raw.txt.gz` | 18:33:11 – 18:37:2x 的**全量** logcat（含系统噪声，2.4 MB）。`zcat … \| grep PlayReporter` 可复现第 2 节 |
| `pcl110-app-log-excerpt.txt` | 同一份捕获里只保留应用 tag 的行（未编辑、未重排） |
| `pcl110-prefs-evidence.txt` | 探针前落盘状态（只摘身份字段；**凭证未采集**） |
| `bit62_guard_probe.py` / `.out` | 位运算与卫语句的纯逻辑复算（Python） |
| `GuardProbe.java` / `.out` | 同上，但跑在真实 JVM 的 `long`/`float` 语义上 |
| `code-excerpts.md` | 相关源码原文（带真实行号） |
