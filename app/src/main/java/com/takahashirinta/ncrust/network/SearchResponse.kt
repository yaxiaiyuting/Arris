package com.takahashirinta.ncrust.network

import androidx.compose.runtime.Immutable
import com.google.gson.annotations.SerializedName
import com.takahashirinta.ncrust.network.model.ArtistItem
import com.takahashirinta.ncrust.network.model.AlbumItem

data class SearchResponse(
    @SerializedName("result") val result: SearchResult?
)

data class SearchResult(
    @SerializedName("songs") val songs: List<SongItem>?,
    @SerializedName("albums") val albums: List<AlbumSearchItem>?,
    @SerializedName("artists") val artists: List<ArtistSearchItem>?
)

// @Immutable：Compose 视 List<T> 为 unstable 参数，会强制每次重组重新比较；
// 显式标注后 Compose 在参数不变时可跳过整个 SongCard 子树的重组。
// 前提是所有字段全 val + 值语义——本 data class 已满足。
@Immutable
data class SongItem(
    @SerializedName("id") val id: Long,
    @SerializedName("name") val name: String,
    @SerializedName("ar") val artists: List<ArtistItem>?,
    @SerializedName("al") val album: AlbumItem?,
    @SerializedName("dt") val duration: Long?,
    /**
     * 音源 key（v2.1.0 · A）：`"netease"` / `"qqmusic"`。**可空且默认 null 是硬要求** ——
     * 队列是 Gson 持久化在 `ncrust_playback_state` 里的，而 Gson 走 Unsafe 反序列化、
     * 不调用构造函数：v2.1.0 之前写入的队列 JSON 里没有这个 key，读到就是 null。
     * null 的语义是「v2.1.0 之前的数据 ⇒ ncm」（见 MusicSource.fromKey）。
     *
     * 判定请用 source 包里的扩展属性 `SongItem.musicSource`，不要直接读这个字符串。
     */
    @SerializedName("source") val source: String? = null,
    /**
     * 平台侧字符串 ID（v2.1.0 · A）：qm 的 `songmid`（形如 `0039MnYb0qxYhV`）。
     *
     * 为什么必须有它：qm 的取链接口要按 **mid** 拼文件名（`<音质前缀><mid>.<扩展名>`），
     * 数字 songid 只能定位歌曲、不能取链。ncm 一侧恒为 null（它的 [id] 就够用）。
     * 同样可空 + 默认值，理由与 [source] 相同。
     */
    @SerializedName("mid") val sourceId: String? = null,
    /**
     * 平台侧**媒体文件** ID（v2.1.0 · B）：qm 的 `file.media_mid`。
     *
     * 为什么它与 [sourceId] 必须分开：QQ 的播放 URL 文件名按 **media_mid** 拼
     * （`<音质前缀><media_mid>.<扩展名>`），而实测同一首歌这两个值经常不同
     * （《晴天》`mid=0039MnYb0qxYhV` 而 `media_mid=003Qui1q2u1Zho`）。
     * 用错一个就会静默拿不到 URL（服务端只回空 purl，不报错）。
     * ncm 一侧恒为 null。可空 + 默认值，理由同 [source]。
     */
    @SerializedName("media_id") val mediaId: String? = null,
    /**
     * ncm 的付费类型原始值（v2.1.4）。**只用于派生「播放是否需要会员」，不参与取链。**
     *
     * 实测语义（2026-09，登录态，cloudsearch/pc）：
     * - `0` = 免费；`8` = 免费播放但**高音质**需会员（播放本身不受限）；
     * - `1` = VIP 专享；`4` = 数字专辑（买了才能听）。
     *
     * 与 [memberOnly] 一样可空 + 有默认值：它会跟着 Gson 进队列持久化，
     * 而 Gson 走 Unsafe 反序列化、不调用构造函数，老队列 JSON 里没有这个 key。
     * 判定本身在 [com.takahashirinta.ncrust.search.TrackAccess] 一处收敛，这里只存原始值。
     */
    @SerializedName("fee") val fee: Int? = null,
    /**
     * **播放是否需要会员**（v2.1.4）。`true` = 会员专享，`false` = 免费可播，
     * `null` = 服务端没给判据或读不懂（见 [com.takahashirinta.ncrust.search.TrackAccess]）。
     *
     * 与 [fee] 的分工：qm 的判据是布尔（`pay.pay_play == 1`），映射时就能确定，
     * 直接写在这里；ncm 的判据是 [fee] 那个整数，由搜索聚合层派生。
     * **null 的语义是「不知道」**，不是「免费」—— 排序把「不知道」与「免费」同组靠后，
     * 绝不把它当会员专享往前推。
     */
    @SerializedName("member_only") val memberOnly: Boolean? = null,
    /**
     * ncm 的**逐曲 `privilege` 对象**（v2.3.0）。搜索 / 艺人热歌 / 单曲详情三条接口
     * 都会下发它，不需要额外请求。
     *
     * ## 为什么必须解析它
     *
     * v2.3.0 的探针要回答「搜索阶段能不能诚实标注能不能播」，结论是**能**，
     * 而唯一的零假阳性判据就在这个对象里（[SongPrivilege.st] / [SongPrivilege.pl]）：
     * 实测 `pl > 0` ⇒ 30/30 可播、假阳性 0（`docs/verification/v2.3.0/probe-copyright.md` §2）。
     *
     * 可空 + 默认值：Gson 走 Unsafe 反序列化、不调用构造函数，而且它会跟着进队列持久化 ——
     * 老队列 JSON 里没有这个 key，读到就是 null（语义 = 「不知道」，不是「不能播」）。
     */
    @SerializedName("privilege") val privilege: SongPrivilege? = null,
    /**
     * ncm 的**「无版权时可推荐的替代」**（v2.3.0）。`null` = 服务端没有声明。
     *
     * 实测（2026-09，591 条样本）只有 **2 条**带它，但**零假阳性** ——
     * 带上它时该曲确实取不到链，且服务端自己给了替代说明（实测 `typeDesc = "其它版本可播"`）。
     * 所以它是「**确定无版权**」的判据，而不是「有没有版权」的普查字段。
     */
    @SerializedName("noCopyrightRcmd") val noCopyright: NoCopyrightRecommendation? = null,
    /**
     * ncm 的**原唱 / 翻唱**标注（v2.3.0）。实测取值与语义（`probe-official-tag.md`）：
     * `1` = 原唱、`2` = 翻唱、`0`/`3` = 不知道（**不打标签**）。
     *
     * 这是**接口字段**，不是启发式 —— 自洽性检验（翻唱声称的原曲回头查是不是原唱）
     * 58/60 = 96.7%，且剩余 3.3% 全落在「不知道」一侧而不是「说反了」。
     * qm 没有等价字段，所以 QQ 侧的该字段恒为 null。
     */
    @SerializedName("originCoverType") val originCoverType: Int? = null,
    /**
     * 翻唱曲目**指向的原曲**（v2.3.0）。只在 [originCoverType] == 2 时出现（实测 198 条里 101 条）。
     * 用途：翻唱角标的副标题「原唱：<艺人> · <曲名>」。
     *
     * 它带 `songId`，可以精确回查 —— 这正是「这不是字符串匹配启发式」的证据。
     */
    @SerializedName("originSongSimpleData") val originSong: OriginSongRef? = null,
)

