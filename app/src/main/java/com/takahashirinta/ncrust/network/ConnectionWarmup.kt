/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.1.0 · P1-B：连接预热（DNS 预解析 + TCP/TLS 预建）。
 */

package com.takahashirinta.ncrust.network

import android.util.Log
import com.takahashirinta.ncrust.player.MediaHttp
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.EventListener
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** Bounded best-effort requests that let the next API or audio call reuse an idle connection. */
internal object ConnectionWarmup {
    private const val TAG = "ConnectionWarmup"
    private const val TIMEOUT_SECONDS = 5L
    private val hosts by lazy {
        WarmupTargetPlanner.plan(
            candidates = listOf(RetrofitClient.INTERFACE_URL, RetrofitClient.API_URL, RetrofitClient.BASE_URL),
            skip = setOf("api.bilibili.com"),
        )
    }
    private val api by lazy { bounded(RetrofitClient.warmupClientBuilder()) }
    internal val audio by lazy { bounded(MediaHttp.client.newBuilder()) }

    private fun bounded(builder: OkHttpClient.Builder): OkHttpClient = builder
        .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .eventListener(EventListener.NONE)
        .build()

    suspend fun warmUp() {
        val tasks = hosts.map { host -> suspend { warm(api, "https://$host/") } }
        BoundedParallel.runAll(tasks)
    }

    suspend fun warmAudioOrigin(streamUrl: String) {
        val stream = streamUrl.toHttpUrlOrNull() ?: return
        val origin = stream.newBuilder().username("").password("").encodedPath("/")
            .query(null).fragment(null).build()
        warm(audio, origin.toString())
    }

    private suspend fun warm(client: OkHttpClient, url: String) {
        val request = Request.Builder().url(url).header("User-Agent", "Arris-warmup/1.0").build()
        try {
            client.newCall(request).awaitWarmupResponse()
        } catch (e: IOException) {
            Log.d(TAG, "Warmup skipped for ${request.url.host}: ${e.javaClass.simpleName}")
        }
    }
}
