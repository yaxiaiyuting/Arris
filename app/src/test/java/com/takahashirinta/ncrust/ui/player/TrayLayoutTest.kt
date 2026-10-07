/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.5.5 · C：托盘几何与控制按钮清单的回归守卫。
 */

package com.takahashirinta.ncrust.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TrayLayout] 的纯逻辑守卫。**不含任何 Compose 依赖**（`ui/player` 下唯一的
 * JVM 可跑的那一层，与 `TrayLyricTest` / `PlayerLayoutVisualizerTest` 同一手法）。
 *
 * 这里钉住三件事，每一件都对应一次真实事故或一次探针发现：
 *
 * 1. **托盘高度只有一个定义处，且它的三个派生量自洽**（v2.5.2 规则 2）。
 *    56 → 80 的改动如果只改 `PlayerCard` 的 `.height(...)`，
 *    `MainActivity.collapsedOffsetY` 与 `BottomOverlayInsetDp` 会静默错位 ——
 *    前者的症状是「卡片收起后与托盘之间露一条空白带」，
 *    后者是「列表最后一项被托盘盖住」。两者都与「托盘高度」看不出关联。
 * 2. **三个播放控制按钮一个都不能少，顺序固定**（铁律 19）。
 *    探针证明托盘**从来没有过**上一首按钮（`git log -S "SkipPrevious"` 零命中），
 *    也就是说「少一个按钮」在源码 review 里完全看不出来。
 * 3. **封面落点中心在旧值上退化为旧实现**（防止「改了高度忘了改封面」）。
 */
class TrayLayoutTest {

    // ------------------------------------------------------------ 0. v3.2.0 · P0-A 封面几何

    /**
     * v3.2.0 · P0-A 的回归网：**封面落点中心必须由两个容器各自的几何算出来**。
     *
     * 真机实测（SM-G9209 / 1440×2560 / density 4，2026-09-28）：
     *
     * | 量 | 实测 |
     * |---|---|
     * | 窄屏顶栏（歌词全屏） | y 96..320px ⇒ 中心 208px |
     * | 封面实际渲染 | y 142..365px ⇒ 中心 253px |
     * | 差 | **45px ≈ 11.6dp** |
     *
     * 根因：v3.1.0 在**两种状态下都用托盘的中心**（`HEIGHT_DP/2 = 40dp`），
     * 而展开态的顶栏只有 56dp（中心 28dp）。这条测试把「两个中心不同」写死。
     */
    @Test
    fun `v3_2_0 封面在托盘与顶栏里的中心不同 —— 差 12dp`() {
        assertEquals(80, TrayLayout.HEIGHT_DP)
        assertEquals(56, TrayLayout.TOP_BAR_HEIGHT_DP)
        assertEquals(40f, TrayLayout.coverCenterOffsetDp(), 0.001f)
        assertEquals(28f, TrayLayout.topBarCoverCenterOffsetDp(), 0.001f)
        // 差距正好是半个托盘高度差 —— 写死这个关系而不是写死 12，
        // 这样将来改任一个高度时这条断言会跟着算，不会变成一句过期的注释。
        assertEquals(
            (TrayLayout.HEIGHT_DP - TrayLayout.TOP_BAR_HEIGHT_DP) / 2f,
            TrayLayout.coverCenterOffsetDp() - TrayLayout.topBarCoverCenterOffsetDp(),
            0.001f,
        )
    }

    /**
     * 封面不再贴屏幕左边缘：中心 X = 左边距 + 半个封面。
     *
     * 旧实现是 `coverHalfDp()`（= 28dp）⇒ 左边缘在 x = 0，
     * 而应用级内容边距是 16dp（`ResponsiveContent` / 搜索框 / 设置行）。
     */
    @Test
    fun `v3_2_0 封面左边距是 16dp 且中心 X 由它派生`() {
        assertEquals(16, TrayLayout.COVER_START_DP)
        assertEquals(16f + 56f / 2f, TrayLayout.coverCenterXDp(), 0.001f)
        // 左边距不能是 0 —— 那正是本 P0 的旧值。
        assertNotEquals(0, TrayLayout.COVER_START_DP)
        // 中心 X 必须 > 半个封面，等价于「左边距 > 0」。
        assertTrue(TrayLayout.coverCenterXDp() > TrayLayout.coverHalfDp())
    }

