/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.1.0 · D：qm QRC 专用的 TripleDES 变体实现。
 *
 * ---------------------------------------------------------------------------
 * 这是本仓库里唯一一段「照着参考实现移植、而非自己写」的算法，理由必须写清楚：
 *
 * **qm 用的不是标准 3DES。** 实测（2026-09）：
 *   - `javax.crypto` 的 `DESede/ECB/NoPadding` 配同一个 24 字节密钥，
 *     解真实 QRC 密文得到的第一块是 `ee186317`，**不是 zlib 流头**（标准 DES 走不通）；
 *   - 参考实现 `luren-dc/QQMusicApi` 的纯 Python DES **同样通不过标准测试向量**
 *     （key=0123456789ABCDEF, pt=4E6F772069732074 应得 3FA40E8A984D4815，实得 FE6782F11080C6E6）。
 *
 * 差异点已定位到**密钥扩展的 PC-2**：它的密钥压缩表与标准完全相同，但取 D 半部时
 * `31 - (pc2[j] - 27)` 少减了 1（标准是 `- 28`），于是 D 的每一位都朝低位偏了一位，
 * 且 PC-2 里 D 的最后一个下标（27）溢出成恒 0 的填充位。
 * 这是个**错位而不是旋转**（末尾补 0、首位移出），所以它**无法**用「换一个标准密钥」
 * 来等价模拟 —— 只能把这份变体实现出来。数据通路（IP / F / S 盒 / IP^-1）与标准 DES 一致，
 * 已逐行对照；`f()` 与置换表由脚本从参考实现导出，避免手抄 512 个 S 盒数字出错。
 *
 * 验证方式不是「看起来对」：`QqDesTest` 用**参考实现加密出来的真实夹具**做逐字节比对
 * （加密侧在 Python，解密侧在 Kotlin），并额外覆盖标准测试向量不一致这一事实。
 * ---------------------------------------------------------------------------
 */

package com.takahashirinta.ncrust.qq

/**
 * QRC 专用的 TripleDES（QQ 客户端变体）。**纯逻辑、无 Android 依赖、JVM 可单测。**
 *
 * 只暴露 [decrypt]：本应用没有任何需要**加密** QRC 的场景，少一个出口就少一份被误用的可能。
 */
internal object QqDes {

    /** 低 4 位清零的掩码（0xFFFFFFF0）。旋转 28 位半区时用它把溢出的高位截掉。 */
    private const val MASK_HIGH28 = -16 // 0xFFFFFFF0 的有符号表示

    private val SBOX: Array<IntArray> = arrayOf(
        // S1
        intArrayOf(
            14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7,
            0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8,
            4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0,
            15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13,
        ),
        // S2
        intArrayOf(
            15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10,
            3, 13, 4, 7, 15, 2, 8, 15, 12, 0, 1, 10, 6, 9, 11, 5,
            0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15,
            13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9,
        ),
        // S3
        intArrayOf(
            10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8,
            13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1,
            13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7,
            1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12,
        ),
        // S4
        intArrayOf(
            7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15,
            13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9,
            10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4,
            3, 15, 0, 6, 10, 10, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14,
        ),
        // S5
        intArrayOf(
            2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9,
            14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6,
            4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14,
            11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3,
        ),
        // S6
        intArrayOf(
            12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11,
            10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8,
            9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6,
            4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13,
        ),
        // S7
        intArrayOf(
            4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1,
            13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6,
            1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2,
            6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12,
        ),
        // S8
        intArrayOf(
            13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7,
            1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2,
            7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8,
            2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11,
        ),
    )

    private val ROUND_SHIFT = intArrayOf(1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1)

    /** PC-1 的 C 半部（0 基、自 MSB 起算）——与标准 DES 相同。 */
    private val KEY_PERM_C = intArrayOf(
        56, 48, 40, 32, 24, 16, 8, 0, 57, 49, 41, 33, 25, 17, 9, 1,
        58, 50, 42, 34, 26, 18, 10, 2, 59, 51, 43, 35,
    )

    /** PC-1 的 D 半部——与标准 DES 相同。 */
    private val KEY_PERM_D = intArrayOf(
        62, 54, 46, 38, 30, 22, 14, 6, 61, 53, 45, 37, 29, 21, 13, 5,
        60, 52, 44, 36, 28, 20, 12, 4, 27, 19, 11, 3,
    )

