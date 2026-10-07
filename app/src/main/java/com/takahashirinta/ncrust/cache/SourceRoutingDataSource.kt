/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.2.4 · P0：按 URI 选数据源（B 站媒体一个身份，其余音源逐字节不变）。
 */

package com.takahashirinta.ncrust.cache

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.takahashirinta.ncrust.bili.BiliCdn
import java.io.IOException

/**
 * v3.2.4 · P0：**按 URI 逐次选数据源**的转发层。
 *
 * ## 为什么必须自己写这一层（而不是继续用 `ResolvingDataSource`）
 *
 * v3.1.0 用 `ResolvingDataSource` 往 `DataSpec` 里塞 `Referer`，那是 media3 推荐的
 * 「打开之前改写 DataSpec」的写法，对 **header** 是对的。但 P0 的根因是 **`User-Agent`**，
 * 而 UA 有两个 media3 层面的硬约束：
 *
 * 1. **`DefaultHttpDataSource.userAgent` 是 `final` 字段**，只能由
 *    `DefaultHttpDataSource.Factory.setUserAgent(...)` 给；
 * 2. 它在 `makeConnection` 里**最后**才写进连接（实测 media3 1.5.0 的 bytecode：
 *    偏移 166-182 写 UA，晚于 69-139 写 `dataSpec.httpRequestHeaders`）⇒
 *    **`DataSpec` 里的 `User-Agent` 会被工厂的值覆盖**。
 *
 * 所以「只改 DataSpec」这条路在 UA 上是死的：必须按 URI **换一个数据源实例**。
 * 本类就是那个开关 —— 两个上游都是 `DefaultDataSource`，**唯一的差别**是内层
 * `DefaultHttpDataSource.Factory` 有没有 `setUserAgent`：
 *
 * | 分支 | 上游 | header | UA |
 * |---|---|---|---|
 * | B 站媒体 | `DefaultDataSource(DefaultHttpDataSource(userAgent = BiliCdn.USER_AGENT))` | `BiliCdn.requestHeaders()`（Referer + UA） | 桌面 Chrome UA（实测 206） |
 * | 其余 | `DefaultDataSource(DefaultHttpDataSource())` | **一个字节都不加** | media3 默认（与 v3.2.3 逐字相同） |
 *
 * ## 判据只有一处
 *
 * `open` 里问 [BiliCdn.isBiliMedia]（域名白名单 ∪ 取链见过的 host），
 * 选源与加头都用**同一个答案** —— 铁律 26：对称位置的保护必须对称。
 * 绝不允许「选源问 A 判据、加头问 B 判据」：那正是 v3.1.0 的
 * 「Referer 加了、UA 没加」的形状。
 *
 * ## 契约（与 media3 的 `DataSource` 一致）
 *
 * - `open` 之外的调用（`read` / `getUri` / `getResponseHeaders` / `close`）一律转发给
 *   **上一次 `open` 选中的那个**；media3 的单线程使用模型保证它们不会错配。
 * - `addTransferListener` 在创建时对**两个**上游都挂一次（否则监听器会随选源漂移）。
 * - `open` 抛 `IOException` 时**原样上抛**，不吞、不重试：失败语义由 ExoPlayer 决定
 *   （铁律 5：失败处理必须有界）。
 */
@OptIn(UnstableApi::class)
internal class SourceRoutingDataSource(
    private val plain: DataSource,
    private val bili: DataSource,
) : DataSource {

    private var active: DataSource = plain

    override fun addTransferListener(transferListener: TransferListener) {
        plain.addTransferListener(transferListener)
        bili.addTransferListener(transferListener)
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        val useBili = BiliCdn.isBiliMedia(dataSpec.uri.host)
        active = if (useBili) bili else plain
        val spec = if (useBili) dataSpec.withRequestHeaders(BiliCdn.requestHeaders()) else dataSpec
        return active.open(spec)
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        active.read(buffer, offset, length)

    override fun getUri(): Uri? = active.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active.responseHeaders

    @Throws(IOException::class)
    override fun close() = active.close()

    /** 工厂。两个上游都是 `DataSource.Factory`（生产上传的是两个 `DefaultDataSource.Factory`）。 */
    class Factory(
        private val plain: DataSource.Factory,
        private val bili: DataSource.Factory,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            SourceRoutingDataSource(plain.createDataSource(), bili.createDataSource())
    }
}
