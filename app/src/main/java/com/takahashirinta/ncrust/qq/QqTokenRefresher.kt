/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.4.9：qm 登录态的**静默续期**（用户报障：登录身份一周左右就掉）。
 * v3.4.13：久驻进程的使用时触发、迟到响应闸门、失败原因不再折叠、显式 loginType=0。
 */

package com.takahashirinta.ncrust.qq

import android.content.Context
import android.util.Log
import com.takahashirinta.ncrust.BuildConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * qm 登录态的**静默续期**。
 *
 * ## 它解决的是哪一个用户可见的故障
 *
 * 报障原话：「qq 音乐登录身份在一周左右就会掉，重新登录很麻烦」。
 * 根因不是「腾讯的票据不稳定」，而是**本客户端从来没有续过期**：
 * 登录响应里那些能换新票的字段（`refreshKey` / `refreshToken` / `accessToken`）
 * 在 v3.4.8 及之前**一个都没落盘**（见 [QqRefreshCredential] 的 KDoc）。
 * 票据到期 ⇒ 手里没有可换票的东西 ⇒ 只能重新登录。这条链路上没有任何一步是概率的。
 *
 * ## 三条触发路径，缺一不可
 *
 * | 路径 | 触发点 | 为什么必须有 |
 * |---|---|---|
 * | **主动·冷启动** | [refreshIfNeeded]（`QqMusicSourceProvider.install`），票据寿命还剩 < 12 h 时 | 用户**感觉不到** |
 * | **主动·使用时** | [refreshIfNeeded]（`QqApi.requestVkeyBatch` 每次取链前） | 修复「进程久驻 / 后台跨过整个到期窗口 ⇒ 只有冷启动那一次机会」 |
 * | **被动** | 取链被服务端明确拒绝（`QqApi` 的 [refreshAfterRejection]） | 寿命未知的老凭证、或主动路径没覆盖到的时钟异常 |
 *
 * 只有被动 = 每次到期都要先失败一次（用户看到一次「要会员/去登录」的错误）；
 * 只有冷启动主动 = 进程活过一周时，第二次机会永远不出现。三条都在才是完整的。
 *
 * ⚠️ **不引入后台定时器 / 网络心跳**：使用时触发只在「用户真的在取链」的那一刻发生，
 * 冷启动触发只在 Activity 创建时发生。没有 WorkManager，也没有周期唤醒。
 *
 * ## 四条并发与风控纪律
 *
 * 1. **单飞**：[mutex] 保证同一时刻只有一次刷新在飞。
 * 2. **失败冷却**：[FAILED_COOLDOWN_MS] 内不再试，避免「每首歌 × 每档位」放大失败请求。
 * 3. **迟到响应闸门**：响应落盘前由 [QqRefreshCommitGuard] 比对「请求开始时的凭证 +
 *    登录态版本」与当前盘上的状态；登出、同账号重登、换号、另一个刷新先完成时一律丢弃。
 *    旧实现直接写回，会把新登录态覆盖成旧账号/旧票且不报错。
 * 4. **终态不重试**：服务端明确说凭证失效（`AUTH_EXPIRED`）后，本进程内不再对同一份
 *    登录态重复发刷新请求；用户重新登录会推进登录态版本，自动解除。网络失败、限流、
 *    未知错误**不会**被标成凭证失效，只会进入冷却后重试。
 *
 * ## 合规边界（与整个 qm 侧一致）
 *
 * 只用**用户自己登录时服务端下发的凭证**换新票，不采集密码、不代管账号、
 * 不绕过任何鉴权 —— 服务端说不行就是不行（[RefreshResult.AUTH_EXPIRED]）。
 * 全程没有任何网络出口是自己发起的：唯一的出口是 [QqClient.musicuRefresh]，
 * 也就是登录那条已经存在的通道。
 */
object QqTokenRefresher {

    private const val TAG = "QqTokenRefresher"