    /** PC-2——**与标准 DES 相同**；偏移 bug 发生在使用它的那一行（见 [keySchedule]）。 */
    private val KEY_COMPRESSION = intArrayOf(
        13, 16, 10, 23, 0, 4, 2, 27, 14, 5, 20, 9, 22, 18, 11, 3,
        25, 7, 15, 6, 26, 19, 12, 1, 40, 51, 30, 36, 46, 54, 29, 39,
        50, 44, 32, 47, 43, 48, 38, 55, 33, 52, 45, 41, 49, 35, 28, 31,
    )


    /** 初始置换 IP：8 字节 → (s0, s1)。 */
    private fun initialPermutation(input: ByteArray): IntArray {
        val v0 = (((input[0].toInt() and 0xFF)) or (((input[1].toInt() and 0xFF)) shl 8)) or
            ((((input[2].toInt() and 0xFF)) shl 16) or (((input[3].toInt() and 0xFF)) shl 24))
        val v1 = (((input[4].toInt() and 0xFF)) or (((input[5].toInt() and 0xFF)) shl 8)) or
            ((((input[6].toInt() and 0xFF)) shl 16) or (((input[7].toInt() and 0xFF)) shl 24))

        val s0 = (
            (((v1 ushr 6) and 1) shl 0x1F) or
                (((v1 ushr 0xE) and 1) shl 0x1E) or
                (((v1 ushr 0x16) and 1) shl 0x1D) or
                (((v1 ushr 0x1E) and 1) shl 0x1C) or
                (((v0 ushr 6) and 1) shl 0x1B) or
                (((v0 ushr 0xE) and 1) shl 0x1A) or
                (((v0 ushr 0x16) and 1) shl 0x19) or
                (((v0 ushr 0x1E) and 1) shl 0x18) or
                (((v1 ushr 4) and 1) shl 0x17) or
                (((v1 ushr 0xC) and 1) shl 0x16) or
                (((v1 ushr 0x14) and 1) shl 0x15) or
                (((v1 ushr 0x1C) and 1) shl 0x14) or
                (((v0 ushr 4) and 1) shl 0x13) or
                (((v0 ushr 0xC) and 1) shl 0x12) or
                (((v0 ushr 0x14) and 1) shl 0x11) or
                (((v0 ushr 0x1C) and 1) shl 0x10) or
                (((v1 ushr 2) and 1) shl 0xF) or
                (((v1 ushr 0xA) and 1) shl 0xE) or
                (((v1 ushr 0x12) and 1) shl 0xD) or
                (((v1 ushr 0x1A) and 1) shl 0xC) or
                (((v0 ushr 2) and 1) shl 0xB) or
                (((v0 ushr 0xA) and 1) shl 0xA) or
                (((v0 ushr 0x12) and 1) shl 9) or
                (((v0 ushr 0x1A) and 1) shl 8) or
                (((v1 ushr 0) and 1) shl 7) or
                (((v1 ushr 8) and 1) shl 6) or
                (((v1 ushr 0x10) and 1) shl 5) or
                (((v1 ushr 0x18) and 1) shl 4) or
                (((v0 ushr 0) and 1) shl 3) or
                (((v0 ushr 8) and 1) shl 2) or
                (((v0 ushr 0x10) and 1) shl 1) or
                ((v0 ushr 0x18) and 1)
        )
        val s1 = (
            (((v1 ushr 7) and 1) shl 0x1F) or
                (((v1 ushr 0xF) and 1) shl 0x1E) or
                (((v1 ushr 0x17) and 1) shl 0x1D) or
                (((v1 ushr 0x1F) and 1) shl 0x1C) or
                (((v0 ushr 7) and 1) shl 0x1B) or
                (((v0 ushr 0xF) and 1) shl 0x1A) or
                (((v0 ushr 0x17) and 1) shl 0x19) or
                (((v0 ushr 0x1F) and 1) shl 0x18) or
                (((v1 ushr 5) and 1) shl 0x17) or
                (((v1 ushr 0xD) and 1) shl 0x16) or
                (((v1 ushr 0x15) and 1) shl 0x15) or
                (((v1 ushr 0x1D) and 1) shl 0x14) or
                (((v0 ushr 5) and 1) shl 0x13) or
                (((v0 ushr 0xD) and 1) shl 0x12) or
                (((v0 ushr 0x15) and 1) shl 0x11) or
                (((v0 ushr 0x1D) and 1) shl 0x10) or
                (((v1 ushr 3) and 1) shl 0xF) or
                (((v1 ushr 0xB) and 1) shl 0xE) or
                (((v1 ushr 0x13) and 1) shl 0xD) or
                (((v1 ushr 0x1B) and 1) shl 0xC) or
                (((v0 ushr 3) and 1) shl 0xB) or
                (((v0 ushr 0xB) and 1) shl 0xA) or
                (((v0 ushr 0x13) and 1) shl 9) or
                (((v0 ushr 0x1B) and 1) shl 8) or
                (((v1 ushr 1) and 1) shl 7) or
                (((v1 ushr 9) and 1) shl 6) or
                (((v1 ushr 0x11) and 1) shl 5) or
                (((v1 ushr 0x19) and 1) shl 4) or
                (((v0 ushr 1) and 1) shl 3) or
                (((v0 ushr 9) and 1) shl 2) or
                (((v0 ushr 0x11) and 1) shl 1) or
                ((v0 ushr 0x19) and 1)
        )
        return intArrayOf(s0, s1)
    }


