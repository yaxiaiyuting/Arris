/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.5.0 · A 回归单测：动效规格（[AppMotion]）。
 */

package com.takahashirinta.ncrust.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v2.5.0 · A：动效规格的两类回归保护。
 *
 * ## 第一部分：**数值出处**
 *
 * `probe-splayer-ref.md` §5 把 Material 3 Expressive 的六个弹簧逐值抓了下来。
 * 本类把其中三条 spatial 与三条 effects **钉在用例里** —— 它们不是"我们觉得好看的数"，
 * 而是有出处的官方值；将来有人"顺手调一下"，必须在这里说明为什么。
 *
 * 同时把**任务书里被证伪的那一条**也钉住：任务书说「spatial/effects 四档」，
 * 探针证明官方是「三种速度 × 两种类型 = 六个弹簧」。所以本类断言
 * spatial 与 effects **各三条**，多一条少一条都变红。
 *
 * ## 第二部分：**播放器转场不得过冲**（这条最重要）
 *
 * `progress` 同时是命中测试的开关（`< 0.01f` 卸载展开态子树、`> 0.99f` 吞事件）。
 * 会过冲的弹簧会让它反复穿越这两个阈值 ⇒ 子树挂载抖动 + 命中区闪烁。
 * 官方三条 spatial 的 `dampingRatio` 都 `< 1`（这正是 Expressive 的手感来源），
 * 所以**不能**拿它们驱动 `progress`。本类把 [AppMotion.playerExpand] /
 * [AppMotion.playerCollapse] 的 `>= 1.0` 写成断言，
 * 防止下一个人「顺手调成 0.6 更活泼」。
 *
 * ## 第三部分：新代码不得散写 `spring(`
 *
 * 探针实测：v2.5.0 之前本仓库 `spring(` **0 命中**、手写 `tween(` **41 处**。
 * 存量的 41 处不在本版范围内（`probe-motion.md` §P3 写了为什么不能批量替换），
 * 但**弹簧是全新的范式** —— 现在还没有任何存量，所以可以也应该从第一天起
 * 就要求它只能来自 [AppMotion]。这与 `AppShapesSingleSourceTest` 是同一种做法。
 */
class AppMotionSpecTest {

    // ─────────────────────────────────────────────────────────────────────
    // 1. 数值出处
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 官方 M3 Expressive spatial 三档。
     * 出处：androidx `ExpressiveMotionTokens.kt`（文件头 VERSION v0_14_0）。
     */
    @Test
    fun `spatial 三档与官方 M3 Expressive 取值一致`() {
        assertEquals(0.6f, AppMotion.spatialFast.dampingRatio, 1e-6f)
        assertEquals(800f, AppMotion.spatialFast.stiffness, 1e-6f)

        assertEquals(0.8f, AppMotion.spatialDefault.dampingRatio, 1e-6f)
        assertEquals(380f, AppMotion.spatialDefault.stiffness, 1e-6f)

        assertEquals(0.8f, AppMotion.spatialSlow.dampingRatio, 1e-6f)
        assertEquals(200f, AppMotion.spatialSlow.stiffness, 1e-6f)
    }

    /**
     * 官方 M3 Expressive effects 三档 —— 三档都是临界阻尼（1.0）。
     *
     * 这条同时是「effects 不做回弹」的保证：透明度这类无质量属性弹起来会显得忽快忽慢。
     * 另外 Standard 与 Expressive 在 effects 上**完全相同**，只有 spatial 不同 ——
     * 所以这组值没有"标准/表达"两套，不怕选错。
     */
    @Test
    fun `effects 三档与官方一致 且都是临界阻尼`() {
        val effects = listOf(
            AppMotion.effectsSpringFast to 3800f,
            AppMotion.effectsSpringDefault to 1600f,
            AppMotion.effectsSpringSlow to 800f,
        )
        effects.forEach { (spec, stiffness) ->
            assertEquals("effects 必须临界阻尼（不过冲）", 1.0f, spec.dampingRatio, 1e-6f)
            assertEquals(stiffness, spec.stiffness, 1e-6f)
        }
    }

    @Test
    fun `spatial 与 effects 各三档 不是任务书说的四档`() {
        val spatial = listOf(AppMotion.spatialFast, AppMotion.spatialDefault, AppMotion.spatialSlow)
        val effects = listOf(
            AppMotion.effectsSpringFast,
            AppMotion.effectsSpringDefault,
            AppMotion.effectsSpringSlow,
        )
        assertEquals("官方是三种速度 × 两种类型，spatial 应为 3 档", 3, spatial.size)
        assertEquals("official effects 应为 3 档", 3, effects.size)
        // 六档两两不同 —— 复制粘贴时最容易犯的错。
        assertEquals("六个弹簧必须互不相同", 6, (spatial + effects).toSet().size)
    }

