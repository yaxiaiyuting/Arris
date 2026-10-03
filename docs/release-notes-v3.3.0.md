# Ncrust v3.3.0-gpl

**versionCode 60** ｜ 基于 v3.2.4-gpl ｜ 全量单测 **2477 用例 / 0 失败 / 184 个测试类** ｜ `lintDebug` 与 `lintVitalRelease` 零 error

这一版处理了 10 条用户反馈与建议。下面逐条写清**做了什么**、**根因是什么**，
以及**哪些没有验证**——最后一部分请务必看完再判断能不能用。

---

## 用户反馈的 6 条问题

### 1. 离线播放有点不太行 —— 两个独立缺陷（P0 + P1）

**P0：自动接续的歌永远进不了离线兜底。**
症状是「连播一张歌单后断网，只有当初手点的那几首能放，自动接续的下一首全『暂时无法播放』，
可设置→离线缓存管理里它们都在」。

根因：URL 清单的唯一写入点是 `playUrl`，而**无缝接续**走的是
`preload_next` → `onMediaItemTransition`，全程不经过它。于是那些歌的音频字节**已经落盘**、
离线曲目索引**也已经写了**，**唯独 URL 清单是空的** —— 而离线兜底要求两个清单同时命中。
管理页看得到它们，是因为那一页读的是曲目索引，两个清单的口径不同步。
→ 在槽位守卫之后补记「真正起播那一项」的 URL。

**P1：「有任何片段」被当成「能从头播」。**
旧判据只问「缓存里有没有这个 key 的片段」，而播放器是**从 position 0 读**的。
缓存只有中段时（seek 过去听过、或上次缓冲到一半就切歌）它会放行 ⇒
起播正常、播到洞的位置**突然卡死**再弹降级。比直接说放不了更糟，因为它先给了承诺。
→ 判据换成「position 0 是否被连续覆盖」（`coversStart`，纯函数 + 11 条单测）。
允许缓存不完整（本应用没有下载功能），但要求**从 0 起连续**。

### 2. 用歌词搜索歌曲

已实现，做成**两路原生聚合**（不是一个音源的能力之和拼凑）：

| 音源 | 能力 | 实测 |
|---|---|---|
| ncm | `cloudsearch/pc` 的 `type=1006` | 搜「让我掉下眼泪的」→ 60 条，首条《成都》- 赵雷 |
| qm | 旧版 `client_search_cp` 的 `t=7` | 条目在 `data.lyric.list`，首条同为《成都》- 赵雷 |
| B 站 | **没有这个能力** | 见下面第 7 条的实测说明 |

搜索页新增「歌词」tab（挨着「单曲」，因为它返回的也是单曲、复用同一套卡片与筛选）。

> 另：你澄清过「不是听歌识曲」——那个需要第三方识别服务的 key，本轮不做。

### 3. 60 帧设备上还是抖动（P1）

v3.2.4 刚做过帧率适配，而那条改动**在 60Hz 上等于没改**（非低内存设备帧步长恒为 1），
所以抖动来自另一处：v3.2.3 引入的亚格插值。

根因：`sinceBarMs` 在**每根音频柱到达时被清零**。可见位置 = 已到达格数 + 相位，
清零让那一帧的位移多出 `1 − 相位`，最高 **2.1×** 名义速度；
而 `coerceIn(0f, 1f)` 又把「迟到」压成停顿、下一帧补回来 ——
观感就是每 ~100ms 一次的「顿-冲」，**与刷新率、与帧步长都无关**。

修法：把「平移格数」与「小数位置」交给**同一个时钟** —— 相位每跨过一个整数就平移一格历史窗口。
`x` 与 `x−1` 的小数部分相同 ⇒ 逐帧位移恒为 `dt/间隔`。

逐帧诊断实测：步长**唯一取值 0.1667**，平均 0.16666673（名义 0.16666667，偏差 4.4e-6）⇒ **零抖动**。
覆盖 7 组输入（柱间隔 100 / 92.88 / 85ms、±3ms 抖动、60 与 120Hz）× 1400 帧。

顺带修掉两处被这次调查暴露的既有缺陷：柱间隔估计与相位耦合（会把 100ms 算成 140ms、
画面越滚越慢）、静音段只冻结了相位而没冻结平移。

### 4. 桌面上的播放卡片

判定与第 8 条是**同一交付物**（本仓库自称播放器为「卡片」），详见下面第 8 条。

### 5. 设置里「后台播放」点不动

**真根因不是回调失效** —— 实测点它确实拉起了系统电池优化授权页，点「允许」后
`dumpsys deviceidle whitelist` 从「不在白名单」变成 `user,com.takahashirinta.ncrust,10183`：
**功能是好的**。

