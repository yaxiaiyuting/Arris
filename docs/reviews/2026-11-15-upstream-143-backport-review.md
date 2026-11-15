# 上游 Ncrust v1.3.1 → v1.4.3 变更摘要与回移评估

2026-11-15。只读分析：clone `GuitaristRin/Ncrust`，对比 `v1.3.1..488a900`（`488a900` 是
「build: 升级至 v1.4.3」，同一提交被 `CrateStack-20261115A` 包含）；`Arris` 侧取
`refactor/arris-source` 的 `e3e5df5`（4.1.4 发布提交）。**本文件不改任何代码。**

## 结论先行

- 区间内 **226 个提交**，其中 **56 个**触及 `app/`（Android），**131 个文件、+16,847/−1,330 行**；
  其余绝大多数是 Windows（C#/UWP）与文档线。
- **值得回移的只有"协议/服务端交互"这一组**：收藏写入（xeapi 同形 + 字段集 + 502 + 反作弊凭据）、
  扫码登录（eapi + PC 身份 + type 3，修 −462）、播放上报（clientlog eapi + osx 身份）、
  客户端身份统一（设备 cookie / `XeapiIdentity`）。这些是"服务器那边的既有事实"，跟我们 UI 分叉无关。
- **不建议回移**：Windows/lumia 线、UI 重构与 Compose 微优化、应用内更新链路（我们已有自己的一套
  镜像 + `/api/latest`）、无缝播放会话（我们已按 Media3 重写）。
- **`spec/` 是这次最划算的收获**：上游把协议、加密、音质、队列、更新、歌词都沉淀成夹具
  （`spec/fixtures/*.json` + `spec/tools/*.mjs`），可以直接当"行为规格/测试向量"用，不必搬代码。

## 分档口径

以 **fork 基线 `v1.3.1`** 为参照，逐个文件算 `Arris` 当前内容与基线的差异行数：

| 档 | 差异行数 | 文件数 | 含义 |
|---|---|---|---|
| low | < 40 | 8 | 基本没被我们动过，**可挑** |
| mid | 40–200 | 42 | 小改，**手工合** |
| high | > 200 | 42 | 我们重写过，**只能按语义重做** |
| missing | 文件不存在 | 39 | 上游新增，**属新功能，要先决定要不要** |

## 一、上游 Android 侧变更摘要（按主题）

### 1. 收藏与同步（价值最高）

| 提交 | 内容 |
|---|---|
| `bf1a8be` | 收藏写入改走官方 Android 同形的 xeapi `/api/v1/radio/like`，修「收藏隔天消失」 |
| `4410100` | 收藏写入体补 `checkToken`，与官方客户端同形 |
| `293430c` | 写入体补全官方字段集并**接受 502**（按 9.6.13 反编译实证对齐） |
| `c84c0fd` | 收藏状态以**红心 id 底表**为准，修播放器白心与点击反向取消 |
| `336f9cd` | 待同步收藏按记录的**期望方向**推送，不再误发取消收藏 |
| `4133381` | 禁用旧通道回退，保护最新一次操作 |
| `19bf30c` | 反作弊凭据缺失时**保留待同步**（不丢用户操作） |
| `6b5b470` | 收藏同步可观测化；云端读失败**不再覆盖本地** |
| `9acf753` / `8064b45` | 收藏专辑翻页取全量；统一 xeapi 客户端身份并补齐设备 cookie |

### 2. 登录

| 提交 | 内容 |
|---|---|
| `108ddb9` | 扫码登录改走 eapi + PC 身份 + `type 3`，修申请二维码被回 **−462** |
| `c2977ae` | 网页登录只认**非空 `MUSIC_U`**，并等旧 cookie 清完再加载登录页 |

### 3. 播放上报

| 提交 | 内容 |
|---|---|
| `6d4814d` | 播放上报迁到 **clientlog eapi + osx 身份**，修「本地收听不入账」 |

### 4. 歌单写操作与管理（新功能，Arris 没有对应实现）

`bfba8b9` 后端接口 → `b1fe7ec` 账号隔离的写入与读回确认 → `ada002a` 管理界面与歌单上下文播放 →
`a439577` 详情长按多选 + 菜单挪到行尾 → `4f0247b` 播放列表构图重整。

### 5. 播放会话 / 无缝（我们已重写，不建议搬）

`afa5033`（会话原子块）、`da07bb0`（显式键信封落盘，修 release 启动闪退）、
`5247497`（MediaItem 身份为准，消除元数据串歌）、`98d1fc5`（队列索引以实际在播为准）、
`d31cd8e`（下载中断前保存可信续传元数据）、`9a4f75c`（区分手动下一首与自然结束）。

