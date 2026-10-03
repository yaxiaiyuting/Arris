/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.5.5 · F：把「迭代数」与「编译模式」变成**运行时可调**的参数。
 */

package com.takahashirinta.ncrust.benchmark

import android.os.Bundle
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMacrobenchmarkApi
import androidx.test.platform.app.InstrumentationRegistry

/**
 * 基准的运行参数（迭代数 / 编译模式），可被 `am instrument -e` 覆盖。
 *
 * ## 为什么需要它（v2.5.4 的真实卡点）
 *
 * v2.5.4 的 macrobenchmark **没跑完**：`StartupBenchmark` 20 分钟无输出。
 * 复盘（`docs/verification/v2.5.5/probe-benchmark.md` §2）给出的根因里有一条是
 * **参数只能改源码**：
 *
 * - 迭代数硬编码在四个 `@Test` 里（8 / 6 / 6 / 6）；
 * - 编译模式硬编码 `CompilationMode.DEFAULT`。
 *
 * 于是「换台设备、降迭代、换编译模式再跑一次」= 改四处 Kotlin + 重新构建 benchmark 模块。
 * 换设备复跑是 macrobenchmark 的常规动作，把它做成源码改动等于每次都要重新编译，
 * 而且**改了什么参数不会留在任何日志里**（报告里只能写「我记得改成了 3 次」）。
 *
 * `am instrument -e key value` 是 Android 测试框架自带的通道，`InstrumentationRegistry`
 * 读得到它。参数因此可以写进命令行、写进 `run_benchmark.sh`、也自动出现在
 * `benchmarkData.json` 的上下文里（同一份命令可复现）。
 *
 * ## 默认值 = 原行为
 *
 * 不传任何参数时，[iterations] 返回调用点给的 [default]，[compilationMode] 返回
 * [CompilationMode.DEFAULT] —— 与 v2.5.4 **逐字节相同**。这一点是硬要求：
 * 参数化不能顺手改掉既有口径，否则 v2.5.4 之前的所有数字都不可比。
 *
 * ## 编译模式的取值与取舍
 *
 * | 值 | 语义 | 什么时候用 |
 * |---|---|---|
 * | `default`（默认） | `CompilationMode.DEFAULT` —— API >= 24 上等于 `Partial(UseIfAvailable, 0)`（字节码实测，见探针 §2.2） | 与历史数字可比时 |
 * | `none` | `CompilationMode.None` —— **重置**编译态（会清 profile），测"最坏情况" | 需要最坏情况时；**会在迭代间清掉 ART profile** |
 * | `ignore` | `CompilationMode.Ignore` —— 完全不动 app 的编译态与登录态（字节码实测 `shouldReset=false`） | **降迭代复跑的首选**：它不改设备上的既有状态 |
 * | `full` | `CompilationMode.Full` —— 每次迭代前全量 AOT，**极慢** | 只在需要"最优情况"上界时 |
 *
 * ⚠️ `full` 在低端设备上单次迭代就可能几分钟，配合 8 次迭代必然「跑不完」——
 * v2.5.4 的 `EVIDENCE.md` 把源码里的 `DEFAULT` 记成了 `Full`，那个记录是错的
 * （探针用字节码实测纠正过）。**记参数时请以本文件的输出为准**：它会把实际生效的
 * 取值打进 logcat（tag `NcrustBench`）。
 */
object BenchArgs {

    /** 迭代数覆盖。 */
    const val KEY_ITERATIONS = "ncrust.bench.iterations"

    /** 编译模式覆盖（见上表）。 */
    const val KEY_COMPILATION = "ncrust.bench.compilation"

    private const val TAG = "NcrustBench"

    private val args: Bundle by lazy {
        runCatching { InstrumentationRegistry.getArguments() }.getOrElse { Bundle() }
    }

    /**
     * 实际使用的迭代数：命令行给了合法正整数就用它，否则用 [default]。
     *
     * 非法值（0 / 负数 / 非数字）**回落默认**而不是抛：一次参数打错不该让整轮测量失败，
     * 但会打一条 warning（否则「我明明传了 3」与「实际跑了 8」无法区分）。
     */
    fun iterations(default: Int): Int {
        val raw = args.getString(KEY_ITERATIONS)?.trim()
        if (raw.isNullOrEmpty()) return default
        val parsed = raw.toIntOrNull()
        if (parsed == null || parsed <= 0) {
            log("ignored invalid $KEY_ITERATIONS='$raw', using default=$default")
            return default
        }
        log("iterations=$parsed (default was $default)")
        return parsed
    }

    /**
     * 实际使用的编译模式。未识别取值同样**回落默认**并告警。
     *
     * 刻意不支持 `partial`：它的参数是平台内部细节（`UseIfAvailable` + 阈值），
     * 暴露一个只能传一半的旋钮比不暴露更容易误用。
     */
    @OptIn(ExperimentalMacrobenchmarkApi::class)
    fun compilationMode(): CompilationMode {
        val raw = args.getString(KEY_COMPILATION)?.trim()?.lowercase()
        val mode = when (raw) {
            null, "" -> CompilationMode.DEFAULT
            "default" -> CompilationMode.DEFAULT
            // ⚠️ 这四个在 1.4.1 里是**类**不是 object（`CompilationMode.None()`），
            // 而 `Ignore` 带 `@ExperimentalMacrobenchmarkApi` ⇒ 本函数要 OptIn。
            // 写错的表现是编译期的 `Classifier 'None' does not have a companion object`。
            "none" -> CompilationMode.None()
            "ignore" -> CompilationMode.Ignore()
            "full" -> CompilationMode.Full()
            else -> {
                log("ignored unknown $KEY_COMPILATION='$raw', using DEFAULT")
                CompilationMode.DEFAULT
            }
        }
        log("compilationMode=${mode.javaClass.simpleName}")
        return mode
    }

    /**
     * 把实际生效的参数写进 logcat。
     *
     * 这是本文件存在的一半理由：**参数必须留在证据里**。
     * v2.5.4 的报告里写着 `CompilationMode.Full`，而源码写的是 `DEFAULT` ——
     * 那次偏差不是记性问题，是「参数不出现在任何日志里」的必然结果。
     */
    private fun log(message: String) {
        runCatching { android.util.Log.i(TAG, message) }
    }
}
