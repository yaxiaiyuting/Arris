/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.2.0 · P1：B 站账号的独立存储 + 进程内镜像。
 */

package com.takahashirinta.ncrust.bili

import android.content.Context
import android.content.SharedPreferences

/**
 * B 站扫码登录拿到的凭据（v3.2.0 · P1）。
 *
 * ## 字段与载体
 *
 * 实测（见 `docs/verification/v3.2.0/probe-bili-login.md` §1）扫码成功的凭据有**两个载体**，
 * 缺一不可：
 * 1. 轮询响应的 **`Set-Cookie`**（`SESSDATA` / `bili_jct` / `DedeUserID` /
 *    `DedeUserID__ckMd5` / `sid`）；
 * 2. 同一次响应的 **`data.url`**（`https://passport.biligame.com/crossDomain?…&SESSDATA=…`，
 *    给浏览器做跨域登录用的 query 参数）。
 *
 * 有些实现只读其中一个。本轮没有可用的 B 站账号，**成功态没能实测**，所以两边都解析、
 * 逐字段合并（见 [merge]），谁有值用谁 —— 这是"两处都可能"的唯一正确写法。
 *
 * ## 为什么 `toString()` 被改写成不含凭据值
 *
 * 数据类的默认 `toString()` 会把 `SESSDATA` 原样打出来。而这个类**一定会**出现在
 * 日志、崩溃上报、`Log.d("…$result")` 这类地方 —— 一次疏忽就是永久性凭据泄露
 * （`SESSDATA` 有效期以月计）。所以这里把 `toString()` 改成只打**长度**：
 * 任何形式的字符串插值都不可能把凭据带出去。单测
 * `BiliAuthStoreTest.凭据的 toString 不含任何凭据值（结构性防泄露）` 钉住它。
 */
data class BiliCredential(
    val sessdata: String = "",
    val biliJct: String = "",
    /** `DedeUserID` 就是用户 mid。**按字符串原样保存**（cookie 值本来就是字符串，不做数值转换）。 */
    val dedeUserId: String = "",
    val dedeUserIdCkMd5: String = "",
    val sid: String = "",
) {

    /** 可用判据只有一条：**`SESSDATA` 非空**。其余字段缺失会让写操作失败，但不影响取流。 */
    fun isUsable(): Boolean = sessdata.isNotBlank()

    /**
     * 拼成 `Cookie` 头：`SESSDATA=…; bili_jct=…; DedeUserID=…`。
     *
     * **值一律原样写回，不做 URL 解码**：真实 `SESSDATA` 里的逗号是 `%2C` 的形状，
     * 解码成 `,` 会改变凭据本身（服务端比对的是它签发时的那个字符串）。
     * 空的字段直接省略 —— 带上 `bili_jct=` 这种空值反而会让服务端的 CSRF 校验拿到空串。
     */
    fun cookieHeader(): String {
        val parts = ArrayList<String>(5)
        if (sessdata.isNotBlank()) parts.add("SESSDATA=$sessdata")
        if (biliJct.isNotBlank()) parts.add("bili_jct=$biliJct")
        if (dedeUserId.isNotBlank()) parts.add("DedeUserID=$dedeUserId")
        if (dedeUserIdCkMd5.isNotBlank()) parts.add("DedeUserID__ckMd5=$dedeUserIdCkMd5")
        if (sid.isNotBlank()) parts.add("sid=$sid")
        return parts.joinToString("; ")
    }

    /**
     * 逐字段合并：**`this` 为底，[other] 里非空的字段覆盖**。
     *
     * 两个载体（`Set-Cookie` 与 `data.url`）内容可能只有一份是全的，而**空值不能覆盖有值** ——
     * 那正是 `QqCookie.merge` 当年踩过的形状（覆盖式写入把上一次登录留下的 uin 弄丢了）。
     */
    fun merge(other: BiliCredential): BiliCredential = BiliCredential(
        sessdata = other.sessdata.ifBlank { sessdata },
        biliJct = other.biliJct.ifBlank { biliJct },
        dedeUserId = other.dedeUserId.ifBlank { dedeUserId },
        dedeUserIdCkMd5 = other.dedeUserIdCkMd5.ifBlank { dedeUserIdCkMd5 },
        sid = other.sid.ifBlank { sid },
    )

    /** **只打长度**，绝不打值。见类文档。 */
    override fun toString(): String =
        "BiliCredential(SESSDATA len=${sessdata.length}, bili_jct len=${biliJct.length}, " +
            "DedeUserID=${if (dedeUserId.isBlank()) "(空)" else dedeUserId}, " +
            "ckMd5 len=${dedeUserIdCkMd5.length}, sid len=${sid.length})"
}

