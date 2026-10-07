/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.4.8：**改名护栏** —— 品牌名可以换，换不得的东西钉在这里。
 */

package com.takahashirinta.ncrust

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 改名（Ncrust → Arris）的**机械护栏**。
 *
 * ## 为什么需要它
 *
 * 改名这件事会诱人顺手改一批「看起来也是名字」的东西 —— 而其中若干项
 * **一改用户就掉数据或掉功能**。这一次改名（v3.4.8）里被明确挡在门外的有四类：
 *
 * | 看起来该改 | 为什么绝对不能改 |
 * |---|---|
 * | `applicationId` | 改了就变成**另一个 App**：老用户**无法覆盖安装**；且登录态、收藏、离线缓存全留在旧包里读不到 |
 * | SharedPreferences 文件名（24 个） | 改了 = 设置 / 三家账号 cookie / 收藏 / 歌词缓存 / 离线索引**全部读不到**，用户看到的是「升级后要重新登录、收藏空了」 |
 * | Kotlin 包名 `com.takahashirinta.ncrust` | 与 applicationId 同因，且是一次零用户收益的全仓库重构 |
 * | `NcrustWidgetProvider` 的类名 | 桌面已放置的小组件**会失效**（launcher 按 ComponentName 记录，改了就找不到旧组件） |
 *
 * 反过来，日志 TAG（`NcrustHttpTiming` / `NcrustTrack` / …）与
 * `NcrustColors` / `NcrustTheme` 这类**内部标识符**也保留 —— 它们被
 * `AGENTS.md` 与 `docs/verification/` 下的证据文件里的 `adb logcat -s …` 直接引用，
 * 改了会让那些排查手册失效。**用户看不见它们，改了只有坏处。**
 *
 * ## 与「上游归属」的边界
 *
 * 每个源文件头部的 `* Ncrust —— ncm 第三方客户端` 是**上游作品名**，
 * GPLv3 §5 要求保留；它下面那行 `本文件属于本 Fork（Arris，…）` 才是本 fork 的名字。
 * 本测试**只钉住「哪些不能改」，不检查归属行**（那是另一件事，且已在 v3.4.8 全量核对过）。
 */
class RenameGuardTest {

    /** 单测的工作目录是模块目录（`app/`），与 `BiliApiIoContractTest` 同一读法。 */
    private fun source(relative: String): String {
        val f = File(relative)
        assertTrue("源文件不存在（工作目录变了？）：${f.absolutePath}", f.isFile)
        return f.readText()
    }

