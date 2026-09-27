/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.1.0 · B：B 站音源的**偏好与档位映射**。纯逻辑（映射部分无 Android），JVM 可单测。
 */

package com.takahashirinta.ncrust.bili

import android.content.Context
import android.content.SharedPreferences

/**
 * B 站音频区的 `qn`（音质档位）与码率。
 *
 * ## 这是**协议常量**，不是我们的档位表
 *
 * 本应用自己的档位名是 `standard` / `higher` / `exhigh` / `lossless` / `hires` / …（8 档，
 * 见 `QualityLadder.LEVELS`）。B 站音频区只认 0~3 这四个 `qn`，且**同一个 `qn`
 * 在登不登录时的实际码率不同**（匿名多半被服务端压到 192K）。
 * 所以 [mapFromLevel] 只回答「该请求哪个 qn」，**实际拿到什么**一律以响应里的
 * `quality` / 文件后缀为准（`SongUrlResult.levelFromFile` 那条既有纪律）。
 */
enum class BiliQn(val qn: Int, val label: String, val approxKbps: Int) {
    Q128(0, "128K", 128),
    Q192(1, "192K", 192),
    Q320(2, "320K", 320),

    /** FLAC（需要大会员；匿名请求会被服务端降级）。 */
    FLAC(3, "FLAC", 0),
    ;

    companion object {
        fun of(qn: Int): BiliQn? = values().firstOrNull { it.qn == qn }
    }
}

/**
 * 本应用档位 ↔ B 站 `qn` 的映射，以及**降级阶梯**（纯逻辑，JVM 可单测）。
 *
 * ## 映射规则
 *
 * | 本应用档位 | B 站 `qn` | 理由 |
 * |---|---|---|
 * | `standard`（压缩） | 0 | 128K |
 * | `higher`（较好） | 1 | 192K |
 * | `exhigh`（更好） | 2 | 320K |
 * | `lossless`（无损） | 3 | FLAC |
 * | `hires` / `jyeffect` / `jymaster` / `dolby` | 3 | **B 站没有比 FLAC 更高的档** —— 映射到它自己的最高档，而不是「不请求」 |
 * | 未知字符串 | 3 → 逐级下探 | 与 `SongUrlFetcher` 的 `else` 分支同一取舍：先试最高、再降 |
 *
 * ## 为什么最高档是 3 而不是「匿名只能 1」
 *
 * 「匿名只能拿 192K」是**服务端的结论**，不是客户端的常量：用户登录（未来的版本）
 * 或有大会员时同一个 `qn=3` 能拿到 FLAC。把它写死成 1 等于把将来的登录能力一起锁死。
 * 拿不到时由 [fallbackLadder] 逐级下探 —— 失败处理有界（铁律 5）。
 */
object BiliQuality {

    /** 本应用档位 → 首选 `qn`。 */
    fun mapFromLevel(level: String): BiliQn = when (level) {
        "standard" -> BiliQn.Q128
        "higher" -> BiliQn.Q192
        "exhigh" -> BiliQn.Q320
        "lossless", "hires", "jyeffect", "jymaster", "dolby" -> BiliQn.FLAC
        else -> BiliQn.FLAC
    }

    /**
     * 降级阶梯：从 [mapFromLevel] 的结果逐级降到 128K，**去重、有序**。
     *
     * 与 `SongUrlFetcher.fetch` 的 `fallbackLevels` 同一条纪律：循环有界、
     * 每一档只试一次、最低档兜底。
     */
    fun fallbackLadder(level: String): List<BiliQn> {
        val start = mapFromLevel(level).qn
        return (start downTo 0).mapNotNull { BiliQn.of(it) }
    }

    /**
     * 服务端返回的 `quality` 是否**真的是**我们请求的那一档。
     *
     * 用途只有一个：把「请求 FLAC、拿到 192K」这件事**如实**标出来
     * （`SongUrlResult.levelFromFile`），而不是按请求档位自欺。
     */
    fun actualLabel(returnedQn: Int, fallback: String): String =
        BiliQn.of(returnedQn)?.label ?: fallback
}

/**
 * B 站音源的**用户开关**（v3.1.0 · 铁律 24：必须支持独立开关，用户可选择是否启用）。
 *
 * ## 默认**关闭**（与 v3.0.0 五个动效开关「默认全开」相反）
 *
 * 两者的理由正好相反：
 * - 动效开关默认全开，是因为它们只影响**本机渲染**，「默认关」会让用户以为升级没变化；
 * - B 站音源默认关闭，是因为它是一个**外部平台依赖**：要发 Wbi 签名的请求、
 *   有自己的风控与 URL 时效。默认打开等于「用户什么都没做，App 就开始给 B 站发请求」。
 *   `RECOMMENDATIONS.md`（net-research）§4 把这条定成了接入约束。
 *
 * ## 为什么是 `@Volatile` + `init`，而不是每个调用点读一次 SharedPreferences
 *
 * 与 `RetrofitClient.currentCookie` 同一套服务定位器模式：
 * - 读取发生在**组合期**（音源角标、设置页开关状态）与**每通请求之前**，
 *   每次 `getSharedPreferences` + `getBoolean` 虽然便宜但会进组合期；
 * - 写只有两个入口（`MainActivity` 冷启动、设置页开关），
 *   两边都走 [setEnabled]，盘上与内存不会漂移。
 */
object BiliPrefs {

    const val PREFS_FILE = "ncrust_settings"

    /**
     * 开关的 prefs 键。**显式声明**（铁律 17：持久化结构字段名必须显式声明），
     * 且与 `SettingsRegistry` 里的 `key` 逐字相同 —— 由
     * `BiliPrefsTest.开关的键与设置注册表逐字一致` 钉住。
     */
    const val KEY_ENABLED = "bilibili_enabled"

    /** 默认关闭。 */
    const val DEFAULT_ENABLED = false

    @Volatile
    private var enabled: Boolean = DEFAULT_ENABLED

    /** 冷启动时调用一次（`MainActivity` 与 `RetrofitClient.init` 同处）。 */
    fun init(context: Context) {
        enabled = read(context)
    }

    /** 当前是否启用。**读内存**，可以安全地在组合期调用。 */
    fun isEnabled(): Boolean = enabled

    /**
     * 设置页的唯一写入入口：**先落盘、再改内存**。
     *
     * 顺序不能反：反过来的话写盘失败（极罕见但存在）会让内存说「开着」、
     * 下次冷启动又说「关着」—— 用户看到的是「开关自己弹回去了」。
     */
    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()
        enabled = value
    }

    /**
     * 直接从盘上读（**只给初始化和单测用**）。
     *
     * 缺键 = 默认关闭。注意判据是 `getBoolean(KEY, DEFAULT_ENABLED)` ——
     * 与「键存在但为 false」在语义上等价（都是关），这是有意的：
     * 参考实现里没有「从未设置过」这个第三态。
     */
    fun read(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, DEFAULT_ENABLED)

    /**
     * 只给单测用：直接改进程内镜像（**不落盘**）。
     *
     * 存在的理由是「开关的默认值也要被单测钉住」：JVM 单测没有 `Context`，
     * 而 `BiliSourceProviderTest` 必须能证明「关着的时候一个请求都不发」。
     * 它不是生产入口 —— 唯一的生产写入入口是 [setEnabled]。
     */
    internal fun setEnabledForTest(value: Boolean) {
        enabled = value
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
}
