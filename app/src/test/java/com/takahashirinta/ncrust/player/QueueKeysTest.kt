/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.5.3 · P1：队列身份（`QueueKeys` / `TrackKey`）单测。
 */

package com.takahashirinta.ncrust.player

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.takahashirinta.ncrust.QueueModes
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.SourceIds
import com.takahashirinta.ncrust.source.TrackKey
import com.takahashirinta.ncrust.source.dedupeKey
import com.takahashirinta.ncrust.source.musicSource
import com.takahashirinta.ncrust.source.trackKeyOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.5.3 · P1：**把队列身份从裸 `song.id` 换成 `TrackKey`** 的验收单测。
 *
 * ## 探针结论先行（本版不修线上 bug，这点必须说清楚）
 *
 * `docs/verification/v2.5.3/probe-queue-dedup.md` 实测：
 *  · 全部 QQ 曲目的 id 都由 [SourceIds.qqId] 产出，bit62 恒置位，
 *    与 ncm 的 id 区间**结构性不相交**；
 *  · 抽样 1000 首双源配对，裸 id 跨源冲突 **0** 次；
 *  · 队列快照落盘的是整个 `SongItem`（含 `source`/`mid`/`media_id`），
 *    **没有需要迁移的旧 key 形状**。
 *
 * 所以本版**修的不是一个线上 bug**。它买到的是：那个 0 从「依赖每个 id 生产者
 * 都记得走 `qqId`」这条**纪律**，变成由**类型**承载的判据；
 * 并且队列判重、待播槽位、歌词闸门、续播恢复从此只有**一套**身份规则。
 *
 * ## 覆盖范围（对应任务书 §4.3）
 *
 * | 要求 | 用例 |
 * |---|---|
 * | 同源同 id 去重 | `同源同 id - 去重且幂等` |
 * | 跨源同 id **不**去重 | `跨源同 id - 绝不当成同一首（本版的核心验收项）` |
 * | 幂等性保持 | `去重是幂等的（跑 N 次结果一致）` |
 * | 历史数据迁移 | `持久化队列的形状 - 老 JSON 直接可用 无需迁移` 等 4 条 |
 * | 待播槽位行为不变 | `待播槽位 - 身份判定与改造前逐值一致` |
 * | 随机模式行为不变 | `随机模式 - 跨源插入后的排列仍然合法` |
 * | 规则长期防线 | `QueueInsert 的签名不许再退回裸 Long`（反射断言） |
 */
class QueueKeysTest {

    // ---------------------------------------------------------------- 夹具

    private fun netease(id: Long, name: String = "NE$id") = SongItem(
        id = id, name = name, artists = null, album = null, duration = null,
    )

    private fun qq(rawId: Long, mid: String = "0039MnYb0qxYhV", name: String = "QQ$rawId") = SongItem(
        id = SourceIds.qqId(rawId, mid), name = name, artists = null, album = null, duration = null,
        source = MusicSource.QQMUSIC.key, sourceId = mid, mediaId = mid + "_m",
    )

    // ---------------------------------------------------------------- 身份

    @Test
    fun `身份含音源 - ncm的 123 与 QQ 的 123 是两首歌`() {
        // TrackKey 的相等性只看 (source, id) —— 这是全部队列判重的地基。
        assertNotEquals(TrackKey(MusicSource.NETEASE, 123L), TrackKey(MusicSource.QQMUSIC, 123L))
        assertNotEquals(
            QueueKeys.keyOf(netease(123)).hashCode(),
            QueueKeys.keyOf(qqWithPlainId(123)).hashCode(),
        )
    }

    /** 造一个「source 标了 QQ、id 却是裸 id」的条目 —— 只用于验证身份含 source。 */
    private fun qqWithPlainId(id: Long) = SongItem(
        id = id, name = "qq-plain", artists = null, album = null, duration = null,
        source = MusicSource.QQMUSIC.key, sourceId = "mid",
    )

