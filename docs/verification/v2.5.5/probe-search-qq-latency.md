# 探针：聚合搜索 «晴天» 的 qm 链路延迟（v2.5.5）

- 设备：`PLC110`（`3B15CD00GB700000`，Android 16，KernelSU root，**已登录 ncm + QQ**）
  、`SM-G9209`（`0715f763f54c023a`，Android 7.0，Magisk root，已登录，**锁屏带 PIN，无法解锁 ⇒ 未参与 UI 实测**）
- 被测包：**v2.5.4-gpl / versionCode 45**（`dumpsys package` 实测，非 release 包重编译）
- 读的源码：**`10e9df9`**（v2.5.4 之后、v2.5.5 修复**之前**的状态）。
  ⚠️ 本文所有 `SearchViewModel.kt` 行号都指**这个提交**；探针期间该文件已被并行修复，
  现落在 `c74c6d8`（§7 对该提交做了复核），行号不再对应 —— 引用时请以 `git show 10e9df9:…` 为准。
- 探针原则：**不改 `app/` 源码**、不注入、不 hook。只读 app 自己**无条件**打的日志
  （release 包里 QQ 侧的 `Log.d` 全被 `BuildConfig.DEBUG` 关掉，唯一存活的是
  `SearchViewModel` 的 `Log.i`）＋ `screencap` 帧序列 + 宿主机直连接口探测。

---

## §0 结论先行

### 0.1 「约 5 秒」的来源归因（按贡献排序）

| # | 归因 | 结论 | 证据 |
|---|---|---|---|
| 1 | **串行编排（结构性）** | ✅ **成立，是「看到 0 首」的直接原因**：ncm 与 QQ **不是并发**，先 `await` ncm 并**立即发布**，再发 QQ。整体可见延迟是 **sum**（netease + qq），不是 max | `SearchViewModel.kt:131-136`（await ncm）→ `:167-171`（发布 + 关 loading）→ `:175-188`（才发 QQ） |
| 2 | **接口本身慢（主因）** | ✅ legacy 通道 `client_search_cp` **TTFB P50 1.72s / P95 2.99s**（n=30，同网段直连），DNS 6ms、TLS 213ms ⇒ **耗时几乎全在服务端**，不是本地处理 | 见 §2.1 原始数据 |
| 3 | **首次初始化** | ❌ **不成立**。搜索路径**完全没有** token/签名初始化：`ptqrtoken`/`hash33`/`g_tk` 只出现在**扫码登录**（`QqQrLogin.kt:224`），搜索链路一次都不用；冷启首搜 3 次 = 2117 / 4363 / 2295ms，热态 5 次 = 2034…2864ms，**分布重叠、无系统性差异** | §2.2 |
| 4 | **重试** | ⚠️ **没有重试，但有「第二条通道」**：`legacy` 拿不到/0 条时**再发一次** `musicu.fcg`（`QqApi.kt:68-81`）。两条都跑 ⇒ 串行两次 RTT。本次 15 次实测**全部第一条就成功**（宿主机 30/30 也是），所以**本次未观测到**这条路径贡献延迟 | `QqClient.kt:122-127`（不重试）、`QqApi.kt:68-81` |
| 5 | **映射/解析** | ❌ 不是瓶颈。`QqSongMapper` 是纯 `JSONObject` 遍历（30 条、无正则/无 IO），**上界 = QQ leg 实测 − 接口 TTFB ≤ ~0.3s**（未单独基准，见 §4） | `QqSongMapper.kt:52-142` |
| 6 | **超时预算把慢请求变成「永久 0 首」** | ⚠️ 硬预算 5s（`QQ_SEARCH_BUDGET_MS`）：QQ leg >5s 时**结果被整包丢弃**，UI 永远停在「qm 0 首」，**没有任何提示** | `SearchViewModel.kt:83, 179-181, 195` |

**一句话**：5s 不是某一次「慢」凑出来的，而是**「ncm leg（~1s）+ QQ legacy leg（1.7~3.0s，服务端慢）串行相加」**，
最慢一次实测 4363ms（P95 4147ms），再叠加冷启/弱网就会顶到 5s 预算上限；顶到上限时**结果被丢掉**，
用户看到的是「qm 0 首」这个**假事实**。

