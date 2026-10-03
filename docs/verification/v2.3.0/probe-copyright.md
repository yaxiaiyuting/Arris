# 探针 ①：搜索 / 艺人 / 专辑 / 单曲 四条接口的**版权可用性字段**

> v2.3.0 · 全部为 2026-09-25/26 **匿名**实测（不带 Cookie），非文档推断。
> 复现脚本：`probe-copyright.py`、`probe-copyright-semantics.py`、`probe-copyright-accuracy.py`、
> `probe-judgement-validity.py`；原始 JSON 与逐行报告在 `probe-raw/`。

## 0. 结论先行

| 问题 | 结论 |
|---|---|
| 接口有没有「可播放 / 版权」字段？ | **有，而且是逐曲内嵌的**，但**没有一个是「可播放」的直接布尔**。可用的是 `privilege.pl`（可播最高码率）与 `privilege.st`（状态码）两个整数 |
| 能不能直接标「可播放」？ | **能**，判据 `privilege.pl > 0`：实测 **30/30 命中，假阳性 0** |
| 能不能直接标「无版权」？ | **只能标「服务端显式声明的那一种」**：`st == -200` 或 `noCopyrightRcmd != null`。整池 591 条里只有 2 条，但**零假阳性** |
| 能不能用「取链接口预检」代替？ | **不能**，成本测出来了：单曲一次 **448–515 ms**，搜索一页 30 首 ≈ **14 s** 串行 |
| 关键否定结论 | `privilege.st == -1` **不是**「不可播」：30 首实测 **15 可播 / 15 不可播**，绝不可当判据 |
| ncm 无版权歌曲在搜索阶段返回什么？ | 仍**照常返回**（搜索不隐藏），信息只在 `privilege` / `noCopyrightRcmd` / `originCoverType` 里 |

---

## 1. 字段位置：不在 `result.privileges`，而在**每个 song 对象里**

第一版探针按老文档找 `result.privileges` 数组 —— **不存在**（`result` 只有 `songCount` / `songs`）。
`privilege` 是**逐曲内嵌**的：

```
POST https://music.163.com/api/cloudsearch/pc   {"s":"周杰伦","type":1,"limit":30,"offset":0}
└── result
    ├── songCount: 30
    └── songs[30]
        ├── id / name / ar[] / al{} / dt / fee / copyright / resourceState
        ├── st / cp / pst / ftype / rtype / version / noCopyrightRcmd
        ├── originCoverType / originSongSimpleData{}
        └── privilege { id, fee, payed, st, pl, dl, fl, sp, cp, subp, cs, maxbr,
                        toast, flag, preSell, playMaxbr, downloadMaxbr, maxBrLevel,
                        playMaxBrLevel, downloadMaxBrLevel, plLevel, dlLevel, flLevel,
                        freeTrialPrivilege{}, rightSource, chargeInfoList[] }
```

实测（`probe-raw/ne-search-zhoujielun.json`）：

- `result.privileges` **不存在**；
- `songs[i].privilege` **240/240 全部存在**（`probe-copyright-semantics.txt` 汇总行：
  「总样本 = 240；privilege 缺失 = 0」）。

⇒ **可以按行标注**，不需要额外的批量请求。

四条接口的对照：

| 接口 | `privilege` 在哪 | 实测 |
|---|---|---|
| `POST /api/cloudsearch/pc`（搜索） | `songs[i].privilege` ✅ | 逐曲齐全 |
| `GET /api/v1/artist/{id}`（艺人热歌） | `hotSongs[i].privilege` ✅ | 逐曲齐全（`ne-artist-6452.json`） |
| `POST /api/v3/song/detail`（单曲详情） | 顶层 `privileges[]` ✅ | 与 `songs[]` 同序、等长 |
| `GET /api/v1/album/{id}`（专辑） | 顶层 `privileges[]` ✅ | **需登录**：匿名请求返回 `code:200` 但 `songs: []`（`ne-album-1883703.json` 里 `songs` 条数为 **0**） |