    @Test
    fun `身份落点是 TrackKey_ofSong - source 字符串丢了也能靠 bit62 认出 QQ`() {
        // 这是 `fromSong` 与 `ofSong` 的唯一区别，也是本版选后者的理由：
        // 搜索结果进历史记录那条路只存得下 id，source 字符串会丢。
        val synthId = SourceIds.qqId(1234567L, "0039MnYb0qxYhV")
        val sourceLost = SongItem(
            id = synthId, name = "丢了 source 的 QQ 曲目",
            artists = null, album = null, duration = null, source = null,
        )
        assertEquals(MusicSource.NETEASE, sourceLost.musicSource)      // ← 字符串口径：认成 ncm
        assertEquals(MusicSource.QQMUSIC, sourceLost.trackKeyOf().source) // ← 结构性口径：认出 QQ
        assertEquals(TrackKey.fromSong(sourceLost).source, MusicSource.NETEASE) // 记录旧口径的差异
    }

    @Test
    fun `dedupeKey 与 TrackKey 是同一个值（不再各算各的）`() {
        listOf(netease(1), qq(2), qqWithPlainId(3)).forEach { s ->
            assertEquals(s.trackKeyOf().tag, s.dedupeKey)
        }
    }

    // ---------------------------------------------------------------- 同源去重

    @Test
    fun `同源同 id - 去重且幂等`() {
        val queue = QueueKeys.keysOf(listOf(netease(1), netease(2), netease(3)))
        val once = QueueKeys.dedupe(queue, QueueKeys.keyOf(netease(2))) + QueueKeys.keyOf(netease(2))
        val twice = QueueKeys.dedupe(once, QueueKeys.keyOf(netease(2))) + QueueKeys.keyOf(netease(2))
        assertEquals(listOf(1L, 3L, 2L), once.map { it.id })
        assertEquals(once, twice)
    }

    @Test
    fun `去重是幂等的（跑 N 次结果一致）`() {
        val queue = QueueKeys.keysOf(listOf(netease(1), netease(2), netease(3)))
        val target = QueueKeys.keyOf(netease(9))
        // ★ 用 fold 而不是 runningFold：runningFold 会把**初始值**也放进结果，
        //   而初始值本来就与「应用一次之后」不同 —— 那不是幂等性问题。
        var acc = queue
        val snaps = mutableListOf<List<TrackKey>>()
        repeat(5) {
            acc = QueueKeys.dedupe(acc, target) + target
            snaps += acc
        }
        assertEquals("第 1 次之后的所有快照必须完全相同", 1, snaps.toSet().size)
        assertEquals(listOf(1L, 2L, 3L, 9L), snaps.first().map { it.id })
    }

    @Test
    fun `去重拿掉的是全部同身份项 不是第一个`() {
        // 队列里本不该有重复（事实不变量），但真出现时必须一次清干净 ——
        // 只删第一份会留下 [当前, 下一首, 下一首'] 的形状，那正是 v1.5.2 串台的根因。
        val queue = listOf(netease(1), netease(2), netease(1), netease(3))
        val after = QueueKeys.dedupe(QueueKeys.keysOf(queue), QueueKeys.keyOf(netease(1)))
        assertEquals(listOf(2L, 3L), after.map { it.id })
        assertFalse(QueueKeys.hasDuplicates(after))
    }

    // ---------------------------------------------------------------- 跨源不误判

    /**
     * **本版的核心验收项**：跨源同数值 id 绝不能被当成同一首。
     *
     * 这一条在今天的 id 空间下（QQ 带 bit62）不会真的取到「数值相等」的一对，
     * 所以用例用手工构造的 `TrackKey` 直接钉住**判据本身** ——
     * 否则「因为不可能发生所以测不出来」会让这条规则失去守卫。
     */
    @Test
    fun `跨源同 id - 绝不当成同一首（本版的核心验收项）`() {
        val ne = TrackKey(MusicSource.NETEASE, 123L)
        val qqKey = TrackKey(MusicSource.QQMUSIC, 123L)
        assertNotEquals(ne, qqKey)

        // 队列里同时有这两首 ⇒ 加第三首（ncm 123）只应去重 ncm 那一份。
        val queue = listOf(ne, qqKey)
        val after = QueueKeys.dedupe(queue, ne) + ne
        assertEquals(listOf(qqKey, ne), after)
        assertTrue("跨源的那一首必须还在", after.contains(qqKey))

        // 反向同理。
        val after2 = QueueKeys.dedupe(queue, qqKey) + qqKey
        assertEquals(listOf(ne, qqKey), after2)
    }

