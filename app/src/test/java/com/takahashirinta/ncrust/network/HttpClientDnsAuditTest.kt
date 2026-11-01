package com.takahashirinta.ncrust.network

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网络客户端必须从 [AppHttp] 出发（v3.8.16）。
 *
 * ## 为什么用源码扫描
 *
 * 「本机 IPv6 到外网完全不通」这个坑，我们手动补了**六次** dns：
 * ncm 取链 → 音频流 → B站 → 检查更新 → 应用内下载 → 封面/艺人图。
 * 每次都是新建了一个裸 `OkHttpClient.Builder()`。单测抓不到（每个客户端只测自己的逻辑），
 * 只能靠人记 —— 记不住。
 *
 * 现在规则收紧成两条：
 *  1. `app/src/main` 里**不允许**出现裸的 `OkHttpClient.Builder()`，只能用 `AppHttp.builder()`；
 *  2. 测试里给客户端的注入口（`clientForTest` 之类）不受此限。
 *
 * 这条测试就是这套约定的看门人：加新客户端时如果绕过 AppHttp，CI 立刻红。
 */
class HttpClientDnsAuditTest {

    @Test
    fun `主源码里不允许裸建 OkHttpClient`() {
        val root = File("src/main/java/com/takahashirinta/ncrust")
        assertTrue("找不到源码目录：${root.absolutePath}", root.isDirectory)

        val offenders = mutableListOf<String>()
        root.walkTopDown().filter { it.extension == "kt" && it.name != "AppHttp.kt" }.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                val trimmed = line.trimStart()
                if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) return@forEachIndexed
                // v3.8.18：也抓 `OkHttpClient()` 与 `OkHttpClient().newBuilder()` ——
                // TTML 歌词客户端就是从这个缝里漏过去的。
                if (line.contains("OkHttpClient.Builder()") ||
                    line.contains("OkHttpClient()") ||
                    line.contains("OkHttpClient().newBuilder()")
                ) {
                    offenders += "${file.name}:${index + 1}"
                }
            }
        }
        assertTrue(
            "这些地方绕过了 AppHttp（裸建 OkHttpClient 会丢掉 IPv4 优先/超时/连接池）：$offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `AppHttp 默认就带 IPv4 优先与超时`() {
        assertTrue(AppHttp.builder().build().dns is AddressPreference.IPv4FirstDns)
    }
}