### 0.2 加载态现状：**QQ leg 没有任何加载态**

- `_isLoading.value = false` 在**ncm 到手那一刻**就执行（`SearchViewModel.kt:171`）；
  唯一的转圈是搜索框里那个 `MetroProgressIndicator`，由 `isLoading` 驱动（`SearchScreen.kt:201-204`）⇒ **QQ 还在路上时，界面已经完全没有「正在加载」的信号**。
- 更糟的一条：`songs.isEmpty() && !isLoading` ⇒ 显示「没有找到歌曲」（`SearchScreen.kt:428`）。
  即 **ncm 0 条 + QQ 未回** 时，界面会直接判「没有找到」——一个比「0 首」更硬的错误结论。
- QQ 超时/失败时**既不报错也不重试**：只有「两个源都空**且 ncm 报过错**」才写 `_error`（`:198-200`）。

### 0.3 结果合并方式：**在 ncm 结果之上整体「重排 + 替换」，不是追加**

`publish(netease, qq)` = `SearchRanking.order(...).map{value}.distinctBy { it.trackKey }`（`:143-165`）：
QQ 到达后**整表重算**（会员墙分组 + 拉链交错），再 `distinctBy` 去重。

### 0.4 会不会被 v2.4.0 跨源匹配（`crosssource/`）丢弃：**不会**

`CatalogAggregator` 的调用点只有 `AlbumDetailScreen / ArtistDetailScreen / SongDetailScreen`
（`grep -rn CatalogAggregator app/src/main/java` 共 3 个 Screen），**搜索链路一次都不经过它**。
搜索里唯一的去重是 `distinctBy { it.trackKey }`，而 `trackKey = "netease:123" / "qqmusic:456"`
（`SongSourceExt.kt:35-36`）**含音源**，同号不同源不会被合并；只有 **(source,id) 完全相同**的重复条目会被去掉。

---

## §1 方法（可复现）

| 脚本 | 作用 |
|---|---|
| `probe-qq-search-latency.py` | 宿主机直连（`--noproxy '*'`）打 **app 里逐字节相同**的两条 QQ 通道：A) legacy GET 每次新连接；B) legacy ×10 同连接；C) `musicu.fcg` POST。`curl -w` 取 DNS/TCP/TLS/TTFB/total 分相 |
| `probe-device-search-timeline.py` | 真机时间线：`logcat`（app 自己的 `aggregate` 行）+ ~0.3–1.0s 一帧 `screencap`，并在点击前后校验 `mCurrentFocus` 与搜索框像素，避免把「点错界面」当成结果 |
| `probe-device-cold-warm.sh` | 冷启（`force-stop` + `am start`）vs 热态各 3 轮，读同一行 `aggregate` |
| 触达中文关键词的办法 | `adb shell input text` **打不了中文**；改为 root 写入 `search_history.xml` 一条**「不完整的老 QQ 条目」**（`source=qqmusic` 且无 `sourceid`），点它会走 `viewModel.onQueryChanged(title)`（`SearchScreen.kt:264-274` + `SearchHistoryMigration.isIncomplete`），等价于用户手输「晴天」而**一次请求都不多发**。原始文件已备份并在收尾时**还原到 sha256 一致**（见 §3 改动清单） |

关键命令（原始输出在 `probe-raw/search-qq/`）：

```bash
python3 probe-qq-search-latency.py --n 30 --keyword 晴天 --out probe-raw/search-qq/host-endpoint-qq-search-direct.json
bash probe-device-cold-warm.sh 3B15CD00GB700000 3 probe-raw/search-qq/plc110-cold-warm-ipv6off.txt
python3 probe-device-search-timeline.py --device 3B15CD00GB700000 --tag ui3 --tap 570,705 \
    --seconds 5 --clear-first --clear-tap 1080,267 --outdir probe-raw/search-qq/timeline
```

---

## §2 逐问回答