坏的是**反馈**：应用已在白名单内时（首启弹窗引导过就是常态）系统页启动即 finish、
屏幕**零变化**；而这一行既没有开关也不显示状态，失败还被静默吞掉
—— 用户只能判定「点不动」。

现在：① 两态回显（已允许 / 未允许，措辞描述的是**权限状态**而不是「后台播放开关」——
本应用的后台播放是恒开的）；② 已允许时改跳电池优化列表页并给一句提示；
③ 两条路都失败时给可见提示。

**模拟器 A/B 双向验证**：白名单内 → 显示「已允许后台运行」；移除后 → 「未允许，点按前往系统设置」。

### 6. 账号持久性：要反复退出登录再登录才能播 VIP / 高音质

这是**概率性**症状，排查出三条独立机制，都修了：

**(a) 登录态靠 `onPageFinished` 一次性快照抓取 —— 头号机制。**
ncm 登录页是 `music.163.com/#/login` 的 **hash 路由 SPA**：登录成功后的跳转
**不产生新的文档级导航**，因此经常**不触发** `onPageFinished`。
抓取时机是否落在「cookie 已写入且回调恰好到来」这个窗口里，每次重登都是独立掷骰子，
命中次数服从几何分布 —— 那正是「要试很多次才偶尔成功」的形状。
→ 改成**轮询 cookie**，与同仓库 **QQ 侧早已采用**的做法一致（那条纪律在 QQ 那边写了很久，
ncm 这条一直没跟上）。上限 150 次 × 1s，有界。

**(b) cookie 覆盖写 + 判据过弱。**
三条登录路径写入形状不同（WebView 整串 / 扫码只回增量 / 局域网回传），覆盖写会丢掉先到的键。
判据只有 `contains("MUSIC_U=")` ⇒ 缺 `__csrf` 也算「登录成功」，
而缺 `__csrf` 会让**所有写操作 403**（收藏、歌单增删改）。
→ 改合并写入 + 判据要求 `MUSIC_U` **且** `__csrf`，并拒绝用半截会话覆盖完整会话。

**(c) 取链身份被用户 cookie 的同名键压制。**
`extraCookieFor` 原本是「用户有同名键就跳过」。而服务端的**音质上限恰恰按这几个键判定**：
缺 `os`/`appver` 时 hires / 母带会被**静默回落**成 lossless，而 `code` 仍是 200。
于是「cookie 里有个空的 `appver=`」= **永久失去高清**，客户端看不出任何异常。
→ 改成按我们的值覆盖身份字段（保留键名与位置），`MUSIC_U` / `__csrf` 一个字节不动；
`deviceId` 是刻意例外（它绑定服务端认识的会话，只在用户没有时才补）。

> 顺带修一个我自己在重构时引入又抓到的回归：把 SharedPreferences 的键名
> `client_device_id` 误当成 cookie 字段名发出去，服务端不认 ⇒ 设备指纹静默失效。
> 已加一条永久守卫用例。

### 7. B 站音源歌词问题

你补充的线索（「B 站的歌大多没有对应字幕」）指向了正确方向。实测后确认：
**v3.1.0 的「视频音轨没有歌词数据源」这条结论是错的** —— 漏掉的是**字幕**。

实测（登录态）：

| 事实 | 实测值 |
|---|---|
| `player/wbi/v2` 能列出字幕 | 是，且**只需 `bvid` + `cid`**（不必先问 `view`） |
| 匿名能否拿到字幕 | **不能**，列表恒为空 —— **这就是你观察到的「大多没有字幕」** |
| 字幕来源 | 绝大多数是 **`ai-zh`（B 站 AI 自动生成）**，不是 UP 主手传的 CC 字幕 |
| 正文里有歌词吗 | **有，带时间轴的逐句歌词** |
| 抽样覆盖率 | 12 条音乐视频：**8 条有字幕**，其中 **5 条判定为歌词** |

真实样本（赵雷《成都》官方 MV）：
```
[  7.94-  8.96] 昨晚他来了吗                    ← music = 0.0（MV 开头的人声对白）
[ 59.46- 66.84] ♪ 让我掉下眼泪的不止昨夜的酒 ♪   ← music ≈ 1.0（歌词）
```

判据用服务端的 **`music` 字段**（结构化置信度）而不是「有没有 ♪ 符号」，
`♪` 只作兜底。这个区分是必要的：音乐视频的字幕**不只有歌词**，MV 有开场对白、
合集视频有主持人串场 —— 把它们当歌词会让时间轴整体错位。
过滤按**比例**而不是绝对条数（一首歌 40~60 条，而「144 首金曲合集」实测有 6997 条）。

