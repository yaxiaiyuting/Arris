/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.8.0「设置界面二级菜单重构」阶段 3：一级页 → 二级页的路由常量与解析（**纯逻辑**）。
 */

package com.takahashirinta.ncrust.ui.settings

/**
 * 设置二级页的路由形状与 group 参数解析。
 *
 * ## 为什么解析要抽成纯函数
 *
 * 探针 §4.3-4 的硬要求：「进程重建后 `group` 可能对不上（版本升级删了分组），
 * 解析失败必须回落到一级页并 `popBackStack()`，**不抛异常**」。
 * 这条纪律只有抽成纯函数才能被 JVM 单测逐字钉住（7 个合法 id + 若干非法 id），
 * 而不是靠「导航库里那个 `when` 看起来没问题」。
 *
 * ## 与 [SettingsGroup.id] 的关系
 *
 * 路由参数**就是** [SettingsGroup.id]（`account` / `general` / …）——
 * 刻意不复用枚举名（`ACCOUNT`）：枚举名是代码标识，改个名字不该让用户的返回栈失效；
 * 而 id 是持久化契约（进程重建后系统会把整条路由重新喂回来）。
 * 反过来说，**删分组 = 让老路由失效**，所以 [resolve] 必须容忍未知值。
 */
object SettingsGroupRoute {

    /** 路由模板（`NavRoutes.SETTINGS_GROUP` 直接引用它，保证两处只有一个字面量）。 */
    const val PATTERN: String = "settings/{group}"

    /** 路径参数名（`navArgument(ARG)` 与 `arguments?.getString(ARG)` 共用）。 */
    const val ARG: String = "group"

    /** 一级页的卡片点击目标：`settings/{id}`。 */
    fun route(groupId: String): String = "settings/$groupId"

    /** 同上，枚举重载。 */
    fun route(group: SettingsGroup): String = route(group.id)

    /**
     * 路由参数 → 分组。**未知 id / null / 大小写不符 / 带空白一律返回 null**（不抛异常）。
     *
     * 不做 `trim()`、不做 `lowercase()`：路由参数是我们自己拼的，多余的宽容会让
     * 「`settings/ACCOUNT` 也能打开账号页」变成一个没人验证过的隐式行为。
     * 宁可回落一级页 —— 那正是探针要求的兜底。
     */
    fun resolve(raw: String?): SettingsGroup? {
        if (raw.isNullOrEmpty()) return null
        return SettingsGroup.entries.firstOrNull { it.id == raw }
    }

    /** 全部合法路由（单测/诊断用；顺序 = 一级页卡片顺序）。 */
    fun allRoutes(): List<String> = SettingsGroup.entries.map { route(it) }
}
