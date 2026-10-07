/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.5.6 · P0：baseline profile **生成器**。
 */

package com.takahashirinta.ncrust.benchmark

import android.os.SystemClock
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val PACKAGE = "com.takahashirinta.ncrust"

/**
 * v2.5.6 · P0：**用真实交互采样生成 baseline profile**。
 *
 * ## 为什么需要它（而不是继续手写 `baseline-prof.txt`）
 *
 * v1.2.1（`f9d45f4`）接入 `androidx.profileinstaller` 时，`app/src/main/baseline-prof.txt`
 * 是**手写的 class-only 清单**（91 行 `Lcom/…;`）。class-only 条目只能让 ART
 * 提前加载/校验类，**不能**把方法标成 hot 去做 AOT 编译 —— 它自己那份注释也承认
 * 「覆盖 60~70% 收益」。真正的收益在**方法级** profile，而那只能靠真机采样得到。
 *
 * 本文件就是那个采样器：它驱动 7 条用户旅程，`BaselineProfileRule` 在后台用
 * `ProfileInstaller` + ART 的采样通道记录「哪些方法真的被执行了」，最后落盘成
 * 新的 `baseline-prof.txt`。
 *
 * ## 覆盖的 7 条旅程（= 任务书 §3.2 的清单）
 *
 * | # | 旅程 | 实现 |
 * |---|---|---|
 * | 1 | 冷启动到首页 | [journeyColdStartHome] |
 * | 2 | 进入搜索 | [journeySearch] |
 * | 3 | 进入库 | [journeyLibrary] |
 * | 4 | 进入播放器 | [journeyPlayer] |
 * | 5 | 播放开始 | [journeyPlaybackStart] |
 * | 6 | 切歌 | [journeyTrackChange] |
 * | 7 | 列表滚动 | [journeyScroll] |
 *
 * ## 三条硬约束（照铁律 4 / 5 / 16 写的，不是风格问题）
 *
 * 1. **旅程之间互相隔离**：每条旅程整个包在 `runCatching` 里（见 [Journey]）。
 *    一条旅程找不到入口（未登录、队列为空、机型没有某个控件）**不得**让整轮采样失败 ——
 *    半份 profile 仍然比没有 profile 强，而「采样崩了」会让人误以为是 profile 有问题。
 *    这与铁律 4「非核心组件不得破坏核心链路」同形：采样器是旁路，不许把主链路一起拖下水。
 *
 * 2. **失败必须有界**：所有等待都是 `device.wait(Until…, timeoutMs)` 的**有限**超时，
 *    没有 `while (true)`、没有无限重试（铁律 5）。滑动只在「节点失效」时重试一次。
 *
 * 3. **不伪造覆盖**：每条旅程结束都往 logcat 打一行 `OK` / `SKIP(reason)`
 *    （tag 见 [TAG]）。报告里「覆盖了哪几条」只能来自这些日志，不能来自「我写了这段代码」。
 *
 * ## 怎么跑
 *
 * ```bash
 * # 生产路径（推荐）：构建 + 安装 release + 采样 + 拉回文件
 * benchmark/generate_baseline_profile.sh
 *
 * # 手工路径（与既有基准同一条通道）
 * ./gradlew :benchmark:assembleDebug
 * adb install -r -t benchmark/build/outputs/apk/debug/benchmark-debug.apk
 * adb shell am instrument -w -e class \
 *   com.takahashirinta.ncrust.benchmark.BaselineProfileGenerator \
 *   com.takahashirinta.ncrust.benchmark/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * 迭代数可被 `-e ncrust.bench.iterations N` 覆盖（复用 [BenchArgs]，与四条基准同一条通道）。
 *
 * ## 前置条件（不满足只会让部分旅程 SKIP，不会让采样失败）
 *
 * - 被测应用**已安装 release 包并保持登录态**（未登录 ⇒ 旅程 5/6 必 SKIP）；
 * - 系统动画建议关闭（滑动旅程的稳定性；`generate_baseline_profile.sh` 会关并还原）。
 *
 * ## ⚠️ 设备 API 前提（v2.5.6 实测的**硬约束**，不是建议）
 *
 * 采样设备**不能是 API 24 的 S6**。实测（S6 / API 24）：
 *
 * ```
 * java.lang.IllegalArgumentException: Baseline Profile collection requires API 33+,
 * or a rooted device running API 28 or higher and rooted adb session (via `adb root`).
 *     at androidx.benchmark.macro.BaselineProfilesKt.buildMacrobenchmarkScope(BaselineProfiles.kt:160)
 * ```
 *
 * 也就是说 `BaselineProfileRule` 只接受两种设备：
 *
 * | 档 | 条件 |
 * |---|---|
 * | A | **API 33+**（无需 root） |
 * | B | API 28+ **且** `adb root` 可用（需要 userdebug/eng 版本或可 `adb root` 的 ROM） |
 *
 * 本仓库的可用采样机因此是 **PLC110（API 36）**。
 *
 * **这条限制不影响 profile 的用途**：profile 是**方法命中集合**，与 API 级别无关
 * （同一份 dex），API 36 上采到的条目在 API 24~30 上同样成立。
 * 但「在哪台机器上采的」必须写进证据 —— 所以 `generate_baseline_profile.sh`
 * 开头会把设备型号与 API 打进日志。
 *
 * ## 另一条实测约束：无障碍服务会抢走 `UiAutomation`
 *
 * v2.5.5 在 PLC110 上撞过 `UiAutomationService … already registered!`，
 * 根因是该机启用了两个第三方无障碍服务（Scene / GKD）。
 * 采样前需临时关闭它们，采完还原 ——
 * 留痕见 `docs/verification/v2.5.6/verification/plc110-accessibility-restore.txt`。
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generate() {
        baselineProfileRule.collect(
            packageName = PACKAGE,
            // 采样轮数：每轮都从冷启动开始重放 7 条旅程。
            // 轮数越多，命中越稳定（偶发路径被覆盖的概率上升），但耗时线性增长。
            // 默认 15 与官方模板一致，可用 `-e ncrust.bench.iterations N` 降低。
            maxIterations = BenchArgs.iterations(default = DEFAULT_ITERATIONS),
            // ⚠️ 参数名是 `stableIterations` 不是 `warmupIterations`
            // （benchmark-macro 1.4.1 的 Kotlin metadata 实测：
            //  `packageName / maxIterations / stableIterations / outputFilePrefix /
            //   includeInStartupProfile / strictStability / filterPredicate / profileBlock`）。
            // 语义是「连续多少轮没有新方法命中就认为 profile 已稳定」——
            // 它是**提前收敛**的判据，不是预热轮数。
            stableIterations = STABLE_ITERATIONS,
        ) {
            // 每轮开头把状态打回「未跑」，轮末统一报告 —— 这样日志里能看出
            // 「第几轮哪条旅程没跑成」，而不是只有一个笼统的总结。
            val journeys = JourneyReport()
            journeyColdStartHome(journeys)
            journeySearch(journeys)
            journeyLibrary(journeys)
            journeyPlayer(journeys)
            journeyPlaybackStart(journeys)
            journeyTrackChange(journeys)
            journeyScroll(journeys)
            journeys.log()
        }
    }

    // ── 旅程 1：冷启动到首页 ───────────────────────────────────────────
    /**
     * 冷启动 + 落到首页。
     *
     * 这一条是**唯一不允许 SKIP 的旅程**：`startActivityAndWait()` 之后 app 必然在前台，
     * 首页 tab 的文案在 8 个语言包里都非空 ⇒ 找不到就说明启动链路真的坏了。
     * 即便如此也不抛异常（铁律 4）：记 SKIP，让整轮采样继续。
     */
    private fun MacrobenchmarkScope.journeyColdStartHome(report: JourneyReport) =
        report.record("1-cold-start-home") {
            startActivityAndWait()
            device.waitForIdle()
            clickTab("首页", "Home")
            awaitMainScrollable(timeoutMs = 8_000)
        }

    // ── 旅程 2：进入搜索 ───────────────────────────────────────────────
    /**
     * 进搜索页 → 在输入框里敲一个 ASCII 关键词 → 回车 → 等结果列表。
     *
     * 为什么用 ASCII（`"love"`）而不是中文：UiAutomator 的 `setText` 对 Compose
     * `BasicTextField` 走的是 IME 通道，中文需要真实输入法；ASCII 在 8 个语言包下
     * 都能命中一些结果，且不依赖任何特定输入法。这里要的是**把搜索链路的代码跑热**，
     * 不是验证搜索结果对不对（那是 `SearchScreen` 的功能测试）。
     */
    private fun MacrobenchmarkScope.journeySearch(report: JourneyReport) =
        report.record("2-search") {
            clickTab("搜索", "Search")
            val field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 6_000)
                ?: error("未找到搜索输入框（EditText 不在 a11y 树里）")
            field.click()
            device.waitForIdle()
            field.text = SEARCH_QUERY
            device.waitForIdle()
            device.pressEnter()
            // 500ms debounce（SearchScreen）+ 两源并发 + 首屏渲染。
            SystemClock.sleep(4_000)
            device.waitForIdle()
            // 结果列表也滚一下，把 SongCard / 封面加载的代码带进来。
            awaitMainScrollable(timeoutMs = 5_000).also { list ->
                repeat(2) { swipeOnce(list, Direction.UP) }
            }
        }

    // ── 旅程 3：进入库 ─────────────────────────────────────────────────
    /** 进「库」页（默认「单曲」tab）并把列表滚两屏。 */
    private fun MacrobenchmarkScope.journeyLibrary(report: JourneyReport) =
        report.record("3-library") {
            clickTab("库", "Library")
            val list = awaitMainScrollable(timeoutMs = 8_000)
            repeat(3) { swipeOnce(list, Direction.UP) }
            repeat(1) { swipeOnce(list, Direction.DOWN) }
        }

    // ── 旅程 4：进入播放器 ─────────────────────────────────────────────
    /**
     * 从 mini bar 上拉展开播放器，再下拉收起。
     *
     * 与 `ExpandPlayerBenchmark` 同一套手势（40 步连续拖拽），因为它已经证明
     * 能在真机上把 `progress` 从 0 拉到 1（v1.7.0 的手势阈值修复就是为它做的）。
     * **队列为空时也能展开**（显示「暂无播放」），所以这条旅程不要求登录态。
     */
    private fun MacrobenchmarkScope.journeyPlayer(report: JourneyReport) =
        report.record("4-player") {
            // mini bar 在底部：先确保我们在某个有列表的页面上（首页/库都行）。
            clickTab("首页", "Home")
            device.waitForIdle()
            SystemClock.sleep(1_200) // splash 淡出 + miniBar 就位
            val w = device.displayWidth
            val h = device.displayHeight
            val miniBarY = (h * 0.92f).toInt()
            val topY = (h * 0.15f).toInt()
            device.swipe(w / 2, miniBarY, w / 2, topY, 40)
            device.waitForIdle()
            SystemClock.sleep(800) // 展开动画（400ms）+ 首次组合
            // 歌词 / 队列 双面板：切一次队列再切回来，把 QueueView 的代码带进 profile。
            device.wait(Until.findObject(By.desc("队列")), 3_000)?.click()
            device.waitForIdle()
            SystemClock.sleep(400)
            device.wait(Until.findObject(By.desc("歌词")), 3_000)?.click()
            device.waitForIdle()
            SystemClock.sleep(400)
            device.swipe(w / 2, topY, w / 2, miniBarY, 40)
            device.waitForIdle()
            SystemClock.sleep(400)
        }

    // ── 旅程 5：播放开始 ───────────────────────────────────────────────
    /**
     * 从「库 → 单曲」点第一行开始播放。
     *
     * 为什么点列表行而不是按 mini bar 的播放键：列表行的点击走的是
     * `MainScreen.playSongItem` → 替换队列 → 拉 URL → 起播，这条链**包含**播放器
     * 真正开始出声的全部代码；而 mini bar 的播放键只覆盖 resume。
     *
     * 需要登录态（未登录时「单曲」tab 为空）⇒ 找不到可点的行就 SKIP，不抛。
     */
    private fun MacrobenchmarkScope.journeyPlaybackStart(report: JourneyReport) =
        report.record("5-playback-start") {
            clickTab("库", "Library")
            val list = awaitMainScrollable(timeoutMs = 8_000)
            val row = firstClickableRow(list)
                ?: error("「库 → 单曲」没有可点击的行（未登录 / 收藏为空）")
            row.click()
            device.waitForIdle()
            // 拉 URL + ExoPlayer prepare + 起播 + mini bar 元数据刷新。
            SystemClock.sleep(5_000)
            device.waitForIdle()
        }

    // ── 旅程 6：切歌 ───────────────────────────────────────────────────
    /**
     * 展开播放器 → 按「下一首」→ 收起。
     *
     * 前置是「当前有曲目在播」（旅程 5 刚点过一行，或 app 从
     * `ncrust_playback_state` 恢复了上次的队列）。两者都没有时
     * `By.desc("下一首")` 找不到按钮 ⇒ SKIP，不抛。
     */
    private fun MacrobenchmarkScope.journeyTrackChange(report: JourneyReport) =
        report.record("6-track-change") {
            clickTab("首页", "Home")
            device.waitForIdle()
            SystemClock.sleep(1_200)
            val w = device.displayWidth
            val h = device.displayHeight
            val miniBarY = (h * 0.92f).toInt()
            val topY = (h * 0.15f).toInt()
            device.swipe(w / 2, miniBarY, w / 2, topY, 40)
            device.waitForIdle()
            SystemClock.sleep(800)

            val next = device.wait(Until.findObject(By.desc("下一首")), 4_000)
                ?: error("播放器里没有「下一首」按钮（队列为空 / 未起播）")
            next.click()
            device.waitForIdle()
            // 切歌：停旧 → 拉新 URL → 预载槽位替换 → 元数据/歌词刷新。
            SystemClock.sleep(5_000)
            device.waitForIdle()

            device.swipe(w / 2, topY, w / 2, miniBarY, 40)
            device.waitForIdle()
        }

    // ── 旅程 7：列表滚动 ───────────────────────────────────────────────
    /**
     * 首页主列表上下各滚几屏。
     *
     * 单开一条（而不是并进旅程 1）的理由：滚动要的是 `LazyColumn` 的组合/回收、
     * `SongCard`、Coil 封面解码这几条路径，它们在冷启动那一轮里**只跑首屏**。
     */
    private fun MacrobenchmarkScope.journeyScroll(report: JourneyReport) =
        report.record("7-scroll") {
            clickTab("首页", "Home")
            val list = awaitMainScrollable(timeoutMs = 8_000)
            repeat(5) { swipeOnce(list, Direction.UP) }
            repeat(3) { swipeOnce(list, Direction.DOWN) }
        }

    // ── 工具 ───────────────────────────────────────────────────────────

    /**
     * 点底部导航（窄屏）/ 左侧栏（宽屏）的某个 tab。
     *
     * 中文文案优先，找不到再退回英文 —— 与 `HomeScrollBenchmark` /
     * `SettingsScrollBenchmark` 同一套容错（本应用 UI 文案走运行时 i18n，
     * `By.res()` 一个都找不到）。
     */
    private fun MacrobenchmarkScope.clickTab(zh: String, en: String) {
        val tab = device.wait(Until.findObject(By.text(zh)), 8_000)
            ?: device.wait(Until.findObject(By.textContains(en)), 3_000)
            ?: error("未见导航入口「$zh」（splash 未结束？）")
        tab.click()
        device.waitForIdle()
    }

    /**
     * 取可见高度最大的 scrollable 作为主列表。
     *
     * 不用 `height > width` 判定：平板/横屏下主列表宽度大于高度，会被误排除
     * （`HomeScrollBenchmark` KDoc 记的同一教训）。
     */
    private fun MacrobenchmarkScope.awaitMainScrollable(timeoutMs: Long): UiObject2 {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val vertical = try {
                device.findObjects(By.scrollable(true))
                    .maxByOrNull { it.visibleBounds.height() }
            } catch (_: StaleObjectException) {
                null
            }
            if (vertical != null) return vertical
            SystemClock.sleep(250)
        }
        error("${timeoutMs}ms 内未找到纵向主列表")
    }

    /** 滚动一次；节点被 LazyColumn 回收时重查一次（最多一次，铁律 5）。 */
    private fun swipeOnce(list: UiObject2, direction: Direction) {
        try {
            list.swipe(direction, 1.0f)
        } catch (_: StaleObjectException) {
            // 节点失效：本次滑动放弃。不重查 —— 下一条旅程会重新 awaitMainScrollable。
        }
        SystemClock.sleep(300)
    }

    /**
     * 主列表里**第一行**可点击节点。
     *
     * 不能用 `By.text(...)`：库页的行是 `SongCard`，标题是用户数据，采样时不可知。
     * 改用几何判据 —— 取落在列表可见区内、且中心 y 最小的 clickable 节点，
     * 那就是第一行。找不到返回 null（调用方按 SKIP 处理）。
     */
    private fun MacrobenchmarkScope.firstClickableRow(list: UiObject2): UiObject2? {
        val bounds = try {
            list.visibleBounds
        } catch (_: StaleObjectException) {
            return null
        }
        return try {
            device.findObjects(By.clickable(true))
                .filter { obj ->
                    val b = try {
                        obj.visibleBounds
                    } catch (_: StaleObjectException) {
                        return@filter false
                    }
                    b.height() > 0 &&
                        b.width() > 0 &&
                        // 行必须整个落在列表可见区内（容 4px 误差），
                        // 否则会点到导航栏 / mini bar 上的按钮。
                        bounds.contains(b.left, b.top) &&
                        bounds.contains(b.right - 1, b.bottom - 1)
                }
                .minByOrNull { it.visibleBounds.top }
        } catch (_: StaleObjectException) {
            null
        }
    }

    private companion object {
        /** 默认采样轮数上限（与官方 `:baselineprofile` 模板一致）。 */
        const val DEFAULT_ITERATIONS = 15
        const val STABLE_ITERATIONS = 5

        /** ASCII 关键词：不依赖输入法，8 个语言包下都能命中结果。 */
        const val SEARCH_QUERY = "love"
    }
}

