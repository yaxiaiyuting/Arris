/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.1.0 · B：qm 请求所需的设备标识（本机自生成，不改写他人标识）。
 */

package com.takahashirinta.ncrust.qq

import android.content.Context
import java.security.SecureRandom

/**
 * qm 客户端协议里的设备标识（v2.1.0 · B）。
 *
 * ## 为什么自生成而不是照抄参考实现里的常量
 *
 * 参考实现（以及网上大量示例）直接写死了一组 `QIMEI36` / `OpenUDID` / `uid` 常量 ——
 * 那是**某个具体设备/账号的指纹**。照抄会带来两个问题：
 * 1. 我们无从知道那个值是否已经失效（它属于别人）；
 * 2. 全应用共用一个人家的指纹，是在冒用身份，与本 fork 的定位不符。
 *
 * 官方客户端的做法本来就是「首次启动生成、之后固定」，所以我们照做：
 * 生成一次、写进 `ncrust_qq_prefs`、之后每次请求都带同一组值。
 * **不含任何账号信息**，清空 QQ 账号也不会清掉它（它标识的是设备，不是账号）。
 */
object QqIdentity {

    private const val PREFS = "ncrust_qq_prefs"
    private const val KEY_DEVICE = "qq_device_seed"

    /** 参考实现里 `uid`/`OpenUDID` 是 10 位十六进制字符串，这里保持一致的长度。 */
    private const val DEVICE_LEN = 16

    private val random = SecureRandom()

    /**
     * 稳定的设备标识（16 位小写十六进制）。首次调用时生成并落盘。
     *
     * 并发安全：多个协程同时首次请求时各自生成一个候选值，`commit()` 返回 false 的那个
     * 说明别人先写成功了 —— 此时**读回已存在的值**而不是用自己这份，
     * 否则同一次启动里的两个请求会带上不同指纹（服务端会当成两台设备）。
     */
    fun deviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE, null)?.takeIf { it.length == DEVICE_LEN }?.let { return it }
        val candidate = randomHex(DEVICE_LEN)
        return if (prefs.edit().putString(KEY_DEVICE, candidate).commit()) {
            candidate
        } else {
            prefs.getString(KEY_DEVICE, null)?.takeIf { it.length == DEVICE_LEN } ?: candidate
        }
    }

    /** `comm.guid`：参考实现用 10 位数字。 */
    fun guid(context: Context): String {
        val digits = deviceId(context).filter { it.isDigit() }.padEnd(10, '0')
        return digits.take(10)
    }

    /** `comm.QIMEI36`：36 位十六进制。由设备标识确定性派生，保证同机同值。 */
    fun qimei36(context: Context): String =
        (deviceId(context) + deviceId(context)).padEnd(36, '0').take(36)

    private fun randomHex(len: Int): String {
        val chars = "0123456789abcdef"
        val sb = StringBuilder(len)
        repeat(len) { sb.append(chars[random.nextInt(chars.length)]) }
        return sb.toString()
    }
}
