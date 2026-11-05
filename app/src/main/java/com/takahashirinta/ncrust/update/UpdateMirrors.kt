/*
 * Arris —— 本 Fork 的修改部分，Copyright (c) 2026 yaxiaiyuting，GPLv3。
 */
package com.takahashirinta.ncrust.update

/**
 * 下载镜像（4.0.9）。
 *
 * ## 为什么要多源
 *
 * 官网直链走 Cloudflare，国内线路会**忽快忽慢**（同一个包 200KB/s 和 2MB/s 都可能）。
 * 这不是"某个源坏了"，而是单源单线路的固有波动。做法有两半：
 *  ① 分段下载时把不同段**分给不同镜像**（同时用满几条线路）；
 *  ② 某一段在某镜像上失败时，**换下一条镜像重试这一段**，而不是整批回落单流。
 *
 * ## 镜像从哪来
 *
 * - `site`：本站 `/get/` 直链（Cloudflare）
 * - `ghproxy`：GitHub Release 的公共反代（免费、无需备案，存活时间不定 ⇒ 只做备选）
 * - `gitee`：[GiteeMirror] 里配置的仓库附件地址（需实名建仓后填；国内直连最快）
 *
 * 所有镜像必须指向**同一个 asset**（同一版本、同一文件名），否则拼出来的包是坏的 ——
 * 分段校验会核对 `Content-Range` 的起止与总长，对不上立刻整批失败。
 */
internal object UpdateMirrors {

    /** GitHub Release 资产地址（未反代）。 */
    fun githubAsset(tag: String?, assetName: String?): String? {
        if (tag.isNullOrBlank() || assetName.isNullOrBlank()) return null
        return "https://github.com/yaxiaiyuting/Arris/releases/download/$tag/$assetName"
    }

    /** ghproxy 类公共反代：前缀 + 原始 GitHub 地址。顺序即优先级。 */
    private val GH_PROXIES = listOf(
        "https://ghfast.top/",
        "https://gh-proxy.com/",
        "https://ghproxy.net/",
    )

    /**
     * Gitee 镜像（可选）。
     *
     * 用法：在 Gitee 建仓 → 发 Release → 把附件地址填到这里（形如
     * `https://gitee.com/<user>/<repo>/releases/download/<tag>/<file>`）。
     * 留空表示未配置，不影响其它镜像。
     */
    const val GITEE_BASE: String = ""

    fun giteeAsset(assetName: String?): String? {
        if (GITEE_BASE.isBlank() || assetName.isNullOrBlank()) return null
        return GITEE_BASE.trimEnd('/') + "/" + assetName
    }

    /**
     * 组装下载源列表：本站 → Gitee（若配置）→ ghproxy 若干。
     *
     * 顺序有意义：本站是唯一"一定能拿到最新版"的源，排第一；
     * 反代排在最后，因为它们随时可能失效。
     */
    fun sources(
        siteUrl: String,
        tag: String?,
        assetName: String?,
    ): List<String> {
        val out = LinkedHashSet<String>()
        if (siteUrl.isNotBlank()) out += siteUrl
        giteeAsset(assetName)?.let { out += it }
        githubAsset(tag, assetName)?.let { gh ->
            GH_PROXIES.forEach { prefix -> out += prefix + gh }
        }
        return out.toList()
    }
}
