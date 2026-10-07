/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 修改说明：
 *   - v2.5.0 · D：「添加到下一首播放」的队列边界判定（纯逻辑，JVM 可单测）。
 */

package com.takahashirinta.ncrust.player

import com.takahashirinta.ncrust.QueueModes
import com.takahashirinta.ncrust.source.TrackKey

/**
 * v2.5.0 · D：「添加到下一首播放」的**队列边界判定**（纯逻辑，不依赖 Android）。
 *
 * ## 为什么抽成纯函数
 *
 * 铁律 16 要求「必须正确处理队列边界（当前歌是最后一首、队列为空、随机模式）」。
 * 「正确处理了」这件事必须**能被测出来**，不能靠读代码相信它 ——
 * 这与 v2.2.1 把熔断判据抽成 `PlaybackGuard` 的理由逐字相同。
 *
 * 本对象的输入是**队列的 id 序列**（不是 `SongItem`），输出是**新的 id 序列 + 新下标**；
 * `MainActivity` 只负责按 id 把 `SongItem` 重新装配起来。
 * 这样所有下标搬家逻辑都在这里、都能被 JVM 单测覆盖。
 *
 * ## 与既有 `insertNext` 的关系（探针结论）
 *
 * `probe-queue.md` 实测：`MainActivity.insertNext(song)` **在 v2.5.0 之前就已经存在**，
 * 并且已经做对了最容易被写错的两件事 ——
 *  1. 先记录当前歌 id、去重后**重新定位** `currentQueueIndex`
 *     （而不是直接 `.filter` 后沿用旧下标 —— 那正是 `AGENTS.md` 点名的关键不变量）；
 *  2. 不改变正在播的那首歌。
 *
 * 本版**不重写**它，而是：
 *  - 把它内部的判定搬到本对象（唯一落点），补上它没处理的三个边界；
 *  - 修掉一个真实缺陷：它**没有重新同步待播槽位**（见 [shouldPreloadAfterInsert]）。
 *
 * ## 去重还是允许重复？（任务书 §6.2 明确要求「需确认」）
 *
 * **决定：去重，并且是幂等的。**
 *
 * 三条理由，按重要性排序：
 *  1. **重复项会直接踩 v1.5.2 的串台形状。** 那一版用户报告的「UI/歌词/媒体卡片显示下一首、
 *     耳朵还是上一首」，根因链的第一步就是 ExoPlayer 播放列表里出现了 `[当前, 下一首, 下一首']`。
 *     「添加到下一首播放」如果在同一位置再塞一份，就人为造出了同一个形状。
 *  2. **本应用所有队列写入都去重**（`insertNext` / `appendToQueue` / `insertAllNext` /
 *     `appendAllToQueue` 四处全部 `filter { it.id != … }`）。只有这一处允许重复，
 *     会让「队列里同一首歌最多一份」这条事实不变量失效，而下游（队列面板、
 *     保存为歌单、INFINITY 的 `existingIds` 过滤）都默认它成立。
 *  3. 用户意图上，「添加到下一首播放」表达的是**位置**，不是**次数**；
 *     想重复听有单曲循环（`QueueModes.SINGLE`）。
 *
 * 代价（如实记录）：用户无法用这个入口把同一首歌排两次。
 * 需要重复时的既有手段是单曲循环，本版不新增「允许重复」的开关。
 *
 * ## 身份：v2.5.3 · P1 起用 [TrackKey]（不再是裸 `Long` id）
 *
 * v2.5.0 写这一节时的原话是「本对象按 `song.id` 判重，与全部既有队列写入同一把尺子；
 * `PlayerViewModel` 另有更严格的 `TrackKey`，但队列去重从来没用过它 —— 本版刻意
 * 不单独升级判重强度，以免两处身份语义不一致」。那个取舍在当时是对的：**单独**升级
 * 这一处确实会制造第三种口径。
 *
 * v2.5.3 换了一条路：**不是升级一处，而是把全部队列写入一起升级**，
 * 并把身份运算收敛到 `player/QueueKeys.kt`。于是「不一致」的顾虑消失了，
 * 队列判重与待播槽位（[PreloadSlot]）、歌词闸门、续播恢复用同一个 [TrackKey]。
 *
 * 探针结论（`docs/verification/v2.5.3/probe-queue-dedup.md`）：跨源裸 id 撞号的
 * **实际发生率是 0** —— 全部 QQ id 都经 `SourceIds.qqId()` 产出、bit62 恒置位，
 * 与 ncm 的 id 区间结构性不相交。所以这次改造**不修任何线上 bug**，
 * 它买到的是「那个 0 不再依赖调用点的纪律，而由类型承载」。
 */
