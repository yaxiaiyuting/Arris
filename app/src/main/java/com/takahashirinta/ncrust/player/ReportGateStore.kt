/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.5.5 · B：跨音源上报闸门计数的**落盘**（本地私有目录，绝不上报）。
 */

package com.takahashirinta.ncrust.player

import android.content.Context
import android.util.Log
import com.google.gson.Gson

/**
 * [ReportGateCounters] 的持久化。**本地私有目录，没有网络出口。**
 *
 * ## 为什么单开一个 prefs 文件，而不是塞进 `ncrust_qq_probe`
 *
 * `ncrust_qq_probe` 是「QQ 兜底路径」的诊断样本，与「跨源上报被拦了几次」
 * 是两件不同的事：前者的样本由 QQ 解析路径产生，后者的样本由**上报路径**产生，
 * 两者可以独立为零。混在一个文件里，将来要清掉其中一份就会把另一份一起清掉。
 * 这与 AGENTS.md v2.5.4 规则 3 的理由同源（「不要寄生在会被清掉的文件上」）。
 *
 * ## 写盘时机（绝不在埋点点位落盘）
 *
 * 与 [com.takahashirinta.ncrust.qq.QqProbeStore] 一致：只在
 * ① 用户主动打开诊断入口；② `MainActivity.onStop`（进程可能被杀）两处落盘，
 * 且都用 `apply()`（异步、不阻塞调用线程）。
 * **上报路径本身只做一次内存自增**（[ReportGateCounter]），不碰 IO ——
 * 铁律 4：非核心组件不得破坏核心播放链路。
 *
 * ## 加字段 = 加迁移逻辑 = 加单测
 *
 * 读出来的 JSON 先过 [ReportGateCounters.canonical]（`null` 归零）再喂给计数器。
 * 老 JSON 缺字段 ⇒ `null` ⇒ 0 ⇒ 语义正确（「没记到」）。
 */
object ReportGateStore {

    private const val TAG = "ReportGateStore"
    private const val PREFS = "ncrust_report_gate"
    private const val KEY = "stats"

    private val gson = Gson()

    @Volatile
    private var seeded = false

    /** JSON → 快照。坏 JSON 返回 `null`（调用方按「还没有样本」处理，**不抛**）。 */
    fun decode(json: String?): ReportGateCounters? {
        if (json.isNullOrEmpty()) return null
        return try {
            gson.fromJson(json, ReportGateCounters::class.java)
        } catch (_: Exception) {
            null
        }
    }

    fun encode(counters: ReportGateCounters): String = gson.toJson(counters)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 进程启动后**第一次**触达时把落盘样本读回计数器（幂等）。 */
    fun ensureSeeded(context: Context) {
        if (seeded) return
        seeded = true
        val restored = decode(prefs(context).getString(KEY, null))
        if (restored != null) ReportGateStats.counter.seed(restored)
    }

    /**
     * 把当前快照写回磁盘，并返回它。
     *
     * 返回快照而不是 Unit：唯一的调用点要同时「读出来给人看」和「落盘」——
     * 分两次调用就会出现两份不同的数字。
     */
    fun snapshotAndFlush(context: Context): ReportGateCounters {
        val counters = ReportGateStats.counter.snapshot()
        return try {
            prefs(context).edit().putString(KEY, encode(counters)).apply()
            counters
        } catch (e: Exception) {
            // 落盘失败不该影响任何东西 —— 统计是旁路，不是功能。
            Log.w(TAG, "report gate flush failed", e)
            counters
        }
    }
}