    /**
     * 失败冷却：60 秒。
     *
     * 取值理由：取链失败是按「每首歌 × 每档位」发生的，冷却太短等于没有冷却；
     * 太长（几分钟）会让「刚开机网络不好时的失败」把接下来几分钟的续期机会全部吃掉。
     * 60 秒与「用户重试一次播放」的节奏同量级。
     */
    private const val FAILED_COOLDOWN_MS = 60_000L

    /**
     * 刷新**成功**后的静默窗口：10 分钟。
     *
     * 正常路径下刷新成功会带回新的签发时间，`needsRefresh()` 立刻变 false，这个窗口
     * 不会生效。它守的是一个边界：响应 `code=0` 但**没有**新的 `musickeyCreateTime`
     * 且票据未轮换 —— 本地无法证明寿命被延长，`needsRefresh()` 仍可能为 true。
     * 没有这个窗口，取链前的主动续期会退化成「每首歌一次刷新」，把一次成功变成风控请求。
     *
     * 它**不是**定时器：只在一次成功之后抑制后续主动/被动刷新；用户重新登录/登出
     * 会推进登录态版本，窗口自动失效。
     */
    private const val SUCCESS_QUIET_MS = 10 * 60_000L

    /**
     * 刷新请求的响应码归类。**纯函数、JVM 可单测。**
     *
     * 与 `Login`（登录）的码表**不是一套**：登录侧 `1000` 是「验证码错」，
     * 而续期侧参考实现把 `1000` / `104400` / `104401` 一律归成
     * `LoginAuthExpiredError`（**鉴权参数无效或已过期**）—— 因为续期没有验证码可错，
     * 同一个码在这个上下文里只有一个含义：凭证本身不被接受了。
     *
     * `RATE_LIMITED` / `FAILED` **不是**凭证过期：前者过一会儿还能试，
     * 后者可能只是网络/响应畸形。把它们折叠成 `AUTH_EXPIRED` 会让一次网络抖动
     * 变成「请重新登录」，这正是要避免的误判。
     */
    enum class RefreshOutcome {
        /** `0`：换到了新票据。 */
        REFRESHED,

        /** `1000` / `104400` / `104401`：凭证不再被接受 —— **重试没有意义**。 */
        AUTH_EXPIRED,

        /** `104604` / `100001`：太频繁。下次仍然值得试。 */
        RATE_LIMITED,

        /** `20277` / `20278` / `20450`：账号受限。 */
        ACCOUNT_RESTRICTED,

        /** `20279`：登录设备数超限。 */
        DEVICE_LIMIT,

        /** 其它非 0 码。**未知 ≠ 过期**，保留重试机会。 */
        FAILED,
    }

    fun classifyRefresh(reqCode: Int): RefreshOutcome = when (reqCode) {
        0 -> RefreshOutcome.REFRESHED
        1000, 104400, 104401 -> RefreshOutcome.AUTH_EXPIRED
        100001, 104604 -> RefreshOutcome.RATE_LIMITED
        20277, 20278, 20450 -> RefreshOutcome.ACCOUNT_RESTRICTED
        20279 -> RefreshOutcome.DEVICE_LIMIT
        else -> RefreshOutcome.FAILED
    }

    /** 一次续期尝试的结局。 */
    enum class RefreshResult {
        /** 换到了新票据，cookie 与续期凭证都已落盘。 */
        REFRESHED,

        /**
         * 没有可以续期的凭证（v3.4.9 之前登录的用户、或已经登出）。
         *
         * **这是本次迁移的正常形态**，不是错误：老用户的表现为
         * 「行为与 v3.4.8 完全一致」，重新登录一次之后就再也不会走到这里。
         */
        NO_CREDENTIAL,

        /**
         * 服务端明确不接受这份凭证（[RefreshOutcome.AUTH_EXPIRED]）。
         *
         * **终态**：本进程内对同一份登录态不再自动重试。调用方应提示重新登录，
         * 但**不能**把它与网络失败混为一谈。
         */
        AUTH_EXPIRED,

