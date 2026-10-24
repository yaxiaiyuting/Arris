# 网易云参考实现对照与 QQ 适用性审查

审查日期：2026-10-25（Asia/Shanghai）。Arris 基线：88e4a6b（v3.7.2-gpl / 88）。参考项目固定为 [NeteaseCloudMusicApiEnhanced/api-enhanced @ f01f99e](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced/tree/f01f99eece7252ba6d4496f0082eb9429d5f06ea)，避免 main 后续变化使结论不可追溯。本轮只读对照，没有向真实账号发送续期、收藏或播放上报请求，没有接入第三方托管服务，也未复制参考代码。

## 新发现一：P1，网易云旧会话响应能影响新登录态

证据：`network/PlaylistApi.kt:48` 的 reportBusinessCode 仅传 code，不携带请求起始的会话身份；`account/SessionMonitor.kt:124–138` 收到 301 无条件 markExpired 并清除当前 Cookie。CookieManager 的 save/clear 没有 auth revision。SessionMonitor 的 expiryGeneration 仅在 markExpired 增长，登录成功与主动登出不增长；checkBlocking 在 account/get 返回后才为第二跳 VIP 读取 generation，因此也不能隔离整个复核流程。

可达次序：A 会话发出私有请求 → 用户退出并登录 B → A 的迟到响应返回 301 → markExpired 清掉 B 的有效 Cookie。另一条次序是旧 account/get 在登出后返回 Valid，继续写回旧资料。现有 generation 只覆盖一部分 VIP 请求期间的失效事件，不能把该问题视为已解决。

同一问题的缓存落点：`auth/NeteaseVipStore.kt:96–107` 请求结束直接写全局 is_vip / checked_at，不校验请求属于哪个账号；退出时 clear 仍可被在途 A 的旧结果重新填入。B 看到的搜索排序会员判据可沿用 A 的结果，直至 30 分钟 TTL。这里只影响客户端资料/排序，不授予服务端权益。

建议：先给网易云会话引入原子快照（凭证 + 单调 auth revision），登录、同号重登、登出、轮换都推进版本。每个业务响应、失败副作用、资料缓存提交都对照请求快照；迟到 301 不能清新凭证。清缓存与版本变更必须受同一提交闸门保护。验证应覆盖 A→B、A→登出、同号重登、旧失败/旧成功迟到，以及复核两跳中间换号。

## 新发现二：P2，QQ 歌单把断网和服务异常误判为需要登录

证据：`qq/QqPlaylistApi.kt:67–75` fetchUserInfo 已区分 Ok / Network / Failure / NeedLogin，但 checkLoginValid 用 `is Ok` 压成 Boolean。`qq/QqPlaylistRepository.kt:105、223` 两处把所有 false 映射为 NEED_LOGIN / NeedLogin。

触发：已登录用户断网后强制刷新歌单，或首次打开无缓存详情时请求失败。musicu 返回 null → Network → false → 界面提示重新登录。未知业务码和畸形响应也走相同误分类。这不一定清除 QQ 凭证，但会误导用户重登。

建议：调用方保留完整分类。只有明确 NeedLogin 才走续期/重新登录；Network 和未知失败按离线/重试处理。有缓存时保留缓存。歌单只读鉴权可复用 QQ 现有单飞续期、冷却、auth revision 和最多一次重试，但不能把公开歌单读取成功视为登录态有效。补测列表/详情、有无缓存、断网、限流、显式过期、刷新成功后重试。

## 参考实现哪些值得用

