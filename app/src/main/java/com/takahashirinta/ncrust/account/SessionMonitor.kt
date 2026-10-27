package com.takahashirinta.ncrust.account

import android.content.Context
import android.util.Log
import com.takahashirinta.ncrust.auth.CookieManager
import com.takahashirinta.ncrust.auth.NeteaseSessionSnapshot
import com.takahashirinta.ncrust.auth.NeteaseTokenRefresher
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.takahashirinta.ncrust.network.PlaylistApi

/** Session checks, including failure effects, are bound to the credential generation at dispatch. */
object SessionMonitor {
    private const val TAG = "SessionMonitor"
    private const val KEY_WAS_LOGGED_IN = "session_was_logged_in"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val checks = Mutex()
    private val _state = MutableStateFlow<SessionState>(SessionState.Unknown)
    val state: StateFlow<SessionState> = _state.asStateFlow()
    @Volatile private var appContext: Context? = null
    private var lastCheckAt = 0L
    private var lastRevision = -1L
    /**
     * J03：失败后的退避截止。
     *
     * 旧实现无论成败都把 `lastCheckAt` 推进到"现在"，于是**一次网络抖动**就让整段
     * 30 分钟窗口内不再复核（用户明明在线却被当成"暂时不可用"）。失败只退避 60 秒。
     */
    private var retryAfterAt = 0L
    private fun prefs(c: Context) = c.getSharedPreferences("ncrust_settings", Context.MODE_PRIVATE)

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        CookieManager.init(app)
        NeteaseTokenRefresher.init(app)
        if (CookieManager.hasCookie(app)) prefs(app).edit().putBoolean(KEY_WAS_LOGGED_IN, true).apply()
        refresh(app)
    }
    fun onResume(context: Context) { refresh(context) }
    fun onLoginSucceeded(context: Context) {
        val app = context.applicationContext
        CookieManager.advanceGeneration(app) // Same bytes after an explicit login are still a new session.
        CookieManager.ifCurrent(CookieManager.snapshot()) {
            prefs(app).edit().putBoolean(KEY_WAS_LOGGED_IN, true).apply()
            _state.value = SessionState.Unknown
        }
        refresh(app, true)
    }
    fun onLoggedOut(context: Context) {
        CookieManager.advanceGeneration(context)
        CookieManager.ifCurrent(CookieManager.snapshot()) {
            prefs(context).edit().remove(KEY_WAS_LOGGED_IN).apply()
            _state.value = SessionState.LoggedOut
        }
    }
    /** No implicit current-snapshot default: an old 301 must always carry its request identity. */
    fun reportApiCode(code: Int?, started: NeteaseSessionSnapshot) {
        if (code != 301 || started.cookie.isNullOrBlank()) return
        val app = appContext ?: return
        scope.launch {
            when (NeteaseTokenRefresher.refreshAfterRejection(started)) {
                NeteaseTokenRefresher.Result.REFRESHED -> refresh(app, true)
                NeteaseTokenRefresher.Result.AUTH_EXPIRED, NeteaseTokenRefresher.Result.NOT_NEEDED -> markExpired(app, started)
                else -> Unit // Transport errors and cooldown never revoke a ticket.
            }
        }
    }
    fun markExpired(context: Context, started: NeteaseSessionSnapshot) {
        CookieManager.ifCurrent(started) {
            CookieManager.clearCookie(context)
            prefs(context).edit().putBoolean(KEY_WAS_LOGGED_IN, true).apply()
            _state.value = SessionState.Expired
            Log.w(TAG, "会话已失效，本地收藏缓存保留")
        }
    }
    fun refresh(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        appContext = app
        NeteaseTokenRefresher.init(app)
        scope.launch {
            checks.withLock {
                var started = CookieManager.snapshot()
                val now = android.os.SystemClock.elapsedRealtime()
                if (!force && lastRevision == started.revision && now < retryAfterAt) return@withLock
                if (!force && lastRevision == started.revision && now - lastCheckAt in 0 until 30 * 60_000L) return@withLock
                lastCheckAt = now
                lastRevision = started.revision
                try {
                    NeteaseTokenRefresher.refreshIfNeeded()
                    started = CookieManager.snapshot()
                    lastRevision = started.revision
                    if (started.cookie.isNullOrBlank()) {
                        CookieManager.ifCurrent(started) {
                            _state.value = if (prefs(app).getBoolean(KEY_WAS_LOGGED_IN, false)) SessionState.Expired else SessionState.LoggedOut
                        }
                        return@withLock
                    }
                    val account = PlaylistApi.getAccount()
                    // A read may renew and retry. Re-run under the new generation, never publish an old response.
                    if (!CookieManager.isCurrent(started)) { refresh(app, true); return@withLock }
                    when (val derived = deriveSession(true, account)) {
                        is SessionState.Valid -> {
                            val vip = try { PlaylistApi.getVipInfo(derived.userId) }
                                catch (e: CancellationException) { throw e }
                                catch (_: Exception) { null }
                            CookieManager.ifCurrent(started) { _state.value = derived.copy(vip = deriveVip(vip)) }
                        }
                        SessionState.Expired -> reportApiCode(301, started)
                        else -> Unit
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: com.takahashirinta.ncrust.auth.NeteaseSessionChangedException) {
                    // The account changed mid-check: re-run against the new credential instead of
                    // publishing (or keeping) state that belongs to the previous one.
                    lastRevision = CookieManager.snapshot().revision
                    refresh(app, true)
                }
                catch (_: Exception) {
                    // 失败：60 秒后可重试，但**不**把 30 分钟窗口当成"刚检查过"。
                    retryAfterAt = android.os.SystemClock.elapsedRealtime() + 60_000L
                    lastCheckAt = 0L
                    Log.w(TAG, "会话复核暂不可用，保留已知状态（60s 后重试）")
                }
            }
        }
    }
}
