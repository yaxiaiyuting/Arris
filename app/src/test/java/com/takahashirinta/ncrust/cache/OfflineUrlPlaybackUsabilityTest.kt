/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.cache

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.takahashirinta.ncrust.testsupport.MemoryPrefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v4.1.5：离线 URL 的「能播 / 不能播」标记（[OfflineUrlIndex.setPlaybackUsable]）。
 *
 * 这组用例钉的是三条产品行为，任何一条塌了，用户看到的就是「点了播放没声音」：
 *
 *  1. **标记后挑选跳过** —— 一条已经证明播不出来的 URL 不许再被离线兜底喂回播放器；
 *  2. **恢复** —— 真的播出声了、或者重新解析出了新的 URL，标记必须解除，否则一次误判
 *     等于把这个档位的离线回放永久关掉；
 *  3. **换号 / 清空 / 重建** —— 标记是「本进程的观察结果」，不许跟着清单落盘，
 *     也不许在清单清空、删歌、LRU 淘汰之后继续挂在同名 key 上（那是冤案）。
 */
class OfflineUrlPlaybackUsabilityTest {

    private fun key(level: String) = OfflineKeys.key(42L, level)

    // ── 1. 标记后挑选跳过 ────────────────────────────────────────────────────────

    @Test
    fun `标记失败的档位被跳过——回落到同一首的另一档`() {
        val idx = OfflineUrlIndex()
        idx.put(key("lossless"), "https://cdn.invalid/lossless")
        idx.put(key("standard"), "https://cdn.invalid/standard")

        idx.setPlaybackUsable(key("lossless"), "https://cdn.invalid/lossless", false)

        val hit = idx.recallRecord(42L, "lossless") { true }
        assertEquals(key("standard"), hit?.first)
        assertEquals("https://cdn.invalid/standard", hit?.second?.url)
    }

    @Test
    fun `只有一条档位且被标记失败——返回 null，绝不把坏链再喂回播放器`() {
        val idx = OfflineUrlIndex()
        idx.put(key("lossless"), "https://cdn.invalid/lossless")
        idx.setPlaybackUsable(key("lossless"), "https://cdn.invalid/lossless", false)

        assertNull(idx.recallRecord(42L, "lossless") { true })
        // 别的歌不受牵连
        idx.put(OfflineKeys.key(43L, "lossless"), "https://cdn.invalid/other")
        assertEquals("https://cdn.invalid/other", idx.recallRecord(43L, "lossless") { true }?.second?.url)
    }

    @Test
    fun `标记只对那条 URL 生效——索引里已经换成新 URL 时旧结论不生效`() {
        val idx = OfflineUrlIndex()
        idx.put(key("standard"), "https://cdn.invalid/new")
        // 迟到的失败回调用的是上一条 URL：此时索引已经换了文件，旧结论没有意义。
        idx.setPlaybackUsable(key("standard"), "https://cdn.invalid/old", false)

        assertEquals("https://cdn.invalid/new", idx.recallRecord(42L, "standard") { true }?.second?.url)
    }

    // ── 2. 恢复 ────────────────────────────────────────────────────────────────

    @Test
    fun `成功标记解除跳过`() {
        val idx = OfflineUrlIndex()
        idx.put(key("standard"), "https://cdn.invalid/a")
        idx.setPlaybackUsable(key("standard"), "https://cdn.invalid/a", false)
        assertNull(idx.recallRecord(42L, "standard") { true })

        idx.setPlaybackUsable(key("standard"), "https://cdn.invalid/a", true)
        assertEquals(key("standard"), idx.recallRecord(42L, "standard") { true }?.first)
    }

    @Test
    fun `重新解析出新 URL 自动解除——URL 变了就是换了文件`() {
        val idx = OfflineUrlIndex()
        idx.put(key("standard"), "https://cdn.invalid/expired")
        idx.setPlaybackUsable(key("standard"), "https://cdn.invalid/expired", false)

        idx.put(key("standard"), "https://cdn.invalid/fresh")

        assertEquals("https://cdn.invalid/fresh", idx.recallRecord(42L, "standard") { true }?.second?.url)
    }

