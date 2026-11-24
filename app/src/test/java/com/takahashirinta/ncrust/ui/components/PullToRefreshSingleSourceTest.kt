/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v4.2.0 回归单测：**下拉刷新指示器只能是悬浮层，不能是列表的一项**。
 */

package com.takahashirinta.ncrust.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v4.2.0：把「指示器不许再插进列表」变成**可测事实**。
 *
 * ## 为什么要有这条守卫
 *
 * v2.3.0–v4.1.10 的写法是 `item(key = "pull-refresh") { PullToRefreshIndicator(...) }`
 * —— 指示器是 `LazyColumn` 的**第一项**，靠 `translationY` 往上滑出槽位。
 * 真机（歌手详情页）暴露了两个纯结构性的毛病，而且**换个人照着旧写法再抄一遍就会复发**：
 *
 * 1. 往上滑出槽位后，它画的正是 header（同名 tab 行）的位置 ⇒ 转圈被切掉一半；
 * 2. `reveal` 有 0.15 的下限 ⇒ 手指动 1px 就往列表里插一项 40dp 的 item，
 *    松手又摘掉 ⇒ 内容上下各跳 40dp，一次手势里闪好几次。
 *
 * 这两条都不是「调参数能好」的问题 —— 只要指示器还是列表的一项，就一定会挤占布局。
 * 所以正解是**把它从布局里拿出来**（`PullToRefreshOverlay` 挂在内容区顶部），
 * 并用这条测试把「不许再放回列表」钉住。
 *
 * ## 判据
 *
 * `src/main/java` 下任何 `.kt`（除了本组件自己的实现文件）出现以下任一即失败：
 *   · `item(key = "pull-refresh")` —— 旧写法的指纹；
 *   · `PullToRefreshIndicator(` —— 那个已经被删掉的、会滑出槽位的旧组件名。
 *
 * 文本扫描而不是 AST：同 `AppShapesSingleSourceTest` 的取舍（不引入 kotlin-compiler
 * 依赖，失败信息直接给 `file:line`）。
 */
class PullToRefreshSingleSourceTest {

    private val forbidden = listOf(
        "item(key = \"pull-refresh\")" to "指示器又被放回列表了（会挤占布局 ⇒ 内容跳动 + 被 header 切掉）",
        "PullToRefreshIndicator(" to "旧组件已经删除（它靠 translationY 滑出槽位）；改用 PullToRefreshOverlay",
    )

    /** 允许出现上述字样的唯一文件：组件自己（KDoc 里要解释旧写法）。 */
    private val allowedFiles = listOf("ui/components/PullToRefreshBox.kt")

    private fun findSourceRoot(): File {
        val candidates = listOf(
            File("src/main/java"),
            File("app/src/main/java"),
            File("../app/src/main/java"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error(
                "找不到 src/main/java（工作目录 = ${File(".").absolutePath}）。" +
                    "扫不到文件就判定失败 —— 恒绿的守卫没有意义。"
            )
    }

    private fun kotlinFiles(root: File): List<File> =
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `源码树确实被扫到了（防止工作目录变了导致空扫描）`() {
        val files = kotlinFiles(findSourceRoot())
        assertTrue("只扫到 ${files.size} 个 .kt，明显不对", files.size > 100)
    }

    @Test
    fun `下拉指示器不许再作为列表项挂载`() {
        val root = findSourceRoot()
        val offenders = mutableListOf<String>()
        var scanned = 0

        for (file in kotlinFiles(root)) {
            val rel = file.relativeTo(root).invariantSeparatorsPath
            scanned++
            if (allowedFiles.any { rel.endsWith(it) }) continue
            file.readLines().forEachIndexed { index, line ->
                for ((token, why) in forbidden) {
                    if (line.contains(token)) offenders.add("$rel:${index + 1} 出现 `$token` —— $why")
                }
            }
        }

        assertTrue("扫了 $scanned 个文件", scanned > 100)
        assertTrue(
            buildString {
                appendLine("下拉刷新指示器只能是内容区顶部的悬浮层（PullToRefreshOverlay）：")
                offenders.forEach { appendLine("  " + it) }
                appendLine()
                appendLine("用法：DetailScaffold(..., pullToRefreshState = pullState)（详情页）")
                appendLine("      或 Box { 内容; PullToRefreshOverlay(pullState, Modifier.align(TopCenter)) }")
            },
            offenders.isEmpty(),
        )
    }
}