### 2.1 Q1 — QQ 搜索接口 P50/P95；接口慢还是本地慢

**接口慢。** 宿主机与两台设备同一 /24 网段、同一出口（`192.168.5.32 / .70 / .75`），
**必须 `--noproxy '*'`**：本机 `https_proxy=http://127.0.0.1:10808` 存在，走代理测的是另一条出口。

| 通道 | n | total P50 | total P95 | TTFB P50 | TTFB P95 | TLS P50 | DNS P50 |
|---|---|---|---|---|---|---|---|
| A) legacy `client_search_cp` 新连接 | 30 | **1857ms** | **3128ms** | 1722ms | 2989ms | 213ms | 6ms |
| B) legacy 同连接 ×10 | 10 | 1969ms | 2600ms | 1821ms | 2466ms | 0ms | 0ms |
| C) `musicu.fcg` POST（兜底） | 15 | **376ms** | **910ms** | 376ms | 627ms | 252ms | 1ms |

30/30 与 15/15 全部 HTTP 200 且**返回非空歌曲列表**（`empty_results=0`）⇒ 本次没触发第二条通道。
`TLS 213ms + DNS 6ms` 之外**全是服务端时间**⇒ 本地处理不可能是主因。
（对照：走宿主机代理的同一脚本 `n=30` 得到 legacy P50 2076 / P95 3104 —— **代理会改变结论的绝对值**，
故正文一律用直连数据。）

### 2.2 Q2 — 首次 vs 第二次

真机 `aggregate` 行（含两侧条数与总耗时）：

| 阶段 | 3 次实测（ms） |
|---|---|
| **冷启后第一次搜索** | 2117 / **4363** / 2295 |
| 同进程热态 | 2259 / 2176 / 2864 |
| 另一进程热态（5 次） | 2554 / 2494 / 2570 / 2034 / 2545 |

15 次合计 **P50 2545ms / P95 4147ms / max 4363ms**，每次都是 `netease=30 qq=30`。
⇒ **没有「首次初始化」效应**（见 §0.1 #3）：搜索路径无 token/签名预热，冷热分布重叠。
真正的变量是**服务端抖动**（接口 P95 是 P50 的 1.7 倍）。

### 2.3 Q3 — 并发还是串行：**串行**（确切结构）

```
viewModelScope.launch {                      // SearchViewModel.kt:88 / :102
  delay(500)                                 // :89  防抖
  searchByType(type)                         // :108
    _isLoading = true                        // :109
    RetrofitClient.api.search(...)  ←─ 挂起等待ncm   // :131-136  (runCatching)
    publish(netease, emptyList()); _sourceCounts = (n, 0) ←─ 先发布  // :167-171
    withTimeoutOrNull(5_000) { SourceRouter.searchSongs(QQMUSIC, ...) } ←─ 才发 QQ  // :179-181
    if (qq.isNotEmpty() && query == keyword) publish(netease, qq)     // :192-194
    _sourceCounts = (netease.size, qq.size)  ←─ 无 query 守卫        // :195
}
```
**没有 `async`/`awaitAll`/`coroutineScope`**，两个源在同一个协程里一前一后 ⇒ **整体 = sum**。
（ncm leg 真机约 1.2–1.8s：见 §2.5；QQ leg = 总耗时 − ncm leg。）

### 2.4 Q4 — QQ 搜索请求链路：ptqrtoken / 签名 / 必填字段

- **搜索不用任何 token 与签名**：`ptqrtoken = hash33(qrsig)` 只属于扫码登录
  （`QqQrLogin.kt:48-59, :224`）；`g_tk` 只在 `webComm` 里硬编码 5381（`QqClient.kt:304-317`），
  服务端不校验。`zzc_sign` 那套是另一族 web 接口，本实现不用（`QqClient.kt:36-49`）。
- 主通道：`GET https://c.y.qq.com/soso/fcgi-bin/client_search_cp?p=1&n=30&w=<kw>&format=json&cr=1&new_json=1`
  + UA=桌面 Chrome + `Referer: https://y.qq.com/` + cookie（`QqRequests.kt:99-105`、`QqClient.kt:275-297`）。