        /** 服务端限流（`104604` / `100001`）。冷却后仍可重试。 */
        RATE_LIMITED,

        /** 账号受限（`20277` / `20278` / `20450`）。不是凭证过期。 */
        ACCOUNT_RESTRICTED,

        /** 登录设备数超限（`20279`）。不是凭证过期。 */
        DEVICE_LIMIT,

        /** 传输层失败 / 未知业务码 / 响应畸形：**不是**凭证过期，冷却后可重试。 */
        RETRYABLE,

        /** `code=0` 但响应里没有可落盘的 cookie（半份凭证），本次不覆盖旧登录态。 */
        INVALID_RESPONSE,

        /** 有另一次刷新正在飞 —— 这次什么都没做（不是失败）。 */
        SKIPPED_IN_FLIGHT,

        /** 刚失败过，还在冷却里。 */
        SKIPPED_COOLDOWN,

        /** 响应回来时登录态已被登出/重登/换号/更新，**拒绝覆盖**。 */
        SKIPPED_STALE,

        /** 刚成功刷新过，仍在 [SUCCESS_QUIET_MS] 的静默窗口内（避免每首歌一次请求）。 */
        SKIPPED_RECENT_SUCCESS,

        /** 这次调用**没有需要刷新**（票据还早 / 寿命未知）。 */
        NOT_NEEDED,
    }

    private val mutex = Mutex()

    @Volatile
    private var lastFailedAtMs: Long = 0L

    /**
     * 上一次失败所属的登录态版本。
     *
     * 冷却必须跟着会话走：旧请求失败把 `lastFailedAtMs` 写下来后，用户同号重登/换号
     * 会推进版本；新会话若仍被这 60 秒挡住，就又是「旧失败污染新会话」。
     */
    @Volatile
    private var lastFailedRevision: Long = -1L

    /** 上一次成功刷新的完成时刻与当时的登录态版本。 */
    @Volatile
    private var lastSuccessAtMs: Long = 0L

    @Volatile
    private var lastSuccessRevision: Long = -1L

    /**
     * 服务端明确判过「凭证失效」时的登录态版本。
     *
     * `-1` = 没有终态。记录版本而不是布尔：用户重新登录/登出都会推进
     * [QqAuthStore.currentAuthRevision]，旧终态自动失效，不需要任何清理钩子。
     */
    @Volatile
    private var authRejectedRevision: Long = -1L

    /**
     * 主动续期：**只在需要时**发请求。
     *
     * 冷启动与每次取链前都会调用。窗口检查（[QqRefreshCredential.needsRefresh]）
     * 保证「不需要时一个请求都不发」，所以它可以安全地放在热路径上。
     *
     * @param appContext 进程级的 application context（见 `QqMusicSourceProvider.install`）。
     * @return 结局。调用方**不需要**处理返回值 —— 它唯一的用途是日志与单测。
     */
    suspend fun refreshIfNeeded(appContext: Context?): RefreshResult {
        val ctx = appContext ?: return RefreshResult.NO_CREDENTIAL
        if (isAuthRejectedForCurrentSession()) return RefreshResult.AUTH_EXPIRED
        // 已登出时盘上若还残留旧凭证，绝不能拿它去刷新（会把登出状态又写回来）。
        if (!QqAuthStore.isLoggedIn(ctx)) return RefreshResult.NO_CREDENTIAL
        if (isRecentlySucceeded()) return RefreshResult.SKIPPED_RECENT_SUCCESS
        val stored = QqRefreshStore.get(ctx) ?: return RefreshResult.NO_CREDENTIAL
        if (!isUsableFor(ctx, stored)) return RefreshResult.NO_CREDENTIAL
        if (!stored.needsRefresh()) return RefreshResult.NOT_NEEDED
        return refresh(appContext, force = false)
    }

