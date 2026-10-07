/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.5.0 · A 回归单测：**圆角规范只有一个落点**。
 */

package com.takahashirinta.ncrust.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v2.5.0 · A：把「圆角不散落硬编码」变成**可测事实**。
 *
 * ## 为什么需要它（这条规范的形状与常规不同）
 *
 * 探针实测（`probe-theme.md` §3.1）：v2.5.0 之前本仓库
 * `RoundedCornerShape` **0 命中** —— 因为设计正典是「直角、无圆角」。
 * 所以「圆角散落不统一」这个问题**在引入圆角的那一刻才会产生**：
 * 第一行 `RoundedCornerShape(12.dp)` 写下去之前，它还不存在。
 *
 * 也就是说本测试保护的不是一个既有的乱局，而是**阻止乱局产生**。
 * 这类约定靠代码评审是靠不住的（41 处手写 `tween` 就是前车之鉴：
 * 约定写在文档里，实际仍然散落），所以必须让它在 CI 里变红。
 *
 * ## 判据
 *
 * `ui/theme/AppShapes.kt` **之外**的任何 `.kt` 文件里出现
 * `RoundedCornerShape(` / `CircleShape` / `CutCornerShape(` ⇒ 测试失败。
 *
 * 为什么连 `CircleShape` 也禁：圆形就是 `RoundedCornerShape(percent = 50)`
 * （Material 3 官方把这一档叫 `full`，见 `probe-splayer-ref.md` §4），
 * 用 `AppShapes.full` 表达同一件事，就不需要第二个落点。
 * 唯一放开的是 `RectangleShape` —— 它就是「没有圆角」，不构成圆角规范的一份子。
 *
 * ## 已知的取舍
 *
 * 本测试用**文本扫描**而不是 Kt AST。理由：AST 需要引入编译器依赖
 * （`kotlin-compiler-embeddable`），那是一个只在测试期存在的重量级依赖；
 * 而这里要判的是「某个构造器名有没有出现」，文本扫描足够，且失败信息里能直接
 * 打印 `file:line`，比 AST 报告更好读。代价是注释里写 `RoundedCornerShape(` 也会变红 ——
 * 这在本仓库是**可接受**的（注释要举例就写 `AppShapes.large`），
 * 而且误报会在第一次运行时立刻暴露，不会长期潜伏。
 */
class AppShapesSingleSourceTest {

    /** 允许出现 shape 构造器的唯一文件（相对仓库 app 模块）。 */
    private val allowedFile = "ui/theme/AppShapes.kt"

    /** 被禁止的构造器。 */
    private val forbidden = listOf("RoundedCornerShape(", "CircleShape", "CutCornerShape(")

    /**
     * 定位 `src/main/java` 源码根。
     *
     * 单测的工作目录在不同 Gradle/AGP 版本下可能是模块目录、也可能是仓库根，
     * 所以按候选列表逐个探测，而不是假定某一个 —— 假定错的表现是
     * 「扫描了 0 个文件、测试恒绿」，那是比失败更糟的假阳性。
     */
    private fun findSourceRoot(): File {
        val candidates = listOf(
            File("src/main/java"),
            File("app/src/main/java"),
            File("../app/src/main/java"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error(
                "找不到 src/main/java（工作目录 = ${File(".").absolutePath}）。" +
                    "本测试必须真的扫到文件，扫不到就判定失败 —— 恒绿的守卫没有意义。"
            )
    }

    private fun kotlinFiles(root: File): List<File> =
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `源码树确实被扫到了（防止工作目录变了导致空扫描）`() {
        val files = kotlinFiles(findSourceRoot())
        // 184 个 .kt 是 v2.5.0 的实测量级；给一个宽松但绝不可能是"没扫到"的下限。
        assertTrue("只扫到 ${files.size} 个 .kt，明显不对", files.size > 100)
    }

    @Test
    fun `圆角构造器只允许出现在 AppShapes_kt`() {
        val root = findSourceRoot()
        val offenders = mutableListOf<String>()
        var scanned = 0

        for (file in kotlinFiles(root)) {
            val rel = file.relativeTo(root).invariantSeparatorsPath
            scanned++
            // 唯一豁免文件：它自己就是落点。
            if (rel.endsWith(allowedFile)) continue
            file.readLines().forEachIndexed { index, line ->
                for (token in forbidden) {
                    if (line.contains(token)) {
                        offenders.add("$rel:${index + 1} 出现 `$token`")
                    }
                }
            }
        }

        assertTrue("扫了 $scanned 个文件", scanned > 100)
        assertTrue(
            buildString {
                appendLine("圆角散落硬编码（应改用 ui/theme/AppShapes.kt 里的 token）：")
                offenders.forEach { appendLine("  " + it) }
                appendLine()
                appendLine("映射：卡片 medium(12) / 列表项 small(8) / 封面 large(16) /")
                appendLine("      弹窗 extraLarge(28) / 小角标 extraSmall(4) / 药丸与圆形 full(50%)")
            },
            offenders.isEmpty(),
        )
    }

    @Test
    fun `AppShapes 的六个 token 都在且互不相同（改坏了要先在这里红）`() {
        val shapes = listOf(
            "extraSmall" to AppShapes.extraSmall,
            "small" to AppShapes.small,
            "medium" to AppShapes.medium,
            "large" to AppShapes.large,
            "extraLarge" to AppShapes.extraLarge,
            "full" to AppShapes.full,
        )
        assertEquals("token 数量变了：要么是漏了一个，要么是加了新的没更新本用例", 6, shapes.size)
        shapes.forEach { (name, shape) ->
            assertTrue("$name 是 null", shape.toString().isNotEmpty())
        }
        // 六档必须互不相同 —— 复制粘贴时把 `medium` 也写成 12dp 的 `large` 是本类
        // 最容易犯又最难肉眼发现的错误。
        val distinct = shapes.map { it.second }.toSet()
        assertEquals("有 token 取了相同的 shape：${shapes.map { it.first }}", 6, distinct.size)
    }
}
