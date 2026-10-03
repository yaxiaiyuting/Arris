# PROBE — qm「用户歌单 / 用户信息」只读接口实测（v2.2.0）

- 探针日期：2026-09-25
- 目标：在写任何同步代码**之前**，把 qm 用户歌单相关的接口、字段、分页、登录态语义钉死
- 端点：`https://u.y.qq.com/cgi-bin/musicu.fcg`（客户端协议，**免签**，与 v2.1.0 既定结论一致）
- 设备：`3B15CD00GB700000`（OPPO PLC110 / Android 16 / SDK 36 / arm64-v8a），已 root（KernelSU），已登录 qm
- 账号：本机真机登录态（cookie 从应用私有目录 `ncrust_qq_prefs` 经 root 读出，**只在内存中使用，不打印、不落盘**）
- 脚本：`tools/probe-qq-playlist.py`（第 1–2 轮）、`tools/probe-qq-playlist-r3.py`、`tools/probe-qq-playlist-r4.py`
- 纪律：**只调读接口**；写接口只在 §7 登记、一次都没有调用；落盘一律脱敏

---

## 0. 一句话结论

> qm 用户歌单同步**可做且已实测打通全链路**：`music.musicasset.PlaylistBaseRead.GetPlaylistByUin`
> 拿歌单列表（**含 `dirId=201`「我喜欢」**），`music.srfDissInfo.DissInfo.CgiGetDiss` 用
> `song_begin`/`song_num` 分页拿歌单详情，`songlist[]` 经**既有的 `QqSongMapper`** 即可得到
> `songmid`/`media_mid` 两个身份载荷 —— 与播放链路需要的字段**完全一致**。
> 但三件事必须按实测来、不能按直觉写：**① 列表接口返回的是 camelCase（`dirId`/`songNum`/`tid`），
> 详情接口的 `dirinfo` 却是下划线（`dirid`/`songnum`）；② 歌单列表接口不是登录接口，`param.uin`
> 才是查询主体；③ 登录态是否还有效只能靠 `GetLoginUserInfo` 判（`code=1000`），
> 其余读接口在无 cookie 时照样返回数据。**

---

## 1. 接口清单

| # | 用途 | module | method | 需要登录 | 实测 code |
|---|---|---|---|---|---|
| 1 | 登录态自证 + 用户信息（昵称/头像） | `music.UserInfo.userInfoServer` | `GetLoginUserInfo` | **是** | 0 / 未登录 **1000** |
| 2 | 会员档位与到期 | `VipLogin.VipLoginInter` | `vip_login_base` | 否（实测匿名也 0） | 0 |
| 3 | 用户歌单列表（含「我喜欢」） | `music.musicasset.PlaylistBaseRead` | `GetPlaylistByUin` | 否（见 §5） | 0 |
| 4 | 收藏（他人）歌单列表 | `music.musicasset.PlaylistFavRead` | `CgiGetPlaylistFavInfo` | 否（须传 `encrypt_uin`） | 0 |
| 5 | 歌单详情 + 歌曲列表（分页） | `music.srfDissInfo.DissInfo` | `CgiGetDiss` | 否 | 0 |
| 6 | 榜单分类 | `music.musicToplist.Toplist` | `GetAll` | 否 | 0 |

### 1.1 请求信封（与 v2.1.0 既定形状逐字一致）

```json
{"comm": {"ct":11,"cv":14090008,"v":14090008,"chid":"10003505","tmeAppID":"qqmusic",
          "QIMEI36":"…","OpenUDID":"…","udid":"…","aid":"…","os_ver":"10","phonetype":"MI 10",
          "uin":"<uin>","authst":"<qqmusic_key>","format":"json"},
 "req": {"module":"…","method":"…","param":{…}}}
```

- UA：`QQMusic 14090008(android 10)`；Referer：`https://y.qq.com/`
- Cookie 头：登录态 cookie（`uin`/`qqmusic_uin`/`qqmusic_key`/`qm_keyst`/`psrf_musickey_createtime`）
- **免签**：不需要 `zzc_sign`（与 `QqClient` KDoc 一致）

### 1.2 关键身份响应字段（`GetLoginUserInfo` → `data.info`）

实测 `data.info` 共 32 个字段，其中：