/**
 * B 站账号资料（来自 `nav` 的 `data`）。**匿名时这些字段一个都不存在**（实测见探针文档 §6）。
 *
 * @property uname 昵称。`nav` 的 `data.uname`。
 * @property mid 用户 mid（`data.mid`）。与 `DedeUserID` 同值（后者是 cookie 载体）。
 * @property vipType 大会员类型：`0` 非会员、`1` 月度、`2` 年度（社区枚举）。
 * @property vipStatus 大会员状态：`0` 非会员、`1` 会员。
 *
 * ⚠️ **本轮没有可用的 B 站账号**：`vipType` / `vipStatus` 的取值枚举来自社区文档，
 * **登录态 `nav` 未实测**（匿名 `nav` 里这两个字段根本不存在 —— 这是实测的，见 §6）。
 * 所以 [isVip] 只做「有没有会员」的判断，**绝不写死具体档位**（腾讯那边已经踩过一次：
 * 把 0/1 写死会让服务端新增档位时把所有会员判成非会员）。
 */
data class BiliProfile(
    val uname: String? = null,
    val mid: Long = 0L,
    val vipType: Int = 0,
    val vipStatus: Int = 0,
) {
    /**
     * 是否大会员。
     *
     * 判据是 `vipStatus > 0 || vipType > 0`：两个字段在社区枚举里是「状态」与「档位」，
     * 任一为正都意味着有会员。**不区分月度/年度** —— 本应用没有任何按档位分叉的行为。
     */
    fun isVip(): Boolean = vipStatus > 0 || vipType > 0
}

/**
 * B 站账号的独立存储（v3.2.0 · P1）。
 *
 * ## 为什么必须是**独立的 SharedPreferences 文件**（照 `QqAuthStore` 的理由写）
 *
 * 网易云的 cookie 在 `ncrust_prefs`/`user_cookie`（`auth/CookieManager`），QQ 音乐的在
 * `ncrust_qq_prefs`（`qq/QqAuthStore`）。三家的登录态必须能**各自独立地**读、写、失效、登出：
 * - 共用一份存储 ⇒ 登出 B 站会把网易云的登录态一起清掉；
 * - 共用一份存储 ⇒「哪一家的 cookie 过期了」无法判定，只能三家一起重新登录；
 * - 而且 B 站音源**默认关闭**（[BiliPrefs.DEFAULT_ENABLED]），它的登录态必须能在
 *   整个 B 站音源关掉的情况下独立存在/独立清除，不牵动另外两家。
 *
 * 所以：`ncrust_bili_prefs`。它与 `ncrust_prefs` / `ncrust_qq_prefs` / `ncrust_settings`
 * **没有任何交集**（`BiliPrefs` 用的 `ncrust_settings` 只放开关）。
 *
 * ## 落盘内容与合规
 *
 * 只落**服务端下发的 cookie 与公开资料**。本应用不采集 B 站密码、不做密码登录，
 * 凭据**只存在本机、不上传任何服务器**（与网易云/QQ 一侧同样的约定）。
 * 日志里**只出现字段名与长度**（[BiliCredential.toString] 结构性保证 + 源码扫描守卫）。
 *
 * ## 为什么有「进程内镜像」
 *
 * 网络层（[BiliApi]）没有、也不该有 `Context`（它是个 `object`，且会被单测直接调用）。
 * 所以登录态在这里维护一份只读镜像：冷启动 [init] 载入，[save] / [clear] 时同步更新，
 * 网络层通过 [requestCookieHeader] 读它 —— 与 `BiliPrefs` 的 `@Volatile` 镜像同一套模式。
 * **镜像为空 = 匿名**，此时 B 站的请求与 v3.1.0 逐字一致（一个 Cookie 都不带）。
 */
object BiliAuthStore {

    /** 独立文件（见类文档）。 */
    const val PREFS = "ncrust_bili_prefs"

