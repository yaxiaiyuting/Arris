# 探针 · 专辑页接口（ncm ↔ qm）

> 生成：`docs/verification/v2.4.0/probe-catalog-api.py`（**匿名只读**）。原始响应：`probe-raw/catalog-api.json`。

## 端点对照

| 能力 | ncm | qm |
|---|---|---|
| 专辑详情 + 曲目 | `GET /api/v1/album/{id}` | `musicu.fcg` → `music.musichallAlbum.AlbumSongList/GetAlbumSongList` |
| 专辑搜索 | `POST /api/cloudsearch/pc` `type=10` | `GET client_search_cp?t=8` |
| 专辑元数据专端点 | 无（详情里带） | `music.musichallAlbum.AlbumInfoServer/GetAlbumInfo` → **实测 `code=40000` 失败** |

## ncm 返回的字段

```
resourceState = bool
songs = array[11]
songs[].rtUrls = array[0]
songs[].ar = array[1]
songs[].ar[].id = int
songs[].ar[].name = str
songs[].ar[].alia = array[2]
songs[].al = object
songs[].al.id = int
songs[].al.name = str
songs[].al.picUrl = str
songs[].al.pic_str = str
songs[].al.pic = int
songs[].al.alia = array[1]
songs[].st = int
songs[].noCopyrightRcmd = NoneType
songs[].artistClassics = NoneType
songs[].songJumpInfo = NoneType
songs[].no = int
songs[].fee = int
songs[].djId = int
songs[].mv = int
songs[].cd = str
songs[].t = int
songs[].v = int
songs[].dt = int
```

关键读数：

- `album.size` = 曲目数；`album.publishTime` = **毫秒时间戳**；`album.company` = 厂牌；
- `songs[]` 里**每一首都有 `fee` 与 `st`**，但实测 `st` 在本专辑**恒为 `-1`**，而 `-1` 在 v2.3.0 的判据里是「不可用」（15/30 可播）—— 见 `probe-copyright-field.md` C3；
- **`songs[]` 里没有 `privilege`** ⇒ 版权判定必须另发批量详情。

## QQ 返回的字段

```
code = int
ts = int
start_ts = int
traceid = str
req = object
req.code = int
req.data = object
req.data.albumMid = str
req.data.totalNum = int
req.data.songList = array[11]
req.data.songList[].songInfo = object
req.data.songList[].listenCount = int
req.data.songList[].uploadTime = str
req.data.songList[].isThemeSong = int
req.data.songList[].teamStr = str
req.data.classicList = array[0]
req.data.sort = int
req.data.albumTips = str
req.data.index = int
req.data.schedule_status = int
req.data.curBegin = int
req.data.cdNewStyle = int
req.data.cdNameMap = NoneType
req.data.deviceValid = int
req.data.countdownText = str
```

关键读数：

- `totalNum` = 曲目数（实测叶惠美 11）；
- 曲目在 `songList[].songInfo` 里，字段与搜索响应**同构**（`mid` / `name` / `singer[]` / `album` / `interval` / `file.media_mid` / `pay` / `action`）⇒ **可以复用同一个 mapper**，不需要第二套解析；
- `file.media_mid` 与 `mid` 实测**全部不同**（230/230，见 `probe-song-mapping.md`）⇒ 取链必须用 `media_mid`；
- `pay.pay_play` / `action.*` 都只是**标签**，能不能播要用批量 vkey 预检（见 `probe-copyright-field.md` C4）。

## 分页机制

| 源 | 参数 | 语义 | 实测 |
|---|---|---|---|
| ncm | 无（一次全量） | 专辑曲目一次返回 | 叶惠美 11 首一次回全 |
| QQ | `begin` + `num` | 偏移分页 | `num=50` 一次回全 11 首 |

## 双源聚合必须补的东西

1. **QQ 专辑曲目端点本版本之前完全没有接入**；
2. 两源都能拿到「曲目名集合」与「曲目数」⇒ 专辑页可以做到 `EXACT` 级匹配；
3. ncm 页面的 `SongItem` 产物**不带 `source`**（`AlbumDetailScreen` 手搓 `SongItem`），这是「点进去播不了」的一条隐藏成因：下游会把它当 ncm。
