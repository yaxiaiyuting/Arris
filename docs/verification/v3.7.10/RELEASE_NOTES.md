# Arris v3.7.10-gpl

versionCode **96**。v3.7.9 发布时列出的「已知未修」这一轮全部落地。

## 一、H13：QQ 单曲详情终于能播

- 单曲路由增加 **4 个可选查询载荷**（`sourceId` / `mediaId` / `name` / `artist`）。
  空值不进查询串 ⇒ 老调用点产出的路由与 v3.7.9 **逐字节相同**；读侧仍不解码（H08 纪律）。
- 详情页用载荷构造锚点 `SongItem`：QQ 曲目拿到 `songmid` / `media_mid` ⇒ 能取链、也能跨源召回；
  载荷缺失时该行**禁用**播放/插播并显示行内提示，而不是交出一个必然失败的条目。
- 顺带接上「添加到下一首」——它此前在单曲页是默认空实现（点了没反应）。

## 二、J15：颜色量化的两处错误都修完

- **剪枝读错距离**：`Arrays.sort` 之后按位置 j 当簇号，三角不等式失效（该剪的不剪、不该剪的剪掉）。
  现在另存一份**未排序**的簇距矩阵专供剪枝；新增对照用例证明「开剪枝 == 全量扫描」，
  并用 mutation 验证该用例的判别力（改回旧写法立刻红）。
- **`maximize` 的 Int 平方溢出**（上一轮只修了 `variance`）：`halfR * halfR + …` 先溢出成负数，
  `temp` 永远比不过 `max` ⇒ `cutLocation = -1` ⇒ Wu 把整张图压成 1 个盒子。
  实测：红/绿各半的输入从「只剩一个 0xFF7F7F00」变成「两个原色各 1250」；
  八色图从 7 个簇（深灰与灰被合并）变成 **8 个输入色全部按真实权重还原**。
- 两条 parity 用例改名 `…DeviatesFromOfficialKotlinPort…` 并重定基线，注释写明**有意偏离**官方端口及其原因。

## 三、H10 / H21：上游把完整性交出来

- `PlaylistApi` 新增 `PlaylistDetailResult(songs, declaredCount, failedBatches, complete)` 与
  `getPlaylistDetailResult()`；**保留** `getPlaylistDetail(): List<SongItem>` 便利包装（既有调用点零改动）。
  本地同步的网易分支据此产出 `Complete / Incomplete(TRUNCATED) / Failed`。
- QQ 歌单分页游标改用**原始条目数**（`rawCount`），翻页不再重叠；
  `songlist` 数组缺失直接判结构错误，不再被当成"空页/完整"。

## 四、J10 / H14：历史数据迁移

- **J10 真正落地**：`SongItem` 新增 `qq_songid`（此前响应里的 songid 从未被持久化，
  于是 `id` 是 `FNV-1a(mid)` 散列、不可反推）。`QqSongMapper` 落盘真实 songid，
  读路径把旧散列 id **幂等**改写成规范 id；没有该字段的（更早的数据）原样保留。
- **H14**：读路径给旧 B 站视频 id 补位 60（`au:` 不动、已补位的不动），覆盖收藏、队列、单曲状态。

## 五、E10 / K05

- 队列重排提交改带两端 `TrackKey`：`MainActivity` 按身份重定位后再移动，任一端不在队列就放弃。
- 播放器红心不再依赖组件局部 tick：`LibraryManager.savedRevision`（可观察修订）+ 按身份查询，
  红心叶子自己订阅；库页/搜索页改收藏后播放器立即正确。`libraryTick` 字段已删除。

## 已知未修（本轮范围外）

| 项 | 说明 |
|---|---|
| H14 三张次级身份表 | `cache/OfflineTrackCodec`、`lyric/LyricsCache`、`local/LocalPlaylistStore` 仍存旧 AV id。只影响 B 站视频条目的历史缓存键；取链走 `sourceId`，播放不受影响。可复用 `SourceIds.migrateBiliAvPartition` |
| 队列重排的签名清理 | 当前实现用一次性 `QueueMoveCommit` 在 `QueueView` 与 `MainActivity` 之间传身份；彻底做法是把 `onMoveInQueue` 改签名为 `(TrackKey, TrackKey)`（4 处各 1 行）。行为已正确，属结构清理 |
| J10 的"更早数据" | 没有 `qq_songid` 的历史条目无法迁移（散列不可反推），会保留两条身份 |

## 验证

- JVM 单测 **3314 通过 / 0 失败**
- Lint **0 error / 60 warning**（17 条在基线内）
- 未做真机验证；真机待确认项：手写深链 `song/qqmusic/<id>`（应打开页面、缺载荷行禁用、另一源召回行可播）
