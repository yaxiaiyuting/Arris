/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.4.9：qm 登录态的**续期凭证**（纯逻辑，无 IO、无 Android 依赖、JVM 可单测）。
 */

package com.takahashirinta.ncrust.qq

import com.google.gson.annotations.SerializedName
import org.json.JSONObject

/**
 * qm 登录态的**续期凭证**：`Login` 响应里除 cookie 之外的那些字段。
 *
 * ## 这个类要解决的问题（用户报障的原话）
 *
 * > 「qq 音乐登录身份在一周左右就会掉，重新登录很麻烦」
 *
 * 根因是**两条各自都成立的实测事实**叠在一起：
 *
 * 1. `musickey`（`qqmusic_key` / `qm_keyst`）**有寿命**。签发时间与寿命都由服务端在
 *    `Login` 的响应里给出（`musickeyCreateTime` + `keyExpiresIn`），
 *    参考实现（`L-1124/QQMusicApi` 的 `Credential.is_expired()`）用的就是这两个字段：
 *    `now >= musickeyCreateTime + keyExpiresIn`。本机落盘样本里
 *    `psrf_musickey_createtime=1790277358`，与用户描述的「一周左右」同量级。
 * 2. 在 v3.4.9 之前，本仓库把 `Login` 的响应**只留了 5 个字段拼进 cookie**
 *    （`uin` / `qqmusic_uin` / `qqmusic_key` / `qm_keyst` / `psrf_musickey_createtime`），
 *    **`refresh_key` / `refresh_token` / `openid` / `access_token` / `musicid` 全部丢掉**。
 *
 * 于是票据一到期，客户端手里**没有任何可以用来换新票的东西**，唯一的出路就是让用户
 * 重新扫码/重新收短信 —— 这不是「一周掉一次」的偶然，是**必然**：丢掉续期凭证等于
 * 把「可续期的会话」降级成「一次性票据」。
 *
 * ## 续期怎么做（端点与参数全部有依据，不是猜的）
 *
 * 同一个 `music.login.LoginServer` / `Login`，`loginMode` 从 1（验证码登录）换成
 * **2（刷新）**，参数里带上这一整份凭证。依据是 `L-1124/QQMusicApi`
 * `modules/login.py::refresh_credential()` 的三套参数分支（它按 `login_type` 分叉）：
 *
 * | `loginType` | 参数集 |
 * |---|---|
 * | 1（微信） | `openid` `refresh_token` `str_musicid` `musickey` `unionid` `refresh_key` `loginMode=2` |
 * | 2（QQ） | `openid` `access_token` `refresh_token` `expired_in` `musicid` `musickey` `refresh_key` `loginMode=2` |
 * | 其它/未知（**手机号登录走这一支**） | 上面两套的**并集** |
 *
 * 本客户端拿到凭证的入口不止一条（手机号、官方 App 扫码、历史微信票据），
 * `loginType` 由响应或 `musickey` 前缀确定；[QqRequests.refreshCredential]
 * 与 [QqClient.musicuRefresh] 都按这同一个值分叉。手机号的显式 0
 * （既不是微信 1 也不是 QQ 2）走上面的**并集**分支 —— 这是参考实现 `case _`
 * 的形状，不是「多带点总没错」的猜测。
 *
 * ## 敏感性（与 cookie 同级的账号凭证）
 *
 * `refresh_key` / `refresh_token` / `access_token` 是**可以换出新票据的凭证**，
 * 敏感性不低于 `musickey`。所以：
 * - 落盘只在**本机** `ncrust_qq_prefs`（与 cookie 同一个文件、同一个「绝不上传」约定）；
 * - **任何日志都不许打印它们的值**（`maskSensitive` / `describe()` 只给长度与前缀）。
 *
 * ## 加字段 = 加迁移逻辑（AGENTS.md 铁律）
 *
 * 每个字段**可空 + 有默认值**：Gson 走 Unsafe 反序列化、不调用构造函数，
 * v3.4.9 之前落盘的 JSON 里没有这个 key，读出来就是 `null`。
 * 「字段缺失」的语义是**这份凭证换不出新票据**（[isRefreshable] / [missingFields]），
 * 于是表现为「照旧要重新登录」—— 与 v3.4.8 完全一致，**不会更糟**。
 *
 * ## 一处**如实标注**的能力边界：网页登录这条路不可续期
 *
 * 能拿到这份凭证的只有**手机号验证码登录**（它走 `music.login.LoginServer` 的 `Login`，
 * 响应里就是这些字段）。另外两条路拿到的是**cookie**、不是凭证 JSON：
 *
 * | 登录方式 | 拿到的东西 | 能不能续期 |
 * |---|---|---|
 * | 手机号验证码 | `Login` 的 `data`（含 `refreshKey` / `refreshToken`） | ✅ 能 |
 * | 网页登录 / QQ 互联扫码 | `y.qq.com` 域下的 cookie | ❌ 不能 |
 *
 * 网页登录那条路我们只能读到 cookie；要把它变成可续期的凭证，需要服务端并未提供的
 * 等价物（cookie 里没有 `refreshKey`，也没有 `openid`）。所以：
 * **走网页登录的用户仍然会在票据到期后需要重新登录一次** ——
 * 这不是本版没做完，而是那条路在客户端侧**拿不到续期所需的事实**。
 * 把它写成「已支持」才是错的（本仓库反复修过的「阴性结果伪装成没有数据」）。
 *
 * 想让网页登录也可续期，正确的做法是让那条路改走 `Login`（即加一个手机号登录入口
 * 之外的 `loginMode` 路径），而不是在客户端猜字段。本版不做。
 */
