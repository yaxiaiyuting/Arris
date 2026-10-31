/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.bili

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/** Network interceptors run on every redirect hop, just before request headers are written. */
internal object BiliCredentialBoundary : Interceptor {
    internal fun sanitize(request: Request): Request {
        val host = request.url.host
        if (request.url.isHttps && (host == "bilibili.com" || host.endsWith(".bilibili.com"))) return request
        // A redirected POST can carry refresh/CSRF secrets in its body, even without Cookie.
        if (request.body != null) throw IOException("Bili credential body redirect outside account domain")
        return request.newBuilder().removeHeader("Cookie").removeHeader("Authorization")
            .removeHeader("Referer").removeHeader("Origin").build()
    }
    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(sanitize(chain.request()))
}
