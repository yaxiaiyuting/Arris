/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v4.2.0：后台播放保护的**厂商适配表 + 纯函数映射**。
 */

package com.takahashirinta.ncrust.power

import com.takahashirinta.ncrust.ui.i18n.BackgroundGuideStrings

/**
 * 厂商定制系统的配置档。
 *
 * [id] 是**稳定标识**（只用于日志/单测，不进 UI）；[displayName] 直接进文案模板
 * （`BackgroundGuideStrings.oemSection` 的参数），因此必须是厂牌自己的写法
 * —— 翻译成「小米」会让刷了国际版 ROM 的用户对不上自己屏幕上的名字。
 */
enum class OemProfile(val id: String, val displayName: String) {
    /** OPPO / OnePlus / realme：ColorOS（含 ColorOS for OnePlus 与 realme UI）。 */
    COLOR_OS("coloros", "ColorOS"),

    /** 小米 / Redmi / POCO：HyperOS 与 MIUI 共用同一套电源管理入口。 */
    MIUI_HYPER_OS("miui", "HyperOS / MIUI"),

    /** vivo / iQOO：OriginOS 与 FuntouchOS。 */
    ORIGIN_OS("originos", "OriginOS / FuntouchOS"),

    /** 华为 / 荣耀：HarmonyOS、EMUI、MagicOS。 */
    HARMONY_EMUI("emui", "HarmonyOS / EMUI / MagicOS"),

    /** 魅族：Flyme。 */
    FLYME("flyme", "Flyme"),

    /** 三星：One UI（没有半导体厂商的「自启动」概念，只有电池后台限制）。 */
    ONE_UI("oneui", "One UI"),

    /** 未识别：AOSP 或没适配过的 ROM，只给通用路径 + 系统兜底页。 */
    OTHER("other", "Android"),
}

/**
 * 一条**深链候选**的描述（包名 + Activity + 可选 action / extra），**不是** [android.content.Intent]。
 *
 * ## 为什么表里存描述而不是 Intent
 *
 * 本仓库的单测是纯 JVM（没有 Robolectric，`app/build.gradle.kts` 只有 junit / json），
 * 在单测里 `Intent()` 会抛 `RuntimeException: Method … not mocked`。
 * 表里存纯数据，映射与候选表就能在**本机直接跑单测**；
 * 把描述翻成 Intent 的那一步落在 [OemBackgroundActions]（Android 侧，靠真机验证）。
 *
 * 另一层好处是这张表可以被**机械审阅**：`OemBackgroundGuideTest` 会断言
 * 每一档的候选数、包名/类名的形状与去重 —— 这是「厂商字符串 → 档位 → 候选深链」整条链路里
 * 唯一能在 CI 上钉死的部分。
 */
data class OemDeepLink(
    /** 目标包名，逐字照抄自厂商 ROM（写错只是这一条失败，会走下一个候选）。 */
    val packageName: String,
    /** 目标 Activity 的完整类名；空串表示只按 [action] 拉起。 */
    val className: String = "",
    /** 少数入口只能按 action 拉起。 */
    val action: String? = null,
    /**
     * 需要额外塞进 Intent 的**包名参数键**。
     *
     * HyperOS/MIUI 的 `HiddenAppsConfigActivity` 正是靠 `package_name` 直接定位到本应用那一行，
     * 不带这个 extra 会停在列表页（可用，但要多点一次）。
     */
    val extraPackageName: String? = null,
    /**
     * 是否把 `package:<自己的包名>` 塞进 Intent 的 data。
     *
     * 系统授权对话框（`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`）与应用详情页都靠这个 data 定位到本应用；
     * 不带 data 时 ColorOS 上直接 `No activity found`。
     */
    val dataPackage: Boolean = false,
)

/**
 * 一档 OEM 的完整引导：三步说明 + 最近任务锁定的表述 + 深链候选。
 *
 * [steps] / [recents] 是**已解析的文案**（由 [OemBackgroundGuide.guideOf] 从
 * [BackgroundGuideStrings] 取），而不是写死在表里的中文字符串 —— 文案的唯一真源是
 * `ui/i18n` 下的 8 份语言文件，表里再存一份就等于给自己造一个不同步的副本。
 */
data class OemGuide(
    val profile: OemProfile,
    /** 恰好三步（单测逐语言断言），UI 按序号 1/2/3 渲染。 */
    val steps: List<String>,
    /** 该机型「最近任务卡片怎么锁」的具体手势。 */
    val recents: String,
    /** 深链候选，按顺序尝试；空表示只能用系统兜底页。 */
    val links: List<OemDeepLink>,
)

