/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.6.0 · P0 **回归单测**：QQ 歌曲「加入库」→ 刷新 → 重启 之后必须仍在。
 *
 * ## 为什么这条必须是 release-only 缺陷的回归用例
 *
 * 用户报告的路径是「入库 → 刷新 → 消失」，而根因不在 UI 也不在网络 ——
 * 它是**持久化结构缺一维**（`saved_songs` 里没有「这条是怎么进来的」）。
 * 那条路径跨了三个真实组件：`LibraryManager`（内存真源）、
 * `SavedSongCodec`（磁盘形状）、`SavedSongSync`（同步规则）。
 *
 * 真机 logcat 只能证明「这一次没丢」，证明不了「下一次混淆映射变化后也不丢」。
 * 所以这里把**三个组件的组合**在 JVM 上跑一遍：
 *
 * ```
 * 磁盘(JSON) ──decode──▶ 内存(List<SavedSongEntry>) ──sync/addManual──▶ 内存 ──encode──▶ 磁盘(JSON)
 *      ▲                                                                                  │
 *      └──────────────────────────── 「重启」 = 重新 decode ◀────────────────────────────┘
 * ```
 *
 * 每一步都是**真的编解码**（不是 mock），只有 Context 与网络被换成纯数据。
 */

package com.takahashirinta.ncrust.library