    @Test
    fun `missing 不把跨源同号当成已有`() {
        val existing = listOf(TrackKey(MusicSource.NETEASE, 123L))
        val candidates = listOf(TrackKey(MusicSource.QQMUSIC, 123L), TrackKey(MusicSource.NETEASE, 123L))
        assertEquals(listOf(TrackKey(MusicSource.QQMUSIC, 123L)), QueueKeys.missing(existing, candidates))
    }

    @Test
    fun `真实的双源队列可以同时装下两首同号歌`() {
        // 端到端形状：ncm 123 与 QQ 的 123 同时进队列，两首都在。
        val ne = netease(123)
        val qqPlain = qqWithPlainId(123)
        val songs = listOf(ne, qqPlain)
        val keys = QueueKeys.keysOf(songs)
        assertEquals(2, keys.size)
        assertNotEquals(keys[0], keys[1])
        assertFalse(QueueKeys.hasDuplicates(keys))
        assertEquals(songs, QueueKeys.rebuild(songs, keys))
    }

    // ---------------------------------------------------------------- 重定位

    @Test
    fun `去重后重定位当前歌 - 绝不沿用旧下标`() {
        // 当前歌之前有一份重复项：直接 filter 后沿用旧下标会把指针指到别人身上。
        val queue = listOf(netease(9), netease(1), netease(2), netease(3))
        val current = QueueKeys.keyOf(netease(1))
        val keys = QueueKeys.keysOf(queue)
        assertEquals(1, QueueKeys.indexOfCurrent(keys, current))

        val after = QueueKeys.dedupe(keys, QueueKeys.keyOf(netease(9)))
        assertEquals("删掉当前歌之前的项 ⇒ 下标必须左移", 0, QueueKeys.indexOfCurrent(after, current))
        assertEquals(1L, after[QueueKeys.indexOfCurrent(after, current)].id)
    }

    @Test
    fun `没有当前歌时返回 -1 - 不回落 0`() {
        // 回落 0 会让「队列非空但没有当前项」被悄悄当成「当前是第一首」。
        assertEquals(-1, QueueKeys.indexOfCurrent(QueueKeys.keysOf(listOf(netease(1))), null))
    }

    @Test
    fun `excludeCurrent 挡住把当前歌塞到下一首`() {
        val current = QueueKeys.keyOf(netease(2))
        val batch = QueueKeys.keysOf(listOf(netease(1), netease(2), netease(3)))
        assertEquals(listOf(1L, 3L), QueueKeys.excludeCurrent(batch, current).map { it.id })
        assertEquals(batch, QueueKeys.excludeCurrent(batch, null))
    }

    // ---------------------------------------------------------------- 重建

    @Test
    fun `rebuild 按身份重排 - 装配不出来时返回 null 而不是交出缺项的队列`() {
        val songs = listOf(netease(1), netease(2), netease(3))
        val reordered = QueueKeys.keysOf(listOf(netease(3), netease(1), netease(2)))
        assertEquals(
            listOf(3L, 1L, 2L),
            QueueKeys.rebuild(songs, reordered)!!.map { it.id },
        )

        // 队列里没有的身份、又不在 extra 里 ⇒ null（调用方必须放弃这次变更）。
        val stranger = TrackKey(MusicSource.NETEASE, 999L)
        assertNull(QueueKeys.rebuild(songs, reordered + stranger))

        // 放进 extra 就能装出来（这是插入路径的形状）。
        val inserted = QueueKeys.rebuild(songs, reordered + stranger, extra = listOf(netease(999)))
        assertEquals(listOf(3L, 1L, 2L, 999L), inserted!!.map { it.id })
    }

    @Test
    fun `duplicates 能报出重复的身份`() {
        val keys = QueueKeys.keysOf(listOf(netease(1), netease(2), netease(1)))
        assertTrue(QueueKeys.hasDuplicates(keys))
        assertEquals(setOf(TrackKey(MusicSource.NETEASE, 1L)), QueueKeys.duplicates(keys))
        assertFalse(QueueKeys.hasDuplicates(QueueKeys.keysOf(listOf(netease(1), netease(2)))))
    }