    /** 匹配**字符串字面量**里的旧品牌名；类名/标识符不在其中。 */
    private val LiteralBrand = Regex(""""[^"\n]*Ncrust[^"\n]*"""")

    private fun mainSources(): List<File> =
        File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    // ─────────────────────────────────────────── 1. 安装身份

    /**
     * ★ `applicationId` 必须逐字不变。
     *
     * 这是「能不能覆盖安装」的唯一判据：变了就是另一个包名，老用户装不上，
     * 装了也是空数据。`BuildConfig.APPLICATION_ID` 是 AGP **从 build.gradle.kts
     * 真实取值**生成的，比读源码文本更可信。
     */
    @Test
    fun `applicationId 逐字不变（改了老用户无法覆盖安装且数据全丢）`() {
        assertEquals(
            "applicationId 变了！这会让老用户无法覆盖安装、且登录态/收藏/离线缓存全部读不到。" +
                "改名只改显示名（app_name），**绝不动 applicationId**。",
            "com.takahashirinta.ncrust",
            BuildConfig.APPLICATION_ID,
        )
    }

    /** `namespace`（Kotlin 包名）同样不动 —— 与 applicationId 同因，且零用户收益。 */
    @Test
    fun `namespace 逐字不变`() {
        val gradle = File("build.gradle.kts").readText()
        assertTrue(
            "namespace 变了 —— 这是一次零用户收益的全仓库重构，且与 applicationId 同因。",
            gradle.contains("""namespace = "com.takahashirinta.ncrust""""),
        )
    }

    // ─────────────────────────────────────────── 2. 用户数据

    /**
     * ★ 24 个 SharedPreferences 文件名必须逐字不变。
     *
     * 判据是**双向等值**：源码里的 `ncrust_*` / `search_history` 字面量集合，
     * 必须与下面这张冻结清单**完全相等** —— 少一个说明有人改了名（用户掉数据），
     * 多一个说明新增了一份 prefs（那也要在这张清单里显式登记）。
     */
    @Test
    fun `24 个 prefs 文件名逐字不变（改了用户设置与登录态全部读不到）`() {
        val frozen = setOf(
            "ncrust_settings",        // 设置总表：主题 / 语言 / 音质 / 歌词 / B站三个新参数
            "ncrust_prefs",           // ncm 登录 cookie
            "ncrust_qq_prefs",        // qm 登录 cookie（与 ncm 完全独立）
            "ncrust_bili_prefs",      // B站登录 cookie
            "ncrust_library",         // 收藏的单曲 / 专辑
            "ncrust_liked",           // 红心歌单 id
            "ncrust_local_playlists", // 本地歌单
            "ncrust_lyrics_cache",    // 歌词缓存（LRC / 译文 / 音译 / TTML 共表）
            "ncrust_offline",         // 离线索引 + URL 表
            "ncrust_playback_state",  // 上次播放 + 队列
            "ncrust_playback",
            "ncrust_home_cache",      // 首页快照
            "ncrust_daily",           // 每日推荐
            "ncrust_fm",              // 私人 FM
            "ncrust_match_cache",     // 跨源匹配缓存
            "ncrust_qq_playlists",    // qm 歌单离线缓存
            "ncrust_qq_probe",        // qm 探针水位
            "ncrust_stats",           // 播放统计
            "ncrust_report_gate",     // 上报闸门
            "ncrust_netease_vip",     // ncm 会员态缓存
            "ncrust_live_update",     // 实时更新水位
            "ncrust_device",          // 设备判据
            "ncrust_root",            // root 探针
            "search_history",         // 搜索历史（**唯一不带 ncrust_ 前缀的一份**）
        )
        assertEquals("冻结清单本身应当是 24 项", 24, frozen.size)

        val literal = Regex("\"(ncrust_[a-z_]+|search_history)\"")
        val actual = mainSources()
            .flatMap { literal.findAll(it.readText()).map { m -> m.groupValues[1] }.toList() }
            .toSet()

        assertEquals(
            "prefs 文件名集合变了 —— 少一个 = 有人改了文件名（用户设置/登录态/收藏会全部读不到）；" +
                "多一个 = 新增了一份 prefs，请在上面的冻结清单里显式登记。",
            frozen,
            actual,
        )
    }

    // ─────────────────────────────────────────── 3. 桌面小组件

    /**
     * ★ `NcrustWidgetProvider` 的**全限定类名**必须与 manifest 里注册的一致，
     * 且类名本身不改。
     *
     * launcher 按 `ComponentName` 记录「桌面上这个组件是谁」。改了类名，
     * 用户桌面上已放置的小组件会变成「无法加载小组件」——
     * 而这是一个**纯装饰性**的改名收益换来的真实功能损失。
     */
    @Test
    fun `桌面小组件的类名与 manifest 注册一致且不改`() {
        val manifest = source("src/main/AndroidManifest.xml")
        assertTrue(
            "manifest 里的 AppWidgetProvider 注册变了 —— 桌面已放置的小组件会失效。",
            manifest.contains(""".ui.widget.NcrustWidgetProvider"""),
        )
        assertTrue(
            "类文件不见了或改了名（见上一条的后果）。",
            File("src/main/java/com/takahashirinta/ncrust/ui/widget/NcrustWidgetProvider.kt").isFile,
        )
    }

    /** Application 类名同理（manifest 注册项，且无改名收益）。 */
    @Test
    fun `Application 类名与 manifest 注册一致`() {
        assertTrue(
            "manifest 里的 Application 注册变了。",
            source("src/main/AndroidManifest.xml").contains(""".NcrustApplication"""),
        )
    }

    // ─────────────────────────────────────────── 4. 改名**确实发生了**

    /**
     * 反向断言：**用户可见的品牌名必须已经是 Arris**。
     *
     * 只钉「不能改」的护栏是不够的 —— 它挡不住「改到一半」。这一条钉住
     * 「该改的真的改了」，两边合起来才是完整的改名。
     */
    @Test
    fun `用户可见的品牌名已经是 Arris 且不再有旧名`() {
        val strings = source("src/main/res/values/strings.xml")
        assertTrue("<string name=\"app_name\"> 必须是 Arris", strings.contains(">Arris<"))
        assertTrue("app_name 里不该再有旧品牌名", !strings.contains(">Ncrust<"))

        // 8 个语言文件里都不该再有旧品牌名（v3.4.8 一次性全量核对过）
        val i18n = File("src/main/java/com/takahashirinta/ncrust/ui/i18n")
        val offenders = i18n.listFiles { f -> f.extension == "kt" }
            .orEmpty()
            // ★ 只看**字符串字面量**里的旧品牌名。
            //   直接 `contains("Ncrust")` 会误伤 `NcrustWidgetProvider` 这类类名引用
            //   （它们在 KDoc 里，改不得 —— 见本类文档的表格）。
            .filter { LiteralBrand.containsMatchIn(it.readText()) }
            .map { it.name }
        assertTrue(
            "这些 i18n 文件里还留着旧品牌名：$offenders " +
                "（注意：`NcrustWidgetProvider` 这类**类名**不算，见本类文档）",
            offenders.isEmpty(),
        )
    }

    /**
     * 启动窗口底色必须与图标底色**解耦**。
     *
     * 两者曾经是同一个 `@color/ic_launcher_background`，于是「把图标换成深色」
     * 会连带把 OLED 纯黑的冷启动底改成别的颜色、冷启动闪一下。
     */
    @Test
    fun `启动窗口底色与图标底色已解耦`() {
        val colors = source("src/main/res/values/colors.xml")
        assertTrue("colors.xml 必须有独立的 window_background", colors.contains("name=\"window_background\""))
        assertTrue("colors.xml 必须有 ic_launcher_background", colors.contains("name=\"ic_launcher_background\""))
        listOf("src/main/res/values/themes.xml", "src/main/res/values-night/themes.xml").forEach { p ->
            val t = source(p)
            assertTrue(
                "$p 的 windowBackground 必须指向 @color/window_background —— " +
                    "指向 ic_launcher_background 会让改图标底色时连带改掉启动底色。",
                t.contains("""android:windowBackground">@color/window_background<"""),
            )
        }
    }

    /**
     * 自适应图标必须**同时**有 `anydpi-v26` 与至少一个密度桶。
     *
     * 仓库此前只有 `mipmap-anydpi-v26/ic_launcher.xml`，而 `minSdk = 24` ——
     * 于是 **Android 7.0/7.1 上 launcher 解析不到图标，显示的是系统默认图标**
     * （实测 `aapt2 dump resources` 只有一个 `(anydpi-v26)` 配置）。
     */
    @Test
    fun `自适应图标与 API 24-25 的密度桶回落同时存在`() {
        assertTrue(
            "缺 mipmap-anydpi-v26/ic_launcher.xml —— API 26+ 没有自适应图标。",
            File("src/main/res/mipmap-anydpi-v26/ic_launcher.xml").isFile,
        )
        val buckets = listOf("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")
        val missing = buckets.filter { !File("src/main/res/mipmap-$it/ic_launcher.png").isFile }
        assertTrue(
            "缺这些密度桶的 PNG 回落：$missing —— 没有它们，Android 7.0/7.1（API 24/25）" +
                "上 launcher 拿不到图标、显示系统默认图标。",
            missing.isEmpty(),
        )
        assertTrue(
            "自适应图标缺 <monochrome> 单色层 —— Android 13+ 用户开「主题图标」时" +
                "会看到一张系统给的灰底占位图。",
            source("src/main/res/mipmap-anydpi-v26/ic_launcher.xml").contains("<monochrome"),
        )
    }
}
