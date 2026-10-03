/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.1.0 · E：qm 在「未登录」时是否仍然参与搜索。
 */

package com.takahashirinta.ncrust.qq

/**
 * qm 的能力开关（v2.1.0 · E）。
 *
 * ## 为什么未登录也参与搜索
 *
 * 实测（2026-09）：qm**匿名**就能搜索、能拿歌词，只有取播放 URL 会被服务端拒绝
 * （空 `purl` + `result=104003`）。所以未登录时把 QQ 从搜索里摘掉是净损失：
 * 用户既搜不到、也无从知道「这里有歌、登录就能放」。
 *
 * 保留它的代价是搜索结果里会出现点不开的歌 —— 所以 UI 上必须能看出音源
 * （见 `SongCard` 的音源标识），让「为什么这首放不出来」有答案。
 */
object QqAccountAvailability {

    /**
     * 未登录时是否允许 qm 参与搜索。
     *
     * 默认 `true`（见上面的理由）。留成常量而不是直接写 `true`，是为了让这个**产品决策**
     * 有一个名字与一处开关 —— 将来若发现匿名搜索触发限流、影响体验，改这里一处即可。
     */
    const val allowAnonymousSearch: Boolean = true
}
