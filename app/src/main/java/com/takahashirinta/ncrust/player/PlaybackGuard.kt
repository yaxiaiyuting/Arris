/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.2.1 · P0：**失败处理必须有界**。
 *
 * 这个文件是 2026-09-25 那次 P0 级联故障（QQ 音源切母带 → 循环切音质 → 自动跳歌 →
 * 反复抢音频焦点）的止血与根因修复的判定核心。三件事都抽成纯逻辑，所以 JVM 单测能覆盖：
 *
 *  1. [QualityRetryGuard] —— 降档重试**必须单调、去重、有上限**。
 *  2. [AutoSkipGuard]    —— 自动跳歌**必须连续计数并熔断**。
 *  3. [classifyFailure]  —— 播放失败要能分类，且**音质失败不能当播放失败处理**。
 *
 * 为什么必须重写（旧实现的三个洞，全部在真机日志里现过形）：
 *
 *  | 洞 | 旧行为 | 后果 |
 *  |---|---|---|
 *  | 无上限 | `handlePlaybackError` 每错一次就降一档，没有次数上限 | 一首歌可以被重取 8 次，每次都 `setMediaItem`+`play()` ⇒ 每次都抢一次音频焦点 |
 *  | 不单调 | 下一档由 `lastPlayedLevel`（**实际**拿到档位）算，而实际档位可能不随请求变化 | 固定点：`requested=exhigh → actual=lossless → next=exhigh → actual=lossless …` 永不收敛 |
 *  | 无熔断 | 最低档仍失败 ⇒ `onUnplayable` ⇒ `playNext()`，没有连续计数 | 整条队列一起失败时无限跳歌（用户报的「不关应用就一直切」） |
 *
 * 计数清零条件（写死在这里，调用方不许自己发明）：
 *  - **用户手动操作**（点播放/切歌/手动改音质）→ [QualityRetryGuard.resetAll] + [AutoSkipGuard.onUserAction]；
 *  - **应用重启** → 两个 guard 都是进程内状态，天然清零；
 *  - **确实播出声了**（进度前进 ≥ [AutoSkipGuard.PROGRESS_CONFIRM_MS]）→ 自动跳歌计数清零。
 */

package com.takahashirinta.ncrust.player

/** 一次开播请求的来源。**音质切换不是播放失败**，靠它把两条路径分开。 */
enum class PlayOrigin {
    /** 用户在列表/详情/队列里点了一首歌。 */
    USER,

    /** 播完自动接续（含无缝接管）。 */
    AUTO_NEXT,

    /** 用户手动切音质（含设置页改档位）。 */
    QUALITY_SWITCH,

    /** 播放失败后的自动降档重试。 */
    QUALITY_RETRY,

    /** 预载接管。 */
    PRELOAD,
}

/** 自动重试熔断后的动作。 */
enum class GuardAction {
    /** 允许重取，档位见 [RetryDecision.nextLevel]（可能要先等 [RetryDecision.delayMs]）。 */
    RETRY,

    /** 停在这里：暂停 + 错误态，**等用户手动操作**。绝不跳歌。 */
    STOP_AND_WAIT,

    /** 允许跳下一首（只可能来自「这首歌根本取不到链」，且自动跳歌没熔断）。 */
    SKIP_ALLOWED,
}

/**
 * @property action 该做什么。
 * @property nextLevel 仅 [GuardAction.RETRY] 有意义：下一次要请求的档位。
 * @property delayMs 仅 [GuardAction.RETRY] 有意义：**音频焦点节流**——距离上一次失败不足
 *   [QualityRetryGuard.MIN_RESTART_INTERVAL_MS] 时，要先等这么久再重取，否则每次重试都会
 *   重新 `play()` 一次，也就是重新 `requestAudioFocus` 一次（用户看到的「看视频被反复打断」）。
 * @property reason 给日志/UI 的一句话原因。
 */
data class RetryDecision(
    val action: GuardAction,
    val nextLevel: String? = null,
    val delayMs: Long = 0L,
    val reason: String = "",
)

