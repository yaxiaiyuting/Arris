/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.6.0 · P1/P2 单元测试：库页「歌单」tab 的**布局模式**与**三个区块的折叠状态**。
 *
 * 铁律 24：「布局切换状态必须持久化，切换后立即生效。」这条在单测里能证明的
 * 只有**持久化那一半**（读写往返、脏值回落、键名稳定）；「立即生效」是 UI 行为，
 * 由真机验证覆盖（见 `docs/verification/v2.6.0/EVIDENCE.md`）——
 * 这里不拿单测冒充它。
 *
 * FakePrefs 手写（不引 mock 框架）：与 `PageTransitionSettingTest` /
 * `LyricsSourcePrefsTest` 同一手法 —— 需要能构造「键存在但类型不对」
 * 这类真实磁盘上会出现、而类型安全 API 造不出来的形状。
 */

package com.takahashirinta.ncrust.ui.screen

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 最小可用的 [SharedPreferences] 假实现。
 *
 * 只实现本页真正用到的读写；`edit()` 返回的 Editor 立刻生效（apply 与 commit 同义）——
 * 与真机上的可见性差异不是这个测试要证明的东西。
 */
private class FakePrefs(
    private val map: MutableMap<String, Any?> = mutableMapOf(),
) : SharedPreferences {

    /** 写入一个**类型错误**的值（真机上 SharedPreferences 允许 `putString` 到任意键）。 */
    fun putRaw(key: String, value: Any?) {
        map[key] = value
    }

    override fun contains(key: String): Boolean = map.containsKey(key)

    // ⚠️ 必须**忠实复刻真机行为**：键存在但类型不对时，真的 SharedPreferences 会抛
    //    ClassCastException，而不是安静地回落默认值。写成 `as? Int ?: defValue`
    //    会把「生产代码有没有吞掉这个异常」这件事测掉 —— 那正是这条用例要测的。
    override fun getInt(key: String, defValue: Int): Int {
        val v = map[key] ?: return defValue
        return v as? Int ?: throw ClassCastException("$key is not an Int")
    }

    override fun getBoolean(key: String, defValue: Boolean): Boolean {
        val v = map[key] ?: return defValue
        return v as? Boolean ?: throw ClassCastException("$key is not a Boolean")
    }

    override fun getString(key: String, defValue: String?): String? {
        val v = map[key] ?: return defValue
        return v as? String ?: throw ClassCastException("$key is not a String")
    }
    override fun getAll(): MutableMap<String, *> = map
    override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        defValues

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val staged = mutableMapOf<String, Any?>()
        private var clear = false
        override fun putInt(key: String, value: Int) = apply { staged[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { staged[key] = value }
        override fun putString(key: String, value: String?) = apply { staged[key] = value }
        override fun putLong(key: String, value: Long) = apply { staged[key] = value }
        override fun putFloat(key: String, value: Float) = apply { staged[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { staged[key] = values }
        override fun remove(key: String) = apply { staged[key] = REMOVED }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean { flush(); return true }
        override fun apply() { flush() }
        private fun flush() {
            if (clear) map.clear()
            staged.forEach { (k, v) -> if (v === REMOVED) map.remove(k) else map[k] = v }
            staged.clear()
        }
    }

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    private companion object {
        val REMOVED = Any()
    }
}

class PlaylistLayoutSettingTest {

    // ---------------------------------------------------------------- 默认值 ----

    @Test
    fun `键不存在时回落卡片式`() {
        val p = FakePrefs()
        assertNull("新用户应当读不到这个键", PlaylistLayoutSetting.readStoredIndex(p))
        assertEquals(PlaylistLayout.CARD, PlaylistLayout.fromIndex(PlaylistLayoutSetting.readStoredIndex(p)))
        assertEquals("默认值必须是 CARD（见 PlaylistLayout 的 KDoc）", 0, PlaylistLayout.DEFAULT_INDEX)
    }

    @Test
    fun `默认值的语义被钉住（CARD 是面积最大的两段的现状）`() {
        assertEquals(PlaylistLayout.CARD, PlaylistLayout.entries[PlaylistLayout.DEFAULT_INDEX])
        assertEquals(2, PlaylistLayout.entries.size)
    }

    // ---------------------------------------------------------------- 往返 ----

    @Test
    fun `写入列表式后读回列表式（重启后保持）`() {
        val p = FakePrefs()
        PlaylistLayoutSetting.write(p, PlaylistLayout.LIST)
        assertEquals(1, PlaylistLayoutSetting.readStoredIndex(p))
        assertEquals(PlaylistLayout.LIST, PlaylistLayout.fromIndex(PlaylistLayoutSetting.readStoredIndex(p)))
    }

    @Test
    fun `写入卡片式后读回卡片式（切回去也要落盘）`() {
        val p = FakePrefs()
        PlaylistLayoutSetting.write(p, PlaylistLayout.LIST)
        PlaylistLayoutSetting.write(p, PlaylistLayout.CARD)
        assertEquals(PlaylistLayout.CARD, PlaylistLayout.fromIndex(PlaylistLayoutSetting.readStoredIndex(p)))
    }

    @Test
    fun `落盘的是整数索引而不是枚举名`() {
        val p = FakePrefs()
        PlaylistLayoutSetting.write(p, PlaylistLayout.LIST)
        assertEquals(
            "存枚举名会让「将来改枚举顺序」变成一次静默的数据损坏",
            1,
            p.getAll()[PlaylistLayoutSetting.KEY_LAYOUT],
        )
    }

    // ------------------------------------------------------------ 脏值回落 ----

    @Test
    fun `越界的整数回落默认值而不是抛异常`() {
        assertEquals(PlaylistLayout.CARD, PlaylistLayout.fromIndex(-1))
        assertEquals(PlaylistLayout.CARD, PlaylistLayout.fromIndex(2))
        assertEquals(PlaylistLayout.CARD, PlaylistLayout.fromIndex(999))
        assertEquals(PlaylistLayout.CARD, PlaylistLayout.fromIndex(null))
    }

    @Test
    fun `键存在但类型是字符串时回落默认值（不崩）`() {
        val p = FakePrefs()
        p.putRaw(PlaylistLayoutSetting.KEY_LAYOUT, "list")
        // readStoredIndex 吞掉 ClassCastException 并返回 null ⇒ fromIndex 回落 CARD。
        val raw = PlaylistLayoutSetting.readStoredIndex(p)
        assertNull("类型错配必须被吞掉，否则真机上是 ClassCastException 崩溃", raw)
        assertEquals(PlaylistLayout.CARD, PlaylistLayout.fromIndex(raw))
    }

    // ------------------------------------------------------------ 键名契约 ----

    /**
     * 键名是**对外契约**（用户在 root / 备份还原时看得见它）。
     *
     * 与 `PersistenceFieldNameContractTest` 的纪律同源：改名会让老用户的偏好
     * 静默回到默认（不崩、不报错，只是「我明明设过」）。这里把它钉死，
     * 改名会让用例变红而不是让用户的选择消失。
     */
    @Test
    fun `prefs 文件名与键名是稳定契约`() {
        assertEquals("ncrust_settings", PlaylistLayoutSetting.PREFS_NAME)
        assertEquals("library_playlist_layout", PlaylistLayoutSetting.KEY_LAYOUT)
    }
}

class LibrarySectionFoldSettingTest {

    // ---------------------------------------------------------------- 默认值 ----

    @Test
    fun `默认全展开（老用户升级零行为变化）`() {
        val p = FakePrefs()
        val fold = LibrarySectionFold(
            collapsedLocal = LibrarySectionFoldSetting.readBool(p, LibrarySection.LOCAL.prefsKey),
            collapsedNetease = LibrarySectionFoldSetting.readBool(p, LibrarySection.NETEASE.prefsKey),
            collapsedQq = LibrarySectionFoldSetting.readBool(p, LibrarySection.QQMUSIC.prefsKey),
        )
        assertEquals(LibrarySectionFold.ALL_EXPANDED, fold)
        assertFalse(fold.isCollapsed(LibrarySection.LOCAL))
        assertFalse(fold.isCollapsed(LibrarySection.NETEASE))
        assertFalse(fold.isCollapsed(LibrarySection.QQMUSIC))
    }

    @Test
    fun `三个区块的键互不相同且都是稳定契约`() {
        val keys = LibrarySection.entries.map { it.prefsKey }
        assertEquals("三个键必须互不相同，否则折叠一个会连带折叠另一个", 3, keys.distinct().size)
        assertEquals(
            listOf(
                "library_section_collapsed_local",
                "library_section_collapsed_netease",
                "library_section_collapsed_qq",
            ),
            keys,
        )
        assertEquals("ncrust_settings", LibrarySectionFoldSetting.PREFS_NAME)
    }

    // ---------------------------------------------------------------- 往返 ----

    @Test
    fun `折叠一个区块后读回 其余不受影响`() {
        val p = FakePrefs()
        LibrarySectionFoldSetting.write(p, LibrarySection.QQMUSIC, true)
        val fold = LibrarySectionFold(
            collapsedLocal = LibrarySectionFoldSetting.readBool(p, LibrarySection.LOCAL.prefsKey),
            collapsedNetease = LibrarySectionFoldSetting.readBool(p, LibrarySection.NETEASE.prefsKey),
            collapsedQq = LibrarySectionFoldSetting.readBool(p, LibrarySection.QQMUSIC.prefsKey),
        )
        assertTrue(fold.isCollapsed(LibrarySection.QQMUSIC))
        assertFalse("折叠 QQ 不该连带折叠本地", fold.isCollapsed(LibrarySection.LOCAL))
        assertFalse("折叠 QQ 不该连带折叠 ncm", fold.isCollapsed(LibrarySection.NETEASE))
    }

    /**
     * 只写**被改动的那一个键**：`ncrust_settings` 由 ThemeManager / LanguageManager /
     * 设置页共享，整表重写会覆盖并发写入（例如用户此刻正好在切主题）。
     */
    @Test
    fun `写一个区块不会碰其他区块的键`() {
        val p = FakePrefs()
        LibrarySectionFoldSetting.write(p, LibrarySection.LOCAL, true)
        assertFalse(
            "写 LOCAL 时 ncm 的键不该被创建",
            p.contains(LibrarySection.NETEASE.prefsKey),
        )
        assertFalse(p.contains(LibrarySection.QQMUSIC.prefsKey))
    }

    @Test
    fun `展开回去也要落盘（false 是一个显式取值，不是「删键」）`() {
        val p = FakePrefs()
        LibrarySectionFoldSetting.write(p, LibrarySection.LOCAL, true)
        LibrarySectionFoldSetting.write(p, LibrarySection.LOCAL, false)
        assertTrue("键必须还在（显式 false）", p.contains(LibrarySection.LOCAL.prefsKey))
        assertFalse(LibrarySectionFoldSetting.readBool(p, LibrarySection.LOCAL.prefsKey))
    }

    // ------------------------------------------------------------ 脏值回落 ----

    @Test
    fun `键存在但类型不是布尔时回落展开（不崩）`() {
        val p = FakePrefs()
        p.putRaw(LibrarySection.LOCAL.prefsKey, "true")
        assertFalse(
            "类型错配必须被吞掉 —— ncrust_settings 是用户可编辑的（root / 备份还原）",
            LibrarySectionFoldSetting.readBool(p, LibrarySection.LOCAL.prefsKey),
        )
    }

    // -------------------------------------------------------- 状态机（纯逻辑） ----

    @Test
    fun `toggled 只反转被点的那个区块 且是纯函数`() {
        val base = LibrarySectionFold.ALL_EXPANDED
        val a = base.toggled(LibrarySection.NETEASE)
        assertTrue(a.isCollapsed(LibrarySection.NETEASE))
        assertFalse(a.isCollapsed(LibrarySection.LOCAL))
        assertFalse(a.isCollapsed(LibrarySection.QQMUSIC))
        assertEquals("toggled 不许改原对象（不可变值对象）", LibrarySectionFold.ALL_EXPANDED, base)

        val b = a.toggled(LibrarySection.NETEASE)
        assertEquals("再点一次必须回到原状", base, b)
    }

    @Test
    fun `三个区块可以各自独立折叠`() {
        val all = LibrarySection.entries.fold(LibrarySectionFold.ALL_EXPANDED) { acc, s -> acc.toggled(s) }
        LibrarySection.entries.forEach { assertTrue("${it.name} 应当被折叠", all.isCollapsed(it)) }
        val back = LibrarySection.entries.fold(all) { acc, s -> acc.toggled(s) }
        assertEquals(LibrarySectionFold.ALL_EXPANDED, back)
    }

    /**
     * ★ **不自动折叠**的结构性证明（铁律：歌单绝不自动折叠）。
     *
     * `LibrarySectionFold` 的公开 API 只有 [LibrarySectionFold.toggled] 与
     * [LibrarySectionFold.isCollapsed] —— **没有任何**「按数量/按加载结果设置状态」
     * 的入口。这条用例把那个事实钉成断言：反射列出全部公开方法，
     * 只允许白名单里的名字出现。将来若有人加一个 `collapseIfLarge(n)`，
     * 这条会红，而 code review 很可能会放过它（它在阅读时完全合理）。
     */
    @Test
    fun `折叠状态只有 toggle 一个入口（不自动折叠的结构性证明）`() {
        val allowed = setOf(
            "isCollapsed", "toggled", "getCollapsedLocal", "getCollapsedNetease",
            "getCollapsedQq", "component1", "component2", "component3", "copy",
            "equals", "hashCode", "toString",
        )
        // 只看**本类声明**的方法：`getClass`/`wait`/`notify` 是 `Any` 的，
        // 把它们算进来会让这条断言永远红（那种「反正会红」的防线等于没有防线）。
        val offenders = LibrarySectionFold::class.java.declaredMethods
            .filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .filterNot { it in allowed }
            .filterNot { it.startsWith("copy\$") || it.startsWith("access\$") }
            .distinct()
        assertTrue(
            "LibrarySectionFold 上出现了白名单外的公开方法：$offenders —— " +
                "如果它是「自动折叠」的入口，那正是本版明确禁止的行为；" +
                "如果是别的用途，请把它加进这条用例的白名单并说明理由。",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `折叠状态的默认构造是全展开`() {
        assertEquals(LibrarySectionFold(false, false, false), LibrarySectionFold())
        assertEquals(LibrarySectionFold(), LibrarySectionFold.ALL_EXPANDED)
    }
}
