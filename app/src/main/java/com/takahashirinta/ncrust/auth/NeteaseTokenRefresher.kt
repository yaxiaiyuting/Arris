package com.takahashirinta.ncrust.auth

import android.content.Context
import android.os.SystemClock
import com.takahashirinta.ncrust.network.RetrofitClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import org.json.JSONObject

/** All renewal is demand driven. No alarm, heartbeat, or replay of write operations. */
object NeteaseTokenRefresher {
    enum class Result { REFRESHED, NOT_NEEDED, RETRYABLE, AUTH_EXPIRED, STALE }
    /** 24h is a refresh interval, not a claim about the server's ticket lifetime. */
    private const val REFRESH_INTERVAL_MS = 24 * 60 * 60_000L
    @Volatile private var app: Context? = null
    private val mutex = Mutex()
    private val throttle = NeteaseRefreshThrottle()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var proactiveJob: Job? = null
    /** Proactive upkeep never holds a playable request behind a slow refresh server. */
    @Synchronized fun scheduleIfNeeded() {
        val context = app ?: return
        if (proactiveJob?.isActive == true || !NeteaseCookie.isUsable(CookieManager.snapshot().cookie)) return
        val at = CookieManager.lastRefreshedAt(context)
        if (at > 0 && System.currentTimeMillis() - at in 0 until REFRESH_INTERVAL_MS) return
        proactiveJob = scope.launch { refreshIfNeeded() }
    }
    fun init(context: Context) { app = context.applicationContext }

    /**
     * Fast path before the shared mutex: every read calls this, so a stale ticket must not make
     * ordinary playlist requests queue behind one in-flight renewal.
     */
    suspend fun refreshIfNeeded(): Result {
        val started = CookieManager.snapshot()
        val context = app ?: return Result.NOT_NEEDED
        if (!NeteaseCookie.isUsable(started.cookie)) return Result.NOT_NEEDED
        val refreshedAt = CookieManager.lastRefreshedAt(context)
        val age = System.currentTimeMillis() - refreshedAt
        if (refreshedAt > 0 && age in 0 until REFRESH_INTERVAL_MS) return Result.NOT_NEEDED
        return refresh(started, false)
    }
    suspend fun refreshAfterRejection(started: NeteaseSessionSnapshot): Result = refresh(started, true)

    private suspend fun refresh(started: NeteaseSessionSnapshot, force: Boolean): Result = withContext(Dispatchers.IO) {
        val context = app ?: return@withContext Result.NOT_NEEDED
        if (!NeteaseCookie.isUsable(started.cookie)) return@withContext Result.NOT_NEEDED
        mutex.withLock {
            if (!CookieManager.isCurrent(started)) return@withLock Result.STALE
            val now = SystemClock.elapsedRealtime()
            if (!throttle.allowed(started.revision, now)) return@withLock Result.RETRYABLE
            val refreshedAt = CookieManager.lastRefreshedAt(context)
            val age = System.currentTimeMillis() - refreshedAt
            // 24h is a refresh interval, not a claim about the server's ticket lifetime.
            if (!force && refreshedAt > 0 && age in 0 until REFRESH_INTERVAL_MS) return@withLock Result.NOT_NEEDED
            try {
                val response = RetrofitClient.refreshNeteaseSession(started.cookie.orEmpty())
                val candidate = response.use {
                    val json = JSONObject(it.body?.string().orEmpty())
                    if (json.optInt("code", -1) == 301) return@withLock failed(started, now, Result.AUTH_EXPIRED)
                    if (json.optInt("code", -1) != 200) return@withLock failed(started, now, Result.RETRYABLE)
                    val cookies = Cookie.parseAll(it.request.url, it.headers)
                    if (cookies.any { c -> c.name in NeteaseCookie.REQUIRED_KEYS && (c.value.isEmpty() || c.expiresAt <= System.currentTimeMillis()) })
                        return@withLock failed(started, now, Result.AUTH_EXPIRED)
                    NeteaseCookie.merge(started.cookie, cookies.joinToString("; ") { c -> "${c.name}=${c.value}" })
                }
                // A successful refresh response alone is insufficient: validate the returned ticket.
                val account = RetrofitClient.eapiPost("/eapi/w/nuser/account/get", emptyMap(), cookieOverride = candidate).use {
                    JSONObject(it.body?.string().orEmpty())
                }
                if (account.optInt("code", -1) == 301 ||
                    (account.optInt("code", -1) == 200 && account.isNull("account") && account.isNull("profile")))
                    return@withLock failed(started, now, Result.AUTH_EXPIRED)
                val uid = account.optJSONObject("account")?.optLong("id", 0L)
                    ?: account.optJSONObject("profile")?.optLong("userId", 0L) ?: 0L
                if (account.optInt("code", -1) != 200 || uid <= 0 || !NeteaseCookie.isUsable(candidate))
                    return@withLock failed(started, now, Result.RETRYABLE)
                if (!CookieManager.commitRefresh(context, started, candidate, System.currentTimeMillis()))
                    return@withLock failed(started, SystemClock.elapsedRealtime(), Result.RETRYABLE)
                throttle.record(CookieManager.snapshot().revision, SystemClock.elapsedRealtime(), true)
                Result.REFRESHED
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { failed(started, SystemClock.elapsedRealtime(), Result.RETRYABLE) }
        }
    }
    private fun failed(started: NeteaseSessionSnapshot, now: Long, result: Result): Result =
        CookieManager.ifCurrent(started) { throttle.record(started.revision, now, false)
            if (result == Result.AUTH_EXPIRED) app?.let { com.takahashirinta.ncrust.account.SessionMonitor.markExpired(it, started) }
            result } ?: Result.STALE
}

/** Monotonic, session-bound cooldown; a previous account's failure cannot block a new login. */
internal class NeteaseRefreshThrottle {
    private var revision = -1L
    private var until = 0L
    fun allowed(rev: Long, now: Long) = rev != revision || now >= until
    fun record(rev: Long, now: Long, success: Boolean) {
        revision = rev
        until = now + if (success) 10 * 60_000L else 60_000L
    }
}