| 参考路径与机制 | Arris 当前覆盖 | 网易云建议 | QQ 可迁移部分 |
|---|---|---|---|
| [like_v1.js](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced/blob/f01f99eece7252ba6d4496f0082eb9429d5f06ea/module/like_v1.js)：xeapi `/api/v1/radio/like` + checkToken v3 | PlaylistApi / RetrofitClient / XeapiState 已覆盖；设备 ID 持久化、公钥缓存与失败后一次刷新已有 | 保留，关注服务端是否持久保存的回读结果；不能仅据 HTTP 200 宣布云端永不回滚 | 可迁移“目标状态 + 确认 + 有限重试”，加密、域名、token 都不能搬到 QQ |
| [login_refresh.js](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced/blob/f01f99eece7252ba6d4496f0082eb9429d5f06ea/module/login_refresh.js)：`/api/login/token/refresh` 并返回新 Cookie | 网易云无此续期调用；SessionMonitor 当前发现失效后清 Cookie | 优先补会话版本闸门，再做使用时/回前台按需刷新，合并响应 Cookie 并自证有效；没有寿命证据时不编造“一周有效期” | QQ 已有 refreshCredential / 三种登录类型参数 / 单飞 / 失败冷却 / 成功静默期 / 迟到响应保护；适合扩展到歌单鉴权入口 |
| [request.js](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced/blob/f01f99eece7252ba6d4496f0082eb9429d5f06ea/util/request.js)：neapi 配置按 TTL 更新、共享在飞 Promise，777/999 后最多重试一次 | xeapi 公钥已锁内复用刷新；QQ Mutex 等待在飞续期 | 可借鉴“同一失效状态只刷新一次”的状态机。neapi 属于另一整套信封/配置/响应协议，需有独立夹具与接口验证后再做试验通道，当前无证据必须替换所有 eapi | 只移植并发/生命周期策略；QQ 必须继续使用自身 musicu、comm、登录类型和业务码 |
| [lyric_new.js](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced/blob/f01f99eece7252ba6d4496f0082eb9429d5f06ea/module/lyric_new.js)：新版逐字歌词 | Arris 经 `/api/song/lyric` + yv=-1 取 YRC，另有 TTML 来源链、缓存和请求身份闸门；v3.7.1 已修缓冲计时与迟到任务 | 已具备逐字能力，但并非同一个 endpoint；旧探针没有发现切 v1 可提高覆盖率（YrcParser 文档）。先做对照再决定是否换接口，播放位置重锚仍由本地音频时钟负责 | QQ 保持自己的逐字解析，统一时间/身份抽象即可，网易字段不能冒充 QQ 字段 |
| [song_url_v1.js](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced/blob/f01f99eece7252ba6d4496f0082eb9429d5f06ea/module/song_url_v1.js)：xeapi 取链、按档位附加字段 | Arris 取链现用 eapi + 自有音质与权益识别 | 可做同账号、同曲目、同档位的离线夹具/受控对照；不能因参考通道较新就全量替换已经有实测记录的路径 | 借鉴“实际返回格式/试听属性为准”，不映射网易音质名到未经验证的 QQ 档位 |

参考 request 层并没有自动调用 login_refresh；不能把这个独立接口误读为已实现完整的客户端自动续期。服务端参考实现返回 Set-Cookie 让调用者保存，Android 客户端仍需自己完成持久化、账号隔离与更新请求层快照。

## 不能照搬的部分

1. 不把整个 Node 服务嵌入手机或强制增加远端中转：Arris 现有 Kotlin 直连可逐模块吸收协议与状态管理。参考项目 MIT 许可；以后实际复制实现需保留其版权与许可说明。
2. 不把解灰结果当作网易原曲、原音质或真实码率。参考 song_url_v1 的可选 unblock 分支会构造 level/type 等字段；Arris 若未来做音源替代，必须保留真实来源与匹配依据，实际音质来自返回流。当前未接入这条路径。
3. 不迁移随机 IP、固定客户端身份常量作为“通用修复”。现有协议已经按扫码、收藏、上报区分身份；每次任意变更会使已有证据失效。
4. 不复制错误分支：该固定版本 [login_qr_check.js](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced/blob/f01f99eece7252ba6d4496f0082eb9429d5f06ea/module/login_qr_check.js) 的 catch 引用了 try 内局部变量 result，失败时会产生 ReferenceError；[playlist_tracks.js](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced/blob/f01f99eece7252ba6d4496f0082eb9429d5f06ea/module/playlist_tracks.js) 的 512 处理会重复提交 trackIds，不宜在没有服务端语义证据时搬进 Arris。

