/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 2026-10-27 审查 H15：首页旧快照可以被重新标成新账号。
 */

package com.takahashirinta.ncrust.cache

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H15 的**纯逻辑判据**：一份快照的归属能不能被提交 / 读取。
 *
 * ## 缺陷形状
 *
 * `HomeSnapshot.save` 捕获列表之后异步落盘，却在序列化**末尾**重新读一次
 * `CookieManager.accountOwner()` 写进 `KEY_OWNER`。换账号正好发生在这一次写盘的中途时，
 * A 的列表被贴上 B 的标签落盘；B 的冷启动 `restoreIntoCache`（按 owner 过滤）于是
 * 把 A 的推荐当成"自己的"。`CookieManager` 换号时清理只能清**已经存在**的值。
 *
 * 修法：归属在 `save(...)` **被调用的那一刻**捕获（默认参数在调用线程求值），
 * 落盘前再复核一次；两处判据都是 [HomeSnapshot.sameOwner]。
 *
 * ## 为什么这里只测判据
 *
 * 真正的 `save` / `restoreIntoCache` 要 `Context` 与 `SharedPreferences`，而本仓库的
 * JVM 单测没有 Robolectric（`unitTests.isReturnDefaultValues = true` 只抹平桩方法返回值），
 * 造不出 prefs。所以把"哪两份归属算同一个"抽成纯函数钉住；调用链的一致性由
 * `save`/`restore` 都走**这一个**函数来保证（源码里只有一处实现）。
 */
class HomeSnapshotOwnerTest {

    /** 未登录（null）与未登录是**同一个**归属：游客也有"自己的"首页。 */
    @Test
    fun `未登录与未登录是同一个归属`() {
        assertTrue(HomeSnapshot.sameOwner(null, null))
    }

    /** 真正的换号：A 的快照不允许在 B 会话里提交或恢复。 */
    @Test
    fun `不同账号不是同一个归属`() {
        assertFalse(HomeSnapshot.sameOwner("ownerA", "ownerB"))
    }

    /** 登录 / 登出都必须算换号（否则游客会读到上一个账号的个性化推荐）。 */
    @Test
    fun `登录态与未登录之间永远是换号`() {
        assertFalse(HomeSnapshot.sameOwner(null, "ownerA"))
        assertFalse(HomeSnapshot.sameOwner("ownerA", null))
    }

    @Test
    fun `同一账号（同一指纹）是同一个归属`() {
        assertTrue(HomeSnapshot.sameOwner("ownerA", "ownerA"))
    }
}
