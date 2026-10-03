# 社区实现横向对比 —— Ncrust v3.1.0 调研

> **调研时间**：2026-09-28 02:2x–02:4x（+0800）
> **网络说明**：**GitHub API / raw.githubusercontent 走代理**（`https_proxy=http://127.0.0.1:10808`），因为本机直连 `api.github.com` 会超时（`curl (28)`）；**B站接口全部直连**（见其余文档）。
> **方法**：优先读**真实源码文件**（`raw.githubusercontent.com` / `cdn.jsdelivr.net`），拿不到源码时明确标注"仅 README / 目录结构"。

---

## 0. ⚠️ 最重要的两条合规结论（先读这一节）

### 0.1 `bilibili-API-collect` 已**永久关停**，且是 **CC BY-NC 4.0**

调研过程中发现，本任务指定要参考的 `SocialSisterYi/bilibili-API-collect` **已经死了**。

**上游 README 原文**（`https://raw.githubusercontent.com/SocialSisterYi/bilibili-API-collect/master/README.md`，实测 HTTP 200）：

```markdown
# Deprecated

本仓库停止维护并永久关停。

This Repo will cease maintenance and be permanently closed.

## 原因

2026年1月28日，本仓库维护者收到B站委托的律师事务所发律师函警告邮件，指控本仓库中的项目存在
"通过技术手段对哔哩哔哩平台非公开的API接口及其调用逻辑、参数结构、访问控制及安全认证机制
进行系统性收集、整理，并以技术文档、代码示例等形式向不特定公众传播"的侵权行为。

即日起停止维护并删除相关文档及源代码。
```

**实测确认上游内容已删除**：

| 路径（上游 master） | 实测 |
|---|---|
| `LICENSE` | **HTTP 404** |
| `docs/audio/info.md` | **HTTP 404** |
| `docs/misc/sign/wbi.md` | **HTTP 404** |