    /**
     * 被动续期：**只在取链真的被服务端拒绝之后**才调用（见 [QqApi] 的调用点）。
     *
     * 与 [refreshIfNeeded] 的差别只有一条：**不看本地寿命**，直接换。
     * 理由是走到这里已经有一个权威事实了 —— 服务端刚拒过这张票，
     * 本地时钟/寿命推算在此刻没有发言权（它正是可能不准的那个）。
     */
    suspend fun refreshAfterRejection(appContext: Context?): RefreshResult =
        refresh(appContext, force = true)

    /**
     * 诊断用：当前是否**具备**续期能力（不发任何请求）。
     *
     * 存在的意义是把「登录态掉了」分成两类：能续（静默恢复）与不能续（要重新登录）。
     * 设置页与日志都需要这个区分，而它只能从落盘的凭证判断。
     */
    fun canRefresh(context: Context): Boolean {
        if (isAuthRejectedForCurrentSession()) return false
        val stored = QqRefreshStore.get(context) ?: return false
        return isUsableFor(context, stored)
    }

    /** 票据寿命还剩多少秒（本地推算，未发请求）。未知返回 `null`。 */
    fun remainingSeconds(context: Context): Long? = QqRefreshStore.get(context)?.remainingSeconds()

    /** 当前是否已经进入主动续期窗口（只看 `keyCreatedAt + keyExpiresIn`）。 */
    fun needsRefresh(context: Context): Boolean =
        QqRefreshStore.get(context)?.needsRefresh() == true

    private suspend fun refresh(appContext: Context?, force: Boolean): RefreshResult {
        val ctx = appContext ?: return RefreshResult.NO_CREDENTIAL
        if (isAuthRejectedForCurrentSession()) return RefreshResult.AUTH_EXPIRED
        if (!QqAuthStore.isLoggedIn(ctx)) return RefreshResult.NO_CREDENTIAL
        // 成功后的静默窗口对主动/被动都生效：服务端刚接受过刷新、紧接着又拒取链，
        // 再发一次刷新既救不了这次播放，也会把「一首歌一次」放大成风控请求。
        if (isRecentlySucceeded()) return RefreshResult.SKIPPED_RECENT_SUCCESS
        // 冷却检查放在抢锁**之前**：并发调用里排在后面的那些应当立刻得到 SKIPPED_COOLDOWN，
        // 而不是排队等到拿到锁之后再发现「刚失败过」。
        if (!force && isCoolingDown()) return RefreshResult.SKIPPED_COOLDOWN
        if (mutex.isLocked) return RefreshResult.SKIPPED_IN_FLIGHT
        return mutex.withLock {
            // 拿到锁之后再读一次：等锁期间别人可能已经刷成功了。
            if (isAuthRejectedForCurrentSession()) return@withLock RefreshResult.AUTH_EXPIRED
            if (!QqAuthStore.isLoggedIn(ctx)) return@withLock RefreshResult.NO_CREDENTIAL
            if (isRecentlySucceeded()) return@withLock RefreshResult.SKIPPED_RECENT_SUCCESS
            val stored = QqRefreshStore.get(ctx) ?: return@withLock RefreshResult.NO_CREDENTIAL
            if (!isUsableFor(ctx, stored)) return@withLock RefreshResult.NO_CREDENTIAL
            // force 路径也要尊重冷却：否则「一首歌失败 → 每首歌都再试一次」。
            if (isCoolingDown()) return@withLock RefreshResult.SKIPPED_COOLDOWN
            // 版本必须在**发请求前**取。任何登录/登出/另一份凭证写入都会推进它。
            val startedRevision = QqAuthStore.currentAuthRevision()
            doRefresh(ctx, stored, startedRevision)
        }
    }

