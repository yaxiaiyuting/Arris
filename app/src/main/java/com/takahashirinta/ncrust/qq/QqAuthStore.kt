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

    /**
     * 登录态版本的单调计数器（进程内）。**只用于拒绝迟到的续期响应。**
     *
     * 为什么不是时间戳：时间戳只能表达「先后」，不能表达「是否发生过写」。一次续期请求
     * 发出后，用户可能登出、同账号重登、换号 —— 这些都应当让旧响应作废；而如果只是
     * 过了几秒，旧响应仍然可能是有效的。这里要的是「有没有人动过登录态」这个事实。
     *
     * 为什么不落盘：它保护的是**本进程内正在飞的请求**。进程被杀，请求与响应一起消失，
     * 不存在「上一个进程的迟到响应」；重启后从 0 开始即可。
     *
     * 所有 cookie / 续期凭证的写入都必须经过 [withAuthLock] 并调用
     * [noteAuthMutationLocked]。读写同一把锁 ⇒ 「检查当前状态 + 写入」是一个原子动作，
     * 不会在两者之间被登录线程插进来。
     */
    private val authLock = Any()

    @Volatile
    private var authRevision: Long = 0L

    /** 在登录态锁内执行一段代码（可重入）。 */
    internal fun <T> withAuthLock(block: () -> T): T = synchronized(authLock, block)

    /** 当前登录态版本（线程安全）。 */
    fun currentAuthRevision(): Long = synchronized(authLock) { authRevision }

    /** 已持有 [authLock] 时读取版本，避免在锁内再次同步。 */
    internal fun authRevisionLocked(): Long = authRevision

    /** 已持有 [authLock] 时登记一次登录态写入。 */
    internal fun noteAuthMutationLocked() {
        authRevision++
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- cookie ----------

    /**
     * 保存 cookie。**用 [QqCookie.merge] 而不是覆盖** —— 登录流程拿回来的往往只是增量字段集，
     * 直接覆盖会把上次登录留下的 uin 丢掉（详见 [QqCookie.merge] 的注释）。
     *
     * v3.4.9：**登录路径必须先调 [QqRefreshStore.clear]**（见 [QqPhoneLogin.cookieFromCredential]
     * 与 `QqLoginOverlay` 的调用点）。续期凭证与 cookie 是同一个账号的两半，
     * 换了账号却留着上一份凭证，会让下一次续期把新账号的登录态换成旧账号的，
     * 而**全程不报任何错**。
     *
     * v3.4.13：写入在 [authLock] 内完成并推进 [authRevision]，让正在飞的续期响应
     * 能被 [QqRefreshCommitGuard] 判成迟到。
     */
    fun saveCookie(context: Context, cookie: String) = withAuthLock {
        val merged = QqCookie.merge(getCookie(context), cookie)
        prefs(context).edit().putString(KEY_COOKIE, merged).apply()
        noteAuthMutationLocked()
    }

    /** 覆盖式写入。只在「确认这份 cookie 就是完整登录态」（例如从备份导入）时用。 */
    fun replaceCookie(context: Context, cookie: String) = withAuthLock {
        prefs(context).edit().putString(KEY_COOKIE, cookie).apply()
        noteAuthMutationLocked()
    }

    fun getCookie(context: Context): String? =
        prefs(context).getString(KEY_COOKIE, null)

    /** 已登录 = cookie 里 uin 与票据同时存在（判据集中在 [QqCookie.isLoggedIn]）。 */
    fun isLoggedIn(context: Context): Boolean = QqCookie.isLoggedIn(getCookie(context))

    fun uin(context: Context): Long? = QqCookie.uinOf(getCookie(context))

    /**
     * 登出：**只清 qm 这一份**，不碰 `ncrust_prefs` 里的 ncm cookie。
     *
     * v3.4.9：[QqRefreshStore.clear] 必须与 cookie 一起清。续期凭证属于**这个**账号，
     * 留着它等于给下一个账号留了一把上一个账号的钥匙 —— 而误用它的表现是
     * 「登录着 B、数据全是 A」，没有任何一行日志会提示这件事。
     * 两个清理写在同一个函数里，是为了让这条不变量由**结构**保证、而不是靠调用方记得。
     *
     * v3.4.13：整段清理在同一把锁内完成并推进版本；并发中的续期响应要么看到「已登出」，
     * 要么看到「版本已变」，两条都会拒绝落盘。
     */
    fun clear(context: Context) = withAuthLock {
        // 登出/换号后不能再按"我是会员"隐藏「需会员」角标。
        vipSnapshot = false
        QqRefreshStore.clear(context)
        prefs(context).edit()
            .remove(KEY_COOKIE)
            .remove(KEY_NICK)
            .remove(KEY_UID)
            .remove(KEY_VIP_TYPE)
            .remove(KEY_VIP_EXPIRE)
            .remove(KEY_PROFILE_AT)
            .apply()
        noteAuthMutationLocked()
    }

    // ---------- 资料 / 会员 ----------

    /**
     * 无 Context 场景用的会员状态快照（角标/排序等纯函数需要它）。
     *
     * 为什么必须缓存：`TrackAvailability.of(song)` 是个**纯函数**，没有 Context 可读
     * `ncrust_qq_prefs`；而「这条 QQ 曲目对我是不是会员专享」必须看当前账号。
     * 快照在 [profile]/[saveProfile] 被调用时更新 —— 这两个入口本来就发生在
     * 「账号状态变了」或「界面在读账号」的时刻，足够新，也避免了每次画卡片都读盘。
     */
    @Volatile
    private var vipSnapshot: Boolean = false

    /** 最近一次读到的 qm 会员状态。未登录/未读取时是 false（保守：仍显示「需会员」）。 */
    fun isVipCached(): Boolean = vipSnapshot

    fun saveProfile(context: Context, profile: QqProfile) {
        vipSnapshot = profile.isVip()
        prefs(context).edit()
            .putString(KEY_NICK, profile.nick)
            .putLong(KEY_UID, profile.uid)
            .putInt(KEY_VIP_TYPE, profile.vipType)
            .putLong(KEY_VIP_EXPIRE, profile.vipExpireAt)
            .putLong(KEY_PROFILE_AT, System.currentTimeMillis())
            .apply()
    }

    /** 请求开始时的会话已变更则丢弃资料，不能把旧账号的会员日期写给新账号。 */
    fun saveProfileIfCurrent(context: Context, revision: Long, profile: QqProfile): Boolean = withAuthLock {
        if (revision != authRevisionLocked() || !isLoggedIn(context)) return@withAuthLock false
        saveProfile(context, profile)
        true
    }

    fun profile(context: Context): QqProfile = QqProfile(
        nick = prefs(context).getString(KEY_NICK, null),
        uid = prefs(context).getLong(KEY_UID, 0L),
        vipType = prefs(context).getInt(KEY_VIP_TYPE, 0),
        vipExpireAt = prefs(context).getLong(KEY_VIP_EXPIRE, 0L),
    ).also { vipSnapshot = it.isVip() }

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
