/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.3.0 · C/D：音源归属之外的**两个可确证标签** —— 版权可用性 与 原唱/翻唱。
 * **纯逻辑，无 Android 依赖，JVM 可单测。**
 */

package com.takahashirinta.ncrust.search

import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.musicSource

/**
 * 一首曲目的**版权可用性**（v2.3.0 · C）。
 *
 * ## 这个枚举只回答「服务端有没有**明确**告诉我」，不回答「我猜它能不能播」
 *
 * v2.3.0 的探针（`docs/verification/v2.3.0/probe-copyright.md`）把这件事量化了：
 * 网易云**没有**一个「可播放」的布尔字段，只有两个整数（`privilege.pl` / `privilege.st`），
 * 而它们的可信度**不一样**：
 *
 * | 分层 | 预检 30 首的实测 | 能否用作判据 |
 * |---|---|---|
 * | `pl > 0` | **30/30 可播**（假阳性 **0**） | ✅ [PLAYABLE] |
 * | `st == 0 && pl == 0`（且 `fee ∈ {1,4}`） | **30/30 不可播** | ✅ [MEMBER_ONLY] |
 * | `st == -1 && pl == 0` | **15 可播 / 15 不可播** | ❌ 一律 [UNKNOWN] |
 * | `st == -200` 或 `noCopyrightRcmd != null` | 该曲取不到链且服务端自陈无版权 | ✅ [NO_COPYRIGHT] |
 *
 * **[UNKNOWN] 是本枚举里最重要的一个取值**：它表示「不说」。
 * 任务书第 5.2 条要求「不允许在搜索阶段就假设某源可播」—— 这条要求的技术落点就是它：
 * 判不出来时必须留白，而不是默认成 [PLAYABLE]。
 *
 * ## 与 [TrackAccess] 的分工（两者**都要留着**，不是重复）
 *
 * | | [TrackAccess] | [TrackAvailability] |
 * |---|---|---|
 * | 用途 | **排序**（v2.1.4：会员买在哪家，哪家先出） | **标注**（给用户看的角标） |
 * | 输入 | `fee` / `pay_play` —— 只看「要不要会员」 | `privilege` 全套 —— 看「现在能不能播」 |
 * | 取值 | 3 个（含「会员专享」） | 4 个（多出「服务端说没版权」） |
 * | 判错方向 | 排序靠后（可容忍） | 给用户一个假承诺（**不可容忍**） |
 *
 * 所以本版**没有**把两者合并：`TrackAccess.ofNeteaseFee(fee)` 的语义（`fee=8` 免费播放、
 * 高音质要会员）与 `privilege.pl` 的语义（当前身份的实际可播码率）是两回事。
 * 前者的既有单测（`SearchRankingTest`）一个字节未动。
 */
enum class TrackAvailability {

    /**
     * 服务端确认**当前身份**能取到播放链。
     *
     * 判据：网易云 `privilege.pl > 0`。实测 30/30，**假阳性 0**。
     * 注意它带 Cookie 时反映的是**这个账号自己的权益**，所以文案是「可播放」而不是「有版权」。
     */
    PLAYABLE,

    /**
     * 当前身份**拿不到链**，且判据指向会员墙。
     *
     * 判据：`privilege.st == 0 && privilege.pl == 0`（实测 30/30 不可播，这 30 首 `fee` 全是 `1`），
     * 或 QQ 的 `pay.pay_play == 1`（v2.1.4 已实测）。
     */
    MEMBER_ONLY,

    /**
     * 服务端**显式声明**了这一条没有版权（或已下架）。
     *
     * 判据：`privilege.st == -200` 或 `noCopyrightRcmd != null`。
     * 实测在 591 条池子里只出现 2~3 次，但**零假阳性** —— 覆盖率低不代表不能用，
     * 只代表它会**少标**，而少标是安全的方向。
     */
    NO_COPYRIGHT,

    /**
     * **不知道**。服务端没给判据、或给的判据实测不可靠（`st == -1`）。
     *
     * UI 必须**什么都不显示**。把它显示成任何一个确定结论都是撒谎。
     */
    UNKNOWN,
    ;