    @Test
    fun `同一条 URL 再写一次仍然保留标记——固定点闸门`() {
        val idx = OfflineUrlIndex()
        val bad = "https://cdn.invalid/same"
        idx.put(key("standard"), bad)
        idx.setPlaybackUsable(key("standard"), bad, false)

        // 重新取链仍然拿到同一条链（20 分钟内的签名没变）：不许借此洗白。
        repeat(3) { idx.put(key("standard"), bad) }

        assertNull(idx.recallRecord(42L, "standard") { true })
    }

    @Test
    fun `标记不进 JSON——本轮观察结果不落盘`() {
        val idx = OfflineUrlIndex()
        idx.put(key("standard"), "https://cdn.invalid/a")
        val before = idx.toJson()
        idx.setPlaybackUsable(key("standard"), "https://cdn.invalid/a", false)
        assertSame(before, idx.toJson())
    }

    // ── 3. 换号 / 清空 / 删除 / 重建的一致性 ─────────────────────────────────────

    @Test
    fun `换号清空标记——上一个账号的判决不作数`() {
        val idx = OfflineUrlIndex()
        idx.put(key("lossless"), "https://cdn.invalid/lossless")
        idx.setPlaybackUsable(key("lossless"), "https://cdn.invalid/lossless", false)
        assertNull(idx.recallRecord(42L, "lossless") { true })

        idx.clearFailures()

        assertEquals(key("lossless"), idx.recallRecord(42L, "lossless") { true }?.first)
    }

    @Test
    fun `清空清单把标记一起清掉——清缓存之后同名条目可播`() {
        val idx = OfflineUrlIndex()
        idx.put(key("standard"), "https://cdn.invalid/a")
        idx.setPlaybackUsable(key("standard"), "https://cdn.invalid/a", false)

        idx.clear()
        idx.put(key("standard"), "https://cdn.invalid/a")

        assertEquals(key("standard"), idx.recallRecord(42L, "standard") { true }?.first)
    }

    @Test
    fun `删歌把该歌的标记一起删掉——重写同名 key 不带旧判决`() {
        val idx = OfflineUrlIndex()
        idx.put(key("lossless"), "https://cdn.invalid/a")
        idx.put(key("hires"), "https://cdn.invalid/b")
        idx.setPlaybackUsable(key("lossless"), "https://cdn.invalid/a", false)

        assertEquals(2, idx.removeSong(42L))
        idx.put(key("lossless"), "https://cdn.invalid/a")

        assertEquals(key("lossless"), idx.recallRecord(42L, "lossless") { true }?.first)
    }

    @Test
    fun `LRU 淘汰的条目不会把判决留给后来者`() {
        val idx = OfflineUrlIndex(maxEntries = 1)
        idx.put(key("standard"), "https://cdn.invalid/a")
        idx.setPlaybackUsable(key("standard"), "https://cdn.invalid/a", false)

        // 挤掉上面那条，再用同名 key 重新写入一条全新的 URL。
        idx.put(OfflineKeys.key(43L, "standard"), "https://cdn.invalid/other")
        idx.put(key("standard"), "https://cdn.invalid/a")

        assertEquals("https://cdn.invalid/a", idx.recallRecord(42L, "standard") { true }?.second?.url)
    }

    @Test
    fun `清单重建（冷启读盘）不携带标记`() {
        val idx = OfflineUrlIndex()
        idx.put(key("standard"), "https://cdn.invalid/a")
        idx.setPlaybackUsable(key("standard"), "https://cdn.invalid/a", false)

        val rebuilt = OfflineUrlIndex.fromJson(idx.toJson())

        assertEquals(1, rebuilt.size())
        assertEquals(key("standard"), rebuilt.recallRecord(42L, "standard") { true }?.first)
    }
}

