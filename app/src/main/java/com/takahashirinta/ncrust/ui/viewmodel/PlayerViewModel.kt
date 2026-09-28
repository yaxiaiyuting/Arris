/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 修改说明（Bug1「音质切换」）：
 *   - A：新增 onQualityPreferenceChanged()，设置页改音质后若正在播放且生效档位确实变化，
 *        立即按新档位对当前歌重新取链播放（不再等到下一首才生效）。
 *   - B：新增 preferredQualityIndex（偏好档位）与 qualityDowngraded（降级标记），
 *        与 currentQualityIndex（服务端实际返回档位）区分，供播放器 UI 提示"已降级"。
 *   - 抽出 effectivePreferredLevel()，统一设置页/播放路径的档位读取。 */

package com.takahashirinta.ncrust.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.takahashirinta.ncrust.cache.OfflineAudioCache
import com.takahashirinta.ncrust.cache.OfflineKeys
import com.takahashirinta.ncrust.cache.OfflineUrlStore
import com.takahashirinta.ncrust.network.NetworkAvailability
import com.takahashirinta.ncrust.player.QualityAssessment
import com.takahashirinta.ncrust.player.QualityLadder
import com.takahashirinta.ncrust.player.QualityStatus
import com.takahashirinta.ncrust.lyric.AmllTtmlClient
import com.takahashirinta.ncrust.lyric.LrcLine
import com.takahashirinta.ncrust.lyric.LrcParser
import com.takahashirinta.ncrust.lyric.YrcParser
import com.takahashirinta.ncrust.lyric.LyricCandidate
import com.takahashirinta.ncrust.lyric.LyricLoadCoordinator
import com.takahashirinta.ncrust.lyric.LyricSourceChain
import com.takahashirinta.ncrust.lyric.LyricSourceKind
import com.takahashirinta.ncrust.lyric.LyricSourcePrefs
import com.takahashirinta.ncrust.lyric.LyricTrack
import com.takahashirinta.ncrust.lyric.LyricTrackMerge
import com.takahashirinta.ncrust.lyric.LyricTrackSource
import com.takahashirinta.ncrust.lyric.cacheTag
import com.takahashirinta.ncrust.lyric.LyricsCache
import com.takahashirinta.ncrust.lyric.LyricsDisplayPrefs
import com.takahashirinta.ncrust.lyric.LyricsSweepQuality
import com.takahashirinta.ncrust.lyric.LyricsWordAnimationMode
import com.takahashirinta.ncrust.lyric.TtmlDoc
import com.takahashirinta.ncrust.lyric.TtmlParser
import com.takahashirinta.ncrust.bili.BiliSourceProvider
import com.takahashirinta.ncrust.network.BoundedParallel
import com.takahashirinta.ncrust.network.RetrofitClient
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.qq.QqApi
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.SourceIds
import com.takahashirinta.ncrust.source.SourceRouter
import com.takahashirinta.ncrust.source.TrackKey
import com.takahashirinta.ncrust.warmup.ListPrefetch
import com.takahashirinta.ncrust.source.isResolvable
import com.takahashirinta.ncrust.source.songRefOf
import com.takahashirinta.ncrust.player.AutoSkipGuard
import com.takahashirinta.ncrust.player.FailureKind
import com.takahashirinta.ncrust.player.GuardAction
import com.takahashirinta.ncrust.player.PlayOrigin
import com.takahashirinta.ncrust.player.PlaybackFailure
import com.takahashirinta.ncrust.player.PlaybackService
import com.takahashirinta.ncrust.player.QualityCeilingMemory
import com.takahashirinta.ncrust.player.QualityRetryGuard
import com.takahashirinta.ncrust.player.SourceFallbackHintGate
import com.takahashirinta.ncrust.player.classifyFailure
import com.takahashirinta.ncrust.player.ResolveFailure
import com.takahashirinta.ncrust.player.ResolveFailureAction
import com.takahashirinta.ncrust.player.ResolveFailureKind
import com.takahashirinta.ncrust.player.ResolveOutcome
import com.takahashirinta.ncrust.player.UrlRetryGate
import com.takahashirinta.ncrust.player.maySkipOnUrlFailure
import com.takahashirinta.ncrust.player.resolveFailureAction
import com.takahashirinta.ncrust.player.resolveFailureTextFor
import com.takahashirinta.ncrust.player.PlaybackStateManager
import com.takahashirinta.ncrust.player.PreloadCacheEntry
import com.takahashirinta.ncrust.player.PreloadCachePolicy
import com.takahashirinta.ncrust.player.PlayReporter
import com.takahashirinta.ncrust.player.SongUrlFetcher
import com.takahashirinta.ncrust.qq.QqProbeCounters
import com.takahashirinta.ncrust.player.SongUrlResult
import com.takahashirinta.ncrust.formatDuration
import com.takahashirinta.ncrust.ui.i18n.getSavedLanguageCode
import com.takahashirinta.ncrust.ui.i18n.stringsForCode
import android.widget.Toast
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import com.takahashirinta.ncrust.ui.theme.CoverThemeColors

