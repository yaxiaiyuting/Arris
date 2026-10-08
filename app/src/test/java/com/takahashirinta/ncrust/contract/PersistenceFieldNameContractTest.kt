/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.5.5 · A：**持久化结构字段名的防复发机制**（AGENTS.md 新增铁律 18）。
 */

package com.takahashirinta.ncrust.contract

import com.google.gson.annotations.SerializedName
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「任何持久化到磁盘的结构，字段名必须显式声明，不得依赖 R8 后的类结构。」
 *
 * ## 为什么需要它（不是一个假想的风险）
 *
 * 本仓库到 v2.5.4 为止已经取到**四次**真机证据，v2.5.5 的探针又补了**两次**：
 *
 * | # | 落点 | 落盘形状 | 发现版本 | 处置 |
 * |---|---|---|---|---|
 * | 1 | `local.LocalPlaylistDto` 等 | `{"a":"netease","b":"…"}` | v2.3.0 | `-keep class …local.**` |
 * | 2 | `playlist.**`（泛型签名丢失） | `LinkedTreeMap` | v2.2.0 | 改 TypeToken + `-keep` |
 * | 3 | `crosssource.EnvelopeDto` 等 | 单字母 | v2.4.0 | `-keep class …crosssource.**` |
 * | 4 | `library.SearchHistoryCodec$EntryDto` | `{"a":…,"b":…}` | v2.5.4 | `@SerializedName` + 三形状读 |
 * | 5 | `cache.OfflineTrack` | `{"a":…,"b":…,"i":…}` | **v2.5.5 探针** | `@SerializedName` + 三形状读 |
 * | 6 | `player.PlaybackStateManager$PositionEntry` | `{"a":…,"b":…}` | **v2.5.5 探针** | `@SerializedName` + 三形状读 |
 * | 7 | `library.AlbumInfo` | `{"a":…,"e":…}` | **v2.5.5 探针** | `@SerializedName` + 三形状读 |
 *
 * 共同形状：**同一 APK 内读写自洽 ⇒ 不崩**，只在下一次混淆映射变化时静默丢数据。
 * 所以「靠 code review 发现」是无效的 —— 上一版就是这么漏掉最后两个的。
 *
 * ## 这个测试做三件事（三条互补的防线）
 *
 * 1. **DTO 注册表**：逐个加载已知的持久化 DTO 类，断言
 *    ① 每个声明字段都有 `@SerializedName`；② 注解值**不是单字母**（单字母 = 混淆形状）；
 * 2. **keep 白名单**：对走「加 `-keep`」路线的包（`local`/`crosssource`/`playlist`/`network`/`lyric`），
 *    断言 `app/proguard-rules.pro` 里**确实有**那条规则 —— 删规则会红，而不是等下一次发布；
 * 3. **源码扫描**：扫遍 `app/src/main/java`，任何 `@SerializedName("x")`（单字母注解值）都直接失败。
 *    这一条防的是「有人为了对齐老数据，把 `@SerializedName` 也写成单字母」——
 *    那是把 bug 固化进契约，比不加注解更糟。
 *
 * ## 它**不能**证明什么（如实写在用例注释里）
 *
 * - 不能证明 release 产物里的注解真的进了 dex（那是 `proguard-rules.pro` 的
 *   `-keepclassmembers … @SerializedName <fields>;` 负责的，探针用 apkanalyzer 反汇编
 *   验证过一次）；本测试只能证明**源码契约**在，且 keep 规则没被删；
 * - 不能覆盖将来新增的、不在注册表里的 DTO。**加新 DTO 必须同时加进 [PERSISTED_DTOS]**——
 *   这条纪律写在 `AGENTS.md` 的新规则里，也写在下面这张表的注释上。
 */
class PersistenceFieldNameContractTest {