| 字段 | 用途 | 实测 |
|---|---|---|
| `nick` | **昵称** | `str(len=6)`（已脱敏） |
| `logo` | **头像 URL** | `https://thirdwx.qlogo.cn/…`（微信 CDN，说明该账号走微信登录；已脱敏为形状） |
| `logos` | 头像多尺寸 | `null` |
| `bgPic` / `ifpicurl` | 背景图 | 空串 |

**`uin` 不在这个接口里** —— 它来自 cookie（`QqCookie.uinOf`）。会员信息来自接口 2 的 `data.identity`（`vip`/`svip`/`overdate`/`music_lev_*`）。

> **对既有代码的修正**：`QqApi.fetchProfile()` 当前硬编码 `nick = null, uid = 0L`
> （`QqApi.kt:436-443`，注释写「本仓库没有 qm 账号」）。**这个前提已经不成立**：
> 本探针就是在有真实登录态的设备上跑出来的，`nick` 与 `logo` 都拿得到。v2.2.0 据此补上。

---

## 2. 用户歌单列表（接口 3）

`param = {"uin": "<uin>"}` → `data`

| 响应字段 | 类型 | 说明 |
|---|---|---|
| `v_playlist[]` | array | 歌单列表（**包含「我喜欢」**） |
| `total` / `bFinish` | int / bool | 总数 / 是否已取完 |
| `v_delTid[]` | array | 已删除歌单标记 |

`v_playlist[]` 单条 29 个字段，实测取值：

| 字段 | 类型 | 语义 | 实测样本 |
|---|---|---|---|
| `dirId` | int | **本账号内的目录号** | `201`（我喜欢）、`1`（自建） |
| `tid` | long | **全局歌单 id**（= 详情接口的 `disstid`） | `6364233546`、`8591804616` |
| `dirName` | string | 歌单名 | 已脱敏 |
| `songNum` | int | 歌曲数 | `1`、`22` |
| `createTime` / `updateTime` | long | 创建 / 更新时间（秒） | `1790314465` / `1618134646` |
| `picUrl` / `bigpicUrl` | string | 封面 | `http://y.gtimg.cn/…` |
| `opType` | int | 操作类型标记 | `5` |
| `dirShow` | int | 是否展示 | `1` |
| `status` | int | 状态 | `11` |
| `sortWeight` | int | 排序权重 | `0` / `10001` |
| `uin` | string | 归属账号 | 已脱敏 |
| `nick` / `desc` / `avatar` / `identIcon` / `layerUrl` | string | **实测全为空串**（列表接口不填充） | `""` |
| `invalid` | bool | 是否失效 | `false` |
| `fav_cnt` / `play_cnt` / `comment_cnt` | int | 互动计数 | `0` |

### 2.1 ⚠️ 字段命名陷阱（本轮最重要的方法论产出）

**同一份数据、两套命名**：

| 接口 | 命名风格 | 例子 |
|---|---|---|
| 接口 3 `GetPlaylistByUin` | **camelCase** | `dirId` / `dirName` / `songNum` / `createTime` |
| 接口 5 `CgiGetDiss` 的 `dirinfo` | **下划线** | `dirid` / `songnum` / `host_uin` / `encrypt_uin` |
| 接口 5 `CgiGetDiss` 的 `songlist[]` | **下划线** | `file.media_mid` / `index_cd` / `time_public` |

第 1 轮探针按参考实现（`_qqmusic_ref/QQMusicApi` 用 pydantic `AliasChoices` 把两套名字**都**吃掉）
的别名去读，结果 `dissid`/`dirid`/`songnum` **全部落空拿到 `None`**（第 1 轮原始证据见 `round1/`）。
**照抄参考实现的字段名，在真实响应上会静默拿到 nil。** 这就是「探针先行」的直接价值。

**因此客户端做法**：解析一律走 `optString/optLong` + 显式候选名，且每个字段的取值都要有单测。

---

## 3. 歌单详情与分页（接口 5）

`param`：`{disstid, dirid, tag, song_begin, song_num, userinfo, orderlist, onlysonglist}`

### 3.1 两种寻址方式等价（实测）

| 寻址 | code | 返回 | `dirinfo.id` | `dirinfo.dirid` |
|---|---|---|---|---|
| `disstid=0, dirid=1` | 0 | 5 首 | `8591804616` | `1` |
| `disstid=8591804616, dirid=1` | 0 | 5 首 | `8591804616` | `1` |

**结论**：自建歌单两种都行；「我喜欢」用 `disstid=0, dirid=201` 亦可（见 §4）。
对**他人/收藏**的歌单只能用 `disstid=<tid>`（`dirid` 不是自己的目录号）。