data class QqRefreshCredential(
    /** `musicid`（QQ 号或微信的长数字 ID）。缺了它 `Login` 认不出是谁。 */
    @SerializedName("musicid") val musicId: Long = 0L,
    /** 上一次签发的 `musickey`。刷新时作为「我是谁」的一部分一起回传。 */
    @SerializedName("musickey") val musicKey: String? = null,
    /** 刷新用的长期凭证。**这是整条续期链路的关键**。 */
    @SerializedName("refreshKey") val refreshKey: String? = null,
    /** 与 [refreshKey] 并列的另一种刷新凭证（微信侧用得多）。 */
    @SerializedName("refreshToken") val refreshToken: String? = null,
    @SerializedName("accessToken") val accessToken: String? = null,
    @SerializedName("openid") val openId: String? = null,
    @SerializedName("unionid") val unionId: String? = null,
    /** `str_musicid`：字符串形态的 musicid（部分账号只有这个）。 */
    @SerializedName("strMusicId") val strMusicId: String? = null,
    /** 服务端给的登录类型：1 = 微信，2 = QQ，0 = 手机号。**显式 0 不能被推断成 2。** */
    @SerializedName("loginType") val loginType: Int = 0,
    /**
     * [loginType] 是否由**本次解析**确定（服务端显式给了，或按 [previousLoginType] 补了缺失）。
     *
     * 这个布尔解决的是「0 到底是手机号还是未知」：`Int` 默认值 0 同时承担两种语义，
     * 合并时用 `> 0` 判断会把显式 `loginType=0` 丢掉。解析器给出 `true` 后，
     * 合并就原样采用它（包括 0）；直接构造的数据类实例（测试/迁移）默认 `false`，
     * 合并时仍保留旧值，不会把已知类型降级成未知。
     *
     * 旧版本落盘的 JSON 没有这个键 ⇒ false，行为与旧版一致；下一次真实刷新会补上。
     */
    @SerializedName("loginTypeResolved") val loginTypeResolved: Boolean = false,
    /**
     * `expired_at`：登录时下发的 access_token **绝对到期时刻**（秒级）。
     *
     * v3.4.9 的第一版丢掉了这个字段，改用 `musickeyCreateTime + psrf_access_token_expiresAt`
     * 在请求时反推。反推的两个前提没有响应证据：`psrf_access_token_expiresAt` 究竟是
     * 「寿命」还是「绝对时刻」、以及它与 `musickeyCreateTime` 是否同一时基。参考实现的
     * `Credential.expired_at` 就是登录响应的原字段，`refresh_credential()` 直接把它
     * 当作 `expired_in` 发出（`models/request.py` 与 `modules/login.py`）。
     *
     * 本机实测的 `Login` 失败骨架里 `expired_at` **键存在、值为 0**
     * （见 `QqPhoneLoginTest` 的 35 字段样本），所以这是响应里真实存在的字段，
     * 不是从别处推断出来的。
     *
     * 默认 0 = 未知；请求侧只在它 > 0 时优先使用，否则回落到旧的反推式。
     */
    @SerializedName("expired_at") val expiredAt: Long = 0L,
    /** `musickeyCreateTime`：票据签发时间（秒级时间戳）。 */
    @SerializedName("keyCreatedAt") val keyCreatedAt: Long = 0L,
    /** `keyExpiresIn`：票据寿命（秒）。有了它才能在**到期前**主动续期。 */
    @SerializedName("keyExpiresIn") val keyExpiresIn: Long = 0L,
    /**
     * `needRefreshKeyIn`：服务端下发的 refreshKey 轮换提示字段。
     *
     * 本仓库只落盘/合并它，并用它判断「服务端是否明确索要 refreshKey」；
     * **不**据此调度主动续期（语义没有服务端保证，参考实现也未这样用）。
     */
    @SerializedName("needRefreshKeyIn") val needRefreshKeyIn: Long = 0L,
    /** `encryptUin`：收藏歌单接口要的加密 uin（与登录态同源，顺手留下）。 */
    @SerializedName("encryptUin") val encryptUin: String? = null,
) {

    /**
     * 本地时间戳判断：票据是否**已经**过期。
     *
     * 两个时间字段缺任何一个都返回 `false` —— **「不知道」不等于「过期」**。
     * 猜错的代价不对称：误判成过期 ⇒ 每次都白跑一次刷新请求（还可能因为风控被封）；
     * 漏判 ⇒ 走 [QqTokenRefresher] 的被动路径（服务端拒绝时再刷新），一样能自愈。
     */
    fun isExpired(nowSeconds: Long = System.currentTimeMillis() / 1000L): Boolean {
        if (keyCreatedAt <= 0L || keyExpiresIn <= 0L) return false
        return nowSeconds >= keyCreatedAt + keyExpiresIn
    }

    /**
     * 是否**该主动**续期（v3.4.9）。
     *
     * 判据只有一条：`keyCreatedAt + keyExpiresIn`（服务端给的票据寿命）提前
     * [REFRESH_MARGIN_SECONDS] 换，而不是等到过期那一刻 —— 到期瞬间用户正在播歌，
     * 那时再换会多一次「取链失败 → 刷新 → 重取」的可见停顿。
     *
     * ⚠️ `needRefreshKeyIn` **不参与**这个调度。它继续落盘/合并（诊断与续期门槛用），
     * 但「从签发时间起过 N 秒就该换 refreshKey」这个语义没有服务端保证，
     * 参考实现也没有依据它调度（只在 `Credential` 模型里保存字段）。
     * 本仓库不为猜测的调度增加请求：多刷新一次是风控风险，晚刷新一次还能靠
     * 被动路径兜底。
     *
     * 寿命未知（老凭证）时返回 `false`：没有依据就不主动发请求，
     * 交给被动路径（服务端明确拒绝时再刷新）。
     */
    fun needsRefresh(nowSeconds: Long = System.currentTimeMillis() / 1000L): Boolean {
        if (keyCreatedAt <= 0L || keyExpiresIn <= 0L) return false
        return nowSeconds >= keyCreatedAt + keyExpiresIn - REFRESH_MARGIN_SECONDS
    }

    /** 剩余寿命（秒）。未知时返回 `null`（**不是 0** —— 0 会被读成「已经过期」）。 */
    fun remainingSeconds(nowSeconds: Long = System.currentTimeMillis() / 1000L): Long? {
        if (keyCreatedAt <= 0L || keyExpiresIn <= 0L) return null
        return (keyCreatedAt + keyExpiresIn) - nowSeconds
    }

    /**
     * 刷新成功、但响应没给 `musickeyCreateTime` 时的**有界**时间补全。
     *
     * ## 只在一个有响应证据的前提下补
     *
     * `code=0` 本身只说明「这次刷新被接受」，**不**说明签发了一张新票：服务端可能把
     * 同一张 `musickey` 原样返回。无条件把 `keyCreatedAt` 改成「现在」会凭空延长
     * 本地判定的寿命（用户会在真正过期后才发现），所以这里要求
     * **响应里的 `musickey` 与刷新前不同** —— 那才是「签发了一张新票」的证据。
     *
     * 同 key + 缺时间：保持原值（不猜）。[QqTokenRefresher] 另有一小段成功静默窗口，
     * 防止「刷新成功但本地仍判需续期」退化成每首歌一次网络请求。
     *
     * @param previous 刷新前那份凭证。
     * @param nowSeconds 本次刷新的完成时刻（只在检测到新 key 时使用）。
     */
    fun withRefreshTimestamp(
        previous: QqRefreshCredential,
        nowSeconds: Long = System.currentTimeMillis() / 1000L,
    ): QqRefreshCredential {
        if (keyCreatedAt > 0L) return this
        if (musicKey == null) return this
        val keyRotated = previous.musicKey == null || previous.musicKey != musicKey
        return if (keyRotated) copy(keyCreatedAt = nowSeconds) else this
    }

    /**
     * 关键字段是否齐全到**能换出新票据**。
     *
     * ## 判定条件是**实测**出来的，不是推出来的（v3.4.9 真机，微信扫码那条路）
     *
     * 第一版要求「[refreshKey] 或 [refreshToken] 至少有一个」，于是扫码登录
     * （`loginType = 1`）被一律判成**不可续期** —— 因为服务端在那条路上把
     * `refresh_key` / `refresh_token` / `access_token` 三个键**都给了、值都是空的**。
     * 界面上于是显示「不会自动续期」，而用户三天后照样掉登录。
     *
     * 后来用手上真实的那份凭证实测了一次续期（`RenewProbeLiveTest`）：
     * **只带 `musicid` + `musickey` + `openid`（`refresh_key` 传空串）**
     * 就换回了一张新票（`req.code = 0`），而且响应里 `refresh_token` 这次**有值**。
     *
     * 所以续期的真正必需项只有三样：**身份**（[musicId] / [strMusicId]）、
     * **上一张票**（[musicKey]）、以及能被服务端认出来的登录类型。
     * `refreshKey` / `refreshToken` 是**服务端想不想下发**的事，不是我们的门槛 ——
     * 把它们当门槛，正好把唯一能续的那条路挡在门外。
     *
     * [refreshKey] 只在一种情况下才算必需：**服务端明确索要它**
     * （`needRefreshKeyIn > 0`）。那是它自己说「我要求轮换」的字段，此前一律为空。
     */
    fun isRefreshable(): Boolean = missingFields().isEmpty()

    /** 缺哪些字段（诊断用，只给字段名，**绝不带值**）。齐全时返回空表。 */
    fun missingFields(): List<String> {
        val missing = ArrayList<String>(3)
        if (musicId <= 0L && strMusicId.isNullOrEmpty()) missing += "musicid"
        if (musicKey.isNullOrEmpty()) missing += "musickey"
        // 只有服务端**明确索要**时才把 refreshKey 当必需项（见 KDoc 的实测依据）。
        if (needRefreshKeyIn > 0L && refreshKey.isNullOrEmpty()) missing += "refreshKey"
        return missing
    }

    /**
     * 诊断用摘要：**只有字段名与长度，没有任何值**。
     *
     * 与 [QqCookie.fieldNamesOf] 同一条纪律 —— 票据是账号凭证，
     * 一行日志都不该把它带出去（截图/贴日志时都是泄露面）。
     */
    fun describe(): String = buildString {
        append("musicid=").append(if (musicId > 0L) "set" else "empty")
        append(" loginType=").append(loginType)
        append(" loginTypeResolved=").append(loginTypeResolved)
        append(" refreshKey=").append(len(refreshKey))
        append(" refreshToken=").append(len(refreshToken))
        append(" accessToken=").append(len(accessToken))
        append(" musickey=").append(len(musicKey))
        append(" expiredAt=").append(expiredAt)
        append(" keyCreatedAt=").append(keyCreatedAt)
        append(" keyExpiresIn=").append(keyExpiresIn)
        append(" refreshable=").append(isRefreshable())
    }

    private fun len(value: String?): String =
        if (value.isNullOrEmpty()) "empty" else "len${value.length}"

    companion object {

        /**
         * 主动续期的提前量：票据寿命里**还剩 12 小时**时就换掉。
         *
         * 取值理由：寿命是**周**量级（参考实现与实测样本同量级），12 小时足够覆盖
         * 「用户关掉 App 一整晚再打开」这种最常见的跨期场景，又远小于一周，
         * 不会让「每天首次启动都刷一次」这种事发生（那才是风控风险）。
         */
        const val REFRESH_MARGIN_SECONDS: Long = 12L * 60L * 60L

        /** 登录类型：微信。**只在** `loginType` 缺失时由 `musickey` 前缀推断。 */
        const val LOGIN_TYPE_WECHAT = 1

        /** 登录类型：QQ。 */
        const val LOGIN_TYPE_QQ = 2

        /** 微信侧 `musickey` 的固定前缀（`W_X_…`，见 QQMusicApi 的 `Credential` 文档）。 */
        private const val WECHAT_KEY_PREFIX = "W_X"

        /**
         * 从 `Login` 响应的 `req.data` 里解析续期凭证。
         *
         * ## 字段名必须同时认 snake_case 与旧 camelCase
         *
         * 本仓库 `QqPhoneLoginTest` 里逐字保存的**真实响应骨架**（`Login` 回 `code=1000`
         * 时的 35 字段样本）用的是服务端原始的 snake_case：
         * `refresh_token` / `access_token` / `expired_at` / `refresh_key` / `str_musicid`。
         * 参考实现 `models/request.py::Credential` 的字段名同样是 snake_case。
         *
         * v3.4.9 的解析器只读 camelCase（`refreshToken` / `refreshKey` / …），于是真实
         * 响应里的轮换凭证**一个都没读进来** —— 这不只是「旧 JSON 缺字段」，而是
         * 「字段在、名字不匹配」。表现是第一次续期也许能成，但响应轮换的新
         * `refresh_token` 被丢掉，下一次续期又拿旧值，最终掉登录。
         *
         * 这里对每个字段同时认两种写法：snake_case 优先（服务端原始响应），camelCase
         * 作为旧版本/旧落盘 JSON 的兼容。字段**缺失**返回 null / 0，与「字段存在但
         * 值为空串」在合并时都按「本次没给」处理（`mergeWith` 保留旧值）。
         *
         * ## 解析不设最低门槛
         *
         * 字段缺了也照常返回对象，由 [isRefreshable] 判定能不能用。
         * 理由是这里多一条「不完整就返回 null」，日志里就少一次「到底缺哪个字段」的证据。
         *
         * ## 显式 `loginType = 0` 与「字段缺失」是两种语义
         *
         * 参考实现的 `Credential._infer_login_type()` 只在 **`loginType` 键不存在** 时
         * 才按 `musickey` 前缀推断；显式下发的 `0` 会原样保留。旧实现写的是
         * `rawLoginType.takeIf { it > 0 } ?: infer`，于是手机号登录的显式 `0`
         * 被推成 `2`（QQ），续期请求的 `comm.tmeLoginType` 与参数分支一起错。
         *
         * @param previousLoginType 刷新响应没给 `loginType` 时保留的旧值。
         *   仅由 [QqTokenRefresher] 传入 —— 它手里有「刷新前那份凭证」这个事实；
         *   登录路径传 `null`，仍按参考实现从 `musickey` 推断。
         */
        fun fromLoginData(
            data: JSONObject?,
            previousLoginType: Int? = null,
        ): QqRefreshCredential? {
            if (data == null) return null
            val musicId = data.optLong("musicid", 0L).takeIf { it > 0L }
                ?: stringField(data, "str_musicid", "strMusicId")?.toLongOrNull()?.takeIf { it > 0L }
                ?: 0L
            val musicKey = stringField(data, "musickey")
            // 参考实现同时接受 `loginType` 与 `login_type`（pydantic populate_by_name）；
            // 服务端真实响应骨架是 `loginType`，旧落盘 JSON 也可能是 camelCase。
            // 空串/null 按「本次没给」处理（用 previous 补），只有数字 0 才是「显式手机号」。
            val explicitLoginType = intField(data, "loginType", "login_type")
            val loginType = when {
                explicitLoginType != null -> explicitLoginType
                previousLoginType != null -> previousLoginType
                // 与参考实现一致：只有 musickey 存在时才推断。没有票据的失败骨架
                // 保持 0（未知），不要凭空造出一个「QQ 账号」。
                musicKey?.startsWith(WECHAT_KEY_PREFIX) == true -> LOGIN_TYPE_WECHAT
                musicKey != null -> LOGIN_TYPE_QQ
                else -> 0
            }
            return QqRefreshCredential(
                musicId = musicId,
                musicKey = musicKey,
                refreshKey = stringField(data, "refresh_key", "refreshKey"),
                refreshToken = stringField(data, "refresh_token", "refreshToken"),
                accessToken = stringField(data, "access_token", "accessToken"),
                openId = stringField(data, "openid"),
                unionId = stringField(data, "unionid"),
                strMusicId = stringField(data, "str_musicid", "strMusicId"),
                loginType = loginType,
                // loginType 这一项由本次解析确定（显式值 / previous 补缺失 / 前缀推断），
                // 合并时原样采用，包括显式的 0。直接构造的实例默认 false。
                loginTypeResolved = true,
                // 响应侧的 `expired_at` 是绝对值；`expiredAt` 是同一语义的另一种写法。
                // 只把它当「有值才用」的权威来源，0 继续表示未知。
                expiredAt = longField(data, "expired_at", "expiredAt").coerceAtLeast(0L),
                keyCreatedAt = longField(data, "musickeyCreateTime", "musickey_create_time")
                    .coerceAtLeast(0L),
                keyExpiresIn = longField(data, "keyExpiresIn", "key_expires_in")
                    .coerceAtLeast(0L),
                needRefreshKeyIn = longField(data, "needRefreshKeyIn", "need_refresh_key_in")
                    .coerceAtLeast(0L),
                encryptUin = stringField(data, "encryptUin", "encrypt_uin"),
            )
        }

        /**
         * 读字符串字段：先认第一个名字，再认第二个；空串与缺失都返回 null。
         *
         * 空串在这里的语义是「本次没有下发这个值」，由 [mergeWith] 保留旧值。
         * 不能把空串当作「服务端明确要求清空」—— 服务端把键给全、值全空是它的
         * 失败骨架（35 字段样本），不是一次轮换。
         */
        private fun stringField(data: JSONObject, vararg names: String): String? {
            for (name in names) {
                if (!data.has(name)) continue
                val value = data.optString(name)
                if (value.isNotEmpty()) return value
            }
            return null
        }

        /**
         * 读整数字段：同时认 snake_case 与 camelCase。
         *
         * 返回 `null` = 缺失 / null / 空串 / 非数字；**数字 0 是确定值**。
         * 这正是「显式 loginType=0」与「字段没给」的分界。
         */
        private fun intField(data: JSONObject, vararg names: String): Int? {
            for (name in names) {
                if (!data.has(name) || data.isNull(name)) continue
                when (val raw = data.opt(name)) {
                    is Number -> return raw.toInt()
                    is String -> raw.trim().toIntOrNull()?.let { return it }
                }
            }
            return null
        }

        /** 读长整数字段：同时认 snake_case 与 camelCase；缺失/非数字返回 0。 */
        private fun longField(data: JSONObject, vararg names: String): Long {
            for (name in names) {
                if (data.has(name)) return data.optLong(name, 0L)
            }
            return 0L
        }

        /**
         * 用一份**已知有效**的凭证里现存的信息补全字段。
         *
         * 为什么需要：`refresh_key` 是长期凭证、`refresh_token` 是轮换凭证 ——
         * 腾讯在刷新响应里**可能只回其中一部分**（参考实现把两者都当可选字段）。
         * 一次刷新若把 `refreshKey` 换成空，下一次刷新就失败了 ——
         * 于是「能续期一次」变成「续期一次之后照样掉登录」，
         * 而这比完全没有续期更难查（用户会以为「修了一半」）。
         *
         * 规则：**新值非空就用新值，为空就保留旧值**；[keyCreatedAt] 以本次响应为准
         * （它是这次签发的证据）。旧值为 null 时 [old] 为 null，直接返回 [this]。
         *
         * `loginType` 的「0 是手机号、不是缺失」同样适用于合并：旧实现写
         * `if (new.loginType > 0) new else old`，于是新响应显式给 0 时会被旧值
         * 覆盖。现在用 [loginTypeResolved] 区分「解析器确定的值（含显式 0）」与
         * 「数据类默认值 0（未知）」：
         * - 解析器确定 ⇒ **原样采用，包括 0**；
         * - 直接构造且非 0 ⇒ 采用新值；
         * - 直接构造且为 0 ⇒ 保留旧值，避免把已知类型降级成未知。
         */
        fun mergeWith(old: QqRefreshCredential?, new: QqRefreshCredential): QqRefreshCredential {
            if (old == null) return new
            val useNewLoginType = new.loginTypeResolved || new.loginType != 0
            val mergedLoginTypeResolved = when {
                new.loginTypeResolved -> true
                new.loginType != 0 -> true
                new.loginType == old.loginType -> old.loginTypeResolved
                else -> false
            }
            return new.copy(
                musicId = if (new.musicId > 0L) new.musicId else old.musicId,
                musicKey = new.musicKey ?: old.musicKey,
                refreshKey = new.refreshKey ?: old.refreshKey,
                refreshToken = new.refreshToken ?: old.refreshToken,
                accessToken = new.accessToken ?: old.accessToken,
                openId = new.openId ?: old.openId,
                unionId = new.unionId ?: old.unionId,
                strMusicId = new.strMusicId ?: old.strMusicId,
                loginType = if (useNewLoginType) new.loginType else old.loginType,
                loginTypeResolved = mergedLoginTypeResolved,
                // 时间字段的意义是「这一版票是什么时候签的」，所以**以新响应为准**；
                // 新响应没给（0）时才保留旧的，免得把有依据的判断退化成「不知道」。
                expiredAt = if (new.expiredAt > 0L) new.expiredAt else old.expiredAt,
                keyCreatedAt = if (new.keyCreatedAt > 0L) new.keyCreatedAt else old.keyCreatedAt,
                keyExpiresIn = if (new.keyExpiresIn > 0L) new.keyExpiresIn else old.keyExpiresIn,
                needRefreshKeyIn = if (new.needRefreshKeyIn > 0L) {
                    new.needRefreshKeyIn
                } else {
                    old.needRefreshKeyIn
                },
                encryptUin = new.encryptUin ?: old.encryptUin,
            )
        }
    }
}