**同时修掉另一半**：旧实现把「网络失败/风控」与「这首确实没有词」**折叠成同一个 null**，
然后一律标记为「确无歌词」⇒ 歌词按钮**永久置灰、点不动、无法重试**。
现在用显式的三义结果分开：取不到 ⇒ 可重试的失败（按钮保持可点）；确实没有 ⇒ 稳定空态。

**还有一条你可能没注意到的**：所有音源共用的歌词解析器有一个 P0 缺陷 ——
LRC 允许一行挂多个时间戳（副歌重复），而旧实现**只取第一个**，
把后面的 `[02:44.32]` 当成歌词正文。结果是：**副歌在 02:44 / 03:15 / 03:50 / 04:47 整段消失，
且屏上出现方括号字面量**。这不是 B 站独有 —— ncm 与 QQ 的歌词走同一个函数。
真实样本 22 行里 13 行是多戳、44 个时间点里丢了 21 个。已修并补 13 条单测（此前**没有**这个解析器的测试）。

### 8. 安卓桌面小组件的适配

已实现 **App Widget**（同时也回答了第 4 条「桌面播放卡片」——排查后确认是同一件事）。

- **单 provider + 运行时按尺寸换布局**，三档：小（2×1）/ 中（4×1）/ 大（4×2）。
- 选 `RemoteViews` 而不是 `Glance`：后者要求 Kotlin 2.x（会牵动全仓库版本），
  而且**同样没有 Canvas**，救不回波形这个卖点。
- **推送节流是硬约束**：只在「播放/暂停切换、切歌、整数秒变化、尺寸变化、封面有无变化」时推，
  **暂停时一律不推**。桌面没有卡片时一次 Binder 都不发。
- 封面先压到卡片像素尺寸再跨进程传（Binder 事务约 1MB 硬限）。

**波形在小组件上不做** —— `RemoteViews` 是「快照 + apply」，宿主不跑我们的代码，没有自绘通道。
这是一个物理限制，不是没做。

### 9. 歌词复制 / 分享 / 生成图片

- **复制**：默认不带时间戳（粘到聊天/备忘录时它是噪声），但弹层里有开关
  ——「导出 LRC 片段」是真实存在的第二用途。带歌名/歌手出处（没有出处的歌词粘出去无法回溯）。
- **出图**：1080×1920。用 `Canvas` + `StaticLayout` 手绘（零新依赖、API 24 稳）；
  字号 10 档自适应，最小档仍放不下就从**尾部**丢行，**永远保住当前行**。
  **三级降级**：带封面 → 无封面 → 720×1280 → 放弃出图并自动改为分享纯文本。
- 分享走 `FileProvider`。**API ≤28 的「保存到相册」不显示**（写相册需要存储权限，
  而本版清单预算只允许加 FileProvider）—— 这是**有意隐藏**，不是失败后才提示。

### 10. 播放统计页面

- **入口**：底部导航新增第 5 个 tab「播放统计」。
- **「有效收听时长」= 播放器位置的正向增量**，绝不拿歌曲时长当收听时长。
  用「增量 vs 墙上时钟」挡 seek 前跳（而不是固定阈值）—— 所以主线程卡顿后的一次补偿仍照记。
  暂停停表、拖动不计入、单曲循环分别计、**被系统强杀最多丢 30 秒**（有界，页面上写明了）。
- **维度**：各平台 / 单曲 Top 20 / 按天。
  **「曲风 / 语种」如实不做**：接口不下发这两个字段（已核对），页面里因此**不显示空维度**，
  只在「统计口径」里说明原因。「本地歌曲」也不是独立平台（本地歌单是本地保存的歌单定义，
  曲目本身仍来自各音源）。

---

## 你额外建议的一条（已实现）

**离线缓存的歌在库里单独做一个界面，可以直接点击播放。**

之前离线歌只能在「设置 → 存储与缓存 → 离线缓存管理」看到，而那里是**管理**入口，
**行点不动** —— 一个管着「能离线听的歌」的界面里，行点不动确实不合理。

现在：
- 库页新增第 4 个 tab「**离线**」（与单曲 / 歌单 / 专辑并列）；
- **点一行就播这一首**（走与任意列表点歌完全相同的链路）；
- 正在播放的那一行锁住删除（删掉正在读的缓存片段会让播放回源网络，断网即报错）；
- 设置页的全屏管理弹窗**保留**（看占用、调上限、删曲目），两个宿主共用**同一套**
  读取、对账与点播语义 —— 不复制任何一段判断，避免「库里能播、设置里不能删」这类漂移。

---

## 验证到什么程度（**请务必看这一节**）

### 已验证

