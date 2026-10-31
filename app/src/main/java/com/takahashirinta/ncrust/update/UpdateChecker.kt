/*
 * Arris —— 本 Fork（https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 移植自上游 Ncrust c14b1be / 3e462c4，改写点：
 *  - 仓库源改为 yaxiaiyuting/Arris；
 *  - 资产名按本 Fork 的 `Ncrust-v<version>-gpl-<release|debug>.apk` 解析；
 *  - 按当前 build type 只挑同名签名的资产（debug 不更新 release，反之亦然）。
 */

package com.takahashirinta.ncrust.update

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** 一次可用更新：目标版本、要下载的 APK，以及更新页要展示的元数据。 */
data class UpdateInfo(
    val version: AppVersion,
    val assetName: String,
    val assetUrl: String,
    val assetSize: Long,
    val releaseTag: String?,
    val releasePageUrl: String?,
    val publishedAt: String?,
    val body: String?,
    /**
     * GitHub 资产 `id`。发布契约允许**同名资产原地替换**，此时 URL / 名字都不变，
     * 只有 id 会换——所以断点续传必须以它为稳定身份，否则旧前缀 + 新后缀会拼出坏包。
     * 老资产 / 夹具没给 id 时为 null。
     */
    val assetId: String? = null
)

/**
 * 稳定目标标识：优先 GitHub 资产 id，其次资产 URL。
 *
 * 更新页据此门控「显示 / 安装」——绝不能把 A 的已下载文件装到 B 的页面上。
 */
val UpdateInfo.targetKey: String get() = assetId ?: assetUrl

/** 两份 [UpdateInfo] 是否指向同一个资产（同一目标）。 */
fun UpdateInfo.matchesTarget(other: UpdateInfo): Boolean = targetKey == other.targetKey

/**
 * 从 GitHub Releases API 的 JSON 里找出适用于本 Fork 的更新。**纯函数**
 * （Gson 树 API，不反射绑定 model），网络由 [UpdateService] 负责。
 *
 * 规则（由 `spec/fixtures/update/releases.json` 锁住）：
 *  - 只认 `Ncrust-v<version>-gpl-<variant>.apk`，扩展名必须是 `.apk`；
 *  - 忽略 `draft` 与 `prerelease`，不依赖 GitHub 的「Latest」徽章；
 *  - 跨所有可见 release 取**严格高于当前版本**的最高者；
 *  - 版本比较用资产名里的版本（三段 / 四段），`versionCode` 的二次门控在
 *    下载后的 [ApkVerification] 里做——GitHub API 不暴露 APK 内的 versionCode。
 *
 * ## 为什么必须按 variant 过滤
 *
 * 同一个版本会同时发 `-release` 与 `-debug` 两个资产，两者签名不同。release 构建
 * 只能挑 release 资产；否则会下载一个系统必然拒装的包（或者更糟：让用户以为
 * 有可用更新，下完才看到签名错误）。
 */
object UpdateChecker {

    /** 默认资产通道。调用方应传 [ForkRelease.variantFor] 的结果。 */
    const val DEFAULT_VARIANT = ForkRelease.VARIANT_RELEASE

    /**
     * 发布通道判定（v3.8.3）。
     *
     * - **LTS 线**（当前 < 3.8）：只接受同一 minor 线的版本，外加 `lts-*` 滚动条目；
     * - **主线**（当前 ≥ 3.8）：接受所有普通版本条目，忽略 `lts-*`。
     *
     * 低于 3.8 的设备看不到 3.8+ 的包，避免「点了更新却装不上」。
     */
    internal fun sameChannel(tag: String?, candidate: AppVersion, current: AppVersion): Boolean {
        val isLtsEntry = tag?.startsWith("lts-") == true
        // 只有**被声明为 LTS 的那条线**受限（当前是 3.7）；更早的历史版本仍按主线处理，
        // 否则 3.4 的用户会被卡在原地永远升不到 3.5（真机 fixture 用例抓到过）。
        val currentIsLts = current.major * 100 + current.minor == LTS_LINE
        if (isLtsEntry) return currentIsLts
        if (!currentIsLts) return true
        return candidate.major * 100 + candidate.minor == current.major * 100 + current.minor
    }

