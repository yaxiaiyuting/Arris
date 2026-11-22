/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v4.2.0：把 [OemDeepLink] 描述翻成 Intent 并**按顺序尝试**（Android 侧）。
 */

package com.takahashirinta.ncrust.power

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 后台播放保护的跳转执行器。
 *
 * ## 为什么候选表在别处、执行在这里
 *
 * 「厂商字符串 → 档位」和「档位 → 候选深链」都是纯数据，放在 [OemBackgroundGuide] 里
 * 由纯 JVM 单测钉住；Intent 的构造与 `startActivity` 只能在真机/模拟器上验证，
 * 所以单独落在本对象。两边的契约只有 [OemDeepLink] 一个数据类。
 *
 * ## 深链为什么必须「逐个试」
 *
 * 这些 Activity 名来自各 ROM 的逆向与社区整理，**版本一变就可能改名或直接消失**
 * （ColorOS 12 → 13 就换过一次启动优化页）。硬编码任何一条都会在部分机型上
 * 变成「点了没反应」，因此：逐条尝试 → 任一条成功即止 → 全失败才退到
 * 「应用详情页」这条**一定存在**的兜底。
 *
 * ## 关于 `FLAG_ACTIVITY_NEW_TASK`
 *
 * 本页是应用内的全屏 Dialog，`Context` 是 Activity ⇒ 不需要该 flag（带上会让系统设置
 * 另起一个任务，返回时先回桌面）。但 [openStartupSettings] 是 public 的，将来若有
 * 非 Activity 调用方（服务、通知回调），不带 flag 会直接抛 `AndroidRuntimeException`，
 * 所以按 `context is Activity` 条件添加。
 */
object OemBackgroundActions {

    private const val TAG = "OemBackground"

    /** 当前设备的配置档（读 `Build.MANUFACTURER` / `BRAND` / `DISPLAY` 交给纯函数）。 */
    fun currentProfile(): OemProfile =
        OemBackgroundGuide.profileOf(Build.MANUFACTURER, Build.BRAND, Build.DISPLAY)

    /**
     * 依次尝试该档位的深链，全失败则退到应用详情页。
     *
     * @return `true` = 有界面确实被拉起（用户看到了变化）。`false` = 连兜底页都失败，
     *   调用方应给一句可见提示，而不是静默。
     */
    fun openStartupSettings(context: Context, profile: OemProfile): Boolean {
        for (link in OemBackgroundGuide.linksOf(profile)) {
            val intent = intentOf(context, link)
            if (launch(context, intent, label = link.className.ifEmpty { link.action.orEmpty() })) return true
        }
        // 兜底：应用详情页在任何 Android 上都有，用户可以从那里进「电池 / 权限」。
        return launch(
            context,
            BackgroundActivity.appDetailsIntent(context),
            label = "ACTION_APPLICATION_DETAILS_SETTINGS",
        )
    }

    /**
     * 未加入白名单时的电池优化跳转：直达授权框 → 应用详情页兜底。
     *
     * 只在「未允许」分支调用（已允许时授权框会启动即 finish、屏幕零变化，
     * 所以那种情况在 UI 上根本不显示这个按钮，只显示状态文案）。
     */
    fun openBatteryOptimizationSettings(context: Context): Boolean {
        if (launch(context, BackgroundActivity.requestIntent(context), label = "battery-optimization")) return true
        return launch(
            context,
            BackgroundActivity.appDetailsIntent(context),
            label = "ACTION_APPLICATION_DETAILS_SETTINGS",
        )
    }

    /** 纯翻译：描述 → Intent（不做 try/catch，也不启动）。 */
    fun intentOf(context: Context, link: OemDeepLink): Intent {
        val intent = Intent()
        if (link.className.isNotEmpty()) {
            intent.component = ComponentName(link.packageName, link.className)
        } else {
            intent.setPackage(link.packageName)
        }
        link.action?.let { intent.action = it }
        link.extraPackageName?.let { key -> intent.putExtra(key, context.packageName) }
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent
    }

    /**
     * 单次启动。只吞两种「预期内失败」：
     *  - [ActivityNotFoundException]：该 ROM 上没有这个 Activity（版本差异 / 非本厂商）；
     *  - [SecurityException]：Activity 存在但未导出（新 ROM 收紧权限后的常见表现）。
     * 其余异常照旧上抛 —— 那是我们自己的 bug，不该被当成「这条候选不可用」藏起来。
     */
    private fun launch(context: Context, intent: Intent, label: String): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        Log.i(TAG, "深链不可用（未找到）: $label", e)
        false
    } catch (e: SecurityException) {
        Log.i(TAG, "深链不可用（未导出）: $label", e)
        false
    }
}
