/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.1.0 · B：Wbi 签名。**纯逻辑、无 Android、无 IO**，JVM 可单测。
 */

package com.takahashirinta.ncrust.bili

import java.net.URLEncoder
import java.security.MessageDigest

/**
 * B 站 **Wbi 签名**（v3.1.0 · B）。铁律 25：搜索 API 需要 Wbi 签名，必须实现签名逻辑，不得跳过。
 *
 * ## 为什么不能跳过
 *
 * B 站的搜索接口 `/x/web-interface/wbi/search/type` 在**没有** `w_rid` + `wts` 时
 * 返回 **HTTP 412**（Precondition Failed），而不是「空结果」——
 * 也就是说「不实现签名」在用户视角不是「B 站搜不到」，而是**整个搜索请求失败**。
 * 本仓库的探针把这条钉成了 A/B 对照，见
 * `docs/verification/v3.1.0/bili-research/wbi-signature.md` 与 `EVIDENCE.md`。
 *
 * ## 算法（三步，社区文档 + 本仓库实测一致）
 *
 * ```
 * 1) mixin_key = 按 MIXIN_KEY_ENC_TAB 重排 (img_key + sub_key)，取前 32 个字符
 * 2) 参数按键名字典序排序，剔除 value 里的 !'()* 四个字符，URL 编码后拼成 query
 * 3) w_rid = md5(query + mixin_key)
 * ```
 *
 * `img_key` / `sub_key` 来自 `https://api.bilibili.com/x/web-interface/nav` 的
 * `data.wbi_img.img_url` / `sub_url` —— **那两个是完整 URL**（形如
 * `https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png`），
 * 参与签名的是**去掉路径与扩展名之后的文件名**（[fileStem]）。
 * 直接拿整个 URL 去拼是一个静默错误的实现：签名算得出来、请求也发得出去，
 * 只是服务端一律回 412 —— 所以这一步单独抽成函数并有单测。
 *
 * ## 两个容易写错的地方（都有单测）
 *
 * 1. **`wts` 是秒**，不是毫秒。B 站按秒比对，发毫秒会直接被判过期。
 * 2. **URL 编码用 `URLEncoder.encode`（空格 → `+`）**，与社区参考实现
 *    （Python 的 `urllib.parse.urlencode` 默认 `quote_plus`）逐字一致。
 *    换成 `Uri.encode`（空格 → `%20`）会让**含空格的关键词**签名不匹配 ——
 *    而搜索关键词里带空格是最常见的情况（「周杰伦 晴天」）。
 */
object BiliWbi {

