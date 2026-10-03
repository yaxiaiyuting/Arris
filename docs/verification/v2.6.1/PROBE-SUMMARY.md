# v2.6.1 探针汇总（PROBE-SUMMARY）

> 目标：QQ 歌曲二级菜单「转到歌手」跳转到错误艺人。
> 目标版本 `v2.6.0-gpl`（versionCode 48），修复版本 `v2.6.1-gpl`。
> 方法：静态全树审计 + 公开接口取证 + **两台真机**受控复现 + 同机交叉 A/B。
> **任务书 §2 的初步判断被本探针推翻了三条**，见 §2。

---

## 1. 复现路径与稳定性

### 1.1 最短复现路径（用户报告的路径）

> 搜索 `Jay Chou` → 结果里出现 qm《晴天 / 周杰伦》→ **长按该行** →
> 二级菜单 → 点 **「转到歌手」** → 落地页是 **马洪波**

| 设备 | Android | 结果 | 稳定性 |
|---|---|---|---|
| WGR-W09（华为平板） | 12 | 马洪波（专辑 5 / 单曲 40） | **5/5** |
| PCL110（OPPO 手机） | 16 | 马洪波 | **2/2** |

原始证据：`screenshots/before-wgr-02-wrong-artist-mahongbo.png` +
`probe-raw/wgr-before-wrong-artist-tree.txt`（语义树：`528 277 马洪波`，
同时底部托盘是 `稻香 / 周杰伦 / qm`）。

### 1.2 第二种症状：**点了没反应**

| 设备 | 触发条件 | 结果 | 稳定性 |
|---|---|---|---|
| PCL110 | 冷启动后（当前歌曲由 `PlaybackStateManager` 恢复）→ 播放器点歌名 → 二级菜单 → 转到歌手 | 菜单关闭，**页面完全不变** | **2/2** |

### 1.3 同机交叉 A/B：症状随**数据来源**切换，不随机型切换

同一台 PCL110、同一个账号、同一个页面、同一组坐标：

| 让 QQ《晴天》成为当前歌曲的方式 | `artists[0]` | 结果 |
|---|---|---|
| force-stop 后冷启动恢复 | 只有 `name`，`id == null` | **没反应** |
| 在搜索结果里点一下那一行 | `id = 4558`（QQ 数字域） | **跳到马洪波** |

两轮之间只差一次点击，**没有重启、没有换设备、没有清缓存**。
→ 用户报告的「手机没反应、平板跳错」是**同一个 bug 的两种表现**，
不是平台差异（纪律 6「平台假设必须 A/B 对照」的正面用法）。

---

## 2. 任务书判断 vs 实测（三条被推翻）

| 任务书 §2 / §3 的假设 | 实测 | 证据 |
|---|---|---|
| 「走了 v2.4.0 跨源匹配，置信度不足」 | **完全没走匹配**。这条路径上一次都没调用 `CrossSourceMatcher` / `MatchCacheStore` / `MatchConfidence` | `probe-artist-jump-static.md` §2.1 |
| 「存在 fallback 跳到某个默认艺人」 | **不存在**。fallback 是「补一次**ncm** `song/detail`」，QQ 的 bit62 合成 id 在那里必然查空 ⇒ 静默放弃 | `MainActivity.kt:1845-1847`（v2.6.0） |
| 「匹配缓存被污染」 | **没污染，而且存的是正确答案** | §3.3 |
| 「只有 QQ / 只有二级菜单 / 只有周杰伦」 | 只有 QQ ✔；**不止二级菜单**（播放页托盘同一错法）✘；**不止周杰伦**（林俊杰/陈奕迅同样错，只是看起来不同）✘ | §4 |

---

## 3. 马洪波的身份与来源（逐条回答任务书 §2.2）

### 3.1 他是谁

**ncm 的艺人 `4558`**，真名「马洪波」，`albumSize=1`、`musicSize=32`。
`GET https://music.163.com/api/artist/4558` → `{"code":200,"id":4558,"name":"马洪波",...}`。

### 3.2 两个源的艺人 ID