专辑页这一条是本次探针的一个**意外发现**：匿名访问 `api/v1/album/{id}` 拿到的是空曲目表
（响应里只有 `code` 与 `resourceState`），所以「专辑页的版权标注」在未登录时**没有数据可标**，
必须走降级路径（见 §4）。

---

## 2. `privilege.pl` / `privilege.st` 的取值语义（金标准对照）

金标准 = `POST /api/song/enhance/player/url/v1`（与 App 的 `SongUrlFetcher` 同一条取链路径）
在 `standard` 档位能否拿到**非空 `url`**。分层抽样、`random.seed(20260926)`、每层 30 首
（`probe-raw/probe-judgement-validity.txt`）：

| 分层 (st / pl) | 预检数 | 实测可播 | 实测不可播 | fee 分布 | 判定 |
|---|---|---|---|---|---|
| `st = 0`，`pl > 0` | 30 | **30** | **0** | `{0:8, 8:22}` | ✅ **可用：「可播放」的零假阳性判据** |
| `st = 0`，`pl = 0` | 30 | **0** | **30** | `{1:30}` | ✅ 可用：全部是会员专享（匿名身份拿不到链） |
| `st = -1`，`pl = 0` | 30 | **15** | **15** | `{0:9, 1:15, 8:6}` | ❌ **不可用：正负各半** |

各 `st` 取值的样本量（591 条去重池）：`{"0": 515, "-1": 76}`；另在语义探针里见到 `-200` 1 条。

**三条硬结论：**

1. **`pl > 0` ⇒ 一定可播**（30/30，假阳性 0）。
   这是本版唯一敢用于**正向**标注的判据。它的语义是「该账号在该曲上的可播最高码率」——
   带上 Cookie 时反映的是**这个账号自己的权益**，所以文案应当写成「当前账号可播放」而不是
   「有版权」（版权与账号权益是两件事）。
2. **`st == 0 && pl == 0` ⇒ 当前身份拿不到链**（30/30）。实测这 30 首 `fee` 全是 `1`（VIP 专享），
   所以这一档的正确解释是**「受限 / 需要会员」**，不是「无版权」。
3. **`st == -1` 一律沉默**。它是「付费相关」的模糊码：`fee=0` 的伴奏/纯音乐大量落在这一档，
   而它们**匿名就能播**。若按「`st < 0` ⇒ 不可播」标注，会白灰掉 15/30 首能放的歌。

### 小样本对比（第一轮，`probe-copyright-accuracy.txt`）

第一轮只抽 12 首/层，得到 `pl > 0 ⇒ 可播` **12/12**、判据 C（`st==-200` 或 `ncr`) **0 假阳性**。
第二轮把每层加到 30 首后结论不变 —— 两轮一起构成证据。

---

## 3. 「无版权」到底能不能标？能，但只有两种情况

**唯一可信的判据**：`privilege.st == -200` **或** `noCopyrightRcmd != null`。

实测形如（`probe-copyright-semantics.txt`）：

```
晴天 (钢琴版)        st=-200  pl=0  fee=1  noCopyrightRcmd={"typeDesc":"其它版本可播","type":2}
                                                                   ↑ 实测取链：url 为空
Shake It Off        st=0     pl=0  ncr={"typeDesc":"其它版本可播","type":2}   （Taylor Swift 搜索）
Back To December    st=0     pl=0  ncr={"typeDesc":"其它版本可播","type":2}
```

- `noCopyrightRcmd` 的字面意思是「**无版权时可推荐的替代**」，它的 `typeDesc` 就是给用户看的
  一句话（实测取值 `其它版本可播`）。它在整池 **591 条里只出现 2 次（0.3%）** ——
  ncm 只在**确实需要给替代品**的时候才下发它，所以它**不会**覆盖所有无版权曲目。
- `st == -200` 更罕见（1 条样本）。

**因此：**
- 有 `-200` / `ncr` ⇒ 标 **「无版权」**（诚实、零假阳性）；
- 没有 ⇒ **不标「可播放」也不标「无版权」**，只在 `pl > 0` 时标「可播放」，其余沉默。