object QueueInsert {

    /** 一次「添加到下一首播放」的判定结果类型。 */
    enum class Outcome {
        /**
         * 队列为空（或没有有效的当前项）⇒ **立刻播放这一首**（任务书 §6.2「队列为空 → 直接播放」）。
         * 调用方必须真的起播，不能只改队列 —— 这是既有点上的真实缺陷。
         */
        START_FRESH,

        /** 正常插入到当前歌之后。当前歌不变、不打断播放。 */
        INSERTED,

        /** 这首歌原本在队列别处，被**移动**到了当前歌之后（不是新增一份）。 */
        MOVED_TO_NEXT,

        /** 它已经在下一首的位置上 ⇒ 幂等忽略，队列一个字节不动。 */
        ALREADY_NEXT,

        /** 它就是**正在播**的那一首 ⇒ 幂等忽略。 */
        ALREADY_CURRENT,
    }

    /**
     * 判定结果。
     *
     * @param keys 新队列的**身份序列**（[Outcome.ALREADY_NEXT] / [Outcome.ALREADY_CURRENT] 时与入参相同）
     * @param currentIndex 新的 `currentQueueIndex`
     * @param insertPos 新歌在新队列里的下标；**-1 表示这次调用没有改变队列**
     * @param outcome 结果类型，调用方据此决定提示文案
     */
    data class Plan(
        val keys: List<TrackKey>,
        val currentIndex: Int,
        val insertPos: Int,
        val outcome: Outcome,
    ) {
        /** 队列是否真的变了（变了才需要落盘 + 重建列表）。 */
        val queueChanged: Boolean
            get() = outcome == Outcome.START_FRESH ||
                outcome == Outcome.INSERTED ||
                outcome == Outcome.MOVED_TO_NEXT

        /** 是否需要立刻起播（只有空队列那一条路径需要）。 */
        val shouldStartPlayback: Boolean
            get() = outcome == Outcome.START_FRESH
    }