| | qm | ncm |
|---|---|---|
| 周杰伦 **数字** id | `4558`（`singer.id`） | `6452` |
| 周杰伦 **字符串** id | `0025NhlN2yWrP4`（`singer.mid` = singerMID） | 无此概念（十进制就是身份） |
| 马洪波 | — | `4558` |

**结论：`4558` 是 QQ 域的数字 id，被当成 ncm id 查，撞上了真实存在的马洪波。**

### 3.3 他的 id 是不是某个 fallback 值（0 / -1 / null）？

**不是。** `4558` 是一个真实的服务端数字，来自 QQ 搜索响应：
`{"id": 4558, "mid": "0025NhlN2yWrP4", "name": "周杰伦", "pmid": "", "title": "周杰伦", "type": 0, "uin": 0}`。

### 3.4 他是不是「API 失败时返回的第一条结果」？

**不是。** 那条请求（`/api/artist/4558`）返回 `code 200` 且**成功**命中了一个真实艺人
—— 这正是最坏的一档：页面正常渲染，用户看到的是一个有头像、有专辑、有单曲的真人。

### 3.5 他是从哪来的（应用里）

从**没有**任何地方「搜出」马洪波。他是 `NavRoutes.artist(artistId: Long)` 这条老路由
（composable 里 `sourceKey = MusicSource.NETEASE.key` 写死）把 QQ 的 `4558` 交给
ncm `api/artist/albums/4558` 之后，服务端**正确地**返回的那个人。

### 3.6 匹配缓存有没有被污染？

**没有。** 真机 `ncrust_match_cache.xml`（两台设备一致）里存的恰恰是**正确**的对应关系：

```json
"artist:netease:6452": {
  "confidence": "EXACT", "overlap": 33,
  "reason": "唯一同名候选 + 专辑重合 33 张（占较小侧 76%）",
  "aliasesJson": "[{\"id\":\"0025NhlN2yWrP4\",\"name\":\"周杰伦\",\"source\":\"qqmusic\"}]"
}
```

应用**早就知道**「ncm 6452 ↔ QQ `0025NhlN2yWrP4` 是同一个人，EXACT」，
只是这条跳转路径从来没问过这张表（它的键是 `(netease, 6452)`，而用户手上只有 `4558`）。

---

## 4. 根因

**两个独立的身份缺陷，叠在同一条路径上。**

### 根因 A（跳错人）：QQ 的 `singerMID` 在映射层被丢掉，跳转层又把源写死

1. `qq/QqSongMapper.kt:110` 只取 `singer[].id`（QQ 数字域），**丢掉了同一个对象上一直存在的 `singer[].mid`**；
   `ArtistItem` 当时也没有字段能装它。
2. `MainActivity.resolveAndNavigate` 只读 `artists[0].id` 就跳，**从不读 `song.musicSource`**。
3. 老路由 `artist/{artistId}` 的 composable 把 `sourceKey` **写死**成 `MediaSource.NETEASE`
   —— QQ 的数字 id 于是被拿去查 ncm。

> 附带纠正一条**写进代码注释的错误结论**：`AlbumDetailScreen.kt:170-172` 说
> 「QQ 一侧拿不到 `singerMID`」。实测 QQ `singer[]` 的字段集是
> `{id, mid, name, pmid, title, title_highlight, type, uin}` —— **`mid` 一直在**。
> 那句话对**专辑接口**成立，对**搜索接口**不成立。

### 根因 B（没反应）：补 id 的回落是 ncm 的，QQ 的合成 id 在那里必然落空

```kotlin
// MainActivity.kt:1841-1848（v2.6.0）
val idMissing = target.artists?.firstOrNull()?.id == null
if (idMissing) {
    target = runCatching { PlaylistApi.getSongsByIds(listOf(song.id)) }   // ← ncm /eapi/v3/song/detail
        .getOrDefault(emptyList()).firstOrNull() ?: song
}
...
when {
    toArtist && artistId != null -> …   // ← 不成立
    !toArtist && albumId != null -> …   // ← 不成立
    // 什么都不做，也不提示
}
```

