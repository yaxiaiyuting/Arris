package com.takahashirinta.ncrust.network

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自建 OkHttpClient 必须显式 IPv4 优先（v3.8.16）。
 *
 * ## 为什么用源码扫描，而不是逐个单测
 *
 * 这条坑已经踩了 **六次**：ncm 取链（3.8.1）、音频流（3.8.9）、B站（3.8.10）、
 * 检查更新（3.8.11）、应用内下载（3.8.13）、封面/艺人/专辑图与 QQ 链路（3.8.16）。
 * 每次都是「新建了一个 OkHttpClient，忘了加 dns」——单测抓不到，因为每个客户端
 * 各自的测试都只测自己的逻辑。
 *
 * 真机事实：本机 Wi-Fi 是双栈，但 **IPv6 到外网完全不通**（IPv4 200 / IPv6 15s 超时）。
 * 系统解析把 IPv6 排前面，于是任何没设 `dns` 的客户端都会先把预算耗死在死地址上。
 *
 * 所以这里直接扫源码：`OkHttpClient.Builder()` 后面必须跟 `.dns(...)`。
 * 白名单里的文件是在**测试里构造假客户端**用的，不进入生产链路。
 */
class HttpClientDnsAuditTest {

    private val allowedWithoutDns = setOf(
        // 单测里给客户端的传输层注入口，不参与生产请求
        "UpdateService.kt",
    )

    @Test
    fun `每个自建 OkHttpClient 都显式指定了 DNS`() {
        val root = File("src/main/java/com/takahashirinta/ncrust")
        assertTrue("找不到源码目录：${root.absolutePath}", root.isDirectory)

        val offenders = mutableListOf<String>()
        root.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            if (file.name in allowedWithoutDns) return@forEach
            val text = file.readText()
            var index = text.indexOf("OkHttpClient.Builder()")
            while (index >= 0) {
                val lineStart = text.lastIndexOf('\n', index) + 1
                val line = text.substring(lineStart, text.indexOf('\n', index).let { if (it < 0) text.length else it })
                // 跳过注释里出现的写法
                if (!line.trimStart().startsWith("*") && !line.trimStart().startsWith("//")) {
                    // 链式配置里常夹着长注释（RetrofitClient 那条主线客户端就有一大段），窗口放宽到 3000 字符
                    val window = text.substring(index, minOf(text.length, index + 3000))
                    if (!window.contains(".dns(")) {
                        val lineNo = text.take(index).count { it == '\n' } + 1
                        offenders += "${file.name}:$lineNo"
                    }
                }
                index = text.indexOf("OkHttpClient.Builder()", index + 1)
            }
        }
        assertTrue(
            "这些 OkHttpClient 没有 .dns(...)，本机 IPv6 不通时会把请求耗到超时：$offenders",
            offenders.isEmpty(),
        )
    }
}
