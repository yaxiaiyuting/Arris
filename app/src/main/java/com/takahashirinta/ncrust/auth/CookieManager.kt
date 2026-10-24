package com.takahashirinta.ncrust.auth

import android.content.Context
import android.content.SharedPreferences

object CookieManager {
    private const val PREFS_NAME = "ncrust_prefs"
    private const val KEY_COOKIE = "user_cookie"
    private const val KEY_REFRESHED_AT = "session_refreshed_at"
    private val guard = NeteaseSessionGuard()
    private var app: Context? = null

    fun init(context: Context) = synchronized(guard) {
        if (app == null) {
            app = context.applicationContext
            guard.replace(getPrefs(context).getString(KEY_COOKIE, null))
        }
    }
    fun snapshot(): NeteaseSessionSnapshot = synchronized(guard) { guard.snapshot() }
    fun isCurrent(s: NeteaseSessionSnapshot): Boolean = synchronized(guard) { guard.isCurrent(s) }
    fun <T> ifCurrent(s: NeteaseSessionSnapshot, block: () -> T): T? = synchronized(guard) {
        guard.ifCurrent(s, block)
    }
    fun advanceGeneration(context: Context) = synchronized(guard) {
        init(context)
        guard.replace(guard.snapshot().cookie)
    }
    fun lastRefreshedAt(context: Context): Long = getPrefs(context).getLong(KEY_REFRESHED_AT, 0L)
    fun commitRefresh(context: Context, started: NeteaseSessionSnapshot, cookie: String, now: Long): Boolean =
        synchronized(guard) {
            if (!guard.isCurrent(started) || !NeteaseCookie.isUsable(cookie)) return@synchronized false
            // Publish the persistent credential and generation under the same lock as login/logout.
            val ok = getPrefs(context).edit().putString(KEY_COOKIE, cookie).putLong(KEY_REFRESHED_AT, now).commit()
            if (!ok) { getPrefs(context).edit().putString(KEY_COOKIE, started.cookie).remove(KEY_REFRESHED_AT).commit(); return@synchronized false }
            guard.replace(cookie, renewal = true)
            com.takahashirinta.ncrust.network.XeapiState.clearSession()
            true
        }


    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 覆盖写入整个 cookie 串。
     *
     * ⚠️ v3.3.0 起**登录路径不该直接调它**，改用 [saveCookieMerged]。
     * 三条登录路径给的形状不同（WebView 整串 / 扫码只回 803 响应的 `Set-Cookie` 增量 /
     * 局域网回传），覆盖写会让先到的那批键丢掉 —— 那是用户反馈
     * 「要很多次退出登录再登录才能播 VIP」的机制之一（见 [NeteaseCookie] 的类文档）。
     * 保留这个函数是为了让「登出」「清空」以及既有单测仍有一个明确的整体写入点。
     */
    fun saveCookie(context: Context, cookie: String) {
        synchronized(guard) {
            init(context)
            getPrefs(context).edit().putString(KEY_COOKIE, cookie).remove(KEY_REFRESHED_AT).apply()
            guard.replace(cookie)
            NeteaseVipStore.clear(context)
        }
    }

    /**
     * v3.3.0：**合并**写入（同名取新值，不同名保留）。
     *
     * 返回值是「是否真的写进去了」：轮询期间会反复读到同一份 cookie，
     * 也会读到「页面还没写入 `__csrf`」的中间态 —— 而 [NeteaseCookie.shouldReplace]
     * 会拒绝用半截会话覆盖完整会话。没有变化就不写盘，避免每次轮询都触发一次 prefs 写。
     */
    fun saveCookieMerged(context: Context, incoming: String?): Boolean = synchronized(guard) {
        if (incoming.isNullOrBlank()) return@synchronized false
        val old = getCookie(context)
        if (!NeteaseCookie.shouldReplace(old, incoming)) return@synchronized false
        val merged = NeteaseCookie.merge(old, incoming)
        if (merged == old) return@synchronized false
        saveCookie(context, merged)
        true
    }

    fun getCookie(context: Context): String? {
        return synchronized(guard) { init(context); guard.snapshot().cookie }
    }

    fun hasCookie(context: Context): Boolean {
        return !getCookie(context).isNullOrBlank()
    }

    /**
     * v3.3.0：这是一份**可用**的登录态吗（`MUSIC_U` **与** `__csrf` 都要有）。
     *
     * 与 [hasCookie] 的区别是刻意的：[hasCookie] 只说明「存了点东西」，
     * 而只带 `MUSIC_U` 的半截会话会让界面显示「已登录」但**所有写操作 403**
     * （收藏、歌单增删改）—— 一个看起来成功、用起来失败的状态。
     *
     * ⚠️ 现有调用点继续用 [hasCookie] 的不必改：那是一个**更宽松**的口径，
     * 放宽不会把已登录的用户踢出去。新写的判断请用这个。
     */
    fun isLoggedIn(context: Context): Boolean = NeteaseCookie.isUsable(getCookie(context))

    fun clearCookie(context: Context) {
        synchronized(guard) {
            init(context)
            getPrefs(context).edit().remove(KEY_COOKIE).remove(KEY_REFRESHED_AT).apply()
            guard.replace(null)
            NeteaseVipStore.clear(context)
        }
    }
}
