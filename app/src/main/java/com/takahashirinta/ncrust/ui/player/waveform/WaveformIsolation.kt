/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player.waveform

/**
 * v3.2.2：波形**渲染侧的异常隔离**（纯逻辑，JVM 直测）。
 *
 * ## 为什么要有这个文件（而不是在各处写 try/catch）
 *
 * 探针 §2.1 的结论很明确：这条链路上**只有音频线程那一段有隔离**
 * （`TransparentWaveformSink.handleBuffer` 的 `catch (Throwable)`，被三条单测钉住），
 * 而**渲染/绘制这一段一处兜底都没有**，并且 `PlayerCard.kt` 明确写了
 * 「@Composable 里不再包 try/catch（会破坏重组语义）」。铁律 28 要求的
 * 「波形是每帧更新的组件，任何改动必须异常隔离」因此必须落在**另外两层**上：
 *
 *  1. **组合期**：算 HCT 三角色的 `BandColorRoles.paletteFor` 自带 try/catch（已经落了）；
 *  2. **draw 期**：本文件的 [isolateFrame] —— 一帧的绘制包在里面，抛异常就**丢这一帧**。
 *
 * 关键区别：`Canvas { … }` 的 lambda **不是** @Composable 作用域（它在 draw 阶段执行），
 * 所以在这里 try/catch 不会碰到"重组语义"那条禁忌 —— 它可以安全地吞掉这一帧的失败。
 *
 * ## 有界（铁律 5）
 *
 * 失败**不重试、不累积**：这一帧什么都不画，下一帧照常重来（状态机里没有任何"待重试队列"）。
 * 计数器只用于诊断，且写入侧不分配、不加锁（UI 线程单写者）。
 *
 * @return 正常返回 block 的结果；block 抛异常时返回 null，并调用一次 [onFailure]。
 */
internal inline fun <T> isolateFrame(onFailure: () -> Unit, block: () -> T): T? = try {
    block()
} catch (t: Throwable) {
    // 只丢这一帧：不向上抛（否则会冒泡进 Compose 的绘制阶段，把整棵播放器子树打挂 ——
    // 正是 v2.2.1 那条 P0 的形状），也不在这里打日志（每帧都可能触发 ⇒ 日志本身就是雪崩）。
    onFailure()
    null
}