    @Test
    fun `describe 带音源 - 裸 id 的日志看不出 123 是谁`() {
        val text = QueueKeys.describe(QueueKeys.keysOf(listOf(netease(123), qqWithPlainId(123))))
        assertEquals("netease:123, qqmusic:123", text)
    }

    // ---------------------------------------------------------------- 历史数据

    /**
     * **历史数据迁移**：结论是 **不需要迁移**，并在这里把这件事钉住。
     *
     * `ncrust_playback_state` 的 `queue` 落盘的是 `List<SongItem>`（Gson 全量对象），
     * 不是 id 列表 —— 所以 v2.1.0 · A 起写入的每一条**本来就带** `source`/`mid`/`media_id`，
     * `TrackKey` 可以直接从既有字段算出来，没有任何「旧 key 形状」需要翻译。
     *
     * 而 v2.1.0 **之前**写入的条目没有 `source` 字段，Gson 走 Unsafe 反序列化
     * 不调用构造函数 ⇒ 读到 `null` ⇒ `MusicSource.fromKey(null)` 回落 ncm。
     * 那正是老数据的正确解释（那时候只有 ncm），本版**保持**这个语义。
     */
    @Test
    fun `持久化队列的形状 - 老 JSON 直接可用 无需迁移`() {
        val gson = Gson()
        val listType = object : TypeToken<List<SongItem>>() {}.type

        // ① v2.1.0 之前：没有 source / mid / media_id 三个 key。
        val legacyJson = """
            [{"id":5257138,"name":"屋顶","dt":245000},
             {"id":287035,"name":"遇见","dt":260000}]
        """.trimIndent()
        val legacy: List<SongItem> = gson.fromJson(legacyJson, listType)
        assertEquals(2, legacy.size)
        assertNull("老条目没有 source 字段", legacy[0].source)
        assertEquals(MusicSource.NETEASE, legacy[0].musicSource)
        assertEquals(
            listOf(TrackKey(MusicSource.NETEASE, 5257138L), TrackKey(MusicSource.NETEASE, 287035L)),
            QueueKeys.keysOf(legacy),
        )

        // ② v2.1.0 起：带 source/mid/media_id。
        val mid = "0039MnYb0qxYhV"
        val synth = SourceIds.qqId(97773L, mid)
        val currentJson = """
            [{"id":$synth,"name":"晴天","source":"qqmusic","mid":"$mid","media_id":"${mid}_m"}]
        """.trimIndent()
        val current: List<SongItem> = gson.fromJson(currentJson, listType)
        assertEquals(MusicSource.QQMUSIC, current[0].musicSource)
        assertEquals(mid, current[0].sourceId)
        assertEquals(TrackKey(MusicSource.QQMUSIC, synth, mid), QueueKeys.keyOf(current[0]))
    }

    @Test
    fun `混合老新数据的队列 - 身份各自正确 且互不干扰`() {
        val gson = Gson()
        val listType = object : TypeToken<List<SongItem>>() {}.type
        val mid = "0039MnYb0qxYhV"
        val synth = SourceIds.qqId(123L, mid)
        val json = """
            [{"id":123,"name":"老 ncm"},
             {"id":$synth,"name":"新 QQ","source":"qqmusic","mid":"$mid"}]
        """.trimIndent()
        val queue: List<SongItem> = gson.fromJson(json, listType)
        val keys = QueueKeys.keysOf(queue)
        assertEquals(MusicSource.NETEASE, keys[0].source)
        assertEquals(MusicSource.QQMUSIC, keys[1].source)
        assertFalse(QueueKeys.hasDuplicates(keys))
        // 两首歌的**原始 songid 都是 123**，但身份不同 —— 这正是 TrackKey 要表达的事。
        assertEquals(123L, SourceIds.qqRawId(keys[1].id))
        assertNotEquals(keys[0], keys[1])
    }

