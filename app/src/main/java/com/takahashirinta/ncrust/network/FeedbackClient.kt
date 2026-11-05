/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.network

import android.os.Build
import com.takahashirinta.ncrust.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 反馈投递（**3.7.31 回移主线 4.0.11**）。
 *
 * ## 为什么要走自己的中转
 *
 * 客户端**不放**任何 Gitee / GitHub 令牌 —— 令牌打进 APK 等于公开。
 * 所以流程是：客户端 POST 一段 JSON 到 `arris.de5.net/api/feedback`
 * → Worker 用**服务端保存的令牌**在 `gitee.com/yaxiaiyuting/arris-feedback` 建 issue。
 * 选 Gitee 的理由就是国内可达性：GitHub API 在部分网络下要代理，反馈不该依赖这个。
 *
 * ## 报文只带"诊断必需"的字段
 *
 * 版本 / 机型 / 系统版本 / 语言 / 主题 / 音质档 / 是否登录（**布尔，不带任何凭证**）
 * / 用户写的描述与可选联系方式。**不采集**：cookie、令牌、曲目历史、文件路径、IMEI 等。
 *
 * ## LTS 移植差异
 *
 * 与主线逐字节相同，**只有一处**刻意不同：client 必须从 [AppHttp.builder] 出发。
 * LTS 有 `HttpClientDnsAuditTest` 扫描 `app/src/main`，出现裸 `OkHttpClient.Builder()`
 * 直接判红 —— 那条纪律在 LTS 里比主线更硬（本机 IPv6 不通是实测过的真问题）。
 */
object FeedbackClient {

    private const val ENDPOINT = "https://arris.de5.net/api/feedback"

    // 走 AppHttp：所有出网客户端都必须继承「IPv4 优先 DNS + 统一超时 + 连接池」，
    // 裸建 OkHttpClient 会让本机的 IPv6 不通问题在"发反馈"这条路上重演（HttpClientDnsAuditTest 把关）。
    private val client = AppHttp.builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** 诊断信息：全部来自公开 API 或用户设置，不含任何凭证。 */
    data class Diagnostics(
        val appVersion: String,
        val versionCode: Int,
        val androidRelease: String,
        val sdkInt: Int,
        val model: String,
        val brand: String,
        val language: String,
        val themeMode: String,
        val qualityIndex: Int,
        val loggedInNcm: Boolean,
        val loggedInQq: Boolean,
        val extra: String = "",
    )

    fun collect(
        language: String,
        themeMode: String,
        qualityIndex: Int,
        loggedInNcm: Boolean,
        loggedInQq: Boolean,
        extra: String = "",
    ): Diagnostics = Diagnostics(
        appVersion = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
        androidRelease = Build.VERSION.RELEASE.orEmpty(),
        sdkInt = Build.VERSION.SDK_INT,
        model = Build.MODEL.orEmpty(),
        brand = Build.BRAND.orEmpty(),
        language = language,
        themeMode = themeMode,
        qualityIndex = qualityIndex,
        loggedInNcm = loggedInNcm,
        loggedInQq = loggedInQq,
        extra = extra,
    )

    sealed interface Result {
        data class Sent(val issueUrl: String?) : Result

        /** 网络或服务端失败；[draft] 是可直接粘贴到剪贴板的全文，别让用户白写。 */
        data class Failed(val reason: String, val draft: String) : Result
    }

    /** 给「复制到剪贴板」兜底用的纯文本。 */
    fun draft(title: String, message: String, contact: String, d: Diagnostics): String = buildString {
        appendLine("## $title")
        appendLine()
        appendLine(message)
        if (contact.isNotBlank()) {
            appendLine()
            appendLine("联系方式：$contact")
        }
        appendLine()
        appendLine("---")
        appendLine("Arris ${d.appVersion} (${d.versionCode})")
        appendLine("Android ${d.androidRelease} (API ${d.sdkInt}) · ${d.brand} ${d.model}")
        appendLine("语言 ${d.language} · 主题 ${d.themeMode} · 音质档 ${d.qualityIndex}")
        appendLine("登录 ncm=${d.loggedInNcm} qm=${d.loggedInQq}")
        if (d.extra.isNotBlank()) appendLine("补充：${d.extra}")
    }

    suspend fun send(
        title: String,
        message: String,
        contact: String,
        d: Diagnostics,
    ): Result = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("title", title.take(120))
            put("message", message.take(4000))
            put("contact", contact.take(120))
            put("app", d.appVersion)
            put("code", d.versionCode)
            put("android", "${d.androidRelease} (API ${d.sdkInt})")
            put("device", "${d.brand} ${d.model}".trim())
            put("language", d.language)
            put("theme", d.themeMode)
            put("quality", d.qualityIndex)
            put("ncm", d.loggedInNcm)
            put("qq", d.loggedInQq)
            put("extra", d.extra.take(1000))
        }
        val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(ENDPOINT)
            .post(body)
            .header("User-Agent", "Arris/${d.appVersion}")
            .build()
        try {
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext Result.Failed("HTTP ${resp.code}", draft(title, message, contact, d))
                }
                val url = runCatching { JSONObject(text).optString("issue") }.getOrNull()
                Result.Sent(url?.takeIf { it.isNotBlank() })
            }
        } catch (e: Throwable) {
            Result.Failed(e.message ?: "网络异常", draft(title, message, contact, d))
        }
    }
}
