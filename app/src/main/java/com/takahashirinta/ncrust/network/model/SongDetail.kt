package com.takahashirinta.ncrust.network.model

import androidx.compose.runtime.Immutable
import com.google.gson.annotations.SerializedName

/**
 * 曲目里的一个艺人（v2.6.1：**带上音源内的字符串身份**）。
 *
 * ## 为什么必须加 [mid]（P0 根因，有真机 + 接口实证）
 *
 * v2.6.0 及以前这里只有 `id: Long?`。qm 的 `singer[]` 条目同时给两个身份：
 * **数字 `id`（QQ 域）** 与 **`mid`（singerMID，base62）**。旧映射只取了数字 `id`，
 * 而 `NavRoutes.artist(artistId: Long)` 那条老路由在 composable 里**硬编码 ncm**，
 * 于是 QQ 的 `id` 被当成 ncm 的艺人 id 去查：
 *
 * | 歌曲 | QQ `singer.id` | 当成 ncm id 查出来 |
 * |---|---|---|
 * | 稻香 / 晴天（周杰伦） | `4558` | **马洪波**（专辑 1 / 单曲 32，真机复现） |
 * | 江南（林俊杰） | `4286` | 刘子译（专辑 0 / 单曲 0） |
 * | 富士山下（陈奕迅） | `143` | 404 |
 *
 * 两个编号空间**互不相通**（周杰伦：QQ `4558` vs ncm `6452`），拿一个去查另一个
 * 不是「查不到」而是「查到另一个人」—— 用户看到的是一位毫无关系的歌手。
 *
 * ## 字段契约（照 AGENTS.md「加字段 = 加迁移逻辑 = 加单测」）
 *
 * - **可空 + 默认值**：Gson 走 Unsafe 反序列化、不调用构造函数，本字段出现之前落盘的
 *   队列 / 收藏 / 歌单缓存 / 首页快照里没有这个 key，读出来必须是 `null` 而不是崩。
 * - `null` 的语义是「**这条数据没有字符串身份**」（ncm 恒为 null；QQ 侧是「来自本字段
 *   存在之前的旧版本，或来自只存 id 的历史路径」），**不是**「不需要身份」。
 * - **没有它就不许跳**：QQ 曲目缺 `mid` 时唯一正确的处置是「去搜索」，
 *   绝不用数字 `id` 顶替 —— 见 [com.takahashirinta.ncrust.source.ArtistNavigator]。
 *
 * @property id 音源内的**数字** id。ncm 是艺人 id；QQ 是 `singerID`（**不是**打开
 *   QQ 艺人页的键，那个是 [mid]）。仍然保留：QQ 侧用它做诊断与排序，
 *   ncm 侧它就是唯一的身份。
 * @property name 艺人名。只用于**召回**与展示，**永远不能**当身份用
 *   （v2.4.0 的实测：同名的仿冒号会把 `EXACT` 判成 `NONE`）。
 * @property mid 音源内的**字符串**身份：qm 的 `singerMID`（形如 `0025NhlN2yWrP4`）。
 *   ncm 侧没有这个概念，恒为 `null`。
 */
@Immutable
data class ArtistItem(
    @SerializedName("id") val id: Long? = null,
    @SerializedName("name") val name: String,
    @SerializedName("mid") val mid: String? = null
)

