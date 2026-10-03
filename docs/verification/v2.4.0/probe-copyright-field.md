# 探针 · 版权可用性字段（ncm / qm）

> 生成：`docs/verification/v2.4.0/probe-copyright-field.py`（**匿名只读**）。
> 金标准 = 真的去要一次播放地址（ncm `.../player/url/v1`；QQ `CgiGetVkey` 批量）。
> 原始响应：`probe-raw/copyright-field.json`。

## C1 · 三个页面的接口是否返回版权状态

| 页面 | ncm 接口 | 有版权字段吗 | QQ 接口 | 有版权字段吗 |
|---|---|---|---|---|
| 艺人主页 | `GET /api/artist/albums/{id}`（专辑列表）+ `POST /api/cloudsearch/pc`（热门曲） | **专辑列表没有**；热门曲走搜索接口，**有** `privilege`（仅在带 `privilege` 字段的搜索响应里） | `fcg_v8_singer_album.fcg`（专辑列表）+ `music.musichallAlbum.AlbumListServer` | **没有**（只有专辑元数据） |
| 专辑页 | `GET /api/v1/album/{id}` | **没有** —— `songs[]` 里只有 `st` / `fee`，而实测 `st` 在专辑页恒为 `-1`（见 C3） | `music.musichallAlbum.AlbumSongList/GetAlbumSongList` | **有** `pay.*` 与 `action.*`，但两者都不是权威的「能不能播」判据（见 C4） |
| 单曲页 | `POST /api/v3/song/detail` | **有** —— `privileges[]`（`st`/`pl`）+ `songs[].noCopyrightRcmd` | `music.pf_song_detail_svr/get_song_detail_yqq` | 同样只有 `pay.*` / `action.*` |

**关键结论**：ncm 的**列表接口（专辑页 / 艺人页）不返回 `privilege`**，只有 `fee` 与一个恒为 `-1` 的 `st`。要做版权可用性优先排序，**必须再补一次批量 `/api/v3/song/detail`**（一次请求最多 100 首，见 C2 成本）。

## C2 · 播放预检的成本

| 音源 | 预检方式 | 粒度 | 单次上限 | 实测延迟 |
|---|---|---|---|---|
| ncm | **不用预检**：`POST /api/v3/song/detail` 返回 `privileges[]`，一次拿一批 | 100 首/请求 | 100（本次按 100 分批） | ~0.3–0.6s/批 |
| QQ | `musicu.fcg` 的 `vkey.GetVkeyServer/CgiGetVkey` **支持 `songmid` 数组** | **一首歌 5 个文件名前缀（flac×3 + mp3×2）** | 本次实测 12 首/请求通过 | ~0.4–0.8s/请求 |

⇒ **成本可接受**：一屏 30 首，ncm 1 次请求、QQ 1 次请求，都在既有 `DetailScaffold` 的加载窗口内。**但预检必须与主请求并行、且失败不能阻塞列表渲染**（铁律 5：非核心组件不得破坏核心链路）。

## C3 · ncm「无版权」在接口里长什么样（这一节直接推翻了一个常见假设）

