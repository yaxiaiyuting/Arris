# 探针 · 艺人主页接口（ncm ↔ qm）

> 生成：`docs/verification/v2.4.0/probe-catalog-api.py`（**匿名只读**）。字段表是**从真实响应递归扫出来的**。原始响应：`probe-raw/catalog-api.json`。

## 端点对照

| 能力 | ncm | qm |
|---|---|---|
| 艺人搜索结果 | `POST /api/cloudsearch/pc` `type=100` | `GET c.y.qq.com/soso/fcgi-bin/client_search_cp?t=9` |
| 艺人专辑列表 | `GET /api/artist/albums/{id}?limit&offset` | `GET c.y.qq.com/v8/fcg-bin/fcg_v8_singer_album.fcg?singermid&num&begin&order` |
| 艺人热门曲 | `GET /api/v1/artist/{id}`（`hotSongs[]`） | `musicu.fcg` → `music.web_singer_info_svr/get_singer_detail_info`（`songlist[]`） |
| 艺人详情 | `GET /api/artist/detail/{id}` → **实测 404** | 无独立端点（艺人信息在搜索/专辑列表里） |

## ncm 返回的字段（`/api/artist/albums/{id}`）

```
code = int
artist = object
artist.img1v1Id = int
artist.topicPerson = int
artist.picId = int
artist.musicSize = int
artist.albumSize = int
artist.briefDesc = str
artist.picUrl = str
artist.img1v1Url = str
artist.followed = bool
artist.trans = str
artist.alias = array[2]
artist.name = str
artist.id = int
artist.picId_str = str
artist.img1v1Id_str = str
hotAlbums = array[10]
hotAlbums[].songs = array[0]
hotAlbums[].paid = bool
hotAlbums[].onSale = bool
hotAlbums[].mark = int
hotAlbums[].awardTags = NoneType
hotAlbums[].displayTags = NoneType
hotAlbums[].artists = array[1]
hotAlbums[].artists[].img1v1Id = int
hotAlbums[].artists[].topicPerson = int
hotAlbums[].artists[].picId = int
```

关键读数：

- `hotAlbums[].size` = **曲目数**（实测叶惠美 11，与专辑详情 `album.size` 一致）；
- `hotAlbums[].publishTime` = **毫秒时间戳**（QQ 那边是 `YYYY-MM-DD` 字符串，不可直接比）；
- `hotAlbums[].company` = 厂牌（实测本样本为空串，不可依赖）；
- `artist.albumSize` / `artist.musicSize` 与列表长度**口径不同**（周杰伦：`albumSize=41`，但 `limit=10` 的列表只回 10 条、`limit=100` 回 44 条）—— **不要拿 `albumSize` 当列表长度**；
- **没有 `privilege`** ⇒ 版权可用性必须另发一次批量 `/api/v3/song/detail`。

## QQ 返回的字段（`fcg_v8_singer_album.fcg`）

```
code = int
data = object
data.list = array[10]
data.list[].Fattribute_5 = str
data.list[].Ftype = str
data.list[].albumID = str
data.list[].albumMID = str
data.list[].albumName = str
data.list[].albumtype = str
data.list[].company = str
data.list[].desc = str
data.list[].lan = str
data.list[].latest_song = object
data.list[].listen_count = str
data.list[].pubTime = str
data.list[].score = str
data.list[].shoufa = int
data.list[].singerID = str
data.list[].singerMID = str
data.list[].singerName = str
data.list[].singers = array[1]
data.list[].type = int
data.singer_id = int
data.singer_mid = str
data.singer_name = str
data.total = int
message = str
subcode = int
```

关键读数：

- `albumMID` 是专辑的**唯一键**（base62 字符串），`albumID` 是数字 id；
- `latest_song.song_count` = **曲目数**（实测叶惠美 11，与 `GetAlbumSongList` 的 `totalNum` 一致）；
- `pubTime` = `YYYY-MM-DD`，`albumtype` = 「录音室专辑」等中文标签，`company` = 版权方；
- **`musicu.fcg` 的 `music.musichallAlbum.AlbumListServer/GetAlbumList` 每条 `totalNum` 恒为 0** —— 同一份数据在旧版 CGI 里有曲目数、在新版音乐库里没有。艺人页聚合必须走旧版 CGI，否则会退化成「每张专辑再打一次详情」的 N+1。

## 分页机制（两源不一致）

| 源 | 参数 | 语义 | 实测上限 |
|---|---|---|---|
| ncm | `limit` + `offset` | 偏移分页 | 周杰伦 `limit=100` 一次回 44 张，够用 |
| QQ | `num` + `begin` | 偏移分页 | 周杰伦 `num=100` 一次回 43 张，够用 |

⇒ **形状不同但语义一致**（都是 offset/limit），聚合层可以用同一个「拉一页 100 条」的策略。

## 双源聚合必须补的东西

1. QQ 侧的艺人专辑列表与热门曲**本版本之前完全没有接入**（`QqApi` 只有搜索/取链/歌词）；
2. 两源的曲目数都拿得到 ⇒ 可以进专辑匹配的 `EXACT` 判据；
3. 两源都**没有** `privilege` ⇒ 版权可用性需要每条链路各补一次批量预检。
