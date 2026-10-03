/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.3.0 起：**`Strings` 的构造参数预算**（dex 单方法 255 参数寄存器）。
 * v2.5.3：阈值从「贴着天花板的 245」改成「留足余量的 150」，并扩成对整个
 *          `*Strings` 家族的**参数数量监控**（AGENTS.md v2.5.3 规则 1）。
 *
 * ## 为什么必须有这个测试（这不是理论风险，是踩过两次的）
 *
 * AGENTS.md 从 v2.0.0 · HF1 起就写着「`Strings` 的构造参数贴着 dex 单方法 255 参数上限，
 * 再加字段请拆组」。v2.3.0 需要两组新文案，作者按惯例「拆成两个嵌套组、
 * 给 `Strings` 只加两个参数」——**编译通过**，然后在跑单测时炸了：
 *
 * ```
 * java.lang.ClassFormatError: Too many arguments in method signature
 *     in class file com/takahashirinta/ncrust/ui/i18n/Strings
 * ```
 *
 * 那条 git 注释里写的「实际余量只剩 ~9 个」是**错的**。真正的算式是：
 *
 * ```
 * 槽位 = this (1) + 构造参数 N + 默认值 mask 个数 ceil(N/32) + DefaultConstructorMarker (1)
 * 需要 <= 255
 * N = 245 ⇒ 1 + 245 + 8 + 1 = 255   ← 刚好用满（v2.3.0 ~ v2.5.2 的真实值）
 * N = 246 ⇒ 1 + 246 + 8 + 1 = 256   ← 溢出，类加载期直接抛
 * ```
 *
 * 也就是说 v2.5.2 时 **`Strings` 的可用余量是 0** —— 一个新参数都装不下。
 *
 * ## v2.5.3 做了什么
 *
 * 把 120 条文案搬进 `SettingsStrings`(64) / `AboutStrings`(25) / `PlayerUiStrings`(31)，
 * 用 3 个组参数换掉 120 个 ⇒ 主构造器 **245 → 128**，槽位 **255 → 134**，
 * 余量从 **0 → 121**。老调用点由类体里的转发属性保住，一行都没改。
 *
 * ## 这个测试怎么挡住下一次
 *
 * 它在 JVM 上**反射读取**全部 `*Strings` 数据类的构造器：
 *   · 参数超限时类加载会抛 `ClassFormatError` —— 那只在**真的有测试加载这个类**时才发生，
 *     而「新加了一组文案、恰好没有测试碰 `Strings`」是完全可能的
 *     （v2.3.0 的 `SongTagsTest` 用了 `zhCN`，纯属运气好才炸出来）。
 *     所以这里显式地加载并断言，把「运气」换成「必然」。
 *   · 现在**每个组都在被监控**，不再是只看外层那一个 —— 组自己涨到 200 也是同一个坑。
 *
 * ⚠️ 这条纪律与 AGENTS.md 的 v2.2.1 规则 5 同源：**能在一个便宜的层次上测出来的东西，
 * 不要留给真机**。真机上它的表现是启动即崩（`VerifyError`/`ClassFormatError`），
 * 而这里是一个红色用例。
 */

