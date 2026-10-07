/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.4.8：**进程内镜像的播种点**守卫（源码形状，与 BiliApiIoContractTest 同款）。
 */

package com.takahashirinta.ncrust.bili

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.4.8：B 站的**四个进程内镜像**必须在**两个进程入口**都播种。
 *
 * ## 为什么这是一条真实的缺陷，不是洁癖
 *
 * `BiliPrefs` / `BiliAuthStore` 是「拿不到 `Context` 的调用点读的进程内镜像」
 * （服务定位器模式）。它们的播种点原先只有 `MainActivity.onCreate` ——
 * 而 `PlaybackService` 是**除 Activity 之外唯一的进程入口**：
 * Android Auto / 车机绑定、媒体按钮、通知栏恢复都能在**没有 Activity** 的情况下
 * 把进程拉起来。那时三个镜像全在默认值上：
 *
 * | 镜像 | 未播种时的表现 |
 * |---|---|
 * | `BiliPrefs.isEnabled` | 恒 false ⇒ **B 站曲目直接返回 null、整首跳过** |
 * | `BiliPrefs.qualityCap` | 恒 auto ⇒ 用户设的「仅 192K 省流」失效 |
 * | `BiliPrefs.preferFlac` | 恒 true ⇒ 省流用户拿到 2~3 Mbps 的 FLAC |
 * | `BiliAuthStore` | 恒匿名 ⇒ **大会员也拿不到 Hi-Res**（本版修复在车机上完全不生效） |
 *
 * 也就是说：**本次修复的可见效果会随进程是被谁拉起来的而变化** ——
 * 这正是「同一个前提在两条链路上不能有两种写法」那条纪律要防的形状。
 *
 * ## 为什么用源码扫描而不是行为测试
 *
 * 「服务被系统单独拉起」在 JVM 单测里造不出来（需要真实 Service 生命周期 +
 * 真实 Android Auto 绑定）。而这里要守的东西是**离散的、可枚举的**：
 * 「某两个文件里有没有出现这两行」。行为测不了的东西留给真机 ⇒ 那这条就永远没人守；
 * 所以退一步守**形状**，与 `BiliApiIoContractTest` 守「`withContext(Dispatchers.IO)`」
 * 是同一个取舍，也如实承认它**不能**证明那两行真的被执行到。
 */
class BiliMirrorSeedingTest {

    private fun source(relative: String): String {
        // 单测的工作目录是模块目录（app/），与 `BiliApiIoContractTest` 同一读法。
        val f = File("src/main/java/com/takahashirinta/ncrust/$relative")
        assertTrue("源文件不存在（工作目录变了？）：${f.absolutePath}", f.isFile)
        return f.readText()
    }

    @Test
    fun `MainActivity 播种全部四个 B 站镜像`() {
        val src = source("MainActivity.kt")
        listOf(
            "BiliPrefs.init(this)",
            "BiliAuthStore.init(this)",
            "initLanguageMirror(this)",
        ).forEach { call ->
            assertTrue("MainActivity 必须在冷启动时调用 `$call`", src.contains(call))
        }
    }

    /**
     * ★ `PlaybackService` 也要播种 —— 这是本版**新增**的那一半。
     *
     * 漏掉它，Android Auto / 媒体按钮路径上的 B 站行为会与 App 内不一致，
     * 而「不一致」的表现恰好是本次用户报的那个：「无论如何都播放的是普通版本」
     * （车机路径恒匿名 ⇒ 恒拿不到 Hi-Res）。
     */
    @Test
    fun `PlaybackService 也要播种 B 站镜像与登录态`() {
        val src = source("player/PlaybackService.kt")
        listOf(
            "BiliPrefs.init(this)",
            "BiliAuthStore.init(this)",
        ).forEach { call ->
            assertTrue(
                "PlaybackService.onCreate 必须调用 `$call` —— 否则「服务被系统单独拉起」时" +
                    "B 站镜像停在默认值上（源关着 / 省流上限失效 / 恒匿名拿不到 Hi-Res）",
                src.contains(call),
            )
        }
    }

    /**
     * 播种必须发生在 **`onCreate` 里、且在任何取链之前**。
     *
     * 只断言「文件里有这两行」不够：把它们放在文件末尾的某个私有函数里，
     * 本条测试仍然会过，而服务被拉起时它们从没执行。
     * 判据用「`onCreate` 的函数体里出现」——`onCreate` 到下一个 `override fun` 之间。
     */
    @Test
    fun `播种发生在 onCreate 的函数体里而不是别处`() {
        val src = source("player/PlaybackService.kt")
        val start = src.indexOf("override fun onCreate()")
        assertTrue("找不到 onCreate（改名了？请同步本测试）", start >= 0)
        val end = src.indexOf("override fun ", start + 1).let { if (it < 0) src.length else it }
        val body = src.substring(start, end)
        assertTrue("BiliPrefs.init 必须在 onCreate 体内", body.contains("BiliPrefs.init(this)"))
        assertTrue("BiliAuthStore.init 必须在 onCreate 体内", body.contains("BiliAuthStore.init(this)"))
    }
}