    @Test
    fun `v2_5_2 的队列在 v2_5_3 下判重结果逐条一致（搬家 ≠ 改行为）`() {
        // 对**正常路径**（source 有值）而言，旧口径 `SourceIds.trackKey(musicSource, id)`
        // 与新口径 `TrackKey.ofSong().tag` 必须给出同一个串；只有「source 丢了但 id 带
        // bit62」这一种形态会不同，而那是修正。
        val songs = listOf(
            netease(5257138),
            netease(287035),
            qq(97773),
            SongItem(id = 42L, name = "无 source", artists = null, album = null, duration = null),
        )
        songs.forEach { s ->
            val old = com.takahashirinta.ncrust.source.SourceIds.trackKey(s.musicSource, s.id)
            assertEquals("正常路径下新旧判重键必须相同：$s", old, s.dedupeKey)
        }
    }

    // ---------------------------------------------------------------- 待播槽位

    /**
     * **待播槽位行为不变**。
     *
     * 槽位判定（[PreloadSlot]）从 v1.5.2 起就是按 `mediaId`（内含音源）走的，
     * 本版**没有碰它**。这里把它与队列身份对齐一遍，确认两者对「同一首歌」给出同一个答案 ——
     * 这正是「队列判重按 A、槽位按 B」那种分叉不会再出现的形式化表达。
     */
    @Test
    fun `待播槽位 - 身份判定与改造前逐值一致`() {
        val ne = netease(123)
        val q = qq(123)

        // mediaId 里编了音源：ncm song:123 / QQ song:qqmusic:<synth>
        assertEquals("song:123", PreloadSlot.mediaIdFor(MusicSource.NETEASE, ne.id, "http://x"))
        assertEquals(
            "song:qqmusic:${q.id}",
            PreloadSlot.mediaIdFor(MusicSource.QQMUSIC, q.id, "http://x"),
        )

        // 两条 mediaId 反解出来的 `(音源, id)`，与队列身份**逐字段**一致。
        assertEquals(
            QueueKeys.keyOf(ne).source to QueueKeys.keyOf(ne).id,
            PreloadSlot.identityFromMediaId(PreloadSlot.mediaIdFor(MusicSource.NETEASE, ne.id, "u")),
        )
        assertEquals(
            QueueKeys.keyOf(q).source to QueueKeys.keyOf(q).id,
            PreloadSlot.identityFromMediaId(PreloadSlot.mediaIdFor(MusicSource.QQMUSIC, q.id, "u")),
        )
        // 跨源同号反解出来的两件套不相等 —— 槽位不会认错歌。
        assertNotEquals(
            PreloadSlot.identityFromMediaId(PreloadSlot.mediaIdFor(MusicSource.NETEASE, 123L, "u")),
            PreloadSlot.identityFromMediaId(PreloadSlot.mediaIdFor(MusicSource.QQMUSIC, 123L, "u")),
        )
    }

    @Test
    fun `待播槽位的 decide 与队列身份无关（仍按 mediaId 判定）`() {
        // 直接钉住既有契约：同 mediaId ⇒ 幂等忽略；不同 ⇒ 替换。
        assertEquals(
            PreloadSlot.Decision.IGNORE,
            PreloadSlot.decide(pendingSongId = 1L, pendingUrl = "u", incomingSongId = 1L, incomingUrl = "u"),
        )
        assertEquals(
            PreloadSlot.Decision.APPEND,
            PreloadSlot.decide(pendingSongId = -1L, pendingUrl = null, incomingSongId = 2L, incomingUrl = "v"),
        )
        assertEquals(
            PreloadSlot.Decision.REPLACE,
            PreloadSlot.decide(pendingSongId = 1L, pendingUrl = "u", incomingSongId = 2L, incomingUrl = "v"),
        )
    }

    // ---------------------------------------------------------------- 随机模式