- 兜底通道：`POST musicu.fcg`，信封 key = **module 名**且**不带 `comm`**（带了会被判通道不匹配）
  （`QqRequests.kt:90-97`、`QqApi.kt:84-95`）。
- **与主页路径是否一致**：主页/详情走的是 `RetrofitClient`（ncm）与 QQ 的歌单页 `QqPlaylistApi`，
  **搜索这两条通道是搜索独有的**；同一个 `QqClient.searchHttp` 只服务搜索。未逐字段比对主页路径（§4）。

### 2.5 Q5 — 会不会静默重试累加成 5s

**没有循环重试**（`QqClient.musicu`/`legacyGet` 只发一次，失败返回 null；`QqApi.searchSongs` 也没有重试）。
但有一条**一次性的第二通道**：`legacy` 返回 null 或解析出 0 条 ⇒ 再打 `musicu.fcg`（`QqApi.kt:68-81`）。
若第一条通道是**超时失败**，最坏 = legacy 8s（callTimeout）+ musicu ≤8s，但 `withTimeoutOrNull(5_000)`
会在 5s 处放弃 ⇒ **累加被 5s 预算截断，代价是这一轮 QQ 结果全丢**。
本次 15 次真机 + 30 次宿主机实测**第一条通道全部成功**，**未观测到**这条路径真实发生（§4）。

### 2.6 Q6 — 结果映射耗时

`QqSongMapper` 是纯 JSON 遍历（`optJSONObject/optJSONArray`，30 条，无正则、无 IO、无排序）
（`QqSongMapper.kt:52-142`）。真机 UI 观测显示：QQ 结果到达与计数刷新在同一帧窗口内，
**没有可观测的额外停顿**（帧间隔 0.5–1.0s 内完成）⇒ 映射耗时 **< ~0.3s（上界，非独立基准）**，不是瓶颈。

### 2.7 Q7 — 有无加载态？「0 首」是真没有还是还没回来？

**没有任何加载态，且两种语义在 UI 上完全同形。**「qm 0 首」有**三个**不同来源，界面无法区分：

1. `_sourceCounts.value = netease.size to 0` —— **QQ 还没发出去**就先写死 0（`SearchViewModel.kt:170`）；
2. QQ leg 超 5s 被 `withTimeoutOrNull` 放弃 ⇒ `qq = emptyList()` ⇒ `:195` 写 `(n, 0)`，**且此后再无更新**；
3. QQ 真的返回 0 条。

文案由 `strings.sourceSummary(a, b)` 生成（`SearchScreen.kt:448-459`，`zh_CN.kt:150` =
`"ncm $a 首 · qm $b 首"`）；`b` 直接来自 `_sourceCounts.second`，**不含任何 loading/unknown 维**。

### 2.8 Q8 — 统计数字是定格还是动态更新

**动态更新，但只更新两次、且第二次可能永不发生**：`:170` 写 `(n, 0)` → `:195` 写 `(n, qq.size)`。
`sourceCounts` 是 `StateFlow`，`SearchScreen.kt:101` collect ⇒ 第二次写入会触发重组刷新。
注意 `:195` **没有** `_query.value == keyword` 守卫（`:192` 有）⇒ 用户已改词时，陈旧计数仍会写回。

### 2.9 Q9 — 追加 / 替换 / 去重；会不会被跨源匹配丢弃

**替换 + 重排 + 同源去重**：
`:192-194` 用**完整的两侧列表**再调一次 `publish` ⇒ `SearchRanking.order(...)` 整表重算（会员墙分组、
两源交错），再 `.map{value}.distinctBy { it.trackKey }`（`:143-165`）。
`trackKey` 含音源（`SongSourceExt.kt:35-36`）⇒ **跨源不会被吞**；`crosssource/CatalogAggregator`
**不在搜索链路上**（只有专辑/艺人/单曲详情页调用）⇒ **不会被 v2.4.0 跨源匹配丢弃**。

### 2.10 Q10 — 超时阈值与超时后的 UI