    /**
     * 持久化 DTO 注册表 —— **全部通过 `@SerializedName` 固定字段名**。
     *
     * ⚠️ 新增任何落盘 DTO 时**必须**在这里加一行，否则本测试对它一无所知。
     * 表里为空 = 这条防线失效，所以另有一条用例断言表不为空。
     */
    private val PERSISTED_DTOS = listOf(
        // v2.5.4 · B：搜索历史（`search_history`）
        "com.takahashirinta.ncrust.library.SearchHistoryCodec\$EntryDto",
        // v2.5.5 · A：离线曲目索引（`ncrust_offline` / `tracks`）
        "com.takahashirinta.ncrust.cache.OfflineTrackCodec\$TrackDto",
        // v2.5.5 · A：续播进度（`ncrust_playback_state` / `song_positions`）
        "com.takahashirinta.ncrust.player.PlaybackPositionCodec\$PositionDto",
        // v2.5.5 · A：收藏专辑（`ncrust_library` / `saved_albums`）
        "com.takahashirinta.ncrust.library.SavedAlbumCodec\$AlbumDto",
        // v2.6.0 · P0：收藏库曲目（`ncrust_library` / `saved_songs`）。
        // 注意 v2.6.0 之前这里落的是**裸 `SongItem` 数组**（没有 origin / tombstone），
        // 所以 `SavedSongCodec` 除了这层信封之外**还必须认那种老形状** ——
        // 见它的 KDoc 与 `SavedSongCodecTest` 里的 S6 真机样本（147 条 / 只有
        // `al ar dt id name` 五个 key）。注册表只能覆盖信封，覆盖不了那条老形状。
        "com.takahashirinta.ncrust.library.SavedSongCodec\$SavedSongDto",
        // v2.5.4 · C：QQ 兜底统计（`ncrust_qq_probe` / `stats`）
        "com.takahashirinta.ncrust.qq.QqFallbackCounters",
        // v2.5.5 · B：跨源上报闸门计数（`ncrust_report_gate` / `stats`）
        "com.takahashirinta.ncrust.player.ReportGateCounters",
        // v2.5.5 · A：续播记录本体（写路径直接序列化它）
        "com.takahashirinta.ncrust.player.PlaybackStateManager\$PositionEntry",
        // v2.6.1 · P0：曲目里的艺人。它**嵌在 `SongItem.artists` 里**落盘（5 张表：
        // `ncrust_library` / `ncrust_playback_state` / `ncrust_home_cache` /
        // `ncrust_local_playlists` / `ncrust_qq_playlists`），所以它自己的字段名
        // 必须进注册表 —— v2.6.1 给它加了 `mid`（QQ 的 `singerMID`），
        // 这个名字一旦被 R8 改掉，QQ 曲目的「转到歌手」会静默退化成跳搜索。
        "com.takahashirinta.ncrust.network.model.ArtistItem",
        // v2.6.2 · P0：曲目里的专辑。与 ArtistItem 同一处境 —— 它**嵌在 `SongItem.album`
        // 里**落盘（同样那 5 张表），所以它自己的字段名也必须进注册表：
        // v2.6.2 给它加了 `mid`（QQ 的 `albumMID`），这个名字一旦被 R8 改掉，
        // QQ 曲目的「转到专辑」会静默退化成跳搜索，而**页面上完全看不出是字段丢了**。
        // （`network.model.**` 有 `-keep` 兜底，但注册表是**显式**契约：
        //  keep 规则被误删时这里会红，而不是等下一次发布后由用户的数据发现。）
        "com.takahashirinta.ncrust.network.model.AlbumItem",
        // v3.3.0 · 需求 10：播放统计（`ncrust_stats`）。
        // 这张表比其它几张小得多（聚合计数器 ≈80 KB，与听多少年无关），但**语义上更需要**
        // 这份契约：它是**跨年累积**的数据，字段名一旦被 R8 改成单字母，
        // 后果不是崩溃而是「用户几百小时的收听记录在某次升级后静默清零」——
        // `StatsCodec` 的容错解码会把认不出的字段当成缺失、按默认值处理，
        // 所以现场连一行错误日志都不会有。这正是注册表存在的理由（v2.5.5 的教训）。
        "com.takahashirinta.ncrust.stats.StatsCodec\$SnapshotDto",
        "com.takahashirinta.ncrust.stats.StatsCodec\$SongDto",
        // v3.4.9：qm 登录态的续期凭证（`ncrust_qq_prefs` / `qq_refresh_credential`）。
        // 这张表**尤其**需要这份契约：它是「票据到期后能不能静默换新票」的唯一依据，
        // 而字段名一旦被 R8 改成单字母，`refreshKey` 会读成 null ⇒
        // `isRefreshable()` 为 false ⇒ **静默退化成「必须重新登录」**，
        // 也就是用户报的那个 bug 原样复发，且现场一行错误日志都不会有。
        "com.takahashirinta.ncrust.qq.QqRefreshCredential",
    )

