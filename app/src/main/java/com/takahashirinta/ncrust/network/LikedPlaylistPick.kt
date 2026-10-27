/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.4.5 · P0：从 `/eapi/user/playlist` 的响应里挑出「我喜欢的音乐」的 id。
 * **纯逻辑，无 Android / 无网络依赖，JVM 可单测。**
 */

package com.takahashirinta.ncrust.network

import org.json.JSONArray

/**
 * 「我喜欢的音乐」（红心歌单）的挑选规则 —— **三级精确兜底**。
 *
 * ## 为什么需要单独一个函数（这是一个真踩过的根因）
 *
 * 上游 `de193ff` 记录的真机现象是「收藏后隔天消失」：`like` 的**写入目标**是服务端
 * 固定的红心歌单，而旧读端取的是「**第一个** `specialType != 0` 的歌单」。这个谓词
 * **不唯一** —— 账号里只要还有别的特殊歌单且排在红心歌单之前，读端拿到的就是
 * **另一个歌单**的 `trackIds`，与写入目标不是同一个。
 *
 * 在本仓库里后果更脏：收藏库是**只加不减**的（`SavedSongSync.merge`，云端只负责追加），
 * 所以错歌单的 id 会被当作「云端有、本地没有」而**追加进本地收藏** ——
 * 把别处的歌**导入**「我的收藏」，同时 `liked_ids` 底表 / `likedTotal` 计数 /
 * 「播放全部」全部以错歌单为准。
 *
 * ## 判序为什么是「名称 → specialType == 5 → 任意特殊歌单」
 *
 * 1. **名称最可靠**：「我喜欢的音乐」是服务端固定的名字，**用户改不了**；
 *    繁体/多语言账号下可能只出现「喜欢的音乐」，所以两种子串都收。
 * 2. `specialType == 5` 是红心歌单的稳定取值。
 * 3. 「任意 `specialType != 0`」只作为**个别老账号取值不同**的最后兜底 ——
 *    它正是旧实现唯一的那条判据，保留它只为「宁可读到一个特殊歌单，也不要完全读不到」。
 *
 * 三级都是「取第一个命中者」，因为它们各自已经足够特异。
 *
 * ## 返回 null 的含义
 *
 * 数组为空、或一个候选都没有 ⇒ `null`。**调用方必须把它当作「读取失败」**
 * （`LikedIdsResult.Failure`），绝不能当成「收藏为空」—— 后者会把用户的底表清空。
 */
internal fun pickLikedPlaylistIdFrom(playlists: JSONArray?): Long? {
    if (playlists == null || playlists.length() == 0) return null

    fun nameOf(item: org.json.JSONObject): String = item.optString("name")
    fun looksLikeHeart(name: String): Boolean =
        name.contains("我喜欢的音乐") || name.contains("喜欢的音乐")

    // ① specialType == 5 是**语义**判据（H24）。旧实现把名字放第一位，于是用户自建的
    // 「以前喜欢的音乐精选」会被当成红心歌单，收藏底表就按它读 —— 名字永远不该单独成为身份。
    // 但同为 specialType==5 时，名字仍是最强的消歧依据（官方那条就叫「我喜欢的音乐」）。
    var specialFallback: Long? = null
    var specialNamed: Long? = null
    for (i in 0 until playlists.length()) {
        val item = playlists.optJSONObject(i) ?: continue
        if (item.optInt("specialType") != 5) continue
        if (specialFallback == null) specialFallback = item.optLong("id")
        if (specialNamed == null && looksLikeHeart(nameOf(item))) specialNamed = item.optLong("id")
    }
    specialNamed?.let { return it }

    // ② 名字只在**特殊歌单**里做兜底：官方「我喜欢的音乐」名字不可改，
    // 但同名普通歌单可以随便建，所以必须叠加 specialType != 0。
    for (i in 0 until playlists.length()) {
        val item = playlists.optJSONObject(i) ?: continue
        if (item.optInt("specialType") == 0) continue
        if (looksLikeHeart(nameOf(item))) return item.optLong("id")
    }
    specialFallback?.let { return it }

    // ③ 最后兜底：任意特殊歌单（个别老账号 specialType 取值不同）。
    for (i in 0 until playlists.length()) {
        val item = playlists.optJSONObject(i) ?: continue
        if (item.optInt("specialType") != 0) return item.optLong("id")
    }

    return null
}