| 层 | 阈值 | 超时后果 |
|---|---|---|
| QQ 搜索（ViewModel） | **5000ms** `withTimeoutOrNull`（`SearchViewModel.kt:83, 179`） | 返回 null ⇒ 当成空列表 ⇒ 计数 `(n, 0)`、**无错误、无提示、不重试** |
| QQ search OkHttp | connect 4s / read 6s / write 6s / **callTimeout 8s**（`QqClient.kt:81-88`） | 抛异常 → `SourceRouter`/Provider 的 `runCatching` 吞掉 → 空列表（`SourceRouter.kt:77-82`） |
| ncm OkHttp | connect/read **30s**，**无 callTimeout**（`RetrofitClient.kt:36-54`） | ncm leg 单独就能挂 30s；实测曾出现 `elapsed=22868ms / 15742ms` 后被用户清空取消 |

**重要**：`withTimeoutOrNull` **取消不了**阻塞中的 `execute()`（`QqClient.kt:73-80` 的 KDoc 已写明）
⇒ 5s 后 UI 放弃，底层请求仍在跑、结果被丢弃。

### 2.11 Q11 — 有无「QQ 搜索失败但静默」

**有，而且是默认行为。** 三层 `runCatching` 全部把异常变成空列表：
`QqMusicSourceProvider.searchSongs`（`:50-53`）、`SourceRouter.searchSongs`（`:77-82`）、
`SearchViewModel` 的 `try/catch`（`:182-185`）。
只有「ncm 也空 **且** ncm 报过错」才写 `_error`（`:198-200`）⇒
**QQ 单独失败时用户看到的就是「qm 0 首」，没有任何提示**。

---

## §3 修复方向建议（不改本次探针范围）

### 3.1 已被验证的根因（按性价比）

| 方案 | 对应根因 | 成本 | 回归面 |
|---|---|---|---|
| **A. 加载态（必做）** | §0.2 / §2.7 | 小：把 `sourceCounts: Pair<Int,Int>?` 换成三态（`Loading / Loaded(n,m) / Failed(reason)`），文案区分「未回」与「0 条」 | `Strings.kt` + 8 个语言文件各加 1–2 个属性；`SearchScreen` 小字那一处 |
| **B. 并发（推荐）** | §2.3 串行 sum | 中：`coroutineScope { async ncm; async QQ }`，ncm 到手先 publish，QQ 到手再 publish | **主源先发布的语义必须保住**（否则回到 v2.1.0 hotfix 3 的「一直转圈」）；两条 leg 的错误处理与 `_isLoading` 时序要一并改 |
| **C. 换主通道（推荐，本次最大发现）** | §2.1：legacy P50 1857ms vs **musicu P50 376ms** | 小：把 `QqApi.searchSongs` 的两条通道**调换优先级**（musicu 先、legacy 兜底） | 与 `QqApi.kt:55-62` 的注释结论相反 —— 该注释称 musicu「连续 6 次只成功 1 次（code 2001）」，但**本次 15/15 成功且快 5 倍**；服务端行为可能已变，需再采一轮 n≥30 才能翻案 |
| **D. 缓存** | 同词重复搜索 | 中：按 `(keyword, source)` 短 TTL 缓存 | 结果时效性与「搜 A 显示 B」的既有守卫（`:192`）要一起考虑 |
| **E. 降级** | §2.10 超时丢结果 | 小：超时**不清空**上一次 QQ 结果、或延长到 8s 并与 `callTimeout` 对齐 | 与「结果过期」语义冲突，需要版本号/关键词守卫 |

**推荐组合：A（必做）+ B + C**。A 单独就能消掉「用户以为没有」这个**错误结论**；
B 把可见延迟从 sum 拉到 max；C 若成立直接把 QQ leg 从 ~1.9s 压到 ~0.4s。

### 3.2 顺带发现（不在本次要求内，供决策）

- `SearchViewModel.kt:224` 的 `catch (e: Exception)` **会吞掉 `CancellationException`**：
  实测取消后仍继续走完 QQ 分支并打日志（`W SearchViewModel: netease search failed / C5.e0: u0 was cancelled`）。
  正确做法是先 `if (e is CancellationException) throw e`。