    /** 是否要在列表里给这一行加角标。 */
    val hasBadge: Boolean get() = this != UNKNOWN

    companion object {

        /**
         * 网易云：由逐曲 `privilege` + `fee` 判定。**判据只此一处。**
         *
         * 判定顺序是有意的 —— `NO_COPYRIGHT` 优先于 `PLAYABLE`：
         * 实测有一条 `st == -200` 且 `noCopyrightRcmd != null` 的样本（`晴天 (钢琴版)`），
         * 它的 `pl == 0`，两条判据不冲突；但**万一**将来出现「声明无版权却给了 pl」的自相矛盾数据，
         * 应当相信服务端的显式声明（保守的一侧），而不是那个整数。
         *
         * @param privilegeSt  `privilege.st`；null = 字段缺失
         * @param playableBr   `privilege.pl`；null = 字段缺失
         * @param noCopyright  `noCopyrightRcmd != null`；null = 未声明
         * @param fee          网易云的 `fee`（`1`/`4` = 会员或数字专辑）
         */
        fun ofNetease(
            privilegeSt: Int?,
            playableBr: Int?,
            noCopyright: Boolean?,
            fee: Int?,
        ): TrackAvailability {
            // ① 显式无版权声明 —— 最高优先级，且零假阳性
            if (noCopyright == true || privilegeSt == NO_COPYRIGHT_ST) return NO_COPYRIGHT

            // ② 可播最高码率 > 0 ⇒ 一定取得到链（30/30，假阳性 0）
            val br = playableBr
            if (br != null && br > 0) return PLAYABLE

            // ③ st == 0 且 pl == 0 ⇒ 当前身份拿不到链。实测这一档 30/30 不可播、fee 全为 1，
            //    所以只在 fee 也指向会员/数字专辑时才敢说「需会员」。
            //    ★ 刻意**不**把 `st == -1` 收进来：那一档 15/30 是能播的。
            if (privilegeSt == NORMAL_ST && br != null && br <= 0 && fee in MEMBER_FEES) {
                return MEMBER_ONLY
            }

            return UNKNOWN
        }

        /**
         * QQ 音乐：只有 `pay.pay_play` 一个可用判据（v2.1.4）。
         *
         * **刻意不做的事**：`pay_play == 0` **不**返回 [PLAYABLE]。
         * 它的字面语义是「不需要付费」，不是「有版权、能取到链」——
         * QQ 侧没有等价于 `privilege.pl` 的字段（`probe-source-attribution.md` §3：
         * `action.switch` 的 bit0 在 130/130 条上恒为 1，**零区分度**；`action.alert` 语义无权威定义）。
         * 把「不用付费」标成「可播放」正是任务书禁止的「在搜索阶段假设某源可播」。
         */
        fun ofQq(memberOnly: Boolean?): TrackAvailability =
            if (memberOnly == true) MEMBER_ONLY else UNKNOWN

        /** 按曲目所属音源分派。**唯一入口**，调用方不要自己 when。 */
        fun of(song: SongItem): TrackAvailability = when (song.musicSource) {
            // v3.1.0：B 站的音频区响应里**没有**任何版权/可播字段（`song/info` 只给
            // 标题/UP主/封面/时长/歌词）。按本类型的既有纪律，「不知道」必须是
            // [UNKNOWN] 而不是 [FREE] —— 猜成 FREE 会让一首放不出来的曲子排到前面，
            // 用户点下去只会跳歌。B 站真正的可播性判据在取链那一步（拿不到 URL 就跳）。
            MusicSource.BILIBILI -> UNKNOWN
            MusicSource.QQMUSIC -> ofQq(song.memberOnly)
            MusicSource.NETEASE -> ofNetease(
                privilegeSt = song.privilege?.st,
                playableBr = song.privilege?.pl,
                noCopyright = song.noCopyright?.let { true },
                fee = song.fee,
            )
        }

        /** 网易云 `privilege.st` 的「正常」取值。 */
        const val NORMAL_ST = 0

        /** 网易云 `privilege.st` 的「下架 / 无版权」取值。 */
        const val NO_COPYRIGHT_ST = -200

        /** 「播放本身被会员墙挡住」的 `fee` 取值（与 [TrackAccess] 一致，但用途不同）。 */
        private val MEMBER_FEES = setOf(1, 4)

        /** 排序用的分组序：可播最前，「不知道」与「受限」同组靠后。 */
        fun rankGroup(availability: TrackAvailability): Int = when (availability) {
            PLAYABLE -> 0
            // 「不知道」与「受限」同组：不知道就靠后，绝不往前推。
            // 这一条与 TrackAccess.UNKNOWN 的既有约定（v2.1.4）是同一条原则。
            MEMBER_ONLY, NO_COPYRIGHT, UNKNOWN -> 1
        }
    }
}

