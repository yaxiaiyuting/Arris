/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.6.2 · P0：值域判据（唯一落点）的单测。
 */

package com.takahashirinta.ncrust.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SourceIdDomain] 的单测（v2.6.2 · P0 抽出）。
 *
 * ## 为什么单独给它一个文件
 *
 * 它是「传错源就必然失败」的**唯一结构性防线**：两个 P0
 * （v2.6.1 周杰伦 `4558` → 马洪波、v2.6.2 陈奕迅 `22276` → 陈小云）
 * 的共同形状都是"把一个源的数字 id 拿去另一个源查"。
 * 这道闸门一旦被绕过或改宽，两个 bug 会**同时**回来，而且表现是"页面看起来正常"。
 *
 * 所以这里不测"某个调用点"，只测规则本身；调用点由
 * `ArtistNavigatorTest` / `AlbumNavigatorTest` 各自覆盖。
 *
 * ## 一份实现，两个语境
 *
 * `ArtistNavigator.idDomainMatches` 与 `AlbumNavigator.idDomainMatches` 都必须
 * 委托到本对象 —— 「艺人跳得对、专辑跳错」正是规则分成两份时的典型症状。
 */
class SourceIdDomainTest {

    // ------------------------------------------------------------ ncm

    @Test
    fun `ncm只吃十进制正整数`() {
        assertTrue(SourceIdDomain.matches(MusicSource.NETEASE, "1"))
        assertTrue(SourceIdDomain.matches(MusicSource.NETEASE, "6451"))
        assertTrue(SourceIdDomain.matches(MusicSource.NETEASE, "18905"))
        assertTrue("首尾空白在闸门内被 trim 掉", SourceIdDomain.matches(MusicSource.NETEASE, " 6451 "))
    }

    @Test
    fun `ncm拒绝零 负数 小数与任何非数字形状`() {
        for (bad in listOf("0", "-1", "1.5", "6451L", "abc", "004Z85XP1c25b7", "12a", "", "   ")) {
            assertFalse("'$bad' 不该通过 ncm 闸门", SourceIdDomain.matches(MusicSource.NETEASE, bad))
        }
        assertFalse(SourceIdDomain.matches(MusicSource.NETEASE, null))
    }

    @Test
    fun `ncm拒绝超出值域上界的数`() {
        assertTrue(SourceIdDomain.matches(MusicSource.NETEASE, (SourceIdDomain.NETEASE_ID_MAX - 1).toString()))
        assertFalse(SourceIdDomain.matches(MusicSource.NETEASE, SourceIdDomain.NETEASE_ID_MAX.toString()))
        assertFalse(
            "QQ 的 bit62 合成 id 必须落在 ncm 值域之外",
            SourceIdDomain.matches(MusicSource.NETEASE, SourceIds.QQ_ID_FLAG.toString()),
        )
    }

    // ------------------------------------------------------------ qm

    @Test
    fun `QQ 只吃 base62 的 mid`() {
        // 真实值：albumMID《What's Going On...?》/ 《叶惠美》/ 《U 87》/ 《第二天堂》/ singerMID 周杰伦
        for (good in listOf(
            "004Z85XP1c25b7", "000MkMni19ClKG", "003J6fvc0bVJon", "000y5gq7449K9I", "0025NhlN2yWrP4",
        )) {
            assertTrue("'$good' 应当是合法 mid", SourceIdDomain.matches(MusicSource.QQMUSIC, good))
        }
    }

    @Test
    fun `QQ 拒绝ncm值域内的纯数字——这就是两个 P0 的形状`() {
        for (qqNumeric in listOf("4558", "22276", "7879", "8220", "143", "1")) {
            assertFalse(
                "'$qqNumeric' 是 QQ 的数字 id，不是 mid —— 放它过去就等于放回了 P0",
                SourceIdDomain.matches(MusicSource.QQMUSIC, qqNumeric),
            )
        }
    }

    @Test
    fun `QQ 拒绝 pmid——封面照片 id 不是身份`() {
        // `pmid = "<albumMid>_<封面序号>"`。实测服务端**碰巧**能容忍它，
        // 但那是服务端的宽容、不是契约；把身份建立在巧合上迟早出错。
        assertFalse(SourceIdDomain.matches(MusicSource.QQMUSIC, "004Z85XP1c25b7_5"))
        assertFalse(SourceIdDomain.matches(MusicSource.QQMUSIC, "000MkMni19ClKG_3"))
    }

    @Test
    fun `QQ 拒绝过短 过长 与非 base62 字符`() {
        assertFalse(SourceIdDomain.matches(MusicSource.QQMUSIC, "abcd"))          // 短于下界
        assertFalse(SourceIdDomain.matches(MusicSource.QQMUSIC, "a".repeat(SourceIdDomain.QQ_MID_MAX_LEN + 1)))
        assertFalse(SourceIdDomain.matches(MusicSource.QQMUSIC, "004Z85XP1c25b-"))
        assertFalse(SourceIdDomain.matches(MusicSource.QQMUSIC, "004Z85XP1c25 7"))
        assertFalse(SourceIdDomain.matches(MusicSource.QQMUSIC, null))
        assertFalse(SourceIdDomain.matches(MusicSource.QQMUSIC, ""))
        // 首尾空白在闸门内被 trim（与 ncm 一侧同一条规则），内部空白则不是合法 base62。
        assertTrue(SourceIdDomain.matches(MusicSource.QQMUSIC, " 004Z85XP1c25b7 "))
    }

    @Test
    fun `下界本身是合法的`() {
        assertTrue(SourceIdDomain.matches(MusicSource.QQMUSIC, "a".repeat(SourceIdDomain.QQ_MID_MIN_LEN)))
    }

    // -------------------------------------------------- 唯一落点（不许分叉）

    @Test
    fun `两个 Navigator 的闸门都委托到本对象`() {
        // 这条是"只有一份规则"的可执行版本。若有人把规则复制回某个 Navigator，
        // 复制出来的那一份迟早与这里分叉 —— 而分叉的症状正是"某个入口跳错"。
        val ids = listOf(
            "6451", "18905", "22276", "7879", "1", "0", "-1",
            "004Z85XP1c25b7", "004Z85XP1c25b7_5", "0025NhlN2yWrP4",
            "abcd", "", "   ", "4611686018427837109",
        )
        for (source in MusicSource.values()) {
            for (id in ids) {
                val expected = SourceIdDomain.matches(source, id)
                assertEquals("ArtistNavigator 与 SourceIdDomain 分叉了：($source, '$id')",
                    expected, ArtistNavigator.idDomainMatches(source, id))
                assertEquals("AlbumNavigator 与 SourceIdDomain 分叉了：($source, '$id')",
                    expected, AlbumNavigator.idDomainMatches(source, id))
            }
        }
    }

    @Test
    fun `值域上界与 v2_6_1 的常量是同一个数`() {
        // 搬迁必须行为零变化：v2.6.1 的公开常量与这里的取值逐位相同。
        assertEquals(1L shl 40, SourceIdDomain.NETEASE_ID_MAX)
        assertEquals(SourceIdDomain.NETEASE_ID_MAX, ArtistNavigator.NETEASE_ID_MAX)
        assertEquals(SourceIdDomain.NETEASE_ID_MAX, AlbumNavigator.NETEASE_ID_MAX)
    }
}