### 3.2 响应字段

| 字段 | 语义 |
|---|---|
| `songlist[]` | 当前页歌曲 |
| `songlist_size` | 本页返回数 |
| `total_song_num` | 歌单总曲数 |
| `hasmore` | 0/1，是否还有下一页 |
| `dirinfo` | 歌单元数据（42 字段） |

`dirinfo` 关键字段：`id`(=tid) / `dirid` / `title` / `songnum` / `picurl` / `ctime` / `mtime` /
`host_uin` / `host_nick` / **`encrypt_uin`** / `creator{musicid,type,singerid,nick}` /
`dirtype` / `disstype` / `owndir` / `role` / `listennum` / `dir_show`

### 3.3 分页机制（实测）

| 用例 | `song_begin` | `song_num` | code | 返回 | `hasmore` |
|---|---|---|---|---|---|
| 第 1 页 | 0 | 10 | 0 | 10 | 1 |
| 第 2 页 | 10 | 10 | 0 | 10 | 1 |
| 大页 | 0 | 500 | 0 | 22（全部） | 0 |
| 大页 | 0 | 1000 | 0 | 22 | 0 |
| 大页 | 0 | 2000 | 0 | 22 | 0 |
| 越界 | total+100 | 10 | 0 | **0** | 0 |
| 末页 | total-5 | 10 | 0 | 5 | 0 |

**逐页游标单调性**（`song_begin` 0,5,10,15,20 / `song_num=5`）：

```
begin=0   -> 5 首 累计 5
begin=5   -> 5 首 累计 10
begin=10  -> 5 首 累计 15
begin=15  -> 5 首 累计 20
begin=20  -> 2 首 累计 22     重叠页 0
```

**结论**：
1. 分页是 **offset + size**（`song_begin` / `song_num`），**不是** page number；
2. `hasmore` 是权威的「还有下一页」判据；末页 `hasmore=0`；
3. **越界不报错**：`code=0` 且返回 0 首 + `hasmore=0`（客户端可安全地把「返回 0 首」当结束）；
4. `song_num` 上限宽松（2000 仍接受），但**服务端会按实际总数截断**；
5. 逐页取不重叠、不丢页。

> ⚠️ **大歌单（500+）未能在真机验证**：该账号最大自建歌单只有 **22 首**，
> 榜单最大 `totalNum=300`，而「造一个 500+ 歌单」属于**写操作**（本版明令禁止）。
> 已实测的是「大 `song_num` 被接受」「游标逐页不重叠」「越界优雅降级」三条；
> **500+ 规模的端到端分页属于未验证项**，见 §8 与发布说明的遗留缺口。

---

## 4. 收藏 /「我喜欢」是否独立 dirId —— **是**

| dirId | code | dirtype | disstype | `dirinfo.id` | songnum | owndir |
|---|---|---|---|---|---|---|
| 200 | 10004 | – | – | 0 | 0 | – |
| **201** | **0** | 1 | **0** | 6364233546 | 1 | 1 |
| **202** | **0** | 1 | **2** | 6878446074 | 30 | 1 |
| **203** | **0** | 1 | **2** | 6462177000 | 30 | 1 |
| 204–215 | 10004 | – | – | 0 | 0 | – |

**结论**：

1. **「我喜欢」是独立且固定的 `dirId = 201`** —— 与参考实现 `like_song(dirid=201)` 的常量一致，
   且**它本身就出现在用户歌单列表 `v_playlist[]` 里**（`dirId=201, dirName="我喜欢"`）。
   所以客户端**不需要**单独发明「我喜欢」入口：列表接口已经把它带回来了，
   用 `dirId == 201` 即可标记 `isFavorite`。
2. **另外存在两个服务端系统目录 `dirId=202` / `203`**（`disstype=2`，各 30 首，
   `owndir=1`，**不出现在 `v_playlist[]` 里**）。
   **它们的语义没有确认** —— 探针只读了分类字段，**没有读取目录名**
   （名字属用户数据，按隐私纪律不落盘，也不靠猜）。
   **v2.2.0 不实现这两个目录。**
3. `code=10004` = 该目录不存在（客户端可据此判「不是我的目录」）。

---

## 5. 登录态语义（第 2、3、4 轮 A/B 对照）—— 三条反直觉结论

### 5.1 歌单列表接口**不是**登录接口