/**
 * 播放失败分类。**分类不是为了好看，是为了决定「重试还有没有意义」**。
 *
 * 实测（2026-09-25 · PCL110 · QQ 音源《One Last Kiss》）：请求杜比档时 QQ 回的是
 * `Q001`＝**6 声道 FLAC**，而本应用的音频处理链里那个可视化 tee 把输出声明成 1 声道，
 * media3 的 `ChannelMixingMatrix` 只实现了「同声道数 / 1→2 / 2→1」三种矩阵，
 * 6→1 直接抛 `UnsupportedOperationException`。抛完 AudioSink 进入不可恢复状态
 * （logcat 里 `Disable failed` / `Reset failed`），**同一 player 实例之后任何档位都播不出**，
 * 于是降档阶梯一路失败、最后跳歌 —— 这就是那条级联。
 */
enum class FailureKind {
    /** 网络 / HTTP / 链接过期。降一档重取是有意义的。 */
    SOURCE,

    /** 解码器不认这个文件（容器/位深/采样率）。降一档重取有意义。 */
    DECODER,

    /** 音频输出链故障（AudioSink / 声道矩阵 / AudioTrack）。**必须先让播放器回到干净状态**。 */
    SINK,

    /** 其它。按 SOURCE 处理，但同样受次数上限约束。 */
    UNKNOWN,
}

/** [PlaybackService] 交给 ViewModel 的失败描述（只带诊断用的字符串，不带 URL/密钥）。 */
data class PlaybackFailure(
    val errorCode: Int,
    val errorCodeName: String,
    val causeClass: String?,
    val causeMessage: String?,
) {
    /** media3 的 `PlaybackException.ERROR_CODE_*` 是 int，这里只做「是不是 IO 类」的粗判。 */
    val looksLikeSource: Boolean
        get() = errorCodeName.contains("IO_", ignoreCase = true) ||
            errorCodeName.contains("SOURCE", ignoreCase = true) ||
            errorCodeName.contains("NETWORK", ignoreCase = true)
}

/**
 * 把一次播放失败分类。
 *
 * ⚠️ 判据是 `causeClass`/`causeMessage` 而**不是** errorCode：实测那次是
 * `ERROR_CODE_UNSPECIFIED`（看起来最没用的一档），真正的原因只出现在 cause 里
 * （`java.lang.UnsupportedOperationException: Default channel mixing coefficients for 6->1 …`）。
 * 只看 errorCode 会把它当 UNKNOWN，于是继续在坏掉的 sink 上重试 8 次。
 */
fun classifyFailure(failure: PlaybackFailure): FailureKind {
    val cause = (failure.causeClass ?: "") + " " + (failure.causeMessage ?: "")
    val lower = cause.lowercase()
    return when {
        lower.contains("channel mixing") ||
            lower.contains("unsupportedoperationexception") ||
            lower.contains("audiotrack") ||
            lower.contains("audiosink") -> FailureKind.SINK

        failure.errorCodeName.contains("DECOD", ignoreCase = true) ||
            lower.contains("decoder") ||
            lower.contains("mediacodec") -> FailureKind.DECODER

        failure.looksLikeSource -> FailureKind.SOURCE

        else -> FailureKind.UNKNOWN
    }
}

/**
 * 取链彻底失败（所有档位都没有可播放 URL）时，这个来源**能不能**跳到下一首。
 *
 * 「音质切换失败 ≠ 播放失败」这条铁律的判据本身。音质路径永远不能跳 ——
 * 用户切档失败只说明「这首歌的这一档放不出来」，把它变成「这首歌被跳过」，
 * 再叠上整条队列都取不到链的情况，就是用户报的「不关应用就一直切」。
 *
 * 抽成纯函数是为了能被单测钉住：真正的分支在 `PlayerViewModel.onUrlUnavailable`，
 * 而那个类需要真 Application（项目里没有 Robolectric）。
 */
fun maySkipOnUrlFailure(origin: PlayOrigin): Boolean =
    origin != PlayOrigin.QUALITY_SWITCH && origin != PlayOrigin.QUALITY_RETRY

/**
 * 取链彻底失败时，是否应该先回退到「本会话里最后一个确实取到过链的档位」再考虑停下。
 * 与 [maySkipOnUrlFailure] 互补：能跳歌的来源不需要回退（它直接换歌了）。
 */
fun prefersLastGoodLevel(origin: PlayOrigin): Boolean = !maySkipOnUrlFailure(origin)