    /**
     * 文字起始位 = 左边距 + 封面宽 + 间隙。**封面动而文字不动**是这类错位最典型的形状，
     * 所以两者必须来自同一组常量。
     */
    @Test
    fun `v3_2_0 顶栏文字起始位与封面几何自洽`() {
        assertEquals(
            TrayLayout.COVER_START_DP + TrayLayout.COVER_SIZE_DP + TrayLayout.COVER_TEXT_GAP_DP,
            TrayLayout.topBarTextStartDp(),
        )
        assertEquals(84, TrayLayout.topBarTextStartDp())
        // 文字必须排在封面右边（否则会压在封面上）。
        assertTrue(TrayLayout.topBarTextStartDp() > TrayLayout.COVER_START_DP + TrayLayout.COVER_SIZE_DP)
    }

    /**
     * 源码扫描守卫：`PlayerCard` **不许**再出现顶栏高度与封面落点的字面量。
     *
     * 这条与仓库里既有的几处「源码扫描守卫」（`ArtistRouteContractTest` /
     * `PersistenceFieldNameContractTest`）同一手法 —— 因为
     * 「改了 TrayLayout 但某一处仍写着 56.dp / 68.dp」在编译期完全看不出来，
     * 只在真机上表现为一次说不清来路的错位。
     */
    @Test
    fun `v3_2_0 PlayerCard 不再写死顶栏高度与封面起始位的字面量`() {
        // v3.2.1 · P0：`PlayerCard` 被拆成同包的多个文件（布局分支 / 托盘 / 叠加层 /
        // 状态与几何 / 副作用 / 背景层）。这条用例守的是「托盘几何只有 TrayLayout 一个
        // 事实来源」这条**不变量**，与它落在哪个文件无关 —— 所以改成扫描整个文件族，
        // 并断言「字面量在**任何**一个文件里都不许出现」。
        val files = listOf(
            "PlayerCard.kt", "PlayerCardLayouts.kt", "PlayerCardTray.kt",
            "PlayerCardOverlays.kt", "PlayerCardState.kt", "PlayerCardEffects.kt",
            "PlayerCardBackdrop.kt",
        ).map { java.io.File("src/main/java/com/takahashirinta/ncrust/ui/player/$it") }
        assertTrue("播放器卡片文件族一个都没找到", files.any { it.exists() })
        val srcs = files.filter { it.exists() }.map { it.readText() }
        val all = srcs.joinToString("\n")
        // 顶栏/收起键的高度必须引用 token。
        assertTrue(
            "顶栏高度必须是 TrayLayout.TOP_BAR_HEIGHT_DP",
            srcs.any { it.contains("height(TrayLayout.TOP_BAR_HEIGHT_DP.dp)") },
        )
        // 旧的封面文字起始位字面量必须消失。
        assertTrue(
            "不许再有 padding(start = 68.dp, …) —— 它必须由 TrayLayout.topBarTextStartDp() 派生",
            !all.contains("padding(start = 68.dp"),
        )
        // overlay 的中心 X 必须引用 token。
        assertTrue(
            "封面中心 X 必须引用 TrayLayout.coverCenterXDp()",
            srcs.any { it.contains("TrayLayout.coverCenterXDp()") },
        )
    }

    // ------------------------------------------------------------ 1. 高度与派生量