冷启动恢复的曲目 `artists[0].id` **恒为 null**（`MainActivity.kt:920` 只存了艺人名字），
而 `song.id` 是 `SourceIds.qqId` 合成的（bit62），问 ncm 必然返回空 ⇒ **静默无反应**。

### 明确排除的三项

| 排除项 | 判据 |
|---|---|
| 跨源匹配错误 | 路径上零次匹配调用 |
| 缓存污染 | 缓存内容正确且未被读取 |
| fallback 跳默认艺人 | 不存在这种 fallback；存在的是「补一次 ncm detail」 |

---

## 5. 影响范围（逐条回答任务书 §2.4）

| 问题 | 答案 |
|---|---|
| 只有 QQ？ | **是。** ncm 曲目的 `artists[0].id` 就是 ncm 艺人 id，老路由写死的源恰好正确（已做 A/B 回归，无回归） |
| 只有二级菜单？ | **否。** 二级菜单 + **播放页竖屏托盘作者名**是两个独立入口、同一个错法。而二级菜单本身挂在 **9 个宿主页面**上（首页/搜索/收藏/歌单/QQ 歌单/本地歌单/专辑/艺人/播放器） |
| 只有周杰伦？ | **否。** 周杰伦只是唯一一个「撞到有内容的真人」的样本：<br>· 周杰伦 `4558` → 马洪波（专辑 1 / 单曲 32，**页面看起来完全正常**）<br>· 林俊杰 `4286` → 刘子译（0 / 0，空页面）<br>· 陈奕迅 `143` → 404（报错） |
| 严重度排序 | **周杰伦这一档最严重**：页面正常 ⇒ 用户会以为「应用的艺人数据整体是错的」（铁律 21 的原话） |
| 哪些数据形态会中招 | ① 搜索/歌单/专辑/艺人页里**新鲜加载**的 QQ 曲目（跳错人）；② **冷启动恢复**的任意曲目（没反应） |

---

## 6. 修复方向（详见 `CHANGELOG-v2.6.1.md`）

1. **把 `singerMID` 带出来**：`ArtistItem` 新增 `mid: String?`（可空 + 默认值，Gson 安全），
   在 `QqSongMapper` 与 `QqCatalogMapper` 两条映射链路上一起填，并加对称单测钉住逐值一致。
2. **身份判定收成一个纯函数**：新增 `source/ArtistNavigator.kt`，
   产出 `Direct(source, id)` / `Search(keyword, reason)` / `Unavailable` 三态；
   **类型上就不存在「跳到另一个源」**。
3. **值域闸门**：`idDomainMatches(source, id)` —— ncm 吃十进制、QQ 吃 base62 的 mid，
   **纯数字的 QQ `singerID` 在 QQ 域不是合法身份**，所以在闸门上就被拦下。
4. **置信度闸门**：`crossSourceJump(confidence, targetSource, targetId)` 要求
   `MatchConfidence.mergeable`（全应用唯一阈值）**且**目标值域合法；任一不满足 ⇒ 跳搜索。
5. **兜底跳搜索**：身份不可信时切到搜索 tab 并预填艺人名，**并给一句提示**
   —— 旧行为是静默失败，那正是它拖到用户报告才被发现的原因。
6. **观测性**：这条路径第一次有了 `TAG_ARTIST_NAV` 日志（修复前 release 包零日志，
   见 `probe-logcat.md`）。

---

## 7. 本探针的产出清单

| 文件 | 内容 |
|---|---|
| `probe-artist-jump-static.md` | 静态审计：全部入口、路由、参数链路、逐条回答 §2.3 八问 |
| `probe-artist-id-collision.sh` / `.out.txt` | 接口取证：QQ `singer.id`/`mid` ↔ ncm artist id 的撞号矩阵 |
| `probe-raw/qq-search-*.json` | 三首真实 QQ 搜索响应原文（稻香 / 林俊杰 / 陈奕迅） |
| `probe-artist-jump.md` | 真机复现步骤、交叉 A/B、环境问题如实记录 |
| `probe-logcat.md` | 「修复前这条路径零日志」的取证与可重跑命令 |
| `screenshots/` | 修复前截图 |
| `EVIDENCE.md` | 证据索引（哪条结论对应哪个文件） |
