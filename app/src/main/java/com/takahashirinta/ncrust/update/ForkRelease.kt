/*
 * Arris —— 本 Fork（https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 本文件移植自上游 Ncrust 的 c14b1be / 3e462c4（应用内更新），并按本 Fork 的
 * 发布契约改写：仓库是 yaxiaiyuting/Arris，版本线是 vX-gpl，资产名是
 * `Ncrust-v<version>-gpl-<release|debug>.apk`。规则由 spec/fixtures/update 锁住。
 */

package com.takahashirinta.ncrust.update

/**
 * 本 Fork 的发布契约（**纯函数**，不碰 Android / 网络）。
 *
 * 上游 Ncrust 用 `ncrust-android-universal-<version>.apk` 与 `CrateStack-*` 班次 tag；
 * Arris 的 tag 与资产名是另一套：
 *
 *  - tag：`v3.4.13-gpl`（`v` 前缀 + 三段版本 + `-gpl`）；
 *  - 资产：`Ncrust-v3.4.13-gpl-release.apk` / `...-debug.apk`。
 *
 * ## 为什么要按 build type 选资产
 *
 * release 与 debug 是**两张不同的签名证书**。拿 release 资产去更新 debug 安装包，
 * 系统会以 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` 拒装；反过来同理。所以检查阶段就
 * 必须只挑与当前 build type 匹配的资产，签名校验只作为第二道门（[ApkVerification]）。
 *
 * ## 为什么版本解析单独一份
 *
 * `versionName` 是 `3.4.12-gpl`，资产名里的版本段是 `3.4.13`；两者都不能直接交给
 * [AppVersion.parseOrNull]（它只认纯数字，这是与 Windows 共用的严格尺子）。
 * 这里把 `-gpl` 这个**本 Fork 的固定后缀**剥掉，再去用同一把尺子解析，避免为了一个
 * 后缀把严格解析器放宽成「允许任意尾巴」。
 */
object ForkRelease {

    /** GitHub 仓库（应用内检查的唯一源；不加镜像）。 */
    const val REPO = "yaxiaiyuting/Arris"

    /** 本 Fork 的 tag / 资产版本后缀。 */
    const val VERSION_SUFFIX = "-gpl"

    const val VARIANT_RELEASE = "release"
    const val VARIANT_DEBUG = "debug"

    /** 资产名前缀；`v` 可有可无，兼容历史发布的两种写法。 */
    private const val ASSET_PREFIX = "Ncrust-v"

    private val TAG_PATTERN =
        Regex("""^[vV]?(\d{1,9}(?:\.\d{1,9}){0,3})-gpl$""", RegexOption.IGNORE_CASE)

    /**
     * 资产名：`Ncrust-v<version>-gpl-release.apk` / `...-debug.apk`。
     *
     * 只认 `.apk` 结尾，且扩展名必须是最后一段 —— `...-release.apk.sha256` 是校验文件，
     * 不是安装包。版本段最多四段，与 [AppVersion] 一致。
     */
    private val ASSET_PATTERN = Regex(
        // 兼容三种实际出现过的命名（v3.8.5 放宽）：
        //   Arris-v3.8.4-gpl.apk            ← 现在 GitHub Release 用的就是这种
        //   Ncrust-v3.7.11-gpl-release.apk  ← 早期 CI 产物
        //   Arris-v3.7.11-gpl-formal-code99-release.apk 的尾部 `-release.apk`
        // 也接受已发布的 Arris-v3.7.33-lts-gpl.apk；缺省按 release 处理。
        """^(?:[A-Za-z][A-Za-z0-9]*-)?v?(\d{1,9}(?:\.\d{1,9}){0,3})(?:-lts)?-gpl(?:[-.](?:formal[-.][A-Za-z0-9]+|release|debug))*\.apk$""",
        RegexOption.IGNORE_CASE
    )

    /** 当前构建应使用的资产通道。 */
    fun variantFor(debugBuild: Boolean): String =
        if (debugBuild) VARIANT_DEBUG else VARIANT_RELEASE

    /**
     * 从 `versionName` / release tag 解析版本（例如 `3.4.12-gpl`、`v3.4.13-gpl`）。
     *
     * 不接受到 `-gpl` 之外的后缀：`3.4.12-gpl-debug` 这种资产名前缀不是版本号，
     * 应当由 [parseAsset] 按资产规则处理。解析失败返回 null，绝不抛。
     */
    fun parseTag(text: String?): AppVersion? {
        val match = TAG_PATTERN.matchEntire(text?.trim().orEmpty()) ?: return null
        return AppVersion.parseOrNull(match.groupValues[1])
    }

    /** 旧名字：`versionName` 与 tag 共用同一套 `-gpl` 规则。 */
    fun parseVersionName(text: String?): AppVersion? = parseTag(text)

    /** 资产名解析结果。 */
    data class Asset(val version: AppVersion, val variant: String)

    /** 解析本 Fork 的 APK 资产名；不合规（校验文件 / 上游命名 / 版本非法）返回 null。 */
    fun parseAsset(name: String?): Asset? {
        val trimmed = name?.trim().orEmpty()
        val match = ASSET_PATTERN.matchEntire(trimmed) ?: return null
        val version = AppVersion.parseOrNull(match.groupValues[1]) ?: return null
        // 变体缺省 = release：现在发布的资产就叫 `Arris-vX.Y.Z-gpl.apk`，没有 `-release` 后缀。
        // 只有明确写了 `-debug` 的才算 debug 通道。
        val variant = if (trimmed.contains("debug", ignoreCase = true)) VARIANT_DEBUG else VARIANT_RELEASE
        return Asset(version, variant)
    }

    /** 便于诊断：本 Fork 期望的资产名前缀。 */
    fun assetPrefix(): String = ASSET_PREFIX

    /** 本 Fork 的 release 页（浏览器退路）。 */
    fun releasePage(): String = "https://github.com/$REPO/releases"
}
