/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 修改说明（歌单详情「长按进入多选」）：
 *   把多选的状态迁移从 PlaylistDetailScreen.kt 里抽成**纯函数**，便于 JVM 单测。
 *   原先这类状态只能靠真机点，而「点掉最后一个该不该退出模式」这类边界
 *   恰恰是最容易在两个分支里写得不一样的地方。
 */

package com.takahashirinta.ncrust.ui.screen

/**
 * 歌单详情「多选」的纯状态：不可变值，所有迁移都返回新值。
 *
 * 屏幕侧只负责把它存进 `mutableStateOf`，UI 上是 6 个入口：
 * 长按进入 / 点击切换 / 全选 / 取消全选 / 退出 / 批量移出后清空。
 * 把这 6 条迁移写在这里而不是散在 Composable 的 `when` 里，是为了让
 * [PlaylistSelectionTest] 能在 JVM 上逐条钉住它们。
 *
 * ## 不变式（改这个类之前先读）
 *
 * 1. **`ids` 只在 [active] 为真时有意义**：[exit] 一定把两者同时清空 ——
 *    否则「退出多选后残留的选中集」会在下次进入时以「已选 N 首」的形式复活。
 * 2. **[toggle] 允许把选择清空到 0 而不退出**：多选模式的出口只有 [exit] 与
 *    [afterRemove]。点掉最后一个是「我反悔了」，不是「我结束多选」——
 *    Android 列表的惯例如此，也让用户能在一个都不选时直接点行外的操作。
 * 3. **[afterRemove] 只在「移除后没有剩余选中」时才退出**：批量移出的语义是
 *    「把选中的这些删掉」，删完还在多选模式里等下一次操作是没有意义的；
 *    但若只删掉了一部分（理论上不会，UI 是一次性删完），剩下的仍保持选中。
 * 4. [selectAll] / [clearSelection] 都是**幂等**的：重复点全选不会累加、
 *    重复点取消不会把状态弄乱。
 */
data class PlaylistSelection(
    /** 是否处于多选模式。false 时所有迁移都是空操作（除 [enter]）。 */
    val active: Boolean = false,
    /** 已选曲目 id 集合。顺序无意义（Set），与列表顺序无关。 */
    val ids: Set<Long> = emptySet(),
) {
    /** 已选数量，直接给选择条上的「已选 N 首」用。 */
    val count: Int get() = ids.size

    fun isSelected(id: Long): Boolean = id in ids

    /**
     * 「全选」是否已达成 —— 决定选择条上那个按钮是显示「全选」还是「取消全选」。
     *
     * 空列表一律返回 false：歌单空的时候不展示「取消全选」，否则按钮点了没有变化。
     */
    fun isAllSelected(all: Collection<Long>): Boolean {
        val target = all.toSet()
        return active && target.isNotEmpty() && ids.containsAll(target)
    }

    /**
     * 长按进入多选并选中这一行。
     *
     * 幂等：已经在多选模式时再进入，等价于「只选中这一行」（与长按别的行效果一致）。
     * UI 侧在模式内不派发长按，所以这里不承担「累加」语义。
     */
    fun enter(id: Long): PlaylistSelection = PlaylistSelection(active = true, ids = setOf(id))

    /** 多选模式下点击一行 = 切换选中。非多选模式下是空操作（点击仍然走播放）。 */
    fun toggle(id: Long): PlaylistSelection =
        if (!active) this else copy(ids = if (id in ids) ids - id else ids + id)

    /** 全选：把 [all] 里的全部 id 收进选中集。 */
    fun selectAll(all: Collection<Long>): PlaylistSelection =
        if (!active) this else copy(ids = all.toSet())

    /** 取消全选：清空选中但**不退出**多选模式（用户可以接着点单个行）。 */
    fun clearSelection(): PlaylistSelection =
        if (!active) this else copy(ids = emptySet())

    /** 退出多选（返回键 / 选择条上的 ✕）。两个字段一起清，见不变式 1。 */
    fun exit(): PlaylistSelection = None

    /**
     * 列表刷新后剔除已经不存在的 id。
     *
     * 存在的理由：进详情页时先渲染缓存、再拉一次网络（`ContentCache` 的既有节奏），
     * 刷新回来的曲目可能少了（别人删了 / 缓存是旧的）。留着已消失的 id 会让
     * 「已选 3 首」与「实际只有 2 首能被移除」对不上。
     */
    fun retainExisting(existing: Collection<Long>): PlaylistSelection =
        if (!active) this else copy(ids = ids intersect existing.toSet())

    /**
     * 批量移出成功之后调用。
     *
     * 全部删完 ⇒ 退出多选（选择条随之消失，不会有「已选 0 首」的空壳挂着）；
     * 还有剩余 ⇒ 只把删掉的那些从选中集里去掉。
     */
    fun afterRemove(removed: Collection<Long>): PlaylistSelection {
        if (!active) return this
        val left = ids - removed.toSet()
        return if (left.isEmpty()) None else copy(ids = left)
    }

    companion object {
        /** 非多选状态的唯一取值（也是初始值）。 */
        val None = PlaylistSelection()
    }
}
