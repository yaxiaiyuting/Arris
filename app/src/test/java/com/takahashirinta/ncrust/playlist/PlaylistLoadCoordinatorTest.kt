/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.2.0：账号/歌单切换竞态的单测。
 */

package com.takahashirinta.ncrust.playlist

import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.PlaylistKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PlaylistLoadCoordinator] 的单测（v2.2.0）。
 *
 * 覆盖任务书点名的竞态场景：**「旧响应后到不覆盖」**。
 * 这不是理论风险：v2.1.5 的跨源歌词串台就是同一类 bug，而歌单串台的后果更重
 * （会把 A 账号的歌单当成 B 账号的显示出来）。
 */
class PlaylistLoadCoordinatorTest {

    private val ownerA = "10001"
    private val ownerB = "20002"

    private fun key(id: String, owner: String) =
        PlaylistKey(MusicSource.QQMUSIC, id, owner)

    @Test
    fun `初始状态是 Idle 且世代为 0`() {
        val c = PlaylistLoadCoordinator()
        assertEquals(0L, c.generation)
        assertEquals(PlaylistLoadCoordinator.State.Idle, c.state)
        assertNull(c.currentKey)
        assertNull(c.currentOwnerId)
    }

    @Test
    fun `世代严格单调递增`() {
        val c = PlaylistLoadCoordinator()
        val g0 = c.generation
        c.beginList(ownerA)
        val g1 = c.generation
        c.beginDetail(key("1", ownerA))
        val g2 = c.generation
        c.invalidate()
        val g3 = c.generation
        assertTrue(g1 > g0)
        assertTrue(g2 > g1)
        assertTrue(g3 > g2)
    }

    /**
     * 任务书核心用例：**切歌单后，旧歌单的响应回来不得落地**。
     */
    @Test
    fun `切歌单后旧响应必须被丢弃`() {
        val c = PlaylistLoadCoordinator()
        val stale = c.beginDetail(key("111", ownerA))
        // 用户点了另一个歌单
        val fresh = c.beginDetail(key("222", ownerA))

        assertFalse("旧请求不得再被认为有效", c.isCurrent(stale))
        assertFalse("旧响应不得写进状态", c.accept(stale, trackCount = 99))
        assertTrue("新请求有效", c.isCurrent(fresh))
        assertTrue(c.accept(fresh, trackCount = 3))
        assertEquals(PlaylistLoadCoordinator.State.Loaded(fresh.key, 3), c.state)
    }

    /**
     * 任务书核心用例：**切账号后，旧账号的响应回来不得落地**。
     */
    @Test
    fun `切账号后旧响应必须被丢弃`() {
        val c = PlaylistLoadCoordinator()
        val stale = c.beginDetail(key("111", ownerA))
        c.onOwnerChanged(ownerB)

        assertFalse(c.isCurrent(stale))
        assertFalse(c.accept(stale, trackCount = 7))
        assertNull("换账号后不得残留上一个账号的歌单", c.currentKey)
        assertEquals(ownerB, c.currentOwnerId)
        assertEquals(PlaylistLoadCoordinator.State.Idle, c.state)
    }

    /**
     * **同一个歌单 id、不同账号**必须判不等 —— 这是 [PlaylistKey] 带 ownerId 的全部意义。
     * qm 的 dirId 是账号内编号，`dirId=1` 在两个账号下是两个不同的歌单。
     */
    @Test
    fun `同一个 playlistId 在不同账号下是两个不同的主体`() {
        val c = PlaylistLoadCoordinator()
        val stale = c.beginDetail(key("1", ownerA))
        val fresh = c.beginDetail(key("1", ownerB))
        assertFalse("id 相同但账号不同 ⇒ 不是同一个请求", c.isCurrent(stale))
        assertTrue(c.isCurrent(fresh))
    }