/**
 * ncm 逐曲 `privilege` 里**本应用用到的两个整数**（v2.3.0）。
 *
 * 只解析两个字段是有意的：整个 `privilege` 对象有 30+ 个 key（`chargeInfoList` /
 * `freeTrialPrivilege` / 各种 `*Level`），本版一个都不用 —— 探针只确证了这两个的语义。
 * 多解析一个没有实测支撑的字段，就是多一处「猜」。
 *
 * | 字段 | 实测语义 | 依据 |
 * |---|---|---|
 * | `pl` | **当前身份**在该曲上的可播最高码率；`> 0` ⇒ 一定能取到链 | 30/30，假阳性 0 |
 * | `st` | 状态码。`0` = 正常；`-200` = 下架/无版权；`-1` = **混合，不可用** | `st==0 && pl==0` ⇒ 30/30 不可播；`st==-1` ⇒ 15 可播 / 15 不可播 |
 */
@Immutable
data class SongPrivilege(
    @SerializedName("st") val st: Int? = null,
    @SerializedName("pl") val pl: Int? = null,
)

/**
 * ncm 的「无版权推荐」对象（v2.3.0）。
 *
 * @property typeDesc 服务端给用户的一句话（实测 `"其它版本可播"`）。中文原文，
 *   本版**只把它当作「确实无版权」的证据**，展示文案走本地化字符串，不直接回显。
 * @property type 推荐类型（实测 `2`）。语义未确证，不参与判定。
 */
@Immutable
data class NoCopyrightRecommendation(
    @SerializedName("typeDesc") val typeDesc: String? = null,
    @SerializedName("type") val type: Int? = null,
)

/**
 * 翻唱曲目指向的原曲（v2.3.0）。字段名与 ncm `originSongSimpleData` 一一对应。
 *
 * @property songId 原曲的 songId。**可精确回查**，所以「翻唱 → 原唱」这条关系可验证，
 *   不是名字匹配。
 * @property artists 原曲的艺人（实测与 `ar` 同构，用同一个 [ArtistItem]）。
 */
@Immutable
data class OriginSongRef(
    @SerializedName("songId") val songId: Long? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("artists") val artists: List<ArtistItem>? = null,
)

@Immutable
data class AlbumSearchItem(
    @SerializedName("id") val id: Long,
    @SerializedName("name") val name: String,
    @SerializedName("picUrl") val picUrl: String?,
    @SerializedName("artist") val artist: ArtistItem?,
    @SerializedName("publishTime") val publishTime: Long?,
    @SerializedName("size") val size: Int?,
    @SerializedName("company") val company: String?
)

@Immutable
data class ArtistSearchItem(
    @SerializedName("id") val id: Long,
    @SerializedName("name") val name: String,
    @SerializedName("picUrl") val picUrl: String?,
    @SerializedName("picId") val picId: Long?,
    @SerializedName("albumSize") val albumSize: Int?,
    @SerializedName("musicSize") val musicSize: Int?,
    @SerializedName("alias") val alias: List<String>?,
    @SerializedName("trans") val trans: String?
)