# 探针 · 单曲页接口（ncm ↔ qm）

> 生成：`docs/verification/v2.4.0/probe-catalog-api.py`（**匿名只读**）。原始响应：`probe-raw/catalog-api.json`。

## 端点对照

| 能力 | ncm | qm |
|---|---|---|
| 单曲详情（含版权） | `POST /api/v3/song/detail`（`c=[{id}]`，**支持批量**） | `music.pf_song_detail_svr/get_song_detail_yqq`（单首） |
| 播放地址 | `GET /api/song/enhance/player/url/v1?ids&level&encodeType` | `musicu.fcg` → `vkey.GetVkeyServer/CgiGetVkey`（**支持批量**） |
| 歌词 | `POST /api/song/lyric` | `music.musichallSong.PlayLyricInfo/GetPlayLyricInfo` |

## ncm 返回的字段

```
songs = array[1]
songs[].name = str
songs[].mainTitle = NoneType
songs[].additionalTitle = NoneType
songs[].id = int
songs[].pst = int
songs[].t = int
songs[].ar = array[1]
songs[].ar[].id = int
songs[].ar[].name = str
songs[].ar[].tns = array[0]
songs[].ar[].alias = array[0]
songs[].alia = array[1]
songs[].pop = float
songs[].st = int
songs[].rt = str
songs[].fee = int
songs[].v = int
songs[].crbt = NoneType
songs[].cf = str
songs[].al = object
songs[].al.id = int
```

关键读数：

- **`privileges[]` 是 ncm 唯一的权威版权字段**：`st`（`-200` = 下架/无版权、`0` = 正常）、`pl`（可播码率，`>0` ⇒ 一定取得到链）、`fee`；
- `songs[].noCopyrightRcmd != null` 是**显式无版权声明**（零假阳性，覆盖率低）；
- 批量上限实测 100 首/请求（本探针按 100 分批，未遇到失败）。

## QQ 返回的字段

```
code = int
ts = int
start_ts = int
traceid = str
req = object
req.code = int
req.data = object
req.data.info = object
req.data.info.company = object
req.data.info.genre = object
req.data.info.intro = object
req.data.info.lan = object
req.data.info.pub_time = object
req.data.extras = object
req.data.extras.name = str
req.data.extras.transname = str
req.data.extras.subtitle = str
req.data.extras.from = str
req.data.extras.wikiurl = str
req.data.track_info = object
req.data.track_info.id = int
req.data.track_info.type = int
req.data.track_info.mid = str
req.data.track_info.name = str
req.data.track_info.title = str
req.data.track_info.subtitle = str
req.data.track_info.singer = array[1]
req.data.track_info.album = object
req.data.track_info.mv = object
req.data.track_info.interval = int
```

关键读数：

- `req.data.track_info` 是曲目本体（与搜索/专辑曲目**同构**），`req.data.info` 是分节的「歌曲信息卡」（唱片公司/流派/语种/发行时间等，字段名与展示文案耦合，**不适合做判据**）；
- `track_info.file.media_mid` 与 `track_info.mid` 不同（老结论复现）；
- `track_info.pay.pay_play` 只是标签，权威判据是 vkey 预检。

## 分页

单曲页没有分页。两源的**批量能力正相反**：

| 源 | 详情 | 播放地址 |
|---|---|---|
| ncm | **批量**（≤100） | 单首（`ids` 只吃一首就够） |
| QQ | 单首 | **批量**（`songmid[]` + `filename[]`） |

⇒ 聚合层对 ncm 要**一次批量拿 privilege**，对 QQ 要**一次批量做 vkey 预检**，两边各自一次请求就能覆盖一屏。

## 双源聚合必须补的东西

1. **QQ 单曲详情/播放预检的批量路径**（`QqApi.requestVkeyBatch` 已存在且已是批量，但只用于「用户已经点了播放」，没有用于「列表页标版权」）；
2. **ncm 的批量 privilege 路径**（新增，见 C1）；
3. 单曲页的 `SongDetailScreen` 今天**在应用内不可达**（路由注册了但没有调用点），本版要把它接上并做成双源聚合。
