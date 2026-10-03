# Ncrust v3.4.0-gpl

**versionCode 62** ｜ 全量单测 **2538 用例 / 0 失败 / 191 个测试类** ｜ `lintDebug` 零 error

这一版做了一次**全仓库品牌改名**、新增**专辑页音源角标与筛选**,并修了几处真实缺陷。
下面区分「已确认」「未确认」——最后两节请务必看完。

---

## 一、品牌改名:网易云 → `ncm`,QQ 音乐 → `qm`

按你的要求,全仓库(含代码、注释、文档、release 说明)一起改,共 **621 个文件 / 6348 处**。

**一条红线守住了**:`MusicSource` 里的 `"netease"` / `"qqmusic"` 是**落盘契约** ——
你的收藏库、播放队列、离线索引、搜索结果缓存里存的就是这两个字符串。
改掉它们**不会报错**,只会让所有老数据认不出来(收藏清空、队列失效、离线失效)。
所以本次只动**展示字样**,两个取值一个字节未改。每一轮都用
`grep -c '"netease"' MusicSource.kt` 前后对比自证(恒为 1),并额外做了
**264 条字符串字面量逐条比对**(0 处变化)。

**有一处边界你要知道**:搜索页的「网易云古典榜 / 网易云电音榜」这类**榜单名称**
来自服务端接口(`/eapi/toplist`),**不是应用内的文案** —— 客户端改名改不了服务端下发的内容。
类似地,B 站音源里 UP 主自己写的标题、歌单名也保持原样。

---

## 二、专辑页音源角标 + 筛选(你的需求)

- 专辑卡片与专辑详情显示音源角标(与搜索结果页同一套样式与文案)。
- 专辑列表支持「全部 / 只看 ncm / 只看 qm」筛选;筛选档**常驻**,不会因为筛完是空列表
  就一起消失(那样你就没有路径点回「全部」了)。