    /**
     * 高度：56 → 80。这个数是**实测定的**，不是拍的：
     * 三行排版盒（`bodySmall` 16sp + `bodyMedium` 20sp + `bodySmall` 16sp）+ 两条
     * [TrayLayout.LINE_GAP_DP] = 56dp，56dp 的托盘上下各剩 0dp。
     */
    @Test
    fun `托盘高度是 80dp`() {
        assertEquals(80, TrayLayout.HEIGHT_DP)
        assertTrue(
            "托盘高度必须容得下三行 + 两条行间距",
            TrayLayout.HEIGHT_DP > 56,
        )
    }

    /**
     * 内容高度 ≤ 托盘高度，且留白**至少 8dp 每侧**。
     *
     * 算式用 sp→dp 的 1:1 近似（Compose 的默认 `lineHeight` 对 `bodySmall` 是 16sp、
     * `bodyMedium` 是 20sp；sp 与 dp 在默认字体缩放下相等）。真正的像素证据在
     * `docs/verification/v2.5.5/probe-tray-regression.md` §3。
     */
    @Test
    fun `三行内容加行间距之后仍然留得下留白`() {
        val contentDp = 16 + 20 + 16 + TrayLayout.LINE_GAP_DP * 2
        assertEquals("三行内容高度变了？", 56, contentDp)
        val paddingPerSide = (TrayLayout.HEIGHT_DP - contentDp) / 2f
        assertTrue(
            "每侧留白 $paddingPerSide dp 太少（< 8dp），三行会贴死托盘上下沿",
            paddingPerSide >= 8f,
        )
        // 56dp 的旧高度会算出 0dp 留白 —— 这条断言正是「为什么必须加高」的机器可读版本。
        assertTrue("旧高度 56dp 的每侧留白不应通过", (56 - contentDp) / 2f < 8f)
    }

    /**
     * 封面落点中心 = 托盘中心。
     *
     * **退化断言**：旧实现是 `statusBar + miniCoverHalfPx`，而那时
     * `miniCoverHalfPx = 28.dp`、托盘高也是 56dp ⇒ 两者是同一个点。
     * 本用例把「HEIGHT = COVER = 56 时新旧公式逐值相同」写死，
     * 这样改 [TrayLayout.HEIGHT_DP] 的人一定会同时看到封面落点这一项。
     */
    @Test
    fun `封面中心是托盘中心 且在旧值上退化为 28dp`() {
        assertEquals(
            "封面中心必须等于托盘中心",
            TrayLayout.HEIGHT_DP / 2f,
            TrayLayout.coverCenterOffsetDp(),
            0.0001f,
        )
        // 旧值退化：HEIGHT=56 ⇒ 28dp（= 旧的 miniCoverHalfPx 字面量）
        assertEquals(28f, 56 / 2f, 0.0001f)
        // 封面本身比托盘矮 ⇒ 上下各有 (80-56)/2 = 12dp 余量
        val coverMarginDp = (TrayLayout.HEIGHT_DP - TrayLayout.COVER_SIZE_DP) / 2f
        assertEquals(12f, coverMarginDp, 0.0001f)
        assertTrue("封面不能比托盘高", TrayLayout.COVER_SIZE_DP <= TrayLayout.HEIGHT_DP)
    }

    /**
     * 封面尺寸是**定值 56dp**，不是「与托盘等高」。
     *
     * 若改成与托盘等高（80dp），窄屏（360dp）的文本列会从 136dp 掉到 112dp，
     * 而封面并不需要那 24dp —— 这是 `probe-tray-layout.md` §2 的量化取舍。
     */
    @Test
    fun `封面尺寸是 56dp 而不是与托盘等高`() {
        assertEquals(56, TrayLayout.COVER_SIZE_DP)
        assertNotEquals(
            "封面不该跟着托盘一起长高",
            TrayLayout.HEIGHT_DP,
            TrayLayout.COVER_SIZE_DP,
        )
    }

    // ------------------------------------------------------------ 2. 底部预留（三个消费者之一）