- `:195` 的 `_sourceCounts` 写入缺 `_query.value == keyword` 守卫（`:192` 有）。

---

## §4 未验证项（如实列出卡点）

1. **S6（Android 7.0）未参与 UI 实测** —— 设备处于**带 PIN 的锁屏**（截图 `s6-lockscreen.png`），
   `input swipe` 无法解锁，无 PIN 不可绕过。其登录态已由 root 读 `ncrust_prefs.xml` / `ncrust_qq_prefs.xml`
   确认为「ncm + QQ 双登录」，但**没有任何一台 Android 7 的耗时数据**。
2. **映射层没有独立基准**：§2.6 的「<0.3s」是「QQ leg 实测 − 接口 TTFB」的**上界**，不是 JVM 基准测试。
3. **`legacy → musicu` 兜底路径未被真实触发**（15+30 次里第一条通道 100% 成功）⇒ 「两次串行 RTT 会不会
   凑成 5s」只有代码推断（§2.5），**没有实测**。
4. **未做「主页/详情路径 vs 搜索路径」的逐字段比对**（§2.4 末）。
5. **未测弱网/移动数据**：所有数据都在同一 WiFi（`192.168.5.0/24`）下取得。
6. **首次冷启动的 `elapsed` 样本只有 3 次**，不足以给出冷/热的统计结论。
7. **`ui4` 那一次观测到「app 日志已记 `qq=30` 但 5.29s 的帧仍是 0 首」**，与 `ui2/ui3` 不一致；
   帧间隔 0.5–1.0s + `screencap` 取帧时刻的不确定性，无法判定是「重组延迟」还是「取帧时序」。
   **不作为结论**，仅记录。

### 4.1 测过的环境阻塞（如实记录，不是 app 缺陷）

PLC110 上最初 4 次搜索**全部卡死**（`elapsed=10269/15742/22868ms`，`netease=0 qq=0`，直至被取消）。
根因定位：**该 WiFi 下到 `*.music.163.com` 的 IPv6 被黑洞**
（`/proc/net/tcp6` 显示 app 到 `2409:8c70:3a08:6:8000:0:d00:12:443` 的两条连接停在 `SYN_SENT`；
`nc -z <IPv6> 443` `exit=1`，`nc -z 111.19.176.86 443` `exit=0` 且 80ms），
而 `RetrofitClient` 的 ncm 通道是 30s connect / 30s read、**无 callTimeout** ⇒ ncm leg 单独挂 30s。
**处置**：`su -c 'echo 1 > /proc/sys/net/ipv6/conf/wlan0/disable_ipv6'` 临时关掉 wlan0 的 IPv6，
搜索立即恢复正常（此后 15 次全部成功）；**收尾已还原为 `0`**。
⇒ §2 的所有真机数字都是**在关掉该接口 IPv6 之后**取的（QQ 侧只走 IPv4，不受影响）。

---

## §5 对设备做的改动（全部已还原，逐条留痕）

| 改动 | 备份/还原证据 |
|---|---|
| `search_history.xml` 写入一条合成的「晴天」**不完整 QQ 条目**（为了在 `input text` 打不了中文的前提下触发搜索） | 备份 `/data/local/tmp/search_history.xml.bak`，sha256 `38a2a48a62992bf18b143a8ee11cb1c384169ccb4c075c873648d7dca59e72ea`；收尾还原后 **sha256 与备份逐字节一致** |
| `wlan0` 临时禁用 IPv6 | 原值 `0` → 改 `1` → 收尾还原 `0`（§4.1） |
| app 多次 `force-stop` / `am start` | 无持久影响；期间与**并行任务**（父任务在跑 gradle 全量构建与设备验证）可能互相 force-stop，若某次帧序列被打断即按「被打断」处理，不重跑 |
| 副作用 | 一次误点（把历史条目当成搜索入口）导致播放器**切了歌**（`無限抱擁` / `EM16_Normal_Edit#070705`）；未改任何播放设置 |