    /** 逆初始置换 IP^-1。 */
    private fun inversePermutation(s0: Int, s1: Int): ByteArray {
        val data = ByteArray(8)
        data[7] = (
            (((s1 ushr 0x1C) and 1) shl 7) or
                (((s0 ushr 0x1C) and 1) shl 6) or
                (((s1 ushr 0x14) and 1) shl 5) or
                (((s0 ushr 0x14) and 1) shl 4) or
                (((s1 ushr 0xC) and 1) shl 3) or
                (((s0 ushr 0xC) and 1) shl 2) or
                (((s1 ushr 4) and 1) shl 1) or
                ((s0 ushr 4) and 1)
        ).toByte()
        data[6] = (
            (((s1 ushr 0x1D) and 1) shl 7) or
                (((s0 ushr 0x1D) and 1) shl 6) or
                (((s1 ushr 0x15) and 1) shl 5) or
                (((s0 ushr 0x15) and 1) shl 4) or
                (((s1 ushr 0xD) and 1) shl 3) or
                (((s0 ushr 0xD) and 1) shl 2) or
                (((s1 ushr 5) and 1) shl 1) or
                ((s0 ushr 5) and 1)
        ).toByte()
        data[5] = (
            (((s1 ushr 0x1E) and 1) shl 7) or
                (((s0 ushr 0x1E) and 1) shl 6) or
                (((s1 ushr 0x16) and 1) shl 5) or
                (((s0 ushr 0x16) and 1) shl 4) or
                (((s1 ushr 0xE) and 1) shl 3) or
                (((s0 ushr 0xE) and 1) shl 2) or
                (((s1 ushr 6) and 1) shl 1) or
                ((s0 ushr 6) and 1)
        ).toByte()
        data[4] = (
            (((s1 ushr 0x1F) and 1) shl 7) or
                (((s0 ushr 0x1F) and 1) shl 6) or
                (((s1 ushr 0x17) and 1) shl 5) or
                (((s0 ushr 0x17) and 1) shl 4) or
                (((s1 ushr 0xF) and 1) shl 3) or
                (((s0 ushr 0xF) and 1) shl 2) or
                (((s1 ushr 7) and 1) shl 1) or
                ((s0 ushr 7) and 1)
        ).toByte()
        data[3] = (
            (((s1 ushr 0x18) and 1) shl 7) or
                (((s0 ushr 0x18) and 1) shl 6) or
                (((s1 ushr 0x10) and 1) shl 5) or
                (((s0 ushr 0x10) and 1) shl 4) or
                (((s1 ushr 8) and 1) shl 3) or
                (((s0 ushr 8) and 1) shl 2) or
                (((s1 ushr 0) and 1) shl 1) or
                ((s0 ushr 0) and 1)
        ).toByte()
        data[2] = (
            (((s1 ushr 0x19) and 1) shl 7) or
                (((s0 ushr 0x19) and 1) shl 6) or
                (((s1 ushr 0x11) and 1) shl 5) or
                (((s0 ushr 0x11) and 1) shl 4) or
                (((s1 ushr 9) and 1) shl 3) or
                (((s0 ushr 9) and 1) shl 2) or
                (((s1 ushr 1) and 1) shl 1) or
                ((s0 ushr 1) and 1)
        ).toByte()
        data[1] = (
            (((s1 ushr 0x1A) and 1) shl 7) or
                (((s0 ushr 0x1A) and 1) shl 6) or
                (((s1 ushr 0x12) and 1) shl 5) or
                (((s0 ushr 0x12) and 1) shl 4) or
                (((s1 ushr 0xA) and 1) shl 3) or
                (((s0 ushr 0xA) and 1) shl 2) or
                (((s1 ushr 2) and 1) shl 1) or
                ((s0 ushr 2) and 1)
        ).toByte()
        data[0] = (
            (((s1 ushr 0x1B) and 1) shl 7) or
                (((s0 ushr 0x1B) and 1) shl 6) or
                (((s1 ushr 0x13) and 1) shl 5) or
                (((s0 ushr 0x13) and 1) shl 4) or
                (((s1 ushr 0xB) and 1) shl 3) or
                (((s0 ushr 0xB) and 1) shl 2) or
                (((s1 ushr 3) and 1) shl 1) or
                ((s0 ushr 3) and 1)
        ).toByte()
        return data
    }


