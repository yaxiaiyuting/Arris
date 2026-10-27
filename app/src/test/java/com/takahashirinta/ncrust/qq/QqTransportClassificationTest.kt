/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.8.0 · H12：`musicu` 的传输层事实**绑定到请求**，并发请求不得互相改写失败分类。
 */

package com.takahashirinta.ncrust.qq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H12 的回归契约：失败分类只读**这一次请求**的传输结果。
 *
 * ## 缺陷本体
 *
 * v3.2.0 起 `QqClient` 把每次请求的 HTTP 状态码写进单例字段 `lastHttpStatus`，
 * 而取链的失败分类是**在挂起调用返回之后**才去读它：
 *
 * ```
 * val response = QqClient.musicu(...) ?: classify(QqRejectionInput(httpStatus = QqClient.lastHttpStatus))
 * ```
 *
 * 并发下这等于「读别人的状态」。探针的时序是：vkey 拿到 HTTP403 → 在响应收尾处暂停 →
 * 另一条形如资料查询的 musicu 完成（HTTP200）→ 放行原请求 ⇒ 那个 403 被分类成
 * `NETWORK`（错过鉴权续期、直接进网络重试）。
 *
 * ## 现在的形状（本文件钉住的部分）
 *
 * `QqClient.execute` 返回 [QqMusicuOutcome]（HTTP 状态码 + 失败原因 + 响应体），
 * 分类由 [classifyMusicuTransportFailure] 从**那一个对象**算出。
 * 于是「两次请求各自分类」在纯逻辑层就是两个独立调用 —— 没有任何共享状态可以串味。
 *
 * 真网络/并发时序没法在 JVM 单测里重放（那需要打真实 `u.y.qq.com`），所以这里钉的是
 * **分类函数的纯契约** + `QqClient` 源码里不再出现「业务控制流读 last 字段」。
 */
class QqTransportClassificationTest {

    @Test
    fun `HTTP 403 是凭证过期而不是网络失败`() {
        val outcome = QqMusicuOutcome(payload = null, httpStatus = 403, transportError = "http=403")
        assertEquals(
            QqRejection.AUTH_EXPIRED,
            classifyMusicuTransportFailure(outcome, loggedIn = true),
        )
        assertEquals(
            "未登录时同样是「票被拒」，分类不因本地票据状态改变（续期另有闸门）",
            QqRejection.AUTH_EXPIRED,
            classifyMusicuTransportFailure(outcome, loggedIn = false),
        )
    }

    @Test
    fun `HTTP 401 也是凭证过期`() {
        assertEquals(
            QqRejection.AUTH_EXPIRED,
            classifyMusicuTransportFailure(
                QqMusicuOutcome(null, 401, "http=401"),
                loggedIn = true,
            ),
        )
    }

    @Test
    fun `HTTP 200 的空响应与异常都是网络类失败`() {
        assertEquals(
            "HTTP200 却解析不出业务对象（畸形 JSON）⇒ NETWORK",
            QqRejection.NETWORK,
            classifyMusicuTransportFailure(QqMusicuOutcome(null, 200, "malformed json"), loggedIn = true),
        )
        assertEquals(
            "连响应都没拿到（IOException / 超时）⇒ NETWORK",
            QqRejection.NETWORK,
            classifyMusicuTransportFailure(QqMusicuOutcome(null, null, "IOException: timeout"), loggedIn = true),
        )
    }

    @Test
    fun `两次请求的分类互不影响 —— 没有可被覆盖的共享状态`() {
        val vkeyFailure = QqMusicuOutcome(payload = null, httpStatus = 403, transportError = "http=403")
        val profileSuccess = QqMusicuOutcome(payload = org.json.JSONObject("{}"), httpStatus = 200, transportError = null)

        // 「后完成的那次是 200」不能把先完成那次 403 改判成网络失败。
        assertEquals(
            QqRejection.AUTH_EXPIRED,
            classifyMusicuTransportFailure(vkeyFailure, loggedIn = true),
        )
        assertEquals(
            "成功的那次不属于失败分类的输入（payload 非空 ⇒ 调用方根本不该调这个函数）",
            false,
            profileSuccess.failed,
        )
        assertEquals(
            "反方向同样不能串味：403 那次不会把 200 那次变成凭证问题",
            QqRejection.NETWORK,
            classifyMusicuTransportFailure(QqMusicuOutcome(null, 502, "http=502"), loggedIn = true),
        )
        // 再算一次第一次的结果：必须逐字节一致（纯函数，无记忆）。
        assertEquals(
            QqRejection.AUTH_EXPIRED,
            classifyMusicuTransportFailure(vkeyFailure, loggedIn = true),
        )
    }

    @Test
    fun `未初始化时返回的是失败而不是空成功`() {
        val outcome = QqMusicuOutcome.notInitialized()
        assertTrue("没有 context 也必须是「没拿到响应」", outcome.failed)
        assertNull(outcome.httpStatus)
        assertTrue(outcome.transportError.orEmpty().contains("not initialized"))
    }

    @Test
    fun `业务控制流不再读全局 last 字段`() {
        // 阳性对照：真的扫到了 QqApi。路径定位与 ArtistRouteContractTest 同一套（往上找 app/src/main/java）。
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: ".").absoluteFile
        var srcRoot: java.io.File? = null
        while (dir != null && srcRoot == null) {
            val candidate = java.io.File(dir, "app/src/main/java")
            srcRoot = when {
                candidate.isDirectory -> candidate
                dir.name == "app" && java.io.File(dir, "src/main/java").isDirectory -> java.io.File(dir, "src/main/java")
                else -> null
            }
            dir = dir.parentFile
        }
        val root = srcRoot ?: throw AssertionError("找不到 app/src/main/java")

        val offenders = mutableListOf<String>()
        var scannedFiles = 0
        root.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            scannedFiles++
            file.readLines().forEachIndexed { i, raw ->
                val code = raw.substringBefore("//")
                val trimmed = code.trim()
                if (trimmed.startsWith("*") || trimmed.startsWith("/*")) return@forEachIndexed
                if (code.contains("lastHttpStatus") || code.contains("lastTransportError")) {
                    offenders += "${file.name}:${i + 1}: ${raw.trim()}"
                }
            }
        }
        assertTrue("扫描没扫到任何文件 —— 路径写错了", scannedFiles > 50)
        assertEquals(
            "传输层状态必须走 QqMusicuOutcome（H12）。除 QqClient 自己（写它只供诊断日志）之外，" +
                "任何业务代码读这两个单例字段都是并发串味。违规行：\n" +
                offenders.joinToString("\n"),
            emptyList<String>(),
            offenders.filterNot { it.startsWith("QqClient.kt") },
        )
    }
}