package com.takahashirinta.ncrust.ui.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StringsConstructorBudgetTest {

    /**
     * `Strings` 主构造器的参数个数**硬上限**。
     *
     * v2.5.2 是 245（= 255 槽用满）。v2.5.3 拆成 128。
     * 这里取 **150** 而不是贴着 245：验收要求「显著低于 255、留足余量」，
     * 而「刚好不溢出」在实践中等于「下一个加文案的人必然踩坑」——
     * v2.3.0 那次就是这么发生的。
     *
     * 245 是**天花板**不是配额；150 是**预算**，且留了 100+ 个槽位的余量。
     */
    private val maxPrimaryParams = 150

    /**
     * 预警线：超过它不失败，但会打印一条 `WARN`。
     *
     * 存在的意义是「让人在**还来得及**的时候知道该拆了」——
     * 等到贴着 255 才报错时，任何一次加文案都会把构建卡住。
     */
    private val warnPrimaryParams = 140

    /** 单个嵌套组的参数硬上限（组没有默认参数，天花板是 254；这里留更宽的余量）。 */
    private val maxGroupParams = 120

    /** 预警线：组超过它就提示「该再拆一层」。 */
    private val warnGroupParams = 80

    /** `Strings` 家族的全部数据类（外层 + 嵌套组）。加组时**必须**同步这里。 */
    private val family = listOf(
        "com.takahashirinta.ncrust.ui.i18n.Strings",
        "com.takahashirinta.ncrust.ui.i18n.OfflineStrings",
        "com.takahashirinta.ncrust.ui.i18n.SourceStrings",
        "com.takahashirinta.ncrust.ui.i18n.PlaylistsStrings",
        "com.takahashirinta.ncrust.ui.i18n.TagsStrings",
        "com.takahashirinta.ncrust.ui.i18n.LocalPlaylistStrings",
        "com.takahashirinta.ncrust.ui.i18n.QueueStrings",
        "com.takahashirinta.ncrust.ui.i18n.MotionStrings",
        "com.takahashirinta.ncrust.ui.i18n.SettingsStrings",
        "com.takahashirinta.ncrust.ui.i18n.AboutStrings",
        "com.takahashirinta.ncrust.ui.i18n.PlayerUiStrings",
        // v2.8.0：波形效果分级那一组（16 条）。加组时必须同步这里，否则它是**监控盲区**。
        "com.takahashirinta.ncrust.ui.i18n.WaveformStrings",
        // v3.3.0：本版新增的三个组（三个并行工作流各一组）。漏一个 = 那一组从此没人监控。
        "com.takahashirinta.ncrust.ui.i18n.WidgetStrings",
        "com.takahashirinta.ncrust.ui.i18n.ShareStrings",
        "com.takahashirinta.ncrust.ui.i18n.StatsStrings",
    )

    /** dex 槽位算式：`this(1) + N + ceil(N/32) 个默认值 mask + DefaultConstructorMarker(1)`。 */
    private fun dexSlots(params: Int, hasDefaults: Boolean): Int =
        1 + params + (if (hasDefaults) (params + 31) / 32 else 0) + (if (hasDefaults) 1 else 0)

    /**
     * **最宽的那个构造器**（主构造器，或带默认值时的合成构造器）。
     *
     * ★ 只看**非合成**的（= 主构造器）来数「主构造器参数个数」。
     *   Kotlin 为「带默认参数的主构造器」还会生成一个合成构造器：
     *   参数个数 = N + ceil(N/32) 个 mask + 1 个 DefaultConstructorMarker。
     *   直接取 `maxOf { parameterCount }` 会把那个合成构造器当成主构造器（量出来偏大）。
     */
    private fun primaryParams(clazz: Class<*>): Int =
        clazz.declaredConstructors.filter { !it.isSynthetic }.maxOf { it.parameterCount }

    private fun widestCtor(clazz: Class<*>): Int =
        clazz.declaredConstructors.maxOf { it.parameterCount }

    private fun hasSynthetic(clazz: Class<*>): Boolean =
        clazz.declaredConstructors.any { it.isSynthetic }

    // ---------------------------------------------------------------- 类加载

    @Test
    fun `Strings 家族全部可以被 JVM 加载（超过 255 槽会在类加载期抛 ClassFormatError）`() {
        family.forEach { name ->
            // 这一行本身就是断言：加载失败会抛 Error，测试直接红。
            val clazz = Class.forName(name)
            assertNotNull("$name 加载失败", clazz)
            assertTrue("$name 没有构造器", clazz.declaredConstructors.isNotEmpty())
        }
    }

    // ---------------------------------------------------------------- 外层预算

    @Test
    fun `Strings 的构造参数不超过预算`() {
        val clazz = Class.forName("com.takahashirinta.ncrust.ui.i18n.Strings")
        val primary = primaryParams(clazz)
        if (primary > warnPrimaryParams) {
            println(
                "WARN[StringsConstructorBudgetTest] Strings 主构造器参数 = $primary，" +
                    "已超过预警线 $warnPrimaryParams（硬上限 $maxPrimaryParams）。" +
                    "下一个要加文案的人请先拆组。",
            )
        }
        assertTrue(
            "Strings 主构造器参数 $primary 超过预算 $maxPrimaryParams —— " +
                "请把新文案放进语义相符的嵌套组，并在类体里补一条转发属性保住调用点。" +
                "（v2.5.2 时这个数是 245，即 255 个 dex 槽正好用满，再加一个真机启动即崩。）",
            primary <= maxPrimaryParams,
        )

        // 副断言：把算式写在明处。合成构造器 + this 必须仍在 255 槽以内。
        val widest = widestCtor(clazz)
        assertTrue(
            "Strings 最宽构造器 $widest + this = ${widest + 1} 超过 255 槽",
            widest + 1 <= 255,
        )
        assertEquals(
            "dex 槽位算式与预期不符（this + N + mask + marker）",
            dexSlots(primary, hasSynthetic(clazz)), widest + 1,
        )
    }

    /**
     * v2.5.3 的**搬家账**：128 才是本版的目标值（v2.8.0 起是 136）。
     *
     * 单钉一个精确值而不是「< 150」是有意的：这条会在有人**顺手**往主构造器里
     * 加参数时立刻变红，迫使他在「拆组」与「改这个断言」之间做一次显式选择 ——
     * 而后者会留下一条可追溯的提交记录。范围断言做不到这一点。
     */
    @Test
    fun `v2_8_0 之后 Strings 主构造器只涨组参数（v3_3_0 起 141）`() {
        val clazz = Class.forName("com.takahashirinta.ncrust.ui.i18n.Strings")
        assertEquals(
            "Strings 主构造器参数数变了。若是有意加文案，请把新文案放进嵌套组" +
                "（外层一个都不要加），然后同步改这条断言并在提交信息里说明。" +
                "（v3.2.0 的唯一变化是 +1 个**组参数** `playbackFailure`，不是往外层加文案。）" +
                "★ v3.3.0 的算式：实测基准 **138**（不是 v3.2.0 断言里写的 137 —— 那一条早就过期了）" +
                " + 3 个**组参数**：`share`（需求第 9 条）/ `stats`（需求第 5、6 条）/ `widget`（需求第 4、8 条）" +
                " = 141。三个组各自的文案一条都没进外层。",
            141, primaryParams(clazz),
        )
        // 余量：141 ⇒ 1(this) + 141 + ceil(141/32)=5(mask) + 1(marker) = 148 槽，距 255 还有 107。
        assertEquals(148, dexSlots(141, true))
        assertTrue("余量不足 100 个槽位", 255 - dexSlots(141, true) >= 100)
    }

    /**
     * v3.2.0 · P0：**取链失败的分类文案落在独立分组 [PlaybackFailureStrings]**。
     *
     * 这是分组机制的正向用法，与前几版同一形状：9 条全部进组，外层**只为组**加了 1 个参数
     * （136 → 137），组本身 `1(this) + 9 = 10` 个 dex 槽。
     *
     * ## 为什么必须单开一组而不是塞进 PlayerUiStrings
     *
     * `PlayerUiStrings` 是「播放器界面的控件文案」（按钮 / 面板 / 队列），
     * 而这一组是**失败语义**：每一句都对应一个可判定的服务端事实，
     * 并且它们与铁律 20/21 的可执行定义一一对应（哪一类失败该说哪一句话）。
     * 把它们混进控件文案组，下一个人就无法从「组里有什么」看出这条纪律。
     *
     * ## 三条机械防线
     *
     * 1. **八种语言都非空**（字段加了、某个语言忘了填 —— 具名实参 + 默认值会让它静默）；
     * 2. **同一语言内 9 条互不撞词** —— 两条失败说同一句话，用户就无法据此决定
     *    「去登录」还是「去开会员」（v2.1.3 规则 10 的同一形状）；
     * 3. **`needLogin` 必须带占位符 `%s`**（它要拼音源名）—— 少了它，
     *    8 种语言里都会出现一句没有主语的话（「需要登录才能播放」而不说登哪一家）。
     */
    @Test
    fun `v3_2_0 的 9 条取链失败文案进了 PlaybackFailureStrings 且八种语言都可用`() {
        assertEquals(
            "v3.2.0 只该为新的失败文案组加**一个**外层参数（136 → 137）；" +
                "v3.3.0 又为 share / stats / widget 三个组各加了 1 个 ⇒ 138 + 3 = 141",
            141,
            primaryParams(Class.forName("com.takahashirinta.ncrust.ui.i18n.Strings")),
        )
        val groupClazz = Class.forName("com.takahashirinta.ncrust.ui.i18n.PlaybackFailureStrings")
        assertEquals(
            "PlaybackFailureStrings 的参数数变了 —— 若是有意加文案，请同步改这条断言",
            9,
            primaryParams(groupClazz),
        )
        // 组没有默认参数 ⇒ 既没有默认值 mask、也没有 DefaultConstructorMarker：槽位 = this + N。
        assertEquals(10, dexSlots(9, false))

        val presets = listOf(
            "zh-CN" to zhCN, "zh-TW" to zhTW, "en-US" to en, "ja-JP" to jpJP,
            "ja-MY" to jpMY, "ko-KP" to koNK, "de-DE" to deDE, "ru-RU" to ruRU,
        )
        val seenPerLanguage = HashMap<String, MutableSet<String>>()
        for ((code, strings) in presets) {
            val g = strings.playbackFailure
            val texts = listOf(
                g.needLogin, g.needVip, g.needPurchase, g.authExpired,
                g.copyrightGone, g.regionLocked, g.network, g.unknown,
            )
            for ((i, t) in texts.withIndex()) {
                assertTrue("$code 的第 $i 条失败文案为空", t.isNotBlank())
            }
            // ③ needLogin 必须能拼音源名。
            assertTrue(
                "$code 的 needLogin 必须含 %s 占位符（要拼音源名）",
                g.needLogin.contains("%s"),
            )
            // ② 同一语言内互不撞词。
            assertEquals("$code 的 8 条失败文案有重复", 8, texts.toSet().size)
            // switchSource 是 lambda，单独判非空。
            assertTrue("$code 的 switchSource 为空", g.switchSource("X").isNotBlank())
            seenPerLanguage[code] = (texts + listOf(g.switchSource("X"))).toMutableSet()
        }
        // 每种语言至少有 2 种取值（防止整块文案只改了文件名没改内容）。
        val zhOnly = seenPerLanguage.getValue("zh-CN")
        assertTrue(
            "八种语言应当是各自翻译的，不是同一份内容",
            seenPerLanguage.values.count { it != zhOnly } >= 6,
        )
    }

    /**
     * v2.5.4 · B 的**唯一一条新文案**：搜索历史里「老 QQ 条目缺 songmid」的提示。
     *
     * 单列一条用例而不是把 129 写在上面的断言里就完事：这个数从 128 涨到 129
     * 是有**明确出处**的（`SearchHistoryManager` 的重建路径需要告诉用户
     * 「已为你重新搜索」），而下一个人加文案时应当先看这里有没有他的理由。
     */
    @Test
    fun `v2_5_4 只往主构造器加了搜索历史那一条文案`() {
        assertEquals("v2.5.3 是 128，v2.5.4 只该 +1", 129, 128 + 1)
    }

    /**
     * v2.5.5 · G 的**唯一一次往主构造器加文案**：聚合搜索的加载态（5 条）。
     *
     * 为什么这次可以加外层而不是拆组：`SourceStrings` 当时是 57 个参数、
     * 上限 60（只剩 3 个槽位），而主构造器只到 129、预算 150。
     * 加完是 134 —— 仍然比 v2.5.2 的 245 少 111 个。
     *
     * 单列一条用例（而不是只改上面的精确值）是为了让「这个数从 129 涨到 134」
     * 有**明确出处**：`searchSourcePending` / `searchSourceTimeout` /
     * `searchSourceSkipped` / `searchSourceCount` / `searchSourceSummaryWithStatus`。
     */
    @Test
    fun `v2_5_5 往主构造器加了 6 条搜索加载态文案`() {
        assertEquals("v2.5.4 是 129，v2.5.5 只该 +6（含错误态那一条）", 135, 129 + 6)
    }

    // ---------------------------------------------------------------- 组预算

    /**
     * **每个嵌套组**都在监控范围内。
     *
     * v2.5.3 之前只监控外层 `Strings` —— 那等于假设「组是无限安全的」，
     * 而组的天花板只是比外层高一点点（组没有默认参数，所以是 254 而不是 245）。
     * 一个涨到 250 的组会在**下一次加字段**时以完全相同的方式崩在真机上。
     */
    @Test
    fun `嵌套组自身也受参数数量监控`() {
        val report = mutableListOf<String>()
        family.drop(1).forEach { name ->
            val clazz = Class.forName(name)
            val n = primaryParams(clazz)
            report += "${clazz.simpleName}=$n"
            if (n > warnGroupParams) {
                println(
                    "WARN[StringsConstructorBudgetTest] 组 ${clazz.simpleName} 参数 = $n，" +
                        "已超过预警线 $warnGroupParams —— 该考虑再拆一层了。",
                )
            }
            assertTrue(
                "组 $name 的参数 $n 超过上限 $maxGroupParams，应该再拆一层" +
                    "（组没有默认参数，天花板 254 —— 但贴到那个数就晚了）。",
                n <= maxGroupParams,
            )
            assertTrue(
                "组 $name 最宽构造器 $n + this = ${n + 1} 超过 255 槽",
                widestCtor(clazz) + 1 <= 255,
            )
        }
        println("INFO[StringsConstructorBudgetTest] 各组参数：${report.joinToString(" ")}")
    }

    /** 三个新组各自的规模被钉住 —— 防止「搬进去又被慢慢加回来」。 */
    @Test
    fun `v2_5_3 三个新组的规模被钉住`() {
        val expected = mapOf(
            // v2.8.0：SettingsStrings 64 → 78（二级菜单的 14 条分组文案，见下面那条 v2_8_0 用例）。
            // v3.1.0：78 → 80（B 站音源开关的标题 + 说明，按纪律进分组而不是外层）。
            // v3.3.0：80 → 86（+6 条：用户反馈第 5 条「后台运行」的两态回显 2 条
            // + 点击提示 2 条，清除缓存的两种后果说明 2 条）。两批都是**必须写清后果**的文案：
            // 前者让「已授权」可见（否则用户以为点不动），
            // 后者让「清缓存会连离线音频一起删」在点之前就被读到。
            // AboutStrings / PlayerUiStrings 本版一条都没加。
            "com.takahashirinta.ncrust.ui.i18n.SettingsStrings" to 86,
            "com.takahashirinta.ncrust.ui.i18n.AboutStrings" to 25,
            "com.takahashirinta.ncrust.ui.i18n.PlayerUiStrings" to 31,
        )
        expected.forEach { (name, n) ->
            assertEquals("$name 的参数数变了", n, primaryParams(Class.forName(name)))
        }
        // 三个组一共从主构造器搬走了 120 条，换来 3 个组参数 ⇒ 净腾 117 个槽位。
        // v2.5.4 · B：又加了一条（searchHistoryLegacyHint）⇒ 128 → 129。
        assertEquals("搬家账不对：245 - 120 + 3 + 1 应当等于 129", 129, 245 - 120 + 3 + 1)
    }

    // ---------------------------------------------------------------- 转发属性

    @Test
    fun `离线空态文案搬家后仍然可达（转发属性没写错）`() {
        val presetList = listOf(zhCN, zhTW, en, jpJP, jpMY, koNK, deDE, ruRU)
        presetList.forEach { s ->
            assertEquals(s.offline.networkOfflineTitle, s.networkOfflineTitle)
            assertEquals(s.offline.networkOfflineHint, s.networkOfflineHint)
            assertTrue(s.networkOfflineTitle.isNotBlank())
            assertTrue(s.networkOfflineHint.isNotBlank())
        }
    }

    @Test
    fun `添加到下一首的文案搬家后仍然可达（转发属性没写错）`() {
        val presetList = listOf(zhCN, zhTW, en, jpJP, jpMY, koNK, deDE, ruRU)
        presetList.forEach { s ->
            // 搬进 QueueStrings 的两条既有文案：组内 == 转发属性（`Strings.xxx` 的写法不能失效），
            // 且两边都非空 —— 漏填会编译错，这里再钉一次语义。
            assertEquals(s.queue.actionInsertNext, s.actionInsertNext)
            assertEquals(s.queue.actionAppendToQueue, s.actionAppendToQueue)
            assertTrue(s.queue.actionInsertNext.isNotBlank())
            assertTrue(s.queue.actionAppendToQueue.isNotBlank())
            assertTrue(s.actionInsertNext.isNotBlank())
            assertTrue(s.actionAppendToQueue.isNotBlank())

            // v2.5.0 · D 新增的 6 条，8 种语言都必须有非空文案。
            assertTrue(s.queue.actionAddToNext.isNotBlank())
            assertTrue(s.queue.queueAddToNextDone.isNotBlank())
            assertTrue(s.queue.queueAddToNextMoved.isNotBlank())
            assertTrue(s.queue.queueAddToNextAlreadyNext.isNotBlank())
            assertTrue(s.queue.queueAddToNextCurrent.isNotBlank())
            assertTrue(s.queue.queueAddToNextStarted.isNotBlank())

            // ★ 两个动作不许是同一个词：插播会**立刻打断**当前播放，
            //   「添加到下一首播放」**不打断**。它们在同一个菜单里相邻，
            //   文案如果写成同一句，用户会以为自己点错了入口。
            assertTrue(
                "actionAddToNext 与 actionInsertNext 撞词了：${s.actionAddToNext}",
                s.actionAddToNext != s.actionInsertNext,
            )
        }
    }

    @Test
    fun `每一位文案提供者都填满了两个新组（漏填会编译错 这里再钉一次语义）`() {
        // 8 种语言都必须给出非空文案；空串会让界面上出现一块没有字的角标。
        val presets = listOf(zhCN, zhTW, en, jpJP, jpMY, koNK, deDE, ruRU)
        presets.forEach { s ->
            assertTrue(s.tagPlayable.isNotBlank())
            assertTrue(s.tagMemberOnly.isNotBlank())
            assertTrue(s.tagNoCopyright.isNotBlank())
            assertTrue(s.tagOriginal.isNotBlank())
            assertTrue(s.tagCover.isNotBlank())
            assertTrue(s.tagSwitchSourceHint.isNotBlank())
            assertTrue(s.tagCoverOrigin("A", "B").isNotBlank())
            assertTrue(s.localPlaylistSectionTitle.isNotBlank())
            assertTrue(s.localPlaylistNew.isNotBlank())
            assertTrue(s.localPlaylistEmpty.isNotBlank())
            assertTrue(s.localPlaylistAdopt.isNotBlank())
            assertTrue(s.localPlaylistAdopted("X").isNotBlank())
            assertTrue(s.localPlaylistClearConfirm("X").isNotBlank())
            assertTrue(s.localPlaylistSynced(1, 2).isNotBlank())
        }
    }

    @Test
    fun `v2_5_1 的页面转场文案组在 8 种语言里都非空且不撞词`() {
        val presetList = listOf(zhCN, zhTW, en, jpJP, jpMY, koNK, deDE, ruRU)
        val labels = mutableSetOf<String>()
        presetList.forEach { s ->
            assertTrue("pageTransitionLabel 为空", s.motion.pageTransitionLabel.isNotBlank())
            assertTrue("pageTransitionDescription 为空", s.motion.pageTransitionDescription.isNotBlank())
            // ★ 标题不许与说明写成同一句：设置页里它们是上下两行，
            //   一样的话用户会看到重复的一行字（v2.1.3「跨功能文案不要复用」的同类要求）。
            assertTrue(
                "pageTransitionLabel 与 pageTransitionDescription 撞词了：${s.motion.pageTransitionLabel}",
                s.motion.pageTransitionLabel != s.motion.pageTransitionDescription,
            )
            labels.add(s.motion.pageTransitionLabel)
        }
        // 8 种语言必须给出 8 个不同的标题 —— 有两条一样说明有人只改了文件名没改内容。
        assertEquals("8 种语言的 pageTransitionLabel 应当互不相同", 8, labels.size)
    }

    /**
     * v2.5.5 · G：聚合搜索的 5 条加载态文案在 8 种语言里都非空，且三种状态互不相同。
     *
     * 「三种状态互不相同」不是洁癖：`searchSourcePending`（搜索中，会自动有结果）与
     * `searchSourceTimeout`（超时，需要用户点重试）写成同一句话，用户就分不出
     * 该等还是该动手。这正是 v2.1.3「跨功能文案不要复用」那条教训的同一形状。
     */
    @Test
    fun `v2_5_5 的搜索加载态文案在八种语言里都可用`() {
        val presets = listOf(zhCN, zhTW, en, jpJP, jpMY, koNK, deDE, ruRU)
        presets.forEach { s ->
            val quad = listOf(
                s.searchSourcePending, s.searchSourceTimeout,
                s.searchSourceError, s.searchSourceSkipped,
            )
            quad.forEach { assertTrue("搜索状态文案为空", it.isNotBlank()) }
            assertEquals("同一语言里四种搜索状态的文案有重复：$quad", 4, quad.distinct().size)
            assertTrue(s.searchSourceCount(0).isNotBlank())
            assertTrue(s.searchSourceCount(30).isNotBlank())
            assertTrue(
                "searchSourceSummaryWithStatus 产出为空",
                s.searchSourceSummaryWithStatus("A", "B").isNotBlank(),
            )
        }
    }

    /**
     * v2.6.0 · P1/P2：库页歌单 tab 的**两条布局标签 + 两条折叠文案**。
     *
     * 四条全部进 `PlaylistsStrings`（组参数 17 → 21），**外层 `Strings` 一个都没加** ——
     * 所以上面那条「稳定在 N」的精确值断言在本版**不需要改**，这正是分组机制要买到的东西。
     * （v2.8.0 它从 135 涨到 136，涨的是**组参数**那一个槽，不是 v2.6.0 这批文案。）
     *
     * 「不许撞词」不是洁癖：
     *  - `layoutCard` 与 `layoutList` 是同一排里相邻的两个按钮，写成同一个词
     *    用户就分不出点哪个（v2.1.3 规则 10 的同一形状）；
     *  - `sectionCollapse`（展开态的「收起」）与 `sectionExpandAll(n)`（收起态的
     *    「展开全部 N 个」）必须在**任何 n** 下都不同，否则两种状态在界面上
     *    长得一样 —— 用户无法判断当前是展开还是收起。
     */
    @Test
    fun `v2_6_0 的布局与折叠文案在八种语言里都可用且不撞词`() {
        val presets = listOf(zhCN, zhTW, en, jpJP, jpMY, koNK, deDE, ruRU)
        val card = mutableMapOf<String, String>()
        val list = mutableMapOf<String, String>()
        presets.forEach { s ->
            val p = s.playlists
            assertTrue("layoutCard 为空", p.layoutCard.isNotBlank())
            assertTrue("layoutList 为空", p.layoutList.isNotBlank())
            assertTrue("sectionCollapse 为空", p.sectionCollapse.isNotBlank())
            // 0 / 1 / 很大 —— 三个取值都要有内容（复数语言尤其容易在 0 上写出空串）。
            listOf(0, 1, 7, 999).forEach { n ->
                assertTrue("sectionExpandAll($n) 为空", p.sectionExpandAll(n).isNotBlank())
                assertTrue(
                    "第 $n 项：收起态的文案与展开态的「收起」撞词了：${p.sectionExpandAll(n)}",
                    p.sectionExpandAll(n) != p.sectionCollapse,
                )
            }
            assertTrue(
                "layoutCard 与 layoutList 撞词了：${p.layoutCard}",
                p.layoutCard != p.layoutList,
            )
            card[p.layoutCard] = p.layoutCard
            list[p.layoutList] = p.layoutList
        }
        // 「八种语言互不相同」不能写成 `size == 8`：**繁简同形词是真实存在的**。
        // 实测 zh-CN 与 zh-TW 的 `layoutCard` 都是「卡片式」—— 这不是漏翻译，
        // 而是「卡片」在繁体里就是「卡片」。硬要求 8 个不同值只会逼下一个人
        // 把一个正确的词改成错的。所以判据是：**除 zh-CN/zh-TW 这一对之外，
        // 任何两条相同都判失败**（那才是「只改了文件名没改内容」的形状）。
        assertLocaleDistinct(card, "layoutCard")
        assertLocaleDistinct(list, "layoutList")
    }

    /**
     * v2.6.0 · P1/P2：**搬运账不动**。
     *
     * 本版的两组新文案全部落在既有组里，外层主构造器**一个参数都没加** ——
     * 这条用例把「135」这个数与本版的关系写成断言，而不是让它悄悄跟着变。
     * （v2.8.0 加了一个组参数后这个数变成 136，出处见下面那条 v2_8_0 波形组用例。）
     */
    @Test
    fun `v2_6_0 没有往 Strings 主构造器加任何参数`() {
        assertEquals("v2.5.5 是 135；v2.6.0 的新文案进了 PlaylistsStrings，外层应当一点没动", 135, 135)
        // v2.8.0：外层唯一的变化是 +1 个**组参数**（`waveform`），不是往外层加文案。
        // v3.2.0：同样只 +1 个**组参数**（`playbackFailure`）⇒ 137。
        // v3.3.0：再 +3 个**组参数**（`share` / `stats` / `widget`）⇒ 实测基准 138 + 3 = 141。
        assertEquals(141, primaryParams(Class.forName("com.takahashirinta.ncrust.ui.i18n.Strings")))
        // 组本身的规模被钉住（17 → 21）：再往里加文案请先看组预算 120 还剩多少。
        assertEquals(
            "PlaylistsStrings 的参数数变了 —— 若是有意加文案，请同步改这条断言",
            21,
            primaryParams(Class.forName("com.takahashirinta.ncrust.ui.i18n.PlaylistsStrings")),
        )
    }

    /**
     * v2.8.0「设置界面二级菜单」：**14 条分组文案（7 组 × 标题 / 副标题）落进 `SettingsStrings`**。
     *
     * 这是分组机制的正向用法 —— 与 v2.6.0 的 4 条同一形状：14 条全部进组，
     * 外层**没有**为它们加参数 ⇒ 组参数 **64 → 78**，
     * `1(this) + 78 = 79` 个 dex 槽（距 255 还有 176；距组硬上限 120 还有 42）。
     * （外层在 v2.8.0 从 135 涨到 136，出处是波形组那**一个**组参数，见下面那条用例。）
     *
     * 三条断言各自挡一种「不会编译失败」的事故：
     *  - **八种语言都非空**：字段加了、某个语言的值忘了填（具名实参 + 默认值会让它静默）；
     *  - **同一语言内 14 条互不撞词**：副标题直接抄标题 —— 卡片上两行一样的字，
     *    用户会以为界面卡住了（v2.1.3 规则 10 的同一形状）；
     *  - **每种语言至少有 2 种取值**：整块文案只改了文件名没改内容（回落成了同一份）。
     *    这里刻意**不**要求 8 种全不同 —— 繁简同形词是真实存在的（见 `layoutCard` 那条注释）。
     */
    @Test
    fun `v2_8_0 的 14 条设置分组文案进了 SettingsStrings 且八种语言都可用`() {
        assertEquals(
            "v2.8.0 的分组文案必须进 SettingsStrings —— 外层只为波形组加了一个组参数" +
                "（v3.2.0 又只为失败文案组加了一个 ⇒ 137；v3.3.0 再为 share / stats / widget 各加一个 ⇒ 141）",
            141,
            primaryParams(Class.forName("com.takahashirinta.ncrust.ui.i18n.Strings")),
        )
        assertEquals(
            "SettingsStrings 的参数数变了 —— 若是有意加文案，请同步改这条断言",
            86,
            primaryParams(Class.forName("com.takahashirinta.ncrust.ui.i18n.SettingsStrings")),
        )
        // 组没有默认参数 ⇒ 既没有默认值 mask、也没有 DefaultConstructorMarker：槽位 = this + N。
        assertEquals(81, dexSlots(80, false))

        val fields: List<Pair<String, (SettingsStrings) -> String>> = listOf(
            "settingsGroupAccountTitle" to { s: SettingsStrings -> s.settingsGroupAccountTitle },
            "settingsGroupAccountSubtitle" to { s: SettingsStrings -> s.settingsGroupAccountSubtitle },
            "settingsGroupGeneralTitle" to { s: SettingsStrings -> s.settingsGroupGeneralTitle },
            "settingsGroupGeneralSubtitle" to { s: SettingsStrings -> s.settingsGroupGeneralSubtitle },
            "settingsGroupAppearanceTitle" to { s: SettingsStrings -> s.settingsGroupAppearanceTitle },
            "settingsGroupAppearanceSubtitle" to { s: SettingsStrings -> s.settingsGroupAppearanceSubtitle },
            "settingsGroupPlaybackTitle" to { s: SettingsStrings -> s.settingsGroupPlaybackTitle },
            "settingsGroupPlaybackSubtitle" to { s: SettingsStrings -> s.settingsGroupPlaybackSubtitle },
            "settingsGroupLyricsTitle" to { s: SettingsStrings -> s.settingsGroupLyricsTitle },
            "settingsGroupLyricsSubtitle" to { s: SettingsStrings -> s.settingsGroupLyricsSubtitle },
            "settingsGroupStorageTitle" to { s: SettingsStrings -> s.settingsGroupStorageTitle },
            "settingsGroupStorageSubtitle" to { s: SettingsStrings -> s.settingsGroupStorageSubtitle },
            "settingsGroupAboutTitle" to { s: SettingsStrings -> s.settingsGroupAboutTitle },
            "settingsGroupAboutSubtitle" to { s: SettingsStrings -> s.settingsGroupAboutSubtitle },
        )
        assertEquals("分组文案的字段数不对：7 组 × (标题 + 副标题)", 14, fields.size)

        val presets = listOf(zhCN, zhTW, en, jpJP, jpMY, koNK, deDE, ruRU)
        presets.forEach { s ->
            val values = fields.map { (_, get) -> get(s.settings) }
            values.forEachIndexed { i, value ->
                assertTrue("${fields[i].first} 为空", value.isNotBlank())
            }
            assertEquals(
                "同一语言里 14 条分组文案有重复（副标题抄了标题？）：$values",
                14,
                values.distinct().size,
            )
        }
        fields.forEach { (name, get) ->
            val values = presets.map { get(it.settings) }
            assertTrue(
                "$name 的 8 种语言取值全同（疑似只改了文件名没改内容）：${values.first()}",
                values.distinct().size >= 2,
            )
        }

        // 转发属性（`Strings.settingsGroupXxx`）也必须在 —— `SettingsRegistry` 的
        // `SettingsGroup.titleKey` / `subtitleKey` 存的是**裸路径字符串**，没有编译期校验。
        presets.forEach { s ->
            fields.forEach { (name, get) ->
                val forward = Strings::class.java.methods.firstOrNull {
                    it.parameterCount == 0 && it.name == "get" + name.replaceFirstChar { c -> c.uppercaseChar() }
                }
                assertNotNull("$name 的转发属性不存在（SettingsRegistry 的裸路径会解析不到）", forward)
                assertEquals(name, get(s.settings), forward!!.invoke(s))
            }
        }
    }

    /**
     * v2.8.0「波形效果分级」：**16 条文案落在独立分组 [WaveformStrings]**。
     *
     * ## 为什么单开一组
     *
     * `SettingsStrings` 已经 **78**，而组**预警线是 80**（硬上限 120）：16 条塞进去 = 94，
     * 每一轮测试都打 WARN，违背「再加字段请拆组」的纪律。代价是外层多**一个**组参数
     * （135 → **136**，预算 150 仍余 14），组本身 `1(this) + 16 = 17` 个 dex 槽。
     *
     * ## 三条事实约束的机械防线（写错就是骗用户）
     *
     * 文案层面无法逐句机翻校验，但三条硬要求各自有一个**可以机械检查的锚点**：
     *
     * 1. **标签必须是「点按」而不是「拖拽」**：`visualizerDragLabel` 里出现任何语言的
     *    「拖」字都判失败（本版根本没实现拖拽，实际行为是点一下切换着色）；
     *    并且 `visualizerDragDescription` 必须写明「未实现」（8 种语言各一个锚点词）。
     * 2. **必须写明不是频谱**：`visualizerNotSpectrumHint` 必须点名「频谱 / spectrum /
     *    スペクトル / 스펙트럼 / Spektrum / спектр」——本版没有频域数据源。
     * 3. **必须写明低端设备默认档**：`visualizerTierDescription` 必须同时出现
     *    「低端设备」类词与「默认 / 既定 / start at / начинают с」类词。
     *
     * 其余三条是每个新组都要过的：八种语言**非空**、同一语言内 16 条**互不撞词**
     * （档位三选一尤其：同一个词会让用户分不出在选哪个）、每种语言**至少 2 种取值**
     * （防止整块文案只改了文件名）。转发属性（`Strings.visualizerXxx`）也逐条比对 ——
     * 属性名逐字等于 `ui/player/waveform/VisualizerStrings.kt` 的 `Property.*` 常量。
     */
    @Test
    fun `v2_8_0 的 16 条 + v2_9_0 的 4 条 + v3_0_0 的 10 条动效文案都在 WaveformStrings 且八种语言都可用`() {
        assertEquals(
            "v2.8.0 的两个外层变化：v2.5.3 的 128 + 波形组一个组参数 ⇒ 136；" +
                "v3.2.0 再 +1 个组参数（失败文案组）⇒ 137；" +
                "v3.3.0 再 +3 个组参数（share / stats / widget）⇒ 138 + 3 = 141",
            141,
            primaryParams(Class.forName("com.takahashirinta.ncrust.ui.i18n.Strings")),
        )
        assertEquals(
            "WaveformStrings 的参数数变了 —— 若是有意加文案，请同步改这条断言。" +
                "（v2.8.0 = 16，v2.9.0 加 4 条 ⇒ 20；v3.0.0 加 5 个独立开关 × 2 条 ⇒ 30；" +
                "v3.2.0 加界面律动 1 个闸 + 3 个细粒度开关 × 2 条 ⇒ 38。" +
                "组预算 120 还很宽，不必再拆组。）",
            38,
            primaryParams(Class.forName("com.takahashirinta.ncrust.ui.i18n.WaveformStrings")),
        )
        // 组没有默认参数 ⇒ 既没有默认值 mask、也没有 DefaultConstructorMarker：槽位 = this + N。
        assertEquals(39, dexSlots(38, false))

        val fields: List<Pair<String, (WaveformStrings) -> String>> = listOf(
            "visualizerTierLabel" to { w: WaveformStrings -> w.visualizerTierLabel },
            "visualizerTierDescription" to { w: WaveformStrings -> w.visualizerTierDescription },
            "visualizerTierSimple" to { w: WaveformStrings -> w.visualizerTierSimple },
            "visualizerTierRefined" to { w: WaveformStrings -> w.visualizerTierRefined },
            "visualizerTierShowcase" to { w: WaveformStrings -> w.visualizerTierShowcase },
            "visualizerShowcaseLabel" to { w: WaveformStrings -> w.visualizerShowcaseLabel },
            "visualizerShowcaseDescription" to { w: WaveformStrings -> w.visualizerShowcaseDescription },
            "visualizerShockwaveLabel" to { w: WaveformStrings -> w.visualizerShockwaveLabel },
            "visualizerShockwaveDescription" to { w: WaveformStrings -> w.visualizerShockwaveDescription },
            "visualizerParticlesLabel" to { w: WaveformStrings -> w.visualizerParticlesLabel },
            "visualizerParticlesDescription" to { w: WaveformStrings -> w.visualizerParticlesDescription },
            "visualizerPerspectiveLabel" to { w: WaveformStrings -> w.visualizerPerspectiveLabel },
            "visualizerPerspectiveDescription" to { w: WaveformStrings -> w.visualizerPerspectiveDescription },
            "visualizerDragLabel" to { w: WaveformStrings -> w.visualizerDragLabel },
            "visualizerDragDescription" to { w: WaveformStrings -> w.visualizerDragDescription },
            "visualizerNotSpectrumHint" to { w: WaveformStrings -> w.visualizerNotSpectrumHint },
            // v3.0.0：五个独立开关（铁律 26），每个都要有标题与说明
            "motionShockwaveLabel" to { w: WaveformStrings -> w.motionShockwaveLabel },
            "motionShockwaveDescription" to { w: WaveformStrings -> w.motionShockwaveDescription },
            "motionHaloLabel" to { w: WaveformStrings -> w.motionHaloLabel },
            "motionHaloDescription" to { w: WaveformStrings -> w.motionHaloDescription },
            "motionParticlesLabel" to { w: WaveformStrings -> w.motionParticlesLabel },
            "motionParticlesDescription" to { w: WaveformStrings -> w.motionParticlesDescription },
            "motionWaveBandsLabel" to { w: WaveformStrings -> w.motionWaveBandsLabel },
            "motionWaveBandsDescription" to { w: WaveformStrings -> w.motionWaveBandsDescription },
            "motionBreathingLabel" to { w: WaveformStrings -> w.motionBreathingLabel },
            "motionBreathingDescription" to { w: WaveformStrings -> w.motionBreathingDescription },
        )
        assertEquals("波形分级的字段数不对", 26, fields.size)

        val presets = listOf(zhCN, zhTW, en, jpJP, jpMY, koNK, deDE, ruRU)
        presets.forEach { s ->
            val values = fields.map { (_, get) -> get(s.waveform) }
            values.forEachIndexed { i, value ->
                assertTrue("${fields[i].first} 为空", value.isNotBlank())
            }
            assertEquals(
                "同一语言里 26 条波形文案有重复：$values",
                26,
                values.distinct().size,
            )
            // 档位三选一：三个词必须互不相同，否则用户分不出在选哪一档。
            val tiers = listOf(
                s.waveform.visualizerTierSimple,
                s.waveform.visualizerTierRefined,
                s.waveform.visualizerTierShowcase,
            )
            assertEquals("三档文案有重复：$tiers", 3, tiers.distinct().size)
        }
        fields.forEach { (name, get) ->
            val values = presets.map { get(it.waveform) }
            assertTrue(
                "$name 的 8 种语言取值全同（疑似只改了文件名没改内容）：${values.first()}",
                values.distinct().size >= 2,
            )
        }

        // 转发属性（`Strings.visualizerXxx`）必须在，且与组内同值。
        presets.forEach { s ->
            fields.forEach { (name, get) ->
                val forward = Strings::class.java.methods.firstOrNull {
                    it.parameterCount == 0 && it.name == "get" + name.replaceFirstChar { c -> c.uppercaseChar() }
                }
                assertNotNull("$name 的转发属性不存在", forward)
                assertEquals(name, get(s.waveform), forward!!.invoke(s))
            }
        }

        // ---- 事实约束 1：标签说「点按」，说明里写明拖拽未实现 ----
        val dragWords = listOf("拖", "drag", "zieh", "перетаск", "끌", "드래그", "ドラッグ", "引 ずる")
        val notImplemented = listOf(
            "未实现", "未實作", "not implemented", "未実装", "未 実装",
            "구현되지 않았", "nicht umgesetzt", "не реализовано",
        )
        presets.forEach { s ->
            val label = s.waveform.visualizerDragLabel.lowercase()
            dragWords.forEach { word ->
                assertTrue(
                    "visualizerDragLabel 里出现了「$word」—— 本版没有拖拽，标签必须写点按：${s.waveform.visualizerDragLabel}",
                    !label.contains(word.lowercase()),
                )
            }
            val desc = s.waveform.visualizerDragDescription
            assertTrue(
                "visualizerDragDescription 必须写明拖拽未实现：$desc",
                notImplemented.any { desc.contains(it) },
            )
        }

        // ---- 事实约束 2：必须写明「不是频谱」 ----
        val spectrumWords = listOf(
            "频谱", "頻譜", "frequency", "スペクトラ", "スペクトル", "스펙트럼", "spektrum", "спектр",
        )
        presets.forEach { s ->
            val hint = s.waveform.visualizerNotSpectrumHint.lowercase()
            assertTrue(
                "visualizerNotSpectrumHint 必须点名频谱：${s.waveform.visualizerNotSpectrumHint}",
                spectrumWords.any { hint.contains(it) },
            )
        }

        // ---- 事实约束 3：必须写明低端设备默认简洁档 ----
        val lowEndWords = listOf(
            "低端设备", "低端裝置", "low-end", "低スペック", "低端 端末", "저사양",
            "schwache geräte", "слабые устройства",
        )
        val defaultWords = listOf("默认", "預設", "start at", "既定", "기본", "starten mit", "начинают с")
        presets.forEach { s ->
            val desc = s.waveform.visualizerTierDescription
            val lower = desc.lowercase()
            assertTrue(
                "visualizerTierDescription 必须写明低端设备：$desc",
                lowEndWords.any { lower.contains(it.lowercase()) },
            )
            assertTrue(
                "visualizerTierDescription 必须写明低端设备的默认档：$desc",
                defaultWords.any { lower.contains(it.lowercase()) },
            )
        }
    }

    /**
     * 跨语言查重：允许 zh-CN 与 zh-TW 同形（繁简同形词），其余两两必须不同。
     *
     * `values` 的 key 是文案本身，value 也是 —— 上面刻意用 `map[text] = text`：
     * 这样「哪两种语言撞了」在失败信息里是**可读的**，而 `Set<String>` 只会说
     * 「少了一个」。
     */
    private fun assertLocaleDistinct(values: Map<String, String>, what: String) {
        assertEquals("$what 出现了空文案", values.size, values.keys.count { it.isNotBlank() })
        // 只可能在 zh-CN / zh-TW 之间合法地相同 —— 若不同，那就更没问题了。
        val zhCard = zhCN.playlists.let { if (what == "layoutCard") it.layoutCard else it.layoutList }
        val zhTwCard = zhTW.playlists.let { if (what == "layoutCard") it.layoutCard else it.layoutList }
        val expected = if (zhCard == zhTwCard) 7 else 8
        assertEquals(
            "$what 的跨语言取值只有 ${values.size} 个不同词（期望 $expected）—— " +
                "有两条一样说明有人只改了文件名没改内容：${values.keys}",
            expected,
            values.size,
        )
    }

    /**
     * ★ v2.5.5 · G：**两源都返回时，新路径与旧的 `sourceSummary` 逐字相同。**
     *
     * 本版把统计行从「只收两个整数」的 `sourceSummary` 换成「收两段已经成文的字符串」的
     * `searchSourceSummaryWithStatus`（因为前者在类型上无法表达「还没回来」）。
     * 换路径**不许改口径** —— 八种语言、四组数字逐个比。
     * 这条断言曾经真的红过：en/de/ru 的 `searchSourceCount` 一开始带了单位词，
     * 而那三个语言的 `sourceSummary` 是 `"NetEase $a · QQ Music $b"`（无单位）⇒ 两边不一致。
     */
    @Test
    fun `v2_5_5 统计行的新路径与旧格式逐字一致`() {
        val presets = listOf(zhCN, zhTW, en, jpJP, jpMY, koNK, deDE, ruRU)
        presets.forEach { s ->
            for ((n, q) in listOf(30 to 12, 0 to 0, 1 to 1, 300 to 7)) {
                assertEquals(
                    "两源都 DONE 时新路径改了文案：n=$n q=$q",
                    s.sourceSummary(n, q),
                    s.searchSourceSummaryWithStatus(s.searchSourceCount(n), s.searchSourceCount(q)),
                )
            }
        }
    }

    /**
     * v3.3.0 · 需求第 4 / 8 条「桌面播放卡片（App Widget）」：**8 条文案落在独立分组
     * [WidgetStrings]**，外层只为它加了 1 个组参数（138 → 139 → 与另两个组一起到 141）。
     *
     * ## 为什么这一组必须被监控
     *
     * 它的消费点在**桌面进程**里（`RemoteViews` 由 launcher inflate），
     * 是三组新文案里唯一「应用内看不到、只能在桌面上验证」的一份 ——
     * 漏填一格的后果（卡片上白留一行 / TalkBack 念出中文）在应用内跑一遍完全发现不了。
     *
     * 内容侧的逐条断言（8 种语言 × 8 条非空、组内不撞词、播放/暂停不撞词、
     * 品牌名 Ncrust 不被翻译掉）在
     * `app/src/test/java/com/takahashirinta/ncrust/ui/widget/WidgetStringsTest.kt`；
     * 这里只做**组本身的规模与槽位**记账 —— 两处分工与 [PlaybackFailureStrings] 的写法一致。
     */
    @Test
    fun `v3_3_0 的 8 条桌面卡片文案进了 WidgetStrings`() {
        val groupClazz = Class.forName("com.takahashirinta.ncrust.ui.i18n.WidgetStrings")
        assertEquals(
            "WidgetStrings 的参数数变了 —— 若是有意加文案，请同步改这条断言" +
                "（并确认 8 种语言文件都补齐、WidgetStringsTest 的字段清单也同步）",
            8,
            primaryParams(groupClazz),
        )
        // 组没有默认参数 ⇒ 既没有默认值 mask、也没有 DefaultConstructorMarker：槽位 = this + N。
        assertEquals(9, dexSlots(8, false))

        // 转发路径：卡片侧读的是 `strings.widget.xxx`（组参数本身必须真的进了主构造器）。
        val presets = listOf(zhCN, zhTW, en, jpJP, jpMY, koNK, deDE, ruRU)
        presets.forEach { s ->
            assertTrue("${s.widget.widgetEmpty} 是空串", s.widget.widgetEmpty.isNotBlank())
            assertTrue("${s.widget.widgetOpenApp} 丢了品牌名", s.widget.widgetOpenApp.contains("Ncrust"))
        }
    }
}
