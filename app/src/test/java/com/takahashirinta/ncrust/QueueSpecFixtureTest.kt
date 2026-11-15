/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * `spec/fixtures/queue/` 的跨端夹具对照（移植自上游 488a900）。
 */

package com.takahashirinta.ncrust

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.player.PlaybackQueueLogic
import com.takahashirinta.ncrust.player.QueueInsert
import com.takahashirinta.ncrust.player.QueueKeys
import com.takahashirinta.ncrust.player.ShuffleRound
import com.takahashirinta.ncrust.testutil.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `spec/fixtures/queue/queue.json` 的逐条对照：队列操作后的**队列内容 + 当前索引**。
 *
 * ## 除了「结果对不对」，还钉住三条不变量
 *
 * 夹具的 `expect` 只写结果，而队列真正的坑在**不变量**上（`AGENTS.md`「Queue Management」）：
 *
 * 1. `queue[current]` 恒等于正在播的那首歌 —— 所以「不换歌的操作」之后当前曲身份必须一模一样
 *    （`remove.before-current-preserves-identity` 就是这条的正面用例：删当前曲**之前**的项，
 *    当前索引必须重新定位，不能沿用旧值）；
 * 2. 队列里同一身份最多一份 —— 生产侧四处写入全部去重，夹具每条用例再验一遍；
 * 3. 模式推进要跟着队列走 —— `CYCLE` 队尾回绕、`LINE`/`INFINITY` 队尾停下、`SHUFFLE` 沿本轮次推进，
 *    这条由夹具的 `mode` 字段驱动。
 *
 * ## 为什么有两处「在测试里组合纯函数」
 *
 * `insertNext` / `playSong` / `remove` / `move` 在生产里已经是纯函数，直接调。
 * 但 `appendToQueue` / `insertAllNext` / `appendAllToQueue` 是 `MainScreen` 里的**局部函数**
 * （依赖 Compose 状态，JVM 调不到），它们的纯逻辑由 `QueueKeys` 的几个原语组合而成。
 * 所以本文件按生产代码的**同一顺序**组合那些原语，并另加一条**源码接线守卫**
 * （`批量队列写入的生产接线仍是本单测组合的那几条`）：生产改了组合而夹具没跟着改，守卫会红。
 *
 * 身份是 `TrackKey`（音源 + id），夹具里的裸 id 一律映射成 ncm 身份；
 * 详见 `spec/fixtures/queue/README.md`「Arris 适配说明」。
 */
class QueueSpecFixtureTest {

    private val cases: JsonArray by lazy {
        JsonParser.parseString(Fixtures.readText("spec/fixtures/queue/queue.json")).asJsonArray
    }

    // ---------- 身份与状态 ----------

    /** 夹具里的裸 id → ncm 队列条目（`source = null` 即本仓库对 ncm 的历史约定）。 */
    private fun song(id: Long): SongItem = SongItem(
        id = id,
        name = "song-$id",
        artists = null,
        album = null,
        duration = null,
        source = null,
        sourceId = null,
        mediaId = null,
    )

    private fun keyOf(song: SongItem) = QueueKeys.keyOf(song)

    private data class State(val queue: List<SongItem>, val current: Int) {
        val currentSong: SongItem? get() = queue.getOrNull(current)
        val ids: List<Long> get() = queue.map { it.id }
    }

    private fun ids(element: JsonArray): List<Long> = element.map { it.asLong }

    private fun modeOf(wire: String): Int = when (wire) {
        "CYCLE" -> QueueModes.CYCLE
        "SINGLE" -> QueueModes.SINGLE
        "SHUFFLE" -> QueueModes.SHUFFLE
        "LINE" -> QueueModes.LINE
        "INFINITY" -> QueueModes.INFINITY
        else -> error("夹具里有未知的 mode: $wire")
    }

    // ---------- 单个 op 的状态机（逐步对应生产落点） ----------

