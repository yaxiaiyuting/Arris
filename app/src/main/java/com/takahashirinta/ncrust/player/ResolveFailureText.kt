/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.2.0 · P0：失败分类 → 用户可读文案（**纯函数**，JVM 可单测）。
 */

package com.takahashirinta.ncrust.player

import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.ui.i18n.Strings

/** 音源 → 该语言的音源名（三处 `when` 合并到一处，避免文案分叉）。 */
internal fun sourceLabelOf(source: MusicSource, strings: Strings): String = when (source) {
    MusicSource.NETEASE -> strings.sourceNetease
    MusicSource.QQMUSIC -> strings.sourceQqMusic
    MusicSource.BILIBILI -> strings.source.sourceBilibili
}

/**
 * 把一次取链失败翻译成**用户能据以行动**的一句话。
 *
 * ## 三条纪律（都来自 v3.2.0 的探针结论）
 *
 * 1. **不说服务端没说过的话。** 只有 [ResolveFailureKind.COPYRIGHT_GONE] 才允许出现
 *    「没有版权」字样，而那一档只有音源**显式声明**时才会被产出（目前只有 ncm 的
 *    `noCopyrightRcmd`）。QQ 侧的失败一律不会被说成无版权（铁律 20）。
 * 2. **不回显服务端文案。** `failure.rawMessage` 只进日志。外部平台的自由文本
 *    既不本地化也不可信 —— 而且它常常带着内部诊断信息。
 * 3. **能给动作的必须给动作。** 「需要登录」要把人引到登录页，
 *    「需要会员」要同时给出「开会员」与「重新登录」两条出路
 *    （票据失效与权益不足在客户端分不开，说死任何一边都会让另一半用户白折腾）。
 *
 * @param otherSource 有另一个可切换的音源时传它，否则传 null。
 *   「换个音源试试」只对「这个源拿不到、另一源可能有」的三类失败追加
 *   （见 [suggestsOtherSource]）—— 对「未登录」给这条提示等于让用户白跑一趟。
 */
fun resolveFailureText(
    failure: ResolveFailure,
    strings: Strings,
    otherSource: MusicSource? = null,
): String {
    val pf = strings.playbackFailure
    val label = sourceLabelOf(failure.source, strings)
    val base = when (failure.kind) {
        ResolveFailureKind.NEED_LOGIN -> pf.needLogin.format(label)
        ResolveFailureKind.NEED_VIP -> pf.needVip
        ResolveFailureKind.NEED_PURCHASE -> pf.needPurchase
        ResolveFailureKind.AUTH_EXPIRED -> pf.authExpired
        ResolveFailureKind.COPYRIGHT_GONE -> pf.copyrightGone
        ResolveFailureKind.REGION_LOCKED -> pf.regionLocked
        ResolveFailureKind.NETWORK -> pf.network
        // 结构性失败（缺 sourceId / 音源未注册 / 音源被关掉）不会走到这里 ——
        // 它是唯一允许跳歌的一类，调用方在那之前就分流掉了。真走到这里时
        // 按「读不懂」处理：**不给用户编一个原因**。
        ResolveFailureKind.UNRESOLVABLE -> pf.unknown
        ResolveFailureKind.UNKNOWN -> pf.unknown
    }
    if (otherSource == null || !suggestsOtherSource(failure.kind)) return base
    return base + "  ·  " + pf.switchSource(sourceLabelOf(otherSource, strings))
}

/**
 * 便捷入口：从音源自己算出「另一个音源」（B 站不在其中 —— 它不可登录，
 * 见 `MusicSource.loginSources`）。拿不到另一个源时退化成不带后缀的那一句。
 */
fun resolveFailureTextFor(
    failure: ResolveFailure,
    strings: Strings,
    loginSourcesOnly: Boolean = true,
): String =
    resolveFailureText(
        failure = failure,
        strings = strings,
        otherSource = if (loginSourcesOnly) otherThanSafe(failure.source) else null,
    )

/** `MusicSource.otherThan` 在只有一个可登录源时返回 null，这里只是收口命名。 */
private fun otherThanSafe(source: MusicSource): MusicSource? = MusicSource.otherThan(source)
