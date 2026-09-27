/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.6.2 · P0：**「这个 id 属于哪个源」的唯一判据**。
 * 纯逻辑，无 Android 依赖，JVM 可单测。
 */

package com.takahashirinta.ncrust.source

/**
 * 音源内**字符串身份**的值域判据（v2.6.2 · P0 抽出的唯一落点）。
 *
 * ## 为什么必须只有一份
 *
 * v2.6.1 修「转到歌手」时，这条规则第一次被写下来，落在
 * [`ArtistNavigator.idDomainMatches`] 里 —— 名字里带 `Artist`，于是 v2.6.2 修
 * 「转到专辑」时最自然的动作是**再抄一份**。抄一份的代价不是多几行，而是
 * **两处会漂移的规则**：
 *
 * - 值域判据是「用错源就必然失败」的**唯一**结构性防线（见下）。它一旦在两处不一致，
 *   表现是「艺人跳得对、专辑跳错」这种只在特定入口复现的形状 —— 正是 v2.6.1 的
 *   `AMBIGUOUS_NUMERIC_ID` 注释点名要避免的。
 * - 本仓库对这类事已有明确纪律（v2.4.0 铁律 2：阈值/判据只能有一处定义，
 *   不许在调用方各写一份）。
 *
 * 所以把规则搬到这里，两个 Navigator 都委托它。**行为逐字不变** ——
 * v2.6.1 的 `ArtistNavigatorTest` 全部用例原样覆盖了这次搬迁。
 *
 * ## 它到底判什么
 *
 * 两个源的"身份"形状**结构性不同**，这不是约定、是接口事实：
 *
 * | 源 | 页面身份 | 形状 | 谁在用 |
 * |---|---|---|---|
 * | 网易云 | 十进制 id | `"6452"` / `"18905"` | `api/artist/{id}`、`api/v1/album/{id}` |
 * | QQ 音乐 | base62 mid | `"0025NhlN2yWrP4"`（singerMID）/ `"000MkMni19ClKG"`（albumMID） | `musicu.fcg` 的 `singermid` / `albumMid` 参数 |
 *
 * 于是「传错域」不需要靠调用方自觉，在闸门上就会失败：
 * **QQ 的数字 `singerID`(4558) / `albumID`(22276) 在 QQ 域里不是合法身份**
 * （它是纯数字且落在网易云值域内），因此必然被拦下。
 * 这正是 v2.6.1（周杰伦 `4558` → 马洪波）与 v2.6.2（陈奕迅 `22276` → 陈小云）
 * 两处 P0 的共同形状。
 *
 * ## 一个容易踩的坑：QQ 的 `pmid` **不是**身份
 *
 * QQ 搜索响应里专辑对象同时给三个候选：
 * `id`（数字）、`mid`（albumMID，`000MkMni19ClKG`）、
 * `pmid`（**封面照片** id，`000MkMni19ClKG_5` —— 尾部 `_N` 是封面序号）。
 * 实测服务端**碰巧能容忍**把 `pmid` 当 `albumMid` 传（它内部会剥掉 `_N`），
 * 但那是服务端的宽容、不是契约；而 `_` 不在 base62 字符集里，
 * 所以本闸门**会把 `pmid` 挡下** —— 这是有意的：
 * 「封面照片 id」与「专辑身份」是两件事，能用不能混。
 */
object SourceIdDomain {

    /**
     * 网易云 id 的值域上界（不含）。
     *
     * 取 `2^40`：网易云的 id 是十进制百万~十亿量级，远小于它；而
     * [SourceIds.QQ_ID_FLAG]（`2^62`）造的合成 id 远大于它。
     * 于是「这个 id 到底属于哪个值域」是一个**结构性**判据，不是启发式。
     */
    const val NETEASE_ID_MAX: Long = 1L shl 40

    /** QQ mid 的合法字符集（base62）。`_` / `-` / `=` 都不在其中 —— 见 KDoc 的 `pmid` 一节。 */
    private val BASE62 = Regex("^[0-9A-Za-z]+$")

    /** QQ mid 的长度下界。真实 `singerMID` / `albumMID` 实测都是 14 位 base62。 */
    const val QQ_MID_MIN_LEN: Int = 5

    /** QQ mid 的长度上界。留足余量，同时不让任意长串蒙混过关。 */
    const val QQ_MID_MAX_LEN: Int = 64

    /**
     * 这个 id 是不是 [source] 域内的合法**字符串**身份。纯函数，JVM 可单测。
     *
     * - **网易云**：纯十进制、`1 ..< ` [NETEASE_ID_MAX]。这就是 `api/artist/{id}` /
     *   `api/v1/album/{id}` 吃的形状。
     * - **QQ 音乐**：base62 的 mid（`singerMID` / `albumMID`），长度
     *   [QQ_MID_MIN_LEN]`..`[QQ_MID_MAX_LEN]，**且不能是一个网易云值域内的纯数字**
     *   —— 后者正是「QQ 数字 id 被当成 mid 用」的形状。真实 mid 是 14 位 base62，
     *   恰好落在 [NETEASE_ID_MAX] 以内的纯数字概率约 `62^-14`，可以忽略；
     *   而把这条写死成判据，是为了让「传错域」**必然**在闸门上失败。
     */
    fun matches(source: MusicSource, id: String?): Boolean {
        val v = id?.trim().orEmpty()
        if (v.isEmpty()) return false
        return when (source) {
            MusicSource.NETEASE ->
                v.all { it in '0'..'9' } && v.toLongOrNull()?.let { it in 1 until NETEASE_ID_MAX } == true

            MusicSource.QQMUSIC -> {
                if (v.length < QQ_MID_MIN_LEN || v.length > QQ_MID_MAX_LEN) return false
                if (!BASE62.matches(v)) return false
                // 纯数字且落在网易云值域 ⇒ 这是 QQ 的数字 id，不是 mid。
                val asLong = v.toLongOrNull()
                !(asLong != null && asLong in 1 until NETEASE_ID_MAX)
            }

            /**
             * v3.1.0 · B：B 站的页面身份就是 **auid**（十进制数字）。
             *
             * ⚠️ **它与网易云的值域重叠**（实测 auid 2.3×10⁷ 落在网易云的量级里），
             * 所以这一条分支**不能**用来判「这个数字属于哪个源」—— 值域在这里
             * 结构性地不可用。真正承担隔离的是 [SourceIds.BILI_ID_FLAG]：应用内部的
             * 数字 id 一律是 `BILI_ID_FLAG or auid`，而**字符串**身份（本函数的入参）
             * 只在「已经知道音源是 B 站」的路径上出现（搜索结果的 sid、音频区 URI 解析）。
             * 换句话说：本分支回答的是「这个串在 B 站域内合不合法」，不是「它是不是 B 站」。
             */
            MusicSource.BILIBILI ->
                v.all { it in '0'..'9' } && v.toLongOrNull()?.let { it in 1 until NETEASE_ID_MAX } == true
        }
    }
}