    /** 轮函数 F。 */
    private fun f(state: Int, key: IntArray): Int {
        val t1 = (
            ((state and 1) shl 0x1F) or
                ((state and 0xF8000000.toInt()) ushr 1) or
                ((state and 0x1F800000) ushr 3) or
                ((state and 0x1F80000) ushr 5) or
                ((state and 0x1F8000) ushr 7)
        )
        val t2 = (
            ((state and 0x1F800) shl 0xF) or
                ((state and 0x1F80) shl 0xD) or
                ((state and 0x1F8) shl 0xB) or
                ((state and 0x1F) shl 9) or
                ((state and 0x80000000.toInt()) ushr 0x17)
        )

        val k0 = ((t1 ushr 24) and 0xFF) xor key[0]
        val k1 = ((t1 ushr 16) and 0xFF) xor key[1]
        val k2 = ((t1 ushr 8) and 0xFF) xor key[2]
        val k3 = ((t2 ushr 24) and 0xFF) xor key[3]
        val k4 = ((t2 ushr 16) and 0xFF) xor key[4]
        val k5 = ((t2 ushr 8) and 0xFF) xor key[5]

        val sboxOut = (SBOX[0][sboxBit(k0 ushr 2)] shl 28) or
            (SBOX[1][sboxBit((((k0 and 0x03)) shl 4) or (k1 ushr 4))] shl 24) or
            (SBOX[2][sboxBit((((k1 and 0x0F)) shl 2) or (k2 ushr 6))] shl 20) or
            (SBOX[3][sboxBit(k2 and 0x3F)] shl 16) or
            (SBOX[4][sboxBit(k3 ushr 2)] shl 12) or
            (SBOX[5][sboxBit((((k3 and 0x03)) shl 4) or (k4 ushr 4))] shl 8) or
            (SBOX[6][sboxBit((((k4 and 0x0F)) shl 2) or (k5 ushr 6))] shl 4) or
            SBOX[7][sboxBit(k5 and 0x3F)]

        // 参考实现在这里是**重新赋值给 `state`**，后面的置换用的是 S 盒的输出。
        // 用新名字 `sboxOut` 时必须把 return 里的 state 全换成它 —— 否则置换的还是旧 state，
        // 结果会是一个「看起来像密文」的错值（曾经就是这么错了 4 个字节）。
        return (
            (((sboxOut ushr 0x10) and 1) shl 0x1F) or
                (((sboxOut ushr 0x19) and 1) shl 0x1E) or
                (((sboxOut ushr 0xC) and 1) shl 0x1D) or
                (((sboxOut ushr 0xB) and 1) shl 0x1C) or
                (((sboxOut ushr 3) and 1) shl 0x1B) or
                (((sboxOut ushr 0x14) and 1) shl 0x1A) or
                (((sboxOut ushr 4) and 1) shl 0x19) or
                (((sboxOut ushr 0xF) and 1) shl 0x18) or
                (((sboxOut ushr 0x1F) and 1) shl 0x17) or
                (((sboxOut ushr 0x11) and 1) shl 0x16) or
                (((sboxOut ushr 9) and 1) shl 0x15) or
                (((sboxOut ushr 6) and 1) shl 0x14) or
                (((sboxOut ushr 0x1B) and 1) shl 0x13) or
                (((sboxOut ushr 0xE) and 1) shl 0x12) or
                (((sboxOut ushr 1) and 1) shl 0x11) or
                (((sboxOut ushr 0x16) and 1) shl 0x10) or
                (((sboxOut ushr 0x1E) and 1) shl 0xF) or
                (((sboxOut ushr 0x18) and 1) shl 0xE) or
                (((sboxOut ushr 8) and 1) shl 0xD) or
                (((sboxOut ushr 0x12) and 1) shl 0xC) or
                (((sboxOut ushr 0) and 1) shl 0xB) or
                (((sboxOut ushr 5) and 1) shl 0xA) or
                (((sboxOut ushr 0x1D) and 1) shl 9) or
                (((sboxOut ushr 0x17) and 1) shl 8) or
                (((sboxOut ushr 0xD) and 1) shl 7) or
                (((sboxOut ushr 0x13) and 1) shl 6) or
                (((sboxOut ushr 2) and 1) shl 5) or
                (((sboxOut ushr 0x1A) and 1) shl 4) or
                (((sboxOut ushr 0xA) and 1) shl 3) or
                (((sboxOut ushr 0x15) and 1) shl 2) or
                (((sboxOut ushr 0x1C) and 1) shl 1) or
                ((sboxOut ushr 7) and 1)
        )
    }

