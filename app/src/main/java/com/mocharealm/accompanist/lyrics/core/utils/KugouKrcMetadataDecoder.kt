/*
 * NeriPlayer 歌词模块（Apache-2.0）—— 移植自 https://github.com/cwuom/accompanist-lyrics-core
 * 与原项目 https://github.com/cwuom/accompanist-lyrics-ui（Apache License 2.0）。
 * 本文件在 Arris（GPLv3）中的修改：KMP → 单平台 Android、去除 material3 依赖。
 * 原版权：Copyright (c) NeriPlayer contributors，Apache License 2.0。
 */

package com.mocharealm.accompanist.lyrics.core.utils

import org.json.JSONArray
import org.json.JSONObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * 酷狗 KRC 的 `[language:…]` 头解码（4.0.0 移植）。
 *
 * 与上游的差异：上游用 kotlinx.serialization 解析这段 JSON；我们只有这一处需要
 * JSON，用 Android 自带的 `org.json` 即可，省掉一个编译器插件与运行时依赖。
 */
object KugouKrcMetadataDecoder {
    data class Metadata(
        val translations: List<String> = emptyList(),
        val phonetics: List<List<String>> = emptyList()
    )

    @OptIn(ExperimentalEncodingApi::class)
    fun decode(languageHeader: String?): Metadata {
        if (languageHeader.isNullOrBlank()) return Metadata()
        val contentBase64 = languageHeader
            .substringAfter("[language:")
            .substringBeforeLast("]")
            .trim()
        if (contentBase64.isEmpty()) return Metadata()
        return try {
            parseJsonContent(Base64.decode(contentBase64).decodeToString())
        } catch (e: Exception) {
            Metadata()
        }
    }

    private fun parseJsonContent(jsonStr: String): Metadata {
        return try {
            val contentArray = JSONObject(jsonStr).optJSONArray("content") ?: return Metadata()
            val lyricLines = mutableListOf<String>()
            val pronLines = mutableListOf<List<String>>()

            for (i in 0 until contentArray.length()) {
                val obj = contentArray.optJSONObject(i) ?: continue
                val type = obj.optInt("type", -1)             // 1 = 翻译行，0 = 音译音节
                val rows = obj.optJSONArray("lyricContent") ?: continue

                when (type) {
                    1 -> for (r in 0 until rows.length()) {
                        val parts = rows.optJSONArray(r) ?: continue
                        lyricLines += (0 until parts.length()).joinToString("") { parts.optString(it, "") }
                    }
                    0 -> for (r in 0 until rows.length()) {
                        val syllables = rows.optJSONArray(r) ?: continue
                        pronLines += (0 until syllables.length()).map { s ->
                            val parts = syllables.optJSONArray(s)
                            if (parts == null) "" else (0 until parts.length()).joinToString("") { parts.optString(it, "") }
                        }
                    }
                }
            }
            Metadata(translations = lyricLines, phonetics = pronLines)
        } catch (e: Exception) {
            Metadata()
        }
    }
}