    /** 当前被声明为 LTS 的版本线（3.7）；新的 LTS 线出现时改这里。 */
    private const val LTS_LINE = 307

    /** 解析失败 / 无更新 / 不是数组时返回 null；绝不抛。 */
    fun find(
        releasesJson: String?,
        current: AppVersion?,
        variant: String = DEFAULT_VARIANT
    ): UpdateInfo? {
        if (releasesJson.isNullOrBlank() || current == null) return null

        val root = runCatching { JsonParser.parseString(releasesJson) }.getOrNull() ?: return null
        if (!root.isJsonArray) return null

        val wantedVariant = variant.lowercase()
        var best: UpdateInfo? = null
        for (element in root.asJsonArray) {
            if (!element.isJsonObject) continue
            val release = element.asJsonObject
            // 草稿与预发布都不可见：草稿是发布者还没发出来的，预发布是拿来试的。
            if (release.boolOrFalse("draft")) continue
            if (release.boolOrFalse("prerelease")) continue

            val hit = bestAssetOf(release, wantedVariant) ?: continue
            if (hit.version <= current) continue
            // 与 Linux 内核同样的「线」概念：3.7.x（LTS）只在该线内更新，不会被推到 3.8.x
            // —— 3.8.x 的 minSdk 是 29，Android 7 设备根本装不上。
            // 反过来主线用户不会被 `lts-*` 的滚动条目干扰（那条的附件版本更低，本来也不会中）。
            if (!sameChannel(release.stringOrNull("tag_name"), hit.version, current)) continue
            if (best == null || hit.version > best.version) {
                best = UpdateInfo(
                    version = hit.version,
                    assetName = hit.name,
                    assetUrl = hit.url,
                    assetSize = hit.size,
                    releaseTag = release.stringOrNull("tag_name"),
                    releasePageUrl = release.stringOrNull("html_url"),
                    publishedAt = release.stringOrNull("published_at"),
                    body = release.stringOrNull("body"),
                    assetId = hit.id
                )
            }
        }
        return best
    }

    /** 一个 release 里版本最高的合规资产；没有则 null。 */
    private fun bestAssetOf(release: JsonObject, variant: String): AssetHit? {
        val assets = release.arrayOrNull("assets") ?: return null
        var hit: AssetHit? = null
        for (element in assets) {
            if (!element.isJsonObject) continue
            val asset = element.asJsonObject
            val name = asset.stringOrNull("name") ?: continue
            val parsed = ForkRelease.parseAsset(name) ?: continue
            if (parsed.variant != variant) continue

            val url = asset.stringOrNull("browser_download_url") ?: continue
            if (hit == null || parsed.version > hit.version) {
                hit = AssetHit(
                    parsed.version,
                    name,
                    url,
                    asset.longOrZero("size"),
                    asset.idOrNull("id")
                )
            }
        }
        return hit
    }

    private data class AssetHit(
        val version: AppVersion,
        val name: String,
        val url: String,
        val size: Long,
        val id: String?
    )

    private fun JsonObject.arrayOrNull(name: String): JsonArray? {
        val element = get(name) ?: return null
        return if (element.isJsonArray) element.asJsonArray else null
    }

    private fun JsonObject.stringOrNull(name: String): String? {
        val element = get(name) ?: return null
        return if (element.isJsonPrimitive && element.asJsonPrimitive.isString) element.asString else null
    }

    private fun JsonObject.boolOrFalse(name: String): Boolean {
        val element = get(name) ?: return false
        return element.isJsonPrimitive && runCatching { element.asBoolean }.getOrDefault(false)
    }

    private fun JsonObject.longOrZero(name: String): Long {
        val element = get(name) ?: return 0L
        return if (element.isJsonPrimitive) runCatching { element.asLong }.getOrDefault(0L) else 0L
    }

    /** 资产 `id` 在 GitHub API 里是数字；统一转成字符串做身份比较，空 / 非标量返回 null。 */
    private fun JsonObject.idOrNull(name: String): String? {
        val element = get(name) ?: return null
        if (!element.isJsonPrimitive) return null
        val primitive = element.asJsonPrimitive
        return when {
            primitive.isNumber -> primitive.asLong.toString()
            primitive.isString -> primitive.asString.takeIf { it.isNotBlank() }
            else -> null
        }
    }
}