`GetPlaylistByUin` 的 `param.uin` **就是查询主体**。用「不可能映射到真实账号」的非法 uin 白名单
（沿用本仓库 `probe-qq-phone-login.py` 的安全白名单纪律）实测：

| `param.uin` | cookie | code | `v_playlist` |
|---|---|---|---|
| 真 uin | 有 | 0 | 2 |
| 真 uin | **无** | 0 | 2 |
| `1` | 无 | **80030** | 0 |
| `0` | 无 | **80030** | 0 |
| `99999999999999999999` | 无 | **80030** | 0 |
| `-1` | 无 | **80030** | 0 |
| `99999999999999999999` | 有 | **80030** | 0 |

**结论**：非法 uin 一律 `80030` ⇒ **服务端按 `param.uin` 取数**，
**这个接口是「按 uin 公开可读」的，不校验登录态**。

> **隐私提示（必须写进实现约束）**：正因为它是按 uin 公开可读的，
> 客户端**绝不能**把「这个接口返回了数据」当成「登录态还有效」的判据；
> 也绝不应当用它去读非当前账号的 uin。

### 5.2 `authst` 对读接口无影响

| 用例 | `authst` | cookie | code |
|---|---|---|---|
| A 完整 | 真 | 有 | 0 |
| B 票据损坏 | 篡改 | 有 | **0** |
| C 票据清空 | `""` | 有 | **0** |
| D `uin` 与 cookie 不一致 | 真 | 有 | **0**（返回的是 cookie 账号的数据） |

**结论**：**读接口的身份由 cookie 决定，`authst` 缺失/损坏不影响读**。
（`authst` 对**取链** `music.vkey.GetVkey` 才是关键 —— v2.1.4 已单独实测并修复。）

### 5.3 「登录态是否还有效」只能用 `GetLoginUserInfo` 判

真 uin + **无 cookie** 时各读接口的表现：

| 接口 | 无 cookie | 有 cookie |
|---|---|---|
| `GetLoginUserInfo` | **code = 1000** ← **唯一可靠判据** | code = 0 |
| `vip_login_base` | code = 0（照样返回） | code = 0 |
| `CgiGetDiss`（`dirid=201`） | code = 0，n=1 | code = 0，n=1 |
| `GetPlaylistByUin` | code = 0，n=2 | code = 0，n=2 |
| `CgiGetPlaylistFavInfo` | code = 0，n=0 | code = 0，n=0 |

**结论**：**`GetLoginUserInfo` 的 `code == 1000` 是「登录态已失效」的权威信号**；
其余读接口在没有登录态时**照样返回数据**，用它们判登录态会得到假阳性。
客户端降级策略据此设计（见 §6）。

### 5.4 账号切换

- 请求侧：`comm.uin` / cookie 由 `QqCookie.applyIdentity` 注入（**全有或全无**：uin 与票据缺一即不注入）；
- 服务端侧：**cookie 决定返回谁的歌单**（用例 D：`param.uin` 与 cookie 不一致时，
  返回的仍是 cookie 账号的数据 —— 即**不会**因为参数写错而读到别人的私密数据）；
- 客户端侧：**切换账号必须使在途请求作废**（`ownerId` + generation 双判据，见实现文档）。

---

## 6. 得到结论：歌单里的歌曲用什么字段做身份

### 6.1 `songlist[]` 的身份字段（42 字段中的关键 4 个）

| 字段 | 存在 | 用途 |
|---|---|---|
| `id` | ✅ | 数字 `songId`（本应用经 `SourceIds.qqId` 加上 bit62 标志位） |
| `mid` | ✅ | **`songMid` —— 播放身份**（vkey 的 `songmid` 参数、取词、歌词） |
| `file.media_mid` | ✅ | **`mediaMid` —— 文件名身份**（vkey 的 `filename` = `<前缀><media_mid>.<ext>`） |
| 顶层 `media_mid` | ❌ **不存在** | — |
| `strMediaMid` | ❌ **不存在** | — |

### 6.2 ⚠️ `mid ≠ file.media_mid` 是常态（v2.1.0 教训的直接延续）

实测该账号 22 首歌单的第 1 页 10 首，**`mid != file.media_mid` 的有 6 首**：