/**
 * 曲目所属的专辑（v2.6.2：**带上音源内的字符串身份**）。
 *
 * ## 为什么必须加 [mid]（P0 根因，有真机 + 接口实证）
 *
 * v2.6.1 及以前这里只有 `id: Long?`。qm 的 `album` 对象同时给三个候选：
 * **数字 `id`（QQ 域）**、**`mid`（albumMID，base62）**、
 * **`pmid`（封面照片 id，形如 `000MkMni19ClKG_5`）**。旧映射把 `mid`/`pmid`
 * 读进一个局部 val 只用来拼封面，`AlbumItem` 没有字段装它 —— 而
 * `NavRoutes.album(albumId: Long)` 那条老路由在 composable 里**硬编码 ncm**，
 * 于是 QQ 的 `id` 被当成 ncm 的专辑 id 去查：
 *
 * | 歌曲 | QQ `album.id` | 当成 ncm id 查出来 |
 * |---|---|---|
 * | 富士山下 / 陈奕迅《What's Going On...?》 | `22276` | **《百万金曲 陈小云2 苦恋梦 免失志》/ 陈小云**（真机复现，页面完全正常） |
 * | 葡萄成熟时 / 陈奕迅《U 87》 | `7879` | **《爱的供养》/ 邓杰**（真机复现） |
 * | 晴天 / 周杰伦《叶惠美》 | `8220` | 404（静默失败） |
 *
 * 两个编号空间**互不相通**（《叶惠美》：QQ `8220` vs ncm `18905`），拿一个去查另一个
 * 不是「查不到」而是「查到另一张专辑」—— 用户看到的是一张毫无关系的专辑，
 * 而且封面 / 发行日期 / 厂牌 / 曲目数一应俱全。
 *
 * ## 字段契约（照 AGENTS.md「加字段 = 加迁移逻辑 = 加单测」）
 *
 * - **可空 + 默认值**：Gson 走 Unsafe 反序列化、不调用构造函数，本字段出现之前落盘的
 *   队列 / 收藏 / 歌单缓存 / 首页快照里没有这个 key，读出来必须是 `null` 而不是崩。
 * - `null` 的语义是「**这条数据没有字符串身份**」（ncm 恒为 null；QQ 侧是「来自本字段
 *   存在之前的旧版本，或来自只存数字 id 的历史路径」），**不是**「不需要身份」。
 *   真机上确实存在这样的条目：`{"al":{"picUrl":"…T002R500x500M000002Neh8l0uciQZ_3.jpg"}}`
 *   —— 连 `id` 和 `name` 都没有。
 * - **没有它就不许跳**：QQ 曲目缺 `mid` 时唯一正确的处置是「去搜索」，
 *   绝不用数字 `id` 顶替 —— 见 [com.takahashirinta.ncrust.source.AlbumNavigator]。
 *
 * @property id 音源内的**数字** id。ncm 是专辑 id；QQ 是 `albumID`
 *   （**不是**打开 QQ 专辑页的键，那个是 [mid]）。仍然保留：ncm 侧它就是唯一的身份。
 * @property name 专辑名。只用于**展示与搜索召回**，**永远不能**当身份用
 *   （真机复现里两个源的同名专辑，名字在归一化前就不相等：
 *   `What's Going On...?` 三个点 vs `What's Going On…?` 省略号）。
 * @property picUrl 封面。**继续由 `pmid ?: mid` 拼**（v2.6.2 一行未改）——
 *   它与 [mid] 是两个职责：一个给眼睛看，一个给接口认。
 * @property mid 音源内的**字符串**身份：qm 的 `albumMID`（形如 `000MkMni19ClKG`）。
 *   ncm 侧没有这个概念，恒为 `null`。
 *   ⚠️ **不是 `pmid`**：`pmid` 是封面照片 id（`<albumMid>_<封面序号>`）。实测 QQ
 *   服务端碰巧能容忍把 `pmid` 当 `albumMid` 传，但那是服务端的宽容、不是契约；
 *   `AlbumNavigator` 的值域闸门（base62，`_` 不合法）会把它挡下 —— 这是有意的。
 */
@Immutable
data class AlbumItem(
    @SerializedName("id") val id: Long? = null,
    @SerializedName("name") val name: String?,
    @SerializedName("picUrl") val picUrl: String?,
    @SerializedName("mid") val mid: String? = null
)

data class SongDetailResponse(
    @SerializedName("songs") val songs: List<SongDetail>
)

data class SongDetail(
    @SerializedName("id") val id: Long,
    @SerializedName("name") val name: String,
    @SerializedName("ar") val artists: List<ArtistItem>,
    @SerializedName("al") val album: AlbumItem,
    @SerializedName("dt") val duration: Long,
    @SerializedName("no") val trackNumber: Int?,
    @SerializedName("mv") val mvId: Long?
)

data class LyricResponse(
    @SerializedName("code") val code: Int = 200,
    @SerializedName("lrc") val lrc: LyricContent?,
    @SerializedName("tlyric") val tlyric: LyricContent?,
    // v1.5.0 · B：逐字歌词（只在请求带 yv=-1 且该曲挂了逐字资产时才有这个 key）。
    @SerializedName("yrc") val yrc: LyricContent? = null,
    // v1.9.2：音译轨（罗马音/粤拼）。实测它与 rv 参数无关 —— rv=0 与 rv=-1 对同一首歌返回
    // **逐字节相同**的 body（4/4 首实测，含中/韩/英各一首），所以本版不动请求参数，
    // 只把响应里本来就有、此前一直没解析的字段接上。可空 + 默认值，缺 key 时等于「没有音译轨」。
    @SerializedName("romalrc") val romalrc: LyricContent? = null
)

data class LyricContent(
    @SerializedName("lyric") val lyric: String?
)