    /**
     * 走「`-keep class …<pkg>.**`」路线的包 —— 它们的字段名由 keep 规则固定，
     * 而不是由 `@SerializedName`。第二条防线（keep 白名单）覆盖它们。
     */
    private val KEEP_GUARANTEED_PACKAGES = listOf(
        "com.takahashirinta.ncrust.local",
        "com.takahashirinta.ncrust.crosssource",
        "com.takahashirinta.ncrust.playlist",
        "com.takahashirinta.ncrust.network",
        "com.takahashirinta.ncrust.lyric",
    )

    // ------------------------------------------------------------ 1. DTO 注册表

    @Test
    fun `注册表不为空`() {
        // 空注册表 = 这条防线静默失效。用一个下界把它钉住。
        assertTrue("持久化 DTO 注册表为空", PERSISTED_DTOS.size >= 7)
        assertEquals(PERSISTED_DTOS.size, PERSISTED_DTOS.distinct().size)
    }

    /** ① 每个声明字段都有 `@SerializedName`。**这是本文件最重要的一条。** */
    @Test
    fun `每个持久化 DTO 的每个字段都显式声明了落盘名字`() {
        val missing = mutableListOf<String>()
        for (name in PERSISTED_DTOS) {
            val clazz = Class.forName(name)
            val fields = clazz.declaredFields.filter {
                // 跳过 Kotlin 合成物（Companion / 静态标记）
                !it.isSynthetic && !java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                    // v3.3.0：再按**名字**跳过编译器管理的字段。
                    //
                    // 起因：`StatsCodec$SnapshotDto` 上出现了 `$stable` —— 它是
                    // Compose 编译器为「稳定性推断」插入的字段，**不是** synthetic
                    // （`isSynthetic == false`），所以上面那道过滤拦不住它，
                    // 而这轮它把两处 DTO 判成了「缺 @SerializedName」。
                    //
                    // 它是编译器产物，**不可能也不该**加 `@SerializedName`：
                    // 加了反而会在运行时与编译器的插入逻辑打架。
                    // 过滤条件写成「以 `$` 开头」而不是「等于 `$stable`」——
                    // 将来编译器再插别的 `$` 前缀字段（`$cachedSerializer` 之类）时，
                    // 这条守卫不会第二次误报。业务字段不允许以 `$` 开头（Kotlin 语法也不允许），
                    // 所以这个过滤**不会漏掉任何真实字段**。
                    !it.name.startsWith("$")
            }
            assertTrue("$name 一个实例字段都没有？反射路径可能失效了", fields.isNotEmpty())
            for (f in fields) {
                if (f.getAnnotation(SerializedName::class.java) == null) {
                    missing += "$name.${f.name}"
                }
            }
        }
        assertTrue(
            "以下持久化字段没有 @SerializedName —— 它们的落盘 key 会由 R8 决定，" +
                "下一次混淆映射变化时用户的数据静默消失（AGENTS.md 铁律 18）：\n" +
                missing.joinToString("\n"),
            missing.isEmpty(),
        )
    }

    /** ② 注解值不许是单字母（单字母就是被混淆过的形状，写成那样等于把 bug 固化）。 */
    @Test
    fun `持久化 DTO 的落盘名字不是单字母`() {
        val bad = mutableListOf<String>()
        for (name in PERSISTED_DTOS) {
            for (f in Class.forName(name).declaredFields) {
                val ann = f.getAnnotation(SerializedName::class.java) ?: continue
                val value = ann.value
                assertTrue("$name.${f.name} 的 @SerializedName 为空", value.isNotEmpty())
                if (value.length < 2) bad += "$name.${f.name} -> \"$value\""
            }
        }
        assertTrue(
            "以下 @SerializedName 的值是单字母 —— 那是 R8 混淆出来的形状，" +
                "把它写进契约等于把老 bug 固化：\n${bad.joinToString("\n")}",
            bad.isEmpty(),
        )
    }