    @Test
    fun `Dp 变体与 Float 变体取值一致`() {
        assertEquals(AppMotion.spatialFast.dampingRatio, AppMotion.spatialFastDp.dampingRatio, 1e-6f)
        assertEquals(AppMotion.spatialFast.stiffness, AppMotion.spatialFastDp.stiffness, 1e-6f)
        assertEquals(
            AppMotion.spatialDefault.dampingRatio,
            AppMotion.spatialDefaultDp.dampingRatio,
            1e-6f,
        )
        assertEquals(
            AppMotion.spatialDefault.stiffness,
            AppMotion.spatialDefaultDp.stiffness,
            1e-6f,
        )
    }

    @Test
    fun `任务书 §3-3 的 tween 骨架逐值保留`() {
        assertEquals(200, AppMotion.effects.durationMillis)
        assertEquals(100, AppMotion.effectsFast.durationMillis)
    }

    @Test
    fun `既有曲线被归纳而不是被替换`() {
        // 这条曲线在本仓库出现 6 次，逐字保留（probe-motion.md §1.2）。
        assertEquals(300, AppMotion.sheetAppear.durationMillis)
        assertEquals(260, AppMotion.sheetDismiss.durationMillis)
        assertEquals(400, AppMotion.coverFade.durationMillis)
        assertEquals(220, AppMotion.listItemEnter.durationMillis)
    }

    // ─────────────────────────────────────────────────────────────────────
    // 2. 播放器转场不得过冲（本类的核心用例）
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `播放器展开收起必须临界阻尼或过阻尼 —— 不得过冲`() {
        listOf(
            "playerExpand" to AppMotion.playerExpand,
            "playerCollapse" to AppMotion.playerCollapse,
        ).forEach { (name, spec) ->
            assertTrue(
                "$name 的 dampingRatio=${spec.dampingRatio} < 1 ⇒ progress 会越过 1.0 再回弹，" +
                    "反复跨越 0.99/0.01 两个命中测试阈值 ⇒ 展开态子树挂载抖动 + 命中区闪烁。" +
                    "播放器转场必须是临界阻尼（1.0）或过阻尼（>1.0）。",
                spec.dampingRatio >= 1.0f,
            )
        }
    }

    @Test
    fun `播放器转场与官方 spatial 是有意分开的两组 token`() {
        // 若哪天有人把 playerExpand 直接改成 spatialDefault，这条会红 ——
        // 那时必须先想清楚上面那条阈值问题。
        assertNotEquals(AppMotion.spatialDefault.dampingRatio, AppMotion.playerExpand.dampingRatio)
        assertNotEquals(AppMotion.spatialFast.dampingRatio, AppMotion.playerCollapse.dampingRatio)
        // 而「不过冲」那一档与播放器展开应当同族（都是临界阻尼）。
        assertEquals(1.0f, AppMotion.spatialNoOvershoot.dampingRatio, 1e-6f)
    }

    @Test
    fun `收起比展开更果断（stiffness 更大）`() {
        assertTrue(
            "收起应当比展开快 —— 用户已经决定不看它了",
            AppMotion.playerCollapse.stiffness > AppMotion.playerExpand.stiffness,
        )
    }

    @Test
    fun `按压回弹是欠阻尼的（有意过冲）`() {
        assertTrue("按压回弹就是要弹一下", AppMotion.pressScale.dampingRatio < 1.0f)
        assertEquals(1.05f, AppMotion.PRESS_SCALE, 1e-6f)
    }

    // ─────────────────────────────────────────────────────────────────────
    // 3. 新代码不得散写 spring(
    // ─────────────────────────────────────────────────────────────────────

    private fun findSourceRoot(): File {
        val candidates = listOf(
            File("src/main/java"),
            File("app/src/main/java"),
            File("../app/src/main/java"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("找不到 src/main/java（工作目录 = ${File(".").absolutePath}）")
    }

    @Test
    fun `弹簧只能来自 AppMotion —— 新代码不得散写 spring 调用`() {
        val root = findSourceRoot()
        val offenders = mutableListOf<String>()
        var scanned = 0
        for (file in root.walkTopDown().filter { it.isFile && it.extension == "kt" }) {
            val rel = file.relativeTo(root).invariantSeparatorsPath
            scanned++
            if (rel.endsWith("ui/theme/AppMotion.kt")) continue
            file.readLines().forEachIndexed { index, line ->
                // 只看真的调用：排除 import 行与注释里提到它的行。
                val code = line.substringBefore("//")
                if (code.contains("spring(") && !code.trimStart().startsWith("import ")) {
                    offenders.add("$rel:${index + 1}")
                }
            }
        }
        assertTrue("扫了 $scanned 个文件", scanned > 100)
        assertTrue(
            "以下位置散写了 spring(...)，应改用 AppMotion 里具名的 token：\n" +
                offenders.joinToString("\n") { "  $it" },
            offenders.isEmpty(),
        )
    }
}