### 6. 更新链路（我们有自己的实现，跳过）

`c14b1be` / `3e462c4` / `f648d99`：应用内更新检测、过期缓存、续传身份、目标门控、分页。
Arris 走 `arris.de5.net/api/latest` + 多镜像 + `UpdateMirrors`，与上游那套是两条独立实现。

### 7. UI 与性能（分叉最大，不建议搬）

`f5593a8` 动态调度与音频 offload、`cb51990` 播放卡片最小作用域、`6f6986b` 隐藏界面停 ticker、
`bff5b69` 小屏高度档、`0b3684d` 主界面左右滑动切标签、`6cbb030` 上拉全屏阈值、`0ed2d4a` 等若干。

### 8. 分享

`0b13228` 歌曲分享出口与外部链接/分享接收。

## 二、回移清单（按建议优先级）

| 优先级 | 主题 | 上游提交 | Arris 现状（差异行数） | 建议 | 预估 |
|---|---|---|---|---|---|
| **P0** | 播放上报 clientlog eapi + osx | `6d4814d` | `account/PlaybackNotice.kt` 差异 35（low） | **直接挑**，再看上报入口是否一致 | 0.5 天 |
| **P0** | 收藏写入同形 + 字段集 + 502 | `bf1a8be` `4410100` `293430c` | `library/LibraryManager.kt` 差异 796（high）、`network/crypto/XeapiCrypto.kt` 384（high）、`network/RetrofitClient.kt` 687（high） | **按语义重做**；先读上游实现，再落到我们的写入路径 | 2–3 天 |
| **P0** | 待同步队列与反作弊凭据 | `336f9cd` `4133381` `19bf30c` | 上游新增 `library/PendingLikeWrites.kt`(61) `library/LikeSyncAudit.kt`(26) `network/XeapiIdentity.kt`(73) | **可直接引入**这三个新文件，再接到我们的收藏路径 | 2 天 |
| **P1** | 扫码登录 −462 | `108ddb9` | 登录相关文件 high | 按语义改（PC 身份 + type 3） | 1–2 天 |
| **P1** | 红心 id 底表判定 | `c84c0fd` | 与收藏同一批文件 | 与 P0 收藏一起做 | 并入上条 |
| **P1** | 网页登录只认非空 `MUSIC_U` | `c2977ae` | `auth/CookieManager.kt` 差异 127（mid） | 手工合 | 0.5 天 |
| **P2** | 歌单写操作 + 管理界面 | `bfba8b9` `b1fe7ec` `ada002a` `a439577` | 上游新增 `playlist/PlaylistManager.kt`(435) `PlaylistRepository.kt`(284) `PlaylistReadApi.kt`、`network/PlaylistEditApi.kt`(352)；我们的 `network/PlaylistApi.kt` 差异 1049 | **当新功能重做**，别 cherry-pick | 4–6 天 |
| **P3** | 小屏高度档 | `bff5b69` | `ui/HeightClass.kt` 差异 35（low） | 可挑，但需接我们的布局常量 | 0.5 天 |
| **P3** | 分享出口 | `0b13228` | 我们有 `share/`（1,559 行） | 先比对差异，再决定 | 1 天 |

## 三、`spec/` 夹具（这次最划算的部分）

上游在区间内把协议与行为规格沉淀成夹具，**与语言无关，可直接拿来当 Arris 的测试向量**：

| 文件 | 行数 | 用途 |
|---|---|---|
| `spec/fixtures/crypto/{eapi,weapi,xeapi}.json` | 53 / 30 / 277 | 加密固定向量（含 xeapi 新向量） |
| `spec/fixtures/entitlement/{session,vip,playback}.json` | 99 / 154 / 430 | **权限/会员可播判定**的完整用例 |
| `spec/fixtures/quality/quality.json` | 186 | 音质阶梯 |
| `spec/fixtures/queue/queue.json` | 379 | 队列状态机 |
| `spec/fixtures/update/releases.json` | 853 | 更新源与筛选 |
| `spec/fixtures/lrc/lrc.json` | 78 | 歌词解析 |
| `spec/api/*.md`（xeapi / login / playlists / playlist-edit / entitlement / endpoints） | 211+199+79+74+67+64 | 端点目录（谁调谁、字段、错误码） |
| `spec/tools/*.mjs`（xeapi / ncm-client / qr-login / sms-login / like-experiment / probe-*） | ~1,000 | 现成的协议探针，可复现上游的实证结论 |

建议：把 `spec/fixtures/crypto`、`entitlement`、`quality` 三组**先接进 Arris 的单测**
（我们已有 3,622 项 JVM 测试，加进来成本低），用来证明"我们对协议的理解与上游一致"。