    /** ③ 注解值与 `*Codec.stableKeys()` 一致（两处各写一份必然分叉）。 */
    @Test
    fun `注解值与各 codec 的 stableKeys 一致`() {
        assertSameKeys(
            "com.takahashirinta.ncrust.cache.OfflineTrackCodec\$TrackDto",
            com.takahashirinta.ncrust.cache.OfflineTrackCodec.stableKeys(),
        )
        assertSameKeys(
            "com.takahashirinta.ncrust.player.PlaybackPositionCodec\$PositionDto",
            com.takahashirinta.ncrust.player.PlaybackPositionCodec.stableKeys(),
        )
        assertSameKeys(
            "com.takahashirinta.ncrust.library.SavedAlbumCodec\$AlbumDto",
            com.takahashirinta.ncrust.library.SavedAlbumCodec.stableKeys(),
        )
        // v2.6.0 · P0：收藏库曲目信封。三处（DTO 注解 / codec 的 STABLE_KEYS /
        // 注册表）各写一份必然分叉 —— 这条把它们钉在一起。
        assertSameKeys(
            "com.takahashirinta.ncrust.library.SavedSongCodec\$SavedSongDto",
            com.takahashirinta.ncrust.library.SavedSongCodec.stableKeys(),
        )
    }

    private fun assertSameKeys(className: String, stableKeys: List<String>) {
        val annotated = Class.forName(className).declaredFields
            .mapNotNull { it.getAnnotation(SerializedName::class.java)?.value }
        assertEquals(
            "$className 的 @SerializedName 与 stableKeys() 不一致 —— 两处各写一份会分叉",
            stableKeys.sorted(),
            annotated.sorted(),
        )
    }

    // ------------------------------------------------------------ 2. keep 白名单

    /** 走 keep 路线的包，规则必须还在。删一条就会红。 */
    @Test
    fun `keep 白名单里的包在 proguard-rules 里仍然有 keep class 规则`() {
        val rules = readProguardRules()
        val missing = KEEP_GUARANTEED_PACKAGES.filterNot { pkg ->
            rules.contains("-keep class $pkg.** { *; }")
        }
        assertTrue(
            "以下包的 `-keep class <pkg>.** { *; }` 规则不见了 —— 它们的持久化 DTO " +
                "没有 @SerializedName，字段名完全依赖这条规则：\n${missing.joinToString("\n")}",
            missing.isEmpty(),
        )
    }

    /**
     * `cache.**` **不许**加 `-keep class`。
     *
     * 这是 v2.5.5 探针给的一条**反向**约束：`cache.OfflineTrack` 已经落盘的形状是
     * `a`~`i`，加 keep 只影响将来写出来的形状、对老数据毫无用处；
     * 而它会**改掉全局混淆映射**，可能把「静态丢失」从 `cache.**` 换到别的类上
     * （`AGENTS.md` v2.5.4 规则 1 第二条）。老数据只能靠迁移逻辑救。
     */
    @Test
    fun `cache 包不许加 keep class 规则`() {
        val rules = readProguardRules()
        assertTrue(
            "proguard-rules.pro 里出现了 `-keep class com.takahashirinta.ncrust.cache.**` —— " +
                "它会改掉全局混淆映射，救不了已落盘的 a~i，只会把风险换到别的类上。" +
                "cache 的字段名契约走 @SerializedName + OfflineTrackCodec。",
            !rules.contains("-keep class com.takahashirinta.ncrust.cache.** { *; }"),
        )
    }

    /** Gson 注解必须真的进 dex —— 这条全局规则是上面所有 `@SerializedName` 生效的前提。 */
    @Test
    fun `proguard-rules 保留了 SerializedName 字段的注解规则`() {
        val rules = readProguardRules()
        assertTrue(
            "proguard-rules.pro 缺少 `-keepclassmembers,allowobfuscation class * { @SerializedName <fields>; }` —— " +
                "没有它，@SerializedName 的字符串常量可能进不了 dex，所有 DTO 的契约一起失效。",
            rules.contains("@com.google.gson.annotations.SerializedName <fields>;"),
        )
        assertTrue(
            "proguard-rules.pro 缺少 `-keepattributes *Annotation*` —— 注解本身会被 R8 丢掉。",
            rules.contains("-keepattributes *Annotation*"),
        )
    }