/**
 * 厂商适配表与识别逻辑。
 *
 * ## 识别规则（纯函数，可单测）
 *
 * 取 `Build.MANUFACTURER` / `Build.BRAND` / `Build.DISPLAY` 三个字段，**小写后做包含匹配**，
 * 按 [DETECTION_TOKENS] 的声明顺序取第一个命中 —— 顺序有意义：
 *
 *  - `oneplus` / `realme` / `oplus` 都跑 ColorOS，但它们的 `Build.BRAND` 里
 *    **不含** `oppo`，所以必须单列；
 *  - `honor`（MagicOS）与 `huawei` 共用启动管理入口，归同一档，但 `honor` 的
 *    `Build.MANUFACTURER` 已经不再写 `huawei`，同样必须单列；
 *  - `iqoo` 的 `Build.BRAND` 是 `iQOO`、`Build.MANUFACTURER` 是 `vivo`，两者都在表里；
 *  - `Build.DISPLAY` 参与匹配是为了**刷机场景**：一加刷了 ColorOS、小米装了类原生 ROM
 *    时，品牌字段可能已被改写，而 DISPLAY 里通常仍带 ROM 名。
 *
 * 未命中任何 token（Pixel / 摩托 / 类原生）⇒ [OemProfile.OTHER]，只给通用路径。
 */
object OemBackgroundGuide {

    /**
     * 识别 token 表：`档位 → 小写 token`，**顺序即优先级**。
     *
     * token 全部小写、且只能是 `lowercase()` 之后仍等于自身的串（单测断言），
     * 否则匹配会因大小写而漏。
     */
    val DETECTION_TOKENS: List<Pair<OemProfile, List<String>>> = listOf(
        OemProfile.COLOR_OS to listOf("oppo", "oneplus", "realme", "oplus", "coloros"),
        OemProfile.MIUI_HYPER_OS to listOf("xiaomi", "redmi", "poco", "miui", "hyperos"),
        OemProfile.ORIGIN_OS to listOf("vivo", "iqoo", "originos", "funtouch"),
        OemProfile.HARMONY_EMUI to listOf("huawei", "honor", "harmony", "emui", "magicos"),
        OemProfile.FLYME to listOf("meizu", "flyme"),
        OemProfile.ONE_UI to listOf("samsung", "oneui", "one ui"),
    )

    /**
     * ColorOS 系的两条候选：**只放第三方应用确实能打开的入口**。
     *
     * ColorOS 把「自启动」（`com.oplus.battery/...StartupAppListActivity`，受
     * `oplus.permission.OPLUS_COMPONENT_SAFE` 保护）与「应用耗电管理」
     * （`...PowerUsageModelActivity`，受 `com.oplus.permission.safe.SETTINGS` 保护）都设成了
     * 仅系统可访问，第三方拉起一律 `SecurityException` —— 236 版两颗按钮全部落到应用详情页就是这么来的。
     */
    private val COLOR_OS_LINKS = listOf(
        // 实测（PLC110 / ColorOS 16，2026-11-22，逐个组件验证）：
        //   「设置 → 应用」这一页（Settings$ManageApplicationsActivity / Settings$AppDashboardActivity）
        //   虽然 exported=true 且无权限，但 ColorOS 的 Settings 会把它们**重定向到设置首页**
        //   （am start 后 topResumedActivity 一直是 com.android.settings/.Settings）。
        //   「自启动」/「应用耗电管理」真正的页面（com.oplus.battery 里那两个）带签名级权限，
        //   第三方 startActivity 一律 SecurityException。
        // 因此 ColorOS 上第三方能做到的极限是：打开设置首页 + 打开应用详情页（那里有「耗电管理」）。
        OemDeepLink(
            packageName = "com.android.settings",
            action = "android.settings.SETTINGS",
        ),
        OemDeepLink(
            packageName = "com.android.settings",
            action = "android.settings.APPLICATION_DETAILS_SETTINGS",
            dataPackage = true,
        ),
    )

    /** HyperOS / MIUI：电源管理的隐藏应用列表（带 `package_name`）+ 自启动管理。 */
    private val MIUI_LINKS = listOf(
        OemDeepLink(
            packageName = "com.miui.powerkeeper",
            className = "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
            extraPackageName = "package_name",
        ),
        OemDeepLink(
            packageName = "com.miui.securitycenter",
            className = "com.miui.securitycenter.permission.AutoStartManagementActivity",
        ),
    )

