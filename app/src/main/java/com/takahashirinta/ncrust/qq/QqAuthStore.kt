/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.1.0 · C：qm 账号的独立存储。
 */

package com.takahashirinta.ncrust.qq

import android.content.Context
import android.content.SharedPreferences

/**
 * qm 账号资料（v2.1.0 · C）。
 *
 * @property nick 昵称。取不到时 null（UI 显示「已登录」而不是空白）。
 * @property uid qm 的 uid（`music.UserInfo` 的 `uid`），与 cookie 里的 uin 不是一回事：
 *   uin 是登录身份，uid 是音乐侧账号 id。
 * @property vipType 会员类型。**0 = 非会员**，>0 = 会员（腾讯侧的枚举会变化，
 *   所以这里只区分「有没有会员」与原始值，绝不把具体数字写死进业务判断 ——
 *   写死过一次的话，腾讯新增一个会员档位就会让老版本把所有会员判成非会员）。
 * @property vipExpireAt 会员到期时间（秒级时间戳），0 = 未知/不过期。
 */
data class QqProfile(
    val nick: String? = null,
    val uid: Long = 0L,
    val vipType: Int = 0,
    val vipExpireAt: Long = 0L,
) {
    /** 是否有会员（不区分 VIP/SVIP）。到期时间已知且已过期时按非会员处理。 */
    fun isVip(nowSeconds: Long = System.currentTimeMillis() / 1000L): Boolean {
        if (vipType <= 0) return false
        // 边界取 `<=`：到期时刻本身就算过期。宁可少给一秒（用户重登即可恢复），
        // 也不要多给一秒 —— 后者表现为「显示有会员但取链被拒」，比早一秒难排查得多。
        if (vipExpireAt > 0L && vipExpireAt <= nowSeconds) return false
        return true
    }
}

/**
 * qm 账号的独立存储（v2.1.0 · C）。
 *
 * ## 为什么必须是**独立的 SharedPreferences 文件**
 *
 * ncm 的 cookie 在 `ncrust_prefs`/`user_cookie`（[com.takahashirinta.ncrust.auth.CookieManager]）。
 * 两家的登录态必须能**各自独立地**读、写、失效、登出：
 * - 共用一份存储 ⇒ 登出 ncm 会把 qm 的登录态一起清掉；
 * - 共用一份存储 ⇒ 「哪一家的 cookie 过期了」无法判定，只能两家一起重新登录。
 *
 * 所以这里用 `ncrust_qq_prefs`，与 `ncrust_prefs` 没有任何交集。
 *
 * ## 落盘内容与合规
 *
 * 只落**服务端下发的 cookie 与公开资料**。本应用不采集 QQ 密码、不做密码登录，
 * cookie 也**只存在本机**、不上传任何服务器（与 ncm 一侧同样的约定）。
 */
object QqAuthStore {

    const val PREFS = "ncrust_qq_prefs"

    const val KEY_COOKIE = "qq_cookie"
    const val KEY_NICK = "qq_nick"
    const val KEY_UID = "qq_uid"
    const val KEY_VIP_TYPE = "qq_vip_type"
    const val KEY_VIP_EXPIRE = "qq_vip_expire_at"
    const val KEY_PROFILE_AT = "qq_profile_at"

    /** 会员资料的内存缓存 TTL：10 分钟。UI 每次组合都读它，不能每次都发请求。 */
    private const val PROFILE_TTL_MS = 10 * 60 * 1000L

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- cookie ----------

    /**
     * 保存 cookie。**用 [QqCookie.merge] 而不是覆盖** —— 登录流程拿回来的往往只是增量字段集，
     * 直接覆盖会把上次登录留下的 uin 丢掉（详见 [QqCookie.merge] 的注释）。
     */
    fun saveCookie(context: Context, cookie: String) {
        val merged = QqCookie.merge(getCookie(context), cookie)
        prefs(context).edit().putString(KEY_COOKIE, merged).apply()
    }

    /** 覆盖式写入。只在「确认这份 cookie 就是完整登录态」（例如从备份导入）时用。 */
    fun replaceCookie(context: Context, cookie: String) {
        prefs(context).edit().putString(KEY_COOKIE, cookie).apply()
    }

    fun getCookie(context: Context): String? =
        prefs(context).getString(KEY_COOKIE, null)

    /** 已登录 = cookie 里 uin 与票据同时存在（判据集中在 [QqCookie.isLoggedIn]）。 */
    fun isLoggedIn(context: Context): Boolean = QqCookie.isLoggedIn(getCookie(context))

    fun uin(context: Context): Long? = QqCookie.uinOf(getCookie(context))

    /** 登出：**只清 qm 这一份**，不碰 `ncrust_prefs` 里的 ncm cookie。 */
    fun clear(context: Context) {
        prefs(context).edit()
            .remove(KEY_COOKIE)
            .remove(KEY_NICK)
            .remove(KEY_UID)
            .remove(KEY_VIP_TYPE)
            .remove(KEY_VIP_EXPIRE)
            .remove(KEY_PROFILE_AT)
            .apply()
    }

    // ---------- 资料 / 会员 ----------

    fun saveProfile(context: Context, profile: QqProfile) {
        prefs(context).edit()
            .putString(KEY_NICK, profile.nick)
            .putLong(KEY_UID, profile.uid)
            .putInt(KEY_VIP_TYPE, profile.vipType)
            .putLong(KEY_VIP_EXPIRE, profile.vipExpireAt)
            .putLong(KEY_PROFILE_AT, System.currentTimeMillis())
            .apply()
    }

    fun profile(context: Context): QqProfile = QqProfile(
        nick = prefs(context).getString(KEY_NICK, null),
        uid = prefs(context).getLong(KEY_UID, 0L),
        vipType = prefs(context).getInt(KEY_VIP_TYPE, 0),
        vipExpireAt = prefs(context).getLong(KEY_VIP_EXPIRE, 0L),
    )

    /**
     * 资料是否需要重新拉取。**未登录时恒为 false**（没登录就没有资料可拉，
     * 否则 UI 一进设置页就会发一个注定 401 的请求）。
     */
    fun needsProfileRefresh(context: Context): Boolean {
        if (!isLoggedIn(context)) return false
        val at = prefs(context).getLong(KEY_PROFILE_AT, 0L)
        return System.currentTimeMillis() - at > PROFILE_TTL_MS
    }
}
