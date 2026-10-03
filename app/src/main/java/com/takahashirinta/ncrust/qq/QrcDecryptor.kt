/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.1.0 · D：qm QRC 歌词解密。
 */

package com.takahashirinta.ncrust.qq

import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.zip.InflaterInputStream

/**
 * QRC 歌词解密（v2.1.0 · D）。
 *
 * ## 算法（2026-09 对真实响应核实，不是文档推断）
 *
 * `music.musichallSong.PlayLyricInfo` 在 `crypt=1` 时把 `lyric`/`trans`/`roma` 三个字段
 * 都返回成**十六进制字符串**，解出来是：
 *
 * ```
 * hex 字符串 → 字节 → TripleDES（QQ 变体）解密 → zlib 解压 → UTF-8 文本
 * ```
 *
 * - **密钥是固定的 24 字节常量**（三个 8 字节 DES 密钥），与 songmid 无关：
 *   `!@#)(*$%123ZXC!@!@#)(NHL`。网上流传的若干版本会按 mid 派生密钥，实测**不需要**。
 * - **不是标准 3DES**，所以走 [QqDes] 而不是 `javax.crypto`。实测依据与差异定位见 [QqDes]
 *   的文件头注释；简单说：标准 `DESede/ECB/NoPadding` 解真实密文得到的第一块不是 zlib 流头，
 *   参考实现的密钥扩展里有一处 QQ 特有的 PC-2 偏移。
 * - **不做 PKCS 去填充**：加密侧在 zlib 流之后补过填充字节，但 zlib 流自带结束标记，
 *   解压器读到流结束就会停下、忽略尾部多余字节（Python `zlib.decompress` 与 Java [Inflater] 同）。
 * - 三层文本的**格式不同**：`lyric` 与 `roma` 是 QRC 的 XML（逐字），
 *   `trans` 是**行级 LRC**（没有逐字）。解析交给 [QrcParser]，本类只负责解密。
 */
object QrcDecryptor {

    private const val TAG = "QrcDecryptor"

    /**
     * 解密一段十六进制的 QRC 数据。任何一步失败都返回 null（不抛异常）：
     * 歌词拿不到是常态（无版权、纯音乐、服务端改格式），上层按「没有歌词」处理即可，
     * 不该让一次歌词失败冒泡成崩溃。
     */
    fun decrypt(hex: String?): String? {
        if (hex.isNullOrEmpty()) return null
        return try {
            val cipherBytes = hexToBytes(hex) ?: return null
            if (cipherBytes.isEmpty() || cipherBytes.size % 8 != 0) {
                Log.w(TAG, "cipher length not block-aligned: ${cipherBytes.size}")
                return null
            }
            val plain = QqDes.decrypt(cipherBytes)
            inflate(plain)
        } catch (e: Exception) {
            // 包含 DataFormatException / OOM / 数组越界等一切异常 —— 歌词失败不该影响播放。
            Log.w(TAG, "qrc decrypt failed", e)
            null
        }
    }

    /**
     * zlib 解压。
     *
     * 用 [InflaterInputStream] 而不是手写 `Inflater` 循环：本格式的密文在 zlib 流之后还有
     * 补齐到 8 字节边界的填充字节，手写循环在「流已结束但缓冲区里还有尾字节」这一档上
     * 会少读最后一个字节（实测：解出来的 XML 少了结尾的换行）。JDK 的流实现把
     * 「读到流结束、忽略尾部多余字节」这件事做对了，也没有额外的分配。
     */
    private fun inflate(data: ByteArray): String? {
        val out = java.io.ByteArrayOutputStream(data.size * 4)
        InflaterInputStream(java.io.ByteArrayInputStream(data)).use { input ->
            val buf = ByteArray(8 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
        }
        if (out.size() == 0) return null
        return out.toString(Charsets.UTF_8.name())
    }

    /** 十六进制 → 字节。奇数长度或含非十六进制字符时返回 null。 */
    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