    private fun apply(state: State, op: JsonObject): State {
        val type = op.get("type").asString
        return when (type) {
            // 生产：MainActivity.insertNext → QueueInsert.plan + QueueKeys.rebuild
            "insertNext" -> {
                val added = song(op.get("id").asLong)
                val plan = QueueInsert.plan(state.queue.map(::keyOf), state.current, keyOf(added))
                State(
                    queue = QueueKeys.rebuild(state.queue, plan.keys, extra = listOf(added))
                        ?: error("insertNext: 队列重建失败（夹具用例不该走到这里）"),
                    current = plan.currentIndex,
                )
            }

            // 生产：MainActivity.appendToQueue —— 去重 → 尾接 → 按身份重新定位当前索引
            "append" -> {
                val added = song(op.get("id").asLong)
                val currentKey = state.currentSong?.let(::keyOf)
                val addedKey = keyOf(added)
                if (addedKey == currentKey) {
                    state
                } else {
                    val target = QueueKeys.dedupe(state.queue.map(::keyOf), addedKey) + addedKey
                    val rebuilt = QueueKeys.rebuild(state.queue, target, extra = listOf(added))
                        ?: error("append: 队列重建失败（夹具用例不该走到这里）")
                    State(
                        queue = rebuilt,
                        current = QueueKeys.indexOfCurrent(rebuilt.map(::keyOf), currentKey).coerceAtLeast(0),
                    )
                }
            }

            // 生产：MainActivity.playSongItem → QueueKeys.planPlayItem
            "playSong" -> {
                val plan = QueueKeys.planPlayItem(state.queue, state.current, song(op.get("id").asLong))
                State(plan.queue, plan.currentIndex)
            }

            // 生产：MainActivity.insertAllNext —— 剔除当前曲 → 去重要插的 → 整批插到当前歌之后
            "insertAllNext" -> {
                val added = ids(op.getAsJsonArray("ids")).map(::song)
                val currentKey = state.currentSong?.let(::keyOf)
                if (state.current < 0) {
                    // 无当前项 ⇒ 等价整队替换（生产走 replaceQueueAndPlay）。
                    State(added, 0)
                } else {
                    val toInsert = QueueKeys.excludeCurrent(added.map(::keyOf), currentKey)
                    if (toInsert.isEmpty()) {
                        state
                    } else {
                        val insertSet = toInsert.toHashSet()
                        val filtered = QueueKeys.without(state.queue.map(::keyOf), insertSet).toMutableList()
                        val newCurrentIndex = QueueKeys.indexOfCurrent(filtered, currentKey).coerceAtLeast(0)
                        val insertPos = (newCurrentIndex + 1).coerceIn(0, filtered.size)
                        filtered.addAll(insertPos, toInsert)
                        State(
                            queue = QueueKeys.rebuild(state.queue, filtered, extra = added)
                                ?: error("insertAllNext: 队列重建失败（夹具用例不该走到这里）"),
                            current = newCurrentIndex,
                        )
                    }
                }
            }

            // 生产：MainActivity.appendAllToQueue —— 只追加队列里没有的
            "appendAll" -> {
                val added = ids(op.getAsJsonArray("ids")).map(::song)
                if (state.current < 0) {
                    State(added, 0)
                } else {
                    val newSongs = QueueKeys.songsOutside(added, QueueKeys.keySetOf(state.queue))
                    if (newSongs.isEmpty()) state else State(state.queue + newSongs, state.current)
                }
            }

            // 生产：PlaybackQueueLogic.removeAt（按身份重新定位当前索引）
            "remove" -> {
                val next = PlaybackQueueLogic.removeAt(state.queue, state.current, op.get("index").asInt)
                State(next.queue, next.currentIndex)
            }

            // 生产：MainActivity.reorderByIndices（身份版是 reorderByIdentity）
            "move" -> {
                val next = reorderByIndices(
                    state.queue, state.current, op.get("from").asInt, op.get("to").asInt,
                ) ?: error("move(${op.get("from")}→${op.get("to")}): 生产会放弃这次移动，夹具期望它生效")
                State(next.queue, next.currentIndex)
            }

            // 生产：MainActivity.playFromQueue（无独立纯函数）—— 只切当前，不动队列
            "playFrom" -> State(state.queue, op.get("index").asInt)

            // 生产：MainActivity.replaceQueueAndPlay（无独立纯函数）—— 整队替换，当前置 0
            "replace" -> State(ids(op.getAsJsonArray("ids")).map(::song), 0)

            else -> error("夹具里有本单测没覆盖的 op.type: $type")
        }
    }