    /**
     * 固定 64 位乱序表（mix-in key table）。
     *
     * ## 关于来源与许可（**必读，v3.1.0 的合规修正**）
     *
     * v3.1.0 的第一版注释把来源写成「`SocialSisterYi/bilibili-API-collect`，MIT/CC 授权」——
     * **两处都不准确**，已据实修正：
     * - 该仓库的许可是 **CC BY-NC 4.0（禁止商业使用）**，与 GPLv3 **不兼容**，
     *   其文档内容**不得复制进本仓库**；
     * - 该仓库已于 2026-01-28 因 B 站委托律所的律师函**永久关停**，
     *   `docs/` 与 `LICENSE` 均已删除（上游链接实测 404）。
     *
     * 因此本表的合法性依据**不是**「抄自某个仓库」，而是：
     * 1. 它是一张**协议常量表** —— 唯一能把 `img_key + sub_key` 映射成 `mixin_key` 的
     *    64 项置换，没有可推导的规律，也不构成可著作权的表达；
     * 2. 本仓库的实现经过**独立自检**：`BiliWbiTest` 用四条固定向量逐字节比对
     *    （`wbi_golden.py` 的官方向量），并且 `wbi_ab.py` 在服务端做过
     *    「无签名 → `-352` / 有效签名 → `code:0` / 伪造 `w_rid` → 又回 `-352`」的 A/B；
     * 3. 全部结论来自本仓库自己的 curl 实测
     *    （`docs/verification/v3.1.0/bili-research/evidence/`，73 个原始响应文件）。
     *
     * ⚠️ 这是一张**常量表**，不得「顺手优化」成生成式写法 —— 它没有可推导的规律。
     */
    val MIXIN_KEY_ENC_TAB: IntArray = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
        33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
        61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
        36, 20, 34, 44, 52,
    )

    /** mixin_key 的长度。社区实现一致取 32。 */
    const val MIXIN_KEY_LEN = 32

    /** 签名时从 value 里剔除的字符（B 站的 WAF 对这四个字符敏感）。 */
    private val FILTERED = charArrayOf('!', '\'', '(', ')', '*')

    /**
     * 从 wbi 的 URL 里取参与签名的「文件名主体」。
     *
     * `https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png` → `7cd084941338484aae1ad9425b84077c`
     *
     * 传进来的已经是裸文件名（没有 `/` 与 `.`）时**原样返回** ——
     * nav 接口的字段形状变过一次（有的实现给裸 key），两种都认，不猜。
     */
    fun fileStem(wbiUrl: String?): String {
        val raw = wbiUrl?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        val afterSlash = raw.substringAfterLast('/')
        return afterSlash.substringBeforeLast('.')
    }

    /**
     * 由 `img_url` / `sub_url` 算出 `mixin_key`。任一为空返回**空串** ——
     * 调用方据此判「key 不可用」，绝不能拿一个半截的 key 去签名（那必然 412）。
     */
    fun mixinKey(imgUrl: String?, subUrl: String?): String {
        val raw = fileStem(imgUrl) + fileStem(subUrl)
        if (raw.length < MIXIN_KEY_ENC_TAB.size) return ""
        val sb = StringBuilder(MIXIN_KEY_LEN)
        for (i in 0 until MIXIN_KEY_LEN) {
            sb.append(raw[MIXIN_KEY_ENC_TAB[i]])
        }
        return sb.toString()
    }

    /**
     * 算出带签名的完整 query（**不含前导 `?`**）。
     *
     * @param params 业务参数。`wts` / `w_rid` 由本函数补齐并**覆盖**入参里的同名键 ——
     *   调用方不需要（也不应该）自己拼。
     * @param mixinKey [mixinKey] 的产物；空串时**原样返回未签名 query**，
     *   让请求照发并由服务端回 412 —— 那样失败是**可见的**（401/412 日志），
     *   而在这里静默返回 null 只会让「搜索没反应」变成一个没有线索的现象。
     * @param nowSec 当前 unix 秒。显式传入是为了单测能钉死一条**固定**的签名向量。
     */
    fun signedQuery(
        params: Map<String, String>,
        mixinKey: String,
        nowSec: Long = System.currentTimeMillis() / 1000L,
    ): String {
        val merged = LinkedHashMap<String, String>()
        params.forEach { (k, v) -> if (k != "wts" && k != "w_rid") merged[k] = v }
        merged["wts"] = nowSec.toString()

        // ① 先剔除敏感字符、再字典序排序。
        //    ⚠️ 顺序不能反：URLEncoder 会把 `'` 编成 `%27`、`(` 编成 `%28`，
        //    编码之后再剔就一个都剔不掉了（而 B 站就是按**原始字符**判的）。
        val filtered = merged.entries
            .sortedBy { it.key }
            .joinToString("&") { (k, v) -> "$k=${encode(filterValue(v))}" }

        if (mixinKey.isEmpty()) return filtered

        // ② w_rid 排在最后（它自己不进排序）。
        val rid = md5Hex(filtered + mixinKey)
        return "$filtered&w_rid=$rid"
    }

    /**
     * URL 编码：`URLEncoder.encode`（空格 → `+`）。
     *
     * 与社区参考实现逐字一致（Python 的 `urllib.parse.urlencode` 默认 `quote_plus`）。
     * 换成 `Uri.encode`（空格 → `%20`）会让**含空格的关键词**签名不匹配 ——
     * 而「周杰伦 晴天」这种带空格的搜索是最常见的用法。
     *
     * 入参**必须已经过 [filterValue]**（见 [signedQuery] 的顺序说明）。
     */
    internal fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** 参数值里剔除 `!'()*`（B 站的规则）。**必须在 URL 编码之前调用。** */
    internal fun filterValue(value: String): String {
        if (value.none { it in FILTERED }) return value
        val sb = StringBuilder(value.length)
        for (c in value) if (c !in FILTERED) sb.append(c)
        return sb.toString()
    }

    /** MD5 → 小写十六进制。用平台的 `MessageDigest`，不引第三方依赖。 */
    fun md5Hex(text: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xff
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0f])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()

    /**
     * 业务 query 的构造入口：解析 mixin_key 并签名。
     *
     * **过滤已经在 [signedQuery] 内部做掉了**，这里不再做一遍 ——
     * 两处都做看起来"更保险"，实际是给下一个读代码的人留下「过滤到底在哪一层」的疑问，
     * 而漏掉它的表现是 412（一个不会说原因的失败）。
     */
    fun buildQuery(
        params: Map<String, String>,
        imgUrl: String?,
        subUrl: String?,
        nowSec: Long = System.currentTimeMillis() / 1000L,
    ): String = signedQuery(params, mixinKey(imgUrl, subUrl), nowSec)
}