| `id` | `mid`（songMid） | `file.media_mid` | 相同？ |
|---|---|---|---|
| 263167477 | `002n3u4D2u8DLn` | `002n3u4D2u8DLn` | 相同 |
| 102792543 | `0036avMK009ptj` | `002OVQbr00xbs6` | **不同** |
| 109200888 | `000WSotS3LnGHf` | `0022g3hT3Oa0Nh` | **不同** |
| 102793018 | `002gzBYN1Bvidu` | `000q72xu3gNJcz` | **不同** |
| 257214928 | `002ERy7k3ouIq1` | `002ERy7k3ouIq1` | 相同 |
| 231626112 | `003PzRim0oA5mf` | `0013gdd73ook5y` | **不同** |

「我喜欢」曲目 `id=201455`：`mid=003L8Oq94De6Fy` / `file.media_mid=003u4H921sRR6B` —— 同样**不同**。

**所以「歌单里拿到的身份」与「播放时需要的身份」的映射关系是**：

```
songlist[i].id              → SongItem.id        （经 SourceIds.qqId 加 bit62）
songlist[i].mid             → SongItem.sourceId  （songmid：vkey 的 songmid、取词）
songlist[i].file.media_mid  → SongItem.mediaId   （media_mid：vkey 的 filename）
songlist[i].interval（秒）  → SongItem.duration  （× 1000 转毫秒）
```

**这条映射不需要新写一行代码** —— 既有的 `QqSongMapper.fromSongObject(JSONObject)` 就是
**唯一**做这件事的地方（`QqSongMapper.kt:87-129`，`mediaMidOf()` 是「两个 mid 二选一」的唯一判定点，
`QqSongMapperTest` 已覆盖）。歌单详情解析**必须复用它**，不得另写一套 ——
否则就是「另起一套身份模型」，正是任务书禁止的。

### 6.3 `songtype`

详情 `songlist[]` 同时返回 `type=1`（普通歌曲）与 `songtype=13`。
**取链请求里 `songtype` 传的是 `0`**（`QqRequests.vkey` 既有实现，v2.1.0 起真机验证可用），
与响应里的 `songtype` **不是同一个语义**。**v2.2.0 不动取链参数**，沿用 `songtype=0`。

### 6.4 一致性自检

歌单详情返回的 `songlist[]` 与搜索接口返回的歌曲对象**字段集合同形**
（都是 `id`/`mid`/`file.media_mid`/`singer`/`album`/`interval`/`pay`），
所以经同一个 mapper 出来的 `SongItem` 可以**直接进队列、直接取链、直接取词** ——
这是「按源隔离但不另起身份体系」的结构性保证。

---

## 7. 写操作接口（**仅登记，一次都没有调用**）

| 操作 | module | method |
|---|---|---|
| 创建歌单 | `music.musicasset.PlaylistBaseWrite` | `AddPlaylist` |
| 删除歌单 | `music.musicasset.PlaylistBaseWrite` | `DelPlaylist` |
| 歌单加歌 | `music.musicasset.PlaylistDetailWrite` | `AddSonglist` |
| 歌单删歌 | `music.musicasset.PlaylistDetailWrite` | `DelSonglist` |
| 收藏歌单 | `music.musicasset.PlaylistFavWrite` | `FavPlaylist` |
| 取消收藏歌单 | `music.musicasset.PlaylistFavWrite` | `CancelFavPlaylist` |

依据：`_qqmusic_ref/QQMusicApi/qqmusic_api/modules/songlist.py` 与 `user.py`。
**本版不实现任何写操作**（任务书第 11 条）。脚本里对这几个 module 一次请求都没发。

---

## 8. 「最近播放」是否存在 / 是否可拉取

**结论：没有找到可用的最近播放接口。本版不实现。**

15 个候选 module/method 全部失败：

```
music.musicasset.PlayHistoryRead / GetPlayHistory        → 500003（module 不存在）
music.musicasset.PlayHistoryRead / CgiGetPlayHistory     → 500003
music.playhistory.PlayHistory    / GetPlayHistory        → 500003
music.UserInfo.PlayHistory       / GetPlayHistory        → 500003
music.musicasset.PlayHistoryWrite/ GetPlayHistory        → 500003
music.recommend.PlayHistorySrv   / GetPlayHistory        → 500003
music.musicasset.PlayHistoryRead / GetHistory            → 500003
music.homepage.PlayHistory       / GetPlayHistory        → 500003
music.musicasset.PlayHistoryRead / GetPlayHistoryList    → 500003
music.musicasset.PlayHistorySrv  / GetPlayHistory        → 500003
music.srfDissInfo.DissInfo       / CgiGetPlayHistory     → 40000（方法不存在）
music.musicasset.RecentPlayRead  / GetRecentPlay         → 500003
music.musicasset.PlayHistoryRead / GetRecentPlay         → 500003
music.recent.RecentPlay          / GetRecentPlay         → 500003
music.musicasset.PlayHistoryRead / GetSongHistory        → 500003
```