    // ---------- 主对照 ----------

    @Test
    fun `队列操作后的内容与当前索引与夹具一致`() {
        var checked = 0
        for (element in cases) {
            val case = element.asJsonObject
            val id = case.get("id").asString
            assertEquals("$id: kind", "op", case.get("kind").asString)

            val input = case.getAsJsonObject("input")
            val mode = modeOf(input.get("mode").asString)
            val before = State(
                queue = ids(input.getAsJsonArray("queue")).map(::song),
                current = input.get("current").asInt,
            )
            val op = input.getAsJsonObject("op")
            val after = apply(before, op)
            val expect = case.getAsJsonObject("expect")

            // ① 夹具的直接期望
            assertEquals("$id: 队列内容", ids(expect.getAsJsonArray("queue")), after.ids)
            assertEquals("$id: 当前索引", expect.get("current").asInt, after.current)

            // ② 不变量 1：本来有歌在播、且这次操作不换歌 ⇒ queue[current] 必须还是同一首歌
            if (before.currentSong != null && preservesCurrentTrack(before, op)) {
                assertEquals(
                    "$id: 这个操作不该换当前曲（queue[current] 不变量被破坏）",
                    before.currentSong?.let(::keyOf),
                    after.currentSong?.let(::keyOf),
                )
            }

            // ③ 不变量 2：队列里同一身份最多一份
            assertEquals(
                "$id: 队列身份必须唯一（生产侧所有写入都去重）",
                after.queue.map(::keyOf).distinct().size,
                after.queue.size,
            )

            // ④ 不变量 3：模式推进跟着队列/索引走
            if (after.current in after.queue.indices) {
                assertModeAdvance(id, mode, after)
            }

            // ⑤ SHUFFLE 用例的 shuffleValid
            if (expect.has("shuffleValid")) {
                assertEquals("$id: shuffleValid 只应出现在 SHUFFLE 用例", QueueModes.SHUFFLE, mode)
                assertTrue("$id: 夹具声明 shuffleValid = true", expect.get("shuffleValid").asBoolean)
                assertShuffleRoundValid(id, after)
            }
            checked++
        }
        assertEquals("夹具用例数应全部被检查", cases.size(), checked)
    }

    /**
     * 这次操作是否**应该**保持当前曲不变。
     *
     * 只有四种操作本来就换歌：删掉当前曲本身（槽位语义）、点播别的歌、`playFrom`、`replace`。
     * 其余全都不许动当前曲 —— 这正是「去重要先记当前歌、过滤后重新定位」那条不变量的反面。
     */
    private fun preservesCurrentTrack(before: State, op: JsonObject): Boolean =
        when (op.get("type").asString) {
            "insertNext", "append", "insertAllNext", "appendAll", "move" -> true
            "playSong" -> before.currentSong?.id == op.get("id").asLong
            "remove" -> op.get("index").asInt != before.current
            else -> false
        }