class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    val isPlaying = MutableStateFlow(false)
    val currentPosition = MutableStateFlow(0L)
    val duration = MutableStateFlow(0L)
    val progress = MutableStateFlow(0f)
    val lyrics = MutableStateFlow<List<LrcLine>>(emptyList())
    // 当前 lyrics 内容所属的歌曲 id：-1 = 尚无(切歌后旧歌词只是渐隐过渡, 不算就绪)。
    // 让 UI 的 "lyricsReady" 判断基于"歌词属于当前歌", 而非"有没有歌词数组"——
    // 否则切歌后旧歌词残留会被误判为就绪, 把无歌词的新歌切回歌词视图显示旧歌词。
    val lyricsSongId = MutableStateFlow(-1L)
    // 外文歌词的译文(tlyric),Spotify 式渲染在原句下方。
    val translatedLyrics = MutableStateFlow<List<LrcLine>>(emptyList())
    /**
     * v1.9.2：音译轨（罗马音 / 粤拼），与 [translatedLyrics] 同构的行级轨道，来源可以是 TTML 的
     * `x-roman`，也可以是网易云的 `romalrc`，缺口按文本逐行回退（见 [LyricTrackMerge]）。
     *
     * **本版没有 UI 消费者**：v1.9.0/v1.9.1 从来没有渲染过音译（TtmlDoc.romans 无引用、
     * 网易云 romalrc 也没解析），要显示它必须给 LyricsView / NcrustLyricsPanel 加一个副文本槽，
     * 而「渲染层 diff 必须为空」是本版的硬约束。所以这里先把**数据层**做完整并单测覆盖，
     * 渲染接线留给解禁渲染层的版本（届时 LyricsView 只需多收一个参数）。
     */
    val romanizedLyrics = MutableStateFlow<List<LrcLine>>(emptyList())
    // 歌词是否仍在加载中(网络往返未返回)。UI 用它区分「真的没歌词」与「还没加载出来」:
    // 加载中或成功为空 → 全屏默认大封面、歌词按钮置灰;加载出非空 → 自动切回歌词视图。
    val lyricsLoading = MutableStateFlow(false)
    // 服务端明确答复"这首歌没有歌词"(code==200 且 lrc 为空)的歌曲 id。
    // 与「请求失败 / 尚未加载」严格区分：失败时保持 -1，歌词按钮仍可点（触发重试），
    // 避免一次网络抖动就把按钮永久置灰、必须切歌才能恢复。
    val lyricsNoContentSongId = MutableStateFlow(-1L)
    // 设置页开关:是否显示歌词翻译。默认开——外文歌直接看到双语,中文歌 tlyric 为空不受影响。
    val showLyricsTranslation = MutableStateFlow(true)

    /**
     * v1.9.3：是否显示音译轨（罗马音 / 粤拼）。**默认关**。
     *
     * 默认关是刻意的：音译数据（[romanizedLyrics]）是 v1.9.2 才进缓存的新资产，老用户升级后
     * 不该因为「服务端刚好有这份数据」就凭空多出一行小字。打开后渲染层按 timeMs 逐行配对，
     * 缺音译的行、空白行、与原文逐字相同的行都不显示（规则见 [com.takahashirinta.ncrust.lyric.LyricSubtitleText]）。
     *
     * 它只控制**显示**：与歌词源、缓存、请求全都无关，切换即时生效且不重取歌词。
     */
    val showLyricsRomanization = MutableStateFlow(false)

    /**
     * v2.0.0 · T4：动态字号（实验性，默认**关**）。只改每一句的 fontSize/lineHeight 倍率，
     * 与歌词源、缓存、请求全都无关；关掉时渲染路径与 v1.9.3 逐字节一致。
     */
    val showDynamicLyricFont = MutableStateFlow(false)
    // v1.5.1 · D：上一次推给媒体面板的歌词行（去重用，避免 2Hz 采样反复写同一个值）。
    private var lastMediaLyricLine: String? = null

    // v1.5.1 · E：字号落盘防抖窗口 —— A-/A+ 连点时只写最后一次。
    private val FONT_SCALE_WRITE_DEBOUNCE_MS = 150L
    // 设置页开关:逐字歌词(v1.5.0 · B，v1.5.1 · A 起是三模式)。默认「渐变扫过」——
    // 只在歌曲真的带 yrc 逐字数据时才有区别，没有逐字数据的歌行为与关掉完全一致。
    // 取值见 LyricsWordAnimationMode（0 渐变扫过 / 1 逐字硬切 / 2 关闭逐字）。
    val lyricsWordAnimation = MutableStateFlow(LyricsWordAnimationMode.GRADIENT_SWEEP)

    // v1.5.2：逐字扫过的绘制质量（0 自动 / 1 高级软边 / 2 兼容硬边）。默认「自动」——
    // 按设备是否 low-RAM 决定；两种画法的**光标位置算法完全相同**，所以切到兼容档只是
    // 少了渐变带的柔化，不会退回 v1.5.1 那种按词跳变。取值见 LyricsSweepQuality。
    val lyricsSweepQuality = MutableStateFlow(LyricsSweepQuality.AUTO)

    /**
     * v1.5.1 · D：是否把当前歌词行送进系统媒体面板（通知栏 / 锁屏 / 车机）。
     *
     * **默认关**：Android 13+ 的媒体面板第二行只认 ARTIST，开启后 ARTIST 会变成
     * 「艺人 · 歌词行」，这是对既有语义的改写 —— 只有用户明确打开才做。
     */
    val lyricsInMediaSession = MutableStateFlow(false)

    /**
     * v1.9.0：AMLL TTML 歌词源总开关（默认开）。关掉 = 一个 TTML 请求都不发，
     * 既有 LRC / yrc 路径原样保留，等价于 v1.8.1 的行为。
     */
    val lyricsTtmlEnabled = MutableStateFlow(true)

    /**
     * v1.9.0：TTML 与网易云歌词都可用时是否优先用 TTML（默认开）。
     * 只在 [lyricsTtmlEnabled] 开着时有意义 —— 关掉 TTML 时它不影响任何结果。
     */
    val lyricsTtmlFirst = MutableStateFlow(true)

    /**
     * v1.5.1 · E：歌词字号倍率（0.7~1.5，默认 1.0）。设置页与歌词界面的 A- / A+ 共用它，
     * 变更立即生效（StateFlow → 面板重组），落盘做 150ms 防抖（连续点按只写一次磁盘）。
     */
    val lyricsFontScale = MutableStateFlow(LyricsDisplayPrefs.FONT_SCALE_DEFAULT)
    private var fontScaleWriteJob: kotlinx.coroutines.Job? = null

    val isBuffering = MutableStateFlow(false)
    // Emits true when the current song enters the preload window (last 20 s).
    val needsPreload = MutableStateFlow(false)

    val currentSongId = MutableStateFlow<Long?>(null)
    val currentSongName = MutableStateFlow<String?>(null)
    val currentSongArtist = MutableStateFlow<String?>(null)
    val currentSongArtwork = MutableStateFlow<String?>(null)

    /**
     * 当前歌曲的**音源身份**（v2.1.0 · C；v2.1.5 收成一个不可变值）。
     *
     * v2.1.4 及更早这里是三个并列的 `var`，而且**只有 `playSong()` 会写它们** ——
     * 无缝预载的自动接续（`preloadNextSong` → `onMediaItemTransition` → `onSongTransitioned`）
     * 只更新了 `currentSongId`，三个音源字段全部留在上一首的取值上。
     * 于是跨源自动接续之后，取词仍然按**上一首的音源**路由 ——
     * 用户看到「音频已经是网易云，歌词还是 QQ 那首」。
     *
     * 现在只有一个写入点（本字段），三个旧名字退化成**只读派生值**：
     * 想改音源就必须改身份，结构上不可能再出现「id 换了、音源没换」的半更新状态。
     */
    private var currentTrack: TrackKey? = null
        set(value) {
            field = value
            currentSource.value = value?.source ?: MusicSource.NETEASE
        }

    private val currentSongSourceKey: String? get() = currentTrack?.source?.key
    private val currentSongSourceId: String? get() = currentTrack?.sourceId
    private val currentSongMediaId: String? get() = currentTrack?.mediaId

    /** 当前歌曲的音源（UI 用它画音源标识）。 */
    val currentSource = MutableStateFlow(MusicSource.NETEASE)

    private var onSongEndedCallback: (() -> Unit)? = null
    private var onSongPreviousCallback: (() -> Unit)? = null
    private var onSongTransitionedCallback: (() -> Unit)? = null
    private var onUnplayableCallback: (() -> Unit)? = null
    private var playJob: Job? = null
    private var preloadJob: Job? = null

    // 防止同一首歌重复上报播放行为。
    private var lastReportedSongId = -1L

    /** B4：本歌最近一次已落盘的进度，避免每 500ms 的采样都写一次 SharedPreferences。 */
    private var lastSavedPositionMs = -1L

    /** B4：本次起播的续播位置（0 = 从 0 分 0 秒开始）。 */
    val resumedFromMs = MutableStateFlow(0L)

    // Incremented on every explicit playSong call; lets preloadNextSong detect staleness.
    private var songPlayVersion = 0

    companion object {
        /**
         * 音质档位（低 → 高）。索引与 i18n qualityOptions 顺序、以及 eapi level 取值一一对应；
         * 索引越大音质越高，因此「实际索引 < 偏好索引」表示可能被降级（A3 起改用实际文件参数判定）。
         * A2 起档位表由 QualityLadder 提供，并新增 jymaster（超清母带）。
         */
        val QUALITY_LEVELS = QualityLadder.LEVELS

        /** B4：进度落盘间隔。2Hz 采样下最多每 5s 写一次，兼顾续播精度与 IO 开销。 */
        private const val POSITION_SAVE_INTERVAL_MS = 5_000L
    }

    /** 服务端**实际**返回的档位索引（可能因设备解码能力 / 会员权限 / 版权低于偏好）。 */
    val currentQualityIndex = MutableStateFlow(3)

    /** 用户**偏好**档位索引（由设置页 wifi / mobile 偏好 + 当前网络决定）。 */
    val preferredQualityIndex = MutableStateFlow(3)

    /**
     * 音质状态（A3）。不再用「level 字符串的档位序号」判断降级 —— 实测存在标签写 lossless、
     * 实际文件是 Hi-Res 的情况，也存在请求杜比拿到 jyeffect（不同容器）的情况。
     * 判定统一走 QualityAssessment：按实际文件参数（br/type）+ 该曲档位上限给结论。
     */
    val qualityStatus = MutableStateFlow(QualityStatus.NORMAL)

    /**
     * B2-C：跟随封面时的原始主题色（ARGB）。由 PlaybackService 的 Palette 结果静态回调推送，
     * null = 无封面 / 提取失败 → UI 回落预设色。饱和度压制与亮度锚定在 UI 侧按当前明暗处理
     * （同一张封面在深色/浅色下锚定区间不同，不能只按 URL 缓存处理结果）。
     */
    val coverAccentRgb = MutableStateFlow<Int?>(null)

    /**
     * v2.5.0 · A（色调）：封面取色的**多角色调色板**（深/浅两套一起给），
     * null = 无封面 / 灰度封面 / 提取失败 → UI 回落预设主题。
     *
     * 与 [coverAccentRgb] 是两条独立通路（后者走 `androidx.palette`、服务通知栏着色）：
     * 这一条走新增的 HCT 通路、服务界面主题。两条都在后台线程算完才推过来，
     * **UI 侧不需要再做任何颜色计算**（这正是"切深浅色立即正确"的前提 ——
     * 两套变体已经算好，切模式只是换一次字段读取）。
     */
    val coverTheme = MutableStateFlow<CoverThemeColors?>(null)

    private val qualityApiLevels = QUALITY_LEVELS

    /** 本次播放**请求**的档位；与 lastPlayedLevel（实际拿到）区分，用于判断偏好是否真的变了。 */
    private var lastRequestedLevel = ""

    // 播放出错(如设备解码不了 24-bit FLAC / 高采样率)时自动降档重试的阶梯。
    // 每出错一次降一档,到 standard 仍失败才跳歌:保证「有声音,或跳歌」,绝不静默卡住。
    //
    // v2.2.1 · P0：**阶梯本身没变，变的是谁能走几步**。
    // 旧实现让失败处理沿这条 8 档阶梯一路走到底，而实测（PCL110 / QQ《One Last Kiss》）
    // 走到底是这样的：dolby(Q001=6 声道 FLAC) 把 AudioSink 打坏 → 之后**每一档**都失败
    // → 8 次 setMediaItem/play = 8 次抢音频焦点 → 跳歌 → 下一首继续。
    // 现在由 [qualityRetryGuard] 限成最多 3 次、单调降档、且两次之间至少隔 10s。
    private val qualityRetryLadder = listOf(
        "jymaster", "dolby", "jyeffect", "hires", "lossless", "exhigh", "higher", "standard",
    )

    /** v2.2.1 · P0：降档重试熔断（单调 + 去重 + 上限 + 节流）。纯逻辑，见 PlaybackGuard.kt。 */
    private val qualityRetryGuard = QualityRetryGuard()

    /**
     * v2.2.1 · P0：本曲实测能拿到的最高档位（降级状态持久化）。
     *
     * 用户报障的一半是「重进页面又触发升级」：自动接续 / 预载每次都拿全局偏好去请求，
     * 而权益是按曲的。这里把「这首歌实际只能到哪一档」记下来（24h TTL），
     * **只约束自动路径** —— 用户手动切档位一律照请求走，并清掉该曲上限。
     */
    private val qualityCeiling = QualityCeilingMemory()

    /** v2.2.1 · P0：自动跳歌熔断（连续 5 次即停）。纯逻辑，见 PlaybackGuard.kt。 */
    private val autoSkipGuard = AutoSkipGuard()

    /**
     * v2.3.0 · C：「此源无版权，可切另一源」提示的节流闸（20s 一条）。
     *
     * 提示挂在「取链彻底失败 ⇒ 即将自动跳歌」这条**本来就会连续触发**的路径上，
     * 所以它自己必须也有界 —— 否则一段整体下架的歌单会连着弹 5 条提示。
     * 判定是纯逻辑（[SourceFallbackHintGate]），由 JVM 单测钉住。
     */
    private val sourceFallbackHintGate = SourceFallbackHintGate()

    /**
     * v3.2.0 · P0：取链**网络失败**的原样重试闸（每首歌最多 2 次、间隔 ≥3s）。
     *
     * 铁律 5（失败处理必须有界）在这一版新增的那个分支上的落点：
     * 「网络失败 → 重试，不跳歌」如果没有闸，「断网 + 一首放不出来的歌」
     * 就会变成一个取链空转环（v2.2.1 那次 P0 级联的同形状）。
     */
    private val urlRetryGate = UrlRetryGate()

    /**
     * v3.2.0 · P0：**失败分类 → 用户可读文案**。播放路径上唯一的失败提示出口。
     *
     * ## v3.1.0 的 `showSourceFallbackHint` 错在哪（本版重写它的理由）
     *
     * 旧实现不看失败原因，**一律**说「此源无版权，可切另一源：<另一家>」。
     * 实测（2026-09-28，真机 + 真 VIP 账号）QQ 对 VIP 歌在匿名态回 `result=104003`
     * （= 需要登录/会员），与版权**毫无关系**；网络抖动同样会走到这一句。
     * 于是用户看到的是「这个应用的版权数据是错的」——而真因是「你该登录 / 该开会员 / 网络不好」。
     *
     * 现在文案由 [resolveFailureText] 按分类产出（纯函数、JVM 单测覆盖），
     * **只有服务端显式声明无版权那一档**才会出现「暂无版权」字样（铁律 20）。
     *
     * 「可切另一源」的后缀只对三类失败追加（见 `suggestsOtherSource`）——
     * 对「未登录 / 凭证过期」不给这条后缀，因为那两件事在当前音源登录一下就能解决。
     */
    private fun showResolveFailureHint(failure: ResolveFailure) {
        val app = runCatching { getApplication<Application>() }.getOrNull() ?: return
        // 节流闸对**所有**失败提示生效（提示挂在本来就会连续触发的路径上，铁律 8）。
        if (!sourceFallbackHintGate.shouldHint(System.currentTimeMillis())) return
        val strings = stringsForCode(getSavedLanguageCode(app))
        val text = resolveFailureTextFor(failure, strings)
        Log.w("PlayerViewModel", "resolve failure hint: ${failure.diag()} -> $text")
        runCatching { Toast.makeText(app, text, Toast.LENGTH_LONG).show() }
    }

    /**
     * v2.2.1 · P0：本会话内**已经证明播不出来**的 URL（离线缓存 key）。
     *
     * 存在的理由：离线兜底会「退化成这首歌的任意档位」，于是把一条坏链反复喂回播放器
     * （实测固定点：请求 exhigh → 兜底给 lossless 的旧链 → 失败 → 又请求 exhigh → 又是同一条）。
     * 一个 URL 失败过就不再进第二次，是这条环路的最后一道闸。
     */
    private val failedUrlKeys = mutableSetOf<String>()

    /** v2.2.1 · P0：本次开播请求的来源。决定失败时是「重试 / 停下」还是「跳歌」。 */
    private var lastPlayOrigin: PlayOrigin = PlayOrigin.USER

    /**
     * v2.2.1 · P0：本会话里已经播成功过的最高档位（这首歌）。
     * 手动切音质失败时回退到这里继续播，而不是跳歌 —— 见 [handlePlaybackError]。
     */
    private var lastGoodLevel = ""


    // 上一次交给 PlaybackService 的 URL 的实际档位(fetch 内部可能已降级)。
    private var lastPlayedLevel = ""
    // A3：最近一次取链的输入，供偏好变化后重算状态（不重新取链）。
    private var lastVerdictRequested = ""
    private var lastVerdictResult: SongUrlResult? = null
    // 已处理过的出错点 (songId@level),配合时间窗防止同一错误反复触发重试。
    private var lastErrorKey = ""
    private var lastErrorHandledAt = 0L

    private var gaplessEnabled = false
    // 进入当前歌最后 60 秒即触发下一首预载: 取链(网络往返)+ ExoPlayer 准备/buffer
    // 需要充足时间, 20s 碰到慢网络/冷缓存会来不及, 无缝退化成硬切。
    private val PRELOAD_THRESHOLD_MS = 60_000L

    // Metadata for the in-flight preload; applied when ExoPlayer auto-transitions.
    // All reads/writes happen on the main thread.
    private var preloadedSongId = -1L
    private var preloadedTitle = ""
    private var preloadedArtist = ""
    private var preloadedArtwork = ""
    // A3：预载结果整体留存 —— 自动切歌时要据此重算音质状态（br/type/songMaxLevel 都要用）。
    private var preloadedResult: SongUrlResult? = null
    private var preloadedRequestedLevel = ""
    // Cached stream URL from the last completed preload; used by playSong fast-path to skip fetch.
    private var preloadedUrl = ""
    /**
     * v2.1.5：待播槽位里那一首的**完整身份**（含音源与 QQ 的 songmid / media_mid）。
     *
     * 旧实现只留了 [preloadedSongId] 这个裸 id，于是自动接续时音源无从得知 ——
     * 那正是跨源串台的根因。这里与 [PlaybackService] 的 pendingNext* 一一对应，
     * 并且随 preload_next intent 一起送过去，两端不会各记一份不一致的状态。
     */
    private var preloadedTrack: TrackKey? = null
    /**
     * v2.1.5：`PlaybackService` 从**真正起播的那一项**反解出的身份，在
     * [PlaybackService.onTrackTransitioned] 里写入、在 `onSongTransitioned` 里消费。
     *
     * 用一次性的暂存字段而不是直接改状态，是因为服务先报身份、后报「切歌了」，
     * 而状态更新必须发生在**同一个**主线程回合里，否则中间那一瞬 UI 会看到
     * 「新歌 id + 旧歌音源」的半更新状态 —— 那正是要消灭的东西。
     */
    private var transitionedTrack: TrackKey? = null
    // Song ID most recently requested by playSong; lets a concurrent preload detect a same-song race.
    private var latestPlaySongId = -1L

    // v3.1.0 · P0-B：条目与新鲜度判据搬到 `player/PreloadCachePolicy.kt`，
    // 原因是判据原本在这一处内联表达式里被抄了三遍，而 B 站需要**第二种 TTL 模型**
    // （服务端给的绝对过期时刻，不是一个时长）。字段与语义与 v3.0.0 逐字相同。
    private val preloadCache = mutableMapOf<Long, PreloadCacheEntry>()
    private val CACHE_TTL_MS = PreloadCachePolicy.DEFAULT_TTL_MS
    // Prevents duplicate preload launches for the same song while one is in flight.
    private var currentlyPreloadingSongId = -1L

    /**
     * v3.1.0 · P0-A：开播时的并行辅助腿（歌词 + 封面）。
     *
     * **刻意不在下一次 playSong 时 cancel 它** —— 它自带世代闸门
     * （`LyricLoadCoordinator`），旧歌的响应会被整包丢弃；而 cancel 会走进
     * `loadNeteaseLyrics` 那个会吞 `CancellationException` 的 `catch (e: Exception)`。
     * 保留这个引用只是为了诊断（`isActive`）与将来真的需要停掉它时有把手。
     */
    private var auxiliaryJob: Job? = null

    // ⚠️ 下面这三个**必须声明在 init 块之前**（v1.9.0 · S1 hotfix）。
    //
    // Kotlin 的属性初始化与 init 块**按声明顺序**执行，而 init 块里的
    // `viewModelScope.launch { fetchLyrics(...) }` 在 Main.immediate 下可能**在构造期就同步跑起来**
    // （构造本身就在主线程）—— 于是 fetchLyrics 会在构造函数返回之前执行。
    // 声明在 init 之后的属性此刻还是默认值：引用类型是 null，基本类型是 0。
    //
    // 实测崩溃（v1.9.0 首次真机安装，PCL110）：
    // `NullPointerException: Attempt to invoke virtual method 'long ...LyricRequestGate.begin()'
    //  on a null object reference` at PlayerViewModel.<init> → fetchLyrics。
    // 老代码里「正在取词的那首歌」是 Long（读成 0 只是判等失真，不崩），所以这个坑一直潜伏；
    // v1.9.0 新增的闸门、v2.1.5 新增的 [lyricCoordinator] 与 [STALE_NETEASE_LYRICS]
    // 都是**对象引用**，一读就炸。
    //
    // 单测抓不到：项目里没有 Robolectric，构造 AndroidViewModel 需要真 Application，
    // 所以只有「装到真机冷启」能暴露。**新增供 fetchLyrics 使用的字段时，一律放这一段。**

    // 同一首歌的歌词请求只允许一个在途(playSong / onSongTransitioned / 冷启动恢复
    // 会并发发起, 不打去重会瞬间打 3×n 个请求, 触发服务端限流反而更拉胯)。
    //
    // v2.1.5：判重的键从裸 songId 换成 [TrackKey]（裸 id 会把「网易云 123」与
    // 「QQ 123」当成同一首歌而误判为「已在途」，直接吞掉新歌的请求），
    // 并且**同时要求那次请求仍然是当前世代**。
    //
    // 后一条是真机踩出来的（S6 / API 24，跨源自动接续）：`MainActivity` 在切歌回调里
    // 会再调一次 `fetchLyricsForSong(当前 id)`，而那个方法会 `onTrackChanged` 让世代前进。
    // 只按「曲目相同」去重时，结果是**在途请求被作废、重发又被去重挡掉** ——
    // 表现是切歌后歌词永远不出现（`applyBestLyricSource` 一次都没跑，日志里只剩
    // 一行 `fetchLyrics cache hit`）。加上世代判据后，被作废的请求不再算「在途」，
    // 于是要么原请求继续有效、要么补发一次，不存在「两边都不干活」的窗口。
    private var activeLoad: LyricLoadCoordinator.Load? = null

    /**
     * v1.9.0 引入 / v2.1.5 收敛：歌词加载协调器（见 [LyricLoadCoordinator]）。
     *
     * 去重（[lyricsFetchingTrack]）挡的是「同一首歌的重复请求」，协调器挡的是
     * 「旧歌的响应盖掉新歌」**以及**「另一个音源的响应盖掉当前歌」——
     * 两件事必须分开：A 歌请求还在飞时切到 B，去重放行 B，协调器作废 A。
     *
     * 它同时拥有 UI 状态机（Idle / Loading / Loaded / Empty / Error）与「当前曲目身份」，
     * 所以「这份歌词属于哪首歌」只有一个答案，不再需要在每个挂起点各判一次。
     */
    private val lyricCoordinator = LyricLoadCoordinator()

    /**
     * v2.1.5 探针 tag（debug 包用来复盘「切歌 → 取词 → 渲染」这条链）。
     *
     * 存在的理由：跨源串台在日志里原本**看不出来** —— 旧代码只打 `songId=…`，
     * 而两首歌的 id 都「是当前歌」，出问题的维度（音源）根本没被记下来。
     * 现在每一环都带上 `音源:id`，`adb logcat -s NcrustTrack` 就能直接对照
     * 「起播的是谁 / 请求为谁发的 / 落到哪首上」。
     */
    private val TAG_TRACK = "NcrustTrack"

    /**
     * 播放器当前曲目身份（只读镜像，给诊断与渲染判据用）。
     *
     * 与 [currentTrack] 是同一个值；单独暴露是为了让「歌词是不是当前歌的」这个问题
     * 能在 ViewModel 之外被回答（探针页、日志）。
     */
    val currentTrackKey: TrackKey? get() = currentTrack

    /** 闸门判定「这次请求已经被新歌取代」时的返回：调用方拿到它只会原样丢弃，不会写任何状态。 */
    private val STALE_NETEASE_LYRICS = NeteaseLyrics("", "", "", "", authoritative = false)

    init {
        refreshGaplessSetting()
        // 从设置读歌词开关(默认开);设置页切换时经 setLyricsTranslation /
        // setLyricsWordAnimation 实时生效。readWordAnimation 顺带完成 v1.5.0 布尔开关的迁移。
        lyricsWordAnimation.value = LyricsDisplayPrefs.readWordAnimation(
            getApplication<Application>()
                .getSharedPreferences(LyricsDisplayPrefs.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        )
        showLyricsTranslation.value = getApplication<Application>()
            .getSharedPreferences("ncrust_settings", 0)
            .getBoolean("lyrics_translation", true)
        // v1.5.1 · E：歌词字号倍率（默认 1.0x —— 与 v1.5.0 的视觉完全一致）。
        lyricsFontScale.value = LyricsDisplayPrefs.readFontScale(
            getApplication<Application>()
                .getSharedPreferences(LyricsDisplayPrefs.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        )
        // v1.5.2：逐字扫过质量（默认自动）。
        lyricsSweepQuality.value = LyricsDisplayPrefs.readSweepQuality(
            getApplication<Application>()
                .getSharedPreferences(LyricsDisplayPrefs.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        )
        // v1.5.1 · D：媒体面板歌词开关（默认关，见字段注释）。
        lyricsInMediaSession.value = getApplication<Application>()
            .getSharedPreferences("ncrust_settings", 0)
            .getBoolean("lyrics_in_media_session", false)
        // v1.9.0：TTML 歌词源两个开关（默认都开）。read* 自身会把「键被写坏」回落成默认值。
        val lyricPrefs = getApplication<Application>()
            .getSharedPreferences(LyricsDisplayPrefs.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        lyricsTtmlEnabled.value = LyricsDisplayPrefs.readTtmlEnabled(lyricPrefs)
        lyricsTtmlFirst.value = LyricsDisplayPrefs.readTtmlFirst(lyricPrefs)
        // v1.9.3：音译显示开关（默认关）。同样是「键被写坏就回落默认」的安全读。
        showLyricsRomanization.value = LyricsDisplayPrefs.readRomanization(lyricPrefs)
        // v2.0.0 · T4：动态字号（实验性，默认关）。
        showDynamicLyricFont.value = LyricsDisplayPrefs.readDynamicFont(lyricPrefs)

        // v1.5.1 · D：把「当前行」推给 PlaybackService —— 只在**跨行**时写一次
        // （currentPosition 是 2Hz 采样，这里每次采样只做一次 O(行数) 的二分/线性比较，
        // 值没变就不写），通知栏因此不会逐帧重绘。关掉开关或无歌词时推 null。
        viewModelScope.launch {
            combine(lyrics, currentPosition, lyricsInMediaSession) { lines, pos, on ->
                Triple(lines, pos, on)
            }.collect { (lines, pos, on) ->
                val line = if (!on || lines.isEmpty()) null else lines.lastOrNull { it.timeMs <= pos }?.text
                if (line != lastMediaLyricLine) {
                    lastMediaLyricLine = line
                    PlaybackService.mediaLyricLine = line
                }
            }
        }

        PlaybackService.onProgressUpdate = { pos, dur ->
            currentPosition.value = pos
            duration.value = dur
            progress.value = if (dur > 0) pos.toFloat() / dur.toFloat() else 0f

            // v2.2.1 · P0：「确实播出声了」是自动跳歌熔断的**唯一**清零条件。
            // 不能拿「起播成功」当清零条件 —— 起播即失败的那些歌会把计数冲掉，
            // 5 次上限就永远数不到（实测那轮级联就是这么绕过一切防线的）。
            if (currentSongId.value != null && pos >= AutoSkipGuard.PROGRESS_CONFIRM_MS) {
                autoSkipGuard.onProgressConfirmed()
                // v2.5.4 · C：QQ 兜底统计的「真的播出声了」判据。
                //
                // 为什么挂在这里而不是「起播成功」：与上面那条熔断清零条件同源 ——
                // 「起播成功」不等于「出声了」（v2.2.1 的级联故障就是起播即失败）。
                //
                // 开销：本分支**已经在每 tick 执行**，新增的只是一次 Long 比较与一次
                // @Volatile 比较；`onPlaybackTick` 内部先用「本曲已确认」短路，
                // 所以实际每曲只自增一次。不新增集合查找、不新增分配、不新增调度、
                // 不碰 IO、不碰网络 —— 统计是旁路，不是功能（铁律 4）。
                QqProbeCounters.onPlaybackTick(currentSongId.value ?: -1L, currentTrack?.sourceId)
            }

            // 播放行为上报: 进度达 80% 视为"听完",每首歌只上报一次。
            val sid = currentSongId.value ?: -1L
            if (sid > 0 && sid != lastReportedSongId && PlayReporter.reachedCompletion(pos, dur)) {
                lastReportedSongId = sid
                PlayReporter.reportPlay(sid, pos, dur, end = "playend", isWifi = isOnWifi())
            }

            // B4：每 5s 落盘一次本歌进度，供断点续播；播完会由 onPlaybackEnded 清除。
            if (sid > 0 && kotlin.math.abs(pos - lastSavedPositionMs) >= POSITION_SAVE_INTERVAL_MS) {
                lastSavedPositionMs = pos
                PlaybackStateManager.saveSongPosition(getApplication(), sid, pos)
            }

            // Signal the preload window once per song (guarded by !needsPreload.value).
            if (gaplessEnabled && dur > 0 && pos > 1_000L && !needsPreload.value) {
                val remaining = dur - pos
                if (remaining in 1L..PRELOAD_THRESHOLD_MS) {
                    needsPreload.value = true
                }
            }
        }
        PlaybackService.onPlaybackEnded = {
            // 自然播放结束时,若尚未上报则补一条 playend。
            val sid = currentSongId.value ?: -1L
            // B4：正常播完 = 这首歌已「听完」，清除进度记录，重播时从 0 分 0 秒对齐。
            if (sid > 0) PlaybackStateManager.clearSongPosition(getApplication(), sid)
            if (sid > 0 && sid != lastReportedSongId) {
                lastReportedSongId = sid
                PlayReporter.reportPlay(sid, duration.value, duration.value, end = "playend", isWifi = isOnWifi())
            }
            onSongEndedCallback?.invoke()
        }
        PlaybackService.onPlaybackPrevious = { onSongPreviousCallback?.invoke() }
        PlaybackService.onIsPlayingChanged = { playing -> isPlaying.value = playing }
        PlaybackService.onBufferingChanged = { buffering -> isBuffering.value = buffering }
        // B2-C：封面 Palette 提取出的主题色（null = 无封面 / 提取失败 → UI 回落预设色）。
        PlaybackService.onCoverAccent = { rgb -> coverAccentRgb.value = rgb }
        // v2.5.0 · A（色调）：同一张封面的 HCT 多角色调色板（深/浅两套）。
        PlaybackService.onCoverTheme = { theme -> coverTheme.value = theme }
        // ExoPlayer 主线程回调。播放失败 → 降档重试,而不是无声地停在 IDLE。
        // v2.2.1 · P0：回调现在带失败描述（errorCode + cause），重试策略由
        // [QualityRetryGuard] 决定 —— 单调、去重、有上限、带音频焦点节流。
        // 回调在 ExoPlayer 主线程；重试里要做「音频焦点节流」的 delay()，所以丢进 viewModelScope。
        PlaybackService.onPlaybackError = { sid, failure ->
            // v2.5.4 · C：兜底曲目的失败计数（同样只对兜底 id 生效，内部短路去重）。
            QqProbeCounters.onPlaybackFailure(sid, currentTrack?.sourceId)
            viewModelScope.launch { handlePlaybackError(sid, failure) }
        }

        // Called on the main thread by ExoPlayer's onMediaItemTransition (AUTO reason).
        // v2.1.5：先接身份、再切状态。顺序不能反 —— 取词的路由依据就是这里的身份。
        PlaybackService.onTrackTransitioned = { source, songId, sourceId, mediaId ->
            transitionedTrack = if (songId > 0L) TrackKey(source, songId, sourceId, mediaId) else null
            Log.i(
                TAG_TRACK,
                "transition reported: track=" + (transitionedTrack?.toString() ?: "none") +
                    " (source=${source.key} id=$songId)"
            )
        }
        PlaybackService.onSongTransitioned = {
            // B4：无缝切换说明上一首已自然播完，清除其进度记录（重播从 0:00 对齐）。
            val finishedId = currentSongId.value ?: -1L
            if (finishedId > 0) PlaybackStateManager.clearSongPosition(getApplication(), finishedId)
            // v2.1.5：身份优先用**服务端（PlaybackService）从起播项反解出来的那一份**，
            // 本地 preloadedTrack 只作兜底。这是本版修的核心接缝：
            // 旧代码只更新 currentSongId，音源字段留在上一首上，取词于是问错平台。
            val nextTrack = transitionedTrack ?: preloadedTrack
            transitionedTrack = null
            if (preloadedSongId > 0 && nextTrack != null && nextTrack.id == preloadedSongId) {
                resetLyricsForNewSong()
                // ① 身份必须在任何取词动作**之前**落到 currentTrack。
                currentTrack = nextTrack
                currentSongId.value = nextTrack.id
                currentSongName.value = preloadedTitle
                currentSongArtist.value = preloadedArtist
                currentSongArtwork.value = preloadedArtwork
                preloadedResult?.let { applyQualityVerdict(preloadedRequestedLevel, it) }
                PlaybackStateManager.saveState(
                    getApplication(), nextTrack.id,
                    preloadedTitle, preloadedArtist, preloadedArtwork, true,
                    sourceKey = nextTrack.source.key, sourceId = nextTrack.sourceId,
                    mediaId = nextTrack.mediaId,
                )
                // ② 作废所有在途请求 + 置 Loading：旧歌词不会残留到新歌词就绪。
                lyricCoordinator.onTrackChanged(nextTrack)
                Log.i(
                    TAG_TRACK,
                    "auto transition -> currentTrack=$nextTrack " +
                        "cacheKey=${lyricCoordinator.cacheKeyOf(nextTrack)}"
                )
                viewModelScope.launch { fetchLyrics(nextTrack) }
                clearPreloadedState()
                needsPreload.value = false
            }
            onSongTransitionedCallback?.invoke()
        }

        val savedState = PlaybackStateManager.getState(getApplication())
        if (savedState != null) {
            resetLyricsForNewSong()
            // v2.1.5：续播状态里只存得下裸 id，而 QQ 的 id 带 bit62 标志位 ——
            // [TrackKey.of] 会据此把音源判成 QQ 音乐，不会再拿一个 2^62 的 id
            // 去问网易云的歌词接口（那必然查不到，表现是「恢复 QQ 歌曲永远没歌词」）。
            //
            // v2.2.1 · P0：**光有 bit62 不够 —— 取链要的是 songmid，不是音源名。**
            // 旧实现在这里写死 `TrackKey.of(null, savedState.songId)`，即使
            // `PlaybackStateManager` 里存着 songmid / media_mid 也**从不读**，
            // 于是冷启动后任何一次重取链（点播放 / 切音质 / 降档重试）对 QQ 曲目都是
            // `SourceRouter: unresolvable song source=qqmusic (missing sourceId)`，
            // 只能吃离线缓存里**另一个档位**的旧 URL。
            // 现在优先用落盘的身份；落盘没有时才退回 bit62 推断（老数据自愈路径）。
            val savedSourceKey = PlaybackStateManager.getSourceKey(getApplication())
            val savedSourceId = PlaybackStateManager.getSourceId(getApplication())
            val savedMediaId = PlaybackStateManager.getMediaId(getApplication())
            currentTrack = TrackKey.of(savedSourceKey, savedState.songId, savedSourceId, savedMediaId)
            Log.i(
                TAG_TRACK,
                "restore -> track=$currentTrack mid=${savedSourceId ?: "none"} " +
                    "media=${savedMediaId ?: "none"} (sourceKey=${savedSourceKey ?: "inferred"})",
            )
            currentSongId.value = savedState.songId
            currentSongName.value = savedState.songName
            currentSongArtist.value = savedState.songArtist
            currentSongArtwork.value = savedState.songArtwork

            // Activity 冷重建但前台 Service 还活着的场景：不能盲写 isPlaying=false，
            // 否则 UI 显示暂停但音频还在响，用户要点多次按钮才能让状态与音频对齐。
            // 直接从 live service 拉真值，同时把 duration/position 一并同步——
            // 否则 500ms 心跳到来前 duration=0，togglePlayPause 会误走全量 playSong 分支导致重取 URL。
            val svc = PlaybackService.instance
            if (svc != null) {
                runCatching {
                    val p = svc.player
                    isPlaying.value = p.isPlaying
                    val livePos = p.currentPosition
                    val liveDur = p.duration
                    if (liveDur > 0) {
                        currentPosition.value = livePos
                        duration.value = liveDur
                        progress.value = livePos.toFloat() / liveDur.toFloat()
                    }
                }
            } else {
                isPlaying.value = false
            }

            if (savedState.songId > 0) {
                // 身份就是上面落下的那一个（含 bit62 推断出的音源），不要再从裸 id 现构。
                currentTrack?.let { t -> viewModelScope.launch { fetchLyrics(t) } }
            }
        }
    }

    /**
     * v2.2.1 · P0：用**队列里那一份**曲目身份补齐当前曲目缺的 songmid / media_mid。
     *
     * 为什么需要：歌曲身份有两个来源 —— ① 落盘的单曲状态（只有 id + 音源，见
     * [PlaybackStateManager]）；② 队列里的 `SongItem`（**带** `mid` / `media_id`）。
     * 冷启动时 ① 是权威但信息少，而队列随后才恢复。旧代码在 ① 上就停住了，
     * 于是 QQ 曲目永远缺 songmid ⇒ 取不到链 ⇒ 只能吃离线缓存里别的档位的旧 URL。
     *
     * 本方法由 `MainScreen` 在恢复完队列后调用一次：只在**确实更全**时才覆盖，
     * 绝不把已有的 songmid 冲成 null。
     */
    fun adoptTrackIdentity(sourceKey: String?, songId: Long, sourceId: String?, mediaId: String?) {
        if (songId <= 0L || songId != currentSongId.value) return
        if (sourceId.isNullOrEmpty() && mediaId.isNullOrEmpty()) return
        val merged = TrackKey.of(
            sourceKey ?: currentTrack?.source?.key,
            songId,
            sourceId ?: currentTrack?.sourceId,
            mediaId ?: currentTrack?.mediaId,
        )
        if (merged == currentTrack) return
        Log.i(TAG_TRACK, "identity backfilled from queue -> $merged")
        currentTrack = merged
        lyricCoordinator.onTrackChanged(merged)
        // 身份变全了，歌词也值得重取一次（QQ 取词同样需要 songmid）。
        viewModelScope.launch { fetchLyrics(merged) }
    }

    fun setOnSongEndedCallback(callback: () -> Unit) { onSongEndedCallback = callback }
    fun setOnSongPreviousCallback(callback: () -> Unit) { onSongPreviousCallback = callback }
    fun setOnSongTransitionedCallback(callback: () -> Unit) { onSongTransitionedCallback = callback }
    fun setOnUnplayableCallback(callback: () -> Unit) { onUnplayableCallback = callback }

    /**
     * 切歌时立即清空旧歌词并置加载态。
     *
     * 上一版为了 Crossfade 渐隐而保留旧歌词, 切歌后最多 3 秒还挂着上一首的歌词,
     * 用户观感是"还停在旧歌"或"无歌词状态前闪一下旧歌词"——prog 长专整张连播时
     * 非常明显。这里直接清空: 旧歌词不再显示, 新歌词由 fetchLyrics 就绪后
     * PlayerCard 自动切回歌词视图; 确无歌词则由大封面盖住。
     */
    private fun resetLyricsForNewSong() {
        lyrics.value = emptyList()
        translatedLyrics.value = emptyList()
        romanizedLyrics.value = emptyList()
        lyricsLoading.value = true
        // 旧歌词是渐隐过渡素材, 不属于新歌; 在"当前歌歌词就绪"判定里立即失效
        lyricsSongId.value = -1L
        lyricsNoContentSongId.value = -1L
        // v1.5.2：位置也必须跟着一首歌一起翻篇。
        // 位置流是 2Hz 广播的，切歌那一两帧里 currentPosition 还是**上一首的末尾**；
        // 歌词面板（LyricsView）是拿 positionState.value 给外推做种子的，于是新歌词一到位就会
        // 用上一首的位置去二分找行 —— 落在新歌的最后一行，面板从第一行快速滚到最后一行、
        // 等真实位置（0）到了再滚回来。用户看到的就是「切歌时把歌词从头到尾过了一遍」。
        // 这里在**所有切歌路径共用的地方**归零，服务端随后会用 startPositionMs 广播真实值。
        currentPosition.value = 0L
        progress.value = 0f
    }

    fun resetPreloadFlag() { needsPreload.value = false }

    fun refreshGaplessSetting() {
        val prefs = getApplication<Application>().getSharedPreferences("ncrust_settings", 0)
        // 默认开启: 无缝预载是播放体验的一部分, 不该让大多数用户默默用着硬切换
        gaplessEnabled = prefs.getBoolean("gapless_playback", true)
    }

    /**
     * v1.5.0 · B / v1.5.1 · A：逐字动画模式。只影响行内渲染，不重取歌词
     * （词时间轴一直在 LrcLine 上），所以切换是即时的、不需要重新请求。
     */
    fun setLyricsWordAnimation(mode: Int) {
        val normalized = LyricsWordAnimationMode.normalize(mode)
        lyricsWordAnimation.value = normalized
        LyricsDisplayPrefs.writeWordAnimation(
            getApplication<Application>()
                .getSharedPreferences(LyricsDisplayPrefs.PREFS_NAME, android.content.Context.MODE_PRIVATE),
            normalized
        )
    }

    /**
     * v1.5.2：逐字扫过的绘制质量（自动 / 高级软边 / 兼容硬边）。
     *
     * 只影响**怎么画**，不重取歌词、不重建轨道，所以切换是即时的：
     * `softEdge` 只在 draw 阶段被读，改完下一帧就生效。
     */
    fun setLyricsSweepQuality(quality: Int) {
        val normalized = LyricsSweepQuality.normalize(quality)
        lyricsSweepQuality.value = normalized
        LyricsDisplayPrefs.writeSweepQuality(
            getApplication<Application>()
                .getSharedPreferences(LyricsDisplayPrefs.PREFS_NAME, android.content.Context.MODE_PRIVATE),
            normalized
        )
    }

    /** 设置页开关:歌词翻译开/关。写 SharedPreferences + 更新 StateFlow,播放器立即可见。 */

    /**
     * v1.5.1 · D：媒体面板歌词开关。关闭时立刻清掉已经写进 ARTIST 的歌词行，
     * 不等下一次跨行 —— 否则用户关了开关、通知栏还挂着上一句歌词。
     */
    fun setLyricsInMediaSession(enabled: Boolean) {
        lyricsInMediaSession.value = enabled
        getApplication<Application>()
            .getSharedPreferences("ncrust_settings", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("lyrics_in_media_session", enabled).apply()
        if (!enabled) {
            lastMediaLyricLine = null
            PlaybackService.mediaLyricLine = null
        }
    }

    /**
     * v1.5.1 · E：设置歌词字号倍率。立即生效；落盘防抖（3GB 设备上连续点 A-/A+
     * 不该每次都同步写一遍 SharedPreferences）。
     */
    fun setLyricsFontScale(scale: Float) {
        val clamped = scale.coerceIn(LyricsDisplayPrefs.FONT_SCALE_MIN, LyricsDisplayPrefs.FONT_SCALE_MAX)
        lyricsFontScale.value = clamped
        fontScaleWriteJob?.cancel()
        fontScaleWriteJob = viewModelScope.launch {
            delay(FONT_SCALE_WRITE_DEBOUNCE_MS)
            LyricsDisplayPrefs.writeFontScale(
                getApplication<Application>()
                    .getSharedPreferences(LyricsDisplayPrefs.PREFS_NAME, android.content.Context.MODE_PRIVATE),
                clamped
            )
        }
    }

    /** v5.1 · E：歌词界面里的 A- / A+ —— 在档位表上走一格。 */
    fun stepLyricsFontScale(delta: Int) {
        setLyricsFontScale(LyricsDisplayPrefs.steppedFontScale(lyricsFontScale.value, delta))
    }

    fun setLyricsTranslation(enabled: Boolean) {
        showLyricsTranslation.value = enabled
        getApplication<Application>().getSharedPreferences("ncrust_settings", 0)
            .edit().putBoolean("lyrics_translation", enabled).apply()
    }

    /**
     * v1.9.3：设置页「显示音译」开关。只改显示，不重取歌词（音译轨一直在 StateFlow 上），
     * 所以切换是即时的；落盘同样即时（它不是连续点按的控件，不需要字号那种防抖）。
     */
    fun setLyricsRomanization(enabled: Boolean) {
        showLyricsRomanization.value = enabled
        LyricsDisplayPrefs.writeRomanization(
            getApplication<Application>()
                .getSharedPreferences(LyricsDisplayPrefs.PREFS_NAME, android.content.Context.MODE_PRIVATE),
            enabled
        )
    }

    /**
     * v2.0.0 · T4：设置页「动态字号（实验性）」开关。只改显示，不重取歌词 ——
     * 倍率是纯函数（[com.takahashirinta.ncrust.lyric.DynamicLyricFont]），切换即时生效。
     */
    fun setDynamicLyricFont(enabled: Boolean) {
        showDynamicLyricFont.value = enabled
        LyricsDisplayPrefs.writeDynamicFont(
            getApplication<Application>()
                .getSharedPreferences(LyricsDisplayPrefs.PREFS_NAME, android.content.Context.MODE_PRIVATE),
            enabled
        )
    }

    /**
     * v1.9.0：AMLL TTML 歌词源开关。写盘 + **立即对当前歌重拉一次** ——
     * 「用哪一份歌词」是源选择的结果，本地缓存里存的是上一次选择的结果，
     * 不重拉的话用户会看到开关拨了却什么都没变。
     */
    fun setLyricsTtmlEnabled(enabled: Boolean) {
        lyricsTtmlEnabled.value = enabled
        LyricsDisplayPrefs.writeTtmlEnabled(prefs(), enabled)
        refetchLyricsForSourceChange()
    }

    /**
     * v1.9.0：「TTML 优先」开关。语义同样是源选择，因此与总开关一样需要重拉当前歌。
     * 总开关关着时这个值不影响任何结果（UI 侧此时也不挂载这一行）。
     */
    fun setLyricsTtmlFirst(first: Boolean) {
        lyricsTtmlFirst.value = first
        LyricsDisplayPrefs.writeTtmlFirst(prefs(), first)
        refetchLyricsForSourceChange()
    }

    private fun prefs(): SharedPreferences = getApplication<Application>()
        .getSharedPreferences(LyricsDisplayPrefs.PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 歌词源设置变化后重拉当前歌。复用 [retryLyrics]：它会清掉去重与「确无歌词」标记，
     * 这两件事都必须做 —— 否则同一首歌的第二次请求会被去重挡掉、脏标记还会让 UI 置灰。
     */
    private fun refetchLyricsForSourceChange() = retryLyrics()

    /**
     * 读取当前**生效的偏好档位**：设置页 wifi_quality / mobile_quality 按当前网络二选一。
     * 统一入口，避免设置页、播放路径、预载路径各写一份（Bug1 根因之一）。
     */
    private fun effectivePreferredLevel(prefs: SharedPreferences): String =
        if (isOnWifi()) qualityApiLevels.getOrElse(prefs.getInt("wifi_quality", 3)) { "lossless" }
        else qualityApiLevels.getOrElse(prefs.getInt("mobile_quality", 1)) { "higher" }

    /**
     * A3：记录一次取链结果并刷新音质状态。判定依据是**实际文件参数**（br/type）与该曲
     * 档位上限，而不是服务端返回的 level 字符串 —— 后者只是标签，可能写低（实测
     * granted=lossless 而 br=1,685,762，其实是 Hi-Res 文件）。
     */
    private fun applyQualityVerdict(requested: String, result: SongUrlResult) {
        lastPlayedLevel = result.actualLevel
        lastVerdictRequested = requested
        lastVerdictResult = result
        val verdict = QualityAssessment.assess(
            requested = requested,
            granted = result.actualLevel,
            br = result.br,
            type = result.type,
            songMaxLevel = result.songMaxLevel,
            // v2.2.1 · P0：QQ 前缀 / 离线 key 反推出的档位是**证据**，允许它触发诚实的「已降级」。
            levelFromFile = result.levelFromFile,
        )
        // v2.2.1 · P0：记下这条链上确实取到过 URL 的档位，供「音质路径取不到链」时回退。
        if (result.levelFromFile) lastGoodLevel = result.actualLevel
        // v2.2.1 · P0：降级状态持久化 —— 只记「确实低于请求档位」的情况，
        // 不记等于请求的情况（否则一次成功就会把上限钉死在当前档位）。
        val reqIdx = qualityApiLevels.indexOf(requested)
        val gotIdx = qualityApiLevels.indexOf(result.actualLevel)
        if (reqIdx >= 0 && gotIdx >= 0 && gotIdx < reqIdx) {
            qualityCeiling.remember(currentTrack?.toString() ?: "", result.actualLevel, System.currentTimeMillis())
        }
        currentQualityIndex.value = verdict.displayIndex
        qualityStatus.value = verdict.status
        // v2.1.4 · 诊断：把「这一次到底拿到了什么」打成一行。
        // 起因是用户报「选了超清母带却只出极高」—— 而档位标签显示的是**请求档位**，
        // 没有这一行就分不清「真的只拿到 320k」还是「拿到了高档位、只是界面没体现」。
        // Log.i（不是 Log.d）是有意的：release 包里也能读到，用户不必装 debug 包。
        Log.i(
            "PlayerViewModel",
            "quality verdict requested=$requested granted=${result.actualLevel}" +
                " br=${result.br} type=${result.type} songMax=${result.songMaxLevel}" +
                " -> displayIdx=${verdict.displayIndex} status=${verdict.status}",
        )
    }

    /** 偏好档位变化后重算状态（不重新取链，避免每次改设置都重播当前歌）。 */
    private fun refreshQualityVerdict() {
        val result = lastVerdictResult ?: return
        applyQualityVerdict(lastVerdictRequested, result)
    }

    /**
     * 设置页改动音质偏好后调用（Bug1-A）。
     *
     * 1. 先刷新 preferredQualityIndex / qualityStatus，让播放器标签立刻反映偏好变化；
     * 2. 若当前有歌在播、**且生效档位确实变化**，按新档位对当前歌重新取链播放。
     *    生效档位没变时直接返回——否则每次改设置都会把当前歌从头重播一次。
     */
    fun onQualityPreferenceChanged() {
        val prefs = getApplication<Application>().getSharedPreferences("ncrust_settings", 0)
        val newLevel = effectivePreferredLevel(prefs)
        preferredQualityIndex.value = qualityApiLevels.indexOf(newLevel).coerceAtLeast(0)
        refreshQualityVerdict()

        val sid = currentSongId.value ?: return
        if (sid <= 0L) return
        if (newLevel == lastRequestedLevel) return
        playSong(
            sid,
            title = currentSongName.value ?: "",
            artist = currentSongArtist.value ?: "",
            artworkUrl = currentSongArtwork.value ?: "",
            quality = newLevel,
            // v2.1.0 · C：换档重播必须带上**当前歌的音源** —— 不然 QQ 曲目会去网易云取链。
            sourceKey = currentSongSourceKey,
            sourceId = currentSongSourceId,
            mediaId = currentSongMediaId,
            // v2.2.1 · P0：用户手动切档 —— 清零重试额度（用户有权重新试探），
            // 且这条路径上的取链失败**不得**触发跳歌。
            origin = PlayOrigin.QUALITY_SWITCH,
        )
    }

    /**
     * P1：**大屏模式就地切换音质**（不再"跳设置页"）。
     *
     * 复用设置页那条现成通道，一处逻辑两处入口：
     *  1. 写**当前网络对应**的那一项偏好（Wi-Fi → `wifi_quality`，移动网络 → `mobile_quality`），
     *     与 UserScreen 的两个下拉完全一致 —— 否则在 Wi-Fi 下改的档位会污染移动网络的偏好；
     *  2. 复用 [onQualityPreferenceChanged]：只改偏好 + **档位真变了才**重新取链，
     *     不会因为点一次音质就把当前歌从头重播。
     *
     * 调用方（播放器里的音质选择器）因此不需要自己读/写 prefs，也不需要判断网络类型。
     */
    /**
     * P1：读**当前生效**的偏好档位（按当前网络在 `wifi_quality` / `mobile_quality` 里二选一），
     * 供大屏模式的就地选择器回显。
     *
     * 必须现读，不能直接用 [preferredQualityIndex]：那个 StateFlow 是"最近一次已知值"，
     * 只在播放歌曲 / 改过设置时刷新，冷启动后仍是默认档 3（无损）——
     * 实测 PCL110：偏好其实是「超清母带」，选择器却把「无损」高亮成当前项。
     * 顺带把读到的值写回 StateFlow，让别处读到的也是一致的。
     */
    fun currentQualityPreferenceIndex(): Int {
        val prefs = getApplication<Application>().getSharedPreferences("ncrust_settings", 0)
        val index = qualityApiLevels.indexOf(effectivePreferredLevel(prefs)).coerceAtLeast(0)
        preferredQualityIndex.value = index
        return index
    }

    /**
     * v2.1.4：当前正在播放的曲目，**只给 debug 取链诊断用**。
     *
     * 存在的理由：诊断必须拿到 `sourceId`（songmid）与 `mediaId`（media_mid），
     * 而这两个字段是 private 的 —— 它们只在开播与取链之间传递，没有任何 UI 订阅。
     * 与其让诊断再搜一次歌（那会引入「诊断的曲目与正在播的不是同一首」这个新变量），
     * 不如把 ref 原样交出去。release 包里没有调用方（入口整行不挂载）。
     */
    fun currentSongForDiagnostics(): SongItem? {
        val id = currentSongId.value ?: return null
        return songRefOf(
            MusicSource.fromKey(currentSongSourceKey),
            id,
            currentSongSourceId,
            currentSongMediaId,
        ).copy(
            name = currentSongName.value.orEmpty(),
        )
    }

    /**
     * v2.1.4：最近一次取链判定的输入与结果，**只给 debug 取链诊断用**。
     *
     * 分开报 requested / granted 是刻意的：这两行对不上，就说明「界面显示的档位」
     * 与「真正拿到的文件」不是一回事 —— 那正是用户报「选了母带却只能出极高」时
     * 最需要先排除的一种可能。
     */
    fun lastVerdictRequestedForDiagnostics(): String = lastVerdictRequested

    fun lastPlayedLevelForDiagnostics(): String = lastPlayedLevel

    fun lastVerdictResultForDiagnostics(): SongUrlResult? = lastVerdictResult

    fun setQualityPreference(index: Int) {
        if (index !in qualityApiLevels.indices) return
        // v2.2.1 · P0：用户手动选档位 = 重新试探 ⇒ 清掉本曲的降级上限与重试计数。
        qualityCeiling.clear(currentTrack?.toString() ?: "")
        qualityRetryGuard.resetAll()
        autoSkipGuard.onUserAction()
        // v3.2.0 · P0：用户手动改档位 = 他有权重新试一次 ⇒ 取链网络重试额度一并清零。
        urlRetryGate.onUserAction()
        val prefs = getApplication<Application>().getSharedPreferences("ncrust_settings", 0)
        prefs.edit()
            .putInt(if (isOnWifi()) "wifi_quality" else "mobile_quality", index)
            .apply()
        onQualityPreferenceChanged()
    }

    private fun isOnWifi(): Boolean {
        val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    fun playSong(
        songId: Long,
        title: String = "",
        artist: String = "",
        artworkUrl: String = "",
        quality: String = "",
        // v2.1.0 · C：音源三件套。默认值 = 网易云，因此所有既有调用点**零改动**且行为不变。
        // 只有 QQ 音乐的曲目需要显式带上（它必须要 songmid 才能取链）。
        sourceKey: String? = null,
        sourceId: String? = null,
        mediaId: String? = null,
        // B4 续播：-1 = 自动（读这首歌的进度记录）；>= 0 = 显式指定起播位置。
        // 显式传值的唯一场景是「播放失败降档重试」—— 那时必须沿用**当前**进度，
        // 不能退回记录里的旧位置，否则听感上会倒退一截。
        startPositionMs: Long = -1L,
        // v2.2.1 · P0：本次开播的**来源**。默认 USER 让所有既有调用点零改动，
        // 而失败处理据此把「音质问题」与「播放问题」分开（见 [handlePlaybackError]）。
        origin: PlayOrigin = PlayOrigin.USER,
    ) {
        songPlayVersion++
        val fetchVersion = songPlayVersion
        latestPlaySongId = songId
        lastPlayOrigin = origin
        // 计数清零条件（写死在这里，别处不许发明）：
        //  · 用户手动点播 / 手动切歌 → 自动跳歌计数清零；
        //  · 用户手动改音质        → 重试额度与「试过的档位」清零（用户有权重新试探）。
        //  · v3.2.0 新增：**换歌**（不是重试同一首）→ 取链网络重试额度清零。
        //    注意判据是 `origin != QUALITY_RETRY` 而不是「是不是用户点的」：
        //    自动接续 / 预载接管同样是**另一首歌**，那首歌该有自己的额度。
        when (origin) {
            PlayOrigin.USER -> {
                autoSkipGuard.onUserAction()
                urlRetryGate.onUserAction()
            }

            PlayOrigin.QUALITY_SWITCH -> {
                qualityRetryGuard.resetAll()
                urlRetryGate.onUserAction()
            }

            else -> Unit
        }
        if (origin != PlayOrigin.QUALITY_RETRY) urlRetryGate.onNewSong(currentTrack.toString())
        // 换歌（含用户手点）时忘掉上一首的「最后成功档位」。
        if (origin != PlayOrigin.QUALITY_RETRY) lastGoodLevel = ""
        // v2.1.0 · C：记住当前歌的音源身份 —— 取链、歌词、媒体通知都要用它路由。
        // v2.1.5：只此一处写 [currentTrack]，三个音源字段都是它的只读派生值。
        val track = TrackKey.of(sourceKey, songId, sourceId, mediaId)
        currentTrack = track
        lyricCoordinator.onTrackChanged(track)
        val ref = songRefOf(track.source, songId, sourceId, mediaId)
        Log.i(
            TAG_TRACK,
            "playSong -> currentTrack=$track cacheKey=${lyricCoordinator.cacheKeyOf(track)}"
        )
        // ★ v3.1.0 · P0-A：**取链与歌词同时发起**。
        //
        // v3.0.0 的形状是「取链（阻塞）→ 取链返回 → 才 launch 取词」，
        // 两段 RTT **相加**。探针实测这两段各自 P50 在 80~250ms 量级
        // （`docs/verification/v3.1.0/net-research/net-latency-breakdown.md` §1），
        // 相加就是用户感知里那一段「点了没反应」。
        //
        // 现在：身份先落地（上一行的 onTrackChanged），随后立刻开辅助腿
        //（歌词 + 封面），取链那条腿在下面照旧跑 —— 两者在时间上重叠。
        //
        // ⚠️ 为什么歌词**不**放进 playJob：playJob 会被下一次 playSong `cancel()`，
        // 而 `loadNeteaseLyrics` 的重试循环用的是 `catch (e: Exception)`，
        // 会把 CancellationException 一起吞掉（v2.5.2 点名的形状）。
        // 把歌词挂在 playJob 上会让「快速连点两首歌」把第一首的取词变成一次
        // 静默的半途而废。它们必须是兄弟，不是父子。
        startAuxiliaryLoad(track, artworkUrl)
        needsPreload.value = false
        refreshGaplessSetting()

        val prefs = getApplication<Application>().getSharedPreferences("ncrust_settings", 0)
        val requestedQuality = if (quality.isNotEmpty()) quality else effectivePreferredLevel(prefs)
        // v2.2.1 · P0：**自动路径不得自己升回原档位**。
        // 只有用户在列表/队列里点播（USER）与手动切档（QUALITY_SWITCH）才照请求走；
        // 自动接续 / 降档重试 / 预载接管一律不超过本曲记住的上限。
        val selectedQuality = when (origin) {
            PlayOrigin.USER, PlayOrigin.QUALITY_SWITCH -> requestedQuality
            else -> qualityCeiling.effectiveRequest(
                preferred = requestedQuality,
                key = track.toString(),
                ladderLowToHigh = qualityApiLevels,
                nowMs = System.currentTimeMillis(),
            )
        }
        if (selectedQuality != requestedQuality) {
            Log.i(
                "PlayerViewModel",
                "quality ceiling applied: preferred=$requestedQuality -> $selectedQuality (origin=$origin)",
            )
        }
        val qIdx = qualityApiLevels.indexOf(selectedQuality).coerceAtLeast(0)
        lastRequestedLevel = selectedQuality

        // B4：决定这首歌的起播位置。没有记录（或已播完被清除）就是 0 —— 即
        // 「每首歌自动从 0 分 0 秒播放对齐」；有记录则从上次退出点续播。
        val resumeMs = if (startPositionMs >= 0) startPositionMs
        else PlaybackStateManager.getSongPosition(getApplication(), songId)
        lastSavedPositionMs = resumeMs
        resumedFromMs.value = resumeMs
        if (resumeMs > 0) {
            // GUI 提示：明确告诉用户这是「从上次退出的时间点续播」，
            // 而不是进度条/歌词出了错（B4）。
            val app = getApplication<Application>()
            val text = stringsForCode(getSavedLanguageCode(app)).resumeFromFormat(formatDuration(resumeMs))
            Toast.makeText(app, text, Toast.LENGTH_SHORT).show()
        }
        // 显式传入 quality 的调用来自「播放失败降档重试」，那不是用户偏好，不能覆盖偏好档位。
        if (quality.isEmpty()) preferredQualityIndex.value = qIdx
        currentQualityIndex.value = qIdx
        qualityStatus.value = QualityStatus.NORMAL

        // Fast path: URL was preloaded and cached for THIS requested level — skip network round-trip.
        // 缓存条目带档位:播放失败降档重试时,绝不会把上一档(可能已证明播不出声)的 URL 原样喂回。
        val cachedEntry = preloadCache[songId]?.takeIf {
            // v3.1.0 · P0-B：判据的唯一落点（铁律 22：过期的 URL 不得使用）。
            PreloadCachePolicy.isFresh(it, selectedQuality, defaultTtlMs = CACHE_TTL_MS)
        }
        if (cachedEntry != null) {
            clearPreloadedState()
            applyQualityVerdict(
                selectedQuality,
                SongUrlResult(
                    cachedEntry.url, cachedEntry.actualLevel,
                    cachedEntry.br, cachedEntry.type, cachedEntry.songMaxLevel,
                ),
            )
            // v3.1.0 · P0-A：`resetLyricsForNewSong()` 与取词都已在 startAuxiliaryLoad 里
            // 于本函数**开头**做过一次。这里再 reset 会把已经落地的歌词（本地缓存命中时
            // 可能只要几毫秒）清掉 —— 那正是「切歌瞬间歌词闪一下空白」的形状。
            currentSongId.value = songId
            currentSongName.value = title
            currentSongArtist.value = artist
            currentSongArtwork.value = artworkUrl
            val intent = Intent(getApplication(), PlaybackService::class.java).apply {
                putExtra("url", cachedEntry.url)
                putExtra("title", title)
                putExtra("artist", artist)
                putExtra("artwork", artworkUrl)
                putExtra("songId", songId)
                putExtra("startPositionMs", resumeMs)
                // v2.1.0 · C：音源身份随 Intent 一起交给 PlaybackService（车机/通知要用）
                putExtra("sourceKey", ref.source)
                putExtra("sourceId", sourceId)
                putExtra("mediaId", mediaId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                getApplication<Application>().startForegroundService(intent)
            else
                getApplication<Application>().startService(intent)
            isPlaying.value = true
            PlaybackStateManager.saveState(
                getApplication(), songId, title, artist, artworkUrl, true,
                sourceKey = ref.source, sourceId = sourceId, mediaId = mediaId,
            )
            return
        }

        playJob?.cancel()
        playJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                // v2.0.0 · T3：离线优先取链（见 fetchUrlOfflineFirst）。
                // 旧顺序是「先把 5~6 档 eapi 全试一遍（每档都要等 connectTimeout）才回落缓存」，
                // 断网首播要干等数秒；现在明确离线时先离线兜底，命中就一个字节都不发。
                val outcome = fetchUrlOfflineFirst(ref, selectedQuality)
                val result = outcome.result
                if (result == null) {
                    // 该歌在所有音质档位都取不到可播放的 URL（无版权 / 需会员且当前无订阅）。
                    // 前一个版本会兜底喂给 ExoPlayer 一个 404 的 HTML 链接导致无限缓冲"卡住"，
                    // 现在改成交由 MainScreen 跳下一首，绝不播放坏链接。
                    //
                    // v2.2.1 · P0：**取不到链 ≠ 可以跳歌**。这里必须按来源分流：
                    //  · 音质切换 / 降档重试取不到链 —— 那是音质问题，跳歌等于把用户的
                    //    「换一档听听」变成「这首歌被跳过了」，而且会把整条队列一起带走；
                    //  · 自动接续取不到链 —— 才是真的「这首放不了」，而且还要过跳歌熔断。
                    // v3.2.0 · P0：把**分类**一起交出去 —— 它是「跳歌 / 重试 / 停下提示」
                    // 的唯一判据（铁律 20/21）。旧代码在这里丢掉原因，于是三种完全不同的
                    // 情况在播放链眼里长得一模一样。
                    withContext(Dispatchers.Main) {
                        onUrlUnavailable(songId, selectedQuality, origin, outcome.failure)
                    }
                    return@launch
                }
                // 取链期间若有更新的 playSong / 预载接管发生(版本号已前进),
                // 本次结果作废: 再发一次 "url" intent 会让 ExoPlayer setMediaItem
                // 把同一首歌重播一遍 —— 就是"听起来像拖带"的卡顿。
                if (fetchVersion != songPlayVersion) return@launch
                // setMediaItem 会替换整个播放列表，槽位随之作废。
                clearPreloadedState()
                // v3.1.0 · P0-A：取词与封面已在本函数开头并行发起（startAuxiliaryLoad），
                // 这里**只**替换播放列表。原先那两行（reset + launch）留在这里的含义是
                // 「等取链回来再开始取词」—— 那正是本版要拆掉的串行。
                withContext(Dispatchers.Main) {
                    applyQualityVerdict(selectedQuality, result)
                    currentSongId.value = songId
                    currentSongName.value = title
                    currentSongArtist.value = artist
                    currentSongArtwork.value = artworkUrl

                    val intent = Intent(getApplication(), PlaybackService::class.java).apply {
                        putExtra("url", result.url)
                        putExtra("title", title)
                        putExtra("artist", artist)
                        putExtra("artwork", artworkUrl)
                        putExtra("songId", songId)
                        putExtra("startPositionMs", resumeMs)
                        putExtra("sourceKey", ref.source)
                        putExtra("sourceId", sourceId)
                        putExtra("mediaId", mediaId)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        getApplication<Application>().startForegroundService(intent)
                    else
                        getApplication<Application>().startService(intent)
                    isPlaying.value = true
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // v2.1.5：协程取消**不是**错误，必须原样抛出。
                // 真机日志（S6 · 跨源切歌）实证：取链被 playJob?.cancel() 取消后
                // 这里打出了 `E PlayerViewModel: fetchUrl failed /
                // kotlinx.coroutines.JobCancellationException` —— 一条把正常控制流
                // 报成故障的噪声日志，会掩盖真正的取链失败。
                throw e
            } catch (e: Exception) {
                Log.e("PlayerViewModel", "fetchUrl failed", e)
            }
        }
    }

    /**
     * v1.6.0 · D1：离线兜底取链。
     *
     * 只有「离线 URL 清单里有这首歌」**且**「本地音频缓存里确实有对应片段」时才返回结果 ——
     * 两个条件缺一不可：只满足前者会拿一个指向空缓存的过期 URL 去起播，退化成网络
     * 403/404 的无限缓冲（正是 v1.3.0 明确禁止的那类坏链接）。
     *
     * 档位不严格要求一致（[OfflineUrlStore.recall] 会退化成「这首歌的任意档位」）：
     * 离线时能把这歌放出来，比死守用户选的档位重要得多。
     */
    private fun recallOfflineCache(songId: Long, level: String): SongUrlResult? {
        val app = getApplication<Application>()
        val hit = OfflineUrlStore.recall(app, songId, listOf(level)) ?: return null
        if (!OfflineAudioCache.contains(app, hit.first)) return null
        // v2.2.1 · P0：本会话里已经证明播不出来的那条链，不再喂第二次。
        //
        // 这一条是那个**固定点**的最后一道闸：离线清单会「退化成这首歌的任意档位」，
        // 于是「请求 exhigh → 兜底给 lossless 的旧链 → 失败 → 又请求 exhigh → 又是同一条」
        // 可以永远转下去（实测 logcat 里连续十几轮）。去掉重放之后，环必然会被打破。
        if (hit.first in failedUrlKeys) {
            Log.w("PlayerViewModel", "offline cache hit but already failed this session: ${hit.first}")
            return null
        }
        Log.i("PlayerViewModel", "offline cache hit songId=$songId key=" + hit.first)
        // v2.2.1 · P0：离线兜底拿到的档位**不一定等于请求档位**（recall 会退化成任意档位）。
        // 把这个事实如实带出去：levelFromFile=true 表示这个档位是从离线 key 反推的、
        // 不是服务端标签，QualityAssessment 据此可以给出诚实的「已降级」而不是沉默。
        return SongUrlResult(
            url = hit.second,
            actualLevel = OfflineKeys.levelOf(hit.first) ?: level,
            br = 0L,
            type = "",
            songMaxLevel = null,
            levelFromFile = true,
            fallbackFromLevel = level.takeIf { OfflineKeys.levelOf(hit.first) != level },
        )
    }

    /**
     * v2.0.0 · T3：离线优先取链。**在线路径与 v1.9.3 逐字一致**，只在「明确离线」时换个顺序：
     *
     * | 网络 | 顺序 | 结果 |
     * |---|---|---|
     * | 明确离线（[NetworkAvailability.isOnline] == false） | 先 [recallOfflineCache] | 命中 ⇒ 一个字节都不发，立刻起播 |
     * | 明确离线但缓存没命中 | 照旧走 [SongUrlFetcher.fetch] | 与旧版完全一样（慢，但不会因为一次网络判断把歌静默跳过） |
     * | 在线 / 判断失败（保守按在线） | 照旧 [SongUrlFetcher.fetch] → 失败再回落缓存 | 与 v1.9.3 逐字一致 |
     *
     * 只读一次系统网络状态、不开线程也不注册监听器；[NetworkAvailability] 的异常一律返回
     * true（按在线处理），与本仓库其它调用方（HomeScreen / AppWarmup）同一个判据。
     */
    private suspend fun fetchUrlOfflineFirst(ref: SongItem, level: String): ResolveOutcome {
        val app = getApplication<Application>()
        val songId = ref.id
        if (!NetworkAvailability.isOnline(app)) {
            recallOfflineCache(songId, level)?.let { return ResolveOutcome.ok(it) }
        }
        // v2.1.0 · A/C：取链从「直连 SongUrlFetcher」改为按音源路由。
        // 网易云一侧走的就是 NeteaseSourceProvider → SongUrlFetcher.fetch(id, level)，
        // 与 v2.0.2 **逐字节同一条路径**（8 档降级阶梯、FLAC 门控、离线 key 全在里面）。
        // 在线取链失败（典型是取链途中断网）时仍回落离线缓存 —— v1.6.0 · D1 的兜底路径。
        //
        // v3.2.0 · P0：这里从 `SongUrlResult?` 换成 `ResolveOutcome`（**带失败分类**）。
        // 离线兜底命中时是 Ok；在线取链失败**且离线也没命中**时才把分类交出去 ——
        // 也就是说「离线缓存救回来了」这件事优先于失败分类（能播就是不失败）。
        val outcome = SourceRouter.resolveUrlOutcome(ref, level)
        outcome.result?.let { return outcome }
        recallOfflineCache(songId, level)?.let { return ResolveOutcome.ok(it) }
        return outcome
    }

    /**
     * v2.2.1 · P0：取链彻底失败（所有档位都没有可播放 URL）时的分流。
     *
     * 这是「**音质切换失败 ≠ 播放失败**」这条铁律的落点，也是自动跳歌熔断的唯一入口。
     * 旧实现在这里无条件 `onUnplayableCallback()`（= 跳下一首），于是：
     *  · 用户切一下音质、恰好那一档取不到链 → 这首歌被跳过；
     *  · 整条队列都取不到链 → 5 首/秒 的无限跳歌（用户报的「不关应用就一直切」）。
     */
    private fun onUrlUnavailable(
        songId: Long,
        requested: String,
        origin: PlayOrigin,
        /**
         * v3.2.0 · P0：失败的分类。`null` 只可能来自旧调用点（离线兜底那条路），
         * 按 [ResolveFailureKind.UNKNOWN] 处理 —— 保守的那一侧（不跳歌）。
         */
        failure: ResolveFailure? = null,
    ) {
        val effective = failure ?: ResolveFailure(
            ResolveFailureKind.UNKNOWN,
            SourceIds.sourceOfId(songId),
        )
        Log.w(
            "PlayerViewModel",
            "no playable url songId=$songId requested=$requested origin=$origin " +
                "(${effective.diag()}) " +
                "(retryAttempts=${qualityRetryGuard.attemptsForCurrentSong()})",
        )
        // 判据抽在 PlaybackGuard.kt 里（纯函数 + 单测），这里只消费它 ——
        // 「哪些来源可以跳歌」这件事本身是被测过的，不是散在各处的 if。
        if (!maySkipOnUrlFailure(origin)) {
                // 音质路径：回退到本会话里最后一个确实取到过链的档位继续播；
                // 没有可回退的档位就停下等用户 —— **绝不跳歌**。
                val fallback = lastGoodLevel.takeIf { it.isNotEmpty() && it != requested }
                if (fallback != null) {
                    Log.w("PlayerViewModel", "quality path has no url at $requested, falling back to $fallback")
                    playSong(
                        songId,
                        title = currentSongName.value ?: "",
                        artist = currentSongArtist.value ?: "",
                        artworkUrl = currentSongArtwork.value ?: "",
                        quality = fallback,
                        startPositionMs = currentPosition.value,
                        sourceKey = currentSongSourceKey,
                        sourceId = currentSongSourceId,
                        mediaId = currentSongMediaId,
                        origin = PlayOrigin.QUALITY_RETRY,
                    )
                } else {
                    stopForQualityFailure(songId, "请求档位 $requested 取不到可播放链接")
                }
        } else {
            // ===== v3.2.0 · P0：失败路径**分离**（铁律 21）=====
            //
            // v3.1.0 在这里无条件「提示 + 跳歌」，于是「VIP 歌曲权限不足」「登录过期」
            // 「网络抖了一下」全部被当成「这首歌没有版权」并被跳过。现在按分类分流，
            // 判据是纯函数 `resolveFailureAction(kind)`（JVM 单测逐条覆盖）。
            when (resolveFailureAction(effective.kind)) {
                // ① 结构性取不到（缺 sourceId / 音源没注册 / 音源被关掉）：
                //    与版权无关，重试一万次也是同一个结果 ⇒ 这才是唯一允许跳歌的一类，
                //    并且仍然要过连续跳歌熔断。
                ResolveFailureAction.SKIP_ALLOWED -> {
                    Log.w(
                        "PlayerViewModel",
                        "unresolvable track songId=$songId (${effective.diag()}) -> skip",
                    )
                    if (autoSkipGuard.requestAutoSkip()) {
                        Log.w(
                            "PlayerViewModel",
                            "auto skip #${autoSkipGuard.consecutiveSkips} for songId=$songId",
                        )
                        onUnplayableCallback?.invoke()
                    } else {
                        showResolveFailureHint(effective)
                        stopForQualityFailure(
                            songId,
                            "连续自动跳歌已达上限 ${AutoSkipGuard.MAX_CONSECUTIVE_AUTO_SKIPS} 次",
                        )
                    }
                }

                // ② 网络失败：**有界重试同一个档位**，不跳歌。额度用尽则停下 + 提示。
                ResolveFailureAction.RETRY -> {
                    if (urlRetryGate.requestRetry(System.currentTimeMillis())) {
                        Log.w(
                            "PlayerViewModel",
                            "network failure, retry #${UrlRetryGate.MAX_ATTEMPTS_PER_SONG -
                                urlRetryGate.remaining()} songId=$songId level=$requested",
                        )
                        viewModelScope.launch {
                            delay(UrlRetryGate.MIN_INTERVAL_MS)
                            playSong(
                                songId,
                                title = currentSongName.value ?: "",
                                artist = currentSongArtist.value ?: "",
                                artworkUrl = currentSongArtwork.value ?: "",
                                quality = requested,
                                startPositionMs = currentPosition.value,
                                sourceKey = currentSongSourceKey,
                                sourceId = currentSongSourceId,
                                mediaId = currentSongMediaId,
                                origin = PlayOrigin.QUALITY_RETRY,
                            )
                        }
                    } else {
                        showResolveFailureHint(effective)
                        stopForQualityFailure(songId, "取链网络失败且重试额度已用尽")
                    }
                }

                // ③ 权限 / 版权 / 读不懂：**停下 + 说清楚原因，绝不跳歌**。
                //    这是本版与 v3.1.0 最大的行为差异，也是用户报的那个 P0 的正面修复。
                ResolveFailureAction.STOP_AND_INFORM -> {
                    showResolveFailureHint(effective)
                    stopForQualityFailure(songId, effective.diag())
                }
            }
        }
    }

    /**
     * 播放出错（设备解码不了 24-bit FLAC / 6 声道 FLAC / 坏链接）时的降档重试。
     *
     * ## v2.2.1 · P0 重写（旧实现是那次级联故障的放大器，逐条对照）
     *
     * | 旧行为 | 后果 | 现在 |
     * |---|---|---|
     * | 下一档由 `lastPlayedLevel`（**实际**档位）算 | 实际档位不随请求变化时形成固定点，永不收敛 | 由 [QualityRetryGuard.decide] 取 `min(请求, 实际)` 再严格降一档，并记住**试过哪些**，绝不重复 |
     * | 无次数上限 | 一首歌能重取 8 次 = 抢 8 次音频焦点 | 单曲上限 3 次（[QualityRetryGuard.MAX_ATTEMPTS_PER_SONG]） |
     * | 不看失败原因 | 输出链已经坏了还在同一实例上重试，必然全败 | [classifyFailure] 分类；SINK 类先把该档标成不可用，并依赖 Service 已 `stop()`（reset sink + 释放焦点） |
     * | 无节流 | 每次重试立刻 `play()` ⇒ 视频被反复打断 | 两次自动重取之间至少 [QualityRetryGuard.MIN_RESTART_INTERVAL_MS] |
     * | 最低档失败 ⇒ `onUnplayable` ⇒ 跳歌 | **音质问题被当成播放问题**，整条队列无限跳 | 自动重试路径**永不跳歌**：熔断后进「暂停 + 错误态」等用户手动操作 |
     */
    private suspend fun handlePlaybackError(songId: Long, failure: PlaybackFailure) {
        if (songId <= 0 || songId != currentSongId.value) return
        val kind = classifyFailure(failure)
        val now = System.currentTimeMillis()

        // 同一首歌的自动重试计数（换歌才清零）。
        qualityRetryGuard.onNewSong(currentTrack?.toString() ?: songId.toString())
        qualityRetryGuard.onFailure(now)

        // 同一 (歌 @ 档位) 在 1.5s 内的重复错误视为同一次故障 —— ExoPlayer 在拆链时
        // 可能连报几次（onPlayerError + onAudioSinkError），不能各算一次重试额度。
        val dedupeKey = "$songId@${lastRequestedLevel.ifEmpty { lastPlayedLevel }}"
        if (dedupeKey == lastErrorKey && now - lastErrorHandledAt < 1_500L) return
        lastErrorKey = dedupeKey
        lastErrorHandledAt = now

        // 本会话内不再重放这条链：离线兜底会「退化成这首歌的任意档位」，
        // 不记住失败的话，坏链会被反复喂回播放器（实测的固定点就是这么来的）。
        if (songId > 0 && lastPlayedLevel.isNotEmpty()) {
            failedUrlKeys.add(OfflineKeys.key(songId, lastPlayedLevel))
            if (failedUrlKeys.size > 128) failedUrlKeys.clear()
        }

        // 输出链故障：本档已经被证明会打坏 AudioSink，本首歌内不要再碰它。
        // 实测触发者是 QQ 的「臻品音质」档（Q001 = 6 声道 FLAC）：media3 的
        // ChannelMixingMatrix 没有 6→1 的系数，抛异常后 sink 不可恢复。
        if (kind == FailureKind.SINK) {
            qualityRetryGuard.markUnusable(lastPlayedLevel.ifEmpty { lastRequestedLevel })
        }

        val decision = qualityRetryGuard.decide(
            requested = lastRequestedLevel.ifEmpty { lastPlayedLevel },
            actual = lastPlayedLevel,
            ladder = qualityRetryLadder,
            nowMs = now,
        )
        Log.w(
            "PlayerViewModel",
            "playback error songId=$songId level=$lastPlayedLevel kind=$kind " +
                "cause=${failure.causeClass}: ${failure.causeMessage} -> ${decision.action} " +
                "${decision.nextLevel ?: ""} (${decision.reason})",
        )

        when (decision.action) {
            GuardAction.RETRY -> {
                // 音频焦点节流：不足 10s 就先等，绝不立刻再 play() 一次。
                if (decision.delayMs > 0) delay(decision.delayMs)
                qualityRetryGuard.onRetryStarted(System.currentTimeMillis())
                playSong(
                    songId,
                    title = currentSongName.value ?: "",
                    artist = currentSongArtist.value ?: "",
                    artworkUrl = currentSongArtwork.value ?: "",
                    quality = decision.nextLevel ?: return,
                    // 降档重试必须从**当前**进度接着播，不能读进度记录（那是上一次退出的位置）。
                    startPositionMs = currentPosition.value,
                    // v2.1.0 · C：降档重试同属「重播当前歌」，音源必须原样带着。
                    sourceKey = currentSongSourceKey,
                    sourceId = currentSongSourceId,
                    mediaId = currentSongMediaId,
                    origin = PlayOrigin.QUALITY_RETRY,
                )
            }

            GuardAction.STOP_AND_WAIT, GuardAction.SKIP_ALLOWED -> {
                // 退无可退：**不跳歌**。回到本条链路上最后一个确实播出过声的档位接着播；
                // 连那个都没有，就停下来进错误态，等用户手动操作。
                val fallback = lastGoodLevel.takeIf { it.isNotEmpty() && it != lastPlayedLevel }
                if (fallback != null && lastPlayOrigin == PlayOrigin.QUALITY_RETRY) {
                    Log.w("PlayerViewModel", "quality retry exhausted, falling back to last good level=$fallback")
                    playSong(
                        songId,
                        title = currentSongName.value ?: "",
                        artist = currentSongArtist.value ?: "",
                        artworkUrl = currentSongArtwork.value ?: "",
                        quality = fallback,
                        startPositionMs = currentPosition.value,
                        sourceKey = currentSongSourceKey,
                        sourceId = currentSongSourceId,
                        mediaId = currentSongMediaId,
                        origin = PlayOrigin.QUALITY_RETRY,
                    )
                } else {
                    stopForQualityFailure(songId, decision.reason)
                }
            }
        }
    }

    /**
     * v2.2.1 · P0：**有界失败的终点** —— 暂停 + 错误态，等用户手动操作。
     *
     * 这是「禁止无限循环」这条铁律的落点：无论失败多少次，最终一定走到这里，
     * 而不是继续重试、更不是跳到下一首。暂停同时让 media3 释放它持有的音频焦点，
     * 用户正在看的视频不再被打断。
     */
    private fun stopForQualityFailure(songId: Long, reason: String) {
        Log.w(
            "PlayerViewModel",
            "quality pipeline gave up for songId=$songId: $reason — pausing for user action",
        )
        isPlaying.value = false
        qualityStatus.value = QualityStatus.DOWNGRADED
        // 静音停下（保留当前歌与队列，用户点一下就能重来）。
        val intent = Intent(getApplication(), PlaybackService::class.java).apply {
            putExtra("action", "pause")
        }
        runCatching { getApplication<Application>().startService(intent) }
    }

    /**
     * 清空「待播槽位」的本地镜像（preloaded*），与 PlaybackService.clearPendingNext 成对。
     *
     * 任何一次**替换 ExoPlayer 播放列表**的开播动作都必须调用它（playSong 两条路径、
     * 预载接管、stopService）。否则残留的 preloadedSongId 会同时造成两个后果：
     *  1. preloadNextSong 误判「这首已经在槽位里」而不再预载 → 无缝播放静默失效；
     *  2. onSongTransitioned 把一首并不在播放列表里的歌当成已切歌 → 串台。
     */
    /**
     * v3.1.0 · P0-A：开播时的**并行辅助腿** —— 歌词 + 封面，与取链同时跑。
     *
     * 三件事都在这里定死，不在调用点各写一遍：
     * 1. **先清空上一首的歌词状态**（`resetLyricsForNewSong`）：它必须在取词**之前**，
     *    否则本地缓存命中时「新歌词先到、旧的清空后到」会把新歌词抹掉；
     * 2. **并发上限**走 [BoundedParallel.DEFAULT_MAX_CONCURRENCY]（铁律 23）；
     * 3. **取消原样传播**：`BoundedParallel` 对 `CancellationException` 直接 rethrow，
     *    不落进「子任务失败」那一支（铁律：不吞 CancellationException）。
     *
     * 封面这一腿用的是列表项**已有的** URL —— 它不产生新的元数据请求，
     * 只是把「播起来之后封面才开始解码」提前到「点下去就开始解码」。
     */
    private fun startAuxiliaryLoad(track: TrackKey, artworkUrl: String) {
        resetLyricsForNewSong()
        val app = getApplication<Application>()
        auxiliaryJob = viewModelScope.launch {
            BoundedParallel.runAll(
                listOf(
                    "lyrics" to {
                        fetchLyrics(track)
                        true
                    },
                    "cover" to {
                        ListPrefetch.prefetchCover(app, artworkUrl)
                        true
                    },
                ),
            )
            Unit
        }
    }

    /**
     * v3.1.0 · P0-B：把**下一首**的行级歌词预取进 [LyricsCache]，不进 UI 状态。
     *
     * ## 为什么不能直接调 `fetchLyrics(nextTrack)`
     *
     * 那条路会写 `lyrics` / `lyricsSongId` 这些**当前曲目**的状态流 ——
     * 预取一首还没播的歌会把界面上的歌词换成下一首的（正是 v2.1.5 串台的形状）。
     * 所以这里只做「请求 + 落缓存」：走的是与正式路径**同一张表、同一组字段**
     * （`LyricsCache.put`），切歌时 `loadNeteaseLyrics` 会以缓存命中把它秒回。
     *
     * 只对**网易云**曲目做：QQ 与 B 站的歌词不进这张表（字段形状不同，
     * 见 `loadQqLyrics` / `loadBiliLyrics` 的 KDoc），给它们预取等于白花一次请求。
     *
     * 失败完全静默：预取失败不该在日志里伪装成播放故障。
     */
    private suspend fun prefetchNeteaseLyrics(track: TrackKey) {
        if (track.source != MusicSource.NETEASE || track.id <= 0L) return
        runCatching {
            val resp = RetrofitClient.api.getLyric(id = track.id)
            if (resp.code == 200) {
                LyricsCache.put(
                    getApplication(),
                    track.id,
                    resp.lrc?.lyric ?: "",
                    resp.tlyric?.lyric ?: "",
                    resp.yrc?.lyric ?: "",
                    resp.romalrc?.lyric ?: "",
                )
                Log.d(TAG_TRACK, "prefetched lyric for next track=$track")
            }
        }
    }

    private fun clearPreloadedState() {
        preloadedSongId = -1L
        preloadedTitle = ""
        preloadedArtist = ""
        preloadedArtwork = ""
        preloadedResult = null
        preloadedRequestedLevel = ""
        preloadedUrl = ""
        // v2.1.5：身份也是槽位的一部分。漏清它会让下一次自动接续用**上一首的音源**
        // 去解释新起播的曲目 —— 与漏清 preloadedSongId 相比，这个错误更隐蔽。
        preloadedTrack = null
    }

    fun preloadNextSong(
        songId: Long,
        title: String,
        artist: String,
        artworkUrl: String,
        allowCurrent: Boolean = false,
        // v2.1.0 · C：与 playSong 同一组参数，默认值保证既有调用点零改动。
        sourceKey: String? = null,
        sourceId: String? = null,
        mediaId: String? = null,
    ) {
        // Dedup: skip only if the SAME song is already being fetched/in queue.
        // 不能因 URL 已缓存而整体跳过——缓存意味着"省的再取链", 但下一首仍需
        // addMediaItem 入 ExoPlayer 队列才能无缝切换; 否则缓存命中时直接 return,
        // ExoPlayer 队列永远只有当前一首, 播完必然走 songEnded→playNext 硬切(= 无缝失效)。
        //
        // v2.1.5：判重的键从裸 songId 换成 [TrackKey]。裸 id 会把「网易云 123」与
        // 「QQ 123」当成同一首歌 —— 在跨源队列里那会让真正该预载的那一首被静默跳过，
        // 无缝播放退化成硬切，且下一次自动接续没有身份可用。
        val nextTrack = TrackKey.of(sourceKey, songId, sourceId, mediaId)
        if (currentlyPreloadingSongId == songId) return
        // 把当前正在播的歌再入队(除单曲循环由 MainScreen 显式 allowCurrent 外)——
        // 队尾回绕/单曲队列等边界会让 [A,A] 自动过渡成"假单曲循环"。
        if (!allowCurrent && songId > 0 && nextTrack == currentTrack) return
        // v1.5.2 串台修复：这一首已经在待播槽位里 → 幂等跳过，绝不再发一次 preload_next。
        // preloadedSongId 是槽位的本地镜像，被消费（onSongTransitioned）或播放列表被替换
        // （clearPreloadedState）时清空。19f2969 拆掉上游「URL 已缓存就整体 return」之后，
        // 同一首下一曲会在「切歌瞬间」和「进入最后 60s」各预载一次，第二次会把同一个
        // media item 再 addMediaItem 一遍，播放列表变成 [当前, 下一首, 下一首']。
        if (songId > 0 && nextTrack == preloadedTrack) return

        val capturedVersion = songPlayVersion
        preloadJob?.cancel()
        currentlyPreloadingSongId = songId
        preloadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val prefs = getApplication<Application>().getSharedPreferences("ncrust_settings", 0)
                if (!prefs.getBoolean("gapless_playback", true)) {
                    currentlyPreloadingSongId = -1L
                    return@launch
                }
                // v1.9.0：顺手预取**下一首**的 TTML 歌词。这里是「下一首是谁」唯一的确定入口
                // （MainScreen 在切歌瞬间与进入最后 60s 各调一次，重复调用已被上面的槽位判断
                // 挡掉），所以挂在这里最准。单独 launch：预取是为了省掉切歌时的等待，
                // 绝不能反过来挤占取链、把播放拖慢；拿不到下一首时根本不会走到这里。
                // v3.1.0 · P0-B：下一首的**三样**辅助内容并行预取，与下面的取链同跑。
                // v3.0.0 只预取 TTML（且受开关门控），LRC 与封面都要等切歌那一刻才开始 ——
                // 而切歌那一刻正是最不该再花时间的地方。
                launch {
                    val aux = ArrayList<Pair<String, suspend () -> Boolean?>>(3)
                    if (lyricsTtmlEnabled.value) {
                        aux += "ttml" to {
                            AmllTtmlClient.prefetch(getApplication(), songId)
                            true
                        }
                    }
                    aux += "lyric-lrc" to {
                        prefetchNeteaseLyrics(nextTrack)
                        true
                    }
                    aux += "cover" to {
                        ListPrefetch.prefetchCover(getApplication(), artworkUrl)
                        true
                    }
                    runCatching { BoundedParallel.runAll(aux) }
                    Unit
                }
                val quality = if (isOnWifi())
                    qualityApiLevels.getOrElse(prefs.getInt("wifi_quality", 3)) { "lossless" }
                else
                    qualityApiLevels.getOrElse(prefs.getInt("mobile_quality", 1)) { "higher" }
                // 缓存命中(同档 + TTL 内)则跳过网络, 但仍走下方入队路径。
                val cacheHit = preloadCache[songId]?.takeIf {
                    PreloadCachePolicy.isFresh(it, quality, defaultTtlMs = CACHE_TTL_MS)
                }
                // v3.2.0 · P0：预加载这条路**只取成功的那一半** ——
                // 失败分类在预加载里没有消费者（预载失败什么都不做，等当前歌自然结束），
                // 但把它显式丢掉而不是让类型自己塌成 `Any`，是为了让下一个人一眼看到
                // 「预载路径不参与失败分流」这个事实。
                val result: SongUrlResult? = cacheHit?.let {
                    SongUrlResult(it.url, it.actualLevel, it.br, it.type, it.songMaxLevel)
                } ?: fetchUrlOfflineFirst(
                    songRefOf(nextTrack.source, songId, sourceId, mediaId),
                    quality,
                ).result
                if (result == null) {
                    // 预加载失败：可能无版权/无订阅，忽略即可，等当前歌结束时由 songEnded 跳歌。
                    // ⚠️ 这里是**预加载**，不是用户点了播放 —— 不提示、不跳歌、不改任何状态。
                    currentlyPreloadingSongId = -1L
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    currentlyPreloadingSongId = -1L
                    // Store in cache regardless of staleness — URL is valid even if a new song started.
                    preloadCache[songId] = PreloadCacheEntry(
                        result.url, result.actualLevel, quality, result.br, result.type, result.songMaxLevel,
                        // v3.1.0：把 Provider 给的绝对过期时刻带上（B 站必需；网易云/QQ 为 null）。
                        expiresAtMs = result.expiresAtMs,
                    )
                    if (capturedVersion != songPlayVersion) {
                        // playSong was called while this fetch was in flight.
                        // If it was for THIS same song and hasn't completed its own fetch, take over.
                        if (songId == latestPlaySongId && currentSongId.value != songId) {
                            // 接管 = 一次新的开播动作: 版本号前进, 让并发 playSong 的
                            // 取链结果在 fetchVersion 检查处作废, 杜绝二次 setMediaItem 重播。
                            songPlayVersion++
                            playJob?.cancel()
                            lastRequestedLevel = quality
                            applyQualityVerdict(quality, result)
                            // 接管同样是一次替换播放列表的开播，槽位镜像必须一并作废。
                            clearPreloadedState()
                            resetLyricsForNewSong()
                            // v2.1.5：接管也是一次开播，身份必须跟着走 ——
                            // 这里原先只写 currentSongId，音源会留在上一首上。
                            currentTrack = nextTrack
                            lyricCoordinator.onTrackChanged(nextTrack)
                            currentSongId.value = songId
                            currentSongName.value = title
                            currentSongArtist.value = artist
                            currentSongArtwork.value = artworkUrl
                            val intent = Intent(getApplication(), PlaybackService::class.java).apply {
                                putExtra("url", result.url)
                                putExtra("title", title)
                                putExtra("artist", artist)
                                putExtra("artwork", artworkUrl)
                                putExtra("songId", songId)
                                // 预载接管同样遵守续播语义（这次接管就是一次开播动作）。
                                putExtra(
                                    "startPositionMs",
                                    PlaybackStateManager.getSongPosition(getApplication(), songId)
                                )
                                // v2.1.5：音源身份随 Intent 交给服务，媒体会话/车机才不会再按
                                // 默认的网易云去解释一首 QQ 曲目。
                                putExtra("sourceKey", nextTrack.source.key)
                                putExtra("sourceId", sourceId)
                                putExtra("mediaId", mediaId)
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                                getApplication<Application>().startForegroundService(intent)
                            else
                                getApplication<Application>().startService(intent)
                            isPlaying.value = true
                            Log.i(TAG_TRACK, "preload takeover -> currentTrack=$nextTrack")
                            viewModelScope.launch { fetchLyrics(nextTrack) }
                            // 预载接管路径：这里没有 ref（它属于 playSong），用预载参数现构一个。
                            PlaybackStateManager.saveState(
                                getApplication(), songId, title, artist, artworkUrl, true,
                                sourceKey = nextTrack.source.key,
                                sourceId = sourceId,
                                mediaId = mediaId,
                            )
                        }
                        return@withContext
                    }
                    // Normal path: add to ExoPlayer queue for gapless auto-transition.
                    preloadedSongId = songId
                    preloadedTitle = title
                    preloadedArtist = artist
                    preloadedArtwork = artworkUrl
                    preloadedResult = result
                    preloadedRequestedLevel = quality
                    preloadedUrl = result.url
                    // v2.1.5：槽位的**完整身份**。少了这一行，自动接续时 ViewModel
                    // 无从知道起播的是哪个音源的歌 —— 那就是用户报的跨源串台。
                    preloadedTrack = nextTrack
                    val intent = Intent(getApplication(), PlaybackService::class.java).apply {
                        putExtra("action", "preload_next")
                        putExtra("url", result.url)
                        putExtra("title", title)
                        putExtra("artist", artist)
                        putExtra("artwork", artworkUrl)
                        putExtra("songId", songId)
                        // 槽位身份与服务端共享同一份事实，两端不会各记一份不一致的状态。
                        putExtra("sourceKey", nextTrack.source.key)
                        putExtra("sourceId", sourceId)
                        putExtra("mediaId", mediaId)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        getApplication<Application>().startForegroundService(intent)
                    else
                        getApplication<Application>().startService(intent)
                    Log.i(TAG_TRACK, "preload enqueued: slotTrack=$nextTrack")
                }
            } catch (e: Exception) {
                currentlyPreloadingSongId = -1L
                Log.w("PlayerViewModel", "Preload failed for songId=$songId", e)
            }
        }
    }

    /**
     * 外部（非播放路径）请求某首歌的歌词。
     *
     * v2.1.5：只有裸 id 时按 [TrackKey.of] 推断音源（QQ 的 id 带 bit62 标志位，
     * 推得出；songmid 推不出，所以 QQ 曲目会取不到词）。要拿到 QQ 歌词，
     * 调用方应当走播放路径 —— 那里有队列里的完整 [SongItem]。
     */
    fun fetchLyricsForSong(songId: Long) {
        if (songId <= 0L) return
        val bare = TrackKey.of(null, songId)
        // 已经是当前曲目时**原样沿用**现有身份，不要用只有裸 id 的那份覆盖它 ——
        // QQ 曲目的 songmid / media_mid 只存在于播放路径传下来的身份里，
        // 覆盖掉就等于把「能取到词」变成「缺 songmid 取不到」。
        val track = currentTrack?.takeIf { it == bare } ?: bare
        // 同一首歌不重复作废在途请求：本方法的语义是「请确保这首歌有歌词」，
        // 不是「换歌了」。无条件 onTrackChanged 会把刚发出的请求作废，
        // 而调用方（MainActivity 的切歌回调）恰恰是在自动接续的取词刚发出之后调它。
        if (currentTrack != track) {
            currentTrack = track
            lyricCoordinator.onTrackChanged(track)
        }
        viewModelScope.launch { fetchLyrics(track) }
    }

    /** v1.9.0：一次网易云歌词取数的结果。抽出来是为了让「缓存命中」与「网络返回」共用同一条源选择路径。 */
    private data class NeteaseLyrics(
        val lrc: String,
        val tlyric: String,
        val yrc: String,
        /** v1.9.2：音译轨原文（romalrc）。与 rv 参数无关，实测 rv=0 也会返回，缺 key 时为空串。 */
        val romalrc: String,
        /** 服务端 code==200 的**权威**答复（有歌词或确无歌词）；false = 瞬时失败，不能判成「确无歌词」。 */
        val authoritative: Boolean,
    )

    /** 网易云一侧解析好的三条轨（主轨 + 译文 + 音译），一次解析、两相共用。 */
    private data class NeteaseTracks(
        val lines: List<LrcLine> = emptyList(),
        val tlyric: List<LrcLine> = emptyList(),
        val romalrc: List<LrcLine> = emptyList(),
    )

    /** TTML 胜出时算好的两条副文本轨，连同它们的来源标记一起交给落地函数。 */
    private data class LyricTracks(
        val translation: LyricTrack = LyricTrack(),
        val roman: LyricTrack = LyricTrack(),
    )

    /**
     * v2.1.0 · C：QQ 音乐歌词（QRC 逐字 + 翻译 + 音译）。
     *
     * **不经过 `LyricsCache`**：那张表存的是网易云的字段形状（lrc/tlyric/yrc/romalrc/ttml），
     * 往里塞 QQ 的数据要么新加字段 + 迁移逻辑（v1.9.3 的教训：加字段 = 加迁移逻辑 = 加单测），
     * 要么污染网易云的字段语义。本版的取舍是**每次播放现取**（一次请求，实测 QRC 十几 KB），
     * 代价是断网时 QQ 曲目没有歌词 —— 已在 release notes 的未验证/已知问题里写明。
     * [LyricLoadCoordinator.cacheKeyOf] 对 QQ 曲目返回 null，把这条约定变成可执行的判据。
     *
     * 瞬时失败（网络错误）**什么都不写**，与网易云侧同一契约：歌词按钮保持可点、
     * 用户重试能再来一次；只有服务端明确回答（哪怕是「没有歌词」）才落状态。
     *
     * ## v2.1.5：身份来自参数，**不再读全局字段**
     *
     * 旧实现在这里读 `currentSongSourceKey` / `currentSongSourceId` 构造 ref。
     * 那正是跨源串台的最后一步：自动接续时它们是**上一首**的取值，
     * 于是「为网易云新歌取词」变成了「用上一首 QQ 曲目的 songmid 去问 QQ」——
     * 拿回来的是上一首的 QRC，而且因为请求确实是当前代而通过了所有闸门。
     * 现在 ref 只能由 [track] 构造，物理上不可能问错歌。
     */
    private suspend fun loadQqLyrics(track: TrackKey, load: LyricLoadCoordinator.Load) {
        val ref = songRefOf(track.source, track.id, track.sourceId, track.mediaId)
        if (!ref.isResolvable) {
            // QQ 曲目缺 songmid 时取词无从谈起。记进状态机而不是静默返回，
            // 这样探针能区分「没请求」与「请求了但没载荷」。
            lyricCoordinator.fail(load, "qq track missing sourceId (songmid)")
            Log.w(TAG_TRACK, "qq lyric skipped: track=$track missing songmid")
            return
        }
        val pack = runCatching { QqApi.fetchLyric(ref) }.getOrNull()
        if (pack == null) {
            lyricCoordinator.fail(load, "qq lyric request failed")
            return
        }
        if (!lyricCoordinator.isCurrent(load)) {
            // 旧响应后到 —— 整包丢弃（v2.1.5 的核心不变量）。
            Log.i(TAG_TRACK, "qq lyric DROPPED (stale): track=$track")
            return
        }
        if (pack.isEmpty) {
            // 服务端明确说「这首歌没有歌词」——记下来，UI 显示暂无歌词而不是一直转圈。
            lyricCoordinator.markEmpty(load)
            lyricsNoContentSongId.value = track.id
            return
        }
        val lines = pack.qrcLines
        if (!lyricCoordinator.accept(load, lines.size)) return
        lyrics.value = lines
        translatedLyrics.value = QqApi.alignToMainLines(lines, pack.transLines)
        romanizedLyrics.value = QqApi.alignToMainLines(lines, pack.romaLines)
        lyricsSongId.value = track.id
        lyricsNoContentSongId.value = -1L
        Log.i(TAG_TRACK, "qq lyric APPLIED track=$track lines=" + lines.size)
    }

    /**
     * v3.1.0 · B：B 站音源的取词。
     *
     * ## 两条腿的歌词能力不同，这里如实分派
     *
     * - **音频区曲目**（`sourceId = au:<auid>`）：`song/info` 的 `lyric` 字段就是
     *   **LRC 原文**，直接交给既有的 [LrcParser]（不新写解析器）。
     * - **视频音轨**（`sourceId = bv:...`）：B 站没有歌词数据源 ⇒
     *   `BiliSourceProvider.fetchLyric` 返回 null，这里记 [LyricLoadCoordinator.markEmpty]
     *   （「这首歌没有歌词」），而不是 [LyricLoadCoordinator.fail] ——
     *   重试一百次也不会有歌词，把它标成可重试的错误只会让界面白转圈。
     *
     * 缓存：**不走 [LyricsCache]**。那张表存的是网易云的字段形状
     * （lrc/tlyric/yrc/romalrc/ttml），往里塞 B 站的数据要么新加字段 + 迁移逻辑
     * （v1.9.3 的教训），要么污染网易云的字段语义 —— 与 QQ 那条路同一取舍
     * （见 `loadQqLyrics` 的 KDoc）。代价是每次播放现取一次，已写进未验证项。
     */
    private suspend fun loadBiliLyrics(track: TrackKey, load: LyricLoadCoordinator.Load) {
        val ref = songRefOf(track.source, track.id, track.sourceId, track.mediaId)
        val raw = runCatching { BiliSourceProvider.fetchLyric(ref) }.getOrNull()
        if (!lyricCoordinator.isCurrent(load)) {
            Log.i(TAG_TRACK, "bili lyric DROPPED (stale): track=$track")
            return
        }
        // null = 这个数据源没有歌词（视频音轨）；空串 = 确实是纯音乐。
        if (raw.isNullOrBlank()) {
            lyricCoordinator.markEmpty(load)
            lyricsNoContentSongId.value = track.id
            Log.i(TAG_TRACK, "bili lyric EMPTY track=$track (source=${if (raw == null) "none" else "blank"})")
            return
        }
        val lines = withContext(Dispatchers.Default) { LrcParser.parse(raw) }
        if (!lyricCoordinator.isCurrent(load)) return
        if (lines.isEmpty()) {
            lyricCoordinator.markEmpty(load)
            lyricsNoContentSongId.value = track.id
            return
        }
        if (!lyricCoordinator.accept(load, lines.size)) return
        lyrics.value = lines
        // B 站音频区只给一份主轨（没有 tlyric / romalrc 的对应接口），
        // 所以译文与音译显式清空 —— 而不是沿用上一首的（那正是 v2.1.5 的串台形状）。
        translatedLyrics.value = emptyList()
        romanizedLyrics.value = emptyList()
        lyricsSongId.value = track.id
        lyricsNoContentSongId.value = -1L
        Log.i(TAG_TRACK, "bili lyric APPLIED track=$track lines=" + lines.size)
    }

    /** 日志用：「来源/行数」。 */
    private fun trackText(track: LyricTrack): String =
        (track.source?.cacheTag ?: "none") + "/" + track.lines.size

    /**
     * v2.1.5：取词的**唯一入口**，身份由调用方显式传入。
     *
     * 签名从 `fetchLyrics(songId: Long)` 改成 `fetchLyrics(track: TrackKey)` 是本版的关键改动：
     * 旧签名只带得动一个裸 id，函数体于是只能去读全局的音源字段 ——
     * 而在跨源自动接续那条路径上那个字段是**上一首**的值。身份变成参数之后，
     * 「这次请求为谁发的」与「现在播的是谁」在同一个值上比较，串台没有生存空间。
     */
    private suspend fun fetchLyrics(track: TrackKey) {
        // 去重必须排在 begin() 之前：同歌并发时第二次直接返回；若先 begin()，第二次会把第一次
        // 的号作废 —— 第一次的结果整包丢弃、第二次又不干活，歌词反而永远出不来。
        // v2.1.5：判重的键含音源（跨源同号不会再互相吞掉请求），且必须**仍是当前世代** ——
        // 否则一次 `onTrackChanged(同一首歌)` 就能把在途请求作废并让重发被去重挡掉（见字段注释）。
        activeLoad?.let { if (it.track == track && lyricCoordinator.isCurrent(it)) return }
        // v1.9.0：领号。此后每个挂起点之后都要问一次「我还是当前号吗」，不是就整包丢弃；
        // 这是「A 歌请求还在飞、用户已切到 B 歌」时不把 A 的歌词盖到 B 上的唯一保证。
        // v2.1.5：号里同时带上**曲目身份**，跨源同号也分得开（见 LyricLoadCoordinator）。
        val load = lyricCoordinator.begin(track)
        activeLoad = load
        lyricsLoading.value = true
        Log.i(TAG_TRACK, "lyric request begin: track=$track gen=${load.generation}")
        try {
            // v2.1.0 · C：QQ 音乐的曲目走 QRC 直取（网易云那套两相取数链对它没有意义 ——
            // TTML DB 是按网易云 id 索引的，拿 QQ 的 id 去查只会 404，
            // 极小概率还会命中一首**完全无关**的歌的 TTML）。
            // v2.1.5：分叉依据是**这次请求的身份**，不是全局字段。
            if (track.source == MusicSource.QQMUSIC) {
                loadQqLyrics(track, load)
                return
            }
            // v3.1.0 · B：B 站音源的取词。**不能**落进下面那条网易云的链 ——
            // TTML DB 是按网易云 id 索引的，拿 B 站的合成 id 去查只会 404
            // （极小概率命中一首完全无关的歌的 TTML，与 QQ 那条路的理由逐字相同）。
            if (track.source == MusicSource.BILIBILI) {
                loadBiliLyrics(track, load)
                return
            }
            val netease = loadNeteaseLyrics(track, load)
            if (!lyricCoordinator.isCurrent(load)) return
            // 瞬时失败（风控 / 需登录 / 断网）：保持既有行为 —— 什么都不写、按钮保持可点可重试，
            // 也**不**去拉 TTML（拉 TTML 的前提是「已经拿到网易云的权威答复」）。
            if (!netease.authoritative) {
                lyricCoordinator.fail(load, "netease lyrics not authoritative")
                return
            }
            applyBestLyricSource(track, load, netease)
        } finally {
            // 只有本次请求仍是"当前在途"时才清加载态。旧歌请求的 finally 若在
            // 新歌请求在途时无条件清 lyricsLoading=false, 会让 UI 误判新歌"加载已结束
            // 且无歌词"→ 关歌词回封面, 表现为"听几首后歌词莫名不见"。
            if (activeLoad == load && lyricCoordinator.isCurrent(load)) {
                lyricsLoading.value = false
                activeLoad = null
            }
        }
    }

    /**
     * 取网易云那份歌词：先缓存、后网络（含既有 4 次退避重试）。
     *
     * 缓存命中也不在这里直接落地：TTML 开关是**播放期**策略，缓存里只有 LRC 原文，
     * 最终显示哪一份统一交给 [applyBestLyricSource] 用 [LyricSourceChain] 决定。
     */
    private suspend fun loadNeteaseLyrics(
        track: TrackKey,
        load: LyricLoadCoordinator.Load,
    ): NeteaseLyrics {
        val songId = track.id
        // 先查本地缓存：命中则不再打网络（歌词几乎不变）。
        // 这是进程被杀重进时歌词能秒回、且不受冷启动风控/限流影响的关键。
        val cached = LyricsCache.get(getApplication(), songId)
        if (!lyricCoordinator.isCurrent(load)) return STALE_NETEASE_LYRICS
        // v1.9.2：升级前写下的条目没有 romalrc 字段（Gson Unsafe ⇒ null）。那不是「这首歌没有音译」，
        // 而是「这份缓存没记过音译」—— 继续当命中用会让音译回退对升级用户永远不生效（LRC 条目没有
        // TTL，只有 200 条的 LRU 上限）。所以这种条目按 miss 处理，重取一次把字段补上；
        // 补完（哪怕是空串）就恢复缓存命中。**网络失败时回落到这份老缓存**（见 degraded()），
        // 不能让离线用户从「有歌词」变成「没歌词」。
        val legacyEntry = cached?.takeIf { LyricsCache.needsRomalrcRefetch(it) }
        if (cached != null && legacyEntry == null) {
            Log.d("PlayerViewModel", "fetchLyrics cache hit id=$songId lrc=${cached.lrc.length}")
            return NeteaseLyrics(
                cached.lrc, cached.tlyric, cached.yrc.orEmpty(), cached.romalrc.orEmpty(),
                authoritative = true
            )
        }
        if (legacyEntry != null) {
            Log.d("PlayerViewModel", "fetchLyrics 老缓存缺 romalrc，重取一次 id=$songId")
        }
        // 老缓存兜底：只在「本来就要打网络」的路径上用，语义与缓存命中完全一致
        // （缓存只写 code==200 的权威结果），没有条目时等价于原来的 STALE_NETEASE_LYRICS。
        fun degraded(): NeteaseLyrics = legacyEntry?.let {
            NeteaseLyrics(
                it.lrc, it.tlyric, it.yrc.orEmpty(), it.romalrc.orEmpty(), authoritative = true
            )
        } ?: STALE_NETEASE_LYRICS
        // 失败重试(最多 4 次, 递增退避): 冷启动时 AppWarmup 与恢复请求同时在
        // 打网络, 歌词请求的瞬时超时/限流不该让歌词永久消失。关键是**响应层面的
        // 失败也要重试** —— 服务端风控(-460/-462)或需登录(301)返回的 code!=200
        // 响应里 lrc 为空, 旧实现当成"这首歌没歌词"直接结束, 用户必须切歌才能
        // 重新触发加载; 这些失败码是瞬时的, 退避重试大概率能拿到真歌词。
        // code==200 是服务端的权威答复(有歌词或无歌词), 不再重试。
        repeat(4) { attempt ->
            try {
                val lyricResponse = RetrofitClient.api.getLyric(id = songId)
                // 已经切歌了就别再解析/重试：这一整轮请求的结果对当前歌没有任何意义。
                if (!lyricCoordinator.isCurrent(load)) return STALE_NETEASE_LYRICS
                val code = lyricResponse.code
                val lrcText = lyricResponse.lrc?.lyric ?: ""
                val tlyricText = lyricResponse.tlyric?.lyric ?: ""
                // v1.5.0 · B：逐字时间轴。实测该曲没挂逐字资产时响应里连 yrc 这个 key
                // 都没有(不是 null)，所以这里为空是常态，不是错误。
                val yrcText = lyricResponse.yrc?.lyric ?: ""
                // v1.9.2：音译轨。同一个响应里本来就有这个字段（实测与 rv 取值无关），
                // 此前一直没有解析，于是「TTML 胜出」时音译轨只能是 TTML 那一份（多半为空）。
                val romalrcText = lyricResponse.romalrc?.lyric ?: ""
                if (code != 200 && attempt < 3) {
                    Log.w("PlayerViewModel", "fetchLyrics unsettled songId=$songId code=$code, retry ${attempt + 1}")
                    delay(700L + attempt * 400L)
                    if (!lyricCoordinator.isCurrent(load)) return STALE_NETEASE_LYRICS
                    return@repeat
                }
                // 只缓存 code==200 的权威结果(有歌词/确无歌词)。code!=200 是瞬时
                // 失败(风控/需登录), 不写缓存也不置"确无歌词", 按钮保持可点可重试。
                if (code == 200) {
                    LyricsCache.put(
                        getApplication(), songId, lrcText, tlyricText, yrcText, romalrcText
                    )
                }
                // 瞬时失败（风控 / 需登录）且手上有老缓存：用它，别把已有歌词判成没有。
                if (code != 200) return degraded()
                Log.d(
                    "PlayerViewModel",
                    "fetchLyrics id=$songId code=$code lrc=${lrcText.length} " +
                        "tlyric=${tlyricText.length} yrc=${yrcText.length} " +
                        "romalrc=${romalrcText.length} current=${currentSongId.value}"
                )
                return NeteaseLyrics(
                    lrcText, tlyricText, yrcText, romalrcText, authoritative = code == 200
                )
            } catch (e: Exception) {
                // 失败不清空已有歌词(网络抖动不该把 UI 变空白), 重试后仍失败才退出
                if (attempt == 3) {
                    Log.e("PlayerViewModel", "fetchLyrics failed for songId=$songId", e)
                    return degraded()
                }
                delay(700L + attempt * 400L)
                if (!lyricCoordinator.isCurrent(load)) return STALE_NETEASE_LYRICS
            }
        }
        return STALE_NETEASE_LYRICS
    }

    /**
     * v1.9.0：按 [LyricSourceChain] 选源并落地。分两相，为的是**不让 TTML 拖住既有 LRC 的显示**：
     *
     *  1. 第一相只用网易云的候选（YRC / LRC）决策，选中立刻显示 —— 显示时机与 v1.8.1 完全一致；
     *  2. 第二相在「用户开了 TTML」时拉一次 TTML，连同第一相的候选重新 [LyricSourceChain.pick]
     *     一次，TTML 赢了才替换。TTML 是第三方镜像（最坏要跑 4 面镜子、每面 connect 5s），
     *     把它放在第一相之前，会把「本来就有歌词」变成「一直转圈」，那是对既有行为的倒退。
     *
     * 失败一律静默：拉不到 / 解析不出 / 没有逐字 span，都保留第一相的结果，不弹错、不新增 Toast。
     */
    private suspend fun applyBestLyricSource(
        track: TrackKey,
        load: LyricLoadCoordinator.Load,
        netease: NeteaseLyrics,
    ) {
        val songId = track.id
        val sourcePrefs = LyricSourcePrefs(
            ttmlEnabled = lyricsTtmlEnabled.value,
            ttmlFirst = lyricsTtmlFirst.value,
        )
        val order = LyricSourceChain.order(sourcePrefs)
        // 解析是 CPU 活(逐行正则), 放 Default 上跑, 别让主线程在切歌瞬间一边处理重组一边解 LRC。
        // v1.9.2：网易云三条轨（主轨 + 译文 + 音译）在**同一次** withContext 里解析完，
        // 第一相与第二相共用同一份结果 —— 合并只发生在一次 fetch 内部，不会把两次请求的数据拼起来。
        val parsed = if (netease.lrc.isNotEmpty()) {
            withContext(Dispatchers.Default) {
                NeteaseTracks(
                    lines = parseLyrics(netease.lrc, netease.yrc),
                    tlyric = if (netease.tlyric.isEmpty()) emptyList() else LrcParser.parse(netease.tlyric),
                    romalrc = if (netease.romalrc.isEmpty()) emptyList() else LrcParser.parse(netease.romalrc),
                )
            }
        } else {
            NeteaseTracks()
        }
        if (!lyricCoordinator.isCurrent(load)) return
        val neteaseHasWords = parsed.lines.any { it.words.isNotEmpty() }

        fun candidate(kind: LyricSourceKind, ttml: TtmlDoc?): LyricCandidate? = when (kind) {
            // YRC 与 LRC 都来自同一份网易云响应：行文本是同一份，差别只在有没有逐字时间轴。
            LyricSourceKind.YRC -> parsed.lines.takeIf { it.isNotEmpty() }
                ?.let { LyricCandidate(kind, it.size, neteaseHasWords) }
            LyricSourceKind.LRC -> parsed.lines.takeIf { it.isNotEmpty() }
                ?.let { LyricCandidate(kind, it.size, false) }
            LyricSourceKind.TTML -> ttml?.let {
                // hasWordLevel 必须由解析器判「有没有词」——只有整句的 TTML 投稿
                // 用它替换 LRC 只会白白丢掉网易云的行级数据。
                LyricCandidate(kind, it.lines.size, TtmlParser.hasWordLevel(it))
            }
        }

        // 第一相：网易云的候选按 order 排好（TTML 先缺席）交给 pick，选中就落地显示。
        val neteasePick = LyricSourceChain.pick(
            order.filter { it != LyricSourceKind.TTML }.mapNotNull { candidate(it, null) }
        )
        if (neteasePick != null) {
            applyNeteaseLyrics(track, load, parsed.lines, parsed.tlyric, parsed.romalrc)
            // v1.9.1：把「最终用了哪个源」显式打出来。此前只能从网络请求侧反推，
            // 独立验证者因此把「优先级开关是否真的改变选中源」列为未验证项（U3）——
            // 两相结构下「有没有发 TTML 请求」推不出「最后显示的是谁」。
            Log.i(
                "PlayerViewModel",
                "歌词源 songId=$songId phase=1 picked=${neteasePick.kind} " +
                    "lines=${neteasePick.lineCount} words=${neteasePick.hasWordLevel}"
            )
            // 歌词已经落到 StateFlow：先把加载态收掉，UI 才能立刻显示它（lyricsReady 要求
            // !lyricsLoading）。第二相只是「再挑一次、择优升级」，不该让它继续转圈。
            if (lyricCoordinator.isCurrent(load) && currentSongId.value == songId) {
                lyricsLoading.value = false
            }
        }

        // 第二相：只有用户开了 TTML 才发请求；关掉就一个字节都不拉（隐私开关不能形同虚设）。
        var ttmlWon = false
        if (sourcePrefs.ttmlEnabled && currentSongId.value == songId) {
            // AmllTtmlClient 自己吞掉网络异常并返回 null；这里的 runCatching 只是兜底
            // 「绝不让补充源把播放路径搞崩」—— 失败静默，走上面那一相的结果。
            val fresh = runCatching { AmllTtmlClient.load(getApplication(), songId) }.getOrNull()
            // v2.0.0 · T3：离线时用**过期** TTML 兜底。LyricsCache.getTtmlStale 在 v1.9.0 就
            // 写好了（KDoc 明说「给离线兜底用」）却一直没有调用者，后果是：离线时过期的 AMLL
            // TTML 直接消失，逐字退到网易云那份。这里补上，并且**只在明确离线时**兜底 ——
            // 在线时一个字节的行为都不变（在线拿不到就走 phase=1 的结果，与 v1.9.3 一致）。
            val raw = fresh ?: if (NetworkAvailability.isOnline(getApplication())) {
                null
            } else {
                runCatching { LyricsCache.getTtmlStale(getApplication(), songId) }.getOrNull()
            }
            if (!lyricCoordinator.isCurrent(load)) return
            val doc = if (raw.isNullOrBlank()) {
                null
            } else {
                withContext(Dispatchers.Default) { TtmlParser.parse(raw) }
            }
            if (!lyricCoordinator.isCurrent(load)) return
            if (doc != null) {
                val picked = LyricSourceChain.pick(order.mapNotNull { candidate(it, doc) })
                if (picked?.kind == LyricSourceKind.TTML) {
                    // v1.9.2：两条副文本轨在这里一次算完（纯函数，放 Default 上跑 —— 里面有一次
                    // O(n·m) 的 LCS）。withContext 是挂起点，回来必须复查闸门与当前歌。
                    val tracks = withContext(Dispatchers.Default) {
                        LyricTracks(
                            translation = LyricTrackMerge.merge(
                                doc.lines, doc.translations, parsed.lines, parsed.tlyric
                            ),
                            roman = LyricTrackMerge.merge(
                                doc.lines, doc.romans, parsed.lines, parsed.romalrc
                            ),
                        )
                    }
                    if (!lyricCoordinator.isCurrent(load) || currentSongId.value != songId) return
                    applyTtmlLyrics(track, load, doc, tracks)
                    ttmlWon = true
                    Log.i(
                        "PlayerViewModel",
                        "歌词源 songId=$songId phase=2 picked=TTML " +
                            "lines=${doc.lines.size} words=${TtmlParser.hasWordLevel(doc)} " +
                            "轨道 translation=${trackText(tracks.translation)} " +
                            "roman=${trackText(tracks.roman)} (覆盖了 phase=1)"
                    )
                } else {
                    // 拉了 TTML 但没赢（没有逐字 span / 排序后被 YRC 压过）—— 显式记一行，
                    // 否则「用户开了 TTML 却仍是整行」在线上无法归因。
                    Log.i(
                        "PlayerViewModel",
                        "歌词源 songId=$songId phase=2 picked=${picked?.kind} " +
                            "(TTML 未胜出，保留 phase=1)"
                    )
                }
            }
        }

        // 两个源都没得用，才是「确无歌词」：与既有语义一致，置灰歌词按钮。
        if (!ttmlWon && neteasePick == null &&
            lyricCoordinator.isCurrent(load) && currentSongId.value == songId
        ) {
            lyricsNoContentSongId.value = songId
            translatedLyrics.value = emptyList()
            romanizedLyrics.value = emptyList()
        }
    }

    /**
     * 把网易云那份落地：行来自 lrc（有 yrc 时已挂上逐字），译文来自 tlyric，音译来自 romalrc。
     *
     * 三条轨的解析已经在 [applyBestLyricSource] 里（Default 线程上）做完，这里只写状态。
     * 网易云源的两条副文本轨**按时间戳与原行配对**是既有语义（tlyric/romalrc 与 lrc 是同一份资产、
     * 时间戳同刻），v1.5.0 起就是这么显示的，本版一行不改 —— 分轨合并只在 TTML 胜出时介入。
     */
    private suspend fun applyNeteaseLyrics(
        track: TrackKey,
        load: LyricLoadCoordinator.Load,
        lines: List<LrcLine>,
        translations: List<LrcLine>,
        romans: List<LrcLine>,
    ) {
        val songId = track.id
        // 状态写入前必须重新确认「仍是当前号 + 仍是当前歌」。
        if (!lyricCoordinator.isCurrent(load) || currentSongId.value != songId) return
        if (lines.isNotEmpty()) {
            lyrics.value = lines
            // 有歌词：标记为"当前歌的歌词就绪"（供 UI 自动回切歌词视图）
            lyricsSongId.value = songId
            lyricsNoContentSongId.value = -1L
        }
        translatedLyrics.value = translations
        romanizedLyrics.value = romans
        // v1.9.2：记下这两条轨实际来自谁（观测字段；值未变则不落盘）。
        LyricsCache.putTrackSources(
            getApplication(),
            songId,
            LyricTrackSource.NETEASE.takeIf { translations.isNotEmpty() },
            LyricTrackSource.NETEASE.takeIf { romans.isNotEmpty() },
        )
    }

    /**
     * 把 TTML 那份落地（v1.9.2：分轨合并）。
     *
     * 主轨是 TTML 的行；译文轨与音译轨各自独立决定（[LyricTrackMerge]）：
     * TTML 那一轨能对上主轨时间戳的行原样用，**缺的行**按文本/行序回退到网易云的 tlyric / romalrc，
     * 对不上的逐行丢弃。v1.9.0 是直接把 `doc.translations.filter { ... }` 覆盖上去 ——
     * TTML 没有译文时那是个空表，会把刚显示的网易云译文**整轨清空**（实测 22704409）。
     *
     * 渲染层按 timeMs 精确配对（translatedLyrics.associateBy { timeMs }），而合并产出的每一行
     * 时间戳都取自 [TtmlDoc.lines]，所以这里不需要动渲染层一行。
     */
    private suspend fun applyTtmlLyrics(
        track: TrackKey,
        load: LyricLoadCoordinator.Load,
        doc: TtmlDoc,
        tracks: LyricTracks,
    ) {
        val songId = track.id
        if (currentSongId.value != songId) return
        lyrics.value = doc.lines
        translatedLyrics.value = tracks.translation.lines
        romanizedLyrics.value = tracks.roman.lines
        lyricsSongId.value = songId
        lyricsNoContentSongId.value = -1L
        // v1.9.2：记下两条轨实际来自谁（观测字段；值未变则不落盘）。
        LyricsCache.putTrackSources(
            getApplication(), songId, tracks.translation.source, tracks.roman.source
        )
    }

    /**
     * v1.5.0 · B：统一的歌词解析入口。有 yrc 就把逐词时间轴挂到 LRC 行上，没有就只解 LRC。
     * [YrcParser.attachWords] 不会增删行、也不改行文本与时间戳 —— 关掉逐字开关后渲染结果
     * 与 v1.4.1 逐字节一致。
     */
    private fun parseLyrics(lrcText: String, yrcText: String): List<LrcLine> {
        val lines = LrcParser.parse(lrcText)
        return if (yrcText.isEmpty()) lines else YrcParser.attachWords(lines, yrcText)
    }

    /**
     * 手动重试当前歌歌词。请求失败后用户点歌词按钮可再次触发加载；
     * 清掉去重与"确无歌词"标记，强制重新请求（失败结果本就不入缓存）。
     */
    fun retryLyrics() {
        // v2.1.5：重试的是**当前曲目的身份**，不是裸 id —— 否则 QQ 曲目会被
        // 当成网易云同号歌去重取（这正是跨源串台的反向版本）。
        val track = currentTrack ?: return
        if (track.id <= 0L) return
        lyricsNoContentSongId.value = -1L
        lyricsLoading.value = true
        activeLoad = null
        viewModelScope.launch { fetchLyrics(track) }
    }

    /**
     * 只载入歌曲元数据, 不取链不播放(剪贴板分享的单曲场景)。
     * 之后用户按下播放键 → togglePlayPause 检测到 duration==0 且 songId>0,
     * 自动走全量 playSong 路径开播。
     */
    fun prepareSongWithoutPlay(songId: Long, title: String, artist: String, artworkUrl: String) {
        latestPlaySongId = songId
        resetLyricsForNewSong()
        // v2.1.5：身份必须与 currentSongId 同步落下（旧代码只写 id，音源留在上一首上）。
        val track = TrackKey.of(null, songId)
        currentTrack = track
        lyricCoordinator.onTrackChanged(track)
        currentSongId.value = songId
        currentSongName.value = title
        currentSongArtist.value = artist
        currentSongArtwork.value = artworkUrl
        currentPosition.value = 0L
        duration.value = 0L
        progress.value = 0f
        isPlaying.value = false
        viewModelScope.launch { fetchLyrics(track) }
        PlaybackStateManager.saveState(
            getApplication(), songId, title, artist, artworkUrl, false
        )
    }

    fun togglePlayPause() {
        val songId = currentSongId.value
        // 只有"已载入但从未开播"的场景才走全量 playSong(剪贴板单曲/冷启动恢复态)。
        // duration==0 且当前曲正在播(如无缝过渡后心跳还没把 duration 同步回来)时
        // 若走 playSong 会重取 URL + setMediaItem, 把正在播的歌从 0 重播一遍=拖带感。
        if (duration.value == 0L && songId != null && songId > 0 && !isPlaying.value) {
            playSong(
                songId,
                title = currentSongName.value ?: "",
                artist = currentSongArtist.value ?: "",
                artworkUrl = currentSongArtwork.value ?: "",
                sourceKey = currentSongSourceKey,
                sourceId = currentSongSourceId,
                mediaId = currentSongMediaId,
            )
            return
        }
        isPlaying.value = !isPlaying.value
        val intent = Intent(getApplication(), PlaybackService::class.java).apply {
            putExtra("action", if (isPlaying.value) "resume" else "pause")
        }
        getApplication<Application>().startService(intent)
    }

    /** 仅暂停播放,保留当前歌曲与队列(LINE 模式队尾停用)。 */
    fun pausePlayback() {
        isPlaying.value = false
        val intent = Intent(getApplication(), PlaybackService::class.java).apply {
            putExtra("action", "pause")
        }
        getApplication<Application>().startService(intent)
    }

    fun seekTo(position: Long) {
        // 乐观更新：startService 是异步的，等 PlaybackService 回调会有可感知延迟；
        // 暂停态下 ticker 又不广播，不在这里更新就会"拖了没反应"。
        // 服务端 seek 完成后会再广播一次权威值，覆盖这里的估算。
        val dur = duration.value
        currentPosition.value = position
        if (dur > 0) progress.value = (position.toFloat() / dur.toFloat()).coerceIn(0f, 1f)

        val intent = Intent(getApplication(), PlaybackService::class.java).apply {
            putExtra("action", "seek")
            putExtra("position", position)
        }
        getApplication<Application>().startService(intent)
    }

    fun stopService() {
        val app = getApplication<Application>()
        PlaybackStateManager.clearState(app)
        PlaybackStateManager.clearQueue(app)
        // v1.9.0：退出播放器时作废所有在途的歌词请求，别让它们的响应落回已经清空的播放器状态。
        lyricCoordinator.invalidate()

        val intent = Intent(app, PlaybackService::class.java).apply {
            putExtra("action", "stop")
        }
        app.startService(intent)
        isPlaying.value = false
        clearPreloadedState()
        resetLyricsForNewSong()
        currentSongId.value = null
        currentSongName.value = null
        currentSongArtist.value = null
        currentSongArtwork.value = null
    }

    override fun onCleared() {
        PlaybackService.onProgressUpdate = null
        PlaybackService.onPlaybackEnded = null
        PlaybackService.onPlaybackPrevious = null
        PlaybackService.onIsPlayingChanged = null
        PlaybackService.onSongTransitioned = null
        PlaybackService.onBufferingChanged = null
        PlaybackService.onCoverAccent = null
        PlaybackService.onPlaybackError = null
        super.onCleared()
    }
}