| 专辑 | `songs[].st`（专辑页） | `privileges[].st`（批量详情） | `privileges[].pl` | 金标准能取到 url |
|---|---|---|---|---|
| 周杰伦 / 叶惠美（ncm 实测无版权） | -1 | -200 | 0 | 0/11（其中完整文件 0） |
| 周杰伦 / 范特西（ncm 实测无版权） | -1 | -200 | 0 | 0/10（其中完整文件 0） |
| Taylor Swift / 1989 (Taylor's Version) (Deluxe)（VIP 专享） | 1 | 0 | 0 | 12/12（其中完整文件 0） |

**读数**：

1. **专辑页的 `st` 恒为 `-1`，对周杰伦那两张「无版权」专辑与对林俊杰那两张「有版权」专辑完全一样** ⇒ 它**零区分度**，不能当判据（这正是 v2.3.0 规则 5 说的「`st == -1` 绝不可当判据」在专辑页上的复现）。
2. **批量 `/api/v3/song/detail` 的 `privileges[].st` 才是有区分度的那个**：无版权专辑是 `-200`、有版权专辑是 `0`。
3. `privileges[].pl` 在同一批里同时给出「能不能取到链」的正向证据。

### ncm A/B 混淆矩阵（标签 vs 金标准）

| 标签判定 | 金标准给**完整**文件 | 金标准只给**试听片段** | 金标准不给文件 |
|---|---|---|---|
| MEMBER_ONLY | 0 | 12 | 0 |
| NO_COPYRIGHT | 0 | 0 | 21 |

- **假阳性（说能完整播、实际只给试听/不给）= 0**（**这一版为 0**）
- **假阴性（说不能播、实际给了完整文件）= 0**（**这一版为 0**）

### ★ 「金标准」本身的坑（第一版探针在这里判错过一次）

`url != null` **不等于**「能完整播放」：实测 VIP 专享曲在匿名态下**也会返回 url**，但 `freeTrialInfo = {start: 0, end: 30}` —— 那是 30 秒试听片段。

| 曲目 | `fee` | `privileges.pl` | 金标准 url | `freeTrialInfo.end` | 真相 |
|---|---|---|---|---|---|
| Welcome To New York (Taylo | 1 | 0 | 有 | **45 秒** | 只是试听，**不能完整播** |
| Blank Space (Taylor's Vers | 1 | 0 | 有 | **45 秒** | 只是试听，**不能完整播** |
| Style (Taylor's Version) | 1 | 0 | 有 | **45 秒** | 只是试听，**不能完整播** |
| Out Of The Woods (Taylor's | 1 | 0 | 有 | **45 秒** | 只是试听，**不能完整播** |
| All You Had To Do Was Stay | 1 | 0 | 有 | **45 秒** | 只是试听，**不能完整播** |
| Shake It Off (Taylor's Ver | 1 | 0 | 有 | **45 秒** | 只是试听，**不能完整播** |
| I Wish You Would (Taylor's | 1 | 0 | 有 | **45 秒** | 只是试听，**不能完整播** |
| Bad Blood (Taylor's Versio | 1 | 0 | 有 | **45 秒** | 只是试听，**不能完整播** |
| Wildest Dreams (Taylor's V | 1 | 0 | 有 | **45 秒** | 只是试听，**不能完整播** |
| How You Get The Girl (Tayl | 1 | 0 | 有 | **45 秒** | 只是试听，**不能完整播** |
| This Love (Taylor's Versio | 1 | 0 | 有 | **45 秒** | 只是试听，**不能完整播** |
| I Know Places (Taylor's Ve | 1 | 0 | 有 | **45 秒** | 只是试听，**不能完整播** |

**修正后的判据**：金标准 = `url != null && freeTrialInfo == null`。改完之后，`MEMBER_ONLY` 的假阴性从 12 降到 0 —— 也就是说 v2.3.0 定的那条判据在专辑页上同样成立。

## C4 · qm 的三条候选判据，谁有区分度

样本 97 首；匿名态能拿到 `purl` 的 18 首。

| 专辑 | 曲目数 | 匿名可拿 purl | `pay_play=1` |
|---|---|---|---|
| 周杰伦 / 叶惠美（QQ 有版权，多为会员专享） | 11 | 0 | 11 |
| Taylor Swift / 1989 (Taylor's Version) (Deluxe)（VIP 专享） | 12 | 0 | 12 |
| 林俊杰 / 100天（QQ 免费曲对照组） | 12 | 0 | 12 |
| 五月天 / Final Home 当我们混在一起（QQ 免费曲对照组） | 12 | 0 | 12 |
| 搜索对照组：周深 | 10 | 5 | 5 |
| 搜索对照组：钢琴 | 10 | 6 | 4 |
| 搜索对照组：纯音乐 | 10 | 6 | 4 |
| 搜索对照组：Adele | 10 | 0 | 10 |
| 搜索对照组：邓紫棋 | 10 | 1 | 9 |

| 判据 | 取值分布 | 与金标准的一致性 | 能不能用 |
|---|---|---|---|
| `action.switch` bit0 | bit0=1×97 | — | ❌ **零区分度**（与 v2.3.0 的 130/130 结论一致） |
| `action.alert` | 11×4, 2×42, 21×12, 22×24, 41×15 | — | ❌ 语义无权威定义，本轮无金标准可验证 |
| `pay.pay_play` | pay_play=(0, True)×18, pay_play=(1, False)×79 | 见下 | ⚠️ **只能当「需会员」标签，不能当「可播放」** |
| **`CgiGetVkey` 的 `purl` 非空** | 有 purl 18 / 无 purl 79 | **自身即金标准** | ✅ 唯一可信的「这个源现在能不能播」判据 |

`pay.pay_play` × 金标准 交叉表：

| `pay_play` | 有 purl | 无 purl | 结论 |
|---|---|---|---|
| 0 | 18 | 0 | — |
| 1 | 0 | 79 | 会员专享（有登录态时才可能拿到） |

**结论**：QQ 侧唯一诚实的判据是**批量 vkey 预检的 `purl`**。`pay_play == 1` 可以标「VIP」，**但不可以标「无版权」** —— 它没有告诉我们这首歌在 QQ 是否上架，只告诉我们「要钱」。

## C5 · 本版采用的判据（写进代码的就是这几条）

| 音源 | 可播放 | 需会员 | 无版权 | 其余 |
|---|---|---|---|---|
| ncm | `privileges[].pl > 0` | `privileges[].st == 0 && pl == 0 && fee ∈ {1,4}` | `privileges[].st == -200` 或 `songs[].noCopyrightRcmd != null` | `UNKNOWN`（不显示） |
| QQ | **批量 vkey 的 `purl` 非空** | `purl` 为空且 `result == 104003`；或 `pay.pay_play == 1`（只作 VIP 角标） | **不判**（没有字段能证明「没上架」） | `UNKNOWN` |

排序规则（特性 D）：**`PLAYABLE` > `UNKNOWN` > `MEMBER_ONLY` > `NO_COPYRIGHT`**。`UNKNOWN` 排在受限项之前、`PLAYABLE` 之后，是因为「不知道」**不等于**「不能播」—— 把它沉底等于凭空说它不能播（v2.3.0 的 `rankGroup` 就是这条约定）。