    // ------------------------------------------------------------ 3. 源码扫描

    /**
     * 扫遍 `app/src/main/java`，任何 `@SerializedName("<单字母>")` 都是失败。
     *
     * 与第 ①②③ 条的关系：那三条只看**注册表里**的类；这一条看**全部源码**，
     * 因此能抓到「新加了一个 DTO、忘了进注册表、而且注解值写成了单字母」这种组合。
     */
    @Test
    fun `全仓源码里没有单字母的 SerializedName`() {
        val root = appSourceRoot()
        val pattern = Regex("""@SerializedName\(\s*"([^"]*)"\s*\)""")
        val offenders = mutableListOf<String>()
        var total = 0
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val text = file.readText()
            for (m in pattern.findAll(text)) {
                total++
                val value = m.groupValues[1]
                if (value.length < 2) {
                    offenders += "${file.relativeTo(root)}: @SerializedName(\"$value\")"
                }
            }
        }
        assertTrue("源码里一个 @SerializedName 都没扫到 —— 扫描路径可能错了：$root", total > 0)
        assertTrue(
            "以下 @SerializedName 的值是单字母：\n${offenders.joinToString("\n")}",
            offenders.isEmpty(),
        )
    }

    /**
     * 反证：扫描器**真的在扫源码**。
     *
     * 一条「什么都没扫到也算通过」的用例是假防线。这里断言扫到的注解数量
     * **不少于注册表里字段的总数** —— 若扫描路径错了、或注解被删了，这条会红。
     */
    @Test
    fun `源码扫描确实扫到了注解`() {
        val root = appSourceRoot()
        val pattern = Regex("""@SerializedName\(\s*"([^"]*)"\s*\)""")
        var total = 0
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            total += pattern.findAll(file.readText()).count()
        }
        val expectedFields = PERSISTED_DTOS.sumOf { name ->
            Class.forName(name).declaredFields.count { it.getAnnotation(SerializedName::class.java) != null }
        }
        assertTrue(
            "源码里扫到 $total 个 @SerializedName，少于注册表里的 $expectedFields 个字段 —— " +
                "扫描路径不对，或有注解被删了",
            total >= expectedFields,
        )
    }

    /** 扫描到的必须是**持久化包**里的注解 —— 防止扫描器误命中测试或无关目录。 */
    @Test
    fun `源码扫描的路径指向 app 主源码集`() {
        val root = appSourceRoot()
        assertTrue("源码根不存在：$root", root.isDirectory)
        assertTrue(
            "源码根不是 app/src/main/java：$root",
            root.path.replace('\\', '/').endsWith("app/src/main/java"),
        )
        assertNotNull(root.listFiles())
    }

    // ------------------------------------------------------------ 工具

    /**
     * 定位 `app/src/main/java`。
     *
     * Gradle 的 JVM 单测工作目录是**模块目录**（`…/Ncrust/app`），但不同 AGP 版本
     * 与 IDE 运行方式下可能是仓库根。所以从 `user.dir` **逐级向上**找，
     * 找到第一个含 `app/src/main/java` 或本身就是 `…/app` 的目录。
     * 找不到就**直接失败**（不许静默跳过 —— 那是假防线）。
     */
    private fun appSourceRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "app/src/main/java")
            if (candidate.isDirectory) return candidate
            if (dir.name == "app" && File(dir, "src/main/java").isDirectory) {
                return File(dir, "src/main/java")
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "找不到 app/src/main/java（user.dir=${System.getProperty("user.dir")}）—— " +
                "源码扫描防线失效，必须修好而不是跳过。",
        )
    }

    /**
     * 读 `app/proguard-rules.pro`。从源码根逐级向上找，**找不到直接失败**
     * （静默跳过 = 假防线，keep 规则被删就没人发现了）。
     */
    private fun readProguardRules(): String {
        var dir: File? = appSourceRoot()
        while (dir != null) {
            val candidate = File(dir, "proguard-rules.pro")
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        throw AssertionError(
            "找不到 app/proguard-rules.pro（从 ${appSourceRoot()} 逐级向上都没找到）—— " +
                "keep 白名单防线失效，必须修好而不是跳过。",
        )
    }
}