    /**
     * 密钥扩展。**`decrypt = true` 时轮密钥顺序取反**（`togen = 15 - i`），
     * 与参考实现一致：3DES 的「解密」= 用同一套子密钥逆序跑数据通路。
     */
    private fun keySchedule(key: ByteArray, decrypt: Boolean): Array<IntArray> {
        val schedule = Array(16) { IntArray(6) }

        val v0 = (key[0].toInt() and 0xFF) or ((key[1].toInt() and 0xFF) shl 8) or
            ((key[2].toInt() and 0xFF) shl 16) or ((key[3].toInt() and 0xFF) shl 24)
        val v1 = (key[4].toInt() and 0xFF) or ((key[5].toInt() and 0xFF) shl 8) or
            ((key[6].toInt() and 0xFF) shl 16) or ((key[7].toInt() and 0xFF) shl 24)

        var c = 0
        var d = 0
        for (i in KEY_PERM_C.indices) {
            val b = KEY_PERM_C[i]
            val bit = if (b < 32) (v0 ushr (31 - b)) and 1 else (v1 ushr (63 - b)) and 1
            c = c or (bit shl (31 - i))
        }
        for (i in KEY_PERM_D.indices) {
            val b = KEY_PERM_D[i]
            val bit = if (b < 32) (v0 ushr (31 - b)) and 1 else (v1 ushr (63 - b)) and 1
            d = d or (bit shl (31 - i))
        }

        for (i in 0 until 16) {
            val s = ROUND_SHIFT[i]
            c = ((c shl s) or (c ushr (28 - s))) and MASK_HIGH28
            d = ((d shl s) or (d ushr (28 - s))) and MASK_HIGH28

            val round = if (decrypt) 15 - i else i
            for (j in 0 until 6) schedule[round][j] = 0

            for (j in 0 until 24) {
                // C 半部：标准取法（31 - pc2[j]）
                val bit = (c ushr (31 - KEY_COMPRESSION[j])) and 1
                schedule[round][j / 8] = schedule[round][j / 8] or (bit shl (7 - (j % 8)))
            }
            for (j in 24 until 48) {
                // ⚠️ QQ 的 PC-2 偏移 bug：这里是 `- 27`，标准 DES 是 `- 28`。
                // 改成 28 会让所有 QRC 解密失败（KeyScheduleTest 有反例断言守着这一点）。
                val bit = (d ushr (31 - (KEY_COMPRESSION[j] - 27))) and 1
                schedule[round][j / 8] = schedule[round][j / 8] or (bit shl (7 - (j % 8)))
            }
        }
        return schedule
    }