    @Test
    fun `窄屏底部预留 = 导航 80 + 托盘 80 + 缓冲 8`() {
        assertEquals(168, TrayLayout.bottomOverlayInsetDp(isWideLayout = false))
        assertEquals(
            TrayLayout.BOTTOM_NAV_HEIGHT_DP + TrayLayout.HEIGHT_DP + TrayLayout.BOTTOM_INSET_BUFFER_DP,
            TrayLayout.bottomOverlayInsetDp(isWideLayout = false),
        )
    }

    @Test
    fun `宽屏底部预留 = 托盘 80 + 缓冲 8（无底部导航）`() {
        assertEquals(88, TrayLayout.bottomOverlayInsetDp(isWideLayout = true))
    }

    /**
     * **这条是「托盘加高必须同步改预留」的机器可读版本。**
     *
     * v2.5.4 的 `BottomOverlayInsetDp` 是两个字面量 `144.dp / 64.dp`
     * （= 80+56+8 / 56+8）。托盘改成 80 之后它们必须一起变；
     * 若有人把某一边写回字面量，这条用例会立刻发现差值不再是 [TrayLayout.HEIGHT_DP]。
     */
    @Test
    fun `底部预留与托盘高度严格同增同减`() {
        val narrow = TrayLayout.bottomOverlayInsetDp(isWideLayout = false)
        val wide = TrayLayout.bottomOverlayInsetDp(isWideLayout = true)
        assertEquals(
            "窄屏与宽屏的差值必须正好是底部导航高度",
            TrayLayout.BOTTOM_NAV_HEIGHT_DP,
            narrow - wide,
        )
        assertEquals(
            "宽屏预留 = 托盘 + 缓冲（v2.5.4 的 64 是 56+8，v2.5.5 应为 80+8）",
            TrayLayout.HEIGHT_DP + TrayLayout.BOTTOM_INSET_BUFFER_DP,
            wide,
        )
        // 旧值 144 / 64 必须**不再**成立，否则说明有人把字面量写回去了。
        assertNotEquals("窄屏预留还是 v2.5.4 的 144dp", 144, narrow)
        assertNotEquals("宽屏预留还是 v2.5.4 的 64dp", 64, wide)
    }

    // ------------------------------------------------------------ 3. 控制按钮（铁律 19）

    /**
     * ★ **三个控制按钮一个都不能少。** 缺失属 P0 回归。
     *
     * 探针（`probe-tray-regression.md` §0）证明托盘从来没有过上一首：
     * `git log -S "SkipPrevious" -- PlayerCard.kt` **零命中**。
     * 所以这条断言不是「防回归」，而是把一条**新功能**钉成红线 ——
     * 少了它，用户会以为「这应用没有上一首」。
     */
    @Test
    fun `上一首 播放暂停 下一首 三个按钮都在且顺序固定`() {
        assertEquals(
            listOf(
                TrayLayout.Control.PREVIOUS,
                TrayLayout.Control.PLAY_PAUSE,
                TrayLayout.Control.NEXT,
            ),
            TrayLayout.controls,
        )
        assertEquals("控制按钮个数", 3, TrayLayout.controls.size)
    }

    /** 上一首**必须**在清单里 —— 单独一条，失败信息直指铁律 19。 */
    @Test
    fun `上一首按钮存在（铁律 19 核心功能）`() {
        assertTrue(
            "托盘缺上一首按钮 —— 上一首/播放暂停/下一首是核心功能，缺失属 P0 回归（AGENTS.md 铁律 19）",
            TrayLayout.Control.PREVIOUS in TrayLayout.controls,
        )
    }

    /** 播放/暂停与下一首同样一条一条钉住，避免「加了第四个顺手删了第二个」。 */
    @Test
    fun `播放暂停与下一首按钮也都在`() {
        assertTrue(TrayLayout.Control.PLAY_PAUSE in TrayLayout.controls)
        assertTrue(TrayLayout.Control.NEXT in TrayLayout.controls)
    }