    /**
     * 计算「把 [newKey] 添加到当前歌的下一首」之后队列该长什么样。
     *
     * 覆盖的边界（每条都有对应用例，见 `QueueInsertTest`）：
     *
     * | 输入 | 结果 |
     * |---|---|
     * | 队列为空 | [Outcome.START_FRESH]，队列 = `[newKey]`，`currentIndex = 0` |
     * | 有队列但没有有效当前项 | [Outcome.START_FRESH]，追加到队尾并**起播它** |
     * | `newKey` == 当前歌 | [Outcome.ALREADY_CURRENT]，队列不变 |
     * | `newKey` 已在 `currentIndex + 1` | [Outcome.ALREADY_NEXT]，队列不变（**幂等**） |
     * | 当前歌是**最后一首** | [Outcome.INSERTED]，插到队尾（`insertPos == size - 1`） |
     * | `newKey` 在队列别处 | [Outcome.MOVED_TO_NEXT]，把它**搬**过来，不新增 |
     * | 其余 | [Outcome.INSERTED] |
     *
     * @param queueKeys 当前队列的**身份序列**（调用方保证**已去重**，与既有队列写入一致）
     * @param currentIndex 当前 `currentQueueIndex`；不在 `queueKeys.indices` 内视为「没有当前项」
     * @param newKey 要插入的那首歌的身份
     *
     * ## v2.5.3 · P1：参数从裸 `Long` 换成 [TrackKey]
     *
     * 旧签名是 `plan(queueIds: List<Long>, currentIndex: Int, newId: Long)`，
     * 与当时 `MainActivity` 里另外三处队列写入用同一把尺子（裸 `song.id`）。
     * 探针确认跨源撞号的实际发生率是 **0**（QQ 的 id 带 bit62 标志位，与 ncm 的
     * id 区间结构性不相交），但那 0 依赖「每个 id 生产者都记得走 `SourceIds.qqId`」
     * 这条**纪律**；换成 [TrackKey] 之后判重语义由类型承载，且与待播槽位、
     * 歌词闸门、续播恢复**只剩一套**身份规则。
     *
     * 换成强类型之后，`QueueInsert.plan(queue.map { it.id }, …)` 这种写法**编译不过** ——
     * 这正是要的效果：队列身份不许再退回裸 id。
     */
    fun plan(queueKeys: List<TrackKey>, currentIndex: Int, newKey: TrackKey): Plan {
        // ── 边界 1/2：没有有效的当前项 ────────────────────────────────────────
        // 队列为空 ⇒ 这一首就是全部（任务书 §6.2「队列为空 → 直接播放」）。
        // 队列非空但下标失效（理论上不该出现，但它是**可能**的状态）⇒ 追加到队尾并起播它，
        // 而不是把 id 硬塞到下标 0 —— 那会让队列顺序莫名其妙地反转。
        if (currentIndex !in queueKeys.indices) {
            val rest = queueKeys.filter { it != newKey }
            val keys = rest + newKey
            return Plan(
                keys = keys,
                currentIndex = keys.lastIndex,
                insertPos = keys.lastIndex,
                outcome = Outcome.START_FRESH,
            )
        }

        // ── 边界 3：它就是在播的那一首 ────────────────────────────────────────
        // 既有实现在这里 `return`（静默无反馈）。本对象把它变成一个**可上报的结果**，
        // 让 UI 能给出「这首歌正在播放」而不是「点了没反应」。
        val currentKey = queueKeys[currentIndex]
        if (newKey == currentKey) {
            return Plan(queueKeys, currentIndex, -1, Outcome.ALREADY_CURRENT)
        }

        // ── 边界 4：它已经在下一首的位置上 ────────────────────────────────────
        // 幂等：队列一个字节不动。注意必须用「旧队列里 newId 的位置」判断，
        // 不能用「去重后的位置」—— 后者恒等于 currentIndex + 1，永远成立。
        val existingIndex = queueKeys.indexOf(newKey)
        if (existingIndex == currentIndex + 1) {
            return Plan(queueKeys, currentIndex, existingIndex, Outcome.ALREADY_NEXT)
        }

        // ── 通用路径 ──────────────────────────────────────────────────────────
        // ① 去重：把 newId 从任何位置拿掉；
        // ② **重新定位**当前歌 —— 这是 AGENTS.md 点名的关键不变量：
        //    直接 `.filter` 之后沿用旧下标，会把 currentQueueIndex 指到错误的项上。
        val filtered = queueKeys.filter { it != newKey }
        val relocated = filtered.indexOf(currentKey)
        // relocated 不可能为 -1：newKey != currentKey，filter 删不掉当前歌。
        // 但仍然显式兜底 —— 真出现 -1 说明调用方违反了「队列已去重」的前置条件，
        // 此时插到队首比抛异常好（播放链路不能因为队列写入而中断）。
        val safeRelocated = if (relocated >= 0) relocated else 0

        // ── 边界 5：当前歌是最后一首 ⇒ 插到队尾 ───────────────────────────────
        // `coerceIn` 让 `currentIndex + 1 == filtered.size` 时落在末尾。
        // 插到末尾之后当前歌**不再**是最后一首，所以 CYCLE/LINE/INFINITY 的
        // 「队尾没有下一首」判定会自然指向它 —— 不需要为这个边界写特例。
        val insertPos = (safeRelocated + 1).coerceIn(0, filtered.size)
        val keys = filtered.toMutableList().also { it.add(insertPos, newKey) }

        return Plan(
            keys = keys,
            currentIndex = safeRelocated,
            insertPos = insertPos,
            // 边界 6：原本就在队列别处 ⇒ 这是「搬过来」，不是「新增一首」。
            // 区分它只为文案准确（「已移到下一首」vs「已添加到下一首」）。
            outcome = if (existingIndex >= 0) Outcome.MOVED_TO_NEXT else Outcome.INSERTED,
        )
    }

