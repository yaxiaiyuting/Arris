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
 * v2.0.0 · T2：「播放时禁止熄屏」开关的**唯一读写入口**（与 [RotationSetting] /
 * [com.takahashirinta.ncrust.ui.player.VisualizerSetting] 同一套写法）。
 *
 * 语义（已确认的设计决策）：
 *  - 只在 **`isPlaying == true`** 时生效 —— 暂停 / 缓冲恢复系统熄屏策略；
 *  - 只在**播放器界面**（竖屏全屏播放器与大屏模式共用同一个展开进度）生效，
 *    首页 / 库 / 搜索 / mini bar 都不生效；
 *  - **后台不生效**：进 ON_PAUSE 立刻摘掉窗口 flag（残留会持续耗电）；
 *  - **默认开**，可在设置 → 播放里关掉。
 *
 * 实现用 `WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON`，**不用 WakeLock**：
 * WakeLock 需要权限、且忘记释放的代价是持续耗电；窗口 flag 跟随 Activity 生命周期，
 * 连"忘记清"都很难发生（见 MainActivity 的 KeepScreenOnEffect）。
 */
object KeepScreenOnSetting {

    private const val PREFS = "ncrust_settings"
    private const val KEY = "keep_screen_on"

    /** 默认**开**：这就是用户要的"听着歌别熄屏"。 */
    const val DEFAULT_ENABLED = true

    private val stateHolder = mutableStateOf(DEFAULT_ENABLED)
    private var loadedFromDisk = false

    /** 进程内广播：设置页开关与播放器侧的消费者读同一份值，改完立即生效。 */
    val state: MutableState<Boolean> get() = stateHolder

    fun read(context: Context): Boolean {
        if (!loadedFromDisk) {
            stateHolder.value = prefs(context).getBoolean(KEY, DEFAULT_ENABLED)
            loadedFromDisk = true
        }
        return stateHolder.value
    }

    fun write(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY, enabled).apply()
        loadedFromDisk = true
        stateHolder.value = enabled
    }

    internal fun resetForTest() {
        stateHolder.value = DEFAULT_ENABLED
        loadedFromDisk = false
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
