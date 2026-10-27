/*
 * Arris —— 本 Fork（https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * J02：把「服务端验证通过」与「本地已接受这份凭据」分开（纯逻辑，JVM 可单测）。
 */

package com.takahashirinta.ncrust.ui.components

import com.takahashirinta.ncrust.auth.NeteaseCookie

/**
 * J02：扫码登录的两个结果**不是**同一个结果。
 *
 * ## 缺陷形状
 *
 * 三条链路各自回答一个问题：
 *
 * 1. `PlaylistApi.validateSession(cookie)` —— **服务端**认不认这份 cookie（`account/get` 200）；
 * 2. `CookieManager.saveCookieMerged(context, cookie)` —— **本地**接不接受它；
 * 3. 界面 —— 上面两件事都被跳过了：`MainActivity` 忽略 `saveCookieMerged` 的返回值，
 *    直接 `SessionMonitor.onLoginSucceeded` + 关闭弹窗。
 *
 * 而 (2) 有真实的拒绝分支：`NeteaseCookie.shouldReplace` 不许「半截会话」（只有 `MUSIC_U`、
 * 缺 `__csrf`）覆盖一份**完整**的旧会话 —— 扫码 803 的 `Set-Cookie` 常常就是这种增量形状。
 * 结果是：手机端确认了、服务端也验过了、界面说登录成功，本地留下的却还是**上一个账号**的凭据。
 *
 * ## 三种结果
 *
 * - **接受**（`saved == true`）：写进去了，收尾成功；
 * - **同会话无变化**（没写盘，但本地凭据已经覆盖候选的每一个键值）：重复轮询 / 同号重扫，
 *   功能上等价于接受，也必须收尾成功 —— 否则用户会卡在一个"看起来没反应"的弹窗里；
 * - **拒绝**（没写盘，且本地凭据不覆盖候选）：候选不完整，**不能**收尾成功，
 *   也不能用旧账号的 CSRF 去拼；应保留弹窗让用户重试或改用网页登录。
 */
internal object LoginCredentialAcceptance {

    /**
     * @param savedByStore 写入接口的返回值：`saveCookieMerged` 有 Boolean；
     *   `saveCookie` 只返回 Unit，传 null 表示"未知"。
     * @param stored 写入之后**从本地读回**的凭据（`CookieManager.getCookie`）。
     * @param candidate 这次要落地的候选凭据。
     */
    fun isApplied(savedByStore: Boolean?, stored: String?, candidate: String?): Boolean {
        val wanted = NeteaseCookie.parse(candidate)
        if (wanted.isEmpty()) return false
        if (savedByStore == true) return true
        // 没写盘（或不知道写没写）：必须能从本地读回候选的每一个键值，才算"已经生效"。
        return coversAll(stored, wanted)
    }

    /** [stored] 是否覆盖 [wanted] 的每一个键值（同名同值）。 */
    private fun coversAll(stored: String?, wanted: Map<String, String>): Boolean {
        val have = NeteaseCookie.parse(stored)
        if (have.isEmpty()) return false
        return wanted.all { (key, value) -> have[key] == value }
    }
}
