/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.5.4 · C：QQ 兜底统计的**落盘**（本地私有目录，绝不上报）。
 */

package com.takahashirinta.ncrust.qq

import android.content.Context
import android.util.Log
import com.google.gson.Gson

/**
 * `QqFallbackCounters` 的持久化。**本地私有目录，没有网络出口。**
 *
 * ## 为什么单开一个 prefs 文件，而不是塞进 `ncrust_offline`
 *
 * `ncrust_offline` 与「清空离线缓存」是配对的不变量
 * （`UserScreen` 的清缓存入口 → `OfflineAudioCache.clear`）。诊断计数一旦住在那里，
 * 用户顺手清一次缓存就把样本抹掉了 —— 而本版要的正是「跑一两天」的累积样本。
 *
 * ## 写盘时机（绝不在埋点点位落盘）
 *
 * 只有两处：① debug 诊断入口被点开时；② `MainActivity.onStop`（进程可能被杀）。
 * 两处都不在播放关键路径上，而且都用 `apply()`（异步落盘、不阻塞调用线程）。
 *
 * ## 加字段 = 加迁移逻辑 = 加单测
 *
 * 读出来的 JSON 先过 [QqFallbackCounters.canonical]（`null` 归零）再喂给计数器。
 * 老 JSON 缺字段 ⇒ `null` ⇒ 0 ⇒ 语义正确（「没记到」）。
 * 这条与 `OfflineLibrary` / `OfflineUrlStore` 的三段式（纯逻辑 / 落盘 / 快照）一致。
 */
object QqProbeStore {

    private const val TAG = "QqProbeStore"
    private const val PREFS = "ncrust_qq_probe"
    private const val KEY = "stats"

    private val gson = Gson()

    @Volatile
    private var seeded = false

    /** JSON → 快照。坏 JSON 返回 `null`（调用方按「还没有样本」处理，**不抛**）。 */
    fun decode(json: String?): QqFallbackCounters? {
        if (json.isNullOrEmpty()) return null
        return try {
            gson.fromJson(json, QqFallbackCounters::class.java)
        } catch (_: Exception) {
            null
        }
    }

    fun encode(counters: QqFallbackCounters): String = gson.toJson(counters)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 进程启动后**第一次**触达时把落盘样本读回计数器（幂等）。 */
    fun ensureSeeded(context: Context) {
        if (seeded) return
        seeded = true
        val json = prefs(context).getString(KEY, null)
        val restored = decode(json)
        if (restored != null) QqProbeCounters.seed(restored)
    }

    /**
     * 把当前快照写回磁盘，并返回它。
     *
     * 返回快照而不是 Unit：唯一的调用点是 debug 诊断入口，它要同时
     * 「读出来给人看」和「落盘」—— 分两次调用就会出现两份不同的数字。
     */
    fun snapshotAndFlush(context: Context): QqFallbackCounters {
        val counters = QqProbeCounters.snapshot()
        return try {
            prefs(context).edit().putString(KEY, encode(counters)).apply()
            counters
        } catch (e: Exception) {
            // 落盘失败不该影响任何东西 —— 统计是旁路，不是功能。
            Log.w(TAG, "qq probe flush failed", e)
            counters
        }
    }
}