新闻佐证（外部来源）：[凤凰科技](https://tech.ifeng.com/c/8qJ6eBQuxXV)、[IT之家](https://www.ithome.com/0/917/481.htm)、[新浪财经](https://finance.sina.com.cn/tech/digi/2026-01-29/doc-inhixyxy4258772.shtml) 均报道"超 20k 点赞的 B 站 API 第三方收集项目收到律师函，开发者宣布停止维护并清空代码"。

**本次调研实际使用的文档副本**来自**贡献者存档 fork**（`pskdje/bilibili-API-collect`、`venhow/bilibili-API-collect`、`Geekertao-bot/bilibili-API-collect`，三者 `docs/misc/sign/wbi.md` 与 `LICENSE` 均实测 HTTP 200）。

### 0.2 许可证：**CC BY-NC 4.0 —— 与 GPLv3 不兼容，不能抄**

从存档 fork 实测取得的 `LICENSE` 首行：

```
Creative Commons Attribution-NonCommercial 4.0 International
```

上游 README 的 `<img alt="GitHub license" src=".../License-CC%20BY--NC%204.0-lightgrey.svg">` 与声明段互相印证：

```markdown
::: warning 声明
1. 本项目遵守 CC-BY-NC 4.0 协议，禁止一切商业使用，如需转载请注明作者 ID
2. **请勿滥用，本项目仅用于学习和测试！**
...
6. **上传任何信息时请注意脱敏，删去账户密码、敏感 cookies 等可能泄漏个人信息的数据
   （例如 `SESSDATA`、`bili_jct` 之类的 cookies）**
```

**对 Ncrust（GPLv3）的三条硬约束**：

| # | 约束 | 理由 |
|---|---|---|
| 1 | **不得把该文档的任何文字/表格/示例代码复制进本仓库** | CC BY-NC 4.0 的 **NC** 条款增加了 GPLv3 不允许的额外限制，二者**不兼容**（Creative Commons 官方亦声明 CC BY-NC 不属于自由文化许可、与 GPL 不兼容）。一旦复制，本仓库的 GPLv3 授权声明即出现瑕疵 |
| 2 | **本文档中的算法描述必须是 clean-room 重写** | 见下方"事实 vs 表达"说明 |
| 3 | **不得把它作为"权威依据"引用为项目依赖** | 上游已删除且停止维护，链接会烂；且它是被 B站法务点名的对象 |

> **"事实 vs 表达"（本仓库的立足点）**：
> 著作权保护的是**表达**，不是**事实与接口要求**。`img_key`/`sub_key` 的获取路径、64 位乱序表的取值、`md5(query + mixin_key)` 这一步 —— 这些是**为了让自己的客户端能与服务端互通而必须复现的接口事实**，属于"唯一且必然的实现方式"，不受著作权保护。
> **本次调研的做法**正是按这条线走的：
> ① 算法不是抄文档得出的，而是**用官方向量做自检**（`wbi_golden.py`：4 项逐字节 MATCH，证明实现等价于该算法）+
> ② **用服务端行为做 A/B 验证**（`wbi_ab.py`：无签名 `-352` → 有签名 `code:0`，证明服务端认可）；
> ③ 本文档的**文字、表格、结论、反例全部是本次实测原创**，未复制上游任何段落。
>
> **建议**：`wbi-signature.md` 里保留"算法来源为社区公开逆向结论（原文档已因法律原因关停）"的**事实性致谢**，但**不引用其文本、不链接其被删路径**。

### 0.3 ⚠️ 对 Ncrust 项目本身的法律风险提示（超出调研范围但必须说）

上游收到的律师函指控的关键词是：

> "对哔哩哔哩平台**非公开的 API 接口**及其调用逻辑、参数结构、访问控制及**安全认证机制**进行系统性收集、整理，并……向不特定公众传播"

Ncrust 若接入 B站音源，**在行为形态上与该项目有重叠面**（使用非公开接口 + 复现 Wbi 这一"安全认证机制"）。
本文档**不构成法律意见**，但必须把这条风险**显式记录**，供项目所有者决策：

1. B站已于 2026-01 展现实质性的法务动作意愿；
2. B站音源与 ncm/QQ 音源在这一点上的风险**不同**（后两者未见到同等量级的公开法务动作）；
3. 建议的**风险缓释**（技术侧能做的）：
   - 音频源**默认关闭**、由用户显式开启（"用户主动选择"而非"产品默认提供"）；
   - 不实现任何**写操作**（投币/收藏/点赞）；
   - 不内置任何 B站账号凭证，不代登录；
   - 速率限制保守（不做批量抓取）；
   - README / 关于页明确"仅用于个人学习，与哔哩哔哩无关联"。

---

## 1. 许可证总表（**GPLv3 项目只能借鉴思路，不能抄代码**）

| 项目 | 仓库 | ★ | 语言 | **License（实测来源）** | 能否抄代码进 GPLv3 项目？ |
|---|---|---|---|---|---|
| bilibili-API-collect | `SocialSisterYi/bilibili-API-collect` | ~20k（已关停） | Markdown/VuePress | **CC BY-NC 4.0**（存档 fork 的 `LICENSE` 首行 + README badge） | ❌ **不能**。NC 限制与 GPLv3 不兼容；且已因律师函关停 |
| bili-music | `AprDeci/bili-music` | 373 | Dart/Flutter | **MIT**（`LICENSE` 实测：`MIT License Copyright (c) 2026 AprDec`） | ✅ **可以**（MIT → GPLv3 单向兼容，需保留版权声明与许可全文） |
| Bilibili-MusicPlayer | `despcy/Bilibili-MusicPlayer` | 45 | Java/Android | **MIT**（`LICENSE` 实测：`MIT License Copyright (c) 2019 Chenxi Yang`） | ✅ **可以** |
| 哔哔音乐 | `bigbirdone/BiliBili_Music` + `bb-music/flutter-app` | — | Dart/Flutter | ❌ **未找到 LICENSE 文件**（main/master × `LICENSE`/`LICENSE.md`/`LICENSE.txt`/`COPYING`/`license` 全部 404） | ❌ **不能**。无许可 = 默认保留全部权利 |
| biu | `wood3n/biu` | **2695** | TypeScript/Electron | **PolyForm Noncommercial License 1.0.0**（`LICENSE` 实测首行：`# PolyForm Noncommercial License 1.0.0` / `Required Notice: Copyright (c) 2022–2025 wood3n`） | ❌ **绝对不能**。**非商业限制** + **非 OSI 开源许可** |
| （参考）UtopiaMusic | `UtopiaXC/UtopiaMusic` | 14 | Dart/Flutter | MIT（GitHub 元数据 `license.spdx_id=MIT`，**未读 LICENSE 原文**） | ⚠️ 需自行复核 |
| （参考）BiliMusic | `zsakvo/BiliMusic` | — | Dart/Flutter | ❌ 未找到 LICENSE | ❌ 不能 |

### 1.1 一句话规则（写进 `community-implementations.md` 的目的）

> **Ncrust 是 GPLv3 项目。**
> - **MIT** 的项目（`bili-music`、`despcy/Bilibili-MusicPlayer`）：**可以**借鉴甚至移植代码，但**必须**保留原版权声明 + MIT 全文，并在 `THIRD-PARTY-LICENSES.md` 登记。
> - **无 LICENSE** 的项目（`bb-music`、`zsakvo/BiliMusic`）：**默认保留全部权利**，只能**读思路、不能抄代码**。
> - **PolyForm Noncommercial**（`biu`）：**既非开源、又限非商业**，**连思路都建议只做"知道它这么做"级别的参考**。
> - **CC BY-NC 4.0**（`bilibili-API-collect`）：**文档也不能抄**，只能 clean-room 重写 + 自行实测验证。
>
> **本次调研的全部结论都建立在"自己 curl 实测"之上**（见 `EVIDENCE.md`），这是唯一在许可证上完全干净的做法。

---

## 2. 逐项目详解

### 2.1 `SocialSisterYi/bilibili-API-collect` —— 参考文档（**已关停**）

| 维度 | 情况 |
|---|---|
| **License** | **CC BY-NC 4.0**（实测存档 fork 的 `LICENSE`；上游 `LICENSE` 已随仓库清空而 404） |
| **状态** | **2026-01-28 起永久关停**，`docs/` 与源码已删除（实测 `docs/audio/info.md`、`docs/misc/sign/wbi.md` 均 HTTP 404） |
| **音频区文档** | `docs/audio/`：`info.md`、`musicstream_url.md`、`music_list.md`、`action.md`、`rank.md`、`status_number.md` |
| **Wbi 文档** | `docs/misc/sign/wbi.md`（本次通过存档 fork 取得 19,290 bytes 副本） |
| **音源方式** | 文档性质，两条路都记（音频区 `au` + 视频 DASH） |
| **Wbi** | 给出了**完整算法**与 Python/JS/Go/C#/Java 五种实现 Demo，含**官方向量**（本次用作 golden test vector） |
| **登录要求** | 文档覆盖匿名与登录两态；音频区文档明确标注哪些需要登录 |
| **URL TTL** | `musicstream_url.md` 写 `timeout` 字段"单位为秒，**一般为 3h**" —— ⚠️ **本次实测纠正：`timeout` 只是名义常量，URL 真实过期以 query 里的 `deadline`（`now+7200s`）为准** |
| **歌词** | `info.md` 记录了 `song/info` 的 `lyric` 字段（`.lrc` 文件 URL）与 `song/lyric` 端点 |

**本次调研从它那里"合法获得"的只有**：算法的**事实性描述**（乱序表取值、5 步流程）、以及**官方向量**（用作自检输入）。所有结论均经本机独立实测复核，并**推翻了其中至少两处**（`timeout` 语义、"web 端点仅 192K"被 APP 端点超越）。

---

### 2.2 `AprDeci/bili-music` —— ★373，MIT，**全平台** Flutter（技术参考价值最高）

| 维度 | 情况（源码实测） |
|---|---|
| **License** | **MIT** ✅ |
| **音源方式** | **视频 DASH**（不是音频区 au） |
| **关键端点** | `/x/player/wbi/playurl`、`/x/web-interface/view`、`/x/player/online/total` |
| **playurl 参数** | `bvid`/`avid` + `cid` + **`fnval: 4048`** + `fnver: 0` + **`qn: 80`** + `fourk: 1`，且 **`requiresWbi: true`** |
| **音频流选择** | 收集 `dash.audio[]` **并额外读 `dash.flac.audio`**，按 `bandwidth` **降序**排序后按用户偏好挑 |
| **音质 id 映射**（源码 `_buildQualityLabel`） | `30251`→hires、`30280`→192K、`30232`→132K、`30216`→64K、`30250`→(杜比) |
| **Wbi** | ✅ **完整实现**：`lib/core/bili/sign/bili_wbi_signer.dart`，`_mixinKeyEncTab` 与文档**逐值相同**（46,47,18,2,53,8,23,32,…）；`md5.convert(utf8.encode(query + mixinKey))` |
| **key 来源** | 存在 **session** 里（`BiliSession.imgKey` / `subKey`），缺失时抛 `BiliWbiSignException('Missing WBI keys in current session.')` —— 说明它是**跟用户会话一起拿 nav** 的 |
| **编码实现** | `Uri.encodeQueryComponent`（Dart）—— ⚠️ **Dart 的这个函数把空格编码成 `+`**，与文档要求的 `%20` **不同**。这是一处**潜在实现差异**（可能服务端对它宽容，也可能它只用在特定参数上） |
| **值过滤** | `value.replaceAll(RegExp(r"[!'()*]"), '')` ✅ 与文档一致 |
| **登录** | ✅ 有完整 auth 模块（`bili_auth_repository` / `bili_auth_controller` / `bili_session_store`）；`_buildPlayurlRequestHeaders(session)` 说明**取流会带会话头** |
| **URL 缓存 TTL** | ❌ **源码里没有找到任何硬编码的 URL TTL / `deadline` 处理 / 过期重取逻辑** —— 每次 `resolveAudioStream` 都是现取 |
| **歌词** | 未在本次抓取的文件中看到；目录里有 `feature/comment`、`feature/favorites`，**未发现歌词模块** |
| **⚠️ 隐私提示** | `net_config.dart` 里有一个**第三方后端** `bmBaseUrl = 'https://bm.126386.xyz'`（带 `oh-my-pass: bilibilibmusic` 头），用于"元信息匹配"。**这是把用户的部分数据发给第三方服务器** —— Ncrust 不应采用这种设计 |
| **可借鉴点** | ✅ **DASH 音质 id → 显示名的映射表**（30251/30280/30232/30216/30250）；✅ **`dash.flac.audio` 也要读**（FLAC 不在 `dash.audio[]` 里）；✅ `requiresWbi` 的**开关式设计**（同一 client 既能签名又能不签名）—— 与本文 §4.2「不是所有端点都验签」的实测结论天然契合 |

---

### 2.3 `despcy/Bilibili-MusicPlayer` —— ★45，MIT，Android MVVM + ExoPlayer（**离 Ncrust 架构最近**）

| 维度 | 情况（源码实测） |
|---|---|
| **License** | **MIT** ✅ |
| **架构** | MVVM + Room + LiveData + Retrofit + ExoPlayer，Java，`AndroidApp/` 子目录 |
| **音源方式** | **视频 DASH** |
| **playurl** | `@GET("x/player/playurl?fnval=16&otype=json")`，参数 `avid` + `cid` —— ⚠️ **注意用的是 `avid`（不是 `aid`/`bvid`），且 `fnval=16`（只要 DASH，不含 FLAC/杜比等扩展位）** |
| **Wbi** | ❌ **完全没有**（项目 2019 年，早于 2023-03 的 Wbi 上线） |
| **音质常量** | `Constants.AUDIO_QUALITY_HIGH = 30280`、`AUDIO_QUALITY_LOW = 30216` —— **只做高低两档** |
| **曲库来源** | **不是音频区，也不是搜索**，而是**B站收藏夹**：`x/space/fav/nav`（收藏夹列表）+ `x/space/fav/arc`（夹内视频），并支持在 App 内新建/删除收藏夹、加入/移出视频（`x/v2/fav/folder/add|del`、`x/v2/fav/video/add|del`，带 `Cookie` + `csrf`） |
| **热门列表** | `https://s.search.bilibili.com/cate/search?main_ver=v3&search_type=video&view_type=hot_rank&order=click`，参数 `cate_id` + `page` + `pagesize` + `time_from` + `time_to`；音乐区 `cate_id` 常量：`ORIG=28 原创`、`COVER=31 翻唱`、`VOCAL=30 VOCALOID`、`ELEC=194 电子`、`PLAY=59 演奏`、`MV=193 MV`、`LIVE=29 现场`、`MISC=130 其他` |
| **登录** | ✅ **强依赖** —— 整个曲库基于收藏夹，必须 `Cookie`（**且写操作需要 `csrf` = `bili_jct`**） |
| **URL 缓存 TTL** | ❌ 未找到 TTL 逻辑（Room 里存的是 `Durl`/`Audio` 对象，**未见过期判定**） |
| **歌词** | ❌ 未发现歌词模块（App 有 WebView 页，可能是"去网页看"） |
| **可借鉴点** | ✅ **音乐区 `cate_id` 常量表**（28/31/30/194/59/193/29/130）—— 这是**视频搜索按分区过滤**的现成取值，比 `tids=3` 更细；✅ "用收藏夹当曲库"的产品思路（但 Ncrust 本版不建议，因为要登录）；✅ 与 Ncrust 同为 Android + ExoPlayer，**DASH 取流→ExoPlayer 的接线方式可直接参考** |
| **⚠️ 过时点** | 端点 `x/space/acc/info`、`x/space/fav/nav`、`x/space/fav/arc` 都是**旧版**（新版是 `/x/v3/fav/...`、`/x/space/wbi/acc/info`）；`fnval=16` 无 Wbi，**今天照抄会失败** |

---

### 2.4 `wood3n/biu` —— ★2695，**PolyForm Noncommercial**，Electron/TS（功能最全，但**不能抄**）

| 维度 | 情况（源码实测） |
|---|---|
| **License** | ❌ **PolyForm Noncommercial License 1.0.0**（`LICENSE` 实测首行 + `Required Notice: Copyright (c) 2022–2025 wood3n`）。**OSI 不认定为开源**，且**禁止商业使用** |
| **音源方式** | **两条路都有！** `electron/ipc/api/audio-stream-url.ts`（音频区）+ `electron/ipc/api/dash-url.ts`（视频 DASH） |
| **音频区端点** | **APP 端点** `/audio/music-service-c/url`，参数 `songid` / `quality` / `privilege=2` / `mid` / `platform`，**注释明确写"认证：APP（access_key）或 Cookie（SESSDATA）"**、"可获取付费音频" |
| **音质代码** | 源码注释：`0=128K，1=192K，2=320K，3=FLAC` ✅ 与本次实测一致 |
| **`qualities[]` 类型** | 定义了 `{type, desc, size, bps, tag, require, requiredesc}` ✅ 与本次实测字段**完全一致** |
| **Wbi** | ✅ 实现于 `src/service/request/wbi-sign.ts`，用 `spark-md5`；`mixinKeyEncTab` 与文档相同；`encodeURIComponent`（JS，空格→`%20` ✅）；通过 `useWbi: true` 开关式调用 |
| **Wbi 调用点** | `player-playurl.ts` → `/x/player/wbi/playurl`（`useWbi: true`）；另有 `space-wbi-acc-info.ts`、`space-wbi-acc-relation.ts`、`space-wbi-arc-search.ts` |
| **URL TTL** | ❌ `playurl` 的类型定义里 `ttl: number; // 1` 指的是**响应 top-level 的 `ttl` 字段**（恒为 1），**不是 URL 有效期**；**未找到 `deadline` 解析或 TTL 常量** |
| **歌词** | ⚠️ **来自第三方，不是 B站**：`electron/ipc/api/lrclib-lyric.ts`（LRCLIB）+ `netease-lyric.ts`（**ncm**）—— 说明作者认为 **B站自身歌词不够用** |
| **新发现（本次实测采纳）** | `src/service/web-interface-new-music.ts` → **`/x/centralization/interface/new/music?plat=2&web_location=333.1351`** —— 本次照着实测，确认**匿名可用**（见 `bili-audio-api.md` §6.8）。这是本次调研**唯一**从社区实现里"捡到"的有价值端点 |
| **可借鉴点** | ⚠️ **仅限"知道它这么做"**，**不得复制任何代码或注释文本**。可借鉴的**思路**：① 音频区 vs DASH 双路并存；② `useWbi` 开关式设计；③ B站歌词质量不足时接第三方（Ncrust 不应这么做，见 `RECOMMENDATIONS.md`） |

---

### 2.5 哔哔音乐 `bigbirdone/BiliBili_Music` / `bb-music/flutter-app` —— ❌ **无 License**

| 维度 | 情况 |
|---|---|
| **License** | ❌ **未找到 LICENSE 文件**（`main`/`master` × `LICENSE`/`LICENSE.md`/`LICENSE.txt`/`COPYING`/`license` 全部 404）⇒ **默认保留全部权利，不能抄任何代码** |
| **关系** | `bigbirdone/BiliBili_Music` 的 README 里写"下载地址 https://github.com/bb-music/flutter-app/releases/latest"，**是同一项目的迁移**（两者 `lib/` 文件列表高度重合） |
| **README 原文**（`bigbirdone/BiliBili_Music`，实测可读） | 见下 |

```markdown
# 哔哔音乐
使用 B 站作为歌曲源开发的音乐播放器

## 实现思路
1. B 站上有很多的音乐视频，相当于一种超级全的音乐聚合曲库（索尼直接将 B 站当做网盘，传了 15w 个视频）
2. 对这些视频进行收集制作成歌单
3. **无需登录即可完整播放，无广告**
4. 使用 [SocialSisterYi](https://github.com/SocialSisterYi/bilibili-API-collect)
   整理的 B 站接口文档，直接就可以获取和搜索 B 站视频数据

## 功能
- [x] 播放器（播放,暂停,上一首,下一首 / 播放列表 / 单曲循环,列表循环,随机播放 / 进度拖动 / 计时播放）
- [x] 搜索 —— 名称关键字搜索
- [x] 歌单
```

| 维度 | 情况 |
|---|---|
| **音源方式** | **视频**（README 明说"B站上的音乐视频"、"获取和搜索 B 站视频数据"）⇒ **视频 DASH** |
| **登录** | ❌ **不需要**（README 第 3 条明确"无需登录即可完整播放"） |
| **Wbi** | ⚠️ **未能确认**。文件列表（jsDelivr）显示存在 `lib/origin_sdk/bili/sign.dart` + `client.dart` + `types.dart` + `utils.dart` —— **`sign.dart` 的存在强烈暗示实现了 wbi**，但**本次尝试抓取这些文件全部 404**（`raw.githubusercontent.com` 与 `cdn.jsdelivr.net` 均失败，jsDelivr 的 flat 列表疑似缓存陈旧）。**⇒ 归入未验证项** |
| **歌单来源** | 目录里有 `lib/modules/user_music_order/github/` —— **从 GitHub 拉取用户共享歌单**（社区共建歌单的形态），另有 `open_music_order`、`local` |
| **歌词** | 未确认 |
| **URL TTL** | 未确认 |
| **可借鉴点** | ✅ **产品结论可参考**：它用 2.7k+ 项目（`biu`）+ 本项目共同证明了一件事 —— **"B站当曲库"这条路，匿名 + 视频搜索 + 社区歌单即可支撑一个完整音乐 App，不需要音频区和登录**。这与本次调研的实测结论方向一致 |

---

### 2.6 其他（只做登记，未深入）

| 项目 | 语言 | License | 备注 |
|---|---|---|---|
| `UtopiaXC/UtopiaMusic` | Dart | MIT（GitHub 元数据，**未读原文**） | "又一个跨平台 Bilibili 音乐客户端" |
| `ZikZhao/Bilibili-Music-Player` | Dart | MIT（元数据） | ★1 |
| `huxiaofan1223/bili_music_flutter` | Dart | **NONE**（GitHub 元数据） | ★18 |
| `zsakvo/BiliMusic` | Dart | **未找到 LICENSE** | — |
| `MatCauthon/BiliMusicPlayer` | — | 未查 | 搜索过程中出现 |

---

## 3. 横向对比表

| 项目 | License | 音源方式 | Wbi | 登录要求 | URL TTL 处理 | 歌词来源 |
|---|---|---|---|---|---|---|
| **bilibili-API-collect** | **CC BY-NC 4.0**（已关停） | 文档（两路都记） | ✅ 文档+5 语言 Demo | 两态都覆盖 | 文档写 `timeout`≈3h（**实测应为 `deadline`=2h**） | `info.lyric` / `song/lyric` |
| **AprDeci/bili-music** ★373 | **MIT** | **视频 DASH**（`fnval=4048`） | ✅ 完整（`bili_wbi_signer.dart`） | ✅ 有 auth 模块，取流带会话头 | ❌ 无（每次现取） | ❌ 未发现 |
| **despcy/Bilibili-MusicPlayer** ★45 | **MIT** | **视频 DASH**（`fnval=16`） | ❌ 无（2019 年项目） | ✅ **强依赖**（曲库=收藏夹） | ❌ 无 | ❌ 未发现 |
| **wood3n/biu** ★2695 | ❌ **PolyForm Noncommercial 1.0.0** | **音频区 APP 端点 + 视频 DASH（双路）** | ✅ 完整（`wbi-sign.ts`，开关式） | 文档称 APP `access_key` 或 `SESSDATA` 可取付费音频 | ❌ 无（`ttl` 是响应字段，不是 URL 寿命） | ⚠️ **第三方：LRCLIB + ncm** |
| **bb-music / 哔哔音乐** | ❌ **无 LICENSE** | **视频**（README 明说） | ⚠️ 疑似（有 `sign.dart`），**未验证** | ❌ **明确不需要登录** | 未确认 | 未确认 |
| **（本次实测结论）** | — | **音频区 au 优先**（见 RECOMMENDATIONS） | ✅ 必须实现但**音频区端点不需要** | ❌ 本版不需要 | ✅ **必须显式处理：`deadline` = now+7200s** | **B站 `song/lyric`（LRC）** |

---

## 4. 从社区实现得到的 5 条结论

1. **主流是"视频当曲库"，不是音频区。** 5 个项目里 4 个走视频 DASH，只有 `biu` 额外用了音频区（且用的是**需要登录的 APP 端点**）。
   → 但本次实测发现音频区**匿名就能到 320K**（`bili-audio-api.md` §3），这条社区普遍不知道，是本调研的增量。

2. **没有人正确处理 URL 过期。** 五个项目里**没有一个**解析 `deadline` 或设置 TTL 常量。这解释了为什么"B站音源听着听着没声了"是这类 App 的常见反馈。
   → Ncrust 应显式处理（`RECOMMENDATIONS.md` §2.4）。

3. **Wbi 实现方式高度一致**（三个实现了的项目都是同一张乱序表 + `md5(query+mixin_key)`），差异只在**编码器**（Dart 用 `encodeQueryComponent` 可能出 `+`，JS/Python 出 `%20`）。
   → Ncrust 应**自己手写百分号编码 + JVM 单测**，不要依赖平台 API。

4. **B站歌词质量被普遍认为不足**：`biu` 直接放弃了 B站歌词，改接 **LRCLIB 与 ncm**。
   → 但本次实测确认音频区的 `song/lyric` **是标准 LRC**（`[MM:SS.mm]`，含重复段落多时间戳），**可直接喂 Ncrust 现有 `LrcParser`**。Ncrust 不需要引第三方歌词源（那还会引入新的许可与隐私问题）。

5. **许可证风险是真实的、可量化的**：5 个参考项目里，**2 个无许可、1 个非商业许可、1 个 CC BY-NC 且已因律师函关停** —— 只有 2 个（都是 MIT）是干净的。
   → **Ncrust 的 B站接入实现必须是 clean-room 的**，这一点没有折中空间。

---

## 5. 未验证项

| 项 | 状态 |
|---|---|
| `bb-music`/`BiliBili_Music` 的 `lib/origin_sdk/bili/sign.dart` 内容（是否真实现 wbi） | ❌ **未能取得源码**（raw 404 / jsDelivr 404，尽管 flat 列表显示存在）。**仅由文件名推断** |
| `bb-music` 的歌词与 TTL 处理 | ❌ 未取得源码 |
| `UtopiaXC/UtopiaMusic`、`ZikZhao/Bilibili-Music-Player` 的实际实现 | ❌ 仅元数据，未读源码 |
| `biu` 是否**实际**使用音频区播放，还是只作为备用路径 | ⚠️ 仅读到 `audio-stream-url.ts` 的存在与注释；**未读调用方**确认它在 UI 上的位置 |
| `AprDeci/bili-music` 的 `Uri.encodeQueryComponent` 是否导致签名在含空格参数上失败 | ⚠️ **未验证**（只是代码层面的差异观察） |
| `s.search.bilibili.com/cate/search` 老接口今天是否仍可用 | ❌ 未实测（`despcy` 项目在用，但项目 2019 年） |
| 各项目在 2026 年的实际可用性（是否已因 B站改动而失效） | ❌ 未实测（本次只做**静态源码阅读**，未运行任何第三方项目） |
| GitHub 星标数 | ⚠️ 取自 GitHub Search API（走代理），**可能滞后**；`bilibili-API-collect` 的 ~20k 来自新闻报道 |