    // ---------------------------------------------------------------- 盘上字段名（铁律 17） ----
    //
    // 全部**显式声明**为常量。守卫：`BiliAuthStoreTest.盘上字段名与声明逐字一致（铁律 17）`
    // 会把这里的字面量与预期表逐条比对，并断言每个常量**真的被读写用到**（没有写死的字面量 key）。

    /** 核心凭据；同时是音频区取流/收藏夹的鉴权载体。 */
    const val KEY_SESSDATA = "SESSDATA"

    /** CSRF token。**必须与 `SESSDATA` 一起存**（写操作缺它必失败，失败形态是 HTTP 200 + 业务码）。 */
    const val KEY_BILI_JCT = "bili_jct"

    /** 用户 mid。 */
    const val KEY_DEDE_USER_ID = "DedeUserID"

    /** mid 的校验值（`Set-Cookie` 里一同下发）。 */
    const val KEY_DEDE_USER_ID_CK_MD5 = "DedeUserID__ckMd5"

    /** 会话 id。 */
    const val KEY_SID = "sid"

    const val KEY_UNAME = "uname"
    const val KEY_MID = "mid"
    const val KEY_VIP_TYPE = "vip_type"
    const val KEY_VIP_STATUS = "vip_status"

    /** 资料最后一次拉取的时刻（毫秒）。 */
    const val KEY_PROFILE_AT = "profile_at"

    /** 资料的内存缓存 TTL：10 分钟（与 `QqAuthStore.PROFILE_TTL_MS` 同口径）。 */
    const val PROFILE_TTL_MS = 10 * 60 * 1000L

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- 进程内镜像 ----

    @Volatile
    private var mirror: BiliCredential? = null

    /**
     * 冷启动时调用一次（与 `BiliPrefs.init` / `RetrofitClient.init` 同处）。
     *
     * 不调用也不崩：镜像保持 null ⇒ 网络层按**匿名**发请求，与 v3.1.0 行为一致。
     */
    fun init(context: Context) {
        mirror = credential(context)
    }

    /**
     * 登录成功后落盘。
     *
     * **顺序是「先落盘、再改镜像」**（与 `BiliPrefs.setEnabled` 同一条纪律）：反过来的话
     * 写盘失败会让镜像说「已登录」、下次冷启动又说「没登录」—— 用户看到的是「登录自己掉了」。
     */
    fun save(context: Context, credential: BiliCredential) {
        prefs(context).edit()
            .putString(KEY_SESSDATA, credential.sessdata)
            .putString(KEY_BILI_JCT, credential.biliJct)
            .putString(KEY_DEDE_USER_ID, credential.dedeUserId)
            .putString(KEY_DEDE_USER_ID_CK_MD5, credential.dedeUserIdCkMd5)
            .putString(KEY_SID, credential.sid)
            .apply()
        mirror = credential
    }

    /** 盘上的凭据（**只给初始化和单测用**；热路径读镜像）。 */
    fun credential(context: Context): BiliCredential {
        val p = prefs(context)
        return BiliCredential(
            sessdata = p.getString(KEY_SESSDATA, "").orEmpty(),
            biliJct = p.getString(KEY_BILI_JCT, "").orEmpty(),
            dedeUserId = p.getString(KEY_DEDE_USER_ID, "").orEmpty(),
            dedeUserIdCkMd5 = p.getString(KEY_DEDE_USER_ID_CK_MD5, "").orEmpty(),
            sid = p.getString(KEY_SID, "").orEmpty(),
        )
    }

    /**
     * 登出：**只清 B 站这一份**，不碰 `ncrust_prefs` / `ncrust_qq_prefs` / `ncrust_settings`。
     *
     * 资料字段一起清：留着上一个账号的昵称会让界面显示「已登录：某某」而实际没有凭据。
     */
    fun clear(context: Context) {
        prefs(context).edit()
            .remove(KEY_SESSDATA)
            .remove(KEY_BILI_JCT)
            .remove(KEY_DEDE_USER_ID)
            .remove(KEY_DEDE_USER_ID_CK_MD5)
            .remove(KEY_SID)
            .remove(KEY_UNAME)
            .remove(KEY_MID)
            .remove(KEY_VIP_TYPE)
            .remove(KEY_VIP_STATUS)
            .remove(KEY_PROFILE_AT)
            .apply()
        mirror = null
    }

    // ---------------------------------------------------------------- 登录态 ----

    /** 盘上是否有可用凭据。 */
    fun isLoggedIn(context: Context): Boolean = credential(context).isUsable()