    /**
     * **随机模式行为不变**：编排序列是**下标**，与身份类型无关。
     *
     * 本版把 `QueueInsert.shuffleAfterInsert` 的入参类型换成了 `TrackKey`，
     * 但算法一行未动。这里用一个**跨源**队列复核结果仍是合法排列，
     * 且插入项确实落在当前歌的播放顺序之后。
     */
    @Test
    fun `随机模式 - 跨源插入后的排列仍然合法`() {
        // 队列：ncm1, QQ1(合成), ncm2, ncm3 —— 4 首，当前 = ncm1
        val songs = listOf(netease(1), qq(1), netease(2), netease(3))
        val oldKeys = QueueKeys.keysOf(songs)
        assertEquals(4, oldKeys.size)
        assertFalse(QueueKeys.hasDuplicates(oldKeys))

        val newSong = netease(99)
        val plan = QueueInsert.plan(oldKeys, currentIndex = 0, newKey = QueueKeys.keyOf(newSong))
        assertEquals(QueueInsert.Outcome.INSERTED, plan.outcome)
        assertEquals(5, plan.keys.size)
        assertFalse(QueueKeys.hasDuplicates(plan.keys))

        // 编排：下标 0,2,3,1（= ncm1, ncm2, ncm3, QQ1）
        val shuffled = listOf(0, 2, 3, 1)
        val fixed = QueueInsert.shuffleAfterInsert(
            oldKeys = oldKeys,
            newKeys = plan.keys,
            shuffled = shuffled,
            newCurrentIndex = plan.currentIndex,
            insertPos = plan.insertPos,
        )
        assertTrue("必须给出一个合法排列", fixed != null)
        assertEquals("排列必须恰好覆盖每个下标一次", plan.keys.size, fixed!!.size)
        assertEquals(plan.keys.size, fixed.toSet().size)
        val pos = fixed.indexOf(plan.currentIndex)
        assertEquals("插入项必须紧跟在当前歌的播放顺序之后", plan.insertPos, fixed[pos + 1])
    }

    @Test
    fun `随机模式 - nextIndexAfterInsert 在乱序下走编排而不是线性加一`() {
        // 编排 0,2,1（3 首），当前 = 0 ⇒ 下一首应当是下标 2，而不是 1。
        val shuffled = listOf(0, 2, 1)
        assertEquals(
            2,
            QueueInsert.nextIndexAfterInsert(
                playMode = QueueModes.SHUFFLE, size = 3, newCurrentIndex = 0,
                shuffledIndices = shuffled,
            ),
        )
        // 非乱序模式仍然是线性 +1（含队尾返回 -1）。
        assertEquals(
            1,
            QueueInsert.nextIndexAfterInsert(
                playMode = QueueModes.CYCLE, size = 3, newCurrentIndex = 0,
                shuffledIndices = shuffled,
            ),
        )
        assertEquals(
            -1,
            QueueInsert.nextIndexAfterInsert(
                playMode = QueueModes.LINE, size = 3, newCurrentIndex = 2,
                shuffledIndices = shuffled,
            ),
        )
    }

    // ---------------------------------------------------------------- 规则防线

    /**
     * **AGENTS.md v2.5.3 规则 2 的机器守卫**：队列去重不得再用裸 `song.id`。
     *
     * 这条不是风格检查 —— `QueueInsert.plan` 的签名一旦退回 `List<Long>`，
     * 「跨源同号」就又变成一次判重事故的候选项。用反射把签名钉住，
     * 改签名的人必须同时改这条用例（留下可追溯的记录），而不是悄悄改回去。
     */
    @Test
    fun `QueueInsert 的签名不许再退回裸 Long`() {
        val plan = QueueInsert::class.java.methods.first { it.name == "plan" }
        val types = plan.genericParameterTypes
        assertEquals("plan 的第一个参数应当是 List<TrackKey>", 3, types.size)
        assertEquals(
            "java.util.List",
            (types[0] as java.lang.reflect.ParameterizedType).rawType.typeName,
        )
        assertEquals(
            "plan 的第一个参数的元素类型必须是 TrackKey —— 队列身份不许退回裸 Long",
            TrackKey::class.java,
            (types[0] as java.lang.reflect.ParameterizedType).actualTypeArguments[0],
        )
        assertEquals(
            "plan 的第三个参数必须是 TrackKey —— 新歌的身份不许退回裸 Long",
            TrackKey::class.java,
            types[2],
        )
    }

    @Test
    fun `QueueKeys 是队列身份的唯一落点（不再有 map it_id 这种写法）`() {
        // `QueueKeys.keyOf` 必须与 `SongItem.trackKeyOf()` 是同一件事，
        // 否则「同一个应用里两套身份规则」会以另一种形式回来。
        val songs = listOf(netease(1), qq(2), qqWithPlainId(3))
        songs.forEach { assertEquals(it.trackKeyOf(), QueueKeys.keyOf(it)) }
        assertEquals(songs.map { it.trackKeyOf() }, QueueKeys.keysOf(songs))
    }
}