import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.network.model.AlbumItem
import com.takahashirinta.ncrust.network.model.ArtistItem
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.SourceIds
import com.takahashirinta.ncrust.source.TrackKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryImportPersistenceTest {

    private var clock = 1_700_000_000_000L
    private fun tick(): Long = ++clock

    private fun song(
        id: Long,
        name: String,
        source: MusicSource = MusicSource.NETEASE,
        mid: String? = null,
    ) = SongItem(
        id = id,
        name = name,
        artists = listOf(ArtistItem(1L, "某艺人")),
        album = AlbumItem(2L, "某专辑", "https://x/y.jpg"),
        duration = 200_000L,
        source = if (source == MusicSource.NETEASE) null else source.key,
        sourceId = mid,
    )

    /** 一台「设备」：一个 JSON 字符串就是它的 SharedPreferences。 */
    private class FakeDevice(json: String? = null) {
        var disk: String? = json

        /** 进程重启：丢掉内存、只从磁盘恢复。 */
        fun restart(): MutableList<SavedSongEntry> = SavedSongCodec.decode(disk).toMutableList()

        fun flushInMemory(memory: List<SavedSongEntry>) {
            disk = SavedSongCodec.encode(memory)
        }
    }

    private val qqRaw = SourceIds.qqId(1234567890L, "0039MnYb0qxYhV")
    private val qqSong = song(qqRaw, "晴天", MusicSource.QQMUSIC, "0039MnYb0qxYhV")
    private val neteaseSong = song(111L, "ncm 的一首")

    // ============================================================ 主用例 ----

    /**
     * ★ **本版的核心回归用例**（对应验收标准「QQ 歌曲入库后刷新、重启均不消失」）。
     *
     * 步骤与真机一一对应：
     * 1. 设备上已有一份云端同步来的收藏（模拟已登录、已刷新过）；
     * 2. 用户长按一首 QQ 歌 → 「加入库」（[SavedSongSync.addManual] + 落盘）；
     * 3. **刷新**（[SavedSongSync.merge] + 落盘）—— 云端 likedIds 里没有那首 QQ 歌；
     * 4. **重启**（重新 decode）→ 它必须还在。
     */
    @Test
    fun `QQ 歌曲加入库后 刷新与重启都不消失`() {
        val device = FakeDevice()
        // 1) 首次同步：云端有 2 首。
        var memory = SavedSongSync.merge(
            device.restart(),
            listOf(111L, 222L),
            mapOf(111L to neteaseSong, 222L to song(222L, "另一首")),
            tick(),
        )
        device.flushInMemory(memory)
        assertEquals(2, memory.size)

        // 2) 用户把一首 QQ 歌加入库。
        memory = SavedSongSync.addManual(device.restart(), qqSong, tick()).toMutableList()
        device.flushInMemory(memory)
        assertTrue("入库后应当立刻可见", memory.any { it.trackKey.id == qqRaw && it.isVisible })

        // 3) 下拉刷新 —— 这是**旧实现丢数据的那一步**。
        memory = SavedSongSync.merge(
            device.restart(),
            listOf(111L, 222L),
            mapOf(111L to neteaseSong, 222L to song(222L, "另一首")),
            tick(),
        ).toMutableList()
        device.flushInMemory(memory)
        assertTrue(
            "刷新后 QQ 曲目消失了 —— 这正是 v2.6.0 修的 bug",
            memory.any { it.trackKey.id == qqRaw && it.isVisible },
        )

        // 4) 重启（切 tab / 杀进程 / 冷启都走这条路）。
        val afterRestart = device.restart()
        val survivor = afterRestart.singleOrNull { it.trackKey.id == qqRaw }
        assertTrue("重启后 QQ 曲目应当仍在", survivor != null)
        assertEquals(SavedSongOrigin.LOCAL, survivor!!.origin)
        assertFalse(survivor.tombstoned)
        // 载荷（songmid）也必须活着回来，否则「在库里但播不了」。
        assertEquals("0039MnYb0qxYhV", survivor.song.sourceId)
        assertEquals(MusicSource.QQMUSIC, survivor.trackKey.source)
        // 反复刷新十次也不许丢（旧实现是「每次进库页丢一次」）。
        repeat(10) {
            memory = SavedSongSync.merge(
                device.restart(),
                listOf(111L, 222L),
                mapOf(111L to neteaseSong, 222L to song(222L, "另一首")),
                tick(),
            ).toMutableList()
            device.flushInMemory(memory)
        }
        assertTrue(
            "反复刷新后 QQ 曲目丢了",
            device.restart().any { it.trackKey.id == qqRaw && it.isVisible },
        )
    }

    @Test
    fun `ncm歌曲同样正常（入库 刷新 重启）`() {
        val device = FakeDevice()
        var memory = SavedSongSync.merge(device.restart(), listOf(111L), mapOf(111L to neteaseSong), tick())
        device.flushInMemory(memory)

        memory = SavedSongSync.addManual(device.restart(), neteaseSong, tick()).toMutableList()
        device.flushInMemory(memory)

        memory = SavedSongSync.merge(device.restart(), listOf(111L), mapOf(111L to neteaseSong), tick())
            .toMutableList()
        device.flushInMemory(memory)

        val back = device.restart()
        assertEquals(1, back.size)
        assertTrue(back.single().isVisible)
        assertEquals(111L, back.single().trackKey.id)
    }

    /**
     * **旧设备的迁移路径**：v2.5.6 及更早落盘的是裸 `SongItem` 数组，
     * 升级后第一次读到它们必须是 REMOTE（唯一有证据支撑的解释），
     * 而且**一条都不能少**。
     */
    @Test
    fun `从 v1 裸数组升级后 老收藏一条不少且不会在同步时被删`() {
        val v1 = """[{"al":{"id":2,"name":"某专辑","picUrl":"https://x/y.jpg"},"ar":[{"id":1,"name":"某艺人"}],"dt":200000,"id":111,"name":"ncm 的一首"}]"""
        val device = FakeDevice(v1)
        val memory = SavedSongSync.merge(device.restart(), listOf(111L, 999L), mapOf(111L to neteaseSong), tick())
        assertEquals(1, memory.size)
        assertEquals(SavedSongOrigin.REMOTE, memory.single().origin)
        // 升级后写回的是 v2 信封；再重启一次仍是同一条。
        device.flushInMemory(memory)
        assertEquals(111L, device.restart().single().trackKey.id)
    }

    /**
     * **同步不覆盖用户手动新增**（验收标准原文）。
     *
     * 用「云端完全是空的」这个极端形状来测：未登录 / 换账号 / 云端被清空时，
     * 手动加入的条目**一条都不许消失**。
     */
    @Test
    fun `云端为空时手动新增的条目全部保留`() {
        val device = FakeDevice()
        var memory = emptyList<SavedSongEntry>()
        val manualSongs = listOf(qqSong, neteaseSong, song(333L, "第三首"))
        manualSongs.forEach { memory = SavedSongSync.addManual(memory, it, tick()) }
        device.flushInMemory(memory)

        // 云端返回空列表（未登录 / 换账号 / 云端清空）。
        val after = SavedSongSync.merge(device.restart(), emptyList(), emptyMap(), tick())
        device.flushInMemory(after)

        assertEquals(3, after.size)
        assertEquals(
            manualSongs.map { TrackKey.ofSong(it) }.toSet(),
            device.restart().map { it.trackKey }.toSet(),
        )
        assertTrue(after.all { it.origin == SavedSongOrigin.LOCAL })
    }

    /**
     * **同步不覆盖本地新增 + tombstone 不被复活**，两者在同一个流程里各走一次。
     */
    @Test
    fun `删除过的歌不会被后续同步复活 而同一次同步里手动新增的条目保留`() {
        val device = FakeDevice()
        // 云端有两首 + 手动加一首 QQ。
        var memory = SavedSongSync.merge(
            device.restart(),
            listOf(111L, 222L),
            mapOf(111L to neteaseSong, 222L to song(222L, "另一首")),
            tick(),
        ).toMutableList()
        memory = SavedSongSync.addManual(memory, qqSong, tick()).toMutableList()
        device.flushInMemory(memory)

        // 用户删掉云端那一首（规则 6）。
        memory = SavedSongSync.remove(device.restart(), TrackKey(MusicSource.NETEASE, 222L)).toMutableList()
        device.flushInMemory(memory)

        // 刷新三次：云端仍然有 222 ⇒ 它必须仍然不可见（规则 4），QQ 那首仍在（规则 5）。
        repeat(3) {
            memory = SavedSongSync.merge(
                device.restart(),
                listOf(111L, 222L),
                mapOf(111L to neteaseSong, 222L to song(222L, "另一首")),
                tick(),
            ).toMutableList()
            device.flushInMemory(memory)
        }
        val visible = device.restart().filter { it.isVisible }
        assertEquals(
            "可见的应当只剩云端那首 111 与手动加的 QQ 那首",
            setOf(111L, qqRaw),
            visible.map { it.trackKey.id }.toSet(),
        )
        assertFalse(
            "用户删掉的歌被同步复活了",
            visible.any { it.trackKey.id == 222L },
        )
    }

    /**
     * **重启后重新添加已删除的歌**（规则 7 的跨重启版本）：
     * tombstone 落盘之后仍能被清除，且清除后不会再被打回。
     */
    @Test
    fun `删除后重启 再重新添加 仍然能恢复`() {
        val device = FakeDevice()
        var memory = SavedSongSync.merge(
            device.restart(),
            listOf(111L),
            mapOf(111L to neteaseSong),
            tick(),
        ).toMutableList()
        device.flushInMemory(memory)

        memory = SavedSongSync.remove(device.restart(), TrackKey(MusicSource.NETEASE, 111L)).toMutableList()
        device.flushInMemory(memory)
        assertTrue("删除后落盘应当留下 tombstone", device.restart().single().tombstoned)

        // 重启之后重新添加。
        memory = SavedSongSync.addManual(device.restart(), neteaseSong, tick()).toMutableList()
        device.flushInMemory(memory)
        val revived = device.restart().single()
        assertFalse(revived.tombstoned)
        assertTrue(revived.isVisible)

        // 再同步一次，不许被打回 tombstone。
        memory = SavedSongSync.merge(device.restart(), listOf(111L), mapOf(111L to neteaseSong), tick())
            .toMutableList()
        device.flushInMemory(memory)
        assertTrue(device.restart().single().isVisible)
    }

    /** 一首歌在库里**只出现一次**（身份 = `TrackKey`，两条路进来的同一首必须合并）。 */
    @Test
    fun `同一首歌从云端与手动两条路进来时只有一条记录`() {
        val device = FakeDevice()
        var memory = SavedSongSync.merge(
            device.restart(),
            listOf(111L),
            mapOf(111L to neteaseSong),
            tick(),
        ).toMutableList()
        memory = SavedSongSync.addManual(memory, neteaseSong, tick()).toMutableList()
        memory = SavedSongSync.appendRemote(memory, listOf(neteaseSong), tick()).toMutableList()
        device.flushInMemory(memory)
        assertEquals(1, device.restart().size)
    }

    /** 容量有界：tombstone 不会无限增长（铁律 5）。 */
    @Test
    fun `反复加删不会让落盘体积无界增长`() {
        var memory = mutableListOf<SavedSongEntry>()
        repeat(SavedSongSync.MAX_TOMBSTONES + 300) { i ->
            val s = song(10_000L + i, "s$i")
            memory = SavedSongSync.addManual(memory, s, tick()).toMutableList()
            memory = SavedSongSync.remove(memory, TrackKey.ofSong(s)).toMutableList()
        }
        val json = SavedSongCodec.encode(memory)
        // 每条 tombstone 的 JSON 大约 200 字节；上限 500 条 ⇒ 上界 ~150KB，
        // 无界生长的版本在这里会到 ~1600 条 / ~400KB。
        assertTrue("落盘体积 $${json.length} 字节 —— tombstone 没有被有界化", json.length < 250_000)
        assertEquals(SavedSongSync.MAX_TOMBSTONES, memory.size)
    }
}