## 四、明确不建议回移

- `windows/`、`lumia/`、`Ritsudou.sln`：C#/UWP 与 Lumia 原型，跟 Android 无关。
- 更新链路（`UpdateService` / `UpdateScreen` / `UpdateDownloader`）：两套独立实现，换掉等于推翻
  我们的多镜像与 `/api/latest`。
- 无缝播放/会话落盘（`afa5033` `da07bb0` `5247497`）：我们 4.1.2–4.1.4 已按 Media3 重写并真机验证过。
- UI 重构与 Compose 性能微调：`MainActivity` 差异 915 行、`PlayerCard` 329、`FullPlayerControls` 274，
  照搬必然破坏我们的 Kanesumi 视觉与 8 语言文案契约。

## 五、建议执行顺序

1. **第一批（协议正确性，1 周内）**：`spec/fixtures` 接入单测 → 播放上报 → 收藏写入同形 + 待同步队列。
   验收：JVM 全绿 + 真机"收藏后隔天仍在""播放计入收听"两条实测。
2. **第二批（登录，3–4 天）**：扫码 −462、`MUSIC_U` 判定。验收：真机扫码成功、网页登录不再被半截会话覆盖。
3. **第三批（歌单写操作，按需）**：当新功能做，先出交互稿再写。验收：账号隔离（换号不串歌单）、写后读回确认。
4. 每批都只动一处，保持 `app/` 之外的 `spec/` 夹具同步更新，避免出现"两套理解"。

## 六、风险提示

- **同名文件双方都改过**（`LibraryManager`、`PlaylistApi`、`RetrofitClient`、`XeapiCrypto`）：
  cherry-pick 一定冲突，必须按语义重做，别指望 `git am`。
- **同一条服务器接口会出现两套实现**（我们 + 上游）：改协议认知时两边都要改，否则又是一次
  "主线/LTS 双线回移"的老账。建议以 `spec/fixtures` 为唯一事实来源。
- **测试 pin 会动**：Arris 有若干"契约测试"锁死文案与设置项数量，新增设置/词条要同步更新 pin。

## 复核（2026-11-15 下午，动手前逐项核对本仓实现）

上表的 P0 三项**在 Arris 里已经存在**，是我第一版排错了：当时按"上游改了哪个文件"去映射，
没有逐个核对本仓是否早已实现。证据：

| 项 | 上游提交 | Arris 现状 |
|---|---|---|
| 播放上报 clientlog + eapi + osx 身份 | `6d4814d` | `player/PlayReporter.kt` 头部即写明"通道 `POST https://clientlog.music.163.com/eapi/feedback/weblog`，eapi 加密 + os=osx 身份"，并已拆 `startplay` / `play`（≥80% 或自然结束）、每首去重 |
| 收藏走 xeapi `/api/v1/radio/like` + `checkToken` + 502 语义 | `bf1a8be` `4410100` `293430c` | `network/PlaylistApi.kt:1020-1085` 已有完整实现与依据，并保留 weapi/eapi 仅作回退 |
| 扫码登录 eapi + PC 身份 + `type=3`（修 −462） | `108ddb9` | `auth/QrLoginProtocol.kt:4-9` 已记录 −462 实测并走 eapi + PC 身份 |
| 网页登录只认可用 `MUSIC_U` | `c2977ae` | `auth/CookieManager.isUsable` 要求 `MUSIC_U` **与** `__csrf` 同时存在，比上游"非空 MUSIC_U"更严 |
| 收藏专辑翻页取全量 | `9acf753` | `PlaylistApi.getSubscribedAlbums` 已有 100 页循环 + 去重 + 无进展检测 + 登录代次校验，是上游那版的上位实现 |
| 系统手动下一首 vs 自然结束 | `9a4f75c` | 不适用：Arris 已换成 Media3 `MediaSession`（`playbackSession`），没有上游那条 `MediaSessionCompat.onSkipToNext → onPlaybackEnded` 的错误接线 |

结论修正：**上游 1.4.3 在 Android 侧没有"必须回移"的协议/播放正确性修复**。
剩余差异集中在两类，都不是"搬代码"能解决的：

1. **UI/交互细节**：歌单详情长按多选、歌曲菜单挪到行尾、播放列表构图重整（`a439577` `4f0247b`）；
   我们的界面是 Kanesumi 重写版，只能按交互稿重做。
2. **上游新增的测试夹具**：`spec/fixtures/{quality,queue,lrc}`（本仓目前只有 crypto/entitlement/update）。
   这属于"对齐规格"，可以移植，但**不改变任何用户可见行为**。

下次要动的正确顺序：先决定要不要那两个 UI 交互，再决定要不要补夹具；P0 回移清单作废。
