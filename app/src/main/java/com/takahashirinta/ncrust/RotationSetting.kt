/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

/**
 * v1.8.0 · T4：「自动旋转」开关的**唯一读写入口**。
 *
 * 为什么需要一个单例而不是各页面各自 `remember` 读 prefs：
 * 这个开关有**三个**互不相邻的消费者 ——
 *  1. `MainActivity.applyOrientationPolicy()`（Activity 层，onCreate / onConfigurationChanged
 *     都会读；它在 Compose 之外，读不到 Compose 状态）；
 *  2. 设置页整行开关（`UserScreen`）；
 *  3. 播放器里的旋转图标（`FullPlayerControls`）。
 * 任何一处改了值，另外两处必须立刻看到，否则会出现「播放器里关了，进设置页还显示开着」
 * 这类状态分裂。所以：**写**只有 [write] 一条路（落盘 + 更新进程内 Compose 状态），
 * **读**在 Compose 里读 [state]（可观察），在 Activity 里读 [read]（同步、不订阅）。
 *
 * ⚠️ **应用内开关 ≠ 系统开关**：本开关只决定**本应用**是否跟随传感器旋转
 * （`Activity.requestedOrientation = SCREEN_ORIENTATION_SENSOR`），
 * **既不读取、也不修改** `Settings.System.ACCELEROMETER_ROTATION`。
 * 系统那一层的自动旋转锁与本开关互相独立。
 *
 * 默认开：与"手机系统默认允许旋转"一致。关掉时行为**逐字节等于 v1.7.0**：
 * 手机锁竖屏、平板不限制方向、大屏模式靠 ⤢ 按钮手动进出。
 */
object RotationSetting {

    private const val PREFS = "ncrust_settings"
    private const val KEY = "auto_rotate"

    /** 默认**开**。 */
    const val DEFAULT_ENABLED = true

    private val stateHolder = mutableStateOf(DEFAULT_ENABLED)
    private var loadedFromDisk = false

    /** Compose 侧的可观察状态。不要在 Activity 的非 Compose 代码里读它。 */
    val state: MutableState<Boolean> get() = stateHolder

    /** 同步读盘（带进程内缓存：第一次之后只走内存）。Activity 的方向策略要用。 */
    fun read(context: Context): Boolean {
        if (!loadedFromDisk) {
            stateHolder.value = prefs(context).getBoolean(KEY, DEFAULT_ENABLED)
            loadedFromDisk = true
        }
        return stateHolder.value
    }

    /** 唯一的写入口：落盘 + 立刻广播给所有观察者。 */
    fun write(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY, enabled).apply()
        loadedFromDisk = true
        stateHolder.value = enabled
    }

    /**
     * 仅用于单测 / 进程内重置：把内存态恢复成"没读过盘"。
     * 没有它的话，同一个进程里先后跑的多条用例会互相污染。
     */
    internal fun resetForTest() {
        stateHolder.value = DEFAULT_ENABLED
        loadedFromDisk = false
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