/**
 * 降档重试守卫：**单调 + 去重 + 有上限 + 节流**。
 *
 * 单调的含义：下一次请求的档位必须**严格低于**「本次请求档位」与「本次实际拿到档位」的较小者。
 * 用较小者（而不是只用一个）是这次修复的关键：
 *  - 只看「请求档位」会忽略「请求母带、实际只给了 320k」这种情况（降得不够，白白重试）；
 *  - 只看「实际档位」会踩固定点 —— 实测离线缓存回放那条路会把 `actualLevel` 报成缓存里的档位
 *    （请求 exhigh、实际 lossless），下一次又从 exhigh 起 ⇒ 永远在同一个坑里打转。
 *
 * 上限 [MAX_ATTEMPTS_PER_SONG] = 3：与任务书一致，也远小于阶梯长度（8），
 * 所以「走完 8 档才跳歌」这种把音频焦点抢 8 次的行为不可能再发生。
 */
class QualityRetryGuard(
    private val maxAttemptsPerSong: Int = MAX_ATTEMPTS_PER_SONG,
) {
    companion object {
        /** 同一首歌最多自动降档重试几次。 */
        const val MAX_ATTEMPTS_PER_SONG = 3

        /** 两次自动重取之间的最小间隔 —— 音频焦点节流窗口（任务书要求 10s）。 */
        const val MIN_RESTART_INTERVAL_MS = 10_000L
    }

    private var songKey: String = ""
    private val attempted = LinkedHashSet<String>()
    private var attempts = 0
    private var lastFailureAtMs = 0L

    /** 本首歌被判定为「这条链已经证明播不出来」的档位（例如 6 声道那条沉浸声链）。 */
    private val unusableLevels = LinkedHashSet<String>()

    /** 换歌：清掉「本首歌」的计数，但保留节流时间戳（节流是全局的，跨歌也该生效）。 */
    fun onNewSong(key: String) {
        if (key == songKey) return
        songKey = key
        attempted.clear()
        attempts = 0
        unusableLevels.clear()
    }

    /** 用户手动操作 / 应用重启：全部清零。 */
    fun resetAll() {
        songKey = ""
        attempted.clear()
        attempts = 0
        lastFailureAtMs = 0L
        unusableLevels.clear()
    }

    /** 把某档位标记为「本首歌不要再用」。 */
    fun markUnusable(level: String) {
        unusableLevels.add(level)
    }

    fun attemptsForCurrentSong(): Int = attempts

    fun attemptedLevels(): Set<String> = attempted.toSet()

    /**
     * 决定一次失败之后该怎么办。
     *
     * @param requested 本次**请求**的档位（用户偏好或上一次的降档结果）。
     * @param actual 本次**实际**拿到的档位（`SongUrlResult.actualLevel`；拿不到传空串）。
     * @param ladder 降档阶梯（高 → 低），与 `qualityRetryLadder` 同一份。
     * @param nowMs 当前时间（注入以便单测；生产传 [System.currentTimeMillis]）。
     */
    fun decide(
        requested: String,
        actual: String,
        ladder: List<String>,
        nowMs: Long,
    ): RetryDecision {
        attempted.add(requested)
        if (attempts >= maxAttemptsPerSong) {
            return RetryDecision(
                GuardAction.STOP_AND_WAIT,
                reason = "已达单曲自动重试上限 $maxAttemptsPerSong 次",
            )
        }

        // ⚠️ 方向：本阶梯是**高 → 低**（index 0 = jymaster，index 越大音质越低），
        // 所以「降一档」= index **+1**。这与 QualityLadder.LEVELS（低 → 高）相反，
        // 是本文件最容易写反的地方 —— 单测 `retry ladder is strictly monotonic` 钉着它。
        val requestedIdx = ladder.indexOf(requested)
        val actualIdx = ladder.indexOf(actual)
        // 取两者中**较差**的那个（index 更大）再往下走一档：
        //  · 用 min（较好的那个）会降得不够 —— 「请求母带、实际只给 320k」时会白重试高档位；
        //  · 用「实际档位」单独做基准会踩固定点 —— 离线兜底把 actual 报成缓存里的档位
        //    （请求 exhigh、实际 lossless），下一档又回到 exhigh，永远转圈。
        // 取 max 同时躲开这两个坑，并且保证严格单调向下。
        val floorIdx = when {
            requestedIdx >= 0 && actualIdx >= 0 -> maxOf(requestedIdx, actualIdx)
            requestedIdx >= 0 -> requestedIdx
            actualIdx >= 0 -> actualIdx
            // 两个都认不出来（服务端给了阶梯外的档位）：从最高档开始，靠 attempted 去重兜底。
            else -> -1
        }

        val next = (floorIdx + 1 until ladder.size)
            .map { ladder[it] }
            .firstOrNull { it !in attempted && it !in unusableLevels }

        if (next == null) {
            return RetryDecision(
                GuardAction.STOP_AND_WAIT,
                reason = "已无更低且未试过的档位可用（floor=${ladder.getOrNull(floorIdx) ?: requested}）",
            )
        }

        attempts++
        val elapsed = nowMs - lastFailureAtMs
        val delay = if (lastFailureAtMs == 0L) 0L else (MIN_RESTART_INTERVAL_MS - elapsed).coerceAtLeast(0L)
        return RetryDecision(GuardAction.RETRY, nextLevel = next, delayMs = delay, reason = "降档重试")
    }

    /** 调用方在真正开始一次自动重试时登记时间戳（节流窗口的起点）。 */
    fun onRetryStarted(nowMs: Long) {
        lastFailureAtMs = nowMs
    }

    fun onFailure(nowMs: Long) {
        lastFailureAtMs = nowMs
    }
}

