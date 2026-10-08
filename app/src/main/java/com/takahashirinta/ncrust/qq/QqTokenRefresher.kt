/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.4.9：qm 登录态的**静默续期**（用户报障：登录身份一周左右就掉）。
 */

package com.takahashirinta.ncrust.qq

import android.content.Context
import android.util.Log
import com.takahashirinta.ncrust.BuildConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * qm 登录态的**静默续期**（v3.4.9）。
 *
 * ## 它解决的是哪一个用户可见的故障
 *
 * 报障原话：「qq 音乐登录身份在一周左右就会掉，重新登录很麻烦」。
 * 根因不是「腾讯的票据不稳定」，而是**本客户端从来没有续过期**：
 * 登录响应里那些能换新票的字段（`refreshKey` / `refreshToken` / `accessToken`）
 * 在 v3.4.8 及之前**一个都没落盘**（见 [QqRefreshCredential] 的 KDoc）。
 * 票据到期 ⇒ 手里没有可换票的东西 ⇒ 只能重新登录。这条链路上没有任何一步是概率的。
 *
 * ## 两条触发路径，缺一不可
 *
 * | 路径 | 触发点 | 为什么必须有 |
 * |---|---|---|
 * | **主动** | 冷启动（[refreshIfNeeded]），票据寿命还剩 < 12 h 时 | 用户**感觉不到**。等到过期再换，用户会在「正在播歌」的那一刻撞上一次可见的失败 |
 * | **被动** | 取链被服务端拒（[refreshAfterRejection]） | 寿命未知的老凭证、或用户把 App 挂在后台跨过了整个到期窗口 —— 主动路径覆盖不到这些情况 |
 *
 * 只有被动 = 每次到期都要先失败一次（用户看到一次「要会员/去登录」的错误）；
 * 只有主动 = 时钟不准、`keyExpiresIn` 缺失、或后台常驻时全都漏掉。两条都在才是完整的。
 *
 * ## 三条并发与风控纪律
 *
 * 1. **单飞**：[mutex] 保证同一时刻只有一次刷新在飞。冷启动与播放链路可能同时触发，
 *    并发刷新会各自拿一份旧凭证去换票，后到的那次可能用**已经作废的**旧票把新票覆盖掉
 *    （参考实现专门为此在锁内比对「池内最新凭证是否已被别人刷新」）。
 * 2. **失败冷却**：一次失败后 [FAILED_COOLDOWN_MS] 内不再试。取链是**每首歌**都会调用的
 *    高频路径，没有冷却就会出现「一首歌 + 一次注定失败的刷新」；
 *    而没有登录态时取链本来就会走到这里 —— 那正是最容易被放大成风控请求的形态。
 * 3. **不做定时任务**：刷新只在「用户真的在用」的时刻发生（冷启动 / 取链被拒），
 *    不引入 WorkManager 那种后台唤醒 —— 那会把「续期」变成「定期向腾讯报到的机器人」，
 *    与本 fork「不伪造客户端行为」的定位不符，收益也只有一个后台唤醒的复杂度。
 *
 * ## 合规边界（与整个 qm 侧一致）
 *
 * 只用**用户自己登录时服务端下发的凭证**换新票，不采集密码、不代管账号、
 * 不绕过任何鉴权 —— 服务端说不行就是不行（[QqTokenRefresher.RefreshResult.Failed]）。
 * 全程没有任何网络出口是自己发起的：唯一的出口是 [QqClient.musicuLogin]，
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
     * 刷新请求的响应码归类（v3.4.9）。**纯函数、JVM 可单测。**
     *
     * 与 `Login`（登录）的码表**不是一套**：登录侧 `1000` 是「验证码错」，
     * 而续期侧参考实现把 `1000` / `104400` / `104401` 一律归成
     * `LoginAuthExpiredError`（**鉴权参数无效或已过期**）—— 因为续期没有验证码可错，
     * 同一个码在这个上下文里只有一个含义：凭证本身不被接受了。
     *
     * 这个区分不是学术性的：`AuthExpired` 是**终态**（再试也没用，该让用户重新登录），
     * `Failed` 是**可重试**的（网络抖动、未知码）。把它们折叠成一个 `false`
     * 正是本仓库反复修过的「静默失败伪装成没有数据」。
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

        /** 其它非 0 码。 */
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

        /** 服务端不接受这份凭证（[RefreshOutcome.AUTH_EXPIRED]），或响应不成形。**要重新登录**。 */
        FAILED,

        /** 有另一次刷新正在飞 —— 这次什么都没做（不是失败）。 */
        SKIPPED_IN_FLIGHT,

        /** 刚失败过，还在冷却里。 */
        SKIPPED_COOLDOWN,

        /** 这次调用**没有需要刷新**（票据还早 / 寿命未知）。 */
        NOT_NEEDED,
    }

    private val mutex = Mutex()

    @Volatile
    private var lastFailedAtMs: Long = 0L

    /**
     * 主动续期：**只在需要时**发请求。
     *
     * @param appContext 进程级的 application context（见 `QqMusicSourceProvider.install`）。
     * @return 结局。调用方**不需要**处理返回值 —— 它唯一的用途是日志与单测。
     */
    suspend fun refreshIfNeeded(appContext: Context?): RefreshResult {
        val ctx = appContext ?: return RefreshResult.NO_CREDENTIAL
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
        val stored = QqRefreshStore.get(context) ?: return false
        return isUsableFor(context, stored)
    }

    /** 票据寿命还剩多少秒（本地推算，未发请求）。未知返回 `null`。 */
    fun remainingSeconds(context: Context): Long? = QqRefreshStore.get(context)?.remainingSeconds()

    private suspend fun refresh(appContext: Context?, force: Boolean): RefreshResult {
        val ctx = appContext ?: return RefreshResult.NO_CREDENTIAL
        // 冷却检查放在抢锁**之前**：并发调用里排在后面的那些应当立刻得到 SKIPPED_COOLDOWN，
        // 而不是排队等到拿到锁之后再发现「刚失败过」。
        if (!force && isCoolingDown()) return RefreshResult.SKIPPED_COOLDOWN
        if (mutex.isLocked) return RefreshResult.SKIPPED_IN_FLIGHT
        return mutex.withLock {
            // 拿到锁之后再读一次：等锁期间别人可能已经刷成功了。
            val stored = QqRefreshStore.get(ctx) ?: return@withLock RefreshResult.NO_CREDENTIAL
            if (!isUsableFor(ctx, stored)) return@withLock RefreshResult.NO_CREDENTIAL
            // force 路径也要尊重冷却：否则「一首歌失败 → 每首歌都再试一次」。
            if (isCoolingDown()) return@withLock RefreshResult.SKIPPED_COOLDOWN
            doRefresh(ctx, stored)
        }
    }

    private suspend fun doRefresh(ctx: Context, stored: QqRefreshCredential): RefreshResult {
        val cookie = QqAuthStore.getCookie(ctx)
        val request = QqRequests.refreshCredential(stored, cookie)
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "refresh attempt credential={" + stored.describe() + "}")
        }
        // ⚠️ 必须走 musicuRefresh 而**不是** musicuLogin：后者会给 comm 塞
        // `tmeLoginMethod = 3`（手机验证码登录专用），而续期带上它会被服务端拒
        // （实测 code=1000；去掉就是 code=0 + 新票）。详见 QqClient.musicuRefresh 的 KDoc。
        val response = QqClient.musicuRefresh(request, stored.loginType) ?: run {
            // 传输层失败：算失败但**不推进冷却的语义**是「值得马上再试」——
            // 这里仍然进冷却，因为「网络不通」时每首歌都重试一次是最糟的形态。
            markFailed()
            Log.w(TAG, "refresh transport failed: ${QqClient.lastTransportError}")
            return RefreshResult.FAILED
        }
        val reqCode = response.optInt("code", -1)
        return when (val outcome = classifyRefresh(reqCode)) {
            RefreshOutcome.REFRESHED -> onRefreshed(ctx, response.optJSONObject("data"))
            else -> {
                markFailed()
                Log.w(TAG, "refresh rejected req.code=$reqCode outcome=$outcome")
                RefreshResult.FAILED
            }
        }
    }

    private fun onRefreshed(ctx: Context, data: JSONObject?): RefreshResult {
        // 与登录**共用同一个凭证读取器**：两个响应是同一个 `Login` 方法的同一个形状，
        // 各写一份字段映射只会得到两份迟早会分叉的契约。
        val newCookie = QqPhoneLogin.cookieFromCredential(data)
        if (newCookie == null) {
            markFailed()
            Log.w(TAG, "refresh 成功但凭证不成形，拒绝落盘")
            return RefreshResult.FAILED
        }
        val newCredential = QqRefreshCredential.fromLoginData(data)
        // 先落 cookie 再落凭证：万一第二步失败，下一次取链用的还是有效票据
        // （反过来的话，会出现「票据是旧的、凭证是新的」这种自相矛盾的状态）。
        QqAuthStore.saveCookie(ctx, newCookie)
        if (newCredential != null) QqRefreshStore.save(ctx, newCredential)
        lastFailedAtMs = 0L
        Log.i(
            TAG,
            "qm 登录态已静默续期 newCredential={" + (newCredential?.describe() ?: "null") + "}",
        )
        return RefreshResult.REFRESHED
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
     * 不一致时**删掉**本地凭证（它是上一个账号的残留，留着只有坏处），
     * 并按「不能续期」处理 —— 这是唯一安全的动作。
     */
    private fun isUsableFor(ctx: Context, stored: QqRefreshCredential): Boolean {
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
            Log.w(TAG, "续期凭证属于另一个账号，已丢弃（uin 与 musicid 不一致）")
            QqRefreshStore.clear(ctx)
            return false
        }
        return true
    }

    private fun isCoolingDown(): Boolean {
        val at = lastFailedAtMs
        return at > 0L && System.currentTimeMillis() - at < FAILED_COOLDOWN_MS
    }

    private fun markFailed() {
        lastFailedAtMs = System.currentTimeMillis()
    }
}
