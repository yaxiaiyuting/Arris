/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.4.0 · E：名称归一化的回归单测。用例里的字符串**全部来自探针的真实数据**
 * （`docs/verification/v2.4.0/probe-raw/` 下的 JSON），不是编出来的。
 */

package com.takahashirinta.ncrust.crosssource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NameNormalizerTest {

    // ------------------------------------------------------------------ 括注 ----

    @Test
    fun `括注及其中内容被整体剥掉`() {
        assertEquals("叶惠美", NameNormalizer.normalizeName("叶惠美（Deluxe）"))
        assertEquals("叶惠美", NameNormalizer.normalizeName("叶惠美 (Deluxe Edition)"))
        assertEquals("叶惠美", NameNormalizer.normalizeName("叶惠美【豪华版】"))
        assertEquals("1989", NameNormalizer.normalizeName("1989 (Taylor's Version) (Deluxe)"))
    }

    @Test
    fun `带括号与不带括号的版本词收敛到同一个结果`() {
        // 这是归一化最容易写错的一处：先剥括注再去版本词，
        // 否则 `(Deluxe Edition)` 会被括注规则整个吃掉、而裸 `Deluxe Edition`
        // 只会被版本词规则吃掉 —— 两条路必须收敛到同一个字符串。
        val withBracket = NameNormalizer.normalizeName("范特西 (Deluxe Edition)")
        val withWord = NameNormalizer.normalizeName("范特西 Deluxe Edition")
        val bare = NameNormalizer.normalizeName("范特西")
        assertEquals(bare, withBracket)
        assertEquals(bare, withWord)
    }

    // ------------------------------------------------------------------ 全角 ----

    @Test
    fun `全角字符与带圈数字被规范化`() {
        assertEquals("abc123", NameNormalizer.normalizeName("ＡＢＣ１２３"))
        assertEquals("1", NameNormalizer.normalizeName("①"))
    }

    @Test
    fun `标点与空白一律剥掉`() {
        assertEquals("cantstop", NameNormalizer.normalizeName("Can't Stop!"))
        assertEquals("周杰伦", NameNormalizer.normalizeName("周 杰 伦"))
        assertEquals("abc", NameNormalizer.normalizeName("a·b—c"))
    }

    // ------------------------------------------------------------------ 空值 ----

    @Test
    fun `空名字不参与匹配（空能匹配空是错配的捷径）`() {
        assertFalse(NameNormalizer.sameName("", ""))
        assertFalse(NameNormalizer.sameName(null, null))
        assertFalse(NameNormalizer.sameName("   ", "   "))
        assertFalse(NameNormalizer.sameName("叶惠美", ""))
        assertTrue(NameNormalizer.sameName("叶惠美", "叶惠美（Deluxe）"))
    }

    // ------------------------------------------------------------------ 包含 ----

    @Test
    fun `包含召回能认出艺名不等于真名的那一对（探针里朴素算法失败的那条）`() {
        // probe-artist-mapping.md P6 的原始数据：
        // ncm 真身是 `G.E.M.邓紫棋`(7763)，QQ 也是 `G.E.M.邓紫棋`(001fNHEf1SFEFN)；
        // 而 ncm 的仿冒号叫 `邓紫棋`(62017015)。包含关系必须**同时**召回这两个 ——
        // 判定交给专辑重合，不在这里做。
        assertTrue(NameNormalizer.containsName("邓紫棋", "G.E.M.邓紫棋"))
        assertTrue(NameNormalizer.containsName("G.E.M.邓紫棋", "邓紫棋"))
        assertTrue(NameNormalizer.containsName("周杰伦", "周杰伦jay"))
        assertTrue(NameNormalizer.containsName("周杰伦", "周杰伦♚"))
    }

    @Test
    fun `太短的包含关系不算召回（单字符包含是纯噪声）`() {
        assertFalse(NameNormalizer.containsName("A", "ABBA"))
        assertFalse(NameNormalizer.containsName("Adele", "A"))
    }

    @Test
    fun `相等永远成立 不看长度（双字华语名是主力 不能有长度门槛）`() {
        // 这条是一次真实的回归：第一版 containsName 用 minLength=3，
        // 于是 `李健`（归一化后 2 字符）连**自己**都召回不了 ——
        // 探针里明明是 EXACT 的艺人，在代码里变成了「召回为空」。
        assertTrue(NameNormalizer.containsName("李健", "李健"))
        assertTrue(NameNormalizer.containsName("周深", "周深"))
        assertTrue(NameNormalizer.containsName("李健", "李健的粉丝团"))
    }

    @Test
    fun `繁简不互相召回（本版有意不做简繁归一）`() {
        // 铁律 17 的降级原则：宁可不合并，也不冒错配风险。
        // QQ 侧同时存在 `周杰伦`(4558, 43 张) 与 `周杰倫`(23063564, 1 张)，
        // 本版把后者当成**另一个艺人**。
        assertFalse(NameNormalizer.sameName("周杰伦", "周杰倫"))
        assertFalse(NameNormalizer.containsName("周杰伦", "周杰倫"))
    }

    // ------------------------------------------------------------------ 艺人 ----

    @Test
    fun `艺人名集合只要有交集就算一致`() {
        assertTrue(NameNormalizer.artistsOverlap(listOf("周杰伦"), listOf("周杰伦", "袁咏琳")))
        assertTrue(NameNormalizer.artistsOverlap(listOf("Jay Chou"), listOf("jaychou")))
        assertFalse(NameNormalizer.artistsOverlap(listOf("周杰伦"), listOf("林俊杰")))
    }

    @Test
    fun `空艺人列表不算一致（缺字段不能变成匹配成功）`() {
        assertFalse(NameNormalizer.artistsOverlap(emptyList(), listOf("周杰伦")))
        assertFalse(NameNormalizer.artistsOverlap(null, null))
        assertFalse(NameNormalizer.artistsOverlap(listOf("", "  "), listOf("周杰伦")))
    }

    // ------------------------------------------------------------------ 版本标记 ----

    @Test
    fun `版本标记能被单独识别出来（单曲的 EXACT 与 HIGH 就靠它分叉）`() {
        assertTrue(NameNormalizer.hasEditionMarker("Enrich Your Life(伴奏)"))
        assertTrue(NameNormalizer.hasEditionMarker("你过得好吗(伴奏)"))
        assertTrue(NameNormalizer.hasEditionMarker("Secret (慢板)"))
        assertTrue(NameNormalizer.hasEditionMarker("Do You Ever Shine? (BITTER BLOOD Version)"))
        assertTrue(NameNormalizer.hasEditionMarker("晴天 (Live)"))
        assertTrue(NameNormalizer.hasEditionMarker("叶惠美 豪华版"))
        assertFalse(NameNormalizer.hasEditionMarker("晴天"))
        assertFalse(NameNormalizer.hasEditionMarker("叶惠美"))
    }

    @Test
    fun `版本标记相同的两条曲名归一化后相等`() {
        // 归一化会把 `(伴奏)` 整个剥掉，所以「原曲」与「伴奏」会落在同一个桶里 ——
        // 这正是为什么单曲匹配必须有第二道判据（时长）。这里只钉住「同桶」这个事实。
        assertEquals(
            NameNormalizer.normalizeName("你过得好吗"),
            NameNormalizer.normalizeName("你过得好吗(伴奏)"),
        )
    }

    // ------------------------------------------------------------------ 宽松 ----

    @Test
    fun `宽松归一化保留括注内容（用来区分同桶里的多个候选）`() {
        val strict = NameNormalizer.normalizeName("晴天 (Live)")
        val loose = NameNormalizer.normalizeLoose("晴天 (Live)")
        assertEquals("晴天", strict)
        assertTrue(loose.contains("live"))
        assertFalse(loose == NameNormalizer.normalizeLoose("晴天"))
    }
}