/**
 * v4.1.5：[OfflineUrlStore] 这一层的三条接线 —— 标记、换号清空、清单未加载时的空操作。
 *
 * 单例的 `index` 字段在用例之间是共享的，所以每个用例开始前把它摘掉、结束后放回原值
 * （与 `OfflineLibraryTest` 里那套反射替身同一个手法：项目没有 Robolectric，
 * 只用一个内存版 `SharedPreferences` 顶替真盘）。
 */
class OfflineUrlStorePlaybackUsabilityTest {

    private val indexField = OfflineUrlStore.javaClass.getDeclaredField("index").apply { isAccessible = true }
    private val appliedField = OfflineUrlStore.javaClass.getDeclaredField("appliedJson").apply { isAccessible = true }
    private var savedIndex: Any? = null
    private var savedApplied: Any? = null

    private val prefs = MemoryPrefs()
    private val context: Context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
    }

    private fun url(level: String = "lossless", host: String = "a") =
        OfflineKeys.withKey("https://cdn.invalid/$host.mp3", 42L, level)

    @Before
    fun detachSingletonIndex() {
        savedIndex = indexField.get(OfflineUrlStore)
        savedApplied = appliedField.get(OfflineUrlStore)
        indexField.set(OfflineUrlStore, null)
        appliedField.set(OfflineUrlStore, null)
    }

    @After
    fun restoreSingletonIndex() {
        indexField.set(OfflineUrlStore, savedIndex)
        appliedField.set(OfflineUrlStore, savedApplied)
    }

    @Test
    fun `service 标记失败的 URL——离线兜底跳过它，另一档照常命中`() {
        val bad = url("lossless", "bad")
        val good = url("standard", "good")
        OfflineUrlStore.rememberFromUrl(context, bad)
        OfflineUrlStore.rememberFromUrl(context, good)

        OfflineUrlStore.setPlaybackUsable(bad, false)

        val hit = OfflineUrlStore.recallRecord(context, 42L, "lossless") { true }
        assertEquals(OfflineKeys.keyOf(good), hit?.first)
    }

    @Test
    fun `换号清空失败标记——同一条 URL 重新可播`() {
        val bad = url()
        OfflineUrlStore.rememberFromUrl(context, bad)
        OfflineUrlStore.setPlaybackUsable(bad, false)
        assertNull(OfflineUrlStore.recallRecord(context, 42L, "lossless") { true })

        OfflineUrlStore.clearPlaybackFailures()

        assertNotNull(OfflineUrlStore.recallRecord(context, 42L, "lossless") { true })
    }

    @Test
    fun `清空缓存后重新记住的条目可播——标记不跨清空存活`() {
        val bad = url()
        OfflineUrlStore.rememberFromUrl(context, bad)
        OfflineUrlStore.setPlaybackUsable(bad, false)

        OfflineUrlStore.clear(context)
        OfflineUrlStore.rememberFromUrl(context, bad)

        assertEquals(
            OfflineKeys.keyOf(bad),
            OfflineUrlStore.recallRecord(context, 42L, "lossless") { true }?.first,
        )
    }

    @Test
    fun `清单还没加载进内存时标记是空操作——不会误伤盘上已有的条目`() {
        val stored = url()
        // 先落一条到盘上，再让进程「重启」：单例里没有内存副本。
        OfflineUrlStore.rememberFromUrl(context, stored)
        indexField.set(OfflineUrlStore, null)
        appliedField.set(OfflineUrlStore, null)

        // 此刻打标记：清单没加载，什么都不该发生（也绝不能因此去读盘/写盘）。
        OfflineUrlStore.setPlaybackUsable(stored, false)

        // 之后正常的读路径把清单加载回来，条目仍然是可播的。
        assertEquals(
            OfflineKeys.keyOf(stored),
            OfflineUrlStore.recallRecord(context, 42L, "lossless") { true }?.first,
        )
        assertTrue(prefs.contains("urls"))
        // 兜底闸门仍然独立生效：缓存里没有开头字节时照样不命中。
        val noBytes = OfflineUrlStore.recallRecord(context, 42L, "lossless") { false }
        assertNull(noBytes)
    }
}