/**
 * 自动跳歌守卫：**连续**自动跳歌超过 [maxConsecutive] 次就熔断，进入「暂停 + 错误态」。
 *
 * 「连续」的定义是本文件档头那三条清零条件之一成立之前一直累加 ——
 * 特别是**必须真的播出声**才算打断连续（[onProgressConfirmed]），
 * 否则「起播即失败」的那些歌会把计数冲掉，熔断永远不生效。
 */
class AutoSkipGuard(
    private val maxConsecutive: Int = MAX_CONSECUTIVE_AUTO_SKIPS,
) {
    companion object {
        /** 连续自动跳歌上限。 */
        const val MAX_CONSECUTIVE_AUTO_SKIPS = 5

        /** 进度前进到这个值才认为「确实播出来了」，此时清零连续计数。 */
        const val PROGRESS_CONFIRM_MS = 3_000L
    }

    private var consecutive = 0
    private var trippedFlag = false

    val consecutiveSkips: Int get() = consecutive

    val tripped: Boolean get() = trippedFlag

    /** 请求一次自动跳歌。返回 false = 熔断，调用方必须停下来等用户。 */
    fun requestAutoSkip(): Boolean {
        if (trippedFlag) return false
        if (consecutive + 1 > maxConsecutive) {
            trippedFlag = true
            return false
        }
        consecutive++
        return true
    }

    /** 确实播出声了 → 连续计数清零。 */
    fun onProgressConfirmed() {
        consecutive = 0
        trippedFlag = false
    }

    /** 用户手动操作 → 清零（用户接手之后重新给满额度）。 */
    fun onUserAction() {
        consecutive = 0
        trippedFlag = false
    }

    /** 换歌但**不算**一次跳歌（例如用户点的歌）——不发生，留给显式调用方。 */
    fun peekWouldTrip(): Boolean = trippedFlag || consecutive + 1 > maxConsecutive
}

/**
 * v2.2.1 · P0：**降级状态记忆** —— 降级之后，自动路径不得再自己升回原档位。
 *
 * 为什么需要它：真实账号的权益/版权是**按曲**的（实测同一账号：《稻香》拿得到母带，
 * 《One Last Kiss》只有 Hi-Res），而每次自动接续、每次预载都会拿**全局偏好**去请求。
 * 于是一首已经证明「拿不到母带」的歌，会在每次重播时再撞一次墙 —— 那既是白花的往返，
 * 也是重试环路的燃料。
 *
 * 三条语义（缺一条就会变成「功能被永久降级」这种更糟的 bug）：
 *  1. **只约束自动路径**：用户手动切档位一律照请求走（他是权威），并**清掉**该曲的上限；
 *  2. **有 TTL**（默认 24h）：权益会变（续费、版权回归），过期自动重新试探；
 *  3. **缺失 = 无上限**：老数据没有这条记录，语义是「不知道」，不是「最高只到 standard」。
 */