/** 采样器日志 tag。报告里的「覆盖了几条旅程」只能来自这个 tag 的行。 */
internal const val PROFILE_GEN_TAG = "NcrustProfileGen"

/**
 * 一条旅程的执行结果。**只记事实，不记「应该没问题」**。
 *
 * `SKIP` 与 `OK` 的区别就是「这段代码到底有没有被跑热」——
 * 报告里把 SKIP 写成 OK 会让生成的 profile 看起来覆盖了实际没覆盖的路径。
 */
private class JourneyReport {

    private val results = LinkedHashMap<String, String>()

    /** 跑一条旅程：成功记 `OK`，任何异常记 `SKIP(原因)`。异常**不外传**（铁律 4）。 */
    fun record(name: String, block: () -> Unit) {
        val outcome = try {
            block()
            "OK"
        } catch (t: Throwable) {
            // OutOfMemory / 进程被杀这类 Error 也吞掉：采样是旁路，
            // 一条旅程的失败不该让整轮 profile 收集作废。
            "SKIP(${t.javaClass.simpleName}: ${t.message?.take(120) ?: ""})"
        }
        results[name] = outcome
    }

    /** 把本轮结果打进 logcat。这是**唯一的**覆盖证据来源。 */
    fun log() {
        val ok = results.values.count { it == "OK" }
        android.util.Log.i(PROFILE_GEN_TAG, "journeys $ok/${results.size} OK")
        results.forEach { (name, outcome) ->
            android.util.Log.i(PROFILE_GEN_TAG, "  $name -> $outcome")
        }
    }
}