---

## §6 原始证据索引（`docs/verification/v2.5.5/probe-raw/search-qq/`）

| 文件 | 内容 |
|---|---|
| `host-endpoint-qq-search-direct.json` / `.log` | §2.1 直连接口数据（n=30 / 10 / 15，含每次样本） |
| `host-endpoint-qq-search-anon.json` / `.log` | 走宿主机代理的对照数据（证明代理会改变绝对值） |
| `plc110-cold-warm-ipv6off.txt` | §2.2 冷/热 3+3 轮的 app 日志原文 |
| `plc110-cold-warm.txt` | IPv6 修复前的失败样本（卡死 ≥9s 后被取消） |
| `logcat-coldsearch-full.txt` | 卡死现场的全量 logcat（含 `elapsed=15742ms` 等） |
| `timeline/ui1..ui4/` | 每次：`timeline.json`（**逐帧时刻 + app 日志原文**）+ `summary-strip2.png` + 1 张代表帧。⚠️ 为控制体积，其余帧已删除；`timeline.json` 里仍保留每帧的文件名与时刻，需要时可用同一条命令重跑复现 |
| `timeline/ui2/summary-strip2.png`、`timeline/ui3/summary-strip2.png` | **「ncm 30 首 · qm 0 首」→「qm 30 首」逐帧证据**（§0 的关键截图） |
| `plc110-searchtab.png` / `plc110-precheck.png` | 搜索页初始态与前置校验帧（脚本每次点击前都会留 `pre.png` 供事后核对） |
| `s6-lockscreen.png` | S6 带 PIN 锁屏（§4.1 第 1 条的卡点证据） |

---

## §7 对并行落地的修复的复核（`c74c6d8`）

探针期间父任务已把修复提交为 **`c74c6d8 fix(search): 聚合搜索的「qm 0 首」假话 + 两个源改成并发发起**。
本节是对该提交的**代码级复核**（不涉及重新实测，被测 APK 仍是 v2.5.4）。

| 复核项 | 结论 |
|---|---|
| 两条判断是否成立 | ✅ **都成立**：① 旧实现确实 `await ncm → 再发 QQ`（sum 而非 max，`10e9df9:131-188`）；② 「qm 0 首」确实来自 `_sourceCounts.value = netease.size to 0`（`10e9df9:170`） |
| 并发是否保住了「主源先发布」 | ✅ 保住：`async` 只让**请求**重叠，`neteaseDeferred.await()` 之后才 `publish(netease, emptyList())` 与关 loading，顺序契约未动 |
| 三态是否覆盖了 §2.7 的三种「0 首」 | ✅ 覆盖：`PENDING`（还没回）/ `TIMEOUT`（超预算）/ `DONE + 0`（真的 0 条）+ `SKIPPED`（未登录且不允许匿名） |
| 计数写入是否补了 query 守卫 | ✅ 补了（`if (_query.value == keyword)`），修掉了 `10e9df9:195` 的陈旧写回 |
| `withTimeoutOrNull` 的 null 语义 | ✅ 现在区分了 `null ⇒ TIMEOUT` 与 `emptyList ⇒ DONE/0`；**但**通用异常也走 `timedOut = true`（网络错误会被显示成「超时」），语义上建议拆成 `TIMEOUT` / `ERROR` 两态 |
| 遗留（非本次修复引入） | ⚠️ 外层 `catch (e: Exception)` **仍吞 `CancellationException`**：用户改词会 cancel，此时仍会写 `_error` 并可能在「一条结果都没有」时 `clearResults()`。建议 `if (e is CancellationException) throw e`。本次实测多次复现（`W SearchViewModel: … u0 was cancelled` 之后照常走完并打日志） |
| 遗留 | ⚠️ `withTimeoutOrNull` 取消不了阻塞中的 `execute()`（`QqClient.kt:73-80` 已写明）：5s 后 UI 放弃，底层请求仍在跑。并发化之后 QQ 请求**总是**会发出去，这一点的影响面比之前更大（以前 QQ 至少排在 ncm 之后） |