    /**
     * **随机模式**下把插入的歌接到当前歌的播放顺序之后（铁律 16 的「随机模式」）。
     *
     * ## 为什么必须有这一步（这是本版修掉的真实缺陷）
     *
     * 乱序播放的下一首**不是** `queue[currentIndex + 1]`，而是
     * `shuffledIndices[shuffledPosition + 1]`（契约见 [ShuffleRound]：
     * `shuffledIndices[shuffledPosition]` 恒等于正在播的那一首）。
     * 只改线性队列、不改编排序列，用户点「添加到下一首播放」之后
     * **下一首仍然会是原来那首随机歌** —— 功能静默失效。
     *
     * ## 算法
     *
     * 1. 把旧排列的每一项**按 id 映射**到新队列的下标（不按 `±1` 算术搬 ——
     *    因为这次变更同时包含「删除一处」与「插入一处」，纯算术会错）；
     * 2. 丢掉映射到 [Plan.insertPos] 的那一项（它是被搬走的那一份，避免重复）；
     * 3. 把 [Plan.insertPos] 插到**当前歌在新排列里的位置之后**。
     *
     * ## 返回 null 的含义
     *
     * **任何无法保证结果是合法排列的情况都返回 null**，调用方据此回退到
     * 「重新生成一轮乱序」。宁可重洗一轮，也绝不给出一个错的播放顺序 ——
     * 错顺序的表现是「点了添加，下一首是别的歌」或「某首歌再也播不到」，
     * 而重洗一轮只是随机性变了一次。
     *
     * @param oldKeys 变更**前**的队列**身份**序列
     * @param newKeys 变更**后**的队列身份序列（= [Plan.keys]）
     * @param shuffled 变更前的乱序排列（旧下标）
     * @param newCurrentIndex 变更后的 `currentQueueIndex`
     * @param insertPos 新歌在 [newKeys] 里的下标
     */
    fun shuffleAfterInsert(
        oldKeys: List<TrackKey>,
        newKeys: List<TrackKey>,
        shuffled: List<Int>,
        newCurrentIndex: Int,
        insertPos: Int,
    ): List<Int>? {
        // 没有可修正的排列（空队列 / 尚未建立乱序）⇒ 交给调用方重新生成。
        if (shuffled.isEmpty()) return null
        if (insertPos !in newKeys.indices) return null
        if (newCurrentIndex !in newKeys.indices) return null

        // O(n) 反查表：队列可能有上千首，逐项 indexOf 会退化成 O(n²)。
        val posByKey = HashMap<TrackKey, Int>(newKeys.size * 2)
        for (i in newKeys.indices) posByKey[newKeys[i]] = i

        val shifted = ArrayList<Int>(shuffled.size)
        for (oldIdx in shuffled) {
            val key = oldKeys.getOrNull(oldIdx) ?: return null
            val newIdx = posByKey[key] ?: return null
            // 被搬走的那一份：丢弃旧的落点，稍后统一插到当前位置之后。
            if (newIdx == insertPos) continue
            shifted.add(newIdx)
        }

        val currentPos = shifted.indexOf(newCurrentIndex)
        // 当前歌不在排列里 ⇒ 这个排列已经与队列脱节，重洗比修补安全。
        if (currentPos < 0) return null

        val out = ArrayList<Int>(shifted.size + 1)
        out.addAll(shifted.subList(0, currentPos + 1))
        out.add(insertPos)
        out.addAll(shifted.subList(currentPos + 1, shifted.size))

        // 最后一道闸：结果必须是**恰好覆盖新队列每个下标一次**的排列。
        // 不满足就返回 null（重洗），绝不把一个缺项/重项的排列交给播放链路 ——
        // 那正是「某首歌再也播不到」或「同一首连播两次」的形状。
        if (out.size != newKeys.size) return null
        if (out.toHashSet().size != newKeys.size) return null
        return out
    }