    /** 单个 8 字节块的 DES 数据通路（IP → 16 轮 → IP^-1）。与标准 DES 相同。 */
    private fun cryptBlock(input: ByteArray, key: Array<IntArray>): ByteArray {
        val ip = initialPermutation(input)
        var s0 = ip[0]
        var s1 = ip[1]
        for (idx in 0 until 15) {
            val previousS1 = s1
            s1 = f(s1, key[idx]) xor s0
            s0 = previousS1
        }
        s0 = f(s1, key[15]) xor s0
        return inversePermutation(s0, s1)
    }

    /** 与参考实现同源的位重排。 */
    private fun sboxBit(a: Int): Int = (a and 32) or ((a and 31) ushr 1) or ((a and 1) shl 4)

    /** QRC 的固定 24 字节密钥（三个 8 字节 DES 密钥）。与 songmid 无关，实测如此。 */
    private val QRC_KEY = "!@#)(*$%123ZXC!@!@#)(NHL".toByteArray(Charsets.UTF_8)

    /**
     * 解密一段 QRC 密文（长度必须是 8 的倍数；不足一块的尾字节原样带出）。
     *
     * 三次数据通路按参考实现的顺序：**D(K3) → E(K2) → D(K1)**。
     * 没有「去填充」这一步 —— QRC 的填充在 zlib 流之后，由解压器读到流结束自然忽略。
     */
    fun decrypt(data: ByteArray, key: ByteArray = QRC_KEY): ByteArray {
        require(key.size == 24) { "3DES key must be 24 bytes, got ${key.size}" }
        val k3 = keySchedule(key.copyOfRange(16, 24), decrypt = true)
        val k2 = keySchedule(key.copyOfRange(8, 16), decrypt = false)
        val k1 = keySchedule(key.copyOfRange(0, 8), decrypt = true)

        val out = ByteArray(data.size)
        var offset = 0
        while (offset + 8 <= data.size) {
            var block = ByteArray(8)
            System.arraycopy(data, offset, block, 0, 8)
            block = cryptBlock(block, k3)
            block = cryptBlock(block, k2)
            block = cryptBlock(block, k1)
            System.arraycopy(block, 0, out, offset, 8)
            offset += 8
        }
        // 不足一块的尾巴（正常数据不会出现）原样带出，交给上层判为解密失败。
        while (offset < data.size) {
            out[offset] = data[offset]
            offset++
        }
        return out
    }

    /**
     * **仅供单测**：跑一次单 DES 数据通路（8 字节块）。
     *
     * 存在的唯一理由是让「移植是否忠实」可以被逐块验证：把参考实现的单 DES 输出
     * （key=0123456789ABCDEF, pt=4E6F772069732074 → FE6782F11080C6E6，**故意不是**
     * 标准 DES 的 3FA40E8A984D4815）钉进单测，一旦有人「顺手把 PC-2 改回标准」，
     * 这条用例会立刻红。生产代码不要调它。
     */
    internal fun singleDesBlockForTest(block: ByteArray, key8: ByteArray, encrypt: Boolean): ByteArray {
        require(block.size == 8) { "block must be 8 bytes" }
        require(key8.size == 8) { "key must be 8 bytes" }
        return cryptBlock(block, keySchedule(key8, decrypt = !encrypt))
    }

    /** **仅供单测**：3DES 端到端（与 [decrypt] 同一路径，但允许自定义密钥）。 */
    internal fun tripleDesForTest(block: ByteArray, key: ByteArray, decrypt: Boolean): ByteArray {
        require(key.size == 24) { "3DES key must be 24 bytes, got ${key.size}" }
        val k1 = keySchedule(key.copyOfRange(0, 8), decrypt = decrypt)
        val k2 = keySchedule(key.copyOfRange(8, 16), decrypt = !decrypt)
        val k3 = keySchedule(key.copyOfRange(16, 24), decrypt = decrypt)
        var b = block
        if (decrypt) {
            b = cryptBlock(b, k3); b = cryptBlock(b, k2); b = cryptBlock(b, k1)
        } else {
            b = cryptBlock(b, k1); b = cryptBlock(b, k2); b = cryptBlock(b, k3)
        }
        return b
    }
}