/**
 * 一首曲目的**版本性质**：原唱 / 翻唱（v2.3.0 · D）。
 *
 * ## 为什么敢做，而「官方音源」不敢做
 *
 * 网易云在单曲级下发 `originCoverType`（整数枚举）与 `originSongSimpleData`（指向原曲的**结构化引用**，
 * 带 `songId` 可精确回查）。两者都不是字符串启发式，所以可用：
 *
 * - 自洽性检验：对 60 条「翻唱」，回头查它声称的原曲，**58 条（96.7%）** 的 `originCoverType == 1`，
 *   剩下 2 条是 `0`（无信息）—— **没有一条指向另一个「翻唱」**；
 * - 反向对照：`originCoverType == 1` 的 429 条里，**0 条**带 `originSongSimpleData`。
 *
 * ## 为什么 `0` / `3` 必须留白
 *
 * `0` = 服务端无信息（实测多为 UP 主自制 beat / 采样）；`3` = 语义未确证（22 条样本混有
 * DJ 版与普通条目）。这两档若硬套「非原唱即翻唱」会把一大批歌标错，
 * 所以一律 [UNKNOWN]。
 *
 * ## 为什么 QQ 恒为 [UNKNOWN]
 *
 * QQ 搜索结果的 `label` / `type` / `ov` / `singer[].type` 在 130 条样本上**全部恒为同一个值**，
 * `songtype` 字段根本不存在。实测四条同名《晴天》（官方 + 三条翻唱）的这些字段**逐字段相同**
 * —— 没有任何可用判据（`probe-official-tag.md` §4）。所以 QQ 侧不打标签。
 */
enum class TrackVersionTag {
    /** 服务端声明这条就是原曲本身（`originCoverType == 1`）。 */
    ORIGINAL,

    /** 服务端声明这条是翻唱（`originCoverType == 2`）。 */
    COVER,

    /** 服务端没给判据、或判据语义未确证（`0` / `3` / 字段缺失）。UI **不显示任何标签**。 */
    UNKNOWN,
    ;

    val hasBadge: Boolean get() = this != UNKNOWN

    companion object {
        /** 网易云 `originCoverType` → 标签。**判据只此一处。** */
        fun ofOriginCoverType(value: Int?): TrackVersionTag = when (value) {
            ORIGINAL_CT -> ORIGINAL
            COVER_CT -> COVER
            else -> UNKNOWN
        }

        /** 按曲目所属音源分派。QQ 恒 [UNKNOWN]（没有字段）。 */
        fun of(song: SongItem): TrackVersionTag = when (song.musicSource) {
            MusicSource.QQMUSIC -> UNKNOWN
            MusicSource.NETEASE -> ofOriginCoverType(song.originCoverType)
            // B 站音频区没有「原唱/翻唱」这种结构化标注（翻唱信息写在标题里）。
            MusicSource.BILIBILI -> UNKNOWN
        }

        /** 网易云 `originCoverType`：原唱。 */
        const val ORIGINAL_CT = 1

        /** 网易云 `originCoverType`：翻唱。 */
        const val COVER_CT = 2
    }
}
