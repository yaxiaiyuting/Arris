# v2.6.2 探针 · logcat 取证

> 目的有两层：① 用**网络请求路径**坐实"QQ 的数字专辑 id 被交给了 ncm 接口"；
> ② 证明**修复前这条路径在 release 包里一条日志都没有** —— 这正是它能拖到用户报告
> 才被发现的原因，也是本版必须补 `TAG_ALBUM_NAV` 的理由。

---

## 1. 取到的原始日志（PCL110 / `v2.6.1-gpl` / release 包）

`logcat-before-pcl-album-jump.txt`（`adb logcat -d -v threadtime --pid=<app pid>`，
`logcat -c` 之后只做**一个**动作：长按 QQ 曲目《葡萄成熟时》→ 点「转到专辑」→ 等 8 秒）：

```
09-26 23:59:11.458 16910 22005 I NcrustHttpTiming: path=/api/v1/album/7879 ttfb=305ms body=3ms total=310ms
09-26 23:59:11.664 16910 22005 I NcrustHttpTiming: path=/api/v1/album/7879 ttfb=201ms body=3ms total=205ms
09-26 23:59:14.324 16910 22005 I NcrustHttpTiming: path=/api/v1/album/7879 ttfb=295ms body=5ms total=302ms
09-26 23:59:14.826 16910 21208 I NcrustHttpTiming: path=/eapi/v3/song/detail ttfb=298ms body=1ms total=299ms
```

### 1.1 这条日志说明了什么

| 事实 | 依据 |
|---|---|
| 请求打的是**ncm**的专辑接口 | `/api/v1/album/{id}` 是 `NcmApi.getAlbumDetail`，`RetrofitClient.BASE_URL = https://music.163.com` |
| 路径里的 `7879` 是 **QQ** 的 `album.id` | QQ 搜索响应实测《葡萄成熟时》`album.id = 7879`（`probe-raw/probe-album-id-collision.out.json` 可复跑） |
| 两个编号空间不相通 | ncm 上 `7879` 是《爱的供养》/ 邓杰（`probe-raw/probe-album-cross-domain.out.txt`） |
| 页面为什么"看起来正常" | 服务端 `code 200` + 一张真实存在的专辑 ⇒ 封面/发行日期/厂牌/曲目数全都渲染得出来 |

**这一条同时覆盖了根因的 ② 和 ③**：跳转层没读 `song.musicSource`（否则不会用 7879 去问 ncm），
老路由 `album/{albumId}` 把 `sourceKey` 写死成 `MusicSource.NETEASE`（否则请求不会落到
`music.163.com`）。两件事各缺一半都不会出现这行日志。

---

## 2. 「修复前这条路径零日志」

同一次抓取里，与跳转决策有关的 tag **一条都没有**：

```bash
$ grep -icE "AlbumNav|ArtistNav|AlbumNavigator" logcat-before-pcl-album-jump.txt
0
```

这不是"日志级别没开"：同一个 pid 下 `NcrustHttpTiming`（`I` 级）正常输出，
说明 release 包**在打日志**，只是**没有人在这条路径上写日志**。

对照 `MainActivity.kt`（v2.6.1-gpl）：

- 艺人分支（v2.6.1 修的）：`TAG_ARTIST_NAV = "ArtistNav"`，`Log.i` / `Log.w` 各一处；
- 专辑分支（本次修的）：**零日志**，`albumId == null` 时连"我什么都没做"都不说。

后果按严重度：

1. **静默失败无法从线上回捞** —— 用户报"点了没反应"时，日志里查不到这首歌走过这条路；
2. **跳错专辑更查不到** —— 页面正常、请求 200，唯一的线索是这个 `7879` 到底是谁的 id，
   而它和"用户点的是哪首歌"之间没有任何一条日志把它们连起来。

---

## 3. 修复后这条路径应当出现什么（本版新增，验收用）

```bash
adb logcat -s AlbumNav
```

| 场景 | 期望日志 |
|---|---|
| QQ 曲目新鲜加载 | `I AlbumNav: 转到专辑 source=qqmusic id=004Z85XP1c25b7 song=qqmusic:4611686018…` |
| 老缓存 / 无 albumMID | `I AlbumNav: 转到专辑降级为搜索 reason=MISSING_ID song=… keyword=稻香 周杰伦` |
| 只有数字 id | `I AlbumNav: 转到专辑降级为搜索 reason=AMBIGUOUS_NUMERIC_ID song=… keyword=…` |
| 连名字都没有 | `W AlbumNav: 转到专辑：无专辑信息，忽略 song=…` |

> 修复后的实测日志见 `verification/DEVICE-VERIFICATION.md`（同一台 PCL110、同一个动作）。

---

## 4. 可重跑命令

```bash
SERIAL=3B15CD00GB700000
PID=$(adb -s $SERIAL shell pidof com.takahashirinta.ncrust | tr -d '\r')
adb -s $SERIAL shell logcat -c
# —— 在设备上做一次「转到专辑」——
adb -s $SERIAL shell logcat -d -v threadtime --pid=$PID
```