判别法沿用本仓库 PHASE0 既有方法论（`500003` = module 不存在；`40000` = module 对、method 不存在）。

**唯一线索**：系统目录 `dirId=202` / `203`（`disstype=2`，各 30 首）**可读但语义未确认**。
由于「最近播放」在官方客户端属于**服务端行为数据**，且本版范围明确不含它，
**v2.2.0 不实现最近播放**，并在发布说明中标注为遗留缺口。

---

## 9. 榜单接口的边界（用于尝试 500+ 分页）

`music.musicToplist.Toplist.GetAll` 返回 `group[5]`，每项 `toplist[]`（共 **46** 个榜单，
字段名是 **小写 `toplist`**，不是 `topList`）。

| `topId` | `totalNum` |
|---|---|
| 135 | 300 |
| 26 | 300 |
| 133 | 300 |
| 607 | 299 |
| 503 | 200 |
| 73 | 110 |

**关键实测**：榜单**不能**用歌单详情接口打开 ——
`CgiGetDiss(disstid=135, dirid=0, num=100/500/1000)` 一律 **`code=0` 但返回 0 首**。
榜单是**另一类资源**（走 `music.musicToplist.Toplist.GetDetail`），与歌单详情**不同源不同形**。
因此**榜单不能当作「大歌单」替身**来验证 500+ 分页，而榜单自身的最大规模也只有 300 首。

→ 500+ 分页在本轮**无法真机验证**，如实记录（见 §3.3 的未验证项说明）。

---

## 10. 对实现的直接约束（探针 → 代码）

| # | 约束 | 依据 |
|---|---|---|
| 1 | 歌单列表用 camelCase（`dirId`/`dirName`/`songNum`/`tid`），详情 `dirinfo` 用下划线（`dirid`/`songnum`） | §2.1 |
| 2 | `dirId == 201` ⇒ 「我喜欢」；它本就在列表里，不需要单独入口 | §4 |
| 3 | 歌单详情的 `songlist[]` **必须**经 `QqSongMapper.fromSongObject` 转 `SongItem` | §6.2 |
| 4 | 必须同时保留 `mid` 与 `file.media_mid`（`sourceId` / `mediaId`） | §6.2 |
| 5 | 分页用 `song_begin`/`song_num`，`hasmore` 判结束，返回 0 首也当结束 | §3.3 |
| 6 | 登录态失效判据 = `GetLoginUserInfo` 的 `code == 1000` | §5.3 |
| 7 | 不得用歌单列表接口的成败判登录态（它公开可读） | §5.1 |
| 8 | 缓存 key 必须含 `source + ownerId + playlistId`（双账号隔离） | §5.4 |
| 9 | 不做最近播放、不做 `dirId 202/203`、不做任何写操作 | §8、§4、§7 |
| 10 | 不打印/不落盘 uin、昵称、头像、歌单名、歌名 | 全程纪律 |

---

## 11. 证据文件索引

| 文件 | 内容 |
|---|---|
| `probe-run-20260925-133422.txt` | 第 2 轮完整人读记录（P0–P9） |
| `probe-r3-20260925-133536.txt` | 第 3 轮（目录语义 / 登录态码 / uin 语义 / 大页 / 游标） |
| `probe-r4-20260925-133617.txt` | 第 4 轮（非法 uin 白名单 / 榜单结构 / dirId 200–215） |
| `probe-*.json` | 脱敏后的请求/响应原文 + 字段形状（56 份） |
| `calls-*.json` | 每次请求的台账（module/method/code/http/耗时/落盘文件名） |
| `round1/` | **第 1 轮原始证据**（保留了「按参考实现字段名解析全部落空」的现场） |
| `EVIDENCE.md` | 命令原文、时间、序列号、输出路径 |

> 脱敏规则：`uin` / 昵称 / 头像 / 加密账号 / 歌单名 / 歌名 / 歌手名 一律替换为
> `<redacted:len>` / `<text:len>` / `<url 形状>`；**保留**公开目录 id（`songid`/`songmid`/`media_mid`/`dirId`/`tid`），
> 因为它们是本节结论（身份字段映射）的证据本体，且不指向个人。
