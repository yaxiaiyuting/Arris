/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.1.0 · B：qm 音质档位映射。**纯逻辑，JVM 可单测。**
 */

package com.takahashirinta.ncrust.qq

/**
 * qm 的「文件档位」（v2.1.0 · B）。
 *
 * qm 取链不传「音质等级」，而是传一个**拼好的文件名**：
 * `<档位前缀><media_mid>.<扩展名>`，例如 `M800003Qui1q2u1Zho.mp3`。
 * 服务端按这个文件名去找文件，找不到就返回空 [purl]（不是报错）——
 * 所以档位前缀写错的表现是「静默拿不到 URL」，而不是一个显式的失败。
 *
 * @property prefix 文件名前缀。
 * @property ext 扩展名（**不带点**）。
 * @property label 人类可读的档位名（只用于日志与诊断，不进 UI —— UI 文案走 i18n）。
 */
enum class QqFileType(
    val prefix: String,
    val ext: String,
    val label: String,
    /**
     * 音质档次（越大越高）。**只用于「兜底段该不该接在这一档后面」的判断**，
     * 不参与 UI 排序 —— UI 的档位顺序由 `QualityLadder.LEVELS` 单一来源决定。
     * `C400` 与 `M800` 同档：AAC 320k 与 mp3 320k 听感同级，谁先谁后只是容器偏好。
     */
    val rank: Int,
) {
    /** 128k mp3，人人都能放。 */
    M500("M500", "mp3", "128k mp3", 0),

    /** 320k mp3。 */
    M800("M800", "mp3", "320k mp3", 1),

    /** AAC/m4a。 */
    C400("C400", "m4a", "AAC m4a", 1),

    /** 无损 FLAC。 */
    F000("F000", "flac", "FLAC 无损", 2),

    /** Hi-Res FLAC。 */
    RS01("RS01", "flac", "Hi-Res FLAC", 3),

    /** 臻品母带（会员）。前缀与扩展名来自社区实现，未在登录态实测。 */
    AI00("AI00", "flac", "臻品母带", 4),

    /** 臻品全景声（会员）。同上。 */
    Q000("Q000", "flac", "臻品全景声", 5),

    /** 臻品音质（会员）。同上。 */
    Q001("Q001", "flac", "臻品音质", 6),
    ;

    /** 是否为需要 FLAC 解码器的档位（复用 `SongUrlFetcher` 的设备门控判定）。 */
    val isFlac: Boolean get() = ext == "flac"
}

/**
 * Arris 统一档位名 ↔ QQ 文件档位的映射与降级链（v2.1.0 · B）。
 *
 * ## 两条设计约束
 *
 * 1. **档位名是本应用统一的**（`QualityLadder.LEVELS`：`standard`…`dolby`），
 *    两个平台各自映射到自己能理解的东西。QQ 侧**不新增档位**，否则设置页要分平台两套。
 * 2. **每一档都要给一条降级链，而不是一个值**。原因见 [QqFileType] 的注释：
 *    前缀不被该曲支持时服务端只是返回空 purl。实测（匿名态）请求任意档位都返回
 *    `purl: ""` + `result: 104003`，因此**降级链是唯一能在「前缀猜错/该曲没有该档」
 *    两种情况下都拿到声音的机制**。
 *
 * ## 未验证项的边界（如实标注）
 *
 * `M500`/`M800`/`C400`/`F000`/`RS01` 是社区实现里长期一致的前缀，可信度高；
 * `AI00`/`Q000`/`Q001` 只见于部分实现，**没有在登录态实测过**（本仓库没有 qm 账号），
 * 它们排在无损之上、且后面永远跟着 `F000`⇒`M800`⇒`M500` ——
 * 猜错的最坏结果是「退到无损」，不是「放不出来」。
 */
object QqQuality {

    /**
     * 某个统一档位名对应的**首选尝试顺序**（自左向右）。
     *
     * 只列「这一档真正想要的东西」，兜底段由 [attemptsFor] 统一追加 ——
     * 把兜底写进每个分支里，早晚会有一支漏掉，表现就是「某一档莫名放不出来」。
     */
    fun ladderFor(level: String): List<QqFileType> = when (level) {
        "dolby" -> listOf(QqFileType.Q001, QqFileType.Q000, QqFileType.AI00)
        "jymaster" -> listOf(QqFileType.AI00, QqFileType.RS01)
        "jyeffect" -> listOf(QqFileType.Q000, QqFileType.RS01)
        "hires" -> listOf(QqFileType.RS01)
        "lossless" -> listOf(QqFileType.F000)
        // 极高 = AAC；AAC 拿不到时 320k mp3 听感并不更差，退它比退 128k 合理。
        "exhigh" -> listOf(QqFileType.C400, QqFileType.M800)
        "higher" -> listOf(QqFileType.M800)
        "standard" -> listOf(QqFileType.M500)
        // 未知档位（服务端可能返回阶梯之外的值）：从无损往下试，与 ncm 侧同样保守。
        else -> listOf(QqFileType.F000)
    }