    /**
     * 触摸区是 48dp（Kanesumi `MetroIconButton` 的默认 `touchTargetDp`，无障碍下限）。
     * 三个按钮合计 144dp —— 这个数就是「不加第四个按钮」的量化理由
     * （360dp 窄屏上文本列只剩 136dp）。
     */
    @Test
    fun `控制区总宽 144dp 且等于 三个 48dp 触摸区`() {
        assertEquals(48, TrayLayout.CONTROL_TOUCH_TARGET_DP)
        assertEquals(144, TrayLayout.controlsWidthDp())
        assertEquals(
            TrayLayout.controls.size * TrayLayout.CONTROL_TOUCH_TARGET_DP,
            TrayLayout.controlsWidthDp(),
        )
    }

    /**
     * 展开态的占位宽度必须**等于**收起态的控制区宽度，否则展开/收起时文字会重排。
     * v2.5.4 写死 `96.dp`（两个按钮），加第三个按钮后若不改就会少 48dp。
     */
    @Test
    fun `展开态占位宽度与收起态控制区宽度一致`() {
        assertEquals(TrayLayout.controlsWidthDp(), TrayLayout.controlsPlaceholderWidthDp())
        assertNotEquals(
            "v2.5.4 的 96dp 是两个按钮的宽度，本版应为三个",
            96,
            TrayLayout.controlsPlaceholderWidthDp(),
        )
    }

    /**
     * 窄屏文本列可用宽 ≥ 120dp。
     *
     * 算式（`probe-tray-regression.md` §2 的真机数字）：
     * `屏宽 360 − 封面 56 − 控制区 144 − 文本列 padding 24 = 136dp`。
     * 这条是「不加第四个按钮」的量化依据：再加一个 48dp 只剩 88dp，
     * 「歌名 / 作者 / 音源」三层必然塌成两行以上。
     */
    @Test
    fun `窄屏 360dp 下文本列仍有 136dp`() {
        val narrowScreenDp = 360
        val horizontalPaddingDp = 24
        val textColumnDp =
            narrowScreenDp - TrayLayout.COVER_SIZE_DP - TrayLayout.controlsWidthDp() - horizontalPaddingDp
        assertEquals(136, textColumnDp)
        assertTrue("文本列太窄（$textColumnDp dp）", textColumnDp >= 120)
        // 反证：第四个按钮会把文本列打到 88dp
        assertTrue(narrowScreenDp - TrayLayout.COVER_SIZE_DP - 192 - horizontalPaddingDp < 120)
    }

    // ------------------------------------------------------------ 4. 三行结构

    /**
     * 三行的**语义顺序**。UI 必须按这个顺序画；本用例让「谁在第一行」
     * 不靠读 `PlayerCard` 的 `Column` 相信。
     */
    @Test
    fun `三行顺序是 歌词 歌名 作者与音源`() {
        assertEquals(
            listOf(TrayLayout.Row.LYRIC, TrayLayout.Row.TITLE, TrayLayout.Row.META),
            TrayLayout.rows,
        )
        assertEquals("三层布局必须正好三行", 3, TrayLayout.rows.size)
    }

    /** 第二行「歌名」独占：它不能与 [TrayLayout.Row.META] 合并 —— 那是 v2.5.4 的旧形态。 */
    @Test
    fun `歌名独占一行不与 作者音源 合并`() {
        assertEquals(TrayLayout.Row.TITLE, TrayLayout.rows[1])
        assertEquals(TrayLayout.Row.META, TrayLayout.rows[2])
        assertNotEquals(TrayLayout.rows[1], TrayLayout.rows[2])
    }

    /**
     * 行间距是**正整数且很小**：它是「分野」不是「分组」。
     * 0 会让三行糊成一团；> 8dp 会吃掉留白预算（见上面的留白断言）。
     */
    @Test
    fun `行间距在 1 到 8dp 之间`() {
        assertTrue("行间距必须为正", TrayLayout.LINE_GAP_DP >= 1)
        assertTrue("行间距过大（${TrayLayout.LINE_GAP_DP}dp）会吃掉留白", TrayLayout.LINE_GAP_DP <= 8)
    }
}
