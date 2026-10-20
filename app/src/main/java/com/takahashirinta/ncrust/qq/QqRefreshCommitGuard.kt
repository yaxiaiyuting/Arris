/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.4.13：续期响应落盘前的**纯逻辑竞态闸门**（无 IO、无 Android 依赖、JVM 可单测）。
 */

package com.takahashirinta.ncrust.qq

/**
 * 一次续期请求从「发出」到「响应回来」之间，本地登录态可能已经变了：
 *
 * - 用户登出；
 * - 用户在同一账号上重新登录（服务端重新签票）；
 * - 用户换了一个账号登录；
 * - 另一个续期先完成并写了更新的票。
 *
 * 旧实现拿到响应就直接 `saveCookie` + `QqRefreshStore.save`，于是**迟到的旧响应**
 * 会把新登录态覆盖回旧账号/旧票，而且全程不报错。表现是「刚登录完，过一会儿又掉了」
 * 或「登录着 B、数据全是 A」。
 *
 * 这个对象只回答一个纯问题：**这份响应还能不能落盘**。判断依据是「请求开始时那份凭证」
 * 与「现在盘上的登录态」的**同一性**，不是时间窗口，也不是概率：
 *
 * 1. 还登录着吗？（登出/清空 ⇒ 不能写）
 * 2. 本地登录态版本变过吗？（任何 cookie/凭证写入都会 bump；变了 ⇒ 不能写）
 * 3. 盘上的续期凭证还是请求开始时那**一份**吗？（同账号重登也会换票 ⇒ 不能写）
 * 4. 盘上的 uin 还是这份凭证的账号吗？（换号 ⇒ 不能写）
 *
 * 只有四条全部通过才允许提交。宁可丢掉一次刷新，也不能覆盖用户更新的登录态 ——
 * 丢掉的代价是下次再刷一次，覆盖的代价是用户重新登录。
 */
internal object QqRefreshCommitGuard {

    /** 提交闸门的结论。 */
    enum class Decision {
        /** 登录态没有变化，可以写回。 */
        COMMIT,

        /** 请求期间已经登出（cookie 或票据缺失）。 */
        NOT_LOGGED_IN,

        /** 登录态版本变了：有别的登录/登出/续期完成了。 */
        SUPERSEDED,

        /** 盘上的续期凭证已经不是请求开始的那一份（同账号重登/换号等）。 */
        CREDENTIAL_CHANGED,

        /** 盘上的 uin 与请求凭证的 musicid 不一致（换号）。 */
        ACCOUNT_CHANGED,
    }

    /**
     * @param started 发起请求时读到的凭证。
     * @param current 现在盘上的凭证（`null` = 已被清掉）。
     * @param currentUin 现在 cookie 里的数字 uin。
     * @param loggedIn 现在 cookie 是否仍是完整登录态。
     * @param startedRevision 发起请求时 `QqAuthStore` 的登录态版本。
     * @param currentRevision 现在 `QqAuthStore` 的登录态版本。
     */
    fun decide(
        started: QqRefreshCredential,
        current: QqRefreshCredential?,
        currentUin: Long?,
        loggedIn: Boolean,
        startedRevision: Long,
        currentRevision: Long,
    ): Decision {
        // ① 最早判登出：已登出时后面的「凭证相等」可能因为都为空而误判成可写。
        if (!loggedIn) return Decision.NOT_LOGGED_IN
        // ② 任何 cookie / 凭证写入都会推进版本。版本变了就意味着「这次响应不再是唯一的更新」。
        if (startedRevision != currentRevision) return Decision.SUPERSEDED
        // ③ 盘上还是同一份凭证（不是同一账号的新票，而是同一张旧票）。
        if (current != started) return Decision.CREDENTIAL_CHANGED
        // ④ 账号身份必须对得上。musicid 未知（老凭证）时无法比较，放行。
        if (started.musicId > 0L && currentUin != started.musicId) {
            return Decision.ACCOUNT_CHANGED
        }
        return Decision.COMMIT
    }
}
