/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.8.0 · H12：把 `musicu` 的传输层结果**绑到请求上**，不再读全局 last 字段。
 */

package com.takahashirinta.ncrust.qq

import org.json.JSONObject

/**
 * 一次 `musicu.fcg` 调用的**请求级**结果（v3.8.0 · H12）。
 *
 * ## 为什么不能再用「全局 last 字段」
 *
 * v3.2.0 起 [QqClient] 把每次请求的传输层事实写进 `lastHttpStatus` / `lastTransportError`
 * 这两个**单例字段**，而取链的失败分类是**在挂起调用返回之后**才去读它们：
 *
 * ```kotlin
 * val response = QqClient.musicu(...) ?: return VkeyBatch(
 *     transportRejection = classifyQqRejection(
 *         QqRejectionInput(transportFailed = true, httpStatus = QqClient.lastHttpStatus, ...)
 *     ), ...)
 * ```
 *
 * 单线程下这是对的，**并发下不是**：QQ 侧同时会跑资料/歌单/歌词/其它取链请求。
 * 探针复现的时序是「vkey 拿到 HTTP 403 → 在响应收尾处暂停 → 一条资料形态的
 * musicu 完成（HTTP 200）→ 放行原请求」，结果是那次 403 被分类成 `NETWORK`
 * 而不是 `AUTH_EXPIRED` ⇒ 错过鉴权续期，直接进网络重试。反向污染同样成立。
 *
 * 修法：`execute()` 返回本对象，**失败分类只读自己这一份**。
 * 全局 last 字段保留（诊断日志仍在用），但**业务控制流不得再读它**。
 *
 * @property payload 响应里的业务对象（`req` 或 module 那个 key）；`null` = 这次请求失败。
 * @property httpStatus 本次响应的 HTTP 状态码；`null` = 没拿到响应（连接/超时/畸形）。
 * @property transportError 本次失败的一句话原因（**只进日志/诊断**，不参与分类）。
 */
data class QqMusicuOutcome(
    val payload: JSONObject?,
    val httpStatus: Int?,
    val transportError: String?,
) {
    /** 这次调用有没有拿到可解析的响应体。 */
    val failed: Boolean get() = payload == null

    internal companion object {
        /** 连 `appContext` 都没有（进程刚起、`init` 还没跑）—— 与「网络失败」区分开。 */
        fun notInitialized(): QqMusicuOutcome =
            QqMusicuOutcome(payload = null, httpStatus = null, transportError = "not initialized")
    }
}

/**
 * 传输层失败 → 拒绝分类（纯函数，JVM 可单测）。
 *
 * 判据只有两样，且都来自**这一次请求**的 [QqMusicuOutcome]：
 * HTTP 401/403 = 凭证过期（[QqRejection.AUTH_EXPIRED]），其余 = 网络/畸形
 * （[QqRejection.NETWORK]）。真正的分派仍在 [classifyQqRejection] 一处。
 */
fun classifyMusicuTransportFailure(outcome: QqMusicuOutcome, loggedIn: Boolean): QqRejection =
    classifyQqRejection(
        QqRejectionInput(
            transportFailed = true,
            httpStatus = outcome.httpStatus,
            loggedIn = loggedIn,
        )
    )