class QualityCeilingMemory(
    private val ttlMs: Long = DEFAULT_TTL_MS,
) {
    companion object {
        const val DEFAULT_TTL_MS = 24L * 60 * 60 * 1000
        /** 上限条数，防无界增长。 */
        const val MAX_ENTRIES = 200
    }

    private val entries = LinkedHashMap<String, Pair<String, Long>>()

    /** 记下「这首歌实际最高只能到 [grantedLevel]」。只在确实低于请求档位时调用。 */
    fun remember(key: String, grantedLevel: String, nowMs: Long) {
        if (key.isEmpty() || grantedLevel.isEmpty()) return
        entries.remove(key)
        entries[key] = grantedLevel to nowMs
        while (entries.size > MAX_ENTRIES) {
            val oldest = entries.keys.firstOrNull() ?: break
            entries.remove(oldest)
        }
    }

    /** 用户手动切档位 ⇒ 该曲的上限作废，下一次自动路径重新试探。 */
    fun clear(key: String) {
        entries.remove(key)
    }

    fun snapshot(nowMs: Long): Map<String, String> =
        entries.entries
            .filter { nowMs - it.value.second <= ttlMs }
            .associate { it.key to it.value.first }

    fun restore(saved: Map<String, String>, nowMs: Long) {
        entries.clear()
        saved.forEach { (k, v) -> entries[k] = v to nowMs }
    }

    /**
     * 自动路径实际该请求的档位：不超过该曲记住的上限。
     *
     * @param preferred 用户偏好档位（API level 名）。
     * @param ladderLowToHigh `QualityLadder.LEVELS`（低 → 高）。
     * @return 若偏好高于记住的上限，返回上限；否则原样返回偏好。
     */
    fun effectiveRequest(preferred: String, key: String, ladderLowToHigh: List<String>, nowMs: Long): String {
        val remembered = entries[key] ?: return preferred
        if (nowMs - remembered.second > ttlMs) {
            entries.remove(key)
            return preferred
        }
        val capIdx = ladderLowToHigh.indexOf(remembered.first)
        val prefIdx = ladderLowToHigh.indexOf(preferred)
        if (capIdx < 0 || prefIdx < 0) return preferred
        return if (prefIdx > capIdx) remembered.first else preferred
    }
}

/**
 * v2.3.0 · C：「此源无版权，可切另一源」提示的**节流闸**。
 *
 * ## 为什么提示也要有界（铁律 8）
 *
 * 这条提示挂在「取链彻底失败 ⇒ 即将自动跳歌」这条路径上，而那条路径**本来就会连续触发**：
 * 一段整张专辑都下架的歌单能一路跳下去。`AutoSkipGuard` 把跳歌限到 5 次，
 * 但 5 条提示在 2 秒内连续弹出仍然是一屏噪音，用户只会觉得应用坏了。
 *
 * 所以提示单独设一道闸：**同一个时间窗内最多一条**。
 * 与 `AutoSkipGuard` / `QualityRetryGuard` 一样，判定抽成纯逻辑、由 JVM 单测钉住 ——
 * 「有界」这件事必须能被测出来，不能靠读代码相信它。
 *
 * ## 为什么不用「只提示一次」
 *
 * 用户可能隔十分钟又碰到一首放不了的歌 —— 那时提示是有用的。
 * 所以闸门是**节流**（时间窗）而不是**一次性**（latch）。
 */
class SourceFallbackHintGate(
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
) {

    private var lastShownAtMs: Long = Long.MIN_VALUE

    /**
     * @param nowMs 当前时刻（`System.currentTimeMillis()`）。
     * @return true = 这一次可以弹提示（并且内部已记下时刻）。
     */
    fun shouldHint(nowMs: Long): Boolean {
        // 两个判据都必须显式写出来，不能图省事写成 `nowMs - lastShownAtMs < minIntervalMs`：
        //
        // · 「从未提示过」用**哨兵比较**而不是算术（`Long.MIN_VALUE` 参与减法会溢出，
        //   方向正好相反 —— 第一次会被误判成「刚提示过」，提示永远不出现）；
        // · 时钟被往回拨（用户改系统时间 / NTP 校正）时 `nowMs - lastShownAtMs` 是负数，
        //   同样会被误判成「刚提示过」。那一侧选择**放行**：宁可再提示一次，
        //   也不要因为时钟问题让用户永远看不到「可以换个音源」这条出口。
        if (lastShownAtMs != Long.MIN_VALUE && nowMs >= lastShownAtMs &&
            nowMs - lastShownAtMs < minIntervalMs
        ) {
            return false
        }
        lastShownAtMs = nowMs
        return true
    }

    /** 用户回到前台 / 手动操作时清闸，让下一次失败立刻能提示。 */
    fun reset() {
        lastShownAtMs = Long.MIN_VALUE
    }

    companion object {
        /** 同一个提示在 20 秒内不重复。 */
        const val DEFAULT_MIN_INTERVAL_MS = 20_000L
    }
}