## 建议顺序及本轮边界

先修网易云会话代次/迟到响应，以及 QQ 歌单的分类丢失，再接网易云按需续期；最后才评估 neapi/xeapi 取链实验。写操作遇到响应解密失败或超时，服务端可能已经执行：不能通用自动重放全部请求，优先查询目标状态。QQ 可共享策略接口，不能共享网易协议实现。

前一轮队列预载、离线多档回退、试听缓存属性三项见 `2026-10-24-playback-adjacent.md`。本报告新增的两类问题也尚未修改业务代码；v3.7.2 仅修庆祝按钮布局，避免把源码建议描述成已经上线或经过真实账号验证。

## 修复状态（v3.7.3-gpl / versionCode 89）

本轮按建议顺序落地，**没有**把推荐项描述成已上线经验：所有验证都是 JVM 单测 + 静态检查，未连接真实账号、未做真机测试。

### P1 会话代次与迟到响应

- `auth/NeteaseSessionGuard`：会话快照 = 凭证 + 单调 revision + 登录代次。登录、登出、明确重登都推进登录代次；续期只推进 revision（`renewal = true`），因此“换票”不会让同账号的在飞读取作废。`toString()` 不含凭证。
- `auth/CookieManager`：`snapshot / isCurrent / ifCurrent / advanceGeneration / commitRefresh` 与 Cookie 落盘在同一把锁内完成；`commitRefresh` 先校验请求身份再写盘，写失败回滚并返回 false。
- `network/RetrofitClient`：请求带 `NeteaseSessionSnapshot` 标签（`cookieOverride` 路径不带，避免把“验证票”误认成当前会话）。
- `account/SessionMonitor`：`reportApiCode(code, started)` 必须携带请求身份；`markExpired` 只在 `ifCurrent(started)` 内清 Cookie；复核期间换号会重新排队一次，不再把旧账号结果写进状态。`deriveSession` 与 VIP 读取都受同一身份闸门保护。
- `auth/NeteaseVipStore`：先取快照再请求，提交前 `ifCurrent`，旧账号的会员结果不会覆盖新账号的排序判据。

### 网易云按需续期

- `auth/NeteaseTokenRefresher`：启动（`SessionMonitor.init`）、回前台（`onResume`）、实际使用（`SongUrlFetcher` / `SettingsAccountPage` / 每次 eapi 读取）按需触发；`Mutex` 共享在飞刷新；会话级冷却（失败 60 s、成功 10 min）；**续期后先用 `w/nuser/account/get` 验证票据再落盘**；`301` 才判 `AUTH_EXPIRED`，其余失败按可重试处理，绝不因网络故障清凭证。
- `auth/NeteaseSessionRequests`：`renewRead` 是纯编排缝——首次读取 → 判定是否被拒 → 共享续期 → 最多重试一次；换号期间抛 `NeteaseSessionChangedException`（**不是** `CancellationException`，否则调用方会把它当取消吞掉、监视器停摆）。
- 参考实现里 `777/999` 是 neapi 配置轮换判据，没有搬进登录失效判定。

### P2 QQ 歌单错误分类

- `qq/QqPlaylistAuth`：`playlistAuthFailure` 保留完整分类，`Network`/未知业务码 → `OFFLINE`，只有 `NeedLogin` → `NEED_LOGIN`。列表与详情两条路径都改用它。
- `QqPlaylistApi.checkLoginValid()` 返回完整结果而不是 Boolean；`fetchUserInfo` 走 `QqRefreshRetry`（共享在飞、冷却、成功静默期、迟到响应保护），只对明确的 `NeedLogin` 续期并重试一次。
- 写缓存前在 `QqAuthStore.withAuthLock` 内复核 owner，换号后不再把旧账号歌单写进新账号。

## 未做

- 逐字歌词接口切换、xeapi 取链通道对照：仍按“先做同账号同曲目夹具对照再决定”的顺序，本轮未动。
- 真机 / 真实账号验证：本轮明确不做。