    /** 兜底段（无损 → 320k → 128k）。任何一档试到底都还能出声。 */
    val FALLBACK_TAIL: List<QqFileType> = listOf(
        QqFileType.F000, QqFileType.M800, QqFileType.M500,
    )

    /**
     * 实际要尝试的完整序列：把 [ladderFor] 的结果接上 [FALLBACK_TAIL] 并去重保序。
     *
     * 去重是必要的：`jymaster` 的链里已经有 `F000`，拼接后会出现两次 ——
     * 重复尝试同一档只是白白多一次往返，而且在「该曲没有无损」时会多等一轮。
     */
    fun attemptsFor(level: String): List<QqFileType> {
        val ladder = ladderFor(level)
        // 只接**严格低于**本档首选的那些档位。
        // 早先的写法是把整条兜底段无条件接上去，结果 `standard` 的链变成
        // [M500, F000, M800]：用户特意选了 128k 省流量，链尾却摆着无损和高码率，
        // 而且「每一条链都收敛到 M500」这条不变量也被破坏（链尾成了 M800）。
        val floor = ladder.minOf { it.rank }
        val tail = FALLBACK_TAIL.filter { it.rank < floor }
        val out = LinkedHashSet<QqFileType>()
        out.addAll(ladder)
        out.addAll(tail)
        return out.toList()
    }

    /**
     * 拼出文件名：`<前缀><mid>.<扩展名>`。
     *
     * **必须传 `file.media_mid`**（搜索响应里就有），不能传 `song.mid`：
     * 实测同一首歌这两个字段经常不同（《晴天》`mid=0039MnYb0qxYhV` 而
     * `media_mid=003Qui1q2u1Zho`），用错 mid 会静默拿不到 URL。
     * 见 [QqSongMapper.mediaMidOf]：没有 `media_mid` 时才回落 `mid`。
     */
    fun fileNameFor(fileType: QqFileType, mediaMid: String): String =
        fileType.prefix + mediaMid + "." + fileType.ext

    /**
     * 反向映射：实际拿到的 QQ 文件档位 → 本应用的统一档位名。
     *
     * 必要性：播放器要显示「这首歌实际在什么档位」。QQ 侧给我们的是**文件名**，
     * 不反推回去的话，UI 只能显示请求档位 —— 而降级恰恰是常态
     * （会员档拿不到时会退到无损/320k），显示请求档位等于骗用户。
     */
    fun ncrustLevelOf(fileType: QqFileType): String = when (fileType) {
        QqFileType.M500 -> "standard"
        QqFileType.M800 -> "higher"
        QqFileType.C400 -> "exhigh"
        QqFileType.F000 -> "lossless"
        QqFileType.RS01 -> "hires"
        QqFileType.AI00 -> "jymaster"
        QqFileType.Q000 -> "jyeffect"
        QqFileType.Q001 -> "dolby"
    }

    /** 从文件名前缀反查档位（诊断与降级判定用）。找不到返回 null，不猜。 */
    fun fileTypeOfFileName(fileName: String): QqFileType? =
        QqFileType.values().firstOrNull { fileName.startsWith(it.prefix) }

    /**
     * 该档位的**确定码率**（bps）；FLAC 档位按前缀推不出码率，返回 0 = 未知。
     *
     * 为什么需要它：QQ 的 vkey 响应没有 br 字段，而「界面显示的档位」是按实际文件参数算的
     * （见 [com.takahashirinta.ncrust.player.QualityAssessment]）。不知道实际码率时，
     * 界面只能信服务端标签 —— 而「请求超清母带、被降级到 320k」时标签正是**请求档位**，
     * 于是界面会把 320k 的 mp3 写成「超清母带」，用户看到的名字与听到的东西不符。
     *
     * 这几个数字不是猜的：128k/320k mp3 与 96k AAC 就是档位定义本身
     * （`M500`/`M800`/`C400` 的名字即来自码率），与 PHASE0 报告 §7.1 的档位表一致。
     * FLAC 档位（`F000`/`RS01`/`AI00`/`Q000`/`Q001`）**不填**：
     * 无损及以上同一档位在不同曲目上码率差异很大（实测无损 0.87–0.92 Mbps、
     * 母带 4.7–5.8 Mbps），编一个数字会直接污染 `measuredLevel` 的判定。
     */
    fun knownBitrateOf(fileType: QqFileType): Long = when (fileType) {
        QqFileType.M500 -> 128_000L
        QqFileType.M800 -> 320_000L
        QqFileType.C400 -> 96_000L
        else -> 0L
    }
}
