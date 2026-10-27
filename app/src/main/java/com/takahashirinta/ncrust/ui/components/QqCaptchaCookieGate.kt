/*
 * Arris —— 本 Fork（https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * E04：QQ 图形验证码「验证是否已经写进 cookie」的判定（纯逻辑，JVM 可单测）。
 */

package com.takahashirinta.ncrust.ui.components

import com.takahashirinta.ncrust.qq.QqCookie

/**
 * E04：验证页完成检测的**纯逻辑**部分。
 *
 * ## 旧实现哪里错了
 *
 * 基线存的是 `Set<String>`（只有键名），完成条件只看「出现了新键」：
 *
 * ```kotlin
 * if (now.keys.any { it !in base }) { ...onVerified... }
 * ```
 *
 * 但腾讯的验证结果**经常是给已有 cookie 换值**（`uin`/`p_skey`/`skey`/`pt4_token`
 * 这些键在进验证页时就已经存在，验证只是把它们改成新的）。于是：
 * 自动检测永远不结束 → 用户在验证页做完滑块，界面毫无反应，只能点底部的
 * 「验证完成/重试」兜底（`QqPhoneLoginDialog` 那条路要靠 `onVerified` 才会重发短信）。
 *
 * 修法：基线改成**键 → 值**，判定「任一新键 **或** 任一已存在的键换了值」。
 * 手动按钮保留（验证结果也可能只回在 URL 上，那时 cookie 确实不变）。
 */
internal object QqCaptchaCookieGate {

    /** 把 WebView cookie 串解析成「键 → 值」基线。 */
    fun snapshot(raw: String?): Map<String, String> = QqCookie.parse(raw)

    /**
     * 相对 [baseline]，cookie 是否被写过东西。
     *
     * - 出现基线上没有的键 ⇒ true（新会话键）；
     * - 已存在的键值发生变化 ⇒ true（**E04 的主体**：验证只更新旧键的值）；
     * - 空 cookie（`now` 为空）⇒ false：什么都读不到时不能当成"验证完成"，
     *   否则会把一份空 cookie 拿去重发短信。
     */
    fun changedSince(baseline: Map<String, String>, now: Map<String, String>): Boolean {
        if (now.isEmpty()) return false
        for ((key, value) in now) {
            val old = baseline[key]
            if (old == null) return true
            if (old != value) return true
        }
        return false
    }
}