    /**
     * **列表页**的竞态：列表请求的 `key` 是 null，两个账号的列表请求 key 都是 null，
     * 若只比 key 会互相覆盖 —— ownerId 那一条判据正是为此存在。
     */
    @Test
    fun `账号切换时列表页的旧响应也必须被丢弃`() {
        val c = PlaylistLoadCoordinator()
        val staleList = c.beginList(ownerA)
        val freshList = c.beginList(ownerB)
        assertFalse("null key 相同，但账号不同 ⇒ 必须丢弃", c.isCurrent(staleList))
        assertTrue(c.isCurrent(freshList))
    }

    /**
     * **重新登录同一个账号**也要让旧票据时代的请求作废：旧请求可能带着已失效的 cookie，
     * 它回来的 `NEED_LOGIN` 会覆盖新登录的成功结果，用户看到的是「刚登录就说登录过期」。
     */
    @Test
    fun `同一个账号重新登录也要作废在途请求`() {
        val c = PlaylistLoadCoordinator()
        val stale = c.beginList(ownerA)
        c.onOwnerChanged(ownerA) // 同一账号，重新登录
        assertFalse(c.isCurrent(stale))
    }

    @Test
    fun `重试同一个歌单会让更早的在途请求失效`() {
        val c = PlaylistLoadCoordinator()
        val first = c.beginDetail(key("9", ownerA))
        val second = c.beginDetail(key("9", ownerA))
        assertFalse(c.isCurrent(first))
        assertTrue(c.isCurrent(second))
    }

    @Test
    fun `未登录时用显式常量而不是空串`() {
        val c = PlaylistLoadCoordinator()
        val load = c.beginList(null)
        assertEquals(PlaylistKey.OWNER_ANONYMOUS, load.ownerId)
        assertTrue(PlaylistKey.isAnonymous(load.ownerId))
        assertTrue("未登录也是一个明确主体，可以判等", c.isCurrent(load))
    }

    @Test
    fun `主体未确定时任何响应都不算数`() {
        val c = PlaylistLoadCoordinator()
        val load = c.beginList(ownerA)
        c.invalidate() // 退出页面
        assertNull(c.currentOwnerId)
        assertFalse(c.isCurrent(load))
    }

    @Test
    fun `失败也要判世代——切歌单后的超时不得把新歌单打成错误态`() {
        val c = PlaylistLoadCoordinator()
        val stale = c.beginDetail(key("1", ownerA))
        val fresh = c.beginDetail(key("2", ownerA))
        assertFalse("旧请求的失败不得改写状态", c.fail(stale, PlaylistLoadCoordinator.Reason.NETWORK))
        assertEquals(PlaylistLoadCoordinator.State.Loading(fresh.key), c.state)
        assertTrue(c.fail(fresh, PlaylistLoadCoordinator.Reason.NOT_FOUND))
        assertEquals(
            PlaylistLoadCoordinator.State.Error(fresh.key, PlaylistLoadCoordinator.Reason.NOT_FOUND),
            c.state,
        )
    }

    @Test
    fun `渲染闸门只接受当前主体的已就绪数据`() {
        val c = PlaylistLoadCoordinator()
        val load = c.beginDetail(key("5", ownerA))
        // Loading 期间：即便 key 相同也不该渲染（还没有数据）
        assertFalse(c.shouldRender(load.key))
        c.accept(load, 2)
        assertTrue(c.shouldRender(load.key))
        // 另一个歌单的数据不得被渲染
        assertFalse(c.shouldRender(key("6", ownerA)))
        // 另一个账号的同 id 数据也不得被渲染
        assertFalse(c.shouldRender(key("5", ownerB)))
        assertFalse("列表页（null key）在详情就绪时不得渲染", c.shouldRender(null))
    }

    @Test
    fun `成功之后世代不再变化——判等不会误伤已就绪的数据`() {
        val c = PlaylistLoadCoordinator()
        val load = c.beginDetail(key("3", ownerA))
        c.accept(load, 1)
        assertTrue("读取状态不应改变世代", c.isCurrent(load))
    }
}