    /**
     * 盘上凭据拼成的 `Cookie` 头（`SESSDATA=…; bili_jct=…; DedeUserID=…`）。
     *
     * 与 [requestCookieHeader] 的分工：这个是**给需要显式读取的调用方**（例如登录后立刻校验一次
     * `nav`、或把 cookie 交给别的组件），走盘上那份；网络层走进程内镜像那个（它没有 `Context`）。
     * 未登录返回 **null**。
     */
    fun cookieHeader(context: Context): String? =
        credential(context).takeIf { it.isUsable() }?.cookieHeader()?.takeIf { it.isNotBlank() }

    /** 镜像判据（无 `Context`；网络层与组合期用）。 */
    fun isLoggedInInMemory(): Boolean = mirror?.isUsable() == true

    /**
     * 给**网络层**用的 Cookie 头（读镜像，无 `Context`）。
     *
     * 未登录 / 未 [init] ⇒ **null**（[BiliApi] 据此一个 Cookie 都不带，见它的 `get`）。
     */
    fun requestCookieHeader(): String? =
        mirror?.takeIf { it.isUsable() }?.cookieHeader()?.takeIf { it.isNotBlank() }

    /** 只给单测用：直接改进程内镜像（**不落盘**）。生产代码里没有写入点。 */
    internal fun setMirrorForTest(credential: BiliCredential?) {
        mirror = credential
    }

    // ---------------------------------------------------------------- 资料 / 大会员 ----

    fun saveProfile(context: Context, profile: BiliProfile) {
        prefs(context).edit()
            .putString(KEY_UNAME, profile.uname)
            .putLong(KEY_MID, profile.mid)
            .putInt(KEY_VIP_TYPE, profile.vipType)
            .putInt(KEY_VIP_STATUS, profile.vipStatus)
            .putLong(KEY_PROFILE_AT, System.currentTimeMillis())
            .apply()
    }

    fun profile(context: Context): BiliProfile = BiliProfile(
        uname = prefs(context).getString(KEY_UNAME, null),
        mid = prefs(context).getLong(KEY_MID, 0L),
        vipType = prefs(context).getInt(KEY_VIP_TYPE, 0),
        vipStatus = prefs(context).getInt(KEY_VIP_STATUS, 0),
    )

    fun uname(context: Context): String? = profile(context).uname?.takeIf { it.isNotBlank() }

    /** 是否大会员（判据在 [BiliProfile.isVip]；**未登录恒 false**）。 */
    fun isVip(context: Context): Boolean =
        isLoggedIn(context) && profile(context).isVip()

    /**
     * 资料是否需要重新拉取。**未登录时恒为 false**（没登录就没有资料可拉，
     * 否则一进设置页就会发一个注定 `-101` 的请求 —— 与 `QqAuthStore.needsProfileRefresh` 同一条）。
     */
    fun needsProfileRefresh(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean =
        shouldRefreshProfile(isLoggedIn(context), prefs(context).getLong(KEY_PROFILE_AT, 0L), nowMs)

    /**
     * [needsProfileRefresh] 的**纯逻辑**部分（JVM 单测直接钉它；`Context` 那半边没有逻辑）。
     *
     * 边界取 `>`（TTL 恰好到期算新鲜）：与 `QqAuthStore` 的 `now - at > TTL` 逐字一致。
     */
    internal fun shouldRefreshProfile(loggedIn: Boolean, profileAtMs: Long, nowMs: Long): Boolean {
        if (!loggedIn) return false
        return nowMs - profileAtMs > PROFILE_TTL_MS
    }

    // ---------------------------------------------------------------- 纯工具 ----

    /**
     * 把「登录 Cookie」与「额外 Cookie」（目前只有 `buvid3`）拼成一条 `Cookie` 头。
     *
     * 两者都可能为 null：都为空 ⇒ **返回 null**（调用方据此完全不带 `Cookie` 头，
     * 这是"未登录时与 v3.1.0 逐字一致"的保证点）。
     */
    internal fun mergeCookieHeaders(login: String?, extra: String?): String? {
        val parts = listOfNotNull(
            login?.trim()?.takeIf { it.isNotEmpty() },
            extra?.trim()?.takeIf { it.isNotEmpty() },
        )
        return if (parts.isEmpty()) null else parts.joinToString("; ")
    }
}