**一个必须如实说的技术发现**:收藏专辑**不能**用 `albumId` 反推音源 ——
收藏这一侧存的是**服务端给的裸数字 albumID**,两源编号量级相同
(陈奕迅《What's Going On...?》:QQ `22276` vs ncm `6451`),没有任何标志位可区分,
拿它反推会把 qm 专辑说成 ncm。所以改用 `mid`(albumMID 只可能来自 QQ)+ 标志位判定,
**判不了时角标返回 null ⇒ 不画角标、不显示「未知」、绝不回落成 ncm**,
筛选里未知源只在「全部」档出现(归到某一源就是替你下结论)。

**同样如实说**:目前**没有任何代码路径能把 QQ 专辑写进收藏表**,所以真机上每张收藏专辑
都会显示 `ncm`,「只看 qm」今天必然为空(那时显示的是指名档位的空态,不是「暂无收藏专辑」)。
这个功能今天是**正确但前瞻**的 —— 等 QQ 专辑能收藏时自动生效。

---

## 三、修掉的真实缺陷

### 1. 「点某一首歌」的静默失败分支

`playSongItem` 在队列装配失败时会走 `if (idx >= 0) playFromQueue(idx)` 的**静默 return** ——
这是整条链上**唯一**「点了什么都不发生、连 `playSong` 日志都不打」的分支。
现在抽成纯函数(`QueueKeys.planPlayItem`,15 例单测)并兜底成「追加队尾并播它」+ warning 日志。

同时给两处**以前完全静默**的结果丢弃加了日志:
`fetch result DISCARDED (superseded)`、`preload result DROPPED (stale, no takeover)`。

### 2. 取链慢:连接预热从未预热到业务连接

`ConnectionWarmup` **从未预热到业务真正用的连接**,两个缺陷缺一不可:
① 它自己 `build()` 了一个 client ⇒ 自有连接池,而业务走 `plainClient`(eapi,含取链)
与 `restClient`(REST,含歌词);② **光共享池子还不够** ——
OkHttp 的 `Address` 比较含 `sslSocketFactory`/`certificatePinner`,而 `build()` 会给
每个 client 各造一份 ⇒ 两个各自 build 的 client `Address` 永不相等。
另外预热原来排在 8s 预算的首页预取**之后**,最晚冷启动后 8 秒才跑。

**同口径实测(真实 host,取链那条通,n=5)**:业务请求「真的重连」**5/5 → 0/5**,
`conn+queue` 中位数 **139 ms → 1 ms**。设备侧这笔代价的量级是 **148~205 ms/host**。

⚠️ 但我要如实说:**这条缺陷是 v3.1.0 时代就存在的,不是 v3.3.0 的回归**。
你的「冷启动性能」假设**没有得到数据支持** —— 冷启动本身只有 1271 ms,
而这笔建连代价**不是冷启动独有**(连接池空闲 5 分钟后每次 host 首通都要再付一次)。

### 3. B 站视频轨歌词取不到(v3.3.0 引入,本版修正)

v3.3.0 我实现了「视频字幕当歌词」,但**一次请求都没发过** ——
视频搜索接口不返回 `cid`,而取词那条路写成 `payload.cid ?: return null`
(取流那条路早就会补问一次)。现在补齐,并用 8 条端到端测试钉住
「请求到底发出去了没有、cid 有没有被补问出来」。

### 4. 其它

- 歌词一行多时间戳被截断(副歌整段丢失 + 屏上出现方括号)—— 影响**所有音源**。
- 无缝接续的歌进不了离线兜底;「有片段」被当成「能从头播」。
- 账号持久性:登录态改为轮询 cookie(原来是等 `onPageFinished`,在 hash 路由 SPA 上经常不触发)。

---

## 四、⚠️ 未确认 / 未解决(**请不要当成已修好**)

### 1. 你报的「点其他歌不切换」—— **未定位,未复现**

我在模拟器上做了 9/9 连点实测(含快路径、回跳、重复点同一首),每次都
`playSong` + `Playing:` 都在,`media_session` 元数据每次跟着换 ⇒ **不可复现**。
**我没有编一个看起来合理的根因。** 修掉的是上面那条静默分支(它是链上唯一
「连日志都没有」的位置),但**它是否就是你的原因,我不知道**。

你补充的「毫无反应,歌照旧继续唱」+「所有页面都有」很有价值 ——
它排除了取链失败路径(那条会弹提示 + 暂停)。**如果这版还有,请抓这段:**

```bash
adb logcat -s NcrustTrack MainActivity PlaybackService
```

判据是二分的:有 `playSong -> currentTrack=…` 但**没有** `Playing:` ⇒ 取链之后断了;
**连 `playSong` 都没有** ⇒ 点击没到达播放链;多出 `DISCARDED`/`DROPPED` ⇒ 就是新加的那两条日志抓到的静默丢弃。

### 2. 你报的「歌词刷不出来 / 等好久」—— **没有解释,没有修**

性能那一路在真机上实测歌词是**持久缓存命中、0~1 ms**。它另外看到一个更可能的机制:
AMLL/TTML 镜像探测 `AmllTtml: 无此歌词 code=404` 出现在请求后 **+2.6 s**,
也就是一次**大概率可跳过的串行等待**。**未量化、未修。**

### 3. 波形抖动 —— **这一版我不为它背书**

我在这个组件上连续失败过 6 次(判据的测量口径错了三次,最后把 v3.3.0 的改动**整体回退**
到 v3.2.4 并在代码里留了教训)。本版又有一轮改动,但:
- 我没有在真机上量过「逐帧可见位移」;
- 判据的可信度取决于「量的到底是不是渲染层消费的那个量」,这一条我无法替你确认;
- **所以请你实测确认。** 如果还抖,请告诉我比 v3.3.1 更好还是更差 —— 那比我的仿真数字有用。

### 4. 其它未验证

- 专辑角标与筛选的**真机视觉**未验证(只做了编译 + 24 例纯逻辑单测);
- 模拟器上没有已收藏专辑,所以角标**在设备上没渲染出来过**(空态已验证);
- **本版没有任何 release 包性能数据**(铁律 16);模拟器用软件渲染,其帧时间不可作为真机结论。

---

## 五、顺带发现(未修,供你决定)

**冷启动时每个收藏库端点被发了两遍**:`user/playlist`、`playlist/detail`、
`song/detail`、`album/sublist` 各 2 次,其中两次 `playlist/detail` 的 TTFB 分别是
1020 ms 与 891 ms ⇒ **每次冷启动约 2.7 秒的重复网络**。
这是独立缺陷,不在本次范围内。

---

## 六、验收记录

- API 33 模拟器安装 release 包:**冷启 724 ms,无崩溃,无 E 级日志**;
- 逐项确认:库页 4 个 tab(单曲/歌单/专辑/**离线**)、离线空态文案、
  播放统计页(含「接口不提供曲风/语种字段,所以没有这两个维度」的如实说明)、
  设置页「后台运行」两态回显(重装后未加白名单 ⇒ 显示「未允许,点按前往系统设置」,
  与 `dumpsys deviceidle whitelist` 真实状态一致);
- 全量单测 2538 / 0 失败;`lintDebug` 零 error;R8 mapping 已归档。

---

## English summary

Ncrust **v3.4.0** (`versionCode 62`) renames the two sources across the entire repository
(621 files: 网易云/NetEase → `ncm`, QQ 音乐/QQ Music → `qm`) and adds per-source badges and
filtering to the album views.

**The rename deliberately leaves the persisted `"netease"`/`"qqmusic"` keys untouched** —
they are stored in saved songs, the playback queue, the offline index and search caches, so
changing them would silently invalidate all existing user data. Verified with a
field-by-field comparison of 264 string literals (0 changes).

**Album source detection**: saved albums store a *bare numeric* albumID from the server, so
inferring the source from it would mislabel QQ albums as ncm. The implementation uses the
album `mid` (QQ-only) plus flag bits, and renders **no badge at all** when it cannot decide —
it never falls back to guessing. Note that no code path currently saves QQ albums, so the
badge always reads `ncm` today; the feature is correct and forward-looking.

**Fixed**: a silent-failure branch in the click-to-play path (the only place that could do
nothing *without even logging*); connection warmup that never warmed the connection the
business path actually uses (measured: business-request reconnect 5/5 → 0/5, `conn+queue`
median 139 ms → 1 ms — but this is a v3.1.0-era defect, **not** the v3.3.0 regression, and the
"cold start" hypothesis was **not** supported by the data); and Bilibili video-track lyrics,
which v3.3.0 never even requested (missing cid resolution).

**Not fixed / not confirmed**: the reported "tapping another song doesn't switch" could not
be reproduced on the emulator (9/9 switches worked) — no root cause was invented for it. The
reported slow/blank lyrics are unexplained (lyrics measured as a 0–1 ms cache hit; a 2.6 s
AMLL/TTML mirror probe is the likelier mechanism, unquantified). The waveform jitter has a
fresh round of changes that **I do not vouch for** — please verify on a real device.

**Acceptance**: installs and cold-starts in 724 ms on an API 33 emulator with no crashes;
2538 unit tests pass; lint is clean; R8 mapping archived.