    /**
     * 插入之后，**下一首应该是队列里的哪一个下标**；-1 表示没有下一首。
     *
     * 只回答「我们刚刚插进去的那一首在哪」—— 这正是本功能要保证的事，
     * 所以**不需要复刻** `playFromQueue` / `needsPreload` / `songTransitioned`
     * 里那三份按模式分支的「下一首」逻辑（它们还带 INFINITY 续播、CYCLE 回绕等
     * 与本次插入无关的行为）。把那些一并抽出来会改动播放主链路，
     * 风险远大于收益。
     *
     * - 乱序：[shuffledIndices] 里当前歌之后的那一项；
     * - 其余模式（含 CYCLE / LINE / INFINITY / SINGLE）：线性队列的 `currentIndex + 1`。
     *
     * @param shuffledIndices 已按 [shuffleAfterInsert] 修正过的排列
     */
    fun nextIndexAfterInsert(
        playMode: Int,
        size: Int,
        newCurrentIndex: Int,
        shuffledIndices: List<Int>,
    ): Int {
        if (size <= 0) return -1
        if (playMode == QueueModes.SHUFFLE) {
            val pos = shuffledIndices.indexOf(newCurrentIndex)
            if (pos < 0) return -1
            return shuffledIndices.getOrNull(pos + 1) ?: -1
        }
        val next = newCurrentIndex + 1
        return if (next in 0 until size) next else -1
    }

    /**
     * 插入之后**要不要重新同步待播槽位**。
     *
     * ## 这是本版修掉的核心缺陷（不是可选优化）
     *
     * ExoPlayer 的播放列表里，当前项之后**至多一首**预载项，且
     * `PlaybackService.pendingNext*` 必须与它一一对应（v1.5.2 的不变量，见 [PreloadSlot]）。
     *
     * 既有的队列写入（`insertNext` / `insertAllNext` / `appendToQueue` …）**都不碰这个槽位**。
     * 平时这没暴露问题，因为槽位会在「进入最后 60s」的 `needsPreload` 心跳里被
     * `PreloadSlot.Decide.REPLACE` 纠正。但「添加到下一首播放」是**立即**语义：
     *
     * ```
     * 1. A 在播，B 已被预载         → ExoPlayer 列表 = [A, B]
     * 2. 用户把 C 加到下一首         → 应用队列 = [A, C, B]，ExoPlayer 列表**仍是** [A, B]
     * 3. A 播完                     → ExoPlayer 播的**是 B**，而队列面板显示下一首是 C
     * ```
     *
     * 这正是用户报告的「串台」形状，只不过触发它的是本版新增的入口。
     * 因此插入之后**必须立刻重新预载新的下一首**，让 `PreloadSlot.decide` 走 REPLACE
     * 把旧的待播项换掉。
     *
     * ## 为什么单曲循环模式**不**重同步
     *
     * `QueueModes.SINGLE` 的语义是「永远重播当前这一首」，它的无缝实现方式是把
     * **当前歌自己**预载进槽位（`allowCurrent = true`）。若在这里改成预载「下一首」，
     * ExoPlayer 就会在播完后自动前进到它 —— 那等于**静默地把单曲循环改成了顺序播放**。
     * 所以单曲循环下照旧只插队列、不动槽位；退出单曲循环后该曲自然成为下一首。
     */
    fun shouldPreloadAfterInsert(playMode: Int): Boolean = playMode != QueueModes.SINGLE
}
