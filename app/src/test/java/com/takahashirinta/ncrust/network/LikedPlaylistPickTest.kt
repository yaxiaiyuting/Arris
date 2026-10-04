/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.4.5 · P0（移植自上游 de193ff）：红心歌单挑选规则的守卫。
 */

package com.takahashirinta.ncrust.network

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [pickLikedPlaylistIdFrom] 的纯逻辑守卫。
 *
 * ## 这些用例对应的**真机事实**
 *
 * 上游 `de193ff` 的根因是「取第一个 `specialType != 0`」这个谓词**不唯一**。
 * 所以下面第一条用例就是那个**反例**：让一个「别的特殊歌单」排在红心歌单**前面** ——
 * 它在旧实现下会红（旧实现返回 `111`，正确值是 `999`）。
 *
 * JSON 形状取自 `/eapi/user/playlist` 的真实响应字段（`id` / `name` / `specialType`），
 * 只保留本函数读到的三个 key。
 */
class LikedPlaylistPickTest {

    private fun list(vararg items: JSONObject): JSONArray =
        JSONArray().apply { items.forEach { put(it) } }

    private fun playlist(id: Long, name: String, specialType: Int): JSONObject =
        JSONObject()
            .put("id", id)
            .put("name", name)
            .put("specialType", specialType)

    // ------------------------------------------------- ① 名称优先（本次修复的核心）

    /**
     * ★ 回归守卫：**另一个特殊歌单排在前面时，必须仍然选中红心歌单**。
     *
     * 旧实现（「第一个 `specialType != 0`」）在这里返回 `111` —— 它会去读
     * `111` 的 trackIds，与 like 的写入目标不一致，并把那个歌单的歌**导入**本地收藏。
     */
    @Test
    fun `另一个特殊歌单排在前面时仍然选中红心歌单`() {
        val arr = list(
            playlist(111L, "某个别的特殊歌单", specialType = 1),
            playlist(999L, "我喜欢的音乐", specialType = 5),
            playlist(222L, "我的自建歌单", specialType = 0),
        )
        assertEquals(999L, pickLikedPlaylistIdFrom(arr))
    }

    @Test
    fun `名称命中优先于 specialType 命中`() {
        val arr = list(
            playlist(111L, "早期特殊歌单", specialType = 5),
            playlist(999L, "我喜欢的音乐", specialType = 5),
        )
        assertEquals("两条都是 specialType=5 时，名称是最强判据", 999L, pickLikedPlaylistIdFrom(arr))
    }

    /** 繁体/多语言账号下名字可能只有「喜欢的音乐」——两种子串都要收。 */
    @Test
    fun `名称只含「喜欢的音乐」也能命中`() {
        val arr = list(
            playlist(111L, "其他", specialType = 1),
            playlist(999L, "喜欢的音乐", specialType = 0),
        )
        assertEquals(999L, pickLikedPlaylistIdFrom(arr))
    }

    // ------------------------------------------------- ② specialType == 5

    /** 名称对不上（例如账号语言导致名字被本地化）时，退到 `specialType == 5`。 */
    @Test
    fun `名称对不上时按 specialType 等于 5 命中`() {
        val arr = list(
            playlist(111L, "Liked Songs", specialType = 1),
            playlist(999L, "Liked Songs", specialType = 5),
        )
        assertEquals(999L, pickLikedPlaylistIdFrom(arr))
    }

    // ------------------------------------------------- ③ 最后兜底

    /** 个别老账号 `specialType` 取值不同 —— 此时仍然要读到一个特殊歌单，而不是 null。 */
    @Test
    fun `未知 specialType 取值时退到任意特殊歌单`() {
        val arr = list(
            playlist(111L, "自建歌单", specialType = 0),
            playlist(999L, "老账号的特殊歌单", specialType = 7),
        )
        assertEquals(999L, pickLikedPlaylistIdFrom(arr))
    }

    // ------------------------------------------------- 边界与容错

    /** 全是普通自建歌单 ⇒ **null**（调用方必须当成读取失败，而不是「收藏为空」）。 */
    @Test
    fun `全是普通歌单时返回 null`() {
        val arr = list(
            playlist(1L, "歌单一", specialType = 0),
            playlist(2L, "歌单二", specialType = 0),
        )
        assertNull(pickLikedPlaylistIdFrom(arr))
    }

    @Test
    fun `空数组与 null 都返回 null`() {
        assertNull(pickLikedPlaylistIdFrom(JSONArray()))
        assertNull(pickLikedPlaylistIdFrom(null))
    }

    /** 缺 `name` / 缺 `specialType` 的条目不许让整次挑选崩掉，也不许误命中。 */
    @Test
    fun `缺字段的条目不崩也不误命中`() {
        val arr = list(
            JSONObject().put("id", 111L),                                  // 两个 key 都缺
            JSONObject().put("id", 222L).put("name", JSONObject.NULL),      // name 是 null
            playlist(333L, "我的自建歌单", specialType = 0),
        )
        assertNull(pickLikedPlaylistIdFrom(arr))
    }

    /** 非对象元素（脏数据）跳过，不影响后面的正常条目。 */
    @Test
    fun `数组里的脏元素被跳过`() {
        val arr = JSONArray().apply {
            put("这是一个字符串，不是对象")
            put(playlist(999L, "我喜欢的音乐", specialType = 5))
        }
        assertEquals(999L, pickLikedPlaylistIdFrom(arr))
    }

    /**
     * 空名字不许被当成命中。
     *
     * 名称判据是「`name.contains(子串)`」—— 只要 `name` 不真的含那个子串就不该命中；
     * 这里钉住「名字缺失/为空 ⇒ 不匹配，继续往下走」，避免有人把判据改成
     * 「子串.contains(name)」这类方向反了就恒真的写法。
     */
    @Test
    fun `空名字不会误命中`() {
        val arr = list(
            playlist(111L, "", specialType = 0),
            playlist(222L, "普通歌单", specialType = 0),
        )
        assertNull(pickLikedPlaylistIdFrom(arr))
    }
}