    private suspend fun doRefresh(
        ctx: Context,
        stored: QqRefreshCredential,
        startedRevision: Long,
    ): RefreshResult {
        val cookie = QqAuthStore.getCookie(ctx)
        val request = QqRequests.refreshCredential(stored, cookie)
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "refresh attempt credential={" + stored.describe() + "}")
        }
        // ⚠️ 必须走 musicuRefresh 而**不是** musicuLogin：后者会给 comm 塞
        // `tmeLoginMethod = 3`（手机验证码登录专用），而续期带上它会被服务端拒
        // （实测 code=1000；去掉就是 code=0 + 新票）。详见 QqClient.musicuRefresh 的 KDoc。
        val response = QqClient.musicuRefresh(request, stored.loginType) ?: run {
            // 传输层失败：**不是凭证过期**。是否进冷却必须绑定「这次请求属于的登录态」——
            // 旧请求迟到时不能把新会话也冷却掉。
            return applyFailureIfStillCurrent(
                ctx = ctx,
                started = stored,
                startedRevision = startedRevision,
                result = RefreshResult.RETRYABLE,
                reason = "transport: ${QqClient.lastTransportError}",
            )
        }
        val reqCode = response.optInt("code", -1)
        return when (classifyRefresh(reqCode)) {
            RefreshOutcome.REFRESHED ->
                onRefreshed(ctx, response.optJSONObject("data"), stored, startedRevision)

            RefreshOutcome.AUTH_EXPIRED -> applyFailureIfStillCurrent(
                ctx = ctx,
                started = stored,
                startedRevision = startedRevision,
                result = RefreshResult.AUTH_EXPIRED,
                reason = "req.code=$reqCode outcome=AUTH_EXPIRED（终态：需重新登录）",
            )

            RefreshOutcome.RATE_LIMITED -> applyFailureIfStillCurrent(
                ctx = ctx,
                started = stored,
                startedRevision = startedRevision,
                result = RefreshResult.RATE_LIMITED,
                reason = "req.code=$reqCode outcome=RATE_LIMITED（限流，可重试）",
            )

            RefreshOutcome.ACCOUNT_RESTRICTED -> applyFailureIfStillCurrent(
                ctx = ctx,
                started = stored,
                startedRevision = startedRevision,
                result = RefreshResult.ACCOUNT_RESTRICTED,
                reason = "req.code=$reqCode outcome=ACCOUNT_RESTRICTED（非凭证过期）",
            )

            RefreshOutcome.DEVICE_LIMIT -> applyFailureIfStillCurrent(
                ctx = ctx,
                started = stored,
                startedRevision = startedRevision,
                result = RefreshResult.DEVICE_LIMIT,
                reason = "req.code=$reqCode outcome=DEVICE_LIMIT（非凭证过期）",
            )

            RefreshOutcome.FAILED -> applyFailureIfStillCurrent(
                ctx = ctx,
                started = stored,
                startedRevision = startedRevision,
                result = RefreshResult.RETRYABLE,
                reason = "req.code=$reqCode outcome=FAILED（未知，可重试）",
            )
        }
    }

    /**
     * 把一次失败的所有**副作用**（冷却时间、AUTH_EXPIRED 终态）绑定到发起请求时的会话。
     *
     * 旧实现只保护了成功响应的落盘；拒绝/传输失败仍然无条件 `markFailed()`，
     * 而且 `AUTH_EXPIRED` 记的是**当前**版本。于是「旧请求迟到返回 1000，期间用户
     * 已同号重登/换号」会把新会话记成终态、或者把新会话拖进 60 秒冷却 ——
     * 新凭证明明是好的，却被旧请求的失败污染。
     *
     * 与成功落盘用同一个 [QqRefreshCommitGuard]：在登录态锁内重新确认
     * 「仍登录 + 版本未变 + 盘上还是发起时那份凭证 + uin 对得上」。任何一条不成立，
     * 这次失败就只属于旧会话，直接丢弃、不写任何影响后续刷新的状态。
     */
    private fun applyFailureIfStillCurrent(
        ctx: Context,
        started: QqRefreshCredential,
        startedRevision: Long,
        result: RefreshResult,
        reason: String,
    ): RefreshResult = QqAuthStore.withAuthLock {
        val decision = QqRefreshCommitGuard.decide(
            started = started,
            current = QqRefreshStore.get(ctx),
            currentUin = QqAuthStore.uin(ctx),
            loggedIn = QqAuthStore.isLoggedIn(ctx),
            startedRevision = startedRevision,
            currentRevision = QqAuthStore.authRevisionLocked(),
        )
        if (decision != QqRefreshCommitGuard.Decision.COMMIT) {
            Log.i(TAG, "丢弃迟到的刷新失败（$reason）：$decision")
            return@withAuthLock RefreshResult.SKIPPED_STALE
        }
        markFailed()
        if (result == RefreshResult.AUTH_EXPIRED) {
            // 记**发起时的版本**，不是当前版本：虽然此刻两者相等（锁内已确认），
            // 但语义上这个终态只属于这一份登录态，写 startedRevision 让这个约束显式。
            authRejectedRevision = startedRevision
        }
        Log.w(TAG, "refresh rejected $reason")
        result
    }

    private fun onRefreshed(
        ctx: Context,
        data: JSONObject?,
        previous: QqRefreshCredential,
        startedRevision: Long,
    ): RefreshResult {
        // 与登录**共用同一个凭证读取器**：两个响应是同一个 `Login` 方法的同一个形状，
        // 各写一份字段映射只会得到两份迟早会分叉的契约。
        val newCookie = QqPhoneLogin.cookieFromCredential(data)
        if (newCookie == null) {
            return applyFailureIfStillCurrent(
                ctx = ctx,
                started = previous,
                startedRevision = startedRevision,
                result = RefreshResult.INVALID_RESPONSE,
                reason = "success response without a usable credential",
            )
        }
        val newCredential = QqRefreshCredential.fromLoginData(data, previous.loginType)
            ?.withRefreshTimestamp(previous)
            ?: return applyFailureIfStillCurrent(
                ctx = ctx,
                started = previous,
                startedRevision = startedRevision,
                result = RefreshResult.INVALID_RESPONSE,
                reason = "success response without data",
            )

        // 检查与写入必须在同一把登录态锁内：否则「检查完、还没写」的间隙里
        // 用户登出/重登，旧响应仍然会覆盖新登录态。
        return QqAuthStore.withAuthLock {
            val current = QqRefreshStore.get(ctx)
            val decision = QqRefreshCommitGuard.decide(
                started = previous,
                current = current,
                currentUin = QqAuthStore.uin(ctx),
                loggedIn = QqAuthStore.isLoggedIn(ctx),
                startedRevision = startedRevision,
                currentRevision = QqAuthStore.authRevisionLocked(),
            )
            if (decision != QqRefreshCommitGuard.Decision.COMMIT) {
                // 丢弃一次刷新不是失败：登录态已经比这份响应更新，写回去才是事故。
                Log.i(TAG, "丢弃迟到的刷新响应：$decision")
                return@withAuthLock RefreshResult.SKIPPED_STALE
            }
            // 先落 cookie 再落凭证：万一第二步失败，下一次取链用的还是有效票据
            // （反过来的话，会出现「票据是旧的、凭证是新的」这种自相矛盾的状态）。
            QqAuthStore.saveCookie(ctx, newCookie)
            val saved = QqRefreshStore.save(ctx, newCredential)
            lastFailedAtMs = 0L
            lastSuccessAtMs = System.currentTimeMillis()
            lastSuccessRevision = QqAuthStore.currentAuthRevision()
            authRejectedRevision = -1L
            Log.i(
                TAG,
                "qm 登录态已静默续期 newCredential={" + saved.describe() + "}",
            )
            if (saved.needsRefresh()) {
                // 响应成功但本地仍判需续期：通常是没有新的签发时间、且 musickey 未轮换。
                // 不伪造时间；只进入成功静默窗口，避免下一次取链立刻再刷一次。
                Log.w(
                    TAG,
                    "刷新成功但本地仍判需续期（响应缺签发时间/票据未轮换），" +
                        "进入 ${SUCCESS_QUIET_MS / 60_000} 分钟静默窗口",
                )
            }
            RefreshResult.REFRESHED
        }
    }

    /**
     * 这份凭证**是否属于当前登录的那个账号**。
     *
     * 防的是「本地串号」：用户登出 A 账号、用 B 账号登录，而 A 的续期凭证还留在盘上。
     * 拿 A 的凭证去刷新会把 B 的登录态换成 A 的（用户看到的是「登录着 B、数据全是 A」），
     * 而且全程不报错。判据是 musicid 必须与 cookie 里的 uin 一致 ——
     * 两者都来自同一个 `Login` 响应（[QqPhoneLogin.cookieFromCredential] 里
     * `uin` 就是 `musicid`），所以它们**必须**相等。
     *
     * 不一致时**只返回 false，不删除任何东西**。
     *
     * v3.4.13 的第一版在这里 `QqRefreshStore.clear(ctx)`，理由是「清掉上一个账号的残留」。
     * 但这是一个**读路径**（`canRefresh` / `refreshIfNeeded` 的前置检查），
     * `stored` 是调用方读到的旧快照，而 `uin`、当前凭证都可能已经被另一个线程
     * 换成新账号 —— 此时删除会把新账号刚保存的凭证清掉，且没有任何错误日志。
     *
     * 残留凭证不会被误用：这条路径返回 false 后不会发刷新请求；登录路径本来就会
     * 先 `QqRefreshStore.clear` 再写新凭证，登出路径也一样。所以这里**不做破坏性清理**，
     * 只把「这份快照能不能用」作为纯判据返回。
     *
     * `internal`（而不是 private）是为了让独立 JVM harness 能直接构造
     * 「旧 snapshot + 新账号已落盘」的竞态并断言新凭证仍在；它不是公开 API。
     */
    internal fun isUsableFor(ctx: Context, stored: QqRefreshCredential): Boolean {
        if (!stored.isRefreshable()) {
            // 缺字段的原因只有两种：老版本落的（没有凭证）或响应不完整。
            // 只在 debug 里打，它的信息量与「不能续期」等价。
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "credential not refreshable missing=" + stored.missingFields())
            }
            return false
        }
        val uin = QqAuthStore.uin(ctx)
        if (uin != null && stored.musicId > 0L && uin != stored.musicId) {
            Log.w(TAG, "续期凭证快照属于另一个账号（uin 与 musicid 不一致），本次不续期；不清理当前凭证")
            return false
        }
        return true
    }

    /** 本进程内，服务端是否已对**当前这份**登录态明确判过凭证失效。 */
    private fun isAuthRejectedForCurrentSession(): Boolean {
        val rejected = authRejectedRevision
        return rejected >= 0L && rejected == QqAuthStore.currentAuthRevision()
    }

    private fun isCoolingDown(): Boolean {
        val at = lastFailedAtMs
        if (at <= 0L) return false
        // 版本变了 = 这是另一份登录态；旧会话的冷却不能挡住新会话。
        if (lastFailedRevision != QqAuthStore.currentAuthRevision()) return false
        return System.currentTimeMillis() - at < FAILED_COOLDOWN_MS
    }

    /**
     * 刚刚成功刷新过，且登录态版本没变。
     *
     * 版本必须比对：用户在一次成功之后重新登录/登出，旧的成功窗口不能继续压制
     * 新登录态的主动续期。
     */
    private fun isRecentlySucceeded(): Boolean {
        val at = lastSuccessAtMs
        if (at <= 0L) return false
        if (System.currentTimeMillis() - at >= SUCCESS_QUIET_MS) return false
        return lastSuccessRevision == QqAuthStore.currentAuthRevision()
    }

    private fun markFailed() {
        lastFailedAtMs = System.currentTimeMillis()
        lastFailedRevision = QqAuthStore.currentAuthRevision()
    }
}