    private fun assertModeAdvance(id: String, mode: Int, state: State) {
        val queue = state.queue
        val current = state.current
        when (mode) {
            QueueModes.CYCLE -> {
                val next = PlaybackQueueLogic.nextIndex(queue, current, mode)
                if (queue.size > 1) {
                    assertEquals("$id: CYCLE 应按 (current+1) 推进，队尾回绕队首", (current + 1) % queue.size, next)
                } else {
                    assertEquals("$id: 单曲队列不预载自身", -1, next)
                }
            }
            QueueModes.SINGLE -> assertEquals("$id: SINGLE 始终指向自己", current, PlaybackQueueLogic.nextIndex(queue, current, mode))
            QueueModes.LINE, QueueModes.INFINITY -> {
                val expected = if (current < queue.lastIndex) current + 1 else -1
                assertEquals("$id: LINE/INFINITY 队尾没有下一首", expected, PlaybackQueueLogic.nextIndex(queue, current, mode))
            }
            QueueModes.SHUFFLE -> {
                val order = round(id, state)
                val expected = if (order.size > 1) order[1] else -1
                assertEquals(
                    "$id: SHUFFLE 的下一首是 shufflePosition+1 那一项",
                    expected,
                    PlaybackQueueLogic.nextIndex(queue, current, mode, order, 0),
                )
            }
        }
    }

    private fun round(id: String, state: State): List<Int> =
        ShuffleRound.newRound(state.queue.size, state.current, keyOf = { null }).also { order ->
            assertEquals("$id: 打乱表长度 = 队列长度", state.queue.size, order.size)
            assertEquals("$id: 打乱表必须是 0..n-1 的排列", (0 until state.queue.size).toSet(), order.toSet())
            assertEquals("$id: 打乱表首项 = 当前索引（即 shuffledPosition = 0）", state.current, order.first())
        }

    /**
     * `shuffleValid` 在本 Fork 的含义（见夹具 README）：
     * 长度 == 队列长度、是 `0..n-1` 的排列、首项 == 当前索引。
     *
     * 生成器用的是生产的 `ShuffleRound.newRound`：本 Fork **不是**「数组末尾回绕到 0」，
     * 当前曲被钉在轮次下标 0，轮末开新一轮。
     */
    private fun assertShuffleRoundValid(id: String, state: State) {
        val order = round(id, state)
        assertEquals("$id: 打乱表覆盖每个下标一次", state.queue.size, order.toSet().size)
    }

    // ---------- 接线守卫：批量写入的三段组合 ----------

    /**
     * `appendToQueue` / `insertAllNext` / `appendAllToQueue` 是 `MainScreen` 的局部函数，
     * JVM 调不到，所以本文件按它们的**组合顺序**调用 `QueueKeys` 的原语。
     * 这条守卫确保「测试组合的」与「生产写的」还是同一段 —— 谁改了另一半都会红。
     */
    @Test
    fun `批量队列写入的生产接线仍是本单测组合的那几条`() {
        val text = Fixtures.readText("app/src/main/java/com/takahashirinta/ncrust/MainActivity.kt")
        val expected = listOf(
            // appendToQueue
            "val target = QueueKeys.dedupe(QueueKeys.keysOf(playbackQueue), songKey) + songKey",
            "currentQueueIndex = QueueKeys.indexOfCurrent(QueueKeys.keysOf(rebuilt), currentKey)",
            // insertAllNext
            "val toInsert = QueueKeys.excludeCurrent(QueueKeys.keysOf(songs), currentKey)",
            "val filtered = QueueKeys.without(QueueKeys.keysOf(playbackQueue), insertSet).toMutableList()",
            "filtered.addAll(insertPos, toInsert)",
            // appendAllToQueue
            "val newSongs = QueueKeys.songsOutside(songs, QueueKeys.keySetOf(playbackQueue))",
            "playbackQueue = playbackQueue + newSongs",
        )
        for (snippet in expected) {
            assertTrue(
                "生产侧的组合变了（找不到：$snippet）。" +
                    "批量队列写入的纯逻辑顺序是本夹具的对照前提，改了要同步改 QueueSpecFixtureTest",
                text.contains(snippet),
            )
        }
    }
}