`ncr.typeDesc` 是**服务端给的原文**，可以直接展示（本版选择展示为固定的本地化文案
「无版权 · 其它版本可播」，不用它当唯一文案——它是中文，而本应用有 8 种语言）。

---

## 4. 「点进去才发现播不了」的降级闭环

统计出来的规模：在 90 首分层样本里，**实测不可播 45 首**
（`0/pl=0` 30 首 + `-1/pl=0` 15 首），其中：
- 30 首能被 `st==0 && pl==0` 判出来 ⇒ 标**「需会员」**（免费/vip 都看得懂）；
- 15 首（`st==-1`）**判不出来** ⇒ 必须靠**播放失败时**的提示兜底。

所以本版的诚实边界写成两条：

1. **搜索阶段**：只标能确证的（可播放 / 需会员 / 无版权），其余留白 —— 不猜；
2. **播放失败时**：如果这首歌在**另一个音源**上也有候选，提示「此源无版权，可切另一源」并给出切换入口
   （`PlaybackGuard` 已有的「失败处理必须有界」约束依然生效：提示本身不重试、不跳歌）。

### 取链预检的成本（实测）

| 调用 | 观测耗时 |
|---|---|
| `player/url/v1`（单曲，standard） | 448 / 451 / 453 / 455 / 456 / 457 / 463 / 464 / 469 / 470 / 473 / 474 / 475 / 476 / 478 / 479 / 480 / 483 / 486 / 492 / 493 / 494 / 495 / 503 / 505 / 508 / 515 / 517 / 1579 ms |
| 中位数 | ≈ **475 ms** |
| 一页 30 首串行 | ≈ **14.3 s** |
| 一页 30 首 6 并发 | ≈ **2.4 s**，但会给取链接口带来 30 倍瞬时压力 |

⇒ **不采用预检**。这不是「做不做得到」的问题，是「每翻一页搜索都额外打 30 个取链请求」——
既慢又像刷接口。

---

## 5. 对本版的直接输入（特性 C 的判据表）

| 展示标签 | 判据 | 数据来源 | 实测依据 |
|---|---|---|---|
| 可播放 | `privilege.pl > 0` | 搜索/艺人/单曲的逐曲 `privilege` | 30/30，假阳性 0 |
| 需会员 | `st == 0 && pl == 0 && fee ∈ {1,4}` | `privilege` + `fee` | 30/30 不可播，fee 全为 1 |
| 无版权 | `st == -200` 或 `noCopyrightRcmd != null` | 逐曲字段 | 零假阳性（样本 2 条 + 1 条） |
| **不标任何东西** | 其余全部（含 `st == -1`） | — | `st==-1` 层 15/15 混合 |

`fee` 与会员的既有映射（v2.1.4 的 `TrackAccess`）**保持不变**，本版只是在它之上补
「可播放」与「无版权」两个**新**的可确证标签；`TrackAccess` 仍然是排序用的那一个判据。

---

## 6. 未验证 / 边界（如实）

1. **金标准是匿名请求**。带 Cookie 的登录态（尤其 VIP 账号）`pl` 的取值会变宽，
   「`pl > 0` ⇒ 可播」这一条没有在登录态下重测（本探针刻意不带 Cookie，理由见文件头）。
   风险方向是**假阴性变小**（更多歌被标成可播放且确实可播），不是假阳性。
2. **专辑页匿名拿不到曲目表**（`songs: []`），所以专辑页的版权标注只在已登录时有数据；
   未登录时整页降级为「不标注」，不做任何猜测。
3. `st == -200` 只有 1 条样本、`noCopyrightRcmd` 只有 2 条 —— 判据方向（零假阳性）可信，
   **覆盖率**不可信，故文案上不做「本曲无版权」以外的任何断言。
4. qm 一侧**没有**任何等价的「可播放 / 无版权」字段，见 `probe-source-attribution.md` §3。