- **全量单测 2477 用例 / 0 失败 / 184 个测试类**；`lintDebug` 与 `lintVitalRelease` 零 error。
- **release APK 在 API 33 模拟器上安装、冷启、无崩溃、无 E 级日志**（PSS 111 MB）。
- **新功能逐个用 UI 自动化确认渲染**：底部导航 5 项、库页 4 个 tab、
  离线 tab 空态文案、统计页完整文案、搜索页「歌词」tab。
- **需求 5 做了双向 A/B**（白名单内 / 移除后），两态文案与 `dumpsys` 实测状态一致，
  实验后设备已还原。
- **抖动修复有可复现的量化判据**：逐帧位移唯一取值、平均速度偏差 4.4e-6。
- release 产物的 `versionCode=60` / `versionName=3.3.0-gpl` 与签名 DN 均实测确认；
  R8 mapping 已归档（`docs/verification/v3.3.0/`）。

### **未验证**（不要当成已实现）

- **小组件的真机渲染**：三档排版观感、`Chronometer` 在各家桌面是否真的自走秒、
  进度条在 OEM 桌面的表现、`previewLayout` 在 API 31+ 选择器的效果 —— 全部未验证。
- **冷启动时点小组件按钮**（应用与服务都没起）走 `PendingIntent.getService`，
  Android 8+ 的后台服务启动限制可能拒绝它。这是本版**最需要实测的假设**。
  ⚠️ 若失败**不要**简单改成 `getForegroundService` —— 在空播放列表上它不会触发
  `startForeground`，会变成前台服务超时崩溃。
- **分享出图的实际观感**（字体度量、行距、封面裁切、对比度）、FileProvider 授权链路、
  `ACTION_SEND` 选择器、剪贴板在 Android 13+ 的系统气泡、真实的 OOM 降级路径 ——
  只有代码与单测。
- **统计采集端的运行时行为一次没跑过**（它依赖 Android 与 ViewModel，无法 JVM 单测）。
- **B 站字幕链路的端到端**：判据与解析有真实样本的单测，但「点开某个 MV 看到歌词上屏」
  这一步没有在设备上走过（模拟器未登录 B 站）。
- **QQ 歌词搜索的实际返回**：接口实测可用，但应用内的端到端未验证（模拟器未登录 QQ）。
- **本版没有任何 release 包性能数据** —— 统计页与小组件都没有做帧时间声明，
  也不打算把 debug 包的数字当结论。

---

## English summary

Ncrust v3.3.0 (`versionCode 60`) addresses ten user reports and suggestions.

**Fixes.** (1) Offline playback: gapless auto-advanced tracks were never recorded in the
offline URL list (only `playUrl` wrote it), and "any cached fragment" was treated as
"playable from the start" — the latter now requires contiguous coverage from position 0.
(3) 60 Hz jitter: the sub-cell interpolation phase was zeroed on every audio-bar arrival,
producing a 0.5×–2.1× per-frame velocity spike every ~100 ms. The phase is now the single
clock that drives both window shifting and fractional position; measured per-frame
displacement deviation is 4.4e-6. (5) The "background run" settings row worked but gave no
feedback — it now shows a two-state indicator and no longer silently swallows failures.
(6) VIP/quality flakiness: login state was captured from a single `onPageFinished` callback
on a hash-routed SPA (now polled, matching the QQ path), cookies were overwritten instead of
merged with too weak a completeness check, and client identity fields were skipped whenever
the user cookie already had a same-named key (silently capping quality at lossless).
(7) Bilibili video tracks do have a lyrics source — subtitles (`ai-zh`), available only when
logged in. Also fixed a shared LRC parser bug where repeated-chorus lines with multiple
timestamps lost every timestamp but the first, dropping whole choruses across all sources.

**Features.** (2) Lyric search, aggregating ncm `type=1006` and QQ `t=7` natively
(Bilibili has no such capability — verified). (4)(8) An App Widget for the desktop player
card, single provider with three runtime size buckets. (9) Lyric copy / share / poster
image generation with a three-level degradation chain. (10) A playback statistics page with
an explicit "effective listening time" definition. Plus, per user suggestion, offline
tracks now have their own library tab and can be played by tapping a row.

**Testing.** 2477 unit tests across 184 classes, all passing; `lintDebug` and
`lintVitalRelease` clean. Verified on an API 33 emulator: installs, cold-starts, no crashes;
all new surfaces render; the battery-whitelist row verified in both states against
`dumpsys deviceidle whitelist`.

**Not verified.** Widget rendering on real launchers (and the cold-start
`getService` background-start exemption — the riskiest assumption here), share-poster
visuals and the FileProvider chain, end-to-end Bilibili subtitle playback and QQ lyric
search (no logins on the test device), and the statistics recorder's runtime behaviour.
No release-build performance numbers are claimed.