    /** vivo / iQOO：老 FuntouchOS 在 `com.vivo.permissionmanager`，iQOO 分支在 `com.iqoo.secure`。 */
    private val ORIGIN_OS_LINKS = listOf(
        OemDeepLink(
            packageName = "com.vivo.permissionmanager",
            className = "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        ),
        OemDeepLink(
            packageName = "com.iqoo.secure",
            className = "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
        ),
    )

    /** 华为 / 荣耀：启动管理列表页（`hihonor` 是荣耀拆出去之后的包名）。 */
    private val HARMONY_LINKS = listOf(
        OemDeepLink(
            packageName = "com.huawei.systemmanager",
            className = "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        ),
        OemDeepLink(
            packageName = "com.hihonor.systemmanager",
            className = "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        ),
    )

    /** Flyme：只有一个后台管理入口。 */
    private val FLYME_LINKS = listOf(
        OemDeepLink(
            packageName = "com.meizu.safe",
            className = "com.meizu.safe.permission.SmartBGActivity",
        ),
    )

    /** One UI：三星没有「自启动」页，`lool`（设备维护）的电池页是唯一能改后台限制的地方。 */
    private val ONE_UI_LINKS = listOf(
        OemDeepLink(
            packageName = "com.samsung.android.lool",
            className = "com.samsung.android.lool.activity.BatteryActivity",
        ),
    )

    /**
     * 档位 → 深链候选。**每一档都必须有键**（含 [OemProfile.OTHER] 的空表）：
     * 少一个键会让 UI 在 `getValue` 处崩，单测对 `OemProfile.entries` 逐档断言。
     */
    val LINK_TABLE: Map<OemProfile, List<OemDeepLink>> = mapOf(
        OemProfile.COLOR_OS to COLOR_OS_LINKS,
        OemProfile.MIUI_HYPER_OS to MIUI_LINKS,
        OemProfile.ORIGIN_OS to ORIGIN_OS_LINKS,
        OemProfile.HARMONY_EMUI to HARMONY_LINKS,
        OemProfile.FLYME to FLYME_LINKS,
        OemProfile.ONE_UI to ONE_UI_LINKS,
        OemProfile.OTHER to emptyList(),
    )

    /**
     * 三个 Build 字段 → 配置档。**纯函数**：参数就是全部输入，不读静态字段，
     * 因此可以对任意机型字符串组合做单测（真机型号不可能穷举）。
     */
    fun profileOf(manufacturer: String?, brand: String?, display: String?): OemProfile {
        val fields = listOfNotNull(manufacturer, brand, display)
            .map { it.lowercase() }
            .filter { it.isNotBlank() }
        if (fields.isEmpty()) return OemProfile.OTHER
        return DETECTION_TOKENS.firstOrNull { (_, tokens) ->
            fields.any { field -> tokens.any { field.contains(it) } }
        }?.first ?: OemProfile.OTHER
    }

    /** 一档的深链候选；未知档位按空表处理（等价于「直接走系统兜底页」）。 */
    fun linksOf(profile: OemProfile): List<OemDeepLink> = LINK_TABLE[profile].orEmpty()

    /**
     * 组装某一档的完整引导：文案来自 8 语言文件，深链来自 [LINK_TABLE]。
     *
     * 纯函数（文案对象由调用方传入），单测可以对 8 种语言逐档断言「恰好三步」。
     */
    fun guideOf(profile: OemProfile, texts: BackgroundGuideStrings): OemGuide {
        val (steps, recents) = when (profile) {
            OemProfile.COLOR_OS -> lines(texts.stepsColorOs) to texts.recentsColorOs
            OemProfile.MIUI_HYPER_OS -> lines(texts.stepsMiui) to texts.recentsMiui
            OemProfile.ORIGIN_OS -> lines(texts.stepsOriginOs) to texts.recentsOriginOs
            OemProfile.HARMONY_EMUI -> lines(texts.stepsEmui) to texts.recentsEmui
            OemProfile.FLYME -> lines(texts.stepsFlyme) to texts.recentsFlyme
            OemProfile.ONE_UI -> lines(texts.stepsOneUi) to texts.recentsOneUi
            OemProfile.OTHER -> lines(texts.stepsOther) to texts.recentsOther
        }
        return OemGuide(profile = profile, steps = steps, recents = recents, links = linksOf(profile))
    }

    /**
     * 一条三行文案 → 三个步骤。
     *
     * 为什么文案里用 `\n` 分行而不是三条独立字段：3 步 × 7 档 × 8 语言 = 168 个字符串，
     * 而它们的差别只是分隔符；分行交给代码后译文里「哪三行」是一眼能看全的一件事。
     *
     * 空行被丢弃、每行 trim：译文多打一个空行不该让 UI 出现一个空步骤。
     */
    private fun lines(raw: String): List<String> =
        raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
}
